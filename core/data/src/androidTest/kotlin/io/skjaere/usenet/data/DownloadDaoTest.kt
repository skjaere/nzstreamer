package io.skjaere.usenet.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * This device's downloads as the library stores them (L351): the queue in its order and a move in it, the finished ones
 * by watch key, a show's and a film's, the progress record written whole, a row read back as it was written, and a
 * delete.
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
    }
}
