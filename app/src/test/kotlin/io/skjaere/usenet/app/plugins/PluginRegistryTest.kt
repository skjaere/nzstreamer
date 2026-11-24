package io.skjaere.usenet.app.plugins

import kotlinx.coroutines.Job
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
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * L582, L616, L618, L619, L581, L583: the installed plugins. Each in the lowest free of four slots, kept across a start;
 * two at most. Files read as the process starts, code loaded only once the first screen asks, each plugin on its own, so
 * one that can't start leaves the other. An update waits for its slot to leave the screen, then swaps in the same
 * process, and one that can't start goes back to the version before, which still passes the checks (a version counts as
 * the highest only once it loaded), and isn't tried again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginRegistryTest {
    @TempDir
    lateinit var dir: File

    private fun TestScope.harness() = RegistryHarness(dir, this)

    @Test
    fun `each plugin takes the lowest free slot, kept across a start`() = runTest {
        val h = harness()
        val registry = h.registry().apply { start(); firstActivity() }
        assertIs<InstallResult.Installed>(h.install(registry, "skeleton", 1))
        assertIs<InstallResult.Installed>(h.install(registry, "kestrel", 1))
        assertEquals(listOf("skeleton" to 1, "kestrel" to 2), registry.plugins.value!!.map { it.id to it.slot })
        registry.remove("skeleton")
        assertIs<InstallResult.Installed>(h.install(registry, "harbor", 1))
        assertEquals(listOf("harbor" to 1, "kestrel" to 2), registry.plugins.value!!.map { it.id to it.slot })

        val next = h.registry().apply { start() }
        advanceUntilIdle()
        assertEquals(listOf("harbor" to 1, "kestrel" to 2), next.plugins.value!!.map { it.id to it.slot })
    }

    @Test
    fun `two plugins at most`() = runTest {
        val h = harness()
        val registry = h.registry().apply { start(); firstActivity() }
        h.install(registry, "skeleton", 1)
        h.install(registry, "kestrel", 1)
        assertIs<InstallResult.NoRoom>(h.install(registry, "harbor", 1))
        assertEquals(listOf("skeleton", "kestrel"), registry.plugins.value!!.map { it.id })
        assertFalse(File(h.files, "plugins/harbor").exists())
    }

    @Test
    fun `nothing is loaded until the first screen asks, and only once`() = runTest {
        val h = harness()
        h.registry().apply { start(); firstActivity() }.let { h.install(it, "skeleton", 1) }
        val loadsBefore = h.loading.loads.size

        val registry = h.registry()
        assertNull(registry.plugins.value, "nothing known before the files are read")
        registry.start()
        advanceUntilIdle()
        assertEquals(PluginStatus.Waiting, registry.installed("skeleton").status)
        assertEquals(loadsBefore, h.loading.loads.size, "no code loaded for a process the first screen hasn't asked in")

        registry.firstActivity()
        registry.firstActivity()
        advanceUntilIdle()
        assertNotNull(registry.running("skeleton"))
        assertEquals(loadsBefore + 1, h.loading.loads.size, "asked twice, loaded once")
        assertEquals("Fake", registry.installed("skeleton").label)
    }

    @Test
    fun `a plugin that can't start says why, and the other one loads`() = runTest {
        val h = harness()
        val registry = h.registry().apply { start() }
        h.install(registry, "skeleton", 1)
        h.install(registry, "kestrel", 1)
        h.loading.failing += "skeleton" to 1L
        registry.firstActivity()
        advanceUntilIdle()
        val failed = assertIs<PluginStatus.CouldntStart>(registry.installed("skeleton").status)
        assertEquals(LoadFailure.Linkage, failed.failure)
        assertEquals("its code didn't load", failed.failure.reason)
        assertNotNull(registry.running("kestrel"))
    }

    @Test
    fun `a plugin whose load throws an Error says it couldn't start, and the other one loads`() = runTest {
        val h = harness()
        val registry = h.registry().apply { start() }
        h.install(registry, "skeleton", 1)
        h.install(registry, "kestrel", 1)
        h.loading.throwing["skeleton" to 1L] = { NotImplementedError("the fake's static initializer") }
        registry.firstActivity()
        advanceUntilIdle()
        assertEquals("its code didn't load", assertIs<PluginStatus.CouldntStart>(registry.installed("skeleton").status).failure.reason)
        assertNotNull(registry.running("kestrel"))
    }

    @Test
    fun `a stored file that no longer passes is refused at the start, with the page's reason`() = runTest {
        val h = harness()
        h.registry().apply { start(); firstActivity() }.let { h.install(it, "skeleton", 1) }
        val stored = h.store.apk("skeleton", 1)
        stored.setWritable(true)
        stored.writeText("skeleton 1 and then some")
        stored.setReadOnly()

        val registry = h.registry().apply { start(); firstActivity() }
        advanceUntilIdle()
        val refused = assertIs<PluginStatus.Refused>(registry.installed("skeleton").status)
        assertEquals(Refusal.Hash, refused.refusal)
        assertEquals("its file changed after it was installed", refused.refusal.reason)
    }

    @Test
    fun `turn on again loads a version turned off, at once`() = runTest {
        val h = harness()
        var throwing = true
        h.loading.plugin = { _, _ -> FakePlugin(tabThrows = throwing) }
        val registry = h.registry().apply { start(); firstActivity() }
        h.install(registry, "skeleton", 1)
        advanceUntilIdle()
        assertEquals(PluginStatus.OffAfterError, registry.installed("skeleton").status)
        val again = h.registry().apply { start(); firstActivity() }
        advanceUntilIdle()
        assertEquals(PluginStatus.OffAfterError, again.installed("skeleton").status, "off at the next start too")

        throwing = false
        registry.turnOnAgain("skeleton")
        advanceUntilIdle()
        assertNotNull(registry.running("skeleton"))
        val next = h.registry().apply { start(); firstActivity() }
        advanceUntilIdle()
        assertNotNull(next.running("skeleton"), "on at the next start")
    }

    @Test
    fun `an update waits for its slot to leave the screen, then swaps in the same process`() = runTest {
        val h = harness()
        val registry = h.registry().apply { start(); firstActivity() }
        h.install(registry, "skeleton", 1)
        val v1 = assertNotNull(registry.running("skeleton"))
        val v1Plugin = v1.plugin as FakePlugin
        v1Plugin.host = v1.host

        assertIs<InstallResult.UpdateWaiting>(h.install(registry, "skeleton", 2))
        assertSame(v1, registry.running("skeleton"), "v1 runs on while its slot shows")
        assertTrue(registry.installed("skeleton").updateWaiting)
        assertTrue(h.store.apk("skeleton", 2).isFile, "written beside it")
        assertTrue(h.store.apk("skeleton", 1).isFile)

        assertTrue(registry.atSwapPoint("skeleton"), "its slot's stack is to be cleared")
        val v2 = assertNotNull(registry.running("skeleton"))
        assertNotSame(v1, v2)
        assertEquals(2L, v2.versionCode)
        assertTrue(v2.key != v1.key, "a new instance key")
        assertEquals(listOf("tab", "close"), v1Plugin.calls)
        assertEquals(true, v1Plugin.scopeCancelledAtClose, "its scope cancelled before close()")
        assertTrue(v1.host.scope.coroutineContext[Job]!!.isCancelled)
        assertFalse(File(h.files, "plugins/skeleton/code/1").exists(), "the old version's folder goes once the new one loaded")
        with(registry.installed("skeleton")) {
            assertFalse(updateWaiting)
            assertEquals(2L, versionCode)
            assertEquals("2", lastUpdate?.versionName)
        }
        assertFalse(registry.atSwapPoint("skeleton"), "nothing waits")
        val next = h.registry().apply { start(); firstActivity() }
        advanceUntilIdle()
        assertEquals(2L, next.running("skeleton")?.versionCode)
    }

    @Test
    fun `a swap whose new version can't start goes back to the old one, which still passes, and doesn't try it again`() = runTest {
        val h = harness()
        val registry = h.registry().apply { start(); firstActivity() }
        h.install(registry, "skeleton", 1)
        val v1 = assertNotNull(registry.running("skeleton"))
        assertIs<InstallResult.UpdateWaiting>(h.install(registry, "skeleton", 2), "v2 passed the checks")
        h.loading.failing += "skeleton" to 2L

        registry.atSwapPoint("skeleton")
        val back = assertNotNull(registry.running("skeleton"), "v1 runs again: v2 passing the checks didn't make it the highest")
        assertEquals(1L, back.versionCode)
        assertTrue(back.key != v1.key, "a new loader over the kept file")
        with(registry.installed("skeleton")) {
            assertEquals("2", failedUpdate?.versionName)
            assertEquals("its code didn't load", failedUpdate?.reason)
            assertFalse(updateWaiting)
        }
        assertFalse(File(h.files, "plugins/skeleton/code/2").exists())
        assertIs<InstallResult.NotAgain>(h.install(registry, "skeleton", 2), "not tried again")

        val loads = h.loading.loads.size
        val next = h.registry().apply { start(); firstActivity() }
        advanceUntilIdle()
        assertEquals(1L, next.running("skeleton")?.versionCode)
        assertEquals(listOf("skeleton" to 1L), h.loading.loads.drop(loads))
    }

    @Test
    fun `an update installed again while the store can't write keeps the copy stored before, which still takes over`() = runTest {
        val h = harness()
        val registry = h.registry().apply { start(); firstActivity() }
        h.install(registry, "skeleton", 1)
        assertIs<InstallResult.UpdateWaiting>(h.install(registry, "skeleton", 2))
        // The next copy can't be written, as on a full disk: its .tmp is a folder with something in it.
        val inTheWay = File(h.store.apk("skeleton", 2).parentFile, "plugin.apk.tmp")
        File(inTheWay, "in-the-way").apply { parentFile!!.mkdirs(); writeText("x") }
        assertIs<InstallResult.Failed>(h.install(registry, "skeleton", 2))
        assertTrue(h.store.apk("skeleton", 2).isFile, "the update stored before is kept")
        assertTrue(registry.installed("skeleton").updateWaiting)

        inTheWay.deleteRecursively()
        assertTrue(registry.atSwapPoint("skeleton"))
        assertEquals(2L, registry.running("skeleton")?.versionCode, "it takes over")
        assertNull(registry.installed("skeleton").failedUpdate)
    }

    @Test
    fun `a version never goes down`() = runTest {
        val h = harness()
        val registry = h.registry().apply { start(); firstActivity() }
        h.install(registry, "skeleton", 2)
        val refused = assertIs<InstallResult.Refused>(h.install(registry, "skeleton", 1))
        assertEquals(Refusal.Older, refused.refusal)
        assertEquals(2L, registry.running("skeleton")?.versionCode)
    }

    @Test
    fun `remove lets the instance go, frees the slot and deletes the plugin's files and secrets`() = runTest {
        val h = harness()
        val registry = h.registry().apply { start(); firstActivity() }
        h.install(registry, "skeleton", 1)
        val instance = assertNotNull(registry.running("skeleton"))
        val plugin = instance.plugin as FakePlugin
        plugin.host = instance.host
        instance.host.secrets.put("key", "abc")
        assertEquals(setOf("plugin:skeleton:key"), h.secrets.values.keys)

        registry.remove("skeleton")
        assertEquals(emptyList(), registry.plugins.value)
        assertEquals(listOf("tab", "close"), plugin.calls)
        assertFalse(File(h.files, "plugins/skeleton").exists())
        assertFalse(File(h.cache, "plugins/skeleton").exists())
        assertEquals(emptyMap(), h.secrets.values)
        assertEquals(emptyList(), h.registry().apply { start() }.let { advanceUntilIdle(); it.plugins.value })
    }
}
