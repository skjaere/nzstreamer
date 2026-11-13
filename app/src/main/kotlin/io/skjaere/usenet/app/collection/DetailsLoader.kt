package io.skjaere.usenet.app.collection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Details by key (Home's seasons, and titles in progress that aren't collected), fetched with
 * [fetch] [concurrency] at a time. An answer is kept for [maxAge] and then fetched again
 * (TmdbClient's own cache decides whether that needs the network): on the next [load], and every
 * [maxAge] while a load is collected, so Home left open sees new episodes and air dates too.
 */
class DetailsLoader<K : Any, V : Any>(
    private val fetch: suspend (K) -> V,
    private val clock: Clock = Clock.systemUTC(),
    private val maxAge: Duration = Duration.ofHours(1),
    private val concurrency: Int = 4,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private class Entry<V>(val details: V?, val fetchedAt: Instant)

    private val entries = ConcurrentHashMap<K, Entry<V>>()

    /**
     * Details for [ids], first what is already known and then again as each answer arrives: a key
     * missing from the map is still loading, null means it couldn't be loaded.
     */
    fun load(ids: List<K>): Flow<Map<K, V?>> = channelFlow {
        val permits = Semaphore(concurrency)
        // Answers arrive on several threads: reading the entries and sending what was read happen
        // under one lock, so a read made before another answer was stored can't go out after it.
        val sending = Mutex()
        while (true) {
            send(loaded(ids))
            coroutineScope {
                ids.filter { due(it) }.forEach { id ->
                    launch {
                        permits.withPermit { refresh(id) }
                        sending.withLock { send(loaded(ids)) }
                    }
                }
            }
            delay(maxAge.toMillis())
        }
    }.flowOn(dispatcher)

    /** Never fetched, failed, or older than [maxAge]. */
    private fun due(id: K): Boolean =
        entries[id]?.let { it.details == null || Duration.between(it.fetchedAt, clock.instant()) >= maxAge } ?: true

    /** A failed fetch keeps the details already there, which stay due. */
    private suspend fun refresh(id: K) {
        val details = try {
            fetch(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (details != null || entries[id]?.details == null) entries[id] = Entry(details, clock.instant())
    }

    private fun loaded(ids: List<K>): Map<K, V?> =
        ids.mapNotNull { id -> entries[id]?.let { id to it.details } }.toMap()
}
