package io.github.aritouma1205.quietintentlauncher.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure rules for the TOOLS list (design 7): normalization pins the list to
 * the five known tools, and reorder helpers move one row at a time.
 */
class ToolRulesTest {

    private fun ids(items: List<ToolSetting>) = items.map { it.id }

    @Test
    fun `normalization preserves stored order and appends missing tools`() {
        val items = ToolRules.normalized(
            listOf(
                ToolSetting("timer"),
                ToolSetting("light"),
            ),
        )
        assertEquals(
            listOf("timer", "light", "calculator", "qr", "screenshot"),
            ids(items),
        )
    }

    @Test
    fun `normalization drops unknown ids and collapses duplicates`() {
        val items = ToolRules.normalized(
            listOf(
                ToolSetting("qr", visible = false),
                ToolSetting("nope"),
                ToolSetting("qr", visible = true),
            ),
        )
        assertEquals(
            listOf("qr", "light", "calculator", "timer", "screenshot"),
            ids(items),
        )
        assertEquals(false, items.first { it.id == "qr" }.visible)
    }

    @Test
    fun `empty stored list yields the default order`() {
        assertEquals(
            ToolItem.entries.map { it.id },
            ids(ToolRules.normalized(emptyList())),
        )
    }

    @Test
    fun `targets are stripped from tools that cannot use them`() {
        val app = StoredTarget.App("a/.B")
        val shortcut = StoredTarget.Shortcut("p", "s")
        val items = ToolRules.normalized(
            listOf(
                ToolSetting("light", target = app),
                ToolSetting("screenshot", target = app),
                ToolSetting("timer", target = app),
                ToolSetting("calculator", target = shortcut),
                ToolSetting("qr", target = shortcut),
            ),
        )
        val byId = items.associateBy { it.id }
        assertNull(byId.getValue("light").target)
        assertNull(byId.getValue("screenshot").target)
        assertEquals(app, byId.getValue("timer").target)
        assertNull(byId.getValue("calculator").target)
        assertEquals(shortcut, byId.getValue("qr").target)
    }

    @Test
    fun `link targets are stripped from every tool`() {
        val link = StoredTarget.HttpsLink("https://example.com")
        val items = ToolRules.normalized(
            listOf(
                ToolSetting("calculator", target = link),
                ToolSetting("timer", target = link),
                ToolSetting("qr", target = link),
            ),
        )
        val byId = items.associateBy { it.id }
        assertNull(byId.getValue("calculator").target)
        assertNull(byId.getValue("timer").target)
        assertNull(byId.getValue("qr").target)
    }

    @Test
    fun `moveUp and moveDown shift one row at a time`() {
        val items = ToolRules.normalized(emptyList())
        val moved = ToolRules.moveUp(items, 1)
        assertEquals(
            listOf("calculator", "light", "qr", "timer", "screenshot"),
            ids(moved),
        )
        val movedDown = ToolRules.moveDown(moved, 0)
        assertEquals(ids(items), ids(movedDown))
    }

    @Test
    fun `moves at the edges are no-ops`() {
        val items = ToolRules.normalized(emptyList())
        assertEquals(items, ToolRules.moveUp(items, 0))
        assertEquals(items, ToolRules.moveDown(items, items.lastIndex))
        assertEquals(items, ToolRules.moveUp(items, -1))
        assertEquals(items, ToolRules.moveDown(items, 99))
    }
}
