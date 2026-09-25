package io.github.aritouma1205.quietintentlauncher.home

import io.github.aritouma1205.quietintentlauncher.context.ContextRule
import io.github.aritouma1205.quietintentlauncher.settings.DoAction
import io.github.aritouma1205.quietintentlauncher.settings.ToolItem

/** Why a configured target cannot launch right now (design 6). */
enum class UnavailableReason {
    /** The target app was uninstalled or disabled. */
    AppGone,

    /** The shortcut was revoked, or the HOME role needed to see it is lost. */
    ShortcutGone,

    /** No installed app can open the HTTPS link. */
    NoHandler,
}

/** Launchability of one action row, resolved against live OS state. */
sealed interface ActionStatus {
    /** No target configured yet. */
    data object Unset : ActionStatus

    /** The target resolves; [targetLabel] names the app/link/shortcut. */
    data class Available(val targetLabel: String) : ActionStatus

    /** The configured target is gone or unusable; config stays intact. */
    data class Unavailable(val reason: UnavailableReason) : ActionStatus
}

/** One DO panel row: the configured action plus its resolved status. */
data class ActionRow(
    val action: DoAction,
    val status: ActionStatus,
)

/**
 * One Context Slot row of the DO panel (design 10): the resolved action
 * plus the rule that selected it. [matchedRule] is null when the slot's
 * default action is shown — the display reason explains either.
 */
data class ContextRow(
    val slotIndex: Int,
    val slotLabel: String,
    val action: DoAction,
    val matchedRule: ContextRule?,
)

/** Why a tool row cannot run right now (design 7). */
enum class ToolUnavailable {
    /** No launch target configured (calculator / QR / timer fallback). */
    Unset,

    /** The configured target app/shortcut is gone. */
    TargetGone,

    /** No handler for the system timer list and no fallback target set. */
    NoTimerHandler,

    /** The device has no usable camera flash. */
    NoFlash,

    /** The CAMERA permission has not been granted yet. */
    PermissionMissing,

    /** Another app holds the camera/torch right now. */
    Busy,

    /** The backing accessibility service is disabled or disconnected. */
    ServiceInactive,

    /** The per-feature switch is off (screenshot before opt-in). */
    SwitchOff,
}

/**
 * One TOOLS row (design 7): the tool plus its resolved state. The status
 * line is rendered by the panel; execution goes through the ViewModel.
 */
sealed interface ToolStatus {
    /** Ready to run; [detail] is an optional secondary label. */
    data class Ready(val detail: String? = null) : ToolStatus

    /** The torch is on; tapping turns it off. */
    data object LightOn : ToolStatus

    /** The torch is off; tapping turns it on. */
    data object LightOff : ToolStatus

    /** Cannot run; the row explains why and may offer a settings path. */
    data class Blocked(val reason: ToolUnavailable) : ToolStatus
}

data class ToolRow(
    val tool: ToolItem,
    val status: ToolStatus,
)
