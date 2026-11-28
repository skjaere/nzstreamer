package app.nzstreamer.plugin.sportarr.bouts

import app.nzstreamer.plugin.sportarr.model.SportsEvent
import io.skjaere.usenet.plugin.HttpAnswer
import io.skjaere.usenet.plugin.PluginHttp
import io.skjaere.usenet.plugin.PluginHttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale
import kotlin.coroutines.CoroutineContext

/** What a read of an event's fight card gave (L-B5, L-B6). */
internal sealed interface CardRead {
    /** The [card], out and split into parts. */
    data class Found(val card: BoutCard) : CardRead

    /** No page or card yet before the event's start, or a card not split into parts (W4). */
    data object NotYet : CardRead

    /** No page or card found after the event's start. */
    data object NoCard : CardRead

    /** No answer to say either ([why]: a status, a kind of failure, "held" or "an error"): nothing is kept of it. */
    data class Failed(val why: String) : CardRead
}

/**
 * Reads UFC events' fight cards from Wikipedia at [base] (L-B6): one read at a time ([lock], which the kept cards' writes
 * take too), its requests one after the other through [http], no retry inside a read. [PageFinder] finds the article within
 * the budget, its index kept in [index]; [reader] reads its card on [work], off the main thread.
 *
 * Reads run in the host's scope, where an escaping exception turns the plugin off. So a read catches everything but a
 * cancellation: one thrown while the accepted article is read means it holds no card (NotYet before the event's start,
 * NoCard after); one thrown anywhere else fails the read. Either way the line logged names the exception's class alone.
 * Each read logs one line: its outcome, the bouts and parts it found, its requests and its time; never a title, a name, an
 * address or a path.
 */
internal class WikipediaReads(
    http: PluginHttp,
    base: String,
    private val clock: () -> Instant,
    index: IndexCopy,
    private val work: CoroutineContext = Dispatchers.Default,
    private val reader: (page: String, article: String, title: String, parts: List<String>) -> BoutTable.Read = BoutTable::read,
) {
    /** One read at a time in the plugin; a caller that holds it reads with [readHeld]. */
    val lock = Mutex()

    private val requests = WikipediaRequests(http, clock)
    private val finder = PageFinder(base, requests, index, clock, work)

    /** [event]'s card, once the read before it ended. */
    suspend fun read(event: SportsEvent): CardRead = lock.withLock { readHeld(event) }

    /** [event]'s card, for a caller that holds [lock]. */
    suspend fun readHeld(event: SportsEvent): CardRead {
        val started = System.nanoTime()
        val sent = requests.sent
        val read = try {
            attempt(event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.warn("Fight card read failed: {}", e.javaClass.simpleName)
            CardRead.Failed("an error")
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        val asked = requests.sent - sent
        when (read) {
            is CardRead.Found -> log.info(
                "Fight card read: found, {} bouts in {} parts, {} requests in {} ms",
                read.card.parts.sumOf { it.bouts.size }, read.card.parts.size, asked, ms,
            )
            CardRead.NotYet -> log.info("Fight card read: not out yet, {} requests in {} ms", asked, ms)
            CardRead.NoCard -> log.info("Fight card read: no card, {} requests in {} ms", asked, ms)
            is CardRead.Failed -> log.info("Fight card not read: {} after {} requests in {} ms", read.why, asked, ms)
        }
        return read
    }

    /** Forgets the index's rows kept (the switch turned off); the holds stay. */
    suspend fun forget() = finder.forget()

    private suspend fun attempt(event: SportsEvent): CardRead {
        if (requests.held()) return CardRead.Failed(HELD)
        return when (val found = finder.find(event)) {
            is PageFinder.Found.Failed -> CardRead.Failed(found.why)
            PageFinder.Found.NoPage -> noCard(event)
            is PageFinder.Found.Article -> card(found, event)
        }
    }

    private suspend fun card(article: PageFinder.Found.Article, event: SportsEvent): CardRead {
        val read = try {
            withContext(work) { reader(article.page, article.title, event.title, event.parts.map { it.id }) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.warn("A fight card's page was unreadable: {}", e.javaClass.simpleName)
            return noCard(event)
        }
        return when (read) {
            is BoutTable.Read.Out -> CardRead.Found(read.card)
            BoutTable.Read.NotOut -> CardRead.NotYet
            BoutTable.Read.Missing -> noCard(event)
        }
    }

    /** No page or card: not out yet before the event's start, none after it. */
    private fun noCard(event: SportsEvent): CardRead = if (clock().isBefore(event.start)) CardRead.NotYet else CardRead.NoCard

    private companion object {
        const val HELD = "held"
        val log = LoggerFactory.getLogger(WikipediaReads::class.java)
    }
}

/** What a request to Wikipedia got, as L-B6 reads its answer. */
internal sealed interface Fetch {
    /** A 200's [body]. */
    class Page(val body: ByteArray) : Fetch

    /** A 404: no article under that title. */
    data object Miss : Fetch

    /** Anything else ([why]): a failed read. */
    data class Failed(val why: String) : Fetch
}

/**
 * Wikipedia's requests through the host's [http] (L-B6): `/wiki/` addresses with no header of the plugin's (the host sends
 * its User-Agent, and OkHttp asks for gzip when the caller sets no Accept-Encoding), each answer read for what it means,
 * and the holds an answer asks for: no request before a 429's Retry-After (60 s without one), for 30 s after a 5xx (its
 * Retry-After when longer), or for an hour after a 403. A Retry-After is seconds or an HTTP date in any of its three
 * forms, at most an hour. Holds live in memory, timed by [clock]; while one lasts, a request fails at once with none sent.
 */
internal class WikipediaRequests(private val http: PluginHttp, private val clock: () -> Instant) {
    /** Requests sent so far, which each read's log line counts. */
    var sent = 0
        private set

    private var heldUntil: Instant? = null

    /** Whether a hold lasts now. */
    fun held(): Boolean = heldUntil?.let { clock().isBefore(it) } == true

    /** Gets [url], reading at most [maxBytes] of its body. */
    suspend fun get(url: String, maxBytes: Int): Fetch {
        if (held()) return Fetch.Failed("held")
        sent++
        val answer = try {
            http.get(url, maxBytes = maxBytes)
        } catch (e: PluginHttpException) {
            // Every kind fails the read, one a later API minor adds among them.
            return Fetch.Failed(e.kind.name)
        }
        val status = answer.status
        return when {
            status == 200 -> Fetch.Page(answer.body)
            status == 404 -> Fetch.Miss
            status == 429 -> hold(retryAfter(answer) ?: TOO_MANY, status)
            status == 403 -> hold(REFUSED, status)
            status in 500..599 -> hold(maxOf(SERVER_ERROR, retryAfter(answer) ?: Duration.ZERO), status)
            else -> Fetch.Failed("$status")
        }
    }

    private fun hold(length: Duration, status: Int): Fetch {
        val until = clock().plus(length)
        heldUntil = heldUntil?.let { maxOf(it, until) } ?: until
        return Fetch.Failed("$status")
    }

    /** [answer]'s Retry-After: seconds, or an HTTP date from now; at most an hour, none where it reads as neither. */
    private fun retryAfter(answer: HttpAnswer): Duration? {
        val written = answer.headers["retry-after"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val now = clock()
        val length = if (written.all { it in '0'..'9' }) {
            Duration.ofSeconds(written.toLongOrNull()?.coerceAtMost(LONGEST.seconds) ?: LONGEST.seconds)
        } else {
            Duration.between(now, httpDate(written, now) ?: return null)
        }
        return length.coerceIn(Duration.ZERO, LONGEST)
    }

    /**
     * [written] as an HTTP date, in each of the three forms RFC 9110 has a recipient read: IMF-fixdate ("Sun, 06 Nov
     * 1994 08:49:37 GMT"), and the obsolete rfc850-date ("Sunday, 06-Nov-94 08:49:37 GMT", whose two-digit year, more
     * than fifty years after [now], is the last such year gone by) and asctime-date ("Sun Nov  6 08:49:37 1994", in UTC).
     * Null where it is none of them.
     */
    private fun httpDate(written: String, now: Instant): Instant? {
        val text = Html.collapse(written)
        val rfc850 = DateTimeFormatterBuilder()
            .appendPattern("EEEE, dd-MMM-")
            .appendValueReduced(ChronoField.YEAR, 2, 2, now.atOffset(ZoneOffset.UTC).toLocalDate().minusYears(49))
            .appendPattern(" HH:mm:ss 'GMT'")
            .toFormatter(Locale.ENGLISH)
            .withZone(ZoneOffset.UTC)
        return listOf(DateTimeFormatter.RFC_1123_DATE_TIME, rfc850, ASCTIME).firstNotNullOfOrNull { form ->
            try {
                ZonedDateTime.parse(text, form).toInstant()
            } catch (e: DateTimeException) {
                null
            }
        }
    }

    private companion object {
        val TOO_MANY: Duration = Duration.ofSeconds(60)
        val SERVER_ERROR: Duration = Duration.ofSeconds(30)
        val REFUSED: Duration = Duration.ofHours(1)
        val LONGEST: Duration = Duration.ofHours(1)

        /** asctime-date, its spaces collapsed first ("Sun Nov 6 08:49:37 1994"). */
        val ASCTIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC)
    }
}
