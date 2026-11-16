package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.NetworkNow
import io.skjaere.usenet.app.play.MovieTarget
import io.skjaere.usenet.app.search.MovieFind
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadPrefs
import io.skjaere.usenet.data.PlaybackPrefs
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.releases.ReleaseRanker
import io.skjaere.usenet.releases.ReleaseReason
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import io.skjaere.usenet.search.EpisodeRequest
import io.skjaere.usenet.search.FindResult
import io.skjaere.usenet.search.FinderEvent
import io.skjaere.usenet.search.FoundRelease
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.SearchStage
import io.skjaere.usenet.search.SeasonFind
import io.skjaere.usenet.search.SeasonRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDate

/**
 * What a Download sheet reads and asks, faked, for the one episode's or film's sheet and the season's alike; what it
 * asked recorded. The searches are seams: [finder] and [films] for one title, [seasons] for a season's one search.
 */
internal class SheetFakes {
    var finder: (EpisodeRequest, RankingContext) -> Flow<FinderEvent> = { request, _ -> flowOf(SheetFixtures.finished(request, emptyList())) }
    var films: (MovieTarget, RankingContext) -> Flow<MovieFind> = { _, _ -> flowOf(MovieFind(null, emptyList())) }

    /** A season's search: by default nothing for any episode asked. */
    var seasons: suspend (SeasonRequest, RankingContext) -> SeasonFind = { request, _ ->
        SeasonFind(request.episodes.associate { it.number to FindResult(request.request(it), emptyList(), emptyList(), emptyList()) }, emptyList())
    }
    var space: DownloadSpace? = DownloadSpace(available = 15 * SheetFixtures.GB, used = 0, queued = 0)
    var rows: List<Download> = emptyList()
    var prefs = DownloadPrefs()
    var network = SheetFixtures.WIFI
    var charging = false
    var ask: NotificationAsk? = null

    /** Whether the runner queues it: not on a TV, where it queues nothing (L369). */
    var queues = true
    val contexts = mutableListOf<RankingContext>()
    val calls = mutableListOf<String>()
    val enqueued = mutableListOf<Pair<DownloadTarget, List<DownloadCandidate>>>()

    /** What each download queued was counted as needing until it is resolved. */
    val needs = mutableListOf<Long>()

    /** What each season's search asked, and what each Download of a season queued, its episodes in the order given. */
    val seasonRequests = mutableListOf<SeasonRequest>()
    val enqueuedAll = mutableListOf<List<DownloadRunner.QueueItem>>()

    fun deps(outlive: CoroutineScope) = DownloadSheetDeps(
        findEpisode = { request, context ->
            contexts += context
            finder(request, context)
        },
        findMovie = { movie, context ->
            contexts += context
            films(movie, context)
        },
        findSeason = { request, context ->
            contexts += context
            seasonRequests += request
            seasons(request, context)
        },
        context = { SheetFixtures.DOWNLOADING },
        space = { space },
        rows = { rows },
        prefs = { prefs },
        network = { network },
        charging = { charging },
        askDue = { ask?.due() == true },
        enqueue = { target, candidates, need ->
            calls += "enqueue"
            enqueued += target to candidates
            needs += need
            queued(target, candidates).takeIf { queues }
        },
        enqueueAll = { items ->
            calls += "enqueueAll"
            enqueuedAll += items
            if (queues) items.map { queued(it.target, it.candidates) } else emptyList()
        },
        beforeDownload = {
            calls += "ask"
            ask?.beforeDownload()
        },
        schedule = { calls += "schedule" },
        outlive = outlive,
        today = { SheetFixtures.TODAY },
    )

    private fun queued(target: DownloadTarget, candidates: List<DownloadCandidate>) = Download(
        id = "queued", watchKey = target.watchKey, kind = target.kind, title = target.title, position = 0, candidates = candidates,
        indexerSize = candidates.firstOrNull()?.size ?: 0, createdAt = 0, updatedAt = 0,
    )
}

/** What the sheets' tests share: sizes, networks, the download ranking, invented releases and how a search ranks them. */
internal object SheetFixtures {
    const val GB = 1_000_000_000L
    val TODAY: LocalDate = LocalDate.of(2026, 11, 15)
    val WIFI = NetworkNow(usable = true, unmetered = true, wifi = true)
    val ETHERNET = NetworkNow(usable = true, unmetered = true, wifi = false)
    val MOBILE = NetworkNow(usable = true, unmetered = false, wifi = false)

    val PHONE = StaticDeviceCapabilities(displayHeight = 1080)
    val DOWNLOADING: RankingContext = DownloadRanking.context(PHONE, PlaybackPrefs(), DownloadPrefs(), "en")
    val PLAYING = RankingContext(PHONE, PlaybackPrefs().rankingPrefs("en"))

    fun nzb(title: String, size: Long, indexer: String = "idx-a", hash: String? = null, passworded: Boolean = false, grabs: Int? = null) = NewznabItem(
        indexerId = indexer, guid = title, title = title, sizeBytes = size, publishedAt = null, postedAt = null, categories = emptyList(), grabs = grabs,
        passworded = passworded, files = null, tvdbId = null, tmdbId = null, imdbId = null, tvmazeId = null, season = null, episode = null, coverUrl = null,
        mediaInfo = null, downloadTemplate = "https://indexer.invalid/api?t=get&id=$title&apikey={apikey}".takeIf { hash == null }, attributes = emptyMap(),
        infoHash = hash,
    )

    /** [item] as a ranking placed it, by [score]: playable, with [reasons] to say of it. */
    fun ranked(item: NewznabItem, score: Double, reasons: List<ReleaseReason> = emptyList()) = RankedItem(
        item,
        RankedRelease(
            ReleaseFacts(item.key, item.title, item.sizeBytes, source = if (item.infoHash != null) ReleaseFacts.TORRENT else ReleaseFacts.USENET),
            ReleaseParser.parse(item.title), playable = true, score = score, reasons = reasons, estimatedBitrate = null,
        ),
    )

    /** [items] as the ranker ranks them by [context], playable first. */
    fun byRanker(items: List<NewznabItem>, context: RankingContext): List<RankedItem> {
        val held = items.associateBy { it.key }
        return ReleaseRanker().rank(items.map { ReleaseFacts(it.key, it.title, it.sizeBytes, grabs = it.grabs) }, context).map { RankedItem(held.getValue(it.facts.id), it) }
    }

    fun finished(request: EpisodeRequest, found: List<FoundRelease>) = FinderEvent.Finished(FindResult(request, found, emptyList(), emptyList()))

    /** A finder that found [items], in this order. */
    fun inOrder(vararg items: NewznabItem): (EpisodeRequest, RankingContext) -> Flow<FinderEvent> = { request, _ ->
        flowOf(finished(request, items.mapIndexed { i, item -> ranked(item, 100.0 - i).let { FoundRelease(it.item, it.ranked, SearchStage.Episode) } }))
    }
}
