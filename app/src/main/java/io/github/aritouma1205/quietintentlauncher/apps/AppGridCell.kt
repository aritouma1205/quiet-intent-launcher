package io.github.aritouma1205.quietintentlauncher.apps

import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aritouma1205.quietintentlauncher.ui.DrawableIcon

/**
 * One app cell of the categorized grids (design 9.3, shared by All Apps and
 * the settings target picker): icon over a 2-line label, with a decorative
 * first-letter placeholder behind the icon and — only when the label is
 * ambiguous — the package name as a third line. Interaction semantics
 * (click label, long-press) live in [modifier] so each host keeps its own
 * action contract; hosts provide the 48dp touch target.
 */
@Composable
internal fun AppGridCell(
    entry: AppEntry,
    showPackage: Boolean,
    iconLoader: (AppEntry) -> Drawable?,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme
                            .onSurface.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(12.dp),
                    ),
            ) {
                // Decorative only — the label Text below is the spoken name,
                // so this glyph is hidden from TalkBack. First code point
                // keeps surrogate-pair labels intact.
                Text(
                    text = entry.label
                        .takeIf { it.isNotEmpty() }
                        ?.let { it.substring(0, it.offsetByCodePoints(0, 1)) }
                        ?: "?",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
            DrawableIcon(
                loader = { iconLoader(entry) },
                contentDescription = null,
                modifier = Modifier.size(48.dp),
            )
        }
        Text(
            text = entry.label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (showPackage) {
            Text(
                text = entry.packageName,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}
