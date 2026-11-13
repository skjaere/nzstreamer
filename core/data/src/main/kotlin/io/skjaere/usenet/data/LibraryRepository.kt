package io.skjaere.usenet.data

import androidx.room.withTransaction
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.releases.ReleaseKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** One episode the caller wants to mark, with the title to store if it has no state yet. */
data class EpisodeRef(val showTmdbId: Int, val season: Int, val episode: Int, val title: String) {
    val key: String get() = WatchKey.episode(showTmdbId, season, episode)
}

class LibraryRepository(
    private val db: LibraryDatabase,
    private val clock: Clock = Clock.System,
) {
    private val collection = db.collection()
    private val movieCollection = db.movies()
    private val summaries = db.summaries()
    private val watch = db.watchStates()
    private val searches = db.recentSearches()
    private val trackChoices = db.trackChoices()
    private val tried = db.triedReleases()
    private val upNextRemovals = db.upNextRemovals()

    // Collection

    val shows: Flow<List<CollectedShow>> = collection.shows()
    val movies: Flow<List<CollectedMovie>> = movieCollection.movies()

    fun isCollected(tmdbId: Int): Flow<Boolean> = collection.isCollected(CollectedShow.idFor(tmdbId))

    fun isMovieCollected(tmdbId: Int): Flow<Boolean> = movieCollection.isCollected(CollectedMovie.idFor(tmdbId))

    /** The movie's collection row, a removed one ([CollectedMovie.deleted]) included: what names its page when TMDB can't. */
    fun movie(tmdbId: Int): Flow<CollectedMovie?> = movieCollection.movie(CollectedMovie.idFor(tmdbId))

    /** The collected titles' TMDB ids, without reading the rows. */
    val collectedShowIds: Flow<Set<Int>> = collection.ids().map { it.toSet() }
    val collectedMovieIds: Flow<Set<Int>> = movieCollection.ids().map { it.toSet() }

    val counts: Flow<CollectionCounts> = collection.counts()

    suspend fun addShow(show: CollectedShow) {
        val now = clock.now()
        val existing = collection.get(show.id)
        collection.upsert(show.copy(addedAt = existing?.takeIf { !it.deleted }?.addedAt ?: now, updatedAt = now, deleted = false))
    }

    /** As [addShow]: added again, a movie keeps when it was added, unless it was removed meanwhile. */
    suspend fun addMovie(movie: CollectedMovie) {
        val now = clock.now()
        val existing = movieCollection.get(movie.id)
        movieCollection.upsert(movie.copy(addedAt = existing?.takeIf { !it.deleted }?.addedAt ?: now, updatedAt = now, deleted = false))
    }

    /** Watch history of the show is kept, so adding it back restores progress. */
    suspend fun removeShow(tmdbId: Int) {
        removeTitles(shows = listOf(tmdbId))
    }

    suspend fun removeMovie(tmdbId: Int) {
        removeTitles(movies = listOf(tmdbId))
    }

    /**
     * Removes titles from the collection in one transaction, leaving tombstones. Watch history stays,
     * so a title added again resumes where it was. Returns the ones it removed (not those removed
     * already or never collected), for [undoRemove].
     */
    suspend fun removeTitles(shows: Collection<Int> = emptyList(), movies: Collection<Int> = emptyList()): Removal {
        val now = clock.now()
        return db.withTransaction {
            Removal(
                shows = tombstone(shows, CollectedShow::idFor, collection::live, collection::tombstone, now),
                movies = tombstone(movies, CollectedMovie::idFor, movieCollection::live, movieCollection::tombstone, now),
                at = now,
            )
        }
    }

    /** The ones of [tmdbIds] that are collected, tombstoned at [now], in order. */
    private suspend fun tombstone(
        tmdbIds: Collection<Int>,
        idFor: (Int) -> String,
        live: suspend (List<String>) -> List<String>,
        tombstone: suspend (List<String>, Long) -> Int,
        now: Long,
    ): List<Int> = tmdbIds.distinct().chunked(CHUNK).flatMap { chunk ->
        val ids = chunk.map(idFor)
        val collected = live(ids).toSet()
        tombstone(ids, now)
        chunk.filter { idFor(it) in collected }
    }

    /**
     * Brings back what [removal] removed, as it was added (one added or removed again since stays as
     * that left it). Returns how many came back.
     */
    suspend fun undoRemove(removal: Removal): Int {
        val now = clock.now()
        return db.withTransaction {
            removal.shows.chunked(CHUNK).sumOf { collection.untombstone(it.map(CollectedShow::idFor), removal.at, now) } +
                removal.movies.chunked(CHUNK).sumOf { movieCollection.untombstone(it.map(CollectedMovie::idFor), removal.at, now) }
        }
    }

    // What TMDB said of the collected titles, for the lists

    val showSummaries: Flow<List<ShowSummary>> = summaries.shows()
    val movieSummaries: Flow<List<MovieSummary>> = summaries.movies()

    fun showSummary(tmdbId: Int): Flow<ShowSummary?> = summaries.show(tmdbId)

    fun movieSummary(tmdbId: Int): Flow<MovieSummary?> = summaries.movie(tmdbId)

    /** Titles TMDB has told nothing of yet whose last refresh failed; emits when that changes, not on every refresh. */
    val factlessFailures: Flow<List<RefreshFailure>> = summaries.factlessFailures().distinctUntilChanged()

    /** The collected shows and movies with their bookkeeping, for the refresher. */
    val refreshCandidates: Flow<Pair<List<RefreshCandidate>, List<RefreshCandidate>>> =
        combine(collection.refreshCandidates(), movieCollection.refreshCandidates()) { shows, movies -> shows to movies }

    /**
     * Stores what the refresher learnt, in one transaction. Facts equal to the stored ones are not
     * written, as a write wakes every list reading the table even when it changes nothing; the
     * bookkeeping always is (no list reads it).
     */
    suspend fun saveSummaries(shows: List<ShowSummary>, movies: List<MovieSummary>, refresh: List<SummaryRefresh>) {
        db.withTransaction {
            val changedShows = shows.chunked(CHUNK).flatMap { chunk ->
                val stored = summaries.showsIn(chunk.map { it.tmdbId }).associateBy { it.tmdbId }
                chunk.filter { stored[it.tmdbId] != it }
            }
            val changedMovies = movies.chunked(CHUNK).flatMap { chunk ->
                val stored = summaries.moviesIn(chunk.map { it.tmdbId }).associateBy { it.tmdbId }
                chunk.filter { stored[it.tmdbId] != it }
            }
            if (changedShows.isNotEmpty()) summaries.upsertShows(changedShows)
            if (changedMovies.isNotEmpty()) summaries.upsertMovies(changedMovies)
            if (refresh.isNotEmpty()) summaries.upsertRefresh(refresh)
        }
    }

    /** Every title due now (the TMDB language changed); the facts stay until the new answers come. */
    suspend fun invalidateSummaries() = summaries.invalidateAll()

    /** Forgets the facts and bookkeeping of titles no longer collected that TMDB was last asked about before [before]. */
    suspend fun pruneSummaries(before: Long) = db.withTransaction {
        summaries.pruneShows(before)
        summaries.pruneMovies(before)
        summaries.pruneRefresh(before)
    }

    // Watch state

    fun episodeStates(showTmdbId: Int): Flow<Map<Pair<Int, Int>, WatchState>> =
        watch.forShow(showTmdbId).map { states -> states.associateBy { (it.season ?: 0) to (it.episode ?: 0) } }

    /** Started but not finished, most recent first; 40, so films and releases taken out of Home's Up next don't crowd out the rest of its ten. */
    val continueWatching: Flow<List<WatchState>> = watch.inProgress(minPositionMs = MIN_RESUME_MS, limit = 40)

    fun state(key: String): Flow<WatchState?> = watch.observe(key)

    /** Every show's episode watches in one query, for the lists. */
    fun showWatchAggregates(): Flow<List<ShowWatchAggregate>> = watch.showWatchAggregates(MIN_RESUME_MS)

    val movieWatches: Flow<List<MovieWatch>> = watch.movieWatches()

    /** The in-progress episodes of collected shows, in one query. */
    val inProgressOfCollected: Flow<List<WatchState>> = watch.inProgressOfCollected(MIN_RESUME_MS)

    suspend fun lastRelease(key: String): RememberedRelease? = watch.get(key)?.takeIf { !it.deleted }?.release

    /**
     * Saves the position of something playing. Crossing [WATCHED_FRACTION] of the duration
     * marks it watched, which also drops it from Continue watching. [replaces] are the keys of
     * releases played without TMDB that failed mid-play and whose progress this play took over
     * under its own key: they leave Continue watching as tombstones.
     */
    suspend fun recordProgress(
        template: WatchState,
        positionMs: Long,
        durationMs: Long,
        release: RememberedRelease? = null,
        replaces: Collection<String> = emptyList(),
    ) {
        val now = clock.now()
        val finished = durationMs > 0 && positionMs >= durationMs * WATCHED_FRACTION
        // Read and written in one transaction, so a release forgotten meanwhile isn't written back.
        db.withTransaction {
            val existing = watch.get(template.key)?.takeIf { !it.deleted }
            val state = (existing ?: template).copy(
                title = template.title,
                positionMs = if (finished) 0 else positionMs,
                durationMs = durationMs,
                watched = finished || (existing?.watched == true && positionMs < MIN_RESUME_MS),
                lastPlayedAt = now,
                release = release ?: existing?.release,
                updatedAt = now,
                deleted = false,
            )
            val replaced = replaces.filter { it != template.key }.mapNotNull { watch.get(it) }.filterNot { it.deleted }
            watch.upsert(listOf(state) + replaced.map { it.copy(deleted = true, updatedAt = now) })
            if (state.kind == WatchKind.Release) watch.pruneReleases(MAX_RELEASE_ENTRIES)
        }
    }

    /**
     * Saves where a play this phone started on a TV stopped (W5, L11), as [RemoteProgress.decide] says: as if it had played
     * here, as of the Final's own time, over a row that isn't newer, the phone's remembered release kept. Its watch key is
     * [RemoteProgress.key]'s, which the caller checked. Read and written in one transaction. Whether it wrote.
     */
    suspend fun recordRemoteProgress(final: LinkMessage.Final): Boolean {
        val now = clock.now()
        return db.withTransaction {
            val next = RemoteProgress.decide(watch.get(final.watchKey), final, now) ?: return@withTransaction false
            watch.upsert(listOf(next))
            true
        }
    }

    /**
     * Forgets [release] as the one that played [key] when it is the one remembered (the same
     * indexer and guid): it failed mid-play, and Resume and replay would otherwise choose it
     * again. The position and the rest of the history stay.
     */
    suspend fun forgetRelease(key: String, release: RememberedRelease) = db.withTransaction {
        val existing = watch.get(key)?.takeIf { !it.deleted } ?: return@withTransaction
        val remembered = existing.release ?: return@withTransaction
        if (remembered.indexerId == release.indexerId && remembered.guid == release.guid) {
            watch.upsert(listOf(existing.copy(release = null, updatedAt = clock.now())))
        }
    }

    /** Marks episodes (one, or a whole season) watched or unwatched; clears their resume point. */
    suspend fun markEpisodes(episodes: List<EpisodeRef>, watched: Boolean) {
        val now = clock.now()
        db.withTransaction {
            val states = episodes.map { ref ->
                val existing = watch.get(ref.key)?.takeIf { !it.deleted }
                (existing ?: WatchState(
                    key = ref.key, kind = WatchKind.Episode, showTmdbId = ref.showTmdbId, season = ref.season,
                    episode = ref.episode, title = ref.title, updatedAt = now,
                )).copy(watched = watched, positionMs = 0, updatedAt = now, deleted = false)
            }
            watch.upsert(states)
        }
    }

    /**
     * Marks whole shows: watched, each show's [ShowMark.episodes]; unwatched, every episode state the
     * shows have (the position too; when they played and the release stay). Returns what it replaced,
     * for [undoMarks]. [SHOWS_PER_TRANSACTION] shows per transaction, so marking hundreds doesn't hold
     * the database for long.
     */
    suspend fun markShows(shows: List<ShowMark>, watched: Boolean): WatchSnapshot {
        val at = clock.now()
        val parts = shows.chunked(SHOWS_PER_TRANSACTION).map { chunk ->
            db.withTransaction {
                if (watched) {
                    val templates = chunk.flatMap { show ->
                        show.episodes.map { (s, e) ->
                            WatchState(
                                key = WatchKey.episode(show.tmdbId, s, e), kind = WatchKind.Episode, showTmdbId = show.tmdbId, season = s, episode = e,
                                title = WatchTitle.episode(show.name, s, e), updatedAt = at,
                            )
                        }
                    }
                    mark(templates.map { it.key }.chunked(CHUNK).flatMap { watch.getAll(it) }, templates, watched, at)
                } else {
                    mark(watch.episodesOf(chunk.map { it.tmdbId }), emptyList(), watched, at)
                }
            }
        }
        return WatchSnapshot(parts.flatMap { it.before }, parts.flatMap { it.created }, at)
    }

    /** As [markShows], for movies: unwatched changes only movies that have a state. */
    suspend fun markMovies(movies: List<MovieMark>, watched: Boolean): WatchSnapshot {
        val at = clock.now()
        val parts = movies.chunked(CHUNK).map { chunk ->
            db.withTransaction {
                val templates = chunk.map { WatchState(key = WatchKey.movie(it.tmdbId), kind = WatchKind.Movie, movieTmdbId = it.tmdbId, title = it.title, updatedAt = at) }
                mark(watch.getAll(templates.map { it.key }), if (watched) templates else emptyList(), watched, at)
            }
        }
        return WatchSnapshot(parts.flatMap { it.before }, parts.flatMap { it.created }, at)
    }

    /**
     * Sets the live ones of [stored] watched or not, the position cleared, and creates [templates]
     * without a live state. States already so are left as they are (no change, no new time).
     */
    private suspend fun mark(stored: List<WatchState>, templates: List<WatchState>, watched: Boolean, at: Long): WatchSnapshot {
        val byKey = stored.associateBy { it.key }
        val changed = stored.filter { !it.deleted && (it.watched != watched || it.positionMs != 0L) }
        // None yet, or a tombstone.
        val missing = templates.distinctBy { it.key }.filter { byKey[it.key]?.deleted != false }
        watch.upsert((changed + missing).map { it.copy(watched = watched, positionMs = 0, updatedAt = at, deleted = false) })
        return WatchSnapshot(before = changed + missing.mapNotNull { byKey[it.key] }, created = missing.filter { it.key !in byKey }.map { it.key }, at = at)
    }

    /**
     * Undoes a bulk mark: the states it replaced come back and the ones it created become tombstones,
     * each only while still as the mark left it (a play or a mark since stays). Returns how many.
     */
    suspend fun undoMarks(snapshot: WatchSnapshot): Int {
        val now = clock.now()
        val before = snapshot.before.associateBy { it.key }
        return (before.keys + snapshot.created).chunked(CHUNK).sumOf { keys ->
            db.withTransaction {
                val undone = watch.getAll(keys).filter { it.updatedAt == snapshot.at }
                    .map { state -> before[state.key]?.copy(updatedAt = now) ?: state.copy(deleted = true, updatedAt = now) }
                watch.upsert(undone)
                undone.size
            }
        }
    }

    // Taken out of Home's Up next

    /** The keys of Home's Up next rows taken out of it ([UpNextRemoval.id]); emits when that changes, not on every save of a play. */
    val upNextHidden: Flow<Set<String>> = upNextRemovals.hidden().map { it.toSet() }.distinctUntilChanged()

    /**
     * Takes [id], a row of Home's Up next, out of it until a play: of any episode of [showTmdbId]'s show, else of
     * [watchKey]. Taken out again, it is from now on. Returns the removal, for [undoUpNextRemoval].
     */
    suspend fun removeFromUpNext(id: String, showTmdbId: Int? = null, watchKey: String? = null): UpNextRemoval {
        require((showTmdbId == null) != (watchKey == null)) { "a show or what played, not both: $id" }
        val now = clock.now()
        return UpNextRemoval(id, showTmdbId, watchKey, removedAt = now, updatedAt = now).also { upNextRemovals.upsert(it) }
    }

    /** Brings back what [removal] took out, while it is as the removal left it (not one made again since). Whether it did. */
    suspend fun undoUpNextRemoval(removal: UpNextRemoval): Boolean = upNextRemovals.undo(removal.id, removal.updatedAt, clock.now()) > 0

    // Recent searches

    val recentSearches: Flow<List<RecentSearch>> = searches.recent(MAX_SEARCHES)

    /** Remembers a search; [pick] is the TMDB title it was picked from, which a later search of the same words without one drops. */
    suspend fun addSearch(query: String, pick: SearchPick? = null) {
        val trimmed = query.trim().replace(Regex("\\s+"), " ")
        if (trimmed.isEmpty()) return
        val now = clock.now()
        db.withTransaction {
            searches.upsert(RecentSearch(trimmed.lowercase(), trimmed, searchedAt = now, updatedAt = now, pick = pick))
            searches.trim(MAX_SEARCHES, now)
        }
    }

    /**
     * Remembers a TMDB [title] opened from Search (its page), by its id: opened again from other words it is the same
     * entry. A search of the indexers for its name is another.
     */
    suspend fun addTitle(title: SearchPick) {
        val now = clock.now()
        db.withTransaction {
            searches.upsert(RecentSearch(RecentSearch.titleKey(title), title.name, searchedAt = now, updatedAt = now, pick = title, kind = RecentKind.Title))
            searches.trim(MAX_SEARCHES, now)
        }
    }

    suspend fun clearSearches() = searches.clear(clock.now())

    suspend fun removeSearch(key: String) = searches.remove(key, clock.now())

    // Audio and subtitles per show

    /** The audio and subtitles picked for the show's episodes; null when none were, or they were reset. */
    suspend fun trackChoice(showTmdbId: Int): ShowTrackChoice? = trackChoices.get(showTmdbId)?.takeIf { !it.deleted }

    /** Remembers [choice] for its show, replacing what was there. */
    suspend fun setTrackChoice(choice: ShowTrackChoice) = trackChoices.upsert(choice.copy(updatedAt = clock.now(), deleted = false))

    /** "Reset to my defaults": the show's episodes follow the preferences again. Leaves a tombstone. */
    suspend fun clearTrackChoice(showTmdbId: Int) = trackChoices.clear(showTmdbId, clock.now())

    // Releases found missing on the providers

    /** What is remembered of the releases [ids] ([ReleaseChecks.key]), by id; expired entries until pruned. */
    suspend fun triedReleases(ids: Collection<String>): Map<String, TriedRelease> =
        ids.distinct().chunked(CHUNK).flatMap { tried.getAll(it) }.associateBy { it.id }

    /** [triedReleases], and again whenever what is remembered changes: a release found missing, one that played, Clear. */
    fun observeTriedReleases(ids: Collection<String>): Flow<Map<String, TriedRelease>> {
        val chunks = ids.distinct().chunked(CHUNK).map { tried.observe(it) }
        if (chunks.isEmpty()) return flowOf(emptyMap())
        return combine(chunks) { found -> found.flatMap { it }.associateBy { it.id } }
    }

    /**
     * Remembers [indexerId]/[guid] as missing now on [providers] ([ReleaseChecks.provider]s), or as an
     * NZB that lacks files ([incomplete]), with what is remembered of it already; past
     * [ReleaseChecks.MAX_ENTRIES] the least recently checked are forgotten. Returns the entry.
     */
    suspend fun recordMissing(indexerId: String, guid: String, providers: Collection<String>, incomplete: Boolean = false): TriedRelease =
        db.withTransaction {
            val existing = tried.get(ReleaseKey.of(indexerId, guid))
            ReleaseChecks.record(existing, indexerId, guid, providers, incomplete, clock.now()).also {
                tried.upsert(listOf(it))
                tried.trim(ReleaseChecks.MAX_ENTRIES)
            }
        }

    /**
     * Remembers [indexerId]/[guid] as needing a password it doesn't come with (its archive encrypted, no password in its
     * NZB or from its indexer), whatever the providers, with what is remembered of it already. Returns the entry.
     */
    suspend fun recordPasswordNeeded(indexerId: String, guid: String): TriedRelease =
        db.withTransaction {
            val existing = tried.get(ReleaseKey.of(indexerId, guid))
            ReleaseChecks.record(existing, indexerId, guid, emptyList(), incomplete = false, clock.now(), needsPassword = true).also {
                tried.upsert(listOf(it))
                tried.trim(ReleaseChecks.MAX_ENTRIES)
            }
        }

    /**
     * Remembers [indexerId]/[guid] as a release whose files are a copy of a disc (W103), whatever the providers, with what
     * is remembered of it already. Returns the entry.
     */
    suspend fun recordDiscCopy(indexerId: String, guid: String): TriedRelease =
        db.withTransaction {
            val existing = tried.get(ReleaseKey.of(indexerId, guid))
            ReleaseChecks.record(existing, indexerId, guid, emptyList(), incomplete = false, clock.now(), discCopy = true).also {
                tried.upsert(listOf(it))
                tried.trim(ReleaseChecks.MAX_ENTRIES)
            }
        }

    /**
     * Forgets what is remembered of [indexerId]/[guid] for [providers] ([ReleaseChecks.provider]s), and
     * its NZB lacking files: it just played with them ([ReleaseChecks.found]). The entry goes once
     * nothing is left of it.
     */
    suspend fun recordFound(indexerId: String, guid: String, providers: Collection<String>) = db.withTransaction {
        val id = ReleaseKey.of(indexerId, guid)
        val existing = tried.get(id) ?: return@withTransaction
        ReleaseChecks.found(existing, providers, clock.now())?.let { tried.upsert(listOf(it)) } ?: tried.delete(id)
    }

    /**
     * How many releases are remembered as missing (Settings › Cache): those with a check that hasn't expired
     * ([ReleaseChecks.EXPIRY_MS]), not the expired ones still stored until [pruneTriedReleases].
     */
    suspend fun triedReleaseCount(): Int = tried.count(clock.now() - ReleaseChecks.EXPIRY_MS)

    /** Forgets them all (Settings › Cache's Clear): each is grabbed and checked again. */
    suspend fun clearTriedReleases() = tried.clear()

    /** Forgets those whose every check has expired ([ReleaseChecks.EXPIRY_MS]); the app does at its start. */
    suspend fun pruneTriedReleases() = tried.prune(clock.now() - ReleaseChecks.EXPIRY_MS)

    /** Every entry, for device tests, which put back what they found ([restoreTriedReleases]). */
    suspend fun triedReleaseEntries(): List<TriedRelease> = tried.all()

    /** Replaces every entry with [saved], read with [triedReleaseEntries] earlier: for device tests, which must leave the library as they found it. */
    suspend fun restoreTriedReleases(saved: List<TriedRelease>) = db.withTransaction {
        tried.clear()
        saved.chunked(CHUNK).forEach { tried.upsert(it) }
    }

    companion object {
        const val WATCHED_FRACTION = 0.9
        const val MIN_RESUME_MS = 60_000L
        const val MAX_RELEASE_ENTRIES = 200
        const val MAX_SEARCHES = 20

        /** Ids per statement: SQLite takes at most 999 variables before Android 11. */
        private const val CHUNK = 500
        private const val SHOWS_PER_TRANSACTION = 50
    }
}
