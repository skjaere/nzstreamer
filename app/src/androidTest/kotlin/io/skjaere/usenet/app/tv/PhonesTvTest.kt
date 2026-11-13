package io.skjaere.usenet.app.tv

import android.view.KeyEvent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.link.PAIR_PHONE_TITLE
import io.skjaere.usenet.app.link.PHONES_EMPTY
import io.skjaere.usenet.app.link.PHONES_NONE
import io.skjaere.usenet.app.link.PHONES_REMOVE
import io.skjaere.usenet.app.link.PHONES_TITLE
import io.skjaere.usenet.app.link.PairedPhones
import io.skjaere.usenet.app.link.TvListener
import io.skjaere.usenet.app.link.TvReach
import io.skjaere.usenet.app.link.TvState
import io.skjaere.usenet.app.link.phoneClient
import io.skjaere.usenet.app.link.removeAsk
import io.skjaere.usenet.app.link.softwareKey
import io.skjaere.usenet.app.setup.PHONE_SETUP_TITLE
import io.skjaere.usenet.link.Connect
import io.skjaere.usenet.link.LinkClient
import io.skjaere.usenet.link.LinkClose
import io.skjaere.usenet.link.LinkHandler
import io.skjaere.usenet.link.LinkKey
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.PeerLink
import io.skjaere.usenet.link.Refusal
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Settings › Phones on a real TV (tv36, PtTvSettings, L30), with the remote: the row in the top card after "Set up with
 * your phone", its line naming the phones; the page's focus on Pair a phone, Up reaching Remove, Back on the row; Remove
 * asking first (Cancel focused), then closing that phone's link at once; Remove of the only phone telling it so while
 * the TV listens on (L4a); a row gone after the phone's own Unpair; and the page with nothing paired. The phones are in
 * the test's process, linked to the TV's own address; their names are invented.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
class PhonesTvTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container = instrumentation.targetContext.container
    private val pages = LinkPages(compose)
    private val remote = pages.remote
    private val home = HomeContentShown()
    private val phones = PairedPhones()
    private val clients = CopyOnWriteArrayList<LinkClient>()

    @Before
    fun tv() {
        assumeTv()
        phones.clear()
        home.show()
        instrumentation.setInTouchMode(false)
    }

    @After
    fun restore() {
        clients.forEach { it.close() }
        instrumentation.setInTouchMode(true)
        home.restore()
        phones.restore()
    }

    /** What a phone's link hears. */
    private class Heard : LinkHandler {
        val closed = CopyOnWriteArrayList<LinkClose>()
        override fun received(link: PeerLink, message: LinkMessage) = Unit
        override fun closed(link: PeerLink, reason: LinkClose) {
            closed += reason
        }
    }

    /** A phone paired as [name], linked to the TV now. */
    private fun linkedPhone(name: String, key: LinkKey = softwareKey()): Pair<Connect.Linked, Heard> {
        phones.add(name, key.id)
        val heard = Heard()
        val client = phoneClient(name, key).also { clients += it }
        val connect = client.connect(pages.address(), pages.tvKey(), heard)
        assertTrue("linked: $connect", connect is Connect.Linked)
        return connect as Connect.Linked to heard
    }

    @Test
    fun theHubRowAndItsLine() {
        pages.launch().use {
            // The row's title and its line; its chevron is drawn alone, not read.
            assertEquals("$PHONES_TITLE | $PHONES_NONE", pages.settingsOnPhones())
            // In the top card, after "Set up with your phone".
            assertTrue(remote.press(KeyEvent.KEYCODE_DPAD_UP).startsWith(PHONE_SETUP_TITLE))
            phones.add("Invented phone")
            phones.add("Invented tablet")
            assertTrue(remote.press(KeyEvent.KEYCODE_DPAD_DOWN).startsWith(PHONES_TITLE))
            compose.waitUntil(5_000) { remote.focused() == "$PHONES_TITLE | 2 phones · Invented phone, Invented tablet" }
        }
    }

    @Test
    fun theFocusStartsOnPairAPhoneUpReachesRemoveAndBackReturnsToTheRow() {
        phones.add("Invented phone")
        pages.launch().use {
            pages.phones()
            assertTrue(pages.exists(hasText("Invented phone")))
            assertEquals(PHONES_REMOVE, remote.press(KeyEvent.KEYCODE_DPAD_UP))
            assertEquals(PAIR_PHONE_TITLE, remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
            remote.press(KeyEvent.KEYCODE_BACK)
            remote.waitForFocus(PHONES_TITLE)
        }
    }

    /** Another phone paired keeps the listener up: the removed one's link closes by the removal itself, not with the listener. */
    @Test
    fun removeAsksFirstThenClosesThatPhonesLinkAtOnce() {
        phones.add("Invented tablet")
        pages.launch().use {
            val (_, heard) = linkedPhone("Invented phone")
            pages.phones()
            compose.waitUntil(5_000) { pages.exists(hasText("Paired", substring = true) and hasText("Seen now", substring = true)) }
            // Up from Pair a phone: the last row's Remove, the phone paired last.
            assertEquals(PHONES_REMOVE, remote.press(KeyEvent.KEYCODE_DPAD_UP))
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) { pages.exists(hasText(removeAsk("Invented phone"))) }
            remote.waitForFocus("Cancel")
            // OK twice in a row: the second lands on Cancel.
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) { !pages.exists(hasText(removeAsk("Invented phone"))) }
            Thread.sleep(500)
            assertTrue("kept", heard.closed.isEmpty() && runBlocking { container.data.link.phones.first() }.size == 2)

            remote.waitForFocus(PHONES_REMOVE)
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) { pages.exists(hasText(removeAsk("Invented phone"))) }
            compose.onAllNodes(hasText(PHONES_REMOVE) and hasClickAction()).onLast().requestFocus()
            remote.waitForFocus(PHONES_REMOVE)
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(2_000) { heard.closed.isNotEmpty() }
            assertTrue("still listening for the other phone", container.tvLink.state.value is TvListener.Listening)
            compose.waitUntil(5_000) { !pages.exists(hasText("Invented phone")) }
            assertTrue(pages.exists(hasText("Invented tablet")))
            assertEquals(listOf("Invented tablet"), runBlocking { container.data.link.phones.first() }.map { it.name })
            remote.waitForFocus(PAIR_PHONE_TITLE)
        }
    }

    /**
     * L4a: Remove of the only phone, with no other kept paired on purpose (the common household). The TV goes on
     * listening, so the phone hears it was removed: on its live link at once, which it reads as "removed this phone", and
     * again on its next try.
     */
    @Test
    fun removeOfTheOnlyPhoneTellsItOnItsLinkAndTheListenerStaysUp() {
        pages.launch().use {
            val key = softwareKey()
            val (_, heard) = linkedPhone("Invented phone", key)
            pages.phones()
            compose.waitUntil(5_000) { pages.exists(hasText("Seen now", substring = true)) }
            assertEquals(PHONES_REMOVE, remote.press(KeyEvent.KEYCODE_DPAD_UP))
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) { pages.exists(hasText(removeAsk("Invented phone"))) }
            compose.onAllNodes(hasText(PHONES_REMOVE) and hasClickAction()).onLast().requestFocus()
            remote.waitForFocus(PHONES_REMOVE)
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(2_000) { heard.closed.isNotEmpty() }
            assertEquals("told on its link", listOf<LinkClose>(LinkClose.Refused(Refusal.NotPaired)), heard.closed.toList())
            assertEquals(TvState.RemovedHere, TvReach.closed(heard.closed.single()))
            compose.waitUntil(5_000) { pages.exists(hasText(PHONES_EMPTY)) }
            assertEquals(emptyList<Any>(), runBlocking { container.data.link.phones.first() })

            // Well past the moment the last phone went: still listening, on the same address.
            val on = container.tvLink.state.value
            assertTrue("listening: $on", on is TvListener.Listening)
            Thread.sleep(1_500)
            assertEquals("still listening with no phone paired", on, container.tvLink.state.value)
            val again = phoneClient("Invented phone", key).also { clients += it }.connect(pages.address(), pages.tvKey(), Heard())
            assertEquals(Connect.Refused(Refusal.NotPaired), again)
            assertEquals(TvState.RemovedHere, TvReach.refused((again as Connect.Refused).reason))
            assertEquals(on, container.tvLink.state.value)
        }
    }

    @Test
    fun theRowGoesAfterAPhonesOwnUnpair() {
        pages.launch().use {
            val (linked, heard) = linkedPhone("Invented phone")
            linkedPhone("Invented tablet")
            pages.phones()
            compose.waitUntil(5_000) { pages.exists(hasText("Invented phone")) && pages.exists(hasText("Invented tablet")) }
            linked.link.send(LinkMessage.Unpair)
            compose.waitUntil(5_000) { !pages.exists(hasText("Invented phone")) }
            assertTrue(pages.exists(hasText("Invented tablet")))
            compose.waitUntil(3_000) { heard.closed.isNotEmpty() }
            assertEquals(listOf("Invented tablet"), runBlocking { container.data.link.phones.first() }.map { it.name })
        }
    }

    @Test
    fun withNothingPairedTheLineThenPairAPhone() {
        pages.launch().use {
            pages.phones()
            assertTrue(pages.exists(hasText(PHONES_EMPTY)))
            assertFalse(pages.exists(hasText(PHONES_REMOVE)))
            assertEquals("nothing above it", PAIR_PHONE_TITLE, remote.press(KeyEvent.KEYCODE_DPAD_UP))
        }
    }
}
