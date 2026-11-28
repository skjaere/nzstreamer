package app.nzstreamer.plugin.sportarr.ui

import app.nzstreamer.plugin.sportarr.Session
import app.nzstreamer.plugin.sportarr.bouts.Bout
import app.nzstreamer.plugin.sportarr.bouts.BoutCard
import app.nzstreamer.plugin.sportarr.bouts.BoutPart
import app.nzstreamer.plugin.sportarr.bouts.CardState
import app.nzstreamer.plugin.sportarr.bouts.Cards
import app.nzstreamer.plugin.sportarr.bouts.CardsOption
import app.nzstreamer.plugin.sportarr.bouts.Fixtures
import app.nzstreamer.plugin.sportarr.bouts.Form
import app.nzstreamer.plugin.sportarr.bouts.IndexFile
import app.nzstreamer.plugin.sportarr.bouts.KeptCards
import app.nzstreamer.plugin.sportarr.bouts.PageFinder
import app.nzstreamer.plugin.sportarr.bouts.TestWiki
import app.nzstreamer.plugin.sportarr.bouts.WIKI_BASE
import app.nzstreamer.plugin.sportarr.bouts.WikipediaReads
import app.nzstreamer.plugin.sportarr.model.BOXING
import app.nzstreamer.plugin.sportarr.model.FORMULA_1
import app.nzstreamer.plugin.sportarr.model.PREMIER
import app.nzstreamer.plugin.sportarr.model.SKIING
import app.nzstreamer.plugin.sportarr.model.Sports
import app.nzstreamer.plugin.sportarr.model.UFC
import app.nzstreamer.plugin.sportarr.model.at
import app.nzstreamer.plugin.sportarr.model.kestrelWeekend
import app.nzstreamer.plugin.sportarr.model.on
import app.nzstreamer.plugin.sportarr.model.sports
import app.nzstreamer.plugin.sportarr.model.ufc999
import app.nzstreamer.plugin.sportarr.model.watched
import app.nzstreamer.plugin.sportarr.source.Connection
import app.nzstreamer.plugin.sportarr.source.Failure
import app.nzstreamer.plugin.sportarr.source.ListRead
import app.nzstreamer.plugin.sportarr.source.ListReader
import app.nzstreamer.plugin.sportarr.source.ListState
import app.nzstreamer.plugin.sportarr.source.MapSecrets
import app.nzstreamer.plugin.sportarr.source.RecordingHttp
import app.nzstreamer.plugin.sportarr.source.SportarrClient
import app.nzstreamer.plugin.sportarr.source.TestSportarr
import app.nzstreamer.plugin.sportarr.source.league
import app.nzstreamer.plugin.sportarr.source.loopbackSafety
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.skjaere.usenet.plugin.AddressSafety
import io.skjaere.usenet.plugin.Found
import io.skjaere.usenet.plugin.IndexerLine
import io.skjaere.usenet.plugin.ItemReleases
import io.skjaere.usenet.plugin.Offered
import io.skjaere.usenet.plugin.PlayItem
import io.skjaere.usenet.plugin.PluginHost
import io.skjaere.usenet.plugin.PluginHttp
import io.skjaere.usenet.plugin.PluginNav
import io.skjaere.usenet.plugin.PluginPlay
import io.skjaere.usenet.plugin.PluginSearch
import io.skjaere.usenet.plugin.PluginSecrets
import io.skjaere.usenet.plugin.PluginWatch
import io.skjaere.usenet.plugin.ReleaseHandle
import io.skjaere.usenet.plugin.WatchInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The event pages as data (L644, L647, L652, L653), frame by frame as the phone's and TV's game, weekend and card
 * designs draw them, with Generic's page and the TV card's, which none draws: each page's title and lines, its buttons
 * and the part each plays, its parts with their ⋮, its footnote, and a TV's first focus. The top button goes on from the
 * last part watched; the count is a card's parts or a weekend's sessions begun; a page whose event left the list says so.
 * Play or Choose release finding nothing leaves a line on the page until a release of it plays; Choose release says it is
 * searching and takes no second press; nothing is searched as a page opens; no page holds anything Sportarr sends besides
 * titles, leagues, rounds and times.
 *
 * A UFC card's page (L-B7, BtPhoneCard, BtPhoneCardStates, BtTvCard): each part's bouts under its row in running order,
 * the credit and the bouts' footnote; a bout plays its part (W5); the line of each state in the credit's place with
 * today's footnote; none at all while the card isn't known; the card read once as the page shows; the TV's first focus as
 * before; a card read before the event and after it, its winners moved left, draws the same page (W6).
 */
class PageModelTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `a game's page, aired and not watched`() {
        assertEquals(
            listOf(
                GAME,
                "Premier League · Sat 21 Nov · 15:00",
                "Not watched [muted]",
                "[Play]* [Choose release] [Search indexers]",
                "Mark watched",
                GAME_FOOT,
                "focus: play",
            ),
            lines(page(GAMES, Pages.EVENT, "ev-000012")),
        )
    }

    @Test
    fun `a game's page, played halfway`() {
        val halfway = mapOf("ev-000012:full" to WatchInfo(4_080_000, 6_600_000, false, at("2026-11-22T19:00")))
        assertEquals(
            listOf(
                GAME,
                "Premier League · Sat 21 Nov · 15:00",
                "42 min left [accent] ▬",
                "[Resume]* [Choose release] [Search indexers]",
                "Mark watched",
                GAME_FOOT,
                "focus: play",
            ),
            lines(page(GAMES, Pages.EVENT, "ev-000012", halfway)),
        )
    }

    @Test
    fun `a game's page before it starts, with nothing to play or search yet`() {
        assertEquals(
            listOf(
                "Wrenford Albion vs. Lantern Bay FC",
                "Premier League",
                "Starts Sat 15:00 [muted]",
                "Play, Choose release and Search indexers show once it has started. Releases often appear a few hours after the end.",
                GAME_FOOT,
                "focus: held",
            ),
            lines(page(GAMES, Pages.EVENT, "ev-000014")),
        )
    }

    @Test
    fun `a game's page once watched`() {
        assertEquals(
            listOf(
                GAME,
                "Premier League · Sat 21 Nov · 15:00",
                "✓ Watched [good]",
                "[Play again]* [Choose release] [Search indexers]",
                "Mark unwatched",
                GAME_FOOT,
                "focus: play",
            ),
            lines(page(GAMES, Pages.EVENT, "ev-000012", mapOf("ev-000012:full" to watched()))),
        )
    }

    @Test
    fun `a game's page on a TV is the phone's, the focus first on Play, Play again, or the line before the start`() {
        val states = listOf(emptyMap(), mapOf("ev-000012:full" to watched()))
        states.forEach { watch -> assertEquals(lines(page(GAMES, Pages.EVENT, "ev-000012", watch)), lines(page(GAMES, Pages.EVENT, "ev-000012", watch, tv = true))) }
        assertEquals("focus: play", lines(page(GAMES, Pages.EVENT, "ev-000012", mapOf("ev-000012:full" to watched()), tv = true)).last())
        assertEquals("focus: held", lines(page(GAMES, Pages.EVENT, "ev-000014", tv = true)).last())
    }

    @Test
    fun `a weekend's page on Saturday evening, the qualifying half watched and the race to come`() {
        assertEquals(
            listOf(
                "Grand Prix of Kestrel",
                "Formula 1 · Round 20 · 20–22 Nov",
                "2 of 4 watched ▬",
                "[Resume Qualifying · 18:22]*",
                "  Practice 1 | Fri 20 Nov · 12:30 | ✓ | ⋮ Choose release, Search indexers, Mark unwatched",
                "  Practice 2 | Fri 20 Nov · 16:00 | ✓ | ⋮ Choose release, Search indexers, Mark unwatched",
                "  Practice 3 | Sat 21 Nov · 11:30 | ⋮ Choose release, Search indexers, Mark watched",
                "> Qualifying | Sat 21 Nov · 15:00 | 32:10 left ▬ | ⋮ Choose release, Search indexers, Mark watched",
                "  Race | Starts Sun 15:00",
                WEEKEND_FOOT,
                "focus: play",
            ),
            lines(page(WEEKEND, Pages.WEEKEND, "2:r20", SATURDAY_EVENING, now = at("2026-11-21T20:41"))),
        )
    }

    @Test
    fun `a weekend's page after the race goes on to the race, as a show's page goes on to the next episode`() {
        assertEquals(
            listOf(
                "Grand Prix of Kestrel",
                "Formula 1 · Round 20 · 20–22 Nov",
                "3 of 5 watched ▬",
                "[Play Race]*",
                "  Practice 1 | Fri 20 Nov · 12:30 | ✓ | ⋮ Choose release, Search indexers, Mark unwatched",
                "  Practice 2 | Fri 20 Nov · 16:00 | ✓ | ⋮ Choose release, Search indexers, Mark unwatched",
                "  Practice 3 | Sat 21 Nov · 11:30 | ⋮ Choose release, Search indexers, Mark watched",
                "  Qualifying | Sat 21 Nov · 15:00 | ✓ | ⋮ Choose release, Search indexers, Mark unwatched",
                "> Race | Sun 22 Nov · 15:00 | ⋮ Choose release, Search indexers, Mark watched",
                WEEKEND_FOOT,
                "focus: play",
            ),
            lines(page(WEEKEND, Pages.WEEKEND, "2:r20", AFTER_THE_RACE, now = MONDAY)),
        )
    }

    @Test
    fun `a weekend's page once its race is watched, with nothing to go on to`() {
        assertEquals(
            listOf(
                "Grand Prix of Kestrel",
                "Formula 1 · Round 20 · 20–22 Nov",
                "✓ Watched [good]",
                "4 of 5 watched ▬",
                "  Practice 1 | Fri 20 Nov · 12:30 | ✓ | ⋮ Choose release, Search indexers, Mark unwatched",
                "  Practice 2 | Fri 20 Nov · 16:00 | ✓ | ⋮ Choose release, Search indexers, Mark unwatched",
                "  Practice 3 | Sat 21 Nov · 11:30 | ⋮ Choose release, Search indexers, Mark watched",
                "  Qualifying | Sat 21 Nov · 15:00 | ✓ | ⋮ Choose release, Search indexers, Mark unwatched",
                "  Race | Sun 22 Nov · 15:00 | ✓ | ⋮ Choose release, Search indexers, Mark unwatched",
                WEEKEND_FOOT,
                "focus: row:ev-000023:full",
            ),
            lines(page(WEEKEND, Pages.WEEKEND, "2:r20", AFTER_THE_RACE + ("ev-000025:full" to watched()), now = MONDAY)),
        )
    }

    @Test
    fun `a weekend's page on a TV, Choose release for the session the top button plays, the focus first on that button`() {
        assertEquals(
            listOf(
                "Grand Prix of Kestrel",
                "Formula 1 · Round 20 · 20–22 Nov",
                "2 of 4 watched ▬",
                "[Resume Qualifying · 18:22]* [Choose release]",
                "  Practice 1 | Fri 20 Nov · 12:30 | ✓ | ⋮ Choose release, Search indexers, Mark unwatched",
                "  Practice 2 | Fri 20 Nov · 16:00 | ✓ | ⋮ Choose release, Search indexers, Mark unwatched",
                "  Practice 3 | Sat 21 Nov · 11:30 | ⋮ Choose release, Search indexers, Mark watched",
                "> Qualifying | Sat 21 Nov · 15:00 | 32:10 left ▬ | ⋮ Choose release, Search indexers, Mark watched",
                "  Race | Starts Sun 15:00",
                "Press OK on a session to play the best release, or ⋮ to choose one yourself.",
                "focus: play",
            ),
            lines(page(WEEKEND, Pages.WEEKEND, "2:r20", SATURDAY_EVENING, now = at("2026-11-21T20:41"), tv = true)),
        )
        val monday = page(WEEKEND, Pages.WEEKEND, "2:r20", AFTER_THE_RACE, now = MONDAY, tv = true)
        assertEquals("[Play Race]* [Choose release]", lines(monday)[3])
        val screen = assertIs<EventScreen>(monday)
        assertEquals(listOf("ev-000025:full", "ev-000025:full"), screen.buttons.map { it.part }, "both buttons are for the race")
    }

    @Test
    fun `a weekend's page before its first session, the line holding a TV's focus`() {
        assertEquals(
            listOf(
                "Grand Prix of Tarnholm",
                "Formula 1 · Round 21 · 27–29 Nov",
                "Starts Fri 12:30 [muted]",
                "The sessions play once each has started. Releases often appear a few hours after the end.",
                "  Practice 1 | Starts Fri 12:30",
                "  Qualifying | Starts Sat 15:00",
                "  Race | Starts 29 Nov 15:00",
                WEEKEND_FOOT,
                "focus: held",
            ),
            lines(page(WEEKEND, Pages.WEEKEND, "2:r21")),
        )
    }

    @Test
    fun `a card's page with its prelims half watched`() {
        assertEquals(
            listOf(
                "UFC 999: Quessel vs. Halde",
                "UFC · Sat 21 Nov · 23:00",
                "1 of 3 watched ▬",
                "[Resume Prelims · 37:45]*",
                "  Early Prelims | ✓ | ⋮ Choose release, Search indexers, Mark unwatched",
                "> Prelims | 52:10 left ▬ | ⋮ Choose release, Search indexers, Mark watched",
                "  Main Card | ⋮ Choose release, Search indexers, Mark watched",
                CARD_FOOT,
                "focus: play",
            ),
            lines(page(CARDS, Pages.EVENT, "ev-000031", PRELIMS_HALFWAY)),
        )
    }

    @Test
    fun `a card's page once its main card is watched, its early prelims still to play`() {
        val watch = mapOf("ev-000031:prelims" to watched(), "ev-000031:main-card" to watched())
        assertEquals(
            listOf(
                "UFC 999: Quessel vs. Halde",
                "UFC · Sat 21 Nov · 23:00",
                "✓ Watched [good]",
                "2 of 3 watched ▬",
                "  Early Prelims | ⋮ Choose release, Search indexers, Mark watched",
                "  Prelims | ✓ | ⋮ Choose release, Search indexers, Mark unwatched",
                "  Main Card | ✓ | ⋮ Choose release, Search indexers, Mark unwatched",
                CARD_FOOT,
                "focus: row:ev-000031:early-prelims",
            ),
            lines(page(CARDS, Pages.EVENT, "ev-000031", watch)),
        )
    }

    @Test
    fun `a card's page before it starts, its parts shown but not playable`() {
        assertEquals(
            listOf(
                "UFC Fight Night: Brisk vs. Loam",
                "UFC",
                "Starts Sat 19:00 [muted]",
                "The parts play once the card has started. Releases often appear a few hours after the end.",
                "  Prelims",
                "  Main Card",
                CARD_FOOT,
                "focus: held",
            ),
            lines(page(CARDS, Pages.EVENT, "ev-000032")),
        )
    }

    @Test
    fun `a card's page on a TV, as a weekend's, the focus on the top button, else on the first part not watched`() {
        val tv = lines(page(CARDS, Pages.EVENT, "ev-000031", PRELIMS_HALFWAY, tv = true))
        assertEquals("[Resume Prelims · 37:45]* [Choose release]", tv[3])
        assertEquals("Press OK on a part to play the best release, or ⋮ to choose one yourself.", tv[tv.lastIndex - 1])
        assertEquals("focus: play", tv.last())
        val watch = mapOf("ev-000031:prelims" to watched(), "ev-000031:main-card" to watched())
        assertEquals("focus: row:ev-000031:early-prelims", lines(page(CARDS, Pages.EVENT, "ev-000031", watch, tv = true)).last())
        assertEquals("focus: held", lines(page(CARDS, Pages.EVENT, "ev-000032", tv = true)).last())
    }

    @Test
    fun `a one-part fight's page is a game's, its footnote naming the event`() {
        val fight = sports(BOXING to listOf(on(BOXING, 33, at("2026-11-21T22:00"), "Quessel vs. Halde")))
        assertEquals(
            listOf(
                "Quessel vs. Halde",
                "Kestrel Boxing · Sat 21 Nov · 22:00",
                "Not watched [muted]",
                "[Play]* [Choose release] [Search indexers]",
                "Mark watched",
                "Play finds the best release on your indexers that names this event and its date; the one that played before goes first. Details from your Sportarr.",
                "focus: play",
            ),
            lines(page(fight, Pages.EVENT, "ev-000033")),
        )
    }

    @Test
    fun `Generic's page puts Choose release in Play's place, Resume while it is played, and says you pick`() {
        val downhill = "ev-000034"
        assertEquals(
            listOf(
                "Kestrel · Men's Downhill",
                "FIS Alpine Ski World Cup · Sat 21 Nov · 10:30",
                "Not watched [muted]",
                "You pick the release",
                "[Choose release]* [Search indexers]",
                "Mark watched",
                GENERIC_FOOT,
                "focus: choose",
            ),
            lines(page(SKI, Pages.EVENT, downhill)),
        )
        val played = mapOf("$downhill:full" to WatchInfo(1_200_000, 5_400_000, false, at("2026-11-22T12:00")))
        assertEquals(
            listOf("1 h 10 min left [accent] ▬", "You pick the release", "[Resume]* [Choose release] [Search indexers]", "Mark watched"),
            lines(page(SKI, Pages.EVENT, downhill, played)).subList(2, 6),
        )
        assertEquals("focus: play", lines(page(SKI, Pages.EVENT, downhill, played, tv = true)).last())
        assertEquals(
            listOf("✓ Watched [good]", "You pick the release", "[Choose release]* [Search indexers]", "Mark unwatched"),
            lines(page(SKI, Pages.EVENT, downhill, mapOf("$downhill:full" to watched()))).subList(2, 6),
        )
        assertEquals(
            listOf(
                "Kestrel · Women's Giant Slalom",
                "FIS Alpine Ski World Cup",
                "Starts Sat 10:30 [muted]",
                "You pick the release",
                "Choose release and Search indexers show once it has started. Releases often appear a few hours after the end.",
                GENERIC_FOOT,
                "focus: held",
            ),
            lines(page(SKI, Pages.EVENT, "ev-000035", tv = true)),
        )
    }

    @Test
    fun `the top button goes on from the last part watched, never back to one skipped`() {
        fun top(watch: Map<String, WatchInfo>, now: Instant) = lines(page(WEEKEND, Pages.WEEKEND, "2:r20", watch, now = now))[3]
        val saturdayNoon = at("2026-11-21T12:00")
        assertEquals("[Play Practice 1]*", top(emptyMap(), saturdayNoon), "nothing watched: the first session begun")
        assertEquals("[Play Practice 3]*", top(mapOf("ev-000022:full" to watched()), saturdayNoon), "the first practice skipped stays skipped")
        val twoBegun = mapOf(
            "ev-000023:full" to WatchInfo(600_000, 3_900_000, false, at("2026-11-21T13:00")),
            "ev-000024:full" to WatchInfo(1_102_000, 3_032_000, false, at("2026-11-21T19:30")),
        )
        assertEquals("[Resume Qualifying · 18:22]*", top(twoBegun, at("2026-11-21T20:41")), "the one played last")
        // Friday evening, both practices watched: the third is still to come, so nothing goes on yet.
        val friday = lines(page(WEEKEND, Pages.WEEKEND, "2:r20", mapOf("ev-000021:full" to watched(), "ev-000022:full" to watched()), now = at("2026-11-20T20:41")))
        assertEquals("2 of 2 watched ▬", friday[2])
        assertTrue(friday.none { it.startsWith("[") }, friday.toString())
        assertEquals("focus: row:ev-000021:full", friday.last())
        val raceWatched = lines(page(WEEKEND, Pages.WEEKEND, "2:r20", mapOf("ev-000025:full" to watched(), "ev-000023:full" to WatchInfo(600_000, 3_900_000, false, NOW)), now = MONDAY))
        assertTrue(raceWatched.none { it.startsWith("[") }, "the race watched, a practice half watched resumes from its row alone: $raceWatched")
    }

    @Test
    fun `a card counts its parts, a weekend its sessions begun`() {
        assertEquals("0 of 3 watched ▬", lines(page(CARDS, Pages.EVENT, "ev-000031"))[2])
        assertEquals("[Play Early Prelims]*", lines(page(CARDS, Pages.EVENT, "ev-000031"))[3])
        assertEquals("0 of 4 watched ▬", lines(page(WEEKEND, Pages.WEEKEND, "2:r20", now = at("2026-11-21T20:41")))[2])
    }

    @Test
    fun `a page whose event left the list says so, with Back`() {
        val gone = listOf("This event is no longer in your Sportarr's list.", "[Back]*", "focus: back")
        assertEquals(gone, lines(page(GAMES, Pages.EVENT, "ev-000999")))
        assertEquals(gone, lines(page(WEEKEND, Pages.WEEKEND, "2:r19")))
        assertEquals(gone, lines(PageScreens.of(ListState.NothingFollowed, Pages.EVENT, "ev-000012", emptyMap(), emptyMap(), null, NOW, LONDON, tv = false)))
        assertEquals(gone, lines(PageScreens.of(ListState.Unreachable(null, Failure.NoAnswer), Pages.EVENT, "ev-000012", emptyMap(), emptyMap(), null, NOW, LONDON, tv = true)))
        assertNull(PageScreens.of(ListState.Loading, Pages.EVENT, "ev-000012", emptyMap(), emptyMap(), null, NOW, LONDON, tv = false), "nothing to say while the list loads")
        // A list kept through a failed read still has the event.
        assertEquals(GAME, lines(PageScreens.of(ListState.Unreachable(GAMES, Failure.NoAnswer), Pages.EVENT, "ev-000012", emptyMap(), emptyMap(), null, NOW, LONDON, tv = false)).first())
    }

    @Test
    fun `a session's own page is its weekend's`() {
        assertEquals(lines(page(WEEKEND, Pages.WEEKEND, "2:r20")), lines(page(WEEKEND, Pages.EVENT, "ev-000023")))
    }

    @Test
    fun `Back from Choose release puts a TV's focus on what opened it, Choose release or a part's ⋮`() {
        val game = assertIs<EventScreen>(page(GAMES, Pages.EVENT, "ev-000012", tv = true))
        assertEquals(PageKeys.CHOOSE, game.buttons.single { it.action == PageAction.Choose }.key)
        val weekend = assertIs<EventScreen>(page(WEEKEND, Pages.WEEKEND, "2:r20", SATURDAY_EVENING, now = at("2026-11-21T20:41"), tv = true))
        assertEquals(PageKeys.CHOOSE, weekend.buttons.single { it.action == PageAction.Choose }.key)
        val keys = weekend.parts.map { PageKeys.more(it.part) } + weekend.parts.map { PageKeys.row(it.part) } + weekend.buttons.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "each element its own key, for the focus to come back to: $keys")
    }

    @Test
    fun `Choose release says it is searching, and takes no second press`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val search = GatedSearch(gate)
        val plays = Plays(RecordingPlay(), MutableWatch(), search, this, { NOW }, EmptyCoroutineContext)
        val model = PageModel(MutableStateFlow(ListState.Ready(GAMES)), MutableWatch(), plays, Pages.EVENT, "ev-000012", tv = false, { NOW }, { LONDON }, EmptyCoroutineContext)
        val running = launch { model.run() }
        model.next { true }
        val nav = RecordingNav()

        val choosing = launch { model.choose("ev-000012:full", nav) }
        assertEquals("[Play]* [Searching your indexers…] [Search indexers]", lines(model.next { "[Play]* [Searching your indexers…] [Search indexers]" in lines(it) })[3])
        val asked = search.count.get()
        model.choose("ev-000012:full", nav)
        assertEquals(asked, search.count.get(), "a second press searches nothing more")
        gate.complete(Unit)
        choosing.join()
        model.next { "[Play]* [Choose release] [Search indexers]" in lines(it) }
        running.cancelAndJoin()
    }

    @Test
    fun `while Choose release searches, Play and a part's row play nothing`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val play = RecordingPlay()
        val plays = Plays(play, MutableWatch(), GatedSearch(gate), this, { MONDAY }, EmptyCoroutineContext)
        val model = PageModel(MutableStateFlow(ListState.Ready(WEEKEND)), MutableWatch(), plays, Pages.WEEKEND, "2:r20", tv = false, { MONDAY }, { LONDON }, EmptyCoroutineContext)
        val running = launch { model.run() }
        model.next { true }

        val choosing = launch { model.choose("ev-000025:full", RecordingNav()) }
        model.next { screen -> (screen as EventScreen).parts.any { it.searching } }
        model.play("ev-000025:full")
        model.play("ev-000021:full")
        repeat(10) { kotlinx.coroutines.yield() }
        assertEquals(emptyList(), play.items, "nothing handed to the host while Choose release searches")

        gate.complete(Unit)
        choosing.join()
        model.play("ev-000025:full")
        withTimeout(WAIT) { while (play.items.isEmpty()) kotlinx.coroutines.yield() }
        assertEquals("ev-000025:full", play.items.single().key, "once it is done, Play plays")
        running.cancelAndJoin()
    }

    @Test
    fun `Play finding nothing leaves the page's line until a release of it plays`() = runBlocking<Unit> {
        val play = RecordingPlay()
        val watch = MutableWatch()
        val monday = at("2026-11-23T20:41")
        val plays = Plays(play, watch, AnsweringSearch(), this, { monday }, EmptyCoroutineContext)
        val model = PageModel(MutableStateFlow(ListState.Ready(GAMES)), watch, plays, Pages.EVENT, "ev-000013", tv = false, { monday }, { LONDON }, EmptyCoroutineContext)
        val running = launch { model.run() }
        assertEquals(listOf("[Play]* [Choose release] [Search indexers]", "Mark watched"), lines(model.next { true }).subList(3, 5))

        model.play("ev-000013:full")
        withTimeout(WAIT) { while (play.releases.isEmpty()) kotlinx.coroutines.yield() }
        play.releases.single()()
        val kept = "No release at 20:41. Releases often appear a few hours after a game ends."
        assertEquals(listOf("[Play]* [Choose release] [Search indexers]", kept, "Mark watched"), lines(model.next { kept in lines(it) }).subList(3, 6))

        // The page made again (Back, then the row again) keeps it: it lives as long as the plugin runs.
        val again = PageModel(MutableStateFlow(ListState.Ready(GAMES)), watch, plays, Pages.EVENT, "ev-000013", tv = false, { monday }, { LONDON }, EmptyCoroutineContext)
        val second = launch { again.run() }
        again.next { kept in lines(it) }
        second.cancelAndJoin()

        // A release of it plays: the host keeps when.
        watch.states.value = mapOf("ev-000013:full" to WatchInfo(60_000, 6_600_000, false, monday.plus(Duration.ofMinutes(5))))
        model.next { kept !in lines(it) }
        running.cancelAndJoin()
    }

    @Test
    fun `Choose release finding nothing on a weekend's session leaves the line for a session`() = runBlocking<Unit> {
        val plays = Plays(RecordingPlay(), MutableWatch(), AnsweringSearch(), this, { MONDAY }, EmptyCoroutineContext)
        val model = PageModel(MutableStateFlow(ListState.Ready(WEEKEND)), MutableWatch(), plays, Pages.WEEKEND, "2:r20", tv = false, { MONDAY }, { LONDON }, EmptyCoroutineContext)
        val running = launch { model.run() }
        model.next { true }
        val nav = RecordingNav()
        model.choose("ev-000023:full", nav)
        assertEquals(emptyList(), nav.opened, "nothing at all opens nothing")
        model.next { "No release at 20:41. Releases often appear a few hours after a session ends." in lines(it) }
        running.cancelAndJoin()
    }

    @Test
    fun `the line names the day it was left on, once that isn't today`() {
        // Play found nothing on Saturday evening; the plugin, and its line, live on into the next days.
        val saturday = at("2026-11-21T20:41")
        val nothing = mapOf("ev-000012:full" to saturday)
        fun kept(now: Instant) = assertIs<EventScreen>(PageScreens.of(ListState.Ready(GAMES), Pages.EVENT, "ev-000012", emptyMap(), nothing, null, now, LONDON, tv = false)).kept
        val often = "Releases often appear a few hours after a game ends."
        assertEquals("No release at 20:41. $often", kept(at("2026-11-21T23:30")))
        assertEquals("No release yesterday at 20:41. $often", kept(at("2026-11-22T18:00")))
        assertEquals("No release on Sat 21 Nov at 20:41. $often", kept(at("2026-11-24T09:00")))
    }

    @Test
    fun `on a weekend's page, a later play of any of its sessions ends the line one of them left`() {
        // Practice 3's Choose release found nothing at noon on Saturday; that evening the qualifying plays from the top button.
        val noon = at("2026-11-21T12:00")
        val kept = "No release at 12:00. Releases often appear a few hours after a session ends."
        val nothing = mapOf("ev-000023:full" to noon)
        val before = PageScreens.of(ListState.Ready(WEEKEND), Pages.WEEKEND, "2:r20", emptyMap(), nothing, null, at("2026-11-21T17:00"), LONDON, tv = false)
        assertEquals(kept, assertIs<EventScreen>(before).kept)
        val played = mapOf("ev-000024:full" to WatchInfo(1_102_000, 3_032_000, false, at("2026-11-21T19:30")))
        val after = PageScreens.of(ListState.Ready(WEEKEND), Pages.WEEKEND, "2:r20", played, nothing, null, at("2026-11-21T20:00"), LONDON, tv = false)
        assertNull(assertIs<EventScreen>(after).kept, "a release of the weekend played since")
    }

    @Test
    fun `Choose release no indexer answered shows the indexers' words for a moment, and keeps no line`() = runBlocking<Unit> {
        val paused = object : PluginSearch {
            override fun search(words: String, runtimeMinutes: Int?, fresh: Boolean): Flow<Found> =
                flow { emit(Found(listOf(IndexerLine("Indexer A", IndexerLine.State.Limited, 0, "Paused: too many searches from Sportarr")), emptyList())) }
        }
        val monday = at("2026-11-23T20:41")
        val plays = Plays(RecordingPlay(), MutableWatch(), paused, this, { monday }, EmptyCoroutineContext)
        val model = PageModel(MutableStateFlow(ListState.Ready(GAMES)), MutableWatch(), plays, Pages.EVENT, "ev-000013", tv = false, { monday }, { LONDON }, EmptyCoroutineContext)
        val running = launch { model.run() }
        model.next { true }
        val nav = RecordingNav()

        model.choose("ev-000013:full", nav)
        assertEquals(emptyList(), nav.opened, "nothing to open")
        val words = "No indexer answered. Indexer A: Paused: too many searches from Sportarr."
        assertEquals(listOf("[Play]* [Choose release] [Search indexers]", words, "Mark watched"), lines(model.next { words in lines(it) }).subList(3, 6))
        assertEquals(emptyMap(), plays.nothing.value, "no line kept for the plugin's run")

        // The page again (Back, then the row): nothing said of releases that may well be there.
        val again = PageModel(MutableStateFlow(ListState.Ready(GAMES)), MutableWatch(), plays, Pages.EVENT, "ev-000013", tv = false, { monday }, { LONDON }, EmptyCoroutineContext)
        val second = launch { again.run() }
        assertEquals(listOf("[Play]* [Choose release] [Search indexers]", "Mark watched"), lines(again.next { true }).subList(3, 5))
        second.cancelAndJoin()
        running.cancelAndJoin()
    }

    @Test
    fun `nothing is searched as a page opens, and Play asks the host alone`() = runBlocking<Unit> {
        val now = Instant.now()
        val game = on(PREMIER, 1, now.minus(Duration.ofHours(3)), "Lantern Bay FC vs. Copper Crown United")
        val sportarr = TestSportarr(listOf(league(PREMIER.id, PREMIER.name, "Soccer")), mapOf(PREMIER.id to listOf(game)))
        val host = FakeHost(sportarr.http, File(dir, "plugin"))
        try {
            val session = Session(host, WIKI_BASE)
            withTimeout(WAIT) { session.refresh.shown().join() }
            val model = session.page(Pages.EVENT, "ev-000001", tv = false)
            val running = launch { model.run() }
            val screen = assertIs<EventScreen>(model.next { it is EventScreen })
            assertEquals("Lantern Bay FC vs. Copper Crown United", screen.title)
            host.settle()
            assertEquals(0, host.searches.get(), "nothing searched as the page opens")

            model.play("ev-000001:full")
            withTimeout(WAIT) { while (host.played.isEmpty()) kotlinx.coroutines.yield() }
            assertEquals("ev-000001:full", host.played.single().key)
            host.settle()
            assertEquals(0, host.searches.get(), "the host searches once the play asks for releases, not the page")
            running.cancelAndJoin()
        } finally {
            host.close()
        }
    }

    @Test
    fun `no page holds a canary value, read from Sportarr with every one of them sent`() = runBlocking<Unit> {
        val leagues = listOf(PREMIER, FORMULA_1, UFC, BOXING, SKIING)
        val events = GAMES.events + WEEKEND.events + CARDS.events + SKI.events
        val wire = GAMES_WIRE + WEEKEND_WIRE + CARDS_WIRE + SKI_WIRE + on(BOXING, 33, at("2026-11-21T22:00"), "Quessel vs. Halde")
        val sportarr = TestSportarr(leagues.map { league(it.id, it.name, it.sport) }, wire.groupBy { it.leagueId ?: 0 })
        val read = ListReader(
            SportarrClient(sportarr.http, "http://127.0.0.1:1867", "kestrel0123456789abcdef0123456789", ::loopbackSafety, EmptyCoroutineContext),
            NOW, LONDON, work = EmptyCoroutineContext,
        ).read()
        val library = Sports.of(assertIs<ListRead.Read>(read).list)
        assertTrue(events.size <= library.events.size, "every event read")
        val watch = library.events.flatMap { it.parts }.withIndex().associate { (i, part) ->
            part.key to if (i % 2 == 0) watched() else WatchInfo(600_000, 6_000_000, false, NOW)
        }
        val nothing = library.events.flatMap { it.parts }.associate { it.key to NOW.minus(Duration.ofMinutes(1)) }
        val shown = listOf(false, true).flatMap { tv ->
            library.events.map { PageScreens.of(ListState.Ready(library), Pages.EVENT, it.key, watch, nothing, null, NOW, LONDON, tv) } +
                library.weekends.map { PageScreens.of(ListState.Ready(library), Pages.WEEKEND, it.key, emptyMap(), emptyMap(), it.items.first().part.key, NOW, LONDON, tv) }
        }
        val text = shown.joinToString("\n")
        assertTrue(GAME in text && "Grand Prix of Kestrel" in text && "UFC 999" in text, "the pages show")
        val leaked = TestSportarr.CANARY_VALUES.filter { text.contains(it, ignoreCase = true) }
        assertTrue(leaked.isEmpty(), "canary values in the pages: $leaked")
    }

    @Test
    fun `a UFC card's bouts show under each part in running order, the main event last, with the credit and the bouts' footnote`() {
        val prelims = "⋮ Choose release, Search indexers, Mark watched"
        assertEquals(
            listOf(
                "UFC 999: Quessel vs. Halde",
                "UFC · Sat 21 Nov · 23:00",
                "1 of 3 watched ▬",
                "[Resume Prelims · 37:45]*",
                "  Early Prelims | ✓ | ⋮ Choose release, Search indexers, Mark unwatched",
                "    Ambrel vs. Savrin | Flyweight | ▶ Play Early Prelims",
                "    Durnay vs. Wexmoor | Women's Strawweight | ▶ Play Early Prelims",
                "    Kjelsen vs. Tollerup | Welterweight | ▶ Play Early Prelims",
                "    Brannoc Marhoff vs. Elsvin Marhoff | Bantamweight | ▶ Play Early Prelims",
                "> Prelims | 52:10 left ▬ | $prelims",
                "    Ferlund vs. Tarnoy | Bantamweight | ▶ Play Prelims",
                "    Grisholm vs. Pellicot | Light Heavyweight | ▶ Play Prelims",
                "    Kestvik vs. Velmar | Featherweight | ▶ Play Prelims",
                "    Brenneck vs. Marhoff | Middleweight | ▶ Play Prelims",
                "  Main Card | $prelims",
                "    Lindqvar vs. Zelinek | Heavyweight | ▶ Play Main Card",
                "    Ostrem vs. Tessmar | Women’s Flyweight | ▶ Play Main Card",
                "    Rendahl vs. Varnholt | Welterweight | ▶ Play Main Card",
                "    Brisk vs. Loam | Bantamweight | Title fight | ▶ Play Main Card",
                "    Quessel vs. Halde | Lightweight | Title fight | Main event | ▶ Play Main Card",
                "ⓘ Fight card from Wikipedia | UFC 999 → $WIKI_BASE/wiki/UFC_999",
                BOUTS_FOOT,
                "focus: play",
            ),
            lines(card(CardState.Bouts(Fixtures.UFC_999_CARD))),
        )
    }

    @Test
    fun `a bout plays its part, as its row does, and says which part it plays, each bout with its own key`() {
        val screen = assertIs<EventScreen>(card(CardState.Bouts(Fixtures.UFC_999_CARD)))
        screen.parts.forEach { row ->
            assertEquals(listOf(Words.SportsList.play(row.label)), row.bouts.map { it.play }.distinct(), "${row.label}'s bouts play it")
            assertEquals(row.bouts.indices.map { PageKeys.bout(row.part, it) }, row.bouts.map { it.key })
        }
        assertEquals("Play Prelims", screen.parts.single { it.part == "ev-000031:prelims" }.bouts.first().play)
        val keys = screen.parts.flatMap { row -> row.bouts.map { it.key } + PageKeys.row(row.part) + PageKeys.more(row.part) } +
            screen.buttons.map { it.key } + PageKeys.CREDIT + PageKeys.CARD_BUTTON + PageKeys.HELD
        assertEquals(keys.size, keys.toSet().size, "each element its own key, for the focus to come back to: $keys")
    }

    @Test
    fun `before the start the bouts show as their parts do, none played, with the credit and the bouts' footnote`() {
        assertEquals(
            listOf(
                "UFC Fight Night: Brisk vs. Loam",
                "UFC",
                "Starts Sat 19:00 [muted]",
                "The parts play once the card has started. Releases often appear a few hours after the end.",
                "  Prelims",
                "    Ostrem vs. Wexmoor | Women's Strawweight",
                "    Kestvik vs. Savrin | Featherweight",
                "  Main Card",
                "    Brenneck vs. Pellicot | Middleweight",
                "    Brisk vs. Loam | Bantamweight | Main event",
                "ⓘ Fight card from Wikipedia | UFC Fight Night: Brisk vs. Loam → $WIKI_BASE/wiki/UFC_Fight_Night:_Brisk_vs._Loam",
                BOUTS_FOOT,
                "focus: held",
            ),
            lines(card(CardState.Bouts(FIGHT_NIGHT_CARD), key = "ev-000032", watch = emptyMap())),
        )
        val tv = lines(card(CardState.Bouts(FIGHT_NIGHT_CARD), key = "ev-000032", watch = emptyMap(), tv = true))
        assertEquals("focus: held", tv.last(), "the line before the start holds a TV's first focus, as before")
    }

    @Test
    fun `a card's bouts on a TV, with its footnote, and a credit with no address, as a TV has no browser`() {
        val phone = lines(card(CardState.Bouts(Fixtures.UFC_999_CARD)))
        val tv = lines(card(CardState.Bouts(Fixtures.UFC_999_CARD), tv = true))
        assertEquals("[Resume Prelims · 37:45]* [Choose release]", tv[3])
        assertEquals(phone.subList(4, 20), tv.subList(4, 20), "the parts and their bouts as the phone's")
        assertEquals(listOf("ⓘ Fight card from Wikipedia | UFC 999", BOUTS_TV_FOOT, "focus: play"), tv.takeLast(3))
    }

    @Test
    fun `View on Wikipedia's address is the encoder's, from the base and the kept article's title`() {
        listOf("UFC 999", "UFC 999 (Kestrel)", "UFC Fight Night: Quéssel vs. Halde").forEach { article ->
            val screen = assertIs<EventScreen>(card(CardState.Bouts(Fixtures.UFC_999_CARD.copy(article = article))))
            assertEquals(Credit(article, PageFinder.address(WIKI_BASE, article)), screen.credit)
        }
        val accented = assertIs<EventScreen>(card(CardState.Bouts(Fixtures.UFC_999_CARD.copy(article = "UFC Fight Night: Quéssel vs. Halde"))))
        assertEquals("$WIKI_BASE/wiki/UFC_Fight_Night:_Qu%C3%A9ssel_vs._Halde", accented.credit?.address)
        val tv = assertIs<EventScreen>(card(CardState.Bouts(Fixtures.UFC_999_CARD.copy(article = "UFC 999 (Kestrel)")), tv = true))
        assertEquals(Credit("UFC 999 (Kestrel)", null), tv.credit, "the TV's sheet names the article, with no link")
    }

    @Test
    fun `each state's line shows under the parts in the credit's place, with today's footnote, and none while none`() {
        val today = lines(page(CARDS, Pages.EVENT, "ev-000031", PRELIMS_HALFWAY))
        assertEquals(today, lines(card(null)), "none: the page as before")
        val tvToday = lines(page(CARDS, Pages.EVENT, "ev-000031", PRELIMS_HALFWAY, tv = true))
        assertEquals(tvToday, lines(card(null, tv = true)), "none on a TV: the page as before")
        assertEquals(CARD_FOOT, today[today.lastIndex - 1])
        assertEquals(CARD_TV_FOOT, tvToday[tvToday.lastIndex - 1])
        val shown = mapOf(
            CardState.Reading to "◌ Reading the fight card…",
            CardState.NotYet to "The fight card isn't on Wikipedia yet.",
            CardState.NoCard to "No fight card found on Wikipedia for this event.",
            CardState.CantReach to "Couldn't reach Wikipedia for the fight card. [Try again]",
            CardState.Off to "Fight cards from Wikipedia are off. [Sportarr settings]",
        )
        shown.forEach { (state, line) ->
            assertEquals(today.dropLast(2) + line + today.takeLast(2), lines(card(state)), "$state on a phone")
            assertEquals(tvToday.dropLast(2) + line + tvToday.takeLast(2), lines(card(state, tv = true)), "$state on a TV")
        }
        // Before the start, the same lines under the parts not begun.
        val before = lines(card(null, key = "ev-000032", watch = emptyMap()))
        shown.forEach { (state, line) ->
            assertEquals(before.dropLast(2) + line + before.takeLast(2), lines(card(state, key = "ev-000032", watch = emptyMap())), "$state before the start")
        }
    }

    @Test
    fun `a TV's first focus is the same in every state of the card`() {
        val states = listOf(null, CardState.Reading, CardState.NotYet, CardState.NoCard, CardState.CantReach, CardState.Off, CardState.Bouts(Fixtures.UFC_999_CARD))
        val watches = listOf(emptyMap(), PRELIMS_HALFWAY, mapOf("ev-000031:prelims" to watched(), "ev-000031:main-card" to watched()))
        watches.forEach { watch ->
            val focus = states.map { assertIs<EventScreen>(card(it, watch = watch, tv = true)).firstFocus }
            assertEquals(listOf(focus.first()), focus.distinct(), "with $watch: $focus")
        }
        val upcoming = states.map { it.takeUnless { state -> state is CardState.Bouts } ?: CardState.Bouts(FIGHT_NIGHT_CARD) }
        upcoming.forEach { assertEquals(PageKeys.HELD, assertIs<EventScreen>(card(it, key = "ev-000032", watch = emptyMap(), tv = true)).firstFocus, "$it") }
    }

    @Test
    fun `a game's, a weekend's and a premium card's pages show no card, whatever state they are given`() {
        val premium = sports(UFC to listOf(on(UFC, 51, at("2026-11-21T20:00"), "UFC Countdown: Quessel vs. Halde", parts = listOf("Countdown", "Main Show"))))
        val bouts = CardState.Bouts(Fixtures.UFC_999_CARD)
        listOf(true, false).forEach { tv ->
            listOf(CardState.Off, CardState.Reading, bouts).forEach { state ->
                fun given(sports: Sports, page: String, key: String, watch: Map<String, WatchInfo> = emptyMap(), now: Instant = NOW) =
                    PageScreens.of(ListState.Ready(sports), page, key, watch, emptyMap(), null, now, LONDON, tv, card = state, wikipedia = WIKI_BASE)
                assertEquals(page(GAMES, Pages.EVENT, "ev-000012", tv = tv), given(GAMES, Pages.EVENT, "ev-000012"))
                assertEquals(
                    page(WEEKEND, Pages.WEEKEND, "2:r20", SATURDAY_EVENING, now = at("2026-11-21T20:41"), tv = tv),
                    given(WEEKEND, Pages.WEEKEND, "2:r20", SATURDAY_EVENING, at("2026-11-21T20:41")),
                )
                assertEquals(page(premium, Pages.EVENT, "ev-000051", tv = tv), given(premium, Pages.EVENT, "ev-000051"))
            }
        }
    }

    @Test
    fun `a UFC card's page reads its card as it shows, Reading meanwhile, and the page again reads nothing more`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val wiki = TestWiki()
        val http = RecordingHttp { request ->
            gate.await()
            wiki.http.get(request.url, request.headers, request.maxBytes)
        }
        val cards = cards(this, http)
        val plays = Plays(RecordingPlay(), MutableWatch(), AnsweringSearch(), this, { NOW }, EmptyCoroutineContext)
        val first = cardModel(plays, cards)
        val running = launch { first.run() }
        val reading = assertIs<EventScreen>(first.next { (it as EventScreen).cardLine != null })
        assertEquals(CardLine.Reading, reading.cardLine)
        assertEquals(CARD_FOOT, reading.footnote, "today's footnote while it reads")
        assertTrue(reading.parts.all { it.bouts.isEmpty() } && reading.credit == null)
        gate.complete(Unit)
        val shown = assertIs<EventScreen>(first.next { (it as EventScreen).credit != null })
        assertEquals(lines(card(CardState.Bouts(Fixtures.UFC_999_CARD), watch = emptyMap())), lines(shown))
        assertEquals(listOf("$WIKI_BASE/wiki/UFC_999"), http.requests.map { it.url })
        running.cancelAndJoin()

        // Back, then the row again: the card kept shows at once, and nothing is asked.
        val second = cardModel(plays, cards)
        val again = launch { second.run() }
        assertEquals(lines(shown), lines(second.next { (it as EventScreen).credit != null }))
        again.cancelAndJoin()
        assertEquals(1, http.requests.size, "one read for the two showings")
    }

    @Test
    fun `Can't reach says Try again, which reads once more, nothing asked while the hold lasts`() = runBlocking<Unit> {
        var now = NOW
        val wiki = TestWiki().apply { status = 503 }
        val cards = cards(this, wiki.http, clock = { now })
        val plays = Plays(RecordingPlay(), MutableWatch(), AnsweringSearch(), this, { now }, EmptyCoroutineContext)
        val model = cardModel(plays, cards, clock = { now })
        val running = launch { model.run() }
        val failed = assertIs<EventScreen>(model.next { (it as EventScreen).cardLine == CardLine.CantReach })
        assertEquals(Words.ListStates.TRY_AGAIN, failed.cardLine?.button)
        assertEquals(1, wiki.http.requests.size)

        wiki.status = null
        assertNotNull(model.tryAgain()).join()
        model.next { (it as EventScreen).cardLine == CardLine.CantReach }
        assertEquals(1, wiki.http.requests.size, "the 503's hold: no request")

        now = NOW.plusSeconds(30)
        assertNotNull(model.tryAgain()).join()
        assertEquals(lines(card(CardState.Bouts(Fixtures.UFC_999_CARD), watch = emptyMap())), lines(model.next { (it as EventScreen).credit != null }))
        assertEquals(2, wiki.http.requests.size)
        running.cancelAndJoin()
    }

    @Test
    fun `with fight cards off, a card's page says so with Sportarr settings and reads nothing`() = runBlocking<Unit> {
        assertTrue(CardsOption(File(File(dir, "cards"), "data"), EmptyCoroutineContext).write(false))
        val wiki = TestWiki()
        val cards = cards(this, wiki.http)
        val plays = Plays(RecordingPlay(), MutableWatch(), AnsweringSearch(), this, { NOW }, EmptyCoroutineContext)
        val model = cardModel(plays, cards)
        val running = launch { model.run() }
        val off = assertIs<EventScreen>(model.next { (it as EventScreen).cardLine != null })
        assertEquals(CardLine.Off, off.cardLine)
        assertEquals(Words.Setup.SETTINGS_TITLE, off.cardLine?.button)
        running.cancelAndJoin()
        assertEquals(0, wiki.http.requests.size)
    }

    @Test
    fun `a game's page and a premium card's read no card`() = runBlocking<Unit> {
        val wiki = TestWiki()
        val cards = cards(this, wiki.http)
        val plays = Plays(RecordingPlay(), MutableWatch(), AnsweringSearch(), this, { NOW }, EmptyCoroutineContext)
        val premium = sports(UFC to listOf(on(UFC, 51, at("2026-11-21T20:00"), "UFC Countdown: Quessel vs. Halde", parts = listOf("Countdown", "Main Show"))))
        listOf(GAMES to "ev-000012", premium to "ev-000051").forEach { (sports, key) ->
            val model = cardModel(plays, cards, key = key, sports = sports)
            val running = launch { model.run() }
            val screen = assertIs<EventScreen>(model.next { true })
            yield()
            assertNull(screen.cardLine)
            running.cancelAndJoin()
        }
        assertEquals(0, wiki.http.requests.size)
    }

    @Test
    fun `a UFC card of one part has a game's page, which reads no card and shows none`() = runBlocking<Unit> {
        val wiki = TestWiki()
        val cards = cards(this, wiki.http)
        val plays = Plays(RecordingPlay(), MutableWatch(), AnsweringSearch(), this, { NOW }, EmptyCoroutineContext)
        val one = sports(UFC to listOf(on(UFC, 31, at("2026-11-21T23:00"), "UFC 999: Quessel vs. Halde", parts = listOf("Main Card"))))
        val model = cardModel(plays, cards, sports = one)
        val running = launch { model.run() }
        model.next { true }
        // What the page asked of the cards, run to its end.
        coroutineContext.job.children.filter { it !== running }.toList().forEach { it.join() }
        val screen = assertIs<EventScreen>(model.screen.value)
        assertEquals(PageKind.Game, screen.kind)
        assertNull(screen.cardLine)
        assertNull(screen.credit)
        running.cancelAndJoin()
        assertEquals(emptyList(), wiki.asked, "nothing asked for a card the page has no room for")
    }

    @Test
    fun `a card read before the event and after it, its winners moved left, draws the same page on a phone and on a TV`() = runBlocking<Unit> {
        val plays = Plays(RecordingPlay(), MutableWatch(), AnsweringSearch(), this, { NOW }, EmptyCoroutineContext)
        val shown = Form.entries.map { form ->
            val wiki = TestWiki().apply { pages["/wiki/UFC_999"] = Fixtures.article(form = form) }
            val cards = cards(this, wiki.http, name = form.name)
            listOf(false, true).map { tv ->
                val model = cardModel(plays, cards, tv = tv)
                val running = launch { model.run() }
                assertIs<EventScreen>(model.next { (it as EventScreen).credit != null }).also { running.cancelAndJoin() }
            }
        }
        val (before, after) = shown
        assertEquals(before, after, "the phone's pages and the TV's")
        assertEquals(lines(card(CardState.Bouts(Fixtures.UFC_999_CARD), watch = emptyMap())), lines(before[0]))
        // Nothing of a result in a bout's row or in what TalkBack says of it.
        val words = shown.flatten().flatMap { screen -> screen.parts.flatMap { row -> row.bouts.flatMap { listOfNotNull(it.names, it.weight, it.play) } } }
        Fixtures.RESULT_WORDS.forEach { word -> assertTrue(words.none { word in it }, "\"$word\" shown") }
        assertFalse(words.any { Regex("""\d""").containsMatchIn(it) }, "a round or a time shown: $words")
    }

    /** UFC 999's page ([key]: the fight night's, else) with the card's [state], View on Wikipedia's address on the test's base. */
    private fun card(
        state: CardState?,
        key: String = "ev-000031",
        watch: Map<String, WatchInfo> = PRELIMS_HALFWAY,
        now: Instant = NOW,
        tv: Boolean = false,
        sports: Sports = CARDS,
    ): PageScreen? = PageScreens.of(ListState.Ready(sports), Pages.EVENT, key, watch, emptyMap(), null, now, LONDON, tv, card = state, wikipedia = WIKI_BASE)

    /**
     * A session's fight cards over [http] in [scope], as the session makes them, in the test's folder [name], on the test's
     * base; the time from [clock], the files read and written in place.
     */
    private fun cards(scope: CoroutineScope, http: PluginHttp, name: String = "cards", clock: () -> Instant = { NOW }): Cards {
        val folder = File(dir, name)
        val reads = WikipediaReads(http, WIKI_BASE, clock, IndexFile(File(folder, "cache"), EmptyCoroutineContext), EmptyCoroutineContext)
        return Cards(scope, reads, KeptCards(File(folder, "data"), EmptyCoroutineContext), CardsOption(File(folder, "data"), EmptyCoroutineContext), clock)
    }

    /** UFC 999's page's model over [cards], in [plays]' scope. */
    private fun cardModel(plays: Plays, cards: Cards, tv: Boolean = false, key: String = "ev-000031", clock: () -> Instant = { NOW }, sports: Sports = CARDS) =
        PageModel(MutableStateFlow(ListState.Ready(sports)), MutableWatch(), plays, Pages.EVENT, key, tv, clock, { LONDON }, EmptyCoroutineContext, cards, WIKI_BASE)

    /** [page] [key] of [sports] as the model builds it from a list read and kept. */
    private fun page(
        sports: Sports,
        page: String,
        key: String,
        watch: Map<String, WatchInfo> = emptyMap(),
        now: Instant = NOW,
        tv: Boolean = false,
    ): PageScreen? = PageScreens.of(ListState.Ready(sports), page, key, watch, emptyMap(), null, now, LONDON, tv)

    /**
     * A page as lines, in the order it draws them: the title, the meta, the status line (its colour, ▬ for its bar), the
     * count, Generic's line, the buttons ([primary]*), the line before the start, the kept line, Mark watched, the parts
     * ("> " marks the one the top button plays; when, what is left, ✓, the ⋮'s items), each with its bouts under it (their
     * names, weight class and labels, and "▶" with what a tap on one plays, as TalkBack says it), a card's line (◌ for its
     * spinner, [its button]), the credit (the article, → View on Wikipedia's address), the footnote, a TV's first focus.
     */
    private fun lines(screen: PageScreen?): List<String> = when (screen) {
        null -> listOf("(loading)")
        is GoneScreen -> listOf(screen.line, "[${screen.back}]*", "focus: ${screen.firstFocus}")
        is EventScreen -> buildList {
            add(screen.title)
            add(screen.meta)
            screen.status?.let { add("${it.text} [${it.tone.name.lowercase()}]" + if (it.progress != null) " ▬" else "") }
            screen.count?.let { add(it.text + if (it.progress != null) " ▬" else "") }
            screen.pick?.let(::add)
            if (screen.buttons.isNotEmpty()) add(screen.buttons.joinToString(" ") { "[${it.label}]" + if (it.primary) "*" else "" })
            screen.note?.let(::add)
            screen.kept?.let(::add)
            screen.mark?.let { add(it.label) }
            screen.parts.forEach { part ->
                add(
                    (if (part.current) "> " else "  ") + listOfNotNull(
                        part.label,
                        part.meta,
                        part.left?.let { it + if (part.progress != null) " ▬" else "" },
                        "✓".takeIf { part.watched },
                        "searching".takeIf { part.searching },
                        part.menu.takeIf { it.isNotEmpty() }?.joinToString(", ", "⋮ ") { it.label },
                    ).joinToString(" | "),
                )
                part.bouts.forEach { bout ->
                    add(
                        "    " + listOfNotNull(
                            bout.names,
                            bout.weight,
                            Words.Bouts.TITLE_FIGHT.takeIf { bout.titleFight },
                            Words.Bouts.MAIN_EVENT.takeIf { bout.mainEvent },
                            bout.play?.let { "▶ $it" },
                        ).joinToString(" | "),
                    )
                }
            }
            screen.cardLine?.let { line -> add((if (line == CardLine.Reading) "◌ " else "") + line.words + (line.button?.let { " [$it]" } ?: "")) }
            screen.credit?.let { credit -> add("ⓘ ${Words.Bouts.CREDIT} | ${credit.article}" + (credit.address?.let { " → $it" } ?: "")) }
            add(screen.footnote)
            add("focus: ${screen.firstFocus}")
        }
    }

    /** The model's next screen that [holds], or a failure after [WAIT]. */
    private suspend fun PageModel.next(holds: (PageScreen) -> Boolean): PageScreen = withTimeout(WAIT) { screen.first { it != null && holds(it) }!! }

    /** The host's watch states, changed by the test: those of the keys asked; a mark keeps nothing. */
    private class MutableWatch : PluginWatch {
        val states = MutableStateFlow<Map<String, WatchInfo>>(emptyMap())

        override fun observe(keys: Collection<String>): Flow<Map<String, WatchInfo>> = states.map { all -> all.filterKeys { it in keys } }

        override suspend fun mark(items: List<Pair<String, String>>, watched: Boolean) = Unit
    }

    /** The user's indexers, answering every search with nothing, counting them. */
    private class AnsweringSearch : PluginSearch {
        val count = AtomicInteger()

        override fun search(words: String, runtimeMinutes: Int?, fresh: Boolean): Flow<Found> = flow {
            count.incrementAndGet()
            emit(Found(listOf(IndexerLine("Indexer A", IndexerLine.State.Found, 0, "0 found")), emptyList()))
        }
    }

    /** The user's indexers, answering nothing until [gate] opens. */
    private class GatedSearch(private val gate: CompletableDeferred<Unit>) : PluginSearch {
        val count = AtomicInteger()

        override fun search(words: String, runtimeMinutes: Int?, fresh: Boolean): Flow<Found> = flow {
            count.incrementAndGet()
            gate.await()
            emit(Found(emptyList(), emptyList()))
        }
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

    /** The host's pages, keeping each Choose release opened. */
    private class RecordingNav : PluginNav {
        val opened: MutableList<PlayItem> = CopyOnWriteArrayList()

        override fun open(page: String, arg: String?) = Unit
        override fun back() = Unit
        override fun openSettings() = Unit
        override fun openReleases(item: PlayItem, accepted: List<ReleaseHandle>, offered: List<Offered>, leftOut: String?) {
            opened += item
        }

        override fun openSearch(words: String, note: String?) = Unit
    }

    /**
     * A host for one session: Sportarr through [http], the address and key saved, no watch states, a folder of its own,
     * and a scope on a thread of its own, as the host's main thread is. It counts searches and keeps what it was asked to
     * play, and never asks a play's releases.
     */
    private class FakeHost(override val http: PluginHttp, override val dataDir: File) : PluginHost {
        val searches = AtomicInteger()
        val played: MutableList<PlayItem> = CopyOnWriteArrayList()
        private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        override val id = "sportarr"
        override val scope = CoroutineScope(SupervisorJob() + main)
        override val cacheDir: File = File(dataDir, "cache")
        override val secrets: PluginSecrets = MapSecrets(mapOf(Connection.URL to "http://127.0.0.1:1867", Connection.KEY to "kestrel0123456789abcdef0123456789"))
        override val watch: PluginWatch = MutableWatch()
        override val search = object : PluginSearch {
            override fun search(words: String, runtimeMinutes: Int?, fresh: Boolean): Flow<Found> = flow {
                searches.incrementAndGet()
                emit(Found(emptyList(), emptyList()))
            }
        }
        override val play = object : PluginPlay {
            override fun play(item: PlayItem, releases: suspend () -> ItemReleases) {
                played += item
            }

            @Composable
            override fun PlayOnButton(modifier: Modifier) = Unit
        }

        override fun addressSafety(url: String): AddressSafety = loopbackSafety(url)

        /**
         * Waits for what the host's scope was given meanwhile: its queue run, and what was launched in it finished (a search
         * started there, as a page or the list is made, counts by then, however late it starts). The fake's search answers
         * at once, so nothing launched there waits long.
         */
        suspend fun settle() = withTimeout(WAIT) {
            repeat(SETTLE_ROUNDS) {
                withContext(main) {}
                scope.coroutineContext.job.children.toList().forEach { it.join() }
            }
        }

        fun close() {
            scope.cancel()
            main.close()
        }
    }

    private companion object {
        val LONDON: ZoneId = ZoneId.of("Europe/London")

        /** Sunday 22 November 2026, 20:41 in London (UTC in November), as the designs draw the pages. */
        val NOW: Instant = at("2026-11-22T20:41")

        /** Monday 23 November at 20:41: after the race. */
        val MONDAY: Instant = at("2026-11-23T20:41")

        const val WAIT = 20_000L

        /** Rounds of waiting for the host's scope: what one round's work launched there, the next waits for. */
        const val SETTLE_ROUNDS = 3

        const val GAME = "Lantern Bay FC vs. Copper Crown United"
        const val GAME_FOOT =
            "Play finds the best release on your indexers that names both teams and the date; the one that played before goes first. Details from your Sportarr."
        const val WEEKEND_FOOT = "Tap a session to play the best release, or ⋮ to choose one yourself. Details from your Sportarr."
        const val CARD_FOOT = "Tap a part to play the best release, or ⋮ to choose one yourself. Details from your Sportarr."
        const val CARD_TV_FOOT = "Press OK on a part to play the best release, or ⋮ to choose one yourself."
        const val BOUTS_FOOT = "Tap a part, or a bout in it, to play the part's best release, or ⋮ to choose one yourself. Details from your Sportarr."
        const val BOUTS_TV_FOOT = "Press OK on a part, or a bout in it, to play the part's best release, or ⋮ to choose one yourself."

        /** The fight night's card as the reader keeps it: its early-prelims bout under Prelims, first; the main event last. */
        val FIGHT_NIGHT_CARD = BoutCard(
            "UFC Fight Night: Brisk vs. Loam",
            listOf(
                BoutPart(
                    "prelims",
                    listOf(
                        Bout("Women's Strawweight", "Ostrem", "Wexmoor", titleFight = false, mainEvent = false),
                        Bout("Featherweight", "Kestvik", "Savrin", titleFight = false, mainEvent = false),
                    ),
                ),
                BoutPart(
                    "main-card",
                    listOf(
                        Bout("Middleweight", "Brenneck", "Pellicot", titleFight = false, mainEvent = false),
                        Bout("Bantamweight", "Brisk", "Loam", titleFight = false, mainEvent = true),
                    ),
                ),
            ),
        )
        const val GENERIC_FOOT =
            "NZStreamer can't tell this sport's releases apart for sure, so you pick one; the one you played goes first next time. Details from your Sportarr."

        /** The game's page's games: the one aired, Monday's evening game (SpPhoneNothing's), and next Saturday's. */
        val GAMES_WIRE = listOf(
            on(PREMIER, 12, at("2026-11-21T15:00"), GAME),
            on(PREMIER, 13, at("2026-11-23T17:30"), "Quarrydown FC vs. Ellerholt United"),
            on(PREMIER, 14, at("2026-11-28T15:00"), "Wrenford Albion vs. Lantern Bay FC"),
        )
        val GAMES: Sports = sports(PREMIER to GAMES_WIRE)

        /** The Grand Prix of Kestrel (round 20), and the next round's three sessions a week on. */
        val WEEKEND_WIRE = kestrelWeekend(firstId = 21) + listOf(
            on(FORMULA_1, 41, at("2026-11-27T12:30"), "Grand Prix of Tarnholm - Practice 1", round = "21"),
            on(FORMULA_1, 44, at("2026-11-28T15:00"), "Grand Prix of Tarnholm - Qualifying", round = "21"),
            on(FORMULA_1, 45, at("2026-11-29T15:00"), "Grand Prix of Tarnholm - Race", round = "21"),
        )
        val WEEKEND: Sports = sports(FORMULA_1 to WEEKEND_WIRE)

        /** UFC 999, and next Saturday's fight night. */
        val CARDS_WIRE = listOf(ufc999(), on(UFC, 32, at("2026-11-28T19:00"), "UFC Fight Night: Brisk vs. Loam", parts = listOf("Prelims", "Main Card")))
        val CARDS: Sports = sports(UFC to CARDS_WIRE)

        /** A downhill, and next Saturday's giant slalom. */
        val SKI_WIRE = listOf(
            on(SKIING, 34, at("2026-11-21T10:30"), "Kestrel · Men's Downhill"),
            on(SKIING, 35, at("2026-11-28T10:30"), "Kestrel · Women's Giant Slalom"),
        )
        val SKI: Sports = sports(SKIING to SKI_WIRE)

        /** Saturday evening: the first two practices watched, the qualifying played to 32:10 from its end. */
        val SATURDAY_EVENING = mapOf(
            "ev-000021:full" to watched(),
            "ev-000022:full" to watched(),
            "ev-000024:full" to WatchInfo(1_102_000, 3_032_000, false, at("2026-11-21T19:30")),
        )

        /** After the race: the practices and the qualifying watched, the third practice skipped. */
        val AFTER_THE_RACE = mapOf("ev-000021:full" to watched(), "ev-000022:full" to watched(), "ev-000024:full" to watched())

        /** UFC 999's early prelims watched, its prelims played to 37:45, 52:10 from their end. */
        val PRELIMS_HALFWAY = mapOf(
            "ev-000031:early-prelims" to watched(),
            "ev-000031:prelims" to WatchInfo(2_265_000, 5_395_000, false, at("2026-11-22T20:00")),
        )
    }
}
