package io.skjaere.usenet.app.cast

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.casting.CastEnd
import io.skjaere.usenet.app.casting.CastFeed
import io.skjaere.usenet.app.casting.CastPlayerState
import io.skjaere.usenet.app.casting.stopCastingTo
import io.skjaere.usenet.app.collection.ShowScreen
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.link.OPEN_THE_REMOTE
import io.skjaere.usenet.app.link.PUT_AWAY
import io.skjaere.usenet.app.link.loadingOn
import io.skjaere.usenet.app.link.onTvAt
import io.skjaere.usenet.app.link.onTvLine
import io.skjaere.usenet.app.link.pauseOn
import io.skjaere.usenet.app.link.playOnTv
import io.skjaere.usenet.app.link.playbackStoppedOn
import io.skjaere.usenet.app.link.reconnectingTo
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.PlayMiniBar
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Playing on bar and the Show page while a Cast device plays (L91, L95; CsPhoneBar, CsPhoneTalkBack 9-11) on a real
 * device, over a fake Cast session and device player and a fake feed of the play there (L73): Google's software is never
 * started. The bar says what the device does, with Google's Cast icon and the device named; its pause and play go to the
 * device's player once each, and its Stop casting ends the cast and the session, the bar going with it; loading, its
 * position stands still; reconnecting, its buttons wait; after an error there, Stop casting alone is left; it has no ×.
 * TalkBack reads it as one stop with its two buttons after; the Show page's button and the episode's row say Kitchen
 * display plays it, each opening the remote. The device, the show and the play are invented.
 */
@RunWith(AndroidJUnit4::class)
class CastBarDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val routes = FakeRoutes()
    private val connector = FakeConnector()
    private val kitchen = castRoute("Kitchen display")
    private val opened = AtomicInteger()
    private lateinit var remote: FakeRemotePlayer

    /** This case's LOAD, its content id: each case's own, as each LOAD's is. */
    private val load = "cast-bar-load-${System.nanoTime()}"
    private var tmdb: FakeTmdb? = null

    private val episode = WatchTarget(WatchKind.Episode, WatchTitle.episode("Harbor Lights", 2, 4), showTmdbId = SHOW, season = 2, episode = 4)
    private val release = SavedRelease("cast-bar-device-test", RELEASE, RELEASE, null, 0, "cast-bar-nzb", "harbor-lights.mkv", 2_000_000_000)
    private val launch = PlayerLaunch(
        UsenetUris.build(release.nzbId, 0, release.fileName, release.fileSize), "Harbor Lights", "S02E04 · Low Water", WatchKey.episode(SHOW, 2, 4), episode, release,
        cast = CastTo(kitchen.id, kitchen.name),
    )

    private val show = ShowDetails(
        SHOW, "Harbor Lights", null, 2021, null, null, null, "Ended", false, 52,
        listOf(SeasonSummary(2, null, 6, LocalDate.of(2022, 3, 1), null)),
        lastEpisode = EpisodeSummary(2, 6, "Episode 6", LocalDate.of(2022, 4, 5)), nextEpisode = null, ids = ExternalIds(tvdb = 991_602),
    )

    private val season = Season(SHOW, 2, null, null, (1..6).map { Episode(2, it, "Episode $it", null, LocalDate.of(2022, 3, 1).plusWeeks(it - 1L), 52, null) })

    @Before
    fun setUp() {
        assumeFalse("a TV never casts (tv.TvNoCastTest)", container.television)
        CastFakes.started(routes, connector, CopyOnWriteArrayList())
        CastFakes.connected(kitchen, connector)
        remote = onMainThread { GoogleCast.connection.player(context) } as FakeRemotePlayer
        // The device has the play: its item there, as a LOAD leaves it.
        onMainThread {
            container.playback.withdraw()
            remote.setMediaItem(MediaItem.Builder().setMediaId(load).build())
        }
    }

    @After
    fun restore() {
        CastFakes.reset()
        tmdb?.close()
    }

    private fun bar() = compose.setContent {
        AppTheme {
            Column { PlayMiniBar(container.playback, tv = container.tvStart, playing = container.remotePlaying, onRemote = { opened.incrementAndGet() }) }
        }
    }

    /** The play on the device as the phone follows it: [state] at [positionMs] of 52 minutes, and how it ended, if it has. */
    private fun device(state: CastPlayerState, positionMs: Long = PAUSED_AT, end: CastEnd? = null) =
        onMainThread { Casting.follows(CastFeed(load, launch, state, positionMs, LENGTH, end = end)) }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, millis: Long = 10_000) = compose.waitUntil(millis) { exists(matcher) }

    private fun tap(matcher: SemanticsMatcher) {
        waitFor(matcher)
        compose.onNode(matcher).performClick()
    }

    /** The device's player's commands, once they stop coming. */
    private fun commands(): List<String> {
        Thread.sleep(500)
        return remote.commands.toList()
    }

    @Test
    fun eachButtonActsOnceAndStopCastingEndsTheCastAndTheBar() {
        bar()
        device(CastPlayerState.Playing)
        waitFor(hasText("Harbor Lights · S02E04"))
        assertTrue(exists(hasText("Playing on ${kitchen.name}")))
        assertFalse("no × for a cast", exists(hasContentDescription(PUT_AWAY)))
        tap(hasContentDescription(pauseOn(kitchen.name)) and hasClickAction())
        compose.waitUntil(5_000) { remote.commands.isNotEmpty() }
        assertEquals(listOf("pause"), commands())
        device(CastPlayerState.Paused)
        waitFor(hasText("Paused on ${kitchen.name}"))
        tap(hasContentDescription(playOnTv(kitchen.name)) and hasClickAction())
        compose.waitUntil(5_000) { remote.commands.size == 2 }
        assertEquals(listOf("pause", "play"), commands())
        // A tap anywhere but its buttons opens the remote.
        compose.onNode(hasText("Harbor Lights · S02E04", substring = true)).performClick()
        compose.waitUntil(5_000) { opened.get() == 1 }
        tap(hasContentDescription(stopCastingTo(kitchen.name)) and hasClickAction())
        compose.waitUntil(5_000) { connector.ends.get() == 1 }
        compose.waitUntil(5_000) { !exists(hasText("Harbor Lights · S02E04")) }
        assertEquals("the cast and its session ended once", 1, connector.ends.get())
        assertEquals(listOf("pause", "play"), commands())
    }

    @Test
    fun loadingTheLineStandsStillAndReconnectingTheButtonsWait() {
        bar()
        device(CastPlayerState.Loading)
        waitFor(hasText(loadingOn(kitchen.name)))
        compose.onNode(hasContentDescription(pauseOn(kitchen.name))).assertIsEnabled()
        val loading = "Harbor Lights, season 2, episode 4. ${loadingOn(kitchen.name)}, 32 minutes 10 seconds of 52 minutes"
        waitFor(hasContentDescription(loading))
        Thread.sleep(1_500)
        assertTrue("the position stands still while it loads", exists(hasContentDescription(loading)))
        // The phone's Wi-Fi gone a moment: the session suspended.
        connector.suspended(kitchen)
        waitFor(hasText(reconnectingTo(kitchen.name)))
        compose.onNode(hasContentDescription(pauseOn(kitchen.name))).assertIsNotEnabled()
        compose.onNode(hasContentDescription(stopCastingTo(kitchen.name))).assertIsNotEnabled()
        connector.resumed(kitchen)
        device(CastPlayerState.Playing)
        waitFor(hasText("Playing on ${kitchen.name}"))
        compose.onNode(hasContentDescription(pauseOn(kitchen.name))).assertIsEnabled()
        compose.onNode(hasContentDescription(stopCastingTo(kitchen.name))).assertIsEnabled()
        assertEquals(emptyList<String>(), commands())
    }

    @Test
    fun afterAnErrorThereStopCastingIsAloneLeft() {
        bar()
        device(CastPlayerState.Playing)
        waitFor(hasText("Playing on ${kitchen.name}"))
        // The play ended there with an error: the service has stopped, the session lives on.
        device(CastPlayerState.Idle, end = CastEnd(CastEnd.How.Failed, PAUSED_AT))
        waitFor(hasText(playbackStoppedOn(kitchen.name)))
        compose.onNode(hasContentDescription(pauseOn(kitchen.name))).assertIsNotEnabled()
        tap(hasContentDescription(stopCastingTo(kitchen.name)) and hasClickAction())
        compose.waitUntil(5_000) { connector.ends.get() == 1 }
        compose.waitUntil(5_000) { !exists(hasText(playbackStoppedOn(kitchen.name))) }
    }

    @Test
    fun talkBackReadsTheBarAsOneStopWithItsTwoButtonsAfterIt() {
        bar()
        device(CastPlayerState.Paused)
        val spoken = "Harbor Lights, season 2, episode 4. Paused on ${kitchen.name}, 32 minutes 10 seconds of 52 minutes"
        waitFor(hasContentDescription(spoken))
        val stop = compose.onNode(hasContentDescription(spoken)).fetchSemanticsNode()
        assertEquals("one stop, its words merged", OPEN_THE_REMOTE, stop.config.getOrNull(SemanticsActions.OnClick)?.label)
        val play = compose.onNode(hasContentDescription(playOnTv(kitchen.name)) and hasClickAction()).fetchSemanticsNode()
        val stopCasting = compose.onNode(hasContentDescription(stopCastingTo(kitchen.name)) and hasClickAction()).fetchSemanticsNode()
        assertEquals(spoken, stop.config.getOrNull(SemanticsProperties.ContentDescription)?.single())
        assertTrue("its buttons after it, in reading order", stop.boundsInRoot.right <= play.boundsInRoot.left && play.boundsInRoot.right <= stopCasting.boundsInRoot.left)
        assertTrue("a 48 dp button", play.size.width >= with(compose.density) { 48.dp.roundToPx() })
        // Playing: the words L95 reads, the time moving on.
        device(CastPlayerState.Playing)
        waitFor(hasContentDescription("Harbor Lights, season 2, episode 4. Playing on ${kitchen.name}, 32 minutes", substring = true))
        waitFor(hasContentDescription(pauseOn(kitchen.name)) and hasClickAction())
    }

    @Test
    fun theShowPageSaysTheEpisodeIsPlayingOnTheCastDeviceAndOpensTheRemote() {
        tmdb = FakeTmdb(shows = mapOf(SHOW to show), seasons = { id, number -> if (id == SHOW && number == 2) season else null })
        compose.setContent { AppTheme { ShowScreen(SHOW, onBack = {}, onRemote = { opened.incrementAndGet() }) } }
        // Paused, so its place stands still for the words.
        device(CastPlayerState.Paused)
        val button = hasText("Playing S02E04 · 32:10", substring = true) and hasText(onTvLine(kitchen.name), substring = true) and hasClickAction()
        waitFor(button)
        compose.onNode(button).performClick()
        compose.waitUntil(5_000) { opened.get() == 1 }
        val row = hasText(onTvAt(kitchen.name, PAUSED_AT))
        waitFor(row)
        // The row too opens the remote, rather than starting it over here.
        compose.onNode(row).performClick()
        compose.waitUntil(5_000) { opened.get() == 2 }
        // The play over there: the button and the row go back to what they were.
        device(CastPlayerState.Idle, end = CastEnd(CastEnd.How.Stopped, PAUSED_AT))
        compose.waitUntil(5_000) { !exists(row) }
        assertFalse(exists(hasText("Playing S02E04", substring = true)))
    }

    private companion object {
        /** An invented show's TMDB id; nothing real has it. */
        const val SHOW = 991_612
        const val RELEASE = "Harbor.Lights.S02E04.1080p.WEB-DL.H.264-CAST"

        /** 32:10 of 52 minutes, where the boards' cast is. */
        const val PAUSED_AT = 1_930_000L
        const val LENGTH = 3_120_000L
    }
}
