package io.skjaere.usenet.app

import android.app.Activity
import android.media.MediaCodecList
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.RankedItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The player's guard against a black screen: a release whose video the player selects none of, as no decoder of the
 * device takes it, would play its sound alone. The probe catches most such files before playback; this is for where its
 * renderers judge otherwise than the player's. Here a remembered release reaches the player straight, its file `dv-p7.mkv` (a copy of core:player's
 * fixture of that name, see the README beside it there: Dolby Vision profile 7, which a device without a Dolby Vision
 * decoder can't play), served from memory: the player stops, its panel says why and offers Try another release, and
 * the release is no longer the one Resume chooses.
 *
 * The film is invented ("Kestrel Point", TMDB id 900024, which nothing real has); its library row is left at
 * position 0, out of Up next.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class NoVideoGuardDeviceTest {
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
    private val film = WatchTarget(WatchKind.Movie, "Kestrel Point", movieTmdbId = FILM)
    private val release = SavedRelease(INDEXER, DOLBY_VISION, "Kestrel.Point.2026.2160p.UHD.BluRay.REMUX.DV.HEVC-NZST", null, 0, NZB_ID)

    @Before
    fun setUp() {
        assumeFalse("this device has a Dolby Vision decoder", decodes(MimeTypes.VIDEO_DOLBY_VISION))
        val bytes = instrumentation.context.assets.open("dv-p7.mkv").use { it.readBytes() }
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        PlayerActivity.nextEpisode = { _, _ -> null }
        PlayerActivity.previousEpisode = { _, _ -> null }
        PlayerActivity.dataSources = { DataSource.Factory { ByteArrayDataSource(bytes) } }
        // As if it had played the film before: the one Resume would choose.
        runBlocking { container.library.recordProgress(template(), 120_000, DURATION_MS, release.toRemembered()) }
    }

    @After
    fun restore() {
        PlayerActivity.lookUpSegments = realLookUp
        PlayerActivity.nextEpisode = realNext
        PlayerActivity.previousEpisode = realPrevious
        PlayerActivity.dataSources = realDataSources
        container.playback.cancel()
        // Out of Up next.
        runBlocking { container.library.recordProgress(template(), 0, DURATION_MS) }
    }

    private fun template() = WatchState(WatchKey.movie(FILM), WatchKind.Movie, movieTmdbId = FILM, title = film.title, updatedAt = 0)

    private fun candidate(guid: String, title: String) = RankedItem(
        NewznabItem(
            indexerId = INDEXER, guid = guid, title = title, sizeBytes = 690_000, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        ),
        RankedRelease(ReleaseFacts("$INDEXER/$guid", title, 690_000), ReleaseParser.parse(title), playable = true, score = 1.0, reasons = emptyList(), estimatedBitrate = null),
    )

    /** The release's launch, as the coordinator would have made it for a request of it and another release. */
    private fun launch(): PlayerLaunch {
        val request = PlayRequest(
            film.title, "2026", listOf(candidate(DOLBY_VISION, release.title), candidate("kestrel-point-sdr", "Kestrel.Point.2026.1080p.WEB.h264-NZST")),
            FilePicker.Largest, film,
        )
        return PlayerLaunch(
            uri = UsenetUris.build(NZB_ID, release.fileIndex), title = request.title, subtitle = request.subtitle,
            watchKey = film.key(NZB_ID, release.fileIndex), watch = film, release = release,
            requestId = container.playback.keep(request), startPositionMs = 0,
        )
    }

    @Test
    fun videoNoDecoderTakesStopsThePlayWithTheReleaseAtFault() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, launch())).use { scenario ->
            waitFor("the panel", timeoutMs = 30_000) { scenario.read { a -> overlayWords(a).takeIf { "Playback stopped" in it } } }
            val words = scenario.read(::overlayWords)
            assertTrue(words.toString(), "This device can't play this release's video (90p Dolby Vision profile 7)." in words)
            assertTrue(words.toString(), "Try another release" in words && "Close" in words)
            // Its sound doesn't play on under the panel.
            assertEquals(Player.STATE_IDLE, scenario.read { it.player?.playbackState })
        }
        // The release's fault: Resume no longer chooses it.
        withTimeout(5_000) { while (container.library.lastRelease(WatchKey.movie(FILM))?.guid == DOLBY_VISION) delay(100) }
    }

    private fun decodes(mime: String) =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { c -> !c.isEncoder && c.supportedTypes.any { it.equals(mime, ignoreCase = true) } }

    private fun overlayWords(activity: Activity): List<String> =
        views(activity.window.decorView).filterIsInstance<ViewRootForTest>().filter { it.view.isShown }
            .flatMap { subtree(it.semanticsOwner.unmergedRootSemanticsNode) }
            .flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun subtree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::subtree)

    private fun views(view: View): List<View> =
        listOf(view) + ((view as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())

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
        const val FILM = 900024
        const val INDEXER = "no-video-guard-device-test"
        const val DOLBY_VISION = "kestrel-point-dv"
        /** Hex, as an NZB's id is. */
        const val NZB_ID = "0dd5e7"
        const val DURATION_MS = 6_000_000L
    }
}
