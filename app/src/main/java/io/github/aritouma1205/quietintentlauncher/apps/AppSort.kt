package io.github.aritouma1205.quietintentlauncher.apps

import java.text.Collator
import java.util.Locale

/** Pure sort key so ordering logic is unit-testable without Android types. */
data class AppSortKey(
    val label: String,
    val packageName: String,
    val activityName: String,
)

/**
 * All-apps ordering (design 9.3): Japanese-locale label order, then
 * package name, then activity name so same-named apps and packages with
 * multiple launch activities stay distinguishable and stable.
 */
object AppSort {
    fun <T> sorted(items: List<T>, keyOf: (T) -> AppSortKey): List<T> {
        val collator = Collator.getInstance(Locale.JAPANESE)
        return items.sortedWith { a, b ->
            val ka = keyOf(a)
            val kb = keyOf(b)
            collator.compare(ka.label, kb.label).takeIf { it != 0 }
                ?: ka.packageName.compareTo(kb.packageName).takeIf { it != 0 }
                ?: ka.activityName.compareTo(kb.activityName)
        }
    }
}
