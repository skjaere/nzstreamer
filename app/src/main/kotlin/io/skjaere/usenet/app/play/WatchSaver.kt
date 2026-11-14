package io.skjaere.usenet.app.play

import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.saveOrLog
import io.skjaere.usenet.data.LibraryRepository
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchState
import kotlinx.coroutines.launch

/**
 * A play's progress as the library records it ([io.skjaere.usenet.data.LibraryRepository.recordProgress]): the watch's
 * [template], where it was ([positionMs]) of how long ([durationMs]), the release that played ([release]: none where it
 * failed there, so Resume no longer plays it) and the keys it took over ([replaces]).
 */
data class WatchSave(val template: WatchState, val positionMs: Long, val durationMs: Long, val release: RememberedRelease?, val replaces: List<String>)

/**
 * How a play's progress is saved (L61, L90): the player's, here or a Cast device's, by its launch. The library's 90 % rule
 * then decides whether it was watched. A play starts from it again where nothing else says where ([resumeAt]).
 */
object WatchSaver {
    /**
     * Where a play of the watch saved as [state] starts, here or cast: where it was left, unless watched or under a minute
     * in. A tombstone is no place: its row was removed (an undo of what made it, or another release taking its progress
     * over), so the play starts from the beginning, as without a row.
     */
    fun resumeAt(state: WatchState?): Long =
        state?.takeIf { !it.deleted && !it.watched }?.positionMs?.takeIf { it >= LibraryRepository.MIN_RESUME_MS } ?: 0

    /**
     * [launch] at [positionMs] of [durationMs], its release left out where it failed there ([releaseFailed]). Null without
     * a length (none known, or Media3's unset time), and for a file Import opened, which saves nothing (L147).
     */
    fun of(launch: PlayerLaunch, positionMs: Long, durationMs: Long, releaseFailed: Boolean): WatchSave? {
        if (durationMs <= 0 || launch.imported) return null
        val w = launch.watch
        val template = WatchState(
            key = launch.watchKey, kind = w.kind, showTmdbId = w.showTmdbId, season = w.season, episode = w.episode,
            movieTmdbId = w.movieTmdbId, title = w.title, updatedAt = 0,
        )
        return WatchSave(template, positionMs, durationMs, launch.release.toRemembered().takeUnless { releaseFailed }, launch.replaces)
    }

    /** Records [save] in the library, in the app's scope: it outlives the screen, and a failure is logged, never thrown. */
    fun save(container: AppContainer, save: WatchSave) {
        container.appScope.launch {
            saveOrLog("save progress for ${save.template.key}") {
                container.library.recordProgress(save.template, save.positionMs, save.durationMs, save.release, replaces = save.replaces)
            }
        }
    }
}
