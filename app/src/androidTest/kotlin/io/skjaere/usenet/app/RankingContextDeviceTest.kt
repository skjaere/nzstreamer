package io.skjaere.usenet.app

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.cast.CastFakes
import io.skjaere.usenet.app.cast.FakeConnector
import io.skjaere.usenet.app.cast.FakeRoutes
import io.skjaere.usenet.app.cast.castRoute
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.PlayOrigin
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.releases.RankingTarget
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The app's ranking, as every search and play asks for it, on the device it runs on: a TV ranks by a TV's rules (above
 * 1080p hardware decoders only, the screen's HDR), a phone or tablet as before (TV-5's L6 and L10); and the display and
 * decoders it ranked by are atop a shared log (L12). Runs on the phone and the TV lanes alike.
 *
 * On a phone it ranks for whom Play goes to (L127): the Cast device chosen in Play on, by its model's profile (a fake
 * session, Google's software never started, L73); inside a cast play's job, the job's own device; inside a play's job on
 * this phone, this phone, a Cast device chosen or not. The devices are invented.
 */
@RunWith(AndroidJUnit4::class)
class RankingContextDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theRankingFollowsTheFormFactorAndTheLogHeaderSaysTheDevice() = runBlocking<Unit> {
        val ranking = context.container.rankingContext()
        Log.i("RankingContext", "tv=${ranking.tv} displayHeight=${ranking.device.displayHeight} hdr=${ranking.device.hdrTypes}")

        assertEquals(FormFactor.of(context) == FormFactor.Tv, ranking.tv)
        val header = context.container.logHeader.lines()
        assertTrue(header.joinToString("\n"), header.any { it.startsWith("Display: ") } && header.any { it.startsWith("Video: ") })
    }

    /**
     * L127, L128, W53: a Shield chosen in Play on, connected over a fake session: releases rank for it, its 2160 lines
     * counting as 4K; a cast play's job ranks for the job's own device (here a first Chromecast, 1080p), and a play's job
     * on this phone for this phone, whatever is chosen.
     */
    @Test
    fun aCastDeviceChosenRanksForItAndAPlaysJobForItsOwnDevice() {
        val container = context.container
        assumeFalse("a TV never casts (tv.TvNoCastTest)", container.television)
        val routes = FakeRoutes()
        val connector = FakeConnector()
        val den = castRoute("Den TV", model = "SHIELD Android TV")
        try {
            CastFakes.started(routes, connector, CopyOnWriteArrayList())
            CastFakes.connected(den, connector)
            container.playOn.choose(PlayChoice.Cast(den.id, den.name))
            waitUntil("Den TV connected") { container.playOn.target.value == PlayTarget.Cast(den.id, den.name, CastLink.Connected, den.facts) }

            val chosen = runBlocking { container.rankingContext() }
            assertEquals(RankingTarget.Cast("Den TV"), chosen.target)
            assertEquals("the Shield counts as 4K", 2160, chosen.device.displayHeight)
            assertEquals("the app's form factor", false, chosen.tv)

            val kitchen = CastTo("route:kitchen", "Kitchen display", device = "device-kitchen", model = "Chromecast")
            val inItsJob = runBlocking { withContext(PlayOrigin(cast = kitchen)) { container.rankingContext() } }
            assertEquals(RankingTarget.Cast("Kitchen display"), inItsJob.target)
            assertEquals(1080, inItsJob.device.displayHeight)

            val here = runBlocking { withContext(PlayOrigin.Here) { container.rankingContext() } }
            assertEquals(RankingTarget.Device, here.target)
            container.playOn.choose(PlayChoice.Phone)
            waitUntil("This phone chosen") { container.playOn.target.value == PlayTarget.Phone }
            assertEquals("This phone chosen: this phone's", RankingTarget.Device, runBlocking { container.rankingContext() }.target)
        } finally {
            container.playOn.choose(PlayChoice.Phone)
            CastFakes.reset()
        }
    }

    private fun waitUntil(what: String, timeoutMs: Long = 10_000, probe: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (!probe()) {
            if (SystemClock.uptimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(50)
        }
    }
}
