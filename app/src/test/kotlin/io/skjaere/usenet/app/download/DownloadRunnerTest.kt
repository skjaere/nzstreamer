package io.skjaere.usenet.app.download

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.NzbMetadataResponse
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.stream.StreamableFile
import io.skjaere.usenet.app.download.DownloadResolver.Resolution
import io.skjaere.usenet.core.DownloadWriter
import io.skjaere.usenet.core.FileCandidate
import io.skjaere.usenet.core.OpenedStream
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.ProviderConfig
import io.skjaere.usenet.core.StreamGaps
import io.skjaere.usenet.core.StreamOpener
import io.skjaere.usenet.core.UsenetError
import io.skjaere.usenet.core.UsenetException
import io.skjaere.usenet.data.Clock
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadDao
import io.skjaere.usenet.data.DownloadGap
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.releases.ReleaseKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The downloads' runner (item 7, L335, L337, L340, L342, L346, L359, L379-L381, L388): the queue one at a time in Room's
 * order, over fakes of the table, the places on the device and Resolve, and the real writer (item 6) over a fake opener
 * that serves each release's bytes, holds a stream part way, fills what no provider has with zeros, or fails. Resolve's
 * fake answers as DownloadResolver does: a release opened again from the download's own NZB where it has one, else the
 * next candidate not tried, at most 3 across its resolves, its offset kept for the release its file came from. Time is
 * virtual. The episodes are Harbor Lights'; releases are invented.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadRunnerTest {
    @TempDir
    lateinit var dir: File

    /**
     * A release as the fake indexer and providers have it: its [title] and file's bytes, whether it is its release's one
     * video ([oneVideo]; a pack's isn't), and how its streams go: [missing] stretches no provider has (filled with zeros
     * and said), a [hold] before which a stream waits for [gate], a failure ([fails]) at a byte, and opens [refused]
     * before any byte (by their order, from 0).
     */
    private inner class Fake(
        val guid: String,
        val title: String,
        size: Int = SIZE,
        val oneVideo: Boolean = true,
        val password: String? = null,
    ) {
        val data: ByteArray = Random(guid.hashCode()).nextBytes(size)
        val size: Long get() = data.size.toLong()
        val fileName = "$title.mkv"
        val nzb = "<nzb><!-- $guid --></nzb>".encodeToByteArray()
        val prepared = PreparedNzb(
            PreparedNzb.idFor(nzb), title,
            ExtractedMetadata.Raw(NzbMetadataResponse(volumes = listOf(fileName), obfuscated = false, entries = emptyList()), NzbDocument(emptyList())),
            listOf(FileCandidate(0, StreamableFile(fileName, size.toLong(), 0, 0, 0, 0))),
        )
        val file: FileCandidate get() = prepared.candidates.single()
        val candidate get() = DownloadCandidate(INDEXER, guid, title, size = size, passworded = password != null)
        val key: String get() = ReleaseKey.of(INDEXER, guid)

        var missing: List<LongRange> = emptyList()
        var hold: Long? = null
        val gate = CompletableDeferred<Unit>()
        var fails: Pair<Long, UsenetError>? = null
        var refused: Set<Int> = emptySet()

        /** Each stream's first byte, in order. */
        val opens = mutableListOf<Long>()

        fun open(range: LongRange): OpenedStream {
            val index = opens.size
            opens += range.first
            if (index in refused) {
                return OpenedStream(ByteChannel().apply { cancel(UsenetException(UsenetError.ConnectionFailed("refused"))) }, Job())
            }
            val channel = ByteChannel()
            val gaps = StreamGaps()
            val job = work.launch {
                var at = range.first
                val end = range.last + 1
                try {
                    while (at < end) {
                        hold?.let { if (at >= it && !gate.isCompleted) gate.await() }
                        fails?.let { (offset, error) ->
                            if (at >= offset) {
                                channel.cancel(UsenetException(error))
                                return@launch
                            }
                        }
                        val stop = listOfNotNull(end, at + CHUNK, hold?.takeIf { it > at && !gate.isCompleted }, fails?.first?.takeIf { it > at }).min()
                        val chunk = data.copyOfRange(at.toInt(), stop.toInt())
                        // Said before its zeros come, as the engine's streams say them.
                        missing.forEach { gap ->
                            val from = maxOf(gap.first, at)
                            val to = minOf(gap.last, stop - 1)
                            if (from <= to) {
                                gaps.add(from..to)
                                chunk.fill(0, (from - at).toInt(), (to - at + 1).toInt())
                            }
                        }
                        channel.writeFully(chunk)
                        channel.flush()
                        at = stop
                    }
                    channel.flushAndClose()
                } catch (e: CancellationException) {
                    channel.cancel(e)
                    throw e
                }
            }
            return OpenedStream(channel, job, gaps)
        }
    }

    private val fakes = mutableMapOf<String, Fake>()

    private fun fake(guid: String, size: Int = SIZE, oneVideo: Boolean = true, password: String? = null) =
        Fake(guid, "Harbor.Lights.S02E04.1080p.WEB.h264-${guid.uppercase().replace("-", "")}", size, oneVideo, password).also { fakes[guid] = it }

    /**
     * Where the runner, its hosts and the streams' fake providers run: the test's own, so its virtual time runs them all
     * (the background's tasks are left out of advanceUntilIdle), and cancelled at the test's end, flows and held streams
     * with it.
     */
    private lateinit var work: CoroutineScope

    private fun test(body: suspend TestScope.() -> Unit) = runTest(timeout = 60.seconds) {
        val job = Job(coroutineContext[Job])
        work = CoroutineScope(coroutineContext + job)
        try {
            body()
        } finally {
            job.cancel()
        }
    }

    private val opener = StreamOpener { nzb, _, range -> fakes.values.first { it.title == nzb.title }.open(range) }

    private val dao = FakeDao()
    private val storage by lazy { FakeStorage() }
    private val resolver = FakeResolver()

    private var host = DownloadHost.Job
    private val switches = mutableListOf<Boolean>()

    /** The releases remembered as missing, by guid, with the ids of the providers asked. */
    private val recorded = mutableListOf<Pair<String, List<String>>>()
    private val providers = listOf(ProviderConfig("provider-1", "Harbor news", "news.harbor-lights.invalid", 563, "", "", useTls = true))

    private var clock = Clock { NOW }

    /** Whether Android calls the network validated, as the runner and its writer read it. */
    private val network = MutableStateFlow(true)
    private val traced get() = File(dir, "debug/${DownloadDebug.TRACE}").takeIf { it.isFile }?.readLines().orEmpty()

    /** The runner of these fakes, in the test's work, on its virtual time. */
    private fun TestScope.runner(television: Boolean = false, exit: DownloadExit? = null): DownloadRunner {
        clock = Clock { NOW + testScheduler.currentTime }
        val writer = DownloadWriter(opener, network, timeSource = testScheduler.timeSource, longGapBytes = LONG_GAP)
        return DownloadRunner(
            DownloadRunnerDeps(
                downloads = dao, storage = storage, resolve = resolver::resolve, writer = writer,
                missing = { release, asked -> recorded += release.guid to asked.map { it.id } },
                inUse = { providers }, lastExit = { exit }, television = television, host = { host }, receiverSwitch = { switches += it },
                debug = DownloadDebug.On(File(dir, "debug"), clock), network = network, clock = clock, timeSource = testScheduler.timeSource,
                io = StandardTestDispatcher(testScheduler), undoMillis = DownloadRunner.UNDO_MILLIS,
            ),
            work,
        )
    }

    /** A host lending [runner] its lifetime: it walks the queue until nothing can run. */
    private fun host(runner: DownloadRunner): Job = work.launch { runner.run(host) }

    /** Harbor Lights' episode [episode] of season 2. */
    private fun target(episode: Int) = DownloadTarget.episode(SHOW, "Harbor Lights", 2, episode, seasonEpisodes = 8, episodeName = "Low Water")

    private suspend fun DownloadRunner.add(episode: Int, vararg releases: Fake): String =
        assertNotNull(enqueue(target(episode), releases.map { it.candidate })).id

    /** A row put in the table as a test needs it, with its marker, as a download the sheet queued and then went on. */
    private suspend fun row(id: String, episode: Int, position: Long, vararg releases: Fake, edit: (Download) -> Download = { it }): Download {
        val row = Download(
            id = id, watchKey = WatchKey.episode(SHOW, 2, episode), kind = DownloadKind.Episode, title = "Harbor Lights", showTmdbId = SHOW,
            season = 2, episode = episode, position = position, candidates = releases.map { it.candidate }, indexerSize = releases.firstOrNull()?.size ?: 0,
            createdAt = NOW, updatedAt = NOW,
        ).let(edit)
        storage.add(row)
        return row
    }

    private suspend fun stored(id: String): Download = assertNotNull(dao.get(id), "download $id")

    private fun order() = dao.rows.value.values.filter { it.state != DownloadState.Done && it.state != DownloadState.Deleting }
        .sortedWith(compareBy(Download::position, Download::createdAt)).map { it.id }

    @Test
    fun `one at a time, in position order, each resolved when the one before is done`() = test {
        val (a, b, c) = listOf(fake("hl-a"), fake("hl-b"), fake("hl-c"))
        // Put in out of order: the queue goes by position.
        row(C, 3, 2, c)
        row(A, 1, 0, a)
        row(B, 2, 1, b)
        a.hold = HOLD
        val runner = runner()
        val walk = host(runner)
        runCurrent()
        assertEquals(listOf(A), resolver.calls)
        assertEquals(listOf(0L), a.opens)
        assertTrue(b.opens.isEmpty() && c.opens.isEmpty())
        assertEquals(DownloadState.Running, stored(A).state)
        assertEquals(DownloadState.Queued, stored(B).state)

        a.gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(walk.isCompleted, "the walk ends once nothing can run")
        assertEquals(listOf(A, B, C), resolver.calls)
        // Each resolved once the one before was finished, never two written at once.
        assertEquals(listOf("open $A", "finish $A", "open $B", "finish $B", "open $C", "finish $C"), storage.events.filter { it.startsWith("open") || it.startsWith("finish") })
        listOf(A to a, B to b, C to c).forEach { (id, fake) ->
            assertEquals(DownloadState.Done, stored(id).state)
            assertContentEquals(fake.data, storage.video(id).readBytes())
        }
    }

    @Test
    fun `pause holds the queue and keeps the running one's offset, resume goes on from it, and a Stopped one holds it too`() = test {
        val a = fake("hl-a")
        val b = fake("hl-b")
        a.hold = HOLD
        val runner = runner()
        val first = runner.add(1, a)
        val second = runner.add(2, b)
        val walk = host(runner)
        runCurrent()
        assertEquals(HOLD, runner.state.value.first { it.row.id == first }.written)

        runner.pause()
        runCurrent()
        val paused = stored(first)
        assertEquals(DownloadState.Paused, paused.state)
        // At its safe offset, its file and room kept.
        assertEquals(HOLD, paused.safeOffset)
        assertNotNull(paused.relativePath)
        assertEquals(a.size, storage.part(first).length())
        assertTrue(walk.isCompleted, "the walk ends, and its host")
        assertEquals(DownloadState.Queued, stored(second).state)
        assertTrue(b.opens.isEmpty())

        // Held: a host started again finds nothing to run.
        host(runner).join()
        assertEquals(listOf(first), resolver.calls)

        runner.resume()
        a.gate.complete(Unit)
        host(runner)
        advanceUntilIdle()
        // Back at the head, opened again from its own NZB with no second grab, on from where it stopped; then the next.
        assertEquals(listOf(0L, HOLD), a.opens)
        assertEquals(listOf(first), resolver.reopened)
        assertEquals(listOf(a.guid, b.guid), resolver.grabbed)
        assertEquals(DownloadState.Done, stored(first).state)
        assertContentEquals(a.data, storage.video(first).readBytes())
        assertEquals(DownloadState.Done, stored(second).state)

        // Stopped by Android holds the queue as Pause does.
        val c = fake("hl-c")
        row(STOPPED, 3, 10, c) { it.copy(state = DownloadState.Stopped, reason = DownloadHostRules.STOPPED_BY_ANDROID) }
        row(QUEUED, 4, 11, c)
        host(runner).join()
        assertTrue(c.opens.isEmpty())
        assertEquals(DownloadState.Queued, stored(QUEUED).state)
        assertEquals(emptyList(), runner.runnable())
    }

    @Test
    fun `a cancel of the running one stops it and deletes what it wrote, its NZB first, and the next starts`() = test {
        val a = fake("hl-a", password = "s3cret")
        val b = fake("hl-b")
        a.hold = HOLD
        val runner = runner()
        val first = runner.add(1, a)
        val second = runner.add(2, b)
        val walk = host(runner)
        runCurrent()
        assertTrue(storage.part(first).isFile)
        assertTrue(File(storage.bookkeeping(first), "nzb").isFile)

        runner.cancel(first)
        advanceUntilIdle()
        assertNull(dao.get(first))
        assertFalse(storage.videoFolder(first).exists())
        assertFalse(storage.bookkeeping(first).exists())
        assertEquals(listOf("drop source $first", "delete $first"), storage.events.filter { it == "drop source $first" || it == "delete $first" })
        assertEquals(DownloadState.Done, stored(second).state)
        assertTrue(walk.isCompleted)
    }

    @Test
    fun `remove hides at once and deletes after Undo's time, and Undo brings one back as it was`() = test {
        val d = fake("hl-d")
        val a = fake("hl-a")
        val runner = runner()
        val finished = runner.add(1, d)
        host(runner)
        advanceUntilIdle()
        assertEquals(setOf(WatchKey.episode(SHOW, 2, 1)), runner.done.value.keys)
        a.hold = HOLD
        val running = runner.add(2, a)
        val walk = host(runner)
        runCurrent()

        runner.remove(listOf(finished, running))
        runCurrent()
        // Hidden at once, the running one stopped; their files still there while Undo lasts.
        assertEquals(emptyList(), runner.state.value)
        assertEquals(emptyMap(), runner.done.value)
        assertTrue(walk.isCompleted)
        assertTrue(storage.video(finished).isFile)
        assertTrue(storage.part(running).isFile)
        advanceTimeBy(DownloadRunner.UNDO_MILLIS - 1)
        runCurrent()
        assertEquals(DownloadState.Deleting, stored(finished).state)
        assertEquals(DownloadState.Deleting, stored(running).state)
        advanceTimeBy(2)
        runCurrent()
        assertNull(dao.get(finished))
        assertNull(dao.get(running))
        assertFalse(storage.videoFolder(finished).exists())
        assertFalse(storage.bookkeeping(running).exists())

        // Undo: the finished one Done again, the stopped one queued again, which the walk still under way takes up from
        // its offset; nothing deleted after.
        val e = fake("hl-e")
        val f = fake("hl-f")
        val again = runner.add(3, e)
        host(runner)
        advanceUntilIdle()
        f.hold = HOLD
        val stopped = runner.add(4, f)
        host(runner)
        runCurrent()
        runner.remove(listOf(again, stopped))
        runner.undoRemove(listOf(again, stopped))
        advanceTimeBy(DownloadRunner.UNDO_MILLIS + 1)
        runCurrent()
        assertEquals(DownloadState.Done, stored(again).state)
        assertTrue(storage.video(again).isFile)
        assertEquals(setOf(WatchKey.episode(SHOW, 2, 3)), runner.done.value.keys)
        assertEquals(DownloadState.Running, stored(stopped).state)
        assertEquals(listOf(0L, HOLD), f.opens)
        assertTrue(storage.part(stopped).isFile)
    }

    @Test
    fun `move takes a queued one up and down past its queued neighbours`() = test {
        val runner = runner()
        val (q1, q2, q3) = (1..3).map { runner.add(it, fake("hl-q$it")) }
        runner.move(q3, up = true)
        assertEquals(listOf(q1, q3, q2), order())
        runner.move(q1, up = true)
        assertEquals(listOf(q1, q3, q2), order())
        runner.move(q1, up = false)
        assertEquals(listOf(q3, q1, q2), order())
        runner.move(q2, up = false)
        assertEquals(listOf(q3, q1, q2), order())

        // The running one keeps its place: a queued one goes no further up than the queued.
        fakes.getValue("hl-q3").hold = HOLD
        host(runner)
        runCurrent()
        assertEquals(DownloadState.Running, stored(q3).state)
        runner.move(q1, up = true)
        assertEquals(listOf(q3, q1, q2), order())
        runner.move(q2, up = true)
        assertEquals(listOf(q3, q2, q1), order())
    }

    @Test
    fun `replace of the running one deletes its file and gives its room back, in its place, the new releases resolved`() = test {
        val a = fake("hl-a")
        val x = fake("hl-x", size = SIZE / 2)
        val b = fake("hl-b")
        a.hold = HOLD
        val runner = runner()
        val id = runner.add(1, a)
        val second = runner.add(2, b)
        host(runner)
        runCurrent()
        val position = stored(id).position

        runner.replace(id, listOf(x.candidate))
        runCurrent()
        val replaced = stored(id)
        assertEquals(position, replaced.position)
        assertTrue("delete video $id" in storage.events)
        advanceUntilIdle()
        val done = stored(id)
        assertEquals(DownloadState.Done, done.state)
        assertEquals(x.guid, done.release?.guid)
        assertEquals(listOf(x.key), done.tried)
        assertEquals(listOf(0L), x.opens)
        // Its old file gone before the new one was made: the new is the new release's alone, at its size.
        assertContentEquals(x.data, storage.video(id).readBytes())
        assertTrue(storage.events.indexOf("delete video $id") < storage.events.lastIndexOf("open $id"))
        // The new release before the one queued behind it.
        assertEquals(listOf(id, id, second), resolver.calls)
    }

    /**
     * The file goes only once the row no longer names it, its NZB gone first, whatever stops the call as the file is
     * deleted (a Pause, the host's end, the sheet's scope closing): a row left naming a deleted file at its offset would
     * reopen its release into a new file of zeros and finish it with its first bytes zeros.
     */
    @Test
    fun `a replace or a give-up stopped as the file is deleted leaves the row naming no file and no NZB`() = test {
        val a = fake("hl-a").apply { hold = HOLD }
        val x = fake("hl-x")
        val runner = runner()
        val id = runner.add(1, a)
        host(runner)
        runCurrent()
        val deleting = CompletableDeferred<Unit>()
        val letGo = CompletableDeferred<Unit>()
        storage.afterDeleteVideo = {
            deleting.complete(Unit)
            letGo.await()
        }
        val replace = work.launch { runner.replace(id, listOf(x.candidate)) }
        runCurrent()
        assertTrue(deleting.isCompleted)
        // The sheet's scope closes as the file goes.
        replace.cancel()
        letGo.complete(Unit)
        replace.join()
        storage.afterDeleteVideo = {}
        val replaced = stored(id)
        assertNull(replaced.release)
        assertNull(replaced.relativePath)
        assertEquals(0L, replaced.safeOffset)
        assertEquals(listOf(x.candidate), replaced.candidates)
        assertNull(storage.source(id))
        // The new release from its first byte, its file its own.
        a.gate.complete(Unit)
        host(runner)
        advanceUntilIdle()
        assertEquals(DownloadState.Done, stored(id).state)
        assertContentEquals(x.data, storage.video(id).readBytes())

        // A release given up as it is written, the walk paused as its file goes.
        val gap = fake("hl-gap").apply { missing = listOf(SIZE / 2L until SIZE) }
        val next = fake("hl-next")
        val second = runner.add(2, gap, next)
        val stopping = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        storage.afterDeleteVideo = {
            stopping.complete(Unit)
            release.await()
        }
        host(runner)
        advanceUntilIdle()
        assertTrue(stopping.isCompleted)
        val pause = work.launch { runner.pause() }
        runCurrent()
        release.complete(Unit)
        pause.join()
        storage.afterDeleteVideo = {}
        val paused = stored(second)
        assertEquals(DownloadState.Paused, paused.state)
        assertNull(paused.release)
        assertNull(paused.relativePath)
        assertEquals(0L, paused.safeOffset)
        assertNull(storage.source(second))
        runner.resume()
        host(runner)
        advanceUntilIdle()
        // The next release, never the one given up opened again from its copy.
        assertEquals(DownloadState.Done, stored(second).state)
        assertEquals(next.guid, stored(second).release?.guid)
        assertContentEquals(next.data, storage.video(second).readBytes())
    }

    @Test
    fun `retry of a failed one resets its tries and goes on from its offset with the same release, else from 0`() = test {
        val r = fake("hl-r")
        val runner = runner()
        // Failed as "Storage is full" part way through: its file holds its first bytes.
        failedPartWay(SAME, 1, r, r)
        runner.retry(SAME)
        assertEquals(DownloadState.Queued, stored(SAME).state)
        assertEquals(emptyList(), stored(SAME).tried)
        host(runner)
        advanceUntilIdle()
        assertEquals(listOf(HOLD), r.opens)
        assertEquals(DownloadState.Done, stored(SAME).state)
        assertContentEquals(r.data, storage.video(SAME).readBytes())

        // The release its file came from is gone from its candidates: another starts from 0.
        val gone = fake("hl-gone")
        val n = fake("hl-n")
        failedPartWay(OTHER, 2, gone, n)
        runner.retry(OTHER)
        host(runner)
        advanceUntilIdle()
        assertEquals(listOf(0L), n.opens)
        assertEquals(DownloadState.Done, stored(OTHER).state)
        assertContentEquals(n.data, storage.video(OTHER).readBytes())
    }

    /** Row [id], made from [made] and failed with its first [HOLD] bytes written, to be tried again from [candidates]. */
    private suspend fun failedPartWay(id: String, episode: Int, made: Fake, vararg candidates: Fake) {
        row(id, episode, episode.toLong(), *candidates)
        storage.open(id, made.fileName, made.size).use { it.write(ByteBuffer.wrap(made.data, 0, HOLD.toInt()), 0) }
        dao.update(
            stored(id).copy(
                state = DownloadState.Failed, reason = DownloadWriter.STORAGE_FULL, tried = listOf(made.key), fileName = made.fileName, size = made.size,
                release = RememberedRelease(INDEXER, made.guid, made.title, null, 0, made.prepared.id, made.fileName, made.size),
                safeOffset = HOLD, relativePath = storage.relativePath(id, made.fileName),
            ),
        )
    }

    @Test
    fun `a release given up as it is written moves to the next, at most 3, each one's file and NZB gone first`() = test {
        val four = (1..4).map { i -> fake("hl-gap-$i").apply { missing = listOf(SIZE / 2L until SIZE) } }
        val runner = runner()
        val id = runner.add(1, *four.toTypedArray())
        val walk = host(runner)
        advanceUntilIdle()
        assertTrue(walk.isCompleted)
        four.take(3).forEach { assertEquals(listOf(0L), it.opens, it.guid) }
        assertTrue(four[3].opens.isEmpty())
        assertEquals(four.take(3).map { it.guid }, resolver.grabbed)
        // Never opened again from a copy of the one given up.
        assertEquals(emptyList(), resolver.reopened)
        val failed = stored(id)
        assertEquals(DownloadState.Failed, failed.state)
        assertEquals(DownloadResolver.INCOMPLETE, failed.reason)
        assertEquals(four.take(3).map { it.key }, failed.tried)
        assertNull(storage.source(id))
        assertFalse(storage.videoFolder(id).exists())
        // Each given up: its NZB dropped, then its file deleted, before the next is resolved; then the failure's drop.
        val given = listOf("keep source $id", "drop source $id", "delete video $id")
        assertEquals(given + given + given + "drop source $id", storage.events.filter { it in given })

        // A damaged one goes to the next too.
        val damaged = fake("hl-damaged").apply { fails = SIZE / 4L to UsenetError.Corrupt("bad yEnc") }
        val whole = fake("hl-whole")
        val next = runner.add(2, damaged, whole)
        host(runner)
        advanceUntilIdle()
        assertEquals(DownloadState.Done, stored(next).state)
        assertEquals(whole.guid, stored(next).release?.guid)
        assertContentEquals(whole.data, storage.video(next).readBytes())
    }

    @Test
    fun `a release of one video found missing is remembered with its providers, a pack's and a damaged one's aren't`() = test {
        val single = fake("hl-single").apply { missing = listOf(SIZE / 2L until SIZE) }
        val pack = fake("hl-pack", oneVideo = false).apply { missing = listOf(SIZE / 2L until SIZE) }
        val damaged = fake("hl-damaged").apply { fails = SIZE / 4L to UsenetError.Corrupt("bad yEnc") }
        val whole = fake("hl-whole")
        val runner = runner()
        runner.add(1, single, pack, whole)
        host(runner)
        advanceUntilIdle()
        assertEquals(listOf(single.guid to listOf("provider-1")), recorded)
        runner.add(2, damaged, fake("hl-other"))
        host(runner)
        advanceUntilIdle()
        assertEquals(listOf(single.guid to listOf("provider-1")), recorded)
    }

    @Test
    fun `the NZB and its password go on Done, Failed and Cancel, the marker kept while the row is`() = test {
        val runner = runner()
        val done = runner.add(1, fake("hl-done", password = "s3cret"))
        host(runner)
        advanceUntilIdle()
        assertEquals(DownloadState.Done, stored(done).state)
        assertTrue(File(storage.bookkeeping(done), DownloadStorage.MARKER).isFile)
        assertFalse(File(storage.bookkeeping(done), "nzb").exists())
        assertFalse(File(storage.bookkeeping(done), "password").exists())

        // Six reopens that reached the provider and got nothing: Failed, its file and record kept for Try again.
        val garbled = fake("hl-garbled", password = "s3cret").apply { fails = 0L to UsenetError.Protocol("garbled") }
        val failed = runner.add(2, garbled)
        host(runner)
        advanceUntilIdle()
        assertEquals(DownloadState.Failed, stored(failed).state)
        assertEquals(DownloadWriter.NO_PROGRESS, stored(failed).reason)
        assertEquals(7, garbled.opens.size)
        assertTrue(File(storage.bookkeeping(failed), DownloadStorage.MARKER).isFile)
        assertFalse(File(storage.bookkeeping(failed), "nzb").exists())
        assertFalse(File(storage.bookkeeping(failed), "password").exists())
        assertTrue(storage.part(failed).isFile)

        // A cancel takes the NZB and its password first, then the rest of it with its row.
        val held = fake("hl-held", password = "s3cret").apply { hold = HOLD }
        val cancelled = runner.add(3, held)
        host(runner)
        runCurrent()
        assertTrue(File(storage.bookkeeping(cancelled), "password").isFile)
        runner.cancel(cancelled)
        runCurrent()
        val steps = storage.events.filter { it.endsWith(cancelled) }
        assertTrue(steps.indexOf("drop source $cancelled") < steps.indexOf("delete $cancelled"), steps.toString())
        assertNull(dao.get(cancelled))
    }

    @Test
    fun `reconcile stops a row left running only when the user stopped the app after its last update`() = test {
        assertEquals(android.app.ApplicationExitInfo.REASON_USER_REQUESTED, DownloadExit.USER_REQUESTED)
        val r = fake("hl-r")
        suspend fun left() {
            dao.rows.value = emptyMap()
            row(RUNNING, 1, 0, r) { it.copy(state = DownloadState.Running, updatedAt = AT) }
            row(RESOLVING, 2, 1, r) { it.copy(state = DownloadState.Resolving, updatedAt = AT) }
            row(QUEUED, 3, 2, r)
        }

        left()
        runner(exit = DownloadExit(DownloadExit.USER_REQUESTED, AT + 1)).reconcile()
        assertEquals("reconcile", storage.events.first())
        assertEquals(DownloadState.Stopped, stored(RUNNING).state)
        assertEquals(DownloadHostRules.STOPPED_BY_ANDROID, stored(RUNNING).reason)
        assertEquals(DownloadState.Stopped, stored(RESOLVING).state)
        assertEquals(DownloadState.Queued, stored(QUEUED).state)
        assertTrue(traced.any { it.endsWith("reconcile id=$RUNNING decision=stopped exit=10 exit-at=${AT + 1}") }, traced.toString())

        // A stop before its last update was another process's, before a reboot: left for its host, as any other end is.
        listOf(DownloadExit(DownloadExit.USER_REQUESTED, AT - 1), DownloadExit(android.app.ApplicationExitInfo.REASON_SIGNALED, AT + 1), null).forEach { exit ->
            left()
            runner(exit = exit).reconcile()
            assertEquals(DownloadState.Running, stored(RUNNING).state, "$exit")
            assertEquals(DownloadState.Resolving, stored(RESOLVING).state, "$exit")
        }
        assertTrue(traced.any { it.endsWith("reconcile id=$RUNNING decision=left exit=none") }, traced.toString())
        assertEquals(4, storage.reconciles)
    }

    @Test
    fun `the waits for the providers and for storage and a held queue start nothing, where a need's wait runs`() = test {
        val r = fake("hl-r")
        row(PROVIDER, 1, 0, r) { it.copy(state = DownloadState.Waiting, reason = DownloadHostRules.CHECK_PROVIDER) }
        row(REACH, 2, 1, r) { it.copy(state = DownloadState.Waiting, reason = DownloadHostRules.CANT_REACH_PROVIDER) }
        row(VOLUME, 3, 2, r) { it.copy(state = DownloadState.Waiting, reason = DownloadHostRules.STORAGE_UNAVAILABLE) }
        row(FAILED, 4, 3, r) { it.copy(state = DownloadState.Failed, reason = DownloadResolver.WONT_FIT) }
        storage.there = false
        val runner = runner()
        host(runner).join()
        assertEquals(emptyList(), resolver.calls)
        assertEquals(emptyList(), runner.runnable())
        // The volume back: its wait can run again.
        storage.there = true
        assertEquals(listOf(VOLUME), runner.runnable().map { it.id })

        // A wait for Wi-Fi is one the job's constraints wait for: it runs once the host does.
        dao.rows.value = emptyMap()
        val wifi = fake("hl-wifi")
        row(WIFI, 5, 0, wifi) { it.copy(state = DownloadState.Waiting, reason = DownloadHostRules.WAITING_FOR_WIFI) }
        host(runner)
        advanceUntilIdle()
        assertEquals(DownloadState.Done, stored(WIFI).state)

        // A paused one holds those behind it.
        row(PAUSED, 6, 1, r) { it.copy(state = DownloadState.Paused) }
        row(QUEUED, 7, 2, r)
        host(runner).join()
        assertEquals(listOf(WIFI), resolver.calls)

        // A provider that refuses the login ends the walk, though another is queued; a provider saved queues it again.
        dao.rows.value = emptyMap()
        val refused = fake("hl-refused").apply { fails = 0L to UsenetError.AuthFailed("481") }
        val after = fake("hl-after")
        val first = runner.add(8, refused)
        val second = runner.add(9, after)
        val walk = host(runner)
        advanceUntilIdle()
        assertTrue(walk.isCompleted)
        assertEquals(DownloadState.Waiting, stored(first).state)
        assertEquals(DownloadHostRules.CHECK_PROVIDER, stored(first).reason)
        assertEquals(DownloadState.Queued, stored(second).state)
        assertTrue(after.opens.isEmpty())
        runner.retryNow()
        assertEquals(DownloadState.Queued, stored(first).state)
        assertNull(stored(first).reason)
    }

    @Test
    fun `the state says the running one's bytes and rate, and the rows say why they wait`() = test {
        val a = fake("hl-a").apply { hold = HOLD }
        val b = fake("hl-b")
        val runner = runner()
        val first = runner.add(1, a)
        val second = runner.add(2, b)
        resolver.scripted += Resolution.Unreachable(UsenetError.ConnectionFailed("refused"))
        host(runner)
        runCurrent()
        // Resolve found the providers out of reach: it says so while it waits to ask again.
        val resolving = runner.state.value.first { it.row.id == first }
        assertEquals(DownloadState.Resolving, resolving.row.state)
        assertEquals(DownloadHostRules.CANT_REACH_PROVIDER, DownloadHostRules.words(resolving.row)?.text)
        advanceTimeBy(DownloadWriter.FIRST_BACKOFF.inWholeMilliseconds + 1)
        runCurrent()
        val running = runner.state.value.first { it.row.id == first }
        assertEquals(HOLD, running.written)
        assertEquals(HOLD, running.bytesPerSecond)
        val words = assertNotNull(DownloadHostRules.words(running.row, running.written, running.bytesPerSecond, more = 1))
        assertTrue(words.text.startsWith("Downloading · "), words.text)
        assertTrue(words.text.endsWith(" min left"), words.text)
        assertEquals("1 more queued", words.subText)
        val queued = runner.state.value.first { it.row.id == second }
        assertEquals(0L, queued.written)
        assertNull(queued.bytesPerSecond)
        assertEquals("Queued", DownloadHostRules.words(queued.row)?.text)
        a.gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(setOf(WatchKey.episode(SHOW, 2, 1), WatchKey.episode(SHOW, 2, 2)), runner.done.value.keys)
        assertEquals(first, runner.done.value.getValue(WatchKey.episode(SHOW, 2, 1)).id)

        // The writer backing off from a provider out of reach: the row says so until bytes come again.
        val c = fake("hl-c").apply { refused = setOf(0) }
        val third = runner.add(3, c)
        host(runner)
        runCurrent()
        assertEquals(DownloadState.Running, stored(third).state)
        assertEquals(DownloadHostRules.CANT_REACH_PROVIDER, stored(third).reason)
        advanceUntilIdle()
        assertEquals(DownloadState.Done, stored(third).state)
        assertNull(stored(third).reason)

        // An hour of Resolve finding them out of reach: the download waits, and the walk ends (L388).
        repeat(40) { resolver.scripted += Resolution.Unreachable(UsenetError.ConnectionFailed("refused")) }
        val fourth = runner.add(4, fake("hl-d"))
        val walk = host(runner)
        advanceTimeBy(59.minutes.inWholeMilliseconds)
        runCurrent()
        assertFalse(walk.isCompleted)
        advanceUntilIdle()
        assertTrue(walk.isCompleted)
        assertEquals(DownloadState.Waiting, stored(fourth).state)
        assertEquals(DownloadHostRules.CANT_REACH_PROVIDER, stored(fourth).reason)
    }

    @Test
    fun `a retryNow or the network's return while Resolve asks ends the wait after it`() = test {
        val runner = runner()
        val id = runner.add(1, fake("hl-a"))
        repeat(4) { resolver.scripted += Resolution.Unreachable(UsenetError.ConnectionFailed("refused")) }
        resolver.during = { call ->
            when (call) {
                // Resume, or a provider saved, while Resolve's second try connects.
                2 -> runner.retryNow()
                // The network lost and back while its fourth does.
                4 -> {
                    network.value = false
                    kotlinx.coroutines.delay(100)
                    network.value = true
                }
            }
        }
        host(runner)
        advanceUntilIdle()
        // 2 s after the first; none after the second, the backoff starting again; 2 s; none after the fourth.
        assertEquals(listOf(0L, 2_000, 2_000, 4_000, 4_100), resolver.times)
        assertEquals(DownloadState.Done, stored(id).state)
    }

    @Test
    fun `the boot receiver is switched on while the host is the service and something can run`() = test {
        host = DownloadHost.Service
        val runner = runner()
        runCurrent()
        assertEquals(listOf(false), switches)
        runner.add(1, fake("hl-a"))
        runCurrent()
        assertEquals(listOf(false, true), switches)
        host(runner)
        advanceUntilIdle()
        assertEquals(listOf(false, true, false), switches)

        // The job's host never wakes at boot: JobScheduler keeps the job.
        switches.clear()
        host = DownloadHost.Job
        val jobs = runner()
        jobs.add(2, fake("hl-b"))
        runCurrent()
        assertEquals(listOf(false), switches)
    }

    @Test
    fun `nothing runs on a TV, where only reconcile does anything`() = test {
        host = DownloadHost.Service
        val tv = runner(television = true)
        val a = fake("hl-a")
        assertNull(tv.enqueue(target(1), listOf(a.candidate)))
        assertEquals(emptyMap(), dao.rows.value)
        // A test's row: nothing starts it there.
        row(QUEUED, 1, 0, a)
        tv.run(DownloadHost.Job)
        tv.pause()
        tv.resume()
        tv.retryNow()
        tv.move(QUEUED, up = false)
        tv.replace(QUEUED, listOf(fake("hl-x").candidate))
        tv.retry(QUEUED)
        tv.remove(listOf(QUEUED))
        tv.cancel(QUEUED)
        advanceUntilIdle()
        assertEquals(emptyList(), resolver.calls)
        assertEquals(DownloadState.Queued, stored(QUEUED).state)
        assertEquals(emptyList(), tv.runnable())
        assertEquals(emptyList(), tv.state.value)
        assertEquals(emptyMap(), tv.done.value)
        assertEquals(emptyList(), switches)
        tv.reconcile()
        assertEquals(1, storage.reconciles)
    }

    /** Resolve's fake, answering as DownloadResolver does, as the class says. */
    private inner class FakeResolver {
        val calls = mutableListOf<String>()
        val grabbed = mutableListOf<String>()
        val reopened = mutableListOf<String>()

        /** What the next resolves answer before any release is tried, in turn. */
        val scripted = ArrayDeque<Resolution>()

        /** When each resolve was asked for, from the test's start. */
        val times = mutableListOf<Long>()

        /** What happens while each resolve runs, by its number from 1: a Resume, the network lost and back. */
        var during: suspend (call: Int) -> Unit = {}

        suspend fun resolve(id: String): Resolution {
            calls += id
            times += clock.now() - NOW
            during(calls.size)
            val row = dao.get(id) ?: return Resolution.Gone
            scripted.removeFirstOrNull()?.let { said ->
                when (said) {
                    is Resolution.Unreachable -> dao.setState(id, DownloadState.Resolving, DownloadHostRules.CANT_REACH_PROVIDER, clock.now())
                    is Resolution.Waiting -> dao.setState(id, DownloadState.Waiting, said.reason, clock.now())
                    is Resolution.Failed -> dao.setState(id, DownloadState.Failed, said.reason, clock.now())
                    else -> Unit
                }
                return said
            }
            val source = storage.source(id)
            if (row.release != null && row.size != null && source != null) {
                reopened += id
                val fake = fakes.values.first { it.nzb.contentEquals(source.nzb) }
                return Resolution.Ready(row, fake.prepared, fake.file)
            }
            val left = DownloadResolver.MAX_TRIES - row.tried.size
            val candidate = row.candidates.firstOrNull { ReleaseKey.of(it.indexerId, it.guid) !in row.tried }
            if (left <= 0 || candidate == null) {
                dao.setState(id, DownloadState.Failed, DownloadResolver.NO_OTHER_RELEASE, clock.now())
                return Resolution.Failed(DownloadResolver.NO_OTHER_RELEASE)
            }
            val fake = fakes.getValue(candidate.guid)
            grabbed += fake.guid
            dao.setState(id, DownloadState.Resolving, DownloadHostRules.FINDING_RELEASES, clock.now())
            val counted = stored(id).copy(tried = row.tried + fake.key)
            val same = counted.size == fake.size && counted.release?.guid == fake.guid
            val base = if (same) {
                counted
            } else {
                if (counted.relativePath != null) storage.deleteVideo(id)
                counted.copy(safeOffset = 0, gaps = emptyList(), relativePath = null)
            }
            val resolved = base.copy(
                release = RememberedRelease(INDEXER, fake.guid, fake.title, null, 0, fake.prepared.id, fake.fileName, fake.size),
                fileName = fake.fileName, size = fake.size, oneVideo = fake.oneVideo, updatedAt = clock.now(),
            )
            dao.update(resolved)
            storage.keepSource(id, fake.nzb, fake.password)
            return Resolution.Ready(resolved, fake.prepared, fake.file)
        }
    }

    /** The downloads' table in memory, ordered as Room's queries order it. */
    private class FakeDao : DownloadDao {
        val rows = MutableStateFlow<Map<String, Download>>(emptyMap())

        private fun sorted(filter: (Download) -> Boolean): Flow<List<Download>> =
            rows.map { all -> all.values.filter(filter).sortedWith(compareBy(Download::position, Download::createdAt)) }

        override fun queue() = sorted { it.state != DownloadState.Done && it.state != DownloadState.Deleting }
        override fun all() = sorted { true }
        override suspend fun get(id: String) = rows.value[id]
        override fun done() = rows.map { all -> all.values.filter { it.state == DownloadState.Done }.sortedWith(compareBy({ it.completedAt }, { it.createdAt })) }
        override suspend fun doneFor(watchKey: String) = rows.value.values.filter { it.state == DownloadState.Done && it.watchKey == watchKey }.maxByOrNull { it.completedAt ?: 0 }
        override fun forShow(showTmdbId: Int) = sorted { it.showTmdbId == showTmdbId && it.state != DownloadState.Deleting }
        override fun forMovie(movieTmdbId: Int) = rows.map { all -> all.values.firstOrNull { it.movieTmdbId == movieTmdbId && it.state != DownloadState.Deleting } }
        override suspend fun insert(download: Download) = rows.update { it + (download.id to download) }
        override suspend fun update(download: Download) = rows.update { if (download.id in it) it + (download.id to download) else it }

        override suspend fun progress(id: String, safeOffset: Long, gaps: List<DownloadGap>, updatedAt: Long) =
            change(id) { it.copy(safeOffset = safeOffset, gaps = gaps, updatedAt = updatedAt) }

        override suspend fun setState(id: String, state: DownloadState, reason: String?, updatedAt: Long) =
            change(id) { it.copy(state = state, reason = reason, updatedAt = updatedAt) }

        override suspend fun move(id: String, position: Long) = change(id) { it.copy(position = position) }
        override suspend fun delete(id: String) = rows.update { it - id }

        private fun change(id: String, block: (Download) -> Download) = rows.update { all -> all[id]?.let { all + (id to block(it)) } ?: all }
    }

    /**
     * The downloads' places as DownloadStorage lays them out, in the test's folder: the bookkeeping (its marker, NZB and
     * password) under `files/`, the video under `volume/downloads/<id>/`, `.part` until finished. What each call did, in
     * order, goes in [events].
     */
    private inner class FakeStorage : DownloadRunner.Storage {
        private val files = File(dir, "files")
        private val volume = File(dir, "volume")
        var there = true
        var reconciles = 0
        val events = mutableListOf<String>()

        fun bookkeeping(id: String) = File(files, id)
        fun videoFolder(id: String) = File(volume, "downloads/$id")
        fun video(id: String) = File(videoFolder(id), "v.mkv")
        fun part(id: String) = File(videoFolder(id), "v.mkv.part")

        override suspend fun add(row: Download) {
            bookkeeping(row.id).mkdirs()
            File(bookkeeping(row.id), DownloadStorage.MARKER).createNewFile()
            dao.insert(row)
        }

        override suspend fun open(id: String, fileName: String, size: Long): FileChannel {
            if (!there) throw DownloadStorage.Unavailable()
            events += "open $id"
            videoFolder(id).mkdirs()
            if (video(id).isFile && !part(id).exists()) video(id).renameTo(part(id))
            val file = RandomAccessFile(part(id), "rw")
            // As allocateBytes does: the whole length, never less than the file has.
            if (file.length() < size) file.setLength(size)
            return file.channel
        }

        override suspend fun finish(id: String, fileName: String) {
            events += "finish $id"
            if (!part(id).renameTo(video(id))) throw IOException("Couldn't rename download $id's file")
        }

        override fun relativePath(id: String, fileName: String) = "downloads/$id/v.mkv"

        /** Called once a video is deleted, before the call returns: where a cancel finds a delete that has happened. */
        var afterDeleteVideo: suspend () -> Unit = {}

        override suspend fun deleteVideo(id: String) {
            if (!there) throw DownloadStorage.Unavailable()
            events += "delete video $id"
            videoFolder(id).deleteRecursively()
            afterDeleteVideo()
        }

        fun keepSource(id: String, nzb: ByteArray, password: String?) {
            events += "keep source $id"
            File(bookkeeping(id), "nzb").writeBytes(nzb)
            val file = File(bookkeeping(id), "password")
            if (password != null) file.writeText(password) else file.delete()
        }

        fun source(id: String): DownloadStorage.Source? {
            val nzb = File(bookkeeping(id), "nzb").takeIf { it.isFile } ?: return null
            return DownloadStorage.Source(nzb.readBytes(), File(bookkeeping(id), "password").takeIf { it.isFile }?.readText())
        }

        override suspend fun dropSource(id: String) {
            events += "drop source $id"
            File(bookkeeping(id), "nzb").delete()
            File(bookkeeping(id), "password").delete()
        }

        override suspend fun delete(id: String) {
            events += "delete $id"
            dao.setState(id, DownloadState.Deleting, null, clock.now())
            if (!there) return
            videoFolder(id).deleteRecursively()
            bookkeeping(id).deleteRecursively()
            dao.delete(id)
        }

        override suspend fun reconcile() {
            reconciles++
            events += "reconcile"
        }

        override fun volumeThere() = there
    }

    private companion object {
        const val INDEXER = "fake-indexer"
        const val SHOW = 900_002
        const val NOW = 1_800_000_000_000L
        const val AT = NOW - 60_000

        /** A release's file: 256 KiB, sent in 16 KiB pieces. */
        const val SIZE = 256 * 1024
        const val CHUNK = 16 * 1024L

        /** Where a held stream waits. */
        const val HOLD = 96 * 1024L

        /** A stretch missing in a row of this many bytes gives the release up, as the writer is given it small here. */
        const val LONG_GAP = 64 * 1024L

        const val A = "dl-a"
        const val B = "dl-b"
        const val C = "dl-c"
        const val SAME = "dl-same"
        const val OTHER = "dl-other"
        const val STOPPED = "dl-stopped"
        const val QUEUED = "dl-queued"
        const val RUNNING = "dl-running"
        const val RESOLVING = "dl-resolving"
        const val PROVIDER = "dl-provider"
        const val REACH = "dl-reach"
        const val VOLUME = "dl-volume"
        const val FAILED = "dl-failed"
        const val WIFI = "dl-wifi"
        const val PAUSED = "dl-paused"
    }
}
