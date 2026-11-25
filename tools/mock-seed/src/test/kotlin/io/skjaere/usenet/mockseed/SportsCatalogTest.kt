package io.skjaere.usenet.mockseed

import com.sun.net.httpserver.HttpServer
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.NewznabClient
import io.skjaere.usenet.newznab.SearchQuery
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sports fakes' one catalogue (L655): sports-catalogue.json, its library and its releases expanded from an anchor
 * date, as the fake Sportarr and the sports indexer serve them, the plugin's tests read them and the device classes find
 * their titles and expectations. The mock NNTP server is a stand-in here that records what is posted to it.
 */
class SportsCatalogTest {
    private val catalog = SportsCatalog.load()
    private val anchor = LocalDate.of(2026, 11, 22)
    private val leagues = catalog.leagues(anchor)
    private val events = leagues.flatMap { it.events }

    private val releases = catalog.releases(anchor)

    private fun league(name: String) = leagues.single { it.name == name }

    private fun release(id: String) = releases.single { it.id == id }
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

    @Test
    fun `every release's name and files take their dates from its event's start at the anchor, and its posted time too`() {
        assertTrue(releases.all { release -> (listOf(release.name) + release.files).none { '{' in it || '}' in it } }, "every template expanded")
        assertEquals("Premier.League.2026.11.21.Lantern.Bay.FC.vs.Copper.Crown.United.1080p50.WEB.h264-KESTREL", release("lbf-ccu-kestrel").name)
        assertEquals("Formula1.2026.Round20.Kestrel.Grand.Prix.Race.1080p50.WEB.h264-KESTREL", release("kestrel-race-kestrel").name)
        assertEquals("Formula1.2026x20.Race.1080p.WEB.h264-DRIFTWOOD", release("kestrel-race-running-number").name)
        // Named by the US evening's date: the day before the UTC date.
        assertEquals("NBA.2026.11.19.Easton.Comets.vs.Lantern.Bay.Herons.1080p.WEB.h264-KESTREL", release("comets-herons").name)
        assertEquals("Velmora.Monday.Brawl.2026.11.20.1080p.WEB.h264-ORCHID", release("brawl-week-3").name)
        assertEquals(event(1502).broadcastDate.toString().replace('-', '.'), release("gulls-lynx-broadcast").name.split('.').drop(1).take(3).joinToString("."))
        assertEquals("NBA.2026.11.18.Saltmere.Gulls.vs.Marrowby.Lynx.720p.WEB.h264-WREN", release("gulls-lynx-utc").name)
        // A release's file is its name, but for the weekend pack's six.
        assertEquals(listOf("${release("lbf-ccu-kestrel").name}.mkv"), release("lbf-ccu-kestrel").files)
        val pack = release("vellmar-weekend").files
        assertEquals(6, pack.size)
        assertTrue("Formula1.2026.Round19.Vellmar.Grand.Prix.Race.1080p.WEB.h264-WREN.mkv" in pack)
        // Posted hours from its event's start; Fight Night 999 two thousand days before UFC 999.
        assertEquals(Instant.parse("2026-11-21T18:00:00Z"), release("lbf-ccu-kestrel").postedAt)
        assertEquals(event(1901).start.minus(Duration.ofDays(2000)), release("ufc-fight-night-999").postedAt)
        assertEquals(event(1016).start.minus(Duration.ofHours(5)), release("quarrydown-preview").postedAt)
        assertEquals(300_000_000L, release("gildenford-marrowby-small").sizeBytes)
        // Across a new year: the year and the date of each event's own start.
        val january = catalog.releases(LocalDate.of(2027, 1, 1))
        assertEquals("Formula1.2026.Round20.Kestrel.Grand.Prix.Race.1080p50.WEB.h264-KESTREL", january.single { it.id == "kestrel-race-kestrel" }.name)
        assertEquals("Premier.League.2026.12.31.LBF.vs.CCU.720p.WEB.h264-WREN", january.single { it.id == "lbf-ccu-codes" }.name)
        // As the sports indexer lists them: TV/Sport, the size and posted time given, every file the clip.
        val fake = release("lbf-ccu-kestrel").fake()
        assertEquals(FakeCatalog.TV_SPORT, fake.category)
        assertEquals("sp-lbf-ccu-kestrel", fake.guid)
        assertEquals(6_200_000_000L, fake.sizeBytes)
        assertEquals(release("lbf-ccu-kestrel").postedAt, fake.postedAt)
        assertEquals(312, fake.grabs)
    }

    @Test
    fun `every row names an event of the library and one of its parts, with L647's verdict, reason and kind`() {
        assertEquals(releases.size, releases.map { it.id }.toSet().size)
        assertEquals(releases.size, releases.map { it.name }.toSet().size)
        val parts = setOf("full", "early-prelims", "prelims", "main-card", "pre-show", "main-show")
        releases.flatMap { release -> release.verdicts.map { release to it } }.forEach { (release, row) ->
            assertTrue(events.any { it.id == row.eventId }, "${release.id}: event ${row.eventId}")
            assertTrue(row.part in parts, "${release.id}: part ${row.part}")
            when (row.verdict) {
                SportsVerdict.Verdict.Accept -> assertTrue(row.reason == null && row.kind == null, release.id)
                SportsVerdict.Verdict.Offer -> {
                    assertTrue(row.reason in REASONS, "${release.id}: \"${row.reason}\" isn't one of L647's reasons")
                    assertTrue(row.reason!!.length <= 60 && row.kind == null, release.id)
                }
                SportsVerdict.Verdict.Reject -> assertTrue(row.kind in KINDS && row.reason == null, "${release.id}: \"${row.kind}\" isn't one of L647's kinds")
            }
        }
        // Every row says whether the plugin's queries reach it: written in the file, never left to a default.
        val written = Json.parseToJsonElement(javaClass.classLoader.getResource(SportsCatalog.RESOURCE)!!.readText()).jsonObject
        val rows = written.getValue("releases").jsonArray.map { it.jsonObject }
        assertEquals(releases.size, rows.size)
        (rows + rows.flatMap { row -> row["also"]?.jsonArray?.map { it.jsonObject }.orEmpty() }).forEach { row: JsonObject ->
            assertTrue((row["reached"] as? JsonPrimitive)?.booleanOrNull != null, "$row")
        }
    }

    @Test
    fun `every accepted row the plugin's queries reach is seeded whole, and a name only ever left out never is`() {
        releases.forEach { release ->
            if (release.verdicts.any { it.verdict == SportsVerdict.Verdict.Accept && it.reached }) assertTrue(release.whole, release.id)
            if (release.verdicts.all { it.verdict == SportsVerdict.Verdict.Reject }) assertTrue(!release.whole, release.id)
        }
        // A Generic event's offered row, which a pick plays.
        assertTrue(release("downhill").whole)
        // The release lists, by what the queries reach: the game's three, two and six; the race's, its F1-led and
        // running-number names judged but never reached.
        fun reached(event: Int) = releases.flatMap { it.verdicts }.filter { it.eventId == event && it.reached }.groupingBy { it.verdict }.eachCount()
        assertEquals(mapOf(SportsVerdict.Verdict.Accept to 3, SportsVerdict.Verdict.Offer to 2, SportsVerdict.Verdict.Reject to 6), reached(1001))
        assertEquals(mapOf(SportsVerdict.Verdict.Accept to 2, SportsVerdict.Verdict.Offer to 1, SportsVerdict.Verdict.Reject to 5), reached(1605))
        assertEquals(mapOf(SportsVerdict.Verdict.Reject to 6), reached(1016), "six results, none for this game")
        // One result-word name in each family, left out as such.
        val results = releases.filter { release -> release.verdicts.any { it.kind == "a name with a result" } }.map { it.verdicts.first().eventId }
        assertEquals(setOf(1016, 1615, 1901, 2201), results.toSet())
    }

    @Test
    fun `the command seeds the clip whole under the names that play and every other name with each article missing`(@TempDir dir: File) {
        val raw = CopyOnWriteArrayList<String>()
        val missing = CopyOnWriteArrayList<String>()
        val mock = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/mocks/") { exchange ->
                val id = Regex(""""articleId":"<([^>]+)>"""").find(exchange.requestBody.readBytes().decodeToString())!!.groupValues[1]
                when (exchange.requestURI.path) {
                    "/mocks/yenc-body/raw" -> raw += id
                    "/mocks/stat" -> missing += id
                    else -> error("unexpected ${exchange.requestURI.path}")
                }
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            start()
        }
        try {
            // One segment a file.
            val clip = File(dir, "clip.mkv").apply { writeBytes(Random(7).nextBytes(350_000)) }
            val options = mapOf(
                "port" to "0", "indexer-port" to "0", "anchor" to "2026-11-22", "video" to clip.path, "mock" to "http://127.0.0.1:${mock.address.port}",
            )
            val log = CopyOnWriteArrayList<String>()
            sports(options, log = { log += it }).use { fakes ->
                releases.forEach { release ->
                    // The seeder's article ids: seed-<guid>-<the clip's hash>-<file>-<part>; one guid can begin another.
                    val ids = Regex("seed-${Regex.escape(release.fake().guid)}-[0-9a-f]{8}-\\d+-\\d+@mock")
                    val posted = raw.count { ids.matches(it) }
                    val absent = missing.count { ids.matches(it) }
                    if (release.whole) assertEquals(release.files.size to 0, posted to absent, release.id)
                    else assertEquals(0 to release.files.size, posted to absent, release.id)
                }
                val indexer = IndexerConfig("sports", "Sports", "http://127.0.0.1:${fakes.indexer.port}", SportsFakes.INDEXER_KEY)
                val found = runBlocking { NewznabClient(NewznabClient.defaultHttpClient()).search(indexer, SearchQuery.Text("Lantern Bay Copper Crown")) }
                assertEquals(
                    releases.filter { release -> release.verdicts.any { it.eventId == 1001 && it.reached } && "Lantern" in release.name }.map { it.fake().guid }.toSet(),
                    found.items.map { it.guid }.toSet(),
                )
            }
            assertTrue(log.any { "seeded whole" in it && "sp-lbf-ccu-kestrel" in it }, log.joinToString("\n"))
            assertTrue(log.any { "every article missing" in it && "sp-lbf-ccu-score" in it }, log.joinToString("\n"))
        } finally {
            mock.stop(0)
        }
    }

    @Test
    fun `with --seed false the sports indexer lists every name in TV Sport with its size and posted time, and needs no clip`() = runBlocking<Unit> {
        sports(mapOf("port" to "0", "indexer-port" to "0", "anchor" to "2026-11-22", "seed" to "false", "video" to "none.mkv"), log = {}).use { fakes ->
            val indexer = IndexerConfig("sports", "Sports", "http://127.0.0.1:${fakes.indexer.port}", SportsFakes.INDEXER_KEY)
            val client = NewznabClient(NewznabClient.defaultHttpClient())
            val race = client.search(indexer, SearchQuery.Text("Formula1 Kestrel")).items
            val kestrel = release("kestrel-race-kestrel")
            val item = race.single { it.guid == kestrel.fake().guid }
            assertEquals(kestrel.name, item.title)
            assertEquals(kestrel.sizeBytes, item.sizeBytes)
            assertEquals(kestrel.postedAt, item.postedAt)
            assertEquals(listOf(FakeCatalog.TV_SPORT), item.categories)
            // Whole words alone, as the fake indexer matches: the F1-led name isn't among them.
            assertTrue(race.none { it.guid == release("kestrel-race-f1").fake().guid })
            assertEquals(releases.size, client.search(indexer, SearchQuery.Text("")).items.size)
        }
    }

    @Test
    fun `the command needs a clip to seed`() {
        val refused = assertFailsWith<IllegalArgumentException> {
            sports(mapOf("port" to "0", "indexer-port" to "0", "video" to "none.mkv", "mock" to "http://127.0.0.1:1"), log = {}).close()
        }
        assertContains(refused.message!!, "none.mkv missing")
    }

    private companion object {
        /** L647's reasons, its nouns' "Small for a whole <noun>" among them. */
        val REASONS = setOf(
            "No date in the name", "Team codes only", "Only a running number, no place", "Session not named in English",
            "Only one team named", "No game number", "A week number, no date", "No place or round in the name",
            "Endurance race: you pick", "Testing: you pick", "No race number in the name", "Main event only",
            "Picked by league and date", "Picked by the title's words", "One day of the match",
        ) + listOf("game", "session", "main card", "prelims", "early prelims", "fight", "show", "pre-show", "main show", "event").map { "Small for a whole $it" }

        /** L647's kinds of names left out. */
        val KINDS = setOf(
            "other games", "other sessions", "other cards", "other parts", "other fights", "other shows", "other events",
            "highlights", "a name with a score", "a name with a result", "previews and shows", "a pre-race show",
            "parts of a game", "packs", "older releases",
        )
    }
}
