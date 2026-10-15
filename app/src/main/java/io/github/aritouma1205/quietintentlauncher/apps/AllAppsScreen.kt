package io.github.aritouma1205.quietintentlauncher.apps

import android.graphics.drawable.Drawable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.ui.DeepScrim

/**
 * All launchable apps of the personal profile (design 9.3): a 「最近」
 * section built from local launch history, then a categorized icon grid —
 * ApplicationInfo.category sections in [AppCategory] order, app labels in
 * Japanese-locale order inside each. Tap launches; long-press opens an
 * action dialog (app info, and uninstall when [AppEntry.canUninstall]).
 * Duplicate labels get the package name as a secondary line so
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
    onUninstallApp: (AppEntry) -> Unit,
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

    // The dialog tracks the displayed package, not the entry object: after
    // the OS uninstall confirmation the package leaves the catalog (a
    // reverted factory version keeps the same packageName and must keep
    // the dialog open). While the first load is still out (apps == null)
    // nothing can be proven absent, so the dialog is left alone.
    val detailPackage = detailTarget?.packageName
    val detailStillListed = remember(apps, recents, detailPackage) {
        detailPackage == null || apps == null ||
            (apps + recents).any { it.packageName == detailPackage }
    }
    LaunchedEffect(detailStillListed) {
        if (!detailStillListed) detailTarget = null
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
                            modifier = Modifier
                                .semantics { heading() }
                                .padding(
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
                        AppGridCell(
                            entry = entry,
                            showPackage = entry.label in duplicatedLabels,
                            iconLoader = iconLoader,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .combinedClickable(
                                    onClickLabel = entry.label,
                                    onClick = { onLaunch(entry) },
                                    onLongClickLabel =
                                        stringResource(R.string.do_action_menu),
                                    onLongClick = { detailTarget = entry },
                                ),
                        )
                    }
                }
            }
        }
    }

    detailTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { detailTarget = null },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { detailTarget = null }) {
                    Text(stringResource(R.string.back))
                }
            },
            title = { Text(entry.label) },
            text = {
                Column {
                    Text(entry.packageName)
                    DialogActionRow(label = stringResource(R.string.app_info)) {
                        detailTarget = null
                        onAppInfo(entry)
                    }
                    if (entry.canUninstall) {
                        DialogActionRow(
                            label = stringResource(R.string.all_apps_uninstall),
                        ) {
                            // Keep the dialog up: the OS confirmation owns
                            // dismissal — a completed removal drops the
                            // package from the list above, which closes it.
                            onUninstallApp(entry)
                        }
                    }
                }
            },
        )
    }
}

/**
 * One full-width action line of the long-press dialog (design 9.3). The
 * text itself carries the click so the merged semantics node exposes both
 * the label and the action to accessibility services.
 */
@Composable
private fun DialogActionRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}
