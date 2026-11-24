package io.skjaere.usenet.app.plugins

import io.skjaere.usenet.app.search.Answers
import io.skjaere.usenet.app.search.IndexerStatus
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.IndexerOutcome
import io.skjaere.usenet.newznab.NewznabError
import io.skjaere.usenet.newznab.ReleaseSearch
import io.skjaere.usenet.newznab.SearchQuery
import io.skjaere.usenet.plugin.Found
import io.skjaere.usenet.plugin.IndexerLine
import io.skjaere.usenet.plugin.PluginSearch
import io.skjaere.usenet.plugin.ReleaseHandle
import io.skjaere.usenet.plugin.ReleaseInfo
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.Severity
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGrouper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * The user's indexers searched for plugins (L630, L605): the app's own search ([search], with the debrid services' check),
 * every indexer asked for the words as text with no category, each with its own User-Agent, limits and 15-minute cache;
 * the answers taken as Search takes typed words (with no title) and ranked as Search ranks them, over the runtime the
 * plugin gives in place of [ranking]'s. A plugin gets words and opaque handles, nothing else:
 * - an indexer by the name the user typed for it, unless that is its address; else "Indexer <n>", its place in Settings;
 * - a failure by its kind, in the host's words: the indexer's own message can hold its address and the request;
 * - a release by its name, size, date, indexer and whether it plays here, with a [ReleaseHandle] that only this
 *   service maps back to it ([ranked]): its key, address, guid and link stay in the host. Each plugin's table keeps its
 *   latest [maxHandles].
 * A soft cap guards the user's indexer limits: [CAP] searches a minute ([CAP_WINDOW_MS], on [now]) per plugin that asked an
 * indexer anew ([IndexerOutcome.requested]: an answer from the cache never counts, a failed request does). Past it a
 * search is answered at once, every indexer paused, and the pause is logged once a minute by the plugin's id alone.
 */
class PluginSearchService(
    private val search: () -> ReleaseSearch,
    private val indexers: suspend () -> List<IndexerConfig>,
    private val ranking: suspend () -> RankingContext,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val maxHandles: Int = MAX_HANDLES,
    private val work: CoroutineDispatcher = Dispatchers.Default,
    private val grouper: ResultGrouper = ResultGrouper(),
) {
    private val plugins = ConcurrentHashMap<String, PluginState>()

    /** Plugin [id]'s search, its cap and handles its own; [name], the plugin's, says whose searches are paused. */
    fun of(id: String, name: String): PluginSearch = Searcher(plugins.computeIfAbsent(id) { PluginState(it, HandleTable(maxHandles)) }, name)

    /**
     * The releases plugin [id]'s [handles] stand for, in their order: one its table no longer keeps, another plugin's and
     * one the plugin made itself are left out.
     */
    fun ranked(id: String, handles: List<ReleaseHandle>): List<RankedItem> = plugins[id]?.table?.items(handles).orEmpty()

    private inner class Searcher(private val plugin: PluginState, private val name: String) : PluginSearch {
        override fun search(words: String, runtimeMinutes: Int?, fresh: Boolean): Flow<Found> = flow {
            val started = now()
            // Counted from its start, the cap checked in the same step, before anything waits: searches begun together
            // (one per row of a list) each find it as those before them left it. A search dropped before an indexer
            // answered may have asked one all the same, and stays counted; one that ends with every answer from the cache,
            // or asking none, didn't, and no longer counts.
            val counted = plugin.begin(started)
            var requested = false
            try {
                val asked = indexers()
                val on = asked.filter { it.enabled }
                val names = names(asked)
                if (!counted) {
                    emit(Found(on.map { IndexerLine(names.getValue(it.id), IndexerLine.State.Limited, 0, paused(name)) }, emptyList()))
                    return@flow
                }
                if (on.isEmpty()) {
                    plugin.uncount(started)
                    emit(Found(emptyList(), emptyList()))
                    return@flow
                }
                val context = ranking().copy(runtimeMinutes = runtimeMinutes)
                val answers = Answers(null)
                val failures = HashMap<String, NewznabError>()
                // A release keeps its handle as more indexers answer, its ranking brought up to date.
                val handles = HashMap<String, Handle>()
                search().search(asked, fresh) { SearchQuery.Text(words) }.collect { outcome ->
                    requested = requested || outcome.requested
                    if (outcome is IndexerOutcome.Failed) failures[outcome.indexer.id] = outcome.error
                    if (answers.take(outcome) == null) return@collect
                    val releases = grouper.ranked(answers.accepted, context, asked.map { it.id }).map { ranked ->
                        val handle = handles[ranked.key]?.also { plugin.table.update(it, ranked) } ?: plugin.table.add(ranked).also { handles[ranked.key] = it }
                        ranked.info(names, handle)
                    }
                    val statuses = answers.statuses.associateBy { it.id }
                    emit(Found(on.map { line(names.getValue(it.id), statuses[it.id], failures[it.id]) }, releases))
                }
            } catch (e: Throwable) {
                // The host's own failure before any indexer was asked counts nothing; a search dropped meanwhile stays counted.
                if (counted && e !is CancellationException && !requested) plugin.uncount(started)
                throw e
            }
            if (counted && !requested) plugin.uncount(started)
        }.flowOn(work).catch { e ->
            if (e !is Exception) throw e
            // The host's own failure, never the plugin's: its search ends with what it found, and the plugin goes on.
            log.warn("Plugin {}'s search failed: {}", plugin.id, e.javaClass.name)
        }
    }

    /** One plugin's cap and its handles. */
    private class PluginState(val id: String, val table: HandleTable) {
        /** When each search that asked an indexer anew began, and each still under way. */
        private val counted = mutableListOf<Long>()
        private var loggedAt: Long? = null

        /**
         * A search beginning [at], in one step: counted, unless [CAP] began in the minute before it, when it is paused
         * (false), logged once a minute.
         */
        @Synchronized
        fun begin(at: Long): Boolean {
            counted.removeAll { it <= at - CAP_WINDOW_MS }
            if (counted.size < CAP) {
                counted += at
                return true
            }
            if (loggedAt.let { it == null || at - it >= CAP_WINDOW_MS }) {
                loggedAt = at
                log.warn("Plugin {}: searches paused, {} in the last minute asked the indexers anew", id, counted.size)
            }
            return false
        }

        /** A search begun [at] that asked no indexer anew: every answer came from the cache. */
        @Synchronized
        fun uncount(at: Long) {
            counted.remove(at)
        }
    }

    /** One plugin's handles and the releases they stand for, the latest [max] kept. */
    private class HandleTable(private val max: Int) {
        /** Whose a handle is: this table's own, never one a plugin made or another plugin's. */
        private val owner = Any()
        private var next = 0L
        private val items = object : LinkedHashMap<Long, RankedItem>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, RankedItem>?): Boolean = size > max
        }

        @Synchronized
        fun add(item: RankedItem): Handle = Handle(owner, next++).also { items[it.serial] = item }

        /** [handle]'s release ranked again, while it is kept. */
        @Synchronized
        fun update(handle: Handle, item: RankedItem) {
            if (handle.serial in items) items[handle.serial] = item
        }

        @Synchronized
        fun items(handles: List<ReleaseHandle>): List<RankedItem> =
            handles.mapNotNull { handle -> (handle as? Handle)?.takeIf { it.owner === owner }?.let { items[it.serial] } }
    }

    /** A release as a plugin holds it: which table's it is and its number there, nothing of the release. */
    private class Handle(val owner: Any, val serial: Long) : ReleaseHandle {
        override fun toString(): String = "ReleaseHandle"
    }

    companion object {
        /** Searches a minute per plugin that may ask an indexer anew (L630). */
        const val CAP = 10
        const val CAP_WINDOW_MS = 60_000L

        /** The handles each plugin's table keeps, the oldest dropped (L630). */
        const val MAX_HANDLES = 4_096

        private val log = LoggerFactory.getLogger(PluginSearchService::class.java)

        /** An indexer not yet answered. */
        const val SEARCHING = "Searching…"

        /** An indexer that can't run a search of words. */
        const val SKIPPED = "Doesn't support this search"

        fun found(count: Int): String = "$count found"

        fun paused(plugin: String): String = "Paused: too many searches from $plugin"

        /** A failure in the host's words, by its kind alone: the error's message can hold the indexer's address and the request. */
        fun failure(error: NewznabError): String = when (error) {
            is NewznabError.Auth, is NewznabError.Disabled -> "Sign-in refused"
            is NewznabError.ApiLimitReached, is NewznabError.DownloadLimitReached -> "Limit reached"
            is NewznabError.Timeout -> "No answer in time"
            is NewznabError.Network -> "Couldn't reach it"
            is NewznabError.Server -> "Server error"
            is NewznabError.BadResponse, is NewznabError.NotFound, is NewznabError.Unsupported -> BAD_ANSWER
        }

        private const val BAD_ANSWER = "Bad answer"

        /**
         * Each indexer's name for a plugin, by id: the one the user typed unless it names its address (Settings names one
         * saved without a name after its host), else "Indexer <n>" by its place in Settings ([asked]'s order).
         */
        fun names(asked: List<IndexerConfig>): Map<String, String> =
            asked.withIndex().associate { (place, indexer) -> indexer.id to (indexer.typedName() ?: "Indexer ${place + 1}") }

        private fun IndexerConfig.typedName(): String? {
            val typed = name.trim().takeIf { it.isNotEmpty() } ?: return null
            val host = runCatching { URI(baseUrl.trim()).host }.getOrNull()?.lowercase()?.removePrefix("[")?.removeSuffix("]")
            val words = typed.lowercase()
            return typed.takeUnless { "://" in words || !host.isNullOrEmpty() && host in words }
        }

        private fun line(name: String, status: IndexerStatus?, error: NewznabError?): IndexerLine = when (val state = status?.state) {
            null, IndexerStatus.State.Searching -> IndexerLine(name, IndexerLine.State.Searching, 0, SEARCHING)
            is IndexerStatus.State.Answered -> IndexerLine(name, IndexerLine.State.Found, state.results, found(state.results))
            // A torrent indexer's torrents count as found while they are checked, as once they are.
            is IndexerStatus.State.Checking -> IndexerLine(name, IndexerLine.State.Found, state.results, found(state.results))
            is IndexerStatus.State.Failed -> IndexerLine(name, IndexerLine.State.Failed, 0, error?.let { failure(it) } ?: BAD_ANSWER)
            is IndexerStatus.State.Skipped -> IndexerLine(name, IndexerLine.State.Skipped, 0, SKIPPED)
        }

        private fun RankedItem.info(names: Map<String, String>, handle: ReleaseHandle) = ReleaseInfo(
            name = item.title,
            size = item.sizeBytes,
            postedAt = item.postedAt ?: item.publishedAt,
            indexer = names[item.indexerId].orEmpty(),
            playable = ranked.playable,
            whyNot = ranked.reasons.firstOrNull { it.severity == Severity.Blocking }?.text?.takeUnless { ranked.playable },
            handle = handle,
        )
    }
}
