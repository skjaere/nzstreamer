package io.skjaere.usenet.app.plugins

import android.os.Process
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.plugin.NzPlugin
import io.skjaere.usenet.plugin.PluginHost
import io.skjaere.usenet.plugin.PluginNav
import io.skjaere.usenet.plugin.PluginTab
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An exception that escapes a plugin's own scope, on a device (L619, L635, L595): an in-process fake, Kestrel, put in a
 * slot of the app's own registry through its test seam (L622), starts work in its host's scope, on the main thread, that
 * throws. NZStreamer goes on; the version is off "after an error" at once, its scope cancelled and its close() called;
 * and the crash guard's crash.json names it as an error, so it is off at the next start too. Kestrel's classes are this
 * test's own, outside any plugin's package, so no frame could blame it: the scope's handler names it by its instance.
 */
@RunWith(AndroidJUnit4::class)
class PluginScopeDeviceTest {
    @get:Rule
    val plugins = PluginStateRule()

    @Test
    fun anExceptionEscapingAPluginsScopeTurnsItsVersionOffAndNzStreamerGoesOn() = runBlocking<Unit> {
        val pid = Process.myPid()
        val registry = plugins.app
        val kestrel = Kestrel()
        registry.installForTest(kestrel, KESTREL)
        val running = registry.await(KESTREL, "running") { it.status is PluginStatus.Running }
        val instance = (running.status as PluginStatus.Running).instance

        val refresh = kestrel.refresh(instance.host)
        registry.await(KESTREL, "off after an error") { it.status == PluginStatus.OffAfterError }
        assertEquals("NZStreamer's process goes on", pid, Process.myPid())
        var mainRuns = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync { mainRuns = true }
        assertTrue("and its main thread, where the work failed", mainRuns)
        assertTrue("the failed work ended", refresh.isCompleted && refresh.isCancelled)
        assertFalse("its scope cancelled", instance.host.scope.isActive)
        assertTrue("its close() called", kestrel.closed)
        assertEquals("crash.json names its version, as an error", listOf(Named(KESTREL, 1, error = true)), crashJson(plugins.root))
    }

    /** A plugin of the test's own, outside any plugin's package: its refresh fails in its host's scope, as a plugin's might. */
    private class Kestrel : NzPlugin {
        @Volatile
        var closed = false
            private set

        override fun tab() = PluginTab("Kestrel", Icons.Filled.Build)

        @Composable
        override fun Page(page: String, arg: String?, host: PluginHost, nav: PluginNav) = Unit

        @Composable
        override fun Settings(host: PluginHost, nav: PluginNav) = Unit

        override fun close() {
            closed = true
        }

        /** Work of its own in its host's scope, as a list's refresh would be, that fails as it starts. */
        fun refresh(host: PluginHost): Job = host.scope.launch { throw IllegalStateException("The Kestrel Cup's list didn't read") }
    }

    private companion object {
        const val KESTREL = "kestrel"
    }
}
