package io.skjaere.usenet.app.play

import android.view.KeyEvent
import androidx.media3.common.C
import io.skjaere.usenet.app.play.RemoteKeys.Action
import io.skjaere.usenet.app.play.RemoteKeys.Focus
import io.skjaere.usenet.app.play.RemoteKeys.Scene
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.player.SubtitlePlan
import io.skjaere.usenet.player.TrackKey
import io.skjaere.usenet.player.TrackOption
import io.skjaere.usenet.player.TrackTag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteKeysTest {
    /** Playing with the controls hidden, nothing but the picture focused: as a TV remote mostly finds the player. */
    private val hidden = Scene(controlsFullyVisible = false, focus = Focus.Picture, sheet = false, failure = false, cardHasNext = false, seekingKey = null)
    private val ok = listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A)
    private val dpad = ok + listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
    private val anywhere = listOf(KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK, KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_INFO)
    private val leftRight = listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
    /** ⏪ and ⏩. */
    private val rewindForward = listOf(KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
    /** Every scene a key can find outside the sheet and the panel: the controls up or not, whatever has the focus. */
    private val scenes = listOf(false, true).flatMap { up -> Focus.entries.map { hidden.copy(controlsFullyVisible = up, focus = it) } }

    private fun press(key: Int, scene: Scene = hidden) = RemoteKeys.action(key, down = true, repeatCount = 0, scene)

    private fun held(key: Int, scene: Scene = hidden) = RemoteKeys.action(key, down = true, repeatCount = 3, scene)

    private fun released(key: Int, scene: Scene = hidden) = RemoteKeys.action(key, down = false, repeatCount = 0, scene)

    @Test
    fun `with the controls hidden OK and its aliases play or pause, on the picture or with nothing focused`() {
        listOf(Focus.Picture, Focus.Nothing).forEach { focus ->
            ok.forEach { key -> assertEquals(Action.TogglePlay, press(key, hidden.copy(focus = focus)), "key $key on $focus") }
        }
    }

    @Test
    fun `with the controls hidden left and right seek, and go on seeking while held`() {
        listOf(Focus.Picture, Focus.Nothing).forEach { focus ->
            listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT).forEach { key ->
                assertEquals(Action.Seek, press(key, hidden.copy(focus = focus)))
                assertEquals(Action.Seek, held(key, hidden.copy(focus = focus)))
            }
        }
    }

    @Test
    fun `with the controls hidden up and down bring them`() {
        listOf(Focus.Picture, Focus.Nothing).forEach { focus ->
            listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN).forEach { key -> assertEquals(Action.ShowControls, press(key, hidden.copy(focus = focus))) }
        }
    }

    /** OK presses a focused skip button or Play now, and a control keeps its keys as the controls fade (the back arrow leaves). */
    @Test
    fun `a focused skip button, card or control takes the D-pad and OK`() {
        listOf(Focus.Overlay, Focus.Controls).forEach { focus ->
            dpad.forEach { key -> assertEquals(Action.Pass, press(key, hidden.copy(focus = focus)), "key $key on $focus") }
        }
    }

    @Test
    fun `with the controls up the D-pad and OK go to them, whatever has the focus, but Left and Right on the time bar`() {
        Focus.entries.forEach { focus ->
            val keys = if (focus == Focus.TimeBar) dpad - leftRight.toSet() else dpad
            keys.forEach { key -> assertEquals(Action.Pass, press(key, hidden.copy(controlsFullyVisible = true, focus = focus)), "key $key on $focus") }
        }
    }

    /**
     * L2: with the time bar focused, Left and Right seek as with the controls hidden (one burst, one seek, on [ScrubRate]'s
     * curve), with the controls up or as they fade; the key's up ends the hold. Up, Down and OK stay the bar's.
     */
    @Test
    fun `on the focused time bar left and right seek, held too, with the controls up or fading, and their up ends the hold`() {
        listOf(true, false).map { up -> hidden.copy(controlsFullyVisible = up, focus = Focus.TimeBar) }.forEach { bar ->
            leftRight.forEach { key ->
                assertEquals(Action.Seek, press(key, bar), "key $key on $bar")
                assertEquals(Action.Seek, held(key, bar.copy(seekingKey = key)), "key $key held on $bar")
                assertEquals(Action.SeekEnd, released(key, bar.copy(seekingKey = key)), "key $key let go on $bar")
            }
            (ok + listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)).forEach { key ->
                assertEquals(Action.Pass, press(key, bar), "key $key on $bar")
                assertEquals(Action.Pass, released(key, bar), "key $key let go on $bar")
            }
        }
    }

    /** L6: OK on the bar while a hold's seek waits is the bar's as before; with the controls hidden it plays or pauses, the seek first. */
    @Test
    fun `OK while a seek waits does what it did`() {
        val waiting = listOf(null, KeyEvent.KEYCODE_DPAD_RIGHT)
        waiting.forEach { seeking ->
            ok.forEach { key ->
                assertEquals(Action.Pass, press(key, hidden.copy(controlsFullyVisible = true, focus = Focus.TimeBar, seekingKey = seeking)), "key $key, seeking $seeking")
                assertEquals(Action.TogglePlay, press(key, hidden.copy(seekingKey = seeking)), "key $key, seeking $seeking")
            }
        }
    }

    /**
     * L7: ⏪ and ⏩ seek as Left and Right do, through the same burst and curve, with the controls hidden or up and
     * whatever has the focus; PlayerView no longer takes them, so they don't bring the controls. Their up ends the hold.
     */
    @Test
    fun `rewind and fast-forward seek with the controls hidden or up, whatever has the focus`() {
        scenes.forEach { scene ->
            rewindForward.forEach { key ->
                assertEquals(Action.Seek, press(key, scene), "key $key in $scene")
                assertEquals(Action.Seek, held(key, scene.copy(seekingKey = key)), "key $key held in $scene")
                assertEquals(Action.SeekEnd, released(key, scene.copy(seekingKey = key)), "key $key let go in $scene")
            }
        }
    }

    @Test
    fun `the sheet and the failure panel keep every key`() {
        val keys = dpad + anywhere + rewindForward + listOf(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_BACK)
        scenes.flatMap { listOf(it.copy(sheet = true, cardHasNext = true), it.copy(failure = true, cardHasNext = true)) }.forEach { scene ->
            keys.forEach { key ->
                assertEquals(Action.Pass, press(key, scene), "key $key in $scene")
                assertEquals(Action.Pass, released(key, scene), "key $key let go in $scene")
            }
        }
    }

    /** Info opens File details (W2), where a TV player's Info key shows what plays. */
    @Test
    fun `captions, audio track, stop and info work whatever has the focus, the controls up or not`() {
        scenes.forEach { scene ->
            assertEquals(Action.ToggleSubtitles, press(KeyEvent.KEYCODE_CAPTIONS, scene))
            assertEquals(Action.NextAudio, press(KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK, scene))
            assertEquals(Action.Leave, press(KeyEvent.KEYCODE_MEDIA_STOP, scene))
            assertEquals(Action.ToggleDetails, press(KeyEvent.KEYCODE_INFO, scene))
        }
    }

    /** File details open, over the player or over the "Playback stopped" panel: Info closes them, every other key is theirs. */
    @Test
    fun `while file details show, info closes them and they keep every other key`() {
        val keys = dpad + anywhere.filter { it != KeyEvent.KEYCODE_INFO } + rewindForward + listOf(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_BACK)
        scenes.flatMap { listOf(it.copy(details = true, cardHasNext = true), it.copy(details = true, failure = true)) }.forEach { scene ->
            assertEquals(Action.ToggleDetails, press(KeyEvent.KEYCODE_INFO, scene), "in $scene")
            assertEquals(Action.Ignore, released(KeyEvent.KEYCODE_INFO, scene), "in $scene")
            keys.forEach { key ->
                assertEquals(Action.Pass, press(key, scene), "key $key in $scene")
                assertEquals(Action.Pass, released(key, scene), "key $key let go in $scene")
            }
        }
        // A Left or Right held to seek as they opened goes on to them, as to the sheet.
        val seeking = hidden.copy(details = true, seekingKey = KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(Action.Pass, held(KeyEvent.KEYCODE_DPAD_RIGHT, seeking))
        assertEquals(Action.Pass, released(KeyEvent.KEYCODE_DPAD_RIGHT, seeking))
    }

    @Test
    fun `next plays the next episode while the card offers one, and is left to PlayerView otherwise`() {
        scenes.forEach { scene ->
            assertEquals(Action.PlayNext, press(KeyEvent.KEYCODE_MEDIA_NEXT, scene.copy(cardHasNext = true)))
            assertEquals(Action.Pass, press(KeyEvent.KEYCODE_MEDIA_NEXT, scene))
        }
    }

    /** PlayerView takes the other media keys; Back goes through the activity's dispatcher. */
    @Test
    fun `media keys but rewind and fast-forward, Back and any other key are left as they were`() {
        val keys = listOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_SPACE,
        )
        scenes.forEach { scene ->
            keys.forEach { key ->
                assertEquals(Action.Pass, press(key, scene), "key $key in $scene")
                assertEquals(Action.Pass, held(key, scene))
                assertEquals(Action.Pass, released(key, scene))
            }
        }
    }

    /** Only a press's down does something: its repeats and its up are taken, so PlayerView doesn't see half a press. */
    @Test
    fun `the rest of a press acted on is taken, doing nothing`() {
        (ok + listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)).forEach { key ->
            assertEquals(Action.Ignore, held(key), "key $key")
            assertEquals(Action.Ignore, released(key), "key $key")
        }
        // Not a press that sought: nothing to end.
        (leftRight + rewindForward).forEach { key -> assertEquals(Action.Ignore, released(key)) }
        scenes.forEach { scene ->
            anywhere.forEach { key ->
                assertEquals(Action.Ignore, held(key, scene), "key $key in $scene")
                assertEquals(Action.Ignore, released(key, scene), "key $key in $scene")
            }
            assertEquals(Action.Ignore, released(KeyEvent.KEYCODE_MEDIA_NEXT, scene.copy(cardHasNext = true)))
        }
        // A key passed on is passed on whole.
        dpad.forEach { key ->
            assertEquals(Action.Pass, held(key, hidden.copy(focus = Focus.Overlay)))
            assertEquals(Action.Pass, released(key, hidden.copy(controlsFullyVisible = true)))
        }
    }

    /**
     * A Left or Right held to seek with the controls hidden (or ⏪ or ⏩) goes on seeking, and its up
     * ends the hold, whatever took the focus or brought the controls since: the skip button or the
     * card as the burst's seek ran with the target in an intro or the credits, before the key's
     * first repeat.
     */
    @Test
    fun `the rest of a press that seeks is its own, whatever has the focus since`() {
        (leftRight + rewindForward).forEach { key ->
            val since = listOf(hidden, hidden.copy(focus = Focus.Overlay), hidden.copy(focus = Focus.Controls), hidden.copy(controlsFullyVisible = true, focus = Focus.Controls))
            since.map { it.copy(seekingKey = key) }.forEach { scene ->
                assertEquals(Action.Seek, held(key, scene), "key $key held in $scene")
                assertEquals(Action.SeekEnd, released(key, scene), "key $key let go in $scene")
            }
        }
        // A new press goes by the rules again, and so does another key; the sheet and the panel still take every key.
        val toButton = hidden.copy(focus = Focus.Overlay, seekingKey = KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(Action.Pass, press(KeyEvent.KEYCODE_DPAD_RIGHT, toButton))
        assertEquals(Action.Pass, held(KeyEvent.KEYCODE_DPAD_LEFT, toButton))
        assertEquals(Action.Pass, released(KeyEvent.KEYCODE_DPAD_CENTER, toButton))
        listOf(toButton.copy(sheet = true), toButton.copy(failure = true)).forEach { scene ->
            assertEquals(Action.Pass, held(KeyEvent.KEYCODE_DPAD_RIGHT, scene), "in $scene")
            assertEquals(Action.Pass, released(KeyEvent.KEYCODE_DPAD_RIGHT, scene), "in $scene")
        }
    }

    /**
     * A key held to seek stays the one through another key's press, so its up still ends the hold: Right held with the
     * controls hidden, Up bringing them up meanwhile, then Right let go. Pressed again without seeking (its up lost over
     * Bluetooth, say), it isn't any more, and the rest of that press is the controls'.
     */
    @Test
    fun `a held key's up still ends its hold after another key's press, but not after its own press again`() {
        val right = KeyEvent.KEYCODE_DPAD_RIGHT
        val shown = hidden.copy(controlsFullyVisible = true, focus = Focus.Controls)
        var seeking: Int? = null
        // One event as the player takes it: what it does, and the key held to seek after it.
        fun event(key: Int, down: Boolean, repeat: Int, scene: Scene) =
            RemoteKeys.action(key, down, repeat, scene.copy(seekingKey = seeking)).also { seeking = RemoteKeys.seekingKey(seeking, key, down, repeat, it) }
        assertEquals(Action.Seek, event(right, down = true, repeat = 0, hidden))
        assertEquals(Action.Seek, event(right, down = true, repeat = 1, hidden))
        assertEquals(Action.ShowControls, event(KeyEvent.KEYCODE_DPAD_UP, down = true, repeat = 0, hidden))
        assertEquals(Action.Pass, event(KeyEvent.KEYCODE_DPAD_UP, down = false, repeat = 0, shown))
        assertEquals(Action.SeekEnd, event(right, down = false, repeat = 0, shown))
        assertNull(seeking)

        // Held again, its up lost: its next press, on the controls, is theirs.
        assertEquals(Action.Seek, event(right, down = true, repeat = 0, hidden))
        assertEquals(Action.Pass, event(right, down = true, repeat = 0, shown))
        assertNull(seeking)
        assertEquals(Action.Pass, event(right, down = true, repeat = 1, shown))
        assertEquals(Action.Pass, event(right, down = false, repeat = 0, shown))
    }

    /**
     * #321 (CSK-3): a key whose press didn't seek (File details took it, or the TV was switching its frame rate), held on
     * until its repeats do: the first of them that seeks makes it the key held to seek, so its up ends the hold, whatever
     * took the focus meanwhile. A repeat that doesn't seek leaves it as it was.
     */
    @Test
    fun `a key whose press didn't seek is held to seek from its first repeat that does, and its up ends the hold`() {
        val right = KeyEvent.KEYCODE_DPAD_RIGHT
        var seeking: Int? = null
        // One event as the player takes it: what it does, and the key held to seek after it.
        fun event(key: Int, down: Boolean, repeat: Int, scene: Scene) =
            RemoteKeys.action(key, down, repeat, scene.copy(seekingKey = seeking)).also { seeking = RemoteKeys.seekingKey(seeking, key, down, repeat, it) }
        listOf(hidden.copy(details = true) to Action.Pass, hidden.copy(switching = true) to Action.Ignore).forEach { (pressedIn, pressed) ->
            assertEquals(pressed, event(right, down = true, repeat = 0, pressedIn), "pressed in $pressedIn")
            assertNull(seeking)
            assertEquals(pressed, event(right, down = true, repeat = 1, pressedIn), "its first repeat in $pressedIn")
            assertNull(seeking)
            assertEquals(Action.Seek, event(right, down = true, repeat = 2, hidden), "its repeat once $pressedIn went")
            assertEquals(right, seeking)
            assertEquals(Action.Seek, event(right, down = true, repeat = 3, hidden.copy(focus = Focus.Overlay)))
            assertEquals(Action.SeekEnd, event(right, down = false, repeat = 0, hidden.copy(focus = Focus.Overlay)))
            assertNull(seeking)
        }
    }

    /** Logged, a key nothing in the player took says what a box's remote sends that nothing acts on. */
    @Test
    fun `a key nothing takes is logged, but not one Android acts on after the player`() {
        listOf(KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_MEDIA_RECORD, KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_DPAD_CENTER)
            .forEach { key -> assertTrue(RemoteKeys.logsUnhandled(key), "key $key") }
        // The focus moves with these; the window sets the volume with these.
        listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        ).forEach { key -> assertFalse(RemoteKeys.logsUnhandled(key), "key $key") }
    }

    /** Q4: one code path for every D-pad remote, phones with one too. */
    @Test
    fun `the rules hold on every device`() {
        assertTrue(FormFactor.entries.all(RemoteKeys::appliesOn))
    }

    // CAPTIONS and MEDIA_AUDIO_TRACK.

    private fun audio(group: Int, language: String, label: String, selected: Boolean = false, playable: Boolean = true) =
        option(C.TRACK_TYPE_AUDIO, group, language, label, selected = selected, playable = playable)

    private fun text(group: Int, language: String?, label: String, selected: Boolean = false, default: Boolean = false, forced: Boolean = false, playable: Boolean = true) =
        option(C.TRACK_TYPE_TEXT, group, language, label, selected, default, forced, playable)

    private fun option(type: Int, group: Int, language: String?, label: String, selected: Boolean = false, default: Boolean = false, forced: Boolean = false, playable: Boolean = true) =
        TrackOption(
            type = type, groupIndex = group, trackIndex = 0, language = language, label = label, tags = if (forced) setOf(TrackTag.Forced) else emptySet(),
            name = null, playable = playable, locked = false, isDefault = default, selected = selected, key = TrackKey("$group", null, 0, label),
        )

    private val english = audio(0, "en", "English · Stereo · AAC", selected = true)
    private val german = audio(1, "de", "German · 5.1 · AAC")
    private val french = audio(2, "fr", "French · 5.1 · E-AC-3", playable = false)
    private val englishSubs = text(3, "en", "English · SRT")
    private val germanForced = text(4, "de", "German · SRT · Forced", forced = true)
    private val germanSubs = text(5, "de", "German · SRT", default = true)
    private val tracks = listOf(english, german, french, englishSubs, germanForced, germanSubs)

    /** Forced only, the rule's "none", keeps forced subtitles: the key turns them off only after the sheet's Off. */
    @Test
    fun `captions turn full subtitles off, back to what showed before them`() {
        assertEquals(SubtitlePlan.ForcedOnly, RemoteKeys.captions(tracks, SubtitlePlan.Track(germanSubs), last = germanSubs.key, off = SubtitlePlan.ForcedOnly))
        assertEquals(SubtitlePlan.Off, RemoteKeys.captions(tracks, SubtitlePlan.Track(germanSubs), last = germanSubs.key, off = SubtitlePlan.Off))
    }

    @Test
    fun `captions bring back the last subtitles chosen, found by their key`() {
        val again = germanSubs.copy(groupIndex = 7)
        assertEquals(SubtitlePlan.Track(again), RemoteKeys.captions(tracks - germanSubs + again, SubtitlePlan.Off, last = germanSubs.key, off = SubtitlePlan.ForcedOnly))
        // Forced only shows no full subtitles: it counts as off.
        assertEquals(SubtitlePlan.Track(germanForced), RemoteKeys.captions(tracks, SubtitlePlan.ForcedOnly, last = germanForced.key, off = SubtitlePlan.ForcedOnly))
    }

    @Test
    fun `with none shown before, captions pick full subtitles in the audio's language, else the default, else the first`() {
        assertEquals(SubtitlePlan.Track(englishSubs), RemoteKeys.captions(tracks, SubtitlePlan.ForcedOnly, last = null, off = SubtitlePlan.ForcedOnly))
        val inGerman = tracks.map { if (it == english) it.copy(selected = false) else if (it == german) it.copy(selected = true) else it }
        assertEquals(SubtitlePlan.Track(germanSubs), RemoteKeys.captions(inGerman, SubtitlePlan.Off, last = null, off = SubtitlePlan.ForcedOnly))
        val inFrench = tracks.map { it.copy(selected = it.groupIndex == french.groupIndex) }
        assertEquals(SubtitlePlan.Track(germanSubs.copy(selected = false)), RemoteKeys.captions(inFrench, SubtitlePlan.Off, last = null, off = SubtitlePlan.ForcedOnly))
        assertEquals(SubtitlePlan.Track(englishSubs), RemoteKeys.captions(inFrench.filterNot { it.groupIndex == germanSubs.groupIndex }, SubtitlePlan.Off, last = null, off = SubtitlePlan.ForcedOnly))
        // A last track the file no longer has is no help.
        assertEquals(SubtitlePlan.Track(englishSubs), RemoteKeys.captions(tracks, SubtitlePlan.Off, last = TrackKey("gone", null, 0, null), off = SubtitlePlan.ForcedOnly))
    }

    @Test
    fun `captions turn on forced subtitles only when the file has no others, and nothing it can't play`() {
        assertEquals(SubtitlePlan.Track(germanForced), RemoteKeys.captions(listOf(english, germanForced), SubtitlePlan.Off, last = null, off = SubtitlePlan.ForcedOnly))
        val unplayable = englishSubs.copy(playable = false)
        assertNull(RemoteKeys.captions(listOf(english, unplayable), SubtitlePlan.Off, last = unplayable.key, off = SubtitlePlan.ForcedOnly))
        assertNull(RemoteKeys.captions(listOf(english, german), SubtitlePlan.Off, last = null, off = SubtitlePlan.ForcedOnly))
    }

    /**
     * While the TV switches its frame rate, before the player is made ("Matching the TV to 23.976 Hz…"): OK plays at once,
     * Back leaves through the activity's dispatcher and the volume keys reach the system; every other key does nothing,
     * but a gamepad's buttons and Stop (below).
     */
    @Test
    fun `while the TV switches its rate OK plays at once, Back and the volume keys pass, the rest do nothing`() {
        val switching = hidden.copy(switching = true)
        ok.forEach { key ->
            assertEquals(Action.PlayAtOnce, press(key, switching), "key $key")
            assertEquals(Action.Ignore, held(key, switching), "key $key held")
            assertEquals(Action.Ignore, released(key, switching), "key $key let go")
        }
        listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE).forEach { key ->
            assertEquals(Action.Pass, press(key, switching), "key $key")
        }
        val others = dpad - ok.toSet() + (anywhere - KeyEvent.KEYCODE_MEDIA_STOP) + rewindForward +
            listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP)
        others.forEach { key -> assertEquals(Action.Ignore, press(key, switching), "key $key") }
        // Nothing else is up then (no player yet), but the rule holds whatever the scene says besides.
        assertEquals(Action.PlayAtOnce, press(KeyEvent.KEYCODE_DPAD_CENTER, switching.copy(controlsFullyVisible = true, focus = Focus.Controls)))
        assertEquals(Action.TogglePlay, press(KeyEvent.KEYCODE_DPAD_CENTER), "not switching")
    }

    /**
     * A gamepad's buttons are Android's while the TV switches, as everywhere else in the player but A: one nothing takes
     * comes back as the key it stands for in the device's key map (B as Back, which leaves; Start as OK, which plays at
     * once). Stop leaves, as Back does.
     */
    @Test
    fun `while the TV switches a gamepad's buttons pass for the keys they stand for, and Stop leaves`() {
        val switching = hidden.copy(switching = true)
        val gamepad = listOf(
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_BUTTON_1, KeyEvent.KEYCODE_BUTTON_16,
        )
        gamepad.forEach { key ->
            assertEquals(Action.Pass, press(key, switching), "key $key")
            assertEquals(Action.Pass, released(key, switching), "key $key let go")
            assertEquals(press(key), press(key, switching), "key $key as elsewhere")
        }
        assertEquals(Action.Leave, press(KeyEvent.KEYCODE_MEDIA_STOP, switching))
        assertEquals(Action.Ignore, released(KeyEvent.KEYCODE_MEDIA_STOP, switching))
        assertEquals(Action.PlayAtOnce, press(KeyEvent.KEYCODE_BUTTON_A, switching))
    }

    @Test
    fun `the audio track key goes to the next audio this device plays, round to the first`() {
        assertEquals(german, RemoteKeys.nextAudio(tracks))
        // French can't play: past it, round to English.
        assertEquals(english.copy(selected = false), RemoteKeys.nextAudio(tracks.map { it.copy(selected = it.groupIndex == german.groupIndex) }))
        // The only one stays.
        assertEquals(english, RemoteKeys.nextAudio(listOf(english, french, englishSubs)))
        // Nothing selected yet: the first.
        assertEquals(english.copy(selected = false), RemoteKeys.nextAudio(tracks.map { it.copy(selected = false) }))
        assertNull(RemoteKeys.nextAudio(listOf(french, englishSubs)))
    }

    /**
     * A media key nothing in the player takes (⏩ under the sheet, Play under "Playback stopped") goes no further: Android
     * would hand it to the app's own media session, which would act on it under them. The rest go on as before.
     */
    @Test
    fun `media keys stay in the player, the others don't`() {
        val media = listOf(
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_RECORD,
            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK,
        )
        media.forEach { assertTrue(RemoteKeys.isMediaKey(it), "key $it") }
        (dpad + listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_CHANNEL_UP))
            .forEach { assertFalse(RemoteKeys.isMediaKey(it), "key $it") }
    }
}
