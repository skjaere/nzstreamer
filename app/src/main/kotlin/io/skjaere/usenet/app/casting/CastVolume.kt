package io.skjaere.usenet.app.casting

import androidx.annotation.MainThread
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** A Cast device's volume as its session says it (L118): its [level] on Google's scale, 0 to [CastVolume.MAX], and whether it is [muted]. */
data class CastVolumeNow(val level: Int, val muted: Boolean)

/**
 * A Cast device's volume as its slider shows and moves it (L118, M1-A; Sender volume A-E; CsPhoneSheetConnected,
 * CsPhoneRemoteC2): the session's [player]'s device volume, Google's 21 steps shown as 0 to 100 % in 5 % steps ([percent];
 * muted reads 0 %), following the device as it changes (its own remote, another phone, the keys) while a slider listens,
 * and read from the session's own level as one starts to, as the player's may still hold a level the device refused.
 * Only the user's drags go out, each step the finger reaches, once ([drag]); never a level of its own, not even on
 * connect. Media3 takes a level it sent at once and corrects it only when the device says otherwise, which a device that
 * keeps its volume to itself may never do: so [REREAD_MS] after a drag ends, the slider takes the session's own level
 * ([session]), never the player's ([reread]). Found back where it was before the drag, though the drag asked for another
 * level, the device refused it ([refused]): the slider snaps back and stays usable, and its line says so for the rest
 * of the session, whatever the route said of its volume. One for each session, made as it starts, so the list's block
 * and the remote share it. On the main thread.
 */
class CastVolume(private val player: Player, private val session: () -> CastVolumeNow?) {
    private val _percent = MutableStateFlow(level())

    /** What the slider shows: the device's volume as 0 to 100 %, or the finger's step while it drags. */
    val percent: StateFlow<Int> = _percent.asStateFlow()

    private val _refused = MutableStateFlow(false)

    /** Whether the device refused a change in this session: the line under the slider, from then on. */
    val refused: StateFlow<Boolean> = _refused.asStateFlow()

    /** A finger on the slider: the device's own changes wait until it lifts. */
    private var dragging = false

    /** The level shown as the drags before the next re-read began; null with none waiting for it. */
    private var before: Int? = null

    /** The last level those drags sent; null if they sent none. */
    private var asked: Int? = null

    /**
     * Whether a drag ended and the session's level is to be read again ([reread]): until it is, a slider shown again waits
     * its [REREAD_MS] for it, as one closed meanwhile never read it.
     */
    val waiting: Boolean get() = !dragging && before != null

    private val _ends = MutableStateFlow(0)

    /**
     * Each drag's end, counted ([released]): every slider showing waits its [REREAD_MS] from the last for the re-read,
     * whichever was dragged, so one closed within the second (the list's, over the remote) leaves it to another.
     */
    val ends: StateFlow<Int> = _ends.asStateFlow()

    /** Whether the slider takes drags (M1-A): the session's player can set the device's volume, whatever its route says. */
    val settable: Boolean get() = player.isCommandAvailable(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)

    /** Follows the device's volume from now, from what the player has, until closed: while a slider shows. */
    @MainThread
    fun listen(): AutoCloseable {
        val heard = object : Player.Listener {
            override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) {
                if (!dragging) _percent.value = percentOf(volume, muted)
            }
        }
        player.addListener(heard)
        // A drag still waiting for its re-read keeps its step; else what the device has now.
        if (!dragging && before == null) _percent.value = level()
        return AutoCloseable { player.removeListener(heard) }
    }

    /** The device's level now, as a percentage: the session's own, else (with none to read) the player's. */
    private fun level(): Int = session()?.let { percentOf(it.level, it.muted) } ?: percentOf(player.deviceVolume, player.isDeviceMuted)

    /** The finger at [percent] of the slider: the step it is on goes to the device, unless the slider shows it already. */
    @MainThread
    fun drag(percent: Float) {
        val shown = _percent.value / STEP
        if (!dragging) {
            dragging = true
            if (before == null) before = shown
        }
        val level = (percent / STEP).roundToInt().coerceIn(0, MAX)
        if (level == shown) return
        player.setDeviceVolume(level, 0)
        asked = level
        _percent.value = level * STEP
    }

    /**
     * A key's step on the slider ([by] steps up, or down): the step that many from what it shows, sent as a drag to it is
     * ([drag]); the key let go ends it as a finger does ([released]).
     */
    @MainThread
    fun step(by: Int) = drag(((_percent.value / STEP + by).coerceIn(0, MAX) * STEP).toFloat())

    /** The finger lifted: the session's level is read again [REREAD_MS] after ([reread]), by any slider showing ([ends]). */
    @MainThread
    fun released() {
        dragging = false
        _ends.value++
    }

    /**
     * [REREAD_MS] after the last drag ended: the slider shows the session's own level, and a device found where it was
     * before the drags, which asked for another level, refused it. Nothing while a finger is down again, after no drag,
     * or with no session.
     */
    @MainThread
    fun reread() {
        if (dragging) return
        val from = before ?: return
        val sent = asked
        before = null
        asked = null
        val now = session() ?: return
        if (sent != null && sent != from && now.level == from) _refused.value = true
        _percent.value = percentOf(now.level, now.muted)
    }

    companion object {
        /** Google's scale, as Media3's Cast player reads it: 0 to 20. */
        const val MAX = 20

        /** One step of it on the slider: 5 %. */
        const val STEP = 5

        /** How long after a drag the session's own level is read again. */
        const val REREAD_MS = 1_000L

        private fun percentOf(level: Int, muted: Boolean) = if (muted) 0 else level.coerceIn(0, MAX) * STEP
    }
}

/** The slider's name to TalkBack (CsPhoneTalkBackC2 7): "Volume on Kitchen display". */
fun volumeOn(name: String) = "Volume on $name"

/** Its level to TalkBack: "45 percent". */
fun volumePercent(percent: Int) = "$percent percent"

/** Under the slider once the device refused a change (M1-A; CsPhoneSheetConnected 4's words). */
fun ownVolume(name: String) = "$name sets its own volume: use its own remote."
