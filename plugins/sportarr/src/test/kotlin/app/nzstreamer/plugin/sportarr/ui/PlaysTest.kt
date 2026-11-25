package app.nzstreamer.plugin.sportarr.ui

import app.nzstreamer.plugin.sportarr.model.BOXING
import app.nzstreamer.plugin.sportarr.model.FORMULA_1
import app.nzstreamer.plugin.sportarr.model.PREMIER
import app.nzstreamer.plugin.sportarr.model.SKIING
import app.nzstreamer.plugin.sportarr.model.Sports
import app.nzstreamer.plugin.sportarr.model.SportsEvent
import app.nzstreamer.plugin.sportarr.model.UFC
import app.nzstreamer.plugin.sportarr.model.VELMORA
import app.nzstreamer.plugin.sportarr.model.at
import app.nzstreamer.plugin.sportarr.model.kestrelWeekend
import app.nzstreamer.plugin.sportarr.model.on
import app.nzstreamer.plugin.sportarr.model.sports
import app.nzstreamer.plugin.sportarr.model.ufc999
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.skjaere.usenet.plugin.FileInfo
import io.skjaere.usenet.plugin.Found
import io.skjaere.usenet.plugin.IndexerLine
import io.skjaere.usenet.plugin.ItemReleases
import io.skjaere.usenet.plugin.Offered
import io.skjaere.usenet.plugin.PlayItem
import io.skjaere.usenet.plugin.PluginNav
import io.skjaere.usenet.plugin.PluginPlay
import io.skjaere.usenet.plugin.PluginSearch
import io.skjaere.usenet.plugin.PluginWatch
import io.skjaere.usenet.plugin.ReleaseHandle
import io.skjaere.usenet.plugin.ReleaseInfo
import io.skjaere.usenet.plugin.WatchInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How a page plays a part through NZStreamer (L647, L652, L653): each family's item as the host plays and keeps it (its
 * key, the title Up next names it by, the league under it, its runtime, the noun Choose release's lines use, Search
 * indexers' words with the scores note, and the part picker where an event has several parts or a session may come in a
 * weekend's release); Play handing it to the host, which asks for releases only as it plays; Choose release opening the
 * host's list with what the runner found, and nothing at all when it found nothing; Search indexers; Mark watched.
 */
class PlaysTest {
    @Test
    fun `each family's item, as the host plays and keeps it`() {
        val brawls = listOf(23L, 16L, 9L).mapIndexed { i, day ->
            on(VELMORA, 41 + i, at("2026-11-${day.toString().padStart(2, '0')}T01:00"), "Velmora Monday Brawl")
        }
        val library = sports(
            PREMIER to listOf(on(PREMIER, 12, at("2026-11-21T15:00"), "Lantern Bay FC vs. Copper Crown United")),
            FORMULA_1 to kestrelWeekend(firstId = 21),
            UFC to listOf(ufc999(), on(UFC, 32, at("2026-11-28T19:00"), "UFC Fight Night: Brisk vs. Loam", parts = listOf("Prelims", "Main Card"))),
            BOXING to listOf(on(BOXING, 33, at("2026-11-21T22:00"), "Quessel vs. Halde")),
            VELMORA to brawls + on(VELMORA, 45, at("2026-11-29T00:00"), "Velmora Iron Crown"),
            SKIING to listOf(on(SKIING, 34, at("2026-11-21T10:30"), "Kestrel · Men's Downhill")),
        )
        fun item(key: String, part: String = "full"): String {
            val event = library.event(key)!!
            return described(Plays.item(library, event, event.parts.single { it.id == part }))
        }
        val note = "Indexer results can show scores."

        assertEquals("ev-000012:full | Lantern Bay FC vs. Copper Crown United | Premier League | 110 | game | Lantern Bay Copper Crown | $note | -", item("ev-000012"))
        assertEquals(
            "ev-000025:full | Grand Prix of Kestrel · Race | Formula 1 | 120 | session | Formula 1 Kestrel | $note | No file for the Race in this release",
            item("ev-000025"),
        )
        assertEquals(
            "ev-000022:full | Grand Prix of Kestrel · Practice 2 | Formula 1 | 65 | session | Formula 1 Kestrel | $note | No file for the Practice 2 in this release",
            item("ev-000022"),
        )
        assertEquals(
            "ev-000031:main-card | UFC 999: Quessel vs. Halde · Main Card | UFC | 180 | main card | UFC 999 | $note | No file for the Main Card in this release",
            item("ev-000031", "main-card"),
        )
        assertEquals(
            "ev-000031:early-prelims | UFC 999: Quessel vs. Halde · Early Prelims | UFC | 90 | early prelims | UFC 999 | $note | No file for the Early Prelims in this release",
            item("ev-000031", "early-prelims"),
        )
        assertEquals(
            "ev-000032:prelims | UFC Fight Night: Brisk vs. Loam · Prelims | UFC | 120 | prelims | UFC Brisk Loam | $note | No file for the Prelims in this release",
            item("ev-000032", "prelims"),
        )
        assertEquals("ev-000033:full | Quessel vs. Halde | Kestrel Boxing | 240 | fight | Quessel Halde | $note | -", item("ev-000033"))
        // A weekly show's words without its date: the same show airs every week, and Search finds every night of it.
        assertEquals("ev-000041:full | Velmora Monday Brawl | Velmora Wrestling | 150 | show | Velmora Monday Brawl | $note | -", item("ev-000041"))
        assertEquals(
            "ev-000045:pre-show | Velmora Iron Crown · Pre-Show | Velmora Wrestling | 60 | pre-show | Velmora Iron Crown | $note | No file for the Pre-Show in this release",
            item("ev-000045", "pre-show"),
        )
        assertEquals(
            "ev-000045:main-show | Velmora Iron Crown · Main Show | Velmora Wrestling | 240 | main show | Velmora Iron Crown | $note | No file for the Main Show in this release",
            item("ev-000045", "main-show"),
        )
        // Generic's words as its own search writes them, so Search finds what Play's searches would.
        assertEquals("ev-000034:full | Kestrel · Men's Downhill | FIS Alpine Ski World Cup | - | event | Kestrel Mens Downhill | $note | -", item("ev-000034"))
    }

    @Test
    fun `an event without Sportarr's own id is kept under its local key`() {
        val library = sports(PREMIER to listOf(on(PREMIER, 12, at("2026-11-21T15:00"), "Lantern Bay FC vs. Copper Crown United").copy(externalId = null)))
        val event = library.event("local:12")!!
        assertEquals("local:12:full", Plays.item(library, event, event.headline).key)
    }

    @Test
    fun `a session's picker chooses its file in a weekend's release, and a card part's in a full event`() {
        val library = sports(FORMULA_1 to kestrelWeekend(firstId = 21), UFC to listOf(ufc999()))
        val race = library.event("ev-000025")!!
        val weekend = listOf(
            FileInfo("Formula1.2026.Round20.Kestrel.Qualifying.1080p.WEB.h264-KESTREL.mkv", 3_000_000_000, true),
            FileInfo("Formula1.2026.Round20.Kestrel.Race.1080p.WEB.h264-KESTREL.mkv", 7_000_000_000, true),
            FileInfo("Formula1.2026.Round20.Kestrel.Race.Analysis.1080p.WEB.h264-KESTREL.mkv", 9_000_000_000, true),
        )
        assertEquals(1, Plays.item(library, race, race.headline).picker!!.pick(weekend))
        val card = library.event("ev-000031")!!
        val full = listOf(
            FileInfo("UFC.999.Early.Prelims.1080p.WEB.h264-WREN.mkv", 2_000_000_000, true),
            FileInfo("UFC.999.Prelims.1080p.WEB.h264-WREN.mkv", 3_000_000_000, true),
            FileInfo("UFC.999.Main.Card.1080p.WEB.h264-WREN.mkv", 5_000_000_000, true),
        )
        assertEquals(1, Plays.item(library, card, card.parts.single { it.id == "prelims" }).picker!!.pick(full))
    }

    @Test
    fun `Play hands the part to NZStreamer's play, which asks for its releases only as it plays`() = runBlocking<Unit> {
        val search = AnsweringSearch { listOf(release(ACCEPTED)) }
        val play = RecordingPlay()
        val plays = Plays(play, RecordingWatch(), search, this, { NOW }, EmptyCoroutineContext)
        val (library, game) = game()

        plays.play(library, game, game.headline).join()
        val item = play.items.single()
        assertEquals("ev-000012:full", item.key)
        assertEquals("Lantern Bay FC vs. Copper Crown United", item.title)
        assertEquals(0, search.count.get(), "nothing searched until the host asks for the releases")

        val answer = play.releases.single()()
        assertEquals(listOf(110), search.runtimes.distinct(), "every search with the part's runtime")
        assertEquals(listOf("Lantern Bay Copper Crown", "Premier League 2026 11 21"), search.words.sorted())
        assertEquals(1, answer.accepted.size)
        assertEquals(emptyMap(), plays.nothing.value, "a play that found something keeps no line")
    }

    @Test
    fun `an answer with nothing for the part keeps the page's line, from when it came`() = runBlocking<Unit> {
        val play = RecordingPlay()
        val plays = Plays(play, RecordingWatch(), AnsweringSearch { emptyList() }, this, { NOW }, EmptyCoroutineContext)
        val (library, game) = game()

        plays.play(library, game, game.headline).join()
        val answer = play.releases.single()()
        assertTrue(answer.accepted.isEmpty() && answer.offered.isEmpty())
        assertEquals("Releases often appear a few hours after a game ends. Try Play again later.", answer.note)
        assertEquals(mapOf("ev-000012:full" to NOW), plays.nothing.value)
    }

    @Test
    fun `Choose release opens the host's list with what the runner found`() = runBlocking<Unit> {
        val search = AnsweringSearch { words -> if (words == "Lantern Bay Copper Crown") listOf(release(ACCEPTED), release(UNDATED), release(SCORED)) else emptyList() }
        val nav = RecordingNav()
        val plays = Plays(RecordingPlay(), RecordingWatch(), search, this, { NOW }, EmptyCoroutineContext)
        val (library, game) = game()

        assertEquals(Chosen.Opened, plays.choose(library, game, game.headline, nav))
        val opened = nav.releases.single()
        assertEquals("ev-000012:full", opened.item.key)
        assertEquals("Lantern Bay Copper Crown", opened.item.search?.words)
        assertEquals(1, opened.accepted.size)
        assertEquals(listOf("No date in the name"), opened.offered.map { it.reason })
        assertEquals("1 left out: a name with a score. Their names aren't shown.", opened.leftOut)
        assertEquals(emptyMap(), plays.nothing.value)
        assertEquals(emptyList(), nav.searches)
    }

    @Test
    fun `Choose release with nothing at all opens nothing, and the page keeps its line`() = runBlocking<Unit> {
        val search = AnsweringSearch { listOf(release(SCORED)) }
        val nav = RecordingNav()
        val plays = Plays(RecordingPlay(), RecordingWatch(), search, this, { NOW }, EmptyCoroutineContext)
        val (library, game) = game()

        assertEquals(Chosen.NoneFound, plays.choose(library, game, game.headline, nav))
        assertEquals(emptyList(), nav.releases, "no list opened")
        assertEquals(mapOf("ev-000012:full" to NOW), plays.nothing.value)
    }

    @Test
    fun `the line goes once a later Play or Choose release finds releases for the part`() = runBlocking<Unit> {
        var there = false
        val search = AnsweringSearch { if (there) listOf(release(ACCEPTED)) else emptyList() }
        val play = RecordingPlay()
        val nav = RecordingNav()
        val plays = Plays(play, RecordingWatch(), search, this, { NOW }, EmptyCoroutineContext)
        val (library, game) = game()

        assertEquals(Chosen.NoneFound, plays.choose(library, game, game.headline, nav))
        assertEquals(mapOf("ev-000012:full" to NOW), plays.nothing.value)
        there = true
        assertEquals(Chosen.Opened, plays.choose(library, game, game.headline, nav))
        assertEquals(emptyMap(), plays.nothing.value, "Choose release listed releases: no line says there are none")

        there = false
        plays.play(library, game, game.headline).join()
        play.releases.last()()
        assertEquals(mapOf("ev-000012:full" to NOW), plays.nothing.value)
        there = true
        plays.play(library, game, game.headline).join()
        assertTrue(play.releases.last()().accepted.isNotEmpty())
        assertEquals(emptyMap(), plays.nothing.value, "Play found releases: no line says there are none")
    }

    @Test
    fun `a search no indexer answered keeps no line, and Choose release opens nothing`() = runBlocking<Unit> {
        // Paused by the host's cap, failed, or none switched on: nothing says the releases aren't there yet.
        listOf(
            listOf(IndexerLine("Indexer A", IndexerLine.State.Limited, 0, PAUSED)),
            listOf(IndexerLine("Indexer A", IndexerLine.State.Failed, 0, "Couldn't reach it"), IndexerLine("Indexer B", IndexerLine.State.Skipped, 0, "Can't search by text")),
            emptyList(),
        ).forEach { lines ->
            val search = UnansweredSearch(lines)
            val play = RecordingPlay()
            val nav = RecordingNav()
            val plays = Plays(play, RecordingWatch(), search, this, { NOW }, EmptyCoroutineContext)
            val (library, game) = game()

            plays.play(library, game, game.headline).join()
            val answer = play.releases.single()()
            assertTrue(answer.accepted.isEmpty() && answer.offered.isEmpty(), "nothing found with ${lines.map { it.state }}")
            assertEquals(emptyMap(), plays.nothing.value, "Play keeps no line when no indexer answered: ${lines.map { it.state }}")

            val why = if (lines.isEmpty()) "No indexers are switched on." else "No indexer answered. ${lines.joinToString(" · ") { "${it.name}: ${it.text}" }}."
            assertEquals(Chosen.Unanswered(why), plays.choose(library, game, game.headline, nav))
            assertEquals(emptyList(), nav.releases, "no list opened")
            assertEquals(emptyMap(), plays.nothing.value, "Choose release keeps no line when no indexer answered: ${lines.map { it.state }}")
        }
    }

    @Test
    fun `Search indexers opens Search with the event's words and the scores note, and searches nothing itself`() = runBlocking<Unit> {
        val search = AnsweringSearch { emptyList() }
        val nav = RecordingNav()
        val plays = Plays(RecordingPlay(), RecordingWatch(), search, this, { NOW }, EmptyCoroutineContext)
        val (library, game) = game()
        val weekend = sports(FORMULA_1 to kestrelWeekend(firstId = 21))

        plays.search(library, game, game.headline, nav)
        plays.search(weekend, weekend.event("ev-000023")!!, weekend.event("ev-000023")!!.headline, nav)
        assertEquals(
            listOf<Pair<String, String?>>("Lantern Bay Copper Crown" to "Indexer results can show scores.", "Formula 1 Kestrel" to "Indexer results can show scores."),
            nav.searches,
        )
        assertEquals(0, search.count.get())
    }

    @Test
    fun `Mark watched and unwatched store the part under its key, with the title its play stores`() = runBlocking<Unit> {
        val watch = RecordingWatch()
        val plays = Plays(RecordingPlay(), watch, AnsweringSearch { emptyList() }, this, { NOW }, EmptyCoroutineContext)
        val library = sports(FORMULA_1 to kestrelWeekend(firstId = 21), UFC to listOf(ufc999()))
        val race = library.event("ev-000025")!!
        val card = library.event("ev-000031")!!

        plays.mark(library, race, race.headline, watched = true).join()
        plays.mark(library, card, card.parts.single { it.id == "prelims" }, watched = false).join()
        assertEquals(
            listOf(
                listOf("ev-000025:full" to "Grand Prix of Kestrel · Race") to true,
                listOf("ev-000031:prelims" to "UFC 999: Quessel vs. Halde · Prelims") to false,
            ),
            watch.marks,
        )
    }

    /** The game of SpPhoneGame, with Lantern Bay FC in the real club's place. */
    private fun game(): Pair<Sports, SportsEvent> {
        val library = sports(PREMIER to listOf(on(PREMIER, 12, at("2026-11-21T15:00"), "Lantern Bay FC vs. Copper Crown United")))
        return library to library.event("ev-000012")!!
    }

    /** An item as one line: key, title, subtitle, runtime, noun, Search's words and note, and the picker's line for no file. */
    private fun described(item: PlayItem): String = listOf(
        item.key, item.title, item.subtitle, item.runtimeMinutes?.toString() ?: "-", item.noun, item.search?.words, item.search?.note,
        item.picker?.noMatch ?: "-",
    ).joinToString(" | ")

    private fun release(name: String) = ReleaseInfo(name, 6_200_000_000, null, "Indexer A", playable = true, whyNot = null, handle = object : ReleaseHandle {})

    /** The user's indexers, answering each search's [words] with [answers], counting the searches and keeping their words and runtimes. */
    private class AnsweringSearch(private val answers: (words: String) -> List<ReleaseInfo>) : PluginSearch {
        val count = AtomicInteger()
        val words: MutableList<String> = CopyOnWriteArrayList()
        val runtimes: MutableList<Int?> = CopyOnWriteArrayList()

        override fun search(words: String, runtimeMinutes: Int?, fresh: Boolean): Flow<Found> = flow {
            count.incrementAndGet()
            this@AnsweringSearch.words += words
            runtimes += runtimeMinutes
            val found = answers(words)
            emit(Found(listOf(IndexerLine("Indexer A", IndexerLine.State.Found, found.size, "${found.size} found")), found))
        }
    }

    /** The user's indexers, none of which answers a search: each says [lines], the host's words for why. */
    private class UnansweredSearch(private val lines: List<IndexerLine>) : PluginSearch {
        override fun search(words: String, runtimeMinutes: Int?, fresh: Boolean): Flow<Found> = flow { emit(Found(lines, emptyList())) }
    }

    /** NZStreamer's play, keeping each item it was handed and the way to ask for its releases. */
    private class RecordingPlay : PluginPlay {
        val items: MutableList<PlayItem> = CopyOnWriteArrayList()
        val releases: MutableList<suspend () -> ItemReleases> = CopyOnWriteArrayList()

        override fun play(item: PlayItem, releases: suspend () -> ItemReleases) {
            items += item
            this.releases += releases
        }

        @Composable
        override fun PlayOnButton(modifier: Modifier) = Unit
    }

    /** The host's watch states: none, keeping each mark. */
    private class RecordingWatch : PluginWatch {
        val marks: MutableList<Pair<List<Pair<String, String>>, Boolean>> = CopyOnWriteArrayList()

        override fun observe(keys: Collection<String>): Flow<Map<String, WatchInfo>> = emptyFlow()

        override suspend fun mark(items: List<Pair<String, String>>, watched: Boolean) {
            marks += items to watched
        }
    }

    /** The host's pages, keeping each Choose release and Search opened. */
    private class RecordingNav : PluginNav {
        class Releases(val item: PlayItem, val accepted: List<ReleaseHandle>, val offered: List<Offered>, val leftOut: String?)

        val releases: MutableList<Releases> = CopyOnWriteArrayList()
        val searches: MutableList<Pair<String, String?>> = CopyOnWriteArrayList()

        override fun open(page: String, arg: String?) = Unit
        override fun back() = Unit
        override fun openSettings() = Unit

        override fun openReleases(item: PlayItem, accepted: List<ReleaseHandle>, offered: List<Offered>, leftOut: String?) {
            releases += Releases(item, accepted, offered, leftOut)
        }

        override fun openSearch(words: String, note: String?) {
            searches += words to note
        }
    }

    private companion object {
        /** Sunday 22 November 2026, 20:41 in London (UTC in November). */
        val NOW: Instant = at("2026-11-22T20:41")

        const val ACCEPTED = "Premier.League.2026.11.21.Lantern.Bay.FC.vs.Copper.Crown.United.1080p50.WEB.h264-KESTREL"
        const val UNDATED = "Lantern.Bay.FC.vs.Copper.Crown.United.1080p.WEB.h264-DRIFTWOOD"
        const val SCORED = "Lantern.Bay.FC.3.1.Copper.Crown.United.2026.11.21.720p.WEB.h264-WREN"

        /** The host's words for an indexer its cap paused. */
        const val PAUSED = "Paused: too many searches from Sportarr"
    }
}
