package io.skjaere.usenet.app

import android.app.Activity
import android.content.Intent
import android.graphics.RectF
import android.media.MediaCodecList
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.debrid.DebridFakes
import io.skjaere.usenet.app.debrid.DebridSearchFixture
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.FileDetailsSection
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.core.PrepareOutcome
import io.skjaere.usenet.core.UsenetError
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.core.formatSize
import io.skjaere.usenet.data.EpisodeRef
import io.skjaere.usenet.data.MovieMark
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.logging.LogBuffer
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.player.UsenetDataSourceException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch

/**
 * File details in the player end to end on a phone, read from memory (PlayerActivity.dataSources) without the network:
 * copies of core:player's fixtures (see the README beside them there), each a second of 160x90 video, played over and
 * over as an invented film's release. The ⓘ button at the end of the top bar opens the panel with what the file is and
 * what this device does with it: its decoder as MediaCodecList lists it, its display as the platform says it. The
 * controls stay down and playback goes on while it shows; Back closes it, the focus back on ⓘ. A remote reaches ⓘ one
 * Right from Audio and subtitles, and goes through the panel a section at a time. After "No video this device can play"
 * the "Playback stopped" panel's File details show the video that couldn't play. The system bars stay hidden. Only the
 * NZBs opened as files need the mock NNTP server (tracks.nzb, as TrackSheetDeviceTest has it), and a debrid play's Source
 * the fakes (DebridSearchFixture).
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class FileDetailsDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realLookUp = PlayerActivity.lookUpSegments
    private val realNext = PlayerActivity.nextEpisode
    private val realPrevious = PlayerActivity.previousEpisode
    private val realDataSources = PlayerActivity.dataSources
    private val realRead = PlayerActivity.readPlayback
    private val film = WatchTarget(WatchKind.Movie, TITLE, movieTmdbId = FILM)

    /** Indexers a test switched off, so the next-episode card's search asks none; switched back on after it. */
    private var switchedOff = emptyList<String>()

    /** Holds a play of a file no fixture is for: its tracks never come, until the test ends. */
    private val held = CountDownLatch(1)

    /** The fixtures' bytes, by the NZB id their plays' URIs name. */
    private val files = mapOf(HEVC to "hevc-level62.mkv", AV1 to "av1-10bit.mkv", RATE to "rate-23976.mkv", DOLBY_VISION to "dv-p7.mkv")
        .mapValues { (_, name) -> instrumentation.context.assets.open(name).use { it.readBytes() } }

    /**
     * Each play's file from memory, by its URI, played over and over from its first byte ([playOverAndOver]): a second
     * long, it would end at once, and the player leaves at a play's end. A file without one held until the test ends.
     */
    private val fixtures = DataSource.Factory {
        object : DataSource {
            private var source: DataSource? = null

            override fun addTransferListener(transferListener: TransferListener) = Unit

            override fun open(dataSpec: DataSpec): Long {
                val nzbId = UsenetUris.parse(dataSpec.uri.toString())?.nzbId
                // Fails at once, and for good, as a damaged first article does.
                if (nzbId == BROKEN) {
                    throw UsenetDataSourceException(UsenetError.Corrupt("invented"), IOException("invented"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
                }
                val bytes = files[nzbId] ?: try {
                    held.await()
                    throw InterruptedIOException("no fixture for ${dataSpec.uri}")
                } catch (e: InterruptedException) {
                    throw InterruptedIOException("closed")
                }
                playOverAndOver()
                return ByteArrayDataSource(bytes).also { source = it }.open(dataSpec)
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = source!!.read(buffer, offset, length)

            override fun getUri(): Uri? = source?.uri

            override fun close() {
                source?.close()
                source = null
            }
        }
    }

    @Before
    fun setUp() {
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        PlayerActivity.nextEpisode = { _, _ -> null }
        PlayerActivity.previousEpisode = { _, _ -> null }
        PlayerActivity.dataSources = { fixtures }
    }

    @After
    fun restore() {
        held.countDown()
        PlayerActivity.lookUpSegments = realLookUp
        PlayerActivity.nextEpisode = realNext
        PlayerActivity.previousEpisode = realPrevious
        PlayerActivity.dataSources = realDataSources
        PlayerActivity.readPlayback = realRead
        instrumentation.setInTouchMode(true)
        container.playback.cancel()
        runBlocking {
            switchedOff.forEach { container.settings.setIndexerEnabled(it, true) }
            // Out of Up next and unwatched, once the player's last save is in.
            delay(500)
            container.library.recordProgress(WatchState(WatchKey.movie(FILM), WatchKind.Movie, movieTmdbId = FILM, title = TITLE, updatedAt = 0), 0, 1_000)
            container.library.markMovies(listOf(MovieMark(FILM, TITLE)), watched = false)
            val episode = WatchState(
                WatchKey.episode(SHOW, 1, 1), WatchKind.Episode, showTmdbId = SHOW, season = 1, episode = 1, title = "$SHOW_NAME S01E01", updatedAt = 0,
            )
            container.library.recordProgress(episode, 0, 1_000)
            container.library.markEpisodes(listOf(EpisodeRef(SHOW, 1, 1, episode.title)), watched = false)
        }
    }

    /** The film played by [release], its file [name] (a fixture's, by [nzbId]) of its real size. */
    private fun launch(nzbId: String, release: String, name: String = "$release.mkv"): PlayerLaunch {
        val size = files[nzbId]?.size?.toLong() ?: 1_000_000L
        val saved = SavedRelease(INDEXER, release.lowercase(), release, null, 0, nzbId, name, size)
        return PlayerLaunch(
            uri = UsenetUris.build(nzbId, 0, name, size), title = TITLE, subtitle = "2026", watchKey = film.key(nzbId, 0), watch = film, release = saved,
        )
    }

    /** An invented show's first episode played by [release], the HEVC fixture, with its second offered next by the card. */
    private fun episodeLaunch(release: String = "Low.Tide.Inn.S01E01.1080p.WEB-DL.H.265-NZST"): PlayerLaunch {
        val size = files.getValue(HEVC).size.toLong()
        val saved = SavedRelease(INDEXER, release.lowercase(), release, null, 0, HEVC, "$release.mkv", size)
        return PlayerLaunch(
            uri = UsenetUris.build(HEVC, 0, "$release.mkv", size), title = SHOW_NAME, subtitle = "S01E01 · The First", watchKey = WatchKey.episode(SHOW, 1, 1),
            watch = WatchTarget(WatchKind.Episode, "$SHOW_NAME S01E01", showTmdbId = SHOW, season = 1, episode = 1), release = saved,
        )
    }

    private val secondEpisode = EpisodePlay(
        showTmdbId = SHOW, showName = SHOW_NAME, showYear = 2026, aliases = emptyList(), ids = TvIds(tmdb = SHOW), season = 1, episode = 2,
        episodeName = "The Second", airDate = null, runtimeMinutes = 45,
    )

    /**
     * The next-episode card for the episode playing, once [offered] (at once by default): its search asks no indexer, all
     * switched off, so nothing goes out, and nothing plays by itself.
     */
    private suspend fun offerTheNextEpisode(offered: CompletableDeferred<Unit> = CompletableDeferred(Unit)) {
        switchedOff = container.settings.indexers.first().filter { it.enabled }.map { it.id }
        switchedOff.forEach { container.settings.setIndexerEnabled(it, false) }
        PlayerActivity.readPlayback = { realRead(it).copy(autoPlayNext = false) }
        PlayerActivity.nextEpisode = { _, playing -> offered.await(); secondEpisode.takeIf { playing.showTmdbId == SHOW && playing.episode == 1 } }
    }

    private fun intent(launch: PlayerLaunch, tv: Boolean = false): Intent =
        PlayerActivity.intent(context, launch).apply { if (tv) putExtra(FormFactor.EXTRA, "tv") }

    @Test
    fun theButtonShowsTheFilesVideoAndWhatThisDevicePlaysItWith() = runBlocking<Unit> {
        val play = launch(HEVC, "Wren.Harbour.2026.1080p.WEB-DL.H.265-NZST")
        // The log is the process's: the line looked for is this player's.
        val before = LogBuffer.snapshot().maxOfOrNull { it.seq } ?: 0L
        ActivityScenario.launch<PlayerActivity>(intent(play)).use { scenario ->
            awaitTracks(scenario)
            openByTouch(scenario)
            val rows = waitFor("the decoder row") { scenario.read { a -> rows(a).takeIf { "Decoder" in it } } }
            val words = scenario.read(::paneWords)
            assertTrue(words.toString(), play.release.title in words && play.release.fileName in words)
            assertEquals(listOf("Matroska (MKV)"), rows["Container"])
            assertEquals(listOf(formatSize(files.getValue(HEVC).size.toLong())), rows["Size"])
            assertEquals(listOf("HEVC Main", "level 6.2"), rows["Format"])
            // The name says otherwise (W4): the file is what plays.
            assertEquals(listOf("160 × 90 · 90p · 16:9", "The name says 1080p"), rows["Resolution"])
            assertEquals(listOf("10 fps"), rows["Frame rate"])
            assertEquals(listOf("None"), rows["HDR"])
            assertEquals(listOf("An indexer since removed"), rows["Indexer"])
            assertDecoder(rows.getValue("Decoder"), MimeTypes.VIDEO_H265)
            assertDisplay(rows.getValue("Display"))
            // Nothing of the audio or subtitles: the sheet beside it has those.
            assertTrue(words.toString(), words.none { "audio" in it.lowercase() || "subtitle" in it.lowercase() })
            scenario.read { a ->
                // The controls stay down while it shows; playback goes on.
                assertFalse(playerView(a).isControllerFullyVisible)
                assertTrue(a.player!!.playWhenReady)
            }
            // One line in the log, of this device's facts only: never the release, its file, where it came from, or a
            // provider's name or host. Said by their place in the list, not their words, so a failure doesn't log a host.
            val line = waitFor("this player's video line in the log") {
                LogBuffer.snapshot().lastOrNull { it.seq > before && it.message.startsWith("Video: ") && rows.getValue("Decoder")[0] in it.message }
            }
            assertTrue(line.message, "output" in line.message && "display HDR" in line.message)
            // The ratio beside the codec, as File details show it beside the resolution (W11).
            assertTrue(line.message, "), 16:9, " in line.message)
            val names = listOf(play.release.title, play.release.fileName!!, play.release.indexerId, "Wren") + rows.getValue("Indexer") +
                container.engine.inUse.flatMap { listOf(it.name, it.host) }.filter { it.length >= 3 }
            names.forEachIndexed { i, name -> assertFalse("the log's video line has name ${i + 1} of the ${names.size} it mustn't", name in line.message) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor<Boolean>("the panel to close, the controls back") { scenario.read { a -> (pane(a) == null && playerView(a).isControllerFullyVisible).takeIf { it } } }
        }
    }

    /**
     * A debrid play's Source (L207, DbPhoneFileDetails), over the fakes ([DebridSearchFixture]): Harbor Lights S01E02 from the
     * release TorBox has cached names its torrent indexer, then "Service: TorBox" with its line where a Usenet play has its
     * providers, and nothing of the link, the torrent or the account.
     */
    @Test
    fun aDebridPlaysSourceNamesItsTorrentIndexerThenItsService() = runBlocking<Unit> {
        PlayerActivity.dataSources = realDataSources
        DebridSearchFixture().use { fixture ->
            fixture.open()
            fixture.usenetIndexerOn(false)
            onMainThread { container.playback.withdraw() }
            val episode = EpisodePlay(
                showTmdbId = DebridSearchFixture.HARBOR_LIGHTS, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(),
                ids = TvIds(tvdb = 900001, tmdb = DebridSearchFixture.HARBOR_LIGHTS, imdb = "tt9000003"), season = 1, episode = 2,
                episodeName = DebridSearchFixture.EPISODES[1], airDate = fixture.today.minusDays(53), runtimeMinutes = 45,
            )
            onMainThread { container.playback.playEpisode(episode) }
            val play = withTimeout(120_000) { container.playback.launches.first() }
            ActivityScenario.launch<PlayerActivity>(intent(play)).use { scenario ->
                awaitTracks(scenario, timeoutMs = 60_000)
                openByTouch(scenario)
                val rows = waitFor("the Service row") { scenario.read { a -> rows(a).takeIf { "Service" in it } } }
                assertEquals(listOf("Home Prowlarr"), rows["Indexer"])
                assertEquals(listOf("TorBox", "a cached torrent, streamed from the service"), rows["Service"])
                assertFalse("no providers: $rows", "Providers" in rows)
                val words = scenario.read(::paneWords)
                listOf(play.release.guid.removePrefix("btih:"), DebridFakes.TORBOX_ACCOUNT, "/cdn/", DebridFakes.HOST).forEach { secret ->
                    assertTrue("the panel shows a secret", words.none { secret in it })
                }
            }
        }
    }

    /** A 23.976 fps film, from Matroska's DefaultDuration, and a 10-bit AV1 one: each decoder said as MediaCodecList has it. */
    @Test
    fun aFilmsFrameRateAndEachDecoderAreSaidAsTheDeviceHasThem() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(intent(launch(RATE, "Wren.Harbour.2026.720p.BluRay.x264-NZST"))).use { scenario ->
            awaitTracks(scenario)
            openByTouch(scenario)
            val rows = waitFor("the decoder row") { scenario.read { a -> rows(a).takeIf { "Decoder" in it } } }
            assertEquals("H.264 High", rows.getValue("Format")[0])
            assertEquals(listOf("23.976 fps"), rows["Frame rate"])
            assertDecoder(rows.getValue("Decoder"), MimeTypes.VIDEO_H264)
        }
        ActivityScenario.launch<PlayerActivity>(intent(launch(AV1, "Wren.Harbour.2026.1080p.WEB-DL.10bit.AV1-NZST"))).use { scenario ->
            awaitTracks(scenario)
            openByTouch(scenario)
            val rows = waitFor("the decoder row") { scenario.read { a -> rows(a).takeIf { "Decoder" in it } } }
            assertEquals("AV1 Main 10-bit", rows.getValue("Format")[0])
            assertEquals(listOf("10-bit"), rows["Bit depth"])
            assertDecoder(rows.getValue("Decoder"), MimeTypes.VIDEO_AV1)
        }
    }

    /** A remote: Up from the controls to Audio and subtitles, Right to ⓘ, OK; Down a section at a time to the last, which keeps it; Back to ⓘ. */
    @Test
    fun onARemoteItIsOneRightFromAudioAndSubtitlesAndBackReturnsToIt() = runBlocking<Unit> {
        instrumentation.setInTouchMode(false)
        ActivityScenario.launch<PlayerActivity>(intent(launch(HEVC, "Wren.Harbour.2026.1080p.WEB-DL.H.265-NZST"))).use { scenario ->
            awaitTracks(scenario)
            scenario.onActivity { playerView(it).showController() }
            waitFor("the controls, play/pause focused") { scenario.read { a -> (playerView(a).isControllerFullyVisible && a.currentFocus?.id == androidx.media3.ui.R.id.exo_play_pause).takeIf { it } } }
            press(scenario, KeyEvent.KEYCODE_DPAD_UP, "Audio and subtitles") { a -> a.currentFocus === button(a, AUDIO) }
            press(scenario, KeyEvent.KEYCODE_DPAD_RIGHT, "File details") { a -> a.currentFocus === button(a, DETAILS) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("the panel, on its first section") { scenario.read { a -> focusedSection(a)?.takeIf { it == "Release" } } }
            listOf("File", "Video", "On this device", "Source").forEach { section ->
                press(scenario, KeyEvent.KEYCODE_DPAD_DOWN, section) { a -> focusedSection(a) == section }
            }
            // The last keeps the focus: it never leaves the panel for what is under it.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            delay(500)
            assertEquals("Source", scenario.read(::focusedSection))
            scenario.read { a -> assertFalse(playerView(a).isControllerFullyVisible) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor<Boolean>("the panel to close, ⓘ focused") { scenario.read { a -> (pane(a) == null && a.currentFocus === button(a, DETAILS)).takeIf { it } } }
        }
    }

    /**
     * ⓘ goes with the title and Audio and subtitles as the controls' buttons fade; held by a remote's focus it stays, and OK
     * opens the panel. The second-long file plays over and over ([fixtures]), never ended: media3 would keep the controls
     * up then.
     */
    @Test
    fun theButtonFadesWithTheControlsButStaysWhileARemoteHasIt() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(intent(launch(HEVC, "Wren.Harbour.2026.1080p.WEB-DL.H.265-NZST"))).use { scenario ->
            awaitTracks(scenario)
            // Long enough to see the controls fully up first in a loaded run (#269).
            scenario.onActivity { a ->
                playerView(a).controllerShowTimeoutMs = FADE_MS
                playerView(a).showController()
            }
            waitFor("the controls with ⓘ") { scenario.read { a -> (playerView(a).isControllerFullyVisible && button(a, DETAILS).isShown).takeIf { it } } }
            waitFor("the controls to fade to the time bar", timeoutMs = FADE_MS + 5_000L) {
                scenario.read { a -> (!playerView(a).isControllerFullyVisible && a.findViewById<View>(androidx.media3.ui.R.id.exo_progress).isShown).takeIf { it } }
            }
            scenario.read { a -> assertFalse("ⓘ outlived the controls' buttons", button(a, DETAILS).isShown) }
        }
        instrumentation.setInTouchMode(false)
        ActivityScenario.launch<PlayerActivity>(intent(launch(HEVC, "Wren.Harbour.2026.1080p.WEB-DL.H.265-NZST"))).use { scenario ->
            awaitTracks(scenario)
            scenario.onActivity { a ->
                playerView(a).controllerShowTimeoutMs = FADE_MS
                playerView(a).showController()
                assertTrue(button(a, DETAILS).requestFocus())
            }
            waitFor("the controls fully up") { scenario.read { a -> playerView(a).isControllerFullyVisible.takeIf { it } } }
            waitFor("the controls to fade with ⓘ still focused", timeoutMs = FADE_MS + 5_000L) {
                scenario.read { a -> (!playerView(a).isControllerFullyVisible && a.findViewById<View>(androidx.media3.ui.R.id.exo_progress).isShown).takeIf { it } }
            }
            scenario.read { a -> assertTrue(button(a, DETAILS).isShown && button(a, DETAILS).isFocused) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor<Boolean>("the panel") { scenario.read { a -> (pane(a) != null).takeIf { it } } }
        }
    }

    /**
     * A remote's Info opens File details whatever has the focus, and Info again gives the focus back to what had it: play/pause
     * with the controls up, and the next-episode card's Play now (which asks for the focus only once, as the card shows),
     * so OK then plays the next episode rather than pausing this one.
     */
    @Test
    fun infoGivesTheFocusBackToWhatHadItPlayNowToo() = runBlocking<Unit> {
        instrumentation.setInTouchMode(false)
        ActivityScenario.launch<PlayerActivity>(intent(launch(HEVC, "Wren.Harbour.2026.1080p.WEB-DL.H.265-NZST"))).use { scenario ->
            awaitTracks(scenario)
            scenario.onActivity { a ->
                playerView(a).controllerShowTimeoutMs = 0
                playerView(a).showController()
            }
            waitFor("the controls, play/pause focused") { scenario.read { a -> (playerView(a).isControllerFullyVisible && a.currentFocus?.id == androidx.media3.ui.R.id.exo_play_pause).takeIf { it } } }
            infoInfo(scenario)
            waitFor("play/pause focused again, the controls up") {
                scenario.read { a -> (playerView(a).isControllerFullyVisible && a.currentFocus?.id == androidx.media3.ui.R.id.exo_play_pause).takeIf { it } }
            }
        }
        offerTheNextEpisode()
        ActivityScenario.launch<PlayerActivity>(intent(episodeLaunch())).use { scenario ->
            waitFor("Play now focused") { scenario.read { a -> focusedWords(a).takeIf { "Play now" in it } } }
            scenario.read { a -> assertFalse(playerView(a).isControllerFullyVisible) }
            infoInfo(scenario)
            waitFor<List<String>>("Play now focused again, not ${scenario.read { it.currentFocus }}", timeoutMs = 3_000) { scenario.read { a -> focusedWords(a).takeIf { "Play now" in it } } }
        }
    }

    /** The next-episode card first showing under File details takes the focus as they close: it couldn't while they showed. */
    @Test
    fun aNextEpisodeCardShownUnderFileDetailsTakesTheFocusAsTheyClose() = runBlocking<Unit> {
        instrumentation.setInTouchMode(false)
        val offered = CompletableDeferred<Unit>()
        offerTheNextEpisode(offered)
        ActivityScenario.launch<PlayerActivity>(intent(episodeLaunch())).use { scenario ->
            awaitTracks(scenario)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_INFO)
            waitFor("File details") { scenario.read { a -> pane(a) } }
            offered.complete(Unit)
            waitFor("the card under them") { scenario.read { a -> allWords(a).takeIf { "Play now" in it } } }
            delay(1_000)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_INFO)
            waitFor<Boolean>("Play now focused, not ${scenario.read { it.currentFocus }}", timeoutMs = 3_000) { scenario.read { a -> (pane(a) == null && "Play now" in focusedWords(a)).takeIf { it } } }
        }
    }

    /** Info, File details open; Info, closed. */
    private suspend fun infoInfo(scenario: ActivityScenario<PlayerActivity>) {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_INFO)
        waitFor("File details after Info", timeoutMs = 3_000) { scenario.read { a -> pane(a) } }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_INFO)
        waitFor("File details to close after Info", timeoutMs = 3_000) { scenario.read { a -> (pane(a) == null).takeIf { it } } }
    }

    /**
     * The system bars stay hidden with File details open and after they close, as the player keeps them: read from memory,
     * so it runs without the mock NNTP server, which ImmersivePlayerDeviceTest's file needs.
     */
    @Test
    fun theSystemBarsStayHiddenWithThePanelOpenAndAfter() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(intent(launch(HEVC, "Wren.Harbour.2026.1080p.WEB-DL.H.265-NZST"))).use { scenario ->
            awaitTracks(scenario)
            waitFor("the bars to hide, not ${scenario.read(::barsShown)}") { scenario.read { a -> barsShown(a).isEmpty().takeIf { it } } }
            openByTouch(scenario)
            assertStayHidden(scenario, "with File details open")
            // Back reaches the player's window, nothing over it.
            waitFor("the player's window to have the focus", timeoutMs = 3_000) { scenario.read { a -> a.hasWindowFocus().takeIf { it } } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("File details to close") { scenario.read { a -> (pane(a) == null).takeIf { it } } }
            assertStayHidden(scenario, "after File details closed")
        }
    }

    /** The system bars that show, as the window's insets say. */
    private fun barsShown(a: Activity): List<String> {
        val insets = ViewCompat.getRootWindowInsets(a.window.decorView) ?: return listOf("no insets yet")
        return listOf(WindowInsetsCompat.Type.statusBars() to "status bar", WindowInsetsCompat.Type.navigationBars() to "navigation bar")
            .filter { (type, _) -> insets.isVisible(type) }.map { it.second }
    }

    /** None of the bars shows for a second, read every 100 ms. */
    private suspend fun assertStayHidden(scenario: ActivityScenario<PlayerActivity>, during: String) {
        repeat(10) {
            val shown = scenario.read(::barsShown)
            assertTrue("the $shown shows $during", shown.isEmpty())
            delay(100)
        }
    }

    /** For TalkBack the panel is a pane over what it covers, all of which comes back as it closes. */
    @Test
    fun forTalkBackThePanelIsAPaneOverWhatIsUnderIt() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(intent(launch(HEVC, "Wren.Harbour.2026.1080p.WEB-DL.H.265-NZST"))).use { scenario ->
            awaitTracks(scenario)
            val before = scenario.read { a -> under(a).map { it.importantForAccessibility } }
            // The picture, the card, the skip button, the missing-parts prompt, the slow-stream banner and "Playback stopped".
            assertEquals(6, before.size)
            openByTouch(scenario)
            scenario.read { a ->
                assertEquals(listOf("File details"), paneTitles(a))
                under(a).forEach { v -> assertEquals("$v", View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, v.importantForAccessibility) }
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("the panel to close") { scenario.read { a -> (pane(a) == null).takeIf { it } } }
            assertEquals(before, scenario.read { a -> under(a).map { it.importantForAccessibility } })
        }
    }

    /**
     * TalkBack keeps a phone in touch mode, where the top bar's buttons take no input focus: closing File details or the
     * audio and subtitles sheet still gives TalkBack's focus back to the button that opened them (S7-7). Without TalkBack
     * running here, the buttons' accessibility delegates see it asked for.
     */
    @Test
    fun inTouchModeClosingGivesTalkBacksFocusBackToTheButton() = runBlocking<Unit> {
        instrumentation.setInTouchMode(true)
        ActivityScenario.launch<PlayerActivity>(intent(launch(HEVC, "Wren.Harbour.2026.1080p.WEB-DL.H.265-NZST"))).use { scenario ->
            awaitTracks(scenario)
            for (name in listOf(DETAILS, AUDIO)) {
                val asked = mutableListOf<Int>()
                scenario.onActivity { a ->
                    assertTrue("in touch mode", a.window.decorView.isInTouchMode)
                    button(a, name).accessibilityDelegate = object : View.AccessibilityDelegate() {
                        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                            asked += action
                            return super.performAccessibilityAction(host, action, args)
                        }
                    }
                    playerView(a).showController()
                    button(a, name).performClick()
                }
                waitFor("$name open") { scenario.read { a -> (name in paneTitles(a)).takeIf { it } } }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                waitFor("$name to close") { scenario.read { a -> (name !in paneTitles(a)).takeIf { it } } }
                scenario.read { a ->
                    assertFalse("$name took the input focus in touch mode", button(a, name).isFocused)
                    assertTrue("$name asked for actions $asked, not TalkBack's focus", AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS in asked)
                }
            }
        }
    }

    /** Until the player has read the file's tracks the release, the file and where it came from show, and the rest is being read. */
    @Test
    fun beforeTheTracksAreKnownTheVideoAndTheDeviceAreBeingRead() = runBlocking<Unit> {
        val play = launch(HELD, "Wren.Harbour.2026.2160p.WEB-DL.DV.HDR10.H.265-NZST")
        ActivityScenario.launch<PlayerActivity>(intent(play)).use { scenario ->
            waitFor("the player") { scenario.read { it.player } }
            openByTouch(scenario)
            scenario.read { a ->
                assertTrue(a.player!!.currentTracks.isEmpty)
                val words = paneWords(a)
                assertTrue(words.toString(), play.release.title in words)
                assertEquals(words.toString(), 2, words.count { it == "Reading the file…" })
                assertEquals(listOf("Matroska (MKV)"), rows(a)["Container"])
                assertTrue(words.toString(), "Format" !in words && "Decoder" !in words)
            }
        }
    }

    /**
     * "No video this device can play" (a Dolby Vision profile 7 file, NoVideoGuardDeviceTest's): the "Playback stopped"
     * panel's File details show the video that couldn't play, read before the play stopped, and Back returns a remote's
     * focus to that button.
     */
    @Test
    fun afterNoVideoThisDeviceCanPlayTheStoppedPanelShowsTheVideo() = runBlocking<Unit> {
        assumeFalse("this device has a Dolby Vision decoder", decodes(MimeTypes.VIDEO_DOLBY_VISION))
        instrumentation.setInTouchMode(false)
        ActivityScenario.launch<PlayerActivity>(intent(launch(DOLBY_VISION, "Wren.Harbour.2026.2160p.UHD.BluRay.REMUX.DV.HEVC-NZST"))).use { scenario ->
            waitFor("the stopped panel", timeoutMs = 30_000) { scenario.read { a -> allWords(a).takeIf { "Playback stopped" in it } } }
            // Close first, File details beside it.
            waitFor("Close focused") { scenario.read { a -> focusedWords(a).takeIf { "Close" in it } } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            waitFor("File details focused") { scenario.read { a -> focusedWords(a).takeIf { "File details" in it } } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            val rows = waitFor("the panel's video") { scenario.read { a -> rows(a).takeIf { "Format" in it } } }
            assertEquals("HEVC Main 10", rows.getValue("Format")[0])
            assertTrue(rows.toString(), rows.getValue("HDR").any { it.startsWith("Dolby Vision profile 7") })
            // No decoder took it.
            assertFalse(rows.toString(), "Decoder" in rows)
            scenario.read { a -> assertEquals(listOf("File details"), paneTitles(a).filter { it == "File details" }) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor<Boolean>("the panel to close, File details focused again") {
                scenario.read { a -> (pane(a) == null && "File details" in focusedWords(a) && "Playback stopped" in allWords(a)).takeIf { it } }
            }
        }
    }

    /**
     * A play that stops before the player has read the file's tracks: the "Playback stopped" panel's File details say
     * nothing is being read any more. The video, never read, is left out; the display, read as the panel opens, shows.
     */
    @Test
    fun aPlayStoppedBeforeTheTracksSaysNothingIsBeingRead() = runBlocking<Unit> {
        instrumentation.setInTouchMode(false)
        ActivityScenario.launch<PlayerActivity>(intent(launch(BROKEN, "Wren.Harbour.2026.1080p.WEB-DL.H.265-NZST"))).use { scenario ->
            waitFor("the stopped panel") { scenario.read { a -> allWords(a).takeIf { "Playback stopped" in it } } }
            waitFor("Close focused") { scenario.read { a -> focusedWords(a).takeIf { "Close" in it } } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            waitFor("File details focused") { scenario.read { a -> focusedWords(a).takeIf { "File details" in it } } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            val rows = waitFor("the panel's display row") { scenario.read { a -> rows(a).takeIf { "Display" in it } } }
            val words = scenario.read(::paneWords)
            assertFalse(words.toString(), "Reading the file…" in words)
            assertTrue(words.toString(), "ON THIS DEVICE" in words && "VIDEO" !in words && "Format" !in rows)
            assertDisplay(rows.getValue("Display"))
        }
    }

    /** A play started while one plays (onNewIntent) shows its own file, never the facts of the one before. */
    @Test
    fun aNewPlayNeverShowsTheFileBeforesFacts() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(intent(launch(HEVC, "Wren.Harbour.2026.1080p.WEB-DL.H.265-NZST"))).use { scenario ->
            awaitTracks(scenario)
            openByTouch(scenario)
            waitFor("the first file's video") { scenario.read { a -> rows(a)["Format"]?.takeIf { it[0] == "HEVC Main" } } }
            val second = launch(HELD, "Wren.Harbour.2026.2160p.WEB-DL.DV.HDR10.H.265-NZST")
            context.startActivity(intent(second).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("the second play") { scenario.read { a -> (a.intent.getStringExtra("launch")?.contains(HELD) == true).takeIf { it } } }
            // Closed with the play before; opened again, the second release, its file being read.
            scenario.read { a -> assertEquals(null, pane(a)) }
            openByTouch(scenario)
            scenario.read { a ->
                val words = paneWords(a)
                assertTrue(words.toString(), second.release.title in words && "Wren.Harbour.2026.1080p.WEB-DL.H.265-NZST" !in words)
                assertEquals(words.toString(), 2, words.count { it == "Reading the file…" })
                assertFalse(words.toString(), "HEVC Main" in words)
            }
        }
    }

    /** On a TV (the layout forced on the phone): every word and section of the panel inside the 48/27 dp safe area, section by section. */
    @Test
    fun onATvThePanelKeepsInsideTheSafeArea() = runBlocking<Unit> {
        instrumentation.setInTouchMode(false)
        ActivityScenario.launch<PlayerActivity>(intent(launch(HEVC, "Wren.Harbour.2026.1080p.WEB-DL.H.265-NZST"), tv = true)).use { scenario ->
            awaitTracks(scenario)
            scenario.onActivity { a ->
                playerView(a).showController()
                button(a, DETAILS).performClick()
            }
            waitFor("the panel, on its first section") { scenario.read { a -> focusedSection(a)?.takeIf { it == "Release" } } }
            val safe = scenario.read(::safeArea)
            listOf("Release", "File", "Video", "On this device", "Source").forEachIndexed { i, section ->
                if (i > 0) press(scenario, KeyEvent.KEYCODE_DPAD_DOWN, section) { a -> focusedSection(a) == section }
                delay(300)
                val shown = scenario.read(::paneShown)
                assertTrue("found only $shown", shown.isNotEmpty())
                shown.forEach { (what, r) -> assertTrue("$what $r is outside the safe area $safe on $section", safe.holds(r)) }
            }
        }
    }

    /**
     * Where it came from, with the mock NNTP server: the tracks.nzb fixture opened as an NZB file is, named by the NZB's
     * title, its file by the NZB's listing, and streamed from the mock provider with the connections its four allow.
     */
    @Test
    fun anNzbOpenedAsAFileSaysSoAndNamesTheProviderStreamingIt() = runBlocking<Unit> {
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 1119), 2_000) } }.isSuccess)
        PlayerActivity.dataSources = realDataSources
        val nzb = engineSetup("the mock on the engine, the tracks fixture prepared") {
            mockOnEngine()
            prepareFixture("tracks")
        }
        val file = nzb.defaultPick!!
        // As Import opens it: the file's URI and its name.
        val opened = Intent(context, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_URI, UsenetUris.of(nzb.id, file)).putExtra("title", file.name)
        ActivityScenario.launch<PlayerActivity>(opened).use { scenario ->
            awaitTracks(scenario, timeoutMs = 30_000)
            openByTouch(scenario)
            val rows = waitFor("the panel's rows") { scenario.read { a -> rows(a).takeIf { "Providers" in it } } }
            val words = scenario.read(::paneWords)
            assertTrue(words.toString(), "tracks" in words && file.name in words)
            assertEquals(listOf(formatSize(file.size)), rows["Size"])
            assertEquals(listOf("Opened from an NZB file"), rows["Indexer"])
            assertEquals(listOf("mock", "3 connections"), rows["Providers"])
        }
    }

    /**
     * An NZB opened as a file, the player recreated after its process was killed: the store has the NZB only on disk until
     * the player's stream prepares it again, after the panel's facts were first read. Its title still names the release.
     * A copy of tracks.nzb unlike any other (a comment added), so no earlier test left it in memory; the mock NNTP server
     * streams it.
     */
    @Test
    fun anNzbOpenedAsAFileIsNamedByItsTitleAlsoAfterTheProcessWasRecreated() = runBlocking<Unit> {
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 1119), 2_000) } }.isSuccess)
        PlayerActivity.dataSources = realDataSources
        val nzbBytes = instrumentation.context.assets.open("tracks.nzb").use { it.readBytes() }.decodeToString()
            .replace("</nzb>", "<!-- FileDetailsDeviceTest: a process recreated -->\n</nzb>").encodeToByteArray()
        val title = "Wren.Harbour.2026.Recreated.720p.WEB-DL.H.264-NZST"
        // Not put in the store: what a process before this one left is on disk only.
        val outcome = engineSetup("the mock on the engine, a copy of the tracks fixture prepared") {
            mockOnEngine()
            container.preparer.prepare(nzbBytes, title)
        }
        assumeTrue("the mock NNTP server lacks the fixture's articles ($outcome); see TrackSheetDeviceTest's KDoc", outcome is PrepareOutcome.Ready)
        val nzb = (outcome as PrepareOutcome.Ready).nzb
        assertEquals(null, container.store.get(nzb.id))
        // As a process before this one left it: its NZB and title on disk.
        val stored = listOf("nzb", "title").map { File(context.filesDir, "nzb/${nzb.id}.$it") }
        try {
            stored[0].parentFile!!.mkdirs()
            stored[0].writeBytes(nzbBytes)
            stored[1].writeText(title)
            val file = nzb.defaultPick!!
            val opened = Intent(context, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_URI, UsenetUris.of(nzb.id, file)).putExtra("title", file.name)
            ActivityScenario.launch<PlayerActivity>(opened).use { scenario ->
                awaitTracks(scenario, timeoutMs = 30_000)
                openByTouch(scenario)
                waitFor("the NZB's title, not ${scenario.read(::paneWords)}") { scenario.read { a -> paneWords(a).takeIf { title in it } } }
                assertTrue(file.name in scenario.read(::paneWords))
            }
        } finally {
            stored.forEach { it.delete() }
        }
    }

    // The player, as its views and semantics say.

    private suspend fun awaitTracks(scenario: ActivityScenario<PlayerActivity>, timeoutMs: Long = 20_000) {
        waitFor("the player to know the file's tracks", timeoutMs) { scenario.read { a -> a.player?.currentTracks?.takeUnless { it.isEmpty } } }
    }

    /** Opens File details by touch, from the controls' top bar. */
    private suspend fun openByTouch(scenario: ActivityScenario<PlayerActivity>) {
        scenario.onActivity { a ->
            playerView(a).showController()
            button(a, DETAILS).performClick()
        }
        waitFor("the panel") { scenario.read { a -> pane(a) } }
    }

    /** Its name, and its note's words matching what MediaCodecList says of the decoder: one of [mime]'s, in hardware or not. */
    private fun assertDecoder(row: List<String>, mime: String) {
        val info = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { !it.isEncoder && (it.name == row[0] || it.canonicalName == row[0]) }
        assertNotNull("${row[0]} isn't a decoder MediaCodecList lists", info)
        assertTrue("${row[0]} doesn't decode $mime", info!!.supportedTypes.any { it.equals(mime, ignoreCase = true) })
        assertEquals(if (info.isHardwareAccelerated) "hardware" else "software", row[1])
    }

    /** As the platform says of the display now: its output size, its mode's refresh rate, the HDR it shows. */
    private fun assertDisplay(row: List<String>) = assertEquals(platformDisplayRow(context), row)

    private fun decodes(mime: String) =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { c -> !c.isEncoder && c.supportedTypes.any { it.equals(mime, ignoreCase = true) } }

    private fun composeRoots(activity: Activity) = views(activity.window.decorView).filterIsInstance<ViewRootForTest>().filter { it.view.isShown }

    /** The File details pane, merged as TalkBack reads it; null while it is closed. */
    private fun pane(activity: Activity): SemanticsNode? =
        composeRoots(activity).flatMap { subtree(it.semanticsOwner.rootSemanticsNode) }.firstOrNull { it.config.getOrNull(SemanticsProperties.PaneTitle) == "File details" }

    /** Each row of the panel by its label: its value (or chips) and notes, as one node reads. */
    private fun rows(activity: Activity): Map<String, List<String>> =
        pane(activity)?.let(::subtree).orEmpty().mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.map { it.text }?.takeIf { it.size >= 2 } }
            .associate { it.first() to it.drop(1) }

    private fun paneWords(activity: Activity): List<String> =
        pane(activity)?.let(::subtree).orEmpty().flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    /** The section a remote's focus is on; null for none. */
    private fun focusedSection(activity: Activity): String? =
        composeRoots(activity).flatMap { subtree(it.semanticsOwner.unmergedRootSemanticsNode) }
            .firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true && it.config.contains(FileDetailsSection) }?.config?.get(FileDetailsSection)

    private fun allWords(activity: Activity): List<String> =
        composeRoots(activity).flatMap { subtree(it.semanticsOwner.unmergedRootSemanticsNode) }.flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    /** The words of what has a remote's focus in the Compose views. */
    private fun focusedWords(activity: Activity): List<String> =
        composeRoots(activity).flatMap { subtree(it.semanticsOwner.rootSemanticsNode) }.filter { it.config.getOrNull(SemanticsProperties.Focused) == true }
            .flatMap { n -> subtree(n).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } } }

    private fun paneTitles(activity: Activity): List<String> =
        composeRoots(activity).flatMap { subtree(it.semanticsOwner.unmergedRootSemanticsNode) }.mapNotNull { it.config.getOrNull(SemanticsProperties.PaneTitle) }

    /** What of the pane can be focused or read, and shows, with its bounds. */
    private fun paneShown(activity: Activity): List<Pair<String, RectF>> =
        composeRoots(activity).flatMap { subtree(it.semanticsOwner.unmergedRootSemanticsNode) }
            .firstOrNull { it.config.getOrNull(SemanticsProperties.PaneTitle) == "File details" }?.let(::subtree).orEmpty().drop(1)
            .filter { n -> n.config.contains(SemanticsProperties.Focused) || n.config.contains(SemanticsProperties.Text) }
            .map { n -> (n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text } ?: "a section") to n.boundsInWindow.let { RectF(it.left, it.top, it.right, it.bottom) } }
            // Scrolled out of the panel, it doesn't show.
            .filter { (_, r) -> r.width() > 0 && r.height() > 0 }

    /** The window but for 48 dp at the sides and 27 dp at the top and bottom. */
    private fun safeArea(a: Activity): RectF {
        val density = a.resources.displayMetrics.density
        val window = a.window.decorView
        return RectF(48 * density, 27 * density, window.width - 48 * density, window.height - 27 * density)
    }

    private fun RectF.holds(other: RectF) = other.left >= left - 1 && other.top >= top - 1 && other.right <= right + 1 && other.bottom <= bottom + 1

    /** The views the panel covers: the picture with the controls, and the overlays beside it (the panel fills the screen). */
    private fun under(activity: Activity): List<View> {
        val picture = playerView(activity)
        val root = picture.parent as ViewGroup
        val overlays = (0 until root.childCount).map(root::getChildAt).filterIsInstance<ComposeView>()
            .filter { it.layoutParams.width == ViewGroup.LayoutParams.WRAP_CONTENT }
        return listOf(picture) + overlays
    }

    private fun button(activity: Activity, description: String) =
        views(activity.window.decorView).filterIsInstance<ImageButton>().single { it.contentDescription == description }

    private fun playerView(activity: Activity) = views(activity.window.decorView).filterIsInstance<PlayerView>().single()

    private fun views(view: View): List<View> =
        listOf(view) + ((view as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())

    private fun subtree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::subtree)

    private suspend fun press(scenario: ActivityScenario<PlayerActivity>, key: Int, what: String, isIt: (PlayerActivity) -> Boolean) {
        instrumentation.sendKeyDownUpSync(key)
        waitFor("$what to have focus after ${KeyEvent.keyCodeToString(key)}, not ${scenario.read { a -> focusedSection(a) ?: a.currentFocus.toString() }}", timeoutMs = 3_000) {
            scenario.read { a -> isIt(a).takeIf { it } }
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

    private companion object {
        /** An invented film's TMDB id; nothing real has it. */
        const val FILM = 900033
        const val TITLE = "Wren Harbour"
        const val INDEXER = "file-details-device-test"
        /** The fixtures' NZB ids, hex as an NZB's id is. */
        const val HEVC = "f11e01"
        const val AV1 = "f11e02"
        const val RATE = "f11e03"
        const val DOLBY_VISION = "f11e04"
        /** No fixture: its play is held, its tracks never known. */
        const val HELD = "f11e0f"
        /** Its file fails to open, for good. */
        const val BROKEN = "f11e0e"
        /** An invented show's TMDB id and name; nothing real has them. */
        const val SHOW = 900035
        const val SHOW_NAME = "Low Tide Inn"
        const val AUDIO = "Audio and subtitles"
        const val DETAILS = "File details"
        /** The controls' timeout in the fading test, long enough to see them fully up first in a loaded run (#269). */
        const val FADE_MS = 3_000
    }
}
