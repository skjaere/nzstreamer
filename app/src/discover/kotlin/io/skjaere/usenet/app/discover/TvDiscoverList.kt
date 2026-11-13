package io.skjaere.usenet.app.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.skjaere.usenet.app.collection.TRY_AGAIN
import io.skjaere.usenet.app.tv.PosterGrid
import io.skjaere.usenet.app.tv.PosterItem
import io.skjaere.usenet.app.tv.TvMenu
import io.skjaere.usenet.app.tv.TvMenuItem
import io.skjaere.usenet.app.tv.TvPill
import io.skjaere.usenet.app.tv.rememberPosterGridState
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.TvDimens
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.rememberInitialFocus
import io.skjaere.usenet.app.ui.tryFocus
import io.skjaere.usenet.discover.Filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A list's See all page on a TV (the DcTvSeeAll mockup): a page without the rail, its whole 864 dp column. Above the
 * posters, scrolling with them: the title with the filters' pills (each a menu: TvMenu, its lists capped), and what the
 * list shows in a line. Then TV-4's grid of posters, seven to a line, which asks for the next step as it nears the end
 * ([onNearEnd]), and under them how the paging stands.
 *
 * The first focus: the first pill; coming back from a page, the poster that opened it. A pick keeps the focus on its
 * pill. The paging keys move three lines, CH+ from the first line back up to the first pill. Back leaves the page.
 */
@Composable
internal fun TvDiscoverList(
    ui: DiscoverListUi,
    onOpen: (DiscoverCard) -> Unit,
    onNearEnd: () -> Unit,
    onRetry: () -> Unit,
    onPick: (Filter, String) -> Unit,
    onClear: () -> Unit,
    onMetadata: () -> Unit,
) {
    val grid = rememberPosterGridState()
    val initial = rememberInitialFocus()
    val scope = rememberCoroutineScope()
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    // A poster opened a page: coming back, the grid puts the focus on it again, not the first pill.
    var opened by rememberSaveable { mutableStateOf(false) }
    val firstPill = ui.pills.firstOrNull()?.filter?.let(::pillKey) ?: STATE_BUTTON

    // The first pill, the grid at its top first: its line is composed only once in view.
    suspend fun focusTop() {
        initial.awaitTurn()
        if (grid.grid.firstVisibleItemIndex > 0) grid.grid.scrollToItem(0)
        repeat(FOCUS_FRAMES) {
            withFrameNanos {}
            if (requester(firstPill).tryFocus()) return
        }
    }

    // The footer's Try again goes as the step is asked again: the focus waits on the last poster meanwhile, rather than
    // lost and put back at the screen's top, and goes on to the first new one as the step lands, the user still there.
    val latest by rememberUpdatedState(ui)
    val retryFooter = {
        val count = ui.cards.size
        val last = ui.cards.lastOrNull()?.key
        if (last != null) {
            grid.focusPoster(last)
            scope.launch {
                // Asked again, then its titles came, or it failed again (the focus stays).
                snapshotFlow { latest }.first { it.footer !is Footer.Failed }
                val answered = snapshotFlow { latest }.first { it.cards.size > count || it.footer is Footer.Failed }
                val next = answered.cards.getOrNull(count) ?: return@launch
                repeat(FOCUS_FRAMES) {
                    withFrameNanos {}
                    // Not when the user moved on meanwhile; nor again once it took.
                    if (grid.focusedPoster != last) return@launch
                    grid.focusPoster(next.key)
                }
            }
        }
        onRetry()
    }

    LaunchedEffect(ui.key) {
        if (opened) {
            opened = false
            return@LaunchedEffect
        }
        if (ui.key == KeyState.Missing || ui.key == KeyState.Refused) {
            initial.awaitTurn()
            repeat(FOCUS_FRAMES) {
                withFrameNanos {}
                if (requester(STATE_BUTTON).tryFocus()) return@LaunchedEffect
            }
        } else {
            focusTop()
        }
    }

    Box(Modifier.fillMaxSize().semantics { tvDiscoverList = true }) {
        when (ui.key) {
            null -> return@Box
            KeyState.Missing -> {
                TvStateCard(NO_KEY_TITLE, NO_KEY_LINE, ADD_KEY, primary = true, onMetadata, buttonModifier = Modifier.focusRequester(requester(STATE_BUTTON)))
                return@Box
            }
            KeyState.Refused -> {
                TvStateCard(REFUSED_TITLE, REFUSED_LINE, CHECK_KEY, primary = true, onMetadata, buttonModifier = Modifier.focusRequester(requester(STATE_BUTTON)))
                return@Box
            }
            KeyState.Ready -> Unit
        }
        val poster = remember { { card: DiscoverCard -> PosterItem(card.key, card.title.title, card.title.posterPath, card.line, card.tone, spoken = card.spoken, lineLines = card.lineLines) } }
        val header = listOf(TOP, SUMMARY) + when (ui.view) {
            ListView.Loading -> listOf(LOADING)
            is ListView.Failed -> listOf(FAILED)
            is ListView.Empty -> listOf(EMPTY)
            ListView.Shown -> emptyList()
        }
        PosterGrid(
            items = if (ui.view == ListView.Shown) ui.cards else emptyList(), key = { it.key }, poster = poster, state = grid, artwork = ui.artwork,
            onOpen = { card ->
                grid.leaving(card.key)
                opened = true
                onOpen(card)
            },
            modifier = Modifier.fillMaxSize(),
            onTab = false,
            header = header,
            headerItem = { key ->
                when (key) {
                    TOP -> TopLine(ui, { k -> focusRequester(requester(k)) }, onPick)
                    SUMMARY -> Column(Modifier.padding(bottom = 4.dp)) {
                        Text("${ui.summary} · $FROM_TMDB", style = AppType.secondary, color = colors.muted)
                        ui.offline?.let { Text(offlineLine(it), style = AppType.secondary, color = colors.muted) }
                        if (ui.justWatch) Text(JUSTWATCH, style = AppType.secondary, color = colors.muted)
                    }
                    LOADING -> Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(TvDimens.posterGutter)) {
                        repeat(BLANKS) { Box(Modifier.size(TvDimens.poster).clip(RoundedCornerShape(8.dp)).background(colors.surface)) }
                    }
                    FAILED -> (ui.view as? ListView.Failed)?.let { failed ->
                        StateLine(failed.message, TRY_AGAIN, onRetry, buttonModifier = Modifier.focusRequester(requester(STATE_BUTTON)))
                    }
                    EMPTY -> (ui.view as? ListView.Empty)?.let { empty ->
                        if (empty.canClear) StateLine(NOTHING_MATCHES, CLEAR_FILTERS, onClear, buttonModifier = Modifier.focusRequester(requester(STATE_BUTTON)))
                        else Text(NOTHING_MATCHES, style = AppType.body, color = colors.textSecondary)
                    }
                }
            },
            footer = { Footer(ui.footer, retryFooter) },
            onHeader = { scope.launch { focusTop() } },
            onNearEnd = onNearEnd,
        )
    }
}

/**
 * The title, and the filters' pills: each says what is chosen, and OK opens its choices (the chosen one in the accent).
 * On one line as the mockup has them; with a larger text size the pills go on to the next, the title whole.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopLine(ui: DiscoverListUi, element: Modifier.(String) -> Modifier, onPick: (Filter, String) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(ui.title, style = AppType.screenTitle, color = colors.text, modifier = Modifier.weight(1f).heightIn(min = 40.dp).wrapContentHeight(Alignment.CenterVertically))
        ui.pills.forEach { pill ->
            var open by remember { mutableStateOf(false) }
            Box {
                TvPill("${pill.label} ▾", Modifier.element(pillKey(pill.filter)), style = AppType.secondary, said = pillSaid(pill)) { open = true }
                TvMenu(open, onDismiss = { open = false }) { first ->
                    pill.options.forEachIndexed { i, option ->
                        TvMenuItem(option.label, if (i == 0) first else Modifier, selected = option.selected) {
                            open = false
                            onPick(pill.filter, option.id)
                        }
                    }
                }
            }
        }
    }
}

/** In the posters' place: why there are none, and the way on ([button]; [buttonModifier] goes on it). */
@Composable
private fun StateLine(message: String, button: String, onClick: () -> Unit, modifier: Modifier = Modifier, buttonModifier: Modifier = Modifier) {
    Row(modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SecondaryButton(button, onClick, buttonModifier)
        Text(message, style = AppType.body, color = colors.textSecondary)
    }
}

/** Under the posters: the next step coming, its failure with Try again, or the end at the cap. */
@Composable
private fun Footer(footer: Footer, onRetry: () -> Unit) {
    when (footer) {
        Footer.None -> Unit
        Footer.Loading -> Text(LOADING_MORE, style = AppType.secondary, color = colors.muted, modifier = Modifier.padding(vertical = 8.dp))
        is Footer.Failed -> StateLine(footer.message, TRY_AGAIN, onRetry)
        Footer.Capped -> Text(SHOWING_FIRST, style = AppType.secondary, color = colors.muted, modifier = Modifier.padding(vertical = 8.dp))
        Footer.Searched -> Text(SEARCHED_FIRST, style = AppType.secondary, color = colors.muted, modifier = Modifier.padding(vertical = 8.dp))
    }
}

private fun pillKey(filter: Filter) = PILL + filter.name

/** What TalkBack hears of a pill, and the tests know it by: "Genre: Drama". */
internal fun pillSaid(pill: FilterPill): String = "${pill.filter.name}: ${pill.label}"

/** The TV's See all page, which the tests know it by. */
val TvDiscoverListKey = SemanticsPropertyKey<Boolean>("TvDiscoverList")
private var SemanticsPropertyReceiver.tvDiscoverList by TvDiscoverListKey

/** The summary line's end, as the mockup has it. */
const val FROM_TMDB = "lists from TMDB"

/** The header's lines (none a poster's key: "show:1", "movie:1"). */
private const val TOP = "top"
private const val SUMMARY = "summary"
private const val LOADING = "loading"
private const val FAILED = "failed"
private const val EMPTY = "empty"

private const val PILL = "pill:"
private const val STATE_BUTTON = "state-button"
private const val BLANKS = 7
private const val FOCUS_FRAMES = 10
