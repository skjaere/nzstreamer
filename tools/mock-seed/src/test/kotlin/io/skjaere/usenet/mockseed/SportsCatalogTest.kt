package io.skjaere.usenet.mockseed

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sports fakes' one catalogue (L655): sports-catalogue.json, its library expanded from an anchor date, as the fake
 * Sportarr serves it, the plugin's tests read it and the device classes find its titles.
 */
class SportsCatalogTest {
    private val catalog = SportsCatalog.load()
    private val anchor = LocalDate.of(2026, 11, 22)
    private val leagues = catalog.leagues(anchor)
    private val events = leagues.flatMap { it.events }

    private fun league(name: String) = leagues.single { it.name == name }
    private fun event(id: Int) = events.single { it.id == id }

    @Test
    fun `every start is its day from the anchor at its UTC time, and its broadcast date the UTC date unless moved`() {
        assertEquals(1, catalog.format)
        assertEquals(Instant.parse("2026-11-21T15:00:00Z"), event(1001).start)
        assertEquals(LocalDate.of(2026, 11, 21), event(1001).broadcastDate, "Sportarr's fallback: the UTC date")
        assertNull(event(1001).broadcastTimezone)
        // A US evening game: after midnight in UTC, the evening before where it is broadcast.
        assertEquals(Instant.parse("2026-11-18T01:30:00Z"), event(1502).start)
        assertEquals(LocalDate.of(2026, 11, 17), event(1502).broadcastDate)
        assertEquals("Etc/GMT+5", event(1502).broadcastTimezone)
        assertEquals(LocalDate.of(2026, 11, 20), event(1501).broadcastDate, "a fallback looks like a broadcast date")
        assertEquals(Instant.parse("2026-11-28T14:00:00Z"), event(1625).start, "days ahead too")
        // Every league's and event's names and fields as written.
        assertEquals("Lantern Bay FC vs Copper Crown United", event(1001).title)
        assertEquals("Lantern Bay FC" to "Copper Crown United", event(1001).homeTeamName to event(1001).awayTeamName)
        assertEquals("13", event(1001).round)
        assertEquals(1, event(1001).leagueId)
        assertEquals(listOf("Countdown", "Main Show"), event(2005).partNames)
        assertNull(event(1001).partNames)
    }

    @Test
    fun `a season is the start's UTC year, or the split season it falls in, written long or short`() {
        assertEquals("2026-2027", event(1001).season, "Premier League: a split season")
        assertEquals("2026-27", event(1501).season, "NBA: a split season, written short")
        assertEquals("2026", event(1605).season)
        // Across a new year: the year of each start, and the split season from July on.
        val january = catalog.leagues(LocalDate.of(2027, 1, 1)).flatMap { it.events }
        assertEquals("2026", january.single { it.id == 1605 }.season, "the race a day before the anchor, on 31 December")
        assertEquals("2027", january.single { it.id == 1625 }.season)
        assertEquals("2026-2027", january.single { it.id == 1001 }.season)
        val july = catalog.leagues(LocalDate.of(2026, 7, 1)).flatMap { it.events }
        assertEquals("2025-2026", july.single { it.id == 1001 }.season, "30 June closes a season")
        assertEquals("2026-2027", july.single { it.id == 1012 }.season, "1 July opens one")
    }

    @Test
    fun `every event's key is unique, its externalId where it has a usable one, else local and its id`() {
        assertEquals(events.size, events.map { it.id }.toSet().size)
        assertEquals(events.size, events.map { it.key }.toSet().size)
        assertEquals(leagues.size, leagues.map { it.id }.toSet().size)
        assertEquals(leagues.size, leagues.map { it.externalId }.toSet().size)
        assertEquals("ev-001001", event(1001).key)
        assertNull(event(2201).externalId)
        assertEquals("local:2201", event(2201).key)
        assertTrue(events.all { it.key.matches(Regex("[A-Za-z0-9._:-]{1,160}")) })
        assertTrue(leagues.all { league -> league.events.all { it.leagueId == league.id } })
    }

    @Test
    fun `the library holds each case of L642's table, and the families' events`() {
        // A league switched off in Sportarr is never read.
        assertEquals(false, league("Kestrel Hockey League").monitored)
        assertTrue(league("Kestrel Hockey League").events.isNotEmpty())
        // Scope None with one monitored card: the only one listed.
        val kcl = league("KCL")
        assertEquals("None", kcl.monitorType)
        assertEquals(1, kcl.events.count { it.monitored })
        assertTrue(kcl.events.size > 1 && kcl.teamless)
        // Keep every game: a monitored game, one with a file alone, and one with neither, stored but not on the page.
        val kept = league("Kestrel Premier Division")
        assertEquals(true, kept.keepAllEvents)
        assertTrue(kept.events.any { it.monitored })
        assertTrue(kept.events.any { !it.monitored && it.hasFile })
        assertTrue(kept.events.any { !it.monitored && !it.hasFile && !it.listed })
        assertTrue(kept.events.any { !it.monitored && !it.hasFile && it.listed }, "a followed team's game switched off by hand")
        // A Future league listing a game switched off by hand (W163 a).
        val premier = league("Premier League")
        assertEquals("Future", premier.monitorType)
        assertTrue(premier.events.any { !it.monitored && it.listed } && premier.events.any { it.monitored })
        // No monitorType or Keep every game sent at all; the route's answer stands.
        assertNull(league("Kestrel Endurance Series").monitorType)
        assertNull(league("Kestrel Endurance Series").keepAllEvents)
        // Stored, not on the league's page: sessions not chosen, a game of a team not followed.
        assertTrue(league("MotoGP").events.any { !it.listed } && league("NBA").events.any { !it.listed })
        // Fighting leagues list every stored event, whatever their scope.
        assertEquals(setOf("KCL", "UFC", "Velmora Wrestling", "Kestrel Boxing"), leagues.filter { it.teamless }.map { it.name }.toSet())
        // A monitored league with nothing stored yet.
        assertTrue(leagues.any { it.monitored && it.events.isEmpty() })
        // The double header: the same sides on the same day, Game 1 and Game 2.
        val baseball = league("Kestrel Baseball League").events
        assertEquals(listOf("Game 1", "Game 2"), baseball.filter { it.title.startsWith("Kestrel Club 03 vs Kestrel Club 07") }.map { it.title.substringAfterLast(" - ") })
        // The US evening games: one broadcast date Sportarr's fallback, one the evening's.
        assertEquals(event(1501).start.atZone(java.time.ZoneOffset.UTC).toLocalDate(), event(1501).broadcastDate)
        assertTrue(event(1502).broadcastDate < event(1502).start.atZone(java.time.ZoneOffset.UTC).toLocalDate())
        // A weekly show three weeks running, a week apart, the third's broadcast date the fallback.
        val brawl = events.filter { it.title == "Velmora Monday Brawl" }.sortedBy { it.start }
        assertEquals(3, brawl.size)
        assertEquals(listOf(7L, 7L), brawl.zipWithNext { a, b -> java.time.Duration.between(a.start, b.start).toDays() })
        // Sportarr's part names where it sends them: a numbered card's with its post show, a fight night's, Night 2's.
        assertEquals(listOf("Early Prelims", "Prelims", "Main Card", "Post Show"), event(1901).partNames)
        assertEquals(listOf("Prelims", "Main Card"), event(1902).partNames)
        assertTrue(events.filter { leagues.single { l -> l.id == it.leagueId }.teamless.not() }.all { it.partNames == null })
    }

    @Test
    fun `the busy league fills the window past the cap, with a season ahead and one behind it`() {
        val busy = catalog.busy(anchor, events = 5200)
        assertEquals(SportsCatalog.BUSY_LEAGUE_ID, busy.id)
        assertEquals("Baseball", busy.sport)
        assertTrue(busy.monitored)
        val windowStart = anchor.minusDays(60).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
        val windowEnd = anchor.plusDays(32).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
        assertEquals(5200, busy.events.count { it.start >= windowStart && it.start < windowEnd })
        assertEquals(SportsCatalog.BUSY_AHEAD, busy.events.count { it.start >= windowEnd })
        assertEquals(SportsCatalog.BUSY_BEHIND, busy.events.count { it.start < windowStart })
        assertEquals(busy.events.size, busy.events.map { it.id }.toSet().size)
        assertTrue(busy.events.none { it.id in events.map { e -> e.id } })
        assertTrue(busy.events.all { it.title.matches(Regex("Kestrel Club (0[1-9]|[12][0-9]|30) vs Kestrel Club (0[1-9]|[12][0-9]|30)")) })
        assertTrue(busy.events.all { it.homeTeamName != it.awayTeamName })
    }
}
