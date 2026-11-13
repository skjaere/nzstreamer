package io.skjaere.usenet.app

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import io.skjaere.usenet.app.tv.LocalRailFocus

/**
 * Where "+ Collection" on Search sends the Collection tab: the title's [key] (a TitleKey's value,
 * "show:123" or "movie:456") and [name]. [added] false: "In collection ›", for one there already.
 */
data class CollectionJump(val key: String, val name: String, val added: Boolean = true)

/**
 * The Collection tab's jump to a title just added on Search ([jump], consumed once by [jumpDone], or
 * dropped as its list is left before the reveal is done), and whether Back there returns to Search
 * ([backToSearch]): armed by the jump, disarmed by Back or by leaving the tab. Not a route argument:
 * the tab's saved route would bring it back, and a later tap on the tab would jump again.
 */
@Stable
class CollectionJumpState(jump: CollectionJump? = null, backToSearch: Boolean = false) {
    var jump by mutableStateOf(jump)
        private set
    var backToSearch by mutableStateOf(backToSearch)
        private set

    fun added(target: CollectionJump) {
        jump = target
        backToSearch = true
    }

    fun jumpDone() {
        jump = null
    }

    fun backHandled() {
        backToSearch = false
    }

    /**
     * Back to Search stays armed only while the user stays in the Collection tab: its list, or a Show or
     * Movie page opened from it. The jump only while its list is shown ([onList]): left before the reveal
     * is done (another tab, Back, a page opened from it), it isn't replayed on a later visit.
     */
    fun destinationChanged(inCollectionTab: Boolean, onList: Boolean) {
        if (!inCollectionTab) backToSearch = false
        if (!onList) jump = null
    }

    companion object {
        val Saver: Saver<CollectionJumpState, Any> = listSaver(
            save = { listOf(it.backToSearch, it.jump?.key, it.jump?.name, it.jump?.added) },
            restore = { saved ->
                val key = saved[1] as String?
                val name = saved[2] as String?
                CollectionJumpState(if (key != null && name != null) CollectionJump(key, name, saved[3] as Boolean) else null, saved[0] as Boolean)
            },
        )
    }
}

/** AppNav's jump state; leaving the Collection tab disarms its Back to Search, leaving its list drops the jump. */
@Composable
fun rememberCollectionJumps(nav: NavController): CollectionJumpState {
    val jumps = rememberSaveable(saver = CollectionJumpState.Saver) { CollectionJumpState() }
    val entry by nav.currentBackStackEntryAsState()
    LaunchedEffect(entry) {
        val destination = entry?.destination ?: return@LaunchedEffect
        // A Show or Movie page counts only with the Collection list under it on this stack (a tab's stack
        // is [Home, its screens]); getBackStackEntry throws when the route isn't there.
        val collectionBelow = runCatching { nav.getBackStackEntry<CollectionRoute>() }.isSuccess
        val page = destination.hasRoute(ShowRoute::class) || destination.hasRoute(MovieRoute::class)
        val list = destination.hasRoute(CollectionRoute::class)
        jumps.destinationChanged(inCollectionTab = list || (page && collectionBelow), onList = list)
    }
    return jumps
}

/**
 * "+ Collection" on Search: the Collection tab, started fresh (the user asked to see the collection,
 * not a page left open there), to reveal [target]. Search's history is saved as the tab is left, so
 * [backToSearch] brings it back as it was.
 */
fun NavController.jumpToCollection(jumps: CollectionJumpState, target: CollectionJump) {
    jumps.added(target)
    switchTab(CollectionRoute, restore = false)
}

/** Back after a jump: the Search tab as it was left, its results and its ViewModel. */
fun NavController.backToSearch(jumps: CollectionJumpState) {
    jumps.backHandled()
    switchTab(SearchRoute())
}

/**
 * In the Collection tab's destination ([screen]): after a jump, Back returns to Search. Composed
 * before the screen, whose own Back handlers go first (the letter grid, the field, select mode, an
 * empty search). On a TV a Back as the tab fades in after the jump counts (TopLevelBack's rule, TVX-6),
 * where a phone's waits for the tab to be up; the user's move into the rail disarms it
 * ([CollectionJumpState.backHandled]), from wherever in the tab it comes: the grid, the mini bar under it.
 */
@Composable
fun BackToSearch(nav: NavController, jumps: CollectionJumpState, screen: NavBackStackEntry) {
    val rail = LocalRailFocus.current
    if (rail == null) {
        BackHandler(enabled = jumps.backToSearch) { screen.ifResumed { nav.backToSearch(jumps) } }
        return
    }
    // The rail held: the user's move, not the window's first focus put there as the tab fades in. Back from the rail
    // then goes where it always does.
    LaunchedEffect(rail, jumps) { snapshotFlow { rail.hasFocus && rail.held }.collect { if (it) jumps.backHandled() } }
    // Only while this screen is the current one: a page opened from it has its own Back. Known from the screen's first
    // frame: currentBackStackEntryAsState starts at null, a frame or two before the entry comes, and a Back meanwhile (the
    // jump's tab fading in) would go past this to the NavHost's own, which pops the tab to Home.
    val current by remember(nav) { nav.currentBackStackEntryFlow }.collectAsState(nav.currentBackStackEntry)
    BackHandler(enabled = jumps.backToSearch && current?.id == screen.id) {
        if (nav.currentBackStackEntry?.id == screen.id) nav.backToSearch(jumps)
    }
}
