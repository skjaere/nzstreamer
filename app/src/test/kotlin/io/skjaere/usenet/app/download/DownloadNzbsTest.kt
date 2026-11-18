package io.skjaere.usenet.app.download

import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.NzbMetadataResponse
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.usenet.app.play.IMPORTED
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.logging.LogBuffer
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
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
        assertContentEquals(nzb, assertNotNull(copy.source).nzb)
        assertEquals(PASSWORD, assertNotNull(copy.source).password)
        assertFalse(copy.toString().contains(PASSWORD))

        // Written again with none: no password left from the first.
        packs.write(meta.copy(leads = false), nzb, null)
        val again = assertNotNull(packs.find(key))
        assertNull(assertNotNull(again.source).password)
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

    /**
     * L423: an imported NZB's copy, keyed by Import's own release of it ([IMPORTED], the NZB's id) and held by that NZB,
     * written and read back with the password typed for it; no show, no season, never leading. A copy a DN2a build wrote
     * (no `nzb`, its show and season numbers) still reads as it was.
     */
    @Test
    fun `an imported NZB's copy is written and read back, and one written before it still reads`() = runBlocking<Unit> {
        packs.write(imported, IMPORT_NZB, TYPED)
        val copy = assertNotNull(packs.find(importKey))
        assertEquals(imported, copy.meta)
        assertEquals(DownloadNzbs.Meta(importKey, IMPORT_TITLE, importCandidate, show = null, season = null, leads = false, nzb = NZB_SHA), copy.meta)
        assertContentEquals(IMPORT_NZB, assertNotNull(copy.source).nzb)
        assertEquals(TYPED, assertNotNull(copy.source).password)
        assertFalse(TYPED in copy.toString())

        // A season pack's copy as DN2a wrote its meta: read as before, held by no NZB.
        val folder = packs.folder(key).apply { mkdirs() }
        File(folder, DownloadNzbs.NZB).writeBytes(nzb)
        File(folder, DownloadNzbs.META).writeText(
            """{"key":"$key","title":"$TITLE","candidate":{"indexerId":"$INDEXER","guid":"$GUID","title":"$TITLE","size":8400000000},"show":$SHOW,"season":2,"leads":true}""",
        )
        val old = assertNotNull(packs.find(key)).meta
        assertEquals(DownloadNzbs.Meta(key, TITLE, candidate.copy(downloadTemplate = null), SHOW, 2, leads = true), old)
        assertNull(old.nzb)
    }

    /**
     * L456, L423: an imported NZB's copy is the one source of each of its files still to download or failed. Another of
     * its files queued, or one put back, with the same NZB and the password typed for it, leaves the copy as it was
     * written, never taken away for a moment to be written again: a write that failed part way, or a process that died in
     * it, would leave those files with no copy. Asked for with another password, it is written again.
     */
    @Test
    fun `an imported NZB's copy kept already is left as it was written for another of its files`() = runBlocking<Unit> {
        packs.write(imported, IMPORT_NZB, TYPED)
        // Anything written now fails part way: where the NZB is written whole, a folder is in the way.
        val inTheWay = File(packs.folder(importKey), DownloadNzbs.NZB + ".tmp").apply { mkdirs() }
        val second = DownloadNzbs.Meta.imported(importCandidate.copy(size = 1_400_000_000))
        val wrote = runCatching { packs.write(second, IMPORT_NZB, TYPED) }
        assertTrue(wrote.isSuccess, "written again: ${wrote.exceptionOrNull()}")
        val copy = assertNotNull(packs.find(importKey), "the copy taken away")
        assertEquals(imported, copy.meta)
        assertContentEquals(IMPORT_NZB, assertNotNull(copy.source).nzb)
        assertEquals(TYPED, assertNotNull(copy.source).password)
        assertEquals(listOf(imported), packs.copies.value)

        assertTrue(inTheWay.delete())
        packs.write(second, IMPORT_NZB, "typed-again")
        val again = assertNotNull(packs.find(importKey))
        assertEquals(second, again.meta)
        assertEquals("typed-again", assertNotNull(again.source).password)
    }

    /** L423: a season's later rows never take an imported NZB's copy as their season's pack, whatever its files are. */
    @Test
    fun `a season's copies never include an imported NZB's`() = runBlocking<Unit> {
        packs.write(meta, nzb, PASSWORD)
        packs.write(imported, IMPORT_NZB, TYPED)
        assertEquals(listOf(key), packs.forSeason(SHOW, 2).map { it.meta.key })
        assertEquals(emptyList(), packs.forSeason(SHOW, 1))
        assertEquals(setOf(key, importKey), packs.copies.value.mapTo(HashSet()) { it.key })
    }

    /**
     * L423 (C11): an imported NZB's copy, with the password typed for it, is kept while a download of one of its files is
     * still to finish, failed (for Try again) or removed with its Undo pending, and dropped once they are all done or gone:
     * never kept for the file's life. Another NZB's rows, and a season's, hold nothing of it.
     */
    @Test
    fun `an imported NZB's copy is kept for its rows still to finish, failed or removed, and dropped once all are done or gone`() = runBlocking<Unit> {
        packs.write(imported, IMPORT_NZB, TYPED)
        (DownloadRunner.GOING_ON + DownloadState.Failed).forEach { state ->
            packs.sweep(listOf(file("f1", 0, DownloadState.Done), file("f2", 1, state)))
            assertNotNull(packs.find(importKey), "with a row $state")
        }
        // Removed, its Undo pending: held; its Undo gone: not.
        val removed = listOf(file("f1", 0, DownloadState.Done), file("f2", 1, DownloadState.Deleting))
        packs.sweep(removed, removed = setOf("f2"))
        assertNotNull(packs.find(importKey))
        packs.sweep(removed)
        assertNull(packs.find(importKey))
        assertFalse(packs.folder(importKey).exists())

        // Another NZB's rows, a season's and a film's hold nothing of it.
        packs.write(imported, IMPORT_NZB, TYPED)
        packs.sweep(listOf(file("other", 0, DownloadState.Queued, nzbId = "9f8e7d"), row("e1", 2, 1, DownloadState.Queued)))
        assertNull(packs.find(importKey))
        // All done: gone, the password with it.
        packs.write(imported, IMPORT_NZB, TYPED)
        packs.sweep(listOf(file("f1", 0, DownloadState.Done), file("f2", 1, DownloadState.Done)))
        assertNull(packs.find(importKey))
        assertEquals(emptyList(), File(dir, DownloadNzbs.PATH).list()!!.toList())
    }

    /**
     * L462: a season's torrent pack is kept as its meta alone, with no NZB and no password, as its attempt prepares it by its
     * hash: written and found, among its season's copies as a Usenet copy is, and kept as it was where it is kept already
     * (its leads as the first row wrote it). It is held by a row of its show and season still to finish, and swept with
     * the season's last. A Usenet copy's meta with its NZB gone is no copy still. Neither on disk nor in a log line is it
     * named by its hash, its key or its title.
     */
    @Test
    fun `a season's torrent pack is kept as its meta alone, found for its season, held by it and swept`() = runBlocking<Unit> {
        val from = LogBuffer.snapshot().lastOrNull()?.seq ?: 0L
        packs.write(meta, nzb, PASSWORD)
        packs.write(torrentMeta, null, null)
        val copy = assertNotNull(packs.find(torrentKey))
        assertEquals(torrentMeta, copy.meta)
        assertNull(copy.source)
        val folder = packs.folder(torrentKey)
        assertEquals(sha256(torrentKey), folder.name)
        assertEquals(setOf(DownloadNzbs.META), folder.list()!!.toSet())
        assertEquals(listOf(key, torrentKey).sorted(), packs.forSeason(SHOW, 2).map { it.meta.key })
        assertNull(packs.forSeason(SHOW, 2).single { it.meta.key == torrentKey }.source)
        assertEquals(setOf(key, torrentKey), packs.copies.value.mapTo(HashSet()) { it.key })

        // Kept again for another row: as the first wrote it.
        packs.write(torrentMeta.copy(leads = false), null, null)
        assertTrue(assertNotNull(packs.find(torrentKey)).meta.leads)

        // Held by a row of its season still to finish; another season's holds nothing of it.
        packs.sweep(listOf(row("e2", 2, 2, DownloadState.Paused)))
        assertNotNull(packs.find(torrentKey))
        packs.sweep(listOf(row("e1", 2, 1, DownloadState.Done), row("e9", 3, 1, DownloadState.Queued)))
        assertNull(packs.find(torrentKey))
        assertFalse(folder.exists())
        assertEquals(emptyList(), packs.copies.value)

        // A Usenet copy's meta whose NZB is gone is no copy, torrent or not.
        packs.write(meta, nzb, PASSWORD)
        File(packs.folder(key), DownloadNzbs.NZB).delete()
        assertNull(packs.find(key))
        assertEquals(emptyList(), packs.forSeason(SHOW, 2))

        val lines = LogBuffer.snapshot().filter { it.seq > from }.map { it.message }
        assertEquals(emptyList(), lines.filter { line -> listOf(HASH, torrentKey, TORRENT_TITLE, folder.name).any { it in line } })
        assertTrue(lines.count { folder.name.take(8) in it } >= 2, lines.toString())
    }

    /**
     * L456: an import writes its copy and then its row from the visible app while a walk's end may sweep. A sweep reads the
     * rows and deletes under storage's lock ([DownloadNzbs.sweepRows]), and the import writes under it too: one begun
     * before the import's write, its rows read without the import's row, never deletes the copy written after them. Here
     * the gate holds the sweep between its read and its delete while the import comes.
     */
    @Test
    fun `a sweep begun before an import's write never deletes it`() = runBlocking<Unit> {
        val changes = Mutex()
        val packs = DownloadNzbs(File(dir, DownloadNzbs.PATH), Dispatchers.Unconfined, changes)
        val rows = mutableListOf(row("e1", 3, 1, DownloadState.Done))
        val read = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val sweep = launch {
            packs.sweepRows({
                rows.toList().also {
                    read.complete(Unit)
                    gate.await()
                }
            })
        }
        read.await()
        // The import, as storage's add makes it: its copy, then its row, under the same lock.
        val import = launch {
            changes.withLock {
                packs.write(imported, IMPORT_NZB, TYPED)
                rows += file("f1", 0, DownloadState.Queued)
            }
        }
        yield()
        gate.complete(Unit)
        sweep.join()
        import.join()
        assertNotNull(packs.find(importKey), "the sweep took the copy written after its read")
        // The next sweep reads its row, and keeps it.
        packs.sweepRows({ rows.toList() })
        assertNotNull(packs.find(importKey))
    }

    /**
     * L456: storage's add writes an import's copy before its row, under the lock its sweeps read the rows and delete under;
     * reconcile, which holds that lock itself, sweeps with the rows it read (a Mutex isn't reentrant).
     */
    @Test
    fun `storage writes an import's copy before its row under the lock its sweeps take`() {
        val source = File("src/main/kotlin/io/skjaere/usenet/app/download/DownloadStorage.kt").readText()
        // What [signature] runs under storage's lock, to its closing brace.
        fun body(signature: String): String {
            val at = source.indexOf(signature)
            assertTrue(at >= 0, "no $signature")
            val start = source.indexOf("changes.withLock {", at) + "changes.withLock ".length
            var depth = 0
            for (i in start until source.length) {
                if (source[i] == '{') depth++
                if (source[i] == '}' && --depth == 0) return source.substring(start, i + 1)
            }
            error("no end of $signature")
        }
        assertTrue("suspend fun add(row: Download, grab: Grab? = null, copy: DownloadNzbs.Copy? = null): Unit = changes.withLock {" in source)
        assertTrue("suspend fun addAll(rows: List<Download>, putBack: List<Download> = emptyList(), copy: DownloadNzbs.Copy? = null): Unit = changes.withLock {" in source)
        listOf("suspend fun add(row: Download", "suspend fun addAll(rows: List<Download>").forEach { signature ->
            val add = body(signature)
            val copy = add.indexOf("packs.write(")
            assertTrue(copy >= 0 && copy < add.indexOf("downloads.insert"), "$signature: the copy before the row")
        }
        assertTrue(Regex("""suspend fun sweepPacks\(removed: Set<String> = emptySet\(\)\) = packs\.sweepRows\(\{ downloads\.all\(\)\.first\(\) \}, removed\)""").containsMatchIn(source))
        assertTrue(Regex("""val packs = DownloadNzbs\(File\(filesDir, DownloadNzbs\.PATH\), io, changes\)""").containsMatchIn(source))
        assertTrue("packs.sweep(left)" in body("suspend fun reconcile("))
    }

    private fun row(id: String, season: Int, episode: Int, state: DownloadState) = Download(
        id = id, watchKey = WatchKey.episode(SHOW, season, episode), kind = DownloadKind.Episode, title = "Harbor Lights", showTmdbId = SHOW,
        season = season, episode = episode, state = state, position = episode.toLong(), createdAt = 0, updatedAt = 0,
    )

    /** Download [id] of file [index] of the imported NZB [nzbId] (L423), in [state]: its NZB read from its pick. */
    private fun file(id: String, index: Int, state: DownloadState, nzbId: String = NZB_SHA) = Download(
        id = id, watchKey = WatchKey.release(nzbId, index), kind = DownloadKind.Release, title = "Quiet.Meridian.S01E0${index + 1}.1080p.WEB.h264-WREN.mkv",
        subtitle = IMPORT_TITLE, state = state, position = index.toLong(), candidates = listOf(importCandidate.copy(guid = nzbId)),
        pick = DownloadColumns.text(DownloadPick(index, "Quiet.Meridian.S01E0${index + 1}.1080p.WEB.h264-WREN.mkv", 1_300_000_000, nzbId)),
        createdAt = 0, updatedAt = 0,
    )

    /** The season as a torrent, as a row keeps its candidate: its content id for its guid (L459). */
    private val torrentCandidate = DownloadCandidate(TORZNAB, "btih:$HASH", TORRENT_TITLE, size = 6_600_000_021L, source = ReleaseFacts.TORRENT)
    private val torrentKey = ReleaseKey.of(TORZNAB, "btih:$HASH")
    private val torrentMeta = DownloadNzbs.Meta(torrentKey, TORRENT_TITLE, torrentCandidate, SHOW, 2, leads = true)

    private val importCandidate = DownloadCandidate(IMPORTED, NZB_SHA, IMPORT_TITLE, size = 1_300_000_000)
    private val importKey = ReleaseKey.of(IMPORTED, NZB_SHA)
    private val imported = DownloadNzbs.Meta.imported(importCandidate)

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

        /** A torrent indexer, and its season pack of season 2: its v1 hash and its name. */
        const val TORZNAB = "fake-torznab"
        const val HASH = "c0ffee21c0ffee22c0ffee23c0ffee24c0ffee25"
        const val TORRENT_TITLE = "Harbor.Lights.S02.1080p.WEB-DL.AAC2.0.H.264-PACK"

        /** An imported NZB: its id (the SHA-256 of its bytes), its title, the password typed for it on Import. */
        const val NZB_SHA = "5b1c9d0e7f3a2b4c6d8e0f1a3b5c7d9e1f2a4b6c8d0e2f4a6b8c0d2e4f6a8b0c"
        const val IMPORT_TITLE = "Quiet.Meridian.S01.1080p.WEB.h264-WREN"
        const val TYPED = "typed-on-import"
        val IMPORT_NZB = "<nzb><!-- $IMPORT_TITLE --></nzb>".encodeToByteArray()
    }
}
