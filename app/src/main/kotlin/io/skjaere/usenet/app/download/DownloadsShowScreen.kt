package io.skjaere.usenet.app.download

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.skjaere.usenet.app.collection.SelectMark
import io.skjaere.usenet.app.collection.TopPill
import io.skjaere.usenet.app.collection.UndoBar
import io.skjaere.usenet.app.collection.cardSegment
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.SectionHeader
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.WatchState

/**
 * A show's downloads (L372), opened from its row on the Downloads page: its finished episodes by season, Resume at the
 * top (the episode begun, or the next not watched), each row's size and length, what is left of one begun, ✓ once
 * watched, and its parts missing; a tap plays the file, online or not. Each row's ⋮: Remove download (with Undo), Mark
 * watched or unwatched, and Open the show's page ([onOpenShow]). Select with Delete, as on the Downloads page. Its names
 * are the rows', so it works offline (L368). Phones and tablets alone (W108, L369). Once every episode is gone, and no Undo
 * waits, it closes.
 */
@Composable
fun DownloadsShowScreen(showTmdbId: Int, onBack: () -> Unit, onOpenShow: (showTmdbId: Int) -> Unit) {
    val context = LocalContext.current
    val container = context.container
    if (!DownloadPlaces.shown(LocalFormFactor.current, container.television)) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val deps = remember(container) { DownloadsDeps.of(container, context.applicationContext) }
    DownloadsShowScreen(deps, showTmdbId, onBack, onOpenShow)
}

/** The show's downloads over [deps]: the app's, or a test's seams. */
@Composable
internal fun DownloadsShowScreen(deps: DownloadsDeps, showTmdbId: Int, onBack: () -> Unit, onOpenShow: (showTmdbId: Int) -> Unit) {
    val vm: DownloadsShowViewModel = viewModel(key = "downloads-show-$showTmdbId") { DownloadsShowViewModel(deps, showTmdbId) }
    val episodes by vm.episodes.collectAsStateWithLifecycle()
    val states by vm.states.collectAsStateWithLifecycle()
    val selection by vm.edits.selection.collectAsStateWithLifecycle()
    val offer by vm.edits.offer.collectAsStateWithLifecycle()
    val keepFor = undoTimeout(vm.undoMillis)
    val compact = compactBar()
    // The show's name, kept while its last episodes wait on Undo.
    var named by remember { mutableStateOf("") }
    val title = episodes?.firstOrNull()?.title ?: named
    SideEffect { named = title }
    LaunchedEffect(episodes, offer) { if (episodes?.isEmpty() == true && offer == null) onBack() }
    BackHandler(enabled = selection != null, onBack = vm.edits::exitSelect)

    val rows = episodes ?: return
    val selected = selection
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (selected != null) {
                val chosen = rows.filter { it.id in selected }
                DownloadsSelectBar(
                    selected.size, rows.size, rows.isNotEmpty() && selected.containsAll(rows.map { it.id }), chosen.sumOf(DownloadsPage::held), compact,
                    onExit = vm.edits::exitSelect, onSelectAll = { vm.edits.selectAll(rows.map { it.id }) },
                    onDelete = { vm.edits.remove(chosen, keepFor) },
                )
            } else {
                TopBar(title, onBack, subtitle = if (rows.isEmpty()) null else DownloadsPage.showSub(rows)) {
                    if (rows.isNotEmpty()) TopPill(DownloadsPage.SELECT, Icons.Filled.CheckCircle, compact, onClick = { vm.edits.startSelect() })
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = if (offer != null) 76.dp else 16.dp)) {
                val next = DownloadsShow.next(rows, states)
                if (selected == null && next != null) {
                    item(key = "resume") {
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            PrimaryButton(
                                DownloadsShow.button(next), { vm.play(next.row) },
                                leading = { Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.padding(end = 6.dp).size(24.dp)) },
                            )
                        }
                    }
                }
                DownloadsShow.seasons(rows).forEachIndexed { i, season ->
                    item(key = "season-${season.number}") { SectionHeader(DownloadsShow.seasonTitle(season.number), top = if (i == 0) 14.dp else 18.dp) }
                    itemsIndexed(season.episodes, key = { _, it -> it.id }) { at, row ->
                        EpisodeItem(
                            row, states[row.season!! to row.episode!!], first = at == 0, last = at == season.episodes.lastIndex,
                            selected = selected?.let { row.id in it },
                            onPlay = { vm.play(row) },
                            onSelect = { if (selected == null) vm.edits.startSelect(row.id) else vm.edits.toggle(row.id) },
                            onRemove = { vm.edits.remove(listOf(row), keepFor) },
                            onMark = { watched -> vm.mark(row, watched) },
                            onOpenShow = { onOpenShow(showTmdbId) },
                        )
                    }
                }
            }
        }
        offer?.let { o ->
            UndoBar(o.message, null, onUndo = vm.edits::undo, modifier = Modifier.align(Alignment.BottomCenter))
            LaunchedEffect(o.id) { vm.edits.shown(keepFor) }
        }
    }
}

/**
 * An episode downloaded, in the Show page's phone look: its number, its name, its size and length (and its parts
 * missing), what is left of it begun with its 2 dp bar, ✓ once watched, and its ⋮ beside it. A tap plays it; a long press
 * starts select mode with it, where a tap selects it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeItem(
    row: Download,
    state: WatchState?,
    first: Boolean,
    last: Boolean,
    selected: Boolean?,
    onPlay: () -> Unit,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
    onMark: (watched: Boolean) -> Unit,
    onOpenShow: () -> Unit,
) {
    val watched = state?.watched == true
    val divider = colors.divider
    val body = if (selected != null) {
        Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onSelect() })
            .semantics { stateDescription = if (selected) "Selected" else "Not selected" }
    } else {
        Modifier.combinedClickable(onClickLabel = "Play", onClick = onPlay, onLongClick = onSelect)
    }
    Row(
        Modifier.cardSegment(first, last, if (selected == true) colors.rowSelected else colors.surface)
            .drawBehind { if (!first) drawLine(divider, Offset(12.dp.toPx(), 0f), Offset(size.width - 12.dp.toPx(), 0f), 1.dp.toPx()) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).heightIn(min = 68.dp).then(body).padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selected != null) SelectMark(selected)
            Text("${row.episode}", style = AppType.bodyStrong, color = colors.textSecondary, modifier = Modifier.width(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(DownloadsShow.name(row), style = AppType.body, color = colors.text)
                val meta = DownloadsShow.meta(row, state)
                DownloadsPage.missing(row)?.let { MissingLine(it, before = "$meta · ") } ?: Text(meta, style = AppType.small, color = colors.muted)
                DownloadsShow.left(state)?.let { left ->
                    Text(left, style = AppType.small, color = colors.accent)
                    LinearProgressIndicator(
                        progress = { DownloadsShow.progress(state) ?: 0f }, modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = colors.accent, trackColor = colors.divider, drawStopIndicator = {},
                    )
                }
            }
            if (watched) Text("✓", style = AppType.bodyStrong, color = colors.good)
        }
        if (selected == null) {
            Box(Modifier.padding(end = 4.dp)) {
                MoreMenu(
                    moreFor(row),
                    listOf(
                        MenuEntry(DownloadsPage.REMOVE_DOWNLOAD, true, onRemove),
                        MenuEntry(if (watched) DownloadsPage.MARK_UNWATCHED else DownloadsPage.MARK_WATCHED, true) { onMark(!watched) },
                        MenuEntry("Open ${row.title}", true, onOpenShow),
                    ),
                )
            }
        }
    }
}
