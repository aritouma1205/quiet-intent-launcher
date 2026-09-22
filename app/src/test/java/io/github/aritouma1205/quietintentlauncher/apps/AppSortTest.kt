package io.github.aritouma1205.quietintentlauncher.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSortTest {

    private fun key(label: String, pkg: String, activity: String = "$pkg.Main") =
        AppSortKey(label, pkg, activity)

    @Test
    fun `orders by Japanese label collation`() {
        val sorted = AppSort.sorted(
            listOf(
                key("カメラ", "pkg.c"),
                key("あいう", "pkg.a"),
                key("さしす", "pkg.b"),
            ),
        ) { it }
        assertEquals(
            listOf("あいう", "カメラ", "さしす"),
            sorted.map { it.label },
        )
    }

    @Test
    fun `same label falls back to package then activity`() {
        val sorted = AppSort.sorted(
            listOf(
                key("メモ", "pkg.b"),
                key("メモ", "pkg.a", "pkg.a.Second"),
                key("メモ", "pkg.a", "pkg.a.First"),
            ),
        ) { it }
        assertEquals(
            listOf("pkg.a.First", "pkg.a.Second", "pkg.b.Main"),
            sorted.map { it.activityName },
        )
    }

    @Test
    fun `result is stable for identical keys`() {
        val input = listOf(
            key("a", "pkg.a", "pkg.a.One"),
            key("a", "pkg.a", "pkg.a.One"),
        )
        assertEquals(input, AppSort.sorted(input) { it })
    }
}
