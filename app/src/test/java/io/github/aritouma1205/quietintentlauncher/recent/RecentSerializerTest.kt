package io.github.aritouma1205.quietintentlauncher.recent

import androidx.datastore.core.CorruptionException
import io.github.aritouma1205.quietintentlauncher.settings.StoredTarget
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class RecentSerializerTest {

    private val serializer = RecentSerializer()

    private fun read(text: String): RecentData = runBlocking {
        serializer.readFrom(ByteArrayInputStream(text.toByteArray()))
    }

    private fun assertCorrupt(text: String) {
        try {
            read(text)
            fail("expected CorruptionException for: $text")
        } catch (e: CorruptionException) {
            // expected
        }
    }

    @Test
    fun `blank input yields defaults`() = runBlocking {
        val data = serializer.readFrom(ByteArrayInputStream(byteArrayOf()))
        assertEquals(RecentData(), data)
    }

    @Test
    fun `all target kinds round-trip`() = runBlocking {
        val stored = RecentData(
            listOf(
                RecentEntry(StoredTarget.App("com.a/.Main"), 100),
                RecentEntry(StoredTarget.Shortcut("com.pkg", "id-1"), 200),
                RecentEntry(StoredTarget.HttpsLink("https://a.b/c"), 300),
            ),
        )
        assertEquals(stored, read(serializer.serialize(stored)))
    }

    @Test
    fun `unknown keys are ignored`() = runBlocking {
        val data = read(
            """{"entries":[{"target":{"type":"app","component":"com.a/.Main"},
              "lastLaunchedAtMillis":7,"future":true}],"futureField":1}"""
                .replace("\n", "").replace(" ", ""),
        )
        assertEquals(1, data.entries.size)
        assertEquals(
            StoredTarget.App("com.a/.Main"),
            data.entries.single().target,
        )
        assertEquals(7, data.entries.single().lastLaunchedAtMillis)
    }

    @Test
    fun `corrupted input raises CorruptionException`() {
        assertCorrupt("this is not json {{{")
        assertCorrupt("""{"entries":[""")
        assertCorrupt("""{"entries":[{"target":{"type":"app"}}]}""")
    }
}
