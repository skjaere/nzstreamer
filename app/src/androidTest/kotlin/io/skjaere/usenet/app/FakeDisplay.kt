package io.skjaere.usenet.app

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.skjaere.usenet.app.play.DisplayPort
import io.skjaere.usenet.player.ModeSize
import io.skjaere.usenet.player.Passthrough
import io.skjaere.usenet.player.tv.ScreenMode
import io.skjaere.usenet.player.tv.ScreenModes
import io.skjaere.usenet.releases.Hdr
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A TV's display for the frame-rate tests, as the emulators list one 60 Hz mode: invented 1080p modes at 23.976, 24, 50,
 * 59.94 and 60 Hz, running 60. Each activity's window votes through its own [port], as the platform has it: the display
 * switches to a mode a window asks for that it doesn't run (after [respondAfterMs], or when the test says, [respond]),
 * and goes back to its own mode as soon as no live window asks for one (a [releases]: a blank between two windows that
 * should have held the mode). Main thread only, as the platform's calls are.
 */
class FakeDisplay(var modes: List<ScreenMode> = TV_MODES, private val default: ScreenMode = SIXTY) {
    private val main = Handler(Looper.getMainLooper())

    /** The mode the display runs. */
    @Volatile
    var active: ScreenMode = default
        private set

    /** When it last changed mode (SystemClock.elapsedRealtime). */
    @Volatile
    var changedAt: Long? = null
        private set

    /** Every id a window set, with its activity's class: "PlayerActivity" to 2, "MainActivity" to 0. */
    val writes = CopyOnWriteArrayList<Pair<String, Int>>()

    /** The modes asked for that the display didn't run then: the switches a TV would make, and blank for. */
    val switches = CopyOnWriteArrayList<Int>()

    /** When the last of [switches] was asked for (SystemClock.elapsedRealtime), as the window set it. */
    @Volatile
    var askedAt: Long? = null
        private set

    /** How often the display went back to its own mode, no window asking for one any more. */
    val releases = AtomicInteger()

    private val listeners = CopyOnWriteArrayList<(Int) -> Unit>()

    /** How many listen to the display now. */
    val listening: Int get() = listeners.size

    /** How long after a switch is asked for the display runs the mode; null: only when the test calls [respond]. */
    @Volatile
    var respondAfterMs: Long? = 300

    @Volatile
    var hdr: Set<Hdr>? = setOf(Hdr.Hdr10)

    @Volatile
    var route: Passthrough? = Passthrough.NONE

    /** What a switch does to the rest as it is asked for, on the main thread: the Shield's HDMI re-sync. */
    @Volatile
    var onSwitch: () -> Unit = {}

    private val votes = LinkedHashMap<Activity, Int>()

    /** [activity]'s window and this display. */
    fun port(activity: Activity): DisplayPort = object : DisplayPort {
        override fun read() = ScreenModes(30, active, modes, null, active.size, outputFromTv = false)

        override var preferredModeId: Int
            get() = votes[activity] ?: 0
            set(value) = vote(activity, value)

        override fun listen(changed: (modeId: Int) -> Unit): AutoCloseable {
            listeners += changed
            return AutoCloseable { listeners -= changed }
        }

        override fun hdr() = hdr

        override fun passthrough() = route

        override fun now() = SystemClock.elapsedRealtime()
    }

    /** The id [activity]'s window asks for now; 0 for none. */
    fun voteOf(activity: Activity): Int = votes[activity] ?: 0

    private fun vote(activity: Activity, id: Int) {
        writes += activity.javaClass.simpleName to id
        votes[activity] = id
        // A finished activity's window votes no more.
        votes.keys.removeAll { it.isDestroyed }
        if (id != 0 && id != active.id) {
            askedAt = SystemClock.elapsedRealtime()
            switches += id
            onSwitch()
            respondAfterMs?.let { ms -> main.postDelayed({ if (id in votes.values) change(mode(id)) }, ms) }
        } else if (votes.values.none { it != 0 } && active != default) {
            releases.incrementAndGet()
            change(default)
        }
    }

    /** The display runs the mode last asked for now. */
    fun respond() = main.post { change(mode(switches.last())) }

    /** The display runs [mode] now, no window asking for it: as a play's switch left it. Main thread. */
    fun run(mode: ScreenMode) = change(mode)

    /** The display reports a change, its mode the same: HDR's, or a TV waking. */
    fun reportSameMode() = main.post { listeners.forEach { it(active.id) } }

    private fun mode(id: Int) = modes.first { it.id == id }

    private fun change(to: ScreenMode) {
        active = to
        changedAt = SystemClock.elapsedRealtime()
        listeners.forEach { it(to.id) }
    }

    companion object {
        private val HD = ModeSize(1920, 1080)
        val FILM = ScreenMode(2, HD, 23.976025f)
        val TWENTY_FOUR = ScreenMode(3, HD, 24f)
        val FIFTY = ScreenMode(4, HD, 50f)
        val NTSC = ScreenMode(5, HD, 59.94006f)
        val SIXTY = ScreenMode(1, HD, 60f)
        val TV_MODES = listOf(SIXTY, FILM, TWENTY_FOUR, FIFTY, NTSC)
    }
}
