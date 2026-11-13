package io.skjaere.usenet.app.link

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.skjaere.usenet.app.CastExtras
import io.skjaere.usenet.app.casting.CAST_CONNECTED
import io.skjaere.usenet.app.casting.CAST_FROM_ANOTHER
import io.skjaere.usenet.app.casting.CastDeviceMedia
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.casting.CastLinkNow
import io.skjaere.usenet.app.casting.CastNow
import io.skjaere.usenet.app.casting.CastStop
import io.skjaere.usenet.app.casting.CastStopRule
import io.skjaere.usenet.app.casting.CastVolume
import io.skjaere.usenet.app.casting.DISCONNECT
import io.skjaere.usenet.app.casting.STOP_CASTING
import io.skjaere.usenet.app.casting.disconnectFrom
import io.skjaere.usenet.app.casting.ownVolume
import io.skjaere.usenet.app.casting.playingOn
import io.skjaere.usenet.app.casting.playingThere
import io.skjaere.usenet.app.casting.stopCastingTo
import io.skjaere.usenet.app.casting.volumeOn
import io.skjaere.usenet.app.casting.volumePercent
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.colors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The connected Cast device's block at the top of Play on's list (W47, W49, L117, L154; CsPhoneSheetConnected), as it
 * reads: the device's [name], a heading; its [line] under it ("Connected", what plays there, or reconnecting) and how
 * TalkBack reads it ([spokenLine]); the round pause or play button while this phone's film is there ([toggle], acting
 * while the device's player has it and the session is up: [toggleActs]); the volume, which takes drags while the session
 * is up ([connected]); the device's own-volume line once it refused a change in this session ([fixed], M1-A); the line
 * for another phone's film ([fromAnother]); and Stop casting or Disconnect ([stop]).
 */
data class CastBlockLook(
    val name: String,
    val line: RowLine,
    val spokenLine: String,
    val toggle: Toggle?,
    val toggleActs: Boolean,
    val connected: Boolean,
    val fromAnother: Boolean,
    val fixed: Boolean,
    val stop: CastStop,
) {
    /** The round button: pause while it plays, play while it is paused. */
    enum class Toggle { Pause, Play }

    val toggleWords: String?
        get() = when (toggle) {
            Toggle.Pause -> pauseOn(name)
            Toggle.Play -> playOnTv(name)
            null -> null
        }

    val stopWords: String get() = if (stop == CastStop.StopCasting) STOP_CASTING else DISCONNECT

    /** The button to TalkBack: "Stop casting to Kitchen display", "Disconnect from Kitchen display". */
    val stopSpoken: String get() = if (stop == CastStop.StopCasting) stopCastingTo(name) else disconnectFrom(name)

    val fixedLine: String? get() = ownVolume(name).takeIf { fixed }

    val anotherLine: String? get() = CAST_FROM_ANOTHER.takeIf { fromAnother }

    /** TalkBack's reading, from the heading to the button (CsPhoneTalkBackC2 5-9), the volume's level aside. */
    val spoken: List<String>
        get() = listOfNotNull(
            "$name, heading", spokenLine, toggleWords?.let { "$it, button" }, "${volumeOn(name)}, slider", fixedLine, anotherLine, "$stopSpoken, button",
        )
}

/**
 * The block for the Cast session [now] (L117), while it is connected or reconnecting; none while it connects, or with
 * none. What it says plays there: this phone's film ([ours]: the cast play the bar follows, on this device, while it
 * lives), else another sender's ([media], by the title and line it gave: L154), else "Connected". The button by
 * [CastStopRule]: this phone's cast play ([casting], from its tap) or nothing playing, Stop casting; another's, Disconnect,
 * with a line saying it goes on playing. [fixed]: the device refused a change of volume in this session (M1-A), never what
 * its route says.
 */
fun castBlockLook(now: CastLinkNow?, media: CastDeviceMedia?, ours: RemoteNow?, casting: Boolean, fixed: Boolean): CastBlockLook? {
    if (now == null || now.link == CastLink.Connecting) return null
    val connected = now.link == CastLink.Connected
    val mine = ours?.takeIf { it.live }
    val stop = CastStopRule.of(casting || mine != null, media)
    val theirs = media?.takeIf { stop == CastStop.Disconnect }
    val (line, spoken) = when {
        !connected -> RowLine(RECONNECTING, RowLine.Tone.Muted) to RECONNECTING
        mine != null -> RowLine(playingThere(mine.label), RowLine.Tone.Good) to playingThere(mine.spokenTitle)
        theirs != null -> {
            val words = listOfNotNull(theirs.title, theirs.line).filter { it.isNotBlank() }.takeIf { theirs.title?.isNotBlank() == true }
            if (words == null) {
                RowLine(playingOn(now.name), RowLine.Tone.Good) to playingOn(now.name)
            } else {
                RowLine(playingThere(words.joinToString(" · ")), RowLine.Tone.Good) to playingThere(words.joinToString(", "))
            }
        }
        else -> RowLine(CAST_CONNECTED, RowLine.Tone.Good) to CAST_CONNECTED
    }
    val toggle = mine?.let { if (it.shows == RemoteBar.Paused) CastBlockLook.Toggle.Play else CastBlockLook.Toggle.Pause }
    return CastBlockLook(
        now.name, line, spoken, toggle, toggleActs = connected && mine?.controls == true, connected = connected, fromAnother = theirs != null,
        fixed = fixed, stop = stop,
    )
}

/**
 * The connected Cast device's block (L117; CsPhoneSheetConnected), over Play on's list wherever it shows, whatever the
 * Cast group says (the opt-in turned off while casting too, L112): Google's filled Cast icon in the accent and the
 * device's name as the title; its line in green; the round pause or play button for this phone's film there; the volume
 * (L118); the lines; and an outlined Stop casting (■) or Disconnect. Then the rule, and the list. Nothing while no session
 * is connected or reconnecting. Reading it calls nothing of Google's.
 */
@Composable
internal fun CastBlock(extras: CastExtras) {
    val now by extras.now.collectAsStateWithLifecycle()
    val media by extras.media.collectAsStateWithLifecycle()
    val casting by extras.playing.collectAsStateWithLifecycle()
    val volume by extras.volume.collectAsStateWithLifecycle()
    val refused by remember(volume) { volume?.refused ?: NOT_REFUSED }.collectAsStateWithLifecycle()
    val castPlaying = LocalContext.current.container.castPlaying
    val played by remember(castPlaying) { castPlaying?.now ?: NO_PLAY }.collectAsStateWithLifecycle()
    val look = castBlockLook(now, media, played?.takeIf { it.route == now?.route }, casting, refused) ?: return
    Column(Modifier.padding(bottom = 4.dp)) {
        Row(Modifier.padding(start = 20.dp, end = 12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(CastConnectedIcon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(look.name, style = AppType.screenTitle, color = colors.text, modifier = Modifier.semantics { heading() })
                // Read as words, "Playing Harbor Lights, season 2, episode 4", not as the line's marks.
                LineView(look.line, Modifier.semantics(mergeDescendants = true) { contentDescription = look.spokenLine })
            }
            look.toggle?.let { toggle ->
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(colors.chip).alpha(if (look.toggleActs) 1f else DISABLED)
                        .clickable(enabled = look.toggleActs, role = Role.Button) {
                            if (toggle == CastBlockLook.Toggle.Pause) castPlaying?.pause() else castPlaying?.resume()
                        }
                        .semantics { contentDescription = look.toggleWords.orEmpty() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (toggle == CastBlockLook.Toggle.Pause) PauseIcon else Icons.Filled.PlayArrow, contentDescription = null, tint = colors.text, modifier = Modifier.size(24.dp))
                }
            }
        }
        volume?.let { CastVolumeSlider(look.name, it, enabled = look.connected, inset = 20.dp, top = 8.dp) }
        look.anotherLine?.let { Text(it, style = AppType.small, color = colors.muted, modifier = Modifier.padding(start = 20.dp, top = 6.dp, end = 20.dp)) }
        OutlinedButton(
            onClick = if (look.stop == CastStop.StopCasting) extras::stop else extras::disconnect,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp).fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = look.stopSpoken },
            shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, colors.outline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text),
        ) {
            if (look.stop == CastStop.StopCasting) Icon(StopIcon, contentDescription = null, modifier = Modifier.padding(end = 10.dp).size(20.dp))
            Text(look.stopWords, style = AppType.body)
        }
    }
    Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().height(1.dp).background(colors.divider))
    Spacer(Modifier.height(8.dp))
}

/**
 * A Cast device's volume (L118, M1-A; CsPhoneSheetConnected, CsPhoneRemoteC2): the speaker mark, then Material's slider
 * in the accent on the chip's colour, [inset] from the sides and [top] under what is above it; the same in the list's
 * block and on the remote. It follows the device while it shows; a drag sends each step it reaches, and 1 s after the
 * finger lifts the session's own level is read again. Under it, once the device refused a change in this session, the
 * line saying it sets its own volume, which TalkBack reads once as it appears. While the session reconnects ([enabled]
 * false) it waits, greyed. For TalkBack it is "Volume on Kitchen display, 45 percent, slider", a swipe up or down one
 * step, 5 %; with a keyboard or a D-pad, Right and Left one step too, and Up and Down on to the next control, as
 * Android's own sliders have them ([volumeKey]).
 */
@Composable
internal fun CastVolumeSlider(name: String, volume: CastVolume, enabled: Boolean, inset: Dp, top: Dp) {
    DisposableEffect(volume) {
        val heard = volume.listen()
        onDispose { heard.close() }
    }
    val percent by volume.percent.collectAsStateWithLifecycle()
    val refused by volume.refused.collectAsStateWithLifecycle()
    val acts = enabled && volume.settable
    val focus = LocalFocusManager.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Each drag's end, this slider's or another's showing too (the list's over the remote): the session's own level is
    // read once its second is up, the wait begun again by a drag that ends before then, or by the slider shown again
    // before a drag's re-read was made.
    val ended by volume.ends.collectAsStateWithLifecycle()
    LaunchedEffect(volume, ended) {
        if (!volume.waiting) return@LaunchedEffect
        delay(CastVolume.REREAD_MS)
        volume.reread()
    }
    Row(Modifier.padding(start = inset, top = top, end = inset).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(SoundIcon, contentDescription = null, tint = colors.muted, modifier = Modifier.size(24.dp).alpha(if (acts) 1f else DISABLED))
        Slider(
            value = percent.toFloat(), onValueChange = { volume.drag(it) },
            onValueChangeFinished = volume::released,
            enabled = acts, valueRange = 0f..100f,
            colors = SliderDefaults.colors(
                thumbColor = colors.accent, activeTrackColor = colors.accent, inactiveTrackColor = colors.chip, activeTickColor = colors.accent,
                inactiveTickColor = colors.accent, disabledThumbColor = colors.muted.copy(alpha = DISABLED), disabledActiveTrackColor = colors.muted.copy(alpha = DISABLED),
                disabledInactiveTrackColor = colors.chip.copy(alpha = DISABLED),
            ),
            modifier = Modifier.weight(1f).onPreviewKeyEvent { volumeKey(it, volume, acts, focus, rtl) }.clearAndSetSemantics {
                contentDescription = volumeOn(name)
                stateDescription = volumePercent(percent)
                // One step 5 %: TalkBack's swipe up or down moves it by one.
                progressBarRangeInfo = ProgressBarRangeInfo(percent.toFloat(), 0f..100f, steps = CastVolume.MAX - 1)
                if (acts) {
                    setProgress { to ->
                        volume.drag(to)
                        volume.released()
                        true
                    }
                } else {
                    disabled()
                }
            },
        )
    }
    // Read once by TalkBack as it appears, as the slider snaps back with no word of why; then after the slider, as written.
    if (refused) {
        Text(
            ownVolume(name), style = AppType.small, color = colors.muted,
            modifier = Modifier.padding(start = inset + 36.dp, top = 2.dp, end = inset).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * A key on the slider ([CastVolumeSlider]): Right and Left one 5 % step up or down (the other way round [rtl]), as
 * TalkBack's swipe, where Material's own would move it a hundredth, which no step takes; Up and Down move the focus on, as
 * Android's own sliders let them, so a D-pad leaves it. Whether the key was taken.
 */
private fun volumeKey(event: KeyEvent, volume: CastVolume, acts: Boolean, focus: FocusManager, rtl: Boolean): Boolean {
    val by = when (event.key) {
        Key.DirectionRight -> if (rtl) -1 else 1
        Key.DirectionLeft -> if (rtl) 1 else -1
        Key.DirectionUp, Key.DirectionDown -> {
            if (event.type == KeyEventType.KeyDown) focus.moveFocus(if (event.key == Key.DirectionUp) FocusDirection.Up else FocusDirection.Down)
            return true
        }
        else -> return false
    }
    if (!acts) return false
    when (event.type) {
        KeyEventType.KeyDown -> volume.step(by)
        KeyEventType.KeyUp -> volume.released()
    }
    return true
}

/** No session's volume: nothing refused. */
private val NOT_REFUSED: StateFlow<Boolean> = MutableStateFlow(false)

/** A build or device with no cast play to follow. */
private val NO_PLAY: StateFlow<CastNow?> = MutableStateFlow(null)

/** A control that waits, as Material greys one. */
private const val DISABLED = 0.38f
