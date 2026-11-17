package io.skjaere.usenet.app.download

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.cast.CastFakes
import io.skjaere.usenet.app.cast.CastSeams
import io.skjaere.usenet.app.cast.FakeConnector
import io.skjaere.usenet.app.cast.FakeLauncher
import io.skjaere.usenet.app.cast.FakeRoutes
import io.skjaere.usenet.app.cast.GoogleCast
import io.skjaere.usenet.app.cast.castRoute
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.casting.connectingTo
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.HL
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.assertWhole
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.episode
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.exists
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.scrolledTo
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.show
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.waitFor
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.waitTill
import io.skjaere.usenet.app.everyStep
import io.skjaere.usenet.app.link.CastButtonLook
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.EpisodeRef
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.player.PlayUris
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
 * A show's downloads on a phone (item 19, L372): Harbor Lights' finished episodes seeded through the app's own storage,
 * Season 1 watched and Season 2's first begun, as the library keeps them.
 * - By season, each row's size and length, what is left of the one begun, ✓ on the watched, the parts missing; the
 *   episode still downloading left on the Downloads page; Resume at the top.
 * - Resume and a row's tap play the downloads' files (a seam: no player).
 * - Remove download with its Undo; Mark watched; Open Harbor Lights.
 * - At twice the font size, nothing cut off.
 * - With Delete once watched on, Season 1's watched episodes say "Deleting tomorrow", whole at twice the font size too,
 *   and nothing does once it is off (C4, L415).
 * - The bar (L453): Select, then the Cast button; none in select mode.
 * - With Kitchen display connected (Cast's fakes, L73): Resume shows Google's Cast icon and "on Kitchen display", an
 *   episode's tap is read "Play on Kitchen display", and Resume casts the next episode's file there (L418, L470). While
 *   it connects, Resume greys with "Connecting to Kitchen display…" under it, and lights up once it is connected.
 *
 * Its rows are deleted after each case, and the show's watch states put back unwatched, as the downloads' other tests
 * leave theirs, and the settings a case took as they were; where a case chose Kitchen display, Play on is put back to
 * This phone and Cast's seams reset. The show and its episodes are invented.
 */
@RunWith(AndroidJUnit4::class)
class DownloadsShowDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val library = container.library
    private val seeds = DownloadsPageSeeds(context, container)
    private val opened = mutableListOf<Int>()
    private var backs = 0
    private lateinit var episodes: List<Download>

    // Cast's, where a case chose Kitchen display: put back after.
    private val routes = FakeRoutes()
    private val connector = FakeConnector()
    private val launcher = FakeLauncher()
    private val kitchen = castRoute("Kitchen display")
    private var cast = false

    @Before
    fun setUp() = runBlocking<Unit> {
        seeds.before()
        val sizes = listOf(1_700_000_000L, 1_700_000_000, 1_700_000_000, 2_100_000_000, 2_000_000_000, 2_000_000_000)
        val runtimes = listOf(52, 50, 54, 52, 49, 51)
        episodes = (0 until 6).map { i ->
            val row = episode(if (i < 3) 1 else 2, i % 3 + 1, DownloadState.Done, size = sizes[i], runtime = runtimes[i], gaps = if (i == 5) 3 else 0)
            // Its release kept as Resolve keeps one, so its file can be cast.
            val name = "Harbor.Lights.S0${row.season}E0${row.episode}.1080p.WEB-DL.DDP5.1.H.264-KESTREL.mkv"
            seeds.seed(row.copy(release = RememberedRelease("dl-page-a", row.id, name.removeSuffix(".mkv"), null, 0, "nzb-${row.id}", name, row.size), fileName = name))
        }
        seeds.seed(episode(2, 4, DownloadState.Running, size = 2_000_000_000, file = true, written = 400_000_000))
        library.markEpisodes(REFS.take(3), watched = true)
        val begun = WatchState(
            key = WatchKey.episode(HL, 2, 1), kind = WatchKind.Episode, showTmdbId = HL, season = 2, episode = 1, title = WatchTitle.episode("Harbor Lights", 2, 1),
            updatedAt = 1,
        )
        library.recordProgress(begun, 20 * 60_000L, 52 * 60_000L)
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        everyStep(
            { if (cast) onMainThread { container.playback.cancel() } },
            { if (cast) container.playOn.choose(PlayChoice.Phone) },
            { if (cast) CastFakes.reset() },
            { seeds.after() },
            { library.markEpisodes(REFS, watched = false) },
        )
    }

    @Test
    fun itsEpisodesBySeasonWithResumeAtTheTop() {
        show()
        compose.waitFor(hasText("Resume S02E01 · 20:00"))
        listOf(
            "Harbor Lights", "6 episodes · 11.2 GB", "SEASON 1", "First Light", "1.7 GB · 52 min", "Pilot Boat", "1.7 GB · 50 min", "The Sandbar",
            "SEASON 2", "Brine", "2.1 GB · 52 min", "32:00 left", "The Breakwater", "2.0 GB · 49 min", "Fog Signal", "2.0 GB · 51 min · 3 parts missing",
        ).forEach { assertTrue("'$it' shown", compose.scrolledTo(hasText(it))) }
        assertEquals("Season 1's three watched", 3, compose.onAllNodes(hasText("✓"), useUnmergedTree = true).fetchSemanticsNodes().size)
        // Each season's header is a heading, for TalkBack's heading navigation.
        listOf("SEASON 1", "SEASON 2").forEach { assertTrue("'$it' a heading", compose.scrolledTo(hasText(it) and isHeading())) }
        assertFalse("the one still downloading stays on the Downloads page", compose.scrolledTo(hasText("Low Water")))
    }

    @Test
    fun resumeAndARowsTapPlayTheFiles() {
        show()
        compose.waitFor(hasText("Resume S02E01 · 20:00") and hasClickAction())
        compose.onNode(hasText("Resume S02E01 · 20:00") and hasClickAction()).performClick()
        compose.waitTill("Resume played S02E01") { seeds.played.toList() == listOf(episodes[3].id) }
        assertTrue(compose.scrolledTo(hasText("Pilot Boat")))
        compose.onNode(hasText("Pilot Boat") and hasClickAction()).performClick()
        compose.waitTill("the row played S01E02") { seeds.played.toList() == listOf(episodes[3].id, episodes[1].id) }
    }

    @Test
    fun removeDownloadOffersUndoAndMarkWatchedAndOpenTheShow() {
        show()
        val fog = episodes[5]
        compose.waitFor(hasText("Harbor Lights"))
        assertTrue(compose.scrolledTo(hasContentDescription("More for Harbor Lights S02E03")))
        compose.onNode(hasContentDescription("More for Harbor Lights S02E03")).performClick()
        compose.waitFor(hasText(DownloadsPage.REMOVE_DOWNLOAD))
        assertTrue(compose.exists(hasText(DownloadsPage.MARK_WATCHED)))
        assertTrue(compose.exists(hasText("Open Harbor Lights")))
        compose.onNode(hasText(DownloadsPage.REMOVE_DOWNLOAD) and hasClickAction()).performClick()
        compose.waitFor(hasText("Deleted Harbor Lights S02E03 · 2.0 GB"))
        compose.waitTill("hidden at once") { seeds.row(fog.id)?.state == DownloadState.Deleting }
        compose.waitFor(hasText("5 episodes · 9.2 GB"))
        compose.onNode(hasText("Undo") and hasClickAction()).performClick()
        compose.waitTill("brought back") { seeds.row(fog.id)?.state == DownloadState.Done }
        compose.waitFor(hasText("6 episodes · 11.2 GB"))

        assertTrue(compose.scrolledTo(hasContentDescription("More for Harbor Lights S02E02")))
        compose.onNode(hasContentDescription("More for Harbor Lights S02E02")).performClick()
        compose.waitFor(hasText(DownloadsPage.MARK_WATCHED) and hasClickAction())
        compose.onNode(hasText(DownloadsPage.MARK_WATCHED) and hasClickAction()).performClick()
        compose.waitTill("S02E02 marked watched") { runBlocking { library.episodeStates(HL).first()[2 to 2]?.watched == true } }

        assertTrue(compose.scrolledTo(hasContentDescription("More for Harbor Lights S01E01")))
        compose.onNode(hasContentDescription("More for Harbor Lights S01E01")).performClick()
        compose.waitFor(hasText(DownloadsPage.MARK_UNWATCHED), timeoutMs = 5_000)
        compose.onNode(hasText("Open Harbor Lights") and hasClickAction()).performClick()
        compose.waitTill("its Show page asked for") { opened == listOf(HL) }
    }

    @Test
    fun withDeleteOnceWatchedOnTheWatchedSayDeletingTomorrowAndNothingDoesOnceItIsOff() = runBlocking<Unit> {
        withTimeout(SEEDING_MILLIS) { container.devSettingsSaved.await() }
        val saved = container.settings.settings.first()
        try {
            container.settings.setDownloadsDeleteOnceWatched(true)
            show(fontScale = 2f)
            compose.waitFor(hasText(AutoDelete.DELETING_TOMORROW))
            assertEquals("Season 1's three watched", 3, compose.onAllNodes(hasText(AutoDelete.DELETING_TOMORROW), useUnmergedTree = true).fetchSemanticsNodes().size)
            compose.assertWhole(AutoDelete.DELETING_TOMORROW, context.resources.displayMetrics.widthPixels)
            // Under its size and length, in its row: TalkBack hears it with the row.
            assertTrue(compose.scrolledTo(hasText(AutoDelete.DELETING_TOMORROW, substring = true) and hasText("First Light", substring = true)))
            container.settings.setDownloadsDeleteOnceWatched(false)
            compose.waitTill("nothing says it goes once it is off") { !compose.exists(hasText(AutoDelete.DELETING_TOMORROW, substring = true)) }
        } finally {
            container.settings.restore(saved)
        }
    }

    @Test
    fun atTwiceTheFontSizeNothingIsCutOff() {
        show(fontScale = 2f)
        compose.waitFor(hasText("Resume S02E01 · 20:00"))
        val width = context.resources.displayMetrics.widthPixels
        listOf(
            "Resume S02E01 · 20:00", "Harbor Lights", "6 episodes · 11.2 GB", "First Light", "1.7 GB · 52 min", "Brine", "32:00 left", "The Breakwater",
            "Fog Signal", "2.0 GB · 51 min · 3 parts missing",
        ).forEach { compose.assertWhole(it, width) }
        // Select becomes its icon, read as its word.
        assertTrue(compose.exists(hasContentDescription(DownloadsPage.SELECT)))
    }

    /** L453: the bar's Select, then the Cast button (this phone's); none in select mode. */
    @Test
    fun theBarHasSelectThenTheCastButtonAndNoneWhileSelecting() {
        show()
        compose.waitFor(castButton)
        val select = bounds(hasContentDescription(DownloadsPage.SELECT) and hasClickAction())
        val button = bounds(castButton)
        assertTrue("Select at $select, the Cast button at $button", select.left < button.left)
        assertEquals(listOf("Play on: this phone"), castWords())
        compose.onNode(hasContentDescription(DownloadsPage.SELECT) and hasClickAction()).performClick()
        compose.waitFor(hasText(DownloadsPage.selected(0)))
        assertFalse("no Cast button while selecting", compose.exists(castButton))
    }

    /**
     * L418, L470: with Kitchen display connected, Resume shows Google's Cast icon and "on
     * Kitchen display" under its words, an episode's tap is read "Play on Kitchen display", and Resume casts the next
     * episode's file there: the launch handed to the device is S02E01's download.
     */
    @Test
    fun withKitchenDisplayConnectedResumeAndTheRowsSayItAndResumeCastsTheNextEpisodesFile() {
        connectKitchen()
        show(seeds.deps().copy(play = DownloadsDeps.of(container, context).play))
        compose.waitFor(hasText("on Kitchen display"))
        val resume = hasText("Resume S02E01 · 20:00") and hasClickAction()
        compose.onNode(resume).assertIsEnabled()
        assertEquals(listOf("Play on: Kitchen display"), castWords())
        assertTrue(compose.scrolledTo(hasText("Pilot Boat")))
        assertEquals("Play on Kitchen display", clickLabel(hasText("Pilot Boat") and hasClickAction()))
        compose.onNode(resume).performClick()
        compose.waitTill("the file handed to Kitchen display", 30_000) { launcher.loads.isNotEmpty() }
        val launch = launcher.loads.single()
        assertEquals(PlayUris.download(episodes[3].id), launch.uri)
        assertEquals("Kitchen display", launch.cast?.name)
        launcher.plays()
        compose.waitTill("the sheet closed") { container.playback.state.value == PlayState.Idle }
    }

    /**
     * L470, L97: while Kitchen display connects, Resume greys with "Connecting to Kitchen
     * display…" under it, and lights up by itself once the device is connected; the rows are read as playing there.
     */
    @Test
    fun whileKitchenDisplayConnectsResumeGreysWithItsLineAndLightsUpOnceConnected() {
        castStarted()
        onMainThread {
            GoogleCast.connection.attach(connector, context)
            GoogleCast.connection.connect(kitchen)
        }
        container.playOn.choose(PlayChoice.Cast(kitchen.id, kitchen.name))
        compose.waitTill("Kitchen display connecting") { (container.playOn.target.value as? PlayTarget.Cast)?.link == CastLink.Connecting }
        show()
        val resume = hasText("Resume S02E01 · 20:00") and hasClickAction()
        compose.waitFor(hasText(connectingTo(kitchen.name)))
        compose.onNode(resume).assertIsNotEnabled()
        assertEquals("Play on Kitchen display", clickLabel(hasText("Brine") and hasClickAction()))
        connector.started(kitchen)
        compose.waitTill("lit up once connected") { !compose.exists(hasText(connectingTo(kitchen.name))) }
        compose.onNode(resume).assertIsEnabled()
        assertTrue(compose.exists(hasText("on Kitchen display")))
    }

    private val castButton = SemanticsMatcher.keyIsDefined(CastButtonLook)

    /** What TalkBack reads of the Cast button now. */
    private fun castWords(): List<String>? = compose.onAllNodes(castButton).fetchSemanticsNodes().singleOrNull()?.config?.getOrNull(SemanticsProperties.ContentDescription)

    /** What TalkBack says a tap on [matcher]'s node does. */
    private fun clickLabel(matcher: SemanticsMatcher): String? = compose.onNode(matcher).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label

    private fun bounds(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().firstOrNull()?.boundsInRoot ?: throw AssertionError("not shown: ${matcher.description}")

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

    private fun show(deps: DownloadsDeps = seeds.deps(), fontScale: Float = 1f) {
        compose.show(context, fontScale) { DownloadsShowScreen(deps, HL, onBack = { backs++ }, onOpenShow = { opened += it }) }
    }

    private companion object {
        const val SEEDING_MILLIS = 60_000L
        val REFS = listOf(1 to 1, 1 to 2, 1 to 3, 2 to 1, 2 to 2, 2 to 3).map { (s, e) -> EpisodeRef(HL, s, e, WatchTitle.episode("Harbor Lights", s, e)) }
    }
}
