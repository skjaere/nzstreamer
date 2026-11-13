package io.skjaere.usenet.app.collection

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.skjaere.usenet.app.link.TvEpisodeLine
import io.skjaere.usenet.app.tv.TvMenu
import io.skjaere.usenet.app.tv.TvMenuItem
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.OwnedRipple
import io.skjaere.usenet.app.ui.TvDimens
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.app.ui.focusScale
import io.skjaere.usenet.app.ui.turnsOver
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.TmdbImages

/**
 * An episode's row: OK, or a tap, plays it ([onPlay]). Its ⋮ is a button of its own beside it, so a remote's Right
 * reaches it and Left comes back (#160); it opens Choose release and Mark watched or unwatched, as a long press on the
 * row does. An episode TMDB lists as not aired yet plays, and opens its menu, as any other: releases often appear before
 * TMDB's date. It says when it airs, a little quieter than the aired ones. [modifier] goes on the row's playing part and
 * [moreModifier] on its ⋮ (their focus requesters); [onChoose] says which opened the menu (the ⋮, or a long press). On a TV the row turns over when focused, [current] (the next-up
 * episode) is marked, and with [showArtwork] the episode's [still] is beside its name; a phone's row is as it was, save
 * that while the TV plays the episode for this phone ([onTv], W14) it says "On Living room TV · 32:10" in place of what
 * is left.
 */
@Composable
internal fun EpisodeRow(
    e: Episode,
    state: WatchState?,
    aired: Boolean,
    onPlay: () -> Unit,
    onChoose: (fromMore: Boolean) -> Unit,
    onMark: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    moreModifier: Modifier = Modifier,
    still: String? = null,
    showArtwork: Boolean = false,
    current: Boolean = false,
    onTv: TvEpisodeLine? = null,
) {
    var menu by remember { mutableStateOf(false) }
    // Which opened the menu, for the focus on the way back from Choose release: the ⋮, or the row's long press.
    var fromMore by remember { mutableStateOf(false) }
    val text = episodeRowText(e, aired)
    val said = text.stateDescription?.let { Modifier.semantics { stateDescription = it } } ?: Modifier
    val left = state?.takeIf { !it.watched && it.positionMs >= 60_000 && it.durationMs > 0 }
    val more = @Composable {
        EpisodeMenu(
            e, state?.watched == true, menu, onOpen = { fromMore = true; menu = true }, onDismiss = { menu = false },
            onChoose = { onChoose(fromMore) }, onMark = onMark, modifier = moreModifier,
        )
    }
    if (LocalFormFactor.current == FormFactor.Tv) {
        TvEpisodeRow(e, aired, state?.watched == true, left, text, still, showArtwork, current, onPlay, { fromMore = false; menu = true }, modifier.then(said), more)
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PhoneRowContent(e, aired, state?.watched == true, left, text, onTv, onPlay, { fromMore = false; menu = true }, modifier.then(said))
        Box(Modifier.padding(end = 4.dp)) { more() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun androidx.compose.foundation.layout.RowScope.PhoneRowContent(
    e: Episode, aired: Boolean, watched: Boolean, left: WatchState?, text: EpisodeRowText, onTv: TvEpisodeLine?, onPlay: () -> Unit, onMenu: () -> Unit, modifier: Modifier,
) {
    Row(
        // As tall as the row was with its ⋮ inside it, and the ✓ as far from the ⋮: a phone's rows look as they did.
        modifier.weight(1f).heightIn(min = 68.dp).focusOutline().combinedClickable(onClickLabel = "Play", onClick = onPlay, onLongClick = onMenu)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("${e.episode}", style = AppType.bodyStrong, color = if (aired) colors.textSecondary else colors.muted, modifier = Modifier.width(28.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(e.name ?: "Episode ${e.episode}", style = AppType.body, color = if (aired) colors.text else colors.textSecondary, maxLines = 1)
            if (text.meta.isNotEmpty()) Text(text.meta, style = AppType.small, color = colors.muted)
            if (onTv != null) {
                Text(onTv.line, style = AppType.small, color = colors.accent)
                LinearProgressIndicator(
                    progress = { onTv.progress ?: 0f }, modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = colors.accent, trackColor = colors.divider, drawStopIndicator = {},
                )
            } else left?.let { state ->
                Text("${mmss(state.durationMs - state.positionMs)} left", style = AppType.small, color = colors.accent)
                LinearProgressIndicator(
                    progress = { state.positionMs.toFloat() / state.durationMs }, modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = colors.accent, trackColor = colors.divider, drawStopIndicator = {},
                )
            }
        }
        if (watched) Text("✓", style = AppType.bodyStrong, color = colors.good)
    }
}

/**
 * A TV's row, as the TV design draws it: "N · name" over when it aired and how long it runs, with how much is
 * left of a play begun; the ✓ of a watched one; turned over while focused (cream, its words dark), so the focus shows
 * from across the room. 84 dp tall with a still, 72 dp without.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvEpisodeRow(
    e: Episode, aired: Boolean, watched: Boolean, left: WatchState?, text: EpisodeRowText, still: String?, showArtwork: Boolean, current: Boolean,
    onPlay: () -> Unit, onMenu: () -> Unit, modifier: Modifier, more: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val turned = interaction.turnsOver()
    val progress = left?.let { it.positionMs.toFloat() / it.durationMs }
    val accent = if (turned) colors.turnedAccent else colors.accent
    Row(
        Modifier.fillMaxWidth().then(if (current) Modifier.background(colors.rowSelected) else Modifier)
            // 5 dp in from the card's edge, which clips it: the part that turns over grows 1.02 when focused.
            .padding(start = 5.dp, end = 8.dp, top = TV_ROW_INSET, bottom = TV_ROW_INSET),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OwnedRipple {
            Row(
                // The row's height but its inset, so the part that turns over fills it.
                modifier.weight(1f).heightIn(min = (if (showArtwork) TV_ROW_WITH_STILL else TV_ROW_PLAIN) - TV_ROW_INSET * 2)
                    .focusScale(interaction, { it.rowScale }).clip(RoundedCornerShape(10.dp))
                    .then(if (turned) Modifier.background(colors.text) else Modifier)
                    .combinedClickable(interaction, LocalIndication.current, onClickLabel = "Play", onLongClick = onMenu, onClick = onPlay)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (showArtwork) Still(still, progress)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "${e.episode} · ${e.name ?: "Episode ${e.episode}"}",
                        style = if (current) AppType.bodyStrong else AppType.body, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = when {
                            turned -> colors.background
                            aired -> colors.text
                            else -> colors.textSecondary
                        },
                    )
                    val meta = buildAnnotatedString {
                        append(text.meta)
                        left?.let { state ->
                            if (text.meta.isNotEmpty()) append(" · ")
                            withStyle(SpanStyle(color = accent)) { append("${mmss(state.durationMs - state.positionMs)} left") }
                        }
                    }
                    if (meta.isNotEmpty()) {
                        Text(
                            meta, style = AppType.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = when {
                                turned -> colors.turnedSecondary
                                current -> colors.textSecondary
                                else -> colors.muted
                            },
                        )
                    }
                    // Without a still, how far it was watched goes under the words, as on a phone.
                    if (!showArtwork && progress != null) {
                        LinearProgressIndicator(
                            progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp),
                            color = accent, trackColor = colors.divider, drawStopIndicator = {},
                        )
                    }
                }
                if (watched) Text("✓", style = AppType.bodyStrong, color = accent)
            }
        }
        more()
    }
}

/** The episode's still, 16:9, with how far it was watched ([progress]) along its foot; a tinted tile without one. */
@Composable
private fun Still(path: String?, progress: Float?) {
    Box(Modifier.size(TvDimens.still).clip(RoundedCornerShape(6.dp)).background(STILL_TILE)) {
        if (path != null) {
            AsyncImage(TmdbImages.still(path, STILL_PX), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
        progress?.let { p ->
            LinearProgressIndicator(
                progress = { p }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                color = colors.accent, trackColor = colors.chip, drawStopIndicator = {}, gapSize = 0.dp,
            )
        }
    }
}

/**
 * The row's ⋮ and its menu: Choose release, and Mark watched or unwatched. On a TV the menu's first item takes the focus
 * as it opens (a remote has nothing to point at it with), and its items turn over when focused. As it closes, the focus
 * is where it was before: on the ⋮, or on the row after a long press. The OK that opened it, held, picks nothing in it
 * (play/OkGuard.kt's rule).
 */
@Composable
private fun EpisodeMenu(
    e: Episode, watched: Boolean, expanded: Boolean, onOpen: () -> Unit, onDismiss: () -> Unit, onChoose: () -> Unit, onMark: (Boolean) -> Unit, modifier: Modifier,
) {
    Box {
        IconButton(onClick = onOpen, modifier = modifier.focusOutline(CircleShape, fill = false)) {
            Icon(Icons.Filled.MoreVert, "More for episode ${e.episode}", tint = colors.muted)
        }
        TvMenu(expanded, onDismiss) { first ->
            TvMenuItem("Choose release", first) {
                onDismiss()
                onChoose()
            }
            TvMenuItem(if (watched) "Mark unwatched" else "Mark watched") {
                onDismiss()
                onMark(!watched)
            }
        }
    }
}

/**
 * What an episode row says: [meta] under its name, when it aired or airs and how long it runs;
 * [stateDescription], for TalkBack, that TMDB doesn't list it as aired yet (null once it does).
 */
internal data class EpisodeRowText(val meta: String, val stateDescription: String?)

internal fun episodeRowText(e: Episode, aired: Boolean) = EpisodeRowText(
    meta = listOfNotNull(
        e.airDate?.let { if (aired) shortDate(it) else "Airs ${shortDate(it)}" } ?: if (!aired) "Not aired yet" else null,
        e.runtimeMinutes?.let { "$it min" },
    ).joinToString(" · "),
    stateDescription = if (aired) null else "Hasn't aired yet according to TMDB",
)

/**
 * A TV row's height with a still beside the name (the still's 63 dp and room round it), and without, and the inset of
 * the part that turns over in it; a loading season's outline is made of them too.
 */
internal val TV_ROW_WITH_STILL = 84.dp
internal val TV_ROW_PLAIN = 72.dp
internal val TV_ROW_INSET = 6.dp

/** The tile under a still, and in its place without one. */
private val STILL_TILE = Color(0xFF2E4A5C)

/** A 112 dp still at 2x: the image size TMDB has nearest above it. */
private const val STILL_PX = 300
