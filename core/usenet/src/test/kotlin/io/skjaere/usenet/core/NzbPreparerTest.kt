package io.skjaere.usenet.core

import io.skjaere.compressionutils.EncryptionInfo
import io.skjaere.compressionutils.MalformedRarArchiveException
import io.skjaere.nntp.ArticleNotFoundException
import io.skjaere.nzbstreamer.enrichment.ArchiveVolumeNotInNzbException
import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.NzbMetadataResponse
import io.skjaere.nzbstreamer.metadata.PrepareResult
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.stream.SegmentTimeoutException
import io.skjaere.nzbstreamer.stream.StreamableFile
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NzbPreparerTest {
    private val metadata = ExtractedMetadata.Raw(
        response = NzbMetadataResponse(volumes = emptyList(), obfuscated = false, entries = emptyList()),
        orderedArchiveNzb = NzbDocument(files = emptyList()),
    )

    private fun file(path: String, size: Long) = StreamableFile(
        path = path, totalSize = size, startVolumeIndex = 0, startOffsetInVolume = 0,
        continuationHeaderSize = 0, endOfArchiveSize = 0,
    )

    private fun backend(result: () -> PrepareResult, files: List<StreamableFile> = emptyList()) = object : PrepareBackend {
        var lastPassword: String? = null
        override suspend fun prepare(nzbBytes: ByteArray, password: String?): PrepareResult {
            lastPassword = password
            return result()
        }
        override suspend fun streamableFiles(metadata: ExtractedMetadata) = files
    }

    @Test
    fun `success lists candidates and picks the largest non-sample video`() = runTest {
        val files = listOf(
            file("movie.nfo", 2_000),
            file("Movie.Sample.mkv", 50_000_000),
            file("Movie.2024.1080p.mkv", 4_000_000_000),
            file("extras/featurette.mp4", 300_000_000),
        )
        val outcome = NzbPreparer(backend({ PrepareResult.Success(metadata) }, files)).prepare("nzb".toByteArray(), "t")
        val ready = assertIs<PrepareOutcome.Ready>(outcome)
        assertEquals(4, ready.nzb.candidates.size)
        assertEquals("Movie.2024.1080p.mkv", ready.nzb.defaultPick?.name)
        assertEquals(PreparedNzb.idFor("nzb".toByteArray()), ready.nzb.id)
    }

    @Test
    fun `backend runs off the calling thread`() {
        // Preparing fetches and decodes articles and parses archive headers: it must never run on
        // the caller's thread, which may be Android's main one. (nzb-streamer's prepare used to
        // block there as well, on work launched in the caller's context: a self-deadlock, N7.)
        val callerThreads = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }
        try {
            var backendThread: String? = null
            val recording = object : PrepareBackend {
                override suspend fun prepare(nzbBytes: ByteArray, password: String?): PrepareResult {
                    backendThread = Thread.currentThread().name
                    return PrepareResult.Success(metadata)
                }
                override suspend fun streamableFiles(metadata: ExtractedMetadata) = listOf(file("a.mkv", 1))
            }
            runBlocking(callerThreads.asCoroutineDispatcher()) { NzbPreparer(recording).prepare(byteArrayOf(1), "t") }
            // Debug mode appends " @coroutine#n" to thread names.
            assertFalse(backendThread.orEmpty().startsWith("fake-main"), "backend ran on $backendThread")
        } finally {
            callerThreads.shutdown()
        }
    }

    @Test
    fun `success without streamable files is unsupported`() = runTest {
        val outcome = NzbPreparer(backend({ PrepareResult.Success(metadata) })).prepare(byteArrayOf(1), "t")
        assertIs<PrepareOutcome.Unsupported>(outcome)
    }

    @Test
    fun `encrypted reports whether a password was tried`() = runTest {
        val info = EncryptionInfo(
            archiveName = "a.rar", volumeIndex = 0, encryptionVersion = 0, kdfIterationsLog2 = 15,
            salt = null, hasPasswordCheck = true,
        )
        val preparer = NzbPreparer(backend({ PrepareResult.Encrypted("enc", info) }))
        assertEquals(false, assertIs<PrepareOutcome.NeedsPassword>(preparer.prepare(byteArrayOf(1), "t")).passwordWasTried)
        assertEquals(true, assertIs<PrepareOutcome.NeedsPassword>(preparer.prepare(byteArrayOf(1), "t", "pw")).passwordWasTried)
    }

    @Test
    fun `exceptions escaping prepare become Failed`() = runTest {
        val malformed = NzbPreparer(backend({ throw MalformedRarArchiveException("a.rar", 0, byteArrayOf(1), "bad") }))
        assertIs<UsenetError.Corrupt>(assertIs<PrepareOutcome.Failed>(malformed.prepare(byteArrayOf(1), "t")).error)

        val notConfigured = NzbPreparer(backend({ throw UsenetException(UsenetError.NotConfigured) }))
        assertEquals(UsenetError.NotConfigured, assertIs<PrepareOutcome.Failed>(notConfigured.prepare(byteArrayOf(1), "t")).error)
    }

    /**
     * A volume the NZB doesn't hold is missing on every provider; an article a provider lacks may be
     * on one that wasn't asked. The outcome tells them apart (library issue NR-18).
     */
    @Test
    fun `missing articles say whether the NZB doesn't hold what is missing`() = runTest {
        val words = "Archive volume 'Movie.2020.part2.rar', which the PAR2 index names, isn't in the NZB"
        val notInNzb = NzbPreparer(backend({ PrepareResult.MissingArticles(words, ArchiveVolumeNotInNzbException(words)) }))
        assertEquals(PrepareOutcome.MissingArticles(words, notInNzb = true), notInNzb.prepare(byteArrayOf(1), "t"))
        val gone = NzbPreparer(backend({ PrepareResult.MissingArticles("Article <x> not found", ArticleNotFoundException("Article <x> not found")) }))
        assertEquals(PrepareOutcome.MissingArticles("Article <x> not found", notInNzb = false), gone.prepare(byteArrayOf(1), "t"))
    }

    /** nzb-streamer's own timeouts arrive as SegmentTimeoutException (N20), which says which article timed out where. */
    @Test
    fun `a library timeout is Timeout in its words`() = runTest {
        val words = "Segment <seg0@test> timed out after 5000ms on pool[0] (news.example.net:563)"
        val preparer = NzbPreparer(backend({ PrepareResult.Failure(words, SegmentTimeoutException("seg0@test", 5_000, words)) }))
        assertEquals(UsenetError.Timeout(words), assertIs<PrepareOutcome.Failed>(preparer.prepare(byteArrayOf(1), "t")).error)
    }

    /** Out of prepare, a TimeoutCancellationException is a cancellation (someone's withTimeout) and goes on up, not the release's failure. */
    @Test
    fun `a timeout's cancellation out of prepare is rethrown`() = runTest {
        val fired = runCatching { withTimeout(1) { delay(1_000) } }.exceptionOrNull()
        assertIs<TimeoutCancellationException>(fired)
        val preparer = NzbPreparer(backend({ throw fired }))
        assertIs<TimeoutCancellationException>(runCatching { preparer.prepare(byteArrayOf(1), "t") }.exceptionOrNull())
    }

    @Test
    fun `store re-prepares from disk after restart`() = runTest {
        val dir = kotlin.io.path.createTempDirectory("nzbstore").toFile()
        val preparer = NzbPreparer(backend({ PrepareResult.Success(metadata) }, listOf(file("a.mkv", 10))))
        val bytes = "<nzb/>".toByteArray()
        val first = assertIs<PrepareOutcome.Ready>(preparer.prepare(bytes, "Title")).nzb
        PreparedNzbStore(dir).put(first, bytes)

        val restarted = PreparedNzbStore(dir)
        assertTrue(restarted.get(first.id) == null)
        val again = assertIs<PrepareOutcome.Ready>(restarted.getOrPrepare(first.id, preparer)).nzb
        assertEquals("Title", again.title)
        assertTrue(restarted.get(first.id) != null)
    }
}
