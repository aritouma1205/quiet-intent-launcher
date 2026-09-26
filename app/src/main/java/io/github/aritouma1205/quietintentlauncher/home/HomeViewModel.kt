package io.github.aritouma1205.quietintentlauncher.home

import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.aritouma1205.quietintentlauncher.AppContainer
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.apps.AppEntry
import io.github.aritouma1205.quietintentlauncher.calendar.CalendarAccess
import io.github.aritouma1205.quietintentlauncher.calendar.EventRules
import io.github.aritouma1205.quietintentlauncher.calendar.TodayEvent
import io.github.aritouma1205.quietintentlauncher.context.ContextEvaluator
import io.github.aritouma1205.quietintentlauncher.launch.LaunchResult
import io.github.aritouma1205.quietintentlauncher.launch.LaunchTarget
import io.github.aritouma1205.quietintentlauncher.launch.toLaunchTarget
import io.github.aritouma1205.quietintentlauncher.recent.RecentEntry
import io.github.aritouma1205.quietintentlauncher.search.ExternalSearch
import io.github.aritouma1205.quietintentlauncher.search.LocalSearch
import io.github.aritouma1205.quietintentlauncher.search.RecentRow
import io.github.aritouma1205.quietintentlauncher.search.SearchDispatcher
import io.github.aritouma1205.quietintentlauncher.search.SearchItem
import io.github.aritouma1205.quietintentlauncher.search.SearchItemKind
import io.github.aritouma1205.quietintentlauncher.search.SearchRow
import io.github.aritouma1205.quietintentlauncher.search.SettingsDestination
import io.github.aritouma1205.quietintentlauncher.settings.DerivedOp
import io.github.aritouma1205.quietintentlauncher.settings.DoAction
import io.github.aritouma1205.quietintentlauncher.settings.SettingsData
import io.github.aritouma1205.quietintentlauncher.settings.SettingsState
import io.github.aritouma1205.quietintentlauncher.settings.StoredTarget
import io.github.aritouma1205.quietintentlauncher.settings.ToolItem
import io.github.aritouma1205.quietintentlauncher.settings.ToolSetting
import io.github.aritouma1205.quietintentlauncher.settings.ToolsOpenMode
import io.github.aritouma1205.quietintentlauncher.settings.VibrationMode
import io.github.aritouma1205.quietintentlauncher.settings.WeatherLocation
import io.github.aritouma1205.quietintentlauncher.system.SystemAction
import io.github.aritouma1205.quietintentlauncher.today.TodayUi
import io.github.aritouma1205.quietintentlauncher.today.WeatherBlock
import io.github.aritouma1205.quietintentlauncher.torch.TorchResult
import io.github.aritouma1205.quietintentlauncher.torch.TorchState
import io.github.aritouma1205.quietintentlauncher.weather.WeatherFreshness
import io.github.aritouma1205.quietintentlauncher.weather.WeatherRules
import io.github.aritouma1205.quietintentlauncher.weather.WeatherService
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
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

    /** No app can run the requested web search or share (design 9.2). */
    NoExternalHandler,

    /** No calendar app can display the tapped event (design 8.1). */
    NoEventHandler,

    /** The optional system service is not enabled/connected (design 13). */
    SystemServiceOff,

    /** A system action was requested but the OS refused it (design 13). */
    SystemActionFailed,

    /** No camera flash exists on this device (design 7). */
    ToolNoFlash,

    /** The camera is busy in another app; the torch cannot switch. */
    ToolLightBusy,

    /** CAMERA was denied; the light tool cannot run (design 7, 13). */
    ToolLightDenied,

    /** Torch operation failed for another reason. */
    ToolLightFailed,

    /** No timer-list handler and no fallback clock app configured. */
    ToolNoTimerHandler,
}

/** Resolved external hand-off capability for the search screen. */
data class ExternalAvailability(
    val chatGpt: Boolean = false,
    val share: Boolean = false,
)

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

    /**
     * TOOLS rows (design 7): the configured order, visible entries only,
     * with status resolved live — the torch state comes from the OS
     * callback, launch targets from the catalogs, the screenshot state from
     * the service connection.
     */
    private val _toolRows = MutableStateFlow<List<ToolRow>>(emptyList())
    val toolRows: StateFlow<List<ToolRow>> = _toolRows.asStateFlow()

    /** Optional service state for the system settings screen (design 13). */
    val systemServiceEnabled: StateFlow<Boolean> = container.systemActions.enabled
    val systemServiceConnected: StateFlow<Boolean> = container.systemActions.connected

    /** CAMERA runtime-permission requests; the UI owns the result contract. */
    private val _cameraPermissionRequests =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val cameraPermissionRequests: SharedFlow<Unit> =
        _cameraPermissionRequests.asSharedFlow()

    /** OS accessibility-settings requests; the Activity performs the launch. */
    private val _accessibilitySettingsRequests =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val accessibilitySettingsRequests: SharedFlow<Unit> =
        _accessibilitySettingsRequests.asSharedFlow()

    /** Tool the DoSettings screen should focus (set by panel navigation). */
    private val _toolEditFocus = MutableStateFlow<String?>(null)
    val toolEditFocus: StateFlow<String?> = _toolEditFocus.asStateFlow()

    /**
     * Armed screenshot (design 7): set when the user taps the tool, consumed
     * exactly once by [onRevealSettled] after the close animation finished.
     * An AtomicBoolean so a rapid second tap and a late settle can never
     * produce two OS requests.
     */
    private val pendingScreenshot = AtomicBoolean(false)

    /**
     * Context Slot rows of the DO panel (design 10). Evaluated when the
     * panel opens and after settings saves; frozen while the panel is open
     * — no background re-evaluation.
     */
    private val _contextRows = MutableStateFlow<List<ContextRow>>(emptyList())
    val contextRows: StateFlow<List<ContextRow>> = _contextRows.asStateFlow()

    /** 「最近」 rows resolved against live OS state (design 9). */
    private val _recentRows = MutableStateFlow<List<RecentRow>>(emptyList())
    val recentRows: StateFlow<List<RecentRow>> = _recentRows.asStateFlow()

    /**
     * Search query owned by the VM, not the composable: screen state must
     * not resurrect a typed query after the stack left Search (design 3).
     * Any navigation away from Search clears it (see the screen collector).
     */
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** Local search results; the latest query wins (design 9.1 世代照合). */
    private val searchDispatcher = SearchDispatcher(
        scope = viewModelScope,
        compute = ::computeSearchRows,
        emptyResult = emptyList(),
    )
    val searchRows: StateFlow<List<SearchRow>> = searchDispatcher.results

    /**
     * Whether the external hand-off rows can be offered (design 9.2).
     * Resolved on a worker thread each time Search opens — reading the
     * package manager inside composition would run a binder call on every
     * keystroke (design 15).
     */
    private val _externalAvailability = MutableStateFlow(ExternalAvailability())
    val externalAvailability: StateFlow<ExternalAvailability> =
        _externalAvailability.asStateFlow()

    private var availabilityJob: Job? = null

    /** Serializes [refreshActionRows]: a newer request cancels the older one. */
    private var refreshJob: Job? = null

    /** Serializes recent-row resolution against app/shortcut changes. */
    private var recentResolveJob: Job? = null

    /** Action the DoSettings screen should focus (set by panel navigation). */
    private val _actionEditFocus = MutableStateFlow<String?>(null)
    val actionEditFocus: StateFlow<String?> = _actionEditFocus.asStateFlow()

    /** Slot the ContextSlots screen should focus (set by panel navigation). */
    private val _slotEditFocus = MutableStateFlow<Int?>(null)
    val slotEditFocus: StateFlow<Int?> = _slotEditFocus.asStateFlow()

    /** GLANCE auto-dismiss is paused while a finger is held (design 8.3). */
    private val _glanceHold = MutableStateFlow(false)
    private var glanceJob: Job? = null

    /**
     * TODAY panel + GLANCE content (design 8). Recomputed at the designed
     * refresh points — never polled in the background.
     */
    private val _today = MutableStateFlow(TodayUi())
    val today: StateFlow<TodayUi> = _today.asStateFlow()

    /** Weather state surfaced to TODAY/GLANCE and the info settings. */
    val weatherUi: StateFlow<WeatherService.WeatherUi> = container.weatherService.ui

    /** Region-search results for the weather settings picker. */
    val regionResults: StateFlow<WeatherService.RegionSearchState> =
        container.weatherService.regionResults

    /** Calendar rows of the TODAY panel (design 8.1); memory-only. */
    private val _events = MutableStateFlow<List<TodayEvent>>(emptyList())

    /** READ_CALENDAR grant state, refreshed on foreground / settings use. */
    private val _calendarGranted = MutableStateFlow(false)
    val calendarGranted: StateFlow<Boolean> = _calendarGranted.asStateFlow()

    /** Selectable calendars for the settings picker. */
    private val _calendars = MutableStateFlow<List<CalendarAccess.CalendarInfo>>(emptyList())
    val calendars: StateFlow<List<CalendarAccess.CalendarInfo>> = _calendars.asStateFlow()

    /** VIEW intents for tapped events; the Activity performs the launch. */
    private val _eventIntents = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val eventIntents: SharedFlow<Intent> = _eventIntents.asSharedFlow()

    private var refreshEventsJob: Job? = null
    private var eventsObserverUnregister: (() -> Unit)? = null

    /**
     * Whether the launcher UI is in the foreground. The minute ticker
     * only runs while this is true — a clock that is not on screen must
     * not keep periodic work alive in the background (design 15).
     */
    private val _foregrounded = MutableStateFlow(true)

    /**
     * True while the minute ticker should tick: a clock face is actually
     * on screen and the UI is foregrounded. Exposed internally so tests
     * can assert the condition without waiting a real minute.
     */
    internal val clockTickerActive: StateFlow<Boolean>

    private var introDecided = false

    init {
        clockTickerActive = combine(
            _foregrounded,
            overlay,
            screen,
            settingsState,
        ) { foregrounded, currentOverlay, currentScreen, state ->
            clockVisible(foregrounded, currentOverlay, currentScreen, state)
        }.distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)
        viewModelScope.launch {
            container.appCatalog.apps.collect {
                refreshActionRows()
                refreshRecentRows()
                // A catalog load/update arriving while Search is open
                // re-runs the current query — the latest submission still
                // wins through the same generation guard (design 9.1, 15).
                if (nav.screen.value == HomeScreen.Search &&
                    _searchQuery.value.isNotBlank()
                ) {
                    searchDispatcher.submit(_searchQuery.value)
                }
            }
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
                        // Recording off ⇒ stored history must not linger
                        // (design 9). The entries collector may have run
                        // before the settings arrived, so retry a failed
                        // erase here too.
                        if (!state.data.search.recentRecording &&
                            container.recentStore.entries.value.isNotEmpty()
                        ) {
                            container.recentStore.clear()
                        }
                    }
                    is SettingsState.Degraded -> _recoveryDismissed.value = false
                    SettingsState.Loading -> Unit
                }
            }
        }
        viewModelScope.launch {
            container.recentStore.entries.collect { entries ->
                refreshRecentRows()
                // While recording is off no stored history may linger: if an
                // earlier erase failed, retry it here (design 9). A failed
                // clear does not emit, so this cannot loop.
                if (entries.isNotEmpty() &&
                    !currentSettings().search.recentRecording
                ) {
                    container.recentStore.clear()
                }
            }
        }
        viewModelScope.launch {
            container.targetLauncher.recentLaunchEvents.collect { record ->
                // Successful launches only; honoring the recording switch
                // (design 9). Queries are never recorded.
                if (currentSettings().search.recentRecording) {
                    container.recentStore.record(record.target)
                }
            }
        }
        viewModelScope.launch {
            // Any screen other than Search drops the query and pending
            // results — home-return, back, launches and detours alike
            // (design 3, 9.4). Entering Search re-checks which external
            // receivers exist.
            nav.screen.collect { screen ->
                // Any stack navigation leaving Quiet dismisses every
                // overlay — the accessibility actions (search / all-apps /
                // settings) bypass the gesture path that already closes
                // GLANCE, and a stale GLANCE would swallow the next Back
                // and never time out for a screen-reader user (design 3,
                // 8.3).
                if (screen != HomeScreen.Quiet) {
                    overlays.clear()
                    // A screenshot request never survives leaving Quiet:
                    // the request was scoped to capturing this surface.
                    pendingScreenshot.set(false)
                }
                if (screen != HomeScreen.Search) {
                    resetSearch()
                } else {
                    refreshExternalAvailability()
                    // Entries that crossed the 30-day line while the
                    // process lived are pruned here — where the history is
                    // shown — rather than by a background watcher
                    // (design 9, 15). The emission refreshes 最近 rows.
                    container.recentStore.prune()
                }
            }
        }
        viewModelScope.launch {
            // The OS torch callback repaints the light row — including the
            // "another app turned it off" case (design 7: display the real
            // state, never a hoped-for one).
            container.torch.state.collect { refreshToolRows() }
        }
        viewModelScope.launch {
            // Service connect/disconnect repaints the screenshot row.
            container.systemActions.connected.collect { refreshToolRows() }
        }
        viewModelScope.launch {
            container.systemActions.enabled.collect { refreshToolRows() }
        }
        viewModelScope.launch {
            // A weather cache update repaints the clock faces (design 8.2).
            container.weatherService.ui.collect { refreshToday() }
        }
        viewModelScope.launch {
            _events.collect { refreshToday() }
        }
        viewModelScope.launch {
            // Minute ticker — runs only while a clock face is actually
            // visible and the UI is foregrounded (design 15: no
            // launcher-owned periodic work while everything is off or
            // the surface is backgrounded). While TODAY is open the
            // event selection is re-evaluated at each minute boundary
            // (design 8.1).
            clockTickerActive.collectLatest { active ->
                if (!active) return@collectLatest
                while (currentCoroutineContext().isActive) {
                    refreshToday()
                    if (overlay.value == HomeOverlay.Today) refreshEvents()
                    delay(msUntilNextMinute())
                }
            }
        }
        viewModelScope.launch {
            // Opening TODAY refreshes everything and watches the provider
            // while the panel stays open (design 8.1); leaving unregisters
            // the observer. GLANCE repaints on open but never waits for
            // the network (design 8.2/8.3).
            overlay.collect { current ->
                // A new overlay appearing before the previous close finished
                // cancels an armed screenshot — it must only fire into a
                // fully-settled Quiet surface.
                if (current != null) pendingScreenshot.set(false)
                when (current) {
                    HomeOverlay.Today -> {
                        refreshToday()
                        refreshEvents()
                        container.weatherService.requestAutoRefresh()
                        eventsObserverUnregister?.invoke()
                        eventsObserverUnregister = container.calendarAccess
                            .observeChanges { refreshEvents() }
                    }
                    HomeOverlay.Glance -> refreshToday()
                    else -> {
                        eventsObserverUnregister?.invoke()
                        eventsObserverUnregister = null
                    }
                }
            }
        }
    }

    override fun onCleared() {
        // viewModelScope cancellation stops the collectors, but the
        // ContentObserver lives on the ContentResolver — unregister it
        // explicitly so a cleared ViewModel never keeps watching the
        // provider (design 15).
        eventsObserverUnregister?.invoke()
        eventsObserverUnregister = null
        super.onCleared()
    }

    /**
     * A clock is on screen while TODAY/GLANCE is open or the optional
     * Quiet clock is enabled on the Quiet surface — and only while the
     * UI is foregrounded (design 12, 15).
     */
    private fun clockVisible(
        foregrounded: Boolean,
        currentOverlay: HomeOverlay?,
        currentScreen: HomeScreen,
        state: SettingsState,
    ): Boolean =
        foregrounded && (
            currentOverlay == HomeOverlay.Today ||
                currentOverlay == HomeOverlay.Glance ||
                (
                    // The Quiet clock composes only while no overlay is
                    // up — a Reveal panel covering it must not keep the
                    // ticker alive (design 15).
                    currentOverlay == null &&
                        currentScreen == HomeScreen.Quiet &&
                        (state as? SettingsState.Ready)?.data?.clock?.enabled == true
                    )
            )

    private fun msUntilNextMinute(): Long {
        val remainder = container.wallClock() % MINUTE_MS
        return if (remainder <= 0) MINUTE_MS else MINUTE_MS - remainder
    }

    fun refreshHomeRole() {
        _isDefaultHome.value = container.homeRole.isHeld()
    }

    fun completeIntro(actions: List<DoAction>, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            // The intro screen is only left once the write succeeded; a
            // failed update keeps the draft so it can be retried.
            val ok = container.settingsStore.update {
                it.copy(introCompleted = true, actions = actions)
            }
            if (ok) nav.resetToQuiet()
            onResult(ok)
        }
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
        // Slot evaluation is skipped while the DO panel is open so the
        // shown slots stay stable (design 10); opening re-evaluates.
        refreshDoContents(evaluateSlots = overlay.value !is HomeOverlay.Do)
    }

    private fun refreshDoContents(evaluateSlots: Boolean) {
        // Serialize refreshes: an older, slower pass must not overwrite a
        // newer snapshot.
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val data = (settingsState.value as? SettingsState.Ready)?.data
                ?: SettingsData()
            // Binder calls (LauncherApps, PackageManager) stay off the UI
            // thread (design 15).
            val (rows, context) = withContext(Dispatchers.IO) {
                val actionRows = data.actions
                    .filter { it.visible }
                    .map { action -> ActionRow(action, resolveStatus(action)) }
                _toolRows.value = resolveToolRows(data)
                actionRows to if (evaluateSlots) evaluateContextRows(data) else null
            }
            _actionRows.value = rows
            context?.let { _contextRows.value = it }
        }
    }

    /**
     * Re-resolves the TOOLS rows: torch state is read from the OS-driven
     * [TorchController.state], launch targets from the live catalogs, the
     * screenshot readiness from the service connection (design 7, 13).
     */
    fun refreshToolRows() {
        viewModelScope.launch {
            val data = (settingsState.value as? SettingsState.Ready)?.data
                ?: SettingsData()
            _toolRows.value = withContext(Dispatchers.IO) { resolveToolRows(data) }
        }
    }

    private suspend fun resolveToolRows(data: SettingsData): List<ToolRow> =
        data.tools.items.mapNotNull { item ->
            val tool = ToolItem.byId(item.id) ?: return@mapNotNull null
            if (!item.visible) return@mapNotNull null
            ToolRow(tool, resolveToolStatus(tool, item, data))
        }

    private suspend fun resolveToolStatus(
        tool: ToolItem,
        item: ToolSetting,
        data: SettingsData,
    ): ToolStatus = when (tool) {
        // The light row mirrors the OS-reported torch state; a missing
        // permission shows the state anyway — only switching needs CAMERA.
        ToolItem.Light -> when (container.torch.state.value) {
            TorchState.NoFlash -> ToolStatus.Blocked(ToolUnavailable.NoFlash)
            TorchState.Unavailable -> ToolStatus.Blocked(ToolUnavailable.Busy)
            TorchState.On -> ToolStatus.LightOn
            TorchState.Off -> ToolStatus.LightOff
        }
        ToolItem.Screenshot -> when {
            !data.systemActions.screenshotEnabled ->
                ToolStatus.Blocked(ToolUnavailable.SwitchOff)
            !container.systemActions.isConnected() ->
                ToolStatus.Blocked(ToolUnavailable.ServiceInactive)
            else -> ToolStatus.Ready(container.stringFor(R.string.tool_screenshot_note))
        }
        ToolItem.Timer -> {
            val target = item.target
            when {
                target != null -> resolveToolTarget(target)
                container.toolLauncher.canShowTimers() ->
                    ToolStatus.Ready(container.stringFor(R.string.tool_timer_list))
                else -> ToolStatus.Blocked(ToolUnavailable.NoTimerHandler)
            }
        }
        ToolItem.Calculator, ToolItem.Qr -> {
            val target = item.target
            if (target == null) {
                ToolStatus.Blocked(ToolUnavailable.Unset)
            } else {
                resolveToolTarget(target)
            }
        }
    }

    private suspend fun resolveToolTarget(target: StoredTarget): ToolStatus =
        when (val status = resolveTargetStatus(target)) {
            is ActionStatus.Available -> ToolStatus.Ready(status.targetLabel)
            is ActionStatus.Unavailable ->
                ToolStatus.Blocked(ToolUnavailable.TargetGone)
            ActionStatus.Unset -> ToolStatus.Blocked(ToolUnavailable.Unset)
        }

    /** Tap on a TOOLS row (design 7). Unset/blocked rows offer the edit path. */
    fun onToolTapped(tool: ToolItem) {
        val item = currentSettings().tools.items.firstOrNull { it.id == tool.id }
        when (tool) {
            ToolItem.Light -> toggleTorch()
            ToolItem.Screenshot -> requestScreenshot()
            ToolItem.Calculator, ToolItem.Qr -> {
                val target =
                    item?.target?.toLaunchTarget(container.appCatalog.currentUser)
                if (target == null) {
                    // Unset or vanished config: open the tool editor as the
                    // configured 代替導線 (design 7).
                    openToolEditor(tool)
                } else {
                    applyLaunchResult(container.targetLauncher.launch(target))
                }
            }
            ToolItem.Timer -> {
                val target =
                    item?.target?.toLaunchTarget(container.appCatalog.currentUser)
                if (target != null) {
                    applyLaunchResult(container.targetLauncher.launch(target))
                } else if (container.toolLauncher.canShowTimers()) {
                    // Only the timer LIST is opened — the launcher never
                    // creates or starts a timer (design 7).
                    if (container.toolLauncher.showTimers()) {
                        overlays.clear()
                        nav.resetToQuiet()
                    } else {
                        _messages.tryEmit(HomeMessage.ToolNoTimerHandler)
                    }
                } else {
                    _messages.tryEmit(HomeMessage.ToolNoTimerHandler)
                    openToolEditor(tool)
                }
            }
        }
    }

    /** Opens 「行動・道具」 focused on one tool's row (design 7 導線). */
    fun openToolEditor(tool: ToolItem) {
        _toolEditFocus.value = tool.id
        // The edit focuses are mutually exclusive: a stale action or slot
        // focus must never win over a tool entry (or the reverse).
        _actionEditFocus.value = null
        _slotEditFocus.value = null
        overlays.clear()
        nav.navigateTo(HomeScreen.DoSettings)
    }

    /**
     * Light tool (design 7, 13): the first use asks for CAMERA through the
     * UI-owned permission request; the visible state is always the
     * OS-reported one — a successful request updates nothing until the
     * torch callback reports it.
     */
    private fun toggleTorch() {
        val torch = container.torch
        if (!torch.hasPermission()) {
            _cameraPermissionRequests.tryEmit(Unit)
            return
        }
        val turnOn = torch.state.value != TorchState.On
        when (torch.setTorch(turnOn)) {
            TorchResult.Toggled -> Unit
            TorchResult.PermissionMissing ->
                _cameraPermissionRequests.tryEmit(Unit)
            TorchResult.NoFlash -> _messages.tryEmit(HomeMessage.ToolNoFlash)
            TorchResult.Busy -> _messages.tryEmit(HomeMessage.ToolLightBusy)
            TorchResult.Failed -> _messages.tryEmit(HomeMessage.ToolLightFailed)
        }
    }

    /** CAMERA permission result forwarded by the UI's launcher. */
    fun onCameraPermissionResult(granted: Boolean) {
        if (granted) {
            toggleTorch()
        } else {
            _messages.tryEmit(HomeMessage.ToolLightDenied)
        }
    }

    /**
     * TOOLS screenshot (design 7): requires the per-feature switch AND a
     * live service. The panel closes first; the OS request fires exactly
     * once when the UI reports the close animation settled
     * ([onRevealSettled]).
     */
    fun requestScreenshot() {
        val data = currentSettings()
        if (!data.systemActions.screenshotEnabled) {
            // The alternative route: land the user on the system settings
            // screen where the switch and the service state live.
            openSystemActionsSettings()
            return
        }
        if (!systemServiceReady()) {
            _messages.tryEmit(HomeMessage.SystemServiceOff)
            return
        }
        if (!pendingScreenshot.compareAndSet(false, true)) {
            // Rapid second tap while one request is still armed: refuse so
            // one gesture never produces two OS screenshots (design 7).
            _messages.tryEmit(HomeMessage.LaunchBusy)
            return
        }
        overlays.clear()
    }

    /**
     * Called by the UI when the reveal surface has fully closed. Fires an
     * armed screenshot exactly once — only while still on Quiet and
     * foregrounded; otherwise the request is dropped rather than shooting
     * another app's screen (design 7).
     */
    fun onRevealSettled() {
        if (!pendingScreenshot.getAndSet(false)) return
        if (!_foregrounded.value || nav.screen.value != HomeScreen.Quiet) return
        if (!container.systemActions.isConnected() ||
            !container.systemActions.perform(SystemAction.TakeScreenshot)
        ) {
            _messages.tryEmit(HomeMessage.SystemActionFailed)
        }
    }

    /** システム操作 settings screen (design 11.2). */
    fun openSystemActionsSettings() {
        overlays.clear()
        nav.navigateTo(HomeScreen.SystemSettings)
    }

    /**
     * OS accessibility settings for the optional service (design 13). The
     * external flow keeps this settings screen under the OS screen so the
     * user lands back here after granting.
     */
    fun openAccessibilitySettings() {
        nav.beginExternalFlow()
        _accessibilitySettingsRequests.tryEmit(Unit)
    }

    /**
     * Resolves every slot to the action it would show now (design 10).
     * Availability is resolved live so a slot whose selected action is
     * unusable falls through to the next rule / default. The visible flag
     * only declutters the normal action list — design 10 skips 起動先未設定・
     * 削除済み・無効 targets, not hidden ones, and a slot is an explicit
     * configuration, so a hidden action still resolves here.
     */
    private suspend fun evaluateContextRows(data: SettingsData): List<ContextRow> {
        val usable = HashSet<String>()
        for (action in data.actions) {
            if (action.target != null &&
                resolveStatus(action) is ActionStatus.Available
            ) {
                usable += action.id
            }
        }
        val evaluations = ContextEvaluator.evaluate(
            slots = data.contextSlots,
            now = LocalDateTime.now(),
            isActionUsable = usable::contains,
        )
        return evaluations.mapNotNull { evaluation ->
            val action = data.actions.firstOrNull { it.id == evaluation.actionId }
                ?: return@mapNotNull null
            val slot = data.contextSlots.getOrNull(evaluation.slotIndex)
                ?: return@mapNotNull null
            ContextRow(
                slotIndex = evaluation.slotIndex,
                slotLabel = slot.label,
                action = action,
                matchedRule = evaluation.matchedRule,
            )
        }
    }

    private suspend fun resolveStatus(action: DoAction): ActionStatus =
        resolveTargetStatus(action.target)

    private suspend fun resolveTargetStatus(target: StoredTarget?): ActionStatus =
        when (target) {
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
                        target.url.toUri().host ?: target.url,
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
        _toolEditFocus.value = null
        _slotEditFocus.value = null
        overlays.clear()
        nav.navigateTo(HomeScreen.DoSettings)
    }

    /**
     * An editor screen applied (or found no) pending editor focus.
     * Focus requests are one-shot: all three channels are cleared here so
     * a later plain visit never re-opens a stale picker or scroll.
     */
    fun consumeEditFocus() {
        _actionEditFocus.value = null
        _toolEditFocus.value = null
        _slotEditFocus.value = null
    }

    suspend fun shortcutsFor(packageName: String) =
        container.shortcutCatalog.list(packageName)

    // ---- Search / 最近 / Context Slots (design 9, 10) --------------------

    /** Query input; debounced and generation-guarded (design 9.1). */
    fun onSearchQueryChanged(query: String) {
        val capped = query.take(LocalSearch.MAX_QUERY_CHARS)
        _searchQuery.value = capped
        searchDispatcher.submit(capped)
    }

    /** Clears the query, pending work and published results. */
    fun resetSearch() {
        _searchQuery.value = ""
        searchDispatcher.reset()
    }

    /**
     * Builds the searchable index from live settings + the app catalog and
     * runs the pure matcher. Tool / settings labels resolve through
     * [AppContainer.stringFor]; hidden actions stay searchable (the flag
     * declutters the panel, it is not a privacy switch).
     */
    private suspend fun computeSearchRows(query: String): List<SearchRow> =
        withContext(Dispatchers.Default) {
            val data = currentSettings()
            val appList = apps.value ?: emptyList()
            val pairs = ArrayList<Pair<SearchItem, SearchRow>>()

            data.actions.forEachIndexed { index, action ->
                pairs += SearchItem(
                    id = "action:${action.id}",
                    kind = SearchItemKind.Action,
                    primary = action.name,
                    aliases = action.aliases,
                    groupOrder = index,
                ) to SearchRow.Action(action)
                action.derivedOps.forEachIndexed { opIndex, op ->
                    if (op.label.isNotBlank()) {
                        pairs += SearchItem(
                            id = "op:${op.id}",
                            kind = SearchItemKind.DerivedOp,
                            primary = op.label,
                            groupOrder = index,
                            subOrder = opIndex + 1,
                        ) to SearchRow.Op(action, op)
                    }
                }
            }

            // Tools are indexed in the configured order (design 7); hidden
            // tools stay searchable — the flag declutters the panel, it is
            // not a privacy switch (same rule as hidden actions).
            val toolOrder = data.tools.items.mapNotNull { ToolItem.byId(it.id) }
            toolOrder.forEachIndexed { index, tool ->
                pairs += SearchItem(
                    id = "tool:${tool.id}",
                    kind = SearchItemKind.ToolSetting,
                    primary = container.stringFor(tool.labelRes),
                    groupOrder = index,
                ) to SearchRow.Tool(tool)
            }
            SettingsDestination.entries.forEachIndexed { index, destination ->
                pairs += SearchItem(
                    id = "setting:${destination.name}",
                    kind = SearchItemKind.ToolSetting,
                    primary = container.stringFor(destination.labelRes),
                    groupOrder = toolOrder.size + index,
                ) to SearchRow.Setting(destination)
            }

            appList.forEachIndexed { index, entry ->
                pairs += SearchItem(
                    id = "app:${entry.key}",
                    kind = SearchItemKind.App,
                    primary = entry.label,
                    groupOrder = index,
                ) to SearchRow.App(entry, showPackage = false)
            }

            val payloads = pairs.associate { it.first.id to it.second }
            val rows = LocalSearch.search(query, pairs.map { it.first })
                .mapNotNull { payloads[it.id] }
            // Same-named app results carry the package name (design 9.1);
            // same-named action results carry the launch-target name so
            // they stay distinguishable (design 6).
            val duplicatedLabels = rows
                .filterIsInstance<SearchRow.App>()
                .groupingBy { it.entry.label }
                .eachCount()
                .filterValues { it > 1 }
                .keys
            val duplicatedActionNames = rows
                .filterIsInstance<SearchRow.Action>()
                .groupingBy { it.action.name }
                .eachCount()
                .filterValues { it > 1 }
                .keys
            rows.map { row ->
                when {
                    row is SearchRow.App &&
                        row.entry.label in duplicatedLabels ->
                        row.copy(showPackage = true)
                    row is SearchRow.Action &&
                        row.action.name in duplicatedActionNames ->
                        row.copy(
                            targetLabel = (resolveStatus(row.action) as?
                                ActionStatus.Available)?.targetLabel,
                        )
                    else -> row
                }
            }
        }

    /** Explicit web search on the configured engine (design 9.2). */
    fun runWebSearch(query: String) {
        applyExternalOutcome(
            container.externalSearch.webSearch(query, currentSettings().search.webEngine),
        )
    }

    /**
     * 「ChatGPTに送る」— falls back to the share chooser when ChatGPT is
     * not an ACTION_SEND receiver (design 9.2).
     */
    fun runShare(query: String) {
        val outcome = container.externalSearch.shareToChatGpt(query)
            ?: container.externalSearch.shareWithChooser(query)
        applyExternalOutcome(outcome)
    }

    /** Re-resolves which external receivers exist, off the UI thread. */
    private fun refreshExternalAvailability() {
        availabilityJob?.cancel()
        availabilityJob = viewModelScope.launch {
            _externalAvailability.value = withContext(Dispatchers.IO) {
                ExternalAvailability(
                    chatGpt = container.externalSearch.isChatGptAvailable(),
                    share = container.externalSearch.canShare(),
                )
            }
        }
    }

    private fun applyExternalOutcome(outcome: ExternalSearch.Outcome) {
        when (outcome) {
            ExternalSearch.Outcome.Launched -> {
                overlays.clear()
                nav.resetToQuiet()
            }
            ExternalSearch.Outcome.NoHandler ->
                _messages.tryEmit(HomeMessage.NoExternalHandler)
            is ExternalSearch.Outcome.Failed ->
                _messages.tryEmit(HomeMessage.LaunchFailed)
        }
    }

    /** A 「最近」 row re-launches the stored target (design 9). */
    fun launchStoredTarget(target: StoredTarget) {
        val launchTarget = target.toLaunchTarget(container.appCatalog.currentUser)
        if (launchTarget == null) {
            _messages.tryEmit(HomeMessage.TargetUnavailable)
            return
        }
        applyLaunchResult(container.targetLauncher.launch(launchTarget))
    }

    /** A tool result opens the DO panel with TOOLS expanded (design 9.1). */
    fun openToolsPanelFromSearch() {
        nav.resetToQuiet()
        overlays.openDo(toolsExpanded = true)
        refreshDoContents(evaluateSlots = true)
    }

    /** A settings result navigates to the matching settings screen. */
    fun openSettingsDestination(destination: SettingsDestination) {
        when (destination) {
            SettingsDestination.Root -> nav.navigateTo(HomeScreen.Settings)
            SettingsDestination.Edge -> nav.navigateTo(HomeScreen.EdgeSettings)
            SettingsDestination.Actions -> openActionEditor(null)
            SettingsDestination.ContextSlots -> openContextSlotEditor(null)
            SettingsDestination.Search -> nav.navigateTo(HomeScreen.SearchSettings)
            SettingsDestination.Info -> nav.navigateTo(HomeScreen.InfoSettings)
            SettingsDestination.System -> nav.navigateTo(HomeScreen.SystemSettings)
        }
    }

    /** Context slot editor entry, optionally focused on one slot. */
    fun openContextSlotEditor(slotIndex: Int?) {
        _slotEditFocus.value = slotIndex
        // Mutually exclusive with the 「行動・道具」 focuses (see
        // openToolEditor): a stale focus must never win over this entry.
        _actionEditFocus.value = null
        _toolEditFocus.value = null
        overlays.clear()
        nav.navigateTo(HomeScreen.ContextSettings)
    }

    /**
     * Re-resolves 「最近」 rows against live OS state; entries whose target
     * disappeared (uninstalled app, revoked shortcut, no HTTPS handler) are
     * dropped from display — the record itself stays for the retention
     * window (design 9).
     */
    private fun refreshRecentRows() {
        recentResolveJob?.cancel()
        recentResolveJob = viewModelScope.launch {
            val entries = container.recentStore.entries.value
            _recentRows.value = withContext(Dispatchers.IO) {
                val rows = entries.mapNotNull { resolveRecentRow(it) }
                val duplicated = rows
                    .filter { it.appEntry != null }
                    .groupingBy { it.label }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys
                rows.map { row ->
                    if (row.appEntry != null && row.label in duplicated) {
                        row.copy(showPackage = true)
                    } else {
                        row
                    }
                }
            }
        }
    }

    private suspend fun resolveRecentRow(entry: RecentEntry): RecentRow? =
        when (val target = entry.target) {
            is StoredTarget.App -> container.appCatalog
                .resolveApp(target.component)
                ?.let { RecentRow(target, it.label, it, showPackage = false) }
            is StoredTarget.Shortcut -> container.shortcutCatalog
                .listNow(target.packageName)
                .firstOrNull { it.id == target.shortcutId }
                ?.let { RecentRow(target, it.label, null, showPackage = false) }
            is StoredTarget.HttpsLink ->
                if (container.targetLauncher.canOpenHttps(target.url)) {
                    RecentRow(
                        target,
                        target.url.toUri().host ?: target.url,
                        null,
                        showPackage = false,
                    )
                } else {
                    null
                }
        }

    /**
     * Search-settings save; when recording is off or the user asked to
     * clear, the history erase runs BEFORE the settings write (design 9:
     * 停止時は保存済み履歴も確認のうえ消す). A failed erase reports a failed
     * save and leaves the stored settings untouched, so recording never
     * ends up persisted as off while history stays on disk. If the
     * settings write fails after a successful erase, recording stays on
     * with an empty history — history is disposable data, so that is the
     * safer direction.
     */
    fun saveSearchSettings(
        data: SettingsData,
        clearHistory: Boolean,
        onResult: (Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            val cleared = if (clearHistory || !data.search.recentRecording) {
                container.recentStore.clear()
            } else {
                true
            }
            val ok = cleared && container.settingsStore.update { data }
            onResult(ok)
        }
    }

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
        overlays.openDo(toolsExpanded)
        // Availability and Context Slots are re-evaluated on every open
        // (design 6, 10, 15). Opening the panel is the evaluation point.
        refreshDoContents(evaluateSlots = true)
    }

    fun openTodayPanel() = overlays.openToday()

    // ---- TODAY / GLANCE / optional integrations (design 8) -------------

    /**
     * Recomputes TODAY/GLANCE content from live sources (design 8):
     * time/date/battery from the provider, weather from the key-filtered
     * cache with freshness applied, events from the in-memory selection.
     */
    fun refreshToday() {
        val info = container.todayData.current()
        val weather = container.weatherService.ui.value
        val snapshot = weather.snapshot
        val freshness = snapshot?.let {
            WeatherRules.freshness(
                container.elapsedClock(),
                container.wallClock(),
                it,
            )
        }
        _today.value = TodayUi(
            timeText = info.timeText,
            dateText = info.dateText,
            weekdayText = info.weekdayText,
            weather = if (snapshot != null && freshness != WeatherFreshness.Hidden) {
                WeatherBlock(
                    temperatureCelsius = snapshot.temperatureCelsius,
                    labelRes = WeatherRules.weatherLabelRes(snapshot.weatherCode),
                    fresh = freshness == WeatherFreshness.Fresh,
                    fetchedAtWallMs = snapshot.fetchedAtWallMs,
                    regionName = weather.region?.name.orEmpty(),
                )
            } else {
                null
            },
            events = _events.value,
            batteryPercent = info.batteryPercent,
            charging = info.charging,
            nowMs = container.wallClock(),
        )
    }

    /**
     * Re-reads the Calendar Provider selection (design 8.1) on the
     * designed triggers — TODAY opened, foreground return, provider
     * change while open, minute boundary while open. A revoked
     * permission drops the in-memory rows; the latest call wins.
     */
    fun refreshEvents() {
        refreshEventsJob?.cancel()
        refreshEventsJob = viewModelScope.launch {
            val data = currentSettings()
            val granted = withContext(Dispatchers.IO) {
                container.calendarAccess.hasPermission()
            }
            _calendarGranted.value = granted
            val ids = data.info.selectedCalendarIds.toSet()
            if (!data.info.eventsEnabled || !granted || ids.isEmpty()) {
                _events.value = emptyList()
                return@launch
            }
            val nowMs = container.wallClock()
            val zone = ZoneId.systemDefault()
            _events.value = withContext(Dispatchers.IO) {
                try {
                    EventRules.select(
                        container.calendarAccess.queryInstances(nowMs, zone, ids),
                        nowMs,
                        zone,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RuntimeException) {
                    // Provider failure degrades to an empty list, never a
                    // crash (design 8.1/15): a revoked permission also
                    // drops the grant flag, while a stopped provider
                    // (SQLite/DeadObject/…) just shows no events.
                    if (e is SecurityException) _calendarGranted.value = false
                    emptyList()
                }
            }
        }
    }

    /** Loads the calendar picker list (settings entry / grant result). */
    fun refreshCalendars() {
        viewModelScope.launch {
            val granted = withContext(Dispatchers.IO) {
                container.calendarAccess.hasPermission()
            }
            _calendarGranted.value = granted
            _calendars.value = if (granted) {
                withContext(Dispatchers.IO) {
                    try {
                        container.calendarAccess.listCalendars()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: RuntimeException) {
                        // Same degradation as refreshEvents: a stopped or
                        // failing provider yields an empty picker.
                        if (e is SecurityException) _calendarGranted.value = false
                        emptyList()
                    }
                }
            } else {
                emptyList()
            }
        }
    }

    /** Tapping an event opens it in a calendar app (design 8.1). */
    fun onEventTapped(event: TodayEvent) {
        val intent = container.calendarAccess.viewEventIntent(event)
        if (container.calendarAccess.canOpenEvent(intent)) {
            _eventIntents.tryEmit(intent)
        } else {
            // No handler: the row stays visible and the UI explains.
            _messages.tryEmit(HomeMessage.NoEventHandler)
        }
    }

    /** Region-name search for the settings picker (design 8.2). */
    fun searchRegions(query: String) = container.weatherService.searchRegions(query)

    fun clearRegionSearch() = container.weatherService.clearRegionSearch()

    /** Manual weather refresh from settings (design 8.2: 30 s minimum). */
    fun retryWeather() {
        container.weatherService.requestManualRefresh()
    }

    /**
     * 「情報」 settings save (design 8, 11.2). Disabling weather erases
     * the cache file BEFORE the settings write (design 8.2): a failed
     * erase reports failure and leaves the persisted settings untouched,
     * so no state ever says "disabled" while a stale snapshot still sits
     * on disk. The screen keeps the draft on failure and a retry
     * re-runs the erase.
     */
    fun saveInfoSettings(data: SettingsData, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            // Erase BEFORE writing (same ordering as the history-clear
            // save): when the erase fails the persisted settings stay
            // untouched — weather keeps its previous enabled state and
            // the still-matching cache remains valid for it. No state
            // ever persists as "disabled with a stale cache behind it".
            if (!data.info.weather.enabled &&
                !container.weatherService.clearCache()
            ) {
                onResult(false)
                return@launch
            }
            val ok = container.settingsStore.update { data }
            if (ok) refreshEvents()
            onResult(ok)
        }
    }

    /**
     * Test seam: instrumentation observes the broadcast-driven refresh —
     * TIME_SET/TIMEZONE_CHANGED are protected broadcasts only the system
     * (or shell) can send, so the real-path test watches this probe.
     */
    internal var timeChangedProbe: () -> Unit = {}

    /** Time/date/timezone broadcasts (design 8.1): repaint + re-select. */
    fun onTimeChanged() {
        refreshToday()
        refreshEvents()
        timeChangedProbe()
    }

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
        val seconds = currentSettings().info.glanceDismissSeconds
        // 0 = 自動消去なし (design 11.2): stays until an explicit close.
        if (seconds <= 0) return
        // The countdown runs even when a screen reader is active: every
        // tick re-checks it, so GLANCE opened while TalkBack is on simply
        // never decreases, and turning TalkBack off mid-display resumes a
        // normal dismissal instead of leaving GLANCE stuck (design 8.3).
        glanceJob = viewModelScope.launch {
            var remaining = seconds * 1000L
            while (remaining > 0) {
                delay(GLANCE_TICK_MS)
                if (overlay.value != HomeOverlay.Glance) return@launch
                // A held finger pauses the countdown; so does an external
                // assistive service (screen reader / switch access)
                // switched on mid-display (design 8.3: never leave while
                // the user is reading). Our own operation-only service
                // reads nothing, so it does not pause the timer.
                if (!_glanceHold.value && !container.isAssistiveServiceActive()) {
                    remaining -= GLANCE_TICK_MS
                }
            }
            overlays.close()
        }
    }

    /**
     * Down-swipe → notification shade (design 5, 13). Disabled: the first
     * swipe explains the opt-in once. Enabled but the service is off:
     * explain that only this operation cannot run — the launcher itself
     * keeps working.
     */
    // The notification hint is a once-ever toast; the persisted flag lands
    // asynchronously, so an in-memory guard covers the gap between the
    // first swipe and the store write propagating back (design 5).
    private var notificationHintSent = false

    private fun requestNotifications() {
        val data = (settingsState.value as? SettingsState.Ready)?.data ?: return
        if (!data.systemActions.notificationsEnabled) {
            // Disabled: guide once, then stay silent (design 5).
            if (!data.notificationHintShown && !notificationHintSent) {
                notificationHintSent = true
                _messages.tryEmit(HomeMessage.NotificationHint)
                viewModelScope.launch {
                    container.settingsStore.update { it.copy(notificationHintShown = true) }
                }
            }
            return
        }
        if (!systemServiceReady()) {
            _messages.tryEmit(HomeMessage.SystemServiceOff)
            return
        }
        if (!container.systemActions.perform(SystemAction.Notifications)) {
            _messages.tryEmit(HomeMessage.SystemActionFailed)
        }
    }

    /**
     * Double-tap → OS screen-off (design 5, 13). This is the OS lock path —
     * the launcher never draws its own lock screen; PIN/biometric unlock
     * stays with Android.
     */
    private fun requestScreenOff() {
        val data = (settingsState.value as? SettingsState.Ready)?.data ?: return
        if (!data.systemActions.screenOffEnabled) return
        if (!systemServiceReady()) {
            _messages.tryEmit(HomeMessage.SystemServiceOff)
            return
        }
        if (!container.systemActions.perform(SystemAction.LockScreen)) {
            _messages.tryEmit(HomeMessage.SystemActionFailed)
        }
    }

    /**
     * Enabled in the OS accessibility settings AND a live service
     * connection — re-read on every call so a revoked service never runs
     * (design 13: 実行直前にも確認).
     */
    private fun systemServiceReady(): Boolean =
        container.systemActions.isEnabledInOs() &&
            container.systemActions.isConnected()

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
        _foregrounded.value = true
        nav.onForegrounded()
        // Foreground-only subscriptions (design 15): the torch callback and
        // the enabled-services observer only run while the UI is up. The
        // enabled bit is re-read now — a revoke while away is seen here.
        container.torch.setObserving(true)
        container.systemActions.setObserving(true)
        container.systemActions.isEnabledInOs()
        refreshToolRows()
        // Designed refresh points (design 8): repaint the clock faces,
        // re-read events (permission may have been revoked), and let the
        // weather service decide whether 30 min passed since the last
        // success — GLANCE/TODAY never wait on the network.
        refreshToday()
        refreshEvents()
        container.weatherService.requestAutoRefresh()
    }

    fun onBackgrounded() {
        _foregrounded.value = false
        // A screenshot armed before backgrounding must never fire into
        // another app's screen (design 7).
        pendingScreenshot.set(false)
        container.torch.setObserving(false)
        container.systemActions.setObserving(false)
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

    /**
     * Touch exploration active right now (design 12). Read at
     * gesture-settle time so a reader toggled mid-session takes effect
     * immediately — the free-area double tap must never steal TalkBack's.
     * QuietSystemService alone does not set this: it requests no
     * accessibility flags, so screen-off stays reachable through it.
     */
    fun isTouchExplorationActive(): Boolean = container.isTouchExplorationActive()

    companion object {
        private const val GLANCE_TICK_MS = 50L
        private const val MINUTE_MS = 60_000L

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { HomeViewModel(container) }
        }
    }
}
