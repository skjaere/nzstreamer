package io.skjaere.usenet.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import io.skjaere.usenet.app.casting.CastPlaying
import io.skjaere.usenet.app.link.CastIntroFrame
import io.skjaere.usenet.app.link.RemotePlaying
import io.skjaere.usenet.app.link.RemoteSaidBar
import io.skjaere.usenet.app.link.TvStart
import io.skjaere.usenet.app.play.CastMissingPartsPrompt
import io.skjaere.usenet.app.play.PlayMiniBar
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.SlowStreamBanner
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.colors

/**
 * A phone's frame around [content] (the NavHost): the bottom bar on the [tabs]' own screens, and the mini bar above it,
 * a start on a TV's ([tvStart]) in its place while there is one, else the Playing on bar of a play this phone follows on
 * a TV or a Cast device ([playing], L91), which opens the remote ([onRemote]), on every screen but the remote; above
 * them, a cast's missing-parts prompt ([cast], W37: the remote has its own, over its picture), or its slow-stream banner
 * after it (L134); and over the page, what the phone says of its plays there as they end (item 27, L93). Over it all,
 * bars included, once, the introduction to the Cast button (L123, [CastIntroFrame]).
 */
@Composable
fun PhoneShell(
    nav: NavController,
    tabs: List<Tab>,
    playback: PlaybackCoordinator,
    tvStart: TvStart? = null,
    playing: RemotePlaying? = null,
    cast: CastPlaying? = null,
    onRemote: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val onTab = tabs.any { destination.isTab(it) }
    CastIntroFrame(home = destination?.hasRoute(HomeRoute::class) == true, playback, tvStart, playing, cast) {
        Scaffold(
            containerColor = colors.background,
            bottomBar = {
                Column {
                    // The tab bar keeps clear of the system navigation bar itself; elsewhere the mini bar must.
                    // Not over the remote, which shows the play itself, and its missing parts over its picture.
                    val remote = destination?.hasRoute(TvRemoteRoute::class) == true
                    val shown = playing.takeUnless { remote }
                    if (!remote) cast?.let { CastPromptAbove(it) }
                    PlayMiniBar(playback, windowInsets = if (onTab) WindowInsets(0) else WindowInsets.navigationBars, tv = tvStart, playing = shown, onRemote = onRemote)
                    if (onTab) NavigationBar(containerColor = colors.surface) {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = destination.isTab(tab),
                                onClick = { nav.switchTab(tab.route) },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(tab.label, style = AppType.small) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = colors.onAccent, indicatorColor = colors.accent,
                                    selectedTextColor = colors.accent, unselectedIconColor = colors.muted, unselectedTextColor = colors.muted,
                                ),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().background(colors.background).padding(padding)) {
                content()
                // Where a play on a TV or a Cast device stopped, wherever the phone is (item 27, L93): over the page, on
                // what sits at the bottom.
                playing?.let { RemoteSaidBar(it, Modifier.align(Alignment.BottomCenter), screen = entry?.id) }
            }
        }
    }
}

/**
 * A cast's missing-parts prompt in the bottom stack, above the bar (W37; CsPhoneMissingParts 2), or while it doesn't ask,
 * the cast's slow-stream banner there (L134; CsPhoneSlowCast 2): one at a time, the prompt first. So the page ends above
 * it: 16 dp from the sides and 8 dp from what is round it, on the chip's colour; across the screen while the prompt asks,
 * and the banner always. Never more than [PROMPT_SHARE] of the window's height: on a short window (a phone turned, a large
 * font) its words scroll and its buttons stay whole, and the page, the bar and the tabs keep their room.
 */
@Composable
private fun CastPromptAbove(cast: CastPlaying) {
    val prompt by cast.missing.collectAsStateWithLifecycle()
    val slow by cast.slow.collectAsStateWithLifecycle()
    val asking = prompt
    val banner = slow
    if (asking == null && banner == null) return
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val within = Modifier.heightIn(max = maxHeight * PROMPT_SHARE)
        if (asking != null) {
            CastMissingPartsPrompt(
                asking, onKeepWatching = cast::keepWatching, onTryAnother = cast::tryAnother,
                modifier = (if (asking.tryAnother) Modifier.fillMaxWidth() else Modifier).then(within), background = colors.chip, withinHeight = true,
            )
        } else if (banner != null) {
            SlowStreamBanner(
                banner.shown, compact = false, onTrySmaller = cast::trySmaller.takeIf { banner.trySmaller }, modifier = Modifier.fillMaxWidth().then(within),
                background = colors.chip,
            )
        }
    }
}

/** The share of the window's height the cast's prompt or banner above the bar may take, at most. */
private const val PROMPT_SHARE = 1f / 3
