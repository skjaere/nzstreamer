package io.skjaere.usenet.app.play

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import androidx.annotation.VisibleForTesting
import io.skjaere.usenet.app.container
import io.skjaere.usenet.player.DisplayFacts
import io.skjaere.usenet.player.Passthrough
import io.skjaere.usenet.player.tv.ScreenModes
import io.skjaere.usenet.releases.Hdr

/**
 * What a frame-rate switch asks of an activity's window and the default display ([DisplayModeSwitcher]): the platform's,
 * or a test's, as the emulators list one mode.
 */
interface DisplayPort {
    /** The default display's modes now, with their ids: read at the moment of a request, as a box numbers them again. */
    fun read(): ScreenModes

    /** The mode the window asks the display for (WindowManager.LayoutParams.preferredDisplayModeId); 0 for none. */
    var preferredModeId: Int

    /** Hears each change of the default display with the id of the mode it runs then, until closed. */
    fun listen(changed: (modeId: Int) -> Unit): AutoCloseable

    /** The HDR the display says it shows now (DisplayFacts.hdr); null when it can't say. */
    fun hdr(): Set<Hdr>?

    /** What the TV or receiver takes over HDMI now, as the app's watcher last heard it; null before it started. */
    fun passthrough(): Passthrough?

    /** Now, in milliseconds, on a clock that doesn't jump. */
    fun now(): Long
}

/**
 * The window of [activity] and the default display, as the platform has them. [route]: the HDMI audio route as the app's
 * watcher last heard it.
 */
class WindowDisplayPort(private val activity: Activity, private val route: () -> Passthrough?) : DisplayPort {
    override fun read(): ScreenModes = ScreenModes.read(activity)

    override var preferredModeId: Int
        get() = activity.window.attributes.preferredDisplayModeId
        // Through setAttributes, which the window manager hears; changing the attributes alone it doesn't.
        set(value) {
            activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = value }
        }

    override fun listen(changed: (modeId: Int) -> Unit): AutoCloseable {
        val displays = activity.getSystemService(DisplayManager::class.java) ?: return AutoCloseable {}
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) displays.getDisplay(displayId)?.mode?.modeId?.let(changed)
            }
        }
        displays.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        return AutoCloseable { displays.unregisterDisplayListener(listener) }
    }

    override fun hdr(): Set<Hdr>? = DisplayFacts.read(activity).hdr

    override fun passthrough(): Passthrough? = route()

    override fun now(): Long = SystemClock.elapsedRealtime()
}

/** Where the player and the play sheet's activity get their [DisplayPort]: the platform's, or a test's. */
object DisplayPorts {
    /** Test seam: the port of an activity's window, which a test makes a TV's with the modes it needs. */
    @VisibleForTesting
    @Volatile
    var of: (Activity) -> DisplayPort = { a -> WindowDisplayPort(a) { a.container.audioRoute?.route } }
}
