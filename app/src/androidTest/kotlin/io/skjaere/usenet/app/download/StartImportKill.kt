package io.skjaere.usenet.app.download

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.ImportUi
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.importNzb
import io.skjaere.usenet.app.play.IMPORTED
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.releases.ReleaseKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The first half of the ImportKill pair (L476): two files of one NZB opened on Import, queued for download as
 * [ImportKillSeed] says, the user-initiated job their host; then it returns. The pairs' runner then opens the app as a
 * user does, waits for the first file's transfer past a quarter, kills the app's process (`kill -9`), waits for a host
 * start after it, then done, then the trace quiet (the second file written too), and runs [CheckImportKill].
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class StartImportKill {
    /** Only where its run names it, on a phone ([PairRun]). */
    @get:Rule
    val pair = PairRun()

    @Test
    fun seedsTwoFilesOfAnNzbOpenedOnImport() = runBlocking<Unit> {
        ImportKillSeed.seed()
    }
}

/**
 * What the ImportKill pair seeds (L423, L476), in [PairSeed]'s frame (the downloads emptied, the mock alone, the switches
 * off, the debrid seeding held off, the writer at 1 MB a second):
 * - every Usenet indexer off: nothing is searched for a file opened on Import, and no indexer is there that a grab could
 *   go to;
 * - the season pack's NZB ([PairFixture.PACK]: Harbor Lights' S03E07 and S03E08, the SeasonPackKill pair's) opened as
 *   Import opens a file picked under that name, with no password ([importNzb]): prepared on the mock and kept in the NZB
 *   store;
 * - both its videos queued as Import's Download sheet queues each, in the order Import lists them
 *   ([DownloadRequest.File.of], [DownloadRunner.enqueueImport]): Release rows ([fixtures]), each with Import's own release
 *   of the NZB ([key]) as its one candidate at the file's exact size and the file picked by its name and size, and the
 *   NZB kept once in `.packs/` for both. Nothing is resolved here: the host's turn resolves each in the app's own process
 *   from that copy, its track probe reading the video;
 * - then Stored data cleared ([io.skjaere.usenet.core.PreparedNzbStore.clearStored]): the store keeps no file of the NZB,
 *   so the app's next processes can have it from the copy alone.
 */
object ImportKillSeed {
    /** Import's title for the NZB: the picked file's name without `.nzb`, here the release's own. */
    const val TITLE = PairFixture.PACK

    /** The NZB's bytes, as Import reads them from the file the user picked. */
    private val nzb: ByteArray by lazy { PairFixture.PACK_E07.nzbBytes() }

    /** The NZB's SHA-256, in lowercase hex: what Import names it by, and what its files' watch keys carry. */
    val nzbId: String by lazy { DownloadFixtures.sha256(nzb) }

    /** Import's own release of the NZB: each row's one candidate and try, and its copy's key. */
    val key: String by lazy { ReleaseKey.of(IMPORTED, nzbId) }

    /**
     * The NZB's two videos as Release rows download them, at their places in the NZB (S03E07 first, as it lists them):
     * each a download of its file of the NZB under Import's title, saving its place under `release:<nzbId>:<index>`.
     */
    val fixtures: List<PairFixture> by lazy {
        listOf(PairFixture.PACK_E07, PairFixture.PACK_E08).mapIndexed { index, episode ->
            val file = requireNotNull(episode.file) { "a file of the pack" }
            PairFixture(episode.nzb, episode.sha256Asset, episode.release, episode.size, DownloadTarget.release(nzbId, index, file, TITLE), file)
        }
    }

    /** Opened on Import and prepared there: the pack's two files and the first articles of each, from the mock. */
    private const val PREPARE_MILLIS = 60_000L

    /** Seeds what the object says; the rows as seeded, in the queue's order. */
    suspend fun seed(): List<Download> = PairSeed.seed(
        fixtures, PairSeed.THROTTLE, serviceHost = false,
        queue = { container -> queue(container) },
        readBack = { container, rows -> readBack(container, rows) },
    )

    private suspend fun queue(container: AppContainer): List<Download> {
        val settings = container.settings
        settings.indexers.first().filter { it.enabled }.forEach { settings.setIndexerEnabled(it.id, false) }
        // Import's open of the picked file, on the provider saved now (the mock alone): prepared, and kept in the NZB store.
        val opened = engineSetup("the NZB opened as Import opens it", PREPARE_MILLIS) { importNzb(container.preparer, container.store, nzb, TITLE, password = null) }
        val ready = opened as? ImportUi.Ready ?: throw AssertionError("Import didn't open the NZB: ${opened::class.simpleName}")
        // Each video's Download, as its sheet queues it once the file is Ready.
        val rows = fixtures.map { fixture ->
            val file = ready.nzb.files.firstOrNull { it.name == fixture.file } ?: throw AssertionError("Import lists no ${fixture.file}")
            val request = DownloadRequest.File.of(ready.nzb, file, nzb) ?: throw AssertionError("Import offers no Download of ${fixture.file}")
            container.downloads.enqueueImport(request.target, request.candidate, request.pick, request.source)
                ?: throw AssertionError("not queued: the runner runs nothing here")
        }
        // Settings' Stored data cleared: the NZB store keeps no file of the NZB from here on.
        container.store.clearStored()
        return rows
    }

    /** What didn't take of the NZB's rows, its copy and the store. */
    private suspend fun readBack(container: AppContainer, rows: List<Download>): List<String> {
        val problems = mutableListOf<String>()
        fun expect(ok: Boolean, what: () -> String) {
            if (!ok) problems += what()
        }
        val storage = container.downloadStorage
        expect(rows.map { it.watchKey } == fixtures.map { it.target.watchKey }) { "${rows.size} downloads queued, not the NZB's two files at their places in it, in their order" }
        container.data.downloads.all().first().forEach { row ->
            val fixture = fixtures.firstOrNull { it.target.watchKey == row.watchKey } ?: return@forEach
            val index = requireNotNull(fixture.target.fileIndex)
            expect(row.state == DownloadState.Queued && row.reason == null) { "download ${row.id} is ${row.state} (${row.reason}), not Queued" }
            expect(row.kind == DownloadKind.Release && row.title == fixture.file && row.subtitle == TITLE) { "download ${row.id} isn't a file of the NZB opened on Import" }
            expect(row.candidates == listOf(DownloadCandidate(IMPORTED, nzbId, TITLE, size = fixture.size)) && row.tried.isEmpty() && row.indexerSize == fixture.size) {
                "download ${row.id}'s release isn't Import's own, untried, at the file's exact size"
            }
            expect(DownloadColumns.pick(row) == DownloadPick(index, requireNotNull(fixture.file), fixture.size, nzbId)) { "download ${row.id}'s file isn't picked by its name and size" }
            expect(row.release == null && row.size == null && row.safeOffset == 0L && row.relativePath == null) { "download ${row.id} is resolved or written already" }
            expect(File(storage.bookkeeping(row.id), DownloadStorage.MARKER).isFile) { "download ${row.id} has no marker" }
            expect(storage.source(row.id) == null && storage.grab(row.id) == null) { "download ${row.id} keeps an NZB of its own" }
        }
        val copy = storage.packs.find(key)
        expect(
            copy != null && copy.meta.nzb == nzbId && copy.meta.show == null && copy.meta.season == null && !copy.meta.leads &&
                copy.source?.nzb?.contentEquals(nzb) == true && copy.source?.password == null,
        ) { "the NZB's copy isn't Import's, with no password, kept for its downloads" }
        expect(container.store.stored(nzbId) == null) { "the NZB store still keeps a file of the NZB" }
        val asked = container.indexerConfigs().size
        expect(asked == 0 && container.settings.indexer(IMPORTED) == null) { "$asked indexer(s) a search or a grab could ask" }
        return problems
    }
}
