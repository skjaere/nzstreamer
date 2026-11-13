package io.skjaere.usenet.search

import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.releases.RankingContext
import java.security.MessageDigest
import java.util.Base64

/** What one [EpisodeReleaseFinder.find] is asked; its result holds only while all of it is unchanged. */
data class EpisodeSearch(
    val request: EpisodeRequest,
    val indexers: List<IndexerConfig>,
    val context: RankingContext,
    val remembered: RememberedPick? = null,
)

/**
 * Hands a background search for an episode (auto-advance starts one before the current episode
 * ends) to the play that follows it. The next [take] empties it, and returns the result only for
 * the same search made within [maxAgeMs]: the episode, the indexers (ids, URLs, enabled, API
 * keys), the ranking and the remembered release. A result with nothing playable or an indexer
 * error is never held, so a retry after a network failure, or for an episode whose releases
 * haven't appeared yet, searches again.
 */
class EpisodeFindHandoff(
    private val maxAgeMs: Long = 10 * 60_000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** An [EpisodeSearch] with each API key as a hash. */
    private data class Fingerprint(
        val request: EpisodeRequest,
        val indexers: List<IndexerPrint>,
        val context: RankingContext,
        val remembered: RememberedPick?,
    )

    private data class IndexerPrint(val id: String, val baseUrl: String, val enabled: Boolean, val keyHash: String)

    private class Held(val fingerprint: Fingerprint, val result: FindResult, val atMs: Long)

    private var held: Held? = null

    @Synchronized
    fun offer(search: EpisodeSearch, result: FindResult) {
        val worthKeeping = result.playable.isNotEmpty() && result.stages.none { stage -> stage.indexers.any { it.error != null } }
        held = if (worthKeeping) Held(fingerprint(search), result, now()) else null
    }

    @Synchronized
    fun take(search: EpisodeSearch): FindResult? {
        val h = held ?: return null
        held = null
        return h.result.takeIf { h.fingerprint == fingerprint(search) && now() - h.atMs < maxAgeMs }
    }

    /**
     * Whether a [take] for [request] would get the held result, also [forMs] from now, when the
     * rest of its search is unchanged: tells a caller the result is gone without the whole search.
     */
    @Synchronized
    fun holds(request: EpisodeRequest, forMs: Long = 0): Boolean {
        val h = held ?: return false
        return h.fingerprint.request == request && now() + forMs - h.atMs < maxAgeMs
    }

    @Synchronized
    fun clear() {
        held = null
    }

    private fun fingerprint(search: EpisodeSearch) = Fingerprint(
        search.request,
        search.indexers.map { IndexerPrint(it.id, it.baseUrl, it.enabled, sha256(it.apiKey)) },
        search.context,
        search.remembered,
    )

    private fun sha256(s: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))
}
