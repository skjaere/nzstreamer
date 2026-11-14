package io.skjaere.usenet.app

import io.skjaere.usenet.app.play.nextAiredEpisode
import io.skjaere.usenet.app.play.previousAiredEpisode
import io.skjaere.usenet.metadata.Aged
import io.skjaere.usenet.metadata.KeyCheck
import io.skjaere.usenet.metadata.MovieDetails
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.metadata.TitleSuggestion
import io.skjaere.usenet.metadata.TmdbError
import io.skjaere.usenet.metadata.TmdbException
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * TMDB for the app's device tests: TmdbLookup's seams answer for the given invented titles (the fake
 * catalogue's, which TMDB doesn't know), or fail for [movieErrors]'s, and pass everything else to the
 * real lookups. Screens, Home, the lists' refresher and Search's suggestions all read the seams, so one
 * swap covers them; [close] puts the real ones back. [lookups] counts the answers it gave. Its answers
 * are [age] old, as TmdbClient's cache would give them (from TMDB just now by default). [keys] answers
 * a key's check (Settings › Metadata's, the phone setup's) for invented keys; null asks TMDB.
 *
 * [strict], as the README screenshots have it, passes nothing on: an id, query or key it has no answer for throws
 * [Unknown] and is kept in [misses], and [close] then fails with the list, even where the app caught the throw and
 * showed nothing. The player's own TMDB seams answer from it too: the next and previous episode by its [shows] and
 * [seasons], and TheIntroDB's segments none, as with the lookup off. Give it [inventedKey] and save [INVENTED_KEY] as
 * the app's key, so a lookup that slips past the seams meets a key TMDB refuses.
 */
class FakeTmdb(
    private val shows: Map<Int, ShowDetails> = emptyMap(),
    private val movies: Map<Int, MovieDetails> = emptyMap(),
    private val seasons: (show: Int, number: Int) -> Season? = { _, _ -> null },
    private val suggestions: (query: String) -> List<TitleSuggestion>? = { null },
    private val movieErrors: Map<Int, TmdbError> = emptyMap(),
    private val age: Duration = Duration.ZERO,
    private val keys: (key: String) -> KeyCheck? = { null },
    private val strict: Boolean = false,
) : AutoCloseable {
    private val realShow = TmdbLookup.showAged
    private val realSeason = TmdbLookup.seasonAged
    private val realMovie = TmdbLookup.movieAged
    private val realSearch = TmdbLookup.searchTitles
    private val realValidate = TmdbLookup.validateKey
    private val realNext = PlayerActivity.nextEpisode
    private val realPrevious = PlayerActivity.previousEpisode
    private val realSegments = PlayerActivity.lookUpSegments
    val lookups = AtomicInteger()

    /** What a strict fake was asked for and had no answer to, in order. A key's check is said without the key. */
    val misses = CopyOnWriteArrayList<String>()

    init {
        TmdbLookup.showAged = { c, id -> shows[id]?.let { lookups.incrementAndGet(); Aged(it, age) } ?: orReal("show $id") { realShow(c, id) } }
        TmdbLookup.seasonAged = { c, id, number ->
            seasons(id, number)?.let { lookups.incrementAndGet(); Aged(it, age) } ?: orReal("season $number of show $id") { realSeason(c, id, number) }
        }
        TmdbLookup.movieAged = { c, id ->
            movieErrors[id]?.let { throw TmdbException(it) }
            movies[id]?.let { lookups.incrementAndGet(); Aged(it, age) } ?: orReal("movie $id") { realMovie(c, id) }
        }
        TmdbLookup.searchTitles = { c, query -> suggestions(query)?.also { lookups.incrementAndGet() } ?: orReal("search \"$query\"") { realSearch(c, query) } }
        TmdbLookup.validateKey = { c, key -> keys(key)?.also { lookups.incrementAndGet() } ?: orReal("a key's check") { realValidate(c, key) } }
        if (strict) {
            PlayerActivity.nextEpisode = { c, e ->
                nextAiredEpisode(e.showTmdbId, e.season, e.episode, LocalDate.now(), loadShow = { TmdbLookup.show(c, it) }, loadSeason = { id, n -> TmdbLookup.season(c, id, n) })
            }
            PlayerActivity.previousEpisode = { c, e ->
                previousAiredEpisode(e.showTmdbId, e.season, e.episode, LocalDate.now(), loadShow = { TmdbLookup.show(c, it) }, loadSeason = { id, n -> TmdbLookup.season(c, id, n) })
            }
            PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        }
    }

    private inline fun <T> orReal(what: String, real: () -> T): T {
        if (!strict) return real()
        misses += what
        throw Unknown("a strict FakeTmdb has no answer for $what")
    }

    /** Puts the real lookups back; then, for a strict fake that was asked for what it didn't know, fails with the list. */
    override fun close() {
        TmdbLookup.showAged = realShow
        TmdbLookup.seasonAged = realSeason
        TmdbLookup.movieAged = realMovie
        TmdbLookup.searchTitles = realSearch
        TmdbLookup.validateKey = realValidate
        if (strict) {
            PlayerActivity.nextEpisode = realNext
            PlayerActivity.previousEpisode = realPrevious
            PlayerActivity.lookUpSegments = realSegments
        }
        if (misses.isNotEmpty()) throw AssertionError("a strict FakeTmdb was asked for what it doesn't know: ${misses.joinToString()}")
    }

    /** A strict fake's answer to what it doesn't know. */
    class Unknown(message: String) : IllegalStateException(message)

    companion object {
        /** An invented key, not one TMDB gives out: it isn't 32 hex digits. */
        const val INVENTED_KEY = "0000invented0000screens0key00000"

        /** Takes [INVENTED_KEY], and has no answer for any other key. */
        val inventedKey: (String) -> KeyCheck? = { if (it == INVENTED_KEY) KeyCheck.Valid else null }
    }
}
