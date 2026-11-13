package io.skjaere.usenet.app.link

import android.animation.ValueAnimator
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.app.ui.tryFocus

/**
 * What the Cast button shows (L113; CsPhoneButton): how it looks ([look]) and what TalkBack reads of it ([label], C1's
 * "Play on: …" words, which say where Play goes).
 */
data class ButtonFace(val look: Look, val label: String) {
    enum class Look {
        /** Google's Cast icon at rest, in the text colour: Play goes to this phone, and Cast can work here. */
        Rest,

        /** The TV icon at rest, as before C2: Play goes to this phone, which has no Google Play services, so no Cast. */
        NoPlayServices,

        /** A Cast device connecting or reconnecting: the chip's circle, its frame muted, the waves lighting in turn. */
        Connecting,

        /** A Cast device connected: Google's filled Cast icon in the amber circle. */
        Connected,

        /** A TV chosen that answers: the TV in the amber circle. */
        Tv,

        /** A TV chosen whose NZStreamer isn't open, or isn't reached yet: grey, the TV muted, with the orange dot. */
        TvNotOpen,
    }
}

/**
 * The Cast button's face for [target], Play on's, on a screen at [place] (L113, W59): This phone shows Google's Cast icon,
 * or the TV icon where Google Play services is known to be missing ([playServices] false; null while it isn't read yet,
 * which draws Google's icon); a Cast device its state, from any place; a chosen TV its own face on a title's page, and
 * This phone's where Play still plays here with a TV chosen (Search, the release lists, Import, the player). Null on a
 * [television]: a TV has no button (L110).
 */
fun castButtonFace(target: PlayTarget, place: SheetPlace, playServices: Boolean?, television: Boolean): ButtonFace? {
    if (television) return null
    val phone = ButtonFace(if (playServices == false) ButtonFace.Look.NoPlayServices else ButtonFace.Look.Rest, playOnLabel(PlayTarget.Phone))
    return when (target) {
        PlayTarget.Phone -> phone
        is PlayTarget.Tv -> if (place != SheetPlace.Title) phone else ButtonFace(if (target.open) ButtonFace.Look.Tv else ButtonFace.Look.TvNotOpen, playOnLabel(target))
        is PlayTarget.Cast -> ButtonFace(if (target.link == CastLink.Connected) ButtonFace.Look.Connected else ButtonFace.Look.Connecting, playOnLabel(target))
    }
}

/** The button's sizes in dp: its touch [target], the [circle] it fills once something is chosen (null: none), its [icon]. */
data class ButtonSizes(val target: Int, val circle: Int?, val icon: Int)

/**
 * The design's sizes for a face that [look]s so (CsPhoneButton): in a top bar a 48 dp target, the 24 dp icon at rest, a
 * 40 dp circle with a 22 dp icon once something is chosen; [round] in Home's search bar, a 40 dp circle with a 20 dp
 * icon whatever it shows, as round as Settings beside it.
 */
fun castButtonSizes(look: ButtonFace.Look, round: Boolean): ButtonSizes = when {
    round -> ButtonSizes(40, 40, 20)
    look == ButtonFace.Look.Rest || look == ButtonFace.Look.NoPlayServices -> ButtonSizes(48, null, 24)
    else -> ButtonSizes(48, 40, 22)
}

/**
 * The waves while a Cast device connects (L113, W66): the design's loop of about 1.3 s, a quarter with none lit, then the
 * corner wave (①), then the middle one too (②), then all three (③). With the phone's "Remove animations" on they stay on
 * ③. Whether they move is read through [enabled], Android's own switch by default, which a test sets rather than change
 * the phone's animator scale, which a test process stopped mid-run would never put back.
 */
object CastButtonMotion {
    const val PERIOD_MS = 1_300

    /** ③, all three lit: where the waves stay without animations. */
    const val STILL = 3

    private const val STEPS = 4

    /** Whether the phone runs animations: false with "Remove animations" on. */
    @VisibleForTesting
    @Volatile
    var enabled: () -> Boolean = { ValueAnimator.areAnimatorsEnabled() }

    /** Whether the waves move now, as [enabled] says. */
    fun moves(): Boolean = enabled()

    /** How many waves are lit, from the corner out, at [fraction] of the loop; [STILL] where they don't [moves]. */
    fun waves(moves: Boolean, fraction: Float): Int = if (moves) (fraction * STEPS).toInt().coerceIn(0, STILL) else STILL
}

/** The Cast button's face as its semantics carry it, for tests: what it shows, as [ButtonFace.Look] names it. */
val CastButtonLook = SemanticsPropertyKey<ButtonFace.Look>("CastButtonLook")
private var SemanticsPropertyReceiver.castButtonLook by CastButtonLook

/**
 * The Cast button (W43, L113, L114; CsPhoneButton): one button that says where Play goes from this screen ([place]), by
 * Play on's [target], and opens the Play on list ([onClick]), which holds your TVs and the Cast devices. Its face is
 * [castButtonFace]'s at the design's sizes ([castButtonSizes]): [round] in Home's search bar, else a top bar's. Drawing it
 * calls nothing of Google's: Google Play services is known through the app's castAvailable (L146), which reads package
 * facts alone. Never on a TV. TalkBack reads where Play goes ([ButtonFace.label]). It tells the shell where it is
 * ([LocalCastButtonSpot]), for the introduction that circles it (L123), presses it and gives it the focus as it closes.
 */
@Composable
fun CastButton(target: PlayTarget, onClick: () -> Unit, modifier: Modifier = Modifier, round: Boolean = false, place: SheetPlace = SheetPlace.Title) {
    val container = LocalContext.current.container
    val playServices by container.castAvailable.collectAsStateWithLifecycle()
    val face = castButtonFace(target, place, playServices, container.television) ?: return
    val sizes = castButtonSizes(face.look, round)
    val spot = LocalCastButtonSpot.current
    val press by rememberUpdatedState(onClick)
    val focus = remember { FocusRequester() }
    // Focusable in touch mode only while the introduction hands it the focus as it closes, which TalkBack's follows.
    var taking by remember { mutableStateOf(false) }
    val owner = remember { Any() }
    if (spot != null) DisposableEffect(spot) { onDispose { spot.left(owner) } }
    val placed = if (spot == null) {
        Modifier
    } else {
        Modifier
            .onGloballyPositioned { at ->
                spot.place(owner, at.boundsInRoot(), press = { press() }) {
                    taking = true
                    focus.tryFocus()
                }
            }
            .focusRequester(focus)
            .focusProperties { if (taking) canFocus = true }
            .onFocusChanged { if (!it.isFocused) taking = false }
    }
    IconButton(
        onClick = onClick,
        modifier = modifier.size(sizes.target.dp).focusOutline(CircleShape, color = if (round) colors.text else null, fill = false).then(placed)
            .semantics {
                contentDescription = face.label
                castButtonLook = face.look
            },
    ) {
        val icon = sizes.icon.dp
        val circle = (sizes.circle ?: 0).dp
        when (face.look) {
            ButtonFace.Look.Rest, ButtonFace.Look.NoPlayServices -> {
                val vector = if (face.look == ButtonFace.Look.Rest) CastIcon else TvIcon
                if (sizes.circle == null) Icon(vector, contentDescription = null, tint = colors.text, modifier = Modifier.size(icon))
                else Disc(circle, colors.chip) { Icon(vector, contentDescription = null, tint = colors.text, modifier = Modifier.size(icon)) }
            }
            ButtonFace.Look.Connecting -> Disc(circle, colors.chip) { Waves(icon) }
            ButtonFace.Look.Connected -> Glyph(CastConnectedIcon, circle, icon, colors.accent, colors.onAccent)
            ButtonFace.Look.Tv -> Glyph(TvIcon, circle, icon, colors.accent, colors.onAccent)
            ButtonFace.Look.TvNotOpen -> Box(Modifier.size(circle)) {
                Glyph(TvIcon, circle, icon, colors.chip, colors.muted)
                // The hub rows' orange dot at its corner, ringed in the page's colour.
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = (-5).dp, y = 5.dp).size(8.dp).clip(CircleShape).background(colors.warnStrong)
                        .border(2.dp, colors.background, CircleShape),
                )
            }
        }
    }
}

/** A [size] circle in [fill], [content] in its middle. */
@Composable
private fun Disc(size: Dp, fill: Color, content: @Composable () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) { content() }
}

/** [vector], [icon] large in [tint], in a [size] circle in [fill]. */
@Composable
private fun Glyph(vector: ImageVector, size: Dp, icon: Dp, fill: Color, tint: Color) {
    Disc(size, fill) { Icon(vector, contentDescription = null, tint = tint, modifier = Modifier.size(icon)) }
}

/**
 * Google's Cast icon as it connects (CsPhoneButton): the frame muted, the waves lit in the accent from the corner out as
 * [CastButtonMotion] says, the others faint. The loop runs through rememberInfiniteTransition (L143), read where it is
 * drawn, so it only redraws.
 */
@Composable
private fun Waves(size: Dp) {
    val muted = colors.muted
    val lit = colors.accent
    val moves = remember { CastButtonMotion.moves() }
    val at = if (moves) {
        rememberInfiniteTransition(label = "cast button").animateFloat(
            0f, 1f, infiniteRepeatable(tween(CastButtonMotion.PERIOD_MS, easing = LinearEasing)), label = "waves",
        )
    } else {
        null
    }
    Canvas(Modifier.size(size)) {
        val shown = CastButtonMotion.waves(moves, at?.value ?: 0f)
        scale(this.size.minDimension / ICON_GRID, pivot = Offset.Zero) {
            drawPath(CastParts.frame, muted)
            CastParts.waves.forEachIndexed { i, wave -> if (i < shown) drawPath(wave, lit) else drawPath(wave, muted, alpha = UNLIT) }
        }
    }
}

/** Google's icon's frame and waves as paths, on its 24-unit grid, made once. */
private object CastParts {
    val frame: Path by lazy { PathParser().parsePathString(CAST_FRAME).toPath() }
    val waves: List<Path> by lazy { CAST_WAVES.map { PathParser().parsePathString(it).toPath() } }
}

private const val ICON_GRID = 24f

/** A wave not lit yet: the frame's grey, faint (the design's 0.32). */
private const val UNLIT = 0.32f
