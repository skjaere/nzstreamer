package io.skjaere.usenet.app.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.debrid.ServiceSource
import io.skjaere.usenet.app.play.FileChoice
import io.skjaere.usenet.app.play.OkGuard
import io.skjaere.usenet.app.play.PlaySheet
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.StartedFrom
import io.skjaere.usenet.app.play.StoppedPlay
import io.skjaere.usenet.app.play.nextServiceNotice
import io.skjaere.usenet.app.play.retriedSteps
import io.skjaere.usenet.app.play.retryNotice
import io.skjaere.usenet.app.play.runningLine
import io.skjaere.usenet.app.search.SourceTag
import io.skjaere.usenet.app.settings.NoUsableProvider
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.PipelineStep
import io.skjaere.usenet.core.StepStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes

/**
 * The play sheet with a remote on a TV (forced on the phone emulator): Searching and Running open
 * with the focus on Hide, never on Cancel; a double OK on what started the play doesn't reach the
 * sheet; Back hides it to the mini bar. Failed and No release focus their way on. The question of
 * which file plays takes the focus on its first file, as it does on a phone used with a D-pad, its
 * "Not in this release" follows the files, and Back stops the play. A debrid service's limit moves on to a Usenet release
 * with the phone's short words, or ends on the failure sheet with its wait, Close focused (DbTvPlaySheet 2-3); a torrent's
 * failed Opening on the first service goes back to Preparing on the next, Hide focused (D2PhonePlaySheetRetry 3). Phones
 * keep the sheet as it was.
 */
@RunWith(AndroidJUnit4::class)
class PlaySheetTvTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var state by mutableStateOf<PlayState>(PlayState.Idle)
    private var startedFrom by mutableStateOf<StartedFrom?>(null)
    private var expanded by mutableStateOf(true)
    private val cancels = AtomicInteger()
    private val hides = AtomicInteger()
    private val plays = AtomicInteger()
    private val chosen = java.util.concurrent.CopyOnWriteArrayList<Int>()
    private val notInRelease = AtomicInteger()

    private val searching = PlayState.Searching("Harbor Lights", "S02E06 · Breakwater", "Searching 3 indexers by TVDB id")
    private val running = PlayState.Running(
        "Harbor Lights", "S02E06 · Breakwater", "Searched 3 indexers by TVDB id", 1, 2, "Harbor.Lights.S02E06.Breakwater.1080p.WEB-DL.DDP5.1.H.264-NZST",
        listOf("1080p", "WEB-DL", "H.264", "DD+ 5.1"),
        mapOf(PipelineStep.FetchNzb to StepStatus.Done(400), PipelineStep.CheckAvailability to StepStatus.Running(18, 32)),
        notice = null,
    )
    private val choosing = PlayState.ChoosingFile(
        "Harbor Lights", "S02E01 · Brine", "Looking for S02E01 'Brine'", "Harbor.Lights.S02.720p.WEB.h264-GRP",
        listOf(
            FileChoice(2, "Harbor.Lights.2-6.mp4", 1_200_000_000, sample = false),
            FileChoice(5, "Harbor.Lights.5_6.mp4", 1_100_000_000, sample = false),
            FileChoice(0, "Harbor.Lights.sample.mp4", 50_000_000, sample = true),
        ),
    )

    @Before
    fun remote() {
        instrumentation.setInTouchMode(false)
    }

    @After
    fun touch() {
        instrumentation.setInTouchMode(true)
    }

    /** The sheet over a stand-in for what starts a play (an Up next row), as AppNav shows it on [formFactor]. */
    private fun show(formFactor: FormFactor = FormFactor.Tv) {
        val content = @androidx.compose.runtime.Composable {
            Column {
                Button(onClick = { plays.incrementAndGet(); state = searching; expanded = true }) { Text("Play Harbor Lights") }
                if (!expanded) Text("Mini bar")
            }
            if (expanded) {
                PlaySheet(
                    state, onMinimize = { hides.incrementAndGet(); expanded = false }, onCancel = { cancels.incrementAndGet() },
                    onDismiss = {}, onSearch = {}, onProviders = {}, onChooseFile = { chosen += it }, onNotInRelease = { notInRelease.incrementAndGet() },
                    startedFrom = startedFrom,
                )
            }
        }
        if (formFactor == FormFactor.Tv) compose.setTvContent(content = content) else compose.setContent { AppTheme(formFactor) { content() } }
    }

    private fun press(key: Int) {
        instrumentation.sendKeyDownUpSync(key)
        compose.waitForIdle()
    }

    private fun waitForFocusOn(text: String) {
        try {
            compose.waitUntil(5_000) { runCatching { compose.onNodeWithText(text).assertIsFocused() }.isSuccess }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            val roots = compose.onAllNodes(androidx.compose.ui.test.isRoot())
            val trees = (0 until roots.fetchSemanticsNodes().size).joinToString("\n") { roots[it].printToString() }
            throw AssertionError("focus never on $text; on screen:\n$trees", e)
        }
    }

    /**
     * A play a paired phone asked for (PtTvIncoming): under the title, who asked; after a hand-over, what stopped and where;
     * while it asks which file it is, that the phone waits. A play asked for here says none of it.
     */
    @Test
    fun aPhonesPlaySaysWhoAskedUnderItsTitle() {
        state = running
        show()
        assertEquals(0, compose.onAllNodesWithText("Started from", substring = true).fetchSemanticsNodes().size)
        startedFrom = StartedFrom("Galaxy phone")
        compose.onNodeWithText("Started from Galaxy phone").assertExists()
        waitForFocusOn("Hide")
        startedFrom = StartedFrom("Galaxy phone", StoppedPlay("The Glass Orchard", 3_734_000))
        state = searching
        compose.onNodeWithText("Started from Galaxy phone").assertExists()
        compose.onNodeWithText("The Glass Orchard stopped at 1:02:14. Resume goes on from there.").assertExists()
        state = choosing
        compose.onNodeWithText("Galaxy phone is waiting for you to choose").assertExists()
        assertEquals(0, compose.onAllNodesWithText("Started from", substring = true).fetchSemanticsNodes().size)
    }

    /**
     * DbTvPlaySheet 1: a release a debrid service has cached, starting, lists its own three steps, Preparing done with its
     * time, and "Release" says where it plays from; the focus on Hide, as for any play.
     */
    @Test
    fun aTorrentsSheetListsPreparingOpeningAndTheProbeWithWhereItPlaysFrom() {
        state = PlayState.Running(
            "Harbor Lights", "S02E04 · Low Water", null, 1, 1, "Harbor.Lights.S02E04.2160p.WEB.H265-KESTREL", listOf("2160p", "WEB", "HEVC", "6.1 GB"),
            mapOf(PipelineStep.Prepare to StepStatus.Done(900), PipelineStep.Open to StepStatus.Running()), notice = null,
            sourceSteps = ServiceSource.STEPS, tag = SourceTag("TorBox", "From TorBox", dim = false),
        )
        show()
        waitForFocusOn("Hide")
        compose.onNodeWithText("Preparing").assertExists()
        compose.onNodeWithText("0.9 s").assertExists()
        compose.onNodeWithText("Opening").assertExists()
        compose.onNodeWithText("Checking audio and video").assertExists()
        assertEquals("no Usenet step", 0, compose.onAllNodesWithText("Fetching NZB").fetchSemanticsNodes().size)
        compose.onNodeWithText("Release").assertExists()
        // The tag is one node, read as where it plays from (L251): its words are drawn, not text of their own.
        compose.onNode(hasContentDescription("From TorBox")).assertExists()
    }

    /**
     * DbTvPlaySheet 2: a debrid service's limit stops only that account's releases, and the play goes on with a Usenet one:
     * the notice in the phone's short words, naming no service and leaving the wait to the failure sheet, "Release 2 of 3"
     * tagged "Usenet", its four steps again and no Preparing; the focus on Hide.
     */
    @Test
    fun aServicesLimitMovesOnToAUsenetReleaseWithItsNoticeAndItsOwnSteps() {
        val limit = AttemptFailure.ServiceLimit(20.minutes, "service:a")
        state = PlayState.Running(
            "Harbor Lights", "S02E04 · Low Water", "Searched 3 indexers by TVDB id · 9 found", 2, 3, "Harbor.Lights.S02E04.Low.Water.1080p.WEB-DL.DDP5.1.H.264-NZST",
            listOf("1080p", "WEB-DL", "H.264", "2.9 GB"), mapOf(PipelineStep.FetchNzb to StepStatus.Running()),
            notice = retryNotice(limit, 2, 3, null), noticeFailure = limit, tag = SourceTag("Usenet", "From Usenet", dim = false),
        )
        show()
        waitForFocusOn("Hide")
        compose.onNodeWithText("The debrid service’s limit was reached · trying release 2 of 3").assertExists()
        assertEquals("the wait is the failure sheet's", 0, compose.onAllNodesWithText("try again", substring = true).fetchSemanticsNodes().size)
        compose.onNodeWithText("Release 2 of 3").assertExists()
        compose.onNode(hasContentDescription("From Usenet")).assertExists()
        listOf("Fetching NZB", "Checking availability", "Opening", "Checking audio and video").forEach { compose.onNodeWithText(it).assertExists() }
        assertEquals("no Preparing", 0, compose.onAllNodesWithText("Preparing").fetchSemanticsNodes().size)
    }

    /**
     * DbTvPlaySheet 3: a torrent chosen from the list, its service's limit reached and nothing else to try: today's failure
     * sheet, the release's card with the short words and the wait the service gave, and Close focused.
     */
    @Test
    fun aServicesLimitWithNothingElseToTryShowsItsWaitWithCloseFocused() {
        val limit = AttemptFailure.ServiceLimit(20.minutes, "service:a")
        state = PlayState.Failed("Harbor Lights", "S02E04 · Low Water", listOf("Harbor.Lights.S02E04.2160p.WEB.H265-NZST" to limit.text), notTried = 0)
        show()
        waitForFocusOn("Close")
        compose.onNodeWithText("Couldn't start playback. The release failed:").assertExists()
        compose.onNodeWithText("✕ The debrid service’s limit was reached · try again in about 20 min").assertExists()
        compose.onNodeWithText("Harbor.Lights.S02E04.2160p.WEB.H265-NZST").assertExists()
    }

    /**
     * D2PhonePlaySheetRetry 3 (DEP-6): a torrent chosen from the list, cached on both services, TorBox first in the order and
     * its Opening failed, so the same release goes on to the next service, nothing else to try: the notice in the sheet's
     * short words, naming no service; Preparing running again, the tag naming Premiumize, which tries it now, and the steps
     * after it waiting, no ✕ left; "Release" alone; the focus on Hide, as for any play. Minimised, it says Preparing.
     */
    @Test
    fun aTorrentTriedOnTheNextServiceIsBackAtPreparingWithHideFocused() {
        val down = AttemptFailure.ServiceDown("service:tb")
        val opened = mapOf(PipelineStep.Prepare to StepStatus.Done(800), PipelineStep.Open to StepStatus.Failed(400, down))
        state = PlayState.Running(
            "Harbor Lights", "S02E04 · Low Water", null, 1, 1, "Harbor.Lights.S02E04.2160p.WEB.H265-KESTREL",
            listOf("2160p", "WEB", "HEVC", "6.1 GB", "≈ 16 Mbps", "DD+ 5.1"),
            retriedSteps(opened, ServiceSource.STEPS) + (PipelineStep.Prepare to StepStatus.Running()),
            notice = nextServiceNotice(down, cast = null), noticeFailure = down, noticeNextService = true,
            sourceSteps = ServiceSource.STEPS, tag = SourceTag("Premiumize", "From Premiumize", dim = false),
        )
        show()
        waitForFocusOn("Hide")
        compose.onNodeWithText("Cancel").assertIsNotFocused()
        compose.onNodeWithText("The debrid service stopped answering · trying your next debrid service").assertExists()
        compose.onNode(hasContentDescription("Preparing, in progress")).assertExists()
        compose.onNode(hasContentDescription("Opening")).assertExists()
        compose.onNode(hasContentDescription("Checking audio and video")).assertExists()
        assertEquals("no ✕ left of TorBox's Opening", 0, compose.onAllNodesWithText("✕").fetchSemanticsNodes().size)
        compose.onNodeWithText("Release").assertExists()
        compose.onNode(hasContentDescription("From Premiumize")).assertExists()
        assertEquals("no service named but the one trying it", 0, compose.onAllNodesWithText("TorBox", substring = true).fetchSemanticsNodes().size)
        assertEquals("nor read", 0, compose.onAllNodes(hasContentDescription("TorBox", substring = true)).fetchSemanticsNodes().size)
        assertEquals("no other release tried", 0, compose.onAllNodesWithText("trying release", substring = true).fetchSemanticsNodes().size)
        assertEquals("Preparing…", runningLine(state as PlayState.Running, cast = null))
    }

    @Test
    fun searchingOpensWithTheFocusOnHideNotCancel() {
        state = searching
        show()
        waitForFocusOn("Hide")
        compose.onNodeWithText("Cancel").assertIsNotFocused()
    }

    @Test
    fun runningOpensWithTheFocusOnHide() {
        state = running
        show()
        waitForFocusOn("Hide")
        compose.onNodeWithText("Cancel").assertIsNotFocused()
    }

    /** One press of OK as the remote sent it at [at] (uptime), however late it reaches the app. */
    private fun okSentAt(at: Long) {
        instrumentation.sendKeySync(KeyEvent(at, at, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        instrumentation.sendKeySync(KeyEvent(at, at + 40, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        compose.waitForIdle()
    }

    @Test
    fun aDoubleOkOnWhatStartedThePlayDoesntReachTheSheet() {
        expanded = false
        show()
        compose.onNodeWithText("Play Harbor Lights").requestFocus()
        compose.waitForIdle()
        val first = SystemClock.uptimeMillis()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitForFocusOn("Hide")
        assertEquals(1, plays.get())

        // The double press's second OK, sent 150 ms after the first: it reaches the sheet, which ignores it.
        okSentAt(first + 150)
        assertEquals("cancelled", 0, cancels.get())
        assertEquals("hidden", 0, hides.get())
        assertEquals("the second OK went to what started the play", 1, plays.get())
        compose.onNodeWithText("Hide").assertExists()

        // Later, OK is the user's: on Hide.
        SystemClock.sleep(OkGuard.WINDOW_MS)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("hidden", 1, hides.get())
        assertEquals("cancelled", 0, cancels.get())
        compose.onNodeWithText("Mini bar").assertExists()
    }

    @Test
    fun backHidesTheSheetToTheMiniBar() {
        state = searching
        show()
        waitForFocusOn("Hide")
        press(KeyEvent.KEYCODE_BACK)
        assertEquals("hidden", 1, hides.get())
        assertEquals("cancelled", 0, cancels.get())
        compose.onNodeWithText("Mini bar").assertExists()
    }

    /** Which file plays: the focus starts on the first file, Down goes through them, and Back stops the play, not hides it. */
    @Test
    fun choosingAFileOpensWithTheFocusOnTheFirstFileAndBackStopsThePlay() {
        state = choosing
        show()
        waitForFocusOn("Harbor.Lights.2-6.mp4")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitForFocusOn("Harbor.Lights.5_6.mp4")
        press(KeyEvent.KEYCODE_BACK)
        assertEquals("cancelled", 1, cancels.get())
        assertEquals("hidden", 0, hides.get())
        assertEquals("chosen", emptyList<Int>(), chosen.toList())
    }

    /** OK on a file plays it: the coordinator hears its place in the release. */
    @Test
    fun okOnAFileChoosesIt() {
        state = choosing
        show()
        waitForFocusOn("Harbor.Lights.2-6.mp4")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitForFocusOn("Harbor.Lights.5_6.mp4")
        // Past the sheet's guard against an OK meant for what opened it.
        SystemClock.sleep(OkGuard.WINDOW_MS)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(5), chosen.toList())
        assertEquals("cancelled", 0, cancels.get())
    }

    /**
     * Below the files, "Not in this release" answers that none of them is the one: Down from the last file reaches it, and
     * OK on it answers so, which neither chooses a file nor stops the play. Asked to choose (Choose file), there is no such answer.
     */
    @Test
    fun notInThisReleaseFollowsTheFilesAndAnswersTheQuestion() {
        state = choosing
        show()
        waitForFocusOn("Harbor.Lights.2-6.mp4")
        repeat(3) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        waitForFocusOn("Not in this release")
        SystemClock.sleep(OkGuard.WINDOW_MS)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("not in the release", 1, notInRelease.get())
        assertEquals("chosen", emptyList<Int>(), chosen.toList())
        assertEquals("cancelled", 0, cancels.get())

        state = choosing.copy(prompt = "Choose the file to play as S02E01:", asked = true)
        compose.waitForIdle()
        compose.onNodeWithText("Choose the file to play as S02E01:").assertExists()
        compose.onNodeWithText("Not in this release").assertDoesNotExist()
    }

    /** A phone used with a D-pad or a keyboard: the question takes the focus on its first file too. */
    @Test
    fun onAPhoneWithAKeyboardTheQuestionTakesTheFocus() {
        state = choosing
        show(FormFactor.Phone)
        waitForFocusOn("Harbor.Lights.2-6.mp4")
    }

    @Test
    fun aFailedPlayFocusesItsSearch() {
        state = PlayState.Failed("Harbor Lights", "S02E06", listOf("Harbor.Lights.S02E06.720p.HDTV.x264-NZST" to "Articles missing"), notTried = 0, search = "harbor lights s02e06")
        show()
        waitForFocusOn("Search “harbor lights s02e06”")
    }

    /**
     * On a real TV (tv36), a play whose ten releases all failed lists more than the screen holds, above Search, which has
     * the focus at the bottom: Up reads up through the list to its first release, the best ranked, and Down goes back
     * through it to Search.
     */
    @Test
    fun upFromSearchReadsAFailedPlaysReleasesToTheFirst() {
        assumeTv()
        val failures = (1..10).map { "Harbor.Lights.S02E06.2160p.WEB-DL.DV.H.265-NZST$it" to "No video this device can play: 2160p Dolby Vision profile 7" }
        state = PlayState.Failed("Harbor Lights", "S02E06", failures, notTried = 0, search = "harbor lights s02e06")
        show()
        waitForFocusOn("Search “harbor lights s02e06”")
        val first = compose.onNodeWithText(failures.first().first)
        // Wholly on screen: none of it clipped away by the sheet's scrolling.
        fun shown() = first.fetchSemanticsNode().let { it.boundsInRoot.height >= it.size.height - 1 }
        assertFalse("the first release is on screen before any Up", shown())
        repeat(10) { if (!shown()) press(KeyEvent.KEYCODE_DPAD_UP) }
        assertTrue("the first release never came on screen", shown())
        repeat(10) { if (runCatching { compose.onNodeWithText("Search “harbor lights s02e06”").assertIsFocused() }.isFailure) press(KeyEvent.KEYCODE_DPAD_DOWN) }
        waitForFocusOn("Search “harbor lights s02e06”")
    }

    @Test
    fun noReleaseFocusesItsSearch() {
        state = PlayState.NoRelease("Harbor Lights", "S02E06", listOf("Searched 3 indexers" to "Nothing yet"), null, "harbor lights s02e06", emptyList())
        show()
        waitForFocusOn("Search “harbor lights s02e06”")
    }

    /**
     * On a real TV (tv36) each sheet keeps its text and buttons inside the TV's safe area, 27 dp from
     * the bottom: it is a window of its own, which the shell's padding doesn't reach.
     */
    @Test
    fun theSheetsKeepInsideTheSafeAreaOnATv() {
        assumeTv()
        val sheets = listOf(
            searching to "Hide",
            running to "Hide",
            PlayState.NoRelease("Harbor Lights", "S02E06", listOf("Searched 3 indexers" to "Nothing yet"), null, "harbor lights s02e06", emptyList()) to "Search “harbor lights s02e06”",
            PlayState.Failed("Harbor Lights", "S02E06", listOf("Harbor.Lights.S02E06.720p.HDTV.x264-NZST" to "Articles missing"), notTried = 0, search = "harbor lights s02e06") to "Search “harbor lights s02e06”",
            PlayState.Failed("Harbor Lights", "S02E06", listOf("Harbor.Lights.S02E06.720p.HDTV.x264-NZST" to "Articles missing"), notTried = 0) to "Close",
            PlayState.Failed("Harbor Lights", "S02E06", emptyList(), notTried = 0, noProvider = NoUsableProvider.AllOff) to NoUsableProvider.ACTION,
            choosing to "Harbor.Lights.2-6.mp4",
        )
        state = sheets.first().first
        show()
        val outside = sheets.flatMap { (sheet, first) ->
            state = sheet
            waitForFocusOn(first)
            windowOutsideTheSafeArea(compose.onNodeWithText(first).fetchSemanticsNode()).map { "${sheet::class.simpleName}: $it" }
        }
        assertEquals(emptyList<String>(), outside)
    }

    /**
     * "Plays on this device" is the ranker's word: beside the probe's failed step, which found no audio or video this
     * device plays, the sheet no longer says it. Any other step leaves it.
     */
    @Test
    fun aFailedProbeStepTakesAwayPlaysOnThisDevice() {
        state = running
        show(FormFactor.Phone)
        compose.onNodeWithText("✓ Plays on this device").assertExists()
        val opened = mapOf(PipelineStep.FetchNzb to StepStatus.Done(400), PipelineStep.CheckAvailability to StepStatus.Done(900), PipelineStep.Open to StepStatus.Done(300))
        state = running.copy(steps = opened + (PipelineStep.ProbeTracks to StepStatus.Failed(200, AttemptFailure.NoPlayableVideo(listOf("2160p Dolby Vision profile 7")))))
        compose.waitForIdle()
        compose.onNodeWithText("No video this device can play: 2160p Dolby Vision profile 7").assertExists()
        compose.onNodeWithText("✓ Plays on this device").assertDoesNotExist()
        state = running.copy(steps = mapOf(PipelineStep.FetchNzb to StepStatus.Failed(400, AttemptFailure.Passworded)))
        compose.waitForIdle()
        compose.onNodeWithText("✓ Plays on this device").assertExists()
    }

    @Test
    fun phonesKeepTheSheetAsItWas() {
        state = searching
        show(FormFactor.Phone)
        compose.onNodeWithText("Cancel").assertExists()
        compose.onNodeWithText("Hide").assertDoesNotExist()
    }
}
