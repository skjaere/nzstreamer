package io.skjaere.usenet.app.setup

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.skjaere.usenet.app.HomeRoute
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.settings.IndexerTest
import io.skjaere.usenet.app.tabsFor
import io.skjaere.usenet.app.tv.Remote
import io.skjaere.usenet.app.tv.TvShell
import io.skjaere.usenet.app.tv.outsideTheSafeArea
import io.skjaere.usenet.app.tv.setTvContent
import io.skjaere.usenet.app.tv.words
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.handoff.SETUP_NOT_ENCRYPTED
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.handoff.ProviderDraft
import io.skjaere.usenet.handoff.SetupChange
import io.skjaere.usenet.handoff.SetupChanges
import io.skjaere.usenet.handoff.SetupEvent
import io.skjaere.usenet.handoff.SetupMode
import io.skjaere.usenet.handoff.SetupOutcome
import io.skjaere.usenet.handoff.SetupSection
import io.skjaere.usenet.handoff.SetupSlot
import io.skjaere.usenet.handoff.SetupStep
import io.skjaere.usenet.metadata.KeyCheck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * The phone setup's page in the TV shell (forced on the phone emulator), over fake sessions and tests: each state's
 * first focus and Back, the review's OK guard, the QR code reading as the address shown, no password or whole key in
 * any node TalkBack could read, and everything inside the TV's safe area.
 */
@RunWith(AndroidJUnit4::class)
class PhoneSetupScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val remote = Remote(compose)
    private lateinit var nav: NavHostController
    private val done = AtomicInteger()

    @Serializable
    private data object SetupPage

    @Before
    fun remote() {
        instrumentation.setInTouchMode(false)
    }

    @After
    fun touch() {
        instrumentation.setInTouchMode(true)
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, ms: Long = 5_000) = compose.waitUntil(ms) { exists(matcher) }

    private val part = { name: String -> SemanticsMatcher.expectValue(PhoneSetupPart, name) }

    /** The page over Home, opened as the app opens it; Done counts and leaves, as AppNav's does. */
    private fun show(fakes: SetupFakes) {
        val playback = instrumentation.targetContext.container.playback
        compose.setTvContent {
            nav = rememberNavController()
            TvShell(nav, tabsFor(FormFactor.Tv), playback) {
                NavHost(nav, startDestination = HomeRoute) {
                    composable<HomeRoute> { Text("Home of the test") }
                    composable<SetupPage> {
                        PhoneSetupScreen(onDone = { done.incrementAndGet(); nav.popBackStack() }, onBack = { nav.popBackStack() }, deps = fakes.deps)
                    }
                }
            }
        }
        compose.runOnIdle { nav.navigate(SetupPage) }
        compose.waitForIdle()
    }

    private fun waiting(fakes: SetupFakes): SetupFakes {
        show(fakes)
        waitFor(part(ADDRESS))
        return fakes
    }

    /** What [changes] brought, on the review, its first focus on Save. */
    private fun reviewing(fakes: SetupFakes = SetupFakes(), changes: SetupChanges = SetupFakes.ALL): SetupFakes {
        waiting(fakes)
        fakes.receive(changes)
        waitFor(hasText(PHONE_SETUP_REVIEW))
        focusOn(PHONE_SETUP_SAVE)
        return fakes
    }

    /** The button reading [words] exactly takes the focus. */
    private fun focusOn(words: String, ms: Long = 5_000) {
        val button = hasText(words) and hasClickAction()
        try {
            compose.waitUntil(ms) { remote.isFocused(button) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("focus never on \"$words\"; it is on \"${remote.focused()}\"", e)
        }
    }

    private fun button(words: String) = hasText(words) and hasClickAction()

    /** OK once the review's guard has passed. */
    private fun okAfterTheGuard(): String {
        Thread.sleep(1_100)
        return remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    private fun texts(): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            out += node.words()
            node.children.forEach(::walk)
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return out
    }

    @Test
    fun waitingShowsTheCodeAndItsAddressWithNothingToFocus() {
        val fakes = waiting(SetupFakes())
        val address = compose.onNode(part(ADDRESS)).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        // The authority on a line of its own, the code in fours on the next.
        assertEquals(fakes.session.display.replaceFirst("/", "/\n"), address)
        assertTrue(address, address.startsWith("192.168.1.23:41234/\nk7m2-q9xd-"))
        // Nothing saved: the wizard's steps.
        assertTrue("the wizard's step", exists(hasText("2  $PHONE_SETUP_FOLLOW")))
        assertTrue(exists(hasText("3  $PHONE_SETUP_SAVE_HERE")))
        assertTrue("the warning", exists(hasText(SETUP_NOT_ENCRYPTED)))
        assertTrue("the help line", exists(hasText(PHONE_SETUP_SAME_NETWORK)))
        assertTrue("the countdown", exists(hasText("Waiting for your phone · the code works for 10:00") or hasText("Waiting for your phone · the code works for 9:5", substring = true)))
        assertEquals("nothing on the page takes the focus", "(nothing)", remote.focused())
        assertEquals(emptyList<String>(), compose.outsideTheSafeArea())
    }

    /** What a phone's camera reads is the address the page shows, typed as it shows it (the server ignores the dashes). */
    @Test
    fun theQrCodeReadsAsTheAddressShown() {
        waiting(SetupFakes())
        val shown = compose.onNode(part(ADDRESS)).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        val bitmap = compose.onNode(part(QR)).captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        val read = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))).text
        assertEquals("http://" + shown.replace("\n", "").replace("-", ""), read)
    }

    @Test
    fun backFromWaitingLeavesThePageAndClosesTheCode() {
        val fakes = waiting(SetupFakes())
        remote.press(KeyEvent.KEYCODE_BACK)
        waitFor(hasText("Home of the test"))
        compose.waitUntil(5_000) { fakes.session.closed }
    }

    @Test
    fun wrongTriesAndTheOpenerAreSaid() {
        val fakes = waiting(SetupFakes())
        fakes.session.events(SetupEvent.WrongTry(1))
        fakes.session.events(SetupEvent.Opened(SetupFakes.PHONE))
        waitFor(hasText("1 of 5 wrong attempts"))
        waitFor(hasText("Page opened on 192.168.1.40"))
    }

    /**
     * A test from the phone's page shows while it runs, then how each card went, by its place: a line to read, not a stop
     * for the focus, not read out on its own. Above it, where the phone is in the wizard.
     */
    @Test
    fun aTestFromThePhoneShowsOnWaiting() {
        val fakes = waiting(SetupFakes())
        fakes.session.events(SetupEvent.Opened(SetupFakes.PHONE))
        waitFor(hasText("On your phone: step 1 of 4, providers"))
        fakes.session.events(SetupEvent.TestStarted(SetupSlot(SetupSection.Provider, 1)))
        waitFor(hasText(PHONE_SETUP_TESTING))
        fakes.session.events(SetupEvent.TestFinished(SetupSlot(SetupSection.Provider, 1), SetupOutcome.Passed))
        fakes.session.events(SetupEvent.TestFinished(SetupSlot(SetupSection.Provider, 2), SetupOutcome.Failed))
        fakes.session.events(SetupEvent.StepChanged(SetupStep.Indexers))
        fakes.session.events(SetupEvent.TestFinished(SetupSlot(SetupSection.Indexer, 1), SetupOutcome.Failed))
        fakes.session.events(SetupEvent.TestFinished(SetupSlot.Tmdb, SetupOutcome.Passed))
        val line = "Tested from your phone: Provider 1 works · Provider 2 failed · Indexer 1 failed · TMDB key works"
        waitFor(hasText(line))
        waitFor(hasText("On your phone: step 2 of 4, indexers"))
        assertFalse(exists(hasText(PHONE_SETUP_TESTING)))
        // Below "Page opened on …".
        val opened = compose.onNode(hasText("Page opened on 192.168.1.40")).fetchSemanticsNode().boundsInRoot
        assertTrue("above the line it follows", compose.onNode(hasText(line)).fetchSemanticsNode().boundsInRoot.top >= opened.bottom)
        assertFalse("a live region", compose.onNode(hasText(line)).fetchSemanticsNode().config.contains(SemanticsProperties.LiveRegion))
        assertEquals("nothing on the page takes the focus", "(nothing)", remote.focused())
        assertEquals(emptyList<String>(), compose.outsideTheSafeArea())
        assertFalse("the code closed", fakes.session.closed)
    }

    /** Next or Back on the phone stopped its test: Waiting stops saying it is testing, and says how the cards went before it. */
    @Test
    fun aTestThePhoneStoppedNoLongerShowsOnWaiting() {
        val fakes = waiting(SetupFakes())
        val p1 = SetupSlot(SetupSection.Provider, 1)
        fakes.session.events(SetupEvent.Opened(SetupFakes.PHONE))
        fakes.session.events(SetupEvent.TestStarted(p1))
        waitFor(hasText(PHONE_SETUP_TESTING))
        fakes.session.events(SetupEvent.TestFinished(p1, SetupOutcome.Passed))
        fakes.session.events(SetupEvent.TestStarted(p1))
        fakes.session.events(SetupEvent.StepChanged(SetupStep.Indexers))
        fakes.session.events(SetupEvent.TestStopped(p1))
        waitFor(hasText("On your phone: step 2 of 4, indexers"))
        compose.waitUntil("the testing line goes", 5_000) { !exists(hasText(PHONE_SETUP_TESTING)) }
        assertTrue(exists(hasText("Tested from your phone: Provider 1 works")))
        assertFalse("the code closed", fakes.session.closed)
    }

    /** With a provider and an indexer saved the phone gets the edit page, and Waiting says so. */
    @Test
    fun withSourcesSavedWaitingSaysToChangeThemOnThePhone() {
        val fakes = SetupFakes()
        runBlocking {
            fakes.settings.saveProvider(ProviderRecord("", "Invented", "news.example.net", 563, "someone"), SetupFakes.PASSWORD)
            fakes.settings.saveIndexer(null, "Invented", "https://indexer.example.org/api", SetupFakes.INDEXER_KEY)
        }
        waiting(fakes)
        assertEquals(SetupMode.Edit, fakes.session.opening.mode)
        waitFor(hasText("2  $PHONE_SETUP_CHANGE"))
        fakes.session.events(SetupEvent.Opened(SetupFakes.PHONE))
        waitFor(hasText("On your phone: editing"))
        assertEquals("nothing on the page takes the focus", "(nothing)", remote.focused())
        assertEquals(emptyList<String>(), compose.outsideTheSafeArea())
    }

    @Test
    fun withoutANetworkItSaysSoWithNothingToFocus() {
        val fakes = SetupFakes()
        fakes.addresses.value = null
        show(fakes)
        waitFor(hasText(PHONE_SETUP_NO_NETWORK))
        assertEquals("(nothing)", remote.focused())
        assertFalse("kept on with no network", screenKeptOn())
        remote.press(KeyEvent.KEYCODE_BACK)
        waitFor(hasText("Home of the test"))
    }

    @Test
    fun aClosedCodeOffersNewCodeFocused() {
        val fakes = waiting(SetupFakes())
        fakes.session.events(SetupEvent.Locked)
        waitFor(hasText(PHONE_SETUP_LOCKED))
        remote.waitForFocus("New code")
        assertEquals(emptyList<String>(), compose.outsideTheSafeArea())
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(part(ADDRESS))
        assertEquals(2, fakes.sessions.size)
    }

    @Test
    fun aChangedNetworkOffersNewCodeFocused() {
        val fakes = waiting(SetupFakes())
        fakes.addresses.value = SetupFakes.v4("192.168.7.7")
        waitFor(hasText(PHONE_SETUP_NETWORK_CHANGED))
        remote.waitForFocus("New code")
        remote.press(KeyEvent.KEYCODE_BACK)
        waitFor(hasText("Home of the test"))
    }

    @Test
    fun anExpiredCodeOffersNewCodeFocused() {
        waiting(SetupFakes(lifetimeMillis = 2_000))
        waitFor(hasText(PHONE_SETUP_EXPIRED), 6_000)
        remote.waitForFocus("New code")
    }

    /** It arrives unprompted, the focus on Save: an OK in its first second does nothing. */
    @Test
    fun theReviewTakesTheFocusBehindTheGuard() {
        val fakes = reviewing()
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        assertTrue("an OK inside the guard tested: ${fakes.tested}", fakes.tested.isEmpty())
        assertTrue(exists(button(PHONE_SETUP_SAVE)))
        okAfterTheGuard()
        compose.waitUntil(5_000) { fakes.tested.isNotEmpty() }
    }

    /**
     * As the TV's review mockup has it: where it came from, a summary, each change with whether the phone tested it, and
     * what stays as it is; whoever sent it, the review says from where ("Page opened on …" shows only while waiting,
     * which a quick sender skips).
     */
    @Test
    fun theReviewSaysWhatChangesAndWhereItWasSentFrom() {
        val fakes = SetupFakes()
        val main = runBlocking {
            fakes.settings.saveProvider(ProviderRecord("", "Invented main", "news.example.net", 563, "someone"), SetupFakes.PASSWORD)
        }
        val block = runBlocking { fakes.settings.saveProvider(ProviderRecord("", "Invented block", "block.example.net", 563, ""), "") }
        runBlocking { fakes.settings.saveIndexer(null, "Invented indexer", "https://indexer.example.org/api", SetupFakes.INDEXER_KEY) }
        reviewing(
            fakes,
            SetupChanges(
                listOf(
                    SetupChange(main, ProviderDraft("Invented main", "news.example.net", 563, true, "someone", "", 8), removed = false),
                    SetupChange(block, ProviderDraft("Invented block", "block.example.net", 563, true, "", "", 8), removed = true),
                    SetupChange(null, ProviderDraft("", "fill.example.net", null, true, "me2", SetupFakes.PASSWORD, null), removed = false),
                ),
                emptyList(),
                null,
            ),
        )
        assertTrue(exists(hasText("Sent from 192.168.1.40")))
        assertTrue(exists(hasText("1 new · 1 to remove")))
        assertTrue(exists(hasText("fill.example.net:563 · TLS · me2 · password typed")))
        assertTrue(exists(hasText(TESTED_AT_SAVE)))
        assertTrue(exists(hasText("block.example.net:563")))
        assertTrue(exists(hasText("Unchanged: Invented main, Invented indexer.")))
        assertTrue(exists(hasText(PHONE_SETUP_REVIEW_NOTE)))
        assertEquals(emptyList<String>(), compose.outsideTheSafeArea())
    }

    @Test
    fun eachItemIsAStopForTheFocusThatDoesNothing() {
        val fakes = reviewing()
        val right = remote.pressUntil(KeyEvent.KEYCODE_DPAD_RIGHT, "Usenet provider", max = 3)
        assertTrue("Right from Save reaches the changes; focus on \"$right\"", "Usenet provider" in right)
        assertTrue(remote.isFocused(part("provider 1")))
        Thread.sleep(1_100)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        assertTrue(exists(hasText(PHONE_SETUP_REVIEW)))
        assertTrue(fakes.tested.isEmpty())
        assertTrue(remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "TMDB key", max = 4).contains("TMDB key"))
        assertTrue(remote.isFocused(part("tmdb 1")))
    }

    @Test
    fun noNodeHoldsThePasswordOrAWholeKey() {
        reviewing()
        val shown = texts()
        listOf(SetupFakes.PASSWORD, SetupFakes.INDEXER_KEY, SetupFakes.TMDB_KEY).forEach { secret ->
            assertTrue("$secret on screen", shown.none { secret in it })
        }
        assertTrue(shown.any { "•••• 3333" in it })
        okAfterTheGuard()
        focusOn(PHONE_SETUP_DONE)
        val after = texts()
        listOf(SetupFakes.PASSWORD, SetupFakes.INDEXER_KEY, SetupFakes.TMDB_KEY).forEach { secret ->
            assertTrue("$secret on screen", after.none { secret in it })
        }
    }

    @Test
    fun backOnTheReviewAsksFirstWithKeepFocused() {
        val fakes = reviewing()
        remote.press(KeyEvent.KEYCODE_BACK)
        waitFor(hasText(PHONE_SETUP_DISCARD_ASK))
        remote.waitForFocus("Keep")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { !exists(hasText(PHONE_SETUP_DISCARD_ASK)) }
        assertTrue(exists(hasText(PHONE_SETUP_REVIEW)))
        remote.press(KeyEvent.KEYCODE_BACK)
        waitFor(hasText(PHONE_SETUP_DISCARD_ASK))
        remote.waitForFocus("Keep")
        val discard = remote.pressUntil(KeyEvent.KEYCODE_DPAD_RIGHT, "Discard", max = 2)
        assertTrue("Discard beside Keep; focus on \"$discard\"", "Discard" in discard)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Home of the test"))
        assertEquals(emptyList<Any>(), runBlocking { fakes.settings.providers.first() })
        assertTrue(fakes.tested.isEmpty())
    }

    /** Nothing is saved before every test has passed: the review again, as it says. */
    @Test
    fun backWhileTestingStopsTheTestsAndReturnsToTheReview() {
        val fakes = SetupFakes()
        val never = CompletableDeferred<IndexerTest>()
        fakes.indexer = { never.await() }
        reviewing(fakes)
        assertTrue(okAfterTheGuard().contains("Testing…"))
        compose.waitUntil(5_000) { "provider" in fakes.tested }
        remote.press(KeyEvent.KEYCODE_BACK)
        waitFor(button(PHONE_SETUP_SAVE))
        focusOn(PHONE_SETUP_SAVE)
        assertFalse(exists(hasText(PHONE_SETUP_DISCARD_ASK)))
        assertEquals("saved while testing", emptyList<Any>(), runBlocking { fakes.settings.providers.first() })
    }

    /** A failure on the TV holds the save: its Try again focused, Save anyway and Leave out beside it, nothing saved. */
    @Test
    fun aFailureOnTheTvFocusesItsTryAgainAndSavesNothing() {
        val fakes = SetupFakes()
        fakes.indexer = { IndexerTest.Failed("No answer within 15 s") }
        reviewing(fakes)
        okAfterTheGuard()
        compose.waitUntil(5_000) { remote.isFocused(part(retryPart("indexer 1"))) }
        assertTrue(exists(button(PHONE_SETUP_SAVE_ANYWAY)) && exists(button(PHONE_SETUP_LEAVE_OUT)))
        assertFalse("Save while one fails", exists(button(PHONE_SETUP_SAVE)))
        assertEquals(emptyList<Any>(), runBlocking { fakes.settings.providers.first() })
        assertEquals(emptyList<String>(), compose.outsideTheSafeArea())
        // Left out, none is left: Save takes the focus, and saves the rest.
        val leaveOut = remote.pressUntil(KeyEvent.KEYCODE_DPAD_RIGHT, PHONE_SETUP_LEAVE_OUT, max = 3)
        assertTrue("focus on \"$leaveOut\"", PHONE_SETUP_LEAVE_OUT in leaveOut)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        focusOn(PHONE_SETUP_SAVE)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        focusOn(PHONE_SETUP_DONE)
        assertEquals(1, runBlocking { fakes.settings.providers.first().size })
        assertEquals(0, runBlocking { fakes.settings.indexers.first().size })
        assertTrue(exists(hasText(PHONE_SETUP_LEFT_OUT, substring = true)))
    }

    /**
     * The only provider removed, a new one in its place: while the new one fails on the TV (and once it is left out),
     * nothing would play after Save, and the results say so.
     */
    @Test
    fun theOnlyProviderWarningFollowsWhatSaveWillDo() {
        val fakes = SetupFakes()
        val old = runBlocking { fakes.settings.saveProvider(ProviderRecord("", "Invented old", "old.example.net", 563, "someone"), SetupFakes.PASSWORD) }
        fakes.provider = { f, password -> SetupFakes.result(f, password, ok = false) }
        reviewing(
            fakes,
            SetupChanges(
                listOf(
                    SetupChange(old, ProviderDraft("Invented old", "old.example.net", 563, true, "someone", "", 8), removed = true),
                    SetupChange(null, ProviderDraft("", "news.example.net", null, true, "alice", SetupFakes.PASSWORD, null), removed = false),
                ),
                emptyList(),
                null,
            ),
        )
        assertFalse("a new one is in its place", exists(hasText(ONLY_PROVIDER_REMOVED)))
        okAfterTheGuard()
        waitFor(button(PHONE_SETUP_LEAVE_OUT))
        assertTrue(exists(hasText(ONLY_PROVIDER_REMOVED)))
        compose.onNode(button(PHONE_SETUP_LEAVE_OUT)).performClick()
        waitFor(button(PHONE_SETUP_SAVE))
        assertTrue(exists(hasText(ONLY_PROVIDER_REMOVED)))
    }

    /**
     * Two failures, the focus on the first one's Try again: Leave out on the second takes its buttons away with the
     * focus on them. The focus goes back to the one still failing, not to wherever the shell puts a lost focus (Send
     * again, where a stray OK would throw the review away).
     */
    @Test
    fun leavingOutAnotherFailureGivesTheFocusToTheOneStillFailing() {
        val fakes = SetupFakes()
        fakes.provider = { f, password -> SetupFakes.result(f, password, ok = false) }
        fakes.indexer = { IndexerTest.Failed("No answer within 15 s") }
        reviewing(fakes)
        okAfterTheGuard()
        val provider = part(retryPart("provider 1"))
        compose.waitUntil(5_000) { remote.isFocused(provider) }
        // Down to the indexer's row, then its buttons, and its Leave out.
        assertTrue(remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "Indexer", max = 6).contains("Indexer"))
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        val leaveOut = remote.pressUntil(KeyEvent.KEYCODE_DPAD_RIGHT, PHONE_SETUP_LEAVE_OUT, max = 3)
        assertTrue("focus on \"$leaveOut\"", PHONE_SETUP_LEAVE_OUT in leaveOut)
        assertFalse(remote.isFocused(provider))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText(PHONE_SETUP_LEFT_OUT)) }
        // Settled: the shell's own handling of a lost focus has run too.
        Thread.sleep(500)
        compose.waitForIdle()
        assertTrue("focus on \"${remote.focused()}\"", remote.isFocused(provider))
    }

    /** A Try again that fails again leaves the focus on it, not on the first failing item above. */
    @Test
    fun aTryAgainThatFailsAgainKeepsTheFocus() {
        val fakes = SetupFakes()
        fakes.provider = { f, password -> SetupFakes.result(f, password, ok = false) }
        // As TMDB answers, after a while: the page shows the test running in between.
        fakes.tmdb = { delay(500); KeyCheck.Invalid }
        reviewing(fakes)
        okAfterTheGuard()
        val provider = part(retryPart("provider 1"))
        val tmdb = part(retryPart("tmdb 1"))
        compose.waitUntil(5_000) { remote.isFocused(provider) }
        // Down the rows to the TMDB key's, then its buttons.
        assertTrue(remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, "TMDB key", max = 6).contains("TMDB key"))
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        repeat(2) { if (!remote.isFocused(tmdb)) remote.press(KeyEvent.KEYCODE_DPAD_LEFT) }
        assertTrue("the TMDB key's Try again; focus on \"${remote.focused()}\"", remote.isFocused(tmdb))
        val tries = fakes.tested.count { it == "tmdb" }
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { fakes.tested.count { it == "tmdb" } == tries + 1 && exists(button(PHONE_SETUP_LEAVE_OUT)) && !exists(hasText("Testing…")) }
        // Settled: what the results' first focus does has been done.
        Thread.sleep(500)
        compose.waitForIdle()
        assertTrue("focus on \"${remote.focused()}\"", remote.isFocused(tmdb))
    }

    @Test
    fun onceSavedDoneIsFocusedAndLeaves() {
        reviewing()
        okAfterTheGuard()
        focusOn(PHONE_SETUP_DONE)
        assertTrue(exists(hasText(PHONE_SETUP_SAVED, substring = true)))
        assertEquals(emptyList<String>(), compose.outsideTheSafeArea())
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Home of the test"))
        assertEquals(1, done.get())
    }

    /** What was saved, a form under the page would write its old values over: Back is Done. */
    @Test
    fun backOnceSavedIsDone() {
        reviewing()
        okAfterTheGuard()
        focusOn(PHONE_SETUP_DONE)
        remote.press(KeyEvent.KEYCODE_BACK)
        waitFor(hasText("Home of the test"))
        assertEquals(1, done.get())
    }

    /** Saved, Send again, then Back on the new code, must close a form under the page as Done does. */
    @Test
    fun afterASaveBackFromSendAgainLeavesAsDone() {
        val fakes = reviewing()
        okAfterTheGuard()
        focusOn(PHONE_SETUP_DONE)
        assertTrue(remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, PHONE_SETUP_SEND_AGAIN, max = 2).contains(PHONE_SETUP_SEND_AGAIN))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(part(ADDRESS))
        assertEquals(2, fakes.sessions.size)
        assertEquals("a saved arrival isn't the next page's", null, fakes.session.opening.from)
        remote.press(KeyEvent.KEYCODE_BACK)
        waitFor(hasText("Home of the test"))
        assertEquals(1, done.get())
    }

    /** Send again before saving: the next code's page starts from what arrived. Discarding what came next leaves as Done once saved. */
    @Test
    fun sendAgainStartsFromWhatArrivedAndAfterASaveDiscardLeavesAsDone() {
        val fakes = reviewing()
        assertTrue(remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, PHONE_SETUP_SEND_AGAIN, max = 2).contains(PHONE_SETUP_SEND_AGAIN))
        Thread.sleep(1_100)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(part(ADDRESS))
        assertEquals(SetupFakes.ALL, fakes.session.opening.from)
        fakes.receive()
        waitFor(hasText(PHONE_SETUP_REVIEW))
        focusOn(PHONE_SETUP_SAVE)
        okAfterTheGuard()
        focusOn(PHONE_SETUP_DONE)
        remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, PHONE_SETUP_SEND_AGAIN, max = 2)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(part(ADDRESS))
        fakes.receive()
        waitFor(hasText(PHONE_SETUP_REVIEW))
        focusOn(PHONE_SETUP_SAVE)
        remote.press(KeyEvent.KEYCODE_BACK)
        waitFor(hasText(PHONE_SETUP_DISCARD_ASK))
        remote.waitForFocus("Keep")
        remote.pressUntil(KeyEvent.KEYCODE_DPAD_RIGHT, "Discard", max = 2)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Home of the test"))
        assertEquals(1, done.get())
    }

    /** What arrived changes nothing: it says so, Done focused, which leaves without a word. */
    @Test
    fun nothingChangedOffersDone() {
        val fakes = SetupFakes()
        val main = runBlocking { fakes.settings.saveProvider(ProviderRecord("", "Invented", "news.example.net", 563, ""), "") }
        waiting(fakes)
        fakes.receive(SetupChanges(listOf(SetupChange(main, ProviderDraft("Invented", "news.example.net", 563, true, "", "", 8), removed = false)), emptyList(), null))
        waitFor(hasText(SetupReview.NOTHING_CHANGED))
        focusOn(PHONE_SETUP_DONE)
        remote.press(KeyEvent.KEYCODE_BACK)
        waitFor(hasText("Home of the test"))
        assertFalse(exists(hasText(PHONE_SETUP_DISCARD_ASK)))
        assertEquals(0, done.get())
    }

    /** Whether anything in the page's window keeps the screen on, as Modifier.keepScreenOn does through its view. */
    private fun screenKeptOn(): Boolean = compose.runOnIdle {
        fun views(view: View): List<View> = listOf(view) + ((view as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())
        views(compose.activity.window.decorView).any { it.keepScreenOn }
    }

    /**
     * On while the phone is being filled in and while what it sent waits to be saved (a screensaver would stop the
     * page); off once it is saved, so a TV left there can sleep.
     */
    @Test
    fun theScreenStaysOnUntilWhatArrivedIsSaved() {
        val fakes = SetupFakes()
        fakes.indexer = { IndexerTest.Failed("No answer within 15 s") }
        waiting(fakes)
        assertTrue("waiting", screenKeptOn())
        fakes.receive()
        waitFor(hasText(PHONE_SETUP_REVIEW))
        assertTrue("on the review", screenKeptOn())
        okAfterTheGuard()
        waitFor(button(PHONE_SETUP_LEAVE_OUT))
        assertTrue("with a failure, nothing saved", screenKeptOn())
        compose.onNode(button(PHONE_SETUP_LEAVE_OUT)).performClick()
        waitFor(button(PHONE_SETUP_SAVE))
        compose.onNode(button(PHONE_SETUP_SAVE)).performClick()
        waitFor(button(PHONE_SETUP_DONE))
        compose.waitUntil(2_000) { !screenKeptOn() }
    }

    @Test
    fun theScreenMayGoOffOnceTheCodeHasEndedOrWithoutANetwork() {
        waiting(SetupFakes(lifetimeMillis = 2_000))
        waitFor(hasText(PHONE_SETUP_EXPIRED), 6_000)
        assertFalse("expired", screenKeptOn())
    }

    @Test
    fun theReviewIsInsideTheSafeArea() {
        reviewing()
        assertEquals(emptyList<String>(), compose.outsideTheSafeArea())
    }

    /** The countdown's words: minutes and seconds, rounded up. */
    @Test
    fun theCountdownReadsAsMinutesAndSeconds() {
        assertEquals("10:00", countdown(600_000))
        assertEquals("9:41", countdown(581_000))
        assertEquals("0:01", countdown(1))
        assertEquals("0:00", countdown(0))
    }

}
