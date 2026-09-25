package io.github.aritouma1205.quietintentlauncher.settings

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.calendar.CalendarAccess
import io.github.aritouma1205.quietintentlauncher.ui.DeepScrim
import io.github.aritouma1205.quietintentlauncher.weather.WeatherService
import kotlinx.serialization.json.Json

/**
 * 「情報」 settings (design 8, 11.2, 12): GLANCE position and auto-dismiss
 * time, the optional Quiet clock, opt-in weather with a manually chosen
 * region, and opt-in calendar events.
 *
 * Weather stays off until a region is picked; the screen explains what is
 * sent to the provider before the field is used. Disabling weather asks
 * first and, on save, deletes the region setting and the weather cache.
 * Calendar events require a runtime permission and at least one selected
 * calendar; the provider content is never persisted.
 */
@Composable
fun InfoSettingsScreen(
    initial: SettingsData,
    weatherUi: WeatherService.WeatherUi,
    regionResults: WeatherService.RegionSearchState,
    calendarGranted: Boolean,
    calendars: List<CalendarAccess.CalendarInfo>,
    onSave: (SettingsData, (Boolean) -> Unit) -> Unit,
    onSearchRegions: (String) -> Unit,
    onClearRegionSearch: () -> Unit,
    onRetryWeather: () -> Unit,
    onRefreshCalendars: () -> Unit,
    onBack: () -> Unit,
) {
    val settingsSaver = remember {
        Saver<SettingsData, String>(
            save = { Json.encodeToString(SettingsData.serializer(), it) },
            restore = { Json.decodeFromString(SettingsData.serializer(), it) },
        )
    }
    var draft by rememberSaveable(stateSaver = settingsSaver) {
        mutableStateOf(initial)
    }
    var saveFailed by rememberSaveable { mutableStateOf(false) }
    var exitConfirm by rememberSaveable { mutableStateOf(false) }
    var confirmDisableWeather by rememberSaveable { mutableStateOf(false) }
    var regionSearchOpen by rememberSaveable { mutableStateOf(false) }
    // Set when the weather switch opened the region picker — picking a
    // region then enables weather; opening via 地域を選ぶ never does.
    var enablingWeather by rememberSaveable { mutableStateOf(false) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }

    // The calendar list and the live grant state refresh on entry.
    LaunchedEffect(Unit) { onRefreshCalendars() }

    // READ_CALENDAR request result lands in the draft: granted enables the
    // events switch, denied keeps it off and explains (design 8.1, 13).
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            permissionDenied = false
            draft = draft.copy(info = draft.info.copy(eventsEnabled = true))
            onRefreshCalendars()
        } else {
            permissionDenied = true
        }
    }

    fun attemptSave() {
        onSave(draft) { ok ->
            if (ok) onBack() else saveFailed = true
        }
    }

    fun requestExit() {
        if (regionSearchOpen) {
            enablingWeather = false
            regionSearchOpen = false
            onClearRegionSearch()
            return
        }
        if (draft != initial) exitConfirm = true else onBack()
    }
    BackHandler { requestExit() }

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
            TextButton(onClick = { requestExit() }) {
                Text(stringResource(R.string.back))
            }
            Text(
                text = stringResource(
                    if (regionSearchOpen) {
                        R.string.region_search_title
                    } else {
                        R.string.info_settings_title
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        if (regionSearchOpen) {
            RegionSearchBody(
                results = regionResults,
                onQueryChanged = onSearchRegions,
                modifier = Modifier.weight(1f),
                onPick = { location ->
                    // A pick lands in the draft; the enable intent from the
                    // switch is honoured only on that path (design 8.2).
                    draft = draft.copy(
                        info = draft.info.copy(
                            weather = draft.info.weather.copy(
                                enabled = draft.info.weather.enabled || enablingWeather,
                                location = location,
                            ),
                        ),
                    )
                    enablingWeather = false
                    regionSearchOpen = false
                    onClearRegionSearch()
                },
            )
            return@Column
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            // GLANCE position + auto-dismiss (design 8.3, 11.2).
            InfoLabel(stringResource(R.string.info_glance_position))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                listOf(
                    GlancePosition.Top to R.string.info_glance_top,
                    GlancePosition.Center to R.string.info_glance_center,
                    GlancePosition.Bottom to R.string.info_glance_bottom,
                ).forEach { (position, labelRes) ->
                    FilterChip(
                        selected = draft.info.glancePosition == position,
                        onClick = {
                            draft = draft.copy(
                                info = draft.info.copy(glancePosition = position),
                            )
                        },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }
            InfoLabel(stringResource(R.string.info_glance_timeout))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                listOf(2, 3, 5, 10).forEach { seconds ->
                    FilterChip(
                        selected = draft.info.glanceDismissSeconds == seconds,
                        onClick = {
                            draft = draft.copy(
                                info = draft.info.copy(glanceDismissSeconds = seconds),
                            )
                        },
                        label = {
                            Text(stringResource(R.string.info_seconds_format, seconds))
                        },
                    )
                }
                FilterChip(
                    selected = draft.info.glanceDismissSeconds ==
                        InfoSettings.GLANCE_DISMISS_NEVER,
                    onClick = {
                        draft = draft.copy(
                            info = draft.info.copy(
                                glanceDismissSeconds = InfoSettings.GLANCE_DISMISS_NEVER,
                            ),
                        )
                    },
                    label = { Text(stringResource(R.string.info_glance_never)) },
                )
            }

            // Optional small clock on Quiet (design 12).
            InfoSwitchRow(
                label = stringResource(R.string.info_clock),
                note = null,
                checked = draft.clock.enabled,
                onCheckedChange = { checked ->
                    draft = draft.copy(clock = draft.clock.copy(enabled = checked))
                },
            )
            if (draft.clock.enabled) {
                InfoLabel(stringResource(R.string.info_clock_position))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    listOf(
                        QuietClockPosition.TopStart to R.string.info_clock_left,
                        QuietClockPosition.TopCenter to R.string.info_clock_center,
                        QuietClockPosition.TopEnd to R.string.info_clock_right,
                    ).forEach { (position, labelRes) ->
                        FilterChip(
                            selected = draft.clock.position == position,
                            onClick = {
                                draft = draft.copy(
                                    clock = draft.clock.copy(position = position),
                                )
                            },
                            label = { Text(stringResource(labelRes)) },
                        )
                    }
                }
            }

            // Weather (design 8.2): the explanation precedes the switch.
            Text(
                text = stringResource(R.string.info_weather_note),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 24.dp),
            )
            InfoSwitchRow(
                label = stringResource(R.string.info_weather),
                note = null,
                checked = draft.info.weather.enabled,
                onCheckedChange = { checked ->
                    if (checked) {
                        if (draft.info.weather.location == null) {
                            // Enabling needs a region first (design 8.2).
                            enablingWeather = true
                            regionSearchOpen = true
                        } else {
                            draft = draft.copy(
                                info = draft.info.copy(
                                    weather = draft.info.weather.copy(enabled = true),
                                ),
                            )
                        }
                    } else {
                        confirmDisableWeather = true
                    }
                },
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = draft.info.weather.location?.name
                            ?: stringResource(R.string.info_weather_region_none),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                TextButton(onClick = {
                    enablingWeather = false
                    regionSearchOpen = true
                }) {
                    Text(stringResource(R.string.info_weather_pick_region))
                }
            }
            if (draft.info.weather.enabled) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = when {
                            weatherUi.refreshing ->
                                stringResource(R.string.info_weather_fetching)
                            weatherUi.lastFailureAtElapsedMs != null ->
                                stringResource(R.string.info_weather_failed)
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRetryWeather) {
                        Text(stringResource(R.string.info_weather_retry))
                    }
                }
            }

            // Calendar events (design 8.1): runtime permission + selection.
            Text(
                text = stringResource(R.string.info_events_note),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 24.dp),
            )
            InfoSwitchRow(
                label = stringResource(R.string.info_events),
                note = null,
                checked = draft.info.eventsEnabled,
                onCheckedChange = { checked ->
                    if (!checked) {
                        draft = draft.copy(
                            info = draft.info.copy(eventsEnabled = false),
                        )
                    } else if (calendarGranted) {
                        draft = draft.copy(
                            info = draft.info.copy(eventsEnabled = true),
                        )
                    } else {
                        permissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                    }
                },
            )
            if (!calendarGranted && (draft.info.eventsEnabled || permissionDenied)) {
                // Denied now or revoked at OS level (design 8.1): the row
                // explains and offers the request again.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.info_events_permission_denied),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        permissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                    }) {
                        Text(stringResource(R.string.info_events_grant))
                    }
                }
            }
            if (draft.info.eventsEnabled && calendarGranted) {
                InfoLabel(stringResource(R.string.info_events_pick_calendars))
                if (calendars.isEmpty()) {
                    Text(
                        text = stringResource(R.string.info_events_no_calendars),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    calendars.forEach { calendar ->
                        val selected = calendar.id in draft.info.selectedCalendarIds
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val ids = draft.info.selectedCalendarIds
                                    draft = draft.copy(
                                        info = draft.info.copy(
                                            selectedCalendarIds = if (selected) {
                                                ids - calendar.id
                                            } else {
                                                ids + calendar.id
                                            },
                                        ),
                                    )
                                }
                                .padding(vertical = 4.dp),
                        ) {
                            Checkbox(checked = selected, onCheckedChange = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(
                                    text = calendar.displayName.ifBlank {
                                        calendar.accountName
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (calendar.accountName.isNotBlank() &&
                                    calendar.accountName != calendar.displayName
                                ) {
                                    Text(
                                        text = calendar.accountName,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                    if (draft.info.selectedCalendarIds.isEmpty()) {
                        Text(
                            text = stringResource(R.string.info_events_none_selected),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            if (saveFailed) {
                Text(
                    text = stringResource(R.string.settings_save_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
            ) {
                OutlinedButton(
                    onClick = { requestExit() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = { attemptSave() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }

    if (confirmDisableWeather) {
        AlertDialog(
            onDismissRequest = { confirmDisableWeather = false },
            title = { Text(stringResource(R.string.info_weather_disable_title)) },
            text = { Text(stringResource(R.string.info_weather_disable_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisableWeather = false
                    // Off means region + cache are deleted on save (8.2).
                    draft = draft.copy(
                        info = draft.info.copy(
                            weather = WeatherSettings(enabled = false, location = null),
                        ),
                    )
                }) {
                    Text(stringResource(R.string.info_weather))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisableWeather = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (exitConfirm) {
        UnsavedChangesDialog(
            onSave = {
                exitConfirm = false
                attemptSave()
            },
            onDiscard = {
                exitConfirm = false
                onBack()
            },
            onKeepEditing = { exitConfirm = false },
        )
    }
}

/**
 * Region-name search inside the info screen (design 8.2). The entered
 * place name is sent to the provider — the note above the field says so
 * before the first keystroke goes out.
 */
@Composable
private fun RegionSearchBody(
    results: WeatherService.RegionSearchState,
    onQueryChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
    onPick: (WeatherLocation) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.region_search_note),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                onQueryChanged(it)
            },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.region_search_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
        when (val state = results) {
            is WeatherService.RegionSearchState.Searching -> Text(
                text = stringResource(R.string.info_weather_fetching),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 16.dp),
            )
            is WeatherService.RegionSearchState.Failed -> Text(
                text = stringResource(R.string.region_search_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 16.dp),
            )
            is WeatherService.RegionSearchState.Results -> {
                if (state.locations.isEmpty()) {
                    Text(
                        text = stringResource(R.string.region_search_empty),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        state.locations.forEach { location ->
                            Text(
                                text = location.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(location) }
                                    .padding(vertical = 14.dp),
                            )
                        }
                    }
                }
            }
            WeatherService.RegionSearchState.Idle -> Unit
        }
    }
}

@Composable
private fun InfoLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun InfoSwitchRow(
    label: String,
    note: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            if (note != null) {
                Text(text = note, style = MaterialTheme.typography.bodySmall)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
