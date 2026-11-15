package io.skjaere.usenet.app.download

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The second half of the DownloadStopApp pair (L359, L367, L389): after [StartDownloadStopApp], the app's open and the
 * runner's Task Manager's Stop (`cmd activity stop-app`) as the first of two downloads ran, the queue is held:
 * reconcile's line after the stop holds the first, "Stopped by Android", with the user's exit, no reopen after the stop
 * (the job Android starts again at once may come before the line: it ends with nothing to run), and the second never
 * started. Then the user's Resume, with the app in front, lets it go: both go on to done, the first from where it
 * stopped.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CheckDownloadStopApp {
    /** Only where its run names it, on a phone ([PairRun]). */
    @get:Rule
    val pair = PairRun()

    private val check = PairCheck()

    @After
    fun putBack() = runBlocking<Unit> { check.putBack() }

    @Test
    fun theStopHoldsTheQueueUntilResumeThenBothGoOn() = runBlocking<Unit> {
        PairRecord.record("check")
        val held = check.held()

        check.resume(listOf(held.first.id, held.second.id))
        val reopen = check.ownReopens(held.first.id).firstOrNull() ?: throw AssertionError("the first never reopened after Resume")
        assertEquals("the first goes on from where it stopped", held.first.safeOffset, reopen["offset"].toLong())
        check.assertWhole(listOf(held.first.id, held.second.id))
    }
}
