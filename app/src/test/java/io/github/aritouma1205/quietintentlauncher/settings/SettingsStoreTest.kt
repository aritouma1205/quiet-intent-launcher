package io.github.aritouma1205.quietintentlauncher.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SettingsStoreTest {

    private lateinit var dir: File
    private lateinit var file: File
    private lateinit var scope: CoroutineScope
    private val serializer = SettingsSerializer()

    @Before
    fun setUp() {
        dir = kotlin.io.path.createTempDirectory("settings-store-test").toFile()
        file = File(dir, "quiet_settings.json")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun newStore() = SettingsStore(
        scope = scope,
        serializer = serializer,
        fileProvider = { file },
        dataStoreFactory = { storeScope ->
            DataStoreFactory.create(
                serializer = serializer,
                scope = storeScope,
                produceFile = { file },
            )
        },
    )

    private suspend fun awaitSettled(store: SettingsStore): SettingsState =
        withTimeout(10_000) {
            store.state.first { it !is SettingsState.Loading }
        }

    @Test
    fun `absent file starts ready with defaults`() = runBlocking {
        val store = newStore()
        store.start()
        val state = awaitSettled(store)
        assertTrue(state is SettingsState.Ready)
        assertEquals(SettingsData(), (state as SettingsState.Ready).data)
    }

    @Test
    fun `valid file loads`() = runBlocking {
        file.writeText("""{"schemaVersion":1,"introCompleted":true}""")
        val store = newStore()
        store.start()
        val state = awaitSettled(store)
        assertTrue((state as SettingsState.Ready).data.introCompleted)
    }

    @Test
    fun `update persists new file`() = runBlocking {
        // NOTE: a single write to a not-yet-existing file is asserted here.
        // A second update would exercise DataStore's scratch-file rename over
        // an existing file, which fails on Windows JVM (POSIX-only atomic
        // rename); on Android this limitation does not apply.
        val store = newStore()
        store.start()
        assertTrue(awaitSettled(store) is SettingsState.Ready)

        assertTrue(store.update { it.copy(introCompleted = true) })
        val written = withTimeout(10_000) {
            store.state.first { it is SettingsState.Ready && it.data.introCompleted }
        }
        assertTrue(written is SettingsState.Ready)
        assertTrue(file.readText().contains("\"introCompleted\":true"))
    }

    @Test
    fun `corrupted file degrades and is preserved`() = runBlocking {
        file.writeText("this is not json {{{")
        val store = newStore()
        store.start()

        val state = awaitSettled(store)
        assertTrue(state is SettingsState.Degraded)
        // The original file must not be overwritten while degraded.
        assertEquals("this is not json {{{", file.readText())
        // Writes are refused in the temporary session.
        assertFalse(store.update { it.copy(introCompleted = true) })
    }

    @Test
    fun `future schema version degrades`() = runBlocking {
        file.writeText("""{"schemaVersion":999,"introCompleted":true}""")
        val store = newStore()
        store.start()
        assertTrue(awaitSettled(store) is SettingsState.Degraded)
    }

    @Test
    fun `retry rereads a repaired file`() = runBlocking {
        file.writeText("broken")
        val store = newStore()
        store.start()
        assertTrue(awaitSettled(store) is SettingsState.Degraded)

        file.writeText("""{"schemaVersion":1,"introCompleted":true}""")
        store.retry()
        val state = awaitSettled(store)
        assertTrue(state is SettingsState.Ready)
        assertTrue((state as SettingsState.Ready).data.introCompleted)
    }

    @Test
    fun `reset replaces the file with defaults`() = runBlocking {
        file.writeText("broken")
        val store = newStore()
        store.start()
        assertTrue(awaitSettled(store) is SettingsState.Degraded)

        assertTrue(store.resetToDefaults())
        val state = awaitSettled(store)
        assertTrue(state is SettingsState.Ready)
        assertEquals(SettingsData(), (state as SettingsState.Ready).data)
        assertTrue(
            file.readText().contains(
                "\"schemaVersion\":${SettingsData.CURRENT_SCHEMA_VERSION}",
            ),
        )
    }

    @Test
    fun `retry after a live store reopens without a duplicate instance`() =
        runBlocking {
            file.writeText("""{"schemaVersion":1,"introCompleted":true}""")
            val store = newStore()
            store.start()
            assertTrue(awaitSettled(store) is SettingsState.Ready)

            // The first DataStore is still registered for this file; retry
            // must release it before opening a replacement instead of
            // crashing on the single-instance check.
            store.retry()
            val state = awaitSettled(store)
            assertTrue(state is SettingsState.Ready)
            assertTrue((state as SettingsState.Ready).data.introCompleted)
        }

    @Test
    fun `reset after a live store reopens without a duplicate instance`() =
        runBlocking {
            val store = newStore()
            store.start()
            assertTrue(awaitSettled(store) is SettingsState.Ready)

            assertTrue(store.resetToDefaults())
            val state = awaitSettled(store)
            assertTrue(state is SettingsState.Ready)
            assertEquals(SettingsData(), (state as SettingsState.Ready).data)
        }

    @Test
    fun `file open failure degrades without crashing`() = runBlocking {
        // A path that exists but cannot be opened as a file (a directory)
        // fails the pre-read open itself, before the serializer runs.
        val dirPath = File(dir, "blocked_settings.json").apply { mkdirs() }
        val store = SettingsStore(
            scope = scope,
            serializer = serializer,
            fileProvider = { dirPath },
            dataStoreFactory = { s ->
                DataStoreFactory.create(
                    serializer = serializer,
                    scope = s,
                    produceFile = { dirPath },
                )
            },
        )
        store.start()
        assertTrue(awaitSettled(store) is SettingsState.Degraded)
        // The original path is untouched.
        assertTrue(dirPath.isDirectory)
    }

    @Test
    fun `read failure after open degrades and retry recovers`() = runBlocking {
        val flaky = object : Serializer<SettingsData> {
            private val delegate = SettingsSerializer()
            var failReads = true
            override val defaultValue: SettingsData get() = delegate.defaultValue
            override suspend fun readFrom(input: InputStream): SettingsData {
                if (failReads) throw CorruptionException("injected", null)
                return delegate.readFrom(input)
            }
            override suspend fun writeTo(t: SettingsData, output: OutputStream) =
                delegate.writeTo(t, output)
        }
        file.writeText("""{"schemaVersion":1,"introCompleted":true}""")
        val store = SettingsStore(
            scope = scope,
            serializer = serializer,
            fileProvider = { file },
            dataStoreFactory = { s ->
                DataStoreFactory.create(
                    serializer = flaky,
                    scope = s,
                    produceFile = { file },
                )
            },
        )
        store.start()
        // The pre-read (real serializer) passes but the DataStore read
        // fails, degrading the session with a live DataStore instance.
        assertTrue(awaitSettled(store) is SettingsState.Degraded)

        // Retry after the read failure must close the live instance and
        // reopen cleanly instead of registering a second DataStore.
        flaky.failReads = false
        store.retry()
        val state = awaitSettled(store)
        assertTrue(state is SettingsState.Ready)
        assertTrue((state as SettingsState.Ready).data.introCompleted)
    }

    @Test
    fun `failed replace preserves the original file`() = runBlocking {
        // The target is a directory: the atomic move cannot replace it, so
        // reset must fail while leaving the original path untouched.
        val dirPath = File(dir, "blocked_settings.json").apply { mkdirs() }
        val store = SettingsStore(
            scope = scope,
            serializer = serializer,
            fileProvider = { dirPath },
            dataStoreFactory = { s ->
                DataStoreFactory.create(
                    serializer = serializer,
                    scope = s,
                    produceFile = { dirPath },
                )
            },
        )
        store.start()
        assertTrue(awaitSettled(store) is SettingsState.Degraded)

        assertFalse(store.resetToDefaults())
        assertTrue(dirPath.isDirectory)
        // No scratch file is left behind either.
        assertFalse(File(dir, "blocked_settings.json.tmp").exists())
    }

    /**
     * A DataStore whose [DataStore.updateData] always throws [failure];
     * the data flow itself stays healthy.
     */
    private fun failingWriteStore(failure: Throwable): SettingsStore {
        val fake = object : DataStore<SettingsData> {
            private val state = MutableStateFlow(SettingsData())
            override val data: Flow<SettingsData> = state
            override suspend fun updateData(
                transform: suspend (SettingsData) -> SettingsData,
            ): SettingsData = throw failure
        }
        return SettingsStore(
            scope = scope,
            serializer = serializer,
            fileProvider = { file },
            dataStoreFactory = { fake },
        )
    }

    @Test
    fun `a cancelled update propagates instead of degrading`() = runBlocking {
        // Issue #42 (I42-T01): CancellationException extends
        // IllegalStateException — a cancelled updateData must reach the
        // caller, never the degrade path.
        val store = failingWriteStore(CancellationException("injected"))
        store.start()
        assertTrue(awaitSettled(store) is SettingsState.Ready)

        try {
            store.update { it.copy(introCompleted = true) }
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            // Expected — propagated, not swallowed as a write failure.
        }
        assertTrue(store.state.value is SettingsState.Ready)
    }

    @Test
    fun `an update closed-store failure degrades as before`() = runBlocking {
        // Contrast for I42-T01: a plain IllegalStateException still reports
        // failure and degrades — only cancellation was reclassified.
        val store = failingWriteStore(IllegalStateException("store closed"))
        store.start()
        assertTrue(awaitSettled(store) is SettingsState.Ready)

        assertFalse(store.update { it.copy(introCompleted = true) })
        assertTrue(store.state.value is SettingsState.Degraded)
    }

    @Test
    fun `a cancelled data flow does not degrade`() = runBlocking {
        // Issue #42 (I42-T02): a CancellationException surfacing through
        // the data flow is a lifecycle event, not corruption.
        val throwGate = CompletableDeferred<Unit>()
        val fake = object : DataStore<SettingsData> {
            private val state = MutableStateFlow(SettingsData())
            override val data: Flow<SettingsData> = flow {
                emit(SettingsData())
                throwGate.await()
                throw CancellationException("injected cancellation")
            }
            override suspend fun updateData(
                transform: suspend (SettingsData) -> SettingsData,
            ): SettingsData {
                val updated = transform(state.value)
                state.value = updated
                return updated
            }
        }
        val store = SettingsStore(
            scope = scope,
            serializer = serializer,
            fileProvider = { file },
            dataStoreFactory = { fake },
        )
        store.start()
        assertTrue(awaitSettled(store) is SettingsState.Ready)

        throwGate.complete(Unit)
        delay(300) // let the flow's failure land
        assertTrue(store.state.value is SettingsState.Ready)
    }
}
