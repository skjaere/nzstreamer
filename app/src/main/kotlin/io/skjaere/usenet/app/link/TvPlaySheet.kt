package io.skjaere.usenet.app.link

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.skjaere.usenet.app.play.STEP_LABELS
import io.skjaere.usenet.app.play.SheetTitle
import io.skjaere.usenet.app.play.StepRow
import io.skjaere.usenet.app.play.cancelKeptClear
import io.skjaere.usenet.app.play.currentStep
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.ChipKind
import io.skjaere.usenet.app.ui.ChipRow
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.InfoChip
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.core.StepStatus
import io.skjaere.usenet.link.LinkMessage.Status
import io.skjaere.usenet.link.SheetKind
import io.skjaere.usenet.link.SheetStep

/**
 * The phone's own play sheet, fed by the TV (L37, PtPhoneStarting, PtPhoneStartFail): its look and rows, with what is
 * new in it. "Starting on Living room TV" under the title; the TV's lines in the TV's words; "Living room TV chose ·
 * release 1 of 3" above the release; "✓ Plays on Living room TV", the TV's own check (L10); a last row, Buffering, until
 * the TV's picture starts; "Choose the file on the TV" for a season pack, which the TV's remote answers. A start that
 * didn't start, or went nowhere, offers Close alone (W13): no Search and no Providers, which act on this phone.
 * [onMinimize] as it is tapped outside, swiped down or left with Back; [onCancel] asks the TV to stop; [onClose] puts
 * away a start that is over.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvPlaySheet(now: TvStartNow, onMinimize: () -> Unit, onCancel: () -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onMinimize, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.background, dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp).verticalScroll(rememberScrollState())) {
            SheetTitle(now.title, now.subtitle)
            val name = now.tvName
            val sheet = now.sheet
            when (val phase = now.phase) {
                TvPhase.Asking -> Starting(now, null, onCancel)
                is TvPhase.Starting, is TvPhase.Buffering, is TvPhase.Cancelling -> when (sheet?.kind) {
                    SheetKind.NoRelease -> NoRelease(name, sheet, onClose)
                    SheetKind.Failed -> Failed(name, sheet, onClose)
                    SheetKind.NoProvider -> Said(couldntStartOn(name) + " " + sheet.noProvider.orEmpty(), null, onClose)
                    SheetKind.Choosing -> Choosing(now, sheet, onCancel)
                    else -> Starting(now, sheet, onCancel)
                }
                TvPhase.NoAnswer -> {
                    OnTv(startingOn(name))
                    Said(didntAnswer(name), null, onClose)
                }
                is TvPhase.Lost -> Lost(name, sheet, now.rows, phase, onClose)
                is TvPhase.Rejected -> rejectedWords(phase.reason, name).let { (first, second) -> Said(first, second, onClose) }
                is TvPhase.NotSent -> notSentWords(name, phase.why).let { (first, second) -> Said(first, second, onClose) }
            }
        }
    }
}

/**
 * The mini bar for a start on a TV (PtPhoneStarting 5, PtPhoneStartFail): what starts, "On Living room TV · Checking
 * availability…" and Show, or "⚠ Couldn't play Harbor Lights on Living room TV". [windowInsets] as the app's own bar's.
 */
@Composable
fun TvMiniBar(now: TvStartNow, windowInsets: WindowInsets, onExpand: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(colors.surface).windowInsetsPadding(windowInsets).clickable(onClick = onExpand)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (now.over) {
            Text("⚠", style = AppType.bodyStrong, color = colors.warnStrong)
            Text(couldntPlayOn(now.title, now.tvName), style = AppType.bodyStrong, color = colors.text, maxLines = 1, modifier = Modifier.weight(1f))
        } else {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accent, trackColor = colors.outline)
            Column(Modifier.weight(1f)) {
                Text("Starting ${now.title}" + (now.subtitle?.let { " · $it" } ?: ""), style = AppType.bodyStrong, color = colors.text, maxLines = 1)
                Text(onTvStep(now.tvName, miniStep(now)), style = AppType.small, color = colors.muted, maxLines = 1)
            }
        }
        Text("Show", style = AppType.secondary, color = colors.accent)
    }
}

/** What the mini bar says the TV does now. */
internal fun miniStep(now: TvStartNow): String {
    val sheet = now.sheet
    return when {
        now.phase is TvPhase.Buffering -> "$BUFFERING…"
        now.phase is TvPhase.Cancelling -> "Stopping…"
        sheet?.kind == SheetKind.Choosing -> CHOOSE_ON_TV
        sheet?.kind == SheetKind.Searching -> sheet.line ?: "Starting…"
        sheet?.kind == SheetKind.Running -> mirroredSteps(sheet).let { steps -> currentStep(steps, mirroredRows(steps)) }?.let { STEP_LABELS.getValue(it) + "…" } ?: "Starting…"
        else -> "Starting…"
    }
}

/** Under the title, which TV the sheet speaks for, by the TV mark: the accent while it starts, the warning once lost. */
@Composable
private fun OnTv(words: String, warn: Boolean = false) {
    Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(TvIcon, contentDescription = null, tint = if (warn) colors.warnStrong else colors.accent, modifier = Modifier.size(18.dp))
        Text(words, style = AppType.secondary, color = if (warn) colors.warnStrong else colors.textSecondary)
    }
}

/**
 * A start the TV works on (PtPhoneStarting 1, 2, 4): the notice, the steps the TV's sheet shows and Buffering after them,
 * then the release the TV chose; Cancel, or "Stopping on Living room TV…" once it was pressed. With no sheet yet, the
 * first row waits for the TV.
 */
@Composable
private fun Starting(now: TvStartNow, sheet: Status.Sheet?, onCancel: () -> Unit) {
    val name = now.tvName
    OnTv(startingOn(name))
    sheet?.notice?.let { Text(it, style = AppType.secondary, color = colors.warn, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp)) }
    val running = sheet?.takeIf { it.kind == SheetKind.Running }
    val steps = running?.let(::mirroredSteps).orEmpty()
    // What the TV does first: searching, what its search found, or, before it says, the wait for it.
    val first: Pair<String, StepStatus>? = when {
        running != null -> running.line?.let { it to StepStatus.Done(0) }
        sheet == null || sheet.kind == SheetKind.Unknown -> waitingFor(name) to StepStatus.Running()
        else -> (sheet.line ?: waitingFor(name)) to StepStatus.Running()
    }
    GroupCard {
        first?.let { (line, status) -> StepRow(line, status) }
        // The steps the TV's own sheet lists: Usenet's, or a debrid service's, as its hello or its search said, or from
        // Preparing on.
        mirroredRows(steps, now.rows).forEachIndexed { i, step ->
            if (i > 0 || first != null) InsetDivider()
            StepRow(STEP_LABELS.getValue(step), steps[step])
        }
        InsetDivider()
        StepRow(BUFFERING, if (now.phase is TvPhase.Buffering) StepStatus.Running() else null)
    }
    running?.takeIf { it.release != null }?.let { Release(name, it) }
    Stop(now, onCancel)
}

/** The release the TV chose (W4), what it is, and the TV's own check that it plays there (L10). */
@Composable
private fun Release(name: String, sheet: Status.Sheet) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tvChose(name, sheet.attempt, sheet.of), style = AppType.small, color = colors.muted)
        Text(sheet.release.orEmpty(), style = AppType.release, color = colors.chipText)
        ChipRow {
            sheet.chips.forEach { InfoChip(it) }
            if (sheet.playsHere) InfoChip(playsOn(name), ChipKind.Good)
        }
    }
}

/** Cancel, or once pressed, "Stopping on Living room TV…" greyed with the spinner. */
@Composable
private fun Stop(now: TvStartNow, onCancel: () -> Unit) {
    val modifier = Modifier.padding(horizontal = 16.dp).padding(top = 4.dp).fillMaxWidth().cancelKeptClear()
    if (now.phase is TvPhase.Cancelling) {
        OutlinedButton(
            onClick = {}, enabled = false, modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline.copy(alpha = 0.6f)),
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.accent, trackColor = colors.outline)
            Text(stoppingOn(now.tvName), style = AppType.body, modifier = Modifier.padding(start = 10.dp))
        }
    } else {
        SecondaryButton("Cancel", onCancel, modifier)
    }
}

/** A season pack's question (PtPhoneStarting 3): the TV shows its files, and its remote chooses; the phone waits. */
@Composable
private fun Choosing(now: TvStartNow, sheet: Status.Sheet, onCancel: () -> Unit) {
    val name = now.tvName
    OnTv(startingOn(name))
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text(CHOOSE_ON_TV, style = AppType.cardTitle, color = colors.text, modifier = Modifier.semantics { heading() })
        sheet.lookingFor?.let { Text(it, style = AppType.secondary, color = colors.textSecondary, modifier = Modifier.padding(top = 4.dp)) }
        Text(choosingOn(name), style = AppType.secondary, color = colors.textSecondary, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
        sheet.release?.let { Text(it, style = AppType.release, color = colors.muted, modifier = Modifier.padding(bottom = 14.dp)) }
    }
    GroupCard {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(56.dp).heightIn(min = 36.dp).border(2.dp, colors.outline, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                Icon(TvIcon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
            }
            Text(WAITING_FOR_THE_TV, style = AppType.secondary, color = colors.textSecondary)
        }
    }
    Box(Modifier.padding(top = 16.dp)) { Stop(now, onCancel) }
}

/** No release on the TV's indexers (PtPhoneStartFail 1): what each of its searches did, the note, what it hid; Close. */
@Composable
private fun NoRelease(name: String, sheet: Status.Sheet, onClose: () -> Unit) {
    var showHidden by rememberSaveable { mutableStateOf(false) }
    Text(noReleaseOn(name), style = AppType.cardTitle, color = colors.text, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp))
    if (sheet.stages.isNotEmpty()) {
        GroupCard {
            sheet.stages.forEachIndexed { i, stage ->
                if (i > 0) InsetDivider()
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stage.what, style = AppType.body, color = colors.text)
                    Text(stage.why, style = AppType.secondary, color = if (stage.why.startsWith("⚠")) colors.warn else colors.muted)
                }
            }
        }
    }
    sheet.note?.let { Text(it, style = AppType.small, color = colors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (sheet.hidden.isNotEmpty()) {
            SecondaryButton(if (showHidden) "Hide the ${sheet.hiddenCount} found" else "Show the ${sheet.hiddenCount} found", { showHidden = !showHidden }, Modifier.fillMaxWidth())
        }
        if (showHidden) GroupCard(Modifier.padding(horizontal = 0.dp)) {
            sheet.hidden.forEachIndexed { i, hidden ->
                if (i > 0) InsetDivider()
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(hidden.title, style = AppType.release, color = colors.muted)
                    ChipRow { hidden.reasons.forEach { InfoChip(it, if (it.startsWith("⚠")) ChipKind.Warn else if (it.startsWith("✓")) ChipKind.Good else ChipKind.Plain) } }
                }
            }
            val more = sheet.hiddenCount - sheet.hidden.size
            if (more > 0) Text("and $more more", style = AppType.small, color = colors.muted, modifier = Modifier.padding(12.dp))
        }
        PrimaryButton("Close", onClose, Modifier.fillMaxWidth())
    }
}

/** Each release the TV tried failed (PtPhoneStartFail 3), in the TV's words, then those it skipped and left; Close. */
@Composable
private fun Failed(name: String, sheet: Status.Sheet, onClose: () -> Unit) {
    val header = if (sheet.failures.isEmpty()) couldntStartOn(name) else eachFailedOn(name, sheet.failures.size)
    Text(header, style = AppType.secondary, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp))
    GroupCard {
        sheet.failures.forEachIndexed { i, failure ->
            if (i > 0) InsetDivider()
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("✕ ${failure.why}", style = AppType.secondary, color = colors.warn)
                Text(failure.what, style = AppType.release, color = colors.muted)
            }
        }
        sheet.skippedBefore.forEachIndexed { i, skipped ->
            if (i > 0 || sheet.failures.isNotEmpty()) InsetDivider()
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(skipped.why, style = AppType.secondary, color = colors.muted)
                Text(skipped.what, style = AppType.release, color = colors.muted)
            }
        }
    }
    if (sheet.skipped > 0) Text(skippedOnTv(sheet.skipped), style = AppType.small, color = colors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
    notTriedOnTv(sheet.notTried, sheet.noRoute).forEach { line ->
        Text(line, style = AppType.small, color = colors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
    }
    PrimaryButton("Close", onClose, Modifier.padding(horizontal = 16.dp).padding(top = 8.dp).fillMaxWidth())
}

/**
 * No word from the TV, its link down (PtPhoneStartFail 5): it may still start there. The steps stop where the TV was
 * last heard, the one that ran then "no answer"; Close.
 */
@Composable
private fun Lost(name: String, sheet: Status.Sheet?, rows: List<SheetStep>, lost: TvPhase.Lost, onClose: () -> Unit) {
    OnTv(lostConnection(name), warn = true)
    Text(LOST_TEXT, style = AppType.secondary, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp))
    val steps = sheet?.takeIf { it.kind == SheetKind.Running }?.let(::mirroredSteps).orEmpty()
    GroupCard {
        sheet?.line?.let {
            StepRow(it, if (sheet.kind == SheetKind.Running) StepStatus.Done(0) else null)
            InsetDivider()
        }
        mirroredRows(steps, rows).forEachIndexed { i, step ->
            if (i > 0) InsetDivider()
            val status = steps[step]
            if (status is StepStatus.Running) Unheard(STEP_LABELS.getValue(step)) else StepRow(STEP_LABELS.getValue(step), status)
        }
        InsetDivider()
        StepRow(BUFFERING, null)
    }
    Text(lastHeard(lost.heardAgoMs), style = AppType.small, color = colors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
    PrimaryButton("Close", onClose, Modifier.padding(horizontal = 16.dp).fillMaxWidth())
}

/** A step that ran as the TV was last heard: "–" and "no answer", its time stopped. */
@Composable
private fun Unheard(label: String) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) { Text("–", style = AppType.bodyStrong, color = colors.muted) }
        Text(label, style = AppType.body, color = colors.muted, modifier = Modifier.weight(1f))
        Text(NO_ANSWER, style = AppType.secondary, color = colors.muted, modifier = Modifier.padding(start = 8.dp))
    }
}

/** What happened, what to do next if anything, and Close. */
@Composable
private fun Said(first: String, second: String?, onClose: () -> Unit) {
    Text(first, style = AppType.body, color = colors.text, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 6.dp))
    second?.let { Text(it, style = AppType.secondary, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 14.dp)) }
    PrimaryButton("Close", onClose, Modifier.padding(horizontal = 16.dp).padding(top = 8.dp).fillMaxWidth())
}

/** The failure sheet's note on releases from an indexer that refused a download, there. */
internal fun skippedOnTv(count: Int) = "Skipped $count more release${if (count == 1) "" else "s"} from an indexer on the TV that refused a download."

/**
 * The failure sheet's notes on the [notTried] releases left, without Choose release's way to them, which plays on this
 * phone: those left to save the TV's indexer's allowance, and apart from them the [noRoute] left as what they play from
 * failed on the TV (#316), as the TV's own sheet splits them.
 */
internal fun notTriedOnTv(notTried: Int, noRoute: Int): List<String> {
    val saved = notTried - noRoute
    return listOfNotNull(
        "$saved more release${if (saved == 1) "" else "s"} not tried, to save the TV's indexer's download allowance.".takeIf { saved > 0 },
        (if (noRoute == 1) "1 more release not tried: what it plays from failed on the TV." else "$noRoute more releases not tried: what they play from failed on the TV.")
            .takeIf { noRoute > 0 },
    )
}
