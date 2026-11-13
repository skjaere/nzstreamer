package io.skjaere.usenet.app.play

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.skjaere.usenet.app.casting.CastPrompt
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.tryFocus

/** The prompt's first line: "This release is missing parts (3 so far)". */
internal fun missingPartsHeading(count: Int) = "This release is missing parts ($count so far)"

/** The heading as TalkBack says it, without the brackets: "This release is missing parts, 3 so far". */
internal fun missingPartsSpoken(count: Int) = "This release is missing parts, $count so far"

internal const val MISSING_PARTS_QUESTION = "Try another release? It starts where you are."

/**
 * The player's prompt for a release found missing parts ([MissingParts]): "This release is missing parts (3 so far)",
 * "Try another release? It starts where you are.", Keep watching and Try another release (QlPhonePrompt, QlTvPromptB);
 * with no other release to offer, the count alone, which hides by itself (QlTvPromptNoOther). It sits where the
 * next-episode card does, styled like the "Playback stopped" panel; [compact] (while the controls show) is one row, as
 * the card's (QlPhonePromptControls). A polite live region, read once as it appears; the count as said in words. Its
 * surface takes its taps ([takesTaps]).
 *
 * Keep watching takes a remote's focus each time [takeFocus] turns true (W6: the controls hidden), the next OK then
 * keeps watching rather than pause. From then, an OK pressed within [OkGuard]'s moment, or a held OK's repeats, press
 * nothing: an OK meant to pause, as the prompt appears, isn't taken for an answer. At a large font the words scroll and the
 * buttons stay whole, one under the other where they don't fit side by side. [keepWatching] is its button's requester.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MissingPartsPrompt(
    prompt: MissingParts.Prompt,
    compact: Boolean,
    takeFocus: Boolean,
    onKeepWatching: () -> Unit,
    onTryAnother: () -> Unit,
    modifier: Modifier = Modifier,
    keepWatching: FocusRequester = remember { FocusRequester() },
) {
    val heading = missingPartsHeading(prompt.count)
    val surface = modifier.clip(RoundedCornerShape(16.dp)).background(colors.surface.copy(alpha = 0.94f)).takesTaps().semantics { liveRegion = LiveRegionMode.Polite }
    if (!prompt.tryAnother) {
        Text(
            heading, style = AppType.small.copy(fontWeight = FontWeight.SemiBold), color = colors.warnStrong,
            modifier = surface.padding(horizontal = 12.dp, vertical = 10.dp).semantics { contentDescription = missingPartsSpoken(prompt.count) },
        )
        return
    }
    // A new guard each time it takes the focus: the press it keeps away is one made as the focus came.
    var focusTaken by remember { mutableIntStateOf(0) }
    val guard = remember(focusTaken) { OkGuard(SystemClock.uptimeMillis()) }
    LaunchedEffect(takeFocus) {
        if (takeFocus && keepWatching.tryFocus()) focusTaken++
    }
    val buttons = @Composable {
        CardButton("Keep watching", onKeepWatching, Modifier.focusRequester(keepWatching), filled = false, horizontalPadding = 16.dp)
        CardButton("Try another release", onTryAnother, Modifier, filled = true, horizontalPadding = 16.dp)
    }
    if (compact) {
        Row(
            surface.okGuard(guard).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f, fill = false)) {
                Text(heading, style = AppType.small.copy(fontWeight = FontWeight.SemiBold), color = colors.warnStrong, modifier = Modifier.semantics { contentDescription = missingPartsSpoken(prompt.count) })
                Text(MISSING_PARTS_QUESTION, style = AppType.small, color = colors.textSecondary)
            }
            buttons()
        }
    } else {
        Column(surface.okGuard(guard).width(300.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Weighted, so the buttons are measured first and the words get what is left, as Playback stopped's.
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(heading, style = AppType.small.copy(fontWeight = FontWeight.SemiBold), color = colors.warnStrong, modifier = Modifier.semantics { contentDescription = missingPartsSpoken(prompt.count) })
                Text(MISSING_PARTS_QUESTION, style = AppType.bodyStrong.copy(fontSize = 14.sp), color = colors.text)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { buttons() }
        }
    }
}

/**
 * The player's prompt for missing parts during a cast (W37, L94, L104; CsPhoneMissingParts): the count, the cast's line
 * under it ([CastPrompt.line]: the device may show glitches, or is stuck at one), the question, then Keep watching and Try
 * another release; with no other release, the count and the line alone, which hide by themselves. Over the remote's
 * picture on the player's surface, which grows to hold it whole, or above the bar on the app's other screens on
 * [background] (the chip's colour there), [withinHeight]: there it has a height it mustn't pass, and at a large font its
 * words scroll and its buttons stay whole, as the player's do. A polite live region, read once as it appears; the count as
 * said in words. Its surface takes its taps.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CastMissingPartsPrompt(
    prompt: CastPrompt,
    onKeepWatching: () -> Unit,
    onTryAnother: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = colors.surface.copy(alpha = 0.94f),
    withinHeight: Boolean = false,
) {
    val surface = modifier.clip(RoundedCornerShape(16.dp)).background(background).takesTaps().semantics { liveRegion = LiveRegionMode.Polite }
    val scrolls = if (withinHeight) Modifier.verticalScroll(rememberScrollState()) else Modifier
    val words = @Composable {
        Text(
            missingPartsHeading(prompt.count), style = AppType.small.copy(fontWeight = FontWeight.SemiBold), color = colors.warnStrong,
            modifier = Modifier.semantics { contentDescription = missingPartsSpoken(prompt.count) },
        )
        Text(prompt.line, style = AppType.secondary, color = colors.textSecondary)
    }
    if (!prompt.tryAnother) {
        Column(surface.padding(horizontal = 12.dp, vertical = 10.dp).then(scrolls), verticalArrangement = Arrangement.spacedBy(2.dp)) { words() }
        return
    }
    Column(surface.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Within a height, weighted, so the buttons are measured first and the words get what is left, as the player's.
        Column(if (withinHeight) Modifier.weight(1f, fill = false).then(scrolls) else Modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            words()
            Text(MISSING_PARTS_QUESTION, style = AppType.bodyStrong.copy(fontSize = 14.sp), color = colors.text)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardButton("Keep watching", onKeepWatching, Modifier, filled = false, horizontalPadding = 16.dp)
            CardButton("Try another release", onTryAnother, Modifier, filled = true, horizontalPadding = 16.dp)
        }
    }
}
