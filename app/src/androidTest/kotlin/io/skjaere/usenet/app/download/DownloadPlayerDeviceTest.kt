package io.skjaere.usenet.app.download

import android.app.Activity
import android.app.NotificationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.widget.ImageButton
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
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
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.Prefetch
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

/**
 * L354, L377: the player's branches for a finished download's file, on this phone, end to end. A recorded gap is skipped
 * as it plays, with the notice, and no Cast button shows. Offline, the next-episode card for an episode not downloaded
 * says so, with Close alone. A downloaded next episode counts down and plays from its file through the coordinator's
 * hook, with no search. Online, the next episode not downloaded is the next even with a later one downloaded, and it is
 * searched for and streamed (the mock and the fake indexer). File details say where the file came from in the providers'
 * place. A file gone says so, with Stream instead offered only once the network is back, which streams the episode.
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
            assertTrue("\"${PlayerActivity.MISSING_PART_SKIPPED}\" never showed", notice)
            assertEquals(listOf(gap.first..gap.last), scenario.read { a -> a.jumps.map(PlayGaps.Jump::bytes) })
            // The top bar with the controls: its buttons, but no Cast (L377).
            scenario.onActivity { a -> playerView(a).showController() }
            waitFor("the top bar") { scenario.read { a -> button(a, DETAILS).takeIf { it.isShown } } }
            assertTrue("a Cast button shows", scenario.read { a -> overlayNodes(a).none { it.config.getOrNull(CastButtonLook) != null } })
        }
    }

    /**
     * Offline, with TMDB not answering: the next episode is the row's next in its season, not downloaded, and the card says
     * so with Close alone: no Play now, no countdown, no search, and the controls' Next plays nothing.
     */
    @Test
    fun offlineTheCardForANextEpisodeNotDownloadedSaysSoWithClose() = runBlocking<Unit> {
        val row = seed(episode(QUIET, 2))
        offline()
        PlayerActivity.prefetchNext = { _, _, _ -> prefetches.incrementAndGet(); MutableStateFlow(Prefetch.Searching) }
        // The card from the start: the file is six minutes long.
        PlayerActivity.timings = PlayerActivity.Timings(cardBeforeEndMs = 400_000)
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, checkNotNull(DownloadLaunch.of(row)))).use { scenario ->
            val card = waitFor("the next-episode card", CARD_MILLIS) { scenario.read { a -> cardWords(a).takeIf { w -> w.any { "S01E03" in it } } } }
            assertTrue(card.toString(), "Not downloaded · offline" in card && "Close" in card)
            assertFalse(card.toString(), "Play now" in card || "Stop" in card || "Next episode plays automatically" in card)
            assertFalse("the controls' Next plays it", scenario.read { a -> playerView(a).player?.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT) == true })
            scenario.onActivity { a -> clickOverlay(a, "Close") }
            waitFor("the card to go") { scenario.read { a -> cardWords(a).none { "S01E03" in it }.takeIf { it } } }
        }
        assertEquals("searches for the next episode", 0, prefetches.get())
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
     * download again.
     */
    @Test
    fun aFileGoneSaysSoAndStreamInsteadComesWithTheNetwork() = runBlocking<Unit> {
        onTheMock()
        tmdb = FakeTmdb(shows = mapOf(HARBOR.id to show(HARBOR)), seasons = { id, number -> if (id == HARBOR.id && number == 1) season(HARBOR) else null })
        val row = seed(episode(HARBOR, 1, release = GOOD))
        checkNotNull(storage.file(row)).delete()
        offline()
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, checkNotNull(DownloadLaunch.of(row)))).use { scenario ->
            val panel = waitFor("Playback stopped", 30_000) { scenario.read { a -> overlayWords(a).takeIf { "Playback stopped" in it } } }
            assertTrue(panel.toString(), "The downloaded file is gone" in panel && "Close" in panel)
            assertFalse(panel.toString(), "Stream instead" in panel || "Try another release" in panel)
            online()
            waitFor("Stream instead with the network back", 30_000) { scenario.read { a -> overlayWords(a).takeIf { "Stream instead" in it } } }
            scenario.onActivity { a -> clickOverlay(a, "Stream instead") }
            waitFor("the player to close", 30_000) { scenario.state.takeIf { it == Lifecycle.State.DESTROYED } }
        }
        val streamed = launchFor(row.watchKey)
        assertTrue("S01E01 plays from ${PlayUris.scheme(streamed.uri)}", PlayUris.parse(streamed.uri) is PlayUris.Target.Usenet)
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
    }
}
