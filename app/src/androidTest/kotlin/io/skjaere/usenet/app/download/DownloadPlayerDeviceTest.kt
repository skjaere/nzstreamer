package io.skjaere.usenet.app.download

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.ImageButton
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.clickOverlay
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.link.CastButtonLook
import io.skjaere.usenet.app.mockOnEngine
import io.skjaere.usenet.app.momentLabel
import io.skjaere.usenet.app.overlayNodes
import io.skjaere.usenet.app.overlayWords
import io.skjaere.usenet.app.play.AutoAdvance
import io.skjaere.usenet.app.play.ControlsPlayer
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.Prefetch
import io.skjaere.usenet.app.play.WatchSaver
import io.skjaere.usenet.app.read
import io.skjaere.usenet.app.views
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.core.FetchException
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadGap
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.EpisodeRef
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.metadata.TmdbError
import io.skjaere.usenet.metadata.TmdbException
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.player.PlayGaps
import io.skjaere.usenet.player.PlayUris
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * L354, L377: the player's branches for a finished download's file, on this phone, end to end. A recorded gap is skipped
 * as it plays, with the notice, and no Cast button shows. Offline, the next-episode card for an episode not downloaded
 * says so, with Close alone. A downloaded next episode counts down and plays from its file through the coordinator's
 * hook, with no search. Online, the next episode not downloaded is the next even with a later one downloaded, and it is
 * searched for and streamed (the mock and the fake indexer). File details say where the file came from in the providers'
 * place. A file gone says so, with Stream instead offered only once the network is back, which streams the episode. And
 * nothing is deleted while it plays (L413): a download removed, or deleted by Delete all, while its player plays it keeps
 * its file, still read by the player's source, until the player finishes; a second download launched to the open player
 * takes the player's hold from the first, and the same one launched again keeps it.
 *
 * The downloads are seeded as a finished one is left, through the app's own storage: the device tests' chapters.mkv
 * written as the file (its recorded gaps as zeros), renamed done, then its row with its marker, Done. The titles, ids and
 * release names are invented; Harbor Lights' are the fake indexer's. Put back after each, whatever happened: the rows and
 * their files deleted, the downloads' jobs cancelled, the library's marks, the indexers and the seams as they were, Wi-Fi
 * on and mobile data as it was.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class DownloadPlayerDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its window's focus. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val storage = container.downloadStorage
    private val dao = container.data.downloads
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val realFetch = PlaybackCoordinator.fetchNzb
    private val realNext = PlayerActivity.nextEpisode
    private val realPrevious = PlayerActivity.previousEpisode
    private val realPrefetch = PlayerActivity.prefetchNext
    private val realLookUp = PlayerActivity.lookUpSegments
    private val realRead = PlayerActivity.readPlayback
    private val realTimings = PlayerActivity.timings
    private val prefetches = AtomicInteger()
    private val made = mutableListOf<Download>()
    /** What a test watches the coordinator by, cancelled at its end. */
    private val watching = mutableListOf<CoroutineScope>()
    private var tmdb: FakeTmdb? = null

    /**
     * What was on before a test that swapped the indexers for the fake one, switched back on after it: the Usenet indexers,
     * and any torrent indexer or service account, so nothing but the fake is asked.
     */
    private var switchedOff: List<String>? = null
    private var torrentOff: List<String> = emptyList()
    private var servicesOff: List<String> = emptyList()

    // What the test changed of the device, so its end puts back exactly that.
    private var wifiOff = false
    private var dataWas: String? = null

    @Before
    fun setUp() = runBlocking<Unit> {
        assertFalse("a phone", container.television)
        // The debug build's start saves its dev settings: before the test reads or takes any.
        withTimeout(SEEDING_MILLIS) { container.devSettingsSaved.await() }
        PlayerActivity.nextEpisode = { _, _ -> null }
        PlayerActivity.previousEpisode = { _, _ -> null }
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        PlayerActivity.readPlayback = { realRead(it).copy(autoPlayNext = true) }
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        val failures = mutableListOf<Throwable>()
        suspend fun back(what: suspend () -> Unit) {
            try {
                what()
            } catch (e: Throwable) {
                failures += e
            }
        }
        PlaybackCoordinator.fetchNzb = realFetch
        PlayerActivity.nextEpisode = realNext
        PlayerActivity.previousEpisode = realPrevious
        PlayerActivity.prefetchNext = realPrefetch
        PlayerActivity.lookUpSegments = realLookUp
        PlayerActivity.readPlayback = realRead
        PlayerActivity.timings = realTimings
        back { tmdb?.close() }
        watching.forEach { it.cancel() }
        container.playback.cancel()
        // No job of the downloads' left behind, then the rows, their files and any notification of theirs.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) back { DownloadJobService.scheduler(context).cancelAll() }
        val manager = context.getSystemService(NotificationManager::class.java)
        made.forEach { row ->
            back { storage.delete(row.id) }
            back { manager.cancel(row.id, DownloadNotifications.DONE_ID) }
        }
        back {
            // Unwatched again and out of Up next, the releases the player kept for them forgotten.
            made.groupBy { it.showTmdbId!! to it.title }.forEach { (show, rows) ->
                val refs = (1..EPISODES).map { EpisodeRef(show.first, 1, it, WatchTitle.episode(show.second, 1, it)) }
                container.library.markEpisodes(refs, watched = false)
                rows.forEach { row -> row.release?.let { container.library.forgetRelease(row.watchKey, it) } }
            }
        }
        switchedOff?.let { off ->
            back {
                container.settings.deleteIndexer(FAKE_INDEXER)
                off.forEach { container.settings.setIndexerEnabled(it, true) }
                torrentOff.forEach { container.settings.setTorrentIndexerEnabled(it, true) }
                servicesOff.forEach { container.settings.setServiceEnabled(it, true) }
            }
        }
        if (wifiOff) back { shell("svc wifi enable") }
        dataWas?.let { was -> back { shell(if (was == "1") "svc data enable" else "svc data disable") } }
        if (wifiOff) back { waitFor("Wi-Fi back for the next test", BACK_MILLIS) { validatedWifi() } }
        failures.firstOrNull()?.let { first -> failures.drop(1).forEach(first::addSuppressed); throw first }
    }

    /**
     * A part no provider had, recorded with the download just after a cluster's start a minute in: the play goes on past it
     * to the next cue, the notice says so, nothing stops; and the top bar has no Cast button while the download plays.
     */
    @Test
    fun aRecordedGapIsSkippedWithTheNoticeAndNoCastButtonShows() = runBlocking<Unit> {
        val mkv = DownloadFixtures.media(MEDIA)
        val at = clusters(mkv)[13] + 100
        val gap = at until at + PART
        val row = seed(episode(QUIET, 1, gaps = listOf(gap)))
        val launch = checkNotNull(DownloadLaunch.of(row)).copy(startPositionMs = GAP_START_MS)
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch)).use { scenario ->
            var last = -1L
            var jump = 0L
            var notice = false
            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (true) {
                val (position, playing, words) = scenario.read { a -> Triple(a.player?.currentPosition ?: -1, a.player?.isPlaying == true, overlayWords(a)) }
                if ("Playback stopped" in words) throw AssertionError("the play stopped: $words")
                if (scenario.read(::momentLabel) != null) notice = true
                if (playing && last >= 0) jump = maxOf(jump, position - last)
                last = if (playing) position else -1
                if (playing && position > GAP_START_MS + 20_000) break
                if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("stuck at $position ms, playing $playing")
                delay(100)
            }
            assertTrue("the play never jumped: at most $jump ms between two looks 100 ms apart", jump >= 2_000)
            // A file on this device streams nothing: no stream-cost line while it plays (L354).
            assertTrue("playing", scenario.read { a -> a.player?.isPlaying == true })
            assertFalse("the stream-cost line runs for a download's play", container.streamCost.running)
            assertTrue("\"${PlayerActivity.MISSING_PART_SKIPPED}\" never showed", notice)
            assertEquals(listOf(gap.first..gap.last), scenario.read { a -> a.jumps.map(PlayGaps.Jump::bytes) })
            // The top bar with the controls: its buttons, but no Cast (L377).
            scenario.onActivity { a -> playerView(a).showController() }
            waitFor("the top bar") { scenario.read { a -> button(a, DETAILS).takeIf { it.isShown } } }
            assertTrue("a Cast button shows", scenario.read { a -> overlayNodes(a).none { it.config.getOrNull(CastButtonLook) != null } })
        }
    }

    /**
     * Three recorded gaps within ten minutes of film: the player skips each as its loader passes it and counts them, and the
     * missing-parts prompt says so. Offline it is the count alone, with nothing to offer; with the network back it asks to
     * stream instead, and Stream instead streams the episode by its stream path (looked up, searched for, grabbed over the
     * mock), another release than the download's, which would meet the same parts missing.
     */
    @Test
    fun threeGapsBringThePromptWhichOffersStreamInsteadOnlyOnline() = runBlocking<Unit> {
        onTheMock()
        tmdb = FakeTmdb(shows = mapOf(HARBOR.id to show(HARBOR)), seasons = { id, number -> if (id == HARBOR.id && number == 1) season(HARBOR) else null })
        val mkv = DownloadFixtures.media(MEDIA)
        val gaps = listOf(13, 15, 17).map { clusters(mkv)[it] + 100 }.map { it until it + PART }
        val row = seed(episode(HARBOR, 1, gaps = gaps, release = GOOD))
        offline()
        // The prompt's time runs as the film plays: long enough here for the test to answer it.
        PlayerActivity.timings = PlayerActivity.Timings(missingPartsAnswerForMs = PROMPT_MILLIS, missingPartsCountOnlyForMs = PROMPT_MILLIS)
        val launch = checkNotNull(DownloadLaunch.of(row)).copy(startPositionMs = GAP_START_MS)
        val tried = triedReleases()
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch)).use { scenario ->
            val count = waitFor("the missing parts counted", 60_000) { scenario.read { a -> overlayWords(a).takeIf { "This release is missing parts (3 so far)" in it } } }
            assertFalse(count.toString(), "Stream instead" in count || "Try another release" in count || "Keep watching" in count)
            online()
            val asked = waitFor("Stream instead with the network back", 30_000) { scenario.read { a -> overlayWords(a).takeIf { "Stream instead" in it } } }
            assertTrue(asked.toString(), "Stream instead? It starts where you are." in asked && "Keep watching" in asked)
            assertFalse(asked.toString(), "Try another release" in asked)
            scenario.onActivity { a -> clickOverlay(a, "Stream instead") }
            waitFor("the player to close", 30_000) { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
        }
        val streamed = launchFor(row.watchKey)
        assertTrue("S01E01 plays from ${PlayUris.scheme(streamed.uri)}", PlayUris.parse(streamed.uri) is PlayUris.Target.Usenet)
        // Not the download's own release, which would meet the same parts missing: neither tried nor played.
        assertTrue("the download's own release streams again", streamed.release.guid != GOOD.guid)
        assertFalse("the download's own release was tried: $tried", GOOD.title in tried)
    }

    /**
     * Offline, with TMDB not reached: the next episode is the row's next in its season, not downloaded, and the card says
     * so with Close alone: no Play now, no countdown, no search, and the controls' Next plays nothing. With the network
     * back while it shows, the episode the row stood in for has nothing to be searched by (no year, no other titles), so
     * the card stays so and nothing is searched.
     */
    @Test
    fun offlineTheCardForANextEpisodeNotDownloadedSaysSoWithClose() = runBlocking<Unit> {
        val row = seed(episode(QUIET, 2))
        offline()
        PlayerActivity.nextEpisode = { _, _ -> throw TmdbException(TmdbError.Network("not reached")) }
        PlayerActivity.prefetchNext = { _, _, _ -> prefetches.incrementAndGet(); MutableStateFlow(Prefetch.Searching) }
        // The card from the start: the file is six minutes long.
        PlayerActivity.timings = PlayerActivity.Timings(cardBeforeEndMs = 400_000)
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, checkNotNull(DownloadLaunch.of(row)))).use { scenario ->
            val card = waitFor("the next-episode card", CARD_MILLIS) { scenario.read { a -> cardWords(a).takeIf { w -> w.any { "S01E03" in it } } } }
            assertTrue(card.toString(), "Not downloaded · offline" in card && "Close" in card)
            assertFalse(card.toString(), "Play now" in card || "Stop" in card || "Next episode plays automatically" in card)
            assertFalse("the controls' Next plays it", scenario.read { a -> playerView(a).player?.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT) == true })
            // A keyboard's or a remote's next-track key plays nothing either.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_NEXT)
            delay(KEY_MILLIS)
            assertEquals("the next-track key closed the player", Lifecycle.State.RESUMED, scenario.state)
            // The key may bring up the controls, under which the card is one low row: the whole card again without them.
            scenario.onActivity { a -> playerView(a).hideController() }
            waitFor("the card after the key") { scenario.read { a -> cardWords(a).takeIf { w -> w.any { "S01E03" in it } } } }
            online()
            // A few of the card's ticks with the network back.
            delay(STAND_IN_MILLIS)
            val back = scenario.read { a -> cardWords(a) }
            assertTrue(back.toString(), "Not downloaded · offline" in back && "Close" in back)
            assertFalse(back.toString(), "Play now" in back || "Next episode plays automatically" in back)
            assertFalse("the controls' Next plays it online", scenario.read { a -> playerView(a).player?.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT) == true })
            scenario.onActivity { a -> clickOverlay(a, "Close") }
            waitFor("the card to go") { scenario.read { a -> cardWords(a).none { "S01E03" in it }.takeIf { it } } }
        }
        assertEquals("searches for the next episode", 0, prefetches.get())
    }

    /**
     * Offline, with TMDB answering from what it kept that nothing has aired after the episode (a weekly show's newest), its
     * answer stands: the row's next, which TMDB's count of the season names but hasn't aired, isn't offered, no card shows,
     * and the player leaves at the end as for any last episode.
     */
    @Test
    fun offlineWhenTmdbSaysNothingHasAiredAfterItNoCardShowsAndThePlayerLeaves() = runBlocking<Unit> {
        val row = seed(episode(QUIET, 2))
        offline()
        PlayerActivity.prefetchNext = { _, _, _ -> prefetches.incrementAndGet(); MutableStateFlow(Prefetch.Searching) }
        PlayerActivity.timings = PlayerActivity.Timings(cardBeforeEndMs = 400_000)
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, checkNotNull(DownloadLaunch.of(row)))).use { scenario ->
            seekNearTheEnd(scenario, beforeEndMs = 3_000)
            waitFor("the player to leave at the end", 60_000) {
                if (scenario.state == Lifecycle.State.DESTROYED) return@waitFor Unit
                val words = runCatching { scenario.read { a -> cardWords(a) } }.getOrDefault(emptyList())
                if (words.any { "S01E03" in it || "Not downloaded" in it }) throw AssertionError("a card for the next episode: $words")
                null
            }
        }
        assertEquals("searches for the next episode", 0, prefetches.get())
    }

    /**
     * Offline, Previous in the first seconds, with TMDB naming an episode before that isn't downloaded (from what it kept):
     * no stream can start, so Previous starts this one again, and the player stays, nothing handed to the coordinator.
     */
    @Test
    fun offlinePreviousToAnEpisodeNotDownloadedStartsThisOneAgain() = runBlocking<Unit> {
        val row = seed(episode(QUIET, 2))
        offline()
        PlayerActivity.previousEpisode = { _, playing -> play(QUIET, 1).takeIf { playing == AutoAdvance.Episode(QUIET.id, 1, 2) } }
        val states = CopyOnWriteArrayList<PlayState>()
        val watching = CoroutineScope(Dispatchers.Default)
        watching.launch { container.playback.state.collect { states += it } }
        try {
            ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, checkNotNull(DownloadLaunch.of(row)))).use { scenario ->
                waitFor("the file playing", 30_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying } } }
                waitFor("Previous looked up") { scenario.read { a -> (playerView(a).player as ControlsPlayer).playPrevious } }
                // Paused, so the place it is left at reads as it was put.
                scenario.onActivity { a -> playerView(a).player!!.run { pause(); seekTo(PREVIOUS_AT_MS) } }
                scenario.onActivity { a -> playerView(a).player!!.seekToPrevious() }
                // Long enough for a hand-over to close the player.
                delay(STAND_IN_MILLIS)
                assertEquals("the player closed", Lifecycle.State.RESUMED, scenario.state)
                val position = scenario.read { a -> a.player!!.currentPosition }
                assertTrue("at $position ms, not started again", position < PREVIOUS_AT_MS)
            }
            assertTrue("the play sheet showed $states", states.all { it == PlayState.Idle })
        } finally {
            watching.cancel()
        }
    }

    /**
     * A downloaded next episode: the card says so and counts down, and at the end the coordinator's hook plays its file,
     * with nothing searched or grabbed and no play sheet. Offline, as a downloaded episode is played: the debug build's
     * indexers stay on, so a hook that missed would search them, and with no network that search reaches nobody.
     */
    @Test
    fun aDownloadedNextEpisodePlaysFromItsFileAtTheEndWithNoSearch() = runBlocking<Unit> {
        val grabs = AtomicInteger()
        PlaybackCoordinator.fetchNzb = { _, _, _ ->
            grabs.incrementAndGet()
            throw FetchException("Not to be fetched", stopsAll = true)
        }
        val first = seed(episode(QUIET, 1))
        val second = seed(episode(QUIET, 2))
        offline()
        PlayerActivity.nextEpisode = { _, playing -> play(QUIET, 2).takeIf { playing == AutoAdvance.Episode(QUIET.id, 1, 1) } }
        PlayerActivity.prefetchNext = { _, _, _ -> prefetches.incrementAndGet(); MutableStateFlow(Prefetch.Searching) }
        PlayerActivity.timings = PlayerActivity.Timings(countdownMs = 2_000)
        val states = CopyOnWriteArrayList<PlayState>()
        val watching = CoroutineScope(Dispatchers.Default)
        watching.launch { container.playback.state.collect { states += it } }
        try {
            ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, checkNotNull(DownloadLaunch.of(first)))).use { scenario ->
                seekNearTheEnd(scenario)
                val card = waitFor("the card for the downloaded S01E02", CARD_MILLIS) { scenario.read { a -> cardWords(a).takeIf { "Downloaded · ready" in it } } }
                assertTrue(card.toString(), "Stop" in card && "Play now" in card && "Next episode plays automatically" in card)
                waitFor("the player to close", 30_000) { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
            }
            val next = withTimeout(LAUNCH_MILLIS) { container.playback.launches.filter { it.watchKey == second.watchKey }.first() }
            assertEquals(PlayUris.download(second.id), next.uri)
            assertEquals("searches for the next episode", 0, prefetches.get())
            assertEquals("indexer grabs", 0, grabs.get())
            assertTrue("the play sheet showed $states", states.all { it == PlayState.Idle })
        } finally {
            watching.cancel()
        }
    }

    /**
     * Online, E01 and E03 downloaded and E02 not: the card after E01 is E02's, never the later download's, and it searches
     * as before ("Release found"); at the end E02 streams from the fake indexer's release over the mock.
     */
    @Test
    fun onlineTheNextEpisodeNotDownloadedIsSearchedForAndStreams() = runBlocking<Unit> {
        onTheMock()
        val first = seed(episode(HARBOR, 1))
        seed(episode(HARBOR, 3))
        PlayerActivity.nextEpisode = { _, playing -> play(HARBOR, 2).takeIf { playing == AutoAdvance.Episode(HARBOR.id, 1, 1) } }
        PlayerActivity.timings = PlayerActivity.Timings(countdownMs = 3_000)
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, checkNotNull(DownloadLaunch.of(first)))).use { scenario ->
            seekNearTheEnd(scenario)
            val card = waitFor("the card for S01E02", CARD_MILLIS) { scenario.read { a -> cardWords(a).takeIf { w -> w.any { "S01E02" in it } } } }
            assertFalse(card.toString(), card.any { "S01E03" in it })
            waitFor("the release found for S01E02", 60_000) { scenario.read { a -> cardWords(a).firstOrNull { it.startsWith("Release found") } } }
            assertFalse(scenario.read { a -> cardWords(a).toString() }, scenario.read { a -> cardWords(a).any { it.startsWith("Downloaded") } })
            waitFor("the player to close", 60_000) { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
        }
        val next = launchFor(WatchKey.episode(HARBOR.id, 1, 2))
        assertTrue("S01E02 plays from ${PlayUris.scheme(next.uri)}", PlayUris.parse(next.uri) is PlayUris.Target.Usenet)
    }

    /** File details' Source for a download: the indexer as for any play, when it was downloaded to this phone, and the parts missing; no providers. */
    @Test
    fun fileDetailsSayWhereTheFileCameFromAndWhatIsMissing() = runBlocking<Unit> {
        val mkv = DownloadFixtures.media(MEDIA)
        val gaps = listOf(30, 40).map { clusters(mkv)[it] + 100 }.map { it until it + PART }
        val row = seed(episode(QUIET, 1, gaps = gaps, completedAt = System.currentTimeMillis()))
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, checkNotNull(DownloadLaunch.of(row)))).use { scenario ->
            waitFor("the file's tracks", 30_000) { scenario.read { a -> a.player?.currentTracks?.takeUnless { it.isEmpty } } }
            scenario.onActivity { a ->
                playerView(a).showController()
                button(a, DETAILS).performClick()
            }
            val rows = waitFor("File details' Source") { scenario.read { a -> detailRows(a).takeIf { "Indexer" in it } } }
            val day = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).format(LocalDate.now())
            assertEquals(listOf("$day, to this phone", "plays from the file, not streamed"), rows["Downloaded"])
            assertEquals(listOf("2 parts", "skipped as it plays"), rows["Missing"])
            assertFalse("providers named: $rows", "Providers" in rows)
        }
    }

    /**
     * A download whose file is gone: offline the panel says so with Close alone; with the network back it offers Stream
     * instead, which streams the episode by its stream path (looked up, searched for, grabbed over the mock), never the
     * download again, from where its player was.
     */
    @Test
    fun aFileGoneSaysSoAndStreamInsteadComesWithTheNetwork() = runBlocking<Unit> {
        onTheMock()
        tmdb = FakeTmdb(shows = mapOf(HARBOR.id to show(HARBOR)), seasons = { id, number -> if (id == HARBOR.id && number == 1) season(HARBOR) else null })
        val row = seed(episode(HARBOR, 1, release = GOOD))
        checkNotNull(storage.file(row)).delete()
        offline()
        val launch = checkNotNull(DownloadLaunch.of(row)).copy(startPositionMs = GONE_AT_MS)
        // Played from its file before, as its player saves it: its release is the one that played the episode.
        val before = checkNotNull(WatchSaver.of(launch, GONE_AT_MS, PLAYED_MS, releaseFailed = false))
        container.library.recordProgress(before.template, before.positionMs, before.durationMs, before.release)
        val tried = triedReleases()
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch)).use { scenario ->
            val panel = waitFor("Playback stopped", 30_000) { scenario.read { a -> overlayWords(a).takeIf { "Playback stopped" in it } } }
            assertTrue(panel.toString(), "The downloaded file is gone" in panel && "Close" in panel)
            assertFalse(panel.toString(), "Stream instead" in panel || "Try another release" in panel)
            // Its row fails at once, as the next start's reconcile would fail it: no Play of the title finds the file again.
            waitFor("the download failed as gone") { runBlocking { container.data.downloads.get(row.id) }?.takeIf { it.state == DownloadState.Failed } }
            assertEquals(DownloadStorage.FILE_GONE, runBlocking { container.data.downloads.get(row.id) }?.reason)
            waitFor("the download out of the finished ones") { container.downloads.done.value[row.watchKey]?.let { null } ?: Unit }
            online()
            waitFor("Stream instead with the network back", 30_000) { scenario.read { a -> overlayWords(a).takeIf { "Stream instead" in it } } }
            scenario.onActivity { a -> clickOverlay(a, "Stream instead") }
            waitFor("the player to close", 30_000) { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
        }
        val streamed = launchFor(row.watchKey)
        assertTrue("S01E01 plays from ${PlayUris.scheme(streamed.uri)}", PlayUris.parse(streamed.uri) is PlayUris.Target.Usenet)
        // Its file gone says nothing of its release, which is tried first as the one that played the episode, never left
        // out. (Which plays may be another: the first connection after the network came back can be cut.)
        assertEquals(GOOD.title, tried.firstOrNull())
        // From where the player was, which its place saved wouldn't give past nine tenths or under a minute.
        val at = checkNotNull(streamed.startPositionMs) { "the stream starts where its place was saved, not where the player was" }
        assertTrue("the stream starts at $at ms", abs(at - GONE_AT_MS) < PLACE_SLACK_MS)
    }

    /**
     * L413: a download removed while its player plays it is hidden at once, and past its Undo's time keeps its file while
     * the player holds it: the player's source still reads it, and a seek far ahead plays on. Once the player finishes, its
     * file goes, row and all.
     */
    @Test
    fun aDownloadRemovedWhileItPlaysKeepsItsFileUntilThePlayerFinishesThenItGoes() = runBlocking<Unit> {
        val row = seed(episode(QUIET, 1))
        val file = checkNotNull(storage.file(row))
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, checkNotNull(DownloadLaunch.of(row)))).use { scenario ->
            waitFor("the file playing", 30_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying } } }
            assertTrue("held by its player", row.id in container.downloads.held.value)
            container.downloads.remove(listOf(row.id), keepFor = UNDO_MILLIS)
            delay(UNDO_MILLIS + SETTLE_MILLIS)
            assertEquals("hidden at once", DownloadState.Deleting, dao.get(row.id)?.state)
            assertTrue("its file, while it plays", file.isFile)
            assertTrue("the player's source reads it", readsThrough(row.id))
            scenario.onActivity { it.player!!.seekTo(SEEK_MS) }
            waitFor("playing on past the seek", 30_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying && it.currentPosition >= SEEK_MS } } }
            assertFalse("the play stopped", "Playback stopped" in scenario.read(::overlayWords))
        }
        waitFor("the download gone with its play", 30_000) { if (runBlocking { dao.get(row.id) } == null && !file.exists()) Unit else null }
        assertFalse(storage.bookkeeping(row.id).exists())
        assertFalse("the player's source reads it still", readsThrough(row.id))
    }

    /**
     * L413: Delete all while a download plays deletes every other download at once, and leaves the one playing hidden with
     * its file, which its player's source still reads, until the player finishes; then it goes too.
     */
    @Test
    fun deleteAllDuringAPlayKeepsThePlayingFileUntilThePlayerFinishes() = runBlocking<Unit> {
        val playing = seed(episode(QUIET, 1))
        val other = seed(episode(QUIET, 2))
        val playingFile = checkNotNull(storage.file(playing))
        val otherFile = checkNotNull(storage.file(other))
        // Delete all deletes every download on the device: only the test's own may be there.
        val others = dao.all().first().filter { row -> made.none { it.id == row.id } }
        if (others.isNotEmpty()) throw AssertionError("${others.size} download(s) not the test's, which Delete all would delete: ${others.map { it.id }}")
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, checkNotNull(DownloadLaunch.of(playing)))).use { scenario ->
            waitFor("the file playing", 30_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying } } }
            container.downloads.deleteAll()
            assertEquals(null, dao.get(other.id))
            assertFalse(otherFile.exists())
            assertEquals("hidden at once", DownloadState.Deleting, dao.get(playing.id)?.state)
            assertTrue("its file, while it plays", playingFile.isFile)
            assertTrue(storage.bookkeeping(playing.id).isDirectory)
            assertTrue("the player's source reads it", readsThrough(playing.id))
            delay(SETTLE_MILLIS)
            assertTrue(scenario.read { a -> a.player?.isPlaying == true })
        }
        waitFor("the download gone with its play", 30_000) { if (runBlocking { dao.get(playing.id) } == null && !playingFile.exists()) Unit else null }
        assertFalse(storage.bookkeeping(playing.id).exists())
    }

    /**
     * L413: a second download launched to the open player (onNewIntent, as a notification's Play reaches a player in
     * picture-in-picture) takes the player's hold from the first in one step: the first, removed, goes once its Undo's time
     * is over, while the second, removed, waits for the player. The second launched again keeps its hold, never let go
     * between (the held ids watched as they change), and plays from its file though it is being deleted; it goes once the
     * player finishes.
     */
    @Test
    fun aDownloadLaunchedToTheOpenPlayerTakesItsHoldAndTheSameOneLaunchedAgainKeepsIt() = runBlocking<Unit> {
        val first = seed(episode(QUIET, 1))
        val second = seed(episode(QUIET, 2))
        val firstFile = checkNotNull(storage.file(first))
        val secondFile = checkNotNull(storage.file(second))
        val secondLaunch = checkNotNull(DownloadLaunch.of(second))
        // The held ids of the test's two, as they change: an earlier case's launch may still hold its own for a while.
        val ours = setOf(first.id, second.id)
        val seen = CopyOnWriteArrayList<Set<String>>()
        CoroutineScope(Dispatchers.Unconfined).also { watching += it }.launch { container.downloads.held.collect { seen += it intersect ours } }
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, checkNotNull(DownloadLaunch.of(first)))).use { scenario ->
            waitFor("the first file playing", 30_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying } } }
            context.startActivity(PlayerActivity.intent(context, secondLaunch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val before = waitFor("the second file playing", 30_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying && playsUri(it) == secondLaunch.uri } } }
            assertEquals(setOf(second.id), container.downloads.held.value intersect ours)
            assertTrue("the held ids passed through none: $seen", seen.dropWhile { first.id !in it }.all { it.isNotEmpty() })

            // The first, let go of: removed, it goes once its Undo's time is over.
            container.downloads.remove(listOf(first.id), keepFor = UNDO_MILLIS)
            waitFor("the first gone at once", 30_000) { if (runBlocking { dao.get(first.id) } == null && !firstFile.exists()) Unit else null }
            // The second, held: removed, it waits for the player.
            container.downloads.remove(listOf(second.id), keepFor = UNDO_MILLIS)
            delay(UNDO_MILLIS + SETTLE_MILLIS)
            assertEquals(DownloadState.Deleting, dao.get(second.id)?.state)
            assertTrue("its file, while it plays", secondFile.isFile)

            // Launched again to the open player: its hold kept throughout, and it plays from its file again.
            val from = seen.size
            context.startActivity(PlayerActivity.intent(context, secondLaunch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("the second launched again playing", 30_000) { scenario.read { a -> a.player?.takeIf { it !== before && it.isPlaying && playsUri(it) == secondLaunch.uri } } }
            assertTrue("let go of as it was launched again: ${seen.drop(from)}", seen.drop(from).all { second.id in it })
            assertEquals(setOf(second.id), container.downloads.held.value intersect ours)
            assertEquals(DownloadState.Deleting, dao.get(second.id)?.state)
            assertTrue(secondFile.isFile)
        }
        waitFor("the second gone with its play", 30_000) { if (runBlocking { dao.get(second.id) } == null && !secondFile.exists()) Unit else null }
        assertEquals(emptySet<String>(), container.downloads.held.value intersect ours)
    }

    /** What [player] plays: its media item's URI. */
    private fun playsUri(player: Player): String? = player.currentMediaItem?.localConfiguration?.uri?.toString()

    /** Whether the app's own source, the player's, opens download [id]'s file and reads from it. */
    private fun readsThrough(id: String): Boolean {
        val source = container.dataSourceFactory.createDataSource()
        return try {
            source.open(DataSpec(Uri.parse(PlayUris.download(id))))
            source.read(ByteArray(4096), 0, 4096) > 0
        } catch (e: java.io.IOException) {
            false
        } finally {
            runCatching { source.close() }
        }
    }

    /** [row] as a finished download is left: chapters.mkv written as its file, its gaps as zeros, renamed done, then its row, Done, with its marker. */
    private suspend fun seed(row: Download): Download {
        val bytes = DownloadFixtures.media(MEDIA)
        row.gaps.forEach { gap -> bytes.fill(0, gap.first.toInt(), gap.last.toInt() + 1) }
        val name = checkNotNull(row.fileName)
        storage.open(row.id, name, bytes.size.toLong()).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        storage.finish(row.id, name)
        val size = bytes.size.toLong()
        val release = checkNotNull(row.release).copy(fileSize = size)
        val done = row.copy(release = release, size = size, indexerSize = size, safeOffset = size, relativePath = storage.relativePath(row.id, name))
        made += done
        storage.add(done)
        // The hooks and the player ask the runner's map, which follows the library.
        waitFor("download ${done.id} among the finished ones") { container.downloads.done.value[done.watchKey]?.takeIf { it.id == done.id } }
        return done
    }

    /** An invented show, as the fake indexer knows Harbor Lights' or as nothing does. */
    private class Show(val id: Int, val name: String, val ids: TvIds)

    private fun episode(show: Show, number: Int, gaps: List<LongRange> = emptyList(), completedAt: Long = 2, release: RememberedRelease? = null): Download {
        val name = "${show.name.replace(' ', '.')}.S01E0$number.720p.WEB.h264-KESTREL.mkv"
        return Download(
            id = Download.newId(), watchKey = WatchKey.episode(show.id, 1, number), kind = DownloadKind.Episode, title = show.name,
            subtitle = "S01E0$number · Episode $number", showTmdbId = show.id, season = 1, episode = number, seasonEpisodes = EPISODES,
            episodeName = "Episode $number", state = DownloadState.Done, position = -1_000L + number,
            release = release ?: RememberedRelease(INDEXER, "dl-player-${show.id}-$number", name.removeSuffix(".mkv"), null, 0, "nzb-${show.id}-$number", name),
            fileName = name, gaps = gaps.map { DownloadGap(it.first, it.last) }, createdAt = 1, completedAt = completedAt, updatedAt = 2,
        )
    }

    /** Episode [number] as TMDB would hand it on: what the next-episode seam answers. */
    private fun play(show: Show, number: Int) = EpisodePlay(
        showTmdbId = show.id, showName = show.name, showYear = 2021, aliases = emptyList(), ids = show.ids, season = 1, episode = number,
        episodeName = "Episode $number", airDate = null, runtimeMinutes = 6, seasonEpisodes = EPISODES,
    )

    private fun show(show: Show) = ShowDetails(
        show.id, show.name, null, 2021, null, null, null, "Returning Series", true, 6, listOf(SeasonSummary(1, null, EPISODES, LocalDate.now().minusDays(60), null)),
        lastEpisode = EpisodeSummary(1, EPISODES, "Episode $EPISODES", LocalDate.now().minusDays(20)), nextEpisode = null,
        ids = ExternalIds(tvdb = show.ids.tvdb, imdb = show.ids.imdb),
    )

    private fun season(show: Show) =
        Season(show.id, 1, null, null, (1..EPISODES).map { Episode(1, it, "Episode $it", null, LocalDate.now().minusDays(60L - 7 * it), 6, null) })

    /** The fake indexer in place of the app's, and the mock on the engine, as a search and a stream of Harbor Lights need. */
    private suspend fun onTheMock() {
        fun reachable(port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", port), 2_000) } }.isSuccess
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", reachable(1119))
        assumeTrue("fake indexer not reachable at 10.0.2.2:8089 (mock-seed indexer)", reachable(8089))
        engineSetup("the mock on the engine") { mockOnEngine() }
        val off = container.settings.indexers.first().filter { it.enabled && it.id != FAKE_INDEXER }.map { it.id }
        switchedOff = off
        off.forEach { container.settings.setIndexerEnabled(it, false) }
        torrentOff = container.settings.torrentIndexers.first().filter { it.enabled }.map { it.id }
        torrentOff.forEach { container.settings.setTorrentIndexerEnabled(it, false) }
        servicesOff = container.settings.services.first().filter { it.enabled }.map { it.id }
        servicesOff.forEach { container.settings.setServiceEnabled(it, false) }
        container.settings.saveIndexer(FAKE_INDEXER, "Fake (download player test)", "http://10.0.2.2:8089", "fake-indexer-key")
    }

    /** The releases the play sheet tries from now on, in turn, as its states name them; gathered until the test ends. */
    private fun triedReleases(): List<String> {
        val tried = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(Dispatchers.Default).also { watching += it }
        scope.launch { container.playback.state.collect { s -> (s as? PlayState.Running)?.release?.takeIf { it != tried.lastOrNull() }?.let(tried::add) } }
        return tried
    }

    /** The coordinator's next launch for [watchKey], or a failure as soon as the play sheet shows one. */
    private suspend fun launchFor(watchKey: String): PlayerLaunch = withTimeout(120_000) {
        merge(
            container.playback.launches.filter { it.watchKey == watchKey },
            container.playback.state.filter { it is PlayState.Failed || it is PlayState.NoRelease }.map { throw AssertionError("$watchKey didn't start: $it") },
        ).first()
    }

    /** No network: Wi-Fi and mobile data off, until Android says none is validated. */
    private suspend fun offline() {
        if (dataWas == null) dataWas = shell("settings get global mobile_data").trim()
        shell("svc data disable")
        wifiOff = true
        shell("svc wifi disable")
        waitFor("no validated network", NETWORK_MILLIS) { if (!container.validatedNetwork.value) Unit else null }
    }

    /** Wi-Fi back on, until Android says it is validated. */
    private suspend fun online() {
        shell("svc wifi enable")
        waitFor("a validated network", BACK_MILLIS) { if (container.validatedNetwork.value) Unit else null }
    }

    /** Into the last ten seconds, where the card shows: [beforeEndMs] before the end. */
    private suspend fun seekNearTheEnd(scenario: ActivityScenario<PlayerActivity>, beforeEndMs: Long = 6_000) {
        val duration = waitFor("the player to know the duration", 30_000) { scenario.read { it.player?.duration?.takeIf { d -> d > 0 } } }
        scenario.onActivity { it.player!!.seekTo(duration - beforeEndMs) }
    }

    /** Where in [mkv] each Matroska cluster starts: its id, 1F 43 B6 75. */
    private fun clusters(mkv: ByteArray): List<Long> = (0..mkv.size - 4).filter { i ->
        mkv[i] == 0x1F.toByte() && mkv[i + 1] == 0x43.toByte() && mkv[i + 2] == 0xB6.toByte() && mkv[i + 3] == 0x75.toByte()
    }.map { it.toLong() }

    private fun composeRoots(activity: Activity) = views(activity.window.decorView).filterIsInstance<ViewRootForTest>().filter { it.view.isShown }

    private fun subtree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::subtree)

    private fun SemanticsNode.words(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    /** The next-episode card's words: every shown Compose view's but the top bar's Cast button's. */
    private fun cardWords(activity: Activity): List<String> =
        composeRoots(activity).map { subtree(it.semanticsOwner.unmergedRootSemanticsNode) }
            .filterNot { nodes -> nodes.any { it.config.getOrNull(CastButtonLook) != null } }.flatten().flatMap { it.words() }

    /** File details' rows by their label: the value and the note, as one node reads. */
    private fun detailRows(activity: Activity): Map<String, List<String>> {
        val pane = composeRoots(activity).flatMap { subtree(it.semanticsOwner.rootSemanticsNode) }
            .firstOrNull { it.config.getOrNull(SemanticsProperties.PaneTitle) == DETAILS } ?: return emptyMap()
        return subtree(pane).mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.map { it.text }?.takeIf { it.size >= 2 } }
            .associate { it.first() to it.drop(1) }
    }

    private fun button(activity: Activity, description: String) =
        views(activity.window.decorView).filterIsInstance<ImageButton>().single { it.contentDescription == description }

    private fun playerView(activity: Activity) = views(activity.window.decorView).filterIsInstance<PlayerView>().single()

    private fun validatedWifi(): Unit? = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        ?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }?.let { }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().decodeToString() }

    private companion object {
        /** An invented show nothing real or fake knows. */
        val QUIET = Show(900241, "Quiet Meridian", TvIds(tmdb = 900241))

        /** Harbor Lights as the fake indexer's catalogue numbers it (mock-seed); nothing real has these ids. */
        val HARBOR = Show(900002, "Harbor Lights", TvIds(tvdb = 900001, tmdb = 900002, imdb = "tt9000003"))
        const val FAKE_INDEXER = "download-player-device-test"
        const val INDEXER = "download-player-device-test-own"

        /** The fake indexer's whole release of Harbor Lights S01E01, as a download of it would have kept it. */
        val GOOD = RememberedRelease(FAKE_INDEXER, "hl-s01e01-good", "Harbor.Lights.S01E01.1080p.WEB-DL.AAC2.0.H.264-GOOD", null, 0, "nzb-hl-good", "Harbor.Lights.S01E01.mkv")

        /** The device tests' film with chapters: six minutes at 10 fps, 0.7 MB. */
        const val MEDIA = "chapters.mkv"
        const val EPISODES = 4
        /** About a second of the file, a fifth of a cluster, as a release's part is. */
        const val PART = 2_000

        /** A few seconds in front of chapters.mkv's 14th cluster, about a minute in. */
        const val GAP_START_MS = 55_000L
        const val DETAILS = "File details"

        const val SEEDING_MILLIS = 60_000L
        const val NETWORK_MILLIS = 30_000L
        const val BACK_MILLIS = 90_000L
        const val CARD_MILLIS = 30_000L
        const val LAUNCH_MILLIS = 15_000L
        /** How long the file played before, as its player saved it: chapters.mkv's six minutes. */
        const val PLAYED_MS = 360_000L
        /** How long the missing-parts prompt shows unanswered, in playback: past the test's answer. */
        const val PROMPT_MILLIS = 600_000L
        /** Where the player of a download whose file is gone was asked to start: what its stream starts from. */
        const val GONE_AT_MS = 120_000L
        /** How far a player that never played may stand from where it was asked to start. */
        const val PLACE_SLACK_MS = 2_000L
        /** Long enough for a key's hand-over to close the player. */
        const val KEY_MILLIS = 2_000L
        /** Where Previous is pressed: inside the file's first seconds, where it goes to the episode before when it can. */
        const val PREVIOUS_AT_MS = 1_500L
        /** How long a card the row stood in for is watched with the network back: several of its ticks. */
        const val STAND_IN_MILLIS = 3_000L
        /** A removal's Undo time, given short. */
        const val UNDO_MILLIS = 1_000L
        /** Long enough for the runner's timer and what it starts to have run. */
        const val SETTLE_MILLIS = 2_000L
        /** Far ahead in chapters.mkv's six minutes. */
        const val SEEK_MS = 200_000L
    }
}
