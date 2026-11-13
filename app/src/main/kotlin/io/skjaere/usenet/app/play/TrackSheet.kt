package io.skjaere.usenet.app.play

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.player.SubtitlePlan
import io.skjaere.usenet.player.TrackOption

/**
 * What the audio and subtitles sheet lists, kept apart from its layout so it can be tested: the
 * file's audio tracks, then Off, Forced only (when the file has a forced track) and its subtitle
 * tracks, and "Reset to my defaults" while the show remembers a choice ([canReset]). The audio
 * playing and the subtitles showing ([subtitles], see TrackSelection.shownSubtitles) are current;
 * Forced only in a file without a forced track shows nothing, and is marked as Off. Until the
 * player knows the file's tracks ([loading]) it lists nothing: nothing to pick or reset, and
 * nothing current that a press could save as a choice.
 */
class TrackSheet(options: List<TrackOption>, subtitles: SubtitlePlan, canReset: Boolean, val loading: Boolean = false) {
    sealed interface Action {
        data class Audio(val option: TrackOption) : Action
        data class Subtitles(val plan: SubtitlePlan) : Action
        data object Reset : Action
    }

    /** [key] tells rows apart across updates; [enabled] false for tracks this device can't play. */
    data class Row(val key: String, val title: String, val details: String?, val enabled: Boolean, val current: Boolean, val action: Action)

    data class Section(val title: String, val rows: List<Row>)

    val sections: List<Section> = if (loading) emptyList() else buildList {
        val audio = options.filter { it.isAudio }
        if (audio.isNotEmpty()) add(Section("Audio", audio.map { o -> o.row("audio", o.selected, Action.Audio(o)) }))
        val text = options.filter { it.isText }
        val shown = (subtitles as? SubtitlePlan.Track)?.option
        val forced = text.any { it.forced }
        val off = subtitles == SubtitlePlan.Off || subtitles == SubtitlePlan.ForcedOnly && !forced
        add(
            Section(
                "Subtitles",
                listOfNotNull(
                    Row("off", "Off", null, true, off, Action.Subtitles(SubtitlePlan.Off)),
                    Row("forced", "Forced only", "Only parts in another language", true, subtitles == SubtitlePlan.ForcedOnly, Action.Subtitles(SubtitlePlan.ForcedOnly))
                        .takeIf { forced },
                ) + text.map { o -> o.row("text", shown != null && shown.groupIndex == o.groupIndex && shown.trackIndex == o.trackIndex, Action.Subtitles(SubtitlePlan.Track(o))) },
            ),
        )
        if (canReset) add(Section("", listOf(Row("reset", "Reset to my defaults", "Forget this show's audio and subtitles", true, false, Action.Reset))))
    }

    /** The row a TV remote starts on: the audio playing, else the first. */
    val focusFirst: Row? = sections.firstOrNull()?.rows?.let { rows -> rows.firstOrNull { it.current && it.action is Action.Audio } ?: rows.firstOrNull() }

    private fun TrackOption.row(prefix: String, current: Boolean, action: Action) =
        Row("$prefix:$groupIndex:$trackIndex", label, details, selectable, current, action)
}

/**
 * The audio and subtitles sheet, a [SidePanel]: a row applies its choice at once and the sheet stays, so audio and
 * subtitles can both be set; Back, or a tap beside the panel, closes it. On a TV remote it opens on the audio playing
 * and the D-pad goes through every row, those this device can't play included, so their reason can be read.
 */
@Composable
fun TrackSheetPanel(sheet: TrackSheet, onAction: (TrackSheet.Action) -> Unit, onClose: () -> Unit, safeArea: WindowInsets) {
    val first = remember { FocusRequester() }
    val keys = LocalInputModeManager.current
    // Bumped by Reset, whose row goes away: the focus goes back to the audio. So it does as the
    // rows come, if the sheet opened before the tracks were known.
    var refocus by remember { mutableIntStateOf(0) }
    LaunchedEffect(refocus, sheet.loading) { if (keys.inputMode == InputMode.Keyboard && sheet.focusFirst != null) first.requestFocus() }
    SidePanel("Audio and subtitles", onClose, safeArea) {
        if (sheet.loading) {
            Text("Loading tracks…", style = AppType.body, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        sheet.sections.forEach { section ->
            if (section.title.isEmpty()) {
                HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), thickness = 1.dp, color = colors.divider)
            } else {
                Text(
                    section.title.uppercase(), style = AppType.section, color = colors.muted,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp).semantics { heading() },
                )
            }
            section.rows.forEach { row ->
                SheetRow(row, if (row.key == sheet.focusFirst?.key) first else null) {
                    onAction(row.action)
                    if (row.action == TrackSheet.Action.Reset) refocus++
                }
            }
        }
    }
}

/**
 * One choice: a radio mark (filled while current, so not by colour alone), the label and the
 * line under it. At least 56 dp tall, growing with the font size. The focused row is outlined in
 * the accent colour for a TV remote.
 */
@Composable
private fun SheetRow(row: TrackSheet.Row, focus: FocusRequester?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val choice = row.action !is TrackSheet.Action.Reset
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 56.dp)
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged { focused = it.hasFocus }
            .clip(shape)
            .background(if (focused) colors.chip else Color.Transparent)
            .border(2.dp, if (focused) colors.accent else Color.Transparent, shape)
            .then(
                if (choice) {
                    Modifier.selectable(selected = row.current, enabled = row.enabled, role = Role.RadioButton, onClick = onClick)
                } else {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                },
            )
            // A row that can't be picked still takes the D-pad, so its reason can be read.
            .then(if (row.enabled) Modifier else Modifier.focusable())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (choice) RadioMark(row.current, row.enabled) else Box(Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.title, style = if (row.current) AppType.bodyStrong else AppType.body,
                color = if (row.enabled) colors.text else colors.muted,
            )
            row.details?.let { Text(it, style = AppType.small, color = if (row.enabled) colors.textSecondary else colors.muted) }
        }
    }
}

/** A ring, with a dot in it while current. */
@Composable
private fun RadioMark(current: Boolean, enabled: Boolean) {
    val tint = when {
        !enabled -> colors.outline
        current -> colors.accent
        else -> colors.muted
    }
    Box(Modifier.size(20.dp).clip(CircleShape).border(2.dp, tint, CircleShape).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        if (current) Box(Modifier.size(10.dp).clip(CircleShape).background(tint))
    }
}
