package io.github.aritouma1205.quietintentlauncher.recent

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import io.github.aritouma1205.quietintentlauncher.settings.StoredTarget
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecentStoreTest {

    private lateinit var dir: File
    private lateinit var file: File
    private lateinit var scope: CoroutineScope
    private val serializer = RecentSerializer()
    private var now = 1_700_000_000_000L

    private val appA = StoredTarget.App("com.a/.Main")
    private val appB = StoredTarget.App("com.b/.Main")

    @Before
    fun setUp() {
        dir = kotlin.io.path.createTempDirectory("recent-store-test").toFile()
        file = File(dir, "quiet_recent.json")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun newStore() = RecentStore(
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
        clock = { now },
    )

    /**
     * A store backed by an in-memory DataStore. The real DataStore is covered
     * by the file-backed tests; multi-write scenarios go through this fake
     * because a second DataStore updateData over an existing file exercises
     * the scratch-file rename that fails on Windows JVM (POSIX-only atomic
     * rename — see SettingsStoreTest). On Android the limit does not apply.
     */
    private fun fakeStore(
        initial: RecentData,
        failWrites: Boolean = false,
    ): RecentStore {
        val fake = object : DataStore<RecentData> {
            private val state = MutableStateFlow(initial)
            override val data: Flow<RecentData> = state
            override suspend fun updateData(
                transform: suspend (RecentData) -> RecentData,
            ): RecentData {
                if (failWrites) throw IOException("injected write failure")
                val updated = transform(state.value)
                state.value = updated
                return updated
            }
        }
        return RecentStore(
            scope = scope,
            serializer = serializer,
            fileProvider = { file },
            dataStoreFactory = { fake },
            clock = { now },
        )
    }

    private suspend fun awaitOpen(store: RecentStore) = withTimeout(10_000) {
        while (!store.isOpen) delay(10)
    }

    @Test
    fun `absent file starts empty`() = runBlocking {
        val store = newStore()
        store.start()
        awaitOpen(store)
        assertEquals(emptyList<RecentEntry>(), store.entries.value)
    }

    @Test
    fun `record writes a new file and reflects in entries`() = runBlocking {
        // NOTE: a single write to a not-yet-existing file is asserted here.
        // A second update would exercise DataStore's scratch-file rename over
        // an existing file, which fails on Windows JVM (see SettingsStoreTest).
        val store = newStore()
        store.start()
        awaitOpen(store)

        assertTrue(store.record(appA))
        val entries = withTimeout(10_000) {
            store.entries.first { it.isNotEmpty() }
        }
        assertEquals(appA, entries.single().target)
        assertEquals(now, entries.single().lastLaunchedAtMillis)
        assertTrue(file.readText().contains("com.a/.Main"))
    }

    @Test
    fun `corrupted file is deleted and the store starts empty`() =
        runBlocking<Unit> {
        file.writeText("this is not json {{{")
        val store = newStore()
        store.start()
        awaitOpen(store)

        // Disposable history: the unreadable file is discarded, not kept.
        assertFalse(file.exists())
        assertEquals(emptyList<RecentEntry>(), store.entries.value)

        // The store stays usable: one fresh write succeeds.
        assertTrue(store.record(appA))
        val entries = withTimeout(10_000) {
            store.entries.first { it.size == 1 }
        }
        assertEquals(appA, entries.single().target)
    }

    @Test
    fun `expired entries are dropped on load`() = runBlocking {
        val expired = RecentEntry(appA, now - RecentRules.RETENTION_MILLIS - 1)
        val fresh = RecentEntry(appB, now)
        file.writeText(serializer.serialize(RecentData(listOf(expired, fresh))))
        val store = newStore()
        store.start()
        awaitOpen(store)

        val entries = withTimeout(10_000) {
            store.entries.first { it.isNotEmpty() }
        }
        assertEquals(listOf(fresh), entries)
    }

    @Test
    fun `expired entries are pruned back to storage on load`() =
        runBlocking<Unit> {
        // Not just hidden: the retained set is written back so the file
        // itself loses expired/duplicate rows (design 9: 30日経過で削除).
        val expired = RecentEntry(appA, now - RecentRules.RETENTION_MILLIS - 1)
        val fresh = RecentEntry(appB, now)
        val writes = mutableListOf<RecentData>()
        val fake = object : DataStore<RecentData> {
            private val state =
                MutableStateFlow(RecentData(listOf(expired, fresh)))
            override val data: Flow<RecentData> = state
            override suspend fun updateData(
                transform: suspend (RecentData) -> RecentData,
            ): RecentData {
                val updated = transform(state.value)
                state.value = updated
                writes += updated
                return updated
            }
        }
        val store = RecentStore(
            scope = scope,
            serializer = serializer,
            fileProvider = { file },
            dataStoreFactory = { fake },
            clock = { now },
        )
        store.start()
        awaitOpen(store)

        withTimeout(10_000) { store.entries.first { it == listOf(fresh) } }
        withTimeout(10_000) { while (writes.isEmpty()) delay(10) }
        assertEquals(listOf(listOf(fresh)), writes.map { it.entries })
    }

    @Test
    fun `recording the same target keeps a single entry`() = runBlocking {
        val store = fakeStore(RecentData())
        store.start()
        awaitOpen(store)

        assertTrue(store.record(appA))
        now += 1
        assertTrue(store.record(appA))

        val entries = withTimeout(10_000) {
            store.entries.first { it.size == 1 }
        }
        assertEquals(appA, entries.single().target)
        assertEquals(now, entries.single().lastLaunchedAtMillis)
    }

    @Test
    fun `recorded entries are newest first and capped at twenty`() =
        runBlocking {
            val store = fakeStore(RecentData())
            store.start()
            awaitOpen(store)

            repeat(25) { i ->
                assertTrue(store.record(StoredTarget.App("com.app$i/.Main")))
                now += 1
            }

            val entries = withTimeout(10_000) {
                store.entries.first { it.size == RecentRules.MAX_ENTRIES }
            }
            assertEquals(
                "com.app24/.Main",
                (entries.first().target as StoredTarget.App).component,
            )
        }

    @Test
    fun `clear empties the list`() = runBlocking {
        val seeded = RecentData(
            listOf(RecentEntry(appA, now), RecentEntry(appB, now)),
        )
        val store = fakeStore(seeded)
        store.start()
        awaitOpen(store)
        withTimeout(10_000) { store.entries.first { it.size == 2 } }

        assertTrue(store.clear())
        withTimeout(10_000) { store.entries.first { it.isEmpty() } }
        assertEquals(emptyList<RecentEntry>(), store.entries.value)
    }

    @Test
    fun `failed write returns false and keeps the in-memory list`() =
        runBlocking {
            val seeded = RecentData(listOf(RecentEntry(appA, now)))
            val store = fakeStore(seeded, failWrites = true)
            store.start()
            awaitOpen(store)
            withTimeout(10_000) { store.entries.first { it.size == 1 } }

            assertFalse(store.record(appB))
            assertEquals(listOf(appA), store.entries.value.map { it.target })
            assertFalse(store.clear())
            assertEquals(listOf(appA), store.entries.value.map { it.target })
        }

    @Test
    fun `unwritable path starts empty without crashing`() = runBlocking {
        // A non-empty directory can neither be opened as a file nor deleted:
        // the store must still open empty and refuse writes without crashing.
        val dirPath = File(dir, "blocked_recent.json").apply {
            mkdirs()
            File(this, "child").writeText("x")
        }
        val store = RecentStore(
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
            clock = { now },
        )
        store.start()
        awaitOpen(store)

        assertEquals(emptyList<RecentEntry>(), store.entries.value)
        assertFalse(store.record(appA))
        assertTrue(dirPath.isDirectory)
    }
}
