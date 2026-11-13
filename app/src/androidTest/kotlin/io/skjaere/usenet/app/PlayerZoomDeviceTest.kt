package io.skjaere.usenet.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.AspectMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max

/**
 * The picture's size end to end, through the real UsenetDataSource: the chapters.mkv fixture
 * (a 16:9 test pattern with an intro at 5–25 s, see SkipSegmentsDeviceTest) on a screen of another
 * shape, the emulator's landscape phone, so Fit leaves bars at the sides. The aspect button in the
 * top bar goes Fit → Fill screen → Fit, with Fill beside camera after Fill screen where the
 * window has a display cutout, as the emulator's has (see FillBesideCameraDeviceTest), a pinch
 * zooms; none changes the picture's shape, and only the video surface is scaled, as the surface's bounds on screen (and a screenshot of
 * the screen's edge) show. The choice is saved in the playback preferences: each test starts from
 * Fit, and puts them back as they were.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerZoomDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realLookUp = PlayerActivity.lookUpSegments
    private val realDataSources = PlayerActivity.dataSources
    private val realRead = PlayerActivity.readPlayback
    /** A new player's file, held by a test: let go after it, so the player can be released. */
    private var loading: CountDownLatch? = null
    private lateinit var fixture: PreparedNzb
    private lateinit var saved: AppSettings

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
        PlayerActivity.lookUpSegments = realLookUp
        PlayerActivity.dataSources = realDataSources
        PlayerActivity.readPlayback = realRead
        loading?.countDown()
        instrumentation.setInTouchMode(true)
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

    @Test
    fun theAspectButtonGoesFitFillFitAndSaysEachModeForAMoment() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            awaitPicture(scenario)
            scenario.onActivity { playerView(it).showController() }
            waitFor("the aspect button") { scenario.read { a -> aspectButton(a).takeIf { it.isShown } } }
            assertEquals("Aspect: Fit. Switch to Fill screen", scenario.read { a -> aspectButton(a).contentDescription })
            assertPicture(scenario, AspectMode.Fit)
            // Styled as the audio and subtitles button beside it.
            scenario.read { a ->
                val (aspect, audio) = aspectButton(a) to audioButton(a)
                assertSame(audio.parent, aspect.parent)
                assertEquals(audio.width to audio.height, aspect.width to aspect.height)
                assertTrue("the aspect button ends at ${aspect.right}, the audio button starts at ${audio.left}", abs(audio.left - aspect.right) <= aspect.width / 4)
            }

            val camera = scenario.read(::camera)
            scenario.onActivity { a -> aspectButton(a).performClick() }
            val pressedAt = SystemClock.elapsedRealtime()
            scenario.read { a ->
                assertEquals("Aspect: Fill screen. Switch to ${if (camera) "Fill beside camera" else "Fit"}", aspectButton(a).contentDescription)
                assertTrue(shownTexts(a).toString(), "Fill screen" in shownTexts(a))
            }
            assertPicture(scenario, AspectMode.Fill)
            // The label goes by itself after about a second and a half.
            waitFor("the label to go", timeoutMs = 4_000) { scenario.read { a -> ("Fill screen" !in shownTexts(a)).takeIf { it } } }
            val shownFor = SystemClock.elapsedRealtime() - pressedAt
            assertTrue("the label showed for $shownFor ms", shownFor in 1_000L..2_600L)

            if (camera) {
                scenario.onActivity { a -> aspectButton(a).performClick() }
                scenario.read { a ->
                    assertEquals("Aspect: Fill beside camera. Switch to Fit", aspectButton(a).contentDescription)
                    assertTrue(shownTexts(a).toString(), "Fill beside camera" in shownTexts(a))
                }
                assertPicture(scenario, AspectMode.FillBesideCamera)
            }

            scenario.onActivity { a -> aspectButton(a).performClick() }
            scenario.read { a ->
                assertEquals("Aspect: Fit. Switch to Fill screen", aspectButton(a).contentDescription)
                assertTrue(shownTexts(a).toString(), "Fit" in shownTexts(a))
                // The presses kept the controls up.
                assertTrue(playerView(a).isControllerFullyVisible)
            }
            assertPicture(scenario, AspectMode.Fit)
            awaitSaved(AspectMode.Fit)
        }
    }

    /** The choice is the next play's, and outlives the player being made again: onStop/onStart, recreation and a play started while one plays. */
    @Test
    fun theChoiceIsRememberedForTheNextPlayAndWhenThePlayerIsMadeAgain() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            awaitPicture(scenario)
            scenario.onActivity { a -> playerView(a).showController(); aspectButton(a).performClick() }
            assertPicture(scenario, AspectMode.Fill)
            awaitSaved(AspectMode.Fill)

            val first = scenario.read { it.player }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            // It waits paused after the stop (W106): played, for the picture as it plays.
            waitFor("the player made again") { scenario.read { a -> a.player?.takeIf { it !== first } } }
            scenario.onActivity { it.player!!.play() }
            awaitPicture(scenario)
            assertNotSame("the player wasn't made again", first, scenario.read { it.player })
            assertMode(scenario, AspectMode.Fill)

            scenario.recreate()
            awaitPicture(scenario)
            assertMode(scenario, AspectMode.Fill)

            // A play started while this one plays (#154) reaches the open player.
            val (activity, before) = scenario.read { it to it.player }
            context.startActivity(fileIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("the new play's player") { scenario.read { a -> a.player?.takeIf { it !== before } } }
            awaitPicture(scenario)
            scenario.read { a -> assertSame("a second player activity opened", activity, a) }
            assertMode(scenario, AspectMode.Fill)
        }
        // A new play starts with it.
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            awaitPicture(scenario)
            assertMode(scenario, AspectMode.Fill)
        }
    }

    /**
     * Recreated (a change of night mode, say) before its first setup has read the choice: the
     * recreated player reads it then, rather than keeping the Fit it had before reading.
     */
    @Test
    fun aChoiceNotYetReadWhenThePlayerIsRecreatedIsReadAfter() = runBlocking<Unit> {
        container.settings.updatePlayback { it.copy(aspectMode = AspectMode.Fill.name) }
        // The first setup waits on its read of the preferences, and is cancelled by the recreation.
        val reads = AtomicInteger()
        val never = CompletableDeferred<Unit>()
        PlayerActivity.readPlayback = { c ->
            if (reads.getAndIncrement() == 0) never.await()
            c.settings.playback.first()
        }
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            assertEquals("the first setup isn't reading", 1, reads.get())
            scenario.recreate()
            awaitPicture(scenario)
            assertMode(scenario, AspectMode.Fill)
        }
    }

    /**
     * A play started while one plays in Fill screen (#154): media3's black shutter closes over
     * Fit's frame alone until the new play shows its first frame, while the surface still holds the
     * last frame of the play before. The surface stays inside the shutter until then, so nothing of
     * that frame shows around it; then the choice applies again.
     */
    @Test
    fun aPlayStartedWhileOneIsZoomedShowsNothingOfThePictureBefore() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            awaitPicture(scenario)
            scenario.onActivity { a -> playerView(a).showController(); aspectButton(a).performClick() }
            assertPicture(scenario, AspectMode.Fill)
            awaitSaved(AspectMode.Fill)

            // The new play's file held: it shows no frame until let go.
            val gate = CountDownLatch(1).also { loading = it }
            PlayerActivity.dataSources = { c -> DataSource.Factory { Held(c.dataSourceFactory.createDataSource(), gate) } }
            val before = scenario.read { it.player }
            context.startActivity(fileIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("the new play's player") { scenario.read { a -> a.player?.takeIf { it !== before } } }
            delay(300)
            scenario.read { a ->
                val shutter = playerView(a).findViewById<View>(androidx.media3.ui.R.id.exo_shutter)
                assertTrue("the shutter is open before the new play's first frame", shutter.isShown)
                val (covered, surface) = shownRect(shutter) to shownRect(playerView(a).videoSurfaceView!!)
                val edge = RectF(covered.left - 2, covered.top - 2, covered.right + 2, covered.bottom + 2)
                assertTrue("the surface shows at ${surface.toShortString()}, past the shutter at ${covered.toShortString()}", edge.contains(surface))
                assertTrue(aspectButton(a).contentDescription.startsWith("Aspect: Fill screen."))
            }
            gate.countDown()
            awaitPicture(scenario)
            assertMode(scenario, AspectMode.Fill)
        }
    }

    /**
     * Pinching zooms the picture without showing or hiding the controls; ending near Fill screen
     * snaps to it, ending well past it is a zoom of its own, below Fit is Fit, and about where it
     * started changes nothing. A single tap still shows and hides the controls.
     */
    @Test
    fun aPinchZoomsThePictureWithoutTogglingTheControls() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            awaitPicture(scenario)
            hideControls(scenario)
            // Fit leaves a bar of one colour at the side; the picture beside it is of others.
            val bar = shot(scenario, bar(scenario))
            assertTrue("Fit leaves no bar at the side: ${bar.distinct().take(5).map(::hex)}", bar.all { same(it, bar.first()) })
            val inside = shot(scenario, scenario.read { a -> shownRect(contentFrame(a)).let { onScreen(a, it.left + it.width() * 0.1f..it.left + it.width() * 0.3f) } })
            assertTrue("the screenshot doesn't show the picture: ${inside.distinct().take(5).map(::hex)}", inside.count { !same(it, bar.first()) } > inside.size / 2)

            val fill = scenario.read { a -> fillScale(a) }
            val zoomed = pinch(scenario, startSpan = 600f, step = 16f) { it >= fill * 1.5f }
            scenario.read { a ->
                assertEquals("Aspect: Zoom. Switch to Fill screen", aspectButton(a).contentDescription)
                assertTrue(shownTexts(a).toString(), "Zoom" in shownTexts(a))
            }
            assertPicture(scenario, AspectMode.Zoom, zoom = zoomed)
            delay(700)
            assertFalse("the pinch brought the controls up", scenario.read { a -> playerView(a).isControllerFullyVisible || backArrow(a).isShown })
            val zoomedBar = shot(scenario, bar(scenario))
            assertTrue("the zoomed picture doesn't reach the screen's edge: ${zoomedBar.distinct().take(5).map(::hex)}", zoomedBar.count { !same(it, bar.first()) } > zoomedBar.size / 2)
            val saved = awaitSaved(AspectMode.Zoom)
            assertEquals(zoomed, saved, 0.01f)

            // With the controls up, back towards Fill screen: it snaps there, and the controls stay.
            scenario.onActivity { playerView(it).showController() }
            waitFor("the controls") { scenario.read { a -> playerView(a).isControllerFullyVisible.takeIf { it } } }
            pinch(scenario, startSpan = 1_400f, step = -16f) { it <= fill * 1.04f }
            assertPicture(scenario, AspectMode.Fill)
            val camera = scenario.read(::camera)
            assertEquals("Aspect: Fill screen. Switch to ${if (camera) "Fill beside camera" else "Fit"}", scenario.read { a -> aspectButton(a).contentDescription })
            delay(700)
            assertTrue("the pinch hid the controls", scenario.read { a -> playerView(a).isControllerFullyVisible })

            // A tap still hides them, and another brings them back.
            tap(scenario)
            waitFor("a tap to hide the controls", timeoutMs = 3_000) { scenario.read { a -> (!playerView(a).isControllerFullyVisible).takeIf { it } } }
            tap(scenario)
            waitFor("a tap to show the controls", timeoutMs = 3_000) { scenario.read { a -> playerView(a).isControllerFullyVisible.takeIf { it } } }

            // Pinched in past Fit: Fit.
            pinch(scenario, startSpan = 1_400f, step = -24f) { it <= 1f }
            assertPicture(scenario, AspectMode.Fit)
            assertEquals("Aspect: Fit. Switch to Fill screen", scenario.read { a -> aspectButton(a).contentDescription })
            awaitSaved(AspectMode.Fit)

            // From Fill screen, two fingers that hardly move (a grip on the phone) leave it as it is, unsaid and unsaved.
            scenario.onActivity { a -> aspectButton(a).performClick() }
            assertPicture(scenario, AspectMode.Fill)
            awaitSaved(AspectMode.Fill)
            waitFor("the label to go", timeoutMs = 4_000) { scenario.read { a -> ("Fill screen" !in shownTexts(a)).takeIf { it } } }
            var moves = 0
            pinch(scenario, startSpan = 600f, step = 16f) { ++moves >= 4 }
            delay(300)
            assertPicture(scenario, AspectMode.Fill)
            scenario.read { a ->
                assertEquals("Aspect: Fill screen. Switch to ${if (camera) "Fill beside camera" else "Fit"}", aspectButton(a).contentDescription)
                assertFalse(shownTexts(a).toString(), "Fill screen" in shownTexts(a) || "Zoom" in shownTexts(a))
            }
            assertEquals(AspectMode.Fill to 1f, container.settings.playback.first().let { it.aspect to it.zoomScale })
        }
    }

    /**
     * Fit's bars are black, as a TV's must be (TV-LO, #199), not the window's grey: at the sides of
     * the 16:9 fixture on this wider screen, and above and below a picture wider than the screen,
     * as a 2.39:1 film's is.
     */
    @Test
    fun fitsBarsAreBlack() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            awaitPicture(scenario)
            hideControls(scenario)
            val sides = shot(scenario, bar(scenario))
            assertTrue("Fit's bars at the sides aren't black: ${sides.distinct().take(5).map(::hex)}", sides.all(::black))

            assumeTrue("the screen is as wide as a 2.39:1 film", scenario.read { a -> playerView(a).width.toFloat() / playerView(a).height < 2.3f })
            scenario.onActivity { a -> (contentFrame(a) as AspectRatioFrameLayout).setAspectRatio(2.39f) }
            waitFor("Fit to lay out a 2.39:1 picture") {
                scenario.read { a -> shownRect(contentFrame(a)).let { abs(it.width() / it.height() - 2.39f) < 0.01f && it.top > 8 }.takeIf { it } }
            }
            delay(300)
            val above = strips(scenario)
            assertTrue("Fit's bars above and below aren't black: ${above.distinct().take(5).map(::hex)}", above.all(::black))
        }
    }

    /** A TV remote reaches the button from the controls, OK cycles it, and the button holds the controls as the others do. */
    @Test
    fun onATvRemoteTheDpadReachesTheButtonAndOkCyclesIt() = runBlocking<Unit> {
        instrumentation.setInTouchMode(false)
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            awaitPicture(scenario)
            // Past the intro, so the skip button doesn't take the focus.
            scenario.onActivity { it.player!!.seekTo(60_000) }
            scenario.onActivity { playerView(it).showController() }
            waitFor("the controls, play/pause focused") { scenario.read { a -> (playerView(a).isControllerFullyVisible && a.currentFocus?.id == androidx.media3.ui.R.id.exo_play_pause).takeIf { it } } }
            press(scenario, KeyEvent.KEYCODE_DPAD_UP, "the audio and subtitles button") { a -> a.currentFocus === audioButton(a) }
            press(scenario, KeyEvent.KEYCODE_DPAD_LEFT, "the aspect button") { a -> a.currentFocus === aspectButton(a) }
            val beside = (AspectMode.FillBesideCamera to "Fill beside camera").takeIf { scenario.read(::camera) }
            listOfNotNull(AspectMode.Fill to "Fill screen", beside, AspectMode.Fit to "Fit").forEach { (mode, label) ->
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor("$label after OK") { scenario.read { a -> aspectButton(a).contentDescription.takeIf { it.startsWith("Aspect: $label.") } } }
                scenario.read { a ->
                    assertTrue(shownTexts(a).toString(), label in shownTexts(a))
                    assertTrue("the button lost the focus to ${a.currentFocus}", aspectButton(a).isFocused)
                    assertTrue(playerView(a).isControllerFullyVisible)
                }
                assertPicture(scenario, mode)
            }
            // As the controls fade, the focused button stays; OK on it still cycles, and brings them back.
            scenario.onActivity { a -> playerView(a).controllerShowTimeoutMs = 1_000 }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("the controls to fade with the button still focused", timeoutMs = 6_000) {
                scenario.read { a -> (!playerView(a).isControllerFullyVisible && a.findViewById<View>(androidx.media3.ui.R.id.exo_progress).isShown && aspectButton(a).isShown && aspectButton(a).isFocused).takeIf { it } }
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            // Fill screen to the mode after it.
            val (after, label) = beside ?: (AspectMode.Fit to "Fit")
            waitFor("$label, with the controls up again", timeoutMs = 3_000) {
                scenario.read { a -> (aspectButton(a).contentDescription.startsWith("Aspect: $label.") && playerView(a).isControllerFullyVisible).takeIf { it } }
            }
            awaitSaved(after)
        }
    }

    /**
     * Only the picture is scaled: the skip button and the subtitles stay where Fit puts them, in
     * every mode and zoomed. When the video's shape changes (a new track or resolution) the mode
     * applies to the new shape.
     */
    @Test
    fun theOverlaysAndSubtitlesStayAsTheyAre() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            awaitPicture(scenario)
            // In the intro, paused: the skip button shows with the controls, and stays.
            scenario.onActivity { it.player!!.seekTo(INTRO_START_MS + 1_000) }
            waitFor("Skip intro", timeoutMs = 20_000) { scenario.read { a -> overlayWords(a).takeIf { "Skip intro" in it } } }
            scenario.onActivity { a -> a.player!!.playWhenReady = false; playerView(a).showController() }
            waitFor("the controls") { scenario.read { a -> playerView(a).isControllerFullyVisible.takeIf { it } } }
            delay(500)
            val (skip, subtitles) = scenario.read { a -> skipRect(a) to shownRect(playerView(a).subtitleView!!) }
            assertEquals(scenario.read { a -> shownRect(contentFrame(a)) }.toShortString(), subtitles.toShortString())

            listOfNotNull(AspectMode.Fill, AspectMode.FillBesideCamera.takeIf { scenario.read(::camera) }).forEach { mode ->
                scenario.onActivity { a -> aspectButton(a).performClick() }
                assertPicture(scenario, mode)
                scenario.read { a ->
                    assertEquals("the skip button in $mode", skip.toShortString(), skipRect(a).toShortString())
                    assertEquals("the subtitles in $mode", subtitles.toShortString(), shownRect(a.playerViewSubtitles()).toShortString())
                }
            }
            val fill = scenario.read { a -> fillScale(a) }
            val zoomed = pinch(scenario, startSpan = 600f, step = 16f) { it >= fill * 1.6f }
            assertPicture(scenario, AspectMode.Zoom, zoom = zoomed)
            scenario.read { a ->
                assertEquals("the skip button zoomed", skip.toShortString(), skipRect(a).toShortString())
                assertEquals("the subtitles zoomed", subtitles.toShortString(), shownRect(a.playerViewSubtitles()).toShortString())
            }

            // The video turning 4:3 mid-stream: Fill screen covers the screen in the new shape.
            scenario.onActivity { a -> a.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame).let { it as AspectRatioFrameLayout }.setAspectRatio(4f / 3f); aspectButton(a).performClick() }
            waitFor("Fit to lay out the new shape") { scenario.read { a -> shownRect(contentFrame(a)).let { abs(it.width() / it.height() - 4f / 3f) < 0.01f }.takeIf { it } } }
            assertPicture(scenario, AspectMode.Fill)
            awaitSaved(AspectMode.Fill)
        }
    }

    // The picture, as the surface's bounds on screen say.

    /** Waits until the file plays with its picture laid out by Fit, narrower than the screen. */
    private suspend fun awaitPicture(scenario: ActivityScenario<PlayerActivity>) {
        waitFor("the picture", timeoutMs = 30_000) {
            scenario.read { a ->
                val p = a.player ?: return@read null
                val shutter = playerView(a).findViewById<View>(androidx.media3.ui.R.id.exo_shutter)
                val frame = contentFrame(a)
                (p.videoSize.width > 0 && p.isPlaying && shutter.visibility != View.VISIBLE && frame.width in 1 until playerView(a).width - 4).takeIf { it }
            }
        }
    }

    /**
     * The surface as [mode] puts it, always in the picture's shape: Fit's frame; covering the
     * screen (Fill screen); covering the screen but for the strip beside the camera, centred beside
     * it (Fill beside camera); [zoom] times Fit's frame.
     */
    private fun assertPicture(scenario: ActivityScenario<PlayerActivity>, mode: AspectMode, zoom: Float = 1f) {
        scenario.read { a ->
            val view = shownRect(playerView(a))
            val frame = shownRect(contentFrame(a))
            val surface = shownRect(playerView(a).videoSurfaceView!!)
            val fill = fillScale(a)
            val expected = when (mode) {
                AspectMode.Fit -> frame
                AspectMode.Fill -> scaled(frame, fill, fill)
                AspectMode.Zoom -> scaled(frame, zoom, zoom)
                AspectMode.FillBesideCamera -> besideCamera(a).let { box ->
                    val s = max(box.width() / frame.width(), box.height() / frame.height())
                    scaled(frame, s, s).apply { offset(box.centerX() - centerX(), box.centerY() - centerY()) }
                }
            }
            val off = listOf(surface.left - expected.left, surface.top - expected.top, surface.right - expected.right, surface.bottom - expected.bottom).maxOf { abs(it) }
            assertTrue("$mode: the surface shows at ${surface.toShortString()}, not ${expected.toShortString()} (frame ${frame.toShortString()}, screen ${view.toShortString()})", off <= 2f)
            if (mode == AspectMode.Fill) assertTrue("Fill screen leaves bars: ${surface.toShortString()} in ${view.toShortString()}", surface.contains(RectF(view.left + 1, view.top + 1, view.right - 1, view.bottom - 1)))
        }
    }

    /** The button and the surface as [mode] puts them. */
    private fun assertMode(scenario: ActivityScenario<PlayerActivity>, mode: AspectMode) {
        val label = mapOf(AspectMode.Fit to "Fit", AspectMode.Fill to "Fill screen", AspectMode.FillBesideCamera to "Fill beside camera").getValue(mode)
        val says = scenario.read { a -> aspectButton(a).contentDescription.toString() }
        assertTrue("$mode: the button says \"$says\"", says.startsWith("Aspect: $label."))
        assertPicture(scenario, mode)
    }

    /** Waits for [mode] to be saved in the playback preferences; returns the zoom saved with it. */
    private suspend fun awaitSaved(mode: AspectMode): Float =
        waitFor("$mode to be saved") { runBlocking { container.settings.playback.first().takeIf { it.aspect == mode }?.zoomScale } }

    /** Whether the window has a display cutout: then the button offers Fill beside camera. */
    private fun camera(a: Activity): Boolean = ViewCompat.getRootWindowInsets(a.window.decorView)?.displayCutout?.boundingRects.orEmpty().isNotEmpty()

    /** The player's view but for a strip along each edge a camera sits on (the one its cutout is nearest), as deep as it reaches in. */
    private fun besideCamera(a: Activity): RectF {
        val view = playerView(a)
        val (w, h) = view.width.toFloat() to view.height.toFloat()
        val rects = ViewCompat.getRootWindowInsets(a.window.decorView)?.displayCutout?.boundingRects.orEmpty()
        return rects.fold(RectF(0f, 0f, w, h)) { box, r ->
            box.apply {
                when (listOf(r.left.toFloat(), r.top.toFloat(), w - r.right, h - r.bottom).let { d -> d.indexOf(d.min()) }) {
                    0 -> left = maxOf(left, r.right.toFloat())
                    1 -> top = maxOf(top, r.bottom.toFloat())
                    2 -> right = minOf(right, r.left.toFloat())
                    else -> bottom = minOf(bottom, r.top.toFloat())
                }
            }
        }
    }

    private fun fillScale(a: Activity): Float {
        val view = playerView(a)
        val frame = contentFrame(a)
        return max(view.width.toFloat() / frame.width, view.height.toFloat() / frame.height)
    }

    private fun scaled(r: RectF, sx: Float, sy: Float) =
        RectF(r.centerX() - r.width() * sx / 2, r.centerY() - r.height() * sy / 2, r.centerX() + r.width() * sx / 2, r.centerY() + r.height() * sy / 2)

    /** [view]'s bounds in the window through every scale on the way up: where it shows. */
    private fun shownRect(view: View): RectF {
        val r = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
        var v: View? = view
        while (v != null) {
            v.matrix.mapRect(r)
            val parent = v.parent as? View
            r.offset((v.left - (parent?.scrollX ?: 0)).toFloat(), (v.top - (parent?.scrollY ?: 0)).toFloat())
            v = parent
        }
        return r
    }

    /** The columns, on screen, of the bar Fit leaves at the player's left edge. */
    private fun bar(scenario: ActivityScenario<PlayerActivity>) = scenario.read { a -> onScreen(a, 4f..shownRect(contentFrame(a)).left - 4) }

    /** The columns, on screen, from [window]'s start to its end in the window: a screenshot has the whole screen. */
    private fun onScreen(a: Activity, window: ClosedFloatingPointRange<Float>): IntRange {
        val left = IntArray(2).also(a.window.decorView::getLocationOnScreen)[0]
        return (left + window.start).toInt()..(left + window.endInclusive).toInt()
    }

    /**
     * A screenshot's pixels every 8 px over [columns] (on screen), down the middle of the
     * player's height. Taken with the controls down, whose dim would cover the picture.
     */
    private fun shot(scenario: ActivityScenario<PlayerActivity>, columns: IntRange): List<Int> {
        val (top, bottom) = scenario.read { a ->
            val at = IntArray(2).also(playerView(a)::getLocationOnScreen)
            at[1] to at[1] + playerView(a).height
        }
        val shot = instrumentation.uiAutomation.takeScreenshot()!!
        assertTrue("the screenshot is ${shot.width}×${shot.height}, not landscape", shot.width > shot.height)
        return (columns step 8).flatMap { x -> (top + (bottom - top) / 10 until bottom - (bottom - top) / 10 step 8).map { shot.getPixel(x, it) } }
    }

    /**
     * A screenshot's pixels every 8 px across the middle of the player's width, in the bars Fit
     * leaves above and below the picture. Taken with the controls down.
     */
    private fun strips(scenario: ActivityScenario<PlayerActivity>): List<Int> {
        val (columns, rows) = scenario.read { a ->
            val at = IntArray(2).also(playerView(a)::getLocationOnScreen)
            val frame = shownRect(contentFrame(a))
            val (width, height) = playerView(a).width to playerView(a).height
            val columns = (at[0] + width / 10 until at[0] + width * 9 / 10 step 8)
            val rows = (at[1] + 2 until at[1] + frame.top.toInt() - 2 step 4) + (at[1] + frame.bottom.toInt() + 2 until at[1] + height - 2 step 4)
            columns to rows
        }
        assertTrue("no bars above and below to look at", rows.isNotEmpty())
        val shot = instrumentation.uiAutomation.takeScreenshot()!!
        return columns.flatMap { x -> rows.map { shot.getPixel(x, it) } }
    }

    /** Black, give or take a compressor's noise. */
    private fun black(pixel: Int) = listOf(Color::red, Color::green, Color::blue).all { it(pixel) <= 8 }

    /** Whether two pixels are of one colour, give or take the video's noise. */
    private fun same(a: Int, b: Int) = listOf(Color::red, Color::green, Color::blue).all { abs(it(a) - it(b)) <= 24 }

    private fun hex(pixel: Int) = "%06x".format(pixel and 0xffffff)

    // Touch, as a finger gives it.

    /**
     * Two fingers either side of the picture's centre, [startSpan] apart, moved [step] apart (or
     * together) at a time until the picture's scale meets [until]; then lifted. Returns the scale
     * the picture had as they lifted.
     */
    private suspend fun pinch(scenario: ActivityScenario<PlayerActivity>, startSpan: Float, step: Float, until: (Float) -> Boolean): Float {
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
                send(down, MotionEvent.ACTION_POINTER_UP or second, fingers())
                send(down, MotionEvent.ACTION_UP, fingers().take(1))
                return scale
            }
        }
        send(down, MotionEvent.ACTION_POINTER_UP or second, fingers())
        send(down, MotionEvent.ACTION_UP, fingers().take(1))
        throw AssertionError("the pinch never got the picture where it was going; its scale was $scale")
    }

    /**
     * One finger down and up in the picture's middle third, where a tap shows or hides the controls at once (one at the
     * sides waits for a second, and two there seek), away from the controls' buttons and the top bar's.
     */
    private suspend fun tap(scenario: ActivityScenario<PlayerActivity>) {
        val at = scenario.read { a ->
            val xy = IntArray(2).also(playerView(a)::getLocationOnScreen)
            PointF(xy[0] + playerView(a).width * 0.5f, xy[1] + playerView(a).height * 0.3f)
        }
        val down = SystemClock.uptimeMillis()
        send(down, MotionEvent.ACTION_DOWN, listOf(at))
        delay(60)
        send(down, MotionEvent.ACTION_UP, listOf(at))
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

    private suspend fun hideControls(scenario: ActivityScenario<PlayerActivity>) {
        scenario.onActivity { playerView(it).hideController() }
        waitFor("the controls to hide") { scenario.read { a -> (!playerView(a).isControllerFullyVisible && !backArrow(a).isShown).takeIf { it } } }
        // Their hide animation.
        delay(800)
    }

    private suspend fun press(scenario: ActivityScenario<PlayerActivity>, key: Int, what: String, isIt: (PlayerActivity) -> Boolean) {
        instrumentation.sendKeyDownUpSync(key)
        waitFor("$what to have focus after ${KeyEvent.keyCodeToString(key)}, not ${scenario.read { it.currentFocus }}", timeoutMs = 3_000) {
            scenario.read { a -> isIt(a).takeIf { it } }
        }
    }

    // Views.

    private fun aspectButton(activity: Activity) = views(activity.window.decorView).filterIsInstance<ImageButton>().single { it.contentDescription?.startsWith("Aspect:") == true }

    private fun audioButton(activity: Activity) = views(activity.window.decorView).filterIsInstance<ImageButton>().single { it.contentDescription == "Audio and subtitles" }

    private fun backArrow(activity: Activity) = views(activity.window.decorView).filterIsInstance<ImageButton>().single { it.contentDescription == "Back" }

    /** What the shown text views say: the top bar's titles, and the label of a new mode while it shows. */
    private fun shownTexts(activity: Activity) = views(activity.window.decorView).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }

    private fun contentFrame(activity: Activity) = playerView(activity).findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)

    private fun PlayerActivity.playerViewSubtitles(): View = playerView(this).subtitleView!!

    private fun playerView(activity: Activity) = views(activity.window.decorView).filterIsInstance<PlayerView>().single()

    private fun views(view: View): List<View> =
        listOf(view) + ((view as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())

    private fun overlayNodes(activity: Activity): List<SemanticsNode> =
        views(activity.window.decorView).filterIsInstance<ViewRootForTest>().filter { it.view.isShown }
            .flatMap { subtree(it.semanticsOwner.unmergedRootSemanticsNode) }

    private fun subtree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::subtree)

    private fun overlayWords(activity: Activity) = overlayNodes(activity).flatMap { n ->
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    /** The skip button's bounds: its node is the polite live region. */
    private fun skipRect(activity: Activity): RectF =
        overlayNodes(activity).single { it.config.contains(SemanticsProperties.LiveRegion) }.boundsInWindow.let { RectF(it.left, it.top, it.right, it.bottom) }

    /** A data source that opens nothing until [gate] lets it. */
    private class Held(private val source: DataSource, private val gate: CountDownLatch) : DataSource by source {
        override fun open(dataSpec: DataSpec): Long {
            gate.await()
            return source.open(dataSpec)
        }
    }

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

    private companion object {
        const val INTRO_START_MS = 5_000L
    }
}
