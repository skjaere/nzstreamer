package io.skjaere.usenet.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
}
