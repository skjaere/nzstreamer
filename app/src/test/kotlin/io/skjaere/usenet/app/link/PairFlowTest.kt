package io.skjaere.usenet.app.link

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.skjaere.usenet.data.PairedTv
import io.skjaere.usenet.link.ConnectFailure
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.PairAttempt
import io.skjaere.usenet.link.PairOutcome
import io.skjaere.usenet.link.Refusal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Add a TV on the phone (item 14, W3, PtPhonePair, PtPhonePairFail, L41) as states, over a pairing and a discovery
 * that a test drives: TVs nearby, the typed address, connecting, the waiting phone's words, the number with Yes and No,
 * "Now choose Pair", paired and saved; and each failure in its words, with Try again. The names are invented.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairFlowTest {
    @AfterEach
    fun main() = Dispatchers.resetMain()

    private val living = InetAddress.getByName("192.168.1.42") as Inet4Address
    private val bedroom = InetAddress.getByName("192.168.1.31") as Inet4Address
    private val livingKey = KeyId.of(Random.nextBytes(91))
    private val bedroomKey = KeyId.of(Random.nextBytes(91))

    private fun found(key: KeyId, name: String, at: Inet4Address, pairing: Boolean = true, port: Int = PortChoice.DEFAULT) =
        FoundTv(ServiceRecord(name, 1, key.short, pairing, at), listOf(at), port)

    /** A pairing the test answers for: where it went, what it was told to show, and how it ends. */
    private class Call(val target: InetSocketAddress, val prompt: PairPrompt) {
        val result = CompletableDeferred<PairAttempt.Result>()
        var cancelled = false
        val answers = mutableListOf<Boolean>()

        fun compare(name: String = "Living room TV", digits: String = "482913") = prompt.compare(name, digits) { answers += it }
    }

    private inner class Fixture(val test: TestScope, val again: KeyId? = null) {
        val discovery = MutableStateFlow<Discovery?>(Discovery(0, emptyList()))
        val tvs = MutableStateFlow<List<PairedTv>>(emptyList())
        var looking = 0
        val calls = mutableListOf<Call>()
        val saved = mutableListOf<String>()
        val forgotten = mutableListOf<KeyId>()
        var saves = true
        /** How long mDNS has gone without each kept TV's own id, as TvLinks would count it. */
        var unseen: (KeyId) -> Long? = { null }
        private val store = ViewModelStore()
        val deps = AddTvDeps(
            discovery = discovery,
            look = {
                looking++
                AutoCloseable { looking-- }
            },
            tvs = tvs,
            pair = { target, prompt ->
                val call = Call(target, prompt)
                calls += call
                try {
                    call.result.await()
                } catch (e: CancellationException) {
                    call.cancelled = true
                    throw e
                }
            },
            save = { key, name, address, port ->
                saved += "${key.short} $name ${address.hostAddress}:$port"
                saves
            },
            forget = { forgotten += it },
            unseen = { unseen(it) },
        )
        val vm: AddTvViewModel by lazy {
            Dispatchers.setMain(StandardTestDispatcher(test.testScheduler))
            ViewModelProvider.create(store, viewModelFactory { initializer { AddTvViewModel(deps, again) } })[AddTvViewModel::class]
        }
        val step get() = vm.state.value.step

        fun clear() = store.clear()
    }

    @Test
    fun `TVs nearby are those taking pairings that aren't paired here yet, looked for while the page shows`() = runTest {
        val f = Fixture(this)
        f.tvs.value = listOf(PairedTv(bedroomKey.hex, "Bedroom TV", 1L))
        f.vm.start()
        runCurrent()
        assertEquals(1, f.looking, "discovery runs while Add a TV shows")
        f.discovery.value = Discovery(
            0,
            listOf(
                found(livingKey, "Living room TV", living),
                found(bedroomKey, "Bedroom TV", bedroom),
                found(KeyId.of(Random.nextBytes(91)), "Guest room TV", InetAddress.getByName("192.168.1.77") as Inet4Address, pairing = false),
            ),
        )
        runCurrent()
        assertEquals(listOf(NearbyTv(livingKey.short, "Living room TV", living, PortChoice.DEFAULT)), f.vm.state.value.nearby)
        f.vm.stop()
        assertEquals(0, f.looking)
        f.clear()
    }

    @Test
    fun `nothing found in 10 s makes Enter the TV's address the main way, until a TV shows`() = runTest {
        val f = Fixture(this)
        f.vm.start()
        runCurrent()
        advanceTimeBy(9_900)
        assertFalse(f.vm.state.value.nothingYet)
        advanceTimeBy(200)
        assertTrue(f.vm.state.value.nothingYet)
        f.discovery.value = Discovery(0, listOf(found(livingKey, "Living room TV", living)))
        runCurrent()
        assertFalse(f.vm.state.value.nothingYet)
        f.clear()
    }

    @Test
    fun `a TV picked pairs once both screens show the number and Yes is chosen, and is saved here`() = runTest {
        val f = Fixture(this)
        f.vm.start()
        f.discovery.value = Discovery(0, listOf(found(livingKey, "Living room TV", living)))
        runCurrent()
        f.vm.pick(f.vm.state.value.nearby.single())
        runCurrent()
        val target = PairTarget(InetSocketAddress(living, PortChoice.DEFAULT), "Living room TV")
        assertEquals(AddTvStep.Connecting(target), f.step)
        assertEquals("Connecting to Living room TV…", connectingTo(target.name))
        val call = f.calls.single()
        assertEquals(target.address, call.target)
        call.compare(name = "Living room TV‮", digits = "082913")
        runCurrent()
        assertEquals(AddTvStep.Compare(target, "Living room TV", "082913"), f.step, "the TV's name cleaned (L28)")
        assertEquals("Does Living room TV show this number?", doesItShow("Living room TV"))
        assertEquals("082 913", numberShown("082913"))
        // The TV's button is Pair (PtTvPair 2), and the phone says so.
        assertEquals("Compare it with the number on the TV. If they match, choose Yes here, then Pair on the TV.", COMPARE_HOW)
        f.vm.yes()
        runCurrent()
        assertEquals(listOf(true), call.answers)
        assertEquals(AddTvStep.Confirming(target, "Living room TV", "082913"), f.step)
        assertEquals("Now choose Pair on Living room TV", choosePairOn("Living room TV"))
        call.result.complete(PairAttempt.Result.Paired(livingKey, "Living room TV"))
        runCurrent()
        assertEquals(AddTvStep.Paired("Living room TV", livingKey), f.step)
        assertEquals(listOf("${livingKey.short} Living room TV 192.168.1.42:${PortChoice.DEFAULT}"), f.saved)
        assertEquals("Living room TV is ready", isReady("Living room TV"))
        // PtPhonePair 5's words, which name Play on (item 23).
        assertEquals("To play there, choose Living room TV in Play on. NZStreamer must be open on the TV when you play.", pairedLine("Living room TV"))
        f.clear()
    }

    @Test
    fun `No here stops it with nothing saved, and Try again pairs with the same TV afresh`() = runTest {
        val f = Fixture(this)
        f.vm.start()
        f.discovery.value = Discovery(0, listOf(found(livingKey, "Living room TV", living)))
        runCurrent()
        f.vm.pick(f.vm.state.value.nearby.single())
        runCurrent()
        val first = f.calls.single()
        first.compare()
        runCurrent()
        f.vm.no()
        runCurrent()
        assertEquals(listOf(false), first.answers)
        first.result.complete(PairAttempt.Result.Stopped(PairOutcome.No))
        runCurrent()
        val failed = assertIs<AddTvStep.Failed>(f.step)
        assertEquals(AddTvFailure.NoMatch, failed.failure)
        assertEquals("Pairing stopped. Nothing was saved.", failureTitle(failed.failure, "Living room TV"))
        assertEquals("The numbers didn’t match, so your phone may have reached another device. Check the TV’s name and try again.", failureText(failed.failure, "Living room TV"))
        assertTrue(f.saved.isEmpty())
        f.vm.tryAgain()
        runCurrent()
        assertEquals(2, f.calls.size)
        assertEquals(first.target, f.calls[1].target)
        assertIs<AddTvStep.Connecting>(f.step)
        f.clear()
    }

    @Test
    fun `each way a pairing ends without one says what to do next`() {
        val name = "Living room TV"
        fun failure(result: PairAttempt.Result) = AddTvFailure.of(result)
        assertEquals(AddTvFailure.CancelledOnTv, failure(PairAttempt.Result.Stopped(PairOutcome.Declined)))
        assertEquals("Pairing was cancelled on the TV. Nothing was saved.", failureTitle(AddTvFailure.CancelledOnTv, name))
        assertNull(failureText(AddTvFailure.CancelledOnTv, name))
        assertEquals(AddTvFailure.NotPairing, failure(PairAttempt.Result.Refused(Refusal.NotPairing)))
        assertEquals("Open Settings › Phones › Pair a phone on the TV, then try again.", failureTitle(AddTvFailure.NotPairing, name))
        assertEquals(AddTvFailure.TooMany, failure(PairAttempt.Result.Refused(Refusal.TooMany)))
        assertEquals("Too many tries. On the TV, leave Pair a phone and open it again.", failureTitle(AddTvFailure.TooMany, name))
        assertEquals(AddTvFailure.UpdateTv, failure(PairAttempt.Result.Refused(Refusal.UpdateTv)))
        assertEquals("Update NZStreamer on the TV", failureTitle(AddTvFailure.UpdateTv, name))
        assertEquals("Living room TV has an older NZStreamer that can’t pair with this phone. Update it on the TV, then try again.", failureText(AddTvFailure.UpdateTv, name))
        assertEquals(AddTvFailure.UpdatePhone, failure(PairAttempt.Result.Refused(Refusal.UpdatePhone)))
        assertEquals(AddTvFailure.UpdatePhone, failure(PairAttempt.Result.Refused(Refusal.Unknown)), "a refusal this version doesn't know (L26)")
        assertEquals("Update NZStreamer on this phone", failureTitle(AddTvFailure.UpdatePhone, name))
        assertEquals(AddTvFailure.Busy, failure(PairAttempt.Result.Refused(Refusal.Busy)))
        assertEquals("Living room TV is busy. Try again in a moment.", failureTitle(AddTvFailure.Busy, name))
        assertEquals("The TV is busy. Try again in a moment.", failureTitle(AddTvFailure.Busy, null))
        assertEquals(AddTvFailure.Unreachable, failure(PairAttempt.Result.Failed(ConnectFailure.Unreachable)))
        assertEquals(AddTvFailure.Unreachable, failure(PairAttempt.Result.Failed(ConnectFailure.TimedOut)))
        assertEquals("Couldn’t reach Living room TV", failureTitle(AddTvFailure.Unreachable, name))
        assertEquals("Your phone and TV must be on the same network. Guest Wi-Fi and VPNs can hide the TV.", failureText(AddTvFailure.Unreachable, name))
        assertEquals(AddTvFailure.TimedOut, failure(PairAttempt.Result.Stopped(PairOutcome.TimedOut)))
        assertEquals(AddTvFailure.Interfered, failure(PairAttempt.Result.Stopped(PairOutcome.WrongCommit)))
        assertEquals(AddTvFailure.NotSaved, failure(PairAttempt.Result.Stopped(PairOutcome.NotSaved)))
        assertEquals(AddTvFailure.ClosedOnTv, failure(PairAttempt.Result.Stopped(PairOutcome.Closed)))
        assertEquals(AddTvFailure.Stopped, failure(PairAttempt.Result.Stopped(PairOutcome.Broken)))
        assertNull(failure(PairAttempt.Result.Failed(ConnectFailure.Closed)), "cancelled here: no failure to show")
        for (each in AddTvFailure.entries) assertTrue(failureTitle(each, name).isNotBlank(), "$each has words")
    }

    @Test
    fun `a phone that waits behind another says so, then shows its number when its turn comes`() = runTest {
        val f = Fixture(this)
        f.vm.start()
        f.discovery.value = Discovery(0, listOf(found(livingKey, "Living room TV", living)))
        runCurrent()
        f.vm.pick(f.vm.state.value.nearby.single())
        runCurrent()
        val call = f.calls.single()
        call.prompt.waiting()
        runCurrent()
        assertIs<AddTvStep.Waiting>(f.step)
        assertEquals("Living room TV is pairing with another device. If it shows a number now, choose Cancel on the TV.", waitingWords("Living room TV"))
        assertEquals("The TV is pairing with another device. If it shows a number now, choose Cancel on the TV.", waitingWords(null))
        call.compare()
        runCurrent()
        assertIs<AddTvStep.Compare>(f.step)
        f.clear()
    }

    @Test
    fun `the typed address takes ip or ip and port, private IPv4 alone`() = runTest {
        assertEquals(InetSocketAddress(living, PortChoice.DEFAULT), parseTvAddress("192.168.1.42"))
        assertEquals(InetSocketAddress(living, 30_123), parseTvAddress(" 192.168.1.42:30123 "))
        for (bad in listOf("", "tv.local", "8.8.8.8", "127.0.0.1", "192.168.1.300", "192.168.1.42:0", "192.168.1.42:70000", "192.168.1.42:", "192.168.01.42", "::1")) {
            assertNull(parseTvAddress(bad), bad)
        }
        val f = Fixture(this)
        f.vm.start()
        f.vm.enterAddress()
        assertEquals(AddTvStep.Address, f.step)
        f.vm.type("tv.local")
        f.vm.connect()
        runCurrent()
        assertEquals(TYPE_ADDRESS_HELP_BAD, f.vm.state.value.typedError)
        assertTrue(f.calls.isEmpty())
        f.vm.type("192.168.1.42:30123")
        assertNull(f.vm.state.value.typedError)
        f.vm.connect()
        runCurrent()
        assertEquals(InetSocketAddress(living, 30_123), f.calls.single().target)
        assertEquals("Connecting to the TV…", connectingTo(null))
        f.calls.single().result.complete(PairAttempt.Result.Refused(Refusal.NotPairing))
        runCurrent()
        assertEquals(AddTvFailure.NotPairing, assertIs<AddTvStep.Failed>(f.step).failure)
        f.clear()
    }

    @Test
    fun `Cancel stops the pairing and goes back to where it started`() = runTest {
        val f = Fixture(this)
        f.vm.start()
        f.vm.enterAddress()
        f.vm.type("192.168.1.42")
        f.vm.connect()
        runCurrent()
        f.vm.cancel()
        runCurrent()
        assertTrue(f.calls.single().cancelled)
        assertEquals(AddTvStep.Address, f.step)
        assertTrue(f.vm.back(), "Back from the address goes to the TVs nearby")
        assertEquals(AddTvStep.Nearby, f.step)
        assertFalse(f.vm.back(), "Back from there leaves Add a TV")
        f.clear()
    }

    @Test
    fun `with 16 TVs kept, none is added, and a save that fails says the same`() = runTest {
        val f = Fixture(this)
        f.tvs.value = List(16) { PairedTv(KeyId.of(Random.nextBytes(91)).hex, "TV $it", 1L) }
        f.vm.start()
        runCurrent()
        assertEquals(AddTvFailure.Full, assertIs<AddTvStep.Failed>(f.step).failure)
        assertEquals("This phone keeps 16 TVs. Forget one in Settings › TVs to add another.", failureTitle(AddTvFailure.Full, null))
        val g = Fixture(this)
        g.saves = false
        g.vm.start()
        g.vm.enterAddress()
        g.vm.type("192.168.1.42")
        g.vm.connect()
        runCurrent()
        g.calls.single().result.complete(PairAttempt.Result.Paired(livingKey, "Living room TV"))
        runCurrent()
        assertEquals(AddTvFailure.Full, assertIs<AddTvStep.Failed>(g.step).failure)
        f.clear()
        g.clear()
    }

    /** Pair again (L30, item 15): a TV that removed this phone is listed though paired, and pairing again keeps its row. */
    @Test
    fun `Pair again for a TV that removed this phone lists it, though paired, and pairs it again in its place`() = runTest {
        val f = Fixture(this, again = livingKey)
        f.tvs.value = listOf(PairedTv(livingKey.hex, "Living room TV", 1L), PairedTv(bedroomKey.hex, "Bedroom TV", 1L))
        f.vm.start()
        f.discovery.value = Discovery(0, listOf(found(livingKey, "Living room TV", living), found(bedroomKey, "Bedroom TV", bedroom)))
        runCurrent()
        assertEquals(listOf("Living room TV"), f.vm.state.value.nearby.map { it.name })
        f.vm.pick(f.vm.state.value.nearby.single())
        runCurrent()
        f.calls.single().result.complete(PairAttempt.Result.Paired(livingKey, "Living room TV"))
        runCurrent()
        assertEquals(AddTvStep.Paired("Living room TV", livingKey), f.step)
        assertTrue(f.forgotten.isEmpty(), "the same key: its row is kept and replaced")
        f.clear()
    }

    /** Pair again for a TV set up again: its new key pairs, and the old row, under the same name, goes. */
    @Test
    fun `Pair again for a TV set up again replaces its old row`() = runTest {
        val newKey = KeyId.of(Random.nextBytes(91))
        val f = Fixture(this, again = livingKey)
        f.tvs.value = List(16) { if (it == 0) PairedTv(livingKey.hex, "Living room TV", 1L) else PairedTv(KeyId.of(Random.nextBytes(91)).hex, "TV $it", 1L) }
        f.vm.start()
        f.discovery.value = Discovery(0, listOf(found(newKey, "Living room TV", living)))
        runCurrent()
        assertEquals(AddTvStep.Nearby, f.step, "16 TVs kept, one of them to be replaced: room enough")
        f.vm.pick(f.vm.state.value.nearby.single())
        runCurrent()
        f.calls.single().result.complete(PairAttempt.Result.Paired(newKey, "Living room TV"))
        runCurrent()
        assertEquals(AddTvStep.Paired("Living room TV", newKey), f.step)
        assertEquals(listOf(livingKey), f.forgotten)
        f.clear()
    }

    /** Pair again that ends up pairing another TV, of another name, leaves the one it was for alone. */
    @Test
    fun `Pair again that pairs another TV keeps the one it was for`() = runTest {
        val f = Fixture(this, again = livingKey)
        f.tvs.value = listOf(PairedTv(livingKey.hex, "Living room TV", 1L))
        f.vm.start()
        f.vm.enterAddress()
        f.vm.type("192.168.1.31")
        f.vm.connect()
        runCurrent()
        f.calls.single().result.complete(PairAttempt.Result.Paired(bedroomKey, "Bedroom TV"))
        runCurrent()
        assertEquals(AddTvStep.Paired("Bedroom TV", bedroomKey), f.step)
        assertTrue(f.forgotten.isEmpty())
        f.clear()
    }

    /** The Living room TV as kept here: last reached at 192.168.1.42 on the default port. */
    private val keptLiving = PairedTv(livingKey.hex, "Living room TV", 1L, address = "192.168.1.42", port = PortChoice.DEFAULT)

    /**
     * A plain Add (no Pair again) of a new key under [name], picked from TVs nearby at 192.168.1.42, with [kept] kept here
     * and mDNS gone [unseen] ms without [kept]'s own id: the TVs it forgot.
     */
    private fun TestScope.plainAdd(kept: PairedTv, unseen: Long?, name: String = "Living room TV"): List<KeyId> {
        val newKey = KeyId.of(Random.nextBytes(91))
        val f = Fixture(this)
        f.tvs.value = listOf(kept)
        f.unseen = { if (it == kept.key) unseen else null }
        f.vm.start()
        f.discovery.value = Discovery(0, listOf(found(newKey, name, living)))
        runCurrent()
        f.vm.pick(f.vm.state.value.nearby.single())
        runCurrent()
        f.calls.single().result.complete(PairAttempt.Result.Paired(newKey, name))
        runCurrent()
        assertEquals(AddTvStep.Paired(name, newKey), f.step)
        f.clear()
        return f.forgotten
    }

    /**
     * A plain Add of a TV set up again (L30, reinstalled or reset there): a new key under the kept row's name, at the
     * address that row was last reached at, once mDNS has gone its 10 s without the row's own id (L12's check, the one its
     * row reads SetUpAgain by). The old row goes, as Pair again's would, so the TV isn't kept twice, one of them "Set up
     * again" for good.
     */
    @Test
    fun `a plain Add of a TV set up again replaces its old row`() = runTest {
        assertEquals(listOf(livingKey), plainAdd(keptLiving, unseen = TvLinksDeps.SET_UP_AGAIN_MS))
        assertEquals(listOf(livingKey), plainAdd(keptLiving, unseen = 60_000))
    }

    /** Another TV: of the same name but kept elsewhere, or of another name at the kept one's address. Both stay kept. */
    @Test
    fun `a plain Add of another TV keeps the kept one`() = runTest {
        assertEquals(emptyList<KeyId>(), plainAdd(keptLiving.copy(address = "192.168.1.31"), unseen = 60_000), "kept elsewhere")
        assertEquals(emptyList<KeyId>(), plainAdd(keptLiving.copy(name = "Kitchen TV"), unseen = 60_000), "another name")
    }

    /**
     * L12: DHCP may have given the kept TV's address to another NZStreamer TV of the same name (two of one model, one of
     * them off). Until mDNS has run 10 s without the kept TV's own id, a record lost a moment ago counting as seen, the
     * kept one stays: its record found now, discovery started a moment ago, the record gone 3 s, just short of 10 s, or
     * discovery not running at all.
     */
    @Test
    fun `a plain Add keeps a kept TV of the same name at its address until mDNS has gone 10 s without its id`() = runTest {
        for (unseen in listOf(0L, 500L, 3_000L, TvLinksDeps.SET_UP_AGAIN_MS - 1, null)) {
            assertEquals(emptyList<KeyId>(), plainAdd(keptLiving, unseen), "unseen for $unseen ms")
        }
    }
}
