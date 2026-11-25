package app.nzstreamer.plugin.sportarr.ui

import androidx.compose.runtime.Composable
import io.skjaere.usenet.app.play.OkGuard

/**
 * A fight card's page (L644, L652): its parts in card order, as Sportarr names them, the main card or the main show its
 * headline, drawn as a page of parts ([PartsPage]) with no day or time on a part, as they air together. Before the card
 * starts its parts show but can't be played. On a TV, a weekend's two panes, the parts on the right.
 */
@Composable
internal fun CardPage(screen: EventScreen, tv: Boolean, actions: PageActions, okGuard: OkGuard, bar: @Composable () -> Unit) {
    PartsPage(screen, tv, actions, okGuard, bar)
}
