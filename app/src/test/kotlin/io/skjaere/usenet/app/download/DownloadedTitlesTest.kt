package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * L407: what the marks say of the finished downloads, from the runner's map of them by watch key alone (never the queue):
 * by title, a show counting its episodes and a film one, with their bytes; by watch key for Up next. Only rows whose file
 * plays from the row alone count, as the play hook's launch takes them. Titles and ids are invented.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadedTitlesTest {
    private fun release(id: String, nzbId: String? = "nzb-$id") = RememberedRelease("fake-indexer", id, "Harbor.Lights.$id.1080p.WEB-KESTREL", null, 0, nzbId)

    private fun episode(show: Int, season: Int, episode: Int, size: Long = 1_300_000_000, state: DownloadState = DownloadState.Done, id: String = "s$show-$season-$episode") = Download(
        id = id, watchKey = WatchKey.episode(show, season, episode), kind = DownloadKind.Episode, title = "Harbor Lights", showTmdbId = show, season = season,
        episode = episode, state = state, position = 0, release = release(id), size = size, indexerSize = size, createdAt = 1, completedAt = 2, updatedAt = 2,
    )

    private fun film(movie: Int, size: Long = 4_100_000_000, id: String = "m$movie") = Download(
        id = id, watchKey = WatchKey.movie(movie), kind = DownloadKind.Movie, title = "Copper Crown", movieTmdbId = movie, state = DownloadState.Done,
        position = 0, release = release(id), size = size, indexerSize = size, createdAt = 1, completedAt = 2, updatedAt = 2,
    )

    @Test
    fun `a show counts its finished episodes and their bytes, a film one`() {
        val done = listOf(
            episode(1, 2, 1), episode(1, 2, 5, size = 1_400_000_000), episode(1, 3, 1, size = 1_000_000_000), episode(2, 1, 1), film(10), film(11, size = 5_500_000_000),
        )
        assertEquals(
            mapOf(
                TitleKey.show(1) to Downloaded(3, 3_700_000_000), TitleKey.show(2) to Downloaded(1, 1_300_000_000),
                TitleKey.movie(10) to Downloaded(1, 4_100_000_000), TitleKey.movie(11) to Downloaded(1, 5_500_000_000),
            ),
            DownloadedTitles.of(done),
        )
    }

    /** One episode downloaded twice is one episode: the runner keeps one by watch key, and a show counts them so. */
    @Test
    fun `an episode finished twice counts once`() {
        val twice = listOf(episode(1, 2, 1), episode(1, 2, 1, size = 1_400_000_000, id = "again"), episode(1, 2, 2))
        assertEquals(mapOf(TitleKey.show(1) to Downloaded(2, 2_600_000_000)), DownloadedTitles.of(twice))
        assertEquals(setOf(WatchKey.episode(1, 2, 1), WatchKey.episode(1, 2, 2)), DownloadedTitles.keys(twice.associateBy { it.id }))
    }

    /** A mark means the file plays: a row whose release isn't kept with its NZB's id has no launch, and no mark; nor one not done. */
    @Test
    fun `only finished rows whose file plays from the row alone count`() {
        val plays = episode(1, 2, 1)
        val noRelease = episode(1, 2, 2).copy(release = null)
        val noNzb = episode(1, 2, 3).let { it.copy(release = it.release!!.copy(nzbId = null)) }
        val filmNoRelease = film(10).copy(release = null)
        val queued = episode(1, 2, 4, state = DownloadState.Queued)
        val deleting = episode(1, 2, 6, state = DownloadState.Deleting)
        // An episode with no show to count it under, as no row the sheets make is.
        val noShow = episode(1, 2, 7).copy(showTmdbId = null)
        val rows = listOf(plays, noRelease, noNzb, filmNoRelease, queued, deleting, noShow)
        assertEquals(mapOf(TitleKey.show(1) to Downloaded(1, 1_300_000_000)), DownloadedTitles.of(rows))
        assertEquals(setOf(plays.watchKey, noShow.watchKey), DownloadedTitles.keys(rows.associateBy { it.watchKey }))
        assertEquals(emptyMap(), DownloadedTitles.of(emptyList()))
    }

    /** The runner tells its map again on every finished row's write; the marks hear only a change in what they say. */
    @Test
    fun `told again only when what the marks say changes`() = runTest(UnconfinedTestDispatcher()) {
        val done = MutableStateFlow(mapOf("a" to episode(1, 2, 1)))
        val told = mutableListOf<Map<TitleKey, Downloaded>>()
        val job = launch { DownloadedTitles.flow(done).toList(told) }
        // A row the hook doesn't take, and the same row written again with another time: the marks say the same.
        done.value = done.value + ("b" to episode(1, 2, 2).copy(release = null))
        done.value = done.value + ("a" to episode(1, 2, 1).copy(updatedAt = 9))
        done.value = done.value + ("c" to episode(1, 2, 3))
        job.cancel()
        assertEquals(listOf(mapOf(TitleKey.show(1) to Downloaded(1, 1_300_000_000)), mapOf(TitleKey.show(1) to Downloaded(2, 2_600_000_000))), told)
    }

    /** What TalkBack adds to a collection row's sentence: a show's count of episodes, a film's word alone. */
    @Test
    fun `TalkBack hears how many of a show's episodes are downloaded, and a film downloaded`() {
        assertEquals("3 episodes downloaded.", DownloadedTitles.spoken(Downloaded(3, 1), show = true))
        assertEquals("1 episode downloaded.", DownloadedTitles.spoken(Downloaded(1, 1), show = true))
        assertEquals("Downloaded.", DownloadedTitles.spoken(Downloaded(1, 1), show = false))
        assertEquals("Harbor Lights. Resume season 2, episode 4. 10 unwatched. 3 episodes downloaded.", DownloadedTitles.said("Harbor Lights. Resume season 2, episode 4. 10 unwatched.", Downloaded(3, 1), show = true))
        // A sentence that doesn't end in a stop gets one first; one with no download is as it was.
        assertEquals("Copper Crown, 2022, 1 hour 52 minutes, Not watched. Downloaded.", DownloadedTitles.said("Copper Crown, 2022, 1 hour 52 minutes, Not watched", Downloaded(1, 1), show = false))
        assertEquals("Harbor Lights. Checking TMDB… 1 episode downloaded.", DownloadedTitles.said("Harbor Lights. Checking TMDB…", Downloaded(1, 1), show = true))
        assertEquals("Copper Crown, 2022", DownloadedTitles.said("Copper Crown, 2022", null, show = false))
    }
}
