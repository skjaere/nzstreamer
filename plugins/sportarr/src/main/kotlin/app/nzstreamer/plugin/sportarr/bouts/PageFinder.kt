package app.nzstreamer.plugin.sportarr.bouts

import app.nzstreamer.plugin.sportarr.model.CardType
import app.nzstreamer.plugin.sportarr.model.Family
import app.nzstreamer.plugin.sportarr.model.SportsEvent
import app.nzstreamer.plugin.sportarr.source.Families
import app.nzstreamer.plugin.sportarr.source.UtcTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs

/**
 * Finds a UFC event's Wikipedia article (L-B1) through [requests], each address built on [base] by [address], within
 * L-B6's budget: up to three candidate titles from Sportarr's title, one after the other, a 404 the next; then, only when
 * every candidate missed, `List of UFC events` (its rows kept a day in [index], so it is asked once a day at most) and the
 * one article a row of it links. A page is the event's only when its canonical link names a title starting "UFC " and its
 * infobox's date is within a day of the event's start in UTC or of its broadcast date.
 *
 * Pages are decoded and read on [work], off the main thread. One that throws while it is read holds no card: a candidate's
 * is a miss, the index's no page; the line logged names the exception's class alone. [clock] says the index copy's age.
 */
internal class PageFinder(
    private val base: String,
    private val requests: WikipediaRequests,
    private val index: IndexCopy,
    private val clock: () -> Instant,
    private val work: CoroutineContext = Dispatchers.Default,
) {
    /** What a search found. */
    sealed interface Found {
        /** The event's article, titled [title] as its canonical link names it, and its [page]. */
        class Article(val title: String, val page: String) : Found

        /** No article for the event. */
        data object NoPage : Found

        /** A request failed or was held ([why]: a status, a kind, "held"): nothing can be said of the page. */
        data class Failed(val why: String) : Found
    }

    /** The index's rows as last read, in memory as on disk. */
    private var copy: KeptIndex? = null

    /** [event]'s article: the candidates in order, then the index's one matching row. */
    suspend fun find(event: SportsEvent): Found {
        candidates(event.title).forEach { title ->
            when (val fetched = requests.get(address(base, title), ARTICLE_BYTES)) {
                is Fetch.Failed -> return Found.Failed(fetched.why)
                Fetch.Miss -> Unit
                is Fetch.Page -> accepted(fetched.body, event)?.let { return it }
            }
        }
        return fromIndex(event)
    }

    /** Forgets the index's rows: its copy in memory and on disk (the switch turned off, L-B8). */
    suspend fun forget() {
        copy = null
        index.delete()
    }

    /** The article in [body], where it is [event]'s by its canonical title and its date; else null, as a miss. */
    private suspend fun accepted(body: ByteArray, event: SportsEvent): Found.Article? = try {
        withContext(work) {
            val page = body.decodeToString()
            val (canonical, date) = head(page)
            val title = canonical?.let { titleOf(pathOf(it)) }
            if (title != null && title.startsWith("UFC ") && date != null && near(date, event)) Found.Article(title, page) else null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        log.warn("A Wikipedia page was unreadable: {}", e.javaClass.simpleName)
        null
    }

    /** The index's one row matching [event], read and checked as a candidate is; none or several are no page. */
    private suspend fun fromIndex(event: SportsEvent): Found {
        val rows = when (val indexed = indexRows()) {
            is Indexed.Rows -> indexed.rows
            Indexed.NoPage -> return Found.NoPage
            is Indexed.Failed -> return Found.Failed(indexed.why)
        }
        val matched = rows.filter { matches(it, event) }.distinctBy { it.article }
        val row = matched.singleOrNull() ?: return Found.NoPage
        return when (val fetched = requests.get(address(base, row.article), ARTICLE_BYTES)) {
            is Fetch.Failed -> Found.Failed(fetched.why)
            Fetch.Miss -> Found.NoPage
            is Fetch.Page -> accepted(fetched.body, event) ?: Found.NoPage
        }
    }

    private sealed interface Indexed {
        class Rows(val rows: List<IndexRow>) : Indexed

        data object NoPage : Indexed

        class Failed(val why: String) : Indexed
    }

    /** The index's rows: the copy where it is under a day old, else read now and kept for a day. */
    private suspend fun indexRows(): Indexed {
        val now = clock()
        val kept = copy ?: index.read()?.also { copy = it }
        if (kept != null && !kept.readAt.isAfter(now) && Duration.between(kept.readAt, now) < INDEX_KEPT) return Indexed.Rows(kept.rows)
        return when (val fetched = requests.get(address(base, INDEX), INDEX_BYTES)) {
            is Fetch.Failed -> Indexed.Failed(fetched.why)
            Fetch.Miss -> Indexed.NoPage
            is Fetch.Page -> {
                val rows = try {
                    withContext(work) { indexRows(fetched.body.decodeToString()) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log.warn("Wikipedia's list of UFC events was unreadable: {}", e.javaClass.simpleName)
                    return Indexed.NoPage
                }
                val read = KeptIndex(KeptIndex.FORMAT, now, rows)
                copy = read
                index.write(read)
                Indexed.Rows(rows)
            }
        }
    }

    /**
     * Whether [row] is [event]'s: its date within a day of the event's, and its link text holding the event's number
     * ("UFC 999", "Fight Night 888") or both surnames of the title's sides.
     */
    private fun matches(row: IndexRow, event: SportsEvent): Boolean {
        val date = try {
            LocalDate.parse(row.date)
        } catch (e: DateTimeException) {
            return false
        }
        if (!near(date, event)) return false
        val words = Html.words(row.text)
        val number = number(event.title)
        if (number != null && words.windowed(number.size).any { it == number }) return true
        val (home, away) = Families.sides(event.title) ?: return false
        // A surname as the pairs key it, whole, or as the text's words split it.
        val keys = (words + Pairs.keys(row.text)).toSet()
        return Pairs.surnameKey(home) in keys && Pairs.surnameKey(away) in keys
    }

    companion object {
        /** An article is about 330 KB of HTML: the host's default bound. */
        const val ARTICLE_BYTES = 4 * 1024 * 1024

        /** The index is about 3.1 MB, and grows: the default would refuse it past 4 MB. */
        const val INDEX_BYTES = 8 * 1024 * 1024

        /** The article that lists every UFC event, read only when every candidate missed. */
        const val INDEX = "List of UFC events"

        private val INDEX_KEPT: Duration = Duration.ofDays(1)

        /**
         * Whether [event]'s page reads a card: a fight card, numbered or a fight night, whose title's first word is UFC.
         * Any other event's page is as before: no request, no line (W1).
         */
        fun reads(event: SportsEvent): Boolean =
            event.family == Family.FightCard && (event.cardType == CardType.Numbered || event.cardType == CardType.FightNight) &&
                Html.words(event.title).firstOrNull() == "UFC"

        /**
         * The titles asked for an event titled [title], trimmed and its spaces collapsed, in order, each once: "UFC <n>"
         * where it starts "UFC" and a number; the title as written; "UFC Fight Night <n>" where it starts "UFC Fight Night"
         * and a number.
         */
        fun candidates(title: String): List<String> {
            val written = Html.collapse(title)
            val numbered = NUMBERED.find(written)?.let { "UFC ${it.groupValues[1]}" }
            val night = NUMBERED_NIGHT.find(written)?.let { "UFC Fight Night ${it.groupValues[1]}" }
            return listOfNotNull(numbered, written.takeIf { it.isNotEmpty() }, night).distinct()
        }

        /**
         * The address of the article titled [title] on [base]: `/wiki/` and the title, its spaces as underscores and every
         * character but ASCII letters, digits and `_-.~:(),` percent-encoded as UTF-8; never a query. Every request, and
         * View on Wikipedia, is built here.
         */
        fun address(base: String, title: String): String = buildString {
            append(base.trimEnd('/')).append("/wiki/")
            Html.collapse(title).replace(' ', '_').toByteArray(Charsets.UTF_8).forEach { byte ->
                val b = byte.toInt() and 0xFF
                val c = b.toChar()
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || (b < 0x80 && c in KEPT)) {
                    append(c)
                } else {
                    append('%').append(HEX[b shr 4]).append(HEX[b and 0xF])
                }
            }
        }

        /** A title from an article's path: what follows /wiki/ (or Parsoid's ./), percent-decoded, underscores as spaces. */
        internal fun titleOf(path: String): String? {
            val raw = when {
                path.startsWith("/wiki/") -> path.removePrefix("/wiki/")
                path.startsWith("./") -> path.removePrefix("./")
                else -> return null
            }
            return Html.collapse(percentDecoded(raw).replace('_', ' ')).takeIf { it.isNotEmpty() }
        }

        /** The path of an address: after its scheme and host where it has them, before any query or fragment. */
        internal fun pathOf(href: String): String {
            val bare = href.trim().substringBefore('#').substringBefore('?')
            val host = when {
                bare.contains("://") -> bare.indexOf("://") + 3
                bare.startsWith("//") -> 2
                else -> return bare
            }
            val slash = bare.indexOf('/', host)
            return if (slash < 0) "" else bare.substring(slash)
        }

        /**
         * The title a row's link names, or null where it names none: an address with a query (a red link's
         * `/w/index.php?…&redlink=1`) or outside /wiki/, or a link whose class holds "new".
         */
        internal fun linked(link: HtmlLink): String? {
            if ("new" in link.classes.split(SPACES)) return null
            if ('?' in link.href) return null
            return titleOf(link.href.trim().substringBefore('#'))
        }

        /** [text] with its %XX sequences decoded as UTF-8; a % not followed by two hex digits stays as written. */
        private fun percentDecoded(text: String): String {
            if ('%' !in text) return text
            val bytes = ByteArrayOutputStream(text.length)
            var i = 0
            while (i < text.length) {
                val high = if (text[i] == '%' && i + 2 < text.length) Character.digit(text[i + 1], 16) else -1
                val low = if (high >= 0) Character.digit(text[i + 2], 16) else -1
                if (high >= 0 && low >= 0) {
                    bytes.write(high * 16 + low)
                    i += 3
                } else {
                    val point = text.codePointAt(i)
                    bytes.write(String(Character.toChars(point)).toByteArray(Charsets.UTF_8))
                    i += Character.charCount(point)
                }
            }
            return bytes.toByteArray().decodeToString()
        }

        /** The canonical link's address and the first infobox's Date row's ISO date, each null where the page has none. */
        private fun head(page: String): Pair<String?, LocalDate?> {
            val reader = HtmlReader(page)
            var canonical: String? = null
            while (true) {
                val token = reader.next() ?: return canonical to null
                if (token !is HtmlToken.Start) continue
                if (token.name == "link" && canonical == null && "canonical" in token.attributes["rel"].orEmpty().split(SPACES)) {
                    canonical = token.attributes["href"]
                }
                if (token.name == "table" && !token.selfClosing && token.hasClass("infobox")) {
                    val row = reader.table().firstOrNull { row -> row.cells.any { it.header && it.text.equals("Date", ignoreCase = true) } }
                    val written = row?.cells?.filter { !it.header }?.firstNotNullOfOrNull { ISO_DATE.find(it.text) }
                    return canonical to written?.let { iso(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
                }
            }
        }

        /** Whether [date] is within a day of [event]'s start in UTC, or of its broadcast date (the venue's day can differ). */
        private fun near(date: LocalDate, event: SportsEvent): Boolean =
            listOfNotNull(event.start.atOffset(ZoneOffset.UTC).toLocalDate(), event.broadcastDate)
                .any { abs(ChronoUnit.DAYS.between(it, date)) <= 1 }

        /** The number a title gives its event by: "UFC 999" for a numbered card, "Fight Night 888" for a fight night. */
        private fun number(title: String): List<String>? {
            val written = Html.collapse(title)
            NUMBERED.find(written)?.let { return listOf("UFC", it.groupValues[1]) }
            return ANY_NIGHT.find(written)?.let { listOf("FIGHT", "NIGHT", it.groupValues[1]) }
        }

        /**
         * The index's rows (L-B2): from every table whose header row has `<th>` cells reading "Event" and "Date", those two
         * columns alone, found by their headers' words (the past events' table starts with "#"). Each row's Event cell's
         * first link that names an article, its text, and its Date cell's day ("Dec 12, 2026", "December 12, 2026"); a row
         * without either is left out.
         */
        internal fun indexRows(page: String): List<IndexRow> {
            val reader = HtmlReader(page)
            val rows = ArrayList<IndexRow>()
            while (true) {
                val token = reader.next() ?: return rows
                if (token is HtmlToken.Start && token.name == "table" && !token.selfClosing) rows += indexTable(reader.table())
            }
        }

        private fun indexTable(table: List<HtmlRow>): List<IndexRow> {
            val head = table.indexOfFirst { row -> row.cells.any { it.header } }
            if (head < 0) return emptyList()
            val columns = placed(table[head].cells)
            fun column(word: String) = columns.entries.firstOrNull { (_, cell) -> cell.header && cell.text.equals(word, ignoreCase = true) }?.key
            val event = column("Event") ?: return emptyList()
            val date = column("Date") ?: return emptyList()
            // Cells spanning rows below them, by column, with how many rows they still cover.
            var above = emptyMap<Int, Pair<HtmlCell, Int>>()
            return table.drop(head + 1).mapNotNull { row ->
                val cells = HashMap<Int, HtmlCell>()
                above.forEach { (at, spanning) -> cells[at] = spanning.first }
                val below = HashMap<Int, Pair<HtmlCell, Int>>()
                above.forEach { (at, spanning) -> if (spanning.second > 1) below[at] = spanning.first to spanning.second - 1 }
                var at = 1
                row.cells.forEach { cell ->
                    while (at in cells) at++
                    (at until at + cell.columns).forEach { spanned ->
                        cells[spanned] = cell
                        if (cell.rows > 1) below[spanned] = cell to cell.rows - 1
                    }
                    at += cell.columns
                }
                above = below
                val linked = cells[event]?.takeIf { it !== cells[date] }?.link?.let { link -> linked(link)?.let { it to link.text } }
                val day = cells[date]?.let { day(it.text) }
                if (linked == null || day == null) null else IndexRow(linked.first, linked.second, day.toString())
            }
        }

        /** A header row's cells by the column each starts at. */
        private fun placed(cells: List<HtmlCell>): Map<Int, HtmlCell> {
            var at = 1
            return cells.associateBy { cell -> at.also { at += cell.columns } }
        }

        /** A day as the index writes it, "Dec 12, 2026" or "December 12, 2026"; null where it reads as none. */
        private fun day(text: String): LocalDate? {
            val (month, day, year) = WRITTEN_DAY.find(text)?.destructured ?: return null
            val number = MONTHS[month.lowercase(Locale.ROOT)] ?: return null
            return try {
                LocalDate.of(year.toInt(), number, day.toInt())
            } catch (e: DateTimeException) {
                null
            }
        }

        private fun iso(year: String, month: String, day: String): LocalDate? = try {
            LocalDate.of(year.toInt(), month.toInt(), day.toInt())
        } catch (e: DateTimeException) {
            null
        }

        private const val KEPT = "_-.~:(),"
        private const val HEX = "0123456789ABCDEF"
        private val NUMBERED = Regex("""^UFC (\d+)(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)
        private val NUMBERED_NIGHT = Regex("""^UFC Fight Night (\d+)(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)
        private val ANY_NIGHT = Regex("""(?<![\p{L}\p{N}])Fight Night (\d+)(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)
        private val ISO_DATE = Regex("""(\d{4})-(\d{2})-(\d{2})""")
        private val WRITTEN_DAY = Regex("""([A-Za-z]+)\.? (\d{1,2}), (\d{4})""")
        private val SPACES = Regex("""\s+""")
        private val MONTHS: Map<String, Int> = listOf(
            "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
        ).flatMapIndexed { i, name -> listOf(name to i + 1, name.take(3) to i + 1) }.toMap() + ("sept" to 9)

        private val log = LoggerFactory.getLogger(PageFinder::class.java)
    }
}

/** A row of the index as kept: the [article] its link names, the link's [text], and its [date] (yyyy-mm-dd). Nothing else. */
@Serializable
internal data class IndexRow(val article: String, val text: String, val date: String)

/** The index's rows as read at [readAt], kept a day (L-B5). */
@Serializable
internal data class KeptIndex(
    val format: Int,
    @Serializable(with = UtcTime::class) val readAt: Instant,
    val rows: List<IndexRow>,
) {
    companion object {
        const val FORMAT = 1
    }
}

/** Where the index's rows are kept between reads: [IndexFile], or a test's stand-in. */
internal interface IndexCopy {
    /** The rows kept, or null where none can be read. */
    suspend fun read(): KeptIndex?

    /** Keeps [index] in place of the last. */
    suspend fun write(index: KeptIndex)

    /** Deletes what is kept, the temporary file a write may have left among it. */
    suspend fun delete()
}

/**
 * The index's rows in `ufc-events.json`, in the plugin's cache folder [dir] (L-B5): written whole to its .tmp, then
 * renamed; read tolerantly, a torn or unknown file read as none; both on [io], off the main thread. A copy that can't be
 * written is only not kept: the next read asks for the index again.
 */
internal class IndexFile(private val dir: File, private val io: CoroutineContext = Dispatchers.IO) : IndexCopy {
    override suspend fun read(): KeptIndex? = withContext(io) {
        try {
            json.decodeFromString(KeptIndex.serializer(), File(dir, NAME).readText()).takeIf { it.format == KeptIndex.FORMAT }
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    override suspend fun write(index: KeptIndex) = withContext(io) {
        val temporary = File(dir, "$NAME.tmp")
        try {
            dir.mkdirs()
            temporary.writeText(json.encodeToString(KeptIndex.serializer(), index))
            if (!temporary.renameTo(File(dir, NAME))) throw IOException("Not renamed")
        } catch (e: IOException) {
            log.warn("The list of UFC events wasn't kept: {}", e.javaClass.simpleName)
        }
    }

    override suspend fun delete() = withContext(io) {
        File(dir, NAME).delete()
        File(dir, "$NAME.tmp").delete()
        Unit
    }

    companion object {
        const val NAME = "ufc-events.json"
        private val json = Json { ignoreUnknownKeys = true }
        private val log = LoggerFactory.getLogger(IndexFile::class.java)
    }
}
