package io.skjaere.usenet.metadata

import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Raw responses of the metadata services (TMDB, TheIntroDB) by request key (path + query, never a key). Blocking. */
interface MetadataCache {
    /** The response stored under [key] if it is at most [maxAge] old, with its age. */
    fun get(key: String, maxAge: Duration): Entry?
    fun put(key: String, body: String)
    fun clear()
    fun sizeBytes(): Long

    class Entry(val body: String, val age: Duration)

    companion object {
        /** TMDB's terms let an answer be cached six months at most: none is kept, or served, longer. */
        val MAX_AGE: Duration = Duration.ofDays(180)
    }
}

/**
 * One file per response under [dir]; age from the file's modification time. Best effort: [dir]
 * can be deleted while the app runs (Clear cache, the system trimming caches) or the disk can
 * be full, and a response that can't be written just isn't cached.
 *
 * Entries not written for [MetadataCache.MAX_AGE] are removed on the first read or write after the cache is
 * opened (on the caller's thread: opening touches no files), then at most daily; none older is served meanwhile.
 */
class FileMetadataCache(private val dir: File, private val clock: Clock = Clock.systemUTC()) : MetadataCache {
    private val log = LoggerFactory.getLogger(FileMetadataCache::class.java)

    /** When entries too old were last removed; null until the first read or write. */
    @Volatile
    private var swept: Instant? = null

    override fun get(key: String, maxAge: Duration): MetadataCache.Entry? {
        sweepIfDue()
        val file = fileFor(key).takeIf { it.isFile } ?: return null
        val age = Duration.between(Instant.ofEpochMilli(file.lastModified()), clock.instant())
        return if (age <= minOf(maxAge, MetadataCache.MAX_AGE)) runCatching { file.readText() }.getOrNull()?.let { MetadataCache.Entry(it, age) } else null
    }

    override fun put(key: String, body: String) {
        sweepIfDue()
        val file = fileFor(key)
        val tmp = File(dir, file.name + ".tmp")
        try {
            dir.mkdirs()
            tmp.writeText(body)
            tmp.setLastModified(clock.millis())
            if (!tmp.renameTo(file)) throw IOException("Couldn't rename $tmp to $file")
        } catch (e: IOException) {
            tmp.delete()
            log.debug("Metadata response not cached: {}", e.message)
        }
    }

    override fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    override fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0

    /** Removes the entries (and any temporary file left) not written for [MetadataCache.MAX_AGE], unless done in the last day. */
    private fun sweepIfDue() {
        val now = clock.instant()
        val last = swept
        // A clock set back since starts the day again.
        if (last != null && !now.isBefore(last) && Duration.between(last, now) < SWEEP_EVERY) return
        swept = now
        val oldest = now.minus(MetadataCache.MAX_AGE).toEpochMilli()
        val removed = dir.listFiles()?.count { it.lastModified() < oldest && it.delete() } ?: 0
        if (removed > 0) log.debug("Removed {} metadata responses older than {} days", removed, MetadataCache.MAX_AGE.toDays())
    }

    private fun fileFor(key: String) = File(dir, sha(key) + ".json")

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(40)

    private companion object {
        val SWEEP_EVERY: Duration = Duration.ofDays(1)
    }
}

class InMemoryMetadataCache(private val clock: Clock = Clock.systemUTC()) : MetadataCache {
    private val entries = java.util.concurrent.ConcurrentHashMap<String, Pair<Instant, String>>()
    override fun get(key: String, maxAge: Duration) =
        entries[key]?.let { (at, body) -> MetadataCache.Entry(body, Duration.between(at, clock.instant())) }?.takeIf { it.age <= maxAge }
    override fun put(key: String, body: String) {
        entries[key] = clock.instant() to body
    }
    override fun clear() = entries.clear()
    override fun sizeBytes() = entries.values.sumOf { it.second.length.toLong() }
}
