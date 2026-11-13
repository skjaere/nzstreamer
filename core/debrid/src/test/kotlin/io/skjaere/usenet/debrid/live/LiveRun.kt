package io.skjaere.usenet.debrid.live

import io.ktor.client.HttpClient
import io.ktor.client.call.save
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodedPath
import io.skjaere.usenet.debrid.DebridService
import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.Prepared
import io.skjaere.usenet.debrid.ServiceException
import io.skjaere.usenet.debrid.ServiceFile
import io.skjaere.usenet.debrid.ServiceListing
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.File
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import java.net.http.HttpClient as JdkHttpClient

/**
 * What a live-account class (L212, W77) runs with, and what it reports. Your key for [service] (`dev<service>Key`
 * from the root's local.properties) and the openly licensed film's torrent (`debridLiveHash`) come from the test's
 * environment, where `-PdebridLive=true` alone has the build put them: never system properties, which Gradle's `--info`
 * prints on the test JVM's command line. The report, `build/reports/debridLive/<service>.txt` (or the [report] name a
 * later check gives it), says what each answer was: each API call's path, status and JSON shape ([recording]), each CDN
 * read's status and the kinds of its headers ([cdn]), buckets, counts, hosts and times. Never a key, a link, a hash, an
 * ETag, the account's e-mail or its customer id: the clients' own log lines ([lines]) name none of them (L208), and every
 * note here is the class's own words over those kinds of facts.
 */
internal class LiveRun(
    private val service: String,
    env: (String) -> String? = System::getenv,
    private val reports: File = File(System.getProperty("debridLiveReports") ?: "build/reports/debridLive"),
    /** The report's file name, `.txt` added: the service's own name unless a later check names its own. */
    private val report: String = service.lowercase(),
    /** The report's first words. */
    private val title: String = "$service live check",
) {
    val key: String? = env("dev${service}Key")?.trim()?.takeIf { it.isNotEmpty() }
    val film: InfoHash? = env("debridLiveHash")?.let(InfoHash::parse)

    /** The clients' log lines, as they write them. */
    val lines = CopyOnWriteArrayList<String>()
    private val notes = CopyOnWriteArrayList<String>()
    private val apiCalls = AtomicInteger()
    private val cdnReads = AtomicInteger()
    private val started = System.nanoTime()

    /** One fact of the run, as the report says it. */
    fun note(fact: String) {
        notes += "+${msSince(started)} ms  $fact"
    }

    /** What [call] came to: its value said by [said], or the failure's bucket; the time it took. */
    suspend fun <T> timed(what: String, said: (T) -> String = { "answered" }, call: suspend () -> T): T? {
        val start = System.nanoTime()
        return try {
            call().also { note("$what: ${said(it)}, ${msSince(start)} ms") }
        } catch (e: ServiceException) {
            note("$what: ${e.error}, ${msSince(start)} ms")
            null
        }
    }

    /** The film's video, the largest file of its listing. */
    val largest: suspend (ServiceListing) -> ServiceFile? = { listing -> listing.files.maxByOrNull { it.size } }

    /**
     * [http] with every answer it gets noted as it arrives: the call's method and path (never its query, which holds the
     * key on TorBox's requestdl), its status and its JSON's [AnswerShape]. The answer is kept whole for the client to read
     * after, as it would have.
     */
    fun recording(http: HttpClient): HttpClient = http.apply {
        plugin(HttpSend).intercept { request ->
            val call = execute(request).save()
            apiCalls.incrementAndGet()
            note("${request.method.value} ${request.url.encodedPath}: HTTP ${call.response.status.value}, ${AnswerShape.of(call.response.bodyAsText())}")
            call
        }
    }

    /** What a CDN answered one read: its [status], and its [etag] kept in memory to compare with a later link's, never written. */
    class CdnAnswer(val status: Int, val etag: String?) {
        override fun toString() = "CdnAnswer($status)"
    }

    /**
     * One read of [link] from a megabyte in, as the player's open after a seek asks it (`Range: bytes=N-`, open-ended), with
     * [ifRange] as the ETag a reopen sends, stopped after 64 KB whatever comes: from a CDN that answers 200 to a range the
     * whole film would come, and it is never fetched. No other header (never a key to a CDN). Its status, protocol,
     * whether a Content-Range starts at the byte asked, the media type, Accept-Ranges, the ETag's kind (never its value),
     * the bytes read and the time.
     */
    fun cdn(what: String, link: String, ifRange: String? = null): CdnAnswer? {
        val start = System.nanoTime()
        cdnReads.incrementAndGet()
        return try {
            val request = HttpRequest.newBuilder(URI(link)).header("Range", "bytes=$READ_FROM-").timeout(Duration.ofSeconds(20)).GET()
            ifRange?.let { request.header("If-Range", it) }
            val answer = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream())
            // Closed before its end, the body's rest is dropped with the connection.
            val read = answer.body().use { it.readNBytes(READ_BYTES).size }
            val status = answer.statusCode()
            val headers = answer.headers()
            val etag = headers.firstValue("ETag").orElse(null)
            note(
                listOf(
                    "$what: HTTP $status${if (status == 200) " (a 200 to a ranged read: the whole file)" else ""}",
                    if (answer.version() == JdkHttpClient.Version.HTTP_2) "HTTP/2" else "HTTP/1.1",
                    rangeOf(headers.firstValue("Content-Range").orElse(null)),
                    typeOf(headers.firstValue("Content-Type").orElse(null)),
                    "ranges ${headers.firstValue("Accept-Ranges").orElse(null)?.takeIf { WORD.matches(it) } ?: "not said"}",
                    "ETag ${when { etag == null -> "none"; etag.startsWith("W/") -> "weak"; else -> "strong" }}",
                    "$read bytes read",
                    "${msSince(start)} ms",
                ).joinToString(", "),
            )
            CdnAnswer(status, etag)
        } catch (e: Exception) {
            note("$what: ${e.javaClass.simpleName}, ${msSince(start)} ms")
            null
        }
    }

    /**
     * Asks [service] for [file]'s link again [after] a while (a minute in the live run): whether one comes, and whether it
     * is another than [first]. The new one is read with the first link's strong ETag ([firstRead]) as If-Range, as Media3
     * sends it on a reopen within one load: a 200 there is the whole file a refreshed link would bring the player (its
     * 200 guard then fails the play). Then whether the new link's ETag is the first's.
     */
    suspend fun askAgain(service: DebridService, prepared: Prepared, file: ServiceFile, first: String, firstRead: CdnAnswer?, after: Duration = Duration.ofMinutes(1)) {
        delay(after.toMillis())
        val link = timed("link again after ${after.seconds} s", said = { if (it.url == first) "the same link" else "another link" }) { service.link(prepared, file) } ?: return
        val etag = firstRead?.etag?.takeUnless { it.startsWith("W/") }
        val read = cdn(if (etag != null) "the new link with the first link's ETag as If-Range" else "the new link", link.url, ifRange = etag) ?: return
        note("the new link's ETag: ${when { read.etag == null -> "none"; read.etag == firstRead?.etag -> "the same"; else -> "another" }}")
    }

    /** One read of a link that a check made by its own means, for the report's count. */
    fun countCdnRead() {
        cdnReads.incrementAndGet()
    }

    /** Whether [host] answers with TLS 1.2 forced (Open 2: an Android 9 device has no TLS 1.3). */
    fun tls12(host: String) {
        val result = try {
            val context = SSLContext.getInstance("TLSv1.2").apply { init(null, null, null) }
            (context.socketFactory.createSocket(host, 443) as SSLSocket).use { socket ->
                socket.soTimeout = 10_000
                socket.enabledProtocols = arrayOf("TLSv1.2")
                socket.startHandshake()
                "answers with TLS 1.2 forced (${socket.session.protocol})"
            }
        } catch (e: Exception) {
            "doesn't answer with TLS 1.2 forced (${e.javaClass.simpleName})"
        }
        note("$host $result")
    }

    /** A random 40-hex info hash that is no torrent: nothing can be fetched for it, whatever a service does with it. */
    fun noTorrent(): InfoHash = InfoHash.of(ByteArray(20).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) })

    /** The link's host alone, for the TLS check: a host is no link. */
    fun hostOf(link: String): String? = runCatching { URI(link).host }.getOrNull()

    /** The report, written whatever happened; the file it went to. */
    fun write(): File {
        val head = listOf("$title, ${Instant.now()}", "API calls: ${apiCalls.get()}, CDN reads: ${cdnReads.get()}", "")
        return reportFile("$report.txt").apply {
            writeText((head + notes + "" + "Client lines:" + lines).joinToString("\n", postfix = "\n"))
        }
    }

    /** The file [name] in the reports' folder, which is made if need be. */
    fun reportFile(name: String): File = File(reports.apply { mkdirs() }, name)

    /** Where a Content-Range starts, against the byte asked: its numbers aren't written. */
    private fun rangeOf(header: String?): String {
        val start = header?.let { RANGE.find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: return if (header == null) "no Content-Range" else "an odd Content-Range"
        return if (start == READ_FROM) "Content-Range from the byte asked" else "Content-Range from another byte"
    }

    private fun typeOf(header: String?): String {
        val type = header?.substringBefore(';')?.trim()?.lowercase() ?: return "no media type"
        return if (MEDIA_TYPE.matches(type)) type else "an odd media type"
    }

    private fun msSince(start: Long) = (System.nanoTime() - start) / 1_000_000

    private companion object {
        val http: JdkHttpClient = JdkHttpClient.newBuilder().followRedirects(JdkHttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(8)).build()

        /** A megabyte in: where a seek would have the player open. */
        const val READ_FROM = 1_048_576L
        const val READ_BYTES = 64 * 1024

        val RANGE = Regex("""^bytes (\d+)-\d+/(\d+|\*)$""")
        val MEDIA_TYPE = Regex("[a-z0-9.+-]{1,40}/[a-z0-9.+-]{1,60}")
        val WORD = Regex("[A-Za-z]{1,10}")
    }
}

/**
 * The shape of an API's JSON answer, for the live reports (L212: field names, codes and times): each field's name and
 * kind, an array's elements merged with their count, and never a value but the envelope's own `success`, `status`,
 * `error` and `code` where they are a flag or a code (letters, digits and underscores; a sentence is the service's words,
 * written as its kind). A key that is no field's name (a hash keying an object, an address) is written `<key>`, with how
 * many there were.
 */
internal object AnswerShape {
    fun of(text: String): String {
        val element = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return "not JSON, ${text.length} characters"
        return shape(element, top = true)
    }

    private fun shape(element: JsonElement, top: Boolean = false): String = when (element) {
        is JsonNull -> "null"
        is JsonPrimitive -> kindOf(element)
        is JsonArray -> "[${merged(element)}] ×${element.size}"
        is JsonObject -> fieldsOf(listOf(element), top)
    }

    /** One shape for every element of [array]: their objects' fields together, else their kinds, a null last. */
    private fun merged(array: JsonArray): String =
        if (array.isNotEmpty() && array.all { it is JsonObject }) fieldsOf(array.map { it as JsonObject }) else alternatives(array.map { shape(it) })

    /** The fields of [objects] together, in the order first met, each with the kinds it had. */
    private fun fieldsOf(objects: List<JsonObject>, top: Boolean = false): String {
        val fields = LinkedHashMap<String, MutableList<String>>()
        var masked = 0
        objects.forEach { obj ->
            obj.forEach { (name, value) ->
                val named = NAME.matches(name) && !HEX.matches(name)
                if (!named) masked++
                fields.getOrPut(if (named) name else MASKED) { mutableListOf() } += if (top && named && name in SAID) said(value) else shape(value)
            }
        }
        return fields.entries.joinToString(", ", "{", "}") { (name, shapes) ->
            "${if (name == MASKED) "$MASKED ×$masked" else name}: ${alternatives(shapes)}"
        }
    }

    private fun alternatives(shapes: List<String>): String = shapes.distinct().sortedBy { it == "null" }.joinToString(" | ")

    /** An envelope's own flag or code as it is; anything else as its kind. */
    private fun said(value: JsonElement): String = when {
        value is JsonPrimitive && value !is JsonNull && !value.isString && value.booleanOrNull != null -> value.content
        value is JsonPrimitive && value.isString && CODE.matches(value.content) -> "\"${value.content}\""
        else -> shape(value)
    }

    private fun kindOf(primitive: JsonPrimitive): String = when {
        primitive.isString -> "string"
        primitive.booleanOrNull != null -> "boolean"
        else -> "number"
    }

    private const val MASKED = "<key>"
    private val SAID = setOf("success", "status", "error", "code")
    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]{0,31}")
    private val HEX = Regex("[0-9a-fA-F]{16,}")
    private val CODE = Regex("[A-Za-z0-9_]{1,40}")
}
