package io.skjaere.usenet.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.skjaere.usenet.app.JumpFixture.Companion.GLASSWATER
import io.skjaere.usenet.app.JumpFixture.Companion.LANTERN
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.data.SearchPick
import io.skjaere.usenet.data.TitleType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "+ Collection" on Search, in the app: from a show's title card (Search on the picked show, as the play
 * sheet's Search opens it), a show suggestion, a movie's card and a movie suggestion, the Collection tab
 * opens on the title's side, the filter that hid it (a saved Watching or Watched) back to All, the
 * title's row on screen and highlighted, TalkBack told; one Back returns to Search as it was, nothing
 * looked up again. "In collection ›" goes there without adding (nor saying "Just added"). Invented
 * titles through the fake TMDB ([JumpFixture]), which puts everything back.
 */
@RunWith(AndroidJUnit4::class)
class CollectionJumpDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fixture = JumpFixture()
    private val container get() = compose.activity.container

    @Before
    fun setUp() = fixture.open()

    @After
    fun restore() = fixture.close()

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) = compose.waitUntil(timeoutMs) { exists(matcher) }

    /** Home's search field opens the Search tab; [text] is typed there. */
    private fun search(text: String) {
        compose.setContent { AppTheme { AppNav() } }
        val field = hasText("Search your indexers") and hasClickAction()
        waitFor(field, 15_000)
        compose.onNode(field).performClick()
        waitFor(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextInput(text)
    }

    /** Search opened on [title], as the play sheet's Search opens it: by the title's id, its card on top. */
    private fun searchPicked(title: SearchPick) {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            AppTheme { AppNav(nav = nav) }
        }
        waitFor(hasText("Search your indexers") and hasClickAction(), 15_000)
        compose.runOnIdle { nav.switchTab(searchRoute(title.name, title), restore = false) }
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /**
     * The title's row revealed and highlighted on [side], with every filter chip at All, and what TalkBack
     * hears: [said], and on the row "Just added" only if it was ([added]).
     */
    private fun assertRevealed(name: String, side: String, said: String, added: Boolean = true) {
        val row = hasContentDescription(name, substring = true) and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)
        // What TalkBack is told comes and goes with the highlight.
        compose.waitUntil(10_000) { exists(row) && exists(hasContentDescription(said)) }
        compose.onNode(row).assertIsDisplayed()
        assertEquals("the row's state", if (added) "Just added" else null, compose.onNode(row).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        assertTrue("not on $side", exists(hasText(side) and isSelected()))
        assertTrue("the filter wasn't put back to All", exists(hasContentDescription("All, ", substring = true) and isSelected()))
    }

    /** Stored, and its facts asked of TMDB (the fake): from here nothing more is looked up. */
    private fun settled(show: Int? = null, movie: Int? = null): Int = runBlocking {
        show?.let { id -> container.library.showSummary(id).first { it != null } }
        movie?.let { id -> container.library.movieSummary(id).first { it != null } }
        compose.waitForIdle()
        fixture.tmdb.lookups.get()
    }

    @Test
    fun aShowAddedFromItsCardIsRevealedAndBackReturnsToTheCard() {
        searchPicked(SearchPick(TitleType.Tv, GLASSWATER, "Glasswater Bay"))
        waitFor(hasText("TV series · 2024 · 1 season"))
        compose.onNode(hasText("+ Collection") and hasClickAction()).performClick()

        assertRevealed("Glasswater Bay", "Shows", "Added Glasswater Bay to your collection")
        compose.waitUntil(5_000) { runBlocking { container.settings.collectionView.first().showFilter } == "All" }
        val lookups = settled(show = GLASSWATER)
        val addedAt = runBlocking { container.library.shows.first().single { it.tmdbId == GLASSWATER }.addedAt }

        back()
        waitFor(hasText("TV series · 2024 · 1 season"))
        assertTrue(exists(hasText("In collection ›")))
        assertEquals("looked up again", lookups, fixture.tmdb.lookups.get())

        // There already: "In collection ›" goes to it, adding nothing.
        compose.onNode(hasText("In collection ›") and hasClickAction()).performClick()
        assertRevealed("Glasswater Bay", "Shows", "Glasswater Bay, in your collection", added = false)
        assertEquals(addedAt, runBlocking { container.library.shows.first().single { it.tmdbId == GLASSWATER }.addedAt })
        back()
        waitFor(hasText("TV series · 2024 · 1 season"))
    }

    @Test
    fun aShowAddedFromASuggestionIsRevealedAndBackReturnsToTheSuggestions() {
        search("quiet orb")
        val add = hasText("+ Collection") and hasClickAction()
        waitFor(hasText("The Quiet Orbit") and hasClickAction())
        compose.onNode(add).performClick()

        assertRevealed("The Quiet Orbit", "Shows", "Added The Quiet Orbit to your collection")
        back()
        waitFor(hasText("In collection ›"))
        assertTrue(exists(hasText("The Quiet Orbit") and hasClickAction()))
    }

    @Test
    fun aMovieAddedFromItsCardIsRevealedOnTheMoviesSide() {
        searchPicked(SearchPick(TitleType.Movie, LANTERN, "Lantern Field"))
        waitFor(hasText("Movie · 2023 · 1 h 41 min"))
        compose.onNode(hasText("+ Collection") and hasClickAction()).performClick()

        assertRevealed("Lantern Field", "Movies", "Added Lantern Field to your collection")
        compose.waitUntil(5_000) { runBlocking { container.settings.collectionView.first() }.let { it.side == "Movies" && it.movieFilter == "All" } }
        val lookups = settled(movie = LANTERN)
        back()
        waitFor(hasText("Movie · 2023 · 1 h 41 min"))
        assertTrue(exists(hasText("In collection ›")))
        assertEquals("looked up again", lookups, fixture.tmdb.lookups.get())
    }

    @Test
    fun aMovieAddedFromASuggestionIsRevealedOnTheMoviesSide() {
        search("copper val")
        waitFor(hasText("Copper Valley") and hasClickAction())
        compose.onNode(hasText("+ Collection") and hasClickAction()).performClick()

        assertRevealed("Copper Valley", "Movies", "Added Copper Valley to your collection")
        back()
        waitFor(hasText("In collection ›"))
    }
}
