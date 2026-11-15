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
 * The second half of the DownloadForceStop pair (L359, L367, L389): after [StartDownloadForceStop], the app's open and
 * the runner's force stop (`am force-stop`) as the first of two downloads ran, the queue is held as after Task
 * Manager's Stop: reconcile's line after it holds the first, "Stopped by Android", with the user's exit (from whichever
 * process started next: one Android started for the cancelled job, or the app's open again), no reopen after the stop,
 * and the second never started; and no host start after it, as the force stop cancelled the job. Then the user's
 * Resume, with the app in front, lets it go: both go on to done, the first from where it stopped.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CheckDownloadForceStop {
    /** Only where its run names it, on a phone ([PairRun]). */
    @get:Rule
    val pair = PairRun()

    private val check = PairCheck()

    @After
    fun putBack() = runBlocking<Unit> { check.putBack() }

    @Test
    fun theForceStopHoldsTheQueueWithNoHostUntilResumeThenBothGoOn() = runBlocking<Unit> {
        PairRecord.record("check")
        val held = check.held()
        assertEquals("no host start after the force stop", emptyList<PairRecord.Traced>(), check.starts(held.at))

        check.resume(listOf(held.first.id, held.second.id))
        val reopen = check.ownReopens(held.first.id).firstOrNull() ?: throw AssertionError("the first never reopened after Resume")
        assertEquals("the first goes on from where it stopped", held.first.safeOffset, reopen["offset"].toLong())
        check.assertWhole(listOf(held.first.id, held.second.id))
    }
}
