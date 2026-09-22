package io.github.aritouma1205.quietintentlauncher.settings

import kotlinx.serialization.Serializable

/**
 * Persisted settings. Design 14.1: typed JSON via DataStore with schemaVersion.
 * Stage 1 keeps only what this stage needs; later stages extend the fields and
 * add sequential migrations in [SettingsSerializer].
 */
@Serializable
data class SettingsData(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val introCompleted: Boolean = false,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}
