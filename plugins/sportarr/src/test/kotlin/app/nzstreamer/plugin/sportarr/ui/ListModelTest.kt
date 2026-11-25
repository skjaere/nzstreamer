package app.nzstreamer.plugin.sportarr.ui

import app.nzstreamer.plugin.sportarr.Session
import app.nzstreamer.plugin.sportarr.model.Days.Side
import app.nzstreamer.plugin.sportarr.model.FORMULA_1
import app.nzstreamer.plugin.sportarr.model.MOTO_GP
import app.nzstreamer.plugin.sportarr.model.NBA
import app.nzstreamer.plugin.sportarr.model.PREMIER
import app.nzstreamer.plugin.sportarr.model.SKIING
import app.nzstreamer.plugin.sportarr.model.Sports
import app.nzstreamer.plugin.sportarr.model.UFC
import app.nzstreamer.plugin.sportarr.model.at
import app.nzstreamer.plugin.sportarr.model.on
import app.nzstreamer.plugin.sportarr.model.watched
import app.nzstreamer.plugin.sportarr.source.Connection
import app.nzstreamer.plugin.sportarr.source.Failure
import app.nzstreamer.plugin.sportarr.source.KeptLeague
import app.nzstreamer.plugin.sportarr.source.ListRead
import app.nzstreamer.plugin.sportarr.source.ListReader
import app.nzstreamer.plugin.sportarr.source.ListState
import app.nzstreamer.plugin.sportarr.source.MapSecrets
import app.nzstreamer.plugin.sportarr.source.SportarrClient
import app.nzstreamer.plugin.sportarr.source.SportsList
import app.nzstreamer.plugin.sportarr.source.TestSportarr
import app.nzstreamer.plugin.sportarr.source.WireEvent
import app.nzstreamer.plugin.sportarr.source.league
import app.nzstreamer.plugin.sportarr.source.loopbackSafety
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.skjaere.usenet.plugin.AddressSafety
import io.skjaere.usenet.plugin.Found
import io.skjaere.usenet.plugin.ItemReleases
import io.skjaere.usenet.plugin.PlayItem
import io.skjaere.usenet.plugin.PluginHost
import io.skjaere.usenet.plugin.PluginHttp
import io.skjaere.usenet.plugin.PluginPlay
import io.skjaere.usenet.plugin.PluginSearch
import io.skjaere.usenet.plugin.PluginSecrets
import io.skjaere.usenet.plugin.PluginWatch
import io.skjaere.usenet.plugin.WatchInfo
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Sports list as data (L641, L644, L645, L652), frame by frame as the phone's list and states designs draw it, over
 * one invented library: Recent as the tab opens, with Up next and the days; Yesterday's five and "+3 more", opened with
 * "Show fewer"; a sport picked, with its leagues; Upcoming; Show watched; and every state of the list. Leagues inside a
 * day go by the sport chips' order, then by name; the chips by how many rows each sport has, then A to Z; a weekend's line
 * on each side; the cut line at the end of Recent. The view's choices are kept per device, the side is not; nothing is
 * searched as the list shows, and no row holds anything Sportarr sends besides titles, leagues, rounds and times.
 */
class ListModelTest {
    @TempDir
    lateinit var dir: File

    private fun screen(
        state: ListState = ListState.Ready(library()),
        side: Side = Side.Recent,
        choices: ViewChoices = ViewChoices(),
        expanded: Set<String> = emptySet(),
        watch: Map<String, WatchInfo> = WATCH,
        now: Instant = NOW,
        tv: Boolean = false,
    ): ListScreen = ListScreens.of(state, side, choices, expanded, watch, now, LONDON, tv)

    @Test
    fun `Recent as the tab opens - Up next, then the days, newest first, a card per league`() {
        val screen = screen()

        assertEquals(Side.Recent, screen.side)
        assertFalse(screen.loading)
        assertEquals(listOf("All", "Soccer", "Motorsport", "Fighting", "Basketball", "Skiing"), screen.sports.map { it.label })
        assertEquals(listOf(true, false, false, false, false, false), screen.sports.map { it.selected })
        assertEquals(emptyList(), screen.leagues, "the leagues' chips show once a sport is picked")
        assertNull(screen.notice)
        assertNull(screen.card)
        assertNull(screen.cut)
        assertEquals(
            listOf(
                "Lantern Bay FC vs Copper Crown United / Premier League · 1 h 5 min left ▬",
                "Grand Prix of Kestrel · Race / Formula 1 · 3 of 5 watched · 1 h 12 min left ▬",
                "UFC 999: Quessel vs. Halde · Main Card / UFC · 2 of 3 watched",
            ),
            screen.upNext.map(::strip),
        )
        assertEquals(listOf(true, false, false), screen.upNext.map { it.primary }, "the first row's Play is the primary one")
        assertEquals(listOf("ev-000012:full", "ev-000025:full", "ev-000061:main-card"), screen.upNext.map { it.item.part.key })
        assertEquals(
            listOf(
                "Today",
                "  Premier League",
                "    14:00 | Gildenford FC vs Marrowby United",
                "    16:30 | Lantern Bay FC vs Copper Crown United | 1:04:38 left ▬",
                "  Formula 1",
                "    14:00 | Grand Prix of Kestrel | Round 20 · 3 of 5 watched | Race · 1:11:24 left ▬",
                "Yesterday",
                "  Premier League",
                "    15:00 | Wrenford Albion vs Brackenmoor FC",
                "    15:00 | Saltmere City vs Lowmarsh Rovers",
                "    15:00 | Thistlecombe FC vs Kestrel Vale",
                "    15:00 | Duskwater FC vs Corvane Athletic",
                "    15:00 | Ravensholt United vs Hollowmere Town",
                "    +3 more",
                "  UFC",
                "    23:00 | UFC 999: Quessel vs. Halde | [✓Early Prelims, ✓Prelims, Main Card]",
                "  FIS Alpine Ski World Cup",
                "    10:30 | Kestrel · Men's Downhill | You pick the release",
                "Fri 20 Nov",
                "  NBA",
                "    01:30 | Easton Comets vs Lantern Bay Herons",
                "Sun 15 Nov",
                "  MotoGP",
                "    13:00 | Grand Prix of Oskelund | Round 19 · Race · 2 sessions",
                "Sun 8 Nov",
                "  Formula 1",
                "    15:00 | Grand Prix of Vellmar | Round 19 · Race · 1 of 5 watched",
                "Sat 7 Nov",
                "  UFC",
                "    19:00 | UFC Fight Night: Loam vs. Quessel | [Prelims, Main Card]",
                "Sun 1 Nov",
                "  MotoGP",
                "    13:00 | Grand Prix of Tarnby | Round 18 · Race · 2 sessions",
            ),
            days(screen),
        )
        // Every row opens its page: an event's by its key, a weekend's by the weekend's.
        val rows = screen.days.flatMap { day -> day.leagues.flatMap { it.rows } }
        assertEquals(Pages.WEEKEND to "2:r20", rows.single { it.title == "Grand Prix of Kestrel" }.let { it.page to it.key })
        assertEquals(Pages.EVENT to "ev-000012", rows.single { it.title == "Lantern Bay FC vs Copper Crown United" }.let { it.page to it.key })
        assertTrue(rows.none { it.upcoming }, "Recent's rows have all started")
    }

    @Test
    fun `Yesterday's five, then +3 more, which opens the league's day in place with Show fewer at its end`() {
        val yesterday = screen().days[1].leagues.first()
        assertEquals("Premier League", yesterday.name)
        assertEquals(5, yesterday.rows.size)
        assertEquals("+3 more", yesterday.more)

        val opened = screen(expanded = setOf(yesterday.key)).days[1].leagues.first()
        assertEquals(
            listOf(
                "Wrenford Albion vs Brackenmoor FC", "Saltmere City vs Lowmarsh Rovers", "Thistlecombe FC vs Kestrel Vale",
                "Duskwater FC vs Corvane Athletic", "Ravensholt United vs Hollowmere Town", "Pellbrook Town vs Ostrey City",
                "Pyrecombe United vs Halvering City", "Quillon Rovers vs Sorrelton FC",
            ),
            opened.rows.map { it.title },
        )
        assertEquals("Show fewer", opened.more)
        assertEquals(yesterday.key, opened.key, "the same card, opened")
        // Only that league's day opens.
        assertEquals(1, screen(expanded = setOf(yesterday.key)).days.flatMap { it.leagues }.count { it.more == "Show fewer" })
    }

    @Test
    fun `a sport picked - its leagues on a second row of chips, no Up next, its rows alone`() {
        val screen = screen(choices = ViewChoices(sport = "Motorsport"))

        assertEquals(listOf(false, false, true, false, false, false), screen.sports.map { it.selected })
        assertEquals(listOf("All leagues", "Formula 1", "MotoGP"), screen.leagues.map { it.label })
        assertEquals(listOf(true, false, false), screen.leagues.map { it.selected })
        assertEquals(emptyList(), screen.upNext, "Up next shows with every sport alone")
        assertEquals(
            listOf(
                "Today",
                "  Formula 1",
                "    14:00 | Grand Prix of Kestrel | Round 20 · 3 of 5 watched | Race · 1:11:24 left ▬",
                "Sun 15 Nov",
                "  MotoGP",
                "    13:00 | Grand Prix of Oskelund | Round 19 · Race · 2 sessions",
                "Sun 8 Nov",
                "  Formula 1",
                "    15:00 | Grand Prix of Vellmar | Round 19 · Race · 1 of 5 watched",
                "Sun 1 Nov",
                "  MotoGP",
                "    13:00 | Grand Prix of Tarnby | Round 18 · Race · 2 sessions",
            ),
            days(screen),
        )

        val motoGp = screen(choices = ViewChoices(sport = "Motorsport", league = LeagueChoice(MOTO_GP.id, "MotoGP")))
        assertEquals(listOf(false, false, true), motoGp.leagues.map { it.selected })
        assertEquals(listOf("Grand Prix of Oskelund", "Grand Prix of Tarnby"), motoGp.days.flatMap { day -> day.leagues.flatMap { it.rows } }.map { it.title })
        // The sport's words as Sportarr writes them match whatever their case.
        assertEquals(days(screen), days(screen(choices = ViewChoices(sport = "motorsport"))))
    }

    @Test
    fun `Upcoming - Today on top, then soonest first, a weekend under the day of its next session, no Up next`() {
        val screen = screen(side = Side.Upcoming)

        assertEquals(Side.Upcoming, screen.side)
        assertEquals(emptyList(), screen.upNext, "Up next is Recent's alone")
        assertEquals(listOf("All", "Soccer", "Motorsport", "Fighting", "Basketball", "Skiing"), screen.sports.map { it.label })
        assertEquals(
            listOf(
                "Today",
                "  Premier League",
                "    14:00 | Gildenford FC vs Marrowby United",
                "    16:30 | Lantern Bay FC vs Copper Crown United | 1:04:38 left ▬",
                "  Formula 1",
                "    14:00 | Grand Prix of Kestrel | Round 20 · 3 of 5 watched | Race · 1:11:24 left ▬",
                "Tomorrow",
                "  Premier League",
                "    20:00 | Brindlecote FC vs Yarrowmede City",
                "Fri 27 Nov",
                "  Formula 1",
                "    12:30 | Grand Prix of Tarnholm | Round 21 · Practice 1 first · 5 sessions",
                "Sat 28 Nov",
                "  Premier League",
                "    15:00 | Wrenford Albion vs Lantern Bay FC",
                "    15:00 | Copper Crown United vs Saltmere City",
                "  UFC",
                "    19:00 | UFC Fight Night: Brisk vs. Loam | [Prelims, Main Card]",
            ),
            days(screen),
        )
        val upcoming = screen.days.flatMap { day -> day.leagues.flatMap { league -> league.rows.map { day.header to it.upcoming } } }
        assertEquals(listOf(false, false, false), upcoming.filter { it.first == "Today" }.map { it.second }, "today's have started")
        assertTrue(upcoming.filter { it.first != "Today" }.all { it.second }, "the rest are still to start, drawn quieter")
    }

    @Test
    fun `the menu says Show watched, then Hide watched once watched events show in place with their tick`() {
        assertEquals("Show watched", screen().watchedItem)
        val screen = screen(choices = ViewChoices(showWatched = true))
        assertEquals("Hide watched", screen.watchedItem)
        assertEquals(
            listOf(
                "Today",
                "  Premier League",
                "    14:00 | Gildenford FC vs Marrowby United",
                "    16:30 | Lantern Bay FC vs Copper Crown United | 1:04:38 left ▬",
                "  Formula 1",
                "    14:00 | Grand Prix of Kestrel | Round 20 · 3 of 5 watched | Race · 1:11:24 left ▬",
                "  NBA",
                "    01:30 | Saltmere Gulls vs Marrowby Lynx | ✓",
                "Yesterday",
                "  Premier League",
                "    12:30 | Northfield Rovers vs Easton Athletic | ✓",
                "    15:00 | Wrenford Albion vs Brackenmoor FC",
                "    15:00 | Saltmere City vs Lowmarsh Rovers",
                "    15:00 | Orvanne FC vs Tollendale FC | ✓",
                "    15:00 | Thistlecombe FC vs Kestrel Vale",
                "    +5 more",
            ),
            days(screen).take(16),
        )
        assertEquals(screen().upNext, screen.upNext, "Up next is the same either way")
    }

    @Test
    fun `leagues inside a day follow the sport chips' order, then their names, a league with no sport last`() {
        val division = KeptLeague(11, "Kestrel Premier Division", "Soccer")
        val cup = KeptLeague(12, "Kestrel Cup", null)
        val saturday = at("2026-11-21T15:00")
        val sports = build(
            PREMIER to listOf(on(PREMIER, 1, saturday, "Wrenford Albion vs Brackenmoor FC")),
            division to listOf(on(division, 2, saturday, "Saltmere City vs Lowmarsh Rovers"), on(division, 3, at("2026-11-14T15:00"), "Duskwater FC vs Corvane Athletic")),
            FORMULA_1 to listOf(on(FORMULA_1, 4, saturday, "Grand Prix of Vellmar", round = "19"), on(FORMULA_1, 5, at("2026-11-08T15:00"), "Grand Prix of Tarnby", round = "18")),
            UFC to listOf(on(UFC, 6, saturday, "UFC 999: Quessel vs. Halde")),
            cup to listOf(on(cup, 7, saturday, "Thistlecombe FC vs Kestrel Vale")),
        )
        val day = screen(ListState.Ready(sports), watch = emptyMap()).days.single { it.header == "Yesterday" }
        assertEquals(listOf("Kestrel Premier Division", "Premier League", "Formula 1", "UFC", "Kestrel Cup"), day.leagues.map { it.name })
    }

    @Test
    fun `the sport chips go by how many rows each sport has here, then A to Z, and so do a sport's leagues`() {
        val division = KeptLeague(11, "Kestrel Premier Division", "Soccer")
        val saturday = at("2026-11-21T15:00")
        val sports = build(
            NBA to listOf(on(NBA, 1, saturday, "Easton Comets vs Lantern Bay Herons"), on(NBA, 2, at("2026-11-20T01:30"), "Saltmere Gulls vs Marrowby Lynx")),
            UFC to listOf(on(UFC, 3, saturday, "UFC 999: Quessel vs. Halde"), on(UFC, 4, at("2026-11-28T19:00"), "UFC Fight Night: Brisk vs. Loam")),
            division to listOf(on(division, 5, saturday, "Saltmere City vs Lowmarsh Rovers")),
            PREMIER to listOf(on(PREMIER, 6, saturday, "Wrenford Albion vs Brackenmoor FC"), on(PREMIER, 7, at("2026-11-23T20:00"), "Brindlecote FC vs Yarrowmede City")),
            SKIING to listOf(on(SKIING, 8, saturday, "Kestrel · Men's Downhill")),
            // A weekend is one row however many sessions it has.
            FORMULA_1 to (1..5).map { on(FORMULA_1, 20 + it, at("2026-11-2${it % 3}T12:00"), "Grand Prix of Kestrel - Practice $it", round = "20") },
        )
        val screen = screen(ListState.Ready(sports), watch = emptyMap())
        assertEquals(listOf("All", "Soccer", "Basketball", "Fighting", "Motorsport", "Skiing"), screen.sports.map { it.label })
        val soccer = screen(ListState.Ready(sports), choices = ViewChoices(sport = "Soccer"), watch = emptyMap())
        assertEquals(listOf("All leagues", "Premier League", "Kestrel Premier Division"), soccer.leagues.map { it.label })
    }

    @Test
    fun `a weekend's line on each side - its round, the session it is dated by, and its count`() {
        val saturdayEvening = at("2026-11-21T20:41")
        val watch = mapOf("ev-000021:full" to watched(), "ev-000024:full" to watched())
        fun line(side: Side, sports: Sports = library(), now: Instant = saturdayEvening, w: Map<String, WatchInfo> = watch, title: String = "Grand Prix of Kestrel") =
            screen(ListState.Ready(sports), side = side, watch = w, now = now).days.flatMap { day -> day.leagues.flatMap { it.rows } }
                .single { it.title == title }.let { listOfNotNull(it.time, it.line, it.left).joinToString(" | ") }

        // Saturday evening: qualifying was the last to air; the race is next, tomorrow.
        assertEquals("15:00 | Round 20 · Qualifying · 2 of 4 watched", line(Side.Recent))
        assertEquals("14:00 | Round 20 · Race first · 2 of 4 watched", line(Side.Upcoming))
        // Nothing watched yet: how many sessions the weekend has.
        assertEquals("15:00 | Round 20 · Qualifying · 5 sessions", line(Side.Recent, w = emptyMap()))
        // The race in progress leads what is left, and the session is left out of the line.
        assertEquals("14:00 | Round 20 · 3 of 5 watched | Race · 1:11:24 left", line(Side.Recent, now = NOW, w = WATCH))
        // The other forms the list draws.
        assertEquals("13:00 | Round 19 · Race · 2 sessions", line(Side.Recent, now = NOW, title = "Grand Prix of Oskelund"))
        assertEquals("15:00 | Round 19 · Race · 1 of 5 watched", line(Side.Recent, now = NOW, w = WATCH, title = "Grand Prix of Vellmar"))
        assertEquals("12:30 | Round 21 · Practice 1 first · 5 sessions", line(Side.Upcoming, now = NOW, title = "Grand Prix of Tarnholm"))

        // A round without a number says no round; one with no round at all, the same.
        val final = build(
            FORMULA_1 to listOf(
                on(FORMULA_1, 1, at("2026-11-21T15:00"), "Grand Prix of Kestrel - Qualifying", round = "Final"),
                on(FORMULA_1, 2, at("2026-11-22T14:00"), "Grand Prix of Kestrel - Race", round = "Final"),
            ),
        )
        assertEquals("15:00 | Qualifying · 2 sessions", line(Side.Recent, final, w = emptyMap()))
        assertEquals("14:00 | Race first · 2 sessions", line(Side.Upcoming, final, w = emptyMap()))
        val none = build(FORMULA_1 to listOf(on(FORMULA_1, 1, at("2026-11-21T15:00"), "Grand Prix of Kestrel - Qualifying")))
        assertEquals("15:00 | Qualifying · 1 session", line(Side.Recent, none, w = emptyMap()))
    }

    @Test
    fun `Up next shows on Recent alone, with every sport, three rows at most`() {
        assertEquals(3, screen().upNext.size)
        assertEquals(emptyList(), screen(side = Side.Upcoming).upNext)
        assertEquals(emptyList(), screen(choices = ViewChoices(sport = "Soccer")).upNext)
        assertEquals(emptyList(), screen(watch = emptyMap()).upNext, "nothing started, nothing to go on with")
        // With a list kept through a failed read, Up next stays.
        assertEquals(screen().upNext, screen(ListState.Unreachable(library(), Failure.NoAnswer)).upNext)
    }

    @Test
    fun `the states - loading, can't reach and refused with and without a list, nothing followed`() {
        val loading = screen(ListState.Loading)
        assertTrue(loading.loading)
        assertEquals(emptyList(), loading.sports)
        assertEquals(emptyList(), loading.days)
        assertEquals(emptyList(), loading.upNext)
        assertNull(loading.card)
        assertNull(loading.notice)
        assertTrue(screen(ListState.NotSetUp).loading)

        // Can't reach Sportarr: the kept list stays, with its age and Try again.
        val kept = library(readAt = at("2026-11-22T17:41"))
        val unreachable = screen(ListState.Unreachable(kept, Failure.NoAnswer))
        assertEquals(Notice("Can't reach Sportarr · list from 3 h ago", "Try again", ListAction.Refresh), unreachable.notice)
        assertEquals(days(screen()), days(unreachable))
        assertEquals(6, unreachable.sports.size)
        assertNull(unreachable.card)
        // The same line when Sportarr answers with an error.
        assertEquals(unreachable.notice, screen(ListState.Unreachable(kept, Failure.ErrorStatus)).notice)

        // No list kept: a card with why, and Try again; a TV says this TV.
        val none = screen(ListState.Unreachable(null, Failure.NoAnswer))
        assertEquals(
            StateCard(
                "Can't reach Sportarr", "Sportarr didn't answer. Check that Sportarr is running and that this phone is on its network.", "Try again",
                ListAction.Refresh, null,
            ),
            none.card,
        )
        assertEquals(emptyList(), none.sports)
        assertEquals(emptyList(), none.days)
        assertNull(none.notice)
        assertFalse(none.loading)
        assertEquals(
            "Sportarr didn't answer. Check that Sportarr is running and that this TV is on its network.",
            screen(ListState.Unreachable(null, Failure.NoAnswer), tv = true).card?.line,
        )
        assertEquals("Sportarr answered with an error. Try again in a moment.", screen(ListState.Unreachable(null, Failure.ErrorStatus)).card?.line)

        // The key refused (changed in Sportarr): Sportarr settings in Try again's place.
        val refused = screen(ListState.Refused(kept))
        assertEquals(Notice("Sportarr refused the API key · list from 3 h ago", "Sportarr settings", ListAction.Settings), refused.notice)
        assertEquals(days(screen()), days(refused))
        assertEquals(screen().upNext, refused.upNext)
        val refusedNone = screen(ListState.Refused(null))
        assertEquals(StateCard("Sportarr refused the API key", null, "Sportarr settings", ListAction.Settings, null), refusedNone.card)
        assertEquals(emptyList(), refusedNone.days)

        // Sportarr answered, but no league is switched on there.
        val nothing = screen(ListState.NothingFollowed)
        assertEquals(
            StateCard(
                "Your Sportarr follows nothing yet",
                "Sports lists the leagues you switched on in Sportarr, with the teams and sessions you chose there. Switch one on in Sportarr, then Refresh.",
                "Refresh", ListAction.Refresh, "Events with no league in Sportarr don't show here: give them one there.",
            ),
            nothing.card,
        )
        assertEquals(emptyList(), nothing.sports)
    }

    @Test
    fun `nothing in Recent, nothing in Upcoming, everything watched, and nothing matching the chips`() {
        // Leagues switched on, but nothing aired in the last 60 days: the first day Recent reaches, and what Upcoming has.
        val later = build(
            PREMIER to listOf(
                on(PREMIER, 1, at("2026-11-23T20:00"), "Brindlecote FC vs Yarrowmede City"),
                on(PREMIER, 2, at("2026-11-28T15:00"), "Wrenford Albion vs Lantern Bay FC"),
                on(PREMIER, 3, at("2026-11-28T15:00"), "Copper Crown United vs Saltmere City"),
            ),
            FORMULA_1 to listOf(
                on(FORMULA_1, 4, at("2026-11-27T12:30"), "Grand Prix of Tarnholm - Practice 1", round = "21"),
                on(FORMULA_1, 5, at("2026-11-29T15:00"), "Grand Prix of Tarnholm - Race", round = "21"),
            ),
            MOTO_GP to listOf(on(MOTO_GP, 6, at("2026-12-06T13:00"), "Grand Prix of Oskelund - Race", round = "20")),
            UFC to listOf(on(UFC, 7, at("2026-11-28T19:00"), "UFC Fight Night: Brisk vs. Loam")),
        )
        val recent = screen(ListState.Ready(later), watch = emptyMap())
        assertEquals(listOf("All", "Soccer", "Motorsport", "Fighting"), recent.sports.map { it.label })
        assertEquals(
            StateCard(
                "Nothing aired in the last 60 days", "Your Sportarr's leagues have nothing between 23 Sep and today. Upcoming has 6 events.",
                "Upcoming", ListAction.Upcoming, null,
            ),
            recent.card,
        )
        assertEquals(emptyList(), recent.days)
        assertEquals(emptyList(), recent.upNext)

        // Nothing in the next 31 days: Recent's button.
        val earlier = build(PREMIER to listOf(on(PREMIER, 1, at("2026-11-21T15:00"), "Wrenford Albion vs Brackenmoor FC")))
        assertEquals(
            StateCard("Nothing in the next 31 days", null, "Recent", ListAction.Recent, null),
            screen(ListState.Ready(earlier), side = Side.Upcoming, watch = emptyMap()).card,
        )

        // Every row of the side watched and hidden: Show watched.
        val all = screen(ListState.Ready(earlier), watch = mapOf("ev-000001:full" to watched()))
        assertEquals(StateCard("Everything here is watched", null, "Show watched", ListAction.ShowWatched, null), all.card)
        assertEquals(emptyList(), all.days)
        assertNull(screen(ListState.Ready(earlier), choices = ViewChoices(showWatched = true), watch = mapOf("ev-000001:full" to watched())).card)

        // A sport picked with nothing in it on this side; and one no longer listed, its chip kept so it can be seen.
        val skiing = screen(side = Side.Upcoming, choices = ViewChoices(sport = "Skiing"))
        assertEquals(StateCard("Nothing matches these filters", null, "Clear filters", ListAction.ClearFilters, null), skiing.card)
        assertEquals(emptyList(), skiing.days)
        val gone = screen(choices = ViewChoices(sport = "Cricket", league = LeagueChoice(99, "Kestrel Cup")))
        assertEquals("Nothing matches these filters", gone.card?.title)
        assertEquals("Cricket" to true, gone.sports.last().let { it.label to it.selected })
        assertEquals(listOf("All leagues" to false, "Kestrel Cup" to true), gone.leagues.map { it.label to it.selected })
    }

    @Test
    fun `the end of Recent says when Sports cut older events, and Upcoming doesn't`() {
        val cut = Sports.of(SportsList(NOW, LIBRARY_LEAGUES, libraryEvents().sortedByDescending { it.eventDate }, cut = true))
        assertEquals("Sports keeps the newest 5,000 events: older ones aren't shown.", screen(ListState.Ready(cut)).cut)
        assertNull(screen(ListState.Ready(cut), side = Side.Upcoming).cut)
        assertNull(screen().cut)
    }

    @Test
    fun `the chips and Show watched are kept per device, and the side opens on Recent each time`() = runBlocking<Unit> {
        val state = MutableStateFlow<ListState>(ListState.Ready(library()))
        val first = model(state)
        val running = launch { first.run() }
        first.next { true }
        first.side(Side.Upcoming)
        first.sport("Motorsport")
        first.league(LeagueChoice(FORMULA_1.id, "Formula 1"))
        first.toggleWatched()
        val changed = first.next { it.watchedItem == "Hide watched" && it.leagues.any { chip -> chip.selected && chip.league?.id == FORMULA_1.id } }
        assertEquals(Side.Upcoming, changed.side)
        withTimeout(WAIT) { while (!File(dir, "view.json").isFile) kotlinx.coroutines.yield() }
        running.cancelAndJoin()
        // The file says the choices alone: nothing of a title, a row, the side or the list.
        val text = File(dir, "view.json").readText()
        assertTrue("Motorsport" in text && "true" in text, text)
        listOf("Grand Prix", "Lantern Bay", "Upcoming", "ev-0000").forEach { assertFalse(it in text, "view.json holds $it: $text") }

        // Opened again: the chips and Show watched as left, the side back on Recent.
        val second = model(state)
        val again = launch { second.run() }
        val reopened = second.next { true }
        assertEquals(Side.Recent, reopened.side)
        assertEquals("Motorsport", reopened.sports.single { it.selected }.label)
        assertEquals("Formula 1", reopened.leagues.single { it.selected }.label)
        assertEquals("Hide watched", reopened.watchedItem)
        // All clears the league too; Clear filters both.
        second.sport(null)
        assertEquals(emptyList(), second.next { it.sports.first().selected }.leagues)
        again.cancelAndJoin()

        // A page's own state (Back from an event's page): the side and the days opened come back as they were.
        val restored = model(state, ListModel.Restored(Side.Upcoming.name, "2026-11-21|1"))
        val back = launch { restored.run() }
        assertEquals(Side.Upcoming, restored.next { true }.side)
        assertEquals(ListModel.Restored(Side.Upcoming.name, "2026-11-21|1"), restored.kept())
        back.cancelAndJoin()
    }

    @Test
    fun `an unreadable view file is no choice, and the list shows as it opens`() = runBlocking<Unit> {
        File(dir, "view.json").writeText("{\"format\":1,\"sport\":")
        val model = model(MutableStateFlow(ListState.Ready(library())))
        val running = launch { model.run() }
        val screen = model.next { true }
        assertTrue(screen.sports.first().selected)
        assertEquals("Show watched", screen.watchedItem)
        running.cancelAndJoin()
    }

    @Test
    fun `the model's actions - +3 more and Show fewer, the side, Clear filters, and the time moving on`() = runBlocking<Unit> {
        var now = NOW
        val model = model(MutableStateFlow(ListState.Ready(library())), clock = { now })
        val running = launch { model.run() }
        val first = model.next { true }
        val yesterday = first.days[1].leagues.first()
        model.toggle(yesterday)
        assertEquals("Show fewer", model.next { it.days[1].leagues.first().more == "Show fewer" }.days[1].leagues.first().more)
        assertEquals(ListModel.Restored(Side.Recent.name, yesterday.key), model.kept())
        model.toggle(yesterday)
        model.next { it.days[1].leagues.first().more == "+3 more" }

        model.sport("Skiing")
        model.side(Side.Upcoming)
        model.next { it.card?.action == ListAction.ClearFilters }
        model.clearFilters()
        val cleared = model.next { it.card == null && it.side == Side.Upcoming }
        assertTrue(cleared.sports.first().selected)

        // Tuesday morning: Monday's game is Yesterday's.
        now = at("2026-11-24T09:00")
        model.side(Side.Recent)
        model.tick()
        assertEquals("Yesterday", model.next { it.side == Side.Recent && it.days.first().header == "Yesterday" }.days.first().header)
        running.cancelAndJoin()
    }

    @Test
    fun `nothing is searched as the list shows, and a tap on an Up next row plays its part`() = runBlocking<Unit> {
        // Sportarr's library around the real clock, as the session reads it: a game an hour in, played half way.
        val now = Instant.now()
        val game = on(PREMIER, 1, now.minus(Duration.ofHours(1)), "Lantern Bay FC vs Copper Crown United")
        val sportarr = TestSportarr(listOf(league(PREMIER.id, PREMIER.name, "Soccer")), mapOf(PREMIER.id to listOf(game)))
        val host = FakeHost(sportarr.http, mapOf("ev-000001:full" to WatchInfo(3_300_000, 6_600_000, false, now)), File(dir, "plugin"))
        try {
            val session = Session(host)
            withTimeout(WAIT) { session.refresh.shown().join() }
            val model = session.list(tv = false, restored = null)
            val running = launch { model.run() }
            val screen = model.next { it.upNext.isNotEmpty() }
            assertEquals(0, host.searches.get(), "nothing searched as the list shows")
            assertEquals(emptyList(), host.played)
            assertTrue(sportarr.http.requests.all { "/api/leagues" in it.url }, "the list's routes alone: ${sportarr.http.requests.map { it.url }}")

            model.play(screen.upNext.single())
            // The item is made off the main thread, then handed to the host's play.
            withTimeout(WAIT) { while (host.played.isEmpty()) yield() }
            val played = host.played.single()
            assertEquals("ev-000001:full", played.key)
            assertEquals("Lantern Bay FC vs Copper Crown United", played.title)
            assertEquals("Premier League", played.subtitle)
            assertEquals(110, played.runtimeMinutes)
            assertEquals("game", played.noun)
            assertEquals(0, host.searches.get(), "the host searches once the play asks for releases, not the list")
            running.cancelAndJoin()
        } finally {
            host.close()
        }
    }

    @Test
    fun `no row holds a canary value, read from Sportarr with every one of them sent`() = runBlocking<Unit> {
        val sportarr = TestSportarr(
            LIBRARY_LEAGUES.map { league(it.id, it.name, it.sport) },
            libraryEvents().groupBy { it.leagueId ?: 0 },
        )
        val read = ListReader(
            SportarrClient(sportarr.http, "http://127.0.0.1:1867", "kestrel0123456789abcdef0123456789", ::loopbackSafety, EmptyCoroutineContext),
            NOW, LONDON, work = EmptyCoroutineContext,
        ).read()
        val sports = Sports.of(assertIs<ListRead.Read>(read).list)
        val everyDay = sports.listed.flatMap { listed -> listed.events.map { "${it.start.atZone(LONDON).toLocalDate()}|${listed.league.id}" } }.toSet()
        val shown = listOf(Side.Recent, Side.Upcoming).flatMap { side ->
            (listOf<String?>(null) + listOf("Soccer", "Motorsport", "Fighting", "Basketball", "Skiing")).flatMap { sport ->
                listOf(false, true).map { showWatched ->
                    ListScreens.of(ListState.Ready(sports), side, ViewChoices(sport, showWatched = showWatched), everyDay, WATCH, NOW, LONDON, tv = false)
                }
            }
        } + ListScreens.of(ListState.Unreachable(sports, Failure.NoAnswer), Side.Recent, ViewChoices(), everyDay, WATCH, NOW, LONDON, tv = true)
        val text = shown.joinToString("\n")
        assertTrue("Lantern Bay FC vs Copper Crown United" in text && "Grand Prix of Kestrel" in text, "the rows show")
        val leaked = TestSportarr.CANARY_VALUES.filter { text.contains(it, ignoreCase = true) }
        assertTrue(leaked.isEmpty(), "canary values in the rows: $leaked")
    }

    /** A model over [state], its view kept in the test's folder, the library's watch states. */
    private fun model(state: MutableStateFlow<ListState>, restored: ListModel.Restored? = null, clock: () -> Instant = { NOW }) =
        ListModel(state, MapWatch(WATCH), KeptView(dir, EmptyCoroutineContext), tv = false, clock, { LONDON }, { _, _ -> }, restored, EmptyCoroutineContext)

    /** The model's next screen that [holds], or a failure after [WAIT]. */
    private suspend fun ListModel.next(holds: (ListScreen) -> Boolean): ListScreen = withTimeout(WAIT) { screen.first { it != null && holds(it) }!! }

    /** Up next's row as one line: its title, its line, and a bar where it shows how far it was played. */
    private fun strip(row: StripRow): String = "${row.title} / ${row.line}" + if (row.progress != null) " ▬" else ""

    /** The days as the list draws them: each day's header, its leagues' cards, their rows and their more line. */
    private fun days(screen: ListScreen): List<String> = screen.days.flatMap { day ->
        listOf(day.header) + day.leagues.flatMap { league ->
            listOf("  ${league.name}") + league.rows.map { "    " + row(it) } + listOfNotNull(league.more?.let { "    $it" })
        }
    }

    private fun row(row: RowView): String = listOfNotNull(
        row.time,
        row.title,
        row.line,
        row.left?.let { left -> left + if (row.progress != null) " ▬" else "" },
        row.parts.takeIf { it.isNotEmpty() }?.joinToString(", ", "[", "]") { (if (it.watched) "✓" else "") + it.label },
        "✓".takeIf { row.watched },
    ).joinToString(" | ")

    /** The host's watch states, as [PluginWatch.observe] gives them: those of the keys asked. */
    private class MapWatch(private val states: Map<String, WatchInfo>) : PluginWatch {
        override fun observe(keys: Collection<String>): Flow<Map<String, WatchInfo>> = MutableStateFlow(states).map { all -> all.filterKeys { it in keys } }

        override suspend fun mark(items: List<Pair<String, String>>, watched: Boolean) = Unit
    }

    /**
     * A host for one session: Sportarr through [http], the address and key saved, [watch]'s states, a folder of its own,
     * and a scope on a thread of its own, as the host's main thread is. It counts searches and keeps what it was asked to
     * play, and never asks a play's releases.
     */
    private class FakeHost(override val http: PluginHttp, watch: Map<String, WatchInfo>, override val dataDir: File) : PluginHost {
        val searches = AtomicInteger()
        val played: MutableList<PlayItem> = java.util.concurrent.CopyOnWriteArrayList()
        private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        override val id = "sportarr"
        override val scope = CoroutineScope(SupervisorJob() + main)
        override val cacheDir: File = File(dataDir, "cache")
        override val secrets: PluginSecrets = MapSecrets(mapOf(Connection.URL to "http://127.0.0.1:1867", Connection.KEY to "kestrel0123456789abcdef0123456789"))
        override val watch: PluginWatch = MapWatch(watch)
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

        fun close() {
            scope.cancel()
            main.close()
        }
    }

    private companion object {
        val LONDON: ZoneId = ZoneId.of("Europe/London")

        /** Sunday 22 November 2026, 20:41 in London (UTC in November), as the designs draw the list. */
        val NOW: Instant = at("2026-11-22T20:41")
        /** How long a test waits for what runs on threads of its own (the session's read and file), at most. */
        const val WAIT = 20_000L

        val LIBRARY_LEAGUES: List<KeptLeague> = listOf(PREMIER, FORMULA_1, MOTO_GP, UFC, SKIING, NBA)

        /**
         * The library the list's designs draw, with invented clubs, fighters and places: Saturday's ten Premier League
         * games, two of them watched; Sunday's two, one in progress; the Grand Prix of Kestrel, its race in progress and three
         * of its sessions watched, with the rounds before and after it in Formula 1 and MotoGP; UFC 999 with its prelims
         * watched, two fight nights; a skiing event; two NBA games, Sunday's watched.
         */
        fun libraryEvents(): List<WireEvent> = listOf(
            on(PREMIER, 1, at("2026-11-21T12:30"), "Northfield Rovers vs Easton Athletic"),
            on(PREMIER, 2, at("2026-11-21T15:00"), "Wrenford Albion vs Brackenmoor FC"),
            on(PREMIER, 3, at("2026-11-21T15:00"), "Saltmere City vs Lowmarsh Rovers"),
            on(PREMIER, 4, at("2026-11-21T15:00"), "Orvanne FC vs Tollendale FC"),
            on(PREMIER, 5, at("2026-11-21T15:00"), "Thistlecombe FC vs Kestrel Vale"),
            on(PREMIER, 6, at("2026-11-21T15:00"), "Duskwater FC vs Corvane Athletic"),
            on(PREMIER, 7, at("2026-11-21T15:00"), "Ravensholt United vs Hollowmere Town"),
            on(PREMIER, 8, at("2026-11-21T17:30"), "Pellbrook Town vs Ostrey City"),
            on(PREMIER, 9, at("2026-11-21T17:30"), "Pyrecombe United vs Halvering City"),
            on(PREMIER, 10, at("2026-11-21T20:00"), "Quillon Rovers vs Sorrelton FC"),
            on(PREMIER, 11, at("2026-11-22T14:00"), "Gildenford FC vs Marrowby United"),
            on(PREMIER, 12, at("2026-11-22T16:30"), "Lantern Bay FC vs Copper Crown United"),
            on(PREMIER, 13, at("2026-11-23T20:00"), "Brindlecote FC vs Yarrowmede City"),
            on(PREMIER, 14, at("2026-11-28T15:00"), "Wrenford Albion vs Lantern Bay FC"),
            on(PREMIER, 15, at("2026-11-28T15:00"), "Copper Crown United vs Saltmere City"),
        ) + weekend(FORMULA_1, 21, "Grand Prix of Kestrel", "20", LocalDate.of(2026, 11, 20), raceAt = "14:00") +
            weekend(FORMULA_1, 31, "Grand Prix of Vellmar", "19", LocalDate.of(2026, 11, 6), raceAt = "15:00") +
            weekend(FORMULA_1, 41, "Grand Prix of Tarnholm", "21", LocalDate.of(2026, 11, 27), raceAt = "15:00", firstAt = "12:30") +
            listOf(
                on(MOTO_GP, 51, at("2026-11-14T14:00"), "Grand Prix of Oskelund - Qualifying", round = "19"),
                on(MOTO_GP, 52, at("2026-11-15T13:00"), "Grand Prix of Oskelund - Race", round = "19"),
                on(MOTO_GP, 53, at("2026-10-31T14:00"), "Grand Prix of Tarnby - Qualifying", round = "18"),
                on(MOTO_GP, 54, at("2026-11-01T13:00"), "Grand Prix of Tarnby - Race", round = "18"),
                on(UFC, 61, at("2026-11-21T23:00"), "UFC 999: Quessel vs. Halde", parts = listOf("Early Prelims", "Prelims", "Main Card")),
                on(UFC, 62, at("2026-11-28T19:00"), "UFC Fight Night: Brisk vs. Loam", parts = listOf("Prelims", "Main Card")),
                on(UFC, 63, at("2026-11-07T19:00"), "UFC Fight Night: Loam vs. Quessel", parts = listOf("Prelims", "Main Card")),
                on(SKIING, 81, at("2026-11-21T10:30"), "Kestrel · Men's Downhill"),
                on(NBA, 71, at("2026-11-20T01:30"), "Easton Comets vs Lantern Bay Herons"),
                on(NBA, 72, at("2026-11-22T01:30"), "Saltmere Gulls vs Marrowby Lynx"),
            )

        /**
         * A race weekend from [friday]: two practices that day, the first at [firstAt], the third and qualifying on
         * Saturday, the race on Sunday at [raceAt] (UTC).
         */
        fun weekend(league: KeptLeague, firstId: Int, stem: String, round: String, friday: LocalDate, raceAt: String, firstAt: String = "11:30"): List<WireEvent> {
            fun day(plus: Long, time: String) = Instant.parse("${friday.plusDays(plus)}T$time:00Z")
            return listOf(
                on(league, firstId, day(0, firstAt), "$stem - Practice 1", round = round),
                on(league, firstId + 1, day(0, "16:00"), "$stem - Practice 2", round = round),
                on(league, firstId + 2, day(1, "11:30"), "$stem - Practice 3", round = round),
                on(league, firstId + 3, day(1, "15:00"), "$stem - Qualifying", round = round),
                on(league, firstId + 4, day(2, raceAt), "$stem - Race", round = round),
            )
        }

        fun library(readAt: Instant = NOW): Sports = Sports.of(SportsList(readAt, LIBRARY_LEAGUES, libraryEvents().sortedByDescending { it.eventDate }, cut = false))

        /** The model of a list of [leagues], each with its events, read now. */
        fun build(vararg leagues: Pair<KeptLeague, List<WireEvent>>): Sports =
            Sports.of(SportsList(NOW, leagues.map { it.first }, leagues.flatMap { it.second }.sortedByDescending { it.eventDate }, cut = false))

        /**
         * The library's watch states: two of Saturday's games and Sunday's NBA game watched; the Lantern Bay game played to
         * 1:04:38 from its end, last at 20:30; the Grand Prix's first and third practices and qualifying watched, its race
         * played to 1:11:24 from its end, last at 20:00; the Vellmar qualifying watched; UFC 999's prelims watched.
         */
        val WATCH: Map<String, WatchInfo> = mapOf(
            "ev-000001:full" to watched(),
            "ev-000004:full" to watched(),
            "ev-000012:full" to WatchInfo(6_600_000 - 3_878_000, 6_600_000, false, at("2026-11-22T20:30")),
            "ev-000021:full" to watched(),
            "ev-000023:full" to watched(),
            "ev-000024:full" to watched(),
            "ev-000025:full" to WatchInfo(7_200_000 - 4_284_000, 7_200_000, false, at("2026-11-22T20:00")),
            "ev-000034:full" to watched(),
            "ev-000061:early-prelims" to watched(at("2026-11-22T10:00")),
            "ev-000061:prelims" to watched(at("2026-11-22T11:00")),
            "ev-000072:full" to watched(),
        )
    }
}
