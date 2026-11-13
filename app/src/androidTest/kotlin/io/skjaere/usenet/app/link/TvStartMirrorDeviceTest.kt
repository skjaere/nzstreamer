package io.skjaere.usenet.app.link

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.play.PlayMiniBar
import io.skjaere.usenet.app.play.PlaySheetHost
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.LinkMessage.Hello
import io.skjaere.usenet.link.LinkMessage.Status
import io.skjaere.usenet.link.Playback
import io.skjaere.usenet.link.Rejection
import io.skjaere.usenet.link.SheetKind
import io.skjaere.usenet.link.SheetStep
import io.skjaere.usenet.link.StepState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The play sheet mirrored from a TV (item 24, PtPhoneStarting, PtPhoneStartFail, L37) on a real device, through the app's
 * own links, start and sheet: a TV in the test's process on this device's own address, paired with the app's key and
 * chosen, answers each Play as the test says, as NZStreamer on a TV would. The sheet says where it starts, in the TV's
 * words, which release the TV chose and that it plays there, then Buffering, and closes as the picture starts; a season
 * pack is chosen on the TV; failures offer Close alone, in the mini bar too; Cancel asks the TV and says when it doesn't
 * answer; silence with the link down reads lost, and the TV heard again goes on; a TV's torrent start shows its own
 * steps, Preparing first. The silence is shrunk in its own case alone (L46): the others' states last their real time, so
 * a slow frame can't hurry them past what a case reads. The TV and the show are invented; the TV leaves the app's
 * link.json after, and the choice goes back to This phone.
 */
@RunWith(AndroidJUnit4::class)
class TvStartMirrorDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val link = container.data.link
    private val timings = PlayOnTimings.current
    private lateinit var tv: FakeTv

    private val ask = TvAsk(episode = LinkMessage.Play.Episode(showTmdbId = 991_501, showName = "Harbor Lights", season = 2, episode = 4, episodeName = "Low Water"))

    @Before
    fun setUp() {
        assumeFalse("a TV plays itself", container.television)
        linkTv(Hello.Ready(provider = true, indexer = true, tmdb = true))
        compose.setContent {
            AppTheme {
                Column {
                    PlayMiniBar(container.playback, tv = container.tvStart)
                    PlaySheetHost(container.playback, onSearch = {}, onProviders = {}, tv = container.tvStart)
                }
            }
        }
    }

    @After
    fun restore() {
        PlayOnTimings.current = timings
        if (!::tv.isInitialized) return
        compose.runOnUiThread { container.tvStart.close() }
        // The bar followed the session the TV took (item 25): it follows nothing after.
        container.tvPlaying.close()
        container.playOn.choose(null)
        tv.close()
        runBlocking { link.forgetTv(tv.key.id) }
    }

    /** A TV saying [ready] as its link comes up, paired, kept and chosen, in place of the one before. */
    private fun linkTv(ready: Hello.Ready) {
        if (::tv.isInitialized) {
            container.playOn.choose(null)
            tv.close()
            runBlocking { link.forgetTv(tv.key.id) }
        }
        val phone = runBlocking { link.identity().identity.key.id }
        tv = FakeTv(context, inventedTvName("Living room TV"), paired = setOf(phone), ready = ready, advertised = false)
        runBlocking { assertTrue(link.addTv(tv.key.id, tv.name, tv.address.hostAddress!!, tv.port)) }
        container.playOn.choose(tv.key.id)
        val until = SystemClock.uptimeMillis() + 20_000
        while ((container.playOn.target.value as? PlayTarget.Tv)?.state != TvState.Ready) {
            assertTrue("the TV linked; now ${container.playOn.target.value}", SystemClock.uptimeMillis() < until)
            Thread.sleep(50)
        }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, millis: Long = 15_000) = compose.waitUntil(millis) { exists(matcher) }

    private fun tap(matcher: SemanticsMatcher) {
        waitFor(matcher)
        compose.onNode(matcher).performClick()
    }

    /** A start on the TV, as Play with it chosen makes one; returns the Play the TV heard. */
    private fun start(): LinkMessage.Play {
        val before = tv.received.size
        compose.runOnUiThread { container.tvStart.start(tv.key.id, "Harbor Lights", "S02E04 · Low Water") { ask } }
        compose.waitUntil(10_000) { tv.received.drop(before).any { it is LinkMessage.Play } }
        return tv.received.drop(before).filterIsInstance<LinkMessage.Play>().single()
    }

    /** The TV says [message] on its link to this phone. */
    private fun say(message: LinkMessage) {
        tv.links.single().send(message)
    }

    private fun starting(sheet: Status.Sheet, session: String = "s-1") = Status(session, Playback.Starting, mine = true, title = "Harbor Lights", sheet = sheet)

    private val running = Status.Sheet(
        SheetKind.Running, "Harbor Lights", "S02E04 · Low Water", line = "Searched 2 indexers by TVDB id · 6 found",
        steps = listOf(
            Status.Step(SheetStep.FetchNzb, StepState.Done, millis = 600, detail = "2.9 MB"),
            Status.Step(SheetStep.CheckAvailability, StepState.Running, done = 112, total = 256),
        ),
        attempt = 1, of = 3, release = "Harbor.Lights.S02E04.Low.Water.2160p.WEB-DL-GRP", chips = listOf("2160p", "WEB-DL"), playsHere = true,
    )

    @Test
    fun aStartIsMirroredInTheTvsWordsAndClosesAsItsPictureStarts() {
        val play = start()
        assertEquals(4, play.episode?.episode)
        waitFor(hasText(startingOn(tv.name)))
        waitFor(hasText(waitingFor(tv.name)))
        say(LinkMessage.Ack(play.seq, "s-1"))
        say(starting(Status.Sheet(SheetKind.Searching, "Harbor Lights", "S02E04 · Low Water", line = "Searching 2 indexers for the episode and season packs · 4 found · 1 of 2 answered")))
        waitFor(hasText("Searching the TV's 2 indexers for the episode and season packs · 4 found · 1 of 2 answered"))
        say(starting(running))
        waitFor(hasText(tvChose(tv.name, 1, 3)))
        assertTrue(exists(hasText("Searched the TV's 2 indexers by TVDB id · 6 found")))
        assertTrue(exists(hasText(running.release!!)))
        assertTrue(exists(hasText(playsOn(tv.name))))
        assertTrue(exists(hasText(BUFFERING)))
        // The session is kept, by the show (L33).
        compose.waitUntil(5_000) { keptTv().sessions.any { it.id == "s-1" && it.showTmdbId == 991_501 } }
        // Swiped down: the mini bar says where and what.
        compose.runOnUiThread { container.tvStart.minimize() }
        waitFor(hasText(onTvStep(tv.name, "Checking availability…")))
        tap(hasText("Show") and hasClickAction())
        waitFor(hasText(tvChose(tv.name, 1, 3)))
        say(Status("s-1", Playback.Buffering, mine = true, title = "Harbor Lights"))
        compose.waitUntil(5_000) { container.tvStart.now.value?.phase == TvPhase.Buffering("s-1") }
        say(Status("s-1", Playback.Playing, mine = true, title = "Harbor Lights", positionMs = 1_000))
        compose.waitUntil(5_000) { container.tvStart.now.value == null }
        compose.waitUntil(5_000) { !exists(hasText(startingOn(tv.name))) }
    }

    /**
     * L203, L206: a TV's torrent start, its sheet naming Preparing, shows the TV's own three steps, Preparing then Opening
     * and Checking audio and video, and none of Usenet's; the mini bar names Preparing while it runs, and Preparing done
     * shows its time.
     */
    @Test
    fun aTorrentStartOnTheTvShowsPreparingAndNoUsenetStep() {
        val play = start()
        say(LinkMessage.Ack(play.seq, "s-1"))
        val torrent = running.copy(release = "Harbor.Lights.S02E04.Low.Water.2160p.WEB.H265-NZST", steps = listOf(Status.Step(SheetStep.Prepare, StepState.Running)))
        say(starting(torrent))
        waitFor(hasText(tvChose(tv.name, 1, 3)))
        waitFor(hasText("Preparing"))
        assertTrue(exists(hasText("Opening")))
        assertTrue(exists(hasText("Checking audio and video")))
        assertFalse("a Usenet step", exists(hasText("Fetching NZB")) || exists(hasText("Checking availability")))
        compose.runOnUiThread { container.tvStart.minimize() }
        waitFor(hasText(onTvStep(tv.name, "Preparing…")))
        tap(hasText("Show") and hasClickAction())
        say(starting(torrent.copy(steps = listOf(Status.Step(SheetStep.Prepare, StepState.Done, millis = 900), Status.Step(SheetStep.Open, StepState.Running)))))
        waitFor(hasText("0.9 s"))
        assertFalse("a Usenet step", exists(hasText("Fetching NZB")) || exists(hasText("Checking availability")))
    }

    /**
     * D2PhoneTvStarting 2 (L223): a TV that plays from its debrid service alone sends the rows its own sheet lists while it
     * searches, so the phone lists Preparing, Opening and Checking audio and video, then Buffering, and none of Usenet's;
     * the TV's refusal for its debrid way is said in D2PhoneTvStartFail's words; a failure's releases left as what they
     * play from failed on the TV are said apart (#316).
     */
    @Test
    fun aDebridOnlyTvsSearchListsItsOwnRowsAndItsRefusalsAndLeftoversAreSaid() {
        val play = start()
        say(LinkMessage.Ack(play.seq, "s-1"))
        say(
            starting(
                Status.Sheet(
                    SheetKind.Searching, "Harbor Lights", "S02E04 · Low Water", line = "Searching 2 indexers for the episode and season packs · 4 found · 1 of 2 answered",
                    rows = listOf(SheetStep.Prepare, SheetStep.Open, SheetStep.ProbeTracks),
                ),
            ),
        )
        waitFor(hasText("Searching the TV's 2 indexers for the episode and season packs · 4 found · 1 of 2 answered"))
        waitFor(hasText("Preparing"))
        assertTrue(exists(hasText("Opening")))
        assertTrue(exists(hasText("Checking audio and video")))
        assertTrue(exists(hasText(BUFFERING)))
        assertFalse("a Usenet step", exists(hasText("Fetching NZB")) || exists(hasText("Checking availability")))
        // It failed there: two releases left as what they play from failed on the TV.
        say(starting(Status.Sheet(SheetKind.Failed, "Harbor Lights", "S02E04", failures = listOf(Status.Line("Harbor.Lights.S02E04.2160p.WEB.H265-KESTREL", "The TV's debrid service stopped answering")), notTried = 2, noRoute = 2)))
        waitFor(hasText(eachFailedOn(tv.name, 1)))
        assertTrue(exists(hasText("2 more releases not tried: what they play from failed on the TV.")))
        assertFalse(exists(hasText("download allowance", substring = true)))
        tap(hasText("Close") and hasClickAction())
        compose.waitUntil(5_000) { container.tvStart.now.value == null }
        // A refusal for each lack of the TV's debrid way.
        for (reason in listOf(Rejection.NotReadyService, Rejection.NotReadyServiceOff, Rejection.NotReadyTorrentIndexer)) {
            val again = start()
            say(LinkMessage.Rejected(again.seq, reason))
            val (first, second) = rejectedWords(reason, tv.name)
            waitFor(hasText(first))
            second?.let { assertTrue(exists(hasText(it))) }
            tap(hasText("Close") and hasClickAction())
            compose.waitUntil(5_000) { container.tvStart.now.value == null }
        }
        assertEquals("Couldn't start on ${tv.name}. Its debrid service is off.", rejectedWords(Rejection.NotReadyServiceOff, tv.name).first)
    }

    /**
     * D2PhoneTvStarting (CWR-1, CWD-1): on a TV whose hello says it plays from its debrid service alone, the sheet lists
     * Preparing, Opening and Checking audio and video from the start, before the TV says anything, and keeps them as a
     * release starts and names no step yet: never Usenet's Fetching NZB or Checking availability, which that TV never runs.
     */
    @Test
    fun aDebridOnlyTvsStartNeverListsUsenetsRows() {
        linkTv(Hello.Ready(provider = false, indexer = false, tmdb = true, debrid = true))
        val play = start()
        waitFor(hasText(waitingFor(tv.name)))
        waitFor(hasText("Opening"))
        assertTrue("its service's first row, before the TV said anything", exists(hasText("Preparing")))
        assertFalse("a Usenet step before the TV said anything", exists(hasText("Fetching NZB")) || exists(hasText("Checking availability")))
        say(LinkMessage.Ack(play.seq, "s-1"))
        say(
            starting(
                Status.Sheet(
                    SheetKind.Searching, "Harbor Lights", "S02E04 · Low Water", line = "Searching 2 indexers for the episode and season packs · 4 found · 1 of 2 answered",
                    rows = listOf(SheetStep.Prepare, SheetStep.Open, SheetStep.ProbeTracks),
                ),
            ),
        )
        waitFor(hasText("Searching the TV's 2 indexers for the episode and season packs · 4 found · 1 of 2 answered"))
        // Release 1 of 3 starts, its sheet naming no step yet.
        val first = running.copy(release = "Harbor.Lights.S02E04.2160p.WEB.H265-KESTREL", chips = listOf("2160p", "WEB"), steps = emptyList())
        say(starting(first))
        waitFor(hasText(tvChose(tv.name, 1, 3)))
        assertTrue("its service's first row as the release started", exists(hasText("Preparing")))
        assertFalse("a Usenet step as the release started", exists(hasText("Fetching NZB")) || exists(hasText("Checking availability")))
        say(starting(first.copy(steps = listOf(Status.Step(SheetStep.Prepare, StepState.Done, millis = 900), Status.Step(SheetStep.Open, StepState.Running)))))
        waitFor(hasText("0.9 s"))
        // Release 2 of 3, naming no step yet again.
        say(starting(first.copy(attempt = 2, release = "Harbor.Lights.S02E04.1080p.WEB.H264-KESTREL", notice = "No longer cached on the TV's debrid service · trying release 2 of 3")))
        waitFor(hasText(tvChose(tv.name, 2, 3)))
        assertTrue("its service's first row as the next release started", exists(hasText("Preparing")))
        assertFalse("a Usenet step as the next release started", exists(hasText("Fetching NZB")) || exists(hasText("Checking availability")))
    }

    @Test
    fun aSeasonPacksQuestionIsAnsweredOnTheTv() {
        val play = start()
        say(LinkMessage.Ack(play.seq, "s-1"))
        say(starting(Status.Sheet(SheetKind.Choosing, "Harbor Lights", "S02E04", lookingFor = "Looking for S02E04 'Low Water'", release = "Harbor.Lights.S02.1080p.BluRay-GRP")))
        waitFor(hasText(CHOOSE_ON_TV))
        assertTrue(exists(hasText(choosingOn(tv.name))))
        assertTrue(exists(hasText(WAITING_FOR_THE_TV)))
    }

    @Test
    fun aStartThatDidntStartOffersCloseAloneInTheTvsWords() {
        val play = start()
        say(LinkMessage.Ack(play.seq, "s-1"))
        say(
            starting(
                Status.Sheet(
                    SheetKind.Failed, "Harbor Lights", "S02E04",
                    failures = listOf(Status.Line("Harbor.Lights.S02E04.2160p-GRP", "Incomplete on the TV's providers · 41 of 256 checked articles missing")),
                    skippedBefore = listOf(Status.Line("Harbor.Lights.S02E04.480p-GRP", "Skipped: missing on your providers (checked today)")),
                ),
            ),
        )
        waitFor(hasText(eachFailedOn(tv.name, 1)))
        assertTrue(exists(hasText("✕ Incomplete on the TV's providers · 41 of 256 checked articles missing")))
        assertTrue(exists(hasText("Skipped: missing on the TV's providers (checked today)")))
        assertTrue(exists(hasText("Close") and hasClickAction()))
        assertFalse("no Search on a TV's failure (W13)", exists(hasText("Search", substring = true) and hasClickAction()))
        compose.runOnUiThread { container.tvStart.minimize() }
        waitFor(hasText(couldntPlayOn("Harbor Lights", tv.name)))
        tap(hasText("Show") and hasClickAction())
        tap(hasText("Close") and hasClickAction())
        compose.waitUntil(5_000) { container.tvStart.now.value == null }
        // A refusal, in L41's words.
        val again = start()
        say(LinkMessage.Rejected(again.seq, Rejection.NotReadyProvider))
        val (first, second) = rejectedWords(Rejection.NotReadyProvider, tv.name)
        waitFor(hasText(first))
        assertTrue(exists(hasText(second!!)))
        tap(hasText("Close") and hasClickAction())
        compose.waitUntil(5_000) { container.tvStart.now.value == null }
    }

    @Test
    fun cancelAsksTheTvToStopAndSaysSoWhenItDoesntAnswer() {
        // Cancel's own time, 5 s: "Stopping on" shows that long, room for a slow frame before it is read.
        val play = start()
        say(LinkMessage.Ack(play.seq, "s-1"))
        say(starting(running))
        tap(hasText("Cancel") and hasClickAction())
        compose.waitUntil(5_000) { tv.received.any { it is LinkMessage.Cancel && it.session == "s-1" } }
        waitFor(hasText(stoppingOn(tv.name)))
        // Unanswered past Cancel's time.
        waitFor(hasText(didntAnswer(tv.name)))
        tap(hasText("Close") and hasClickAction())
        compose.waitUntil(5_000) { container.tvStart.now.value == null }
        // Answered: the sheet closes on both.
        val next = start()
        say(LinkMessage.Ack(next.seq, "s-2"))
        say(starting(running, session = "s-2"))
        tap(hasText("Cancel") and hasClickAction())
        compose.waitUntil(5_000) { tv.received.any { it is LinkMessage.Cancel && it.session == "s-2" } }
        val cancel = tv.received.filterIsInstance<LinkMessage.Cancel>().last()
        say(LinkMessage.Ack(cancel.seq, "s-2"))
        compose.waitUntil(5_000) { container.tvStart.now.value == null }
    }

    @Test
    fun silenceWithTheLinkDownReadsLostAndTheTvHeardAgainGoesOn() {
        // The sheet's silence shrunk here alone, as this case waits it out; to 5 s, room for a slow Ack before it.
        PlayOnTimings.current = PlayOnTimings(silenceMillis = 5_000)
        val play = start()
        say(LinkMessage.Ack(play.seq, "s-1"))
        say(starting(running))
        waitFor(hasText(tvChose(tv.name, 1, 3)))
        // NZStreamer on the TV gone away: no word, and its link down, for the (shrunk) silence.
        tv.close()
        waitFor(hasText(lostConnection(tv.name)), millis = 20_000)
        assertTrue(exists(hasText(LOST_TEXT)))
        assertTrue(exists(hasText(NO_ANSWER)))
        assertTrue(exists(hasText("Last heard from the TV", substring = true)))
        // Back: its link comes up again, its hello naming the play (as a TV's does, StatusPump), and it says where it is.
        tv.session = LinkMessage.Hello.Session("s-1", mine = true)
        tv.reopen()
        compose.waitUntil(30_000) { tv.links.isNotEmpty() }
        say(Status("s-1", Playback.Buffering, mine = true, title = "Harbor Lights"))
        compose.waitUntil(5_000) { container.tvStart.now.value?.phase == TvPhase.Buffering("s-1") }
    }

    /** The test's TV as the app's link.json keeps it. */
    private fun keptTv() = runBlocking { link.tvs.first() }.single { it.key == tv.key.id }
}
