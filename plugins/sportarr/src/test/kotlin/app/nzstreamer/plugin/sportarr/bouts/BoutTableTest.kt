package app.nzstreamer.plugin.sportarr.bouts

import app.nzstreamer.plugin.sportarr.bouts.Fixtures.FIGHT_NIGHT
import app.nzstreamer.plugin.sportarr.bouts.Fixtures.NUMBERED
import app.nzstreamer.plugin.sportarr.bouts.Fixtures.UFC_999_CARD
import app.nzstreamer.plugin.sportarr.bouts.Fixtures.UFC_999_TITLE
import app.nzstreamer.plugin.sportarr.bouts.Fixtures.article
import app.nzstreamer.plugin.sportarr.bouts.Fixtures.data
import app.nzstreamer.plugin.sportarr.bouts.Fixtures.header
import app.nzstreamer.plugin.sportarr.bouts.Fixtures.rows
import app.nzstreamer.plugin.sportarr.bouts.Fixtures.vs
import app.nzstreamer.plugin.sportarr.model.UFC
import app.nzstreamer.plugin.sportarr.model.at
import app.nzstreamer.plugin.sportarr.model.on
import app.nzstreamer.plugin.sportarr.model.sports
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * L-B2's and L-B3's reading of an event's article: the card's heading in either form and the first table after it, its
 * part headers mapped to the event's parts by id, the next-part and split rules, the main event, the title flag; nothing
 * else of the page read, and a page without a card read as none.
 */
class BoutTableTest {
    private fun read(page: String, parts: List<String> = NUMBERED, title: String = UFC_999_TITLE): BoutTable.Read =
        BoutTable.read(page, "UFC 999", title, parts)

    private fun card(page: String, parts: List<String> = NUMBERED, title: String = UFC_999_TITLE): BoutCard =
        assertIs<BoutTable.Read.Out>(read(page, parts, title)).card

    /** Each part's bouts as "first/second" by part id, in running order. */
    private fun pairs(card: BoutCard): Map<String, List<String>> = card.parts.associate { part -> part.id to part.bouts.map { "${it.first}/${it.second}" } }

    private fun page(vararg rows: String): String = article(cardTable = rows(*rows))

    @Test
    fun `either heading form, before and after the event, gives the card in running order`() {
        Heading.entries.forEach { heading ->
            Form.entries.forEach { form ->
                assertEquals(UFC_999_CARD, card(article(form = form, heading = heading)), "$heading, $form")
            }
        }
        assertEquals(UFC_999_CARD, card(article(headingText = "Card", headingId = "Card")))
        assertEquals(UFC_999_CARD, card(article(headingText = "Fight card", headingId = "Fight_card_2")))
    }

    @Test
    fun `part headers map by their words, any broadcaster in brackets left out`() {
        val headers = mapOf(
            "Early preliminary card (UFC Fight Pass)" to "early-prelims",
            "Early Prelims" to "early-prelims",
            "Preliminary Card (Kestrel Sports / Kestrel+)" to "prelims",
            "Prelims" to "prelims",
            "Main card (Pay-per-view)" to "main-card",
        )
        headers.forEach { (words, id) ->
            val other = if (id == "main-card") header("Prelims") + vs("Welterweight", "Rendahl", "Varnholt") else header("Main card") + vs("Lightweight", "Quessel", "Halde")
            val read = card(page(header(words), vs("Flyweight", "Savrin", "Ambrel"), other))
            assertEquals(listOf("Ambrel/Savrin"), pairs(read)[id], words)
        }
    }

    @Test
    fun `Sportarr's own part names still get Wikipedia's bouts, by the parts' ids`() {
        val event = sports(UFC to listOf(on(UFC, 31, at("2026-11-21T23:00"), UFC_999_TITLE, parts = listOf("Early Prelims", "Lead Card", "Main Card"))))
            .events.single()
        assertEquals(listOf("Early Prelims", "Lead Card", "Main Card"), event.parts.map { it.label })
        assertEquals(UFC_999_CARD, card(article(), event.parts.map { it.id }))
    }

    @Test
    fun `on a fight night the early prelims go under Prelims, before Prelims' own bouts`() {
        val read = card(article(), FIGHT_NIGHT)
        assertEquals(listOf("prelims", "main-card"), read.parts.map { it.id })
        val early = UFC_999_CARD.parts[0].bouts
        val prelims = UFC_999_CARD.parts[1].bouts
        assertEquals(early + prelims, read.parts[0].bouts)
        assertEquals(UFC_999_CARD.parts[2], read.parts[1])
    }

    @Test
    fun `a part the event doesn't have goes under its next part the event has, though the table has no header for it`() {
        // A fight night's table with no Prelims header: its early prelims are still Prelims' bouts, never the main card's.
        val read = card(page(header("Main card"), vs("Lightweight", "Quessel", "Halde"), header("Early preliminary card"), vs("Flyweight", "Savrin", "Ambrel")), FIGHT_NIGHT)
        assertEquals(mapOf("prelims" to listOf("Ambrel/Savrin"), "main-card" to listOf("Quessel/Halde")), pairs(read))
        assertEquals(listOf("prelims", "main-card"), read.parts.map { it.id })
    }

    @Test
    fun `an unmapped header's bouts go under the next part, and one above the main card's are left out`() {
        val read = card(
            page(
                header("Featured bouts"), vs("Heavyweight", "Ostrem", "Tessmar"),
                header("Main card"), vs("Lightweight", "Quessel", "Halde"),
                header("Televised prelims"), vs("Flyweight", "Durnay", "Wexmoor"),
                header("Prelims"), vs("Welterweight", "Kjelsen", "Tollerup"),
            ),
        )
        assertEquals(mapOf("prelims" to listOf("Kjelsen/Tollerup"), "main-card" to listOf("Durnay/Wexmoor", "Quessel/Halde")), pairs(read))
    }

    @Test
    fun `the main event is the first bout under the main card's header, shown last`() {
        val read = card(article())
        val main = read.parts.flatMap { it.bouts }.filter { it.mainEvent }
        assertEquals(listOf(Bout("Lightweight", "Quessel", "Halde", titleFight = true, mainEvent = true)), main)
        assertEquals(main.single(), read.parts.last().bouts.last())
    }

    @Test
    fun `a card not split into parts is not out yet`() {
        assertEquals(BoutTable.Read.NotOut, read(page(header("Fight card"), vs("Lightweight", "Quessel", "Halde"), vs("Flyweight", "Savrin", "Ambrel"))))
        assertEquals(BoutTable.Read.NotOut, read(page(vs("Lightweight", "Quessel", "Halde"), vs("Flyweight", "Savrin", "Ambrel"))))
        assertEquals(BoutTable.Read.NotOut, read(page(header("Main card"), vs("Lightweight", "Quessel", "Halde"))))
        assertEquals(BoutTable.Read.NotOut, read(page(header("Prelims"), vs("Flyweight", "Savrin", "Ambrel"), header("Early prelims"), vs("Welterweight", "Kjelsen", "Tollerup"))))
    }

    @Test
    fun `a page with no card heading, no table after it, or no bout in its table has no card`() {
        assertEquals(BoutTable.Read.Missing, read(article(headingText = "Event", headingId = "Event")))
        assertEquals(BoutTable.Read.Missing, read(article(cardTable = null)))
        assertEquals(BoutTable.Read.Missing, read(page(header("Main card"), header("Prelims"), "<tr><th>Weight class</th><th>Method</th></tr>")))
        assertEquals(BoutTable.Read.Missing, read(page(header("Main card"), data("Lightweight", "Quessel", "beat", "Halde"), header("Prelims"), data("Flyweight", "Savrin", "vs."))))
        assertEquals(BoutTable.Read.Missing, read("<html><body><p>Nothing here</p></body></html>"))
        assertEquals(BoutTable.Read.Missing, read(""))
    }

    @Test
    fun `a section's notice box between the heading and the card is passed over, and the card read`() {
        // A maintenance notice is a table of its own at the section's top: no part header, no bout.
        val box = """<table class="box-Update plainlinks metadata ambox ambox-content" role="presentation"><tbody><tr>""" +
            """<td class="mbox-image"><span>i</span></td><td class="mbox-text"><div>This section needs to be brought up to """ +
            """date. Please help with <a href="/wiki/Kestrel">sources</a>.</div></td></tr></tbody></table>"""
        Form.entries.forEach { form ->
            assertEquals(UFC_999_CARD, card(article(form = form, cardTable = box + "\n" + Fixtures.table(Fixtures.UFC_999, form))), "$form")
        }
        assertEquals(BoutTable.Read.Missing, read(article(cardTable = box)), "a notice and no card")
    }

    @Test
    fun `nothing but the card's table is read, so no name from the lead, Background, Announced bouts or Bonus awards`() {
        Form.entries.forEach { form ->
            val names = card(article(form = form)).parts.flatMap { it.bouts }.flatMap { listOf(it.first, it.second) }
            assertEquals(26, names.size)
            // Ostrem, Ferlund, Velmar and Durnay are in the script, the comment and Announced bouts' table in pairs never fought.
            val pairs = card(article(form = form)).parts.flatMap { it.bouts }.map { setOf(it.first, it.second) }
            listOf(setOf("Ostrem", "Ambrel"), setOf("Velmar", "Tarnoy"), setOf("Ferlund", "Durnay"), setOf("Savrin", "Ferlund"), setOf("Grisholm", "Tollerup"))
                .forEach { assertEquals(false, it in pairs, "$it, $form") }
        }
    }

    @Test
    fun `a weight class and a name lose their marks`() {
        val read = card(
            page(
                header("Main card"),
                vs("""Catchweight (128 lb)<sup class="reference"><a href="#cite_note-b">[b]</a></sup>""", "Quessel&nbsp;(c)", "Halde[c]"),
                header("Prelims"),
                vs("Women&#39;s Bantamweight [d]", """<a href="/wiki/Savrin">Savrin</a> (ic)""", "Ambrel"),
            ),
        )
        assertEquals(listOf("Catchweight (128 lb)" to "Quessel/Halde", "Women's Bantamweight" to "Ambrel/Savrin"),
            read.parts.flatMap { it.bouts }.reversed().map { it.weight to "${it.first}/${it.second}" })
    }

    @Test
    fun `a title fight is one with a champion's mark or a note for the title, never a note written after the event`() {
        fun titleFights(left: String, right: String, note: String): Boolean =
            card(page(header("Main card"), vs("Lightweight", "Quessel", "Halde"), vs("Welterweight", left, right, note), header("Prelims"), vs("Flyweight", "Savrin", "Ambrel")))
                .parts.last().bouts.first().titleFight
        assertEquals(true, titleFights("Rendahl (c)", "Varnholt", ""))
        assertEquals(true, titleFights("Rendahl", "Varnholt (ic)", ""))
        assertEquals(true, titleFights("Rendahl", "Varnholt (C)", ""))
        assertEquals(true, titleFights("Rendahl", "Varnholt", "For the UFC Welterweight Championship."))
        assertEquals(true, titleFights("Rendahl", "Varnholt", "for the interim UFC Welterweight title. Varnholt missed weight."))
        assertEquals(false, titleFights("Rendahl", "Varnholt", "For the first time, a title bout went the distance."))
        assertEquals(false, titleFights("Rendahl", "Varnholt", "Varnholt was deducted one point in round 2 due to repeated fouls."))
        assertEquals(false, titleFights("Rendahl", "Varnholt", "For the record. The title was never at stake."))
        assertEquals(false, titleFights("Rendahl", "Varnholt", "Bout for the title eliminator."))
        assertEquals(false, titleFights("Rendahl", "Varnholt", ""))
    }

    @Test
    fun `a row whose third cell isn't vs or def is no bout, and too short a row neither`() {
        val read = card(
            page(
                header("Main card"),
                data("Lightweight", "Quessel", "VS", "Halde"),
                data("Heavyweight", "Ostrem", "draw", "Tessmar"),
                data("Heavyweight", "Ostrem", "vs."),
                data("Flyweight", "Durnay", "Def.", "Wexmoor", "KO", "1", "0:30"),
                header("Prelims"),
                data("Welterweight", "Kjelsen", "vs", "Tollerup"),
            ),
        )
        assertEquals(mapOf("prelims" to listOf("Kjelsen/Tollerup"), "main-card" to listOf("Durnay/Wexmoor", "Quessel/Halde")), pairs(read))
    }

    @Test
    fun `no page cut short, at any tag's edge, makes the reader throw`() {
        listOf(article(form = Form.Results), article(heading = Heading.Legacy)).forEach { page ->
            Fixtures.cuts(page).forEach { cut ->
                read(page.substring(0, cut))
                read(page.substring(0, cut), FIGHT_NIGHT)
            }
        }
        read("<h2 id=\"Results\">" + "<table>".repeat(1000))
        read("<h2 id=\"Results\"><table><tr><th colspan=\"x\">Main card</th></tr><tr><td>a</td><td>b</td><td>vs.</td><td>c</td></tr></table>")
    }
}
