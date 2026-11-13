package io.skjaere.usenet.app.collection

import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.metadata.EpisodeRef
import io.skjaere.usenet.metadata.EpisodeWatch
import io.skjaere.usenet.metadata.NextUp
import io.skjaere.usenet.metadata.ShowDetails
import java.time.LocalDate

/** "14 Nov" this year, "14 Nov 2027" another. */
fun shortDate(date: LocalDate): String = io.skjaere.usenet.app.collection.model.shortDate(date, LocalDate.now())

/** Watch states → the shape ShowProgress needs. */
fun List<WatchState>.toEpisodeWatches(): List<EpisodeWatch> =
    mapNotNull { st -> if (st.season != null && st.episode != null) EpisodeWatch(st.season!!, st.episode!!, st.watched, st.positionMs, st.lastPlayedAt) else null }

/** The episodes with air dates the show's details carry (its last), for ShowProgress's New. */
fun ShowDetails.recentlyAired(): Map<EpisodeRef, LocalDate> =
    listOfNotNull(lastEpisode).mapNotNull { e -> e.airDate?.let { EpisodeRef(e.season, e.episode) to it } }.toMap()

/** A position or what is left of a play: "18:22", "1:16:05". */
internal fun mmss(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** The episode Resume or Play is for; null when up to date. */
internal fun NextUp.episodeRef(): EpisodeRef? = when (this) {
    is NextUp.Resume -> episode
    is NextUp.Play -> episode
    is NextUp.UpToDate -> null
}
