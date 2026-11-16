package io.skjaere.usenet.app.download

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.container
import io.skjaere.usenet.data.DownloadState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The second half of the SeasonPackKill pair (L367, L394, L431): after [StartSeasonPackKill], the app's open and the
 * runner's kill of the app's process (`kill -9`) as it wrote the season's first episode from its pack's copy, the
 * user-initiated job came back by itself (a host start by the job after the kill, the app not opened again) and the first
 * episode reopened at the safe offset recorded before the kill, then done. The second's turn came after it and took the
 * pack from the same copy: its one try the pack's, with no grab, as no indexer is set up and the pack's never was. Both
 * Done, each file whole and its own episode's, their search facts gone with Done (L442); the copy gone after the pair.
 *
 * Gone after the pair only: this process's start reconciles, which sweeps a copy no row still to finish holds, before the
 * check runs, so it can't tell the host's own sweep at the second's end from that one. DownloadRunnerTest and
 * DownloadSeasonEndToEndDeviceTest show the host's sweep, in one process.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CheckSeasonPackKill {
    /** Only where its run names it, on a phone ([PairRun]). */
    @get:Rule
    val pair = PairRun()

    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container

    /** The first episode's: the kill came as it was written. */
    private val check = PairCheck(PairFixture.PACK_E07)

    @After
    fun putBack() = runBlocking<Unit> { check.putBack() }

    @Test
    fun theFirstGoesOnFromItsRecordAndTheSecondComesFromThePacksCopy() = runBlocking<Unit> {
        PairRecord.record("check", SeasonPackSeed.fixtures)
        val (first, second) = check.rows(2)
        assertEquals("the season's two, in their order", SeasonPackSeed.fixtures.map { it.target.watchKey }, listOf(first.watchKey, second.watchKey))
        assertEquals("both done (${first.reason}; ${second.reason})", listOf(DownloadState.Done, DownloadState.Done), listOf(first.state, second.state))

        val reconciled = check.reconciled(first.id)
        assertEquals("the kill left the first for its host: ${reconciled.fields}", DownloadTrace.Decision.LeftForHost.word, reconciled["decision"])
        val killed = check.exitAt(reconciled)
        val start = check.starts(killed).firstOrNull() ?: throw AssertionError("no host start after the kill")
        assertEquals("the job's start: ${start.fields}", listOf("job", "job"), listOf(start["host"], start["cause"]))
        assertEquals("JobScheduler's own run, the app not opened again", emptyList<PairRecord.Traced>(), check.schedules(killed))
        val recorded = check.recorded(first.id, killed)
        val reopen = check.reopens(killed, first.id).firstOrNull() ?: throw AssertionError("no reopen of the first after the kill")
        assertEquals("the first's reopen after the kill at the recorded safe offset", recorded, reopen["offset"].toLong())
        val firstDone = check.done(first.id, reopen.at)

        // One at a time, in the season's order: the second's turn came once the first was done, its file from its first byte.
        val secondLines = check.linesOf(second.id)
        assertTrue("the second's lines after the first's done: ${secondLines.map { it.event to it.at }}", secondLines.isNotEmpty() && secondLines.all { it.at >= firstDone.at })
        assertEquals("the second written from its first byte", "0", check.reopens(firstDone.at, second.id).firstOrNull()?.get("offset"))
        check.done(second.id, firstDone.at)

        // Each from the copy: its one try the pack's, and no indexer a grab of it could go to, then or now.
        assertEquals("each episode's tries", listOf(listOf(SeasonPackSeed.key), listOf(SeasonPackSeed.key)), listOf(first.tried, second.tried))
        val indexers = container.indexerConfigs().size
        assertTrue("$indexers indexer(s) set up, where a grab could have gone", indexers == 0 && container.settings.indexer(PairSeed.INDEXER_ID) == null)
        check.assertWhole(listOf(first.id), PairFixture.PACK_E07)
        check.assertWhole(listOf(second.id), PairFixture.PACK_E08)
        assertEquals("their search facts gone with Done (L442)", listOf(null, null), listOf(first.search, second.search))

        // After the pair: whoever swept it, the host at the second's end or this process's reconcile at its start.
        val packs = container.downloadStorage.packs
        assertNull("the pack's copy gone after the pair", packs.find(SeasonPackSeed.key))
        assertFalse("the copy's folder gone after the pair", packs.folder(SeasonPackSeed.key).exists())
    }
}
