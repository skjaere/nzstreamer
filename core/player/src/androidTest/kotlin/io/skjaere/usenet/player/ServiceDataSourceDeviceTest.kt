package io.skjaere.usenet.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.core.StreamTraffic
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * A debrid play's source ([ServiceDataSource], L172, W81) over Media3's own HTTP source, against a server of its own on
 * this device's loopback address that counts what it is asked and what it sends: it asks for the file from where the
 * read starts itself, Media3 adding no Range of its own; a dead link gets one fresh link and opens again at the same
 * byte, a second dead answer or a refresh the service refuses being final; a 200 to a ranged read fails at once, having
 * read nothing past the headers; a link over 50 minutes old is replaced at the next open; the probe and the player
 * opening together ask for one link; the player sees the placeholder URI, never the link; the bytes reach the
 * stream-cost meter; and a body cut midway is opened again by Media3 at its byte. On a device: the JVM's android.jar
 * can't make the [Uri] a [DataSpec] holds. Invented names; the media is the module's own synthetic fixture.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class ServiceDataSourceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** One request the server answered: its path, its headers in their order, and the body bytes it wrote. */
    private class Request(val path: String, val headers: List<Pair<String, String>>) {
        @Volatile
        var sent = 0L

        fun header(name: String): List<String> = headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

        /** Where its Range starts; 0 without one. */
        val from: Long get() = header("Range").singleOrNull()?.let { Regex("""bytes=(\d+)-\d*""").matchEntire(it)?.groupValues?.get(1)?.toLong() } ?: 0
    }

    /** How the server answers a path. */
    private sealed interface Mode {
        /** The file, from where its Range says (206), or whole without one (200). */
        data object File : Mode

        /** [status], as a link that no longer works answers. */
        data class Dead(val status: Int) : Mode

        /** A page in place of the file, with a 200. */
        data object Page : Mode

        /** The whole file with a 200 whatever the Range asks: its first 64 KB, and the rest only if the reader stays 3 s. */
        data object Whole : Mode

        /** As [File], but its first answer's body cut after [after] bytes. */
        data class Cut(val after: Long) : Mode
    }

    /**
     * An HTTP/1.1 server of [bytes] on 127.0.0.1, one request a connection, answering each path as [modes] says, with the
     * strong ETag [etags] gives the path ("f1" by default): a ranged read whose If-Range names another is answered whole.
     */
    private class CdnServer(val bytes: ByteArray) : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val modes = ConcurrentHashMap<String, Mode>()
        val etags = ConcurrentHashMap<String, String>()
        val requests = CopyOnWriteArrayList<Request>()
        private val cut = ConcurrentHashMap.newKeySet<String>()

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
            val request = Request(line.split(' ')[1], headers).also { requests += it }
            val out = connection.getOutputStream()
            when (val mode = modes[request.path] ?: Mode.Dead(404)) {
                is Mode.Dead -> answer(out, mode.status, "text/plain", "Gone.".toByteArray())
                Mode.Page -> answer(out, 200, "text/html; charset=utf-8", "<html><body>Sign in</body></html>".toByteArray())
                Mode.File -> file(out, request, cutAfter = null)
                is Mode.Cut -> file(out, request, cutAfter = mode.after.takeIf { cut.add(request.path) })
                Mode.Whole -> {
                    head(out, 200, bytes.size.toLong())
                    body(out, request, 0, FIRST.toLong())
                    // A reader that took the 200 for the file reads on; one that gave up closes, which reads as the end.
                    connection.soTimeout = 3_000
                    val left = runCatching { input.read() == -1 }.getOrDefault(false)
                    if (!left) body(out, request, FIRST.toLong(), bytes.size.toLong())
                }
            }
        }

        private fun file(out: OutputStream, request: Request, cutAfter: Long?) {
            val etag = etags[request.path] ?: ETAG
            // RFC 7233: a validator that isn't this file's own gets the whole file.
            val range = request.header("Range").singleOrNull()?.let { Regex("""bytes=(\d+)-(\d*)""").matchEntire(it) }
                ?.takeIf { request.header("If-Range").all { it == etag } }
            if (range == null) {
                head(out, 200, bytes.size.toLong(), etag = etag)
                body(out, request, 0, cutAfter ?: bytes.size.toLong())
                return
            }
            val start = range.groupValues[1].toLong()
            val end = range.groupValues[2].takeIf { it.isNotEmpty() }?.toLong()?.coerceAtMost(bytes.size - 1L) ?: (bytes.size - 1L)
            if (start >= bytes.size) return head(out, 416, 0, "Content-Range: bytes */${bytes.size}", etag = etag)
            head(out, 206, end - start + 1, "Content-Range: bytes $start-$end/${bytes.size}", etag = etag)
            body(out, request, start, cutAfter?.let { minOf(start + it, end + 1) } ?: (end + 1))
        }

        private fun head(out: OutputStream, status: Int, length: Long, vararg extra: String, etag: String = ETAG) {
            val lines = listOf("HTTP/1.1 $status Answer", "Content-Type: application/octet-stream", "Content-Length: $length", "Accept-Ranges: bytes", "ETag: $etag", "Connection: close") + extra
            out.write((lines.joinToString("\r\n") + "\r\n\r\n").toByteArray())
            out.flush()
        }

        private fun answer(out: OutputStream, status: Int, type: String, body: ByteArray) {
            out.write("HTTP/1.1 $status Answer\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body)
            out.flush()
        }

        private fun body(out: OutputStream, request: Request, from: Long, until: Long) {
            var at = from
            while (at < until) {
                val n = minOf(CHUNK.toLong(), until - at).toInt()
                out.write(bytes, at.toInt(), n)
                out.flush()
                at += n
                request.sent += n
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
            const val FIRST = 64 * 1024
            const val CHUNK = 16 * 1024
            const val ETAG = "\"f1\""
        }
    }

    /** A resolver that hands out [links] in turn (the last again after them), recording the stale link each call came with. */
    private class Links(private vararg val links: () -> HttpLink) : LinkResolver {
        val asked = CopyOnWriteArrayList<HttpLink?>()

        /** While set, each call waits for it first. */
        @Volatile
        var gate: CountDownLatch? = null

        override fun link(target: PlayUris.Target.Service, stale: HttpLink?): HttpLink {
            asked += stale
            gate?.await(10, TimeUnit.SECONDS)
            return links[minOf(asked.size, links.size) - 1]()
        }
    }

    private val bytes = ByteArray(5_000_000) { (it * 31 + 7).toByte() }
    private val placeholder = Uri.parse(PlayUris.service("0f3e9a1c5b7d2e4f6a8b0c1d2e3f4a5b", 2))

    private fun spec(position: Long = 0, ifRange: String? = null) =
        DataSpec.Builder().setUri(placeholder).setPosition(position).setHttpRequestHeaders(listOfNotNull(ifRange?.let { "If-Range" to it }).toMap()).build()

    private fun link(server: CdnServer, path: String, madeAtMs: Long = System.currentTimeMillis()): () -> HttpLink =
        { HttpLink(server.url(path), madeAtMs, "Harbor.Lights.S01E02.1080p.WEB-DL.mkv") }

    private fun DataSource.readAll(): ByteArray {
        val all = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = read(buffer, 0, buffer.size)
            if (n == C.RESULT_END_OF_INPUT) return all.toByteArray()
            all.write(buffer, 0, n)
        }
    }

    private fun waitUntil(timeoutMs: Long, what: String, done: () -> Boolean) {
        val until = System.nanoTime() + timeoutMs * 1_000_000
        while (!done()) {
            if (System.nanoTime() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(50)
        }
    }

    @Test
    fun aReadFromAPlaceAsksForItFromThereAloneAndReadsTheFileFromThere(): Unit = CdnServer(bytes).use { server ->
        server.modes["/a"] = Mode.File
        val source = ServiceDataSource.Factory(Links(link(server, "/a"))).createDataSource()
        assertEquals(bytes.size - 1_000L, source.open(spec(1_000)))
        assertArrayEquals(bytes.copyOfRange(1_000, bytes.size), source.readAll())
        // The player and its events see the placeholder, and its type is the file's name's, whatever the CDN says.
        assertEquals(placeholder, source.uri)
        assertEquals(listOf("video/x-matroska"), source.responseHeaders["Content-Type"])
        source.close()
        val request = server.requests.single()
        assertEquals("one Range, the source's own: ${request.headers}", listOf("bytes=1000-"), request.header("Range"))
        assertEquals(listOf("identity"), request.header("Accept-Encoding"))
        assertTrue("no Authorization to a CDN", request.header("Authorization").isEmpty())
    }

    @Test
    fun aDeadLinkGetsOneFreshLinkThatOpensAgainAtTheSameByte(): Unit = CdnServer(bytes).use { server ->
        server.modes["/expired"] = Mode.Dead(403)
        server.modes["/a"] = Mode.File
        val links = Links(link(server, "/expired"), link(server, "/a"))
        val source = ServiceDataSource.Factory(links).createDataSource()
        assertEquals(bytes.size - 2_000L, source.open(spec(2_000)))
        assertArrayEquals(bytes.copyOfRange(2_000, 2_100), source.readAll().copyOfRange(0, 100))
        assertEquals(placeholder, source.uri)
        source.close()
        // The first call had no link; the one refresh came with the dead one.
        assertEquals(2, links.asked.size)
        assertNull(links.asked[0])
        assertEquals(server.url("/expired"), links.asked[1]?.url)
        assertEquals(listOf("/expired", "/a"), server.requests.map { it.path })
        assertEquals(listOf(2_000L, 2_000L), server.requests.map { it.from })
    }

    /**
     * DFH-2: Media3 asks a reopen within one load If-Range the ETag of the open before (an extractor's seek inside the file).
     * Once the link is another (a dead one refreshed, an old one replaced), that validator is another URL's: a CDN node
     * whose ETag differs would answer 200 with the whole file, which fails at once. The source sends it only to the link
     * whose answer gave it.
     */
    @Test
    fun aReopenOnAFreshLinkAsksNoIfRangeOfTheLinkBefore(): Unit = CdnServer(bytes).use { server ->
        server.modes["/a"] = Mode.File
        server.modes["/b"] = Mode.File
        server.etags["/b"] = "\"f2\""
        val source = ServiceDataSource.Factory(Links(link(server, "/a"), link(server, "/b"))).createDataSource()
        source.open(spec(0))
        val etag = checkNotNull(ServiceDataSource.header(source.responseHeaders, "ETag"))
        source.close()
        // The same link: Media3's If-Range goes with the reopen, and the part comes.
        assertEquals(bytes.size - 1_000L, source.open(spec(1_000, ifRange = etag)))
        source.close()
        // The link dies there: the fresh one, from a node with another ETag, is asked the part without the old validator.
        server.modes["/a"] = Mode.Dead(403)
        assertEquals(bytes.size - 2_000L, source.open(spec(2_000, ifRange = etag)))
        assertArrayEquals(bytes.copyOfRange(2_000, 2_100), source.readAll().copyOfRange(0, 100))
        source.close()
        assertEquals(listOf("/a", "/a", "/a", "/b"), server.requests.map { it.path })
        assertEquals(listOf(emptyList(), listOf(etag), listOf(etag), emptyList<String>()), server.requests.map { it.header("If-Range") })
    }

    @Test
    fun aSecondDeadAnswerIsFinal(): Unit = CdnServer(bytes).use { server ->
        server.modes["/expired"] = Mode.Dead(403)
        server.modes["/gone"] = Mode.Dead(404)
        val links = Links(link(server, "/expired"), link(server, "/gone"))
        val source = ServiceDataSource.Factory(links).createDataSource()
        val e = assertThrows(LinkUnavailable::class.java) { source.open(spec(0)) }
        source.close()
        assertEquals(LinkFailure.NoWorkingLink, e.failure)
        assertEquals("one refresh, no more", 2, links.asked.size)
    }

    @Test
    fun aRefreshTheServiceRefusesFailsWithItsOwnKind(): Unit = CdnServer(bytes).use { server ->
        server.modes["/expired"] = Mode.Dead(410)
        for (refused in listOf(LinkFailure.KeyRefused("TorBox"), LinkFailure.Limit(waitMs = 1_200_000))) {
            val links = Links(link(server, "/expired"), { throw LinkUnavailable(refused) })
            val source = ServiceDataSource.Factory(links).createDataSource()
            val e = assertThrows(LinkUnavailable::class.java) { source.open(spec(5_000)) }
            source.close()
            assertEquals(refused, e.failure)
        }
    }

    @Test
    fun a200ToARangedReadFailsAtOnceHavingReadNothingPastTheHeaders(): Unit = CdnServer(bytes).use { server ->
        server.modes["/whole"] = Mode.Whole
        val links = Links(link(server, "/whole"))
        val source = ServiceDataSource.Factory(links).createDataSource()
        val started = System.nanoTime()
        val e = assertThrows(LinkUnavailable::class.java) { source.open(spec(1_000_000)) }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        source.close()
        assertEquals(LinkFailure.NoWorkingLink, e.failure)
        assertTrue("failed at once, not after reading to the place: $tookMs ms", tookMs < 2_000)
        assertEquals("not refreshed for", 1, links.asked.size)
        // Settled once the server saw the reader go: no more than what it wrote before asking.
        waitUntil(5_000, "the server to finish") { server.requests.single().sent > 0 }
        Thread.sleep(3_500)
        val sent = server.requests.single().sent
        assertTrue("the server sent $sent bytes of the body, not the million before the place", sent <= 64 * 1024)
    }

    /** A CDN busy for a moment (503, 429) isn't a dead link: Media3's to retry, as the load policy leaves it (L173). */
    @Test
    fun aCdnsBusyAnswerIsMedia3sToRetry(): Unit = CdnServer(bytes).use { server ->
        for (status in listOf(503, 429)) {
            server.modes["/busy"] = Mode.Dead(status)
            val links = Links(link(server, "/busy"))
            val source = ServiceDataSource.Factory(links).createDataSource()
            val e = assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) { source.open(spec(3_000)) }
            source.close()
            assertEquals(status, e.responseCode)
            assertFalse("$status", UsenetLoadErrorHandlingPolicy.isPermanent(e))
            assertEquals("not a dead link: no fresh one asked for", 1, links.asked.size)
        }
    }

    @Test
    fun aPageInPlaceOfTheFileCountsAsADeadLink(): Unit = CdnServer(bytes).use { server ->
        server.modes["/page"] = Mode.Page
        server.modes["/a"] = Mode.File
        val links = Links(link(server, "/page"), link(server, "/a"))
        val source = ServiceDataSource.Factory(links).createDataSource()
        assertEquals(bytes.size.toLong(), source.open(spec(0)))
        assertArrayEquals(bytes.copyOfRange(0, 100), source.readAll().copyOfRange(0, 100))
        source.close()
        assertEquals(2, links.asked.size)
        assertEquals(listOf("/page", "/a"), server.requests.map { it.path })
    }

    @Test
    fun aLinkOver50MinutesOldIsReplacedAtTheNextOpenAndAYoungerOneKept(): Unit = CdnServer(bytes).use { server ->
        server.modes["/first"] = Mode.File
        server.modes["/second"] = Mode.File
        var now = 1_000_000_000L
        val links = Links({ HttpLink(server.url("/first"), now) }, { HttpLink(server.url("/second"), now) })
        val factory = ServiceDataSource.Factory(links, now = { now })
        fun openAndClose() = factory.createDataSource().run {
            open(spec(4_000))
            close()
        }
        openAndClose()
        now += 49 * 60_000L
        openAndClose()
        assertEquals("a link 49 minutes old opens", 1, links.asked.size)
        now += 2 * 60_000L
        openAndClose()
        assertEquals("one 51 minutes old is replaced", 2, links.asked.size)
        assertEquals(server.url("/first"), links.asked[1]?.url)
        assertEquals(listOf("/first", "/first", "/second"), server.requests.map { it.path })
    }

    @Test
    fun theProbeAndThePlayerOpeningTogetherAskForOneLink(): Unit = CdnServer(bytes).use { server ->
        server.modes["/a"] = Mode.File
        val links = Links(link(server, "/a")).apply { gate = CountDownLatch(1) }
        val factory = ServiceDataSource.Factory(links)
        val failures = CopyOnWriteArrayList<Throwable>()
        val opens = List(2) {
            val source = factory.createDataSource()
            thread {
                try {
                    source.open(spec(it * 10_000L))
                    source.close()
                } catch (e: Throwable) {
                    failures += e
                }
            }
        }
        waitUntil(5_000, "the first to ask") { links.asked.size >= 1 }
        // The second waits for the first's link meanwhile, rather than asking for one of its own.
        Thread.sleep(300)
        links.gate?.countDown()
        opens.forEach { it.join(10_000) }
        assertTrue("$failures", failures.isEmpty())
        assertEquals(1, links.asked.size)
        assertEquals(2, server.requests.size)
    }

    @Test
    fun theBytesReachTheStreamCostMeterAndTheLinkNeverTheUri(): Unit = CdnServer(bytes).use { server ->
        server.modes["/expired"] = Mode.Dead(401)
        server.modes["/a"] = Mode.File
        val traffic = StreamTraffic()
        val source = ServiceDataSource.Factory(Links(link(server, "/expired"), link(server, "/a")), traffic).createDataSource()
        source.open(spec(bytes.size - 100_000L))
        assertEquals(100_000, source.readAll().size)
        assertEquals(100_000L, traffic.snapshot().totalBytes)
        assertEquals(1, traffic.snapshot().streams.size)
        assertEquals(placeholder, source.uri)
        assertFalse(source.uri.toString().contains("127.0.0.1"))
        source.close()
        assertEquals(1L, traffic.snapshot().endedStreams)
        assertEquals(100_000L, traffic.snapshot().totalBytes)
    }

    @Test
    fun aBodyCutMidwayIsOpenedAgainByMedia3AtItsByte() {
        val media = instrumentation.context.assets.open("gaps-faststart.mp4").use { it.readBytes() }
        CdnServer(media).use { server ->
            server.modes["/cut"] = Mode.Cut(after = 40_000)
            val factory = ServiceDataSource.Factory(Links({ HttpLink(server.url("/cut"), System.currentTimeMillis(), "Harbor.Lights.S01E02.mp4") }))
            val failed = AtomicReference<PlaybackException?>()
            var player: ExoPlayer? = null
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(instrumentation.targetContext)
                    .setMediaSourceFactory(PlayerFactory.mediaSourceFactory(SourceDataSource.Factory(mapOf(PlayUris.SERVICE to factory))))
                    .build().apply {
                        addListener(object : Player.Listener {
                            override fun onPlayerError(error: PlaybackException) = failed.set(error)
                        })
                        setMediaItem(MediaItem.fromUri(placeholder))
                        prepare()
                    }
            }
            try {
                waitUntil(30_000, "the file read to its end") {
                    failed.get() != null || server.requests.size >= 2 && server.requests[1].let { it.from + it.sent == media.size.toLong() }
                }
                assertNull(failed.get())
                val again = server.requests[1]
                assertTrue("opened again at its byte, not from the start: ${again.from}", again.from in 1..40_000)
                assertEquals(40_000L, server.requests[0].sent)
            } finally {
                instrumentation.runOnMainSync { player?.release() }
            }
        }
    }
}
