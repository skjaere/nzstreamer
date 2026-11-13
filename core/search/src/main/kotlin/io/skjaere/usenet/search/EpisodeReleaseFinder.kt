package io.skjaere.usenet.search

import io.skjaere.usenet.newznab.IndexerCaps
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.IndexerOutcome
import io.skjaere.usenet.newznab.MultiIndexerSearch
import io.skjaere.usenet.newznab.NewznabError
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.ReleaseSearch
import io.skjaere.usenet.newznab.SearchQuery
import io.skjaere.usenet.newznab.SearchType
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.ParsedRelease
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseKey
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.releases.ReleaseRanker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.Locale

/**
 * The show as the finder needs it; [aliases] are other names releases may use (original title, ...), and
 * [originalLanguage] (ISO 639-1, from TMDB) is the one its releases without a language tag speak.
 */
data class ShowRef(
    val name: String,
    val year: Int? = null,
    val aliases: List<String> = emptyList(),
    val ids: TvIds = TvIds(),
    val originalLanguage: String? = null,
)

data class EpisodeRequest(
    val show: ShowRef,
    val season: Int,
    val episode: Int,
    /** For date-named releases of daily shows. */
    val airDate: LocalDate? = null,
    val runtimeMinutes: Int? = null,
    /** How many episodes TMDB lists for the season: a season pack's size over it is an episode's, for its bitrate. */
    val seasonEpisodes: Int? = null,
)

/**
 * The release that played this episode last time, tried first: the search's release of the same [key] ([ReleaseKey], a
 * search result's [FoundRelease.key]); by default its indexer's and guid's.
 */
data class RememberedPick(
    val indexerId: String,
    val guid: String,
    val title: String,
    val downloadTemplate: String?,
    val key: String = ReleaseKey.of(indexerId, guid),
)

enum class SearchStage(val label: String) {
    Episode("the episode"),
    AirDate("the episode by air date"),
    SeasonPack("season packs"),
    ByName("the episode by name"),
}

data class FoundRelease(
    val item: NewznabItem,
    val ranked: RankedRelease,
    val stage: SearchStage,
    val remembered: Boolean = false,
) {
    val key: String get() = item.key
}

/** What one indexer did in one stage; the "no releases" sheet explains failures from these. */
data class IndexerReport(
    val indexer: IndexerConfig,
    /** How it was asked, e.g. "TVDB id" or "name". */
    val by: String?,
    val found: Int = 0,
    val accepted: Int = 0,
    val error: NewznabError? = null,
    val skipped: String? = null,
)

data class StageReport(val stage: SearchStage, val indexers: List<IndexerReport>) {
    val found: Int get() = indexers.sumOf { it.found }
    val accepted: Int get() = indexers.sumOf { it.accepted }
}

data class FindResult(
    val request: EpisodeRequest,
    /** Ranked; the play pipeline tries them in this order. */
    val playable: List<FoundRelease>,
    /** Right episode, but this device can't play them (shown as "N hidden: reason"). */
    val hidden: List<FoundRelease>,
    val stages: List<StageReport>,
)

sealed interface FinderEvent {
    data class StageStarted(val stage: SearchStage, val indexers: Int) : FinderEvent
    /**
     * [report]: what the indexer did in [stage]. [acceptedKeys]: the releases it accepted there ([FoundRelease.key]), by
     * which those another search accepted too (a season search lists the season's episodes) are counted once.
     */
    data class IndexerAnswered(val stage: SearchStage, val report: IndexerReport, val acceptedKeys: List<String> = emptyList()) : FinderEvent
    data class Finished(val result: FindResult) : FinderEvent
    data class Joined(val result: FindResult) : FinderEvent
}

/**
 * Finds releases of one episode across the user's indexers:
 *
 * 1. **Episode**: `t=tvsearch` with season and episode, by the best id each indexer supports
 *    (TVDB > TMDB > IMDb > TVmaze), else by name.
 * 2. **Air date**, once the episode search is done, only when the request has one and that search
 *    accepted no single-episode release this device can play: a daily show's releases are named by
 *    date ("Show.2026.10.12"), and indexers find them by `season=YYYY&ep=MM/DD` or the date in the
 *    text, not by TMDB's numbers.
 * 3. **Season packs**, always, at the same time as the episode search: the season without an
 *    episode number; the pipeline plays the matching file inside the pack. One more search per
 *    indexer and play, which [MultiIndexerSearch] keeps the answer to a while.
 * 4. **By name**, only when the id searches found nothing at all: TMDB and TVDB number some
 *    shows differently (split seasons, specials), and some releases aren't linked to an id.
 *
 * Every result is checked against its parsed name: the right season and episode (or a pack of
 * the season), and, unless the indexer matched it by id, the show's name and year. Packs and
 * single episodes are ranked together: a better pack beats a worse single, and a single wins at
 * about equal standing ([ReleaseRanker] gives a pack a small cost). The stages' reports come in
 * the order above.
 *
 * Safe to collect on the main thread: searching, checking and ranking (a release-name parse
 * per result) run on [defaultDispatcher].
 */
class EpisodeReleaseFinder(
    private val search: ReleaseSearch,
    private val ranker: ReleaseRanker = ReleaseRanker(),
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val log = LoggerFactory.getLogger(EpisodeReleaseFinder::class.java)

    fun find(
        request: EpisodeRequest,
        indexers: List<IndexerConfig>,
        context: RankingContext,
        remembered: RememberedPick? = null,
    ): Flow<FinderEvent> = channelFlow {
        val ranking = context.copy(
            runtimeMinutes = request.runtimeMinutes ?: context.runtimeMinutes,
            originalLanguage = request.show.originalLanguage ?: context.originalLanguage,
            seasonEpisodes = request.seasonEpisodes ?: context.seasonEpisodes,
        )
        val enabled = indexers.count { it.enabled }
        val order = indexers.map { it.id }
        // Guards every stage's accepted releases and [finished], and keeps the results sent in the order they were made.
        val lock = Mutex()
        val stages = mutableListOf<Stage>()
        var finished = false

        /**
         * One stage's search, once its start is sent: its report, and the results it accepted, once every indexer has
         * answered; a torrent indexer has once it says its torrents are being checked. Those join the stage as they
         * settle, after it has returned: once the finder is done, each time as [FinderEvent.Joined].
         */
        suspend fun stage(stage: SearchStage, plan: (IndexerCaps) -> Pair<SearchQuery, String>?): Stage {
            val reports = mutableListOf<IndexerReport>()
            val found = Stage(stage)
            val answered = CompletableDeferred<Unit>()
            // How each torrent indexer was asked, for its torrents as they settle.
            val byName = mutableMapOf<String, Boolean>()
            launch {
                search.search(indexers) { caps -> plan(caps)?.first }.collect { outcome ->
                    if (outcome is IndexerOutcome.Checked) {
                        val joined = outcome.items.filter { accepts(it, request, byName[outcome.indexer.id] == true) }
                        if (joined.isNotEmpty()) lock.withLock {
                            found.accepted += joined
                            if (finished) send(FinderEvent.Joined(result(request, ranking, order, remembered, stages)))
                        }
                        return@collect
                    }
                    var matches = emptyList<NewznabItem>()
                    val report = when (outcome) {
                        is IndexerOutcome.Results -> {
                            // Trust depends on how this indexer was asked: an id search is the
                            // indexer's own match, a name search can return any similar title.
                            val by = describe(outcome.query)
                            matches = outcome.page.items.filter { accepts(it, request, byName = by == "name") }
                            lock.withLock { found.accepted += matches }
                            IndexerReport(outcome.indexer, by, outcome.page.items.size, matches.size)
                        }
                        is IndexerOutcome.Checking -> {
                            // Its torrents count as found and accepted now; they are results once checked.
                            val by = describe(outcome.query)
                            byName[outcome.indexer.id] = by == "name"
                            matches = outcome.found.items.filter { accepts(it, request, byName = by == "name") }
                            IndexerReport(outcome.indexer, by, outcome.found.items.size, matches.size)
                        }
                        is IndexerOutcome.Failed -> IndexerReport(outcome.indexer, null, error = outcome.error)
                        is IndexerOutcome.Skipped -> IndexerReport(outcome.indexer, null, skipped = outcome.reason)
                        is IndexerOutcome.Checked -> error("handled above")
                    }
                    reports += report
                    send(FinderEvent.IndexerAnswered(stage, report, matches.map { it.key }))
                    if (reports.size >= enabled) answered.complete(Unit)
                }
                answered.complete(Unit)
            }
            answered.await()
            found.report = StageReport(stage, reports.toList())
            return found
        }

        // Singles the ranker blocks (a codec this device can't decode, passworded, ...) don't make the air-date search unnecessary.
        suspend fun noPlayableSingle(stage: Stage): Boolean {
            val singles = lock.withLock { stage.accepted.toList() }.filterNot { parse(it).isSeasonPack }
            return ranker.rank(singles.map { it.toFacts() }, ranking).none { it.playable }
        }

        // Each search keeps what it found to itself while the two run at once; they are put together once both are done.
        send(FinderEvent.StageStarted(SearchStage.Episode, enabled))
        send(FinderEvent.StageStarted(SearchStage.SeasonPack, enabled))
        val packs = async { stage(SearchStage.SeasonPack) { caps -> episodeQuery(caps, request, season = true, episode = false) } }
        val episode = stage(SearchStage.Episode) { caps -> episodeQuery(caps, request, season = true, episode = true) }
        val airDate = request.airDate?.takeIf { noPlayableSingle(episode) }?.let { date ->
            send(FinderEvent.StageStarted(SearchStage.AirDate, enabled))
            stage(SearchStage.AirDate) { caps -> airDateQuery(caps, request, date) }
        }
        val seasonPacks = packs.await()
        lock.withLock { stages += listOfNotNull(episode, airDate, seasonPacks) }
        // Torrents still being checked were accepted, by their names, but aren't results yet: they count as found.
        val idSearchesFoundNothing = stages.all { it.report.found == 0 } && request.show.ids != TvIds()
        if (stages.all { it.report.accepted == 0 } && idSearchesFoundNothing) {
            send(FinderEvent.StageStarted(SearchStage.ByName, enabled))
            val byName = stage(SearchStage.ByName) { caps -> nameQuery(caps, request) }
            lock.withLock { stages += byName }
        }

        lock.withLock {
            val result = result(request, ranking, order, remembered, stages)
            log.info(
                "find {} S{}E{}: {} playable, {} hidden; {}", request.show.name, request.season, request.episode,
                result.playable.size, result.hidden.size,
                result.stages.joinToString { "${it.stage}: ${it.accepted}/${it.found}" },
            )
            send(FinderEvent.Finished(result))
            finished = true
        }
    }.flowOn(defaultDispatcher)

    /**
     * What [stages] found, as one ranked list: a release more than one search found put down to the first of them, in the
     * stages' order, and listed by several indexers kept once ([oneOfEach], by [order]); packs and single episodes in the
     * ranker's one order; the [remembered] release first when the search returned it.
     */
    private fun result(request: EpisodeRequest, ranking: RankingContext, order: List<String>, remembered: RememberedPick?, stages: List<Stage>): FindResult {
        val found = stages.flatMap { s -> s.accepted.map { it to s.stage } }
        val unique = found.map { it.first }.oneOfEach(order)
        val stageOf = found.reversed().associate { (item, stage) -> item.key to stage }
        // Packs and single episodes in one order: the ranker's, a pack's cost included.
        val byKey = unique.associateBy { it.key }
        val ordered = ranker.rank(unique.map { it.toFacts() }, ranking).map { r ->
            FoundRelease(byKey.getValue(r.facts.id), r, stageOf.getValue(r.facts.id))
        }
        // The remembered release is promoted when the search returned it, by its key; when it didn't (the
        // indexer dropped it, or no longer lists it), the caller can still try it first by guid.
        val withRemembered = remembered?.let { pick ->
            val (hit, rest) = ordered.partition { it.key == pick.key }
            hit.map { it.copy(remembered = true) } + rest
        } ?: ordered
        return FindResult(
            request = request,
            playable = withRemembered.filter { it.ranked.playable },
            hidden = withRemembered.filterNot { it.ranked.playable },
            stages = stages.map { it.report },
        )
    }

    /**
     * What one [stage]'s search did, once every indexer answered ([report]), and the results it accepted: a torrent
     * indexer's join [accepted] as their checks settle.
     */
    private class Stage(val stage: SearchStage) {
        lateinit var report: StageReport
        val accepted = mutableListOf<NewznabItem>()
    }

    private fun parse(item: NewznabItem): ParsedRelease = ReleaseParser.parse(item.title)

    /** The same in every stage: a season search lists the season's episodes too, the requested one among them. */
    internal fun accepts(item: NewznabItem, request: EpisodeRequest, byName: Boolean): Boolean {
        val parsed = parse(item)
        val rightContent = matchesEpisode(parsed, request) || (parsed.isSeasonPack && request.season in parsed.seasons)
        return rightContent && matchesShow(item, parsed, request.show, trustIndexer = !byName)
    }

    private fun matchesEpisode(parsed: ParsedRelease, request: EpisodeRequest): Boolean =
        (request.airDate != null && parsed.airDate == request.airDate) ||
            (parsed.seasons.firstOrNull() == request.season && request.episode in parsed.episodes)

    /**
     * Ids the indexer attached decide; without them, a result from an id search is trusted
     * (the indexer matched it), and a result from a name search must match name and year.
     */
    internal fun matchesShow(item: NewznabItem, parsed: ParsedRelease, show: ShowRef, trustIndexer: Boolean): Boolean {
        val ids = show.ids
        listOf(item.tvdbId to ids.tvdb, item.tmdbId to ids.tmdb, item.tvmazeId to ids.tvmaze, item.imdbId to ids.imdb)
            .firstOrNull { (theirs, ours) -> theirs != null && ours != null }
            ?.let { (theirs, ours) -> return theirs == ours }
        if (trustIndexer) return true
        return titleMatches(parsed, show)
    }

    internal fun titleMatches(parsed: ParsedRelease, show: ShowRef): Boolean = TitleMatcher.matches(parsed, show.name, show.year, show.aliases)

    private fun describe(query: SearchQuery): String = when {
        query is SearchQuery.Tv && query.ids.tvdb != null -> "TVDB id"
        query is SearchQuery.Tv && query.ids.tmdb != null -> "TMDB id"
        query is SearchQuery.Tv && query.ids.imdb != null -> "IMDb id"
        query is SearchQuery.Tv && query.ids.tvmaze != null -> "TVmaze id"
        else -> "name"
    }

    private fun NewznabItem.toFacts() = ReleaseFacts(
        id = key,
        name = title,
        sizeBytes = sizeBytes,
        passworded = passworded,
        grabs = grabs,
        ageDays = postedAt?.let { java.time.Duration.between(it, java.time.Instant.now()).toDays().toInt() },
        mediaInfo = mediaInfo,
        source = if (infoHash != null) ReleaseFacts.TORRENT else ReleaseFacts.USENET,
        availability = availability,
    )

    companion object {
        /** The id each indexer can search by, best first, with how it reads in reports. */
        private fun bestId(caps: IndexerCaps, ids: TvIds): Pair<TvIds, String>? = listOfNotNull(
            ids.tvdb?.let { Triple("tvdbid", TvIds(tvdb = it), "TVDB id") },
            ids.tmdb?.let { Triple("tmdbid", TvIds(tmdb = it), "TMDB id") },
            ids.imdb?.let { Triple("imdbid", TvIds(imdb = it), "IMDb id") },
            ids.tvmaze?.let { Triple("tvmazeid", TvIds(tvmaze = it), "TVmaze id") },
        ).firstOrNull { (param, _, _) -> caps.supports(SearchType.Tv, param) }?.let { (_, id, by) -> id to by }

        internal fun episodeQuery(caps: IndexerCaps, request: EpisodeRequest, season: Boolean, episode: Boolean): Pair<SearchQuery, String>? {
            val s = request.season.takeIf { season }
            val e = request.episode.takeIf { episode }
            bestId(caps, request.show.ids)?.let { (ids, by) -> return SearchQuery.Tv(ids = ids, season = s, episode = e) to by }
            return nameQuery(caps, request, season = s, episode = e)
        }

        /**
         * The episode by the day it aired: `season=YYYY&ep=MM/DD` by the best id, or by name, where
         * tv-search takes season and ep; else the date in a text search ("Show 2026 10 12").
         */
        internal fun airDateQuery(caps: IndexerCaps, request: EpisodeRequest, airDate: LocalDate): Pair<SearchQuery, String>? {
            val name = request.show.name
            if (caps.supports(SearchType.Tv, "season") && caps.supports(SearchType.Tv, "ep")) {
                bestId(caps, request.show.ids)?.let { (ids, by) -> return SearchQuery.Tv(ids = ids, airDate = airDate) to by }
                if (caps.supports(SearchType.Tv, "q")) return SearchQuery.Tv(query = name, airDate = airDate) to "name"
            }
            val text = "%s %d %02d %02d".format(Locale.ROOT, name, airDate.year, airDate.monthValue, airDate.dayOfMonth)
            return when {
                caps.supports(SearchType.Search, "q") || caps.supports(SearchType.Search) -> SearchQuery.Text(text) to "name"
                caps.supports(SearchType.Tv, "q") -> SearchQuery.Tv(query = text) to "name"
                else -> null
            }
        }

        internal fun nameQuery(caps: IndexerCaps, request: EpisodeRequest, season: Int? = request.season, episode: Int? = request.episode): Pair<SearchQuery, String>? {
            val name = request.show.name
            return when {
                caps.supports(SearchType.Tv, "q") ->
                    SearchQuery.Tv(query = name, season = season.takeIf { caps.supports(SearchType.Tv, "season") }, episode = episode.takeIf { caps.supports(SearchType.Tv, "ep") }) to "name"
                caps.supports(SearchType.Search, "q") || caps.supports(SearchType.Search) -> {
                    val suffix = listOfNotNull(season?.let { "S%02d".format(Locale.ROOT, it) }, episode?.let { "E%02d".format(Locale.ROOT, it) }).joinToString("")
                    SearchQuery.Text(listOf(name, suffix).filter { it.isNotEmpty() }.joinToString(" ")) to "name"
                }
                else -> null
            }
        }
    }
}
