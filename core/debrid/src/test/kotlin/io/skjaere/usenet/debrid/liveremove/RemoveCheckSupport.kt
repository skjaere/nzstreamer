package io.skjaere.usenet.debrid.liveremove

import io.ktor.client.HttpClient
import io.ktor.client.call.save
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodedPath
import io.skjaere.usenet.debrid.ServiceHttp
import io.skjaere.usenet.debrid.live.LiveRun
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The real calls one test JVM's D2 live checks may make (L235: 25 in all, the APIs' and the CDN's together), each taken
 * as it is about to go: one past [max] fails with [OverBudget] before it is sent.
 */
internal class CallBudget(val max: Int = MAX) {
    private val used = AtomicInteger()

    /** The calls made so far. */
    val spent: Int get() = used.get()

    /** One more call, or [OverBudget] before it goes once the budget is spent. */
    fun take() {
        if (used.incrementAndGet() > max) {
            used.decrementAndGet()
            throw OverBudget()
        }
    }

    /** [http], every request of which takes one call from the budget before it goes. */
    fun guarding(http: HttpClient): HttpClient = http.apply {
        plugin(HttpSend).intercept { request ->
            take()
            execute(request)
        }
    }

    class OverBudget : IllegalStateException("over the live check's budget of calls: not sent")

    companion object {
        const val MAX = 25

        /** The budget the live classes of one run share. */
        val shared = CallBudget()
    }
}

/** An answer as a check says it: its HTTP [status] and the service's own [code], if it gave one. */
internal class Said(val status: Int, val code: String?) {
    override fun toString() = "HTTP $status, code ${code ?: "none"}"
}

/**
 * The last answer [http] got to each call (its method and path, never its query), as [Said]: for a call a client makes,
 * whose answer the client keeps to itself. The answer is kept whole for the client to read after.
 */
internal class LastAnswers {
    private val last = ConcurrentHashMap<String, Said>()

    fun install(http: HttpClient): HttpClient = http.apply {
        plugin(HttpSend).intercept { request ->
            val call = execute(request).save()
            last["${request.method.value} ${request.url.encodedPath}"] = Said(call.response.status.value, codeOf(envelopeOf(call.response.bodyAsText())))
            call
        }
    }

    /** The last answer to the call whose path ends with [path], made with [method]. */
    fun of(method: String, path: String): Said? = last.entries.firstOrNull { (call, _) -> matches(call, method, path) }?.value

    /** Forgets the answers to that call, so that [of] says only what a later one answered. */
    fun forget(method: String, path: String) {
        last.keys.removeIf { matches(it, method, path) }
    }

    private fun matches(call: String, method: String, path: String) = call.startsWith("$method ") && call.endsWith(path)
}

/**
 * Reads of a CDN link for the removal check, each counted by [counted] before it goes: from byte 0 (`Range: bytes=0-`,
 * open-ended, as a player's first open asks), a little of the body read and the rest left open ([Open]). No other header
 * (never a key to a CDN), no redirect followed. Through HttpURLConnection for its read timeout: a body that stops
 * delivering is seen within [readTimeout], never waited on. Only an exception's kind is ever said: its words can hold
 * the CDN's host.
 */
internal class LinkReader(private val counted: () -> Unit, private val readTimeout: Duration = Duration.ofSeconds(5)) {
    /** [link] opened from byte 0, [little] bytes of its body read; the failure's kind when it couldn't be. */
    fun open(link: String, little: Int = LITTLE): Result<Open> {
        counted()
        var connection: HttpURLConnection? = null
        return runCatching {
            val opened = (URI(link).toURL().openConnection() as HttpURLConnection).also { connection = it }
            opened.instanceFollowRedirects = false
            opened.useCaches = false
            opened.connectTimeout = CONNECT_TIMEOUT_MS
            opened.readTimeout = readTimeout.toMillis().toInt()
            opened.setRequestProperty("Range", "bytes=0-")
            val status = opened.responseCode
            val fromStart = opened.getHeaderField("Content-Range")?.trim()?.startsWith("bytes 0-")
            val body = if (status in 200..299) opened.inputStream else null
            Open(opened, status, fromStart, body?.readNBytes(little)?.size ?: 0, body)
        }.onFailure { connection?.disconnect() }
    }

    /**
     * A link's answer: its [status], whether its Content-Range starts at byte 0 ([fromStart], null when it gave none), the
     * bytes [read] at once; its body, when it had one, left open for [readOn].
     */
    class Open internal constructor(
        private val connection: HttpURLConnection,
        val status: Int,
        val fromStart: Boolean?,
        val read: Int,
        private val body: InputStream?,
    ) : AutoCloseable {
        /**
         * Reads on for [window], at most [perTick] bytes every [tick], so the reading is spread over the window: how much
         * came, and how the body ended if it did before the window's end.
         */
        fun readOn(window: Duration, tick: Duration = TICK, perTick: Int = PER_TICK): ReadOn {
            val input = body ?: return ReadOn(0, "no body", 0)
            val start = System.nanoTime()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            try {
                while (System.nanoTime() - start < window.toNanos()) {
                    var thisTick = 0
                    while (thisTick < perTick) {
                        val n = input.read(buffer, 0, minOf(buffer.size, perTick - thisTick))
                        if (n < 0) return ReadOn(total, "the body's end", msSince(start))
                        thisTick += n
                        total += n
                    }
                    Thread.sleep(tick.toMillis())
                }
            } catch (e: IOException) {
                return ReadOn(total, e.javaClass.simpleName, msSince(start))
            }
            return ReadOn(total, null, msSince(start))
        }

        override fun close() {
            runCatching { body?.close() }
            connection.disconnect()
        }

        override fun toString() = "Open($status)"
    }

    /** What reading on brought: the [bytes], and how it [ended] (null: still delivering at the window's end), [afterMs] in. */
    class ReadOn(val bytes: Long, val ended: String?, val afterMs: Long)

    companion object {
        const val LITTLE = 64 * 1024
        const val CONNECT_TIMEOUT_MS = 8_000

        /** A tick's 2 MiB every 250 ms: 8 MiB a second at most, a film's stream several times over. */
        val TICK: Duration = Duration.ofMillis(250)
        const val PER_TICK = 2 * 1024 * 1024
    }
}

/**
 * The short summary of the D2 live checks, `d2-summary.txt` beside their reports: each service's answers as its check
 * says them, and the real calls the run made. Rewritten whole as each class ends, so it holds this run's classes alone.
 */
internal class LiveSummary {
    private val sections = ConcurrentHashMap<String, Pair<String, List<String>>>()

    /** [service]'s answers, said in [lines]; its report is [report]. */
    fun put(service: String, report: String, lines: List<String>) {
        sections[service] = report to lines.toList()
    }

    fun text(budget: CallBudget): String {
        val head = listOf(
            "D2 live check before #318's spec (L235), ${Instant.now()}",
            "Real calls in this run: ${budget.spent} of ${budget.max}, the APIs' and the CDN's together",
            "Field names, HTTP codes, the services' codes, booleans, counts and time deltas only: never a key, a link, a host, an id, a hash or a name.",
        )
        val body = ORDER.mapNotNull { service -> sections[service]?.let { (report, lines) -> listOf("", "$service ($report)") + lines } }.flatten()
        return (head + body).joinToString("\n", postfix = "\n")
    }

    /** The summary, written beside [run]'s report; the file it went to. */
    fun write(run: LiveRun, budget: CallBudget): File = run.reportFile(FILE).apply { writeText(text(budget)) }

    companion object {
        const val FILE = "d2-summary.txt"
        private val ORDER = listOf("TorBox", "Premiumize")

        /** The summary the live classes of one run share. */
        val shared = LiveSummary()
    }
}

/** As the clients sign a call: the key as a bearer token, the app's user agent. */
internal fun HttpRequestBuilder.signed(key: String) {
    header(HttpHeaders.Authorization, "Bearer $key")
    header(HttpHeaders.UserAgent, ServiceHttp.USER_AGENT)
}

/** Lenient reading, as the clients read (L185). */
internal val READ = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
    explicitNulls = false
}

internal fun envelopeOf(text: String): JsonObject? = runCatching { READ.parseToJsonElement(text) as? JsonObject }.getOrNull()

/**
 * The service's own code in an [envelope] (TorBox's `error`, Premiumize's `code`), when it is a code (letters, digits and
 * underscores); a sentence is the service's words, never written.
 */
internal fun codeOf(envelope: JsonObject?): String? {
    val code = listOf("error", "code").firstNotNullOfOrNull { (envelope?.get(it) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) } ?: return null
    return if (CODE.matches(code)) code else "words, not a code"
}

/** A service's time as it writes it: with an offset, or without one as UTC. */
internal fun instantOf(text: String?): Instant? {
    val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC) }.getOrNull()
}

/** An HTTP Date header's time (RFC 9110's IMF-fixdate). */
internal fun dateOf(header: String?): Instant? =
    header?.let { runCatching { ZonedDateTime.parse(it.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull() }

/** [later] less [earlier] in seconds, signed, to the millisecond; "not given" when either is missing. */
internal fun deltaOf(later: Instant?, earlier: Instant?): String =
    if (later == null || earlier == null) "not given" else String.format(Locale.ROOT, "%+.3f s", Duration.between(earlier, later).toMillis() / 1000.0)

internal fun yes(value: Boolean?): String = when (value) {
    true -> "yes"
    false -> "no"
    null -> "not said"
}

internal fun mib(bytes: Long): String = String.format(Locale.ROOT, "%.1f MiB", bytes / 1_048_576.0)

internal fun msSince(start: Long) = (System.nanoTime() - start) / 1_000_000

private val CODE = Regex("[A-Za-z0-9_]{1,40}")
