package io.skjaere.usenet.app.tv

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import io.skjaere.usenet.app.HomeRoute

/**
 * Back on a TV tab's own screen ([screen]), so Back always ends at the TV's home screen: from the
 * content to the rail's item for this tab; from the rail, to Home's own screen (this tab closed, as
 * a phone's Back closes it) with the focus kept on the rail; from Home's item, nothing here (the app
 * finishes). Composed first in the tab's destination, so the screen's own Back (a dialog, a field,
 * select mode) and the Collection's Back to Search go before it. A phone's tabs have no rail: nothing.
 */
@Composable
fun TopLevelBack(nav: NavController, screen: NavBackStackEntry) {
    val rail = LocalRailFocus.current ?: return
    val home = screen.destination.hasRoute(HomeRoute::class)
    // Only while this screen is the current one, but already as it fades in (a page closed, a tab
    // opened): a Back then would be taken here and lost if it waited for the screen to be resumed. While
    // a page opened from it fades in, the page's own Back goes instead. Known from the screen's first frame:
    // currentBackStackEntryAsState starts at null, a frame or two before the entry comes, and a Back meanwhile
    // would go past this to the NavHost's own, which pops the tab to Home (#267).
    val current by remember(nav) { nav.currentBackStackEntryFlow }.collectAsState(nav.currentBackStackEntry)
    BackHandler(enabled = current?.id == screen.id && !(rail.hasFocus && home)) {
        if (nav.currentBackStackEntry?.id != screen.id) return@BackHandler
        if (!rail.hasFocus) {
            rail.focusSelected()
        } else {
            // Home's own screen, as a phone's Back from a tab: not the page Home was left from
            // (kept for OK on Home), which has no rail to keep the focus on. An OK on the rail just
            // before no longer takes the focus into the content.
            rail.entering = false
            nav.popBackStack(nav.graph.findStartDestination().id, inclusive = false)
            rail.focus(HomeRoute::class)
        }
    }
}
