package io.skjaere.usenet.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/** The sizes of [AppType]'s roles on a form factor. */
@Immutable
data class TypeScale(
    /** The title of a page about one title (the movie page's). */
    val hero: TextStyle,
    val screenTitle: TextStyle,
    val cardTitle: TextStyle,
    val section: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val secondary: TextStyle,
    val small: TextStyle,
    val release: TextStyle,
    /** Small capitals on a pill: NEW, the collection's counts. */
    val tag: TextStyle,
    /** An address to type by hand (the phone setup's): monospaced, so 0 and o, 1 and l can be told apart. */
    val address: TextStyle,
) {
    companion object {
        val Phone = TypeScale(
            hero = TextStyle(fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 28.sp),
            screenTitle = TextStyle(fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 20.sp),
            cardTitle = TextStyle(fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 16.sp),
            section = TextStyle(fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.8.sp),
            body = TextStyle(fontFamily = PlexSans, fontSize = 15.sp),
            bodyStrong = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
            secondary = TextStyle(fontFamily = PlexSans, fontSize = 13.sp, lineHeight = 19.sp),
            small = TextStyle(fontFamily = PlexSans, fontSize = 12.sp, lineHeight = 17.sp),
            release = TextStyle(fontFamily = PlexMono, fontSize = 11.5.sp, lineHeight = 16.sp),
            tag = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 17.sp),
            address = TextStyle(fontFamily = PlexMono, fontSize = 15.sp, lineHeight = 21.sp),
        )

        /**
         * Read from about 3 m: each role in the phone's font, weight and tracking, never smaller, and nothing under
         * 12 sp. One step under the TV design's type table, whose sizes looked a little big on a TV: titles a little larger
         * than the phone's, body text the phone's size with set line heights.
         */
        val Tv = TypeScale(
            hero = Phone.hero.copy(fontSize = 26.sp, lineHeight = 32.sp),
            screenTitle = Phone.screenTitle.copy(fontSize = 22.sp, lineHeight = 28.sp),
            cardTitle = Phone.cardTitle.copy(fontSize = 17.sp, lineHeight = 22.sp),
            section = Phone.section.copy(fontSize = 13.sp, lineHeight = 18.sp),
            body = Phone.body.copy(fontSize = 15.sp, lineHeight = 20.sp),
            bodyStrong = Phone.bodyStrong.copy(fontSize = 15.sp, lineHeight = 20.sp),
            secondary = Phone.secondary.copy(fontSize = 13.sp, lineHeight = 19.sp),
            small = Phone.small.copy(fontSize = 12.sp, lineHeight = 17.sp),
            release = Phone.release.copy(fontSize = 12.sp, lineHeight = 17.sp),
            tag = Phone.tag.copy(fontSize = 12.sp, lineHeight = 17.sp),
            // Read off the screen and typed into a phone from across the room.
            address = Phone.address.copy(fontSize = 17.sp, lineHeight = 24.sp),
        )
    }
}

val LocalTypeScale = staticCompositionLocalOf { TypeScale.Phone }

/**
 * How a focused element shows. A phone draws a thin ring ([focusOutline]); a TV, seen from across the room, turns the
 * components the app owns over ([invert]: the text's colour fills them and the background's writes on them), grows them
 * a little, draws 3 dp rings round the rest, and shows a pointer remote's hover as the focus ([hover]). No glow and no
 * blur: TVs' graphics are slow.
 */
@Immutable
data class FocusStyle(
    /** The width of [focusOutline]'s ring and of a focused card's. */
    val outline: Dp,
    val invert: Boolean,
    /** How much a focused row, button and card grow, a pressed card stays grown, and anything else pressed shrinks. */
    val rowScale: Float,
    val buttonScale: Float,
    val cardScale: Float,
    val cardPressedScale: Float,
    val pressedScale: Float,
    val hover: Boolean,
    /** The focus and hover layers of material's controls that the app doesn't wrap, in the accent; null keeps material's own. */
    val ripple: RippleAlpha?,
) {
    companion object {
        val Phone = FocusStyle(
            outline = 2.dp, invert = false, rowScale = 1f, buttonScale = 1f, cardScale = 1f, cardPressedScale = 1f, pressedScale = 1f,
            hover = false, ripple = null,
        )

        /** As the TV design has it: a 2 dp ring is too faint at 3 m (#148). */
        val Tv = FocusStyle(
            outline = 3.dp, invert = true, rowScale = 1.02f, buttonScale = 1.05f, cardScale = 1.1f, cardPressedScale = 1.05f, pressedScale = 0.98f,
            hover = true, ripple = RippleAlpha(draggedAlpha = 0.16f, focusedAlpha = 0.28f, hoveredAlpha = 0.28f, pressedAlpha = 0.24f),
        )
    }
}

val LocalFocusStyle = staticCompositionLocalOf { FocusStyle.Phone }

/**
 * A TV's canvas, from Google's TV layout guide: 960 x 540 dp, with nothing focusable or textual
 * within [horizontal] of the sides or [vertical] of the top and bottom (TVs may crop the edges).
 * Backgrounds may reach the edges.
 */
object TvSafeArea {
    val horizontal = 48.dp
    val vertical = 27.dp

    /** Where the rail ends, its items starting at [horizontal]; widened while it has the focus. */
    val railEnd = 104.dp
    val railExpanded = 240.dp

    /** Where a tab's content starts, clear of the rail. */
    val contentStart = 128.dp

    /** The width the phone's screens take on a TV, centred: their rows would be too long to read across the whole screen. */
    val sharedMax = 720.dp
}

/**
 * How a TV's lists scroll to the focused element: its top to [parentFraction] of the list's height
 * (less [childFraction] of its own), so the focus moves down to that line and then the list moves
 * under it. An element too tall to show whole from there comes up as far as it needs. One already
 * on screen within [band] of the list's height of the line stays: a focused text field asks for
 * itself, then for its cursor a little further down, and taking each to the line would move the
 * list to and fro for ever (foundation's own pivot, its default on devices that declare Leanback,
 * does that). The TV shell sets this one on purpose, so a phone showing the TV's layouts scrolls as
 * a TV does too.
 */
class PivotBringIntoViewSpec(
    private val parentFraction: Float = LINE,
    private val childFraction: Float = 0f,
    private val band: Float = 0.1f,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val pivot = parentFraction * containerSize - childFraction * size
        val onScreen = offset >= 0f && offset + size <= containerSize
        if (onScreen && abs(offset - pivot) <= band * containerSize) return 0f
        val target = if (size <= containerSize && containerSize - pivot < size) containerSize - size else pivot
        return offset - target
    }

    companion object {
        /** Where the TV shell's pivot puts a focused element's top: this much of the list's height down. */
        const val LINE = 0.3f
    }
}

/**
 * The TV's card sizes: a poster 2:3, seven to a 784 dp line (the seventh peeking past a row's
 * padding); a landscape still 16:9, four to the 784 dp beside the rail whole, a row's 16 dp padding at each end
 * included; an episode row's still. The gutters leave room for a focused card's growth and ring, so it never covers
 * its neighbour.
 */
object TvDimens {
    val poster = DpSize(98.dp, 147.dp)
    val posterGutter = 16.dp

    /** Between a poster grid's lines (TV-4's mockup): the room under a line's titles and lines before the next posters. */
    val posterLineGap = 20.dp
    val landscape = DpSize(176.dp, 99.dp)
    val landscapeGutter = 16.dp
    val still = DpSize(112.dp, 63.dp)

    /** A title page's left pane (its poster, title and buttons) and the gap to its right pane, on the page's 864 dp. */
    val pagePane = 300.dp
    val paneGap = 24.dp

    /** A title page's poster, 2:3. */
    val pagePoster = DpSize(120.dp, 180.dp)

    /** The phone setup's QR code, quiet zone included: about 3.3 m away a phone's camera still reads its modules. */
    val qr = 240.dp
}

/** How rows and chips scroll: only as far as the focused element needs to show whole. */
val EdgeBringIntoViewSpec: BringIntoViewSpec = object : BringIntoViewSpec {}

/** As [EdgeBringIntoViewSpec], with [room] (px) past each of the element's ends shown too: a focused chip's growth. */
fun edgeBringIntoViewSpec(room: Float): BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        EdgeBringIntoViewSpec.calculateScrollDistance(offset - room, size + 2 * room, containerSize)
}

/**
 * [content] (a horizontal row) scrolls only as far as its focused element needs, also under a TV's pivot; [room] past its
 * ends too, for what grows when focused (the row's content padding as much, so its first and last can have it).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EdgeScrolling(room: Dp = 0.dp, content: @Composable () -> Unit) {
    val px = with(LocalDensity.current) { room.toPx() }
    val spec = remember(px) { if (px == 0f) EdgeBringIntoViewSpec else edgeBringIntoViewSpec(px) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}
