package io.skjaere.usenet.app.plugins

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import io.skjaere.usenet.app.play.ItemTarget
import io.skjaere.usenet.core.FileListing
import io.skjaere.usenet.core.ListedFile
import io.skjaere.usenet.logging.LogBuffer
import io.skjaere.usenet.plugin.FileInfo
import io.skjaere.usenet.plugin.ItemReleases
import io.skjaere.usenet.plugin.PlayItem
import io.skjaere.usenet.plugin.PluginFilePicker
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
    fun `a start that finds nothing new writes no registry`() = runTest {
        val h = harness()
        h.registry().apply { start(); firstActivity() }.let { h.install(it, "skeleton", 1); h.install(it, "kestrel", 1) }
        advanceUntilIdle()
        val file = File(h.files, "plugins/registry.json")
        val written = file.readText()
        assertTrue(file.setLastModified(1_000_000L))

        val next = h.registry().apply { start(); firstActivity() }
        advanceUntilIdle()
        assertNotNull(next.running("skeleton"))
        assertNotNull(next.running("kestrel"))
        assertEquals(1_000_000L, file.lastModified(), "the registry's file wasn't written again")
        assertEquals(written, file.readText())
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

    /**
     * L581: the swap point is asked once its slot left the screen, but waits for the start's load and the lock first; it
     * asks again whether the slot is still off the screen once it holds the lock, and changes nothing where it isn't.
     */
    @Test
    fun `the swap point changes nothing where its slot came back on the screen while it waited`() = runTest {
        val h = harness()
        val registry = h.registry().apply { start(); firstActivity() }
        h.install(registry, "skeleton", 1)
        val v1 = registry.running("skeleton")
        h.install(registry, "skeleton", 2)
        assertFalse(registry.atSwapPoint("skeleton") { false }, "its slot shows again")
        assertSame(v1, registry.running("skeleton"), "v1 runs on")
        assertTrue(registry.installed("skeleton").updateWaiting, "v2 still waits")
        assertTrue(registry.atSwapPoint("skeleton") { true })
        assertEquals(2L, registry.running("skeleton")?.versionCode)
    }

    @Test
    fun `an update waits at the swap point while plugins are off, and takes over once they are on again`() = runTest {
        val h = harness()
        h.registry().apply { start(); firstActivity() }.let { h.install(it, "skeleton", 1); h.install(it, "skeleton", 2) }
        (h.guard as FakeGuard).pluginsOff = true
        val registry = h.registry().apply { start(); firstActivity() }
        advanceUntilIdle()
        assertTrue(registry.pluginsOff.value)
        val loads = h.loading.loads.size

        assertFalse(registry.atSwapPoint("skeleton"), "no plugin code runs while plugins are off")
        assertEquals(loads, h.loading.loads.size)
        assertEquals(PluginStatus.PluginsOff, registry.installed("skeleton").status)
        assertTrue(registry.installed("skeleton").updateWaiting, "the update still waits")

        registry.turnPluginsOnAgain()
        assertEquals(1L, registry.running("skeleton")?.versionCode)
        assertTrue(registry.atSwapPoint("skeleton"))
        assertEquals(2L, registry.running("skeleton")?.versionCode)
    }

    @Test
    fun `the swap point loads nothing before the first screen asked`() = runTest {
        val h = harness()
        h.registry().apply { start(); firstActivity() }.let { h.install(it, "skeleton", 1); h.install(it, "skeleton", 2) }
        val loads = h.loading.loads.size
        val unasked = h.registry().apply { start() }
        advanceUntilIdle()
        assertFalse(unasked.atSwapPoint("skeleton"), "a process no screen asked in loads no plugin code")
        assertEquals(loads, h.loading.loads.size)
        assertTrue(unasked.installed("skeleton").updateWaiting)
    }

    @Test
    fun `a swap point reported as the first screen asks waits for the start check`() = runTest {
        val h = harness()
        h.registry().apply { start(); firstActivity() }.let { h.install(it, "skeleton", 1); h.install(it, "skeleton", 2) }
        val loads = h.loading.loads.size
        // Reported before the start check has run, which finds plugins off: nothing loads.
        (h.guard as FakeGuard).pluginsOff = true
        val registry = h.registry().apply { start() }
        val swapped = async { registry.atSwapPoint("skeleton") }
        registry.firstActivity()
        advanceUntilIdle()
        assertFalse(swapped.await(), "it waited for the check, which found plugins off")
        assertEquals(loads, h.loading.loads.size)
        assertEquals(PluginStatus.PluginsOff, registry.installed("skeleton").status)
        assertTrue(registry.installed("skeleton").updateWaiting)
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

    /**
     * L621: a version the list withdrew stays off until a fixed one comes. An error it throws as the swap point lets it go,
     * or from its scope while it still runs, doesn't make it one "turned off after an error", which Turn on again loads.
     */
    @Test
    fun `a withdrawn version that throws as it goes stays withdrawn, and Turn on again loads nothing`() = runTest {
        val h = harness()
        h.loading.plugin = { _, _ -> FakePlugin(closeThrows = true) }
        val registry = h.registry().apply { start(); firstActivity() }
        h.install(registry, "skeleton", 1)
        h.install(registry, "kestrel", 1)
        registry.withdraw("skeleton", 1)
        registry.withdraw("kestrel", 1)
        assertTrue(registry.atSwapPoint("skeleton"), "let go, its close() throwing")
        advanceUntilIdle()
        registry.running("kestrel")!!.host.scope.launch { throw IllegalStateException("the fake's refresh") }
        advanceUntilIdle()
        listOf("skeleton", "kestrel").forEach { id ->
            assertEquals(PluginStatus.Withdrawn, registry.installed(id).status, id)
            assertTrue(registry.installed(id).withdrawn, id)
            val loads = h.loading.loads.size
            registry.turnOnAgain(id)
            assertEquals(loads, h.loading.loads.size, "$id: Turn on again loads nothing")
            assertEquals(PluginStatus.Withdrawn, registry.installed(id).status, id)
        }

        val next = h.registry().apply { start(); firstActivity() }
        advanceUntilIdle()
        assertEquals(listOf(PluginStatus.Withdrawn, PluginStatus.Withdrawn), next.plugins.value!!.map { it.status }, "off at the next start")
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
    fun `a start sweeps away what no installed plugin owns, and everything once the registry is lost`() = runTest {
        val h = harness()
        h.registry().apply { start(); firstActivity() }.let { h.install(it, "skeleton", 2) }
        h.secrets.put("plugin:skeleton:key", "kept")
        // Left behind (a death between the registry's write and the files' deletion): a version the registry doesn't keep,
        // a .tmp, and another plugin's code, data, cache and secrets.
        File(h.store.apk("skeleton", 1).parentFile, "plugin.apk").apply { parentFile!!.mkdirs(); writeText("skeleton 1") }
        val tmp = File(h.store.apk("skeleton", 2).parentFile, "plugin.apk.tmp").apply { writeText("half a copy") }
        File(h.store.apk("harbor", 1).parentFile, "plugin.apk").apply { parentFile!!.mkdirs(); writeText("harbor 1") }
        File(h.store.dataDir("harbor").apply { mkdirs() }, "chips.json").writeText("[]")
        h.store.cacheDir("harbor").mkdirs()
        h.secrets.put("plugin:harbor:key", "gone")

        h.registry().apply { start() }
        advanceUntilIdle()
        assertTrue(h.store.apk("skeleton", 2).isFile, "what the registry names stays")
        assertTrue(h.store.dataDir("skeleton").isDirectory)
        assertFalse(tmp.exists())
        assertFalse(h.store.apk("skeleton", 1).parentFile!!.exists())
        assertFalse(File(h.files, "plugins/harbor").exists())
        assertFalse(h.store.cacheDir("harbor").exists())
        assertEquals(setOf("plugin:skeleton:key"), h.secrets.values.keys)

        assertTrue(File(h.files, "plugins/registry.json").delete())
        val lost = h.registry().apply { start() }
        advanceUntilIdle()
        assertEquals(emptyList(), lost.plugins.value)
        assertFalse(File(h.files, "plugins/skeleton").exists(), "a registry lost takes every plugin's files")
        assertFalse(h.store.cacheDir("skeleton").exists())
        assertEquals(emptyMap(), h.secrets.values)
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

    /**
     * Remove with the secret store failing as it writes (a full disk): the plugin is gone all the same, from the registry
     * and as the app shows it, so its card and tab go; its keys go at the next start's sweep. The store's message, which
     * can name a file, isn't logged: the failure's kind alone.
     */
    @Test
    fun `a Remove whose keys can't be deleted takes the plugin away, and logs no path`() = runTest {
        val h = harness()
        val registry = h.registry().apply { start(); firstActivity() }
        h.install(registry, "skeleton", 1)
        h.secrets.put("plugin:skeleton:key", "the plugin's")
        h.secrets.removeAllFails = java.io.IOException("files/datastore/secrets.json.tmp (No space left on device)")
        val from = LogBuffer.snapshot().lastOrNull()?.seq ?: 0L

        registry.remove("skeleton")
        assertEquals(emptyList(), registry.plugins.value!!.map { it.id }, "gone as the app shows it")
        assertEquals(emptyList(), h.store.readRegistry().plugins.map { it.id })
        assertFalse(File(h.files, "plugins/skeleton").exists(), "its code and data deleted")
        val logged = LogBuffer.snapshot().filter { it.seq > from }.joinToString("\n") { it.message }
        assertTrue("IOException" in logged, logged)
        assertFalse("datastore" in logged, logged)

        h.secrets.removeAllFails = null
        h.registry().apply { start() }
        advanceUntilIdle()
        assertEquals(emptyMap(), h.secrets.values.toMap(), "its keys swept at the next start")
    }

    /**
     * L638, L618: a plugin's card and tab show its tab's icon once its version loaded in this process, and keep it while
     * it is off after an error; one that never loaded here has none (the core set's Build stands in), nor one installed
     * again after Remove until it loads.
     */
    @Test
    fun `a plugin's tab icon is kept once it loaded in this process, and goes with Remove`() = runTest {
        val h = harness()
        val registry = h.registry().apply { start(); firstActivity() }
        h.install(registry, "skeleton", 1)
        advanceUntilIdle()
        assertSame(Icons.Filled.Star, registry.installed("skeleton").icon)

        val instance = assertNotNull(registry.running("skeleton"))
        instance.host.scope.launch { throw IllegalStateException("the fake's refresh failed") }
        advanceUntilIdle()
        assertEquals(PluginStatus.OffAfterError, registry.installed("skeleton").status)
        assertSame(Icons.Filled.Star, registry.installed("skeleton").icon, "it ran in this process")
        val next = h.registry().apply { start(); firstActivity() }
        advanceUntilIdle()
        assertNull(next.installed("skeleton").icon, "off since this process started")

        registry.remove("skeleton")
        h.loading.failing += "skeleton" to 1L
        h.install(registry, "skeleton", 1)
        advanceUntilIdle()
        assertIs<PluginStatus.CouldntStart>(registry.installed("skeleton").status)
        assertNull(registry.installed("skeleton").icon, "installed again, it hasn't loaded")
    }

    /**
     * L635: a play keeps the guarded picker of the instance that started it, past that instance's end. Should it throw
     * once Turn on again has made a new instance of the same version, that new instance runs on, and no error is recorded
     * against its version: the instance that threw was let go already.
     */
    @Test
    fun `a let-go instance's picker that throws turns no newer instance of its version off`() = runTest {
        val h = harness()
        val targets = mutableListOf<ItemTarget>()
        val registry = h.registry(play = PluginPlayService(ranked = { _, _ -> emptyList() }) { target, _ -> targets += target })
        registry.start()
        registry.firstActivity()
        h.install(registry, "kestrel", 1)
        val first = registry.running("kestrel")!!
        val torn = object : PluginFilePicker {
            override val noMatch = "No file for the final"
            override fun pick(files: List<FileInfo>): Int = error("its state went with close()")
            override fun takesWhole(releaseName: String) = false
        }
        first.host.play.play(PlayItem("cc-2019:final", "Copper Crown · Final", null, null, picker = torn)) { ItemReleases(emptyList()) }
        first.host.scope.launch { throw IllegalStateException("the fake's refresh") }
        advanceUntilIdle()
        assertEquals(PluginStatus.OffAfterError, registry.installed("kestrel").status)
        registry.turnOnAgain("kestrel")
        val second = assertNotNull(registry.running("kestrel"))
        val recorded = (h.guard as FakeGuard).errors.toList()

        assertNull(targets.single().picker.pick(OneFile), "the old picker finds nothing")
        advanceUntilIdle()
        assertSame(second, registry.running("kestrel"), "the new instance runs on")
        assertEquals(recorded, (h.guard as FakeGuard).errors, "no error recorded against its version")
    }

    private object OneFile : FileListing<OneFile.File> {
        data class File(override val index: Int, override val path: String, override val size: Long) : ListedFile

        override val id = "nzb-final"
        override val title = "Copper.Crown.2019.Final.1080p.WEB.h264-KESTREL"
        override val files = listOf(File(0, "Copper.Crown.2019.Final.mkv", 3_000_000_000))
    }
}
