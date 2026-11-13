package io.skjaere.usenet.app.play

import io.skjaere.usenet.player.ModeSize
import io.skjaere.usenet.player.formatRate
import io.skjaere.usenet.player.tv.ScreenMode
import io.skjaere.usenet.player.tv.ScreenModes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/**
 * The TV's mode kept across a hand-over between two plays (TV-7's W3): auto-advance, Previous and Next, and Try another
 * close the player, and the play sheet finds the next play meanwhile. A window's mode counts only while the window
 * shows, so without this the TV would go back to its own mode for the sheet and switch again for the next episode, a
 * second blank, with the ranking and the track probe reading a display in the middle of its HDMI re-sync. The player
 * hands its mode over as it closes; the play sheet's activity asks for it while it shows, and the next player as it is
 * made, which then keeps it (the same rate), switches once from it (another), or gives it up (a rate not known). Kept by
 * its size and rate, never its id: a box numbers its modes again as their list changes. Released when the next play ends
 * without starting, when the play sheet's activity stops, when the display is found to have left the mode ([keptIn]), or
 * after a minute.
 */
class DisplayModeHold(private val scope: CoroutineScope, private val log: (String) -> Unit, private val capMs: Long = CAP_MS) {
    /** A display mode by its physical [size] and [refreshRate]. */
    data class Held(val size: ModeSize, val refreshRate: Float) {
        fun matches(mode: ScreenMode) = mode.size == size && abs(mode.refreshRate - refreshRate) < SAME_RATE_HZ
    }

    private val lock = Any()
    private val _held = MutableStateFlow<Held?>(null)

    /** The mode held for the next play; null when none is. */
    val held: StateFlow<Held?> = _held.asStateFlow()

    /** The minute after the last hand-over, after which the hold goes by itself. */
    private var expiry: Job? = null

    /** The player closing for the next play, its window asking for [mode]: held for that play. */
    fun handOver(mode: ScreenMode) {
        synchronized(lock) {
            _held.value = Held(mode.size, mode.refreshRate)
            expiry?.cancel()
            expiry = scope.launch {
                delay(capMs)
                synchronized(lock) {
                    // A hand-over or a clear since has this minute no longer counting.
                    if (expiry === coroutineContext[Job]) release("no play started within ${capMs / 1000} s")
                }
            }
        }
        log("Display mode held for the next play: ${mode.size} at ${formatRate(mode.refreshRate.toDouble(), Locale.ROOT)} Hz")
    }

    /** Lets the mode go, saying [why] in the log; nothing when none is held. */
    fun clear(why: String) {
        synchronized(lock) { release(why) }
    }

    /** Under the lock. */
    private fun release(why: String) {
        expiry?.cancel()
        expiry = null
        if (_held.value == null) return
        _held.value = null
        log("Display mode hold released: $why")
    }

    /** The mode [screen] lists for the hold now, found by its size and rate; null without a hold, or where none is listed. */
    fun modeIn(screen: ScreenModes): ScreenMode? {
        val h = held.value ?: return null
        return screen.modes.firstOrNull(h::matches)
    }

    /**
     * The mode held, as [screen] lists it now, while the display still runs it: what the play sheet's window and the next
     * player ask for. A hold whose mode the display has left goes: the app went to the background as the mode was handed
     * over (Home as the next episode starts, #142), and the TV went back to its own mode meanwhile. Asked for again, the
     * TV would switch before the next player's moment shows, with nothing waiting for it to settle; that player's own
     * plan switches it instead. Null without a hold.
     */
    fun keptIn(screen: ScreenModes): ScreenMode? {
        val h = held.value ?: return null
        if (screen.active?.let(h::matches) == true) return modeIn(screen)
        // Unless a hand-over came since.
        synchronized(lock) { if (_held.value == h) release("the display runs another mode") }
        return null
    }

    companion object {
        /** How long a hold waits for the next play to start. */
        const val CAP_MS = 60_000L

        /** A mode read again keeps its rate to the last digit; 59.94 and 60 Hz are 0.06 apart. */
        private const val SAME_RATE_HZ = 0.01f
    }
}
