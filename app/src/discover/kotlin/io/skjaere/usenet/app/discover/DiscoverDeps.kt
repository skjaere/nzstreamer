package io.skjaere.usenet.app.discover

import androidx.annotation.VisibleForTesting
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.DeviceClock
import io.skjaere.usenet.discover.DiscoverSource
import io.skjaere.usenet.discover.TmdbDiscoverSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.util.Locale

/**
 * What Discover's view models read: the app's ([of]), or a test's fakes, so they run in a JVM test. The lists come from
 * [source] (TMDB with the user's key); [knownKey] is whether a key was stored as the app last read it (its splash waits
 * for that), so the tab's first frame needn't wait for the settings, null when they weren't read yet.
 */
class DiscoverDeps(
    val hasTmdbKey: Flow<Boolean>,
    val showArtwork: Flow<Boolean>,
    /** Settings › Metadata's TMDB language (null: the device's): the lists are asked for again in a new one. */
    val language: Flow<String?>,
    val collectedShows: Flow<Set<Int>>,
    val collectedMovies: Flow<Set<Int>>,
    val source: DiscoverSource,
    val knownKey: Boolean? = null,
    /** The original languages are named in the device's language. */
    val locale: () -> Locale = { Locale.getDefault() },
    /** The waits before TMDB is asked again after it said to slow down, one after the other; then Try again is the user's. */
    val backoff: List<Duration> = BACKOFF,
) {
    companion object {
        fun of(c: AppContainer) = DiscoverDeps(
            hasTmdbKey = c.settings.hasTmdbKey, showArtwork = c.settings.metadata.map { it.showArtwork },
            language = c.settings.metadata.map { it.language },
            collectedShows = c.library.collectedShowIds, collectedMovies = c.library.collectedMovieIds,
            source = DiscoverLookup.source(c), knownKey = c.setup.value?.hasTmdbKey,
        )

        /** The lists' refresher's: 10 s, growing to 2 min. */
        val BACKOFF: List<Duration> = listOf(10L, 30L, 60L, 120L).map(Duration::ofSeconds)
    }
}

/**
 * Where Discover's screens get their lists: TMDB with the user's key, one source for the process. A test seam: the
 * device tests answer with invented titles (FakeDiscover, every device test's default), so none of them asks TMDB.
 */
object DiscoverLookup {
    @VisibleForTesting
    @Volatile
    internal var source: (AppContainer) -> DiscoverSource = ::tmdb

    private var made: Pair<AppContainer, DiscoverSource>? = null

    /** On the device's clock: its zone is the timezone Airing today is asked in, its days what Now playing is kept by. */
    private fun tmdb(c: AppContainer): DiscoverSource = synchronized(this) {
        made?.takeIf { it.first === c }?.second ?: TmdbDiscoverSource(c.tmdb, DeviceClock).also { made = c to it }
    }
}
