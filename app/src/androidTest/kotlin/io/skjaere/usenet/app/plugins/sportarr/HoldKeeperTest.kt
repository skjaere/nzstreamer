package io.skjaere.usenet.app.plugins.sportarr

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.skjaere.usenet.app.plugins.PluginStateRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ConnectException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The keeper of a test's hold of the fake Sportarr, which every Sportarr device class runs while it holds the fake: a
 * renewal that fails (the fake not answering for a moment) leaves its thread, so nothing reaches the process's uncaught
 * handler, which would end the instrumentation and every class after it; and a renewal under way when the test gives the
 * fake back lands before stop() returns, never after the give-back. No fake is asked: the renewals here are the test's.
 */
@RunWith(AndroidJUnit4::class)
class HoldKeeperTest {
    @get:Rule
    val plugins = PluginStateRule()

    @Test
    fun aFailedRenewalLeavesTheThreadAndTheRenewalsGoOn() {
        val uncaught = CopyOnWriteArrayList<String>()
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        // In the test's place, the instrumentation's own handler would end the process at the first exception.
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e.toString() }
        val asked = AtomicInteger()
        val again = CountDownLatch(1)
        val keeper = HoldKeeper(everyMs = 20) {
            if (asked.incrementAndGet() == 1) throw ConnectException("the fake didn't answer")
            again.countDown()
        }
        try {
            val renewed = again.await(WAIT_S, TimeUnit.SECONDS)
            assertEquals("nothing left the renewals' thread", emptyList<String>(), uncaught.toList())
            assertTrue("renewed again after a renewal failed (asked ${asked.get()} times)", renewed)
            assertTrue("the renewals' thread runs on", keeper.alive)
        } finally {
            keeper.stop(STOP_MS)
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }
        assertFalse("the renewals' thread ended with stop", keeper.alive)
    }

    @Test
    fun aRenewalUnderWayLandsBeforeStopReturns() {
        val started = CountDownLatch(1)
        val landed = AtomicBoolean(false)
        val keeper = HoldKeeper(everyMs = 20) {
            started.countDown()
            // A request under way, as an ask is: an interrupt doesn't end it.
            SystemClock.sleep(UNDER_WAY_MS)
            landed.set(true)
        }
        try {
            assertTrue("a renewal started", started.await(WAIT_S, TimeUnit.SECONDS))
        } finally {
            keeper.stop(STOP_MS)
        }
        assertTrue("the renewal under way landed before stop returned", landed.get())
        assertFalse("the renewals' thread ended with stop, so none comes after", keeper.alive)
    }

    private companion object {
        const val WAIT_S = 10L
        const val STOP_MS = 5_000L
        const val UNDER_WAY_MS = 300L
    }
}
