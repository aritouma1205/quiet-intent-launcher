package io.github.aritouma1205.quietintentlauncher.home

import androidx.annotation.StringRes
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.settings.DoAction

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

/** Tool rows of the TOOLS area (design 7). Execution arrives in stage 6. */
enum class ToolItem(val id: String, @param:StringRes val labelRes: Int) {
    Light("light", R.string.tool_light),
    Calculator("calculator", R.string.tool_calculator),
    Qr("qr", R.string.tool_qr),
    Timer("timer", R.string.tool_timer),
    Screenshot("screenshot", R.string.tool_screenshot),
}
