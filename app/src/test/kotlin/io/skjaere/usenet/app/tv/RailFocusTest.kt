package io.skjaere.usenet.app.tv

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import io.skjaere.usenet.app.CollectionRoute
import io.skjaere.usenet.app.HomeRoute
import io.skjaere.usenet.app.SettingsRoute
import io.skjaere.usenet.app.Tab
import io.skjaere.usenet.app.plugins.PluginTab1
import io.skjaere.usenet.app.plugins.PluginTab2
import io.skjaere.usenet.app.tabsFor
import io.skjaere.usenet.app.ui.FormFactor
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * L582: the TV rail's focus state is kept as its tabs change. The shell makes one [RailFocus] and gives it each new list
 * of tabs: the registry's first load, a plugin's label changed, a tab added or removed. What the rail knows of the focus
 * stays, and each tab's item keeps its focus requester, by the tab's type, so the current tab's item is still the way
 * in, wherever the new tabs put it.
 */
class RailFocusTest {
    private val sports = Tab("Sports", Icons.Filled.Star, PluginTab1, PluginTab1::class)
    private val kestrel = Tab("Kestrel", Icons.Filled.Star, PluginTab2, PluginTab2::class)

    /** The rail as the user left it: on it, holding it, OK just pressed, the content had the focus before. */
    private fun held(rail: RailFocus) {
        rail.hasFocus = true
        rail.held = true
        rail.entering = true
        rail.contentHadFocus = true
    }

    private fun assertKept(rail: RailFocus, what: String) {
        assertTrue(rail.hasFocus, "$what: hasFocus")
        assertTrue(rail.held, "$what: held")
        assertTrue(rail.entering, "$what: entering")
        assertTrue(rail.contentHadFocus, "$what: contentHadFocus")
    }

    @Test
    fun `a tab added, a label changed and a tab removed keep the rail's focus state and its items`() {
        val start = tabsFor(FormFactor.Tv, extras = null)
        val rail = RailFocus(start)
        held(rail)
        rail.select(start.single { it.type == CollectionRoute::class })
        val home = rail.item(start.single { it.type == HomeRoute::class })
        val collection = rail.wayIn

        val added = tabsFor(FormFactor.Tv, extras = null, plugins = listOf(sports))
        rail.update(added)
        assertKept(rail, "a tab added")
        assertEquals(added, rail.tabs)
        assertSame(home, rail.item(added.single { it.type == HomeRoute::class }), "Home's item kept by its type")
        assertSame(collection, rail.wayIn, "the current tab's item is still the way in, one place further down")
        assertEquals(added.indexOfFirst { it.type == CollectionRoute::class }, rail.selected)

        val renamed = tabsFor(FormFactor.Tv, extras = null, plugins = listOf(Tab("Matches", sports.icon, PluginTab1, PluginTab1::class), kestrel))
        val pluginItem = rail.item(sports)
        rail.update(renamed)
        assertKept(rail, "a label changed and a second tab added")
        assertSame(pluginItem, rail.item(renamed.single { it.type == PluginTab1::class }), "the plugin's item kept under its new label")
        assertSame(collection, rail.wayIn)

        val removed = tabsFor(FormFactor.Tv, extras = null, plugins = listOf(kestrel))
        rail.update(removed)
        assertKept(rail, "a tab removed")
        assertSame(collection, rail.wayIn)
        assertEquals(removed.indexOfFirst { it.type == CollectionRoute::class }, rail.selected)
        assertSame(home, rail.item(removed.single { it.type == HomeRoute::class }))
    }

    /** OK on a tab, and the rail's way to a tab by its type, follow the tab wherever the new list puts it. */
    @Test
    fun `the selected tab is kept by its type as tabs come before it`() {
        val start = tabsFor(FormFactor.Tv, extras = null)
        val rail = RailFocus(start)
        val settings = start.single { it.type == SettingsRoute::class }
        rail.select(settings)
        rail.update(tabsFor(FormFactor.Tv, extras = null, plugins = listOf(sports, kestrel)))
        assertEquals(rail.tabs.indexOf(settings), rail.selected)
        assertTrue(rail.isSelected(settings))
        assertSame(rail.item(settings), rail.wayIn)
    }
}
