package io.skjaere.usenet.app.link

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.skjaere.usenet.app.home.UpNextPlay
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.data.CollectedShow
import io.skjaere.usenet.metadata.EpisodeRef
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The line the phone says of its plays on other devices (L93, L125; RemoteSaidBar) as the phone's shell shows it over its
 * screens: another screen opening under a line tells the play once (W65: a line held for TalkBack goes then), and the same
 * screen drawn again never does. Over a fake play that says one line and keeps what it is told; the device and the show
 * are invented.
 */
@RunWith(AndroidJUnit4::class)
class RemoteSaidBarDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    /** A play that says [line] and keeps what it is told of it: another screen opening ([left]) and its button ([acted]). */
    private class Told(line: RemoteSaid) : RemotePlaying {
        override val now = MutableStateFlow<RemoteNow?>(null)
        override val said = MutableStateFlow<RemoteSaid?>(line)
        val left = CopyOnWriteArrayList<RemoteSaid>()
        val acted = CopyOnWriteArrayList<RemoteSaid>()

        override fun position(now: RemoteNow) = now.positionMs

        override fun pause() = Unit

        override fun resume() = Unit

        override fun stop() = Unit

        override fun seekTo(positionMs: Long) = Unit

        override fun seekBy(deltaMs: Long) = Unit

        override fun dismiss() = Unit

        override fun act(said: RemoteSaid) {
            acted += said
        }

        override fun left(said: RemoteSaid) {
            left += said
        }
    }

    private val line = RemoteSaid("Watched on Kitchen display", 1, RemoteKind.Cast)

    /** CFT-7, W65: a line said over a screen tells its play nothing while that screen shows, and once as another opens. */
    @Test
    fun anotherScreenOpeningUnderTheLineTellsItsPlayOnce() {
        val told = Told(line)
        var screen by mutableStateOf("home-entry")
        compose.setContent { AppTheme { RemoteSaidBar(told, screen = screen) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(line.text)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertEquals("the screen it was said over", emptyList<RemoteSaid>(), told.left.toList())
        screen = "show-entry"
        compose.waitForIdle()
        assertEquals("another screen opened", listOf(line), told.left.toList())
    }

    /**
     * CFU-4, L125: the shell's first frame knows no screen yet (it reads its back stack's entry a frame later), and a
     * recreation (a rotation) draws the bar anew over the same screen: neither is another screen opening, so a line held
     * for TalkBack stays. Another screen opening after them still tells the play.
     */
    @Test
    fun aScreenNotKnownYetAndARecreationAreNoOtherScreen() {
        val told = Told(line)
        var shown by mutableStateOf("home-entry")
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            // As PhoneShell reads its back stack's entry: none in a new composition's first frame, then the one showing.
            val screen by produceState<String?>(null, shown) { value = shown }
            AppTheme { RemoteSaidBar(told, screen = screen) }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(line.text)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertEquals("a screen not known yet", emptyList<RemoteSaid>(), told.left.toList())
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals("a recreation over the same screen", emptyList<RemoteSaid>(), told.left.toList())
        shown = "show-entry"
        compose.waitForIdle()
        assertEquals("another screen opened", listOf(line), told.left.toList())
    }

    /**
     * CFU-6, L125: the button's words name the device, so none is cut: on a 390 dp phone at the largest font size TalkBack
     * users pick (1.3x), and with a long Cast name at 1.0x, they wrap to a second line rather than lose the device's name.
     */
    @Test
    fun theButtonsWordsAreNeverCut() {
        val words = listOf("Play S02E05 on Kitchen display" to 1.3f, "Play S02E05 on Living room Chromecast" to 1.0f)
        var shown by mutableStateOf(words.first())
        compose.setContent {
            val (text, scale) = shown
            val told = remember(text) { Told(line.copy(action = RemoteSaidAction(text, UpNextPlay.NextEpisode(harborLights, EpisodeRef(2, 5))))) }
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                AppTheme { Box(Modifier.width(390.dp)) { RemoteSaidBar(told, screen = "home-entry") } }
            }
        }
        for (pair in words) {
            shown = pair
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(pair.first)).fetchSemanticsNodes().isNotEmpty() }
            val laidOut = mutableListOf<TextLayoutResult>()
            compose.onNode(hasText(pair.first)).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(laidOut)
            assertFalse("\"${pair.first}\" cut at ${pair.second}x", laidOut.single().hasVisualOverflow)
        }
    }

    private val harborLights = CollectedShow(CollectedShow.idFor(4_101), 4_101, name = "Harbor Lights", addedAt = 0, updatedAt = 0)
}
