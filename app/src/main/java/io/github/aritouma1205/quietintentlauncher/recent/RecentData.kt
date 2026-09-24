package io.github.aritouma1205.quietintentlauncher.recent

import io.github.aritouma1205.quietintentlauncher.settings.StoredTarget
import kotlinx.serialization.Serializable

/**
 * One entry of the 「最近」 history (design 9, 14.1): the launched target and
 * the wall-clock time of its last successful launch. Only targets launched
 * from this launcher qualify — search text and web browsing history are
 * never persisted.
 */
@Serializable
data class RecentEntry(
    val target: StoredTarget,
    val lastLaunchedAtMillis: Long,
)

/**
 * The persisted 「最近」 file (design 14.1: Recent lives in its own file,
 * separate from Settings). The list is disposable history rather than
 * configuration, so the file carries no schemaVersion and is discarded —
 * not migrated — when unreadable.
 */
@Serializable
data class RecentData(
    val entries: List<RecentEntry> = emptyList(),
)
