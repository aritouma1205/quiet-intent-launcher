package io.github.aritouma1205.quietintentlauncher.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import io.github.aritouma1205.quietintentlauncher.context.ContextRules
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

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
        return migrate(parsed, hasExplicitActions(text))
    }

    /**
     * Whether the file carries an explicit "actions" field. v2 files predate
     * the field and must be seeded; a v3 file that stores an empty list on
     * purpose must be kept as is, so absence — not emptiness — triggers the
     * seed.
     */
    private fun hasExplicitActions(text: String): Boolean =
        (json.parseToJsonElement(text) as? JsonObject)?.containsKey("actions") == true

    override suspend fun writeTo(t: SettingsData, output: OutputStream) {
        output.write(serialize(t).toByteArray(Charsets.UTF_8))
    }

    fun serialize(data: SettingsData): String =
        json.encodeToString(SettingsData.serializer(), data)

    private fun migrate(data: SettingsData, hasActions: Boolean): SettingsData {
        // Sequential migrations per schemaVersion. v1 -> v2 adds the edge-bar,
        // TOOLS and system-action fields, all with defaults, so decoding an old
        // file already fills them; sanitize keeps stored values inside the
        // designed ranges.
        // v2 -> v3 adds the DO action list; files written before v3 carry no
        // actions field and are seeded with the six unset defaults (design 6).
        // v3 -> v4 adds Context Slots and search settings; absent fields decode
        // to the designed defaults, and stored slots are normalized to two.
        // v4 -> v5 adds the clock and 「情報」 settings; absent fields decode
        // to the designed defaults, and stored ranges are sanitized.
        // v5 -> v6 adds the tool order/visibility/targets and the screenshot
        // switch; the tool list is normalized to the five known tools.
        return data.copy(
            schemaVersion = SettingsData.CURRENT_SCHEMA_VERSION,
            leftBar = data.leftBar.sanitized(),
            rightBar = data.rightBar.sanitized(),
            tools = data.tools.sanitized(),
            actions = if (data.schemaVersion < 3 && !hasActions) {
                DoActionDefaults.defaults()
            } else {
                data.actions
            },
            contextSlots = ContextRules.normalized(data.contextSlots),
            info = data.info.sanitized(),
        )
    }
}
