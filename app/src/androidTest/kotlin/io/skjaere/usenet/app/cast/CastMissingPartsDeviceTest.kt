package io.skjaere.usenet.app.cast

import android.os.SystemClock
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.media3.common.MediaItem
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.casting.CastFeed
import io.skjaere.usenet.app.casting.CastPlayerState
import io.skjaere.usenet.app.casting.CastTimings
import io.skjaere.usenet.app.casting.aMissingPart
import io.skjaere.usenet.app.casting.mayShowGlitches
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.NzbSource
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.RankedItem
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Missing parts during a cast (W37, L94, L104; CsPhoneMissingParts) in the app as MainActivity shows it, over a fake Cast
 * session and device player and a fake feed of the play there, its served gaps and how far the server wrote (L73): Google's
 * software is never started. Three gaps within ten minutes of film ask with the player's words and the cast's line, above
 * the Playing on bar on Home and over the picture on the remote (never both); Keep watching hides it; Try another release
 * keeps the device and opens the play sheet saying the release playing goes on there until the next is ready, at the place;
 * with no other release it is the count alone, gone by itself; one part is said for a moment in the remote's state line;
 * and a device stuck after a served gap is sent past it. The show, the device and the releases are invented.
 */
@RunWith(AndroidJUnit4::class)
class CastMissingPartsDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val home = HomeContentShown()
    private val context get() = compose.activity
    private val container get() = context.container
    private val routes = FakeRoutes()
    private val connector = FakeConnector()
    private val kitchen = castRoute("Kitchen display")
    private val device = CastTo(kitchen.id, kitchen.name)
    private val launcher = FakeLauncher()
    private val realSource = PlaybackCoordinator.nzbSource
    private lateinit var remote: FakeRemotePlayer

    /** This case's LOAD, its content id: each case's own, as each LOAD's is. */
    private val load = "cast-missing-load-${System.nanoTime()}"
    private val episode = WatchTarget(WatchKind.Episode, WatchTitle.episode(SHOW_TITLE, 2, 4), showTmdbId = SHOW, season = 2, episode = 4)
    private val playing = SavedRelease(INDEXER, GUID, RELEASE, null, 0, "cast-missing-nzb", "harbor-lights.mkv", SIZE)

    @Before
    fun setUp() {
        home.show()
        assumeFalse("a TV never casts (tv.TvNoCastTest)", container.television)
        CastFakes.started(routes, connector, CopyOnWriteArrayList())
        CastSeams.launcher = { launcher }
        CastFakes.connected(kitchen, connector)
        remote = onMainThread { GoogleCast.connection.player(context) } as FakeRemotePlayer
        container.playOn.choose(PlayChoice.Cast(kitchen.id, kitchen.name))
        onMainThread {
            container.playback.withdraw()
            // The device has the play: its item there, as a LOAD leaves it.
            remote.setMediaItem(MediaItem.Builder().setMediaId(load).build())
        }
    }

    @After
    fun restore() {
        onMainThread { container.playback.cancel() }
        container.playOn.choose(PlayChoice.Phone)
        CastFakes.reset()
        PlaybackCoordinator.nzbSource = realSource
        home.restore()
    }

    /** A search's release [guid] titled [title], as a request's candidates are. */
    private fun candidate(guid: String, title: String) = RankedItem(
        NewznabItem(
            indexerId = INDEXER, guid = guid, title = title, sizeBytes = SIZE, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        ),
        RankedRelease(ReleaseFacts("$INDEXER/$guid", title, SIZE), ReleaseParser.parse(title), playable = true, score = 1.0, reasons = emptyList(), estimatedBitrate = null),
    )

    /** The episode cast from a request with another release to try (kept as the coordinator keeps a play's). */
    private fun episodeLaunch(): PlayerLaunch {
        val request = PlayRequest(
            SHOW_TITLE, EPISODE_LINE, listOf(candidate(GUID, RELEASE), candidate("hl-720p", "Harbor.Lights.S02E04.720p.WEB-DL.AAC2.0.H.264-GRP")),
            FilePicker.Largest, episode,
        )
        return PlayerLaunch(
            UsenetUris.build(playing.nzbId, 0, playing.fileName, playing.fileSize), SHOW_TITLE, EPISODE_LINE, WatchKey.episode(SHOW, 2, 4), episode, playing,
            requestId = container.playback.keep(request), cast = device,
        )
    }

    /** A film cast with nothing else to try: no request behind it, and no episode to search for. */
    private fun filmLaunch(): PlayerLaunch {
        val film = WatchTarget(WatchKind.Movie, "Copper Crown", movieTmdbId = FILM)
        return PlayerLaunch(UsenetUris.build(playing.nzbId, 0, playing.fileName, playing.fileSize), "Copper Crown", null, WatchKey.movie(FILM), film, playing, cast = device)
    }

    /** The play on the device: [state] at [positionMs], the server having sent [gaps] and gone on past them. */
    private fun device(launch: PlayerLaunch, state: CastPlayerState, positionMs: Long, gaps: List<LongRange> = emptyList(), reportedAt: Long = SystemClock.elapsedRealtime()) =
        onMainThread {
            Casting.follows(CastFeed(load, launch, state, positionMs, LENGTH, reported = positionMs, reportedAt = reportedAt, size = SIZE, gaps = gaps, passed = gaps))
        }

    /** A second of missing articles [atMs] into the film: a thousand bytes a millisecond. */
    private fun gapAt(atMs: Long) = atMs * 1_000 until (atMs + 1_000) * 1_000

    /** Three within ten minutes of film, behind 33:35. */
    private val three = listOf(gapAt(1_500_000), gapAt(1_800_000), gapAt(2_000_000))

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun count(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().size

    private fun waitFor(matcher: SemanticsMatcher, millis: Long = 10_000) = compose.waitUntil(millis) { exists(matcher) }

    private fun bounds(matcher: SemanticsMatcher): Rect = compose.onNode(matcher).fetchSemanticsNode().boundsInWindow

    private val heading = hasText("This release is missing parts (3 so far)")
    private val positionOn = "Position on Kitchen display"
    private val bar = hasText("$SHOW_TITLE · S02E04")
    private val tryAnother = hasText("Try another release") and hasClickAction()

    @Test
    fun threeGapsAskAboveTheBarOnHomeAndOverThePictureOnTheRemote() {
        device(episodeLaunch(), CastPlayerState.Paused, PLACE, three)
        waitFor(bar)
        waitFor(heading)
        assertEquals("one prompt", 1, count(heading))
        assertTrue(exists(hasText(mayShowGlitches(kitchen.name))))
        assertTrue(exists(hasText("Try another release? It starts where you are.")))
        assertTrue(exists(hasText("Keep watching") and hasClickAction()))
        assertEquals("read once as it comes", LiveRegionMode.Polite, compose.onNode(heading, useUnmergedTree = true).fetchSemanticsNode().let { node ->
            generateSequence(node) { it.parent }.firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.LiveRegion) }
        })
        // Above the bar, the bar on the tab bar: the page ends above them.
        val tabs = bounds(hasText("Home") and hasClickAction())
        val shownBar = bounds(bar)
        assertEquals("the bar on the tab bar", tabs.top, shownBar.bottom, 0.5f)
        assertTrue("the prompt above the bar", bounds(tryAnother).bottom <= shownBar.top + 0.5f)
        // The remote: over the picture, the title under it; no bar, so the prompt is there alone.
        compose.onNode(bar).performClick()
        waitFor(hasContentDescription(positionOn))
        compose.waitUntil(10_000) { !exists(bar) }
        waitFor(heading)
        assertEquals("one prompt", 1, count(heading))
        assertTrue("over the picture, above the title", bounds(tryAnother).bottom <= bounds(hasText(SHOW_TITLE)).top)
        // Keep watching hides it, here and on Home.
        compose.onNode(hasText("Keep watching") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { !exists(heading) }
        compose.onNode(hasContentDescription("Back") and hasClickAction()).performClick()
        waitFor(bar)
        assertFalse(exists(heading))
    }

    @Test
    fun tryAnotherReleaseKeepsTheDeviceAndSaysTheReleasePlayingGoesOnThere() {
        val held = CompletableDeferred<ByteArray>()
        PlaybackCoordinator.nzbSource = { _, _ -> NzbSource { held.await() } }
        val launch = episodeLaunch()
        device(launch, CastPlayerState.Paused, PLACE, three)
        waitFor(tryAnother)
        compose.onNode(tryAnother).performClick()
        compose.waitUntil(10_000) { container.playback.state.value is PlayState.Running }
        waitFor(hasText("The release playing goes on there until this one is ready, at 33:35."))
        assertTrue(exists(hasText("Casting to ${kitchen.name}")))
        assertEquals("the device's play started for it", listOf(device), launcher.started.map { it.first })
        assertTrue("nothing loaded yet: the one playing goes on", launcher.loads.isEmpty())
        assertFalse(exists(heading))
    }

    @Test
    fun withNoOtherReleaseItIsTheCountAloneGoneByItself() {
        device(filmLaunch(), CastPlayerState.Playing, PLACE, three)
        waitFor(heading)
        assertTrue(exists(hasText(mayShowGlitches(kitchen.name))))
        assertFalse("no other release to offer", exists(tryAnother))
        assertFalse(exists(hasText("Keep watching")))
        // 5 s of playing.
        compose.waitUntil(15_000) { !exists(heading) }
    }

    @Test
    fun onePartIsSaidForAMomentInTheRemotesStateLine() {
        val launch = episodeLaunch()
        device(launch, CastPlayerState.Playing, PLACE)
        waitFor(bar)
        compose.onNode(bar).performClick()
        waitFor(hasText("Playing on ${kitchen.name}"))
        // A part missing 1.5 s on from where it plays now: said as the play gets there, in the state line's place.
        val now = container.remotePlaying.let { it.position(it.now.value ?: throw AssertionError("no play")) }
        device(launch, CastPlayerState.Playing, PLACE, listOf(gapAt(now + 1_500)))
        val notice = hasText(aMissingPart(kitchen.name), substring = true)
        waitFor(notice)
        assertFalse("in the state line's place", exists(hasText("Playing on ${kitchen.name}")))
        compose.waitUntil(10_000) { !exists(notice) }
        waitFor(hasText("Playing on ${kitchen.name}"))
    }

    @Test
    fun aDeviceStuckAfterAServedGapIsSentPastIt() {
        CastTimings.current = CastTimings(gapStuckMillis = 2_000)
        val launch = episodeLaunch()
        val gap = gapAt(2_000_000)
        device(launch, CastPlayerState.Playing, 1_990_000, listOf(gap))
        waitFor(bar)
        // At the gap, buffering, the server long past it.
        device(launch, CastPlayerState.Buffering, 1_999_600, listOf(gap))
        compose.waitUntil(10_000) { remote.commands.isNotEmpty() }
        assertEquals("past the gap's end and 2 s more, never back", listOf("seek to 2003000"), remote.commands.toList())
    }

    private companion object {
        /** An invented show's and film's TMDB ids; nothing real has them. */
        const val SHOW = 991_631
        const val FILM = 991_632
        const val SHOW_TITLE = "Harbor Lights"
        const val EPISODE_LINE = "S02E04 · Low Water"
        const val INDEXER = "cast-missing-parts-device-test"
        const val GUID = "hl-1080p"
        const val RELEASE = "Harbor.Lights.S02E04.1080p.WEB-DL.H.264-CAST"

        /** 52 minutes in a file of a thousand bytes a millisecond. */
        const val LENGTH = 3_120_000L
        const val SIZE = 3_120_000_000L

        /** 33:35, where the boards' prompt is answered. */
        const val PLACE = 2_015_000L
    }
}
