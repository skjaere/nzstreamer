package io.skjaere.usenet.app.play

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import androidx.core.graphics.withScale
import kotlin.math.roundToInt

/**
 * The speed cue at the start of the moment label while a remote's key held down seeks (W8): three chevrons pointing the
 * way the hold goes, on if [forward], else back; [lit] of them in [accent] and the rest in [dim], two while the speed
 * doubles and three at the top ([ScrubRate.Speed]), lit from the trailing end. Drawn as the approved board has it going
 * on (NxTvSeek3Fast): 22 by 12 dp, 2.2 dp strokes with round ends, 7 dp apart; going back, the same mirrored.
 */
class SpeedCue(
    val lit: Int,
    val forward: Boolean,
    private val density: Float,
    @param:ColorInt private val accent: Int,
    @param:ColorInt private val dim: Int,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_DP * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val chevron = Path()
    private var alpha = 255

    override fun draw(canvas: Canvas) {
        val b = bounds
        val unit = b.width() / WIDTH_DP
        canvas.withScale(if (forward) 1f else -1f, 1f, b.exactCenterX(), b.exactCenterY()) {
            repeat(3) { i ->
                val x = b.left + (1 + 7 * i) * unit
                chevron.rewind()
                chevron.moveTo(x, b.top + 1.5f * unit)
                chevron.lineTo(x + 4.5f * unit, b.top + 6f * unit)
                chevron.lineTo(x, b.top + 10.5f * unit)
                paint.color = if (i < lit) accent else dim
                paint.alpha = paint.alpha * alpha / 255
                drawPath(chevron, paint)
            }
        }
    }

    override fun getIntrinsicWidth() = (WIDTH_DP * density).roundToInt()

    override fun getIntrinsicHeight() = (HEIGHT_DP * density).roundToInt()

    override fun setAlpha(alpha: Int) {
        this.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        const val WIDTH_DP = 22f
        const val HEIGHT_DP = 12f
        const val STROKE_DP = 2.2f
    }
}
