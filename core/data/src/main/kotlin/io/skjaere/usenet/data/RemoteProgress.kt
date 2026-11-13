package io.skjaere.usenet.data

import io.skjaere.usenet.link.FinalKind
import io.skjaere.usenet.link.LinkMessage.Final

/**
 * W5, L11, L33: where a play this phone started on a TV stopped, saved as if it had played here, so Resume, the episode's
 * row, Up next and the collection agree. Pure: [LibraryRepository.recordRemoteProgress] reads and writes the row around
 * [decide] in one transaction.
 *
 * A Final counts only for one of this phone's own sessions on the TV it came from, and for the show or the movie that
 * session was of (so the next episode auto-advance played counts); its watch key is worked out again from its ids
 * ([key]), never taken as sent. It writes only over a row that isn't newer than the Final, by the Final's own time on
 * this phone's clock ([at]: its age back from now, so a TV's wrong clock doesn't matter, L25), as a play here saves:
 * where it stopped, or watched with no place to resume. The phone's remembered release stays; the TV's never comes, its
 * indexer being the TV's.
 */
object RemoteProgress {
    /**
     * The watch key [final] is for, when it is of one of [sessions] (this phone's on the TV it came from) and of the same
     * show or movie, and its watch key is the one its ids make; null for any other, which is dropped (and acked all the
     * same).
     */
    fun key(final: Final, sessions: List<TvSession>): String? {
        val session = sessions.firstOrNull { it.id == final.session } ?: return null
        val key = when (final.kind) {
            FinalKind.Episode -> {
                val show = final.showTmdbId?.takeIf { it == session.showTmdbId } ?: return null
                WatchKey.episode(show, final.season ?: return null, final.episode ?: return null)
            }
            FinalKind.Movie -> WatchKey.movie(final.movieTmdbId?.takeIf { it == session.movieTmdbId } ?: return null)
            FinalKind.Unknown -> return null
        }
        return key.takeIf { it == final.watchKey }
    }

    /** When [final] was made, by this phone's clock at [now]: its age back from [now], never before 0 nor after [now] (L25). */
    fun at(final: Final, now: Long): Long = (now - final.ageMs.coerceAtLeast(0)).coerceIn(0, now)

    /**
     * What [final] writes over [row], this phone's row for its watch key ([key] made it so), a tombstone too, at [now]:
     * null when [row] is newer than the Final. Its time is the Final's own; past [LibraryRepository.WATCHED_FRACTION]
     * (the TV's word, [Final.watched]) it is watched with no place, and a play under [LibraryRepository.MIN_RESUME_MS]
     * into an episode already watched leaves it watched, as a play here does. The phone's own release stays.
     */
    fun decide(row: WatchState?, final: Final, now: Long): WatchState? {
        val at = at(final, now)
        if (row != null && row.updatedAt > at) return null
        val live = row?.takeIf { !it.deleted }
        val template = live ?: WatchState(
            key = final.watchKey,
            kind = when (final.kind) {
                FinalKind.Episode -> WatchKind.Episode
                FinalKind.Movie -> WatchKind.Movie
                FinalKind.Unknown -> return null
            },
            showTmdbId = final.showTmdbId?.takeIf { final.kind == FinalKind.Episode },
            season = final.season?.takeIf { final.kind == FinalKind.Episode },
            episode = final.episode?.takeIf { final.kind == FinalKind.Episode },
            movieTmdbId = final.movieTmdbId?.takeIf { final.kind == FinalKind.Movie },
            title = final.title,
            updatedAt = at,
        )
        return template.copy(
            positionMs = if (final.watched) 0 else final.positionMs,
            durationMs = final.durationMs,
            watched = final.watched || (live?.watched == true && final.positionMs < LibraryRepository.MIN_RESUME_MS),
            lastPlayedAt = at,
            release = live?.release,
            updatedAt = at,
            deleted = false,
        )
    }
}
