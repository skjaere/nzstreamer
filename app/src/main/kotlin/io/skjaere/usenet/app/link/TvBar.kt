package io.skjaere.usenet.app.link

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.skjaere.usenet.app.play.CardButton
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.colors
import kotlinx.coroutines.delay

/**
 * The Playing on bar (L38, W15, L91; PtPhoneBar, PtPhoneLost, PtPhoneTalkBack 10-12, CsPhoneBar, CsPhoneTalkBack 9-11), in
 * the mini bar's place on every screen but the player and the remote, 58 dp: a 2 dp line of how far the play is
 * ([positionMs] of its length, muted while the device's link is lost), then the device's mark (the TV's, or Google's Cast
 * icon; a spinner while it buffers, loads or the next episode starts), "Harbor Lights · S02E04" over what the device says,
 * pause or play, and Stop (Stop casting for a cast). Lost or reconnecting, its buttons wait; a TV's after 2 minutes reads
 * "Can't reach Living room TV" with × ([onPutAway]) in their place; a cast's has no ×. A tap anywhere but its buttons
 * opens the remote ([onOpen]). For TalkBack it is one stop, what plays and where it is, then its two buttons.
 * [windowInsets] as the mini bar's.
 */
@Composable
fun RemotePlayingBar(
    now: RemoteNow,
    positionMs: Long,
    windowInsets: WindowInsets,
    onOpen: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onPutAway: () -> Unit,
) {
    val bar = now.shows
    val lost = now.waits
    val name = now.device
    Column(Modifier.fillMaxWidth().background(colors.surface).windowInsetsPadding(windowInsets)) {
        val done = now.durationMs?.takeIf { it > 0 }?.let { (positionMs.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
        Box(Modifier.fillMaxWidth().height(2.dp).background(colors.divider)) {
            Box(Modifier.fillMaxWidth(done).height(2.dp).background(if (lost) colors.muted else colors.accent))
        }
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).fillMaxHeight().clickable(onClickLabel = OPEN_THE_REMOTE, onClick = onOpen)
                    .semantics(mergeDescendants = true) { contentDescription = barSpoken(now, positionMs) }
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    when (bar) {
                        RemoteBar.Buffering, RemoteBar.NextEpisode, RemoteBar.Loading ->
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.accent, trackColor = colors.outline)
                        else -> Icon(now.mark, contentDescription = null, tint = if (lost) colors.muted else colors.accent, modifier = Modifier.size(24.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(now.label, style = AppType.bodyStrong, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        barLine(now), style = AppType.small, color = if (lost || bar == RemoteBar.Stopped) colors.warn else colors.muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(Modifier.padding(end = 4.dp)) {
                if (bar == RemoteBar.CantReach) {
                    BarButton(Icons.Filled.Close, PUT_AWAY, enabled = true, onClick = onPutAway)
                } else {
                    val paused = bar == RemoteBar.Paused
                    BarButton(
                        if (paused) Icons.Filled.PlayArrow else PauseIcon, if (paused) playOnTv(name) else pauseOn(name),
                        enabled = now.controls, onClick = if (paused) onResume else onPause,
                    )
                    BarButton(StopIcon, stopOn(now), enabled = !lost, onClick = onStop)
                }
            }
        }
    }
}

/**
 * The device's mark: the TV's for a TV (never Cast's, L9); Google's filled Cast icon for a cast, its outline while the
 * session reconnects (L79).
 */
internal val RemoteNow.mark: ImageVector
    get() = when (kind) {
        RemoteKind.Tv -> TvIcon
        RemoteKind.Cast -> if (shows == RemoteBar.Reconnecting) CastIcon else CastConnectedIcon
    }

/** One of the bar's 48 dp buttons, its 24 dp icon at 38 % while it waits. */
@Composable
private fun BarButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.iconButtonColors(contentColor = colors.text, disabledContentColor = colors.text.copy(alpha = 0.38f)),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
    }
}

/**
 * The bar in the mini bar's place while [playing] follows a play another device's player has, and it isn't put away;
 * nothing otherwise. [onRemote] opens the remote.
 */
@Composable
fun RemotePlayingBarHost(playing: RemotePlaying, windowInsets: WindowInsets, onRemote: () -> Unit) {
    val now by playing.now.collectAsStateWithLifecycle()
    val shown = now?.takeIf { it.bar != null } ?: return
    RemotePlayingBar(
        shown, rememberRemotePosition(playing, shown), windowInsets, onOpen = onRemote, onPause = playing::pause, onResume = playing::resume,
        onStop = playing::stop, onPutAway = playing::dismiss,
    )
}

/**
 * Where [now]'s play is, moved on a few times a second while it plays (L38, L91: smoothly between the device's updates,
 * which come about once a second); standing still while paused, buffering, loading or lost.
 */
@Composable
fun rememberRemotePosition(playing: RemotePlaying, now: RemoteNow): Long {
    var position by remember(now) { mutableLongStateOf(playing.position(now)) }
    LaunchedEffect(now) {
        while (now.moving) {
            position = playing.position(now)
            delay(TICK_MS)
        }
    }
    return position
}

/**
 * The episode of [showTmdbId] another device plays for this phone (W14, L91): its season and episode while the bar shows
 * it playing there, paused, buffering or loading (lost or reconnecting too, as it plays on there); null otherwise, or once
 * × put the bar away.
 */
fun RemoteNow.playingEpisodeOf(showTmdbId: Int): Pair<Int, Int>? {
    val (show, season, episode) = episode ?: return null
    if (show != showTmdbId || bar == null || bar == RemoteBar.CantReach || !live) return null
    return season to episode
}

/**
 * The Show page's button while another device plays one of its episodes (W14, L91; PtPhoneBar 2, CsPhoneBar 2): "Playing
 * S02E04 · 32:10" over "on Living room TV", the device's mark before them; it opens the remote.
 */
@Composable
fun PlayingOnButton(now: RemoteNow, positionMs: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PrimaryButton(
        playingEpisode(now, positionMs), onClick, modifier, second = onTvLine(now.device),
        leading = { Icon(now.mark, contentDescription = null, modifier = Modifier.padding(end = 6.dp)) },
    )
}

/**
 * What the phone says of its plays on other devices (item 27, L93; PtPhoneStopped, PtPhoneLost 4, CsPhoneStopped) in the
 * Undo bar's look, the device's mark in place of Undo (the TV's, or Google's outline Cast icon for a cast), for a moment:
 * "Stopped on Living room TV at 32:10", "Stopped on Kitchen display at 32:10". TalkBack reads it once, as it comes. A line
 * with a button (L125; CsPhoneWatchedNext) has it under its words, filled, "Play S02E05 on Kitchen display", read after
 * them; as another [screen] opens under it (a back stack entry's id; null while none is known), it is told
 * ([RemotePlaying.left]), so one held for TalkBack goes.
 */
@Composable
fun RemoteSaidBar(playing: RemotePlaying, modifier: Modifier = Modifier, screen: String? = null) {
    val said by playing.said.collectAsStateWithLifecycle()
    val line = said ?: return
    // The screen it was first said over, so that another one opening is told: kept across a recreation (a rotation draws
    // it anew over the same screen), and the first one known taken for it, as a new composition knows none in its first
    // frame.
    var saidOver by rememberSaveable(line.id, line.kind) { mutableStateOf<String?>(null) }
    LaunchedEffect(line, screen) {
        val known = screen ?: return@LaunchedEffect
        if (saidOver == null) saidOver = known else if (known != saidOver) playing.left(line)
    }
    Column(
        modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().heightIn(min = 52.dp)
            .background(colors.chip, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 36.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(if (line.kind == RemoteKind.Cast) CastIcon else TvIcon, contentDescription = null, tint = colors.muted, modifier = Modifier.size(20.dp))
            Text(line.text, style = AppType.secondary, color = colors.text, modifier = Modifier.weight(1f))
        }
        // Under the words, beside where the mark is (20 dp and its 12 dp gap in).
        line.action?.let { action ->
            CardButton(
                action.words, { playing.act(line) }, Modifier.padding(start = 32.dp, top = 2.dp, bottom = 4.dp), filled = true,
                horizontalPadding = 16.dp, maxLines = 2,
            )
        }
    }
}

/** An episode row's line while another device plays it (W14, L91): "On Living room TV · 32:10", over how far it is. */
data class TvEpisodeLine(val line: String, val progress: Float?)

/** [now]'s row line, its position moving on as the bar's does. */
@Composable
fun rememberRemoteEpisodeLine(playing: RemotePlaying, now: RemoteNow): TvEpisodeLine {
    val position = rememberRemotePosition(playing, now)
    return TvEpisodeLine(onTvAt(now.device, position), now.durationMs?.takeIf { it > 0 }?.let { (position.toFloat() / it).coerceIn(0f, 1f) })
}

/** Double-tap on the bar, as TalkBack says it (PtPhoneTalkBack 10). */
const val OPEN_THE_REMOTE = "open the remote"

/** How often the position moves on while the device plays. */
private const val TICK_MS = 250L
