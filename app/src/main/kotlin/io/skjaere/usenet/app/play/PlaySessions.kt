package io.skjaere.usenet.app.play

import io.skjaere.usenet.app.casting.CastPrecheck
import io.skjaere.usenet.app.search.sourceLabel
import io.skjaere.usenet.core.ProviderConfig
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.RankedItem
import java.util.UUID

/**
 * A play started again after its release failed mid-stream: without the releases that [failed]
 * during it (their keys, [io.skjaere.usenet.releases.ReleaseKey]), the next one starting at [startPositionMs], where the
 * failed one stopped (null: as usual, from the saved position). [failedKeys] are the watch keys
 * the failed ones' progress was saved under, which the next one takes over ([PlayerLaunch.replaces]).
 * Not [midStream]: the release failed the pipeline's checks before anything played (Resume's
 * fallback to the episode search, [ResumePlan.run]), or ([foundIncomplete]) wasn't tried at all,
 * skipped without a grab as found incomplete before ([MissingReleases]), or ([fromPlaying]) it is
 * the release that was playing, which failed the episode after the one it played, or plainly
 * doesn't hold it ([playingLacksIt]) ([PlayingPack]). [left]: the release was left while it still played (for its
 * missing parts, or for a smaller one), and is the way back if nothing else does. [stopped]: the service accounts' routes
 * a failure stopped during the play (a refused key, a limit, an outage, L164): the releases that play from those alone
 * are left out too, as each would fail the same way. [skippedFor]: a torrent skipped without a try, as its debrid service
 * failed it before, in that failure's words ("No longer cached"), which the sheet says in place of found incomplete (DEP-8).
 */
internal data class Retry(
    val failed: Set<String>,
    val startPositionMs: Long?,
    val failedKeys: List<String> = emptyList(),
    val midStream: Boolean = true,
    val foundIncomplete: Boolean = false,
    val fromPlaying: Boolean = false,
    /** The release that was playing ([fromPlaying]) doesn't hold the episode: nothing failed, so the sheet says nothing of it. */
    val playingLacksIt: Boolean = false,
    /** The release that was playing, left for its missing parts (the player's prompt) or for a smaller one (its slow-stream banner): the way back if nothing else plays. */
    val left: Left? = null,
    val stopped: Set<String> = emptySet(),
    val skippedFor: String? = null,
) {
    /**
     * [candidates] without the failed releases, wherever they came from (a search, or the remembered release the store adds
     * back), and without those whose every route is [stopped].
     */
    fun leaveOut(candidates: List<RankedItem>): List<RankedItem> = candidates.filter { it.key !in failed && !stops(it) }

    /** Whether every route [release] plays by is one the play [stopped]. */
    fun stops(release: RankedItem): Boolean = stopped.isNotEmpty() && release.playCandidate.routes.let { it.isNotEmpty() && stopped.containsAll(it) }

    /**
     * The request to keep for the launch of the release that plays, for its Try another release: the one [played], or,
     * where the play [wentBack] to the release left while it played, the request the play had then ([Left.request]): Try
     * a smaller release cut it down to the smaller ones, which were all tried.
     */
    fun kept(played: PlayRequest, wentBack: Boolean): PlayRequest = left?.request?.takeIf { wentBack } ?: played

    /** What the play sheet says above the next release's steps, until another fails. */
    val notice: String?
        get() = when {
            midStream && left?.why == Left.Why.Smaller -> "Switching to a smaller release · it starts where you were"
            midStream && left != null -> "The release before was missing parts · trying another"
            midStream -> "The release before stopped playing · trying another"
            playingLacksIt -> null
            fromPlaying -> "$PLAYING_FAILED · trying another"
            else -> null
        }

    /** What the play sheet says of the release before while another is looked for. */
    val searchingNotice: String
        get() = when {
            left?.why == Left.Why.Smaller -> "Switching to a smaller release"
            left != null -> "The release that played it was missing parts"
            skippedFor != null -> "The release that played it was skipped: ${skippedFor.replaceFirstChar { it.lowercase() }}"
            foundIncomplete -> "The release that played it was found incomplete before"
            fromPlaying -> PLAYING_FAILED
            else -> "The release that played it failed"
        }

    companion object {
        private const val PLAYING_FAILED = "The release that was playing couldn't play it"

        /**
         * After the remembered release [key] played alone ([failed]: its failure, null when it was passed over), the search
         * for another, leaving it out: skipped without a try, found incomplete before on Usenet, or as its debrid service
         * failed it before ([skippedFor], DEP-8).
         */
        fun afterRemembered(key: String, failed: PlayState.Failed?): Retry {
            val skipped = failed?.takeIf { it.onlySkipped }?.missing?.firstOrNull()?.second
            return Retry(
                setOf(key), startPositionMs = null, midStream = false,
                foundIncomplete = skipped != null && usenetSkip(skipped), skippedFor = skipped?.takeUnless(::usenetSkip),
            )
        }
    }
}

/**
 * A release left while it still played (L9, W3): [release], which played [watchKey], fetched from the providers [asked],
 * left [why]: the player's prompt found parts of it missing, or its slow-stream banner had a smaller one tried (L8). It
 * stays the release Resume plays, and isn't remembered as missing, until another release plays; then one left for its
 * missing parts is remembered as missing on [asked], and the one that plays takes Resume over. If none plays, the play
 * goes back to it from where it was, as its last try: from its stored NZB, without the availability check (it was just
 * playing), and with [request], the play's request when it was left, where the one tried was cut down (null: the same).
 */
internal data class Left(
    val release: SavedRelease,
    val watchKey: String,
    val asked: List<ProviderConfig>,
    val why: Why = Why.MissingParts,
    val request: PlayRequest? = null,
) {
    /** Why it was left: its missing parts, or the stream too slow for it. */
    enum class Why { MissingParts, Smaller }

    /** What the play sheet says as the play goes back to it, naming it briefly: "the 2160p Remux". */
    val backNotice: String
        get() {
            val parsed = ReleaseParser.parse(release.title)
            val name = listOfNotNull(parsed.resolution?.let { "${it}p" }, sourceLabel(parsed.source)).joinToString(" ")
            val none = if (why == Why.Smaller) "No smaller release played" else "No other release played"
            return "$none · back to ${if (name.isEmpty()) "the release that was playing" else "the $name"}, from where you were"
        }
}

/**
 * What "Try another release" does for a launch whose release failed, and in whose session ([origin], L33): a phone's play
 * stays that phone's, from where it stopped.
 */
internal sealed interface RetryPlan {
    /** The origin of the play it starts; null for none. */
    val origin: PlayOrigin?

    /** Plays [request] again, without [retry]'s failed releases. */
    data class Again(val request: PlayRequest, val retry: Retry, override val origin: PlayOrigin = PlayOrigin.Here) : RetryPlan

    /** Looks the episode up and searches for it again, leaving out the failed releases: the request is gone, or has nothing left. */
    data class SearchEpisode(val target: EpisodeTarget, val retry: Retry, override val origin: PlayOrigin = PlayOrigin.Here) : RetryPlan

    /** Nothing else to try: the player offers Close only. */
    data object None : RetryPlan {
        override val origin: PlayOrigin? get() = null
    }
}

/**
 * The requests behind the player's launches ([PlayerLaunch.requestId]) and the releases that failed
 * during each play, in memory, so "Try another release" plays the same request without them. Only
 * the [keep] latest are kept. After process death none is found, and an episode is searched for
 * again; ids are random, so a launch from an earlier process never finds another play's request.
 */
internal class PlaySessions(private val keep: Int = 8, private val newId: () -> String = { UUID.randomUUID().toString() }) {
    private class Session(val request: PlayRequest, val failed: Set<String>, val stopped: Set<String>)

    private val sessions = object : LinkedHashMap<String, Session>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Session>?) = size > keep
    }

    /**
     * Keeps [request] for the launch about to start; [failed] are the releases that failed during this play so far, and
     * [stopped] the service accounts' routes their failures stopped ([Retry.stopped]). Returns its id.
     */
    @Synchronized
    fun start(request: PlayRequest, failed: Set<String>, stopped: Set<String> = emptySet()): String = newId().also { sessions[it] = Session(request, failed, stopped) }

    /**
     * "Try a smaller release" for [launch]'s release, still playing at [positionMs] but read at [measuredBps] where the file
     * needs [playingBps] (its size over its length) (L8): its request again with only the releases the ranker estimates at
     * 0.8 of the playing one's or less (a real step down, not one a few per cent smaller), those the stream carries (0.8 of
     * the rate or less) first, each in the request's order; not those that failed during the play, nor those without an
     * estimate, nor those [knownMissing] (by key), which the play would skip without a grab, going straight back to the
     * one playing. The playing release is judged by its own estimate in the request, or without one by [playingBps]. It is
     * the way back if none plays ([Left.Why.Smaller]), with the whole request again ([Retry.kept]). Those alone are tried:
     * the release Resume remembers isn't put first, as it may be one from a play before, not yet replaced by this one's.
     * Nothing when the request has none (a play not started from a list of releases, W2) or is gone.
     */
    @Synchronized
    fun smaller(launch: PlayerLaunch, positionMs: Long?, measuredBps: Long, playingBps: Long, knownMissing: Set<String> = emptySet()): RetryPlan {
        val session = launch.requestId?.let(sessions::get) ?: return RetryPlan.None
        val request = session.request
        val playing = launch.release.key
        val from = request.candidates.firstOrNull { it.key == playing }?.ranked?.estimatedBitrate ?: playingBps
        val stopped = Retry(session.failed, null, stopped = session.stopped)
        val smaller = request.candidates.filter { c ->
            c.key != playing && c.key !in session.failed && !stopped.stops(c) && c.key !in knownMissing && c.ranked.estimatedBitrate?.let { it * 5 <= from * 4 } == true
        }
        if (smaller.isEmpty()) return RetryPlan.None
        val (carried, rest) = smaller.partition { it.ranked.estimatedBitrate!! * 5 <= measuredBps * 4 }
        val retry = Retry(
            session.failed + playing, positionMs, (launch.replaces + launch.watchKey).distinct(),
            left = Left(launch.release, launch.watchKey, emptyList(), Left.Why.Smaller, request), stopped = session.stopped,
        )
        return RetryPlan.Again(request.copy(candidates = carried + rest, rememberedFirst = false), retry, PlayOrigin.after(launch))
    }

    /**
     * Play a compatible release, from the player (L152, L124 step 2): [launch]'s request again from [positionMs], its
     * releases (a hand pick's list's others too) as [rank] orders them for the Cast device [launch] goes to (the list
     * ranked them for whom it showed them), the playable ones, without the one playing, those that failed during the play
     * and those the ranking says the device can't fully play ([CastPrecheck.compatible]): an automatic pick, which moves
     * past any the device's verdict fails, on [launch]'s device, taking over the playing one's progress and saying nothing
     * of a release before. Nothing without the request, or with none left: the question offers no such button.
     */
    @Synchronized
    fun compatible(launch: PlayerLaunch, positionMs: Long?, rank: (List<RankedItem>) -> List<RankedItem>): RetryPlan {
        val session = launch.requestId?.let(sessions::get) ?: return RetryPlan.None
        val playing = launch.release.key
        val held = (session.request.candidates + session.request.alternatives).distinctBy { it.key }
        val retry = Retry(session.failed + playing, positionMs, (launch.replaces + launch.watchKey).distinct(), midStream = false, stopped = session.stopped)
        val others = rank(held).filter { it.ranked.playable && it.key != playing && it.key !in session.failed && !retry.stops(it) }
        val request = CastPrecheck.compatible(session.request.copy(alternatives = others, castAnyway = false)) ?: return RetryPlan.None
        return RetryPlan.Again(request, retry, PlayOrigin.after(launch))
    }

    /**
     * What to play after [launch]'s release failed at [positionMs], or was [left] while it played: its request again
     * without every release that failed during the play, and without it, nor those that play from a service account
     * whose route its failure [stopped] or one before it did; once nothing is left, or without the request, an episode is
     * searched for again; anything else has nothing to try.
     */
    @Synchronized
    fun retry(launch: PlayerLaunch, positionMs: Long?, left: Left? = null, stopped: Set<String> = emptySet()): RetryPlan {
        val session = launch.requestId?.let(sessions::get)
        val retry = Retry(
            session?.failed.orEmpty() + launch.release.key, positionMs, (launch.replaces + launch.watchKey).distinct(), left = left,
            stopped = session?.stopped.orEmpty() + stopped,
        )
        return when {
            session != null && retry.leaveOut(session.request.candidates).isNotEmpty() -> RetryPlan.Again(session.request, retry, PlayOrigin.after(launch))
            else -> launch.watch.episodeTarget?.let { RetryPlan.SearchEpisode(it, retry, PlayOrigin.after(launch)) } ?: RetryPlan.None
        }
    }
}
