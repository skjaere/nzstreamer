package io.skjaere.usenet.app.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.link.HandPhone
import io.skjaere.usenet.app.link.PAIR_ANOTHER
import io.skjaere.usenet.app.link.PAIR_CLOSED
import io.skjaere.usenet.app.link.PAIR_PHONE_TITLE
import io.skjaere.usenet.app.link.PAIR_WAITING
import io.skjaere.usenet.app.link.PairPhoneDeps
import io.skjaere.usenet.app.link.PairedPhones
import io.skjaere.usenet.app.link.PhonePairing
import io.skjaere.usenet.app.link.PlayOnTimings
import io.skjaere.usenet.app.link.TvListener
import io.skjaere.usenet.app.link.anotherAddress
import io.skjaere.usenet.app.link.numberShown
import io.skjaere.usenet.app.link.numberSpoken
import io.skjaere.usenet.app.link.pairWith
import io.skjaere.usenet.app.link.pairedNow
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.PairAttempt
import io.skjaere.usenet.link.PairOutcome
import io.skjaere.usenet.link.Purpose
import io.skjaere.usenet.link.Refusal
import io.skjaere.usenet.link.Role
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Pair a phone on a real TV (tv36, PtTvPair, W3, L22), driven with the remote, with phones in the test's process
 * connecting to the TV's own private address (L14): the question shows the phone's number with Pair focused, and who asks
 * is heard as it shows; an OK on Pair in its first moments is ignored, half a second in too, then OK pairs; Left reaches
 * Cancel, which ends both; the phone's No ends both; an unanswered question closes (timings shrunk, L46); a second phone
 * waiting adds the question's line; a phone refused after 3 failed tries doesn't stop another; pairing closes after its
 * (shrunk) 10 minutes; Back ends pairing mode. The names are invented.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
class PairPhoneTvTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val pages = LinkPages(compose)
    private val remote = pages.remote
    private val home = HomeContentShown()
    private val phones = PairedPhones()
    private val timings = PlayOnTimings.current
    private val open = CopyOnWriteArrayList<Closeable>()

    @Before
    fun tv() {
        assumeTv()
        phones.clear()
        pages.awaitOff()
        home.show()
        instrumentation.setInTouchMode(false)
    }

    @After
    fun restore() {
        open.forEach { runCatching { it.close() } }
        PlayOnTimings.current = timings
        instrumentation.setInTouchMode(true)
        home.restore()
        phones.restore()
    }

    private fun <T : Closeable> keep(c: T): T = c.also { open += it }

    private fun phone(name: String, answer: Boolean? = true) = keep(PhonePairing(pages.address(), name, answer = answer))

    private fun shows(text: String) = pages.exists(hasText(text))

    /** The number, as the screen shows it in threes and TalkBack reads it, digit by digit. */
    private fun showsNumber(digits: String) =
        pages.exists(hasContentDescription(numberSpoken(digits))) && numberShown(digits).length == 7

    private fun asked(name: String) = compose.waitUntil(10_000) { shows(pairWith(name)) }

    /** [text] as TalkBack meets it as it shows (L43): a heading, read out by itself. */
    private fun says(text: String) = pages.exists(
        hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading) and
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
    )

    private fun backToWaiting(name: String) = compose.waitUntil(10_000) { !shows(pairWith(name)) && shows(PAIR_WAITING) }

    /** The focus on the button [label] itself, not on another whose words hold it (Pair a phone, on Phones). */
    private fun focusOn(label: String) {
        try {
            compose.waitUntil(5_000) { remote.focused() == label }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("focus never on \"$label\"; it is on \"${remote.focused()}\"", e)
        }
    }

    /** Until an OK on the question that showed by [seen] (uptime) is taken as meant: its guard's time is over. */
    private fun pastTheGuard(seen: Long) = SystemClock.sleep((seen + PairPhoneDeps.QUESTION_GUARD_MS - SystemClock.uptimeMillis()).coerceAtLeast(0))

    /** An OK as one meant for something else would come, at [at] (uptime): the moment the question showed, or later. */
    private fun okAt(at: Long) {
        instrumentation.sendKeySync(KeyEvent(at, at, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        instrumentation.sendKeySync(KeyEvent(at, at, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        compose.waitForIdle()
    }

    /**
     * The question opens with Pair focused, and an OK on it from the moment the question showed (one meant for the page
     * before, or a stray press) is ignored; past those moments, OK there pairs. Left reaches Cancel beside it.
     */
    @Test
    fun theNumberShowsWithPairFocusedAnOkAtOnceIsIgnoredThereAndThenOkPairs() {
        pages.launch().use {
            pages.pairPage()
            val phone = phone("Invented phone")
            asked("Invented phone")
            val seen = SystemClock.uptimeMillis()
            focusOn("Pair")
            assertTrue("the phone's number: the same six digits", showsNumber(phone.check.get(10, TimeUnit.SECONDS).number.digits))
            okAt(seen)
            Thread.sleep(600)
            assertTrue("the early OK on Pair paired", shows(pairWith("Invented phone")) && !phone.result.isDone)
            assertEquals("Pair", remote.focused())

            assertEquals("Cancel", remote.press(KeyEvent.KEYCODE_DPAD_LEFT))
            assertEquals("Pair", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
            pastTheGuard(seen)
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            val paired = phone.outcome()
            assertTrue("paired: $paired", paired is PairAttempt.Result.Paired && paired.tv == pages.tvKey())
            compose.waitUntil(10_000) { shows(pairedNow("Invented phone")) }
            remote.waitForFocus("Done")
            assertEquals(listOf("Invented phone"), runBlocking { container.data.link.phones.first() }.map { it.name })
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            remote.waitForFocus(PAIR_PHONE_TITLE)
            assertTrue("listed on Phones", shows("Invented phone"))
        }
    }

    /**
     * The question comes unprompted with Pair focused, so it ignores OK longer than a sheet does: an OK half a second
     * after it showed (a reflex press as the page changed, past a sheet's window) doesn't pair either.
     */
    @Test
    fun anOkHalfASecondAfterTheQuestionShowedIsIgnoredToo() {
        pages.launch().use {
            pages.pairPage()
            val phone = phone("Invented phone")
            asked("Invented phone")
            val seen = SystemClock.uptimeMillis()
            focusOn("Pair")
            SystemClock.sleep((seen + 500 - SystemClock.uptimeMillis()).coerceAtLeast(0))
            okAt(seen + 500)
            Thread.sleep(600)
            assertTrue("the OK half a second in paired", shows(pairWith("Invented phone")) && !phone.result.isDone)
            assertEquals("Pair", remote.focused())
        }
    }

    /**
     * TalkBack (L43): the question comes by itself and its first focus is Pair, so who asks is heard as it shows, not
     * only "Pair, button": a user waiting for their own phone hears that another asks before the OK that would pair it.
     */
    @Test
    fun whoAsksIsHeardAsTheQuestionShows() {
        pages.launch().use {
            pages.pairPage()
            phone("Invented phone", answer = null)
            asked("Invented phone")
            focusOn("Pair")
            assertTrue("who asks isn't heard", says(pairWith("Invented phone")))
        }
    }

    /** Cancel, Left of Pair, ends both: the phone hears the TV declined, nothing is saved, and the page waits again. */
    @Test
    fun leftReachesCancelWhichEndsBoth() {
        pages.launch().use {
            pages.pairPage()
            val phone = phone("Invented phone")
            asked("Invented phone")
            val seen = SystemClock.uptimeMillis()
            focusOn("Pair")
            pastTheGuard(seen)
            assertEquals("Cancel", remote.press(KeyEvent.KEYCODE_DPAD_LEFT))
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            backToWaiting("Invented phone")
            assertEquals(PairAttempt.Result.Stopped(PairOutcome.Declined), phone.outcome())
            assertTrue("nothing saved", runBlocking { container.data.link.phones.first() }.isEmpty())
            remote.waitForFocus("Cancel")
        }
    }

    @Test
    fun thePhonesNoEndsBoth() {
        pages.launch().use {
            pages.pairPage()
            val phone = phone("Invented phone", answer = null)
            asked("Invented phone")
            phone.check.get(10, TimeUnit.SECONDS).no()
            backToWaiting("Invented phone")
            assertEquals(PairAttempt.Result.Stopped(PairOutcome.No), phone.outcome())
            assertTrue("nothing saved", runBlocking { container.data.link.phones.first() }.isEmpty())
        }
    }

    /**
     * The phone's No comes a moment before the TV user's OK meant for the question's Pair, focused: that OK lands on
     * Waiting's Cancel, which ignores it as the question did, so the page stays and so does a phone waiting its turn.
     */
    @Test
    fun anOkMeantForTheQuestionThatWentStaysOnThePage() {
        pages.launch().use {
            pages.pairPage()
            val phone = phone("Invented phone", answer = null)
            asked("Invented phone")
            focusOn("Pair")
            phone.check.get(10, TimeUnit.SECONDS).no()
            backToWaiting("Invented phone")
            val seen = SystemClock.uptimeMillis()
            remote.waitForFocus("Cancel")
            okAt(seen)
            Thread.sleep(600)
            assertTrue("the OK left Pair a phone", shows(PAIR_WAITING))
            remote.waitForFocus("Cancel")
        }
    }

    @Test
    fun aQuestionNobodyAnswersClosesByItself() {
        PlayOnTimings.current = PlayOnTimings(questionMillis = 3_000)
        pages.launch().use {
            pages.pairPage()
            val phone = phone("Invented phone", answer = null)
            asked("Invented phone")
            assertTrue(pages.exists(hasText("Closes by itself in 0:0", substring = true)))
            backToWaiting("Invented phone")
            assertEquals(PairAttempt.Result.Stopped(PairOutcome.TimedOut), phone.outcome())
            assertTrue(runBlocking { container.data.link.phones.first() }.isEmpty())
        }
    }

    @Test
    fun aSecondPhoneWaitingAddsTheQuestionsLine() {
        pages.launch().use {
            pages.pairPage()
            val first = phone("Invented phone", answer = null)
            asked("Invented phone")
            assertFalse(shows(PAIR_ANOTHER))
            val second = phone("Invented tablet", answer = null)
            assertTrue("the second phone told it waits", second.waited.await(10, TimeUnit.SECONDS))
            compose.waitUntil(5_000) { shows(PAIR_ANOTHER) }
            // The first one's No: the second's turn, its own question, nobody else waiting.
            first.check.get(10, TimeUnit.SECONDS).no()
            asked("Invented tablet")
            compose.waitUntil(5_000) { !shows(PAIR_ANOTHER) }
            assertTrue(showsNumber(second.check.get(10, TimeUnit.SECONDS).number.digits))
        }
    }

    /**
     * L22: failed tries count by address. A phone from another of the TV's addresses breaks the ceremony's order three
     * times and is refused the fourth (too-many); a phone from the TV's own address still pairs.
     */
    @Test
    fun aPhoneRefusedAfterThreeFailuresDoesntStopAnother() {
        // Each failure makes the next commit wait (L21): shrunk, as the count by address is what this tries.
        PlayOnTimings.current = PlayOnTimings(pairingPauseMillis = 300)
        pages.launch().use {
            pages.pairPage()
            val tv = pages.address()
            val other = anotherAddress(context, tv.address as java.net.Inet4Address)
            assumeTrue("no second private IPv4 address on this TV to come from", other != null)
            keep(other!!)
            fun prank(): LinkMessage? = HandPhone(tv, other.address).use { hand ->
                assertTrue(hand.read() is LinkMessage.Hello)
                hand.send(LinkMessage.Hello(1, 1, Role.Phone, "Invented prankster", app = "0.1.0", purpose = Purpose.Pair))
                // After a failed try, told to wait for the commit first.
                when (val first = hand.read().let { if (it == LinkMessage.PairWait) hand.read() else it }) {
                    is LinkMessage.PairCommit -> {
                        // A confirm before the number: the order broken, the ceremony ended.
                        hand.send(LinkMessage.PairConfirm(yes = true))
                        while (hand.read() != null) Unit
                        first
                    }
                    else -> first
                }
            }
            repeat(3) { assertTrue("try ${it + 1} reached a ceremony", prank() is LinkMessage.PairCommit) }
            assertEquals(LinkMessage.Refused(Refusal.TooMany), prank())
            assertTrue("no question for any of them", shows(PAIR_WAITING))

            val phone = phone("Invented phone")
            asked("Invented phone")
            val seen = SystemClock.uptimeMillis()
            focusOn("Pair")
            pastTheGuard(seen)
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(phone.outcome() is PairAttempt.Result.Paired)
        }
    }

    @Test
    fun pairingClosesByItself() {
        PlayOnTimings.current = PlayOnTimings(pairingMillis = 3_000)
        pages.launch().use {
            pages.pairPage()
            compose.waitUntil(10_000) { shows(PAIR_CLOSED) }
            remote.waitForFocus("Done")
            // Nothing paired and no pairing: nothing listens.
            compose.waitUntil(5_000) { container.tvLink.state.value == TvListener.Off }
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            remote.waitForFocus(PAIR_PHONE_TITLE)
        }
    }

    @Test
    fun backEndsPairingMode() {
        // A phone paired: the listener stays up after Back, and says it doesn't pair now.
        phones.add("Invented tablet")
        pages.launch().use {
            pages.pairPage()
            val address = pages.address()
            remote.press(KeyEvent.KEYCODE_BACK)
            remote.waitForFocus(PAIR_PHONE_TITLE)
            val phone = keep(PhonePairing(InetSocketAddress(address.address, address.port), "Invented phone"))
            assertEquals(PairAttempt.Result.Refused(Refusal.NotPairing), phone.outcome())
        }
    }
}
