package io.skjaere.usenet.core

import io.skjaere.nzbstreamer.NzbStreamer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The engine takes, holds and lets go of its lock on a dispatcher of its own, never on its caller's (#273). A caller's
 * dispatcher can stop running what is sent to it: the v2 Compose test rule's runs a composition's work only while the
 * test syncs with Compose, so a release list's check of the providers, sent back to it with the lock held, held the lock
 * while the test blocked, and a play waited behind it for good. [stalled] stands in for it: run once, never again.
 */
class EngineLockDispatcherTest {
    private val stalled = StandardTestDispatcher()
    private val stalledScope = CoroutineScope(stalled + Job())
    private val engines = mutableListOf<UsenetEngine>()
    private val one = ProviderConfig("p", "p", "127.0.0.1", 1, "", "", useTls = false)
    private val two = ProviderConfig("q", "q", "127.0.0.1", 2, "", "", useTls = false, priority = 1)

    @AfterTest
    fun tearDown() {
        stalledScope.cancel()
        engines.forEach { it.close() }
    }

    /** An engine whose streamers connect nowhere; [factory] and [probe] as a test needs them. */
    private fun engine(
        factory: (EngineSettings) -> NzbStreamer = UsenetEngine::defaultStreamer,
        release: (NzbStreamer) -> Unit = { it.close() },
        probe: suspend (ProviderConfig) -> UsenetError? = { null },
    ) = UsenetEngine(factory, release, probe = probe).also { engines += it }

    /** [block] started on [stalled] as [name], run up to its first dispatch away from it and no further. */
    private fun onStalled(name: String, block: suspend () -> Unit): Job =
        stalledScope.launch(CoroutineName(name)) { block() }.also { stalled.scheduler.runCurrent() }

    /** Fails unless another caller's configure gets the lock within 2 s, saying who holds it otherwise, and [why] it may. */
    private suspend fun assertConfigureReturns(engine: UsenetEngine, why: String = "") {
        val returned = withTimeoutOrNull(2_000) { engine.configure(EngineSettings(listOf(one.copy(id = "other", port = 3)))) }
        assertNotNull(returned, "configure didn't get the lock within 2 s; the lock: ${engine.lockState}$why")
    }

    private suspend fun CountDownLatch.awaitOrFail(what: String) =
        withContext(Dispatchers.IO) { assertTrue(await(10, TimeUnit.SECONDS), "$what never happened") }

    @Test
    fun `a caller whose dispatcher stops after the build doesn't keep the lock`() = runBlocking<Unit> {
        val built = CountDownLatch(1)
        val engine = engine(factory = { settings -> UsenetEngine.defaultStreamer(settings).also { built.countDown() } })
        engine.configure(EngineSettings(listOf(one)))
        onStalled("stalled caller") { engine.checkedInUse() }
        built.awaitOrFail("the build")
        assertConfigureReturns(engine)
    }

    /**
     * The section runs on Default, but its holder is named with its caller's own dispatcher: #273's lines named the
     * composition's, the clue to whose dispatcher had stopped.
     */
    @Test
    fun `a holder is named with its caller's dispatcher, the section on Default`() = runBlocking<Unit> {
        val building = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val engine = engine(
            factory = { settings ->
                building.countDown()
                finish.await(10, TimeUnit.SECONDS)
                UsenetEngine.defaultStreamer(settings)
            },
        )
        engine.configure(EngineSettings(listOf(one)))
        onStalled("stalled caller") { engine.checkedInUse() }
        building.awaitOrFail("the build")
        val held = engine.lockState
        finish.countDown()
        assertNotNull(held, "no holder during the build")
        assertTrue(held.startsWith("checkedInUse from stalled caller on StandardTestDispatcher"), held)
        assertTrue(Regex(""", thread DefaultDispatcher-worker-\d+""").containsMatchIn(held), held)
    }

    @Test
    fun `a waiter whose dispatcher stops isn't handed the lock`() = runBlocking<Unit> {
        val building = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val engine = engine(
            factory = { settings ->
                building.countDown()
                finish.await(10, TimeUnit.SECONDS)
                UsenetEngine.defaultStreamer(settings)
            },
        )
        engine.configure(EngineSettings(listOf(one)))
        val first = launch(Dispatchers.Default + CoroutineName("first caller")) { engine.checkedInUse() }
        building.awaitOrFail("the first caller's build")
        // Waits for the lock the first caller holds.
        onStalled("stalled waiter") { engine.checkedInUse() }
        finish.countDown()
        first.join()
        assertConfigureReturns(engine, " (the first caller is done: it was handed to the waiter)")
    }

    @Test
    fun `a caller whose dispatcher stops during the providers' check doesn't keep the lock`() = runBlocking<Unit> {
        val probing = CountDownLatch(2)
        val answer = CompletableDeferred<Unit>()
        val engine = engine(probe = { probing.countDown(); answer.await(); null })
        engine.configure(EngineSettings(listOf(one, two)))
        onStalled("stalled caller") { engine.checkedInUse() }
        probing.awaitOrFail("the providers' check")
        answer.complete(Unit)
        assertConfigureReturns(engine)
    }

    /**
     * A use counted under the lock is undone when its caller is cancelled before the streamer gets back to it: else a
     * streamer [UsenetEngine.configure] replaced would wait for that use to end and never be closed, and the engine, never
     * idle, would never build again to take back a provider left out.
     */
    @Test
    fun `a withStreamer caller cancelled as the streamer comes back to it doesn't keep it in use`() = runBlocking<Unit> {
        val closed = AtomicInteger()
        val engine = engine(release = { closed.incrementAndGet(); it.close() })
        engine.configure(EngineSettings(listOf(one)))
        engine.streamer()
        val use = onStalled("stalled use") { engine.withStreamer { awaitCancellation() } }
        // Long enough for the lock's part, wherever it runs, to be done and on its way back.
        delay(200)
        use.cancel()
        stalled.scheduler.advanceUntilIdle()
        engine.configure(EngineSettings(listOf(two)))
        assertEquals(1, closed.get(), "the streamer configure replaced was left open: a cancelled use still counts")
    }
}
