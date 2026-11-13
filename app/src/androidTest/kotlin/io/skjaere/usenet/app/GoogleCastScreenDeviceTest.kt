package io.skjaere.usenet.app

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.cast.CastFakes
import io.skjaere.usenet.app.cast.CastGate
import io.skjaere.usenet.app.cast.CastGateState
import io.skjaere.usenet.app.cast.FakeConnector
import io.skjaere.usenet.app.cast.FakeRoutes
import io.skjaere.usenet.app.casting.CAST_LOOKING
import io.skjaere.usenet.app.casting.CAST_LOOK_FOR
import io.skjaere.usenet.app.link.PlayOnHost
import io.skjaere.usenet.app.link.SHEET_TITLE
import io.skjaere.usenet.app.link.TVS_TITLE
import io.skjaere.usenet.app.settings.GoogleCastPage
import io.skjaere.usenet.app.settings.GoogleCastScreen
import io.skjaere.usenet.app.settings.PRIVACY_TITLE
import io.skjaere.usenet.app.settings.SettingsScreen
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Settings › Playback › Google Cast on a phone (W46, L112; CsPhoneSettingsCast 1, 2): the hub's row after TVs says whether
 * this phone looks for Cast devices and opens the page; the page's switch shows the opt-in and writes it. Turned off, the
 * Play on list asks again at once ("Look for Cast devices", the gate's NotAsked) though Google's software already started
 * in the process; turned on again, the list looks with nothing to tap, and nothing starts twice. Over CastFakes, so nothing
 * of Google's starts (L73, L143); the settings are put back after. Its Privacy row, under the footnote, opens the privacy
 * page (L138).
 */
@RunWith(AndroidJUnit4::class)
class GoogleCastScreenDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val routes = FakeRoutes()
    private val connector = FakeConnector()
    private val announced = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        assumeFalse("a TV has no Google Cast row or page (tv.TvNoCastTest)", container.television)
        CastFakes.started(routes, connector, announced)
    }

    @After
    fun restore() {
        compose.runOnUiThread { container.playOn.closeSheet() }
        CastFakes.reset()
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(10_000) { exists(matcher) }

    private fun role(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    /** The page's switch, in [state]. */
    private fun switch(state: ToggleableState) = hasText(GoogleCastPage.SWITCH) and role(Role.Switch) and SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state)

    /** The list's opt-in row, a button (the page's switch has the same words). */
    private val lookFor = hasText(CAST_LOOK_FOR) and role(Role.Button)

    private fun look(): Boolean = runBlocking { container.settings.cast.first().look }

    private fun waitForLooking(on: Boolean) = runBlocking<Unit> { withTimeout(10_000) { container.castLooking.first { it == on } } }

    private fun openList() {
        compose.runOnUiThread { container.playOn.openSheet() }
        waitFor(hasText(SHEET_TITLE))
    }

    private fun closeList() {
        compose.runOnUiThread { container.playOn.closeSheet() }
        compose.waitUntil(5_000) { !exists(hasText(SHEET_TITLE)) }
    }

    /** CsPhoneSettingsCast 1: the row after TVs, its line by the opt-in ("On: …", "Off"), and a tap opens the page. */
    @Test
    fun theRowAfterTvsSaysWhetherThisPhoneLooksAndOpensThePage() {
        var opened = 0
        compose.setContent { AppTheme { SettingsScreen({}, {}, {}, {}, {}, {}, onLogs = {}, onTvs = {}, onCast = { opened++ }) } }
        val on = hasText(GoogleCastPage.TITLE) and hasText(GoogleCastPage.ON) and hasClickAction()
        waitFor(on)
        compose.onNode(on).performScrollTo()
        fun bounds(matcher: SemanticsMatcher) = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot
        assertTrue("after TVs", bounds(on).top >= bounds(hasText(TVS_TITLE) and hasClickAction()).bottom)
        assertTrue("in Playback, before the App group", bounds(on).bottom <= bounds(hasText("App".uppercase())).top)
        compose.onNode(on).performClick()
        compose.runOnIdle { assertEquals(1, opened) }
        runBlocking { container.settings.updateCast { it.copy(look = false) } }
        waitFor(hasText(GoogleCastPage.TITLE) and hasText(GoogleCastPage.OFF) and hasClickAction())
    }

    /** CsPhoneSettingsCast 2, L138: Privacy, under the footnote, opens the privacy page. */
    @Test
    fun itsPrivacyRowUnderTheFootnoteOpensThePrivacyPage() {
        var opened = 0
        compose.setContent { AppTheme { GoogleCastScreen(onBack = {}, onPrivacy = { opened++ }) } }
        val privacy = hasText(PRIVACY_TITLE) and hasText(GoogleCastPage.PRIVACY_LINE) and hasClickAction()
        waitFor(privacy)
        fun bounds(matcher: SemanticsMatcher) = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot
        assertTrue("under the footnote", bounds(privacy).top >= bounds(hasText(GoogleCastPage.FOOTNOTE.last())).bottom)
        compose.onNode(privacy).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    /**
     * CsPhoneSettingsCast 2, L112: the list looked once (Google's software started, here the fake's), then the switch off:
     * the list asks again, the gate saying NotAsked, and nothing more starts; on again, the list looks at once.
     */
    @Test
    fun theSwitchFlipsAndTheListAsksAgainAfterOff() {
        compose.setContent {
            AppTheme {
                Box {
                    GoogleCastScreen(onBack = {}, onPrivacy = {})
                    PlayOnHost(onAddTv = {}, onPairAgain = {}, onPrivacy = {})
                }
            }
        }
        openList()
        waitFor(hasText(CAST_LOOKING))
        assertEquals("started once", 1, CastFakes.starts.get())
        closeList()

        waitFor(switch(ToggleableState.On))
        compose.onNode(switch(ToggleableState.On)).performClick()
        waitForLooking(false)
        assertFalse("written off", look())
        waitFor(switch(ToggleableState.Off))
        assertEquals("NotAsked with the opt-in off", CastGateState.NotAsked, onMainThread { CastGate.open(context, television = false, looking = false) })
        openList()
        waitFor(lookFor)
        assertFalse("nothing looks", exists(hasText(CAST_LOOKING)))
        assertEquals("nothing started again", 1, CastFakes.starts.get())
        closeList()

        compose.onNode(switch(ToggleableState.Off)).performClick()
        waitForLooking(true)
        assertTrue("written on", look())
        waitFor(switch(ToggleableState.On))
        openList()
        waitFor(hasText(CAST_LOOKING))
        assertFalse("nothing to tap", exists(lookFor))
        assertEquals("nothing started twice", 1, CastFakes.starts.get())
    }
}
