package io.skjaere.usenet.app.collection

import io.skjaere.usenet.app.collection.model.showDetails
import io.skjaere.usenet.app.collection.model.toSummary
import io.skjaere.usenet.app.refresh.SchedulerClock
import io.skjaere.usenet.data.ShowSummary
import io.skjaere.usenet.metadata.Aged
import io.skjaere.usenet.metadata.Freshness
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.metadata.TmdbError
import io.skjaere.usenet.metadata.TmdbException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A Show or Movie page's TMDB answer while its ViewModel lives (under the player, with the Collection
 * tab's saved state): it follows the stored facts its collection row shows, and is asked again when
 * the page is shown after the time TmdbClient's cache keeps it, never sooner.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PageAnswerTest {
    private val v1 = showDetails(900040, last = 1 to 5, lastAired = LocalDate.of(2026, 10, 7))
    private val v2 = showDetails(900040, last = 1 to 6, lastAired = LocalDate.of(2026, 10, 14))
    /** Its next episode airs today: TmdbClient keeps the answer an hour. */
    private val airing = showDetails(900040, last = 1 to 5, lastAired = LocalDate.of(2026, 10, 7), next = 1 to 6, nextAirs = LocalDate.of(2026, 10, 14))
    private val stored = MutableStateFlow<ShowSummary?>(null)
    /** TMDB's language as Settings › Metadata sets it. */
    private val language = MutableStateFlow<String?>(null)

    /** TMDB through the cache: [answers] in turn, the last one again after that; [loads] counts the asks. */
    private class Tmdb(vararg answers: Aged<ShowDetails>) {
        val queue = ArrayDeque(answers.toList())
        var loads = 0
        var failing: TmdbException? = null
        var gate: CompletableDeferred<Unit>? = null

        suspend fun load(): Aged<ShowDetails> {
            loads++
            gate?.await()
            failing?.let { throw it }
            return if (queue.size > 1) queue.removeFirst() else queue.first()
        }
    }

    private fun TestScope.page(tmdb: Tmdb, scope: CoroutineScope = backgroundScope, onAnswer: suspend (Aged<ShowDetails>, Boolean) -> Unit = { _, _ -> }) =
        PageAnswer(scope, SchedulerClock(testScheduler), tmdb::load, Freshness::answer, onAnswer).also { it.follow(stored, language) { d -> d.toSummary() } }

    private fun fresh(d: ShowDetails) = Aged(d, Duration.ZERO)

    @Test
    fun `a change to the stored facts reaches the open page`() = runTest {
        val tmdb = Tmdb(fresh(v1), fresh(v2))
        val page = page(tmdb)
        runCurrent()
        assertEquals(v1, page.value.value)
        // The refresher's answer, written an hour later: TmdbClient's cache has it, the page reads it.
        advanceTimeBy(Duration.ofHours(1).toMillis())
        stored.value = v2.toSummary()
        runCurrent()
        assertEquals(v2, page.value.value)
        assertEquals(2, tmdb.loads)
    }

    @Test
    fun `stored facts the page already shows ask nothing`() = runTest {
        // Stored before the page opened, and its own answer stored after it (the refresher takes the page's offer).
        stored.value = v1.toSummary()
        val tmdb = Tmdb(fresh(v1))
        page(tmdb)
        runCurrent()
        stored.value = v1.toSummary().copy()
        stored.value = null
        stored.value = v1.toSummary()
        runCurrent()
        assertEquals(1, tmdb.loads)
    }

    @Test
    fun `the facts stored as the page opens don't ask again`() = runTest {
        stored.value = v2.toSummary()
        val tmdb = Tmdb(fresh(v1))
        val page = page(tmdb)
        runCurrent()
        assertEquals(v1 to 1, page.value.value to tmdb.loads)
    }

    @Test
    fun `shown again before its time asks nothing, after it asks once`() = runTest {
        val tmdb = Tmdb(fresh(v1), fresh(v2))
        val page = page(tmdb)
        runCurrent()
        // A returning show without a next date: TmdbClient keeps it a day.
        advanceTimeBy(Duration.ofHours(23).toMillis())
        page.shown()
        runCurrent()
        assertEquals(v1 to 1, page.value.value to tmdb.loads)
        advanceTimeBy(Duration.ofHours(1).toMillis())
        page.shown()
        page.shown()
        runCurrent()
        assertEquals(v2 to 2, page.value.value to tmdb.loads)
        page.shown()
        runCurrent()
        assertEquals(2, tmdb.loads)
    }

    @Test
    fun `a show whose next episode is due is asked again an hour on, and a copy from the cache counts its age`() = runTest {
        val tmdb = Tmdb(Aged(airing, Duration.ofMinutes(50)), fresh(v2))
        val page = page(tmdb)
        runCurrent()
        advanceTimeBy(Duration.ofMinutes(9).toMillis())
        page.shown()
        runCurrent()
        assertEquals(1, tmdb.loads)
        advanceTimeBy(Duration.ofMinutes(1).toMillis())
        page.shown()
        runCurrent()
        assertEquals(v2 to 2, page.value.value to tmdb.loads)
    }

    @Test
    fun `shown again while its answer loads asks nothing more`() = runTest {
        val tmdb = Tmdb(fresh(v1)).apply { gate = CompletableDeferred() }
        val page = page(tmdb)
        runCurrent()
        page.shown()
        runCurrent()
        tmdb.gate?.complete(Unit)
        runCurrent()
        assertEquals(v1 to 1, page.value.value to tmdb.loads)
    }

    @Test
    fun `a stored change while its answer loads asks once more after it`() = runTest {
        val tmdb = Tmdb(fresh(v1), fresh(v2)).apply { gate = CompletableDeferred() }
        val page = page(tmdb)
        runCurrent()
        stored.value = v2.toSummary()
        runCurrent()
        tmdb.gate?.complete(Unit)
        runCurrent()
        assertEquals(v2 to 2, page.value.value to tmdb.loads)
    }

    @Test
    fun `an answer that can't be had again keeps the one there was`() = runTest {
        val tmdb = Tmdb(fresh(v1))
        val page = page(tmdb)
        runCurrent()
        advanceTimeBy(Duration.ofHours(25).toMillis())
        tmdb.failing = TmdbException(TmdbError.InvalidKey("Invalid API key"))
        page.shown()
        stored.value = v2.toSummary()
        runCurrent()
        assertEquals(v1, page.value.value)
        assertNull(page.error.value)
    }

    @Test
    fun `a page that couldn't load says why, and loads when shown again or when the stored facts change`() = runTest {
        val tmdb = Tmdb(fresh(v1), fresh(v2)).apply { failing = TmdbException(TmdbError.NoKey()) }
        val page = page(tmdb)
        runCurrent()
        assertEquals(null to "No TMDB key set", page.value.value to page.error.value)
        tmdb.failing = null
        page.shown()
        runCurrent()
        assertEquals(v1 to null, page.value.value to page.error.value)

        val stuck = Tmdb(fresh(v1)).apply { failing = TmdbException(TmdbError.NoKey()) }
        val other = page(stuck)
        runCurrent()
        stuck.failing = null
        stored.value = v2.toSummary()
        runCurrent()
        assertEquals(v1, other.value.value)
    }

    @Test
    fun `a change of TMDB's language asks again in it, whatever the answer's age`() = runTest {
        val tmdb = Tmdb(fresh(v1), fresh(v2))
        val page = page(tmdb)
        runCurrent()
        // TmdbClient keeps each language's answers apart: a page opened now would read the new one.
        language.value = "es-ES"
        runCurrent()
        assertEquals(v2 to 2, page.value.value to tmdb.loads)
        language.value = "es-ES"
        runCurrent()
        assertEquals(2, tmdb.loads)
    }

    @Test
    fun `each answer is handed on, the first said to be`() = runTest {
        val answers = mutableListOf<Pair<ShowDetails, Boolean>>()
        val tmdb = Tmdb(fresh(v1), fresh(v2))
        page(tmdb) { aged, first -> answers += aged.value to first }
        runCurrent()
        stored.value = v2.toSummary()
        runCurrent()
        assertEquals(listOf(v1 to true, v2 to false), answers)
    }
}
