package io.skjaere.usenet.player

import androidx.media3.common.C
import androidx.media3.common.FileTypes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSourceException
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.cancel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import io.skjaere.nntp.ArticleNotFoundException
import io.skjaere.nntp.NntpConnectionException
import io.skjaere.nntp.NntpConnectionLostException
import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.NzbMetadataResponse
import io.skjaere.nzbstreamer.metadata.PrepareResult
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.stream.SegmentTimeoutException
import io.skjaere.nzbstreamer.stream.StreamableFile
import io.skjaere.usenet.core.FileCandidate
import io.skjaere.usenet.core.NzbPreparer
import io.skjaere.usenet.core.OpenedStream
import io.skjaere.usenet.core.PrepareBackend
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.PreparedNzbStore
import io.skjaere.usenet.core.StreamGaps
import io.skjaere.usenet.core.StreamOpener
import io.skjaere.usenet.core.StreamTraffic
import io.skjaere.usenet.core.UsenetError
import io.skjaere.usenet.core.UsenetException
import io.skjaere.usenet.core.UsenetUris
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.Source
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsenetStreamReaderTest {
    private val data = Random(1).nextBytes(200_000)
    private val nzb = PreparedNzb(
        id = "ab12",
        title = "test",
        metadata = ExtractedMetadata.Raw(NzbMetadataResponse(volumes = emptyList(), obfuscated = false, entries = emptyList()), NzbDocument(emptyList())),
        candidates = listOf("clip.mkv", "Show.S01E01.XviD-GRP.avi", "notes.xyz").mapIndexed { i, name ->
            FileCandidate(i, StreamableFile(name, data.size.toLong(), 0, 0, 0, 0))
        },
    )
    private val preparer = NzbPreparer(object : PrepareBackend {
        override suspend fun prepare(nzbBytes: ByteArray, password: String?) = error("not used")
        override suspend fun streamableFiles(metadata: ExtractedMetadata) = error("not used")
    })
    private val storeDir = kotlin.io.path.createTempDirectory("store").toFile()
    private val store = PreparedNzbStore(storeDir).apply { runBlocking { put(nzb, byteArrayOf(1)) } }

    private val openedRanges = mutableListOf<LongRange>()
    private val jobs = mutableListOf<Job>()
    private val opener = StreamOpener { _, _, range ->
        openedRanges += range
        val job = Job()
        jobs += job
        OpenedStream(ByteReadChannel(data.copyOfRange(range.first.toInt(), range.last.toInt() + 1)), job)
    }

    private fun UsenetStreamReader.readAll(): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(7_000)
        while (true) {
            val n = read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    @Test
    @Timeout(10)
    fun fullRead() {
        val reader = UsenetStreamReader(store, preparer, opener)
        assertEquals(data.size.toLong(), reader.open("ab12", 0, 0, C.LENGTH_UNSET.toLong()))
        assertContentEquals(data, reader.readAll())
        assertEquals(listOf(0L until data.size), openedRanges)
    }

    @Test
    @Timeout(10)
    fun theBytesReadAreCountedForTheStreamCostLine() {
        val traffic = StreamTraffic()
        val reader = UsenetStreamReader(store, preparer, opener, traffic)
        reader.open("ab12", 0, 0, C.LENGTH_UNSET.toLong())
        // A stream counts from its start, the first read: one opened and never read moved nothing.
        assertTrue(traffic.snapshot().streams.isEmpty())
        assertEquals(7_000, reader.read(ByteArray(7_000), 0, 7_000))
        assertEquals(listOf(7_000L), traffic.snapshot().streams.values.toList())
        reader.readAll()
        reader.close()
        // A seek is a close and an open: the next stream is counted on its own.
        reader.open("ab12", 0, 150_000, C.LENGTH_UNSET.toLong())
        reader.readAll()
        val snapshot = traffic.snapshot()
        assertEquals(data.size + 50_000L, snapshot.totalBytes)
        assertEquals(1L, snapshot.endedStreams)
        assertEquals(listOf(50_000L), snapshot.streams.values.toList())
        reader.close()
        assertEquals(2L, traffic.snapshot().endedStreams)
    }

    @Test
    @Timeout(10)
    fun rangedReadIsClampedToFileEnd() {
        val reader = UsenetStreamReader(store, preparer, opener)
        assertEquals(50_000L, reader.open("ab12", 0, 150_000, 80_000))
        assertContentEquals(data.copyOfRange(150_000, 200_000), reader.readAll())
        assertEquals(150_000L..199_999L, openedRanges.single())
    }

    @Test
    @Timeout(10)
    fun openAtEndReadsNothingAndOpensNoStream() {
        val reader = UsenetStreamReader(store, preparer, opener)
        assertEquals(0L, reader.open("ab12", 0, data.size.toLong(), C.LENGTH_UNSET.toLong()))
        assertEquals(C.RESULT_END_OF_INPUT, reader.read(ByteArray(10), 0, 10))
        assertTrue(openedRanges.isEmpty())
    }

    @Test
    @Timeout(10)
    fun positionPastEndIsOutOfRange() {
        val reader = UsenetStreamReader(store, preparer, opener)
        val e = assertFailsWith<DataSourceException> { reader.open("ab12", 0, data.size + 1L, C.LENGTH_UNSET.toLong()) }
        assertEquals(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, e.reason)
    }

    @Test
    @Timeout(10)
    fun earlyEndOfStreamIsAnError() {
        val short = StreamOpener { _, _, _ -> OpenedStream(ByteReadChannel(ByteArray(10)), Job()) }
        val reader = UsenetStreamReader(store, preparer, short)
        reader.open("ab12", 0, 0, 100)
        assertEquals(10, reader.read(ByteArray(100), 0, 100))
        assertFailsWith<DataSourceException> { reader.read(ByteArray(100), 0, 100) }
    }

    @Test
    @Timeout(10)
    fun missingArticleMapsToFileNotFound() {
        val failing = StreamOpener { _, _, _ ->
            val channel = ByteChannel()
            channel.close(ArticleNotFoundException("<x@y> not found on any pool"))
            OpenedStream(channel, Job())
        }
        val reader = UsenetStreamReader(store, preparer, failing)
        reader.open("ab12", 0, 0, 100)
        val e = assertFailsWith<UsenetDataSourceException> { reader.read(ByteArray(100), 0, 100) }
        assertIs<UsenetError.MissingArticle>(e.error)
        assertEquals(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, e.reason)
    }

    @Test
    @Timeout(10)
    fun playerErrorKeepsTheClassificationOfAThrownUsenetException() {
        // NzbStreamOpener throws this when the engine has no provider.
        val unconfigured = StreamOpener { _, _, _ -> throw UsenetException(UsenetError.NotConfigured) }
        val reader = UsenetStreamReader(store, preparer, unconfigured)
        reader.open("ab12", 0, 0, 100)
        // The stream starts with the first read.
        val e = assertFailsWith<UsenetDataSourceException> { reader.read(ByteArray(100), 0, 100) }
        // ExoPlayer hands this exception to onPlayerError as the PlaybackException's cause.
        assertEquals(UsenetError.NotConfigured, e.usenetError())
    }

    @Test
    @Timeout(10)
    fun playerErrorKeepsTheClassificationOfAnUnexpectedFailure() {
        val broken = StreamOpener { _, _, _ -> throw IllegalStateException("boom") }
        val reader = UsenetStreamReader(store, preparer, broken)
        reader.open("ab12", 0, 0, 100)
        val e = assertFailsWith<UsenetDataSourceException> { reader.read(ByteArray(100), 0, 100) }
        assertEquals(UsenetError.Unknown("boom"), e.usenetError())
    }

    @Test
    @Timeout(10)
    fun segmentTimeoutMapsToTimeout() {
        // nzb-streamer's segment fetch timing out on every provider: since N20 its writer closes the
        // channel with the library's own exception, which holds the timeout that fired.
        val fired = assertFailsWith<TimeoutCancellationException> { runBlocking { withTimeout(1) { awaitCancellation() } } }
        val words = "Segment <seg3@test> timed out after 5000ms on pool[0] (news.example.net:563)"
        val failing = StreamOpener { _, _, _ ->
            val channel = ByteChannel()
            channel.cancel(SegmentTimeoutException("seg3@test", 5_000, words, fired))
            OpenedStream(channel, Job())
        }
        val reader = UsenetStreamReader(store, preparer, failing)
        reader.open("ab12", 0, 0, 100)
        val e = assertFailsWith<UsenetDataSourceException> { reader.read(ByteArray(100), 0, 100) }
        assertEquals(UsenetError.Timeout(words), e.error)
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, e.reason)
        assertFalse(UsenetLoadErrorHandlingPolicy.isPermanent(e))
    }

    @Test
    @Timeout(10)
    fun readReturnsDataBeforeWriterFinishesAndCloseCancelsIt() {
        val writerJob = Job()
        val slow = StreamOpener { _, _, _ ->
            val channel = ByteChannel()
            CoroutineScope(Dispatchers.IO + writerJob).launch {
                channel.writeFully(ByteArray(1_000) { 7 })
                channel.flush() // Ktor 3 channels do not auto-flush; nzb-streamer's writer flushes too.
                kotlinx.coroutines.awaitCancellation()
            }
            OpenedStream(channel, writerJob)
        }
        val reader = UsenetStreamReader(store, preparer, slow)
        reader.open("ab12", 0, 0, C.LENGTH_UNSET.toLong())
        val buf = ByteArray(5_000)
        val n = reader.read(buf, 0, buf.size)
        assertTrue(n in 1..1_000)
        reader.close()
        assertTrue(writerJob.isCancelled)
    }

    /**
     * A container's index read a few bytes at a time (Matroska's Cues: megabytes in a long film, in
     * 1- to 8-byte reads) made a blocking round trip per read, millions of them: 20 s and more on a
     * TV box (#224). Reads of bytes the stream already holds copy them without one.
     */
    @Test
    @Timeout(10)
    fun smallReadsCopyWhatTheStreamHoldsWithoutBlocking() {
        val pieces = 4
        val writing = StreamOpener { _, _, _ ->
            val job = Job()
            val channel = CoroutineScope(Dispatchers.IO + job).writer {
                // As nzb-streamer's writer does: a segment at a time, each flushed.
                repeat(pieces) { i ->
                    channel.writeFully(data, i * data.size / pieces, (i + 1) * data.size / pieces)
                    channel.flush()
                }
            }.channel
            OpenedStream(channel, job)
        }
        val reader = UsenetStreamReader(store, preparer, writing)
        reader.open("ab12", 0, 0, C.LENGTH_UNSET.toLong())
        val out = ByteArrayOutputStream()
        val two = ByteArray(2)
        generateSequence { reader.read(two, 0, 2).takeUnless { it == C.RESULT_END_OF_INPUT } }.forEach { out.write(two, 0, it) }
        assertContentEquals(data, out.toByteArray())
        // At most one wait for each piece the writer flushed.
        assertTrue(reader.blockingReads <= pieces, "${reader.blockingReads} of ${reader.reads} reads went through the blocking bridge")
    }

    /**
     * Segments larger than the 1 MiB a Ktor channel takes before its writer waits in flush (a 4K
     * remux's are 3.84 MB): the reads of held bytes must still wake it, or the stream stalls.
     */
    @Test
    @Timeout(10)
    fun smallReadsWakeAWriterWaitingWithASegmentLargerThanTheChannel() {
        val piece = 1_500_000
        val large = Random(2).nextBytes(2 * piece)
        val big = PreparedNzb("big1", "big", nzb.metadata, listOf(FileCandidate(0, StreamableFile("big.mkv", large.size.toLong(), 0, 0, 0, 0))))
        runBlocking { store.put(big, byteArrayOf(2)) }
        val writing = StreamOpener { _, _, _ ->
            val job = Job()
            val channel = CoroutineScope(Dispatchers.IO + job).writer {
                repeat(2) { i ->
                    channel.writeFully(large, i * piece, (i + 1) * piece)
                    channel.flush()
                }
            }.channel
            OpenedStream(channel, job)
        }
        val reader = UsenetStreamReader(store, preparer, writing)
        reader.open("big1", 0, 0, C.LENGTH_UNSET.toLong())
        val out = ByteArrayOutputStream()
        val small = ByteArray(7)
        generateSequence { reader.read(small, 0, small.size).takeUnless { it == C.RESULT_END_OF_INPUT } }.forEach { out.write(small, 0, it) }
        assertContentEquals(large, out.toByteArray())
        assertTrue(reader.blockingReads <= 2, "${reader.blockingReads} of ${reader.reads} reads went through the blocking bridge")
    }

    /**
     * A fault in the channel that isn't its closing (Ktor's handoff to the writer failing as it moves
     * what was flushed) fails the read, as it did before held bytes were read without suspending:
     * taken for the channel closing there, it was lost, and the next read went on as if nothing happened.
     */
    @OptIn(InternalAPI::class)
    @Test
    @Timeout(10)
    fun aFaultInTheChannelThatIsntItsClosingFailsTheRead() {
        val held = Buffer().apply { write(ByteArray(100) { 1 }) }
        var faults = 1
        val faulty = object : ByteReadChannel {
            override val closedCause: Throwable? = null
            override val isClosedForRead: Boolean get() = false
            override val readBuffer: Source get() = if (faults-- > 0) throw IllegalStateException("Already resumed") else held
            override suspend fun awaitContent(min: Int) = true
            override fun cancel(cause: Throwable?) = Unit
        }
        val reader = UsenetStreamReader(store, preparer, StreamOpener { _, _, _ -> OpenedStream(faulty, Job()) })
        reader.open("ab12", 0, 0, 100)
        val e = assertFailsWith<UsenetDataSourceException> { reader.read(ByteArray(100), 0, 100) }
        assertIs<IllegalStateException>(e.cause)
    }

    @Test
    @Timeout(10)
    fun seekClosesThePreviousStream() {
        val reader = UsenetStreamReader(store, preparer, opener)
        reader.open("ab12", 0, 0, 1_000)
        assertEquals(10, reader.read(ByteArray(10), 0, 10))
        reader.open("ab12", 0, 100_000, 1_000)
        assertEquals(10, reader.read(ByteArray(10), 0, 10))
        assertTrue(jobs.first().isCancelled)
        assertEquals(listOf(0L..999L, 100_000L..100_999L), openedRanges)
    }

    /**
     * Media3 opens where an extractor seeks before its loader waits, and the track probe's loader
     * waits for good once it has the tracks: an MP4's or an AVI's seek back from its index opened a
     * stream nobody read, and one opened past a file's start downloads its whole read-ahead at once.
     */
    @Test
    @Timeout(10)
    fun anOpenThatIsNeverReadStartsNoStream() {
        val reader = UsenetStreamReader(store, preparer, opener)
        assertEquals(1_000L, reader.open("ab12", 0, 100_000, 1_000))
        reader.close()
        assertEquals(emptyList(), openedRanges, "streams started")
        // Read, it starts at the position opened.
        assertEquals(1_000L, reader.open("ab12", 0, 100_000, 1_000))
        assertContentEquals(data.copyOfRange(100_000, 101_000), reader.readAll())
        assertEquals(listOf(100_000L..100_999L), openedRanges)
    }

    @Test
    @Timeout(10)
    fun dataSourceTellsTheExtractorTheOpenedFileIsAnAvi() {
        // UsenetDataSource.open only parses its URI (a stub class on the JVM) and opens the reader.
        val reader = UsenetStreamReader(store, preparer, opener)
        val source = UsenetDataSource(reader)
        reader.open("ab12", 1, 0, C.LENGTH_UNSET.toLong())
        assertEquals(mapOf("Content-Type" to listOf("video/x-msvideo")), source.responseHeaders)
        // What DefaultExtractorsFactory reads to try the AVI extractor before the MP3 sniffer.
        assertEquals(FileTypes.AVI, FileTypes.inferFileTypeFromResponseHeaders(source.responseHeaders))
        reader.close()
        assertEquals(emptyMap(), source.responseHeaders)
    }

    @Test
    @Timeout(10)
    fun dataSourceGivesNoTypeForAnUnknownExtension() {
        val reader = UsenetStreamReader(store, preparer, opener)
        val source = UsenetDataSource(reader)
        reader.open("ab12", 2, 0, C.LENGTH_UNSET.toLong())
        assertEquals(emptyMap(), source.responseHeaders)
    }

    @Test
    fun containerMimeTypeFollowsTheExtension() {
        val types = listOf(
            "a.avi", "B.AVI", "c.mkv", "d.mp4", "e.m4v", "f.ts", "g.m2ts", "h.webm", "i.mov", "j.wmv", "k.mpg", "l.mpeg",
            "m.srt", "README", "n.avi.part",
        ).associateWith { UsenetDataSource.containerMimeType(it) }
        assertEquals(
            mapOf(
                "a.avi" to "video/x-msvideo", "B.AVI" to "video/x-msvideo", "c.mkv" to "video/x-matroska",
                "d.mp4" to "video/mp4", "e.m4v" to "video/mp4", "f.ts" to "video/mp2t", "g.m2ts" to "video/mp2t",
                "h.webm" to "video/webm", "i.mov" to "video/quicktime", "j.wmv" to "video/x-ms-wmv",
                "k.mpg" to "video/mp2p", "l.mpeg" to "video/mp2p",
                "m.srt" to null, "README" to null, "n.avi.part" to null,
            ),
            types,
        )
    }

    @Test
    @Timeout(10)
    fun uriRoundTrip() {
        assertEquals(UsenetUris.Target("ab12", 3), UsenetUris.parse(UsenetUris.build("ab12", 3)))
        assertEquals(null, UsenetUris.parse("https://example.com"))
        val name = "Show S01E01 & 100% más+(=).mkv"
        assertEquals(UsenetUris.Target("ab12", 3, name, 1_234), UsenetUris.parse(UsenetUris.build("ab12", 3, name, 1_234)))
        assertEquals(UsenetUris.Target("ab12", 1, "Show.S01E01.XviD-GRP.avi", data.size.toLong()), UsenetUris.parse(UsenetUris.of("ab12", nzb.candidates[1])))
    }

    /**
     * After process death the store prepares the NZB again, and that listing can put the file
     * elsewhere: prepare leaves out a file that lacks its first article while another stands in
     * (library issue N15). The reader opens the file its URI names, not the one now at its position
     * (#179); a URI from before names were opens by position. A name the listing lacks is a file not
     * there, which loading again won't change.
     */
    @Test
    @Timeout(10)
    fun theFileIsOpenedByTheNameItsUriGivesWhereverThePreparedAgainNzbListsIt() {
        val listedOtherwise = NzbPreparer(object : PrepareBackend {
            override suspend fun prepare(nzbBytes: ByteArray, password: String?) = PrepareResult.Success(nzb.metadata)
            override suspend fun streamableFiles(metadata: ExtractedMetadata) = nzb.candidates.drop(1).map { it.file }
        })
        val opened = mutableListOf<Int>()
        val recording = StreamOpener { prepared, fileIndex, range -> opened += fileIndex; opener.open(prepared, fileIndex, range) }
        // Opened and read from: the stream starts with the first read.
        fun open(uri: String): UsenetStreamReader {
            val target = assertNotNull(UsenetUris.parse(uri))
            return UsenetStreamReader(PreparedNzbStore(storeDir), listedOtherwise, recording).apply {
                open(target.nzbId, target.fileIndex, 0, 100, target.name, target.size)
                assertEquals(1, read(ByteArray(1), 0, 1))
            }
        }
        assertEquals("Show.S01E01.XviD-GRP.avi", open(UsenetUris.of("ab12", nzb.candidates[1])).fileName)
        assertEquals(listOf(0), opened)
        assertEquals("notes.xyz", open(UsenetUris.build("ab12", 1)).fileName)
        val gone = assertFailsWith<DataSourceException> { open(UsenetUris.of("ab12", nzb.candidates[0])) }
        assertEquals(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, gone.reason)
        assertTrue(UsenetLoadErrorHandlingPolicy.isPermanent(gone))
    }

    /**
     * After process death the store has the NZB's bytes but not its prepared state, and prepares it
     * again on open. When that can't succeed (articles gone, an archive it can't stream), loading
     * again only repeats it: the open fails as the retry policy won't retry (#62).
     */
    @Test
    @Timeout(10)
    fun anNzbThatCanNoLongerBePreparedFailsForGood() {
        val outcomes = listOf(
            PrepareResult.MissingArticles("2 of 40 articles missing", IOException("430")) to UsenetError.MissingArticle::class,
            PrepareResult.UnsupportedArchive("RAR with compression", IOException("method 3")) to null,
        )
        for ((result, error) in outcomes) {
            val unprepared = PreparedNzbStore(storeDir)
            val preparing = NzbPreparer(object : PrepareBackend {
                override suspend fun prepare(nzbBytes: ByteArray, password: String?) = result
                override suspend fun streamableFiles(metadata: ExtractedMetadata) = error("not used")
            })
            val reader = UsenetStreamReader(unprepared, preparing, opener)
            val e = assertFailsWith<DataSourceException> { reader.open("ab12", 0, 0, 100) }
            assertTrue(UsenetLoadErrorHandlingPolicy.isPermanent(e), "$result: $e")
            if (error != null) assertEquals(error, (e as UsenetDataSourceException).error::class)
        }
    }

    /**
     * A stream whose download fails partway, as nzb-streamer's writer ends it: the bytes in front
     * of the failing segment are written, and once the reader has them ([readInFront]) the writer
     * fails, closing the channel with [failure] as its cause. The job that started it isn't failed.
     */
    private fun failingPartway(failure: Throwable, readInFront: CompletableDeferred<Unit>) = StreamOpener { _, _, range ->
        val session = Job()
        val writer = CoroutineScope(Dispatchers.IO + session).writer {
            channel.writeFully(data.copyOfRange(range.first.toInt(), range.first.toInt() + 10))
            channel.flush()
            readInFront.await()
            throw failure
        }
        OpenedStream(writer.channel, session)
    }

    /** Reads what is in front of the failure, lets the failure come and returns how the next read fails. */
    private fun UsenetStreamReader.failureAfterTenBytes(readInFront: CompletableDeferred<Unit>): UsenetDataSourceException {
        open("ab12", 0, 0, 100)
        val buffer = ByteArray(100)
        var got = 0
        while (got < 10) got += read(buffer, got, 100 - got)
        assertContentEquals(data.copyOf(10), buffer.copyOf(got))
        readInFront.complete(Unit)
        return assertFailsWith<UsenetDataSourceException> { read(buffer, 0, 100) }
    }

    /**
     * An article missing on every provider, partway through: the read fails as a missing article,
     * which the player doesn't retry (#62), not as a dropped connection.
     */
    @Test
    @Timeout(10)
    fun anArticleMissingPartwayFailsTheReadForGood() {
        val readInFront = CompletableDeferred<Unit>()
        val failing = failingPartway(ArticleNotFoundException("Article <seg4@mock> not found on any pool"), readInFront)
        val e = UsenetStreamReader(store, preparer, failing).failureAfterTenBytes(readInFront)
        assertIs<UsenetError.MissingArticle>(e.error)
        assertEquals(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, e.reason)
        assertTrue(UsenetLoadErrorHandlingPolicy.isPermanent(e))
    }

    /**
     * A provider's connection dropping partway through an article, as the library reports it: a connection lost on the
     * way, which the player loads again.
     */
    @Test
    @Timeout(10)
    fun aConnectionDroppedPartwayFailsTheReadSoThePlayerLoadsAgain() {
        val readInFront = CompletableDeferred<Unit>()
        val dropped = NntpConnectionLostException("Connection closed mid-article, before =yend or the article terminator")
        val e = UsenetStreamReader(store, preparer, failingPartway(dropped, readInFront)).failureAfterTenBytes(readInFront)
        assertIs<UsenetError.Network>(e.error)
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, e.reason)
        assertFalse(UsenetLoadErrorHandlingPolicy.isPermanent(e))
    }

    /** A provider that can't be reached partway through, a later segment's connect refused: the player loads again too. */
    @Test
    @Timeout(10)
    fun aProviderOutOfReachPartwayFailsTheReadSoThePlayerLoadsAgain() {
        val readInFront = CompletableDeferred<Unit>()
        val refused = NntpConnectionException("Could not connect to news.example.net:563: Connection refused")
        val e = UsenetStreamReader(store, preparer, failingPartway(refused, readInFront)).failureAfterTenBytes(readInFront)
        assertIs<UsenetError.ConnectionFailed>(e.error)
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, e.reason)
        assertFalse(UsenetLoadErrorHandlingPolicy.isPermanent(e))
    }

    /**
     * Streams [data] as the engine's streams do where articles of the first file are [missing] on every
     * provider: zeros in their place, each recorded in the stream's gaps (offsets in the file, from where
     * the stream starts) before a byte of them is written. Written [piece] bytes at a time, each flushed,
     * as nzb-streamer's writer writes a segment at a time; [beforeWriting] runs before each piece, with its
     * offset in the file. The other files are whole.
     */
    private fun zeroFilling(missingInFirst: List<LongRange>, piece: Int = 10_000, beforeWriting: suspend (Long) -> Unit = {}) = StreamOpener { _, fileIndex, range ->
        openedRanges += range
        val missing = if (fileIndex == 0) missingInFirst else emptyList()
        val job = Job()
        val gaps = StreamGaps()
        val channel = CoroutineScope(Dispatchers.IO + job).writer {
            var at = range.first
            while (at <= range.last) {
                val end = minOf(at + piece, range.last + 1)
                beforeWriting(at)
                val bytes = data.copyOfRange(at.toInt(), end.toInt())
                for (gap in missing) {
                    val from = maxOf(gap.first, at)
                    val to = minOf(gap.last, end - 1)
                    if (from > to) continue
                    // Recorded once, as the stream comes to it: from where the stream starts, if inside it.
                    if (from == maxOf(gap.first, range.first)) gaps.add(from..gap.last)
                    bytes.fill(0, (from - at).toInt(), (to - at + 1).toInt())
                }
                channel.writeFully(bytes)
                channel.flush()
                at = end
            }
        }.channel
        OpenedStream(channel, job, gaps)
    }

    /** Reads [buffer]-sized pieces until a read fails or the end: what came in front, and the failure. */
    private fun UsenetStreamReader.readUntilFailure(buffer: Int = 7_000): Pair<ByteArray, Exception?> {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(buffer)
        while (true) {
            val n = try {
                read(buf, 0, buf.size)
            } catch (e: Exception) {
                return out.toByteArray() to e
            }
            if (n == C.RESULT_END_OF_INPUT) return out.toByteArray() to null
            out.write(buf, 0, n)
        }
    }

    /**
     * An article no provider has, filled with zeros by the stream: the reader hands over every byte in
     * front of it, never one of its zeros, and the next read fails at its first byte, saying which bytes
     * are missing. Not loaded again (a retry would reach the same byte), as a missing article wasn't.
     */
    @Test
    @Timeout(10)
    fun aReadStopsAtTheFirstByteOfAGapAndTheNextSaysWhichBytesAreMissing() {
        val gap = 50_000L..59_999L
        val reader = UsenetStreamReader(store, preparer, zeroFilling(listOf(gap)))
        reader.open("ab12", 0, 0, C.LENGTH_UNSET.toLong())
        val (read, failure) = reader.readUntilFailure()
        assertContentEquals(data.copyOf(50_000), read)
        val reached = assertIs<GapReached>(failure)
        assertEquals(gap, reached.range)
        assertIs<UsenetError.MissingArticle>(reached.error)
        assertEquals(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, reached.reason)
        assertTrue(UsenetLoadErrorHandlingPolicy.isPermanent(reached))
        // Read again, it is reached again: nothing past it is handed over.
        assertEquals(gap, assertFailsWith<GapReached> { reader.read(ByteArray(10), 0, 10) }.range)
    }

    /** A run of missing articles, found one at a time: one gap, all of it, so a skip goes past the whole run. */
    @Test
    @Timeout(10)
    fun missingArticlesSideBySideAreReachedAsOneGap() {
        val reader = UsenetStreamReader(store, preparer, zeroFilling(listOf(50_000L..54_999L, 55_000L..59_999L, 70_000L..79_999L)))
        reader.open("ab12", 0, 0, C.LENGTH_UNSET.toLong())
        val (read, failure) = reader.readUntilFailure()
        assertEquals(50_000, read.size)
        assertEquals(50_000L..59_999L, assertIs<GapReached>(failure).range)
    }

    /**
     * The gap found while a read waits for the stream (the blocking bridge, not the bytes it holds): the
     * read that gets bytes from in front of it and from it returns only those in front.
     */
    @Test
    @Timeout(10)
    fun aGapFoundWhileAReadWaitsCutsThatReadAtItsStart() {
        val gap = 45_000L..54_999L
        val inFrontRead = CountDownLatch(1)
        // The writer holds back from 40,000 until the reader has what is in front, then writes 40,000 to
        // 59,999 at once, the gap's zeros in it: the reader is by then waiting in the bridge, which hands
        // over up to Ktor's 8 KiB at a time, past the gap's start.
        val opener = zeroFilling(listOf(gap), piece = 20_000) { at ->
            if (at == 40_000L) {
                inFrontRead.await(5, TimeUnit.SECONDS)
                kotlinx.coroutines.delay(200)
            }
        }
        val reader = UsenetStreamReader(store, preparer, opener)
        reader.open("ab12", 0, 0, C.LENGTH_UNSET.toLong())
        val buffer = ByteArray(100_000)
        var got = 0
        while (got < 40_000) got += reader.read(buffer, got, 40_000 - got)
        inFrontRead.countDown()
        val blockingBefore = reader.blockingReads
        assertEquals(5_000, reader.read(buffer, 0, buffer.size))
        assertTrue(reader.blockingReads > blockingBefore, "the read didn't wait for the stream")
        assertContentEquals(data.copyOfRange(40_000, 45_000), buffer.copyOf(5_000))
        assertEquals(gap, assertFailsWith<GapReached> { reader.read(buffer, 0, buffer.size) }.range)
    }

    /**
     * Past a gap (where the player goes on from, its stream opened again there) the reader reads on; opened
     * in front of it again (a seek back) it stops there, and opened inside it, at once, naming the whole gap,
     * though the new stream fills only the part from where it starts: what the reader knows of the file's
     * gaps outlives its streams.
     */
    @Test
    @Timeout(10)
    fun theReaderStopsAtAGapItKnowsFromAnEarlierStreamOfTheFile() {
        val gap = 50_000L..59_999L
        val reader = UsenetStreamReader(store, preparer, zeroFilling(listOf(gap)))
        reader.open("ab12", 0, 0, C.LENGTH_UNSET.toLong())
        assertIs<GapReached>(reader.readUntilFailure().second)
        reader.close()
        reader.open("ab12", 0, 60_000, C.LENGTH_UNSET.toLong())
        val (after, end) = reader.readUntilFailure()
        assertNull(end)
        assertContentEquals(data.copyOfRange(60_000, data.size), after)
        reader.close()
        reader.open("ab12", 0, 45_000, C.LENGTH_UNSET.toLong())
        val (inFront, reached) = reader.readUntilFailure()
        assertEquals(5_000, inFront.size)
        assertEquals(gap, assertIs<GapReached>(reached).range)
        reader.close()
        reader.open("ab12", 0, 55_000, C.LENGTH_UNSET.toLong())
        assertEquals(gap, assertFailsWith<GapReached> { reader.read(ByteArray(10), 0, 10) }.range)
        reader.close()
        // Another file of the NZB has none of them.
        reader.open("ab12", 1, 45_000, 10_000)
        assertContentEquals(data.copyOfRange(45_000, 55_000), reader.readUntilFailure().first)
    }

    /** The stream-cost line counts what the player got: the bytes in front of a gap, none of its zeros. */
    @Test
    @Timeout(10)
    fun aGapsZerosArentCountedForTheStreamCostLine() {
        val traffic = StreamTraffic()
        val reader = UsenetStreamReader(store, preparer, zeroFilling(listOf(50_000L..59_999L)), traffic)
        reader.open("ab12", 0, 0, C.LENGTH_UNSET.toLong())
        reader.readUntilFailure()
        assertEquals(50_000L, traffic.snapshot().totalBytes)
    }

    /**
     * The player of a file counts the missing stretches its streams find, as they find them: the read-ahead's too, past
     * what the player has read. Each is reported by file, to those listening for its NZB.
     */
    @Test
    @Timeout(10)
    fun theStretchesAStreamFindsAreReportedForItsFileAsFound() {
        val reports = GapReports()
        val heard = CopyOnWriteArrayList<GapReports.Found>()
        val other = CopyOnWriteArrayList<GapReports.Found>()
        reports.listen("ab12") { heard += it }
        reports.listen("cd34") { other += it }
        val reader = UsenetStreamReader(store, preparer, zeroFilling(listOf(50_000L..59_999L, 150_000L..159_999L)), reports = reports)
        reader.open("ab12", 0, 0, C.LENGTH_UNSET.toLong())
        val buffer = ByteArray(10_000)
        var got = 0
        while (got < 10_000) got += reader.read(buffer, got, 10_000 - got)
        // The stream writes on ahead of the reader.
        val deadline = System.currentTimeMillis() + 5_000
        while (heard.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(listOf(GapReports.Found("ab12", 0, "clip.mkv", 50_000L..59_999L), GapReports.Found("ab12", 0, "clip.mkv", 150_000L..159_999L)), heard.toList())
        assertTrue(other.isEmpty())
    }
}
