package io.skjaere.usenet.app.link

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.skjaere.usenet.link.PairOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The TV's Pair a phone page (PtTvPair, L22, L41) as states, over a listener that a test drives. The names are invented. */
@OptIn(ExperimentalCoroutinesApi::class)
class PairPageTest {
    @AfterEach
    fun main() = Dispatchers.resetMain()

    private val tv = InetAddress.getByName("192.168.1.20") as Inet4Address
    private val answers = mutableListOf<Pair<String, Boolean>>()

    private fun question(name: String = "Galaxy phone", digits: String = "482913") =
        PhoneQuestion(name, "192.168.1.57", digits) { ok -> answers += name to ok }

    @Test
    fun `starting until the listener is up, then waiting at its address`() {
        val page = PairPage()
        assertEquals(PairScreen.Starting, page.screen)
        assertEquals(PairScreen.Waiting("192.168.1.20"), page.copy(listener = TvListener.Listening(tv, PortChoice.DEFAULT)).screen)
        assertEquals(PairScreen.Waiting("192.168.1.20:30123"), page.copy(listener = TvListener.Listening(tv, 30_123)).screen, "ip:port off the default port (L12)")
        assertEquals(PairScreen.NoNetwork, page.copy(listener = TvListener.NoNetwork).screen)
        assertEquals(PairScreen.NoKey, page.copy(listener = TvListener.NoKey).screen, "no link key: said so, not a blank page")
        assertFalse(page.copy(listener = TvListener.NoKey).keepsScreenOn)
    }

    @Test
    fun `a phone asks, and its question counts down from when it was asked`() {
        val asked = question()
        val page = PairPage(listener = TvListener.Listening(tv, PortChoice.DEFAULT)).heard(PairPageEvent.Asking(asked), now = 10_000)
        val screen = assertIs<PairScreen.Asking>(page.screen)
        assertSame(asked, screen.question)
        assertFalse(screen.another)
        assertEquals("Closes by itself in 0:54 if nobody answers.", closesIn(page.left(now = 16_000, questionMillis = 60_000)))
        assertEquals("Closes by itself in 0:01 if nobody answers.", closesIn(page.left(now = 69_400, questionMillis = 60_000)))
        assertEquals("Closes by itself in 0:00 if nobody answers.", closesIn(page.left(now = 71_000, questionMillis = 60_000)))
        assertEquals("482 913", numberShown(asked.digits))
        assertEquals("082 913", numberShown("082913"))
        assertEquals("0 8 2 9 1 3", numberSpoken("082913"), "read digit by digit (L43)")
    }

    /** L22: one more phone waits its turn, and the question says so while it does. */
    @Test
    fun `another phone waiting adds the question's line, and its leaving takes it away`() {
        var page = PairPage(listener = TvListener.Listening(tv, PortChoice.DEFAULT)).heard(PairPageEvent.Asking(question()), 0)
        page = page.heard(PairPageEvent.Waiting(another = true), 1)
        assertTrue(assertIs<PairScreen.Asking>(page.screen).another)
        page = page.heard(PairPageEvent.Waiting(another = false), 2)
        assertFalse(assertIs<PairScreen.Asking>(page.screen).another)
    }

    /** At 16 phones (or a write that failed) the TV can't keep a phone both said yes to: it says so, not waiting as if nothing happened. */
    @Test
    fun `a phone the TV couldn't keep is said so`() {
        val asked = question()
        val page = PairPage(listener = TvListener.Listening(tv, PortChoice.DEFAULT)).heard(PairPageEvent.Asking(asked), 0)
            .heard(PairPageEvent.Ended(asked, PairOutcome.NotSaved), 1)
        assertNotEquals<PairScreen>(PairScreen.Waiting("192.168.1.20"), page.screen, "back to waiting without a word")
        assertEquals(PairScreen.NotKept("Galaxy phone", full = false), page.screen, "a write that failed")
        assertEquals(PairScreen.NotKept("Galaxy phone", full = true), page.copy(phones = 16).screen, "16 kept already")
        assertEquals("Galaxy phone isn’t paired", notKeptTitle("Galaxy phone"))
        assertFalse(page.keepsScreenOn, "the TV may sleep on what came of it")
        // Another phone's question takes its place, and its own end goes back to waiting.
        val next = question("Pixel tablet")
        val asking = page.heard(PairPageEvent.Asking(next), 2)
        assertSame(next, assertIs<PairScreen.Asking>(asking.screen).question)
        assertEquals(PairScreen.Waiting("192.168.1.20"), asking.heard(PairPageEvent.Ended(next, PairOutcome.No), 3).screen)
    }

    @Test
    fun `a ceremony that ends without pairing goes back to waiting`() {
        val start = PairPage(listener = TvListener.Listening(tv, PortChoice.DEFAULT))
        for (outcome in PairOutcome.entries - PairOutcome.Paired - PairOutcome.NotSaved) {
            val asked = question()
            val page = start.heard(PairPageEvent.Asking(asked), 0).heard(PairPageEvent.Ended(asked, outcome), 1)
            assertEquals(PairScreen.Waiting("192.168.1.20"), page.screen, "$outcome")
        }
        // A phone that left before its number changes nothing.
        assertEquals(PairScreen.Waiting("192.168.1.20"), start.heard(PairPageEvent.Ended(null, PairOutcome.Left), 1).screen)
    }

    @Test
    fun `paired shows the phone, and a new question takes its place`() {
        val asked = question()
        var page = PairPage(listener = TvListener.Listening(tv, PortChoice.DEFAULT)).heard(PairPageEvent.Asking(asked), 0)
            .heard(PairPageEvent.Ended(asked, PairOutcome.Paired), 1)
        assertEquals(PairScreen.Paired("Galaxy phone", "192.168.1.57"), page.screen)
        // The phone that waited: its question shows at once, Done's OK guarded as it appears.
        val next = question("Pixel tablet", "120004")
        page = page.heard(PairPageEvent.Asking(next), 2)
        assertSame(next, assertIs<PairScreen.Asking>(page.screen).question)
    }

    /** L22, L41: 10 minutes after the page opened or its last ceremony ended, pairing closes and the page says so. */
    @Test
    fun `pairing closes by itself after its 10 minutes`() {
        val asked = question()
        val page = PairPage(listener = TvListener.Listening(tv, PortChoice.DEFAULT)).heard(PairPageEvent.Asking(asked), 0)
            .heard(PairPageEvent.Ended(asked, PairOutcome.TimedOut), 60_000)
            .heard(PairPageEvent.Closed, 660_000)
        assertEquals(PairScreen.Closed, page.screen)
        assertEquals("Pairing closed. Open Pair a phone again to pair.", PAIR_CLOSED)
        assertFalse(page.keepsScreenOn, "the screen may sleep again")
    }

    @Test
    fun `the screen kept on while the page waits or asks`() {
        val asked = question()
        val waiting = PairPage(listener = TvListener.Listening(tv, PortChoice.DEFAULT))
        assertTrue(PairPage().keepsScreenOn)
        assertTrue(waiting.keepsScreenOn)
        assertTrue(waiting.heard(PairPageEvent.Asking(asked), 0).keepsScreenOn)
        assertFalse(waiting.heard(PairPageEvent.Asking(asked), 0).heard(PairPageEvent.Ended(asked, PairOutcome.Paired), 1).keepsScreenOn)
        assertFalse(PairPage(listener = TvListener.NoNetwork).keepsScreenOn)
    }

    /** What the page's listener is given, and when, as the view model drives it. */
    private inner class Fixture(val test: TestScope) {
        val listener = MutableStateFlow<TvListener>(TvListener.Listening(tv, PortChoice.DEFAULT))
        val opened = mutableListOf<PairPageListener>()
        val closed = mutableListOf<PairPageListener>()
        val phones = MutableStateFlow(3)
        private val store = ViewModelStore()
        val deps = PairPhoneDeps(
            name = "Living room TV", listener = listener, open = { opened += it }, close = { closed += it },
            questionMillis = { 60_000 }, now = { test.testScheduler.currentTime }, phones = phones,
        )
        val vm: PairPhoneViewModel by lazy {
            Dispatchers.setMain(StandardTestDispatcher(test.testScheduler))
            ViewModelProvider.create(store, viewModelFactory { initializer { PairPhoneViewModel(deps) } })[PairPhoneViewModel::class]
        }

        fun clear() = store.clear()
    }

    @Test
    fun `pairing taken while the page is started, and the page hears it`() = runTest {
        val f = Fixture(this)
        f.vm.start()
        runCurrent()
        val page = f.opened.single()
        assertEquals(PairScreen.Waiting("192.168.1.20"), f.vm.page.value.screen)
        val asked = question()
        // From the listener's own thread, as the desk delivers it.
        page.heard(PairPageEvent.Asking(asked))
        runCurrent()
        assertSame(asked, assertIs<PairScreen.Asking>(f.vm.page.value.screen).question)
        f.vm.stop()
        assertSame(page, f.closed.single(), "the page stopped: pairing closed")
        f.clear()
    }

    /** Started again (back from Home): a new opening, the old one's question and outcome gone, a late word from it dropped. */
    @Test
    fun `a page started again starts afresh`() = runTest {
        val f = Fixture(this)
        f.vm.start()
        runCurrent()
        val first = f.opened.single()
        val asked = question()
        first.heard(PairPageEvent.Asking(asked))
        runCurrent()
        f.vm.stop()
        f.vm.start()
        runCurrent()
        assertEquals(2, f.opened.size)
        assertEquals(PairScreen.Waiting("192.168.1.20"), f.vm.page.value.screen)
        first.heard(PairPageEvent.Ended(asked, PairOutcome.Paired))
        first.heard(PairPageEvent.Closed)
        runCurrent()
        assertEquals(PairScreen.Waiting("192.168.1.20"), f.vm.page.value.screen, "the old opening's words dropped")
        f.clear()
        assertEquals(2, f.closed.size, "cleared: the open one closed too")
    }

    @Test
    fun `a phone not kept says why by how many the TV keeps`() = runTest {
        val f = Fixture(this)
        f.vm.start()
        runCurrent()
        val asked = question()
        f.opened.single().heard(PairPageEvent.Asking(asked))
        f.opened.single().heard(PairPageEvent.Ended(asked, PairOutcome.NotSaved))
        runCurrent()
        assertEquals(PairScreen.NotKept("Galaxy phone", full = false), f.vm.page.value.screen)
        f.phones.value = 16
        runCurrent()
        assertEquals(PairScreen.NotKept("Galaxy phone", full = true), f.vm.page.value.screen)
        f.clear()
    }

    @Test
    fun `the listener's address as it changes`() = runTest {
        val f = Fixture(this)
        f.vm.start()
        runCurrent()
        f.listener.value = TvListener.Listening(InetAddress.getByName("10.0.2.15") as Inet4Address, 30_123)
        runCurrent()
        assertEquals(PairScreen.Waiting("10.0.2.15:30123"), f.vm.page.value.screen)
        f.listener.value = TvListener.NoNetwork
        runCurrent()
        assertEquals(PairScreen.NoNetwork, f.vm.page.value.screen)
        f.clear()
    }

    /** The question is the TV's to answer: Pair ([PhoneQuestion.ok]) and Cancel go to the listener's, once each press. */
    @Test
    fun `Pair and Cancel answer the question`() {
        val asked = question()
        asked.ok()
        question("Pixel tablet").cancel()
        assertEquals(listOf("Galaxy phone" to true, "Pixel tablet" to false), answers)
        assertEquals("PhoneQuestion", asked.toString(), "never the number or the name in a log")
        assertNull(PairPage().question)
    }

    @Test
    fun `the drawn steps and paired words name Play on`() {
        assertEquals("On your phone: NZStreamer › Play on › Add a TV, then pick Living room TV.", pairStepOne("Living room TV"))
        assertEquals("On the phone, open a show or film and choose Play on › Living room TV. NZStreamer must be open here.", pairedPlayOn("Living room TV"))
    }

    /** The question's button that pairs reads Pair (PtTvPair 2), and the words around it name it so. */
    @Test
    fun `the step and the question's words name Pair`() {
        assertEquals("Check that both screens show the same number, then choose Yes on the phone and Pair here.", PAIR_STEP_TWO)
        assertEquals("If they match, choose Pair here and Yes on the phone. If not, choose Cancel.", PAIR_IF_THEY_MATCH)
    }
}
