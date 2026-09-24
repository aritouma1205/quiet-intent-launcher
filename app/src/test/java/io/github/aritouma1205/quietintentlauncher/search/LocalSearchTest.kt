package io.github.aritouma1205.quietintentlauncher.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Candidate matching and ranking for local search (design 9.1). */
class LocalSearchTest {

    private fun item(
        id: String,
        primary: String,
        kind: SearchItemKind = SearchItemKind.App,
        aliases: List<String> = emptyList(),
        groupOrder: Int = 0,
        subOrder: Int = 0,
    ) = SearchItem(id, kind, primary, aliases, groupOrder, subOrder)

    private fun ids(result: List<SearchItem>) = result.map { it.id }

    @Test
    fun `exact beats prefix beats partial for a single word`() {
        val items = listOf(
            item("partial", "新しいカメラ"),
            item("exact", "カメラ"),
            item("prefix", "カメラ設定"),
        )
        assertEquals(
            listOf("exact", "prefix", "partial"),
            ids(LocalSearch.search("カメラ", items)),
        )
    }

    @Test
    fun `aliases match and can win the best class`() {
        val items = listOf(
            item("byName", "古いカメラロール"),
            item("byAlias", "撮る", aliases = listOf("写真", "カメラ")),
        )
        // The alias gives an exact match, beating the primary-only partial.
        assertEquals(
            listOf("byAlias", "byName"),
            ids(LocalSearch.search("カメラ", items)),
        )
    }

    @Test
    fun `multi word queries require every term across name and aliases`() {
        val items = listOf(
            item("camOnly", "カメラ"),
            item("bothInName", "カメラの設定"),
            item("acrossNames", "設定ツール", aliases = listOf("カメラ"), groupOrder = 1),
            item("setOnly", "設定"),
            item("phrase", "カメラ 設定", groupOrder = 9),
        )
        // "camOnly" and "setOnly" match just one term and drop out. The exact
        // whole-query phrase ranks above the AND-only matches, which then
        // fall back to groupOrder.
        assertEquals(
            listOf("phrase", "bothInName", "acrossNames"),
            ids(LocalSearch.search("カメラ　設定", items)),
        )
    }

    @Test
    fun `same named apps all stay in the result`() {
        val items = listOf(
            item("app.b", "メモ"),
            item("app.a", "メモ"),
        )
        val result = LocalSearch.search("メモ", items)
        assertEquals(2, result.size)
        assertEquals(listOf("app.a", "app.b"), ids(result))
    }

    @Test
    fun `no match returns empty`() {
        val items = listOf(item("cam", "カメラ"), item("memo", "メモ"))
        assertTrue(LocalSearch.search("zzz", items).isEmpty())
    }

    @Test
    fun `blank query returns empty`() {
        val items = listOf(item("cam", "カメラ"))
        assertTrue(LocalSearch.search("", items).isEmpty())
        assertTrue(LocalSearch.search("  　 ", items).isEmpty())
    }

    @Test
    fun `kinds order action tier then tool settings then apps`() {
        val items = listOf(
            item("app", "メモ", kind = SearchItemKind.App),
            item("tool", "メモ", kind = SearchItemKind.ToolSetting),
            // Action and DerivedOp share the action tier and interleave by
            // groupOrder/subOrder: d0 sits inside action 0, a2 after it.
            item("a2", "メモ", kind = SearchItemKind.Action, groupOrder = 2),
            item("d0", "メモ", kind = SearchItemKind.DerivedOp, groupOrder = 0, subOrder = 1),
            item("a0", "メモ", kind = SearchItemKind.Action, groupOrder = 0, subOrder = 0),
        )
        assertEquals(
            listOf("a0", "d0", "a2", "tool", "app"),
            ids(LocalSearch.search("メモ", items)),
        )
    }

    @Test
    fun `same rank orders by groupOrder then id`() {
        val items = listOf(
            item("app.c", "メモ", groupOrder = 1),
            item("app.b", "メモ", groupOrder = 0),
            item("app.a", "メモ", groupOrder = 0),
        )
        assertEquals(
            listOf("app.a", "app.b", "app.c"),
            ids(LocalSearch.search("メモ", items)),
        )
    }

    @Test
    fun `duplicate ids collapse to the first occurrence`() {
        val items = listOf(
            item("dup", "カメラ", groupOrder = 0),
            item("dup", "カメラ別", groupOrder = 1),
            item("other", "カメラ", groupOrder = 2),
        )
        val result = LocalSearch.search("カメラ", items)
        assertEquals(listOf("dup", "other"), ids(result))
        assertEquals("カメラ", result[0].primary)
    }

    @Test
    fun `japanese and width variants of the query match`() {
        val items = listOf(
            item("kata", "カメラ"),
            item("lat", "Camera"),
            item("other", "メモ"),
        )
        assertEquals(listOf("kata"), ids(LocalSearch.search("かめら", items)))
        assertEquals(listOf("kata"), ids(LocalSearch.search("ｶﾒﾗ", items)))
        assertEquals(listOf("lat"), ids(LocalSearch.search("ＣＡＭＥＲＡ", items)))
        assertEquals(listOf("lat"), ids(LocalSearch.search("camera", items)))
    }

    @Test
    fun `queries beyond the input cap still work`() {
        val longName = "メモ" + "あ".repeat(300)
        val items = listOf(item("long", longName), item("short", "メモ"))
        assertEquals(listOf("long"), ids(LocalSearch.search(longName, items)))
        assertTrue(LocalSearch.search("メモ" + "x".repeat(300), items).isEmpty())
    }

    @Test
    fun `exposes the design constants`() {
        assertEquals(256, LocalSearch.MAX_QUERY_CHARS)
        assertEquals(20, LocalSearch.PAGE_SIZE)
    }
}
