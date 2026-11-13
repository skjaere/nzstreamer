package io.skjaere.usenet.app.casting

import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.home.UpNextPlay
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.data.CollectedShow
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.metadata.EpisodeRef
import io.skjaere.usenet.metadata.NextUp
import io.skjaere.usenet.metadata.ShowProgress
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/** What comes after an episode watched on a Cast device, as the collection says it (L125). */
sealed interface NextAfter {
    /** The collection hasn't counted the watch yet: it still has that episode to play or resume. */
    data object Waiting : NextAfter

    /** Up next's next episode of the show. */
    data class Next(val play: UpNextPlay.NextEpisode) : NextAfter
}

/**
 * What comes after [watched] in [show]'s row of the collection ([progress]: its watches as the collection counts them,
 * null without TMDB's facts), as Home's Up next says it (L125): its next episode to play, the next season's first after a
 * season's last, once the watch is counted; [NextAfter.Waiting] while the row still has [watched] to play or resume; null
 * for nothing to offer: up to date, another episode in progress (Up next resumes it), or no facts.
 */
fun nextEpisodeAfter(show: CollectedShow, progress: ShowProgress?, watched: EpisodeRef): NextAfter? = when (val up = progress?.nextUp) {
    is NextUp.Play -> if (up.episode > watched) NextAfter.Next(UpNextPlay.NextEpisode(show, up.episode)) else NextAfter.Waiting
    is NextUp.Resume -> NextAfter.Waiting.takeIf { up.episode == watched }
    is NextUp.UpToDate, null -> null
}

/**
 * Up next's next episode of the show [launch] played (L125), from the collection's row once it counts the episode's watch,
 * which the play's end saves as the line is said: waited for up to [NEXT_WAIT_MS], then none. Null for a film, a release
 * played without TMDB, a show not collected or not started, or without a TMDB key, as Home's Up next has no next episode
 * for them either.
 */
internal suspend fun upNextAfter(c: AppContainer, launch: PlayerLaunch): UpNextPlay.NextEpisode? {
    val watch = launch.watch
    val show = watch.showTmdbId?.takeIf { watch.kind == WatchKind.Episode } ?: return null
    val watched = EpisodeRef(watch.season ?: return null, watch.episode ?: return null)
    if (!c.settings.hasTmdbKey.first()) return null
    val next = withTimeoutOrNull(NEXT_WAIT_MS) {
        c.collection.shows.filterNotNull()
            .map { rows -> rows.firstOrNull { it.show.tmdbId == show }?.takeIf { it.progress?.started == true }?.let { nextEpisodeAfter(it.show, it.progress, watched) } }
            .first { it != NextAfter.Waiting }
    }
    return (next as? NextAfter.Next)?.play
}

/** How long the Watched line waits for the collection to count the episode's watch before it shows without a button. */
private const val NEXT_WAIT_MS = 3_000L

/** The Watched line's button (L125; CsPhoneWatchedNext): "Play S02E05 on Kitchen display". */
fun playNextOn(episode: EpisodeRef, name: String) = "Play ${episode.label} on $name"
