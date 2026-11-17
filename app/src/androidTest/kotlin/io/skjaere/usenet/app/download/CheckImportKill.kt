package io.skjaere.usenet.app.download

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.play.IMPORTED
import io.skjaere.usenet.data.DownloadKind
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
 * The second half of the ImportKill pair (L476): after [StartImportKill], the app's open and the runner's kill of the
 * app's process (`kill -9`) as it wrote the first file of the NZB opened on Import, the user-initiated job came back by
 * itself (a host start by the job after the kill, the app not opened again) and the first file reopened from its own NZB
 * at the safe offset recorded before the kill, then done. The second's turn came after it, in the process the job came
 * back in, with no prepare held for the NZB there and no file of it in the NZB store: it took the NZB from the import's
 * copy (L423, L457), its one try Import's own release, with no grab, as no indexer is set up and Import's release never
 * names one. Both Done, each file whole and its own, under its release's watch key, `release:<the NZB's SHA-256>:<its
 * index>`, the key its plays save their place under; the copy, which would keep a password typed for the NZB, gone once
 * both were done (C11).
 *
 * Gone after the pair only: this process's start reconciles, which sweeps a copy no row still to finish or failed holds,
 * before the check runs, so it can't tell the host's own sweep at the second's end from that one.
 * DownloadResolverDeviceTest shows the host's sweep, in one process.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CheckImportKill {
    /** Only where its run names it, on a phone ([PairRun]). */
    @get:Rule
    val pair = PairRun()

    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container

    /** The first file's: the kill came as it was written. */
    private val check = PairCheck(ImportKillSeed.fixtures.first())

    @After
    fun putBack() = runBlocking<Unit> { check.putBack() }

    @Test
    fun theFirstGoesOnFromItsRecordAndTheSecondComesFromTheNzbsCopy() = runBlocking<Unit> {
        val fixtures = ImportKillSeed.fixtures
        PairRecord.record("check", fixtures)
        val (first, second) = check.rows(2)
        // The NZB's SHA-256 and each file's place in it, as Import lists them: the keys their plays saved under.
        val sha256 = DownloadFixtures.sha256(PairFixture.PACK_E07.nzbBytes())
        assertEquals("the NZB's two files, in their order, by their releases' keys", listOf("release:$sha256:0", "release:$sha256:1"), listOf(first.watchKey, second.watchKey))
        assertEquals("both files opened on Import", listOf(DownloadKind.Release, DownloadKind.Release), listOf(first.kind, second.kind))
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

        // One at a time, in the order they were queued: the second's turn came once the first was done, its file from its
        // first byte, in the process the job came back in.
        val secondLines = check.linesOf(second.id)
        assertTrue("the second's lines after the first's done: ${secondLines.map { it.event to it.at }}", secondLines.isNotEmpty() && secondLines.all { it.at >= firstDone.at })
        assertEquals("the second written from its first byte", "0", check.reopens(firstDone.at, second.id).firstOrNull()?.get("offset"))
        check.done(second.id, firstDone.at)

        // Each from the copy: its one try Import's own release, its release Import's, and no indexer a search or a grab
        // could go to, then or now.
        val key = ImportKillSeed.key
        assertEquals("each file's tries", listOf(listOf(key), listOf(key)), listOf(first.tried, second.tried))
        assertEquals("each file's release Import's own", listOf(IMPORTED, IMPORTED), listOf(first.release?.indexerId, second.release?.indexerId))
        val indexers = container.indexerConfigs().size
        assertTrue("$indexers indexer(s) set up, where a search or a grab could have gone", indexers == 0 && container.settings.indexer(IMPORTED) == null)
        check.assertWhole(listOf(first.id), fixtures[0])
        check.assertWhole(listOf(second.id), fixtures[1])

        // After the pair: whoever swept it, the host at the second's end or this process's reconcile at its start.
        val packs = container.downloadStorage.packs
        assertNull("the imported NZB's copy gone once both are done (C11)", packs.find(key))
        assertFalse("the copy's folder gone with it, its password too", packs.folder(key).exists())
    }
}
