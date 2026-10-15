package io.github.aritouma1205.quietintentlauncher.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aritouma1205.quietintentlauncher.ui.QuietLauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I43-F-1 regression (design 4.1): the preview frame's content box must be
 * the same track height the shared geometry functions are fed. The bug was
 * `Modifier.height(220).padding(top=8)` shrinking the content to 212dp
 * while `edgeBarTopOffset` still received 220 — a bias=1 bar's bottom
 * overshot the frame by 8dp. Now the padding is an outer margin and the
 * track is exactly PREVIEW_HEIGHT; this test pins the containment and the
 * bottom-flush position on real layout bounds.
 *
 * Bounds note: `edgePreviewFrame`'s tag sits inside `.padding().height()`,
 * so it reports the content box (the track itself, margin excluded), and
 * the bar tags sit inside `.offset()` so they report the translated bar
 * position.
 */
@RunWith(AndroidJUnit4::class)
class EdgeSettingsPreviewTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun previewBarsStayInsideTheFrameAndBiasOneFlushesTheBottom() {
        rule.setContent {
            QuietLauncherTheme {
                EdgeSettingsScreen(
                    initial = SettingsData().copy(
                        leftBar = EdgeBarSettings(verticalBias = 0f),
                        rightBar = EdgeBarSettings(verticalBias = 1f),
                    ),
                    onSave = { _, done -> done(true) },
                    onBack = {},
                )
            }
        }
        rule.waitForIdle()

        val frame = rule.onNodeWithTag("edgePreviewFrame")
            .fetchSemanticsNode().boundsInRoot
        val leftBar = rule.onNodeWithTag("edgePreviewBarLeft")
            .fetchSemanticsNode().boundsInRoot
        val rightBar = rule.onNodeWithTag("edgePreviewBarRight")
            .fetchSemanticsNode().boundsInRoot

        // The two bars anchor to opposite frame edges, so they are distinct.
        assertEquals(frame.left, leftBar.left, 1f)
        assertEquals(frame.right, rightBar.right, 1f)
        assertTrue(
            "frame=$frame left=$leftBar right=$rightBar",
            rightBar.left > leftBar.left,
        )

        // bias=0: the bar top is the track top — equal to the frame's
        // content-box top, never above it.
        assertEquals(
            "frame=$frame left=$leftBar right=$rightBar",
            frame.top,
            leftBar.top,
            1.5f,
        )
        assertTrue(
            "frame=$frame left=$leftBar right=$rightBar",
            leftBar.bottom <= frame.bottom + 1f,
        )

        // bias=1 (the F-1 case): the bar bottom is the track bottom —
        // equal to the frame bottom, never past it.
        assertTrue(
            "frame=$frame left=$leftBar right=$rightBar",
            rightBar.top >= frame.top - 1f,
        )
        assertEquals(
            "frame=$frame left=$leftBar right=$rightBar",
            frame.bottom,
            rightBar.bottom,
            1.5f,
        )
    }
}
