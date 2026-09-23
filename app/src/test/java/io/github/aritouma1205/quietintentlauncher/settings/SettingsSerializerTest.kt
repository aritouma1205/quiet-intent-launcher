package io.github.aritouma1205.quietintentlauncher.settings

import androidx.datastore.core.CorruptionException
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Sequential schema migrations (design 14.1): v1/v2 files gain the seeded
 * DO actions at v3, a v3 file that explicitly stores an empty list is kept
 * as is, and corrupted/future-version files still raise
 * [CorruptionException] so the original is preserved.
 */
class SettingsSerializerTest {

    private val serializer = SettingsSerializer()

    private fun read(text: String): SettingsData = runBlocking {
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
    fun `v2 file is seeded with the six unset defaults`() = runBlocking {
        val data = read("""{"schemaVersion":2,"introCompleted":true}""")
        assertEquals(SettingsData.CURRENT_SCHEMA_VERSION, data.schemaVersion)
        assertTrue(data.introCompleted)
        assertEquals(6, data.actions.size)
        assertEquals(
            listOf("撮る", "話す", "聴く", "見る", "移動する", "調べる"),
            data.actions.map { it.name },
        )
        assertTrue(data.actions.all { it.target == null })
    }

    @Test
    fun `v1 file is seeded too`() = runBlocking {
        val data = read("""{"schemaVersion":1}""")
        assertEquals(6, data.actions.size)
    }

    @Test
    fun `v2 migration preserves existing fields`() = runBlocking {
        val data = read(
            """{
              "schemaVersion":2,
              "introCompleted":true,
              "notificationHintShown":true,
              "leftBar":{"verticalBias":0.8,"color":"Black"},
              "tools":{"openMode":"DeepPull","deepPullFraction":0.7},
              "vibration":"Off",
              "systemActions":{"notificationsEnabled":true}
            }""",
        )
        assertTrue(data.introCompleted)
        assertTrue(data.notificationHintShown)
        assertEquals(EdgeBarColor.Black, data.leftBar.color)
        assertEquals(0.8f, data.leftBar.verticalBias)
        assertEquals(ToolsOpenMode.DeepPull, data.tools.openMode)
        assertEquals(VibrationMode.Off, data.vibration)
        assertTrue(data.systemActions.notificationsEnabled)
        // The new field still arrives seeded.
        assertEquals(6, data.actions.size)
    }

    @Test
    fun `v3 file with an empty actions list is not re-seeded`() = runBlocking {
        val data = read("""{"schemaVersion":3,"actions":[]}""")
        assertEquals(0, data.actions.size)
    }

    @Test
    fun `v3 file keeps a stored action list`() = runBlocking {
        val stored = SettingsData(
            introCompleted = true,
            actions = listOf(
                DoAction(
                    id = "fixed-id",
                    name = "写真",
                    icon = ActionIcon.Star,
                    visible = false,
                    target = StoredTarget.App("com.example/.Main"),
                    aliases = listOf("camera"),
                    derivedOps = listOf(
                        DerivedOp(
                            id = "op-1",
                            label = "動画",
                            target = StoredTarget.HttpsLink("https://example.com"),
                        ),
                    ),
                ),
            ),
        )
        val data = read(serializer.serialize(stored))
        assertEquals(stored, data)
        val action = data.actions.single()
        assertEquals("写真", action.name)
        assertFalse(action.visible)
        assertEquals(
            StoredTarget.App("com.example/.Main"),
            action.target,
        )
        assertEquals(listOf("camera"), action.aliases)
        assertEquals("動画", action.derivedOps.single().label)
    }

    @Test
    fun `v3 file with stored shortcut and link targets round-trips`() =
        runBlocking {
            val stored = SettingsData(
                actions = listOf(
                    DoAction(
                        name = "a",
                        target = StoredTarget.Shortcut("com.pkg", "id-1"),
                    ),
                    DoAction(
                        name = "b",
                        target = StoredTarget.HttpsLink("https://a.b/c"),
                    ),
                ),
            )
            val data = read(serializer.serialize(stored))
            assertEquals(stored.actions, data.actions)
        }

    @Test
    fun `corrupted files still raise CorruptionException`() {
        assertCorrupt("this is not json {{{")
        assertCorrupt("""{"schemaVersion":""")
    }

    @Test
    fun `future schema versions still raise CorruptionException`() {
        assertCorrupt("""{"schemaVersion":999,"introCompleted":true}""")
    }

    @Test
    fun `blank input yields defaults`() = runBlocking {
        val data = serializer.readFrom(ByteArrayInputStream(byteArrayOf()))
        assertEquals(SettingsData(), data)
        assertEquals(6, data.actions.size)
    }

    @Test
    fun `validation errors are null for seeded defaults`() {
        assertNull(ActionRules.validateActions(DoActionDefaults.defaults()))
    }
}
