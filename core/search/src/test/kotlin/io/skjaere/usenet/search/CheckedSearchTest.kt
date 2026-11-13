package io.skjaere.usenet.search

import io.skjaere.usenet.newznab.IndexerCaps
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.IndexerKind
import io.skjaere.usenet.newznab.IndexerOutcome
import io.skjaere.usenet.newznab.NewznabError
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.ReleaseSearch
import io.skjaere.usenet.newznab.SearchPage
import io.skjaere.usenet.newznab.SearchQuery
import io.skjaere.usenet.releases.Availability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * L199 on a virtual clock: a Usenet indexer's answer passes at once; a torrent indexer's answers that it is checking its
 * torrents, which then come as their checks settle, each once, the rest unchecked after 10 s.
 */
class CheckedSearchTest {
    private val usenet = IndexerConfig("a", "Indexer A", "https://a.invalid/api", "k")
    private val prowlarr = IndexerConfig("p", "Home Prowlarr", "https://p.invalid/api", "k", kind = IndexerKind.Torznab)
    private val quarry = IndexerConfig("q", "Quarry", "https://q.invalid/api", "k", kind = IndexerKind.Torznab, useWithService = false)
    private val query = SearchQuery.Text("harbor lights")

    private fun item(indexer: IndexerConfig, title: String, hash: String? = null) = NewznabItem(
        indexerId = indexer.id, guid = hash?.let { "btih:$it" } ?: title, title = title, sizeBytes = 2_000_000_000, publishedAt = null, postedAt = null,
        categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
        tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        infoHash = hash,
    )

    private fun page(vararg items: NewznabItem) = SearchPage(items.toList(), 0, null, null)

    /** Every indexer answering after its own delay, in ms. */
    private class FakeSearch(private val answers: List<Pair<Long, IndexerOutcome>>) : ReleaseSearch {
        override fun search(indexers: List<IndexerConfig>, fresh: Boolean, plan: (IndexerCaps) -> SearchQuery?): Flow<IndexerOutcome> = channelFlow {
            answers.forEach { (after, outcome) -> launch { delay(after); send(outcome) } }
        }
    }

    /** An outcome as the test saw it, at the virtual time it came. */
    private data class Seen(val at: Long, val outcome: IndexerOutcome)

    private fun TestScope.collect(search: ReleaseSearch): List<Seen> {
        val seen = mutableListOf<Seen>()
        launch { search.search(listOf(usenet, prowlarr, quarry)) { query }.collect { seen += Seen(testScheduler.currentTime, it) } }
        testScheduler.advanceUntilIdle()
        return seen
    }

    private fun List<NewznabItem>.settled(availability: Availability) = map { it.copy(availability = availability) }

    private val cached = Availability.Cached(listOf("torbox-1"))

    @Test
    fun `a usenet indexer's answer passes at once, as it came`() = runTest {
        val results = IndexerOutcome.Results(usenet, page(item(usenet, "Harbor.Lights.S01E02.1080p.WEB-DL.H.264-GOOD")), query)
        val failed = IndexerOutcome.Failed(quarry, NewznabError.ApiLimitReached("API limit reached"))
        var asked = 0
        val seen = collect(CheckedSearch(FakeSearch(listOf(0L to results, 0L to failed))) { _, items -> asked++; flow { emit(items) } })
        assertEquals(listOf(Seen(0, results), Seen(0, failed)), seen)
        assertEquals(0, asked, "nothing to check")
    }

    @Test
    fun `a torrent indexer's answer says it is checking first, then its torrents as they settle, each once`() = runTest {
        val remembered = item(prowlarr, "Harbor.Lights.S01E02.1080p.WEB-DL.H.264-GOOD", "1111111111111111111111111111111111111111")
        val first = item(prowlarr, "Harbor.Lights.S01E01.PROPER.1080p.WEB-DL.H.264-TIDE", "2222222222222222222222222222222222222222")
        val second = item(prowlarr, "Harbor.Lights.S01E02.720p.HDTV.x264-DRIFT", "3333333333333333333333333333333333333333")
        val found = page(remembered, first, second)
        val seen = collect(
            CheckedSearch(FakeSearch(listOf(0L to IndexerOutcome.Results(prowlarr, found, query)))) { _, items ->
                flow {
                    // What the memory answers at once; each account's batch as it returns, one item sent twice by mistake.
                    emit(items.take(1).settled(cached))
                    delay(2_000)
                    emit(items.drop(1).take(1).settled(cached))
                    delay(1_000)
                    emit(items.drop(1).settled(Availability.NotCached))
                }
            },
        )
        assertEquals(
            listOf(
                Seen(0, IndexerOutcome.Checking(prowlarr, query, found, waiting = 3)),
                Seen(0, IndexerOutcome.Checked(prowlarr, listOf(remembered).settled(cached), waiting = 2)),
                Seen(2_000, IndexerOutcome.Checked(prowlarr, listOf(first).settled(cached), waiting = 1)),
                Seen(3_000, IndexerOutcome.Checked(prowlarr, listOf(second).settled(Availability.NotCached), waiting = 0)),
            ),
            seen,
        )
    }

    @Test
    fun `a slow check holds only its own torrents`() = runTest {
        val torrent = item(prowlarr, "Harbor.Lights.S01E02.1080p.WEB-DL.H.264-GOOD", "1111111111111111111111111111111111111111")
        val off = item(quarry, "Harbor.Lights.S01E02.2160p.WEB.H265-KESTREL", "4444444444444444444444444444444444444444")
        val nzb = item(usenet, "Harbor.Lights.S01E02.1080p.WEB-DL.H.264-GOOD")
        val seen = collect(
            CheckedSearch(
                FakeSearch(
                    listOf(
                        0L to IndexerOutcome.Results(prowlarr, page(torrent), query),
                        100L to IndexerOutcome.Results(quarry, page(off), query),
                        1_000L to IndexerOutcome.Results(usenet, page(nzb), query),
                    ),
                ),
            ) { indexer, items ->
                flow {
                    // Quarry's switch is off: its torrents settle at once, sent nowhere; Home Prowlarr's check takes 5 s.
                    if (!indexer.useWithService) emit(items.settled(Availability.NotForService)) else { delay(5_000); emit(items.settled(cached)) }
                }
            },
        )
        val times = seen.map { it.at to it.outcome::class.simpleName + ":" + it.outcome.indexer.id }
        assertEquals(
            listOf(0L to "Checking:p", 100L to "Checking:q", 100L to "Checked:q", 1_000L to "Results:a", 5_000L to "Checked:p"),
            times,
        )
    }

    @Test
    fun `a check that runs past 10 s gives the rest as not checked, and stops`() = runTest {
        val quick = item(prowlarr, "Harbor.Lights.S01E02.1080p.WEB-DL.H.264-GOOD", "1111111111111111111111111111111111111111")
        val slow = item(prowlarr, "Harbor.Lights.S01E01.PROPER.1080p.WEB-DL.H.264-TIDE", "2222222222222222222222222222222222222222")
        var stopped = false
        val seen = collect(
            CheckedSearch(FakeSearch(listOf(0L to IndexerOutcome.Results(prowlarr, page(quick, slow), query)))) { _, items ->
                flow {
                    delay(1_000)
                    emit(items.take(1).settled(cached))
                    try {
                        awaitCancellation()
                    } catch (e: CancellationException) {
                        stopped = true
                        throw e
                    }
                }
            },
        )
        assertEquals(
            listOf(
                Seen(0, IndexerOutcome.Checking(prowlarr, query, page(quick, slow), waiting = 2)),
                Seen(1_000, IndexerOutcome.Checked(prowlarr, listOf(quick).settled(cached), waiting = 1)),
                Seen(10_000, IndexerOutcome.Checked(prowlarr, listOf(slow).settled(Availability.NotChecked), waiting = 0)),
            ),
            seen,
        )
        assertTrue(stopped, "the check went on after its 10 s")
    }

    @Test
    fun `a check that fails gives its torrents as not checked, and a torrent indexer with nothing found passes as it came`() = runTest {
        val torrent = item(prowlarr, "Harbor.Lights.S01E02.1080p.WEB-DL.H.264-GOOD", "1111111111111111111111111111111111111111")
        val empty = IndexerOutcome.Results(quarry, page(), query)
        val seen = collect(
            CheckedSearch(FakeSearch(listOf(0L to IndexerOutcome.Results(prowlarr, page(torrent), query), 0L to empty))) { _, _ ->
                flow { delay(500); throw IllegalStateException("the check broke") }
            },
        )
        assertEquals(
            listOf(
                Seen(0, IndexerOutcome.Checking(prowlarr, query, page(torrent), waiting = 1)),
                Seen(0, empty),
                Seen(500, IndexerOutcome.Checked(prowlarr, listOf(torrent).settled(Availability.NotChecked), waiting = 0)),
            ),
            seen,
        )
    }
}
