package io.skjaere.usenet.app.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.download.DownloadFixtures.Packing
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.mockOnEngine
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.core.DownloadWriter
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.core.TrackProbe
import io.skjaere.usenet.data.Clock
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadGap
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.TriedRelease
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.NzbDownload
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseKey
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.FindResult
import io.skjaere.usenet.search.FinderEvent
import io.skjaere.usenet.search.FoundRelease
import io.skjaere.usenet.search.IndexerReport
import io.skjaere.usenet.search.SearchStage
import io.skjaere.usenet.search.StageReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The downloads' runner on a device (item 7): the app's Resolve, its own writer and places over the test's own NNTP server
 * (LocalNntp, throttled to about a provider's rate on each connection) and the engine, the releases' NZBs through the
 * coordinator's grab (its test seam). No host exists yet (WF2b), so the test is the host: it calls [DownloadRunner.run]
 * and waits for it to end.
 * - Two queued: the second starts once the first is done; Pause stops it at its safe offset and the walk ends; Resume and
 *   a walk again take it on from there, from its own NZB with no grab.
 * - A release whose articles go missing from part way on (ids made missing mid-run, the long gap given small) is given up
 *   for the next, its file and NZB gone, and remembered as missing on the server.
 * - A release missing two small articles finishes as "Downloaded · 2 parts missing", their bytes recorded as its gaps.
 *
 * The releases are seeded bytes, not a film, so Resolve's probe reads nothing (the resolver's device test probes a film);
 * each is fresh, so no segment of it is in the video cache. The rows and their files, and what the memory of releases
 * found missing gained, are put back after; the server ends with its articles.
 */
@RunWith(AndroidJUnit4::class)
class DownloadRunnerDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val fixtures = DownloadFixtures()
    private val local get() = fixtures.local
    private val storage = container.downloadStorage
    private val realFetch = PlaybackCoordinator.fetchNzb
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val debug = DownloadDebug.On(fixtures.file("debug"), Clock.System)

    /** The coordinator's grabs, by guid, in order. */
    private val grabbed = CopyOnWriteArrayList<String>()
    private val nzbs = ConcurrentHashMap<String, ByteArray>()
    private val made = CopyOnWriteArrayList<String>()
    private lateinit var remembered: List<TriedRelease>

    @Before
    fun setUp() = runBlocking<Unit> {
        engineSetup("the engine on the test's own NNTP server") {
            mockOnEngine()
            container.engine.configure(EngineSettings(listOf(local.provider)))
        }
        remembered = container.library.triedReleaseEntries()
        PlaybackCoordinator.fetchNzb = { _, _, item ->
            grabbed += item.guid
            NzbDownload(nzbs.getValue(item.guid))
        }
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        scope.cancel()
        local.throttle = null
        local.missing.clear()
        PlaybackCoordinator.fetchNzb = realFetch
        made.forEach { storage.delete(it) }
        container.library.clearTriedReleases()
        container.library.restoreTriedReleases(remembered)
        fixtures.close()
    }

    /** What the turn's search finds for each episode of the season, by its number: the test's finder (L392). */
    private val finds = ConcurrentHashMap<Int, List<FoundRelease>>()

    /** The episodes the turn's search asked for, in order. */
    private val searched = CopyOnWriteArrayList<Int>()

    /**
     * The runner of the app's pieces, the app's own writer among them, its long gap [longGap]; the turn's search over the
     * test's finder ([finds]), the indexer answering, so no indexer is asked.
     */
    private fun runner(longGap: Long = DownloadWriter.LONG_GAP_BYTES): DownloadRunner {
        val search = DownloadSearch(
            downloads = container.data.downloads,
            find = { request, _, _ ->
                flow {
                    searched += request.episode
                    val report = IndexerReport(INDEXER, "TVDB id", found = finds[request.episode].orEmpty().size)
                    emit(FinderEvent.Finished(FindResult(request, finds[request.episode].orEmpty(), emptyList(), listOf(StageReport(SearchStage.Episode, listOf(report))))))
                }
            },
            indexers = { listOf(INDEXER) },
            context = container::downloadRankingContext,
            space = { id -> storage.space(id) },
        )
        val resolver = DownloadResolver.of(container, storage, indexers = { listOf(INDEXER) }, attempts = { 3 }, probe = TrackProbe.None, search = search)
        return DownloadRunner(DownloadRunnerDeps.of(container, context, storage, debug, resolve = resolver::resolve, longGapBytes = longGap), scope)
    }

    /** [candidate] as the finder gives it, of its indexer. */
    private fun found(candidate: DownloadCandidate): FoundRelease {
        val item = NewznabItem(
            indexerId = candidate.indexerId, guid = candidate.guid, title = candidate.title, sizeBytes = candidate.size, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null, tvmazeId = null,
            season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        )
        val ranked = RankedRelease(ReleaseFacts(item.key, item.title, item.sizeBytes), ReleaseParser.parse(item.title), playable = true, score = 1.0, reasons = emptyList(), estimatedBitrate = null)
        return FoundRelease(item, ranked, SearchStage.Episode)
    }

    /** Download [id] while it is written, at [bytes] or more: its live write's, as the page reads it. */
    private suspend fun DownloadRunner.underWay(id: String, bytes: Long = 0) =
        waitFor("download $id under way at $bytes bytes", 60_000) { item(id)?.takeIf { it.bytesPerSecond != null && it.written >= bytes } }

    /** [bytes] posted as the plain release [guid], its NZB the coordinator's grab hands over. */
    private fun release(guid: String, title: String, bytes: Int): Pair<DownloadFixtures.Release, DownloadCandidate> {
        val release = fixtures.post(Packing.Plain, title, DownloadFixtures.seeded(bytes, fresh()))
        nzbs[guid] = release.nzb
        return release to DownloadCandidate(INDEXER.id, guid, title, size = release.size)
    }

    private suspend fun DownloadRunner.add(episode: Int, vararg candidates: DownloadCandidate): String {
        val row = enqueue(DownloadTarget.episode(SHOW, "Harbor Lights", 3, episode, seasonEpisodes = 8), candidates.toList()).must("queued")
        made += row.id
        return row.id
    }

    /** A test's host: the walk, until nothing can run. */
    private fun DownloadRunner.walk(): Job = scope.launch { run(DownloadHost.Job) }

    private fun DownloadRunner.item(id: String) = state.value.firstOrNull { it.row.id == id }

    private suspend fun DownloadRunner.done(id: String, timeoutMs: Long = 120_000): Download =
        waitFor("download $id done", timeoutMs) { item(id)?.row?.takeIf { it.state == DownloadState.Done || it.state == DownloadState.Failed } }
            .also { assertEquals("${it.state}, ${it.reason}", DownloadState.Done, it.state) }

    private fun <T : Any> T?.must(what: String): T = this ?: throw AssertionError("no $what")

    private fun traced(): List<String> = File(fixtures.file("debug"), DownloadDebug.TRACE).takeIf { it.isFile }?.readLines().orEmpty()

    /** Each stream's first byte for download [id], as the trace says them, in order. */
    private fun opens(id: String) = traced().mapNotNull { line -> REOPEN.find(line)?.takeIf { it.groupValues[1] == id }?.groupValues?.get(2)?.toLong() }

    @Test
    fun twoQueuedTheSecondStartsWhenTheFirstIsDoneAndPauseAndResumeGoOnAcrossAWalksEnd() = runBlocking<Unit> {
        val (one, first) = release(FIRST, "Harbor.Lights.S03E01.1080p.WEB-DL.H.264-KESTREL", 16 * DownloadFixtures.MB)
        val (two, second) = release(SECOND, "Harbor.Lights.S03E02.1080p.WEB-DL.H.264-KESTREL", 16 * DownloadFixtures.MB)
        val cache = waitFor("the video cache to be open", timeoutMs = 10_000) { container.videoCache.forStreamer() }
        // The streamer built from here on is lent it.
        container.engine.rebuildWhenIdle()
        local.throttle = THROTTLE
        val runner = runner()
        val a = runner.add(1, first)
        val b = runner.add(2, second)
        val walk = runner.walk()

        runner.done(a)
        runner.underWay(b, 2L * DownloadFixtures.MB)
        assertEquals(DownloadState.Done, runner.item(a)?.row?.state)
        // Its first stream opened only once the first was done, whose progress and end the trace says (the pairs wait on them).
        val lines = traced()
        val done = lines.indexOfFirst { it.endsWith("done id=$a size=${one.size}") }
        assertTrue(lines.toString(), done >= 0 && lines.indexOfFirst { it.contains("reopen id=$b ") } > done)
        assertTrue(lines.toString(), lines.take(done).any { it.endsWith("progress id=$a offset=${one.size} size=${one.size}") })

        runner.pause()
        withTimeout(30_000) { walk.join() }
        val paused = container.data.downloads.get(b).must("the paused row")
        assertEquals(DownloadState.Paused, paused.state)
        assertTrue("paused at ${paused.safeOffset}", paused.safeOffset in 1 until two.size)
        // Its file and room kept.
        assertEquals(two.size, storage.file(paused)?.let { File(it.path + DownloadStorage.PART) }.must("its file").length())

        runner.resume()
        assertEquals(DownloadState.Queued, container.data.downloads.get(b)?.state)
        runner.walk()
        runner.done(b)
        // On from where it stopped, from its own NZB: no grab of it again.
        assertEquals(listOf(0L, paused.safeOffset), opens(b))
        assertEquals(listOf(FIRST, SECOND), grabbed)
        listOf(a to one, b to two).forEach { (id, release) ->
            val row = container.data.downloads.get(id).must("row $id")
            assertEquals(release.sha256, DownloadFixtures.sha256(storage.file(row).must("its file")))
            assertNull(storage.source(id))
        }
        // The app's writer fetches as a download does (L343, M3): the video cache read, never added to. Resolve's prepare
        // reads a file's first and last articles through it, as a play's does: none of what lies between is there.
        listOf(one, two).forEach { release ->
            val between = release.posted.single().ids.drop(2).dropLast(2)
            val cached = between.count { cache.get(it) != null }
            assertEquals("of the ${between.size} articles inside ${release.title}, in the video cache", 0, cached)
        }
    }

    @Test
    fun aReleaseMissingFromPartWayOnIsGivenUpForTheNextItsFilesGone() = runBlocking<Unit> {
        // The first is larger: a file of it left behind would keep its length under the next's.
        val (gone, first) = release(FIRST, "Harbor.Lights.S03E03.1080p.WEB-DL.H.264-KESTREL", 20 * DownloadFixtures.MB)
        val (whole, second) = release(SECOND, "Harbor.Lights.S03E03.720p.WEB-DL.H.264-GRP", 16 * DownloadFixtures.MB)
        local.throttle = THROTTLE
        val runner = runner(longGap = LONG_GAP)
        val id = runner.add(3, first, second)
        runner.walk()
        runner.underWay(id, DownloadFixtures.MB.toLong())
        // From article K on, no provider has it any more.
        val posted = gone.posted.single()
        local.missing += posted.ids.drop(FROM_ARTICLE)

        val done = runner.done(id)
        assertEquals(listOf(FIRST, SECOND), grabbed)
        assertEquals(SECOND, done.release?.guid)
        assertEquals(listOf(ReleaseKey.of(INDEXER.id, FIRST), ReleaseKey.of(INDEXER.id, SECOND)), done.tried)
        // The first's file went before the second's was made: this is the second's alone, at its size.
        val file = storage.file(done).must("its file")
        assertEquals(whole.size, file.length())
        assertEquals(whole.sha256, DownloadFixtures.sha256(file))
        assertEquals(emptyList<DownloadGap>(), done.gaps)
        assertEquals(listOf(0L, 0L), opens(id).filter { it == 0L })
        assertNull(storage.source(id))
        // Remembered as missing on the server, as a play remembers a release of one video.
        assertTrue(container.library.triedReleases(listOf(ReleaseKey.of(INDEXER.id, FIRST))).isNotEmpty())
    }

    @Test
    fun aReleaseMissingTwoSmallArticlesFinishesWithTwoPartsMissingItsGapsRecorded() = runBlocking<Unit> {
        val (release, candidate) = release(FIRST, "Harbor.Lights.S03E04.1080p.WEB-DL.H.264-KESTREL", 16 * DownloadFixtures.MB)
        local.throttle = THROTTLE
        val runner = runner()
        val id = runner.add(4, candidate)
        runner.walk()
        runner.underWay(id)
        val posted = release.posted.single()
        val lost = listOf(posted.ids[FIRST_LOST], posted.ids[SECOND_LOST])
        local.missing += lost

        val done = runner.done(id)
        val gaps = lost.map { posted.bytesOf(it) }
        assertEquals(gaps.map { DownloadGap(it.first, it.last) }, done.gaps)
        val words = DownloadHostRules.words(done).must("its words").text
        assertTrue(words, words.startsWith("Downloaded · 2 parts missing"))
        // Zeros where the two were, the source's bytes everywhere else.
        val bytes = storage.file(done).must("its file").readBytes()
        val expected = release.source.copyOf().also { source -> gaps.forEach { source.fill(0, it.first.toInt(), it.last.toInt() + 1) } }
        assertArrayEquals(expected, bytes)
        // Each was asked for once more at the end, and still had nothing.
        lost.forEach { assertTrue("$it asked for ${local.bodies[it]?.get()} times", (local.bodies[it]?.get() ?: 0) >= 2) }
    }

    /**
     * L392, L398: a season's three rows, queued at once, go in their order, each searched at its turn by its facts; the
     * middle one, whose search finds nothing, fails "No playable release found" with nothing grabbed, and the third runs
     * after it.
     */
    @Test
    fun aSeasonsRowsGoInOrderAndOneWhoseTurnFindsNothingFailsWhileTheNextRuns() = runBlocking<Unit> {
        val (one, first) = release(FIRST, "Harbor.Lights.S03E05.1080p.WEB-DL.H.264-KESTREL", 16 * DownloadFixtures.MB)
        val (three, third) = release(THIRD, "Harbor.Lights.S03E07.1080p.WEB-DL.H.264-KESTREL", 16 * DownloadFixtures.MB)
        finds[5] = listOf(found(first))
        finds[7] = listOf(found(third))
        local.throttle = THROTTLE
        val runner = runner()
        val facts = SeasonSearch("Harbor Lights", 2021, tvdb = 900_734, tmdb = SHOW, seasonEpisodes = 8)
        fun item(episode: Int, vararg candidates: DownloadCandidate) =
            DownloadRunner.QueueItem(DownloadTarget.episode(SHOW, "Harbor Lights", 3, episode, seasonEpisodes = 8), candidates.toList(), search = facts)
        val queued = runner.enqueueAll(listOf(item(5, first), item(6), item(7, third)))
        made += queued.map { it.id }
        val (a, b, c) = queued.map { it.id }
        runner.walk()

        runner.done(a)
        runner.done(c)
        val nothing = container.data.downloads.get(b).must("the middle row")
        assertEquals(DownloadState.Failed, nothing.state)
        assertEquals("No playable release found", nothing.reason)
        assertEquals(emptyList<String>(), nothing.tried)
        // Each searched once, in the queue's order; the middle one before the third ran.
        assertEquals(listOf(5, 6, 7), searched)
        assertEquals(listOf(FIRST, THIRD), grabbed)
        val failedAt = nothing.updatedAt
        val third0 = traced().firstOrNull { it.contains("reopen id=$c ") }.must("the third's first stream")
        assertTrue("the third opened at ${third0.substringBefore(' ')}, the middle failed at $failedAt", third0.substringBefore(' ').toLong() >= failedAt)
        listOf(a to one, c to three).forEach { (id, release) ->
            val row = container.data.downloads.get(id).must("row $id")
            assertEquals(release.sha256, DownloadFixtures.sha256(storage.file(row).must("its file")))
        }
    }

    private companion object {
        const val SHOW = 900_733
        const val FIRST = "hl-runner-kestrel"
        const val SECOND = "hl-runner-grp"
        const val THIRD = "hl-runner-wren"

        /** Never asked: the grabs come through the coordinator's seam. */
        val INDEXER = IndexerConfig("dl-runner-test", "Test indexer", "http://indexer.invalid/api", "test-key")

        /** Each connection's bytes a second: a 16 MB release takes about 4 s on the server's 4 connections. */
        const val THROTTLE = 1_000_000L

        /** The long gap, given small: 4 of the fixtures' 512 KiB articles. */
        const val LONG_GAP = 2L * 1024 * 1024

        /** The first article no provider has from then on: 8 MiB into a 20 MB file. */
        const val FROM_ARTICLE = 16

        /** The two small articles lost part way through: about 10 MB and 13 MB into the file. */
        const val FIRST_LOST = 20
        const val SECOND_LOST = 26

        val REOPEN = Regex("""reopen id=(\S+) offset=(\d+)""")

        /** A seed no run used before: no segment of its bytes is in the video cache. */
        fun fresh(): Long = System.nanoTime()
    }
}
