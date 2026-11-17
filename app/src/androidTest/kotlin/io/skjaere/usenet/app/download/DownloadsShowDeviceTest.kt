package io.skjaere.usenet.app.download

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.HL
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.assertWhole
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.episode
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.exists
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.scrolledTo
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.show
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.waitFor
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.waitTill
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.EpisodeRef
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.data.WatchTitle
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
 *
 * Its rows are deleted after each case, and the show's watch states put back unwatched, as the downloads' other tests
 * leave theirs, and the settings a case took as they were. The show and its episodes are invented.
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

    @Before
    fun setUp() = runBlocking<Unit> {
        seeds.before()
        val sizes = listOf(1_700_000_000L, 1_700_000_000, 1_700_000_000, 2_100_000_000, 2_000_000_000, 2_000_000_000)
        val runtimes = listOf(52, 50, 54, 52, 49, 51)
        episodes = (0 until 6).map { i ->
            seeds.seed(episode(if (i < 3) 1 else 2, i % 3 + 1, DownloadState.Done, size = sizes[i], runtime = runtimes[i], gaps = if (i == 5) 3 else 0))
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
        seeds.after()
        library.markEpisodes(REFS, watched = false)
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

    private fun show(fontScale: Float = 1f) {
        val deps = seeds.deps()
        compose.show(context, fontScale) { DownloadsShowScreen(deps, HL, onBack = { backs++ }, onOpenShow = { opened += it }) }
    }

    private companion object {
        const val SEEDING_MILLIS = 60_000L
        val REFS = listOf(1 to 1, 1 to 2, 1 to 3, 2 to 1, 2 to 2, 2 to 3).map { (s, e) -> EpisodeRef(HL, s, e, WatchTitle.episode("Harbor Lights", s, e)) }
    }
}
