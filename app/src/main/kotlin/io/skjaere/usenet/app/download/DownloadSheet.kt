package io.skjaere.usenet.app.download

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.play.SheetTitle
import io.skjaere.usenet.app.play.StepRow
import io.skjaere.usenet.app.search.Dot
import io.skjaere.usenet.app.search.ReasonChips
import io.skjaere.usenet.app.search.ReleaseList
import io.skjaere.usenet.app.search.ReleaseListMode
import io.skjaere.usenet.app.search.SourceTagChip
import io.skjaere.usenet.app.search.sourceTag
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.DownloadIcon
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.core.StepStatus
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup

/**
 * The Download sheet for one episode or film (L338, L358, L370), in the play sheet's look: what its search ranked first
 * for downloading, the space free and when it would start, or why it won't fit, or that nothing was found. [request] is
 * what an episode's ⋮, a film's page and Search's movie card hand it; [onClose] ends it: Cancel, Close, a swipe, or once
 * Download has queued the download and asked for its host. Choose release opens the release list ranked for downloading
 * over the page; a tap there downloads that release and ends both, and Back comes back to the sheet.
 *
 * Phones and tablets alone (W108, L369): where downloads don't show, it ends at once and shows nothing.
 */
@Composable
fun DownloadSheet(request: DownloadRequest, onClose: () -> Unit) {
    val container = LocalContext.current.container
    if (!DownloadPlaces.shown(LocalFormFactor.current, container.television)) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val ask = LocalNotificationAsk.current
    val context = LocalContext.current.applicationContext
    val deps = remember(container, ask) { DownloadSheetDeps.of(container, ask, context) }
    DownloadSheet(request, onClose, deps)
}

/** The sheet for [request] over [deps]: the app's, or a test's seams. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadSheet(request: DownloadRequest, onClose: () -> Unit, deps: DownloadSheetDeps) {
    // The sheet's own scope: its search goes when it does; what Download starts runs in the deps' own.
    val scope = rememberCoroutineScope()
    val model = remember(request, deps) { DownloadSheetModel(request, deps, scope) }
    LaunchedEffect(model) { model.start() }
    // Back from Android's storage page: the room made there read again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.shown() }
    val state by model.state.collectAsStateWithLifecycle()
    val closed by model.closed.collectAsStateWithLifecycle()
    var listing by remember(model) { mutableStateOf(false) }
    LaunchedEffect(closed) { if (closed) onClose() }
    if (closed) return
    val listed = when (val s = state) {
        is DownloadSheetState.Pick -> s.group to s.mode
        is DownloadSheetState.WontFit -> s.group to s.mode
        else -> null
    }
    if (listing && listed != null) {
        DownloadReleaseList(listed.first, request.title, listed.second, onBack = { listing = false })
        return
    }
    ModalBottomSheet(
        onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        DownloadSheetContent(request, model, onChoose = { listing = true }, onClose = onClose)
    }
}

/**
 * What the sheet shows of [model] for [request]: its title, then Finding, the pick, Won't fit or no release, scrolling at
 * large font sizes; [onChoose] opens Choose release's list, [onClose] ends it. A composable of its own, as the sheet's
 * window lays it out at the system's font size whatever a page's own says: a test lays this out at twice the size itself.
 */
@Composable
internal fun DownloadSheetContent(request: DownloadRequest, model: DownloadSheetModel, onChoose: () -> Unit, onClose: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val downloading by model.downloading.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp).verticalScroll(rememberScrollState())) {
        SheetTitle(DownloadSheetModel.TITLE, request.subtitle)
        when (val s = state) {
            DownloadSheetState.Finding -> Finding(onCancel = onClose)
            is DownloadSheetState.Pick -> Pick(s, downloading, onDownload = model::download, onChoose = onChoose, onCancel = onClose)
            is DownloadSheetState.WontFit -> WontFit(s, onChoose = onChoose, onStorage = { openStorageSettings(context) }, onCancel = onClose)
            is DownloadSheetState.NoRelease -> NoRelease(s, onClose = onClose)
        }
    }
}

/** While it searches: the chooser's words, what the sheet does and doesn't do, and Cancel. */
@Composable
private fun Finding(onCancel: () -> Unit) {
    GroupCard { StepRow(DownloadSheetModel.FINDING, StepStatus.Running()) }
    SheetNote(DownloadSheetModel.FINDING_NOTE)
    Buttons { SecondaryButton("Cancel", onCancel, Modifier.fillMaxWidth()) }
}

/**
 * The pick: the release, the space free with where its size comes from, when it starts, where downloads stay, and on the
 * first Download on Android 13 and later what Android asks next (L358). Download alone on top, Choose release and Cancel
 * under it; the first two wait while a Download is under way ([busy]).
 */
@Composable
private fun Pick(s: DownloadSheetState.Pick, busy: Boolean, onDownload: () -> Unit, onChoose: () -> Unit, onCancel: () -> Unit) {
    Release(s.best, s.need, s.mode)
    GroupCard {
        Line(DownloadSheetModel.free(s.free), sub = DownloadSheetModel.INDEXERS_SIZE)
        InsetDivider()
        StartLine(s.start)
    }
    SheetNote(DownloadSheetModel.STAYS)
    if (s.asks) AskLine()
    Buttons {
        PrimaryButton(
            DownloadSheetModel.TITLE, onDownload, Modifier.fillMaxWidth(), enabled = !busy,
            leading = { Icon(DownloadIcon, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(22.dp)) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(DownloadSheetModel.CHOOSE_RELEASE, onChoose, Modifier.weight(1f), enabled = !busy)
            SecondaryButton("Cancel", onCancel, Modifier.weight(1f))
        }
    }
}

/**
 * The pick doesn't fit: no Download; what it needs and why less is free; on the first Download on Android 13 and later what
 * Android asks next, as a pick in Choose release's list downloads (L358); Choose release, Android's storage page, Cancel.
 */
@Composable
private fun WontFit(s: DownloadSheetState.WontFit, onChoose: () -> Unit, onStorage: () -> Unit, onCancel: () -> Unit) {
    Release(s.best, s.need, s.mode)
    GroupCard {
        Line(DownloadSheetModel.WONT_FIT, colors.warnStrong, lead = { Text("✕", style = AppType.bodyStrong, color = colors.warnStrong) }, strong = true)
        InsetDivider()
        Line(DownloadSheetModel.needs(s.need, s.free), sub = DownloadSheetModel.after(s.queued, s.queuedDownloads))
    }
    SheetNote(DownloadSheetModel.WONT_FIT_NOTE)
    if (s.asks) AskLine()
    Buttons {
        PrimaryButton(DownloadSheetModel.CHOOSE_RELEASE, onChoose, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(DownloadSheetModel.STORAGE_SETTINGS, onStorage, Modifier.weight(1f))
            SecondaryButton("Cancel", onCancel, Modifier.weight(1f))
        }
    }
}

/** Nothing to download: what each search of the indexers did, as the play sheet's no-release says it, and Close. */
@Composable
private fun NoRelease(s: DownloadSheetState.NoRelease, onClose: () -> Unit) {
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
    s.note?.let { SheetNote(it) }
    Buttons { SecondaryButton("Close", onClose, Modifier.fillMaxWidth()) }
}

/**
 * The release: "Best match" and where it comes from (the lists' tag, while a debrid service is on), its quality with what
 * it needs here ("about 1.2 GB"), its name, a season pack's share of the episode, and its chips as the list has them.
 */
@Composable
private fun Release(item: RankedItem, need: Long, mode: ReleaseListMode.Download) {
    val container = LocalContext.current.container
    val accounts by remember(container) { container.serviceNames }.collectAsStateWithLifecycle(emptyList())
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(DownloadSheetModel.BEST, style = AppType.small.copy(fontWeight = FontWeight.SemiBold), color = colors.accent)
            sourceTag(item, accounts)?.let { SourceTagChip(it) }
        }
        Text(DownloadSheetModel.quality(item, need), style = AppType.cardTitle, color = colors.text)
        Text(item.item.title, style = AppType.release, color = colors.chipText)
        mode.packLine(item)?.let { Text(it, style = AppType.secondary, color = colors.textSecondary) }
        ReasonChips(item, bitrate = true)
    }
}

/**
 * When it starts, by its dot: now in the good colour; on Wi-Fi, once online, on the charger or resumed in the warning
 * colour; after the queue muted.
 */
@Composable
private fun StartLine(start: DownloadStart) {
    val (words, sub) = DownloadSheetModel.start(start)
    val (color, dot) = when (start) {
        DownloadStart.Now -> colors.good to colors.goodDot
        DownloadStart.OnWifi, is DownloadStart.Online, DownloadStart.Charging, DownloadStart.Held -> colors.warn to colors.warnStrong
        is DownloadStart.AfterQueue -> colors.textSecondary to colors.muted
    }
    Line(words, color, lead = { Dot(dot) }, sub = sub)
}

/** A line in the sheet's card: [lead] (a dot, or ✕) before its [words], a small muted [sub] under both; read as one. */
@Composable
private fun Line(words: String, color: Color = colors.text, lead: (@Composable () -> Unit)? = null, sub: String? = null, strong: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // By the words' first line: as tall as it, at any font size.
            lead?.let { Box(Modifier.heightIn(min = 20.dp), contentAlignment = Alignment.Center) { it() } }
            Text(words, style = if (strong) AppType.bodyStrong else AppType.body, color = color, modifier = Modifier.weight(1f))
        }
        sub?.let { Text(it, style = AppType.small, color = colors.muted) }
    }
}

/** What Android asks next, on the first Download on Android 13 and later (L358), by the bell. */
@Composable
private fun AskLine() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 10.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.Notifications, contentDescription = null, tint = colors.accent, modifier = Modifier.padding(top = 1.dp).size(18.dp))
        Text(DownloadSheetModel.ASKS, style = AppType.secondary, color = colors.textSecondary, modifier = Modifier.weight(1f))
    }
}

/** A small muted note, at the sheet's inset. */
@Composable
private fun SheetNote(text: String) {
    Text(text, style = AppType.small, color = colors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
}

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

/**
 * Choose release from the sheet (L355, L386): the release list in download [mode], over the page as a page of its own;
 * Back, or its arrow, comes back to the sheet ([onBack]).
 */
@Composable
private fun DownloadReleaseList(group: ResultGroup, title: String, mode: ReleaseListMode.Download, onBack: () -> Unit) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding()) {
            ReleaseList(group, title, onBack = onBack, mode = mode)
        }
    }
}

/** Android's page for making room (ACTION_MANAGE_STORAGE), or its storage settings where a phone has no such page. */
private fun openStorageSettings(context: Context) {
    val pages = listOf(Intent(StorageManager.ACTION_MANAGE_STORAGE), Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
    for (page in pages) {
        if (context !is Activity) page.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(page)
            return
        } catch (e: ActivityNotFoundException) {
            // The next one.
        }
    }
}
