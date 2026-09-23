package io.github.aritouma1205.quietintentlauncher.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.ui.DeepScrim

/**
 * Edge-gesture settings (design 4.1, 4.3, 11.2): bar geometry for both bars,
 * the TOOLS open mode and haptics. Edits go into a draft with a live preview
 * and are only persisted on save (design 11.2: sliders preview live, the
 * stored settings stay untouched until the user saves).
 */
@Composable
fun EdgeSettingsScreen(
    initial: SettingsData,
    onSave: (SettingsData, (Boolean) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var saveFailed by remember { mutableStateOf(false) }

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
                text = stringResource(R.string.edge_settings_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            BarPreview(draft)

            BarEditor(
                title = stringResource(R.string.edge_bar_right),
                bar = draft.rightBar,
                onChange = { draft = draft.copy(rightBar = it) },
            )
            BarEditor(
                title = stringResource(R.string.edge_bar_left),
                bar = draft.leftBar,
                onChange = { draft = draft.copy(leftBar = it) },
            )

            SectionTitle(stringResource(R.string.edge_tools_title))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = draft.tools.openMode == ToolsOpenMode.Tap,
                    onClick = {
                        draft = draft.copy(
                            tools = draft.tools.copy(openMode = ToolsOpenMode.Tap),
                        )
                    },
                    label = { Text(stringResource(R.string.edge_tools_tap)) },
                )
                FilterChip(
                    selected = draft.tools.openMode == ToolsOpenMode.DeepPull,
                    onClick = {
                        draft = draft.copy(
                            tools = draft.tools.copy(openMode = ToolsOpenMode.DeepPull),
                        )
                    },
                    label = { Text(stringResource(R.string.edge_tools_deep)) },
                )
            }
            if (draft.tools.openMode == ToolsOpenMode.DeepPull) {
                ValueSlider(
                    label = stringResource(R.string.edge_tools_depth),
                    value = draft.tools.deepPullFraction,
                    range = ToolsSettings.MIN_DEEP_PULL_FRACTION..
                        ToolsSettings.MAX_DEEP_PULL_FRACTION,
                    format = { stringResource(R.string.percent_format, (it * 100).toInt()) },
                    onChange = {
                        draft = draft.copy(tools = draft.tools.copy(deepPullFraction = it))
                    },
                )
            }

            SectionTitle(stringResource(R.string.edge_vibration_title))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = draft.vibration == VibrationMode.System,
                    onClick = { draft = draft.copy(vibration = VibrationMode.System) },
                    label = { Text(stringResource(R.string.edge_vibration_system)) },
                )
                FilterChip(
                    selected = draft.vibration == VibrationMode.Off,
                    onClick = { draft = draft.copy(vibration = VibrationMode.Off) },
                    label = { Text(stringResource(R.string.edge_vibration_off)) },
                )
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
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        onSave(draft.sanitizedForSave()) { ok ->
                            if (ok) onBack() else saveFailed = true
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}

private fun SettingsData.sanitizedForSave(): SettingsData = copy(
    leftBar = leftBar.sanitized(),
    rightBar = rightBar.sanitized(),
    tools = tools.sanitized(),
)

/** Live preview: both bars drawn at their real size inside a frame so the
 * configured area is explicit (design 4.1). */
@Composable
private fun BarPreview(data: SettingsData) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .padding(top = 8.dp)
            .background(
                Color.White.copy(alpha = 0.08f),
                RoundedCornerShape(12.dp),
            ),
    ) {
        PreviewBar(data.leftBar, isLeft = true)
        PreviewBar(data.rightBar, isLeft = false)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.PreviewBar(
    bar: EdgeBarSettings,
    isLeft: Boolean,
) {
    val color = when (bar.color) {
        EdgeBarColor.White -> Color.White
        EdgeBarColor.Black -> Color.Black
    }
    Box(
        modifier = Modifier
            .align(if (isLeft) Alignment.TopStart else Alignment.TopEnd)
            .offset(y = 110.dp * (bar.verticalBias) - bar.lengthDp.dp / 2)
            .size(width = bar.thicknessDp.dp, height = bar.lengthDp.dp)
            .background(color.copy(alpha = bar.opacity), RoundedCornerShape(50)),
    )
}

@Composable
private fun BarEditor(
    title: String,
    bar: EdgeBarSettings,
    onChange: (EdgeBarSettings) -> Unit,
) {
    SectionTitle(title)
    ValueSlider(
        label = stringResource(R.string.edge_bar_position),
        value = bar.verticalBias,
        range = 0f..1f,
        format = { stringResource(R.string.percent_format, (it * 100).toInt()) },
        onChange = { onChange(bar.copy(verticalBias = it)) },
    )
    ValueSlider(
        label = stringResource(R.string.edge_bar_length),
        value = bar.lengthDp,
        range = EdgeBarSettings.MIN_LENGTH_DP..EdgeBarSettings.MAX_LENGTH_DP,
        format = { stringResource(R.string.dp_format, it.toInt()) },
        onChange = { onChange(bar.copy(lengthDp = it)) },
    )
    ValueSlider(
        label = stringResource(R.string.edge_bar_thickness),
        value = bar.thicknessDp,
        range = EdgeBarSettings.MIN_THICKNESS_DP..EdgeBarSettings.MAX_THICKNESS_DP,
        format = { stringResource(R.string.dp_format, it.toInt()) },
        onChange = { onChange(bar.copy(thicknessDp = it)) },
    )
    ValueSlider(
        label = stringResource(R.string.edge_bar_opacity),
        value = bar.opacity,
        range = EdgeBarSettings.MIN_OPACITY..EdgeBarSettings.MAX_OPACITY,
        format = { stringResource(R.string.percent_format, (it * 100).toInt()) },
        onChange = { onChange(bar.copy(opacity = it)) },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = bar.color == EdgeBarColor.White,
            onClick = { onChange(bar.copy(color = EdgeBarColor.White)) },
            label = { Text(stringResource(R.string.edge_color_white)) },
        )
        FilterChip(
            selected = bar.color == EdgeBarColor.Black,
            onClick = { onChange(bar.copy(color = EdgeBarColor.Black)) },
            label = { Text(stringResource(R.string.edge_color_black)) },
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun ValueSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: @Composable (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = format(value),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
        )
    }
}
