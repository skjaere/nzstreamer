package io.skjaere.usenet.app.refresh

import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.app.collection.model.toSummary
import io.skjaere.usenet.data.MovieSummary
import io.skjaere.usenet.data.RefreshCandidate
import io.skjaere.usenet.data.ShowSummary
import io.skjaere.usenet.data.SummaryRefresh
import io.skjaere.usenet.metadata.Aged
import io.skjaere.usenet.metadata.MovieDetails
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.metadata.TmdbError
import io.skjaere.usenet.metadata.TmdbException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

/**
 * Keeps the lists' TMDB facts ([SummaryStore]) fresh: one per process, for Home and the collection.
 * While [start]'s flow says active (the app in front, with a TMDB key) it asks [source] about the
 * collected titles that are due ([Staleness]), most relevant first ([RefreshPlanner]), [workers] at a
 * time, each under [gate] (TmdbClient spaces its network requests by it; answers from its cache go
 * through at once). Answers are written together, every [flushEveryMs] or [flushAt] titles, so a
 * cold fill wakes the lists about once a second; bookkeeping goes to a table no list reads.
 *
 * No network, or TMDB's rate limit, pauses every worker without counting against the title, until
 * the pause runs out or the app comes back to the front (a pause for no network also ends when TMDB
 * itself answers); a key TMDB rejects stops the loop until it starts again (a new key, the app coming back).
 */
class SummaryRefresher(
    private val store: SummaryStore,
    private val source: TitleSource,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val gate: RateGate,
    private val workers: Int = 4,
    private val flushEveryMs: Long = 1_000,
    private val flushAt: Int = 25,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val log = LoggerFactory.getLogger(SummaryRefresher::class.java)

    /** What a fetch learnt: facts (or none, after a failure) and bookkeeping, done once written. [asked]: the [generation] it was asked for in. */
    private class Outcome(val key: TitleKey, val show: ShowSummary?, val movie: MovieSummary?, val refresh: SummaryRefresh, val asked: Long) {
        val written = CompletableDeferred<Unit>()
    }

    private val _status = MutableStateFlow<RefreshStatus>(RefreshStatus.Idle)
    val status: StateFlow<RefreshStatus> = _status.asStateFlow()

    /** Shared by the loop and [refreshNow], fair: a title asked for waits for one fetch at most. */
    private val permits = Semaphore(workers)
    private val lock = Any()

    // Guarded by lock.
    private val inFlight = HashMap<TitleKey, CompletableDeferred<Outcome?>>()
    private var candidates: Map<TitleKey, RefreshCandidate> = emptyMap()
    /**
     * The bookkeeping this refresher produced that the store's candidates don't show yet (queued,
     * or written and not read back): without it a title just answered would look due again.
     */
    private val produced = HashMap<TitleKey, SummaryRefresh>()
    private var visible: List<TitleKey> = emptyList()
    private var pausedUntil = 0L
    private var pauseReason = RefreshStatus.Reason.Offline
    private var pauses = 0
    private var stopped: RefreshStatus.Reason? = null
    private var running = false
    private var pruned = false
    /** How many times the TMDB language changed ([invalidateAll]). */
    private var generation = 0L

    private val wake = Channel<Unit>(Channel.CONFLATED)

    // Guarded by pending.
    private val pending = ArrayList<Outcome>()
    private var flushTimer: Job? = null
    private val writing = Mutex()

    /** Runs the loop whenever [active] says so, until the returned job is cancelled. A new value restarts it (a new key). */
    fun start(active: Flow<Boolean>): Job = scope.launch(dispatcher) {
        active.collectLatest { on ->
            if (on) run() else _status.value = RefreshStatus.Idle
        }
    }

    /** The rows on screen, in order: they come right after the recently watched. */
    fun prioritize(visible: List<TitleKey>) {
        synchronized(lock) { this.visible = visible }
        wake.trySend(Unit)
    }

    /**
     * Asks about [keys] now, due or not, even while inactive (a title just added); shares a fetch the
     * loop is making. Returns once what TMDB said is stored (nothing is without a key).
     */
    suspend fun refreshNow(keys: Collection<TitleKey>) {
        val outcomes = withContext(gate + dispatcher) { keys.distinct().map { key -> async { join(key) } }.awaitAll() }
        flush(all = true)
        outcomes.filterNotNull().forEach { it.written.await() }
    }

    /**
     * The Show page's answer, stored as the refresher's own when the show is collected; asks TMDB
     * nothing. [age] counts the time the page has been open, so one past its policy is due again soon and pauses nothing.
     */
    fun offer(details: ShowDetails, age: Duration) = offer(TitleKey.show(details.tmdbId), Aged(details, age)) { details.toSummary() to null }

    fun offer(details: MovieDetails, age: Duration) = offer(TitleKey.movie(details.tmdbId), Aged(details, age)) { null to details.toSummary() }

    /**
     * Every title due now (the TMDB language changed); the facts stay until the new answers. One asked
     * for in the old language and not written yet (queued, or still on its way) is written as due too.
     */
    suspend fun invalidateAll() {
        // After a batch being written, never before it: that would write it over as fresh.
        writing.withLock {
            synchronized(lock) {
                generation++
                produced.clear()
            }
            store.invalidateAll()
        }
        wake.trySend(Unit)
    }

    private fun offer(key: TitleKey, aged: Aged<Any>, facts: () -> Pair<ShowSummary?, MovieSummary?>) {
        val asked = synchronized(lock) { generation }
        scope.launch(dispatcher) {
            // Collected titles only: a show looked at from Search is no business of the lists.
            if (synchronized(lock) { key in candidates } || key in snapshot()) {
                val (show, movie) = facts()
                enqueue(answered(key, show, movie, aged, asked, offered = true))
            }
        }
    }

    private suspend fun snapshot(): Map<TitleKey, RefreshCandidate> = store.candidates.first().let { (shows, movies) -> keyed(shows, movies) }

    private fun keyed(shows: List<RefreshCandidate>, movies: List<RefreshCandidate>) =
        shows.associateBy { TitleKey.show(it.tmdbId) } + movies.associateBy { TitleKey.movie(it.tmdbId) }

    private fun now() = clock.millis()

    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun run() = coroutineScope {
        synchronized(lock) {
            stopped = null
            running = true
            // Back in front: asked again at once, whatever pause was on.
            pausedUntil = 0
            pauses = 0
        }
        try {
            if (!pruned) {
                pruned = true
                try {
                    store.prune(now() - PRUNE_AFTER.toMillis())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Couldn't prune the lists' TMDB facts", e)
                }
            }
            launch {
                store.candidates.collect { (shows, movies) ->
                    synchronized(lock) {
                        candidates = keyed(shows, movies)
                        // Read back as written: the store's own bookkeeping counts again.
                        produced.entries.removeAll { (key, r) -> candidates[key]?.let { it.fetchedAt == r.fetchedAt && it.staleAt == r.staleAt && it.retryAt == r.retryAt && it.failures == r.failures } == true }
                    }
                    wake.trySend(Unit)
                }
            }
            withContext(gate) {
                while (true) {
                    permits.acquire()
                    val (key, done) = try {
                        next()
                    } catch (e: Throwable) {
                        permits.release()
                        throw e
                    }
                    // Atomic: a worker cancelled before it starts (the app left the front) still gives
                    // back its permit and its title, else both are gone for the process.
                    launch(start = CoroutineStart.ATOMIC) {
                        try {
                            fetch(key, done)
                        } finally {
                            permits.release()
                        }
                    }
                }
            }
        } finally {
            synchronized(lock) { running = false }
            // What was answered is written, also when the app leaves the front.
            withContext(NonCancellable) { flush(all = true) }
        }
    }

    /** Waits until a title is due and nothing holds the loop back; it is then in flight. */
    private suspend fun next(): Pair<TitleKey, CompletableDeferred<Outcome?>> {
        while (true) {
            // Neither a free permit nor a title due suspends: nothing else stops a cancelled loop picking.
            currentCoroutineContext().ensureActive()
            val now = now()
            var wakeAt: Long? = null
            val picked = synchronized(lock) {
                val known = known()
                val order = if (stopped != null || pausedUntil > now) emptyList() else RefreshPlanner.order(known, now, visible, inFlight.keys)
                _status.value = when {
                    stopped != null -> RefreshStatus.Paused(stopped!!, null)
                    pausedUntil > now -> RefreshStatus.Paused(pauseReason, pausedUntil)
                    order.size + inFlight.size > 0 -> RefreshStatus.Running(order.size + inFlight.size)
                    else -> RefreshStatus.Idle
                }
                wakeAt = when {
                    stopped != null -> null
                    pausedUntil > now -> pausedUntil
                    else -> RefreshPlanner.nextWake(known, now, inFlight.keys)
                }
                order.firstOrNull()?.let { key -> key to CompletableDeferred<Outcome?>().also { inFlight[key] = it } }
            }
            if (picked != null) return picked
            val at = wakeAt
            if (at == null) wake.receive() else withTimeoutOrNull(at - now) { wake.receive() }
        }
    }

    /** The store's candidates with this refresher's newer bookkeeping over them. Under lock. */
    private fun known(): Map<TitleKey, RefreshCandidate> = if (produced.isEmpty()) candidates else candidates.mapValues { (key, c) ->
        produced[key]?.let { r -> c.copy(fetchedAt = r.fetchedAt, staleAt = r.staleAt, retryAt = r.retryAt, failures = r.failures) } ?: c
    }

    /** [key]'s outcome: from a fetch already in flight, else from one made here. */
    private suspend fun join(key: TitleKey): Outcome? {
        while (true) {
            val (done, mine) = synchronized(lock) {
                inFlight[key]?.let { it to false } ?: (CompletableDeferred<Outcome?>().also { inFlight[key] = it } to true)
            }
            if (mine) {
                try {
                    permits.withPermit { fetch(key, done) }
                } catch (e: CancellationException) {
                    // Given up waiting for a permit, before fetch could take the title back: the loop's again.
                    synchronized(lock) { if (inFlight[key] === done) inFlight.remove(key) }
                    done.cancel(e)
                    wake.trySend(Unit)
                    throw e
                }
                return done.await()
            }
            try {
                return done.await()
            } catch (e: CancellationException) {
                // The loop's fetch was cancelled (the app left the front), not this caller: ask here.
                currentCoroutineContext().ensureActive()
            }
        }
    }

    /** Fetches [key], registered in flight with [done], and queues what it learnt. */
    private suspend fun fetch(key: TitleKey, done: CompletableDeferred<Outcome?>) {
        try {
            // A worker that started cancelled asks TMDB nothing.
            currentCoroutineContext().ensureActive()
            val outcome = attempt(key)
            outcome?.let(::enqueue)
            done.complete(outcome)
        } catch (e: CancellationException) {
            done.cancel(e)
            throw e
        } finally {
            synchronized(lock) { inFlight.remove(key) }
            wake.trySend(Unit)
        }
    }

    private suspend fun attempt(key: TitleKey): Outcome? {
        val (previous, asked) = synchronized(lock) { known()[key] to generation }
        return try {
            if (key.isShow) source.show(key.tmdbId).let { answered(key, it.value.toSummary(), null, it, asked) }
            else source.movie(key.tmdbId).let { answered(key, null, it.value.toSummary(), it, asked) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TmdbException) {
            when (val error = e.error) {
                is TmdbError.Network -> null.also { pause(RefreshStatus.Reason.Offline) }
                is TmdbError.RateLimited -> null.also { pause(RefreshStatus.Reason.RateLimited) }
                is TmdbError.NoKey -> null.also { stop(RefreshStatus.Reason.NoKey) }
                is TmdbError.InvalidKey -> null.also { stop(RefreshStatus.Reason.InvalidKey) }
                is TmdbError.NotFound -> failed(key, previous, asked, NOT_FOUND, Staleness.NOT_FOUND_RETRY)
                is TmdbError.Server -> failed(key, previous, asked, "Server", Staleness.retryAfter((previous?.failures ?: 0) + 1))
                is TmdbError.BadResponse -> failed(key, previous, asked, "BadResponse", Staleness.retryAfter((previous?.failures ?: 0) + 1))
                // A title's own request refused (400, 422): TMDB's fault or a change, so asked again later, as a failure.
                is TmdbError.BadRequest -> failed(key, previous, asked, "BadRequest", Staleness.retryAfter((previous?.failures ?: 0) + 1))
            }
        } catch (e: Exception) {
            log.warn("Couldn't refresh {} from TMDB", key, e)
            failed(key, previous, asked, "BadResponse", Staleness.retryAfter((previous?.failures ?: 0) + 1))
        }
    }

    /**
     * An answer: its facts, when TMDB gave them, when they are due again. One TMDB gave while it
     * couldn't be reached, or older than its policy, is kept but asked for again after a pause, and
     * holds every worker back for it; one a page [offered] is as old as the page has been open, and
     * is only due again soon. One straight from TMDB (no age) ends a pause for no network.
     */
    private fun answered(key: TitleKey, show: ShowSummary?, movie: MovieSummary?, aged: Aged<Any>, asked: Long, offered: Boolean = false): Outcome {
        val now = now()
        val fetchedAt = now - aged.age.toMillis()
        val zone = clock.zone
        val policy = show?.let { Staleness.policy(it, now, zone) } ?: Staleness.policy(movie!!, now, zone)
        val stale = aged.offline || aged.age > policy
        val refresh = if (stale && !offered) {
            val retryAt = pause(RefreshStatus.Reason.Offline)
            SummaryRefresh(type(key), key.tmdbId, fetchedAt = fetchedAt, staleAt = retryAt, retryAt = retryAt)
        } else {
            if (!stale) reachable(fromTmdb = aged.age.isZero)
            // Never due sooner than MIN_RECHECK: an offered answer past its policy is asked for again then.
            val staleAt = show?.let { Staleness.staleAt(it, fetchedAt, now, zone) } ?: Staleness.staleAt(movie!!, fetchedAt, now, zone)
            SummaryRefresh(type(key), key.tmdbId, fetchedAt = fetchedAt, staleAt = staleAt)
        }
        return Outcome(key, show, movie, refresh, asked)
    }

    /** A fresh answer: pauses start over. One [fromTmdb] itself, not its cache, shows it reachable: a pause for no network ends. */
    private fun reachable(fromTmdb: Boolean) {
        val resumed = synchronized(lock) {
            pauses = 0
            (fromTmdb && pauseReason == RefreshStatus.Reason.Offline && pausedUntil > now()).also { if (it) pausedUntil = 0 }
        }
        if (resumed) wake.trySend(Unit)
    }

    /** Charged to the title: asked again after [retryIn], the facts it had kept. */
    private fun failed(key: TitleKey, previous: RefreshCandidate?, asked: Long, error: String, retryIn: Duration): Outcome = Outcome(
        key, null, null,
        SummaryRefresh(
            type(key), key.tmdbId, fetchedAt = previous?.fetchedAt ?: 0, staleAt = previous?.staleAt ?: 0,
            retryAt = now() + retryIn.toMillis(), failures = (previous?.failures ?: 0) + 1, error = error,
        ),
        asked,
    )

    /** Holds every worker back; a pause while one is on doesn't count again. Returns when it ends. */
    private fun pause(reason: RefreshStatus.Reason): Long = synchronized(lock) {
        val now = now()
        if (pausedUntil <= now) {
            pauses++
            pausedUntil = now + Staleness.pause(reason, pauses).toMillis()
            pauseReason = reason
            _status.value = RefreshStatus.Paused(reason, pausedUntil)
        }
        pausedUntil
    }.also { wake.trySend(Unit) }

    private fun stop(reason: RefreshStatus.Reason) {
        synchronized(lock) {
            stopped = reason
            if (running) _status.value = RefreshStatus.Paused(reason, null)
        }
        wake.trySend(Unit)
    }

    /** What [o] is written as: asked for before the TMDB language last changed, it is due again at once. Under lock. */
    private fun stored(o: Outcome): SummaryRefresh = if (o.asked == generation) o.refresh else o.refresh.copy(staleAt = 0, retryAt = 0)

    private fun enqueue(outcome: Outcome) {
        synchronized(lock) { produced[outcome.key] = stored(outcome) }
        val full = synchronized(pending) {
            pending += outcome
            if (pending.size < flushAt && flushTimer?.isActive != true) {
                flushTimer = scope.launch(dispatcher) {
                    delay(flushEveryMs)
                    flush(all = true)
                }
            }
            pending.size >= flushAt
        }
        if (full) scope.launch(dispatcher) { flush(all = false) }
    }

    /** Writes what is queued, [flushAt] titles at a time; not [all]: whole batches only, the rest waits for the timer. */
    private suspend fun flush(all: Boolean) = writing.withLock {
        while (true) {
            val batch = synchronized(pending) {
                if (pending.isEmpty() || !all && pending.size < flushAt) return@withLock
                pending.take(flushAt).also { pending.subList(0, it.size).clear() }
            }
            val refresh = synchronized(lock) { batch.map(::stored) }
            try {
                // A write cancelled halfway would lose the batch: Room rolls it back.
                withContext(NonCancellable) { store.save(batch.mapNotNull { it.show }, batch.mapNotNull { it.movie }, refresh) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Couldn't store what TMDB said of {} titles", batch.size, e)
                unwritten(batch, refresh)
            } finally {
                // Whoever waits for these (refreshNow) goes on, written or not.
                batch.forEach { it.written.complete(Unit) }
            }
        }
    }

    /**
     * A batch the store couldn't take (a full disk): its titles count as asked no more, due again after
     * [Staleness.WRITE_RETRY] (not at once: a disk still full would be asked to take them in a loop).
     */
    private fun unwritten(batch: List<Outcome>, refresh: List<SummaryRefresh>) {
        val retryAt = now() + Staleness.WRITE_RETRY.toMillis()
        synchronized(lock) {
            // Those this refresher has no newer word of.
            batch.filterIndexed { i, o -> produced[o.key] == refresh[i] }.forEach { o ->
                // What the store has (nothing written changed it), held back until then.
                val c = candidates[o.key]
                produced[o.key] = SummaryRefresh(type(o.key), o.key.tmdbId, fetchedAt = c?.fetchedAt ?: 0, staleAt = c?.staleAt ?: 0, retryAt = retryAt, failures = c?.failures ?: 0)
            }
        }
        wake.trySend(Unit)
    }

    private fun type(key: TitleKey) = if (key.isShow) SummaryRefresh.SHOW else SummaryRefresh.MOVIE

    private companion object {
        const val NOT_FOUND = "NotFound"
        /** Facts of titles no longer collected stay this long (Undo, adding again), then go. */
        val PRUNE_AFTER: Duration = Duration.ofDays(30)
    }
}
