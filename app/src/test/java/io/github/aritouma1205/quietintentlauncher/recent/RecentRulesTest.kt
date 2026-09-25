package io.github.aritouma1205.quietintentlauncher.recent

import io.github.aritouma1205.quietintentlauncher.launch.stableKey
import io.github.aritouma1205.quietintentlauncher.settings.StoredTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [RecentRules] (design 9: max 20 entries, no duplicate
 * targets, newest first, entries older than 30 days removed). The clock is
 * injected so every boundary is deterministic.
 */
class RecentRulesTest {

    private val now = 1_700_000_000_000L

    private val appA = StoredTarget.App("com.a/.Main")
    private val appB = StoredTarget.App("com.b/.Main")
    private val shortcut = StoredTarget.Shortcut("com.pkg", "id-1")
    private val link = StoredTarget.HttpsLink("https://example.com")

    private fun at(target: StoredTarget, millis: Long) =
        RecentEntry(target, millis)

    @Test
    fun `stable keys differ per target kind`() {
        assertEquals("app:com.a/.Main", appA.stableKey)
        assertEquals("shortcut:com.pkg/id-1", shortcut.stableKey)
        assertEquals("https:https://example.com", link.stableKey)
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals(
            emptyList<RecentEntry>(),
            RecentRules.normalize(emptyList(), now),
        )
    }

    @Test
    fun `normalize sorts newest first`() {
        val result = RecentRules.normalize(
            listOf(
                at(appA, now - 300),
                at(appB, now - 100),
                at(link, now - 200),
            ),
            now,
        )
        assertEquals(listOf(appB, link, appA), result.map { it.target })
    }

    @Test
    fun `normalize removes duplicate targets keeping the newest`() {
        val result = RecentRules.normalize(
            listOf(
                at(appA, now - 300),
                at(appB, now - 200),
                at(appA, now - 100),
            ),
            now,
        )
        assertEquals(2, result.size)
        assertEquals(appA, result[0].target)
        assertEquals(now - 100, result[0].lastLaunchedAtMillis)
        assertEquals(appB, result[1].target)
    }

    @Test
    fun `an entry exactly thirty days old is kept`() {
        val result = RecentRules.normalize(
            listOf(at(appA, now - RecentRules.RETENTION_MILLIS)),
            now,
        )
        assertEquals(1, result.size)
        assertEquals(appA, result.single().target)
    }

    @Test
    fun `an entry older than thirty days is dropped`() {
        val result = RecentRules.normalize(
            listOf(at(appA, now - RecentRules.RETENTION_MILLIS - 1)),
            now,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `normalize caps the list at twenty entries`() {
        val input = (0 until 30).map {
            at(StoredTarget.App("com.app$it/.Main"), now - it.toLong())
        }
        val result = RecentRules.normalize(input, now)
        assertEquals(RecentRules.MAX_ENTRIES, result.size)
        assertEquals(
            "com.app0/.Main",
            (result.first().target as StoredTarget.App).component,
        )
        assertEquals(
            "com.app19/.Main",
            (result.last().target as StoredTarget.App).component,
        )
    }

    @Test
    fun `recorded adds a new target at the front`() {
        val result = RecentRules.recorded(
            listOf(at(appA, now - 10)),
            link,
            now,
        )
        assertEquals(listOf(link, appA), result.map { it.target })
        assertEquals(now, result.first().lastLaunchedAtMillis)
    }

    @Test
    fun `recorded moves an existing target to the front without duplicating`() {
        val result = RecentRules.recorded(
            listOf(at(appA, now - 20), at(appB, now - 10)),
            StoredTarget.App("com.a/.Main"),
            now,
        )
        assertEquals(2, result.size)
        assertEquals(appA, result[0].target)
        assertEquals(now, result[0].lastLaunchedAtMillis)
        assertEquals(appB, result[1].target)
    }

    @Test
    fun `recorded keeps distinct kinds as separate entries`() {
        val entries = RecentRules.recorded(emptyList(), appA, now)
            .let { RecentRules.recorded(it, shortcut, now + 1) }
            .let { RecentRules.recorded(it, link, now + 2) }
        assertEquals(3, entries.size)
        assertEquals(listOf(link, shortcut, appA), entries.map { it.target })
    }

    @Test
    fun `recorded drops the oldest entry when the cap is exceeded`() {
        val base = (0 until RecentRules.MAX_ENTRIES).map {
            at(StoredTarget.App("com.app$it/.Main"), now - it.toLong() - 1)
        }
        val result = RecentRules.recorded(base, link, now)
        assertEquals(RecentRules.MAX_ENTRIES, result.size)
        assertEquals(link, result.first().target)
        assertFalse(
            result.any {
                (it.target as? StoredTarget.App)?.component == "com.app19/.Main"
            },
        )
    }

    @Test
    fun `recorded prunes an expired existing entry`() {
        val expired = at(appA, now - RecentRules.RETENTION_MILLIS - 1)
        val result = RecentRules.recorded(listOf(expired), link, now)
        assertEquals(listOf(link), result.map { it.target })
    }
}
