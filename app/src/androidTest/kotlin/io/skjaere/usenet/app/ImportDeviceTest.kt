package io.skjaere.usenet.app

import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.media3.cast.Cast
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.cast.CastFakes
import io.skjaere.usenet.app.cast.CastSeams
import io.skjaere.usenet.app.cast.FakeConnector
import io.skjaere.usenet.app.cast.FakeLauncher
import io.skjaere.usenet.app.cast.FakeRoutes
import io.skjaere.usenet.app.cast.castRoute
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.link.PlayableFilm
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.WatchKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class ImportDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private var film: PlayableFilm? = null
    private var monitor: Instrumentation.ActivityMonitor? = null
    private val realLauncher = CastSeams.launcher

    @After
    fun restore() {
        monitor?.let(instrumentation::removeMonitor)
        onMainThread { container.playback.cancel() }
        container.playOn.choose(PlayChoice.Phone)
        CastSeams.launcher = realLauncher
        CastFakes.reset()
        film?.close()
    }

    /**
     * A 2 GB video picked instead of the .nzb used to be read whole (OutOfMemoryError, process
     * crash). A file:// uri reports no SIZE, so this goes through the bounded read.
     */
    @Test
    fun aHugeFileIsAnErrorNotACrash() = runBlocking<Unit> {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val video = File(app.cacheDir, "picked-by-mistake.mkv")
        try {
            RandomAccessFile(video, "rw").use { it.setLength(2_000_000_000) } // sparse: no disk space used
            val vm = ImportViewModel(app)
            vm.open(Uri.fromFile(video))
            val state = withTimeout(30_000) { vm.state.first { it is ImportUi.Error || it is ImportUi.Ready || it is ImportUi.NeedsPassword } }
            assertEquals(ImportUi.Error("This file is too large to be an NZB (over 64 MB)."), state)
        } finally {
            video.delete()
        }
    }

    /**
     * Import with a film's .nzb handed over, as another app opens one: its articles on the test's own server
     * ([PlayableFilm]), prepared and its file listed. Returns the NZB's id.
     */
    private fun importOpened(): String {
        val playable = PlayableFilm(FILM, "Copper Crown").also { film = it }
        runBlocking { playable.prepare() }
        val nzb = checkNotNull(playable.nzb)
        val opened = container.nzbHandoff.put(Result.success(nzb to "Copper Crown"))
        compose.setContent { AppTheme { ImportScreen(opened, onBack = {}, onProviders = {}) } }
        compose.waitUntil(60_000) { compose.onAllNodes(hasText("chapters.mkv")).fetchSemanticsNodes().isNotEmpty() }
        return PreparedNzb.idFor(nzb)
    }

    /** The player's starts, caught and never let through: what was asked of it. */
    private fun playerStarts(): List<Intent> {
        val started = CopyOnWriteArrayList<Intent>()
        monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.component?.className == PlayerActivity::class.java.name) Instrumentation.ActivityResult(0, null).also { started += intent } else null
        }.also(instrumentation::addMonitor)
        return started
    }

    /** W44 (i), L147: with This phone chosen, a file tapped plays in the player as it always has: by its URI, with no launch. */
    @Test
    fun aFileTappedWithThisPhonePlaysInThePlayerAsBefore() {
        val nzbId = importOpened()
        val started = playerStarts()
        compose.onNode(hasText("chapters.mkv") and hasClickAction()).performClick()
        compose.waitUntil(10_000) { started.isNotEmpty() }
        val asked = UsenetUris.parse(started.single().getStringExtra(PlayerActivity.EXTRA_URI).orEmpty())
        assertEquals(nzbId, asked?.nzbId)
        assertEquals("chapters.mkv", asked?.name)
        assertEquals(PlayState.Idle, container.playback.state.value)
    }

    /**
     * W44 (i), L147: with a Cast device connected (fakes, L73), a file tapped casts: the play's start and its load go to the
     * device through the launcher, never to this phone's player nor as a launch for a screen to take, with a launch of the
     * file's own that saves no progress; the device plays it, and no progress row appears. Google's software never starts.
     */
    @Test
    fun aFileTappedWithACastDeviceConnectedCastsAndSavesNoProgress() {
        val nzbId = importOpened()
        val launcher = FakeLauncher()
        val connector = FakeConnector()
        val kitchen = castRoute("Kitchen display")
        CastFakes.started(FakeRoutes(), connector, CopyOnWriteArrayList())
        CastSeams.launcher = { launcher }
        CastFakes.connected(kitchen, connector)
        container.playOn.choose(PlayChoice.Cast(kitchen.id, kitchen.name))
        compose.waitUntil(10_000) { container.playOn.target.value == PlayTarget.Cast(kitchen.id, kitchen.name, CastLink.Connected, kitchen.facts) }
        // Nothing left over from another test for this one to take.
        onMainThread { container.playback.withdraw() }
        val started = playerStarts()
        compose.onNode(hasText("chapters.mkv") and hasClickAction()).performClick()
        compose.waitUntil(60_000) { launcher.loads.isNotEmpty() }
        val launch = launcher.loads.single()
        assertEquals(listOf("Kitchen display"), launcher.started.map { it.first.name })
        assertEquals("Kitchen display", launch.cast?.name)
        assertTrue("the file's own launch, saving no progress", launch.imported)
        assertEquals("named as the NZB is", "Copper Crown", launch.title)
        assertEquals(WatchKey.release(nzbId, launch.release.fileIndex), launch.watchKey)
        assertEquals("chapters.mkv", UsenetUris.parse(launch.uri)?.name)
        launcher.plays()
        compose.waitUntil(10_000) { container.playback.state.value == PlayState.Idle }
        assertTrue("never this phone's player", started.isEmpty())
        assertNull("no launch for a screen to take", runBlocking { withTimeoutOrNull(1_000) { container.playback.launches.first() } })
        assertNull("no progress row", runBlocking { container.library.state(launch.watchKey).first() })
        assertTrue("Google's Cast software unstarted", onMainThread { Cast.getSingletonInstance(context).needsInitialization() })
    }

    private companion object {
        /** Invented: a TMDB id nothing real has. */
        const val FILM = 991_771
    }
}
