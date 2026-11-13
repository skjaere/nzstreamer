package io.skjaere.usenet.app.serve

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.LocalNntp
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.mockOnEngine
import io.skjaere.usenet.app.setup.LanAddress
import io.skjaere.usenet.core.ContainerTypes
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.core.OpenedStream
import io.skjaere.usenet.core.PrepareOutcome
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.StreamOpener
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.CachePrefs
import io.skjaere.usenet.serve.ServeEvent
import io.skjaere.usenet.serve.ServeServer
import io.skjaere.usenet.serve.Served
import io.skjaere.usenet.serve.ServedFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * U4 (K5, L96): a Resume's reads through Cast's server finish over the real engine. Every cast Resume, and a first seek,
 * reads the Matroska index at the file's end; a 4K remux's tail read once stalled the probe on the Shield (big parts, the
 * index last). A film of about 28 MB in parts of 3.84 MB, as a remux's are, its index in the last 8 KB, is posted on a
 * server in the test's process ([LocalNntp]) and prepared on the engine; the app's server, bound at the address it binds
 * when casting ([LanAddress]), serves it through [ServedStream] to a raw client standing in for the receiver, which reads
 * as the Shield did: the last 7 MB, as the stalled probe did; then a Resume's head, tail and middle, the head opened ahead
 * of the LOAD (`bytes=0-` for 4 MB, then a close; the index from its offset rounded down to 32 KiB to the end; a far
 * cluster). With "Keep watched video" off and on, each read is byte-exact and finishes within 30 s, with never more than
 * 2 of the engine's streams open. Opening the head ahead returns while the engine is still opening it (item 14 opens it
 * on the main path), its stream from byte 0 is open before the device's first request, and that request reads it: no
 * second stream from byte 0. The film's bytes are random under a Matroska header; its title is invented. The video cache
 * setting is put back after each test.
 */
@RunWith(AndroidJUnit4::class)
class ServedTailDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val settings = container.settings
    private val local = LocalNntp()
    private val scope = CoroutineScope(SupervisorJob())
    private lateinit var saved: AppSettings
    private var server: ServeServer? = null

    /** The server's ends and gaps, as numbers (L75): what a failure says. */
    private val events = CopyOnWriteArrayList<String>()

    /** The engine's streams the server holds: open now, the most at once, and each one's range. */
    private class CountingOpener(private val real: StreamOpener) : StreamOpener {
        val open = AtomicInteger()
        val most = AtomicInteger()
        val ranges = CopyOnWriteArrayList<LongRange>()

        /** While set, an open waits for it: the engine still opening its stream. */
        @Volatile var held: CompletableDeferred<Unit>? = null

        override suspend fun open(nzb: PreparedNzb, fileIndex: Int, range: LongRange): OpenedStream {
            held?.await()
            val stream = real.open(nzb, fileIndex, range)
            ranges += range
            most.accumulateAndGet(open.incrementAndGet(), ::maxOf)
            // Held until the server's adapter lets it go (a close cancels it), parked streams included, as L56 counts them.
            val held = Job()
            held.invokeOnCompletion {
                open.decrementAndGet()
                stream.job.cancel()
            }
            return OpenedStream(stream.channel, held, stream.gaps)
        }
    }

    @Before
    fun setUp() = runBlocking<Unit> {
        saved = settings.settings.first()
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        server?.close()
        scope.cancel()
        local.close()
        // Nothing to put back when the settings were never read.
        if (!::saved.isInitialized) return@runBlocking
        settings.restore(saved)
        withTimeout(10_000) {
            while (container.videoCache.usageNow()?.let { it.on == saved.cache.keepVideo && it.chosenBytes == saved.cache.limitBytes } != true) delay(50)
        }
    }

    @Test
    fun theTailAndAResumeFinishWithKeepWatchedVideoOff() = runBlocking<Unit> {
        readsFinish(keepVideo = false, tag = "tail-off")
    }

    @Test
    fun theTailAndAResumeFinishWithKeepWatchedVideoOn() = runBlocking<Unit> {
        readsFinish(keepVideo = true, tag = "tail-on")
    }

    private suspend fun readsFinish(keepVideo: Boolean, tag: String) {
        cache(keepVideo)
        // Posted under the variant's own ids: a part the cache holds from one is never taken for the other's.
        val film = Random(tag.hashCode()).nextBytes(SIZE).apply { MATROSKA.copyInto(this) }
        val posted = local.post(NAME, film, PART, tag)
        val prepared = engineSetup("the engine on the test's own NNTP server, the film prepared") {
            mockOnEngine()
            container.engine.configure(EngineSettings(listOf(local.provider)))
            (container.preparer.prepare(local.nzb(posted), TITLE) as? PrepareOutcome.Ready)?.nzb ?: throw AssertionError("the film didn't prepare")
        }
        val file = prepared.defaultPick ?: throw AssertionError("no file in the prepared film")
        assertEquals(SIZE.toLong(), file.size)
        val counting = CountingOpener(container.opener)
        val address = withTimeout(10_000) { LanAddress.watch(context).filterNotNull().first() }
        val server = ServeServer(address, onEvent = ::heard).also { server = it }
        server.start()
        val source = ServedStream(prepared, file, counting, scope, container.streamTraffic, container.gapReports)
        val served = server.serve(ServedFile(file.size, ContainerTypes.mimeOf(file.name)!!, ContainerTypes.extension(file.name), source), address)
        val tail = INDEX / ALIGN * ALIGN
        val far = SIZE * 55L / 100 / ALIGN * ALIGN

        // The stalled probe's read: the last 7 MB.
        read(served, server.authority, SIZE - 7_000_000L, film, "the last 7 MB", keepVideo)
        // A Resume, as the Shield read one (T3): the head opened ahead of the LOAD, read 4 MB and closed; the index; the middle.
        // Opening the head ahead returns while the engine is still opening it, as item 14 opens it on the main path.
        val engine = CompletableDeferred<Unit>().also { counting.held = it }
        val asked = SystemClock.elapsedRealtime()
        val opening = thread(isDaemon = true, name = "open-head") { served.openHead() }
        opening.join(5_000)
        assertFalse("opening the head ahead waited for the engine's open", opening.isAlive)
        val openedIn = SystemClock.elapsedRealtime() - asked
        counting.held = null
        engine.complete(Unit)
        // Its stream from byte 0 is open before the device asks; the device's first request reads it.
        withTimeout(10_000) { while (counting.ranges.none { it.first == 0L }) delay(20) }
        read(served, server.authority, 0, film, "bytes=0- for 4 MB", keepVideo, upTo = 4L * MIB)
        assertEquals("streams from byte 0 (opened: ${counting.ranges.map { "${it.first}-${it.last}" }}; $events)", 1, counting.ranges.count { it.first == 0L })
        Log.i(TAG, "keepVideo=$keepVideo: the head opened ahead in $openedIn ms, the engine still opening")
        read(served, server.authority, tail, film, "the tail from the index", keepVideo)
        read(served, server.authority, far, film, "a far cluster for 4 MB", keepVideo, upTo = 4L * MIB)

        val ranges = counting.ranges.map { "${it.first}-${it.last}" }
        assertTrue("${counting.most.get()} of the engine's streams open at once (opened: $ranges; $events)", counting.most.get() <= 2)
        val counters = served.counters()
        assertEquals("upstream failures ($events)", 0, counters.upstreamFailures)
        assertEquals("stalls ($events)", 0, counters.stalls)
        served.revoke()
        // Each stream let go: none parked or read once the file is revoked.
        withTimeout(5_000) { while (counting.open.get() > 0) delay(20) }
        Log.i(TAG, "keepVideo=$keepVideo: streams opened ${ranges.size}, most at once ${counting.most.get()}, bytes sent ${counters.bytesSent}")
    }

    /** "Keep watched video" [on] or off, as Settings › Cache saves it, once the video cache has applied it. */
    private suspend fun cache(on: Boolean) {
        settings.restore(saved.copy(cache = if (on) CachePrefs() else saved.cache.copy(keepVideo = false)))
        withTimeout(10_000) { while (container.videoCache.usageNow()?.on != on) delay(50) }
        if (on) assertTrue("no room for the video cache on this device", (container.videoCache.usageNow()?.limitBytes ?: 0) > 0)
        container.videoCache.clear()
    }

    private fun heard(event: ServeEvent) {
        when (event) {
            is ServeEvent.Ended -> events += "ended from ${event.from} after ${event.sent}: ${event.why}"
            is ServeEvent.Dropped -> events += "dropped: ${event.why}"
            is ServeEvent.Answered -> if (event.status != 206) events += "answered ${event.status}"
            is ServeEvent.Pinned -> Unit
            ServeEvent.ListenerLost -> events += "stopped listening"
        }
    }

    /**
     * A GET of [served] from byte [from], as the receiver asks (`Range: bytes=<from>-` on a new connection, its headers):
     * read to the end, or [upTo] bytes and then closed. Byte-exact against [film], within 30 s.
     */
    private fun read(served: Served, authority: String, from: Long, film: ByteArray, what: String, keepVideo: Boolean, upTo: Long? = null) {
        val start = SystemClock.elapsedRealtime()
        val (host, port) = authority.substringBeforeLast(':') to authority.substringAfterLast(':').toInt()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 5_000)
            socket.soTimeout = 30_000
            socket.getOutputStream().write(
                ("GET ${served.path} HTTP/1.1\r\nHost: $authority\r\nRange: bytes=$from-\r\nAccept-Encoding: identity;q=1, *;q=0\r\n" +
                    "Accept: */*\r\nConnection: keep-alive\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
            )
            val input = BufferedInputStream(socket.getInputStream())
            val status = line(input)
            val headers = generateSequence { line(input).takeIf { it.isNotEmpty() } }.associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            assertEquals("$what: the status", "HTTP/1.1 206 Partial Content", status)
            assertEquals("$what: the range", "bytes $from-${SIZE - 1}/$SIZE", headers["content-range"])
            val want = minOf(upTo ?: Long.MAX_VALUE, SIZE - from).toInt()
            val body = ByteArray(want)
            var got = 0
            while (got < want) {
                val n = try {
                    input.read(body, got, want - got)
                } catch (e: java.net.SocketTimeoutException) {
                    throw AssertionError("$what: stalled at byte ${from + got} after ${SystemClock.elapsedRealtime() - start} ms ($events)", e)
                }
                if (n < 0) throw AssertionError("$what: ended at byte ${from + got} of ${from + want} ($events)")
                got += n
                assertTrue("$what: ${got} bytes took over 30 s ($events)", SystemClock.elapsedRealtime() - start <= 30_000)
            }
            assertArrayEquals("$what: the bytes", film.copyOfRange(from.toInt(), from.toInt() + want), body)
            // Read to the end: the server closes after it (Connection: close).
            if (upTo == null) assertEquals("$what: more than the file", -1, input.read())
        }
        Log.i(TAG, "keepVideo=$keepVideo: $what in ${SystemClock.elapsedRealtime() - start} ms")
    }

    /** A header line, without its CRLF. */
    private fun line(input: InputStream): String {
        val out = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) return out.toString().removeSuffix("\r")
            out.append(c.toChar())
        }
    }

    private companion object {
        const val TAG = "ServedTailDeviceTest"
        const val MIB = 1024 * 1024
        /** A remux's article: 3.84 MB, five times the fixtures'. */
        const val PART = 3_840_000
        /** Seven parts and a part of one: about 28 MB. */
        const val SIZE = 7 * PART + 1_234_567
        /** Where the index starts: the last 8,383 bytes, as in the receiver spike's file (report T2). */
        const val INDEX = SIZE - 8_383
        /** The receiver asks from 32 KiB boundaries (item 6(a)). */
        const val ALIGN = 32_768L
        const val NAME = "Copper.Crown.2026.2160p.REMUX.mkv"
        const val TITLE = "Copper.Crown.2026.2160p.REMUX-TAIL"
        /** A Matroska file's first bytes (EBML's id), so the film reads as one, never as an archive. */
        val MATROSKA = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
    }
}
