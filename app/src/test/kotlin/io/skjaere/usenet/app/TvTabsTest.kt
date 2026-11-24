package io.skjaere.usenet.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import io.skjaere.usenet.app.plugins.PluginTab1
import io.skjaere.usenet.app.plugins.PluginTab2
import io.skjaere.usenet.app.ui.FormFactor
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.test.assertEquals

/** Main's tabs, a build's extra ones merged in (the release build has none: ReleaseExtrasTest), and the plugins' (L618). */
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

    /** The installed plugins' tabs, in slot order, as AppNav makes them from the registry's flow. */
    private val sports = Tab("Sports", Icons.Filled.Star, PluginTab1, PluginTab1::class)
    private val kestrel = Tab("Kestrel", Icons.Filled.Star, PluginTab2, PluginTab2::class)

    /** L618, W160: after Home in a build without an extra after it, phone and TV; two in slot order. */
    @Test
    fun `plugin tabs go after Home in a build without an extra there`() {
        assertEquals(listOf("Home", "Sports", "Collection", "Search"), tabsFor(FormFactor.Phone, extras = null, plugins = listOf(sports)).map { it.label })
        assertEquals(
            listOf("Home", "Sports", "Kestrel", "Collection", "Search"),
            tabsFor(FormFactor.Phone, extras = null, plugins = listOf(sports, kestrel)).map { it.label },
        )
        assertEquals(listOf("Search", "Home", "Sports", "Collection", "Settings"), tabsFor(FormFactor.Tv, extras = null, plugins = listOf(sports)).map { it.label })
        val two = tabsFor(FormFactor.Tv, extras = null, plugins = listOf(sports, kestrel))
        assertEquals(listOf("Search", "Home", "Sports", "Kestrel", "Collection", "Settings"), two.map { it.label })
        assertEquals(listOf("Settings"), two.filter { it.pinned }.map { it.label })
        assertEquals(listOf(PluginTab1::class, PluginTab2::class), two.filter { it.label in setOf("Sports", "Kestrel") }.map { it.type })
    }

    /** L618: after the build's extra placed after Home (Discover's place); one placed elsewhere doesn't move them. */
    @Test
    fun `plugin tabs go after the build's extra tab placed after Home, else after Home`() {
        val extras = Extras(mapOf("Extra" to HomeRoute::class))
        assertEquals(listOf("Home", "Extra", "Sports", "Collection", "Search"), tabsFor(FormFactor.Phone, extras, listOf(sports)).map { it.label })
        assertEquals(
            listOf("Search", "Home", "Extra", "Sports", "Kestrel", "Collection", "Settings"),
            tabsFor(FormFactor.Tv, extras, listOf(sports, kestrel)).map { it.label },
        )
        val elsewhere = Extras(mapOf("Other" to CollectionRoute::class))
        assertEquals(listOf("Home", "Sports", "Collection", "Other", "Search"), tabsFor(FormFactor.Phone, elsewhere, listOf(sports)).map { it.label })
        assertEquals(listOf("Search", "Home", "Sports", "Kestrel", "Collection", "Other", "Settings"), tabsFor(FormFactor.Tv, elsewhere, listOf(sports, kestrel)).map { it.label })
        // Where Settings › Plugins says a new one goes: the same tab.
        assertEquals("Extra", pluginTabsFollow(FormFactor.Phone, extras))
        assertEquals("Home", pluginTabsFollow(FormFactor.Tv, elsewhere))
    }
}
