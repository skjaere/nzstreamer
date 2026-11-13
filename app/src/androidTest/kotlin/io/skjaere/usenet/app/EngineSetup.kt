package io.skjaere.usenet.app

import android.os.Process
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.core.EngineState
import io.skjaere.usenet.core.PrepareOutcome
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.ProviderConfig
import io.skjaere.usenet.core.summary
import io.skjaere.usenet.logging.redactSecrets
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue

/** The mock NNTP server on the emulator's host, as the device tests set the engine up with it. */
val MOCK_PROVIDER = ProviderConfig("mock", "mock", "10.0.2.2", 1119, "", "", useTls = false, maxConnections = 4)

private val container get() = InstrumentationRegistry.getInstrumentation().targetContext.container

private const val TAG = "EngineSetup"

/** Each thread's frames from the top that a timed-out setup's report gives. */
private const val TOP_FRAMES = 6

/** The test's own classes, which [callingTest] looks past. */
private val OWN = setOf("io.skjaere.usenet.app.EngineSetupKt", "io.skjaere.usenet.app.EngineSetup")

/** What [engineSetup] reads of the engine and does to the process, where its own test stands in. */
internal object EngineSetup {
    /** Who holds the app's engine's lock ([io.skjaere.usenet.core.UsenetEngine.lockState]); null while it is free. */
    @Volatile
    var lockState: () -> String? = { container.engine.lockState }

    /** Ends this process: the instrumentation's too. */
    @Volatile
    var endProcess: () -> Unit = { Process.killProcess(Process.myPid()) }

    /** The setup that timed out finding the engine's lock held, while it may still be; null otherwise. */
    @Volatile
    var stuckSince: String? = null
}

/**
 * Runs [block], a device test's setup of the app's engine (configuring it, preparing a fixture on it), and fails if it
 * isn't done within [timeoutMs] (#273): one waiting on an engine whose lock was never let go hung a whole shard until the
 * runner's cap, every later test with it. The failure, also logged at ERROR under the tag EngineSetup, says what the
 * setup was doing, the engine's state, who holds its lock and every thread's top frames. A [block]'s own failures and
 * assumptions pass through as they are.
 *
 * A lock still held after a setup timed out is held for the process's life: every later engine test would fail the same
 * way, each after its own timeout, and the sharded runner, whose watch looks for output that stops, would never restart
 * the process. So the next setup that finds it still held says why and ends the process; the runner then runs the tests
 * not reached yet in a fresh one. A Gradle run of a few classes just ends there. Let go meanwhile, the tests go on. With
 * [endIfStuck] false, a setup leaves that look to the next one, as [engineOnSavedProviders] does; its own timeout still
 * arms it.
 */
suspend fun <T> engineSetup(what: String, timeoutMs: Long = 30_000, endIfStuck: Boolean = true, block: suspend () -> T): T {
    val test = callingTest()
    if (endIfStuck) endIfStillStuck(test, what)
    // Apart from the caller, which gives up on it at the timeout rather than wait for it to end: a setup can be held
    // where cancelling it can't stop it (the engine's build, a close). Named, as the engine's lock lines name a caller.
    val setup = CoroutineScope(currentCoroutineContext().minusKey(Job) + CoroutineName(test)).async { Done(block()) }
    val done = try {
        withTimeoutOrNull(timeoutMs) { setup.await() }
    } finally {
        // Done, failed or given up on: none of it goes on.
        setup.cancel()
    }
    if (done != null) return done.value
    val lockState = EngineSetup.lockState()
    if (lockState != null) EngineSetup.stuckSince = "$test ($what)"
    val report = listOf("$test: $what: not done within $timeoutMs ms; ${engineNow(lockState)}", "Threads, their top frames:") + threads()
    report.forEach { Log.e(TAG, it) }
    throw AssertionError(report.joinToString("\n"))
}

/** A setup's result, so one that is null isn't taken for a timeout. */
private class Done<T>(val value: T)

/**
 * The engine on the mock NNTP server alone ([MOCK_PROVIDER]). The app's own provider sync configures the engine soon
 * after the process starts: it goes first (up to 10 s), or it could replace the mock with the app's saved provider
 * halfway through a test.
 */
suspend fun mockOnEngine() {
    withTimeoutOrNull(10_000) { container.engine.state.first { it != EngineState.NotConfigured } }
    container.engine.configure(EngineSettings(listOf(MOCK_PROVIDER)))
}

/**
 * Waits for the app's own provider sync to put the engine on the saved providers it can use, as an @After does once it
 * has put the settings back, before the next test. Through [engineSetup]: a sync held behind the engine's lock fails the
 * wait with who holds it, the failure a test's report keeps when its @After fails after its body did.
 *
 * It never ends the process over a lock an earlier setup timed out on. An @After runs inside the Compose rule, before the
 * rule's teardown, and that teardown can let go of a holder queued on the test's dispatcher (#273's), so the next setup
 * is the one that decides. A timeout here arms the rule all the same: the next test's setup, often the next class's
 * first, forgets it if the lock was let go meanwhile, or ends the process if it is still held.
 */
suspend fun engineOnSavedProviders() = engineSetup("the engine back on the saved providers", endIfStuck = false) {
    container.engine.configuredProviders.first { it == container.settings.usableProviders().map { p -> p.record.id } }
}

/** The test assets' [name].nzb. */
fun fixtureBytes(name: String): ByteArray = InstrumentationRegistry.getInstrumentation().context.assets.open("$name.nzb").use { it.readBytes() }

/** The test assets' [name].nzb prepared on the engine as [title], as [prepareFixture] with its bytes does. */
suspend fun prepareFixture(name: String, title: String = name): PreparedNzb = prepareFixture(fixtureBytes(name), title)

/**
 * The NZB [bytes] prepared on the engine as [title] and put in the store, as a play's is. The test is skipped when the
 * mock NNTP server lacks their articles: seed them again, as SkipSegmentsDeviceTest's KDoc says for chapters and
 * TrackSheetDeviceTest's for tracks.
 */
suspend fun prepareFixture(bytes: ByteArray, title: String): PreparedNzb {
    val outcome = container.preparer.prepare(bytes, title)
    assumeTrue(
        "the mock NNTP server lacks the $title fixture's articles ($outcome); seed them again, see SkipSegmentsDeviceTest's KDoc (TrackSheetDeviceTest's for tracks)",
        outcome is PrepareOutcome.Ready,
    )
    return (outcome as PrepareOutcome.Ready).nzb.also { container.store.put(it, bytes) }
}

/** The engine as a failure says it: its state, without the providers' hosts, and who holds its lock. */
fun engineNow(lockState: String? = EngineSetup.lockState()): String =
    "the engine is ${container.engine.state.value.summary}, its lock ${lockState?.let { "held by $it" } ?: "free"}"

/** Ends the process if the engine's lock a setup timed out on is still held ([engineSetup]); forgets it once let go. */
private fun endIfStillStuck(test: String, what: String) {
    val since = EngineSetup.stuckSince ?: return
    val lockState = EngineSetup.lockState()
    if (lockState == null) {
        EngineSetup.stuckSince = null
        return
    }
    Log.e(TAG, "The engine has been stuck since $since, its lock held by $lockState: ending the process before $test ($what), so the tests not run yet run in a fresh one")
    EngineSetup.endProcess()
}

/**
 * Every thread, by name, with its state and top frames. A thread's name can hold a link (OkHttp names its call's), so
 * links are left out, and secrets as the app's log leaves them out.
 */
private fun threads(): List<String> = Thread.getAllStackTraces().entries.sortedBy { it.key.name }.map { (thread, frames) ->
    val name = redactSecrets(thread.name).replace(Regex("""\b[A-Za-z][A-Za-z0-9+.-]*://\S*"""), "<link>")
    (listOf("  $name (${thread.state})") + frames.take(TOP_FRAMES).map { "    at $it" }).joinToString("\n")
}

/** The test calling, as "RemoteKeysDeviceTest.setUp": its class, and the method or lambda in it that calls. */
private fun callingTest(): String {
    val frame = Throwable().stackTrace.firstOrNull { it.className.startsWith("io.skjaere.usenet.app.") && it.className.substringBefore('$') !in OWN }
        ?: return "a test"
    val names = frame.className.substringAfterLast('.').split('$')
    return names[0] + "." + (names.getOrNull(1)?.takeIf { it.isNotEmpty() && !it[0].isDigit() } ?: frame.methodName)
}
