package io.skjaere.usenet.app.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import io.skjaere.usenet.app.SearchRoute
import io.skjaere.usenet.app.Tab
import io.skjaere.usenet.app.isTab
import io.skjaere.usenet.app.play.PlayMiniBar
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.switchTab
import io.skjaere.usenet.app.ui.PivotBringIntoViewSpec
import io.skjaere.usenet.app.ui.TvSafeArea
import io.skjaere.usenet.app.ui.colors
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A TV's frame around [content] (the NavHost): the rail on the [tabs]' own screens (hidden on the
 * pages opened from them, as a phone hides its bottom bar), everything inside the TV's safe area,
 * the phone's screens in a centred column, and the mini bar at the bottom of the content. Lists
 * scroll the focused row to a fixed line (the pivot), whatever the device declares. The remote's
 * search key ([searchKeys]) opens a new search, its field focused, as OK on the rail's Search would
 * open the tab, and asks for voice there ([onSearchKey]). Only the user's moves open the rail: when the
 * screen changes under the focus, the focus goes back into the content.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvShell(
    nav: NavController,
    tabs: List<Tab>,
    playback: PlaybackCoordinator,
    searchKeys: Flow<Unit> = emptyFlow(),
    onSearchKey: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val entry by nav.currentBackStackEntryAsState()
    val current = tabs.indexOfFirst { entry?.destination.isTab(it) }
    val onTab = current >= 0
    val rail = remember(tabs) { RailFocus(tabs) }
    SideEffect { if (onTab) rail.selected = current }
    val scope = rememberCoroutineScope()
    // The last OK's wait for its tab's screen: a later OK's replaces it.
    val entering = remember { arrayOfNulls<Job>(1) }
    // The TV's safe area, or the system bars where they reach further (a phone showing the TV's layouts).
    // Not the keyboard's: the screens with fields make room for it themselves, as on a phone.
    val safe = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        .union(WindowInsets(TvSafeArea.horizontal, TvSafeArea.vertical, TvSafeArea.horizontal, TvSafeArea.vertical))
    // The content's whole width beside the rail, for the screens made for a TV's width.
    var column by remember { mutableStateOf(Dp.Unspecified) }
    val density = LocalDensity.current

    /**
     * OK on [tab]'s item: the tab, and the focus into it. Its screen takes the focus as it opens (its
     * first focus may now); else, once it is up, the content's element last focused.
     */
    fun open(tab: Tab, restore: Boolean = true) {
        nav.switchTab(tab.route, restore)
        rail.entering = true
        entering[0]?.cancel()
        entering[0] = scope.launch {
            val opened = nav.currentBackStackEntry ?: return@launch
            opened.lifecycle.currentStateFlow.first { it == Lifecycle.State.RESUMED || it == Lifecycle.State.DESTROYED }
            withFrameNanos {}
            // Not when Back from the rail closed the tab meanwhile: the focus stays on the rail then.
            if (rail.hasFocus && nav.currentBackStackEntry?.id == opened.id) rail.focusContent()
            rail.entering = false
        }
    }

    // Whenever the screen in the content changes (a page opens or closes, the tab's own screen changes, the focused
    // element goes, or a screen says what it shows changed), once the new screen is up and has had its own first focus:
    // the focus into the content if nothing there has it and the user isn't on the rail. The rail opens only by the
    // user's moves; Android would give the window's default focus to it (the first focusable, top left).
    LaunchedEffect(entry?.id) { if (entry != null) rail.contentChanged() }
    LaunchedEffect(rail) {
        rail.changed.collectLatest {
            val shown = nav.currentBackStackEntry
            if (shown != null) {
                shown.lifecycle.currentStateFlow.first { it == Lifecycle.State.RESUMED || it == Lifecycle.State.DESTROYED }
                // A frame for the screen to show, another for its own first focus.
                withFrameNanos {}
                withFrameNanos {}
                val userOnRail = rail.hasFocus && rail.held
                if (!rail.contentHasFocus && !userOnRail && nav.currentBackStackEntry?.id == shown.id) rail.settleContent()
            }
            // Only once done: a later change cancelling this one is still to look.
            rail.settlePending = false
        }
    }

    LaunchedEffect(searchKeys) {
        searchKeys.collect {
            // Not before the NavHost has its graph (a key on a cold start).
            nav.currentBackStackEntryFlow.first()
            tabs.firstOrNull { it.type == SearchRoute::class }?.let {
                onSearchKey()
                open(it, restore = false)
            }
        }
    }

    // The content colour a phone's Scaffold gives its screens: the text's. The focus and press layers of
    // rows and buttons take theirs from it, and Material's default, black, can't be seen on the dark cards.
    CompositionLocalProvider(LocalRailFocus provides rail, LocalBringIntoViewSpec provides Pivot, LocalContentColor provides colors.text, LocalTvColumnWidth provides column) {
        Box(Modifier.fillMaxSize().background(colors.background)) {
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(safe)
                    .padding(start = if (onTab) TvSafeArea.contentStart - TvSafeArea.horizontal else 0.dp)
                    .onFocusChanged {
                        // Lost (the focused element gone, or the user's move to the rail, which stays): the shell looks
                        // once things have settled.
                        if (rail.contentHasFocus && !it.hasFocus) rail.contentChanged()
                        rail.contentHasFocus = it.hasFocus
                        if (it.hasFocus) rail.contentHadFocus = true
                    }
                    .focusRequester(rail.content).focusRestorer().focusGroup(),
            ) {
                Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { column = with(density) { it.width.toDp() } }, contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = TvSafeArea.sharedMax).fillMaxSize()) { content() }
                }
                PlayMiniBar(playback, windowInsets = WindowInsets(0))
            }
            if (onTab) {
                // The rail widens over the content, dimmed behind it.
                if (rail.hasFocus) Box(Modifier.fillMaxSize().background(SCRIM))
                TvRail(tabs, rail, safe, onOpen = ::open)
            }
        }
    }
}

/**
 * The width of the TV shell's content beside the rail. The phone's screens take a centred column of it
 * ([TvSafeArea.sharedMax]); a screen laid out for a TV's width (Search's two panes) takes it all. Unspecified outside
 * the shell, and until it is laid out.
 */
val LocalTvColumnWidth = compositionLocalOf { Dp.Unspecified }

/**
 * A screen laid out for a TV's width takes the shell's whole content column ([LocalTvColumnWidth]: 864 dp on a page,
 * 784 dp beside the rail), not the phone screens' centred [TvSafeArea.sharedMax]; until it is laid out, the width it has.
 */
fun Modifier.tvFullWidth(): Modifier = composed {
    val column = LocalTvColumnWidth.current
    if (column.isSpecified) requiredWidth(column) else fillMaxWidth()
}

private val Pivot = PivotBringIntoViewSpec()
private val SCRIM = Color.Black.copy(alpha = 0.5f)
