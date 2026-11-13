package io.skjaere.usenet.app.tv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.media3.cast.Cast
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionToken
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.CacheRoute
import io.skjaere.usenet.app.CollectionRoute
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.HomeRoute
import io.skjaere.usenet.app.ImportPicker
import io.skjaere.usenet.app.ImportRoute
import io.skjaere.usenet.app.ImportScreen
import io.skjaere.usenet.app.IndexerRoute
import io.skjaere.usenet.app.IndexersRoute
import io.skjaere.usenet.app.LogsRoute
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.MetadataRoute
import io.skjaere.usenet.app.PhonesRoute
import io.skjaere.usenet.app.PlaybackRoute
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.ProviderRoute
import io.skjaere.usenet.app.ProvidersRoute
import io.skjaere.usenet.app.SearchRoute
import io.skjaere.usenet.app.SettingsRoute
import io.skjaere.usenet.app.ThisDeviceRoute
import io.skjaere.usenet.app.cast.CastAvailability
import io.skjaere.usenet.app.cast.CastFakes
import io.skjaere.usenet.app.cast.CastGate
import io.skjaere.usenet.app.cast.CastGateState
import io.skjaere.usenet.app.cast.CastService
import io.skjaere.usenet.app.cast.CastStarter
import io.skjaere.usenet.app.casting.CAST_INTRO_GOT_IT
import io.skjaere.usenet.app.casting.CAST_INTRO_TITLE
import io.skjaere.usenet.app.collection.MovieScreen
import io.skjaere.usenet.app.collection.ShowScreen
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.controllerOf
import io.skjaere.usenet.app.discover.DiscoverRoute
import io.skjaere.usenet.app.link.PlayableFilm
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.MovieTarget
import io.skjaere.usenet.app.search.ReleaseList
import io.skjaere.usenet.app.shell
import io.skjaere.usenet.app.switchTab
import io.skjaere.usenet.app.tabsFor
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.views
import io.skjaere.usenet.link.Playback
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.MovieDetails
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.search.ResultGroup
import io.skjaere.usenet.search.ResultGrouper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * W28, L67, L110: on a TV nothing of Google Cast shows or starts, though every build carries it since C2. Cast's gate says
 * TV without asking Play services or starting anything, and Home (MainActivity's own), a Show page and a Movie page leave
 * Google's Cast software unstarted (`needsInitialization()`, asked on the main thread, as `Cast` wants). The gate's seams
 * count here what the runner's fakes would hide: neither is ever asked. No manifest check can say it (L67). The cast
 * service, which every build declares on a TV too, gives another app's controller nothing there, and doesn't stay (L85).
 * The TV walk's screens (TvSharedScreensWalkTest's), each opened in turn as the app opens it, and the screens C2 puts the
 * Cast button on beside them on a phone (Import with a .nzb opened, a release list, the player) show no Cast button
 * ("Play on: …", its TalkBack words) and no Google Cast row, and leave it unstarted too. The titles are invented.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class TvNoCastTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val checks = AtomicInteger()
    private val starts = AtomicInteger()
    private val home = HomeContentShown()
    private var tmdb: FakeTmdb? = null
    private var page by mutableStateOf(Page.Show)

    private enum class Page { Show, Movie }

    private val show = ShowDetails(
        SHOW, "Marsh Light", null, 2022, null, null, null, "Ended", false, 45,
        listOf(SeasonSummary(1, null, 3, LocalDate.of(2022, 3, 1), null)),
        lastEpisode = EpisodeSummary(1, 3, "Episode 3", LocalDate.of(2022, 3, 15)), nextEpisode = null, ids = ExternalIds(),
    )

    private val season = Season(SHOW, 1, null, null, (1..3).map { Episode(1, it, "Episode $it", null, LocalDate.of(2022, 3, 1).plusWeeks(it - 1L), 45, null) })

    private val movie = MovieDetails(MOVIE, "Copper Bell", null, 2020, null, null, null, 104, ExternalIds(), releaseDate = LocalDate.of(2020, 5, 1))

    @Before
    fun setUp() {
        assumeTrue("on a phone Cast starts at the Play on sheet's opening: cast.CastStartRecordDeviceTest", container.television)
        CastFakes.set(
            CastAvailability {
                checks.incrementAndGet()
                true
            },
            object : CastStarter {
                override fun start(context: Context) {
                    starts.incrementAndGet()
                }

                override fun failed(context: Context) = false
            },
        )
        tmdb = FakeTmdb(shows = mapOf(SHOW to show), seasons = { id, _ -> if (id == SHOW) season else null }, movies = mapOf(MOVIE to movie))
    }

    @After
    fun restore() {
        CastFakes.missing()
        tmdb?.close()
        home.restore()
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun castUnstarted(): Boolean = onMainThread { Cast.getSingletonInstance(context).needsInitialization() }

    /** A node TalkBack reads as the Cast button, whatever its face: its words start "Play on:" (playOnLabel). */
    private val castButton = SemanticsMatcher("described \"$PLAY_ON …\"") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith(PLAY_ON) }
    }

    /**
     * What [screen], composed by the rule, shows or starts of Cast: the button, a Google Cast row, the maximum when casting
     * (its heading or a choice's words), the button's introduction, Google's software.
     */
    private fun castOn(screen: String): List<String> = listOfNotNull(
        "$screen: a Cast button".takeIf { compose.onAllNodes(castButton, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() },
        "$screen: the Cast button's introduction".takeIf { exists(hasText(CAST_INTRO_TITLE)) },
        "$screen: a Google Cast row".takeIf { exists(hasText(GOOGLE_CAST)) },
        "$screen: a maximum when casting".takeIf {
            exists(hasText(CAST_MAX, ignoreCase = true)) || exists(hasContentDescription(CAST_MAX, substring = true, ignoreCase = true))
        },
        "$screen: Google's Cast software started".takeIf { !castUnstarted() },
    )

    /**
     * What [player] shows or starts of Cast, in its Views and its Compose overlays alike, shown or not (its top bar shows
     * with its controls): a button described "Play on: …", a Google Cast row, Google's software started.
     */
    private fun castIn(player: PlayerActivity): List<String> {
        val (described, texts) = onMainThread {
            val all = views(player.window.decorView)
            val nodes = all.filterIsInstance<ViewRootForTest>().flatMap { subtree(it.semanticsOwner.unmergedRootSemanticsNode) }
            val described = all.mapNotNull { it.contentDescription?.toString() } + nodes.flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            val texts = all.filterIsInstance<TextView>().map { it.text.toString() } + nodes.flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
            described to texts
        }
        return listOfNotNull(
            "The player: a Cast button".takeIf { described.any { it.startsWith(PLAY_ON) } },
            "The player: a Google Cast row".takeIf { GOOGLE_CAST in texts },
            "The player: Google's Cast software started".takeIf { !castUnstarted() },
        )
    }

    private fun subtree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::subtree)

    /** [RELEASES] ranked as this TV ranks them, as the one group they make: a list as Search opens it for an episode. */
    private fun releases(): ResultGroup {
        fun item(title: String, size: Long) = NewznabItem(
            indexerId = "no-cast", guid = title, title = title, sizeBytes = size, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        )
        val ranking = runBlocking { container.rankingContext() }
        return ResultGrouper().group(RELEASES.map { (title, size) -> item(title, size) }, ranking).sections.flatMap { it.groups }.first()
    }

    @Test
    fun theGateSaysTvAndHomeShowAndMovieLeaveCastUnstarted() {
        // Even as if "Look for Cast devices" were chosen (L111): a TV first.
        assertEquals(CastGateState.Television, onMainThread { CastGate.open(context, container.television, looking = true) })
        // A Show page, then a Movie page, in the TV shell.
        compose.setTvPage {
            when (page) {
                Page.Show -> ShowScreen(SHOW, onBack = {})
                Page.Movie -> MovieScreen(MOVIE, onBack = {})
            }
        }
        compose.waitUntil(15_000) { exists(hasText("Marsh Light")) }
        page = Page.Movie
        compose.waitUntil(15_000) { exists(hasText("Choose release") and hasClickAction()) }
        // Home, as the TV's launcher opens it (last: its task takes the place of the pages' activity), its content rather
        // than the first-run screen. The rule's clock drives its frames, and its effects, too.
        home.show()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            compose.waitUntil(10_000) { scenario.state == Lifecycle.State.RESUMED }
            // TV Home shown, past the splash: then a moment more of its frames, for what its rows start as they come.
            compose.waitUntil(15_000) { exists(tvHome) }
            val until = SystemClock.uptimeMillis() + 2_000
            compose.waitUntil(5_000) { SystemClock.uptimeMillis() > until }
            // Nor is the Cast button introduced there (L110, L123), whatever the check of Play services would say.
            assertTrue("the Cast button's introduction on TV Home", !exists(hasText(CAST_INTRO_TITLE)) && !exists(hasText(CAST_INTRO_GOT_IT)))
        }
        assertEquals(CastGateState.Television, onMainThread { CastGate.open(context, container.television, looking = true) })
        assertEquals("Play services never asked on a TV", 0, checks.get())
        assertEquals("Cast never started on a TV", 0, starts.get())
        assertTrue("Google's Cast software started on a TV", castUnstarted())
        // Nor are Cast's opt-in and Play services read for it (L112, L146).
        assertNull("the opt-in read on a TV", container.castLooking.value)
        assertNull("Play services read for Cast on a TV", container.castAvailable.value)
    }

    /**
     * W28, L67, L110: the screens the TV walk walks (TvSharedScreensWalkTest's), opened in turn in the TV shell as the app
     * opens them, a tab's afresh and a page over Home: none asks Play services or starts Google's Cast software, and none
     * shows a Cast button, a Google Cast row or a casting maximum, where C2 puts the button on a phone's Search, Collection
     * and Import, a Google Cast row in its Settings and a casting maximum in its Quality and languages. Import's document picker is one
     * no app answers, as on the TVs without one (#121); a .nzb opened there is the next case's.
     */
    @Test
    fun theWalksScreensLeaveCastUnstarted() {
        val real = ImportPicker.contract
        ImportPicker.contract = {
            object : ActivityResultContract<Array<String>, Uri?>() {
                override fun createIntent(context: Context, input: Array<String>) = Intent("io.skjaere.usenet.test.NO_DOCUMENT_PICKER")
                override fun parseResult(resultCode: Int, intent: Intent?): Uri? = null
            }
        }
        try {
            lateinit var nav: NavHostController
            compose.setTvContent {
                nav = rememberNavController()
                AppNav(nav = nav)
            }
            compose.waitForIdle()
            val asked = mutableListOf<String>()
            val shown = mutableListOf<String>()
            var before = 0
            WALK.forEach { (name, route) ->
                compose.runOnIdle {
                    nav.switchTab(HomeRoute)
                    if (tabsFor(FormFactor.Tv).any { it.route == route }) nav.switchTab(route, restore = false) else nav.navigate(route)
                }
                compose.waitForIdle()
                // Its data read, as the walk gives it: the screens load from the settings and the library.
                val until = SystemClock.uptimeMillis() + LOAD_MS
                compose.waitUntil(5_000) { SystemClock.uptimeMillis() > until }
                val now = checks.get() + starts.get()
                if (now > before) asked += name
                before = now
                shown += castOn(name)
            }
            assertEquals("screens that asked Play services or started Cast on a TV", emptyList<String>(), asked)
            assertEquals("Cast on a TV's screens", emptyList<String>(), shown)
            assertEquals("Cast never started on a TV", 0, starts.get())
            assertTrue("Google's Cast software started on a TV", castUnstarted())
        } finally {
            ImportPicker.contract = real
        }
    }

    /**
     * W28, L67, L110: the screens C2 puts the Cast button on beside the walk's, on a TV: Import with a .nzb opened (the
     * film's own, its articles on the test's own server, [PlayableFilm]: its file listed, where a phone's Import plays
     * it), a release list as Search opens one, and the player playing the film, as MainActivity plays it. None shows a
     * Cast button or a Google Cast row, asks Play services or starts Google's Cast software. The pages are composed first:
     * on tv36 MainActivity's launch takes the rule's activity's place.
     */
    @Test
    fun anOpenedNzbAReleaseListAndThePlayerShowNoCast() {
        val film = PlayableFilm(FILM, "Copper Crown")
        try {
            runBlocking { film.prepare() }
            home.show()
            val group = releases()
            lateinit var nav: NavHostController
            compose.setTvContent {
                nav = rememberNavController()
                TvShell(nav, tabsFor(FormFactor.Tv), container.playback) {
                    NavHost(nav, startDestination = HomeRoute) {
                        composable<HomeRoute> {}
                        composable<ImportRoute> { ImportScreen(it.toRoute<ImportRoute>().opened, onBack = {}, onProviders = {}) }
                        composable<ReleasesPage> { ReleaseList(group, "Harbor Lights", onBack = {}, onPlay = {}) }
                    }
                }
            }
            compose.waitForIdle()
            val shown = mutableListOf<String>()
            // Import, the film's .nzb handed over as another app opens one: prepared, its file listed.
            val opened = container.nzbHandoff.put(Result.success(checkNotNull(film.nzb) to "Copper Crown"))
            compose.runOnIdle { nav.navigate(ImportRoute(opened = opened)) }
            compose.waitUntil(60_000) { exists(hasText("chapters.mkv")) }
            shown += castOn("Open NZB file, a .nzb opened")
            // A release list, as Search opens one for an episode.
            compose.runOnIdle { nav.navigate(ReleasesPage) }
            compose.waitUntil(15_000) { exists(hasText(group.label)) }
            shown += castOn("Release list")
            // The player, the film playing, as the TV plays it from MainActivity.
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
                compose.waitUntil(10_000) { scenario.state == Lifecycle.State.RESUMED }
                container.playback.playMovie(MovieTarget(FILM, "Copper Crown", 2026, null, 6))
                compose.waitUntil(60_000) { container.playerInFront.now.value?.let { it.state == Playback.Playing && it.positionMs >= 1_000 } == true }
                val player = onMainThread { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlayerActivity>().firstOrNull() }
                    ?: throw AssertionError("the film played, but no player is in front")
                shown += castIn(player)
                onMainThread { player.finish() }
            }
            assertEquals("Cast on a TV's screens", emptyList<String>(), shown)
            assertEquals("Play services never asked on a TV", 0, checks.get())
            assertEquals("Cast never started on a TV", 0, starts.get())
        } finally {
            film.close()
        }
    }

    /** L85: another app's controller of the cast service is refused on a TV; the service doesn't stay, and Cast never starts. */
    @Test
    fun aControllerOfTheCastServiceIsRefusedAndStartsNothing() {
        val token = SessionToken(context, ComponentName(context, CastService::class.java))
        val refused = try {
            controllerOf(token).release()
            false
        } catch (e: ExecutionException) {
            true
        }
        assertTrue("another app's controller refused", refused)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val record = { instrumentation.uiAutomation.shell("dumpsys activity services ${context.packageName}/${CastService::class.java.name}") }
        compose.waitUntil(10_000) { record().let { "ServiceRecord{" !in it || "CastService" !in it } }
        assertEquals("Play services never asked on a TV", 0, checks.get())
        assertEquals("Cast never started on a TV", 0, starts.get())
        assertTrue("Google's Cast software started on a TV", castUnstarted())
    }

    /**
     * L85: another app starts the exported service for the foreground on a TV, where it builds nothing: it passes through
     * the foreground and stops, and Android leaves this process be. Cast never starts.
     */
    @Test
    fun anotherAppsStartForTheForegroundStopsItAndTheAppLivesOn() {
        context.startForegroundService(Intent().setComponent(ComponentName(context, CastService::class.java)))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val record = { instrumentation.uiAutomation.shell("dumpsys activity services ${context.packageName}/${CastService::class.java.name}") }
        compose.waitUntil(10_000) { record().let { "ServiceRecord{" !in it || "CastService" !in it } }
        // Android takes an app down within moments of such a stop: this process still runs to say it didn't.
        Thread.sleep(3_000)
        assertEquals("Play services never asked on a TV", 0, checks.get())
        assertEquals("Cast never started on a TV", 0, starts.get())
        assertTrue("Google's Cast software started on a TV", castUnstarted())
    }

    /** The release list's page, of its own in the test's shell. */
    @Serializable
    private data object ReleasesPage

    private companion object {
        /** Invented TMDB ids nothing real has. */
        const val SHOW = 991_321
        const val MOVIE = 991_322
        const val FILM = 991_323

        /** How TalkBack reads the Cast button, whatever its face (playOnLabel). */
        const val PLAY_ON = "Play on:"

        /** The phone's Settings row for Google Cast (C2, L112). */
        const val GOOGLE_CAST = "Google Cast"

        /** The phone's Quality and languages section for the maximum when casting (C2, L132), and its choices' words. */
        const val CAST_MAX = "Maximum bitrate when casting"

        /** An episode's releases, names and sizes, as Search lists them. */
        val RELEASES = listOf(
            "Harbor.Lights.S02E05.The.Long.Tide.1080p.WEB-DL.DDP5.1.H.264-NZST" to 3_200_000_000L,
            "Harbor.Lights.S02E05.The.Long.Tide.720p.WEB-DL.DDP5.1.H.264-NZST" to 1_400_000_000L,
            "Harbor.Lights.S02E05.The.Long.Tide.1080p.HDTV.x264-NZST" to 2_100_000_000L,
        )

        /** As the walk gives each screen to load its data. */
        const val LOAD_MS = 400L

        /** The TV walk's screens, by name, as it opens them; its link pages and the first run come with fakes of their own. */
        val WALK: List<Pair<String, Any>> = listOf(
            "Home" to HomeRoute, "Discover" to DiscoverRoute, "Collection" to CollectionRoute, "Search" to SearchRoute(),
            "Settings" to SettingsRoute, "Usenet providers" to ProvidersRoute, "Add provider" to ProviderRoute(), "Indexers" to IndexersRoute,
            "Add indexer" to IndexerRoute(), "Metadata" to MetadataRoute, "Quality and languages" to PlaybackRoute, "Cache" to CacheRoute,
            "This device" to ThisDeviceRoute, "Logs" to LogsRoute, "Open NZB file" to ImportRoute(), "Phones" to PhonesRoute,
        )
    }
}
