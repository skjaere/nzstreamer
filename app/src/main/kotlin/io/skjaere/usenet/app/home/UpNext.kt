package io.skjaere.usenet.app.home

import io.skjaere.usenet.app.collection.model.ShowRow
import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.app.collection.recentlyAired
import io.skjaere.usenet.app.play.ReleaseName
import io.skjaere.usenet.data.CollectedShow
import io.skjaere.usenet.data.LibraryRepository
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeRef
import io.skjaere.usenet.metadata.MovieDetails
import io.skjaere.usenet.metadata.NextUp
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.metadata.ShowProgress
import java.time.LocalDate

/** What a row's Play (or a tap on the row) does. */
sealed interface UpNextPlay {
    /** Plays the release that played [state] before, from where it stopped. */
    data class Resume(val state: WatchState) : UpNextPlay

    /** Finds and plays a collected show's next episode, as the Show screen's Play does. */
    data class NextEpisode(val show: CollectedShow, val episode: EpisodeRef) : UpNextPlay
}

/** One row of Home's Up next: a show, film or release. */
data class UpNextRow(
    val title: String,
    /** "S02E05 · The Long Tide · 30 min left", "S03E01 · Low Water · 12 unwatched". */
    val line: String,
    /** How far in, for a row in progress; null for a next episode. */
    val progress: Float?,
    /** The episode aired within the last week. */
    val new: Boolean,
    val posterPath: String?,
    val play: UpNextPlay,
    /** What the row's Play button says it plays, for TalkBack: "Resume Harbor Lights S02E05". */
    val playLabel: String,
    /**
     * A TV's card's one line under its title, shorter than [line]: what is left of one in progress ("S02E05 · 30 min left",
     * "2019 · 42 min left"), a next episode's name, else how far the show is ("S01E02 · Low Water", "S03E01 · 12 unwatched").
     */
    val cardLine: String = line,
    /** A TV's card's 16:9 picture: the episode's still, the movie's backdrop, or that of a show not collected; null for none. */
    val landscapePath: String? = null,
) {
    /**
     * The show or movie the row is of, whose page a TV's card opens; null for a release played without TMDB, which has
     * none, and for a special in progress: the Show page's Resume and Play are for the show's regular episodes, so the
     * page wouldn't play it. Those cards play.
     */
    val titleKey: TitleKey?
        get() = when (play) {
            is UpNextPlay.Resume -> when {
                play.state.season == 0 -> null
                else -> play.state.showOf()?.let(TitleKey::show) ?: play.state.movieTmdbId?.takeIf { play.state.kind == WatchKind.Movie }?.let(TitleKey::movie)
            }
            is UpNextPlay.NextEpisode -> TitleKey.show(play.show.tmdbId)
        }

    /**
     * What the row is about, the same while it moves: a show's (one row per show, whether its next
     * episode or one in progress), else what played. Home keys its rows by it, so a TV remote's
     * focus stays on a show that moved up once its next episode started playing; a row taken out of
     * Up next is taken out by it ([io.skjaere.usenet.data.UpNextRemoval.id]).
     */
    val key: String
        get() = when (play) {
            is UpNextPlay.Resume -> UpNext.key(play.state)
            is UpNextPlay.NextEpisode -> UpNext.showKey(play.show.tmdbId)
        }
}

/** The show an episode's play belongs to; null for a film or a release. */
private fun WatchState.showOf(): Int? = showTmdbId.takeIf { kind == WatchKind.Episode }

data class SeasonKey(val showTmdbId: Int, val season: Int)

/** What TMDB has answered so far: a missing entry is still loading (or wasn't asked for), null couldn't be loaded. */
data class TmdbAnswers(
    val shows: Map<Int, ShowDetails?> = emptyMap(),
    val seasons: Map<SeasonKey, Season?> = emptyMap(),
    val movies: Map<Int, MovieDetails?> = emptyMap(),
) {
    fun episode(show: Int, season: Int, episode: Int): Episode? = seasons[SeasonKey(show, season)]?.episodes?.firstOrNull { it.episode == episode }
}

/**
 * Home's Up next, from the user's own history and collection: first what is in progress (episodes,
 * films and releases), most recently played first; then the next aired episode of each collected
 * show that was started, the show watched most recently first. A show never started stays out
 * (Collection lists it): Up next is for what was begun. One row per show, its
 * episode in progress ahead of its next one. TMDB only adds to rows in progress (names, posters,
 * New), so they show before it answers; next episodes need it, and a TMDB key.
 */
object UpNext {
    const val MAX_ROWS = 10

    /** The key of the row [state]'s play gives ([UpNextRow.key]): its show's, else its own. */
    fun key(state: WatchState): String = state.showOf()?.let(::showKey) ?: state.key

    fun showKey(tmdbId: Int) = "show:$tmdbId"

    /**
     * [inProgress]: the recent list and every collected show's episodes in progress; [collected]: the
     * collection's rows, whose stored facts and progress give the next episodes. [tmdb] has what TMDB
     * answered for the rest (titles in progress that aren't collected, the seasons' episodes). [hidden]:
     * the keys of the rows taken out of Up next ([LibraryRepository.upNextHidden]), which go before the
     * ten are counted.
     */
    fun rows(
        inProgress: List<WatchState>,
        collected: List<ShowRow>,
        tmdb: TmdbAnswers,
        hasTmdbKey: Boolean,
        today: LocalDate,
        hidden: Set<String> = emptySet(),
    ): List<UpNextRow> {
        val byId = collected.associateBy { it.show.tmdbId }
        // A show's episode in progress counts even when the recent list has run past it.
        val resumed = inProgress
            .filter { !it.watched && !it.deleted && it.positionMs >= LibraryRepository.MIN_RESUME_MS }
            .distinctBy { it.key }
            .sortedByDescending { it.lastPlayedAt }
            .distinctBy { it.showOf() ?: it.key }
        val busy = resumed.mapNotNull { it.showOf() }.toSet()
        val next = if (!hasTmdbKey) emptyList() else collected
            .filter { it.show.tmdbId !in busy }
            .mapNotNull { row ->
                val details = row.details ?: return@mapNotNull null
                val progress = row.progress ?: return@mapNotNull null
                // As the collection's Not started: a play under a minute, only specials or everything marked unwatched.
                if (!progress.started) return@mapNotNull null
                val up = progress.nextUp as? NextUp.Play ?: return@mapNotNull null
                NextRow(nextRow(row.show, details, up, progress, tmdb, today), row.lastWatchedAt, row.show.addedAt)
            }
            .sortedWith(compareByDescending<NextRow> { it.lastPlayedAt }.thenByDescending { it.addedAt })
            .map { it.row }
        return (resumed.map { state -> byId[state.showTmdbId].let { resumeRow(state, it?.show, it?.details, tmdb, today) } } + next)
            .filter { it.key !in hidden }
            .take(MAX_ROWS)
    }

    /** The seasons whose episodes [rows] name, to ask TMDB for. */
    fun seasons(rows: List<UpNextRow>): Set<SeasonKey> = rows.mapNotNull { row ->
        when (val play = row.play) {
            is UpNextPlay.Resume -> play.state.takeIf { it.kind == WatchKind.Episode }?.let { st -> st.showTmdbId?.let { id -> st.season?.let { SeasonKey(id, it) } } }
            is UpNextPlay.NextEpisode -> SeasonKey(play.show.tmdbId, play.episode.season)
        }
    }.toSet()

    private class NextRow(val row: UpNextRow, val lastPlayedAt: Long, val addedAt: Long)

    /** [show] and [stored], its facts, when the show is collected. */
    private fun resumeRow(state: WatchState, show: CollectedShow?, stored: ShowDetails?, tmdb: TmdbAnswers, today: LocalDate): UpNextRow {
        val progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
        val left = timeLeft(state)
        val id = state.showOf()
        val season = state.season
        val number = state.episode
        if (id != null && season != null && number != null) {
            val ref = EpisodeRef(season, number)
            val details = stored ?: tmdb.shows[id]
            val episode = tmdb.episode(id, season, number)
            val title = show?.name ?: details?.name ?: state.title.removeSuffix(" ${ref.label}")
            val name = episode?.name ?: details?.summaryName(ref)
            return UpNextRow(
                title, listOfNotNull(ref.label, name, left).joinToString(" · "), progress,
                isNew(episode?.airDate ?: details?.airDate(ref), today), show?.posterPath ?: details?.posterPath, UpNextPlay.Resume(state),
                "Resume $title ${ref.label}", cardLine = listOfNotNull(ref.label, left ?: name).joinToString(" · "),
                // A collected show's stored facts keep no backdrop: TMDB's own answer is asked for only of shows not collected.
                landscapePath = episode?.stillPath ?: tmdb.shows[id]?.takeIf { show == null }?.backdropPath,
            )
        }
        if (state.kind == WatchKind.Movie) {
            val movie = state.movieTmdbId?.let { tmdb.movies[it] }
            return UpNextRow(
                state.title, listOfNotNull(movie?.year?.toString(), left).joinToString(" · "), progress, false, movie?.posterPath, UpNextPlay.Resume(state),
                "Resume ${state.title}", landscapePath = movie?.backdropPath,
            )
        }
        // A release played without TMDB: named by what its name says.
        val name = ReleaseName(state.title)
        return UpNextRow(
            name.title, listOfNotNull(name.tag, left).joinToString(" · "), progress, false, null, UpNextPlay.Resume(state), "Resume ${name.query}",
        )
    }

    private fun nextRow(show: CollectedShow, details: ShowDetails, up: NextUp.Play, progress: ShowProgress, tmdb: TmdbAnswers, today: LocalDate): UpNextRow {
        val ref = up.episode
        val episode = tmdb.episode(show.tmdbId, ref.season, ref.episode)
        val state = progress.unwatched.takeIf { it > 1 }?.let { "$it unwatched" }
        val name = episode?.name ?: details.summaryName(ref)
        return UpNextRow(
            show.name, listOfNotNull(ref.label, name, state).joinToString(" · "), null,
            isNew(episode?.airDate ?: details.airDate(ref), today), show.posterPath ?: details.posterPath, UpNextPlay.NextEpisode(show, ref),
            "Play ${show.name} ${ref.label}", cardLine = listOfNotNull(ref.label, name ?: state).joinToString(" · "), landscapePath = episode?.stillPath,
        )
    }

    /** The name of [ref] when it is the show's last or next episode, which its details carry. */
    private fun ShowDetails.summaryName(ref: EpisodeRef): String? =
        listOfNotNull(lastEpisode, nextEpisode).firstOrNull { it.season == ref.season && it.episode == ref.episode }?.name

    private fun ShowDetails.airDate(ref: EpisodeRef): LocalDate? =
        recentlyAired()[ref] ?: nextEpisode?.takeIf { it.season == ref.season && it.episode == ref.episode }?.airDate

    /** Aired within the last week, as the collection's NEW counts it. */
    private fun isNew(airDate: LocalDate?, today: LocalDate): Boolean =
        airDate != null && !airDate.isBefore(today.minusDays(7)) && !airDate.isAfter(today)

    private fun timeLeft(state: WatchState): String? {
        if (state.durationMs <= 0) return null
        val minutes = ((state.durationMs - state.positionMs).coerceAtLeast(0) + 59_999) / 60_000
        return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min left" else "$minutes min left"
    }
}
