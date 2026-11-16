package io.skjaere.usenet.app.tv

import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.collection.MovieScreen
import io.skjaere.usenet.app.collection.SHOW_FOOTNOTE_PHONE
import io.skjaere.usenet.app.collection.ShowScreen
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.download.DownloadBootReceiver
import io.skjaere.usenet.app.download.DownloadJobService
import io.skjaere.usenet.app.download.DownloadMarkLook
import io.skjaere.usenet.app.download.DownloadNotice
import io.skjaere.usenet.app.download.DownloadRequest
import io.skjaere.usenet.app.download.DownloadSeasonSheet
import io.skjaere.usenet.app.download.SeasonDownloadRequest
import io.skjaere.usenet.app.download.ServicePlan
import io.skjaere.usenet.app.download.TitleDownloadActions
import io.skjaere.usenet.app.download.movieDownloadSlot
import io.skjaere.usenet.app.home.HomeScreen
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.MovieTarget
import io.skjaere.usenet.app.search.MovieHeroCard
import io.skjaere.usenet.app.search.TitleInfo
import io.skjaere.usenet.app.settings.HubRow
import io.skjaere.usenet.app.settings.SettingsScreen
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.metadata.MovieDetails
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.GroupKind
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Downloads never show on a TV (W108, L369), on a real one (tv36), with a finished download and a queued one in its table
 * as a test leaves them (nothing on a TV makes one): in the TV's layout and in a phone's layout on it,
 * - the episode ⋮ offers no download, and no row has a mark or a line; the season has no Download beside Mark season,
 *   and its sheet, asked for all the same, ends at once; the Movie page and Search's movie card have no Download; Home
 *   has no Downloads section; Settings has no Downloads row;
 * - and once the app is opened as a TV opens it, no permission is asked, no job waits in the downloads' namespace or as
 *   the service's watchdog, the boot receiver is off, and no download notification shows; the rows are as they were.
 *
 * The rows and their folders are deleted after. The show and the film are invented; the TMDB seam answers for them and
 * refuses anything else, so nothing reaches TMDB.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
class TvNoDownloadsTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private var tmdb: FakeTmdb? = null
    private val seeded = mutableListOf<Download>()
    private var layout by mutableStateOf(FormFactor.Tv)

    @Before
    fun setUp() = runBlocking<Unit> {
        assumeTv()
        withTimeout(SEEDING_MILLIS) { container.devSettingsSaved.await() }
        tmdb = FakeTmdb(shows = mapOf(SHOW to harborLights), seasons = { id, n -> season.takeIf { id == SHOW && n == 1 } }, movies = mapOf(MOVIE to copperCrown), strict = true)
        // As a test would leave them: a finished episode and a queued film, each with its folder and marker.
        seeded += row(DownloadState.Done, episode = 2)
        seeded += row(DownloadState.Queued, episode = null)
        seeded.forEach { container.downloadStorage.add(it) }
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        try {
            tmdb?.close()
        } catch (e: AssertionError) {
            // What a page asked of other titles was refused, never sent.
        }
        seeded.forEach { container.downloadStorage.delete(it.id) }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** Nothing of downloads in what shows now: no entry, word, line or mark. */
    private fun noDownloads(where: String) {
        compose.waitForIdle()
        DOWNLOAD_WORDS.forEach { words -> assertFalse("'$words' $where", exists(hasText(words, substring = true, ignoreCase = true))) }
        assertFalse("a download's mark $where", compose.onAllNodes(SemanticsMatcher.keyIsDefined(DownloadMarkLook), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun theShowPagesEpisodeMenuOffersNoDownloadInEitherLayout() {
        compose.setContent { AppTheme(formFactor = layout) { ShowScreen(SHOW, onBack = {}) } }
        listOf(FormFactor.Tv, FormFactor.Phone).forEach { form ->
            layout = form
            val more = hasContentDescription("More for episode 2")
            compose.waitUntil(15_000) { exists(more) }
            runCatching { compose.onNode(hasScrollAction()).performScrollToNode(more) }
            noDownloads("on the $form layout's Show page")
            compose.onNode(more).performClick()
            // The menu's own items: a TV's page has a Choose release of its own.
            val inMenu = hasClickAction() and hasAnyAncestor(isPopup())
            compose.waitUntil(5_000) { exists(hasText("Choose release") and inMenu) }
            assertTrue(exists(hasText("Mark watched") and inMenu))
            noDownloads("in the $form layout's episode menu")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) { !exists(hasText("Choose release") and inMenu) }
        }
        // A phone's layout on a TV says what its ⋮ does there: no download.
        assertFalse(exists(hasText(SHOW_FOOTNOTE_PHONE)))
    }

    /** Season 1 has two aired episodes not downloaded, which a phone's Show page offers as "Download 2 episodes" (L441). */
    @Test
    fun theShowPagesSeasonHasNoDownloadInEitherLayout() {
        compose.setContent { AppTheme(formFactor = layout) { ShowScreen(SHOW, onBack = {}) } }
        listOf(FormFactor.Tv, FormFactor.Phone).forEach { form ->
            layout = form
            val mark = hasText("Mark season watched")
            compose.waitUntil(15_000) { exists(mark) }
            runCatching { compose.onNode(hasScrollAction()).performScrollToNode(mark) }
            val seasonDownload = SemanticsMatcher("a season's Download") { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { SEASON_DOWNLOAD.matches(it.text) }
            }
            assertFalse("a season's Download on the $form layout's Show page", exists(seasonDownload))
        }
    }

    /**
     * A season's Download sheet, which nothing on a TV opens, asked for all the same: it ends at once in either layout,
     * showing nothing and queuing nothing (L441). The TV's indexers are off meanwhile, so a sheet that didn't end would
     * search none, and on again after.
     */
    @Test
    fun aSeasonsDownloadSheetEndsAtOnceInEitherLayout() = runBlocking<Unit> {
        val request = SeasonDownloadRequest(
            "Harbor Lights", 1, listOf(1, 3).map { n -> SeasonDownloadRequest.Listed(EpisodePlay.of(harborLights, season.episodes[n - 1], today)) },
            downloaded = listOf(2), posterPath = null,
        )
        val enabled = container.settings.indexers.first().filter { it.enabled }.map { it.id }
        try {
            enabled.forEach { container.settings.setIndexerEnabled(it, false) }
            var closes = 0
            var open by mutableStateOf(false)
            compose.setContent {
                AppTheme(formFactor = layout) {
                    if (open) {
                        DownloadSeasonSheet(request, onClose = {
                            closes++
                            open = false
                        })
                    }
                }
            }
            listOf(FormFactor.Tv, FormFactor.Phone).forEach { form ->
                layout = form
                open = true
                compose.waitUntil(5_000) { !open }
                noDownloads("where the $form layout's season sheet was asked for")
            }
            assertEquals(2, closes)
            assertEquals("the show's rows as seeded", seeded.filter { it.showTmdbId == SHOW }.map { it.id }, container.data.downloads.all().first().filter { it.showTmdbId == SHOW }.map { it.id })
        } finally {
            enabled.forEach { container.settings.setIndexerEnabled(it, true) }
        }
    }

    @Test
    fun theMoviePageAndSearchsMovieCardHaveNoDownloadInEitherLayout() {
        val sheets = mutableListOf<DownloadRequest>()
        val actions = TitleDownloadActions(TitleDownloadActions.Deps.of(container), container.appScope, container.appScope)
        var page by mutableStateOf(true)
        compose.setContent {
            AppTheme(formFactor = layout) {
                if (page) {
                    MovieScreen(MOVIE, onBack = {}, onDownloads = {})
                } else {
                    val target = MovieTarget(MOVIE, "Copper Crown", 2022, null, 112)
                    MovieHeroCard(
                        card, showArtwork = false, group, inCollection = false, adding = false, onPlay = {}, onReleases = {}, onAdd = {}, onOpen = {}, onJump = {},
                        download = movieDownloadSlot(target, null, actions, onSheet = { sheets += it }, onDownloads = {}),
                    )
                }
            }
        }
        listOf(FormFactor.Tv, FormFactor.Phone).forEach { form ->
            layout = form
            page = true
            compose.waitUntil(15_000) { exists(hasText("Choose release") and hasClickAction()) }
            noDownloads("on the $form layout's Movie page")
            page = false
            compose.waitUntil(5_000) { exists(hasText("2 releases") and hasClickAction()) }
            noDownloads("on the $form layout's movie card")
        }
        assertEquals(emptyList<DownloadRequest>(), sheets)
    }

    @Test
    fun homeHasNoDownloadsSectionAndSettingsNoDownloadsRow() {
        var opened = 0
        var screen by mutableStateOf("home")
        compose.setContent {
            AppTheme(formFactor = layout) {
                if (screen == "home") {
                    HomeScreen(
                        onSearch = {}, onRecent = {}, onSettings = {}, onSetup = { _, _ -> }, onShow = {}, onCollection = {}, onImport = {},
                        onDownloads = { opened++ }, onDownloadsShow = { opened++ },
                    )
                } else {
                    // Given a Downloads page to open, as a phone's Settings is: the hub's own gates leave its row out on a TV.
                    SettingsScreen(
                        onBack = null, onProviders = {}, onIndexers = {}, onMetadata = {}, onPlayback = {}, onCache = {}, onLogs = {},
                        onDownloads = { opened++ },
                    )
                }
            }
        }
        listOf(FormFactor.Tv, FormFactor.Phone).forEach { form ->
            layout = form
            screen = "home"
            compose.waitForIdle()
            SystemClock.sleep(SETTLE_MILLIS)
            // Section headers are said in capitals.
            assertFalse("a Downloads section on the $form layout's Home", exists(hasText("Downloads · ", substring = true, ignoreCase = true)))
            noDownloads("on the $form layout's Home")
            screen = "settings"
            compose.waitUntil(10_000) { exists(hasText(HubRow.Providers.title)) }
            assertFalse("a Downloads row in the $form layout's Settings", exists(hasText("Downloads", ignoreCase = true)))
        }
        assertEquals(0, opened)
        // The runner shows a TV none of what its table holds.
        assertEquals(emptyList<Any>(), container.downloads.state.value)
    }

    @Test
    fun openedAsATvOpensItNothingOfDownloadsIsAskedScheduledSwitchedOnOrPosted() = runBlocking<Unit> {
        val asked = container.settings.downloads.first().notificationsAsked
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor("the app in front", 15_000) { scenario.state.takeIf { it == Lifecycle.State.RESUMED } }
            // Its start's schedule, and anything after it, given time to do what it would.
            delay(SETTLE_MILLIS)
            assertEquals("the app was covered by a dialog", Lifecycle.State.RESUMED, scenario.state)
        }
        assertEquals("the notification permission asked about", asked, container.settings.downloads.first().notificationsAsked)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertEquals("jobs waiting in the downloads' namespace", emptyList<Any>(), DownloadJobService.scheduler(context).allPendingJobs)
        }
        val watchdog = context.getSystemService(JobScheduler::class.java).getPendingJob(ServicePlan.WATCHDOG_ID)
        assertEquals("the service's watchdog waits", null, watchdog)
        val receiver = context.packageManager.getComponentEnabledSetting(ComponentName(context, DownloadBootReceiver::class.java))
        assertTrue("the boot receiver is on ($receiver)", receiver != PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        val ours = setOf(DownloadNotice.DOWNLOADS_CHANNEL, DownloadNotice.FINISHED_CHANNEL)
        val posted = context.getSystemService(NotificationManager::class.java).activeNotifications.filter { it.notification.channelId in ours }
        assertEquals("download notifications", emptyList<Any>(), posted.map { it.id })
        // Nothing started them: as seeded.
        seeded.forEach { row -> assertEquals(row.state, container.data.downloads.get(row.id)?.state) }
    }

    private fun row(state: DownloadState, episode: Int?): Download {
        val id = Download.newId()
        val film = episode == null
        return Download(
            id = id, watchKey = if (film) WatchKey.movie(MOVIE) else WatchKey.episode(SHOW, 1, episode!!), kind = if (film) DownloadKind.Movie else DownloadKind.Episode,
            title = if (film) "Copper Crown" else "Harbor Lights", showTmdbId = SHOW.takeIf { !film }, season = 1.takeIf { !film }, episode = episode,
            movieTmdbId = MOVIE.takeIf { film }, state = state, position = 0, candidates = listOf(DownloadCandidate("tv-none", id, "Harbor.Lights.S01E02.1080p.WEB-DL-KESTREL")),
            size = 1_300_000_000L.takeIf { state == DownloadState.Done }, indexerSize = 1_300_000_000, safeOffset = if (state == DownloadState.Done) 1_300_000_000 else 0,
            relativePath = "downloads/$id/v.mkv".takeIf { state == DownloadState.Done }, createdAt = 1, completedAt = 1L.takeIf { state == DownloadState.Done },
            updatedAt = System.currentTimeMillis(),
        )
    }

    private val today = LocalDate.now()

    private val harborLights = ShowDetails(
        SHOW, "Harbor Lights", null, 2021, null, null, null, "Returning Series", true, 50, listOf(SeasonSummary(1, null, 3, today.minusDays(60), null)),
        lastEpisode = EpisodeSummary(1, 3, "The Sandbar", today.minusDays(40)), nextEpisode = null, ids = ExternalIds(),
    )

    private val season = Season(SHOW, 1, null, null, listOf("First Light", "Pilot Boat", "The Sandbar").mapIndexed { i, name -> Episode(1, i + 1, name, null, today.minusDays(60L - 7 * i), 50, null) })

    private val copperCrown = MovieDetails(
        MOVIE, "Copper Crown", null, 2022, null, null, null, 112, ExternalIds(), releaseDate = LocalDate.of(2022, 3, 1), status = "Released",
    )

    private val card = TitleInfo(MediaType.Movie, MOVIE, "Copper Crown", 2022, "Movie · 2022 · 1 h 52 min", null, null, null, TvIds(tmdb = MOVIE), 112)

    private val group = ResultGroup(
        "movie:$MOVIE", GroupKind.Movie, null, emptyList(), null, 2022,
        listOf("Copper.Crown.2022.1080p.WEB-DL.H.264-WREN", "Copper.Crown.2022.720p.WEB-DL.H.264-GRP").map { name -> ranked(name) }, emptyList(),
    )

    private fun ranked(title: String): RankedItem {
        val item = NewznabItem(
            indexerId = "tv-none", guid = title, title = title, sizeBytes = 4_000_000_000, publishedAt = null, postedAt = null, categories = emptyList(),
            grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null, tvmazeId = null, season = null, episode = null,
            coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        )
        return RankedItem(item, RankedRelease(ReleaseFacts(item.key, title, item.sizeBytes, source = ReleaseFacts.USENET), ReleaseParser.parse(title), playable = true, score = 80.0, reasons = emptyList(), estimatedBitrate = null))
    }

    private companion object {
        /** An invented show and film; nothing real has these ids. */
        const val SHOW = 990_731
        const val MOVIE = 990_732
        const val SEEDING_MILLIS = 60_000L

        /** Long enough for the start's schedule, and anything that would follow it, to have run. */
        const val SETTLE_MILLIS = 3_000L

        /** A season's Download's words on a phone's Show page, whatever it counts. */
        val SEASON_DOWNLOAD = Regex("""Download (season \d+|\d+ episodes?)""")

        /** What a download says anywhere it shows: the entries, the button's and the rows' words, the section's. */
        val DOWNLOAD_WORDS = listOf(
            "Download", "Queued for download", "Downloaded", "Remove download", "Pause download", "Resume download", "Open Downloads",
        )
    }
}
