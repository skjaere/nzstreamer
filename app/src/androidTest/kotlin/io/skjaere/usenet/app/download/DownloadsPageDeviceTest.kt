package io.skjaere.usenet.app.download

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.download.DownloadHostRules.ScheduleCause
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.ATMOS
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.GB
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.HL
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.KESTREL
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.QM
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.episode
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.exists
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.film
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.found
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.scrolledTo
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.show
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.waitFor
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.waitTill
import io.skjaere.usenet.data.DownloadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Downloads page on a phone (item 19; L372, L380, L381), its rows seeded through the app's own storage and shown over
 * the app's runner, which the page's Pause, Resume, Move, Try again and Choose release call; what isn't the page's is a
 * seam (the space, the network, the background limit, the host's schedule, TMDB's lookup and the search), so no host
 * runs and nothing is grabbed.
 * - Each state shows in its words, with the strip's numbers, the mobile-data and background lines, Failed and Downloaded.
 * - Pause holds the queue; Resume lets it go on from the head and asks for its host.
 * - Move up takes a queued one up.
 * - Try again queues a failed one again and asks for its host.
 * - Choose another release looks the episode up on TMDB, lists its releases for downloading, and a tap replaces the
 *   running one's releases.
 * - Choose release is greyed "Needs a connection" offline and where TMDB failed.
 * - With nothing, the empty page.
 */
@RunWith(AndroidJUnit4::class)
class DownloadsPageDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val seeds = DownloadsPageSeeds(context, container)
    private var settings = 0

    @Before
    fun setUp() = seeds.before()

    @After
    fun tearDown() = seeds.after()

    @Test
    fun eachStateShowsInItsWordsWithTheStripAndTheLines() {
        seeds.seed(episode(2, 4, DownloadState.Running, size = 3_100_000_000, file = true, written = 1_302_000_000, position = 0))
        seeds.seed(episode(2, 5, DownloadState.Resolving, reason = "Trying release 2 of 3 · Checking availability", position = 1))
        seeds.seed(episode(2, 6, DownloadState.Waiting, reason = DownloadHostRules.WAITING_FOR_WIFI, size = 2 * GB, file = true, written = 840_000_000, position = 2))
        seeds.seed(episode(2, 7, DownloadState.Stopped, size = 2 * GB, file = true, written = 840_000_000, position = 3))
        seeds.seed(episode(2, 8, DownloadState.Queued, position = 4))
        seeds.seed(episode(1, 3, DownloadState.Failed, show = QM, title = "Quiet Meridian", reason = DownloadResolver.INCOMPLETE, tried = listOf("a", "b", "c"), position = 5))
        (1..3).forEach { seeds.seed(episode(1, it, DownloadState.Done)) }
        seeds.seed(film(gaps = 2))
        val space = DownloadSpace(available = 65_400_000_000, used = 23_400_000_000, queued = 23_400_000_000)
        show(seeds.deps(space = { space }, metered = true, restricted = true))

        compose.waitFor(hasText("Downloading · 42%"))
        listOf(
            "Downloads 23.4 GB · 41.0 GB free", DownloadsPage.MOBILE_DATA, DownloadsPage.RESTRICTED, DownloadsPage.APP_SETTINGS, "DOWNLOADING",
            "S02E04 · Low Water", "Downloading · 42%", "1.3 of 3.1 GB", "Trying release 2 of 3", "Checking availability", "Waiting for Wi-Fi · 42%",
            "Stopped by Android · 42%", "Queued · searched when its turn comes", "FAILED", "✕ Your provider is missing parts of this release",
            "3 releases tried", DownloadsPage.TRY_AGAIN, "DOWNLOADED", "3 episodes · 5.1 GB", "Next: S01E01", "2022 · 1 h 52 min · 5.5 GB",
            "Downloaded · 2 parts missing",
        ).forEach { assertTrue("'$it' shown", compose.scrolledTo(hasText(it))) }
        // The row the queue is at has Pause; the film, Play.
        assertTrue(compose.scrolledTo(hasContentDescription(DownloadsPage.PAUSE)))
        assertTrue(compose.scrolledTo(hasContentDescription("Play Copper Crown")))
        assertTrue(exists(hasText(DownloadsPage.SELECT)))

        compose.onNode(hasContentDescription(DownloadsPage.SETTINGS)).performClick()
        compose.waitTill("the gear opened Settings › Downloads") { settings == 1 }
    }

    @Test
    fun pauseHoldsTheQueueAndResumeLetsItGoOnFromItsHead() {
        // The running one behind a queued one by position (one moved up past it meanwhile): Resume puts it back at the head.
        val queued = seeds.seed(episode(2, 5, DownloadState.Queued, position = 0))
        val running = seeds.seed(episode(2, 4, DownloadState.Running, size = 3_100_000_000, file = true, written = 1_302_000_000, position = 1))
        show(seeds.deps())
        compose.waitFor(hasContentDescription(DownloadsPage.PAUSE))
        compose.onNode(hasContentDescription(DownloadsPage.PAUSE)).performClick()
        compose.waitTill("the running one paused") { seeds.row(running.id)?.state == DownloadState.Paused }
        compose.waitFor(hasText("Paused · 42%"))
        assertEquals("the whole queue held", emptyList<String>(), DownloadHostRules.runnable(seeds.mine()).map { it.id })
        assertEquals(DownloadState.Queued, seeds.row(queued.id)?.state)

        compose.onNode(hasText(DownloadsPage.RESUME) and hasClickAction()).performClick()
        compose.waitTill("resumed") { seeds.row(running.id)?.state == DownloadState.Queued }
        compose.waitTill("its host asked for") { seeds.scheduled == listOf(ScheduleCause.Resume) }
        val rows = seeds.mine()
        assertEquals("at the queue's head", listOf(running.id, queued.id), DownloadHostRules.runnable(rows).sortedBy { it.position }.map { it.id })
    }

    /**
     * A long press on a download being written selects it, though the page draws its row again as it is written (four times
     * a second at most), each time a new row: the press is the same press throughout.
     */
    @Test
    fun aLongPressSelectsARowWhileItsDownloadIsWritten() {
        seeds.seed(episode(2, 4, DownloadState.Running, size = 3_100_000_000, file = true, written = 1_302_000_000))
        val more = MutableStateFlow(0L)
        val base = seeds.deps()
        show(base.copy(items = combine(base.items, more) { items, bytes -> items.map { it.copy(written = it.written + bytes) } }))
        compose.waitFor(hasText("1.3 of 3.1 GB"))
        val row = hasText("Harbor Lights") and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)
        // On the page's root, as the row is another once it is selected: the finger goes up where it went down.
        val at = compose.onNode(row).fetchSemanticsNode().boundsInRoot.center
        compose.mainClock.autoAdvance = false
        try {
            compose.onRoot().performTouchInput { down(at) }
            // Written on as the finger is down: the page draws the row again before the press is long.
            more.value = 200_000_000
            var frames = 0
            while (!compose.exists(hasText("1.5 of 3.1 GB"))) {
                check(++frames < 15) { "the page didn't draw the row again within ${frames * 16} ms of the press" }
                Thread.sleep(50)
                compose.mainClock.advanceTimeByFrame()
            }
            compose.mainClock.advanceTimeBy(1_000)
            compose.onRoot().performTouchInput { up() }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitFor(hasText(DownloadsPage.selected(1)))
    }

    @Test
    fun moveUpTakesAQueuedOneUpPastItsQueuedNeighbour() {
        seeds.seed(episode(2, 4, DownloadState.Running, size = 3_100_000_000, file = true, written = 1_302_000_000, position = 0))
        val fifth = seeds.seed(episode(2, 5, DownloadState.Queued, position = 1))
        val sixth = seeds.seed(episode(2, 6, DownloadState.Queued, position = 2))
        show(seeds.deps())
        compose.waitFor(hasText("S02E06 · Salt and Iron"))
        assertTrue(compose.scrolledTo(hasContentDescription("More for Harbor Lights S02E06")))
        compose.onNode(hasContentDescription("More for Harbor Lights S02E06")).performClick()
        compose.waitFor(hasText(DownloadsPage.MOVE_UP))
        assertTrue(exists(hasText(DownloadsPage.MOVE_DOWN)))
        assertTrue(exists(hasText(DownloadsPage.CANCEL)))
        compose.onNode(hasText(DownloadsPage.MOVE_UP) and hasClickAction()).performClick()
        compose.waitTill("moved up") { (seeds.row(sixth.id)?.position ?: 0) < (seeds.row(fifth.id)?.position ?: 0) }
        compose.waitTill("drawn above") { top("S02E06 · Salt and Iron") < top("S02E05 · The Long Tide") }
    }

    @Test
    fun tryAgainQueuesAFailedOneAgainAndAsksForItsHost() {
        val failed = seeds.seed(episode(1, 3, DownloadState.Failed, show = QM, title = "Quiet Meridian", reason = DownloadResolver.INCOMPLETE, tried = listOf("a", "b", "c")))
        show(seeds.deps())
        compose.waitFor(hasText(DownloadsPage.TRY_AGAIN) and hasClickAction())
        compose.onNode(hasText(DownloadsPage.TRY_AGAIN) and hasClickAction()).performClick()
        compose.waitTill("queued again") { seeds.row(failed.id)?.state == DownloadState.Queued }
        assertEquals(emptyList<String>(), seeds.row(failed.id)?.tried)
        compose.waitTill("its host asked for") { seeds.scheduled == listOf(ScheduleCause.Download) }
        compose.waitFor(hasText("Queued · searched when its turn comes"))
    }

    @Test
    fun chooseAnotherReleaseLooksTheEpisodeUpAndATapReplacesTheRunningOnesReleases() {
        val running = seeds.seed(episode(2, 4, DownloadState.Running, size = 3_100_000_000, file = true, written = 1_302_000_000))
        show(seeds.deps(releases = listOf(found(KESTREL, 1_200_000_000, 2.0), found(ATMOS, 3_400_000_000, 1.0))))
        compose.waitFor(hasContentDescription("More for Harbor Lights S02E04"))
        compose.onNode(hasContentDescription("More for Harbor Lights S02E04")).performClick()
        compose.waitFor(hasText(DownloadsPage.CHOOSE_ANOTHER) and hasClickAction())
        compose.onNode(hasText(DownloadsPage.CHOOSE_ANOTHER) and hasClickAction()).performClick()

        compose.waitFor(hasText("Download S02E04"))
        assertEquals("TMDB asked first", listOf(Triple(HL, 2, 4)), seeds.lookUps.toList())
        compose.waitFor(hasText(ATMOS) and hasClickAction())
        assertTrue(exists(hasText("Ranked for downloading: smaller first")))
        compose.onNode(hasText(ATMOS) and hasClickAction()).performClick()

        compose.waitTill("its releases replaced") { seeds.row(running.id)?.candidates?.map { it.title } == listOf(ATMOS, KESTREL) }
        val row = seeds.row(running.id)!!
        assertEquals(DownloadState.Queued, row.state)
        assertEquals("what it wrote gone", 0L to null, row.safeOffset to row.relativePath)
        compose.waitTill("its host asked for") { seeds.scheduled == listOf(ScheduleCause.Download) }
        compose.waitFor(hasText(DownloadsPage.TITLE))
        assertFalse("the list closed", exists(hasText("Download S02E04")))
    }

    @Test
    fun chooseReleaseIsGreyedOfflineAndWhereTmdbFailed() {
        seeds.seed(episode(1, 3, DownloadState.Failed, show = QM, title = "Quiet Meridian", reason = DownloadResolver.INCOMPLETE, tried = listOf("a")))
        seeds.online.value = false
        show(seeds.deps())
        compose.waitFor(hasText(DownloadsPage.NEEDS_CONNECTION))
        assertFalse("greyed, not tappable", exists(hasText(DownloadsPage.CHOOSE_RELEASE) and hasClickAction()))
        assertTrue(exists(hasText(DownloadsPage.CHOOSE_RELEASE) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled)))

        seeds.online.value = true
        compose.waitFor(hasText(DownloadsPage.CHOOSE_RELEASE) and hasClickAction())
        assertFalse(exists(hasText(DownloadsPage.NEEDS_CONNECTION)))
        seeds.tmdbFails = true
        compose.onNode(hasText(DownloadsPage.CHOOSE_RELEASE) and hasClickAction()).performClick()
        compose.waitFor(hasText(DownloadsPage.NEEDS_CONNECTION))
        assertEquals(listOf(Triple(QM, 1, 3)), seeds.lookUps.toList())
        assertFalse("no list without TMDB's answer", exists(hasText("Download S01E03")))
    }

    @Test
    fun withNothingTheEmptyPageSaysWhatItIsFor() {
        show(seeds.deps())
        compose.waitFor(hasText(DownloadsPage.EMPTY))
        listOf(DownloadsPage.EMPTY_LINE, "41.0 GB free on this device", DownloadsPage.STAYS).forEach { assertTrue("'$it' shown", exists(hasText(it))) }
        assertFalse("no Select with nothing to select", exists(hasText(DownloadsPage.SELECT)))
        assertTrue(exists(hasContentDescription(DownloadsPage.SETTINGS)))
    }

    private fun show(deps: DownloadsDeps) {
        compose.show(context) { DownloadsScreen(deps, onBack = {}, onSettings = { settings++ }, onShow = {}, onOpenMovie = {}) }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.exists(matcher)

    private fun top(text: String): Float = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().firstOrNull()?.boundsInWindow?.top ?: Float.MAX_VALUE
}
