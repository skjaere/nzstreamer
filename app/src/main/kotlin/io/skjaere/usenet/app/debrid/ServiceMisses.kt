package io.skjaere.usenet.app.debrid

import io.skjaere.usenet.app.play.MissingReleases
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.UsableRoutes
import io.skjaere.usenet.app.play.skippedLine
import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.MissingOn
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.core.TriedReleases
import io.skjaere.usenet.data.ReleaseChecks
import io.skjaere.usenet.data.TriedRelease
import io.skjaere.usenet.player.LinkFailure
import io.skjaere.usenet.player.PlayUris
import io.skjaere.usenet.releases.ReleaseFacts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * What plays remember of the torrents service accounts failed (L174), by the torrent and the account: one the account no
 * longer had cached, one its service wouldn't play, and one whose link couldn't be had again, for an hour, in memory
 * (TorBox keeps its own answer an hour); one the service removed, for 30 days, in the library's tried releases ([store]),
 * under the torrent's content id (`btih:<hex>`) with a check per account (`service:<id>`), as the releases found missing
 * on the providers are kept. A play's [ServiceSource] passes over an account so remembered for a torrent, and the play
 * skips a torrent so remembered on every account it plays from without spending an attempt ([forPlay]), unless the user
 * chose it. One an account prepares is forgotten for that account ([forget]). A torrent whose files are a copy of a disc
 * is kept in the same table for 30 days, whichever account listed it, and skipped before any is asked (W103, L328).
 * [now] and [zone] date them.
 */
class ServiceMisses(
    private val store: MissingReleases.Store,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    /** A failure remembered [at] a time. */
    private class Miss(val failure: AttemptFailure, val at: Long)

    /** By the torrent's content id and the account's id. */
    private val memory = ConcurrentHashMap<Pair<String, String>, Miss>()

    private val changed = MutableStateFlow(0)

    /**
     * Counts each change to what is remembered ([record], [forget]): Search, under the player as a play from it fails,
     * reads what its rows' tags pass over again (COT-1).
     */
    val changes: StateFlow<Int> = changed.asStateFlow()

    /** Remembers [failure] of [candidate]'s torrent from the account [account], if it is one a play remembers. */
    suspend fun record(candidate: PlayCandidate, account: String, failure: AttemptFailure) {
        when (failure) {
            AttemptFailure.NoLongerCached, is AttemptFailure.ServiceRefused, AttemptFailure.NoWorkingLink -> memory[candidate.id to account] = Miss(failure, now())
            AttemptFailure.RemovedByService -> candidate.indexer?.let { store.record(it, candidate.id, listOf(UsableRoutes.serviceRoute(account)), incomplete = false) } ?: return
            // An account's limit, a refused key, an outage: its route stops for the play, and its gate holds it beyond.
            else -> return
        }
        changed.update { it + 1 }
    }

    /** What is remembered now of [candidate]'s torrent from the account [account]: the failure, or null to ask the account. */
    suspend fun known(candidate: PlayCandidate, account: String): AttemptFailure? {
        val at = forgetOld()
        memory[candidate.id to account]?.let { return it.failure }
        return AttemptFailure.RemovedByService.takeIf { removedOn(store.get(listOf(candidate.id))[candidate.id], account, at) }
    }

    /** Forgets the failures kept in memory past their hour; the time now. */
    private fun forgetOld(): Long = now().also { at -> memory.entries.removeIf { at - it.value.at >= HOUR_MS } }

    /** Whether [tried] (a torrent's tried release) says the account [account]'s service removed it, within its 30 days. */
    private fun removedOn(tried: TriedRelease?, account: String, at: Long): Boolean =
        tried?.missingOn?.any { it.provider == UsableRoutes.serviceRoute(account) && at - it.checkedAt < ReleaseChecks.EXPIRY_MS } == true

    /** Forgets what is remembered of [candidate]'s torrent from the account [account]: that account just prepared it. */
    suspend fun forget(candidate: PlayCandidate, account: String) {
        memory.remove(candidate.id to account)
        candidate.indexer?.let { store.found(it, candidate.id, listOf(UsableRoutes.serviceRoute(account))) }
        changed.update { it + 1 }
    }

    /**
     * Of [candidates], the torrents a play would skip ([forPlay]), by their ids, with the words of what is remembered on the
     * first account they play from: what a release list marks them with ("✕ No longer cached", L174, DEP-7).
     */
    suspend fun marks(candidates: List<PlayCandidate>): Map<String, String> =
        candidates.mapNotNull { candidate -> skipped(candidate)?.let { candidate.id to it } }.toMap()

    /**
     * Of [candidates], the accounts a play of each would pass over, remembered as having failed it (L247), by the torrents'
     * ids; none for one no account it plays from failed: what Search's season rows and a movie's card leave out of a tag.
     */
    suspend fun passedOver(candidates: List<PlayCandidate>): Map<String, Set<String>> {
        if (candidates.isEmpty()) return emptyMap()
        val at = forgetOld()
        // One read of the tried releases for them all: Search asks this for every row as its results come.
        val tried = store.get(candidates.map { it.id })
        return candidates.mapNotNull { candidate ->
            val passed = candidate.routes.mapNotNull(UsableRoutes::accountOf).filter { account ->
                memory.containsKey(candidate.id to account) || removedOn(tried[candidate.id], account, at)
            }
            passed.takeIf { it.isNotEmpty() }?.let { candidate.id to it.toSet() }
        }.toMap()
    }

    /** Why a play would skip [candidate]: remembered so on every account it plays from, in what is remembered on its first; null if not. */
    private suspend fun skipped(candidate: PlayCandidate): String? {
        if (candidate.routes.isEmpty()) return null
        val known = candidate.routes.map { route -> UsableRoutes.accountOf(route)?.let { known(candidate, it) } ?: return null }
        return known.first().text
    }

    /**
     * What a play skips of torrents ([TriedReleases]): a disc copy, in the line a Usenet release's skip has, whatever its
     * accounts (L328); else one so remembered on every account it plays from, by the words of what is remembered on its
     * first; nothing that the user [chosen]. A disc copy found is kept by the torrent's content id, as a removed one is.
     */
    fun forPlay(chosen: Boolean): TriedReleases = object : TriedReleases {
        override suspend fun knownMissing(candidate: PlayCandidate): String? {
            if (chosen) return null
            // Not in skipped(), which marks() shares: a list's row shows this one's chip, read from the same table.
            val at = now()
            ReleaseChecks.known(store.get(listOf(candidate.id))[candidate.id], emptyList(), at)?.let { return skippedLine(it, at, zone()) }
            return skipped(candidate)
        }

        override suspend fun discCopy(candidate: PlayCandidate) {
            candidate.indexer?.let { store.recordDiscCopy(it, candidate.id) }
        }

        // A torrent is never found missing on providers, nor passworded; what an account prepares, it forgets ([forget]),
        // as only the source knows which account that was.
        override suspend fun missing(candidate: PlayCandidate, on: MissingOn) = Unit
        override suspend fun passwordNeeded(candidate: PlayCandidate) = Unit
        override suspend fun found(candidate: PlayCandidate) = Unit
    }

    private companion object {
        /** How long "not cached" and the rest are remembered: TorBox's own answer is an hour's. */
        const val HOUR_MS = 60 * 60 * 1000L
    }
}

/**
 * A debrid play that failed mid-play, its file no longer to be had (L174, L204), as it is remembered: [launch]'s release
 * as a play's candidate, by the [account] its listing came from, and the player's failure as an attempt's ([failure],
 * [ServiceSource.linkFailureOf]). [stopped]: the routes it stops (the account's limit, refused key or outage), which Try
 * another release leaves out.
 */
class ServicePlayFailure(val candidate: PlayCandidate, val account: String, val failure: AttemptFailure) {
    val stopped: Set<String> get() = setOfNotNull(failure.route)

    companion object {
        /**
         * [launch]'s, for the player's [failure], by the account [accountOf] gives its listing (the URI's opaque id); null
         * for a launch that isn't a debrid play's, or whose listing this process doesn't hold.
         */
        fun of(launch: PlayerLaunch, failure: LinkFailure, accountOf: (listingId: String) -> String?): ServicePlayFailure? {
            val target = PlayUris.parse(launch.uri) as? PlayUris.Target.Service ?: return null
            val account = accountOf(target.listingId) ?: return null
            val route = UsableRoutes.serviceRoute(account)
            val release = launch.release
            val candidate = PlayCandidate(release.key, release.title, release.indexerId, source = ReleaseFacts.TORRENT, routes = listOf(route))
            return ServicePlayFailure(candidate, account, ServiceSource.linkFailureOf(failure, route))
        }
    }
}

/** What a play skips and remembers, by each release's source ([PlayCandidate.source]): Usenet's by [usenet], a torrent's by [service]. */
fun triedBySource(usenet: TriedReleases, service: TriedReleases): TriedReleases = object : TriedReleases {
    private fun of(candidate: PlayCandidate) = if (candidate.source == PlayCandidate.USENET) usenet else service

    override suspend fun knownMissing(candidate: PlayCandidate) = of(candidate).knownMissing(candidate)
    override suspend fun missing(candidate: PlayCandidate, on: MissingOn) = of(candidate).missing(candidate, on)
    override suspend fun found(candidate: PlayCandidate) = of(candidate).found(candidate)
    override suspend fun passwordNeeded(candidate: PlayCandidate) = of(candidate).passwordNeeded(candidate)
    override suspend fun discCopy(candidate: PlayCandidate) = of(candidate).discCopy(candidate)
}
