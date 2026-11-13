package io.skjaere.usenet.app

import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.media3.cast.Cast
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.cast.CastFakes
import io.skjaere.usenet.app.cast.FakeConnector
import io.skjaere.usenet.app.cast.FakeRoutes
import io.skjaere.usenet.app.casting.CAST_LOOK_FOR
import io.skjaere.usenet.app.casting.CAST_PRIVACY
import io.skjaere.usenet.app.link.FakeTv
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.link.PlayOnHost
import io.skjaere.usenet.app.link.SHEET_TITLE
import io.skjaere.usenet.app.link.inventedTvName
import io.skjaere.usenet.app.settings.HUB_FOOTNOTE
import io.skjaere.usenet.app.settings.PRIVACY_SEEDED
import io.skjaere.usenet.app.settings.PRIVACY_TITLE
import io.skjaere.usenet.app.settings.PrivacyPart
import io.skjaere.usenet.app.settings.PrivacyScreen
import io.skjaere.usenet.app.settings.SettingsScreen
import io.skjaere.usenet.app.settings.privacyRowLine
import io.skjaere.usenet.app.settings.privacySections
import io.skjaere.usenet.app.ui.AppTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Settings › Privacy on a phone (W60, L138, L139; CsPhoneSettingsCast 1, 3): its row under About, saying what leaves this
 * phone, over the footnote; the page reads the policy from the app's own copy, every part of it in order, while every URL
 * fetch fails, and this debug build's paragraph about the accounts it came with first. Until the site's page is live
 * (PRIVACY_ONLINE) no row links it: the top bar, then the policy, and nothing opens the browser; once it is, "Read it
 * online" sends the browser one VIEW of https://nzstreamer.app/privacy, which the test takes in its place, or with no
 * browser says where to read it. Both are rendered here whatever PRIVACY_ONLINE says. The list's "Look for Cast devices"
 * Privacy closes the list and opens the page, starting nothing of Google's (L143). The player's ask (MainActivity's
 * extra) opens the page in the activity, but not again from Recents; with the app open, in the activity there is, the TV
 * chosen kept. A turn keeps the reader's place.
 */
@RunWith(AndroidJUnit4::class)
class PrivacyScreenDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realProxies: ProxySelector? = ProxySelector.getDefault()

    /** Stands in for the browser: each web link's VIEW kept, none started. */
    private val browser = object : Instrumentation.ActivityMonitor() {
        val views = CopyOnWriteArrayList<Intent>()

        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
            if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme in setOf("http", "https")) {
                views += intent
                Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            } else {
                null
            }
    }

    @Before
    fun setUp() {
        assumeFalse("a TV's page is tv.PrivacyTvTest's: it opens nothing", container.television)
        instrumentation.addMonitor(browser)
    }

    @After
    fun restore() {
        instrumentation.removeMonitor(browser)
        ProxySelector.setDefault(realProxies)
        compose.runOnUiThread { container.playOn.closeSheet() }
        CastFakes.reset()
    }

    /** [matcher] on the screen; [unmerged]: as a node of its own, a paragraph inside a part. */
    private fun exists(matcher: SemanticsMatcher, unmerged: Boolean = false) = compose.onAllNodes(matcher, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, millis: Long = 10_000) = compose.waitUntil(millis) { exists(matcher) }

    /** The policy as the APK carries it, read here on its own. */
    private fun policy(): String = context.assets.open("privacy.txt").bufferedReader().use { it.readText() }

    /** The policy's first paragraph: the page shows, its text read. */
    private val policyShown = SemanticsMatcher.expectValue(PrivacyPart, 0)

    private val readOnline = hasText("Read it online") and hasText("nzstreamer.app/privacy") and hasClickAction()

    /** What TalkBack reads of a row, its role aside: its words in order. */
    private fun spoken(matcher: SemanticsMatcher): List<String> =
        compose.onNode(matcher).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    /**
     * L138, L139: from the hub's About, the page: this build's paragraph (a debug build came set up with someone's
     * accounts), then each heading and paragraph of the APK's policy in order, the headings headings for TalkBack and the
     * last reachable. Java's networking fails every fetch meanwhile ([FailingFetches]): the page asks for none.
     */
    @Test
    fun theHubsPrivacyUnderAboutOpensThePolicyFromTheAppsOwnCopyWithEveryFetchFailing() {
        val fetches = FailingFetches()
        ProxySelector.setDefault(fetches)
        compose.setContent {
            AppTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = SettingsRoute) {
                    composable<SettingsRoute> { SettingsScreen({}, {}, {}, {}, {}, {}, onLogs = {}, onPrivacy = { nav.navigate(PrivacyRoute) }) }
                    composable<PrivacyRoute> { PrivacyScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }
        val row = hasText(PRIVACY_TITLE) and hasText(privacyRowLine(television = false)) and hasClickAction()
        waitFor(row)
        compose.onNode(row).performScrollTo()
        fun top(matcher: SemanticsMatcher, unmerged: Boolean = false) = compose.onNode(matcher, useUnmergedTree = unmerged).fetchSemanticsNode().positionInRoot.y
        assertTrue("under About", top(hasText("About".uppercase())) < top(row))
        assertTrue("after the App group's Logs", top(hasText("Logs") and hasClickAction()) < top(hasText("About".uppercase())))
        assertTrue("the footnote under it", top(row) < top(hasText(HUB_FOOTNOTE)))
        assertEquals("its words, not its ›", listOf(PRIVACY_TITLE, privacyRowLine(television = false)), spoken(row))
        compose.onNode(row).performClick()

        waitFor(policyShown)
        val sections = privacySections(policy())
        val texts = listOf(PRIVACY_SEEDED) + sections.flatMap { listOfNotNull(it.heading) + it.paragraphs }
        compose.waitUntil(10_000) { texts.all { exists(hasText(it), unmerged = true) } }
        assertTrue("a debug build's paragraph", BuildConfig.SEED_DEV_SETTINGS)
        val tops = texts.map { top(hasText(it), unmerged = true) }
        assertEquals("in order", tops.sorted(), tops)
        sections.mapNotNull { it.heading }.forEach { heading ->
            assertTrue("$heading, heading", compose.onNode(hasText(heading), useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Heading) != null)
        }
        compose.onNode(hasText(texts.last()), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        // Nothing at all, from any host, while the page showed: the clients the app made at its start aren't seen here, so
        // PrivacyPageTest checks that the page's files name no way to the network either.
        assertEquals("nothing fetched", emptyList<URI>(), fetches.asked.toList())
        assertTrue("no web link opened", browser.views.isEmpty())
    }

    /**
     * A turn, or any change that makes the activity again, keeps the reader's place in the policy, as other pages keep
     * theirs, though the page reads its text again after it.
     */
    @Test
    fun aTurnKeepsTheReadersPlace() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { AppTheme { PrivacyScreen(onBack = {}) } }
        // The policy's last paragraph, the furthest place to read to.
        val last = hasText(privacySections(policy()).last().paragraphs.last())
        compose.waitUntil(10_000) { exists(last, unmerged = true) }
        fun top() = compose.onNode(last, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
        val unread = top()
        compose.onNode(last, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        val read = top()
        assertTrue("the page scrolled, from $unread to $read", read < unread)
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(10_000) { exists(last, unmerged = true) }
        compose.waitForIdle()
        assertEquals("back where the reader was, not at the top", read, top(), 1f)
    }

    /**
     * L138: until the site's page is live (PRIVACY_ONLINE off, as every build has it now), the page links it nowhere: no
     * "Read it online" row, no address, nothing to say a browser is missing. The top bar, then the policy, its first
     * paragraph the page's usual 16 dp under the bar (the bar's own 8 dp under its Back, 11 dp above the paragraph and its
     * 5 inside); Back the one thing to tap.
     */
    @Test
    fun theSitesPageIsLinkedNowhereWhileItIsntLive() {
        compose.setContent { AppTheme { PrivacyScreen(onBack = {}, online = false) } }
        waitFor(policyShown)
        listOf("Read it online", "Read it at", "nzstreamer.app/privacy", "can't open web links").forEach { words ->
            assertFalse("\"$words\" on the page", exists(hasText(words, substring = true), unmerged = true))
        }
        val back = hasContentDescription("Back") and hasClickAction()
        assertEquals("Back the one thing to tap", 1, compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
        val bar = compose.onNode(back).fetchSemanticsNode().boundsInRoot.bottom
        val first = compose.onNode(policyShown).fetchSemanticsNode().boundsInRoot.top
        val gap = with(compose.density) { (8 + 11).dp.toPx() }
        assertEquals("the policy right under the top bar", bar + gap, first, 1.5f)
        assertTrue("no web link opened", browser.views.isEmpty())
    }

    /**
     * L138: once the site's page is live (PRIVACY_ONLINE on), "Read it online" over the address is the page's first row,
     * the policy under it; it sends the browser one VIEW of the site's page, and says nothing more. TalkBack reads its words
     * alone, not the ↗ drawn after them.
     */
    @Test
    fun readItOnlineSendsTheBrowserOneViewOfTheSitesPage() {
        compose.setContent { AppTheme { PrivacyScreen(onBack = {}, online = true) } }
        waitFor(readOnline)
        waitFor(policyShown)
        val row = compose.onNode(readOnline).fetchSemanticsNode().boundsInRoot
        assertTrue("the row over the policy", row.bottom <= compose.onNode(policyShown).fetchSemanticsNode().boundsInRoot.top)
        assertEquals(listOf("Read it online", "nzstreamer.app/privacy"), spoken(readOnline))
        compose.onNode(readOnline).performClick()
        compose.waitUntil(5_000) { browser.views.isNotEmpty() }
        compose.waitForIdle()
        val view = browser.views.single()
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals("https://nzstreamer.app/privacy", view.dataString)
        assertFalse("a browser took it", exists(hasText("This device can't open web links", substring = true), unmerged = true))
    }

    /**
     * L138: once the site's page is live, a phone with no browser to take "Read it online" says where to read it, under
     * the row.
     */
    @Test
    fun withNoBrowserReadItOnlineSaysWhereToReadIt() {
        val noBrowser = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) {
                throw ActivityNotFoundException("no browser here")
            }
        }
        compose.setContent { AppTheme { CompositionLocalProvider(LocalContext provides noBrowser) { PrivacyScreen(onBack = {}, online = true) } } }
        waitFor(readOnline)
        compose.onNode(readOnline).performClick()
        val line = hasText("This device can't open web links. Visit https://nzstreamer.app/privacy in a browser elsewhere.")
        compose.waitUntil(5_000) { exists(line, unmerged = true) }
        val row = compose.onNode(readOnline).fetchSemanticsNode().boundsInRoot
        assertTrue("under the row", row.bottom <= compose.onNode(line, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top)
        assertTrue("nothing reached a browser", browser.views.isEmpty())
    }

    /**
     * L138, L143: with "Look for Cast devices" not chosen, the list's Privacy closes the list and opens the page; nothing
     * is looked for, and nothing of Google's starts.
     */
    @Test
    fun theListsPrivacyClosesItAndOpensThePageStartingNothing() {
        val routes = FakeRoutes()
        CastFakes.started(routes, FakeConnector(), CopyOnWriteArrayList())
        runBlocking {
            container.settings.updateCast { it.copy(look = false) }
            withTimeout(10_000) { container.castLooking.first { it == false } }
        }
        var opened = 0
        compose.setContent { AppTheme { PlayOnHost(onAddTv = {}, onPairAgain = {}, onPrivacy = { opened++ }) } }
        compose.runOnUiThread { container.playOn.openSheet() }
        waitFor(hasText(SHEET_TITLE))
        waitFor(hasText(CAST_LOOK_FOR) and hasClickAction())
        compose.onNode(hasText(CAST_PRIVACY), useUnmergedTree = true).performFirstLinkClick()
        compose.waitUntil(5_000) { opened == 1 }
        compose.waitUntil(5_000) { container.playOn.sheet.value == null }
        assertEquals("nothing looked for", 0, routes.watching)
        assertEquals("nothing started", 0, CastFakes.starts.get())
        assertTrue("Google's Cast software unstarted", onMainThread { Cast.getSingletonInstance(context).needsInitialization() })
    }

    /**
     * L138: the player's ask (MainActivity.openPrivacy, as the remote's notification asks) opens the page in the activity,
     * once; the same intent started again from Recents opens Home alone.
     */
    @Test
    fun thePlayersAskOpensThePageButNotAgainFromRecents() {
        ActivityScenario.launch<MainActivity>(MainActivity.openPrivacy(context)).use { scenario ->
            waitFor(policyShown, millis = 15_000)
            scenario.onActivity { assertEquals(1, it.privaciesAsked) }
        }
        // The activity's intent takes the rule's own activity's place: nothing of the rule's is left to wait on here.
        ActivityScenario.launch<MainActivity>(MainActivity.openPrivacy(context).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)).use { scenario ->
            // Its intent is read as it is made, and launch() returns once it is in front.
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            scenario.onActivity { assertEquals("asked from Recents", 0, it.privaciesAsked) }
            // Home's shell drawn, its bar's tabs and all, with no page over it.
            compose.waitUntil(15_000) { exists(hasText("Collection")) }
            assertFalse(exists(policyShown))
        }
    }

    /**
     * L138, W18: the player's ask with the app open, as the launcher opens it, comes back to the activity there is and opens
     * the page there (onNewIntent), closing what is over it. A second activity would choose This phone again: the TV chosen
     * stays chosen.
     */
    @Test
    fun thePlayersAskOpensThePageInTheActivityThereIs() {
        val link = container.data.link
        val phone = runBlocking { link.identity().identity.key.id }
        val tv = FakeTv(context, inventedTvName("Living room TV"), paired = setOf(phone), advertised = false)
        try {
            assertTrue(runBlocking { link.addTv(tv.key.id, tv.name, tv.address.hostAddress!!, tv.port) })
            // As the launcher opens the app: its own task, which the ask's flags find. ActivityScenario's has another affinity.
            context.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            compose.waitUntil(15_000) { mainActivities(Stage.RESUMED).size == 1 }
            val opened = mainActivities(Stage.RESUMED).single()
            onMainThread { container.playOn.choose(tv.key.id) }
            context.startActivity(MainActivity.openPrivacy(context))
            compose.waitUntil(15_000) { onMainThread { opened.privaciesAsked } == 1 }
            waitFor(policyShown, millis = 15_000)
            assertEquals("one MainActivity", listOf(opened), mainActivities(*ALIVE))
            assertEquals("the TV still chosen: no new activity chose This phone (W18)", PlayChoice.Tv(tv.key.id), container.playOn.chosen.value)
        } finally {
            mainActivities(*ALIVE).forEach { activity -> onMainThread { activity.finish() } }
            compose.waitUntil(10_000) { mainActivities(*ALIVE).isEmpty() }
            container.playOn.choose(null)
            tv.close()
            runBlocking { link.forgetTv(tv.key.id) }
        }
    }

    /** The app's MainActivity instances in any of [stages]. */
    private fun mainActivities(vararg stages: Stage): List<MainActivity> = onMainThread {
        stages.flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.filterIsInstance<MainActivity>().distinct()
    }

    private companion object {
        /** An activity's stages from its making to its end. */
        val ALIVE = arrayOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.RESTARTED)
    }

    /**
     * Java's default proxy chooser while a test holds it: every URL fetch through HttpURLConnection, or an OkHttp client
     * made meanwhile, goes to a port nothing listens on and fails, its address kept in [asked].
     */
    private class FailingFetches : ProxySelector() {
        val asked = CopyOnWriteArrayList<URI>()

        override fun select(uri: URI): List<Proxy> {
            asked += uri
            return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getLoopbackAddress(), 1)))
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }
}
