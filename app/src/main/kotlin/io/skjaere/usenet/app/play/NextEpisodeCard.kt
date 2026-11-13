package io.skjaere.usenet.app.play

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.LocalFocusStyle
import io.skjaere.usenet.app.ui.OwnedRipple
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.app.ui.focusScale
import io.skjaere.usenet.app.ui.tryFocus
import io.skjaere.usenet.app.ui.turnsOver
import io.skjaere.usenet.core.formatSize
import io.skjaere.usenet.metadata.TmdbImages

/** Behind the still while it loads, or in its place when artwork is off. */
private val StillPlaceholder = Color(0xFF23384A)

/**
 * The player's "Next episode" card (mockup "Player with next episode"): the next episode's still
 * and name, how the search for its release is going, a countdown with a bar while one runs, and
 * Stop (Close when nothing will start by itself) and Play now. Play now takes focus as the card
 * shows, so a TV remote's centre button plays at once: once, as soon as [takeFocus] lets it (it
 * doesn't while a burst of seeks waits, whose held key would then come to it, nor under a side
 * panel), and again only through [playNow], the player's way to give it back.
 *
 * Its surface takes its taps ([takesTaps]); its buttons have theirs.
 *
 * [compact] (while the playback controls show) is one low row, the countdown, Stop and Play now,
 * that stays below the controls' centre buttons, also on a 360 dp tall screen with a gesture area
 * or navigation bar under it (the player lifts them there). Both forms are one tree with the
 * buttons in the same place in it, so Play now keeps the focus as the form changes.
 */
@Composable
fun NextEpisodeCard(
    next: EpisodePlay,
    card: AutoAdvance.Card,
    showStill: Boolean,
    compact: Boolean,
    takeFocus: Boolean,
    onStop: () -> Unit,
    onPlayNow: () -> Unit,
    modifier: Modifier = Modifier,
    playNow: FocusRequester = remember { FocusRequester() },
) {
    // Not again once it had it: a rewind tap on the controls with the card up keeps its focus as its seek runs. A
    // request nothing took (the card under a side panel) is made again as [takeFocus] next lets it.
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(takeFocus) {
        if (takeFocus && !focused) focused = playNow.tryFocus()
    }
    Column(
        modifier.then(if (compact) Modifier else Modifier.width(300.dp)).clip(RoundedCornerShape(16.dp)).background(colors.surface.copy(alpha = 0.94f))
            .takesTaps().semantics { liveRegion = LiveRegionMode.Polite }.padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!compact) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(96.dp, 54.dp).clip(RoundedCornerShape(8.dp)).background(StillPlaceholder)) {
                    if (showStill && next.stillPath != null) {
                        AsyncImage(TmdbImages.still(next.stillPath), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Countdown(card)
                    Text(next.subtitle, style = AppType.bodyStrong.copy(fontSize = 14.sp), color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Wraps at larger font sizes rather than losing "ready".
                    Text(prefetchLine(card.prefetch), style = AppType.small, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            card.progress?.let { progress ->
                // Eased across the player's 100 ms ticks.
                val shown by animateFloatAsState(progress, tween(100, easing = LinearEasing), label = "countdown")
                LinearProgressIndicator(
                    progress = { shown },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clearAndSetSemantics {},
                    color = colors.accent, trackColor = colors.chip, strokeCap = StrokeCap.Butt, gapSize = 0.dp, drawStopIndicator = {},
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (compact) Countdown(card, Modifier.weight(1f, fill = false))
            val share = if (compact) Modifier else Modifier.weight(1f)
            CardButton(stopLabel(card), onStop, share, filled = false)
            CardButton("Play now", onPlayNow, share.focusRequester(playNow), filled = true)
        }
    }
}

/**
 * Taps on an overlay's surface go nowhere, as on a side panel's: not through to the picture under it, where they would
 * show or hide the controls, or count toward a double-tap's seek at the picture's sides (L11). Its buttons keep theirs.
 */
internal fun Modifier.takesTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }

/**
 * The app's Primary and Secondary buttons, but growing with the font size from a 44 dp touch
 * target instead of a fixed height, with a one-line label and room for it in half the card.
 * [container] fills an outlined one, for a button on its own over the picture. Outlined while a
 * remote is on it, as Home's buttons are; on a TV turned over instead, as the app's buttons are
 * there (the skip button's ring was too faint across a room, #148). A label that names something
 * of any length (a device) wraps to [maxLines], centred, an ellipsis ending one longer still.
 */
@Composable
internal fun CardButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    filled: Boolean,
    container: Color = Color.Transparent,
    horizontalPadding: Dp = 8.dp,
    maxLines: Int = 1,
) {
    val shape = RoundedCornerShape(percent = 50)
    val padding = PaddingValues(horizontal = horizontalPadding, vertical = 8.dp)
    val overflow = if (maxLines > 1) TextOverflow.Ellipsis else TextOverflow.Clip
    val interaction = remember { MutableInteractionSource() }
    val turned = interaction.turnsOver()
    val look = if (LocalFocusStyle.current.invert) modifier.focusScale(interaction, { it.buttonScale })
    else modifier.focusOutline(shape, color = if (filled) colors.text else colors.accent, fill = false)
    val sized = look.heightIn(min = 44.dp)
    // Laid out at 44 dp, not material's 48 dp minimum, or the compact card outgrows the room below
    // the controls' centre buttons; touches within the view configuration's 48 dp still reach it.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 44.dp) {
        OwnedRipple {
            if (filled) {
                Button(
                    onClick, sized, shape = shape, contentPadding = padding, interactionSource = interaction,
                    colors = ButtonDefaults.buttonColors(containerColor = if (turned) colors.text else colors.accent, contentColor = if (turned) colors.background else colors.onAccent),
                ) { Text(text, style = AppType.bodyStrong, textAlign = TextAlign.Center, overflow = overflow, maxLines = maxLines) }
            } else {
                OutlinedButton(
                    onClick, sized, shape = shape, contentPadding = padding, border = BorderStroke(1.dp, if (turned) colors.text else colors.outline),
                    interactionSource = interaction,
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = if (turned) colors.text else container, contentColor = if (turned) colors.background else colors.text),
                ) { Text(text, style = AppType.body, textAlign = TextAlign.Center, overflow = overflow, maxLines = maxLines) }
            }
        }
    }
}

/** "Stop" while the next episode will start by itself (from the credits on), else "Close". */
internal fun stopLabel(card: AutoAdvance.Card): String = if (card.autoPlays) "Stop" else "Close"

/** "Next episode in 8 s", or "Next episode" when nothing counts down. */
@Composable
private fun Countdown(card: AutoAdvance.Card, modifier: Modifier = Modifier) {
    Text(
        card.secondsLeft?.let { "Next episode in $it s" } ?: "Next episode",
        style = AppType.small.copy(fontWeight = FontWeight.SemiBold), color = colors.accent,
        // The live region would read out every second; screen readers get what happens instead.
        modifier = modifier.clearAndSetSemantics { contentDescription = if (card.secondsLeft != null) "Next episode plays automatically" else "Next episode" },
    )
}

/**
 * The card's third line: "Looking for a release…", "Release found · 2.2 GB · ready" (in the season
 * pack playing that names no file for it, that it may be there and its file is the user's to
 * choose: nothing says the pack holds it), "No release found yet", or "Searches again on play" when
 * nothing searches and what was found is not held ([AutoAdvance.Card.prefetch] null).
 */
internal fun prefetchLine(prefetch: Prefetch?): String = when (prefetch) {
    Prefetch.Searching -> "Looking for a release…"
    is Prefetch.Ready -> if (prefetch.choose) "May be in this release · you choose its file"
        else listOfNotNull("Release found", prefetch.sizeBytes.takeIf { it > 0 }?.let(::formatSize), "ready").joinToString(" · ")
    Prefetch.NothingFound -> "No release found yet"
    null -> "Searches again on play"
}
