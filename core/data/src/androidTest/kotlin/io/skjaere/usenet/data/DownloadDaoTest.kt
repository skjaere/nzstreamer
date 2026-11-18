package io.skjaere.usenet.data

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * This device's downloads as the library stores them (L351): the queue in its order and a move in it, the finished ones
 * by watch key, a show's and a film's, the progress record written whole, a row read back as it was written (version 15's
 * search facts, file chosen by hand and limit's wait among it), an imported file's row of its own kind, a candidate's
 * source written for a torrent alone, a season's rows written in one transaction, and a delete.
 */
@RunWith(AndroidJUnit4::class)
class DownloadDaoTest {
    private val db = LibraryDatabase.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
    private val downloads = db.downloads()

    @After
    fun close() {
        db.close()
    }

    @Test
    fun theQueueIsWhatIsNotDoneOrBeingDeletedInPositionOrderAndAMoveReordersIt() = runBlocking<Unit> {
        listOf(
            episode("a", 1, position = 2),
            episode("b", 2, position = 1, state = DownloadState.Running),
            episode("c", 3, position = 3, state = DownloadState.Done),
            episode("d", 4, position = 0, state = DownloadState.Deleting),
            episode("e", 5, position = 4, state = DownloadState.Failed),
            episode("f", 6, position = 5, state = DownloadState.Paused),
            movie("g", position = 6, state = DownloadState.Waiting),
        ).forEach { downloads.insert(it) }

        assertEquals(listOf("b", "a", "e", "f", "g"), downloads.queue().first().map { it.id })
        downloads.move("g", 0)
        assertEquals(listOf("g", "b", "a", "e", "f"), downloads.queue().first().map { it.id })
        downloads.move("b", 7)
        assertEquals(listOf("g", "a", "e", "f", "b"), downloads.queue().first().map { it.id })
        // Every row, the finished and the deleted among them.
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g"), downloads.all().first().map { it.id }.sorted())
    }

    @Test
    fun theFinishedDownloadsAreFoundByWatchKey() = runBlocking<Unit> {
        listOf(
            episode("done", 1, position = 0, state = DownloadState.Done, completedAt = 30),
            episode("queued", 2, position = 1),
            episode("deleting", 3, position = 2, state = DownloadState.Deleting, completedAt = 31),
            movie("film", position = 3, state = DownloadState.Done, completedAt = 20),
        ).forEach { downloads.insert(it) }

        assertEquals(listOf("film", "done"), downloads.done().first().map { it.id })
        assertEquals("done", downloads.doneFor(WatchKey.episode(SHOW, 1, 1))?.id)
        assertEquals("film", downloads.doneFor(WatchKey.movie(FILM))?.id)
        // Queued for one, being deleted for another: neither has a finished download.
        assertNull(downloads.doneFor(WatchKey.episode(SHOW, 1, 2)))
        assertNull(downloads.doneFor(WatchKey.episode(SHOW, 1, 3)))
        assertNull(downloads.doneFor(WatchKey.episode(SHOW, 1, 9)))
    }

    @Test
    fun aShowsAndAFilmsDownloadsAreTheirOwnButThoseBeingDeleted() = runBlocking<Unit> {
        listOf(
            episode("s2e1", 1, position = 0, season = 2, state = DownloadState.Done),
            episode("s1e3", 3, position = 1, state = DownloadState.Running),
            episode("s1e1", 1, position = 2, state = DownloadState.Failed),
            episode("s1e2", 2, position = 3, state = DownloadState.Deleting),
            episode("other", 1, position = 4, show = SHOW + 1),
            movie("film", position = 5, state = DownloadState.Paused),
        ).forEach { downloads.insert(it) }

        assertEquals(listOf("s1e1", "s1e3", "s2e1"), downloads.forShow(SHOW).first().map { it.id })
        assertEquals(listOf("other"), downloads.forShow(SHOW + 1).first().map { it.id })
        assertEquals("film", downloads.forMovie(FILM).first()?.id)
        assertNull(downloads.forMovie(FILM + 1).first())
        downloads.setState("film", DownloadState.Deleting, null, 40)
        assertNull(downloads.forMovie(FILM).first())
    }

    @Test
    fun theProgressRecordWritesTheSafeOffsetAndTheGapsTogetherAndNothingElse() = runBlocking<Unit> {
        val row = episode("a", 1, position = 0, state = DownloadState.Running).copy(size = 48 * MIB, safeOffset = 8 * MIB)
        downloads.insert(row)
        val gaps = listOf(DownloadGap(9 * MIB, 9 * MIB + 767), DownloadGap(20 * MIB, 21 * MIB - 1))
        downloads.progress("a", 32 * MIB, gaps, updatedAt = 50)
        assertEquals(row.copy(safeOffset = 32 * MIB, gaps = gaps, updatedAt = 50), downloads.get("a"))
    }

    @Test
    fun aRowReadsBackAsWrittenAndItsStateAndWholeRowChange() = runBlocking<Unit> {
        val row = Download(
            id = "0c6f3c1e-5f0b-4f39-9d2a-7b1e2a9c4d11",
            watchKey = WatchKey.episode(SHOW, 2, 4),
            kind = DownloadKind.Episode,
            title = "Harbor Lights",
            subtitle = "S02E04 · Low Water",
            showTmdbId = SHOW,
            season = 2,
            episode = 4,
            seasonEpisodes = 8,
            episodeName = "Low Water",
            year = 2021,
            runtimeMinutes = 52,
            posterPath = "/p.jpg",
            stillPath = "/s.jpg",
            state = DownloadState.Running,
            reason = "Trying release 2 of 3",
            position = 3,
            candidates = listOf(
                DownloadCandidate("fake", "hl-s02e04-a", "Harbor.Lights.S02E04.1080p.WEB.h264-GRP", "http://x/api?t=get&id=a&apikey={apikey}", 1_400_000_000),
                DownloadCandidate("fake", "hl-s02e04-b", "Harbor.Lights.S02E04.720p.WEB.h264-KESTREL", null, 900_000_000, passworded = true),
            ),
            tried = listOf("fake/hl-s02e04-a"),
            oneVideo = true,
            release = RememberedRelease(
                "fake", "hl-s02e04-b", "Harbor.Lights.S02E04.720p.WEB.h264-KESTREL", null, 0, "abc", "Harbor.Lights.S02E04.720p.WEB.h264-KESTREL.mkv", 880_000_000,
            ),
            fileName = "Harbor.Lights.S02E04.720p.WEB.h264-KESTREL.mkv",
            size = 880_000_000,
            indexerSize = 900_000_000,
            safeOffset = 64 * MIB,
            gaps = listOf(DownloadGap(10, 20)),
            relativePath = "downloads/0c6f3c1e-5f0b-4f39-9d2a-7b1e2a9c4d11/v.mkv",
            video = """{"fps":23.976,"durationMs":3120000}""",
            tracks = """{"video":[],"audio":[],"text":[]}""",
            createdAt = 10,
            completedAt = null,
            updatedAt = 11,
        )
        downloads.insert(row)
        assertEquals(row, downloads.get(row.id))

        downloads.setState(row.id, DownloadState.Failed, "Storage is full", 12)
        assertEquals(row.copy(state = DownloadState.Failed, reason = "Storage is full", updatedAt = 12), downloads.get(row.id))

        val done = row.copy(state = DownloadState.Done, reason = null, safeOffset = 880_000_000, completedAt = 13, updatedAt = 13)
        downloads.update(done)
        assertEquals(done, downloads.get(row.id))
    }

    @Test
    fun aRowsSearchFactsPickAndLimitWaitAreWrittenAndReadBack() = runBlocking<Unit> {
        val search = """{"name":"Harbor Lights","year":2021,"tvdb":700002,"tmdb":900002,"airDate":"2023-03-14","runtimeMinutes":52,"searched":false}"""
        val pick = """{"index":3,"name":"Harbor.Lights.S02E05.1080p.WEB.h264-PACK.mkv","size":1412345678,"nzbId":"a1b2c3d4"}"""
        val limitWait = """{"indexerId":"fake","kind":"Search","since":1700000000000,"until":1700003600000}"""
        val row = episode("a", 5, position = 0, season = 2).copy(search = search, pick = pick, limitWait = limitWait)
        downloads.insert(row)
        downloads.insert(episode("b", 6, position = 1, season = 2))
        assertEquals(row, downloads.get("a"))
        // A row without them reads none.
        val plain = downloads.get("b")
        assertEquals(listOf(null, null, null), listOf(plain?.search, plain?.pick, plain?.limitWait))

        // The whole row's write changes them, and clears them, as the runner does at Done.
        val waiting = row.copy(state = DownloadState.Waiting, limitWait = """{"indexerId":"fake","kind":"Download","since":1700000500000}""", updatedAt = 20)
        downloads.update(waiting)
        assertEquals(waiting, downloads.get("a"))
        val done = waiting.copy(state = DownloadState.Done, search = null, pick = null, limitWait = null, completedAt = 30, updatedAt = 30)
        downloads.update(done)
        assertEquals(done, downloads.get("a"))
        // A state's write leaves them be.
        downloads.update(waiting)
        downloads.setState("a", DownloadState.Queued, null, 40)
        assertEquals(waiting.copy(state = DownloadState.Queued, reason = null, updatedAt = 40), downloads.get("a"))
    }

    /**
     * L423: a file of an NZB opened on Import is a row of its own kind, stored by the kind's name, with no TMDB ids: named by
     * the file and its NZB, keyed by the NZB's id and the file's place, its one release Import's own with the file chosen
     * in it. Read back as written, found by its watch key once finished, and in the queue as any row before.
     */
    @Test
    fun anImportedFilesRowIsWrittenAndReadBackAsItsOwnKind() = runBlocking<Unit> {
        val nzb = "5b1c9d0e7f3a2b4c6d8e0f1a3b5c7d9e1f2a4b6c8d0e2f4a6b8c0d2e4f6a8b0c"
        val file = "Quiet.Meridian.S01E02.1080p.WEB.h264-WREN.mkv"
        val row = Download(
            id = "7c1d2e3f-0a1b-4c2d-8e3f-405162738495",
            watchKey = WatchKey.release(nzb, 1),
            kind = DownloadKind.Release,
            title = file,
            subtitle = "Quiet.Meridian.S01.1080p.WEB.h264-WREN",
            position = 0,
            candidates = listOf(DownloadCandidate("import", nzb, "Quiet.Meridian.S01.1080p.WEB.h264-WREN", size = 1_300_000_000)),
            indexerSize = 1_300_000_000,
            createdAt = 10,
            updatedAt = 10,
            pick = """{"index":1,"name":"$file","size":1300000000,"nzbId":"$nzb"}""",
        )
        downloads.insert(row)
        assertEquals(row, downloads.get(row.id))
        assertEquals(listOf(row), downloads.queue().first())
        assertEquals(DownloadKind.Release, downloads.all().first().single().kind)

        val done = row.copy(
            state = DownloadState.Done,
            release = RememberedRelease("import", nzb, "Quiet.Meridian.S01.1080p.WEB.h264-WREN", null, 1, nzb, file, 1_300_000_000),
            fileName = file, size = 1_300_000_000, safeOffset = 1_300_000_000, relativePath = "downloads/${row.id}/v.mkv", pick = null, completedAt = 20,
            updatedAt = 20,
        )
        downloads.update(done)
        assertEquals(done, downloads.doneFor("release:$nzb:1"))
        assertEquals(emptyList<Download>(), downloads.queue().first())
    }

    /**
     * L459: a candidate knows its source, a JSON field with a default. A torrent's is written, "torrent"; a Usenet
     * candidate's is left out, so its row's JSON is as an older build wrote it; and a row an older build wrote, its
     * candidates with no source, reads back with none: Usenet's.
     */
    @Test
    fun aCandidatesSourceIsWrittenForATorrentAloneAndAnOlderRowReadsNone() = runBlocking<Unit> {
        val usenet = DownloadCandidate("fake", "hl-s02e04-a", "Harbor.Lights.S02E04.1080p.WEB.h264-GRP", "http://x/api?t=get&id=a&apikey={apikey}", 1_400_000_000)
        val torrent = DownloadCandidate(
            "prowlarr", "btih:$HASH", "Harbor.Lights.S02E04.1080p.WEB.H264-SALTMARSH", size = 1_800_000_000, source = "torrent",
        )
        downloads.insert(episode("usenet", 4, position = 0).copy(candidates = listOf(usenet)))
        downloads.insert(episode("both", 5, position = 1).copy(candidates = listOf(torrent, usenet)))
        assertEquals(listOf(usenet), downloads.get("usenet")?.candidates)
        assertEquals(listOf(torrent, usenet), downloads.get("both")?.candidates)
        // As stored: the torrent's source written, the Usenet one's left out, as before sources were.
        assertEquals("[$OLDER_USENET]", candidatesStored("usenet"))
        val torrentStored = """{"indexerId":"prowlarr","guid":"btih:$HASH","title":"Harbor.Lights.S02E04.1080p.WEB.H264-SALTMARSH","size":1800000000,"source":"torrent"}"""
        assertEquals("[$torrentStored,$OLDER_USENET]", candidatesStored("both"))

        // A row an older build wrote: its candidates read as Usenet's, with no source.
        db.openHelper.writableDatabase.execSQL("UPDATE download SET candidates = ? WHERE id = ?", arrayOf<Any>("[$OLDER_USENET]", "both"))
        val older = downloads.get("both")?.candidates
        assertEquals(listOf(usenet), older)
        assertEquals(listOf(null), older?.map { it.source })
    }

    /** Download [id]'s candidates as stored: the JSON the app wrote. */
    private fun candidatesStored(id: String): String =
        db.query("SELECT candidates FROM download WHERE id = ?", arrayOf(id)).use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        }

    /**
     * A season queued at once (L392, L396): its new rows inserted and its failed ones put back in one transaction, so one
     * insert that fails leaves none of it, the rows put back as they were; whole, it writes them all.
     */
    @Test
    fun insertAllWritesASeasonsRowsInOneTransactionOrNone() = runBlocking<Unit> {
        val failed = episode("failed", 2, position = 0, state = DownloadState.Failed).copy(reason = "No playable release found", tried = listOf("fake/hl-a"))
        downloads.insert(failed)
        downloads.insert(episode("taken", 9, position = 1))
        val back = failed.copy(state = DownloadState.Queued, reason = null, tried = emptyList(), position = 2, updatedAt = 20)

        // The last one's id is taken: its insert fails after the put-back's write and the first insert.
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { downloads.insertAll(listOf(episode("e3", 3, position = 3), episode("taken", 4, position = 4)), putBack = listOf(back)) }
        }
        assertNull(downloads.get("e3"))
        assertEquals(failed, downloads.get("failed"))
        assertEquals(listOf("failed", "taken"), downloads.queue().first().map { it.id })

        downloads.insertAll(listOf(episode("e3", 3, position = 3), episode("e4", 4, position = 4)), putBack = listOf(back))
        assertEquals(back, downloads.get("failed"))
        assertEquals(listOf("taken", "failed", "e3", "e4"), downloads.queue().first().map { it.id })
    }

    @Test
    fun aDeletedRowIsGoneAndTheOthersStay() = runBlocking<Unit> {
        downloads.insert(episode("a", 1, position = 0))
        downloads.insert(episode("b", 2, position = 1, state = DownloadState.Done))
        downloads.delete("b")
        assertNull(downloads.get("b"))
        assertEquals(listOf("a"), downloads.all().first().map { it.id })
        assertEquals(emptyList<Download>(), downloads.done().first())
        // A delete of a row that isn't there does nothing.
        downloads.delete("b")
        assertEquals(listOf("a"), downloads.queue().first().map { it.id })
    }

    private fun episode(
        id: String,
        episode: Int,
        position: Long,
        state: DownloadState = DownloadState.Queued,
        season: Int = 1,
        show: Int = SHOW,
        completedAt: Long? = null,
    ) = Download(
        id = id, watchKey = WatchKey.episode(show, season, episode), kind = DownloadKind.Episode, title = "Harbor Lights",
        subtitle = "S%02dE%02d".format(season, episode), showTmdbId = show, season = season, episode = episode, seasonEpisodes = 8,
        state = state, position = position, indexerSize = 1_400_000_000, createdAt = 10, completedAt = completedAt, updatedAt = 10,
    )

    private fun movie(id: String, position: Long, state: DownloadState = DownloadState.Queued, completedAt: Long? = null) = Download(
        id = id, watchKey = WatchKey.movie(FILM), kind = DownloadKind.Movie, title = "Copper Crown", movieTmdbId = FILM, year = 2019,
        runtimeMinutes = 112, state = state, position = position, indexerSize = 4_200_000_000, createdAt = 10, completedAt = completedAt, updatedAt = 10,
    )

    private companion object {
        const val SHOW = 900002
        const val FILM = 900011
        const val MIB = 1024L * 1024
        const val HASH = "0123456789abcdef0123456789abcdef01234567"

        /** A Usenet candidate as a build from before sources wrote it. */
        const val OLDER_USENET = """{"indexerId":"fake","guid":"hl-s02e04-a","title":"Harbor.Lights.S02E04.1080p.WEB.h264-GRP",""" +
            """"downloadTemplate":"http://x/api?t=get&id=a&apikey={apikey}","size":1400000000}"""
    }
}
