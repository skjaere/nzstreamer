package io.skjaere.usenet.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import io.skjaere.usenet.core.StreamTraffic
import kotlin.concurrent.thread

/**
 * A [DataSource] whose connection another thread can let go mid-read: a read waiting on it then fails at once, and the
 * source opens no more. [ServiceDataSource] is one.
 */
interface AbortableDataSource : DataSource {
    fun abort()
}

/**
 * The player's source for a debrid play's file (L239, L249, W88): the inner source's bytes read ahead of the player by a
 * [ReadAhead], as far as a Usenet play's streams read ahead, so a debrid play has a Usenet play's cushion in front of
 * Media3's own buffer, which stays as it is. Only the player reads through it: the track probe reads a few KB and closes,
 * and the cast relay paces itself to its receiver, so both keep the plain source.
 *
 * Each open opens the inner source on the caller's thread, so an open's failure stays the open's (a link refused fails
 * the open, as before), then a reader thread of its own fills the read-ahead from it while [read] takes from it. The
 * player's takes are metered here, on its thread, as a Usenet stream's are ([StreamTraffic]); the inner source has no
 * meter, and Media3's transfer listeners are this source's own. The URI and headers are the inner source's at the open.
 *
 * [close] stops the reader. A reader waiting for room is done with the inner source, which is closed here and opened
 * again by the next open. One in a read is given a moment ([LET_GO_MS]) to come back, as one from a body still flowing
 * does within milliseconds, and is then done with it too: so only one thread at a time touches the inner source's
 * streams (Android's HTTP stack reads the rest of a body on the thread that closes it, which beside a read under way
 * would have two threads in okio's buffers). One still in its read after that, which a CDN gone silent may hold for its
 * 10 s read time-out, has its connection aborted from here: the close returns once the connection is let go, without
 * waiting out the reader, so a seek's new connection never opens beside the old one. That inner source is then the
 * reader's to close as its read comes back, and the next open makes another. The chunks go back to a pool the next
 * open takes them from.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class ReadAheadDataSource internal constructor(
    private val make: () -> AbortableDataSource,
    traffic: StreamTraffic?,
    /** How far ahead of the player the reader reads. */
    private val budget: Long,
) : BaseDataSource(/* isNetwork = */ true) {
    private val pool = ReadAhead.Pool(ReadAhead.chunksFor(budget))

    /** What the reads come from: kept across opens, but for one a close had to abort. */
    private var inner: AbortableDataSource? = null
    private var ahead: ReadAhead? = null
    private var uri: Uri? = null
    private var headers: Map<String, List<String>> = emptyMap()
    private var opened = false

    init {
        traffic?.let { addTransferListener(ServiceDataSource.TrafficMeter(it)) }
    }

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val source = inner ?: make().also { inner = it }
        val length = source.open(dataSpec)
        uri = source.uri
        headers = source.responseHeaders
        val readAhead = ReadAhead(dataSpec.position, budget, pool)
        ahead = readAhead
        thread(isDaemon = true, name = READER) {
            readAhead.fill(Reopening(source, dataSpec))
            // Closed while it was in the source: its connection is aborted, the rest of its close is the reader's.
            if (readAhead.upstreamLeftToReader) DataSourceUtil.closeQuietly(source)
        }
        opened = true
        transferStarted(dataSpec)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = checkNotNull(ahead) { "read before open" }.take(buffer, offset, length)
        if (n > 0) bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> = headers

    override fun close() {
        uri = null
        headers = emptyMap()
        val readAhead = ahead
        ahead = null
        val source = inner
        try {
            when {
                source == null -> Unit
                readAhead?.close(LET_GO_MS) == true -> {
                    inner = null
                    source.abort()
                }
                // No reader in it (none, one done or waiting for room, or one whose read came back): closed here, a failed
                // open's included.
                else -> source.close()
            }
        } finally {
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    /** The reader's view of the inner source: the open's body, and after a cut the file again from a byte, as the open asked. */
    private class Reopening(private val source: DataSource, private val spec: DataSpec) : ReadAhead.Upstream {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = source.read(buffer, offset, length)

        override fun reopen(position: Long) {
            source.close()
            source.open(spec.subrange(position - spec.position))
        }
    }

    /**
     * Makes a [ReadAheadDataSource] for each player, over sources [inner] makes without a meter (sharing its links), the
     * player's takes reaching [inner]'s meter. How far ahead it reads is asked as each source is made, on the loader's
     * thread (L249), as the engine's cap is: the device's memory is a call to another process, never made by the main
     * thread building the container. Null, the memory unread, is 8 MiB, Usenet's lowest cap: Usenet's own "no cap in
     * bytes" leans on a segment count an HTTP source hasn't.
     */
    class Factory internal constructor(
        private val inner: () -> AbortableDataSource,
        private val traffic: StreamTraffic?,
        private val readAheadBytes: () -> Long?,
    ) : DataSource.Factory {
        constructor(inner: ServiceDataSource.Factory, readAheadBytes: () -> Long?) : this(inner::createUnmetered, inner.traffic, readAheadBytes)

        override fun createDataSource(): DataSource = ReadAheadDataSource(inner, traffic, readAheadBytes() ?: UNREAD_MEMORY_BYTES)
    }

    companion object {
        /** The name of each open's reader thread: a debrid play's player reads ahead on one, as its device tests check. */
        const val READER = "debrid-read-ahead"

        internal const val UNREAD_MEMORY_BYTES = 8L * 1024 * 1024

        /**
         * How long a close waits for the reader to come out of a read before it aborts the connection: one from a body
         * still flowing comes back within milliseconds; one that doesn't waits on a CDN gone silent, where the abort meets
         * okio's guard on the socket's read rather than the body's buffers.
         */
        internal const val LET_GO_MS = 200L
    }
}
