package app.nzstreamer.plugin.sportarr.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.nzstreamer.plugin.sportarr.Session
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.plugin.PluginNav
import kotlinx.coroutines.delay

/**
 * The tab's own page (L652): the setup until an address and a key are saved, then the list. As the list shows, it is read
 * when none is kept or the one kept is over 15 minutes old (L641); a Save from the setup has asked for a read already.
 */
@Composable
internal fun SportsTab(session: Session, nav: PluginNav) {
    val setUp by session.setUp.collectAsState(initial = null)
    when (setUp) {
        // The secrets not read yet: a moment, as the host's slot draws nothing while a plugin loads.
        null -> Unit
        false -> SetupPage(session, Words.Setup.TAB, Words.Setup.INTRO, onBack = null, onSaved = {})
        true -> {
            LaunchedEffect(session) { session.refresh.shown() }
            ListPage(session, nav)
        }
    }
}

/**
 * The list, a phone's or a TV's, over its model for as long as it shows: a row opens its page, the ⋮ and the refused
 * key's button the settings page, Refresh and Try again read the list now. The side and the days opened are kept with the
 * page, as strings (NzPlugin's rule), so they come back with Back from an event's page; the time moves on each minute
 * while the list shows, so rows that start move to Recent and Today turns over at midnight.
 */
@Composable
private fun ListPage(session: Session, nav: PluginNav) {
    val tv = LocalFormFactor.current == FormFactor.Tv
    var keptSide by rememberSaveable { mutableStateOf<String?>(null) }
    var keptExpanded by rememberSaveable { mutableStateOf("") }
    val model = remember(session, tv) { session.list(tv, keptSide?.let { ListModel.Restored(it, keptExpanded) }) }
    LaunchedEffect(model) { model.run() }
    LaunchedEffect(model) {
        while (true) {
            delay(MINUTE_MS)
            model.tick()
        }
    }
    val screen by model.screen.collectAsState()
    // Nothing until the first screen is built (a moment): the list's place in the page comes back with it whole.
    val shown = screen ?: return
    LaunchedEffect(shown) {
        val kept = model.kept()
        keptSide = kept.side
        keptExpanded = kept.expanded
    }
    val actions = remember(session, nav) {
        ListActions(
            open = { row -> nav.open(row.page, row.key) },
            settings = { nav.openSettings() },
            refresh = { session.refresh.refresh() },
        )
    }
    if (tv) TvSportsList(shown, model, actions) else SportsList(shown, model, actions)
}

/** How often the list's time moves on while it shows. */
private const val MINUTE_MS = 60_000L
