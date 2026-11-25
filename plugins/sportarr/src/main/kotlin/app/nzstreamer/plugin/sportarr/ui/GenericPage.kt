package app.nzstreamer.plugin.sportarr.ui

import androidx.compose.runtime.Composable
import io.skjaere.usenet.app.play.OkGuard

/**
 * The page of a sport the plugin has no rules for (L651, L652): the game's page ([GamePage]) with Choose release in Play's
 * place as its primary button, Resume while it is played (the release played before, which the host keeps), the line
 * that the user picks the release, and its own footnote, all of which its [screen] holds. Never Play: the plugin can't
 * tell this sport's releases apart for sure. On a TV the focus starts on its primary button.
 */
@Composable
internal fun GenericPage(screen: EventScreen, tv: Boolean, actions: PageActions, okGuard: OkGuard, bar: @Composable () -> Unit) {
    GamePage(screen, tv, actions, okGuard, bar)
}
