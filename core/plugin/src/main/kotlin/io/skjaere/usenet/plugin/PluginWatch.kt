package io.skjaere.usenet.plugin

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * What the user watched of the plugin's items, as the host keeps it: under item:<plugin id>:<key> with the item's stored
 * title, in Up next while one is in progress. Keys are the plugin's own ([PlayItem.key]); one outside [PluginKeys.validKey]
 * is refused with an [IllegalArgumentException]. An item stays as it is when it leaves the plugin's lists.
 */
interface PluginWatch {
    /**
     * The watch state of each of [keys] the host has any for, by the plugin's key; a key with none is left out. A new map
     * whenever one of them changes: a play's progress, a mark here or in Up next.
     */
    fun observe(keys: Collection<String>): Flow<Map<String, WatchInfo>>

    /**
     * Marks [items], each its key and the title to store for it, [watched] or not, as Mark watched does a film: a watched
     * item leaves Up next. Returns once they are stored.
     */
    suspend fun mark(items: List<Pair<String, String>>, watched: Boolean)
}

/**
 * An item's watch state: where it stopped ([positionMs] of [durationMs]; 0 once watched), whether it is [watched], and
 * when it was last played ([lastPlayedAt]; null when it was only marked).
 */
class WatchInfo(val positionMs: Long, val durationMs: Long, val watched: Boolean, val lastPlayedAt: Instant?)
