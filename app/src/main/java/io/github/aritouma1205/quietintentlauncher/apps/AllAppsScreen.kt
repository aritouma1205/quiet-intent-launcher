package io.github.aritouma1205.quietintentlauncher.apps

import android.graphics.drawable.Drawable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.ui.DeepScrim
import io.github.aritouma1205.quietintentlauncher.ui.DrawableIcon

/**
 * All launchable apps of the personal profile (design 9.3): a 「最近」
 * section built from local launch history, then a categorized icon grid —
 * ApplicationInfo.category sections in [AppCategory] order, app labels in
 * Japanese-locale order inside each. Tap launches, long-press shows app
 * info. Duplicate labels get the package name as a secondary line so
 * same-named apps stay distinguishable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AllAppsScreen(
    apps: List<AppEntry>?,
    recents: List<AppEntry>,
    iconLoader: (AppEntry) -> Drawable?,
    onLaunch: (AppEntry) -> Unit,
    onAppInfo: (AppEntry) -> Unit,
    onBack: () -> Unit,
) {
    var detailTarget by remember { mutableStateOf<AppEntry?>(null) }
    val duplicatedLabels = remember(apps) {
        apps?.groupingBy { it.label }?.eachCount()?.filterValues { it > 1 }?.keys
            ?: emptySet()
    }
    val sections = remember(apps, recents) {
        AppCategories.sections(apps.orEmpty(), recents) { it.category }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepScrim)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.back))
            }
            Text(
                text = stringResource(R.string.all_apps_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        when {
            apps == null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            apps.isEmpty() && recents.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.all_apps_empty))
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 88.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                sections.forEach { section ->
                    item(
                        key = "header:${section.titleRes}",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        Text(
                            text = stringResource(section.titleRes),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(
                                start = 16.dp,
                                top = 16.dp,
                                bottom = 4.dp,
                            ),
                        )
                    }
                    items(
                        section.apps,
                        key = { "${section.titleRes}:${it.key}" },
                    ) { entry ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .combinedClickable(
                                    onClick = { onLaunch(entry) },
                                    onLongClick = { detailTarget = entry },
                                )
                                .padding(horizontal = 4.dp, vertical = 8.dp),
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
                                    Text(
                                        text = entry.label.first().toString(),
                                        style = MaterialTheme.typography.bodyMedium,
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
                            if (entry.label in duplicatedLabels) {
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
                }
            }
        }
    }

    detailTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { detailTarget = null },
            confirmButton = {
                TextButton(onClick = {
                    detailTarget = null
                    onAppInfo(entry)
                }) {
                    Text(stringResource(R.string.app_info))
                }
            },
            dismissButton = {
                TextButton(onClick = { detailTarget = null }) {
                    Text(stringResource(R.string.back))
                }
            },
            title = { Text(entry.label) },
            text = { Text(entry.packageName) },
        )
    }
}
