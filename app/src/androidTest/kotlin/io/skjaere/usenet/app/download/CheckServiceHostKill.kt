package io.skjaere.usenet.app.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The second half of the ServiceHostKill pair (L334, L336, L389): after [StartServiceHostKill], the app's open and the
 * runner's kill of the app's process (`kill -9`) as the service downloaded under the debug override, the service came
 * back: a host start after the kill, by Android's START_STICKY restart or the watchdog, and the record says which;
 * done; the file whole. The kill's time is the exit the reconcile line names.
 */
@RunWith(AndroidJUnit4::class)
class CheckServiceHostKill {
    /** Only where its run names it, on a phone ([PairRun]). */
    @get:Rule
    val pair = PairRun()

    private val check = PairCheck()

    @After
    fun putBack() = runBlocking<Unit> { check.putBack() }

    @Test
    fun theServiceComesBackAfterAKillAndItIsDone() = runBlocking<Unit> {
        PairRecord.record("check")
        val row = check.rows(1).single()
        val reconciled = check.reconciled(row.id)
        assertEquals("the kill left the row for its host: ${reconciled.fields}", DownloadTrace.Decision.LeftForHost.word, reconciled["decision"])
        val killed = check.exitAt(reconciled)

        val start = check.starts(killed).firstOrNull() ?: throw AssertionError("no host start after the kill")
        val restarts = listOf(DownloadTrace.StartCause.StickyRestart, DownloadTrace.StartCause.Watchdog).map { it.word }
        assertEquals("the service's start: ${start.fields}", "service", start["host"])
        assertTrue("by START_STICKY or the watchdog: ${start.fields}", start["cause"] in restarts)
        check.outcome("service-host-kill=${start["cause"]}")
        check.done(row.id, start.at)
        check.assertWhole(listOf(row.id))
    }
}
