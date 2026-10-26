package io.github.aritouma1205.quietintentlauncher.system

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * I49 L1: the pure slider-fraction ↔ stream-index conversion (Issue #49).
 * Covers the linear mapping, the 0..1 / 0..max clamps, the
 * index→fraction→index round trip and the max<=0 guard that keeps a
 * broken AudioManager read from producing an out-of-range write.
 */
class VolumeScaleTest {

    @Test
    fun fractionMapsLinearlyOntoTheIndexRange() {
        assertEquals(0, volumeIndexFromFraction(0f, 15))
        assertEquals(15, volumeIndexFromFraction(1f, 15))
        // 0.5 * 15 = 7.5 rounds half-up to the nearer stop.
        assertEquals(8, volumeIndexFromFraction(0.5f, 15))
        assertEquals(3, volumeIndexFromFraction(0.2f, 15))
        assertEquals(5, volumeIndexFromFraction(0.5f, 10))
    }

    @Test
    fun fractionIsClampedIntoZeroToOne() {
        assertEquals(0, volumeIndexFromFraction(-0.2f, 15))
        assertEquals(0, volumeIndexFromFraction(Float.NEGATIVE_INFINITY, 15))
        assertEquals(15, volumeIndexFromFraction(1.4f, 15))
        assertEquals(15, volumeIndexFromFraction(Float.POSITIVE_INFINITY, 15))
    }

    @Test
    fun indexMapsLinearlyOntoTheFractionRange() {
        assertEquals(0f, volumeFractionFromIndex(0, 15), 1e-6f)
        assertEquals(1f, volumeFractionFromIndex(15, 15), 1e-6f)
        assertEquals(0.5f, volumeFractionFromIndex(5, 10), 1e-6f)
        assertEquals(0.2f, volumeFractionFromIndex(3, 15), 1e-6f)
    }

    @Test
    fun indexIsClampedIntoZeroToMax() {
        assertEquals(0f, volumeFractionFromIndex(-3, 15), 1e-6f)
        assertEquals(1f, volumeFractionFromIndex(20, 15), 1e-6f)
    }

    @Test
    fun indexSurvivesTheRoundTripForEveryStep() {
        // The contract the slider relies on: rendering index i and feeding
        // the snapped fraction back must reproduce i exactly.
        for (max in intArrayOf(1, 5, 10, 15, 25)) {
            for (index in 0..max) {
                assertEquals(
                    "max=$max index=$index",
                    index,
                    volumeIndexFromFraction(
                        volumeFractionFromIndex(index, max),
                        max,
                    ),
                )
            }
        }
    }

    @Test
    fun nonPositiveMaxDegradesToZeroBothWays() {
        // An unreadable AudioManager (max<=0) must never let a drag write
        // an out-of-range index or render a broken position.
        assertEquals(0, volumeIndexFromFraction(0.7f, 0))
        assertEquals(0, volumeIndexFromFraction(0.7f, -4))
        assertEquals(0f, volumeFractionFromIndex(3, 0), 1e-6f)
        assertEquals(0f, volumeFractionFromIndex(3, -4), 1e-6f)
    }
}
