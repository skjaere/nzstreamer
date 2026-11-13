package io.skjaere.usenet.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * A player's "becoming noisy", in place of Media3's own handling (off, [PlayerFactory.create]): the
 * ACTION_AUDIO_BECOMING_NOISY receiver, the audio outputs listed as it arrives ([outputs]), and those an
 * AudioDeviceCallback heard go away, judged for a [tv] or a phone by [NoisyPolicy]; [heard] is told the decision. One
 * for each player: [start] once it is made, [stop] as it goes. Main thread only: the receiver and the callback run on it,
 * and nothing is heard after [stop].
 */
class BecomingNoisy(
    private val context: Context,
    private val tv: Boolean,
    /** The outputs' AudioDeviceInfo types now; a test can make them a TV's with headphones. */
    private val outputs: () -> List<Int> = { listOutputs(context) },
    /** Milliseconds from a monotonic clock, for how long ago an output went away. */
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val heard: (NoisyPolicy.Decision) -> Unit,
) {
    private val log = LoggerFactory.getLogger(BecomingNoisy::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)

    /** The outputs that went away in the last [NoisyPolicy.RECENT_MS], as the callback heard them. */
    private val gone = ArrayDeque<NoisyPolicy.Gone>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) noisy()
        }
    }

    // Its first onAudioDevicesAdded lists every device as it registers: only removals count.
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = removed(removedDevices.filter { it.isSink }.map { it.type })
    }

    /** Whether the receiver and the callback are registered: from [start] to [stop]. */
    var started = false
        private set

    /** Registers the receiver and the callback, on the main thread. */
    fun start() {
        if (started) return
        // A system broadcast: no exported flag needed.
        context.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        audio.registerAudioDeviceCallback(devices, Handler(Looper.getMainLooper()))
        started = true
        registeredNow.incrementAndGet()
    }

    /** Unregisters both; a broadcast or a removal already on its way is dropped. */
    fun stop() {
        if (!started) return
        started = false
        context.unregisterReceiver(receiver)
        audio.unregisterAudioDeviceCallback(devices)
        gone.clear()
        registeredNow.decrementAndGet()
    }

    /** As the receiver hears the broadcast; a test calls it too, as only the system may send it. */
    fun noisy() {
        if (!started) return
        val listed = outputs()
        val at = now()
        val recent = gone.filter { at - it.atMs <= NoisyPolicy.RECENT_MS }
        log.debug("Audio output going away; outputs listed: {}; gone in the last 2 s: {}", NoisyPolicy.kinds(listed), NoisyPolicy.kinds(recent.map { it.type }))
        heard(NoisyPolicy.decide(tv, listed, recent, at))
    }

    /** As the callback hears outputs of [types] go away; a test calls it too, as an emulator's outputs stay. */
    fun removed(types: List<Int>) {
        if (!started || types.isEmpty()) return
        val at = now()
        gone.removeAll { at - it.atMs > NoisyPolicy.RECENT_MS }
        types.forEach { gone += NoisyPolicy.Gone(it, at) }
    }

    companion object {
        private val registeredNow = AtomicInteger()

        /** How many are registered in this process now: for a test, that none is left behind as a player goes. */
        val registered: Int get() = registeredNow.get()

        /** The outputs' AudioDeviceInfo types, as AudioManager lists them now. */
        fun listOutputs(context: Context): List<Int> = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
    }
}
