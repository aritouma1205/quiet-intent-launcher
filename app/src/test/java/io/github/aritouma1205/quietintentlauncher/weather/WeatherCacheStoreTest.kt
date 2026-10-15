package io.github.aritouma1205.quietintentlauncher.weather

import androidx.datastore.core.DataStore
import io.github.aritouma1205.quietintentlauncher.settings.WeatherLocation
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * WeatherCacheStore write-path contract (Issue #42): cancellations reach
 * the caller instead of being reported as write failures, and a write
 * issued while the store is still opening waits for the open instead of
 * being silently dropped — unless start() was never called or the open
 * failed, which still fail fast. Stores run over in-memory DataStores,
 * same fake shape as WeatherServiceTest.
 */
class WeatherCacheStoreTest {

    private lateinit var dir: File
    private lateinit var file: File
    private lateinit var scope: CoroutineScope
    private val serializer = WeatherCacheSerializer()

    private val regionA = WeatherLocation("100", "Tokyo", 35.0, 139.0)
    private val regionB = WeatherLocation("200", "Osaka", 34.7, 135.5)

    @Before
    fun setUp() {
        dir = kotlin.io.path.createTempDirectory("weather-cache-store-test").toFile()
        file = File(dir, "quiet_weather.json")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun snapshot(region: WeatherLocation) = WeatherSnapshot(
        regionKey = region.key,
        temperatureCelsius = 20.0,
        weatherCode = 0,
        isDay = true,
        fetchedAtElapsedMs = 1_000L,
        fetchedAtWallMs = 1_700_000_000_000L,
        bootMarkerMs = 42L,
    )

    /** An in-memory DataStore; [failure] is thrown by every updateData. */
    private fun fakeStore(
        initial: WeatherCacheData = WeatherCacheData(),
        failure: Throwable? = null,
    ): WeatherCacheStore {
        val fake = object : DataStore<WeatherCacheData> {
            private val state = MutableStateFlow(initial)
            override val data: Flow<WeatherCacheData> = state
            override suspend fun updateData(
                transform: suspend (WeatherCacheData) -> WeatherCacheData,
            ): WeatherCacheData {
                failure?.let { throw it }
                val updated = transform(state.value)
                state.value = updated
                return updated
            }
        }
        return WeatherCacheStore(
            scope = scope,
            serializer = serializer,
            fileProvider = { file },
            dataStoreFactory = { fake },
        )
    }

    private suspend fun awaitOpen(store: WeatherCacheStore) = withTimeout(10_000) {
        while (!store.isOpen) delay(10)
    }

    /**
     * Asserts that [block] propagates a CancellationException to its
     * caller instead of converting it into a write failure (Issue #42).
     */
    private suspend fun assertCancelled(block: suspend () -> Boolean) {
        try {
            block()
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            // Expected — the cancellation reaches the caller unchanged.
        }
    }

    @Test
    fun `a cancelled write propagates and keeps the snapshot`() =
        runBlocking<Unit> {
            // Issue #42 (I42-T04): CancellationException extends
            // IllegalStateException — a cancelled updateData must reach the
            // caller, never the failure branch.
            val seeded = WeatherCacheData.of(snapshot(regionA))
            val store = fakeStore(
                seeded,
                failure = CancellationException("injected cancellation"),
            )
            store.start()
            awaitOpen(store)
            withTimeout(10_000) { store.snapshot.first { it != null } }

            assertCancelled { store.save(snapshot(regionA), regionA) }
            assertCancelled { store.clear() }
            // The cached reading is untouched.
            assertEquals(regionA.key, store.snapshot.value?.regionKey)
        }

    @Test
    fun `a save issued before the open finishes waits and lands`() =
        runBlocking<Unit> {
            // Issue #42 (I42-T07a): a fetch completing while the store is
            // still opening must not be dropped.
            val gate = CompletableDeferred<Unit>()
            val state = MutableStateFlow(WeatherCacheData())
            val fake = object : DataStore<WeatherCacheData> {
                override val data: Flow<WeatherCacheData> = state
                override suspend fun updateData(
                    transform: suspend (WeatherCacheData) -> WeatherCacheData,
                ): WeatherCacheData {
                    val updated = transform(state.value)
                    state.value = updated
                    return updated
                }
            }
            val store = WeatherCacheStore(
                scope = scope,
                serializer = serializer,
                fileProvider = { file },
                dataStoreFactory = {
                    gate.await()
                    fake
                },
            )
            store.start()

            val pending = async { store.save(snapshot(regionA), regionA) }
            // While the factory is gated the write is pending, not dropped.
            delay(300)
            assertFalse(pending.isCompleted)
            assertNull(store.snapshot.value)

            gate.complete(Unit)
            assertTrue(withTimeout(5_000) { pending.await() })
            withTimeout(10_000) {
                store.snapshot.first { it?.regionKey == regionA.key }
            }
        }

    @Test
    fun `a failed open makes writes return false without hanging`() =
        runBlocking<Unit> {
            // Issue #42 (I42-T07b): an open that fails must release waiting
            // writers as a normal failure.
            val store = WeatherCacheStore(
                scope = scope,
                serializer = serializer,
                fileProvider = { file },
                dataStoreFactory = {
                    throw IllegalStateException("open failed")
                },
            )
            store.start()

            assertFalse(withTimeout(5_000) { store.save(snapshot(regionA), regionA) })
            assertFalse(withTimeout(5_000) { store.clear() })
        }

    @Test
    fun `a region mismatch keeps the stored snapshot`() = runBlocking<Unit> {
        // Issue #42 (I42-T07c): the existing expected-region guard is
        // unchanged — a stale response returns true but writes nothing.
        val store = fakeStore()
        store.start()
        awaitOpen(store)

        assertTrue(store.save(snapshot(regionA), regionB))
        delay(200)
        assertNull(store.snapshot.value)

        // A matching region still lands.
        assertTrue(store.save(snapshot(regionA), regionA))
        withTimeout(10_000) {
            store.snapshot.first { it?.regionKey == regionA.key }
        }
    }

    @Test
    fun `writes before start return false immediately`() = runBlocking<Unit> {
        // Issue #42 (I42-T08): the documented contract — without start()
        // there is no open to wait for, so writes fail fast.
        val store = fakeStore()
        assertFalse(withTimeout(1_000) { store.save(snapshot(regionA), regionA) })
        assertFalse(withTimeout(1_000) { store.clear() })
    }
}
