package io.skjaere.usenet.app.download

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The second half of the DownloadUpdate pair (L359, L367, L389): after [StartDownloadUpdate], the app's open and the
 * runner's install of the app over itself (`install -r`) as it downloaded, nothing is lost, whichever way the platform
 * went on, and the record says which:
 * - `went-on`: the job ran again by itself, the app not opened again (no schedule of the app's after the update);
 * - `waited-for-the-app`: it waited until the app was opened again (Android 14 keeps the persisted job unrun until
 *   then): the app's open scheduled after the update, though its line may come just after the start, as the job runs
 *   once the app's process is up, before its activity;
 * - `stopped`: reconcile held it as the user's stop ("Stopped by Android"); the user's Resume lets it go on.
 *
 * An update's exit (REASON_PACKAGE_UPDATED, 16) is never read as the user's; the first reopen after it is at the offset
 * recorded before it; done; the file whole. The update's time is the exit the reconcile line names.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CheckDownloadUpdate {
    /** Only where its run names it, on a phone ([PairRun]). */
    @get:Rule
    val pair = PairRun()

    private val check = PairCheck()

    @After
    fun putBack() = runBlocking<Unit> { check.putBack() }

    @Test
    fun anUpdateLosesNothingWhicheverWayItGoesOn() = runBlocking<Unit> {
        PairRecord.record("check")
        val row = check.rows(1).single()
        val reconciled = check.reconciled(row.id)
        val exit = reconciled["exit"]
        if (exit == "$PACKAGE_UPDATED") {
            assertEquals("an update's exit isn't the user's: ${reconciled.fields}", DownloadTrace.Decision.LeftForHost.word, reconciled["decision"])
        }
        val updated = check.exitAt(reconciled)
        val recorded = check.recorded(row.id, updated)

        val outcome = if (reconciled["decision"] == DownloadTrace.Decision.Stopped.word) {
            check.resume(listOf(row.id))
            val reopen = check.ownReopens(row.id).firstOrNull() ?: throw AssertionError("never reopened after Resume")
            assertEquals("the first reopen after the update at the recorded safe offset", recorded, reopen["offset"].toLong())
            "stopped"
        } else {
            assertTrue("a host start after the update", check.starts(updated).isNotEmpty())
            val reopen = check.reopens(updated, row.id).firstOrNull() ?: throw AssertionError("no reopen after the update")
            assertEquals("the first reopen after the update at the recorded safe offset", recorded, reopen["offset"].toLong())
            check.done(row.id, reopen.at)
            if (check.schedules(updated).isEmpty()) "went-on" else "waited-for-the-app"
        }
        check.outcome("update=$outcome exit=$exit")
        check.assertWhole(listOf(row.id))
    }

    private companion object {
        /** ApplicationExitInfo.REASON_PACKAGE_UPDATED, Android 14's. */
        const val PACKAGE_UPDATED = 16
    }
}
