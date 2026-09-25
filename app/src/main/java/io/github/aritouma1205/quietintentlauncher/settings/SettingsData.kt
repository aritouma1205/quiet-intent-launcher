package io.github.aritouma1205.quietintentlauncher.settings

import io.github.aritouma1205.quietintentlauncher.context.ContextRules
import io.github.aritouma1205.quietintentlauncher.context.ContextSlot
import kotlinx.serialization.Serializable

/**
 * Persisted settings. Design 14.1: typed JSON via DataStore with schemaVersion.
 * Later stages extend the fields and add sequential migrations in
 * [SettingsSerializer].
 */
@Serializable
data class SettingsData(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val introCompleted: Boolean = false,
    val leftBar: EdgeBarSettings = EdgeBarSettings(),
    val rightBar: EdgeBarSettings = EdgeBarSettings(),
    val tools: ToolsSettings = ToolsSettings(),
    val vibration: VibrationMode = VibrationMode.System,
    val systemActions: SystemActionSettings = SystemActionSettings(),
    val notificationHintShown: Boolean = false,
    /**
     * The configured DO actions in display order (design 6, 14.1). Added in
     * schemaVersion 3; v1/v2 files are seeded by the serializer migration.
     */
    val actions: List<DoAction> = DoActionDefaults.defaults(),
    /**
     * The two Context Slots (design 10). Added in schemaVersion 4; earlier
     * files decode to the two empty defaults.
     */
    val contextSlots: List<ContextSlot> = ContextRules.defaultSlots(),
    /** Universal Search settings (design 9, 11.2). Added in schemaVersion 4. */
    val search: SearchSettings = SearchSettings(),
    /** Optional small clock on Quiet (design 12). Added in schemaVersion 5. */
    val clock: ClockSettings = ClockSettings(),
    /**
     * GLANCE, weather and calendar-event settings (design 8, 11.2).
     * Added in schemaVersion 5; both optional integrations stay disabled
     * until the user turns them on.
     */
    val info: InfoSettings = InfoSettings(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 6
    }
}

/** Web検索先の選択肢（design 9.2）。任意URLテンプレートはv1では扱わない。 */
@Serializable
enum class WebSearchEngine {
    Google,
    Bing,
    DuckDuckGo,
}

/**
 * Search settings (design 9, 11.2): whether 「最近」 records launches and
 * which fixed engine handles web search. The history itself lives in a
 * separate file — recording off also wipes it (see SearchSettingsScreen).
 */
@Serializable
data class SearchSettings(
    val recentRecording: Boolean = true,
    val webEngine: WebSearchEngine = WebSearchEngine.Google,
)

/** Bar color choice (design 4.1). */
@Serializable
enum class EdgeBarColor {
    White,
    Black,
}

/**
 * One edge bar (design 4.1). The on-screen rectangle is the hit area —
 * nothing invisible is added around it.
 *
 * [verticalBias] positions the bar centre within the usable height as a
 * fraction (0 = top, 1 = bottom), so it survives rotation and font-scale
 * changes.
 */
@Serializable
data class EdgeBarSettings(
    val verticalBias: Float = 0.5f,
    val lengthDp: Float = DEFAULT_LENGTH_DP,
    val thicknessDp: Float = DEFAULT_THICKNESS_DP,
    val opacity: Float = DEFAULT_OPACITY,
    val color: EdgeBarColor = EdgeBarColor.White,
) {
    /** Clamps a decoded value into the designed ranges (design 4.1). */
    fun sanitized(): EdgeBarSettings = copy(
        verticalBias = verticalBias.coerceIn(0f, 1f),
        lengthDp = lengthDp.coerceIn(MIN_LENGTH_DP, MAX_LENGTH_DP),
        thicknessDp = thicknessDp.coerceIn(MIN_THICKNESS_DP, MAX_THICKNESS_DP),
        opacity = opacity.coerceIn(MIN_OPACITY, MAX_OPACITY),
    )

    companion object {
        const val DEFAULT_LENGTH_DP = 96f
        const val MIN_LENGTH_DP = 48f
        const val MAX_LENGTH_DP = 160f
        const val DEFAULT_THICKNESS_DP = 12f
        const val MIN_THICKNESS_DP = 8f
        const val MAX_THICKNESS_DP = 48f
        const val DEFAULT_OPACITY = 0.45f
        const val MIN_OPACITY = 0.20f
        const val MAX_OPACITY = 1f
    }
}

/** How TOOLS is revealed inside the DO panel (design 4.3). */
@Serializable
enum class ToolsOpenMode {
    /** TOOLS button inside the DO panel. Initial value. */
    Tap,

    /** Dragging past the depth threshold from the right bar expands TOOLS. */
    DeepPull,
}

/** TOOLS open mode, deep-pull depth and the tool list (design 4.3, 7). */
@Serializable
data class ToolsSettings(
    val openMode: ToolsOpenMode = ToolsOpenMode.Tap,
    val deepPullFraction: Float = DEFAULT_DEEP_PULL_FRACTION,
    /**
     * Tool order and visibility (design 7). Added in schemaVersion 6;
     * earlier files decode to the designed default order. Always kept
     * normalized to the five known tools by [ToolRules.normalized].
     */
    val items: List<ToolSetting> = ToolItem.entries.map { ToolSetting(it.id) },
) {
    fun sanitized(): ToolsSettings = copy(
        deepPullFraction = deepPullFraction.coerceIn(
            MIN_DEEP_PULL_FRACTION,
            MAX_DEEP_PULL_FRACTION,
        ),
        items = ToolRules.normalized(items),
    )

    companion object {
        const val DEFAULT_DEEP_PULL_FRACTION = 0.75f
        const val MIN_DEEP_PULL_FRACTION = 0.55f
        const val MAX_DEEP_PULL_FRACTION = 0.90f

        /** Pull back this far below the threshold to collapse (design 4.3). */
        const val HYSTERESIS_FRACTION = 0.08f
    }
}

/** Bar/drag haptics (design 4.1). */
@Serializable
enum class VibrationMode {
    System,
    Off,
}

/**
 * Optional system actions (design 13): individual switches for the
 * notification shade, screen-off and the TOOLS screenshot. Each needs both
 * its switch and an enabled+connected QuietSystemService to run.
 * [screenshotEnabled] was added in schemaVersion 6.
 */
@Serializable
data class SystemActionSettings(
    val notificationsEnabled: Boolean = false,
    val screenOffEnabled: Boolean = false,
    val screenshotEnabled: Boolean = false,
)
