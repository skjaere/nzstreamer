package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.search.ReleaseListMode
import io.skjaere.usenet.core.formatSize
import io.skjaere.usenet.releases.ReleaseReason
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.SeasonEpisode
import io.skjaere.usenet.search.SeasonRequest
import io.skjaere.usenet.search.StageReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * What a season's Download sheet is for (W114, L433), from the Show page's own answer, with no TMDB call of its own: the
 * show's season [season], named [title], whose aired [episodes] not yet downloaded it lists in episode order, each with
 * its play (the show's names and ids, its date, length and season's count) and whether its download failed before
 * (L396); the episodes it counts apart by number, [downloaded] already, [queued] already (a download still to finish),
 * and [notAired] yet; and the show's poster, which each row keeps.
 */
data class SeasonDownloadRequest(
    val title: String,
    val season: Int,
    val episodes: List<Listed>,
    val downloaded: List<Int> = emptyList(),
    val queued: List<Int> = emptyList(),
    val notAired: List<Int> = emptyList(),
    val posterPath: String? = null,
) {
    init {
        require(episodes.isNotEmpty()) { "A season's sheet lists an episode at least" }
    }

    /** An episode the sheet lists: its [play], as the Show page plays it, and whether its download [failedBefore] (L396). */
    data class Listed(val play: EpisodePlay, val failedBefore: Boolean = false)

    /** "Harbor Lights · Season 2": under the sheet's title while it finds, and when nothing is found. */
    val subtitle: String get() = "$title · Season $season"

    /** The season's one search (L393): the show as a play of its episodes asks for it, each listed episode's date and length. */
    val search: SeasonRequest
        get() {
            val first = episodes.first().play
            return SeasonRequest(first.request.show, season, episodes.map { SeasonEpisode(it.play.episode, it.play.airDate, it.play.runtimeMinutes) }, first.seasonEpisodes)
        }

    /** What [episode]'s download is of, as its row keeps it: as the episode's own sheet names it, with the show's poster. */
    fun target(episode: Listed): DownloadTarget = DownloadRequest.Episode(episode.play, posterPath).target
}

/**
 * A listed episode as the season's search found it: its Usenet [releases], ranked for downloading as its own sheet ranks
 * them (none: nothing found yet, and its turn searches again), and what the first [need]s here, its size or a season
 * pack's share of the episode; null with none.
 */
data class SeasonEpisodeFound(val listed: SeasonDownloadRequest.Listed, val releases: List<RankedItem>, val need: Long?) {
    val best: RankedItem? get() = releases.firstOrNull()

    /**
     * The Download size its first release is above: the ranking tries those after every one that fits it, so with the
     * first above it every one is (W112). Above it, an episode is still queued, its releases tried as they are.
     */
    val aboveCap: ReleaseReason.AboveDownloadSize? get() = best?.ranked?.reasons?.firstNotNullOfOrNull { it as? ReleaseReason.AboveDownloadSize }
}

/** The episodes a season's sheet counts apart, each kind on a line of its own, with its mark. */
enum class SeasonApart { Downloaded, Queued, NotAired }

/** What a season's Download sheet shows: [Finding] while it searches, then [Pick], [WontFit] or [NoRelease]. */
sealed interface SeasonSheetState {
    /** The season's one search, ranked for downloading: nothing is grabbed until each episode's turn. */
    data object Finding : SeasonSheetState

    /**
     * Every listed [episodes] fits what is [free] for new downloads, by the sizes known: Download queues them all. When the
     * first would [start], and whether the sheet says that Android asks about notifications next ([asks], L358).
     */
    data class Pick(val episodes: List<SeasonEpisodeFound>, val free: Long, val start: DownloadStart, val asks: Boolean) : SeasonSheetState

    /**
     * Not all fit (L400): the first [fit] of [episodes] in episode order do, by the sizes known, in what is [free], which
     * is after the [queued] bytes the [queuedDownloads] queued downloads still need and what is kept free for the phone.
     * With one or more, Download queues those, and [asks] where Android asks next; with none, no Download.
     */
    data class WontFit(
        val episodes: List<SeasonEpisodeFound>,
        val fit: Int,
        val free: Long,
        val queued: Long,
        val queuedDownloads: Int,
        val asks: Boolean,
    ) : SeasonSheetState

    /** No Usenet release for any listed episode: what the season's search of the indexers did ([stages]). */
    data class NoRelease(val stages: List<Pair<String, String>>) : SeasonSheetState
}

/**
 * A season's Download sheet's model (W114, L392, L393, L396, L400): one search for the season over the Usenet indexers,
 * ranked for downloading, each listed episode's releases split from it, and what that comes to on this device.
 * - [SeasonSheetState.Pick]: all fit, with the space free and when the first starts.
 * - [SeasonSheetState.WontFit]: the longest run of them in episode order that fits; none, no Download.
 * - [SeasonSheetState.NoRelease]: nothing to download for any of them, with the search's line.
 *
 * Torrents are left out (L370). An episode with nothing found yet is listed and queued all the same: its turn searches
 * for it. [download] queues each episode once, in episode order ([DownloadRunner.enqueueAll], which puts a failed one's
 * row back), with its releases that fit, the first's need and its search facts, and the sheet is [closed]; then
 * Android's notification ask where it is due, then the host's schedule. The search runs in [scope], the sheet's.
 */
class DownloadSeasonSheetModel(private val request: SeasonDownloadRequest, private val deps: DownloadSheetDeps, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<SeasonSheetState>(SeasonSheetState.Finding)
    val state: StateFlow<SeasonSheetState> = _state.asStateFlow()

    private val _downloading = MutableStateFlow(false)

    /** Whether a Download is under way: the sheet's buttons wait for it. */
    val downloading: StateFlow<Boolean> = _downloading.asStateFlow()

    private val _closed = MutableStateFlow(false)

    /** Whether the sheet is done: the season queued. Its ask and its host's schedule go on after, as one episode's do. */
    val closed: StateFlow<Boolean> = _closed.asStateFlow()

    private var started = false

    /** What the search found, which [shown] weighs again; null while it finds. */
    private var found: List<SeasonEpisodeFound>? = null

    /** What the search said of its indexers, for [SeasonSheetState.NoRelease]. */
    private var said = SeasonSheetState.NoRelease(emptyList())

    /** Searches, once. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            val episodes = search()
            found = episodes
            _state.value = decide(episodes)
        }
    }

    /**
     * The sheet is in front again, maybe from Android's storage page (Won't fit's Storage settings): what was found is
     * weighed again against the space as it is now, with no new search, so room made there offers more.
     */
    fun shown() {
        val episodes = found ?: return
        if (_downloading.value || _closed.value) return
        scope.launch {
            val now = decide(episodes)
            if (!_downloading.value && !_closed.value) _state.value = now
        }
    }

    /**
     * The sheet's Download: every listed episode, or Won't fit's first that fit, queued at once in episode order, then the
     * sheet closes, Android is asked about notifications where that is due, and the host is scheduled (L358). Once.
     * Where the runner queues nothing (a TV's, or every episode queued meanwhile), nothing is asked or scheduled.
     */
    fun download() {
        val (episodes, free) = when (val s = _state.value) {
            is SeasonSheetState.Pick -> s.episodes to s.free
            is SeasonSheetState.WontFit -> s.episodes.take(s.fit) to s.free
            else -> return
        }
        if (episodes.isEmpty() || _downloading.value || _closed.value) return
        _downloading.value = true
        deps.outlive.launch {
            try {
                if (deps.enqueueAll(queueItems(episodes, free)).isEmpty()) return@launch
                _closed.value = true
                deps.beforeDownload()
                deps.schedule()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Couldn't queue the season's downloads: {}", e::class.simpleName)
            } finally {
                _closed.value = true
            }
        }
    }

    /**
     * The season's one search (L393), ranked for downloading: each listed episode's Usenet releases, as the finder ranked
     * them with its length and its season's count, and what the first needs. A search that fails finds nothing.
     */
    private suspend fun search(): List<SeasonEpisodeFound> {
        val find = try {
            deps.findSeason(request.search, deps.context())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Couldn't search for a season's downloads: {}", e::class.simpleName)
            null
        }
        said = SeasonSheetState.NoRelease(find?.let { stages(request.season, it.stages) }.orEmpty())
        val seasonEpisodes = request.episodes.first().play.seasonEpisodes
        return request.episodes.map { e ->
            val found = find?.byEpisode?.get(e.play.episode)?.playable.orEmpty()
            val releases = found.filter { it.item.infoHash == null }.map { RankedItem(it.item, it.ranked) }
            SeasonEpisodeFound(e, releases, releases.firstOrNull()?.let { ReleaseListMode.Download.need(it, seasonEpisodes) })
        }
    }

    /** What the sheet shows for [episodes], found: whether they fit, how many do, when they start, and the ask's line. */
    private suspend fun decide(episodes: List<SeasonEpisodeFound>): SeasonSheetState {
        if (episodes.all { it.best == null }) return said
        val space = deps.space()
        val free = space?.free ?: 0
        val fit = fitting(episodes, free)
        if (fit < episodes.size) {
            return SeasonSheetState.WontFit(episodes, fit, free, space?.queued ?: 0, space?.queuedDownloads ?: 0, asks = fit > 0 && deps.askDue())
        }
        val start = DownloadSheetModel.startOf(deps.rows(), deps.prefs(), deps.network(), deps.charging)
        return SeasonSheetState.Pick(episodes, free, start, deps.askDue())
    }

    /**
     * Each of [episodes] as the runner queues it (L392): its releases that fit the room it will have at its turn, 10 at
     * most, in their order; what the first needs, none with nothing found; and its search facts, with which its turn
     * searches. Its room is what is [free] less what the others queued with it need, as Resolve counts the queue's needs
     * (L401): one that wouldn't fit then would only be found too big after its grab.
     */
    private fun queueItems(episodes: List<SeasonEpisodeFound>, free: Long): List<DownloadRunner.QueueItem> {
        val total = total(episodes)
        val seasonEpisodes = request.episodes.first().play.seasonEpisodes
        return episodes.map { e ->
            val need = e.need ?: 0
            val room = free - (total - need)
            val fits = e.releases.filter { ReleaseListMode.Download.need(it, seasonEpisodes) <= room }
            DownloadRunner.QueueItem(request.target(e.listed), fits.take(KEPT).map(DownloadSheetModel::candidate), need, SeasonSearch.of(e.listed.play.request))
        }
    }

    companion object {
        const val FINDING_NOTE = DownloadSheetModel.FINDING_NOTE
        const val NOTHING_YET = "nothing found yet"
        const val FAILED_BEFORE = "Failed before · searched afresh"
        const val SEASON_FOOT = "Each episode is its own download. Its release is found again when its turn comes, so these sizes are estimates."
        const val WONT_FIT = DownloadSheetModel.WONT_FIT
        const val NONE_FITS_NOTE =
            "A smaller release may fit: each episode's ⋮ › Download shows what does. Storage settings opens Android's page for making room."
        const val NO_RELEASE_NOTE =
            "Each episode's ⋮ › Download still searches it on its own, by air date and by name too. Indexers can also number some shows differently from TMDB."

        /** The releases each download keeps (L338). */
        private const val KEPT = DownloadRunner.MAX_CANDIDATES

        /** "Finding releases for season 2…". */
        fun finding(season: Int): String = "Finding releases for season $season…"

        /** "Season 2 · 6 episodes · about 8.4 GB": the episodes listed, and what the sizes known come to ([total]). */
        fun head(season: Int, episodes: List<SeasonEpisodeFound>): String = "Season $season · ${count(episodes.size)} · about ${formatSize(total(episodes))}"

        /** What [episodes] need here by the sizes known: one with nothing found yet counts none (L400). */
        fun total(episodes: List<SeasonEpisodeFound>): Long = episodes.sumOf { it.need ?: 0 }

        /** "about 1.4 GB": what the episode's first release needs here; "nothing found yet". */
        fun size(episode: SeasonEpisodeFound): String = episode.need?.let { "about ${formatSize(it)}" } ?: NOTHING_YET

        /** Under the episode's name: that its download failed before and is searched afresh (L396); "⚠ Above your 4 GB an hour". */
        fun notes(episode: SeasonEpisodeFound): List<String> =
            listOfNotNull(FAILED_BEFORE.takeIf { episode.listed.failedBefore }, episode.aboveCap?.let { "⚠ ${it.text}" })

        /**
         * The episodes [request] counts apart, each kind by its numbers: "1 and 2 are downloaded already, so they're left
         * out.", "2 is in the queue already, so it's left out.", "6 to 8 haven't aired yet and aren't included. Nothing
         * downloads by itself when they air."
         */
        fun apart(request: SeasonDownloadRequest): List<Pair<SeasonApart, String>> = listOfNotNull(
            request.downloaded.ifEmpty { null }?.let { SeasonApart.Downloaded to "${numbers(it)} ${are(it)} downloaded already, so ${leftOut(it)}." },
            request.queued.ifEmpty { null }?.let { SeasonApart.Queued to "${numbers(it)} ${are(it)} in the queue already, so ${leftOut(it)}." },
            request.notAired.ifEmpty { null }?.let {
                SeasonApart.NotAired to if (it.size == 1) {
                    "${numbers(it)} hasn't aired yet and isn't included. Nothing downloads by itself when it airs."
                } else {
                    "${numbers(it)} haven't aired yet and aren't included. Nothing downloads by itself when they air."
                }
            },
        )

        /**
         * Episode [numbers] as the lines counted apart say them, in order: a run of three or more "1 to 6", the rest one by
         * one, the last after "and": "1 and 2", "1, 3 and 5", "1 to 3 and 5".
         */
        fun numbers(numbers: List<Int>): String {
            val runs = numbers.distinct().sorted().fold(listOf<IntRange>()) { runs, n ->
                val last = runs.lastOrNull()
                if (last != null && last.last == n - 1) runs.dropLast(1) + listOf(last.first..n) else runs + listOf(n..n)
            }
            val parts = runs.flatMap { run -> if (run.count() >= 3) listOf("${run.first} to ${run.last}") else run.map(Int::toString) }
            return if (parts.size == 1) parts.single() else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        }

        /** "41.0 GB free on this device", as one episode's sheet says it. */
        fun free(free: Long): String = DownloadSheetModel.free(free)

        /** Under the space free: that the sizes are estimates, and each episode with nothing found yet searched again at its turn. */
        fun freeNote(episodes: List<SeasonEpisodeFound>): String =
            (listOf(SEASON_FOOT) + episodes.filter { it.best == null }.map { "${name(it.listed)} is searched again at its turn." }).joinToString(" ")

        /**
         * When the season starts, and the line under that: "Starts now, one episode at a time"; "Starts on Wi-Fi, one
         * episode at a time", and why; otherwise as one download's sheet says it.
         */
        fun start(start: DownloadStart): Pair<String, String?> = when (start) {
            DownloadStart.Now -> "Starts now, one episode at a time" to null
            DownloadStart.OnWifi -> "Starts on Wi-Fi, one episode at a time" to DownloadSheetModel.start(start).second
            else -> DownloadSheetModel.start(start)
        }

        /** The Download button's words: "Download 6 episodes", "Download the first 3", "Download the first episode"; none where none fits. */
        fun download(state: SeasonSheetState): String? = when (state) {
            is SeasonSheetState.Pick -> "Download ${count(state.episodes.size)}"
            is SeasonSheetState.WontFit -> when (state.fit) {
                0 -> null
                1 -> "Download the first episode"
                else -> "Download the first ${state.fit}"
            }
            else -> null
        }

        /** "Won't all fit: 5.1 GB free on this device". */
        fun wontAllFit(free: Long): String = "Won't all fit: ${free(free)}"

        /**
         * "The first 3 fit: about 4.1 GB.", "The first fits: about 1.4 GB.", by the sizes known; a run with nothing found yet
         * for any of it, which fits as it counts no size (L400), with none: "The first fits: nothing found for it yet.".
         */
        fun firstFit(state: SeasonSheetState.WontFit): String {
            val run = state.episodes.take(state.fit)
            val what = if (run.all { it.need == null }) "nothing found for ${if (state.fit == 1) "it" else "them"} yet" else "about ${formatSize(total(run))}"
            return if (state.fit == 1) "The first fits: $what." else "The first ${state.fit} fit: $what."
        }

        /** Over the episodes after the cut: "No room for these 3"; for one, "No room for this one". */
        fun noRoom(state: SeasonSheetState.WontFit): String {
            val left = state.episodes.size - state.fit
            return if (left == 1) "No room for this one" else "No room for these $left"
        }

        /** None fits: "The first needs about 1.4 GB · 900 MB free on this device". */
        fun firstNeeds(state: SeasonSheetState.WontFit): String = "The first needs about ${formatSize(state.episodes.first().need ?: 0)} · ${free(state.free)}"

        /** "No playable release found for season 2". */
        fun noRelease(season: Int): String = "No playable release found for season $season"

        /**
         * What the season's search did ([stages]), as the play sheet's no-release says an episode's: how many indexers it
         * asked for [season] and by what, then what they answered, else their errors.
         */
        internal fun stages(season: Int, stages: List<StageReport>): List<Pair<String, String>> = stages.map { st ->
            val asked = st.indexers.size
            val by = st.indexers.mapNotNull { it.by }.distinct().joinToString(" / ")
            val failures = st.indexers.mapNotNull { it.error?.message }.distinct()
            val what = "Searched $asked indexer${if (asked == 1) "" else "s"} for season $season" + (if (by.isNotEmpty()) " by $by" else "")
            val outcome = when {
                st.accepted > 0 -> "${st.accepted} found, none playable"
                st.found > 0 -> "${st.found} results, none for season $season"
                failures.isNotEmpty() -> "⚠ " + failures.joinToString()
                else -> "Nothing yet"
            }
            what to outcome
        }

        /** How many of [episodes] fit what is [free], in episode order, by the sizes known (L400): the longest run from the first. */
        private fun fitting(episodes: List<SeasonEpisodeFound>, free: Long): Int =
            episodes.runningFold(0L) { sum, e -> sum + (e.need ?: 0) }.drop(1).takeWhile { it <= free }.size

        private fun count(episodes: Int) = "$episodes episode${if (episodes == 1) "" else "s"}"

        private fun are(numbers: List<Int>) = if (numbers.size == 1) "is" else "are"

        private fun leftOut(numbers: List<Int>) = if (numbers.size == 1) "it's left out" else "they're left out"

        /** The episode by its name, as TMDB has it; one with none by its number. */
        private fun name(episode: SeasonDownloadRequest.Listed) = episode.play.episodeName ?: "Episode ${episode.play.episode}"

        private val log = LoggerFactory.getLogger(DownloadSeasonSheetModel::class.java)
    }
}
