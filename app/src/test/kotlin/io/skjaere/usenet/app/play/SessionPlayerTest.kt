package io.skjaere.usenet.app.play

import android.view.KeyEvent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import io.skjaere.usenet.data.WatchKey
import org.junit.jupiter.api.Test
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SessionPlayerTest {
    /** What the player was asked to do, in order. */
    private val calls = mutableListOf<String>()
    private var state = Player.STATE_READY
    private var playWhenReady = true
    /** The error the player stopped on, as ExoPlayer has it; null while none. */
    private var failedWith: PlaybackException? = null
    /** The listeners added to the player itself. */
    private val heardByPlayer = mutableListOf<Player.Listener>()

    /** The play as the app made its media item: by its watch key, said as the top bar shows it ([SessionMetadata]). */
    private val said = SessionMetadata(WatchKey.episode(900103, 1, 2), "S01E02 · High Water", "Lowland Signal", MediaMetadata.MEDIA_TYPE_TV_SHOW)
    private val item = MediaItem.Builder().setMediaId(said.mediaId).setMediaMetadata(said.metadata()).build()

    /** ExoPlayer's metadata for a tagged MP4: the file's own artist, album and cover art, with the play's laid over them. */
    private val tagged = said.metadata().buildUpon()
        .setArtist("Lowland Signal").setAlbumTitle("Lowland Signal, Season 1").setAlbumArtist("Low Water Group").setComposer("Tin Sound")
        .setRecordingYear(2026).setArtworkData(byteArrayOf(1, 2, 3), MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()

    /** A player that can do everything, as ExoPlayer can with a file prepared: a minute into a ten-minute file, playing. */
    private val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
        when (method.name) {
            "isCommandAvailable" -> true
            "getAvailableCommands" -> Player.Commands.EMPTY
            "getPlaybackState" -> state
            "getPlayWhenReady" -> playWhenReady
            "getMaxSeekToPreviousPosition" -> 3_000L
            "getCurrentPosition", "getContentPosition" -> 60_000L
            "getDuration" -> 600_000L
            "getSeekBackIncrement", "getSeekForwardIncrement" -> 10_000L
            "getCurrentMediaItem" -> item
            "getMediaMetadata" -> tagged
            "getPlayerError" -> failedWith
            "addListener" -> heardByPlayer += args[0] as Player.Listener
            "play" -> {
                calls += "play"
                playWhenReady = true
            }
            "pause" -> {
                calls += "pause"
                playWhenReady = false
            }
            "setPlayWhenReady" -> {
                calls += "setPlayWhenReady ${args[0]}"
                playWhenReady = args[0] as Boolean
            }
            "prepare", "stop", "seekTo", "seekToDefaultPosition", "seekToNext", "seekToPrevious", "setRepeatMode", "setShuffleModeEnabled", "setVolume" -> calls += method.name
            else -> when (method.returnType) {
                java.lang.Long.TYPE -> 0L
                Integer.TYPE -> 0
                java.lang.Boolean.TYPE -> false
                java.lang.Float.TYPE -> 0f
                else -> null
            }
        }
    } as Player

    private val controls = ControlsPlayer(player, now = { 0L })
    private var left = 0
    private val outside = SessionPlayer(controls) { left++ }

    /** What a listener added through the session's player hears of its commands, and of the play otherwise. */
    private class Heard : Player.Listener {
        val commands = mutableListOf<Player.Commands>()
        val playing = mutableListOf<Boolean>()
        val metadata = mutableListOf<MediaMetadata>()
        val errors = mutableListOf<PlaybackException>()
        val errorChanges = mutableListOf<PlaybackException?>()

        override fun onPlayerError(error: PlaybackException) {
            errors += error
        }

        override fun onPlayerErrorChanged(error: PlaybackException?) {
            errorChanges += error
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            metadata += mediaMetadata
        }

        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
            commands += availableCommands
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playing += isPlaying
        }
    }

    private fun heard() = Heard().also(outside::addListener)

    /** The player's own listeners hear [event], as ExoPlayer tells them. */
    private fun told(event: Player.Listener.() -> Unit) = heardByPlayer.toList().forEach(event)

    private fun ended() {
        state = Player.STATE_ENDED
        told { onPlaybackStateChanged(Player.STATE_ENDED) }
    }

    @Test
    fun `outside the app the player plays, pauses, seeks and stops, and nothing else`() {
        val offered = listOf(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_TRACKS,
        )
        offered.forEach { assertTrue(outside.isCommandAvailable(it), "command $it") }
        // The app prepares the player itself and chooses what it plays; repeat would keep a film from ever ending, and the
        // volume, the audio's handling, the speed and the tracks are the app's own.
        val withheld = listOf(
            Player.COMMAND_PREPARE, Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_SET_AUDIO_ATTRIBUTES, Player.COMMAND_SET_VOLUME, Player.COMMAND_SET_PLAYLIST_METADATA, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
        )
        withheld.forEach { assertFalse(outside.isCommandAvailable(it), "command $it") }
        // Next only once the play knows there is a next episode, as the controls' own.
        assertFalse(outside.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        controls.playNext = {}
        assertTrue(outside.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
    }

    /**
     * ExoPlayer's metadata is the file's own tags (an MP4's artist, album and cover art) with the play's laid over them:
     * the session says the play's alone, as watches, car displays and other apps read it (L17, M12).
     */
    @Test
    fun `the session says the play's own metadata, never the file's tags nor its cover art`() {
        val heard = heard()
        assertEquals(emptyList(), fileTagsIn(outside.mediaMetadata), "the file's own, read by the session")
        assertEquals(playsOwn, wordsOf(outside.mediaMetadata))
        told { onMediaMetadataChanged(tagged) }
        assertEquals(emptyList(), heard.metadata.flatMap(::fileTagsIn), "the file's own, told to the session")
        assertEquals(listOf(playsOwn), heard.metadata.map(::wordsOf))
    }

    /** The play's own, as the app said it. */
    private val playsOwn = listOf("S01E02 · High Water", "Lowland Signal", MediaMetadata.MEDIA_TYPE_TV_SHOW.toString())

    /** [m]'s title, subtitle and type; compared so, as the JVM's android.jar has no TextUtils.equals, which MediaMetadata's equals uses. */
    private fun wordsOf(m: MediaMetadata): List<String?> = listOf(m.title?.toString(), m.subtitle?.toString(), m.mediaType?.toString())

    /** What of the file's own tags [m] holds. */
    private fun fileTagsIn(m: MediaMetadata): List<String> =
        listOfNotNull(m.artist, m.albumTitle, m.albumArtist, m.composer, m.recordingYear, m.artworkData?.let { "cover art" }).map { it.toString() }

    /**
     * ExoPlayer's error under "Playback stopped": its message names the track's format and label (an MKV's track title can
     * be the release's name), and its cause, which other apps' controllers are sent whole, a release's volume or the
     * provider's host. The session says that playback stopped, by the error's code, and no more (M12).
     */
    @Test
    fun `under Playback stopped the session says only that it stopped, never the error's own words`() {
        val heard = heard()
        val release = "Lowland.Signal.S01E02.1080p.WEB.h264-LOW"
        val cause = IOException("The archive set of $release.part01.rar lacks its volume 3")
        val error = PlaybackException("MediaCodecVideoRenderer error, index=0, format=Format(1, $release, video/avc)", cause, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        failedWith = error
        told {
            onPlayerError(error)
            onPlayerErrorChanged(error)
        }
        val said = listOfNotNull(outside.playerError) + heard.errors + heard.errorChanges.filterNotNull()
        assertEquals(3, said.size)
        said.forEach { e ->
            assertEquals("Playback stopped", e.message)
            assertNull(e.cause, "the error's cause")
            assertEquals(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, e.errorCode)
        }
        assertSame(outside.playerError, outside.playerError, "the same error for the session each time it reads it")
        failedWith = null
        told { onPlayerErrorChanged(null) }
        assertNull(outside.playerError)
        assertNull(heard.errorChanges.last())
    }

    /** A session's Stop (the Assistant's "stop", a headset's) leaves the player, as the remote's Stop does: Media3's would leave it idle with no panel. */
    @Test
    fun `Stop leaves the player`() {
        outside.stop()
        assertEquals(1, left)
        assertEquals(emptyList(), calls)
    }

    /** Media3's Play prepares an idle player and replays an ended one from its start: neither under "Playback stopped", nor at the end (L11). */
    @Test
    fun `Play is neither offered nor taken under Playback stopped, nor at the end`() {
        playWhenReady = false
        outside.failed = true
        assertFalse(outside.isCommandAvailable(Player.COMMAND_PLAY_PAUSE), "under Playback stopped")
        outside.play()
        outside.prepare()
        assertEquals(emptyList(), calls)
        outside.failed = false
        assertTrue(outside.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
        ended()
        assertFalse(outside.isCommandAvailable(Player.COMMAND_PLAY_PAUSE), "at the end")
        outside.play()
        assertEquals(emptyList(), calls)
        // A seek takes it out of the end, where it may play again.
        state = Player.STATE_READY
        assertTrue(outside.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
    }

    /** In the window Next and Previous would close it for a hand-over that can't run from there (L13). */
    @Test
    fun `in the window there is no Next nor Previous`() {
        controls.playNext = {}
        controls.playPrevious = {}
        outside.pinned = true
        listOf(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .forEach { assertFalse(outside.isCommandAvailable(it), "command $it in the window") }
        assertTrue(outside.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
        assertTrue(outside.isCommandAvailable(Player.COMMAND_SEEK_FORWARD))
        outside.pinned = false
        assertTrue(outside.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(outside.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
    }

    /**
     * The session hears of its player's commands only through onAvailableCommandsChanged (never the controls' events), and
     * passes on what it hears: told whenever they change, as Next comes with the play's lookup seconds in, and with its own
     * commands where ExoPlayer tells its own.
     */
    @Test
    fun `the session is told of the commands as they change, its own and not ExoPlayer's`() {
        val heard = heard()
        controls.playNext = {}
        assertEquals(1, heard.commands.size, "as Next comes")
        outside.pinned = true
        assertEquals(2, heard.commands.size, "into the window")
        outside.pinned = true
        assertEquals(2, heard.commands.size, "nothing changed")
        outside.failed = true
        assertEquals(3, heard.commands.size, "under Playback stopped")
        outside.failed = false
        ended()
        assertEquals(5, heard.commands.size, "at the end")
        val exoPlayers = Player.Commands.Builder().addAllCommands().build()
        told { onAvailableCommandsChanged(exoPlayers) }
        assertEquals(6, heard.commands.size)
        assertNotSame(exoPlayers, heard.commands.last(), "ExoPlayer's commands, which have Prepare and the rest")
        // Everything else as the player tells it.
        told { onIsPlayingChanged(false) }
        assertEquals(listOf(false), heard.playing)
        outside.removeListener(heard)
        controls.playPrevious = {}
        assertEquals(6, heard.commands.size, "removed")
    }

    /**
     * A headset's or a Bluetooth play/pause toggles at once: Media3 would wait for a second press and take two for Next, the
     * next episode. Its up and its repeats are taken too, doing nothing; the other keys are Media3's.
     */
    @Test
    fun `play and pause buttons toggle at once, a second press back again`() {
        listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK).forEach { key ->
            calls.clear()
            assertTrue(outside.mediaButton(key, down = true, repeatCount = 0))
            assertEquals(listOf("pause"), calls, "key $key")
            assertTrue(outside.mediaButton(key, down = true, repeatCount = 1))
            assertTrue(outside.mediaButton(key, down = false, repeatCount = 0))
            assertTrue(outside.mediaButton(key, down = true, repeatCount = 0))
            assertEquals(listOf("pause", "play"), calls, "key $key")
        }
        listOf(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_STOP)
            .forEach { key -> assertFalse(outside.mediaButton(key, down = true, repeatCount = 0), "key $key") }
    }

    @Test
    fun `play and pause buttons do nothing under Playback stopped, nor at the end`() {
        outside.failed = true
        assertTrue(outside.mediaButton(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, down = true, repeatCount = 0))
        outside.failed = false
        ended()
        playWhenReady = false
        assertTrue(outside.mediaButton(KeyEvent.KEYCODE_HEADSETHOOK, down = true, repeatCount = 0))
        assertEquals(emptyList(), calls)
    }
}
