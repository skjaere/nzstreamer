package io.skjaere.usenet.app.collection

import io.skjaere.usenet.metadata.Aged
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeRef
import io.skjaere.usenet.metadata.Freshness
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.TmdbException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** What the show screen shows under the season chips. */
sealed interface SeasonState {
    data object Loading : SeasonState
    data class Loaded(val season: Season) : SeasonState
    data class Failed(val message: String) : SeasonState
}

/**
 * The show screen's seasons, each loaded with [load] (through TmdbClient's cache): the selected one, and
 * the header's next-up episode. A season is kept as long as TmdbClient keeps it ([Freshness.answer]: an
 * airing one an hour, others a day), and asked again after that when it is selected, played from, or
 * shown again ([shown]), and after TMDB's language changes ([follow]); until the new copy comes, and if
 * it can't be had, the old one stays. One that couldn't be loaded is asked again when selected or shown
 * again. A season already being asked for isn't asked again.
 */
class ShowSeasons(private val scope: CoroutineScope, private val clock: Clock, private val load: suspend (Int) -> Aged<Season>) {
    private val _selected = MutableStateFlow<Int?>(null)
    val selected: StateFlow<Int?> = _selected.asStateFlow()
    private val _state = MutableStateFlow<SeasonState>(SeasonState.Loading)
    val state: StateFlow<SeasonState> = _state.asStateFlow()

    /** Why the header's Play/Resume couldn't play its episode; cleared when it's tapped again. */
    private val _nextUpError = MutableStateFlow<String?>(null)
    val nextUpError: StateFlow<String?> = _nextUpError.asStateFlow()

    /** A season as loaded, and when TmdbClient's copy of it goes stale. */
    private class Kept(val season: Season, val staleAt: Instant)

    private val kept = mutableMapOf<Int, Kept>()
    private val loading = mutableMapOf<Int, CompletableDeferred<Season>>()

    /** Shows season [number], loading it if needed; selecting a failed season again retries it. */
    fun select(number: Int) {
        _selected.value = number
        kept[number]?.let { copy ->
            _state.value = SeasonState.Loaded(copy.season)
            if (stale(copy)) refresh(number)
            return
        }
        _state.value = SeasonState.Loading
        scope.launch {
            val next = try {
                SeasonState.Loaded(fetch(number))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SeasonState.Failed(e.reason())
            }
            if (_selected.value == number) _state.value = next
        }
    }

    /** The page is in front again: the selected season, once past its time or after it failed to load, is asked for again. */
    fun shown() {
        val number = _selected.value ?: return
        val copy = kept[number]
        if (copy != null) {
            if (stale(copy)) refresh(number)
        } else if (_state.value is SeasonState.Failed) {
            select(number)
        }
    }

    /**
     * Follows TMDB's [language], whose answers TmdbClient keeps apart: once it changes, every season kept
     * is past its time, so the selected one is asked for again now and the others when selected or played
     * from. Their copies show until the new ones come.
     */
    fun follow(language: Flow<Any?>): Job = scope.launch {
        language.distinctUntilChanged().drop(1).collect {
            kept.replaceAll { _, copy -> Kept(copy.season, Instant.EPOCH) }
            shown()
        }
    }

    /** Finds [ref] in its season, loading the season if needed, and plays it with [play]. */
    fun playNext(ref: EpisodeRef, play: (Episode) -> Unit): Job = scope.launch {
        _nextUpError.value = null
        val episode = try {
            season(ref.season).episodes.firstOrNull { it.episode == ref.episode }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _nextUpError.value = "Couldn't load ${ref.label} from TMDB: ${e.reason()}"
            return@launch
        }
        if (episode != null) play(episode) else _nextUpError.value = "TMDB doesn't list ${ref.label}"
    }

    /** [number] as kept while it is within its time, else asked for again; the old copy if it can't be had. */
    private suspend fun season(number: Int): Season {
        val copy = kept[number]
        if (copy != null && !stale(copy)) return copy.season
        return try {
            fetch(number)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            copy?.season ?: throw e
        }
    }

    /** Asks for a copy of [number] past its time; the one shown stays if it can't be had. */
    private fun refresh(number: Int) {
        scope.launch {
            try {
                fetch(number)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The old copy stays; it is asked for again next time.
            }
        }
    }

    /** Loads [number], or waits for the load already running, and keeps it; the season selected shows it. */
    private suspend fun fetch(number: Int): Season {
        loading[number]?.let { return it.await() }
        val done = CompletableDeferred<Season>()
        loading[number] = done
        try {
            val answer = load(number)
            kept[number] = Kept(answer.value, clock.instant().minus(answer.age).plus(Freshness.answer(answer.value, LocalDate.now(clock))))
            if (_selected.value == number) _state.value = SeasonState.Loaded(answer.value)
            done.complete(answer.value)
            return answer.value
        } catch (e: Throwable) {
            done.completeExceptionally(e)
            throw e
        } finally {
            loading.remove(number)
        }
    }

    private fun stale(copy: Kept): Boolean = !clock.instant().isBefore(copy.staleAt)

    private fun Exception.reason(): String = (this as? TmdbException)?.error?.message ?: message ?: javaClass.simpleName
}
