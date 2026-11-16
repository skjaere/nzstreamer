package io.skjaere.usenet.app.download

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.play.SheetTitle
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.DownloadIcon
import io.skjaere.usenet.app.ui.DownloadedIcon
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.colors

/**
 * A season's Download sheet (W114, L396, L400, L433), in one episode's sheet's look and parts: the season's aired
 * episodes not yet downloaded, each about how large, those counted apart, the space free and when the first starts; or
 * the episodes that fit, in order, when not all do; or that nothing was found for the season. [request] is what the Show
 * page's season Download hands it; [onClose] ends it: Cancel, Close, a swipe, or once Download has queued the season and
 * asked for its host. No Choose release: each episode's ⋮ keeps its own.
 *
 * Phones and tablets alone (W108, L441): where downloads don't show, it ends at once and shows nothing.
 */
@Composable
fun DownloadSeasonSheet(request: SeasonDownloadRequest, onClose: () -> Unit) {
    val container = LocalContext.current.container
    if (!DownloadPlaces.shown(LocalFormFactor.current, container.television)) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val ask = LocalNotificationAsk.current
    val context = LocalContext.current.applicationContext
    val deps = remember(container, ask) { DownloadSheetDeps.of(container, ask, context) }
    DownloadSeasonSheet(request, onClose, deps)
}

/** The season's sheet for [request] over [deps]: the app's, or a test's seams. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadSeasonSheet(request: SeasonDownloadRequest, onClose: () -> Unit, deps: DownloadSheetDeps) {
    // The sheet's own scope: its search goes when it does; what Download starts runs in the deps' own.
    val scope = rememberCoroutineScope()
    val model = remember(request, deps) { DownloadSeasonSheetModel(request, deps, scope) }
    LaunchedEffect(model) { model.start() }
    // Back from Android's storage page: the room made there read again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.shown() }
    val closed by model.closed.collectAsStateWithLifecycle()
    LaunchedEffect(closed) { if (closed) onClose() }
    if (closed) return
    ModalBottomSheet(
        onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        DownloadSeasonSheetContent(request, model, onClose)
    }
}

/**
 * What the season's sheet shows of [model] for [request]: its title, then Finding, the episodes and Download, Won't fit
 * or nothing found, scrolling at large font sizes; [onClose] ends it. A composable of its own, as the sheet's window lays
 * it out at the system's font size whatever a page's own says: a test lays this out at twice the size itself.
 */
@Composable
internal fun DownloadSeasonSheetContent(request: SeasonDownloadRequest, model: DownloadSeasonSheetModel, onClose: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val downloading by model.downloading.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp).verticalScroll(rememberScrollState())) {
        when (val s = state) {
            SeasonSheetState.Finding -> {
                SheetTitle(DownloadSheetModel.TITLE, request.subtitle)
                Finding(onCancel = onClose, words = DownloadSeasonSheetModel.finding(request.season))
            }
            is SeasonSheetState.Pick -> {
                SheetTitle(DownloadSheetModel.TITLE, request.title)
                Pick(request, s, downloading, onDownload = model::download, onCancel = onClose)
            }
            is SeasonSheetState.WontFit -> {
                SheetTitle(DownloadSheetModel.TITLE, request.title)
                WontFit(request, s, downloading, onDownload = model::download, onStorage = { openStorageSettings(context) }, onCancel = onClose)
            }
            is SeasonSheetState.NoRelease -> {
                SheetTitle(DownloadSheetModel.TITLE, request.subtitle)
                NoRelease(DownloadSeasonSheetModel.noRelease(request.season), s.stages, DownloadSeasonSheetModel.NO_RELEASE_NOTE, onClose)
            }
        }
    }
}

/**
 * All fit: the season's line, those counted apart, the episodes, the space free with why the sizes are estimates, when the
 * first starts, and on the first Download on Android 13 and later what Android asks next (L358); Download and Cancel,
 * Download waiting while one is under way ([busy]).
 */
@Composable
private fun Pick(request: SeasonDownloadRequest, s: SeasonSheetState.Pick, busy: Boolean, onDownload: () -> Unit, onCancel: () -> Unit) {
    Season(request, s.episodes, fit = s.episodes.size, cut = null)
    GroupCard(Modifier.padding(top = 12.dp)) {
        Line(DownloadSeasonSheetModel.free(s.free), sub = DownloadSeasonSheetModel.freeNote(s.episodes))
        InsetDivider()
        StartLine(s.start, DownloadSeasonSheetModel.start(s.start))
    }
    if (s.asks) AskLine()
    Buttons {
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DownloadButton(DownloadSeasonSheetModel.download(s), busy, onDownload)
            SecondaryButton("Cancel", onCancel, Modifier.fillMaxWidth())
        }
    }
}

/**
 * Not all fit (L400): the episodes that fit, then the others dimmed under the cut; with one or more that fit, the space
 * free and what they come to, Download for them, Android's storage page and Cancel; with none, what the first needs and
 * why less is free, what may fit, Android's storage page and Cancel.
 */
@Composable
private fun WontFit(
    request: SeasonDownloadRequest,
    s: SeasonSheetState.WontFit,
    busy: Boolean,
    onDownload: () -> Unit,
    onStorage: () -> Unit,
    onCancel: () -> Unit,
) {
    Season(request, s.episodes, fit = s.fit, cut = DownloadSeasonSheetModel.noRoom(s))
    val cross: @Composable () -> Unit = { Text("✕", style = AppType.bodyStrong, color = colors.warnStrong) }
    GroupCard(Modifier.padding(top = 12.dp)) {
        if (s.fit > 0) {
            Line(DownloadSeasonSheetModel.wontAllFit(s.free), colors.warnStrong, lead = cross, sub = DownloadSeasonSheetModel.firstFit(s), strong = true)
        } else {
            Line(DownloadSeasonSheetModel.WONT_FIT, colors.warnStrong, lead = cross, strong = true)
            InsetDivider()
            Line(DownloadSeasonSheetModel.firstNeeds(s), sub = DownloadSheetModel.after(s.queued, s.queuedDownloads))
        }
    }
    if (s.fit == 0) SheetNote(DownloadSeasonSheetModel.NONE_FITS_NOTE)
    if (s.asks) AskLine()
    Buttons {
        Column(Modifier.padding(top = if (s.fit == 0) 0.dp else 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DownloadSeasonSheetModel.download(s)?.let { DownloadButton(it, busy, onDownload) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(DownloadSheetModel.STORAGE_SETTINGS, onStorage, Modifier.weight(1f))
                SecondaryButton("Cancel", onCancel, Modifier.weight(1f))
            }
        }
    }
}

/** The sheet's Download, by the download mark, in [words]: waits while one is under way ([busy]). */
@Composable
private fun DownloadButton(words: String?, busy: Boolean, onDownload: () -> Unit) {
    words ?: return
    PrimaryButton(
        words, onDownload, Modifier.fillMaxWidth(), enabled = !busy,
        leading = { Icon(DownloadIcon, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(22.dp)) },
    )
}

/**
 * The season's line, the episodes counted apart, each kind by its mark, and the listed [episodes]: the first [fit] as
 * they are, the rest dimmed under the [cut]'s band ("✕ No room for these 3"); each row read as it shows.
 */
@Composable
private fun Season(request: SeasonDownloadRequest, episodes: List<SeasonEpisodeFound>, fit: Int, cut: String?) {
    Text(
        DownloadSeasonSheetModel.head(request.season, episodes), style = AppType.cardTitle, color = colors.text,
        modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
    )
    DownloadSeasonSheetModel.apart(request).forEach { (kind, words) -> Apart(kind, words) }
    GroupCard {
        episodes.forEachIndexed { i, e ->
            when {
                i == fit && cut != null -> Band(cut)
                // Under the band the dimmed rows' dividers run the card's width, as drawn.
                i > fit -> HorizontalDivider(thickness = 1.dp, color = colors.divider)
                i > 0 -> InsetDivider()
            }
            EpisodeLine(e, dimmed = i >= fit)
        }
    }
}

/** An episode counted apart: its line by its mark, the downloaded tick or the download's arrow; none for one not aired. */
@Composable
private fun Apart(kind: SeasonApart, words: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 10.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        when (kind) {
            SeasonApart.Downloaded -> DownloadedIcon
            SeasonApart.Queued -> DownloadIcon
            SeasonApart.NotAired -> null
        }?.let { Icon(it, contentDescription = null, tint = colors.muted, modifier = Modifier.size(18.dp)) }
        Text(words, style = AppType.secondary, color = colors.textSecondary, modifier = Modifier.weight(1f))
    }
}

/** The cut, over the episodes after it: "✕ No room for these 3", in the warning colours. */
@Composable
private fun Band(words: String) {
    Text(
        "✕ $words", style = AppType.small, color = colors.warn,
        modifier = Modifier.fillMaxWidth().background(colors.warnBg).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/**
 * A listed episode: its number, its name with what is said under it (failed before, above the Download size), and about
 * how large it is or that nothing is found yet; [dimmed] past the cut. Name and size share a line where they fit, and
 * the size goes under the name where they don't, as at twice the text size. Read as one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EpisodeLine(e: SeasonEpisodeFound, dimmed: Boolean) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 10.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            e.listed.play.episode.toString(), style = AppType.bodyStrong, color = if (dimmed) colors.muted else colors.textSecondary,
            modifier = Modifier.widthIn(min = 22.dp),
        )
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(e.listed.play.episodeName ?: "Episode ${e.listed.play.episode}", style = AppType.body, color = if (dimmed) colors.muted else colors.text)
                DownloadSeasonSheetModel.notes(e).forEach { Text(it, style = AppType.small, color = colors.warn) }
            }
            Text(
                DownloadSeasonSheetModel.size(e), style = AppType.secondary, color = if (dimmed) colors.muted else colors.textSecondary,
                modifier = Modifier.align(Alignment.CenterVertically).padding(start = 12.dp),
            )
        }
    }
}
