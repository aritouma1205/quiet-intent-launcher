package io.github.aritouma1205.quietintentlauncher.search

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Debounced query -> results pipeline with a generation guard (design 9.1:
 * 入力変更から100msを目安に更新し、古い検索結果が新しいクエリを上書きしない
 * よう検索世代を照合する).
 *
 * Pure coroutines + JVM so the stale-result race is unit-testable; [compute]
 * supplies the matching work.
 */
class SearchDispatcher<T>(
    private val scope: CoroutineScope,
    private val compute: suspend (query: String) -> T,
    private val emptyResult: T,
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
) {
    private val _results = MutableStateFlow(emptyResult)
    val results: StateFlow<T> = _results.asStateFlow()

    private var job: Job? = null
    private var generation = 0

    /** Latest query wins; a superseded computation is never published. */
    fun submit(query: String) {
        val gen = ++generation
        job?.cancel()
        job = scope.launch {
            delay(debounceMillis)
            val result = compute(query)
            // The job is usually cancelled before this point; the explicit
            // generation check still guards a result that raced through.
            if (gen == generation) _results.value = result
        }
    }

    /** Drops pending work and clears the published result. */
    fun reset() {
        generation++
        job?.cancel()
        _results.value = emptyResult
    }

    companion object {
        const val DEBOUNCE_MILLIS = 100L
    }
}
