package io.skjaere.usenet.app.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.TmdbLookup
import io.skjaere.usenet.app.collection.CANCEL
import io.skjaere.usenet.app.collection.MOVIE_LOAD_FAILED
import io.skjaere.usenet.app.collection.MovieScreen
import io.skjaere.usenet.app.collection.TRY_AGAIN
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.data.CollectedMovie
import io.skjaere.usenet.data.MovieMark
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.MovieDetails
import io.skjaere.usenet.metadata.TmdbError
import io.skjaere.usenet.metadata.TmdbException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import kotlin.math.abs

/**
 * The Movie page on a TV (TV-3), in the TV shell forced on a phone emulator, over the real library and
 * the fake catalogue's Copper Crown, collected, which the TMDB seam answers for: Play, Resume (and where it goes on from)
 * or Play again by how far it was watched, an upcoming movie's note, "+ Collection" for one not collected; the remote's
 * way round the two panes, Left and Right back to where it was in each; both inside the safe area with every text shown
 * in full at 1x and 1.3x; Back from Choose release's list, or its Cancel while it searches, back on Choose release; a
 * movie TMDB can't answer for says so with Try again; the OK that opened the page plays nothing. The app's indexers are
 * swapped for the fake one (10.0.2.2:8089) or one that never answers; the movie's state and collection are put back.
 */
@RunWith(AndroidJUnit4::class)
class TvMovieScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container = instrumentation.targetContext.container
    private val remote = Remote(compose)
    private val indexers = PageIndexers(INDEXER)
    private var tmdb: FakeTmdb? = null
    private var scale by mutableFloatStateOf(1f)
    private var collectedBefore = true
    private val nextYear = LocalDate.now().plusYears(1).withMonth(3).withDayOfMonth(12)

    private val crown = MovieDetails(
        MOVIE, "Copper Crown", null, 2019, "A retired forger is pulled into one last job when a crown goes missing from the cathedral he once restored.", null, null, 118,
        ExternalIds(imdb = "tt9000012"), releaseDate = LocalDate.of(2019, 3, 1), status = "Released",
    )
    private val upcoming = MovieDetails(
        UPCOMING, "Lamplighter's Road", null, nextYear.year, "A lamplighter walks the last gas-lit road in the country, the night before it goes electric.", null, null, 101,
        ExternalIds(imdb = "tt9000093"), releaseDate = nextYear, status = "Post Production",
    )

    @Before
    fun setUp() {
        instrumentation.setInTouchMode(false)
        runBlocking {
            collectedBefore = container.library.isMovieCollected(MOVIE).first()
            val now = System.currentTimeMillis()
            container.library.addMovie(CollectedMovie(CollectedMovie.idFor(MOVIE), MOVIE, "tt9000012", "Copper Crown", 2019, addedAt = now, updatedAt = now))
        }
        tmdb = FakeTmdb(
            movies = mapOf(
                MOVIE to crown, UPCOMING to upcoming, UNANSWERED to upcoming.copy(tmdbId = UNANSWERED, title = "Ember Quay"),
                LONG to crown.copy(tmdbId = LONG, title = LONG_TITLE, ids = ExternalIds(imdb = "tt9000094")),
            ),
        )
    }

    @After
    fun restore() {
        instrumentation.setInTouchMode(true)
        tmdb?.close()
        container.playback.cancel()
        indexers.restore()
        runBlocking {
            container.library.markMovies(listOf(MovieMark(MOVIE, "Copper Crown")), watched = false)
            if (!collectedBefore) container.library.removeMovie(MOVIE)
        }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) = compose.waitUntil(timeoutMs) { exists(matcher) }

    private fun button(label: String) = hasText(label) and hasClickAction()

    private fun settle() {
        compose.waitForIdle()
        Thread.sleep(700)
        compose.waitForIdle()
    }

    /** The page, its first focus taken and past the first 400 ms (whose OKs it takes for the one that opened it); when it opened. */
    private fun openPage(id: Int = MOVIE, firstFocus: String = "Play"): Long {
        val opened = SystemClock.uptimeMillis()
        compose.setTvPage({ scale }) { MovieScreen(id, onBack = {}) }
        waitFor(button("Choose release"), 15_000)
        remote.waitForFocus(firstFocus)
        settle()
        return opened
    }

    @Test
    fun theLabelsSayHowFarItWasWatched() {
        openPage()
        assertTrue("not watched", exists(hasText("Not watched")) && exists(button("Play")) && exists(button("Mark watched")) && exists(button("In collection ✓")))
        assertTrue("the title, the page's heading", compose.onAllNodes(hasText("Copper Crown")).fetchSemanticsNodes().any { it.config.contains(SemanticsProperties.Heading) })
        // A TV plays itself: no Play on button, nor its line under Choose release (L36).
        assertFalse("a Play on button on a TV", exists(hasContentDescription("Play on:", substring = true)))
        assertFalse(exists(hasText(io.skjaere.usenet.app.link.CHOSEN_PLAYS_HERE)))
        val template = WatchState(WatchKey.movie(MOVIE), WatchKind.Movie, movieTmdbId = MOVIE, title = "Copper Crown", updatedAt = 0)
        runBlocking { container.library.recordProgress(template, 4_565_000, 7_080_000) }
        waitFor(button("Resume · 1:16:05"))
        assertTrue("what is left", exists(hasText("42 min left")))
        runBlocking { container.library.markMovies(listOf(MovieMark(MOVIE, "Copper Crown")), watched = true) }
        waitFor(button("Play again"))
        assertTrue("watched", exists(hasText("✓ Watched")) && exists(button("Mark unwatched")))
    }

    /** Not out yet, and not collected: Play all the same, the note under it, and "+ Collection". */
    @Test
    fun anUpcomingMovieNotCollectedPlaysAllTheSameAndOffersTheCollection() {
        openPage(UPCOMING)
        assertTrue("its date", exists(hasText("Out ", substring = true)))
        assertTrue("the note under Play", exists(hasText("Releases often appear around then", substring = true)))
        assertTrue(exists(button("+ Collection")))
    }

    @Test
    fun theRemoteGoesRoundThePanesAndBackToWhereItWasInEach() {
        openPage()
        assertEquals("Choose release", remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("In collection ✓", remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("Right: the right pane", "Mark watched", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("Left: back where it was", "In collection ✓", remote.press(KeyEvent.KEYCODE_DPAD_LEFT))
        remote.press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("Play", remote.press(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals("Right again", "Mark watched", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("and Left: Play, where it was", "Play", remote.press(KeyEvent.KEYCODE_DPAD_LEFT))
    }

    @Test
    fun thePanesKeepInsideTheSafeAreaWithEveryTextShownInFullAt1xAnd13x() {
        val problems = mutableListOf<String>()
        listOf(1f, 1.3f).forEach { s ->
            scale = s
            if (s == 1f) openPage() else settle()
            compose.outsideTheSafeArea().forEach { problems += "${s}x: outside the safe area: $it" }
            compose.textsShownInFull().filterValues { !it }.keys.forEach { problems += "${s}x: not shown in full: \"$it\"" }
            // The overview gives way, line by line, to a larger text size.
            compose.textsCut { it == crown.overview }.forEach { problems += "${s}x: cut: $it" }
        }
        assertEquals("problems:\n" + problems.joinToString("\n"), emptyList<String>(), problems)
        scale = 1f
        settle()
        val screen = compose.tvScreen()
        val dp = screen.width / TV_SIZE.width.value
        fun span(label: String) = compose.onNode(button(label)).fetchSemanticsNode().boundsInRoot.let { ((it.left - screen.left) / dp) to ((it.right - screen.left) / dp) }
        val play = span("Play")
        assertTrue("Play spans the left pane, 48..348 dp: $play", abs(play.first - 48) <= 2 && abs(play.second - 348) <= 2)
        val mark = span("Mark watched")
        assertTrue("Mark watched starts the right pane, at 372 dp: $mark", abs(mark.first - 372) <= 2)
    }

    /**
     * A long title at 1x and 1.3x, and the play bar under the page: the title ends in an ellipsis past its lines, nothing
     * else is cut, and the left pane's buttons keep their 48 dp. The pane scrolls when it can't hold everything.
     */
    @Test
    fun aLongTitleAndThePlayBarLeaveTheLeftPaneWhole() {
        SilentIndexer().use { silent ->
            indexers.use("Silent (TV movie test)", silent.url)
            val problems = mutableListOf<String>()
            fun check(where: String, inFull: Boolean = true) {
                compose.outsideTheSafeArea().forEach { problems += "$where: outside the safe area: $it" }
                if (inFull) compose.textsShownInFull().filterValues { !it }.keys.forEach { problems += "$where: not shown in full: \"$it\"" }
                compose.textsCut { it == crown.overview || it == LONG_TITLE }.forEach { problems += "$where: cut: $it" }
                val dp = compose.tvScreen().width / TV_SIZE.width.value
                listOf("Play", "Choose release", "+ Collection").forEach { b ->
                    val tall = compose.onNode(button(b)).fetchSemanticsNode().size.height / dp
                    if (tall < 47.5f) problems += "$where: \"$b\" is %.1f dp tall".format(tall)
                }
            }
            listOf(1f, 1.3f).forEach { s ->
                scale = s
                if (s == 1f) openPage(LONG) else settle()
                check("${s}x")
            }
            // The play bar: Play searching an indexer that never answers (or saying why it can't play), its sheet hidden.
            scale = 1f
            settle()
            remote.waitForFocus("Play")
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) { container.playback.state.value != PlayState.Idle }
            compose.runOnIdle { container.playback.minimize() }
            // The bar under the page, the title in its words.
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(LONG_TITLE, substring = true)).fetchSemanticsNodes().any { it.words() != LONG_TITLE } }
            settle()
            check("1x with the play bar")
            scale = 1.3f
            settle()
            check("1.3x with the play bar", inFull = false)
            scale = 1f
            assertEquals("problems:\n" + problems.joinToString("\n"), emptyList<String>(), problems)
        }
    }

    @Test
    fun backFromChooseReleasesListGoesBackToChooseRelease() {
        assumeTrue("fake indexer not reachable at 10.0.2.2:8089 (mock-seed indexer)", fakeIndexerReachable())
        indexers.use("Fake (TV movie test)", "http://10.0.2.2:8089")
        openPage()
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Copper Crown · 2 releases from 1 indexer"), 30_000)
        settle()
        remote.press(KeyEvent.KEYCODE_BACK)
        waitFor(button("Play"))
        remote.waitForFocus("Choose release")
    }

    @Test
    fun cancelWhileFindingReleasesGoesBackToChooseRelease() {
        SilentIndexer().use { silent ->
            indexers.use("Silent (TV movie test)", silent.url)
            openPage()
            remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor(hasText("Finding releases…"))
            remote.waitForFocus(CANCEL)
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor(button("Play"))
            remote.waitForFocus("Choose release")
        }
    }

    /** Not collected and TMDB can't answer: nothing to show it by but why, and Try again, focused; once TMDB answers, the page. */
    @Test
    fun aMovieTmdbCantAnswerForSaysSoWithTryAgain() {
        val fake = TmdbLookup.movieAged
        var fail = true
        TmdbLookup.movieAged = { c, id -> if (fail && id == UNANSWERED) throw TmdbException(TmdbError.Network("Unable to resolve host")) else fake(c, id) }
        try {
            compose.setTvPage { MovieScreen(UNANSWERED, onBack = {}) }
            waitFor(hasText(MOVIE_LOAD_FAILED, substring = true), 15_000)
            remote.waitForFocus(TRY_AGAIN)
            settle()
            fail = false
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor(button("Choose release"), 15_000)
            remote.waitForFocus("Play")
        } finally {
            TmdbLookup.movieAged = fake
        }
    }

    /** The second press of a double OK that opened the page, with Play focused: no play (an indexer that never answers, else). */
    @Test
    fun theOkThatOpenedThePagePlaysNothing() {
        SilentIndexer().use { silent ->
            indexers.use("Silent (TV movie test)", silent.url)
            val opened = openPage()
            earlyOk(opened)
            compose.waitForIdle()
            Thread.sleep(300)
            assertEquals(PlayState.Idle, container.playback.state.value)
        }
    }

    private companion object {
        /** Copper Crown's id in the fake catalogue's TMDB numbering; nothing real has it. */
        const val MOVIE = 900011
        /** Invented movies; nothing real has the ids. */
        const val UPCOMING = 900092
        const val UNANSWERED = 900093
        const val LONG = 900094
        const val LONG_TITLE = "The Long Way Back to the Copper Crown of Saint Avel's Cathedral"
        const val INDEXER = "tv-movie-screen-test"
    }
}
