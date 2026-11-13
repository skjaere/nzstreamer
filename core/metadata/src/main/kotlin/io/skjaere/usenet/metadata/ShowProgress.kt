package io.skjaere.usenet.metadata

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/** What the user did with one episode, as far as progress is concerned. */
data class EpisodeWatch(val season: Int, val episode: Int, val watched: Boolean, val positionMs: Long = 0, val lastPlayedAt: Long = 0)

data class EpisodeRef(val season: Int, val episode: Int) : Comparable<EpisodeRef> {
    override fun compareTo(other: EpisodeRef) = compareValuesBy(this, other, { it.season }, { it.episode })
    val label: String get() = "S%02dE%02d".format(season, episode)
}

/**
 * Whether [episode] has aired by [today]: by its air date, or, when TMDB has none, by coming at
 * or before the last aired episode, the way [ShowProgress] counts aired episodes.
 */
fun ShowDetails.hasAired(episode: Episode, today: LocalDate): Boolean =
    episode.hasAired(today) || episode.airDate == null && episode.season >= 1 &&
        lastAired(today)?.let { EpisodeRef(episode.season, episode.episode) <= it } == true

private fun EpisodeSummary.ref() = EpisodeRef(season, episode)

/** [ShowDetails.nextEpisode] once it has aired: the details may be cached from before it did. */
private fun ShowDetails.nextAired(today: LocalDate): EpisodeSummary? = nextEpisode?.takeIf { next ->
    next.airDate?.isAfter(today) == false && lastEpisode.let { it == null || next.ref() > it.ref() }
}

private fun ShowDetails.lastAired(today: LocalDate): EpisodeRef? = (nextAired(today) ?: lastEpisode)?.ref()

/**
 * Every regular episode aired by [today], by the seasons' episode counts up to the last aired one:
 * exactly what [ShowProgress] counts as aired, so marking them all watched leaves none unwatched.
 */
fun ShowDetails.airedEpisodes(today: LocalDate): List<EpisodeRef> {
    val last = lastAired(today) ?: return emptyList()
    return seasons.filter { !it.isSpecials && it.episodeCount > 0 && it.number <= last.season }.sortedBy { it.number }
        .flatMap { s -> (1..if (s.number < last.season) s.episodeCount else minOf(s.episodeCount, last.episode)).map { EpisodeRef(s.number, it) } }
}

sealed interface NextUp {
    /** Started and not finished. */
    data class Resume(val episode: EpisodeRef, val positionMs: Long) : NextUp
    data class Play(val episode: EpisodeRef, val firstEpisode: Boolean) : NextUp
    /** Everything aired is watched; [returns] is the next air date if TMDB knows it. */
    data class UpToDate(val returns: LocalDate?, val returningEpisode: EpisodeRef?) : NextUp
}

data class ShowProgress(
    val aired: Int,
    val watched: Int,
    val nextUp: NextUp,
    /** Unwatched episodes that aired within the last week. */
    val newEpisodes: Int,
) {
    val unwatched: Int get() = (aired - watched).coerceAtLeast(0)
    val started: Boolean get() = watched > 0 || nextUp is NextUp.Resume

    companion object {
        /** Episodes started less than this far in don't count as in progress. */
        private const val MIN_RESUME_MS = 60_000L

        /**
         * [show] gives seasons and the last aired episode (or its next episode, once that airs);
         * [recentlyAired] are episodes with air dates TMDB returned for the seasons fetched so far
         * (used for the NEW badge only).
         */
        fun of(show: ShowDetails, watches: Collection<EpisodeWatch>, today: LocalDate, recentlyAired: Map<EpisodeRef, LocalDate> = emptyMap()): ShowProgress {
            val seasons = show.seasons.filter { !it.isSpecials && it.episodeCount > 0 }.sortedBy { it.number }
            // [show] may be cached from before its next episode aired; from its air date on, it counts as aired.
            val nextAired = show.nextAired(today)
            val last = show.lastAired(today)
            fun hasAired(ref: EpisodeRef) = last != null && ref <= last
            val airedCount = seasons.sumOf { s ->
                when {
                    last == null -> 0
                    s.number < last.season -> s.episodeCount
                    s.number == last.season -> minOf(s.episodeCount, last.episode)
                    else -> 0
                }
            }
            val regular = watches.filter { it.season >= 1 }
            val watchedRefs = regular.filter { it.watched }.map { EpisodeRef(it.season, it.episode) }.toSet()
            val watchedAired = watchedRefs.count(::hasAired)
            // The next episode still to air, unless watched already: releases often appear before TMDB's date.
            val upcoming = show.nextEpisode?.takeIf { nextAired == null && it.ref() !in watchedRefs }

            fun after(ref: EpisodeRef): EpisodeRef? {
                val count = seasons.firstOrNull { it.number == ref.season }?.episodeCount ?: return null
                if (ref.episode < count) return EpisodeRef(ref.season, ref.episode + 1)
                return seasons.firstOrNull { it.number > ref.season }?.let { EpisodeRef(it.number, 1) }
            }

            val inProgress = regular.filter { !it.watched && it.positionMs >= MIN_RESUME_MS }.maxByOrNull { it.lastPlayedAt }
            val nextUp = when {
                inProgress != null -> NextUp.Resume(EpisodeRef(inProgress.season, inProgress.episode), inProgress.positionMs)
                else -> {
                    val furthest = watchedRefs.maxOrNull()
                    val candidate = if (furthest == null) seasons.firstOrNull()?.let { EpisodeRef(it.number, 1) } else after(furthest)
                    when {
                        candidate != null && hasAired(candidate) -> NextUp.Play(candidate, firstEpisode = furthest == null)
                        else -> NextUp.UpToDate(upcoming?.airDate, upcoming?.ref() ?: candidate)
                    }
                }
            }
            val weekAgo = today.minusDays(7)
            val recent = recentlyAired + listOfNotNull(nextAired).mapNotNull { e -> e.airDate?.let { e.ref() to it } }
            val newEpisodes = recent.count { (ref, date) -> !date.isBefore(weekAgo) && !date.isAfter(today) && ref !in watchedRefs && hasAired(ref) }
            return ShowProgress(airedCount, watchedAired, nextUp, newEpisodes)
        }

        /** The season a show page opens on: the one holding the next episode to watch. */
        fun openingSeason(show: ShowDetails, watches: Collection<EpisodeWatch>, today: LocalDate): Int {
            val start = when (val next = of(show, watches, today).nextUp) {
                is NextUp.Resume -> next.episode.season
                is NextUp.Play -> next.episode.season
                is NextUp.UpToDate -> next.returningEpisode?.season ?: show.lastEpisode?.season
            }
            return start ?: show.seasons.firstOrNull { !it.isSpecials }?.number ?: 1
        }

        /**
         * [openingSeason] from the library's first answer: the show's details often arrive first
         * (from TMDB's file cache), and without its watches every show would open on season 1.
         */
        suspend fun openingSeason(show: ShowDetails, watches: Flow<Collection<EpisodeWatch>>, today: LocalDate): Int =
            openingSeason(show, watches.first(), today)
    }
}
