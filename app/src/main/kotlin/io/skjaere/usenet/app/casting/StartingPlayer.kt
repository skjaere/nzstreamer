package io.skjaere.usenet.app.casting

import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import io.skjaere.usenet.app.play.SessionMetadata

/**
 * The cast session's player over the device's ([remote]) while a cast play starts (L58, L63). The device's player has an
 * empty timeline, idle, until the device reports the item loaded, and Media3 posts no notification for that, so it never
 * takes the service into the foreground, which Android wants within seconds of the tap however long the phone's search
 * takes. So from the tap ([starting]) until the device's current item is the one this play sent ([sent]), the session
 * has a placeholder in its place: one item with no URI, said as [CastServiceRules.placeholder] says the play, buffering
 * and meant to play, with nothing to press (only the session's own Stop casting). Another sender's item on a device taken
 * over (W32) doesn't end it. Then the device's own state, as it is, until the next play starts, but for a moment when the
 * device's player has nothing (a session resumed, its first status with no media, L91): the session keeps the play's item
 * as it last was there, waiting, for with nothing in it Media3 takes the notification down and lets the foreground go,
 * which Android won't let the service take back from the background (L58). Where it was is the play as the phone follows
 * it ([followed]), for the device's player says nothing of its steady progress. On the main thread, the device player's.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class StartingPlayer(private val remote: Player) : ForwardingSimpleBasePlayer(remote) {
    /** The play starting, as the session says it; null once the device has it, or with none. */
    private var shown: MediaItem? = null

    /** What the play starting sent the device: its item's id, the content id the device reports. */
    private var sent: String? = null

    /** The play's item on the device as last seen there, and where it was: what the session keeps while the device's player has none. */
    private var held: Held? = null

    private class Held(val item: MediaItem, val positionMs: Long, val durationMs: Long, val playWhenReady: Boolean)

    init {
        remote.addListener(
            object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = handOver()
            },
        )
    }

    /** Whether the placeholder shows: a play starting, the device not playing it yet. */
    val starting: Boolean get() = shown != null

    /** Where the play's item is kept, as last seen there or followed since; null with none. A device test's to wait on. */
    @get:VisibleForTesting
    val keptAt: Long? get() = held?.positionMs

    /**
     * A cast play to [device] (its CastTo.name) starts, said as [said] ([SessionMetadata.starting]): the placeholder, until
     * the device has what it sends.
     */
    fun starting(said: SessionMetadata, device: String) {
        shown = CastServiceRules.placeholder(said, device, Build.VERSION.SDK_INT)
        sent = null
        held = null
        invalidateState()
    }

    /** The play starting sent the device its item, as [mediaId]: the device's player takes over once that item is its current one. */
    fun sent(mediaId: String) {
        sent = mediaId
        handOver()
    }

    /** The play starting was given up: the device's own state again, the play loaded before it ([there], its id) going on there (L84). */
    fun gaveUp(there: String?) {
        if (shown == null) return
        shown = null
        sent = there
        held = null
        invalidateState()
    }

    /**
     * The play on the device as the phone follows it ([feed], polled each second): where the item the session keeps is
     * moves on with it, as the device's player sends no event for its steady progress; while the session shows it, waiting,
     * it says the new place.
     */
    fun followed(feed: CastFeed?) {
        val last = held ?: return
        if (feed == null || feed.load != last.item.mediaId || feed.positionMs == last.positionMs) return
        held = Held(last.item, feed.positionMs, last.durationMs, last.playWhenReady)
        if (shown == null && remote.currentTimeline.isEmpty) invalidateState()
    }

    private fun handOver() {
        if (shown != null && CastServiceRules.handsOver(sent, remote.currentMediaItem?.mediaId)) {
            shown = null
            invalidateState()
        }
    }

    override fun getState(): State {
        val state = super.getState()
        shown?.let { return waiting(state, MediaItemData.Builder(PLACEHOLDER).setMediaItem(it).setMediaMetadata(it.mediaMetadata).build(), 0, playWhenReady = true) }
        val ours = sent ?: return state
        if (!remote.currentTimeline.isEmpty) {
            remote.currentMediaItem?.takeIf { it.mediaId == ours }?.let { held = Held(it, remote.currentPosition, remote.duration, remote.playWhenReady) }
            return state
        }
        // The device's player has nothing for a moment: the play's item kept as it last was there, waiting for the device.
        val last = held ?: return state
        val item = MediaItemData.Builder(last.item.mediaId).setMediaItem(last.item).setMediaMetadata(last.item.mediaMetadata)
            .apply { if (last.durationMs != C.TIME_UNSET) setDurationUs(Util.msToUs(last.durationMs)) }
            .build()
        return waiting(state, item, last.positionMs, last.playWhenReady)
    }

    /** [state] with [item] alone, at [positionMs], buffering, with nothing to press but the device's volume. */
    private fun waiting(state: State, item: MediaItemData, positionMs: Long, playWhenReady: Boolean): State {
        // The device's volume still goes to it: the keys work while the play starts (L87).
        val commands = Player.Commands.Builder().addAll(Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA)
        VOLUME.filter { remote.isCommandAvailable(it) }.forEach(commands::add)
        return state.buildUpon()
            .setAvailableCommands(commands.build())
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(positionMs)
            .setPlaybackState(Player.STATE_BUFFERING)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlayerError(null)
            .build()
    }

    private companion object {
        /** The placeholder's own id in the session's timeline, never one of the device's. */
        const val PLACEHOLDER = "io.skjaere.usenet.cast.starting"

        /** The device's volume, as the device's player offers it: its older commands too, which the session may still ask for. */
        @Suppress("DEPRECATION")
        val VOLUME = listOf(
            Player.COMMAND_GET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_ADJUST_DEVICE_VOLUME, Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        )
    }
}
