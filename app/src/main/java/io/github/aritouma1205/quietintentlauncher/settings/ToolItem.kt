package io.github.aritouma1205.quietintentlauncher.settings

import androidx.annotation.StringRes
import io.github.aritouma1205.quietintentlauncher.R
import kotlinx.serialization.Serializable

/**
 * The fixed TOOLS set (design 7). [id] is the persisted identity used by
 * [ToolSetting]; [labelRes] is the display/search name.
 */
enum class ToolItem(val id: String, @param:StringRes val labelRes: Int) {
    Light("light", R.string.tool_light),
    Calculator("calculator", R.string.tool_calculator),
    Qr("qr", R.string.tool_qr),
    Timer("timer", R.string.tool_timer),
    Screenshot("screenshot", R.string.tool_screenshot),
    ;

    companion object {
        fun byId(id: String): ToolItem? = entries.firstOrNull { it.id == id }

        /** Tools whose behaviour is a launch target the user can assign. */
        val TARGETABLE: Set<ToolItem> = setOf(Calculator, Qr, Timer)

        /** Shortcut targets are only meaningful for the QR scanner (design 7). */
        val SHORTCUT_CAPABLE: Set<ToolItem> = setOf(Qr)
    }
}

/**
 * Per-tool configuration inside [ToolsSettings] (design 7, 14.1): display
 * order comes from the list position, [visible] declutters the panel, and
 * [target] is the user-picked launch destination for the targetable tools —
 * light and screenshot are internal operations and never carry a target.
 */
@Serializable
data class ToolSetting(
    val id: String,
    val visible: Boolean = true,
    val target: StoredTarget? = null,
)

/** Pure rules for the tools list (design 7). */
object ToolRules {

    /**
     * Normalizes a stored/derived tools list to exactly the five known
     * tools: stored order is preserved, unknown ids are dropped, duplicates
     * collapse to the first entry, and missing tools append in the designed
     * default order. Targets are kept only where the tool can use them —
     * light/screenshot can never carry one, links are never tool
     * targets, and a shortcut target only survives on the QR tool.
     */
    fun normalized(items: List<ToolSetting>): List<ToolSetting> {
        val present = items
            .filter { ToolItem.byId(it.id) != null }
            .distinctBy { it.id }
            .map { it.sanitized() }
        val missing = ToolItem.entries
            .filter { entry -> present.none { it.id == entry.id } }
            .map { ToolSetting(it.id) }
        return present + missing
    }

    fun ToolSetting.sanitized(): ToolSetting {
        val tool = ToolItem.byId(id) ?: return this
        // A stored target survives only if the tool editor could produce
        // it (design 7): apps for the three targetable tools, shortcuts
        // for QR alone. Links are never tool targets.
        val keptTarget = when {
            tool !in ToolItem.TARGETABLE -> null
            target is StoredTarget.HttpsLink -> null
            tool !in ToolItem.SHORTCUT_CAPABLE && target is StoredTarget.Shortcut -> null
            else -> target
        }
        return copy(target = keptTarget)
    }

    /** Moves the entry at [index] one position up; a no-op at the top. */
    fun moveUp(items: List<ToolSetting>, index: Int): List<ToolSetting> =
        swap(items, index, index - 1)

    /** Moves the entry at [index] one position down; a no-op at the bottom. */
    fun moveDown(items: List<ToolSetting>, index: Int): List<ToolSetting> =
        swap(items, index, index + 1)

    private fun swap(
        items: List<ToolSetting>,
        from: Int,
        to: Int,
    ): List<ToolSetting> {
        if (from !in items.indices || to !in items.indices) return items
        val result = items.toMutableList()
        val moved = result.removeAt(from)
        result.add(to, moved)
        return result
    }
}
