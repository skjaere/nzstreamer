@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.skjaere.usenet.app.plugins

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * L633, L621, L581, L618: install, Cancel, Try again, Install again, the updates after each read of the list, withdrawn
 * versions and Remove, over the real registry and checks (the fakes' archives) with a download of the test's own.
 * - An install goes space, download, its SHA-256, the archive's checks, the store, the registry, the load; a failure says
 *   why in the Plugins page's words and keeps nothing; Cancel keeps nothing.
 * - An update a read finds is stored beside the running version and takes over only at the swap point; a withdrawn version
 *   goes off there and stays off, and a newer one installs over it.
 * - Remove takes the plugin's code, data, cache, secrets and entry, and nothing else; two plugins at most.
 */
class PluginInstallerTest {
    @TempDir
    lateinit var dir: File

    private val events = mutableListOf<String>()
    private val listState = MutableStateFlow(PluginListState())
    private val reads = MutableSharedFlow<PluginIndex>(replay = 1)
    private var television = false

    private inner class Setup(val test: TestScope) {
        val h = RegistryHarness(dir, test)
        val downloads = FakeDownloads(File(h.cache, "plugins/.download"), events)
        lateinit var registry: PluginRegistry
        lateinit var installer: PluginInstaller
        private var process: CoroutineScope? = null

        init {
            nextProcess()
        }

        /**
         * A process's start: the last one's installer gone with it, the registry made again over the same files, and its
         * installer, whose work is the test's own (a background scope's would wait on the test's).
         */
        fun nextProcess() {
            process?.cancel()
            val scope = CoroutineScope(test.coroutineContext + Job(test.coroutineContext[Job]))
            process = scope
            registry = h.registry().apply { start(); firstActivity() }
            installer = PluginInstaller(
                registry, listState, reads, { downloads }, { downloads.free }, scope, { television },
                sdk = 34, compat = PluginCompat(1, 0, HOST_LIBS), now = { test.testScheduler.currentTime },
            )
        }

        /** The list as a read found it: the page's state, and the read handed to the installer. */
        suspend fun read(vararg plugins: ListedPlugin) {
            val index = PluginIndex(Instant.EPOCH, plugins.toList(), emptyList())
            listState.value = PluginListState(index = index, readAt = test.testScheduler.currentTime)
            reads.emit(index)
            test.advanceUntilIdle()
        }

        /** The list as the page shows it, with no read handed on. */
        fun listed(vararg plugins: ListedPlugin) {
            listState.value = PluginListState(index = PluginIndex(Instant.EPOCH, plugins.toList(), emptyList()), readAt = 0)
        }

        /** The process's end: its installer's work stops, so the test can end. */
        fun close() {
            process?.cancel()
        }

        fun nothingKept(id: String) {
            assertEquals(emptyList(), registry.plugins.value.orEmpty().map { it.id }.filter { it == id }, "no entry")
            assertFalse(File(h.files, "plugins/$id").exists(), "no code or data")
            assertFalse(File(h.cache, "plugins/$id").exists(), "no cache")
            assertEquals(emptyList(), downloads.folder.list().orEmpty().toList(), "the download deleted")
        }
    }

    /** A test over a [Setup], its installer stopped as it ends. */
    private fun installerTest(block: suspend TestScope.(Setup) -> Unit) = runTest {
        val s = Setup(this)
        try {
            block(s)
        } finally {
            s.close()
        }
    }

    @Test
    fun `an install goes space, download, hash, checks, store, registry, load, its card following it`() = installerTest { s ->
        s.listed(harbor(version("harbor", 1)))
        s.h.loading.whileVerifying = { _, expected ->
            events += "verify"
            assertSame(InstallState.Checking, s.installer.states.value[expected.id], "Checking while the checks run")
            assertFalse(s.h.store.apk(expected.id, 1).exists(), "nothing stored before the checks")
        }
        s.h.loading.whileLoading = { id, code ->
            if (s.h.store.apk(id, code).let { it.isFile && !it.canWrite() }) events += "store"
            if (s.h.store.readRegistry().plugins.any { it.id == id }) events += "registry"
            events += "load"
        }
        s.downloads.hold = CompletableDeferred()

        assertNotNull(s.installer.install("harbor"))
        runCurrent()
        assertEquals(InstallState.Downloading(50), s.installer.states.value["harbor"], "half its size come")
        s.downloads.hold!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("space", "download", "hash", "verify", "store", "registry", "load"), events)
        assertEquals(1, assertIs<InstallState.Installed>(s.installer.states.value["harbor"]).slot)
        assertNotNull(s.registry.running("harbor"), "loaded")
        assertEquals("Harbor Lights", s.registry.installed("harbor").name, "the list's name")
        assertEquals(emptyList(), s.downloads.folder.list().orEmpty().toList(), "the download deleted after")
        assertNull(s.installer.install("harbor"), "installed: nothing to install")
        s.installer.pageLeft()
        assertNull(s.installer.states.value["harbor"], "the page left, its line goes")
    }

    @Test
    fun `each failure says why in one line, and nothing of it is kept`() = installerTest { s ->
        fun failsWith(line: String, cause: String) {
            assertNotNull(s.installer.install("harbor"), cause)
            advanceUntilIdle()
            assertEquals(line, assertIs<InstallState.Failed>(s.installer.states.value["harbor"], cause).line, cause)
            s.nothingKept("harbor")
        }

        s.listed(harbor(version("harbor", 1)))
        s.downloads.failure = DownloadFailure.Network
        failsWith("Couldn't download it. Check your connection.", "no answer")
        s.downloads.failure = null

        s.downloads.served["harbor" to 1L] = "harbor 1, changed on the way"
        failsWith("The file doesn't match the list, so nothing was installed.", "another file")
        s.downloads.served.clear()

        s.listed(harbor(version("harbor", 1, text = pluginText("harbor", 1, "foreign"))))
        s.downloads.served["harbor" to 1L] = pluginText("harbor", 1, "foreign")
        failsWith("It isn't signed by NZStreamer, so nothing was installed.", "another signer")

        s.listed(harbor(version("harbor", 1, text = pluginText("harbor", 1, "newer"))))
        s.downloads.served["harbor" to 1L] = pluginText("harbor", 1, "newer")
        failsWith("It was made for another NZStreamer version, so nothing was installed.", "stamped with API 1.1, though the list said 1.0")
        s.downloads.served.clear()

        s.listed(harbor(version("harbor", 1)))
        s.downloads.free = 700_000
        failsWith("Not enough space on this phone. Free 0.7 MB, then try again.", "no space")
        television = true
        failsWith("Not enough space on this TV. Free 0.7 MB, then try again.", "no space on a TV")
        assertEquals(listOf("harbor" to 1L, "harbor" to 1L, "harbor" to 1L, "harbor" to 1L), s.downloads.asked, "no space: nothing asked")

        s.downloads.free = Long.MAX_VALUE
        assertNotNull(s.installer.tryAgain("harbor"))
        advanceUntilIdle()
        assertIs<InstallState.Installed>(s.installer.states.value["harbor"], "Try again, with room")
    }

    @Test
    fun `Cancel while it downloads or is checked keeps nothing`() = installerTest { s ->
        s.listed(harbor(version("harbor", 1)))
        s.downloads.hold = CompletableDeferred()
        s.installer.install("harbor")
        runCurrent()
        assertIs<InstallState.Downloading>(s.installer.states.value["harbor"])
        s.installer.cancel("harbor")
        advanceUntilIdle()
        assertNull(s.installer.states.value["harbor"], "back to Install")
        s.nothingKept("harbor")

        s.downloads.hold = null
        s.h.loading.whileVerifying = { _, _ -> s.installer.cancel("harbor") }
        s.installer.install("harbor")
        advanceUntilIdle()
        assertNull(s.installer.states.value["harbor"], "back to Install")
        s.nothingKept("harbor")
        assertEquals(emptyList(), s.h.loading.loads, "nothing loaded")
    }

    @Test
    fun `Install again fetches the installed version through every check`() = installerTest { s ->
        s.listed(harbor(version("harbor", 1)))
        s.installer.install("harbor")
        advanceUntilIdle()
        val stored = s.h.store.apk("harbor", 1)
        stored.setWritable(true)
        stored.writeText("harbor 1 and then some")
        stored.setReadOnly()
        s.nextProcess()
        advanceUntilIdle()
        assertEquals(Refusal.Hash, assertIs<PluginStatus.Refused>(s.registry.installed("harbor").status).refusal)

        val installer = s.installer
        assertEquals(1L, installer.installAgainVersion("harbor")?.versionCode)
        val verifiedBefore = s.h.loading.verified.size
        assertNotNull(installer.installAgain("harbor"))
        advanceUntilIdle()
        assertEquals(listOf("harbor" to 1L, "harbor" to 1L), s.downloads.asked, "the installed version downloaded again")
        assertEquals(verifiedBefore + 1, s.h.loading.verified.size, "its file checked as an install's")
        assertNotNull(s.registry.running("harbor"))
        assertIs<InstallState.Installed>(installer.states.value["harbor"])

        s.listed(harbor(version("harbor", 2)))
        assertEquals(2L, installer.installAgainVersion("harbor")?.versionCode, "the version offered, where the list lost the installed one")
        s.listed(harbor(version("harbor", 1), withdrawn = setOf(1)))
        assertNull(installer.installAgainVersion("harbor"), "a withdrawn version is never fetched again")
        s.listed()
        assertNull(installer.installAgainVersion("harbor"), "not listed: Remove alone")
    }

    @Test
    fun `an update a read finds is stored, and takes over only at the swap point`() = installerTest { s ->
        s.listed(harbor(version("harbor", 1)))
        s.installer.install("harbor")
        advanceUntilIdle()
        s.installer.pageLeft()
        val v1 = assertNotNull(s.registry.running("harbor"))

        s.read(harbor(version("harbor", 1), version("harbor", 2)))
        assertEquals(2L, s.registry.installed("harbor").waitingVersion)
        assertSame(v1, s.registry.running("harbor"), "v1 runs on")
        assertNull(s.installer.states.value["harbor"], "an update shows nothing while it comes")
        s.read(harbor(version("harbor", 1), version("harbor", 2)))
        assertEquals(listOf("harbor" to 1L, "harbor" to 2L), s.downloads.asked, "a waiting update isn't fetched again")

        assertTrue(s.registry.atSwapPoint("harbor"))
        assertEquals(2L, s.registry.running("harbor")?.versionCode)
        val update = assertNotNull(s.installer.recentUpdate(s.registry.installed("harbor")))
        assertEquals("Updated to 2 today", PluginInstaller.updatedLine(update, testScheduler.currentTime, ZoneOffset.UTC))
        assertEquals("Updated to 2 · 1 Jan", PluginInstaller.updatedLine(update, testScheduler.currentTime + DAY_MS, ZoneOffset.UTC))
        advanceTimeBy(7 * DAY_MS - 1)
        assertNotNull(s.installer.recentUpdate(s.registry.installed("harbor")), "for seven days")
        advanceTimeBy(1)
        assertNull(s.installer.recentUpdate(s.registry.installed("harbor")), "then no more")

        // An update that couldn't start isn't fetched again; a newer one is.
        s.h.loading.failing += "harbor" to 3L
        s.read(harbor(version("harbor", 2), version("harbor", 3)))
        assertTrue(s.registry.atSwapPoint("harbor"))
        assertEquals(3L, s.registry.installed("harbor").failedUpdate?.versionCode)
        s.read(harbor(version("harbor", 2), version("harbor", 3)))
        assertEquals(listOf(1L, 2L, 3L), s.downloads.asked.map { it.second })
        s.read(harbor(version("harbor", 3), version("harbor", 4)))
        assertEquals(4L, s.registry.installed("harbor").waitingVersion)
    }

    @Test
    fun `a withdrawn version goes off at the swap point and stays off, and a newer one installs over it`() = installerTest { s ->
        s.listed(harbor(version("harbor", 1)))
        s.installer.install("harbor")
        advanceUntilIdle()
        val v1 = assertNotNull(s.registry.running("harbor"))

        s.read(harbor(version("harbor", 1), withdrawn = setOf(1)))
        assertTrue(s.registry.installed("harbor").withdrawn)
        assertSame(v1, s.registry.running("harbor"), "it runs on while its slot shows")
        assertTrue(s.registry.atSwapPoint("harbor"), "its slot's stack is to be cleared")
        assertEquals(PluginStatus.Withdrawn, s.registry.installed("harbor").status)
        assertTrue(v1.host.scope.coroutineContext[kotlinx.coroutines.Job]!!.isCancelled, "let go")

        s.read(harbor(version("harbor", 1)))
        assertEquals(PluginStatus.Withdrawn, s.registry.installed("harbor").status, "off until a fixed version is out")
        s.nextProcess()
        advanceUntilIdle()
        assertEquals(PluginStatus.Withdrawn, s.registry.installed("harbor").status, "off at the next start")
        assertEquals(listOf("harbor" to 1L), s.h.loading.loads, "never loaded again")

        s.read(harbor(version("harbor", 1), version("harbor", 2), withdrawn = setOf(1)))
        assertEquals(2L, s.registry.installed("harbor").waitingVersion, "the fixed version comes as an update")
        assertTrue(s.registry.atSwapPoint("harbor"))
        assertEquals(2L, s.registry.running("harbor")?.versionCode)
        assertFalse(s.registry.installed("harbor").withdrawn)
    }

    @Test
    fun `a waiting update the list withdraws is dropped`() = installerTest { s ->
        s.listed(harbor(version("harbor", 1)))
        s.installer.install("harbor")
        advanceUntilIdle()
        s.read(harbor(version("harbor", 1), version("harbor", 2)))
        assertEquals(2L, s.registry.installed("harbor").waitingVersion)
        s.read(harbor(version("harbor", 1), version("harbor", 2), withdrawn = setOf(2)))
        assertNull(s.registry.installed("harbor").waitingVersion)
        assertFalse(s.h.store.apk("harbor", 2).exists(), "its file gone")
        assertFalse(s.registry.atSwapPoint("harbor"), "nothing to take over")
        assertEquals(1L, s.registry.running("harbor")?.versionCode)
    }

    @Test
    fun `Remove takes the plugin's code, data, cache, secrets and entry, and frees its slot`() = installerTest { s ->
        s.listed(harbor(version("harbor", 1)), plugin("kestrel", version("kestrel", 1)), plugin("copper", version("copper", 1)))
        s.installer.install("harbor")
        advanceUntilIdle()
        s.installer.install("kestrel")
        advanceUntilIdle()
        File(s.h.store.dataDir("harbor"), "kept.json").writeText("{}")
        File(s.h.store.cacheDir("harbor"), "pages").writeText("cached")
        s.h.secrets.put("plugin:harbor:key", "harbor's")
        s.h.secrets.put("plugin:kestrel:key", "kestrel's")
        s.h.secrets.put("tmdb.key", "the app's")
        // The library's database stands for what the user watched: Remove never reaches it.
        val library = File(dir, "databases/library.db").apply { parentFile!!.mkdirs(); writeText("item:harbor:ev-1") }

        s.installer.remove("harbor")
        s.nothingKept("harbor")
        assertEquals(setOf("plugin:kestrel:key", "tmdb.key"), s.h.secrets.values.keys)
        assertEquals(listOf("kestrel"), s.h.store.readRegistry().plugins.map { it.id })
        assertEquals("item:harbor:ev-1", library.readText())
        assertNotNull(s.registry.running("kestrel"))

        assertNotNull(s.installer.install("copper"))
        advanceUntilIdle()
        assertEquals(1, s.registry.installed("copper").slot, "the slot freed")
    }

    @Test
    fun `a third plugin isn't installed, nor downloaded`() = installerTest { s ->
        s.listed(harbor(version("harbor", 1)), plugin("kestrel", version("kestrel", 1)), plugin("copper", version("copper", 1)))
        s.downloads.hold = CompletableDeferred()
        s.installer.install("harbor")
        s.installer.install("kestrel")
        runCurrent()
        assertNull(s.installer.install("copper"), "two on their way already")
        s.downloads.hold!!.complete(Unit)
        advanceUntilIdle()
        assertNull(s.installer.install("copper"), "two installed")
        assertEquals(listOf("harbor", "kestrel"), s.downloads.asked.map { it.first })
        assertEquals(listOf("harbor", "kestrel"), s.registry.plugins.value!!.map { it.id })
    }

    @Test
    fun `Turn on again loads a version turned off after an error`() = installerTest { s ->
        var throwing = true
        s.h.loading.plugin = { _, _ -> FakePlugin(tabThrows = throwing) }
        s.listed(harbor(version("harbor", 1)))
        s.installer.install("harbor")
        advanceUntilIdle()
        assertEquals(PluginStatus.OffAfterError, s.registry.installed("harbor").status)
        throwing = false
        s.installer.turnOnAgain("harbor")
        assertNotNull(s.registry.running("harbor"))
    }

    /** A download of the test's own: the space, the request, the hash, as the real one makes them, and the file it writes. */
    private class FakeDownloads(val folder: File, private val events: MutableList<String>) : PluginDownloads {
        /** What a version's file holds where it isn't the honest build's words. */
        val served = mutableMapOf<Pair<String, Long>, String>()
        var free = Long.MAX_VALUE
        var failure: DownloadFailure? = null

        /** Where set, each download waits half-way for it. */
        var hold: CompletableDeferred<Unit>? = null
        val asked = mutableListOf<Pair<String, Long>>()

        override suspend fun download(id: String, version: ListedVersion, progress: (Long) -> Unit): Download {
            events += "space"
            if (free < 2 * version.size) return Download.NoSpace(2 * version.size - free)
            events += "download"
            asked += id to version.versionCode
            failure?.let { return Download.Failed(it) }
            folder.mkdirs()
            val file = File(folder, "$id-${version.versionCode}.apk")
            file.writeText(served[id to version.versionCode] ?: pluginText(id, version.versionCode))
            try {
                progress(version.size / 2)
                hold?.await()
            } catch (e: CancellationException) {
                file.delete()
                throw e
            }
            progress(version.size)
            events += "hash"
            if (Sha256.of(file) != version.sha256) {
                file.delete()
                return Download.Failed(DownloadFailure.Mismatch)
            }
            return Download.Done(file)
        }
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        val LIBS = PluginCompat.parseLibs(HOST_LIBS)!!

        /** Plugin [id]'s version [code] as the list gives it, its SHA-256 [text]'s (the honest build's words, unless given). */
        fun version(id: String, code: Long, text: String = pluginText(id, code)) = ListedVersion(
            code, "$code", 1, 0, LIBS, 26, "https://github.com/skjaere/nzstreamer/releases/download/plugin-$id-v$code.0.0/$id.apk", 700_000,
            Sha256.of(text.toByteArray()),
        )

        fun plugin(id: String, vararg versions: ListedVersion, withdrawn: Set<Long> = emptySet()) =
            ListedPlugin(id, id.replaceFirstChar { it.uppercase() }, "", withdrawn, versions.sortedByDescending { it.versionCode })

        fun harbor(vararg versions: ListedVersion, withdrawn: Set<Long> = emptySet()) =
            ListedPlugin("harbor", "Harbor Lights", "Harbor Lights in a tab of its own.", withdrawn, versions.sortedByDescending { it.versionCode })
    }
}
