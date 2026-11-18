package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.app.download.DownloadResolver.Companion.isOutage
import io.skjaere.usenet.app.search.ReleaseListMode
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.Clock
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadDao
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.ServiceAccount
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.MultiIndexerSearch
import io.skjaere.usenet.newznab.NewznabError
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.ReleaseReason
import io.skjaere.usenet.search.EpisodeRequest
import io.skjaere.usenet.search.FinderEvent
import io.skjaere.usenet.search.RankedItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.mapNotNull
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * The turn's search for a season row (L392, L398, L443): its episode searched once its turn comes, by the facts its row
 * keeps ([SeasonSearch], so no TMDB call), before anything is grabbed. The full finder ([find]: the episode, its air date,
 * the season's packs and its name) over the indexers a search asks ([indexers]: the torrent indexers only while debrid
 * shows and a service account is on, L422), ranked for downloading as the Download sheet ranks an episode's ([context],
 * W112): from the background host too, a torrent is checked against the services' caches by its hash alone, as a play's
 * search checks it, and taken once its check has settled; one not cached stays hidden (L459). The row says "Finding
 * releases…" meanwhile.
 * - Found: up to 10 that fit the room now ([space], the row's own need counted out), in their order, written into the row
 *   as its releases, with what the first needs (a season pack's share of the episode) and its facts as searched: a
 *   process that dies after it resolves from that list, with no second search.
 * - Found, and none fits: [Searched.WontFit].
 * - An indexer the walk keeps out of its searches or its grabs ([Limits]) isn't asked (L399b, L443): one refused a grab
 *   for its download limit too, as none of its releases could be grabbed meanwhile and they would crowd the others' out
 *   of those kept.
 * - Nothing found while an indexer is limited, answering so now or kept out ([Limits]): a wait ([Searched.Limited]) for
 *   the limit that ends first, never the outage's backoff, which would ask it again every few seconds (L443).
 * - Nothing found, an indexer out of reach or failing (a 5xx): [Searched.Out], backed off from as a grab's outage is.
 * - Nothing found while the service accounts couldn't say whether its torrents are cached (no account could be asked: its
 *   limit, its key refused, its outage, or a check out of time), or a torrent indexer went unasked for a refused key:
 *   [Searched.Unchecked], which is no answer about the releases, the row waiting for the accounts as what stopped them
 *   says ([stopped], L460, L461, L474).
 * - Nothing found, every indexer answered: [Searched.NoneFound] (L398).
 *
 * Its log lines name the row by its id only: no title, query, indexer or host.
 */
class DownloadSearch(
    private val downloads: DownloadDao,
    /** The episode finder's search ([io.skjaere.usenet.search.EpisodeReleaseFinder.find]), of the indexers given. */
    private val find: (request: EpisodeRequest, indexers: List<IndexerConfig>, context: RankingContext) -> Flow<FinderEvent>,
    /** The indexers a download searches, as a search asks them ([AppContainer.indexerConfigs]). */
    private val indexers: suspend () -> List<IndexerConfig>,
    /** What a download's releases are ranked by ([AppContainer.downloadRankingContext]). */
    private val context: suspend () -> RankingContext,
    /** The room on the device for download [id]'s need, its own counted out; null while the volume isn't there. */
    private val space: suspend (id: String) -> DownloadSpace?,
    private val limits: Limits = Limits.None,
    private val clock: Clock = Clock.System,
    /**
     * What kept the service accounts from saying whether torrents are cached, at [at] (epoch milliseconds), where nothing
     * was found: [unchecked], the finder hid torrents as not checked; else whether a refused key kept the torrent indexers
     * unasked. Null where nothing stopped them. The app's reads the settings and the gates alone ([DownloadSearch.stopped]).
     */
    private val stopped: suspend (unchecked: Boolean, at: Long) -> ServiceStop? = { _, _ -> null },
) {
    /** What keeps the service accounts from being asked about a torrent's cache now (L474): no answer about the release. */
    sealed interface ServiceStop {
        /** Account [account]'s limit shut its gate until [until] (epoch milliseconds): of those on, the first to open. */
        data class Limit(val account: String, val until: Long) : ServiceStop {
            // No account in a log line.
            override fun toString() = "Limit(until=$until)"
        }

        /** An account's key refused: a new one is waited for. */
        data object KeyRefused : ServiceStop

        /** Out of reach or failing, or slower than the check's time: asked again after a wait. */
        data object Out : ServiceStop
    }

    /**
     * What the walk keeps of the indexers' limits (L443): those kept out of a turn's search until their limit's end, and each
     * limit a turn's search was answered with, kept by the runner's memory, as a play's search keeps none of it; and those
     * kept out of a turn's grabs, each refused a grab for its limit by an earlier row's turn.
     */
    interface Limits {
        /** Of [indexers], those kept out at [at] (epoch milliseconds), by id, each with the wait it is kept out for. */
        fun keptOut(indexers: List<IndexerConfig>, at: Long): Map<String, LimitWait>

        /** [indexer] answered a turn's search with its limit at [at], ending [retryAfter] later where it said when. */
        fun answered(indexer: IndexerConfig, retryAfter: Duration?, at: Long)

        /** Of [indexers], those kept out of grabs at [at] (epoch milliseconds), by id, each with the wait it is kept out for. */
        fun grabsKeptOut(indexers: List<IndexerConfig>, at: Long): Map<String, LimitWait> = emptyMap()

        /** [indexer] refused a turn's grab for its limit, as [wait] says. */
        fun refused(indexer: IndexerConfig, wait: LimitWait) = Unit

        companion object {
            /** None kept out, nothing kept. */
            val None: Limits = object : Limits {
                override fun keptOut(indexers: List<IndexerConfig>, at: Long): Map<String, LimitWait> = emptyMap()
                override fun answered(indexer: IndexerConfig, retryAfter: Duration?, at: Long) = Unit
            }
        }
    }

    /** How a turn's search ended. */
    sealed interface Searched {
        /** Found: [row] as it is now kept, its releases those found that fit. */
        data class Found(val row: Download) : Searched

        /** Found, and none fits the room on the device. */
        data object WontFit : Searched

        /** Every indexer asked answered, and nothing was found. */
        data object NoneFound : Searched

        /** Nothing found while an indexer is limited: the row waits as [wait] says. */
        data class Limited(val wait: LimitWait) : Searched

        /** Nothing found while an indexer is out of reach or failing: asked again after a wait. */
        data object Out : Searched

        /** Found, and the volume isn't there to say what fits. */
        data object Unavailable : Searched

        /** The row is gone: removed meanwhile. */
        data object Gone : Searched

        /** Nothing found while the service accounts couldn't check its torrents, as [stop] says: the row waits for them. */
        data class Unchecked(val stop: ServiceStop) : Searched
    }

    /** Whether [row]'s turn searches: a season row (L392) whose facts are still to be searched, and no file chosen by hand. */
    fun due(row: Download): Boolean =
        row.season != null && row.episode != null && DownloadColumns.pick(row) == null && DownloadColumns.search(row)?.searched == false

    /** [row]'s episode searched, as the class says, what was tried before ([leaveOut], by key) left out of what it finds. */
    suspend fun search(row: Download, leaveOut: Set<String> = row.tried.toSet()): Searched {
        val facts = DownloadColumns.search(row) ?: return Searched.NoneFound
        val season = row.season ?: return Searched.NoneFound
        val episode = row.episode ?: return Searched.NoneFound
        downloads.setState(row.id, DownloadState.Resolving, DownloadHostRules.FINDING_RELEASES, clock.now())
        val at = clock.now()
        val all = indexers()
        // Those the walk keeps out for their limits aren't asked: a search limit's would refuse each request and count it
        // against them; a download limit's releases couldn't be grabbed, and would only take the others' places. Each kept
        // out until the later of its limits ends.
        val keptOut = (limits.keptOut(all, at).entries + limits.grabsKeptOut(all, at).entries)
            .groupBy({ it.key }, { it.value }).mapValues { (_, waits) -> waits.maxBy { it.end() } }
        val asked = all.filter { it.id !in keptOut }
        log.info("download {}: searching for its releases, {} indexer{} asked, {} kept out", row.id, asked.size, if (asked.size == 1) "" else "s", keptOut.size)
        // The finder is Finished before a torrent indexer's torrents are checked against the services' caches: they join
        // after, each time with the whole result again, until every check has settled (L459). What it gives last is the one.
        val result = if (asked.isEmpty()) {
            null
        } else {
            find(facts.request(season, episode), asked, context())
                .mapNotNull { event -> (event as? FinderEvent.Finished)?.result ?: (event as? FinderEvent.Joined)?.result }
                .lastOrNull()
        }
        val reports = result?.stages.orEmpty().flatMap { it.indexers }
        val limited = reports.mapNotNull { report -> (report.error as? NewznabError.ApiLimitReached)?.let { report.indexer to it.retryAfter } }.distinctBy { it.first.id }
        limited.forEach { (indexer, retryAfter) -> limits.answered(indexer, retryAfter, at) }
        val found = result?.playable.orEmpty().map { RankedItem(it.item, it.ranked) }.filter { it.key !in leaveOut }
        if (found.isEmpty()) {
            // The limit that ends first, of those met now, each its start now (L434), and those the walk keeps an indexer
            // out for: the app's start queues the row again once any of them may answer.
            val met = limited.map { (indexer, retryAfter) -> LimitWait(indexer.id, LimitWait.Kind.Search, at, retryAfter?.let { at + it.toMillis() }) }
            val wait = (met + keptOut.values).minByOrNull { it.end() }
            return when {
                wait != null -> {
                    log.info("download {}: nothing found, an indexer's limit reached", row.id)
                    Searched.Limited(wait)
                }
                reports.any { it.error?.isOutage() == true } -> {
                    log.info("download {}: nothing found, an indexer out of reach", row.id)
                    Searched.Out
                }
                else -> {
                    // A torrent the services couldn't check may well be cached: nothing is known of it yet.
                    val unchecked = result?.hidden.orEmpty().any { hid -> hid.ranked.reasons.any { it is ReleaseReason.NotChecked } }
                    stopped(unchecked, at)?.let { stop ->
                        log.info("download {}: nothing found, its debrid service unasked ({})", row.id, stop)
                        return Searched.Unchecked(stop)
                    }
                    log.info("download {}: nothing found", row.id)
                    Searched.NoneFound
                }
            }
        }
        val room = space(row.id) ?: return Searched.Unavailable
        val seasonEpisodes = row.seasonEpisodes ?: facts.seasonEpisodes
        val fits = found.filter { ReleaseListMode.Download.need(it, seasonEpisodes) <= room.free }
        if (fits.isEmpty()) {
            log.info("download {}: {} found, none fits in the {} free", row.id, found.size, room.free)
            return Searched.WontFit
        }
        val kept = fits.take(DownloadRunner.MAX_CANDIDATES)
        val now = downloads.get(row.id) ?: return Searched.Gone
        val written = now.copy(
            candidates = kept.map(DownloadSheetModel::candidate), indexerSize = ReleaseListMode.Download.need(kept.first(), seasonEpisodes),
            search = DownloadColumns.text(facts.copy(searched = true)), updatedAt = clock.now(),
        )
        downloads.update(written)
        log.info("download {}: {} found, {} kept to try", row.id, found.size, kept.size)
        return Searched.Found(written)
    }

    companion object {
        /**
         * The app's: the episode finder over the indexers a search asks, ranked for downloading, in the room the downloads'
         * storage gives, the indexers' limits kept in the walk's memory ([limits]: the runner's).
         */
        fun of(container: AppContainer, storage: DownloadStorage, limits: Limits = Limits.None): DownloadSearch = DownloadSearch(
            downloads = container.data.downloads,
            find = { request, indexers, context -> container.finder.find(request, indexers, context) },
            indexers = { container.indexerConfigs() },
            context = container::downloadRankingContext,
            space = { id -> storage.space(id) },
            limits = limits,
            stopped = { unchecked, at -> stopped(unchecked, container.settings.settings.first(), container.services.on(), container.services::limitLeft, at) },
        )

        /**
         * What kept the service accounts [on] from saying whether torrents are cached (L474), read from the settings saved
         * ([saved]) and the accounts' gates ([limitLeft]) with no call, from [now]: only while debrid shows ([shown]) and a
         * torrent indexer is on that sends its torrents to a service. Torrents hidden as not checked ([unchecked]): every
         * account on shut for its limit, the first to open; else a key refused; else out of reach (an outage, or a check out
         * of time). None checked, for want of an account on: its key refused, where one is; else nothing stopped them, an
         * account switched off or signed out of being the user's own doing.
         */
        suspend fun stopped(
            unchecked: Boolean, saved: AppSettings, on: List<ServiceAccount>, limitLeft: suspend (String) -> kotlin.time.Duration?, now: Long,
            shown: Boolean = DebridGate.shown,
        ): ServiceStop? {
            if (!shown || saved.activeTorrentIndexers.none { it.enabled && it.useWithService }) return null
            val refused = saved.services.any { it.enabled && !it.deleted && it.keyRefused }
            if (!unchecked) return ServiceStop.KeyRefused.takeIf { on.isEmpty() && refused }
            val limited = on.mapNotNull { account -> limitLeft(account.id)?.let { account.id to it } }
            if (on.isNotEmpty() && limited.size == on.size) {
                val (account, left) = limited.minBy { it.second }
                return ServiceStop.Limit(account, now + left.inWholeMilliseconds)
            }
            return if (refused) ServiceStop.KeyRefused else ServiceStop.Out
        }
    }
}

/**
 * The walk's memory of the indexers' limits (L443), the runner's, one a process: each indexer that answered a turn's search
 * with its limit is kept out of the later turns' searches until the limit's end, where it said one (its Retry-After), else
 * for 30 minutes from its answer ([LimitWait.end]); and each that refused a turn's grab for its limit out of the later
 * turns' grabs, likewise, so the season's next rows ask it nothing either. Keyed as [MultiIndexerSearch] keys a limit, by
 * the indexer's address and key too, so another account at the same indexer is another limit. A play's search keeps none
 * of it, and Resume forgets it all ([forget]), so a user's Resume asks again at once. Safe from any thread.
 */
class DownloadLimits : DownloadSearch.Limits {
    private val lock = Any()

    /** The search limits kept, by [MultiIndexerSearch.limitKey]. */
    private val kept = HashMap<String, LimitWait>()

    /** The limits that refused grabs, kept likewise. */
    private val grabs = HashMap<String, LimitWait>()

    override fun keptOut(indexers: List<IndexerConfig>, at: Long): Map<String, LimitWait> = synchronized(lock) { of(kept, indexers, at) }

    override fun answered(indexer: IndexerConfig, retryAfter: Duration?, at: Long) = synchronized(lock) {
        // Each answer afresh, its start included: one met again waits its time again (L443).
        kept[MultiIndexerSearch.limitKey(indexer)] = LimitWait(indexer.id, LimitWait.Kind.Search, at, retryAfter?.let { at + it.toMillis() })
    }

    override fun grabsKeptOut(indexers: List<IndexerConfig>, at: Long): Map<String, LimitWait> = synchronized(lock) { of(grabs, indexers, at) }

    override fun refused(indexer: IndexerConfig, wait: LimitWait) = synchronized(lock) {
        grabs[MultiIndexerSearch.limitKey(indexer)] = wait
    }

    /** Forgets every limit kept: Resume asks the indexers again (L434). */
    fun forget() = synchronized(lock) {
        kept.clear()
        grabs.clear()
    }

    /** Of [indexers], those [limits] keeps out at [at], by id, the limits past their end let go. */
    private fun of(limits: HashMap<String, LimitWait>, indexers: List<IndexerConfig>, at: Long): Map<String, LimitWait> {
        limits.values.removeAll { it.end() <= at }
        return indexers.mapNotNull { indexer -> limits[MultiIndexerSearch.limitKey(indexer)]?.let { indexer.id to it } }.toMap()
    }
}

private val log = LoggerFactory.getLogger(DownloadSearch::class.java)
