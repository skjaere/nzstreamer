package io.skjaere.usenet.core

import io.skjaere.nzbstreamer.NzbStreamer
import io.skjaere.nzbstreamer.config.MissingArticlePolicy
import io.skjaere.nzbstreamer.config.SegmentCacheConfig
import io.skjaere.nzbstreamer.config.StreamingConfig
import io.skjaere.nzbstreamer.stream.CacheUse
import io.skjaere.nzbstreamer.stream.SegmentCache
import io.skjaere.nzbstreamer.stream.StreamOptions
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createTempDirectory
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Streams through nzb-streamer from a server, as the player does: how a stream that can't be
 * finished ends is what the player goes by (UsenetStreamReader, UsenetLoadErrorHandlingPolicy).
 * An article missing on every provider doesn't end it: the engine's streams fill it with zeros of
 * its size and go on, recording where in the file ([OpenedStream.gaps]) before the zeros come, so
 * the player can stop in front of them. A dropped connection ends it as a connection failure, which
 * the player retries. What lies in front of the failure is delivered first.
 */
class NzbStreamOpenerTest {
    private val clip = TestClip()
    private val gap = 3

    /** Opened once the reader has everything in front of the gap: the gap's answer waits for it, so it can't overtake those bytes. */
    private val inFrontRead = CountDownLatch(1)

    private val engine = UsenetEngine()
    private val servers = mutableListOf<FakeNntpServer>()

    @AfterTest
    fun tearDown() {
        inFrontRead.countDown()
        engine.close()
        servers.forEach { it.close() }
    }

    /** Serves the segments as [body] says, by index; the gap's answer waits until the reader has what is in front of it. */
    private suspend fun serve(body: (Int) -> FakeNntpServer.Body) {
        val server = FakeNntpServer(body = { id ->
            val i = clip.index(id)
            if (i == gap) inFrontRead.await(30, TimeUnit.SECONDS)
            body(i)
        })
        servers += server
        engine.configure(EngineSettings(listOf(server.provider())))
    }

    private suspend fun stream(): Pair<ByteArray, Throwable?> = withTimeout(30_000) {
        NzbStreamOpener(engine).open(clip.nzb, 0, 0L until clip.data.size.toLong()).readToEnd { read ->
            if (read >= gap * clip.segmentSize) inFrontRead.countDown()
        }
    }

    /** The gap's article, as offsets in the file. */
    private val gapBytes = gap * clip.segmentSize.toLong() until (gap + 1) * clip.segmentSize.toLong()

    /** The clip as the engine streams it with the gap's article missing: zeros in its place. */
    private val zeroFilled = clip.data.copyOf().also { it.fill(0, gapBytes.first.toInt(), gapBytes.last.toInt() + 1) }

    /** What a stream delivered, how it ended, its gaps as they were when the first byte at or past the gap's start was read, and in the end. */
    private class Streamed(val received: ByteArray, val cause: Throwable?, val knownThen: List<LongRange>?, val known: List<LongRange>)

    /** Streams the file from [from] to its end; the gap's answer comes once the bytes in front of it are read. */
    private suspend fun streamFrom(from: Long): Streamed = withTimeout(30_000) {
        if (from >= gapBytes.first) inFrontRead.countDown()
        val opened = NzbStreamOpener(engine).open(clip.nzb, 0, from until clip.data.size.toLong())
        var knownThen: List<LongRange>? = null
        val (received, cause) = opened.readToEnd { read ->
            if (from + read >= gapBytes.first) inFrontRead.countDown()
            if (from + read > gapBytes.first && knownThen == null) knownThen = opened.gaps.all()
        }
        Streamed(received, cause, knownThen, opened.gaps.all())
    }

    /** That [s] read on through the gap, its zeros in place from [from] on, and recorded [gap] before they came. */
    private fun assertFilled(s: Streamed, from: Long, gap: LongRange) {
        assertNull(s.cause, "the stream ended with ${s.cause}")
        assertContentEquals(zeroFilled.copyOfRange(from.toInt(), zeroFilled.size), s.received)
        assertEquals(listOf(gap), s.knownThen, "the gaps known as the first zeros were read")
        assertEquals(listOf(gap), s.known)
    }

    @Test
    fun `an article missing on every provider is filled with zeros of its size, recorded before they come`() = runBlocking<Unit> {
        serve { i -> if (i == gap) FakeNntpServer.Body.Missing else FakeNntpServer.Body.Article(clip.article(i)) }
        assertFilled(streamFrom(0), 0, gapBytes)
    }

    @Test
    fun `a stream of part of the file records its gaps as offsets in the file`() = runBlocking<Unit> {
        serve { i -> if (i == gap) FakeNntpServer.Body.Missing else FakeNntpServer.Body.Article(clip.article(i)) }
        val from = (gap - 1) * clip.segmentSize + 100L
        assertFilled(streamFrom(from), from, gapBytes)
    }

    /** A seek into the gap: the stream fills, and records, only the part of it from where it starts. */
    @Test
    fun `a stream that starts inside a missing article records the gap from its start`() = runBlocking<Unit> {
        serve { i -> if (i == gap) FakeNntpServer.Body.Missing else FakeNntpServer.Body.Article(clip.article(i)) }
        val from = gapBytes.first + 50
        assertFilled(streamFrom(from), from, from..gapBytes.last)
    }

    @Test
    fun `a connection that keeps dropping mid-article ends the stream as a connection failure`() = runBlocking<Unit> {
        serve { i -> if (i == gap) FakeNntpServer.Body.Dropped(clip.article(i), bytes = 2_000) else FakeNntpServer.Body.Article(clip.article(i)) }
        val (received, cause) = stream()
        assertContentEquals(clip.data.copyOf(gap * clip.segmentSize), received)
        val error = cause?.toUsenetError()
        // Either is retried by the player; a missing or corrupt article would not be.
        assertTrue(error is UsenetError.ConnectionFailed || error is UsenetError.Network, "ended with $error: $cause")
    }

    /**
     * A download's opener (item 6, L343): its streams run in the engine's download scope, which no use of the streamer
     * counts (L347), and fetch as [NzbStreamOpener.DOWNLOAD_OPTIONS] say, below every play's step. They read the segment
     * cache but store nothing in it, and fill an article no provider has with zeros whatever the streamer's own policy,
     * while a play's stream on the same streamer stores what it fetches and keeps to that policy.
     */
    @Test
    fun `the download opener passes its options, its streams in the download scope`() = runBlocking<Unit> {
        assertEquals(
            StreamOptions(priority = StreamOptions.BACKGROUND_PRIORITY, cache = CacheUse.READ_ONLY, missingArticles = MissingArticlePolicy.ZERO_FILL),
            NzbStreamOpener.DOWNLOAD_OPTIONS,
        )
        val dir = createTempDirectory("opener-cache")
        val cache = SegmentCache(SegmentCacheConfig(dir, maxBytes = 64L * 1024 * 1024))
        // A streamer that fails a stream at an article no provider has, as nzb-streamer's default does.
        val strict = UsenetEngine(
            factory = { s ->
                NzbStreamer.fromConfig(
                    s.orderedProviders.map { it.toNntpConfig() }, StreamingConfig(missingArticles = MissingArticlePolicy.FAIL),
                    segmentCache = cache, closeSegmentCache = false,
                )
            },
        )
        try {
            val server = FakeNntpServer(body = { id ->
                val i = clip.index(id)
                if (i == gap) inFrontRead.await(30, TimeUnit.SECONDS)
                if (i == gap) FakeNntpServer.Body.Missing else FakeNntpServer.Body.Article(clip.article(i))
            })
            servers += server
            strict.configure(EngineSettings(listOf(server.provider())))
            fun bodies(i: Int) = server.commands.count { it == "BODY <seg$i@test>" }
            val whole = 0L until clip.data.size.toLong()

            val download = withTimeout(30_000) { NzbStreamOpener.download(strict).open(clip.nzb, 0, whole) }
            // Held at the gap: the stream is open, in the download scope alone.
            assertEquals(1, strict.downloadScope.coroutineContext.job.children.count(), "download streams open")
            assertEquals(0, strict.sessionScope.coroutineContext.job.children.count(), "play streams open")
            val (received, cause) = withTimeout(30_000) {
                download.readToEnd { read -> if (read >= gap * clip.segmentSize) inFrontRead.countDown() }
            }
            assertNull(cause, "the download's stream ended with $cause")
            assertContentEquals(zeroFilled, received)
            assertEquals(listOf(gapBytes), download.gaps.all())
            assertEquals(0, cache.stats().entryCount, "segments the download's stream stored")

            // A play's stream on the same streamer: the streamer's policy, and what it fetches stored. Its gap's answer no
            // longer waits for the reader, so what it hadn't read of the bytes in front of it goes with the failure.
            val (played, failed) = withTimeout(30_000) { NzbStreamOpener(strict).open(clip.nzb, 0, whole).readToEnd() }
            assertTrue(played.size <= gap * clip.segmentSize, "the play's stream read ${played.size} bytes")
            assertContentEquals(clip.data.copyOf(played.size), played)
            assertIs<UsenetError.MissingArticle>(failed?.toUsenetError(), "the play's stream ended with $failed")
            assertNotNull(cache.get("seg0@test"), "the play's stream stored nothing")

            // What the cache holds, the download's stream reads from it: no BODY for it.
            val before = bodies(0)
            val (again, _) = withTimeout(30_000) { NzbStreamOpener.download(strict).open(clip.nzb, 0, whole).readToEnd() }
            assertContentEquals(zeroFilled, again)
            assertEquals(before, bodies(0), "BODYs of the cached segment")
        } finally {
            strict.close()
            cache.close()
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * The file's name is the release's, as it was posted: the library names each stream by it in its log lines (its first
     * byte, each article it fills with zeros), and a log shared from Settings › Logs carries them. A download's streams are
     * named by the file's place alone; a play's keep the name, as they always have.
     */
    @Test
    fun `a download's streams leave the file's name out of the log, where a play's name it`() = runBlocking<Unit> {
        serve { i -> if (i == gap) FakeNntpServer.Body.Missing else FakeNntpServer.Body.Article(clip.article(i)) }
        // In any order: nothing here reads the bytes in front of the gap first.
        inFrontRead.countDown()
        val named = PreparedNzb(clip.nzb.id, clip.nzb.title, clip.nzb.metadata, listOf(FileCandidate(0, clip.nzb.candidates.single().file.copy(path = RELEASE_FILE))))
        val whole = 0L until clip.data.size.toLong()

        val (downloaded, log) = stderrOf { withTimeout(30_000) { NzbStreamOpener.download(engine).open(named, 0, whole).readToEnd() } }
        assertContentEquals(zeroFilled, downloaded.first)
        // The library's lines were there to be read: the stream's first byte, and the article it filled.
        assertTrue("Stream startup trace" in log && "zero bytes in its place" in log, log)
        assertFalse(RELEASE_FILE in log, log)
        assertFalse("Harbor.Lights" in log, log)

        val (_, played) = stderrOf { withTimeout(30_000) { NzbStreamOpener(engine).open(named, 0, whole).readToEnd() } }
        assertTrue(RELEASE_FILE in played, played)
    }

    /** What [block] logs: slf4j-simple writes to System.err as it is at the time. */
    private suspend fun <T> stderrOf(block: suspend () -> T): Pair<T, String> {
        val original = System.err
        val captured = ByteArrayOutputStream()
        System.setErr(PrintStream(captured, true))
        try {
            return block() to captured.toString()
        } finally {
            System.setErr(original)
        }
    }

    private companion object {
        /** A posted file's name, as a release names it (invented). */
        const val RELEASE_FILE = "Harbor.Lights.S02E04.1080p.WEB.h264-KESTREL.mkv"
    }
}
