package io.github.aritouma1205.quietintentlauncher.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The theme owns the default text color for every screen (design 12:
 * 文字は基本白). Screens draw plain Boxes over dark scrims and the
 * wallpaper, so without a provided LocalContentColor every unspecified
 * Text fell back to black — the F-01 field failure.
 */
@RunWith(AndroidJUnit4::class)
class ThemeTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun themeProvidesALightDefaultContentColor() {
        var inherited = Color.Unspecified
        var expected = Color.Unspecified
        rule.setContent {
            QuietLauncherTheme {
                expected = MaterialTheme.colorScheme.onSurface
                // Probe at depth, the way any screen's unspecified Text
                // resolves its color: nothing between the theme and the
                // leaves may turn it dark again.
                Box(Modifier.size(1.dp)) {
                    Box(Modifier.size(1.dp)) {
                        inherited = LocalContentColor.current
                    }
                }
            }
        }
        rule.waitForIdle()
        assertEquals(expected, inherited)
        assertTrue(inherited.luminance() > 0.5f)
    }
}
