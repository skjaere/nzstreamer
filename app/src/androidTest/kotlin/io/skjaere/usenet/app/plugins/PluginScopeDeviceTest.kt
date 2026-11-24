package io.skjaere.usenet.app.plugins

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.plugin.NzPlugin
import io.skjaere.usenet.plugin.PluginHost
import io.skjaere.usenet.plugin.PluginNav
import io.skjaere.usenet.plugin.PluginTab
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An exception that escapes a plugin's own scope, on a device (L619, L635, L595): an in-process fake, Kestrel, put in a
 * slot of the app's own registry through its test seam (L622), starts work in its host's scope, on the main thread, that
 * throws. NZStreamer goes on; the version is off "after an error" at once, then its scope cancelled and its close()
 * called once none of its pages is drawn (none is here); and the crash guard's crash.json names it as an error, so it
 * is off at the next start too. Kestrel's classes are this test's own, outside any plugin's package, so no frame could
 * blame it: the scope's handler names it by its instance.
 */
@RunWith(AndroidJUnit4::class)
class PluginScopeDeviceTest {
    @get:Rule
    val plugins = PluginStateRule()

    @Test
    fun anExceptionEscapingAPluginsScopeTurnsItsVersionOffAndNzStreamerGoesOn() = runBlocking<Unit> {
        val registry = plugins.app
        val kestrel = Kestrel()
        registry.installForTest(kestrel, KESTREL)
        val running = registry.await(KESTREL, "running") { it.status is PluginStatus.Running }
        val instance = (running.status as PluginStatus.Running).instance

        val refresh = kestrel.refresh(instance.host)
        registry.await(KESTREL, "off after an error") { it.status == PluginStatus.OffAfterError }
        // Listed off first, so its slot draws none of its pages after its close(): the registry lets it go just after,
        // once none of its pages is drawn, waiting PAGES_GONE_MS at most for a page still drawn.
        withTimeoutOrNull(LET_GO_MS) {
            instance.host.scope.coroutineContext.job.join()
            kestrel.closing.await()
        }
        // NZStreamer's process went on: this test runs in it, so had the failure ended it, the run would have ended here.
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
        /** Completed as its close() runs. */
        val closing = CompletableDeferred<Unit>()
        val closed: Boolean get() = closing.isCompleted

        override fun tab() = PluginTab("Kestrel", Icons.Filled.Build)

        @Composable
        override fun Page(page: String, arg: String?, host: PluginHost, nav: PluginNav) = Unit

        @Composable
        override fun Settings(host: PluginHost, nav: PluginNav) = Unit

        override fun close() {
            closing.complete(Unit)
        }

        /** Work of its own in its host's scope, as a list's refresh would be, that fails as it starts. */
        fun refresh(host: PluginHost): Job = host.scope.launch { throw IllegalStateException("The Kestrel Cup's list didn't read") }
    }

    private companion object {
        const val KESTREL = "kestrel"

        /** The registry's wait for the version's pages to go, with time to spare on a busy emulator. */
        const val LET_GO_MS = PluginRegistry.PAGES_GONE_MS + 4_000
    }
}
