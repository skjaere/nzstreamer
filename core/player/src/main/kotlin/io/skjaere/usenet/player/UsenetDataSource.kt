package io.skjaere.usenet.player

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import io.skjaere.usenet.core.ContainerTypes
import io.skjaere.usenet.core.UsenetUris

/** Media3 adapter over [UsenetStreamReader] for `usenet://` URIs. */
@androidx.annotation.OptIn(UnstableApi::class)
class UsenetDataSource(private val reader: UsenetStreamReader) : BaseDataSource(/* isNetwork = */ true) {
    private var uri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val target = UsenetUris.parse(dataSpec.uri.toString())
            ?: throw DataSourceException("Not a usenet URI: ${dataSpec.uri}", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        transferInitializing(dataSpec)
        val length = reader.open(target.nzbId, target.fileIndex, dataSpec.position, dataSpec.length, target.name, target.size)
        opened = true
        transferStarted(dataSpec)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = reader.read(buffer, offset, length)
        if (n > 0) bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    /**
     * The opened file's container type, the hint DefaultExtractorsFactory uses to try that
     * extractor first. It reads no name from the URI's query, and sniffing in the default order lets
     * the MP3 and AC-3 sniffers claim an AVI whose first chunks are audio frames.
     */
    override fun getResponseHeaders(): Map<String, List<String>> =
        reader.fileName?.let(::containerMimeType)?.let { mapOf(CONTENT_TYPE to listOf(it)) } ?: emptyMap()

    override fun close() {
        reader.close()
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory(private val readerFactory: () -> UsenetStreamReader) : DataSource.Factory {
        override fun createDataSource(): DataSource = UsenetDataSource(readerFactory())
    }

    companion object {
        private const val CONTENT_TYPE = "Content-Type"

        /** The container MIME type [fileName]'s extension names, or null for one it does not know, as [ContainerTypes] has it for Cast's server too. */
        fun containerMimeType(fileName: String): String? = ContainerTypes.mimeOf(fileName)
    }
}
