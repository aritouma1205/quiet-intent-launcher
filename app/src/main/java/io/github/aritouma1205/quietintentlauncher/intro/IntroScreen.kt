package io.github.aritouma1205.quietintentlauncher.intro

import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.apps.AppEntry
import io.github.aritouma1205.quietintentlauncher.settings.AppPickList
import io.github.aritouma1205.quietintentlauncher.settings.DoAction
import io.github.aritouma1205.quietintentlauncher.settings.StoredTarget
import io.github.aritouma1205.quietintentlauncher.settings.targetSummary
import io.github.aritouma1205.quietintentlauncher.ui.DeepScrim
import io.github.aritouma1205.quietintentlauncher.ui.imageVector
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * First-run introduction (design 11.1):
 * 1) short explanation on the wallpaper + HOME role request, 2) a skippable
 * step assigning launch targets to the six actions (app targets only; unset
 * actions can be configured later from the DO panel), then 「はじめる」.
 * No extra permissions are requested here.
 */
@Composable
fun IntroScreen(
    isDefaultHome: Boolean,
    actions: List<DoAction>,
    apps: List<AppEntry>?,
    iconLoader: (AppEntry) -> Drawable?,
    onSetHome: () -> Unit,
    onDone: (List<DoAction>, (Boolean) -> Unit) -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(1) }
    val actionsSaver = remember {
        Saver<List<DoAction>, String>(
            save = {
                Json.encodeToString(ListSerializer(DoAction.serializer()), it)
            },
            restore = {
                Json.decodeFromString(ListSerializer(DoAction.serializer()), it)
            },
        )
    }
    var actionsDraft by rememberSaveable(stateSaver = actionsSaver) {
        mutableStateOf(actions)
    }

    when (step) {
        1 -> IntroExplanationStep(
            isDefaultHome = isDefaultHome,
            onSetHome = onSetHome,
            onNext = { step = 2 },
        )
        else -> IntroActionsStep(
            actions = actionsDraft,
            apps = apps,
            iconLoader = iconLoader,
            onAssign = { id, target ->
                actionsDraft = actionsDraft.map {
                    if (it.id == id) it.copy(target = target) else it
                }
            },
            onBack = { step = 1 },
            onDone = { done -> onDone(actionsDraft, done) },
        )
    }
}

@Composable
private fun IntroExplanationStep(
    isDefaultHome: Boolean,
    onSetHome: () -> Unit,
    onNext: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepScrim)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.intro_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.intro_body),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
        Spacer(Modifier.height(32.dp))
        if (!isDefaultHome) {
            Button(onClick = onSetHome, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.intro_set_home))
            }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.intro_next))
        }
        Text(
            text = stringResource(R.string.intro_note),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

/**
 * Design 11.1 step 3: the six actions listed as 未設定; tapping one opens a
 * compact app picker. Skipping leaves them unset — the DO panel's unset tap
 * offers the same configuration later.
 */
@Composable
private fun IntroActionsStep(
    actions: List<DoAction>,
    apps: List<AppEntry>?,
    iconLoader: (AppEntry) -> Drawable?,
    onAssign: (String, StoredTarget) -> Unit,
    onBack: () -> Unit,
    onDone: ((Boolean) -> Unit) -> Unit,
) {
    var pickFor by remember { mutableStateOf<DoAction?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepScrim)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Text(
                text = stringResource(R.string.intro_actions_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.intro_actions_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            actions.forEach { action ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .clickable(
                            onClickLabel = action.name,
                            onClick = { pickFor = action },
                        )
                        .padding(vertical = 10.dp),
                ) {
                    Icon(
                        imageVector = action.icon.imageVector(),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(
                            text = action.name,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = targetSummary(action.target, apps),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (saveFailed) {
            // A failed write keeps the draft and this step so はじめる can
            // be retried.
            Text(
                text = stringResource(R.string.settings_save_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            TextButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.back))
            }
            Button(
                onClick = {
                    saving = true
                    saveFailed = false
                    onDone { ok ->
                        saving = false
                        if (!ok) saveFailed = true
                    }
                },
                enabled = !saving,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.intro_done))
            }
        }
    }

    pickFor?.let { action ->
        AlertDialog(
            onDismissRequest = { pickFor = null },
            title = { Text(stringResource(R.string.intro_pick_app)) },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    AppPickList(
                        apps = apps,
                        iconLoader = iconLoader,
                        onPick = { entry ->
                            onAssign(
                                action.id,
                                StoredTarget.App(
                                    entry.component.flattenToShortString(),
                                ),
                            )
                            pickFor = null
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { pickFor = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
