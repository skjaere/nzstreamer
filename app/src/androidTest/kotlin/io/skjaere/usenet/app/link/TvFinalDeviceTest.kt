package io.skjaere.usenet.app.link

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.collection.ShowScreen
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.home.HomeScreen
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.CollectedShow
import io.skjaere.usenet.data.EpisodeMark
import io.skjaere.usenet.data.EpisodeRef
import io.skjaere.usenet.data.ShowSummary
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.link.FinalKind
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.LinkMessage.Final
import io.skjaere.usenet.link.LinkMessage.Status
import io.skjaere.usenet.link.Playback
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Progress on the phone from where its play on a TV stopped (item 27, W5, L11, L33; PtPhoneStopped, PtPhoneLost 4) on a
 * real device, through the app's own links, start, Finals and library: a TV in the test's process on this device's own
 * address, paired with the app's key and chosen, takes the phone's Play, then says where it stopped, as NZStreamer on a
 * TV would. The Final is saved and acked, and Resume on the Show page, the episode's row, Home's Up next and the
 * collection all agree; the phone says where it stopped in the Undo bar's look, for TalkBack once; a watched one is
 * watched here too, a late one says the TV stopped, and one of another session is dropped, acked all the same. The TV
 * and the show are invented; the app's library, settings and link.json are put back after.
 */
@RunWith(AndroidJUnit4::class)
class TvFinalDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container
    private val library = container.library
    private lateinit var playing: PlayingTv
    private var tmdb: FakeTmdb? = null
    private var keyAdded = false
    private lateinit var saved: AppSettings

    /**
     * The episodes a test's Finals wrote, marked unwatched again after: a row can't be taken away, and the one the mark
     * leaves is newer than a late Final of the same episode in a test after, so each test's late one is an episode no
     * other test writes.
     */
    private val written = mutableSetOf<Int>()

    private val show = ShowDetails(
        SHOW, "Harbor Lights", null, 2021, null, null, null, "Ended", false, 52,
        listOf(SeasonSummary(1, null, 6, LocalDate.of(2021, 3, 1), null)),
        lastEpisode = EpisodeSummary(1, 6, "Episode 6", LocalDate.of(2021, 4, 5)), nextEpisode = null, ids = ExternalIds(tvdb = 991_607),
    )

    private val season = Season(SHOW, 1, null, null, (1..6).map { Episode(1, it, "Episode $it", null, LocalDate.of(2021, 3, 1).plusWeeks(it - 1L), 52, null) })

    @Before
    fun setUp() {
        assumeFalse("a TV plays itself", container.television)
        runBlocking {
            assumeTrue("no Usenet provider saved, so Home shows the first-run screen", container.settings.providers.first().isNotEmpty())
            saved = container.settings.settings.first()
            container.settings.setIndexerSetupSkipped(true)
            if (container.settings.tmdbKey() == null) {
                container.settings.setTmdbKey("tv-final-device-test")
                keyAdded = true
            }
            // In the collection, with what TMDB said of it, as the refresher keeps it.
            library.addShow(CollectedShow(CollectedShow.idFor(SHOW), SHOW, null, null, null, "Harbor Lights", 2021, null, 0, 0))
            library.saveSummaries(
                listOf(ShowSummary(SHOW, status = "Ended", seasons = "1:6", last = EpisodeMark(1, 6, "Episode 6", LocalDate.of(2021, 4, 5).toEpochDay()))),
                emptyList(), emptyList(),
            )
        }
        tmdb = FakeTmdb(shows = mapOf(SHOW to show), seasons = { id, _ -> if (id == SHOW) season else null })
        playing = PlayingTv()
    }

    @After
    fun restore() {
        if (::playing.isInitialized) playing.close()
        tmdb?.close()
        if (!::saved.isInitialized) return
        runBlocking {
            library.markEpisodes(written.map { EpisodeRef(SHOW, 1, it, WatchTitle.episode("Harbor Lights", 1, it)) }, watched = false)
            library.removeShow(SHOW)
            container.settings.restore(saved)
            if (keyAdded) container.settings.setTmdbKey(null)
        }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, millis: Long = 15_000) = compose.waitUntil(millis) { exists(matcher) }

    /** Where Harbor Lights S01E0[episode] stopped on the TV, in the session the phone started there (or [session]). */
    private fun final(id: String, episode: Int = 3, positionMs: Long = 1_930_000, watched: Boolean = false, ageMs: Long = 0, session: String = PlayingTv.SESSION) = written.add(episode).let { Final(
        id, session, WatchKey.episode(SHOW, 1, episode), FinalKind.Episode, "Harbor Lights S01E0$episode", showTmdbId = SHOW, season = 1, episode = episode,
        positionMs = positionMs, durationMs = 3_120_000, watched = watched, ageMs = ageMs,
    ) }

    private fun acked(id: String) = compose.waitUntil(10_000) { playing.sent<LinkMessage.FinalAck>().any { id in it.ids } }

    private fun row(episode: Int = 3) = runBlocking { library.state(WatchKey.episode(SHOW, 1, episode)).first() }

    /** The page the test shows, below the line the app says. */
    private var page by mutableStateOf("show")

    private fun pages() = compose.setContent {
        AppTheme {
            Box {
                when (page) {
                    "show" -> ShowScreen(SHOW, onBack = {})
                    "home" -> HomeScreen(onSearch = {}, onRecent = {}, onSettings = {}, onSetup = { _, _ -> }, onShow = {}, onCollection = {}, onImport = {})
                }
                RemoteSaidBar(container.tvPlaying, Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    @Test
    fun whereItStoppedIsWhereResumeTheRowUpNextAndTheCollectionAllPickUp() {
        pages()
        waitFor(hasText("Episode 3"))
        playing.play(Playback.Playing)
        // Stop on the TV: where it stopped first, its stopped status after.
        playing.say(final("f-1"))
        acked("f-1")
        playing.say(Status(PlayingTv.SESSION, Playback.Stopped, mine = true))
        val line = stoppedOnAt(playing.name, 1_930_000)
        waitFor(hasText(line))
        assertEquals("read once as it comes", LiveRegionMode.Polite, compose.onNode(hasText(line)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))
        assertEquals("saved here", 1_930_000L, row()?.positionMs)
        // The Show page: Resume from there, on the chosen TV, and the episode's row.
        waitFor(hasText("Resume S01E03 · 32:10", substring = true))
        waitFor(hasText("19:50 left"))
        // The collection.
        val collected = runBlocking { withTimeout(10_000) { container.collection.shows.first { rows -> rows?.any { it.show.tmdbId == SHOW && it.status.startsWith("Resume") } == true } } }
        assertTrue(collected!!.single { it.show.tmdbId == SHOW }.status.startsWith("Resume S01E03"))
        // Home's Up next.
        compose.runOnIdle { page = "home" }
        waitFor(hasContentDescription("Resume Harbor Lights S01E03 on ${playing.name}"))
        waitFor(hasText("S01E03", substring = true) and hasText("20 min left", substring = true))
        // The line goes after about 4 s.
        compose.waitUntil(10_000) { !exists(hasText(line)) }
    }

    @Test
    fun aWatchedOneIsWatchedHereALateOneSaysTheTvStoppedAndAnotherSessionsIsDropped() {
        pages()
        waitFor(hasText("Episode 3"))
        playing.play(Playback.Playing)
        playing.say(Status(PlayingTv.SESSION, Playback.Stopped, mine = true))
        compose.waitUntil(5_000) { container.tvPlaying.now.value == null }
        // Watched on the TV.
        playing.say(final("f-2", positionMs = 3_100_000, watched = true))
        acked("f-2")
        waitFor(hasText(watchedOn(playing.name)))
        assertEquals(0L to true, row()?.let { it.positionMs to it.watched })
        waitFor(hasText("Play S01E04", substring = true))
        // Heard the next time the phone reaches the TV: told late.
        playing.say(final("f-3", episode = 6, positionMs = 2_462_000, ageMs = 600_000))
        acked("f-3")
        waitFor(hasText(stoppedAt(playing.name, 2_462_000)))
        assertEquals(2_462_000L, row(6)?.positionMs)
        // Another session's: dropped, acked all the same.
        playing.say(final("f-4", episode = 5, session = "s-another"))
        acked("f-4")
        Thread.sleep(500)
        assertTrue("nothing saved", row(5)?.positionMs.let { it == null || it == 0L })
    }

    private companion object {
        const val SHOW = PlayingTv.SHOW
    }
}
