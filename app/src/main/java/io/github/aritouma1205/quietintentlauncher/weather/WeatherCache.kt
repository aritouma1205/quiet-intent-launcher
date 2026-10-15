package io.github.aritouma1205.quietintentlauncher.weather

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import io.github.aritouma1205.quietintentlauncher.settings.WeatherLocation
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Persisted weather cache (design 8.2, 14.1). A separate disposable file
 * holds at most one snapshot of the last successful reading; it is deleted
 * together with the region setting when weather is disabled.
 */
@Serializable
data class WeatherCacheData(
    val schemaVersion: Int = CURRENT_VERSION,
    val regionKey: String? = null,
    val temperatureCelsius: Double = 0.0,
    val weatherCode: Int = 0,
    val isDay: Boolean = true,
    val fetchedAtElapsedMs: Long = 0L,
    val fetchedAtWallMs: Long = 0L,
    val bootMarkerMs: Long = 0L,
) {
    fun toSnapshot(): WeatherSnapshot? = regionKey?.let {
        WeatherSnapshot(
            regionKey = it,
            temperatureCelsius = temperatureCelsius,
            weatherCode = weatherCode,
            isDay = isDay,
            fetchedAtElapsedMs = fetchedAtElapsedMs,
            fetchedAtWallMs = fetchedAtWallMs,
            bootMarkerMs = bootMarkerMs,
        )
    }

    companion object {
        const val CURRENT_VERSION = 1

        fun of(snapshot: WeatherSnapshot): WeatherCacheData = WeatherCacheData(
            regionKey = snapshot.regionKey,
            temperatureCelsius = snapshot.temperatureCelsius,
            weatherCode = snapshot.weatherCode,
            isDay = snapshot.isDay,
            fetchedAtElapsedMs = snapshot.fetchedAtElapsedMs,
            fetchedAtWallMs = snapshot.fetchedAtWallMs,
            bootMarkerMs = snapshot.bootMarkerMs,
        )
    }
}

/**
 * JSON serializer for the weather cache. The cache is disposable data, so
 * an unreadable file is simply deleted by the store instead of degrading —
 * the next successful fetch rebuilds it.
 */
class WeatherCacheSerializer(
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : Serializer<WeatherCacheData> {

    override val defaultValue = WeatherCacheData()

    override suspend fun readFrom(input: InputStream): WeatherCacheData {
        val text = try {
            input.readBytes().decodeToString()
        } catch (e: Exception) {
            throw CorruptionException("Failed to read the weather cache.", e)
        }
        if (text.isBlank()) return defaultValue
        val parsed = try {
            json.decodeFromString(WeatherCacheData.serializer(), text)
        } catch (e: SerializationException) {
            throw CorruptionException("Weather cache is not readable.", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("Weather cache is not readable.", e)
        }
        if (parsed.schemaVersion > WeatherCacheData.CURRENT_VERSION) {
            throw CorruptionException("Weather cache is from a newer version.")
        }
        return parsed
    }

    override suspend fun writeTo(t: WeatherCacheData, output: OutputStream) {
        output.write(json.encodeToString(WeatherCacheData.serializer(), t).toByteArray())
    }
}

/**
 * Single-owner store for the weather cache — same open/collect skeleton as
 * RecentStore (pre-read before opening a DataStore, one instance per file),
 * but the file holds one snapshot instead of a list.
 */
class WeatherCacheStore(
    private val scope: CoroutineScope,
    private val serializer: WeatherCacheSerializer,
    private val fileProvider: () -> File,
    // Suspending lets tests gate the open timing (Issue #42).
    private val dataStoreFactory: suspend (CoroutineScope) -> DataStore<WeatherCacheData>,
) {
    private val _snapshot = MutableStateFlow<WeatherSnapshot?>(null)

    /** The cached reading, or null when nothing valid is stored. */
    val snapshot: StateFlow<WeatherSnapshot?> = _snapshot.asStateFlow()

    @Volatile
    private var dataStore: DataStore<WeatherCacheData>? = null

    /**
     * Publishes the outcome of the current open attempt to writers that
     * arrived before the store was ready (Issue #42). Completed with the
     * store once it opens, with null when the open failed or the attempt
     * ended without producing one. Null until [start] is first called —
     * writes then fail fast instead of waiting on an open that never
     * comes (the documented await contract).
     */
    @Volatile
    private var openSignal: CompletableDeferred<DataStore<WeatherCacheData>?>? = null
    private var dataStoreScope: CoroutineScope? = null
    private var collectJob: Job? = null

    internal val isOpen: Boolean get() = dataStore != null

    fun start() {
        collectJob?.cancel()
        // A collect job cancelled before its first dispatch never runs —
        // finally included — so the superseding start() must release the
        // previous generation's signal itself or its writers would hang.
        openSignal?.complete(null)
        val signal = CompletableDeferred<DataStore<WeatherCacheData>?>()
        openSignal = signal
        collectJob = scope.launch {
            try {
                closeDataStore()
                val file = fileProvider()
                if (file.exists()) {
                    try {
                        file.inputStream().use { serializer.readFrom(it) }
                    } catch (e: CorruptionException) {
                        // Disposable cache: discard and start empty.
                        file.delete()
                    } catch (e: IOException) {
                        file.delete()
                    }
                }
                openDataStore(signal)
            } finally {
                // Reached only when the job ends without the open path
                // publishing a result (e.g. superseded by another start):
                // release waiting writers instead of hanging them on a
                // store that never arrives.
                signal.complete(dataStore)
            }
        }
    }

    /**
     * Persists [snapshot] only when it still belongs to [expectedRegion] —
     * the caller re-checks the selection right before calling, and the
     * transform drops the write if a region change landed in between
     * (design 8.2: a stale response must never populate the new region).
     * Once [start] has been called this waits for the open to finish so an
     * early write is not silently dropped (Issue #42); it returns false —
     * never hanging — when [start] was never called or the open failed.
     */
    suspend fun save(snapshot: WeatherSnapshot, expectedRegion: WeatherLocation?): Boolean {
        val store = awaitStore() ?: return false
        return try {
            store.updateData { current ->
                if (expectedRegion == null || expectedRegion.key != snapshot.regionKey) {
                    current
                } else {
                    WeatherCacheData.of(snapshot)
                }
            }
            true
        } catch (e: CancellationException) {
            // Cancellation is not a write failure — propagate it
            // (Issue #42: CancellationException extends
            // IllegalStateException, so it must be rethrown first).
            throw e
        } catch (e: CorruptionException) {
            fileProvider().delete()
            false
        } catch (e: IOException) {
            false
        } catch (e: IllegalStateException) {
            false
        }
    }

    /** Deletes the cached reading (weather disabled / region cleared). */
    suspend fun clear(): Boolean {
        val store = awaitStore() ?: return false
        return try {
            store.updateData { WeatherCacheData() }
            _snapshot.value = null
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: CorruptionException) {
            fileProvider().delete()
            false
        } catch (e: IOException) {
            false
        } catch (e: IllegalStateException) {
            false
        }
    }

    /**
     * The open DataStore, awaiting a [start] whose open is still in flight
     * so an early write is not silently dropped (Issue #42). Null —
     * immediately, never blocking — when [start] was never called or the
     * open failed; callers then report failure exactly as before.
     */
    private suspend fun awaitStore(): DataStore<WeatherCacheData>? =
        dataStore ?: openSignal?.await()

    private suspend fun closeDataStore() {
        dataStore = null
        dataStoreScope?.let {
            it.cancel()
            it.coroutineContext[Job]?.join()
        }
        dataStoreScope = null
    }

    private suspend fun openDataStore(
        signal: CompletableDeferred<DataStore<WeatherCacheData>?>,
    ) {
        closeDataStore()
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = try {
            dataStoreFactory(storeScope)
        } catch (e: CancellationException) {
            storeScope.cancel()
            signal.complete(null)
            throw e
        } catch (e: IllegalStateException) {
            storeScope.cancel()
            // The open failed: release waiting writers so they report a
            // normal failure instead of hanging.
            signal.complete(null)
            return
        }
        dataStoreScope = storeScope
        dataStore = store
        signal.complete(store)
        store.data
            .catch { e ->
                when (e) {
                    is CancellationException -> throw e
                    is CorruptionException, is IOException -> fileProvider().delete()
                    is IllegalStateException -> Unit
                    else -> throw e
                }
            }
            .collect { data -> _snapshot.value = data.toSnapshot() }
    }
}
