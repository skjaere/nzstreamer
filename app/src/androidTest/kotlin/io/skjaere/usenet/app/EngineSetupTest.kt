package io.skjaere.usenet.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The device tests' engine setup ([engineSetup]): one that isn't done in time fails saying what it was doing, the
 * engine's state, who holds the engine's lock and what every thread is doing, where a stuck engine hung a whole shard
 * (#273); and the next setup after one that found the lock held ends the process while it is still held, so the
 * sharded runner carries on in a fresh one, but an @After's wait ([engineOnSavedProviders]) leaves that to the next
 * setup. The engine's lock and the process's end are stand-ins here: the app's engine is only read, never changed.
 */
@RunWith(AndroidJUnit4::class)
class EngineSetupTest {
    private val realLockState = EngineSetup.lockState
    private val realEnd = EngineSetup.endProcess
    private val ended = CopyOnWriteArrayList<String>()

    @Before
    fun standIns() {
        EngineSetup.endProcess = { ended += "ended" }
        EngineSetup.lockState = { null }
        EngineSetup.stuckSince = null
    }

    @After
    fun restore() {
        EngineSetup.lockState = realLockState
        EngineSetup.endProcess = realEnd
        EngineSetup.stuckSince = null
    }

    @Test
    fun aSetupDoneInTimeGivesWhatItsBlockGave() = runBlocking<Unit> {
        assertEquals("done", engineSetup("a quick setup") { "done" })
        assertNull(engineSetup<String?>("a setup giving nothing") { null })
    }

    @Test
    fun aSetupNotDoneInTimeFailsSayingWhatItWasDoingAndWhoHoldsTheEnginesLock() = runBlocking<Unit> {
        EngineSetup.lockState = { "checkedInUse from release list marks on StandardTestDispatcher" }
        val failure = runCatching { engineSetup("a setup that never ends", timeoutMs = 300) { awaitCancellation() } }.exceptionOrNull()
        assertTrue("not an AssertionError: $failure", failure is AssertionError)
        val message = failure?.message.orEmpty()
        assertTrue(message, message.startsWith("EngineSetupTest.aSetupNotDoneInTimeFailsSayingWhatItWasDoingAndWhoHoldsTheEnginesLock: a setup that never ends: not done within 300 ms"))
        assertTrue(message, "its lock held by checkedInUse from release list marks on StandardTestDispatcher" in message)
        assertTrue(message, "the engine is " in message)
        // Every thread's top frames, this one's among them.
        assertTrue(message, "  ${Thread.currentThread().name} (" in message)
        assertTrue(message, "    at " in message)
    }

    @Test
    fun theNextSetupAfterOneThatFoundTheLockHeldEndsTheProcessWhileItIsStillHeld() = runBlocking<Unit> {
        var held: String? = "configure from provider sync on Dispatchers.Default"
        EngineSetup.lockState = { held }
        runCatching { engineSetup("a setup the engine holds up", timeoutMs = 200) { awaitCancellation() } }
        assertEquals("nothing ends at the timeout itself", emptyList<String>(), ended)
        // The stand-in returns where the process would have ended: the setup goes on.
        assertEquals("ran", engineSetup("the next setup") { "ran" })
        assertEquals(listOf("ended"), ended)

        // Let go since: the setups go on in this process, and no later one looks again.
        held = null
        engineSetup("a setup after the lock was let go") { }
        held = "configure from provider sync on Dispatchers.Default"
        engineSetup("a setup that finds it held, with no timeout before it") { }
        assertEquals(listOf("ended"), ended)
    }

    @Test
    fun anAfterTestsWaitForTheSavedProvidersEndsNothingWhileTheLockIsStillHeld() = runBlocking<Unit> {
        EngineSetup.lockState = { "checkedInUse from release list marks on ApplyingContinuationInterceptor" }
        EngineSetup.stuckSince = "an earlier test (a setup the engine held up)"
        // The app's engine is only read here, on its saved providers or not: what counts is the look before the wait,
        // so the wait itself is cut short.
        withTimeoutOrNull(500) { engineOnSavedProviders() }
        assertEquals("an @After's wait ended the process", emptyList<String>(), ended)
        assertEquals("an earlier test (a setup the engine held up)", EngineSetup.stuckSince)
    }

    @Test
    fun aWaitLeavingTheStuckLockToTheNextSetupStillFailsSayingWhoHoldsItAndArmsTheRule() = runBlocking<Unit> {
        EngineSetup.lockState = { "checkedInUse from release list marks on ApplyingContinuationInterceptor" }
        EngineSetup.stuckSince = "an earlier test (a setup the engine held up)"
        val failure = runCatching { engineSetup("an @After's wait", timeoutMs = 300, endIfStuck = false) { awaitCancellation() } }.exceptionOrNull()
        assertEquals("the wait ended the process", emptyList<String>(), ended)
        assertTrue("not an AssertionError: $failure", failure is AssertionError)
        val message = failure?.message.orEmpty()
        assertTrue(message, "an @After's wait: not done within 300 ms" in message)
        assertTrue(message, "its lock held by checkedInUse from release list marks on ApplyingContinuationInterceptor" in message)
        assertTrue(message, "    at " in message)
        assertTrue("the stuck rule isn't armed: ${EngineSetup.stuckSince}", EngineSetup.stuckSince?.endsWith("(an @After's wait)") == true)
        // The next test's setup is the one that decides.
        engineSetup("the next test's setup") { }
        assertEquals(listOf("ended"), ended)
    }

    @Test
    fun aSetupNotDoneInTimeWithTheLockFreeEndsNothingLater() = runBlocking<Unit> {
        runCatching { engineSetup("a slow setup", timeoutMs = 200) { awaitCancellation() } }
        EngineSetup.lockState = { "configure from provider sync on Dispatchers.Default" }
        engineSetup("the next setup") { }
        assertEquals(emptyList<String>(), ended)
    }

    @Test
    fun aSetupsAssumptionStillSkipsTheTest() = runBlocking<Unit> {
        val skipped = runCatching { engineSetup("a setup without its fixture") { assumeTrue("the mock lacks the fixture's articles", false) } }.exceptionOrNull()
        assertTrue("not an assumption: $skipped", skipped is AssumptionViolatedException)
    }
}
