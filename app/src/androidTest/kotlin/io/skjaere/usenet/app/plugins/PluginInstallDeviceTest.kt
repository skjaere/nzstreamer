package io.skjaere.usenet.app.plugins

import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.plugins.PluginListServer.Listed
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.debrid.ServiceHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZoneId

/**
 * Plugins installed, updated, withdrawn and removed from a list, on a device (L633, L621, L581, L596): the skeleton's real
 * builds, listed and served by a list of the test's own on this device's loopback address ([PluginListServer]), which the
 * app reads through PluginSource's test source as it would read GitHub's. The list, the installer and the registry are the
 * app's own kinds, made as the app makes them, over the app's own plugin files (a registry per process, as the store's
 * classes make them). An update's swap point is asked as AppNav asks it once the slot is off the screen.
 */
@RunWith(AndroidJUnit4::class)
class PluginInstallDeviceTest {
    @get:Rule
    val plugins = PluginStateRule()

    private val context = plugins.context
    private val server = PluginListServer()
    private val http = ServiceHttp.defaultHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One process's plugins: its registry, the list and the installer over it, as the app makes them. */
    private inner class Process {
        val device = plugins.registry().apply { start() }
        val registry: PluginRegistry get() = device.registry
        val list = PluginList.of(context.filesDir, installed = { registry.plugins.filterNotNull().first().isNotEmpty() }, http = { http }, scope = scope)
        val installer = PluginInstaller.of(context.filesDir, context.cacheDir, registry, list, http = { http }, scope = scope) { false }

        /** Check again: the list read, and what the read brought (updates, withdrawals) done. */
        suspend fun read() {
            val before = installer.readsApplied.value
            list.checkAgain().join()
            withTimeout(PLUGIN_WAIT_MS) { installer.readsApplied.first { it > before } }
        }

        /** Install, as its card's button does, to its end: the card's state then. */
        suspend fun install(): InstallState? {
            val job = installer.install(SKELETON) ?: throw AssertionError("No install started: ${describe(registry.plugins.value)}")
            withTimeout(PLUGIN_WAIT_MS) { job.join() }
            return installer.states.value[SKELETON]
        }

        fun running(versionCode: Long): PluginInstance = device.running(SKELETON).also { assertEquals("version running", versionCode, it.versionCode) }
    }

    private val downloads: File get() = File(File(context.cacheDir, PluginStore.DIR), PluginStore.DOWNLOADS)

    @Before
    fun serveTheList() {
        PluginSource.override = server.source
    }

    @After
    fun putBackTheSource() {
        // The runner's source for the whole run: an address that answers nothing (L622).
        PluginSource.override = PluginSource.CLOSED
        scope.cancel()
        http.close()
        server.close()
    }

    @Test
    fun v1IsInstalledFromTheListStoredReadOnlyAndLoaded() = runBlocking<Unit> {
        val p = Process()
        server.list(Listed(SkeletonBuild.V1, "1.0.0"))
        p.read()
        assertEquals("installed in the first slot", 1, (p.install() as? InstallState.Installed)?.slot)
        p.device.await(SKELETON, "running") { it.status is PluginStatus.Running }
        val v1 = p.running(1)
        assertEquals("Skeleton", v1.tab.label)
        val apk = p.device.store.apk(SKELETON, 1)
        assertTrue("stored in the app's own files", apk.isFile && apk.path.startsWith(plugins.root.path + File.separator))
        assertFalse("stored read-only", apk.canWrite())
        assertEquals("the list, then its file, and nothing else", listOf("/index.json", server.path("1.0.0")), server.requests.toList())
        assertEquals("the download deleted", emptyList<String>(), downloads.list().orEmpty().toList())
    }

    @Test
    fun theForeignBuildIsRefusedAsAnInstallAndAsVersion2WithV1Kept() = runBlocking<Unit> {
        val p = Process()
        server.list(Listed(SkeletonBuild.Foreign, "2.0.0"))
        p.read()
        val refused = p.install() as? InstallState.Failed
        assertEquals("It isn't signed by NZStreamer, so nothing was installed.", refused?.line)
        assertTrue("nothing installed", p.registry.plugins.value.orEmpty().isEmpty())
        assertFalse("nothing stored", File(plugins.root, SKELETON).exists())

        server.list(Listed(SkeletonBuild.V1, "1.0.0"))
        p.read()
        assertTrue(p.install() is InstallState.Installed)
        val v1 = p.device.await(SKELETON, "running") { it.status is PluginStatus.Running }.let { p.running(1) }
        server.list(Listed(SkeletonBuild.V1, "1.0.0"), Listed(SkeletonBuild.Foreign, "2.0.0"))
        p.read()
        assertTrue("the foreign build downloaded as an update", server.requests.count { it == server.path("2.0.0") } == 2)
        assertSame("v1 runs on", v1, p.running(1))
        assertNull("no update waits", p.device.listed(SKELETON).waitingVersion)
        assertFalse("nothing of it stored", p.device.store.apk(SKELETON, 2).parentFile!!.exists())
        assertEquals("no class loader made for it", 1, p.device.loader.classLoadersMade)
        assertEquals("the download deleted", emptyList<String>(), downloads.list().orEmpty().toList())
    }

    @Test
    fun v2ListedIsDownloadedAndSwappedAndSaysUpdatedTo2() = runBlocking<Unit> {
        val p = Process()
        server.list(Listed(SkeletonBuild.V1, "1.0.0"))
        p.read()
        p.install()
        val v1 = p.device.await(SKELETON, "running") { it.status is PluginStatus.Running }.let { p.running(1) }

        server.list(Listed(SkeletonBuild.V1, "1.0.0"), Listed(SkeletonBuild.V2, "2.0.0"))
        p.read()
        assertEquals("v2 waits for the swap point", 2L, p.device.listed(SKELETON).waitingVersion)
        assertSame("v1 runs on meanwhile", v1, p.running(1))
        assertTrue("the swap", p.registry.atSwapPoint(SKELETON))
        p.running(2)
        assertFalse("v1's folder deleted", p.device.store.apk(SKELETON, 1).parentFile!!.exists())
        assertFalse("v2 stored read-only", p.device.store.apk(SKELETON, 2).canWrite())
        val update = p.installer.recentUpdate(p.device.listed(SKELETON)) ?: throw AssertionError("No update to tell of")
        assertEquals("Updated to 2 today", PluginInstaller.updatedLine(update, System.currentTimeMillis(), ZoneId.systemDefault()))
    }

    @Test
    fun aFileWhoseSha256IsntTheListsIsRefused() = runBlocking<Unit> {
        val p = Process()
        server.list(Listed(SkeletonBuild.V1, "1.0.0", sha256 = Sha256.of(SkeletonBuild.V2.bytes())))
        p.read()
        assertEquals("The file doesn't match the list, so nothing was installed.", (p.install() as? InstallState.Failed)?.line)
        assertTrue("its file was asked for", server.requests.contains(server.path("1.0.0")))
        assertTrue("nothing installed", p.registry.plugins.value.orEmpty().isEmpty())
        assertFalse("nothing stored", File(plugins.root, SKELETON).exists())
        assertEquals("no class loader made", 0, p.device.loader.classLoadersMade)
        assertEquals("the download deleted", emptyList<String>(), downloads.list().orEmpty().toList())
    }

    @Test
    fun theNewerBuildListedAboveV2IsNotOfferedAndSaysItNeedsANewerNZStreamer() = runBlocking<Unit> {
        val p = Process()
        server.list(Listed(SkeletonBuild.V1, "1.0.0"))
        p.read()
        p.install()
        p.device.await(SKELETON, "running") { it.status is PluginStatus.Running }
        server.list(Listed(SkeletonBuild.V1, "1.0.0"), Listed(SkeletonBuild.V2, "2.0.0"), Listed(SkeletonBuild.Newer, "3.0.0"))
        p.read()
        assertEquals("v2 comes, not 3", 2L, p.device.listed(SKELETON).waitingVersion)
        assertTrue(p.registry.atSwapPoint(SKELETON))
        p.running(2)
        p.read()
        assertFalse("3 never downloaded", server.requests.contains(server.path("3.0.0")))

        val installed = p.device.listed(SKELETON)
        val offer = p.list.state.value.index?.offer(SKELETON, installed.versionCode, Build.VERSION.SDK_INT, PluginCompat())
            ?: throw AssertionError("The skeleton isn't listed")
        assertNull("nothing offered", offer.offered)
        val newer = offer.needsNewer ?: throw AssertionError("Nothing listed that needs a newer NZStreamer")
        assertEquals("Version 2 · 3 needs a newer NZStreamer", PluginInstaller.needsNewerLine(installed.versionName, newer.versionName))
    }

    @Test
    fun v2WithdrawnGoesOffAtTheSwapPointAndStaysOff() = runBlocking<Unit> {
        val p = Process()
        server.list(Listed(SkeletonBuild.V2, "2.0.0"))
        p.read()
        p.install()
        val v2 = p.device.await(SKELETON, "running") { it.status is PluginStatus.Running }.let { p.running(2) }

        server.list(Listed(SkeletonBuild.V2, "2.0.0"), withdrawn = setOf(2))
        p.read()
        assertTrue("withdrawn", p.device.listed(SKELETON).withdrawn)
        assertSame("it runs on until its slot leaves the screen", v2, p.running(2))
        assertTrue("let go at the swap point", p.registry.atSwapPoint(SKELETON))
        assertEquals(PluginStatus.Withdrawn, p.device.listed(SKELETON).status)

        val next = plugins.registry().apply { start() }
        assertEquals("off at the next start", PluginStatus.Withdrawn, next.await(SKELETON, "withdrawn") { it.status == PluginStatus.Withdrawn }.status)
        assertEquals("never loaded there", 0, next.loader.classLoadersMade)
    }

    @Test
    fun removeTakesEveryFileAndSecretAndKeepsWhatWasWatched() = runBlocking<Unit> {
        val key = WatchKey.item(SKELETON, "ev-1:main-card")
        try {
            val p = Process()
            server.list(Listed(SkeletonBuild.V1, "1.0.0"))
            p.read()
            p.install()
            p.device.await(SKELETON, "running") { it.status is PluginStatus.Running }
            val host = p.running(1).host
            host.secrets.put("key", "kestrel-test-key")
            File(host.dataDir, "kept.json").writeText("{}")
            File(host.cacheDir, "pages").writeText("cached")
            // Half a minute in: never in Up next, whatever happens to the test.
            context.container.library.recordProgress(WatchState(key, WatchKind.Item, title = "Kestrel Cup · Main card", updatedAt = 0), 30_000, 7_200_000)

            p.installer.remove(SKELETON)
            assertTrue("no plugin", p.registry.plugins.value.orEmpty().isEmpty())
            assertFalse("its code and data gone", File(plugins.root, SKELETON).exists())
            assertFalse("its cache gone", File(File(context.cacheDir, PluginStore.DIR), SKELETON).exists())
            assertNull("its secret gone", context.container.data.secrets.get("${PluginStore.SECRETS}$SKELETON:key"))
            assertTrue("its entry gone", p.device.store.readRegistry().plugins.isEmpty())
            assertNotNull("what was watched kept", context.container.library.state(key).first())
        } finally {
            // The row out of the app's library again: the library itself has no delete for a watch row.
            SQLiteDatabase.openDatabase(context.getDatabasePath(LIBRARY).path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)
                .use { it.delete("watch_state", "`key` = ?", arrayOf(key)) }
        }
    }

    private companion object {
        /** The app's library database's file name (LibraryDatabase.create). */
        const val LIBRARY = "library.db"
    }
}
