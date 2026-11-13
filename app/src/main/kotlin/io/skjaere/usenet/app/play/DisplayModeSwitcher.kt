package io.skjaere.usenet.app.play

import io.skjaere.usenet.player.formatRate
import io.skjaere.usenet.player.tv.ScreenMode
import io.skjaere.usenet.releases.Hdr
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * The wait for a TV's switch of its refresh rate before a play (TV-7's L7), through the window and the display [port].
 * The window asks for the mode after the moment's line has been seen ([Timings.beforeRequestMs]), listening to the
 * display first; then the play waits for the display to run that mode (not any change: HDR's changes and a TV waking
 * report too), at most [Timings.changeMs]; 2 s more for the TV to settle; and, as the Shield re-syncs its HDMI, for what
 * passed through before the request to be back and, for a Dolby Vision file on a TV that showed Dolby Vision, for
 * Dolby Vision: Media3 picks the audio route and the Dolby Vision decoder as the player is made, and keeps them. All of
 * it within a cap from the request, 7 s or 10 s for a Dolby Vision file; at the cap, or at OK, the play goes on as it is.
 */
class DisplayModeSwitcher(private val port: DisplayPort, private val timings: Timings = Timings()) {
    /** The waits, the app's unless a test shortens them; [pollMs]: how often the audio route and HDR are read again. */
    data class Timings(
        val beforeRequestMs: Long = 400,
        val changeMs: Long = 5_000,
        val settleMs: Long = 2_000,
        val capMs: Long = 7_000,
        val dolbyVisionCapMs: Long = 10_000,
        val pollMs: Long = 100,
    )

    private enum class Waited { Met, PlayNow, TimedOut }

    /**
     * Asks the display for [mode] and waits as the class says; [dolbyVision]: the file is Dolby Vision; [playNow]
     * completes on OK, which ends whatever wait is left (the mode is still asked for). Returns the log's line on how it
     * went: "Display switched in 1.4 s, settled; HDR back; passthrough back", "Display didn't switch within 5 s; playing
     * at 59.94 Hz", and so on. Cancelled (the player left), it stops listening to the display.
     */
    suspend fun switchTo(mode: ScreenMode, dolbyVision: Boolean, playNow: Deferred<Unit>): String {
        // The line shows before the TV goes dark.
        withTimeoutOrNull(timings.beforeRequestMs) { playNow.await() }
        val route = port.passthrough()?.takeIf { it.encodings.isNotEmpty() }
        val hadDolbyVision = dolbyVision && port.hdr()?.contains(Hdr.DolbyVision) == true
        // The display's reports come on the main thread, as this runs: each is noted as it comes, and only wakes the wait,
        // so that one coming as a poll times out isn't lost.
        var changedAt: Long? = null
        val woken = Channel<Unit>(Channel.CONFLATED)
        val listening = port.listen { id ->
            if (id == mode.id && changedAt == null) changedAt = port.now()
            woken.trySend(Unit)
        }
        try {
            port.preferredModeId = mode.id
            val asked = port.now()
            val cap = asked + if (dolbyVision) timings.dolbyVisionCapMs else timings.capMs
            suspend fun until(deadline: Long, met: () -> Boolean): Waited {
                while (true) {
                    if (met()) return Waited.Met
                    if (playNow.isCompleted) return Waited.PlayNow
                    val left = deadline - port.now()
                    if (left <= 0) return Waited.TimedOut
                    withTimeoutOrNull(minOf(left, timings.pollMs)) {
                        select {
                            playNow.onAwait { }
                            woken.onReceive { }
                        }
                    }
                }
            }
            when (until(minOf(asked + timings.changeMs, cap)) { changedAt != null }) {
                Waited.TimedOut -> {
                    val rate = port.read().active?.refreshRate?.let { "${formatRate(it.toDouble(), Locale.ROOT)} Hz" } ?: "its rate"
                    return "Display didn't switch within ${seconds(timings.changeMs)}; playing at $rate"
                }
                Waited.PlayNow -> return "Display not switched after ${seconds(port.now() - asked)}; $PLAY_NOW"
                Waited.Met -> Unit
            }
            val switched = "Display switched in ${seconds(changedAt!! - asked)}"
            val settleEnd = changedAt!! + timings.settleMs
            if (until(minOf(settleEnd, cap)) { false } == Waited.PlayNow) return "$switched; $PLAY_NOW"
            fun routeBack() = route == null || port.passthrough()?.encodings?.containsAll(route.encodings) == true
            fun hdrBack() = !hadDolbyVision || port.hdr()?.contains(Hdr.DolbyVision) == true
            val back = until(cap) { routeBack() && hdrBack() }
            val parts = listOfNotNull(
                if (port.now() >= settleEnd) "$switched, settled" else switched,
                if (!hadDolbyVision) null else if (hdrBack()) "HDR back" else "Dolby Vision not back within ${seconds(cap - asked)}",
                if (route == null) null else if (routeBack()) "passthrough back" else "passthrough not back within ${seconds(cap - asked)}",
            )
            return parts.joinToString("; ") + if (back == Waited.PlayNow) "; $PLAY_NOW" else ""
        } finally {
            listening.close()
        }
    }

    private companion object {
        const val PLAY_NOW = "OK pressed: playing at once"

        /** "1.4 s", "2 s": a tenth of a second is as fine as a TV's switch is measured. */
        fun seconds(ms: Long): String =
            DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.ROOT)).apply { roundingMode = RoundingMode.HALF_UP }.format(ms / 1000.0) + " s"
    }
}
