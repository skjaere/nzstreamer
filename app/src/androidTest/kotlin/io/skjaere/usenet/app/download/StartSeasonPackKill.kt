package io.skjaere.usenet.app.download

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.play.Replay
import io.skjaere.usenet.app.search.ReleaseListMode
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.releases.ReleaseKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The first half of the SeasonPackKill pair (L367, L394, L431): two episodes of a season and their pack's copy, seeded as
 * [SeasonPackSeed] says, the user-initiated job their host; then it returns. The pairs' runner then opens the app as a
 * user does, waits for the first episode's transfer past a quarter, kills the app's process (`kill -9`), waits for a host
 * start after it, then done, then the trace quiet (the second episode written too), and runs [CheckSeasonPackKill].
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class StartSeasonPackKill {
    /** Only where its run names it, on a phone ([PairRun]). */
    @get:Rule
    val pair = PairRun()

    @Test
    fun seedsTwoEpisodesOfASeasonAndItsPacksCopy() = runBlocking<Unit> {
        SeasonPackSeed.seed()
    }
}

/**
 * What the SeasonPackKill pair seeds (L394, L431), in [PairSeed]'s frame (the downloads emptied, the mock alone, the
 * switches off, the debrid seeding held off, the writer at 1 MB a second):
 * - every Usenet indexer off and no Download size: a turn's search, should the pack fail, asks no indexer, and the pack
 *   leads whatever the debug build's settings were;
 * - Harbor Lights' S03E07 and S03E08 ([PairFixture.PACK_E07], [PairFixture.PACK_E08]) queued as the season's sheet queues
 *   them ([DownloadRunner.enqueueAll]): their search facts not yet searched, the season's pack ([PairFixture.PACK]) their
 *   one release and its share their need. Nothing is resolved here: the host's turn resolves each in the app's own
 *   process, its track probe reading the episode's video;
 * - the pack's NZB kept once for its season in `.packs/` ([DownloadNzbs]), leading, as Resolve keeps a pack it fetched,
 *   under the key of an indexer that isn't set up ([PairSeed.INDEXER_ID]): a grab of it would fail ("Indexer no longer set
 *   up"), so each episode can only come from the copy. Written after the rows, which hold it for their season.
 */
object SeasonPackSeed {
    /** The season's two episodes, in their order. */
    val fixtures = listOf(PairFixture.PACK_E07, PairFixture.PACK_E08)

    private const val SEASON = 3

    /** The pack's guid, at an indexer that isn't set up. */
    private const val GUID = "pair-pack"

    /** The pack's release key: its copy's, and each episode's one try. */
    val key: String = ReleaseKey.of(PairSeed.INDEXER_ID, GUID)

    /** The pack as each row lists it: one release, both files' size. */
    private val candidate = DownloadCandidate(PairSeed.INDEXER_ID, GUID, PairFixture.PACK, size = fixtures.sumOf { it.size })

    private val show: Int = requireNotNull(PairFixture.PACK_E07.target.showTmdbId)
    private val seasonEpisodes = PairFixture.PACK_E07.target.seasonEpisodes

    /** The copy as Resolve keeps a season pack it fetched (L432): leading, for the show's season. */
    private val meta = DownloadNzbs.Meta(key, PairFixture.PACK, candidate, show, SEASON, leads = true)

    /** What each episode needs while queued, as the season's sheet reckons it: the pack's share of one episode. */
    private val need: Long = ReleaseListMode.Download.need(
        Replay.candidate(RememberedRelease(candidate.indexerId, candidate.guid, candidate.title, fileIndex = 0))
            .let { it.copy(item = it.item.copy(sizeBytes = candidate.size)) },
        seasonEpisodes,
    )

    /** The season's search facts, not yet searched, as its sheet keeps them (L392). */
    private val facts = SeasonSearch(name = "Harbor Lights", tmdb = show, seasonEpisodes = seasonEpisodes)

    /** Seeds what the object says; the rows as seeded, in the queue's order. */
    suspend fun seed(): List<Download> {
        val nzb = PairFixture.PACK_E07.nzbBytes()
        return PairSeed.seed(
            fixtures, PairSeed.THROTTLE, serviceHost = false,
            queue = { container -> queue(container, nzb) },
            readBack = { container, rows -> readBack(container, rows, nzb) },
        )
    }

    private suspend fun queue(container: AppContainer, nzb: ByteArray): List<Download> {
        val settings = container.settings
        settings.indexers.first().filter { it.enabled }.forEach { settings.setIndexerEnabled(it.id, false) }
        settings.setDownloadSizeCap(null)
        val rows = container.downloads.enqueueAll(fixtures.map { DownloadRunner.QueueItem(it.target, listOf(candidate), need, facts) })
        container.downloadStorage.packs.write(meta, nzb, password = null)
        return rows
    }

    /** What didn't take of the season's rows and its pack's copy. */
    private suspend fun readBack(container: AppContainer, rows: List<Download>, nzb: ByteArray): List<String> {
        val problems = mutableListOf<String>()
        fun expect(ok: Boolean, what: () -> String) {
            if (!ok) problems += what()
        }
        val storage = container.downloadStorage
        expect(rows.map { it.watchKey } == fixtures.map { it.target.watchKey }) { "${rows.size} downloads queued, not the season's two in their order" }
        container.data.downloads.all().first().forEach { row ->
            expect(row.state == DownloadState.Queued && row.reason == null) { "download ${row.id} is ${row.state} (${row.reason}), not Queued" }
            expect(row.candidates == listOf(candidate) && row.tried.isEmpty() && row.indexerSize == need) {
                "download ${row.id}'s releases aren't the pack alone, untried, at its share of $need bytes"
            }
            expect(DownloadColumns.search(row)?.searched == false && DownloadColumns.pick(row) == null) { "download ${row.id} isn't a season's download still to search" }
            expect(row.release == null && row.size == null && row.safeOffset == 0L && row.relativePath == null) { "download ${row.id} is resolved or written already" }
            expect(File(storage.bookkeeping(row.id), DownloadStorage.MARKER).isFile) { "download ${row.id} has no marker" }
            expect(storage.source(row.id) == null && storage.grab(row.id) == null) { "download ${row.id} keeps an NZB of its own" }
        }
        val copy = storage.packs.find(key)
        expect(copy != null && copy.meta == meta && copy.source.nzb.contentEquals(nzb) && copy.source.password == null) {
            "the season's copy isn't the pack's NZB, with no password, leading for its season"
        }
        expect(storage.packs.forSeason(show, SEASON).map { it.meta.key } == listOf(key)) { "the season has other copies than the pack's" }
        val asked = container.indexerConfigs().size
        expect(asked == 0 && container.settings.indexer(PairSeed.INDEXER_ID) == null) { "$asked indexer(s) a search or a grab could ask" }
        expect(container.settings.downloads.first().sizeCapGbPerHour == null) { "a Download size is set" }
        return problems
    }
}
