package app.nzstreamer.plugin.sportarr.match

import app.nzstreamer.plugin.sportarr.family.Catalogue
import app.nzstreamer.plugin.sportarr.family.CatalogueAt
import app.nzstreamer.plugin.sportarr.family.Matchers
import io.skjaere.usenet.mockseed.FakeIndexerServer
import io.skjaere.usenet.mockseed.SportsVerdict
import io.skjaere.usenet.plugin.Found
import io.skjaere.usenet.plugin.IndexerLine
import io.skjaere.usenet.plugin.ItemReleases
import io.skjaere.usenet.plugin.PluginSearch
import io.skjaere.usenet.plugin.ReleaseInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals

/**
 * The plugin's searches against the sports indexer (L647-L651, L655's `reached`): mock-seed's fake indexer over the
 * catalogue's names on a loopback port, matching whole words and a word asked twice standing twice, as the device tests'
 * sports indexer does. For each part the catalogue judges releases of, the runner searches it as on a play, stage 1 then
 * stage 2 when stage 1 accepted nothing, each a text search (t=search&q=) read back from the indexer's RSS by
 * [IndexerSearch]. What those searches find is exactly the rows marked reached for that part, never one marked unreached (the
 * race's F1-led and running-number names, and the film named like a side, which no query's words reach), and the runner
 * gives those rows' verdicts, the sizes and posted times read from the RSS among what they rest on.
 */
class QueriesAgainstFakeIndexerTest {
    private val indexer = FakeIndexerServer(emptyList(), KEY, port = 0, bind = InetAddress.getLoopbackAddress(), repeatedWords = true)

    @AfterEach
    fun stop() = indexer.close()

    /** The search that finds what the indexer's own word matching finds, with no request: every day of a year runs fast. */
    private inner class MatchingSearch(private val catalogue: CatalogueAt) : PluginSearch {
        val found: MutableSet<String> = ConcurrentHashMap.newKeySet()

        override fun search(words: String, runtimeMinutes: Int?, fresh: Boolean): Flow<Found> = flow {
            val releases = catalogue.releases.filter { indexer.finds(words, it.name) }
                .map { ReleaseInfo(it.name, it.sizeBytes, it.postedAt, INDEXER, playable = true, whyNot = null, handle = TestHandle(it.fake().guid)) }
            found += releases.map { it.handle.toString() }
            emit(Found(listOf(IndexerLine(INDEXER, IndexerLine.State.Found, releases.size, "${releases.size} found")), releases))
        }
    }

    /** One part the catalogue's rows judge: its event, its part's id, and its rows with the guid the indexer lists. */
    private class Judged(val event: Int, val part: String, val rows: List<Pair<String, SportsVerdict>>) {
        fun reached(verdict: SportsVerdict.Verdict? = null): List<Pair<String, SportsVerdict>> =
            rows.filter { (_, row) -> row.reached && (verdict == null || row.verdict == verdict) }
    }

    private fun judged(catalogue: CatalogueAt): List<Judged> = catalogue.rows
        .groupBy({ (_, row) -> row.eventId to row.part }, { (release, row) -> release.fake().guid to row })
        .map { (part, rows) -> Judged(part.first, part.second, rows) }

    /** What the runner gave and found for [judged]'s part over [search], against its rows; the differences, if any. */
    private fun compare(catalogue: CatalogueAt, matchers: Matchers, judged: Judged, search: PluginSearch, found: () -> Set<String>): List<String> {
        val event = catalogue.event(judged.event)
        val part = event.parts.single { it.id == judged.part }
        val given: ItemReleases = runBlocking { Runner(search).releases(matchers.of(event), event, part) }
        val reached = judged.reached().map { it.first }.toSet()
        val accepted = judged.reached(SportsVerdict.Verdict.Accept).map { it.first }.toSet()
        val offered = judged.reached(SportsVerdict.Verdict.Offer).map { (guid, row) -> guid to row.reason }.toSet()
        val leftOut = judged.reached(SportsVerdict.Verdict.Reject).size
        val got = listOf(
            "found" to (found() to reached),
            "accepted" to (given.accepted.map { it.toString() }.toSet() to accepted),
            "offered" to (given.offered.map { it.handle.toString() to it.reason }.toSet() to offered),
            "left out" to (given.leftOut?.substringBefore(" left out")?.toInt() to leftOut.takeIf { it > 0 }),
        )
        return got.filter { (_, pair) -> pair.first != pair.second }
            .map { (what, pair) -> "${judged.event} ${judged.part} at ${catalogue.anchor}, $what: ${pair.first}, the rows say ${pair.second}" }
    }

    @Test
    fun `each part's searches, stage 1 then stage 2 as the runner sends them, find the rows marked reached and no other`() {
        indexer.serve(Catalogue.releases.map { it.fake() to NZB })
        val matchers = Matchers(Catalogue.sports)
        val wrong = judged(Catalogue).flatMap { judged ->
            val search = IndexerSearch(indexer.port, KEY, INDEXER)
            val differences = compare(Catalogue, matchers, judged, search) { search.found }
            // Stage 1's two searches went first, together; stage 2's after them, only where stage 1 found no name the rows
            // accept.
            val event = Catalogue.event(judged.event)
            val queries = matchers.of(event).queries(event, event.parts.single { it.id == judged.part })
            val first = queries.first.map(String::trim).filter(String::isNotEmpty).distinct().take(2)
            val stageOne = first.flatMap { search.byWords[it].orEmpty() }.toSet()
            val acceptedThere = judged.reached(SportsVerdict.Verdict.Accept).any { (guid, _) -> guid in stageOne }
            val then = listOfNotNull(queries.then?.trim()?.takeIf { !acceptedThere && it.isNotEmpty() && it !in first })
            val asked = search.asked.toList()
            val inOrder = asked.take(first.size).toSet() == first.toSet() && asked.drop(first.size) == then
            differences + listOfNotNull("${judged.event} ${judged.part} asked $asked".takeUnless { inOrder })
        }
        assertEquals(emptyList(), wrong, wrong.joinToString("\n"))
        // The rows no search reaches: the race's F1-led and running-number names, and the film named like a side.
        val unreached = Catalogue.rows.filter { (_, row) -> !row.reached }.map { (release, _) -> release.fake().guid }.toSet()
        assertEquals(setOf("sp-kestrel-race-f1", "sp-kestrel-race-running-number", "sp-copper-crown-film"), unreached)
    }

    /**
     * The fakes start on any day, and a search dated with three numbers may hold one twice ("2027 12 12", "2028 01 01"),
     * which the sports indexer counts: the same searches, matched by the indexer's own word matching with no request (a
     * loopback answer of this fake takes about 40 ms), find the rows marked reached on every day of a year and a month.
     */
    @Test
    fun `the same searches find the same rows whatever the anchor - every day of a year and a month`() {
        val wrong = generateSequence(LocalDate.of(2027, 12, 1)) { it.plusDays(1) }.takeWhile { it.year < 2029 }.flatMap { anchor ->
            val catalogue = CatalogueAt(anchor)
            val matchers = Matchers(catalogue.sports)
            judged(catalogue).flatMap { judged ->
                val search = MatchingSearch(catalogue)
                compare(catalogue, matchers, judged, search) { search.found }
            }
        }.toList()
        assertEquals(emptyList(), wrong.take(20), "${wrong.size} differ:\n" + wrong.take(20).joinToString("\n"))
    }

    private companion object {
        const val KEY = "fake-indexer-key"
        const val INDEXER = "Sports indexer"

        /** What the indexer's get would serve: no test grabs a release. */
        const val NZB = """<?xml version="1.0" encoding="UTF-8"?><nzb xmlns="http://www.newzbin.com/DTD/2003/nzb"/>"""
    }
}
