package io.github.aritouma1205.quietintentlauncher.settings

import androidx.datastore.core.CorruptionException
import io.github.aritouma1205.quietintentlauncher.context.ContextRule
import io.github.aritouma1205.quietintentlauncher.context.ContextSlot
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
    fun `v3 file gains the two empty context slots and search defaults`() =
        runBlocking {
            val data = read("""{"schemaVersion":3,"actions":[]}""")
            assertEquals(SettingsData.CURRENT_SCHEMA_VERSION, data.schemaVersion)
            assertEquals(2, data.contextSlots.size)
            assertTrue(
                data.contextSlots.all {
                    it.rules.isEmpty() && it.defaultActionId == null
                },
            )
            assertTrue(data.search.recentRecording)
            assertEquals(WebSearchEngine.Google, data.search.webEngine)
        }

    @Test
    fun `v4 round trip keeps context slots and search settings`() =
        runBlocking {
            val stored = SettingsData(
                introCompleted = true,
                actions = listOf(DoAction(id = "a1", name = "聴く")),
                contextSlots = listOf(
                    ContextSlot(
                        id = "s1",
                        label = "朝の音楽",
                        rules = listOf(
                            ContextRule(
                                id = "r1",
                                daysOfWeek = setOf(1, 2, 3, 4, 5),
                                startMinuteOfDay = 420,
                                endMinuteOfDay = 540,
                                actionId = "a1",
                            ),
                        ),
                        defaultActionId = "a1",
                    ),
                    ContextSlot(id = "s2", label = "帰宅ルート"),
                ),
                search = SearchSettings(
                    recentRecording = false,
                    webEngine = WebSearchEngine.DuckDuckGo,
                ),
            )
            val data = read(serializer.serialize(stored))
            assertEquals(stored, data)
            assertFalse(data.search.recentRecording)
            assertEquals(WebSearchEngine.DuckDuckGo, data.search.webEngine)
        }

    @Test
    fun `stored context slots are normalized to two`() = runBlocking {
        // A file carrying three slots is truncated; one slot is padded.
        val three = """{"schemaVersion":4,"contextSlots":[{},{},{}]}"""
        assertEquals(2, read(three).contextSlots.size)
        val one = """{"schemaVersion":4,"contextSlots":[{"label":"x"}]}"""
        val migrated = read(one)
        assertEquals(2, migrated.contextSlots.size)
        assertEquals("x", migrated.contextSlots[0].label)
    }

    @Test
    fun `v2 file decodes context slots and search defaults too`() =
        runBlocking {
            val data = read("""{"schemaVersion":2,"introCompleted":true}""")
            assertEquals(2, data.contextSlots.size)
            assertEquals(SearchSettings(), data.search)
        }

    @Test
    fun `v4 file gains clock and info defaults`() = runBlocking {
        val data = read("""{"schemaVersion":4,"search":{"recentRecording":false}}""")
        assertEquals(SettingsData.CURRENT_SCHEMA_VERSION, data.schemaVersion)
        assertFalse(data.clock.enabled)
        assertEquals(QuietClockPosition.TopStart, data.clock.position)
        assertEquals(GlancePosition.Top, data.info.glancePosition)
        assertEquals(3, data.info.glanceDismissSeconds)
        assertFalse(data.info.weather.enabled)
        assertNull(data.info.weather.location)
        assertFalse(data.info.eventsEnabled)
        assertTrue(data.info.selectedCalendarIds.isEmpty())
        // Existing fields survive the v4 -> v5 hop.
        assertFalse(data.search.recentRecording)
    }

    @Test
    fun `v5 round trip keeps clock and info settings`() = runBlocking {
        val stored = SettingsData(
            clock = ClockSettings(
                enabled = true,
                position = QuietClockPosition.TopEnd,
            ),
            info = InfoSettings(
                glancePosition = GlancePosition.Bottom,
                glanceDismissSeconds = 10,
                weather = WeatherSettings(
                    enabled = true,
                    location = WeatherLocation(
                        providerId = "1850147",
                        name = "東京",
                        latitude = 35.6895,
                        longitude = 139.6917,
                    ),
                ),
                eventsEnabled = true,
                selectedCalendarIds = listOf(3L, 7L),
            ),
        )
        val data = read(serializer.serialize(stored))
        assertEquals(stored, data)
    }

    @Test
    fun `glance dismiss seconds are clamped into the designed range`() =
        runBlocking {
            fun seconds(raw: Int) = read(
                """{"schemaVersion":5,"info":{"glanceDismissSeconds":$raw}}""",
            ).info.glanceDismissSeconds
            assertEquals(0, seconds(0)) // 0 = never auto-dismiss
            assertEquals(2, seconds(1))
            assertEquals(10, seconds(99))
            assertEquals(5, seconds(5))
        }

    @Test
    fun `weather enabled without a valid region decodes to disabled`() =
        runBlocking {
            val noLocation = read(
                """{"schemaVersion":5,"info":{"weather":{"enabled":true}}}""",
            )
            assertFalse(noLocation.info.weather.enabled)
            val badLocation = read(
                """{"schemaVersion":5,"info":{"weather":{"enabled":true,
                  "location":{"providerId":"x","name":"n",
                  "latitude":200.0,"longitude":0.0}}}}""",
            )
            assertFalse(badLocation.info.weather.enabled)
            assertNull(badLocation.info.weather.location)
        }

    @Test
    fun `duplicated calendar ids are deduplicated`() = runBlocking {
        val data = read(
            """{"schemaVersion":5,"info":{"selectedCalendarIds":[1,2,2,1]}}""",
        )
        assertEquals(listOf(1L, 2L), data.info.selectedCalendarIds)
    }

    @Test
    fun `v5 file gains the default tool list and screenshot switch`() =
        runBlocking {
            val data = read("""{"schemaVersion":5,"systemActions":{"notificationsEnabled":true}}""")
            assertEquals(SettingsData.CURRENT_SCHEMA_VERSION, data.schemaVersion)
            assertEquals(
                ToolItem.entries.map { it.id },
                data.tools.items.map { it.id },
            )
            assertTrue(data.tools.items.all { it.visible && it.target == null })
            assertFalse(data.systemActions.screenshotEnabled)
            // The v2-era switch survives the hop.
            assertTrue(data.systemActions.notificationsEnabled)
        }

    @Test
    fun `v6 round trip keeps tool order visibility and targets`() =
        runBlocking {
            val stored = SettingsData(
                tools = ToolsSettings(
                    items = listOf(
                        ToolSetting("timer", visible = false),
                        ToolSetting(
                            "calculator",
                            target = StoredTarget.App("com.calc/.Main"),
                        ),
                        ToolSetting("screenshot"),
                        ToolSetting("light"),
                        ToolSetting(
                            "qr",
                            target = StoredTarget.Shortcut("com.qr", "scan"),
                        ),
                    ),
                ),
                systemActions = SystemActionSettings(screenshotEnabled = true),
            )
            val data = read(serializer.serialize(stored))
            assertEquals(stored, data)
            assertEquals(
                listOf("timer", "calculator", "screenshot", "light", "qr"),
                data.tools.items.map { it.id },
            )
            assertFalse(data.tools.items[0].visible)
            assertTrue(data.systemActions.screenshotEnabled)
        }

    @Test
    fun `stored tool list is normalized to the five known tools`() =
        runBlocking {
            // Unknown ids drop out, a duplicate collapses to its first
            // entry, and a missing tool appends in the designed order.
            val data = read(
                """{"schemaVersion":6,"tools":{"items":[
                  {"id":"qr","visible":false},
                  {"id":"bogus"},
                  {"id":"qr","visible":true},
                  {"id":"light"}
                ]}}""",
            )
            assertEquals(
                listOf("qr", "light", "calculator", "timer", "screenshot"),
                data.tools.items.map { it.id },
            )
            // The first "qr" wins, including its visibility.
            assertFalse(data.tools.items.first { it.id == "qr" }.visible)
        }

    @Test
    fun `tool targets survive only where the tool can use them`() =
        runBlocking {
            val data = read(
                """{"schemaVersion":6,"tools":{"items":[
                  {"id":"light","target":{"type":"app","component":"a/.B"}},
                  {"id":"screenshot","target":{"type":"app","component":"a/.B"}},
                  {"id":"calculator","target":{"type":"shortcut","packageName":"p","shortcutId":"s"}},
                  {"id":"qr","target":{"type":"shortcut","packageName":"p","shortcutId":"s"}}
                ]}}""",
            )
            val byId = data.tools.items.associateBy { it.id }
            assertNull(byId.getValue("light").target)
            assertNull(byId.getValue("screenshot").target)
            // Shortcuts are only meaningful on the QR tool (design 7).
            assertNull(byId.getValue("calculator").target)
            assertEquals(
                StoredTarget.Shortcut("p", "s"),
                byId.getValue("qr").target,
            )
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
