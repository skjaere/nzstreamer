package io.skjaere.usenet.player

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * The player's and the probe's data source: each open goes to the source of the URI's scheme ([PlayUris.scheme]) in
 * [byScheme]. A scheme none has fails as a file not there, which the load policy doesn't retry
 * ([UsenetLoadErrorHandlingPolicy]), and opens nothing. Each scheme's source is made at its first open and opened again
 * after, as Media3 opens its one data source again for a seek: Usenet's keeps what it learnt of the file's gaps. The
 * transfer listeners Media3 adds reach every source, made already or later, as they reach the source itself.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class SourceDataSource(private val byScheme: Map<String, DataSource.Factory>) : DataSource {
    private val made = mutableMapOf<String, DataSource>()
    private val listeners = mutableListOf<TransferListener>()

    /** The source the last open went to, until [close]. */
    private var opened: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        made.values.forEach { it.addTransferListener(transferListener) }
    }

    override fun open(dataSpec: DataSpec): Long {
        val scheme = PlayUris.scheme(dataSpec.uri.toString())
        val factory = scheme?.let(byScheme::get)
            ?: throw DataSourceException("No source opens a ${scheme ?: "scheme-less"} URI", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val source = made.getOrPut(scheme) { factory.createDataSource().also { s -> listeners.forEach(s::addTransferListener) } }
        opened = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = checkNotNull(opened) { "read before open" }.read(buffer, offset, length)

    override fun getUri(): Uri? = opened?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = opened?.responseHeaders ?: emptyMap()

    override fun close() {
        val source = opened ?: return
        try {
            source.close()
        } finally {
            opened = null
        }
    }

    /** Makes a [SourceDataSource] over [byScheme] for each player and probe. */
    class Factory(private val byScheme: Map<String, DataSource.Factory>) : DataSource.Factory {
        override fun createDataSource(): DataSource = SourceDataSource(byScheme)
    }
}
