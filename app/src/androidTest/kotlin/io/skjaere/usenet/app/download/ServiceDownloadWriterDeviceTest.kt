package io.skjaere.usenet.app.download

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.HttpClient
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.debrid.DebridFakes
import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.app.debrid.DebridListingStore
import io.skjaere.usenet.app.download.ServiceDownloadWriter.Outcome
import io.skjaere.usenet.app.everyStep
import io.skjaere.usenet.core.StreamTraffic
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.ServiceHttp
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.debrid.torbox.TorBoxService
import io.skjaere.usenet.player.LinkResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The debrid download's writer over the debrid fake's CDN (L419, L471): Harbor Lights S01E02's cached torrent, prepared
 * on the fake's TorBox API with its invented key, its file written to a file of the test's own through the app's reads
 * (Media3's HTTP source, a factory each open) with links from that API. A cut opens again at the byte reached with a
 * fresh link, as does a body the CDN closes at its link's expiry; a CDN that answers a resume with the whole file starts
 * it again from 0 once, then fails it; a part from another byte, or another total, is no working link; never two bodies
 * open at once, and the file whole, its SHA-256 the fake's media's. A meter the test opens on the container's
 * StreamTraffic (a stream starting) holds the reads, the body let go at once, and a fresh link follows 10 s after it
 * closes. It reports M-dbr (a whole file's MB/s on one connection, unthrottled) and M-wait (from the meter's open to the
 * writer's stop and the body's close, and from its close to the next byte) through the instrumentation's status.
 *
 * Nothing of it reaches a row, a job or the app's own processes: the account is the test's own client, no row is made,
 * and the saved accounts are hidden while the endpoints point at the fake, so nothing of the app's asks the fake with
 * another key meanwhile.
 */
@RunWith(AndroidJUnit4::class)
class ServiceDownloadWriterDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val settings = container.settings
    private val dir = File(context.cacheDir, "service-download-writer-${UUID.randomUUID()}")
    private val file = File(dir, "download.part")
    private val folder = Folder()
    private val opens = CopyOnWriteArrayList<Long>()
    private val records = CopyOnWriteArrayList<Long>()
    private var saved: AppSettings? = null
    private var giveBack: (() -> Unit)? = null
    private var pointBack: (() -> Unit)? = null
    private var http: HttpClient? = null
    private lateinit var torbox: TorBoxService

    @Before
    fun setUp() = runBlocking<Unit> {
        DebridFakes.assumeDebrid()
        assumeTrue("debrid is hidden in this build", DebridGate.shown)
        container.devSettingsSaved.await()
        // The saved accounts hidden while the endpoints point at the fake: nothing of the app's asks it with their keys.
        val before = settings.settings.first()
        saved = before
        settings.restore(before.copy(services = before.services.map { it.copy(deleted = true) }))
        giveBack = DebridFakes.holdTheFake()
        DebridFakes.control("reset")
        pointBack = DebridFakes.pointServicesAtTheFake()
        val client = ServiceHttp.defaultHttpClient().also { http = it }
        torbox = TorBoxService(DebridFakes.TORBOX_KEY, client, DebridFakes.endpoint(ServiceKind.TorBox))
        dir.mkdirs()
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        everyStep(
            { giveBack?.let { DebridFakes.control("reset") } },
            { giveBack?.invoke() },
            { pointBack?.invoke() },
            { http?.close() },
            { saved?.let { settings.restore(it) } },
            { dir.deleteRecursively() },
        )
    }

    /** The release's listing as the fake's TorBox prepares it (the torrent added to its account), kept as a row's copy is. */
    private suspend fun listing(): DebridListingStore.Entry {
        val hash = InfoHash.of(GOOD_HASH)
        val prepared = torbox.prepare(hash) { listing -> listing.files.firstOrNull { it.path.endsWith(".mkv") } }
        return DebridListingStore.Entry(DebridListingStore.idFor(ACCOUNT, hash), ACCOUNT, TITLE, prepared)
    }

    private fun writer(reads: ServiceDownloadWriter.Reads = ServiceFileReads, firstBackoff: Duration = ServiceDownloadWriter.FIRST_BACKOFF) = ServiceDownloadWriter(
        reads,
        accounts = { id -> torbox.takeIf { id == ACCOUNT } },
        keyRefused = { false },
        listings = DebridListingStore(File(dir, "listings.json")),
        accountsOn = { listOf(ACCOUNT) },
        streaming = container.streamTraffic.streaming,
        folder = folder,
        firstBackoff = firstBackoff,
    )

    /** [entry]'s file written from 0 to [file], made at its whole size as the runner makes it; each open and record kept. */
    private suspend fun ServiceDownloadWriter.wrote(entry: DebridListingStore.Entry): Outcome {
        val video = entry.prepared.picked!!
        return RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(video.size)
            withTimeout(180_000) {
                write(ID, entry, video, raf.channel, 0, onOpen = { opens += it }) { records += it }
            }
        }
    }

    private fun counts() = JSONObject(DebridFakes.control("counts"))

    /** The links the fake's TorBox gave. */
    private fun links(): Int = counts().getJSONObject("calls").optInt("torbox requestdl")

    /** The bytes each CDN body began at, and how many began there. */
    private fun cdnFrom(): Map<Long, Int> = counts().getJSONObject("cdnFrom").let { json -> json.keys().asSequence().associate { it.toLong() to json.getInt(it) } }

    private fun cdn(status: Int): Int = counts().getJSONObject("cdn").optInt("$status")

    /** The fake's sample: its size and SHA-256. */
    private fun media(): Pair<Long, String> = JSONObject(DebridFakes.control("media")).let { it.getLong("size") to it.getString("sha256") }

    /** The CDN's faults for every link made from now on, the links before them expired so none is given again. */
    private fun faults(query: String) {
        DebridFakes.control("cdn?$query")
        DebridFakes.control("cdn-expire")
    }

    private fun assertWhole() {
        val (size, sha256) = media()
        assertEquals(size, file.length())
        assertEquals("the file is the fake's sample", sha256, DownloadFixtures.sha256(file))
    }

    /** Says [line] under [key] through the instrumentation's status, as the pairs do: the run's output keeps it. */
    private fun report(key: String, line: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(STATUS, Bundle().apply { putString(key, line) })
    }

    @Test
    fun aWholeFileComesInOneBodyFromOneLink() = runBlocking<Unit> {
        val entry = listing()
        val started = SystemClock.elapsedRealtime()
        assertEquals(Outcome.Done, writer().wrote(entry))
        val took = SystemClock.elapsedRealtime() - started
        assertWhole()
        assertEquals(listOf(0L), opens)
        assertEquals(1, links())
        assertEquals(mapOf(0L to 1), cdnFrom())
        assertEquals("one body open at once", 1, counts().getInt("cdnOpenMax"))
        report(M_DBR, "a whole file of ${file.length()} bytes on one connection from the fake's CDN, unthrottled: $took ms, ${"%.1f".format(file.length() / 1_000_000.0 / (took / 1_000.0))} MB/s")
    }

    @Test
    fun aCutOpensAgainAtItsOffsetWithAFreshLink() = runBlocking<Unit> {
        faults("cut=$CUT")
        assertEquals(Outcome.Done, writer().wrote(listing()))
        assertWhole()
        // Each body cut after 3 MB: opened again where it reached, each with a link of its own.
        assertEquals(listOf(0L, CUT, 2 * CUT), opens)
        assertEquals(opens.size, links())
        assertEquals(opens.toSet(), cdnFrom().keys)
        // Each reopen at what was recorded at the cut before it.
        assertTrue("$records", records.containsAll(listOf(CUT, 2 * CUT)))
        assertEquals(file.length(), records.last())
        assertEquals("one body open at once", 1, counts().getInt("cdnOpenMax"))
        report(M_REOPEN, "a cut every $CUT bytes: ${opens.size} opens, ${links()} links asked")
    }

    @Test
    fun aBodyClosedAtItsLinksExpiryGoesOnWithAFreshLink() = runBlocking<Unit> {
        // Links that expire 3 s after they are made, a body closed there, at 1.5 MB/s: the sample takes longer than one link lives.
        faults("expire=3&expireCuts=true&slow=1500")
        assertEquals(Outcome.Done, writer().wrote(listing()))
        assertWhole()
        assertTrue("opened again after the expiry: $opens", opens.size >= 2)
        assertEquals(opens.size, links())
        // Every body began where the one before it reached, never with an expired link.
        assertEquals(opens.toSet(), cdnFrom().keys)
        assertEquals(0, cdn(403))
        assertEquals("one body open at once", 1, counts().getInt("cdnOpenMax"))
        report(M_REOPEN, "links expiring each 3 s: ${opens.size} opens, ${links()} links asked")
    }

    /**
     * A CDN that answers every read with the whole file (`whole=true`), each body cut after 2 MB: the first body from 0 is
     * taken, the resume at 2 MB answered whole is a server that won't resume, so the file starts again from 0 once, its
     * mark written; the second whole answer to a resume fails it. The bodies aborted at their headers count open at the
     * fake until its next write to them fails, so the most open at once isn't read here.
     */
    @Test
    fun aServerThatAnswersAResumeWithTheWholeFileStartsItAgainOnceThenFailsIt() = runBlocking<Unit> {
        faults("whole=true&cut=$WHOLE_CUT")
        assertEquals(Outcome.Failed(DownloadHostRules.CANT_RESUME), writer().wrote(listing()))
        assertEquals(listOf(0L, WHOLE_CUT, 0L, WHOLE_CUT), opens)
        assertEquals(setOf(ID), folder.marked)
        assertEquals(listOf(WHOLE_CUT, 0L, WHOLE_CUT), records)
        assertEquals(4, links())
        assertEquals(4, cdn(200))
        waitUntil("the aborted bodies let go at the fake") { counts().getInt("cdnOpen") == 0 }
    }

    @Test
    fun aPartFromAnotherByteIsNoWorkingLink() = runBlocking<Unit> {
        faults("shift=500")
        assertEquals(Outcome.Failed(DownloadHostRules.SERVICE_KEPT_FAILING), writer(firstBackoff = 100.milliseconds).wrote(listing()))
        // Nothing written: no restart, six reopens with nothing.
        assertEquals(List(7) { 0L }, opens)
        assertEquals(7, links())
        assertTrue(folder.marked.isEmpty())
        assertTrue(records.all { it == 0L })
        assertEquals("one body open at once", 1, counts().getInt("cdnOpenMax"))
    }

    /**
     * One link per open (L419): a link whose CDN answers with a page in place of the file (a dead link's answer) gets no
     * other within its open, where a play's source would ask one: no working link, a counted reopen, each with its own.
     */
    @Test
    fun aLinkAnsweredWithAPageGetsNoOtherWithinItsOpen() = runBlocking<Unit> {
        faults("page=true")
        assertEquals(Outcome.Failed(DownloadHostRules.SERVICE_KEPT_FAILING), writer(firstBackoff = 100.milliseconds).wrote(listing()))
        assertEquals(List(7) { 0L }, opens)
        assertEquals("one link per open", opens.size, links())
        assertEquals("one GET per open", opens.size, cdn(200))
        assertTrue(records.all { it == 0L })
        report(M_REOPEN, "a page in place of the file: ${opens.size} opens, ${links()} links asked")
    }

    @Test
    fun aContentRangeOfAnotherTotalIsNoWorkingLink() = runBlocking<Unit> {
        faults("total=999")
        assertEquals(Outcome.Failed(DownloadHostRules.SERVICE_KEPT_FAILING), writer(firstBackoff = 100.milliseconds).wrote(listing()))
        assertEquals(List(7) { 0L }, opens)
        assertEquals(7, links())
        assertTrue(records.all { it == 0L })
        assertEquals("one body open at once", 1, counts().getInt("cdnOpenMax"))
    }

    /**
     * W123 (L471): the CDN at 800 kB/s; once 1.5 MB are written, the test opens a meter on the container's StreamTraffic,
     * as a play's or a cast's stream does. The writer stops, its body let go at once (the fake's body counted closed, no
     * other begun), and nothing is asked while the meter stays open; closed, a fresh link follows 10 s later, and the file
     * goes on from where it stopped, whole.
     */
    @Test
    fun aStreamThatStartsHoldsTheReadsAndAFreshLinkFollowsTenSecondsAfterItEnds() = runBlocking<Unit> {
        faults("slow=800")
        val entry = listing()
        val timed = Timed()
        val writer = writer(timed)
        var meter: StreamTraffic.Meter? = null
        try {
            val write = async(Dispatchers.Default) { writer.wrote(entry) }
            // When the live first says it waits: the writer's stop, timed as it comes rather than by a poll.
            val stop = async(Dispatchers.Default) {
                writer.live.first { it?.streamWait == true }
                SystemClock.elapsedRealtime()
            }
            waitUntil("1.5 MB written", 60_000) { (writer.live.value?.written ?: 0) >= 1_500_000 }
            val opened = SystemClock.elapsedRealtime()
            meter = container.streamTraffic.open()
            val stoppedAt = withTimeout(10_000) { stop.await() }
            val stopped = writer.live.value!!
            assertTrue(stopped.streamWait)
            val closedAt = waitFor("the body closed", 10_000) { timed.last("close") }
            waitUntil("the body let go at the fake", 5_000) { counts().getInt("cdnOpen") == 0 }
            assertEquals(1, links())
            val from = cdnFrom()
            // Held: nothing asked, nothing read, however long it streams.
            delay(3_000)
            assertEquals(1, links())
            assertEquals(from, cdnFrom())
            assertEquals(stopped.written, writer.live.value?.written)
            meter.close()
            val ended = SystemClock.elapsedRealtime()
            meter = null
            val next = waitFor("the next byte", 30_000) { timed.firstAfter("byte", ended) }
            assertTrue("went on ${next - ended} ms after the stream ended", next - ended >= 10_000)
            assertEquals(Outcome.Done, write.await())
            assertWhole()
            assertEquals("one link per stream start", 2, links())
            assertEquals(listOf(0L, stopped.written), opens)
            assertTrue("recorded at the byte reached: $records", stopped.written in records)
            assertEquals(setOf(0L, stopped.written), cdnFrom().keys)
            assertEquals("one body open at once", 1, counts().getInt("cdnOpenMax"))
            report(
                M_WAIT,
                "meter open to the writer's stop ${stoppedAt - opened} ms, to its body's close ${closedAt - opened} ms; " +
                    "the meter's close to the next byte ${next - ended} ms (the wait is ${ServiceDownloadWriter.QUIET.inWholeSeconds} s)",
            )
        } finally {
            meter?.close()
        }
    }

    /** Polls [probe] until it gives something, [timeoutMs] at most. */
    private suspend fun <T : Any> waitFor(what: String, timeoutMs: Long = 10_000, probe: () -> T?): T {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            check(SystemClock.elapsedRealtime() < until) { "$what: not within $timeoutMs ms" }
            delay(50)
        }
    }

    /** Polls [probe] until it says so, [timeoutMs] at most. */
    private suspend fun waitUntil(what: String, timeoutMs: Long = 10_000, probe: () -> Boolean) {
        waitFor(what, timeoutMs) { true.takeIf { probe() } }
    }

    /** The app's reads, each one's events timed (elapsed real time): its open, its first byte, its abort and its close. */
    private class Timed : ServiceDownloadWriter.Reads {
        private val events = CopyOnWriteArrayList<Pair<String, Long>>()

        fun last(what: String): Long? = events.lastOrNull { it.first == what }?.second

        fun firstAfter(what: String, at: Long): Long? = events.firstOrNull { it.first == what && it.second >= at }?.second

        private fun said(what: String) {
            events += what to SystemClock.elapsedRealtime()
        }

        override fun make(resolver: LinkResolver, uri: String): ServiceDownloadWriter.Read {
            val read = ServiceFileReads.make(resolver, uri)
            return object : ServiceDownloadWriter.Read {
                private var first = true

                override fun open(position: Long) = read.open(position).also { said("open") }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = read.read(buffer, offset, length).also { n ->
                    if (n > 0 && first) {
                        first = false
                        said("byte")
                    }
                }

                override fun abort() {
                    said("abort")
                    read.abort()
                }

                override fun close() {
                    read.close()
                    said("close")
                }
            }
        }
    }

    /** The row's files beside its download, in memory: no row is made. */
    private class Folder : ServiceDownloadWriter.Folder {
        val copies = CopyOnWriteArrayList<DebridListingStore.Entry>()
        val marked: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        override suspend fun keepListing(id: String, entry: DebridListingStore.Entry) {
            copies += entry
        }

        override suspend fun markRestarted(id: String) {
            marked += id
        }

        override suspend fun restarted(id: String) = id in marked
    }

    private companion object {
        /** The fake's Harbor Lights S01E02, cached in both services. */
        const val GOOD_HASH = "1bb5795cb9f72aa684193ddf405249b071b2ba5f"
        const val TITLE = "Harbor.Lights.S01E02.1080p.WEB-DL.AAC2.0.H.264-GOOD"

        /** An account id as the settings give one: the test's TorBox client stands for it. */
        const val ACCOUNT = "3e9a5c71-0b2d-4f86-a1c4-77d0e5b6f912"
        const val ID = "6a1f0d3b-2c5e-4b7a-9f08-1d2e3c4b5a69"
        const val CUT = 3_000_000L
        const val WHOLE_CUT = 2_000_000L

        /** The instrumentation status's code for a line: "in progress", which no runner counts as a test. */
        const val STATUS = 2
        const val M_DBR = "m-dbr"
        const val M_WAIT = "m-wait"
        const val M_REOPEN = "m-reopen"
    }
}
