package io.skjaere.usenet.app.link

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.link.PairAttempt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Add a TV on a phone (item 14, PtPhonePair, PtPhonePairFail) on a real device, through its screen: a TV in the test's
 * process takes pairings on this device's own address, its record advertised under an id of its own (L14). It is
 * listed; Yes pairs and both keep each other; No saves nothing on either side; the TV's Cancel has its words; the typed
 * address pairs; a phone behind another waits and says so; three failures get "too many tries"; and with nothing found
 * (a discovery that finds nothing, not the network) the address becomes the main way after 10 s. The phone's store is
 * the test's own, in memory; the names are invented.
 */
@RunWith(AndroidJUnit4::class)
class AddTvDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val phone = softwareKey()
    private val store = memoryLinkStore()
    private val tvs = CopyOnWriteArrayList<FakeTv>()
    private val others = CopyOnWriteArrayList<AutoCloseable>()
    private val links = TvLinks(
        scope,
        TvLinksDeps(store = store, identity = { phone }, finder = LinkNsdFinder(context), inFront = MutableStateFlow(true), name = "Test phone", app = "0.1.0"),
    )
    @Volatile private var done = false

    @After
    fun stop() {
        others.forEach { it.close() }
        tvs.forEach { it.close() }
        links.close()
        scope.cancel()
    }

    /** A TV whose Pair a phone page is open, deciding each question by [answer]. */
    private fun pairingTv(answer: Boolean?): FakeTv = FakeTv(context, inventedTvName("Living room TV")).also {
        it.answer = answer
        it.pairing()
        tvs += it
    }

    private fun show(deps: AddTvDeps = AddTvDeps.of(links, store)) {
        links.start()
        compose.setContent { AppTheme { AddTvScreen(onDone = { done = true }, onBack = {}, deps = deps) } }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, millis: Long = 15_000) = compose.waitUntil(millis) { exists(matcher) }

    private fun tap(matcher: SemanticsMatcher) {
        waitFor(matcher)
        compose.onNode(matcher).performClick()
    }

    /** The TV's row in TVs nearby, tapped. */
    private fun pick(tv: FakeTv) = tap(hasText(tv.name) and hasClickAction())

    /** The number the phone shows is the one the TV asks about, read digit by digit. */
    private fun numberShows(tv: FakeTv) {
        compose.waitUntil(15_000) { tv.questions.isNotEmpty() && exists(hasContentDescription(numberSpoken(tv.questions.last().number.digits))) }
    }

    private fun tvs() = runBlocking { store.tvs.first() }

    @Test
    fun aTvTakingPairingsIsListedAndYesPairsIt() {
        val tv = pairingTv(answer = true)
        show()
        pick(tv)
        numberShows(tv)
        assertTrue(exists(hasText(doesItShow(tv.name))))
        assertTrue("the words name the TV's Pair", exists(hasText(COMPARE_HOW)))
        tap(hasText(YES_IT_MATCHES) and hasClickAction())
        waitFor(hasText(isReady(tv.name)))
        assertEquals(listOf(tv.key.id), tvs().map { it.key })
        assertEquals(tv.name, tvs().single().name)
        assertEquals(listOf(phone.id), tv.saved.map { it.key })
        tap(hasText("Done") and hasClickAction())
        assertTrue(done)
    }

    /** [text] as TalkBack meets it: a heading, read out as it shows (a polite live region). */
    private fun says(text: String) = exists(
        hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading) and
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
    )

    /**
     * TalkBack (L43): each step is heard as it comes, so the number, the TV's Pair to choose next, and the end are heard,
     * not hunted for while the TV's question runs. The TV's Pair is the test's: until it comes, the phone says to choose it.
     */
    @Test
    fun eachStepIsHeardAsItComes() {
        val tv = pairingTv(answer = null)
        show()
        pick(tv)
        numberShows(tv)
        assertTrue("the number's question isn't heard", says(doesItShow(tv.name)))
        tap(hasText(YES_IT_MATCHES) and hasClickAction())
        waitFor(hasText(choosePairOn(tv.name)))
        assertTrue("Pair on the TV isn't heard", says(choosePairOn(tv.name)))
        tv.questions.last().ok()
        waitFor(hasText(isReady(tv.name)))
        assertTrue("the end isn't heard", says(isReady(tv.name)))
    }

    @Test
    fun noSavesNothingOnEitherSide() {
        val tv = pairingTv(answer = null)
        show()
        pick(tv)
        numberShows(tv)
        tap(hasText("No") and hasClickAction())
        waitFor(hasText(failureTitle(AddTvFailure.NoMatch, tv.name)))
        assertTrue(exists(hasText(failureText(AddTvFailure.NoMatch, tv.name)!!)))
        compose.waitUntil(5_000) { tv.ended.isNotEmpty() }
        assertTrue(tvs().isEmpty())
        assertTrue(tv.saved.isEmpty())
    }

    @Test
    fun cancelOnTheTvHasItsWords() {
        val tv = pairingTv(answer = false)
        show()
        pick(tv)
        waitFor(hasText(failureTitle(AddTvFailure.CancelledOnTv, tv.name)))
        assertTrue(tvs().isEmpty())
        assertTrue(exists(hasText("Try again") and hasClickAction()))
    }

    @Test
    fun theTypedAddressPairs() {
        val tv = pairingTv(answer = true)
        show(AddTvDeps.of(links, store).let { AddTvDeps(flowOf(Discovery(0, emptyList())), it.look, it.tvs, it.pair, it.save) })
        tap(hasText(ENTER_ADDRESS) and hasClickAction())
        waitFor(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextInput("${tv.address.hostAddress}:${tv.port}")
        tap(hasText("Connect") and hasClickAction())
        numberShows(tv)
        tap(hasText(YES_IT_MATCHES) and hasClickAction())
        waitFor(hasText(isReady(tv.name)))
        assertEquals(tv.address.hostAddress, tvs().single().address)
        assertEquals(tv.port, tvs().single().port)
    }

    @Test
    fun aPhoneBehindAnotherWaitsAndSaysSo() {
        val tv = pairingTv(answer = null)
        // Another phone is at the TV's question first.
        val first = PhonePairing(InetSocketAddress(tv.address, tv.port), "Pixel tablet", answer = null).also { others += it }
        compose.waitUntil(15_000) { tv.questions.isNotEmpty() }
        show()
        pick(tv)
        waitFor(hasText(waitingWords(tv.name)))
        // The first one goes: this phone's turn.
        first.close()
        compose.waitUntil(15_000) { tv.questions.size == 2 }
        numberShows(tv)
        assertTrue(first.outcome() !is PairAttempt.Result.Paired)
    }

    @Test
    fun threeFailuresGetTooManyTries() {
        val tv = pairingTv(answer = null)
        show()
        pick(tv)
        repeat(3) { tried ->
            compose.waitUntil(15_000) { tv.questions.size == tried + 1 }
            numberShows(tv)
            tap(hasText("No") and hasClickAction())
            waitFor(hasText(failureTitle(AddTvFailure.NoMatch, tv.name)))
            compose.waitUntil(5_000) { tv.ended.size == tried + 1 }
            tap(hasText("Try again") and hasClickAction())
        }
        waitFor(hasText(failureTitle(AddTvFailure.TooMany, tv.name)))
        assertEquals(3, tv.questions.size)
        assertTrue(tvs().isEmpty())
    }

    @Test
    fun nothingFoundIn10sMakesTheAddressTheMainWay() {
        val deps = AddTvDeps(
            discovery = MutableStateFlow(Discovery(0, emptyList())), look = { AutoCloseable {} }, tvs = flowOf(emptyList()),
            pair = { _, _ -> PairAttempt.Result.Failed(io.skjaere.usenet.link.ConnectFailure.Unreachable) }, save = { _, _, _, _ -> false },
        )
        val shown = System.nanoTime()
        show(deps)
        waitFor(hasText(LOOKING))
        assertFalse(exists(hasText(NO_TVS_YET)))
        // Before its time, the address is the way for networks that hide TVs, under "Not listed?".
        assertTrue(exists(hasText(NOT_LISTED)))
        waitFor(hasText(NO_TVS_YET), millis = 20_000)
        val after = (System.nanoTime() - shown) / 1_000_000
        assertTrue("shown after $after ms", after >= 9_500)
        assertTrue(exists(hasText(SAME_NETWORK)))
        assertTrue(exists(hasText(STILL_LOOKING)))
        // Then the main way on: no "Not listed?" above it, and the only button.
        assertFalse(exists(hasText(NOT_LISTED)))
        assertEquals(1, compose.onAllNodes(hasClickAction() and hasText(ENTER_ADDRESS)).fetchSemanticsNodes().size)
    }
}
