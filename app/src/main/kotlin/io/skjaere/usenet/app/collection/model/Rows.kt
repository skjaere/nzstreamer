package io.skjaere.usenet.app.collection.model

import androidx.compose.runtime.Immutable
import io.skjaere.usenet.app.collection.recentlyAired
import io.skjaere.usenet.data.CollectedMovie
import io.skjaere.usenet.data.CollectedShow
import io.skjaere.usenet.data.LibraryRepository
import io.skjaere.usenet.data.MovieSummary
import io.skjaere.usenet.data.MovieWatch
import io.skjaere.usenet.data.RefreshFailure
import io.skjaere.usenet.data.ShowSummary
import io.skjaere.usenet.data.ShowWatchAggregate
import io.skjaere.usenet.data.SummaryRefresh
import io.skjaere.usenet.metadata.EpisodeRef
import io.skjaere.usenet.metadata.Freshness
import io.skjaere.usenet.metadata.NextUp
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.metadata.ShowProgress
import java.time.LocalDate

/** A row of the collection, a show's or a movie's: everything the list shows of it, worked out once. */
sealed interface TitleRow {
    val key: TitleKey
    val text: TitleText
    val addedAt: Long
    /** When it last played; 0 never. */
    val lastWatchedAt: Long
    val load: LoadState
    /** The line under the title. */
    val status: String
    /** A TV poster's one line under its title, short enough for one line at its 13 sp: "S02E05", "Back 14 Nov", "2019 · 1 h 58". */
    val cardLine: String
    /** The row as TalkBack reads it, units spelled out ("season 2, episode 5", "42 minutes left"). */
    val a11y: String
    val posterPath: String?
}

/**
 * A collected show and its progress, from the stored facts ([details], null until TMDB has answered)
 * and its watches: [progress] is ShowProgress's, as on the Show page.
 */
@Immutable
data class ShowRow(
    override val key: TitleKey,
    val show: CollectedShow,
    val details: ShowDetails?,
    val progress: ShowProgress?,
    val state: ShowState,
    val newEpisodes: Int,
    val ended: Boolean,
    /** The next episode's air date, today or later: Next to air sorts by it. */
    val upcoming: LocalDate?,
    /** [upcoming] as the row's date chip shows it ("14 Nov"). */
    val upcomingLabel: String?,
    /** When the latest aired episode aired: New episodes sorts by it. */
    val lastAired: LocalDate?,
    override val lastWatchedAt: Long,
    override val status: String,
    override val cardLine: String,
    /** When [upcoming] airs, as a poster's line says it under Next to air ("Airs 14 Nov"). */
    val airsLine: String?,
    /** Watched of aired, once started. */
    val watchedFraction: Float?,
    override val load: LoadState,
    override val text: TitleText,
    override val a11y: String,
) : TitleRow {
    override val addedAt: Long get() = show.addedAt
    override val posterPath: String? get() = show.posterPath
}

/** A collected movie, what TMDB said of it and how far it was watched. */
@Immutable
data class MovieRow(
    override val key: TitleKey,
    val movie: CollectedMovie,
    /** IMDb's id from TMDB's facts, else as collected (one added from a suggestion has none of its own): what a search looks for. */
    val imdbId: String?,
    val runtimeMinutes: Int?,
    val releaseDate: LocalDate?,
    val state: MovieState,
    override val lastWatchedAt: Long,
    /** "2019 · 1 h 58 min". */
    val meta: String,
    override val status: String,
    override val cardLine: String,
    /** How far in, while in progress. */
    val progressFraction: Float?,
    override val load: LoadState,
    override val text: TitleText,
    override val a11y: String,
    /** TMDB's facts' (ISO 639-1): the language a search ranks its untagged releases in. */
    val originalLanguage: String? = null,
) : TitleRow {
    override val addedAt: Long get() = movie.addedAt
    override val posterPath: String? get() = movie.posterPath
}

/**
 * The rows last built, by key with the inputs they were built from: equal inputs give the same row
 * instance, so with a thousand rows and one show played only that show's row is built (its
 * aggregate parsed) again, and Compose skips the others. One builder at a time.
 */
class RowCache<R : Any> {
    private class Entry<R>(val inputs: Any, val row: R)

    private var entries = HashMap<TitleKey, Entry<R>>()

    /** Rows built rather than taken from the cache, since it was made. */
    @Volatile
    var built = 0
        private set

    val size: Int get() = entries.size

    /** The rows for [items] (key and inputs), in their order; keys not among them are forgotten. */
    @Synchronized
    internal fun <I : Any> rows(items: List<Pair<TitleKey, I>>, build: (inputs: I, previous: R?) -> R): List<R> {
        val next = HashMap<TitleKey, Entry<R>>(items.size * 2)
        val rows = items.map { (key, inputs) ->
            val old = entries[key]
            val row = if (old != null && old.inputs == inputs) old.row else build(inputs, old?.row).also { built++ }
            next[key] = Entry(inputs, row)
            row
        }
        entries = next
        return rows
    }
}

object RowInputs {
    /** The library's failures by the titles they are of. */
    fun failures(failures: List<RefreshFailure>): Map<TitleKey, String> =
        failures.associate { (if (it.type == SummaryRefresh.SHOW) TitleKey.show(it.tmdbId) else TitleKey.movie(it.tmdbId)) to it.error }

    internal fun load(hasFacts: Boolean, failure: String?, hasKey: Boolean): LoadState = when {
        hasFacts -> LoadState.Ready
        !hasKey -> LoadState.NoKey
        failure == NOT_FOUND -> LoadState.NotFound
        failure != null -> LoadState.Failed
        else -> LoadState.Pending
    }

    /** What a row says while TMDB hasn't told anything of it; [noKey] without a TMDB key. */
    internal fun loadLine(load: LoadState, noKey: String): String = when (load) {
        LoadState.Pending, LoadState.Ready -> "Checking TMDB…"
        LoadState.Failed -> "Couldn't load from TMDB"
        LoadState.NotFound -> "Not on TMDB"
        LoadState.NoKey -> noKey
    }

    /** [loadLine] on a poster's one line. */
    internal fun cardLoadLine(load: LoadState, noKey: String): String = when (load) {
        LoadState.Pending, LoadState.Ready -> "Checking…"
        LoadState.Failed -> "Couldn't load"
        LoadState.NotFound -> "Not on TMDB"
        LoadState.NoKey -> noKey
    }

    private const val NOT_FOUND = "NotFound"
}

object ShowRows {
    private data class Inputs(
        val show: CollectedShow,
        val summary: ShowSummary?,
        val watch: ShowWatchAggregate?,
        val failure: String?,
        val today: LocalDate,
        val hasKey: Boolean,
    )

    /** The collection's shows as rows, in [shows]' order; [cache] keeps what didn't change. */
    fun build(
        shows: List<CollectedShow>,
        summaries: Map<Int, ShowSummary>,
        watches: Map<Int, ShowWatchAggregate>,
        failures: Map<TitleKey, String>,
        today: LocalDate,
        hasKey: Boolean,
        cache: RowCache<ShowRow> = RowCache(),
    ): List<ShowRow> = cache.rows(
        shows.map { show ->
            val key = TitleKey.show(show.tmdbId)
            key to Inputs(show, summaries[show.tmdbId], watches[show.tmdbId], failures[key], today, hasKey)
        },
    ) { inputs, previous -> row(inputs, previous) }

    private fun row(i: Inputs, previous: ShowRow?): ShowRow {
        val watches = i.watch?.let(WatchAggregates::parse).orEmpty()
        val details = i.summary?.toDetails(i.show)
        val progress = details?.let { ShowProgress.of(it, watches, i.today, it.recentlyAired()) }
        val load = RowInputs.load(details != null, i.failure, i.hasKey)
        val ended = Freshness.ended(i.summary?.status)
        val state = when {
            progress == null -> if (watches.isEmpty()) ShowState.NotStarted else ShowState.Unknown
            !progress.started -> ShowState.NotStarted
            progress.unwatched > 0 || progress.nextUp is NextUp.Resume -> ShowState.Watching
            ended -> ShowState.Finished
            else -> ShowState.UpToDate
        }
        val upcoming = details?.nextEpisode?.airDate?.takeIf { !it.isBefore(i.today) }
        val lines = lines(progress, state, load, i.today)
        val new = progress?.newEpisodes ?: 0
        val text = previous?.text?.takeIf { it.isFor(i.show.name, i.summary?.originalName, i.show.year) }
            ?: TitleText(i.show.name, i.summary?.originalName, i.show.year)
        return ShowRow(
            key = TitleKey.show(i.show.tmdbId),
            show = i.show,
            details = details,
            progress = progress,
            state = state,
            newEpisodes = new,
            ended = ended,
            upcoming = upcoming,
            upcomingLabel = upcoming?.let { shortDate(it, i.today) },
            lastAired = listOfNotNull(details?.nextEpisode?.airDate?.takeIf { !it.isAfter(i.today) }, details?.lastEpisode?.airDate).maxOrNull(),
            lastWatchedAt = i.watch?.lastPlayedAt ?: 0,
            status = lines.status,
            cardLine = lines.card,
            airsLine = upcoming?.let { "Airs ${cardDate(it, i.today)}" },
            watchedFraction = progress?.takeIf { it.started && it.aired > 0 }?.let { it.watched.toFloat() / it.aired },
            load = load,
            text = text,
            a11y = "${i.show.name}. ${lines.spoken}" + if (new > 0) " New episode." else "",
        )
    }

    /** The row's line, how TalkBack says it, and a poster's short one. */
    private class Lines(val status: String, val spoken: String, val card: String)

    private fun lines(p: ShowProgress?, state: ShowState, load: LoadState, today: LocalDate): Lines {
        if (p == null) return RowInputs.loadLine(load, noKey = "No episode info yet").let { Lines(it, it, RowInputs.cardLoadLine(load, noKey = "No episodes yet")) }
        if (state == ShowState.Finished) return Lines("Finished · all ${p.aired} watched", "Finished. All ${p.aired} watched.", "Finished")
        return when (val next = p.nextUp) {
            is NextUp.Resume -> {
                val more = p.unwatched > 1
                Lines(
                    "Resume ${next.episode.label}" + if (more) " · ${p.unwatched} unwatched" else "",
                    "Resume ${next.episode.spoken()}." + if (more) " ${p.unwatched} unwatched." else "",
                    "Resume ${next.episode.label}",
                )
            }
            is NextUp.Play ->
                if (next.firstEpisode) Lines("Next: ${next.episode.label} · not started", "Next: ${next.episode.spoken()}. Not started.", next.episode.label)
                else Lines("Next: ${next.episode.label} · ${p.unwatched} unwatched", "Next: ${next.episode.spoken()}. ${p.unwatched} unwatched.", next.episode.label)
            is NextUp.UpToDate -> {
                val returns = next.returns
                val ep = next.returningEpisode
                when {
                    returns == null -> Lines("Up to date", "Up to date.", "Up to date")
                    // A season premiere; a first episode still to air "airs" (nothing returns).
                    ep != null && ep.episode == 1 && ep.season > 1 -> shortDate(returns, today).let { date ->
                        Lines("Season ${ep.season} returns $date", "Season ${ep.season} returns $date.", "Back ${cardDate(returns, today)}")
                    }
                    else -> shortDate(returns, today).let { date ->
                        Lines(
                            "${ep?.label ?: "Next episode"} airs $date",
                            "${ep?.spoken()?.replaceFirstChar { it.uppercase() } ?: "Next episode"} airs $date.",
                            "Airs ${cardDate(returns, today)}",
                        )
                    }
                }
            }
        }
    }

    private fun EpisodeRef.spoken() = "season $season, episode $episode"
}

object MovieRows {
    private data class Inputs(
        val movie: CollectedMovie,
        val summary: MovieSummary?,
        val watch: MovieWatch?,
        val failure: String?,
        val today: LocalDate,
        val hasKey: Boolean,
    )

    private const val RELEASED = "Released"

    /** The collection's movies as rows, in [movies]' order; [cache] keeps what didn't change. */
    fun build(
        movies: List<CollectedMovie>,
        summaries: Map<Int, MovieSummary>,
        watches: Map<Int, MovieWatch>,
        failures: Map<TitleKey, String>,
        today: LocalDate,
        hasKey: Boolean,
        cache: RowCache<MovieRow> = RowCache(),
    ): List<MovieRow> = cache.rows(
        movies.map { movie ->
            val key = TitleKey.movie(movie.tmdbId)
            key to Inputs(movie, summaries[movie.tmdbId], watches[movie.tmdbId], failures[key], today, hasKey)
        },
    ) { inputs, previous -> row(inputs, previous) }

    private fun row(i: Inputs, previous: MovieRow?): MovieRow {
        val summary = i.summary
        val watch = i.watch
        val release = summary?.releaseDay?.let(LocalDate::ofEpochDay)
        val inProgress = watch != null && !watch.watched && watch.positionMs >= LibraryRepository.MIN_RESUME_MS
        val state = when {
            inProgress -> MovieState.InProgress
            watch?.watched == true -> MovieState.Watched
            summary == null -> MovieState.Unknown
            // Without a date, upcoming unless TMDB lists it as out.
            release?.isAfter(i.today) ?: (summary.status != RELEASED) -> MovieState.Upcoming
            else -> MovieState.NotWatched
        }
        val load = RowInputs.load(summary != null, i.failure, i.hasKey)
        val left = watch?.takeIf { inProgress && it.durationMs > 0 }?.let { ((it.durationMs - it.positionMs).coerceAtLeast(0) + 59_999) / 60_000 }?.toInt()
        val (status, spoken) = when (state) {
            MovieState.InProgress -> left?.let { "${duration(it)} left" to "${spokenDuration(it)} left" } ?: ("In progress" to "In progress")
            MovieState.Watched -> "✓ Watched" to "Watched"
            MovieState.Upcoming -> (release?.let { "Out ${shortDate(it, i.today)}" } ?: "Release date not announced").let { it to it }
            MovieState.NotWatched -> "Not watched" to "Not watched"
            MovieState.Unknown -> RowInputs.loadLine(load, noKey = "Not watched").let { it to it }
        }
        val runtime = summary?.runtimeMinutes
        // A movie not watched says what it is, as the meta line does, a minute's "min" left off where hours say it is a time.
        val cardMeta = listOfNotNull(i.movie.year?.toString(), runtime?.let(::shortDuration)).joinToString(" · ").ifEmpty { "Not watched" }
        val card = when (state) {
            MovieState.InProgress -> left?.let { "${shortDuration(it)} left" } ?: "In progress"
            MovieState.Watched -> "✓ Watched"
            MovieState.Upcoming -> release?.let { "Out ${cardDate(it, i.today)}" } ?: "Date not set"
            MovieState.NotWatched -> cardMeta
            MovieState.Unknown -> RowInputs.cardLoadLine(load, noKey = cardMeta)
        }
        val text = previous?.text?.takeIf { it.isFor(i.movie.title, summary?.originalTitle, i.movie.year) }
            ?: TitleText(i.movie.title, summary?.originalTitle, i.movie.year)
        return MovieRow(
            key = TitleKey.movie(i.movie.tmdbId),
            movie = i.movie,
            imdbId = summary?.imdbId ?: i.movie.imdbId,
            runtimeMinutes = runtime,
            releaseDate = release,
            state = state,
            lastWatchedAt = watch?.lastPlayedAt ?: 0,
            meta = listOfNotNull(i.movie.year?.toString(), runtime?.let(::duration)).joinToString(" · "),
            status = status,
            cardLine = card,
            progressFraction = watch?.takeIf { inProgress && it.durationMs > 0 }?.let { it.positionMs.toFloat() / it.durationMs },
            load = load,
            text = text,
            a11y = listOfNotNull(i.movie.title, i.movie.year?.toString(), runtime?.let(::spokenDuration), spoken).joinToString(", "),
            originalLanguage = summary?.originalLanguage,
        )
    }

    /** "1 h 58 min", "2 h", "45 min". */
    private fun duration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return listOfNotNull(h.takeIf { it > 0 }?.let { "$it h" }, m.takeIf { it > 0 || h == 0 }?.let { "$it min" }).joinToString(" ")
    }

    /** "1 h 58", "2 h", "45 min": [duration] on a poster's one line. */
    private fun shortDuration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m min"
            m == 0 -> "$h h"
            else -> "$h h $m"
        }
    }

    /** "1 hour 58 minutes": "min" could be read as "minimum". */
    private fun spokenDuration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { if (it == 1) "1 hour" else "$it hours" },
            m.takeIf { it > 0 || h == 0 }?.let { if (it == 1) "1 minute" else "$it minutes" },
        ).joinToString(" ")
    }
}
