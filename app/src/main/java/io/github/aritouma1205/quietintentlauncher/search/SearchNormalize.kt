package io.github.aritouma1205.quietintentlauncher.search

import java.text.Normalizer

/**
 * Query and label normalization for local search (design 9.1):
 * trim, NFKC, case folding and hiragana-to-katakana unification so that
 * Japanese, half/full-width and case variants compare equal. Runs of
 * whitespace collapse to a single ASCII space. No reading generation,
 * translation or typo guessing — editable aliases cover those.
 *
 * Pure JVM only; safe for host-side unit tests.
 */
object SearchNormalize {
    private val whitespaceRun = Regex("\\s+")

    /** Hiragana letters U+3041..U+3096 map to katakana at +0x60. */
    private fun Char.toKatakanaIfHiragana(): Char =
        if (this in 'ぁ'..'ゖ') this + 0x60 else this

    fun normalize(raw: String): String {
        val nfkc = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
        val folded = nfkc.lowercase()
        val unified = buildString(folded.length) {
            for (c in folded) append(c.toKatakanaIfHiragana())
        }
        return unified.trim().replace(whitespaceRun, " ")
    }

    fun words(raw: String): List<String> =
        normalize(raw).split(' ').filter { it.isNotEmpty() }
}
