package io.skjaere.usenet.newznab

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext

/** Caps per indexer, kept for [ttl]. A failed fetch is not cached. */
class CapsCache(
    private val client: NewznabClient,
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = Duration.ofHours(24),
) {
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Pair<Instant, IndexerCaps>>()

    suspend fun get(indexer: IndexerConfig): IndexerCaps = get(indexer) {}

    /** [get], calling [asking] just before it asks [indexer] for them: not for caps it still keeps. */
    internal suspend fun get(indexer: IndexerConfig, asking: () -> Unit): IndexerCaps {
        val key = indexer.cacheKey()
        mutex.withLock { entries[key] }?.takeIf { (at, _) -> at.plus(ttl).isAfter(clock.instant()) }?.let { return it.second }
        asking()
        val caps = client.caps(indexer)
        mutex.withLock { entries[key] = clock.instant() to caps }
        return caps
    }

    suspend fun invalidate(indexer: IndexerConfig) = mutex.withLock { entries.remove(indexer.cacheKey()) }

    /** [indexer]'s caps while they are kept, without asking for them; null once they would have to be asked for. */
    internal suspend fun kept(indexer: IndexerConfig): IndexerCaps? =
        mutex.withLock { entries[indexer.cacheKey()] }?.takeIf { (at, _) -> at.plus(ttl).isAfter(clock.instant()) }?.second

    // A changed URL, key or User-Agent must not reuse the old answer (L553).
    private fun IndexerConfig.cacheKey() = "$id|$baseUrl|${apiKey.hashCode()}|${userAgent.trim()}"
}

sealed interface IndexerOutcome {
    val indexer: IndexerConfig

    /**
     * Whether this search sent [indexer] a request of its own for it (L630): its caps or its query asked anew, whether
     * that answered, failed or ran out of time. Not for an answer kept from an earlier request, one already on its way for
     * the same search, or an indexer kept out by its limit. A plugin's soft cap counts these.
     */
    val requested: Boolean get() = false

    data class Results(
        override val indexer: IndexerConfig,
        val page: SearchPage,
        val query: SearchQuery,
        override val requested: Boolean = false,
    ) : IndexerOutcome

    data class Failed(override val indexer: IndexerConfig, val error: NewznabError, override val requested: Boolean = false) : IndexerOutcome
    /** The indexer can't run this kind of search (from its caps). */
    data class Skipped(override val indexer: IndexerConfig, val reason: String) : IndexerOutcome

    /**
     * A torrent indexer answered ([found], for [query]), and its torrents are being checked against the debrid service's
     * cache (L199): [waiting] of them, none shown yet. The indexer has answered; its torrents come in [Checked]s.
     */
    data class Checking(
        override val indexer: IndexerConfig,
        val query: SearchQuery,
        val found: SearchPage,
        val waiting: Int,
        override val requested: Boolean = false,
    ) : IndexerOutcome

    /**
     * Some of a torrent indexer's torrents settled ([items], each with its [NewznabItem.availability]), never one twice;
     * [waiting] are left, the last [Checked] of the answer having none.
     */
    data class Checked(override val indexer: IndexerConfig, val items: List<NewznabItem>, val waiting: Int) : IndexerOutcome
}

/** A search of the user's indexers, each one's outcome as it comes: [MultiIndexerSearch], or one that checks torrents as they come. */
interface ReleaseSearch {
    /**
     * [plan] picks the query for each indexer from its caps; returning null skips that indexer. [fresh] asks every indexer
     * again rather than reusing a recent answer.
     */
    fun search(indexers: List<IndexerConfig>, fresh: Boolean = false, plan: (IndexerCaps) -> SearchQuery?): Flow<IndexerOutcome>
}

/**
 * Runs one search on every enabled indexer in parallel and emits each indexer's outcome as it
 * arrives, so fast indexers show results while slow ones are still working. One indexer
 * failing or timing out never affects the others. Safe to collect on the main thread: the
 * client parses answers off it, and what's left here is bookkeeping. A search dropped while an
 * indexer is still answering stops at once, but leaves that request to finish into the cache.
 * A torrent indexer is given [torznabTimeoutMs] where a Usenet one is given [perIndexerTimeoutMs] (L170), and its query
 * names the categories its caps list ([torznabCategories]). An indexer that answered its limit with a Retry-After is
 * kept out until it ends (L169).
 */
class MultiIndexerSearch(
    private val client: NewznabClient,
    private val caps: CapsCache,
    private val perIndexerTimeoutMs: Long = IndexerKind.Newznab.timeoutMs,
    private val clock: Clock = Clock.systemUTC(),
    /** Answers are reused this long, so going back and forth doesn't spend API calls. */
    private val resultTtl: Duration = Duration.ofMinutes(15),
    /** The requests to the indexers' parent, not the searches waiting for them: a request outlives a search dropped midway. */
    private val requests: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val torznabTimeoutMs: Long = IndexerKind.Torznab.timeoutMs,
) : ReleaseSearch {
    /** Guards [results], [sent] and [limited]. */
    private val lock = Any()
    private val results = HashMap<String, Pair<Instant, SearchPage>>()
    /** The requests on their way, by the same key as [results]. */
    private val sent = HashMap<String, Deferred<SearchPage>>()
    /** Until when each indexer is kept out, by [limitKey]: it answered its limit with a Retry-After. */
    private val limited = HashMap<String, Instant>()

    /**
     * [plan] picks the query for each indexer from its caps (e.g. `tvdbid` where supported,
     * a text query elsewhere); returning null skips that indexer.
     */
    override fun search(indexers: List<IndexerConfig>, fresh: Boolean, plan: (IndexerCaps) -> SearchQuery?): Flow<IndexerOutcome> = channelFlow {
        indexers.filter { it.enabled }.forEach { indexer ->
            launch {
                val timeoutMs = timeoutOf(indexer)
                val asked = Asked()
                // A request sent before the time ran out still reached the indexer.
                val outcome = withTimeoutOrNull(timeoutMs) { searchOne(indexer, fresh, plan, asked) }
                    ?: IndexerOutcome.Failed(indexer, NewznabError.Timeout(noAnswerWithin(timeoutMs)), requested = asked.value)
                send(outcome)
            }
        }
    }

    private fun timeoutOf(indexer: IndexerConfig): Long = if (indexer.kind == IndexerKind.Torznab) torznabTimeoutMs else perIndexerTimeoutMs

    /** [indexer]'s outcome; [asked] says whether a request of this search's own went to it, caps or query, as soon as one does. */
    private suspend fun searchOne(indexer: IndexerConfig, fresh: Boolean, plan: (IndexerCaps) -> SearchQuery?, asked: Asked): IndexerOutcome = try {
        val left = stillLimited(indexer)
        // Kept out, it isn't asked: every request meanwhile would be refused, and count against it. What it answered
        // before and is still kept is given as ever, as no request is made for it; anything else, Search indexers'
        // fresh search too, is its limit.
        val limited = left?.let { IndexerOutcome.Failed(indexer, NewznabError.ApiLimitReached(API_LIMIT_REACHED, it)) }
        val found = if (left == null) caps.get(indexer) { asked.value = true } else caps.kept(indexer)
        val query = found?.let(plan)?.let { query ->
            if (indexer.kind == IndexerKind.Torznab && query.categories.isEmpty()) query.withCategories(torznabCategories(found, query)) else query
        }
        when {
            found == null -> limited!!
            query == null -> IndexerOutcome.Skipped(indexer, "Doesn't support this search")
            else -> {
                // Failures are never cached; a changed URL, key or User-Agent is a different entry (L553).
                val key = "${limitKey(indexer)}|${indexer.userAgent.trim()}|$query"
                if (limited == null) {
                    IndexerOutcome.Results(indexer, page(indexer, key, fresh, asked) { client.search(indexer, query) }, query, asked.value)
                } else {
                    kept(key).takeIf { !fresh }?.let { IndexerOutcome.Results(indexer, it, query) } ?: limited
                }
            }
        }
    } catch (e: NewznabException) {
        holdOff(indexer, e.error)
        // A failure reaches the indexer and is never kept: a search that sent the request asked it.
        IndexerOutcome.Failed(indexer, e.error, asked.value)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Anything else is still this indexer's failure alone; it must not fail the whole search.
        IndexerOutcome.Failed(indexer, NewznabError.BadResponse(NewznabClient.safeMessage(e, indexer)), asked.value)
    }

    /** Whether one indexer's part of a search sent it a request of its own; set as the request is made, read once it ends. */
    private class Asked {
        @Volatile
        var value = false
    }


    /** How long [indexer] is still kept out; null once it may be asked. */
    private fun stillLimited(indexer: IndexerConfig): Duration? = synchronized(lock) {
        val key = limitKey(indexer)
        val left = limited[key]?.let { until -> Duration.between(clock.instant(), until) }
        if (left != null && (left.isZero || left.isNegative)) limited.remove(key)
        left?.takeIf { !it.isZero && !it.isNegative }
    }

    /** Keeps [indexer] out for its limit's Retry-After, if [error] is one that came with it. */
    private fun holdOff(indexer: IndexerConfig, error: NewznabError) {
        val wait = (error as? NewznabError.ApiLimitReached)?.retryAfter ?: return
        synchronized(lock) { limited[limitKey(indexer)] = clock.instant().plus(wait) }
    }

    /** [key]'s answer while it is kept, without asking. */
    private fun kept(key: String): SearchPage? = synchronized(lock) {
        results[key]?.takeIf { (at, _) -> at.plus(resultTtl).isAfter(clock.instant()) }?.second
    }

    /**
     * [key]'s answer: cached, else from the request already on its way for it, else from a new one ([ask]), which [asked]
     * then says; [fresh] always asks anew. The indexer counts a request against its API limit as it arrives, so one whose
     * search was dropped (typing on, Search indexers pressed over the preview's own search) goes on into the cache, and the
     * same search a moment later waits for it instead of asking again; and its limit, if that is what it answered, keeps
     * [indexer] out as a waited-for one's does.
     */
    private suspend fun page(indexer: IndexerConfig, key: String, fresh: Boolean, asked: Asked, ask: suspend () -> SearchPage): SearchPage {
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor]
        val timeoutMs = timeoutOf(indexer)
        val request = synchronized(lock) {
            if (!fresh) {
                kept(key)?.let { return it }
                sent[key]?.let { return@synchronized it }
            }
            // On the searcher's dispatcher, where the requests always ran.
            requests.async(dispatcher ?: EmptyCoroutineContext, start = CoroutineStart.LAZY) {
                // Its own timeout: nobody may be waiting for it any more.
                val page = try {
                    withTimeoutOrNull(timeoutMs) { ask() } ?: throw NewznabException(NewznabError.Timeout(noAnswerWithin(timeoutMs)))
                } catch (e: NewznabException) {
                    holdOff(indexer, e.error)
                    throw e
                }
                synchronized(lock) { results[key] = clock.instant() to page }
                page
            }.also { started ->
                sent[key] = started
                started.invokeOnCompletion { synchronized(lock) { sent.remove(key, started) } }
                asked.value = true
            }
        }
        request.start()
        return request.await()
    }

    companion object {
        /**
         * How an indexer's limit is kept: an indexer is one by its address and key too, so another account's limit isn't this
         * one's, but never by its User-Agent, as the limit is the account's whatever asks (L553). A download's walk keeps its
         * own memory of limits by the same key.
         */
        fun limitKey(indexer: IndexerConfig): String = "${indexer.id}|${indexer.baseUrl}|${indexer.apiKey.hashCode()}"
    }
}

/** What an indexer that answered its limit shows (L169), whatever its own words: its pill's "⚠ Quarry · API limit reached". */
const val API_LIMIT_REACHED = "API limit reached"

/** Today's words for an indexer that didn't answer within [timeoutMs]: "No answer within 25 s". */
internal fun noAnswerWithin(timeoutMs: Long) = "No answer within ${timeoutMs / 1000} s"
