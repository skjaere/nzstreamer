package app.nzstreamer.plugin.sportarr.match

import app.nzstreamer.plugin.sportarr.Logged
import app.nzstreamer.plugin.sportarr.family.FightCard
import app.nzstreamer.plugin.sportarr.model.BOXING
import app.nzstreamer.plugin.sportarr.model.FORMULA_1
import app.nzstreamer.plugin.sportarr.model.PREMIER
import app.nzstreamer.plugin.sportarr.model.Parts
import app.nzstreamer.plugin.sportarr.model.SKIING
import app.nzstreamer.plugin.sportarr.model.Sports
import app.nzstreamer.plugin.sportarr.model.UFC
import app.nzstreamer.plugin.sportarr.model.VELMORA
import app.nzstreamer.plugin.sportarr.model.at
import app.nzstreamer.plugin.sportarr.model.on
import app.nzstreamer.plugin.sportarr.model.sports
import app.nzstreamer.plugin.sportarr.model.ufc999
import app.nzstreamer.plugin.sportarr.source.KeptLeague
import app.nzstreamer.plugin.sportarr.source.TestSportarr
import io.skjaere.usenet.plugin.FileInfo
import io.skjaere.usenet.plugin.Found
import io.skjaere.usenet.plugin.IndexerLine
import io.skjaere.usenet.plugin.ItemReleases
import io.skjaere.usenet.plugin.PluginSearch
import io.skjaere.usenet.plugin.ReleaseInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L647's runner: on a play or Choose release alone, a part's two first queries searched together with the part's runtime,
 * a third only when they accepted nothing; every release judged once, at its best place across the answers; and what it
 * gives the host: the accepted in that order, the possible matches with their reasons, one line counting what was left out
 * by kind, and for nothing at all each indexer's line and the note. It can be cancelled at any point and logs no name.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunnerTest {
    private val list = sports(
        PREMIER to listOf(on(PREMIER, 1001, at("2026-11-21T15:00"), "Lantern Bay FC vs Copper Crown United")),
        FORMULA_1 to listOf(on(FORMULA_1, 1605, at("2026-11-22T14:00"), "Grand Prix of Kestrel", round = "20")),
        UFC to listOf(ufc999()),
        SKIING to listOf(on(SKIING, 2201, at("2026-11-21T10:30"), "Kestrel · Men's Downhill")),
    )
    private val game = list.byId(1001)
    private val race = list.byId(1605)
    private val card = list.byId(31)
    private val mainCard = card.parts.single { it.id == "main-card" }
    private val downhill = list.byId(2201)

    private fun Sports.byId(id: Int) = events.single { it.id == id }

    /** One query's last answer: [releases], each indexer's line counting its own. */
    private fun found(releases: List<ReleaseInfo>, lines: List<IndexerLine>? = null): Found = Found(
        lines ?: releases.map { it.indexer }.distinct().map { indexer -> answered(indexer, releases.count { it.indexer == indexer }) },
        releases,
    )

    /** A search answering each query's words with its releases, and nothing for any other. */
    private fun answers(vararg answers: Pair<String, List<ReleaseInfo>>): CountingSearch {
        val byWords = answers.toMap()
        return CountingSearch { words -> found(byWords[words].orEmpty()) }
    }

    /** A family giving the named releases their verdicts, accepting any other. */
    private fun family(vararg verdicts: Pair<String, Verdict>, first: List<String> = listOf("q1", "q2"), then: String? = "q3") =
        TestFamily(first = first, then = then, verdict = { name -> verdicts.toMap()[name.name] ?: Verdict.Accept() })

    private val ItemReleases.acceptedLabels get() = accepted.map { it.toString() }
    private val ItemReleases.offeredLabels get() = offered.map { "${it.handle}: ${it.reason}" }

    private fun gameNamed(words: String) = "Premier.League.2026.11.21.Lantern.Bay.FC.vs.Copper.Crown.United.$words"

    @Test
    fun `nothing is searched until a play or Choose release asks`() = runTest {
        val search = answers()
        val family = family()
        val runner = Runner(search)
        val picker = PartPicker.of(family, game, game.headline)
        picker.takesWhole(gameNamed("1080p.WEB.h264-KESTREL"))
        picker.pick(listOf(FileInfo("Lantern.Bay.FC.vs.Copper.Crown.United.mkv", 6_000_000_000, isVideo = true)))
        family.queries(game, game.headline)
        assertEquals(emptyList(), search.asked)

        runner.releases(family, game, game.headline)
        assertEquals(listOf("q1", "q2", "q3"), search.asked.map { it.words })
    }

    @Test
    fun `stage one searches two queries together with the part's runtime, and three searches at most`() = runTest {
        val search = CountingSearch { delay(1_000); found(emptyList()) }
        val family = family(first = listOf("UFC 999", "UFC 2026 11 21", "UFC Quessel Halde"), then = "UFC Quessel Halde")
        Runner(search).releases(family, card, mainCard)
        assertEquals(listOf("UFC 999", "UFC 2026 11 21", "UFC Quessel Halde"), search.asked.map { it.words })
        assertEquals(2, search.mostAtOnce, "two at once, the third after them")
        assertEquals(listOf(180, 180, 180), search.asked.map { it.runtimeMinutes })

        val generic = answers()
        Runner(generic).releases(family(), downhill, downhill.headline)
        assertEquals(listOf(null, null, null), generic.asked.map { it.runtimeMinutes }, "no runtime for an event the plugin doesn't time")
    }

    /**
     * The host's search as a plugin meets it: its cache keys on the words, so words asked before are answered from it and
     * only new ones reach the [indexer].
     */
    private class CachedSearch(private val indexer: CountingSearch) : PluginSearch {
        val asked: MutableList<String> = CopyOnWriteArrayList()
        private val cache = ConcurrentHashMap<String, Found>()

        override fun search(words: String, runtimeMinutes: Int?, fresh: Boolean): Flow<Found> = flow {
            asked += words
            val cached = cache[words]
            if (cached != null) {
                emit(cached)
            } else {
                indexer.search(words, runtimeMinutes, fresh).collect { found ->
                    cache[words] = found
                    emit(found)
                }
            }
        }
    }

    @Test
    fun `a fight night's second part is searched by the same words, so the host's cache answers it and the limits hold`() = runTest {
        val listed = sports(UFC to listOf(on(UFC, 41, at("2026-11-28T19:00"), "UFC Fight Night 888 Quessel vs Halde", parts = listOf("Prelims", "Main Card"))))
        val night = listed.byId(41)
        val matcher = FightCard(listed)
        fun part(id: String) = night.parts.single { it.id == id }
        val prelims = "UFC.Fight.Night.888.Quessel.vs.Halde.Prelims.1080p.WEB.h264-KESTREL"
        val main = "UFC.Fight.Night.888.Quessel.vs.Halde.1080p.WEB.h264-KESTREL"
        val scene = listOf(release(prelims), release(main))
        val indexer = CountingSearch { words -> found(if (words == "UFC Fight Night 888" || words == "UFC Quessel Halde") scene else emptyList()) }
        val host = CachedSearch(indexer)
        val runner = Runner(host)
        assertEquals(listOf(main), runner.releases(matcher, night, part(Parts.MAIN_CARD)).acceptedLabels)
        assertEquals(listOf(prelims), runner.releases(matcher, night, part(Parts.PRELIMS)).acceptedLabels)
        assertEquals(setOf("UFC Fight Night 888", "UFC Quessel Halde"), indexer.asked.map { it.words }.toSet(), "stage one found both, so no date")
        assertEquals(2, indexer.asked.size, "the prelims' run asked the indexers nothing new")
        assertEquals(4, host.asked.size)
        assertTrue(indexer.mostAtOnce <= 2)

        // With nothing found, each run searches its three, the date last; the second part's are the first's again.
        val empty = CountingSearch { found(emptyList()) }
        val nothing = CachedSearch(empty)
        listOf(Parts.MAIN_CARD, Parts.PRELIMS).forEach { Runner(nothing).releases(matcher, night, part(it)) }
        assertEquals(listOf("UFC 2026 11 28"), empty.asked.drop(2).map { it.words }, "the date in stage two")
        assertEquals(3, empty.asked.size, "three searches for the card, whichever part")
        assertEquals(6, nothing.asked.size, "three a run: two parts within the host's ten a minute")
        assertTrue(empty.mostAtOnce <= 2)
    }

    @Test
    fun `stage two asks only when stage one accepted nothing, possible matches or not`() = runTest {
        val name = gameNamed("1080p.WEB.h264-KESTREL")
        val accepted = answers("q2" to listOf(release(name)))
        Runner(accepted).releases(family(), game, game.headline)
        assertEquals(listOf("q1", "q2"), accepted.asked.map { it.words }.sorted())

        val offered = answers("q2" to listOf(release(name)))
        Runner(offered).releases(family(name to Verdict.Offer("No date in the name")), game, game.headline)
        assertEquals(listOf("q1", "q2", "q3"), offered.asked.map { it.words }.sorted())

        val noSecond = answers()
        Runner(noSecond).releases(family(then = null), game, game.headline)
        assertEquals(2, noSecond.asked.size)

        val same = answers()
        Runner(same).releases(family(first = listOf("q1", " q1 ", ""), then = "q1"), game, game.headline)
        assertEquals(listOf("q1"), same.asked.map { it.words }, "a query is searched once, a blank one never")
    }

    @Test
    fun `a release in two answers is judged once and kept at its best place, then the possible matches in that order`() = runTest {
        val a = gameNamed("1080p.WEB.h264-KESTREL")
        val b = gameNamed("720p.WEB.h264-WREN")
        val c = gameNamed("1080p.HDTV.x264-ORCHID")
        val d = gameNamed("720p.HDTV.x264-DRIFTWOOD")
        val search = answers(
            "q1" to listOf(release(b, "B@q1"), release(c, "C@q1"), release(a, "A@q1")),
            "q2" to listOf(release(a, "A@q2"), release(d, "D@q2")),
        )
        val family = family(c to Verdict.Offer("Team codes only"))
        val result = Runner(search).releases(family, game, game.headline)

        assertEquals(listOf(b, c, a, d), family.judged, "each release judged once")
        assertEquals(listOf("B@q1", "A@q2", "D@q2"), result.acceptedLabels, "by place, the first query first at the same place")
        assertEquals(listOf("C@q1: Team codes only"), result.offeredLabels)
        assertNull(result.leftOut)
    }

    @Test
    fun `the same name from another indexer or with another size is a release of its own`() = runTest {
        val a = gameNamed("1080p.WEB.h264-KESTREL")
        val search = answers(
            "q1" to listOf(release(a, "A@A"), release(a, "A@B", indexer = "Indexer B")),
            "q2" to listOf(release(a, "A@A again"), release(a, "A smaller", size = 5_000_000_000)),
        )
        val family = family()
        val result = Runner(search).releases(family, game, game.headline)
        assertEquals(3, family.judged.size)
        assertEquals(listOf("A@A", "A@B", "A smaller"), result.acceptedLabels)
    }

    @Test
    fun `the left-out line counts the kinds, the most first, three at most`() = runTest {
        val otherGame = "Premier.League.2026.11.21.Saltmere.City.vs.Lowmarsh.Rovers.1080p.WEB.h264-KESTREL"
        val gameLeftOut = listOf(
            gameNamed("Highlights.720p.WEB.h264-WREN"),
            gameNamed("Extended.Highlights.1080p.WEB.h264-ORCHID"),
            gameNamed("Highlights.1080p.WEB.h264-KESTREL"),
            "Premier.League.2026.11.21.Lantern.Bay.FC.3.1.Copper.Crown.United.1080p.WEB.h264-KESTREL",
            "Premier.League.2026.11.21.Lantern.Bay.FC.2-2.Copper.Crown.United.720p.WEB.h264-WREN",
            otherGame,
        )
        val other = otherGame to Verdict.Reject(RejectKind.OtherEvents)
        assertEquals(
            "6 left out: highlights, names with a score, other games. Their names aren't shown.",
            Runner(answers("q1" to gameLeftOut.map { release(it) })).releases(family(other), game, game.headline).leftOut,
        )

        val qualifying = "Formula1.2026.Round20.Kestrel.Grand.Prix.Qualifying.1080p.WEB.h264-KESTREL"
        val practice = "Formula.1.2026.Round.20.Kestrel.Practice.3.720p.WEB.h264-ORCHID"
        val raceLeftOut = listOf(
            qualifying,
            practice,
            "Formula1.2026.Round20.Kestrel.Grand.Prix.Pre-Race.Show.1080p.WEB.h264-WREN",
            "Formula1.2026.Round20.Kestrel.Grand.Prix.Post-Race.Show.720p.WEB.h264-ORCHID",
            "Formula1.2026.Round20.Kestrel.Grand.Prix.Race.Highlights.720p.WEB.h264-ORCHID",
        )
        val sessions = family(qualifying to Verdict.Reject(RejectKind.OtherEvents), practice to Verdict.Reject(RejectKind.OtherEvents))
        assertEquals(
            "5 left out: other sessions, pre-race shows, highlights. Their names aren't shown.",
            Runner(answers("q1" to raceLeftOut.map { release(it) })).releases(sessions, race, race.headline).leftOut,
        )

        val oneEach = listOf(gameNamed("Highlights.720p.WEB.h264-WREN"), gameLeftOut[3], otherGame)
        assertEquals(
            "3 left out: other games, highlights, a name with a score. Their names aren't shown.",
            Runner(answers("q2" to oneEach.map { release(it) })).releases(family(other), game, game.headline).leftOut,
            "kinds with as many, in their own order",
        )

        val early = release(gameNamed("720p.HDTV.x264-ORCHID"), posted = game.start.minus(Duration.ofDays(3)))
        val result = gameNamed("Result.720p.WEB.h264-ORCHID")
        val fiveKinds = oneEach.map { release(it) } + release(result) + early
        assertEquals(
            "5 left out: other games, highlights, a name with a score and more. Their names aren't shown.",
            Runner(answers("q1" to fiveKinds)).releases(family(other), game, game.headline).leftOut,
        )

        assertNull(Runner(answers("q1" to listOf(release(gameNamed("1080p.WEB.h264-KESTREL"))))).releases(family(), game, game.headline).leftOut)
    }

    @Test
    fun `a name with a score or a result word is never accepted or offered, only counted`() = runTest {
        val good = gameNamed("1080p.WEB.h264-KESTREL")
        val scored = "Premier.League.2026.11.21.Lantern.Bay.FC.3.1.Copper.Crown.United.1080p.WEB.h264-KESTREL"
        val result = gameNamed("Result.720p.WEB.h264-ORCHID")
        val releases = listOf(release(scored), release(good), release(result))
        val accepting = Runner(answers("q1" to releases)).releases(family(), game, game.headline)
        assertEquals(listOf(good), accepting.acceptedLabels)
        assertEquals(emptyList(), accepting.offeredLabels)
        assertEquals("2 left out: a name with a score, a name with a result. Their names aren't shown.", accepting.leftOut)

        val offering = family(good to Verdict.Offer("No date in the name"), scored to Verdict.Offer("No date in the name"), result to Verdict.Offer("No date in the name"))
        val offered = Runner(answers("q1" to releases)).releases(offering, game, game.headline)
        assertEquals(emptyList(), offered.acceptedLabels)
        assertEquals(listOf("$good: No date in the name"), offered.offeredLabels)
    }

    @Test
    fun `with nothing at all, each indexer's line in the host's order and the note`() = runTest {
        val highlights = listOf(
            "Highlights.720p.WEB.h264-WREN", "Highlights.1080p.WEB.h264-KESTREL", "Highlights.720p.HDTV.x264-ORCHID",
            "Highlights.1080p.HDTV.x264-DRIFTWOOD", "Extended.Highlights.720p.WEB.h264-WREN", "Extended.Highlights.1080p.WEB.h264-KESTREL",
        ).map(::gameNamed)
        val failed = IndexerLine("Indexer D", IndexerLine.State.Failed, 0, "Couldn't reach it")
        val skipped = IndexerLine("Indexer E", IndexerLine.State.Skipped, 0, "Doesn't support this search")
        val first = highlights.take(4).map { release(it) } + release(highlights[0], indexer = "Indexer B")
        val second = highlights.drop(2).map { release(it) }
        val answers = mapOf(
            "q1" to found(first, listOf(answered("Indexer A", 4), answered("Indexer B", 1), answered("Indexer C", 0), failed, skipped)),
            "q2" to found(second, listOf(answered("Indexer A", 4), answered("Indexer B", 0), answered("Indexer C", 0), failed, skipped)),
            "q3" to found(emptyList(), listOf("Indexer A", "Indexer B", "Indexer C", "Indexer D", "Indexer E").map {
                IndexerLine(it, IndexerLine.State.Limited, 0, "Paused: too many searches from Sportarr")
            } + IndexerLine("Indexer F", IndexerLine.State.Limited, 0, "Paused: too many searches from Sportarr")),
        )
        val result = Runner(CountingSearch { answers.getValue(it) }).releases(family(), game, game.headline)
        assertEquals(
            listOf(
                "Indexer A: 6 results, none for this game",
                "Indexer B: 1 result, none for this game",
                "Indexer C: No results",
                "Indexer D: Couldn't reach it",
                "Indexer E: Doesn't support this search",
                "Indexer F: Paused: too many searches from Sportarr",
            ),
            result.lines.map { "${it.indexer}: ${it.words}" },
        )
        assertEquals("Releases often appear a few hours after a game ends. Try Play again later.", result.note)
        assertEquals("7 left out: highlights. Their names aren't shown.", result.leftOut)

        val one = Runner(answers("q1" to listOf(release(gameNamed("1080p.WEB.h264-KESTREL"))))).releases(family(), game, game.headline)
        assertEquals(emptyList(), one.lines)
        assertNull(one.note)
        val possible = Runner(answers("q1" to listOf(release(gameNamed("1080p.WEB.h264-KESTREL")))))
            .releases(family(gameNamed("1080p.WEB.h264-KESTREL") to Verdict.Offer("No date in the name")), game, game.headline)
        assertEquals(emptyList(), possible.lines)
        assertNull(possible.note)
    }

    @Test
    fun `an accepted release that can't play here finds nothing, so stage two still asks and the sheet gets its lines and note`() = runTest {
        val name = gameNamed("1080p.WEB.h264-KESTREL")
        fun unplayable(name: String) = ReleaseInfo(name, 6_000_000_000, null, "Indexer A", playable = false, whyNot = "Not cached", handle = TestHandle(name))
        val search = answers("q1" to listOf(unplayable(name)))
        val result = Runner(search).releases(family(), game, game.headline)
        assertEquals(listOf("q1", "q2", "q3"), search.asked.map { it.words }.sorted())
        assertEquals(listOf(name), result.acceptedLabels, "still given, for the host to show as hidden")
        assertEquals(listOf("Indexer A"), result.lines.map { it.indexer })
        assertEquals("Releases often appear a few hours after a game ends. Try Play again later.", result.note)

        // A possible match that plays here is something to pick: Choose release opens, with no sheet.
        val offer = gameNamed("720p.WEB.h264-DRIFTWOOD")
        val withOffer = answers("q1" to listOf(unplayable(name), release(offer)))
        val picked = Runner(withOffer).releases(family(offer to Verdict.Offer("No date in the name")), game, game.headline)
        assertEquals(listOf("q1", "q2", "q3"), withOffer.asked.map { it.words }.sorted())
        assertEquals(emptyList(), picked.lines)
        assertNull(picked.note)
    }

    @Test
    fun `an indexer's one release counted under another's listing still counts as its result`() = runTest {
        // A torrent several indexers list is one release, kept under one of them.
        val highlights = gameNamed("Highlights.720p.WEB.h264-WREN")
        val search = CountingSearch { found(listOf(release(highlights)), listOf(answered("Indexer A", 1), answered("Indexer B", 1))) }
        val result = Runner(search).releases(family(), game, game.headline)
        assertEquals(listOf("1 result, none for this game", "1 result, none for this game"), result.lines.map { it.words })
    }

    @Test
    fun `the nouns of each family's items and events`() = runTest {
        val more = sports(
            UFC to listOf(on(UFC, 32, at("2026-11-14T23:00"), "UFC Fight Night: Brisk vs. Loam")),
            BOXING to listOf(on(BOXING, 33, at("2026-11-21T22:00"), "Halde vs. Brisk")),
            VELMORA to listOf(
                on(VELMORA, 41, at("2026-11-10T01:00"), "Velmora Monday Brawl"),
                on(VELMORA, 42, at("2026-11-17T01:00"), "Velmora Monday Brawl"),
                on(VELMORA, 43, at("2026-11-28T23:00"), "Velmora Iron Crown: Night 2", parts = listOf("Countdown", "Main Show")),
            ),
        )
        val nouns = (list.events + more.events).flatMap { event ->
            event.parts.map { "${event.title} / ${it.label}: ${Nouns.item(event, it)}, ${Nouns.event(event)}" }
        }
        assertEquals(
            listOf(
                "Grand Prix of Kestrel / Race: session, session",
                "Halde vs. Brisk / Halde vs. Brisk: fight, fight",
                "Kestrel · Men's Downhill / Kestrel · Men's Downhill: event, event",
                "Lantern Bay FC vs Copper Crown United / Lantern Bay FC vs Copper Crown United: game, game",
                "UFC 999: Quessel vs. Halde / Early Prelims: early prelims, card",
                "UFC 999: Quessel vs. Halde / Main Card: main card, card",
                "UFC 999: Quessel vs. Halde / Prelims: prelims, card",
                "UFC Fight Night: Brisk vs. Loam / Main Card: main card, card",
                "UFC Fight Night: Brisk vs. Loam / Prelims: prelims, card",
                "Velmora Iron Crown: Night 2 / Countdown: pre-show, card",
                "Velmora Iron Crown: Night 2 / Main Show: main show, card",
                "Velmora Monday Brawl / Velmora Monday Brawl: show, show",
                "Velmora Monday Brawl / Velmora Monday Brawl: show, show",
            ),
            nouns.sorted(),
        )
        val nothing = Runner(answers()).releases(family(), downhill, downhill.headline)
        assertEquals("Releases often appear a few hours after an event ends. Try Play again later.", nothing.note)
    }

    @Test
    fun `releases are judged on the runner's own thread, off the caller's`() = runTest {
        val judging = Executors.newSingleThreadExecutor()
        try {
            val work = judging.submit<Thread> { Thread.currentThread() }.get()
            val threads = CopyOnWriteArrayList<Thread>()
            val family = TestFamily(first = listOf("q1"), verdict = { threads += Thread.currentThread(); Verdict.Accept() })
            val search = answers("q1" to listOf(release(gameNamed("1080p.WEB.h264-KESTREL")), release(gameNamed("720p.WEB.h264-WREN"))))
            Runner(search, judging.asCoroutineDispatcher()).releases(family, game, game.headline)
            assertEquals(listOf(work, work), threads.toList())
        } finally {
            judging.shutdown()
        }
    }

    @Test
    fun `a cancelled run leaves no search running, and its cancellation goes on up`() = runTest {
        val search = CountingSearch { awaitCancellation() }
        val mark = Logged.mark()
        // The call itself is watched, not a Deferred, which ends cancelled whatever the call does with the cancellation.
        var returned = false
        var threw: CancellationException? = null
        val run = launch {
            try {
                Runner(search).releases(family(), game, game.headline)
                returned = true
            } catch (e: CancellationException) {
                threw = e
                throw e
            }
        }
        runCurrent()
        assertEquals(2, search.runningNow, "stage one's two searches")
        run.cancel()
        run.join()
        assertTrue(threw != null && !returned, "the call threw its cancellation and gave nothing")
        assertEquals(0, search.runningNow)
        assertEquals(2, search.asked.size, "no stage two after a cancel")
        assertEquals(emptyList(), Logged.pluginLinesSince(mark).map { it.words }, "a cancelled run logs no result")
    }

    @Test
    fun `a run's line names the family, the sport it came from and each search's indexers, and never a name or a query`() = runTest {
        val combat = KeptLeague(4, "UFC", "Combat")
        val fights = sports(combat to listOf(ufc999()))
        val card = fights.byId(31)
        val queries = listOf("UFC 999", "UFC 2026 11 21")
        val names = listOf("UFC.999.Quessel.vs.Halde.1080p.WEB.h264-KESTREL", "UFC.999.Prelims.720p.WEB.h264-WREN")
        val answers = mapOf(
            queries[0] to found(
                names.map { release(it) },
                listOf(
                    answered("Indexer A", 2),
                    IndexerLine("Indexer B", IndexerLine.State.Found, 0, "0 found"),
                    IndexerLine("Indexer C", IndexerLine.State.Failed, 0, "No answer in time"),
                ),
            ),
            queries[1] to found(
                emptyList(),
                listOf(
                    IndexerLine("Indexer A", IndexerLine.State.Failed, 0, "Server error"),
                    IndexerLine("Indexer B", IndexerLine.State.Skipped, 0, "Doesn't support this search"),
                    IndexerLine("Indexer C", IndexerLine.State.Limited, 0, "Paused: too many searches from Sportarr"),
                ),
            ),
            "UFC Quessel Halde" to found(emptyList(), emptyList()),
        )
        val family = TestFamily(first = queries, then = "UFC Quessel Halde", verdict = { Verdict.Offer("No date in the name") })
        val mark = Logged.mark()
        Runner(CountingSearch { answers.getValue(it) }).releases(family, card, card.headline)
        val line = Logged.pluginLinesSince(mark).single().words
        assertEquals(
            "Releases for a FightCard part (the league's sport \"combat\"): 3 searches [2, empty, failed (No answer in time)] " +
                "[failed (Server error), skipped, paused] [no answer], 2 judged, 0 accepted, 2 offered, 0 left out {}",
            line.substringBeforeLast(" in "),
        )
        val secrets = names + queries + listOf("UFC Quessel Halde", card.title, combat.name, "Indexer", "Quessel", "Sportarr") + TestSportarr.CANARY_VALUES
        assertEquals(emptyList(), secrets.filter { it in line }, line)
    }

    @Test
    fun `a search whose answer names no indexer, or that ended with none, says no answer, not that no indexer is on`() = runTest {
        // None switched on, the host's own search failed before any indexer answered, or a flow that ended with nothing:
        // the plugin can't tell them apart, so its line claims none of them.
        val search = object : PluginSearch {
            override fun search(words: String, runtimeMinutes: Int?, fresh: Boolean): Flow<Found> =
                if (words == "q1") flowOf(Found(emptyList(), emptyList())) else emptyFlow()
        }
        val mark = Logged.mark()
        Runner(search).releases(family(then = null), card, mainCard)
        val line = Logged.pluginLinesSince(mark).single().words
        assertTrue(": 2 searches [no answer] [no answer], 0 judged, " in line, line)
    }

    @Test
    fun `a run with no query to search says 0 searches, with no space before the comma`() = runTest {
        // A matcher whose event gives no words (a game with no sides, a series with no written form) searches nothing.
        val search = answers()
        val mark = Logged.mark()
        Runner(search).releases(family(first = emptyList(), then = null), card, mainCard)
        assertEquals(emptyList(), search.asked)
        val line = Logged.pluginLinesSince(mark).single().words
        assertTrue("): 0 searches, 0 judged, 0 accepted, 0 offered, 0 left out {} in " in line, line)
    }

    @Test
    fun `a run's line says where the family came from - the league's sport, the event's, part names or none`() = runTest {
        val combat = KeptLeague(4, "UFC", "Combat")
        val unknown = KeptLeague(21, "Kestrel Games", "Exhibition")
        val none = KeptLeague(22, "Kestrel Open", null)
        val listed = sports(
            combat to listOf(ufc999(), on(combat, 34, at("2026-11-07T23:00"), "2026-11-07")),
            unknown to listOf(
                on(unknown, 32, at("2026-11-14T23:00"), "Kestrel Fight Night: Brisk vs. Loam", parts = listOf("Prelims", "Main Card")),
                on(unknown, 33, at("2026-11-15T23:00"), "Halde vs. Brisk").copy(sport = "Fighting"),
                on(unknown, 35, at("2026-11-16T10:00"), "Kestrel Open Final"),
            ),
            none to listOf(on(none, 36, at("2026-11-17T10:00"), "Kestrel Open Semi-Final")),
            SKIING to listOf(on(SKIING, 2201, at("2026-11-21T10:30"), "Kestrel · Men's Downhill")),
        )
        val origins = listed.events.sortedBy { it.id }.map { event ->
            val mark = Logged.mark()
            Runner(answers()).releases(family(), event, event.headline)
            Logged.pluginLinesSince(mark).single().words.substringBefore("):") + ")"
        }
        assertEquals(
            listOf(
                "Releases for a FightCard part (the league's sport \"combat\")",
                "Releases for a FightCard part (part names, sport \"exhibition\")",
                "Releases for a FightCard part (the event's sport \"fighting\")",
                "Releases for a Generic part (the league's sport \"combat\", stepped down)",
                "Releases for a Generic part (sport \"exhibition\" in no family)",
                "Releases for a Generic part (no sport)",
                "Releases for a Generic part (sport \"skiing\" in no family)",
            ),
            origins,
        )
    }

    @Test
    fun `a sport word in no family is named only when it is a short plain word, else by its length`() = runTest {
        // A league's sport is whatever its Sportarr holds, which a user can rename: free text, of any length.
        val marked = KeptLeague(23, "Kestrel Games", "Kestrel \"Open\": {Night}")
        val longest = KeptLeague(24, "Kestrel Open", "Exhibition".repeat(3) + "ab")
        val tooLong = KeptLeague(25, "Kestrel Open", "Exhibition".repeat(3) + "abc")
        val numbered = KeptLeague(26, "Kestrel Open", "Kestrel Cup 2026")
        val listed = sports(
            marked to listOf(on(marked, 41, at("2026-11-14T23:00"), "Kestrel Fight Night: Brisk vs. Loam", parts = listOf("Prelims", "Main Card"))),
            longest to listOf(on(longest, 42, at("2026-11-15T10:00"), "Kestrel Open Final")),
            tooLong to listOf(on(tooLong, 43, at("2026-11-16T10:00"), "Kestrel Open Final")),
            numbered to listOf(on(numbered, 44, at("2026-11-17T10:00"), "Kestrel Open Final")),
        )
        val origins = listed.events.sortedBy { it.id }.map { event ->
            val mark = Logged.mark()
            Runner(answers()).releases(family(), event, event.headline)
            Logged.pluginLinesSince(mark).single().words.substringBefore("):") + ")"
        }
        assertEquals(
            listOf(
                "Releases for a FightCard part (part names, a sport of 23 characters)",
                "Releases for a Generic part (sport \"exhibitionexhibitionexhibitionab\" in no family)",
                "Releases for a Generic part (a sport of 33 characters in no family)",
                "Releases for a Generic part (a sport of 16 characters in no family)",
            ),
            origins,
        )
    }

    @Test
    fun `a run logs counts and kinds, never a release's name, a query, the event's title or a canary value`() = runTest {
        val names = listOf(
            gameNamed("1080p.WEB.h264-KESTREL"),
            gameNamed("Highlights.720p.WEB.h264-WREN"),
            "Premier.League.2026.11.21.Lantern.Bay.FC.3.1.Copper.Crown.United.1080p.WEB.h264-KESTREL",
            gameNamed("Result.720p.WEB.h264-ORCHID"),
            gameNamed("720p.HDTV.x264-ORCHID"),
        )
        val family = family(names[4] to Verdict.Offer("No date in the name"), first = listOf("Lantern Bay Copper Crown", "Premier League 2026 11 21"))
        val mark = Logged.mark()
        Runner(answers("Lantern Bay Copper Crown" to names.map { release(it) })).releases(family, game, game.headline)
        Runner(answers()).releases(family, card, mainCard)
        val lines = Logged.pluginLinesSince(mark)
        assertEquals(2, lines.size, lines.joinToString("\n") { it.words })
        val secrets = names + listOf(
            game.title, game.league.name, card.title, "Lantern", "Copper", "Quessel", "KESTREL", "WREN", "ORCHID", "No date in the name",
        ) + TestSportarr.CANARY_VALUES
        lines.forEach { line ->
            val held = secrets.filter { it in line.words }
            assertTrue(held.isEmpty(), "\"${line.words}\" holds $held")
        }
    }
}
