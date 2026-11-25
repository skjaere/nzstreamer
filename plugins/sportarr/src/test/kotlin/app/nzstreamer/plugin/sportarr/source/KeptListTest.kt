package app.nzstreamer.plugin.sportarr.source

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L645's list.json: the kept list in the plugin's own folder, format 1, with the time it was read, the leagues kept, their
 * events with the declared fields alone and whether it was cut. Written whole through list.json.tmp, then renamed; read
 * tolerantly, so a torn or unknown file is no list; and never holding anything Sportarr sent that the plugin doesn't
 * declare.
 */
class KeptListTest {
    @TempDir
    lateinit var dir: File

    private val list = SportsList(
        readAt = Instant.parse("2026-11-24T09:41:07Z"),
        leagues = listOf(
            KeptLeague(1, "Premier League", "Soccer", "EPL"),
            KeptLeague(4, "Formula 1", "Motorsport", "F1 | Formula One"),
            KeptLeague(9, "UFC", null, null),
        ),
        events = listOf(
            event(31, Instant.parse("2026-11-22T13:00:00Z"), 4, title = "Grand Prix of Kestrel - Race", round = "20"),
            event(17, Instant.parse("2026-11-21T15:00:00Z"), 1, title = "Lantern Bay FC vs Copper Crown United", home = "Lantern Bay FC",
                away = "Copper Crown United", monitored = false).copy(broadcastDate = LocalDate.of(2026, 11, 21), broadcastTimezone = "Europe/London"),
            event(5, Instant.parse("2026-11-15T03:00:00Z"), 9, title = "UFC 999: Quessel vs. Halde", sport = "Fighting")
                .copy(externalId = null, eventTypePartNames = listOf("Early Prelims", "Prelims", "Main Card", "Post Show")),
        ),
        cut = true,
    )

    private val kept get() = KeptList(dir)

    @Test
    fun `a list written reads back the same`() = runBlocking<Unit> {
        assertTrue(kept.write(list))
        assertEquals(list, kept.read())

        val empty = SportsList(Instant.parse("2026-11-24T10:00:00Z"), emptyList(), emptyList(), cut = false)
        assertTrue(kept.write(empty))
        assertEquals(empty, kept.read())
    }

    @Test
    fun `it is written whole through list_json_tmp, then renamed`() = runBlocking<Unit> {
        kept.write(list)
        assertEquals(listOf("list.json"), dir.list()!!.sorted())
        // A temporary file a crash left behind changes nothing.
        File(dir, "list.json.tmp").writeText("{\"format\":1,")
        assertEquals(list, kept.read())
        kept.write(list.copy(cut = false))
        assertEquals(listOf("list.json"), dir.list()!!.sorted())
        assertEquals(false, kept.read()?.cut)
    }

    @Test
    fun `a torn, unknown or missing file is no list`() = runBlocking<Unit> {
        assertNull(kept.read(), "no file")
        kept.write(list)
        val file = File(dir, "list.json")
        val whole = file.readBytes()
        file.writeBytes(whole.copyOf(whole.size / 2))
        assertNull(kept.read(), "torn")
        listOf("", "null", "[]", "{}", "not json", """{"format":2,"readAt":"2026-11-24T09:41:07Z"}""").forEach { text ->
            file.writeText(text)
            assertNull(kept.read(), text)
        }
        file.delete()
        file.mkdirs()
        assertNull(kept.read(), "a folder where the file should be")
    }

    @Test
    fun `a write that can't be made keeps what was there`() = runBlocking<Unit> {
        kept.write(list)
        val blocked = File(dir, "list.json.tmp").apply { mkdirs(); File(this, "inside").writeText("x") }
        assertFalse(kept.write(list.copy(cut = false)))
        assertEquals(list, kept.read())
        blocked.deleteRecursively()
    }

    @Test
    fun `the file holds no canary value`() = runBlocking<Unit> {
        val events = assertIs<WireEvents.Whole>(Wire.events(TestSportarr.eventsJson(list.events).toByteArray())).events
        val leagues = Wire.leagues(
            TestSportarr(listOf(league(1, "Premier League")), emptyMap()).http.get("http://127.0.0.1:1867/api/leagues").body,
        )!!
        kept.write(SportsList(list.readAt, leagues.map { KeptLeague(it.id, it.name, it.sport, it.alternateName) }, events, cut = false))

        val text = File(dir, "list.json").readText()
        TestSportarr.CANARY_VALUES.forEach { canary -> assertFalse(canary in text, "list.json holds $canary") }
        listOf("homeScore", "awayScore", "status", "images", "thumbUrl", "venue", "description", "logoUrl", "posterUrl").forEach { field ->
            assertFalse("\"$field\"" in text, "list.json names $field")
        }
        assertTrue("Lantern Bay FC vs Copper Crown United" in text)
    }
}
