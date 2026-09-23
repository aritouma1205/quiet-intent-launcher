package io.github.aritouma1205.quietintentlauncher.settings

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
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 2
    }
}

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

/** TOOLS open mode and the deep-pull depth (design 4.3). */
@Serializable
data class ToolsSettings(
    val openMode: ToolsOpenMode = ToolsOpenMode.Tap,
    val deepPullFraction: Float = DEFAULT_DEEP_PULL_FRACTION,
) {
    fun sanitized(): ToolsSettings = copy(
        deepPullFraction = deepPullFraction.coerceIn(
            MIN_DEEP_PULL_FRACTION,
            MAX_DEEP_PULL_FRACTION,
        ),
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
 * Optional system actions (design 13). The switches and the backing
 * accessibility service arrive with the system-action stage; the fields are
 * persisted now so the gesture pipeline already honours them.
 */
@Serializable
data class SystemActionSettings(
    val notificationsEnabled: Boolean = false,
    val screenOffEnabled: Boolean = false,
)
