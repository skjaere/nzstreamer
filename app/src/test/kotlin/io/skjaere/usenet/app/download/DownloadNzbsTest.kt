package io.skjaere.usenet.app.download

import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.NzbMetadataResponse
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.logging.LogBuffer
import io.skjaere.usenet.releases.ReleaseKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A season pack's NZB kept once for its season (L394, L432, C1), in a temp folder as `downloads/.packs/` is laid out, the
 * rows given as the runner and reconcile read them: a copy written and read back; held while a row of its show and season
 * is still to finish, never by another show's or season's; dropped once none is, kept while one is held; a folder whose
 * `meta` isn't there is no copy and goes at the sweep with its NZB and password; and nothing on disk or in a log line is
 * named by the release's key, its title or its password. The release is invented.
 */
class DownloadNzbsTest {
    @TempDir
    lateinit var dir: File

    private val packs by lazy { DownloadNzbs(File(dir, DownloadNzbs.PATH), Dispatchers.Unconfined) }

    private val candidate = DownloadCandidate(INDEXER, GUID, TITLE, "http://indexer.invalid/api?t=get&id=$GUID&apikey={apikey}", 8_400_000_000L)
    private val key = ReleaseKey.of(INDEXER, GUID)
    private val meta = DownloadNzbs.Meta(key, TITLE, candidate, SHOW, 2, leads = true)
    private val nzb = "<nzb><!-- $GUID --></nzb>".encodeToByteArray()

    @Test
    fun `a copy is written and read back, its password with it`() = runBlocking<Unit> {
        assertNull(packs.find(key))
        packs.write(meta, nzb, PASSWORD)
        val copy = assertNotNull(packs.find(key))
        assertEquals(meta, copy.meta)
        assertContentEquals(nzb, copy.source.nzb)
        assertEquals(PASSWORD, copy.source.password)
        assertFalse(copy.toString().contains(PASSWORD))

        // Written again with none: no password left from the first.
        packs.write(meta.copy(leads = false), nzb, null)
        val again = assertNotNull(packs.find(key))
        assertNull(again.source.password)
        assertFalse(again.meta.leads)
        // Another key's is none.
        assertNull(packs.find(ReleaseKey.of(INDEXER, "hl-s02-other")))
    }

    @Test
    fun `a copy is held by a row of its show and season still to finish, never by another's`() = runBlocking<Unit> {
        packs.write(meta, nzb, PASSWORD)
        packs.sweep(listOf(row("e1", 2, 1, DownloadState.Queued)))
        assertNotNull(packs.find(key))

        // Another season of the show, another show's season 2, a film: none holds it.
        packs.sweep(
            listOf(
                row("e2", 3, 1, DownloadState.Queued),
                row("e3", 2, 1, DownloadState.Running).copy(showTmdbId = OTHER_SHOW, watchKey = WatchKey.episode(OTHER_SHOW, 2, 1)),
                Download(
                    id = "film", watchKey = WatchKey.movie(MOVIE), kind = DownloadKind.Movie, title = "Copper Crown", movieTmdbId = MOVIE, position = 3,
                    createdAt = 0, updatedAt = 0,
                ),
            ),
        )
        assertNull(packs.find(key))
        assertFalse(packs.folder(key).exists())
    }

    @Test
    fun `a copy is kept while a row of its season is held and dropped at the last end`() = runBlocking<Unit> {
        packs.write(meta, nzb, PASSWORD)
        val prepared = prepared()
        packs.hold(key, prepared)
        // Each state still to finish holds it.
        listOf(DownloadState.Queued, DownloadState.Resolving, DownloadState.Running, DownloadState.Paused, DownloadState.Waiting, DownloadState.Stopped).forEach { state ->
            packs.sweep(listOf(row("e1", 2, 1, DownloadState.Done), row("e2", 2, 2, state)))
            assertNotNull(packs.find(key), "with a row $state")
            assertSame(prepared, packs.held())
        }
        // Done, failed or being deleted: none holds it. The copy goes, its prepare with it.
        packs.sweep(listOf(row("e1", 2, 1, DownloadState.Done), row("e2", 2, 2, DownloadState.Failed), row("e3", 2, 3, DownloadState.Deleting)))
        assertNull(packs.find(key))
        assertFalse(packs.folder(key).exists())
        assertNull(packs.held())
    }

    /**
     * L379: a row of the season removed whose Undo can still bring it back holds the copy as one still to finish; another
     * season's doesn't, and once its Undo has gone (no longer among those removed) nothing holds it.
     */
    @Test
    fun `a copy is held by a removed row of its season while its Undo can bring it back`() = runBlocking<Unit> {
        packs.write(meta, nzb, PASSWORD)
        val rows = listOf(row("e1", 2, 1, DownloadState.Done), row("e2", 2, 2, DownloadState.Deleting), row("e3", 3, 1, DownloadState.Deleting))
        packs.sweep(rows, removed = setOf("e2", "e3"))
        assertNotNull(packs.find(key))
        packs.sweep(rows, removed = setOf("e3"))
        assertNull(packs.find(key))
    }

    /** A process that died between the NZB's write and the meta's leaves no copy, and nothing of it once swept. */
    @Test
    fun `a folder with no meta is no copy, and the sweep takes it with its NZB and password`() = runBlocking<Unit> {
        packs.write(meta, nzb, PASSWORD)
        val folder = packs.folder(key)
        File(folder, DownloadNzbs.META).delete()
        File(folder, "${DownloadNzbs.META}.tmp").writeText("{\"key\":")
        assertNull(packs.find(key))
        assertTrue(File(folder, DownloadNzbs.NZB).isFile && File(folder, DownloadNzbs.PASSWORD).isFile)
        // A meta that can't be read is none either.
        val garbled = packs.folder(ReleaseKey.of(INDEXER, "hl-s02-garbled")).apply { mkdirs() }
        File(garbled, DownloadNzbs.NZB).writeBytes(nzb)
        File(garbled, DownloadNzbs.META).writeText("not json")

        // A row of the season is still to finish: what has no meta goes all the same.
        packs.sweep(listOf(row("e1", 2, 1, DownloadState.Queued)))
        assertFalse(folder.exists())
        assertFalse(garbled.exists())
        assertEquals(emptyList(), File(dir, DownloadNzbs.PATH).list()!!.toList())
    }

    @Test
    fun `nothing on disk or in a log line is named by the key, the title or the password`() = runBlocking<Unit> {
        val from = LogBuffer.snapshot().lastOrNull()?.seq ?: 0L
        packs.write(meta, nzb, PASSWORD)
        val folder = packs.folder(key)
        // The folder is the key's SHA-256, its files named for what they are.
        assertEquals(sha256(key), folder.name)
        assertEquals(setOf(DownloadNzbs.NZB, DownloadNzbs.PASSWORD, DownloadNzbs.META), folder.list()!!.toSet())
        val names = File(dir, DownloadNzbs.PATH).walkTopDown().map { it.name }.toList()
        assertTrue(names.none { name -> listOf(key, GUID, TITLE, PASSWORD).any { it in name } }, names.toString())
        packs.sweep(emptyList())
        val lines = LogBuffer.snapshot().filter { it.seq > from }.map { it.message }
        assertEquals(emptyList(), lines.filter { line -> listOf(key, GUID, TITLE, PASSWORD, folder.name).any { it in line } })
        // Its lines, as it was kept and as it went, name it by the folder's first 8 hex digits.
        assertTrue(lines.count { folder.name.take(8) in it } >= 2, lines.toString())
    }

    private fun row(id: String, season: Int, episode: Int, state: DownloadState) = Download(
        id = id, watchKey = WatchKey.episode(SHOW, season, episode), kind = DownloadKind.Episode, title = "Harbor Lights", showTmdbId = SHOW,
        season = season, episode = episode, state = state, position = episode.toLong(), createdAt = 0, updatedAt = 0,
    )

    private fun prepared() = PreparedNzb(
        PreparedNzb.idFor(nzb), TITLE,
        ExtractedMetadata.Raw(NzbMetadataResponse(volumes = emptyList(), obfuscated = false, entries = emptyList()), NzbDocument(emptyList())), emptyList(),
    )

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val INDEXER = "fake-indexer"
        const val GUID = "hl-s02-pack"
        const val TITLE = "Harbor.Lights.S02.1080p.WEB.h264-PACK"
        const val PASSWORD = "s3cret-from-indexer"
        const val SHOW = 900002
        const val OTHER_SHOW = 900003
        const val MOVIE = 990719
    }
}
