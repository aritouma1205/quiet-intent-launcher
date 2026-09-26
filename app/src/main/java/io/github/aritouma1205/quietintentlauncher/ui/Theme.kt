package io.github.aritouma1205.quietintentlauncher.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Deep screens float over the wallpaper on a dark scrim (design 12). */
val DeepScrim = Color.Black.copy(alpha = 0.80f)
val QuietBadgeBackground = Color.Black.copy(alpha = 0.45f)

/**
 * Typography hierarchy (design 12): primary information — panel titles,
 * action names, the GLANCE/TODAY clock — is heavier than supporting text
 * so the two stay visibly distinct while every documented size (body
 * >= 16sp, action names 20sp) is kept. Section labels ride on
 * [Typography.labelMedium] with wider tracking; body styles stay Normal.
 */
private val QuietTypography = with(Typography()) {
    copy(
        displayMedium = displayMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = labelMedium.copy(
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.75.sp,
        ),
    )
}

@Composable
fun QuietLauncherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(),
        typography = QuietTypography,
    ) {
        // Nothing here sits on a light surface, so the default content
        // color — black, meant for light themes — is unreadable against
        // the scrims and the wallpaper. Every screen draws plain Boxes
        // rather than Surfaces, so no lower layer provides a content
        // color either: unspecified text fell back to black everywhere.
        // The theme owns the fix so a screen can never regress to it
        // (design 12: 文字は基本白).
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurface,
            content = content,
        )
    }
}
