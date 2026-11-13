package io.skjaere.usenet.app.link

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.link.KeyId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.random.Random

/**
 * The TV's record changing quickly through the app's own advertiser on a real device (L47): ten changes in about two
 * seconds, as Pair a phone opened and closed over and over would make them. NSD gets one call at a time, so an
 * in-process discovery then finds the record saying the latest, and a plain NsdManager registration still comes up
 * afterwards: the platform's mDNS advertiser didn't wedge, as unregistering a record mid-probe did on Android 14. And
 * the tests' fake TV, closed just after a change, returns with its record down. The record's id is random (L14) and its
 * name invented; the addresses in its `v4` are made up and nothing listens there.
 */
@RunWith(AndroidJUnit4::class)
class LinkNsdDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val advertiser = LinkNsd(context)
    private val open = mutableListOf<AutoCloseable>()
    private val sockets = mutableListOf<ServerSocket>()

    @After
    fun stop() {
        open.forEach { it.close() }
        sockets.forEach { it.close() }
        // Taken down before the next test, once NSD has said the record is up: nothing left on the network.
        val until = System.nanoTime() + 30_000_000_000
        while (!advertiser.settled && System.nanoTime() < until) Thread.sleep(50)
    }

    /** The record's [n]th change: `pair` on and off, and its `v4` one on each time. */
    private fun record(name: String, key: KeyId, n: Int) =
        ServiceRecord.of(name, 1, key, pairing = n % 2 == 1, v4 = InetAddress.getByAddress(byteArrayOf(10, 77, 0, n.toByte())) as Inet4Address)

    @Test
    fun tenQuickChangesEndOnTheLatestAndLeaveNsdWorking() {
        val name = inventedTvName("Nsd test TV")
        val key = KeyId.of(Random.nextBytes(91))
        val port = ServerSocket(0).also { sockets += it }.localPort
        var handle: AutoCloseable? = null
        val started = System.nanoTime()
        for (n in 1..10) {
            handle?.close()
            handle = advertiser.advertise(record(name, key, n), port, null)
            if (n < 10) Thread.sleep(200)
        }
        open += handle!!
        val changedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("ten changes in about 2 s: $changedMillis ms", changedMillis < 3_000)

        // The latest record, as a phone would find it: its id, pair=0 and the tenth address.
        val latest = record(name, key, 10)
        val until = System.nanoTime() + 15_000_000_000
        var found: ServiceRecord? = null
        while (found == null && System.nanoTime() < until) {
            val left = (until - System.nanoTime()) / 1_000_000
            found = NsdLookup(context).find(minOf(left, 5_000)) { record ->
                ServiceRecord.read(record.name, record.txt)?.takeIf { it.id == key.short }?.let { it.pairing == latest.pairing && it.v4 == latest.v4 } == true
            }?.let { ServiceRecord.read(it.name, it.txt) }
        }
        assertNotNull("the record with the latest TXT within 15 s", found)
        assertEquals(latest.txt, found!!.txt)

        // The wedge check: a plain registration, outside the app's advertiser, still comes up.
        val plain = ServerSocket(0).also { sockets += it }.localPort
        val outcome = CompletableFuture<Int?>()
        val down = CountDownLatch(1)
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registered: NsdServiceInfo) {
                outcome.complete(null)
            }

            override fun onRegistrationFailed(failed: NsdServiceInfo, errorCode: Int) {
                outcome.complete(errorCode)
            }

            override fun onServiceUnregistered(unregistered: NsdServiceInfo) = down.countDown()

            override fun onUnregistrationFailed(failed: NsdServiceInfo, errorCode: Int) = down.countDown()
        }
        val info = NsdServiceInfo().apply {
            serviceName = "NZStreamer wedge check ${Random.nextInt(100_000, 999_999)}"
            serviceType = ServiceRecord.TYPE
            this.port = plain
            setAttribute("id", "%016x".format(Random.nextLong()))
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        val error = try {
            outcome.get(15, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            TIMED_OUT
        }
        // Taken down only once its answer came (L47); a failed one holds nothing.
        if (error == null || error == TIMED_OUT) {
            nsd.unregisterService(listener)
            down.await(10, TimeUnit.SECONDS)
        }
        assertEquals("a plain registration after the changes: NSD error, or $TIMED_OUT for none in 15 s", null, error)
    }

    /**
     * A fake TV closed just after its record changed: close() returns only once the record is down and NSD has said so.
     * Otherwise a run whose last test closes one that way leaves the removal to the system as the process ends, while
     * the record may still be probing: the removal L47 keeps from the advertiser.
     */
    @Test
    fun aFakeTvClosedJustAfterItsRecordChangedReturnsWithTheRecordDown() {
        val tv = FakeTv(context, inventedTvName("Nsd test TV"))
        try {
            // Its first record is being registered: NSD hasn't said how that went, as a probe takes a second or so.
            val until = System.nanoTime() + 5_000_000_000
            while (!tv.advertising && System.nanoTime() < until) Thread.sleep(5)
            assertTrue("the fake TV advertises nothing", tv.advertising)
            tv.pairing()
            tv.close()
            assertFalse("closed with its record still up or waited on", tv.advertising)
        } finally {
            // However close() went, nothing is left waiting on NSD as this test ends.
            val until = System.nanoTime() + 30_000_000_000
            while (tv.advertising && System.nanoTime() < until) Thread.sleep(50)
        }
    }

    private companion object {
        /** Not one of NsdManager's codes: no answer in time. */
        const val TIMED_OUT = -1
    }
}
