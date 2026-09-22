package io.github.aritouma1205.quietintentlauncher.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Deep screens float over the wallpaper on a dark scrim (design 12). */
val DeepScrim = Color.Black.copy(alpha = 0.80f)
val QuietBadgeBackground = Color.Black.copy(alpha = 0.45f)

@Composable
fun QuietLauncherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(),
        content = content,
    )
}
