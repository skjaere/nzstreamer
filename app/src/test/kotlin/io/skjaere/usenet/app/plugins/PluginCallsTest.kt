package io.skjaere.usenet.app.plugins

import io.skjaere.usenet.logging.LogBuffer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * L619: the host's calls into a plugin's code outside composition each run guarded. One that throws turns that version off
 * "after an error", answers as if the plugin had given nothing, and NZStreamer goes on. Its log line names the plugin's id,
 * version and the exception's kind, never the plugin's own words.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginCallsTest {
    @TempDir
    lateinit var dir: File

    private fun TestScope.harness() = RegistryHarness(dir, this)

    @Test
    fun `a tab() that throws turns its version off after an error, and the other plugin loads`() = runTest {
        val h = harness()
        h.loading.plugin = { id, _ -> FakePlugin(tabThrows = id == "skeleton") }
        val registry = h.registry().apply { start() }
        h.install(registry, "skeleton", 1)
        h.install(registry, "kestrel", 1)
        val from = LogBuffer.snapshot().lastOrNull()?.seq ?: 0L
        registry.firstActivity()
        advanceUntilIdle()

        assertEquals(PluginStatus.OffAfterError, registry.installed("skeleton").status)
        assertNotNull(registry.running("kestrel"))
        assertEquals(listOf("skeleton" to 1L), (h.guard as FakeGuard).errors.distinct())
        val logged = LogBuffer.snapshot().filter { it.seq > from }.map { it.message }
        assertTrue(logged.any { "skeleton" in it && "1" in it && "IllegalStateException" in it }, "$logged")
        assertFalse(logged.any { "hunter2" in it }, "the plugin's own words never reach the log: $logged")
    }

    @Test
    fun `a close() that throws as the plugin is removed leaves NZStreamer going, and the remove done`() = runTest {
        val h = harness()
        h.loading.plugin = { _, _ -> FakePlugin(closeThrows = true) }
        val registry = h.registry().apply { start(); firstActivity() }
        h.install(registry, "skeleton", 1)
        h.install(registry, "kestrel", 1)
        registry.remove("skeleton")
        advanceUntilIdle()
        assertEquals(listOf("kestrel"), registry.plugins.value!!.map { it.id })
        assertFalse(File(h.files, "plugins/skeleton").exists())
        assertEquals(listOf("skeleton" to 1L), (h.guard as FakeGuard).errors.distinct())
        assertNotNull(registry.running("kestrel"))
    }

    @Test
    fun `a close() that throws in a swap turns the old version off after an error, and the new one runs`() = runTest {
        val h = harness()
        h.loading.plugin = { _, version -> FakePlugin(closeThrows = version == 1L) }
        val registry = h.registry().apply { start(); firstActivity() }
        h.install(registry, "skeleton", 1)
        h.install(registry, "skeleton", 2)
        registry.atSwapPoint("skeleton")
        advanceUntilIdle()
        assertEquals(2L, registry.running("skeleton")?.versionCode)
        assertEquals(listOf("skeleton" to 1L), (h.guard as FakeGuard).errors.distinct())
    }

    @Test
    fun `a tab() that calls TODO() turns its version off after an error, and the other plugin loads`() = runTest {
        val h = harness()
        h.loading.plugin = { id, _ -> FakePlugin(tabError = { NotImplementedError("An operation is not implemented.") }.takeIf { id == "skeleton" }) }
        val registry = h.registry().apply { start() }
        h.install(registry, "skeleton", 1)
        h.install(registry, "kestrel", 1)
        registry.firstActivity()
        advanceUntilIdle()

        assertEquals(PluginStatus.OffAfterError, registry.installed("skeleton").status)
        assertNotNull(registry.running("kestrel"), "the plugin in the next slot loads")
        assertEquals(listOf("skeleton" to 1L), (h.guard as FakeGuard).errors.distinct())
    }

    @Test
    fun `a close() that calls TODO() as the plugin is removed leaves the remove done`() = runTest {
        val h = harness()
        h.loading.plugin = { _, _ -> FakePlugin(closeError = { NotImplementedError("An operation is not implemented.") }) }
        val registry = h.registry().apply { start(); firstActivity() }
        h.install(registry, "skeleton", 1)
        h.install(registry, "kestrel", 1)
        registry.remove("skeleton")
        advanceUntilIdle()
        assertEquals(listOf("kestrel"), registry.plugins.value!!.map { it.id })
        assertFalse(File(h.files, "plugins/skeleton").exists())
        val next = h.registry().apply { start() }
        advanceUntilIdle()
        assertEquals(listOf("kestrel"), next.plugins.value!!.map { it.id }, "gone at the next start too")
    }

    @Test
    fun `a close() that calls TODO() in a swap turns the old version off after an error, and the new one runs`() = runTest {
        val h = harness()
        h.loading.plugin = { _, version -> FakePlugin(closeError = { AssertionError("the fake's close") }.takeIf { version == 1L }) }
        val registry = h.registry().apply { start(); firstActivity() }
        h.install(registry, "skeleton", 1)
        h.install(registry, "skeleton", 2)
        assertTrue(registry.atSwapPoint("skeleton"))
        advanceUntilIdle()
        assertEquals(2L, registry.running("skeleton")?.versionCode)
        assertEquals(listOf("skeleton" to 1L), (h.guard as FakeGuard).errors.distinct())
    }

    @Test
    fun `an Error the plugin's code throws is the plugin's, TODO() among them, and only the process's own goes up`() {
        val failures = mutableListOf<Pair<String, String>>()
        val calls = PluginCalls { version, what, e -> failures += what to e.javaClass.simpleName }
        val version = PluginVersion("skeleton", 3, key = 7)
        assertEquals(null, calls.call(version, "tab", nothing = null) { TODO() })
        assertEquals(Unit, calls.call(version, "close", nothing = Unit) { throw AssertionError("the plugin's check") })
        assertEquals(0, calls.call(version, "pick", nothing = 0) { throw StackOverflowError() })
        assertEquals(
            listOf("tab" to "NotImplementedError", "close" to "AssertionError", "pick" to "StackOverflowError"),
            failures,
        )
        assertIs<OutOfMemoryError>(runCatching { calls.call(version, "big", nothing = Unit) { throw OutOfMemoryError("no room") } }.exceptionOrNull())
        assertIs<InternalError>(runCatching { calls.call(version, "vm", nothing = Unit) { throw InternalError("the VM's") } }.exceptionOrNull())
        assertEquals(3, failures.size, "the process's own errors aren't the plugin's")
    }

    @Test
    fun `the guard answers what the plugin would have given had it given nothing`() {
        val failures = mutableListOf<Pair<String, String>>()
        val calls = PluginCalls { version, what, e -> failures += "${version.id} ${version.versionCode} $what" to e.javaClass.simpleName }
        val version = PluginVersion("skeleton", 3, key = 7)
        assertEquals(null, calls.call(version, "tab", nothing = null) { throw IllegalArgumentException("plugin's words") })
        assertEquals(emptyList<String>(), calls.call(version, "list", nothing = emptyList()) { throw NoClassDefFoundError("gone") })
        assertEquals("given", calls.call(version, "ask", nothing = "nothing") { "given" })
        assertEquals(listOf("skeleton 3 tab" to "IllegalArgumentException", "skeleton 3 list" to "NoClassDefFoundError"), failures)
        assertIs<OutOfMemoryError>(runCatching { calls.call(version, "big", nothing = Unit) { throw OutOfMemoryError("no room") } }.exceptionOrNull())
    }
}
