package io.skjaere.usenet.app.setup

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.HomeRoute
import io.skjaere.usenet.app.IndexerRoute
import io.skjaere.usenet.app.MetadataRoute
import io.skjaere.usenet.app.PhoneSetupRoute
import io.skjaere.usenet.app.ProviderRoute
import io.skjaere.usenet.app.SettingsRoute
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.app.home.FirstRun
import io.skjaere.usenet.app.home.SetupState
import io.skjaere.usenet.app.settings.phoneSetupHubLine
import io.skjaere.usenet.app.switchTab
import io.skjaere.usenet.app.tabsFor
import io.skjaere.usenet.app.tv.TvShell
import io.skjaere.usenet.app.tv.Remote
import io.skjaere.usenet.app.tv.setTvContent
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.TV_TYPING_TIP_PHONE
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where "Set up with your phone" opens from on a TV (forced on the phone emulator): a row of its own on the Settings
 * hub, and a row under the typing tip on the provider, indexer and TMDB forms, each the same page. Back from the page
 * returns the focus to the row that opened it. A phone has none of them.
 */
@RunWith(AndroidJUnit4::class)
class PhoneSetupEntriesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val remote = Remote(compose)
    private val home = HomeContentShown()
    private lateinit var nav: NavHostController

    @Before
    fun remote() {
        home.show()
        instrumentation.setInTouchMode(false)
    }

    @After
    fun restore() {
        instrumentation.setInTouchMode(true)
        home.restore()
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private val row = hasText(PHONE_SETUP_TITLE) and hasClickAction()

    @Serializable
    private data object FirstRunPage

    private fun showTheApp(formFactor: FormFactor = FormFactor.Tv) {
        if (formFactor == FormFactor.Tv) {
            compose.setTvContent { nav = rememberNavController(); AppNav(nav = nav) }
        } else {
            compose.setContent { AppTheme(FormFactor.Phone) { nav = rememberNavController(); AppNav(nav = nav) } }
        }
        compose.waitForIdle()
    }

    private fun open(route: Any) {
        compose.runOnIdle { if (route == SettingsRoute) nav.switchTab(SettingsRoute) else nav.navigate(route) }
        compose.waitForIdle()
    }

    /**
     * OK on the row opens the page, the same from everywhere, its phone's page the one what is saved calls for (the
     * wizard until a provider and an indexer are saved, then the edit page), and Back returns to the row.
     */
    private fun opensThePage() {
        // Without a home network the page says so, and which page the phone gets would go unchecked.
        val address = runBlocking { withTimeoutOrNull(5_000) { LanAddress.watch(instrumentation.targetContext).first { it != null } } }
        assumeTrue("this device has no Wi-Fi or Ethernet address for the page", address != null)
        val settings = instrumentation.targetContext.container.settings
        // Either pair (L165): a provider and an indexer, or while debrid shows a service and a torrent indexer.
        val canPlay = runBlocking {
            settings.providers.first().isNotEmpty() && settings.indexers.first().isNotEmpty() ||
                DebridGate.shown && settings.services.first().isNotEmpty() && settings.torrentIndexers.first().isNotEmpty()
        }
        compose.waitUntil(5_000) { exists(row) }
        compose.onNode(row).requestFocus()
        remote.waitForFocus(PHONE_SETUP_TITLE)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { exists(hasText("2  " + if (canPlay) PHONE_SETUP_CHANGE else PHONE_SETUP_FOLLOW)) }
        assertTrue("the page's route", compose.runOnIdle { nav.currentDestination?.hasRoute(PhoneSetupRoute::class) == true })
        compose.waitUntil(5_000) { !exists(row) }
        remote.press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { exists(row) }
        remote.waitForFocus(PHONE_SETUP_TITLE)
    }

    @Test
    fun theSettingsHubOpensThePage() {
        showTheApp()
        open(SettingsRoute)
        // Its first focus stays on the providers: tests and users find them first.
        remote.waitForFocus("Usenet providers")
        // What its page takes: the debrid cards too while debrid shows (L209).
        assertTrue(exists(hasText(phoneSetupHubLine(DebridGate.shown))))
        opensThePage()
    }

    /** Each form's row opens the same page as the hub's, every source on it: the row says so. */
    @Test
    fun aProvidersFormOpensThePage() {
        val id = runBlocking { instrumentation.targetContext.container.settings.providers.first().first().id }
        showTheApp()
        open(ProviderRoute(id))
        assertTrue(exists(hasText(PHONE_SETUP_FORM_ROW)))
        assertTrue(exists(hasText(TV_TYPING_TIP_PHONE)))
        opensThePage()
    }

    /** As the provider's: the device tests run with an indexer saved, and one lost is a failure, not a test skipped. */
    @Test
    fun anIndexersFormOpensThePage() {
        val id = runBlocking { instrumentation.targetContext.container.settings.indexers.first().first().id }
        showTheApp()
        open(IndexerRoute(id))
        opensThePage()
    }

    @Test
    fun aNewIndexersFormOpensThePage() {
        showTheApp()
        open(IndexerRoute())
        opensThePage()
    }

    @Test
    fun theTmdbFormOpensThePage() {
        showTheApp()
        open(MetadataRoute)
        opensThePage()
    }

    /** Typing is slow with a remote: the phone first, the step to do next one press below. */
    @Test
    fun theFirstRunOffersThePhoneFirst() {
        val opened = AtomicInteger()
        val state = SetupState(providers = emptyList(), hasIndexer = false, hasTmdbKey = false, indexerSkipped = false)
        val playback = instrumentation.targetContext.container.playback
        compose.setTvContent {
            nav = rememberNavController()
            TvShell(nav, tabsFor(FormFactor.Tv), playback) {
                NavHost(nav, startDestination = HomeRoute) {
                    composable<HomeRoute> { FirstRun(state, onStep = {}, onSkip = {}, onImport = {}, onPhone = { opened.incrementAndGet() }) }
                    composable<SettingsRoute> {}
                }
            }
        }
        // As the rail's Home opens it, so the focus goes into the content.
        compose.runOnIdle { nav.switchTab(SettingsRoute); nav.switchTab(HomeRoute, restore = false) }
        remote.waitForFocus(PHONE_SETUP_TITLE)
        val below = remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Down from the phone: the provider step; focus on \"$below\"", "Add a Usenet provider" in below)
        remote.press(KeyEvent.KEYCODE_DPAD_UP)
        remote.waitForFocus(PHONE_SETUP_TITLE)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { opened.get() == 1 }
    }

    /** Back from a page the first run opened puts the focus on what opened it: the step's button, a step's row, the phone's. */
    @Test
    fun backFromAPageTheFirstRunOpenedReturnsToItsOpener() {
        val state = SetupState(providers = emptyList(), hasIndexer = false, hasTmdbKey = false, indexerSkipped = false)
        val playback = instrumentation.targetContext.container.playback
        compose.setTvContent {
            nav = rememberNavController()
            TvShell(nav, tabsFor(FormFactor.Tv), playback) {
                NavHost(nav, startDestination = HomeRoute) {
                    composable<HomeRoute> {
                        val page = { nav.navigate(FirstRunPage) }
                        FirstRun(state, onStep = { page() }, onSkip = {}, onImport = page, onPhone = page)
                    }
                    composable<SettingsRoute> {}
                    composable<FirstRunPage> { Text("Page of the test") }
                }
            }
        }
        compose.runOnIdle { nav.switchTab(SettingsRoute); nav.switchTab(HomeRoute, restore = false) }
        remote.waitForFocus(PHONE_SETUP_TITLE)
        fun opensAndComesBack(words: String) {
            remote.waitForFocus(words)
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) { exists(hasText("Page of the test")) }
            remote.press(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) { exists(hasText("Set up your sources")) }
            remote.waitForFocus(words)
        }
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        opensAndComesBack("Add a Usenet provider")
        compose.onNode(hasText("TMDB API key") and hasClickAction()).requestFocus()
        opensAndComesBack("TMDB API key")
        compose.onNode(hasText(PHONE_SETUP_TITLE) and hasClickAction()).requestFocus()
        opensAndComesBack(PHONE_SETUP_TITLE)
    }

    @Test
    fun onAPhoneNoneOfThemExists() {
        showTheApp(FormFactor.Phone)
        listOf(SettingsRoute, ProviderRoute(), IndexerRoute(), MetadataRoute).forEach { route ->
            compose.runOnIdle { nav.navigate(route) }
            compose.waitForIdle()
            Thread.sleep(300)
            assertFalse("a phone's $route offers the phone setup", exists(hasText(PHONE_SETUP_TITLE)))
            compose.runOnIdle { nav.popBackStack(HomeRoute, inclusive = false) }
        }
    }
}
