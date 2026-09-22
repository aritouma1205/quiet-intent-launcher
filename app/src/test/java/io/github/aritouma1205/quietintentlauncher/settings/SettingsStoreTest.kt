package io.github.aritouma1205.quietintentlauncher.settings

import androidx.datastore.core.DataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        dataStoreFactory = {
            DataStoreFactory.create(
                serializer = serializer,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
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
        assertTrue(file.readText().contains("\"schemaVersion\":1"))
    }
}
