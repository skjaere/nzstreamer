package io.skjaere.usenet.app.play

import io.skjaere.usenet.app.collection.model.shortDate
import io.skjaere.usenet.app.search.MovieFind
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.SearchPick
import io.skjaere.usenet.data.TitleType
import io.skjaere.usenet.data.WatchKind
import java.time.LocalDate

/**
 * A movie to find a release of and play, as its page knows it: TMDB's [title] and [year], the IMDb id
 * indexers search by, its runtime, and when it comes out ([releaseDate]; [released]: TMDB lists it as
 * out, for a movie without a date).
 */
data class MovieTarget(
    val tmdbId: Int,
    val title: String,
    val year: Int?,
    val imdb: String?,
    val runtimeMinutes: Int?,
    val releaseDate: LocalDate? = null,
    val released: Boolean = true,
    /** Its original language (ISO 639-1, from TMDB): its releases without a language tag speak it. */
    val originalLanguage: String? = null,
) {
    /** "Copper Crown 2019": what a search by name types (Int.toString's digits are ASCII whatever the locale). */
    val query: String get() = listOfNotNull(title, year?.toString()).joinToString(" ")

    /** Under the title on the play sheet and in the player. */
    val subtitle: String? get() = year?.toString()

    val watch: WatchTarget get() = WatchTarget(WatchKind.Movie, title, movieTmdbId = tmdbId)

    /** The movie as a pick from TMDB's suggestions, so a search for [query] is by its id and what plays saves progress to it. */
    val pick: SearchPick get() = SearchPick(TitleType.Movie, tmdbId, title)
}

/**
 * Play on a movie's page. The release that played it before goes alone while its NZB is still stored
 * (no search, no grab, as Resume on Home does); otherwise, or when that fails, the search the Search
 * tab runs for the movie, the remembered release first unless it just failed.
 */
internal object MoviePlay {
    sealed interface Plan {
        data class RememberedAlone(val remembered: RememberedRelease) : Plan
        data object Search : Plan
    }

    fun plan(remembered: RememberedRelease?, stored: Boolean): Plan =
        if (remembered != null && stored) Plan.RememberedAlone(remembered) else Plan.Search

    /** [release] alone, the file that played in it, as Resume on Home plays it. */
    private fun remembered(target: MovieTarget, release: RememberedRelease) = PlayRequest(
        target.title, target.subtitle, listOf(Replay.candidate(release)), Replay.file(release, FilePicker.Largest, target.watch), target.watch,
        remembered = release,
    )

    /**
     * Plays [target]: [play] runs a request through the pipeline (null once the player starts, else
     * the failure); [searching] puts the sheet's line up as a search starts; [find] searches the
     * indexers. Returns what the sheet shows at the end, or null once the player starts.
     */
    suspend fun run(
        target: MovieTarget,
        remembered: RememberedRelease?,
        stored: Boolean,
        today: LocalDate,
        play: suspend (PlayRequest, Retry?) -> PlayState.Failed?,
        searching: (line: String) -> Unit,
        find: suspend () -> MovieFind,
    ): PlayState? {
        val alone = plan(remembered, stored) as? Plan.RememberedAlone
        val failed = alone?.let { play(remembered(target, it.remembered), null) ?: return null }
        // It just failed, or was skipped as found incomplete before: not tried again, nor put first.
        val retry = alone?.let { Retry.afterRemembered(Replay.key(it.remembered), failed) }
        searching(retry?.let { "${it.searchingNotice} · $SEARCHING" } ?: SEARCHING)
        val found = find()
        val candidates = retry?.leaveOut(found.playable) ?: found.playable
        if (candidates.isEmpty()) {
            return failed?.copy(search = target.query, searchPick = target.pick) ?: PlayState.NoRelease(
                target.title, target.subtitle, found.stages, movieNoReleaseNote(target.releaseDate, target.released, today), target.query, found.hidden, target.pick,
                remoteStages = found.remoteStages,
            )
        }
        val request = PlayRequest(
            target.title, target.subtitle, candidates, FilePicker.Largest, target.watch,
            searchLine = found.searchLine, rememberedFirst = alone == null, hidden = found.hidden.map { it.key }.toSet(),
        )
        return play(request, retry)
    }

    private const val SEARCHING = "Searching your indexers…"
}

/**
 * The note under what was searched, for a movie TMDB lists as still to come: when, since releases
 * often appear around then (some before). Null for one that is out. The movie page says it under Play.
 */
internal fun movieNoReleaseNote(date: LocalDate?, released: Boolean, today: LocalDate): String? = when {
    date != null && date.isAfter(today) -> "TMDB lists it for ${shortDate(date, today)}. Releases often appear around then."
    date == null && !released -> "TMDB has no release date for it yet."
    else -> null
}
