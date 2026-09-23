package io.github.aritouma1205.quietintentlauncher.home

import androidx.annotation.StringRes
import io.github.aritouma1205.quietintentlauncher.R

/**
 * The six default actions (design 6). Editing launch targets, order and
 * visibility is a later stage; this stage renders the fixed list.
 */
enum class DefaultAction(val id: String, @param:StringRes val labelRes: Int) {
    Take("take", R.string.action_take),
    Talk("talk", R.string.action_talk),
    Listen("listen", R.string.action_listen),
    Watch("watch", R.string.action_watch),
    Go("go", R.string.action_go),
    LookUp("lookup", R.string.action_lookup),
}

/** Tool rows of the TOOLS area (design 7). Execution arrives in stage 6. */
enum class ToolItem(val id: String, @param:StringRes val labelRes: Int) {
    Light("light", R.string.tool_light),
    Calculator("calculator", R.string.tool_calculator),
    Qr("qr", R.string.tool_qr),
    Timer("timer", R.string.tool_timer),
    Screenshot("screenshot", R.string.tool_screenshot),
}
