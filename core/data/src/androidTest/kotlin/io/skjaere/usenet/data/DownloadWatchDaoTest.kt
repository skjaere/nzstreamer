package io.skjaere.usenet.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Delete once watched's clock as the library joins it (L412): each finished download whose title's watch state is
 * watched and not a tombstone, written after the download finished, with when it was written. Not one watched before
 * it finished (a title downloaded again after its mark), nor one marked at the moment it finished, nor a tombstone, an
 * unwatched title, a download being deleted, still queued or failed. And followed: a watch state written again is seen
 * again, by the same reader, as Room tells it of either table's writes.
 */
@RunWith(AndroidJUnit4::class)
class DownloadWatchDaoTest {
    private val db = LibraryDatabase.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
    private val downloads = db.downloads()
    private val watches = db.watchStates()
    private val joined = db.downloadWatches()

    @After
    fun close() {
        db.close()
    }

    @Test
    fun aFinishedDownloadWatchedSinceItFinishedIsJoinedWithWhenItWasMarked() = runBlocking<Unit> {
        downloads.insert(episode("done", 1, DownloadState.Done, completedAt = 100))
        downloads.insert(movie("film", DownloadState.Done, completedAt = 200))
        watches.upsert(listOf(episodeState(1, watched = true, updatedAt = 150), movieState(watched = true, updatedAt = 250)))

        assertEquals(
            listOf(WatchedDownload("done", WatchKey.episode(SHOW, 1, 1), 100, 150), WatchedDownload("film", WatchKey.movie(FILM), 200, 250)),
            joined.watched().first(),
        )
    }

    @Test
    fun notOneWatchedBeforeItFinishedNorATombstoneAnUnwatchedOneOrOneBeingDeletedQueuedOrFailed() = runBlocking<Unit> {
        listOf(
            episode("kept", 1, DownloadState.Done, completedAt = 100),
            // Watched before it finished: downloaded again after its mark.
            episode("again", 2, DownloadState.Done, completedAt = 300),
            // Marked as it finished: not after it.
            episode("same", 3, DownloadState.Done, completedAt = 300),
            episode("tombstone", 4, DownloadState.Done, completedAt = 100),
            episode("unwatched", 5, DownloadState.Done, completedAt = 100),
            episode("deleting", 6, DownloadState.Deleting, completedAt = 100),
            episode("queued", 7, DownloadState.Queued),
            episode("failed", 8, DownloadState.Failed),
        ).forEach { downloads.insert(it) }
        watches.upsert(
            listOf(
                episodeState(1, watched = true, updatedAt = 150),
                episodeState(2, watched = true, updatedAt = 250),
                episodeState(3, watched = true, updatedAt = 300),
                episodeState(4, watched = true, updatedAt = 400).copy(deleted = true),
                episodeState(5, watched = false, updatedAt = 400),
                episodeState(6, watched = true, updatedAt = 400),
                episodeState(7, watched = true, updatedAt = 400),
                episodeState(8, watched = true, updatedAt = 400),
            ),
        )

        assertEquals(listOf("kept"), joined.watched().first().map { it.id })
    }

    @Test
    fun aWatchStateWrittenAgainIsSeenAgainByTheSameReader() = runBlocking<Unit> {
        downloads.insert(episode("done", 1, DownloadState.Done, completedAt = 100))
        watches.upsert(listOf(episodeState(1, watched = true, updatedAt = 150)))
        val seen = Channel<List<WatchedDownload>>(Channel.UNLIMITED)
        val reader = launch(Dispatchers.IO) { joined.watched().collect { seen.send(it) } }
        try {
            assertEquals(listOf(150L), withTimeout(WAIT_MILLIS) { seen.receive() }.map { it.watchedAt })
            // Played again past the mark, a moment ago: the day starts again from there.
            watches.upsert(listOf(episodeState(1, watched = true, updatedAt = 500)))
            assertEquals(listOf(500L), withTimeout(WAIT_MILLIS) { next(seen) { list -> list.any { it.watchedAt == 500L } } }.map { it.watchedAt })
            // Marked unwatched: gone from the join.
            watches.upsert(listOf(episodeState(1, watched = false, updatedAt = 600)))
            assertEquals(emptyList<WatchedDownload>(), withTimeout(WAIT_MILLIS) { next(seen) { it.isEmpty() } })
            // Its download removed: gone too, as a write to the downloads is told as well.
            watches.upsert(listOf(episodeState(1, watched = true, updatedAt = 700)))
            withTimeout(WAIT_MILLIS) { next(seen) { it.isNotEmpty() } }
            downloads.setState("done", DownloadState.Deleting, null, 800)
            assertEquals(emptyList<WatchedDownload>(), withTimeout(WAIT_MILLIS) { next(seen) { it.isEmpty() } })
        } finally {
            reader.cancel()
        }
    }

    /** The reader's next value [wanted] takes, those before it passed over (Room may tell a write more than once). */
    private suspend fun next(seen: Channel<List<WatchedDownload>>, wanted: (List<WatchedDownload>) -> Boolean): List<WatchedDownload> {
        while (true) {
            val value = seen.receive()
            if (wanted(value)) return value
        }
    }

    private fun episode(id: String, number: Int, state: DownloadState, completedAt: Long? = null) = Download(
        id = id, watchKey = WatchKey.episode(SHOW, 1, number), kind = DownloadKind.Episode, title = "Harbor Lights", showTmdbId = SHOW, season = 1,
        episode = number, state = state, position = number.toLong(), size = 1_000, indexerSize = 1_000, createdAt = 1, completedAt = completedAt, updatedAt = 1,
    )

    private fun movie(id: String, state: DownloadState, completedAt: Long? = null) = Download(
        id = id, watchKey = WatchKey.movie(FILM), kind = DownloadKind.Movie, title = "Copper Crown", movieTmdbId = FILM, state = state, position = 0,
        size = 1_000, indexerSize = 1_000, createdAt = 1, completedAt = completedAt, updatedAt = 1,
    )

    private fun episodeState(number: Int, watched: Boolean, updatedAt: Long) = WatchState(
        key = WatchKey.episode(SHOW, 1, number), kind = WatchKind.Episode, showTmdbId = SHOW, season = 1, episode = number,
        title = WatchTitle.episode("Harbor Lights", 1, number), watched = watched, updatedAt = updatedAt,
    )

    private fun movieState(watched: Boolean, updatedAt: Long) =
        WatchState(WatchKey.movie(FILM), WatchKind.Movie, movieTmdbId = FILM, title = "Copper Crown", watched = watched, updatedAt = updatedAt)

    private companion object {
        const val SHOW = 900_031
        const val FILM = 900_032
        const val WAIT_MILLIS = 10_000L
    }
}
