package io.skjaere.usenet.app.link

import android.os.SystemClock
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.collection.ChooseState
import io.skjaere.usenet.app.collection.MovieScreen
import io.skjaere.usenet.app.collection.ShowScreen
import io.skjaere.usenet.app.collection.ShowViewModel
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.home.HomeScreen
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.core.FetchException
import io.skjaere.usenet.core.NzbSource
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.EpisodeRef
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.MovieDetails
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.RankedItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.BindException
import java.net.InetSocketAddress
import java.net.Socket
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Cast button and Play with a TV chosen (item 23, L113; PtPhoneWhere B, PtPhoneMovie, CsPhoneButton, L7) on a real
 * device, over the app's own links, Play on and pages: a TV in the test's process on this device's own address, paired
 * with the app's link key and chosen. Play on the Show page and the Movie page, and Up next's Play, send it a `Play` while
 * no NZB is grabbed here and the coordinator stays idle; Up next and the Show page's Play grey while NZStreamer isn't open
 * there; Choose release still plays here; and Play asks to pair again with a TV that removed this phone or was set up
 * again. The TV, the show and the movie are invented; the TV leaves the app's link.json after, and the choice goes back
 * to This phone.
 */
@RunWith(AndroidJUnit4::class)
class PlayOnButtonDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val link = container.data.link
    private var tmdb: FakeTmdb? = null
    private val realFetch = PlaybackCoordinator.fetchNzb
    private val realSource = PlaybackCoordinator.nzbSource
    private val grabs = AtomicInteger()
    private val tvs = mutableListOf<FakeTv>()
    private var keyAdded = false
    private lateinit var saved: AppSettings
    private val today = LocalDate.now()

    private val show = ShowDetails(
        SHOW, "Harbor Lights", null, 2021, null, null, null, "Ended", false, 45,
        listOf(SeasonSummary(1, null, 6, LocalDate.of(2021, 3, 1), null)),
        lastEpisode = EpisodeSummary(1, 6, "Night Crossing", LocalDate.of(2021, 4, 5)), nextEpisode = null, ids = ExternalIds(tvdb = 991_401),
    )

    private val season = Season(SHOW, 1, null, null, (1..6).map { Episode(1, it, "Episode $it", null, LocalDate.of(2021, 3, 1).plusWeeks(it - 1L), 45, null) })

    private val movie = MovieDetails(MOVIE, "North Quay", null, 2023, null, null, null, 118, ExternalIds(imdb = "tt9914020"), releaseDate = LocalDate.of(2023, 3, 9))

    @Before
    fun setUp() {
        assumeFalse("a TV has no Play on button", container.television)
        runBlocking {
            saved = container.settings.settings.first()
            assumeTrue("no Usenet provider saved, so Home shows the first-run screen", container.settings.providers.first().isNotEmpty())
            container.settings.setIndexerSetupSkipped(true)
            if (container.settings.tmdbKey() == null) {
                container.settings.setTmdbKey("play-on-device-test")
                keyAdded = true
            }
            // Made first: a new identity clears the TVs kept, the invented one too.
            link.identity()
        }
        tmdb = FakeTmdb(shows = mapOf(SHOW to show), seasons = { id, _ -> if (id == SHOW) season else null }, movies = mapOf(MOVIE to movie))
        PlaybackCoordinator.fetchNzb = { _, _, _ -> grabs.incrementAndGet(); throw FetchException("Not to be fetched", stopsAll = true) }
        PlaybackCoordinator.nzbSource = { _, _ -> NzbSource { grabs.incrementAndGet(); throw FetchException("Not to be fetched", stopsAll = true) } }
    }

    @After
    fun restore() {
        // What a test left of Play on goes with it: the app's start, sheet and question are the process's, the next class's too.
        compose.runOnUiThread { container.tvStart.close() }
        container.tvPlaying.close()
        container.playOn.answered()
        container.playOn.closeSheet()
        container.playOn.choose(null)
        tvs.forEach { it.close() }
        tmdb?.close()
        PlaybackCoordinator.fetchNzb = realFetch
        PlaybackCoordinator.nzbSource = realSource
        container.playback.cancel()
        if (!::saved.isInitialized) return
        runBlocking {
            tvs.forEach { link.forgetTv(it.key.id) }
            // Its key goes with it (restore only puts back the settings document).
            if (saved.indexers.none { it.id == FAKE_INDEXER } && container.settings.indexer(FAKE_INDEXER) != null) container.settings.deleteIndexer(FAKE_INDEXER)
            container.settings.restore(saved)
            if (keyAdded) container.settings.setTmdbKey(null)
            container.library.markEpisodes(listOf(EpisodeRef(SHOW, 1, 3, WatchTitle.episode("Harbor Lights", 1, 3))), watched = false)
        }
    }

    /** A TV paired with the app's key, kept in its link.json and chosen; returned once its link reads Ready. */
    private fun chosenTv(): FakeTv {
        val phone = runBlocking { link.identity().identity.key.id }
        val tv = FakeTv(context, inventedTvName("Living room TV"), paired = setOf(phone), advertised = false).also { tvs += it }
        runBlocking { assertTrue(link.addTv(tv.key.id, tv.name, tv.address.hostAddress!!, tv.port)) }
        container.playOn.choose(tv.key.id)
        reads(tv, TvState.Ready)
        return tv
    }

    private fun reads(tv: FakeTv, state: TvState, millis: Long = 20_000) {
        val until = SystemClock.uptimeMillis() + millis
        while ((container.playOn.target.value as? PlayTarget.Tv)?.state != state) {
            assertTrue("$state within $millis ms; now ${container.playOn.target.value}", SystemClock.uptimeMillis() < until)
            Thread.sleep(50)
        }
    }

    /** Harbor Lights S01E03, two minutes in, played just now. */
    private fun seedInProgress() = runBlocking {
        val template = WatchState(WatchKey.episode(SHOW, 1, 3), WatchKind.Episode, showTmdbId = SHOW, season = 1, episode = 3, title = WatchTitle.episode("Harbor Lights", 1, 3), updatedAt = 0)
        container.library.recordProgress(template, 120_000, 2_700_000)
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, millis: Long = 15_000) = compose.waitUntil(millis) { exists(matcher) }

    private fun tap(matcher: SemanticsMatcher) {
        waitFor(matcher)
        compose.onNode(matcher).performClick()
    }

    private fun plays(tv: FakeTv): List<LinkMessage.Play> = tv.received.filterIsInstance<LinkMessage.Play>()

    private fun showPage() = compose.setContent {
        AppTheme {
            ShowScreen(SHOW, onBack = {})
            PlayOnHost(onAddTv = {}, onPairAgain = {}, onPrivacy = {})
        }
    }

    @Test
    fun playOnTheShowPageGoesToTheTvAndNothingRunsHere() {
        seedInProgress()
        val tv = chosenTv()
        showPage()
        waitFor(hasContentDescription(playOnLabel(container.playOn.target.value)))
        assertEquals("Play on: ${tv.name}", playOnLabel(container.playOn.target.value))
        val resume = hasText("Resume S01E03 · 2:00", substring = true) and hasText(onTvLine(tv.name), substring = true) and hasClickAction()
        tap(resume)
        compose.waitUntil(10_000) { plays(tv).isNotEmpty() }
        val play = plays(tv).single()
        assertEquals(SHOW, play.episode?.showTmdbId)
        assertEquals(1 to 3, play.episode?.season to play.episode?.episode)
        assertEquals(120_000L, play.from?.positionMs)
        Thread.sleep(500)
        assertEquals("no NZB grabbed here", 0, grabs.get())
        assertEquals(PlayState.Idle, container.playback.state.value)
        assertTrue(!container.playback.startingPlay)
    }

    @Test
    fun playOnTheMoviePageGoesToTheTvAndAReleaseChosenPlaysHere() {
        val tv = chosenTv()
        compose.setContent { AppTheme { MovieScreen(MOVIE, onBack = {}) } }
        waitFor(hasText(CHOSEN_PLAYS_HERE))
        tap(hasText("Play", substring = false) and hasClickAction())
        compose.waitUntil(10_000) { plays(tv).isNotEmpty() }
        assertEquals(MOVIE, plays(tv).single().movie?.tmdbId)
        assertEquals(0, grabs.get())
        assertEquals(PlayState.Idle, container.playback.state.value)
    }

    @Test
    fun upNextGoesToTheTvAndGreysWhileTheTvIsNotOpen() {
        seedInProgress()
        val tv = chosenTv()
        compose.setContent {
            AppTheme {
                HomeScreen(onSearch = {}, onRecent = {}, onSettings = {}, onSetup = { _, _ -> }, onShow = {}, onCollection = {}, onImport = {})
                PlayOnHost(onAddTv = {}, onPairAgain = {}, onPrivacy = {})
            }
        }
        val resume = hasContentDescription("Resume Harbor Lights S01E03 on ${tv.name}")
        tap(resume)
        compose.waitUntil(10_000) { plays(tv).isNotEmpty() }
        val play = plays(tv).single()
        assertEquals(1 to 3, play.episode?.season to play.episode?.episode)
        assertNotNull("this phone's place goes with it", play.from)
        assertEquals(0, grabs.get())
        assertEquals(PlayState.Idle, container.playback.state.value)
        // NZStreamer on the TV closed: greyed, and back once it opens again.
        tv.close()
        reads(tv, TvState.NotOpen)
        compose.waitUntil(10_000) { runCatching { compose.onNode(resume).assertIsNotEnabled() }.isSuccess }
        tv.reopen()
        reads(tv, TvState.Ready, millis = 40_000)
        compose.waitUntil(10_000) { runCatching { compose.onNode(resume).assertIsEnabled() }.isSuccess }
    }

    @Test
    fun theShowPagesPlayGreysWithWhatToDoWhileTheTvIsNotOpen() {
        seedInProgress()
        val tv = chosenTv()
        showPage()
        val resume = hasText("Resume S01E03 · 2:00", substring = true) and hasClickAction()
        waitFor(resume)
        tv.close()
        reads(tv, TvState.NotOpen)
        waitFor(hasText(openNzStreamerOn(tv.name)))
        compose.onNode(resume).assertIsNotEnabled()
        assertEquals("Play on: ${tv.name}, not open", playOnLabel(container.playOn.target.value))
        waitFor(hasContentDescription("Play on: ${tv.name}, not open"))
    }

    /** Through its own search, on the fake indexer (`mock-seed indexer`, 10.0.2.2:8089), which has nothing for the show. */
    @Test
    fun chooseReleaseStillPlaysHere() {
        val reachable = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 8089), 2_000) } }.isSuccess
        assumeTrue("fake indexer not reachable at 10.0.2.2:8089 (mock-seed indexer)", reachable)
        runBlocking {
            assumeTrue("no usable Usenet provider, so a play stops before it starts", container.noUsableProvider() == null)
            container.settings.indexers.first().filter { it.enabled }.forEach { container.settings.setIndexerEnabled(it.id, false) }
            container.settings.saveIndexer(FAKE_INDEXER, "Fake (Play on test)", "http://10.0.2.2:8089", "fake-indexer-key")
        }
        val tv = chosenTv()
        val vm = ShowViewModel(container, SHOW)
        val until = SystemClock.uptimeMillis() + 15_000
        while (vm.details.value == null) {
            assertTrue("the show's details", SystemClock.uptimeMillis() < until)
            Thread.sleep(50)
        }
        compose.runOnUiThread { vm.chooseRelease(season.episodes[2]) }
        compose.waitUntil(30_000) { vm.choose.value is ChooseState.Ready }
        compose.runOnUiThread { vm.playChosen(season.episodes[2], candidate("Harbor.Lights.S01E03.1080p.WEB-DL-GRP")) }
        compose.waitUntil(15_000) { container.playback.state.value != PlayState.Idle }
        compose.waitUntil(15_000) { grabs.get() > 0 || container.playback.state.value is PlayState.Failed }
        Thread.sleep(500)
        assertTrue("the TV was asked nothing", plays(tv).isEmpty())
    }

    @Test
    fun playAsksToPairAgainWithATvThatRemovedThisPhone() {
        seedInProgress()
        val tv = chosenTv()
        // Remove on the TV: it no longer knows this phone.
        tv.paired = emptySet()
        tv.reopen()
        reads(tv, TvState.RemovedHere, millis = 40_000)
        showPage()
        tap(hasText("Resume S01E03 · 2:00", substring = true) and hasClickAction())
        waitFor(hasText(removedHere(tv.name)) and hasAnyAncestor(isDialog()))
        assertTrue(exists(hasText(REMOVED_TEXT)))
        assertTrue(exists(hasText(PAIR_AGAIN)))
        assertTrue(plays(tv).isEmpty())
    }

    @Test
    fun playAsksToPairAgainWithATvSetUpAgain() {
        seedInProgress()
        val tv = chosenTv()
        val again = setUpAgain(tv)
        // Once mDNS has run its 10 s without the pinned id (L12).
        reads(tv, TvState.SetUpAgain, millis = 60_000)
        showPage()
        tap(hasText("Resume S01E03 · 2:00", substring = true) and hasClickAction())
        waitFor(hasText(setUpAgainTitle(tv.name)) and hasAnyAncestor(isDialog()))
        assertTrue(exists(hasText(SET_UP_AGAIN_TEXT)))
        assertTrue(plays(again).isEmpty())
    }

    /** NZStreamer on [old] set up again: another key, the same name, address and port, bound again should the port be held a moment. */
    private fun setUpAgain(old: FakeTv): FakeTv {
        old.close()
        val until = SystemClock.uptimeMillis() + 3_000
        while (true) {
            try {
                return FakeTv(context, old.name, port = old.port, advertised = false).also { tvs += it }
            } catch (e: BindException) {
                if (SystemClock.uptimeMillis() > until) throw e
                Thread.sleep(100)
            }
        }
    }

    private fun candidate(title: String) = RankedItem(
        NewznabItem(
            indexerId = "play-on-device-test", guid = "harbor-lights-s01e03", title = title, sizeBytes = 690_000, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        ),
        RankedRelease(ReleaseFacts("play-on-device-test/harbor-lights-s01e03", title, 690_000), ReleaseParser.parse(title), playable = true, score = 1.0, reasons = emptyList(), estimatedBitrate = null),
    )

    private companion object {
        /** Invented TMDB ids nothing real has. */
        const val SHOW = 991_301
        const val MOVIE = 991_302
        const val FAKE_INDEXER = "play-on-device-test-fake"
    }
}
