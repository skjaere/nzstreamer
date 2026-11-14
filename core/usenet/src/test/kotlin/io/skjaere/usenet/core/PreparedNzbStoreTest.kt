package io.skjaere.usenet.core

import io.skjaere.compressionutils.EncryptionInfo
import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.NzbMetadataResponse
import io.skjaere.nzbstreamer.metadata.PrepareResult
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.stream.StreamableFile
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreparedNzbStoreTest {
    @TempDir
    lateinit var tmp: File

    private val dir get() = File(tmp, "nzb")

    private val fakeMain = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }.asCoroutineDispatcher()
    private val ioThread = Executors.newSingleThreadExecutor { Thread(it, "fake-io") }
    private val fakeIo = ioThread.asCoroutineDispatcher()

    private fun metadata(password: String? = null) =
        ExtractedMetadata.Raw(NzbMetadataResponse(volumes = emptyList(), obfuscated = false, entries = emptyList()), NzbDocument(emptyList(), password = password))

    private fun nzb(bytes: ByteArray, title: String, password: String? = null) = PreparedNzb(PreparedNzb.idFor(bytes), title, metadata(password), emptyList())

    /** Leaves [bytes] on disk as an earlier process's put would have, last written at [modified]. */
    private fun leftOver(bytes: ByteArray, title: String, modified: Long): String {
        val id = PreparedNzb.idFor(bytes)
        dir.mkdirs()
        File(dir, "$id.title").writeText(title)
        File(dir, "$id.nzb").apply { writeBytes(bytes) }.setLastModified(modified)
        return id
    }

    /** Keeps the io thread busy until the latch is counted down; work sent to [fakeIo] waits until then. */
    private fun holdIo() = CountDownLatch(1).also { gate -> ioThread.execute { gate.await() } }

    @AfterEach
    fun stop() {
        fakeMain.close()
        fakeIo.close()
    }

    @Test
    fun `put from the main thread writes the files on the io dispatcher`() = runBlocking<Unit> {
        val store = PreparedNzbStore(dir, ioDispatcher = fakeIo)
        val bytes = "<nzb/>".toByteArray()
        val nzb = nzb(bytes, "Title")
        val gate = holdIo()
        try {
            val put = launch(fakeMain) { store.put(nzb, bytes) }
            withContext(fakeMain) {} // put has done all it can on fake-main
            assertFalse(File(dir, "${nzb.id}.nzb").exists(), "put wrote the NZB on the calling thread")
            gate.countDown()
            put.join()
        } finally {
            gate.countDown()
        }
        assertContentEquals(bytes, File(dir, "${nzb.id}.nzb").readBytes())
        assertEquals("Title", File(dir, "${nzb.id}.title").readText())
        assertEquals(nzb, store.get(nzb.id))
    }

    @Test
    fun `stored from the main thread reads the files on the io dispatcher`() = runBlocking<Unit> {
        val store = PreparedNzbStore(dir, ioDispatcher = fakeIo)
        val bytes = "<nzb/>".toByteArray()
        val nzb = nzb(bytes, "Title")
        store.put(nzb, bytes)
        val gate = holdIo()
        val read = try {
            val read = async(fakeMain) { store.stored(nzb.id) }
            withContext(fakeMain) {} // stored has done all it can on fake-main
            // Only a read that waits for the io thread sees the title written now.
            File(dir, "${nzb.id}.title").writeText("Renamed")
            gate.countDown()
            read.await()
        } finally {
            gate.countDown()
        }
        assertEquals("Renamed", read?.second, "stored read the NZB on the calling thread")
        assertContentEquals(bytes, read?.first)
    }

    @Test
    fun `a put that can't write throws and leaves the store as it was`() = runBlocking<Unit> {
        val store = PreparedNzbStore(dir, maxEntries = 1)
        val keptBytes = "<a/>".toByteArray()
        val kept = nzb(keptBytes, "A")
        store.put(kept, keptBytes)

        // The directory became unwritable: replaced by a plain file, which holds even for root.
        dir.deleteRecursively()
        dir.writeText("")
        val failedBytes = "<b/>".toByteArray()
        val failed = nzb(failedBytes, "B")
        assertFailsWith<IOException> { store.put(failed, failedBytes) }
        assertNull(store.get(failed.id))
        assertEquals(kept, store.get(kept.id), "a put that failed evicted an entry")

        // Once the directory can be created again, puts work and evict as before.
        dir.delete()
        store.put(failed, failedBytes)
        assertEquals(failed, store.get(failed.id))
        assertNull(store.get(kept.id))
        assertContentEquals(failedBytes, store.stored(failed.id)?.first)
    }

    @Test
    fun `the first use evicts what earlier processes left beyond maxEntries, oldest first`() = runBlocking<Unit> {
        // Written newest first, so the order can only come from the modification times.
        val newest = leftOver("<c/>".toByteArray(), "C", modified = 3_000_000)
        val middle = leftOver("<b/>".toByteArray(), "B", modified = 2_000_000)
        val oldest = leftOver("<a/>".toByteArray(), "A", modified = 1_000_000)
        File(dir, "gone.title").writeText("Gone")
        val store = PreparedNzbStore(dir, maxEntries = 2, ioDispatcher = fakeIo)
        val gate = holdIo()
        val read = try {
            val read = async(fakeMain) { store.stored(newest) }
            withContext(fakeMain) {} // stored has done all it can on fake-main
            assertTrue(File(dir, "$oldest.nzb").exists(), "the earlier entries were evicted off the io dispatcher")
            gate.countDown()
            read.await()
        } finally {
            gate.countDown()
        }
        assertEquals("C", read?.second)
        assertEquals(setOf("$newest.nzb", "$newest.title", "$middle.nzb", "$middle.title"), dir.list()?.toSet())
    }

    @Test
    fun `puts evict the entries earlier processes left before their own`() = runBlocking<Unit> {
        val older = leftOver("<a/>".toByteArray(), "A", modified = 1_000_000)
        val newer = leftOver("<b/>".toByteArray(), "B", modified = 2_000_000)
        val store = PreparedNzbStore(dir, maxEntries = 2)
        val bytes = "<c/>".toByteArray()
        val nzb = nzb(bytes, "C")
        store.put(nzb, bytes)
        assertNull(store.stored(older))
        assertFalse(File(dir, "$older.title").exists())
        assertEquals("B", store.stored(newer)?.second)
        assertEquals(nzb, store.get(nzb.id))
    }

    @Test
    fun `a store over an earlier process's files re-prepares with the archive password`() = runBlocking<Unit> {
        val bytes = "<nzb/>".toByteArray()
        PreparedNzbStore(dir).put(nzb(bytes, "Title", password = "secret"), bytes)

        // A RAR5 archive with encrypted headers only prepares with its password.
        val tried = mutableListOf<String?>()
        val preparer = NzbPreparer(object : PrepareBackend {
            override suspend fun prepare(nzbBytes: ByteArray, password: String?): PrepareResult {
                tried += password
                return if (password == "secret") {
                    PrepareResult.Success(metadata(password))
                } else {
                    PrepareResult.Encrypted("encrypted", EncryptionInfo("a.rar", 0, 0, 15, null, true))
                }
            }
            override suspend fun streamableFiles(metadata: ExtractedMetadata) = listOf(StreamableFile("a.mkv", 10, 0, 0, 0, 0))
        })
        val restarted = PreparedNzbStore(dir)
        val ready = assertIs<PrepareOutcome.Ready>(restarted.getOrPrepare(PreparedNzb.idFor(bytes), preparer))
        assertEquals(listOf<String?>("secret"), tried)
        assertEquals("secret", ready.nzb.metadata.orderedArchiveNzb.password)
    }

    /**
     * Settings › Cache › "NZB files kept for resume": how many are on disk and their size, earlier
     * processes' included, and Clear deletes them (on the io dispatcher), so a resume grabs them again.
     * What this process prepared stays in memory: a play in progress goes on, and those are still
     * dropped from memory in their turn.
     */
    @Test
    fun `the NZBs kept for resume are counted and cleared, and those prepared now play on`() = runBlocking<Unit> {
        val earlier = leftOver("<a/>".toByteArray(), "A", modified = 1_000_000)
        File(dir, "$earlier.pw").writeText("secret")
        val store = PreparedNzbStore(dir, maxEntries = 2, ioDispatcher = fakeIo)
        val bytes = "<nzb>played now</nzb>".toByteArray()
        val nzb = nzb(bytes, "Title")
        store.put(nzb, bytes)
        assertEquals(PreparedNzbStore.StoredFiles(count = 2, bytes = dir.listFiles()!!.sumOf { it.length() }), store.storedFiles())

        val gate = holdIo()
        try {
            val clear = launch(fakeMain) { store.clearStored() }
            withContext(fakeMain) {} // clearStored has done all it can on fake-main
            assertTrue(File(dir, "${nzb.id}.nzb").exists(), "cleared on the calling thread")
            gate.countDown()
            clear.join()
        } finally {
            gate.countDown()
        }
        assertEquals(emptyList(), dir.list()?.toList())
        assertEquals(PreparedNzbStore.StoredFiles(count = 0, bytes = 0), store.storedFiles())
        assertNull(store.stored(earlier))
        assertNull(store.stored(nzb.id))
        assertEquals(nzb, store.get(nzb.id))

        val next = listOf("<b/>", "<c/>").map { it.toByteArray() }.map { b -> nzb(b, "Next").also { store.put(it, b) } }
        assertNull(store.get(nzb.id), "kept in memory past maxEntries")
        assertEquals(next, next.map { store.get(it.id) })
        assertEquals(2, store.storedFiles().count)
    }

    /** A put says whether the store held the NZB before, an earlier process's included; remove lets go of it everywhere. */
    @Test
    fun `a put says whether the NZB is new, and remove takes it out of memory and off disk`() = runBlocking<Unit> {
        val earlier = leftOver("<a/>".toByteArray(), "A", modified = 1_000_000)
        val store = PreparedNzbStore(dir)
        assertFalse(store.put(nzb("<a/>".toByteArray(), "A"), "<a/>".toByteArray()), "an earlier process's is held")
        val bytes = "<b/>".toByteArray()
        val nzb = nzb(bytes, "B", password = "secret")
        assertTrue(store.put(nzb, bytes))
        assertFalse(store.put(nzb, bytes))

        store.remove(nzb.id)
        assertNull(store.get(nzb.id))
        assertNull(store.stored(nzb.id))
        assertEquals(setOf("$earlier.nzb", "$earlier.title"), dir.list()?.toSet())
        assertTrue(store.put(nzb, bytes), "new again")
    }

    @Test
    fun `an archive password is kept only as long as its NZB`() = runBlocking<Unit> {
        File(dir.apply { mkdirs() }, "gone.pw").writeText("old")
        val store = PreparedNzbStore(dir, maxEntries = 1)
        val lockedBytes = "<a/>".toByteArray()
        val locked = nzb(lockedBytes, "A", password = "secret")
        store.put(locked, lockedBytes)
        assertEquals(setOf("${locked.id}.nzb", "${locked.id}.title", "${locked.id}.pw"), dir.list()?.toSet())

        val openBytes = "<b/>".toByteArray()
        val open = nzb(openBytes, "B")
        store.put(open, openBytes)
        assertEquals(setOf("${open.id}.nzb", "${open.id}.title"), dir.list()?.toSet())
    }
}
