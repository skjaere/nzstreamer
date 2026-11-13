package io.skjaere.usenet.app.home

import io.skjaere.usenet.app.collection.model.focusAfterRemoval
import io.skjaere.usenet.data.LibraryRepository
import io.skjaere.usenet.data.MovieMark
import io.skjaere.usenet.data.ShowMark
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.metadata.EpisodeRef

/**
 * What an Up next row's menu does to the library, worked out from the row: Remove from Up next on every row, Mark
 * watched on an episode (a special too) or a film in progress. Each says what the Undo bar says once it is done.
 */
sealed interface UpNextEdit {
    /** The row's [UpNextRow.key]. */
    val key: String
    val message: String

    /**
     * Where a remote's focus goes as the Undo bar goes, of [keys] (the rows in order, before the edit): the row after,
     * else the one before ([focusAfterRemoval]); the row itself for a show marked, which stays with its next episode.
     * Null when no row is left: Home's first focus without Up next.
     */
    fun focusAfter(keys: List<String>): String? = if (this is MarkEpisode) key else focusAfterRemoval(keys, setOf(key))

    /**
     * Takes the row out of Up next ([LibraryRepository.removeFromUpNext]) until a play: a show's row (every row of it) of
     * any of [showTmdbId]'s episodes, else of what played, [watchKey].
     */
    data class Remove(override val key: String, val showTmdbId: Int?, val watchKey: String?, override val message: String) : UpNextEdit

    /** Mark watched, with Undo ([LibraryRepository.undoMarks]). */
    sealed interface Mark : UpNextEdit

    /** Marks the episode in progress watched: the show moves on to its next one. */
    data class MarkEpisode(override val key: String, val mark: ShowMark, override val message: String) : Mark

    /** Marks the film in progress watched: it leaves Up next. */
    data class MarkMovie(override val key: String, val mark: MovieMark, override val message: String) : Mark

    companion object {
        fun remove(row: UpNextRow): Remove {
            val message = "Removed “${row.title}” from Up next"
            return when (val play = row.play) {
                is UpNextPlay.NextEpisode -> Remove(row.key, play.show.tmdbId, null, message)
                is UpNextPlay.Resume -> play.state.showTmdbId.takeIf { play.state.kind == WatchKind.Episode }
                    ?.let { Remove(row.key, it, null, message) } ?: Remove(row.key, null, play.state.key, message)
            }
        }

        /**
         * Where a remote's focus goes as the Undo bar goes, of the rows shown now ([keys]): [focusAfter]'s row while it is
         * shown; else the row shown nearest after the one edited ([edited]) among the rows then ([before]), else nearest
         * before it. Rows can go meanwhile: a show marked through its last aired episode, a neighbour played to its end.
         * Null when none is shown: Home's first focus without Up next.
         */
        fun handBack(focusAfter: String?, edited: String?, before: List<String>, keys: List<String>): String? {
            if (focusAfter != null && focusAfter in keys) return focusAfter
            val at = before.indexOf(edited)
            if (at < 0) return null
            return before.drop(at + 1).firstOrNull { it in keys } ?: before.take(at).lastOrNull { it in keys }
        }

        /** Null for a next episode, not begun, and for a release played without TMDB, which nothing marks: they offer Remove only. */
        fun markWatched(row: UpNextRow): Mark? {
            val state = (row.play as? UpNextPlay.Resume)?.state ?: return null
            return when (state.kind) {
                WatchKind.Episode -> {
                    val show = state.showTmdbId ?: return null
                    val ref = EpisodeRef(state.season ?: return null, state.episode ?: return null)
                    MarkEpisode(row.key, ShowMark(show, row.title, listOf(ref.season to ref.episode)), "Marked “${row.title} ${ref.label}” watched")
                }
                WatchKind.Movie -> state.movieTmdbId?.let { MarkMovie(row.key, MovieMark(it, state.title), "Marked “${row.title}” watched") }
                WatchKind.Release -> null
            }
        }
    }
}
