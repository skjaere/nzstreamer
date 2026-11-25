package app.nzstreamer.plugin.sportarr.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
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
 * page, as strings (NzPlugin's rule), and come back only with Back from a page or the settings the list opened, or its
 * activity made again ([ListPlace]): a tab switch keeps the tab's state too, and the side opens on Recent each time the
 * tab opens (L645). The time moves on each minute while the list shows, so rows that start move to Recent and Today
 * turns over at midnight.
 */
@Composable
private fun ListPage(session: Session, nav: PluginNav) {
    val tv = LocalFormFactor.current == FormFactor.Tv
    var keptSide by rememberSaveable { mutableStateOf<String?>(null) }
    var keptExpanded by rememberSaveable { mutableStateOf("") }
    val model = remember(session, tv) { session.list(tv, session.listPlace.take(keptSide?.let { ListModel.Restored(it, keptExpanded) })) }
    val activity = LocalContext.current.activity()
    // Made again for a rotation, or a phone's play turning it to landscape: the list comes back where it was.
    DisposableEffect(session) { onDispose { if (activity?.isChangingConfigurations == true) session.listPlace.keep() } }
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
            open = { row ->
                session.listPlace.keep()
                nav.open(row.page, row.key)
            },
            settings = {
                session.listPlace.keep()
                nav.openSettings()
            },
            refresh = { session.refresh.refresh() },
        )
    }
    if (tv) TvSportsList(shown, model, actions) else SportsList(shown, model, actions)
}

/**
 * Whether the list comes back where it was left ([take]): only once it has left for a page or the settings it opened, or
 * for its activity being made again ([keep]), and once. Used on the main thread alone.
 */
internal class ListPlace {
    private var kept = false

    fun keep() {
        kept = true
    }

    /** [saved], the list's place as its page kept it, where it is to come back; null for the tab opening afresh. */
    fun take(saved: ListModel.Restored?): ListModel.Restored? = saved.takeIf { kept }.also { kept = false }
}

/** The activity a composition's [Context] belongs to, through its wrappers. */
private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** How often the list's time moves on while it shows. */
private const val MINUTE_MS = 60_000L
