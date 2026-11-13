package io.skjaere.usenet.app.play

import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * The player as PlayerView's controls see it.
 *
 * Without track selection, so media3's own subtitle button and the settings menu's audio entry
 * stay hidden (the audio and subtitles sheet replaces them, and remembers what it picks). Without
 * playback speed too: media3 placed its settings popup over the gear and
 * past the screen's edge (#200, #211), and nothing else was in that menu.
 *
 * Rewind and fast-forward, the controls' buttons and a remote's keys alike, go through a
 * [SeekBurst], by the player's own steps: taps in quick succession make one seek, after the last.
 * A remote's key held down keeps the burst open until its up ([holdOpen], [settle]).
 * Meanwhile the controls show where it goes, as the position (with what is buffered past it), and
 * hear of each tap as of a jump in the position; [onBurst] is told what to say of the burst. Play
 * and pause have its seek run first, as [flush] does for anything else; another seek (the time
 * bar's) replaces it. Once the player has ended it seeks at once, the burst going on ([leaveEnd]).
 * As the player goes, [hold] keeps its target as the position, seeking nowhere.
 *
 * Previous and Next, the controls' buttons and the media keys alike, are the app's, not media3's
 * playlist (the player has one file): Next plays the next episode ([playNext]), and is offered
 * only while there is one; Previous starts the file again, and within its first seconds plays the
 * episode before ([playPrevious]) where there is one.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class ControlsPlayer(
    player: Player,
    /** Milliseconds from a monotonic clock: the one the player's looper posts by. */
    now: () -> Long = SystemClock::uptimeMillis,
    /** Told after each tap what to say of the burst: how far it goes from where playback was as it started, or that it is at the start or the end. */
    private val onBurst: (SeekBurst.Said) -> Unit = {},
) : ForwardingPlayer(player) {
    private val burst = SeekBurst(now) { player.seekTo(it) }
    private val handler = Handler(player.applicationLooper)
    private val due = Runnable { update() }
    /** The listeners added through this player: the controls, which hear of each tap. */
    private val listeners = mutableSetOf<Player.Listener>()
    /** Set as the player goes: nothing seeks any more. */
    private var held = false

    /** Plays the next episode, once the play knows there is one: then the controls offer Next. Null for a movie or a file. */
    var playNext: (() -> Unit)? = null
        set(value) {
            field = value
            commandsChanged()
        }

    /** Plays the episode before, once the play knows there is one. Null for a movie, a file or a show's first episode: Previous only starts again. */
    var playPrevious: (() -> Unit)? = null
        set(value) {
            field = value
            commandsChanged()
        }

    init {
        // The file ends while a burst waits: its last tap was in the file's last moments.
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) leaveEnd()
            }
        })
    }

    override fun isCommandAvailable(command: Int): Boolean = when (command) {
        Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS, Player.COMMAND_SET_SPEED_AND_PITCH -> false
        Player.COMMAND_SEEK_TO_NEXT -> playNext != null
        Player.COMMAND_SEEK_TO_PREVIOUS -> playPrevious != null || super.isCommandAvailable(command)
        else -> super.isCommandAvailable(command)
    }

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon()
            .removeAll(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS, Player.COMMAND_SET_SPEED_AND_PITCH)
            .removeIf(Player.COMMAND_SEEK_TO_NEXT, playNext == null)
            .addIf(Player.COMMAND_SEEK_TO_NEXT, playNext != null)
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, playPrevious != null)
            .build()

    override fun seekBack() = tap(-seekBackIncrement)

    override fun seekForward() = tap(seekForwardIncrement)

    /**
     * A step of [stepMs] (back when negative) as a tap is, for a remote's key that seeks, held or
     * not ([ScrubRate]), and a double-tap on a phone's picture: [say] is told what to say of the
     * burst. Its seek runs [waitMs] after it, unless more steps come.
     */
    fun step(stepMs: Long, waitMs: Long = SeekBurst.WINDOW_MS, say: (SeekBurst.Said) -> Unit) = tap(stepMs, say, waitMs)

    /**
     * A remote's seeking key is down, its step made: the burst's seek waits for the key's up ([settle]), at most
     * [SeekBurst.HOLD_MS] after its last event, so a lost up still seeks.
     */
    fun holdOpen() {
        if (held) return
        burst.holdOpen()
        schedule()
    }

    /** The key is up: the burst's seek runs [waitMs] from now, unless more steps come first. */
    fun settle(waitMs: Long = SeekBurst.WINDOW_MS) {
        if (held) return
        burst.settle(waitMs)
        schedule()
    }

    private fun tap(stepMs: Long, say: (SeekBurst.Said) -> Unit = onBurst, waitMs: Long = SeekBurst.WINDOW_MS) {
        if (held) return
        burst.tap(stepMs, super.getCurrentPosition(), duration, waitMs)
        leaveEnd()
        burst.said?.let(say)
        schedule()
        moved()
    }

    /** Has [update] run as the burst's seek is due. */
    private fun schedule() {
        handler.removeCallbacks(due)
        burst.dueInMs()?.let { handler.postDelayed(due, it) }
    }

    /** How long until the burst's seek runs, zero once it is due; null while none is under way. */
    internal val dueInMs: Long? get() = burst.dueInMs()

    /** The burst's seek is due (or, posted early, not yet): it runs. Posted on the player's looper; tests run it by their clock. */
    internal fun update() {
        if (held) return
        burst.update()
        val wait = burst.dueInMs()
        if (wait != null) handler.postDelayed(due, wait) else moved()
    }

    /**
     * A burst can't wait on a player that has ended: there media3's controls take Play for a replay
     * from the start and drop fast-forward taps, and the next-episode card counts down. Its seek
     * runs now, taking the player out of its end (and on from there, as it was playing), and the
     * burst goes on.
     */
    private fun leaveEnd() {
        if (!held && super.getPlaybackState() == Player.STATE_ENDED) burst.seekNow()
    }

    /** Something else is done: a burst's seek runs now, before it. */
    fun flush() {
        if (held || burst.targetMs == null) return
        handler.removeCallbacks(due)
        burst.flush()
        moved()
    }

    /** The player goes: nothing seeks any more, and a burst's target stays the position, for the progress saved and the next player. */
    fun hold() {
        held = true
        handler.removeCallbacks(due)
    }

    /** Another seek: the burst's is dropped. */
    private inline fun replacing(seek: () -> Unit) {
        handler.removeCallbacks(due)
        burst.drop()
        seek()
    }

    override fun seekTo(positionMs: Long) = replacing { super.seekTo(positionMs) }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = replacing { super.seekTo(mediaItemIndex, positionMs) }

    override fun seekToDefaultPosition() = replacing { super.seekToDefaultPosition() }

    /**
     * Within the file's first seconds (the player's own limit for going back an item, 3 s), so on
     * a second press right after one started it again, the episode before; else, or without one,
     * the file from the start. By the position the controls show, where a burst of taps goes.
     */
    override fun seekToPrevious() {
        val previous = playPrevious
        if (previous != null && currentPosition <= maxSeekToPreviousPosition) previous() else seekTo(0)
    }

    /** The next episode at once; the position the controls show is where this one is saved as the player goes. */
    override fun seekToNext() {
        val next = playNext
        if (next != null) next() else replacing { super.seekToNext() }
    }

    override fun play() {
        flush()
        super.play()
    }

    override fun pause() {
        flush()
        super.pause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        flush()
        super.setPlayWhenReady(playWhenReady)
    }

    /** Whether a burst is under way, its seek still to run if it goes anywhere ([seekToCome]). */
    val seeking: Boolean get() = burst.targetMs != null

    /**
     * Whether a burst's seek is still to move the player, which at the end takes it out of its end at once ([leaveEnd]).
     * Not for a burst that goes nowhere (fast-forward from the file's last second), one already sent where it goes, or
     * once the player goes.
     */
    val seekToCome: Boolean get() = !held && burst.seekToCome

    override fun getCurrentPosition(): Long = burst.targetMs ?: super.getCurrentPosition()

    override fun getContentPosition(): Long = burst.targetMs ?: super.getContentPosition()

    override fun getBufferedPosition(): Long = bufferedPast(super.getBufferedPosition())

    override fun getContentBufferedPosition(): Long = bufferedPast(super.getContentBufferedPosition())

    /** Buffered past a burst's target: up to [bufferedMs] inside what the player has, else nothing (it keeps nothing behind playback). */
    private fun bufferedPast(bufferedMs: Long): Long =
        burst.targetMs?.let { to -> if (to in super.getCurrentPosition()..bufferedMs) bufferedMs else to } ?: bufferedMs

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        listeners += listener
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        listeners -= listener
    }

    /** The controls hear of a jump in the position: to a burst's target, or back to playback's as one ends where it started. */
    private fun moved() = tell(Player.EVENT_POSITION_DISCONTINUITY)

    /**
     * The controls hear that Previous or Next changed, and enable their buttons again; so does a listener that hears of
     * the commands alone, as the media session's does ([SessionPlayer]).
     */
    private fun commandsChanged() {
        tell(Player.EVENT_AVAILABLE_COMMANDS_CHANGED)
        val now = availableCommands
        listeners.toList().forEach { it.onAvailableCommandsChanged(now) }
    }

    private fun tell(event: Int) {
        val events = Player.Events(FlagSet.Builder().add(event).build())
        listeners.toList().forEach { it.onEvents(this, events) }
    }
}
