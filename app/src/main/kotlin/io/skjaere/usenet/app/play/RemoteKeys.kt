package io.skjaere.usenet.app.play

import android.view.KeyEvent
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.player.SubtitlePlan
import io.skjaere.usenet.player.TrackKey
import io.skjaere.usenet.player.TrackOption

/**
 * What a remote's key does in the player, kept apart from it so it can be tested. media3's
 * PlayerView takes any D-pad key for "show the controls" while they aren't fully up, so OK
 * wouldn't pause with them hidden, nor Left and Right seek (TV-PC). Here, with the controls hidden
 * and only the picture focused (or nothing): OK, Enter and a gamepad's A play or pause and bring
 * the controls, Left and Right seek with them still hidden (further the longer the key is held,
 * [ScrubRate]), and Up and Down bring them as before. A focused skip button or next-episode card
 * takes the keys, so do the controls once up or while one of them keeps the focus as they fade,
 * and the side panels (the audio and subtitles sheet, File details) and the "Playback stopped"
 * panel take every key, but Info, which closes File details. The focused time bar's Left and
 * Right seek the same way, with the controls up or fading, and so do ⏪ and ⏩ wherever the focus
 * is (L2, L7): one burst and one curve for all of them, where media3's bar and buttons stepped
 * 10 s a key event. A key held to seek goes on seeking whatever takes the focus meanwhile, and
 * its up ends the hold ([Action.SeekEnd]).
 *
 * While the TV switches its frame rate before the player is made (the "Matching the TV to 23.976 Hz…" moment), OK
 * plays at once, Back and Stop leave, and the volume keys reach the system; a gamepad's buttons are Android's, as
 * everywhere else here, so B comes back as Back. Every other key does nothing (there is no player yet for them to act on).
 *
 * Captions, the audio track key, Stop and Info (File details, W2) work whatever has the focus;
 * Next while the next-episode card offers one. The other media keys stay PlayerView's, with the controls hidden too:
 * Previous and Next among them, which its controls pass to the episodes either side
 * ([ControlsPlayer]). Back stays the activity's dispatcher's (Android 16 doesn't dispatch the key
 * at targetSdk 36).
 */
object RemoteKeys {
    /**
     * What has the focus: nothing, PlayerView itself, the skip button or the next-episode card, one of the controls (the
     * top bar's buttons too), or the controls' time bar.
     */
    enum class Focus { Nothing, Picture, Overlay, Controls, TimeBar }

    /**
     * The player as a key finds it: the controls fully up, the focus, the audio and subtitles sheet,
     * the "Playback stopped" panel or File details ([details]) showing, the card offering a next
     * episode, and the key whose press seeks (Left, Right, ⏪ or ⏩), from its down to its up
     * ([seekingKey]; null while none does); [switching]: the TV switches its frame rate, and no
     * player is made yet.
     */
    data class Scene(
        val controlsFullyVisible: Boolean, val focus: Focus, val sheet: Boolean, val failure: Boolean, val cardHasNext: Boolean, val seekingKey: Int?,
        val details: Boolean = false, val switching: Boolean = false,
    )

    enum class Action {
        TogglePlay, Seek,

        /** The up of a key held to seek: the hold is over, and the burst's seek follows a moment later. */
        SeekEnd,

        ShowControls, ToggleSubtitles, NextAudio, PlayNext, Leave,

        /** File details opened, or closed while they show. */
        ToggleDetails,

        /** OK while the TV switches its frame rate: the player is made at once, whatever is left of the wait. */
        PlayAtOnce,

        /** The rest of a press acted on (its repeats, its up): taken, doing nothing, so PlayerView doesn't see half a press. */
        Ignore,

        /** Not this policy's: to the player's views as before. */
        Pass,
    }

    /**
     * Whether these rules, and Back hiding the controls first, hold on [formFactor]. Q4: for every
     * D-pad remote, a phone's too ([EVERY_REMOTE]): only D-pad hardware sends these keys, and touch
     * is unchanged.
     */
    fun appliesOn(formFactor: FormFactor): Boolean = EVERY_REMOTE || formFactor == FormFactor.Tv

    /** What [keyCode] does, going down or up, as the [repeatCount]th repeat of a held key, in [scene]. */
    fun action(keyCode: Int, down: Boolean, repeatCount: Int, scene: Scene): Action {
        // The rest of a press that seeks is its own, though the skip button or the card took the
        // focus as the burst's seek ran with the target in an intro or the credits.
        val seeking = keyCode == scene.seekingKey && !(down && repeatCount == 0) && !scene.sheet && !scene.failure && !scene.details
        val pressed = if (seeking) Action.Seek else onPress(keyCode, scene)
        return when {
            pressed == Action.Pass -> Action.Pass
            down && repeatCount == 0 -> pressed
            // Held, a key that seeks goes on seeking; nothing else happens again.
            down && pressed == Action.Seek -> Action.Seek
            // Let go, it ends the hold.
            seeking -> Action.SeekEnd
            else -> Action.Ignore
        }
    }

    /**
     * The key held to seek ([Scene.seekingKey]) once [keyCode] has gone down as the [repeatCount]th repeat, or up ([down]
     * false), [action] being what [action] made of it, and [seekingKey] the one before: a press that seeks is it from then
     * until its up, and so is a repeat that seeks while none is, its press not having (File details or the frame-rate
     * switch took it, #321), so its up ends the hold too. Another key's press that doesn't seek leaves it (Up bringing the
     * controls, the volume), so its up still ends the hold; its own press again without seeking ends it, its up having
     * been lost.
     */
    fun seekingKey(seekingKey: Int?, keyCode: Int, down: Boolean, repeatCount: Int, action: Action): Int? = when {
        down && action == Action.Seek && (repeatCount == 0 || seekingKey == null) -> keyCode
        keyCode != seekingKey || down && repeatCount > 0 -> seekingKey
        else -> null
    }

    private fun onPress(keyCode: Int, scene: Scene): Action = when {
        scene.switching -> when (keyCode) {
            in OK -> Action.PlayAtOnce
            KeyEvent.KEYCODE_MEDIA_STOP -> Action.Leave
            in WHILE_SWITCHING, in GAMEPAD -> Action.Pass
            else -> Action.Ignore
        }
        // Before the panels' rule: File details close on Info, over the "Playback stopped" panel too.
        scene.details -> if (keyCode == KeyEvent.KEYCODE_INFO) Action.ToggleDetails else Action.Pass
        scene.sheet || scene.failure -> Action.Pass
        keyCode == KeyEvent.KEYCODE_CAPTIONS -> Action.ToggleSubtitles
        keyCode == KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> Action.NextAudio
        keyCode == KeyEvent.KEYCODE_MEDIA_NEXT -> if (scene.cardHasNext) Action.PlayNext else Action.Pass
        keyCode == KeyEvent.KEYCODE_MEDIA_STOP -> Action.Leave
        keyCode == KeyEvent.KEYCODE_INFO -> Action.ToggleDetails
        // PlayerView's would step 10 s a repeat and bring the controls (L7).
        keyCode in REWIND_FORWARD -> Action.Seek
        // media3's bar would step 10 s a key event, and only bring the controls back as they fade (L2).
        scene.focus == Focus.TimeBar && keyCode in LEFT_RIGHT -> Action.Seek
        scene.controlsFullyVisible || scene.focus == Focus.Overlay || scene.focus == Focus.Controls || scene.focus == Focus.TimeBar -> Action.Pass
        keyCode in OK -> Action.TogglePlay
        keyCode in LEFT_RIGHT -> Action.Seek
        keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> Action.ShowControls
        else -> Action.Pass
    }

    /**
     * What the captions key shows: while full subtitles show ([shown]), what showed before them
     * ([off]): Forced only, the rule's "none", which keeps forced subtitles, or the sheet's Off.
     * Else the [last] track chosen during this play (by the sheet, this key or the subtitle rule),
     * found again among [options]; else a full track in the audio's language, the default one, or
     * the first; forced subtitles only in a file without others. Null when the file has none this
     * device plays.
     */
    fun captions(options: List<TrackOption>, shown: SubtitlePlan, last: TrackKey?, off: SubtitlePlan): SubtitlePlan? {
        if (shown is SubtitlePlan.Track) return off
        val text = options.filter { it.isText && it.selectable }
        val full = text.filterNot { it.forced }
        val audio = options.firstOrNull { it.isAudio && it.selected }?.language
        val track = text.firstOrNull { it.key == last }
            ?: full.firstOrNull { audio != null && it.language == audio }
            ?: full.firstOrNull { it.isDefault }
            ?: full.firstOrNull()
            ?: text.firstOrNull()
        return track?.let(SubtitlePlan::Track)
    }

    /** The audio the audio track key goes to: the next after the one playing that this device plays, round to the first; the one playing if it is the only one. */
    fun nextAudio(options: List<TrackOption>): TrackOption? {
        val audio = options.filter { it.isAudio }
        val at = audio.indexOfFirst { it.selected }
        return (audio.drop(at + 1) + audio.take(at + 1)).firstOrNull { it.selectable }
    }

    /**
     * Whether a key nothing in the player took is logged as such: not the D-pad's arrows or Tab,
     * with which Android moves the focus once the player has passed them on, nor the volume keys,
     * which the window takes then. What is left, nothing acts on (CHANNEL_UP, SEARCH and so on).
     */
    fun logsUnhandled(keyCode: Int): Boolean = keyCode !in AFTER_THE_PLAYER

    /**
     * Whether [keyCode] is a media key, which the player keeps where nothing in it takes one (⏩ under the sheet, Play under
     * "Playback stopped"): let through, Android would hand it to the app's own media session, which would act on it under
     * them (L16). Media keys from outside the app (Bluetooth, the Assistant) go to the session without passing the player.
     */
    fun isMediaKey(keyCode: Int): Boolean = keyCode in MEDIA

    /** False keeps the rules to TVs (Q4's other answer). */
    private const val EVERY_REMOTE = true

    /** What a remote, a keyboard or a gamepad sends for OK. */
    internal val OK = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A)

    private val LEFT_RIGHT = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)

    /** ⏪ and ⏩, which go back as Left does and on as Right does. */
    private val REWIND_FORWARD = setOf(KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)

    /** Keys that keep their own use while the TV switches: Back, which leaves, and the volume. */
    private val WHILE_SWITCHING = setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)

    /**
     * A gamepad's buttons (KeyEvent.isGamepadButton's): one nothing takes, Android sends again as the key the device's key
     * map has it stand for, B as Back (Start as OK, on some maps X and Y too).
     */
    private val GAMEPAD = ((KeyEvent.KEYCODE_BUTTON_A..KeyEvent.KEYCODE_BUTTON_MODE) + (KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_16)).toSet()

    /** The keys Android hands to a media session once the window has left them, Play and the headset's button among them. */
    private val MEDIA = setOf(
        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_RECORD,
        KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK,
    )

    /** Keys Android acts on after the activity has passed them on. */
    private val AFTER_THE_PLAYER = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_TAB,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
    )
}
