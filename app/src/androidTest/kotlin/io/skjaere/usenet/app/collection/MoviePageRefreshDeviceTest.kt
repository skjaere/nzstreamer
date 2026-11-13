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
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.MovieDetails
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * The Movie page while it is kept, as under the player or with the Collection tab's saved state: it
 * follows the stored facts its collection row shows, and asks again for an answer past its time when it
 * is in front again. TMDB is the fake, for an invented movie ("Saltmarsh Bell", an id nothing real has),
 * whose answers a test changes as TMDB's would; nothing is collected, played or searched. The stored
 * facts a test writes are that id's only (not collected, they go with the lists' next prune).
 */
@RunWith(AndroidJUnit4::class)
class MoviePageRefreshDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container
    private val movies = ConcurrentHashMap<Int, MovieDetails>()
    private var tmdb: FakeTmdb? = null

    /** Out since 2019: TmdbClient keeps its answer a week. */
    private fun movie(runtime: Int, overview: String) = MovieDetails(
        MOVIE, NAME, null, 2019, overview, null, null, runtime, ExternalIds(), releaseDate = LocalDate.of(2019, 4, 12), status = "Released",
    )

    @After
    fun restore() {
        tmdb?.close()
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun open(age: Duration = Duration.ZERO) {
        tmdb = FakeTmdb(movies = movies, age = age)
        compose.setContent { AppTheme { MovieScreen(MOVIE, onBack = {}) } }
    }

    @Test
    fun aChangeToTheStoredFactsReachesTheOpenPage() {
        movies[MOVIE] = movie(118, OLD)
        // As a run before may have left them: the change below is a change.
        runBlocking { container.library.saveSummaries(emptyList(), listOf(movie(118, OLD).toSummary()), emptyList()) }
        open()
        compose.waitUntil(15_000) { exists(hasText(OLD)) }
        // TMDB has a new cut; the lists' refresher asked, and stored what it said.
        movies[MOVIE] = movie(124, NEW)
        runBlocking { container.library.saveSummaries(emptyList(), listOf(movie(124, NEW).toSummary()), emptyList()) }
        compose.waitUntil(15_000) { exists(hasText(NEW)) }
    }

    @Test
    fun anAnswerPastItsTimeIsAskedAgainWhenThePageIsBackInFront() {
        movies[MOVIE] = movie(118, OLD)
        // Eight days old, as from TmdbClient's cache: past the week it keeps an answer about a movie out a while.
        open(age = Duration.ofDays(8))
        compose.waitUntil(15_000) { exists(hasText(OLD)) }
        movies[MOVIE] = movie(118, NEW)
        // The player covers the page and closes.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(15_000) { exists(hasText(NEW)) }
    }

    private companion object {
        const val MOVIE = 900082
        const val NAME = "Saltmarsh Bell"
        const val OLD = "A lighthouse keeper hears a bell no one rings."
        const val NEW = "A lighthouse keeper hears a bell no one rings, and follows it out to the marsh."
    }
}
