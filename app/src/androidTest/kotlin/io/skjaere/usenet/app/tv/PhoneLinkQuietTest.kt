package io.skjaere.usenet.app.tv

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.GapFixtures
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.LocalNntp
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.R
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.link.PairedPhones
import io.skjaere.usenet.app.mockOnEngine
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.IndexerRecord
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.link.LinkMessage.Play
import io.skjaere.usenet.link.Playback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A paired phone's link coming up while a player is in front, on a real TV (tv36): the player says nothing of it (#320,
 * which took L35's "Galaxy phone is connected" away) and its focus stays where it was. Neither the link that carried the
 * Play that started the play, nor the same phone's link opening again later (its app back in front, its remote opening),
 * nor another paired phone's. And the phone whose play it is hears it titled (L34), another phone its state alone.
 *
 * The play is a film whose remembered release is in the NZB store, on a server inside the test's process (L40), as
 * TvIncomingPlayTest plays it: no mock, no indexer asked. The phones and the film are invented.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PhoneLinkQuietTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    /** The player hides the system bars: Android's hint about that would take its window. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container = instrumentation.targetContext.container
    private val pages = LinkPages(compose)
    private val home = HomeContentShown()
    private val phones = PairedPhones()
    private val linked = mutableListOf<LinkedPhone>()
    private val local = LocalNntp()
    private val realFetch = PlaybackCoordinator.fetchNzb
    private val realLookUp = PlayerActivity.lookUpSegments
    private val realRead = PlayerActivity.readPlayback
    private lateinit var saved: AppSettings
    private var grabs = 0

    /** Past [assumeTv]: on a phone the class is skipped, and there is nothing to put back. */
    private var set = false

    @Before
    fun setUp() {
        assumeTv()
        set = true
        phones.clear()
        home.show()
        instrumentation.setInTouchMode(false)
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        PlayerActivity.readPlayback = { realRead(it).copy(matchFrameRate = false) }
        PlaybackCoordinator.fetchNzb = { c, indexer, item -> grabs++; realFetch(c, indexer, item) }
        runBlocking {
            saved = container.settings.settings.first()
            // One indexer at an address nothing answers, the rest switched off: the TV can play, and asks none.
            container.settings.restore(saved.copy(indexers = saved.indexers.map { it.copy(enabled = false) } + IndexerRecord(INDEXER, "Unanswered", "http://10.0.2.2:9")))
            engineSetup("the engine on the test's own NNTP server") {
                mockOnEngine()
                container.engine.configure(EngineSettings(listOf(local.provider)))
            }
            val fixtures = GapFixtures(local, INDEXER, WatchTarget(WatchKind.Movie, TITLE, movieTmdbId = FILM))
            val release = fixtures.release("chapters.mkv", fixtures.media("chapters.mkv"), PART, "notice-bell", RELEASE) { emptySet() }
            val remembered = fixtures.saved("notice-bell", release.prepared).toRemembered()
            container.library.recordProgress(WatchState(WatchKey.movie(FILM), WatchKind.Movie, movieTmdbId = FILM, title = TITLE, updatedAt = 0), 0, DURATION_MS, remembered)
        }
    }

    @After
    fun tearDown() {
        if (!set) return
        linked.forEach { it.close() }
        instrumentation.setInTouchMode(true)
        PlaybackCoordinator.fetchNzb = realFetch
        PlayerActivity.lookUpSegments = realLookUp
        PlayerActivity.readPlayback = realRead
        container.playback.cancel()
        live<PlayerActivity>(Stage.RESUMED).forEach { onMainThread { it.finish() } }
        local.close()
        phones.restore()
        home.restore()
        runBlocking {
            if (::saved.isInitialized) container.settings.restore(saved)
            container.library.recordProgress(WatchState(WatchKey.movie(FILM), WatchKind.Movie, movieTmdbId = FILM, title = TITLE, updatedAt = 0), 0, DURATION_MS)
        }
    }

    private inline fun <reified A : Activity> live(stage: Stage): List<A> =
        onMainThread { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).filterIsInstance<A>() }

    private fun phone(name: String) = LinkedPhone(name, pages, phones).also { linked += it }

    /** [phone]'s Play of the film from Home, playing: its session. */
    private suspend fun playing(phone: LinkedPhone): String {
        val session = phone.session(phone.ask(Play(1, movie = Play.Movie(FILM, TITLE, 2026))))
        waitFor("the phone's play playing", 45_000) { container.playerInFront.now.value?.takeIf { it.remote?.session == session && it.state == Playback.Playing } }
        return session
    }

    /** What the player's moment label says now; null while it shows nothing. */
    private fun moment(): String? = live<PlayerActivity>(Stage.RESUMED).firstOrNull()?.let { player ->
        onMainThread { player.findViewById<TextView>(R.id.moment_label)?.takeIf { it.isShown }?.text?.toString() }
    }

    /** Everything the label came to say within [forMs], in order, each once. */
    private suspend fun said(forMs: Long): List<String> {
        val said = mutableListOf<String>()
        val until = SystemClock.elapsedRealtime() + forMs
        while (SystemClock.elapsedRealtime() < until) {
            moment()?.takeIf { it !in said }?.let { said += it }
            delay(POLL_MS)
        }
        return said
    }

    /** The view focused in the player now. */
    private fun focused(): View? = live<PlayerActivity>(Stage.RESUMED).firstOrNull()?.let { player -> onMainThread { player.currentFocus } }

    @Test
    fun theLinkThatCarriedThePlayShowsNothing() = runBlocking<Unit> {
        pages.launch().use {
            val galaxy = phone("Invented phone")
            // Watched from the Play on, through its player's start: a notice for this link at any moment shows here.
            val said = CopyOnWriteArrayList<String>()
            val watching = launch(Dispatchers.Default) {
                while (isActive) {
                    moment()?.takeIf { galaxy.name in it || "connected" in it }?.let { said += it }
                    delay(POLL_MS)
                }
            }
            val session = playing(galaxy)
            delay(WATCH_MS)
            watching.cancelAndJoin()
            assertEquals("its own link's coming up says nothing", emptyList<String>(), said.distinct())
            // The play is its own: its link hears it titled (L34).
            val status = waitFor("the play's status, titled") { galaxy.statuses().lastOrNull { it.session == session && it.state == Playback.Playing } }
            assertTrue("titled for its owner", status.mine && status.title == TITLE && status.watchKey == WatchKey.movie(FILM))
            assertEquals(0, grabs)
        }
    }

    @Test
    fun theOwnersLinkOpeningAgainLaterShowsNothing() = runBlocking<Unit> {
        pages.launch().use {
            val galaxy = phone("Invented phone")
            playing(galaxy)
            val before = focused()
            // The phone's app back in front: its link again, its remote opening.
            linked += galaxy.again(phones)
            assertEquals("the player says nothing of it", emptyList<String>(), said(WATCH_MS))
            assertSame("its focus stays", before, focused())
        }
    }

    @Test
    fun anotherPairedPhonesLinkShowsNothing() = runBlocking<Unit> {
        pages.launch().use {
            val galaxy = phone("Invented phone")
            val session = playing(galaxy)
            val before = focused()
            val tablet = phone("Invented tablet")
            assertEquals("the player says nothing of it", emptyList<String>(), said(WATCH_MS))
            assertSame("its focus stays", before, focused())
            // Its play isn't the tablet's: the tablet hears its state alone (L34).
            val status = waitFor("the play's state on the tablet's link") { tablet.statuses().lastOrNull { it.session == session } }
            assertTrue("untitled for another phone", !status.mine && status.title == null && status.watchKey == null)
        }
    }

    private companion object {
        /** How long the label is watched after a link comes up: longer than the 4 s the notice said itself for. */
        const val WATCH_MS = 6_000L
        const val POLL_MS = 50L

        /** An invented film's TMDB id; nothing real has it. */
        const val FILM = 900051
        const val TITLE = "Copper Lantern"
        const val INDEXER = "phone-notice-test"
        const val RELEASE = "Copper.Lantern.2026.1080p.WEB-DL.H.264-LINK"
        const val PART = 32_000
        const val DURATION_MS = 360_000L
    }
}
