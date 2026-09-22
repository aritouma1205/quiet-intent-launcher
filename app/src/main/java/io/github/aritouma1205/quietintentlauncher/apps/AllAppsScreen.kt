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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.ui.DeepScrim
import io.github.aritouma1205.quietintentlauncher.ui.DrawableIcon

/**
 * All launchable apps of the personal profile (design 9.3): icon + label
 * list, tap to launch, long-press for app info. Duplicate labels get the
 * package name as a secondary line so same-named apps stay distinguishable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AllAppsScreen(
    apps: List<AppEntry>?,
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
            apps.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.all_apps_empty))
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(apps, key = { it.key }) { entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onLaunch(entry) },
                                onLongClick = { detailTarget = entry },
                            )
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                    ) {
                        DrawableIcon(
                            loader = { iconLoader(entry) },
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                        )
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(
                                text = entry.label,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (entry.label in duplicatedLabels) {
                                Text(
                                    text = entry.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
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
