package io.skjaere.usenet.core

import io.skjaere.nzbstreamer.config.NntpConfig
import io.skjaere.nzbstreamer.config.PoolTuning

/** A user-configured NNTP provider. Lower [priority] is tried first. */
data class ProviderConfig(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val useTls: Boolean,
    val maxConnections: Int = 8,
    val priority: Int = 0,
    val enabled: Boolean = true,
) {
    fun toNntpConfig() = NntpConfig(
        host = host,
        port = port,
        username = username,
        password = password,
        useTls = useTls,
        maxConnections = maxConnections,
        priority = priority,
    )

    override fun toString() = "ProviderConfig(id=$id, name=$name, host=$host:$port, tls=$useTls, conns=$maxConnections, priority=$priority, enabled=$enabled)"
}

/**
 * What the engine's streamer is built from. [concurrency] and [readAheadSegments] are left to the
 * providers unless set; [readAheadBytes] caps the segments a stream holds ahead of its reader (the
 * device's memory budget; nzb-streamer's StreamingConfig.readAheadBytes says what that leaves out),
 * null for no cap in bytes. [poolTuning] is how every provider's pool keeps its idle connections
 * alive and checks them before use ([POOL_TUNING]); [segmentTimeoutRetries] how many times a
 * segment whose fetch timed out is fetched again on another connection, within the fetch's own
 * deadline (twice [segmentFetchTimeoutMs], so one is all that fits): a connection that dies while
 * in use then costs a fetch timeout, not the stream. With several providers the retry stays on the
 * one it timed out on, and the next is asked after it: a connection that died on the first costs no
 * fetch from a backup, often a block account paid by the gigabyte or one without the article. The
 * price: a first provider whose every fetch stalls holds each segment twice [segmentFetchTimeoutMs]
 * before the next is asked, until its breaker opens.
 */
data class EngineSettings(
    val providers: List<ProviderConfig>,
    val concurrency: Int? = null,
    val readAheadSegments: Int? = null,
    val segmentFetchTimeoutMs: Long = 5_000,
    val readAheadBytes: Long? = null,
    val poolTuning: PoolTuning = POOL_TUNING,
    val segmentTimeoutRetries: Int = 1,
) {
    /**
     * Segments a stream fetches at once: [concurrency], else what the first provider in use allows
     * (every segment is asked of it first) less the connections kept spare, [MAX_CONCURRENCY] at
     * most. Spare connections serve a seek's new stream while the old one's cancelled fetches are
     * read off theirs, a retry, a fallback, another app on the account: two from six connections,
     * one from two, none of one. More at once only splits the link further, and on a slow one a
     * whole wave of segments runs out of time together. Read from the providers the streamer is
     * built with, so a copy that leaves one out (S9-1) follows the next.
     */
    val streamConcurrency: Int
        get() = concurrency ?: orderedProviders.firstOrNull()?.maxConnections?.let(::atOnce) ?: DEFAULT_CONCURRENCY

    /** Segments a stream reads ahead, at most: [readAheadSegments], else three for each fetched at once. */
    val streamReadAhead: Int get() = readAheadSegments ?: (streamConcurrency * 3)

    /** nzb-streamer tries pools in list order, so priority becomes list position. */
    val orderedProviders: List<ProviderConfig>
        get() = providers.filter { it.enabled }.sortedBy { it.priority }

    /** These settings without what only names the providers: what a streamer is built from. */
    internal fun builtFrom(): EngineSettings = copy(providers = providers.map { it.copy(name = "") })

    companion object {
        /**
         * The pools' timings. A NAT, a VPN or the provider may drop a connection that sat idle, often
         * without a word, and a segment fetched on it waited out its timeout and failed the stream. So a
         * connection idle 10 s or more (OkHttp's figure; steady play reuses its connections well within
         * it, a seek, a resume or a spare one doesn't) gets a DATE before it is lent, and one that
         * doesn't answer 111 within 2 s is dropped for another; the 2 s and a connect fit in the 5 s a
         * fetch waits for its connection. Every 30 s the keepalive sends a DATE to the idle connections
         * not used in the last 3 s, with 3 s to answer, which refreshes a NAT's entry for them and finds
         * the dead ones in the background; the pool's idle eviction and sleep run at that pace too. No
         * connection is opened ahead of need (a pool opens one only for a fetch waiting for it): an
         * account's connection limit is shared with its other devices.
         */
        val POOL_TUNING = PoolTuning(keepaliveIntervalMs = 30_000, keepaliveTimeoutMs = 3_000, validateAfterIdleMs = 10_000, validateTimeoutMs = 2_000)

        /** With no provider to say. */
        const val DEFAULT_CONCURRENCY = 4

        /** The most segments a stream fetches at once when the providers say. */
        const val MAX_CONCURRENCY = 8

        /** Segments fetched at once on a provider of [connections]: see [streamConcurrency]. */
        private fun atOnce(connections: Int): Int {
            val spare = when {
                connections >= 6 -> 2
                connections >= 2 -> 1
                else -> 0
            }
            return (connections - spare).coerceIn(1, MAX_CONCURRENCY)
        }
    }
}
