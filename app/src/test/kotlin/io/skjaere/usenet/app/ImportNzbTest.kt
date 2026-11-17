package io.skjaere.usenet.app

import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.NzbMetadataResponse
import io.skjaere.nzbstreamer.metadata.PrepareResult
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.nzb.NzbParser
import io.skjaere.nzbstreamer.stream.StreamableFile
import io.skjaere.usenet.app.download.DownloadRequest
import io.skjaere.usenet.core.FileCandidate
import io.skjaere.usenet.core.NzbPreparer
import io.skjaere.usenet.core.PrepareBackend
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.PreparedNzbStore
import io.skjaere.usenet.core.discCopy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportNzbTest {
    @TempDir
    lateinit var tmp: File

    private val bytes = "<nzb/>".toByteArray()
    private val preparer = NzbPreparer(object : PrepareBackend {
        override suspend fun prepare(nzbBytes: ByteArray, password: String?) = PrepareResult.Success(
            ExtractedMetadata.Raw(NzbMetadataResponse(volumes = emptyList(), obfuscated = false, entries = emptyList()), NzbDocument(emptyList())),
        )
        override suspend fun streamableFiles(metadata: ExtractedMetadata) = listOf(StreamableFile("a.mkv", 10, 0, 0, 0, 0))
    })

    @Test
    fun `a ready NZB is saved for the player and shown`() = runBlocking<Unit> {
        val store = PreparedNzbStore(File(tmp, "nzb"))
        val ready = assertIs<ImportUi.Ready>(importNzb(preparer, store, bytes, "Title", password = "pw"))
        assertEquals(ready.nzb, store.get(ready.nzb.id))
    }

    @Test
    fun `an NZB that can't be saved is an error, not a crash`() = runBlocking<Unit> {
        // Disk full and I/O errors fail the write just like a directory that is a plain file.
        val store = PreparedNzbStore(File(tmp, "nzb").apply { writeText("") })
        val error = assertIs<ImportUi.Error>(importNzb(preparer, store, bytes, "Title", password = "pw"))
        assertTrue(error.message.startsWith("Couldn't save the NZB: "), error.message)
    }

    /**
     * L455, L423: a video of an NZB opened on Import is downloaded with the NZB's bytes and the archive password it was
     * opened with, read from the prepared NZB (what prepare unlocked it with), under the NZB's id, saying how many videos
     * the NZB holds. A file that isn't a video has no Download.
     */
    @Test
    fun `a ready NZB's video is downloaded with its bytes and the password it was opened with`() = runBlocking<Unit> {
        val season = NzbPreparer(object : PrepareBackend {
            // As nzb-streamer keeps it: the password prepare was given, in the archive's NZB.
            override suspend fun prepare(nzbBytes: ByteArray, password: String?) = PrepareResult.Success(
                ExtractedMetadata.Raw(NzbMetadataResponse(volumes = emptyList(), obfuscated = false, entries = emptyList()), NzbDocument(emptyList(), password)),
            )
            override suspend fun streamableFiles(metadata: ExtractedMetadata) = listOf(
                StreamableFile("Quiet.Meridian.S01E01.1080p.WEB-DL.H264.mkv", 1_400_000_000, 0, 0, 0, 0),
                StreamableFile("Quiet.Meridian.S01E02.1080p.WEB-DL.H264.mkv", 1_300_000_000, 1, 0, 0, 0),
                StreamableFile("Quiet.Meridian.S01.nfo", 2_000, 2, 0, 0, 0),
            )
        })
        val store = PreparedNzbStore(File(tmp, "nzb"))
        val ready = assertIs<ImportUi.Ready>(importNzb(season, store, bytes, "Quiet.Meridian.S01.1080p.WEB-DL.H264", password = "typed-on-import"))
        val file = ready.nzb.candidates[1]
        val request = assertNotNull(DownloadRequest.File.of(ready.nzb, file, bytes))
        assertEquals(PreparedNzb.idFor(bytes), request.nzbId)
        assertEquals(file, request.file)
        assertEquals("Quiet.Meridian.S01.1080p.WEB-DL.H264", request.nzbTitle)
        assertEquals(2, request.videos)
        assertContentEquals(bytes, request.source.nzb)
        assertEquals("typed-on-import", request.source.password)
        assertNull(DownloadRequest.File.of(ready.nzb, ready.nzb.candidates[2], bytes), "the .nfo")

        // Opened with no password: none kept.
        val open = assertIs<ImportUi.Ready>(importNzb(season, store, bytes, "Quiet.Meridian.S01.1080p.WEB-DL.H264", password = null))
        assertNull(assertNotNull(DownloadRequest.File.of(open.nzb, open.nzb.candidates[0], bytes)).source.password)
    }

    /** L455: a copy of a disc plays as no single video, so none of its files has a Download, not even its sample. */
    @Test
    fun `no file of a copy of a disc is downloaded`() {
        val metadata = ExtractedMetadata.Raw(NzbMetadataResponse(volumes = emptyList(), obfuscated = false, entries = emptyList()), NzbDocument(emptyList()))
        val files = listOf("Sample/copper.crown.sample.mkv" to 212_000_000L, "BDMV/STREAM/00800.m2ts" to 31_200_000_000L, "BDMV/index.bdmv" to 1_000L)
            .mapIndexed { i, (path, size) -> FileCandidate(i, StreamableFile(path, size, i, 0, 0, 0)) }
        val disc = PreparedNzb(PreparedNzb.idFor(bytes), "Copper.Crown.2022.COMPLETE.BLURAY", metadata, files)
        assertNotNull(disc.discCopy())
        files.forEach { assertNull(DownloadRequest.File.of(disc, it, bytes), it.path) }
    }

    /**
     * A file that isn't an NZB (another app can hand over anything named .nzb): the engine's parse
     * fails, or finds no files in an XML document of another kind. Import says so, not "Unexpected error".
     */
    @Test
    fun `a file that isn't an NZB says so`() = runBlocking<Unit> {
        val store = PreparedNzbStore(File(tmp, "nzb"))
        val parsing = NzbPreparer(object : PrepareBackend {
            override suspend fun prepare(nzbBytes: ByteArray, password: String?) = PrepareResult.Success(
                ExtractedMetadata.Raw(NzbMetadataResponse(volumes = emptyList(), obfuscated = false, entries = emptyList()), NzbParser.parse(nzbBytes)),
            )
            override suspend fun streamableFiles(metadata: ExtractedMetadata) = emptyList<StreamableFile>()
        })
        for (file in listOf("PK\u0003\u0004 not xml at all", "<?xml version=\"1.0\"?><playlist><track>one</track></playlist>")) {
            assertEquals(ImportUi.Error("This file isn't a valid NZB."), importNzb(parsing, store, file.toByteArray(), "Lantern Field", password = null), file)
        }
    }
}
