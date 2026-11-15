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
 * The second half of the DownloadKill pair (L367, L389): after [StartDownloadKill], the app's open and the runner's
 * kill of the app's process (`kill -9`) as it downloaded, the user-initiated job came back by itself: a host start by
 * the job after the kill, MainActivity not relaunched (no schedule of the app's after the kill: its open's would come
 * just after a start, as the job runs once the process is up); the first reopen at the safe offset recorded before the
 * kill, not 0; done; the file whole. The kill's time is the exit the reconcile line names.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CheckDownloadKill {
    /** Only where its run names it, on a phone ([PairRun]). */
    @get:Rule
    val pair = PairRun()

    private val check = PairCheck()

    @After
    fun putBack() = runBlocking<Unit> { check.putBack() }

    @Test
    fun theJobComesBackByItselfAndGoesOnFromItsRecord() = runBlocking<Unit> {
        PairRecord.record("check")
        val row = check.rows(1).single()
        val reconciled = check.reconciled(row.id)
        assertEquals("the kill left the row for its host: ${reconciled.fields}", DownloadTrace.Decision.LeftForHost.word, reconciled["decision"])
        val killed = check.exitAt(reconciled)

        val start = check.starts(killed).firstOrNull() ?: throw AssertionError("no host start after the kill")
        assertEquals("the job's start: ${start.fields}", listOf("job", "job"), listOf(start["host"], start["cause"]))
        assertEquals("JobScheduler's own run, the app not opened again", emptyList<PairRecord.Traced>(), check.schedules(killed))

        val recorded = check.recorded(row.id, killed)
        val reopen = check.reopens(killed, row.id).firstOrNull() ?: throw AssertionError("no reopen after the kill")
        assertEquals("the first reopen after the kill at the recorded safe offset", recorded, reopen["offset"].toLong())
        check.done(row.id, reopen.at)
        check.assertWhole(listOf(row.id))
    }
}
