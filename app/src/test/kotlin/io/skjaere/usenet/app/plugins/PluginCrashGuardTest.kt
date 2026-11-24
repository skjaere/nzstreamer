package io.skjaere.usenet.app.plugins

import io.skjaere.usenet.logging.CrashLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * L585, L619: the crash guard. A crash blames each loaded plugin version whose package is in its stack traces, written on
 * the crashing thread before the crash log; that version is off at the next start ("Turned off after a crash"), until
 * Turn on again or another version, which is tried once. A death during a version's load leaves a marker, and two in a
 * row turn it off as a crash would; one alone is tried again. Two crashes in a row within 30 s of a start that loaded
 * plugins make the next start load none, timed by the guard's own crash time. Every file is written whole and read
 * tolerantly: none of them throws at a start.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginCrashGuardTest {
    @TempDir
    lateinit var dir: File

    private var time = 1_000_000L
    private val skeleton1 = "skeleton" to 1L
    private val skeleton2 = "skeleton" to 2L
    private val kestrel = "kestrel" to 5L

    /** The guard as a new process makes it, over the same files. */
    private fun guard() = PluginCrashGuard(dir, now = { time })

    private val before = Thread.getDefaultUncaughtExceptionHandler()
    private val handedOn = mutableListOf<Throwable>()

    // Stands in for Android's own handler, which ends the process.
    private val android = Thread.UncaughtExceptionHandler { _, e -> synchronized(handedOn) { handedOn += e } }

    @BeforeEach
    fun standIn() {
        Thread.setDefaultUncaughtExceptionHandler(android)
    }

    @AfterEach
    fun putBack() {
        Thread.setDefaultUncaughtExceptionHandler(before)
    }

    private fun pluginFrame(id: String) = StackTraceElement("app.nzstreamer.plugin.$id.Pages", "Crash", "Pages.kt", 12)

    // With no cause given, the cause is left unset, for a test to set it.
    private fun throwableAt(vararg frames: StackTraceElement, cause: Throwable? = null) =
        (if (cause == null) RuntimeException("boom") else RuntimeException("boom", cause)).apply {
            stackTrace = arrayOf(*frames, StackTraceElement("io.skjaere.usenet.app.MainActivity", "onCreate", "MainActivity.kt", 1))
        }

    /** A process that started, loaded [loaded] and crashed with [crash] [after] ms on: what the next start finds. */
    private fun crashedProcess(crash: Throwable, after: Long = 60_000, loaded: List<Pair<String, Long>> = listOf(skeleton1)): PluginCrashGuard {
        val guard = guard()
        guard.startCheck(loaded)
        guard.recordsApplied()
        loaded.forEach { (id, version) ->
            guard.loading(id, version)
            guard.loadDone(id, version, running = true)
        }
        time += after
        guard.blame(crash)
        time += 60_000
        return guard()
    }

    @Test
    fun `a crash whose cause has a plugin's frame blames that version, one with none blames nothing`() {
        val next = crashedProcess(throwableAt(cause = throwableAt(pluginFrame("skeleton"))), loaded = listOf(skeleton1, kestrel))
        assertEquals(setOf(skeleton1), next.startCheck(listOf(skeleton1, kestrel)).crashed)

        val clean = crashedProcess(throwableAt(StackTraceElement("io.skjaere.usenet.app.Other", "run", "Other.kt", 3)))
        assertEquals(emptySet(), clean.startCheck(listOf(skeleton1)).crashed)
    }

    @Test
    fun `a version's frames blame nothing once it was let go, and a version not loaded is never blamed`() {
        val guard = guard()
        guard.startCheck(listOf(skeleton1))
        guard.loading("skeleton", 1)
        guard.loadDone("skeleton", 1, running = true)
        guard.letGo("skeleton", 1)
        guard.loading("kestrel", 5)
        guard.loadDone("kestrel", 5, running = false)
        guard.blame(throwableAt(pluginFrame("skeleton"), pluginFrame("kestrel")))
        assertEquals(emptySet(), guard().startCheck(listOf(skeleton1, kestrel)).crashed)
    }

    @Test
    fun `a crash while a version loads blames it, as one once it runs`() {
        val guard = guard()
        guard.startCheck(listOf(skeleton1))
        guard.recordsApplied()
        guard.loading("skeleton", 1)
        time += 1_000
        guard.blame(throwableAt(pluginFrame("skeleton")))
        assertEquals(setOf(skeleton1), guard().startCheck(listOf(skeleton1)).crashed)
    }

    @Test
    fun `a load that the process's own error ends leaves its marker, as a death would`() = runTest {
        val h = RegistryHarness(dir, this)
        h.guard = PluginCrashGuard(File(h.files, "plugins"), now = { testScheduler.currentTime })
        val ended = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, e -> ended += e })
        val registry = h.registry(scope).apply { start() }
        h.install(registry, "skeleton", 1)
        h.loading.throwing["skeleton" to 1L] = { OutOfMemoryError("no room") }
        registry.firstActivity()
        advanceUntilIdle()
        assertIs<OutOfMemoryError>(ended.single())
        assertTrue("skeleton" in File(h.files, "plugins/loading").readText(), "its marker stays, for the next start to count")
        scope.cancel()
    }

    @Test
    fun `a crash while an instance's close() runs still blames its version`() = runTest {
        val h = RegistryHarness(dir, this)
        val guard = PluginCrashGuard(File(h.files, "plugins"), now = { testScheduler.currentTime }).also { h.guard = it }
        h.loading.plugin = { _, version -> FakePlugin(onClose = { if (version == 1L) guard.blame(throwableAt(pluginFrame("skeleton"))) }) }
        val registry = h.registry().apply { start(); firstActivity() }
        h.install(registry, "skeleton", 1)
        advanceUntilIdle()
        h.install(registry, "skeleton", 2)
        assertTrue(registry.atSwapPoint("skeleton"))
        val record = File(h.files, "plugins/crash.json").readText()
        assertTrue(Regex(""""id":"skeleton","versionCode":1,""").containsMatchIn(record), "v1's close() was still its own code: $record")
    }

    @Test
    fun `cycles of causes and of suppressed ones are walked once each, and the walk returns`() {
        val a = throwableAt(pluginFrame("skeleton"))
        val b = throwableAt()
        a.initCause(b)
        b.initCause(a)
        val c = throwableAt()
        val d = throwableAt(pluginFrame("skeleton"))
        c.addSuppressed(d)
        d.addSuppressed(c)
        listOf(a, c).forEach { crash ->
            val next = assertTimeoutPreemptively(Duration.ofSeconds(5), ThrowingSupplier { crashedProcess(crash) })
            assertEquals(setOf(skeleton1), next.startCheck(listOf(skeleton1)).crashed)
            next.recordsApplied()
        }
    }

    @Test
    fun `a chain of causes deeper than 64 is walked 64 deep, and the walk returns`() {
        fun chain(depth: Int, pluginAt: Int): Throwable {
            var e: Throwable = throwableAt(*(if (pluginAt == depth) arrayOf(pluginFrame("skeleton")) else emptyArray()))
            for (level in depth - 1 downTo 0) e = throwableAt(*(if (pluginAt == level) arrayOf(pluginFrame("skeleton")) else emptyArray()), cause = e)
            return e
        }
        val deep = assertTimeoutPreemptively(Duration.ofSeconds(10), ThrowingSupplier { crashedProcess(chain(10_000, pluginAt = 10_000)) })
        assertEquals(emptySet(), deep.startCheck(listOf(skeleton1)).crashed, "beyond 64 causes")
        val shallow = crashedProcess(chain(10_000, pluginAt = 63))
        assertEquals(setOf(skeleton1), shallow.startCheck(listOf(skeleton1)).crashed, "the 64th")
    }

    @Test
    fun `a death during a load counts one, a second in a row turns the version off, a clean load clears the count`() {
        fun diesLoading(guard: PluginCrashGuard, version: Pair<String, Long>) {
            guard.startCheck(listOf(version))
            guard.recordsApplied()
            guard.loading(version.first, version.second)
        }
        diesLoading(guard(), skeleton1)
        val once = guard().startCheck(listOf(skeleton1))
        assertEquals(emptySet(), once.crashed, "one alone is tried again: a kill mid-load leaves one too")
        assertFalse(File(dir, "loading").exists())

        diesLoading(guard(), skeleton1)
        assertEquals(setOf(skeleton1), guard().startCheck(listOf(skeleton1)).crashed, "the second in a row")

        diesLoading(guard(), skeleton1)
        guard().apply {
            startCheck(listOf(skeleton1))
            recordsApplied()
            loading("skeleton", 1)
            loadDone("skeleton", 1, running = true)
        }
        diesLoading(guard(), skeleton1)
        assertEquals(emptySet(), guard().startCheck(listOf(skeleton1)).crashed, "a clean load between clears the count")
    }

    @Test
    fun `a clean load of one version leaves the deaths of another's load counted`() {
        val both = listOf(skeleton1, skeleton2)
        // An update's load at its swap point dies.
        guard().apply { startCheck(both); recordsApplied(); loading("skeleton", 2) }
        guard().apply {
            assertEquals(emptySet(), startCheck(both).crashed, "one death alone is tried again")
            recordsApplied()
            // The version before loads cleanly as the start's, then the update's load dies again at its swap point.
            loading("skeleton", 1)
            loadDone("skeleton", 1, running = true)
            loading("skeleton", 2)
        }
        assertEquals(setOf(skeleton2), guard().startCheck(both).crashed, "the update's second death in a row")
    }

    @Test
    fun `an update that dies as it takes over is tried once more, and after a second death goes back for good`() = runTest {
        val h = RegistryHarness(dir, this)

        /** A new process, which the test ends as the update's load kills it: the scope's handler stands for the death. */
        class Process {
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, _ -> })
            val guard = PluginCrashGuard(File(h.files, "plugins"), now = { testScheduler.currentTime }).also { h.guard = it }
            val registry = h.registry(scope).apply { start(); firstActivity() }
        }

        val first = Process()
        h.install(first.registry, "skeleton", 1)
        h.install(first.registry, "skeleton", 2)
        h.loading.throwing["skeleton" to 2L] = { OutOfMemoryError("the process dies as version 2 loads") }
        repeat(2) { n ->
            val process = if (n == 0) first else Process()
            advanceUntilIdle()
            assertEquals(1L, process.registry.running("skeleton")?.versionCode, "version 1 runs")
            assertTrue(process.registry.installed("skeleton").updateWaiting, "version 2 waits")
            process.scope.launch { process.registry.atSwapPoint("skeleton") }
            advanceUntilIdle()
            process.scope.cancel()
        }

        val next = Process()
        advanceUntilIdle()
        with(next.registry.installed("skeleton")) {
            assertEquals(1L, versionCode)
            assertFalse(updateWaiting, "version 2 doesn't wait any more")
            assertEquals("2", failedUpdate?.versionName)
            assertEquals("its code didn't load", failedUpdate?.reason)
        }
        assertEquals(1L, next.registry.running("skeleton")?.versionCode)
        assertFalse(next.registry.atSwapPoint("skeleton"), "not tried again")
        assertFalse(File(h.files, "plugins/skeleton/code/2").exists())
        assertIs<InstallResult.NotAgain>(h.install(next.registry, "skeleton", 2))
        next.scope.cancel()
    }

    @Test
    fun `a load that came back without the plugin running ends its marker too`() {
        guard().apply {
            startCheck(listOf(skeleton1))
            recordsApplied()
            loading("skeleton", 1)
            loadDone("skeleton", 1, running = false)
        }
        assertFalse(File(dir, "loading").exists())
        guard().apply { startCheck(listOf(skeleton1)); recordsApplied(); loading("skeleton", 1) }
        assertEquals(emptySet(), guard().startCheck(listOf(skeleton1)).crashed)
    }

    @Test
    fun `two crashes in a row just after a start turn the plugins off, timed by the guard's own crash time`() {
        // The crash log's folder is a file: its record fails, but the guard's own time is written before it.
        File(dir, "logs").writeText("in the way")
        fun earlyCrash() {
            Thread.setDefaultUncaughtExceptionHandler(android)
            val guard = guard()
            assertFalse(guard.startCheck(listOf(skeleton1)).pluginsOff)
            guard.recordsApplied()
            time += 5_000
            CrashLog(File(dir, "logs/last-crash.txt"), header = "h").install(guard::blame)
            Thread { throw IllegalStateException("host code, no plugin frame") }.apply { start(); join() }
            time += 60_000
        }
        earlyCrash()
        earlyCrash()
        assertFalse(File(dir, "logs/last-crash.txt").exists(), "the crash log's record failed")
        assertEquals(2, handedOn.size)
        val third = guard()
        assertTrue(third.startCheck(listOf(skeleton1)).pluginsOff, "the next start loads none")
        assertTrue(guard().startCheck(listOf(skeleton1)).pluginsOff, "until Turn on again")
    }

    @Test
    fun `a crash more than 30 s after its start doesn't count`() {
        crashedProcess(throwableAt(), after = 5_000)
        crashedProcess(throwableAt(), after = 31_000)
        assertFalse(guard().startCheck(listOf(skeleton1)).pluginsOff, "the second came late")
    }

    @Test
    fun `a start that lasted 30 s between two early crashes clears the count`() {
        crashedProcess(throwableAt(), after = 5_000)
        // A start that lasted 30 s (the registry's timer), then was killed, with no crash.
        guard().apply {
            startCheck(listOf(skeleton1))
            recordsApplied()
            time += 30_000
            lasted()
        }
        time += 60_000
        crashedProcess(throwableAt(), after = 5_000)
        assertFalse(guard().startCheck(listOf(skeleton1)).pluginsOff)
    }

    @Test
    fun `a start with no plugin to load times no crash`() {
        repeat(3) { crashedProcess(throwableAt(), after = 3_000, loaded = emptyList()) }
        assertFalse(guard().startCheck(listOf(skeleton1)).pluginsOff)
    }

    @Test
    fun `turn on again clears the plugins' off, and the count`() {
        repeat(2) { crashedProcess(throwableAt(), after = 3_000) }
        val off = guard()
        assertTrue(off.startCheck(listOf(skeleton1)).pluginsOff)
        off.turnOnPlugins()
        val next = guard()
        assertFalse(next.startCheck(listOf(skeleton1)).pluginsOff)
        next.recordsApplied()
        time += 3_000
        next.blame(throwableAt())
        time += 60_000
        assertFalse(guard().startCheck(listOf(skeleton1)).pluginsOff, "one early crash after it, not two")
    }

    @Test
    fun `an update's version is tried once after a crash of the one before`() {
        val next = crashedProcess(throwableAt(pluginFrame("skeleton")))
        assertEquals(emptySet(), next.startCheck(listOf(skeleton2)).crashed, "version 2 is tried")
        next.recordsApplied()
        next.loading("skeleton", 2)
        next.loadDone("skeleton", 2, running = true)
        time += 60_000
        next.blame(throwableAt(pluginFrame("skeleton")))
        time += 60_000
        assertEquals(setOf(skeleton2), guard().startCheck(listOf(skeleton2)).crashed, "and off after its own crash")
    }

    @Test
    fun `an error recorded is off at the next start, unless turned on again first`() {
        guard().apply {
            startCheck(listOf(skeleton1, kestrel))
            recordsApplied()
            recordError("skeleton", 1)
            recordError("kestrel", 5)
            turnedOnAgain("kestrel", 5)
        }
        val check = guard().startCheck(listOf(skeleton1, kestrel))
        assertEquals(setOf(skeleton1), check.errored)
        assertEquals(emptySet(), check.crashed)
    }

    @Test
    fun `a torn crash record, starts record or marker throws nothing at a start, and a torn marker counts for every version`() {
        File(dir, "crash.json").writeText("""{"crashedAt":12,"blamed":[{"id":"skel""")
        File(dir, "starts.json").writeText("""{"lastStart":1""")
        File(dir, "loading").writeText("""{"id":"ske""")
        val first = guard().startCheck(listOf(skeleton1, kestrel))
        assertEquals(emptySet(), first.crashed)
        assertFalse(first.pluginsOff)
        File(dir, "loading").writeText("""{"versionCo""")
        assertEquals(setOf(skeleton1, kestrel), guard().startCheck(listOf(skeleton1, kestrel)).crashed, "a second torn marker in a row")
    }

    @Test
    fun `the registry turns a version blamed by a crash off at the next start, and loads none after two early crashes`() = runTest {
        val h = RegistryHarness(dir, this)

        /** A new process: its own scope, which [crash] ends, so nothing of it runs on, its 30 s timer included. */
        class Process {
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val guard = PluginCrashGuard(File(h.files, "plugins"), now = { testScheduler.currentTime }).also { h.guard = it }
            val registry = h.registry(scope).apply { start(); firstActivity() }

            fun crash(e: Throwable) {
                guard.blame(e)
                scope.cancel()
            }
        }

        val first = Process()
        h.install(first.registry, "skeleton", 1)
        h.install(first.registry, "kestrel", 1)
        advanceTimeBy(60_000)
        first.crash(throwableAt(pluginFrame("skeleton")))

        val next = Process()
        advanceUntilIdle()
        assertEquals(PluginStatus.OffAfterCrash, next.registry.installed("skeleton").status)
        assertTrue(next.registry.running("kestrel") != null)
        next.registry.turnOnAgain("skeleton")
        assertTrue(next.registry.running("skeleton") != null, "Turn on again loads it at once")
        next.scope.cancel()

        repeat(2) {
            val early = Process()
            advanceTimeBy(1_000)
            early.crash(throwableAt())
            advanceTimeBy(60_000)
        }
        val loads = h.loading.loads.size
        val safe = Process().registry
        advanceUntilIdle()
        assertTrue(safe.pluginsOff.value)
        assertEquals(PluginStatus.PluginsOff, safe.installed("skeleton").status)
        assertEquals(PluginStatus.PluginsOff, safe.installed("kestrel").status)
        assertEquals(loads, h.loading.loads.size, "no plugin loaded")
        safe.turnPluginsOnAgain()
        assertFalse(safe.pluginsOff.value)
        assertTrue(safe.running("skeleton") != null && safe.running("kestrel") != null, "loaded at once")
    }
}
