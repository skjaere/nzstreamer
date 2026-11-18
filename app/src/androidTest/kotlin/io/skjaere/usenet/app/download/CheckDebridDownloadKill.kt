package io.skjaere.usenet.app.download

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.debrid.DebridAdds
import io.skjaere.usenet.app.debrid.DebridFakes
import io.skjaere.usenet.app.everyStep
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.releases.ReleaseFacts
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The second half of the DebridDownloadKill pair (L464, L476): after [StartDebridDownloadKill], the app's open and the
 * runner's kill of the app's process (`kill -9`) as it wrote the torrent's file from the debrid fake's CDN, the
 * user-initiated job came back by itself (a host start by the job after the kill, the app not opened again), its process
 * pointed at the fake by `.debrid` alone, and the writer opened again at the safe offset recorded before the kill, with a
 * fresh link and one body from that byte, never two at once; done; the file whole, the fake's sample byte for byte.
 *
 * And its add (L421): the process the job came back in held it by the download's lease before its first sweep, which a
 * grace after its start found it held and removed nothing; nothing was removed while the download ran or at Done. Once
 * this process's own sweep runs a grace after the row's completedAt, it is removed from the account, once.
 *
 * It reports M-reopen (the account's links and the writer's opens) through [PairRecord]'s lines. Its end puts back what
 * the pair left ([PairCheck.putBack], `.debrid` among the seams' files), signs out of the fake's account, and gives the
 * fake back, reset.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CheckDebridDownloadKill {
    /** Only where its run names it, on a phone ([PairRun]). */
    @get:Rule
    val pair = PairRun()

    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container

    @After
    fun putBack() = runBlocking<Unit> {
        everyStep(
            { PairCheck().putBack() },
            {
                container.settings.settings.first().activeServices.filter { it.accountName == DebridFakes.TORBOX_ACCOUNT }
                    .forEach { container.settings.signOut(it.id) }
            },
            { DebridFakes.control("reset") },
            { DebridFakes.giveBack(DebridKillSeed.HOLDER) },
        )
    }

    @Test
    fun theJobComesBackByItselfGoesOnFromItsRecordWithAFreshLinkAndItsAddHeld() = runBlocking<Unit> {
        val fixture = DebridKillSeed.fixture(DebridKillSeed.media())
        val check = PairCheck(fixture)
        PairRecord.record("check", listOf(fixture))
        // What the step's processes left at the fake, before this process's own sweep can remove anything.
        val left = DebridKillSeed.counts()
        val row = check.rows(1, services = 1).single()
        val account = container.settings.settings.first().activeServices.single()
        assertEquals("the fake's account, Start's, still the only one", DebridFakes.TORBOX_ACCOUNT, account.accountName)
        assertEquals("done (${row.reason})", DownloadState.Done, row.state)
        assertEquals("the torrent's download", ReleaseFacts.TORRENT, row.release?.source)

        val reconciled = check.reconciled(row.id)
        assertEquals("the kill left the row for its host: ${reconciled.fields}", DownloadTrace.Decision.LeftForHost.word, reconciled["decision"])
        val killed = check.exitAt(reconciled)
        val start = check.starts(killed).firstOrNull() ?: throw AssertionError("no host start after the kill")
        assertEquals("the job's start: ${start.fields}", listOf("job", "job"), listOf(start["host"], start["cause"]))
        assertEquals("JobScheduler's own run, the app not opened again", emptyList<PairRecord.Traced>(), check.schedules(killed))
        val recorded = check.recorded(row.id, killed)
        val reopen = check.reopens(killed, row.id).firstOrNull() ?: throw AssertionError("no reopen after the kill")
        assertEquals("the first reopen after the kill at the recorded safe offset", recorded, reopen["offset"].toLong())
        val done = check.done(row.id, reopen.at)
        check.assertWhole(listOf(row.id))

        // The start's sweep of the process the job came back in, a grace on: the download's add held by its lease, nothing
        // removed; and no sweep removed anything while it ran.
        val sweeps = check.sweeps(killed)
        assertTrue("a sweep after the restart that found the add held: ${sweeps.map { it.fields }}", sweeps.any { it["held"] == "1" && it["removed"] == "0" })
        assertEquals("removed while it ran", emptyList<PairRecord.Traced>(), sweeps.filter { it.at <= done.at && it["removed"] != "0" })

        // The fake's CDN: a body from the first open and one from the recorded offset, never two at once; nothing removed.
        val from = left.getJSONObject("cdnFrom").let { json -> json.keys().asSequence().associate { it.toLong() to json.getInt(it) } }
        assertEquals("the CDN's bodies, by the byte each began at", mapOf(0L to 1, recorded to 1), from)
        assertEquals("one body open at once", 1, left.getInt("cdnOpenMax"))
        assertEquals("nothing removed from the account by Done", 0, left.getInt("removals"))
        assertEquals("added once, by Start's Resolve", 1, left.getInt("adds"))
        // M-reopen: one link per open, Start's Resolve's (Opening's) besides.
        val opens = check.linesOf(row.id).filter { it.event == "reopen" }
        val links = left.getJSONObject("calls").optInt(REQUESTDL)
        PairRecord.say("measure M-reopen requestdl=$links opens=${opens.size} at=${opens.joinToString(",") { it["offset"] }} cdn-bodies=${from.values.sum()} adds=${left.getInt("adds")}")
        assertEquals("one link for each of the writer's opens, and Resolve's", opens.size + 1, links)

        // A grace after Done, this process's own sweep removes the add from the account, once.
        val completedAt = row.completedAt ?: throw AssertionError("done with no completedAt")
        val due = completedAt + DebridAdds.GRACE.toMillis() + SLACK_MILLIS
        while (System.currentTimeMillis() < due) {
            PairRecord.say("waiting ${(due - System.currentTimeMillis()) / 1000} s for the add's grace after Done")
            delay(minOf(SAY_EVERY_MILLIS, (due - System.currentTimeMillis()).coerceAtLeast(1)))
        }
        container.debridAdds.sweep()
        val after = DebridKillSeed.counts()
        assertEquals("removed once, a grace after Done", 1, after.getInt("removals"))
        assertTrue("this process's sweep says so: ${check.ownSweeps().map { it.fields }}", check.ownSweeps().any { it["removed"] == "1" })
        PairRecord.say("fake removals=${after.getInt("removals")} ${(System.currentTimeMillis() - completedAt) / 1000} s after done")
    }

    private companion object {
        /** TorBox's call for a file's link, as the debrid fake counts it. */
        const val REQUESTDL = "torbox requestdl"

        /** Past the grace, for the clocks. */
        const val SLACK_MILLIS = 5_000L

        /** A line now and then while it waits, so the runner's watchdog hears the phase. */
        const val SAY_EVERY_MILLIS = 30_000L
    }
}
