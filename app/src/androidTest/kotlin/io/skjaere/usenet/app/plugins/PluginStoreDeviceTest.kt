package io.skjaere.usenet.app.plugins

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import dalvik.system.PathClassLoader
import io.skjaere.usenet.plugin.NzPlugin
import io.skjaere.usenet.plugin.PluginApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * The skeleton plugin's real builds in the app's own storage, on a device (L595, L616, L583, L584, L637), where the JVM
 * tests can't go: Android's own reading of an archive, its signer and its typed meta-data, and a PathClassLoader over a
 * read-only file in filesDir whose parent is the app's loader, in the app's own process. Each build goes through the
 * registry's install as a download would (the call the installer makes once its download is checked against the list),
 * and a fresh registry over the same files loads what was stored, as the next start would. An install checks the file
 * alone and never makes a class loader; a start loads, so each refusal is shown there too, stored as if it had passed,
 * refused before its loader is made: the loader's own count stays 0.
 *
 * M-load, from a registry's start to the skeleton's instance (the hash, the archive's checks, the PathClassLoader, the
 * entry class), for the stored file's first load and a later one, goes to the instrumentation's status as m-load.
 */
@RunWith(AndroidJUnit4::class)
class PluginStoreDeviceTest {
    @get:Rule
    val plugins = PluginStateRule()

    private val context = plugins.context
    private val appLoader = PluginLoader::class.java.classLoader!!

    @Test
    fun v1LoadsFromThePrivateStoreReadOnlyInThisProcess() = runBlocking<Unit> {
        // Installed through a registry no screen has asked yet: the stored file's first load is the next start's.
        val installer = plugins.registry().apply { start(asked = false) }
        val installed = installer.install(SkeletonBuild.V1, SkeletonBuild.V1.download(context))
        assertEquals("v1 installed in the first slot", 1, (installed as? InstallResult.Installed)?.slot)
        val apk = installer.store.apk(SKELETON, 1)
        assertTrue("v1 stored in the app's own files", apk.isFile && apk.path.startsWith(plugins.root.path + File.separator))
        assertFalse("v1's stored file is read-only", apk.canWrite())
        assertEquals("nothing of it loads before a screen asks", 0, installer.loader.classLoadersMade)

        val (first, firstMs) = loadAtAStart()
        val (later, laterMs) = loadAtAStart()
        PluginMeasure.say(
            "m-load",
            "skeleton v1 from the private store, a registry's start to its instance, API ${Build.VERSION.SDK_INT}: " +
                String.format(Locale.ROOT, "the stored file's first load %.1f ms, a later one %.1f ms", firstMs, laterMs),
        )

        val instance = first.running(SKELETON)
        val entry = instance.plugin.javaClass
        val loader = entry.classLoader!!
        assertEquals(SKELETON_ENTRY, entry.name)
        assertTrue("the entry's loader is a PathClassLoader of its own", loader is PathClassLoader && loader !== appLoader)
        assertTrue("whose parent is the app's loader", loader.parent === appLoader)
        assertTrue("over the stored file", loader.toString().contains("\"${apk.path}\""))
        assertSame("the API the entry implements is the app's own", NzPlugin::class.java, entry.interfaces.single())
        assertEquals("Skeleton", instance.tab.label)
        assertEquals("one class loader made, at its start", 1, first.loader.classLoadersMade)
        assertEquals("a later start makes its own", 1, later.loader.classLoadersMade)
        assertFalse("the stored file stays read-only", apk.canWrite())
        // Loaded in this process, by a loader of its own (above). The test runs in the app's process, so a load that
        // restarted it would have ended this run: no assertion here could add to that.
        assertEquals("v1 is the highest version loaded", 1L, first.store.readRegistry().plugins.single().highestLoaded)
    }

    @Test
    fun aStoredCopyMadeWritableIsRefusedBeforeItLoads() = runBlocking<Unit> {
        val installer = plugins.registry().apply { start(asked = false) }
        assertTrue("v1 installed", installer.install(SkeletonBuild.V1, SkeletonBuild.V1.download(context)) is InstallResult.Installed)
        val apk = installer.store.apk(SKELETON, 1)
        assertTrue("the stored file made writable", apk.setWritable(true, true) && apk.canWrite())

        val next = plugins.registry().apply { start() }
        val refused = next.await(SKELETON, "refused") { it.status is PluginStatus.Refused }
        assertEquals(Refusal.Writable, (refused.status as PluginStatus.Refused).refusal)
        assertEquals("its file changed after it was installed", Refusal.Writable.reason)
        assertEquals("no class loader made over it", 0, next.loader.classLoadersMade)
    }

    @Test
    fun theForeignSignedBuildIsRefusedBeforeAnyClassOfItsLoads() = runBlocking<Unit> {
        val registry = plugins.registry().apply { start() }
        val download = SkeletonBuild.Foreign.download(context)
        val result = registry.install(SkeletonBuild.Foreign, download)
        assertEquals(Refusal.Signer, (result as? InstallResult.Refused)?.refusal)
        assertEquals("It isn't signed by NZStreamer, so nothing was installed.", Refusal.Signer.installLine)
        assertEquals("an install checks the file alone: no class loader", 0, registry.loader.classLoadersMade)
        assertNothingStored(registry)

        // Stored as if both its file and its recorded SHA-256 had been replaced: the next start's checks refuse it too.
        val sha256 = Sha256.of(download)
        registry.store.writeVersion(SKELETON, 2, download, sha256)
        registry.store.writeRegistry(RegistryFile(listOf(PluginRecord(SKELETON, "Skeleton", 2, "2", sha256, slot = 1, installedAt = 0))))
        val next = plugins.registry().apply { start() }
        val refused = next.await(SKELETON, "refused") { it.status is PluginStatus.Refused }
        assertEquals(Refusal.Signer, (refused.status as PluginStatus.Refused).refusal)
        assertEquals("no class loader made over it at a start", 0, next.loader.classLoadersMade)
    }

    @Test
    fun theNewerBuildIsRefusedForTheApiItNeeds() = runBlocking<Unit> {
        val newer = SkeletonBuild.Newer.download(context)
        // Android reads each build's API stamp as two integers (L584): v1's is this NZStreamer's, newer's the next minor.
        val reader = AndroidArchiveReader(context.packageManager)
        val stamp = { apk: File -> reader.read(apk)?.meta?.let { it.int(PluginApi.META_API_MAJOR) to it.int(PluginApi.META_API_MINOR) } }
        assertEquals(PluginApi.MAJOR to PluginApi.MINOR, stamp(SkeletonBuild.V1.download(context)))
        assertEquals(PluginApi.MAJOR to PluginApi.MINOR + 1, stamp(newer))

        val registry = plugins.registry().apply { start() }
        val result = registry.install(SkeletonBuild.Newer, newer)
        assertEquals(Refusal.Incompatible, (result as? InstallResult.Refused)?.refusal)
        assertEquals("It was made for another NZStreamer version, so nothing was installed.", Refusal.Incompatible.installLine)
        assertEquals("an install checks the file alone: no class loader", 0, registry.loader.classLoadersMade)
        assertNothingStored(registry)

        // Stored as if it had passed, its SHA-256 recorded: the next start's checks refuse it before any class of it loads.
        val sha256 = Sha256.of(newer)
        registry.store.writeVersion(SKELETON, 3, newer, sha256)
        registry.store.writeRegistry(RegistryFile(listOf(PluginRecord(SKELETON, "Skeleton", 3, "3", sha256, slot = 1, installedAt = 0))))
        val next = plugins.registry().apply { start() }
        val refused = next.await(SKELETON, "refused") { it.status is PluginStatus.Refused }
        assertEquals(Refusal.Incompatible, (refused.status as PluginStatus.Refused).refusal)
        assertEquals("no class loader made over it at a start", 0, next.loader.classLoadersMade)
    }

    @Test
    fun aChangedByteIsRefusedByItsSha256() = runBlocking<Unit> {
        val registry = plugins.registry().apply { start() }
        val download = SkeletonBuild.V1.download(context)
        val listed = Sha256.of(download)
        changeAByte(download)
        val result = registry.install(SkeletonBuild.V1, download, listed)
        assertEquals(Refusal.Hash, (result as? InstallResult.Refused)?.refusal)
        assertEquals("an install checks the file alone: no class loader", 0, registry.loader.classLoadersMade)
        assertNothingStored(registry)

        // A byte of the stored copy changed after its install: the next start refuses it before anything reads it.
        val installer = plugins.registry().apply { start(asked = false) }
        assertTrue("v1 installed", installer.install(SkeletonBuild.V1, SkeletonBuild.V1.download(context)) is InstallResult.Installed)
        val apk = installer.store.apk(SKELETON, 1)
        assertTrue("the stored file opened for the change", apk.setWritable(true, true))
        changeAByte(apk)
        assertTrue("and made read-only again", apk.setReadOnly())
        val next = plugins.registry().apply { start() }
        val refused = next.await(SKELETON, "refused") { it.status is PluginStatus.Refused }
        assertEquals(Refusal.Hash, (refused.status as PluginStatus.Refused).refusal)
        assertEquals("no class loader made over it at a start", 0, next.loader.classLoadersMade)
    }

    /** A fresh registry over the same files, started as a process starts: the milliseconds from its start to the skeleton's instance. */
    private suspend fun loadAtAStart(): Pair<DeviceRegistry, Double> {
        val registry = plugins.registry()
        val began = SystemClock.elapsedRealtimeNanos()
        registry.start()
        registry.await(SKELETON, "running") { it.status is PluginStatus.Running }
        return registry to (SystemClock.elapsedRealtimeNanos() - began) / 1_000_000.0
    }

    private fun assertNothingStored(registry: DeviceRegistry) {
        assertEquals("nothing listed", emptyList<String>(), registry.registry.plugins.value.orEmpty().map { it.id })
        assertFalse("nothing of it stored", File(plugins.root, SKELETON).exists())
    }
}
