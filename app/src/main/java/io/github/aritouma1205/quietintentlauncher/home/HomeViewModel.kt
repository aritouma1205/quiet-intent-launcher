package io.github.aritouma1205.quietintentlauncher.home

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.aritouma1205.quietintentlauncher.AppContainer
import io.github.aritouma1205.quietintentlauncher.apps.AppEntry
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
import io.github.aritouma1205.quietintentlauncher.settings.ToolsOpenMode
import io.github.aritouma1205.quietintentlauncher.settings.VibrationMode
import java.time.LocalDateTime
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

    /** No app can run the requested web search or share (design 9.2). */
    NoExternalHandler,
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

    private var introDecided = false

    init {
        viewModelScope.launch {
            container.appCatalog.apps.collect {
                refreshActionRows()
                refreshRecentRows()
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
                    }
                    is SettingsState.Degraded -> _recoveryDismissed.value = false
                    SettingsState.Loading -> Unit
                }
            }
        }
        viewModelScope.launch {
            container.recentStore.entries.collect { refreshRecentRows() }
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
                if (screen != HomeScreen.Search) {
                    resetSearch()
                } else {
                    refreshExternalAvailability()
                }
            }
        }
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
                actionRows to if (evaluateSlots) evaluateContextRows(data) else null
            }
            _actionRows.value = rows
            context?.let { _contextRows.value = it }
        }
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
        overlays.clear()
        nav.navigateTo(HomeScreen.DoSettings)
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

            ToolItem.entries.forEachIndexed { index, tool ->
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
                    groupOrder = ToolItem.entries.size + index,
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
            // Same-named app results carry the package name (design 9.1).
            val duplicatedLabels = rows
                .filterIsInstance<SearchRow.App>()
                .groupingBy { it.entry.label }
                .eachCount()
                .filterValues { it > 1 }
                .keys
            rows.map { row ->
                if (row is SearchRow.App && row.entry.label in duplicatedLabels) {
                    row.copy(showPackage = true)
                } else {
                    row
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
        }
    }

    /** Context slot editor entry, optionally focused on one slot. */
    fun openContextSlotEditor(slotIndex: Int?) {
        _slotEditFocus.value = slotIndex
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
     * clear, saved history is deleted after the settings write succeeded
     * (design 9: 停止時は保存済み履歴も確認のうえ消す).
     */
    fun saveSearchSettings(
        data: SettingsData,
        clearHistory: Boolean,
        onResult: (Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            val ok = container.settingsStore.update { data }
            if (ok && (clearHistory || !data.search.recentRecording)) {
                container.recentStore.clear()
            }
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
