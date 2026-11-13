package io.skjaere.usenet.core

import io.skjaere.nzbstreamer.config.SegmentCacheConfig
import io.skjaere.nzbstreamer.stream.SegmentCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What the video cache holds and may hold, for Settings › Cache and its row on the settings hub. */
data class VideoCacheUsage(
    /** "Keep watched video on this device" is on. */
    val on: Boolean,
    val usedBytes: Long,
    /** What it may hold: the limit chosen, or what fits on the device ([videoCacheLimit]); 0 while off or without room. */
    val limitBytes: Long,
    val chosenBytes: Long,
    /** Free on the cache's volume when last measured. */
    val freeBytes: Long,
) {
    /** On, and held to less than the limit chosen (or to nothing) to leave the device [MIN_FREE_BYTES] free. */
    val short: Boolean get() = on && limitBytes < chosenBytes
}

/** What the video cache leaves free on its volume. */
const val MIN_FREE_BYTES = 1_000_000_000L

/** Less room than this and the video cache keeps nothing: every segment would be evicted as soon as it was written. */
const val MIN_CACHE_BYTES = 100_000_000L

/**
 * What the video cache may hold: the [chosen] limit, or what fits in the [used] bytes it holds and the
 * [free] bytes on its volume, leaving [MIN_FREE_BYTES] free; 0, nothing kept, when that is under [MIN_CACHE_BYTES].
 */
fun videoCacheLimit(chosen: Long, used: Long, free: Long): Long =
    minOf(chosen, used + free - MIN_FREE_BYTES).takeIf { it >= MIN_CACHE_BYTES } ?: 0

/**
 * The process's one segment cache: the video segments played, kept in [dir] ("Keep watched video on
 * this device"), up to the limit chosen or what fits on the device ([videoCacheLimit]). Every streamer
 * the engine builds is lent the same instance ([forStreamer]) without its ownership (closeSegmentCache
 * = false), so a rebuild on a settings change neither closes it nor opens another: two instances on
 * one directory would each take the other's files for their own.
 *
 * Opening it walks [dir], and fitting it to the room stats its files (the system may delete them
 * when storage runs low), so everything that opens, fits, clears or closes it runs on [ioDispatcher],
 * one at a time, and whole, its caller cancelled or not. It is saved by [save], which the app calls
 * as it goes to the background (Android kills processes without warning), and closed only when it is
 * turned off or has no room, cleared first, so never twice; a new instance opens only after the last
 * one's close. The streamers lent a closed one fetch everything again, and [opened] is called when a
 * new one opens while they may hold an older one, or none: the engine builds its streamer again when idle.
 */
class VideoCache(
    private val dir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val freeSpace: () -> Long = { (dir.parentFile ?: dir).usableSpace },
    private val opened: suspend () -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(VideoCache::class.java)
    private val mutex = Mutex()

    @Volatile
    private var cache: SegmentCache? = null

    /** The settings applied last and what they came to; null until the first [apply]. */
    private val state = MutableStateFlow<State?>(null)

    private data class State(val on: Boolean, val chosen: Long, val limit: Long, val free: Long)

    /** The cache to lend a streamer: null while off, without room, or before the settings are applied. Cheap. */
    fun forStreamer(): SegmentCache? = cache

    /** The usage now; null until the settings are applied. Takes no lock and reads no file: cheap on the main thread. */
    fun usageNow(): VideoCacheUsage? = state.value?.let { s ->
        VideoCacheUsage(s.on, cache?.stats()?.totalBytes ?: 0, s.limit, s.chosen, s.free)
    }

    /** [usageNow] while collected: at once on a change of the settings or the room, and every [period] for what plays meanwhile. */
    fun usage(period: Duration = 1.seconds): Flow<VideoCacheUsage?> {
        val ticks = flow {
            while (true) {
                emit(Unit)
                delay(period)
            }
        }
        return combine(state, ticks) { _, _ -> usageNow() }.distinctUntilChanged()
    }

    /** Applies the settings: [keep] on opens it, or holds it to [chosenBytes] (or what fits); off clears and closes it. */
    suspend fun apply(keep: Boolean, chosenBytes: Long) = exclusively { applyLocked(keep, chosenBytes) }

    /** Measures the room again and fits the cache to it, as the Cache screen opens. */
    suspend fun refresh() = exclusively {
        val s = state.value ?: return@exclusively
        applyLocked(s.on, s.chosen)
    }

    /** Fits the cache to the room now and writes its index, so a process killed later loses nothing: as the app goes to the background. */
    suspend fun save() = exclusively {
        val s = state.value ?: return@exclusively
        applyLocked(s.on, s.chosen)
        cache?.save()
    }

    /** Empties it. A play in progress goes on, fetching from the provider what it reads again. */
    suspend fun clear() = exclusively { cache?.clear() }

    /**
     * Runs [block] on [ioDispatcher], after any other's, and whole: one cut short by its caller's
     * cancellation (the Cache screen left as it opens) would leave an instance dropped but still open
     * and lent to the streamer, so that the next opened beside it, or one opened but never passed on.
     */
    private suspend fun exclusively(block: suspend () -> Unit) = mutex.withLock {
        withContext(ioDispatcher + NonCancellable) { block() }
    }

    private suspend fun applyLocked(keep: Boolean, chosen: Long) {
        val open = cache
        if (!keep) {
            discard(open)
            state.value = State(on = false, chosen = chosen, limit = 0, free = freeSpace())
            return
        }
        // The system may have deleted files since (storage running low, its Clear cache): what it
        // holds is room it may keep, so it counts only what is on disk.
        open?.reconcile()
        val current = open ?: openCache(chosen)
        val free = freeSpace()
        val limit = current?.let { videoCacheLimit(chosen, it.stats().totalBytes, free) } ?: 0
        if (current == null || limit == 0L) {
            if (current != null) log.info("No room for the video cache: {} bytes free", free)
            discard(current)
            state.value = State(on = true, chosen = chosen, limit = 0, free = free)
            return
        }
        if (current.stats().maxBytes != limit) current.resize(limit)
        state.value = State(on = true, chosen = chosen, limit = limit, free = free)
        if (open == null) {
            cache = current
            opened()
        }
    }

    /** A new instance on [dir], or null when it can't be opened (the directory can't be made). */
    private fun openCache(maxBytes: Long): SegmentCache? = try {
        SegmentCache(SegmentCacheConfig(dir.toPath(), maxBytes))
    } catch (e: IOException) {
        log.warn("Couldn't open the video cache: {}", e.message)
        null
    }

    /** Clears and closes [open], and deletes whatever is left in [dir] (an earlier process's files, the index). */
    private suspend fun discard(open: SegmentCache?) {
        if (open != null) {
            cache = null
            try {
                open.clear()
            } finally {
                open.close()
            }
        }
        if (dir.exists() && !dir.deleteRecursively()) log.warn("Couldn't delete all of the video cache")
    }
}
