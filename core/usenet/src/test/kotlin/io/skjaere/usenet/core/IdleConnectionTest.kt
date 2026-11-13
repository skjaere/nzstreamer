package io.skjaere.usenet.core

import io.ktor.utils.io.readFully
import io.skjaere.nzbstreamer.config.PoolTuning
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A play's stream that rests while a NAT, a VPN or the provider drops its idle connections without a word reads on to
 * the end once the player reads again: the engine's pools check a connection that sat idle before they lend it
 * ([EngineSettings.poolTuning]), and a segment whose fetch got no answer is fetched again on another connection
 * ([EngineSettings.segmentTimeoutRetries]). Without them the fetch on a dead connection waited out its timeout and
 * failed the stream. Through the real engine and opener against a [FakeNntpServer], with a clip of 32 articles of
 * 256 KiB: the stream's writer then rests on a full channel and read-ahead well before the end, as a play's does.
 */
class IdleConnectionTest {
    private val clip = TestClip(segmentSize = 256 * 1024, count = 32)
    private val engine = UsenetEngine()
    private val servers = mutableListOf<FakeNntpServer>()
    /** Holds back a BODY that is never to be answered, until the test ends. */
    private val never = CountDownLatch(1)

    @AfterTest
    fun tearDown() {
        never.countDown()
        engine.close()
        servers.forEach { it.close() }
    }

    /** Serves the clip, its connections going silent after [idleAfterMs] idle; [body] may hold an article back. */
    private fun serve(idleAfterMs: Long? = null, body: (Int) -> Unit = {}) = FakeNntpServer(
        has = { it.startsWith("<seg") },
        body = { id -> clip.index(id).let { i -> body(i); FakeNntpServer.Body.Article(clip.article(i)) } },
        idleAfterMs = idleAfterMs,
    ).also { servers += it }

    /** The app's settings, defaults and all, on [server], fetching three segments at once and three ahead. */
    private fun settings(server: FakeNntpServer) = EngineSettings(listOf(server.provider()), concurrency = 3, readAheadSegments = 3)

    @Test
    fun `a stream that rests past the provider's silence reads on to the end`() = runBlocking<Unit> {
        val server = serve(idleAfterMs = 300)
        // The app's timings, but for the check before use: the server's connections die after 300 ms idle, where a NAT's
        // or a VPN's do after tens of seconds.
        engine.configure(settings(server).copy(poolTuning = EngineSettings.POOL_TUNING.copy(validateAfterIdleMs = 100, validateTimeoutMs = 300)))
        val stream = NzbStreamOpener(engine).open(clip.nzb, 0, 0L until clip.data.size.toLong())
        val head = ByteArray(2 shl 20)
        withTimeout(20_000) { stream.channel.readFully(head) }
        // The writer fills the channel and its read-ahead at once, and then its connections sit idle past the silence:
        // what it fetches once the reader reads on goes to them first.
        delay(600)
        val opened = server.connections.get()

        val (rest, cause) = withTimeout(30_000) { stream.readToEnd() }

        assertNull(cause, "the stream ended with $cause")
        assertContentEquals(clip.data, head + rest)
        assertTrue(server.silenced.get() > 0, "no fetch after the rest met a dead connection")
        assertTrue(server.connections.get() > opened, "the dead connections weren't replaced")
    }

    /** A connection that dies while in use: its fetch gets no answer, and costs one fetch timeout and a retry. */
    @Test
    fun `a segment whose fetch gets no answer is fetched again on another connection`() = runBlocking<Unit> {
        val asked = AtomicInteger()
        val server = serve { i -> if (i == 3 && asked.incrementAndGet() == 1) never.await() }
        engine.configure(settings(server).copy(segmentFetchTimeoutMs = 500))

        val (received, cause) = withTimeout(30_000) { NzbStreamOpener(engine).open(clip.nzb, 0, 0L until clip.data.size.toLong()).readToEnd() }

        assertNull(cause, "the stream ended with $cause")
        assertContentEquals(clip.data, received)
        assertEquals(2, asked.get())
    }

    /**
     * With several providers the retry stays on the one the fetch timed out on: a connection that died in use there
     * costs one fetch timeout and no fetch from the next, which may be a block account paid by the gigabyte, or lack the
     * article.
     */
    @Test
    fun `with two providers a segment whose fetch gets no answer is fetched again on the first`() = runBlocking<Unit> {
        val asked = AtomicInteger()
        val first = serve { i -> if (i == 3 && asked.incrementAndGet() == 1) never.await() }
        val second = serve()
        val providers = listOf(
            first.provider().copy(id = "first", name = "first", priority = 0),
            second.provider().copy(id = "second", name = "second", priority = 1),
        )
        engine.configure(EngineSettings(providers, concurrency = 3, readAheadSegments = 3, segmentFetchTimeoutMs = 500))

        val (received, cause) = withTimeout(30_000) { NzbStreamOpener(engine).open(clip.nzb, 0, 0L until clip.data.size.toLong()).readToEnd() }

        assertNull(cause, "the stream ended with $cause")
        assertContentEquals(clip.data, received)
        assertEquals(2, asked.get())
        assertTrue(second.commands.none { it.startsWith("BODY ") }, "the second provider was asked: ${second.commands}")
    }

    /** The app's values (L4), so a change to them is a change to this. */
    @Test
    fun `the app's pools are tuned and a timed-out segment is fetched once more`() {
        val settings = EngineSettings(emptyList())
        assertEquals(PoolTuning(keepaliveIntervalMs = 30_000, keepaliveTimeoutMs = 3_000, validateAfterIdleMs = 10_000, validateTimeoutMs = 2_000), settings.poolTuning)
        assertEquals(1, settings.segmentTimeoutRetries)
        assertEquals(5_000, settings.segmentFetchTimeoutMs)
    }
}
