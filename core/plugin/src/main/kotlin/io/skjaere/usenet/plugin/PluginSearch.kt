package io.skjaere.usenet.plugin

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** The user's indexers, searched for a plugin by the host, with their own keys, limits and cache. */
interface PluginSearch {
    /**
     * Searches every indexer the user set up for [words], as text with no category, each with its own User-Agent, limits
     * and the host's 15-minute cache of answers; [fresh] asks each again rather than reuse one. The host ranks what they
     * find as its Search does, with [runtimeMinutes] as how long the item runs (null: unknown), so searching the same words
     * again with another runtime only ranks again.
     *
     * Each collection searches. A [Found] comes as each indexer answers; the flow ends once every one has.
     *
     * A soft cap guards the user's indexer limits: ten searches a minute per plugin that ask an indexer anew (an answer
     * from the cache doesn't count). Past it a search is answered at once, every indexer [IndexerLine.State.Limited]. A
     * plugin searches when the user asks for something, never as a page opens, on a timer or for a whole list.
     */
    fun search(words: String, runtimeMinutes: Int?, fresh: Boolean = false): Flow<Found>
}

/** One search's answers so far: a line for each indexer ([indexers]) and the [releases], best first. */
class Found(val indexers: List<IndexerLine>, val releases: List<ReleaseInfo>)

/**
 * One indexer's part in a search. Its [name] is the one the user typed for it, or "Indexer 1", "Indexer 2" in the
 * settings' order where they typed none: never its address.
 */
class IndexerLine(
    val name: String,
    val state: State,
    /** The releases it found, for [State.Found]; 0 otherwise. */
    val found: Int,
    /**
     * The host's words for the line, to show as they are: "Searching…", "12 found", or a failure by its kind ("Sign-in
     * refused", "Limit reached", "No answer in time", "Couldn't reach it", "Server error", "Bad answer"). Never the
     * indexer's own message, which can carry its address.
     */
    val text: String,
) {
    enum class State {
        /** Asked, not yet answered. */
        Searching,

        /** Answered, with [found] releases. */
        Found,

        /** Asked, and it failed: [text] says how. */
        Failed,

        /** Not asked: it can't run a text search. */
        Skipped,

        /** Not asked: this plugin's searches are paused for a while (the soft cap of [PluginSearch.search]). */
        Limited,
    }
}

/**
 * A release as the host ranked it: its [name], its [size] in bytes, when it was posted ([postedAt]: when it was published
 * where the indexer doesn't say; null where it gives neither), the [indexer] it came from as [IndexerLine.name] names it,
 * and whether it is [playable] here, else [whyNot] in the host's words. Its [handle] stands for it in a play or Choose
 * release.
 */
class ReleaseInfo(
    val name: String,
    val size: Long,
    val postedAt: Instant?,
    val indexer: String,
    val playable: Boolean,
    val whyNot: String?,
    val handle: ReleaseHandle,
)

/**
 * A release found by [PluginSearch.search], opaque: the host keeps what it stands for (the indexer, its key, the link)
 * and hands the plugin only this. The host keeps the latest 4,096 per plugin; an older one is left out where it is
 * handed back. A handle the plugin made itself stands for nothing.
 */
interface ReleaseHandle
