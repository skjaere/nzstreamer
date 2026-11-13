package io.skjaere.usenet.app.discover

import io.skjaere.usenet.app.CollectionRoute
import io.skjaere.usenet.app.HomeRoute
import io.skjaere.usenet.app.SearchRoute
import io.skjaere.usenet.app.appExtras
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
}
