package io.skjaere.usenet.app.play

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import androidx.annotation.ColorInt
import kotlin.math.roundToInt

/**
 * The spinner before "Moving to Kitchen display…" in the player's moment label (CsPhonePlayer 3), as the app's progress
 * indicator looks elsewhere: an arc in [accent] going round a ring in [track], 16 dp with 2 dp strokes, a third of the way
 * round. It turns once a second while started, on the animator's clock, so it stands still with the phone's "Remove
 * animations" on.
 */
class MovingSpinner(private val density: Float, @param:ColorInt private val accent: Int, @param:ColorInt private val track: Int) : Drawable(), Animatable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_DP * density
        strokeCap = Paint.Cap.ROUND
    }
    private val box = RectF()
    private var alpha = 255

    /** Where the arc starts, in degrees from the top. */
    private var angle = 0f
    private val turning = ValueAnimator.ofFloat(0f, FULL_TURN).apply {
        duration = TURN_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            angle = it.animatedValue as Float
            invalidateSelf()
        }
    }

    override fun draw(canvas: Canvas) {
        val inset = paint.strokeWidth / 2
        box.set(bounds)
        box.inset(inset, inset)
        paint.color = track
        paint.alpha = paint.alpha * alpha / 255
        canvas.drawOval(box, paint)
        paint.color = accent
        paint.alpha = paint.alpha * alpha / 255
        canvas.drawArc(box, angle - QUARTER_TURN, SWEEP, false, paint)
    }

    override fun start() {
        if (!turning.isStarted) turning.start()
    }

    override fun stop() = turning.cancel()

    override fun isRunning() = turning.isRunning

    override fun getIntrinsicWidth() = (SIZE_DP * density).roundToInt()

    override fun getIntrinsicHeight() = (SIZE_DP * density).roundToInt()

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
        const val SIZE_DP = 16f
        const val STROKE_DP = 2f
        const val FULL_TURN = 360f
        const val QUARTER_TURN = 90f

        /** The design's dash, 2.2 of the ring's radii: about a third of the way round. */
        const val SWEEP = 126f
        const val TURN_MS = 1_000L
    }
}
