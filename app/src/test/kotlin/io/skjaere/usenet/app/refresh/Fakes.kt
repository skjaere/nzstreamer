package io.skjaere.usenet.app.refresh

import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.data.MovieSummary
import io.skjaere.usenet.data.RefreshCandidate
import io.skjaere.usenet.data.ShowSummary
import io.skjaere.usenet.data.SummaryRefresh
import io.skjaere.usenet.metadata.Aged
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.MovieDetails
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.metadata.TmdbError
import io.skjaere.usenet.metadata.TmdbException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Wall time that moves with the test's virtual time, from [start]. */
class SchedulerClock(private val scheduler: TestCoroutineScheduler, private val start: Instant = START, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
    override fun instant(): Instant = start.plusMillis(scheduler.currentTime)
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = SchedulerClock(scheduler, start, zone)

    companion object {
        val START: Instant = Instant.parse("2026-10-14T10:00:00Z")
    }
}

fun fakeShow(id: Int, status: String = "Returning Series") = ShowDetails(
    id, "Avrel $id", null, 2021, null, null, null, status, true, 45, listOf(SeasonSummary(1, null, 10, null, null)),
    null, null, ExternalIds(),
)

fun fakeMovie(id: Int) = MovieDetails(id, "Ébrin $id", null, 2019, null, null, null, 100, ExternalIds(), status = "Released")

/**
 * TMDB as the refresher sees it, in virtual time: answers from the network (age zero) wait at the
 * caller's RateGate as TmdbClient's beforeNetwork does; cached ones ([cached], an hour old by default)
 * don't. Errors per title; every call and whether it ran under a gate is recorded.
 */
class FakeTitleSource(private val scheduler: TestCoroutineScheduler, var latencyMs: Long = 0) : TitleSource {
    val calls = mutableListOf<TitleKey>()
    val callTimes = mutableListOf<Pair<TitleKey, Long>>()
    val passes = mutableListOf<Long>()
    val gated = mutableListOf<Boolean>()
    var inFlight = 0
        private set
    var maxInFlight = 0
        private set
    var cancelled = 0
        private set
    val answers = mutableMapOf<TitleKey, Aged<Any>>()
    val errors = mutableMapOf<TitleKey, TmdbError>()
    /** From the network, not the cache. */
    val network = mutableSetOf<TitleKey>()

    override suspend fun show(tmdbId: Int): Aged<ShowDetails> {
        @Suppress("UNCHECKED_CAST")
        return answer(TitleKey.show(tmdbId)) { (answers[it] ?: Aged(fakeShow(tmdbId), age(it))) as Aged<ShowDetails> }
    }

    override suspend fun movie(tmdbId: Int): Aged<MovieDetails> {
        @Suppress("UNCHECKED_CAST")
        return answer(TitleKey.movie(tmdbId)) { (answers[it] ?: Aged(fakeMovie(tmdbId), age(it))) as Aged<MovieDetails> }
    }

    private fun age(key: TitleKey): Duration = if (key in network) Duration.ZERO else Duration.ofHours(1)

    private suspend fun <T> answer(key: TitleKey, value: (TitleKey) -> Aged<T>): Aged<T> {
        calls += key
        callTimes += key to scheduler.currentTime
        val gate = currentCoroutineContext()[RateGate]
        gated += gate != null
        inFlight++
        maxInFlight = maxOf(maxInFlight, inFlight)
        try {
            val error = errors[key]
            if (key in network || error != null) {
                gate?.pass()
                passes += scheduler.currentTime
            }
            delay(latencyMs)
            error?.let { throw TmdbException(it) }
            return value(key)
        } catch (e: CancellationException) {
            cancelled++
            throw e
        } finally {
            inFlight--
        }
    }
}

/**
 * The library's side, in memory: collected titles, bookkeeping and facts; every save recorded, refused
 * while [full], and with [hold] set held until it completes (a write that takes a while).
 */
class FakeSummaryStore(shows: Collection<Int> = emptyList(), movies: Collection<Int> = emptyList(), private val time: () -> Long) : SummaryStore {
    data class Flush(val at: Long, val titles: Int, val facts: Int)

    val collectedShows = MutableStateFlow(shows.toSet())
    val collectedMovies = MutableStateFlow(movies.toSet())
    val refresh = mutableMapOf<TitleKey, SummaryRefresh>()
    val showFacts = mutableMapOf<Int, ShowSummary>()
    val movieFacts = mutableMapOf<Int, MovieSummary>()
    val lastWatched = mutableMapOf<TitleKey, Long>()
    val added = mutableMapOf<TitleKey, Long>()
    val flushes = mutableListOf<Flush>()
    val prunes = mutableListOf<Long>()
    /** The disk is full: a save throws, nothing written. */
    var full = false
    var hold: CompletableDeferred<Unit>? = null
    var invalidations = 0
        private set
    private val version = MutableStateFlow(0)

    override val candidates: Flow<Pair<List<RefreshCandidate>, List<RefreshCandidate>>> =
        combine(version, collectedShows, collectedMovies) { _, s, m -> s.map { candidate(TitleKey.show(it)) } to m.map { candidate(TitleKey.movie(it)) } }

    private fun candidate(key: TitleKey): RefreshCandidate {
        val r = refresh[key]
        return RefreshCandidate(key.tmdbId, added[key] ?: 0, r?.fetchedAt, r?.staleAt, r?.retryAt, r?.failures, lastWatched[key])
    }

    override suspend fun save(shows: List<ShowSummary>, movies: List<MovieSummary>, refresh: List<SummaryRefresh>) {
        hold?.await()
        if (full) throw IOException("No space left on device")
        flushes += Flush(time(), refresh.size, shows.size + movies.size)
        shows.forEach { showFacts[it.tmdbId] = it }
        movies.forEach { movieFacts[it.tmdbId] = it }
        refresh.forEach { this.refresh[if (it.type == SummaryRefresh.SHOW) TitleKey.show(it.tmdbId) else TitleKey.movie(it.tmdbId)] = it }
        version.value++
    }

    override suspend fun invalidateAll() {
        refresh.replaceAll { _, r -> r.copy(staleAt = 0, retryAt = 0) }
        invalidations++
        version.value++
    }

    override suspend fun prune(before: Long) {
        prunes += before
    }

    fun touch() {
        version.value++
    }
}
