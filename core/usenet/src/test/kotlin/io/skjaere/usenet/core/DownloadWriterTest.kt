package io.skjaere.usenet.core

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.NzbMetadataResponse
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.usenet.core.DownloadWriter.GiveUp
import io.skjaere.usenet.core.DownloadWriter.Outcome
import io.skjaere.usenet.core.DownloadWriter.Wait
import io.skjaere.usenet.core.FakeNntpServer.Body
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.security.cert.CertificateException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.AbstractLongTimeSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

/**
 * The download writer (item 6; L342, L345, L381, L382) writing a clip of 8 MiB, in 16 articles of 512 KiB, to a file of its
 * own: through the engine's download opener from a [FakeNntpServer] where the transfer itself is what is tested, and
 * through openers of the test's where a provider is out of reach or fails in a set way, with the waits between reopens on
 * virtual time. The record is checked against what the file holds: never ahead of what was written and forced.
 */
@OptIn(ExperimentalCoroutinesApi::class) // The test scheduler's clock.
class DownloadWriterTest {
    private val clip = TestClip(segmentSize = 512 * 1024, count = 16)
    private val size = clip.data.size.toLong()
    private val part = clip.segmentSize.toLong()
    private val video = clip.nzb.candidates.single()
    private val engine = UsenetEngine()
    private val servers = mutableListOf<FakeNntpServer>()
    private val dir = createTempDirectory("download-writer").toFile()
    private val path = File(dir, "v.mkv.part")

    @AfterTest
    fun tearDown() {
        engine.close()
        servers.forEach(FakeNntpServer::close)
        dir.deleteRecursively()
    }

    /** Article [i]'s bytes, as offsets in the file. */
    private fun bytesOf(i: Int) = i * part until (i + 1) * part

    /** The clip with articles [zeroed] as zeros, as a stream fills those no provider has. */
    private fun zeroed(vararg zeroed: Int) = clip.data.copyOf().also { data ->
        zeroed.forEach { i -> data.fill(0, (i * part).toInt(), ((i + 1) * part).toInt()) }
    }

    /**
     * The engine on a server of the clip's articles: STAT finds each, and BODY of article i is as [body] says, whole by
     * default. With a [throttle], each connection's answers go at that rate, its waits passed to [throttleWait].
     */
    private suspend fun serve(
        throttle: Long? = null,
        throttleWait: (Int, Long) -> Unit = { _, _ -> },
        body: (Int) -> Body = { Body.Article(clip.article(it)) },
    ): FakeNntpServer {
        val server = FakeNntpServer(has = { true }, body = { id -> body(clip.index(id)) }, throttle = throttle, throttleWait = throttleWait)
        servers += server
        engine.configure(EngineSettings(listOf(server.provider())))
        return server
    }

    /** How many BODYs of article [i] the server answered. */
    private fun FakeNntpServer.bodies(i: Int) = commands.count { it == "BODY <seg$i@test>" }

    /** The download's file, as long as the clip: the app allocates it whole before its first byte. */
    private fun channel(): FileChannel = RandomAccessFile(path, "rw").apply { setLength(size) }.channel

    private fun writer(
        opener: StreamOpener = NzbStreamOpener.download(engine),
        time: TimeSource = TimeSource.Monotonic,
        network: MutableStateFlow<Boolean> = MutableStateFlow(true),
        throttle: Long? = null,
        recordBytes: Long = DownloadWriter.RECORD_BYTES,
        recordEvery: Duration = DownloadWriter.RECORD_EVERY,
        longGapBytes: Long = DownloadWriter.LONG_GAP_BYTES,
    ) = DownloadWriter(opener, network, { throttle }, time, recordBytes, recordEvery, longGapBytes)

    /** A record the writer made: its safe offset and gaps. */
    private data class Record(val offset: Long, val gaps: List<LongRange>)

    /** What a write did: its outcome, the first byte of each stream it opened, its records. */
    private class Wrote(val outcome: Outcome, val opens: List<Long>, val records: List<Record>)

    /** [nzb]'s clip written to [out] from [from], with [gaps] recorded before: [opened] and [recorded] hear of each. */
    private suspend fun DownloadWriter.wrote(
        out: FileChannel = channel(),
        from: Long = 0,
        gaps: List<LongRange> = emptyList(),
        nzb: PreparedNzb = clip.nzb,
        opened: (Long) -> Unit = {},
        recorded: (Record) -> Unit = {},
    ): Wrote {
        val opens = CopyOnWriteArrayList<Long>()
        val records = CopyOnWriteArrayList<Record>()
        val outcome = out.use {
            write(ID, nzb, video, it, from, gaps, onOpen = { offset -> opens += offset; opened(offset) }) { offset, known ->
                Record(offset, known).also { r ->
                    records += r
                    recorded(r)
                }
            }
        }
        return Wrote(outcome, opens, records)
    }

    @Test
    fun `the file is written byte for byte, forced and recorded at its end`() = runBlocking<Unit> {
        val server = serve()
        val wrote = withTimeout(60_000) { writer().wrote() }
        assertEquals(Outcome.Done(emptyList()), wrote.outcome)
        assertContentEquals(clip.data, path.readBytes())
        assertEquals(Record(size, emptyList()), wrote.records.last())
        assertEquals(listOf(0L), wrote.opens)
        (0 until 16).forEach { i -> assertEquals(1, server.bodies(i), "BODYs of article $i") }
    }

    @Test
    fun `force comes before each record, which is never ahead of what was written`() = runBlocking<Unit> {
        serve()
        val out = RecordingChannel(channel())
        val wrote = withTimeout(60_000) {
            writer(recordBytes = MIB).wrote(out) { r ->
                out.events += RecordingChannel.Recorded(r.offset)
                assertTrue(r.offset <= out.forced, "recorded ${r.offset} with ${out.forced} forced")
            }
        }
        assertEquals(Outcome.Done(emptyList()), wrote.outcome)
        assertTrue(wrote.records.size >= 8, "a record each MiB: ${wrote.records.map { it.offset }}")
        out.events.withIndex().filter { it.value is RecordingChannel.Recorded }.forEach { (i, _) ->
            assertEquals(RecordingChannel.Forced, out.events[i - 1], "what came before the record at event $i")
        }
        assertContentEquals(clip.data, path.readBytes())
    }

    @Test
    fun `a gap is recorded no later than the record that passes it, and its zeros are written`() = runBlocking<Unit> {
        serve { i -> if (i == 5) Body.Missing else Body.Article(clip.article(i)) }
        val wrote = withTimeout(60_000) { writer(recordBytes = MIB).wrote() }
        assertEquals(Outcome.Done(listOf(bytesOf(5))), wrote.outcome)
        wrote.records.forEach { r ->
            if (r.offset > bytesOf(5).first) assertEquals(listOf(bytesOf(5)), r.gaps, "the record at ${r.offset}")
        }
        assertTrue(wrote.records.any { it.offset in bytesOf(5).first + 1 until size }, "no record passed the gap before the end")
        assertContentEquals(zeroed(5), path.readBytes())
    }

    @Test
    fun `a connection cut mid-BODY is a network failure, reopened at the safe offset, nothing before it fetched again`() = runBlocking<Unit> {
        val opens = AtomicInteger()
        val server = serve { i ->
            if (i == 9 && opens.get() < 2) Body.Dropped(clip.article(i), bytes = 100_000) else Body.Article(clip.article(i))
        }
        val wrote = withTimeout(60_000) { writer().wrote(opened = { opens.incrementAndGet() }) }
        assertEquals(Outcome.Done(emptyList()), wrote.outcome)
        assertEquals(2, wrote.opens.size, "streams opened at ${wrote.opens}")
        // Where the reader had got to: what the stream held unread in front of the cut went with its failure.
        val safe = wrote.opens[1]
        assertTrue(safe in 2 * part..bytesOf(9).first, "reopened at $safe")
        // Recorded at the failure: the reopen's first byte is the safe offset, and only from there is anything fetched again.
        assertTrue(wrote.records.any { it.offset == safe }, "records ${wrote.records.map { it.offset }}, reopened at $safe")
        (0 until 16).filter { bytesOf(it).last < safe }.forEach { i -> assertEquals(1, server.bodies(i), "BODYs of article $i") }
        assertTrue(server.bodies(9) >= 2)
        assertContentEquals(clip.data, path.readBytes())
    }

    @Test
    fun `a rebuild of the engine mid-download ends its stream, and the writer goes on on the new streamer`() = runBlocking<Unit> {
        val reached = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        serve { i ->
            if (i == 8 && reached.count > 0) {
                reached.countDown()
                letGo.await(30, TimeUnit.SECONDS)
            }
            Body.Article(clip.article(i))
        }
        val write = async { withTimeout(60_000) { writer().wrote() } }
        withContext(Dispatchers.IO) { assertTrue(reached.await(30, TimeUnit.SECONDS), "the stream never reached article 8") }
        val first = engine.streamer()
        engine.rebuildWhenIdle()
        assertTrue(first !== engine.streamer(), "the download held the rebuild")
        letGo.countDown()
        val wrote = write.await()
        assertEquals(Outcome.Done(emptyList()), wrote.outcome)
        assertEquals(2, wrote.opens.size, "streams opened: ${wrote.opens}")
        assertContentEquals(clip.data, path.readBytes())
    }

    @Test
    fun `a part its NZB lists no article for gives the release up for the next, with no reopen`() = runBlocking<Unit> {
        serve()
        val unlisted = clip.file.copy(segments = clip.file.segments.filter { it.number != 8 })
        val nzb = PreparedNzb(
            "ab13", "Clip",
            ExtractedMetadata.Raw(NzbMetadataResponse(volumes = listOf("clip.mkv"), obfuscated = false, entries = emptyList()), NzbDocument(listOf(unlisted))),
            clip.nzb.candidates,
        )
        val wrote = withTimeout(60_000) { writer().wrote(nzb = nzb) }
        assertEquals(Outcome.NextRelease(GiveUp.Incomplete), wrote.outcome)
        assertEquals(listOf(0L), wrote.opens)
    }

    @Test
    fun `a long gap stops the stream and gives the release up, where a shorter one is written as zeros`() = runBlocking<Unit> {
        val missing = mutableSetOf(4, 5, 6)
        serve { i -> if (i in missing) Body.Missing else Body.Article(clip.article(i)) }
        val long = withTimeout(60_000) { writer(longGapBytes = 3 * part).wrote() }
        assertEquals(Outcome.NextRelease(GiveUp.LongGap), long.outcome)
        assertEquals(listOf(0L), long.opens)

        missing -= 6
        val short = withTimeout(60_000) { writer(longGapBytes = 3 * part).wrote() }
        assertEquals(Outcome.Done(listOf(bytesOf(4).first..bytesOf(5).last)), short.outcome)
        assertContentEquals(zeroed(4, 5), path.readBytes())
    }

    @Test
    fun `six reopens that reached the provider and made no progress fail it`() = runTest(timeout = 60.seconds) {
        val server = serve()
        // Logged in, and every article refused: the provider answers, and nothing comes.
        server.refusing = true
        val wrote = writer(time = testScheduler.timeSource).wrote()
        assertEquals(Outcome.Failed(DownloadWriter.NO_PROGRESS), wrote.outcome)
        // The first stream and six reopens, each after twice the wait before: 2 s, then 4, 8, 16, 32 and 64.
        assertEquals(List(7) { 0L }, wrote.opens)
        assertEquals((2 + 4 + 8 + 16 + 32 + 64).seconds.inWholeMilliseconds, currentTime)
    }

    /**
     * The reader gets ktor's wrapper of what ended the stream, an IOException, whatever that was: a failure the libraries
     * don't name (a check of theirs that failed) is no dropped connection, and counts toward the six (L382), never toward an
     * hour of a provider out of reach.
     */
    @Test
    fun `a failure the libraries don't name counts toward the six with no progress, not as a provider out of reach`() = runTest(timeout = 60.seconds) {
        val wrote = writer(
            StreamOpener { _, _, _ -> failed(IllegalStateException("A check of the library's that failed")) },
            time = testScheduler.timeSource,
        ).wrote()
        assertEquals(Outcome.Failed(DownloadWriter.NO_PROGRESS), wrote.outcome)
        assertEquals(List(7) { 0L }, wrote.opens)
        assertEquals((2 + 4 + 8 + 16 + 32 + 64).seconds.inWholeMilliseconds, currentTime)
    }

    @Test
    fun `ten minutes of refused connections on a validated network fail nothing, and it goes on from its offset`() = runTest(timeout = 60.seconds) {
        var cutting = true
        var refuseUntil: Long? = null
        val server = serve { i -> if (i == 6 && cutting) Body.Dropped(clip.article(i), bytes = 100_000) else Body.Article(clip.article(i)) }
        var unreachableSaid = false
        lateinit var writer: DownloadWriter
        val refusing = Refusing(NzbStreamOpener.download(engine)) {
            (refuseUntil?.let { currentTime < it } ?: false).also { if (it && writer.live.value?.unreachable == true) unreachableSaid = true }
        }
        writer = writer(refusing, time = testScheduler.timeSource)
        var inReachAtEnd = false
        val wrote = writer.wrote(
            opened = { offset ->
                if (offset > 0 && refuseUntil == null) {
                    cutting = false
                    refuseUntil = currentTime + 10.minutes.inWholeMilliseconds
                }
            },
            recorded = { r -> if (r.offset == size) inReachAtEnd = writer.live.value?.unreachable == false },
        )
        assertEquals(Outcome.Done(emptyList()), wrote.outcome)
        assertContentEquals(clip.data, path.readBytes())
        // Refused well over six times, and each reopen from where the cut left it.
        assertTrue(refusing.refused.get() > DownloadWriter.MAX_NO_PROGRESS, "refused ${refusing.refused.get()} times")
        val safe = wrote.opens[1]
        assertTrue(safe in 1..bytesOf(6).first && wrote.opens.drop(1).all { it == safe }, "streams opened at ${wrote.opens}")
        assertTrue(currentTime >= 10.minutes.inWholeMilliseconds)
        assertTrue(unreachableSaid, "the writer never said the provider couldn't be reached")
        assertTrue(inReachAtEnd, "the writer still said the provider couldn't be reached once its bytes came")
        (0 until 16).filter { bytesOf(it).last < safe }.forEach { i -> assertEquals(1, server.bodies(i), "BODYs of article $i") }
    }

    @Test
    fun `an hour of refused connections ends in the wait, what was written recorded`() = runTest(timeout = 60.seconds) {
        var refuse = false
        serve { i -> if (i == 6) Body.Dropped(clip.article(i), bytes = 100_000) else Body.Article(clip.article(i)) }
        val refusing = Refusing(NzbStreamOpener.download(engine)) { refuse }
        val wrote = writer(refusing, time = testScheduler.timeSource).wrote(opened = { offset -> if (offset > 0) refuse = true })
        assertEquals(Outcome.Waiting(Wait.Unreachable), wrote.outcome)
        // Recorded where the cut left the reader, which every reopen started from.
        val safe = wrote.records.last().offset
        assertTrue(safe in 1..bytesOf(6).first && wrote.opens.drop(1).all { it == safe }, "streams opened at ${wrote.opens}, recorded $safe")
        assertTrue(currentTime >= 1.hours.inWholeMilliseconds, "waited ${currentTime / 1000} s")
        // Not much past the hour: the failure that found an hour gone ends it.
        assertTrue(currentTime < (1.hours + DownloadWriter.MAX_BACKOFF * 2).inWholeMilliseconds, "waited ${currentTime / 1000} s")
        assertContentEquals(clip.data.copyOf(safe.toInt()), path.readBytes().copyOf(safe.toInt()))
    }

    @Test
    fun `on a network Android doesn't call validated, failures count nothing, and its return reopens at once`() = runTest(timeout = 60.seconds) {
        var cutting = true
        var refuse = false
        val network = MutableStateFlow(true)
        serve { i -> if (i == 6 && cutting) Body.Dropped(clip.article(i), bytes = 100_000) else Body.Article(clip.article(i)) }
        val opens = mutableListOf<Long>()
        val back = 70.minutes.inWholeMilliseconds
        val refusing = Refusing(NzbStreamOpener.download(engine)) { refuse }
        val wrote = writer(refusing, time = testScheduler.timeSource, network = network).wrote(opened = { offset ->
            opens += currentTime
            if (offset > 0 && !refuse && cutting) {
                cutting = false
                refuse = true
                network.value = false
                // Back after more than the hour that ends a wait for a provider out of reach on a validated network.
                backgroundScope.launch {
                    delay(back - currentTime)
                    refuse = false
                    network.value = true
                }
            }
        })
        assertEquals(Outcome.Done(emptyList()), wrote.outcome)
        assertTrue(refusing.refused.get() > DownloadWriter.MAX_NO_PROGRESS, "refused ${refusing.refused.get()} times")
        assertEquals(back, opens.last(), "the network came back at ${back / 1000} s; opened at ${opens.map { it / 1000 }}")
        assertContentEquals(clip.data, path.readBytes())
    }

    @Test
    fun `retryNow ends the wait between reopens and starts the backoff again from 2 s`() = runTest(timeout = 60.seconds) {
        val opens = mutableListOf<Long>()
        lateinit var writer: DownloadWriter
        writer = writer(
            StreamOpener { _, _, range ->
                opens += currentTime
                if (opens.size < 7) refused() else OpenedStream(ByteReadChannel(clip.data.copyOfRange(range.first.toInt(), size.toInt())), Job())
            },
            time = testScheduler.timeSource,
        )
        backgroundScope.launch {
            delay(40.seconds)
            writer.retryNow()
        }
        val wrote = writer.wrote()
        assertEquals(Outcome.Done(emptyList()), wrote.outcome)
        // 2, 4, 8 and 16 s apart; the wait of 32 s ended at 40 s; then 2 s again.
        assertEquals(listOf(0L, 2, 6, 14, 30, 40, 42).map { it * 1000 }, opens)
        assertContentEquals(clip.data, path.readBytes())
    }

    /**
     * A Resume, a provider saved or the network's return that comes while a stream is being opened, not while the writer
     * waits, still ends the wait after that stream fails (L381): a provider out of reach takes a connect's time to fail,
     * and the wait after it can be five minutes.
     */
    @Test
    fun `a retryNow or the network's return while a stream opens ends the wait after it`() = runTest(timeout = 60.seconds) {
        val opens = mutableListOf<Long>()
        val network = MutableStateFlow(true)
        lateinit var writer: DownloadWriter
        writer = writer(
            StreamOpener { _, _, range ->
                opens += currentTime
                when (opens.size) {
                    // Resume as the second opens.
                    2 -> writer.retryNow()
                    // The network lost and back as the fourth opens.
                    4 -> {
                        network.value = false
                        delay(100)
                        network.value = true
                    }
                }
                if (opens.size < 5) refused() else OpenedStream(ByteReadChannel(clip.data.copyOfRange(range.first.toInt(), size.toInt())), Job())
            },
            time = testScheduler.timeSource,
            network = network,
        )
        val wrote = writer.wrote()
        assertEquals(Outcome.Done(emptyList()), wrote.outcome)
        // 2 s after the first; none after the second, the backoff starting again; 2 s; none after the fourth.
        assertEquals(listOf(0L, 2_000, 2_000, 4_000, 4_100), opens)
        assertContentEquals(clip.data, path.readBytes())
    }

    @Test
    fun `a login refused or no provider waits for the providers, and an incomplete or damaged release goes to the next`() = runTest(timeout = 60.seconds) {
        val cases = mapOf(
            UsenetError.AuthFailed("481 rejected by news.harbor-lights.invalid") to Outcome.Waiting(Wait.Provider),
            UsenetError.NotConfigured to Outcome.Waiting(Wait.Provider),
            UsenetError.MissingArticle("<seg3@test> not found") to Outcome.NextRelease(GiveUp.Incomplete),
            UsenetError.Corrupt("bad yEnc") to Outcome.NextRelease(GiveUp.Corrupt),
        )
        cases.forEach { (error, expected) ->
            val wrote = writer(StreamOpener { _, _, _ -> failed(UsenetException(error)) }, time = testScheduler.timeSource).wrote()
            assertEquals(expected, wrote.outcome, "for $error")
            assertEquals(listOf(0L), wrote.opens, "for $error")
        }
        // The engine with no provider at all.
        UsenetEngine().use { none ->
            val wrote = writer(NzbStreamOpener.download(none), time = testScheduler.timeSource).wrote()
            assertEquals(Outcome.Waiting(Wait.Provider), wrote.outcome)
        }
    }

    @Test
    fun `a stream that ends short with no cause is opened again from where it ended`() = runTest(timeout = 60.seconds) {
        var first = true
        val wrote = writer(
            StreamOpener { _, _, range ->
                val end = if (first) range.first + 3 * part else size
                first = false
                OpenedStream(ByteReadChannel(clip.data.copyOfRange(range.first.toInt(), end.toInt())), Job())
            },
            time = testScheduler.timeSource,
        ).wrote()
        assertEquals(Outcome.Done(emptyList()), wrote.outcome)
        assertEquals(listOf(0L, 3 * part), wrote.opens)
        assertContentEquals(clip.data, path.readBytes())
    }

    /**
     * nzb-streamer ends a stream with no cause where the file's last listed article is shorter than its layout guessed
     * (an NZB that lacks the file's last articles, whose size it can't count): every reopen ends at the same byte. The
     * release can't give what lies past it, and gives up for the next (L382's guessed size), never six reopens that blame
     * the provider.
     */
    @Test
    fun `a stream that keeps ending short at the same byte gives the release up for the next`() = runTest(timeout = 60.seconds) {
        val end = 5 * part + 1_234
        val wrote = writer(
            StreamOpener { _, _, range -> OpenedStream(ByteReadChannel(clip.data.copyOfRange(range.first.toInt(), maxOf(range.first, end).toInt())), Job()) },
            time = testScheduler.timeSource,
        ).wrote()
        assertEquals(Outcome.NextRelease(GiveUp.Incomplete), wrote.outcome)
        assertEquals(listOf(0L, end), wrote.opens)
        assertEquals(Record(end, emptyList()), wrote.records.last())
    }

    @Test
    fun `a full volume fails it as Storage is full, with no reopen and no record past the failed write`() = runBlocking<Unit> {
        serve()
        // The JVM's words for ENOSPC, and Android's.
        listOf("No space left on device", "write failed: ENOSPC (No space left on device)").forEach { words ->
            val at = bytesOf(5).first + 1_234
            val out = RecordingChannel(channel(), fullAt = at, full = words)
            val wrote = withTimeout(60_000) { writer(recordBytes = MIB).wrote(out) }
            assertEquals(Outcome.Failed(DownloadWriter.STORAGE_FULL), wrote.outcome, "for \"$words\"")
            assertEquals(listOf(0L), wrote.opens, "for \"$words\"")
            assertTrue(wrote.records.isNotEmpty() && wrote.records.all { it.offset <= at }, "records ${wrote.records.map { it.offset }} for \"$words\"")
            assertContentEquals(clip.data.copyOf(at.toInt()), path.readBytes().copyOf(at.toInt()))
        }
    }

    @Test
    fun `its log lines name the download, sizes and causes' kinds, never a host, a release or a cause's words`() = runTest(timeout = 60.seconds) {
        val certificate = CertificateException("The server's certificate is not for $HOST; it names $OTHER_HOST")
        var opens = 0
        val (wrote, log) = stderrOf {
            writer(
                StreamOpener { _, _, _ ->
                    opens++
                    when (opens) {
                        // A provider that answered, in words naming its host; then one whose certificate isn't for its host.
                        1 -> failed(UsenetException(UsenetError.Protocol("502 access denied by $HOST")))
                        2 -> failed(IOException("TLS handshake failed", certificate))
                        else -> throw IOException("TLS handshake failed", certificate)
                    }
                },
                time = testScheduler.timeSource,
            ).wrote()
        }
        assertEquals(Outcome.Waiting(Wait.Unreachable), wrote.outcome)
        val lines = log.lines().filter { "DownloadWriter" in it }
        assertTrue(lines.size >= 3, "the writer's lines: $lines")
        assertTrue(lines.all { ID in it }, "a line without the download's id: $lines")
        assertTrue(lines.any { "ConnectionFailed" in it } && lines.any { "Protocol" in it }, "no cause's kind: $lines")
        listOf("harbor-lights", "copper-crown", "certificate", "denied", "TLS", clip.nzb.title, video.name).forEach { words ->
            assertFalse(lines.any { words in it }, "\"$words\" in the writer's lines: $lines")
        }
    }

    @Test
    fun `the end's retry clears a gap whose article came back, and keeps one that didn't`() = runBlocking<Unit> {
        var firstPass = true
        val server = serve { i ->
            when {
                i == 3 && firstPass -> Body.Missing
                i == 10 -> Body.Missing
                else -> Body.Article(clip.article(i))
            }
        }
        val wrote = withTimeout(60_000) { writer().wrote(recorded = { r -> if (r.offset == size) firstPass = false }) }
        assertEquals(Outcome.Done(listOf(bytesOf(10))), wrote.outcome)
        assertEquals(Record(size, listOf(bytesOf(3), bytesOf(10))), wrote.records.first { it.offset == size })
        assertEquals(Record(size, listOf(bytesOf(10))), wrote.records.last())
        assertContentEquals(zeroed(10), path.readBytes())
        assertTrue(server.bodies(3) >= 2)
    }

    @Test
    fun `a cancel at any point leaves the record behind the data, never ahead`() = runBlocking<Unit> {
        serve { i -> if (i == 12) Body.Missing else Body.Article(clip.article(i)) }
        var cancelled = 0
        listOf(1, 2, 5, 13, 34, 89, 233, 610).forEach { writes ->
            path.delete()
            val out = RecordingChannel(channel())
            val records = CopyOnWriteArrayList<Record>()
            lateinit var write: Job
            out.onWrite = { count -> if (count == writes) write.cancel() }
            write = launch {
                writer(recordBytes = MIB).write(ID, clip.nzb, video, out, 0, emptyList()) { offset, gaps ->
                    assertTrue(offset <= out.forced, "recorded $offset with ${out.forced} forced, cancelled after $writes writes")
                    records += Record(offset, gaps)
                }
            }
            withTimeout(60_000) { write.join() }
            out.close()
            // Each record's offset is data on storage, and the gaps it names before it are all of them.
            records.forEach { r ->
                assertTrue(r.offset <= out.forced, "after $writes writes")
                if (r.offset > bytesOf(12).first) assertEquals(listOf(bytesOf(12)), r.gaps, "after $writes writes")
            }
            assertTrue(write.isCancelled || records.lastOrNull()?.offset == size, "after $writes writes")
            if (write.isCancelled) cancelled++
        }
        assertTrue(cancelled >= 6, "only $cancelled writes were cancelled before their end")
    }

    @Test
    fun `a write cancelled while it waits to reopen records what it had, and ends`() = runTest(timeout = 60.seconds) {
        var opens = 0
        val records = mutableListOf<Long>()
        val out = RecordingChannel(channel())
        val write = launch {
            writer(
                StreamOpener { _, _, range ->
                    opens++
                    if (opens == 1) OpenedStream(ByteReadChannel(clip.data.copyOfRange(0, (2 * part).toInt())), Job()) else refused()
                },
                time = testScheduler.timeSource,
            ).write(ID, clip.nzb, video, out, 0, emptyList()) { offset, _ ->
                assertTrue(offset <= out.forced)
                records += offset
            }
        }
        testScheduler.advanceTimeBy(30.seconds)
        write.cancel()
        write.join()
        assertTrue(write.isCancelled)
        assertEquals(2 * part, records.last())
        assertEquals(2 * part, out.forced)
    }

    @Test
    fun `a record every 10 s when its bytes come slower than 32 MiB in that time`() = runBlocking<Unit> {
        // The server's throttle moves the writer's clock as its answers go: 256 KiB a second on each connection.
        val clock = ServerClock()
        serve(throttle = 256L * 1024, throttleWait = { _, nanos -> clock.pass(nanos) })
        val at = CopyOnWriteArrayList<Long>()
        val wrote = withTimeout(60_000) { writer(time = clock).wrote(recorded = { at += clock.nanos.get() }) }
        assertEquals(Outcome.Done(emptyList()), wrote.outcome)
        // The clip's 8 MiB take about 32 s of the clock: a record at each 10 s, and one at the end.
        assertTrue(wrote.records.size in 3..5, "records at ${at.map { it / 1_000_000_000.0 }} s")
        (listOf(0L) + at.dropLast(1)).zipWithNext().forEach { (before, then) ->
            assertTrue(then - before >= 10.seconds.inWholeNanoseconds, "records at ${at.map { it / 1_000_000_000.0 }} s")
        }
        assertContentEquals(clip.data, path.readBytes())
    }

    @Test
    fun `the debug throttle holds it to its bytes a second, which its rate over 5 s says`() = runTest(timeout = 60.seconds) {
        serve()
        lateinit var writer: DownloadWriter
        val rates = mutableListOf<Long>()
        writer = writer(time = testScheduler.timeSource, throttle = 2 * MIB, recordBytes = MIB)
        val wrote = writer.wrote(recorded = { r -> if (r.offset >= 3 * MIB && r.offset < size) rates += writer.bytesPerSecond() })
        assertEquals(Outcome.Done(emptyList()), wrote.outcome)
        // 8 MiB at 2 MiB a second, on virtual time.
        assertTrue(currentTime in 3_900L..4_100L, "took ${currentTime} ms")
        assertTrue(rates.isNotEmpty() && rates.all { it in (2 * MIB * 85 / 100)..(2 * MIB * 115 / 100) }, "rates $rates")
        assertContentEquals(clip.data, path.readBytes())
        // Without it, no wait at all.
        val before = currentTime
        assertEquals(Outcome.Done(emptyList()), writer(time = testScheduler.timeSource).wrote().outcome)
        assertEquals(before, currentTime)
    }

    @Test
    fun `the rate is the bytes of the last 5 s a second, of the time since the first byte while that is shorter`() {
        val time = TestTimeSource()
        val rate = TransferRate(time)
        assertEquals(0, rate.bytesPerSecond())
        rate.add(1_000_000)
        assertEquals(1_000_000, rate.bytesPerSecond())
        repeat(10) {
            time += 1.seconds
            rate.add(1_000_000)
        }
        assertEquals(1_000_000, rate.bytesPerSecond())
        time += 2.seconds
        assertEquals(600_000, rate.bytesPerSecond())
        time += 1.seconds
        rate.add(5_000_000)
        assertEquals(1_400_000, rate.bytesPerSecond())
        time += 10.seconds
        assertEquals(0, rate.bytesPerSecond())
    }

    /** What [block] logs: slf4j-simple writes to System.err as it is at the time. */
    private suspend fun <T> stderrOf(block: suspend () -> T): Pair<T, String> {
        val original = System.err
        val captured = ByteArrayOutputStream()
        System.setErr(PrintStream(captured, true))
        try {
            return block() to captured.toString()
        } finally {
            System.setErr(original)
        }
    }

    /** Opens through [real], or, while [refusing] says, fails at once as a provider that refuses connections. */
    private class Refusing(private val real: StreamOpener, private val refusing: () -> Boolean) : StreamOpener {
        val refused = AtomicInteger()

        override suspend fun open(nzb: PreparedNzb, fileIndex: Int, range: LongRange): OpenedStream =
            if (refusing()) refused().also { this.refused.incrementAndGet() } else real.open(nzb, fileIndex, range)
    }

    /** A clock the server's throttle moves ([FakeNntpServer]'s throttleWait), from any of its threads. */
    private class ServerClock : AbstractLongTimeSource(DurationUnit.NANOSECONDS) {
        val nanos = AtomicLong()

        fun pass(by: Long) {
            nanos.addAndGet(by)
        }

        override fun read(): Long = nanos.get()
    }

    /**
     * A file channel over [real] that says what the writer did to it, in order ([events]), and how far the file is written
     * ([written]) and was at the last force ([forced]): the writer writes in order from its first byte. A write reaching
     * byte [fullAt] fails there as a full volume does, in [full]'s words, the bytes in front of it written.
     */
    private class RecordingChannel(
        private val real: FileChannel,
        private val fullAt: Long? = null,
        private val full: String = "No space left on device",
    ) : FileChannel() {
        sealed interface Event

        data class Wrote(val end: Long) : Event

        data object Forced : Event

        data class Recorded(val offset: Long) : Event

        val events = CopyOnWriteArrayList<Event>()

        @Volatile
        var written = 0L
            private set

        @Volatile
        var forced = 0L
            private set

        private val writes = AtomicInteger()

        /** Called after each write with how many there have been. */
        @Volatile
        var onWrite: (Int) -> Unit = {}

        override fun write(src: ByteBuffer, position: Long): Int {
            check(position <= written) { "a write at $position with only $written written" }
            val end = position + src.remaining()
            val n = if (fullAt != null && fullAt in position until end) {
                if (position == fullAt) throw IOException(full)
                real.write(src.duplicate().apply { limit(position() + (fullAt - position).toInt()) }, position).also { src.position(src.position() + it) }
            } else {
                real.write(src, position)
            }
            if (position + n > written) written = position + n
            events += Wrote(position + n)
            onWrite(writes.incrementAndGet())
            return n
        }

        override fun force(metaData: Boolean) {
            real.force(metaData)
            forced = written
            events += Forced
        }

        override fun read(dst: ByteBuffer): Int = real.read(dst)
        override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = real.read(dsts, offset, length)
        override fun write(src: ByteBuffer): Int = throw UnsupportedOperationException("the writer writes at offsets")
        override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = throw UnsupportedOperationException("the writer writes at offsets")
        override fun position(): Long = real.position()
        override fun position(newPosition: Long): FileChannel = apply { real.position(newPosition) }
        override fun size(): Long = real.size()
        override fun truncate(size: Long): FileChannel = apply { real.truncate(size) }
        override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = real.transferTo(position, count, target)
        override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = real.transferFrom(src, position, count)
        override fun read(dst: ByteBuffer, position: Long): Int = real.read(dst, position)
        override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer = real.map(mode, position, size)
        override fun lock(position: Long, size: Long, shared: Boolean): FileLock = real.lock(position, size, shared)
        override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? = real.tryLock(position, size, shared)
        override fun implCloseChannel() = real.close()
    }

    private companion object {
        const val ID = "dl-7Qx2"
        const val MIB = 1024L * 1024

        /** Invented hosts, as a provider's and its certificate's names. */
        const val HOST = "news.harbor-lights.invalid"
        const val OTHER_HOST = "cdn.copper-crown.invalid"

        /** A stream that ends at once with [cause], before any byte. */
        fun failed(cause: Throwable) = OpenedStream(ByteChannel().apply { cancel(cause) }, Job())

        /** A stream from a provider that refuses connections. */
        fun refused() = failed(UsenetException(UsenetError.ConnectionFailed("Could not connect to $HOST:563: Connection refused")))
    }
}
