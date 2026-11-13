package io.skjaere.usenet.player.tv

import io.skjaere.usenet.player.MatchContentFrameRate
import io.skjaere.usenet.player.formatRate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Why a play leaves the display's mode as it is ([FrameRatePlan.Leave]). */
enum class LeaveReason { Off, NotTv, SystemNever, RateUnknown, Short, ModeUnknown, OutputNotMode, Seamless, NoFittingMode }

/** What a play's window asks of the display before its player is made ([FrameRatePlanner.plan]). */
sealed interface FrameRatePlan {
    /** No vote: Media3's own seamless matching, as before the setting. [why] says so in the log. */
    data class Leave(val reason: LeaveReason, val why: String) : FrameRatePlan

    /** The window asks for [mode]; [wait]: the display doesn't run it now, so the play waits for the switch. */
    data class Hold(val mode: ScreenMode, val wait: Boolean) : FrameRatePlan
}

/**
 * Whether a play on a TV asks the display for a mode that shows the video's frame rate without pulldown, and which: the
 * opt-in "Match the TV to the video's frame rate". Only the refresh rate changes, never the resolution (by design,
 * and Auto quality and the prefetched search's equal display facts rely on it), so the choice is among
 * the modes of the size the display runs. Pure, for the JVM tests; the window and the wait are the player's.
 */
object FrameRatePlanner {
    /**
     * The plan for a play of [fps] frames a second lasting [durationMs] (either null when the track probe couldn't say)
     * on [screen], with the setting [on], on a [tv] device. In order: off or not a TV, Android's own setting Never (API 31
     * and later), a rate not known or not usual, a clip under 5 minutes, a mode not known or one the TV's own output
     * contradicts (#65) all leave the display; a mode that already fits is held without a wait, and a seamless
     * alternative rate of it (API 31 and later) is Media3's call; else the mode at its size that fits best is switched to.
     */
    fun plan(on: Boolean, tv: Boolean, fps: Double?, durationMs: Long?, screen: ScreenModes): FrameRatePlan {
        if (!on) return leave(LeaveReason.Off, "frame rate matching is off")
        if (!tv) return leave(LeaveReason.NotTv, "not a TV")
        if (screen.sdk >= 31 && screen.matchContentFrameRate == MatchContentFrameRate.Never) {
            return leave(LeaveReason.SystemNever, "Android's Match content frame rate is set to Never")
        }
        val rate = known(fps) ?: return leave(LeaveReason.RateUnknown, "frame rate unknown before playback")
        if (USUAL_RATES.none { near(rate, it) }) return leave(LeaveReason.RateUnknown, "${text(rate)} fps isn't a usual frame rate")
        // Google's advice, and VLC's: a clip isn't worth a blank screen. A length not known counts as long.
        if (durationMs != null && durationMs < MIN_DURATION_MS) return leave(LeaveReason.Short, "under 5 minutes long")
        val active = screen.active ?: return leave(LeaveReason.ModeUnknown, "the display's mode is unknown")
        // #65: a TV that says it outputs another size than the mode listed as running; asking for one of the listed
        // modes might drop the output to it.
        if (screen.outputFromTv && screen.output != active.size) {
            return leave(LeaveReason.OutputNotMode, "the TV outputs ${screen.output}, the display's mode is ${active.size}")
        }
        // Held, not left: with a vote for the mode it has, nothing else takes it away mid-film.
        if (fits(rate, active.refreshRate)) return FrameRatePlan.Hold(active, wait = false)
        val seamless = if (screen.sdk >= 31) active.alternativeRates.firstOrNull { fits(rate, it) } else null
        if (seamless != null) return leave(LeaveReason.Seamless, "${text(seamless.toDouble())} Hz is a seamless switch, left to the player")
        val fitting = screen.modes.filter { it.size == active.size && fits(rate, it.refreshRate) }
        fun lowest(modes: List<ScreenMode>) = modes.minByOrNull { it.refreshRate }
        // A film at its own rate, else the next multiple. Other rates at 48 Hz or more where there is one (25 at 50, as
        // common TV players do), where the controls and subtitles move smoothly too, and some TVs flicker at 25.
        val best = if (rate < FILM_LIMIT) lowest(fitting) else lowest(fitting.filter { it.refreshRate >= 48f }) ?: lowest(fitting)
        return best?.let { FrameRatePlan.Hold(it, wait = true) } ?: leave(LeaveReason.NoFittingMode, "no ${text(rate)} Hz mode at ${active.size}")
    }

    /**
     * Whether [hz] shows [fps] without pulldown: a whole multiple of it, within 0.05 %. 23.976 and 24 (and 29.97 and 30,
     * 59.94 and 60) are exactly 0.1 % apart, and the Shield lists 23.976025 Hz for 24000/1001.
     */
    fun fits(fps: Double, hz: Float): Boolean {
        if (known(fps) == null || !(hz > 0f)) return false
        val multiple = (hz / fps).roundToInt()
        return multiple >= 1 && near(hz.toDouble(), multiple * fps)
    }

    /**
     * The log's line for [plan], device facts only: "Frame rate: 23.976 fps; display 3840x2160 at 59.94 Hz: switching
     * to 23.976 Hz (mode 23)", "…: already fits" or "…: left: no 23.976 Hz mode at 3840x2160".
     */
    fun line(fps: Double?, screen: ScreenModes, plan: FrameRatePlan): String {
        val rate = known(fps)?.let { "${text(it)} fps" } ?: "unknown"
        val display = screen.active?.let { "display ${it.size} at ${text(it.refreshRate.toDouble())} Hz" } ?: "display unknown"
        val done = when (plan) {
            is FrameRatePlan.Hold -> if (plan.wait) "switching to ${text(plan.mode.refreshRate.toDouble())} Hz (mode ${plan.mode.id})" else "already fits"
            is FrameRatePlan.Leave -> "left: ${plan.why}"
        }
        return "Frame rate: $rate; $display: $done"
    }

    private fun leave(reason: LeaveReason, why: String) = FrameRatePlan.Leave(reason, why)

    private fun known(fps: Double?) = fps?.takeIf { it.isFinite() && it > 0 }

    private fun near(a: Double, b: Double) = abs(a / b - 1) <= TOLERANCE

    private fun text(rate: Double) = formatRate(rate, Locale.ROOT)

    private const val TOLERANCE = 0.0005
    private const val MIN_DURATION_MS = 5 * 60_000L

    /** Below it a rate is a film's (23.976, 24), shown at its own rate or a multiple; 25 and up go to 48 Hz or more. */
    private const val FILM_LIMIT = 24.5

    /** The frame rates films and video come in; any other is a variable rate's average or a broken header's. */
    private val USUAL_RATES = listOf(24_000.0 / 1001, 24.0, 25.0, 30_000.0 / 1001, 30.0, 48_000.0 / 1001, 48.0, 50.0, 60_000.0 / 1001, 60.0)
}
