package io.skjaere.usenet.app.screens

import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.collection.ScaleImages
import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.app.container
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.CollectedMovie
import io.skjaere.usenet.data.CollectedShow
import io.skjaere.usenet.data.EpisodeRef
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.RecentSearch
import io.skjaere.usenet.data.SearchPick
import io.skjaere.usenet.data.TitleType
import io.skjaere.usenet.data.TriedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.metadata.ShowDetails
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.net.URI

/**
 * The README screenshots' app, staged over the screens catalogue ([titles]): the mock NNTP server as the provider, the
 * screens indexer ([indexerUrl], mock-seed's `indexer --catalog screens`) as the one indexer searched, the invented TMDB
 * key, every catalogue title collected, a show in progress, two shows begun (one with a new episode) and three recent
 * searches. TMDB is the catalogue's strict fake ([tmdb]) and the pictures come from the assets ([images]).
 *
 * [open] first checks what the device has saved: a provider, indexer or torrent indexer anywhere but the emulator's host,
 * or a debrid account, fails the run before anything shows, so no real account reaches a screenshot. The TMDB key is the
 * invented one, which TMDB would refuse, checked once saved. [close] puts everything back: the settings and the TMDB key as they were, the titles it collected
 * out of the collection, the episodes it marked or played unwatched with no position again, its searches gone, the
 * releases it tried as they were and the show's track choice cleared. TMDB's stored facts for the invented titles stay, as
 * they do for any title taken out of the collection, until the app prunes them. Then the fake and the loader fail the run
 * if anything was asked for that the catalogue doesn't have.
 */
class ScreensStage(
    val titles: ScreensTitles,
    private val indexerUrl: String,
    private val indexerKey: String,
) : AutoCloseable {
    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container
    private val settings = container.settings
    private val library = container.library
    private lateinit var saved: AppSettings
    private var keyBefore: String? = null
    private var showsBefore: Set<Int> = emptySet()
    private var moviesBefore: Set<Int> = emptySet()
    private var trackChoiceBefore = false
    private var tried: List<TriedRelease> = emptyList()
    private val searches = mutableListOf<String>()
    private var opened = false
    lateinit var tmdb: FakeTmdb
        private set
    lateinit var images: ScaleImages
        private set

    val kelpmoor: ShowDetails get() = titles.show(KELPMOOR)
    val quillon: ShowDetails get() = titles.show(QUILLON)
    val gullenkiln: ShowDetails get() = titles.show(GULLENKILN)

    fun open(): ScreensStage = runBlocking {
        saved = settings.settings.first()
        refuseRealAccounts(saved)
        keyBefore = settings.tmdbKey()
        showsBefore = library.collectedShowIds.first()
        moviesBefore = library.collectedMovieIds.first()
        trackChoiceBefore = library.trackChoice(kelpmoor.tmdbId) != null
        tried = library.triedReleaseEntries()
        opened = true

        tmdb = titles.fakeTmdb()
        images = ScaleImages.screens(InstrumentationRegistry.getInstrumentation().targetContext).also { it.install() }

        settings.setTmdbKey(FakeTmdb.INVENTED_KEY)
        if (settings.providers.first().none { it.host == HOST && it.port == NNTP_PORT && it.enabled }) {
            settings.saveProvider(ProviderRecord(id = "", name = PROVIDER_NAME, host = HOST, port = NNTP_PORT, useTls = false, connections = 8), password = null)
        }
        settings.indexers.first().filter { it.enabled }.forEach { settings.setIndexerEnabled(it.id, false) }
        settings.saveIndexer(INDEXER_ID, INDEXER_NAME, indexerUrl, indexerKey)
        settings.updateMetadata { it.copy(showArtwork = true) }
        settings.updateCollectionView { it.copy(side = "Shows", showFilter = "All", showSort = "RecentlyWatched", movieFilter = "All", movieSort = "RecentlyAdded") }
        // Cast's introduction never covers a screen: it was shown, as on a phone that has had the app a while.
        settings.updateCast { it.copy(introShown = true) }
        check(settings.tmdbKey() == FakeTmdb.INVENTED_KEY) { "the screens' TMDB key wasn't saved" }
        refuseRealAccounts(settings.settings.first())

        collect()
        watch()
        recent()
        container.refresher.refreshNow(titles.shows.keys.map(TitleKey::show) + titles.movies.keys.map(TitleKey::movie))
        this@ScreensStage
    }

    /**
     * Fails unless every account [settings] holds is on the emulator's host: its providers, indexers and torrent indexers,
     * and no debrid account. Says which kind failed, never the value.
     */
    private fun refuseRealAccounts(settings: AppSettings) {
        check(settings.activeProviders.all { it.host == HOST }) { "a saved provider isn't on the emulator's host: clear the app's data first" }
        check(settings.activeIndexers.all { host(it.baseUrl) == HOST }) { "a saved indexer isn't on the emulator's host: clear the app's data first" }
        check(settings.activeTorrentIndexers.all { host(it.baseUrl) == HOST }) { "a saved torrent indexer isn't on the emulator's host: clear the app's data first" }
        check(settings.activeServices.isEmpty()) { "a debrid account is saved: clear the app's data first" }
    }

    private fun host(url: String): String? = runCatching { URI(url).host }.getOrNull()

    /**
     * Every catalogue title collected; the three Up next shows added last, so they come first where order is by when. The
     * library stamps each with its own clock: a moment apart, so the order is the same on every run.
     */
    private suspend fun collect() {
        val start = System.currentTimeMillis() - 60_000L * (titles.shows.size + titles.movies.size)
        val upNext = setOf(kelpmoor.tmdbId, quillon.tmdbId, gullenkiln.tmdbId)
        val shows = titles.shows.values.sortedBy { it.tmdbId in upNext }
        shows.forEachIndexed { i, show ->
            val at = start + i * 60_000L
            delay(STEP_MS)
            library.addShow(
                CollectedShow(
                    CollectedShow.idFor(show.tmdbId), show.tmdbId, tvdbId = show.ids.tvdb, imdbId = show.ids.imdb, tvmazeId = show.ids.tvmaze,
                    name = show.name, year = show.year, posterPath = show.posterPath, addedAt = at, updatedAt = at,
                ),
            )
        }
        titles.movies.values.forEachIndexed { i, movie ->
            val at = start + (shows.size + i) * 60_000L
            delay(STEP_MS)
            library.addMovie(CollectedMovie(CollectedMovie.idFor(movie.tmdbId), movie.tmdbId, imdbId = movie.ids.imdb, title = movie.title, year = movie.year, posterPath = movie.posterPath, addedAt = at, updatedAt = at))
        }
    }

    /**
     * Up next's three rows and the Show page's marks: Quillon Station begun (its third episode next), The Gullenkiln Archive
     * watched up to its new fourth episode, and Kelpmoor's first three episodes watched with the fourth stopped part way,
     * played last of all. A moment apart, as [collect]'s.
     */
    private suspend fun watch() {
        library.markEpisodes(episodes(quillon, 1, 1..2), watched = true)
        delay(STEP_MS)
        library.markEpisodes(episodes(gullenkiln, 1, 1..3), watched = true)
        delay(STEP_MS)
        library.markEpisodes(episodes(kelpmoor, 1, 1..3), watched = true)
        delay(STEP_MS)
        val e4 = WatchState(
            WatchKey.episode(kelpmoor.tmdbId, 1, 4), WatchKind.Episode, showTmdbId = kelpmoor.tmdbId, season = 1, episode = 4,
            title = WatchTitle.episode(kelpmoor.name, 1, 4), updatedAt = 0,
        )
        library.recordProgress(e4, RESUME_AT_MS, kelpmoor.episodeRuntimeMinutes!! * 60_000L)
    }

    /** Three searches made before, a moment apart: two titles opened from Search, and one searched by its words. */
    private suspend fun recent() {
        val tidewick = titles.show("tidewick")
        val bollard = titles.movie("the-ninth-bollard")
        library.addSearch("brasswick tide")
        searches += "brasswick tide"
        delay(STEP_MS)
        library.addTitle(SearchPick(TitleType.Movie, bollard.tmdbId, bollard.title))
        searches += RecentSearch.titleKey(SearchPick(TitleType.Movie, bollard.tmdbId, bollard.title))
        delay(STEP_MS)
        library.addTitle(SearchPick(TitleType.Tv, tidewick.tmdbId, tidewick.name))
        searches += RecentSearch.titleKey(SearchPick(TitleType.Tv, tidewick.tmdbId, tidewick.name))
    }

    private fun episodes(show: ShowDetails, season: Int, numbers: IntRange): List<EpisodeRef> =
        numbers.map { EpisodeRef(show.tmdbId, season, it, WatchTitle.episode(show.name, season, it)) }

    /** Every episode of the shows Up next shows: what the stage marked, and what the screenshots played. */
    private fun everyEpisode(): List<EpisodeRef> = listOf(kelpmoor, quillon, gullenkiln).flatMap { show ->
        show.seasons.flatMap { s -> episodes(show, s.number, 1..s.episodeCount) }
    }

    override fun close() {
        if (!opened) return
        opened = false
        val failures = mutableListOf<Throwable>()
        fun attempt(step: () -> Unit) {
            try {
                step()
            } catch (t: Throwable) {
                failures += t
            }
        }
        attempt {
            runBlocking {
                // Its key goes with it: restore puts back the settings' file alone.
                if (saved.indexers.none { it.id == INDEXER_ID }) settings.deleteIndexer(INDEXER_ID)
                settings.restore(saved)
                settings.setTmdbKey(keyBefore)
                library.markEpisodes(everyEpisode(), watched = false)
                library.removeTitles(shows = titles.shows.keys - showsBefore, movies = titles.movies.keys - moviesBefore)
                searches.forEach { library.removeSearch(it) }
                library.restoreTriedReleases(tried)
                if (!trackChoiceBefore) library.clearTrackChoice(kelpmoor.tmdbId)
            }
        }
        attempt { images.restore() }
        attempt { tmdb.close() }
        failures.firstOrNull()?.let { first -> failures.drop(1).forEach(first::addSuppressed); throw first }
    }

    companion object {
        /** The emulator's address of the computer it runs on: the mock NNTP server and the screens indexer are there. */
        const val HOST = "10.0.2.2"
        const val NNTP_PORT = 1119
        const val INDEXER_ID = "readme-screens"
        const val INDEXER_NAME = "My indexer"
        const val PROVIDER_NAME = "My provider"

        const val KELPMOOR = "kelpmoor"
        const val QUILLON = "quillon-station"
        const val GULLENKILN = "the-gullenkiln-archive"

        /** Between two of the library's stamps, so no two share a millisecond. */
        const val STEP_MS = 5L

        /** Where Kelpmoor's fourth episode stopped: 21 of its 48 minutes. */
        const val RESUME_AT_MS = 21 * 60_000L + 14_000L
    }
}
