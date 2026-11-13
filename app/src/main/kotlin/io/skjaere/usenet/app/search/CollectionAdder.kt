package io.skjaere.usenet.app.search

import io.skjaere.usenet.app.CollectionJump
import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.app.saveOrLog
import io.skjaere.usenet.data.CollectedMovie
import io.skjaere.usenet.data.CollectedShow
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.metadata.TitleSuggestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * "+ Collection" on Search: the title written to the library from what Search has of it (a
 * suggestion's own data: no TMDB lookup), in [scope], the app's, so leaving Search can't stop it
 * halfway. Then its facts are asked of TMDB ([refresh]), not waited for. Each add's Deferred completes
 * with where the collection jumps once the title is stored, and fails if the write does; [adding]
 * holds the titles being written.
 */
class CollectionAdder(
    private val scope: CoroutineScope,
    private val addShow: suspend (CollectedShow) -> Unit,
    private val addMovie: suspend (CollectedMovie) -> Unit,
    private val refresh: suspend (List<TitleKey>) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _adding = MutableStateFlow<Set<TitleKey>>(emptySet())
    val adding: StateFlow<Set<TitleKey>> = _adding.asStateFlow()

    /** The title card's title, with the ids its lookup gave. */
    fun add(title: TitleInfo): Deferred<CollectionJump> = when (title.type) {
        MediaType.Tv -> write(TitleKey.show(title.tmdbId), title.name) {
            val at = now()
            addShow(CollectedShow(CollectedShow.idFor(title.tmdbId), title.tmdbId, title.ids.tvdb, title.ids.imdb, title.ids.tvmaze, title.name, title.year, title.posterPath, at, at))
        }
        MediaType.Movie -> write(TitleKey.movie(title.tmdbId), title.name) {
            val at = now()
            addMovie(CollectedMovie(CollectedMovie.idFor(title.tmdbId), title.tmdbId, title.ids.imdb, title.name, title.year, title.posterPath, at, at))
        }
    }

    /** A suggestion as it came: its other ids are TMDB's to fill in, as the refresher asks after it. */
    fun add(suggestion: TitleSuggestion): Deferred<CollectionJump> = with(suggestion) {
        when (type) {
            MediaType.Tv -> write(TitleKey.show(tmdbId), name) {
                val at = now()
                addShow(CollectedShow(CollectedShow.idFor(tmdbId), tmdbId, name = name, year = year, posterPath = posterPath, addedAt = at, updatedAt = at))
            }
            MediaType.Movie -> write(TitleKey.movie(tmdbId), name) {
                val at = now()
                addMovie(CollectedMovie(CollectedMovie.idFor(tmdbId), tmdbId, title = name, year = year, posterPath = posterPath, addedAt = at, updatedAt = at))
            }
        }
    }

    private fun write(key: TitleKey, name: String, write: suspend () -> Unit): Deferred<CollectionJump> {
        _adding.update { it + key }
        return scope.async {
            try {
                write()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Whoever waits hears of it too; no one may, once Search is gone.
                log.warn("Couldn't add {} to the collection", name, e)
                throw e
            } finally {
                _adding.update { it - key }
            }
            scope.launch { saveOrLog("look up $name on TMDB") { refresh(listOf(key)) } }
            CollectionJump(key.value, name)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(CollectionAdder::class.java)
    }
}
