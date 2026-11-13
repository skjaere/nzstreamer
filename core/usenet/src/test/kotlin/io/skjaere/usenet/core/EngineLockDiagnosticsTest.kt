package io.skjaere.usenet.core

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Who holds the engine's lock, and who waits for it, can be read and is logged (#273): a play stalled behind a lock that
 * a caller on a stopped dispatcher held, and nothing said who held it. [UsenetEngine.lockState] names the engine's method
 * and the caller (its name, its dispatcher, its thread) while the lock is held; a caller kept waiting is logged with who
 * holds it; a build names the caller it is built for. None of these lines names a provider's host.
 */
class EngineLockDiagnosticsTest {
    private val engines = mutableListOf<UsenetEngine>()
    private val provider = ProviderConfig("p", "p", "127.0.0.1", 1, "", "", useTls = false)
    private val building = CountDownLatch(1)
    private val finish = CountDownLatch(1)

    @AfterTest
    fun tearDown() {
        finish.countDown()
        engines.forEach { it.close() }
    }

    /** An engine whose builds wait for [finish] once they have started ([building]); its streamers connect nowhere. */
    private fun gatedEngine(lockWaitWarn: Duration = UsenetEngine.LOCK_WAIT_WARN) = UsenetEngine(
        factory = { settings ->
            building.countDown()
            finish.await(10, TimeUnit.SECONDS)
            UsenetEngine.defaultStreamer(settings)
        },
        lockWaitWarn = lockWaitWarn,
    ).also { engines += it }

    private suspend fun awaitBuilding() = withContext(Dispatchers.IO) { assertTrue(building.await(10, TimeUnit.SECONDS), "the build never started") }

    @Test
    fun `while a build holds the lock, lockState names the engine's method and the caller, and is null once it is free`() = runBlocking<Unit> {
        val engine = gatedEngine()
        engine.configure(EngineSettings(listOf(provider)))
        assertNull(engine.lockState, "lockState with the lock free")
        var held = ""
        val (_, log) = stderrOf {
            val caller = launch(Dispatchers.Default) { withContext(CoroutineName("probe-test")) { engine.checkedInUse() } }
            awaitBuilding()
            held = assertNotNull(engine.lockState, "lockState while a build held the lock")
            assertTrue(held.startsWith("checkedInUse from probe-test on Dispatchers.Default"), held)
            assertTrue(" thread " in held, held)
            finish.countDown()
            caller.join()
        }
        assertNull(engine.lockState, "lockState once the build was done")
        // The build says whom it is for.
        val built = log.lines().filter { "Building the streamer for" in it }
        assertEquals(1, built.size, log)
        assertTrue("Building the streamer for 1 provider, for checkedInUse from probe-test on Dispatchers.Default" in built.single(), built.single())
        // nzb-streamer's own lines name the host; the engine's don't.
        assertFalse("127.0.0.1" in built.single() || "127.0.0.1" in held, built.single())
    }

    @Test
    fun `a caller kept waiting for the lock is logged with who holds it, and no more once it has it`() = runBlocking<Unit> {
        val engine = gatedEngine(lockWaitWarn = 200.milliseconds)
        engine.configure(EngineSettings(listOf(provider)))
        val (_, log) = stderrOf {
            val holder = launch(Dispatchers.Default + CoroutineName("the holder")) { engine.checkedInUse() }
            awaitBuilding()
            val waiter = launch(Dispatchers.Default + CoroutineName("the waiter")) { engine.configure(EngineSettings(listOf(provider.copy(id = "q", port = 2)))) }
            // About three lines' worth.
            delay(700)
            finish.countDown()
            holder.join()
            waiter.join()
            // A watch left running after the wait would go on saying so.
            delay(600)
        }
        val lines = log.lines().filter { "engine_lock:" in it }
        assertTrue(lines.size in 2..4, "expected about three lines, got ${lines.size}:\n$log")
        lines.forEach { line ->
            assertTrue("engine_lock: configure from the waiter on Dispatchers.Default has waited " in line, line)
            assertTrue("held by checkedInUse from the holder on Dispatchers.Default" in line, line)
            assertTrue("state Connecting" in line, line)
            assertFalse("127.0.0.1" in line, line)
        }
        assertNull(engine.lockState)
    }

    @Test
    fun `a caller without a name is still told apart, by its dispatcher and thread`() = runBlocking<Unit> {
        val engine = gatedEngine()
        engine.configure(EngineSettings(listOf(provider)))
        val caller = launch(Dispatchers.Default) { engine.streamer() }
        awaitBuilding()
        val held = assertNotNull(engine.lockState)
        assertTrue(held.startsWith("streamer from an unnamed caller on Dispatchers.Default"), held)
        finish.countDown()
        caller.join()
    }

    /** The state as the lock's lines say it: never a provider's host, which Ready names and an error's detail may. */
    @Test
    fun `the engine's state as its lines say it names no host`() {
        assertEquals("Ready with 2 providers", EngineState.Ready(listOf("news.example.net:563", "news.example.org:119")).summary)
        assertEquals("Ready with 1 provider", EngineState.Ready(listOf("news.example.net:563")).summary)
        assertEquals("Error (ConnectionFailed)", EngineState.Error(UsenetError.ConnectionFailed("Could not connect to news.example.net:563")).summary)
        assertEquals("Connecting", EngineState.Connecting.summary)
        assertEquals("NotConfigured", EngineState.NotConfigured.summary)
    }

    /** What [block] logs: slf4j-simple writes to System.err as it is at the time. */
    private suspend fun <T> stderrOf(block: suspend () -> T): Pair<T, String> {
        val original = System.err
        val captured = java.io.ByteArrayOutputStream()
        System.setErr(java.io.PrintStream(captured, true))
        try {
            return block() to captured.toString()
        } finally {
            System.setErr(original)
        }
    }
}
