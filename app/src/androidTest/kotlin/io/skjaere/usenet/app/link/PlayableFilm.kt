package io.skjaere.usenet.app.link

import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.GapFixtures
import io.skjaere.usenet.app.LocalNntp
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.mockOnEngine
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.IndexerRecord
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.link.LinkMessage.Play
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.util.concurrent.atomic.AtomicInteger

/**
 * L40: a film NZStreamer on a TV plays with no mock NNTP server and no indexer asked, for a phone's Play end to end
 * (item 28). The device tests' chapters.mkv (6 minutes) is posted on a server in the test's process ([LocalNntp]),
 * prepared and in the store as the release that played the film before, which the Movie page's Play (MoviePlay) plays
 * alone. One indexer is enabled, at an address nothing answers, every other switched off, so the TV can play; [grabs]
 * counts the NZBs fetched, none. The player looks up no segments and switches no frame rate. [prepare] sets it all up
 * (skipped without a usable Usenet provider, which a TV needs to take a Play); [close] puts the settings, the seams and
 * the film's place back, and leaves no player open. The film [title] (TMDB id [tmdbId]) is invented.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayableFilm(val tmdbId: Int, val title: String) : AutoCloseable {
    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container
    private val local = LocalNntp()
    private val realFetch = PlaybackCoordinator.fetchNzb
    private val realLookUp = PlayerActivity.lookUpSegments
    private val realRead = PlayerActivity.readPlayback
    private var saved: AppSettings? = null

    /** The NZBs the coordinator fetched from an indexer meanwhile. */
    val grabs = AtomicInteger()

    /** The film as a phone asks a TV for it. */
    val play: Play.Movie get() = Play.Movie(tmdbId, title, 2026)

    /** The film's NZB, its articles on the test's own server, once [prepare]d: what Import opens as a .nzb another app gave it. */
    var nzb: ByteArray? = null
        private set

    suspend fun prepare() {
        assumeTrue("no usable Usenet provider saved, so the TV can't take a Play (L32)", container.noUsableProvider() == null)
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        PlayerActivity.readPlayback = { realRead(it).copy(matchFrameRate = false) }
        PlaybackCoordinator.fetchNzb = { c, indexer, item -> grabs.incrementAndGet(); realFetch(c, indexer, item) }
        val before = container.settings.settings.first().also { saved = it }
        // One indexer at an address nothing answers, the rest switched off: the TV can play, and asks none.
        container.settings.restore(before.copy(indexers = before.indexers.map { it.copy(enabled = false) } + IndexerRecord(INDEXER, "Unanswered", "http://10.0.2.2:9")))
        engineSetup("the engine on the test's own NNTP server") {
            mockOnEngine()
            container.engine.configure(EngineSettings(listOf(local.provider)))
        }
        val fixtures = GapFixtures(local, INDEXER, WatchTarget(WatchKind.Movie, title, movieTmdbId = tmdbId))
        val release = fixtures.release("chapters.mkv", fixtures.media("chapters.mkv"), PART, "film-$tmdbId", RELEASE) { emptySet() }
        nzb = release.nzb
        container.library.recordProgress(template(), 0, DURATION_MS, fixtures.saved("film-$tmdbId", release.prepared).toRemembered())
    }

    private fun template() = WatchState(WatchKey.movie(tmdbId), WatchKind.Movie, movieTmdbId = tmdbId, title = title, updatedAt = 0)

    override fun close() {
        PlaybackCoordinator.fetchNzb = realFetch
        PlayerActivity.lookUpSegments = realLookUp
        PlayerActivity.readPlayback = realRead
        container.playback.cancel()
        onMainThread { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlayerActivity>() }
            .forEach { player -> onMainThread { player.finish() } }
        local.close()
        runBlocking {
            saved?.let { container.settings.restore(it) }
            container.library.recordProgress(template(), 0, DURATION_MS)
        }
    }

    private companion object {
        const val INDEXER = "playable-film"
        const val RELEASE = "Harbor.Bell.2026.1080p.WEB-DL.H.264-LINK"
        const val PART = 32_000
        const val DURATION_MS = 360_000L
    }
}
