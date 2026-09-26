package io.github.aritouma1205.quietintentlauncher.apps

import android.content.pm.ApplicationInfo
import androidx.annotation.StringRes
import io.github.aritouma1205.quietintentlauncher.R

/**
 * Grid categories for the all-apps screen (design 9.3). The enum order IS
 * the fixed display order (handoff implementer-brief-15-17 §4):
 * communication and work tools first, entertainment last, その他 always
 * last. Sections are emitted in ordinal order, so reorder only with a
 * design change.
 */
enum class AppCategory(@param:StringRes val labelRes: Int) {
    Social(R.string.app_category_social),
    Productivity(R.string.app_category_productivity),
    Audio(R.string.app_category_audio),
    Video(R.string.app_category_video),
    Image(R.string.app_category_image),
    Maps(R.string.app_category_maps),
    News(R.string.app_category_news),
    Game(R.string.app_category_game),
    Other(R.string.app_category_other),
}

/** One grid section: a localized title plus the entries shown under it. */
data class AllAppsSection<T>(
    @param:StringRes val titleRes: Int,
    val apps: List<T>,
)

/**
 * Pure classification and section-building for the categorized all-apps
 * grid (design 9.3). Kept Android-light so the mapping, ordering, empty-
 * category and 「最近」 rules are unit-testable.
 */
object AppCategories {

    const val RECENT_MAX = 6

    /**
     * Maps [ApplicationInfo.category] to a display category.
     * CATEGORY_UNDEFINED and any future or unknown value fall back to
     * [AppCategory.Other].
     */
    fun fromOsCategory(osCategory: Int): AppCategory = when (osCategory) {
        ApplicationInfo.CATEGORY_SOCIAL -> AppCategory.Social
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> AppCategory.Productivity
        ApplicationInfo.CATEGORY_AUDIO -> AppCategory.Audio
        ApplicationInfo.CATEGORY_VIDEO -> AppCategory.Video
        ApplicationInfo.CATEGORY_IMAGE -> AppCategory.Image
        ApplicationInfo.CATEGORY_MAPS -> AppCategory.Maps
        ApplicationInfo.CATEGORY_NEWS -> AppCategory.News
        ApplicationInfo.CATEGORY_GAME -> AppCategory.Game
        else -> AppCategory.Other
    }

    /**
     * Builds grid sections: 「最近」 first (newest launch order, deduplicated
     * and capped at [RECENT_MAX]), then each non-empty category in
     * [AppCategory] order. Entries present in [recents] stay in their
     * category section too (design 9.3: 重複許可 — categories list every
     * app). Input order is preserved inside each section, so callers sort
     * the catalog once before grouping.
     */
    fun <T> sections(
        apps: List<T>,
        recents: List<T>,
        recentMax: Int = RECENT_MAX,
        categoryOf: (T) -> AppCategory,
    ): List<AllAppsSection<T>> = buildList {
        val recentApps = recents.distinct().take(recentMax)
        if (recentApps.isNotEmpty()) {
            add(AllAppsSection(R.string.search_recent_section, recentApps))
        }
        val byCategory = apps.groupBy(categoryOf)
        AppCategory.entries.forEach { category ->
            byCategory[category]?.let { add(AllAppsSection(category.labelRes, it)) }
        }
    }
}
