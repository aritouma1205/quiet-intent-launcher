package io.github.aritouma1205.quietintentlauncher.home

import android.app.Activity
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.apps.AllAppsScreen
import io.github.aritouma1205.quietintentlauncher.apps.AppEntry
import io.github.aritouma1205.quietintentlauncher.intro.IntroScreen
import io.github.aritouma1205.quietintentlauncher.search.SearchScreen
import io.github.aritouma1205.quietintentlauncher.settings.DerivedOp
import io.github.aritouma1205.quietintentlauncher.settings.DoAction
import io.github.aritouma1205.quietintentlauncher.settings.DoSettingsScreen
import io.github.aritouma1205.quietintentlauncher.settings.EdgeSettingsScreen
import io.github.aritouma1205.quietintentlauncher.settings.SettingsData
import io.github.aritouma1205.quietintentlauncher.settings.SettingsScreen
import io.github.aritouma1205.quietintentlauncher.settings.SettingsState
import io.github.aritouma1205.quietintentlauncher.settings.ToolsOpenMode
import io.github.aritouma1205.quietintentlauncher.settings.VibrationMode
import io.github.aritouma1205.quietintentlauncher.today.TodayInfo
import io.github.aritouma1205.quietintentlauncher.ui.DeepScrim
import io.github.aritouma1205.quietintentlauncher.ui.QuietBadgeBackground
import io.github.aritouma1205.quietintentlauncher.ui.QuietLauncherTheme
import kotlin.math.min
import kotlinx.coroutines.launch

@Composable
fun QuietLauncherRoot(
    viewModel: HomeViewModel,
    iconLoader: (AppEntry) -> Drawable?,
    todayInfo: () -> TodayInfo,
    onRequestHomeRole: () -> Unit,
    onRestoreHome: () -> Unit,
    onChangeWallpaper: () -> Unit,
    onOpenAppInfo: (String) -> Unit,
) {
    QuietLauncherTheme {
        val screen by viewModel.screen.collectAsState()
        val overlay by viewModel.overlay.collectAsState()
        val settingsState by viewModel.settingsState.collectAsState()
        val isDefaultHome by viewModel.isDefaultHome.collectAsState()
        val recoveryDismissed by viewModel.recoveryDismissed.collectAsState()
        val context = LocalContext.current
        val view = LocalView.current

        LaunchedEffect(screen) {
            val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            if (screen == HomeScreen.Quiet) {
                controller.hide(WindowInsetsCompat.Type.statusBars())
            } else {
                controller.show(WindowInsetsCompat.Type.statusBars())
            }
        }

        LaunchedEffect(Unit) {
            viewModel.messages.collect { message ->
                val text = when (message) {
                    HomeMessage.LaunchFailed -> R.string.all_apps_launch_failed
                    HomeMessage.LaunchBusy -> R.string.all_apps_duplicate
                    HomeMessage.TargetUnavailable ->
                        R.string.do_launch_unavailable
                    HomeMessage.FeatureLater -> R.string.feature_later
                    HomeMessage.NotificationHint -> R.string.notification_hint
                }
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
        }

        val showRecovery = settingsState is SettingsState.Degraded && !recoveryDismissed
        var resetConfirm by remember { mutableStateOf(false) }
        BackHandler(
            enabled = showRecovery || overlay != null || screen != HomeScreen.Quiet,
        ) {
            if (showRecovery) viewModel.dismissRecovery() else viewModel.handleBack()
        }

        Box(Modifier.fillMaxSize()) {
            when {
                showRecovery -> RecoveryScreen(
                    message = (settingsState as SettingsState.Degraded).message,
                    onRetry = viewModel::retrySettings,
                    onReset = { resetConfirm = true },
                    onContinue = viewModel::dismissRecovery,
                )
                else -> when (screen) {
                    HomeScreen.Quiet -> QuietScreen(
                        isDefaultHome = isDefaultHome,
                        overlay = overlay,
                        settings = viewModel.currentSettings(),
                        todayInfo = todayInfo,
                        onFreeAreaEvent = viewModel::onFreeAreaEvent,
                        onGlanceHold = viewModel::setGlanceHold,
                        onOpenSearch = { viewModel.nav.navigateTo(HomeScreen.Search) },
                        onOpenAllApps = { viewModel.nav.navigateTo(HomeScreen.AllApps) },
                        onOpenSettings = { viewModel.nav.navigateTo(HomeScreen.Settings) },
                        onOpenDoPanel = viewModel::openDoPanel,
                        onOpenTodayPanel = viewModel::openTodayPanel,
                        onExpandTools = viewModel::expandTools,
                        onCollapseTools = viewModel::collapseTools,
                        onCloseOverlay = viewModel::closeOverlay,
                        actionRows = viewModel.actionRows.collectAsState().value,
                        onActionTap = viewModel::onActionTapped,
                        onActionEdit = { viewModel.openActionEditor(it.id) },
                        onDerivedOp = viewModel::onDerivedOpTapped,
                        onUnavailable = {
                            viewModel.emitMessage(HomeMessage.FeatureLater)
                        },
                    )
                    HomeScreen.Intro -> IntroScreen(
                        isDefaultHome = isDefaultHome,
                        actions = viewModel.currentSettings().actions,
                        apps = viewModel.apps.collectAsState().value,
                        iconLoader = iconLoader,
                        onSetHome = {
                            viewModel.nav.beginExternalFlow()
                            onRequestHomeRole()
                        },
                        onDone = viewModel::completeIntro,
                    )
                    HomeScreen.Search -> SearchScreen(
                        onAllApps = { viewModel.nav.navigateTo(HomeScreen.AllApps) },
                        onSettings = { viewModel.nav.navigateTo(HomeScreen.Settings) },
                        onBack = { viewModel.nav.back() },
                    )
                    HomeScreen.AllApps -> AllAppsScreen(
                        apps = viewModel.apps.collectAsState().value,
                        iconLoader = iconLoader,
                        onLaunch = viewModel::launchApp,
                        onAppInfo = { entry ->
                            viewModel.nav.beginExternalFlow()
                            onOpenAppInfo(entry.packageName)
                        },
                        onBack = { viewModel.nav.back() },
                    )
                    HomeScreen.Settings -> SettingsScreen(
                        isDefaultHome = isDefaultHome,
                        edgeSettingsEnabled = settingsState is SettingsState.Ready,
                        doSettingsEnabled = settingsState is SettingsState.Ready,
                        onSetHome = {
                            viewModel.nav.beginExternalFlow()
                            onRequestHomeRole()
                        },
                        onRestoreHome = {
                            viewModel.nav.beginExternalFlow()
                            onRestoreHome()
                        },
                        onChangeWallpaper = {
                            viewModel.nav.beginExternalFlow()
                            onChangeWallpaper()
                        },
                        onEdgeSettings = {
                            viewModel.nav.navigateTo(HomeScreen.EdgeSettings)
                        },
                        onDoSettings = {
                            viewModel.openActionEditor(null)
                        },
                        onReplayIntro = { viewModel.nav.navigateTo(HomeScreen.Intro) },
                        onOpenAppInfo = {
                            viewModel.nav.beginExternalFlow()
                            onOpenAppInfo(context.packageName)
                        },
                        onBack = { viewModel.nav.back() },
                    )
                    HomeScreen.EdgeSettings -> {
                        val data = (settingsState as? SettingsState.Ready)?.data
                        if (data != null) {
                            EdgeSettingsScreen(
                                initial = data,
                                onSave = viewModel::saveSettings,
                                onBack = { viewModel.nav.back() },
                            )
                        } else {
                            EdgeSettingsUnavailable(
                                onBack = { viewModel.nav.back() },
                            )
                        }
                    }
                    HomeScreen.DoSettings -> {
                        val data = (settingsState as? SettingsState.Ready)?.data
                        if (data != null) {
                            DoSettingsScreen(
                                initial = data,
                                focusActionId = viewModel.actionEditFocus
                                    .collectAsState().value,
                                apps = viewModel.apps.collectAsState().value,
                                iconLoader = iconLoader,
                                isHomeRoleHeld = isDefaultHome,
                                shortcutsFor = viewModel::shortcutsFor,
                                onSave = viewModel::saveSettings,
                                onBack = { viewModel.nav.back() },
                            )
                        } else {
                            EdgeSettingsUnavailable(
                                onBack = { viewModel.nav.back() },
                            )
                        }
                    }
                    HomeScreen.Edit -> HomeEditSheet(
                        onOpenSettings = {
                            viewModel.nav.navigateTo(HomeScreen.Settings)
                        },
                        onDismiss = { viewModel.nav.back() },
                    )
                }
            }
        }

        if (resetConfirm) {
            AlertDialog(
                onDismissRequest = { resetConfirm = false },
                title = { Text(stringResource(R.string.settings_reset_confirm_title)) },
                text = { Text(stringResource(R.string.settings_reset_confirm_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        resetConfirm = false
                        viewModel.resetSettings()
                    }) {
                        Text(stringResource(R.string.settings_reset_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { resetConfirm = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}

/**
 * Quiet: wallpaper, the two edge bars and the free area (design 3, 4, 5).
 * Bars stay while GLANCE is up and are hidden while a Reveal panel is open.
 */
@Composable
private fun QuietScreen(
    isDefaultHome: Boolean,
    overlay: HomeOverlay?,
    settings: SettingsData,
    todayInfo: () -> TodayInfo,
    onFreeAreaEvent: (FreeAreaEvent) -> Unit,
    onGlanceHold: (Boolean) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenAllApps: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDoPanel: (Boolean) -> Unit,
    onOpenTodayPanel: () -> Unit,
    onExpandTools: () -> Unit,
    onCollapseTools: () -> Unit,
    onCloseOverlay: () -> Unit,
    actionRows: List<ActionRow>,
    onActionTap: (DoAction) -> Unit,
    onActionEdit: (DoAction) -> Unit,
    onDerivedOp: (DoAction, DerivedOp) -> Unit,
    onUnavailable: () -> Unit,
) {
    val description = stringResource(R.string.quiet_preview_badge)
    val paneTitle = stringResource(R.string.quiet_pane_title)
    val openSearchLabel = stringResource(R.string.quiet_open_search)
    val allAppsLabel = stringResource(R.string.all_apps_entry)
    val settingsLabel = stringResource(R.string.settings_entry)
    val editLabel = stringResource(R.string.quiet_edit_action)
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Whole-surface pointer tracking: a second finger often lands outside
    // the bar's or the free area's own event stream, so arming multi-touch
    // detection on each detector alone misses it (design 4.2, 5). The
    // parent sees every pointer's down/up and latches "multi" until the
    // last finger leaves.
    val activePointerIds = remember { mutableSetOf<PointerId>() }
    var sawMultiPointer by remember { mutableStateOf(false) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                while (true) {
                    awaitPointerEventScope {
                        val event = awaitPointerEvent()
                        for (change in event.changes) {
                            when {
                                change.pressed && !change.previousPressed ->
                                    activePointerIds += change.id
                                !change.pressed && change.previousPressed ->
                                    activePointerIds -= change.id
                            }
                        }
                        when {
                            activePointerIds.isEmpty() -> sawMultiPointer = false
                            activePointerIds.size > 1 -> sawMultiPointer = true
                        }
                    }
                }
            }
            .semantics {
                this.paneTitle = paneTitle
                if (!isDefaultHome) contentDescription = description
                onClick(label = openSearchLabel) {
                    onOpenSearch()
                    true
                }
                customActions = listOf(
                    CustomAccessibilityAction(allAppsLabel) {
                        onOpenAllApps()
                        true
                    },
                    CustomAccessibilityAction(settingsLabel) {
                        onOpenSettings()
                        true
                    },
                    CustomAccessibilityAction(editLabel) {
                        onFreeAreaEvent(FreeAreaEvent.LongPress)
                        true
                    },
                )
            },
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()

        val systemBars = WindowInsets.systemBars
        val cutout = WindowInsets.displayCutout
        val layoutDirection = LocalLayoutDirection.current
        val topPx = maxOf(
            systemBars.getTop(density),
            cutout.getTop(density),
        ).toFloat()
        val bottomPx = maxOf(
            systemBars.getBottom(density),
            cutout.getBottom(density),
        ).toFloat()
        val leftPx = maxOf(
            systemBars.getLeft(density, layoutDirection),
            cutout.getLeft(density, layoutDirection),
        ).toFloat()
        val rightPx = maxOf(
            systemBars.getRight(density, layoutDirection),
            cutout.getRight(density, layoutDirection),
        ).toFloat()
        val systemGestureBottomPx =
            WindowInsets.systemGestures.getBottom(density).toFloat()

        // Design 12: the smaller of 88% of the width and 360dp.
        val panelWidthPx = min(
            0.88f * widthPx,
            with(density) { 360.dp.toPx() },
        )

        val usableTop = topPx
        val usableHeight = (heightPx - topPx - bottomPx).coerceAtLeast(0f)

        var visualPanel by remember { mutableStateOf<EdgeSide?>(null) }
        var previewToolsExpanded by remember { mutableStateOf(false) }
        val panelProgress = remember { mutableFloatStateOf(0f) }
        var dragging by remember { mutableStateOf(false) }

        // Drive the visual panel from the overlay state; a preview drag owns
        // the visuals directly and commits through the VM on release.
        LaunchedEffect(overlay) {
            when (overlay) {
                is HomeOverlay.Do -> {
                    if (visualPanel == null) {
                        visualPanel = EdgeSide.Right
                        panelProgress.floatValue = 0f
                        animate(0f, 1f) { v, _ -> panelProgress.floatValue = v }
                    }
                }
                is HomeOverlay.Today -> {
                    if (visualPanel == null) {
                        visualPanel = EdgeSide.Left
                        panelProgress.floatValue = 0f
                        animate(0f, 1f) { v, _ -> panelProgress.floatValue = v }
                    }
                }
                else -> {
                    if (visualPanel != null && !dragging) {
                        animate(panelProgress.floatValue, 0f) { v, _ ->
                            panelProgress.floatValue = v
                        }
                        visualPanel = null
                        previewToolsExpanded = false
                    }
                }
            }
        }

        fun handleBarEvent(side: EdgeSide, event: EdgeDragEvent) {
            when (event) {
                is EdgeDragEvent.Progress -> {
                    dragging = true
                    visualPanel = side
                    panelProgress.floatValue = event.fraction.coerceIn(0f, 1f)
                }
                is EdgeDragEvent.ToolsExpanded -> {
                    previewToolsExpanded = true
                    panelProgress.floatValue = event.fraction.coerceIn(0f, 1f)
                }
                is EdgeDragEvent.ToolsCollapsed -> {
                    previewToolsExpanded = false
                    panelProgress.floatValue = event.fraction.coerceIn(0f, 1f)
                }
                is EdgeDragEvent.Opened -> scope.launch {
                    animate(panelProgress.floatValue, 1f) { v, _ ->
                        panelProgress.floatValue = v
                    }
                    dragging = false
                    if (side == EdgeSide.Right) {
                        onOpenDoPanel(event.toolsExpanded)
                    } else {
                        onOpenTodayPanel()
                    }
                }
                EdgeDragEvent.Closed -> scope.launch {
                    animate(panelProgress.floatValue, 0f) { v, _ ->
                        panelProgress.floatValue = v
                    }
                    visualPanel = null
                    dragging = false
                    previewToolsExpanded = false
                }
                EdgeDragEvent.Tapped -> {
                    if (side == EdgeSide.Right) onOpenDoPanel(false) else onOpenTodayPanel()
                }
            }
        }

        val barsVisible = overlay == null || overlay == HomeOverlay.Glance
        val freeAreaGate = remember { FreeAreaGate() }
        val viewConfiguration = LocalViewConfiguration.current

        // Free area first (bottom layer); bars and overlays sit above it, so
        // touches inside a bar never reach the free area (design 5 order).
        if (barsVisible) {
            Box(
                Modifier
                    .fillMaxSize()
                    .freeAreaGestures(
                        gate = freeAreaGate,
                        touchSlopPx = viewConfiguration.touchSlop,
                        isSwipeStartAllowed = { offset ->
                            offset.y < heightPx - systemGestureBottomPx
                        },
                        doubleTapEnabled =
                            settings.systemActions.screenOffEnabled,
                        wasMultiPointer = { sawMultiPointer },
                        onHoldChange = onGlanceHold,
                        onEvent = onFreeAreaEvent,
                    ),
            )

            EdgeBar(
                side = EdgeSide.Left,
                settings = settings.leftBar,
                containerWidthPx = widthPx,
                usableTopPx = usableTop,
                usableHeightPx = usableHeight,
                insetSidePx = leftPx,
                panelWidthPx = panelWidthPx,
                deepPullEnabled = false,
                toolsThreshold = settings.tools.deepPullFraction,
                hapticsEnabled = settings.vibration == VibrationMode.System,
                openLabel = stringResource(R.string.edge_bar_open_today),
                wasMultiPointer = { sawMultiPointer },
                onEvent = ::handleBarEvent,
                onHaptic = {
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                },
            )
            EdgeBar(
                side = EdgeSide.Right,
                settings = settings.rightBar,
                containerWidthPx = widthPx,
                usableTopPx = usableTop,
                usableHeightPx = usableHeight,
                insetSidePx = rightPx,
                panelWidthPx = panelWidthPx,
                deepPullEnabled =
                    settings.tools.openMode == ToolsOpenMode.DeepPull,
                toolsThreshold = settings.tools.deepPullFraction,
                hapticsEnabled = settings.vibration == VibrationMode.System,
                openLabel = stringResource(R.string.edge_bar_open_do),
                wasMultiPointer = { sawMultiPointer },
                onEvent = ::handleBarEvent,
                onHaptic = {
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                },
            )
        }

        // Reveal panel: open state from the VM, or the drag preview.
        val openPanel = overlay
        val panelSide = visualPanel
        if (panelSide != null) {
            val toolsExpanded = when (val o = openPanel) {
                is HomeOverlay.Do -> o.toolsExpanded
                else -> previewToolsExpanded
            }
            PanelLayer(
                side = panelSide,
                progress = panelProgress.floatValue,
                panelWidthPx = panelWidthPx,
                paneTitle = stringResource(
                    if (panelSide == EdgeSide.Right) {
                        R.string.panel_do_title
                    } else {
                        R.string.panel_today_title
                    },
                ),
                onClose = onCloseOverlay,
            ) {
                if (panelSide == EdgeSide.Right) {
                    DoPanel(
                        rows = actionRows,
                        toolsExpanded = toolsExpanded,
                        panelWidthPx = panelWidthPx,
                        onExpandTools = onExpandTools,
                        onCollapseTools = onCollapseTools,
                        onClose = onCloseOverlay,
                        onActionTap = onActionTap,
                        onActionEdit = onActionEdit,
                        onDerivedOp = onDerivedOp,
                        onUnavailable = onUnavailable,
                    )
                } else {
                    TodayPanel(
                        info = todayInfo(),
                        panelWidthPx = panelWidthPx,
                        onClose = onCloseOverlay,
                    )
                }
            }
        }

        if (overlay == HomeOverlay.Glance) {
            GlanceOverlay(todayInfo())
        }

        if (!isDefaultHome) {
            Text(
                text = description,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp)
                    .background(QuietBadgeBackground, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** Settings recovery surface shown while the stored file is unreadable. */
@Composable
private fun RecoveryScreen(
    message: String,
    onRetry: () -> Unit,
    onReset: () -> Unit,
    onContinue: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepScrim)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.settings_degraded_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.settings_degraded_body),
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_degraded_retry))
            }
            OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_degraded_reset))
            }
            OutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_degraded_continue))
            }
        }
    }
}

@Composable
private fun EdgeSettingsUnavailable(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepScrim)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
    ) {
        TextButton(onClick = onBack) {
            Text(stringResource(R.string.back))
        }
        Text(
            text = stringResource(R.string.edge_settings_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}
