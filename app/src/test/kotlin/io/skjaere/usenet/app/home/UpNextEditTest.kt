package io.skjaere.usenet.app.home

import io.skjaere.usenet.data.CollectedShow
import io.skjaere.usenet.data.MovieMark
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.ShowMark
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.metadata.EpisodeRef
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What an Up next row's menu does to the library: Remove from Up next on every row, Mark watched on one in progress. */
class UpNextEditTest {
    private val release = RememberedRelease("fake", "guid", "Harbor.Lights.S02E05.1080p.WEB.h264-GRP", null, 0, "nzb")

    private fun inProgress(key: String, kind: WatchKind, title: String, show: Int? = null, s: Int? = null, e: Int? = null, movie: Int? = null) = WatchState(
        key, kind, showTmdbId = show, season = s, episode = e, movieTmdbId = movie, title = title, positionMs = 900_000, durationMs = 2_700_000,
        lastPlayedAt = 10, release = release, updatedAt = 10,
    )

    private fun episode(show: Int, name: String, s: Int, e: Int) = inProgress(WatchKey.episode(show, s, e), WatchKind.Episode, WatchTitle.episode(name, s, e), show, s, e)

    private fun row(title: String, play: UpNextPlay) = UpNextRow(title, "", null, false, null, play, "")

    private val harbor = row("Harbor Lights", UpNextPlay.Resume(episode(1, "Harbor Lights", 2, 5)))
    private val special = row("Harbor Lights", UpNextPlay.Resume(episode(1, "Harbor Lights", 0, 2)))
    private val orbit = row("The Quiet Orbit", UpNextPlay.NextEpisode(CollectedShow(CollectedShow.idFor(2), 2, name = "The Quiet Orbit", addedAt = 0, updatedAt = 0), EpisodeRef(1, 2)))
    private val notCollected = row("Salt & Signal", UpNextPlay.Resume(episode(7, "Salt & Signal", 1, 2)))
    private val film = row("Copper Crown", UpNextPlay.Resume(inProgress(WatchKey.movie(10), WatchKind.Movie, "Copper Crown", movie = 10)))
    private val northbound = row("Northbound", UpNextPlay.Resume(inProgress(WatchKey.release("n-northbound", 0), WatchKind.Release, "Northbound.2019.1080p.BluRay.x264-GRP")))

    /** Any of the show's episodes played again brings it back, whichever row it had. */
    @Test
    fun `a show's row is taken out by its show, whether in progress, a special or next`() {
        assertEquals(UpNextEdit.Remove("show:1", showTmdbId = 1, watchKey = null, message = "Removed “Harbor Lights” from Up next"), UpNextEdit.remove(harbor))
        assertEquals(UpNextEdit.Remove("show:1", showTmdbId = 1, watchKey = null, message = "Removed “Harbor Lights” from Up next"), UpNextEdit.remove(special))
        assertEquals(UpNextEdit.Remove("show:2", showTmdbId = 2, watchKey = null, message = "Removed “The Quiet Orbit” from Up next"), UpNextEdit.remove(orbit))
        assertEquals(UpNextEdit.Remove("show:7", showTmdbId = 7, watchKey = null, message = "Removed “Salt & Signal” from Up next"), UpNextEdit.remove(notCollected))
    }

    @Test
    fun `a film or a release is taken out by what played`() {
        assertEquals(UpNextEdit.Remove("movie:tmdb:10", showTmdbId = null, watchKey = "movie:tmdb:10", message = "Removed “Copper Crown” from Up next"), UpNextEdit.remove(film))
        val key = WatchKey.release("n-northbound", 0)
        assertEquals(UpNextEdit.Remove(key, showTmdbId = null, watchKey = key, message = "Removed “Northbound” from Up next"), UpNextEdit.remove(northbound))
    }

    /** Only the episode in progress is marked (the show moves on to its next one), so the bar names it. */
    @Test
    fun `mark watched marks the episode, special or film in progress`() {
        assertEquals(
            UpNextEdit.MarkEpisode("show:1", ShowMark(1, "Harbor Lights", listOf(2 to 5)), "Marked “Harbor Lights S02E05” watched"),
            UpNextEdit.markWatched(harbor),
        )
        assertEquals(
            UpNextEdit.MarkEpisode("show:1", ShowMark(1, "Harbor Lights", listOf(0 to 2)), "Marked “Harbor Lights S00E02” watched"),
            UpNextEdit.markWatched(special),
        )
        assertEquals(UpNextEdit.MarkMovie("movie:tmdb:10", MovieMark(10, "Copper Crown"), "Marked “Copper Crown” watched"), UpNextEdit.markWatched(film))
    }

    /** A next episode isn't begun, and nothing marks a release played without TMDB: their menus offer Remove only. */
    @Test
    fun `a next episode or a release offers no mark watched`() {
        assertNull(UpNextEdit.markWatched(orbit))
        assertNull(UpNextEdit.markWatched(northbound))
    }

    /** As the collection's: the row after, else the one before; a show marked keeps its row (its next episode), so the focus stays. */
    @Test
    fun `a remote's focus goes to the next row, else the one before, and stays on a show marked`() {
        val keys = listOf(harbor, film, orbit, northbound).map { it.key }
        assertEquals(orbit.key, UpNextEdit.remove(film).focusAfter(keys))
        assertEquals(orbit.key, UpNextEdit.remove(northbound).focusAfter(keys))
        assertEquals(film.key, UpNextEdit.remove(harbor).focusAfter(keys))
        assertNull(UpNextEdit.remove(harbor).focusAfter(listOf(harbor.key)))
        assertEquals(orbit.key, UpNextEdit.markWatched(film)!!.focusAfter(keys))
        assertEquals(harbor.key, UpNextEdit.markWatched(harbor)!!.focusAfter(keys))
    }

    /**
     * As the Undo bar goes: the row focusAfter named while it is shown; gone meanwhile (a show marked through its last aired
     * episode, a neighbour taken out), the row shown nearest after the one edited, else before; none, Home's first focus.
     */
    @Test
    fun `the focus goes back to focusAfter's row while it shows, else the nearest one shown`() {
        val before = listOf(harbor, film, orbit, northbound).map { it.key }
        assertEquals(orbit.key, UpNextEdit.handBack(orbit.key, film.key, before, before - film.key))
        // A show marked keeps its row: back on it.
        assertEquals(harbor.key, UpNextEdit.handBack(harbor.key, harbor.key, before, before))
        // Marked through its last aired episode, its row went too: the next one shown.
        assertEquals(film.key, UpNextEdit.handBack(harbor.key, harbor.key, before, before - harbor.key))
        // The row after went meanwhile: the next one shown after the edited row, else the one before it.
        assertEquals(northbound.key, UpNextEdit.handBack(orbit.key, film.key, before, listOf(harbor.key, northbound.key)))
        assertEquals(harbor.key, UpNextEdit.handBack(northbound.key, orbit.key, before, listOf(harbor.key)))
        // A row that came meanwhile isn't one to go back to unless named.
        assertEquals(orbit.key, UpNextEdit.handBack(null, film.key, before, listOf("show:99", harbor.key, orbit.key)))
        // None left: Home's first focus.
        assertNull(UpNextEdit.handBack(null, harbor.key, listOf(harbor.key), emptyList()))
        assertNull(UpNextEdit.handBack(null, null, emptyList(), emptyList()))
    }
}
