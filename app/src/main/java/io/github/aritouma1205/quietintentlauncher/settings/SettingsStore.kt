package io.github.aritouma1205.quietintentlauncher.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    private val dataStoreFactory: () -> DataStore<SettingsData>,
) {
    private val _state = MutableStateFlow<SettingsState>(SettingsState.Loading)
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    @Volatile
    private var degraded: Boolean = false

    private var dataStore: DataStore<SettingsData>? = null
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
            degraded = true
            _state.value = SettingsState.Degraded(e.message ?: "settings unreadable")
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
     */
    suspend fun resetToDefaults(): Boolean {
        val file = fileProvider()
        val written = try {
            writeAtomically(file, serializer.serialize(SettingsData()))
            true
        } catch (e: IOException) {
            false
        }
        if (!written) return false
        degraded = false
        collectJob?.cancel()
        _state.value = SettingsState.Loading
        collectJob = scope.launch { openDataStore() }
        return true
    }

    private suspend fun openDataStore() {
        val store = try {
            dataStoreFactory()
        } catch (e: IllegalStateException) {
            // A previously opened store for this file was poisoned by a
            // mid-session corruption; a restart is required to re-read it.
            degraded = true
            _state.value = SettingsState.Degraded(e.message ?: "settings unreadable")
            return
        }
        dataStore = store
        store.data
            .catch { e ->
                when (e) {
                    is CorruptionException, is IOException -> {
                        degraded = true
                        _state.value =
                            SettingsState.Degraded(e.message ?: "settings unreadable")
                    }
                    else -> throw e
                }
            }
            .collect {
                degraded = false
                _state.value = SettingsState.Ready(it)
            }
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
