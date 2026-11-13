package io.skjaere.usenet.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import io.skjaere.usenet.app.ui.FormFactor
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.test.assertEquals

/** Main's tabs, and a build's extra ones merged in (the release build has none: ReleaseExtrasTest). */
class TvTabsTest {
    @Test
    fun `a phone keeps its three tabs, in their order`() {
        val tabs = tabsFor(FormFactor.Phone, extras = null)
        assertEquals(listOf("Home", "Collection", "Search"), tabs.map { it.label })
        assertEquals(listOf(HomeRoute::class, CollectionRoute::class, SearchRoute::class), tabs.map { it.type })
        assertEquals(listOf(HomeRoute, CollectionRoute, SearchRoute()), tabs.map { it.route })
        assertEquals(emptyList(), tabs.filter { it.pinned })
    }

    /** A TV's side rail: Search first, as TVs have it; Settings, a tab of its own there, pinned at the bottom. */
    @Test
    fun `a TV's rail has Search first and Settings pinned at the bottom`() {
        val tabs = tabsFor(FormFactor.Tv, extras = null)
        assertEquals(listOf("Search", "Home", "Collection", "Settings"), tabs.map { it.label })
        assertEquals(listOf(SearchRoute::class, HomeRoute::class, CollectionRoute::class, SettingsRoute::class), tabs.map { it.type })
        assertEquals(listOf(SearchRoute(), HomeRoute, CollectionRoute, SettingsRoute), tabs.map { it.route })
        assertEquals(listOf("Settings"), tabs.filter { it.pinned }.map { it.label })
    }

    private object ExtraRoute
    private object OtherRoute

    private class Extras(private val after: Map<String, KClass<*>>) : AppExtras {
        override fun tabs(formFactor: FormFactor) = after.map { (label, type) ->
            ExtraTab(Tab(label, Icons.Filled.Star, if (label == "Extra") ExtraRoute else OtherRoute, if (label == "Extra") ExtraRoute::class else OtherRoute::class), after = type)
        }

        override fun NavGraphBuilder.screens(nav: NavController, container: AppContainer, formFactor: FormFactor) = Unit
    }

    @Test
    fun `a build's extra tabs go after the tab they name, Settings staying at the rail's bottom`() {
        val extras = Extras(mapOf("Extra" to HomeRoute::class))
        assertEquals(listOf("Home", "Extra", "Collection", "Search"), tabsFor(FormFactor.Phone, extras).map { it.label })
        assertEquals(listOf("Search", "Home", "Extra", "Collection", "Settings"), tabsFor(FormFactor.Tv, extras).map { it.label })
        assertEquals(listOf("Settings"), tabsFor(FormFactor.Tv, extras).filter { it.pinned }.map { it.label })
        // After a tab the form factor hasn't (a phone's Settings is a page): last of the tabs, still above a pinned one.
        val two = Extras(mapOf("Extra" to HomeRoute::class, "Other" to SettingsRoute::class))
        assertEquals(listOf("Home", "Extra", "Collection", "Search", "Other"), tabsFor(FormFactor.Phone, two).map { it.label })
        assertEquals(listOf("Search", "Home", "Extra", "Collection", "Settings", "Other"), tabsFor(FormFactor.Tv, two).map { it.label })
    }
}
