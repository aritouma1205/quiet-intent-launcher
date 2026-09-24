package io.github.aritouma1205.quietintentlauncher.recent

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import io.github.aritouma1205.quietintentlauncher.settings.StoredTarget
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
    // same as SettingsStore.
    private val dataStoreFactory: (CoroutineScope) -> DataStore<RecentData>,
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
        collectJob = scope.launch {
            // Release any previous instance before touching the file — only
            // one DataStore may exist per file.
            closeDataStore()
            val file = fileProvider()
            if (file.exists()) {
                try {
                    file.inputStream().use { serializer.readFrom(it) }
                } catch (e: CorruptionException) {
                    // Disposable history: discard and start empty.
                    file.delete()
                } catch (e: IOException) {
                    // Unreadable path (permission, vanished file, non-file):
                    // discard if possible, open anyway, never crash.
                    file.delete()
                }
            }
            openDataStore()
        }
    }

    /**
     * Records a successful launch of [target] (design 9: 最近の記録).
     * Returns false when the store is not open or the write fails; the
     * in-memory list is preserved either way.
     */
    suspend fun record(target: StoredTarget): Boolean {
        val store = dataStore ?: return false
        val now = clock()
        return try {
            store.updateData { data ->
                data.copy(
                    entries = RecentRules.recorded(data.entries, target, now),
                )
            }
            true
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
        val store = dataStore ?: return false
        return try {
            store.updateData { it.copy(entries = emptyList()) }
            true
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
        closeDataStore()
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = try {
            dataStoreFactory(storeScope)
        } catch (e: IllegalStateException) {
            storeScope.cancel()
            return
        }
        dataStoreScope = storeScope
        dataStore = store
        store.data
            .catch { e ->
                when (e) {
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
