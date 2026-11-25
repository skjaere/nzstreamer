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
import kotlinx.serialization.json.jsonPrimitive
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
        // Across a new year: the year of each start, and the split season from July on; a round's, its last event's.
        val january = catalog.leagues(LocalDate.of(2027, 1, 1)).flatMap { it.events }
        assertEquals("2026", january.single { it.id == 1605 }.season, "the race a day before the anchor, on 31 December")
        assertEquals("2027", january.single { it.id == 1625 }.season)
        assertEquals("2026-2027", january.single { it.id == 1001 }.season)
        val july = catalog.leagues(LocalDate.of(2026, 7, 1)).flatMap { it.events }
        assertEquals("2025-26", july.single { it.id == 1501 }.season, "30 June closes a season")
        assertEquals("2026-27", july.single { it.id == 1504 }.season, "1 July opens one")
        // A matchday from 28 June to 2 July: the season its last game opens.
        assertEquals(setOf("2026-2027"), july.filter { it.round == "13" }.map { it.season }.toSet())
    }

    @Test
    fun `a round has one season however its days fall, and a release's year is its events' season`() {
        val rows = Json.parseToJsonElement(javaClass.classLoader.getResource(SportsCatalog.RESOURCE)!!.readText()).jsonObject
            .getValue("releases").jsonArray.map { it.jsonObject }
        val yearly = rows.filter { "{year}" in it.getValue("name").toString() }.map { it.getValue("id").jsonPrimitive.content }.toSet()
        assertTrue(yearly.isNotEmpty())
        // Grand Prix of Vellmar's weekend across New Year: Qualifying on 31 December, the race on the 1st; then the
        // practices on the 31st. A season's turn in July too, and the usual anchor.
        listOf(LocalDate.of(2027, 1, 16), LocalDate.of(2027, 1, 17), LocalDate.of(2027, 1, 2), LocalDate.of(2026, 7, 2), anchor).forEach { day ->
            val leagues = catalog.leagues(day)
            leagues.forEach { league ->
                league.events.filter { it.round != null }.groupBy { it.round }.forEach { (round, events) ->
                    assertEquals(1, events.map { it.season }.distinct().size, "${league.name}'s round $round at $day: ${events.map { it.season }}")
                }
            }
            val library = leagues.flatMap { it.events }.associateBy { it.id }
            catalog.releases(day).filter { it.id in yearly }.forEach { release ->
                val years = Regex("""(?<!\d)(?:19|20)\d{2}(?!\d)""").findAll(release.name).map { it.value }.toSet()
                release.verdicts.forEach { row -> assertEquals(setOf(library.getValue(row.eventId).season), years, "${release.id} for ${row.eventId} at $day") }
            }
        }
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
        // Scope None with nothing monitored: the route's answer, so what it stores still lists.
        assertTrue(
            leagues.any { it.monitored && it.monitorType == "None" && it.events.isNotEmpty() && it.events.none { event -> event.monitored } },
            "a league switched on, scope None, events stored and none monitored",
        )
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
        // No monitorType or Keep every game sent at all; the route's answer stands, a session switched off by hand with it.
        assertNull(league("Kestrel Endurance Series").monitorType)
        assertNull(league("Kestrel Endurance Series").keepAllEvents)
        assertTrue(league("Kestrel Endurance Series").events.any { !it.monitored && it.listed } && league("Kestrel Endurance Series").events.any { it.monitored })
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
    fun `a dated search finds the same names on a day whose day and month are one number`() {
        // The searches L648 and L650 date with three numbers: "Premier League 2026 12 12" holds 12 twice.
        fun found(anchor: LocalDate): Map<String, Set<String>> = sports(
            mapOf("port" to "0", "indexer-port" to "0", "anchor" to "$anchor", "seed" to "false"), log = {},
        ).use { fakes ->
            val library = catalog.leagues(anchor).flatMap { it.events }.associateBy { it.id }
            fun date(id: Int) = numbers(library.getValue(id).broadcastDate)
            val indexer = IndexerConfig("sports", "Sports", "http://127.0.0.1:${fakes.indexer.port}", SportsFakes.INDEXER_KEY)
            val client = NewznabClient(NewznabClient.defaultHttpClient())
            mapOf(
                "the game" to "Premier League ${date(1001)}",
                "the game with six names, none for it" to "Premier League ${date(1016)}",
                "the weekly show's third week" to "Velmora Monday Brawl ${date(2003)}",
            ).mapValues { (_, words) -> runBlocking { client.search(indexer, SearchQuery.Text(words)) }.items.map { it.guid.removePrefix("sp-") }.toSet() }
        }
        val usual = found(anchor)
        assertEquals(7, usual.getValue("the game").size, "every name dated that day with the league's words")
        // The game on 12 December, then the game with six names on it.
        listOf(LocalDate.of(2026, 12, 13), LocalDate.of(2026, 12, 14)).forEach { day -> assertEquals(usual, found(day), "at $day") }
    }

    @Test
    fun `the plugin's searches find the names marked reached for each event and part and no other, whatever the anchor`() {
        sports(mapOf("port" to "0", "indexer-port" to "0", "anchor" to "$anchor", "seed" to "false"), log = {}).use { fakes ->
            val rows = releases.flatMap { release -> release.verdicts.map { release.id to it } }
            val parts = rows.map { (_, row) -> row.eventId to row.part }.distinct()
            fun found(anchor: LocalDate): Map<Pair<Int, String>, Set<String>> {
                val library = catalog.leagues(anchor).flatMap { it.events }.associateBy { it.id }
                val named = catalog.releases(anchor)
                return parts.associateWith { (event, part) ->
                    val (first, second) = ASKED.getValue(event)
                    fun search(stage: List<String>) = stage.flatMap { asked(it, library.getValue(event)) }.distinct()
                        .flatMap { words -> named.filter { fakes.indexer.finds(words, it.name) } }.map { it.id }.toSet()
                    val stage1 = search(first)
                    val accepted = rows.any { (id, row) -> id in stage1 && row.eventId == event && row.part == part && row.verdict == SportsVerdict.Verdict.Accept }
                    if (accepted) stage1 else stage1 + search(second)
                }
            }
            val usual = found(anchor)
            // Each name the searches find has its row for that event and part, so a page's lists can be told from the
            // rows alone; and no row marked unreached is found.
            parts.forEach { (event, part) ->
                val marked = rows.filter { (_, row) -> row.eventId == event && row.part == part }
                assertEquals(marked.filter { it.second.reached }.map { it.first }.toSet(), usual.getValue(event to part), "$event $part")
            }
            // A year and a month of start dates: every day and month, a new year, a 29 February.
            generateSequence(LocalDate.of(2027, 12, 1)) { it.plusDays(1) }.takeWhile { it.year < 2029 }.forEach { day ->
                assertEquals(usual, found(day), "at $day")
            }
        }
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
    fun `the sports indexer lasts a long run of device classes, and takes a limit of its own`() {
        fun search(fakes: SportsFakes) =
            java.net.URI("http://127.0.0.1:${fakes.indexer.port}/api?t=search&q=Kestrel&apikey=${SportsFakes.INDEXER_KEY}").toURL().readText()
        val options = mapOf("port" to "0", "indexer-port" to "0", "anchor" to "$anchor", "seed" to "false")
        sports(options, log = {}).use { fakes -> assertContains(search(fakes), """apimax="100000"""") }
        sports(options + ("api-limit" to "2"), log = {}).use { fakes ->
            repeat(2) { assertContains(search(fakes), "<rss") }
            assertContains(search(fakes), """<error code="500" description="Request limit reached"/>""")
        }
        listOf("0", "-1", "many").forEach { limit ->
            assertFailsWith<IllegalArgumentException>(limit) { sports(options + ("api-limit" to limit), log = {}).close() }
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
        /** [date] as three numbers, as the plugin dates a search: "2026 11 21". */
        fun numbers(date: LocalDate) = "%d %02d %02d".format(date.year, date.monthValue, date.dayOfMonth)

        /** [words] for [event]: {bc} its broadcast date, and its UTC date where that differs, as three numbers; {year} its UTC year. */
        fun asked(words: String, event: SportsEvent): List<String> {
            val utc = event.start.atZone(java.time.ZoneOffset.UTC).toLocalDate()
            return listOf(event.broadcastDate, utc).distinct().map { date -> words.replace("{bc}", numbers(date)).replace("{year}", "${utc.year}") }.distinct()
        }

        /**
         * The plugin's searches for each event the catalogue judges releases for, as L648 to L651 write them (item 16 holds
         * the runner's own to the rows): stage 1, then stage 2 only where stage 1 finds no name the rows accept for the
         * part.
         */
        val ASKED: Map<Int, Pair<List<String>, List<String>>> = buildMap {
            put(1001, listOf("Lantern Bay Copper Crown", "Premier League {bc}") to listOf("Copper Lantern"))
            put(1012, listOf("Gildenford Marrowby", "Premier League {bc}") to listOf("Marrowby Gildenford"))
            put(1016, listOf("Quarrydown Ellerholt", "Premier League {bc}") to listOf("Ellerholt Quarrydown"))
            put(1017, listOf("Kelderby Amberlow", "Premier League {bc}") to listOf("Amberlow Kelderby"))
            listOf(1401, 1402).forEach { put(it, listOf("Kestrel 03 Kestrel 07", "Kestrel Baseball League {bc}") to listOf("Kestrel Kestrel")) }
            put(1501, listOf("Easton Comets Lantern Bay Herons", "NBA {bc}") to listOf("Lantern Easton"))
            put(1502, listOf("Saltmere Gulls Marrowby Lynx", "NBA {bc}") to listOf("Marrowby Saltmere"))
            put(1605, listOf("Formula 1 Kestrel", "Formula1 Kestrel") to listOf("Formula 1 20"))
            (1611..1615).forEach { put(it, listOf("Formula 1 Vellmar", "Formula1 Vellmar") to listOf("Formula 1 19")) }
            put(1901, listOf("UFC 999", "UFC {bc}") to listOf("UFC Quessel Halde"))
            (2001..2003).forEach { put(it, listOf("Velmora Monday Brawl {bc}") to listOf("Velmora Monday Brawl")) }
            put(2101, listOf("Halde Brisk") to emptyList())
            put(2201, listOf("Kestrel Men's Downhill", "FIS Alpine Ski World Cup {bc}") to listOf("Kestrel Men's Downhill {year}"))
        }

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
