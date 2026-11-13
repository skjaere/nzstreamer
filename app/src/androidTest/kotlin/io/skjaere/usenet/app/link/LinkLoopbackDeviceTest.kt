package io.skjaere.usenet.app.link

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.setup.LanBinding
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.link.FinalKind
import io.skjaere.usenet.link.LinkMessage.Hello
import io.skjaere.usenet.link.Playback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Play on TV end to end on one device (item 28, L14): both roles on phone34, over a pinned link on this device's own
 * address. The TV role is the app's own: its link key and link.json, its plays (TvPlays), status, Finals and outbox, its
 * coordinator and its player, MainActivity in front taking the launch as NZStreamer open on a TV does. The phone role is a
 * phone's links, start, bar and Finals with its own key and store, so it never pairs with the app's own key. Play goes,
 * the TV's player plays, the bar reads Playing; Pause pauses that player and the bar reads Paused; Stop leaves it; its
 * Final reaches the phone, which saves it (recordRemoteProgress, through its recorder seam: both roles share one
 * database, whose row the TV role's own save writes too) and acks it, and the TV's outbox for the phone is empty. No
 * indexer is asked and no mock is needed (L40). The phone, the TV and the film are invented; the app's link.json is put
 * back after.
 */
@RunWith(AndroidJUnit4::class)
class LinkLoopbackDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its window. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val link = container.data.link
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val home = HomeContentShown()
    private val phones = PairedPhones()
    private val film = PlayableFilm(BELL, "Harbor Bell")
    private var phone: PhoneRole? = null
    private var port: Int? = null

    @Before
    fun setUp() {
        assumeFalse("a TV plays a phone's Play itself: tv.TvIncomingPlayTest", container.television)
        home.show()
        phones.clear()
        port = runBlocking { link.file.first().port }
        runBlocking { film.prepare() }
    }

    @After
    fun tearDown() {
        phone?.close()
        scope.cancel()
        film.close()
        phones.restore()
        home.restore()
        // The TV role took a port; this phone's own listener never listens, but the file is put back as it was where it can.
        port?.let { runBlocking { link.setPort(it) } }
    }

    private fun owed(key: io.skjaere.usenet.link.KeyId) = runBlocking { link.owed(key) }

    @Test
    fun playPauseAndStopReachTheTvsPlayerAndItsFinalIsSavedAndAcked() = runBlocking<Unit> {
        val tvName = "Loopback TV"
        val role = PhoneRole(name = "Loopback phone").also { phone = it }
        // The TV role keeps the phone, as a pairing would have, and listens on this device's own address.
        phones.add("Loopback phone", role.key.id)
        val host = TvLinkHost(
            scope,
            TvLinkDeps(
                television = true, inFront = MutableStateFlow(true), store = link, identity = { link.identity().identity.key },
                addresses = flowOf(LanBinding(lanAddress(), null)), advertiser = { _, _, _ -> AutoCloseable {} }, name = tvName, app = "0.1.0",
                ready = flowOf(Hello.Ready(provider = true, indexer = true, tmdb = false)), defaultPort = 0,
                commands = container.tvPlays, links = container.tvPhoneLinks,
            ),
        )
        host.start()
        val listening = waitFor("the TV role listening") { host.state.value as? TvListener.Listening }
        val tv = link.identity().identity.key.id
        assertTrue(role.store.addTv(tv, tvName, listening.address.hostAddress!!, listening.port))
        // Chosen on the phone: its link up, and the TV able to play.
        assertEquals(TvNow(TvState.Ready, linked = true), role.choose(tv))
        // NZStreamer in front, as on a TV: the launch goes to its player.
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            role.play(tv, tvName, film.play)
            val session = try {
                waitFor("the bar reads Playing", 60_000) {
                    role.playing.now.value?.takeIf { it.state == Playback.Playing && it.bar == RemoteBar.Playing && it.positionMs >= 1_000 }
                }.session
            } catch (e: AssertionError) {
                throw AssertionError(
                    "the bar never read Playing: start ${role.start.now.value}, bar ${role.playing.now.value}, links ${role.links.states.value}, " +
                        "the TV's sheet ${container.playback.state.value::class.simpleName}, its player ${container.playerInFront.now.value}, listener ${host.state.value}",
                    e,
                )
            }
            assertEquals(WatchKey.movie(BELL), role.playing.now.value?.watchKey)
            assertEquals("the TV's player plays the phone's session", session, container.playerInFront.now.value?.remote?.session)
            assertEquals("the phone kept the session (L33)", listOf(session), role.store.tvs.first().single().sessions.map { it.id })

            role.playing.pause()
            waitFor("the TV's player paused") { container.playerInFront.now.value?.takeIf { it.state == Playback.Paused } }
            waitFor("the bar reads Paused") { role.playing.now.value?.takeIf { it.bar == RemoteBar.Paused } }

            val player = onMainThread { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlayerActivity>().single() }
            role.playing.stop()
            waitFor("the TV's player gone") { player.isDestroyed.takeIf { it } }
            val final = waitFor("the Final saved on the phone (recordRemoteProgress)", 30_000) { role.recorded.firstOrNull() }
            assertEquals(session to WatchKey.movie(BELL), final.session to final.watchKey)
            assertEquals(FinalKind.Movie to BELL, final.kind to final.movieTmdbId)
            assertTrue("stopped past a second: ${final.positionMs} ms", final.positionMs >= 1_000 && !final.watched)
            // Owed as it went out, then let go.
            assertEquals("acked", listOf(final.id), role.acked)
            waitFor("acked: the TV's outbox for the phone empty") { owed(role.key.id).takeIf { it.isEmpty() } }
            waitFor("the bar gone on the TV's stopped status") { (role.playing.now.value == null).takeIf { it } }
            assertEquals(stoppedOnAt(tvName, final.positionMs), role.playing.said.value?.text)
            assertEquals("one Final for the one play", 1, role.recorded.size)
            assertEquals("no indexer asked", 0, film.grabs.get())
            assertFalse(container.playback.startingPlay)
        }
    }

    private companion object {
        /** An invented film's TMDB id: nothing real has it. */
        const val BELL = 900_061
    }
}
