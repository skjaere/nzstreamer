package io.skjaere.usenet.app

import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.plugins.PluginNavImpl
import io.skjaere.usenet.app.search.SearchRequest
import io.skjaere.usenet.data.SearchPick
import io.skjaere.usenet.data.TitleType
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * L629: the Search tab's route carries a plugin's note to the search it runs, as openSearch and the no-release sheet's
 * Search open it: the note shows above that search's results while its words show, and new words clear it (the view
 * model's part, SearchViewModelTest). A route is kept in saved state, so the note is one of its saved fields. The words
 * are invented.
 */
class SearchRouteTest {
    private val note = "Indexer results can show scores."

    @Test
    fun `the note survives the route and reaches the search it runs`() {
        val route = SearchRoute(query = "Lantern Bay Copper Crown", note = note)
        val saved = Json.encodeToString(SearchRoute.serializer(), route)
        assertEquals(route, Json.decodeFromString(SearchRoute.serializer(), saved), "kept as the route is saved and restored")
        assertEquals(SearchRequest("Lantern Bay Copper Crown", note = note), route.request())
        // The tab's own route, and every search opened elsewhere, carry none.
        assertNull(SearchRoute().note)
        assertNull(SearchRoute("Lantern Bay Copper Crown").request()?.note)
        assertNull(searchRoute("Copper Crown", SearchPick(TitleType.Movie, 900011, "Copper Crown")).note)
    }

    @Test
    fun `the no-release sheet's Search carries its note`() {
        val sheet = PlayState.NoRelease(
            "Quarrydown FC vs. Ellerholt United", "KCL", listOf("Indexer A" to "6 results, none for this game"), null,
            nameQuery = "Quarrydown Ellerholt", hidden = emptyList(), searchNote = note,
        )
        assertEquals(SearchRoute(query = "Quarrydown Ellerholt", note = note), sheet.searchRoute())
        assertEquals(note, sheet.searchRoute()?.request()?.note)
        assertNull(sheet.copy(nameQuery = null).searchRoute(), "no words: no Search")
        assertNull(sheet.copy(searchNote = null).searchRoute()?.note)
    }

    /** A plugin's openSearch: its words searched with its note; blank words open Search to type in, with no note. */
    @Test
    fun `a plugin's openSearch opens Search with its words and note`() {
        assertEquals(SearchRoute(query = "Lantern Bay Copper Crown", note = note), PluginNavImpl.searchRoute(" Lantern Bay Copper Crown ", note))
        assertEquals(SearchRoute(query = "Lantern Bay Copper Crown"), PluginNavImpl.searchRoute("Lantern Bay Copper Crown", null))
        assertEquals(SearchRoute(query = "Lantern Bay Copper Crown"), PluginNavImpl.searchRoute("Lantern Bay Copper Crown", "  "))
        assertEquals(SearchRoute(), PluginNavImpl.searchRoute("   ", note))
    }
}
