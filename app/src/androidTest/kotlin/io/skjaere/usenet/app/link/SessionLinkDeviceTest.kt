package io.skjaere.usenet.app.link

import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.data.TvSession
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.link.FinalKind
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.LinkMessage.Final
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W5, L33, L36: a TV this phone started a play on is linked as the app comes to the front, though it isn't chosen and no
 * page lists it (after a restart nothing else would hold it), so what it owes this phone arrives; then it is let go. A TV
 * in the test's process on this device's own address, paired with the app's key, kept in its link.json with a session
 * (timings shrunk, L46). Nothing of the app's own activities is in front as the test starts. The TV and the films are
 * invented; it leaves the app's link.json after.
 */
@RunWith(AndroidJUnit4::class)
class SessionLinkDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val link = container.data.link
    private val timings = PlayOnTimings.current
    private var tv: FakeTv? = null

    @Before
    fun setUp() {
        assumeFalse("a TV plays itself", container.television)
        PlayOnTimings.current = PlayOnTimings(sessionLinkMillis = 4_000)
    }

    @After
    fun tearDown() {
        PlayOnTimings.current = timings
        tv?.let {
            it.close()
            runBlocking { link.forgetTv(it.key.id) }
        }
    }

    @Test
    fun aTvThisPhonePlayedOnIsLinkedAsTheAppComesToTheFrontAndWhatItOwesArrives() = runBlocking<Unit> {
        val phone = link.identity().identity.key.id
        val fake = FakeTv(context, inventedTvName("Living room TV"), paired = setOf(phone), advertised = false).also { tv = it }
        assertTrue(link.addTv(fake.key.id, fake.name, fake.address.hostAddress!!, fake.port))
        link.addSession(fake.key.id, TvSession("s-owed", System.currentTimeMillis(), movieTmdbId = FILM))
        waitFor("the app out of the front") { true.takeIf { !container.inFront.value.value } }
        ActivityScenario.launch(ComponentActivity::class.java).use {
            val up = waitFor("a link from the phone") { fake.links.lastOrNull() }
            assertEquals("not chosen", PlayTarget.Phone, container.playOn.target.value)
            // What the TV owes goes out on its link up: here one of another session, which the phone drops and acks.
            up.send(Final("f-owed", "s-other", WatchKey.movie(OTHER), FinalKind.Movie, "Copper Crown", movieTmdbId = OTHER, positionMs = 600_000, durationMs = 6_240_000, watched = false, ageMs = 5_000))
            waitFor("the Final acked") { fake.received.filterIsInstance<LinkMessage.FinalAck>().firstOrNull { "f-owed" in it.ids } }
            // Then let go: nothing else holds it.
            assertTrue(waitFor("the link let go", timeoutMs = 20_000) { true.takeIf { fake.links.isEmpty() } })
        }
    }

    private companion object {
        /** Invented films' TMDB ids; nothing real has them. */
        const val FILM = 900_051
        const val OTHER = 900_052
    }
}
