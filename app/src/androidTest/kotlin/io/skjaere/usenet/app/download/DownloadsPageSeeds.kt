package io.skjaere.usenet.app.download

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.NetworkNow
import io.skjaere.usenet.app.download.DownloadHostRules.ScheduleCause
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadGap
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadPrefs
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.FindResult
import io.skjaere.usenet.search.FinderEvent
import io.skjaere.usenet.search.FoundRelease
import io.skjaere.usenet.search.SearchStage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDate
import java.util.Collections

/**
 * What the Downloads page's device tests share (item 19): rows seeded through the app's own storage (the row with its
 * bookkeeping folder, and where a case needs it a small file in its video folder), the page shown over the app's runner
 * with seams for what isn't the page's (the space free, the network, Android's background limit, the host's schedule,
 * TMDB's lookup, the search, a play), and the checks: a text found by scrolling the list to it, and a text laid out whole.
 * Every row seeded is the test's own, deleted with its folders after; the downloads' jobs are cancelled before and after.
 * Titles, episodes and releases are invented.
 */
internal class DownloadsPageSeeds(private val context: Context, private val container: AppContainer) {
    private val dao = container.data.downloads
    private val storage = container.downloadStorage
    private val seeded = Collections.synchronizedList(mutableListOf<String>())

    /** What the page's seams were asked: the host's schedules, the plays, TMDB's lookups. */
    val scheduled: MutableList<ScheduleCause> = Collections.synchronizedList(mutableListOf())
    val played: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val lookUps: MutableList<Triple<Int, Int, Int>> = Collections.synchronizedList(mutableListOf())

    /** Whether Android calls the network validated, as the page's Choose release reads it. */
    val online = MutableStateFlow(true)

    /** TMDB's answer to a lookup: an episode as it lists it, or a failure. */
    var tmdbFails = false

    /** Before a case: the queue as reconcile leaves it, and none of the downloads' jobs pending. */
    fun before() = runBlocking<Unit> {
        container.downloads.runnable()
        cancelJobs()
    }

    /** After a case: every row seeded deleted with its folders, and the downloads' jobs cancelled. */
    fun after() = runBlocking<Unit> {
        seeded.toList().forEach { id -> storage.delete(id) }
        cancelJobs()
    }

    private fun cancelJobs() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) DownloadJobService.scheduler(context).cancelAll()
    }

    /** [row] seeded as the runner keeps one: its bookkeeping folder and marker, then the row; with [bytes], a file of them. */
    fun seed(row: Download, bytes: Int = 0): Download = runBlocking {
        seeded += row.id
        storage.add(row)
        if (bytes > 0) {
            val folder = storage.videoFolder(row.id) ?: error("no primary volume")
            folder.mkdirs()
            File(folder, "v.mkv").writeBytes(ByteArray(bytes) { it.toByte() })
        }
        row
    }

    /** The test's own rows, from the table now, those being deleted with them. */
    fun mine(): List<Download> = runBlocking { dao.all().first().filter { it.id in seeded } }

    fun row(id: String): Download? = runBlocking { dao.get(id) }

    /** Whether [id] is a row this test seeded. */
    fun isMine(id: String): Boolean = id in seeded

    /**
     * The page's deps: the app's runner, its calls and its rows (the test's own alone), and seams for the rest: the space
     * ([space]), the network (Wi-Fi, or mobile data where [metered]), the background limit ([restricted]), the schedule,
     * TMDB's lookup, the search ([releases] for an episode), a play, and the Undo bar's time ([undoMillis]).
     */
    fun deps(
        space: suspend () -> DownloadSpace? = { DownloadSpace(available = 42 * GB, used = 0, queued = 0) },
        metered: Boolean = false,
        restricted: Boolean = false,
        releases: List<FoundRelease> = emptyList(),
        undoMillis: Long = DownloadsDeps.of(container, context).undoMillis,
    ): DownloadsDeps {
        val app = DownloadsDeps.of(container, context)
        val wifi = NetworkNow(usable = true, unmetered = true, wifi = true)
        return app.copy(
            items = container.downloads.state.map { items -> items.filter { it.row.id in seeded } },
            space = { space() },
            prefs = flowOf(DownloadPrefs()),
            network = MutableStateFlow(if (metered) NetworkNow(usable = true) else wifi),
            online = online,
            restricted = { restricted },
            schedule = { scheduled += it },
            lookUp = { show, season, episode ->
                lookUps += Triple(show, season, episode)
                if (tmdbFails) error("TMDB didn't answer")
                EpisodePlay(
                    showTmdbId = show, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(), ids = TvIds(tmdb = show), season = season,
                    episode = episode, episodeName = "Low Water", airDate = LocalDate.of(2025, 3, 25), runtimeMinutes = 52, seasonEpisodes = 8,
                )
            },
            search = app.search.copy(
                findEpisode = { request, _ -> flowOf(FinderEvent.Finished(FindResult(request, releases, emptyList(), emptyList()))) },
                prefs = { DownloadPrefs() },
                network = { wifi },
            ),
            play = { played += it.id },
            undoMillis = undoMillis,
        )
    }

    companion object {
        const val GB = 1_000_000_000L
        const val HL = 990_717
        const val QM = 990_718
        const val CC = 990_719
        const val KESTREL = "Harbor.Lights.S02E04.Low.Water.1080p.WEB-DL.DDP5.1.H.265-KESTREL"
        const val ATMOS = "Harbor.Lights.S02E04.Low.Water.1080p.WEB-DL.DDP5.1.Atmos.H.264-TIDEPOOL"
        const val OLD = "Harbor.Lights.S02E04.720p.WEB-DL.H.264-GRP"

        /** Harbor Lights' episodes' names, by season and number. */
        val NAMES = mapOf(
            (1 to 1) to "First Light", (1 to 2) to "Pilot Boat", (1 to 3) to "The Sandbar", (2 to 1) to "Brine", (2 to 2) to "The Breakwater",
            (2 to 3) to "Fog Signal", (2 to 4) to "Low Water", (2 to 5) to "The Long Tide", (2 to 6) to "Salt and Iron",
        )

        /** An episode's row, as the runner keeps one in [state]: [written] of its [size] where its file is made ([file]). */
        fun episode(
            season: Int,
            episode: Int,
            state: DownloadState,
            show: Int = HL,
            title: String = "Harbor Lights",
            size: Long = 1_700_000_000,
            file: Boolean = state == DownloadState.Done,
            written: Long = if (state == DownloadState.Done) size else 0,
            reason: String? = null,
            tried: List<String> = emptyList(),
            runtime: Int? = null,
            gaps: Int = 0,
            position: Long = 0,
            candidates: List<String> = listOf(OLD),
        ): Download {
            val id = Download.newId()
            return Download(
                id = id, watchKey = WatchKey.episode(show, season, episode), kind = DownloadKind.Episode, title = title, showTmdbId = show, season = season,
                episode = episode, seasonEpisodes = 8, episodeName = if (show == HL) NAMES[season to episode] else null, runtimeMinutes = runtime, state = state,
                reason = reason, position = position, candidates = candidates.map { DownloadCandidate("dl-page-a", it, it, size = size) }, tried = tried,
                size = size.takeIf { file }, indexerSize = size, safeOffset = written, relativePath = if (file) "downloads/$id/v.mkv" else null,
                gaps = (0 until gaps).map { DownloadGap(it * 1_000_000L, it * 1_000_000L + 999) }, createdAt = 1, completedAt = if (state == DownloadState.Done) 1 else null,
                updatedAt = System.currentTimeMillis(),
            )
        }

        /** Copper Crown's row: 5.5 GB, 2022, 1 h 52 min, with [gaps] parts missing; finished as the episodes were, so it comes after the shows. */
        fun film(state: DownloadState = DownloadState.Done, gaps: Int = 0, position: Long = 0): Download {
            val id = Download.newId()
            return Download(
                id = id, watchKey = WatchKey.movie(CC), kind = DownloadKind.Movie, title = "Copper Crown", subtitle = "2022", movieTmdbId = CC, year = 2022,
                runtimeMinutes = 112, state = state, position = position, candidates = listOf(DownloadCandidate("dl-page-a", "cc", "Copper.Crown.2022.1080p.WEB-DL-KESTREL")),
                size = 5_500_000_000, indexerSize = 5_500_000_000, safeOffset = 5_500_000_000, relativePath = "downloads/$id/v.mkv",
                gaps = (0 until gaps).map { DownloadGap(it * 1_000_000L, it * 1_000_000L + 999) }, createdAt = 1, completedAt = 1, updatedAt = System.currentTimeMillis(),
            )
        }

        /** [title] as the finder found it, ranked [score], of [size] bytes. */
        fun found(title: String, size: Long, score: Double): FoundRelease {
            val item = NewznabItem(
                indexerId = "dl-page-a", guid = title, title = title, sizeBytes = size, publishedAt = null, postedAt = null, categories = emptyList(), grabs = null,
                passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null, tvmazeId = null, season = null, episode = null, coverUrl = null,
                mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
            )
            val ranked = RankedRelease(
                ReleaseFacts(item.key, title, size, source = ReleaseFacts.USENET), ReleaseParser.parse(title), playable = true, score = score, reasons = emptyList(),
                estimatedBitrate = null,
            )
            return FoundRelease(item, ranked, SearchStage.Episode)
        }

        /** Shows [content] in the app's theme at [fontScale], as a page in the app is. */
        fun ComposeContentTestRule.show(context: Context, fontScale: Float = 1f, content: @Composable () -> Unit) {
            val scaled = context.createConfigurationContext(Configuration(context.resources.configuration).apply { this.fontScale = fontScale })
            setContent { CompositionLocalProvider(LocalDensity provides Density(scaled)) { AppTheme { content() } } }
        }

        /** Waits for [matcher] to show, ten seconds at most. */
        fun ComposeContentTestRule.waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) {
            try {
                waitUntil(timeoutMs) { exists(matcher) }
            } catch (e: ComposeTimeoutException) {
                throw AssertionError("never shown: ${matcher.description}", e)
            }
        }

        fun ComposeContentTestRule.exists(matcher: SemanticsMatcher) = onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

        /** Scrolls the page's list to [matcher] where the list has it, and says whether it is there (in the list or out of it). */
        fun ComposeContentTestRule.scrolledTo(matcher: SemanticsMatcher): Boolean {
            waitForIdle()
            try {
                onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
            } catch (e: AssertionError) {
                // Not in the list: the bar's, or nowhere.
            }
            return exists(matcher)
        }

        /** Waits until [condition] holds, ten seconds at most, saying [what] where it never does. */
        fun ComposeContentTestRule.waitTill(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
            try {
                waitUntil(timeoutMs, condition)
            } catch (e: ComposeTimeoutException) {
                throw AssertionError("never: $what", e)
            }
        }

        private fun SemanticsNode.textLayout(): TextLayoutResult? {
            if (!config.contains(SemanticsActions.GetTextLayoutResult)) return null
            val results = mutableListOf<TextLayoutResult>()
            config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            return results.singleOrNull()
        }

        /**
         * [text] shown whole in the window [widthPx] wide: every word of it laid out, none cut at the bottom or ellipsized,
         * and inside the window sideways. Scrolled to first where the list has it further down.
         */
        fun ComposeContentTestRule.assertWhole(text: String, widthPx: Int, matcher: SemanticsMatcher = androidx.compose.ui.test.hasText(text)) {
            scrolledTo(matcher)
            val node = onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull { it.textLayout() != null }
                ?: throw AssertionError("no text '$text'")
            val layout = node.textLayout()!!
            val last = layout.lineCount - 1
            val laid = layout.layoutInput.text.text
            if (layout.didOverflowHeight || layout.isLineEllipsized(last) || layout.getLineEnd(last) != laid.length) {
                throw AssertionError("'$text' is cut: ${layout.lineCount} lines in ${layout.layoutInput.constraints}")
            }
            val bounds = node.boundsInWindow
            if (bounds.left < 0f || bounds.right > widthPx + 1f) throw AssertionError("'$text' ($bounds) leaves the screen (0-$widthPx)")
        }
    }
}
