package io.skjaere.usenet.app.plugins

import io.skjaere.usenet.logging.LogBuffer
import io.skjaere.usenet.plugin.AddressSafety
import io.skjaere.usenet.plugin.PluginHttpException
import io.skjaere.usenet.plugin.PlayItem
import io.skjaere.usenet.plugin.ItemReleases
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L635, L619: each loaded instance has a host of its own, with a scope whose failures never close NZStreamer: one that
 * escapes it is logged by the plugin's id, version and kind, recorded with the crash guard, and turns that version off at
 * once, its scope cancelled before its close(). And what the host hands an instance: its own folders and secrets, an
 * address judged as an indexer's, and services that answer nothing until they are wired for plugins, or where the
 * registry is given none of the app's (search and HTTP: PluginSearchServiceTest, PluginHttpTest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginScopeTest {
    @TempDir
    lateinit var dir: File

    private fun TestScope.harness() = RegistryHarness(dir, this)

    @Test
    fun `an exception that escapes the plugin's scope is handled, recorded and turns the version off`() = runTest {
        val h = harness()
        val registry = h.registry().apply { start(); firstActivity() }
        h.install(registry, "skeleton", 1)
        h.install(registry, "kestrel", 1)
        val instance = assertNotNull(registry.running("skeleton"))
        val plugin = instance.plugin as FakePlugin
        plugin.host = instance.host
        val sibling = instance.host.scope.launch { CompletableDeferred<Unit>().await() }
        val from = LogBuffer.snapshot().lastOrNull()?.seq ?: 0L

        instance.host.scope.launch { throw IllegalStateException("the plugin's refresh failed: key=hunter2") }
        advanceUntilIdle()

        assertEquals(PluginStatus.OffAfterError, registry.installed("skeleton").status)
        assertEquals(listOf("skeleton" to 1L), (h.guard as FakeGuard).errors)
        assertEquals(listOf("tab", "close"), plugin.calls)
        assertEquals(true, plugin.scopeCancelledAtClose, "cancelled, then close()")
        assertTrue(sibling.isCancelled, "the rest of its work stops with it")
        assertNotNull(registry.running("kestrel"), "NZStreamer and the other plugin go on")
        val logged = LogBuffer.snapshot().filter { it.seq > from }.map { it.message }
        assertTrue(logged.any { "skeleton" in it && "IllegalStateException" in it }, "$logged")
        assertFalse(logged.any { "hunter2" in it }, "$logged")

        val next = h.registry().apply { start(); firstActivity() }
        advanceUntilIdle()
        assertEquals(PluginStatus.OffAfterError, next.installed("skeleton").status, "still off at the next start")
    }

    @Test
    fun `an instance's host has its id, its own folders and its secrets under its prefix`() = runTest {
        val h = harness()
        val registry = h.registry().apply { start(); firstActivity() }
        h.install(registry, "skeleton", 1)
        val host = assertNotNull(registry.running("skeleton")).host
        assertEquals("skeleton", host.id)
        assertEquals(File(h.files, "plugins/skeleton/data"), host.dataDir)
        assertEquals(File(h.cache, "plugins/skeleton"), host.cacheDir)
        assertTrue(host.dataDir.isDirectory && host.cacheDir.isDirectory)

        host.secrets.put("key", "abc")
        assertEquals(mapOf("plugin:skeleton:key" to "abc"), h.secrets.values)
        assertEquals("abc", host.secrets.get("key"))
        assertTrue(host.secrets.has("key").first())
        host.secrets.put("key", null)
        assertNull(host.secrets.get("key"))
        listOf("Key", "", "a".repeat(41), "../key", "key:x").forEach { name ->
            val refused = assertFailsWith<IllegalArgumentException>(name) { host.secrets.put(name, "abc") }
            assertFalse(name.isNotEmpty() && name in refused.message.orEmpty(), "the message names the rule, never the name")
        }
        assertEquals(emptyMap(), h.secrets.values)
    }

    @Test
    fun `play and watch answer nothing until they are wired, search and http nothing without the app's services`() = runTest {
        val h = harness()
        val registry = h.registry().apply { start(); firstActivity() }
        h.install(registry, "skeleton", 1)
        val host = assertNotNull(registry.running("skeleton")).host
        assertEquals(emptyList(), host.search.search("Copper Crown", runtimeMinutes = 90).toList())
        var asked = false
        host.play.play(PlayItem("copper-crown", "Copper Crown", "Final", 90)) { asked = true; ItemReleases(emptyList()) }
        advanceUntilIdle()
        assertFalse(asked, "no release asked for")
        assertEquals(emptyMap(), host.watch.observe(listOf("copper-crown")).first())
        host.watch.mark(listOf("copper-crown" to "Copper Crown"), watched = true)
        assertEquals(PluginHttpException.Kind.Unreachable, assertFailsWith<PluginHttpException> { host.http.get("https://example.invalid/") }.kind)
        assertEquals(AddressSafety.Encrypted, host.addressSafety("https://example.invalid/"))
        assertEquals(AddressSafety.Invalid, host.addressSafety("example.invalid"))
        assertTrue(host.scope.coroutineContext[Job]!!.isActive)
    }
}
