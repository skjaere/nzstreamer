package io.skjaere.usenet.app

import android.os.SystemClock
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule

import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.app.home.MARK_WATCHED
import io.skjaere.usenet.app.home.REMOVE_FROM_UP_NEXT
import io.skjaere.usenet.app.home.UpNext
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.CollectedShow
import io.skjaere.usenet.data.EpisodeRef
import io.skjaere.usenet.data.RecentSearch
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.SearchPick
import io.skjaere.usenet.data.TitleType
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/**
 * Home over the real library: an episode of the fake catalogue's Harbor Lights in progress, and
 * the next episode of an invented collected show ("The Quiet Orbit"). TMDB doesn't know either,
 * so the TMDB seam answers for them. Resuming plays the remembered release's NZB from the store
 * (the chapters.mkv fixture on the mock NNTP server, see SkipSegmentsDeviceTest) without asking
 * the indexer, at the saved position. A recent search picked from TMDB replays by the title's id
 * on the fake indexer (`mock-seed indexer`, 10.0.2.2:8089); a title opened from Search opens its
 * page again. Up next's menu takes a row out until it plays again, or marks it watched, each with
 * Undo. Settings the tests change are put back as they were, and the library rows they made leave
 * Up next; what a test took out of it is put back.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class HomeDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private var tmdb: FakeTmdb? = null
    private val realFetch = PlaybackCoordinator.fetchNzb
    private val realNext = PlayerActivity.nextEpisode
    private val realPrevious = PlayerActivity.previousEpisode
    private val realLookUp = PlayerActivity.lookUpSegments
    private val grabs = AtomicInteger()
    private val today = LocalDate.now()
    private lateinit var saved: AppSettings
    private var keyAdded = false
    private var orbitCollected = true

    private val harborLights = ShowDetails(
        SHOW, "Harbor Lights", null, 2021, null, null, null, "Ended", false, 45,
        listOf(SeasonSummary(1, null, 10, LocalDate.of(2021, 3, 1), null)),
        lastEpisode = EpisodeSummary(1, 10, "Episode 10", LocalDate.of(2021, 5, 3)), nextEpisode = null, ids = ExternalIds(tvdb = 900001, imdb = "tt9000003"),
    )

    /** A new show: its eight episodes aired over the last eight days. */
    private val quietOrbit = ShowDetails(
        ORBIT, "The Quiet Orbit", null, today.year, null, null, null, "Returning Series", true, 40,
        listOf(SeasonSummary(1, null, 8, today.minusDays(8), null)),
        lastEpisode = EpisodeSummary(1, 8, "Episode 8", today.minusDays(1)), nextEpisode = null, ids = ExternalIds(),
    )

    private fun season(show: Int): Season = when (show) {
        SHOW -> Season(SHOW, 1, null, null, (1..10).map { Episode(1, it, "Episode $it", null, LocalDate.of(2021, 3, 1).plusWeeks(it - 1L), 45, null) })
        else -> Season(ORBIT, 1, null, null, (1..8).map { Episode(1, it, "Episode $it", null, today.minusDays(9L - it), 40, null) })
    }

    @Before
    fun setUp() {
        runBlocking {
            saved = container.settings.settings.first()
            assumeTrue("no Usenet provider saved, so Home shows the first-run screen", container.settings.providers.first().isNotEmpty())
            container.settings.setIndexerSetupSkipped(true)
            // Next episodes need a key; the seam answers in TMDB's place.
            if (container.settings.tmdbKey() == null) {
                container.settings.setTmdbKey("home-device-test")
                keyAdded = true
            }
            orbitCollected = container.library.isCollected(ORBIT).first()
        }
        tmdb = FakeTmdb(shows = mapOf(SHOW to harborLights, ORBIT to quietOrbit), seasons = { id, _ -> if (id == SHOW || id == ORBIT) season(id) else null })
        PlaybackCoordinator.fetchNzb = { c, indexer, item -> grabs.incrementAndGet(); realFetch(c, indexer, item) }
        PlayerActivity.nextEpisode = { _, _ -> null }
        PlayerActivity.previousEpisode = { _, _ -> null }
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
    }

    @After
    fun restore() {
        tmdb?.close()
        PlaybackCoordinator.fetchNzb = realFetch
        PlayerActivity.nextEpisode = realNext
        PlayerActivity.previousEpisode = realPrevious
        PlayerActivity.lookUpSegments = realLookUp
        container.playback.cancel()
        runBlocking {
            // Its key goes with it (restore only puts back the settings document).
            if (saved.indexers.none { it.id == FAKE_INDEXER } && container.settings.indexer(FAKE_INDEXER) != null) container.settings.deleteIndexer(FAKE_INDEXER)
            container.settings.restore(saved)
            if (keyAdded) container.settings.setTmdbKey(null)
            // Out of Up next again: no longer in progress, the invented show out of the collection.
            container.library.markEpisodes(
                listOf(EpisodeRef(SHOW, 1, 5, WatchTitle.episode("Harbor Lights", 1, 5)), EpisodeRef(ORBIT, 1, 1, WatchTitle.episode("The Quiet Orbit", 1, 1))),
                watched = false,
            )
            if (!orbitCollected) container.library.removeShow(ORBIT)
            // Taken out of Up next and undone at once: a removal a test made hides nothing any more.
            container.library.undoUpNextRemoval(container.library.removeFromUpNext(UpNext.showKey(SHOW), showTmdbId = SHOW))
            container.library.removeSearch(RECENT.lowercase())
            container.library.removeSearch(RecentSearch.titleKey(orbitOpened))
        }
    }

    /** The Quiet Orbit, as a title opened from Search remembers it. */
    private val orbitOpened = SearchPick(TitleType.Tv, ORBIT, "The Quiet Orbit")

    /** Harbor Lights S01E05, two minutes into its six, played last of all; [release] played it. */
    private suspend fun seedEpisodeInProgress(release: RememberedRelease) {
        val template = WatchState(
            WatchKey.episode(SHOW, 1, 5), WatchKind.Episode, showTmdbId = SHOW, season = 1, episode = 5,
            title = WatchTitle.episode("Harbor Lights", 1, 5), updatedAt = 0,
        )
        container.library.recordProgress(template, POSITION_MS, DURATION_MS, release)
    }

    /**
     * The Quiet Orbit collected, its first episode watched just now, and what TMDB (the fake) says of
     * it stored for the lists, as the refresher stores it when the app is in front.
     */
    private suspend fun seedNextEpisode() {
        val now = System.currentTimeMillis()
        container.library.addShow(CollectedShow(CollectedShow.idFor(ORBIT), ORBIT, name = "The Quiet Orbit", addedAt = now, updatedAt = now))
        val first = WatchState(WatchKey.episode(ORBIT, 1, 1), WatchKind.Episode, showTmdbId = ORBIT, season = 1, episode = 1, title = WatchTitle.episode("The Quiet Orbit", 1, 1), updatedAt = 0)
        container.library.recordProgress(first, 2_400_000, 2_400_000)
        container.refresher.refreshNow(listOf(TitleKey.show(ORBIT), TitleKey.show(SHOW)))
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun showHome() {
        compose.setContent { AppTheme { AppNav() } }
    }

    @Test
    fun upNextShowsTheEpisodeInProgressFirstAndThenAShowsNextEpisode() {
        runBlocking {
            seedNextEpisode()
            seedEpisodeInProgress(RememberedRelease(FAKE_INDEXER, "hl-s01e05-home", RELEASE, null, 0, "not-stored"))
        }
        showHome()
        val resume = hasContentDescription("Resume Harbor Lights S01E05")
        val next = hasContentDescription("Play The Quiet Orbit S01E02")
        compose.waitUntil(15_000) { exists(resume) && exists(next) }
        // Named from TMDB (through the seam): the episode, its time left; the next one, its count and New.
        compose.onNode(hasText("S01E05 · Episode 5 · 4 min left")).assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(next)
        compose.onNode(hasText("S01E02 · Episode 2 · 7 unwatched")).assertIsDisplayed()
        val resumeTop = compose.onNode(resume).fetchSemanticsNode().boundsInRoot.top
        val nextTop = compose.onNode(next).fetchSemanticsNode().boundsInRoot.top
        assertTrue("the episode in progress ($resumeTop) comes before the next episode ($nextTop)", resumeTop < nextTop)
        // The NEW badge is in the next episode's row (S01E02 aired a week ago today), whose texts it is merged with.
        val nextRow = compose.onNode(hasText("S01E02 · Episode 2 · 7 unwatched")).fetchSemanticsNode()
        assertTrue(nextRow.config.toString(), hasText("NEW").matches(nextRow))
        assertTrue(hasText("The Quiet Orbit").matches(nextRow))
    }

    @Test
    fun theEpisodeInProgressResumesFromTheStoredNzbWithoutAskingTheIndexer() {
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", reachable(1119))
        val fixture = runBlocking { storedFixture() }
        val index = fixture.defaultPick!!.index
        runBlocking { seedEpisodeInProgress(RememberedRelease(FAKE_INDEXER, "hl-s01e05-home", RELEASE, null, index, fixture.id)) }
        showHome()
        val resume = hasContentDescription("Resume Harbor Lights S01E05")
        compose.waitUntil(15_000) { exists(resume) }
        compose.onNode(resume).performClick()

        val launch = runBlocking { launchOf(WatchKey.episode(SHOW, 1, 5)) }
        val launchedAt = System.currentTimeMillis()
        assertEquals("indexer grabs", 0, grabs.get())
        assertEquals(fixture.id, launch.release.nzbId)
        // Remembered by its position (as before names were kept, #179), it plays and is launched by its name.
        assertEquals(UsenetUris.of(fixture.id, fixture.defaultPick!!), launch.uri)
        assertEquals(WatchTarget(WatchKind.Episode, "Harbor Lights S01E05", showTmdbId = SHOW, season = 1, episode = 5), launch.watch)
        // What the track probe read of the video goes with the launch, for the TV's frame-rate match: chapters.mkv's
        // 10 fps (its DefaultDuration) and six minutes.
        val video = launch.video
        assertNotNull("the launch's video", video)
        assertEquals(10.0, video!!.fps!!, 1e-6)
        assertTrue("$video", video.durationMs in 359_000L..361_000L)
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch)).use { scenario ->
            val position = waitFor("the player to be ready") {
                var at: Long? = null
                scenario.onActivity { a -> at = a.player?.takeIf { it.playbackState == Player.STATE_READY }?.currentPosition }
                at
            }
            assertTrue("resumed at $position ms, saved at $POSITION_MS", position in POSITION_MS..POSITION_MS + 10_000)
        }
        // The player saves the position as it closes; let it, before the test takes the episode out of Up next.
        val resumedAt = launchedAt
        waitFor("the player's save on closing") { runBlocking { container.library.state(WatchKey.episode(SHOW, 1, 5)).first() }?.takeIf { it.lastPlayedAt > resumedAt } }
    }

    /** The row's ⋮, then [item] in its menu. */
    private fun fromMenu(title: String, item: String) {
        val more = hasContentDescription("More for $title")
        compose.waitUntil(15_000) { exists(more) }
        compose.onNode(more).performClick()
        compose.onNode(hasText(item) and hasAnyAncestor(isPopup()) and hasClickAction()).performClick()
    }

    /**
     * Remove from Up next (L17, L18, L23): the row goes, with Undo offered. The player's save of a play since (every 15 s
     * while it plays, and as it closes: what brings a title back) puts it back.
     */
    @Test
    fun removeFromUpNextTakesTheRowOutUntilItPlaysAgain() {
        val release = RememberedRelease(FAKE_INDEXER, "hl-s01e05-home", RELEASE, null, 0, "not-stored")
        runBlocking { seedEpisodeInProgress(release) }
        showHome()
        val resume = hasContentDescription("Resume Harbor Lights S01E05")
        compose.waitUntil(15_000) { exists(resume) }
        fromMenu("Harbor Lights", REMOVE_FROM_UP_NEXT)
        compose.waitUntil(5_000) { !exists(resume) }
        compose.waitUntil(5_000) { exists(hasText("Removed “Harbor Lights” from Up next")) }
        runBlocking { assertTrue(UpNext.showKey(SHOW) in container.library.upNextHidden.first()) }
        // A play since: the next save is later than the removal.
        Thread.sleep(20)
        runBlocking { container.library.recordProgress(container.library.state(WatchKey.episode(SHOW, 1, 5)).first()!!, POSITION_MS + 15_000, DURATION_MS, release) }
        compose.waitUntil(15_000) { exists(resume) }
    }

    /** Mark watched (W3): the episode in progress is marked, so its row goes (the show isn't collected); Undo puts it back where it was. */
    @Test
    fun markWatchedFromUpNextMarksTheEpisodeAndUndoPutsItBack() {
        runBlocking { seedEpisodeInProgress(RememberedRelease(FAKE_INDEXER, "hl-s01e05-home", RELEASE, null, 0, "not-stored")) }
        showHome()
        val resume = hasContentDescription("Resume Harbor Lights S01E05")
        compose.waitUntil(15_000) { exists(resume) }
        fromMenu("Harbor Lights", MARK_WATCHED)
        compose.waitUntil(5_000) { !exists(resume) }
        compose.waitUntil(5_000) { exists(hasText("Marked “Harbor Lights S01E05” watched")) }
        val key = WatchKey.episode(SHOW, 1, 5)
        runBlocking { assertTrue(container.library.state(key).first()!!.watched) }
        compose.onNode(hasText("Undo") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { exists(resume) }
        val back = runBlocking { container.library.state(key).first()!! }
        assertFalse(back.watched)
        assertEquals(POSITION_MS, back.positionMs)
    }

    /** The field on Home opens the Search tab ready to type in, so TMDB's suggestions show as there. */
    @Test
    fun theSearchFieldOpensTheSearchTabFocusedForTyping() {
        showHome()
        val field = hasText("Search your indexers") and hasClickAction()
        compose.waitUntil(15_000) { exists(field) }
        compose.onNode(field).performClick()
        compose.waitUntil(5_000) { exists(hasSetTextAction() and isFocused()) }
        assertTrue(!exists(hasText("Search your indexers")))
    }

    @Test
    fun aRecentSearchPickedFromTmdbReplaysByTheTitlesId() {
        assumeTrue("fake indexer not reachable at 10.0.2.2:8089 (mock-seed indexer)", reachable(8089))
        val pick = SearchPick(TitleType.Tv, SHOW, "Harbor Lights", season = 1, episode = 1)
        runBlocking {
            container.settings.indexers.first().filter { it.enabled }.forEach { container.settings.setIndexerEnabled(it.id, false) }
            container.settings.saveIndexer(FAKE_INDEXER, "Fake (home test)", "http://10.0.2.2:8089", "fake-indexer-key")
            container.library.addSearch(RECENT, pick)
        }
        showHome()
        val chip = hasText(RECENT) and hasClickAction()
        compose.waitUntil(15_000) { exists(chip) }
        compose.onNode(chip).performClick()
        // The title card shows only for a search by a picked title, here looked up by its TMDB id.
        compose.waitUntil(30_000) { exists(hasText("TV series · 2021 · 1 season")) }
        compose.waitUntil(30_000) { exists(hasText("S01E01")) }
        // Searched again, it is remembered with its title still.
        runBlocking { assertEquals(pick, container.library.recentSearches.first().first { it.query == RECENT }.pick) }
    }

    /** A title opened from Search shows on Home as what it was, a show, and its chip opens its page again. */
    @Test
    fun aRecentTitleOpensItsPageAgain() {
        runBlocking { container.library.addTitle(orbitOpened) }
        showHome()
        val chip = hasText("The Quiet Orbit") and hasText("TV series") and hasClickAction()
        compose.waitUntil(15_000) { exists(chip) }
        compose.onNode(chip).performClick()
        // The Show page's episodes, from TMDB (the fake).
        compose.waitUntil(15_000) { exists(hasContentDescription("More for episode 1")) }
        assertFalse("Search opened instead of the page", exists(hasSetTextAction()))
    }

    /** The chapters fixture prepared from its NZB and put in the store, the engine on the mock server. */
    private suspend fun storedFixture(): PreparedNzb = engineSetup("the mock on the engine, the chapters fixture prepared") {
        mockOnEngine()
        prepareFixture("chapters")
    }

    /** The coordinator's next launch for [key], or a failure as soon as the play sheet shows one. */
    private suspend fun launchOf(key: String): PlayerLaunch = withTimeout(60_000) {
        merge(
            container.playback.launches.filter { it.watchKey == key },
            container.playback.state.filter { it is PlayState.Failed || it is PlayState.NoRelease }.map { throw AssertionError("didn't start: $it") },
        ).first()
    }

    private fun reachable(port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", port), 2_000) } }.isSuccess

    private fun <T : Any> waitFor(what: String, timeoutMs: Long = 30_000, probe: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(100)
        }
    }

    private companion object {
        /** Harbor Lights' id in the fake catalogue's TMDB numbering; nothing real has it. */
        const val SHOW = 900002
        /** An invented show; nothing real has the id. */
        const val ORBIT = 900005
        const val FAKE_INDEXER = "home-device-test"
        const val RELEASE = "Harbor.Lights.S01E05.720p.WEB.h264-HOME"
        const val RECENT = "Harbor Lights S01E01"
        const val POSITION_MS = 120_000L
        const val DURATION_MS = 360_000L
    }
}
