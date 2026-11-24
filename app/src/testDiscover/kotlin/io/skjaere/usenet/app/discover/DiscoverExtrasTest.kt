package io.skjaere.usenet.app.discover

import io.skjaere.usenet.app.CollectionRoute
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import io.skjaere.usenet.app.HomeRoute
import io.skjaere.usenet.app.SearchRoute
import io.skjaere.usenet.app.Tab
import io.skjaere.usenet.app.appExtras
import io.skjaere.usenet.app.plugins.PluginTab1
import io.skjaere.usenet.app.plugins.PluginTab2
import io.skjaere.usenet.app.pluginTabsFollow
import io.skjaere.usenet.app.tabsFor
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.discover.DISCOVER_CANARY
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** The debug, bench and beta builds carry Discover's extras, and the canaries the release guard looks for. */
class DiscoverExtrasTest {
    @Test
    fun `Discover's builds carry its extras`() {
        assertSame(DiscoverExtras, appExtras)
    }

    /** checkNoDiscover fails the release build on these, and its positive control looks for them in the debug build. */
    @Test
    fun `the canaries are the ones the release guard looks for`() {
        assertEquals("nzs.discover.canary.app", DiscoverExtras.canary)
        assertEquals("nzs.discover.canary.core", DISCOVER_CANARY)
    }

    /** W3: after Home in the phone's bar, as the mockup has it. */
    @Test
    fun `a phone's bar has Discover after Home`() {
        val tabs = tabsFor(FormFactor.Phone)
        assertEquals(listOf("Home", DISCOVER, "Collection", "Search"), tabs.map { it.label })
        assertEquals(listOf(HomeRoute::class, DiscoverRoute::class, CollectionRoute::class, SearchRoute::class), tabs.map { it.type })
        assertEquals(DiscoverRoute, tabs[1].route)
    }

    /** W3: the rail's Search, Home, then Discover, Collection, and Settings at the bottom. */
    @Test
    fun `a TV's rail has Discover after Home, Settings still at its bottom`() {
        val tabs = tabsFor(FormFactor.Tv)
        assertEquals(listOf("Search", "Home", DISCOVER, "Collection", "Settings"), tabs.map { it.label })
        assertEquals(listOf("Settings"), tabs.filter { it.pinned }.map { it.label })
        assertEquals(DiscoverRoute::class, tabs[2].type)
    }

    private val sports = Tab("Sports", Icons.Filled.Star, PluginTab1, PluginTab1::class)
    private val kestrel = Tab("Kestrel", Icons.Filled.Star, PluginTab2, PluginTab2::class)

    /** W160, L618: a phone's Home, Discover, the plugins' tabs in slot order, Collection, Search. */
    @Test
    fun `a phone's bar has the plugins' tabs after Discover`() {
        assertEquals(listOf("Home", DISCOVER, "Sports", "Collection", "Search"), tabsFor(FormFactor.Phone, plugins = listOf(sports)).map { it.label })
        val two = tabsFor(FormFactor.Phone, plugins = listOf(sports, kestrel))
        assertEquals(listOf("Home", DISCOVER, "Sports", "Kestrel", "Collection", "Search"), two.map { it.label })
        assertEquals(listOf(HomeRoute::class, DiscoverRoute::class, PluginTab1::class, PluginTab2::class, CollectionRoute::class, SearchRoute::class), two.map { it.type })
        assertEquals(DISCOVER, pluginTabsFollow(FormFactor.Phone))
    }

    /** W160, L618: a TV's rail Search, Home, Discover, the plugins', Collection, and Settings at its bottom. */
    @Test
    fun `a TV's rail has the plugins' tabs after Discover, Settings still at its bottom`() {
        assertEquals(listOf("Search", "Home", DISCOVER, "Sports", "Collection", "Settings"), tabsFor(FormFactor.Tv, plugins = listOf(sports)).map { it.label })
        val two = tabsFor(FormFactor.Tv, plugins = listOf(sports, kestrel))
        assertEquals(listOf("Search", "Home", DISCOVER, "Sports", "Kestrel", "Collection", "Settings"), two.map { it.label })
        assertEquals(listOf("Settings"), two.filter { it.pinned }.map { it.label })
        assertEquals(DISCOVER, pluginTabsFollow(FormFactor.Tv))
    }
}
