package io.skjaere.usenet.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.AutoAdvance
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.NextEpisodeCard
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.newznab.TvIds
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The next-episode card and a TV remote's focus: Play now takes it as the card shows, but not
 * while a burst of seeks waits (a held Left or Right would then hand it the key's repeats), and
 * once only, so a rewind tap on the controls with the card up keeps its focus as its seek runs.
 */
@RunWith(AndroidJUnit4::class)
class NextEpisodeCardFocusTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val next = EpisodePlay(
        showTmdbId = 900002, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(), ids = TvIds(tvdb = 900001),
        season = 2, episode = 6, episodeName = "Breakwater", airDate = null, runtimeMinutes = 45,
    )

    /** Out of touch mode, as a remote leaves the window: the card's buttons take no focus in touch mode. */
    @Before
    fun remote() = instrumentation.setInTouchMode(false)

    @After
    fun touch() = instrumentation.setInTouchMode(true)

    @Test
    fun playNowTakesTheFocusOnceNoSeekWaits() {
        var takeFocus by mutableStateOf(false)
        val card = AutoAdvance.Card(secondsLeft = 8, progress = 0.2f, prefetch = null)
        compose.setContent { AppTheme { NextEpisodeCard(next, card, showStill = false, compact = false, takeFocus = takeFocus, onStop = {}, onPlayNow = {}) } }
        compose.onNodeWithText("Play now").assertIsNotFocused()
        takeFocus = true
        compose.onNodeWithText("Play now").assertIsFocused()
        // The remote moved on (to the controls' rewind; here to Stop): another burst's seek running doesn't bring the focus back.
        compose.onNodeWithText("Stop").requestFocus()
        takeFocus = false
        compose.waitForIdle()
        takeFocus = true
        compose.onNodeWithText("Stop").assertIsFocused()
    }
}
