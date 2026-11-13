package io.skjaere.usenet.app

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.data.SearchPick
import io.skjaere.usenet.data.TitleType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Search's suggestions with a remote (not in touch mode, #160): from the field Right reaches Search
 * indexers at its end, and Down the first title; from a row's title Right reaches its "+ Collection",
 * for a show and for a movie; OK adds the title and opens the collection with the focus on its row,
 * and one Back returns to Search. A title card's "+ Collection" (a recent search picked from TMDB,
 * searched again by the title's id) likewise, and Back finds the card as it was. Nothing here is
 * touched: the tab is opened and text typed through semantics actions, which leave touch mode off.
 */
@RunWith(AndroidJUnit4::class)
class SearchSuggestionDpadTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val fixture = JumpFixture()

    @Before
    fun setUp() {
        fixture.open()
        instrumentation.setInTouchMode(false)
    }

    @After
    fun restore() {
        instrumentation.setInTouchMode(true)
        fixture.close()
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun focused(): String = compose.onAllNodes(isFocused()).fetchSemanticsNodes().lastOrNull()?.let { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" | ")
            ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" | ") { it.text }
    } ?: "(nothing)"

    private fun press(key: Int): String {
        instrumentation.sendKeyDownUpSync(key)
        compose.waitForIdle()
        return focused()
    }

    /** [key] on a remote ([sendRemoteKey]): a text field moves the focus on it. */
    private fun remote(key: Int): String {
        sendRemoteKey(key)
        compose.waitForIdle()
        return focused()
    }

    private fun fieldFocused() = exists(hasSetTextAction() and isFocused())

    private fun type(text: String) {
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput(text)
    }

    /** From [name]'s pick area: Right to its "+ Collection", OK, its row focused in the collection, Back to Search. */
    private fun addWithTheRemote(name: String) {
        val pick = hasText(name) and hasClickAction()
        compose.waitUntil(10_000) { exists(pick) }
        compose.onNode(pick).requestFocus()
        compose.waitUntil(5_000) { focused().startsWith(name) }
        assertTrue("Right went to ${focused()}", press(KeyEvent.KEYCODE_DPAD_RIGHT) == "+ Collection, $name")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        val row = hasContentDescription(name, substring = true) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Just added")
        compose.waitUntil(10_000) { exists(row and isFocused()) }
        // The one Back right after the jump is Search's, not the list's way up to the top bar.
        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { exists(hasText("In collection ›")) && exists(pick) }
    }

    private fun openSearch() {
        compose.setContent { AppTheme { AppNav() } }
        val tab = hasText("Search") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.waitUntil(15_000) { exists(tab) }
        compose.onNode(tab).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(10_000) { exists(hasSetTextAction()) }
    }

    @Test
    fun fromTheFieldRightReachesSearchIndexersAndDownTheFirstTitle() {
        openSearch()
        type("glasswater")
        compose.waitUntil(10_000) { exists(hasText("Glasswater Bay") and hasClickAction()) }
        assertTrue("the field has the focus; it is on ${focused()}", fieldFocused())

        assertEquals("Right from the field", "Search indexers for “glasswater”", remote(KeyEvent.KEYCODE_DPAD_RIGHT))
        // Left goes back to the field, by way of Clear between them.
        repeat(2) { if (!fieldFocused()) remote(KeyEvent.KEYCODE_DPAD_LEFT) }
        assertTrue("Left from Search indexers: the field; focus on ${focused()}", fieldFocused())
        assertTrue("Down from the field: the first title; focus on ${focused()}", remote(KeyEvent.KEYCODE_DPAD_DOWN).startsWith("Glasswater Bay"))
    }

    @Test
    fun aSuggestionsCollectionButtonIsReachedWithTheRemoteAndAddsAndJumps() {
        openSearch()
        type("quiet orb")
        addWithTheRemote("The Quiet Orbit")
        type("copper val")
        addWithTheRemote("Copper Valley")
    }

    @Test
    fun aTitleCardsCollectionButtonPressedWithTheRemoteLeavesTheCardForBack() {
        // Searched before from its card (the play sheet's Search): the recent search runs it again, by the title's id.
        runBlocking { compose.activity.container.library.addSearch("Glasswater Bay", SearchPick(TitleType.Tv, JumpFixture.GLASSWATER, "Glasswater Bay")) }
        openSearch()
        val recent = hasText("Glasswater Bay") and hasText("Indexer search") and hasClickAction()
        compose.waitUntil(10_000) { exists(recent) }
        compose.onNode(recent).performSemanticsAction(SemanticsActions.OnClick)
        val card = hasText("TV series · 2024 · 1 season")
        compose.waitUntil(10_000) { exists(card) }
        val add = hasText("+ Collection") and hasClickAction()
        compose.onNode(add).requestFocus()
        compose.waitUntil(5_000) { focused() == "+ Collection, Glasswater Bay" }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        val row = hasContentDescription("Glasswater Bay", substring = true) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Just added")
        compose.waitUntil(10_000) { exists(row and isFocused()) }
        press(KeyEvent.KEYCODE_BACK)
        // Search as it was left: the card, now "In collection ›", not the suggestions for its title; the field (and the keyboard) not taken.
        compose.waitUntil(10_000) { exists(hasText("In collection ›")) }
        compose.waitForIdle()
        assertTrue("Search shows the suggestions for its title, not the card", exists(card))
        assertFalse("the field took the focus", exists(hasSetTextAction() and isFocused()))
    }
}
