package app.nzstreamer.plugin.sportarr.source

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sportarr's answers as the plugin reads them (L641, L653): only the fields the wire types declare reach an object. Every
 * score, status, image, file and description an event or a league carries is parsed past and held nowhere; times are UTC
 * with or without an offset; the broadcast date is the date as written; round and season are text; an unguaranteed field
 * that can't be read is none; and a body that can't be read is a read failure, never a crash.
 */
class WireTest {
    @Test
    fun `an event with every canary field parses, and nothing it gives holds a canary value`() {
        val page = assertIs<WireEvents.Paged>(Wire.events(envelope(listOf(canaryEvent(1), canaryEvent(2)))))

        assertEquals(listOf(1, 2), page.page.records.map { it.id })
        assertEquals("Lantern Bay FC vs Copper Crown United", page.page.records.first().title)
        assertHoldsNoCanary(page)
        val declared = (WireEvent::class.java.declaredFields + WirePage::class.java.declaredFields).map { it.name }.toSet()
        assertTrue(declared.none { it in EVENT_CANARY_FIELDS }, "declared: $declared")
    }

    @Test
    fun `a league with canary description, logo, banner and poster parses and holds none of them`() {
        val leagues = assertNotNull(Wire.leagues("[${canaryLeague(7)}]".toByteArray()))

        assertEquals("Premier League", leagues.single().name)
        assertEquals("Soccer", leagues.single().sport)
        assertHoldsNoCanary(leagues)
        val declared = WireLeague::class.java.declaredFields.map { it.name }.toSet()
        assertTrue(declared.none { it in LEAGUE_CANARY_FIELDS }, "declared: $declared")
    }

    @Test
    fun `missing optional fields read as none`() {
        val event = assertIs<WireEvents.Whole>(
            Wire.events("""[{"id":3,"title":"Grand Prix of Kestrel - Race","eventDate":"2026-11-22T13:00:00Z","monitored":true,"hasFile":false}]""".toByteArray()),
        ).events.single()

        assertEquals(3, event.id)
        assertEquals(Instant.parse("2026-11-22T13:00:00Z"), event.eventDate)
        listOf(
            event.externalId, event.sport, event.leagueId, event.leagueName, event.season, event.broadcastDate, event.broadcastTimezone,
            event.round, event.homeTeamName, event.awayTeamName, event.eventTypePartNames,
        ).forEach { assertNull(it) }
        val league = assertNotNull(Wire.leagues("""[{"id":4,"name":"Formula 1","monitored":true}]""".toByteArray())).single()
        listOf(league.externalId, league.sport, league.monitorType, league.monitoredEventCount, league.keepAllEvents, league.alternateName)
            .forEach { assertNull(it) }
    }

    @Test
    fun `an envelope is one page and a plain array the whole league`() {
        val paged = assertIs<WireEvents.Paged>(
            Wire.events(
                """{"page":2,"pageSize":1000,"totalRecords":1500,"totalPages":2,"records":[${canaryEvent(9)}]}""".toByteArray(),
            ),
        ).page
        assertEquals(2, paged.page)
        assertEquals(1000, paged.pageSize)
        assertEquals(1500, paged.totalRecords)
        assertEquals(2, paged.totalPages)
        assertEquals(listOf(9), paged.records.map { it.id })

        val whole = assertIs<WireEvents.Whole>(Wire.events("\uFEFF \n[${canaryEvent(4)}, ${canaryEvent(5)}]".toByteArray())).events
        assertEquals(listOf(4, 5), whole.map { it.id })
        assertIs<WireEvents.Whole>(Wire.events("[]".toByteArray()))
    }

    @Test
    fun `a time is UTC with Z, with an offset and with none`() {
        val utc = Instant.parse("2026-11-21T15:00:00Z")
        listOf("2026-11-21T15:00:00Z", "2026-11-21T15:00:00+00:00", "2026-11-21T15:00:00", "2026-11-21T15:00:00.0000000", "2026-11-21T16:00:00+01:00")
            .forEach { written -> assertEquals(utc, event("\"eventDate\":\"$written\"").eventDate, written) }
    }

    @Test
    fun `a broken body is a read failure, never a crash`() {
        val broken = listOf(
            "", "   ", "null", "<html><body>Sportarr</body></html>", "{\"page\":1,", "[{\"id\":1,", "\"text\"", "42", "{}",
            // A guaranteed field missing, or of another type, and a start that isn't a time.
            """[{"id":1,"eventDate":"2026-11-21T15:00:00Z","monitored":true,"hasFile":false}]""",
            """[{"id":"one","title":"Kestrel Club 01 vs Kestrel Club 02","eventDate":"2026-11-21T15:00:00Z","monitored":true,"hasFile":false}]""",
            """[{"id":1,"title":"Kestrel Club 01 vs Kestrel Club 02","eventDate":"soon","monitored":true,"hasFile":false}]""",
            """{"page":1,"pageSize":1000,"totalRecords":1,"totalPages":1,"records":{"id":1}}""",
        )
        broken.forEach { body ->
            assertNull(Wire.events(body.toByteArray()), "events: $body")
            assertNull(Wire.leagues(body.toByteArray()), "leagues: $body")
        }
        listOf("", "null", "<html></html>", "[]", "{\"appName\":", "\"Sportarr\"").forEach { body ->
            assertNull(Wire.status(body.toByteArray()), "status: $body")
        }
        assertNull(Wire.events(byteArrayOf(0xC3.toByte(), 0x28, 0xFF.toByte(), 0xFE.toByte())))
    }

    @Test
    fun `every nullable declared field may come as an explicit null, as Sportarr writes it`() {
        val event = assertIs<WireEvents.Whole>(
            Wire.events(
                """[{"id":12,"externalId":null,"title":"Velmora Monday Brawl","sport":null,"leagueId":null,"leagueName":null,"season":null,
                "seasonNumber":null,"episodeNumber":null,"eventDate":"2026-11-23T01:00:00Z","broadcastDate":null,"broadcastTimezone":null,
                "monitored":false,"hasFile":false,"round":null,"homeTeamName":null,"awayTeamName":null,"eventTypePartNames":null,
                "homeScore":null,"awayScore":null,"status":null,"images":null,"thumbUrl":null}]""".toByteArray(),
            ),
        ).events.single()
        assertEquals("Velmora Monday Brawl", event.title)
        listOf(
            event.externalId, event.sport, event.leagueId, event.leagueName, event.season, event.broadcastDate, event.broadcastTimezone,
            event.round, event.homeTeamName, event.awayTeamName, event.eventTypePartNames,
        ).forEach { assertNull(it) }

        val league = assertNotNull(
            Wire.leagues(
                """[{"id":5,"externalId":null,"name":"KCL","sport":null,"country":null,"monitored":true,"rootFolderId":null,"path":null,
                "tags":null,"monitorType":null,"monitoredEventCount":null,"keepAllEvents":null,"alternateName":null,"description":null}]""".toByteArray(),
            ),
        ).single()
        assertEquals("KCL", league.name)
        listOf(league.externalId, league.sport, league.monitorType, league.monitoredEventCount, league.keepAllEvents, league.alternateName)
            .forEach { assertNull(it) }

        assertEquals(WireStatus(null, null), Wire.status("""{"appName":null,"version":null}""".toByteArray()))
    }

    @Test
    fun `round and season are text, never a number`() {
        listOf("\"20\"" to "20", "\"Week 10\"" to "Week 10", "\"Final\"" to "Final", "null" to null).forEach { (written, read) ->
            assertEquals(read, event("\"round\":$written").round, written)
        }
        assertEquals("2025-2026", event("\"season\":\"2025-2026\"").season)
        assertEquals("2024-25", event("\"season\":\"2024-25\"").season)
        // Sends text today; a number would read as its digits rather than fail the whole read.
        assertEquals("20", event("\"round\":20").round)
        assertEquals("2026", event("\"season\":2026").season)
    }

    @Test
    fun `a broadcast date is the date as written, never moved into a zone`() {
        val before = TimeZone.getDefault()
        try {
            // A zone five hours behind UTC, where midnight UTC is still the evening before.
            TimeZone.setDefault(TimeZone.getTimeZone("GMT-05:00"))
            assertEquals(LocalDate.of(2026, 11, 21), event("\"broadcastDate\":\"2026-11-21T00:00:00Z\"").broadcastDate)
            assertEquals(LocalDate.of(2026, 11, 21), event("\"broadcastDate\":\"2026-11-21T23:30:00-08:00\"").broadcastDate)
            assertEquals(LocalDate.of(2026, 11, 21), event("\"broadcastDate\":\"2026-11-21\"").broadcastDate)
        } finally {
            TimeZone.setDefault(before)
        }
        // Not a date: none, as when Sportarr has no broadcast date.
        listOf("\"\"", "\"Saturday\"", "\"2026-13-40\"", "7", "{}").forEach { written ->
            assertNull(event("\"broadcastDate\":$written").broadcastDate, written)
        }
    }

    @Test
    fun `an unguaranteed field that can't be read is none, and the rest still reads`() {
        val league = assertNotNull(
            Wire.leagues(
                """[{"id":6,"name":"Kestrel Premier Division","sport":"Soccer","monitored":true,"monitorType":{"kind":"Future"},
                "monitoredEventCount":"many","keepAllEvents":"perhaps","alternateName":["Kestrel Premier"]}]""".toByteArray(),
            ),
        ).single()
        assertEquals("Kestrel Premier Division", league.name)
        listOf(league.monitorType, league.monitoredEventCount, league.keepAllEvents, league.alternateName).forEach { assertNull(it) }
        assertEquals(
            WireLeague(6, null, "Kestrel Premier Division", "Soccer", true, "Future", 3, true, "Kestrel Premier | Kestrel Division"),
            assertNotNull(
                Wire.leagues(
                    """[{"id":6,"name":"Kestrel Premier Division","sport":"Soccer","monitored":true,"monitorType":"Future",
                    "monitoredEventCount":3,"keepAllEvents":true,"alternateName":"Kestrel Premier | Kestrel Division"}]""".toByteArray(),
                ),
            ).single(),
        )

        val event = event("\"round\":{\"n\":20},\"homeTeamName\":[\"Lantern Bay FC\"],\"awayTeamName\":7,\"eventTypePartNames\":\"Main Card\"")
        assertNull(event.round)
        assertNull(event.homeTeamName)
        assertEquals("7", event.awayTeamName)
        assertNull(event.eventTypePartNames)
        assertEquals(
            listOf("Early Prelims", "Prelims", "Main Card", "Post Show"),
            event("\"eventTypePartNames\":[\"Early Prelims\",\"Prelims\",\"Main Card\",\"Post Show\"]").eventTypePartNames,
        )
        assertEquals(emptyList(), event("\"eventTypePartNames\":[]").eventTypePartNames)
    }

    @Test
    fun `the status reads Sportarr's name and version`() {
        assertEquals(
            WireStatus("Sportarr", "4.1.12.1122"),
            Wire.status(
                """{"appName":"Sportarr","instanceName":"Sportarr","version":"4.1.12.1122","osName":"CANARY-os","startTime":"2026-11-24T08:00:00Z"}"""
                    .toByteArray(),
            ),
        )
        assertEquals(WireStatus(null, null), Wire.status("{}".toByteArray()))
    }

    /** One event with the declared fields a test doesn't set, and [fields] in place of the ones it does. */
    private fun event(fields: String): WireEvent {
        val given = Regex("\"(\\w+)\":").findAll(fields).map { it.groupValues[1] }.toSet()
        val base = listOf(
            "\"id\":31", "\"title\":\"Kestrel Club 01 vs Kestrel Club 02\"", "\"eventDate\":\"2026-11-21T15:00:00Z\"", "\"monitored\":true",
            "\"hasFile\":false",
        ).filter { field -> Regex("\"(\\w+)\":").find(field)!!.groupValues[1] !in given }
        return assertIs<WireEvents.Whole>(Wire.events("[{${(base + fields).joinToString(",")}}]".toByteArray()), fields).events.single()
    }

    private fun envelope(records: List<String>) =
        """{"page":1,"pageSize":1000,"totalRecords":${records.size},"totalPages":1,"records":[${records.joinToString(",")}]}""".toByteArray()

    /** Fails when any object [read] gives, its fields' values or its words, holds a canary value. */
    private fun assertHoldsNoCanary(read: Any) {
        val seen = buildString {
            append(read.toString())
            values(read).forEach { append('\n').append(it) }
        }
        CANARY_VALUES.forEach { canary -> assertFalse(canary in seen, "a canary value reached an object: $canary") }
    }

    /** Every value inside [value], the plugin's own objects walked field by field through reflection. */
    private fun values(value: Any?): Sequence<Any?> = when {
        value == null -> sequenceOf(null)
        value is Iterable<*> -> value.asSequence().flatMap { values(it) }
        value.javaClass.name.startsWith("app.nzstreamer.plugin.sportarr.") -> value.javaClass.declaredFields.asSequence()
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .flatMap { field -> field.isAccessible = true; values(field.get(value)) }
        else -> sequenceOf(value)
    }

    private companion object {
        /** L653's fields an event carries and the plugin never declares. */
        val EVENT_CANARY_FIELDS = setOf(
            "homeScore", "awayScore", "status", "images", "thumbUrl", "venue", "location", "broadcast", "files", "filePath", "fileSize",
            "quality", "partStatuses", "dvrInfo", "description", "leagueLogoUrl",
        )
        val LEAGUE_CANARY_FIELDS = setOf("description", "logoUrl", "bannerUrl", "posterUrl", "images")

        val CANARY_VALUES = listOf(
            "CANARY-home-score", "CANARY-away-score", "CANARY-status", "canary.invalid", "CANARY-venue", "CANARY-location",
            "CANARY-broadcast", "CANARY-quality", "CANARY-part-status", "CANARY-dvr", "CANARY-description", "7311311311", "86421",
            "CANARY-league-description",
        )

        fun canaryEvent(id: Int) = """
            {"id":$id,"externalId":"ev-${id.toString().padStart(6, '0')}","title":"Lantern Bay FC vs Copper Crown United","sport":"Soccer",
            "leagueId":7,"leagueName":"Premier League","season":"2026-2027","seasonNumber":2026,"episodeNumber":$id,
            "eventDate":"2026-11-21T15:00:00Z","broadcastDate":"2026-11-21T00:00:00Z","broadcastTimezone":"Europe/London",
            "monitored":true,"hasFile":false,"round":"12","homeTeamName":"Lantern Bay FC","awayTeamName":"Copper Crown United",
            "eventTypePartNames":null,
            "homeScore":"CANARY-home-score-3","awayScore":86421,"status":"CANARY-status-final",
            "images":[{"coverType":"poster","url":"http://canary.invalid/poster.jpg"}],"thumbUrl":"http://canary.invalid/thumb.jpg",
            "venue":"CANARY-venue","location":"CANARY-location","broadcast":"CANARY-broadcast",
            "files":[{"path":"/canary/CANARY-quality.mkv","size":7311311311}],"filePath":"/canary/event.mkv","fileSize":7311311311,
            "quality":"CANARY-quality-1080p","partStatuses":[{"part":"Main Card","status":"CANARY-part-status"}],
            "dvrInfo":{"channel":"CANARY-dvr"},"description":"CANARY-description of the game","leagueLogoUrl":"http://canary.invalid/logo.png"}
        """.trimIndent()

        fun canaryLeague(id: Int) = """
            {"id":$id,"externalId":"4328","name":"Premier League","sport":"Soccer","country":"England","monitored":true,"rootFolderId":1,
            "path":"/canary/league","tags":[],"sportFormat":"Team","monitorType":"Future","keepAllEvents":false,"alternateName":"EPL",
            "monitoredEventCount":38,"description":"CANARY-league-description","logoUrl":"http://canary.invalid/logo.png",
            "bannerUrl":"http://canary.invalid/banner.png","posterUrl":"http://canary.invalid/poster.png",
            "images":[{"url":"http://canary.invalid/still.png"}]}
        """.trimIndent()
    }
}
