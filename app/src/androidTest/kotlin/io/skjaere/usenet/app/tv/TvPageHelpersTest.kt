package io.skjaere.usenet.app.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.collection.MovieScreen
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.pageOkGuard
import io.skjaere.usenet.app.play.rememberPageOkGuard
import io.skjaere.usenet.app.tabsFor
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.rememberInitialFocus
import io.skjaere.usenet.app.ui.rememberReturnFocus
import io.skjaere.usenet.app.ui.tryFocus
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.CollectedMovie
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.MovieDetails
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The TV pages' shared helpers (TV-3 D2, D3, D8), forced on a phone emulator: a page takes the shell's whole
 * column; Back from a page gives the remote's focus back to the row that opened it, scrolled below the first screen; the
 * OK that opened a page (pressed twice, or held) never presses its first button, on a TV only.
 */
@RunWith(AndroidJUnit4::class)
class TvPageHelpersTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val remote = Remote(compose)
    private lateinit var nav: NavHostController

    @Before
    fun remote() {
        instrumentation.setInTouchMode(false)
    }

    @After
    fun restore() {
        instrumentation.setInTouchMode(true)
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    /** Once the screen is up and has had its own first focus, and the shell its turn after it. */
    private fun settle() {
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
    }

    /** A list of rows in the TV shell, each opening a page, as a TV's Home or Show page would. */
    private fun showTheShell() {
        compose.setTvContent {
            nav = rememberNavController()
            TvShell(nav, tabsFor(FormFactor.Tv), compose.activity.container.playback) {
                NavHost(nav, startDestination = RowsRoute) {
                    composable<RowsRoute> { Rows(onOpen = { nav.navigate(PageRoute) }) }
                    composable<PageRoute> { Page() }
                }
            }
        }
    }

    @Composable
    private fun Rows(onOpen: () -> Unit) {
        val focus = rememberReturnFocus()
        val initial = rememberInitialFocus()
        val list = rememberLazyListState()
        LaunchedEffect(Unit) {
            if (!focus.restore { key -> list.scrollToItem(key.removePrefix("Row ").toInt()) }) initial.request(focus.requester("Row 0"))
        }
        LazyColumn(Modifier.fillMaxSize().testTag(ROWS), state = list) {
            items(ROW_COUNT) { i ->
                val key = "Row $i"
                Text(
                    key,
                    Modifier.focusRequester(focus.requester(key)).clickable {
                        focus.leaving(key)
                        onOpen()
                    }.padding(16.dp),
                )
            }
        }
    }

    @Composable
    private fun Page() {
        val button = remember { FocusRequester() }
        LaunchedEffect(Unit) { button.tryFocus() }
        Box(Modifier.tvFullWidth().height(120.dp).testTag(PAGE)) {
            Text("On the page", Modifier.focusRequester(button).clickable {}.padding(16.dp))
        }
    }

    @Test
    fun aPageTakesTheShellsWholeColumn() {
        showTheShell()
        compose.runOnIdle { nav.navigate(PageRoute) }
        compose.waitUntil(5_000) { exists(PAGE) }
        settle()
        val screen = compose.tvScreen()
        val dp = screen.width / TV_SIZE.width.value
        val page = compose.onNode(hasTestTag(PAGE)).fetchSemanticsNode().boundsInRoot
        val span = "%.0f..%.0f dp".format((page.left - screen.left) / dp, (page.right - screen.left) / dp)
        assertEquals("the page's span on the screen, as the TV's safe area leaves it", "48..912 dp", span)
    }

    /** The row below the first screen: the list is where it was, and the row has the focus again, not the first row. */
    @Test
    fun backFromAPageGivesTheFocusBackToTheRowThatOpenedIt() {
        showTheShell()
        remote.waitForFocus("Row 0")
        val row = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, OPENER, max = 20)
        assertEquals(OPENER, row)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(PAGE) }
        settle()
        assertEquals("the page's first focus", "On the page", remote.focused())

        remote.press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { exists(ROWS) }
        settle()
        assertEquals("Back from the page: the focus on the row that opened it", OPENER, remote.focused())
    }

    /** What a page's guard sits over: its first button, focused as the page opens, and how often it was pressed. */
    private fun showAGuardedPage(formFactor: FormFactor): Long {
        val before = SystemClock.uptimeMillis()
        val content = @Composable {
            var presses by remember { mutableIntStateOf(0) }
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { first.tryFocus() }
            val guard = rememberPageOkGuard()
            Column(Modifier.fillMaxSize().pageOkGuard(guard)) {
                Text("Pressed $presses", Modifier.focusRequester(first).clickable { presses++ }.padding(16.dp))
            }
        }
        if (formFactor == FormFactor.Tv) compose.setTvContent { content() } else compose.setContent { AppTheme(FormFactor.Phone) { content() } }
        remote.waitForFocus("Pressed 0")
        return before
    }

    private fun key(action: Int, down: Long, at: Long, repeat: Int = 0) =
        instrumentation.sendKeySync(KeyEvent(down, at, action, KeyEvent.KEYCODE_DPAD_CENTER, repeat))

    @Test
    fun onATvAnOkJustAsThePageOpensAndAHeldOksRepeatsAreIgnored() {
        val opened = showAGuardedPage(FormFactor.Tv)
        // The second press of a double OK on what opened the page, just after it opened.
        key(KeyEvent.ACTION_DOWN, opened + 100, opened + 100)
        key(KeyEvent.ACTION_UP, opened + 100, opened + 160)
        compose.waitForIdle()
        assertEquals("an OK just as the page opened", "Pressed 0", remote.focused())
        // An OK held down on what opened the page: its repeats, and its up, long after.
        Thread.sleep(500)
        val held = SystemClock.uptimeMillis() - 1_000
        listOf(1, 2, 3).forEach { key(KeyEvent.ACTION_DOWN, held, SystemClock.uptimeMillis(), repeat = it) }
        key(KeyEvent.ACTION_UP, held, SystemClock.uptimeMillis())
        compose.waitForIdle()
        assertEquals("a held OK's repeats and its up", "Pressed 0", remote.focused())
        // A press of its own, once the page has been up a while.
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("an OK of its own", "Pressed 1", remote.focused())
    }

    /** A phone's pages take every press, as before. */
    @Test
    fun onAPhoneThePageTakesAnOkAsItOpens() {
        val opened = showAGuardedPage(FormFactor.Phone)
        key(KeyEvent.ACTION_DOWN, opened + 100, opened + 100)
        key(KeyEvent.ACTION_UP, opened + 100, opened + 160)
        compose.waitForIdle()
        assertEquals("Pressed 1", remote.focused())
    }

    /**
     * The Movie page, its Play focused as it opens: the OK that opened it, pressed again at once, plays nothing; a press
     * of its own does. The indexers are switched off, so the play looks for nothing.
     */
    @Test
    fun theMoviePageIgnoresTheOkThatOpenedIt() {
        val container = compose.activity.container
        val saved: AppSettings = runBlocking { container.settings.settings.first() }
        val collectedBefore = runBlocking { container.library.isMovieCollected(MOVIE).first() }
        val tmdb = FakeTmdb(movies = mapOf(MOVIE to CROWN))
        try {
            runBlocking {
                container.settings.indexers.first().filter { it.enabled }.forEach { container.settings.setIndexerEnabled(it.id, false) }
                val now = System.currentTimeMillis()
                container.library.addMovie(CollectedMovie(CollectedMovie.idFor(MOVIE), MOVIE, "tt9000012", CROWN.title, 2019, addedAt = now, updatedAt = now))
            }
            val opened = SystemClock.uptimeMillis()
            compose.setTvContent { MovieScreen(MOVIE, onBack = {}) }
            compose.waitUntil(10_000) { compose.onAllNodes(hasText(CROWN.title)).fetchSemanticsNodes().isNotEmpty() }
            remote.waitForFocus("Play")
            key(KeyEvent.ACTION_DOWN, opened + 100, opened + 100)
            key(KeyEvent.ACTION_UP, opened + 100, opened + 160)
            compose.waitForIdle()
            Thread.sleep(300)
            assertEquals("the play after the opening OK", PlayState.Idle, container.playback.state.value)

            Thread.sleep(500)
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) { container.playback.state.value != PlayState.Idle }
            assertTrue("a press of its own plays", container.playback.state.value != PlayState.Idle)
        } finally {
            container.playback.cancel()
            tmdb.close()
            runBlocking {
                container.settings.restore(saved)
                if (!collectedBefore) container.library.removeMovie(MOVIE)
            }
        }
    }

    @Serializable
    private data object RowsRoute

    @Serializable
    private data object PageRoute

    private companion object {
        const val ROWS = "rows"
        const val PAGE = "page"
        const val ROW_COUNT = 30
        /** Below the first screen of rows, at 540 dp. */
        const val OPENER = "Row 14"
        const val SETTLE_MS = 700L
        /** Copper Crown's id in the fake catalogue's TMDB numbering; nothing real has it. */
        const val MOVIE = 900011
        val CROWN = MovieDetails(
            MOVIE, "Copper Crown", null, 2019, "A clockmaker's apprentice keeps the town's only crown wound.", null, null, 104,
            ExternalIds(imdb = "tt9000012"), releaseDate = LocalDate.of(2019, 3, 1), status = "Released",
        )
    }
}
