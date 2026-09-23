package io.github.aritouma1205.quietintentlauncher.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import java.io.File
import java.io.IOException
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

/** Observable settings load state. */
sealed interface SettingsState {
    /** Settings not yet read from disk. */
    data object Loading : SettingsState

    /** Valid settings are in use. */
    data class Ready(val data: SettingsData) : SettingsState

    /**
     * The stored file could not be read (corrupted or a newer schema).
     * The original file is left untouched; writes are refused until the
     * user recovers or reinitializes. The session runs on defaults.
     */
    data class Degraded(val message: String) : SettingsState
}

/**
 * Single-owner store for [SettingsData] (design 14.1, A13 foundation).
 *
 * The file is read once through the serializer before a DataStore is opened.
 * If it is unreadable the session degrades *without* creating a DataStore for
 * that file, so [retry] and [resetToDefaults] can still open a fresh one —
 * AndroidX DataStore does not allow a second instance per file.
 *
 * While degraded, [update] refuses to write: the original file is preserved
 * and the session runs on in-memory defaults.
 */
class SettingsStore(
    private val scope: CoroutineScope,
    private val serializer: SettingsSerializer,
    private val fileProvider: () -> File,
    private val dataStoreFactory: (CoroutineScope) -> DataStore<SettingsData>,
) {
    private val _state = MutableStateFlow<SettingsState>(SettingsState.Loading)
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    @Volatile
    private var degraded: Boolean = false

    private var dataStore: DataStore<SettingsData>? = null

    /**
     * Scope that owns the current [dataStore]. Only one DataStore may be
     * active per file, so this scope is cancelled and joined before any
     * replacement instance is created.
     */
    private var dataStoreScope: CoroutineScope? = null
    private var collectJob: Job? = null

    fun start() {
        collectJob?.cancel()
        _state.value = SettingsState.Loading
        collectJob = scope.launch {
            val file = fileProvider()
            if (file.exists() && file.length() > 0L) {
                try {
                    file.inputStream().use { serializer.readFrom(it) }
                } catch (e: CorruptionException) {
                    degraded = true
                    _state.value =
                        SettingsState.Degraded(e.message ?: "settings unreadable")
                    return@launch
                }
            }
            openDataStore()
        }
    }

    /**
     * Applies [transform] to the persisted settings.
     * Returns false (persisting nothing) while degraded or when the write
     * itself fails; callers keep their draft and report the failure.
     */
    suspend fun update(transform: (SettingsData) -> SettingsData): Boolean {
        if (degraded) return false
        val store = dataStore ?: return false
        return try {
            store.updateData(transform)
            true
        } catch (e: CorruptionException) {
            degrade(e.message)
            false
        } catch (e: IllegalStateException) {
            degrade(e.message)
            false
        } catch (e: IOException) {
            false
        }
    }

    /** Re-reads the original file. Keeps it if still unreadable. */
    fun retry() {
        degraded = false
        start()
    }

    /**
     * Recovery: replaces the file with defaults. Only invoked after the
     * user confirms initialization; a failed write leaves the file as is.
     * The previous DataStore is fully closed before the file is replaced
     * and a fresh instance is opened.
     */
    suspend fun resetToDefaults(): Boolean {
        // Stop collecting and release the file before replacing it.
        collectJob?.cancel()
        closeDataStore()
        val file = fileProvider()
        val written = try {
            writeAtomically(file, serializer.serialize(SettingsData()))
            true
        } catch (e: IOException) {
            false
        }
        if (!written) return false
        degraded = false
        _state.value = SettingsState.Loading
        collectJob = scope.launch { openDataStore() }
        return true
    }

    /**
     * Releases the current DataStore's file registration. Must not touch
     * [collectJob] — it is also invoked from inside that coroutine.
     */
    private suspend fun closeDataStore() {
        dataStore = null
        dataStoreScope?.let {
            it.cancel()
            it.coroutineContext[Job]?.join()
        }
        dataStoreScope = null
    }

    private suspend fun openDataStore() {
        // A file may only ever be held by one DataStore; release the old
        // instance's scope before opening a replacement (retry / reset).
        closeDataStore()
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = try {
            dataStoreFactory(storeScope)
        } catch (e: IllegalStateException) {
            storeScope.cancel()
            degrade(e.message)
            return
        }
        dataStoreScope = storeScope
        dataStore = store
        store.data
            .catch { e ->
                when (e) {
                    is CorruptionException, is IOException,
                    is IllegalStateException,
                    -> degrade(e.message)
                    else -> throw e
                }
            }
            .collect {
                degraded = false
                _state.value = SettingsState.Ready(it)
            }
    }

    private fun degrade(message: String?) {
        degraded = true
        _state.value = SettingsState.Degraded(message ?: "settings unreadable")
    }

    private fun writeAtomically(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}
