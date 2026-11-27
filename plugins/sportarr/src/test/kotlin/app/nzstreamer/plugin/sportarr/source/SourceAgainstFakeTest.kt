package app.nzstreamer.plugin.sportarr.source

import io.skjaere.usenet.mockseed.FakeSportarrServer
import io.skjaere.usenet.mockseed.SportsCatalog
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The plugin's client and list reader against mock-seed's fake Sportarr over loopback (L655), through a PluginHttp with
 * the host's rules: Test's answers for each of the fake's switches (L640), and the list of its invented library as L642's
 * table says, read page by page, out of order, as plain arrays, failing, with a league removed, and cut (L641). The
 * requests a refresh makes are reported (M-refresh).
 */
class SourceAgainstFakeTest {
    private val anchor = LocalDate.of(2026, 11, 22)
    private val now = anchor.atTime(12, 0).toInstant(ZoneOffset.UTC)
    private val catalog = SportsCatalog.load()
    private val library = catalog.leagues(anchor)
    private val fake = FakeSportarrServer(catalog, anchor, port = 0)
    private val http = LoopbackHttp()
    private val address = "http://127.0.0.1:${fake.port}"

    @AfterEach
    fun stop() = fake.close()

    private fun control(path: String) {
        val connection = URI("$address/control/$path").toURL().openConnection() as java.net.HttpURLConnection
        assertEquals(200, connection.responseCode, path)
        connection.disconnect()
    }

    private fun client(address: String = this.address, key: String = FakeSportarrServer.KEY) = SportarrClient(http, address, key, ::loopbackSafety)

    private fun test(address: String = this.address, key: String = FakeSportarrServer.KEY): TestAnswer = runBlocking { client(address, key).test() }

    private fun read(): ListRead = runBlocking { ListReader(client(), now, ZoneOffset.UTC).read() }

    private fun list(): SportsList = assertIs<ListRead.Read>(read()).list

    /** The fake's counts: requests by route, and by league and page. */
    private fun requests(): Pair<Map<String, Int>, Map<String, Int>> {
        val counts = Json.parseToJsonElement(URI("$address/control/counts").toURL().readText()).jsonObject
        fun map(name: String) = counts.getValue(name).jsonObject.mapValues { it.value.jsonPrimitive.int }
        return map("routes") to map("pages")
    }

    private fun titles(list: SportsList) = list.events.map { it.title }.toSet()

    private fun ids(list: SportsList) = list.events.map { it.id }.toSet()

    @Test
    fun `Test answers each of the fake's switches with L640's line`() {
        // What the Tests since the last reset asked of the fake: the status, the leagues where they got that far, and
        // never the health route; then the switches and counts go back.
        fun resetAfterTests() {
            val (routes, _) = requests()
            assertEquals(0, routes.getValue("health"), "Test reads the status and the leagues, nothing else")
            assertTrue(routes.getValue("status") > 0, "$routes")
            control("reset")
        }
        assertEquals(TestAnswer.Connected(FakeSportarrServer.VERSION), test())
        assertEquals(TestAnswer.Refused, test(key = "a-key-Sportarr-never-gave"))
        control("refuse-key")
        assertEquals(TestAnswer.Refused, test())
        resetAfterTests()
        control("version?v=4.0.998")
        assertEquals(TestAnswer.TooOld("4.0.998"), test())
        resetAfterTests()
        control("html")
        assertEquals(TestAnswer.NotSportarr, test(), "a page where Sportarr's answer should be")
        resetAfterTests()
        control("url-base?base=/sportarr")
        assertEquals(TestAnswer.Connected(FakeSportarrServer.VERSION), test("$address/sportarr/"), "the base kept, its trailing slash dropped")
        assertEquals(TestAnswer.NotSportarr, test(), "without the base: Sportarr's 404")
        resetAfterTests()
        control("fail?status=500&route=status")
        assertEquals(TestAnswer.ServerError(500), test())
        control("fail?status=503&route=leagues")
        assertEquals(TestAnswer.ServerError(503), test(), "Test's second request")
        control("fail?status=401&route=leagues")
        assertEquals(TestAnswer.Refused, test(), "the key proved on the leagues")
        resetAfterTests()
        val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertEquals(TestAnswer.Unreachable("127.0.0.1:$closed"), test("http://127.0.0.1:$closed"))
        assertEquals(TestAnswer.InvalidAddress, test("$address/?apikey=${FakeSportarrServer.KEY}"))
        val (routes, _) = requests()
        assertEquals(mapOf("status" to 0, "health" to 0, "leagues" to 0, "events" to 0), routes, "an address refused before anything is sent")
    }

    @Test
    fun `the library reads as Sportarr's league pages show it, as L642's table says`() {
        val started = System.nanoTime()
        val list = list()
        val ms = (System.nanoTime() - started) / 1_000_000
        // Every league switched on, in Sportarr's order; the one switched off isn't read.
        assertEquals(library.filter { it.monitored }.sortedWith(compareBy({ it.sport }, { it.name })).map { it.id }, list.leagues.map { it.id })
        assertTrue(list.leagues.none { it.name == "Kestrel Hockey League" })
        val read = ids(list)
        // A Future league: every game on its page, the one switched off by hand too (W163 a).
        assertTrue(setOf(1001, 1009, 1015, 1016, 1017).all { it in read })
        // Keep every game: the followed team's games and a game with its file; not a game with neither.
        assertTrue(1101 in read && 1102 in read && 1103 in read)
        assertTrue(1104 !in read && 1105 !in read)
        // Scope None with a monitored card: that card alone. Scope None with nothing monitored: the route's answer.
        assertEquals(listOf(1302), list.events.filter { it.leagueId == 4 }.map { it.id })
        assertEquals(listOf(2101), list.events.filter { it.leagueId == 12 }.map { it.id })
        // The fight leagues as Sportarr 4.1.12 names them, one as a server before it, and one of a word no family has.
        assertEquals(
            mapOf(4 to "Fighting", 10 to "Combat", 11 to "Combat", 12 to "Combat", 15 to "Exhibition"),
            list.leagues.filter { it.id in setOf(4, 10, 11, 12, 15) }.associate { it.id to it.sport },
        )
        assertTrue(list.events.filter { it.leagueId in setOf(10, 11, 12) }.all { it.sport == "Combat" })
        assertEquals(listOf("Early Prelims", "Prelims", "Main Card", "Post Show"), list.events.single { it.leagueId == 15 }.eventTypePartNames)
        // The route's answer elsewhere: the sessions chosen, the teams followed.
        assertEquals(setOf(1703, 1704, 1712, 1713), list.events.filter { it.leagueId == 8 }.map { it.id }.toSet())
        assertTrue(1503 !in read)
        // No monitorType or Keep every game sent: the route's answer as it is, the session switched off by hand too.
        assertEquals(setOf(1801, 1802), list.events.filter { it.leagueId == 9 }.map { it.id }.toSet())
        assertTrue(setOf("UFC 999: Quessel vs. Halde", "Velmora Monday Brawl", "Halde vs. Brisk", "Kestrel · Men's Downhill", "Grand Prix of Kestrel").all { it in titles(list) })
        assertEquals(58, list.events.size)
        assertEquals(false, list.cut)
        // Newest first, every event under the league whose page listed it.
        assertEquals(list.events.sortedWith(compareByDescending<WireEvent> { it.eventDate }.thenByDescending { it.id }), list.events)
        assertTrue(list.events.all { event -> library.single { it.id == event.leagueId }.events.any { it.id == event.id } })
        val (routes, pages) = requests()
        assertEquals(mapOf("status" to 0, "health" to 0, "leagues" to 1, "events" to 14), routes)
        assertTrue(pages.keys.all { it.endsWith(":1") }, "one page each: $pages")
        println("M-refresh: the standard library: ${routes.values.sum()} requests, ${list.events.size} events, $ms ms")
    }

    @Test
    fun `pages out of order read on to the last page, and keep what falls in the window`() {
        control("busy?events=1500")
        val inOrder = list()
        val (_, walked) = requests()
        // Newest first: the season ahead, the window, and the page that reaches back before it ends the walk.
        assertEquals(3, walked.keys.count { it.startsWith("${SportsCatalog.BUSY_LEAGUE_ID}:") })
        control("reset")
        control("busy?events=1500")
        control("order?dir=asc")
        val outOfOrder = list()
        val (_, read) = requests()
        assertEquals(4, read.keys.count { it.startsWith("${SportsCatalog.BUSY_LEAGUE_ID}:") }, "the order check off: every page")
        assertEquals(ids(inOrder), ids(outOfOrder))
        assertEquals(58 + 1500, inOrder.events.size)
        assertEquals(false, outOfOrder.cut)
    }

    @Test
    fun `a server that ignores paging answers each league at once, a plain array`() {
        val paged = list()
        control("reset")
        control("paging?on=false")
        val whole = list()
        assertEquals(ids(paged), ids(whole))
        val (routes, _) = requests()
        assertEquals(14, routes.getValue("events"), "one request a league")
    }

    @Test
    fun `a failing page fails the whole read, and a refused one is Refused`() {
        control("busy?events=1500")
        control("fail?status=500&route=events&page=2")
        assertEquals(ListRead.Failed(Failure.ErrorStatus), read())
        control("fail?status=500&route=leagues")
        assertEquals(ListRead.Failed(Failure.ErrorStatus), read())
        control("fail?status=401&route=events&league=1")
        assertEquals(ListRead.Refused, read())
        control("refuse-key")
        assertEquals(ListRead.Refused, read())
        control("reset")
        control("html")
        assertEquals(ListRead.Failed(Failure.Unreadable), read())
    }

    @Test
    fun `a league removed between the two reads is left out, and the rest commits`() {
        control("remove-league?id=1")
        val list = list()
        assertTrue(list.leagues.none { it.id == 1 })
        assertTrue(list.events.none { it.leagueId == 1 })
        assertEquals(58 - 17, list.events.size)
    }

    @Test
    fun `a busy library is cut at 5,000 events and at the page budget`() {
        control("busy?events=5200")
        val started = System.nanoTime()
        val list = list()
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(true, list.cut)
        assertEquals(ListReader.CAP, list.events.size)
        val (routes, pages) = requests()
        assertEquals(ListReader.PAGE_BUDGET, pages.keys.count { it.startsWith("${SportsCatalog.BUSY_LEAGUE_ID}:") })
        assertEquals(1 + 14 + ListReader.PAGE_BUDGET, routes.values.sum())
        println("M-refresh: busy (5,200 events in the window): ${routes.values.sum()} requests, ${list.events.size} events kept, $ms ms")
    }
}
