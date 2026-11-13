package io.skjaere.usenet.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.AspectMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs
import kotlin.math.max

/**
 * Fill beside camera end to end, through the real UsenetDataSource: the chapters.mkv fixture (a
 * 16:9 test pattern, see SkipSegmentsDeviceTest) on the emulator's landscape phone, whose punch-hole
 * camera sits in a display cutout on the left (on the right turned the other way round, at the top
 * upright). The mode fills the screen but for a strip along the camera's edge, which stays black,
 * where Fill screen covers the camera; only the video surface moves, as its bounds on screen (and
 * its clip) show. Without a cutout (the window kept out of it, as Samsung's per-app camera cutout
 * setting does, so it gets none) the button skips the mode and a kept one shows as Fill screen.
 * No cutout emulation overlay is used: the emulator's own cutout and the window's cutout mode give
 * every case before Android 15. From 15 on an app that targets it gets the cutout whatever its
 * window's mode, so the case without one is skipped there; on a screen without a cutout that case
 * is all that runs. Each test starts from Fit, and puts the preferences back as they were.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class FillBesideCameraDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its window's focus. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val application = context.applicationContext as Application
    private val realLookUp = PlayerActivity.lookUpSegments
    private lateinit var fixture: PreparedNzb
    private lateinit var saved: AppSettings

    /**
     * Keeps each player's window out of the display cutout as it starts, before it is shown, as
     * Samsung's per-app setting does: then the window has no cutout. Not from Android 15 on, which
     * lays the window of an app that targets it into the cutout whatever its mode.
     */
    private val outOfCutout = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            if (activity is PlayerActivity) cutoutMode(activity, WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    @Before
    fun setUp() {
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 1119), 2_000) } }.isSuccess)
        runBlocking {
            fixture = engineSetup("the mock on the engine, the chapters fixture prepared") {
                mockOnEngine()
                prepareFixture("chapters")
            }
            saved = container.settings.settings.first()
            // Each test starts from Fit, whatever was chosen on the device before; restore() puts that back.
            container.settings.updatePlayback { it.copy(aspectMode = AspectMode.Fit.name, zoomScale = 1f) }
        }
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
    }

    @After
    fun restore() {
        application.unregisterActivityLifecycleCallbacks(outOfCutout)
        PlayerActivity.lookUpSegments = realLookUp
        if (!::saved.isInitialized) return
        runBlocking {
            // The player saves in the app's scope: let a save still on its way land before putting the preferences back.
            delay(500)
            container.settings.restore(container.settings.settings.first().copy(playback = saved.playback))
            assertEquals(saved.playback, container.settings.playback.first())
        }
    }

    private fun uri() = UsenetUris.build(fixture.id, fixture.defaultPick!!.index)

    /** The fixture opened as an NZB file is: no launch, so no resume and no next episode. */
    private fun fileIntent() = Intent(context, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_URI, uri())

    /**
     * With the camera on the left: Fit, then Fill screen over the camera, then Fill beside camera,
     * clear of it, the strip beside it black and everything but the picture where it was; then
     * Fit. A pinch ending at Fill beside camera's scale sets it; a pinch from it opens
     * no bars, the picture growing into the strip. It keeps the picture clear of the camera as the
     * video's shape changes, also for a picture wider than the room beside the camera, which is cut
     * where it meets the strip.
     */
    @Test
    fun fillBesideCameraKeepsThePictureClearOfTheCameraWhereFillScreenCoversIt() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            awaitPlaying(scenario)
            assumeTrue("this device has no display cutout in landscape", scenario.read { a -> cutout(a).isNotEmpty() })
            assertEquals("the emulator's camera isn't on the left", "left", scenario.read(::cameraSide))
            scenario.onActivity { a -> playerView(a).showController() }
            // Up and done sliding in, so only a change of size can move what they hold.
            waitFor("the controls in place") {
                scenario.read { a -> (playerView(a).isControllerFullyVisible && aspectButton(a).isShown && a.findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar).translationY == 0f).takeIf { it } }
            }
            delay(500)
            assertEquals("Aspect: Fit. Switch to Fill screen", scenario.read { a -> aspectButton(a).contentDescription })
            val still = scenario.read(::stillParts)

            scenario.onActivity { a -> aspectButton(a).performClick() }
            scenario.read { a ->
                assertEquals("Aspect: Fill screen. Switch to Fill beside camera", aspectButton(a).contentDescription)
                val picture = shownPicture(a)
                cutout(a).forEach { r -> assertTrue("Fill screen at ${picture.toShortString()} doesn't cover the camera at $r", picture.contains(RectF(r))) }
            }

            scenario.onActivity { a -> aspectButton(a).performClick() }
            scenario.read { a ->
                assertEquals("Aspect: Fill beside camera. Switch to Fit", aspectButton(a).contentDescription)
                assertTrue(shownTexts(a).toString(), "Fill beside camera" in shownTexts(a))
                assertBesideCamera(a)
                assertEquals("only the picture moves", still, stillParts(a))
            }
            awaitSaved(AspectMode.FillBesideCamera)
            hideControls(scenario)
            assertStripBlack(scenario)

            scenario.onActivity { a -> playerView(a).showController(); aspectButton(a).performClick() }
            assertEquals("Aspect: Fit. Switch to Fill screen", scenario.read { a -> aspectButton(a).contentDescription })
            awaitSaved(AspectMode.Fit)

            // A pinch from Fit ending at Fill beside camera's scale (Fill screen's is 6% more) sets it.
            hideControls(scenario)
            val beside = scenario.read { a -> besideCamera(a).let { box -> contentFrame(a).let { f -> max(box.width() / f.width, box.height() / f.height) } } }
            pinch(scenario, startSpan = 600f, step = 8f) { it >= beside }
            scenario.read { a ->
                assertEquals("Aspect: Fill beside camera. Switch to Fit", aspectButton(a).contentDescription)
                assertBesideCamera(a)
            }
            awaitSaved(AspectMode.FillBesideCamera)

            // A pinch from it opens no bars: growing, the picture moves into the strip, what it leaves of
            // the strip black. Ending about where it started, it is as it was.
            val start = scenario.read { a -> playerView(a).videoSurfaceView!!.scaleX }
            pinch(scenario, startSpan = 600f, step = 4f, held = { scenario.read(::assertNoBarsMidPinch) }) { it >= start * 1.01f }
            scenario.read { a ->
                assertEquals("Aspect: Fill beside camera. Switch to Fit", aspectButton(a).contentDescription)
                assertBesideCamera(a)
            }

            // The video turning 2.39:1 mid-stream, wider than the room beside the camera: it fills that room's height, cut at the strip.
            scenario.onActivity { a -> (contentFrame(a) as AspectRatioFrameLayout).setAspectRatio(2.39f) }
            waitFor("Fit to lay out the new shape") { scenario.read { a -> contentFrame(a).let { abs(it.width.toFloat() / it.height - 2.39f) < 0.01f }.takeIf { it } } }
            settled(scenario, ::assertBesideCamera)
            scenario.read { a ->
                val surface = shownRect(playerView(a).videoSurfaceView!!, clipped = false)
                assertTrue("the picture at ${surface.toShortString()} isn't wider than the room beside the camera", surface.left < besideCamera(a).left - 4)
            }
            delay(500)
            assertStripBlack(scenario)
        }
    }

    /**
     * Fill beside camera follows the camera as the screen turns the other way round (the camera
     * on the right) and upright (at the top), and back; it outlives the player being made again
     * (onStop/onStart, recreation, a play started while it plays) and is the next play's.
     */
    @Test
    fun itFollowsTheCameraAsTheScreenTurnsAndIsKeptForTheNextPlay() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            awaitPlaying(scenario)
            assumeTrue("this device has no display cutout in landscape", scenario.read { a -> cutout(a).isNotEmpty() })
            scenario.onActivity { a -> playerView(a).showController(); repeat(2) { aspectButton(a).performClick() } }
            scenario.read { a ->
                assertEquals("Aspect: Fill beside camera. Switch to Fit", aspectButton(a).contentDescription)
                assertBesideCamera(a)
            }
            awaitSaved(AspectMode.FillBesideCamera)

            listOf(
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE to "right",
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to "top",
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to "left",
            ).forEach { (orientation, side) ->
                scenario.onActivity { a -> a.requestedOrientation = orientation }
                waitFor("the camera on the $side") { scenario.read { a -> (cameraSide(a) == side && playerView(a).width == a.window.decorView.width).takeIf { it } } }
                settled(scenario, ::assertBesideCamera)
            }

            val first = scenario.read { it.player }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitFor("the player made again") { scenario.read { a -> a.player?.takeIf { it !== first } } }
            // It waits paused after the stop (W106): played, for the picture as it plays.
            scenario.onActivity { it.player!!.play() }
            awaitPlaying(scenario)
            settled(scenario, ::assertBesideCamera)

            scenario.recreate()
            awaitPlaying(scenario)
            settled(scenario, ::assertBesideCamera)

            // A play started while this one plays (#154) reaches the open player.
            val before = scenario.read { it.player }
            context.startActivity(fileIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("the new play's player") { scenario.read { a -> a.player?.takeIf { it !== before } } }
            awaitPlaying(scenario)
            settled(scenario, ::assertBesideCamera)
        }
        // The next play starts with it.
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            awaitPlaying(scenario)
            settled(scenario, ::assertBesideCamera)
            assertEquals("Aspect: Fill beside camera. Switch to Fit", scenario.read { a -> aspectButton(a).contentDescription })
        }
    }

    /**
     * Without a cutout Fill beside camera would be Fill screen: a kept one shows as Fill screen
     * and stays the choice, coming back as the window gets a cutout again, and the button goes
     * Fit, Fill screen, Fit.
     */
    @Test
    fun withoutACutoutTheButtonSkipsTheModeAndAKeptOneShowsAsFillScreen() = runBlocking<Unit> {
        container.settings.updatePlayback { it.copy(aspectMode = AspectMode.FillBesideCamera.name) }
        application.registerActivityLifecycleCallbacks(outOfCutout)
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            awaitPlaying(scenario)
            assumeTrue("the window kept out of the cutout has one (${scenario.read(::cutout)}): Android 15 and later ignore its mode", scenario.read(::cutout).isEmpty())
            scenario.onActivity { a -> playerView(a).showController() }
            waitFor("the aspect button") { scenario.read { a -> aspectButton(a).takeIf { it.isShown } } }
            scenario.read { a ->
                assertEquals("Aspect: Fill screen. Switch to Fit", aspectButton(a).contentDescription)
                assertFillScreen(a)
            }
            delay(1_000)
            assertEquals("the kept choice was overwritten", AspectMode.FillBesideCamera, container.settings.playback.first().aspect)

            // Let into the cutout again (the setting turned off): the kept choice is back, on a screen that has one.
            assumeTrue("this device has no display cutout", scenario.read(::screenHasCutout))
            scenario.onActivity { a -> cutoutMode(a, WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS) }
            waitFor("the window's cutout") { scenario.read { a -> cutout(a).takeIf { it.isNotEmpty() } } }
            settled(scenario) { a ->
                assertEquals("Aspect: Fill beside camera. Switch to Fit", aspectButton(a).contentDescription)
                assertBesideCamera(a)
            }

            // Out of it once more: Fill screen, and the button goes on to Fit and Fill screen, never to the camera's mode.
            scenario.onActivity { a -> cutoutMode(a, WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER) }
            waitFor("the window without its cutout") { scenario.read { a -> cutout(a).isEmpty().takeIf { it } } }
            settled(scenario) { a ->
                assertEquals("Aspect: Fill screen. Switch to Fit", aspectButton(a).contentDescription)
                assertFillScreen(a)
            }
            listOf("Aspect: Fit. Switch to Fill screen", "Aspect: Fill screen. Switch to Fit", "Aspect: Fit. Switch to Fill screen", "Aspect: Fill screen. Switch to Fit").forEach { says ->
                scenario.onActivity { a -> playerView(a).showController(); aspectButton(a).performClick() }
                assertEquals(says, scenario.read { a -> aspectButton(a).contentDescription })
            }
            awaitSaved(AspectMode.Fill)
        }
    }

    // The camera and the picture.

    private fun cutoutMode(activity: Activity, mode: Int) {
        activity.window.attributes = activity.window.attributes.apply { layoutInDisplayCutoutMode = mode }
    }

    /** Whether the screen has a cutout, whatever the window's mode: as far as Android 10 and later can say. */
    private fun screenHasCutout(a: Activity): Boolean =
        Build.VERSION.SDK_INT >= 29 && a.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.cutout != null

    /** The display cutout's bounding rects, in the window (where the player is). */
    private fun cutout(a: Activity): List<Rect> = ViewCompat.getRootWindowInsets(a.window.decorView)?.displayCutout?.boundingRects.orEmpty()

    /** The edge each cutout is nearest, in the player's view. */
    private fun sides(a: Activity): List<String> {
        val (w, h) = playerView(a).let { it.width to it.height }
        return cutout(a).map { r -> listOf("left" to r.left, "top" to r.top, "right" to w - r.right, "bottom" to h - r.bottom).minBy { it.second }.first }
    }

    private fun cameraSide(a: Activity): String? = sides(a).singleOrNull()

    /** The player's view but for a strip along each edge a camera sits on, as deep as it reaches in. */
    private fun besideCamera(a: Activity): RectF {
        val (w, h) = playerView(a).let { it.width.toFloat() to it.height.toFloat() }
        return cutout(a).zip(sides(a)).fold(RectF(0f, 0f, w, h)) { box, (r, side) ->
            box.apply {
                when (side) {
                    "left" -> left = maxOf(left, r.right.toFloat())
                    "top" -> top = maxOf(top, r.bottom.toFloat())
                    "right" -> right = minOf(right, r.left.toFloat())
                    else -> bottom = minOf(bottom, r.top.toFloat())
                }
            }
        }
    }

    /** Where the picture shows: the video surface as scaled, moved and clipped, on the player's view. */
    private fun shownPicture(a: Activity): RectF =
        shownRect(playerView(a).videoSurfaceView!!, clipped = true).apply { check(intersect(shownRect(playerView(a), clipped = false))) { "the picture is off the screen" } }

    /** The picture fills the room beside the camera, centred in it, and covers no cutout. */
    private fun assertBesideCamera(a: Activity) {
        val rects = cutout(a)
        assertTrue("no cutout", rects.isNotEmpty())
        val box = besideCamera(a)
        val shown = shownPicture(a)
        val off = listOf(shown.left - box.left, shown.top - box.top, shown.right - box.right, shown.bottom - box.bottom).maxOf { abs(it) }
        assertTrue("the picture shows at ${shown.toShortString()}, not ${box.toShortString()} beside the camera at $rects", off <= 2f)
        val inner = RectF(shown.left + 1, shown.top + 1, shown.right - 1, shown.bottom - 1)
        rects.forEach { r -> assertFalse("the picture at ${shown.toShortString()} covers the camera at $r", RectF.intersects(inner, RectF(r))) }
        // Centred in that room: as much cut off either side of it.
        val surface = shownRect(playerView(a).videoSurfaceView!!, clipped = false)
        assertTrue(
            "the picture at ${surface.toShortString()} isn't centred in ${box.toShortString()}",
            abs(surface.centerX() - box.centerX()) <= 2f && abs(surface.centerY() - box.centerY()) <= 2f,
        )
    }

    /**
     * Mid-pinch from Fill beside camera, a little past its scale: the picture covers the room
     * beside the camera, no bars, and is on its way into the strip, what it leaves of it black.
     */
    private fun assertNoBarsMidPinch(a: Activity) {
        val shown = shownPicture(a)
        val view = shownRect(playerView(a), clipped = false)
        val box = besideCamera(a)
        assertTrue(
            "the picture at ${shown.toShortString()} leaves bars in ${box.toShortString()} beside the camera",
            shown.left <= box.left + 1 && shown.top <= view.top + 1 && shown.right >= view.right - 1 && shown.bottom >= view.bottom - 1,
        )
        assertTrue("the picture at ${shown.toShortString()} stays out of the strip", shown.left < box.left - 4)
        assertEquals("what the picture leaves isn't black", Color.BLACK, (playerView(a).background as? ColorDrawable)?.color)
    }

    /** As Fill screen: covering the player's view, centred on it, nothing cut. */
    private fun assertFillScreen(a: Activity) {
        val view = shownRect(playerView(a), clipped = false)
        val surface = shownRect(playerView(a).videoSurfaceView!!, clipped = false)
        assertTrue("Fill screen leaves bars: ${surface.toShortString()} in ${view.toShortString()}", surface.contains(RectF(view.left + 1, view.top + 1, view.right - 1, view.bottom - 1)))
        assertTrue("Fill screen at ${surface.toShortString()} isn't centred on ${view.toShortString()}", abs(surface.centerX() - view.centerX()) <= 2f && abs(surface.centerY() - view.centerY()) <= 2f)
        assertEquals(shownPicture(a), view)
    }

    /** Everything but the picture, which stays where it is: the subtitles, the controls, the top bar's buttons. */
    private fun stillParts(a: Activity): List<String> =
        listOf(playerView(a).subtitleView!!, a.findViewById<View>(androidx.media3.ui.R.id.exo_controller), aspectButton(a), a.findViewById<View>(androidx.media3.ui.R.id.exo_progress))
            .map { shownRect(it, clipped = false).toShortString() }

    /** In a screenshot, the strip between the camera's edge and the picture is black: not the window's grey, nor the picture. */
    private fun assertStripBlack(scenario: ActivityScenario<PlayerActivity>) {
        val (columns, rows) = scenario.read { a ->
            val at = IntArray(2).also(playerView(a)::getLocationOnScreen)
            val box = besideCamera(a)
            assertEquals("the camera isn't on the left", "left", cameraSide(a))
            (at[0] + 4 until at[0] + box.left.toInt() - 4) to (at[1] + playerView(a).height / 10 until at[1] + playerView(a).height * 9 / 10)
        }
        val shot = instrumentation.uiAutomation.takeScreenshot()!!
        val pixels = (columns step 8).flatMap { x -> (rows step 16).map { shot.getPixel(x, it) } }
        assertTrue("the strip beside the camera isn't black: ${pixels.distinct().take(5).map(::hex)}", pixels.isNotEmpty() && pixels.all { p -> listOf(Color::red, Color::green, Color::blue).all { it(p) <= 12 } })
    }

    private fun hex(pixel: Int) = "%06x".format(pixel and 0xffffff)

    /** [view]'s bounds, or its clip when [clipped] and it has one, in the window through every scale on the way up: where it shows. */
    private fun shownRect(view: View, clipped: Boolean): RectF {
        val r = view.clipBounds?.takeIf { clipped }?.let(::RectF) ?: RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
        var v: View? = view
        while (v != null) {
            v.matrix.mapRect(r)
            val parent = v.parent as? View
            r.offset((v.left - (parent?.scrollX ?: 0)).toFloat(), (v.top - (parent?.scrollY ?: 0)).toFloat())
            v = parent
        }
        return r
    }

    // Waiting.

    /** Waits until the file plays, its first frame shown. */
    private suspend fun awaitPlaying(scenario: ActivityScenario<PlayerActivity>) {
        waitFor("the picture", timeoutMs = 30_000) {
            scenario.read { a ->
                val p = a.player ?: return@read null
                val shutter = playerView(a).findViewById<View>(androidx.media3.ui.R.id.exo_shutter)
                (p.videoSize.width > 0 && p.isPlaying && shutter.visibility != View.VISIBLE && contentFrame(a).width > 0).takeIf { it }
            }
        }
    }

    /** Waits until [check] holds of the player, as the screen and the window settle after a change. */
    private suspend fun settled(scenario: ActivityScenario<PlayerActivity>, check: (Activity) -> Unit) {
        var last: Throwable? = null
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            last = runCatching { scenario.read(check) }.exceptionOrNull() ?: return
            delay(100)
        }
        throw AssertionError("not settled: ${last?.message}", last)
    }

    /** Waits for [mode] to be saved in the playback preferences. */
    private suspend fun awaitSaved(mode: AspectMode) {
        waitFor("$mode to be saved") { runBlocking { container.settings.playback.first().takeIf { it.aspect == mode } } }
    }

    private suspend fun hideControls(scenario: ActivityScenario<PlayerActivity>) {
        scenario.onActivity { playerView(it).hideController() }
        waitFor("the controls to hide") { scenario.read { a -> (!playerView(a).isControllerFullyVisible && !aspectButton(a).isShown).takeIf { it } } }
        // Their hide animation.
        delay(800)
    }

    // Touch, as a finger gives it.

    /**
     * Two fingers either side of the picture's centre, [startSpan] apart, moved [step] apart at a
     * time until the picture's scale meets [until]; then, once [held] has looked, lifted.
     */
    private suspend fun pinch(
        scenario: ActivityScenario<PlayerActivity>,
        startSpan: Float,
        step: Float,
        held: suspend () -> Unit = {},
        until: (Float) -> Boolean,
    ) {
        val (cx, cy) = scenario.read { a ->
            val at = IntArray(2).also(playerView(a)::getLocationOnScreen)
            PointF(at[0] + playerView(a).width / 2f, at[1] + playerView(a).height / 2f)
        }.let { it.x to it.y }
        val down = SystemClock.uptimeMillis()
        var span = startSpan
        fun fingers() = listOf(PointF(cx - span / 2, cy), PointF(cx + span / 2, cy))
        val second = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        send(down, MotionEvent.ACTION_DOWN, fingers().take(1))
        send(down, MotionEvent.ACTION_POINTER_DOWN or second, fingers())
        var scale = 1f
        repeat(150) {
            span += step
            send(down, MotionEvent.ACTION_MOVE, fingers())
            delay(16)
            scale = scenario.read { a -> playerView(a).videoSurfaceView!!.scaleX }
            if (until(scale)) {
                try {
                    held()
                } finally {
                    send(down, MotionEvent.ACTION_POINTER_UP or second, fingers())
                    send(down, MotionEvent.ACTION_UP, fingers().take(1))
                }
                return
            }
        }
        send(down, MotionEvent.ACTION_POINTER_UP or second, fingers())
        send(down, MotionEvent.ACTION_UP, fingers().take(1))
        throw AssertionError("the pinch never got the picture where it was going; its scale was $scale")
    }

    private fun send(downTime: Long, action: Int, fingers: List<PointF>) {
        val properties = fingers.indices.map { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = fingers.map { f -> MotionEvent.PointerCoords().apply { x = f.x; y = f.y; pressure = 1f; size = 1f } }
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, fingers.size, properties.toTypedArray(), coords.toTypedArray(),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        instrumentation.sendPointerSync(event)
        event.recycle()
    }

    // Views.

    private fun aspectButton(activity: Activity) = views(activity.window.decorView).filterIsInstance<ImageButton>().single { it.contentDescription?.startsWith("Aspect:") == true }

    /** What the shown text views say: the top bar's titles, and the label of a new mode while it shows. */
    private fun shownTexts(activity: Activity) = views(activity.window.decorView).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }

    private fun contentFrame(activity: Activity) = playerView(activity).findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)

    private fun playerView(activity: Activity) = views(activity.window.decorView).filterIsInstance<PlayerView>().single()

    private fun views(view: View): List<View> =
        listOf(view) + ((view as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())

    private fun <T> ActivityScenario<PlayerActivity>.read(probe: (PlayerActivity) -> T): T {
        var result: Result<T>? = null
        onActivity { result = runCatching { probe(it) } }
        return result!!.getOrThrow()
    }

    private suspend fun <T : Any> waitFor(what: String, timeoutMs: Long = 20_000, probe: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
            delay(100)
        }
    }
}
