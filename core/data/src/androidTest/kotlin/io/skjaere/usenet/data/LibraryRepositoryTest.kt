package io.skjaere.usenet.data

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.link.FinalKind
import io.skjaere.usenet.link.LinkMessage
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class LibraryRepositoryTest {
    private val db = LibraryDatabase.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
    private var time = 1_000L
    private val library = LibraryRepository(db) { time }

    @After
    fun close() = db.close()

    private fun show(tmdbId: Int, name: String) =
        CollectedShow(CollectedShow.idFor(tmdbId), tmdbId, tvdbId = tmdbId + 1, name = name, addedAt = 0, updatedAt = 0)

    private fun episode(s: Int, e: Int, show: Int = 900002) = WatchState(
        key = WatchKey.episode(show, s, e), kind = WatchKind.Episode, showTmdbId = show, season = s, episode = e,
        title = "Harbor Lights S${s}E$e", updatedAt = 0,
    )

    private fun movie(tmdbId: Int) = WatchState(WatchKey.movie(tmdbId), WatchKind.Movie, movieTmdbId = tmdbId, title = "Copper Crown", updatedAt = 0)

    private fun releaseOnly(nzbId: String) = WatchState(WatchKey.release(nzbId, 0), WatchKind.Release, title = "Northbound.2019.1080p.BluRay.x264-GRP", updatedAt = 0)

    private suspend fun hidden() = library.upNextHidden.first()

    /** Where a play this phone started on a TV stopped (W5): Harbor Lights S1E1 at [positionMs], made [ageMs] ago. */
    private fun final(positionMs: Long, watched: Boolean = false, ageMs: Long = 2_000) = LinkMessage.Final(
        "f-1", "s-1", WatchKey.episode(900002, 1, 1), FinalKind.Episode, "Harbor Lights S1E1", showTmdbId = 900002, season = 1, episode = 1,
        positionMs = positionMs, durationMs = 2_700_000, watched = watched, ageMs = ageMs,
    )

    /** W5, L11 (item 27): a play on a TV saved as if it had played here, as of the Final's own time. */
    @Test
    fun progressFromATvIsSavedAsOfItsOwnTimeAndFeedsContinueWatching() = runBlocking {
        val key = WatchKey.episode(900002, 1, 1)
        time = 100_000
        assertTrue(library.recordRemoteProgress(final(600_000, ageMs = 2_000)))
        val row = library.state(key).first()!!
        assertEquals(600_000L to false, row.positionMs to row.watched)
        assertEquals("the Final's time: now less its age", 98_000L to 98_000L, row.updatedAt to row.lastPlayedAt)
        assertEquals(WatchKind.Episode to Triple(900002, 1, 1), row.kind to Triple(row.showTmdbId, row.season, row.episode))
        assertEquals(listOf(key), library.continueWatching.first().map { it.key })
        time = 200_000
        assertTrue(library.recordRemoteProgress(final(2_500_000, watched = true, ageMs = 0)))
        val done = library.episodeStates(900002).first().getValue(1 to 1)
        assertEquals(0L to true, done.positionMs to done.watched)
        assertEquals(emptyList<WatchState>(), library.continueWatching.first())
    }

    /** L11: a newer row on the phone stays; written over, the phone's remembered release stays with it. */
    @Test
    fun aRowNewerThanATvsFinalIsKeptAndThePhonesReleaseStays() = runBlocking {
        val release = RememberedRelease("fake", "hl-s01e01-good", "Harbor.Lights.S01E01…", "http://x/api?t=get&id=g&apikey={apikey}", 0, "abc")
        time = 50_000
        library.recordProgress(episode(1, 1), positionMs = 900_000, durationMs = 2_700_000, release = release)
        time = 52_000
        assertFalse("its Final made before the phone's own save", library.recordRemoteProgress(final(600_000, ageMs = 5_000)))
        assertEquals(900_000L, library.state(WatchKey.episode(900002, 1, 1)).first()!!.positionMs)
        time = 70_000
        assertTrue(library.recordRemoteProgress(final(1_200_000, ageMs = 1_000)))
        val row = library.state(WatchKey.episode(900002, 1, 1)).first()!!
        assertEquals(1_200_000L to 69_000L, row.positionMs to row.updatedAt)
        assertEquals(release, row.release)
    }

    @Test
    fun collectionIsSortedAndRemovalIsATombstone() = runBlocking {
        library.addShow(show(2, "the quiet orbit"))
        library.addShow(show(1, "Harbor Lights"))
        assertEquals(listOf("Harbor Lights", "the quiet orbit"), library.shows.first().map { it.name })
        library.removeShow(1)
        assertEquals(listOf("the quiet orbit"), library.shows.first().map { it.name })
        assertTrue(db.collection().get("tmdb:1")!!.deleted)
        time = 5_000
        library.addShow(show(1, "Harbor Lights"))
        assertEquals(5_000, db.collection().get("tmdb:1")!!.addedAt)
    }

    @Test
    fun progressFeedsContinueWatchingUntilNinetyPercent() = runBlocking {
        val release = RememberedRelease("fake", "hl-s01e01-good", "Harbor.Lights.S01E01…", "http://x/api?t=get&id=g&apikey={apikey}", 0, "abc")
        library.recordProgress(episode(1, 1), positionMs = 30_000, durationMs = 2_700_000, release = release)
        assertEquals(emptyList<WatchState>(), library.continueWatching.first()) // under a minute: not worth resuming

        time = 2_000
        library.recordProgress(episode(1, 1), positionMs = 600_000, durationMs = 2_700_000)
        val inProgress = library.continueWatching.first().single()
        assertEquals(600_000, inProgress.positionMs)
        assertEquals(release, inProgress.release)
        assertEquals(release, library.lastRelease(WatchKey.episode(900002, 1, 1)))

        library.recordProgress(episode(1, 1), positionMs = 2_500_000, durationMs = 2_700_000)
        assertEquals(emptyList<WatchState>(), library.continueWatching.first())
        val done = library.episodeStates(900002).first().getValue(1 to 1)
        assertTrue(done.watched)
        assertEquals(0, done.positionMs)
    }

    /** Up next lists the 40 most recent: hidden films and releases (taken out of it) don't crowd out the rest. */
    @Test
    fun continueWatchingHasTheFortyMostRecent() = runBlocking {
        (1..45).forEach { i ->
            time = 10_000L + i
            library.recordProgress(movie(900100 + i), positionMs = 600_000, durationMs = 6_000_000)
        }
        val recent = library.continueWatching.first()
        assertEquals((45 downTo 6).map { WatchKey.movie(900100 + it) }, recent.map { it.key })
    }

    /**
     * Taken out of Up next, a show stays out until any of its episodes plays again: a later one, a special, one played to
     * its end. Another show's play leaves it out. Taken out again after a play, it is out again.
     */
    @Test
    fun aShowTakenOutOfUpNextStaysOutUntilAnyOfItsEpisodesPlays() = runBlocking {
        library.recordProgress(episode(1, 2), positionMs = 900_000, durationMs = 2_700_000)
        time = 2_000
        val removal = library.removeFromUpNext(SHOW_KEY, showTmdbId = 900002)
        assertEquals(UpNextRemoval(SHOW_KEY, showTmdbId = 900002, removedAt = 2_000, updatedAt = 2_000), removal)
        assertEquals(setOf(SHOW_KEY), hidden())
        time = 3_000
        library.recordProgress(episode(1, 1, show = 900005), positionMs = 900_000, durationMs = 2_700_000)
        assertEquals(setOf(SHOW_KEY), hidden())

        // A later episode, played under a minute.
        time = 4_000
        library.recordProgress(episode(1, 3), positionMs = 30_000, durationMs = 2_700_000)
        assertEquals(emptySet<String>(), hidden())

        time = 5_000
        library.removeFromUpNext(SHOW_KEY, showTmdbId = 900002)
        assertEquals(setOf(SHOW_KEY), hidden())
        time = 6_000
        library.recordProgress(episode(0, 1), positionMs = 900_000, durationMs = 2_700_000)
        assertEquals(emptySet<String>(), hidden())

        time = 7_000
        library.removeFromUpNext(SHOW_KEY, showTmdbId = 900002)
        time = 8_000
        library.recordProgress(episode(1, 2), positionMs = 2_600_000, durationMs = 2_700_000)
        assertTrue(library.episodeStates(900002).first().getValue(1 to 2).watched)
        assertEquals(emptySet<String>(), hidden())
    }

    /** Marks don't say a show was played: marked watched (one episode or the whole show) or unwatched, it stays out. */
    @Test
    fun markingAShowTakenOutOfUpNextLeavesItOut() = runBlocking {
        library.recordProgress(episode(1, 2), positionMs = 900_000, durationMs = 2_700_000)
        time = 2_000
        library.removeFromUpNext(SHOW_KEY, showTmdbId = 900002)
        time = 3_000
        library.markEpisodes(listOf(EpisodeRef(900002, 1, 2, "Harbor Lights S01E02")), watched = true)
        library.markShows(listOf(ShowMark(900002, "Harbor Lights", listOf(1 to 3, 1 to 4))), watched = true)
        assertEquals(setOf(SHOW_KEY), hidden())
        library.markShows(listOf(ShowMark(900002, "Harbor Lights", emptyList())), watched = false)
        assertEquals(setOf(SHOW_KEY), hidden())
    }

    /**
     * Undo brings back what the removal took out, only while the removal is as it left it: one made again since stays,
     * until its own Undo. It leaves a tombstone, as every synced record does.
     */
    @Test
    fun undoBringsATitleBackOnlyWhileItsRemovalIsUnchanged() = runBlocking {
        time = 2_000
        val first = library.removeFromUpNext(SHOW_KEY, showTmdbId = 900002)
        time = 3_000
        assertTrue(library.undoUpNextRemoval(first))
        assertEquals(emptySet<String>(), hidden())
        val tombstone = db.upNextRemovals().get(SHOW_KEY)!!
        assertTrue(tombstone.deleted)
        assertEquals(3_000, tombstone.updatedAt)
        // Undone already: nothing changes.
        time = 4_000
        assertFalse(library.undoUpNextRemoval(first))
        assertEquals(3_000, db.upNextRemovals().get(SHOW_KEY)!!.updatedAt)

        time = 5_000
        val second = library.removeFromUpNext(SHOW_KEY, showTmdbId = 900002)
        time = 6_000
        val third = library.removeFromUpNext(SHOW_KEY, showTmdbId = 900002)
        time = 7_000
        assertFalse(library.undoUpNextRemoval(second))
        assertEquals(setOf(SHOW_KEY), hidden())
        assertTrue(library.undoUpNextRemoval(third))
        assertEquals(emptySet<String>(), hidden())
    }

    /** A film or a release played without TMDB is taken out by what played, and only its own play brings it back. */
    @Test
    fun aFilmOrAReleaseTakenOutOfUpNextStaysOutUntilItPlays() = runBlocking {
        val film = WatchKey.movie(900011)
        val release = WatchKey.release("n-northbound", 0)
        library.recordProgress(movie(900011), positionMs = 900_000, durationMs = 6_000_000)
        library.recordProgress(releaseOnly("n-northbound"), positionMs = 900_000, durationMs = 6_000_000)
        time = 2_000
        assertEquals(UpNextRemoval(film, watchKey = film, removedAt = 2_000, updatedAt = 2_000), library.removeFromUpNext(film, watchKey = film))
        library.removeFromUpNext(release, watchKey = release)
        assertEquals(setOf(film, release), hidden())
        time = 3_000
        library.recordProgress(movie(900012), positionMs = 900_000, durationMs = 6_000_000)
        library.recordProgress(releaseOnly("n-other"), positionMs = 900_000, durationMs = 6_000_000)
        assertEquals(setOf(film, release), hidden())
        time = 4_000
        library.recordProgress(movie(900011), positionMs = 960_000, durationMs = 6_000_000)
        assertEquals(setOf(release), hidden())
        library.recordProgress(releaseOnly("n-northbound"), positionMs = 960_000, durationMs = 6_000_000)
        assertEquals(emptySet<String>(), hidden())
        // Still in progress all along: the history is untouched.
        assertTrue(library.continueWatching.first().map { it.key }.containsAll(listOf(film, release)))
    }

    @Test
    fun seasonCanBeMarkedAndUnmarked() = runBlocking {
        library.recordProgress(episode(1, 2), positionMs = 900_000, durationMs = 2_700_000)
        library.markEpisodes((1..3).map { EpisodeRef(900002, 1, it, "E$it") }, watched = true)
        val states = library.episodeStates(900002).first()
        assertEquals(setOf(1 to 1, 1 to 2, 1 to 3), states.keys)
        assertTrue(states.values.all { it.watched && it.positionMs == 0L })
        assertEquals("Harbor Lights S1E2", states.getValue(1 to 2).title)

        library.markEpisodes(listOf(EpisodeRef(900002, 1, 3, "E3")), watched = false)
        assertFalse(library.episodeStates(900002).first().getValue(1 to 3).watched)
    }

    @Test
    fun releaseOnlyEntriesArePrunedEpisodesAreNot() = runBlocking {
        library.recordProgress(episode(1, 1), positionMs = 600_000, durationMs = 2_700_000)
        (1..205).forEach { i ->
            time = 10_000L + i
            library.recordProgress(
                WatchState(WatchKey.release("n$i", 0), WatchKind.Release, title = "Release $i", updatedAt = 0),
                positionMs = 120_000, durationMs = 2_700_000,
            )
        }
        assertNull(db.watchStates().get(WatchKey.release("n5", 0)))
        assertTrue(db.watchStates().get(WatchKey.release("n6", 0)) != null)
        assertTrue(db.watchStates().get(WatchKey.episode(900002, 1, 1)) != null)
    }

    @Test
    fun recentSearchesAreDedupedAndCapped() = runBlocking {
        library.addSearch("Harbor Lights")
        time = 2_000
        library.addSearch("  harbor   LIGHTS ")
        assertEquals(listOf("harbor LIGHTS"), library.recentSearches.first().map { it.query })
        (1..25).forEach { time = 3_000L + it; library.addSearch("query $it") }
        val recent = library.recentSearches.first()
        assertEquals(20, recent.size)
        assertEquals("query 25", recent.first().query)
        library.clearSearches()
        assertEquals(emptyList<RecentSearch>(), library.recentSearches.first())
    }

    /** A search picked from TMDB's suggestions keeps the title (#122); the latest search of the same words wins. */
    @Test
    fun aRecentSearchRemembersTheTitleItWasPickedFrom() = runBlocking {
        val show = SearchPick(TitleType.Tv, 900002, "Harbor Lights", season = 2, episode = 5)
        val movie = SearchPick(TitleType.Movie, 900010, "Copper Crown")
        library.addSearch("Harbor Lights S02E05", show)
        time = 2_000
        library.addSearch("Copper Crown", movie)
        assertEquals(listOf(movie, show), library.recentSearches.first().map { it.pick })
        time = 3_000
        library.addSearch("copper crown")
        assertEquals(listOf(null, show), library.recentSearches.first().map { it.pick })
        assertEquals(listOf("copper crown", "Harbor Lights S02E05"), library.recentSearches.first().map { it.query })
    }

    /**
     * A title opened from Search is remembered as a title, by its id: opened again from other words it is the same
     * entry, at the top again; the indexers searched for its name stay an entry of their own.
     */
    @Test
    fun aTitleOpenedIsRememberedByItsIdApartFromSearchesOfItsWords() = runBlocking {
        val show = SearchPick(TitleType.Tv, 900002, "Harbor Lights")
        library.addTitle(show)
        time = 2_000
        library.addSearch("Harbor Lights")
        assertEquals(
            listOf(RecentSearch("harbor lights", "Harbor Lights", 2_000, 2_000), RecentSearch("title:tv:900002", "Harbor Lights", 1_000, 1_000, pick = show, kind = RecentKind.Title)),
            library.recentSearches.first(),
        )
        time = 3_000
        library.addTitle(show)
        assertEquals(listOf(RecentKind.Title, RecentKind.Search), library.recentSearches.first().map { it.kind })
        assertEquals(2, library.recentSearches.first().size)
        library.removeSearch("title:tv:900002")
        assertEquals(listOf("harbor lights"), library.recentSearches.first().map { it.key })
    }

    @Test
    fun aShowRemembersItsAudioAndSubtitlesUntilReset() = runBlocking {
        assertNull(library.trackChoice(900002))
        val choice = ShowTrackChoice(900002, audioLanguage = "de", subtitles = SubtitleMode.Language, subtitleLanguage = "en")
        time = 2_000
        library.setTrackChoice(choice)
        assertEquals(choice.copy(updatedAt = 2_000), library.trackChoice(900002))
        assertNull(library.trackChoice(900003))

        time = 3_000
        library.setTrackChoice(choice.copy(subtitles = SubtitleMode.Off, subtitleLanguage = null))
        assertEquals(choice.copy(subtitles = SubtitleMode.Off, subtitleLanguage = null, updatedAt = 3_000), library.trackChoice(900002))

        // Reset leaves a tombstone, as every synced record does.
        time = 4_000
        library.clearTrackChoice(900002)
        assertNull(library.trackChoice(900002))
        val tombstone = db.trackChoices().get(900002)!!
        assertTrue(tombstone.deleted)
        assertEquals(4_000, tombstone.updatedAt)

        // Choosing again brings it back.
        time = 5_000
        library.setTrackChoice(choice.copy(audioCommentary = true))
        assertEquals(choice.copy(audioCommentary = true, updatedAt = 5_000), library.trackChoice(900002))
        // Resetting a show that has nothing remembered leaves nothing behind.
        library.clearTrackChoice(900004)
        assertNull(db.trackChoices().get(900004))
    }

    /**
     * A release that failed mid-play is no longer the one remembered for what it played, so Resume
     * and replay don't choose it again; the position stays. Only that release is forgotten (by
     * indexer and guid: another file or NZB id of it is the same release), and from the main thread.
     */
    @Test
    fun aFailedReleaseIsForgottenWhenItIsTheOneRemembered() = runBlocking<Unit> {
        val key = WatchKey.episode(900002, 1, 1)
        val failed = RememberedRelease("fake", "hl-s01e01-broken", "Harbor.Lights.S01E01.1080p.WEB-BRKN", null, 0, "abc")
        val other = failed.copy(guid = "hl-s01e01-good", title = "Harbor.Lights.S01E01.720p.WEB-GOOD")
        val main = Executor { Handler(Looper.getMainLooper()).post(it) }.asCoroutineDispatcher()

        // Nothing saved for it: nothing to forget, and no row is made.
        withContext(main) { library.forgetRelease(key, failed) }
        assertNull(db.watchStates().get(key))

        library.recordProgress(episode(1, 1), positionMs = 600_000, durationMs = 2_700_000, release = other)
        time = 2_000
        withContext(main) { library.forgetRelease(key, failed) }
        assertEquals(other, library.lastRelease(key))

        time = 3_000
        library.recordProgress(episode(1, 1), positionMs = 700_000, durationMs = 2_700_000, release = failed)
        time = 4_000
        withContext(main) { library.forgetRelease(key, failed.copy(fileIndex = 2, nzbId = "def")) }
        assertNull(library.lastRelease(key))
        val state = db.watchStates().get(key)!!
        assertEquals(700_000, state.positionMs)
        assertEquals(4_000, state.updatedAt)
        assertEquals(listOf(state), library.continueWatching.first())

        // Saving the position afterwards, without a release, doesn't bring it back.
        time = 5_000
        library.recordProgress(episode(1, 1), positionMs = 710_000, durationMs = 2_700_000)
        assertNull(library.lastRelease(key))
    }

    /**
     * A release played without TMDB saves its progress under itself; one played in place of a
     * release that failed mid-play continues where that stopped, and the failed one's progress
     * leaves Up next as a tombstone. From the main thread, as the player saves.
     */
    @Test
    fun aPlayThatTookOverAFailedReleaseRemovesItsProgress() = runBlocking<Unit> {
        fun release(key: String, title: String) = WatchState(key, WatchKind.Release, title = title, updatedAt = 0)
        val failedKey = WatchKey.release("broken", 0)
        val newKey = WatchKey.release("good", 0)
        val main = Executor { Handler(Looper.getMainLooper()).post(it) }.asCoroutineDispatcher()
        library.recordProgress(release(failedKey, "Northbound.2019.1080p.WEB-BRKN"), positionMs = 1_200_000, durationMs = 6_000_000)

        time = 2_000
        withContext(main) { library.recordProgress(release(newKey, "Northbound.2019.1080p.WEB-GOOD"), 1_230_000, 6_000_000, replaces = listOf(failedKey)) }
        assertEquals(listOf(newKey), library.continueWatching.first().map { it.key })
        val tombstone = db.watchStates().get(failedKey)!!
        assertTrue(tombstone.deleted)
        assertEquals(2_000, tombstone.updatedAt)

        // Later saves leave the tombstone as it is.
        time = 3_000
        library.recordProgress(release(newKey, "Northbound.2019.1080p.WEB-GOOD"), 1_300_000, 6_000_000, replaces = listOf(failedKey))
        assertEquals(2_000, db.watchStates().get(failedKey)!!.updatedAt)
        // Several, when more than one failed in turn: each leaves.
        val earlierKey = WatchKey.release("broken-too", 0)
        library.recordProgress(release(earlierKey, "Northbound.2019.1080p.WEB-BRKN2"), 1_100_000, 6_000_000)
        library.recordProgress(release(newKey, "Northbound.2019.1080p.WEB-GOOD"), 1_350_000, 6_000_000, replaces = listOf(earlierKey, failedKey))
        assertTrue(db.watchStates().get(earlierKey)!!.deleted)
        assertEquals(listOf(newKey), library.continueWatching.first().map { it.key })
        // Replacing itself, or a key with nothing saved, does nothing.
        library.recordProgress(episode(1, 1), 600_000, 2_700_000, replaces = listOf(WatchKey.episode(900002, 1, 1)))
        assertEquals(setOf(newKey, WatchKey.episode(900002, 1, 1)), library.continueWatching.first().map { it.key }.toSet())
        library.recordProgress(release(newKey, "Northbound.2019.1080p.WEB-GOOD"), 1_400_000, 6_000_000, replaces = listOf(WatchKey.release("none", 0)))
        assertNull(db.watchStates().get(WatchKey.release("none", 0)))
    }

    private companion object {
        /** Home's key for a show's row. */
        const val SHOW_KEY = "show:900002"
    }
}
