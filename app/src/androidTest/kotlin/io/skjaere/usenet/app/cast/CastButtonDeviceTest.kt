package io.skjaere.usenet.app.cast

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.cast.Cast
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.casting.CastTimings
import io.skjaere.usenet.app.collection.ShowScreen
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.home.HomeScreen
import io.skjaere.usenet.app.link.ButtonFace
import io.skjaere.usenet.app.link.CastButtonLook
import io.skjaere.usenet.app.link.CastButtonMotion
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.link.castButtonSizes
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * The Cast button (W43, W59, W66, L113, L114, L146; CsPhoneButton) on a real phone, on Home (round, in its search bar) and
 * on a Show page's top bar, over fake devices and sessions (L73): This phone shows Google's Cast icon with Google Play
 * services there, and the TV icon without them (the runner's fake); a Cast device connecting has its waves light in turn
 * from the corner out, about 1.3 s a loop, as the test moves the clock, and the rule still goes idle as its own clock
 * runs; with animations off ([CastButtonMotion.enabled], so no setting of the phone's is touched) they stay on ③;
 * connected, Google's filled icon in the amber circle; reconnecting, the waves again. TalkBack reads C1's "Play on: …"
 * words. Drawing each face starts nothing of Google's. What the button draws is read from its pixels, where the design's
 * colours are. The show and the device are invented.
 */
@RunWith(AndroidJUnit4::class)
class CastButtonDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val routes = FakeRoutes()
    private val connector = FakeConnector()
    private val announced = CopyOnWriteArrayList<String>()
    private val kitchen = castRoute("Kitchen display")
    private val realMotion = CastButtonMotion.enabled
    private var tmdb: FakeTmdb? = null
    private var saved: AppSettings? = null
    private var keyAdded = false
    private var page by mutableStateOf(Page.None)

    private enum class Page { None, Show, Home }

    private val show = ShowDetails(
        SHOW, "Harbor Lights", null, 2021, null, null, null, "Ended", false, 45,
        listOf(SeasonSummary(1, null, 6, LocalDate.of(2021, 3, 1), null)),
        lastEpisode = EpisodeSummary(1, 6, "Night Crossing", LocalDate.of(2021, 4, 5)), nextEpisode = null, ids = ExternalIds(tvdb = 991_501),
    )

    private val season = Season(SHOW, 1, null, null, (1..6).map { Episode(1, it, "Episode $it", null, LocalDate.of(2021, 3, 1).plusWeeks(it - 1L), 45, null) })

    @Before
    fun setUp() {
        assumeFalse("a TV has no Cast button (tv.TvNoCastTest)", container.television)
        runBlocking {
            // Read before CastFakes chooses to look for Cast devices: put back after it puts its own back.
            saved = container.settings.settings.first()
            assumeTrue("no Usenet provider saved, so Home shows the first-run screen", container.settings.providers.first().isNotEmpty())
        }
        // A device connects for as long as each case needs it to.
        CastTimings.current = CastTimings(connectMillis = 60_000)
        CastFakes.started(routes, connector, announced)
        runBlocking {
            container.settings.setIndexerSetupSkipped(true)
            if (container.settings.tmdbKey() == null) {
                container.settings.setTmdbKey("cast-button-device-test")
                keyAdded = true
            }
        }
        tmdb = FakeTmdb(shows = mapOf(SHOW to show), seasons = { id, _ -> if (id == SHOW) season else null })
        compose.setContent {
            AppTheme {
                when (page) {
                    Page.None -> Unit
                    Page.Show -> ShowScreen(SHOW, onBack = {})
                    Page.Home -> HomeScreen(onSearch = {}, onRecent = {}, onSettings = {}, onSetup = { _, _ -> }, onShow = {}, onCollection = {}, onImport = {})
                }
            }
        }
    }

    @After
    fun restore() {
        compose.mainClock.autoAdvance = true
        CastButtonMotion.enabled = realMotion
        container.playOn.choose(PlayChoice.Phone)
        tmdb?.close()
        CastFakes.reset()
        saved?.let { settings ->
            runBlocking {
                container.settings.restore(settings)
                if (keyAdded) container.settings.setTmdbKey(null)
            }
        }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, millis: Long = 10_000) = compose.waitUntil(millis) { exists(matcher) }

    /** The button, by its own semantics: the one node that says which face it shows. */
    private val button = SemanticsMatcher.keyIsDefined(CastButtonLook)

    /** The face the button shows now, and what TalkBack reads of it. */
    private fun face(): ButtonFace? = compose.onAllNodes(button).fetchSemanticsNodes().singleOrNull()?.config?.let { config ->
        ButtonFace(config[CastButtonLook], config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString())
    }

    private fun waitForFace(face: ButtonFace) {
        compose.waitUntil(10_000) { face() == face }
    }

    /** With the clock held ([CastButtonMotion]'s loop runs on it), frames until [condition], for 10 s at most. */
    private fun framesUntil(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < end) { "timed out with the clock held" }
            compose.mainClock.advanceTimeByFrame()
        }
    }

    /** Whether Google's Cast software is still unstarted in this process, asked on the main thread (L143). */
    private fun castUnstarted(): Boolean = onMainThread { Cast.getSingletonInstance(context).needsInitialization() }

    /** Kitchen display's session started, connecting, and the device chosen, as its row's tap does, with no sheet. */
    private fun connecting() {
        onMainThread {
            GoogleCast.connection.attach(connector, context)
            GoogleCast.connection.connect(kitchen)
        }
        container.playOn.choose(PlayChoice.Cast(kitchen.id, kitchen.name))
    }

    private val atRest = ButtonFace(ButtonFace.Look.Rest, "Play on: this phone")
    private val noCast = ButtonFace(ButtonFace.Look.NoPlayServices, "Play on: this phone")
    private val connectingFace = ButtonFace(ButtonFace.Look.Connecting, "Play on: Kitchen display, connecting")
    private val connectedFace = ButtonFace(ButtonFace.Look.Connected, "Play on: Kitchen display")

    /** What the button draws now. */
    private fun drawn(): ImageBitmap = compose.onNode(button).captureToImage()

    /**
     * Which of the waves are lit (in the accent) in [image], the button as drawn with a [round] face that [look]s so: each
     * read at the middle of its band, on Google's 24-unit grid, the icon in the middle of the button.
     */
    private fun lit(image: ImageBitmap, look: ButtonFace.Look, round: Boolean): List<Boolean> {
        val pixels = image.toPixelMap()
        val icon = with(compose.density) { castButtonSizes(look, round).icon.dp.toPx() }
        val left = (image.width - icon) / 2
        val top = (image.height - icon) / 2
        return WAVE_MIDDLES.map { (x, y) -> accent(pixels[(left + x * icon / 24).toInt(), (top + y * icon / 24).toInt()]) }
    }

    /** The colour of [image] [dpBelow] dp under its middle: inside the circle, outside the icon. */
    private fun circleColour(image: ImageBitmap, dpBelow: Int): Color =
        image.toPixelMap()[image.width / 2, (image.height / 2 + with(compose.density) { dpBelow.dp.toPx() }).toInt()]

    /** The design's accent, #F2A33A, give or take what the screen's blending does to it. */
    private fun accent(colour: Color) = abs(colour.red - 0xF2 / 255f) < 0.1f && abs(colour.green - 0xA3 / 255f) < 0.1f && abs(colour.blue - 0x3A / 255f) < 0.12f

    @Test
    fun thisPhoneShowsGooglesCastIconWithPlayServicesAndTheTvIconWithout() {
        // The runner's: Play services missing, as on a phone without Google's (L146; CsPhoneButton's last row).
        CastFakes.missing()
        page = Page.Home
        waitForFace(noCast)
        assertTrue("Google's Cast software unstarted", castUnstarted())
        page = Page.Show
        waitForFace(noCast)
        assertTrue("Google's Cast software unstarted", castUnstarted())
        // With them: Google's Cast icon at rest, on the Show page's bar and round on Home.
        CastFakes.started()
        waitForFace(atRest)
        assertTrue("Google's Cast software unstarted", castUnstarted())
        page = Page.Home
        waitForFace(atRest)
        assertTrue("Google's Cast software unstarted", castUnstarted())
        assertEquals("nothing started", 0, CastFakes.starts.get())
    }

    @Test
    fun aConnectingDeviceLightsItsWavesInTurnFromTheCornerOut() {
        page = Page.Show
        waitForFace(atRest)
        // The test moves the clock from here, so the loop runs as it does on a phone: a rule that moves its own clock
        // stops infinite animations, which would otherwise never let it go idle.
        compose.mainClock.autoAdvance = false
        connecting()
        framesUntil { face() == connectingFace }
        assertTrue("Google's Cast software unstarted", castUnstarted())
        // Twelve looks an eighth of a loop apart: a loop and a half.
        val seen = (0 until 12).map {
            lit(drawn(), ButtonFace.Look.Connecting, round = false).also { compose.mainClock.advanceTimeBy(CastButtonMotion.PERIOD_MS / 8L) }
        }
        // Lit from the corner out: never an outer wave without the ones inside it.
        seen.forEach { waves -> assertEquals("lit from the corner out: $seen", waves.sortedDescending(), waves) }
        val counts = seen.map { waves -> waves.count { it } }
        // In turn: none, the corner wave, the middle one too, all three, and again, a step at a time.
        val steps = counts.zipWithNext().filter { (a, b) -> a != b }
        assertTrue("each step lights the next wave, or starts again: $counts", steps.all { (a, b) -> b == (a + 1) % 4 })
        assertEquals("every frame seen: $counts", setOf(0, 1, 2, 3), counts.toSet())
        // About 1.3 s a loop: four steps a loop, so about six in a loop and a half.
        assertTrue("about 1.3 s a loop: $counts", steps.size in 5..7)
        // The rule's own clock again: the loop stops for the test (Compose's tests stop infinite animations), and it goes idle.
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(connectingFace, face())
        assertTrue("Google's Cast software unstarted", castUnstarted())
    }

    @Test
    fun withAnimationsOffTheWavesStayOnThree() {
        CastButtonMotion.enabled = { false }
        page = Page.Home
        waitForFace(atRest)
        compose.mainClock.autoAdvance = false
        connecting()
        framesUntil { face() == connectingFace }
        repeat(8) {
            assertEquals("③, all three lit, at every look", listOf(true, true, true), lit(drawn(), ButtonFace.Look.Connecting, round = true))
            compose.mainClock.advanceTimeBy(CastButtonMotion.PERIOD_MS / 8L)
        }
        assertTrue("Google's Cast software unstarted", castUnstarted())
    }

    @Test
    fun connectedFillsTheButtonAndReconnectingHasTheWavesAgain() {
        page = Page.Show
        waitForFace(atRest)
        connecting()
        waitForFace(connectingFace)
        // Connecting: the chip's circle, not yet the amber.
        assertTrue("the chip's grey round the waves", !accent(circleColour(drawn(), 15)))
        connector.started(kitchen)
        waitForFace(connectedFace)
        assertTrue("the amber circle", accent(circleColour(drawn(), 15)))
        assertTrue("Google's Cast software unstarted", castUnstarted())
        // Home's round one fills the same way.
        page = Page.Home
        waitForFace(connectedFace)
        assertTrue("the amber circle", accent(circleColour(drawn(), 15)))
        // Suspended: reconnecting looks as connecting does, and says so (C1's words).
        connector.suspended(kitchen)
        waitForFace(connectingFace)
        assertTrue("Google's Cast software unstarted", castUnstarted())
        assertEquals("nothing started", 0, CastFakes.starts.get())
    }

    private companion object {
        /** Invented: a TMDB id nothing real has. */
        const val SHOW = 991_501

        /**
         * The middle of each wave's band on the icon's 24-unit grid (Material's cast): the corner's quarter disc, then the
         * bands 5 to 7 and 9 to 11 units from the corner (1, 21), each at 45°.
         */
        val WAVE_MIDDLES = listOf(2f to 20f, 5.24f to 16.76f, 8.07f to 13.93f)
    }
}
