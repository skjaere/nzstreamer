package io.skjaere.usenet.app

import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.NzbMetadataResponse
import io.skjaere.nzbstreamer.metadata.PrepareResult
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.nzb.NzbParser
import io.skjaere.nzbstreamer.stream.StreamableFile
import io.skjaere.usenet.core.NzbPreparer
import io.skjaere.usenet.core.PrepareBackend
import io.skjaere.usenet.core.PreparedNzbStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
