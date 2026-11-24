package io.skjaere.usenet.app.plugins

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.plugins.PluginListServer.Listed
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L620's daily read as the app wires it: each time MainActivity comes to the front, the plugin list is read if the last
 * attempt was a day ago, and only while a plugin is installed. The list is the test's own, on this device's loopback
 * address (PluginSource's test source, L622). With nothing installed nothing is asked; once read, a return within the
 * day asks nothing.
 */
@RunWith(AndroidJUnit4::class)
class PluginDailyReadDeviceTest {
    @get:Rule
    val plugins = PluginStateRule()

    private val context = plugins.context
    private val container = context.container
    private val server = PluginListServer()
    private val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClass(context, MainActivity::class.java)

    @Before
    fun serveTheList() {
        PluginSource.override = server.source
    }

    @After
    fun putBackTheSource() {
        // The runner's source for the whole run: an address that answers nothing (L622).
        PluginSource.override = PluginSource.CLOSED
        server.close()
    }

    /** MainActivity brought to the front, as a return to the app does, then closed. */
    private fun cameToTheFront() = ActivityScenario.launch<MainActivity>(launch).use { }

    /** Long enough for a read a resume started to reach the list: what is asked by then is all a resume asked. */
    private fun settle() = SystemClock.sleep(QUIET_MS)

    @Test
    fun aReturnReadsTheListADayOnWhileAPluginIsInstalledAndNeverWithNone() = runBlocking<Unit> {
        server.list(Listed(SkeletonBuild.V1, "1.0.0"))
        val copy = PluginListCache.of(context.filesDir)
        val dayAgo = System.currentTimeMillis() - 25 * HOUR_MS
        copy.write(ListCopy(attemptedAt = dayAgo))

        cameToTheFront()
        settle()
        assertEquals("nothing installed: nothing asked", emptyList<String>(), server.requests.toList())
        assertEquals("nor tried", dayAgo, copy.read().attemptedAt)

        val file = SkeletonBuild.V1.download(context)
        assertTrue("installed", container.plugins.install(file, SkeletonBuild.V1.request(Sha256.of(file))) is InstallResult.Installed)
        cameToTheFront()
        val deadline = SystemClock.uptimeMillis() + PLUGIN_WAIT_MS
        while (copy.read().readAt == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals("a day on, with a plugin installed: one read", listOf("/index.json"), server.requests.toList())

        cameToTheFront()
        settle()
        assertEquals("within the day: nothing more", listOf("/index.json"), server.requests.toList())
    }

    private companion object {
        const val HOUR_MS = 60 * 60 * 1000L
        const val QUIET_MS = 2_000L
    }
}
