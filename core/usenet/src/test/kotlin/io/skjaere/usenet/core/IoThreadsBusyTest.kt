package io.skjaere.usenet.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A connection's waits for its server hold their threads, so the engine's network work (a play's check, prepare and
 * stream) runs on ktor-nntp-client's own view of Dispatchers.IO, where they run in place and which IO's 64-thread
 * limit doesn't count: with every IO thread busy (the app's file and database work), a play still checks, prepares and
 * streams. Through the real engine, preparer, opener and pipeline against a [FakeNntpServer].
 */
class IoThreadsBusyTest {
    private val clip = TestClip()
    private val engine = UsenetEngine()
    private val servers = mutableListOf<FakeNntpServer>()

    @AfterTest
    fun tearDown() {
        engine.close()
        servers.forEach { it.close() }
    }

    @Test
    fun `a play checks, prepares and streams while every Dispatchers IO thread is busy`() = runBlocking<Unit> {
        val server = FakeNntpServer(has = { it.startsWith("<seg") }, body = { id -> FakeNntpServer.Body.Article(clip.article(clip.index(id))) })
        servers += server
        engine.configure(EngineSettings(listOf(server.provider())))
        // As many as IO runs at once (kotlinx.coroutines.io.parallelism, 64 by default), all held.
        val threads = 64
        val busy = CountDownLatch(threads)
        val release = CountDownLatch(1)
        val ioWork = CoroutineScope(Dispatchers.IO)
        repeat(threads) {
            ioWork.launch {
                busy.countDown()
                release.await()
            }
        }
        // The play runs apart from the test, which waits for it only so long: work waiting for an IO thread can't be
        // cancelled until it gets one, which would be after the test lets them go.
        val playing = CoroutineScope(Dispatchers.Default)
        try {
            assertTrue(busy.await(10, TimeUnit.SECONDS), "IO's threads never all got busy")
            val pipeline = usenetPipeline({ clip.nzbBytes }, EngineAvailabilityBackend(engine), NzbPreparer(EnginePrepareBackend(engine)))
            val played = withTimeoutOrNull(10_000) { playing.async { pipeline.play(listOf(PlayCandidate("clip1", "Clip 1"))).last() }.await() }
            assertNotNull(played, "the play's check and prepare got nowhere in 10 s while IO was busy")
            assertIs<PipelineEvent.Succeeded>(played)
            val streamed = withTimeoutOrNull(10_000) {
                playing.async { NzbStreamOpener(engine).open(clip.nzb, 0, 0L until clip.data.size.toLong()).readToEnd() }.await()
            }
            assertNotNull(streamed, "the stream got nowhere in 10 s while IO was busy")
            val (received, cause) = streamed
            assertNull(cause, "the stream ended with $cause")
            assertContentEquals(clip.data, received)
        } finally {
            release.countDown()
            ioWork.cancel()
            playing.cancel()
        }
    }
}
