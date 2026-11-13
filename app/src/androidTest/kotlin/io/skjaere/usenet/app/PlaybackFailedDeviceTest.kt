package io.skjaere.usenet.app

import android.app.Activity
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.core.FetchException
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.NzbSource
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.ProviderConfig
import io.skjaere.usenet.core.UsenetError
import io.skjaere.usenet.data.ReleaseChecks
import io.skjaere.usenet.data.TriedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.nzbstreamer.nzb.NzbParser
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.player.UsenetDataSourceException
import io.skjaere.usenet.player.UsenetLoadErrorHandlingPolicy
import io.skjaere.usenet.player.usenetError
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.RankedItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * A release that fails as it opens: the chapters.mkv fixture (see SkipSegmentsDeviceTest) by
 * `chapters-missing-cues.nzb`, its NZB with the last article's id changed to one the mock NNTP
 * server doesn't have (bytes 600,000 to the end, where the file's index, its Cues, sits). It is
 * launched straight into the player at 2:50, so the pipeline's article check can't catch it first.
 * A missing article in a cluster is skipped (GapPlaybackDeviceTest), but the player reads the Cues
 * before anything plays, and without them has nothing to go on from: the "Playback stopped" panel
 * shows within seconds (ExoPlayer doesn't retry a missing article, #62), and "Try another release"
 * plays the request's next release, the whole fixture, served by the coordinator's NZB seam without
 * an indexer, from where the failed one was to start. A failure that is the provider's, not the
 * release's, leaves the release to be kept as the one that played. The release remembered for the
 * film, which Resume plays, is `chapters-missing-article.nzb`: the fourth article's id changed, which
 * the availability check finds.
 *
 * The film is invented ("Lantern Field", TMDB id 900021, which nothing real has); its library row
 * is left at position 0, out of Up next. A missing article is remembered against its release, which
 * a later play would skip: the releases found missing are emptied for each test and put back after.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackFailedDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    /** The app let into picture-in-picture: Home with the "Playback stopped" panel up must not put the player in the window. */
    @get:Rule
    val pictureInPicture = PictureInPictureAllowed()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realLookUp = PlayerActivity.lookUpSegments
    private val realNext = PlayerActivity.nextEpisode
    private val realPrevious = PlayerActivity.previousEpisode
    private val realSource = PlaybackCoordinator.nzbSource
    private val realDataSources = PlayerActivity.dataSources
    private val realReadPlayback = PlayerActivity.readPlayback
    /** A test's hold on the file's loading, let go after it. */
    private var loading: CountDownLatch? = null
    private lateinit var good: PreparedNzb
    private lateinit var broken: PreparedNzb
    /** The release the player can't open: its Cues missing. */
    private lateinit var cuesGap: PreparedNzb
    private lateinit var goodBytes: ByteArray
    /** The NZBs the seam was asked for, by candidate id. */
    private val served = CopyOnWriteArrayList<String>()
    /** The releases found missing before the test, put back after it. */
    private var tried: List<TriedRelease>? = null

    private val film = WatchTarget(WatchKind.Movie, "Lantern Field", movieTmdbId = FILM)

    @Before
    fun setUp() {
        fun reachable(port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", port), 2_000) } }.isSuccess
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", reachable(1119))
        goodBytes = fixtureBytes("chapters")
        val brokenBytes = fixtureBytes("chapters-missing-article")
        val cuesGapBytes = fixtureBytes("chapters-missing-cues")
        runBlocking {
            engineSetup("the mock on the engine, the good and the broken releases prepared") {
                mockOnEngine()
                good = prepareFixture(goodBytes, "Lantern.Field.2026.1080p.WEB.h264-GOOD")
                // Prepared from their first articles: the missing ones aren't asked for yet.
                broken = prepareFixture(brokenBytes, "Lantern.Field.2026.1080p.WEB.h264-BRKN")
                cuesGap = prepareFixture(cuesGapBytes, "Lantern.Field.2026.1080p.WEB.h264-BRKN")
            }
            // As if the broken release had played the film before: it is the one Resume would choose.
            container.library.recordProgress(template(), 120_000, DURATION_MS, savedRelease(BROKEN, broken).toRemembered())
            // What earlier plays of the fixture left in the video cache would be read from it: these
            // failures are the provider's, so every article the player reads goes to it.
            container.videoCache.clear()
            tried = container.library.triedReleaseEntries()
            container.library.clearTriedReleases()
        }
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        PlayerActivity.nextEpisode = { _, _ -> null }
        PlayerActivity.previousEpisode = { _, _ -> null }
        PlaybackCoordinator.nzbSource = { _, grab ->
            NzbSource { candidate ->
                served += candidate.id
                if (candidate.id == "$INDEXER/$GOOD") goodBytes else grab.fetch(candidate)
            }
        }
    }

    @After
    fun restore() {
        PlayerActivity.lookUpSegments = realLookUp
        PlayerActivity.nextEpisode = realNext
        PlayerActivity.previousEpisode = realPrevious
        PlaybackCoordinator.nzbSource = realSource
        PlayerActivity.dataSources = realDataSources
        PlayerActivity.readPlayback = realReadPlayback
        loading?.countDown()
        instrumentation.setInTouchMode(true)
        container.playback.cancel()
        runBlocking {
            // Out of Up next.
            container.library.recordProgress(template(), 0, DURATION_MS)
            tried?.let { container.library.restoreTriedReleases(it) }
        }
    }

    private fun template() = WatchState(WatchKey.movie(FILM), WatchKind.Movie, movieTmdbId = FILM, title = film.title, updatedAt = 0)

    private fun savedRelease(guid: String, nzb: PreparedNzb) = SavedRelease(INDEXER, guid, nzb.title, null, nzb.defaultPick!!.index, nzb.id)

    private fun candidate(guid: String, title: String) = RankedItem(
        NewznabItem(
            indexerId = INDEXER, guid = guid, title = title, sizeBytes = 690_000, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        ),
        RankedRelease(ReleaseFacts("$INDEXER/$guid", title, 690_000), ReleaseParser.parse(title), playable = true, score = 1.0, reasons = emptyList(), estimatedBitrate = null),
    )

    /**
     * The broken release's launch, its NZB the one without the Cues, as the coordinator would have
     * made it for a request of both releases, the broken one first; the request is kept as the
     * coordinator keeps it.
     */
    private fun brokenLaunch(startMs: Long = START_MS, watch: WatchTarget = film): PlayerLaunch {
        val request = PlayRequest("Lantern Field", "2026", listOf(candidate(BROKEN, cuesGap.title), candidate(GOOD, good.title)), FilePicker.Largest, watch)
        val release = savedRelease(BROKEN, cuesGap)
        return PlayerLaunch(
            uri = io.skjaere.usenet.core.UsenetUris.build(cuesGap.id, release.fileIndex), title = request.title, subtitle = request.subtitle,
            watchKey = watch.key(cuesGap.id, release.fileIndex), watch = watch, release = release,
            requestId = container.playback.keep(request), startPositionMs = startMs,
        )
    }

    @Test
    fun aMissingArticleStopsPlaybackWithinSecondsWithTheRemoteOnTryAnotherRelease() = runBlocking<Unit> {
        instrumentation.setInTouchMode(false)
        // The deadline counts from the player's first read, not from its start-up, which a busy
        // emulator can make take seconds: held up here as it might be, with no retry it is still met.
        PlayerActivity.readPlayback = { c -> delay(PANEL_WITHIN_MS + 500); realReadPlayback(c) }
        val firstRead = AtomicLong()
        PlayerActivity.dataSources = { c -> DataSource.Factory { FirstOpen(c.dataSourceFactory.createDataSource(), firstRead) } }
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, brokenLaunch())).use { scenario ->
            waitFor("the panel", timeoutMs = 30_000) { scenario.read { a -> overlayWords(a).takeIf { "Playback stopped" in it } } }
            val shownAfter = SystemClock.elapsedRealtime() - firstRead.get()
            // ExoPlayer's three retries alone wait 3 s, each also fetching the article again.
            assertTrue("the panel showed $shownAfter ms after the player's first read", shownAfter < PANEL_WITHIN_MS)
            val words = scenario.read(::overlayWords)
            val error = scenario.read { a -> generateSequence<Throwable>(a.player?.playerError) { it.cause }.joinToString(" <- ") { "${it::class.simpleName}: ${it.message}" } }
            assertTrue("$words, $error", "Part of this release is missing from every Usenet provider you use." in words)
            assertTrue(words.toString(), "Try another release" in words && "Close" in words)
            waitFor("Try another release to have focus") { scenario.read { a -> focusedWords(a).takeIf { "Try another release" in it } } }
            // The controls stay down: they'd take the remote's focus to play/pause.
            assertFalse(scenario.read { playerView(it).isControllerFullyVisible })
            assertEquals(Player.STATE_IDLE, scenario.read { it.player?.playbackState })

            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("the player to close", timeoutMs = 5_000) { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
        }
        // Back is Close: nothing else plays.
        assertEquals(PlayState.Idle, container.playback.state.value)
        // Missing articles are the release's fault: Resume no longer chooses it.
        withTimeout(5_000) { while (container.library.lastRelease(WatchKey.movie(FILM))?.guid == BROKEN) delay(100) }
        // And they are missing on the provider in use: a play skips the release without a grab.
        val remembered = withTimeout(5_000) {
            var entries = container.library.triedReleases(listOf("$INDEXER/$BROKEN"))
            while (entries.isEmpty()) {
                delay(100)
                entries = container.library.triedReleases(listOf("$INDEXER/$BROKEN"))
            }
            entries.values.single()
        }
        assertEquals(listOf(ReleaseChecks.provider("mock", "10.0.2.2", 1119)), remembered.missingOn.map { it.provider })
    }

    /**
     * The same gap while two providers serve: the panel shows as before, but the release isn't remembered as missing on
     * both. nzb-streamer passes over a provider whose circuit breaker is open without asking it, and says an article the
     * other lacks is on no provider, so which were asked isn't known. Here both are the mock, and both were asked; nothing
     * tells the app so.
     */
    @Test
    fun aMissingArticleWhileTwoProvidersServeIsntRememberedAsMissingOnThem() = runBlocking<Unit> {
        engineSetup("the mock on the engine twice, as two providers") {
            container.engine.configure(EngineSettings(listOf(MOCK_PROVIDER, ProviderConfig("mock-b", "mock b", "10.0.2.2", 1119, "", "", useTls = false, maxConnections = 2))))
        }
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, brokenLaunch())).use { scenario ->
            waitFor("the panel", timeoutMs = 30_000) { scenario.read { a -> overlayWords(a).takeIf { "Playback stopped" in it } } }
            assertEquals(listOf("mock", "mock-b"), container.engine.inUse.map { it.id })
        }
        // The release is forgotten as the one that played, which the panel's failure does at once ...
        withTimeout(5_000) { while (container.library.lastRelease(WatchKey.movie(FILM))?.guid == BROKEN) delay(100) }
        // ... and, given the time a record takes, isn't remembered as missing.
        delay(2_000)
        assertEquals(emptyMap<String, TriedRelease>(), container.library.triedReleases(listOf("$INDEXER/$BROKEN")))
    }

    @Test
    fun tryAnotherReleasePlaysTheNextReleaseFromWhereTheFailedOneStopped() = runBlocking<Unit> {
        instrumentation.setInTouchMode(false)
        val broken = brokenLaunch()
        val states = CopyOnWriteArrayList<PlayState>()
        val watching = launch { container.playback.state.collect { states += it } }
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, broken)).use { scenario ->
            waitFor("Try another release to have focus", timeoutMs = 30_000) { scenario.read { a -> focusedWords(a).takeIf { "Try another release" in it } } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("the player to close", timeoutMs = 5_000) { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
        }
        val next = withTimeout(90_000) {
            merge(
                container.playback.launches,
                container.playback.state.filter { it is PlayState.Failed || it is PlayState.NoRelease }.map { throw AssertionError("nothing played: $it") },
            ).first()
        }
        watching.cancel()
        assertEquals(GOOD, next.release.guid)
        assertEquals(listOf("$INDEXER/$GOOD"), served)
        // The play sheet tried the good release alone, saying why.
        val running = states.filterIsInstance<PlayState.Running>()
        assertTrue(states.toString(), running.isNotEmpty() && running.all { it.of == 1 && "GOOD" in it.release })
        assertEquals("The release before stopped playing · trying another", running.first().notice)
        assertEquals(WatchKey.movie(FILM), next.watchKey)
        assertNotEquals(broken.requestId, next.requestId)
        val start = next.startPositionMs!!
        assertTrue("starts at $start", start in START_MS - 1_000..START_MS + 1_000)

        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, next)).use { scenario ->
            val at = waitFor("the good release to play", timeoutMs = 30_000) {
                scenario.read { a -> a.player?.takeIf { it.playbackState == Player.STATE_READY }?.currentPosition }
            }
            assertTrue("the good release started at $at ms", at in start - 1_000..start + 5_000)
            assertEquals(next.uri, scenario.read { it.player?.currentMediaItem?.localConfiguration?.uri?.toString() })
            delay(1_000)
            assertTrue(scenario.read { overlayWords(it) }.toString(), "Playback stopped" !in scenario.read { overlayWords(it) })
        }
    }

    /**
     * The same for a release played without TMDB (a search without a pick, a season pack), whose
     * progress is saved under the release itself: the good release continues the failed one's, so
     * Up next ends with the good release's row only, named after it, not the failed one's as well,
     * which nothing could resume any more.
     */
    @Test
    fun tryAnotherReleaseOnAReleasePlayLeavesOnlyTheNewReleaseInUpNext() = runBlocking<Unit> {
        instrumentation.setInTouchMode(false)
        val failed = brokenLaunch(watch = WatchTarget(WatchKind.Release, broken.title))
        val goodKey = WatchKey.release(good.id, good.defaultPick!!.index)
        try {
            // It played up to there, saving its position as it went, before running into the missing article.
            container.library.recordProgress(
                WatchState(failed.watchKey, WatchKind.Release, title = broken.title, updatedAt = 0), START_MS, DURATION_MS, failed.release.toRemembered(),
            )
            ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, failed)).use { scenario ->
                waitFor("Try another release to have focus", timeoutMs = 30_000) { scenario.read { a -> focusedWords(a).takeIf { "Try another release" in it } } }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor("the player to close", timeoutMs = 5_000) { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
            }
            val next = withTimeout(90_000) {
                merge(
                    container.playback.launches,
                    container.playback.state.filter { it is PlayState.Failed || it is PlayState.NoRelease }.map { throw AssertionError("nothing played: $it") },
                ).first()
            }
            assertEquals(goodKey, next.watchKey)
            assertEquals(good.title, next.watch.title)
            ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, next)).use { scenario ->
                waitFor("the good release to play", timeoutMs = 30_000) { scenario.read { a -> a.player?.takeIf { it.playbackState == Player.STATE_READY } } }
            }
            // Closing it saved its position, past where the failed one stopped.
            val rows = withTimeout(5_000) {
                var rows = container.library.continueWatching.first()
                while (rows.none { it.key == goodKey }) {
                    delay(100)
                    rows = container.library.continueWatching.first()
                }
                rows
            }
            assertEquals(rows.map { it.key }.toString(), listOf(goodKey), rows.map { it.key }.filter { it == goodKey || it == failed.watchKey })
            assertEquals(good.title, rows.single { it.key == goodKey }.title)
        } finally {
            // Out of Up next.
            listOf(failed.watchKey to broken.title, goodKey to good.title).forEach { (key, title) ->
                container.library.recordProgress(WatchState(key, WatchKind.Release, title = title, updatedAt = 0), 0, DURATION_MS)
            }
        }
    }

    /**
     * Resuming the film from Home when its remembered release has broken since (the article check
     * finds the missing article before anything plays): the release is forgotten, so the next
     * Resume doesn't try it again, and the sheet's Search is by the film's TMDB id, so what plays
     * from it is the film, from its saved position. Its NZB comes from the store: no indexer grab.
     */
    @Test
    fun aFilmWhoseReleaseBrokeOnResumeIsForgottenAndSearchedForByItsTmdbId() = runBlocking<Unit> {
        // The mock answers STAT for an article it has no answer for with "500 Command not
        // recognized", where real servers say 430 (library issue M1): register the 430, as its
        // seeder does for the articles it leaves out.
        assumeTrue("the mock NNTP server's control API isn't reachable at 10.0.2.2:8081", runCatching { statMissing(MISSING_ARTICLE) }.isSuccess)
        val state = container.library.state(WatchKey.movie(FILM)).first()!!
        assertEquals(BROKEN, state.release?.guid)
        container.playback.resume(state)
        val failed = withTimeout(60_000) {
            merge(
                container.playback.state.filter { it is PlayState.Failed }.map { it as PlayState.Failed },
                container.playback.launches.map { throw AssertionError("the broken release played: $it") },
            ).first()
        }
        // The check stops at the missing article: how many of the seven it had answers for by then varies.
        val line = failed.failures.single().second
        assertTrue(line, Regex("""Incomplete on your providers · 1 of [1-7] checked articles? missing""").matches(line))
        assertEquals(SearchRoute("Lantern Field", "Movie", FILM, "Lantern Field"), failed.searchRoute())
        assertEquals(null, container.library.lastRelease(WatchKey.movie(FILM)))
        assertEquals(120_000L, container.library.state(WatchKey.movie(FILM)).first()?.positionMs)
        assertEquals(emptyList<String>(), served)
    }

    /**
     * The audio and subtitles sheet, open as the player runs into the missing article, gives way
     * to the panel. The file's loading is held until the sheet is open: the Cues are read first, so
     * it fails at once.
     */
    @Test
    fun thePanelClosesTheAudioAndSubtitlesSheet() = runBlocking<Unit> {
        val gate = CountDownLatch(1)
        loading = gate
        PlayerActivity.dataSources = { c -> DataSource.Factory { Held(c.dataSourceFactory.createDataSource(), gate) } }
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, brokenLaunch())).use { scenario ->
            waitFor("the player") { scenario.read { a -> a.player } }
            scenario.onActivity { a -> views(a.window.decorView).filterIsInstance<ImageButton>().single { it.contentDescription == "Audio and subtitles" }.performClick() }
            waitFor("the sheet") { scenario.read { a -> overlayWords(a).takeIf { "Audio and subtitles" in it } } }
            gate.countDown()
            waitFor("the panel", timeoutMs = 30_000) { scenario.read { a -> overlayWords(a).takeIf { "Playback stopped" in it } } }
            val words = scenario.read(::overlayWords)
            assertFalse("the sheet stayed open: $words", "Audio and subtitles" in words)
            assertTrue("Try another release" in words)
        }
    }

    /**
     * Try another release twice in one play, through the coordinator: each time the release that
     * just failed is left out, and so is every release that failed before during the play, also one
     * that failed before the first release played (its grab, here). None is fetched again. For a
     * film, once none is left there is nothing to try. No player opens: the launches are taken here.
     */
    @Test
    fun tryAnotherReleaseLeavesOutEveryReleaseThatFailedDuringThePlay() = runBlocking<Unit> {
        val watch = WatchTarget(WatchKind.Movie, "Lantern Field", movieTmdbId = OTHER_FILM)
        val guids = listOf(REFUSED, "lantern-field-1", "lantern-field-2", "lantern-field-3")
        // The first release's grab fails; the others are the whole fixture.
        PlaybackCoordinator.nzbSource = { _, _ ->
            NzbSource { candidate ->
                served += candidate.id
                if (candidate.id == "$INDEXER/$REFUSED") throw FetchException("The indexer has no NZB for it") else goodBytes
            }
        }
        val request = PlayRequest("Lantern Field", "2026", guids.map { candidate(it, "Lantern.Field.2026.1080p.WEB.h264-$it") }, FilePicker.Largest, watch)
        container.playback.play(request)
        val first = nextLaunch()
        assertEquals(guids[1], first.release.guid)
        assertTrue(container.playback.canTryAnother(first))
        container.playback.tryAnother(first, 60_000)
        val second = nextLaunch()
        assertEquals(guids[2], second.release.guid)
        assertTrue(container.playback.canTryAnother(second))
        container.playback.tryAnother(second, 90_000)
        val third = nextLaunch()
        assertEquals(guids[3], third.release.guid)
        assertEquals(90_000L, third.startPositionMs)
        assertEquals(guids.map { "$INDEXER/$it" }, served)
        assertFalse("nothing of the film's request is left", container.playback.canTryAnother(third))
    }

    /** The coordinator's next launch; fails the test if it shows a failure instead. */
    private suspend fun nextLaunch(): PlayerLaunch = withTimeout(90_000) {
        merge(
            container.playback.launches,
            container.playback.state.filter { it is PlayState.Failed || it is PlayState.NoRelease }.map { throw AssertionError("nothing played: $it") },
        ).first()
    }

    /**
     * A failure that is no fault of the release, here the provider refusing the login for a while:
     * left with Home and come back to, the player plays the release on, and its saves keep it as
     * the release that played the film, in place of the one remembered before, which Resume would
     * otherwise play (or for a film with none, have nothing to play).
     */
    @Test
    fun aReleaseThatPlaysOnAfterAProviderFailureIsKeptAsTheReleaseThatPlayed() = runBlocking<Unit> {
        val refusing = AtomicBoolean(true)
        PlayerActivity.dataSources = { c -> DataSource.Factory { Refusing(c.dataSourceFactory.createDataSource(), refusing) } }
        val request = PlayRequest("Lantern Field", "2026", listOf(candidate(GOOD, good.title)), FilePicker.Largest, film)
        val release = savedRelease(GOOD, good)
        val launch = PlayerLaunch(
            uri = io.skjaere.usenet.core.UsenetUris.build(good.id, release.fileIndex), title = request.title, subtitle = request.subtitle,
            watchKey = WatchKey.movie(FILM), watch = film, release = release, requestId = container.playback.keep(request),
        )
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch)).use { scenario ->
            val words = waitFor("the panel", timeoutMs = 30_000) { scenario.read { a -> overlayWords(a).takeIf { "Playback stopped" in it } } }
            assertTrue(words.toString(), "A Usenet provider rejected the login (Settings › Usenet providers)." in words)
            // The login works again by the time the user comes back: Home, then the app again.
            refusing.set(false)
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            // Made again after the stop, the release waits paused where it was (W106); played, it plays on.
            waitFor("the release made again, paused", timeoutMs = 30_000) { scenario.read { a -> a.player?.takeIf { it.playbackState == Player.STATE_READY && !it.playWhenReady } } }
            scenario.onActivity { it.player!!.play() }
            waitFor("the release to play on", timeoutMs = 30_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying } } }
            assertFalse("Playback stopped" in scenario.read(::overlayWords))
        }
        // Closing the player saved where it was, with the release that played.
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (container.library.lastRelease(WatchKey.movie(FILM))?.guid != GOOD && SystemClock.elapsedRealtime() < deadline) delay(100)
        assertEquals(GOOD, container.library.lastRelease(WatchKey.movie(FILM))?.guid)
    }

    /**
     * The provider's connection dropping partway through an article, here the fourth of the good
     * release, where the player starts: the stream fails as a connection failure, which the player
     * loads again, unlike a missing article. The provider is a proxy in front of the mock that cuts
     * the connection while it sends that article, until the player has failed once; loaded again,
     * the release plays on from there, no panel shown.
     */
    @Test
    fun aDroppedConnectionIsLoadedAgainAndTheReleasePlaysOn() = runBlocking<Unit> {
        val fourth = NzbParser.parse(goodBytes).files.single().segments.single { it.number == 4 }.articleId
        DroppingProxy(InetSocketAddress("10.0.2.2", 1119), "<$fourth>").use { proxy ->
            engineSetup("the engine on the dropping proxy") {
                container.engine.configure(EngineSettings(listOf(ProviderConfig("proxy", "proxy", "127.0.0.1", proxy.port, "", "", useTls = false, maxConnections = 4))))
            }
            val failures = CopyOnWriteArrayList<UsenetDataSourceException>()
            PlayerActivity.dataSources = { c -> DataSource.Factory { Failing(c.dataSourceFactory.createDataSource(), failures) { proxy.dropping = false } } }
            val request = PlayRequest("Lantern Field", "2026", listOf(candidate(GOOD, good.title)), FilePicker.Largest, film)
            val release = savedRelease(GOOD, good)
            val launch = PlayerLaunch(
                uri = io.skjaere.usenet.core.UsenetUris.build(good.id, release.fileIndex), title = request.title, subtitle = request.subtitle,
                watchKey = WatchKey.movie(FILM), watch = film, release = release, requestId = container.playback.keep(request), startPositionMs = START_MS,
            )
            ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch)).use { scenario ->
                waitFor("the release to play on past the dropped article's start", timeoutMs = 30_000) {
                    scenario.read { a ->
                        val words = overlayWords(a)
                        if ("Playback stopped" in words) throw AssertionError("the player gave up: $words, ${failures.toList()}")
                        a.player?.takeIf { it.isPlaying && it.currentPosition > START_MS + 1_000 }
                    }
                }
            }
            assertTrue("the connection never dropped", proxy.drops.get() > 0)
            val failure = failures.firstOrNull() ?: throw AssertionError("the player never saw the dropped connection")
            val error = failure.usenetError()
            assertTrue("$error: $failure", error is UsenetError.ConnectionFailed || error is UsenetError.Network)
            assertFalse(UsenetLoadErrorHandlingPolicy.isPermanent(failure))
        }
    }

    /** Has the mock NNTP server answer STAT for [articleId] with 430, through its control API. */
    private fun statMissing(articleId: String) {
        val connection = java.net.URL("http://10.0.2.2:8081/mocks/stat").openConnection() as java.net.HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 2_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"articleId":"$articleId","exists":false}""".encodeToByteArray()) }
            check(connection.responseCode in 200..299) { "the mock answered ${connection.responseCode}" }
        } finally {
            connection.disconnect()
        }
    }

    /** A data source that notes when the first of its kind opens, in [at] (elapsed realtime). */
    private class FirstOpen(private val source: DataSource, private val at: AtomicLong) : DataSource by source {
        override fun open(dataSpec: DataSpec): Long {
            at.compareAndSet(0, SystemClock.elapsedRealtime())
            return source.open(dataSpec)
        }
    }

    /** A data source whose provider refuses the login while [refusing] is set. */
    private class Refusing(private val source: DataSource, private val refusing: AtomicBoolean) : DataSource by source {
        override fun open(dataSpec: DataSpec): Long {
            if (refusing.get()) {
                val error = UsenetError.AuthFailed("481 Authentication rejected")
                throw UsenetDataSourceException(error, IOException(error.detail), PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
            }
            return source.open(dataSpec)
        }
    }

    /**
     * A data source that keeps the stream failures it passes on in [failures], calling [onFailure]
     * at each; not a read the player interrupts, as it does to seek.
     */
    private class Failing(
        private val source: DataSource,
        private val failures: MutableList<UsenetDataSourceException>,
        private val onFailure: () -> Unit,
    ) : DataSource by source {
        override fun open(dataSpec: DataSpec): Long = failing { source.open(dataSpec) }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = failing { source.read(buffer, offset, length) }

        private fun <T> failing(block: () -> T): T = try {
            block()
        } catch (e: UsenetDataSourceException) {
            failures += e
            onFailure()
            throw e
        }
    }

    /**
     * A provider in front of the NNTP server at [upstream] that, while [dropping] is set, drops
     * the connection partway through its answer to `BODY [article]`, as a provider's connection
     * can drop. Commands are passed on a line at a time; nothing else is changed.
     */
    private class DroppingProxy(private val upstream: InetSocketAddress, private val article: String) : AutoCloseable {
        @Volatile var dropping = true
        val drops = AtomicInteger()
        // Not getLoopbackAddress(), which is ::1 on Android: the engine is given 127.0.0.1.
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true, name = "dropping-proxy") {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true, name = "dropping-proxy-conn") { relay(client) }
                }
            }
        }

        private fun relay(client: Socket) {
            val remote = Socket()
            if (runCatching { remote.connect(upstream, 5_000) }.isFailure) return client.close()
            // Set once the article is asked for: the next answer is that article.
            val cut = AtomicBoolean()
            thread(isDaemon = true, name = "dropping-proxy-down") {
                runCatching {
                    val input = remote.getInputStream()
                    val output = client.getOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (cut.get()) {
                            output.write(buffer, 0, minOf(n, 2_000))
                            output.flush()
                            drops.incrementAndGet()
                            break
                        }
                        output.write(buffer, 0, n)
                        output.flush()
                    }
                }
                client.close()
                remote.close()
            }
            runCatching {
                val input = client.getInputStream()
                val output = remote.getOutputStream()
                val line = ByteArrayOutputStream()
                while (true) {
                    val b = input.read()
                    if (b < 0) break
                    line.write(b)
                    if (b == '\n'.code) {
                        if (dropping && line.toString(Charsets.ISO_8859_1.name()).trim() == "BODY $article") cut.set(true)
                        output.write(line.toByteArray())
                        output.flush()
                        line.reset()
                    }
                }
            }
            client.close()
            remote.close()
        }

        override fun close() = server.close()
    }

    /** A data source that opens once [gate] is open. */
    private class Held(private val source: DataSource, private val gate: CountDownLatch) : DataSource by source {
        override fun open(dataSpec: DataSpec): Long {
            gate.await()
            return source.open(dataSpec)
        }
    }

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

    /** Every semantics node of the shown Compose views: the panel, and the sheet while it is open. */
    private fun overlayNodes(activity: Activity): List<SemanticsNode> =
        views(activity.window.decorView).filterIsInstance<ViewRootForTest>().filter { it.view.isShown }
            .flatMap { subtree(it.semanticsOwner.unmergedRootSemanticsNode) }

    private fun subtree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::subtree)

    private fun SemanticsNode.words(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            listOfNotNull(config.getOrNull(SemanticsProperties.PaneTitle))

    private fun overlayWords(activity: Activity) = overlayNodes(activity).flatMap { it.words() }

    private fun focusedWords(activity: Activity) =
        overlayNodes(activity).firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }?.let(::subtree).orEmpty().flatMap { it.words() }

    private companion object {
        /** An invented film's TMDB id; nothing real has it. */
        const val FILM = 900021
        const val INDEXER = "playback-failed-device-test"
        const val BROKEN = "lantern-field-broken"
        /** The article chapters-missing-article.nzb has in place of chapters.nzb's fourth. */
        const val MISSING_ARTICLE = "<seed-fe2c646e-0-4-not-on-the-mock@mock>"
        const val GOOD = "lantern-field-good"
        /** A release whose grab fails. */
        const val REFUSED = "lantern-field-refused"
        /** Another invented film's TMDB id, with no release remembered for it. */
        const val OTHER_FILM = 900022
        /** Where the broken release's play is to start, at a keyframe (every 5 s): its Cues, read first, stop it. */
        const val START_MS = 170_000L
        const val DURATION_MS = 360_000L
        /**
         * From the player's first read, well under ExoPlayer's own retries: their delays alone are
         * 3 s. On the emulator the panel showed within 0.5 s of the first read, and 3.7 s after
         * the player opened with ExoPlayer's default policy.
         */
        const val PANEL_WITHIN_MS = 2_000L
    }
}
