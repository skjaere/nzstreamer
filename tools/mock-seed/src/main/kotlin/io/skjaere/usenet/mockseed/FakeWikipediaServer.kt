package io.skjaere.usenet.mockseed

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.withLock

/**
 * The fake Wikipedia (L-B12): the pages the Sportarr plugin reads a UFC event's fight card from, as invented HTML in the
 * shapes its reader takes (L-B2), so its reads can be tried from an emulator (http://10.0.2.2:8095) and never reach
 * Wikipedia. Built from [cards] (wikipedia.json): the cards of the catalogue's UFC events, each bout with its result, every
 * infobox and index row dated from [anchor] at the catalogue's day and time, as the fake Sportarr dates the events, never
 * from now.
 *
 * - `GET /wiki/<Title>`: an event's article at its title, and at each title redirecting to it as Wikipedia serves a
 *   redirect (200 at the asked address, the canonical link naming the target), in its form ([WikiForm]). The results form
 *   (after the event): heading "Results", each winner moved to the left with "def.", a draw and a no contest with "vs.",
 *   methods, rounds, times and notes, a lead and Bonus awards naming winners. The pre-event form: heading "Fight card",
 *   "vs." with empty cells, and Announced bouts. Unsplit: the card under one "Fight card" header. Bare: no card section.
 *   Missing: every title of the event a 404, and the index leaves it out. Moved: served under another title alone, in its
 *   usual form, which only the index links. Each article writes its headings as Parsoid does or as the legacy parser did
 *   ([WikiHeading]), and puts reference marks in a few cells.
 * - `GET /wiki/List_of_UFC_events`: the index, its scheduled events' table (Event, Date, Venue, Location; full month names)
 *   and its past events' (#, Event, Date, Venue, Location, Attendance; short month names), both newest first: the articles'
 *   rows and the catalogue's invented others, one a red link (an event with no article yet).
 * - Any other title, or any other path, is a 404; a query is refused (400), as the plugin never sends one; only GET.
 *
 * The canonical links name en.wikipedia.invalid, a host that never resolves, so a reader that followed a canonical link's
 * host (the plugin takes its path alone) would fail rather than reach Wikipedia. An answer is gzipped only when the
 * request's Accept-Encoding asks for gzip, and every answer sets a cookie, as Wikipedia's do, so the counts can say
 * whether a client asked for gzip and whether it sent a cookie back.
 *
 * Its switches and counts live on the fake Sportarr's control path, one lease and one reset for both
 * ([FakeSportarrServer]), which calls [form], [usualForms], [fail], [hold], [counts] and [reset]. A request held is
 * answered as every switch stands when the hold lets it go, its article's form as much as a failure. It listens on the
 * loopback address alone. Development only. [onHold] is told each time a request starts waiting out a hold: a test's seam.
 */
class FakeWikipediaServer(
    catalog: SportsCatalog,
    val anchor: LocalDate,
    port: Int,
    val cards: WikipediaCards = WikipediaCards.load(),
    private val onHold: () -> Unit = {},
) : AutoCloseable {
    /** Each event's day at [anchor]: the UTC date it starts on. */
    private val days: Map<Int, LocalDate> =
        catalog.leagues(anchor).flatMap { it.events }.associate { it.id to it.start.atZone(ZoneOffset.UTC).toLocalDate() }

    init {
        cards.articles.forEach { require(it.event in days) { "${WikipediaCards.RESOURCE}'s ${it.title} names no event of the catalogue" } }
    }

    /** The forms switched, by event; an event not in it is served in its article's usual form. */
    private val forms = ConcurrentHashMap<Int, WikiForm>()

    @Volatile private var failing: Int? = null

    /** Guards [holding]; [released] is signalled when a hold ends. */
    private val holdLock = ReentrantLock()
    private val released = holdLock.newCondition()
    private var holding = false

    /** Guards the counts and the cookies' numbers. */
    private val lock = Any()
    private val paths = LinkedHashMap<String, Int>()
    private val asked = ArrayDeque<Asked>()
    private var visits = 0L

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)

    val port: Int get() = server.address.port
    val address: InetAddress get() = server.address.address

    init {
        server.createContext("/") { exchange -> exchange.use { answer(it) } }
        // A held answer mustn't hold another request up.
        server.executor = Executors.newCachedThreadPool()
        server.start()
    }

    override fun close() {
        hold(false)
        server.stop(0)
        (server.executor as? ExecutorService)?.shutdownNow()
    }

    // The switches, which the fake Sportarr's control path sets.

    /** Whether [event] has an article here. */
    fun hasArticle(event: Int): Boolean = cards.articles.any { it.event == event }

    /** Serves [event]'s article in [form] from now on, in its usual form where [form] is null. */
    fun form(event: Int, form: WikiForm?) {
        require(hasArticle(event)) { "the fake Wikipedia has no article for event $event" }
        if (form == null) forms.remove(event) else forms[event] = form
    }

    /** Serves every article in its usual form from now on. */
    fun usualForms() = forms.clear()

    /** Answers every request with [status], one of [FAILS], from now on; as usual again where it is null. */
    fun fail(status: Int?) {
        require(status == null || status in FAILS) { "a failure is one of $FAILS" }
        failing = status
    }

    /** Holds every answer from now on, until it is called with false, which lets every answer held go. */
    fun hold(on: Boolean) = holdLock.withLock {
        holding = on
        if (!on) released.signalAll()
    }

    /** Every switch off, every answer held let go, and the counts emptied. */
    fun reset() {
        usualForms()
        fail(null)
        hold(false)
        synchronized(lock) {
            paths.clear()
            asked.clear()
        }
    }

    /**
     * The requests: `paths`, how many asked each path (as sent, percent-encoded), and `requests`, the last [KEPT_REQUESTS]
     * in order, each with its path and query, the event and the form its title named (null for any other; while it is
     * held, the form it came under, then the one it was answered in), its status (null while held), its User-Agent,
     * whether it asked for gzip and whether it sent a cookie.
     */
    fun counts(): JsonObject = synchronized(lock) {
        buildJsonObject {
            put("paths", JsonObject(paths.mapValues { JsonPrimitive(it.value) }))
            put("requests", JsonArray(asked.map { it.json() }))
        }
    }

    // The answers.

    /** A request as the counts keep it; its [form] is set again, and its [status] set, once it is answered. */
    private class Asked(
        val path: String,
        val query: String?,
        val event: Int?,
        @Volatile var form: WikiForm?,
        val userAgent: String?,
        val gzip: Boolean,
        val cookie: Boolean,
    ) {
        @Volatile var status: Int? = null

        fun json(): JsonObject = buildJsonObject {
            put("path", path)
            put("query", query)
            put("event", event)
            put("form", form?.wire)
            put("status", status)
            put("userAgent", userAgent)
            put("gzip", gzip)
            put("cookie", cookie)
        }
    }

    /** An event's [article] as a title names it now: its [form], and the title it is [served] under there (null: a 404). */
    private class Named(val article: WikiArticle, val form: WikiForm, val served: String?)

    private fun answer(exchange: HttpExchange) {
        val uri = exchange.requestURI
        val title = titleOf(uri.path)
        val arrived = title?.let(::named)
        val gzip = asksGzip(exchange.requestHeaders["Accept-Encoding"].orEmpty())
        val request = Asked(
            uri.rawPath, uri.rawQuery, arrived?.article?.event, arrived?.form, exchange.requestHeaders.getFirst("User-Agent"), gzip,
            exchange.requestHeaders.containsKey("Cookie"),
        )
        synchronized(lock) {
            paths.merge(request.path, 1, Int::plus)
            asked.addLast(request)
            if (asked.size > KEPT_REQUESTS) asked.removeFirst()
        }
        holdLock.withLock {
            if (holding) onHold()
            while (holding) released.await()
        }
        // Every switch as it stands now the hold let the request go: a form switched while it waited is the one it gets.
        val named = title?.let(::named)
        request.form = named?.form
        val failure = failing
        val served = named?.served
        val (status, body) = when {
            exchange.requestMethod != "GET" -> 405 to notice("Only GET is served here.")
            failure != null -> failure to notice("The fake's switched failure.")
            uri.rawQuery != null -> 400 to notice("The fake Wikipedia serves no query: the plugin never sends one.")
            title == INDEX -> 200 to index()
            named != null && served != null -> 200 to article(named.article, named.form, served, title)
            else -> 404 to notice("The fake Wikipedia has no article of this title.")
        }
        request.status = status
        exchange.page(status, body, gzip)
    }

    /** What [title] names: an event's article, its form now, and the title it is served under at [title], if any. */
    private fun named(title: String): Named? {
        val article = cards.articles.firstOrNull { title == it.title || title in it.redirects || title == it.moved } ?: return null
        val form = forms[article.event] ?: article.form
        val served = when (form) {
            WikiForm.Missing -> null
            WikiForm.Moved -> article.moved.takeIf { title == it }
            else -> article.title.takeIf { title != article.moved }
        }
        return Named(article, form, served)
    }

    /** [article]'s page in [form], served under [served] where [asked] asked for it: a redirect's says where it came from. */
    private fun article(article: WikiArticle, form: WikiForm, served: String, asked: String): String {
        // Moved serves the article as it usually is, under its other title.
        val shown = if (form == WikiForm.Moved) article.form else form
        val after = shown == WikiForm.Results
        val day = days.getValue(article.event)
        val page = Writer(article.heading, served)
        val body = buildString {
            append(infobox(article, day, after))
            val known = article.redirects.takeIf { it.isNotEmpty() }?.joinToString(" or ", " (also known as ", ")") { "<b>${html(it)}</b>" }.orEmpty()
            val held = "${LONG.format(day)} at ${html(article.venue)} in ${html(article.city)}"
            append("<p><b>${html(article.name)}</b>$known ")
            if (after) {
                append("was a mixed martial arts event produced by the Ultimate Fighting Championship that took place on $held. ${html(article.result)}")
            } else {
                append("is an upcoming mixed martial arts event produced by the Ultimate Fighting Championship that will take place on $held.")
            }
            append("</p>\n")
            append(page.heading("Background", "Background"))
            append("<p>${html(article.background)}</p>\n")
            when (shown) {
                WikiForm.Results -> append(page.heading("Results", "Results")).append(page.card(article, shown))
                WikiForm.Pre, WikiForm.Unsplit -> append(page.heading("Fight_card", "Fight card")).append(page.card(article, shown))
                else -> Unit
            }
            if (after) {
                append(page.heading("Bonus_awards", "Bonus awards"))
                append("<p>The following fighters received bonuses.</p>\n<ul>")
                article.bonus.forEach { line ->
                    val (award, fighters) = (line.split(": ", limit = 2) + "").take(2)
                    append("<li><b>${html(award)}:</b> ${html(fighters)}</li>")
                }
                append("</ul>\n")
            } else {
                append(page.heading("Announced_bouts", "Announced bouts"))
                append(article.announced.joinToString("", "<ul>", "</ul>\n") { "<li>${html(it)}</li>" })
            }
            append(page.references())
        }
        val redirected = if (asked == served) {
            ""
        } else {
            """<div id="contentSub"><span class="mw-redirectedfrom">(Redirected from <a href="/w/index.php?title=${pathOf(asked)}&amp;redirect=no" """ +
                """class="mw-redirect" title="${html(asked)}">${html(asked)}</a>)</span></div>""" + "\n"
        }
        return top(served) + redirected + body + BOTTOM
    }

    private fun infobox(article: WikiArticle, day: LocalDate, after: Boolean): String = buildString {
        append("""<table class="infobox vevent"><tbody>""").append('\n')
        append("""<tr><th colspan="2" class="infobox-above summary">${html(article.name)}</th></tr>""").append('\n')
        append(
            """<tr><th scope="row" class="infobox-label">Promotion</th><td class="infobox-data"><a href="/wiki/Ultimate_Fighting_Championship" """ +
                """title="Ultimate Fighting Championship">Ultimate Fighting Championship</a></td></tr>""",
        ).append('\n')
        // The day in words, then its ISO date in a hidden span, as Wikipedia's infobox writes it.
        append(
            """<tr><th scope="row" class="infobox-label">Date</th><td class="infobox-data">${LONG.format(day)}<span style="display:none">&#160;""" +
                """(<span class="bday dtstart published updated">$day</span>)</span></td></tr>""",
        ).append('\n')
        append("""<tr><th scope="row" class="infobox-label">Venue</th><td class="infobox-data">${html(article.venue)}</td></tr>""").append('\n')
        append("""<tr><th scope="row" class="infobox-label">City</th><td class="infobox-data">${html(article.city)}</td></tr>""").append('\n')
        if (after && article.attendance != null) {
            append("""<tr><th scope="row" class="infobox-label">Attendance</th><td class="infobox-data">${html(article.attendance)}</td></tr>""").append('\n')
        }
        append("</tbody></table>\n")
    }

    /** A row of List of UFC events: an article's, as its form now lists it, or one of the others. */
    private class Listed(
        val name: String,
        val title: String,
        val day: LocalDate,
        val red: Boolean,
        val venue: String,
        val city: String,
        val number: Int?,
        val attendance: String?,
    )

    private fun index(): String {
        val events = cards.articles.mapNotNull { article ->
            val day = days.getValue(article.event)
            when (forms[article.event] ?: article.form) {
                WikiForm.Missing -> null
                WikiForm.Moved -> Listed(article.moved, article.moved, day, false, article.venue, article.city, article.number, article.attendance)
                else -> Listed(article.name, article.title, day, false, article.venue, article.city, article.number, article.attendance)
            }
        }
        val others = cards.index.map { Listed(it.name, it.title, anchor.plusDays(it.day.toLong()), it.red, it.venue, it.city, it.number, it.attendance) }
        val (past, scheduled) = (events + others).sortedByDescending { it.day }.partition { it.day < anchor }
        val page = Writer(WikiHeading.Parsoid, INDEX)
        val body = buildString {
            append("<p>This is a list of events held and scheduled by the Ultimate Fighting Championship (UFC), a mixed martial arts promotion.</p>\n")
            append(page.heading("Scheduled_events", "Scheduled events"))
            append("""<table class="wikitable sortable"><tbody>""").append('\n')
            append(columns("Event", "Date", "Venue", "Location"))
            scheduled.forEach { row ->
                append(cells(event(row) + page.cite(true), LONG.format(row.day), html(row.venue), html(row.city)))
            }
            append("</tbody></table>\n")
            append(page.heading("Past_events", "Past events"))
            append("""<table class="wikitable sortable"><tbody>""").append('\n')
            append(columns("#", "Event", "Date", "Venue", "Location", "Attendance"))
            past.forEach { row ->
                append(cells(row.number?.toString().orEmpty(), event(row), SHORT.format(row.day), html(row.venue), html(row.city), html(row.attendance.orEmpty())))
            }
            append("</tbody></table>\n")
            append(page.references())
        }
        return top(INDEX) + body + BOTTOM
    }

    /** A row's Event cell: a link to its article, or a red link where it has none yet. */
    private fun event(row: Listed): String = if (row.red) {
        """<a href="/w/index.php?title=${pathOf(row.title)}&amp;action=edit&amp;redlink=1" class="new" title="${html(row.title)} (page does not exist)">${html(row.name)}</a>"""
    } else {
        """<a href="/wiki/${pathOf(row.title)}" title="${html(row.title)}">${html(row.name)}</a>"""
    }

    private fun columns(vararg words: String) = words.joinToString("", "<tr>", "</tr>\n") { """<th scope="col">$it</th>""" }

    private fun cells(vararg cells: String) = cells.joinToString("", "<tr>", "</tr>\n") { "<td>$it</td>" }

    /** An answer: [body] in UTF-8, gzipped where the request asked for it, with a cookie of its own. */
    private fun HttpExchange.page(status: Int, body: String, gzip: Boolean) {
        val plain = body.toByteArray()
        val bytes = if (gzip) ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(plain) } }.toByteArray() else plain
        responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
        responseHeaders.add("Vary", "Accept-Encoding")
        responseHeaders.add("Set-Cookie", "fakeWikiVisit=${synchronized(lock) { ++visits }}; Path=/; HttpOnly")
        if (gzip) responseHeaders.add("Content-Encoding", "gzip")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    /** One page as it is written: its headings in [style], numbered for their edit links, and its reference marks. */
    private class Writer(private val style: WikiHeading, private val title: String) {
        private var sections = 0
        private var notes = 0

        fun heading(id: String, words: String): String {
            sections++
            val edit = """<span class="mw-editsection"><span class="mw-editsection-bracket">[</span><a href="/w/index.php?title=${pathOf(title)}""" +
                """&amp;action=edit&amp;section=$sections" title="Edit section: ${html(words)}">edit</a><span class="mw-editsection-bracket">]</span></span>"""
            return when (style) {
                WikiHeading.Parsoid -> """<div class="mw-heading mw-heading2"><h2 id="$id">${html(words)}</h2>$edit</div>""" + "\n"
                WikiHeading.Legacy -> """<h2><span class="mw-headline" id="$id">${html(words)}</span>$edit</h2>""" + "\n"
            }
        }

        /** A reference mark where [cited], numbered in the page's order; nothing where not. */
        fun cite(cited: Boolean): String {
            if (!cited) return ""
            notes++
            return """<sup id="cite_ref-$notes" class="reference"><a href="#cite_note-$notes"><span class="cite-bracket">&#91;</span>$notes""" +
                """<span class="cite-bracket">&#93;</span></a></sup>"""
        }

        /**
         * [article]'s card table in [form]: each part's header (a `<th>` across eight columns) and the column headers, then
         * its bouts, the main card first and each part's last bout at its top, as Wikipedia lists them; unsplit, the whole
         * card under one "Fight card" header. After the event each winner is on the left with "def.".
         */
        fun card(article: WikiArticle, form: WikiForm): String = buildString {
            val after = form == WikiForm.Results
            val parts = if (form == WikiForm.Unsplit) {
                listOf(UNSPLIT to article.parts.reversed().flatMap { it.bouts.reversed() })
            } else {
                article.parts.reversed().map { it.header to it.bouts.reversed() }
            }
            append("""<table class="toccolours" style="font-size: 85%;"><tbody>""").append('\n')
            parts.forEach { (header, bouts) ->
                append("""<tr><th colspan="8" style="background-color: #E6E8FA; color: #000000; text-align: center;">${html(header)}</th></tr>""").append('\n')
                append(COLUMNS).append('\n')
                bouts.forEach { append(row(it, after)).append('\n') }
            }
            append("</tbody></table>\n")
        }

        private fun row(bout: WikiBout, after: Boolean): String {
            val winner = bout.winner
            val (left, right) = if (after && winner == 1) 1 to 0 else 0 to 1
            fun fighter(at: Int) = html(bout.fighters[at]) + cite(bout.cite == if (at == 0) "first" else "second")
            val cells = listOf(
                html(bout.weight) + cite(bout.cite == "weight"),
                fighter(left),
                if (after && winner != null) "def." else "vs.",
                fighter(right),
                if (after) html(bout.method) else "",
                if (after) "${bout.round}" else "",
                if (after) html(bout.time) else "",
                html(if (after) bout.resultNote else bout.note),
            )
            return cells.joinToString("", "<tr>", "</tr>") { "<td>$it</td>" }
        }

        fun references(): String = heading("References", "References") +
            (1..notes).joinToString("", """<div class="reflist"><ol class="references">""", "</ol></div>\n") { n ->
                """<li id="cite_note-$n"><span class="mw-cite-backlink"><b><a href="#cite_ref-$n">^</a></b></span> """ +
                    """<span class="reference-text">An invented source, number $n.</span></li>"""
            }
    }

    companion object {
        /** The canonical links' scheme and host: Wikipedia's name under .invalid, which never resolves. */
        const val CANONICAL_BASE = "https://en.wikipedia.invalid"

        /** The index's title. */
        const val INDEX = "List of UFC events"

        /** The statuses `/control/wiki-fail` takes. */
        val FAILS = setOf(403, 429, 503)

        /** How many requests the counts list: the fakes stay up across many device runs. */
        const val KEPT_REQUESTS = 1000

        private const val UNSPLIT = "Fight card"

        private val LONG = DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.ENGLISH)
        private val SHORT = DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.ENGLISH)

        private val COLUMNS = listOf("Weight class" to 12, "" to 20, "" to 4, "" to 20, "Method" to 20, "Round" to 4, "Time" to 5, "Notes" to 15)
            .joinToString("", "<tr>", "</tr>") { (words, width) -> """<th scope="col" style="width:$width%">$words</th>""" }

        private const val BOTTOM = "</div></div></div></div>\n</body></html>\n"

        /** A page's head and the start of its body, the canonical link naming [title]. */
        private fun top(title: String): String = """<!DOCTYPE html>
<html class="client-nojs" lang="en" dir="ltr">
<head>
<meta charset="UTF-8">
<title>${html(title)} - Wikipedia</title>
<link rel="canonical" href="$CANONICAL_BASE/wiki/${pathOf(title)}">
<script>(RLQ=window.RLQ||[]).push(function(){mw.config.set({"wgPageName":"${pathOf(title)}","wgAction":"view"});});</script>
<style>.mw-parser-output .infobox{border:1px solid #a2a9b1;float:right}</style>
</head>
<body class="mediawiki ltr skin-vector">
<div id="content" class="mw-body"><h1 id="firstHeading" class="firstHeading mw-first-heading"><span class="mw-page-title-main">${html(title)}</span></h1>
<div id="bodyContent">
<div id="mw-content-text" class="mw-body-content"><div class="mw-content-ltr mw-parser-output" lang="en" dir="ltr">
"""

        /** A small page for an answer that isn't an article's. */
        private fun notice(words: String) =
            """<!DOCTYPE html>""" + "\n" + """<html lang="en"><head><meta charset="UTF-8"><title>Fake Wikipedia</title></head><body><p>${html(words)}</p></body></html>""" + "\n"

        /** The title a path names: what follows /wiki/ (decoded), its underscores as spaces; null outside /wiki/. */
        private fun titleOf(path: String): String? =
            path.takeIf { it.startsWith("/wiki/") }?.removePrefix("/wiki/")?.replace('_', ' ')?.trim()?.takeIf { it.isNotEmpty() }

        /** Whether an Accept-Encoding of [values] asks for gzip: a gzip coding among them whose q isn't 0. */
        private fun asksGzip(values: List<String>): Boolean = values.flatMap { it.split(',') }.any { coding ->
            val params = coding.split(';').map { it.trim() }
            params.first().equals("gzip", ignoreCase = true) &&
                params.drop(1).none { it.startsWith("q=", ignoreCase = true) && it.substring(2).toDoubleOrNull() == 0.0 }
        }
    }
}

/** [title] as a path's last part: its spaces as underscores, every byte but ASCII letters, digits and `_-.:(),~` percent-encoded. */
private fun pathOf(title: String): String = buildString {
    title.replace(' ', '_').toByteArray().forEach { byte ->
        val b = byte.toInt() and 0xFF
        val c = b.toChar()
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || (b < 0x80 && c in "_-.:(),~")) append(c) else append("%%%02X".format(b))
    }
}

/** [s] escaped for HTML text and attribute values. */
private fun html(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

/**
 * The fake Wikipedia's articles (L-B12): wikipedia.json in mock-seed's resources, format 1, our own. The fight cards of the
 * sports catalogue's UFC events, each by its event's id, each part's bouts in the order fought with a result for the
 * results form, and List of UFC events' other rows, each dated by its day from the anchor. Every name is invented.
 */
class WikipediaCards private constructor(root: JsonObject) {
    /** The file's format: 1. */
    val format: Int = root.getValue("format").jsonPrimitive.int

    init {
        require(format == FORMAT) { "$RESOURCE is format $format; this mock-seed reads format $FORMAT" }
    }

    /** One article for each UFC event of the catalogue. */
    val articles: List<WikiArticle> = root.getValue("articles").jsonArray.map { it.jsonObject.article() }

    /** List of UFC events' rows besides the articles' own. */
    val index: List<WikiIndexRow> = root.getValue("index").jsonArray.map { it.jsonObject.indexRow() }

    companion object {
        /** In mock-seed's resources, beside the sports catalogue, so its jar and the plugin's tests carry it. */
        const val RESOURCE = "wikipedia.json"
        const val FORMAT = 1

        /** The articles in mock-seed's resources. */
        fun load(): WikipediaCards = parse(
            requireNotNull(WikipediaCards::class.java.classLoader.getResourceAsStream(RESOURCE)) { "$RESOURCE isn't on the classpath" }
                .use { it.readBytes().decodeToString() },
        )

        fun parse(text: String): WikipediaCards = WikipediaCards(Json.parseToJsonElement(text).jsonObject)

        private fun JsonObject.article(): WikiArticle {
            val form = WikiForm.of(string("form")!!)
            require(form != WikiForm.Missing && form != WikiForm.Moved) { "an article's usual form is one it is served in" }
            return WikiArticle(
                event = int("event"), title = string("title")!!, name = string("name")!!, redirects = strings("redirects"), moved = string("moved")!!,
                form = form, heading = WikiHeading.entries.single { it.name.equals(string("heading"), ignoreCase = true) },
                venue = string("venue")!!, city = string("city")!!, number = this["number"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.int,
                attendance = string("attendance"), background = string("background")!!, result = string("result")!!,
                announced = strings("announced"), bonus = strings("bonus"),
                parts = getValue("parts").jsonArray.map { part ->
                    WikiPart(part.jsonObject.string("header")!!, part.jsonObject.getValue("bouts").jsonArray.map { it.jsonObject.bout() })
                },
            )
        }

        private fun JsonObject.bout(): WikiBout {
            val fighters = strings("fighters")
            val winner = this["winner"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.int
            val cite = string("cite")
            require(fighters.size == 2 && (winner == null || winner in 0..1)) { "a bout is two fighters, its winner one of them or none" }
            require(cite == null || cite in CITED) { "cite is one of $CITED" }
            val note = string("note").orEmpty()
            return WikiBout(
                weight = string("weight")!!, fighters = fighters, winner = winner, method = string("method")!!, round = int("round"),
                time = string("time")!!, note = note, resultNote = string("resultNote") ?: note, cite = cite,
            )
        }

        private fun JsonObject.indexRow() = WikiIndexRow(
            name = string("name")!!, title = string("title")!!, day = int("day"), red = this["red"]?.jsonPrimitive?.boolean ?: false,
            venue = string("venue")!!, city = string("city")!!, number = this["number"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.int,
            attendance = string("attendance"),
        )

        private val CITED = setOf("weight", "first", "second")

        private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int

        private fun JsonObject.string(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

        private fun JsonObject.strings(name: String): List<String> = (this[name] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
    }
}

/**
 * An event's article: its [title] (the canonical link's), its [name] (the infobox's, the lead's and its row's in the
 * index), the titles that redirect to it, the title it is served under alone while [WikiForm.Moved], the form it is served
 * in unless switched, its headings' form, where it is held, its place among past events ([number]) and its [attendance],
 * the background and result sentences (the result's naming winners), the bouts announced for later and the bonuses, and
 * its card's parts in running order.
 */
data class WikiArticle(
    val event: Int,
    val title: String,
    val name: String,
    val redirects: List<String>,
    val moved: String,
    val form: WikiForm,
    val heading: WikiHeading,
    val venue: String,
    val city: String,
    val number: Int?,
    val attendance: String?,
    val background: String,
    val result: String,
    val announced: List<String>,
    val bonus: List<String>,
    val parts: List<WikiPart>,
)

/** A part of a card: its header's words as the table writes them, and its bouts in the order fought. */
data class WikiPart(val header: String, val bouts: List<WikiBout>)

/**
 * A bout: its [weight] class, its two [fighters] as the pre-event table lists them ("(c)" beside a champion), its [winner]
 * (0 or 1 into [fighters]; null for a draw or a no contest, listed as written in both forms), the [method], [round] and
 * [time], the notes before the event ([note]) and after it ([resultNote]), and the cell a reference mark is put on
 * ([cite]: "weight", or "first" or "second" as [fighters] lists them).
 */
data class WikiBout(
    val weight: String,
    val fighters: List<String>,
    val winner: Int?,
    val method: String,
    val round: Int,
    val time: String,
    val note: String,
    val resultNote: String,
    val cite: String?,
)

/** A row of List of UFC events besides the articles': its [name], the [title] it links, its [day] from the anchor, whether its link is [red]. */
data class WikiIndexRow(
    val name: String,
    val title: String,
    val day: Int,
    val red: Boolean,
    val venue: String,
    val city: String,
    val number: Int?,
    val attendance: String?,
)

/**
 * The forms an article is served in (`/control/wiki-form`): [Results] after the event, [Pre] before it, [Unsplit] with
 * the card under one "Fight card" header, [Bare] with no card section, [Missing] (every title a 404, left out of the
 * index) and [Moved] (served under another title alone, which only the index links).
 */
enum class WikiForm(val wire: String) {
    Results("results"),
    Pre("pre"),
    Unsplit("unsplit"),
    Bare("bare"),
    Missing("missing"),
    Moved("moved"),
    ;

    companion object {
        fun of(wire: String): WikiForm = requireNotNull(entries.firstOrNull { it.wire == wire }) { "no form $wire" }
    }
}

/** How an article writes its headings: Parsoid's `<h2 id>`, or the legacy parser's mw-headline span inside the `<h2>`. */
enum class WikiHeading { Parsoid, Legacy }
