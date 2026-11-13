package io.skjaere.usenet.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioCapabilitiesReceiver

/**
 * Follows the HDMI audio route for the process's life: a receiver or soundbar switched on after the app started, or a TV
 * that wakes, changes what passes through, and [changed] hears the new route ([DeviceCapabilitiesLoader.audioRouteChanged]
 * takes it). Media3's own receiver watches (the HDMI plug broadcast, the audio device callbacks, the external surround
 * setting) and calls back only when its reading changes; a change in what the ranking doesn't ask about, a channel count
 * alone, is dropped here. One in an encoding that adds no codec (Atmos's E-AC-3 JOC) is heard, for [route], and the
 * loader counts nothing for it.
 *
 * Main thread only: build it, [start] it and [override] it there. Media3's receiver handles its events on the thread it
 * was built on and isn't safe across threads.
 */
@OptIn(UnstableApi::class)
class AudioRouteWatcher(context: Context, private val changed: (Passthrough) -> Unit) {
    private val receiver = AudioCapabilitiesReceiver(context.applicationContext) { report(Passthrough.of(it)) }

    /** The route last heard; null before [start]. */
    private var last: Passthrough? = null

    /**
     * The route as last heard, every change in it at once (no hold for a loss, as the ranking has): what a TV's switch of
     * its refresh rate waits to have back after its HDMI re-sync. Null before [start].
     */
    val route: Passthrough? get() = last

    /** Registers for good, and returns the route as it is now, which the decoder probe read too. */
    fun start(): Passthrough = Passthrough.of(receiver.register()).also { last = it }

    /** For tests: as if the receiver had read [capabilities], as a receiver switched on would make it. */
    fun override(capabilities: AudioCapabilities) = receiver.overrideCapabilities(capabilities)

    /** For tests: unregisters, as the app never does. */
    fun stop() = receiver.unregister()

    private fun report(now: Passthrough) {
        // By the encodings, not the codecs Passthrough is compared by: the switch's wait asks for all of them back.
        if (now.encodings == last?.encodings) return
        last = now
        changed(now)
    }
}
