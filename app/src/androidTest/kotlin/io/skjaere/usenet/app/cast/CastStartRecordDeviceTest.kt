package io.skjaere.usenet.app.cast

import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.media3.cast.Cast
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.casting.CAST_LOOK_FOR
import io.skjaere.usenet.app.casting.CAST_UNAVAILABLE
import io.skjaere.usenet.app.collection.MovieScreen
import io.skjaere.usenet.app.collection.ShowScreen
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.home.HomeScreen
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.link.PlayableFilm
import io.skjaere.usenet.app.link.playOnLabel
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.MovieTarget
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.link.Playback
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.MovieDetails
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * L50, L73 (U5), L111, on a phone with Google's own check and start, in a run of its own, never in a shard: once started,
 * Google's software stays started for the rest of its process. "Look for Cast devices" off, as on a phone that never chose
 * it, Home, a Show page, a Movie page, a play here (a film posted on the test's own server, [PlayableFilm]) and the Play on
 * sheet's opening leave Cast unstarted (`needsInitialization()`, asked on the main thread), which fails the test if not:
 * nothing of Google's runs before the opt-in's tap. Then "Look for Cast devices" is tapped, and what Google's software
 * does on this image is recorded in the log (whether Play services says it is there, whether Cast started or failed, the
 * failure's kind alone), never failing the test: whether it starts on the emulator's google_apis image is U5's question,
 * not a rule. The settings are put back after. The titles are invented.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class CastStartRecordDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val home = HomeContentShown()
    private val film = PlayableFilm(FILM, "Harbor Bell")
    private var tmdb: FakeTmdb? = null
    private var page by mutableStateOf(Page.Home)

    private enum class Page { Home, Show, Movie }

    /**
     * Whether the sheet's opening asked Play services through the gate, and what it said: the gate asks on the main thread,
     * where it opens; the app's own reads for the button (at its start, at each MainActivity start, as CastFakes sets the
     * check) are off it, and don't count.
     */
    @Volatile private var asked = false
    @Volatile private var available: Boolean? = null

    /** The settings as the test found them, put back after it: it turns "Look for Cast devices" off. */
    private var saved: AppSettings? = null

    private val show = ShowDetails(
        SHOW, "Harbor Lights", null, 2021, null, null, null, "Ended", false, 45,
        listOf(SeasonSummary(1, null, 6, LocalDate.of(2021, 3, 1), null)),
        lastEpisode = EpisodeSummary(1, 6, "Night Crossing", LocalDate.of(2021, 4, 5)), nextEpisode = null, ids = ExternalIds(tvdb = 991_401),
    )

    private val season = Season(SHOW, 1, null, null, (1..6).map { Episode(1, it, "Episode $it", null, LocalDate.of(2021, 3, 1).plusWeeks(it - 1L), 45, null) })

    private val movie = MovieDetails(MOVIE, "North Quay", null, 2023, null, null, null, 118, ExternalIds(imdb = "tt9914020"), releaseDate = LocalDate.of(2023, 3, 9))

    @Before
    fun setUp() {
        assumeFalse("a TV never starts Cast: tv.TvNoCastTest", container.television)
        runBlocking {
            saved = container.settings.settings.first()
            // As on a phone that never chose it (L112): the sheet asks first.
            container.settings.updateCast { it.copy(look = false) }
            withTimeout(10_000) { container.castLooking.first { it == false } }
        }
        home.show()
        runBlocking { film.prepare() }
        tmdb = FakeTmdb(shows = mapOf(SHOW to show), seasons = { id, _ -> if (id == SHOW) season else null }, movies = mapOf(MOVIE to movie))
        // Google's own check and start, the check watched: the sheet's opening is seen to ask it.
        CastFakes.set(
            CastAvailability { context ->
                CastAvailability.Google.available(context).also {
                    if (Looper.getMainLooper().isCurrentThread) {
                        available = it
                        asked = true
                    }
                }
            },
            CastStarter.Google,
        )
    }

    @After
    fun restore() {
        // The runner's again; Google's software, if it started, stays started in this process all the same (L73).
        CastFakes.missing()
        container.playOn.closeSheet()
        tmdb?.close()
        film.close()
        home.restore()
        saved?.let { runBlocking { container.settings.restore(it) } }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, millis: Long = 15_000) = compose.waitUntil(millis) { exists(matcher) }

    private fun castUnstarted(): Boolean = onMainThread { Cast.getSingletonInstance(context).needsInitialization() }

    /** The opt-in's row in the sheet (L112). */
    private val lookFor = hasText(CAST_LOOK_FOR) and hasClickAction()

    private fun <T : Any> waitFor(what: String, millis: Long = 30_000, value: () -> T?): T {
        val until = SystemClock.uptimeMillis() + millis
        while (true) {
            value()?.let { return it }
            assertTrue("$what within $millis ms", SystemClock.uptimeMillis() < until)
            Thread.sleep(100)
        }
    }

    @Test
    fun googlesCastSoftwareStartsAtTheSheetsOpeningAlone() {
        assertTrue("Cast started before the test began", castUnstarted())
        compose.setContent {
            AppTheme {
                when (page) {
                    Page.Home -> HomeScreen(onSearch = {}, onRecent = {}, onSettings = {}, onSetup = { _, _ -> }, onShow = {}, onCollection = {}, onImport = {})
                    Page.Show -> ShowScreen(SHOW, onBack = {})
                    Page.Movie -> MovieScreen(MOVIE, onBack = {})
                }
            }
        }
        // Home, with its TV button.
        waitFor(hasContentDescription(playOnLabel(PlayTarget.Phone)))
        // A Show page, then a Movie page, each with its TV button and Play.
        page = Page.Show
        waitFor(hasText("Harbor Lights"))
        waitFor(hasContentDescription(playOnLabel(PlayTarget.Phone)))
        page = Page.Movie
        waitFor(hasText("Choose release") and hasClickAction())
        waitFor(hasContentDescription(playOnLabel(PlayTarget.Phone)))
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            // A play here, to the player, as from the film's own page.
            container.playback.playMovie(MovieTarget(FILM, "Harbor Bell", 2026, null, 6))
            waitFor("the film playing here", 60_000) { container.playerInFront.now.value?.takeIf { it.state == Playback.Playing && it.positionMs >= 1_000 } }
            onMainThread { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlayerActivity>() }
                .forEach { player -> onMainThread { player.finish() } }
            waitFor("MainActivity in front again") { scenario.state.takeIf { it == Lifecycle.State.RESUMED } }
            assertTrue("Google's Cast software started before the Play on sheet opened", castUnstarted())

            // The sheet opens over Home: Google's own check (L111), then, with Play services there, "Look for Cast devices";
            // nothing of Google's starts before its tap. The compose rule's clock drives MainActivity's frames too, so its
            // waits let them run.
            asked = false
            available = null
            onMainThread { container.playOn.openSheet() }
            runCatching { compose.waitUntil(30_000) { asked } }
            assertTrue("the sheet's opening asked Play services through the gate, first (L111)", asked)
            compose.waitUntil(30_000) { exists(lookFor) || exists(hasText(CAST_UNAVAILABLE)) }
            assertTrue("Google's Cast software started as the Play on sheet opened, before Look for Cast devices", castUnstarted())
            // The tap: Cast's start, if Play services passed. What comes of it is recorded, never asserted.
            if (exists(lookFor)) {
                compose.onNode(lookFor).performClick()
                runCatching { compose.waitUntil(30_000) { !castUnstarted() || exists(hasText(CAST_UNAVAILABLE)) } }
            }
            var failure: Throwable? = null
            // Google's module loads in the background: a failure, if any, within 20 s.
            if (!castUnstarted()) runCatching {
                compose.waitUntil(20_000) {
                    failure = onMainThread { Cast.getSingletonInstance(context).castContextLoadFailure }
                    failure != null
                }
            }
            val gate = onMainThread { CastGate.open(context, container.television, container.castLooking.value) }
            Log.i(
                TAG,
                "U5: Play services available=$available, Cast asked to start=${!castUnstarted()}, " +
                    "failed=${failure?.javaClass?.simpleName ?: "no (none in 20 s)"}, the gate says $gate",
            )
            onMainThread { container.playOn.closeSheet() }
        }
    }

    private companion object {
        const val TAG = "CastStartRecord"

        /** Invented TMDB ids nothing real has. */
        const val SHOW = 991_311
        const val MOVIE = 991_312
        const val FILM = 900_071
    }
}
