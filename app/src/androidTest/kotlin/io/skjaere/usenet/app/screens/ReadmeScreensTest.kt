package io.skjaere.usenet.app.screens

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.BuildConfig
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.cast.CastFakes
import io.skjaere.usenet.app.cast.FakeConnector
import io.skjaere.usenet.app.cast.FakeRoutes
import io.skjaere.usenet.app.cast.castRoute
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.link.CastButtonLook
import io.skjaere.usenet.app.link.FakeTv
import io.skjaere.usenet.app.link.SHEET_TITLE
import io.skjaere.usenet.app.link.TvState
import io.skjaere.usenet.app.link.sheetStateLine
import io.skjaere.usenet.app.overlayWords
import io.skjaere.usenet.app.setup.PhoneSetupScreen
import io.skjaere.usenet.app.setup.SetupFakes
import io.skjaere.usenet.app.tabsFor
import io.skjaere.usenet.app.tv.Remote
import io.skjaere.usenet.app.tv.TvShell
import io.skjaere.usenet.app.tv.inTvCollection
import io.skjaere.usenet.app.tv.railItem
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.views
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * The README's screenshots, staged over invented titles and taken on the emulators (tools/screens/capture.sh runs it):
 * P1-P5 on a phone, T1-T4 on a TV, each test skipped on the other. Skipped unless the instrumentation argument
 * `screens=true` is given, so no other run of the device tests takes it; given it, any build but the bench build with
 * debrid hidden fails the run ([ScreensRun]).
 *
 * Everything shown is staged ([ScreensStage]): the screens catalogue's shows and films, answered by its strict FakeTmdb,
 * their pictures drawn by tools/screens/art.py, and their releases served by mock-seed's `indexer --catalog screens`
 * (the instrumentation arguments `screensIndexer`, its address as the emulator reaches it, and `screensIndexerKey`) over
 * the mock NNTP server, where the two that play hold a public-domain film of the 1890s. The TV and the Cast device on
 * Play on's list are fakes with invented names, and Set up with your phone shows an address from a documentation range.
 * Each screen is saved with the words on it ([ScreenShots]) once every picture it shows has loaded.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@androidx.annotation.OptIn(UnstableApi::class)
class ReadmeScreensTest {
    /** The player hides the system bars: Android's hint about that would show on the player's screens. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val remote = Remote(compose)
    private lateinit var stage: ScreensStage
    private lateinit var shots: ScreenShots
    private val closing = CopyOnWriteArrayList<AutoCloseable>()

    @Before
    fun setUp() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("the README screenshots run with the instrumentation argument screens=true alone", arguments.getString("screens") == "true")
        assumeTrue("the screenshots are taken on Android 12 or later", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        ScreensRun.buildProblem(BuildConfig.BUILD_TYPE, BuildConfig.HIDE_DEBRID)?.let { throw IllegalStateException(it) }
        val indexer = requireNotNull(arguments.getString("screensIndexer")) { "screensIndexer: the screens indexer's address, as http://10.0.2.2:<port>" }
        val key = arguments.getString("screensIndexerKey") ?: DEFAULT_INDEXER_KEY
        // Kept before it opens, so tearDown closes even a stage that failed to: closing twice does nothing.
        stage = ScreensStage(ScreensTitles(), indexer, key)
        stage.open()
        shots = ScreenShots()
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { players().forEach { it.finish() } }
        container.playback.cancel()
        instrumentation.setInTouchMode(true)
        closing.reversed().forEach { it.close() }
        if (::stage.isInitialized) stage.close()
    }

    private val tv: Boolean get() = container.television

    private fun phoneOnly() = assumeFalse("a phone's screenshot", tv)

    private fun tvOnly() = assumeTrue("a TV's screenshot", tv)

    // The phone's screens.

    @Test
    fun p1Home() {
        phoneOnly()
        launchMain().use {
            shoot("p1-home") {
                waitFor(hasText(stage.gullenkiln.name))
                waitFor(hasText(stage.quillon.name))
                waitFor(hasText(stage.kelpmoor.name))
                awaitImages(listOf(stage.kelpmoor, stage.quillon, stage.gullenkiln).mapNotNull { it.posterPath })
            }
        }
    }

    @Test
    fun p2Show() {
        phoneOnly()
        launchMain().use {
            shoot("p2-show") {
                openKelpmoor()
            }
        }
    }

    @Test
    fun p3Releases() {
        phoneOnly()
        launchMain().use {
            shoot("p3-releases") {
                openKelpmoor()
                chooseRelease()
            }
        }
    }

    @Test
    fun p4Player() {
        phoneOnly()
        launchMain().use {
            shoot("p4-player") {
                openKelpmoor()
                chooseRelease()
                tap(hasText(REPACK, substring = true) and hasClickAction())
                val player = playing()
                // Past the intro, so Skip intro (the TV's screenshot has it) is gone; then the panel, with the clip's
                // three audio tracks and its subtitles.
                waitFor("the clip past its intro", 60_000) { onMain { (player.player!!.currentPosition > PAST_INTRO_MS).takeIf { it } } }
                waitFor("Skip intro to go") { onMain { ("Skip intro" !in overlayWords(player)).takeIf { it } } }
                onMain { audioButton(player).performClick() }
                waitFor("the audio and subtitles panel") { onMain { overlayWords(player).takeIf { SPANISH_AUDIO in it } } }
            }
        }
    }

    @Test
    fun p5PlayOn() {
        phoneOnly()
        val tvName = LIVING_ROOM_TV
        val link = container.data.link
        val phone = runBlocking { link.identity().identity.key.id }
        val fakeTv = FakeTv(context, tvName, paired = setOf(phone), advertised = false)
        closing += AutoCloseable {
            fakeTv.close()
            runBlocking { link.forgetTv(fakeTv.key.id) }
        }
        runBlocking { check(link.addTv(fakeTv.key.id, fakeTv.name, fakeTv.address.hostAddress!!, fakeTv.port)) }
        val routes = FakeRoutes()
        CastFakes.started(routes, FakeConnector(), CopyOnWriteArrayList())
        closing += AutoCloseable { CastFakes.reset() }
        routes.found(castRoute(KITCHEN_DISPLAY))
        launchMain().use {
            shoot("p5-play-on") {
                openKelpmoor()
                tap(SemanticsMatcher.keyIsDefined(CastButtonLook))
                waitFor(hasText(SHEET_TITLE))
                waitFor(hasText(KITCHEN_DISPLAY))
                waitFor(hasText(sheetStateLine(TvState.Ready, tvName)), 30_000)
            }
        }
    }

    // The TV's screens.

    @Test
    fun t1Home() {
        tvOnly()
        launchTv().use {
            shoot("t1-home") {
                instrumentation.setInTouchMode(false)
                remote.waitForHomeFocus(timeoutMs = 15_000)
                remote.waitForFocus(stage.kelpmoor.name, timeoutMs = 10_000)
                awaitImages(listOfNotNull(stage.kelpmoor.posterPath, stage.titles.season(stage.kelpmoor.tmdbId, 1).episodes[3].stillPath))
            }
        }
    }

    @Test
    fun t2Collection() {
        tvOnly()
        launchTv().use {
            shoot("t2-collection") {
                instrumentation.setInTouchMode(false)
                remote.waitForHomeFocus(timeoutMs = 15_000)
                remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
                alongTheRail(COLLECTION)
                remote.waitForCollectionFocus(timeoutMs = 10_000)
                // A poster says its words to TalkBack as one description. Kelpmoor, played last, comes first.
                waitFor(hasContentDescription(stage.kelpmoor.name, substring = true) and inTvCollection)
                val focus = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, stage.kelpmoor.name, max = 4)
                check(stage.kelpmoor.name in focus) { "the focus never reached Kelpmoor's poster; it is on \"$focus\"" }
                awaitImages(listOfNotNull(stage.kelpmoor.posterPath))
            }
        }
    }

    @Test
    fun t3Player() {
        tvOnly()
        launchTv().use {
            shoot("t3-player") {
                instrumentation.setInTouchMode(false)
                remote.waitForHomeFocus(timeoutMs = 15_000)
                remote.waitForFocus(stage.kelpmoor.name, timeoutMs = 10_000)
                remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
                val episode = hasText(BRINEFALL, substring = true) and hasClickAction()
                waitFor(episode, 15_000)
                // Past the page's guard against the OK that opened it.
                SystemClock.sleep(600)
                compose.onNode(episode).performScrollTo().performClick()
                val player = playing()
                // The clip's Intro chapter: the TV's Skip intro, with the focus, over the picture alone.
                waitFor("Skip intro", 30_000) { onMain { overlayWords(player).takeIf { "Skip intro" in it } } }
                check(onMain { !playerView(player).isControllerFullyVisible }) { "the controls showed over Skip intro" }
            }
        }
    }

    @Test
    fun t4PhoneSetup() {
        tvOnly()
        val fakes = SetupFakes().also { it.addresses.value = SetupFakes.v4(DOCUMENTATION_ADDRESS) }
        launchTv().use { scenario ->
            shoot("t4-phone-setup") {
                instrumentation.setInTouchMode(false)
                remote.waitForHomeFocus(timeoutMs = 15_000)
                // The page as Settings opens it, over a session of the fakes': the address the TV would have on a home network
                // is one kept for documentation, never the emulator's own.
                scenario.onActivity { activity ->
                    activity.setContent {
                        AppTheme(FormFactor.Tv) {
                            val nav = rememberNavController()
                            TvShell(nav, tabsFor(FormFactor.Tv), container.playback) {
                                NavHost(nav, startDestination = SETUP_PAGE) {
                                    composable(SETUP_PAGE) { PhoneSetupScreen(onDone = {}, onBack = {}, deps = fakes.deps) }
                                }
                            }
                        }
                    }
                }
                waitFor(hasText(DOCUMENTATION_ADDRESS, substring = true), 15_000)
            }
        }
    }

    // Steps.

    /**
     * Runs [steps], waits for every picture asked for to load and for the screen to settle, then saves the screen as
     * [name]; on a failure saves it as failed-[name], for a look at what showed.
     */
    private fun shoot(name: String, steps: () -> Any?) {
        try {
            steps()
            awaitImages(emptyList())
            settle()
        } catch (t: Throwable) {
            runCatching { shots.save("failed-$name") }.exceptionOrNull()?.let(t::addSuppressed)
            throw t
        }
        shots.save(name)
    }

    private fun launchMain(): ActivityScenario<MainActivity> = ActivityScenario.launch(MainActivity::class.java)

    /** The app as a TV's launcher starts it. */
    private fun launchTv(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** Kelpmoor's page, from the collection's See all: its poster and its season's episodes shown. */
    private fun openKelpmoor() {
        tap(hasText("See all") and hasClickAction())
        // A collection row says its words to TalkBack as one description.
        tap(hasContentDescription(stage.kelpmoor.name, substring = true) and hasClickAction())
        waitFor(hasText(stage.titles.season(stage.kelpmoor.tmdbId, 1).episodes.first().name!!, substring = true))
        // A phone's page shows the poster; its episodes have no stills.
        awaitImages(listOfNotNull(stage.kelpmoor.posterPath))
    }

    /** Choose release for Kelpmoor's fifth episode, from its menu on the page: the list once the indexer has answered. */
    private fun chooseRelease() {
        tap(hasContentDescription("More for episode 5"))
        tap(hasText("Choose release") and hasClickAction())
        waitFor(hasText("releases from 1 indexer", substring = true), 30_000)
        // The releases this phone can't play too, with why.
        tap(hasText("hidden", substring = true) and hasClickAction())
    }

    /** The player in front once it plays. */
    private fun playing(): PlayerActivity = waitFor("the player to play", 90_000) {
        onMain { players(Stage.RESUMED).firstOrNull()?.takeIf { it.player?.let { p -> p.isPlaying && p.currentPosition > 0 } == true } }
    }

    private fun playerView(activity: Activity) = views(activity.window.decorView).filterIsInstance<PlayerView>().single()

    private fun audioButton(activity: Activity) = views(activity.window.decorView).single { it.contentDescription == "Audio and subtitles" }

    private fun players(vararg stages: Stage = arrayOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.STARTED, Stage.CREATED)): List<PlayerActivity> {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        return stages.flatMap(monitor::getActivitiesInStage).filterIsInstance<PlayerActivity>()
    }

    /**
     * Waits until each of [paths] (as "/nzs/kelpmoor-poster.jpg") has shown, no picture asked for is still on its way and
     * none has been asked for a moment: so every picture on the screen has loaded. A picture no asset answers fails it.
     */
    private fun awaitImages(paths: Collection<String>) {
        val images = stage.images
        var last: List<Int>? = null
        var quietSince = SystemClock.uptimeMillis()
        waitFor("pictures to load: ${paths.joinToString()}", IMAGES_MS) {
            check(images.unknown.isEmpty()) { "no picture answers ${images.unknown.joinToString()}" }
            val now = listOf(images.shown.size, images.fetched.get(), images.fromMemory.get(), images.cancelled.get(), images.failed.get(), images.inFlight)
            if (now != last) {
                last = now
                quietSince = SystemClock.uptimeMillis()
            }
            val shown = images.shown.keys
            (paths.all { p -> shown.any { it.endsWith(p) } } && images.inFlight == 0 && SystemClock.uptimeMillis() - quietSince >= QUIET_MS).takeIf { it }
        }
    }

    /** Lets animations finish (a sheet sliding up, a picture fading in): Compose's clock on for a moment. */
    private fun settle() {
        val until = SystemClock.uptimeMillis() + SETTLE_MS
        compose.waitUntil(timeoutMillis = SETTLE_MS * 4) { SystemClock.uptimeMillis() >= until }
    }

    /** From the rail's tab that has the focus, along it to [label]'s, and OK. */
    private fun alongTheRail(label: String) {
        val order = tabsFor(FormFactor.Tv).map { it.label }
        val from = order.firstOrNull { remote.isFocused(railItem(it)) } ?: throw AssertionError("not on the rail; the focus is on \"${remote.focused()}\"")
        val key = if (order.indexOf(label) < order.indexOf(from)) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
        repeat(abs(order.indexOf(label) - order.indexOf(from))) { remote.press(key) }
        check(remote.isFocused(railItem(label))) { "not on the rail's $label; the focus is on \"${remote.focused()}\"" }
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    // Waits.

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, millis: Long = 10_000) {
        try {
            compose.waitUntil(millis) { exists(matcher) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("never shown: ${matcher.description}", e)
        }
    }

    private fun tap(matcher: SemanticsMatcher, millis: Long = 10_000) {
        waitFor(matcher, millis)
        val node = compose.onAllNodes(matcher)[0]
        // Into view first, where it is in a list.
        runCatching { node.performScrollTo() }
        node.performClick()
        compose.waitForIdle()
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /**
     * Waits until [probe] gives something, and gives it. Compose's clock goes on meanwhile, a frame at a time, as in
     * [waitFor] for a node: under the test's clock the player's overlays (Compose views) change only as it does.
     */
    private fun <T : Any> waitFor(what: String, timeoutMs: Long = 20_000, probe: () -> T?): T {
        var found: T? = null
        try {
            compose.waitUntil(timeoutMillis = timeoutMs) { probe()?.also { found = it } != null }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("timed out waiting for $what", e)
        }
        return found!!
    }

    private companion object {
        /** mock-seed's indexer's key when given none. */
        const val DEFAULT_INDEXER_KEY = "fake-indexer-key"

        /** Past the clip's Intro chapter, its first 15 s. */
        const val PAST_INTRO_MS = 16_000L

        /** The panel's row for the clip's third audio track. */
        const val SPANISH_AUDIO = "Spanish · Stereo · AAC"

        const val COLLECTION = "Collection"

        /** How long the pictures may take to load; then how long nothing new is asked for before they count as loaded. */
        const val IMAGES_MS = 20_000L
        const val QUIET_MS = 1_000L

        /** How long the screen is given to settle before it is saved. */
        const val SETTLE_MS = 800L

        /** The release the phone plays: the one its list ranks first. */
        const val REPACK = "REPACK"

        /** Kelpmoor's fifth episode, which the TV plays from its start. */
        const val BRINEFALL = "Brinefall"

        /** The TV and the Cast device on Play on's list: invented names. */
        const val LIVING_ROOM_TV = "Living room TV"
        const val KITCHEN_DISPLAY = "Kitchen display"

        /** The TV's address on Set up with your phone: from 192.0.2.0/24, a range kept for documentation (RFC 5737). */
        const val DOCUMENTATION_ADDRESS = "192.0.2.24"

        const val SETUP_PAGE = "screens-setup"
    }
}
