package io.skjaere.usenet.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/**
 * A caller cancelled while the engine builds its streamer or opens a stream gets its cancellation
 * back, not a Usenet failure, and nothing it started is left running or open (#123); opening on a
 * session scope that is already cancelled fails instead of waiting for ever (#163).
 */
class EngineCancellationTest {
    private val clip = TestClip()
    private val servers = mutableListOf<FakeNntpServer>()
    private val engines = mutableListOf<UsenetEngine>()
    private val io = Executors.newFixedThreadPool(2).asCoroutineDispatcher()

    @AfterTest
    fun tearDown() {
        engines.forEach { it.close() }
        servers.forEach { it.close() }
        io.close()
    }

    private fun engine(factory: (EngineSettings) -> io.skjaere.nzbstreamer.NzbStreamer = UsenetEngine::defaultStreamer, release: (io.skjaere.nzbstreamer.NzbStreamer) -> Unit = { it.close() }) =
        UsenetEngine(factory, release, io).also { engines += it }

    private fun server() = clip.server().also { servers += it }

    /** Runs [block] in a job of its own and hands back what it threw (null if nothing), without failing this test's scope. */
    private fun kotlinx.coroutines.CoroutineScope.call(block: suspend () -> Unit): Pair<Job, CompletableDeferred<Throwable?>> {
        val thrown = CompletableDeferred<Throwable?>()
        val job = launch(Dispatchers.Default) {
            try {
                block()
                thrown.complete(null)
            } catch (e: Throwable) {
                thrown.complete(e)
            }
        }
        return job to thrown
    }

    @Test
    fun `a cancelled streamer call rethrows its cancellation, leaves the state as it was and leaks no streamer`() = runBlocking<Unit> {
        val built = AtomicInteger()
        val closed = AtomicInteger()
        val building = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val engine = engine(
            factory = { settings ->
                building.countDown()
                finish.await(10, TimeUnit.SECONDS)
                UsenetEngine.defaultStreamer(settings).also { built.incrementAndGet() }
            },
            release = { closed.incrementAndGet(); it.close() },
        )
        engine.configure(EngineSettings(listOf(ProviderConfig("p", "p", "127.0.0.1", 1, "", "", useTls = false))))
        val before = engine.state.value

        val (job, thrown) = call { engine.streamer() }
        withContext(Dispatchers.IO) { assertTrue(building.await(10, TimeUnit.SECONDS), "the build never started") }
        job.cancel()
        finish.countDown()
        assertIs<CancellationException>(withTimeout(10_000) { thrown.await() })
        job.join()
        assertEquals(before, engine.state.value)
        assertEquals(1, built.get())
        assertEquals(1, closed.get(), "the streamer built for the cancelled call was left open")

        // The next call builds again and keeps that one.
        val streamer = engine.streamer()
        assertIs<EngineState.Ready>(engine.state.value)
        assertEquals(2, built.get())
        assertTrue(streamer === engine.streamer())
        engine.close()
        assertEquals(2, closed.get())
    }

    @Test
    fun `a cancelled streamer call puts back the state it found, a failed build's included`() = runBlocking<Unit> {
        val builds = AtomicInteger()
        val building = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val engine = engine(
            factory = { settings ->
                if (builds.incrementAndGet() == 1) throw IllegalStateException("no selector")
                building.countDown()
                finish.await(10, TimeUnit.SECONDS)
                UsenetEngine.defaultStreamer(settings)
            },
        )
        engine.configure(EngineSettings(listOf(ProviderConfig("p", "p", "127.0.0.1", 1, "", "", useTls = false))))
        assertIs<UsenetException>(runCatching { engine.streamer() }.exceptionOrNull())
        // Not what streamer() sets while it builds, so a state left as it was set then shows.
        val failed = assertIs<EngineState.Error>(engine.state.value)

        // The build returns after the caller was cancelled: the streamer is closed, and the state put back.
        val (job, thrown) = call { engine.streamer() }
        withContext(Dispatchers.IO) { assertTrue(building.await(10, TimeUnit.SECONDS), "the build never started") }
        job.cancel()
        finish.countDown()
        assertIs<CancellationException>(withTimeout(10_000) { thrown.await() })
        job.join()
        assertEquals(failed, engine.state.value)
    }

    @Test
    fun `a configure call cancelled while the old streamer closes still leaves the engine on the new settings`() = runBlocking<Unit> {
        val closing = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val engine = engine(release = { closing.countDown(); finish.await(10, TimeUnit.SECONDS); it.close() })
        engine.configure(EngineSettings(listOf(ProviderConfig("p", "p", "127.0.0.1", 1, "", "", useTls = false))))
        engine.streamer()

        val (job, thrown) = call { engine.configure(EngineSettings(emptyList())) }
        withContext(Dispatchers.IO) { assertTrue(closing.await(10, TimeUnit.SECONDS), "the old streamer was never closed") }
        job.cancel()
        finish.countDown()
        assertIs<CancellationException>(withTimeout(10_000) { thrown.await() })
        job.join()
        assertEquals(EngineState.NotConfigured, engine.state.value)
        assertEquals(emptyList(), engine.configuredProviders.value)
    }

    @Test
    fun `a cancelled open throws its cancellation, and the stream it was starting never starts`() = runBlocking<Unit> {
        val server = server()
        val engine = engine()
        engine.configure(EngineSettings(listOf(server.provider())))
        engine.streamer()
        // Hold both IO threads, so the session waits to start while open() waits for it.
        val hold = CountDownLatch(1)
        repeat(2) { io.executor.execute { hold.await(10, TimeUnit.SECONDS) } }
        try {
            val (job, thrown) = call { NzbStreamOpener(engine).open(clip.nzb, 0, 0L until clip.data.size.toLong()) }
            val sessions = engine.sessionScope.coroutineContext.job
            withTimeout(10_000) { while (sessions.children.none()) delay(10) }
            job.cancel()
            assertIs<CancellationException>(withTimeout(10_000) { thrown.await() })
            assertTrue(sessions.children.all { it.isCancelled }, "the session was left running")
        } finally {
            hold.countDown()
        }
        delay(500)
        assertTrue(server.commands.none { it.startsWith("BODY ") }, "the cancelled stream still asked for articles: ${server.commands}")
    }

    @Test
    fun `open fails at once when the engine's session scope is already cancelled`() = runBlocking<Unit> {
        val engine = engine()
        engine.configure(EngineSettings(listOf(server().provider())))
        engine.sessionScope.cancel()
        val (failure, took) = measureTimedValue {
            withTimeoutOrNull(10_000) { runCatching { NzbStreamOpener(engine).open(clip.nzb, 0, 0L until clip.data.size.toLong()) }.exceptionOrNull() }
        }
        assertTrue(took < 5.seconds, "open() took $took")
        assertIs<UsenetException>(failure)
    }
}
