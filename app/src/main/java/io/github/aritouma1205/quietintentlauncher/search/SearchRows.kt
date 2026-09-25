package io.github.aritouma1205.quietintentlauncher.search

import androidx.annotation.StringRes
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.apps.AppEntry
import io.github.aritouma1205.quietintentlauncher.home.ToolItem
import io.github.aritouma1205.quietintentlauncher.settings.DerivedOp
import io.github.aritouma1205.quietintentlauncher.settings.DoAction
import io.github.aritouma1205.quietintentlauncher.settings.StoredTarget

/**
 * One resolved row of local search results (design 9.1). The pure matcher in
 * [LocalSearch] ranks lightweight [SearchItem]s; the ViewModel maps them back
 * to these domain rows for rendering and launch.
 */
sealed interface SearchRow {
    /** Stable id shared with the originating [SearchItem]. */
    val id: String

    /**
     * A DO action (visible or hidden — hiding declutters the panel only).
     * [targetLabel] is set only when the result list holds another action
     * with the same name: it carries the resolved launch-target name so
     * same-named actions stay distinguishable (design 6).
     */
    data class Action(
        val action: DoAction,
        val targetLabel: String? = null,
    ) : SearchRow {
        override val id: String get() = "action:${action.id}"
    }

    /** A labelled derived op of an action. */
    data class Op(val action: DoAction, val op: DerivedOp) : SearchRow {
        override val id: String get() = "op:${op.id}"
    }

    /** An installed app; [showPackage] distinguishes same-named apps. */
    data class App(val entry: AppEntry, val showPackage: Boolean) : SearchRow {
        override val id: String get() = "app:${entry.key}"
    }

    /** A TOOLS entry; tapping opens the DO panel with TOOLS expanded. */
    data class Tool(val tool: ToolItem) : SearchRow {
        override val id: String get() = "tool:${tool.id}"
    }

    /** A settings destination entry. */
    data class Setting(val destination: SettingsDestination) : SearchRow {
        override val id: String get() = "setting:${destination.name}"
    }
}

/** Settings screens reachable from search results (design 9.1, 11.2). */
enum class SettingsDestination(@param:StringRes val labelRes: Int) {
    Root(R.string.settings_title),
    Edge(R.string.edge_settings_title),
    Actions(R.string.do_settings_title),
    ContextSlots(R.string.context_settings_title),
    Search(R.string.search_settings_title),
    Info(R.string.info_settings_title),
}

/**
 * One 「最近」 row (design 9): the launched target plus its display label
 * resolved against live OS state. [appEntry] carries the app for icon
 * loading; shortcut/link rows are text-only.
 */
data class RecentRow(
    val target: StoredTarget,
    val label: String,
    val appEntry: AppEntry?,
    val showPackage: Boolean,
)
