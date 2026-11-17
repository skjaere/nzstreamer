package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.debrid.DebridListingStore
import io.skjaere.usenet.app.download.ServiceDownloadWriter.Lost
import io.skjaere.usenet.app.download.ServiceDownloadWriter.Outcome
import io.skjaere.usenet.app.download.ServiceDownloadWriter.Wait
import io.skjaere.usenet.core.DownloadWriter
import io.skjaere.usenet.core.RecordedFile
import io.skjaere.usenet.core.StreamTraffic
import io.skjaere.usenet.data.Clock
import io.skjaere.usenet.debrid.DebridService
import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.Prepared
import io.skjaere.usenet.debrid.ServiceError
import io.skjaere.usenet.debrid.ServiceException
import io.skjaere.usenet.debrid.ServiceFile
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.debrid.ServiceLink
import io.skjaere.usenet.debrid.ServiceListing
import io.skjaere.usenet.logging.LogBuffer
import io.skjaere.usenet.player.HttpLink
import io.skjaere.usenet.player.LinkFailure
import io.skjaere.usenet.player.LinkResolver
import io.skjaere.usenet.player.LinkUnavailable
import io.skjaere.usenet.player.PlayUris
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The debrid download's writer (L419, L424, L471): a file of 1.5 MB from its listing's copy, written through fake reads
 * standing in for Media3's HTTP source (each open asks the writer's link resolver first, as a ServiceDataSource does, then
 * answers as its script says) and fake service accounts, on virtual time: the waits before a reopen, the hour out of
 * reach, the throttle and W123's 10 s of quiet. A test's own StreamTraffic is the signal a play's or a cast's meters
 * feed. Where a read must wait on the CDN for real (a cancel, a stream that starts meanwhile), it blocks on its own thread
 * until it is aborted. The release, its files, the link and the key are invented.
 */
@OptIn(ExperimentalCoroutinesApi::class) // The test scheduler's clock.
class ServiceDownloadWriterTest {
    private val data = Random(19).nextBytes(1_540_321)
    private val size = data.size.toLong()
    private val dir = createTempDirectory("service-download-writer").toFile()
    private val path = File(dir, "download.part")
    private val traffic = StreamTraffic()
    private val network = MutableStateFlow(true)
    private val listingsFile = File(dir, "listings.json")
    private val listings = DebridListingStore(listingsFile, io = Dispatchers.Unconfined)

    /** The record's offsets, the restart's mark and the listing's copies, in the order they came. */
    private val events = CopyOnWriteArrayList<String>()
    private val records = CopyOnWriteArrayList<Long>()
    private val opens = CopyOnWriteArrayList<Long>()
    private val folder = FakeFolder()
    private val reads = FakeReads()

    /** The file in its listing on account A, after its info file; on B, first, and a sample after it. */
    private val fileA = ServiceFile(1, PATH, size, "71")
    private val listingA = ServiceListing(listOf(ServiceFile(0, NFO, 4_096, "70"), fileA))
    private val fileB = ServiceFile(0, PATH, size, "31")
    private val listingB = ServiceListing(listOf(fileB, ServiceFile(1, SAMPLE, 50_000, "32")))
    private val entry = DebridListingStore.Entry(DebridListingStore.idFor(ACCOUNT_A, HASH), ACCOUNT_A, TITLE, Prepared(HASH, listingA, fileA, handle = "4101"))
    private val a = FakeService(ACCOUNT_A, listingA, handle = "4101")
    private val b = FakeService(ACCOUNT_B, listingB, handle = "5202")

    /** The accounts switched off or signed out of: the gated lookup gives none. */
    private val gone = ConcurrentHashMap.newKeySet<String>()

    /**
     * The accounts whose key was refused since it was saved, as the registry marks one at the first refusal any call
     * meets (a [FakeService]'s too): still saved and switched on, but the gated lookup gives none for them.
     */
    private val refused = ConcurrentHashMap.newKeySet<String>()

    /** Each account the gated lookup was asked for. */
    private val lookups = CopyOnWriteArrayList<String>()

    @AfterEach
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun writer(
        io: CoroutineDispatcher,
        time: TimeSource,
        now: () -> Long,
        on: List<String> = listOf(ACCOUNT_A),
        services: Map<String, FakeService> = mapOf(ACCOUNT_A to a, ACCOUNT_B to b),
        shown: () -> Boolean = { true },
        httpsOnly: Boolean = true,
        throttle: Long? = null,
        letGo: kotlin.time.Duration = ServiceDownloadWriter.LET_GO,
    ) = ServiceDownloadWriter(
        reads,
        accounts = { id ->
            lookups += id
            services[id]?.takeIf { id !in gone && id !in refused }
        },
        keyRefused = { id -> id in refused && id !in gone },
        listings = listings,
        accountsOn = { on.filter { it !in gone && it !in refused } },
        streaming = traffic.streaming,
        folder = folder,
        network = network,
        clock = Clock { EPOCH + now() },
        dispatcher = io,
        throttle = { throttle },
        shown = shown,
        httpsOnly = { httpsOnly },
        timeSource = time,
        letGo = letGo,
    )

    /** The writer on the test's virtual time, its reads on the test's dispatcher unless [io] says another. */
    private fun TestScope.writer(
        on: List<String> = listOf(ACCOUNT_A),
        services: Map<String, FakeService> = mapOf(ACCOUNT_A to a, ACCOUNT_B to b),
        shown: () -> Boolean = { true },
        httpsOnly: Boolean = true,
        throttle: Long? = null,
        io: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ) = writer(io, testScheduler.timeSource, { testScheduler.currentTime }, on, services, shown, httpsOnly, throttle)

    /** Writes [entry]'s file to [path] from [from], each open's offset in [opens] and each record in [records] and [events]. */
    private suspend fun ServiceDownloadWriter.wrote(
        from: Long = 0,
        onOpen: (Long) -> Unit = {},
        channel: (FileChannel) -> FileChannel = { it },
    ): Outcome = channel(FileChannel.open(path.toPath(), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)).use { out ->
        write(ID, entry, fileA, out, from, onOpen = { offset ->
            opens += offset
            onOpen(offset)
        }) { offset ->
            records += offset
            events += "record $offset"
        }
    }

    /** Everything a case changed, for another round of it. */
    private fun reset() {
        listOf(events, records, opens, lookups, reads.made, a.links, a.picks, b.links, b.picks).forEach { it.clear() }
        folder.copies.clear()
        folder.marked.clear()
        gone.clear()
        refused.clear()
        a.failing = null
        a.again = null
        b.prepareFailing = null
        b.onPrepare = {}
        reads.reads.set(0)
        reads.blocked = CountDownLatch(1)
        path.delete()
    }

    @Test
    fun `a resume opens at the safe offset, and the bytes run unbroken across a cut and an early end`() = runTest(timeout = 60.seconds) {
        path.writeBytes(data.copyOf(300_000))
        reads.script(Script(cutAt = 400_000), Script(endAt = 200_000))
        val writer = writer()
        val lives = CopyOnWriteArrayList<DownloadWriter.Live?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { writer.live.collect { lives += it } }

        assertEquals(Outcome.Done, writer.wrote(from = 300_000))
        assertEquals(listOf(300_000L, 700_000L, 900_000L), opens)
        assertContentEquals(data, path.readBytes())
        // Each reopen at what was recorded before it, each after a wait of 2 s, the progress before it starting it again.
        assertEquals(listOf(700_000L, 900_000L, size), records)
        assertEquals(4_000, currentTime)
        assertEquals(3, a.links.size)
        val said = lives.filterNotNull()
        assertTrue(said.all { it.id == ID && !it.unreachable && !it.streamWait }, said.toString())
        assertEquals(size, said.last().written)
        assertNull(writer.live.value)
    }

    /**
     * A file written whole already (its last record at its end, and its rename or its Done not reached: a volume gone, a
     * kill) is done at once: nothing is asked of its account and nothing read, while something streams too.
     */
    @Test
    fun `a write from the file's end is done at once, with no lookup, link or read`() = runTest(timeout = 60.seconds) {
        path.writeBytes(data)
        assertEquals(Outcome.Done, writer().wrote(from = size))
        assertTrue(lookups.isEmpty() && a.links.isEmpty() && reads.made.isEmpty() && opens.isEmpty(), "$lookups ${a.links} $opens")
        assertTrue(records.isEmpty(), "already recorded at its end: $records")
        assertContentEquals(data, path.readBytes())
        // Something streaming holds nothing: there is nothing to read.
        val meter = traffic.open()
        assertEquals(Outcome.Done, writer().wrote(from = size))
        meter.close()
        assertTrue(lookups.isEmpty() && reads.made.isEmpty())
        assertEquals(0, currentTime)
    }

    @Test
    fun `one read made and one link asked per open, each made once the one before it is let go`() = runTest(timeout = 60.seconds) {
        reads.script(Script(cutAt = 100_000), Script(cutAt = 100_000), Script(refuse = LinkFailure.NoWorkingLink))
        assertEquals(Outcome.Done, writer().wrote())
        assertEquals(listOf(0L, 100_000L, 200_000L, 200_000L), opens)
        assertEquals(4, reads.made.size)
        assertEquals(4, a.links.size)
        // A fresh link each, from the row's own listing.
        assertEquals(4, reads.made.map { it.link?.url }.distinct().size)
        assertTrue(a.links.all { (prepared, file) -> prepared == entry.prepared && file == fileA })
        assertEquals(1, reads.mostOpen, "never two reads open at once")
        assertTrue(reads.made.all { it.closed.get() })
        // A read not read to its end is aborted before it is closed, so a close never reads the rest of its body.
        assertEquals(listOf(true, true, true, false), reads.made.map { it.aborted.get() })
        // Its URI names the listing and the file, never a link.
        assertTrue(reads.made.all { it.uri == PlayUris.service(entry.id, 1) })
    }

    @Test
    fun `an answer of another length or another total is no working link, as is a byte past the size where it says neither`() = runTest(timeout = 60.seconds) {
        reads.script(
            Script(length = { size - it + 1 }),
            Script(total = { size + 1 }),
            // Neither said, and a byte past the size: another, longer file. Its bytes are written again by the next.
            Script(length = { null }, total = { null }, past = 1),
            Script(length = { null }, total = { null }),
        )
        assertEquals(Outcome.Done, writer().wrote())
        assertEquals(listOf(0L, 0L, 0L, 0L), opens)
        assertContentEquals(data, path.readBytes())
        // None made progress: a backoff of 2, 4 and 8 s.
        assertEquals(14_000, currentTime)
        assertEquals(size, records.last())
    }

    @Test
    fun `a link that isn't HTTPS is no working link and never read, but where the endpoints point at the fakes`() = runTest(timeout = 60.seconds) {
        a.scheme = "http"
        assertEquals(Outcome.Failed(DownloadHostRules.SERVICE_KEPT_FAILING), writer().wrote())
        assertEquals(7, a.links.size)
        assertTrue(reads.made.isEmpty())
        reset()
        assertEquals(Outcome.Done, writer(httpsOnly = false).wrote())
        assertContentEquals(data, path.readBytes())
    }

    @Test
    fun `a server that won't resume starts the file again from 0 once, its mark first, and a second time fails it`() = runTest(timeout = 60.seconds) {
        reads.script(Script(cutAt = 500_000), Script(refuse = LinkFailure.NoRanges), Script(cutAt = 700_000), Script(refuse = LinkFailure.NoRanges))
        assertEquals(Outcome.Failed(DownloadHostRules.CANT_RESUME), writer().wrote())
        assertEquals(listOf(0L, 500_000L, 0L, 700_000L), opens)
        assertEquals(setOf(ID), folder.marked)
        // The mark before the record went back to 0, so no later process starts it again a second time.
        assertEquals(listOf("record 500000", "mark", "record 0", "record 700000"), events)
        // The restart opens again at once; each cut waits 2 s.
        assertEquals(4_000, currentTime)
        assertContentEquals(data.copyOf(700_000), path.readBytes().copyOf(700_000))
    }

    @Test
    fun `a mark an earlier process left fails it at the first answer from another part, and with nothing written that answer is no working link`() = runTest(timeout = 60.seconds) {
        folder.marked += ID
        path.writeBytes(data.copyOf(300_000))
        reads.script(Script(refuse = LinkFailure.NoRanges))
        assertEquals(Outcome.Failed(DownloadHostRules.CANT_RESUME), writer().wrote(from = 300_000))
        assertEquals(listOf(300_000L), opens)
        assertEquals(emptyList(), events)
        assertEquals(0, currentTime)

        // Nothing written, no mark: nothing to start again from, so it counts as a broken answer.
        reset()
        reads.script(*Array(7) { Script(refuse = LinkFailure.NoRanges) })
        assertEquals(Outcome.Failed(DownloadHostRules.SERVICE_KEPT_FAILING), writer().wrote())
        assertEquals(List(7) { 0L }, opens)
        assertTrue(folder.marked.isEmpty())
    }

    @Test
    fun `six reopens in a row with no progress fail it, and one with progress starts the count again`() = runTest(timeout = 60.seconds) {
        val none = LinkFailure.NoWorkingLink
        reads.script(
            // The first open's failure is no reopen; then two that brought nothing.
            Script(refuse = none), Script(cutAt = 0), Script(endAt = 0),
            Script(cutAt = 100_000),
            Script(refuse = none), Script(cutAt = 0), Script(refuse = none), Script(endAt = 0), Script(refuse = none), Script(refuse = none),
        )
        assertEquals(Outcome.Failed(DownloadHostRules.SERVICE_KEPT_FAILING), writer().wrote())
        assertEquals(10, opens.size)
        // What the one with progress brought, recorded at its cut (after the 10 s cadence's first record within it).
        assertEquals(listOf(READ.toLong(), 100_000L), records)
    }

    @Test
    fun `an open that reaches nothing says so in its live and counts toward an hour alone, the service unanswered too`() = runTest(timeout = 60.seconds) {
        val writer = writer()
        val lives = CopyOnWriteArrayList<DownloadWriter.Live?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { writer.live.collect { lives += it } }
        // Each third open the service's link request goes unanswered; the others' connections are refused.
        val outcome = writer.wrote(onOpen = {
            if (opens.size % 3 == 0) {
                a.failing = ServiceError.Down
            } else {
                a.failing = null
                reads.script(Script(unreached = true))
            }
        })
        assertEquals(Outcome.Waiting(Wait.Unreachable), outcome)
        assertEquals(DownloadHostRules.CANT_REACH_SERVICE, (outcome as Outcome.Waiting).wait.reason)
        assertTrue(currentTime in 1.hours.inWholeMilliseconds..65.minutes.inWholeMilliseconds, "$currentTime")
        // Far more than six with no progress: none counted.
        assertTrue(opens.size > 7, "${opens.size}")
        assertTrue(lives.any { it?.unreachable == true })
    }

    @Test
    fun `on a network that isn't validated nothing counts, and its return opens it again at once`() = runTest(timeout = 60.seconds) {
        network.value = false
        var reach = false
        val writer = writer()
        val lives = CopyOnWriteArrayList<DownloadWriter.Live?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { writer.live.collect { lives += it } }
        val write = async { writer.wrote(onOpen = { if (!reach) reads.script(Script(unreached = true)) }) }
        advanceTimeBy(2.hours)
        assertFalse(write.isCompleted)
        assertTrue(lives.filterNotNull().none { it.unreachable })
        val at = currentTime
        reach = true
        network.value = true
        assertEquals(Outcome.Done, write.await())
        assertEquals(at, currentTime)
        assertContentEquals(data, path.readBytes())
    }

    @Test
    fun `a limit ends the write with the account and when its gate opens, and a refused key waits for a new one`() = runTest(timeout = 60.seconds) {
        reads.script(Script(cutAt = 250_000))
        val limit = writer().wrote(onOpen = { offset -> if (offset > 0) a.failing = ServiceError.Limit(Duration.ofMinutes(3)) })
        // After the cut's wait of 2 s: its gate's end, 3 minutes on, what was written recorded first.
        assertEquals(Outcome.Waiting(Wait.Limit(ACCOUNT_A, EPOCH + 2_000 + 180_000)), limit)
        assertEquals(DownloadHostRules.SERVICE_LIMIT, (limit as Outcome.Waiting).wait.reason)
        assertFalse(ACCOUNT_A in limit.toString(), "no account in its words")
        assertEquals(listOf(250_000L), records)
        assertEquals(1, reads.made.size, "no read for a link refused")

        // One that named no wait: the gate's hour.
        a.failing = ServiceError.Limit()
        assertEquals(Outcome.Waiting(Wait.Limit(ACCOUNT_A, EPOCH + currentTime + 3_600_000)), writer().wrote(from = 250_000))
        // One a dead link's refresh met, within an open.
        a.failing = null
        reads.script(Script(refuse = LinkFailure.Limit(120_000)))
        assertEquals(Outcome.Waiting(Wait.Limit(ACCOUNT_A, EPOCH + currentTime + 120_000)), writer().wrote(from = 250_000))

        a.failing = ServiceError.KeyRefused
        val key = writer().wrote(from = 250_000)
        assertEquals(Outcome.Waiting(Wait.KeyRefused), key)
        assertEquals(DownloadHostRules.CHECK_SERVICE, (key as Outcome.Waiting).wait.reason)
        // A new key saved, and the CDN's read refused it.
        a.failing = null
        refused.clear()
        reads.script(Script(refuse = LinkFailure.KeyRefused(ServiceKind.TorBox.displayName)))
        assertEquals(Outcome.Waiting(Wait.KeyRefused), writer().wrote(from = 250_000))
    }

    /**
     * L474: a refused key leaves its account saved and switched on, but the gated lookup gives none for it from then on, as
     * the registry marks the key at the first refusal any call meets. The download waits for a new key at its offset,
     * after its own refusal or a play's, and a Resume before one is saved waits again: its torrent is never moved to
     * another account, nor its release given up (L424 is for an account switched off or signed out of).
     */
    @Test
    fun `a refused key waits for a new one at its offset, though the lookup gives none for its account from then on`() = runTest(timeout = 60.seconds) {
        val on = listOf(ACCOUNT_A, ACCOUNT_B)
        // Its own reopen's link refused: the registry marks the key.
        reads.script(Script(cutAt = 400_000))
        assertEquals(Outcome.Waiting(Wait.KeyRefused), writer(on = on).wrote(onOpen = { offset -> if (offset > 0) a.failing = ServiceError.KeyRefused }))
        assertEquals(setOf(ACCOUNT_A), refused)
        assertEquals(listOf(400_000L), records)

        // Resume before a new key is saved: the lookup gives none, and it waits again, asking nothing of either account.
        a.failing = null
        assertEquals(Outcome.Waiting(Wait.KeyRefused), writer(on = on).wrote(from = 400_000))
        assertEquals(2, a.links.size)
        assertEquals(1, reads.made.size)
        assertTrue(b.picks.isEmpty() && b.links.isEmpty() && folder.copies.isEmpty())
        assertEquals(listOf(400_000L), records)

        // A play meets the refusal while the download writes: its next open waits too, what came before recorded.
        reset()
        reads.script(Script(cutAt = 600_000, opened = { refused += ACCOUNT_A }))
        assertEquals(Outcome.Waiting(Wait.KeyRefused), writer(on = on).wrote())
        assertEquals(listOf(600_000L), records)
        assertEquals(1, a.links.size)
        assertTrue(b.picks.isEmpty() && b.links.isEmpty() && folder.copies.isEmpty())
    }

    @Test
    fun `storage with no room fails it as storage full, what came before kept and recorded`() = runTest(timeout = 60.seconds) {
        for ((words, reason) in listOf(
            "No space left on device" to RecordedFile.STORAGE_FULL, "File too large" to RecordedFile.STORAGE_FULL, "Input/output error" to RecordedFile.WRITE_FAILED,
        )) {
            reset()
            assertEquals(Outcome.Failed(reason), writer().wrote(channel = { FullAt(it, 600_000, words) }), words)
            assertEquals(listOf(600_000L), records, words)
            assertContentEquals(data.copyOf(600_000), path.readBytes().copyOf(600_000), words)
            assertEquals(1, reads.made.size, "no reopen: $words")
            assertTrue(reads.made.single().closed.get())
        }
    }

    @Test
    fun `a torrent lost on its account goes on from the safe offset on the next that lists its file at the same size, its listing kept as the row's`() = runTest(timeout = 60.seconds) {
        val losses = listOf<Pair<String, () -> Unit>>(
            "not cached" to { a.failing = ServiceError.NotCached },
            "removed" to { a.failing = ServiceError.Removed },
            "refused" to { a.failing = ServiceError.Refused(tooLarge = true) },
            "signed out of" to { gone += ACCOUNT_A },
        )
        val moved = DebridListingStore.Entry(DebridListingStore.idFor(ACCOUNT_B, HASH), ACCOUNT_B, TITLE, Prepared(HASH, listingB, fileB, handle = "5202"))
        for ((what, lose) in losses) {
            reset()
            val at = currentTime
            reads.script(Script(cutAt = 400_000, opened = lose))
            assertEquals(Outcome.Done, writer(on = listOf(ACCOUNT_A, ACCOUNT_B)).wrote(), what)
            assertContentEquals(data, path.readBytes(), what)
            assertEquals(setOf(0L, 400_000L), opens.toSet(), what)
            // B lists it first, its sample after it: picked by its path and size, before anything is added there.
            assertEquals(listOf<ServiceFile?>(fileB), b.picks, what)
            assertEquals(listOf(moved), folder.copies, what)
            assertEquals(moved, listings.get(moved.id), what)
            // Its link from B, for B's own file, the read naming B's listing; from the safe offset, at once.
            assertEquals(listOf(moved.prepared to fileB), b.links, what)
            assertEquals(PlayUris.service(moved.id, 0), reads.made.last().uri, what)
            assertEquals(listOf(400_000L, size), records, what)
            assertEquals(2_000, currentTime - at, what)
        }
    }

    @Test
    fun `with no other account that lists the file at its size the release is given up, nothing added there`() = runTest(timeout = 60.seconds) {
        // B lists it at another size: its pick is none, so nothing is added.
        val other = FakeService(ACCOUNT_B, ServiceListing(listOf(ServiceFile(0, PATH, size + 1, "31"))), handle = "5202")
        reads.script(Script(cutAt = 400_000, opened = { a.failing = ServiceError.NotCached }))
        val services = mapOf(ACCOUNT_A to a, ACCOUNT_B to other)
        assertEquals(Outcome.NextRelease(Lost.NotCached), writer(on = listOf(ACCOUNT_A, ACCOUNT_B), services = services).wrote())
        assertEquals(listOf<ServiceFile?>(null), other.picks)
        assertTrue(other.links.isEmpty())
        assertTrue(folder.copies.isEmpty())
        assertEquals(listOf(400_000L), records)

        // The next account's own failure passes it over too.
        reset()
        b.prepareFailing = ServiceError.Down
        reads.script(Script(cutAt = 400_000, opened = { a.failing = ServiceError.Removed }))
        assertEquals(Outcome.NextRelease(Lost.Removed), writer(on = listOf(ACCOUNT_A, ACCOUNT_B)).wrote())
        assertTrue(b.links.isEmpty())

        // Signed out of, with no other on: nothing asked of it again.
        reset()
        reads.script(Script(cutAt = 400_000, opened = { gone += ACCOUNT_A }))
        assertEquals(Outcome.NextRelease(Lost.AccountGone), writer().wrote())
        assertEquals(1, a.links.size)
    }

    @Test
    fun `a torrent the account added again for a link is kept in the row's copy and the shared store under the same listing id`() = runTest(timeout = 60.seconds) {
        val again = Prepared(HASH, ServiceListing(listingA.files.map { it.copy(id = "9${it.id}") }), fileA.copy(id = "971"), handle = "4102")
        reads.script(Script(cutAt = 300_000), Script(cutAt = 300_000))
        assertEquals(Outcome.Done, writer().wrote(onOpen = { offset -> if (offset == 300_000L) a.again = again }))
        assertContentEquals(data, path.readBytes())
        val kept = DebridListingStore.Entry(entry.id, ACCOUNT_A, TITLE, again)
        assertEquals(listOf(kept), folder.copies)
        assertEquals(kept, listings.get(entry.id))
        // Written as a play's is, so a process after this one finds it too.
        assertEquals(kept, DebridListingStore(listingsFile).get(entry.id))
        // The next link asked by the new handle, for the file as the new listing has it; the reads under the same id.
        assertEquals(listOf("4101", "4101", "4102"), a.links.map { it.first.handle })
        assertEquals(fileA.copy(id = "971"), a.links.last().second)
        assertTrue(reads.made.all { it.uri == PlayUris.service(entry.id, 1) })
    }

    @Test
    fun `debrid hidden fails it with no call, at its first open or a reopen`() = runTest(timeout = 60.seconds) {
        assertEquals(Outcome.Failed(DownloadHostRules.NOT_IN_THIS_VERSION), writer(shown = { false }).wrote())
        assertTrue(lookups.isEmpty() && a.links.isEmpty() && reads.made.isEmpty() && opens.isEmpty())

        reset()
        var shown = true
        reads.script(Script(cutAt = 200_000, opened = { shown = false }))
        assertEquals(Outcome.Failed(DownloadHostRules.NOT_IN_THIS_VERSION), writer(shown = { shown }).wrote())
        assertEquals(listOf(ACCOUNT_A), lookups)
        assertEquals(1, a.links.size)
        assertEquals(listOf(200_000L), records)
    }

    @Test
    fun `a cancel aborts the read under way from another thread and records what was forced`() = runBlocking<Unit> {
        reads.script(Script(blockAt = 500_000))
        val writer = writer(Dispatchers.IO, TimeSource.Monotonic, { 0L })
        val write = launch(Dispatchers.Default) { writer.wrote() }
        assertTrue(withContext(Dispatchers.IO) { reads.blocked.await(10, TimeUnit.SECONDS) }, "the read waiting on the CDN")
        withTimeout(10_000) { write.cancelAndJoin() }
        val read = reads.made.single()
        assertTrue(read.aborted.get())
        assertTrue(read.closed.get())
        assertEquals(listOf(500_000L), records)
        assertTrue(write.isCancelled)
        assertNull(writer.live.value)
    }

    @Test
    fun `the debug throttle paces its bytes`() = runTest(timeout = 60.seconds) {
        assertEquals(Outcome.Done, writer(throttle = 500_000).wrote())
        assertTrue(currentTime in size * 1_000 / 500_000 - 1..size * 1_000 / 500_000 + 1, "$currentTime")
        reset()
        val at = currentTime
        assertEquals(Outcome.Done, writer().wrote())
        assertEquals(at, currentTime)
    }

    /**
     * W123 (L471): a meter opened as the fifth read comes (a play's or a cast's stream starting) ends the reads with that
     * read: its bytes are written and recorded, the body let go, the live says so. However long it streams, nothing is
     * asked; a stream after it within 10 s keeps it waiting; 10 s with nothing streaming, it goes on at the safe offset
     * with one fresh link.
     */
    @Test
    fun `a stream that starts stops the reads within one read, the body closed at once and what was written recorded, its live saying so`() = runTest(timeout = 60.seconds) {
        var meter: StreamTraffic.Meter? = null
        reads.script(Script(onRead = { count -> if (count == 5) meter = traffic.open() }))
        val writer = writer()
        val openedAt = CopyOnWriteArrayList<Long>()
        val write = async { writer.wrote(onOpen = { openedAt += currentTime }) }
        runCurrent()
        assertEquals(5, reads.reads.get(), "no read after the one under way")
        val first = reads.made.single()
        assertTrue(first.aborted.get() && first.closed.get(), "the body closed at once")
        assertFalse(first.abortedInRead.get(), "never aborted while it read")
        // The bytes of the read the stream started during are left, to be read again after the wait.
        assertEquals(listOf(4L * READ), records)
        assertEquals(DownloadWriter.Live(ID, 4L * READ, unreachable = false, streamWait = true), writer.live.value)

        advanceTimeBy(30.minutes)
        assertEquals(1, a.links.size, "no link while it streams")
        assertEquals(1, reads.made.size)
        meter!!.close()
        advanceTimeBy(9.seconds)
        val second = traffic.open()
        advanceTimeBy(5.seconds)
        second.close()
        val quiet = currentTime
        advanceTimeBy(9_999.milliseconds)
        assertEquals(1, a.links.size, "a stream within 10 s keeps it waiting")
        advanceTimeBy(2.milliseconds)
        runCurrent()

        assertEquals(Outcome.Done, write.await())
        assertEquals(listOf(0L, 4L * READ), opens)
        assertEquals(quiet + 10_000, openedAt.last())
        assertEquals(2, a.links.size, "one link per stream start")
        assertContentEquals(data, path.readBytes())
    }

    @Test
    fun `a stream under way when the write starts holds its first open, with no link asked`() = runTest(timeout = 60.seconds) {
        val meter = traffic.open()
        val writer = writer()
        val write = async { writer.wrote() }
        advanceTimeBy(1.hours)
        assertTrue(lookups.isEmpty() && a.links.isEmpty() && reads.made.isEmpty())
        assertEquals(true, writer.live.value?.streamWait)
        meter.close()
        advanceTimeBy(10_001.milliseconds)
        assertEquals(Outcome.Done, write.await())
        assertEquals(1, a.links.size)
    }

    /** A read still waiting on the CDN after the stop's moment ([ServiceDownloadWriter.LET_GO]) is aborted from another thread. */
    @Test
    fun `a read waiting on the CDN as a stream starts is aborted from another thread`() = runTest(timeout = 60.seconds) {
        reads.script(Script(blockAt = 300_000))
        val writer = writer(io = Dispatchers.IO)
        val write = async { writer.wrote() }
        assertTrue(withContext(Dispatchers.IO) { reads.blocked.await(10, TimeUnit.SECONDS) }, "the read waiting on the CDN")
        val meter = traffic.open()
        assertTrue(withContext(Dispatchers.IO) { reads.made.single().closedLatch.await(10, TimeUnit.SECONDS) }, "the body closed at once")
        assertTrue(reads.made.single().abortedInRead.get())
        runCurrent()
        assertEquals(listOf(300_000L), records)
        assertEquals(true, writer.live.value?.streamWait)
        meter.close()
        advanceTimeBy(10_001.milliseconds)
        assertEquals(Outcome.Done, write.await())
        assertEquals(listOf(0L, 300_000L), opens)
        assertContentEquals(data, path.readBytes())
    }

    /**
     * A read under way as a stream starts is given a moment to come back (here a long one, so the case never leans on a
     * machine's pace): one from a body still flowing comes back, and only then is its body let go, so no other thread
     * meets its HTTP stack's buffers while it reads.
     */
    @Test
    fun `a read still flowing as a stream starts comes back before its body is let go`() = runBlocking<Unit> {
        val streamed = CountDownLatch(1)
        val flowing = CountDownLatch(1)
        var meter: StreamTraffic.Meter? = null
        reads.script(
            Script(onRead = { count ->
                if (count == 3) {
                    meter = traffic.open()
                    streamed.countDown()
                    // Still flowing: it comes back once the case lets it.
                    flowing.await(10, TimeUnit.SECONDS)
                }
            }),
        )
        val writer = writer(Dispatchers.IO, TimeSource.Monotonic, { 0L }, letGo = 30.seconds)
        val write = launch(Dispatchers.Default) { writer.wrote() }
        try {
            assertTrue(withContext(Dispatchers.IO) { streamed.await(10, TimeUnit.SECONDS) }, "the stream started during a read")
            flowing.countDown()
            withTimeout(10_000) { writer.live.first { it?.streamWait == true } }
            val read = reads.made.single()
            assertFalse(read.abortedInRead.get(), "never aborted while it read")
            assertTrue(read.aborted.get() && read.closed.get(), "its body let go once it came back")
            // The byte reached before that read, or after it where it came back before the stop was seen.
            val at = records.single()
            assertTrue(at == 2L * READ || at == 3L * READ, "$records")
            assertContentEquals(data.copyOf(at.toInt()), path.readBytes().copyOf(at.toInt()))
        } finally {
            withTimeout(10_000) { write.cancelAndJoin() }
            meter?.close()
        }
    }

    /**
     * Whatever a read the stop aborted comes back with is never taken (here bytes that aren't the file's, as a read an
     * abort's close met in the HTTP stack's buffers may give): the file goes on, whole, from the byte reached before it.
     */
    @Test
    fun `bytes a read comes back with once a stream's stop aborted it are never taken`() = runTest(timeout = 60.seconds) {
        reads.script(Script(blockAt = 300_000, afterAbort = AfterAbort.Garbles))
        val writer = writer(io = Dispatchers.IO)
        val write = async { writer.wrote() }
        assertTrue(withContext(Dispatchers.IO) { reads.blocked.await(10, TimeUnit.SECONDS) }, "the read waiting on the CDN")
        val meter = traffic.open()
        assertTrue(withContext(Dispatchers.IO) { reads.made.single().closedLatch.await(10, TimeUnit.SECONDS) }, "its body let go")
        assertTrue(reads.made.single().abortedInRead.get())
        runCurrent()
        assertEquals(listOf(300_000L), records)
        meter.close()
        advanceTimeBy(10_001.milliseconds)
        assertEquals(Outcome.Done, write.await())
        assertEquals(listOf(0L, 300_000L), opens)
        assertContentEquals(data, path.readBytes())
    }

    /** A read the stop's abort ends (-1, as a ServiceDataSource read after its abort gives) is a wait, not a body ended short. */
    @Test
    fun `a read a stream's stop ends is a wait, never a failure counted`() = runTest(timeout = 60.seconds) {
        // Five reopens counted with nothing; then a body whose first read waits on the CDN as a stream starts.
        reads.script(*Array(6) { Script(refuse = LinkFailure.NoWorkingLink) }, Script(blockAt = 0, afterAbort = AfterAbort.Ends))
        val writer = writer(io = Dispatchers.IO)
        val write = async { writer.wrote() }
        assertTrue(withContext(Dispatchers.IO) { reads.blocked.await(10, TimeUnit.SECONDS) }, "the read waiting on the CDN")
        val meter = traffic.open()
        assertTrue(withContext(Dispatchers.IO) { reads.made.last().closedLatch.await(10, TimeUnit.SECONDS) }, "its body let go")
        runCurrent()
        assertFalse(write.isCompleted, "a wait, not a sixth failure")
        assertEquals(true, writer.live.value?.streamWait)
        meter.close()
        advanceTimeBy(10_001.milliseconds)
        assertEquals(Outcome.Done, write.await())
        assertContentEquals(data, path.readBytes())
    }

    /**
     * What a source throws besides an IOException (Media3's, or its HTTP stack's own state, as okio's "closed") is a cut,
     * counted, mid-body or at an open, and once a stream's stop came, a wait: it never leaves the write unsorted.
     */
    @Test
    fun `what a source throws besides an IOException is a cut, mid-body or at an open, and after a stream's stop a wait`() = runTest(timeout = 60.seconds) {
        reads.script(Script(breakAt = 300_000), Script(openBreaks = true))
        assertEquals(Outcome.Done, writer().wrote())
        assertEquals(listOf(0L, 300_000L, 300_000L), opens)
        assertEquals(listOf(300_000L, size), records)
        // After the first, with progress, 2 s; after the second, with none, 4 s.
        assertEquals(6_000, currentTime)
        assertContentEquals(data, path.readBytes())

        reset()
        reads.script(Script(blockAt = 300_000, afterAbort = AfterAbort.Breaks))
        val writer = writer(io = Dispatchers.IO)
        val write = async { writer.wrote() }
        assertTrue(withContext(Dispatchers.IO) { reads.blocked.await(10, TimeUnit.SECONDS) }, "the read waiting on the CDN")
        val meter = traffic.open()
        assertTrue(withContext(Dispatchers.IO) { reads.made.single().closedLatch.await(10, TimeUnit.SECONDS) }, "its body let go")
        runCurrent()
        assertEquals(listOf(300_000L), records)
        assertEquals(true, writer.live.value?.streamWait)
        meter.close()
        advanceTimeBy(10_001.milliseconds)
        assertEquals(Outcome.Done, write.await())
        assertContentEquals(data, path.readBytes())
    }

    /**
     * L471 between bodies: a stream that starts while the write waits to open again ends that wait for its own, the live
     * saying so in place of the service out of reach, and the next open comes 10 s after it ends. One that came and went
     * while the next account prepared the torrent holds the open after it as long.
     */
    @Test
    fun `a stream that starts between bodies holds the next open until 10 s after it ends, its live saying it waits`() = runTest(timeout = 60.seconds) {
        reads.script(Script(unreached = true))
        val writer = writer()
        val openedAt = CopyOnWriteArrayList<Long>()
        val write = async { writer.wrote(onOpen = { openedAt += currentTime }) }
        // The open reaches nothing: 2 s before the next, the live saying the service is out of reach.
        advanceTimeBy(1_000)
        assertEquals(true, writer.live.value?.unreachable)
        val meter = traffic.open()
        advanceTimeBy(500)
        assertEquals(DownloadWriter.Live(ID, 0, unreachable = false, streamWait = true), writer.live.value)
        meter.close()
        val ended = currentTime
        advanceTimeBy(9_999)
        assertEquals(1, openedAt.size, "no open within 10 s of the stream's end")
        assertEquals(Outcome.Done, write.await())
        assertEquals(listOf(0L, ended + 10_000), openedAt)

        reset()
        var closed = 0L
        b.onPrepare = {
            val during = traffic.open()
            delay(500)
            during.close()
            closed = currentTime
        }
        reads.script(Script(cutAt = 400_000, opened = { a.failing = ServiceError.NotCached }))
        val opened = CopyOnWriteArrayList<Long>()
        assertEquals(Outcome.Done, writer(on = listOf(ACCOUNT_A, ACCOUNT_B)).wrote(onOpen = { opened += currentTime }))
        assertEquals(closed + 10_000, opened.last(), "$opened")
        assertContentEquals(data, path.readBytes())
    }

    @Test
    fun `a wait counts as no failure, and it goes on at once when it is over`() = runTest(timeout = 60.seconds) {
        var meter: StreamTraffic.Meter? = null
        // Six that failed, five of them counted, then one a stream starts at: a wait, and no sixth.
        reads.script(*Array(6) { Script(refuse = LinkFailure.NoWorkingLink) }, Script(opened = { meter = traffic.open() }))
        val writer = writer()
        val openedAt = CopyOnWriteArrayList<Long>()
        val write = async { writer.wrote(onOpen = { openedAt += currentTime }) }
        advanceTimeBy(126.seconds)
        runCurrent()
        assertEquals(7, opens.size)
        assertFalse(write.isCompleted, "a wait is no failure")
        meter!!.close()
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(Outcome.Done, write.await())
        assertEquals(8, opens.size)
        // On at once once nothing has streamed for 10 s: no backoff's wait.
        assertEquals(openedAt[6] + 10_000, openedAt[7])
    }

    @Test
    fun `nothing of a link, a key, a hash or a file's name in a log line or an exception's message`() = runTest(timeout = 60.seconds) {
        val from = LogBuffer.snapshot().lastOrNull()?.seq ?: 0L
        // A cut, a restart from 0, a torrent added again and the second answer from another part.
        val again = Prepared(HASH, listingA, fileA.copy(id = "971"), handle = "4102")
        reads.script(Script(cutAt = 200_000), Script(refuse = LinkFailure.NoRanges), Script(cutAt = 300_000), Script(refuse = LinkFailure.NoRanges))
        assertEquals(Outcome.Failed(DownloadHostRules.CANT_RESUME), writer().wrote(onOpen = { offset -> if (offset == 0L && opens.size == 3) a.again = again }))
        // Lost on its account, on from the next.
        reset()
        reads.script(Script(cutAt = 400_000, opened = { a.failing = ServiceError.NotCached }))
        assertEquals(Outcome.Done, writer(on = listOf(ACCOUNT_A, ACCOUNT_B)).wrote())
        // A limit, storage with no room, and a hidden build.
        reset()
        a.failing = ServiceError.Limit(Duration.ofMinutes(3))
        val limit = writer().wrote()
        a.failing = null
        assertEquals(Outcome.Failed(RecordedFile.STORAGE_FULL), writer().wrote(channel = { FullAt(it, 100_000, "No space left on device: $path") }))
        writer(shown = { false }).wrote()
        val bad = assertFailsWith<IllegalArgumentException> { writer().wrote(from = size + 1) }

        // This writer's lines and its file's, whatever other tests log meanwhile.
        val lines = LogBuffer.snapshot().filter { it.seq > from && it.tag in setOf("ServiceDownloadWriter", "RecordedFile") }.map { it.message }
        val words = listOf("cdn.example.org", "0123abcd", KEY, HASH.hex, NAME, PATH, TITLE, "Harbor", "KESTREL", "://", ACCOUNT_A, ACCOUNT_B, dir.name)
        assertEquals(emptyList(), lines.filter { line -> words.any { it in line } })
        assertTrue(lines.any { ID in it }, lines.toString())
        assertEquals(emptyList(), words.filter { it in bad.message.orEmpty() || it in limit.toString() })
    }

    /**
     * Account [id]'s service: its [listing], prepared under [handle]; each link asked recorded with what it was asked for.
     * A refused key marks the account, as the registry's watch of its clients does.
     */
    private inner class FakeService(private val id: String, private val listing: ServiceListing, private val handle: String) : DebridService {
        override val kind = ServiceKind.TorBox
        val links = CopyOnWriteArrayList<Pair<Prepared, ServiceFile>>()
        val picks = CopyOnWriteArrayList<ServiceFile?>()

        /** Every link fails so while set. */
        @Volatile var failing: ServiceError? = null

        /** Every prepare fails so while set. */
        @Volatile var prepareFailing: ServiceError? = null

        /** The torrent as the account added it again: handed out with the next link, once. */
        @Volatile var again: Prepared? = null

        /** Heard as each prepare starts. */
        @Volatile var onPrepare: suspend () -> Unit = {}

        @Volatile var scheme = "https"

        override suspend fun test() = throw AssertionError("a download never tests a key")

        override suspend fun check(hashes: List<InfoHash>) = throw AssertionError("a download never checks the cache")

        override suspend fun prepare(hash: InfoHash, pick: suspend (ServiceListing) -> ServiceFile?): Prepared {
            prepareFailing?.let { throw ServiceException(it) }
            onPrepare()
            val picked = pick(listing)
            picks += picked
            // Nothing is added for a pick of none: no handle.
            return Prepared(hash, listing, picked, handle = picked?.let { handle })
        }

        override suspend fun link(prepared: Prepared, file: ServiceFile): ServiceLink {
            links += prepared to file
            failing?.let {
                if (it == ServiceError.KeyRefused) refused += id
                throw ServiceException(it)
            }
            return ServiceLink("$scheme://cdn.example.org/dl/0123abcd${links.size}/$NAME?token=$KEY", Instant.ofEpochMilli(EPOCH), prepared = again?.also { again = null })
        }

        override fun toString() = "FakeService"
    }

    /**
     * How one open answers: refused with [refuse] (a ServiceDataSource's own refusal), or reaching nothing ([unreached]);
     * else with [length] and [total], then its body from the byte asked: cut after [cutAt] bytes (an IOException), ended
     * early after [endAt], waiting on the CDN after [blockAt] until aborted (then as [afterAbort] says), or meeting a broken
     * state after [breakAt] (an IllegalStateException); [past] bytes more after the file's end. [openBreaks] throws such
     * a state at the open. [opened] hears it open; [onRead], each read of every body, counted together.
     */
    private inner class Script(
        val refuse: LinkFailure? = null,
        val unreached: Boolean = false,
        val length: (Long) -> Long? = { size - it },
        val total: (Long) -> Long? = { size },
        val cutAt: Long? = null,
        val endAt: Long? = null,
        val blockAt: Long? = null,
        val afterAbort: AfterAbort = AfterAbort.Throws,
        val breakAt: Long? = null,
        val openBreaks: Boolean = false,
        val past: Int = 0,
        val opened: () -> Unit = {},
        val onRead: (Int) -> Unit = {},
    )

    /** What a read waiting on the CDN does once it is aborted from another thread. */
    private enum class AfterAbort {
        /** Fails, as a read on a socket closed under it does. */
        Throws,

        /** Ends (-1), as a ServiceDataSource read the abort came just before gives. */
        Ends,

        /** Gives bytes that aren't the file's, as a read an abort's close met in the HTTP stack's buffers may. */
        Garbles,

        /** Throws what isn't an IOException, as the HTTP stack's own state may (okio's "closed"). */
        Breaks,
    }

    /**
     * The reads as the app's make them, each over the writer's link resolver: an open asks it for its link first, as a
     * ServiceDataSource asks its factory's links, then answers as the next [Script] says (past them, the rest of the
     * file). [mostOpen] is the most made and not yet closed at once.
     */
    private inner class FakeReads : ServiceDownloadWriter.Reads {
        val made = CopyOnWriteArrayList<Read>()
        private val scripts = ArrayDeque<Script>()
        private var open = 0

        @Volatile var mostOpen = 0

        /** Reads of every body, together. */
        val reads = AtomicInteger()

        /** Counted down as a read waits on the CDN. */
        @Volatile var blocked = CountDownLatch(1)

        fun script(vararg more: Script) = synchronized(scripts) { scripts.addAll(more) }

        override fun make(resolver: LinkResolver, uri: String): Read {
            val script = synchronized(scripts) { scripts.removeFirstOrNull() } ?: Script()
            synchronized(this) {
                open++
                mostOpen = maxOf(mostOpen, open)
            }
            return Read(resolver, uri, script).also { made += it }
        }

        inner class Read(private val resolver: LinkResolver, val uri: String, private val script: Script) : ServiceDownloadWriter.Read {
            @Volatile var link: HttpLink? = null
            val aborted = AtomicBoolean()

            /** Whether an abort came while a read was under way: from another thread, in its buffers. */
            val abortedInRead = AtomicBoolean()
            private val inRead = AtomicBoolean()
            val closed = AtomicBoolean()
            val closedLatch = CountDownLatch(1)
            private val abortedLatch = CountDownLatch(1)
            private var at = 0L
            private var sent = 0L
            private var pastLeft = script.past

            override fun open(position: Long): ServiceDownloadWriter.Answer {
                if (aborted.get()) throw InterruptedIOException("aborted")
                link = resolver.link(PlayUris.parse(uri) as PlayUris.Target.Service, null)
                script.opened()
                script.refuse?.let { throw LinkUnavailable(it) }
                if (script.unreached) throw ConnectException("Connection refused")
                if (script.openBreaks) throw IllegalStateException("unexpected answer")
                at = position
                return ServiceDownloadWriter.Answer(script.length(position), script.total(position))
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                // As a ServiceDataSource after its abort: nothing more to read.
                if (aborted.get()) return -1
                inRead.set(true)
                try {
                    return body(buffer, offset, length)
                } finally {
                    inRead.set(false)
                }
            }

            private fun body(buffer: ByteArray, offset: Int, length: Int): Int {
                script.onRead(reads.incrementAndGet())
                script.blockAt?.let {
                    if (sent >= it) {
                        blocked.countDown()
                        // A CDN gone silent: until the connection is let go.
                        abortedLatch.await(30, TimeUnit.SECONDS)
                        return when (script.afterAbort) {
                            AfterAbort.Throws -> throw IOException("the connection was let go")
                            AfterAbort.Ends -> -1
                            AfterAbort.Garbles -> minOf(length, READ).also { n -> buffer.fill(0x55, offset, offset + n) }
                            AfterAbort.Breaks -> throw IllegalStateException("closed")
                        }
                    }
                }
                script.breakAt?.let { if (sent >= it) throw IllegalStateException("closed") }
                script.cutAt?.let { if (sent >= it) throw IOException("the body was cut") }
                script.endAt?.let { if (sent >= it) return -1 }
                if (at >= size) {
                    if (pastLeft <= 0) return -1
                    pastLeft--
                    buffer[offset] = 1
                    return 1
                }
                val stop = listOfNotNull(script.cutAt, script.endAt, script.blockAt, script.breakAt).minOrNull()?.let { it - sent } ?: Long.MAX_VALUE
                val n = minOf(length.toLong(), READ.toLong(), size - at, stop).toInt()
                data.copyInto(buffer, offset, at.toInt(), (at + n).toInt())
                at += n
                sent += n
                return n
            }

            override fun abort() {
                if (inRead.get()) abortedInRead.set(true)
                aborted.set(true)
                abortedLatch.countDown()
            }

            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                synchronized(this@FakeReads) { open-- }
                closedLatch.countDown()
            }
        }
    }

    /** The downloads' storage beside the row: its listing's copies and its restart mark, in [events] as they come. */
    private inner class FakeFolder : ServiceDownloadWriter.Folder {
        val copies = CopyOnWriteArrayList<DebridListingStore.Entry>()
        val marked: MutableSet<String> = ConcurrentHashMap.newKeySet()

        override suspend fun keepListing(id: String, entry: DebridListingStore.Entry) {
            assertEquals(ID, id)
            copies += entry
            events += "copy"
        }

        override suspend fun markRestarted(id: String) {
            assertEquals(ID, id)
            marked += id
            events += "mark"
        }

        override suspend fun restarted(id: String) = id in marked
    }

    /** [real], every write from [fullAt] on failing with [words], as a volume with no room says it. */
    private class FullAt(private val real: FileChannel, private val fullAt: Long, private val words: String) : FileChannel() {
        override fun write(src: ByteBuffer, position: Long): Int {
            if (position >= fullAt) throw IOException(words)
            if (position + src.remaining() <= fullAt) return real.write(src, position)
            val part = src.duplicate().apply { limit(position() + (fullAt - position).toInt()) }
            return real.write(part, position).also { src.position(src.position() + it) }
        }

        override fun force(metaData: Boolean) = real.force(metaData)
        override fun read(dst: ByteBuffer): Int = real.read(dst)
        override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = real.read(dsts, offset, length)
        override fun write(src: ByteBuffer): Int = throw UnsupportedOperationException("the writer writes at offsets")
        override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = throw UnsupportedOperationException("the writer writes at offsets")
        override fun position(): Long = real.position()
        override fun position(newPosition: Long): FileChannel = apply { real.position(newPosition) }
        override fun size(): Long = real.size()
        override fun truncate(size: Long): FileChannel = apply { real.truncate(size) }
        override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = real.transferTo(position, count, target)
        override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = real.transferFrom(src, position, count)
        override fun read(dst: ByteBuffer, position: Long): Int = real.read(dst, position)
        override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer = real.map(mode, position, size)
        override fun lock(position: Long, size: Long, shared: Boolean): FileLock = real.lock(position, size, shared)
        override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? = real.tryLock(position, size, shared)
        override fun implCloseChannel() = real.close()
    }

    private companion object {
        /** A download's id, as the runner makes one. */
        const val ID = "5d2f0c1e-7a41-4b8e-9c0d-3e6f1a2b4c5d"
        val HASH = InfoHash.of("c0ffee11c0ffee22c0ffee33c0ffee44c0ffee55")
        const val ACCOUNT_A = "4c1d9e70-account-a"
        const val ACCOUNT_B = "8f2a6b13-account-b"
        const val TITLE = "Harbor.Lights.S02E04.1080p.WEB.h264-KESTREL"
        const val NAME = "$TITLE.mkv"
        const val PATH = "$TITLE/$NAME"
        const val NFO = "$TITLE/$TITLE.nfo"
        const val SAMPLE = "$TITLE/Sample/$TITLE.sample.mkv"
        const val KEY = "tbx-not-a-real-key-5a6b7c"

        /** The wall clock's milliseconds at the test's start. */
        const val EPOCH = 1_790_000_000_000L

        /** What a fake read gives at most. */
        const val READ = 64 * 1024
    }
}
