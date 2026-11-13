package io.skjaere.usenet.app.play

import android.content.Intent
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.MediaSession

/**
 * The player as the media session shows it to what controls the play from outside the app (L14-L16): a Bluetooth headset
 * or earbuds, the Assistant, the picture-in-picture window, other apps' controllers. Over [controls], so their rewind and
 * fast-forward join its burst, Play and Pause run a waiting seek first, and Next and Previous are the app's episodes, as
 * the controls' own are; the controls (PlayerView) keep [controls] itself.
 *
 * It plays, pauses, seeks, goes to the next or the episode before, and stops, which leaves the player as the remote's Stop
 * does ([leave]): Media3's stop would leave it idle with nothing on it. Nothing else: the app prepares the player itself
 * and chooses what it plays, repeat would keep a film from ever ending, and the volume, the audio's handling, the speed and
 * the tracks are the app's own. Play isn't offered under "Playback stopped" ([failed]), where Media3's would prepare the
 * failed stream again behind the panel, nor at the end, where it would replay the file from its start (L11). In the window
 * ([pinned]) there is no Next nor Previous (L13): they hand over to another play, which can't start from there.
 *
 * The session hears of the commands only as onAvailableCommandsChanged (the controls' own events never reach it): told
 * of its own as they change, never of ExoPlayer's, which has all of the above. It says of the play only what the app put
 * on its media item ([SessionMetadata]): ExoPlayer's metadata is the file's own tags with that laid over them. Of an error
 * only that playback stopped, and its code.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class SessionPlayer(controls: ControlsPlayer, private val leave: () -> Unit) : ForwardingPlayer(controls) {
    /** In the picture-in-picture window, or going in. */
    var pinned = false
        set(value) {
            if (field == value) return
            field = value
            commandsChanged()
        }

    /** While "Playback stopped" shows. */
    var failed = false
        set(value) {
            if (field == value) return
            field = value
            commandsChanged()
        }

    /** The listeners added here, the session's, by what was added for each to the controls: theirs without ExoPlayer's commands. */
    private val added = mutableMapOf<Player.Listener, Player.Listener>()

    /** Whether the play had ended as it was last told. */
    private var ended = controls.playbackState == Player.STATE_ENDED

    /** ExoPlayer's error, and the session's for it ([bare]): one for each, so the session reads the same one each time. */
    private var bared: Pair<PlaybackException, PlaybackException>? = null

    init {
        controls.addListener(
            object : Player.Listener {
                override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = commandsChanged()

                override fun onPlaybackStateChanged(playbackState: Int) {
                    val now = playbackState == Player.STATE_ENDED
                    if (now == ended) return
                    ended = now
                    commandsChanged()
                }
            },
        )
    }

    override fun isCommandAvailable(command: Int): Boolean = when (command) {
        in WITHHELD -> false
        Player.COMMAND_PLAY_PAUSE -> !failed && super.getPlaybackState() != Player.STATE_ENDED && super.isCommandAvailable(command)
        in NEXT_PREVIOUS -> !pinned && super.isCommandAvailable(command)
        else -> super.isCommandAvailable(command)
    }

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon()
            .removeAll(*WITHHELD.toIntArray())
            .removeIf(Player.COMMAND_PLAY_PAUSE, !isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
            .apply { if (pinned) removeAll(*NEXT_PREVIOUS.toIntArray()) }
            .build()

    override fun play() {
        if (isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) super.play()
    }

    override fun pause() {
        if (isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) super.pause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) super.setPlayWhenReady(playWhenReady)
    }

    /**
     * The play as the app says it ([SessionMetadata], on its media item), not as ExoPlayer has it: that is the file's own
     * tags with the app's laid over them, and an MP4's artist, album and cover art name the show or the release (M12).
     */
    override fun getMediaMetadata(): MediaMetadata = currentMediaItem?.mediaMetadata ?: MediaMetadata.EMPTY

    override fun getPlayerError(): PlaybackException? = super.getPlayerError()?.let(::bare)

    /**
     * [error] as the session says it: that playback stopped, by its code. ExoPlayer's message names the track's format and
     * label, which an MKV can title with the release's name, and its cause, which other apps' controllers are sent whole,
     * names a release's volume or the provider's host (M12).
     */
    private fun bare(error: PlaybackException): PlaybackException =
        bared?.takeIf { it.first === error }?.second ?: PlaybackException(STOPPED, null, error.errorCode).also { bared = error to it }

    /** The app prepares the player itself, as it makes it. */
    override fun prepare() = Unit

    override fun stop() = leave()

    override fun addListener(listener: Player.Listener) {
        val own = OwnCommands(listener)
        added[listener] = own
        super.addListener(own)
    }

    override fun removeListener(listener: Player.Listener) {
        added.remove(listener)?.let { super.removeListener(it) }
    }

    /** The session hears its commands as they are now, whatever changed them. */
    private fun commandsChanged() {
        val now = availableCommands
        added.keys.toList().forEach { it.onAvailableCommandsChanged(now) }
    }

    /**
     * A media button a session heard ([callback]): play/pause and the headset's button play or pause at once, where
     * Media3 would wait for a second press and take two for Next, the next episode; their ups and repeats do nothing. The
     * other keys are left to Media3, which maps them onto this player's commands. Whether it was taken.
     */
    fun mediaButton(keyCode: Int, down: Boolean, repeatCount: Int): Boolean {
        if (keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && keyCode != KeyEvent.KEYCODE_HEADSETHOOK) return false
        if (down && repeatCount == 0 && isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) Util.handlePlayPauseButtonAction(this)
        return true
    }

    /** The session's callback: its media buttons go to [mediaButton] first. */
    fun callback(): MediaSession.Callback = object : MediaSession.Callback {
        override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean {
            val key = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return false
            return mediaButton(key.keyCode, key.action == KeyEvent.ACTION_DOWN, key.repeatCount)
        }
    }

    /**
     * [listener] as it hears everything the player tells, but ExoPlayer's commands, which [commandsChanged] tells it as
     * this player's, the file's metadata, told as the play's ([getMediaMetadata]), and its error, told bare ([bare]).
     * Every call spelled out: Kotlin's delegation leaves out an interface's Java default methods, and Listener's are all
     * such.
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private inner class OwnCommands(private val listener: Player.Listener) : Player.Listener {
        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = Unit

        override fun onEvents(player: Player, events: Player.Events) = listener.onEvents(player, events)
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = listener.onTimelineChanged(timeline, reason)
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = listener.onMediaItemTransition(mediaItem, reason)
        override fun onTracksChanged(tracks: Tracks) = listener.onTracksChanged(tracks)
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = listener.onMediaMetadataChanged(getMediaMetadata())
        override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) = listener.onPlaylistMetadataChanged(mediaMetadata)
        override fun onIsLoadingChanged(isLoading: Boolean) = listener.onIsLoadingChanged(isLoading)
        override fun onLoadingChanged(isLoading: Boolean) = listener.onLoadingChanged(isLoading)
        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) = listener.onTrackSelectionParametersChanged(parameters)
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) = listener.onPlayerStateChanged(playWhenReady, playbackState)
        override fun onPlaybackStateChanged(playbackState: Int) = listener.onPlaybackStateChanged(playbackState)
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = listener.onPlayWhenReadyChanged(playWhenReady, reason)
        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) = listener.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)
        override fun onIsPlayingChanged(isPlaying: Boolean) = listener.onIsPlayingChanged(isPlaying)
        override fun onRepeatModeChanged(repeatMode: Int) = listener.onRepeatModeChanged(repeatMode)
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = listener.onShuffleModeEnabledChanged(shuffleModeEnabled)
        override fun onPlayerError(error: PlaybackException) = listener.onPlayerError(bare(error))
        override fun onPlayerErrorChanged(error: PlaybackException?) = listener.onPlayerErrorChanged(error?.let(::bare))
        override fun onPositionDiscontinuity(reason: Int) = listener.onPositionDiscontinuity(reason)
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
            listener.onPositionDiscontinuity(oldPosition, newPosition, reason)
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = listener.onPlaybackParametersChanged(playbackParameters)
        override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) = listener.onSeekBackIncrementChanged(seekBackIncrementMs)
        override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) = listener.onSeekForwardIncrementChanged(seekForwardIncrementMs)
        override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) = listener.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)
        override fun onAudioSessionIdChanged(audioSessionId: Int) = listener.onAudioSessionIdChanged(audioSessionId)
        override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) = listener.onAudioAttributesChanged(audioAttributes)
        override fun onVolumeChanged(volume: Float) = listener.onVolumeChanged(volume)
        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = listener.onSkipSilenceEnabledChanged(skipSilenceEnabled)
        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = listener.onDeviceInfoChanged(deviceInfo)
        override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = listener.onDeviceVolumeChanged(volume, muted)
        override fun onVideoSizeChanged(videoSize: VideoSize) = listener.onVideoSizeChanged(videoSize)
        override fun onSurfaceSizeChanged(width: Int, height: Int) = listener.onSurfaceSizeChanged(width, height)
        override fun onRenderedFirstFrame() = listener.onRenderedFirstFrame()
        override fun onCues(cues: List<Cue>) = listener.onCues(cues)
        override fun onCues(cueGroup: CueGroup) = listener.onCues(cueGroup)
        override fun onMetadata(metadata: Metadata) = listener.onMetadata(metadata)
    }

    private companion object {
        /** All the session says of an error: the words the player shows in the window (PipWindowStates). */
        const val STOPPED = "Playback stopped"

        val WITHHELD = setOf(
            Player.COMMAND_PREPARE, Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_SET_AUDIO_ATTRIBUTES, Player.COMMAND_SET_VOLUME, Player.COMMAND_SET_PLAYLIST_METADATA,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
        )

        val NEXT_PREVIOUS = setOf(
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
    }
}
