package io.skjaere.usenet.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.skjaere.usenet.core.UsenetUris
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The player's and the probe's data source routes a play's URI by its scheme ([SourceDataSource]): a `usenet://` URI
 * reaches Usenet's source, a scheme no source has fails as a file not there (which the load policy doesn't retry) and
 * opens nothing, and each scheme's source is made once and opened again, as Media3 opens its one data source again for a
 * seek. On a device: the JVM's android.jar can't make the [Uri] a [DataSpec] holds. Fake sources, invented names.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class SourceDataSourceDeviceTest {
    /** A source of [bytes] that records what is asked of it. */
    private class FakeSource(private val bytes: ByteArray) : DataSource {
        val opened = mutableListOf<DataSpec>()
        val listeners = mutableListOf<TransferListener>()
        var closes = 0
        private var uri: Uri? = null
        private var position = 0

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
        }

        override fun open(dataSpec: DataSpec): Long {
            opened += dataSpec
            uri = dataSpec.uri
            position = dataSpec.position.toInt()
            return (bytes.size - position).toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + n)
            position += n
            return n
        }

        override fun getUri(): Uri? = uri

        override fun getResponseHeaders(): Map<String, List<String>> = mapOf("Content-Type" to listOf("video/x-matroska"))

        override fun close() {
            closes++
            uri = null
        }
    }

    private class FakeFactory(private val bytes: ByteArray) : DataSource.Factory {
        val made = mutableListOf<FakeSource>()

        override fun createDataSource(): DataSource = FakeSource(bytes).also { made += it }
    }

    private val usenet = FakeFactory(byteArrayOf(1, 2, 3, 4))
    private val service = FakeFactory(byteArrayOf(9, 8))
    private val source = SourceDataSource.Factory(mapOf(PlayUris.USENET to usenet, PlayUris.SERVICE to service)).createDataSource()

    private val usenetUri = Uri.parse(UsenetUris.build("abc123", 0, "Harbor.Lights.S01E01.1080p.mkv", 4))

    @Test
    fun aUsenetUriReachesUsenetsSource() {
        assertEquals(4L, source.open(DataSpec(usenetUri)))
        val read = ByteArray(4)
        assertEquals(4, source.read(read, 0, 4))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), read)
        assertEquals(C.RESULT_END_OF_INPUT, source.read(read, 0, 4))
        assertEquals(usenetUri, source.uri)
        assertEquals(listOf("video/x-matroska"), source.responseHeaders["Content-Type"])
        assertEquals(listOf(usenetUri), usenet.made.single().opened.map { it.uri })
        assertTrue("the service's source isn't made", service.made.isEmpty())
    }

    @Test
    fun aSchemeNoSourceHasFailsAsNotFoundAndOpensNothing() {
        for (uri in listOf("https://cdn.example.org/Harbor.Lights.S01E01.mkv", "file:///sdcard/Copper.Crown.mkv", "content://media/external/downloads/7", "clip.mkv")) {
            val e = assertThrows(DataSourceException::class.java) { source.open(DataSpec(Uri.parse(uri))) }
            assertEquals(uri, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, e.reason)
        }
        assertTrue("no source made", usenet.made.isEmpty() && service.made.isEmpty())
        assertNull(source.uri)
        assertEquals(emptyMap<String, List<String>>(), source.responseHeaders)
        // Closing it, as Media3 does after a failed open, closes nothing.
        source.close()
        assertTrue(usenet.made.isEmpty() && service.made.isEmpty())
    }

    @Test
    fun closeReachesTheOneOpened() {
        val uri = Uri.parse(PlayUris.service("l7f3a9", 0))
        assertEquals(2L, source.open(DataSpec(uri)))
        assertEquals(uri, source.uri)
        source.close()
        assertEquals(1, service.made.single().closes)
        assertTrue("Usenet's source isn't made", usenet.made.isEmpty())
        assertNull(source.uri)
        // Closed again, it closes nothing more.
        source.close()
        assertEquals(1, service.made.single().closes)
    }

    @Test
    fun eachSchemesSourceIsMadeOnceAndOpenedAgain() {
        // A seek: closed, then opened at the new place. Usenet's source keeps what it learnt of the file's gaps.
        source.open(DataSpec(usenetUri))
        source.close()
        source.open(DataSpec.Builder().setUri(usenetUri).setPosition(2).build())
        val read = ByteArray(2)
        assertEquals(2, source.read(read, 0, 2))
        assertArrayEquals(byteArrayOf(3, 4), read)
        source.close()
        val made = usenet.made.single()
        assertEquals(listOf(0L, 2L), made.opened.map { it.position })
        assertEquals(2, made.closes)
        // Another scheme in between has its own.
        source.open(DataSpec(Uri.parse(PlayUris.service("l7f3a9", 1))))
        source.close()
        source.open(DataSpec(usenetUri))
        source.close()
        assertEquals(1, usenet.made.size)
        assertEquals(1, service.made.size)
    }

    @Test
    fun aTransferListenerReachesEverySource() {
        val first = listener()
        source.addTransferListener(first)
        source.open(DataSpec(usenetUri))
        source.close()
        assertEquals(listOf(first), usenet.made.single().listeners)
        // One added later reaches the sources made, and those made after.
        val second = listener()
        source.addTransferListener(second)
        assertEquals(listOf(first, second), usenet.made.single().listeners)
        source.open(DataSpec(Uri.parse(PlayUris.service("l7f3a9", 0))))
        source.close()
        assertEquals(listOf(first, second), service.made.single().listeners)
    }

    private fun listener() = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) = Unit
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    }
}
