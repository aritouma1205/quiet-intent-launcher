package io.github.aritouma1205.quietintentlauncher.settings

/**
 * Shared bar-geometry math (design 4.1). The production edge bar and the
 * settings preview must produce the same position for the same settings,
 * so both sides run this one formula:
 *
 *     top = bias * (usableHeight - effectiveLength)
 *
 * bias 0 pins the bar's top to the track's top edge, bias 1 its bottom to
 * the track's bottom edge. Inputs are clamped here — never trusting the
 * caller — so the two renderers cannot drift apart.
 */

/** The drawn bar length: a bar can never be taller than its track. */
internal fun edgeBarEffectiveLength(usableHeight: Float, barLength: Float): Float =
    barLength.coerceAtMost(usableHeight)

/** The bar's top offset from the top of the usable track. */
internal fun edgeBarTopOffset(
    bias: Float,
    usableHeight: Float,
    barLength: Float,
): Float =
    bias.coerceIn(0f, 1f) *
        (usableHeight - edgeBarEffectiveLength(usableHeight, barLength))
