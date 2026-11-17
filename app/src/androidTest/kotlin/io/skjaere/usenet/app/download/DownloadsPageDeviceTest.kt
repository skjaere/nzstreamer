package io.skjaere.usenet.app.download

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.cast.CastFakes
import io.skjaere.usenet.app.cast.CastSeams
import io.skjaere.usenet.app.cast.FakeConnector
import io.skjaere.usenet.app.cast.FakeLauncher
import io.skjaere.usenet.app.cast.FakeRoutes
import io.skjaere.usenet.app.cast.castRoute
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.download.DownloadHostRules.ScheduleCause
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.ATMOS
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.CC
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.assertWhole
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.GB
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.HL
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.KESTREL
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.QM
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.episode
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.exists
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.film
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.found
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.imported
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.scrolledTo
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.show
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.waitFor
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.waitTill
import io.skjaere.usenet.app.everyStep
import io.skjaere.usenet.app.link.CastButtonLook
import io.skjaere.usenet.app.link.FakeTv
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.link.PlayOnHost
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.link.SheetPlace
import io.skjaere.usenet.app.link.THIS_PHONE
import io.skjaere.usenet.app.link.TITLE_PAGE_LINE
import io.skjaere.usenet.app.link.inventedTvName
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.MovieMark
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.player.PlayUris
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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
import java.util.concurrent.CopyOnWriteArrayList

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
 * - With Delete once watched on, a film marked watched says "Deleting tomorrow" on its row, whole at twice the font size,
 *   and nothing does once it is off (C4, L415); the settings and the mark put back after.
 * - The bar (L453): Downloads, then Select, the Cast button and the gear; no Cast button in select mode. At twice the
 *   text size "Downloads" stays whole, on a line of its own under the buttons where it can't share their row (L470).
 * - With a TV chosen, downloads play here: the button says this phone, and Play on opened from the page ticks This phone
 *   and greys the TV "Plays from the title's page" (L418, L453).
 * - With Kitchen display connected (Cast's fakes, L73), a film's ▶ is read with the device's name, and its tap casts the
 *   film's file there through the launcher (L418, L470), nothing played here.
 *
 * Where a case chose a device or paired a TV, Play on is put back to This phone, the TV forgotten and Cast's seams reset.
 */
@RunWith(AndroidJUnit4::class)
class DownloadsPageDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val seeds = DownloadsPageSeeds(context, container)
    private var settings = 0

    // Play on's and Cast's, where a case chose a device or paired a TV: put back after.
    private val routes = FakeRoutes()
    private val connector = FakeConnector()
    private val launcher = FakeLauncher()
    private val kitchen = castRoute("Kitchen display")
    private var cast = false
    private var tv: FakeTv? = null

    @Before
    fun setUp() = seeds.before()

    @After
    fun tearDown() = runBlocking<Unit> {
        everyStep(
            { if (cast || tv != null) compose.runOnUiThread { container.playOn.closeSheet() } },
            { if (cast) onMainThread { container.playback.cancel() } },
            { if (cast || tv != null) container.playOn.choose(PlayChoice.Phone) },
            {
                tv?.let {
                    it.close()
                    container.data.link.forgetTv(it.key.id)
                }
            },
            { if (cast) CastFakes.reset() },
            { seeds.after() },
        )
    }

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
        // Each section's header is a heading, for TalkBack's heading navigation.
        listOf("DOWNLOADING", "FAILED", "DOWNLOADED").forEach { assertTrue("'$it' a heading", compose.scrolledTo(hasText(it) and isHeading())) }
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

    @Test
    fun aFilmMarkedWatchedSaysDeletingTomorrowWhileDeleteOnceWatchedIsOn() = runBlocking<Unit> {
        withTimeout(SEEDING_MILLIS) { container.devSettingsSaved.await() }
        val saved = container.settings.settings.first()
        val film = seeds.seed(film())
        try {
            container.settings.setDownloadsDeleteOnceWatched(true)
            container.library.markMovies(listOf(MovieMark(CC, "Copper Crown")), watched = true)
            show(seeds.deps(), fontScale = 2f)
            compose.waitFor(hasText("Copper Crown"))
            assertTrue(compose.scrolledTo(hasText(AutoDelete.DELETING_TOMORROW)))
            compose.assertWhole(AutoDelete.DELETING_TOMORROW, context.resources.displayMetrics.widthPixels)
            // In the film's row, with its words: TalkBack hears it with them.
            assertTrue(exists(hasText(AutoDelete.DELETING_TOMORROW) and hasText("Copper Crown")))
            assertEquals(DownloadState.Done, seeds.row(film.id)?.state)
            container.settings.setDownloadsDeleteOnceWatched(false)
            compose.waitTill("nothing says it goes once it is off") { !exists(hasText(AutoDelete.DELETING_TOMORROW)) }
            assertTrue(exists(hasText("Copper Crown")))
        } finally {
            container.settings.restore(saved)
            container.library.markMovies(listOf(MovieMark(CC, "Copper Crown")), watched = false)
        }
    }

    /**
     * L453: the bar says Downloads beside the back arrow, then Select, the Cast button (this
     * phone's) and the gear last; select mode's own bar has no Cast button.
     */
    @Test
    fun theBarHasSelectThenTheCastButtonThenTheGearAndNoCastButtonWhileSelecting() {
        seeds.seed(film())
        show(seeds.deps())
        compose.waitFor(hasText("Copper Crown"))
        val select = bounds(hasContentDescription(DownloadsPage.SELECT) and hasClickAction())
        val button = bounds(castButton)
        val gear = bounds(hasContentDescription(DownloadsPage.SETTINGS))
        assertTrue("Select at $select, the Cast button at $button, the gear at $gear", select.left < button.left && button.left < gear.left)
        assertEquals("where Play goes", listOf("Play on: this phone"), castWords())
        // At this size the title shares the row: beside the back arrow, not under it.
        val back = bounds(hasContentDescription("Back"))
        val title = bounds(hasText(DownloadsPage.TITLE))
        assertTrue("the title at $title, the back arrow at $back", title.center.y in back.top..back.bottom && title.left >= back.right - 1f)
        compose.onNode(hasContentDescription(DownloadsPage.SELECT) and hasClickAction()).performClick()
        compose.waitFor(hasText(DownloadsPage.selected(0)))
        assertFalse("no Cast button while selecting", exists(castButton))
    }

    /**
     * L470: at twice the text size "Downloads" stays whole, never broken beside Select, the Cast
     * button and the gear. Where it can't share their row (a page 360 dp wide) it gets a line of its own under the back
     * arrow and the buttons. On phone34's own 411 dp it may share the row: Android 14 scales large text by less than
     * twice (its nonlinear font scaling), and there it fits beside them. Either way TalkBack reads the title right after
     * the back arrow, before the buttons, as on every other page.
     */
    @Test
    fun atTwiceTheTextSizeDownloadsStaysWholeWithALineOfItsOwnWhereItCantShareTheRow() {
        seeds.seed(film())
        var narrow by mutableStateOf(false)
        compose.show(context, fontScale = 2f) {
            Box(if (narrow) Modifier.width(NARROW) else Modifier.fillMaxWidth()) {
                DownloadsScreen(seeds.deps(), onBack = {}, onSettings = { settings++ }, onShow = {}, onOpenMovie = {})
            }
        }
        compose.waitFor(castButton)
        val screen = context.resources.displayMetrics.widthPixels
        compose.assertWhole(DownloadsPage.TITLE, screen)
        val title = bounds(hasText(DownloadsPage.TITLE))
        val select = bounds(hasContentDescription(DownloadsPage.SELECT) and hasClickAction())
        assertTrue("the title at $title, beside or under Select at $select", title.right <= select.left + 1f || title.top >= select.bottom - 1f)
        assertEquals("what TalkBack reads after the back arrow", DownloadsPage.TITLE, readAfterBack())
        narrow = true
        compose.waitForIdle()
        compose.assertWhole(DownloadsPage.TITLE, screen)
        val back = bounds(hasContentDescription("Back"))
        val under = bounds(hasText(DownloadsPage.TITLE))
        val buttons = listOf(bounds(hasContentDescription(DownloadsPage.SELECT) and hasClickAction()), bounds(castButton), bounds(hasContentDescription(DownloadsPage.SETTINGS)))
        assertTrue("the title at $under, under the back arrow at $back", under.top >= back.bottom - 1f)
        buttons.forEach { assertTrue("the title at $under, under the button at $it", under.top >= it.bottom - 1f) }
        // The buttons keep the arrow's row, the gear last at its end.
        buttons.forEach { assertTrue("the button at $it, in the arrow's row at $back", it.center.y in back.top..back.bottom) }
        assertEquals("what TalkBack reads after the back arrow, the title under it", DownloadsPage.TITLE, readAfterBack())
    }

    /**
     * L418, L453, W59: with your TV chosen, downloads play on this phone, so the page's button
     * says this phone, and Play on opened from it ticks This phone and greys the TV with where it plays from, not to be
     * chosen here.
     */
    @Test
    fun withATvChosenPlayOnOpenedFromThePageTicksThisPhoneAndGreysTheTv() {
        seeds.seed(film())
        castStarted()
        val fake = pairedTv()
        container.playOn.choose(PlayChoice.Tv(fake.key.id))
        compose.waitTill("the TV chosen") { container.playOn.target.value is PlayTarget.Tv }
        show(seeds.deps(), playOn = true)
        compose.waitFor(castButton and hasClickAction())
        assertEquals("downloads play here", listOf("Play on: this phone"), castWords())
        compose.onNode(castButton and hasClickAction()).performClick()
        compose.waitFor(hasText(TITLE_PAGE_LINE))
        assertEquals("opened as a screen whose Play plays here", SheetPlace.PhoneOnly, container.playOn.sheet.value)
        compose.waitFor(hasText(fake.name))
        val tvRow = hasText(fake.name) and hasClickAction()
        assertFalse("the TV can't be chosen from here", exists(tvRow and isEnabled()))
        assertFalse("nor is it ticked", exists(tvRow and isSelected()))
        assertTrue("This phone ticked", exists(hasText(THIS_PHONE) and isSelected()))
        assertEquals("the TV still chosen", PlayChoice.Tv(fake.key.id), container.playOn.chosen.value)
    }

    /**
     * L418, L470: with Kitchen display connected, the button shows it, a film's ▶ is read "Play
     * Copper Crown on Kitchen display", and its tap casts the film's file there: the launch handed to the device is the
     * download's, nothing played here.
     */
    @Test
    fun withKitchenDisplayConnectedAFilmsPlayIsReadWithItAndCastsItsFile() {
        val film = seeds.seed(castable(film()))
        connectKitchen()
        show(seeds.deps().copy(play = DownloadsDeps.of(container, context).play))
        compose.waitFor(hasContentDescription("Play Copper Crown on Kitchen display") and hasClickAction())
        assertEquals(listOf("Play on: Kitchen display"), castWords())
        assertFalse("no ▶ read as this phone's", exists(hasContentDescription("Play Copper Crown")))
        compose.onNode(hasContentDescription("Play Copper Crown on Kitchen display") and hasClickAction()).performClick()
        compose.waitTill("the file handed to Kitchen display", 30_000) { launcher.loads.isNotEmpty() }
        val launch = launcher.loads.single()
        assertEquals(PlayUris.download(film.id), launch.uri)
        assertEquals("Kitchen display", launch.cast?.name)
        launcher.plays()
        compose.waitTill("the sheet closed", 10_000) { container.playback.state.value == PlayState.Idle }
    }

    /**
     * L424, L454, L472: the files downloaded from Import are a Files section after Downloaded, a heading, in their names'
     * order, each named by what its name says: "Quiet Meridian", "S01E01 · 1.3 GB", the file's name under. Its ▶, read
     * "Play Quiet Meridian S01E01", plays it; its ⋮, read "More for Quiet Meridian S01E02", offers Remove download alone,
     * which hides it with the Undo bar naming it as its row does, and Undo brings it back. Select mode selects it, Delete
     * giving back what it holds.
     */
    @Test
    fun theFilesSectionFollowsDownloadedEachFileNamedByItsNameWithPlayAndRemoveDownloadAlone() {
        val e2 = seeds.seed(imported(E2, index = 2, position = 0))
        val e1 = seeds.seed(imported(E1, index = 1, position = 1))
        seeds.seed(film())
        show(seeds.deps(undoMillis = 60_000))
        compose.waitFor(hasText("Copper Crown"))
        assertTrue(compose.scrolledTo(hasText(DownloadsPage.FILES.uppercase()) and isHeading()))
        listOf("S01E01 · 1.3 GB", "S01E02 · 1.3 GB", E1, E2).forEach { assertTrue("'$it' shown", compose.scrolledTo(hasText(it))) }
        assertTrue("Files after Downloaded", top(DownloadsPage.FILES.uppercase()) > top(DownloadsPage.DOWNLOADED.uppercase()))
        assertTrue("in their names' order", top(E1) < top(E2))
        assertEquals("both named by their names' title", 2, compose.onAllNodes(hasText("Quiet Meridian"), useUnmergedTree = true).fetchSemanticsNodes().size)

        val play = hasContentDescription("Play Quiet Meridian S01E01") and hasClickAction()
        assertTrue(compose.scrolledTo(play))
        compose.onNode(play).performClick()
        compose.waitTill("its file played") { seeds.played == listOf(e1.id) }

        val more = hasContentDescription("More for Quiet Meridian S01E02")
        assertTrue(compose.scrolledTo(more))
        compose.onNode(more).performClick()
        val inMenu = hasClickAction() and hasAnyAncestor(isPopup())
        compose.waitFor(hasText(DownloadsPage.REMOVE_DOWNLOAD) and inMenu)
        assertEquals(1, compose.onAllNodes(inMenu).fetchSemanticsNodes().size)
        compose.onNode(hasText(DownloadsPage.REMOVE_DOWNLOAD) and inMenu).performClick()
        compose.waitFor(hasText("Deleted Quiet Meridian S01E02 · 1.3 GB"))
        compose.waitTill("hidden at once") { seeds.row(e2.id)?.state == DownloadState.Deleting }
        compose.onNode(hasText("Undo") and hasClickAction()).performClick()
        compose.waitTill("brought back") { seeds.row(e2.id)?.state == DownloadState.Done }
        compose.waitFor(hasText(E2))

        compose.onNode(hasText(DownloadsPage.SELECT) and hasClickAction()).performClick()
        assertTrue(compose.scrolledTo(hasText(E1)))
        compose.onNode(hasText(E1)).performClick()
        compose.waitFor(hasText(DownloadsPage.selected(1)))
        assertTrue(exists(hasText("Delete · 1.3 GB")))
        assertTrue(exists(hasText(DownloadsPage.selectAll(3))))
    }

    /** L472: at twice the font size a Files row's lines are whole, the file's name wrapping as it must. */
    @Test
    fun atTwiceTheFontSizeAFilesRowIsWhole() {
        seeds.seed(imported(E2, index = 2))
        show(seeds.deps(), fontScale = 2f)
        compose.waitFor(hasText("S01E02 · 1.3 GB"))
        val screen = context.resources.displayMetrics.widthPixels
        listOf(DownloadsPage.FILES.uppercase(), "Quiet Meridian", "S01E02 · 1.3 GB", E2).forEach { compose.assertWhole(it, screen) }
    }

    private val castButton = SemanticsMatcher.keyIsDefined(CastButtonLook)

    /** What TalkBack reads of the Cast button now. */
    private fun castWords(): List<String>? = compose.onAllNodes(castButton).fetchSemanticsNodes().singleOrNull()?.config?.getOrNull(SemanticsProperties.ContentDescription)

    private fun bounds(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().firstOrNull()?.boundsInRoot ?: throw AssertionError("not shown: ${matcher.description}")

    /**
     * What TalkBack reads after the bar's back arrow, of the title, Select, the Cast button and the gear: as Compose orders
     * the page for a screen reader (each node's next one, which Compose puts in its accessibility node for tests), with
     * accessibility on for the read and off again after.
     */
    private fun readAfterBack(): String {
        val back = compose.onAllNodes(hasContentDescription("Back") and hasClickAction()).fetchSemanticsNodes().single()
        val named = mapOf(
            DownloadsPage.TITLE to hasText(DownloadsPage.TITLE), DownloadsPage.SELECT to (hasContentDescription(DownloadsPage.SELECT) and hasClickAction()),
            "the Cast button" to castButton, DownloadsPage.SETTINGS to hasContentDescription(DownloadsPage.SETTINGS),
        ).mapNotNull { (name, matcher) -> compose.onAllNodes(matcher).fetchSemanticsNodes().firstOrNull()?.let { it.id to name } }.toMap()
        val root = back.root as ViewRootForTest
        val after = onMainThread {
            root.forceAccessibilityForTesting(true)
            try {
                val nodes = root.view.accessibilityNodeProvider ?: throw AssertionError("no accessibility nodes")
                generateSequence(back.id) { id -> nodes.createAccessibilityNodeInfo(id)?.extras?.getInt(TRAVERSAL_BEFORE, -1)?.takeIf { it != -1 } }
                    .drop(1).take(TRAVERSAL_LOOK).toList()
            } finally {
                root.forceAccessibilityForTesting(false)
            }
        }
        return after.firstNotNullOfOrNull { named[it] } ?: throw AssertionError("none of ${named.values} read after the back arrow: $after")
    }

    /** [row] with a release kept as Resolve keeps one, so its file can be cast. */
    private fun castable(row: Download): Download {
        val name = "Copper.Crown.2022.1080p.WEB-DL-KESTREL.mkv"
        return row.copy(release = RememberedRelease("dl-page-a", "cc", name.removeSuffix(".mkv"), null, 0, "nzb-cc", name, row.size), fileName = name)
    }

    /** Cast's fakes started: Play services there, this phone looking for Cast devices, Google's software unstarted (L73). */
    private fun castStarted() {
        cast = true
        CastFakes.started(routes, connector, CopyOnWriteArrayList())
        CastSeams.launcher = { launcher }
    }

    /** Kitchen display's session connected and the device chosen in Play on, with nothing left over in the coordinator. */
    private fun connectKitchen() {
        castStarted()
        CastFakes.connected(kitchen, connector)
        container.playOn.choose(PlayChoice.Cast(kitchen.id, kitchen.name))
        compose.waitTill("Kitchen display connected") { container.playOn.target.value == PlayTarget.Cast(kitchen.id, kitchen.name, CastLink.Connected, kitchen.facts) }
        onMainThread { container.playback.withdraw() }
    }

    /** One of your TVs, paired in the link store (its identity made first, so a list's first read doesn't wipe it). */
    private fun pairedTv(): FakeTv = runBlocking {
        val link = container.data.link
        val phone = link.identity().identity.key.id
        FakeTv(context, inventedTvName("Living room TV"), paired = setOf(phone), advertised = false).also { fake ->
            tv = fake
            assertTrue(link.addTv(fake.key.id, fake.name, fake.address.hostAddress!!, fake.port))
        }
    }

    private fun show(deps: DownloadsDeps, fontScale: Float = 1f, playOn: Boolean = false) {
        compose.show(context, fontScale) {
            DownloadsScreen(deps, onBack = {}, onSettings = { settings++ }, onShow = {}, onOpenMovie = {})
            // Play on's sheet, as the app's shell draws it over every screen.
            if (playOn) PlayOnHost(onAddTv = {}, onPairAgain = {}, onPrivacy = {})
        }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.exists(matcher)

    private fun top(text: String): Float = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().firstOrNull()?.boundsInWindow?.top ?: Float.MAX_VALUE

    private companion object {
        const val SEEDING_MILLIS = 60_000L

        /** A page narrower than phone34's 411 dp, as the narrowest phones are. */
        val NARROW = 360.dp

        /** Where Compose puts, in a node's accessibility info, the id of the node read after it. */
        const val TRAVERSAL_BEFORE = "android.view.accessibility.extra.EXTRA_DATA_TEST_TRAVERSALBEFORE_VAL"

        /** How many nodes after the back arrow are looked through. */
        const val TRAVERSAL_LOOK = 8

        /** Two files of an invented season pack opened on Import. */
        const val E1 = "Quiet.Meridian.S01E01.1080p.WEB-DL.H264-WREN.mkv"
        const val E2 = "Quiet.Meridian.S01E02.1080p.WEB-DL.H264-WREN.mkv"
    }
}
