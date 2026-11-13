package io.skjaere.usenet.app.cast

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.GapFixtures
import io.skjaere.usenet.app.LocalNntp
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.casting.CastEnd
import io.skjaere.usenet.app.casting.CastEvent
import io.skjaere.usenet.app.casting.CastEventHub
import io.skjaere.usenet.app.casting.CastFeed
import io.skjaere.usenet.app.casting.CastLauncher
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.casting.CastLoadOutcome
import io.skjaere.usenet.app.casting.CastPlayerState
import io.skjaere.usenet.app.casting.closedAfterLongPause
import io.skjaere.usenet.app.casting.stopCastingTo
import io.skjaere.usenet.app.collection.ShowScreen
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.home.HomeScreen
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.link.RemoteSaidBar
import io.skjaere.usenet.app.link.loadingOn
import io.skjaere.usenet.app.link.onTvAt
import io.skjaere.usenet.app.link.onTvLine
import io.skjaere.usenet.app.link.stoppedOnAt
import io.skjaere.usenet.app.mockOnEngine
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.PlayMiniBar
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.SessionMetadata
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.CollectedShow
import io.skjaere.usenet.data.EpisodeMark
import io.skjaere.usenet.data.EpisodeRef
import io.skjaere.usenet.data.ShowSummary
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * After a cast (W36, L93; CsPhoneStopped) on a real device, over a fake Cast session and device player (L73), Google's
 * software never started: Stop casting on the bar ends the cast where the device was paused, saved there through the real
 * load's progress (its release served from the test's own NNTP server, LocalNntp, L99); the phone says so in the Undo
 * bar's look by the Cast icon, read once, Play goes back to this phone, and Resume on the Show page, the episode's row and
 * Home's Up next pick up at 32:10. A line made while the app isn't in front (the long pause's) waits until MainActivity
 * starts, and is said once. The show, the device and the release are invented; the library and settings are put back.
 */
@RunWith(AndroidJUnit4::class)
class CastAfterDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val library = container.library
    private val routes = FakeRoutes()
    private val connector = FakeConnector()
    private val kitchen = castRoute("Kitchen display")
    private val device = CastTo(kitchen.id, kitchen.name)
    private val events = CastEventHub()
    private val local = LocalNntp()
    private val episode = WatchTarget(WatchKind.Episode, WatchTitle.episode(SHOW_TITLE, 2, 4), showTmdbId = SHOW, season = 2, episode = 4)
    private val key = WatchKey.episode(SHOW, 2, 4)
    private val fixtures = GapFixtures(local, INDEXER, episode)
    private var tmdb: FakeTmdb? = null
    private var keyAdded = false
    private lateinit var saved: AppSettings
    private lateinit var remote: FakeRemotePlayer
    private val launcher: CastLauncher get() = container.castLauncher() ?: throw AssertionError("no cast launcher")

    /** Where the load's outcome is waited on, never the test's own runBlocking, which would wait for it to the end. */
    private val waits = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Every line the bar's host says, in order. */
    private val lines = CopyOnWriteArrayList<String>()

    private val show = ShowDetails(
        SHOW, SHOW_TITLE, null, 2021, null, null, null, "Ended", false, 52,
        listOf(SeasonSummary(2, null, 6, LocalDate.of(2022, 3, 1), null)),
        lastEpisode = EpisodeSummary(2, 6, "Episode 6", LocalDate.of(2022, 4, 5)), nextEpisode = null, ids = ExternalIds(tvdb = 991_622),
    )

    private val season = Season(
        SHOW, 2, null, null,
        (1..6).map { Episode(2, it, if (it == 4) "Low Water" else "Episode $it", null, LocalDate.of(2022, 3, 1).plusWeeks(it - 1L), 52, null) },
    )

    @Before
    fun setUp() {
        assumeFalse("a TV never casts (tv.TvNoCastTest)", container.television)
        runBlocking {
            assumeTrue("no Usenet provider saved, so Home shows the first-run screen", container.settings.providers.first().isNotEmpty())
            saved = container.settings.settings.first()
            container.settings.setIndexerSetupSkipped(true)
            if (container.settings.tmdbKey() == null) {
                container.settings.setTmdbKey("cast-after-device-test")
                keyAdded = true
            }
            // In the collection, with what TMDB said of it, as the refresher keeps it.
            library.addShow(CollectedShow(CollectedShow.idFor(SHOW), SHOW, null, null, null, SHOW_TITLE, 2021, null, 0, 0))
            library.saveSummaries(
                listOf(ShowSummary(SHOW, status = "Ended", seasons = "2:6", last = EpisodeMark(2, 6, "Episode 6", LocalDate.of(2022, 4, 5).toEpochDay()))),
                emptyList(), emptyList(),
            )
            forgetTheRelease()
        }
        tmdb = FakeTmdb(shows = mapOf(SHOW to show), seasons = { id, number -> if (id == SHOW && number == 2) season else null })
        CastFakes.started(routes, connector, CopyOnWriteArrayList())
        CastSeams.events = { events }
        Casting.artOf = { _, _ -> null }
        CastFakes.connected(kitchen, connector)
        remote = onMainThread { GoogleCast.connection.player(context) } as FakeRemotePlayer
        container.playOn.choose(PlayChoice.Cast(kitchen.id, kitchen.name))
        onMainThread { container.playback.withdraw() }
        waitUntil("Kitchen display connected") { container.playOn.target.value == PlayTarget.Cast(kitchen.id, kitchen.name, CastLink.Connected, kitchen.facts) }
        waits.launch(start = CoroutineStart.UNDISPATCHED) { container.remotePlaying.said.filterNotNull().collect { lines += it.text } }
    }

    @After
    fun restore() {
        waits.cancel()
        container.playOn.choose(PlayChoice.Phone)
        CastFakes.reset()
        tmdb?.close()
        local.close()
        if (!::saved.isInitialized) return
        runBlocking {
            forgetTheRelease()
            library.markEpisodes(listOf(EpisodeRef(SHOW, 2, 4, WatchTitle.episode(SHOW_TITLE, 2, 4))), watched = false)
            library.removeShow(SHOW)
            container.settings.restore(saved)
            if (keyAdded) container.settings.setTmdbKey(null)
        }
    }

    /** The release a play remembers for the episode goes with the test: its NZB names the test's own server. */
    private suspend fun forgetTheRelease() {
        library.lastRelease(key)?.let { library.forgetRelease(key, it) }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, millis: Long = 15_000) = compose.waitUntil(millis) { exists(matcher) }

    private fun waitUntil(what: String, timeoutMs: Long = 20_000, probe: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (!probe()) {
            if (SystemClock.uptimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(50)
        }
    }

    /** The page the test shows, the line the app says and the Playing on bar at its foot, as the phone's shell has them. */
    private var page by mutableStateOf("show")

    private fun pages() = compose.setContent {
        AppTheme {
            Box(Modifier.fillMaxSize()) {
                when (page) {
                    "show" -> ShowScreen(SHOW, onBack = {}, onRemote = {})
                    "home" -> HomeScreen(onSearch = {}, onRecent = {}, onSettings = {}, onSetup = { _, _ -> }, onShow = {}, onCollection = {}, onImport = {})
                }
                Column(Modifier.align(Alignment.BottomCenter)) {
                    RemoteSaidBar(container.remotePlaying)
                    PlayMiniBar(container.playback, playing = container.remotePlaying)
                }
            }
        }
    }

    /**
     * The episode's release on the test's own NNTP server, loaded on Kitchen display through the real load ([Casting]) as
     * the coordinator hands it over at its last step: its LOAD sent, and the load's outcome, waited on as the coordinator's
     * job waits on it.
     */
    private fun loadTheEpisode(): Deferred<CastLoadOutcome> = runBlocking {
        CastSeams.loader = { Casting.with(it) }
        engineSetup("the engine on the test's own NNTP server") {
            mockOnEngine()
            container.engine.configure(EngineSettings(listOf(local.provider)))
        }
        val posted = fixtures.release("chapters.mkv", fixtures.media("chapters.mkv"), PART, "cast-after", RELEASE) { emptySet() }
        val release = fixtures.saved(RELEASE, posted.prepared)
        val launch = PlayerLaunch(
            UsenetUris.build(posted.prepared.id, release.fileIndex, release.fileName, release.fileSize), SHOW_TITLE, EPISODE_LINE, key, episode, release,
            cast = device,
        )
        assertTrue("the service started", onMainThread { launcher.starting(device, SessionMetadata.starting(SHOW_TITLE, EPISODE_LINE, episode, container.appName)) })
        val load = launcher.load(launch)
        val outcome: Deferred<CastLoadOutcome> = waits.async { load.outcome() }
        waitUntil("the load on the device") { onMainThread { remote.currentMediaItem } != null }
        outcome
    }

    /**
     * The episode loaded on Kitchen display as [loadTheEpisode] does, and played there at the device's word: its content id
     * on the device.
     */
    private fun castTheEpisode(): String = runBlocking {
        val outcome = loadTheEpisode()
        val id = onMainThread { remote.currentMediaItem?.mediaId } ?: throw AssertionError("no item")
        remote.playing()
        onMainThread { events.tell(CastEvent.Status(CastPlayerState.Playing, null, id, 0, SystemClock.elapsedRealtime())) }
        assertEquals(CastLoadOutcome.Playing, withTimeout(20_000) { outcome.await() })
        // The device's own length, polled each second.
        waitUntil("the device's length followed") { Casting.feed.value?.durationMs == LENGTH }
        id
    }

    /**
     * L93, W36 (CsPhoneStopped 1-2): Stop casting on the bar, the device paused at 32:10: the phone says where it stopped,
     * once, by the Cast icon, the play saved there as one here is; Play goes back to this phone, and Resume, the episode's
     * row and Home's Up next pick up at 32:10.
     */
    @Test
    fun stopCastingSaysWhereItStoppedAndResumeTheRowAndUpNextPickUpThere() {
        pages()
        waitFor(hasText("Low Water"))
        val id = castTheEpisode()
        // Paused there from the remote: the device's player says so.
        onMainThread {
            remote.seekTo(PAUSED_AT)
            remote.pause()
            events.tell(CastEvent.Status(CastPlayerState.Paused, null, id, PAUSED_AT, SystemClock.elapsedRealtime()))
        }
        waitFor(hasText("Paused on ${kitchen.name}"))
        compose.onNode(hasContentDescription(stopCastingTo(kitchen.name)) and hasClickAction()).performClick()
        val line = stoppedOnAt(kitchen.name, PAUSED_AT)
        waitFor(hasText(line))
        assertEquals("read once as it comes", LiveRegionMode.Polite, compose.onNode(hasText(line)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))
        compose.waitUntil(10_000) { connector.ends.get() == 1 }
        compose.waitUntil(10_000) { container.playOn.chosen.value == PlayChoice.Phone }
        val state = runBlocking { withTimeout(10_000) { library.state(key).first { it?.positionMs == PAUSED_AT } } }
        assertFalse("not watched", state!!.watched)
        // The Show page: Resume from there, on this phone, and the episode's row.
        waitFor(hasText("Resume S02E04 · 32:10", substring = true))
        assertFalse("Play is on this phone again", exists(hasText("Resume S02E04 · 32:10", substring = true) and hasText(onTvLine(kitchen.name), substring = true)))
        waitFor(hasText("19:50 left"))
        // Home's Up next.
        compose.runOnIdle { page = "home" }
        waitFor(hasContentDescription("Resume $SHOW_TITLE S02E04"))
        waitFor(hasText("S02E04", substring = true) and hasText("20 min left", substring = true))
        // The line goes after about 4 s, said once.
        compose.waitUntil(10_000) { !exists(hasText(line)) }
        assertEquals(listOf(line), lines.toList())
    }

    /**
     * L82: Cancel in the cast step, the LOAD already sent, the device still loading: nothing plays there, so nothing shows
     * as playing there. The bar goes, the Show page offers Resume from where the episode was left and its row says what is
     * left, and nothing is said; the session stays, with the device chosen.
     */
    @Test
    fun cancelAfterTheLoadLeavesNothingShownAsPlayingThere() {
        runBlocking {
            library.recordProgress(
                WatchState(key, WatchKind.Episode, showTmdbId = SHOW, season = 2, episode = 4, title = WatchTitle.episode(SHOW_TITLE, 2, 4), updatedAt = 0),
                PAUSED_AT, LENGTH,
            )
        }
        pages()
        waitFor(hasText("Resume S02E04 · 32:10", substring = true))
        val outcome = loadTheEpisode()
        // Loaded from where the episode was left; the device still loading it.
        waitFor(hasText(loadingOn(kitchen.name)))
        waitFor(hasText("Playing S02E04 · 32:10", substring = true))
        // Cancel, as the coordinator's: its job first, then the launcher.
        outcome.cancel()
        launcher.stop()
        compose.waitUntil(10_000) { !exists(hasText(loadingOn(kitchen.name))) }
        waitUntil("nothing followed there") { container.remotePlaying.now.value == null }
        assertNull("the sheet's row says no play there", container.castPlaying?.now?.value)
        waitFor(hasText("Resume S02E04 · 32:10", substring = true))
        assertFalse(exists(hasText("Playing S02E04", substring = true)))
        assertFalse(exists(hasText(onTvAt(kitchen.name, PAUSED_AT))))
        waitFor(hasText("19:50 left"))
        // The session kept, the device still chosen (L82).
        assertEquals(0, connector.ends.get())
        assertEquals(PlayChoice.Cast(kitchen.id, kitchen.name), container.playOn.chosen.value)
        assertTrue("nothing said", lines.isEmpty())
    }

    /**
     * L93 (CsPhoneStopped 4): Kitchen display closes the session after a long pause while the app isn't in front: the line
     * waits until MainActivity starts, is said there once, and Play is back on this phone.
     */
    @Test
    fun theLongPausesLineWaitsUntilTheAppIsOpenedAndIsSaidOnce() {
        val load = "cast-after-load-${System.nanoTime()}"
        val launch = PlayerLaunch(
            UsenetUris.build("cast-after-nzb", 0, "harbor-lights.mkv", 2_000_000_000), SHOW_TITLE, EPISODE_LINE, key, episode,
            SavedRelease(INDEXER, RELEASE, RELEASE, null, 0, "cast-after-nzb", "harbor-lights.mkv", 2_000_000_000), cast = device,
        )
        onMainThread { Casting.follows(CastFeed(load, launch, CastPlayerState.Paused, PAUSED_AT, LENGTH)) }
        // The phone locked: no activity of the app's in front.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        waitUntil("the app out of front") { !container.inFront.value.value }
        // Twenty minutes later the device closes the session.
        connector.ended(kitchen)
        onMainThread { Casting.follows(CastFeed(load, launch, CastPlayerState.Idle, PAUSED_AT, LENGTH, end = CastEnd(CastEnd.How.LongPause, PAUSED_AT))) }
        waitUntil("This phone chosen") { container.playOn.chosen.value == PlayChoice.Phone }
        Thread.sleep(1_500)
        assertNull("not while the app isn't in front", container.remotePlaying.said.value)
        assertTrue(lines.isEmpty())
        val line = closedAfterLongPause(kitchen.name, PAUSED_AT)
        val main = ActivityScenario.launch(MainActivity::class.java)
        try {
            waitFor(hasText(line))
            compose.waitUntil(10_000) { !exists(hasText(line)) }
            // Out of front and back: said once.
            main.moveToState(Lifecycle.State.CREATED)
            waitUntil("the app out of front") { !container.inFront.value.value }
            main.moveToState(Lifecycle.State.RESUMED)
            waitUntil("the app in front") { container.inFront.value.value }
            Thread.sleep(1_000)
            assertEquals(listOf(line), lines.toList())
        } finally {
            main.close()
        }
    }

    private companion object {
        /** An invented show's TMDB id; nothing real has it. */
        const val SHOW = 991_621
        const val SHOW_TITLE = "Harbor Lights"
        const val EPISODE_LINE = "S02E04 · Low Water"
        const val INDEXER = "cast-after-device-test"
        const val RELEASE = "Harbor.Lights.S02E04.1080p.WEB-DL.H.264-CAST"
        const val PART = 32_000

        /** 32:10 of the fake device's 52 minutes, where the boards' cast stops. */
        const val PAUSED_AT = 1_930_000L
        const val LENGTH = 3_120_000L
    }
}
