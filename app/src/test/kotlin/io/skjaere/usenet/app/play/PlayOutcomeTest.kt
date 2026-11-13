package io.skjaere.usenet.app.play

import io.skjaere.usenet.app.settings.NoUsableProvider
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** What a release list marks a release chosen on it with, when its play fails to start: what the play sheet says of it. */
class PlayOutcomeTest {
    private val release = "Harbor.Lights.S01E01.PROPER.1080p.WEB-DL.AAC2.0.H.264-BROKEN"

    @Test
    fun `a release that failed says what its line on the sheet says`() {
        val failed = PlayState.Failed("Harbor Lights", "S01E01", listOf(release to "Incomplete on your providers · 1 of 20 checked articles missing"), notTried = 0)
        assertEquals("Incomplete on your providers · 1 of 20 checked articles missing", PlayOutcome.Failed(failed).reason(release))
    }

    @Test
    fun `a release skipped as found incomplete before says what the sheet says of it`() {
        val failed = PlayState.Failed("Harbor Lights", "S01E01", emptyList(), notTried = 0, missing = listOf(release to "Found incomplete on your providers before · Oct 12"))
        assertEquals("Found incomplete on your providers before · Oct 12", PlayOutcome.Failed(failed).reason(release))
    }

    @Test
    fun `with no usable provider nothing was tried, and it says why as the sheet does`() {
        val failed = noProviderFailure("Harbor Lights", "S01E01", NoUsableProvider.AllOff)
        assertEquals("Every Usenet provider is switched off.", PlayOutcome.Failed(failed).reason(release))
    }

    @Test
    fun `a play whose job failed says something went wrong, as the sheet does`() {
        val failed = PlayState.Failed("Harbor Lights", "S01E01", listOf("Harbor Lights" to "Something went wrong: database or disk is full"), notTried = 0)
        assertEquals("Something went wrong: database or disk is full", PlayOutcome.Failed(failed).reason(release))
    }
}
