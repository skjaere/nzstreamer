package app.nzstreamer.plugin.sportarr.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.OwnedRipple
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.app.ui.focusScale
import io.skjaere.usenet.app.ui.turnsOver

/*
 * The list's rows, as a show's episode rows and Home's Up next draw theirs, without a picture (L653): a day's card per
 * league, its events' and weekends' rows, "+3 more", and the tab's Up next. A phone's and a TV's, the TV's a little
 * taller and turned over when focused. Each takes a modifier for its focus ([element]: the list's own keys), put before
 * its clickable.
 */

/**
 * A league's card on one day, on a phone: the league as a small heading, its rows between dividers, and "+3 more" or
 * "Show fewer" last. [element] gives each row's and the more line's focus.
 */
@Composable
internal fun PhoneLeagueCard(league: LeagueView, element: (String) -> Modifier, onOpen: (RowView) -> Unit, onMore: () -> Unit) {
    GroupCard(Modifier.padding(bottom = 10.dp)) {
        LeagueHeading(league.name, Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 2.dp))
        league.rows.forEachIndexed { index, row ->
            key(row.key) {
                if (index > 0) InsetDivider()
                PhoneEventRow(row, element(ListKeys.row(row.key))) { onOpen(row) }
            }
        }
        league.more?.let { more ->
            InsetDivider()
            PhoneMoreRow(more, element(ListKeys.more(league.key)), onMore)
        }
    }
}

/** A league's card on one day, on a TV: its heading, its rows 5 dp in from the card's edge, then its more line. */
@Composable
internal fun TvLeagueCard(league: LeagueView, element: (String) -> Modifier, onOpen: (RowView) -> Unit, onMore: () -> Unit) {
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface)
            .padding(bottom = 3.dp),
    ) {
        LeagueHeading(league.name, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp))
        league.rows.forEach { row -> key(row.key) { TvEventRow(row, element(ListKeys.row(row.key))) { onOpen(row) } } }
        league.more?.let { TvMoreRow(it, element(ListKeys.more(league.key)), onMore) }
    }
}

/** The league's name over its rows: small and strong, in the secondary text's colour. */
@Composable
private fun LeagueHeading(name: String, modifier: Modifier) {
    Text(name, style = AppType.bodyStrong.copy(fontSize = 13.sp, lineHeight = 18.sp), color = colors.textSecondary, modifier = modifier)
}

/**
 * An event's or weekend's row on a phone: its start in a column, its title, and under the title its line, a card's parts,
 * and what is left of a play begun, in the accent over its 2 dp bar; the ✓ of a watched one in the green, as an episode
 * has it. One still to start is quieter. A tap opens its page.
 */
@Composable
internal fun PhoneEventRow(row: RowView, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).focusOutline().clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                row.time, style = AppType.secondary.copy(fontWeight = FontWeight.Medium), color = if (row.upcoming) colors.muted else colors.textSecondary,
                modifier = Modifier.widthIn(min = PHONE_TIME).alignByBaseline(),
            )
            Column(Modifier.weight(1f).alignByBaseline(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.title, style = AppType.body, color = if (row.upcoming) colors.textSecondary else colors.text)
                row.line?.let { Text(it, style = AppType.small, color = colors.muted) }
                if (row.parts.isNotEmpty()) PartLabels(row.parts, turned = false)
                row.left?.let { left ->
                    Text(left, style = AppType.small, color = colors.accent)
                    row.progress?.let { Bar(it, colors.accent, colors.divider, 2.dp) }
                }
            }
        }
        if (row.watched) Text(Words.SportsList.TICK, style = AppType.bodyStrong, color = colors.good)
    }
}

/**
 * An event's or weekend's row on a TV, as a show's TV episode rows: turned over while focused (cream, its words dark),
 * grown a little, 5 dp in from the card's edge; its line and what is left on one line under the title, what is left in
 * the accent; a card's parts; the ✓ of a watched one. OK opens its page.
 */
@Composable
internal fun TvEventRow(row: RowView, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val turned = interaction.turnsOver()
    val accent = if (turned) colors.turnedAccent else colors.accent
    val quiet = if (turned) colors.turnedSecondary else colors.muted
    OwnedRipple {
        Row(
            modifier.padding(start = 5.dp, end = 8.dp, top = 3.dp).fillMaxWidth().heightIn(min = TV_ROW).focusScale(interaction, { it.rowScale })
                .clip(RoundedCornerShape(10.dp)).then(if (turned) Modifier.background(colors.text) else Modifier)
                .clickable(interaction, LocalIndication.current, role = Role.Button, onClick = onClick).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    row.time, style = AppType.secondary.copy(fontWeight = FontWeight.Medium),
                    color = when {
                        turned -> colors.background
                        row.upcoming -> colors.muted
                        else -> colors.textSecondary
                    },
                    modifier = Modifier.widthIn(min = TV_TIME).alignByBaseline(),
                )
                Column(Modifier.weight(1f).alignByBaseline(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        row.title, style = AppType.body,
                        color = when {
                            turned -> colors.background
                            row.upcoming -> colors.textSecondary
                            else -> colors.text
                        },
                    )
                    if (row.line != null || row.left != null) {
                        // One line, as a TV's episode rows have it: what is left at its end in the accent.
                        val line = Words.line(row.line, row.left)
                        val leftFrom = line.length - row.left.orEmpty().length
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = quiet)) { append(line.substring(0, leftFrom)) }
                                withStyle(SpanStyle(color = accent)) { append(line.substring(leftFrom)) }
                            },
                            style = AppType.secondary,
                        )
                    }
                    if (row.parts.isNotEmpty()) PartLabels(row.parts, turned)
                    if (row.left != null) row.progress?.let { Bar(it, accent, if (turned) TURNED_TRACK else colors.divider, 2.dp) }
                }
            }
            if (row.watched) Text(Words.SportsList.TICK, style = AppType.bodyStrong, color = accent)
        }
    }
}

/** A card's parts as small labels in card order, a watched one's ✓ in the green before its name. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PartLabels(parts: List<PartLabel>, turned: Boolean) {
    FlowRow(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        parts.forEach { part ->
            Row(
                Modifier.heightIn(min = 22.dp).clip(RoundedCornerShape(6.dp)).background(if (turned) TURNED_LABEL else colors.chip)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (part.watched) Text(Words.SportsList.TICK, style = AppType.small, color = if (turned) colors.turnedAccent else colors.good)
                Text(part.label, style = AppType.small, color = if (turned) colors.background else colors.textSecondary)
            }
        }
    }
}

/** "+3 more" or "Show fewer" on a phone, under the rows' titles, in the accent. */
@Composable
private fun PhoneMoreRow(text: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().heightIn(min = 48.dp).focusOutline().clickable(role = Role.Button, onClick = onClick)
            .padding(start = 64.dp, end = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(text, style = AppType.bodyStrong.copy(fontSize = 14.sp), color = colors.accent) }
}

/** "+3 more" or "Show fewer" on a TV, turned over while focused. */
@Composable
private fun TvMoreRow(text: String, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val turned = interaction.turnsOver()
    OwnedRipple {
        Box(
            modifier.padding(start = 5.dp, end = 8.dp, top = 3.dp).fillMaxWidth().heightIn(min = 48.dp).focusScale(interaction, { it.rowScale })
                .clip(RoundedCornerShape(10.dp)).then(if (turned) Modifier.background(colors.text) else Modifier)
                .clickable(interaction, LocalIndication.current, role = Role.Button, onClick = onClick).padding(start = 68.dp, end = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) { Text(text, style = AppType.bodyStrong, color = if (turned) colors.turnedAccent else colors.accent) }
    }
}

/**
 * The tab's Up next on a phone, Home's rows without a picture: the title as the play stores it, its line, the bar of a
 * play begun, and Play at its end, the first row's the primary one. A tap on the row plays it, as Play does (L652).
 */
@Composable
internal fun PhoneUpNext(rows: List<StripRow>, element: (String) -> Modifier, onPlay: (StripRow) -> Unit) {
    GroupCard {
        rows.forEachIndexed { index, row ->
            if (index > 0) InsetDivider()
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(
                    element(ListKeys.next(row.item.part.key)).weight(1f).focusOutline().clickable(role = Role.Button) { onPlay(row) }.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(row.title, style = AppType.bodyStrong, color = colors.text)
                    Text(row.line, style = AppType.secondary, color = colors.muted)
                    row.progress?.let { Bar(it, colors.accent, colors.chip, 4.dp, Modifier.padding(top = 1.dp)) }
                }
                PlayCircle(
                    row.primary, Words.SportsList.play(row.title),
                    Modifier.focusOutline(CircleShape, color = if (row.primary) colors.text else colors.accent, fill = false)
                        .clickable(role = Role.Button) { onPlay(row) },
                    PHONE_PLAY,
                )
            }
        }
    }
}

/**
 * The tab's Up next on a TV: each row one stop, turned over while focused, with Play drawn at its end (OK on the row
 * plays it, L652), so the remote passes down the rows without stopping on a button beside each.
 */
@Composable
internal fun TvUpNext(rows: List<StripRow>, element: (String) -> Modifier, onPlay: (StripRow) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(bottom = 3.dp)) {
        rows.forEach { row -> key(row.item.part.key) { TvUpNextRow(row, element, onPlay) } }
    }
}

/** One row of a TV's Up next, turned over while focused. */
@Composable
private fun TvUpNextRow(row: StripRow, element: (String) -> Modifier, onPlay: (StripRow) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val turned = interaction.turnsOver()
    OwnedRipple {
        Row(
            element(ListKeys.next(row.item.part.key)).padding(start = 5.dp, end = 8.dp, top = 3.dp).fillMaxWidth().heightIn(min = 60.dp)
                .focusScale(interaction, { it.rowScale }).clip(RoundedCornerShape(10.dp))
                .then(if (turned) Modifier.background(colors.text) else Modifier)
                .clickable(interaction, LocalIndication.current, role = Role.Button) { onPlay(row) }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.title, style = AppType.bodyStrong, color = if (turned) colors.background else colors.text)
                Text(row.line, style = AppType.secondary, color = if (turned) colors.turnedSecondary else colors.muted)
                row.progress?.let {
                    Bar(it, if (turned) colors.turnedAccent else colors.accent, if (turned) TURNED_TRACK else colors.chip, 4.dp, Modifier.padding(top = 2.dp))
                }
            }
            PlayCircle(primary = false, spoken = null, modifier = Modifier, size = TV_PLAY, turned = turned)
        }
    }
}

/**
 * Play at the end of an Up next row: the accent's circle for the [primary] one, the chip's for the others, the triangle
 * in the accent; TalkBack reads it as [spoken] (a TV's is drawn alone, the row being its button).
 */
@Composable
private fun PlayCircle(primary: Boolean, spoken: String?, modifier: Modifier, size: Dp, turned: Boolean = false) {
    Box(
        modifier.size(size).clip(CircleShape).background(
            when {
                turned -> TURNED_LABEL
                primary -> colors.accent
                else -> colors.chip
            },
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.PlayArrow, contentDescription = spoken,
            tint = when {
                turned -> colors.turnedAccent
                primary -> colors.onAccent
                else -> colors.accent
            },
            modifier = Modifier.size(size / 2),
        )
    }
}

/** How far a play went: [progress] of the track, [height] tall, round at its ends. */
@Composable
private fun Bar(progress: Float, color: Color, track: Color, height: Dp, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { progress }, modifier = modifier.fillMaxWidth().height(height), color = color, trackColor = track, drawStopIndicator = {},
        gapSize = 0.dp,
    )
}

/** The start time's column: the phone's 40 dp and the TV's 48 dp, wider at a larger text size. */
private val PHONE_TIME = 40.dp
private val TV_TIME = 48.dp

/** A TV's row, a little taller than a phone's 56 dp. */
private val TV_ROW = 60.dp

private val PHONE_PLAY = 48.dp
private val TV_PLAY = 40.dp

/** A turned-over row's labels and Play: a shade of the dark words on the cream. */
internal val TURNED_LABEL = Color(0x1A0E0F12)

/** A turned-over row's bar's track. */
private val TURNED_TRACK = Color(0x260E0F12)
