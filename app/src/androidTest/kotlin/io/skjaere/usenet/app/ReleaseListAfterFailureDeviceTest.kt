package io.skjaere.usenet.app

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.tv.Remote
import io.skjaere.usenet.app.tv.setTvContent
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.TriedRelease
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.MovieDetails
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.LocalDate

/**
 * A release chosen by hand that fails to start brings its release list back, the release marked with why in the play
 * sheet's words, so another can be picked; one that plays closes the Show and Movie pages' lists, so the player's Back
 * leads to the page. The fake indexer's (10.0.2.2:8089) releases, over the real coordinator, settings and library: Harbor
 * Lights S01E01's top release ("BROKEN") lacks an article on the mock NNTP server (10.0.2.2:1119), which the availability
 * check finds; Copper Crown's ("MISLABEL") has E-AC-3 audio under an AAC name, which the track probe finds this device
 * can't play; each has a "GOOD" one that plays. TMDB is the fake. No player opens: the launches are taken here. The
 * season pack among S01E01's releases ("PACK", E01 to E03) offers Choose file, which asks which of its videos plays.
 *
 * The saved providers are hidden for the test (tombstoned, their secrets untouched) and put back as they were; the
 * test's own provider and indexer are deleted with their secrets, and the releases remembered as missing and the
 * recent searches as they were before the test are put back.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseListAfterFailureDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container = instrumentation.targetContext.container
    private val settings = container.settings
    private val library = container.library
    private val home = HomeContentShown()
    private val realFetch = PlaybackCoordinator.fetchNzb
    private var tmdb: FakeTmdb? = null
    /** A provider's port that never accepts a connection or answers: [aPlayChosenWhileTheListChecksTheProvidersStarts]'s. */
    private var silent: ServerSocket? = null
    private lateinit var nav: NavHostController
    private lateinit var saved: AppSettings
    private lateinit var tried: List<TriedRelease>
    private lateinit var recent: Set<String>

    private val today = LocalDate.now()

    private val harborLights = ShowDetails(
        SHOW, "Harbor Lights", null, 2021, null, null, null, "Returning Series", true, 45,
        listOf(SeasonSummary(1, null, 3, today.minusYears(1), null)),
        lastEpisode = EpisodeSummary(1, 3, "The Breakwater", today.minusYears(1).plusDays(14)), nextEpisode = null,
        ids = ExternalIds(tvdb = 900001, imdb = "tt9000003"),
    )

    private val seasonOne = Season(
        SHOW, 1, null, null,
        listOf(
            Episode(1, 1, "Lamplighter", null, today.minusYears(1), 45, null),
            Episode(1, 2, "Salt and Signal", null, today.minusYears(1).plusDays(7), 45, null),
            Episode(1, 3, "The Breakwater", null, today.minusYears(1).plusDays(14), 45, null),
        ),
    )

    private val copperCrown = MovieDetails(
        MOVIE, "Copper Crown", null, 2019, "A retired forger is pulled into one last job when a crown goes missing.", null, null, 118,
        ExternalIds(imdb = "tt9000012"), releaseDate = LocalDate.of(2019, 3, 1), status = "Released",
    )

    @Before
    fun setUp() = runBlocking<Unit> {
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", reachable(1119))
        assumeTrue("fake indexer not reachable at 10.0.2.2:8089 (mock-seed indexer)", reachable(8089))
        saved = settings.settings.first()
        tried = library.triedReleaseEntries()
        recent = library.recentSearches.first().map { it.key }.toSet()
        // Hidden, not deleted: deleting would remove their passwords from the secret store.
        settings.restore(saved.copy(providers = saved.providers.map { it.copy(deleted = true) }))
        val mock = settings.saveProvider(ProviderRecord("", "Mock", "10.0.2.2", 1119, useTls = false, connections = 4), "")
        settings.indexers.first().filter { it.enabled }.forEach { settings.setIndexerEnabled(it.id, false) }
        settings.saveIndexer(FAKE_INDEXER, "Fake (release list test)", "http://10.0.2.2:8089", "fake-indexer-key")
        // The app's own provider sync gives the engine the mock just saved.
        engineSetup("the engine on the saved mock") { container.engine.configuredProviders.first { it == listOf(mock) } }
        home.show()
        tmdb = FakeTmdb(shows = mapOf(SHOW to harborLights), seasons = { id, number -> seasonOne.takeIf { id == SHOW && number == 1 } }, movies = mapOf(MOVIE to copperCrown))
    }

    @After
    fun restore() = runBlocking<Unit> {
        instrumentation.setInTouchMode(true)
        tmdb?.close()
        silent?.close()
        PlaybackCoordinator.fetchNzb = realFetch
        container.playback.cancel()
        // Skipped before anything was changed (a fake isn't reachable).
        if (!::saved.isInitialized) return@runBlocking
        home.restore()
        settings.providers.first().filter { p -> saved.providers.none { it.id == p.id } }.forEach { settings.deleteProvider(it.id) }
        settings.settings.first().providers.forEach { container.providerTests.forget(it.id) }
        if (saved.indexers.none { it.id == FAKE_INDEXER } && settings.indexer(FAKE_INDEXER) != null) settings.deleteIndexer(FAKE_INDEXER)
        settings.restore(saved)
        library.restoreTriedReleases(tried)
        library.recentSearches.first().filter { it.key !in recent }.forEach { library.removeSearch(it.key) }
        // The app's own provider sync puts the engine back on the restored providers; let it before the next test.
        engineOnSavedProviders()
    }

    /**
     * The Show page's Choose release: BROKEN, chosen, fails on the availability check; the list is there behind the sheet,
     * BROKEN marked as the sheet says, and stays once the sheet is closed. GOOD, chosen then, plays: the list closes.
     */
    @Test
    fun theShowPagesListComesBackMarkedAfterAChosenReleaseFailsAndClosesOnceOnePlays() {
        showTheApp()
        openShowList()
        compose.onNode(card(BROKEN)).performClick()
        val reason = failureOf(BROKEN)
        assertTrue(reason, reason.startsWith("Incomplete on your providers"))
        waitFor(card(BROKEN) and hasText("✕ $reason"))
        closeTheSheet()
        waitFor(card(BROKEN) and hasText("✕ $reason"))
        assertTrue("the list of S01E01's releases", exists(card(GOOD)))

        compose.onNode(card(GOOD)).performClick()
        assertEquals("$FAKE_INDEXER/hl-s01e01-good", launched().release.key)
        waitFor(hasContentDescription("More for episode 1"))
        assertTrue("the list still shows over the page", !exists(card(GOOD)))
    }

    /** Cancel while a chosen release starts: the list as it was, nothing marked. */
    @Test
    fun cancelOnThePlaySheetGoesBackToTheList() {
        val grab = CompletableDeferred<Unit>()
        PlaybackCoordinator.fetchNzb = { c, indexer, item -> grab.await(); realFetch(c, indexer, item) }
        showTheApp()
        openShowList()
        compose.onNode(card(GOOD)).performClick()
        waitFor(hasText("Fetching NZB"))
        compose.onNode(hasText("Cancel") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { container.playback.state.value == PlayState.Idle && !exists(hasText("Fetching NZB")) }
        grab.complete(Unit)
        compose.waitForIdle()
        assertTrue("the list after Cancel", exists(card(GOOD)) && exists(card(BROKEN)))
        assertTrue("a release marked", !exists(hasText("✕", substring = true)))
    }

    /**
     * The Show page's Choose release: the S01 pack's row, and no single episode's, offers Choose file (its ⋮), which asks
     * which of the pack's videos is S01E01, marking the one named for it rather than say none is. Back stops
     * the play, back on the list as it was; asked again, the file picked plays as S01E01, whatever its name says, and the
     * list closes.
     */
    @Test
    fun aSeasonPacksRowOffersChooseFileWhichAsksWhichFilePlays() {
        showTheApp()
        openShowList()
        waitFor(card(PACK))
        assertTrue("a single episode's row offers Choose file", !exists(hasContentDescription("More for $GOOD")))
        chooseFile()
        val question = waitForQuestion()
        assertEquals((1..3).map { "Harbor.Lights.S01E0$it.1080p.WEB-DL.AAC2.0.H.264-PACK.mkv" }.toSet(), question.files.map { it.name }.toSet())
        assertEquals(PACK, question.release)
        // The user asked to choose: one is named for S01E01, which the sheet marks rather than say none is.
        assertTrue("says no file is named for it", !exists(hasText("named for it", substring = true) and hasAnyAncestor(isDialog())))
        assertTrue("asks for the file to play", exists(hasText("Choose the file to play as S01E01:") and hasAnyAncestor(isDialog())))
        assertEquals(1, compose.onAllNodes(hasText("Named S01E01", substring = true) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().size)
        compose.waitForIdle()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        // The sheet goes away as its animation ends, on the test's clock.
        compose.waitForIdle()
        compose.waitUntil(5_000) { container.playback.state.value == PlayState.Idle }
        compose.waitForIdle()
        assertTrue("the list after Back", exists(card(PACK)) && exists(card(GOOD)))
        assertTrue("a release marked", !exists(hasText("✕", substring = true)))

        chooseFile()
        waitForQuestion()
        val second = "Harbor.Lights.S01E02.1080p.WEB-DL.AAC2.0.H.264-PACK.mkv"
        compose.onNode(hasText(second) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        val launch = launched()
        assertEquals("$FAKE_INDEXER/hl-s01-pack", launch.release.key)
        assertEquals(second, launch.release.fileName)
        assertEquals(1, launch.watch.episode)
        waitFor(hasContentDescription("More for episode 1"))
    }

    /** The Movie page's Choose release: MISLABEL fails on the track probe; the list comes back marked, and GOOD plays and closes it. */
    @Test
    fun theMoviePagesListComesBackMarkedAfterAChosenReleaseFailsAndClosesOnceOnePlays() {
        showTheApp()
        compose.runOnIdle { nav.navigate(MovieRoute(MOVIE)) }
        waitFor(hasText("Choose release") and hasClickAction(), 15_000)
        compose.onNode(hasText("Choose release") and hasClickAction()).performClick()
        waitFor(card(MISLABEL), 60_000)
        compose.onNode(card(MISLABEL)).performClick()
        val reason = failureOf(MISLABEL)
        assertTrue(reason, reason.startsWith("No audio this device can play"))
        closeTheSheet()
        waitFor(card(MISLABEL) and hasText("✕ $reason"))

        compose.onNode(card(GOOD_MOVIE)).performClick()
        assertEquals("$FAKE_INDEXER/cc-2019-good", launched().release.key)
        waitFor(hasText("Choose release") and hasClickAction())
        assertTrue("the list still shows over the page", !exists(card(GOOD_MOVIE)))
    }

    /**
     * A play chosen while the release list checks the providers starts once the check is done, though the test thread
     * then blocks in a plain runBlocking, which runs none of the list's work (#273). A second provider, a port here that
     * never accepts or greets, holds the list's check, under the engine's lock, for its 10 s login wait; MISLABEL, chosen
     * meanwhile, fails on the track probe as it should, and the silent provider is left out. Before the fix the check
     * came back to the list's dispatcher with the lock held, and the play waited behind it for good.
     */
    @Test
    fun aPlayChosenWhileTheListChecksTheProvidersStarts() {
        val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).also { silent = it }
        val id = runBlocking {
            val id = settings.saveProvider(ProviderRecord("", "Silent", socket.inetAddress.hostAddress, socket.localPort, useTls = false, connections = 1), "")
            engineSetup("the engine on the mock and the silent provider") { container.engine.configuredProviders.first { it.size == 2 && it.last() == id } }
            id
        }
        showTheApp()
        compose.runOnIdle { nav.navigate(MovieRoute(MOVIE)) }
        waitFor(hasText("Choose release") and hasClickAction(), 15_000)
        compose.onNode(hasText("Choose release") and hasClickAction()).performClick()
        waitFor(card(MISLABEL), 60_000)
        // The list's check has the lock until the silent provider's login wait ends.
        compose.waitUntil(10_000) { container.engine.lockState?.contains("release list marks") == true }
        compose.onNode(card(MISLABEL)).performClick()
        val reason = failureOf(MISLABEL)
        assertTrue(reason, reason.startsWith("No audio this device can play"))
        assertEquals(setOf(id), container.engine.leftOut.value.keys)
    }

    /** Search's release list stays under the sheet, as before: BROKEN, chosen there and failed, is marked when the user is back on it. */
    @Test
    fun searchsListMarksAChosenReleaseThatFails() {
        showTheApp()
        compose.runOnIdle { nav.switchTab(SearchRoute(query = "Harbor Lights S01E01"), restore = false) }
        // The group's releases (#160: a tap on its row plays it).
        val group = hasContentDescription("releases for S01E01", substring = true) and hasClickAction()
        waitFor(group, 60_000)
        compose.onAllNodes(group)[0].performClick()
        waitFor(card(BROKEN))
        compose.onNode(card(BROKEN)).performClick()
        val reason = failureOf(BROKEN)
        closeTheSheet()
        waitFor(card(BROKEN) and hasText("✕ $reason"))
        assertTrue("the list of S01E01's releases", exists(card(GOOD)))
    }

    /**
     * Search's row for S01E01 and its release list show the ranker's estimate of the bitrate after the size. The fake
     * indexer's releases are its 10 MB test clip: over an episode's 45 minutes, under a tenth of a Mbps.
     */
    @Test
    fun searchsRowAndReleaseListShowTheBitrateAfterTheSize() {
        showTheApp()
        compose.runOnIdle { nav.switchTab(SearchRoute(query = "Harbor Lights S01E01"), restore = false) }
        val row = SemanticsMatcher("plays S01E01") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Play" } and hasText("S01E01")
        waitFor(row, 60_000)
        val texts = compose.onAllNodes(row)[0].fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }
        val size = texts.indexOfFirst { SIZE.matches(it) }
        assertTrue("no size among $texts", size >= 0)
        assertEquals("the chip after the size: $texts", "< 0.1 Mbps", texts.getOrNull(size + 1))
        compose.onAllNodes(hasContentDescription("releases for S01E01", substring = true) and hasClickAction())[0].performClick()
        waitFor(card(GOOD))
        assertTrue("GOOD's card shows its bitrate", exists(card(GOOD) and hasText("< 0.1 Mbps")))
    }

    /**
     * With a remote, on a TV: OK on BROKEN starts it, the sheet takes the focus, and once its failure is closed the focus is
     * on BROKEN again, marked, in the list, so the next press picks another.
     */
    @Test
    fun withARemoteTheFocusIsOnTheFailedReleaseOnceTheSheetCloses() {
        val remote = Remote(compose)
        compose.setTvContent {
            nav = rememberNavController()
            AppNav(nav = nav)
        }
        compose.waitForIdle()
        openShowList()
        instrumentation.setInTouchMode(false)
        val reached = remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, BROKEN)
        assertTrue("never reached BROKEN; focus on \"$reached\"", BROKEN in reached)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        val reason = failureOf(BROKEN)
        remote.waitForFocus("Close")
        // Past the sheet's guard against an OK meant for what opened it.
        Thread.sleep(500)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { container.playback.state.value == PlayState.Idle }
        remote.waitForFocus(BROKEN)
        assertTrue("the focused release isn't marked: \"${remote.focused()}\"", "✕ $reason" in remote.focused())
    }

    private fun showTheApp() {
        compose.setContent {
            AppTheme {
                nav = rememberNavController()
                AppNav(nav = nav)
            }
        }
        compose.waitForIdle()
    }

    /** Harbor Lights' page, then S01E01's Choose release, until its list shows. */
    private fun openShowList() {
        compose.runOnIdle { nav.navigate(ShowRoute(SHOW)) }
        waitFor(hasContentDescription("More for episode 1"), 15_000)
        compose.onNode(hasContentDescription("More for episode 1")).performClick()
        // The menu's item: a TV's page has a Choose release of its own, for the next-up episode.
        val chooseInMenu = hasText("Choose release") and hasAnyAncestor(isPopup())
        waitFor(chooseInMenu)
        compose.onNode(chooseInMenu).performClick()
        waitFor(card(BROKEN), 60_000)
    }

    /** The pack's menu, and its Choose file. */
    private fun chooseFile() {
        compose.onNode(hasContentDescription("More for $PACK")).performClick()
        waitFor(hasText("Choose file"))
        compose.onNode(hasText("Choose file")).performClick()
    }

    /** The play sheet's question of which of the pack's videos is S01E01, once it shows; a failure as soon as one does. */
    private fun waitForQuestion(): PlayState.ChoosingFile {
        compose.waitUntil(90_000) {
            (container.playback.state.value as? PlayState.Failed)?.let { throw AssertionError("didn't ask: $it") }
            exists(hasText("Looking for S01E01 'Lamplighter'") and hasAnyAncestor(isDialog()))
        }
        return container.playback.state.value as PlayState.ChoosingFile
    }

    /** A release's card in the list, by its name. */
    private fun card(release: String) = hasText(release, substring = true) and hasClickAction() and !hasAnyAncestor(isDialog())

    /**
     * The play sheet's failure: why [release] didn't start, as its line says. A plain runBlocking on purpose: it runs none
     * of the compositions' queued work, which the v2 rule runs only in the test's syncs, and the play must get on all the
     * same (#273). One that never fails says where the play and the engine are.
     */
    private fun failureOf(release: String): String {
        val failed = runBlocking { withTimeoutOrNull(90_000) { container.playback.state.filterIsInstance<PlayState.Failed>().first() } }
            ?: throw AssertionError("$release didn't fail within 90 s: the play is ${container.playback.state.value}; ${engineNow()}")
        return failed.failures.singleOrNull { it.first.contains(release) }?.second ?: throw AssertionError("not $release's failure: $failed")
    }

    /** The failure sheet's Close. */
    private fun closeTheSheet() {
        waitFor(hasText("Close") and hasClickAction() and hasAnyAncestor(isDialog()))
        compose.onNode(hasText("Close") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(5_000) { container.playback.state.value == PlayState.Idle }
        compose.waitForIdle()
    }

    /**
     * The coordinator's next launch, or a failure as soon as the play sheet shows one. A plain runBlocking on purpose, as in
     * [failureOf]; one that never comes says where the play and the engine are.
     */
    private fun launched(): PlayerLaunch = runBlocking {
        withTimeoutOrNull(90_000) {
            merge(
                container.playback.launches,
                container.playback.state.filterIsInstance<PlayState.Failed>().map { throw AssertionError("didn't start: $it") },
            ).first()
        } ?: throw AssertionError("nothing launched within 90 s: the play is ${container.playback.state.value}; ${engineNow()}")
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) = compose.waitUntil(timeoutMs) { exists(matcher) }

    private fun reachable(port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", port), 2_000) } }.isSuccess

    private companion object {
        /** Harbor Lights' and Copper Crown's ids in the fake catalogue's TMDB numbering; nothing real has them. */
        const val SHOW = 900002
        const val MOVIE = 900011
        const val FAKE_INDEXER = "release-list-device-test"
        const val BROKEN = "Harbor.Lights.S01E01.PROPER.1080p.WEB-DL.AAC2.0.H.264-BROKEN"
        const val GOOD = "Harbor.Lights.S01E01.1080p.WEB-DL.AAC2.0.H.264-GOOD"
        const val MISLABEL = "Copper.Crown.2019.PROPER.1080p.WEB-DL.AAC2.0.H.264-MISLABEL"
        const val GOOD_MOVIE = "Copper.Crown.2019.1080p.BluRay.x264-GOOD"
        const val PACK = "Harbor.Lights.S01.1080p.WEB-DL.AAC2.0.H.264-PACK"
        /** A size chip: "650 KB", "9.7 MB", "2.1 GB". */
        val SIZE = Regex("""^\d+(\.\d)? (KB|MB|GB)$""")
    }
}
