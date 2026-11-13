package io.skjaere.usenet.app

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.AutoAdvance
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.Prefetch
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.search.EpisodeRequest
import io.skjaere.usenet.search.FinderEvent
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ShowRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The player's previous and next buttons, and a remote's or a keyboard's media keys, end to end:
 * the chapters.mkv fixture (six minutes of test pattern, see SkipSegmentsDeviceTest; its chapters
 * left out, so no skip button takes the focus) played as an episode of Harbor Lights, whose
 * episodes either side the player's seams answer in TMDB's place. Next hands the next episode to
 * the coordinator at once, as the next-episode card's Play now does; Previous starts the episode
 * again, and within its first 3 s hands over the one before. The coordinator plays them as for any
 * play of an episode: the release that played it before first (this fixture, once a test has
 * played it as that episode), else one it finds on the fake indexer (`mock-seed indexer`,
 * 10.0.2.2:8089), which stands in for the app's indexers while a test runs. A movie, and a file
 * opened without a launch, have Previous start again and no Next.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PreviousNextDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realNext = PlayerActivity.nextEpisode
    private val realPrevious = PlayerActivity.previousEpisode
    private val realLookUp = PlayerActivity.lookUpSegments
    private val realChapters = PlayerActivity.readChapters
    private val realFetch = PlaybackCoordinator.fetchNzb
    /** Indexers that were on before a test that switched them off; switched back on after it. */
    private var switchedOff = emptyList<String>()
    private var fakeIndexerAdded = false
    /** The last episode that has aired, as the seams answer: none after it. */
    private var lastAired = 3
    /** The seams' lookups, as each is answered: "next S01E02", "previous S01E01". */
    private val answered = CopyOnWriteArrayList<String>()
    private lateinit var fixture: PreparedNzb

    @Before
    fun setUp() {
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", reachable(1119))
        runBlocking {
            fixture = engineSetup("the mock on the engine, the chapters fixture prepared") {
                mockOnEngine()
                prepareFixture("chapters")
            }
        }
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        PlayerActivity.readChapters = { emptyList() }
        PlayerActivity.nextEpisode = { _, playing -> episode(playing.episode + 1).takeIf { playing.isHarborLights() && it.episode <= lastAired }.also { answered += "next ${playing.label()}" } }
        PlayerActivity.previousEpisode = { _, playing -> episode(playing.episode - 1).takeIf { playing.isHarborLights() && it.episode >= 1 }.also { answered += "previous ${playing.label()}" } }
    }

    @After
    fun restore() {
        PlayerActivity.nextEpisode = realNext
        PlayerActivity.previousEpisode = realPrevious
        PlayerActivity.lookUpSegments = realLookUp
        PlayerActivity.readChapters = realChapters
        PlaybackCoordinator.fetchNzb = realFetch
        instrumentation.setInTouchMode(true)
        container.playback.cancel()
        runBlocking {
            if (fakeIndexerAdded) container.settings.deleteIndexer(FAKE_INDEXER)
            switchedOff.forEach { container.settings.setIndexerEnabled(it, true) }
        }
    }

    /**
     * Next is enabled once the next episode is known, and the controls, up meanwhile, hear of it;
     * Previous always is. Without an episode after (the end of what has aired), Next stays
     * disabled; so it does for a movie and a file opened without a launch, which look nothing up.
     */
    @Test
    fun theButtonsAreEnabledAsThereAreEpisodesEitherSide() = runBlocking<Unit> {
        val lookUp = CompletableDeferred<Unit>()
        val seam = PlayerActivity.nextEpisode
        PlayerActivity.nextEpisode = { c, playing -> lookUp.await().let { seam(c, playing) } }
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, episodeLaunch(2))).use { scenario ->
            awaitPlaying(scenario)
            holdControlsUp(scenario)
            assertEquals("Previous and Next before the next episode is known", true to false, scenario.read { buttons(it) })
            lookUp.complete(Unit)
            waitFor("Next to be enabled") { scenario.read { a -> buttons(a).second.takeIf { it } } }
            assertTrue(scenario.read { buttons(it).first })
        }
        PlayerActivity.nextEpisode = seam

        // S01E01 as the only episode aired: none either side.
        lastAired = 1
        answered.clear()
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, episodeLaunch(1))).use { scenario ->
            awaitPlaying(scenario)
            holdControlsUp(scenario)
            waitFor("both lookups") { answered.toSet().takeIf { it == setOf("next S01E01", "previous S01E01") } }
            delay(500)
            assertEquals("Previous and Next with no episode either side", true to false, scenario.read { buttons(it) })
        }

        answered.clear()
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, movieLaunch())).use { scenario ->
            awaitPlaying(scenario)
            holdControlsUp(scenario)
            delay(1_000)
            assertEquals("Previous and Next for a movie", true to false, scenario.read { buttons(it) })
        }
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            awaitPlaying(scenario)
            holdControlsUp(scenario)
            delay(1_000)
            assertEquals("Previous and Next for a file opened without a launch", true to false, scenario.read { buttons(it) })
        }
        assertEquals("lookups for a movie or a file", emptyList<String>(), answered.toList())
    }

    /** Next plays the next episode at once, through the coordinator; where this one was is saved, and it isn't marked watched. */
    @Test
    fun nextPlaysTheNextEpisodeAtOnceAndSavesWhereThisOneWas() = runBlocking<Unit> {
        useFakeIndexer()
        val startedAt = System.currentTimeMillis()
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, episodeLaunch(1))).use { scenario ->
            awaitPlaying(scenario)
            playFrom(scenario, 120_000)
            holdControlsUp(scenario)
            waitFor("Next to be enabled") { scenario.read { a -> buttons(a).second.takeIf { it } } }
            scenario.onActivity { a -> a.findViewById<View>(androidx.media3.ui.R.id.exo_next).performClick() }
            waitFor("the player to close", timeoutMs = 5_000) { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
        }
        val next = launchFor(2)
        assertEquals(WatchTarget(WatchKind.Episode, "Harbor Lights S01E02", showTmdbId = SHOW, season = 1, episode = 2), next.watch)
        assertTrue(next.release.title, "S01E02" in next.release.title)
        val saved = savedSince(WatchKey.episode(SHOW, 1, 1), startedAt) { it.positionMs in 120_000L..135_000L }
        assertFalse("S01E01 marked watched a third of the way in", saved.watched)
    }

    /**
     * Previous starts the episode again; a second press right after it (within the first 3 s)
     * plays the episode before, through the coordinator, where this one was being saved too.
     */
    @Test
    fun previousStartsTheEpisodeAgainAndASecondPressPlaysTheOneBefore() = runBlocking<Unit> {
        useFakeIndexer()
        val startedAt = System.currentTimeMillis()
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, episodeLaunch(2))).use { scenario ->
            awaitPlaying(scenario)
            waitFor("the episode before to be known") { answered.takeIf { "previous S01E02" in it } }
            playFrom(scenario, 30_000)
            holdControlsUp(scenario)
            scenario.onActivity { a -> a.findViewById<View>(androidx.media3.ui.R.id.exo_prev).performClick() }
            waitFor("the episode to start again", timeoutMs = 3_000) { scenario.read { a -> a.player!!.currentPosition.takeIf { it < 3_000 } } }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            assertEquals(PlayState.Idle, container.playback.state.value)
            scenario.onActivity { a -> a.findViewById<View>(androidx.media3.ui.R.id.exo_prev).performClick() }
            waitFor("the player to close", timeoutMs = 5_000) { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
        }
        assertEquals(1, launchFor(1).watch.episode)
        savedSince(WatchKey.episode(SHOW, 1, 2), startedAt) { it.positionMs < 3_000 }
    }

    /**
     * Playing from the fake indexer's Harbor Lights S01 pack (E01 to E03), chosen by hand, the episodes either side
     * come from that pack: Next plays S01E02 from it and Previous S01E01 again, with no search and no grab, of another
     * release or of the pack again (the coordinator's grabs are counted). The next-episode card's search finds it at once.
     */
    @Test
    fun theEpisodesEitherSideOfOnePlayingFromASeasonPackPlayFromThatPack() = runBlocking<Unit> {
        try {
            useFakeIndexer()
            val grabbed = CopyOnWriteArrayList<String>()
            PlaybackCoordinator.fetchNzb = { c, indexer, item -> grabbed += item.guid; realFetch(c, indexer, item) }
            val watch = WatchTarget(WatchKind.Episode, "Harbor Lights S01E01", showTmdbId = SHOW, season = 1, episode = 1)
            container.playback.play(PlayRequest("Harbor Lights", "S01E01 · Episode 1", listOf(seasonPack()), FilePicker.Episode(1, 1), watch, rememberedFirst = false, chosen = true))
            val first = launchFor(1)
            assertEquals(PACK, first.release.guid)
            assertTrue(first.release.fileName.orEmpty(), "S01E01" in first.release.fileName.orEmpty())
            // Grabbed once, or not at all where an earlier run left it remembered for S01E01 with its NZB stored.
            val grabs = grabbed.toList()
            assertTrue("$grabs", grabs.all { it == PACK })

            ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, first)).use { scenario ->
                awaitPlaying(scenario)
                // As the card's search asks it, on the main thread.
                val found = scenario.read { container.playback.prefetchEpisode(episode(2), first.release).value to container.playback.holdsPrefetched(episode(2)) }
                assertTrue("$found", found.first is Prefetch.Ready && (found.first as Prefetch.Ready).releaseTitle == first.release.title && found.second)
                holdControlsUp(scenario)
                waitFor("Next to be enabled") { scenario.read { a -> buttons(a).second.takeIf { it } } }
                scenario.onActivity { a -> a.findViewById<View>(androidx.media3.ui.R.id.exo_next).performClick() }
                waitFor("the player to close", timeoutMs = 5_000) { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
            }
            val second = launchFor(2)
            assertEquals("S01E02's release", PACK, second.release.guid)
            assertEquals("S01E02's NZB", first.release.nzbId, second.release.nzbId)
            assertTrue(second.release.fileName.orEmpty(), "S01E02" in second.release.fileName.orEmpty())
            assertEquals("grabs", grabs, grabbed.toList())

            ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, second)).use { scenario ->
                awaitPlaying(scenario)
                waitFor("the episode before to be known") { answered.takeIf { "previous S01E02" in it } }
                holdControlsUp(scenario)
                // Within the first seconds, Previous plays the episode before.
                scenario.onActivity { a ->
                    a.player!!.seekTo(0)
                    a.findViewById<View>(androidx.media3.ui.R.id.exo_prev).performClick()
                }
                waitFor("the player to close", timeoutMs = 5_000) { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
            }
            val back = launchFor(1)
            assertEquals("S01E01's release", PACK, back.release.guid)
            assertEquals("S01E01's NZB", first.release.nzbId, back.release.nzbId)
            assertEquals(first.release.fileName, back.release.fileName)
            assertEquals("grabs", grabs, grabbed.toList())
        } finally {
            // The players saved the pack as the release that played S01E01 and S01E02: the other tests' plays would put it first.
            for (n in 1..2) {
                val key = WatchKey.episode(SHOW, 1, n)
                withTimeoutOrNull(5_000) { container.library.state(key).first { it?.release?.guid == PACK } }
                container.library.lastRelease(key)?.takeIf { it.guid == PACK }?.let { container.library.forgetRelease(key, it) }
            }
        }
    }

    /**
     * From the S01 pack's last episode, S01E03, to S01E04, which its files, named per episode, plainly lack: the card's
     * search and the play's leave the pack out, so it isn't grabbed and prepared again only to have no file for it. The
     * fake indexer has nothing else for S01E04: no release is found.
     */
    @Test
    fun anEpisodeTheSeasonPackPlayingLacksIsSearchedForWithoutIt() = runBlocking<Unit> {
        useFakeIndexer()
        val grabbed = CopyOnWriteArrayList<String>()
        PlaybackCoordinator.fetchNzb = { c, indexer, item -> grabbed += item.guid; realFetch(c, indexer, item) }
        val watch = WatchTarget(WatchKind.Episode, "Harbor Lights S01E03", showTmdbId = SHOW, season = 1, episode = 3)
        container.playback.play(PlayRequest("Harbor Lights", "S01E03 · Episode 3", listOf(seasonPack()), FilePicker.Episode(1, 3), watch, rememberedFirst = false, chosen = true))
        val third = launchFor(3)
        assertEquals(PACK, third.release.guid)
        val grabs = grabbed.toList()

        val card = withTimeout(60_000) { container.playback.prefetchEpisode(episode(4), third.release).first { it != Prefetch.Searching } }
        assertEquals(Prefetch.NothingFound, card)
        container.playback.playEpisode(episode(4), third.release)
        val end = withTimeout(60_000) { container.playback.state.first { it is PlayState.NoRelease || it is PlayState.Failed } }
        assertTrue("$end", end is PlayState.NoRelease)
        assertEquals("grabs", grabs, grabbed.toList())
    }

    /** A movie's Previous starts it again, however soon it is pressed again; nothing else plays. */
    @Test
    fun aMoviesPreviousOnlyStartsItAgain() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, movieLaunch())).use { scenario ->
            awaitPlaying(scenario)
            playFrom(scenario, 30_000)
            holdControlsUp(scenario)
            for (press in 1..2) {
                scenario.onActivity { a -> a.findViewById<View>(androidx.media3.ui.R.id.exo_prev).performClick() }
                waitFor("the movie to start again after press $press", timeoutMs = 3_000) { scenario.read { a -> a.player!!.currentPosition.takeIf { it < 3_000 } } }
            }
            delay(1_000)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            assertEquals(PlayState.Idle, container.playback.state.value)
        }
    }

    /** The media keys in the device's own layout (a keyboard's or a headset's on a phone, a remote's on a TV): as the buttons. */
    @Test
    fun theMediaKeysPlayTheEpisodesEitherSide() = runBlocking<Unit> {
        mediaKeys(tvLayout = false)
    }

    /** A TV remote's media keys in the TV layout, with the controls hidden: as the buttons, and the remote's rules hold. */
    @Test
    fun theMediaKeysPlayTheEpisodesEitherSideInTheTvLayoutWithTheControlsHidden() = runBlocking<Unit> {
        instrumentation.setInTouchMode(false)
        mediaKeys(tvLayout = true)
    }

    /**
     * Previous with the controls hidden starts S01E02 again (it brings them, as any media key);
     * pressed again at once, it plays S01E01. Next with the controls hidden plays S01E02 from S01E01.
     */
    private suspend fun mediaKeys(tvLayout: Boolean) {
        useFakeIndexer()
        fun intent(launch: PlayerLaunch) = PlayerActivity.intent(context, launch).apply { if (tvLayout) putExtra(FormFactor.EXTRA, "tv") }
        ActivityScenario.launch<PlayerActivity>(intent(episodeLaunch(2))).use { scenario ->
            awaitPlaying(scenario)
            waitFor("the episode before to be known") { answered.takeIf { "previous S01E02" in it } }
            playFrom(scenario, 30_000)
            hideControls(scenario)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            waitFor("the episode to start again", timeoutMs = 3_000) { scenario.read { a -> a.player!!.currentPosition.takeIf { it < 3_000 } } }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            waitFor("the player to close", timeoutMs = 5_000) { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
        }
        assertEquals(1, launchFor(1).watch.episode)

        ActivityScenario.launch<PlayerActivity>(intent(episodeLaunch(1))).use { scenario ->
            awaitPlaying(scenario)
            waitFor("Next to be enabled") { scenario.read { a -> buttons(a).second.takeIf { it } } }
            hideControls(scenario)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_NEXT)
            waitFor("the player to close", timeoutMs = 5_000) { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
        }
        assertEquals(2, launchFor(2).watch.episode)
    }

    // The fixture as an episode, a movie and a file.

    private fun uri() = UsenetUris.build(fixture.id, fixture.defaultPick!!.index)

    /** The fixture played as Harbor Lights S01E0[n], a TMDB episode. */
    private fun episodeLaunch(n: Int) = PlayerLaunch(
        uri = uri(), title = "Harbor Lights", subtitle = "S01E0$n · Episode $n", watchKey = WatchKey.episode(SHOW, 1, n),
        watch = WatchTarget(WatchKind.Episode, "Harbor Lights S01E0$n", showTmdbId = SHOW, season = 1, episode = n),
        release = SavedRelease("fixture", "chapters", "Harbor.Lights.S01E0$n.Chapters", null, fixture.defaultPick!!.index, fixture.id),
    )

    /** The fixture played as Copper Crown, a TMDB movie. */
    private fun movieLaunch() = PlayerLaunch(
        uri = uri(), title = "Copper Crown", subtitle = "2019", watchKey = WatchKey.movie(MOVIE),
        watch = WatchTarget(WatchKind.Movie, "Copper Crown (2019)", movieTmdbId = MOVIE),
        release = SavedRelease("fixture", "chapters", "Copper.Crown.2019.Chapters", null, fixture.defaultPick!!.index, fixture.id),
    )

    /** The fixture opened as an NZB file is: no launch, so no resume and no episodes either side. */
    private fun fileIntent() = Intent(context, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_URI, uri())

    private fun episode(n: Int) = EpisodePlay(
        showTmdbId = SHOW, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(),
        ids = TvIds(tvdb = 900001, tmdb = SHOW, imdb = "tt9000003"), season = 1, episode = n,
        episodeName = "Episode $n", airDate = null, runtimeMinutes = 45,
    )

    private fun AutoAdvance.Episode.isHarborLights() = showTmdbId == SHOW && season == 1

    private fun AutoAdvance.Episode.label() = "S%02dE%02d".format(season, episode)

    /** The episodes either side are searched for on the fake indexer only. */
    private suspend fun useFakeIndexer() {
        assumeTrue("fake indexer not reachable at 10.0.2.2:8089 (mock-seed indexer)", reachable(8089))
        switchedOff = container.settings.indexers.first().filter { it.enabled && it.id != FAKE_INDEXER }.map { it.id }
        switchedOff.forEach { container.settings.setIndexerEnabled(it, false) }
        container.settings.saveIndexer(FAKE_INDEXER, "Fake (previous and next test)", "http://10.0.2.2:8089", "fake-indexer-key")
        fakeIndexerAdded = true
    }

    /** The fake indexer's Harbor Lights S01 pack, as the finder ranks it among S01E01's releases. */
    private suspend fun seasonPack(): RankedItem {
        val indexer = IndexerConfig(FAKE_INDEXER, "Fake (previous and next test)", "http://10.0.2.2:8089", "fake-indexer-key")
        val show = ShowRef("Harbor Lights", 2021, ids = TvIds(tvdb = 900001, tmdb = SHOW, imdb = "tt9000003"))
        val found = container.finder.find(EpisodeRequest(show, 1, 1, runtimeMinutes = 45), listOf(indexer), container.rankingContext())
            .filterIsInstance<FinderEvent.Finished>().last().result
        return found.playable.map { RankedItem(it.item, it.ranked) }.single { it.item.guid == PACK }
    }

    private fun reachable(port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", port), 2_000) } }.isSuccess

    /** The coordinator's next launch of S01E0[episode], or a failure as soon as the play sheet shows one. */
    private suspend fun launchFor(episode: Int): PlayerLaunch = withTimeout(120_000) {
        merge(
            container.playback.launches.filter { it.watch.showTmdbId == SHOW && it.watch.episode == episode },
            container.playback.state.filter { it is PlayState.Failed || it is PlayState.NoRelease }.map { throw AssertionError("S01E0$episode didn't start: $it") },
        ).first()
    }

    /** [key]'s progress as saved after [sinceMs], once it is as [expected] says; the player saves it in the app's scope. */
    private suspend fun savedSince(key: String, sinceMs: Long, expected: (WatchState) -> Boolean): WatchState {
        val saved = withTimeoutOrNull(10_000) { container.library.state(key).first { it != null && it.lastPlayedAt >= sinceMs && expected(it) } }
        return saved ?: throw AssertionError("$key not saved as expected since $sinceMs: ${container.library.state(key).first()}")
    }

    // The player.

    private suspend fun awaitPlaying(scenario: ActivityScenario<PlayerActivity>) {
        waitFor("the player to play", timeoutMs = 30_000) { scenario.read { a -> a.player?.takeIf { it.duration > 0 && it.isPlaying } } }
    }

    /** Playing on from [positionMs], as a seek on the time bar leaves it. */
    private suspend fun playFrom(scenario: ActivityScenario<PlayerActivity>, positionMs: Long) {
        scenario.onActivity { it.player!!.seekTo(positionMs) }
        waitFor("the player to play from $positionMs ms", timeoutMs = 20_000) { scenario.read { a -> a.player!!.takeIf { it.isPlaying && it.currentPosition >= positionMs } } }
    }

    /** The controls up, and kept up for the test. */
    private suspend fun holdControlsUp(scenario: ActivityScenario<PlayerActivity>) {
        scenario.onActivity { a ->
            playerView(a).controllerShowTimeoutMs = 0
            playerView(a).showController()
        }
        waitFor("the controls") { scenario.read { a -> playerView(a).isControllerFullyVisible.takeIf { it } } }
    }

    private suspend fun hideControls(scenario: ActivityScenario<PlayerActivity>) {
        scenario.onActivity { playerView(it).hideController() }
        waitFor("the controls to hide") { scenario.read { a -> (!playerView(a).isControllerFullyVisible).takeIf { it } } }
    }

    /** Whether the controls' previous and next buttons are enabled. */
    private fun buttons(a: Activity): Pair<Boolean, Boolean> =
        a.findViewById<View>(androidx.media3.ui.R.id.exo_prev).isEnabled to a.findViewById<View>(androidx.media3.ui.R.id.exo_next).isEnabled

    private fun <T> ActivityScenario<PlayerActivity>.read(probe: (PlayerActivity) -> T): T {
        var result: Result<T>? = null
        onActivity { result = runCatching { probe(it) } }
        return result!!.getOrThrow()
    }

    private suspend fun <T : Any> waitFor(what: String, timeoutMs: Long = 20_000, probe: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
            delay(100)
        }
    }

    private fun views(view: View): List<View> =
        listOf(view) + ((view as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())

    private fun playerView(activity: Activity) = views(activity.window.decorView).filterIsInstance<PlayerView>().single()

    private companion object {
        /** Harbor Lights' and Copper Crown's ids in the fake catalogue's TMDB numbering; nothing real has them. */
        const val SHOW = 900002
        const val MOVIE = 900011
        const val FAKE_INDEXER = "previous-next-device-test"
        const val PACK = "hl-s01-pack"
    }
}
