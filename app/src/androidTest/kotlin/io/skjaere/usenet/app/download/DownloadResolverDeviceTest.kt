package io.skjaere.usenet.app.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.LocalNntp
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.download.DownloadFixtures.Packing
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.mockOnEngine
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.TriedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.NzbDownload
import io.skjaere.usenet.releases.ReleaseKey
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A download's Resolve on the device (item 5), the app's own pieces over the test's own NNTP server (LocalNntp) and the
 * engine: the full check, prepare, the track probe and the store. The releases' NZBs come through the coordinator's grab
 * (its test seam), so no indexer is asked. Three releases of an invented Harbor Lights S03E02, in download order: the first
 * names an article the server never had (STAT 430) where only a check of every article finds it, the second is a pack of
 * two episodes, the third is the episode alone.
 * The first fails its check; the pack's S03E02 is opened and probed, its video and tracks kept with the row; nothing is
 * grabbed after it. The test assets' film is the episodes' bytes, as the probe reads a real file.
 *
 * The row, its folders and what the releases' checks left in the memory of releases found missing are put back as they
 * were; the server ends with its articles.
 */
@RunWith(AndroidJUnit4::class)
class DownloadResolverDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val fixtures = DownloadFixtures()
    private val storage = DownloadStorage.of(context, container.data.downloads)
    private val realFetch = PlaybackCoordinator.fetchNzb

    /** The coordinator's grabs, by guid, in order. */
    private val grabbed = CopyOnWriteArrayList<String>()
    private val nzbs = mutableMapOf<String, ByteArray>()
    private lateinit var remembered: List<TriedRelease>
    private var row: Download? = null

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
        PlaybackCoordinator.fetchNzb = realFetch
        row?.let { storage.delete(it.id) }
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

        /** Never asked: the grabs come through the coordinator's seam. */
        val INDEXER = IndexerConfig("dl-resolver-test", "Test indexer", "http://indexer.invalid/api", "test-key")
    }
}
