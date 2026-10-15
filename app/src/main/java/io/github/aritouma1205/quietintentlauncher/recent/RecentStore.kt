package io.github.aritouma1205.quietintentlauncher.recent

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import io.github.aritouma1205.quietintentlauncher.settings.StoredTarget
import java.io.File
import java.io.IOException
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

/**
 * Single-owner store for the 「最近」 history (design 9, 14.1).
 *
 * Same shape as SettingsStore — the file is pre-read through the serializer
 * before a DataStore is opened, and at most one DataStore per file exists —
 * but history is disposable data, not configuration: a file that cannot be
 * read is deleted and the session starts empty instead of degrading to
 * protect a corrupt original. There is nothing worth recovering; the list
 * is rebuilt through normal use.
 */
class RecentStore(
    private val scope: CoroutineScope,
    private val serializer: RecentSerializer,
    private val fileProvider: () -> File,
    private val clock: () -> Long = System::currentTimeMillis,
    // The factory stays last so callers can use the trailing-lambda form,
    // same as SettingsStore. Suspending lets tests gate the open timing.
    private val dataStoreFactory: suspend (CoroutineScope) -> DataStore<RecentData>,
) {
    private val _entries = MutableStateFlow<List<RecentEntry>>(emptyList())

    /**
     * The current history, always normalized: newest first, the same target
     * never duplicated, entries older than 30 days removed (design 9).
     */
    val entries: StateFlow<List<RecentEntry>> = _entries.asStateFlow()

    @Volatile
    private var dataStore: DataStore<RecentData>? = null

    /**
     * Publishes the outcome of the current open attempt to writers that
     * arrived before the store was ready (Issue #42). Completed with the
     * store once it opens, with null when the open failed or the attempt
     * ended without producing one. Null until [start] is first called —
     * writes then fail fast instead of waiting on an open that never
     * comes (the documented await contract).
     */
    @Volatile
    private var openSignal: CompletableDeferred<DataStore<RecentData>?>? = null

    /**
     * Scope that owns the current [dataStore]. Only one DataStore may be
     * active per file, so this scope is cancelled and joined before any
     * replacement instance is created.
     */
    private var dataStoreScope: CoroutineScope? = null
    private var collectJob: Job? = null

    /** True once the DataStore pipeline is live; internal for tests. */
    internal val isOpen: Boolean get() = dataStore != null

    fun start() {
        collectJob?.cancel()
        // A collect job cancelled before its first dispatch never runs —
        // finally included — so the superseding start() must release the
        // previous generation's signal itself or its writers would hang.
        openSignal?.complete(null)
        val signal = CompletableDeferred<DataStore<RecentData>?>()
        openSignal = signal
        collectJob = scope.launch {
            try {
                // Release any previous instance before touching the file —
                // only one DataStore may exist per file.
                closeDataStore()
                val file = fileProvider()
                if (file.exists()) {
                    try {
                        file.inputStream().use { serializer.readFrom(it) }
                    } catch (e: CorruptionException) {
                        // Disposable history: discard and start empty.
                        file.delete()
                    } catch (e: IOException) {
                        // Unreadable path (permission, vanished file,
                        // non-file): discard if possible, open anyway,
                        // never crash.
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
     * Records a successful launch of [target] (design 9: 最近の記録).
     * Once [start] has been called this waits for the open to finish so a
     * launch in the first moments of the process still lands (Issue #42);
     * it returns false — never hanging — when [start] was never called,
     * the open failed, or the write itself fails. The in-memory list is
     * preserved either way.
     */
    suspend fun record(target: StoredTarget): Boolean {
        val store = awaitStore() ?: return false
        val now = clock()
        return try {
            store.updateData { data ->
                data.copy(
                    entries = RecentRules.recorded(data.entries, target, now),
                )
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

    /**
     * Re-normalizes the stored history at a request point (design 9: entries
     * older than 30 days are deleted). There is no background watcher, so
     * entries that cross the retention line while the process lives are
     * pruned where the history is actually shown — when Search opens. The
     * transform normalizes the CURRENT value inside updateData so it can
     * never resurrect entries a concurrent clear() removed; an unchanged
     * result writes nothing.
     */
    suspend fun prune(): Boolean {
        val store = awaitStore() ?: return false
        return try {
            store.updateData { data ->
                data.copy(entries = RecentRules.normalize(data.entries, clock()))
            }
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

    /** Erases the whole history (design 9: 履歴消去). */
    suspend fun clear(): Boolean {
        val store = awaitStore() ?: return false
        return try {
            store.updateData { it.copy(entries = emptyList()) }
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
    private suspend fun awaitStore(): DataStore<RecentData>? =
        dataStore ?: openSignal?.await()

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

    private suspend fun openDataStore(
        signal: CompletableDeferred<DataStore<RecentData>?>,
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
                    is CorruptionException, is IOException -> {
                        // The file went bad after the pre-read: drop it and
                        // keep the session on the in-memory list.
                        fileProvider().delete()
                    }
                    is IllegalStateException -> Unit
                    else -> throw e
                }
            }
            .collect { data ->
                val normalized = RecentRules.normalize(data.entries, clock())
                _entries.value = normalized
                if (normalized != data.entries) {
                    // Expired/duplicate entries are removed from the file
                    // itself (design 9: 30日経過で削除), not merely hidden.
                    // The write emits the normalized list again; normalize
                    // is idempotent so the pass converges after one write.
                    // The transform re-normalizes the CURRENT data inside
                    // the update: writing the stale snapshot instead could
                    // resurrect entries a concurrent clear() just removed.
                    try {
                        store.updateData { current ->
                            current.copy(
                                entries = RecentRules.normalize(
                                    current.entries,
                                    clock(),
                                ),
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: CorruptionException) {
                        fileProvider().delete()
                    } catch (e: IOException) {
                        // Keep showing the pruned list; the next write
                        // retries the deletion.
                    } catch (e: IllegalStateException) {
                        // The store was closed meanwhile.
                    }
                }
            }
    }
}
