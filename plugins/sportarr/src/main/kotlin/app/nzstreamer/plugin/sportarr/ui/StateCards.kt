package app.nzstreamer.plugin.sportarr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.Footnote
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline

/*
 * The list's states as the phone and the TV draw them (L652): a card in the list's place with its one way on, as
 * Discover's; the line over a list kept through a failed read; and the blank cards while the first read runs.
 */

/**
 * A phone's card in the list's place: its title, its line, its one button ([buttonModifier]: its focus), and its footnote
 * under it.
 */
@Composable
internal fun PhoneStateCard(card: StateCard, onAction: (ListAction) -> Unit, modifier: Modifier = Modifier, buttonModifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 18.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface)
                .padding(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(card.title, style = AppType.cardTitle, color = colors.text)
            card.line?.let { Text(it, style = AppType.body, color = colors.textSecondary) }
            SecondaryButton(
                card.button, { onAction(card.action) },
                buttonModifier.padding(top = 4.dp).focusOutline(RoundedCornerShape(24.dp), color = colors.text, fill = false),
            )
        }
        card.foot?.let { Footnote(it) }
    }
}

/**
 * A TV's card in the list's place, Discover's TV card: 480 dp, centred in the column, its words centred, its button
 * ([buttonModifier]: its focus) the one stop; the footnote under it as wide.
 */
@Composable
internal fun TvStateCard(card: StateCard, onAction: (ListAction) -> Unit, modifier: Modifier = Modifier, buttonModifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.width(TV_CARD).clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(card.title, style = AppType.screenTitle, color = colors.text, textAlign = TextAlign.Center)
            card.line?.let { Text(it, style = AppType.body, color = colors.textSecondary, textAlign = TextAlign.Center) }
            SecondaryButton(card.button, { onAction(card.action) }, buttonModifier.padding(top = 14.dp))
        }
        card.foot?.let {
            Text(it, style = AppType.small, color = colors.muted, textAlign = TextAlign.Center, modifier = Modifier.width(TV_CARD).padding(top = 12.dp))
        }
    }
}

/**
 * A phone's line over the list kept: "Can't reach Sportarr · list from 3 h ago", and its button ([buttonModifier]: its
 * focus) at the line's end.
 */
@Composable
internal fun PhoneNotice(notice: Notice, onAction: (ListAction) -> Unit, modifier: Modifier = Modifier, buttonModifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(notice.line, style = AppType.small, color = colors.muted, modifier = Modifier.weight(1f))
        TextButton(
            onClick = { onAction(notice.action) },
            modifier = buttonModifier.heightIn(min = 48.dp).focusOutline(RoundedCornerShape(20.dp), fill = false),
        ) { Text(notice.button, style = AppType.bodyStrong.copy(fontSize = 14.sp), color = colors.accent) }
    }
}

/** A TV's line over the list kept: its button first ([buttonModifier]: its focus), one Down from the chips, then the line. */
@Composable
internal fun TvNotice(notice: Notice, onAction: (ListAction) -> Unit, modifier: Modifier = Modifier, buttonModifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SecondaryButton(notice.button, { onAction(notice.action) }, buttonModifier)
        Text(notice.line, style = AppType.secondary, color = colors.textSecondary, modifier = Modifier.weight(1f))
    }
}

/** While Sportarr is asked for the first time: a day's header and two cards' outlines, nothing in them to focus. */
@Composable
internal fun Blanks(tv: Boolean) {
    Column {
        Bar(if (tv) 90.dp else 70.dp, Modifier.padding(start = 16.dp, top = if (tv) 20.dp else 22.dp, bottom = 10.dp))
        repeat(2) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = if (tv) 12.dp else 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(colors.surface).padding(vertical = if (tv) 8.dp else 6.dp),
            ) {
                Bar(if (tv) 120.dp else 110.dp, Modifier.padding(start = if (tv) 16.dp else 12.dp, top = 8.dp, bottom = if (tv) 4.dp else 2.dp))
                (if (tv) listOf(300.dp, 360.dp) else listOf(220.dp, 250.dp, 190.dp)).forEach { width ->
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Bar(if (tv) 44.dp else 40.dp)
                        Bar(width, height = 14.dp)
                    }
                }
            }
        }
    }
}

/** A blank line of a card still to come, [width] wide at most. */
@Composable
private fun Bar(width: Dp, modifier: Modifier = Modifier, height: Dp = 12.dp) {
    Box(modifier.width(width).height(height).clip(RoundedCornerShape(height / 2)).background(colors.chip))
}

/** Discover's TV cards' width, in the middle of the column. */
private val TV_CARD = 480.dp
