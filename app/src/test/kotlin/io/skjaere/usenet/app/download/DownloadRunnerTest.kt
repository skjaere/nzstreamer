package io.skjaere.usenet.app.download

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.NzbMetadataResponse
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.stream.StreamableFile
import io.skjaere.usenet.app.download.DownloadResolver.Resolution
import io.skjaere.usenet.app.play.IMPORTED
import io.skjaere.usenet.app.play.MissingReleases
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
import io.skjaere.usenet.data.DownloadPrefs
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.ReleaseChecks
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchedDownload
import io.skjaere.usenet.logging.LogBuffer
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.releases.ReleaseKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import kotlin.test.assertFailsWith
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
        val indexer: String = INDEXER,
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
        val candidate get() = DownloadCandidate(indexer, guid, title, size = size, passworded = password != null)
        val key: String get() = ReleaseKey.of(indexer, guid)

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

    private fun fake(guid: String, size: Int = SIZE, oneVideo: Boolean = true, password: String? = null, indexer: String = INDEXER) =
        Fake(guid, "Harbor.Lights.S02E04.1080p.WEB.h264-${guid.uppercase().replace("-", "")}", size, oneVideo, password, indexer).also { fakes[guid] = it }

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
    /** One, with no name: a line naming it would name its host. */
    private val providers = listOf(ProviderConfig("provider-1", "", "news.harbor-lights.invalid", 563, "", "", useTls = true))

    private var clock = Clock { NOW }

    /** Whether Android calls the network validated: the one flow the deps give the runner and its writer, as the app's container does. */
    private val network = MutableStateFlow(true)

    /** The finished downloads whose titles are marked watched, as the library's join gives them (L412), and Settings › Downloads. */
    private val watched = MutableStateFlow<List<WatchedDownload>>(emptyList())
    private val prefs = MutableStateFlow(DownloadPrefs())
    private val traced get() = File(dir, "debug/${DownloadDebug.TRACE}").takeIf { it.isFile }?.readLines().orEmpty()

    /**
     * The runner of these fakes, in the test's work, on its virtual time: its writer over the fake opener, made by the deps as
     * the app's are; the releases found missing [recorded], unless the deps are given how to remember them ([missing]).
     */
    /** The walk's memory of the indexers' limits (L443), which the runner's Resume forgets. */
    private val limits = DownloadLimits()

    private fun TestScope.runner(
        television: Boolean = false,
        exit: DownloadExit? = null,
        missing: suspend (RememberedRelease, List<ProviderConfig>, String) -> Unit = { release, asked, _ -> recorded += release.guid to asked.map { it.id } },
    ): DownloadRunner {
        clock = Clock { NOW + testScheduler.currentTime }
        return DownloadRunner(
            DownloadRunnerDeps(
                downloads = dao, storage = storage, resolve = resolver::resolve, opener = opener,
                missing = missing,
                inUse = { providers }, lastExit = { exit }, television = television, host = { host }, network = network,
                receiverSwitch = { switches += it }, debug = DownloadDebug.On(File(dir, "debug"), clock), longGapBytes = LONG_GAP, clock = clock,
                timeSource = testScheduler.timeSource, io = StandardTestDispatcher(testScheduler), undoMillis = DownloadRunner.UNDO_MILLIS, limits = limits,
                watched = watched, prefs = prefs,
            ),
            work,
        )
    }

    /** When download [id]'s streams opened, from the test's start: each open's trace line, timed by the test's clock. */
    private fun openedAt(id: String): List<Long> = traced.filter { " reopen id=$id " in it }.map { it.substringBefore(' ').toLong() - NOW }

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
        // The debug build's trace, which the device pairs wait on (item 25): each stream's first byte, its progress as it is
        // recorded, and its end, in that order.
        listOf(A to a, B to b, C to c).forEach { (id, fake) ->
            val mine = traced.map { it.substringAfter(' ') }.filter { "id=$id " in it }
            assertEquals("reopen id=$id offset=0", mine.first(), mine.toString())
            assertEquals("progress id=$id offset=${fake.size} size=${fake.size}", mine[mine.size - 2], mine.toString())
            assertEquals("done id=$id size=${fake.size}", mine.last(), mine.toString())
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

    /**
     * Pause holds the whole queue until Resume (L380), whatever is done to the row that holds it: Choose another release
     * keeps it paused, and Cancel or Remove hands the hold to the next that would run. So does Stopped by Android's.
     */
    @Test
    fun `a replace keeps the held row held, and a cancel or remove of it hands the hold to the next`() = test {
        val a = fake("hl-a").apply { hold = HOLD }
        val runner = runner()
        val first = runner.add(1, a)
        val second = runner.add(2, fake("hl-b"))
        val third = runner.add(3, fake("hl-c"))
        host(runner)
        runCurrent()
        runner.pause()
        runCurrent()
        assertEquals(DownloadState.Paused, stored(first).state)

        runner.replace(first, listOf(fake("hl-x").candidate))
        assertEquals(DownloadState.Paused, stored(first).state)
        assertEquals(emptyList(), runner.runnable())

        runner.cancel(first)
        assertEquals(DownloadState.Paused, stored(second).state)
        assertEquals(emptyList(), runner.runnable())

        runner.remove(listOf(second))
        assertEquals(DownloadState.Paused, stored(third).state)
        assertEquals(emptyList(), runner.runnable())
        host(runner).join()
        assertEquals(listOf(first), resolver.calls)

        // Undo brings the removed one back, held too: Resume lets both go, in their order.
        runner.undoRemove(listOf(second))
        runner.resume()
        assertEquals(listOf(second, third), runner.runnable().map { it.id })

        // Stopped by Android's hold goes the same way, with its words.
        dao.rows.value = emptyMap()
        val c = fake("hl-c2")
        row(STOPPED, 4, 10, c) { it.copy(state = DownloadState.Stopped, reason = DownloadHostRules.STOPPED_BY_ANDROID) }
        row(QUEUED, 5, 11, c)
        runner.cancel(STOPPED)
        assertEquals(DownloadState.Stopped, stored(QUEUED).state)
        assertEquals(DownloadHostRules.STOPPED_BY_ANDROID, stored(QUEUED).reason)
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
    fun `a remove given the Undo bar's own time keeps what it removed that long`() = test {
        val d = fake("hl-d")
        val runner = runner()
        val finished = runner.add(1, d)
        host(runner)
        advanceUntilIdle()
        // TalkBack's longer bar (L379): its files stay until it has gone.
        runner.remove(listOf(finished), keepFor = 3 * DownloadRunner.UNDO_MILLIS)
        advanceTimeBy(DownloadRunner.UNDO_MILLIS + 1)
        runCurrent()
        assertEquals(DownloadState.Deleting, stored(finished).state)
        assertTrue(storage.video(finished).isFile)
        advanceTimeBy(2 * DownloadRunner.UNDO_MILLIS)
        runCurrent()
        assertNull(dao.get(finished))
        assertFalse(storage.videoFolder(finished).exists())
    }

    /**
     * L413: a download held while it plays keeps its file. Removed, it is hidden at once, and once its Undo's time is over its
     * files stay until its last hold ends; then they go, row and all. One owner holding one id twice is one hold, let go of
     * once. A hold that ends while the Undo can still bring it back leaves it to its timer, as a removal is.
     */
    @Test
    fun `a held download's removal deletes nothing until its last hold ends, then deletes it`() = test {
        val runner = runner()
        val first = runner.add(1, fake("hl-a"))
        val second = runner.add(2, fake("hl-b"))
        host(runner)
        advanceUntilIdle()
        val player = "player:lease"
        val launch = Any()
        runner.hold(player, first)
        runner.hold(player, first)
        runner.hold(launch, first)
        assertEquals(setOf(first), runner.held.value)

        runner.remove(listOf(first))
        advanceTimeBy(DownloadRunner.UNDO_MILLIS + 1)
        runCurrent()
        assertEquals(DownloadState.Deleting, stored(first).state)
        assertTrue(storage.video(first).isFile, "its file, while it plays")
        assertTrue(storage.bookkeeping(first).isDirectory)
        assertFalse("delete $first" in storage.events)
        // The launch's hold ends: the player's still holds it.
        runner.release(launch, first)
        runCurrent()
        assertTrue(storage.video(first).isFile)
        // The player's, held twice by one owner, ends with one release: then it goes, in the crash-safe order.
        runner.release(player, first)
        runCurrent()
        assertEquals(emptySet(), runner.held.value)
        assertNull(dao.get(first))
        assertFalse(storage.videoFolder(first).exists())
        assertFalse(storage.bookkeeping(first).exists())

        // A hold that ends while the Undo can still bring it back: its timer deletes it, as it would have.
        runner.hold(player, second)
        runner.remove(listOf(second))
        runCurrent()
        runner.release(player)
        runCurrent()
        assertEquals(DownloadState.Deleting, stored(second).state)
        assertTrue(storage.video(second).isFile)
        advanceTimeBy(DownloadRunner.UNDO_MILLIS + 1)
        runCurrent()
        assertNull(dao.get(second))
        assertFalse(storage.videoFolder(second).exists())
    }

    /**
     * L413: Delete all deletes every download but those held: one playing is hidden at once, its NZB dropped and its file
     * kept, and its closing reconcile is told it is held, so it leaves it (DownloadStorage's reconcile deletes every row
     * being deleted). Its files go once its last hold ends.
     */
    @Test
    fun `delete all leaves a held download's files until its hold ends, its reconcile told it is held`() = test {
        val runner = runner()
        val playing = runner.add(1, fake("hl-a"))
        val other = runner.add(2, fake("hl-b"))
        host(runner)
        advanceUntilIdle()
        val queued = runner.add(3, fake("hl-c"))
        val player = "player:lease"
        runner.hold(player, playing)
        storage.reconciledHeld = null

        runner.deleteAll()
        advanceUntilIdle()
        assertEquals(DownloadState.Deleting, stored(playing).state)
        assertTrue(storage.video(playing).isFile, "its file, while it plays")
        assertTrue("drop source $playing" in storage.events)
        assertNull(dao.get(other))
        assertNull(dao.get(queued))
        val held = assertNotNull(storage.reconciledHeld, "Delete all's reconcile")
        assertTrue(held(playing))
        assertFalse(held(other))

        runner.release(player)
        runCurrent()
        assertNull(dao.get(playing))
        assertFalse(storage.videoFolder(playing).exists())
        assertFalse(storage.bookkeeping(playing).exists())
    }

    /**
     * L413: a player's lease is one owner across its launches: as another launch comes to the open player, its hold becomes
     * the new launch's download in one step ([DownloadRunner.holdOnly]). A different download is held before the one before
     * is let go, so the held ids never pass through none, and the one before, removed, goes then; the same one launched
     * again is never let go.
     */
    @Test
    fun `holdOnly hands an owner's hold to another download with no gap, and to the one it holds keeps it`() = test {
        val runner = runner()
        val first = runner.add(1, fake("hl-a"))
        val second = runner.add(2, fake("hl-b"))
        host(runner)
        advanceUntilIdle()
        val seen = mutableListOf<Set<String>>()
        work.launch(UnconfinedTestDispatcher(testScheduler)) { runner.held.collect { seen += it } }
        val lease = "player:lease"
        runner.holdOnly(lease, first)
        runner.remove(listOf(first))
        advanceTimeBy(DownloadRunner.UNDO_MILLIS + 1)
        runCurrent()
        assertEquals(DownloadState.Deleting, stored(first).state)

        // The same download launched again: never let go.
        runner.holdOnly(lease, first)
        runCurrent()
        assertTrue(storage.video(first).isFile)
        assertEquals(listOf(emptySet(), setOf(first)), seen)

        // Another: held before the one before is let go, which goes then.
        runner.holdOnly(lease, second)
        runCurrent()
        assertEquals(listOf(emptySet(), setOf(first), setOf(second)), seen)
        assertNull(dao.get(first))
        assertFalse(storage.videoFolder(first).exists())
        assertEquals(DownloadState.Done, stored(second).state)
        runner.release(lease)
        runCurrent()
        assertEquals(emptySet(), runner.held.value)
    }

    /**
     * Settings › Downloads' Delete all (item 20, L379), which the page asks about first: every download goes at once, the
     * running one stopped first and its NZB dropped before the rest of it, each file, bookkeeping and row in the crash-safe
     * order, those removed and waiting for their Undo's end with them (their timers then find nothing, and an Undo brings
     * nothing back); then reconcile, for a folder left without a row. The walk ends, with nothing left to run.
     */
    @Test
    fun `delete all stops the running one and deletes every download, those waiting for their Undo's end too`() = test {
        val d = fake("hl-d")
        val e = fake("hl-e")
        val a = fake("hl-a", password = "s3cret")
        val b = fake("hl-b")
        val runner = runner()
        val finished = runner.add(1, d)
        val removed = runner.add(2, e)
        host(runner)
        advanceUntilIdle()
        runner.remove(listOf(removed))
        a.hold = HOLD
        val running = runner.add(3, a)
        val queued = runner.add(4, b)
        val walk = host(runner)
        runCurrent()
        assertTrue(storage.part(running).isFile)
        assertTrue(File(storage.bookkeeping(running), "nzb").isFile)
        val reconciles = storage.reconciles

        runner.deleteAll()
        runCurrent()
        assertEquals(emptyMap(), dao.rows.value)
        listOf(finished, removed, running, queued).forEach { id ->
            assertFalse(storage.videoFolder(id).exists(), "$id's video")
            assertFalse(storage.bookkeeping(id).exists(), "$id's bookkeeping")
        }
        assertEquals(listOf("drop source $running", "delete $running"), storage.events.filter { it == "drop source $running" || it == "delete $running" })
        assertEquals(reconciles + 1, storage.reconciles, "reconciled after")
        assertTrue(walk.isCompleted)
        assertEquals(emptyList(), runner.state.value)
        assertEquals(emptyMap(), runner.done.value)
        assertEquals(emptyList(), b.opens, "the queued one never started")

        runner.undoRemove(listOf(removed))
        advanceTimeBy(DownloadRunner.UNDO_MILLIS + 1)
        runCurrent()
        assertNull(dao.get(removed))
        assertEquals(1, storage.events.count { it == "delete $removed" }, "deleted once, by Delete all")
    }

    /**
     * Whether a live walk will take up what is queued now, for the app's schedule (L335): from a walk's start until its
     * last look finds nothing to run, false in that same step, before its host can end. A Download or Resume that comes as
     * the walk ends is scheduled, never left with no host; one while it runs is taken up by it.
     */
    @Test
    fun `the runner says whether a live walk will take up what is queued now`() = test {
        val runner = runner()
        val seen = mutableListOf<Boolean>()
        work.launch { runner.walking.collect { seen += it } }
        fun schedules() = DownloadHostRules.schedules(
            DownloadHostRules.ScheduleCause.Resume, television = false, visible = true, runnable = true, walking = runner.walking.value, pending = DownloadHostRules.JobPending(),
        )
        runCurrent()
        assertFalse(runner.walking.value)
        val a = fake("hl-a").apply { hold = HOLD }
        val first = runner.add(1, a)
        val walk = host(runner)
        runCurrent()
        assertTrue(runner.walking.value)
        // Queued as it runs: the walk takes it up, nothing to schedule.
        val second = runner.add(2, fake("hl-b"))
        assertFalse(schedules())
        a.gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(walk.isCompleted)
        assertEquals(DownloadState.Done, stored(second).state)
        assertEquals(listOf(false, true, false), seen)

        // Paused: the walk ends with it, and Resume schedules a host again.
        val c = fake("hl-c").apply { hold = HOLD }
        val third = runner.add(3, c)
        host(runner)
        runCurrent()
        assertTrue(runner.walking.value)
        runner.pause()
        runCurrent()
        assertFalse(runner.walking.value)
        runner.resume()
        assertTrue(schedules())
        assertEquals(listOf(third), runner.runnable().map { it.id })
        assertEquals(DownloadState.Done, stored(first).state)
    }

    /**
     * A host's walk says when it has taken the queue up ([DownloadRunner.run]'s taken): once [DownloadRunner.walking] says
     * whether it will take up what is queued, under the queue's lock. Until then the job reads as waiting to run, so the
     * app's schedule never stops a job whose walk hasn't begun (L335, C5).
     */
    @Test
    fun `a walk says it has taken the queue up once walking says whether it will`() = test {
        val runner = runner()
        val seen = mutableListOf<Boolean>()
        val a = fake("hl-a").apply { hold = HOLD }
        runner.add(1, a)
        val walk = work.launch { runner.run(host) { seen += runner.walking.value } }
        runCurrent()
        assertEquals(listOf(true), seen)
        a.gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(walk.isCompleted)
        // Once only: the walk's later looks at the queue say nothing more.
        assertEquals(listOf(true), seen)

        // Nothing to take up: said all the same, walking false, so the ask after it schedules.
        work.launch { runner.run(host) { seen += runner.walking.value } }
        advanceUntilIdle()
        assertEquals(listOf(true, false), seen)
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
    fun `a title with a download still to finish is never queued twice, a finished or failed one's is queued anew`() = test {
        val runner = runner()
        val a = fake("hl-a")
        val b = fake("hl-b")
        val first = runner.add(4, a)
        // A second Download of it (a sheet restored with its page, a second tap meanwhile): the one queued, no other row.
        assertEquals(first, runner.add(4, b))
        assertEquals(listOf(first), order())
        assertEquals(listOf(a.candidate), stored(first).candidates, "its releases as they were")
        // Held, waiting or under way, it is still the one.
        listOf(DownloadState.Paused, DownloadState.Waiting, DownloadState.Running, DownloadState.Stopped).forEach { state ->
            dao.setState(first, state, null, NOW)
            assertEquals(first, runner.add(4, b), "$state")
        }
        // Failed: Download again is the row's own, but a new Download of the title queues one of its own.
        dao.setState(first, DownloadState.Failed, null, NOW)
        val again = runner.add(4, b)
        assertTrue(again != first)
        assertEquals(2, dao.rows.value.size)
    }

    @Test
    fun `asked to, a second download of a title still to finish is queued beside it, as the pairs' seeding queues its two`() = test {
        val runner = runner()
        val a = fake("hl-a")
        val b = fake("hl-b")
        val first = runner.add(4, a)
        // The pairs' seeding (L367): two rows of its one fixture episode, the first resolved and Queued again.
        val second = assertNotNull(runner.enqueue(target(4), listOf(b.candidate), onePerTitle = false)).id
        assertTrue(second != first, "a row of its own")
        assertEquals(listOf(first, second), order())
        assertEquals(listOf(b.candidate), stored(second).candidates)
        // A user's Download of the title still has the first.
        assertEquals(first, runner.add(4, b))
        assertEquals(2, dao.rows.value.size)
    }

    @Test
    fun `a download counts what the sheet reckoned it needs until it is resolved, its releases keeping the indexer's sizes`() = test {
        val runner = runner()
        // A season pack: the sheet reckons this episode's share of it; the pack's own size is what Resolve ranks by.
        val pack = fake("hl-pack")
        val id = assertNotNull(runner.enqueue(target(4), listOf(pack.candidate), need = 1_400)).id
        assertEquals(1_400, stored(id).indexerSize)
        assertEquals(listOf(SIZE.toLong()), stored(id).candidates.map { it.size })
        // Another release chosen in its place: what that one needs.
        runner.replace(id, listOf(fake("hl-x").candidate, pack.candidate), need = 1_200)
        assertEquals(1_200, stored(id).indexerSize)
        // Given nothing, its first release's size, as the indexer says it.
        assertEquals(SIZE.toLong(), stored(runner.add(5, fake("hl-b"))).indexerSize)
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

    /** Harbor Lights' search facts for episode [episode] of season 2, as the season's sheet keeps them (L392). */
    private fun facts(episode: Int, searched: Boolean = false) = SeasonSearch(
        "Harbor Lights", 2021, tvdb = 700_002, tmdb = SHOW, airDate = "2023-03-%02d".format(episode), runtimeMinutes = 44, seasonEpisodes = 8,
        searched = searched,
    )

    /** Episode [episode] of a season queued at once, from [releases], with its search facts. */
    private fun item(episode: Int, vararg releases: Fake) = DownloadRunner.QueueItem(target(episode), releases.map { it.candidate }, search = facts(episode))

    /**
     * L392: a season's episodes queued at once take consecutive places at the queue's end, in episode order whatever order
     * they come in, each with its releases, what the first needs and its search facts, none of them searched yet.
     */
    @Test
    fun `a season is queued at the queue's end in episode order, at consecutive places, each with its search facts`() = test {
        val runner = runner()
        row(QUEUED, 8, 1, fake("hl-q"))
        row(A, 7, 4, fake("hl-a")) { it.copy(state = DownloadState.Done, completedAt = NOW) }
        val (x, y, z) = listOf(fake("hl-x"), fake("hl-y"), fake("hl-z"))
        val queued = runner.enqueueAll(
            listOf(
                item(3, z),
                DownloadRunner.QueueItem(target(1), listOf(x.candidate, y.candidate), need = 1_400, search = facts(1, searched = true)),
                item(2),
            ),
        )
        assertEquals(listOf(1, 2, 3), queued.map { it.episode })
        assertEquals(queued, queued.map { stored(it.id) })
        assertEquals(listOf(5L, 6L, 7L), queued.map { it.position })
        assertEquals(listOf(QUEUED) + queued.map { it.id }, order())
        assertEquals(listOf(listOf(x.candidate, y.candidate), emptyList(), listOf(z.candidate)), queued.map { it.candidates })
        assertEquals(listOf(1_400L, 0L, z.size), queued.map { it.indexerSize })
        assertEquals((1..3).map { facts(it) }, queued.map { DownloadColumns.search(it) })
        queued.forEach { row ->
            assertEquals(DownloadState.Queued, row.state)
            assertTrue(File(storage.bookkeeping(row.id), DownloadStorage.MARKER).isFile, "its marker")
        }
    }

    @Test
    fun `a download with no release to try yet is queued only with its search facts`() = test {
        val runner = runner()
        // One of a season without them: none of the season is queued.
        assertFailsWith<IllegalArgumentException> { runner.enqueueAll(listOf(item(1, fake("hl-a")), DownloadRunner.QueueItem(target(2), emptyList()))) }
        assertFailsWith<IllegalArgumentException> { runner.enqueue(target(3), emptyList()) }
        assertEquals(emptyMap(), dao.rows.value)
        assertEquals(emptySet(), storage.folders())

        // With them, its turn searches (L392).
        val one = assertNotNull(runner.enqueue(target(3), emptyList(), search = facts(3, searched = true)))
        assertEquals(emptyList(), stored(one.id).candidates)
        assertEquals(0L, stored(one.id).indexerSize)
        assertEquals(facts(3), DownloadColumns.search(stored(one.id)))
        assertEquals(listOf(emptyList<DownloadCandidate>()), runner.enqueueAll(listOf(item(4))).map { stored(it.id).candidates })
    }

    /**
     * L396: a season queued again leaves an episode still to finish or downloaded as it is, and puts back the row of one
     * that failed, never a second: queued anew in the season's order from its new releases, its tries, reason, pick and
     * limit's wait gone, its search facts the new ones, not yet searched, and what it is of as the sheet has it now (TMDB's
     * names, pictures, runtime and season's count may have come since it was first queued), queued now. The release and
     * file it failed with stay, as Try again keeps them.
     */
    @Test
    fun `an episode still to finish or downloaded keeps its row, and a failed one's row is put back in the season's order`() = test {
        val runner = runner()
        val old = fake("hl-old")
        row(QUEUED, 1, 0, old) { it.copy(state = DownloadState.Waiting, reason = DownloadHostRules.CANT_REACH_PROVIDER) }
        row(A, 3, 1, old) { it.copy(state = DownloadState.Done, completedAt = NOW) }
        val written = RememberedRelease(INDEXER, old.guid, old.title, null, 0, old.prepared.id, old.fileName, old.size)
        row(FAILED, 2, 2, old) {
            it.copy(
                state = DownloadState.Failed, reason = DownloadResolver.NO_OTHER_RELEASE, tried = listOf(old.key), search = DownloadColumns.text(facts(2, searched = true)),
                pick = DownloadColumns.text(DownloadPick(1, old.fileName, old.size, old.prepared.id)),
                limitWait = DownloadColumns.text(LimitWait(INDEXER, LimitWait.Kind.Download, NOW)),
                // Queued from its menu early in the season, as TMDB had it then.
                seasonEpisodes = 3, createdAt = AT, release = written, fileName = old.fileName, size = old.size, safeOffset = CHUNK,
                relativePath = storage.relativePath(FAILED, old.fileName),
            )
        }
        val still = stored(QUEUED)
        val done = stored(A)
        val (n1, n2, n3, n4) = listOf(fake("hl-n1"), fake("hl-n2"), fake("hl-n3"), fake("hl-n4"))
        val now = target(2).copy(subtitle = "S02E02 · Low Water", runtimeMinutes = 44, posterPath = "/hl-poster.jpg", stillPath = "/hl-s02e02.jpg")

        val queued = runner.enqueueAll(listOf(item(1, n1), DownloadRunner.QueueItem(now, listOf(n2.candidate), search = facts(2)), item(3, n3), item(4, n4)))
        assertEquals(listOf(2, 4), queued.map { it.episode })
        assertEquals(FAILED, queued.first().id)
        assertEquals(still, stored(QUEUED))
        assertEquals(done, stored(A))
        assertEquals(4, dao.rows.value.size, "one row an episode")
        val back = stored(FAILED)
        assertEquals(DownloadState.Queued, back.state)
        assertNull(back.reason)
        assertEquals(emptyList(), back.tried)
        assertEquals(listOf(n2.candidate), back.candidates)
        assertEquals(n2.size, back.indexerSize)
        assertEquals(facts(2), DownloadColumns.search(back))
        assertNull(back.pick)
        assertNull(back.limitWait)
        assertEquals(listOf(3L, 4L), queued.map { stored(it.id).position })
        assertEquals(listOf(QUEUED, FAILED, queued[1].id), order())
        assertEquals(
            listOf(now.title, now.subtitle, now.seasonEpisodes, now.episodeName, now.year, now.runtimeMinutes, now.posterPath, now.stillPath),
            listOf(back.title, back.subtitle, back.seasonEpisodes, back.episodeName, back.year, back.runtimeMinutes, back.posterPath, back.stillPath),
        )
        assertEquals(NOW, back.createdAt, "queued now")
        assertEquals(listOf(written, old.fileName, old.size, CHUNK, storage.relativePath(FAILED, old.fileName)), listOf(back.release, back.fileName, back.size, back.safeOffset, back.relativePath))
    }

    /**
     * L396: a failed row put back never opens again the release it failed with from the NZB a process death left beside it
     * (one that died after the row failed and before its NZB went). That NZB, and one kept for an attempt, go as the row is
     * put back, as Try again drops them, so its turn resolves the season's new releases.
     */
    @Test
    fun `a failed row put back has the NZB a process death left with it dropped, as Try again drops it`() = test {
        val runner = runner()
        val old = fake("hl-old")
        row(FAILED, 2, 0, old) {
            it.copy(
                state = DownloadState.Failed, reason = DownloadWriter.STORAGE_FULL, tried = listOf(old.key),
                release = RememberedRelease(INDEXER, old.guid, old.title, null, 0, old.prepared.id, old.fileName, old.size), fileName = old.fileName, size = old.size,
            )
        }
        storage.keepSource(FAILED, old.nzb, "s3cret")
        storage.keepGrab(FAILED, DownloadStorage.Grab(old.key, DownloadStorage.Source(old.nzb, "s3cret")))
        val fresh = fake("hl-fresh")

        assertEquals(listOf(FAILED), runner.enqueueAll(listOf(item(2, fresh))).map { it.id })
        assertNull(storage.source(FAILED))
        assertNull(storage.grab(FAILED))
        host(runner)
        advanceUntilIdle()
        assertEquals(emptyList(), resolver.reopened)
        assertEquals(listOf(fresh.guid), resolver.grabbed)
        assertEquals(DownloadState.Done, stored(FAILED).state)
        assertEquals(fresh.guid, stored(FAILED).release?.guid)
    }

    @Test
    fun `a season is queued all or none, an insert that fails leaving no row, no folder and no row put back`() = test {
        val runner = runner()
        val old = fake("hl-old")
        row(FAILED, 2, 0, old) { it.copy(state = DownloadState.Failed, reason = DownloadResolver.NO_OTHER_RELEASE, tried = listOf(old.key)) }
        val failed = stored(FAILED)
        val season = listOf(item(1, fake("hl-a")), item(2, fake("hl-b")), item(3, fake("hl-c")))
        dao.full = setOf("insertAll")
        assertFailsWith<android.database.sqlite.SQLiteFullException> { runner.enqueueAll(season) }
        assertEquals(mapOf(FAILED to failed), dao.rows.value)
        assertEquals(setOf(FAILED), storage.folders())

        // The failed row's put-back refused: written in the same transaction as the new rows, none of those either.
        dao.full = setOf("update")
        assertFailsWith<android.database.sqlite.SQLiteFullException> { runner.enqueueAll(season) }
        assertEquals(mapOf(FAILED to failed), dao.rows.value)
        assertEquals(setOf(FAILED), storage.folders())

        dao.full = emptySet()
        assertEquals(listOf(1, 2, 3), runner.enqueueAll(season).map { it.episode })
        assertEquals(3, dao.rows.value.size)
        assertEquals(DownloadState.Queued, stored(FAILED).state)
    }

    /** L396, L398: Try again searches a season row's episode afresh; one without search facts, or with a file chosen by hand, keeps its releases. */
    @Test
    fun `retry of a season row clears its releases to search again, one with a pick or no search facts keeps them`() = test {
        val runner = runner()
        val a = fake("hl-a")
        val failed = { row: Download -> row.copy(state = DownloadState.Failed, reason = DownloadResolver.NO_OTHER_RELEASE, tried = listOf(a.key)) }
        row(FAILED, 2, 0, a) { failed(it).copy(search = DownloadColumns.text(facts(2, searched = true))) }
        runner.retry(FAILED)
        val again = stored(FAILED)
        assertEquals(DownloadState.Queued, again.state)
        assertEquals(emptyList(), again.tried)
        assertEquals(emptyList(), again.candidates)
        assertEquals(facts(2), DownloadColumns.search(again))

        row(OTHER, 3, 1, a) { failed(it) }
        runner.retry(OTHER)
        assertEquals(listOf(a.candidate), stored(OTHER).candidates)
        assertNull(stored(OTHER).search)

        val chosen = DownloadPick(1, a.fileName, a.size, a.prepared.id)
        row(SAME, 4, 2, a) { failed(it).copy(search = DownloadColumns.text(facts(4, searched = true)), pick = DownloadColumns.text(chosen)) }
        runner.retry(SAME)
        assertEquals(DownloadState.Queued, stored(SAME).state)
        assertEquals(listOf(a.candidate), stored(SAME).candidates)
        assertEquals(chosen, DownloadColumns.pick(stored(SAME)))
    }

    /** L403: a file chosen by hand is queued with its pick, its NZB and password kept beside the marker before the row is there. */
    @Test
    fun `a file chosen by hand is queued with its pick, its NZB there before its row`() = test {
        val runner = runner()
        val pack = fake("hl-pack", oneVideo = false)
        val chosen = DownloadPick(3, "Harbor.Lights.S02E04.1080p.WEB.h264-PACK.mkv", 1_412_345, pack.prepared.id)
        val grab = DownloadStorage.Grab(pack.key, DownloadStorage.Source(pack.nzb, "s3cret"))
        val seen = mutableListOf<String?>()
        dao.onInsert = { row -> seen += storage.grab(row.id)?.key }
        val id = assertNotNull(runner.enqueue(target(4), listOf(pack.candidate, fake("hl-b").candidate), need = chosen.size, pick = chosen, grab = grab)).id
        assertEquals(listOf<String?>(pack.key), seen)
        assertEquals(chosen, DownloadColumns.pick(stored(id)))
        assertEquals(chosen.size, stored(id).indexerSize)
        val kept = assertNotNull(storage.grab(id))
        assertContentEquals(pack.nzb, kept.source.nzb)
        assertEquals("s3cret", kept.source.password)
        // An NZB of a release the row doesn't list would never be tried: the caller's mistake.
        assertFailsWith<IllegalArgumentException> { runner.enqueue(target(5), listOf(fake("hl-c").candidate), grab = grab) }
    }

    /** L403, L435: Choose file from the Downloads page replaces a row's release with its pick at once; a release chosen by hand is tried as chosen, with no search. */
    @Test
    fun `a replace takes a file chosen by hand with its NZB, and clears the search facts and a pick before`() = test {
        val runner = runner()
        val pack = fake("hl-pack", oneVideo = false)
        val id = runner.enqueueAll(listOf(item(4, fake("hl-a")))).single().id
        dao.update(stored(id).copy(limitWait = DownloadColumns.text(LimitWait(INDEXER, LimitWait.Kind.Download, NOW))))
        val chosen = DownloadPick(3, "Harbor.Lights.S02E04.1080p.WEB.h264-PACK.mkv", 1_412_345, pack.prepared.id)
        runner.replace(id, listOf(pack.candidate), need = chosen.size, pick = chosen, grab = DownloadStorage.Grab(pack.key, DownloadStorage.Source(pack.nzb, null)))
        val picked = stored(id)
        assertNull(picked.search)
        assertNull(picked.limitWait)
        assertEquals(chosen, DownloadColumns.pick(picked))
        assertEquals(chosen.size, picked.indexerSize)
        assertEquals(pack.key, storage.grab(id)?.key)

        runner.replace(id, listOf(fake("hl-x").candidate))
        assertNull(stored(id).pick)
        assertNull(storage.grab(id))
    }

    /** L442: TMDB's facts go from a row as it is done, and its limit's wait with them; the file chosen for it stays. */
    @Test
    fun `at Done a row's search facts and limit's wait go, and its pick stays`() = test {
        val runner = runner()
        val a = fake("hl-a")
        val chosen = DownloadPick(0, a.fileName, a.size, a.prepared.id)
        val id = assertNotNull(runner.enqueue(target(4), listOf(a.candidate), search = facts(4), pick = chosen)).id
        dao.update(stored(id).copy(limitWait = DownloadColumns.text(LimitWait(INDEXER, LimitWait.Kind.Search, NOW, NOW + 60_000))))
        assertEquals(facts(4), DownloadColumns.search(stored(id)))
        host(runner)
        advanceUntilIdle()
        val done = stored(id)
        assertEquals(DownloadState.Done, done.state)
        assertNull(done.search)
        assertNull(done.limitWait)
        assertEquals(chosen, DownloadColumns.pick(done))
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

    /**
     * L457: the release of a file opened on Import ([IMPORTED]: no indexer) is never remembered as missing on the providers
     * in use, so a later Download again of it is never passed over untried: the user opened that file. An indexer's
     * release of one video is remembered, as the case above.
     */
    @Test
    fun `an imported file's release found missing isn't remembered`() = test {
        val imported = fake("qm-import", indexer = IMPORTED).apply { missing = listOf(SIZE / 2L until SIZE) }
        val runner = runner()
        val target = DownloadTarget.release(imported.guid, 1, imported.fileName, imported.title)
        val id = assertNotNull(runner.enqueue(target, listOf(imported.candidate))).id
        host(runner)
        advanceUntilIdle()
        assertEquals(listOf(0L), imported.opens.take(1), "its file was written")
        assertEquals(DownloadState.Failed, stored(id).state, "given up for its gap, with no other release")
        assertEquals(emptyList(), recorded)
    }

    /**
     * L423: an imported file's target is named by the file and its NZB, which no log line or trace says: its toString names
     * neither, only its kind and watch key (the NZB's SHA-256 and the file's place). Nor does an episode's or a film's name
     * its title.
     */
    @Test
    fun `a target's toString names no file, NZB or title`() {
        val file = "Quiet.Meridian.S01E02.1080p.WEB.h264-WREN.mkv"
        val target = DownloadTarget.release(NZB_SHA, 3, file, "Quiet.Meridian.S01.1080p.WEB.h264-WREN")
        assertEquals(DownloadKind.Release, target.kind)
        assertEquals(WatchKey.release(NZB_SHA, 3), target.watchKey)
        assertEquals(file to "Quiet.Meridian.S01.1080p.WEB.h264-WREN", target.title to target.subtitle)
        val said = listOf(target, target(4), DownloadTarget.movie(MOVIE, "Copper Crown", 2022, subtitle = "2022")).map { it.toString() }
        assertEquals(emptyList(), said.filter { line -> listOf("Quiet", "Meridian", "WREN", ".mkv", "Harbor", "Low Water", "Copper").any { it in line } })
        assertEquals("DownloadTarget(Release, ${WatchKey.release(NZB_SHA, 3)})", said[0])
    }

    /**
     * A download's lines in the app's log (logcat, and Settings › Logs, which a user may share) name it by its id and its
     * release by its place among the row's, never by the release's name or a provider's host. A release given up for a long
     * gap is remembered as missing on its provider by the app's memory of releases tried, whose own lines name a play's
     * release by its title and the providers by their names, else their hosts: here as the app's deps remember it, on a fake
     * table, with the provider in use that has no name. A release of its own here, so no other test's lines can name it.
     */
    @Test
    fun `a release given up for a long gap is remembered with a line that names no release and no host`() = test {
        val gap = fake("hl-gap-meridian").apply { missing = listOf(SIZE / 2L until SIZE) }
        val whole = fake("hl-whole-meridian")
        val table = TriedTable(NOW)
        val remembered = MissingReleases(table, inUse = { providers }, checkedInUse = { providers }, now = { NOW })
        val runner = runner(missing = DownloadRunnerDeps.remembered(remembered))
        val from = LogBuffer.snapshot().lastOrNull()?.seq ?: 0L
        val id = runner.add(1, gap, whole)
        host(runner)
        advanceUntilIdle()
        assertEquals(DownloadState.Done, stored(id).state)
        val provider = providers.single()
        assertEquals(listOf(ReleaseChecks.provider(provider.id, provider.host, provider.port)), table.entries.getValue(gap.key).missingOn.map { it.provider })
        val lines = LogBuffer.snapshot().filter { it.seq > from }.map { it.message }
        assertEquals(emptyList(), lines.filter { line -> listOf(gap.title, whole.title, provider.host).any { it in line } })
        assertTrue("download $id, release 1 missing on 1 provider" in lines, lines.toString())
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

    /** The NZB and its password go with a download's end, whatever stops the walk as its row is written (L346). */
    @Test
    fun `the NZB and its password go on Done and Failed though the walk is stopped as they go`() = test {
        val runner = runner()
        suspend fun stoppedAsItDrops(id: String, release: Fake) {
            val dropping = CompletableDeferred<Unit>()
            val letGo = CompletableDeferred<Unit>()
            storage.beforeDropSource = {
                dropping.complete(Unit)
                letGo.await()
            }
            release.gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(dropping.isCompleted)
            // Paused, or the host stopped, as the NZB goes.
            val pause = work.launch { runner.pause() }
            runCurrent()
            storage.beforeDropSource = {}
            letGo.complete(Unit)
            pause.join()
            assertNull(storage.source(id))
            assertFalse(File(storage.bookkeeping(id), "password").exists())
        }

        val done = fake("hl-done", password = "s3cret").apply { hold = HOLD }
        val first = runner.add(1, done)
        host(runner)
        runCurrent()
        stoppedAsItDrops(first, done)
        assertEquals(DownloadState.Done, stored(first).state)

        val garbled = fake("hl-garbled", password = "s3cret").apply {
            hold = HOLD
            fails = HOLD to UsenetError.Protocol("garbled")
        }
        val second = runner.add(2, garbled)
        host(runner)
        runCurrent()
        stoppedAsItDrops(second, garbled)
        assertEquals(DownloadState.Failed, stored(second).state)
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

    /**
     * Before Android 14 an update force-stops the app, which Android records as the user's stop (REASON_USER_REQUESTED: no
     * REASON_PACKAGE_UPDATED until 14). An exit the package's update came with is the update's, and a swipe from Recents
     * ("remove task") isn't Task Manager's Stop or a force stop: both leave the download for its host (L359). A force stop
     * long before an update, or long after one, is still the user's.
     */
    @Test
    fun `an exit an update came with, or a swipe from Recents, leaves a row for its host`() = test {
        val r = fake("hl-r")
        val exit = AT + 1_000
        suspend fun left(by: DownloadExit): DownloadState {
            dao.rows.value = emptyMap()
            row(RUNNING, 1, 0, r) { it.copy(state = DownloadState.Running, updatedAt = AT) }
            runner(exit = by).reconcile()
            return stored(RUNNING).state
        }
        // Updated as the exit was recorded, or up to minutes after it (the install's commit comes after its kill).
        assertEquals(DownloadState.Running, left(DownloadExit(DownloadExit.USER_REQUESTED, exit, updated = exit + 2_000)))
        assertEquals(DownloadState.Running, left(DownloadExit(DownloadExit.USER_REQUESTED, exit, updated = exit + 5 * 60_000)))
        assertEquals(DownloadState.Running, left(DownloadExit(DownloadExit.USER_REQUESTED, exit, updated = exit - 1_000)))
        assertEquals(DownloadState.Running, left(DownloadExit(DownloadExit.USER_REQUESTED, exit, description = "remove task")))
        // The user's: an update a day before, or a day after (the app not opened until then), or none known.
        assertEquals(DownloadState.Stopped, left(DownloadExit(DownloadExit.USER_REQUESTED, exit, updated = exit - 86_400_000)))
        assertEquals(DownloadState.Stopped, left(DownloadExit(DownloadExit.USER_REQUESTED, exit, updated = exit + 86_400_000)))
        assertEquals(DownloadState.Stopped, left(DownloadExit(DownloadExit.USER_REQUESTED, exit, description = "stop")))
        assertEquals(DownloadState.Stopped, left(DownloadExit(DownloadExit.USER_REQUESTED, exit)))
    }

    /**
     * The app's start launches reconcile; a host (the boot receiver's service, the job brought back, the start's schedule)
     * can ask first. Whatever asks first, the rows are as reconcile leaves them: a download the user stopped from Android is
     * never taken up by a host that came before it (L359).
     */
    @Test
    fun `a host or the start's schedule that asks before reconcile finds the rows as reconcile leaves them`() = test {
        val r = fake("hl-r")
        row(RUNNING, 1, 0, r) { it.copy(state = DownloadState.Running, updatedAt = AT) }
        row(QUEUED, 2, 1, r)
        val runner = runner(exit = DownloadExit(DownloadExit.USER_REQUESTED, AT + 1))
        assertEquals(emptyList(), runner.runnable())
        host(runner).join()
        assertEquals(emptyList(), resolver.calls)
        assertEquals(DownloadState.Stopped, stored(RUNNING).state)
        assertEquals(DownloadState.Queued, stored(QUEUED).state)
        // The start's own call after them: once a process.
        runner.reconcile()
        assertEquals(1, storage.reconciles)
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

    /**
     * L399b: a download waiting on its indexer's limit no longer ends the walk. Of a season's three rows, the first met the
     * indexer's download limit at its grab: it waits as the limit, and the walk goes on to the next in order, which needs no
     * request to that indexer (its season's copy serves it, as Resolve's cases show) and finishes; the third, with only the
     * limited indexer's releases left, waits the same way. The host ends once nothing more can run, and nothing resolves
     * them again until Resume or the app's start after the limit's end (L434). A wait for the providers still ends the walk
     * (L388), the rest of the queue left queued.
     */
    @Test
    fun `an indexer's limit makes its download wait and the walk goes on to the next, the host ending once nothing more can run`() = test {
        val runner = runner()
        val served = fake("hl-from-copy")
        val queued = runner.enqueueAll(listOf(item(1, fake("hl-limited-first")), item(2, served), item(3, fake("hl-limited-third"))))
        val (first, second, third) = queued.map { it.id }
        resolver.answers[first] = Resolution.Waiting(DownloadHostRules.LIMIT_REACHED)
        resolver.answers[third] = Resolution.Waiting(DownloadHostRules.LIMIT_REACHED)
        val walk = host(runner)
        advanceUntilIdle()
        assertTrue(walk.isCompleted, "the walk ends once nothing more can run, and its host")
        assertFalse(runner.walking.value)
        assertEquals(listOf(first, second, third), resolver.calls)
        listOf(first, third).forEach { id ->
            assertEquals(DownloadState.Waiting, stored(id).state)
            assertEquals(DownloadHostRules.LIMIT_REACHED, stored(id).reason)
        }
        assertEquals(DownloadState.Done, stored(second).state)
        assertContentEquals(served.data, storage.video(second).readBytes())
        assertEquals(emptyList(), runner.runnable(), "the limit's waits can't run")
        // Once: nothing resolves them again until Resume or the app's start.
        host(runner).join()
        assertEquals(listOf(first, second, third), resolver.calls)

        // A wait for the providers still ends the walk, the next left queued.
        val refused = runner.add(4, fake("hl-provider"))
        val next = runner.add(5, fake("hl-next"))
        resolver.answers[refused] = Resolution.Waiting(DownloadHostRules.CHECK_PROVIDER)
        val ended = host(runner)
        advanceUntilIdle()
        assertTrue(ended.isCompleted)
        assertEquals(listOf(first, second, third, refused), resolver.calls)
        assertEquals(DownloadState.Waiting, stored(refused).state)
        assertEquals(DownloadState.Queued, stored(next).state)
    }

    /**
     * L399, L399b, L434: a download waiting on its indexer's limit waits, the walk going on with the rest of the queue. Resume
     * queues it again at once, and forgets the walk's memory of the indexers' limits (L443), so its turn asks them again; a
     * provider saved doesn't. The app's start queues it again once its limit's end is past, or, with no end said, 30 minutes
     * after it began; not before. Nothing of it on a TV.
     */
    @Test
    fun `an indexer's limit makes its download wait, Resume queues it again at once, and the app's start once its end is past`() = test {
        val runner = runner()
        val first = runner.add(1, fake("hl-limit"))
        val second = runner.add(2, fake("hl-after-limit"))
        dao.update(stored(first).copy(limitWait = DownloadColumns.text(LimitWait(INDEXER, LimitWait.Kind.Download, NOW, null))))
        resolver.answers[first] = Resolution.Waiting(DownloadHostRules.LIMIT_REACHED)
        val walk = host(runner)
        advanceUntilIdle()
        assertTrue(walk.isCompleted, "the walk ends once nothing more can run, and its host")
        assertEquals(listOf(first, second), resolver.calls)
        assertEquals(DownloadState.Waiting, stored(first).state)
        assertEquals(DownloadHostRules.LIMIT_REACHED, stored(first).reason)
        assertEquals(DownloadState.Done, stored(second).state)
        assertEquals(emptyList(), runner.runnable().map { it.id }, "the limit's wait can't run")

        // A provider saved isn't the limit's end.
        runner.retryNow()
        assertEquals(DownloadState.Waiting, stored(first).state)

        // Resume: queued again at once, the walk's memory of limits forgotten, and at the head of what runs.
        val indexer = IndexerConfig(INDEXER, "Indexer A", "https://a.indexer.invalid/api", "key-a")
        limits.answered(indexer, null, NOW)
        val third = runner.add(3, fake("hl-after-resume"))
        runner.resume()
        assertEquals(DownloadState.Queued, stored(first).state)
        assertNull(stored(first).reason)
        assertEquals(emptyMap(), limits.keptOut(listOf(indexer), NOW))
        assertEquals(listOf(first, third), runner.runnable().map { it.id })
        host(runner)
        advanceUntilIdle()
        assertEquals(listOf(first, second, first, third), resolver.calls)
        assertEquals(DownloadState.Waiting, stored(first).state)

        // The app's start: not before 30 minutes after the limit began, none said when it ends.
        resolver.answers.remove(first)
        dao.update(stored(first).copy(limitWait = DownloadColumns.text(LimitWait(INDEXER, LimitWait.Kind.Download, NOW + testScheduler.currentTime, null))))
        runner.limitsEnded()
        assertEquals(DownloadState.Waiting, stored(first).state)
        advanceTimeBy(30 * MINUTE - 1)
        runner.limitsEnded()
        assertEquals(DownloadState.Waiting, stored(first).state)
        advanceTimeBy(1)
        runner.limitsEnded()
        assertEquals(DownloadState.Queued, stored(first).state)
        assertNull(stored(first).reason)

        // One whose indexer said when it ends: once that is past, not 30 minutes after it began.
        val at = NOW + testScheduler.currentTime
        dao.update(
            stored(first).copy(
                state = DownloadState.Waiting, reason = DownloadHostRules.LIMIT_REACHED,
                limitWait = DownloadColumns.text(LimitWait(INDEXER, LimitWait.Kind.Search, at, at + 2 * HOUR)),
            ),
        )
        advanceTimeBy(31 * MINUTE)
        runner.limitsEnded()
        assertEquals(DownloadState.Waiting, stored(first).state)
        advanceTimeBy(2 * HOUR - 31 * MINUTE)
        runner.limitsEnded()
        assertEquals(DownloadState.Queued, stored(first).state)
        // Another wait isn't the limit's: the app's start leaves it as it is.
        dao.setState(second, DownloadState.Waiting, DownloadHostRules.CHECK_PROVIDER, NOW)
        runner.limitsEnded()
        assertEquals(DownloadState.Waiting, stored(second).state)

        // Nothing on a TV.
        dao.setState(first, DownloadState.Waiting, DownloadHostRules.LIMIT_REACHED, NOW)
        runner(television = true).limitsEnded()
        assertEquals(DownloadState.Waiting, stored(first).state)
    }

    /**
     * L392, L434: a season row waiting on an indexer's limit after its turn's search keeps what that search found through
     * Resume and the app's start: queued again with its releases and its facts as searched, so its next turn searches
     * nothing again and asks the indexers only for its grab.
     */
    @Test
    fun `a season row waiting on a limit keeps what its turn found through Resume and the app's start, searching nothing again`() = test {
        val runner = runner()
        val found = listOf(fake("hl-found-a"), fake("hl-found-b"))
        val id = "hl-limited"
        row(id, 4, 0, *found.toTypedArray()) {
            it.copy(
                state = DownloadState.Waiting, reason = DownloadHostRules.LIMIT_REACHED, search = DownloadColumns.text(facts(4, searched = true)),
                limitWait = DownloadColumns.text(LimitWait(INDEXER, LimitWait.Kind.Download, NOW, null)),
            )
        }
        runner.resume()
        val resumed = stored(id)
        assertEquals(DownloadState.Queued, resumed.state)
        assertEquals(found.map { it.candidate }, resumed.candidates)
        assertEquals(facts(4, searched = true), DownloadColumns.search(resumed))

        // The app's start, once its limit has ended: the same.
        dao.setState(id, DownloadState.Waiting, DownloadHostRules.LIMIT_REACHED, NOW)
        advanceTimeBy(30 * MINUTE)
        runner.limitsEnded()
        val started = stored(id)
        assertEquals(DownloadState.Queued, started.state)
        assertEquals(found.map { it.candidate }, started.candidates)
        assertEquals(facts(4, searched = true), DownloadColumns.search(started))
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

    /**
     * The device's network, the one flow the deps give the runner and the writer they make (L381): lost and back while the
     * writer waits to open the stream again after a provider out of reach, its return ends that wait at once, the backoff
     * starting again from 2 s, as Resume's does. The host stays on through it, as a job or service whose need holds does.
     */
    @Test
    fun `the network's return ends the writer's wait before a reopen, through the one flow the deps give it`() = test {
        // Refused four times: opened at 0, 2 and 6 s, and next at 14 s but for the network.
        val a = fake("hl-a").apply { refused = setOf(0, 1, 2, 3) }
        val runner = runner()
        val id = runner.add(1, a)
        work.launch {
            kotlinx.coroutines.delay(7_000)
            network.value = false
            kotlinx.coroutines.delay(2_000)
            network.value = true
        }
        host(runner)
        advanceUntilIdle()
        // Lost at 7 s and back at 9 s: opened again at once, then 2 s after that.
        assertEquals(listOf(0L, 2_000, 6_000, 9_000, 11_000), openedAt(id))
        assertEquals(DownloadState.Done, stored(id).state)
        assertContentEquals(a.data, storage.video(id).readBytes())
    }

    /**
     * The download's file is allocated whole before its first byte, so a device that fills meanwhile fails the record's
     * write to Room first (SQLITE_FULL, no IOException): it fails as "Storage is full" (L382). Where even that can't be
     * written, the walk ends rather than take the same row up again at once, and the next host goes on with it.
     */
    @Test
    fun `a full device fails a download as Storage is full, and a failure that can't be kept ends the walk`() = test {
        val a = fake("hl-a").apply { hold = HOLD }
        val runner = runner()
        val id = runner.add(1, a)
        resolver.during = { call -> if (call > 2) throw AssertionError("download $id resolved again at once: resolve $call") }
        val walk = host(runner)
        runCurrent()
        dao.full = setOf("progress")
        a.gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(walk.isCompleted)
        assertEquals(DownloadState.Failed, stored(id).state)
        assertEquals(DownloadWriter.STORAGE_FULL, stored(id).reason)

        // Nothing can be written: the walk ends, the row as it was, for the next host.
        val b = fake("hl-b").apply { hold = HOLD }
        dao.full = emptySet()
        resolver.calls.clear()
        val second = runner.add(2, b)
        val again = host(runner)
        runCurrent()
        dao.full = setOf("progress", "update", "setState")
        b.gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(again.isCompleted, "the walk took the row up again and again")
        assertEquals(listOf(second), resolver.calls)
        assertEquals(DownloadState.Running, stored(second).state)

        // Room again: the next host goes on with it from its offset.
        dao.full = emptySet()
        host(runner)
        advanceUntilIdle()
        assertEquals(DownloadState.Done, stored(second).state)
        assertContentEquals(b.data, storage.video(second).readBytes())
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

    /**
     * A job Android stopped (item 9): its host cancels the walk, which records where the download under way stopped, then
     * says why it stopped there: waiting for the need that went, the job running it again once the need is back, or Stopped
     * by Android when the user stopped it, holding the queue (L359). One a walk has taken up again since is left as it runs.
     * The queue is read as the table has it now, for what a host's end says.
     */
    @Test
    fun `a host stopped by Android leaves the one under way waiting for its need, or stopped, never one a walk runs again`() = test {
        val a = fake("hl-a").apply { hold = HOLD }
        val runner = runner()
        val first = runner.add(1, a)
        val second = runner.add(2, fake("hl-b"))
        val walk = host(runner)
        runCurrent()
        assertEquals(DownloadState.Running, stored(first).state)

        // Stopped for the network: as a kill leaves it, then waiting for Wi-Fi at its safe offset.
        walk.cancelAndJoin()
        assertEquals(DownloadState.Running, stored(first).state)
        runner.hostStopped(DownloadState.Waiting, DownloadHostRules.WAITING_FOR_WIFI)
        val waiting = stored(first)
        assertEquals(DownloadState.Waiting, waiting.state)
        assertEquals(DownloadHostRules.WAITING_FOR_WIFI, waiting.reason)
        assertEquals(HOLD, waiting.safeOffset)
        assertEquals(DownloadState.Queued, stored(second).state)
        // The table now, ahead of the state Room says later: what the host's end leaves in the status notification.
        assertEquals(listOf(first to DownloadState.Waiting, second to DownloadState.Queued), runner.items().map { it.row.id to it.row.state })
        assertEquals(HOLD, runner.items().first().written)
        // The job's need back, it runs first, from where it stopped.
        assertEquals(listOf(first, second), runner.runnable().map { it.id })
        val again = host(runner)
        runCurrent()
        assertEquals(DownloadState.Running, stored(first).state)
        assertEquals(listOf(0L, HOLD), a.opens)

        // A stop said after a walk took it up again leaves it running.
        runner.hostStopped(DownloadState.Stopped, DownloadHostRules.STOPPED_BY_ANDROID)
        assertEquals(DownloadState.Running, stored(first).state)

        // Stopped by the user from Android: held until Resume.
        again.cancelAndJoin()
        runner.hostStopped(DownloadState.Stopped, DownloadHostRules.STOPPED_BY_ANDROID)
        assertEquals(DownloadState.Stopped, stored(first).state)
        assertEquals(DownloadHostRules.STOPPED_BY_ANDROID, stored(first).reason)
        assertEquals(DownloadState.Queued, stored(second).state)
        assertEquals(emptyList(), runner.runnable())
    }

    /**
     * The service waits in place for its need (item 10): the download it would run next says why, the one a stopped walk
     * left under way first, at its safe offset; with none under way, the first that can run. A need's wait still runs (L388),
     * and the walk takes it up first when the need is back. One a walk runs is left as it runs.
     */
    @Test
    fun `a host waiting in place for its need says so on the one it would run next, never on one a walk runs`() = test {
        val a = fake("hl-a").apply { hold = HOLD }
        val runner = runner()
        val first = runner.add(1, a)
        val second = runner.add(2, fake("hl-b"))

        // Before any walk: the first that can run waits to charge, the other stays queued, and both can still run.
        runner.hostWaits(DownloadHostRules.WAITING_TO_CHARGE)
        assertEquals(DownloadState.Waiting, stored(first).state)
        assertEquals(DownloadHostRules.WAITING_TO_CHARGE, stored(first).reason)
        assertEquals(DownloadState.Queued, stored(second).state)
        assertEquals(listOf(first, second), runner.runnable().map { it.id })

        // Its need back, the walk takes it up first; a wait said while it runs leaves it running.
        val walk = host(runner)
        runCurrent()
        assertEquals(DownloadState.Running, stored(first).state)
        runner.hostWaits(DownloadHostRules.WAITING_FOR_WIFI)
        assertEquals(DownloadState.Running, stored(first).state)
        assertNull(stored(first).reason)

        // The network gone: the walk stopped where it was, then waiting for Wi-Fi at its safe offset; the other queued still.
        walk.cancelAndJoin()
        runner.hostWaits(DownloadHostRules.WAITING_FOR_WIFI)
        val waiting = stored(first)
        assertEquals(DownloadState.Waiting, waiting.state)
        assertEquals(DownloadHostRules.WAITING_FOR_WIFI, waiting.reason)
        assertEquals(HOLD, waiting.safeOffset)
        assertEquals(DownloadState.Queued, stored(second).state)

        // Back: on from where it stopped.
        host(runner)
        runCurrent()
        assertEquals(DownloadState.Running, stored(first).state)
        assertEquals(listOf(0L, HOLD), a.opens)

        // A held queue has nothing to say a wait on.
        runner.pause()
        runner.hostWaits(DownloadHostRules.WAITING_FOR_WIFI)
        assertEquals(DownloadState.Paused, stored(first).state)
        assertEquals(DownloadState.Queued, stored(second).state)
    }

    @Test
    fun `a show's and a film's downloads come with the live write's bytes, the removed left out, and none on a TV`() = test {
        val a = fake("hl-a").apply { hold = HOLD }
        val runner = runner()
        val first = runner.add(1, a)
        val second = runner.add(2, fake("hl-b"))
        val film = row(FILM, 0, 5, fake("cc")) { it.copy(watchKey = WatchKey.movie(MOVIE), kind = DownloadKind.Movie, title = "Copper Crown", showTmdbId = null, season = null, episode = null, movieTmdbId = MOVIE) }
        host(runner)
        runCurrent()
        // The one being written says its bytes as they come, before its record does.
        val show = runner.forShow(SHOW).first()
        assertEquals(listOf(first, second), show.map { it.row.id })
        assertEquals(HOLD, show.first().written)
        assertNotNull(show.first().bytesPerSecond)
        assertEquals(0L, show.last().written)
        assertNull(show.last().bytesPerSecond)
        assertEquals(film.id, runner.forMovie(MOVIE).first()?.row?.id)
        assertNull(runner.forMovie(MOVIE + 1).first())
        runner.remove(listOf(second))
        assertEquals(listOf(first), runner.forShow(SHOW).first().map { it.row.id })
        a.gate.complete(Unit)
        advanceUntilIdle()
        // A TV reads none, whatever the table holds.
        val tv = runner(television = true)
        assertEquals(emptyList(), tv.forShow(SHOW).first())
        assertNull(tv.forMovie(MOVIE).first())
    }

    @Test
    fun `nothing runs on a TV, where only reconcile does anything`() = test {
        host = DownloadHost.Service
        val tv = runner(television = true)
        val a = fake("hl-a")
        assertNull(tv.enqueue(target(1), listOf(a.candidate)))
        assertEquals(emptyList(), tv.enqueueAll(listOf(item(2, a))))
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
        tv.deleteAll()
        advanceUntilIdle()
        assertEquals(emptyList(), resolver.calls)
        assertEquals(DownloadState.Queued, stored(QUEUED).state)
        assertEquals(emptyList(), tv.runnable())
        assertEquals(emptyList(), tv.state.value)
        assertEquals(emptyMap(), tv.done.value)
        assertEquals(emptyList(), tv.items())
        tv.hostStopped(DownloadState.Stopped, DownloadHostRules.STOPPED_BY_ANDROID)
        tv.hostWaits(DownloadHostRules.WAITING_FOR_WIFI)
        assertEquals(DownloadState.Queued, stored(QUEUED).state)
        assertNull(stored(QUEUED).reason)
        assertEquals(emptyList(), switches)
        tv.reconcile()
        assertEquals(1, storage.reconciles)
        // Nothing is held there (L413).
        tv.hold(Any(), QUEUED)
        tv.holdOnly(Any(), QUEUED)
        assertEquals(emptySet(), tv.held.value)
    }

    /**
     * L445: a show's notification names one of its downloads, and the page its tap or Play opens reads that row once the
     * runner's state lists it, and its finished ones for the hook: a page opened as the process starts finds what it shows.
     * One gone since, or being removed, is none; on a TV, none.
     */
    @Test
    fun `a notification's download is read once the runner lists it, none once it is gone or removed, nor on a TV`() = test {
        val a = fake("hl-a")
        val done = row(A, 1, 0, a) { it.copy(state = DownloadState.Done, completedAt = NOW) }
        row(B, 2, 1, a) { it.copy(state = DownloadState.Deleting) }
        val runner = runner()
        // Asked as the process starts, before the runner's state has said: answered once it lists the row, at once, not
        // as its wait runs out.
        val asked = testScheduler.currentTime
        val listed = async(start = CoroutineStart.UNDISPATCHED) { runner.listed(A) }
        assertFalse(listed.isCompleted, "answered before the runner's state listed it")
        runCurrent()
        assertTrue(listed.isCompleted, "answered once the runner listed it")
        assertEquals(asked, testScheduler.currentTime, "no time waited")
        assertEquals(done, listed.await())
        assertTrue(runner.state.value.any { it.row.id == A })
        assertEquals(done, runner.done.value[done.watchKey])
        assertNull(runner.listed(B), "being removed")
        assertNull(runner.listed("dl-gone"))
        assertNull(runner(television = true).listed(A))
    }

    /**
     * A finished download of episode [episode] as a past process left it, finished at [completedAt] (by the test's clock):
     * its row Done, its marker, and a small file where the runner keeps one.
     */
    private suspend fun finished(id: String, episode: Int, completedAt: Long): Download {
        val row = row(id, episode, 0, fake("hl-$id")) { it.copy(state = DownloadState.Done, completedAt = completedAt, relativePath = "downloads/$id/v.mkv", size = 1_024) }
        storage.videoFolder(id).mkdirs()
        storage.video(id).writeBytes(ByteArray(1_024))
        return row
    }

    /** [row]'s title marked watched at [at], as the library's join gives it. */
    private fun watchedAt(row: Download, at: Long) = WatchedDownload(row.id, row.watchKey, row.completedAt!!, at)

    /** Delete once watched switched on at [since]. */
    private fun deleteOnceWatched(since: Long) {
        prefs.value = DownloadPrefs(deleteOnceWatched = true, deleteOnceWatchedSince = since)
    }

    private fun gone(id: String): Boolean = dao.rows.value[id] == null && !storage.videoFolder(id).exists() && !storage.bookkeeping(id).exists()

    /**
     * W111, L412, L413: the run deletes each finished download due, a day after its watched mark, in the crash-safe order
     * (storage's delete alone: hidden, then its files, then its row), with no Undo; it leaves one a play holds, one not due
     * yet, one queued and one removed while its Undo can still bring it back, whatever the join says of them. Their count
     * and ids are what it returns.
     */
    @Test
    fun `deleteWatched deletes each due download not held in the crash-safe order, and leaves the rest`() = test {
        val runner = runner()
        val done = finished(A, 1, completedAt = NOW - 3 * DAY)
        val held = finished(B, 2, completedAt = NOW - 3 * DAY)
        val ahead = finished(C, 3, completedAt = NOW - 3 * DAY)
        val removed = finished(OTHER, 4, completedAt = NOW - 3 * DAY)
        val queued = row(QUEUED, 5, 1, fake("hl-q"))
        runner.remove(listOf(removed.id))
        runner.hold("player:lease", held.id)
        deleteOnceWatched(NOW - 4 * DAY)
        watched.value = listOf(
            watchedAt(done, NOW - 2 * DAY), watchedAt(held, NOW - 2 * DAY), watchedAt(ahead, NOW - HOUR), watchedAt(removed, NOW - 2 * DAY),
            WatchedDownload(queued.id, queued.watchKey, NOW - 3 * DAY, NOW - 2 * DAY),
        )
        runCurrent()
        val before = storage.events.size

        assertEquals(listOf(A), runner.deleteWatched(NOW))
        assertTrue(gone(A), "the due one, row, file and bookkeeping")
        assertEquals(listOf("delete $A"), storage.events.drop(before), "storage's delete alone, its crash-safe order")
        assertEquals(DownloadState.Done, stored(B).state)
        assertTrue(storage.video(B).isFile, "the one a play holds")
        assertEquals(DownloadState.Done, stored(C).state)
        assertTrue(storage.video(C).isFile, "the one watched an hour ago")
        assertEquals(DownloadState.Deleting, stored(OTHER).state, "removed, its Undo still to come")
        assertEquals(DownloadState.Queued, stored(QUEUED).state)
        // No Undo: nothing brings it back.
        runner.undoRemove(listOf(A))
        assertNull(dao.get(A))
        // Again, nothing more is due.
        assertEquals(emptyList(), runner.deleteWatched(NOW))
        // Off, nothing goes, the one held let go of or not.
        prefs.value = DownloadPrefs()
        runner.release("player:lease")
        runCurrent()
        assertEquals(emptyList(), runner.deleteWatched(NOW + 10 * DAY))
        assertEquals(DownloadState.Done, stored(B).state)
    }

    /**
     * L414, L444: the app's start's run waits two minutes, as a player made again after a process death takes its hold
     * first: a download due already reads as a plain one meanwhile, and goes then; one ahead says "Deleting tomorrow" and
     * goes at its own time while the process lives, and says nothing more once gone. Mark unwatched keeps one.
     */
    @Test
    fun `the start's run waits two minutes, then deletes each download at its due time while the process lives`() = test {
        val runner = runner()
        val due = finished(A, 1, completedAt = NOW - 3 * DAY)
        val ahead = finished(B, 2, completedAt = NOW - 3 * DAY)
        val unmarked = finished(C, 3, completedAt = NOW - 3 * DAY)
        deleteOnceWatched(NOW - 4 * DAY)
        watched.value = listOf(watchedAt(due, NOW - 2 * DAY), watchedAt(ahead, NOW - DAY + 30 * MINUTE), watchedAt(unmarked, NOW - DAY + 40 * MINUTE))
        work.launch { runner.autoDelete() }
        runCurrent()
        assertEquals(setOf(B, C), runner.deletingTomorrow.value, "the due one says nothing more")

        advanceTimeBy(2 * MINUTE - 1)
        runCurrent()
        assertEquals(DownloadState.Done, stored(A).state, "in the start's two minutes")
        advanceTimeBy(1)
        runCurrent()
        assertTrue(gone(A), "two minutes after the start")
        assertEquals(setOf(B, C), runner.deletingTomorrow.value)

        // Marked unwatched: kept, and it says nothing more.
        watched.value = watched.value.filter { it.id != C }
        runCurrent()
        assertEquals(setOf(B), runner.deletingTomorrow.value)

        advanceTimeBy(30 * MINUTE - 2 * MINUTE - 1)
        runCurrent()
        assertEquals(DownloadState.Done, stored(B).state, "a moment before its time")
        advanceTimeBy(1)
        runCurrent()
        assertTrue(gone(B), "at its time")
        assertEquals(emptySet(), runner.deletingTomorrow.value)
        advanceTimeBy(DAY)
        runCurrent()
        assertEquals(DownloadState.Done, stored(C).state, "marked unwatched")
    }

    /**
     * L413, L414: a due download a play holds is left by the run, and goes as its hold ends; one let go of in the start's
     * two minutes waits for them.
     */
    @Test
    fun `a due download a play holds goes as its hold ends, once the start's two minutes are over`() = test {
        val runner = runner()
        val playing = finished(A, 1, completedAt = NOW - 3 * DAY)
        val launched = finished(B, 2, completedAt = NOW - 3 * DAY)
        deleteOnceWatched(NOW - 4 * DAY)
        watched.value = listOf(watchedAt(playing, NOW - 2 * DAY), watchedAt(launched, NOW - 2 * DAY))
        runner.hold("player:lease", playing.id)
        val launch = Any()
        runner.hold(launch, launched.id)
        work.launch { runner.autoDelete() }
        runCurrent()

        advanceTimeBy(MINUTE)
        runner.release(launch)
        runCurrent()
        assertEquals(DownloadState.Done, stored(B).state, "let go of in the start's two minutes")
        assertEquals(emptySet(), runner.deletingTomorrow.value, "due, it says nothing more")
        advanceTimeBy(MINUTE)
        runCurrent()
        assertTrue(gone(B))
        assertEquals(DownloadState.Done, stored(A).state, "while it plays")
        assertTrue(storage.video(A).isFile)

        advanceTimeBy(HOUR)
        runCurrent()
        assertEquals(DownloadState.Done, stored(A).state, "still while it plays")
        runner.release("player:lease")
        runCurrent()
        assertTrue(gone(A), "as its play ended")
    }

    /**
     * L417: a cast of a download is a third holder, beside the hook's launch and a player's lease. A move to Kitchen
     * display holds the file in its own name before the player closes and lets go, so a due download the cast holds is
     * left by the run, and goes as the cast's hold ends: Delete once watched counts a cast as a play.
     */
    @Test
    fun `a due download a cast holds is left by the run, and goes as the cast lets go`() = test {
        val runner = runner()
        val casting = finished(A, 1, completedAt = NOW - 3 * DAY)
        deleteOnceWatched(NOW - 4 * DAY)
        watched.value = listOf(watchedAt(casting, NOW - 2 * DAY))
        runner.hold("player:lease", casting.id)
        work.launch { runner.autoDelete() }
        runCurrent()
        val follow = Any()
        assertTrue(runner.hold(follow, casting.id), "the cast's own hold")
        runner.release("player:lease")
        advanceTimeBy(2 * MINUTE + 1)
        runCurrent()
        assertEquals(DownloadState.Done, stored(A).state, "while it casts, the player gone")
        assertTrue(storage.video(A).isFile)
        assertEquals(emptyList(), runner.deleteWatched(NOW))
        runner.release(follow, casting.id)
        runCurrent()
        assertTrue(gone(A), "as the cast let go")
    }

    /**
     * L413: the run never deletes a file a play holds, though the play began as it ran. A Play of the next due download,
     * taken while the run deletes the one before, holds it before the run comes to it, and the run leaves it to go as that
     * hold ends; one the run has taken can't be held, so no play is launched from a file going, nor handed to a player.
     */
    @Test
    fun `a play begun as the run deletes is never deleted from under it`() = test {
        val runner = runner()
        val first = finished(A, 1, completedAt = NOW - 3 * DAY)
        val next = finished(B, 2, completedAt = NOW - 3 * DAY)
        deleteOnceWatched(NOW - 4 * DAY)
        watched.value = listOf(watchedAt(first, NOW - 2 * DAY), watchedAt(next, NOW - 2 * DAY))
        runCurrent()
        val deleting = CompletableDeferred<Unit>()
        val begun = CompletableDeferred<Unit>()
        storage.onDelete = { id ->
            if (id == A) {
                begun.complete(Unit)
                deleting.await()
            }
        }
        val run = async { runner.deleteWatched(NOW) }
        runCurrent()
        assertTrue(begun.isCompleted, "deleting the first")
        // A Play of the one being deleted: not held, whoever asks.
        assertFalse(runner.hold(Any(), A), "held as it is deleted")
        runner.holdOnly("player:lease", A)
        assertEquals(emptySet(), runner.held.value)
        // A Play of the next, tapped meanwhile: its hold taken before the run comes to it.
        assertTrue(runner.hold("player:lease", B))
        deleting.complete(Unit)
        assertEquals(listOf(A), run.await())
        assertTrue(gone(A))
        assertEquals(DownloadState.Done, stored(B).state, "held as the run came to it")
        assertTrue(storage.video(B).isFile)
        // Gone, the first is never held again, though the finished ones' map lists it a moment longer.
        assertFalse(runner.hold("player:lease", A))
        assertEquals(setOf(B), runner.held.value)
    }

    /** W108, L441: on a TV nothing is deleted or said, whatever the setting and the marks. */
    @Test
    fun `delete once watched deletes nothing and says nothing on a TV`() = test {
        val tv = runner(television = true)
        finished(A, 1, completedAt = NOW - 3 * DAY)
        deleteOnceWatched(NOW - 4 * DAY)
        watched.value = listOf(WatchedDownload(A, WatchKey.episode(SHOW, 2, 1), NOW - 3 * DAY, NOW - 2 * DAY))
        work.launch { tv.autoDelete() }
        advanceTimeBy(DAY)
        runCurrent()
        assertEquals(emptyList(), tv.deleteWatched(NOW + 10 * DAY))
        assertEquals(DownloadState.Done, stored(A).state)
        assertTrue(storage.video(A).isFile)
        assertEquals(emptySet(), tv.deletingTomorrow.value)
    }

    /**
     * L394, C1: a season pack's copy is held while a row of its show and season is still to finish, read from the rows, and
     * goes at the season's last end, whichever end it is: Done; failed by Resolve itself, which fails no row through the
     * runner's own failed(); cancelled; deleted after a Remove; Delete all; failed by the runner as it is written.
     */
    @Test
    fun `a season pack's copy goes at its season's last end, whichever end that is`() = test {
        val runner = runner()
        storage.packs.write(PACK, PACK_NZB, "s3cret")
        val b = fake("hl-b").apply { hold = HOLD }
        val first = runner.add(1, fake("hl-a"))
        val second = runner.add(2, b)
        host(runner)
        advanceUntilIdle()
        // The first done, the second under way: held.
        assertEquals(DownloadState.Done, stored(first).state)
        assertEquals(DownloadState.Running, stored(second).state)
        assertNotNull(storage.packs.find(PACK.key))
        b.gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(DownloadState.Done, stored(second).state)
        assertNull(storage.packs.find(PACK.key))

        // Failed at Resolve: no release left to try.
        storage.packs.write(PACK, PACK_NZB, "s3cret")
        val failed = runner.add(3, fake("hl-c"))
        resolver.scripted += Resolution.Failed(DownloadResolver.NO_OTHER_RELEASE)
        host(runner)
        advanceUntilIdle()
        assertEquals(DownloadState.Failed, stored(failed).state)
        assertNull(storage.packs.find(PACK.key))

        // Cancelled as it is written.
        storage.packs.write(PACK, PACK_NZB, "s3cret")
        val cancelled = runner.add(4, fake("hl-d").apply { hold = HOLD })
        host(runner)
        advanceUntilIdle()
        assertNotNull(storage.packs.find(PACK.key))
        runner.cancel(cancelled)
        advanceUntilIdle()
        assertNull(storage.packs.find(PACK.key))

        // Removed: gone once the row is deleted, after the Undo bar's time.
        storage.packs.write(PACK, PACK_NZB, "s3cret")
        val removed = runner.add(5, fake("hl-e").apply { hold = HOLD })
        host(runner)
        advanceUntilIdle()
        runner.remove(listOf(removed))
        advanceTimeBy(DownloadRunner.UNDO_MILLIS + 1)
        runCurrent()
        assertNull(dao.get(removed))
        assertNull(storage.packs.find(PACK.key))

        // Delete all, a row of the season still queued: its reconcile sweeps.
        storage.packs.write(PACK, PACK_NZB, "s3cret")
        runner.add(6, fake("hl-f").apply { hold = HOLD })
        host(runner)
        advanceUntilIdle()
        runner.deleteAll()
        advanceUntilIdle()
        assertNull(storage.packs.find(PACK.key))

        // Failed by the runner itself as it is written, its record refused by a full device (Storage is full).
        storage.packs.write(PACK, PACK_NZB, "s3cret")
        val g = fake("hl-g").apply { hold = HOLD }
        val full = runner.add(7, g)
        host(runner)
        runCurrent()
        assertNotNull(storage.packs.find(PACK.key))
        dao.full = setOf("progress")
        g.gate.complete(Unit)
        advanceUntilIdle()
        dao.full = emptySet()
        assertEquals(DownloadState.Failed, stored(full).state)
        assertEquals(DownloadWriter.STORAGE_FULL, stored(full).reason)
        assertNull(storage.packs.find(PACK.key))
    }

    /**
     * L394, L379: a row removed with its Undo still to come is still to finish for its season's copy, as an Undo brings it
     * back: a row of the season that ends meanwhile leaves the copy for it. Once the Undo has gone and the row is deleted,
     * the copy goes with it.
     */
    @Test
    fun `a season pack's copy stays for the season's rows removed while their Undo can bring them back`() = test {
        val runner = runner()
        storage.packs.write(PACK, PACK_NZB, "s3cret")
        val a = fake("hl-a").apply { hold = HOLD }
        val running = runner.add(1, a)
        val second = runner.add(2, fake("hl-b"))
        val third = runner.add(3, fake("hl-c"))
        host(runner)
        runCurrent()
        assertEquals(DownloadState.Running, stored(running).state)

        // The season's others removed by mistake; the one under way ends within the Undo bar's time.
        runner.remove(listOf(second, third))
        runCurrent()
        a.gate.complete(Unit)
        advanceTimeBy(DownloadRunner.UNDO_MILLIS / 2)
        runCurrent()
        assertEquals(DownloadState.Done, stored(running).state)
        assertEquals(DownloadState.Deleting, stored(second).state)
        assertNotNull(storage.packs.find(PACK.key), "the copy, while an Undo can bring the season's others back")

        // Brought back: still to finish, the copy theirs once the removal's time is over.
        runner.undoRemove(listOf(second, third))
        advanceTimeBy(DownloadRunner.UNDO_MILLIS)
        runCurrent()
        assertEquals(DownloadState.Queued, stored(second).state)
        assertNotNull(storage.packs.find(PACK.key))

        // Removed again, and deleted once the Undo has gone: the copy with them.
        runner.remove(listOf(second, third))
        advanceTimeBy(DownloadRunner.UNDO_MILLIS + 1)
        runCurrent()
        assertNull(dao.get(second))
        assertNull(dao.get(third))
        assertNull(storage.packs.find(PACK.key))
    }

    /**
     * L398: a season row whose turn found nothing fails "No playable release found" (Resolve's answer), and the walk goes
     * on to the next: nothing searches it again by itself.
     */
    @Test
    fun `an episode whose turn found nothing fails, and the next starts`() = test {
        val (a, c) = listOf(fake("hl-a"), fake("hl-c"))
        val runner = runner()
        val queued = runner.enqueueAll(listOf(item(1, a), DownloadRunner.QueueItem(target(2), emptyList(), search = facts(2)), item(3, c)))
        val (first, nothing, third) = queued.map { it.id }
        resolver.answers[nothing] = Resolution.Failed(DownloadSheetModel.NO_RELEASE)
        val walk = host(runner)
        advanceUntilIdle()
        assertTrue(walk.isCompleted)
        assertEquals(listOf(first, nothing, third), resolver.calls)
        assertEquals(DownloadState.Done, stored(first).state)
        assertEquals(DownloadState.Failed, stored(nothing).state)
        assertEquals("No playable release found", stored(nothing).reason)
        assertEquals(DownloadState.Done, stored(third).state)
        assertContentEquals(c.data, storage.video(third).readBytes())
        // Once: nothing resolves it again until Try again.
        host(runner).join()
        assertEquals(listOf(first, nothing, third), resolver.calls)
    }

    /**
     * L406: the runner says which seasons a kept copy of their pack leads, and every copy's release key, for the Downloads
     * page's words: as the copies are written and as they are swept. None on a TV.
     */
    @Test
    fun `the runner's packs say which seasons' copies lead and every copy's key, as they are written and swept`() = test {
        val runner = runner()
        runCurrent()
        assertEquals(DownloadRunner.Packs(), runner.packs.value)
        val other = PACK.copy(key = ReleaseKey.of(INDEXER, "hl-s03-pack"), season = 3, leads = false)
        storage.packs.write(PACK, PACK_NZB, null)
        storage.packs.write(other, PACK_NZB, null)
        runCurrent()
        assertEquals(DownloadRunner.Packs(mapOf((SHOW to 2) to setOf(PACK.key)), setOf(PACK.key, other.key)), runner.packs.value)
        // Season 2's still to finish; season 3's none: its copy goes at the sweep.
        runner.add(4, fake("hl-a"))
        storage.packs.sweep(dao.all().first())
        runCurrent()
        assertEquals(DownloadRunner.Packs(mapOf((SHOW to 2) to setOf(PACK.key)), setOf(PACK.key)), runner.packs.value)

        val tv = runner(television = true)
        runCurrent()
        assertEquals(DownloadRunner.Packs(), tv.packs.value)
    }

    /** Resolve's fake, answering as DownloadResolver does, as the class says. */
    private inner class FakeResolver {
        val calls = mutableListOf<String>()
        val grabbed = mutableListOf<String>()
        val reopened = mutableListOf<String>()

        /** What the next resolves answer before any release is tried, in turn. */
        val scripted = ArrayDeque<Resolution>()

        /** What a download's resolves answer, by its id, before any release is tried: a turn whose search found nothing. */
        val answers = mutableMapOf<String, Resolution>()

        /** When each resolve was asked for, from the test's start. */
        val times = mutableListOf<Long>()

        /** What happens while each resolve runs, by its number from 1: a Resume, the network lost and back. */
        var during: suspend (call: Int) -> Unit = {}

        suspend fun resolve(id: String): Resolution {
            calls += id
            times += clock.now() - NOW
            during(calls.size)
            val row = dao.get(id) ?: return Resolution.Gone
            (answers[id] ?: scripted.removeFirstOrNull())?.let { said ->
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
                release = RememberedRelease(fake.indexer, fake.guid, fake.title, null, 0, fake.prepared.id, fake.fileName, fake.size),
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

        /** Which writes fail as SQLite's do on a full device, while set. */
        var full: Set<String> = emptySet()

        private fun write(what: String) {
            if (what in full) throw android.database.sqlite.SQLiteFullException("database or disk is full (code 13 SQLITE_FULL)")
        }

        private fun sorted(filter: (Download) -> Boolean): Flow<List<Download>> =
            rows.map { all -> all.values.filter(filter).sortedWith(compareBy(Download::position, Download::createdAt)) }

        override fun queue() = sorted { it.state != DownloadState.Done && it.state != DownloadState.Deleting }
        override fun all() = sorted { true }
        override suspend fun get(id: String) = rows.value[id]
        override fun done() = rows.map { all -> all.values.filter { it.state == DownloadState.Done }.sortedWith(compareBy({ it.completedAt }, { it.createdAt })) }
        override suspend fun doneFor(watchKey: String) = rows.value.values.filter { it.state == DownloadState.Done && it.watchKey == watchKey }.maxByOrNull { it.completedAt ?: 0 }
        override fun forShow(showTmdbId: Int) = sorted { it.showTmdbId == showTmdbId && it.state != DownloadState.Deleting }
        override fun forMovie(movieTmdbId: Int) = rows.map { all -> all.values.firstOrNull { it.movieTmdbId == movieTmdbId && it.state != DownloadState.Deleting } }
        /** Called as each row goes in, before it is there: what the places hold of it then. */
        var onInsert: (Download) -> Unit = {}

        override suspend fun insert(download: Download) {
            onInsert(download)
            rows.update { it + (download.id to download) }
        }

        /**
         * As Room's transaction ([DownloadDao.insertAll]): each row put back updated, then each inserted, and all of it kept,
         * or none where one of those writes fails.
         */
        override suspend fun insertAll(downloads: List<Download>, putBack: List<Download>) {
            putBack.forEach { write("update") }
            write("insertAll")
            downloads.forEach(onInsert)
            rows.update { all -> all + putBack.filter { it.id in all }.associateBy { it.id } + downloads.associateBy { it.id } }
        }

        override suspend fun update(download: Download) {
            write("update")
            rows.update { if (download.id in it) it + (download.id to download) else it }
        }

        override suspend fun progress(id: String, safeOffset: Long, gaps: List<DownloadGap>, updatedAt: Long) {
            write("progress")
            change(id) { it.copy(safeOffset = safeOffset, gaps = gaps, updatedAt = updatedAt) }
        }

        override suspend fun setState(id: String, state: DownloadState, reason: String?, updatedAt: Long) {
            write("setState")
            change(id) { it.copy(state = state, reason = reason, updatedAt = updatedAt) }
        }

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

        /** The season packs' copies, as DownloadStorage keeps them beside the bookkeeping (L394). */
        val packs = DownloadNzbs(File(files, ".packs"), Dispatchers.Unconfined)
        var there = true
        var reconciles = 0
        val events = mutableListOf<String>()

        fun bookkeeping(id: String) = File(files, id)
        fun videoFolder(id: String) = File(volume, "downloads/$id")
        fun video(id: String) = File(videoFolder(id), "v.mkv")
        fun part(id: String) = File(videoFolder(id), "v.mkv.part")

        /** The bookkeeping folders there are, by id: the season packs' copies left out. */
        fun folders(): Set<String> = files.listFiles().orEmpty().filter { it.isDirectory && it.name != ".packs" }.mapTo(HashSet()) { it.name }

        private fun mark(id: String) {
            bookkeeping(id).mkdirs()
            File(bookkeeping(id), DownloadStorage.MARKER).createNewFile()
        }

        override suspend fun add(row: Download, grab: DownloadStorage.Grab?) {
            mark(row.id)
            grab?.let { keepGrab(row.id, it) }
            dao.insert(row)
        }

        /** As DownloadStorage's: the folders and markers first, then the rows in one go; a failure takes the new folders back. */
        override suspend fun addAll(rows: List<Download>, putBack: List<Download>) {
            rows.forEach { mark(it.id) }
            try {
                dao.insertAll(rows, putBack)
            } catch (e: Exception) {
                rows.filter { dao.get(it.id) == null }.forEach { bookkeeping(it.id).deleteRecursively() }
                throw e
            }
        }

        override suspend fun keepGrab(id: String, grab: DownloadStorage.Grab) {
            events += "keep grab $id"
            File(bookkeeping(id), DownloadStorage.GRAB).writeBytes(grab.source.nzb)
            val password = File(bookkeeping(id), DownloadStorage.GRAB_PASSWORD)
            grab.source.password?.let(password::writeText) ?: password.delete()
            File(bookkeeping(id), DownloadStorage.GRAB_KEY).writeText(grab.key)
        }

        /** The NZB kept for [id]'s attempt, as DownloadStorage reads it back. */
        fun grab(id: String): DownloadStorage.Grab? {
            val key = File(bookkeeping(id), DownloadStorage.GRAB_KEY).takeIf { it.isFile }?.readText() ?: return null
            val nzb = File(bookkeeping(id), DownloadStorage.GRAB).readBytes()
            return DownloadStorage.Grab(key, DownloadStorage.Source(nzb, File(bookkeeping(id), DownloadStorage.GRAB_PASSWORD).takeIf { it.isFile }?.readText()))
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

        /** Called as an NZB is about to be dropped: where a cancel finds a drop on its way, as Android's I/O dispatch does. */
        var beforeDropSource: suspend () -> Unit = {}

        override suspend fun dropSource(id: String) {
            beforeDropSource()
            events += "drop source $id"
            listOf("nzb", "password", DownloadStorage.GRAB, DownloadStorage.GRAB_PASSWORD, DownloadStorage.GRAB_KEY).forEach { File(bookkeeping(id), it).delete() }
        }

        /** Called as a delete begins, before anything of it goes: a case's wait in the middle of a run's deletes. */
        var onDelete: suspend (id: String) -> Unit = {}

        override suspend fun delete(id: String) {
            events += "delete $id"
            onDelete(id)
            dao.setState(id, DownloadState.Deleting, null, clock.now())
            if (!there) return
            videoFolder(id).deleteRecursively()
            bookkeeping(id).deleteRecursively()
            dao.delete(id)
        }

        /** What the last reconcile was told is held (L413): those it leaves though they are being deleted. */
        var reconciledHeld: ((String) -> Boolean)? = null

        override suspend fun reconcile(held: (String) -> Boolean) {
            reconciles++
            events += "reconcile"
            reconciledHeld = held
            // As DownloadStorage's: the season packs' copies swept with the rest.
            packs.sweep(dao.all().first())
        }

        override suspend fun sweepPacks(removed: Set<String>) {
            events += "sweep packs"
            packs.sweep(dao.all().first(), removed)
        }

        override val copies get() = packs.copies

        override fun volumeThere() = there
    }

    private companion object {
        const val INDEXER = "fake-indexer"
        const val SHOW = 900_002

        /** A season pack of season 2 kept for its season's rows, and its NZB. */
        val PACK = DownloadNzbs.Meta(
            ReleaseKey.of(INDEXER, "hl-s02-pack"), "Harbor.Lights.S02.1080p.WEB.h264-PACK",
            DownloadCandidate(INDEXER, "hl-s02-pack", "Harbor.Lights.S02.1080p.WEB.h264-PACK", size = 8L * SIZE), SHOW, 2, leads = true,
        )
        val PACK_NZB = "<nzb><!-- hl-s02-pack --></nzb>".encodeToByteArray()
        const val NOW = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
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
        const val FILM = "dl-film"
        const val MOVIE = 990_719

        /** An imported NZB's id: the SHA-256 of its bytes. */
        const val NZB_SHA = "5b1c9d0e7f3a2b4c6d8e0f1a3b5c7d9e1f2a4b6c8d0e2f4a6b8c0d2e4f6a8b0c"
    }
}
