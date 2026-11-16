package io.skjaere.usenet.app.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.play.FileQuestion
import io.skjaere.usenet.app.play.FileRow
import io.skjaere.usenet.app.play.STEP_LABELS
import io.skjaere.usenet.app.play.SheetTitle
import io.skjaere.usenet.app.play.StepRow
import io.skjaere.usenet.app.search.SourceTagChip
import io.skjaere.usenet.app.search.sourceTag
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.core.PipelineStep
import io.skjaere.usenet.search.RankedItem

/**
 * Choose file's sheet over the release list in download mode (L403, L435), in the Download sheet's look: while [choice]'s
 * pack is fetched and opened, the play sheet's two steps with the release under them and Cancel; then its videos to
 * choose from, the play's file chooser's rows, greyed where one won't fit, and Cancel; or why the pack failed, with Try
 * another release and Close. A file tapped is [onPick]ed: the model takes it from there. [onCancel] (a swipe and Back
 * too) goes back to the list, as does [onTryAnother], where the pack is marked with why it failed; [onClose] goes back to
 * where the list was opened from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadFileChoiceSheet(
    choice: DownloadFileChoice,
    onCancel: () -> Unit,
    onTryAnother: () -> Unit,
    onClose: () -> Unit,
    onPick: (index: Int) -> Unit = choice::pick,
) {
    ModalBottomSheet(
        onDismissRequest = onCancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        DownloadFileChoiceContent(choice, onPick, onCancel, onTryAnother, onClose)
    }
}

/**
 * What Choose file's sheet shows of [choice]: the Download sheet's title and its subtitle, then the pack opening, its
 * files, or why it failed, scrolling at large font sizes. A composable of its own, as the sheet's window lays it out at the
 * system's font size whatever a page's own says: a test lays this out at twice the size itself.
 */
@Composable
internal fun DownloadFileChoiceContent(choice: DownloadFileChoice, onPick: (index: Int) -> Unit, onCancel: () -> Unit, onTryAnother: () -> Unit, onClose: () -> Unit) {
    val state by choice.state.collectAsStateWithLifecycle()
    Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp).verticalScroll(rememberScrollState())) {
        SheetTitle(DownloadSheetModel.TITLE, choice.subtitle)
        when (val s = state) {
            is DownloadFileChoice.State.Opening -> Opening(choice.release, s, onCancel)
            is DownloadFileChoice.State.Files -> Files(choice, s, onPick, onCancel)
            is DownloadFileChoice.State.Failed -> Failed(choice.release, s, onTryAnother, onClose)
        }
    }
}

/** The pack fetched, then opened, in the play sheet's step words; the release under them; what this does and doesn't; Cancel. */
@Composable
private fun Opening(release: RankedItem, s: DownloadFileChoice.State.Opening, onCancel: () -> Unit) {
    GroupCard {
        StepRow(STEP_LABELS.getValue(PipelineStep.FetchNzb), s.fetch)
        InsetDivider()
        StepRow(STEP_LABELS.getValue(PipelineStep.Open), s.open)
    }
    // As the play's running sheet has its release: what it is and where it comes from (the lists' tag, while a debrid
    // service is on), over its name; read as one.
    val container = LocalContext.current.container
    val accounts by remember(container) { container.serviceNames }.collectAsStateWithLifecycle(emptyList())
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(DownloadFileChoice.kind(release), style = AppType.small, color = colors.muted)
            sourceTag(release, accounts)?.let { SourceTagChip(it) }
        }
        Text(release.item.title, style = AppType.release, color = colors.chipText)
    }
    SheetNote(DownloadFileChoice.OPENING_NOTE)
    Buttons { SecondaryButton("Cancel", onCancel, Modifier.fillMaxWidth()) }
}

/**
 * The pack's videos: what is looked for and the question, the release, the room free, then the files, largest first, the
 * episode's named, one that won't fit greyed with what it needs; Cancel alone.
 */
@Composable
private fun Files(choice: DownloadFileChoice, s: DownloadFileChoice.State.Files, onPick: (index: Int) -> Unit, onCancel: () -> Unit) {
    FileQuestion(choice.lookingFor, choice.prompt, choice.release.item.title)
    Text(s.room, style = AppType.secondary, color = colors.muted, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp))
    GroupCard {
        s.files.forEachIndexed { i, option ->
            if (i > 0) InsetDivider()
            FileRow(option.file, Modifier, clickLabel = choice.tapLabel, wontFit = option.wontFit) { onPick(option.file.index) }
        }
    }
    Buttons { SecondaryButton("Cancel", onCancel, Modifier.padding(top = 12.dp).fillMaxWidth()) }
}

/** The pack failed: the words, its card ("✕ <reason>" over its name, read as one), the note, Try another release and Close. */
@Composable
private fun Failed(release: RankedItem, s: DownloadFileChoice.State.Failed, onTryAnother: () -> Unit, onClose: () -> Unit) {
    Text(DownloadFileChoice.FAILED, style = AppType.secondary, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp))
    GroupCard {
        Column(Modifier.fillMaxWidth().padding(12.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("✕ ${s.reason}", style = AppType.secondary, color = colors.warn)
            Text(release.item.title, style = AppType.release, color = colors.muted)
        }
    }
    SheetNote(DownloadFileChoice.FAILED_NOTE)
    Buttons {
        PrimaryButton(DownloadFileChoice.TRY_ANOTHER, onTryAnother, Modifier.fillMaxWidth())
        SecondaryButton("Close", onClose, Modifier.fillMaxWidth())
    }
}
