package io.skjaere.usenet.app

import android.app.Activity
import android.content.Intent
import android.media.MediaCodecList
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.Lifecycle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.ShowTrackChoice
import io.skjaere.usenet.data.SubtitleMode
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The audio and subtitles sheet end to end, through the real UsenetDataSource: a minute of test
 * pattern (`app/src/androidTest/fixtures/tracks.mkv`, made with ffmpeg from testsrc, sine tones and
 * the `tracks-*.srt` files beside it) with four audio tracks, English stereo AAC, German 5.1 AAC,
 * an English commentary (named "Commentary", flagged default and comment) and French 5.1 E-AC-3,
 * and four subtitle tracks, English SRT (flagged default), English SDH (named "SDH", flagged
 * hearing impaired), German forced and German. It streams from the mock NNTP server by the NZB in
 * this test's assets. The preferred languages are set through PlayerActivity's settings seam.
 *
 * The NZB stays valid while the mock keeps the fixture's articles (their ids come from the file's
 * bytes). After restarting the mock, seed them again, writing the NZB anywhere but over the asset:
 * `./gradlew :tools:mock-seed:run --args="seed --video app/src/androidTest/fixtures/tracks.mkv --out build/tracks.nzb --segment 100000"`.
 * To change the fixture, regenerate it in that folder and write the NZB over `app/src/androidTest/assets/tracks.nzb`:
 * `ffmpeg -f lavfi -i testsrc=duration=60:size=160x90:rate=10 -f lavfi -i sine=frequency=440:duration=60:sample_rate=8000
 * -f lavfi -i sine=frequency=330:duration=60:sample_rate=8000 -f lavfi -i sine=frequency=550:duration=60:sample_rate=8000
 * -f lavfi -i sine=frequency=660:duration=60:sample_rate=32000 -i tracks-en.srt -i tracks-en-sdh.srt -i tracks-de-forced.srt -i tracks-de.srt
 * -map 0:v -map 1:a -map 2:a -map 3:a -map 4:a -map 5:s -map 6:s -map 7:s -map 8:s
 * -c:v libx264 -profile:v high -pix_fmt yuv420p -preset veryslow -crf 38 -g 50
 * -c:a:0 aac -ac:a:0 2 -b:a:0 12k -c:a:1 aac -ac:a:1 6 -channel_layout:a:1 5.1 -b:a:1 24k -c:a:2 aac -ac:a:2 2 -b:a:2 12k
 * -c:a:3 eac3 -ac:a:3 6 -channel_layout:a:3 5.1 -b:a:3 48k -c:s srt
 * -metadata:s:a:0 language=eng -metadata:s:a:1 language=ger -metadata:s:a:2 language=eng -metadata:s:a:2 title=Commentary -metadata:s:a:3 language=fre
 * -metadata:s:s:0 language=eng -metadata:s:s:1 language=eng -metadata:s:s:1 title=SDH -metadata:s:s:2 language=ger -metadata:s:s:3 language=ger
 * -disposition:v:0 default -disposition:a:0 0 -disposition:a:1 0 -disposition:a:2 default+comment -disposition:a:3 0
 * -disposition:s:0 default -disposition:s:1 hearing_impaired -disposition:s:2 forced -disposition:s:3 0
 * -default_mode passthrough -fflags +bitexact -flags:v +bitexact -flags:a +bitexact tracks.mkv`.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class TrackSheetDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realRead = PlayerActivity.readPlayback
    private val realLookUp = PlayerActivity.lookUpSegments
    private val realNext = PlayerActivity.nextEpisode
    private val realPrevious = PlayerActivity.previousEpisode
    private lateinit var fixture: PreparedNzb

    @Before
    fun setUp() {
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 1119), 2_000) } }.isSuccess)
        runBlocking {
            fixture = engineSetup("the mock on the engine, the tracks fixture prepared") {
                mockOnEngine()
                prepareFixture("tracks")
            }
            container.library.clearTrackChoice(SHOW)
        }
        prefer("en", "de")
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        PlayerActivity.nextEpisode = { _, _ -> null }
        PlayerActivity.previousEpisode = { _, _ -> null }
    }

    @After
    fun restore() {
        PlayerActivity.readPlayback = realRead
        PlayerActivity.lookUpSegments = realLookUp
        PlayerActivity.nextEpisode = realNext
        PlayerActivity.previousEpisode = realPrevious
        instrumentation.setInTouchMode(true)
        runBlocking { container.library.clearTrackChoice(SHOW) }
    }

    /** The preferred audio languages, first one first, with the subtitle rule on. */
    private fun prefer(vararg languages: String) {
        PlayerActivity.readPlayback = { realRead(it).copy(audioLanguages = languages.toList(), subtitlesWhenForeign = true, autoPlayNext = false) }
    }

    private fun uri() = UsenetUris.build(fixture.id, fixture.defaultPick!!.index)

    /** The fixture opened as an NZB file is: no launch, so no show to remember anything for. */
    private fun fileIntent() = Intent(context, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_URI, uri())

    /** The fixture (or the file at [uri]) played as episode [n] of a fake show. */
    private fun episode(n: Int, uri: String = uri()) = PlayerActivity.intent(
        context,
        PlayerLaunch(
            uri = uri, title = "Harbor Lights", subtitle = "S01E0$n · Episode $n", watchKey = WatchKey.episode(SHOW, 1, n),
            watch = WatchTarget(WatchKind.Episode, "Harbor Lights S01E0$n", showTmdbId = SHOW, season = 1, episode = n),
            release = SavedRelease("fixture", "tracks", "Harbor.Lights.S01E0$n.Tracks", null, fixture.defaultPick!!.index, fixture.id),
        ),
    )

    @Test
    fun theSheetListsTheFilesTracksAndMarksWhatPlays() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            awaitPlaying(scenario, audio = ENGLISH, text = null)
            // media3's own subtitle button and settings menu are gone: no audio entry, and no playback speed.
            // Its settings popup opened over the gear and off the screen's edge (#200, #211).
            scenario.onActivity { a ->
                val shown = playerView(a).player!!
                assertFalse(shown.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS))
                assertFalse(shown.availableCommands.contains(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS))
                assertFalse(shown.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH))
                assertFalse(shown.availableCommands.contains(Player.COMMAND_SET_SPEED_AND_PITCH))
                playerView(a).showController()
            }
            waitFor("the controls") { scenario.read { a -> playerView(a).isControllerFullyVisible.takeIf { it } } }
            scenario.read { a ->
                assertFalse("media3's subtitle button shows", a.findViewById<View>(androidx.media3.ui.R.id.exo_subtitle)?.isShown == true)
                assertFalse("media3's settings button shows", a.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.isShown == true)
                // The top bar: the episode's title, and the button.
                assertTrue(audioButton(a).isShown)
            }
            scenario.onActivity { audioButton(it).performClick() }
            val rows = waitFor("the sheet") { scenario.read { a -> sheetRows(a).takeIf { it.isNotEmpty() } } }
            val eac3 = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { c -> !c.isEncoder && c.supportedTypes.any { it.equals(MimeTypes.AUDIO_E_AC3, ignoreCase = true) } }
            assertEquals(
                listOf(
                    "English · Stereo · AAC | current",
                    "German · 5.1 · AAC",
                    "English · Stereo · AAC · Commentary",
                    "French · 5.1 · E-AC-3" + if (eac3) "" else " · Can't play on this device | disabled",
                    "Off",
                    "Forced only · Only parts in another language | current",
                    "English · SRT",
                    "English · SRT · SDH",
                    "German · SRT · Forced",
                    "German · SRT",
                ),
                rows.map { it.describe() },
            )
            scenario.read { a ->
                // The controls stay down while the sheet shows; playback goes on.
                assertFalse(playerView(a).isControllerFullyVisible)
                assertTrue(a.player!!.playWhenReady)
            }
            // Picked by touch: applied at once, the sheet staying for the other part.
            click(scenario, "German · 5.1 · AAC")
            awaitPlaying(scenario, audio = GERMAN, text = null, forced = true)
            click(scenario, "English · SRT · SDH")
            awaitPlaying(scenario, audio = GERMAN, text = ENGLISH_SDH)
            val marked = scenario.read { a -> sheetRows(a).filter { it.current }.map { it.text } }
            assertEquals(listOf("German · 5.1 · AAC", "English · SRT · SDH"), marked)
            // A file, not a show: nothing to reset.
            assertTrue(scenario.read { a -> sheetRows(a).none { "Reset to my defaults" in it.text } })
            click(scenario, "Off")
            awaitPlaying(scenario, audio = GERMAN, text = null)
            scenario.read { a -> assertTrue(C.TRACK_TYPE_TEXT in a.player!!.trackSelectionParameters.disabledTrackTypes) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("the sheet to close") { scenario.read { a -> sheetRows(a).isEmpty().takeIf { it } } }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    /** A TV remote: the sheet opens on the audio playing; the D-pad goes through every row; OK picks; Back goes back to the button. */
    @Test
    fun onATvRemoteTheDpadPicksAudioAndSubtitlesAndBackReturnsToTheButton() = runBlocking<Unit> {
        instrumentation.setInTouchMode(false)
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            awaitPlaying(scenario, audio = ENGLISH, text = null)
            scenario.onActivity { playerView(it).showController() }
            waitFor("the controls, play/pause focused") { scenario.read { a -> (playerView(a).isControllerFullyVisible && a.currentFocus?.id == androidx.media3.ui.R.id.exo_play_pause).takeIf { it } } }
            // Up from the controls' centre goes to the button.
            press(scenario, KeyEvent.KEYCODE_DPAD_UP, "the audio and subtitles button") { a -> a.currentFocus === audioButton(a) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            val first = waitFor("the sheet, focused on the audio playing") { scenario.read { a -> focusedRow(a)?.takeIf { it.current } } }
            assertEquals("English · Stereo · AAC", first.text)
            // Down through every row, picking German audio and English subtitles on the way.
            val eac3 = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { c -> !c.isEncoder && c.supportedTypes.any { it.equals(MimeTypes.AUDIO_E_AC3, ignoreCase = true) } }
            val order = listOf(
                "German · 5.1 · AAC", "English · Stereo · AAC · Commentary", "French · 5.1 · E-AC-3" + if (eac3) "" else " · Can't play on this device",
                "Off", "Forced only · Only parts in another language", "English · SRT", "English · SRT · SDH", "German · SRT · Forced", "German · SRT",
            )
            order.forEach { row ->
                press(scenario, KeyEvent.KEYCODE_DPAD_DOWN, row) { a -> focusedRow(a)?.text == row }
                if (row == "German · 5.1 · AAC") {
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                    awaitPlaying(scenario, audio = GERMAN, text = null, forced = true)
                }
                if (row == "English · SRT") {
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                    awaitPlaying(scenario, audio = GERMAN, text = ENGLISH_SUBS)
                }
            }
            // The last row keeps the focus: it never leaves the sheet for what is under it.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            delay(500)
            assertEquals("German · SRT", scenario.read { a -> focusedRow(a)?.text })
            // Up goes back through the sections.
            repeat(6) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP) }
            waitFor("the French row") { scenario.read { a -> focusedRow(a)?.text?.takeIf { it.startsWith("French") } } }
            // The sheet kept the picks marked, and the controls down.
            scenario.read { a ->
                assertEquals(listOf("German · 5.1 · AAC", "English · SRT"), sheetRows(a).filter { it.current }.map { it.text })
                assertFalse(playerView(a).isControllerFullyVisible)
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("the sheet to close, the button focused") { scenario.read { a -> (sheetRows(a).isEmpty() && a.currentFocus === audioButton(a)).takeIf { it } } }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            awaitPlaying(scenario, audio = GERMAN, text = ENGLISH_SUBS)
        }
    }

    /**
     * Backlog #107: what the sheet picked stays when the player is made again, on onStop/onStart
     * and on recreation. A pick turns the subtitle rule off for the play: German audio picked alone
     * keeps the subtitles as they were (none but forced), where the rule would give English ones.
     */
    @Test
    fun choicesSurviveTheScreenGoingOffAndTheActivityBeingRecreated() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            awaitPlaying(scenario, audio = ENGLISH, text = null)
            scenario.onActivity { a -> playerView(a).showController(); audioButton(a).performClick() }
            waitFor("the sheet") { scenario.read { a -> sheetRows(a).takeIf { it.isNotEmpty() } } }
            click(scenario, "German · 5.1 · AAC")
            awaitPlaying(scenario, audio = GERMAN, text = null, forced = true)
            val before = scenario.read { it.player }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitPlaying(scenario, audio = GERMAN, text = null, forced = true)
            assertNotSame("the player wasn't made again", before, scenario.read { it.player })
            // Held there a while: neither the rule nor the defaults come back.
            delay(2_000)
            awaitPlaying(scenario, audio = GERMAN, text = null, forced = true)

            scenario.onActivity { a -> playerView(a).showController(); audioButton(a).performClick() }
            waitFor("the sheet") { scenario.read { a -> sheetRows(a).takeIf { it.isNotEmpty() } } }
            click(scenario, "English · SRT")
            awaitPlaying(scenario, audio = GERMAN, text = ENGLISH_SUBS)
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitPlaying(scenario, audio = GERMAN, text = ENGLISH_SUBS)
            scenario.recreate()
            awaitPlaying(scenario, audio = GERMAN, text = ENGLISH_SUBS)
            delay(2_000)
            awaitPlaying(scenario, audio = GERMAN, text = ENGLISH_SUBS)
        }
    }

    /** A pick during an episode is the show's from then on: the next episode plays it, until Reset to my defaults. */
    @Test
    fun aShowKeepsThePickForItsOtherEpisodesUntilReset() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(episode(1)).use { scenario ->
            awaitPlaying(scenario, audio = ENGLISH, text = null)
            scenario.onActivity { a -> playerView(a).showController(); audioButton(a).performClick() }
            waitFor("the sheet") { scenario.read { a -> sheetRows(a).takeIf { it.isNotEmpty() } } }
            click(scenario, "German · 5.1 · AAC")
            click(scenario, "English · SRT")
            awaitPlaying(scenario, audio = GERMAN, text = ENGLISH_SUBS)
            val saved = waitFor("the show's choice to be saved") { runBlocking { container.library.trackChoice(SHOW)?.takeIf { it.subtitles == SubtitleMode.Language } } }
            assertEquals(
                ShowTrackChoice(SHOW, audioLanguage = "de", subtitles = SubtitleMode.Language, subtitleLanguage = "en", updatedAt = saved.updatedAt),
                saved,
            )
            assertTrue(scenario.read { a -> sheetRows(a).any { it.text.startsWith("Reset to my defaults") } })
        }
        ActivityScenario.launch<PlayerActivity>(episode(2)).use { scenario ->
            awaitPlaying(scenario, audio = GERMAN, text = ENGLISH_SUBS)
            scenario.onActivity { a -> playerView(a).showController(); audioButton(a).performClick() }
            waitFor("the sheet") { scenario.read { a -> sheetRows(a).takeIf { it.isNotEmpty() } } }
            click(scenario, "Reset to my defaults")
            // The preferences again, at once: English audio, and no full subtitles for it.
            awaitPlaying(scenario, audio = ENGLISH, text = null)
            waitFor("the show's choice to go") { runBlocking { (container.library.trackChoice(SHOW) == null).takeIf { it } } }
            waitFor("the reset row to go") { scenario.read { a -> sheetRows(a).none { it.text.startsWith("Reset") }.takeIf { it } } }
        }
        // And the episode after plays by the preferences.
        ActivityScenario.launch<PlayerActivity>(episode(3)).use { scenario ->
            awaitPlaying(scenario, audio = ENGLISH, text = null)
        }
    }

    /**
     * The show keeps only the parts picked, each over what it had: a part never picked follows the
     * preferences and the rule in later episodes. Subtitles picked alone leave the audio to the
     * preferred languages, which here change before the next episode; audio picked there joins
     * the subtitles the show had.
     */
    @Test
    fun aShowRemembersOnlyThePartsPicked() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(episode(1)).use { scenario ->
            awaitPlaying(scenario, audio = ENGLISH, text = null)
            openSheet(scenario)
            click(scenario, "German · SRT")
            awaitPlaying(scenario, audio = ENGLISH, text = GERMAN_SUBS)
            val saved = waitFor("the show's choice to be saved") { runBlocking { container.library.trackChoice(SHOW) } }
            assertEquals(ShowTrackChoice(SHOW, subtitles = SubtitleMode.Language, subtitleLanguage = "de", updatedAt = saved.updatedAt), saved)
        }
        prefer("de", "en")
        ActivityScenario.launch<PlayerActivity>(episode(2)).use { scenario ->
            // German audio by the preferences, with the show's German subtitles.
            awaitPlaying(scenario, audio = GERMAN, text = GERMAN_SUBS)
            openSheet(scenario)
            click(scenario, "English · Stereo · AAC")
            awaitPlaying(scenario, audio = ENGLISH, text = GERMAN_SUBS)
            val saved = waitFor("the show's audio to be saved") { runBlocking { container.library.trackChoice(SHOW)?.takeIf { it.audioLanguage != null } } }
            assertEquals(
                ShowTrackChoice(SHOW, audioLanguage = "en", subtitles = SubtitleMode.Language, subtitleLanguage = "de", updatedAt = saved.updatedAt),
                saved,
            )
        }
    }

    /**
     * The top bar's title and audio and subtitles button go as the controls' buttons fade after
     * their timeout, with the dim behind them, not 2 s later with the time bar. The back arrow
     * stays with the time bar, so a TV remote's OK on it still leaves (SkipSegmentsDeviceTest).
     */
    @Test
    fun theTitleAndTheButtonFadeWithTheControls() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(episode(1)).use { scenario ->
            awaitPlaying(scenario, audio = ENGLISH, text = null)
            scenario.onActivity { a ->
                playerView(a).controllerShowTimeoutMs = 1_000
                playerView(a).showController()
            }
            waitFor("the controls with the top bar") {
                scenario.read { a -> (playerView(a).isControllerFullyVisible && title(a).isShown && audioButton(a).isShown).takeIf { it } }
            }
            waitFor("the controls to fade to the time bar", timeoutMs = 5_000) {
                scenario.read { a -> (!playerView(a).isControllerFullyVisible && a.findViewById<View>(androidx.media3.ui.R.id.exo_progress).isShown).takeIf { it } }
            }
            scenario.read { a ->
                assertFalse("the title outlived the controls' buttons", title(a).isShown)
                assertFalse("the audio and subtitles button outlived the controls' buttons", audioButton(a).isShown)
                assertTrue("the back arrow went before the time bar", backArrow(a).isShown)
            }
            // Up again with a tap: the whole bar.
            scenario.onActivity { a -> playerView(a).showController() }
            waitFor<Boolean>("the top bar again") { scenario.read { a -> (playerView(a).isControllerFullyVisible && title(a).isShown && audioButton(a).isShown).takeIf { it } } }
        }
    }

    /** As the back arrow does, the audio and subtitles button keeps a TV remote's focus as the controls fade, and OK opens the sheet. */
    @Test
    fun onATvRemoteTheFocusedButtonStaysAsTheControlsFade() = runBlocking<Unit> {
        instrumentation.setInTouchMode(false)
        ActivityScenario.launch<PlayerActivity>(episode(1)).use { scenario ->
            awaitPlaying(scenario, audio = ENGLISH, text = null)
            scenario.onActivity { a ->
                playerView(a).controllerShowTimeoutMs = 1_000
                playerView(a).showController()
                assertTrue(audioButton(a).requestFocus())
            }
            // Fully up first: on their way up they are not fully visible either, with the time bar shown.
            waitFor("the controls fully up") { scenario.read { a -> playerView(a).isControllerFullyVisible.takeIf { it } } }
            waitFor("the controls to fade with the button still focused", timeoutMs = 5_000) {
                scenario.read { a -> (!playerView(a).isControllerFullyVisible && a.findViewById<View>(androidx.media3.ui.R.id.exo_progress).isShown).takeIf { it } }
            }
            scenario.read { a ->
                assertTrue(audioButton(a).isShown && audioButton(a).isFocused)
                assertFalse(title(a).isShown)
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor<Boolean>("the sheet") { scenario.read { a -> sheetRows(a).isNotEmpty().takeIf { it } } }
        }
    }

    /**
     * Until the player knows the file's tracks (it is still opening it, or failed to) the sheet
     * says so, with nothing to pick and nothing marked as current, Reset included: a press there
     * would save for the show a choice nobody made. Here the file isn't there, so they never come.
     */
    @Test
    fun beforeTheTracksAreKnownTheSheetOffersNothing() = runBlocking<Unit> {
        container.library.setTrackChoice(ShowTrackChoice(SHOW, audioLanguage = "de"))
        ActivityScenario.launch<PlayerActivity>(episode(1, uri = UsenetUris.build("0badc0de", 0))).use { scenario ->
            waitFor("the player") { scenario.read { it.player } }
            scenario.onActivity { a -> playerView(a).showController(); audioButton(a).performClick() }
            waitFor("the sheet") { scenario.read { a -> sheetWords(a).takeIf { "Audio and subtitles" in it } } }
            scenario.read { a ->
                assertTrue(a.player!!.currentTracks.isEmpty)
                assertEquals(emptyList<String>(), sheetRows(a).map { it.describe() })
                assertTrue(sheetWords(a).toString(), "Loading tracks…" in sheetWords(a))
            }
            assertEquals(ShowTrackChoice(SHOW, audioLanguage = "de"), container.library.trackChoice(SHOW)?.copy(updatedAt = 0))
        }
    }

    /**
     * For TalkBack the sheet is a pane, announced as it opens, and what lies under it is out of
     * reach while it shows: the picture with its controls, the next-episode card, the skip
     * button, the missing-parts prompt, the slow-stream banner and the "Playback stopped" panel.
     * All of it comes back as the sheet closes.
     */
    @Test
    fun forTalkBackTheSheetIsAPaneOverWhatIsUnderIt() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            awaitPlaying(scenario, audio = ENGLISH, text = null)
            val before = scenario.read { a -> under(a).map { it.importantForAccessibility } }
            assertEquals(6, before.size)
            openSheet(scenario)
            scenario.read { a ->
                assertEquals(listOf("Audio and subtitles"), paneTitles(a))
                under(a).forEach { v -> assertEquals("$v", View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, v.importantForAccessibility) }
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("the sheet to close") { scenario.read { a -> sheetRows(a).isEmpty().takeIf { it } } }
            assertEquals(before, scenario.read { a -> under(a).map { it.importantForAccessibility } })
        }
    }

    /**
     * Finding #9, checked on a device: with nothing chosen, the subtitle rule decides, for each
     * player. English audio for an English-first viewer gets no full subtitles (the file's
     * default-flagged English track stays off, also once the player is made again); German audio
     * for a German-first viewer only its forced subtitles.
     */
    @Test
    fun withNothingChosenTheSubtitleRuleStillAppliesForEachPlayer() = runBlocking<Unit> {
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            // The main English track, not the default-flagged commentary; and no subtitles.
            awaitPlaying(scenario, audio = ENGLISH, text = null)
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitPlaying(scenario, audio = ENGLISH, text = null)
            delay(2_000)
            awaitPlaying(scenario, audio = ENGLISH, text = null)
        }
        prefer("de", "en")
        ActivityScenario.launch<PlayerActivity>(fileIntent()).use { scenario ->
            awaitPlaying(scenario, audio = GERMAN, text = null, forced = true)
        }
    }

    /**
     * A show's choice without subtitles (audio picked alone; each part is planned on its own)
     * leaves them to the rule: German audio gets English subtitles for an English-first viewer.
     */
    @Test
    fun aShowsAudioAloneLeavesTheSubtitlesToTheRule() = runBlocking<Unit> {
        container.library.setTrackChoice(ShowTrackChoice(SHOW, audioLanguage = "de"))
        ActivityScenario.launch<PlayerActivity>(episode(1)).use { scenario ->
            awaitPlaying(scenario, audio = GERMAN, text = ENGLISH_SUBS)
        }
    }

    // What plays, as the player's tracks say.

    private data class Track(val language: String, val channels: Int = Format.NO_VALUE, val label: String? = null)

    /** Waits until the player plays [audio] with [text] showing (null: no full subtitles; [forced]: the German forced track shows). */
    private suspend fun awaitPlaying(scenario: ActivityScenario<PlayerActivity>, audio: Track, text: Track?, forced: Boolean = false) {
        waitFor("$audio with subtitles $text${if (forced) " (forced)" else ""}, not ${scenario.read { playing(it) }}", timeoutMs = 30_000) {
            scenario.read { a ->
                val (a1, t1) = playing(a) ?: return@read null
                val textOk = when {
                    text != null -> t1 == text
                    forced -> t1 == FORCED
                    else -> t1 == null || t1 == FORCED && audio == GERMAN
                }
                (a1 == audio && textOk).takeIf { it }
            }
        }
    }

    /** The selected audio and subtitle tracks; null until the player knows its tracks. */
    private fun playing(a: PlayerActivity): Pair<Track?, Track?>? {
        val tracks = a.player?.currentTracks?.takeUnless { it.isEmpty } ?: return null
        fun selected(type: Int) = tracks.groups.filter { it.type == type && it.isSelected }
            .firstNotNullOfOrNull { g -> (0 until g.length).firstOrNull(g::isTrackSelected)?.let(g::getTrackFormat) }
        val audio = selected(C.TRACK_TYPE_AUDIO)?.let { Track(it.language.orEmpty(), it.channelCount, it.label) }
        val text = selected(C.TRACK_TYPE_TEXT)?.let { f -> if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) FORCED else Track(f.language.orEmpty(), label = f.label) }
        return audio to text
    }

    // The sheet, as its semantics say.

    private data class Row(val text: String, val current: Boolean, val enabled: Boolean, val focused: Boolean, val node: SemanticsNode) {
        fun describe() = listOfNotNull(text, "current".takeIf { current }, "disabled".takeIf { !enabled }).joinToString(" | ")
    }

    /** The sheet's rows, top to bottom: the nodes that can be selected, with their words; none while it is closed. */
    private fun sheetRows(activity: Activity): List<Row> =
        views(activity.window.decorView).filterIsInstance<ViewRootForTest>().filter { it.view.isShown }
            .flatMap { subtree(it.semanticsOwner.unmergedRootSemanticsNode) }
            .filter { it.config.contains(SemanticsProperties.Selected) || it.config.getOrNull(SemanticsProperties.Role) != null && it.config.contains(SemanticsActions.OnClick) && "Reset to my defaults" in subtree(it).flatMap { n -> n.words() } }
            .map { n ->
                Row(
                    text = subtree(n).flatMap { it.words() }.joinToString(" · "),
                    current = n.config.getOrNull(SemanticsProperties.Selected) == true || n.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On,
                    enabled = !n.config.contains(SemanticsProperties.Disabled),
                    focused = n.config.getOrNull(SemanticsProperties.Focused) == true || subtree(n).any { it.config.getOrNull(SemanticsProperties.Focused) == true },
                    node = n,
                )
            }

    private fun focusedRow(activity: Activity) = sheetRows(activity).firstOrNull { it.focused }

    /**
     * The views the sheet covers: the picture with the controls, and the overlays beside it (the
     * card and the skip button, at the bottom end and as big as their content; the sheet fills the screen).
     */
    private fun under(activity: Activity): List<View> {
        val picture = playerView(activity)
        val root = picture.parent as ViewGroup
        val overlays = (0 until root.childCount).map(root::getChildAt).filterIsInstance<ComposeView>()
            .filter { it.layoutParams.width == ViewGroup.LayoutParams.WRAP_CONTENT }
        return listOf(picture) + overlays
    }

    /** The pane titles of the shown Compose views. */
    private fun paneTitles(activity: Activity): List<String> =
        views(activity.window.decorView).filterIsInstance<ViewRootForTest>().filter { it.view.isShown }
            .flatMap { subtree(it.semanticsOwner.unmergedRootSemanticsNode) }.mapNotNull { it.config.getOrNull(SemanticsProperties.PaneTitle) }

    /** Every word the shown Compose views hold: the sheet's, while it shows. */
    private fun sheetWords(activity: Activity): List<String> =
        views(activity.window.decorView).filterIsInstance<ViewRootForTest>().filter { it.view.isShown }
            .flatMap { subtree(it.semanticsOwner.unmergedRootSemanticsNode) }.flatMap { it.words() }

    /** Opens the sheet by touch, from the controls' top bar. */
    private suspend fun openSheet(scenario: ActivityScenario<PlayerActivity>) {
        scenario.onActivity { a -> playerView(a).showController(); audioButton(a).performClick() }
        waitFor("the sheet") { scenario.read { a -> sheetRows(a).takeIf { it.isNotEmpty() } } }
    }

    private suspend fun click(scenario: ActivityScenario<PlayerActivity>, row: String) {
        scenario.onActivity { a ->
            val rows = sheetRows(a)
            val target = rows.firstOrNull { it.text == row } ?: rows.first { it.text.startsWith("$row · ") }
            target.node.config[SemanticsActions.OnClick].action!!.invoke()
        }
        delay(200)
    }

    private suspend fun press(scenario: ActivityScenario<PlayerActivity>, key: Int, what: String, isIt: (PlayerActivity) -> Boolean) {
        instrumentation.sendKeyDownUpSync(key)
        waitFor("$what to have focus after ${KeyEvent.keyCodeToString(key)}, not ${scenario.read { a -> focusedRow(a)?.text ?: a.currentFocus.toString() }}", timeoutMs = 3_000) {
            scenario.read { a -> isIt(a).takeIf { it } }
        }
    }

    private fun audioButton(activity: Activity) = views(activity.window.decorView).single { it.contentDescription == "Audio and subtitles" }

    private fun backArrow(activity: Activity) = views(activity.window.decorView).filterIsInstance<ImageButton>().single { it.contentDescription == "Back" }

    /** The top bar's title: the episode's name. */
    private fun title(activity: Activity) = views(activity.window.decorView).filterIsInstance<TextView>().single { it.text.toString() == "S01E01 · Episode 1" }

    private fun <T> ActivityScenario<PlayerActivity>.read(probe: (PlayerActivity) -> T): T {
        var result: Result<T>? = null
        onActivity { result = runCatching { probe(it) } }
        return result!!.getOrThrow()
    }

    private suspend fun <T : Any> waitFor(what: String, timeoutMs: Long = 20_000, probe: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
            delay(100)
        }
    }

    private fun views(view: View): List<View> =
        listOf(view) + ((view as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())

    private fun playerView(activity: Activity) = views(activity.window.decorView).filterIsInstance<PlayerView>().single()

    private fun subtree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::subtree)

    private fun SemanticsNode.words(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private companion object {
        /** A fake show's id in TMDB numbering; nothing real has it. */
        const val SHOW = 900021
        val ENGLISH = Track("en", 2)
        val GERMAN = Track("de", 6)
        val ENGLISH_SUBS = Track("en")
        val ENGLISH_SDH = Track("en", label = "SDH")
        val GERMAN_SUBS = Track("de")
        val FORCED = Track("forced")
    }
}
