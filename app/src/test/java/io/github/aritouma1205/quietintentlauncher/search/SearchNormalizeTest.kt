package io.github.aritouma1205.quietintentlauncher.search

import org.junit.Assert.assertEquals
import org.junit.Test

/** Query normalization for local search (design 9.1). */
class SearchNormalizeTest {

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("カメラ", SearchNormalize.normalize("  カメラ  "))
        assertEquals("カメラ", SearchNormalize.normalize("\tカメラ\n"))
    }

    @Test
    fun `nfkc folds full-width ascii to half-width`() {
        assertEquals("camera", SearchNormalize.normalize("ＣＡＭＥＲＡ"))
        assertEquals("123", SearchNormalize.normalize("１２３"))
    }

    @Test
    fun `nfkc folds half-width katakana to full-width`() {
        assertEquals("カメラ", SearchNormalize.normalize("ｶﾒﾗ"))
        assertEquals("アプリ", SearchNormalize.normalize("ｱﾌﾟﾘ"))
    }

    @Test
    fun `folds case`() {
        assertEquals("camera", SearchNormalize.normalize("Camera"))
        assertEquals("camera", SearchNormalize.normalize("CAMERA"))
    }

    @Test
    fun `unifies hiragana to katakana`() {
        assertEquals("カメラ", SearchNormalize.normalize("かめら"))
        assertEquals("キク", SearchNormalize.normalize("きく"))
        // Katakana input stays as-is.
        assertEquals("カメラ", SearchNormalize.normalize("カメラ"))
    }

    @Test
    fun `collapses whitespace runs to one space`() {
        assertEquals("a b c", SearchNormalize.normalize("a   b\t\tc"))
        // Ideographic space becomes an ASCII space via NFKC, then collapses.
        assertEquals("a b", SearchNormalize.normalize("a　　b"))
    }

    @Test
    fun `empty and blank input normalize to empty`() {
        assertEquals("", SearchNormalize.normalize(""))
        assertEquals("", SearchNormalize.normalize("  　 "))
    }

    @Test
    fun `words splits on collapsed spaces`() {
        assertEquals(
            listOf("カメラ", "アプリ"),
            SearchNormalize.words("  かめら   ｱﾌﾟﾘ "),
        )
        assertEquals(listOf("カメラ"), SearchNormalize.words("かめら"))
    }

    @Test
    fun `words returns empty list for blank input`() {
        assertEquals(emptyList<String>(), SearchNormalize.words(""))
        assertEquals(emptyList<String>(), SearchNormalize.words(" 　 "))
    }
}
