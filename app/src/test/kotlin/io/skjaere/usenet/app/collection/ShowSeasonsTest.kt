package io.skjaere.usenet.app.collection

import io.skjaere.usenet.app.refresh.SchedulerClock
import io.skjaere.usenet.metadata.Aged
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeRef
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.TmdbError
import io.skjaere.usenet.metadata.TmdbException
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ShowSeasonsTest {
    private val offline = TmdbException(TmdbError.Network("Unable to resolve host"))

    private fun episode(season: Int, number: Int, airDate: LocalDate? = null, name: String? = null) = Episode(season, number, name, null, airDate, null, null)
    private fun season(number: Int, episodes: Int = 3) = Season(900002, number, null, null, (1..episodes).map { episode(number, it) })
    private fun fresh(season: Season) = Aged(season, Duration.ZERO)

    /** Its episodes aired over the last three weeks, the last one yesterday: TmdbClient keeps it an hour. */
    private fun airing(name: String) = Season(900002, 2, null, null, (1..3).map { episode(2, it, today.minusDays(21L - 10 * (it - 1)), "$name $it") })
    /** Aired a year ago: TmdbClient keeps it a day. */
    private fun finished(name: String) = Season(900002, 1, null, null, (1..3).map { episode(1, it, today.minusYears(1).plusDays(7L * it), "$name $it") })
    private val today = LocalDate.of(2026, 10, 14)

    /** TMDB through the cache: [answers] in turn for each season, the last one again after that; [loads] counts the asks. */
    private class Tmdb(private val answers: Map<Int, List<Aged<Season>>>) {
        val loads = mutableListOf<Int>()
        var failing: TmdbException? = null
        var gate: CompletableDeferred<Unit>? = null

        suspend fun load(number: Int): Aged<Season> {
            loads += number
            gate?.await()
            failing?.let { throw it }
            val list = answers.getValue(number)
            return list[minOf(loads.count { it == number }, list.size) - 1]
        }
    }

    private fun TestScope.seasons(tmdb: Tmdb) = ShowSeasons(backgroundScope, SchedulerClock(testScheduler), tmdb::load)

    @Test
    fun `an airing season is asked again when the page is shown an hour on, not before`() = runTest {
        val tmdb = Tmdb(mapOf(2 to listOf(fresh(airing("Old")), fresh(airing("New")))))
        val seasons = seasons(tmdb)
        seasons.select(2)
        runCurrent()
        advanceTimeBy(Duration.ofMinutes(59).toMillis())
        seasons.shown()
        runCurrent()
        assertEquals<Pair<SeasonState, List<Int>>>(SeasonState.Loaded(airing("Old")) to listOf(2), seasons.state.value to tmdb.loads)
        advanceTimeBy(Duration.ofMinutes(1).toMillis())
        seasons.shown()
        // The old copy stays up while the new one comes.
        assertEquals(SeasonState.Loaded(airing("Old")), seasons.state.value)
        runCurrent()
        assertEquals<Pair<SeasonState, List<Int>>>(SeasonState.Loaded(airing("New")) to listOf(2, 2), seasons.state.value to tmdb.loads)
    }

    @Test
    fun `a change of TMDB's language asks again for the selected season at once, and for the others when selected`() = runTest {
        val tmdb = Tmdb(mapOf(1 to listOf(fresh(finished("Old")), fresh(finished("Nuevo"))), 2 to listOf(fresh(airing("Old")), fresh(airing("Nuevo")))))
        val language = MutableStateFlow<String?>("en-US")
        val seasons = seasons(tmdb).also { it.follow(language) }
        seasons.select(1)
        runCurrent()
        seasons.select(2)
        runCurrent()
        language.value = "es-ES"
        runCurrent()
        assertEquals<Pair<SeasonState, List<Int>>>(SeasonState.Loaded(airing("Nuevo")) to listOf(1, 2, 2), seasons.state.value to tmdb.loads)
        // Season 1, though within its day: its copy shows until the new one comes.
        seasons.select(1)
        assertEquals(SeasonState.Loaded(finished("Old")), seasons.state.value)
        runCurrent()
        assertEquals<Pair<SeasonState, List<Int>>>(SeasonState.Loaded(finished("Nuevo")) to listOf(1, 2, 2, 1), seasons.state.value to tmdb.loads)
    }

    @Test
    fun `a finished season is asked again a day on, and a copy from the cache counts its age`() = runTest {
        val tmdb = Tmdb(mapOf(1 to listOf(Aged(finished("Old"), Duration.ofHours(20)), fresh(finished("New")))))
        val seasons = seasons(tmdb)
        seasons.select(1)
        runCurrent()
        advanceTimeBy(Duration.ofHours(3).toMillis())
        seasons.shown()
        runCurrent()
        assertEquals(1, tmdb.loads.size)
        advanceTimeBy(Duration.ofHours(1).toMillis())
        seasons.shown()
        runCurrent()
        assertEquals(SeasonState.Loaded(finished("New")) to 2, seasons.state.value to tmdb.loads.size)
    }

    @Test
    fun `a season selected again after its time shows its copy at once and is asked again`() = runTest {
        val tmdb = Tmdb(mapOf(1 to listOf(fresh(finished("Old")), fresh(finished("New"))), 2 to listOf(fresh(airing("Old")))))
        val seasons = seasons(tmdb)
        seasons.select(1)
        runCurrent()
        seasons.select(2)
        runCurrent()
        // Back to season 1 within its day: from what was loaded.
        seasons.select(1)
        assertEquals(SeasonState.Loaded(finished("Old")), seasons.state.value)
        runCurrent()
        assertEquals(listOf(1, 2), tmdb.loads)
        advanceTimeBy(Duration.ofHours(24).toMillis())
        seasons.select(1)
        assertEquals(SeasonState.Loaded(finished("Old")), seasons.state.value)
        runCurrent()
        assertEquals<Pair<SeasonState, List<Int>>>(SeasonState.Loaded(finished("New")) to listOf(1, 2, 1), seasons.state.value to tmdb.loads)
    }

    @Test
    fun `a season that can't be asked again stays as it was`() = runTest {
        val tmdb = Tmdb(mapOf(2 to listOf(fresh(airing("Old")))))
        val seasons = seasons(tmdb)
        seasons.select(2)
        runCurrent()
        advanceTimeBy(Duration.ofHours(2).toMillis())
        tmdb.failing = offline
        seasons.shown()
        runCurrent()
        assertEquals<Pair<SeasonState, List<Int>>>(SeasonState.Loaded(airing("Old")) to listOf(2, 2), seasons.state.value to tmdb.loads)
        // The next-up episode plays from it too.
        val played = mutableListOf<Episode>()
        seasons.playNext(EpisodeRef(2, 3)) { played += it }
        runCurrent()
        assertEquals(listOf(airing("Old").episodes[2]), played)
        assertNull(seasons.nextUpError.value)
    }

    @Test
    fun `asks made while a season loads share its answer`() = runTest {
        val tmdb = Tmdb(mapOf(2 to listOf(fresh(airing("Old")), fresh(airing("New")))))
        val seasons = seasons(tmdb)
        seasons.select(2)
        runCurrent()
        advanceTimeBy(Duration.ofHours(2).toMillis())
        tmdb.gate = CompletableDeferred()
        seasons.shown()
        seasons.shown()
        seasons.select(2)
        val played = mutableListOf<Episode>()
        seasons.playNext(EpisodeRef(2, 1)) { played += it }
        runCurrent()
        tmdb.gate?.complete(Unit)
        runCurrent()
        assertEquals(listOf(2, 2), tmdb.loads)
        assertEquals(SeasonState.Loaded(airing("New")), seasons.state.value)
        assertEquals(listOf(airing("New").episodes[0]), played)
    }

    @Test
    fun `the next-up episode plays from its season as loaded within its time, and from a new copy after it`() = runTest {
        val tmdb = Tmdb(mapOf(2 to listOf(fresh(airing("Old")), fresh(airing("New")))))
        val seasons = seasons(tmdb)
        val played = mutableListOf<Episode>()
        seasons.playNext(EpisodeRef(2, 1)) { played += it }
        runCurrent()
        seasons.playNext(EpisodeRef(2, 1)) { played += it }
        runCurrent()
        advanceTimeBy(Duration.ofHours(1).toMillis())
        seasons.playNext(EpisodeRef(2, 1)) { played += it }
        runCurrent()
        assertEquals(listOf("Old 1", "Old 1", "New 1"), played.map { it.name })
        assertEquals(listOf(2, 2), tmdb.loads)
        // Loaded by Play, it is what the list shows when selected.
        seasons.select(2)
        assertEquals(SeasonState.Loaded(airing("New")), seasons.state.value)
    }

    @Test
    fun `a season that can't be loaded says why instead of loading forever`() = runTest {
        val seasons = ShowSeasons(backgroundScope, SchedulerClock(testScheduler)) { throw offline }
        seasons.select(4)
        assertEquals(SeasonState.Loading, seasons.state.value)
        runCurrent()
        assertEquals(SeasonState.Failed("Unable to resolve host"), seasons.state.value)
    }

    @Test
    fun `trying a failed season again loads it`() = runTest {
        var calls = 0
        val seasons = ShowSeasons(backgroundScope, SchedulerClock(testScheduler)) { n -> if (calls++ == 0) throw offline else fresh(season(n)) }
        seasons.select(4)
        runCurrent()
        assertIs<SeasonState.Failed>(seasons.state.value)
        seasons.select(4)
        assertEquals(SeasonState.Loading, seasons.state.value)
        runCurrent()
        assertEquals(SeasonState.Loaded(season(4)), seasons.state.value)
    }

    @Test
    fun `a season that couldn't be loaded is asked again when the page is shown, not while it loads`() = runTest {
        val tmdb = Tmdb(mapOf(3 to listOf(fresh(season(3))))).apply { failing = offline }
        val seasons = seasons(tmdb)
        seasons.select(3)
        runCurrent()
        assertIs<SeasonState.Failed>(seasons.state.value)
        // Back on Wi-Fi, the page comes back to the front.
        tmdb.failing = null
        tmdb.gate = CompletableDeferred()
        seasons.shown()
        runCurrent()
        seasons.shown()
        runCurrent()
        tmdb.gate?.complete(Unit)
        runCurrent()
        assertEquals<Pair<SeasonState, List<Int>>>(SeasonState.Loaded(season(3)) to listOf(3, 3), seasons.state.value to tmdb.loads)
    }

    @Test
    fun `a season failing after another was picked doesn't replace it`() = runTest {
        val third = CompletableDeferred<Season>()
        val seasons = ShowSeasons(backgroundScope, SchedulerClock(testScheduler)) { n -> fresh(if (n == 3) third.await() else season(n)) }
        seasons.select(3)
        runCurrent()
        seasons.select(4)
        runCurrent()
        third.completeExceptionally(offline)
        runCurrent()
        assertEquals(SeasonState.Loaded(season(4)), seasons.state.value)
    }

    @Test
    fun `a next-up episode whose season can't be loaded says why instead of doing nothing`() = runTest {
        val seasons = ShowSeasons(backgroundScope, SchedulerClock(testScheduler)) { throw offline }
        val played = mutableListOf<Episode>()
        seasons.playNext(EpisodeRef(4, 1)) { played += it }
        runCurrent()
        assertEquals(emptyList(), played)
        assertEquals("Couldn't load S04E01 from TMDB: Unable to resolve host", seasons.nextUpError.value)
    }

    @Test
    fun `a next-up episode missing from its season says so`() = runTest {
        val seasons = ShowSeasons(backgroundScope, SchedulerClock(testScheduler)) { fresh(season(it, episodes = 3)) }
        val played = mutableListOf<Episode>()
        seasons.playNext(EpisodeRef(4, 5)) { played += it }
        runCurrent()
        assertEquals(emptyList(), played)
        assertEquals("TMDB doesn't list S04E05", seasons.nextUpError.value)
    }

    @Test
    fun `asking for the next-up episode again clears the failure and plays it from a season loaded once`() = runTest {
        var calls = 0
        val seasons = ShowSeasons(backgroundScope, SchedulerClock(testScheduler)) { n -> if (calls++ == 0) throw offline else fresh(season(n)) }
        val played = mutableListOf<Episode>()
        seasons.playNext(EpisodeRef(4, 1)) { played += it }
        runCurrent()
        assertNotNull(seasons.nextUpError.value)
        seasons.playNext(EpisodeRef(4, 1)) { played += it }
        runCurrent()
        assertNull(seasons.nextUpError.value)
        assertEquals(listOf(episode(4, 1)), played)
        seasons.select(4)
        assertEquals(SeasonState.Loaded(season(4)), seasons.state.value)
        assertEquals(2, calls)
    }
}
