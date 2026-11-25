package app.nzstreamer.plugin.sportarr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.skjaere.usenet.app.play.OkGuard
import io.skjaere.usenet.app.play.pageOkGuard
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.Footnote
import io.skjaere.usenet.app.ui.PaneMemory
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.paneElement

/**
 * A game's page (L652), the Movie page's look without poster or overview (L653), as for every event of one part: a team
 * game, a boxing matchup, a weekly show, and Generic's ([GenericPage]). The title; the league and when; not watched, what
 * is left with its bar, ✓ Watched, or when it starts; Play (Resume, Play again), Choose release and Search indexers, each
 * on a line of its own, or before the start the line saying they show once it has; the line kept after Play found
 * nothing; Mark watched or unwatched at the right; the footnote. NZStreamer's Cast button is in the phone's top bar.
 *
 * On a TV, two panes, as the Movie page's on a TV: the title, the lines and the buttons on the left, Mark watched and the
 * footnote on the right, Left and Right going back to where the focus was in each. The focus starts on the first button
 * (Play, Play again), or before the start on the line, which holds it; Back from Choose release puts it back there.
 */
@Composable
internal fun GamePage(screen: EventScreen, tv: Boolean, actions: PageActions, okGuard: OkGuard, bar: @Composable () -> Unit) {
    PageFocus(EVENT_SHOWN, screen.firstFocus, tv, actions.focus)
    if (tv) TvOnePart(screen, actions, okGuard) else PhoneOnePart(screen, actions, okGuard, bar)
}

@Composable
private fun PhoneOnePart(screen: EventScreen, actions: PageActions, okGuard: OkGuard, bar: @Composable () -> Unit) {
    val line = AppType.body.copy(fontSize = 14.sp)
    Column(Modifier.fillMaxSize().pageOkGuard(okGuard)) {
        bar()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PageTitle(screen.title, tv = false)
                    Text(screen.meta, style = line, color = colors.muted)
                    screen.status?.let { Status(it, line, 4.dp, colors.chip) }
                    screen.pick?.let { Text(it, style = line, color = colors.textSecondary) }
                    screen.buttons.forEachIndexed { index, button ->
                        Box(if (index == 0) Modifier.padding(top = 6.dp) else Modifier) { PhoneButton(button, actions) }
                    }
                    screen.note?.let { SmallLine(it) }
                    screen.kept?.let { SmallLine(it, live = true) }
                }
            }
            screen.mark?.let { mark -> item { PhoneMark(mark, actions) } }
            item { Footnote(screen.footnote) }
        }
    }
}

@Composable
private fun TvOnePart(screen: EventScreen, actions: PageActions, okGuard: OkGuard) {
    val panes = remember(actions.focus) { PaneMemory(actions.focus) }
    TvPanes(
        panes, okGuard,
        left = {
            TvHeading(screen)
            TvButtons(screen.buttons, panes, actions)
            screen.note?.let { HeldLine(it, panes) }
            screen.kept?.let { SmallLine(it, live = true) }
        },
        right = {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                screen.mark?.let { mark ->
                    SecondaryButton(mark.label, { actions.mark(mark.part, mark.watched) }, Modifier.paneElement(panes, RIGHT, PageKeys.MARK))
                }
            }
            TvFoot(screen.footnote)
        },
    )
}

/** A TV page's heading on the left: the title, the meta, then the status, a card's or weekend's count and Generic's line. */
@Composable
internal fun TvHeading(screen: EventScreen) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PageTitle(screen.title, tv = true)
        Text(screen.meta, style = AppType.secondary, color = colors.muted)
        screen.status?.let { Status(it, AppType.secondary, 4.dp, colors.chip, Modifier.padding(top = 6.dp)) }
        screen.count?.let { Status(it, AppType.secondary, 4.dp, colors.chip, Modifier.padding(top = 6.dp)) }
        screen.pick?.let { Text(it, style = AppType.secondary, color = colors.textSecondary) }
    }
}

/** What the page's first focus is placed for once: an event's or weekend's content. */
internal const val EVENT_SHOWN = "event"
