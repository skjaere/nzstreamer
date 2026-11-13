package io.skjaere.usenet.app.cast

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.casting.CastEnd
import io.skjaere.usenet.app.casting.CastFeed
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.casting.CastPlayerState
import io.skjaere.usenet.app.casting.castingTo
import io.skjaere.usenet.app.casting.playNextOn
import io.skjaere.usenet.app.collection.ShowScreen
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.home.HomeScreen
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.link.RemoteSaidBar
import io.skjaere.usenet.app.link.watchedOn
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.PlaySheetHost
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.CollectedShow
import io.skjaere.usenet.data.EpisodeMark
import io.skjaere.usenet.data.EpisodeRef
import io.skjaere.usenet.data.ShowSummary
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import io.skjaere.usenet.metadata.EpisodeRef as Next

/**
 * The next episode on the Watched line (W54 (a), W65, L125; CsPhoneWatchedNext) on a real device, over a fake Cast session
 * and launcher (Google's software never started, L73): Harbor Lights' S02E04 ends watched on Kitchen display, as its feed
 * says, its watch saved as the play's end saves it. Home and the Show page say "Watched on Kitchen display" with one
 * button, "Play S02E05 on Kitchen display", Up next's next episode of the show, read after the line; a tap starts S02E05
 * on the device still chosen, the play sheet saying "Casting to Kitchen display" (its search waits on an indexer this test
 * runs on the device, which never answers, so no indexer of the user's is asked). With the session gone, the line is alone.
 * The show, the device and the release are invented; the library and settings are put back.
 */
@RunWith(AndroidJUnit4::class)
class CastWatchedNextDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val container get() = compose.activity.container
    private val library get() = container.library
    private val routes = FakeRoutes()
    private val connector = FakeConnector()
    private val launcher = FakeLauncher()
    private val kitchen = castRoute("Kitchen display")
    private val device = CastTo(kitchen.id, kitchen.name, kitchen.deviceId, kitchen.model)
    private val episode = WatchTarget(WatchKind.Episode, WatchTitle.episode(SHOW_TITLE, 2, 4), showTmdbId = SHOW, season = 2, episode = 4)
    private var tmdb: FakeTmdb? = null
    private var indexer: Silent? = null
    private var keyAdded = false
    private lateinit var saved: AppSettings

    private val show = ShowDetails(
        SHOW, SHOW_TITLE, null, 2021, null, null, null, "Ended", false, 52,
        listOf(SeasonSummary(2, null, 6, LocalDate.of(2022, 3, 1), null)),
        lastEpisode = EpisodeSummary(2, 6, "Episode 6", LocalDate.of(2022, 4, 5)), nextEpisode = null, ids = ExternalIds(tvdb = 991_632),
    )

    private val season = Season(
        SHOW, 2, null, null,
        (1..6).map { Episode(2, it, NAMES.getOrElse(it) { "Episode $it" }, null, LocalDate.of(2022, 3, 1).plusWeeks(it - 1L), 52, null) },
    )

    @Before
    fun setUp() {
        assumeFalse("a TV never casts (tv.TvNoCastTest)", container.television)
        runBlocking {
            assumeTrue("no Usenet provider saved, so Home shows the first-run screen and a play stops at once", container.noUsableProvider() == null)
            saved = container.settings.settings.first()
            container.settings.setIndexerSetupSkipped(true)
            if (container.settings.tmdbKey() == null) {
                container.settings.setTmdbKey("cast-watched-next-device-test")
                keyAdded = true
            }
            // A search waits on this test's own indexer, which never answers: no indexer of the user's is asked.
            container.settings.indexers.first().filter { it.enabled }.forEach { container.settings.setIndexerEnabled(it.id, false) }
            val silent = Silent().also { indexer = it }
            container.settings.saveIndexer(INDEXER, "Silent (watched next test)", silent.url, "watched-next-key")
            // In the collection, with what TMDB said of it, as the refresher keeps it; S02E01-03 watched before.
            library.addShow(CollectedShow(CollectedShow.idFor(SHOW), SHOW, null, null, null, SHOW_TITLE, 2021, null, 0, 0))
            library.saveSummaries(
                listOf(ShowSummary(SHOW, status = "Ended", seasons = "2:6", last = EpisodeMark(2, 6, "Episode 6", LocalDate.of(2022, 4, 5).toEpochDay()))),
                emptyList(), emptyList(),
            )
            mark((1..3), watched = true)
        }
        tmdb = FakeTmdb(shows = mapOf(SHOW to show), seasons = { id, number -> if (id == SHOW && number == 2) season else null })
        CastFakes.started(routes, connector, CopyOnWriteArrayList())
        CastSeams.launcher = { launcher }
        CastFakes.connected(kitchen, connector)
        container.playOn.choose(PlayChoice.Cast(kitchen.id, kitchen.name))
        onMainThread { container.playback.withdraw() }
        waitUntil("Kitchen display connected") { container.playOn.target.value == PlayTarget.Cast(kitchen.id, kitchen.name, CastLink.Connected, kitchen.facts) }
    }

    @After
    fun restore() {
        if (!::saved.isInitialized) return
        onMainThread { container.playback.cancel() }
        container.playOn.choose(PlayChoice.Phone)
        CastFakes.reset()
        tmdb?.close()
        indexer?.close()
        runBlocking {
            mark((1..6), watched = false)
            library.removeShow(SHOW)
            if (container.settings.indexer(INDEXER) != null) container.settings.deleteIndexer(INDEXER)
            container.settings.restore(saved)
            if (keyAdded) container.settings.setTmdbKey(null)
        }
    }

    private suspend fun mark(episodes: IntRange, watched: Boolean) =
        library.markEpisodes(episodes.map { EpisodeRef(SHOW, 2, it, WatchTitle.episode(SHOW_TITLE, 2, it)) }, watched)

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, millis: Long = 15_000) = compose.waitUntil(millis) { exists(matcher) }

    private fun waitUntil(what: String, timeoutMs: Long = 20_000, probe: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (!probe()) {
            if (SystemClock.uptimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(50)
        }
    }

    /** The page the test shows, the line the app says at its foot and the play sheet, as the phone's shell has them. */
    private var page by mutableStateOf("home")

    private fun pages() = compose.setContent {
        AppTheme {
            Box(Modifier.fillMaxSize()) {
                when (page) {
                    "show" -> ShowScreen(SHOW, onBack = {}, onRemote = {})
                    "home" -> HomeScreen(onSearch = {}, onRecent = {}, onSettings = {}, onSetup = { _, _ -> }, onShow = {}, onCollection = {}, onImport = {})
                }
                Column(Modifier.align(Alignment.BottomCenter)) { RemoteSaidBar(container.remotePlaying, screen = page) }
                PlaySheetHost(container.playback, onSearch = {}, onProviders = {})
            }
        }
    }

    /**
     * S02E04 played there to its end, as the device's feed says it ([load]: its LOAD's own id); its watch saved a moment
     * after, as the play's end saves it, which the line's button waits for.
     */
    private fun watchedThere(load: String) {
        val launch = PlayerLaunch(
            UsenetUris.build("watched-next-nzb", 0, "harbor-lights.mkv", 2_000_000_000), SHOW_TITLE, "S02E04 · Low Water", WatchKey.episode(SHOW, 2, 4), episode,
            SavedRelease(INDEXER, RELEASE, RELEASE, null, 0, "watched-next-nzb", "harbor-lights.mkv", 2_000_000_000), cast = device,
        )
        onMainThread { Casting.follows(CastFeed(load, launch, CastPlayerState.Playing, LENGTH - 60_000, LENGTH)) }
        onMainThread { Casting.follows(CastFeed(load, launch, CastPlayerState.Idle, LENGTH, LENGTH, end = CastEnd(CastEnd.How.Watched, LENGTH))) }
        runBlocking { mark((4..4), watched = true) }
    }

    private val button = hasText(playNextOn(Next(2, 5), "Kitchen display")) and hasClickAction()

    /**
     * CsPhoneWatchedNext 1-3, CsPhoneTalkBackC2: the line and its button on Home, then on the Show page; the line read once
     * as it comes, the button after it as one; a tap casts S02E05 to Kitchen display, still connected and chosen.
     */
    @Test
    fun theWatchedLineOffersTheNextEpisodeOnHomeAndTheShowPageAndATapCastsIt() {
        pages()
        watchedThere("watched-next-${System.nanoTime()}")
        val line = watchedOn(kitchen.name)
        waitFor(hasText(line))
        waitFor(button)
        assertEquals(LiveRegionMode.Polite, compose.onNode(hasText(line)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))
        assertEquals(Role.Button, compose.onNode(button).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        assertFalse("the button isn't part of the line's reading", exists(hasText(line) and hasText(playNextOn(Next(2, 5), "Kitchen display"))))

        // The same wherever the phone is: the Show page.
        compose.runOnIdle { page = "show" }
        waitFor(hasText("Low Water"))
        assertEquals("still there, its 10 s not run out", true, exists(button))

        compose.onNode(button).performClick()
        waitUntil("S02E05 started for Kitchen display") { launcher.started.isNotEmpty() }
        val (to, said) = launcher.started.single()
        assertEquals(device, to)
        assertEquals("the next episode", WatchKey.episode(SHOW, 2, 5), said.mediaId)
        waitFor(hasText(castingTo(kitchen.name)))
        compose.waitUntil(5_000) { !exists(button) }
        assertEquals(PlayTarget.Cast(kitchen.id, kitchen.name, CastLink.Connected, kitchen.facts), container.playOn.target.value)
    }

    /** L125: after the session ended (Play went back to this phone, W36), a Watched line is alone, as in C1. */
    @Test
    fun withTheSessionGoneTheWatchedLineIsAlone() {
        pages()
        connector.ended(kitchen)
        waitUntil("This phone chosen") { container.playOn.chosen.value == PlayChoice.Phone }
        watchedThere("watched-next-${System.nanoTime()}")
        val line = watchedOn(kitchen.name)
        waitFor(hasText(line))
        Thread.sleep(1_500)
        assertFalse(exists(button))
        assertEquals(emptyList<Any>(), launcher.started.toList())
    }

    /** An indexer that takes every connection and never answers, until closed: a search of it waits for it. */
    private class Silent : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val connections = CopyOnWriteArrayList<Socket>()
        val url = "http://127.0.0.1:${server.localPort}"

        init {
            thread(isDaemon = true, name = "silent-indexer") {
                while (!server.isClosed) runCatching { connections += server.accept() }
            }
        }

        override fun close() {
            server.close()
            connections.forEach { it.close() }
        }
    }

    private companion object {
        /** An invented show's TMDB id; nothing real has it. */
        const val SHOW = 991_631
        const val SHOW_TITLE = "Harbor Lights"
        const val INDEXER = "cast-watched-next-device-test"
        const val RELEASE = "Harbor.Lights.S02E04.1080p.WEB-DL.H.264-CAST"

        /** The fake device's 52 minutes. */
        const val LENGTH = 3_120_000L

        val NAMES = mapOf(4 to "Low Water", 5 to "The Long Tide")
    }
}
