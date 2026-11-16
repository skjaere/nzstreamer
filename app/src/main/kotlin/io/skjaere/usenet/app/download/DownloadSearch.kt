package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.download.DownloadResolver.Companion.isOutage
import io.skjaere.usenet.app.search.ReleaseListMode
import io.skjaere.usenet.data.Clock
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadDao
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.MultiIndexerSearch
import io.skjaere.usenet.newznab.NewznabError
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.search.EpisodeRequest
import io.skjaere.usenet.search.FinderEvent
import io.skjaere.usenet.search.RankedItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.firstOrNull
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * The turn's search for a season row (L392, L398, L443): its episode searched once its turn comes, by the facts its row
 * keeps ([SeasonSearch], so no TMDB call), before anything is grabbed. The full finder ([find]: the episode, its air date,
 * the season's packs and its name) over the Usenet indexers ([indexers], L370), ranked for downloading as the Download sheet
 * ranks an episode's ([context], W112), torrents left out. The row says "Finding releases…" meanwhile.
 * - Found: up to 10 that fit the room now ([space], the row's own need counted out), in their order, written into the row
 *   as its releases, with what the first needs (a season pack's share of the episode) and its facts as searched: a
 *   process that dies after it resolves from that list, with no second search.
 * - Found, and none fits: [Searched.WontFit].
 * - Nothing found while an indexer is limited, answering so now or kept out of the walk's searches ([Limits]): a wait
 *   ([Searched.Limited]), never the outage's backoff, which would ask it again every few seconds (L443).
 * - Nothing found, an indexer out of reach or failing (a 5xx): [Searched.Out], backed off from as a grab's outage is.
 * - Nothing found, every indexer answered: [Searched.NoneFound] (L398).
 *
 * Its log lines name the row by its id only: no title, query, indexer or host.
 */
class DownloadSearch(
    private val downloads: DownloadDao,
    /** The episode finder's search ([io.skjaere.usenet.search.EpisodeReleaseFinder.find]), of the indexers given. */
    private val find: (request: EpisodeRequest, indexers: List<IndexerConfig>, context: RankingContext) -> Flow<FinderEvent>,
    /** The indexers a download searches: the Usenet ones ([DownloadSheetDeps.usenetOnly]). */
    private val indexers: suspend () -> List<IndexerConfig>,
    /** What a download's releases are ranked by ([AppContainer.downloadRankingContext]). */
    private val context: suspend () -> RankingContext,
    /** The room on the device for download [id]'s need, its own counted out; null while the volume isn't there. */
    private val space: suspend (id: String) -> DownloadSpace?,
    private val limits: Limits = Limits.None,
    private val clock: Clock = Clock.System,
) {
    /**
     * What the walk keeps of the indexers' limits (L443): those kept out of a turn's search until their limit's end, and each
     * limit a turn's search was answered with, kept by the runner's memory, as a play's search keeps none of it.
     */
    interface Limits {
        /** Of [indexers], those kept out at [at] (epoch milliseconds), by id, each with the wait it is kept out for. */
        fun keptOut(indexers: List<IndexerConfig>, at: Long): Map<String, LimitWait>

        /** [indexer] answered a turn's search with its limit at [at], ending [retryAfter] later where it said when. */
        fun answered(indexer: IndexerConfig, retryAfter: Duration?, at: Long)

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
        // Those the walk keeps out for their limits aren't asked: each request would be refused, and count against them.
        val keptOut = limits.keptOut(all, at)
        val asked = all.filter { it.id !in keptOut }
        log.info("download {}: searching for its releases, {} indexer{} asked, {} kept out", row.id, asked.size, if (asked.size == 1) "" else "s", keptOut.size)
        val result = if (asked.isEmpty()) {
            null
        } else {
            find(facts.request(season, episode), asked, context()).filterIsInstance<FinderEvent.Finished>().firstOrNull()?.result
        }
        val reports = result?.stages.orEmpty().flatMap { it.indexers }
        val limited = reports.mapNotNull { report -> (report.error as? NewznabError.ApiLimitReached)?.let { report.indexer to it.retryAfter } }.distinctBy { it.first.id }
        limited.forEach { (indexer, retryAfter) -> limits.answered(indexer, retryAfter, at) }
        val found = result?.playable.orEmpty().map { RankedItem(it.item, it.ranked) }.filter { it.item.infoHash == null && it.key !in leaveOut }
        if (found.isEmpty()) {
            // A limit met now is the wait, its start now (L434); else one the walk keeps an indexer out for.
            val wait = limited.firstOrNull()?.let { (indexer, retryAfter) -> LimitWait(indexer.id, LimitWait.Kind.Search, at, retryAfter?.let { at + it.toMillis() }) }
                ?: keptOut.values.firstOrNull()
            return when {
                wait != null -> {
                    log.info("download {}: nothing found, an indexer's search limit reached", row.id)
                    Searched.Limited(wait)
                }
                reports.any { it.error?.isOutage() == true } -> {
                    log.info("download {}: nothing found, an indexer out of reach", row.id)
                    Searched.Out
                }
                else -> {
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
         * The app's: the episode finder over the Usenet indexers, ranked for downloading, in the room the downloads' storage
         * gives, the indexers' limits kept in the walk's memory ([limits]: the runner's).
         */
        fun of(container: AppContainer, storage: DownloadStorage, limits: Limits = Limits.None): DownloadSearch = DownloadSearch(
            downloads = container.data.downloads,
            find = { request, indexers, context -> container.finder.find(request, indexers, context) },
            indexers = { DownloadSheetDeps.usenetOnly(container.indexerConfigs()) },
            context = container::downloadRankingContext,
            space = { id -> storage.space(id) },
            limits = limits,
        )
    }
}

/**
 * The walk's memory of the indexers' limits (L443), the runner's, one a process: each indexer that answered a turn's search
 * with its limit is kept out of the later turns' searches until the limit's end, where it said one (its Retry-After), else
 * for 30 minutes from its answer ([LimitWait.end]). Keyed as [MultiIndexerSearch] keys a limit, by the indexer's address and
 * key too, so another account at the same indexer is another limit. A play's search keeps none of it, and Resume forgets
 * it all ([forget]), so a user's Resume asks again at once. Safe from any thread.
 */
class DownloadLimits : DownloadSearch.Limits {
    private val lock = Any()

    /** The limits kept, by [MultiIndexerSearch.limitKey]. */
    private val kept = HashMap<String, LimitWait>()

    override fun keptOut(indexers: List<IndexerConfig>, at: Long): Map<String, LimitWait> = synchronized(lock) {
        kept.values.removeAll { it.end() <= at }
        indexers.mapNotNull { indexer -> kept[MultiIndexerSearch.limitKey(indexer)]?.let { indexer.id to it } }.toMap()
    }

    override fun answered(indexer: IndexerConfig, retryAfter: Duration?, at: Long) = synchronized(lock) {
        // Each answer afresh, its start included: one met again waits its time again (L443).
        kept[MultiIndexerSearch.limitKey(indexer)] = LimitWait(indexer.id, LimitWait.Kind.Search, at, retryAfter?.let { at + it.toMillis() })
    }

    /** Forgets every limit kept: Resume asks the indexers again (L434). */
    fun forget() = synchronized(lock) { kept.clear() }
}

private val log = LoggerFactory.getLogger(DownloadSearch::class.java)
