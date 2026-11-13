package io.skjaere.usenet.app.cast

import android.app.Activity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.PictureInPictureAllowed
import io.skjaere.usenet.app.PictureInPicturePlays
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.R
import io.skjaere.usenet.app.casting.CastTimings
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.link.CastButtonLook
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.overlayNodes
import io.skjaere.usenet.app.read
import io.skjaere.usenet.app.views
import io.skjaere.usenet.data.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Play & connect and the picture-in-picture window (L9, L124): the film moving to a Cast device goes into the window by
 * the top bar's button while the device connects. The move's own start on the device is no other play started from the
 * app, which the window would pause for and drop as the user goes back to it: Play pressed in the window leaves the move
 * going, and once the device plays it the player closes. Over fakes alone (L143), as PlayerCastDeviceTest: the Cast
 * group's devices and sessions, and the device's side of a cast play, whose load the test says the device plays; the
 * film is PictureInPicturePlays' (a server inside the test's process). The device and the film are invented.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerCastWindowDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    @get:Rule
    val pictureInPicture = PictureInPictureAllowed()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val plays = PictureInPicturePlays(INDEXER)
    private val routes = FakeRoutes()
    private val connector = FakeConnector()
    private val launcher = FakeLauncher()
    private val kitchen = castRoute("Kitchen display", model = "Chromecast Ultra")
    private var saved: AppSettings? = null

    @Before
    fun setUp() {
        plays.assumeWindow()
        runBlocking {
            saved = container.settings.settings.first()
            plays.setUp()
        }
        CastFakes.started(routes, connector, CopyOnWriteArrayList())
        CastSeams.launcher = { launcher }
        // The device connects when the test says, not given up after 15 s meanwhile.
        CastTimings.current = CastTimings(connectMillis = 60_000)
        routes.found(kitchen)
    }

    @After
    fun restore() {
        mainActivities().forEach { activity -> onMainThread { activity.finish() } }
        compose.waitUntil(20_000) { mainActivities().isEmpty() }
        plays.close()
        container.playOn.choose(PlayChoice.Phone)
        // Last: the settings as they were before CastFakes chose to look for Cast devices.
        CastFakes.reset()
        runBlocking {
            saved?.let { container.settings.restore(it) }
            plays.forget()
        }
    }

    /**
     * L9, L124: the film moving, put in the window by its button while the device connects; once connected, the move's
     * start on the device comes as the window floats, and Play pressed in it (its own button, or a headset's) drops no
     * play: nothing started there stops, and once the device plays the film the player closes.
     */
    @Test
    fun aMoveGoesOnInTheWindowAndPlayPressedThereLeavesIt() {
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, runBlocking { plays.launch("copper-tide-window-move") })).use { scenario ->
            await("the film to play", 30_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying } } }
            scenario.onActivity { a ->
                playerView(a).controllerShowTimeoutMs = 0
                playerView(a).showController()
            }
            await("the Cast button") { scenario.read { a -> castNode(a) } }
            scenario.onActivity { a -> castNode(a)!!.config[SemanticsActions.OnClick].action!!.invoke() }
            await("Kitchen display's row") { scenario.read { a -> castRow(a) } }
            scenario.onActivity { a -> castRow(a)!!.config[SemanticsActions.OnClick].action!!.invoke() }
            await("the film paused, moving") { scenario.read { a -> moment(a)?.takeIf { it.startsWith("Moving to") && a.player?.playWhenReady == false } } }
            // Into the window by the top bar's button, while the device connects.
            scenario.onActivity { a -> views(a.window.decorView).single { it.contentDescription == "Picture in picture" }.performClick() }
            await("in the window") { scenario.read { a -> a.isInPictureInPictureMode.takeIf { it } } }
            connector.started(kitchen)
            await("the launch loaded on the device") { launcher.loads.firstOrNull() }
            frames(1_000)
            // Play pressed in the window.
            scenario.onActivity { it.player!!.play() }
            frames(2_000)
            assertEquals("the move goes on: nothing started there stopped", 0, launcher.stops.get())
            launcher.plays()
            await("the player closed") { players().all { it.isFinishing || it.isDestroyed }.takeIf { it } }
        }
        assertEquals("still Kitchen display's", PlayChoice.Cast(kitchen.id, kitchen.name), container.playOn.chosen.value)
    }

    private fun playerView(activity: Activity) = views(activity.window.decorView).filterIsInstance<PlayerView>().single()

    /** The Cast button's node while it shows. */
    private fun castNode(activity: Activity): SemanticsNode? = overlayNodes(activity).firstOrNull { it.config.getOrNull(CastButtonLook) != null }

    /** Kitchen display's row in the player's list, by TalkBack's words for it. */
    private fun castRow(activity: Activity): SemanticsNode? =
        overlayNodes(activity).firstOrNull { n -> n.config.getOrNull(SemanticsActions.OnClick) != null && n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith("${kitchen.name}, Cast device") } }

    /** The player's moment label's words while it shows. */
    private fun moment(activity: Activity): String? = activity.findViewById<android.widget.TextView>(R.id.moment_label)?.takeIf { it.isShown }?.text?.toString()

    /** Waits for [probe] to give something, on the compose rule's clock, which drives every composition here. */
    private fun <T : Any> await(what: String, millis: Long = 20_000, probe: () -> T?): T {
        var found: T? = null
        compose.waitUntil("$what, in ${millis / 1_000} s", millis) { probe()?.also { found = it } != null }
        return found!!
    }

    /** [millis] of the player's frames, which the rule's clock drives: never a sleep. */
    private fun frames(millis: Long) {
        val until = android.os.SystemClock.uptimeMillis() + millis
        compose.waitUntil(millis + 5_000) { android.os.SystemClock.uptimeMillis() > until }
    }

    /** The players alive. */
    private fun players(): List<PlayerActivity> = onMainThread {
        ALIVE.flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.filterIsInstance<PlayerActivity>().distinct()
    }

    /** The app's MainActivity instances alive. */
    private fun mainActivities(): List<MainActivity> = onMainThread {
        ALIVE.flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.filterIsInstance<MainActivity>().distinct()
    }

    private companion object {
        const val INDEXER = "player-cast-window-device-test"

        /** An activity's stages from its making to its end. */
        val ALIVE = arrayOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.RESTARTED)
    }
}
