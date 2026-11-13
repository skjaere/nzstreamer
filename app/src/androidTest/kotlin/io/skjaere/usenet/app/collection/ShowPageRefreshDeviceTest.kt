package io.skjaere.usenet.app.collection

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.collection.model.toSummary
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * The Show page while it is kept, as under the player or with the Collection tab's saved state: it
 * follows the stored facts its collection row shows, and asks again for its answer or a season past its
 * time when it is in front again. TMDB is the fake, for an invented show ("Brindle Reach", an id nothing real has),
 * whose answers a test changes as TMDB's would; nothing is collected, played or searched. The stored
 * facts a test writes are that id's only (not collected, they go with the lists' next prune).
 */
@RunWith(AndroidJUnit4::class)
class ShowPageRefreshDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container
    private val today = LocalDate.now()
    private val shows = ConcurrentHashMap<Int, ShowDetails>()
    @Volatile
    private var season = season(second = "Copper Kettle")
    private var tmdb: FakeTmdb? = null

    /** Its second episode aired yesterday: an airing season, which TmdbClient keeps an hour. */
    private fun season(second: String) = Season(
        SHOW, 1, null, null,
        listOf(Episode(1, 1, "Low Tide Bell", null, today.minusDays(8), 44, null), Episode(1, 2, second, null, today.minusDays(1), 44, null)),
    )

    private fun show(status: String) = ShowDetails(
        SHOW, NAME, null, 2025, null, null, null, status, status == "Returning Series", 44,
        listOf(SeasonSummary(1, null, 2, today.minusDays(8), null)),
        lastEpisode = EpisodeSummary(1, 2, null, today.minusDays(1)), nextEpisode = null, ids = ExternalIds(),
    )

    @After
    fun restore() {
        tmdb?.close()
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun open(age: Duration = Duration.ZERO) {
        tmdb = FakeTmdb(shows = shows, seasons = { id, _ -> season.takeIf { id == SHOW } }, age = age)
        compose.setContent { AppTheme { ShowScreen(SHOW, onBack = {}) } }
    }

    @Test
    fun aChangeToTheStoredFactsReachesTheOpenPage() {
        shows[SHOW] = show("Returning Series")
        // As a run before may have left them: the change below is a change.
        runBlocking { container.library.saveSummaries(listOf(show("Returning Series").toSummary()), emptyList(), emptyList()) }
        open()
        compose.waitUntil(15_000) { exists(hasText("2025 · 1 season · Returning Series")) }
        // TMDB says it ended; the lists' refresher asked, and stored what it said.
        shows[SHOW] = show("Ended")
        runBlocking { container.library.saveSummaries(listOf(show("Ended").toSummary()), emptyList(), emptyList()) }
        compose.waitUntil(15_000) { exists(hasText("2025 · 1 season · Ended")) }
    }

    @Test
    fun aSeasonPastItsTimeIsAskedAgainWhenThePageIsBackInFront() {
        shows[SHOW] = show("Returning Series")
        // Two hours old, as from TmdbClient's cache: the airing season is past its hour, the show within its day.
        open(age = Duration.ofHours(2))
        compose.waitUntil(15_000) { exists(hasText("Copper Kettle")) }
        season = season(second = "Lantern Gate")
        // The player covers the page and closes.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(15_000) { exists(hasText("Lantern Gate")) }
    }

    @Test
    fun anAnswerPastItsTimeIsAskedAgainWhenThePageIsBackInFront() {
        shows[SHOW] = show("Returning Series")
        // A day and an hour old, as from TmdbClient's cache: past the day it keeps a returning show.
        open(age = Duration.ofHours(25))
        compose.waitUntil(15_000) { exists(hasText("2025 · 1 season · Returning Series")) }
        shows[SHOW] = show("Ended")
        // The player covers the page and closes; nothing was stored meanwhile.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(15_000) { exists(hasText("2025 · 1 season · Ended")) }
    }

    @Test
    fun aChangeToTheStoredFactsAlsoAsksAgainForTheSelectedSeasonPastItsTime() {
        shows[SHOW] = show("Returning Series")
        runBlocking { container.library.saveSummaries(listOf(show("Returning Series").toSummary()), emptyList(), emptyList()) }
        // Two hours old, as from TmdbClient's cache: the airing season is past its hour.
        open(age = Duration.ofHours(2))
        compose.waitUntil(15_000) { exists(hasText("Copper Kettle")) }
        season = season(second = "Lantern Gate")
        // The page stays in front (a TV left on it); the lists' refresher stores what TMDB says now.
        shows[SHOW] = show("Ended")
        runBlocking { container.library.saveSummaries(listOf(show("Ended").toSummary()), emptyList(), emptyList()) }
        compose.waitUntil(15_000) { exists(hasText("2025 · 1 season · Ended")) }
        compose.waitUntil(15_000) { exists(hasText("Lantern Gate")) }
    }

    private companion object {
        const val SHOW = 900081
        const val NAME = "Brindle Reach"
    }
}
