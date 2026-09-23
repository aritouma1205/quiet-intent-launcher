package io.github.aritouma1205.quietintentlauncher.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.aritouma1205.quietintentlauncher.AppContainer
import io.github.aritouma1205.quietintentlauncher.apps.AppEntry
import io.github.aritouma1205.quietintentlauncher.launch.LaunchResult
import io.github.aritouma1205.quietintentlauncher.launch.LaunchTarget
import io.github.aritouma1205.quietintentlauncher.launch.toLaunchTarget
import io.github.aritouma1205.quietintentlauncher.settings.DerivedOp
import io.github.aritouma1205.quietintentlauncher.settings.DoAction
import io.github.aritouma1205.quietintentlauncher.settings.SettingsData
import io.github.aritouma1205.quietintentlauncher.settings.SettingsState
import io.github.aritouma1205.quietintentlauncher.settings.StoredTarget
import io.github.aritouma1205.quietintentlauncher.settings.ToolsOpenMode
import io.github.aritouma1205.quietintentlauncher.settings.VibrationMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One-shot UI events carrying a string resource id. */
enum class HomeMessage {
    LaunchFailed,
    LaunchBusy,

    /**
     * The configured target is gone or unusable (app removed/disabled,
     * shortcut revoked, no HTTPS handler). The action's config is kept;
     * the DO panel row explains and offers 変更する (design 6).
     */
    TargetUnavailable,

    /** An optional feature that has no backing implementation yet. */
    FeatureLater,

    /** Down-swipe guidance shown once while the feature is unavailable. */
    NotificationHint,
}

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    val nav = HomeNavigation()
    val screen: StateFlow<HomeScreen> = nav.screen

    val settingsState: StateFlow<SettingsState> = container.settingsStore.state
    val apps: StateFlow<List<AppEntry>?> = container.appCatalog.apps

    private val overlays = HomeOverlayController()
    val overlay: StateFlow<HomeOverlay?> = overlays.overlay

    private val _isDefaultHome = MutableStateFlow(container.homeRole.isHeld())
    val isDefaultHome: StateFlow<Boolean> = _isDefaultHome.asStateFlow()

    private val _recoveryDismissed = MutableStateFlow(false)
    val recoveryDismissed: StateFlow<Boolean> = _recoveryDismissed.asStateFlow()

    private val _messages = MutableSharedFlow<HomeMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<HomeMessage> = _messages.asSharedFlow()

    /** DO panel rows: visible actions + availability resolved from live OS state. */
    private val _actionRows = MutableStateFlow<List<ActionRow>>(emptyList())
    val actionRows: StateFlow<List<ActionRow>> = _actionRows.asStateFlow()

    /** Serializes [refreshActionRows]: a newer request cancels the older one. */
    private var refreshJob: Job? = null

    /** Action the DoSettings screen should focus (set by panel navigation). */
    private val _actionEditFocus = MutableStateFlow<String?>(null)
    val actionEditFocus: StateFlow<String?> = _actionEditFocus.asStateFlow()

    /** GLANCE auto-dismiss is paused while a finger is held (design 8.3). */
    private val _glanceHold = MutableStateFlow(false)
    private var glanceJob: Job? = null

    private var introDecided = false

    init {
        viewModelScope.launch {
            container.appCatalog.apps.collect { refreshActionRows() }
        }
        viewModelScope.launch {
            settingsState.collect { state ->
                refreshActionRows()
                when (state) {
                    is SettingsState.Ready -> {
                        _recoveryDismissed.value = false
                        if (!introDecided) {
                            introDecided = true
                            if (!state.data.introCompleted) {
                                nav.navigateTo(HomeScreen.Intro)
                            }
                        }
                    }
                    is SettingsState.Degraded -> _recoveryDismissed.value = false
                    SettingsState.Loading -> Unit
                }
            }
        }
    }

    fun refreshHomeRole() {
        _isDefaultHome.value = container.homeRole.isHeld()
    }

    fun completeIntro(actions: List<DoAction>) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(introCompleted = true, actions = actions)
            }
        }
        nav.resetToQuiet()
    }

    fun launchApp(entry: AppEntry) {
        applyLaunchResult(
            container.targetLauncher.launch(
                LaunchTarget.AppActivity(entry.component, entry.user),
            ),
        )
    }

    // ---- DO actions (design 6) --------------------------------------------

    /**
     * Re-resolves every visible action's availability against live OS state:
     * the app catalog for apps, the LauncherApps shortcut query for
     * shortcuts and the package manager for HTTPS handlers. While settings
     * are Loading/Degraded the in-memory defaults keep the panel usable.
     */
    fun refreshActionRows() {
        // Serialize refreshes: an older, slower pass must not overwrite a
        // newer snapshot.
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val data = (settingsState.value as? SettingsState.Ready)?.data
                ?: SettingsData()
            // Binder calls (LauncherApps, PackageManager) stay off the UI
            // thread (design 15).
            _actionRows.value = withContext(Dispatchers.IO) {
                data.actions
                    .filter { it.visible }
                    .map { action -> ActionRow(action, resolveStatus(action)) }
            }
        }
    }

    private suspend fun resolveStatus(action: DoAction): ActionStatus =
        when (val target = action.target) {
            null -> ActionStatus.Unset
            is StoredTarget.App -> container.appCatalog
                .resolveApp(target.component)
                ?.let { ActionStatus.Available(it.label) }
                ?: ActionStatus.Unavailable(UnavailableReason.AppGone)
            is StoredTarget.Shortcut -> container.shortcutCatalog
                .listNow(target.packageName)
                .firstOrNull { it.id == target.shortcutId }
                ?.let { ActionStatus.Available(it.label) }
                ?: ActionStatus.Unavailable(UnavailableReason.ShortcutGone)
            is StoredTarget.HttpsLink ->
                if (container.targetLauncher.canOpenHttps(target.url)) {
                    ActionStatus.Available(
                        Uri.parse(target.url).host ?: target.url,
                    )
                } else {
                    ActionStatus.Unavailable(UnavailableReason.NoHandler)
                }
        }

    /** Tap on an action row (design 6). Unset opens the target picker. */
    fun onActionTapped(action: DoAction) {
        val target = action.target?.toLaunchTarget(container.appCatalog.currentUser)
        if (target == null) {
            openActionEditor(action.id)
            return
        }
        applyLaunchResult(container.targetLauncher.launch(target))
    }

    /** A derived op launches through the same shared path as the action. */
    fun onDerivedOpTapped(action: DoAction, op: DerivedOp) {
        val target = op.target?.toLaunchTarget(container.appCatalog.currentUser)
        if (target == null) {
            openActionEditor(action.id)
            return
        }
        applyLaunchResult(container.targetLauncher.launch(target))
    }

    /** Opens the action editor, optionally focused on one action. */
    fun openActionEditor(actionId: String?) {
        _actionEditFocus.value = actionId
        overlays.clear()
        nav.navigateTo(HomeScreen.DoSettings)
    }

    suspend fun shortcutsFor(packageName: String) =
        container.shortcutCatalog.list(packageName)

    private fun applyLaunchResult(result: LaunchResult) {
        when (result) {
            LaunchResult.Success -> {
                // Successful external launch returns the home side to Quiet
                // (design 3); a failure keeps the panel/screen in place.
                overlays.clear()
                nav.resetToQuiet()
            }
            LaunchResult.Busy -> _messages.tryEmit(HomeMessage.LaunchBusy)
            LaunchResult.NotFound,
            LaunchResult.ShortcutUnavailable,
            LaunchResult.NoHandler,
            -> {
                // Gone/unusable target: keep the action's config, explain,
                // and refresh the row so it offers 変更する (design 6).
                _messages.tryEmit(HomeMessage.TargetUnavailable)
                container.appCatalog.reloadAll()
                refreshActionRows()
            }
            is LaunchResult.Failure ->
                _messages.tryEmit(HomeMessage.LaunchFailed)
        }
    }

    // ---- Reveal overlays -------------------------------------------------

    fun emitMessage(message: HomeMessage) {
        _messages.tryEmit(message)
    }

    fun openDoPanel(toolsExpanded: Boolean) {
        // Availability is re-checked on every open (design 6, 15).
        refreshActionRows()
        overlays.openDo(toolsExpanded)
    }

    fun openTodayPanel() = overlays.openToday()

    fun expandTools() = overlays.expandTools()

    fun collapseTools() = overlays.collapseTools()

    fun closeOverlay() = overlays.close()

    fun setGlanceHold(holding: Boolean) {
        _glanceHold.value = holding
    }

    /**
     * Free-area gesture routing (design 5). GLANCE is dismissed by any other
     * operation; a tap toggles it.
     */
    fun onFreeAreaEvent(event: FreeAreaEvent) {
        when (event) {
            FreeAreaEvent.Tap -> {
                overlays.toggleGlance()
                scheduleGlance()
            }
            FreeAreaEvent.LongPress -> {
                overlays.clear()
                nav.navigateTo(HomeScreen.Edit)
            }
            FreeAreaEvent.SwipeUp -> {
                overlays.clear()
                nav.navigateTo(HomeScreen.Search)
            }
            FreeAreaEvent.SwipeDown -> {
                overlays.clear()
                requestNotifications()
            }
            FreeAreaEvent.DoubleTap -> {
                overlays.clear()
                requestScreenOff()
            }
        }
    }

    private fun scheduleGlance() {
        glanceJob?.cancel()
        if (overlay.value != HomeOverlay.Glance) return
        // TalkBack / switch users read at their own pace (design 8.3).
        if (container.isAccessibilityActive()) return
        glanceJob = viewModelScope.launch {
            var remaining = GLANCE_TIMEOUT_MS
            while (remaining > 0) {
                delay(GLANCE_TICK_MS)
                if (overlay.value != HomeOverlay.Glance) return@launch
                if (!_glanceHold.value) remaining -= GLANCE_TICK_MS
            }
            overlays.close()
        }
    }

    private fun requestNotifications() {
        val data = (settingsState.value as? SettingsState.Ready)?.data ?: return
        if (!data.systemActions.notificationsEnabled) {
            // Disabled: guide once, then stay silent (design 5).
            if (!data.notificationHintShown) {
                _messages.tryEmit(HomeMessage.NotificationHint)
                viewModelScope.launch {
                    container.settingsStore.update { it.copy(notificationHintShown = true) }
                }
            }
            return
        }
        // The backing accessibility service arrives with the system-action
        // stage; do not pretend the action ran.
        _messages.tryEmit(HomeMessage.FeatureLater)
    }

    private fun requestScreenOff() {
        val data = (settingsState.value as? SettingsState.Ready)?.data ?: return
        if (data.systemActions.screenOffEnabled) {
            _messages.tryEmit(HomeMessage.FeatureLater)
        }
    }

    /** Back order: TOOLS fold -> panel/GLANCE close -> nav stack. */
    fun handleBack(): Boolean = overlays.back() || nav.back()

    // ---- Settings --------------------------------------------------------

    fun saveSettings(data: SettingsData, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            onResult(container.settingsStore.update { data })
        }
    }

    fun dismissRecovery() {
        _recoveryDismissed.value = true
    }

    fun retrySettings() {
        container.settingsStore.retry()
    }

    fun resetSettings() {
        viewModelScope.launch {
            if (container.settingsStore.resetToDefaults()) {
                _recoveryDismissed.value = false
            }
        }
    }

    // ---- Lifecycle ---------------------------------------------------------

    fun onForegrounded() {
        nav.onForegrounded()
    }

    fun onBackgrounded() {
        overlays.clear()
        nav.onBackgrounded()
    }

    fun onHomeInvoked() {
        overlays.clear()
        nav.onHomeInvoked()
    }

    /** Current bar / TOOLS / vibration settings for the gesture layer. */
    fun currentSettings(): SettingsData =
        (settingsState.value as? SettingsState.Ready)?.data ?: SettingsData()

    fun toolsDeepPullEnabled(): Boolean =
        currentSettings().tools.openMode == ToolsOpenMode.DeepPull

    fun vibrationEnabled(): Boolean =
        currentSettings().vibration == VibrationMode.System

    companion object {
        private const val GLANCE_TIMEOUT_MS = 3000L
        private const val GLANCE_TICK_MS = 50L

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { HomeViewModel(container) }
        }
    }
}
