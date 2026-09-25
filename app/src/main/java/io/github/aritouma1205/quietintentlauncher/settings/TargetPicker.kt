package io.github.aritouma1205.quietintentlauncher.settings

import android.graphics.drawable.Drawable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.apps.AppEntry
import io.github.aritouma1205.quietintentlauncher.apps.ShortcutEntry
import io.github.aritouma1205.quietintentlauncher.launch.LinkValidation
import io.github.aritouma1205.quietintentlauncher.ui.DrawableIcon

/** The assignable target kinds (design 6, 7). */
enum class PickerMode { App, Link, Shortcut }

/**
 * Target picker shared by the action editor, derived-op editor, tool
 * editor and the intro (design 6, 7, 11.1): アプリ / HTTPSリンク /
 * 公開ショートカット. [allowedModes] narrows the offered kinds — the tool
 * targets only accept what the tool can run (design 7). It never navigates
 * to a store page on its own.
 */
@Composable
fun TargetPicker(
    current: StoredTarget?,
    apps: List<AppEntry>?,
    iconLoader: (AppEntry) -> Drawable?,
    isHomeRoleHeld: Boolean,
    shortcutsFor: suspend (String) -> List<ShortcutEntry>,
    onPick: (StoredTarget?) -> Unit,
    onPendingInvalid: (Boolean) -> Unit = {},
    allowedModes: Set<PickerMode> = PickerMode.entries.toSet(),
) {
    var mode by rememberSaveable {
        mutableStateOf(
            when (current) {
                is StoredTarget.App -> PickerMode.App
                is StoredTarget.HttpsLink -> PickerMode.Link
                is StoredTarget.Shortcut -> PickerMode.Shortcut
                null -> PickerMode.App
            }.name,
        )
    }
    // A stored mode outside the allowed set (e.g. a link on a tool that
    // only accepts apps) falls back to the first allowed mode.
    val requestedMode = PickerMode.valueOf(mode)
    val activeMode =
        if (requestedMode in allowedModes) requestedMode else allowedModes.first()

    // Bumping this recreates the link editor below so 「解除する」 also
    // resets a pending invalid input and its reported validity, letting an
    // explicitly cleared target save as unset.
    var clearCount by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            if (PickerMode.App in allowedModes) {
                FilterChip(
                    selected = activeMode == PickerMode.App,
                    onClick = { mode = PickerMode.App.name },
                    label = { Text(stringResource(R.string.target_kind_app)) },
                )
            }
            if (PickerMode.Link in allowedModes) {
                FilterChip(
                    selected = activeMode == PickerMode.Link,
                    onClick = { mode = PickerMode.Link.name },
                    label = { Text(stringResource(R.string.target_kind_link)) },
                )
            }
            if (PickerMode.Shortcut in allowedModes) {
                FilterChip(
                    selected = activeMode == PickerMode.Shortcut,
                    onClick = { mode = PickerMode.Shortcut.name },
                    label = { Text(stringResource(R.string.target_kind_shortcut)) },
                )
            }
            if (current != null) {
                TextButton(
                    onClick = { clearCount++; onPick(null) },
                    modifier = Modifier.align(Alignment.CenterVertically),
                ) {
                    Text(stringResource(R.string.target_clear))
                }
            }
        }

        when (activeMode) {
            PickerMode.App -> AppPickList(
                apps = apps,
                iconLoader = iconLoader,
                onPick = { entry ->
                    onPick(StoredTarget.App(entry.component.flattenToShortString()))
                },
            )
            PickerMode.Link -> key(clearCount) {
                HttpsLinkEditor(
                    current = current as? StoredTarget.HttpsLink,
                    onPick = onPick,
                    onPendingInvalid = onPendingInvalid,
                )
            }
            PickerMode.Shortcut -> ShortcutPickList(
                apps = apps,
                iconLoader = iconLoader,
                isHomeRoleHeld = isHomeRoleHeld,
                shortcutsFor = shortcutsFor,
                onPick = { entry ->
                    onPick(StoredTarget.Shortcut(entry.packageName, entry.id))
                },
            )
        }
    }
}

/** App list shared by the target picker and the intro assignment dialog. */
@Composable
fun AppPickList(
    apps: List<AppEntry>?,
    iconLoader: (AppEntry) -> Drawable?,
    onPick: (AppEntry) -> Unit,
) {
    when {
        apps == null -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        apps.isEmpty() -> Text(
            text = stringResource(R.string.all_apps_empty),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        else -> Column {
            apps.forEach { entry ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .clickable(
                            onClickLabel = entry.label,
                            onClick = { onPick(entry) },
                        )
                        .padding(vertical = 6.dp),
                ) {
                    DrawableIcon(
                        loader = { iconLoader(entry) },
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                    )
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(
                            text = entry.label,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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

/**
 * HTTPS link input with save-time validation (design 6): the destination
 * host is shown before saving and only a valid https:// URL reaches the
 * draft.
 */
@Composable
private fun HttpsLinkEditor(
    current: StoredTarget.HttpsLink?,
    onPick: (StoredTarget?) -> Unit,
    onPendingInvalid: (Boolean) -> Unit,
) {
    var raw by rememberSaveable { mutableStateOf(current?.url ?: "") }
    val valid = LinkValidation.isValidHttpsUrl(raw)
    val host = LinkValidation.httpsHost(raw)

    // An invalid non-blank input never reaches the draft; report it so the
    // parent can refuse to save while it is pending. Leaving the editor
    // (mode switch, picker close) clears the pending flag.
    val invalid = raw.isNotBlank() && !valid
    LaunchedEffect(invalid) { onPendingInvalid(invalid) }
    DisposableEffect(Unit) {
        onDispose { onPendingInvalid(false) }
    }

    OutlinedTextField(
        value = raw,
        onValueChange = { input ->
            raw = input
            when {
                // Clearing the field clears the stored link; an invalid
                // non-blank input keeps the draft untouched and shows the
                // error state instead.
                input.isBlank() -> onPick(null)
                LinkValidation.isValidHttpsUrl(input) ->
                    onPick(StoredTarget.HttpsLink(input.trim()))
            }
        },
        label = { Text(stringResource(R.string.target_link_hint)) },
        isError = raw.isNotEmpty() && !valid,
        singleLine = true,
        supportingText = {
            Text(
                text = when {
                    host != null -> stringResource(R.string.target_link_host, host)
                    raw.isNotEmpty() -> stringResource(R.string.target_link_invalid)
                    else -> stringResource(R.string.target_link_hint)
                },
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    )
}

/**
 * Public shortcut picking (design 6): pick the source app, then one of its
 * currently listed shortcuts. Without the HOME role, or when the app
 * publishes none, an explanation replaces the empty list.
 */
@Composable
private fun ShortcutPickList(
    apps: List<AppEntry>?,
    iconLoader: (AppEntry) -> Drawable?,
    isHomeRoleHeld: Boolean,
    shortcutsFor: suspend (String) -> List<ShortcutEntry>,
    onPick: (ShortcutEntry) -> Unit,
) {
    var packageName by rememberSaveable { mutableStateOf<String?>(null) }

    if (!isHomeRoleHeld) {
        Text(
            text = stringResource(R.string.target_shortcut_needs_home),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        return
    }

    val pkg = packageName
    if (pkg == null) {
        Text(
            text = stringResource(R.string.target_shortcut_pick_app),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 12.dp),
        )
        AppPickList(
            apps = apps,
            iconLoader = iconLoader,
            onPick = { packageName = it.packageName },
        )
        return
    }

    val shortcuts by produceState<List<ShortcutEntry>?>(null, pkg) {
        value = shortcutsFor(pkg)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        TextButton(onClick = { packageName = null }) {
            Text(stringResource(R.string.back))
        }
        Text(
            text = pkg,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    when {
        shortcuts == null -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        shortcuts!!.isEmpty() -> Text(
            text = stringResource(R.string.target_shortcut_empty),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        else -> Column {
            shortcuts!!.forEach { entry ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .clickable(
                            onClickLabel = entry.label,
                            onClick = { onPick(entry) },
                        )
                        .padding(vertical = 10.dp),
                ) {
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Human-readable summary of a stored target for editor rows. */
@Composable
fun targetSummary(target: StoredTarget?, apps: List<AppEntry>?): String =
    when (target) {
        null -> stringResource(R.string.do_target_unset)
        is StoredTarget.App -> apps
            ?.firstOrNull {
                it.component.flattenToShortString() == target.component
            }
            ?.label
            ?: target.component
        is StoredTarget.Shortcut ->
            stringResource(R.string.target_shortcut_summary, target.packageName)
        is StoredTarget.HttpsLink -> target.url
    }
