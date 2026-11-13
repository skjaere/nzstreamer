package io.skjaere.usenet.app.link

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.setup.LanBinding
import io.skjaere.usenet.link.LinkMessage.Hello
import io.skjaere.usenet.link.PairOutcome
import io.skjaere.usenet.link.Playback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The TV's half of phone-to-TV across two devices (items 16 and 28, L14): on tv36, a TvLinkHost in pairing mode on the
 * emulators' shared Wi-Fi (held for the run, as the gate found it must be), with the app's own link key and link.json,
 * plays (TvPlays), status, Finals and outbox, its record advertised through the app's LinkNsd, OK said to the question the
 * moment it shows (the phone's half says Yes). NZStreamer is in front, so the phone's Play goes to its player (L8): a film
 * that plays with no mock and no indexer asked (L40). It waits up to 120 s for the phone to pair and link, pinned; then
 * for the phone's film to play here in its session, for the phone's Stop to leave the player, and for the phone's FinalAck
 * to empty its outbox; and holds the link while the phone does. Opt-in (`-e linkPeer tv`, from emu-link-pair.sh): skipped
 * in every other run. The names and the film are invented; the app's link.json and settings are put back after.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
class LinkTwoDevicesTvTest {
    /** The player hides the system bars: Android's hint about that would take its window. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closing = CopyOnWriteArrayList<AutoCloseable>()
    private val phones = PairedPhones()
    private val home = HomeContentShown()
    private val film = PlayableFilm(PAIR_FILM, PAIR_FILM_TITLE)
    private var set = false

    @After
    fun stop() {
        closing.forEach { it.close() }
        scope.cancel()
        if (set) {
            film.close()
            phones.restore()
            home.restore()
        }
    }

    @Test
    fun pairsAPhoneAndPlaysWhatItAsks() {
        assumeTrue("opt-in: -e linkPeer tv", linkPeer == "tv")
        assumeTv()
        val wifi = holdWifi(context)?.also { closing += it }
        assertNotNull("tv36 joined no Wi-Fi in 30 s", wifi)
        home.show()
        phones.clear()
        set = true
        runBlocking { film.prepare() }
        val store = container.data.link
        val host = TvLinkHost(
            scope,
            TvLinkDeps(
                television = true, inFront = MutableStateFlow(true), store = store, identity = { store.identity().identity.key },
                addresses = flowOf(LanBinding(wifi!!.address, wifi.network)), advertiser = LinkNsd(context), name = PAIR_TV_NAME, app = "0.1.0",
                ready = flowOf(Hello.Ready(provider = true, indexer = true, tmdb = false)), commands = container.tvPlays, links = container.tvPhoneLinks,
            ),
        )
        val ended = CopyOnWriteArrayList<PairOutcome>()
        // The seam: OK as each question shows; the phone's half says Yes on its own.
        val page = PairPageListener { event ->
            when (event) {
                is PairPageEvent.Asking -> event.question.ok()
                is PairPageEvent.Ended -> ended += event.outcome
                else -> Unit
            }
        }
        // NZStreamer in front, as a TV a phone plays on: its launches go to the player. Started is all it takes, its focus
        // wherever it lands.
        ActivityScenario.launch<MainActivity>(context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)!!).use {
            host.start()
            host.openPairing(page)
            val listening = waitFor(30) { host.state.value as? TvListener.Listening }
            assertNotNull("not listening on the Wi-Fi address; ${host.state.value}", listening)
            pairReport(TAG, "pair TV: listening on Wi-Fi, port ${listening!!.port}")
            val paired = waitFor(120) { ended.firstOrNull { it == PairOutcome.Paired } }
            pairReport(TAG, "pair TV: pairings ended $ended")
            assertEquals("no phone paired in 120 s; pairings ended $ended", PairOutcome.Paired, paired)
            val phone = runBlocking { store.phones.first() }.single().key
            assertNotNull(phone)
            val linked = waitFor(30) { host.connected.value.takeIf { phone in it } }
            assertNotNull("the paired phone didn't link in 30 s", linked)
            pairReport(TAG, "pair TV: the paired phone linked")
            host.closePairing(page)
            val held = System.nanoTime()
            // The phone's outbox as it changes: its Final owed once the player saves where it stopped, then let go.
            val owed = AtomicBoolean()
            scope.launch { store.phones.collect { rows -> if (rows.any { it.key == phone && it.outbox.isNotEmpty() }) owed.set(true) } }
            // Play (item 28): the phone's film plays here, in its session.
            val playing = waitFor(120) { container.playerInFront.now.value?.takeIf { it.remote?.phone == phone!!.hex && it.state == Playback.Playing } }
            assertNotNull("the phone's play didn't play here in 120 s; the player says ${container.playerInFront.now.value}", playing)
            pairReport(TAG, "pair TV: the phone's play plays here")
            // Stop: the player leaves.
            val left = waitFor(120) { (container.playerInFront.now.value == null).takeIf { it } }
            assertNotNull("the player didn't leave in 120 s", left)
            pairReport(TAG, "pair TV: the phone's Stop left the player")
            // Final: owed to the phone until its FinalAck lets it go.
            val acked = waitFor(60) { (owed.get() && runBlocking { store.owed(phone!!) }.isEmpty()).takeIf { it } }
            assertNotNull("the phone's Final ${if (owed.get()) "still owed" else "never owed"} after 60 s", acked)
            pairReport(TAG, "pair TV: the phone acked where its play stopped")
            assertEquals("no indexer asked", 0, film.grabs.get())
            // The phone holds its link a while more, then closes it as its half ends.
            waitFor(60) { (phone !in host.connected.value).takeIf { it } }
            val seconds = (System.nanoTime() - held) / 1_000_000_000
            pairReport(TAG, "pair TV: the phone's link was held $seconds s")
            // Through the play: it reached 2 s on the phone before its Stop.
            assertTrue("the link went after $seconds s, before the phone's half let it go", seconds >= 2)
        }
    }

    /** [value]'s first non-null within [seconds]; null when none came. */
    private fun <T> waitFor(seconds: Long, value: () -> T?): T? {
        val until = System.nanoTime() + seconds * 1_000_000_000
        while (System.nanoTime() < until) {
            value()?.let { return it }
            Thread.sleep(100)
        }
        return null
    }

    private companion object {
        const val TAG = "LinkTwoDevices"
    }
}
