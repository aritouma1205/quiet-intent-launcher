package io.github.aritouma1205.quietintentlauncher.apps

import android.content.pm.ApplicationInfo
import io.github.aritouma1205.quietintentlauncher.R
import org.junit.Assert.assertEquals
import org.junit.Test

class AppCategoriesTest {

    private fun titles(sections: List<AllAppsSection<String>>): List<Int> =
        sections.map { it.titleRes }

    @Test
    fun `every OS category maps to its display category`() {
        val cases = mapOf(
            ApplicationInfo.CATEGORY_SOCIAL to AppCategory.Social,
            ApplicationInfo.CATEGORY_PRODUCTIVITY to AppCategory.Productivity,
            ApplicationInfo.CATEGORY_AUDIO to AppCategory.Audio,
            ApplicationInfo.CATEGORY_VIDEO to AppCategory.Video,
            ApplicationInfo.CATEGORY_IMAGE to AppCategory.Image,
            ApplicationInfo.CATEGORY_MAPS to AppCategory.Maps,
            ApplicationInfo.CATEGORY_NEWS to AppCategory.News,
            ApplicationInfo.CATEGORY_GAME to AppCategory.Game,
        )
        cases.forEach { (osCategory, expected) ->
            assertEquals(
                "osCategory=$osCategory",
                expected,
                AppCategories.fromOsCategory(osCategory),
            )
        }
    }

    @Test
    fun `undefined and unknown values fall back to Other`() {
        assertEquals(
            AppCategory.Other,
            AppCategories.fromOsCategory(ApplicationInfo.CATEGORY_UNDEFINED),
        )
        assertEquals(AppCategory.Other, AppCategories.fromOsCategory(-1))
        assertEquals(AppCategory.Other, AppCategories.fromOsCategory(42))
        assertEquals(AppCategory.Other, AppCategories.fromOsCategory(Int.MAX_VALUE))
    }

    @Test
    fun `sections follow the fixed category order`() {
        // Input order deliberately reversed: the section order must be the
        // enum order, not the order categories first appear.
        val apps = listOf("other-app", "game-app", "social-app")
        val sections = AppCategories.sections(apps, emptyList()) { name ->
            when (name) {
                "social-app" -> AppCategory.Social
                "game-app" -> AppCategory.Game
                else -> AppCategory.Other
            }
        }
        assertEquals(
            listOf(
                R.string.app_category_social,
                R.string.app_category_game,
                R.string.app_category_other,
            ),
            titles(sections),
        )
    }

    @Test
    fun `all nine categories emit in the fixed display order`() {
        // Seeded in reverse so a regression to input order fails loudly.
        val apps = AppCategory.entries.reversed().map { "app-${it.name}" }
        val sections = AppCategories.sections(apps, emptyList()) { name ->
            AppCategory.valueOf(name.removePrefix("app-"))
        }
        assertEquals(
            listOf(
                R.string.app_category_social,
                R.string.app_category_productivity,
                R.string.app_category_audio,
                R.string.app_category_video,
                R.string.app_category_image,
                R.string.app_category_maps,
                R.string.app_category_news,
                R.string.app_category_game,
                R.string.app_category_other,
            ),
            titles(sections),
        )
    }

    @Test
    fun `empty categories are skipped`() {
        val apps = listOf("social-app", "other-app")
        val sections = AppCategories.sections(apps, emptyList()) { name ->
            if (name == "social-app") AppCategory.Social else AppCategory.Other
        }
        assertEquals(2, sections.size)
        assertEquals(
            listOf(R.string.app_category_social, R.string.app_category_other),
            titles(sections),
        )
    }

    @Test
    fun `recent section is first and capped at six`() {
        val recents = (1..8).map { "recent-$it" }
        val apps = listOf("recent-1", "recent-2", "other-app")
        val sections = AppCategories.sections(apps, recents) { AppCategory.Other }

        assertEquals(R.string.search_recent_section, sections.first().titleRes)
        assertEquals(6, sections.first().apps.size)
        assertEquals(recents.take(6), sections.first().apps)
        // 重複許可: recent entries stay in their category too.
        val category = sections.first { it.titleRes == R.string.app_category_other }
        assertEquals(apps, category.apps)
    }

    @Test
    fun `no recents means no recent section`() {
        val sections = AppCategories.sections(
            listOf("a"),
            emptyList(),
        ) { AppCategory.Other }
        assertEquals(listOf(R.string.app_category_other), titles(sections))
    }

    @Test
    fun `within a category the input order is preserved`() {
        val apps = listOf("b-app", "a-app", "c-app")
        val sections = AppCategories.sections(apps, emptyList()) { AppCategory.Game }
        assertEquals(apps, sections.single().apps)
    }

    @Test
    fun `recent duplicates are deduplicated`() {
        val sections = AppCategories.sections(
            listOf("a"),
            listOf("a", "a", "b", "b", "a"),
        ) { AppCategory.Other }
        assertEquals(listOf("a", "b"), sections.first().apps)
    }
}
