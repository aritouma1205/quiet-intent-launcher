package io.github.aritouma1205.quietintentlauncher.recent

import io.github.aritouma1205.quietintentlauncher.launch.stableKey
import io.github.aritouma1205.quietintentlauncher.settings.StoredTarget

/**
 * Pure rules for the 「最近」 list (design 9): at most 20 entries, the same
 * target never appears twice, newest first, and entries older than 30 days
 * are removed. No Android dependencies — usable from plain unit tests.
 */
object RecentRules {
    const val MAX_ENTRIES = 20

    /** 30 days in milliseconds — 「30日経過で削除」 (design 9). */
    const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000

    /**
     * De-duplicates by [StoredTarget.stableKey] keeping the newest timestamp,
     * drops entries older than [RETENTION_MILLIS] relative to [nowMillis],
     * sorts newest first and caps the list at [MAX_ENTRIES]. An entry exactly
     * 30 days old is kept — deletion starts strictly past the boundary
     * (「30日経過で削除」).
     */
    fun normalize(entries: List<RecentEntry>, nowMillis: Long): List<RecentEntry> {
        val newestPerKey = LinkedHashMap<String, RecentEntry>(entries.size)
        for (entry in entries) {
            val key = entry.target.stableKey
            val current = newestPerKey[key]
            if (current == null ||
                entry.lastLaunchedAtMillis > current.lastLaunchedAtMillis
            ) {
                newestPerKey[key] = entry
            }
        }
        val oldestKept = nowMillis - RETENTION_MILLIS
        return newestPerKey.values
            .filter { it.lastLaunchedAtMillis >= oldestKept }
            .sortedByDescending { it.lastLaunchedAtMillis }
            .take(MAX_ENTRIES)
    }

    /**
     * Records a successful launch of [target] at [nowMillis]: any existing
     * entry for the same target is removed, the new entry is prepended, and
     * the list is normalized.
     */
    fun recorded(
        entries: List<RecentEntry>,
        target: StoredTarget,
        nowMillis: Long,
    ): List<RecentEntry> {
        val rest = entries.filterNot { it.target.stableKey == target.stableKey }
        return normalize(listOf(RecentEntry(target, nowMillis)) + rest, nowMillis)
    }
}
