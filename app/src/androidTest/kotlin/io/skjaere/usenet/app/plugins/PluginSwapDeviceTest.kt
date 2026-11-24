package io.skjaere.usenet.app.plugins

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import dalvik.system.PathClassLoader
import io.skjaere.usenet.plugin.FileInfo
import io.skjaere.usenet.plugin.PluginFilePicker
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An update's swap from the app's own storage, on a device (L581, L595, L635): the skeleton's v2 is installed while v1's
 * instance runs, stored beside it, and waits; at the swap point v2 loads in this same process under a new instance key
 * and a loader of its own, v1's scope is cancelled and v1's folder deleted. v1's instance, which a play already started
 * would still hold, still answers: its dex stays open after its file is gone, for its tab() and for a class of v1's that
 * nothing had asked for yet, while a loader made now over that file finds nothing.
 */
@RunWith(AndroidJUnit4::class)
class PluginSwapDeviceTest {
    @get:Rule
    val plugins = PluginStateRule()

    private val context = plugins.context
    private val appLoader = PluginLoader::class.java.classLoader!!

    @Test
    fun v2TakesOverInThisProcessWhileV1sKeptInstanceStillAnswers() = runBlocking<Unit> {
        val pid = Process.myPid()
        val registry = plugins.registry().apply { start(asked = false) }
        assertTrue("v1 installed", registry.install(SkeletonBuild.V1, SkeletonBuild.V1.download(context)) is InstallResult.Installed)
        registry.firstScreen()
        registry.await(SKELETON, "running v1") { it.status is PluginStatus.Running }
        val v1 = registry.running(SKELETON)
        val v1Loader = v1.plugin.javaClass.classLoader!!
        val v1File = registry.store.apk(SKELETON, 1)
        val v2File = registry.store.apk(SKELETON, 2)

        assertSame("v2 waits for the swap point", InstallResult.UpdateWaiting, registry.install(SkeletonBuild.V2, SkeletonBuild.V2.download(context)))
        assertTrue("v2 waiting", registry.listed(SKELETON).updateWaiting)
        assertSame("v1's instance runs on meanwhile", v1, registry.running(SKELETON))
        assertTrue("v2 stored beside v1", v1File.isFile && v2File.isFile)

        assertTrue("the swap took place", registry.registry.atSwapPoint(SKELETON))
        val v2 = registry.running(SKELETON)
        val v2Loader = v2.plugin.javaClass.classLoader!!
        assertEquals("v2 runs", 2L, v2.versionCode)
        assertNotEquals("under a new instance key", v1.key, v2.key)
        assertTrue("from a loader of its own", v2Loader is PathClassLoader && v2Loader !== v1Loader)
        assertTrue("whose parent is the app's loader", v2Loader.parent === appLoader)
        assertEquals("in this same process", pid, Process.myPid())
        assertFalse("v1's scope cancelled", v1.host.scope.isActive)
        assertFalse("v1's folder deleted", v1File.parentFile!!.exists())
        assertFalse("v2's file read-only", v2File.canWrite())
        assertEquals("the update recorded", "2", registry.listed(SKELETON).lastUpdate?.versionName)
        assertEquals("v2 is the highest version loaded", 2L, registry.store.readRegistry().plugins.single().highestLoaded)

        // v1's code still runs from its open dex: its tab(), and a class it hadn't loaded, as a play's picker would be.
        assertEquals("Skeleton", v1.plugin.tab().label)
        val picker = Class.forName(SKELETON_PICKER, true, v1Loader).getField("INSTANCE").get(null) as PluginFilePicker
        assertTrue("the picker is v1's", picker.javaClass.classLoader === v1Loader)
        val files = listOf(FileInfo("Copper Crown/notes.nfo", 4_096, false), FileInfo("Copper Crown/Copper Crown.mkv", 1L shl 30, true))
        assertEquals("v1's picker answers", 1, picker.pick(files))
        // What v1 answers came from its open dex: a loader made now over its deleted file finds nothing of it.
        assertThrows(ClassNotFoundException::class.java) { PathClassLoader(v1File.path, appLoader).loadClass(SKELETON_PICKER) }
    }
}
