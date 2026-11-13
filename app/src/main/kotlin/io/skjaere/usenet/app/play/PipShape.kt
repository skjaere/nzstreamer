package io.skjaere.usenet.app.play

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The picture-in-picture window's shape and where it opens from, as the player tells Android of them (L5), kept apart
 * from the player so it can be tested: the window takes the picture's shape, within what Android allows; it opens from
 * the picture where it shows full screen, and closes back to it.
 */
object PipShape {
    /** A width to height ratio in whole numbers, in their lowest terms, as Android's PictureInPictureParams take it. */
    data class Ratio(val width: Int, val height: Int)

    /**
     * The widest and the narrowest window Android allows (config_pictureInPictureMaxAspectRatio and Min…): it throws for
     * any other, a scope film's 2.40:1 included.
     */
    val WIDEST = Ratio(239, 100)
    val NARROWEST = Ratio(100, 239)

    /**
     * The window's shape for a video [width]×[height] px whose pixels are [pixelRatio] as wide as they are tall (an
     * anamorphic video's are wider), as media3 reports it, a rotated one already turned; the widest or narrowest Android
     * allows past them, where the picture then shows with Fit's bars in the window. Null while the size isn't known: the
     * window keeps the shape it had.
     */
    fun aspect(width: Int, height: Int, pixelRatio: Float): Ratio? {
        if (width <= 0 || height <= 0 || !(pixelRatio > 0f) || pixelRatio.isInfinite()) return null
        val wide = (width * pixelRatio.toDouble()).roundToLong().coerceAtLeast(1)
        val high = height.toLong()
        return when {
            wide * WIDEST.height > high * WIDEST.width -> WIDEST
            wide * NARROWEST.height < high * NARROWEST.width -> NARROWEST
            else -> gcd(wide, high).let { Ratio((wide / it).toInt(), (high / it).toInt()) }
        }
    }

    /**
     * Where the window opens from and closes back to (its sourceRectHint), in the player's window: the picture as Fit lays
     * it out, media3's content frame ([frame]), in every zoom mode, as only the surface in it is scaled. For a picture
     * wider or narrower than the window's [aspect], the frame grown about its middle to the window's shape, with as much
     * of Fit's bars as the window shows; never past the player's [view]. Null while the frame isn't laid out.
     */
    fun hint(frame: Area, view: Area, aspect: Ratio?): Area? {
        if (frame.width <= 0 || frame.height <= 0) return null
        val grown = when {
            aspect == null -> frame
            frame.width.toLong() * aspect.height > frame.height.toLong() * aspect.width -> {
                val high = (frame.width.toDouble() * aspect.height / aspect.width).roundToInt()
                val top = (frame.top + frame.bottom) / 2 - high / 2
                Area(frame.left, top, frame.right, top + high)
            }
            frame.width.toLong() * aspect.height < frame.height.toLong() * aspect.width -> {
                val wide = (frame.height.toDouble() * aspect.width / aspect.height).roundToInt()
                val left = (frame.left + frame.right) / 2 - wide / 2
                Area(left, frame.top, left + wide, frame.bottom)
            }
            else -> frame
        }
        return Area(maxOf(grown.left, view.left), maxOf(grown.top, view.top), minOf(grown.right, view.right), minOf(grown.bottom, view.bottom))
    }

    private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
}
