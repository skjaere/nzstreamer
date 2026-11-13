package io.skjaere.usenet.app.tv

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.GapFixtures
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.LocalNntp
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.link.InFront
import io.skjaere.usenet.app.link.NsdLookup
import io.skjaere.usenet.app.link.PairedPhones
import io.skjaere.usenet.app.link.PeerNames
import io.skjaere.usenet.app.link.ServiceRecord
import io.skjaere.usenet.app.link.TvListener
import io.skjaere.usenet.app.link.accepts
import io.skjaere.usenet.app.link.ownAddresses
import io.skjaere.usenet.app.mockOnEngine
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.pressHome
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Play on TV's listener on a real TV (tv36, L4, L27, L31): with a phone paired it listens on the TV's own private IPv4
 * address while Home is in front and while a player plays over it, and closes a second after Home; with no phone paired
 * and no pairing nothing listens; and a look on the network, made from inside the app as a phone would, finds the TV's
 * record by its id with pv, id and v4 and nothing about what plays. The film is dip.mkv, posted on a server inside the
 * test's process ([LocalNntp]) under an invented title (TMDB id 900031); the phones are invented too.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class TvLinkHostTest {
    /** The player hides the system bars: Android's hint about that would take its window. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val host = container.tvLink
    private val phones = PairedPhones()
    private val realLookUp = PlayerActivity.lookUpSegments
    private val local = LocalNntp()
    private val fixtures = GapFixtures(local, INDEXER, WatchTarget(WatchKind.Movie, "Harbor Bell", movieTmdbId = FILM))

    @Before
    fun setUp() {
        assumeTv()
        phones.clear()
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
    }

    @After
    fun tearDown() {
        PlayerActivity.lookUpSegments = realLookUp
        local.close()
        phones.restore()
        runBlocking {
            container.library.recordProgress(WatchState(WatchKey.movie(FILM), WatchKind.Movie, movieTmdbId = FILM, title = "Harbor Bell", updatedAt = 0), 0, 20_000)
        }
    }

    private inline fun <reified A : Activity> live(stage: Stage): List<A> =
        onMainThread { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).filterIsInstance<A>() }

    private fun stages(): String = Stage.values().joinToString { stage -> "$stage ${live<MainActivity>(stage).size}" }

    private fun until(what: String, timeoutMs: Long = 10_000, done: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!done()) {
            check(SystemClock.uptimeMillis() < deadline) { "timed out waiting for $what (the listener: ${host.state.value})" }
            Thread.sleep(20)
        }
    }

    /** The listener, once it listens: on one of this TV's own private IPv4 addresses, and taking connections. */
    private fun listening(): TvListener.Listening {
        until("the listener") { host.state.value is TvListener.Listening }
        val on = host.state.value as TvListener.Listening
        assertTrue("a private IPv4 address: ${on.address}", on.address.isSiteLocalAddress)
        assertTrue("one of this TV's own", on.address in ownAddresses())
        assertTrue("taking connections", accepts(on.address, on.port))
        return on
    }

    private fun home(): ActivityScenario<MainActivity> = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).also { main ->
        until("Home in front") { live<MainActivity>(Stage.RESUMED).isNotEmpty() }
    }

    /** dip.mkv playing in the player, started over the app's screens as the play sheet starts one; [check] while it plays. */
    private fun play(main: ActivityScenario<MainActivity>, check: (PlayerActivity) -> Unit) = runBlocking<Unit> {
        engineSetup("the engine on the test's own NNTP server") {
            mockOnEngine()
            container.engine.configure(EngineSettings(listOf(local.provider)))
        }
        val release = fixtures.release("dip.mkv", fixtures.media("dip.mkv"), 32_000, "harbor-bell-link", TITLE) { emptySet() }
        val launch = fixtures.launch(release.prepared, "harbor-bell-link", 0)
        main.onActivity { PlayerActivity.start(it, launch) }
        val player = waitFor("the film playing", timeoutMs = 30_000) { live<PlayerActivity>(Stage.RESUMED).firstOrNull { onMainThread { it.player?.isPlaying == true } } }
        try {
            check(player)
        } finally {
            onMainThread { player.finish() }
            until("the player gone") { live<PlayerActivity>(Stage.RESUMED).isEmpty() && live<PlayerActivity>(Stage.PAUSED).isEmpty() }
        }
    }

    @Test
    fun listensWhileHomeIsInFrontAndWhileAPlayerPlays() {
        phones.add()
        home().use { main ->
            val on = listening()
            play(main) {
                until("Home stopped under the player (Home: ${stages()})") { live<MainActivity>(Stage.STOPPED).isNotEmpty() }
                // Long past InFront's grace: the player counts as the app in front.
                Thread.sleep(InFront.GRACE_MS * 2)
                assertEquals("still listening, where it was", on, host.state.value)
                assertTrue(accepts(on.address, on.port))
            }
        }
    }

    @Test
    fun closedASecondAfterHome() {
        phones.add()
        home().use {
            val on = listening()
            pressHome()
            until("Home pressed") { live<MainActivity>(Stage.STOPPED).isNotEmpty() }
            val stopped = SystemClock.uptimeMillis()
            Thread.sleep(300)
            assertEquals("still listening within InFront's grace", on, host.state.value)
            until("closed", timeoutMs = 5_000) { host.state.value == TvListener.Off }
            val after = SystemClock.uptimeMillis() - stopped
            assertTrue("closed only after the grace: $after ms", after >= InFront.GRACE_MS - 200)
            assertFalse("the port closed", accepts(on.address, on.port))
        }
    }

    @Test
    fun nothingListensWithNoPhonePairedAndNoPairing() {
        home().use {
            Thread.sleep(3_000)
            assertEquals(TvListener.Off, host.state.value)
            val port = runBlocking { container.data.link.file.first().port }
            if (port != null) {
                ownAddresses().filterIsInstance<java.net.Inet4Address>().filter { it.isSiteLocalAddress }.forEach {
                    assertFalse("nothing on $it:$port", accepts(it, port))
                }
            }
            val id = runBlocking { container.data.link.identity().identity.key.id.short }
            assertEquals("no record", null, NsdLookup(context).find(5_000) { ServiceRecord.read(it.name, it.txt)?.id == id })
        }
    }

    /** As a phone finds the TV: by the record's id, which gives the version and the address it listens on, and nothing of the film playing. */
    @Test
    fun aDiscoveryInTheAppSeesPvAndIdAndNoTitle() {
        phones.add()
        val id = runBlocking { container.data.link.identity().identity.key.id }
        home().use { main ->
            val on = listening()
            play(main) {
                val found = NsdLookup(context).find(20_000) { ServiceRecord.read(it.name, it.txt)?.id == id.short }
                assertNotNull("the TV's record by its id", found)
                found!!
                val txt = found.txt.mapValues { (_, value) -> value?.decodeToString() }
                assertEquals(mapOf("pv" to "1", "id" to id.short, "v4" to on.address.hostAddress), txt)
                assertEquals(on.port, found.port)
                val name = PeerNames.own(Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME), Build.MANUFACTURER, Build.MODEL)
                assertTrue("the TV's own name: \"${found.name}\"", found.name.startsWith(ServiceRecord.instanceName(name)))
                val words = (txt.values.filterNotNull() + found.name).joinToString(" ").lowercase()
                assertTrue("nothing of the film", "harbor" !in words && "bell" !in words && "dip" !in words)
            }
        }
    }

    private companion object {
        /** An invented film's TMDB id; nothing real has it. */
        const val FILM = 900031
        const val INDEXER = "tv-link-host-test"
        const val TITLE = "Harbor.Bell.2026.1080p.WEB-DL.H.264-LINK"
    }
}
