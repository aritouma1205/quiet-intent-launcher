package io.github.aritouma1205.quietintentlauncher.search

/** Where a searchable row comes from (design 9.1 candidate types). */
enum class SearchItemKind { Action, DerivedOp, ToolSetting, App }

/** Whole-query match strength; a better class always ranks earlier. */
enum class MatchClass { Exact, Prefix, Partial }

/**
 * One searchable target. [id] is a stable unique id used for duplicate
 * removal and the final ordering tiebreak. [groupOrder] is the rank inside
 * its kind (actions: settings order, apps: Japanese name order index);
 * [subOrder] ranks derived operations inside their parent action.
 */
data class SearchItem(
    val id: String,
    val kind: SearchItemKind,
    val primary: String,
    val aliases: List<String> = emptyList(),
    val groupOrder: Int,
    val subOrder: Int = 0,
)

/**
 * On-device local search (design 9.1): pure matching and ranking over an
 * already-built candidate list. The UI applies the 256-char input cap and
 * pages results with [PAGE_SIZE]; this function returns every match.
 */
object LocalSearch {
    const val MAX_QUERY_CHARS = 256
    const val PAGE_SIZE = 20

    /** Rank for items that satisfy the AND filter without containing the whole query. */
    private const val AND_ONLY_RANK = 3

    fun search(rawQuery: String, items: List<SearchItem>): List<SearchItem> {
        val query = SearchNormalize.normalize(rawQuery)
        if (query.isEmpty()) return emptyList()
        val terms = query.split(' ')

        return items
            .distinctBy { it.id }
            .mapNotNull { item ->
                val names = listOf(item.primary)
                    .plus(item.aliases)
                    .map(SearchNormalize::normalize)
                    .filter { it.isNotEmpty() }
                val allTermsHit = terms.all { term -> names.any { term in it } }
                if (!allTermsHit) return@mapNotNull null
                Ranked(item, bestRank(names, query))
            }
            .sortedWith(
                compareBy<Ranked> { it.rank }
                    .thenBy { kindRank(it.item.kind) }
                    .thenBy { it.item.groupOrder }
                    .thenBy { it.item.subOrder }
                    .thenBy { it.item.id },
            )
            .map { it.item }
    }

    /** Best whole-query class over the primary and every alias; [AND_ONLY_RANK] when absent. */
    private fun bestRank(names: List<String>, query: String): Int {
        var best = AND_ONLY_RANK
        for (name in names) {
            val cls = when {
                name == query -> MatchClass.Exact
                name.startsWith(query) -> MatchClass.Prefix
                query in name -> MatchClass.Partial
                else -> null
            }
            if (cls != null && cls.ordinal < best) best = cls.ordinal
        }
        return best
    }

    /** Actions and derived operations share the top tier (design 9.1). */
    private fun kindRank(kind: SearchItemKind): Int = when (kind) {
        SearchItemKind.Action, SearchItemKind.DerivedOp -> 0
        SearchItemKind.ToolSetting -> 1
        SearchItemKind.App -> 2
    }

    private data class Ranked(val item: SearchItem, val rank: Int)
}
