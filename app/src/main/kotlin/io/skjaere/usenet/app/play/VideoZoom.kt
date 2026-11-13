package io.skjaere.usenet.app.play

import io.skjaere.usenet.data.AspectMode
import io.skjaere.usenet.data.PlaybackPrefs
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max

/** A rectangle in the player, in px: the room beside the camera, a camera's bounds, or the part of the picture that shows. */
data class Area(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** How far something reaches in from each edge of the player, in px. */
data class Sides(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0) {
    val none: Boolean get() = left <= 0 && top <= 0 && right <= 0 && bottom <= 0
}

/**
 * The display cutout as the player's window has it, in the window's px (the player fills the
 * window): its [safe] insets on the edges it is on, and the bounding [rects] of what it cuts out,
 * the front camera. None where the screen has none, before Android 9, or with the window kept out
 * of it (as Samsung's per-app camera cutout setting can).
 */
data class Cutout(val safe: Sides = Sides(), val rects: List<Area> = emptyList()) {
    /**
     * The strip along each edge of a [width]×[height] window that Fill beside camera keeps the
     * picture out of: on an edge with a safe inset, as far in as the bounding rects in the window
     * that start inside the inset reach, no further than the inset; none elsewhere. The rects
     * rather than the inset alone: the inset can be deeper than the camera (padded to the status
     * bar's height on many phones), and on a screen with curved edges it holds those too, which
     * are no camera. In the window: one that reaches the camera's edge gets the cutout with its
     * rects moved into the window's px but not cut to it, so in the other half of a split screen
     * the camera can be wholly outside it.
     */
    fun strips(width: Int, height: Int): Sides {
        val inWindow = rects.filter { it.right > 0 && it.left < width && it.bottom > 0 && it.top < height }
        fun reach(inset: Int, inside: (Area) -> Boolean, depth: (Area) -> Int) =
            if (inset <= 0) 0 else inWindow.filter(inside).maxOfOrNull(depth)?.coerceIn(0, inset) ?: 0
        return Sides(
            left = reach(safe.left, { it.left < safe.left }, { it.right }),
            top = reach(safe.top, { it.top < safe.top }, { it.bottom }),
            right = reach(safe.right, { it.right > width - safe.right }, { width - it.left }),
            bottom = reach(safe.bottom, { it.bottom > height - safe.bottom }, { height - it.top }),
        )
    }
}

/**
 * Where the picture shows: laid out by Fit at [fitWidth]×[fitHeight] (media3's content frame,
 * the video's shape), centred in the player's [viewWidth]×[viewHeight], which fills the window and
 * has its [cutout].
 */
data class PictureFrame(
    val fitWidth: Int,
    val fitHeight: Int,
    val viewWidth: Int,
    val viewHeight: Int,
    val cutout: Cutout = Cutout(),
) {
    /** Whether the picture has been laid out: before that, it shows as it is. */
    internal val known get() = fitWidth > 0 && fitHeight > 0 && viewWidth > 0 && viewHeight > 0
    private val widthScale get() = if (known) viewWidth.toFloat() / fitWidth else 1f
    private val heightScale get() = if (known) viewHeight.toFloat() / fitHeight else 1f

    /** Fill screen's scale over Fit's: the smallest that covers the view, as media3's RESIZE_MODE_ZOOM. 1 until laid out. */
    val fill: Float get() = max(widthScale, heightScale)

    /** A pinch's largest scale: [VideoZoom.MAX_SCALE], or Fill screen's where that is more (a tall picture). */
    val maxScale: Float get() = max(VideoZoom.MAX_SCALE, fill)

    /** The strips along the edges the camera is on ([Cutout.strips]); none until the view is laid out. */
    val camera: Sides get() = if (viewWidth > 0 && viewHeight > 0) cutout.strips(viewWidth, viewHeight) else Sides()

    /** The view but for [camera]'s strips: the room beside the camera, where Fill beside camera shows the picture. */
    val besideCamera: Area get() = camera.let { Area(it.left, it.top, viewWidth - it.right, viewHeight - it.bottom) }

    /** Whether there is a camera to keep the picture clear of, and room beside it: else Fill beside camera would be Fill screen. */
    val hasCamera: Boolean get() = !camera.none && besideCamera.width > 0 && besideCamera.height > 0

    /** Fill beside camera's scale over Fit's: the smallest that covers [besideCamera], as Fill screen's covers the view. 1 until laid out. */
    val besideCameraFill: Float get() = if (known) besideCamera.let { max(it.width.toFloat() / fitWidth, it.height.toFloat() / fitHeight) } else 1f
}

/**
 * How the player shows the picture: the video surface scaled by [scales] over Fit's about its
 * centre, moved by [shift] px and cut to [clip], in its own px as Fit lays it out (null: all of
 * it). What it leaves of the screen is [black] where it is cut beside the camera, and during a
 * pinch from Fill beside camera; else the window's own background, as Fit's bars.
 */
data class Look(val scales: Pair<Float, Float>, val shift: Pair<Float, Float> = 0f to 0f, val clip: Area? = null, val black: Boolean = clip != null)

/**
 * How the player sizes the picture, kept apart from it so it can be tested: the aspect button's
 * [mode], and for a pinch's [AspectMode.Zoom] its [scale] (a multiple of Fit's; 1 for the other
 * modes). The player scales only the video surface by [scales], and for Fill beside camera moves
 * it by [shift] and cuts it to [clip] ([look]; during a pinch, [pinched]), so the subtitles and
 * the overlays stay where Fit puts them.
 *
 * The button goes Fit → Fill screen → Fit, with Fill beside camera after Fill screen where a
 * camera is beside the picture ([PictureFrame.hasCamera]); from a pinch's zoom, on to Fill screen.
 * None changes the picture's shape: they crop the bars away or zoom, never stretch. Without a
 * camera a kept Fill beside camera shows as Fill screen ([shownAs]) and stays the choice, for a
 * screen or a way round that has one. A pinch scales the picture between Fit and
 * [PictureFrame.maxScale], and where it ends ([afterPinch]) it snaps to Fit, Fill screen or Fill
 * beside camera when close to one, or back to where it started.
 */
data class VideoZoom(val mode: AspectMode = AspectMode.Fit, val scale: Float = 1f) {
    val label: String get() = labelOf(mode)

    /**
     * As it shows with a [camera] beside the picture or without one: without, Fill beside camera
     * shows as the Fill screen it would be, and stays the choice.
     */
    fun shownAs(camera: Boolean): VideoZoom = if (mode == AspectMode.FillBesideCamera && !camera) VideoZoom(AspectMode.Fill) else this

    /** The button's content description, with a [camera] beside the picture or without: the mode showing, and what a press does. */
    fun description(camera: Boolean): String = "Aspect: ${shownAs(camera).label}. Switch to ${next(camera).label}"

    /** What a press of the button gives: after Fill screen, Fill beside camera where there is a [camera] to keep clear of. */
    fun next(camera: Boolean): VideoZoom = VideoZoom(
        when (shownAs(camera).mode) {
            AspectMode.Fit -> AspectMode.Fill
            AspectMode.Fill -> if (camera) AspectMode.FillBesideCamera else AspectMode.Fit
            AspectMode.FillBesideCamera -> AspectMode.Fit
            AspectMode.Zoom -> AspectMode.Fill
        },
    )

    /** The picture's scale on each axis over Fit's, in [frame]; a saved zoom held to the frame's range. */
    fun scales(frame: PictureFrame): Pair<Float, Float> = if (!frame.known) 1f to 1f else when (shownAs(frame.hasCamera).mode) {
        AspectMode.Fit -> 1f to 1f
        AspectMode.Fill -> frame.fill.let { it to it }
        AspectMode.FillBesideCamera -> frame.besideCameraFill.let { it to it }
        AspectMode.Zoom -> held(scale, frame).let { it to it }
    }

    /** How far the picture's centre moves from the view's, in px: for Fill beside camera to the middle of the room beside the camera; else none. */
    fun shift(frame: PictureFrame): Pair<Float, Float> = look(frame).shift

    /**
     * The part of the picture that shows, in its own px as Fit lays it out: for Fill beside camera
     * a picture wider (or taller) than the room beside the camera is cut where it meets the strip,
     * never inside it, so the strip stays clear; past the screen's other edges nothing shows
     * anyway. Null (all of it) otherwise.
     */
    fun clip(frame: PictureFrame): Area? = look(frame).clip

    /** How the player shows the picture in [frame]: by [scales], [shift] and [clip]. */
    fun look(frame: PictureFrame): Look = if (besideCamera(frame)) beside(frame, frame.besideCameraFill, 1f) else Look(scales(frame))

    /**
     * How a pinch from this zoom shows the picture at [scale] in [frame]: as a zoom of that scale,
     * but from Fill beside camera by degrees, so no bars open beside the camera. Growing, the
     * picture moves into the strip as it nears Fill screen's scale, all of it there (or [GLIDE]
     * past where it started, where Fill screen's is no further); shrinking, it moves back to the
     * screen's centre by Fit's. What it leaves of the screen stays black till the pinch ends.
     */
    fun pinched(scale: Float, frame: PictureFrame): Look {
        val s = held(scale, frame)
        if (!besideCamera(frame)) return zoom(s).look(frame)
        val start = frame.besideCameraFill
        // How much of the strip it keeps out of: all at Fill beside camera's scale, none by Fit's or once it is all in the strip.
        val share = if (s < start) (s - 1) / (start - 1) else max(frame.fill, start * (1 + GLIDE)).let { end -> (end - s) / (end - start) }
        return beside(frame, s, share.coerceIn(0f, 1f)).copy(black = true)
    }

    /**
     * The picture at [scale] in [frame], kept out of [share] of the camera's strips (1: all of
     * them, as deep as they are): centred in the room they leave, and cut where it meets them,
     * never inside them; past the screen's other edges nothing shows anyway. With no share, a
     * zoom's look.
     */
    private fun beside(frame: PictureFrame, scale: Float, share: Float): Look {
        if (share <= 0f) return Look(scale to scale)
        val strips = frame.camera
        // The room the strips leave, in the view's px, and its middle, where the picture's is.
        val left = strips.left * share
        val top = strips.top * share
        val right = frame.viewWidth - strips.right * share
        val bottom = frame.viewHeight - strips.bottom * share
        val cx = (left + right) / 2
        val cy = (top + bottom) / 2
        // The room's edges in the picture's px.
        fun x(edge: Float) = (edge - cx) / scale + frame.fitWidth / 2f
        fun y(edge: Float) = (edge - cy) / scale + frame.fitHeight / 2f
        // Rounded into the room, so the cut is never inside the strip.
        fun up(at: Float, size: Int) = ceil(at - NOISE).toInt().coerceIn(0, size)
        fun down(at: Float, size: Int) = floor(at + NOISE).toInt().coerceIn(0, size)
        val clip = Area(
            left = if (strips.left > 0) up(x(left), frame.fitWidth) else 0,
            top = if (strips.top > 0) up(y(top), frame.fitHeight) else 0,
            right = if (strips.right > 0) down(x(right), frame.fitWidth) else frame.fitWidth,
            bottom = if (strips.bottom > 0) down(y(bottom), frame.fitHeight) else frame.fitHeight,
        )
        return Look(scale to scale, cx - frame.viewWidth / 2f to cy - frame.viewHeight / 2f, clip)
    }

    /** The scale a pinch starts from: the one showing. */
    fun pinchStart(frame: PictureFrame): Float = when (shownAs(frame.hasCamera).mode) {
        AspectMode.Fit -> 1f
        AspectMode.Fill -> frame.fill
        AspectMode.FillBesideCamera -> frame.besideCameraFill
        AspectMode.Zoom -> held(scale, frame)
    }

    /**
     * Where a pinch from this zoom that ended at [scale] leaves the picture in [frame]: held to Fit
     * and the maximum, then as it was when that close to where it started ([pinchStart]), so two
     * fingers that hardly moved change nothing; or Fit, Fill screen or (with a camera beside the
     * picture) Fill beside camera when that close to one; the nearest of them (where they tie, as
     * it was, then Fit, then Fill screen); else a zoom of its own.
     */
    fun afterPinch(scale: Float, frame: PictureFrame): VideoZoom {
        val s = held(scale, frame)
        fun distance(target: Float) = abs(ln(s / target))
        val besideCamera = (VideoZoom(AspectMode.FillBesideCamera) to frame.besideCameraFill).takeIf { frame.hasCamera }
        val snapped = listOfNotNull(this to pinchStart(frame), VideoZoom() to 1f, VideoZoom(AspectMode.Fill) to frame.fill, besideCamera)
            .filter { (_, target) -> distance(target) <= ln(1 + SNAP) }
            .minByOrNull { (_, target) -> distance(target) }
        return snapped?.first ?: zoom(s)
    }

    /** [prefs] with this choice in them, for the next play. */
    fun saveIn(prefs: PlaybackPrefs): PlaybackPrefs =
        prefs.copy(aspectMode = mode.name, zoomScale = if (mode == AspectMode.Zoom) scale else 1f)

    /** Whether it shows as Fill beside camera in [frame]: laid out, with a camera beside it. */
    private fun besideCamera(frame: PictureFrame) = mode == AspectMode.FillBesideCamera && frame.known && frame.hasCamera

    companion object {
        /** A pinch's largest zoom over Fit: enough for a picture windowboxed in a 4:3 frame to fill a wide screen. */
        const val MAX_SCALE = 3f
        /** How close a pinch must end to Fit, Fill screen or Fill beside camera to snap to it, as a fraction of the scale. */
        const val SNAP = 0.08f
        /**
         * How far past Fill beside camera's scale a pinch from it takes the picture all into the
         * strip beside the camera where Fill screen's is no further (a picture as wide as the room
         * beside the camera or wider): a glide, not a jump.
         */
        const val GLIDE = 0.04f
        /** A clip's edge this close to a whole px is on it: what is left of float rounding, not a px of the picture. */
        private const val NOISE = 0.01f

        fun zoom(scale: Float) = VideoZoom(AspectMode.Zoom, scale)

        /** The choice kept in [prefs]; Fit for a zoom that can't be one (no larger than Fit). */
        fun of(prefs: PlaybackPrefs): VideoZoom = when (val mode = prefs.aspect) {
            AspectMode.Zoom -> prefs.zoomScale.takeIf { it > 1f }?.let(::zoom) ?: VideoZoom()
            else -> VideoZoom(mode)
        }

        /** [scale] between Fit and [frame]'s maximum; Fit for one that is no number. */
        private fun held(scale: Float, frame: PictureFrame) = if (scale.isNaN()) 1f else scale.coerceIn(1f, frame.maxScale)

        private fun labelOf(mode: AspectMode) = when (mode) {
            AspectMode.Fit -> "Fit"
            AspectMode.Fill -> "Fill screen"
            AspectMode.FillBesideCamera -> "Fill beside camera"
            AspectMode.Zoom -> "Zoom"
        }
    }
}
