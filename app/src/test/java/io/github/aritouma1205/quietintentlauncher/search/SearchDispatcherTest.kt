package io.github.aritouma1205.quietintentlauncher.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Debounce + generation guard (design 9.1: 入力変更から100msを目安に更新し、
 * 古い検索結果が新しいクエリを上書きしないよう検索世代を照合する).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchDispatcherTest {

    @Test
    fun `rapid inputs collapse into one computation`() = runTest {
        var calls = 0
        val dispatcher = SearchDispatcher(
            scope = this,
            compute = { calls++; "R:$it" },
            emptyResult = "EMPTY",
        )
        dispatcher.submit("a")
        advanceTimeBy(50)
        dispatcher.submit("ab")
        advanceTimeBy(50)
        dispatcher.submit("abc")
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals("R:abc", dispatcher.results.value)
    }

    @Test
    fun `a stale computation never overwrites a newer result`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val dispatcher = SearchDispatcher(
            scope = this,
            compute = {
                if (it == "slow") {
                    // Held open: even if it somehow completed later, the
                    // generation check must refuse to publish it.
                    gate.await()
                    "SLOW"
                } else {
                    "FAST:$it"
                }
            },
            emptyResult = "EMPTY",
        )
        dispatcher.submit("slow")
        advanceTimeBy(SearchDispatcher.DEBOUNCE_MILLIS)
        dispatcher.submit("fast")
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("FAST:fast", dispatcher.results.value)
    }

    @Test
    fun `reset clears pending work and the published result`() = runTest {
        val dispatcher = SearchDispatcher(
            scope = this,
            compute = { "R:$it" },
            emptyResult = "EMPTY",
        )
        dispatcher.submit("a")
        advanceUntilIdle()
        assertEquals("R:a", dispatcher.results.value)
        dispatcher.reset()
        assertEquals("EMPTY", dispatcher.results.value)
        dispatcher.submit("b")
        advanceTimeBy(SearchDispatcher.DEBOUNCE_MILLIS - 1)
        dispatcher.reset()
        advanceUntilIdle()
        assertEquals("EMPTY", dispatcher.results.value)
    }
}
