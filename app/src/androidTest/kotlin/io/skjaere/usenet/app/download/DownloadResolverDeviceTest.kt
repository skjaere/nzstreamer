package io.skjaere.usenet.app.download

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.usenet.app.LocalNntp
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.download.DownloadFixtures.Packing
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.mockOnEngine
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.core.EnginePrepareBackend
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.core.PrepareBackend
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.TrackProbe
import io.skjaere.usenet.data.Clock
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.TriedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.NzbDownload
import io.skjaere.usenet.releases.ReleaseKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A download's Resolve on the device (item 5), the app's own pieces over the test's own NNTP server (LocalNntp) and the
 * engine: the full check, prepare, the track probe and the store. The releases' NZBs come through the coordinator's grab
 * (its test seam), so no indexer is asked. Three releases of an invented Harbor Lights S03E02, in download order: the first
 * names an article the server never had (STAT 430) where only a check of every article finds it, the second is a pack of
 * two episodes, the third is the episode alone.
 * The first fails its check; the pack's S03E02 is opened and probed, its video and tracks kept with the row; nothing is
 * grabbed after it. The test assets' film is the episodes' bytes, as the probe reads a real file.
 *
 * A season pack fetched once for its season (L394, M-pack): three episodes, each a split RAR4 set, posted as one release,
 * and three rows of the season, each with that release alone: the first grabs it and prepares it, the others take it from
 * the season's copy and the prepare held for it, through the app's runner, each file written whole. What the prepare read
 * is said through the instrumentation's status, never the log.
 *
 * The rows, their folders, the season's copy and what the releases' checks left in the memory of releases found missing
 * are put back as they were; the server ends with its articles.
 */
@RunWith(AndroidJUnit4::class)
class DownloadResolverDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val fixtures = DownloadFixtures()
    private val storage = DownloadStorage.of(context, container.data.downloads)
    private val realFetch = PlaybackCoordinator.fetchNzb

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The coordinator's grabs, by guid, in order. */
    private val grabbed = CopyOnWriteArrayList<String>()
    private val nzbs = ConcurrentHashMap<String, ByteArray>()
    private lateinit var remembered: List<TriedRelease>
    private var row: Download? = null
    private val made = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() = runBlocking<Unit> {
        engineSetup("the engine on the test's own NNTP server") {
            mockOnEngine()
            container.engine.configure(EngineSettings(listOf(fixtures.local.provider)))
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
        PlaybackCoordinator.fetchNzb = realFetch
        row?.let { storage.delete(it.id) }
        made.forEach { storage.delete(it) }
        storage.packs.folder(ReleaseKey.of(INDEXER.id, SEASON_PACK)).deleteRecursively()
        container.library.clearTriedReleases()
        container.library.restoreTriedReleases(remembered)
        fixtures.close()
    }

    @Test
    fun aReleaseMissingAnArticleFallsToAPackWhoseEpisodeIsOpenedProbedAndKept() = runBlocking<Unit> {
        val film = DownloadFixtures.media("chapters.mkv")

        // The episode alone, larger than the play's sample of 256 articles, its NZB naming one article the server never
        // had: STAT answers 430 for it. It is the second, which the sample's spread passes over (0, 3, 7, …, and the ends):
        // only the full check finds it (L341).
        val first = fixtures.post(
            Packing.Plain, "Harbor.Lights.S03E02.1080p.WEB.h264-KESTREL", DownloadFixtures.seeded(16 * DownloadFixtures.MB, System.nanoTime()), partSize = SMALL_PART,
        )
        assertTrue("${first.ids.size} articles", first.ids.size > 2 * 256)
        val gone = "lnntp-never-posted-${first.ids[1].substringAfter("lnntp-")}"
        nzbs[FIRST] = first.nzb.decodeToString().replace(">${first.ids[1]}<", ">$gone<").encodeToByteArray()
        assertFalse(nzbs.getValue(FIRST).contentEquals(first.nzb))

        // A pack of two episodes, the second the one wanted.
        val episodes = (1..2).map { e -> post("Harbor.Lights.S03E%02d.1080p.WEB.h264-GRP.mkv".format(e), film) }
        nzbs[PACK] = fixtures.local.nzb(*episodes.toTypedArray())

        // The episode alone again, whole: never reached.
        nzbs[THIRD] = fixtures.post(Packing.Plain, "Harbor.Lights.S03E02.720p.WEB.h264-WREN", film, partSize = PART).nzb

        val added = Download(
            id = Download.newId(), watchKey = WatchKey.episode(SHOW, 3, 2), kind = DownloadKind.Episode, title = "Harbor Lights", showTmdbId = SHOW,
            season = 3, episode = 2, seasonEpisodes = 8, episodeName = "Slack Tide", position = 0,
            candidates = listOf(
                DownloadCandidate(INDEXER.id, FIRST, "Harbor.Lights.S03E02.1080p.WEB.h264-KESTREL", size = first.size),
                DownloadCandidate(INDEXER.id, PACK, "Harbor.Lights.S03.1080p.WEB.h264-GRP", size = 2L * film.size),
                DownloadCandidate(INDEXER.id, THIRD, "Harbor.Lights.S03E02.720p.WEB.h264-WREN", size = film.size.toLong()),
            ),
            indexerSize = film.size.toLong(), createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
        )
        storage.add(added)
        row = added

        val resolver = DownloadResolver.of(container, storage, indexers = { listOf(INDEXER) }, attempts = { 3 })
        val ready = withTimeout(120_000) { resolver.resolve(added.id) } as? DownloadResolver.Resolution.Ready
            ?: throw AssertionError("not resolved: ${container.data.downloads.get(added.id)?.let { "${it.state}, ${it.reason}" }}")

        // The first failed its full check, before anything of it was fetched; the pack played its part, and the third was
        // never grabbed.
        assertEquals(listOf(FIRST, PACK), grabbed)
        assertEquals("BODYs of the first's articles", 0, first.ids.sumOf { fixtures.local.bodies[it]?.get() ?: 0 })
        assertEquals("Harbor.Lights.S03E02.1080p.WEB.h264-GRP.mkv", ready.file.name)
        assertEquals(film.size.toLong(), ready.file.size)

        val kept = container.data.downloads.get(added.id) ?: throw AssertionError("the row is gone")
        assertEquals(DownloadState.Resolving, kept.state)
        assertEquals(PACK, kept.release?.guid)
        assertEquals("Harbor.Lights.S03.1080p.WEB.h264-GRP", kept.release?.title)
        assertEquals("Harbor.Lights.S03E02.1080p.WEB.h264-GRP.mkv", kept.fileName)
        assertEquals(film.size.toLong(), kept.size)
        // One of the pack's two videos: a gap in it is no word on its other episode.
        assertFalse(kept.oneVideo)
        assertEquals(listOf(ReleaseKey.of(INDEXER.id, FIRST), ReleaseKey.of(INDEXER.id, PACK)), kept.tried)

        // What the probe read of the episode, as the launch keeps it: the film's 10 fps over six minutes, its picture and sound.
        val video = DownloadResolver.video(kept) ?: throw AssertionError("no video kept")
        assertEquals(10.0, video.fps!!, 1e-6)
        assertTrue("$video", video.durationMs!! in 359_000L..361_000L)
        val tracks = DownloadResolver.tracks(kept) ?: throw AssertionError("no tracks kept")
        assertTrue("$tracks", tracks.video.isNotEmpty() && tracks.audio.isNotEmpty())

        // The pack's NZB is the download's own, and not one of the store's (L383).
        assertArrayEquals(nzbs.getValue(PACK), (storage.source(added.id) ?: throw AssertionError("no NZB kept")).nzb)
        val id = PreparedNzb.idFor(nzbs.getValue(PACK))
        assertNull(container.store.get(id))
        assertNull(container.store.stored(id))

        // The first is remembered as missing on the server, as a play remembers it.
        assertTrue(container.library.triedReleases(listOf(ReleaseKey.of(INDEXER.id, FIRST))).isNotEmpty())
    }

    /**
     * L394: a season pack is grabbed and prepared once for its season's rows. Harbor Lights season 4 as one release of three
     * episodes, each a split RAR4 set as season packs post them (its volumes named for its episode), fresh bytes under ids
     * no run used; three rows of the season queued with it as their one release, run by the app's runner over the app's
     * Resolve. The first grabs it through the coordinator's seam and prepares it, and keeps it for the season; the second and
     * third take it from that copy, with no grab, and from the prepare held for it, with none: one grab and one prepare in all.
     * Each file is its episode's, whole. Once the season's last is done, its copy is gone.
     *
     * M-pack: what the prepare read from the server (its BODYs times their articles' sizes) during each row's Resolve, the
     * first time and with the held prepare, said through the instrumentation's status.
     */
    @Test
    fun aSeasonPackIsGrabbedAndPreparedOnceForItsSeasonsRowsEachFileWhole() = runBlocking<Unit> {
        val salt = System.nanoTime()
        val episodes = (1..3).map { e ->
            fixtures.post(Packing.Rar4, "Harbor.Lights.S04E%02d.1080p.WEB.h264-PACK".format(e), DownloadFixtures.seeded(16 * DownloadFixtures.MB, salt + e), salt = "$salt")
        }
        nzbs[SEASON_PACK] = fixtures.local.nzb(*episodes.flatMap { it.posted }.toTypedArray())
        val candidate = DownloadCandidate(INDEXER.id, SEASON_PACK, "Harbor.Lights.S04.1080p.WEB.h264-PACK", size = episodes.sumOf { it.size })

        // The engine's prepare, counted.
        val prepares = AtomicInteger()
        val engine = EnginePrepareBackend(container.engine)
        val counted = object : PrepareBackend {
            override suspend fun prepare(nzbBytes: ByteArray, password: String?) = engine.prepare(nzbBytes, password).also { prepares.incrementAndGet() }
            override suspend fun streamableFiles(metadata: ExtractedMetadata) = engine.streamableFiles(metadata)
        }
        val resolver = DownloadResolver.of(container, storage, indexers = { listOf(INDEXER) }, attempts = { 3 }, probe = TrackProbe.None, prepareBackend = counted)
        // What the server sent during each row's Resolve, which runs alone: the walk resolves a row once the one before is written.
        val read = ConcurrentHashMap<String, Long>()
        fun sent() = episodes.flatMap { it.posted }.sumOf { posted -> posted.ids.indices.sumOf { i -> (fixtures.local.bodies[posted.ids[i]]?.get() ?: 0) * posted.sizes[i].toLong() } }
        val runner = DownloadRunner(
            DownloadRunnerDeps.of(
                container, context, storage, DownloadDebug.On(fixtures.file("debug"), Clock.System),
                resolve = { id ->
                    val before = sent()
                    resolver.resolve(id).also { read.merge(id, sent() - before) { a, b -> a + b } }
                },
            ),
            scope,
        )
        val ids = (1..3).map { e ->
            val queued = runner.enqueue(DownloadTarget.episode(SHOW, "Harbor Lights", 4, e, seasonEpisodes = 3), listOf(candidate))
                ?: throw AssertionError("episode $e not queued")
            made += queued.id
            queued.id
        }
        scope.launch { runner.run(DownloadHost.Job) }

        val done = ids.map { id ->
            waitFor("download $id done", 120_000) { runner.state.value.firstOrNull { it.row.id == id }?.row?.takeIf { it.state == DownloadState.Done || it.state == DownloadState.Failed } }
                .also { assertEquals("${it.state}, ${it.reason}", DownloadState.Done, it.state) }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            STATUS, Bundle().apply { putString(M_PACK, "first prepare ${read[ids[0]]} bytes; with the held prepare ${read[ids[1]]} and ${read[ids[2]]} bytes") },
        )
        assertEquals(listOf(SEASON_PACK), grabbed)
        assertEquals("prepares", 1, prepares.get())
        done.forEachIndexed { i, row ->
            assertEquals("Harbor.Lights.S04E%02d.1080p.WEB.h264-PACK.mkv".format(i + 1), row.fileName)
            assertEquals(listOf(ReleaseKey.of(INDEXER.id, SEASON_PACK)), row.tried)
            assertEquals(episodes[i].sha256, DownloadFixtures.sha256(storage.file(row) ?: throw AssertionError("no file for ${row.id}")))
        }
        // The season's last is done: its copy went with it.
        val copy = storage.packs.folder(ReleaseKey.of(INDEXER.id, SEASON_PACK))
        waitFor("the season's copy gone", 10_000) { true.takeIf { !copy.exists() } }
        assertNull(storage.packs.find(ReleaseKey.of(INDEXER.id, SEASON_PACK)))
    }

    /** [data] posted as the file [name], its articles' ids a digest of its name, bytes and part size, as the fixtures' are. */
    private fun post(name: String, data: ByteArray): LocalNntp.Posted {
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(name.toByteArray())
            update(data)
        }.digest().joinToString("") { "%02x".format(it) }
        return fixtures.local.post(name, data, PART, "dlr-${digest.take(16)}-$PART")
    }

    private companion object {
        const val PART = 64 * 1024

        /** The first release's articles: 16 MB in 977 of them. */
        const val SMALL_PART = 16 * 1024
        const val SHOW = 900_731
        const val FIRST = "hl-s03e02-kestrel"
        const val PACK = "hl-s03-pack-grp"
        const val THIRD = "hl-s03e02-wren"
        const val SEASON_PACK = "hl-s04-pack"

        /** The instrumentation status's code for a measurement: 2, "in progress", which no result parser counts as a test. */
        const val STATUS = 2

        /** Its key for M-pack. */
        const val M_PACK = "m-pack"

        /** Never asked: the grabs come through the coordinator's seam. */
        val INDEXER = IndexerConfig("dl-resolver-test", "Test indexer", "http://indexer.invalid/api", "test-key")
    }
}
