package io.skjaere.usenet.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.search.SuggestionCollectionAction
import io.skjaere.usenet.app.search.TitleInfo
import io.skjaere.usenet.app.search.TvTitleCard
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.newznab.TvIds
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A title's collection action on Search with a remote (not in touch mode) keeps the focus as the title
 * is added, on the title card and on a suggestion: "+ Collection", "Adding…" while it is written, then
 * "In collection ›". A disabled or replaced button would drop it, and the remote would hand it to the
 * search field above: its keyboard up, Search turned to suggestions.
 */
@RunWith(AndroidJUnit4::class)
class CollectionActionFocusTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var adding by mutableStateOf(false)
    private var collected by mutableStateOf(false)
    private val title = TitleInfo(MediaType.Tv, 900071, "Glasswater Bay", 2024, "TV series · 2024 · 1 season", null, null, null, TvIds(tmdb = 900071), 45)

    @Before
    fun remote() = instrumentation.setInTouchMode(false)

    @After
    fun restore() = instrumentation.setInTouchMode(true)

    /** [action] under a search field (where a remote's dropped focus would go, first on the screen), focused, then the title added. */
    private fun assertKeepsTheFocus(action: @Composable () -> Unit) {
        compose.setContent {
            AppTheme {
                Column {
                    TextField("glasswater", {}, Modifier.fillMaxWidth())
                    action()
                }
            }
        }
        val add = hasText("+ Collection") and hasClickAction()
        compose.onNode(add).requestFocus()
        compose.onNode(add).assertIsFocused()
        adding = true
        compose.onNode(hasText("Adding…")).assertIsFocused()
        adding = false
        collected = true
        compose.onNode(hasText("In collection ›") and hasClickAction()).assertIsFocused()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
    }

    @Test
    fun theTitleCardsCollectionActionKeepsTheFocusAsTheTitleIsAdded() = assertKeepsTheFocus {
        TvTitleCard(title, showArtwork = false, inCollection = collected, adding = adding, onAdd = {}, onOpen = {}, onJump = {})
    }

    @Test
    fun aSuggestionsCollectionActionKeepsTheFocusAsTheTitleIsAdded() = assertKeepsTheFocus {
        SuggestionCollectionAction(title.name, inCollection = collected, adding = adding, onAdd = {}, onJump = {})
    }
}
