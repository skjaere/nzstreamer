package io.skjaere.usenet.app.play

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.withSave
import androidx.core.view.isVisible
import io.skjaere.usenet.app.R
import io.skjaere.usenet.app.ui.AppColors
import kotlin.math.roundToInt

/**
 * The mark on the side a double-tap seeks from (L13, NxPhoneSeekTaps): a soft tint over that side of the picture, its
 * inner edge an arc, brighter where the finger last was; and over it a pill like the moment label's, saying how far the
 * burst goes (SeekBurst.Said's words) with chevrons pointing its way, none where it can't move, and under that where it
 * goes while the controls are hidden (with them up the time bar shows it). The pill is a polite live region: TalkBack
 * reads Said.spoken, with the time where it shows; [show]'s quiet while a remote's key held to seek says it, as the
 * moment label is. PlayerActivity says what it shows ([show], [update]), where its pill goes ([place]) and when it fades
 * ([fade]). It takes no touch, focus or TalkBack focus of its own but the pill's words.
 */
internal class SideMark(context: Context) : FrameLayout(context) {
    private val appColors = AppColors()
    private val density = resources.displayMetrics.density
    private val howFar = text(18f, 600, appColors.text, R.id.side_mark_count).apply {
        // At a large font longer words ("At the end") go onto a second line in the room beside the controls' buttons.
        maxLines = 2
        gravity = Gravity.CENTER_HORIZONTAL
        compoundDrawablePadding = dp(8)
    }
    private val whereTo = text(13f, 400, appColors.textSecondary, R.id.side_mark_time)
    private val pill = LinearLayout(context).apply {
        id = R.id.side_mark
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(appColors.surface.copy(alpha = 0.94f).toArgb())
        }
        setPadding(dp(16), dp(8), dp(16), dp(8))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(howFar)
        addView(whereTo)
    }
    private val chevronsOn = Chevrons(forward = true, density, appColors.accent.toArgb())
    private val chevronsBack = Chevrons(forward = false, density, appColors.accent.toArgb())
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = appColors.text.copy(alpha = 0.10f).toArgb() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Path()
    private val cover = Path()
    private var room: Room? = null

    /**
     * The overlays in the bottom-end slot (the next-episode card, the skip button, the missing-parts prompt, the slow-stream
     * banner). The mark is over them, so its pill shows where it has no room above them ([Room.pillTop]), but its tint is
     * drawn round those showing, as if under them (NxPhoneSeekOverlays).
     */
    var overlays: List<View> = emptyList()

    /** Which side it shows on: the right, going on; else the left, going back. */
    var forward = true
        private set

    /** Where the finger last was on this side, for the brighter ring; null for none. */
    private var tap: PointF? = null

    init {
        // The tint is drawn here, under the pill.
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isVisible = false
        addView(pill, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
    }

    /**
     * The room the pill centres on, in px of the player: from [left] to [right] across, below [top] (the status bar's or
     * the top bar's bottom), on [centreY] unless that would bring it within a margin of an overlay's top, [clearAbove],
     * under it; the tint starts [edge] in from the side.
     */
    data class Room(val left: Int, val right: Int, val top: Int, val centreY: Int, val clearAbove: Int?, val edge: Int) {
        /**
         * Where the top of a pill [height] tall goes: on [centreY], or up [margin] clear of the overlay under it, but no
         * higher than [margin] under [top]. An overlay leaving no room above it (a tall one on a short phone, a large font)
         * has the pill over its top edge rather than off the screen.
         */
        fun pillTop(height: Int, margin: Int): Float {
            val above = clearAbove?.let { it - margin - height / 2f } ?: Float.MAX_VALUE
            return maxOf((top + margin).toFloat(), minOf(centreY.toFloat(), above) - height / 2f)
        }
    }

    /**
     * Shows [said] on the right if [forward], else the left: with chevrons where it moves, the [time] it goes to (null
     * while the controls show it), the ring where [tap] was (null: none). [quiet]: no live region, for a held key. In
     * full again, a fade under way stopped: the burst has new words.
     */
    fun show(said: SeekBurst.Said, forward: Boolean, time: String?, tap: PointF?, quiet: Boolean) {
        update(said, forward, time, tap, quiet)
        animate().cancel()
        alpha = 1f
        isVisible = true
    }

    /**
     * Says what [show] says, but left showing, fading or gone as it is: the controls coming or going change what it says,
     * not how long it shows.
     */
    fun update(said: SeekBurst.Said, forward: Boolean, time: String?, tap: PointF?, quiet: Boolean) {
        if (this.forward != forward || this.tap != tap) invalidate()
        if (this.tap != tap) ring.shader = tap?.let { p -> ringShader(p) }
        this.forward = forward
        this.tap = tap
        howFar.text = said.text
        // By the screen's sides, as the mark is: after the words on the right, before them on the left.
        val chevrons = if (said.forward == null) null else if (forward) chevronsOn else chevronsBack
        howFar.setCompoundDrawablesWithIntrinsicBounds(chevrons.takeUnless { forward }, null, chevrons.takeIf { forward }, null)
        whereTo.text = time
        whereTo.isVisible = time != null
        pill.accessibilityLiveRegion = if (quiet) ACCESSIBILITY_LIVE_REGION_NONE else ACCESSIBILITY_LIVE_REGION_POLITE
        pill.contentDescription = if (quiet) null else time?.let { said.withTarget(it).spoken } ?: said.spoken
    }

    /** Where the pill goes ([Room]); as wide as its words, but no wider than the room leaves. */
    fun place(room: Room) {
        // Drawn again: an overlay come, gone or grown changes where the tint goes round it.
        invalidate()
        if (room == this.room) return
        this.room = room
        val widest = (room.right - room.left - 2 * dp(MARGIN_DP) - pill.paddingLeft - pill.paddingRight).coerceAtLeast(0)
        listOf(howFar, whereTo).forEach { if (it.maxWidth != widest) it.maxWidth = widest }
        movePill()
    }

    /** Fades away, a moment after the last tap. */
    fun fade() {
        animate().alpha(0f).setDuration(FADE_MS).withEndAction(::hide)
    }

    /** Gone at once. */
    fun hide() {
        animate().cancel()
        isVisible = false
        alpha = 1f
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        movePill()
    }

    private fun movePill() {
        val r = room ?: return
        pill.translationX = (r.left + r.right) / 2f - pill.width / 2f
        pill.translationY = r.pillTop(pill.height, dp(MARGIN_DP))
    }

    /**
     * The tint: from the side's edge (inside the cutout's or the bars' inset) in, as far as half its third at the top and
     * bottom and four fifths of it across the middle, along an arc of a circle; the ring, where the finger was, inside it.
     */
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val edge = (room?.edge ?: 0).toFloat()
        val third = w / 3
        val bulge = (RIPPLE_MIDDLE - RIPPLE_ENDS) * third
        // The circle through the arc's ends, at the top and the bottom, and its middle.
        val radius = (bulge * bulge + h * h / 4) / (2 * bulge)
        val cx = if (forward) w - edge - RIPPLE_MIDDLE * third + radius else edge + RIPPLE_MIDDLE * third - radius
        canvas.withSave {
            if (forward) clipRect(0f, 0f, w - edge, h) else clipRect(edge, 0f, w, h)
            val corner = OVERLAY_CORNER_DP * density
            overlays.forEach { o ->
                if (!o.isVisible || o.width == 0) return@forEach
                cover.rewind()
                cover.addRoundRect(o.left.toFloat(), o.top.toFloat(), o.right.toFloat(), o.bottom.toFloat(), corner, corner, Path.Direction.CW)
                clipOutPath(cover)
            }
            drawCircle(cx, h / 2, radius, tint)
            tap?.let { p ->
                arc.rewind()
                arc.addCircle(cx, h / 2, radius, Path.Direction.CW)
                clipPath(arc)
                drawCircle(p.x, p.y, RING_DP * density, ring)
            }
        }
    }

    /** The ring's light, brightest where the finger was. */
    private fun ringShader(at: PointF) = RadialGradient(
        at.x, at.y, RING_DP * density,
        intArrayOf(appColors.text.copy(alpha = 0.24f).toArgb(), appColors.text.copy(alpha = 0.08f).toArgb(), appColors.text.copy(alpha = 0f).toArgb()),
        floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP,
    )

    /** One line of the mark's words, in the app's font, as the player's own text. */
    private fun text(sizeSp: Float, weight: Int, color: Color, id: Int) = TextView(context).apply {
        this.id = id
        typeface = ResourcesCompat.getFont(context, R.font.ibm_plex_sans)
        fontVariationSettings = "'wght' $weight"
        // Figures of one width, so the count doesn't shift as it changes.
        fontFeatureSettings = "tnum"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color.toArgb())
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        // The pill says it for TalkBack.
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun dp(v: Int) = (v * density).roundToInt()

    /**
     * Three solid chevrons pointing the way the burst goes, in the accent, lit more toward the way it goes: 8 by 10 dp
     * each, 2 dp apart, as the approved board draws them (NxPhoneSeekTaps).
     */
    private class Chevrons(private val forward: Boolean, private val density: Float, @param:ColorInt private val accent: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val chevron = Path()
        private var alpha = 255

        override fun draw(canvas: Canvas) {
            val b = bounds
            val unit = b.width() / WIDTH_DP
            repeat(3) { i ->
                val x = b.left + i * (CHEVRON_DP + GAP_DP) * unit
                val (tip, base) = if (forward) x + CHEVRON_DP * unit to x else x to x + CHEVRON_DP * unit
                chevron.rewind()
                chevron.moveTo(base, b.top.toFloat())
                chevron.lineTo(tip, b.top + HEIGHT_DP / 2 * unit)
                chevron.lineTo(base, b.top + HEIGHT_DP * unit)
                chevron.close()
                paint.color = accent
                paint.alpha = ((if (forward) LIT[i] else LIT[2 - i]) * alpha).roundToInt()
                canvas.drawPath(chevron, paint)
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
            const val CHEVRON_DP = 8f
            const val GAP_DP = 2f
            const val WIDTH_DP = 3 * CHEVRON_DP + 2 * GAP_DP
            const val HEIGHT_DP = 10f
            /** From the one furthest back to the one furthest on. */
            val LIT = floatArrayOf(0.35f, 0.65f, 1f)
        }
    }

    private companion object {
        /** How far the pill keeps from the room's sides and from an overlay under it. */
        const val MARGIN_DP = 8
        /** How far in from the side the tint's arc reaches, at its ends and in its middle, in thirds of the width. */
        const val RIPPLE_ENDS = 0.5f
        const val RIPPLE_MIDDLE = 0.8f
        /** The ring's radius, where the finger was. */
        const val RING_DP = 58f
        /** The overlays' corners, the card's and the prompt's, which the tint goes round. */
        const val OVERLAY_CORNER_DP = 16f
        const val FADE_MS = 250L
    }
}
