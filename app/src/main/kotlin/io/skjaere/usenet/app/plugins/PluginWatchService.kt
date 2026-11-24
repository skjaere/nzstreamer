package io.skjaere.usenet.app.plugins

import io.skjaere.usenet.data.ItemMark
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.plugin.PluginKeys
import io.skjaere.usenet.plugin.PluginWatch
import io.skjaere.usenet.plugin.WatchInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Instant

/**
 * What the user watched of plugins' items, as the library keeps it (L594, L626): a plugin's keys are its own, stored under
 * item:<plugin id>:<key> ([WatchKey.item]), so two plugins' keys never meet and a plugin reads and marks only its own. A
 * key outside the API's rule ([PluginKeys.validKey]) is refused before anything is read or written, with an
 * IllegalArgumentException naming the rule, never the key. [states] reads the library's live states of the keys it is
 * given, as they change ([io.skjaere.usenet.data.LibraryRepository.observeStates]); [marking] marks items as Mark watched
 * does a film ([io.skjaere.usenet.data.LibraryRepository.markItems]).
 */
class PluginWatchService(
    private val states: (keys: List<String>) -> Flow<List<WatchState>>,
    private val marking: suspend (items: List<ItemMark>, watched: Boolean) -> Unit,
) {
    /** Plugin [id]'s watch state. */
    fun of(id: String): PluginWatch = Watch(id)

    private inner class Watch(private val id: String) : PluginWatch {
        override fun observe(keys: Collection<String>): Flow<Map<String, WatchInfo>> {
            // The library's key of each of the plugin's, and back.
            val plugin = keys.associateBy(::stored)
            if (plugin.isEmpty()) return flowOf(emptyMap())
            return states(plugin.keys.toList())
                .map { rows -> rows.filter { it.key in plugin } }
                .distinctUntilChanged()
                .map { rows -> rows.associate { plugin.getValue(it.key) to info(it) } }
        }

        override suspend fun mark(items: List<Pair<String, String>>, watched: Boolean) {
            val marks = items.map { (key, title) -> ItemMark(stored(key), title) }
            if (marks.isNotEmpty()) marking(marks, watched)
        }

        private fun stored(key: String): String {
            require(PluginKeys.validKey(key)) { "An item's key is 1 to 200 letters, digits, dots, underscores, colons and dashes" }
            return WatchKey.item(id, key)
        }
    }

    private companion object {
        /** [row] as a plugin reads it: when it last played is unknown for an item only marked. */
        fun info(row: WatchState) = WatchInfo(row.positionMs, row.durationMs, row.watched, row.lastPlayedAt.takeIf { it > 0 }?.let(Instant::ofEpochMilli))
    }
}
