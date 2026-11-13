package io.skjaere.usenet.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.google.common.base.Predicate
import io.skjaere.usenet.core.ContainerTypes
import io.skjaere.usenet.core.StreamTraffic
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException

/**
 * Media3's source for a debrid play's `debrid://` URIs (L171, L172, W81): the file's link comes from the app's [LinkResolver]
 * and is read through Media3's own HTTP source ([DefaultHttpDataSource]: connect 8 s, read 10 s, `Accept-Encoding:
 * identity`, no redirect from HTTPS to HTTP, no Authorization header to the CDN).
 *
 * A link is held for every source the [Factory] makes, the probe's and the player's, one asking for it at a time per file,
 * and replaced before an open once over 50 minutes old. An answer a dead link gives (401, 403, 404, 410, a page in place
 * of the file) gets one fresh link and the open again at the same byte; a second is final, as is a refresh the service
 * refuses ([LinkUnavailable], which the load policy doesn't retry). The decisions are [ServiceLinkRules]'.
 *
 * Media3's HTTP source answers a 200 to a ranged request by reading and dropping every byte up to the place, inside its
 * open, which no wrapper could stop after the fact: so the inner source is always opened at its position 0 with this
 * source's own `Range: bytes=N-`, Media3 then adding no Range and skipping nothing, and before anything is read the
 * answer must be the part asked for (a 206 from N, or a 200 from 0). This source's own place and length stay N-based for
 * Media3. Its URI is the placeholder, never the link; its container type is the file's name's ([ContainerTypes]), as a
 * CDN may name none. Each open's bytes reach a [StreamTraffic] meter, as a Usenet stream's do, for the stream-cost line
 * and the slow-stream banner. Media3's If-Range, the ETag of the answer before, goes only to the link that gave it: another
 * link may be another CDN node, whose ETag differs, and would answer 200 with the whole file (DFH-2).
 *
 * Another thread may [abort] it mid-read (a [ReadAheadDataSource] closing while its reader waits on the CDN, L249): the
 * connection is let go at once, an open under way lets its own go, and the source asks for no link and opens no more.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class ServiceDataSource internal constructor(
    private val links: HeldLinks,
    private val http: HttpDataSource,
    traffic: StreamTraffic?,
) : BaseDataSource(/* isNetwork = */ true), AbortableDataSource {
    private var uri: Uri? = null

    /** The file's name, from its link: its container type. */
    private var name: String? = null

    /**
     * Whether [http] is open, and whether this source is (it may be, at the file's end, with nothing to read). [abort] reads
     * [httpOpen] from another thread, and it changes under [closing].
     */
    @Volatile
    private var httpOpen = false
    private var opened = false

    /** Guards [http]'s close, which [abort] makes from another thread than the reads. */
    private val closing = Any()

    /** Set by [abort]: an open after it, or under way as it came, lets its connection go and fails. */
    @Volatile
    private var aborted = false

    /** Whether [http] is opening, its connection made or being made; changes under [closing]. */
    private var opening = false

    /** The link of the last open that read: Media3's next If-Range is the ETag its answer gave, kept across a close. */
    private var headersFrom: HttpLink? = null

    init {
        traffic?.let { addTransferListener(TrafficMeter(it)) }
    }

    override fun open(dataSpec: DataSpec): Long {
        if (aborted) throw InterruptedIOException("The debrid read was aborted")
        val target = PlayUris.parse(dataSpec.uri.toString()) as? PlayUris.Target.Service
            ?: throw DataSourceException("Not a debrid URI", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        var link = links.current(target)
        var refreshed = false
        while (true) {
            when (val answer = openAt(link, dataSpec, refreshed)) {
                is Opening.Read -> {
                    name = link.name
                    headersFrom = link
                    opened = true
                    transferStarted(dataSpec)
                    return answer.length
                }
                is Opening.Refresh -> {
                    // Aborted meanwhile: no call to the service for a link nothing will read.
                    if (aborted) throw InterruptedIOException("The debrid read was aborted")
                    link = links.refresh(target, link, answer.why)
                    refreshed = true
                }
            }
        }
    }

    /** What one open with [link] came to: the file to read, or a fresh link to open again with. */
    private sealed interface Opening {
        class Read(val length: Long) : Opening
        class Refresh(val why: String) : Opening
    }

    private fun openAt(link: HttpLink, spec: DataSpec, refreshed: Boolean): Opening {
        // A validator from another link's answer (a dead one refreshed, an old one replaced) isn't sent: the file under one
        // listing and index doesn't change, and the Content-Range's start is checked here anyway.
        val headers = if (link == headersFrom) spec.httpRequestHeaders else spec.httpRequestHeaders.filterKeys { !it.equals(IF_RANGE, ignoreCase = true) }
        val inner = DataSpec.Builder()
            .setUri(Uri.parse(link.url))
            .setHttpRequestHeaders(headers + (RANGE to ServiceLinkRules.range(spec.position, spec.length)))
            .setFlags(spec.flags and DataSpec.FLAG_ALLOW_GZIP.inv())
            .build()
        var length = 0L
        val answer = try {
            length = openHttp(inner)
            ServiceLinkRules.answer(spec.position, http.responseCode, null, header(http.responseHeaders, CONTENT_RANGE), refreshed)
        } catch (e: HttpDataSource.InvalidContentTypeException) {
            closeHttp()
            ServiceLinkRules.answer(spec.position, OK, e.contentType, null, refreshed)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            closeHttp()
            ServiceLinkRules.answer(spec.position, e.responseCode, header(e.headerFields, CONTENT_TYPE), header(e.headerFields, CONTENT_RANGE), refreshed)
                .takeUnless { it == ServiceLinkRules.Answer.Retry } ?: throw e
        }
        return when (answer) {
            ServiceLinkRules.Answer.Opened -> Opening.Read(length)
            ServiceLinkRules.Answer.AtEnd -> Opening.Read(if (spec.length != C.LENGTH_UNSET.toLong()) spec.length else 0)
            is ServiceLinkRules.Answer.Refresh -> Opening.Refresh(answer.why)
            is ServiceLinkRules.Answer.Fail -> {
                // Closed before a byte of the body is read: what a 200 would have had read up to the place stays unread.
                closeHttp()
                throw LinkUnavailable(answer.failure)
            }
            ServiceLinkRules.Answer.Retry -> throw IOException("Unexpected answer to a debrid read")
        }
    }

    /**
     * [http] opened at [inner], unless [abort] came first, which then opens nothing, or comes while it opens: then the
     * connection goes, as soon as it is made, and the open fails as an interrupted read.
     */
    private fun openHttp(inner: DataSpec): Long {
        synchronized(closing) {
            if (aborted) throw InterruptedIOException("The debrid read was aborted")
            opening = true
        }
        val length = try {
            http.open(inner)
        } catch (e: Throwable) {
            synchronized(closing) { opening = false }
            // What the abort's close of the connection made it throw, or a failure nothing reads now.
            if (aborted && e is IOException) throw InterruptedIOException("The debrid read was aborted while it opened").apply { initCause(e) }
            throw e
        }
        synchronized(closing) {
            opening = false
            if (aborted) {
                closeQuietly()
                throw InterruptedIOException("The debrid read was aborted while it opened")
            }
            httpOpen = true
        }
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!httpOpen) return C.RESULT_END_OF_INPUT
        val n = http.read(buffer, offset, length)
        if (n > 0) bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    /** The CDN's headers (its ETag, for Media3's If-Range), the container type the file's name's where it names one. */
    override fun getResponseHeaders(): Map<String, List<String>> {
        val headers = if (httpOpen) http.responseHeaders else emptyMap()
        val type = name?.let(ContainerTypes::mimeOf) ?: return headers
        val kept = HashMap<String?, List<String>>()
        for (entry in headers.entries) {
            // Media3's HTTP source lists the status line under a null key.
            val key: String? = entry.key
            if (key == null || !key.equals(CONTENT_TYPE, ignoreCase = true)) kept[key] = entry.value
        }
        kept[CONTENT_TYPE] = listOf(type)
        @Suppress("UNCHECKED_CAST")
        return kept as Map<String, List<String>>
    }

    override fun close() {
        uri = null
        name = null
        try {
            closeHttp()
        } finally {
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    /**
     * Lets the connection go from another thread than the reads (L249): a read waiting on the CDN fails at once rather than
     * after the 10 s read time-out, and the source opens no more. The connection is closed once, under [closing] with
     * [close]'s, which then has nothing left to let go. An open under way (the read-ahead's reader opening the file again
     * after a cut, its answer slow) has its connection closed too, so it never lasts beside the next open's; one still
     * being made goes as its open returns. One not begun, and a fresh link not yet asked for, never are.
     */
    override fun abort() {
        synchronized(closing) {
            aborted = true
            when {
                httpOpen -> {
                    httpOpen = false
                    closeQuietly()
                }
                opening -> closeQuietly()
            }
        }
    }

    private fun closeHttp() {
        synchronized(closing) {
            if (!httpOpen) {
                // A failed open leaves Media3's source to let its connection go; after an abort there is nothing to.
                runCatching { http.close() }
                return
            }
            httpOpen = false
            http.close()
        }
    }

    /**
     * [http]'s close, where a read on another thread may meet it: Android's HTTP stack can throw from a close that meets a
     * read under way (okio's "Unbalanced enter/exit"), and Media3 disconnects in its finally all the same.
     */
    private fun closeQuietly() {
        try {
            http.close()
        } catch (e: IOException) {
            // Let go all the same.
        } catch (e: RuntimeException) {
            if (e is CancellationException) throw e
        }
    }

    /**
     * Makes a [ServiceDataSource] for each player, probe and cast relay's read, every one sharing the links [resolver] gives,
     * aged by [now], its bytes metered by [traffic] (a player's through its [ReadAheadDataSource], [createUnmetered]).
     */
    class Factory(
        resolver: LinkResolver,
        internal val traffic: StreamTraffic? = null,
        now: () -> Long = System::currentTimeMillis,
    ) : DataSource.Factory {
        private val links = HeldLinks(resolver, now)

        /** One sharing the links, its bytes metered: the probe's, and each of the cast relay's reads, which a close may [abort] (L249). */
        override fun createDataSource(): ServiceDataSource = ServiceDataSource(links, http.createDataSource(), traffic)

        /** One sharing the links, its bytes metered nowhere: a [ReadAheadDataSource]'s, which meters what the player takes of it. */
        fun createUnmetered(): ServiceDataSource = ServiceDataSource(links, http.createDataSource(), null)

        private val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(false)
            // A page in place of the file fails the open as an answer of its type, which the rules take for a dead link.
            .setContentTypePredicate(Predicate { !ServiceLinkRules.isPage(it) })
    }

    /** A [StreamTraffic] meter for each open, counting the bytes it hands the player. */
    internal class TrafficMeter(private val traffic: StreamTraffic) : TransferListener {
        private var meter: StreamTraffic.Meter? = null

        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            meter?.close()
            meter = traffic.open()
        }

        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            meter?.add(bytesTransferred)
        }

        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            meter?.close()
            meter = null
        }
    }

    internal companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 10_000
        private const val OK = 200
        private const val RANGE = "Range"
        private const val IF_RANGE = "If-Range"
        private const val CONTENT_RANGE = "Content-Range"
        private const val CONTENT_TYPE = "Content-Type"

        /** [name]'s first value in [headers], whatever its case. */
        fun header(headers: Map<String, List<String>>, name: String): String? =
            headers.entries.firstOrNull { entry ->
                val key: String? = entry.key
                key?.equals(name, ignoreCase = true) == true
            }?.value?.firstOrNull()
    }
}

/**
 * The links of the files being read (L172), shared by the sources one [ServiceDataSource.Factory] makes, so the probe and
 * the player reading one file together ask the [resolver] once: one asks at a time per file, the other then takes its
 * link. A link over 50 minutes old ([ServiceLinkRules.isOld], by [now]) is replaced before an open. A refresh says why in
 * the [log] (L208: "debrid: link refreshed (403, after 52 min)"), and how old the link was: never the link or its file.
 */
internal class HeldLinks(private val resolver: LinkResolver, private val now: () -> Long, private val log: (String) -> Unit = { logger.info(it) }) {
    private val held = HashMap<PlayUris.Target.Service, HttpLink>()
    private val locks = HashMap<PlayUris.Target.Service, Any>()

    private fun lockOf(target: PlayUris.Target.Service): Any = synchronized(locks) { locks.getOrPut(target) { Any() } }

    private fun heldFor(target: PlayUris.Target.Service): HttpLink? = synchronized(held) { held[target] }

    /** The link to open [target] with: the one held, unless it is too old; else the resolver's. */
    fun current(target: PlayUris.Target.Service): HttpLink = synchronized(lockOf(target)) {
        val link = heldFor(target)
        if (link != null && !ServiceLinkRules.isOld(link, now())) return link
        ask(target, link, "old")
    }

    /** A fresh link in place of [dead], which answered as a dead link for [why]; or one another open got meanwhile. */
    fun refresh(target: PlayUris.Target.Service, dead: HttpLink, why: String): HttpLink = synchronized(lockOf(target)) {
        val link = heldFor(target)
        if (link != null && link != dead && !ServiceLinkRules.isOld(link, now())) return link
        ask(target, dead, why)
    }

    private fun ask(target: PlayUris.Target.Service, stale: HttpLink?, why: String): HttpLink {
        val fresh = try {
            resolver.link(target, stale)
        } catch (e: InterruptedException) {
            // Media3 interrupts a load it cancels: as its own sources do, an interrupted I/O.
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Interrupted while asking for a link")
        }
        synchronized(held) { held[target] = fresh }
        if (stale != null) log("debrid: link refreshed ($why, after ${(now() - stale.madeAtMs) / 60_000} min)")
        return fresh
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(ServiceDataSource::class.java)
    }
}
