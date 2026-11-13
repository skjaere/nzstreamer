package io.skjaere.usenet.app.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.skjaere.usenet.app.search.Poster
import io.skjaere.usenet.app.tv.tvFullWidth
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.ReturnFocus
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.TvDimens
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.play.OkGuard
import io.skjaere.usenet.app.play.pageOkGuard
import io.skjaere.usenet.app.ui.PaneMemory
import io.skjaere.usenet.app.ui.focusPane
import io.skjaere.usenet.app.ui.paneElement
import io.skjaere.usenet.app.ui.rememberInitialFocus
import io.skjaere.usenet.app.ui.tryFocus
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeRef
import io.skjaere.usenet.metadata.NextUp
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.metadata.ShowProgress
import io.skjaere.usenet.metadata.hasAired
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

/** What the Show page shows, once TMDB has told it of the show ([details]). */
@Immutable
internal data class ShowPageUi(
    val details: ShowDetails,
    val selected: Int?,
    val season: SeasonState,
    val states: Map<Pair<Int, Int>, WatchState>,
    val collected: Boolean,
    val artwork: Boolean,
    val today: LocalDate,
    val nextUpError: String?,
) {
    val progress: ShowProgress = ShowProgress.of(details, states.values.toList().toEpisodeWatches(), today, details.recentlyAired())
}

/**
 * What a TV's Show page keeps above its release chooser, which takes the page's place while it shows: the episodes'
 * place in their list, the season whose way in the list was last scrolled to, and the chips' place in their line.
 */
@Stable
internal class TvShowMemory {
    val list = LazyListState()
    var scrolledTo: Int? = null
    val chips = LazyListState()
}

/**
 * The Show page on a TV (TV-3 D4 to D8 and D11): two panes across the page's 864 dp. On
 * the left the poster, the title (the page's heading), how much is watched, the next-up episode's Resume or Play, Choose
 * release for it and the collection toggle, the overview, and how a remote plays an episode. On the right the season
 * chips, Mark season, and the season's episodes. The focus starts on Resume or Play, else on the selected season's chip;
 * Left and Right go back to where it was in each pane; Down from the chips enters the episodes at the next-up one (else
 * where the user would go on), Up from the first episode goes back to the selected chip. Back from the release chooser
 * puts the focus on what opened it ([focus]).
 */
@Composable
internal fun TvShowContent(ui: ShowPageUi, vm: ShowViewModel, focus: ReturnFocus, memory: TvShowMemory, okGuard: OkGuard) {
    val d = ui.details
    val next = ui.progress.nextUp
    val nextRef = next.episodeRef()
    val episodes = (ui.season as? SeasonState.Loaded)?.season?.episodes.orEmpty()
    val loadedSeason = (ui.season as? SeasonState.Loaded)?.season?.number
    val entry = episodeEntry(episodes, next) { ui.states[it.season to it.episode]?.watched == true }
    val entryRow = episodes.getOrNull(entry)?.let { focus.requester(rowKey(it)) }
    val selectedChip = ui.selected?.let { focus.requester(chipKey(it)) }
    val initial = rememberInitialFocus()
    // Where the focus was in each pane, for Left and Right between them.
    val panes = remember(focus) { PaneMemory(focus) }
    val scope = rememberCoroutineScope()

    // An episode's row or ⋮ is composed only once the list has it in view: there first, for the focus back on it.
    suspend fun bringIntoView(key: String) {
        val i = episodes.indexOfFirst { rowKey(it) == key || moreKey(it) == key }
        if (i >= 0 && memory.list.layoutInfo.visibleItemsInfo.none { it.index == i }) memory.list.scrollToItem(i)
    }

    // A chip is composed only once its line has it in view: a show with more seasons than the line holds opens on a
    // late one past its end. Then a frame for it to be placed, so it can take the focus.
    suspend fun chipIntoView(season: Int) {
        val i = chipOrder(d.seasons).indexOfFirst { it.number == season }
        if (i < 0) return
        // Not before the line is laid out: until then it shows no chip, in view or not.
        val shown = snapshotFlow { memory.chips.layoutInfo.visibleItemsInfo }.first { it.isNotEmpty() }
        if (shown.any { it.index == i }) return
        memory.chips.scrollToItem(i)
        withFrameNanos {}
    }

    // The way into the episodes, in view first: the list may have been walked back to its top since the season showed.
    suspend fun focusEntry() {
        val row = episodes.getOrNull(entry) ?: return
        if (memory.list.layoutInfo.visibleItemsInfo.none { it.index == entry }) {
            memory.list.scrollToItem((entry - 1).coerceAtLeast(0))
            withFrameNanos {}
        }
        focus.requester(rowKey(row)).tryFocus()
    }

    // The selected season's chip, in view first.
    suspend fun focusSelectedChip() {
        val season = ui.selected ?: return
        chipIntoView(season)
        focus.requester(chipKey(season)).tryFocus()
    }

    // The first focus: back on what opened the chooser, else Resume or Play, else the selected season's chip. Once the
    // library has said what was watched (the season is selected then): before, every show would start on S01E01. Once.
    var firstAsked by remember { mutableStateOf(false) }
    LaunchedEffect(ui.selected != null) {
        if (firstAsked) return@LaunchedEffect
        if (focus.restore(::bringIntoView)) {
            firstAsked = true
            return@LaunchedEffect
        }
        if (ui.selected == null) return@LaunchedEffect
        firstAsked = true
        initial.awaitTurn()
        withFrameNanos {}
        if (!focus.requester(NEXT_KEY).tryFocus()) focusSelectedChip()
    }

    // The selected season's chip in view, which the line, opening at its start, may not show.
    LaunchedEffect(ui.selected) { ui.selected?.let { chipIntoView(it) } }

    // Resume or Play, or Choose release, gone while it had the focus (its episode watched as the player closed, the
    // show now up to date): the focus to the selected season's chip, as when the page opens up to date, not wherever
    // the shell would settle it. Whether one had it, read as this is composed, before the buttons are gone.
    val nextFocused = remember { BooleanArray(2) }
    val nextHadFocus = remember(nextRef == null) { nextFocused.any { it } }
    LaunchedEffect(nextRef == null) {
        if (nextRef != null || !nextHadFocus) return@LaunchedEffect
        withFrameNanos {}
        focusSelectedChip()
    }

    // A season shown anew: its way in (the next-up episode, or where the user goes on) in view, so Down finds it composed.
    LaunchedEffect(loadedSeason) {
        if (loadedSeason == null || memory.scrolledTo == loadedSeason) return@LaunchedEffect
        memory.scrolledTo = loadedSeason
        memory.list.scrollToItem((entry - 1).coerceAtLeast(0))
    }

    // The season's episodes, or Try again, replaced while they had the focus (Try again pressed, the season loaded
    // again): the focus to what replaces them, Try again or the way in. Not when the user is elsewhere (on the chips).
    var bodyFocused by remember { mutableStateOf(false) }
    val owed = remember { BooleanArray(1) }
    val kind = ui.season::class
    // Whether the body had the focus as this state came: read as it is composed, before the old body is gone with it.
    val hadFocus = remember(kind) { bodyFocused }
    LaunchedEffect(kind) {
        if (hadFocus) owed[0] = true
        if (!owed[0] || ui.season is SeasonState.Loading) return@LaunchedEffect
        owed[0] = false
        withFrameNanos {}
        if (ui.season is SeasonState.Failed) focus.requester(TRY_KEY).tryFocus() else entryRow?.tryFocus()
    }

    Row(Modifier.fillMaxHeight().tvFullWidth().pageOkGuard(okGuard), horizontalArrangement = Arrangement.spacedBy(TvDimens.paneGap)) {
        TvPagePane(
            Modifier.width(TvDimens.pagePane).fillMaxHeight().focusPane(panes, LEFT), gap = 16.dp,
            top = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ShowHeading(d, ui.progress, ui.artwork)
                    // Resume or Play with Choose release and the toggle under it, close as one group.
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // One button for Resume and Play, so the focus stays on it when what is watched changes its words.
                        val nextLabel = when (next) {
                            is NextUp.Resume -> "Resume ${next.episode.label} · ${mmss(next.positionMs)}"
                            is NextUp.Play -> "Play ${next.episode.label}"
                            is NextUp.UpToDate -> null
                        }
                        if (nextLabel != null && nextRef != null) {
                            PrimaryButton(
                                nextLabel, { vm.playNext(nextRef) },
                                Modifier.fillMaxWidth().paneElement(panes, LEFT, NEXT_KEY).onFocusChanged { nextFocused[0] = it.isFocused },
                                leading = { PlayIcon() },
                            )
                        } else {
                            val returns = (next as? NextUp.UpToDate)?.returns
                            Text(returns?.let { "Up to date · next episode ${shortDate(it)}" } ?: "Up to date", style = AppType.secondary, color = colors.muted)
                        }
                        nextRef?.let { ref ->
                            SecondaryButton(
                                "Choose release",
                                {
                                    focus.leaving(CHOOSE_KEY)
                                    vm.chooseNext(ref)
                                },
                                Modifier.fillMaxWidth().paneElement(panes, LEFT, CHOOSE_KEY).onFocusChanged { nextFocused[1] = it.isFocused },
                            )
                        }
                        SecondaryButton(
                            if (ui.collected) "In collection ✓" else "+ Collection", { vm.toggleCollection() },
                            Modifier.fillMaxWidth().paneElement(panes, LEFT, COLLECTION_KEY),
                        )
                    }
                    ui.nextUpError?.let { Text(it, style = AppType.small, color = colors.warn) }
                }
            },
            // What room is left: the overview gives way, line by line, to a larger text size, a long title, the play bar.
            between = { d.overview?.let { WholeLines(it, AppType.body, colors.textSecondary, maxLines = 3) } },
            bottom = { Text(SHOW_FOOTNOTE_TV, style = AppType.small, color = colors.muted) },
        )

        Column(Modifier.weight(1f).fillMaxHeight().focusPane(panes, RIGHT), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                // As tall as with Mark season at its end (a 48 dp button), which shows only once the season has: the
                // episodes below don't move as a season loads.
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SeasonChips(
                    d.seasons, ui.selected, { n -> ui.states.count { (key, st) -> key.first == n && st.watched } }, vm::seasonPressed,
                    Modifier.weight(1f),
                    // Room for a focused chip's growth at the row's ends.
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    state = memory.chips,
                    chipModifier = { n -> Modifier.paneElement(panes, RIGHT, chipKey(n)) },
                    onFocused = vm::seasonFocused, onLeft = vm::seasonsLeft,
                )
                // At the end of the chips' line, outside their row: Down from any chip goes into the episodes.
                (ui.season as? SeasonState.Loaded)?.let { loaded ->
                    val mark = seasonMark(loaded.season.episodes, { d.hasAired(it, ui.today) }, { ui.states[it.season to it.episode]?.watched == true })
                    SecondaryButton(mark.label, { vm.mark(mark.episodes, mark.watched) }, Modifier.paneElement(panes, RIGHT, MARK_KEY), enabled = mark.enabled)
                }
            }
            val body = Modifier.fillMaxWidth().onFocusChanged { bodyFocused = it.hasFocus }
            when (val season = ui.season) {
                SeasonState.Loading -> SkeletonRows(ui.artwork, body)
                is SeasonState.Failed -> Column(body, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(SEASON_LOAD_FAILED + season.message, style = AppType.secondary, color = colors.textSecondary)
                    SecondaryButton(TRY_AGAIN, { ui.selected?.let(vm::seasonPressed) }, Modifier.paneElement(panes, RIGHT, TRY_KEY))
                }
                is SeasonState.Loaded -> Box(
                    // Clipped to its corners: the next-up row's tint, first or last in view, would show square ones.
                    body.weight(1f, fill = false).clip(RoundedCornerShape(16.dp)).background(colors.surface)
                        .focusProperties {
                            onEnter = {
                                // Off the list's screen it isn't composed: brought into view, then given the focus.
                                if (requestedFocusDirection == FocusDirection.Down && entryRow?.tryFocus() == false) {
                                    cancelFocusChange()
                                    scope.launch { focusEntry() }
                                }
                            }
                            onExit = {
                                // The selected chip, not the one the move would find: that one's dwell would switch the
                                // season. Off the line's end it isn't composed: brought into view, then given the focus.
                                if (requestedFocusDirection == FocusDirection.Up && selectedChip?.tryFocus() == false) {
                                    cancelFocusChange()
                                    scope.launch { focusSelectedChip() }
                                }
                            }
                        }
                        .focusGroup(),
                ) {
                    LazyColumn(Modifier.fillMaxWidth(), state = memory.list) {
                        itemsIndexed(season.season.episodes, key = { _, e -> "${e.season}x${e.episode}" }) { i, e ->
                            if (i > 0) InsetDivider()
                            EpisodeRow(
                                e, ui.states[e.season to e.episode], d.hasAired(e, ui.today),
                                onPlay = { vm.play(e) },
                                onChoose = { fromMore ->
                                    focus.leaving(if (fromMore) moreKey(e) else rowKey(e))
                                    vm.chooseRelease(e)
                                },
                                onMark = { w -> vm.mark(listOf(e), w) },
                                modifier = Modifier.paneElement(panes, RIGHT, rowKey(e)),
                                moreModifier = Modifier.paneElement(panes, RIGHT, moreKey(e)),
                                still = e.stillPath, showArtwork = ui.artwork, current = nextRef == EpisodeRef(e.season, e.episode),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The poster beside the title (the page's heading), the year, seasons and status, and how much is watched. */
@Composable
private fun ShowHeading(d: ShowDetails, p: ShowProgress, artwork: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Poster(d.posterPath, artwork, TvDimens.pagePoster.width.value.toInt(), TvDimens.pagePoster.height.value.toInt(), imageWidth = 342)
        Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Three lines at most, beside the poster: a long title doesn't push the buttons down the page.
            Text(d.name, style = AppType.screenTitle, color = colors.text, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            val seasons = d.seasons.count { !it.isSpecials }
            Text(
                listOfNotNull(listOfNotNull(d.year?.toString(), "$seasons season${if (seasons == 1) "" else "s"}").joinToString(" · "), d.status).joinToString("\n"),
                style = AppType.secondary, color = colors.muted,
            )
            if (p.aired > 0) {
                Text("${p.watched} of ${p.aired} watched", style = AppType.secondary, color = colors.textSecondary, modifier = Modifier.padding(top = 6.dp))
                LinearProgressIndicator(
                    progress = { p.watched.toFloat() / p.aired }, modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = colors.accent, trackColor = colors.chip, drawStopIndicator = {},
                )
            }
        }
    }
}

@Composable
private fun PlayIcon() = Icon(Icons.Filled.PlayArrow, null, Modifier.padding(end = 6.dp))

/** A season loading: the shape of its rows, where they will be and as tall, with nothing to focus. */
@Composable
private fun SkeletonRows(artwork: Boolean, modifier: Modifier) {
    Column(modifier.background(colors.surface, RoundedCornerShape(16.dp))) {
        repeat(SKELETON_ROWS) { i ->
            if (i > 0) InsetDivider()
            Row(
                // Made as a row is, its inset and the rest, so it is as tall at any density.
                Modifier.semantics { skeletonRow = i }.fillMaxWidth().padding(vertical = TV_ROW_INSET)
                    .height((if (artwork) TV_ROW_WITH_STILL else TV_ROW_PLAIN) - TV_ROW_INSET * 2).padding(horizontal = 10.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (artwork) Box(Modifier.size(TvDimens.still).background(colors.chip, RoundedCornerShape(6.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth(0.6f).height(14.dp).background(colors.chip, RoundedCornerShape(4.dp)))
                    Box(Modifier.fillMaxWidth(0.35f).height(12.dp).background(colors.chip, RoundedCornerShape(4.dp)))
                }
            }
        }
    }
}

/** A loading season's outline's row, by its place, which the tests find the outline by; TalkBack doesn't see it. */
internal val SkeletonRowKey = SemanticsPropertyKey<Int>("SkeletonRow")
private var SemanticsPropertyReceiver.skeletonRow by SkeletonRowKey

private fun rowKey(e: Episode) = "row:${e.season}x${e.episode}"

private fun moreKey(e: Episode) = "more:${e.season}x${e.episode}"

private fun chipKey(season: Int) = "chip:$season"

private const val NEXT_KEY = "next"
private const val CHOOSE_KEY = "choose"
private const val TRY_KEY = "try"
private const val COLLECTION_KEY = "collection"
private const val MARK_KEY = "mark"

/** The panes, for [PaneMemory]. */
private const val LEFT = "left"
private const val RIGHT = "right"
private const val SKELETON_ROWS = 4
