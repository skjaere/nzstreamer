package io.skjaere.usenet.app.screens

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The README's screenshots come from the bench build with debrid hidden alone, as tools/screens/capture.sh builds it:
 * that is the app as the published beta shows it, with no developer settings. Any other build fails the run, and says
 * why.
 */
@RunWith(AndroidJUnit4::class)
class ScreensRunTest {
    @Test
    fun theBenchBuildWithDebridHiddenTakesThem() {
        assertNull(ScreensRun.buildProblem("bench", hideDebrid = true))
    }

    @Test
    fun anyOtherBuildIsRefusedAndToldWhy() {
        listOf("debug", "release", "beta").forEach { type ->
            val problem = ScreensRun.buildProblem(type, hideDebrid = true)
            assertTrue("$type: $problem", problem != null && "bench" in problem && "capture.sh" in problem)
        }
        val shown = ScreensRun.buildProblem("bench", hideDebrid = false)
        assertTrue("debrid shown: $shown", shown != null && "hideDebrid" in shown && "capture.sh" in shown)
    }
}
