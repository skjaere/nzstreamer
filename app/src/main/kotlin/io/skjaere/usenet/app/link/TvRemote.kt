package io.skjaere.usenet.app.link

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.skjaere.usenet.app.R
import io.skjaere.usenet.app.castExtras
import io.skjaere.usenet.app.casting.CastPlaying
import io.skjaere.usenet.app.casting.CastPrompt
import io.skjaere.usenet.app.casting.CastSlowBanner
import io.skjaere.usenet.app.casting.CastVolume
import io.skjaere.usenet.app.collection.mmss
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.play.CastMissingPartsPrompt
import io.skjaere.usenet.app.play.SlowStreamBanner
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.metadata.TmdbImages
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The remote (L38, L43, L92; PtPhoneRemote, PtPhoneLost 1, PtPhoneTalkBack, CsPhoneRemote, CsPhoneTalkBack), opened from
 * the Playing on bar or the Show page, Back to where it was opened from: this phone's own picture of the play (L34), its
 * title, what the device says of it (a polite live region), the device's position on a time bar a finger drags (one seek
 * as it lifts), ±10 s taps that make one seek after the last, play or pause, and Stop (Stop casting for a cast). Lost or
 * reconnecting, it says so and waits; Playback stopped there, only Stop is left. It closes as the play ends: Stop, the
 * device's own end, or another play there. It follows [playing]: the app's, the cast while one lives, else the TV's. For
 * a cast, [cast]'s missing-parts prompt sits over the picture, as the player's sits over the video, and one part is said
 * for a moment in the state line's place (W37, L94; CsPhoneMissingParts); after it, the cast's slow-stream banner sits
 * there too (L134; CsPhoneSlowCast). The Cast button is at the top right, opening Play on's list (L115). A cast has the
 * device's volume between the buttons and Stop casting, as the list's block has it (L118; CsPhoneRemoteC2); a TV's play
 * has none.
 */
@Composable
fun TvRemoteScreen(
    onBack: () -> Unit,
    playing: RemotePlaying = LocalContext.current.container.remotePlaying,
    cast: CastPlaying? = LocalContext.current.container.castPlaying,
) {
    val followed by playing.now.collectAsStateWithLifecycle()
    // Over: nothing to control here any more.
    LaunchedEffect(followed == null) { if (followed == null) onBack() }
    val now = followed ?: return
    TvRemote(playing, now, onBack, cast?.takeIf { now.kind == RemoteKind.Cast })
}

@Composable
private fun TvRemote(playing: RemotePlaying, now: RemoteNow, onBack: () -> Unit, cast: CastPlaying?) {
    val prompt by (cast?.missing ?: NO_PROMPT).collectAsStateWithLifecycle()
    val slow by (cast?.slow ?: NO_SLOW).collectAsStateWithLifecycle()
    val notice by (cast?.notice ?: NO_NOTICE).collectAsStateWithLifecycle()
    val bar = now.shows
    val lost = now.waits
    // Only while the device's player plays, pauses, buffers or loads is there a place to move or a play to pause.
    val controls = now.controls
    // The seeks go to the play they were made on: a burst under way as the remote switches from the TV's play to the cast's,
    // or back, goes to its own as the switch disposes it, never to the one shown when its half second is up.
    val target = remember(playing, now.kind) { playing.playOf(now) }
    val seek = remember(target) { RemoteSeek(SystemClock::uptimeMillis, seekBy = target::seekBy, seekTo = target::seekTo) }
    // Bumped as the taps and the drag move what the time bar shows, and as a burst's seek is due.
    var moved by remember { mutableIntStateOf(0) }
    LaunchedEffect(moved) {
        while (true) {
            delay(seek.dueInMs() ?: break)
            seek.update()
        }
    }
    // Back, the remote closing, or the play it follows switching, within a burst's half second: its seek goes as the remote
    // does, to its own play, not with it.
    DisposableEffect(seek) { onDispose { seek.flush() } }
    val position = rememberRemotePosition(playing, now)
    val shown = remember(moved, position) { seek.shown(position) }
    val name = now.device
    Column(Modifier.fillMaxSize().background(colors.background).navigationBarsPadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.text)
            }
            Spacer(Modifier.weight(1f))
            // Where Play goes, top right as Google asks of a remote (L115, CsPhoneRemoteC2): for a cast and a TV's play alike.
            val playOn = LocalContext.current.container.playOn
            val target by playOn.target.collectAsStateWithLifecycle()
            CastButton(target, { playOn.openSheet() })
        }
        // Only a picture: no stop for TalkBack (PtPhoneTalkBack 2). It grows only for a prompt larger than it (a large
        // font).
        Box(Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp).fillMaxWidth().heightIn(min = 201.dp).clip(RoundedCornerShape(16.dp)).background(PICTURE_TILE)) {
            now.art?.let { art ->
                val url = if (art.poster) TmdbImages.poster(art.path, POSTER_PX) else TmdbImages.still(art.path)
                AsyncImage(url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            }
            // One at a time, the prompt first (W37, L134).
            if (cast != null) prompt?.let { MissingPartsOver(it, cast) } ?: slow?.let { SlowOver(it, cast) }
        }
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp).semantics(mergeDescendants = true) { heading(); contentDescription = remoteHeading(now) }) {
            Text(now.title, style = AppType.hero, color = colors.text)
            now.subtitle?.let { Text(it, style = AppType.secondary, color = colors.muted) }
        }
        val said = notice
        when {
            lost -> LostBox(now)
            said != null -> NoticeLine(said)
            else -> StateLine(now)
        }
        TimeBar(
            now, shown, seek.dragging, enabled = controls,
            onDrag = { seek.drag(it); moved++ }, onRelease = { seek.release(); moved++ }, onCancel = { seek.cancelDrag(); moved++ },
            onSeek = { seek.drag(it); seek.release(); moved++ },
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 24.dp).alpha(if (controls) 1f else DISABLED),
            horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
        ) {
            val length = now.durationMs ?: -1
            RoundButton(painterResource(R.drawable.ic_window_rewind), REWIND_10, 56.dp, 28.dp, colors.chip, colors.text, controls) {
                seek.tap(-STEP_MS, playing.position(now), length)
                moved++
            }
            val paused = bar == RemoteBar.Paused
            RoundButton(
                rememberVectorPainter(if (paused) Icons.Filled.PlayArrow else PauseIcon), if (paused) playOnTv(name) else pauseOn(name), 72.dp, 36.dp,
                colors.accent, colors.onAccent, controls,
            ) {
                // A burst's seek goes before the pause or the play.
                seek.flush()
                if (paused) playing.resume() else playing.pause()
                moved++
            }
            RoundButton(painterResource(R.drawable.ic_window_forward), FAST_FORWARD_10, 56.dp, 28.dp, colors.chip, colors.text, controls) {
                seek.tap(STEP_MS, playing.position(now), length)
                moved++
            }
        }
        // The device's volume, for a cast alone (L118): the same as the list's block, waiting while the link is lost.
        val castVolume by remember { castExtras?.volume ?: NO_VOLUME }.collectAsStateWithLifecycle()
        val volume = castVolume?.takeIf { now.kind == RemoteKind.Cast }
        volume?.let { CastVolumeSlider(name, it, enabled = !lost, inset = 16.dp, top = 16.dp) }
        OutlinedButton(
            onClick = playing::stop, enabled = !lost,
            modifier = Modifier.padding(start = 16.dp, top = if (volume != null) 16.dp else 24.dp, end = 16.dp, bottom = 16.dp).fillMaxWidth().heightIn(min = 48.dp)
                .alpha(if (lost) DISABLED else 1f).semantics { contentDescription = stopOn(now) },
            shape = RoundedCornerShape(24.dp), border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text, disabledContentColor = colors.text),
        ) {
            Icon(StopIcon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stopWords(now), style = AppType.body, modifier = Modifier.padding(start = 10.dp))
        }
    }
}

/** What the device says, by its mark: a polite live region, read again once each time it changes (L43, L95). */
@Composable
private fun StateLine(now: RemoteNow) {
    val bar = now.shows
    Row(
        Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
            when (bar) {
                RemoteBar.Buffering, RemoteBar.NextEpisode, RemoteBar.Loading ->
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accent, trackColor = colors.outline)
                RemoteBar.Stopped -> Icon(now.mark, contentDescription = null, tint = colors.warnStrong, modifier = Modifier.size(20.dp))
                else -> Icon(now.mark, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
            }
        }
        Text(barLine(now), style = AppType.body, color = if (bar == RemoteBar.Stopped) colors.warn else colors.textSecondary)
    }
}

/**
 * A cast's missing-parts prompt over the picture (W37; CsPhoneMissingParts 1, 4): across its foot, 6 dp in, asking; the
 * count alone at its foot's start, 8 dp in.
 */
@Composable
private fun BoxScope.MissingPartsOver(prompt: CastPrompt, cast: CastPlaying) {
    val place = if (prompt.tryAnother) Modifier.align(Alignment.BottomCenter).padding(6.dp).fillMaxWidth() else Modifier.align(Alignment.BottomStart).padding(8.dp)
    CastMissingPartsPrompt(prompt, onKeepWatching = cast::keepWatching, onTryAnother = cast::tryAnother, modifier = place)
}

/**
 * The cast's slow-stream banner over the picture (L134; CsPhoneSlowCast 1), as the missing-parts prompt sits there: 6 dp in,
 * the picture growing to hold it whole, as the remote's page scrolls.
 */
@Composable
private fun BoxScope.SlowOver(banner: CastSlowBanner, cast: CastPlaying) {
    SlowStreamBanner(
        banner.shown, compact = false, onTrySmaller = cast::trySmaller.takeIf { banner.trySmaller },
        modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp).fillMaxWidth(), withinHeight = false,
    )
}

/**
 * One missing part during a cast, for a moment in the state line's place (L94; CsPhoneMissingParts): "⚠ A missing part:
 * Kitchen display may glitch", in the warning colour; a polite live region.
 */
@Composable
private fun NoticeLine(words: String) {
    Row(
        Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) { Text("⚠", style = AppType.bodyStrong, color = colors.warnStrong) }
        Text(words, style = AppType.body, color = colors.warn)
    }
}

/**
 * The link lost (PtPhoneLost 1, CsPhoneRemote 4): the device plays on, and the phone tries again; a polite live region. A
 * TV keeps playing there; a Cast device plays on from what it has loaded.
 */
@Composable
private fun LostBox(now: RemoteNow) {
    val title = if (now.shows == RemoteBar.CantReach) cantReach(now.device) else lostConnection(now.device)
    val after = if (now.kind == RemoteKind.Cast) CAST_PLAYS_ON else KEEPS_PLAYING
    Column(
        Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.warnBg).padding(12.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite; contentDescription = "$title. $after" },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = AppType.bodyStrong, color = colors.warnStrong)
        Text(after, style = AppType.secondary, color = colors.text)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.accent, trackColor = colors.outline)
            Text(RECONNECTING, style = AppType.secondary, color = colors.textSecondary)
        }
    }
}

/**
 * The TV's position, made for a thumb (PtPhoneRemote 4): a 48 dp strip, its 4 dp track played in the accent, a 16 dp
 * thumb (24 dp with its halo while dragged); where it is under its start, its length under its end. A drag moves only
 * the thumb and the time, shown in the accent ([onDrag]); one seek goes as the finger lifts ([onRelease]); a tap seeks
 * there. For TalkBack it is "Position on the TV, 32 minutes 10 seconds of 52 minutes, slider" ("Position on Kitchen
 * display" for a cast), and a swipe up or down is one seek, 10 s on or back ([onSeek], L43, L95).
 */
@Composable
private fun TimeBar(
    now: RemoteNow,
    shownMs: Long,
    dragging: Boolean,
    enabled: Boolean,
    onDrag: (Long) -> Unit,
    onRelease: () -> Unit,
    onCancel: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val length = now.durationMs?.takeIf { it > 0 }
    val done = length?.let { (shownMs.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
    val track = colors.chip
    val accent = colors.accent
    val gestures = if (enabled && length != null) {
        Modifier
            .pointerInput(length) {
                val edge = GUTTER.toPx()
                detectTapGestures { offset -> onSeek(at(offset.x, edge, size.width.toFloat(), length)) }
            }
            .pointerInput(length) {
                val edge = GUTTER.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { offset -> onDrag(at(offset.x, edge, size.width.toFloat(), length)) },
                    onDragEnd = onRelease,
                    onDragCancel = onCancel,
                ) { change, _ ->
                    change.consume()
                    onDrag(at(change.position.x, edge, size.width.toFloat(), length))
                }
            }
    } else Modifier
    Column(Modifier.padding(top = 16.dp).fillMaxWidth().alpha(if (enabled) 1f else DISABLED)) {
        Box(
            Modifier.fillMaxWidth().height(48.dp)
                .semantics {
                    contentDescription = positionOn(now)
                    stateDescription = length?.let { "${spokenTime(shownMs)} of ${spokenTime(it)}" } ?: spokenTime(shownMs)
                    if (length != null) {
                        // One step a 10 s seek: TalkBack's swipe up or down moves it by one.
                        val seconds = length / 1_000f
                        progressBarRangeInfo = ProgressBarRangeInfo(shownMs / 1_000f, 0f..seconds, steps = (length / STEP_MS - 1).toInt().coerceAtLeast(0))
                        if (enabled) setProgress { target -> onSeek((target * 1_000).toLong().coerceIn(0, length)); true }
                    }
                    if (!enabled) disabled()
                }
                .then(gestures)
                .drawBehind {
                    val edge = GUTTER.toPx()
                    val width = size.width - 2 * edge
                    val y = size.height / 2
                    val bar = 4.dp.toPx()
                    drawRoundRect(track, Offset(edge, y - bar / 2), Size(width, bar), CornerRadius(bar / 2))
                    drawRoundRect(accent, Offset(edge, y - bar / 2), Size(width * done, bar), CornerRadius(bar / 2))
                    val x = edge + width * done
                    if (dragging) drawCircle(accent.copy(alpha = 0.22f), radius = 22.dp.toPx(), center = Offset(x, y))
                    drawCircle(accent, radius = (if (dragging) 12.dp else 8.dp).toPx(), center = Offset(x, y))
                },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = GUTTER)) {
            Text(
                mmss(shownMs), style = AppType.secondary.copy(fontWeight = if (dragging) FontWeight.SemiBold else null),
                color = if (dragging) colors.accent else colors.text, modifier = Modifier.weight(1f),
            )
            Text(length?.let(::mmss).orEmpty(), style = AppType.secondary, color = colors.muted)
        }
    }
}

/** Where [x] (from the strip's start, [edge] in from each side of its [width]) falls in the play's [length]. */
private fun at(x: Float, edge: Float, width: Float, length: Long): Long = (((x - edge) / (width - 2 * edge)).coerceIn(0f, 1f) * length).toLong()

/** One of the remote's round buttons: [size] across, [icon] at [iconSize], in [tint] on [background]; at 38 % while it waits. */
@Composable
private fun RoundButton(icon: Painter, label: String, size: Dp, iconSize: Dp, background: Color, tint: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(background).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** The title as TalkBack reads its heading (PtPhoneTalkBack 3, CsPhoneTalkBack 2): "Harbor Lights, season 2, episode 4, Low Water". */
fun remoteHeading(now: RemoteNow): String {
    val episodeName = now.subtitle?.takeIf { now.episodeLabel != null }?.substringAfter(" · ", "")?.takeIf { it.isNotEmpty() }
    return listOfNotNull(now.spokenTitle, episodeName).joinToString(", ")
}

/** The time bar's name for TalkBack (PtPhoneTalkBack 5). */
const val POSITION_ON_TV = "Position on the TV"

/** The ±10 s buttons' names, the app's words, as on its picture-in-picture buttons. */
const val REWIND_10 = "Rewind 10 seconds"
const val FAST_FORWARD_10 = "Fast-forward 10 seconds"

const val KEEPS_PLAYING = "It keeps playing there."
const val RECONNECTING = "Reconnecting…"

/** A tap's step on the remote, as the player's own. */
private const val STEP_MS = 10_000L

/** The time bar's strip reaches its ends 24 dp in from the screen's sides. */
private val GUTTER = 24.dp

/** A control that waits, as Material greys one. */
private const val DISABLED = 0.38f

/** A TV's play: no missing-parts prompt, notice or slow-stream banner of the phone's (the TV says its own). */
private val NO_PROMPT: StateFlow<CastPrompt?> = MutableStateFlow(null)
private val NO_NOTICE: StateFlow<String?> = MutableStateFlow(null)
private val NO_SLOW: StateFlow<CastSlowBanner?> = MutableStateFlow(null)

/** A build without Cast: no device's volume. */
private val NO_VOLUME: StateFlow<CastVolume?> = MutableStateFlow(null)

/** The picture's tile while there is none, as a poster's box is. */
private val PICTURE_TILE = Color(0xFF2E4A5C)

/** A poster's width in the picture's place. */
private const val POSTER_PX = 500
