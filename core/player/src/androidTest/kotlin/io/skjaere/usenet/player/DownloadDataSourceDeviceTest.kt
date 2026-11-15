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
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/**
 * A finished download's file as the player reads it (L352, [DownloadDataSource]): its bytes from the file the app's
 * [DownloadFiles] finds for the `download://` URI's id; a read that stops at the first byte of a stretch the download had
 * no article for, the next failing with [GapReached] for the whole stretch, as a stream's does, so the extractors skip it;
 * the container type from the release's own name for the file; and a download with no file, or whose file is gone, as a
 * file not there, which the load policy doesn't retry. On a device: the JVM's android.jar can't make a [Uri]. The file is
 * random bytes, written in this test's own cache and deleted after; the ids and names are invented.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class DownloadDataSourceDeviceTest {
    private val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "download-data-source").apply { mkdirs() }
    private val bytes = Random(352).nextBytes(SIZE)
    private val file = File(folder, "v.mkv").apply { writeBytes(bytes) }

    /** What the app keeps, by id: [ID]'s file, with [gaps], named as the release names it. */
    private var gaps = listOf<LongRange>()
    private var name: String? = "Harbor.Lights.S01E01.1080p.WEB.h264-KESTREL.mkv"
    private val asked = mutableListOf<String>()
    private val files = DownloadFiles { id ->
        asked += id
        if (id == ID) DownloadFile(file, SIZE.toLong(), gaps, name) else null
    }
    private val source = DownloadDataSource.Factory(files).createDataSource()

    @After
    fun tearDown() {
        source.close()
        folder.deleteRecursively()
    }

    @Test
    fun theFilesBytesAreRead() {
        val uri = Uri.parse(PlayUris.download(ID))
        assertEquals(SIZE.toLong(), source.open(DataSpec(uri)))
        assertEquals(uri, source.uri)
        assertArrayEquals(bytes, readAll(source))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(8), 0, 8))
        source.close()
        assertEquals(listOf(ID), asked)

        // A seek: opened again where it goes on, for a length.
        assertEquals(1_000L, source.open(DataSpec.Builder().setUri(uri).setPosition(40_000).setLength(1_000).build()))
        assertArrayEquals(bytes.copyOfRange(40_000, 41_000), readAll(source))
        source.close()
        // To the end, past which nothing opens.
        assertEquals(0L, source.open(DataSpec.Builder().setUri(uri).setPosition(SIZE.toLong()).build()))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(8), 0, 8))
        source.close()
        val past = assertThrows(DataSourceException::class.java) { source.open(DataSpec.Builder().setUri(uri).setPosition(SIZE + 1L).build()) }
        assertEquals(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, past.reason)
    }

    @Test
    fun aReadStopsAtAGapAndTheNextFailsWithTheWholeStretch() {
        // Two stretches that touch read as one, as a stream merges them.
        gaps = listOf(20_000L..20_999L, 21_000L..24_999L, 50_000L..50_099L)
        val uri = Uri.parse(PlayUris.download(ID))
        source.open(DataSpec(uri))
        val read = readUntilGap(source)
        assertArrayEquals("the bytes in front of it, and no zero of it", bytes.copyOfRange(0, 20_000), read.first)
        assertEquals(20_000L..24_999L, read.second.range)
        source.close()

        // Opened past it, as the extractor's jump opens it: on to the next stretch.
        source.open(DataSpec.Builder().setUri(uri).setPosition(25_000).build())
        val next = readUntilGap(source)
        assertArrayEquals(bytes.copyOfRange(25_000, 50_000), next.first)
        assertEquals(50_000L..50_099L, next.second.range)
        source.close()

        // Opened inside one, as a seek may land: its first read fails at once.
        source.open(DataSpec.Builder().setUri(uri).setPosition(22_000).build())
        assertEquals(20_000L..24_999L, assertThrows(GapReached::class.java) { source.read(ByteArray(64), 0, 64) }.range)
        source.close()

        // Past the last, to the end, whole.
        source.open(DataSpec.Builder().setUri(uri).setPosition(50_100).build())
        assertArrayEquals(bytes.copyOfRange(50_100, SIZE), readAll(source))
        // A missing part, as the load policy reads it: not loaded again.
        assertTrue(UsenetLoadErrorHandlingPolicy.isPermanent(GapReached(20_000L..24_999L)))
    }

    @Test
    fun theContainerTypeIsTheStoredNamesAndTheReadIsNoNetworksOne() {
        val networks = mutableListOf<Boolean>()
        source.addTransferListener(object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                networks += isNetwork
            }
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) = Unit
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        })
        source.open(DataSpec(Uri.parse(PlayUris.download(ID))))
        assertEquals(mapOf("Content-Type" to listOf("video/x-matroska")), source.responseHeaders)
        assertEquals(listOf(false), networks)
        source.close()
        // An MP4 release's file, though the file on the device is v.mkv: the release's name says the type.
        name = "Copper.Crown.2019.1080p.WEB.h264-WREN.mp4"
        source.open(DataSpec(Uri.parse(PlayUris.download(ID))))
        assertEquals(listOf("video/mp4"), source.responseHeaders["Content-Type"])
        source.close()
        // No name kept, or one of no type it knows: no hint, so the extractors sniff.
        name = null
        source.open(DataSpec(Uri.parse(PlayUris.download(ID))))
        assertEquals(emptyMap<String, List<String>>(), source.responseHeaders)
        source.close()
        assertEquals(emptyMap<String, List<String>>(), source.responseHeaders)
    }

    @Test
    fun aDownloadWithNoFileOrAFileGoneReadsAsNotFound() {
        // No finished download of that id.
        val none = assertThrows(DataSourceException::class.java) { source.open(DataSpec(Uri.parse(PlayUris.download("not-a-download")))) }
        assertEquals(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, none.reason)
        assertTrue(UsenetLoadErrorHandlingPolicy.isPermanent(none))
        source.close()

        // Its file deleted since.
        assertTrue(file.delete())
        val gone = assertThrows(DataSourceException::class.java) { source.open(DataSpec(Uri.parse(PlayUris.download(ID)))) }
        assertEquals(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, gone.reason)
        assertTrue(UsenetLoadErrorHandlingPolicy.isPermanent(gone))
        // Neither says the release's name.
        assertFalse(none.message.orEmpty().contains("Harbor") || gone.message.orEmpty().contains("Harbor"))
        source.close()
    }

    @Test
    fun aFileShorterThanItsSizeFailsAsAReadPastItsEnd() {
        file.writeBytes(bytes.copyOf(SIZE - 1_000))
        source.open(DataSpec(Uri.parse(PlayUris.download(ID))))
        val failed = assertThrows(DataSourceException::class.java) { readAll(source) }
        assertEquals(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, failed.reason)
    }

    /** Every byte [source] gives until its end. */
    private fun readAll(source: DataSource): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(4_096)
        while (true) {
            val n = source.read(chunk, 0, chunk.size)
            if (n == C.RESULT_END_OF_INPUT) return out.toByteArray()
            out.write(chunk, 0, n)
        }
    }

    /** What [source] gives until a read fails at a gap, and that failure. */
    private fun readUntilGap(source: DataSource): Pair<ByteArray, GapReached> {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(4_096)
        while (true) {
            val n = try {
                source.read(chunk, 0, chunk.size)
            } catch (reached: GapReached) {
                return out.toByteArray() to reached
            }
            if (n == C.RESULT_END_OF_INPUT) throw AssertionError("no gap reached")
            out.write(chunk, 0, n)
        }
    }

    private companion object {
        const val ID = "3f2c9e1a-7b4d-4c2e-9a10-5e6f7a8b9c0d"
        const val SIZE = 64 * 1024
    }
}
