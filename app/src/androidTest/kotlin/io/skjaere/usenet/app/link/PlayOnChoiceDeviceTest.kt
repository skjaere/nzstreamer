package io.skjaere.usenet.app.link

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.cast.CastFakes
import io.skjaere.usenet.app.cast.FakeConnector
import io.skjaere.usenet.app.cast.FakeRoutes
import io.skjaere.usenet.app.cast.GoogleCast
import io.skjaere.usenet.app.cast.castRoute
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.onMainThread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * W18, L36: the TV chosen in Play on is held in memory alone, as MainActivity keeps it: a recreation (a rotation, back
 * from Recents) keeps it through MainActivity's saved state, and a new launch (MainActivity made with none) is This phone
 * again. A TV in the test's process on this device's own address, kept in the app's link.json while the test runs; it is
 * invented, and leaves link.json after. A Cast session alive in the process wins over a new launch (W38, L64): a fake
 * one here, as Google's software never starts in these tests (L73).
 */
@RunWith(AndroidJUnit4::class)
class PlayOnChoiceDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val link = container.data.link
    private var tv: FakeTv? = null

    @Before
    fun setUp() {
        assumeFalse("a TV has no Play on", container.television)
    }

    @After
    fun tearDown() {
        container.playOn.choose(null)
        CastFakes.reset()
        tv?.let {
            it.close()
            runBlocking { link.forgetTv(it.key.id) }
        }
    }

    @Test
    fun theChoiceOutlivesARecreationAndANewLaunchIsThisPhoneAgain() {
        val phone = runBlocking { link.identity().identity.key.id }
        val fake = FakeTv(context, inventedTvName("Living room TV"), paired = setOf(phone), advertised = false).also { tv = it }
        assertTrue(runBlocking { link.addTv(fake.key.id, fake.name, fake.address.hostAddress!!, fake.port) })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().runOnMainSync { container.playOn.choose(fake.key.id) }
            scenario.recreate()
            assertEquals("kept across a recreation", PlayChoice.Tv(fake.key.id), container.playOn.chosen.value)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            assertEquals("a new launch is This phone again", PlayChoice.Phone, container.playOn.chosen.value)
        }
    }

    @Test
    fun aLiveCastIsStillChosenAfterANewLaunch() {
        val connector = FakeConnector()
        CastFakes.started(FakeRoutes(), connector, CopyOnWriteArrayList())
        val kitchen = castRoute("Kitchen display")
        val choice = PlayChoice.Cast(kitchen.id, kitchen.name)
        // Kitchen display tapped on the sheet, and connected.
        onMainThread {
            GoogleCast.connection.attach(connector, context)
            GoogleCast.connection.connect(kitchen)
            container.playOn.choose(choice)
        }
        connector.started(kitchen)
        val until = android.os.SystemClock.uptimeMillis() + 10_000
        while (container.playOn.target.value != PlayTarget.Cast(kitchen.id, kitchen.name, CastLink.Connected, kitchen.facts)) {
            assertTrue("connected; now ${container.playOn.target.value}", android.os.SystemClock.uptimeMillis() < until)
            Thread.sleep(50)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            assertEquals("a live cast is still chosen on a new launch", choice, container.playOn.chosen.value)
        }
    }
}
