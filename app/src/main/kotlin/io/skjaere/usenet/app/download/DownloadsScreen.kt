package io.skjaere.usenet.app.download

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.skjaere.usenet.app.collection.ActionPill
import io.skjaere.usenet.app.collection.SelectMark
import io.skjaere.usenet.app.collection.TextAction
import io.skjaere.usenet.app.collection.TopPill
import io.skjaere.usenet.app.collection.UndoBar
import io.skjaere.usenet.app.collection.cardSegment
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.link.CastButton
import io.skjaere.usenet.app.link.PauseIcon
import io.skjaere.usenet.app.link.SheetPlace
import io.skjaere.usenet.app.search.PlayCircle
import io.skjaere.usenet.app.search.Poster
import io.skjaere.usenet.app.search.ReleaseList
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.DownloadIcon
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.SectionHeader
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.metadata.TmdbImages

/**
 * The Downloads page (item 19; L372, L379-L381), on phones and tablets alone (W108, L369): where downloads don't show it
 * ends at once and shows nothing. The bar's Select, the Cast button and its gear to Settings › Downloads ([onSettings],
 * L453), the title on a line of its own where they leave it no room (L470); the space strip, with the lines about mobile
 * data and Android's background limit (L361); Downloading (the row the queue is at, with Pause or Resume, then the queue
 * with Move up and Move down), Failed, and Downloaded, one row per show ([onShow] opens its downloads) and one per film
 * (a tap plays it where Play on points, L418); select mode with Delete and the Undo bar. Choose release and Choose
 * another release show the release list in download mode in the page's place. [onOpenMovie] opens a film's page. A
 * notification's ask comes as [choose] (a download's releases) or [play] (a finished one played), with its own id ([ask]).
 */
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onShow: (showTmdbId: Int) -> Unit,
    onOpenMovie: (movieTmdbId: Int) -> Unit,
    choose: String? = null,
    play: String? = null,
    ask: String? = null,
) {
    val context = LocalContext.current
    val container = context.container
    if (!DownloadPlaces.shown(LocalFormFactor.current, container.television)) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val deps = remember(container) { DownloadsDeps.of(container, context.applicationContext) }
    DownloadsScreen(deps, onBack, onSettings, onShow, onOpenMovie, choose, play, ask)
}

/** The page over [deps]: the app's, or a test's seams. */
@Composable
internal fun DownloadsScreen(
    deps: DownloadsDeps,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onShow: (showTmdbId: Int) -> Unit,
    onOpenMovie: (movieTmdbId: Int) -> Unit,
    choose: String? = null,
    play: String? = null,
    ask: String? = null,
) {
    val vm: DownloadsViewModel = viewModel { DownloadsViewModel(deps, createSavedStateHandle()) }
    // Each ask once, the one the page opened with and any that comes to it on top (DownloadsRoute.ask).
    LaunchedEffect(ask) { vm.take(ask, choose, play) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.shown() }
    val choosing by vm.choice.state.collectAsStateWithLifecycle()
    when (val c = choosing) {
        is DownloadChoice.State.LookingUp -> {
            BackHandler(onBack = vm.choice::close)
            ChoiceFinding(c.row, vm.choice::close)
            return
        }
        is DownloadChoice.State.Choosing -> {
            BackHandler(onBack = vm.choice::close)
            ChoiceList(c, vm.choice::close)
            return
        }
        DownloadChoice.State.None -> Unit
    }

    val page by vm.page.collectAsStateWithLifecycle()
    val measured by vm.space.collectAsStateWithLifecycle()
    val selection by vm.edits.selection.collectAsStateWithLifecycle()
    val offer by vm.edits.offer.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val mobileData by vm.mobileData.collectAsStateWithLifecycle()
    val restricted by vm.restricted.collectAsStateWithLifecycle()
    val artwork by vm.showArtwork.collectAsStateWithLifecycle()
    val lookupsFailed by vm.choice.needsConnection.collectAsStateWithLifecycle()
    val keepFor = undoTimeout(vm.undoMillis)
    val compact = compactBar()
    val context = LocalContext.current
    val playOn by context.container.playOn.target.collectAsStateWithLifecycle()
    BackHandler(enabled = selection != null, onBack = vm.edits::exitSelect)

    val p = page
    val selected = selection
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (selected != null && p != null) {
                val rows = p.rows.filter { it.key in selected }
                val keys = p.rows.map { it.key }
                DownloadsSelectBar(
                    selected.size, keys.size, keys.isNotEmpty() && selected.containsAll(keys), rows.sumOf { it.held }, compact,
                    onExit = vm.edits::exitSelect, onSelectAll = { vm.edits.selectAll(keys) },
                    onDelete = { vm.edits.remove(rows.flatMap { it.downloads }, keepFor) },
                )
            } else {
                TopBar(DownloadsPage.TITLE, onBack, stacked = true) {
                    if (p?.empty == false) TopPill(DownloadsPage.SELECT, Icons.Filled.CheckCircle, compact, onClick = { vm.edits.startSelect() })
                    // Where the rows play (L453): Play on opened here ticks This phone with a TV chosen, as Import's does.
                    CastButton(playOn, { context.container.playOn.openSheet(SheetPlace.PhoneOnly) }, place = SheetPlace.PhoneOnly)
                    IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Settings, contentDescription = DownloadsPage.SETTINGS, tint = colors.text)
                    }
                }
            }
            if (p == null) return@Column
            val space = measured?.space
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = if (offer != null) 76.dp else 16.dp)) {
                if (p.empty) {
                    item(key = "empty") { Empty(measured != null, space) }
                    return@LazyColumn
                }
                item(key = "strip") { Strip(measured != null, space, mobileData, restricted) { openAppSettings(context) } }
                val choosable = { row: Download -> vm.choice.can(row, online, lookupsFailed) }
                fun select(row: DownloadsRow): Boolean? = selected?.let { row.key in it }
                fun pick(row: DownloadsRow) {
                    if (selected == null) vm.edits.startSelect(row.key) else vm.edits.toggle(row.key)
                }
                section(DownloadsPage.DOWNLOADING, p.downloading, top = 14.dp) { row, first, last ->
                    ActiveRow(
                        row, first, last, select(row), artwork, choosable(row.row), onSelect = { pick(row) },
                        onPause = vm::pause, onResume = vm::resume, onMove = { up -> vm.move(row.row, up) }, onChoose = { vm.choice.open(row.row) },
                        onCancel = { vm.edits.remove(row.downloads, keepFor) },
                    )
                }
                section(DownloadsPage.FAILED, p.failed) { row, first, last ->
                    FailedRow(
                        row, first, last, select(row), artwork, choosable(row.row), onSelect = { pick(row) }, onRetry = { vm.retry(row.row) },
                        onChoose = { vm.choice.open(row.row) }, onRemove = { vm.edits.remove(row.downloads, keepFor) },
                    )
                }
                section(DownloadsPage.DOWNLOADED, p.downloaded) { row, first, last ->
                    when (row) {
                        is DownloadsRow.Show -> ShowRow(row, first, last, select(row), artwork, onSelect = { pick(row) }, onOpen = { onShow(row.showTmdbId) })
                        is DownloadsRow.Film -> FilmRow(
                            row, first, last, select(row), artwork, DownloadsPage.playLabel(row.title, playOn), onSelect = { pick(row) },
                            onPlay = { vm.play(row.row) }, onRemove = { vm.edits.remove(row.downloads, keepFor) }, onMark = { vm.mark(row.row, !row.watched) },
                            onOpen = { row.row.movieTmdbId?.let(onOpenMovie) },
                        )
                        else -> Unit
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

/** A section of the page: its header, a heading for TalkBack's heading navigation, and its rows in one card, row by row. */
private fun <T : DownloadsRow> LazyListScope.section(title: String, rows: List<T>, top: Dp = 18.dp, row: @Composable (T, Boolean, Boolean) -> Unit) {
    if (rows.isEmpty()) return
    item(key = "header-$title") { SectionHeader(title, Modifier.semantics(mergeDescendants = true) { heading() }, top = top) }
    itemsIndexed(rows, key = { _, it -> it.key }) { i, it -> row(it, i == 0, i == rows.lastIndex) }
}

/** The row the queue is at, or one in the queue: its state, its bar and bytes, whether its season's pack serves it, Pause or Resume, and its ⋮. */
@Composable
private fun ActiveRow(
    row: DownloadsRow.Active,
    first: Boolean,
    last: Boolean,
    selected: Boolean?,
    artwork: Boolean,
    choosable: Boolean,
    onSelect: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onMove: (up: Boolean) -> Unit,
    onChoose: () -> Unit,
    onCancel: () -> Unit,
) {
    val words = DownloadsPage.active(row.item, row.current, row.pack, row.limit)
    val queued = row.row.state == DownloadState.Queued
    val choose = MenuEntry(if (row.current) DownloadsPage.CHOOSE_ANOTHER else DownloadsPage.CHOOSE_RELEASE, choosable, onChoose)
    val menu = if (queued) {
        listOf(MenuEntry(DownloadsPage.MOVE_UP, row.up) { onMove(true) }, MenuEntry(DownloadsPage.MOVE_DOWN, row.down) { onMove(false) }, choose)
    } else {
        listOf(choose)
    } + MenuEntry(DownloadsPage.CANCEL, true, onCancel)
    RowFrame(
        row, first, last, selected, artwork, onClick = null, onSelect = onSelect,
        end = {
            when (words.button) {
                RowButton.Pause -> IconButton(onClick = onPause, modifier = Modifier.size(48.dp)) {
                    Icon(PauseIcon, contentDescription = DownloadsPage.PAUSE, tint = colors.text)
                }
                RowButton.Resume -> TextAction(DownloadsPage.RESUME, onClick = onResume)
                null -> Unit
            }
            MoreMenu(moreFor(row.row), menu)
        },
    ) {
        DownloadsPage.sub(row.row)?.let { Text(it, style = AppType.secondary, color = colors.muted) }
        words.lines.forEach { Text(it.text, style = AppType.small, color = it.tone.color()) }
        words.bar?.let { Bar(it) }
        words.bytes?.let { Text(it, style = AppType.small, color = colors.muted) }
        words.note?.let { Text(it, style = AppType.small, color = colors.muted) }
    }
}

/** A failed download: why, how many releases were tried, Try again and Choose release, and Remove in its ⋮. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FailedRow(
    row: DownloadsRow.Failed,
    first: Boolean,
    last: Boolean,
    selected: Boolean?,
    artwork: Boolean,
    choosable: Boolean,
    onSelect: () -> Unit,
    onRetry: () -> Unit,
    onChoose: () -> Unit,
    onRemove: () -> Unit,
) {
    RowFrame(
        row, first, last, selected, artwork, onClick = null, onSelect = onSelect,
        end = { MoreMenu(moreFor(row.row), listOf(MenuEntry(DownloadsPage.REMOVE, true, onRemove))) },
    ) {
        DownloadsPage.sub(row.row)?.let { Text(it, style = AppType.secondary, color = colors.muted) }
        DownloadsPage.failure(row.row).forEachIndexed { i, line ->
            Text(line.text, style = if (i == 0) AppType.secondary else AppType.small, color = line.tone.color())
        }
        if (selected == null) {
            FlowRow(Modifier.offset(x = (-6).dp), horizontalArrangement = Arrangement.spacedBy(10.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                TextAction(DownloadsPage.TRY_AGAIN, onClick = onRetry)
                Greyable(DownloadsPage.CHOOSE_RELEASE, choosable, onChoose)
            }
            if (!choosable) Text(DownloadsPage.NEEDS_CONNECTION, style = AppType.small, color = colors.muted)
        }
    }
}

/**
 * A show's finished episodes as this page draws them, alone in a card of its own, opening its downloads ([onOpen]): the
 * Show page's "Downloaded" section where TMDB gave it no page (L439). No select mode there, so no long press.
 */
@Composable
internal fun DownloadsShowCard(row: DownloadsRow.Show, artwork: Boolean, onOpen: () -> Unit) =
    ShowRow(row, first = true, last = true, selected = null, artwork = artwork, onSelect = null, onOpen = onOpen)

/** A show's finished episodes: how many and their size, what plays next, and › to its downloads. */
@Composable
private fun ShowRow(row: DownloadsRow.Show, first: Boolean, last: Boolean, selected: Boolean?, artwork: Boolean, onSelect: (() -> Unit)?, onOpen: () -> Unit) {
    RowFrame(
        row, first, last, selected, artwork, onClick = onOpen, onSelect = onSelect,
        end = { Text("›", style = AppType.body, color = colors.muted, modifier = Modifier.padding(start = 4.dp, end = 16.dp)) },
    ) {
        Text(DownloadsPage.showSub(row.episodes), style = AppType.secondary, color = colors.muted)
        row.next?.let { Text(DownloadsPage.nextLine(it), style = AppType.secondary, color = colors.muted) }
    }
}

/**
 * A finished film: its year, length and size, its missing parts, and "Deleting tomorrow" while Delete once watched has it
 * going (L415); Play, read as [playLabel] (the Cast device it plays on, L470), and its ⋮. A tap plays it.
 */
@Composable
private fun FilmRow(
    row: DownloadsRow.Film,
    first: Boolean,
    last: Boolean,
    selected: Boolean?,
    artwork: Boolean,
    playLabel: String,
    onSelect: () -> Unit,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onMark: () -> Unit,
    onOpen: () -> Unit,
) {
    val menu = listOf(
        MenuEntry(DownloadsPage.REMOVE_DOWNLOAD, true, onRemove),
        MenuEntry(if (row.watched) DownloadsPage.MARK_UNWATCHED else DownloadsPage.MARK_WATCHED, true, onMark),
        MenuEntry("Open ${row.title}", row.row.movieTmdbId != null, onOpen),
    )
    RowFrame(
        row, first, last, selected, artwork, onClick = onPlay, onSelect = onSelect,
        end = {
            PlayCircle(primary = false, label = playLabel, onClick = onPlay)
            MoreMenu(moreFor(row.row), menu)
        },
    ) {
        Text(DownloadsPage.filmSub(row.row), style = AppType.secondary, color = colors.muted)
        DownloadsPage.missing(row.row)?.let { MissingLine(it) }
        DownloadsPage.going(row)?.let { Text(it, style = AppType.secondary, color = colors.muted) }
    }
}

/** "Downloaded · 2 parts missing", what is missing in the warning colour. */
@Composable
internal fun MissingLine(missing: String, before: String = "${DownloadsPage.DOWNLOADED} · ") {
    val warn = colors.warn
    Text(buildAnnotatedString {
        append(before)
        withStyle(SpanStyle(color = warn)) { append(missing) }
    }, style = AppType.small, color = colors.muted)
}

/**
 * A row in its section's card: in select mode one target with its mark, the row selected toggled by a tap; else a tap
 * does [onClick] (none: nothing, as for a download still to finish) and a long press starts select mode with it
 * ([onSelect]; none where there is no select mode). The poster through the image loader: the download's stored copy while
 * it has a fresh one (L411), else TMDB's while Coil has it (L378), a plain tile while there is none. [end]: its button and ⋮.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowFrame(
    row: DownloadsRow,
    first: Boolean,
    last: Boolean,
    selected: Boolean?,
    artwork: Boolean,
    onClick: (() -> Unit)?,
    onSelect: (() -> Unit)?,
    end: @Composable RowScope.() -> Unit,
    lines: @Composable ColumnScope.() -> Unit,
) {
    val divider = colors.divider
    // The page draws a download's row again as it is written, each time with a new [onSelect]: the press goes on through
    // that, keyed on nothing, and a long one selects with the latest.
    val select by rememberUpdatedState(onSelect)
    val body = when {
        selected != null -> Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onSelect?.invoke() })
            .semantics { stateDescription = if (selected) "Selected" else "Not selected" }
        onClick != null -> Modifier.combinedClickable(onClick = onClick, onLongClick = onSelect)
        onSelect == null -> Modifier
        else -> Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { select?.invoke() }) }
            .semantics(mergeDescendants = true) { customActions = listOf(CustomAccessibilityAction(DownloadsPage.SELECT) { onSelect(); true }) }
    }
    Row(
        Modifier.cardSegment(first, last, if (selected == true) colors.rowSelected else colors.surface)
            .drawBehind { if (!first) drawLine(divider, Offset(12.dp.toPx(), 0f), Offset(size.width - 12.dp.toPx(), 0f), 1.dp.toPx()) }
            .padding(end = if (selected == null) 4.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).then(body).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selected != null) SelectMark(selected)
            // The download's own copy while it has one (L411), so the page shows it offline; TMDB's otherwise.
            Poster(storedPicture(row.posterRow?.id, DownloadImages.Kind.Poster, TmdbImages.poster(row.posterPath, 154)), artwork, 44, 66)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(row.title, style = AppType.bodyStrong, color = colors.text)
                lines()
            }
        }
        if (selected == null) end()
    }
}

/** The bar under a download still to finish: how much is written, or moving while its release is found. */
@Composable
private fun Bar(bar: PageBar) {
    val modifier = Modifier.padding(top = 4.dp).fillMaxWidth().height(4.dp)
    when (bar) {
        is PageBar.Part -> LinearProgressIndicator(
            progress = { bar.fraction }, modifier = modifier, color = bar.tone.color(), trackColor = colors.chip, drawStopIndicator = {}, gapSize = 0.dp,
        )
        PageBar.Finding -> LinearProgressIndicator(modifier = modifier, color = colors.accent, trackColor = colors.chip, gapSize = 0.dp)
    }
}

/**
 * The space strip: what downloads hold, the running one whole, and what is free, those removed counted gone at once (L379);
 * on mobile data, that downloads wait for Wi-Fi or Ethernet; where Android limits the app in the background, that it
 * does, with the app's settings (L361). Nothing until the space is [read]; while the volume isn't there, it says so.
 */
@Composable
private fun Strip(read: Boolean, space: DownloadSpace?, mobileData: Boolean, restricted: Boolean, onAppSettings: () -> Unit) {
    val text = colors.text
    Column(
        Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surface)
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
    ) {
        when {
            !read -> Unit
            space == null -> Text(DownloadHostRules.STORAGE_UNAVAILABLE, style = AppType.secondary, color = colors.warn)
            else -> {
                val strip = DownloadsPage.strip(space)
                val words = DownloadsPage.stripWords(strip)
                Text(
                    buildAnnotatedString {
                        words.held?.let { held ->
                            append("${DownloadsPage.TITLE} ")
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = text)) { append(held) }
                            append(" · ")
                        }
                        append(words.free)
                    },
                    style = AppType.secondary, color = colors.textSecondary,
                )
                LinearProgressIndicator(
                    progress = { strip.fraction }, modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(6.dp),
                    color = colors.accent, trackColor = colors.chip, drawStopIndicator = {}, gapSize = 0.dp,
                )
            }
        }
        if (mobileData) Text(DownloadsPage.MOBILE_DATA, style = AppType.small, color = colors.muted, modifier = Modifier.padding(top = 8.dp))
        if (restricted) {
            Text(DownloadsPage.RESTRICTED, style = AppType.small, color = colors.muted, modifier = Modifier.padding(top = 8.dp))
            TextAction(DownloadsPage.APP_SETTINGS, Modifier.offset(x = (-6).dp), onClick = onAppSettings)
        }
    }
}

/** Nothing downloaded or queued: what the page is for, the space free, and where downloads stay. */
@Composable
private fun Empty(read: Boolean, space: DownloadSpace?) {
    Column(
        Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(colors.surface), contentAlignment = Alignment.Center) {
            Icon(DownloadIcon, contentDescription = null, tint = colors.muted, modifier = Modifier.size(32.dp))
        }
        Text(
            DownloadsPage.EMPTY, style = AppType.cardTitle, color = colors.text, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp).semantics { heading() },
        )
        Text(DownloadsPage.EMPTY_LINE, style = AppType.body, color = colors.textSecondary, textAlign = TextAlign.Center)
        if (read) {
            Text(
                space?.let { DownloadSheetModel.free(DownloadsPage.strip(it).free) } ?: DownloadHostRules.STORAGE_UNAVAILABLE,
                style = AppType.small, color = colors.muted, textAlign = TextAlign.Center,
            )
        }
        Text(DownloadsPage.STAYS, style = AppType.small, color = colors.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
    }
}

/**
 * Select mode's bar, as the Collection's: ✕, the count, Select all; under it Delete with what it gives back, which wraps
 * at large font sizes, Select all going down with it where the bar is short of room ([compact]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DownloadsSelectBar(
    count: Int,
    total: Int,
    all: Boolean,
    bytes: Long,
    compact: Boolean,
    onExit: () -> Unit,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
) {
    val selectAll = if (all) "Select none" else DownloadsPage.selectAll(total)
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onExit, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Leave select mode", tint = colors.text)
            }
            Text(DownloadsPage.selected(count), style = AppType.cardTitle, color = colors.text, maxLines = 2, modifier = Modifier.weight(1f))
            if (!compact) TextAction(selectAll, onClick = onSelectAll)
        }
        FlowRow(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            ActionPill(DownloadsPage.delete(bytes), Icons.Filled.Delete, count > 0, onClick = onDelete)
            if (compact) TextAction(selectAll, onClick = onSelectAll)
        }
    }
}

/** An item of a row's ⋮: its [text], greyed "Needs a connection" where a choice can't open now ([enabled]). */
internal class MenuEntry(val text: String, val enabled: Boolean, val onClick: () -> Unit)

/** A row's ⋮ ([label] for TalkBack) and its menu of [entries]. */
@Composable
internal fun MoreMenu(label: String, entries: List<MenuEntry>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.MoreVert, contentDescription = label, tint = colors.muted) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = colors.surface) {
            entries.forEach { entry ->
                val choice = entry.text == DownloadsPage.CHOOSE_RELEASE || entry.text == DownloadsPage.CHOOSE_ANOTHER
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(entry.text, style = AppType.body)
                            if (choice && !entry.enabled) Text(DownloadsPage.NEEDS_CONNECTION, style = AppType.small, color = colors.muted)
                        }
                    },
                    onClick = {
                        open = false
                        entry.onClick()
                    },
                    enabled = entry.enabled,
                    colors = MenuDefaults.itemColors(textColor = colors.text, disabledTextColor = colors.text.copy(alpha = DISABLED_ALPHA)),
                )
            }
        }
    }
}

/** A text action that can be greyed, as Material greys a disabled button; read as dimmed. */
@Composable
private fun Greyable(text: String, enabled: Boolean, onClick: () -> Unit) {
    if (enabled) {
        TextAction(text, onClick = onClick)
    } else {
        Text(
            text, style = AppType.bodyStrong.copy(fontSize = 14.sp), color = colors.text.copy(alpha = DISABLED_ALPHA),
            modifier = Modifier.heightIn(min = 48.dp).semantics { disabled() }.padding(horizontal = 6.dp, vertical = 13.dp),
        )
    }
}

/** Choose release while its episode is looked up or its search runs: the list's title, finding. */
@Composable
private fun ChoiceFinding(row: Download, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar(DownloadsPage.choosing(row), onBack, subtitle = DownloadHostRules.FINDING_RELEASES)
        CircularProgressIndicator(Modifier.padding(24.dp), color = colors.accent)
    }
}

/**
 * Choose release's list for [choice]: the release list in download mode once found (item 16), a tap replacing the
 * download's releases and closing it; nothing found, the indexers' lines as the Download sheet says them. A pack's Choose
 * file opens its files in a sheet over the list (L435): a file chosen there replaces the download's release at once, as a
 * tap does; Cancel and Try another release go back to the list, where a pack that failed says why; Close closes the list.
 */
@Composable
private fun ChoiceList(choice: DownloadChoice.State.Choosing, onBack: () -> Unit) {
    val state by choice.model.state.collectAsStateWithLifecycle()
    val failed by choice.model.failed.collectAsStateWithLifecycle()
    val files by choice.model.choice.collectAsStateWithLifecycle()
    files?.let { DownloadFileChoiceSheet(it, onCancel = choice.model::closeChoice, onTryAnother = choice.model::closeChoice, onClose = onBack) }
    when (val s = state) {
        DownloadSheetState.Finding -> ChoiceFinding(choice.row, onBack)
        is DownloadSheetState.Pick -> ReleaseList(s.group, choice.row.title, onBack = onBack, failed = failed, mode = s.mode)
        is DownloadSheetState.WontFit -> ReleaseList(s.group, choice.row.title, onBack = onBack, failed = failed, mode = s.mode)
        // Never here: a file chosen on this page replaces the download's release at once.
        is DownloadSheetState.Chosen -> ReleaseList(s.group, choice.row.title, onBack = onBack, failed = failed, mode = s.mode)
        // Never here: Choose release opens nothing for a file opened on Import (DownloadChoice.open), which has no releases.
        is DownloadSheetState.File, is DownloadSheetState.FileWontFit -> TopBar(DownloadsPage.choosing(choice.row), onBack)
        is DownloadSheetState.NoRelease -> Column(Modifier.fillMaxSize()) {
            TopBar(DownloadsPage.choosing(choice.row), onBack)
            Text(
                DownloadSheetModel.NO_RELEASE, style = AppType.cardTitle, color = colors.text,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp).semantics { heading() },
            )
            if (s.stages.isNotEmpty()) {
                GroupCard {
                    s.stages.forEachIndexed { i, (what, outcome) ->
                        if (i > 0) InsetDivider()
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(what, style = AppType.body, color = colors.text)
                            Text(outcome, style = AppType.secondary, color = if (outcome.startsWith("⚠")) colors.warn else colors.muted)
                        }
                    }
                }
            }
            s.note?.let { Text(it, style = AppType.small, color = colors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) }
        }
    }
}

/** "More for Harbor Lights S02E04", "More for Copper Crown": a row's ⋮ for TalkBack. */
internal fun moreFor(row: Download): String = "More for " + listOfNotNull(row.title, DownloadsPage.label(row)).joinToString(" ")

/** The Undo bar's time from [base], longer where TalkBack's settings ask for it, as the Collection's bar takes it. */
@Composable
internal fun undoTimeout(base: Long): Long {
    val a11y = LocalAccessibilityManager.current
    return remember(a11y, base) { a11y?.calculateRecommendedTimeoutMillis(base, containsIcons = false, containsText = true, containsControls = true) ?: base }
}

/** Short of room in the bar, as the Collection's: large text, or a narrow window. */
@Composable
internal fun compactBar(): Boolean {
    val density = LocalDensity.current
    val width = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    return density.fontScale >= 1.5f || width < 360.dp
}

@Composable
internal fun Tone.color(): Color = when (this) {
    Tone.Accent -> colors.accent
    Tone.Text -> colors.text
    Tone.Secondary -> colors.textSecondary
    Tone.Muted -> colors.muted
    Tone.Warn -> colors.warn
    Tone.WarnStrong -> colors.warnStrong
}

/** The app's page in Android's settings, where its battery and background use are set (L361). */
private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // A device with no such page: nothing to open.
    }
}

/** Material's disabled content alpha, as the app's greyed Play does. */
private const val DISABLED_ALPHA = 0.38f
