package io.skjaere.usenet.app

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.cast.Cast
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.skjaere.usenet.app.cast.CastFakes
import io.skjaere.usenet.app.cast.FakeConnector
import io.skjaere.usenet.app.cast.FakeRoutes
import io.skjaere.usenet.app.casting.CAST_LOOK_FOR
import io.skjaere.usenet.app.casting.CAST_PRIVACY
import io.skjaere.usenet.app.link.ADD_TV_TITLE
import io.skjaere.usenet.app.link.ENTER_ADDRESS
import io.skjaere.usenet.app.link.FakeTv
import io.skjaere.usenet.app.link.POSITION_ON_TV
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.link.PlayingTv
import io.skjaere.usenet.app.link.TVS_TITLE
import io.skjaere.usenet.app.link.YES_IT_MATCHES
import io.skjaere.usenet.app.link.inventedTvName
import io.skjaere.usenet.app.link.isReady
import io.skjaere.usenet.app.link.playOnLabel
import io.skjaere.usenet.app.link.stopPlayingOn
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.settings.GoogleCastPage
import io.skjaere.usenet.app.settings.PRIVACY_TITLE
import io.skjaere.usenet.app.settings.PrivacyPart
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.link.LinkMessage.Status
import io.skjaere.usenet.link.Playback
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The app shell as MainActivity shows it: navigation between screens and what sits at the bottom, and where Add a TV's
 * Done goes from the Play on sheet (the TV chosen) and from Settings › TVs (nothing chosen). On a phone, AppNav's own
 * ways to Settings › Google Cast and to the privacy page: the hub's row, the Google Cast page's Privacy, and the Play on
 * list's.
 */
@RunWith(AndroidJUnit4::class)
class AppNavTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val home = HomeContentShown()

    @Before
    fun showHome() = home.show()

    @After
    fun restore() = home.restore()

    /** Taps [node] twice, the second time while the screens are still cross-fading after the first. */
    private fun doubleTap(node: SemanticsMatcher) {
        compose.mainClock.autoAdvance = false
        compose.onNode(node).performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.onNode(node).performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test
    fun doubleTapOnBackPopsOneScreen() {
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Search your indexers").assertDoesNotExist()

        doubleTap(hasContentDescription("Back"))

        compose.onNodeWithText("Search your indexers").assertIsDisplayed()
    }

    /** Runs [check] while the mini bar shows: a start with no releases to try fails at once, then is minimised. */
    private fun withMiniBar(check: (bar: Rect) -> Unit) {
        val playback = compose.activity.container.playback
        try {
            playback.play(PlayRequest("Mini bar check", null, emptyList(), watch = WatchTarget(WatchKind.Release, "Mini bar check")))
            compose.waitUntil(5_000) { playback.state.value is PlayState.Failed }
            playback.minimize()
            check(compose.onNodeWithText("Couldn't play Mini bar check").fetchSemanticsNode().boundsInWindow)
        } finally {
            playback.dismiss()
        }
    }

    @Test
    fun theMiniBarStaysClearOfTheNavigationBarOnAScreenWithoutTabs() {
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        withMiniBar { bar ->
            val (windowHeight, navigationBar) = compose.runOnUiThread {
                val decor = compose.activity.window.decorView
                decor.height to ViewCompat.getRootWindowInsets(decor)!!.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            }
            assertTrue("no navigation bar inset to stay clear of", navigationBar > 0)
            assertTrue("mini bar ends at ${bar.bottom}, under the navigation bar from ${windowHeight - navigationBar}", bar.bottom <= windowHeight - navigationBar)
        }
    }

    @Test
    fun theMiniBarSitsOnTheTabBar() {
        withMiniBar { bar ->
            val tabs = compose.onNode(hasText("Home") and hasClickAction()).fetchSemanticsNode().boundsInWindow
            assertEquals(tabs.top, bar.bottom, 0.5f)
        }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /**
     * Play on TV (item 26, L38): the Playing on bar sits on the tab bar and opens the remote, which shows the play itself
     * (no bar over it), and closes as the play ends there, back where it was opened.
     */
    @Test
    fun thePlayingOnBarOpensTheRemoteWhichClosesAsThePlayEnds() {
        assumeFalse("a TV has no Playing on bar", compose.activity.container.television)
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().isNotEmpty() }
        PlayingTv().use { playing ->
            playing.play(Playback.Playing)
            val bar = hasText("Harbor Lights · S01E03")
            compose.waitUntil(10_000) { exists(bar) }
            val tabs = compose.onNode(hasText("Home") and hasClickAction()).fetchSemanticsNode().boundsInWindow
            assertEquals("on the tab bar", tabs.top, compose.onNode(bar).fetchSemanticsNode().boundsInWindow.bottom, 0.5f)
            assertTrue("its Stop in it", compose.onNode(hasContentDescription(stopPlayingOn(playing.name))).fetchSemanticsNode().boundsInWindow.bottom <= tabs.top)
            compose.onNode(bar).performClick()
            compose.waitUntil(5_000) { exists(hasContentDescription(POSITION_ON_TV)) }
            assertFalse("no bar over the remote", exists(bar))
            playing.say(Status(PlayingTv.SESSION, Playback.Stopped, mine = true))
            compose.waitUntil(5_000) { !exists(hasContentDescription(POSITION_ON_TV)) }
            compose.onNodeWithText("Search your indexers").assertIsDisplayed()
            assertFalse("the bar gone with the play", exists(bar))
        }
    }

    /** A TV in the test's process taking pairings, its OK given; on this device's own address, typed in (no mDNS). */
    private fun pairingTv(): FakeTv = FakeTv(compose.activity, inventedTvName("Living room TV"), advertised = false).also {
        it.answer = true
        it.pairing()
    }

    /** Add a TV, open: [tv] paired through its typed address, and Done. */
    private fun pairAndDone(tv: FakeTv) {
        tap(hasText(ENTER_ADDRESS) and hasClickAction())
        compose.waitUntil(10_000) { exists(hasSetTextAction()) }
        compose.onNode(hasSetTextAction()).performTextInput("${tv.address.hostAddress}:${tv.port}")
        tap(hasText("Connect") and hasClickAction())
        tap(hasText(YES_IT_MATCHES) and hasClickAction(), millis = 20_000)
        compose.waitUntil(15_000) { exists(hasText(isReady(tv.name))) }
        tap(hasText("Done") and hasClickAction())
    }

    private fun tap(matcher: SemanticsMatcher, millis: Long = 10_000) {
        compose.waitUntil(millis) { exists(matcher) }
        compose.onNode(matcher).performScrollTo().performClick()
    }

    /** The TV paired in a test gone from the app's link.json, and This phone the choice again. */
    private fun forget(tv: FakeTv) {
        val container = compose.activity.container
        container.playOn.choose(null)
        tv.close()
        runBlocking { container.data.link.forgetTv(tv.key.id) }
    }

    /** Item 23, PtPhonePair's note: Done from Add a TV opened from the Play on sheet chooses the TV it paired. */
    @Test
    fun addATvFromThePlayOnSheetChoosesTheTvItPairs() {
        val container = compose.activity.container
        assumeFalse("a TV has no Play on", container.television)
        val tv = pairingTv()
        try {
            tap(hasContentDescription(playOnLabel(PlayTarget.Phone)) and hasClickAction())
            tap(hasText(ADD_TV_TITLE) and hasClickAction())
            pairAndDone(tv)
            compose.waitUntil(5_000) { container.playOn.chosen.value == PlayChoice.Tv(tv.key.id) }
        } finally {
            forget(tv)
        }
    }

    /** The privacy page's policy, its first paragraph shown once its text is read. */
    private val policyShown = SemanticsMatcher.expectValue(PrivacyPart, 0)

    /**
     * L112, L138 through AppNav's graph on a phone: the hub's Google Cast row opens its page, the switch on it, and the
     * page's Privacy, under its footnote, opens the privacy page.
     */
    @Test
    fun settingsGoogleCastOpensItsPageWhosePrivacyOpensThePolicy() {
        assumeFalse("a TV has no Google Cast page (tv.TvNoCastTest)", compose.activity.container.television)
        tap(hasContentDescription("Settings") and hasClickAction())
        compose.waitUntil(10_000) { exists(hasText(TVS_TITLE) and hasClickAction()) }
        val row = hasText(GoogleCastPage.TITLE) and hasClickAction()
        assertTrue("the hub's Google Cast row", exists(row))
        tap(row)
        val switch = hasText(GoogleCastPage.SWITCH) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        compose.waitUntil(10_000) { exists(switch) }
        tap(hasText(PRIVACY_TITLE) and hasText(GoogleCastPage.PRIVACY_LINE) and hasClickAction())
        compose.waitUntil(10_000) { exists(policyShown) }
    }

    /**
     * L138, L143 through AppNav on a phone: with "Look for Cast devices" not chosen, the Play on list's Privacy closes the
     * list and opens the privacy page; nothing is looked for, and nothing of Google's starts.
     */
    @Test
    fun theListsPrivacyClosesItAndOpensThePolicy() {
        val container = compose.activity.container
        assumeFalse("a TV has no Play on (tv.TvNoCastTest)", container.television)
        val routes = FakeRoutes()
        CastFakes.started(routes, FakeConnector(), CopyOnWriteArrayList())
        try {
            runBlocking {
                container.settings.updateCast { it.copy(look = false) }
                withTimeout(10_000) { container.castLooking.first { it == false } }
            }
            compose.waitUntil(10_000) { exists(hasContentDescription("Settings")) }
            compose.runOnUiThread { container.playOn.openSheet() }
            compose.waitUntil(10_000) { exists(hasText(CAST_LOOK_FOR) and hasClickAction()) }
            compose.onNode(hasText(CAST_PRIVACY), useUnmergedTree = true).performFirstLinkClick()
            compose.waitUntil(5_000) { container.playOn.sheet.value == null }
            compose.waitUntil("the privacy page opened", 10_000) { exists(policyShown) }
            assertEquals("nothing looked for", 0, routes.watching)
            assertEquals("nothing started", 0, CastFakes.starts.get())
            assertTrue("Google's Cast software unstarted", onMainThread { Cast.getSingletonInstance(compose.activity).needsInitialization() })
        } finally {
            compose.runOnUiThread { container.playOn.closeSheet() }
            CastFakes.reset()
        }
    }

    /** Done from Add a TV opened from Settings › TVs pairs the TV and chooses nothing. */
    @Test
    fun addATvFromSettingsChoosesNothing() {
        val container = compose.activity.container
        assumeFalse("a TV has no Settings › TVs", container.television)
        val tv = pairingTv()
        try {
            tap(hasContentDescription("Settings") and hasClickAction())
            tap(hasText(TVS_TITLE) and hasClickAction())
            tap(hasText("Add") and hasClickAction())
            pairAndDone(tv)
            compose.waitUntil(5_000) { exists(hasText(TVS_TITLE)) && runBlocking { container.data.link.tvs.first() }.any { it.key == tv.key.id } }
            Thread.sleep(500)
            assertEquals("nothing chosen", PlayChoice.Phone, container.playOn.chosen.value)
        } finally {
            forget(tv)
        }
    }
}
