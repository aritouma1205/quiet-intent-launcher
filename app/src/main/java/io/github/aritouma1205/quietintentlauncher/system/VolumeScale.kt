package io.github.aritouma1205.quietintentlauncher.system

import kotlin.math.roundToInt

/**
 * Pure fraction ↔ index conversion for the media-volume slider
 * (Issue #49). The slider works in a continuous 0..1 fraction while
 * AudioManager takes a discrete index in 0..max — keeping the mapping
 * pure lets unit tests pin the rounding, clamping and the broken-read
 * guard without a device.
 */

/**
 * Slider fraction → stream index. Out-of-range input is clamped; a
 * non-positive max (an unreadable AudioManager) always resolves to
 * index 0 so a broken read can never produce an out-of-range
 * setStreamVolume call.
 */
internal fun volumeIndexFromFraction(fraction: Float, max: Int): Int {
    if (max <= 0) return 0
    return (fraction.coerceIn(0f, 1f) * max).roundToInt().coerceIn(0, max)
}

/**
 * Stream index → slider fraction. The index is clamped into 0..max and
 * a non-positive max yields 0 — the slider rests at the left end rather
 * than rendering a meaningless position.
 */
internal fun volumeFractionFromIndex(index: Int, max: Int): Float {
    if (max <= 0) return 0f
    return index.coerceIn(0, max).toFloat() / max
}
