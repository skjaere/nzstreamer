package io.skjaere.usenet.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Back arrow + title row used at the top of secondary screens. A TV shows no arrow: the remote's
 * Back does that, and an arrow there would be one more stop for the focus, where a stray OK leaves.
 */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null && LocalFormFactor.current != FormFactor.Tv) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.text)
            }
        } else {
            Box(Modifier.size(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = if (subtitle == null) AppType.screenTitle else AppType.cardTitle, color = colors.text)
            subtitle?.let { Text(it, style = AppType.secondary, color = colors.muted) }
        }
        actions()
    }
}

/** A section's header, in capitals; [top] above it (a padding [modifier] adds to it, never takes it away). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, top: Dp = 18.dp, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = top, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = AppType.section, color = colors.muted, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** A rounded group of rows separated by inset dividers. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface), content = content)
}

@Composable
fun InsetDivider() = HorizontalDivider(Modifier.padding(horizontal = 12.dp), thickness = 1.dp, color = colors.divider)

/**
 * A tappable settings-style row: title, one-line status, chevron ([end]: "↗" for one that opens the browser), which is
 * drawn alone: TalkBack reads the title and status, then the button, not the glyph's name. [modifier] goes before its
 * clickable: a focus outline. On a TV it turns over while focused ([turnsOver]).
 */
@Composable
fun NavRow(title: String, status: String? = null, warn: Boolean = false, modifier: Modifier = Modifier, end: String = "›", onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val turned = interaction.turnsOver()
    OwnedRipple {
        Row(
            modifier.fillMaxWidth().heightIn(min = 60.dp).focusScale(interaction, { it.rowScale })
                .then(if (turned) Modifier.background(colors.text) else Modifier)
                .clickable(interaction, LocalIndication.current, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = AppType.body, color = if (turned) colors.background else colors.text)
                status?.let { Text(it, style = AppType.secondary, color = if (turned) colors.turnedSecondary else colors.muted) }
            }
            // Turned over, a darker orange: the usual one all but goes on the cream, on the very row the user looks at.
            if (warn) Box(Modifier.padding(end = 8.dp).size(8.dp).clip(CircleShape).background(if (turned) colors.turnedWarn else colors.warnStrong))
            Text(end, style = AppType.body, color = if (turned) colors.background else colors.muted, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

/** [Muted]: what is known of a release that doesn't stop it, such as its being found incomplete before. */
enum class ChipKind { Plain, Good, Warn, Lang, Muted }

/** A chip's [text] in its [kind]'s colours; [spoken], where TalkBack is to read words other than those shown (L150). */
@Composable
fun InfoChip(text: String, kind: ChipKind = ChipKind.Plain, spoken: String? = null) {
    val (bg, fg) = when (kind) {
        ChipKind.Plain -> colors.chip to colors.chipText
        ChipKind.Good -> colors.goodBg to colors.good
        ChipKind.Warn -> colors.warnBg to colors.warn
        ChipKind.Lang -> colors.langBg to colors.lang
        ChipKind.Muted -> colors.chip to colors.muted
    }
    Text(
        text, style = AppType.small, color = fg,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 7.dp, vertical = 2.dp)
            .then(if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

enum class StatusKind { Ok, Busy, Warn, None }

/**
 * Per-indexer status pill: "Indexer A · 21", "⚠ Indexer C · API limit reached". Taller with a large font size, so its words
 * aren't cut off. A [quiet] end follows the words in the muted colour (a torrent indexer's "· checking 12 torrents",
 * DbPhoneSearch); TalkBack reads [spoken] where it is given, for the whole pill.
 */
@Composable
fun StatusPill(text: String, kind: StatusKind, quiet: String? = null, spoken: String? = null) {
    val warn = kind == StatusKind.Warn
    Row(
        Modifier.heightIn(min = 28.dp).clip(RoundedCornerShape(14.dp)).background(if (warn) colors.warnBg else colors.surface).padding(horizontal = 10.dp)
            .then(if (spoken != null) Modifier.semantics(mergeDescendants = true) { contentDescription = spoken } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (kind) {
            StatusKind.Ok -> Box(Modifier.size(8.dp).clip(CircleShape).background(colors.goodDot))
            StatusKind.None -> Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF5A5D66)))
            StatusKind.Busy -> androidx.compose.material3.CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 2.dp, color = colors.accent, trackColor = colors.outline)
            StatusKind.Warn -> Text("⚠", style = AppType.small, color = colors.warn)
        }
        val muted = colors.muted
        Text(
            if (quiet == null) AnnotatedString(text) else buildAnnotatedString {
                append(text)
                append(' ')
                withStyle(SpanStyle(color = muted)) { append(quiet) }
            },
            style = AppType.small, color = if (warn) colors.warn else colors.textSecondary,
        )
    }
}

/**
 * Outlines the element while it has the focus (a TV remote's D-pad), in [color], with a [fill]
 * behind it for rows; buttons that are filled already pass false. Goes before the element's
 * clickable, whose focus it follows (not that of buttons inside it). The ring is [FocusStyle.outline]
 * wide, and on a TV a pointer over the element shows it too ([FocusStyle.hover]). The rows that take
 * it set their words' colours themselves, so it never turns them over: they would vanish.
 */
fun Modifier.focusOutline(shape: Shape = RoundedCornerShape(12.dp), color: Color? = null, fill: Boolean = true): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val colors = LocalAppColors.current
    val style = LocalFocusStyle.current
    val pointer = remember { MutableInteractionSource() }
    val hovered by pointer.collectIsHoveredAsState()
    val lit = focused || (style.hover && hovered)
    onFocusChanged { focused = it.isFocused }
        .then(if (style.hover) Modifier.hoverable(pointer) else Modifier)
        .clip(shape)
        .then(if (lit && fill) Modifier.background(colors.chip) else Modifier)
        .border(style.outline, if (lit) color ?: colors.accent else Color.Transparent, shape)
}

/**
 * At least 48 dp tall, growing with the font size. On a TV it turns over while focused: the amber
 * goes cream, so the focus shows on it ([turnsOver]). [second]: a smaller line under the words, where it plays
 * ("on Living room TV", PtPhoneWhere B).
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: @Composable (() -> Unit)? = null,
    second: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val turned = interaction.turnsOver()
    OwnedRipple {
        Button(
            onClick = onClick, enabled = enabled, modifier = modifier.focusScale(interaction, { it.buttonScale }).heightIn(min = 48.dp),
            shape = RoundedCornerShape(24.dp), interactionSource = interaction,
            colors = ButtonDefaults.buttonColors(containerColor = if (turned) colors.text else colors.accent, contentColor = if (turned) colors.background else colors.onAccent),
        ) {
            leading?.invoke()
            if (second == null) {
                Text(text, style = AppType.bodyStrong)
            } else {
                Column {
                    Text(text, style = AppType.bodyStrong)
                    Text(second, style = AppType.small.copy(fontWeight = FontWeight.Medium))
                }
            }
        }
    }
}

/** Outlined; on a TV it turns over while focused ([turnsOver]). */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val turned = interaction.turnsOver()
    OwnedRipple {
        OutlinedButton(
            onClick = onClick, enabled = enabled, modifier = modifier.focusScale(interaction, { it.buttonScale }).heightIn(min = 48.dp),
            shape = RoundedCornerShape(24.dp), interactionSource = interaction,
            border = androidx.compose.foundation.BorderStroke(1.dp, if (turned) colors.text else colors.outline),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = if (turned) colors.text else Color.Transparent, contentColor = if (turned) colors.background else colors.text),
        ) { Text(text, style = AppType.body) }
    }
}

/** The dashed "N hidden · can't play on this device" line. */
@Composable
fun HiddenLine(text: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .border(1.dp, colors.outline, RoundedCornerShape(12.dp)).clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = AppType.secondary, color = colors.textSecondary, modifier = Modifier.weight(1f))
        Text(if (expanded) "Hide" else "Show ›", style = AppType.secondary, color = colors.muted)
    }
}

@Composable
fun Footnote(text: String, modifier: Modifier = Modifier) {
    Text(text, style = AppType.small, color = colors.muted, modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

/** A footnote in [paragraphs], 8 dp apart, for one that says two things. */
@Composable
fun Footnote(paragraphs: List<String>, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        paragraphs.forEach { Text(it, style = AppType.small, color = colors.muted) }
    }
}

/** Placeholder poster block until images load (or when artwork is off). */
@Composable
fun PosterBox(width: Int, height: Int, tint: Color = Color(0xFF2E4A5C), modifier: Modifier = Modifier) {
    Box(modifier.size(width.dp, height.dp).clip(RoundedCornerShape(6.dp)).background(tint))
}

/**
 * At the top of a form on a TV: a neutral word on typing with a remote. Google TV's phone app,
 * among others, types into the field that has the focus. Nothing on a phone.
 */
@Composable
fun TvTypingTip(modifier: Modifier = Modifier, phoneBelow: Boolean = false) {
    if (LocalFormFactor.current != FormFactor.Tv) return
    // With "Set up with your phone" under it, one word on the phone, leading to it.
    Text(
        if (phoneBelow) TV_TYPING_TIP_PHONE else TV_TYPING_TIP, style = AppType.secondary, color = colors.textSecondary,
        modifier = modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
    )
}

const val TV_TYPING_TIP = "Typing with a remote is slow. Your TV's phone remote app may let you type with your phone's keyboard."
const val TV_TYPING_TIP_PHONE = "Typing with a remote is slow. Use your phone instead, or type with your TV's remote app."
