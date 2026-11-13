package io.skjaere.usenet.app.link

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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.collection.ShowScreen
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.play.PlayMiniBar
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.LinkMessage.Status
import io.skjaere.usenet.link.Playback
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Playing on bar and the Show page while the TV plays (item 25, PtPhoneBar, PtPhoneLost, PtPhoneTalkBack 10-12, W14,
 * W15) on a real device, through the app's own links, start and bar: a TV in the test's process on this device's own
 * address, paired with the app's key and chosen, takes a Play and says how it plays, as NZStreamer on a TV would. Each of
 * the bar's buttons sends its command once, and the bar goes on the TV's stopped status; TalkBack reads it as one stop
 * with its two buttons after; a lost link reads so, then "Can't reach" (its time shrunk in that case alone, L46), whose
 * × puts it away until a new link brings it back; the Show page's button and the episode's row say the TV plays it, each
 * opening the remote; and a play the app no longer follows (a restart) is followed again as the TV says it plays it. The
 * TV and the show are invented; the TV leaves the app's link.json after, and the app follows nothing.
 */
@RunWith(AndroidJUnit4::class)
class TvBarDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container
    private val timings = PlayOnTimings.current
    private val opened = AtomicInteger()
    private lateinit var playing: PlayingTv
    private val tv get() = playing.tv
    private var tmdb: FakeTmdb? = null

    private val show = ShowDetails(
        SHOW, "Harbor Lights", null, 2021, null, null, null, "Ended", false, 52,
        listOf(SeasonSummary(1, null, 6, LocalDate.of(2021, 3, 1), null)),
        lastEpisode = EpisodeSummary(1, 6, "Episode 6", LocalDate.of(2021, 4, 5)), nextEpisode = null, ids = ExternalIds(tvdb = 991_601),
    )

    private val season = Season(SHOW, 1, null, null, (1..6).map { Episode(1, it, "Episode $it", null, LocalDate.of(2021, 3, 1).plusWeeks(it - 1L), 52, null) })

    @Before
    fun setUp() {
        assumeFalse("a TV plays itself", container.television)
        playing = PlayingTv()
    }

    @After
    fun restore() {
        PlayOnTimings.current = timings
        tmdb?.close()
        if (::playing.isInitialized) playing.close()
    }

    private fun bar() = compose.setContent {
        AppTheme {
            Column { PlayMiniBar(container.playback, tv = container.tvStart, playing = container.tvPlaying, onRemote = { opened.incrementAndGet() }) }
        }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, millis: Long = 15_000) = compose.waitUntil(millis) { exists(matcher) }

    private fun tap(matcher: SemanticsMatcher) {
        waitFor(matcher)
        compose.onNode(matcher).performClick()
    }

    private fun say(message: LinkMessage) = playing.say(message)

    private fun status(state: Playback, positionMs: Long = 1_930_000, show: Int = SHOW) = playing.status(state, positionMs, show)

    private fun plays(state: Playback = Playback.Playing) = playing.play(state)

    private inline fun <reified T : LinkMessage> sent(): List<T> = playing.sent<T>()

    @Test
    fun eachButtonSendsItsCommandOnceAndTheBarGoesOnTheTvsStoppedStatus() {
        bar()
        plays()
        waitFor(hasText("Harbor Lights · S01E03"))
        assertTrue(exists(hasText("Playing on ${tv.name}")))
        tap(hasContentDescription(pauseOn(tv.name)))
        compose.waitUntil(5_000) { sent<LinkMessage.Pause>().isNotEmpty() }
        Thread.sleep(500)
        assertEquals(listOf("s-1"), sent<LinkMessage.Pause>().map { it.session })
        say(status(Playback.Paused))
        waitFor(hasText("Paused on ${tv.name}"))
        tap(hasContentDescription(playOnTv(tv.name)))
        compose.waitUntil(5_000) { sent<LinkMessage.Resume>().isNotEmpty() }
        say(status(Playback.Playing))
        waitFor(hasText("Playing on ${tv.name}"))
        tap(hasContentDescription(stopPlayingOn(tv.name)))
        compose.waitUntil(5_000) { sent<LinkMessage.Stop>().isNotEmpty() }
        Thread.sleep(500)
        assertEquals(1, sent<LinkMessage.Resume>().size)
        assertEquals(listOf("s-1"), sent<LinkMessage.Stop>().map { it.session })
        assertTrue("the bar stays until the TV says it stopped", exists(hasText("Harbor Lights · S01E03")))
        // A tap anywhere but its buttons opens the remote.
        compose.onNode(hasText("Harbor Lights · S01E03", substring = true)).performClick()
        compose.waitUntil(5_000) { opened.get() == 1 }
        say(Status("s-1", Playback.Stopped, mine = true, title = "Harbor Lights"))
        compose.waitUntil(5_000) { !exists(hasText("Harbor Lights · S01E03")) }
        assertEquals(null, container.tvPlaying.now.value)
    }

    @Test
    fun talkBackReadsTheBarAsOneStopWithItsTwoButtonsAfterIt() {
        bar()
        plays(Playback.Paused)
        val spoken = "Harbor Lights, season 1, episode 3. Paused on ${tv.name}, 32 minutes 10 seconds of 52 minutes"
        waitFor(hasContentDescription(spoken))
        val stop = compose.onNode(hasContentDescription(spoken)).fetchSemanticsNode()
        assertEquals("one stop, its words merged", OPEN_THE_REMOTE, stop.config.getOrNull(SemanticsActions.OnClick)?.label)
        val play = compose.onNode(hasContentDescription(playOnTv(tv.name)) and hasClickAction()).fetchSemanticsNode()
        val stopButton = compose.onNode(hasContentDescription(stopPlayingOn(tv.name)) and hasClickAction()).fetchSemanticsNode()
        assertEquals(spoken, stop.config.getOrNull(SemanticsProperties.ContentDescription)?.single())
        assertTrue("its buttons after it, in reading order", stop.boundsInRoot.right <= play.boundsInRoot.left && play.boundsInRoot.right <= stopButton.boundsInRoot.left)
        assertTrue("a 48 dp button", play.size.width >= with(compose.density) { 48.dp.roundToPx() })
    }

    @Test
    fun aLostLinkReadsSoThenCantReachWhoseXPutsItAwayUntilANewLinkBringsItBack() {
        // Can't reach shrunk here alone, as this case waits it out; to 5 s, room to read the lost bar's buttons before it.
        PlayOnTimings.current = PlayOnTimings(cantReachMillis = 5_000)
        bar()
        plays()
        waitFor(hasText("Playing on ${tv.name}"))
        // NZStreamer on the TV gone: the link lost, the buttons wait.
        tv.close()
        waitFor(hasText(LOST_RECONNECTING), millis = 20_000)
        compose.onNode(hasContentDescription(pauseOn(tv.name))).assertIsNotEnabled()
        compose.onNode(hasContentDescription(stopPlayingOn(tv.name))).assertIsNotEnabled()
        // Past the (shrunk) two minutes.
        waitFor(hasText(cantReach(tv.name)), millis = 15_000)
        tap(hasContentDescription(PUT_AWAY))
        compose.waitUntil(5_000) { !exists(hasText("Harbor Lights · S01E03")) }
        // Back: its link up again, and the TV says its play.
        tv.reopen()
        say(status(Playback.Playing, positionMs = 2_110_000))
        waitFor(hasText("Playing on ${tv.name}"), millis = 30_000)
        compose.onNode(hasContentDescription(pauseOn(tv.name))).assertIsEnabled()
    }

    /** The process started afresh: nothing followed; the TV, its link up, says it plays the session this phone kept. */
    @Test
    fun aPlayOfThisPhonesTheTvSaysItPlaysIsFollowedAgainAfterARestart() {
        bar()
        plays()
        waitFor(hasText("Playing on ${tv.name}"))
        compose.waitUntil(5_000) { runBlocking { container.data.link.tvs.first() }.any { row -> row.key == tv.key.id && row.sessions.any { it.id == PlayingTv.SESSION } } }
        container.tvPlaying.close()
        compose.waitUntil(5_000) { !exists(hasText("Harbor Lights · S01E03")) }
        say(status(Playback.Paused))
        waitFor(hasText("Paused on ${tv.name}"))
        tap(hasContentDescription(playOnTv(tv.name)))
        compose.waitUntil(5_000) { sent<LinkMessage.Resume>().isNotEmpty() }
        assertEquals(listOf(PlayingTv.SESSION), sent<LinkMessage.Resume>().map { it.session })
    }

    @Test
    fun theShowPageSaysTheEpisodeIsPlayingOnTheTvAndOpensTheRemote() {
        tmdb = FakeTmdb(shows = mapOf(SHOW to show), seasons = { id, _ -> if (id == SHOW) season else null })
        compose.setContent { AppTheme { ShowScreen(SHOW, onBack = {}, onRemote = { opened.incrementAndGet() }) } }
        waitFor(hasText("Episode 3"))
        // Paused, so its place stands still for the words.
        plays(Playback.Paused)
        val button = hasText("Playing S01E03 · 32:10", substring = true) and hasText(onTvLine(tv.name), substring = true) and hasClickAction()
        waitFor(button)
        waitFor(hasText(onTvAt(tv.name, 1_930_000)))
        compose.onNode(button).performClick()
        compose.waitUntil(5_000) { opened.get() == 1 }
        // The row too opens the remote, rather than starting it over on the TV.
        val plays = sent<LinkMessage.Play>().size
        compose.onNode(hasText(onTvAt(tv.name, 1_930_000))).performClick()
        compose.waitUntil(5_000) { opened.get() == 2 }
        assertEquals(plays, sent<LinkMessage.Play>().size)
        // Another episode on the TV: this one's row and the button go back to what they were.
        say(status(Playback.Paused, show = SHOW + 1))
        compose.waitUntil(5_000) { !exists(hasText(onTvAt(tv.name, 1_930_000))) }
        assertTrue(!exists(hasText("Playing S01E03", substring = true)))
    }

    private companion object {
        const val SHOW = PlayingTv.SHOW
    }
}
