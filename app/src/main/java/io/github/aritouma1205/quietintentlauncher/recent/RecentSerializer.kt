package io.github.aritouma1205.quietintentlauncher.recent

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * JSON serializer for [RecentData] (design 14.1). Unlike settings, the
 * recent-history file is disposable: unreadable content raises
 * [CorruptionException] so the caller can delete the file and start empty
 * instead of preserving a corrupt original for recovery.
 */
class RecentSerializer(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) : Serializer<RecentData> {

    override val defaultValue: RecentData = RecentData()

    override suspend fun readFrom(input: InputStream): RecentData {
        val text = try {
            input.readBytes().decodeToString()
        } catch (e: Exception) {
            throw CorruptionException("Failed to read the recent file.", e)
        }
        if (text.isBlank()) return defaultValue

        return try {
            json.decodeFromString(RecentData.serializer(), text)
        } catch (e: SerializationException) {
            throw CorruptionException("Recent file is not readable.", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("Recent file is not readable.", e)
        }
    }

    override suspend fun writeTo(t: RecentData, output: OutputStream) {
        output.write(serialize(t).toByteArray(Charsets.UTF_8))
    }

    fun serialize(data: RecentData): String =
        json.encodeToString(RecentData.serializer(), data)
}
