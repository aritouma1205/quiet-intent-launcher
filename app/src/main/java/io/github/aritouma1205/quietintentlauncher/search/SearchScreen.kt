package io.github.aritouma1205.quietintentlauncher.search

import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.apps.AppEntry
import io.github.aritouma1205.quietintentlauncher.ui.DeepScrim
import io.github.aritouma1205.quietintentlauncher.ui.DrawableIcon
import io.github.aritouma1205.quietintentlauncher.ui.imageVector
import kotlinx.coroutines.delay

/** 「最近」は空クエリ時に最大6件 (design 9). */
private const val MAX_RECENTS_SHOWN = 6

/**
 * Universal Search (design 9): bottom-anchored input, local results ranked
 * by match strength, 「最近」 on the empty query, and a strictly separated
 * external-search block — nothing leaves the device while typing; the query
 * is shared only through the explicit Web/共有 actions.
 *
 * The query lives in the ViewModel, not in composable state: it is never
 * persisted and is cleared whenever the stack leaves Search (design 3).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    query: String,
    rows: List<SearchRow>,
    recents: List<RecentRow>,
    chatGptAvailable: Boolean,
    shareAvailable: Boolean,
    iconLoader: (AppEntry) -> Drawable?,
    onQueryChanged: (String) -> Unit,
    onRowTapped: (SearchRow) -> Unit,
    onRecentTapped: (RecentRow) -> Unit,
    onWebSearch: (String) -> Unit,
    onShare: (String) -> Unit,
    onAllApps: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
) {
    var visibleCount by rememberSaveable { mutableIntStateOf(LocalSearch.PAGE_SIZE) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible

    // Back peels one layer at a time (design 9.4): keyboard -> screen.
    BackHandler {
        if (imeVisible) keyboard?.hide() else onBack()
    }

    LaunchedEffect(Unit) {
        // Wait a frame so the field exists before asking for the IME.
        delay(50)
        focusRequester.requestFocus()
        keyboard?.show()
    }
    // New query restarts the result window at the first page.
    LaunchedEffect(query) { visibleCount = LocalSearch.PAGE_SIZE }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepScrim)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            if (query.isBlank()) {
                // 「最近」は最大6件 (design 9).
                val shownRecents = recents.take(MAX_RECENTS_SHOWN)
                if (shownRecents.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.search_recent_section),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    shownRecents.forEach { recent ->
                        SearchResultRow(
                            title = recent.label,
                            subtitle = if (recent.showPackage) {
                                recent.appEntry?.packageName
                            } else {
                                null
                            },
                            icon = recent.appEntry?.let { entry ->
                                { DrawableIcon(loader = { iconLoader(entry) }, contentDescription = null, modifier = Modifier.size(28.dp)) }
                            },
                            onClick = { onRecentTapped(recent) },
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }
            } else {
                val shown = rows.take(visibleCount)
                if (shown.isEmpty()) {
                    // 0件でも入口は残す（design 9.1）— the entries below stay.
                    Text(
                        text = stringResource(R.string.search_empty_results),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                shown.forEach { row ->
                    SearchRowItem(row = row, iconLoader = iconLoader) {
                        onRowTapped(row)
                    }
                }
                if (rows.size > visibleCount) {
                    SearchEntry(
                        label = stringResource(R.string.search_more),
                        onClick = { visibleCount += LocalSearch.PAGE_SIZE },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                // External search is an explicit hand-off (design 9.2).
                SearchEntry(
                    label = stringResource(R.string.search_web),
                    onClick = { onWebSearch(query) },
                )
                if (chatGptAvailable) {
                    SearchEntry(
                        label = stringResource(R.string.search_chatgpt),
                        onClick = { onShare(query) },
                    )
                } else if (shareAvailable) {
                    SearchEntry(
                        label = stringResource(R.string.search_share),
                        onClick = { onShare(query) },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
            SearchEntry(
                label = stringResource(R.string.all_apps_entry),
                onClick = onAllApps,
            )
            SearchEntry(
                label = stringResource(R.string.settings_entry),
                onClick = onSettings,
            )
        }
        TextField(
            value = query,
            onValueChange = {
                onQueryChanged(it.take(LocalSearch.MAX_QUERY_CHARS))
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            placeholder = { Text(stringResource(R.string.search_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = { if (query.isNotBlank()) onWebSearch(query) },
            ),
        )
    }
}

/** One ranked local result. */
@Composable
private fun SearchRowItem(
    row: SearchRow,
    iconLoader: (AppEntry) -> Drawable?,
    onClick: () -> Unit,
) {
    when (row) {
        is SearchRow.Action -> SearchResultRow(
            title = row.action.name,
            subtitle = null,
            icon = {
                Icon(
                    imageVector = row.action.icon.imageVector(),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            },
            onClick = onClick,
        )
        is SearchRow.Op -> SearchResultRow(
            title = row.op.label.ifBlank { row.action.name },
            subtitle = stringResource(R.string.search_kind_op, row.action.name),
            icon = {
                Icon(
                    imageVector = row.action.icon.imageVector(),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            },
            onClick = onClick,
        )
        is SearchRow.App -> SearchResultRow(
            title = row.entry.label,
            subtitle = if (row.showPackage) row.entry.packageName else null,
            icon = {
                DrawableIcon(
                    loader = { iconLoader(row.entry) },
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
            },
            onClick = onClick,
        )
        is SearchRow.Tool -> SearchResultRow(
            title = stringResource(row.tool.labelRes),
            subtitle = stringResource(R.string.search_kind_tool),
            icon = null,
            onClick = onClick,
        )
        is SearchRow.Setting -> SearchResultRow(
            title = stringResource(row.destination.labelRes),
            subtitle = stringResource(R.string.search_kind_setting),
            icon = null,
            onClick = onClick,
        )
    }
}

@Composable
private fun SearchResultRow(
    title: String,
    subtitle: String?,
    icon: (@Composable () -> Unit)?,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        if (icon != null) {
            Row(
                modifier = Modifier.size(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon()
            }
        }
        Column(Modifier.padding(start = if (icon != null) 12.dp else 0.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SearchEntry(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
    )
}
