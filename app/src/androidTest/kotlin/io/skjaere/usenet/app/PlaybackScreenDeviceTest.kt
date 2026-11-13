package io.skjaere.usenet.app

import android.view.KeyEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.settings.CastBitrateSetting
import io.skjaere.usenet.app.settings.MaxBitrateSetting
import io.skjaere.usenet.app.settings.PlaybackScreen
import io.skjaere.usenet.app.tv.Remote
import io.skjaere.usenet.app.tv.setTvContent
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.PlaybackPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings › Quality and languages' maximum bitrate, picked by touch, and under it the maximum when casting, a choice of
 * its own with its explanation; each has twelve cells in three full rows of four, which a remote goes straight through at
 * a TV's size (W102, L309); on a TV's layouts the frame-rate switch last in Behaviour, a phone's keeping its four rows.
 * The settings are put back as they were afterwards.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackScreenDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container = instrumentation.targetContext.container
    private val settings = container.settings
    private lateinit var saved: AppSettings

    @Before
    fun setUp() = runBlocking<Unit> {
        saved = settings.settings.first()
        settings.restore(saved.copy(playback = PlaybackPrefs()))
    }

    @After
    fun restore() = runBlocking<Unit> {
        // The remote's case takes the device out of touch mode, which outlasts the test.
        instrumentation.setInTouchMode(true)
        settings.restore(saved)
        assertEquals(saved, settings.settings.first())
    }

    @Test
    fun theMaximumBitrateIsNotSetUntilChosenAndIsSavedAsPicked() = runBlocking<Unit> {
        compose.setContent { AppTheme { PlaybackScreen(onBack = {}) } }
        val notSet = hasContentDescription("No maximum bitrate")
        val forty = hasContentDescription("Maximum bitrate 40 Mbps")
        compose.waitUntil(5_000) { exists(notSet) }
        compose.onNode(notSet).performScrollTo().assertIsSelected()
        // A phone's words (L308): larger releases rank higher once a maximum is set.
        compose.onNode(hasText(MaxBitrateSetting.footnote(television = false))).performScrollTo()
        listOf(150, 200).forEach { assertTrue("a cell for $it Mbps (W102)", exists(hasContentDescription("Maximum bitrate $it Mbps"))) }
        assertRowsOfFour { mbps -> if (mbps == null) "No maximum bitrate" else "Maximum bitrate $mbps Mbps" }

        compose.onNode(forty).performScrollTo().performClick()
        compose.waitForIdle()
        withTimeout(5_000) { settings.playback.first { it.maxBitrateMbps == 40 } }
        compose.waitUntil(5_000) { runCatching { compose.onNode(forty).assertIsSelected() }.isSuccess }
        compose.onNode(notSet).assertIsNotSelected()

        // The lowest, for a slow line (W4).
        val five = hasContentDescription("Maximum bitrate 5 Mbps")
        compose.onNode(five).performScrollTo().performClick()
        compose.waitForIdle()
        withTimeout(5_000) { settings.playback.first { it.maxBitrateMbps == 5 } }

        // One of the two above 100, for an 80 GB remux of a 100-minute film (W102).
        val oneFifty = hasContentDescription("Maximum bitrate 150 Mbps")
        compose.onNode(oneFifty).performScrollTo().performClick()
        compose.waitForIdle()
        withTimeout(5_000) { settings.playback.first { it.maxBitrateMbps == 150 } }
        compose.waitUntil(5_000) { runCatching { compose.onNode(oneFifty).assertIsSelected() }.isSuccess }
        compose.onNode(hasContentDescription("Maximum bitrate 200 Mbps")).assertIsNotSelected()

        compose.onNode(notSet).performScrollTo().performClick()
        compose.waitForIdle()
        withTimeout(5_000) { settings.playback.first { it.maxBitrateMbps == null } }
        compose.waitUntil(5_000) { runCatching { compose.onNode(notSet).assertIsSelected() }.isSuccess }
        compose.onNode(forty).assertIsNotSelected()
        compose.onNode(five).assertIsNotSelected()
        compose.onNode(oneFifty).assertIsNotSelected()
    }

    /**
     * At a TV's size, with a remote (L309, L325): the cells are a true grid, so Down from Not set goes straight to 20, then
     * to 80, and Right from there runs along the last row to 200.
     */
    @Test
    fun withARemoteDownAndRightGoStraightThroughTheCells() {
        val remote = Remote(compose)
        compose.setTvContent { PlaybackScreen(onBack = {}) }
        val notSet = hasContentDescription("No maximum bitrate")
        compose.waitUntil(5_000) { exists(notSet) }
        instrumentation.setInTouchMode(false)

        compose.onNode(notSet).performScrollTo().requestFocus()
        compose.waitForIdle()
        remote.waitForFocus("No maximum bitrate")
        assertEquals("Down from Not set", "Maximum bitrate 20 Mbps", remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("Down again", "Maximum bitrate 80 Mbps", remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(
            "Right along the last row",
            listOf("Maximum bitrate 100 Mbps", "Maximum bitrate 150 Mbps", "Maximum bitrate 200 Mbps"),
            List(3) { remote.press(KeyEvent.KEYCODE_DPAD_RIGHT) },
        )
    }

    /**
     * W52, L132 (CsPhoneCastBitrate): under Maximum bitrate's footnote, "Maximum bitrate when casting" with the same twelve
     * choices in the same rows (L309), Not set as it comes, and the explanation under them, before Behaviour. 40
     * picked by touch is saved as the casting maximum alone: Maximum bitrate stays Not set.
     */
    @Test
    fun theMaximumBitrateWhenCastingIsAChoiceOfItsOwnUnderMaximumBitrateWithItsExplanation() = runBlocking<Unit> {
        assumeFalse("a TV never casts: no casting maximum there (tv.TvNoCastTest)", container.television)
        compose.setContent { AppTheme { PlaybackScreen(onBack = {}) } }
        val notSet = hasContentDescription("No maximum bitrate when casting")
        val forty = hasContentDescription("Maximum bitrate when casting 40 Mbps")
        compose.waitUntil(5_000) { exists(notSet) }
        compose.onNode(notSet).performScrollTo().assertIsSelected()
        assertEquals(12, compose.onAllNodes(hasContentDescription("maximum bitrate when casting", substring = true, ignoreCase = true)).fetchSemanticsNodes().size)
        assertRowsOfFour(CastBitrateSetting::label)
        CastBitrateSetting.EXPLANATION.forEach { compose.onNode(hasText(it)).performScrollTo() }

        // In the column's order: Maximum bitrate's footnote, the heading, its choices, the explanation, then Behaviour.
        fun top(matcher: SemanticsMatcher) = compose.onNode(matcher).fetchSemanticsNode().positionInRoot.y
        val tops = listOf(
            hasText(MaxBitrateSetting.footnote(television = false)),
            hasText("MAXIMUM BITRATE WHEN CASTING"),
            notSet,
            hasContentDescription("Maximum bitrate when casting 200 Mbps"),
            hasText(CastBitrateSetting.EXPLANATION.first()),
            hasText(CastBitrateSetting.EXPLANATION.last()),
            hasText("BEHAVIOUR"),
        ).map(::top)
        assertTrue("out of order: $tops", tops.zipWithNext().all { (a, b) -> a < b })

        compose.onNode(forty).performScrollTo().performClick()
        compose.waitForIdle()
        val saved = withTimeout(5_000) { settings.playback.first { it.castMaxBitrateMbps == 40 } }
        assertNull("Maximum bitrate stays not set", saved.maxBitrateMbps)
        compose.waitUntil(5_000) { runCatching { compose.onNode(forty).assertIsSelected() }.isSuccess }
        compose.onNode(notSet).assertIsNotSelected()
        compose.onNode(hasContentDescription("No maximum bitrate")).assertIsSelected()
        compose.onNode(hasContentDescription("Maximum bitrate 40 Mbps")).assertIsNotSelected()

        compose.onNode(notSet).performScrollTo().performClick()
        compose.waitForIdle()
        withTimeout(5_000) { settings.playback.first { it.castMaxBitrateMbps == null } }
        compose.waitUntil(5_000) { runCatching { compose.onNode(notSet).assertIsSelected() }.isSuccess }
        compose.onNode(forty).assertIsNotSelected()
    }

    /**
     * On a TV's layouts (the TV theme, here on a phone emulator) Behaviour ends with "Match the TV to the video's frame
     * rate", in the mockup's words, off until turned on; OK (a click) turns it on and it is saved.
     */
    @Test
    fun onATvBehaviourEndsWithTheFrameRateSwitchOffUntilTurnedOn() = runBlocking<Unit> {
        compose.setContent { AppTheme(FormFactor.Tv) { PlaybackScreen(onBack = {}) } }
        val row = hasText(MATCH_LABEL) and hasText(MATCH_DETAIL)
        compose.waitUntil(5_000) { exists(row) }
        assertEquals("the last of Behaviour's switches", MATCH_LABEL, switches().last())
        assertEquals(5, switches().size)
        compose.onNode(row).performScrollTo().assertIsOff()
        assertEquals(false, settings.playback.first().matchFrameRate)

        compose.onNode(row).performClick()
        compose.waitForIdle()
        withTimeout(5_000) { settings.playback.first { it.matchFrameRate } }
        compose.waitUntil(5_000) { runCatching { compose.onNode(row).assertIsOn() }.isSuccess }
    }

    /** A phone's Behaviour keeps its four rows: the frame-rate switch is a TV's. */
    @Test
    fun onAPhoneBehaviourKeepsItsFourRows() {
        compose.setContent { AppTheme(FormFactor.Phone) { PlaybackScreen(onBack = {}) } }
        compose.waitUntil(5_000) { exists(hasText("Check every article before playing")) }
        assertEquals(
            listOf(
                "Subtitles when audio isn't your first language", "Play next episode automatically", "Look up intro and credits times online",
                "Check every article before playing",
            ),
            switches(),
        )
        assertEquals(false, exists(hasText(MATCH_LABEL)))
    }

    /** Each switch row's label, in the column's order (the tree's: rows scrolled out of sight have clipped bounds). */
    private fun switches(): List<String> = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).fetchSemanticsNodes()
        .map { it.config[SemanticsProperties.Text].first().text }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /**
     * A section's bitrate cells, each found by how TalkBack reads it ([label]), in three full rows of four (L309): Not set,
     * 5, 10 and 15 share a top, as do 20 to 60 and 80 to 200, each row under the one before. Read with no scroll between.
     */
    private fun assertRowsOfFour(label: (Int?) -> String) {
        val rows = listOf(listOf(null, 5, 10, 15), listOf(20, 30, 40, 60), listOf(80, 100, 150, 200))
        val tops = rows.map { row -> row.map { compose.onNode(hasContentDescription(label(it))).fetchSemanticsNode().positionInRoot.y } }
        tops.forEachIndexed { i, row -> assertEquals("row ${i + 1} (${rows[i]}) shares a top: $row", 1, row.distinct().size) }
        assertTrue("rows in order: $tops", tops.map { it.first() }.zipWithNext().all { (a, b) -> a < b })
    }

    private companion object {
        const val MATCH_LABEL = "Match the TV to the video’s frame rate"
        const val MATCH_DETAIL = "Before a film or episode plays, the TV switches to the video’s frame rate (such as 23.976 Hz) for smoother motion. " +
            "The screen goes dark for a second or two while it switches."
    }
}
