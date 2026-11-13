package io.skjaere.usenet.app.link

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The TV's record through the advertiser (L47), over a stand-in for NsdManager that the test answers for: one NSD call
 * at a time per record. No unregister before the registration is heard of, no new register before the old record is
 * down, changes made meanwhile coming to the latest record alone (or none), an answer that never comes given up after
 * its time with one line, closing while it waits still taking the record down once it is up and leaving nothing held,
 * and a failed registration blocking nothing. The records are invented; each is known here by its `v4`'s last number.
 */
class LinkNsdTest {
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "link-nsd-test").apply { isDaemon = true } }
    private val nsd = FakeNsd()
    private val lines = CopyOnWriteArrayList<String>()

    @AfterEach
    fun stop() {
        executor.shutdownNow()
    }

    private fun advertiser(giveUpMillis: Long = 60_000) = LinkNsd(nsd, executor, giveUpMillis) { lines += it }

    /** The TV's record, as its [n]th change makes it: `pair` and `v4` move, the rest stays. */
    private fun record(n: Int) = ServiceRecord(
        "Living room TV", 1, "0123456789abcdef", pairing = n % 2 == 1,
        v4 = InetAddress.getByAddress(byteArrayOf(10, 0, 0, n.toByte())) as Inet4Address,
    )

    private fun LinkNsd.advertise(n: Int): AutoCloseable = advertise(record(n), 28_462, null)

    /** Whatever the advertiser was told, or heard, has been dealt with. */
    private fun settle() {
        executor.submit {}.get(5, TimeUnit.SECONDS)
    }

    private fun eventually(what: String, millis: Long = 5_000, condition: () -> Boolean) {
        val until = System.nanoTime() + millis * 1_000_000
        while (!condition()) {
            assertTrue(System.nanoTime() < until, "$what; NSD was asked ${nsd.calls}")
            Thread.sleep(10)
        }
    }

    @Test
    fun `no unregister before its registration is heard of`() {
        val ads = advertiser()
        ads.advertise(1).close()
        settle()
        assertEquals(listOf("register 1"), nsd.calls, "unregistered before its registration was heard of")
        nsd.registered(1)
        settle()
        assertEquals(listOf("register 1", "unregister 1"), nsd.calls)
        nsd.unregistered(1)
        settle()
        assertEquals(listOf("register 1", "unregister 1"), nsd.calls)
    }

    @Test
    fun `no new register before the old record is down`() {
        val ads = advertiser()
        val first = ads.advertise(1)
        nsd.registered(1)
        settle()
        first.close()
        ads.advertise(2)
        settle()
        assertEquals(listOf("register 1", "unregister 1"), nsd.calls, "registered again before the old record was down")
        nsd.unregistered(1)
        settle()
        assertEquals(listOf("register 1", "unregister 1", "register 2"), nsd.calls)
        // An unregistration that failed is an end too: the next goes on.
        nsd.registered(2)
        settle()
        assertEquals(listOf("register 1", "unregister 1", "register 2"), nsd.calls, "the latest left up")
    }

    @Test
    fun `an unregistration that failed lets the next record go on`() {
        val ads = advertiser()
        val first = ads.advertise(1)
        nsd.registered(1)
        settle()
        first.close()
        ads.advertise(2)
        settle()
        nsd.unregistrationFailed(1)
        settle()
        assertEquals(listOf("register 1", "unregister 1", "register 2"), nsd.calls)
    }

    @Test
    fun `changes made while it waits come to the latest record alone`() {
        val ads = advertiser()
        var handle = ads.advertise(1)
        for (n in 2..5) {
            handle.close()
            handle = ads.advertise(n)
        }
        settle()
        assertEquals(listOf("register 1"), nsd.calls)
        nsd.registered(1)
        settle()
        assertEquals(listOf("register 1", "unregister 1"), nsd.calls)
        // More while the first goes down.
        for (n in 6..9) {
            handle.close()
            handle = ads.advertise(n)
        }
        nsd.unregistered(1)
        settle()
        assertEquals(listOf("register 1", "unregister 1", "register 9"), nsd.calls, "only the latest registered")
        nsd.registered(9)
        settle()
        assertEquals(listOf("register 1", "unregister 1", "register 9"), nsd.calls, "the latest left up")
    }

    @Test
    fun `changes made while it waits that end with none register nothing more`() {
        val ads = advertiser()
        ads.advertise(1).close()
        ads.advertise(2).close()
        ads.advertise(3).close()
        nsd.registered(1)
        settle()
        nsd.unregistered(1)
        settle()
        assertEquals(listOf("register 1", "unregister 1"), nsd.calls)
        assertTrue(nsd.held.isEmpty())
        assertFalse(nsd.multicast, "the multicast lock given back")
    }

    /**
     * The give-up is a second here, far beyond anything the test waits on before it: a loaded machine's pause can't let
     * it fire early and make the test fail on a change that never happened.
     */
    @Test
    fun `an answer that never comes is given up after its time, with one line each`() {
        val ads = advertiser(giveUpMillis = 1_000)
        ads.advertise(1).close()
        ads.advertise(2)
        // The close and the next record are dealt with while the registration is waited on: settle() says so.
        settle()
        assertEquals(listOf("register 1"), nsd.calls, "nothing more while the registration may still be heard of")
        // No word of the registration: taken down as if it were up. No word of that either: the next record goes on.
        eventually("both given up", millis = 10_000) { nsd.calls.size >= 3 }
        assertEquals(listOf("register 1", "unregister 1", "register 2"), nsd.calls)
        assertEquals(2, lines.size, "one line each: $lines")
        assertTrue("registration" in lines[0] && "taken down" in lines[1], "the registration's line, then the unregistration's: $lines")
        assertTrue(lines.none { "Living room" in it || "10.0.0" in it || "0123456789abcdef" in it }, "nothing of the record: $lines")
        // Late words of the first change nothing; the second's, in its time, ends its wait.
        nsd.registered(1)
        nsd.unregistered(1)
        nsd.registered(2)
        settle()
        Thread.sleep(1_200)
        assertEquals(listOf("register 1", "unregister 1", "register 2"), nsd.calls)
        assertEquals(2, lines.size, "nothing given up once heard: $lines")
    }

    @Test
    fun `closing while it waits still takes the record down once it is up, and holds nothing after`() {
        val ads = advertiser()
        ads.advertise(1).close()
        settle()
        assertTrue(nsd.multicast, "the multicast lock held while the record may come up")
        nsd.registered(1)
        settle()
        assertEquals(listOf("register 1", "unregister 1"), nsd.calls, "taken down once up")
        assertTrue(nsd.multicast, "held until it is down")
        nsd.unregistered(1)
        settle()
        assertTrue(nsd.held.isEmpty(), "NSD holds no listener: ${nsd.held}")
        assertFalse(nsd.multicast, "the multicast lock given back")
    }

    @Test
    fun `a failed registration blocks nothing, and isn't taken down`() {
        val ads = advertiser()
        val first = ads.advertise(1)
        nsd.registrationFailed(1)
        settle()
        first.close()
        ads.advertise(2)
        settle()
        assertEquals(listOf("register 1", "register 2"), nsd.calls)
        // One NsdManager refuses at once (a name it finds bad, say): nothing waits on it either.
        nsd.refuseAtOnce = true
        val ads2 = advertiser()
        val refused = ads2.advertise(3)
        settle()
        refused.close()
        nsd.refuseAtOnce = false
        val next = ads2.advertise(4)
        settle()
        assertEquals(listOf("register 1", "register 2", "register 3", "register 4"), nsd.calls)
        next.close()
        settle()
        nsd.registered(4)
        settle()
        assertEquals(listOf("register 1", "register 2", "register 3", "register 4", "unregister 4"), nsd.calls)
    }

    /**
     * NsdManager as the test answers for it: each call in [calls], in order, by the record's number; [held] the
     * registrations it would hold now, from the register until an answer ends them. Answers come on the test's thread,
     * as NsdManager's come on a thread of its own.
     */
    private class FakeNsd : NsdCalls {
        val calls = CopyOnWriteArrayList<String>()
        val held: MutableSet<Int> = ConcurrentHashMap.newKeySet()
        private val heard = ConcurrentHashMap<Int, NsdHeard>()
        @Volatile var multicast = false
        @Volatile var refuseAtOnce = false

        private fun number(advert: Advert) = advert.record.v4!!.address[3].toInt()

        override fun register(advert: Advert, heard: NsdHeard): Any? {
            val n = number(advert)
            calls += "register $n"
            if (refuseAtOnce) return null
            this.heard[n] = heard
            held += n
            return n
        }

        override fun unregister(registration: Any): Boolean {
            val n = registration as Int
            calls += "unregister $n"
            return n in held
        }

        override fun multicast(held: Boolean) {
            multicast = held
        }

        /** Record [n]'s registration, once the advertiser has asked for it: an answer comes only after its call. */
        private fun heard(n: Int): NsdHeard {
            val until = System.nanoTime() + 5_000_000_000
            while (System.nanoTime() < until) {
                heard[n]?.let { return it }
                Thread.sleep(5)
            }
            throw AssertionError("record $n was never registered; NSD was asked $calls")
        }

        fun registered(n: Int) = heard(n).registered()

        fun registrationFailed(n: Int) {
            val registration = heard(n)
            held -= n
            registration.registrationFailed(-1)
        }

        fun unregistered(n: Int) {
            val registration = heard(n)
            held -= n
            registration.unregistered()
        }

        fun unregistrationFailed(n: Int) {
            val registration = heard(n)
            held -= n
            registration.unregistrationFailed(-1)
        }
    }
}
