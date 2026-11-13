package io.skjaere.usenet.app.collection

import io.skjaere.usenet.metadata.Aged
import io.skjaere.usenet.metadata.TmdbException
import kotlinx.coroutines.CancellationException
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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * A title page's TMDB answer ([load], through TmdbClient's cache), kept up to date while the page
 * lives: its ViewModel stays under the player and with the Collection tab's saved state, for days.
 * It is asked again when the stored facts its collection row shows ([follow], the lists' refresher's)
 * change to ones it doesn't say, when TMDB's language changes, and when the page is shown again
 * ([shown]) once it is older than TmdbClient keeps it ([freshFor]). Either way TmdbClient's cache
 * answers while it can: the refresher's own answer is there, and a page shown again asks just when a
 * page opened anew would. Nothing is asked twice at once. [onAnswer] gets each answer, and whether it
 * is the page's first; an answer that can't be had again leaves the one there was.
 *
 * TMDB's answer wins over the stored facts, as it did before: it is the whole answer (overview,
 * seasons), at least as new as the stored facts once it follows them, and for a title not collected,
 * whose stored facts nothing refreshes, the newer of the two.
 */
internal class PageAnswer<T : Any>(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val load: suspend () -> Aged<T>,
    /** How long TmdbClient keeps the answer ([io.skjaere.usenet.metadata.Freshness.answer]). */
    private val freshFor: (T, LocalDate) -> Duration,
    private val onAnswer: suspend (answer: Aged<T>, first: Boolean) -> Unit = { _, _ -> },
) {
    private val _value = MutableStateFlow<T?>(null)
    val value: StateFlow<T?> = _value.asStateFlow()

    /** Why there is no answer yet; null once one came. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** When TMDB produced the answer there is (now less its age). */
    var answeredAt: Instant = Instant.EPOCH
        private set

    private var loading: Job? = null
    /** Asked for again while a load ran: one more follows it. */
    private var again = false

    /**
     * Asks for the answer, then follows [stored], the facts the lists keep of the title: once they
     * change to facts the answer doesn't say ([facts] of it), or while there is no answer, it is asked
     * for again. Those stored as the page opens are what its first answer is asked against. It also
     * follows TMDB's [language], whose answers TmdbClient keeps apart: once it changes, the answer is
     * asked for in it, as a page opened anew would. Called once, when the page's ViewModel is ready
     * for [onAnswer].
     */
    fun <S : Any> follow(stored: Flow<S?>, language: Flow<Any?>, facts: (T) -> S): Job {
        reload()
        return scope.launch {
            launch { language.distinctUntilChanged().drop(1).collect { reload() } }
            stored.distinctUntilChanged().drop(1).collect { s ->
                if (s != null && _value.value?.let(facts) != s) reload()
            }
        }
    }

    /** The page is in front again: an answer past its time, or none after a failure, is asked for again. */
    fun shown() {
        if (loading?.isActive == true) return
        val answer = _value.value
        if (answer == null || !clock.instant().isBefore(answeredAt.plus(freshFor(answer, LocalDate.now(clock))))) reload()
    }

    private fun reload() {
        if (loading?.isActive == true) {
            again = true
            return
        }
        loading = scope.launch {
            do {
                again = false
                fetch()
            } while (again)
        }
    }

    private suspend fun fetch() {
        val answer = try {
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (_value.value == null) _error.value = (e as? TmdbException)?.error?.message ?: e.message ?: e.javaClass.simpleName
            return
        }
        val first = _value.value == null
        _value.value = answer.value
        answeredAt = clock.instant().minus(answer.age)
        _error.value = null
        onAnswer(answer, first)
    }
}
