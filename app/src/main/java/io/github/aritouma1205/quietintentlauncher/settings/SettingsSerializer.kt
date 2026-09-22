package io.github.aritouma1205.quietintentlauncher.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * JSON serializer for [SettingsData].
 *
 * Design 14.1: never silently overwrite a corrupted or future-version file.
 * Read failures are reported as [CorruptionException] so the caller can keep
 * the original file and offer recovery instead of replacing it with defaults.
 */
class SettingsSerializer(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) : Serializer<SettingsData> {

    override val defaultValue: SettingsData = SettingsData()

    override suspend fun readFrom(input: InputStream): SettingsData {
        val text = try {
            input.readBytes().decodeToString()
        } catch (e: Exception) {
            throw CorruptionException("Failed to read settings file.", e)
        }
        if (text.isBlank()) return defaultValue

        val parsed = try {
            json.decodeFromString(SettingsData.serializer(), text)
        } catch (e: SerializationException) {
            throw CorruptionException("Settings file is not readable.", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("Settings file is not readable.", e)
        }

        if (parsed.schemaVersion > SettingsData.CURRENT_SCHEMA_VERSION) {
            throw CorruptionException(
                "Settings schemaVersion ${parsed.schemaVersion} is newer than " +
                    "supported version ${SettingsData.CURRENT_SCHEMA_VERSION}.",
            )
        }
        return migrate(parsed)
    }

    override suspend fun writeTo(t: SettingsData, output: OutputStream) {
        output.write(serialize(t).toByteArray(Charsets.UTF_8))
    }

    fun serialize(data: SettingsData): String =
        json.encodeToString(SettingsData.serializer(), data)

    private fun migrate(data: SettingsData): SettingsData {
        // Sequential migrations per schemaVersion go here in later stages.
        // v1 is the only version today; normalize the stored version field.
        return data.copy(schemaVersion = SettingsData.CURRENT_SCHEMA_VERSION)
    }
}
