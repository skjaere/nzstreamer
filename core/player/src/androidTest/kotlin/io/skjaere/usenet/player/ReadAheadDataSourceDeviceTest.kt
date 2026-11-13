package io.skjaere.usenet.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.skjaere.usenet.core.StreamTraffic
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * A debrid play's read-ahead over its HTTP source ([ReadAheadDataSource], L239, L249), against a server of its own on this
 * device's loopback address that notes when each connection comes and goes: one connection at a time, the old one let go
 * before a seek's new one opens; closes while the body flows abort nothing; a close while the reader waits on a silent
 * server returns within a second; a close while it opens the file again lets that connection go, and one while it asks
 * for a fresh link leaves nothing sent after it; from the
 * file's first byte no more than 2 MiB is read ahead until the player has taken 1 MiB; a seek's open reads ahead the whole
 * budget; a server silent for 6 s mid-body is ridden on the bytes queued; a cut after some bytes is opened again at its
 * byte; a fresh link the service refuses fails the read at its byte, the bytes before it read; the player's takes reach
 * the stream-cost meter, not what was read ahead. What the read-ahead pulls is counted where it pulls it from its inner
 * source, through a counting wrapper, never as the server sent it: a loopback socket buffers megabytes. On a device, as
 * [ServiceDataSourceDeviceTest] is. Invented names.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class ReadAheadDataSourceDeviceTest {
    /** One request the server answered: its path, where its Range starts, and when the client let its connection go. */
    private class Request(val path: String, val from: Long) {
        val arrivedAt = System.nanoTime()

        /** As the server saw the client go: 0 while the connection is open. */
        @Volatile
        var closedAt = 0L

        /** When a silence began on it, 0 if none did. */
        @Volatile
        var silencedAt = 0L
    }

    /** How the server answers a path. */
    private sealed interface Mode {
        /** The file from where the Range asks, as fast as it goes. */
        data object File : Mode

        /** The file at [bytesPerSecond]. */
        data class Paced(val bytesPerSecond: Long) : Mode

        /** The file, but silent for [ms] once [after] bytes of the body are sent (or until the client goes). */
        data class Silent(val after: Long, val ms: Long) : Mode

        /** The path's first answer cut after [after] bytes; the next whole, or [then] if set. */
        data class Cut(val after: Long, val then: Int? = null) : Mode

        /** The path's first answer cut after [after] bytes; the next one's head held until the client goes; then whole. */
        data class CutThenHeld(val after: Long) : Mode
    }

    /** An HTTP/1.1 server of [bytes] on 127.0.0.1, one request a connection, each path as [modes] says. */
    private class CdnServer(val bytes: ByteArray) : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val modes = ConcurrentHashMap<String, Mode>()
        val requests = CopyOnWriteArrayList<Request>()
        private val answered = ConcurrentHashMap<String, AtomicInteger>()

        init {
            thread(isDaemon = true, name = "cdn-accept") {
                while (true) {
                    val connection = runCatching { socket.accept() }.getOrNull() ?: break
                    thread(isDaemon = true, name = "cdn-serve") { runCatching { connection.use(::serve) } }
                }
            }
        }

        fun url(path: String) = "http://127.0.0.1:${socket.localPort}$path"

        override fun close() = socket.close()

        private fun serve(connection: Socket) {
            val input = connection.getInputStream()
            val line = readLine(input) ?: return
            val headers = generateSequence { readLine(input)?.takeIf { it.isNotEmpty() } }
                .map { it.substringBefore(':').trim() to it.substringAfter(':').trim() }.toList()
            val range = headers.firstOrNull { it.first.equals("Range", ignoreCase = true) }?.second
                ?.let { Regex("""bytes=(\d+)-""").find(it)?.groupValues?.get(1)?.toLong() }
            val request = Request(line.split(' ')[1], range ?: 0).also { requests += it }
            // A GET sends nothing more: a read on its connection ends only as the client lets it go.
            thread(isDaemon = true, name = "cdn-watch") {
                runCatching { while (input.read() != -1) Unit }
                request.closedAt = System.nanoTime()
            }
            val nth = answered.getOrPut(request.path) { AtomicInteger() }.incrementAndGet()
            val out = connection.getOutputStream()
            val from = request.from
            val size = bytes.size.toLong()
            when (val mode = modes[request.path] ?: return answer(out, 404)) {
                Mode.File -> {
                    head(out, from)
                    body(out, request, from, size)
                }
                is Mode.Paced -> {
                    head(out, from)
                    body(out, request, from, size, mode.bytesPerSecond)
                }
                is Mode.Silent -> {
                    head(out, from)
                    val until = minOf(size, from + mode.after)
                    body(out, request, from, until)
                    request.silencedAt = System.nanoTime()
                    val wakeAt = System.nanoTime() + mode.ms * 1_000_000
                    while (System.nanoTime() < wakeAt && request.closedAt == 0L) Thread.sleep(20)
                    if (request.closedAt == 0L) body(out, request, until, size)
                }
                is Mode.Cut -> when {
                    nth == 1 -> {
                        head(out, from)
                        body(out, request, from, minOf(size, from + mode.after))
                    }
                    mode.then != null -> answer(out, mode.then)
                    else -> {
                        head(out, from)
                        body(out, request, from, size)
                    }
                }
                is Mode.CutThenHeld -> when (nth) {
                    1 -> {
                        head(out, from)
                        body(out, request, from, minOf(size, from + mode.after))
                    }
                    2 -> {
                        request.silencedAt = System.nanoTime()
                        val until = System.nanoTime() + 30_000_000_000
                        while (System.nanoTime() < until && request.closedAt == 0L) Thread.sleep(20)
                    }
                    else -> {
                        head(out, from)
                        body(out, request, from, size)
                    }
                }
            }
        }

        private fun head(out: OutputStream, from: Long) {
            val size = bytes.size
            val lines = listOf(
                "HTTP/1.1 206 Partial", "Content-Type: application/octet-stream", "Content-Length: ${size - from}",
                "Content-Range: bytes $from-${size - 1}/$size", "Accept-Ranges: bytes", "ETag: \"f1\"", "Connection: close",
            )
            out.write((lines.joinToString("\r\n") + "\r\n\r\n").toByteArray())
            out.flush()
        }

        private fun answer(out: OutputStream, status: Int) {
            out.write("HTTP/1.1 $status Answer\r\nContent-Type: text/plain\r\nContent-Length: 5\r\nConnection: close\r\n\r\nGone.".toByteArray())
            out.flush()
        }

        /** [from] to [until] of the file, as fast as it goes, or at [bytesPerSecond] in tenths of a second. */
        private fun body(out: OutputStream, request: Request, from: Long, until: Long, bytesPerSecond: Long? = null) {
            val chunk = bytesPerSecond?.let { (it / 10).toInt() } ?: CHUNK
            var at = from
            while (at < until && request.closedAt == 0L) {
                val n = minOf(chunk.toLong(), until - at).toInt()
                out.write(bytes, at.toInt(), n)
                out.flush()
                at += n
                if (bytesPerSecond != null) Thread.sleep(100)
            }
        }

        private fun readLine(input: InputStream): String? {
            val line = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b == -1) return if (line.size() == 0) null else line.toString(Charsets.ISO_8859_1.name())
                if (b == '\n'.code) return line.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
                line.write(b)
            }
        }

        private companion object {
            const val CHUNK = 16 * 1024
        }
    }

    /** A resolver that hands out [links] in turn (the last again after them). */
    private class Links(private vararg val links: () -> HttpLink) : LinkResolver {
        val asked = CopyOnWriteArrayList<HttpLink?>()

        override fun link(target: PlayUris.Target.Service, stale: HttpLink?): HttpLink {
            asked += stale
            return links[minOf(asked.size, links.size) - 1]()
        }
    }

    /**
     * The inner source as the read-ahead reads it: what it pulls is counted, and how many of the inner sources' connections
     * are open at once, from an open's answer to the close or abort that lets it go.
     */
    private class Counted(private val source: ServiceDataSource, private val counts: Counts) : AbortableDataSource {
        private var connected = false

        override fun open(dataSpec: DataSpec): Long = source.open(dataSpec).also { connected(true) }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            source.read(buffer, offset, length).also { if (it > 0) counts.pulled.addAndGet(it.toLong()) }

        override fun close() {
            source.close()
            connected(false)
        }

        override fun abort() {
            counts.aborts.incrementAndGet()
            source.abort()
            connected(false)
        }

        @Synchronized
        private fun connected(now: Boolean) {
            if (now == connected) return
            connected = now
            val open = if (now) counts.open.incrementAndGet() else counts.open.decrementAndGet()
            counts.most.accumulateAndGet(open, ::maxOf)
        }

        override fun getUri(): Uri? = source.uri

        override fun getResponseHeaders(): Map<String, List<String>> = source.responseHeaders

        override fun addTransferListener(transferListener: TransferListener) = source.addTransferListener(transferListener)
    }

    private class Counts {
        val pulled = AtomicLong()
        val open = AtomicInteger()
        val most = AtomicInteger()
        val aborts = AtomicInteger()
    }

    private val bytes = ByteArray(16 * MIB.toInt()) { (it * 31 + 7).toByte() }
    private val placeholder = Uri.parse(PlayUris.service("0f3e9a1c5b7d2e4f6a8b0c1d2e3f4a5b", 2))
    private val counts = Counts()

    private fun spec(position: Long) = DataSpec.Builder().setUri(placeholder).setPosition(position).build()

    private fun link(server: CdnServer, path: String): () -> HttpLink =
        { HttpLink(server.url(path), System.currentTimeMillis(), "Harbor.Lights.S01E02.1080p.WEB-DL.mkv") }

    /** The player's source over [links], reading [BUDGET] ahead, its inner sources counted. */
    private fun source(links: LinkResolver, traffic: StreamTraffic? = null): DataSource {
        val services = ServiceDataSource.Factory(links)
        return ReadAheadDataSource.Factory({ Counted(services.createUnmetered(), counts) }, traffic) { BUDGET }.createDataSource()
    }

    /** Reads [count] bytes, checking each is the file's from [from]; the longest a read took, in ms. */
    private fun DataSource.readChecked(count: Long, from: Long, pace: Long? = null): Long {
        val buffer = ByteArray(64 * 1024)
        var done = 0L
        var longest = 0L
        while (done < count) {
            val started = System.nanoTime()
            val n = read(buffer, 0, minOf(buffer.size.toLong(), count - done).toInt())
            longest = maxOf(longest, (System.nanoTime() - started) / 1_000_000)
            assertTrue("a byte at least before $count, got $n after $done", n > 0)
            assertArrayEquals("bytes from ${from + done}", bytes.copyOfRange((from + done).toInt(), (from + done + n).toInt()), buffer.copyOf(n))
            done += n
            // Taken at the pace a player at that bitrate takes them.
            if (pace != null) Thread.sleep(n * 1_000L / pace)
        }
        return longest
    }

    /** What the read-ahead has pulled once it has stopped pulling: nothing new for 300 ms. */
    private fun settled(): Long {
        val until = System.nanoTime() + 15_000_000_000
        var last = -1L
        var since = System.nanoTime()
        while (System.nanoTime() < until) {
            val now = counts.pulled.get()
            if (now != last) {
                last = now
                since = System.nanoTime()
            } else if (System.nanoTime() - since > 300_000_000) {
                return now
            }
            Thread.sleep(20)
        }
        throw AssertionError("still pulling after 15 s: ${counts.pulled.get()}")
    }

    private fun waitUntil(timeoutMs: Long, what: String, done: () -> Boolean) {
        val until = System.nanoTime() + timeoutMs * 1_000_000
        while (!done()) {
            if (System.nanoTime() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    @Test
    fun oneConnectionAtATimeTheOldOneLetGoBeforeASeeksNewOneOpens(): Unit = CdnServer(bytes).use { server ->
        // Silent after a little, so the reader waits in a read whenever the player seeks, and each close aborts it.
        server.modes["/a"] = Mode.Silent(after = 300_000, ms = 30_000)
        val source = source(Links(link(server, "/a")))
        for (position in listOf(0L, 8 * MIB, MIB, 12 * MIB)) {
            source.open(spec(position))
            source.readChecked(100_000, from = position)
            source.close()
        }
        assertEquals("each close aborted a read waiting", 4, counts.aborts.get())
        assertEquals("one connection open at a time", 1, counts.most.get())
        assertEquals(listOf(0L, 8 * MIB, MIB, 12 * MIB), server.requests.map { it.from })
        // As the server saw it: each one gone by the time the next asked, but for the threads' scheduling.
        server.requests.zipWithNext { old, new ->
            waitUntil(1_000, "the connection from ${old.from} to go") { old.closedAt != 0L }
            assertTrue("the one from ${old.from} went ${(old.closedAt - new.arrivedAt) / 1_000_000} ms after the next asked", old.closedAt - new.arrivedAt < 200_000_000)
        }
    }

    /**
     * GRA-1: closes while the body flows, the server sending every tenth of a second: each close waits for the reader to
     * come out of its read and lets the connection go itself, never aborting it under a read that flows (Android's HTTP
     * stack reads the rest of a body on the thread that closes it); one connection at a time, each open's bytes the file's.
     */
    @Test
    fun closesWhileTheBodyFlowsWaitForTheReaderAndAbortNothing(): Unit = CdnServer(bytes).use { server ->
        server.modes["/a"] = Mode.Paced(bytesPerSecond = 4 * MIB)
        val source = source(Links(link(server, "/a")))
        val positions = (0 until 12).map { it * 1_234_567L % (12 * MIB) }
        for (position in positions) {
            source.open(spec(position))
            source.readChecked(150_000, from = position)
            source.close()
        }
        assertEquals("no abort under a read that flows", 0, counts.aborts.get())
        assertEquals("one connection open at a time", 1, counts.most.get())
        assertEquals(positions, server.requests.map { it.from })
    }

    @Test
    fun aCloseWhileTheReaderWaitsOnASilentServerReturnsWithinASecond(): Unit = CdnServer(bytes).use { server ->
        server.modes["/a"] = Mode.Silent(after = 300_000, ms = 30_000)
        val source = source(Links(link(server, "/a")))
        source.open(spec(1_000))
        source.readChecked(300_000, from = 1_000)
        assertEquals(300_000L, settled())
        val started = System.nanoTime()
        source.close()
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("closed in $tookMs ms, not after the read time-out", tookMs < 1_000)
        val request = server.requests.single()
        waitUntil(1_000, "the server to see the connection go") { request.closedAt != 0L }
        assertEquals(0, counts.open.get())
    }

    /**
     * GRA-2, FGX-5: a close while the reader opens the file again after a cut, its answer's head held back by the CDN:
     * the close aborts the open under way, so that connection goes within a second, not when the CDN answers, and a seek's
     * open after it is the only one.
     */
    @Test
    fun aCloseWhileTheReaderOpensAgainLetsThatConnectionGo(): Unit = CdnServer(bytes).use { server ->
        server.modes["/a"] = Mode.CutThenHeld(after = 700_000)
        val source = source(Links(link(server, "/a")))
        source.open(spec(0))
        source.readChecked(700_000, from = 0)
        waitUntil(5_000, "the reader's open again to reach the server") { server.requests.size == 2 }
        val again = server.requests[1]
        val started = System.nanoTime()
        source.close()
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("closed in $tookMs ms", tookMs < 1_000)
        waitUntil(1_000, "the open again's connection to go") { again.closedAt != 0L }
        source.open(spec(5 * MIB))
        source.readChecked(100_000, from = 5 * MIB)
        source.close()
        assertEquals(listOf(0L, 700_000L, 5 * MIB), server.requests.map { it.from })
        assertTrue("the open again's connection gone before the seek's asked", again.closedAt < server.requests[2].arrivedAt)
    }

    /**
     * GRA-2: a close while the reader asks the service for a fresh link after a dead answer: once the service answers, the
     * reader opens nothing with the link, the source aborted.
     */
    @Test
    fun aCloseWhileTheReaderAsksForAFreshLinkSendsNothingAfterIt(): Unit = CdnServer(bytes).use { server ->
        server.modes["/a"] = Mode.Cut(after = 700_000, then = 403)
        server.modes["/b"] = Mode.File
        val asking = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val fresh = link(server, "/b")
        val links = Links(link(server, "/a"), {
            asking.countDown()
            answer.await(10, TimeUnit.SECONDS)
            fresh()
        })
        val source = source(links)
        source.open(spec(0))
        source.readChecked(700_000, from = 0)
        assertTrue("the reader asks for a fresh link", asking.await(5, TimeUnit.SECONDS))
        val started = System.nanoTime()
        source.close()
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("closed in $tookMs ms", tookMs < 1_000)
        answer.countDown()
        // What the reader would open the fresh link with comes within milliseconds: a second shows none came.
        Thread.sleep(1_000)
        assertEquals(listOf("/a", "/a"), server.requests.map { it.path })
        assertEquals("the first link, and one refresh", 2, links.asked.size)
    }

    @Test
    fun fromTheFilesFirstByteNoMoreThan2MiBIsPulledUntil1MiBIsTaken(): Unit = CdnServer(bytes).use { server ->
        server.modes["/a"] = Mode.File
        val source = source(Links(link(server, "/a")))
        assertEquals(bytes.size.toLong(), source.open(spec(0)))
        // The player sees the placeholder and the file's type, as from the inner source.
        assertEquals(placeholder, source.uri)
        assertEquals(listOf("video/x-matroska"), source.responseHeaders["Content-Type"])
        assertEquals(2 * MIB, settled())
        source.readChecked(MIB - 1, from = 0)
        assertEquals("2 MiB ahead of what was taken, to its chunk", 3 * MIB, settled())
        source.readChecked(1, from = MIB - 1)
        assertEquals("the budget ahead once 1 MiB is taken", MIB + BUDGET, settled())
        source.close()
    }

    @Test
    fun aSeeksOpenReadsAheadTheWholeBudget(): Unit = CdnServer(bytes).use { server ->
        server.modes["/a"] = Mode.File
        val source = source(Links(link(server, "/a")))
        source.open(spec(0))
        source.readChecked(10, from = 0)
        source.close()
        val before = settled()
        source.open(spec(5 * MIB))
        assertEquals("the budget at once, nothing taken", BUDGET, settled() - before)
        source.readChecked(MIB, from = 5 * MIB)
        assertEquals("kept full", MIB + BUDGET, settled() - before)
        source.close()
    }

    @Test
    fun aServerSilentFor6sMidBodyIsRiddenOnTheBytesQueued(): Unit = CdnServer(bytes).use { server ->
        server.modes["/a"] = Mode.Silent(after = 8 * MIB, ms = 6_000)
        val source = source(Links(link(server, "/a")))
        source.open(spec(MIB))
        assertEquals(BUDGET, settled())
        // At half a MiB a second the silence comes about 4 s in, with 6 MiB, 12 s of it, queued.
        val longest = source.readChecked(7 * MIB, from = MIB, pace = MIB / 2)
        val request = server.requests.single()
        assertTrue("the server went silent mid-body", request.silencedAt != 0L)
        assertTrue("no read waited out the silence: the longest took $longest ms", longest < 500)
        source.close()
    }

    @Test
    fun aCutAfterSomeBytesIsOpenedAgainAtItsByte(): Unit = CdnServer(bytes).use { server ->
        server.modes["/a"] = Mode.Cut(after = 1_500_000)
        val source = source(Links(link(server, "/a")))
        source.open(spec(2_000))
        source.readChecked(bytes.size - 2_000L, from = 2_000)
        assertEquals(-1, source.read(ByteArray(10), 0, 10))
        source.close()
        assertEquals("opened again at the byte reached", listOf(2_000L, 1_502_000L), server.requests.map { it.from })
    }

    @Test
    fun aFreshLinkTheServiceRefusesFailsTheReadAtItsByteAsLinkUnavailable(): Unit = CdnServer(bytes).use { server ->
        server.modes["/a"] = Mode.Cut(after = 700_000, then = 403)
        val links = Links(link(server, "/a"), { throw LinkUnavailable(LinkFailure.KeyRefused("TorBox")) })
        val source = source(links)
        source.open(spec(0))
        source.readChecked(700_000, from = 0)
        val e = assertThrows(LinkUnavailable::class.java) { source.read(ByteArray(10), 0, 10) }
        assertEquals(LinkFailure.KeyRefused("TorBox"), e.failure)
        source.close()
        assertEquals("the first link, and one refresh", 2, links.asked.size)
        assertEquals(listOf(0L, 700_000L), server.requests.map { it.from })
    }

    @Test
    fun thePlayersTakesReachTheStreamCostMeterNotWhatWasReadAhead(): Unit = CdnServer(bytes).use { server ->
        server.modes["/a"] = Mode.File
        val traffic = StreamTraffic()
        val services = ServiceDataSource.Factory(Links(link(server, "/a")), traffic)
        val source = ReadAheadDataSource.Factory(services) { BUDGET }.createDataSource()
        source.open(spec(3 * MIB))
        // The read-ahead fills meanwhile: none of that is the player's.
        Thread.sleep(1_000)
        source.readChecked(100_000, from = 3 * MIB)
        assertEquals(100_000L, traffic.snapshot().totalBytes)
        assertEquals(1, traffic.snapshot().streams.size)
        source.close()
        assertEquals(1L, traffic.snapshot().endedStreams)
        assertEquals(100_000L, traffic.snapshot().totalBytes)
    }

    private companion object {
        const val MIB = 1024L * 1024
        const val BUDGET = 6 * MIB
    }
}
