package io.skjaere.usenet.app

import android.content.pm.PackageManager
import androidx.lifecycle.Lifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.AutoAdvance
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.Prefetch
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.core.NzbSource
import io.skjaere.usenet.core.UsenetError
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.player.UsenetDataSourceException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The picture-in-picture tests' plays, and the end-of-play tests' (EndOfPlayDeviceTest): gaps-faststart.mp4, a 16:9 film
 * a minute long, posted on a server inside the test's process ([LocalNntp]) and launched straight into the player, as
 * [GapFixtures] launch theirs, so they run without the shared mock. The film is invented ("Copper Tide", TMDB id
 * 900102), as is the one a test plays from the app's screens over the window ([otherFilm]); their progress is put back
 * by [forget], every seam the tests set by [close]. The window's tests run on phones and tablets with picture-in-picture
 * only: a TV has none to test ([assumeWindow]).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PictureInPicturePlays(indexer: String) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val realLookUp = PlayerActivity.lookUpSegments
    private val realRead = PlayerActivity.readPlayback
    private val realDataSources = PlayerActivity.dataSources
    private val realSdk = PlayerActivity.pictureInPictureSdk
    private val realTv = PlayerActivity.isTelevision
    private val realFeature = PlayerActivity.hasPictureInPicture
    private val realVideoSize = PlayerActivity.videoSizeOf
    private val realNext = PlayerActivity.nextEpisode
    private val realPrevious = PlayerActivity.previousEpisode
    private val realTimings = PlayerActivity.timings
    private val realPrefetch = PlayerActivity.prefetchNext
    private val realNzbSource = PlaybackCoordinator.nzbSource
    /** Indexers that were on before [offerTheNextEpisode]; switched back on by [close]. */
    private var switchedOff = emptyList<String>()
    val local = LocalNntp()
    val fixtures = GapFixtures(local, indexer, WatchTarget(WatchKind.Movie, TITLE, movieTmdbId = FILM))
    private val mp4 by lazy { fixtures.media("gaps-faststart.mp4") }

    /** Skips the test where there is no window to test: a device without picture-in-picture (tv36), or a TV. */
    fun assumeWindow() {
        assumeTrue("no picture-in-picture on this device", context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
        assumeFalse("a TV: the player has no window there", container.television)
    }

    /** The engine on the test's own server, and no TheIntroDB lookups. */
    suspend fun setUp() {
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        engineSetup("the engine on the test's own NNTP server") {
            mockOnEngine()
            container.engine.configure(EngineSettings(listOf(local.provider)))
        }
    }

    override fun close() {
        PlayerActivity.lookUpSegments = realLookUp
        PlayerActivity.readPlayback = realRead
        PlayerActivity.dataSources = realDataSources
        PlayerActivity.pictureInPictureSdk = realSdk
        PlayerActivity.isTelevision = realTv
        PlayerActivity.hasPictureInPicture = realFeature
        PlayerActivity.videoSizeOf = realVideoSize
        PlayerActivity.nextEpisode = realNext
        PlayerActivity.previousEpisode = realPrevious
        PlayerActivity.timings = realTimings
        PlayerActivity.prefetchNext = realPrefetch
        PlaybackCoordinator.nzbSource = realNzbSource
        container.playback.cancel()
        runBlocking { switchedOff.forEach { container.settings.setIndexerEnabled(it, true) } }
        local.close()
    }

    /** Out of Up next, the films and the episode. */
    suspend fun forget() {
        container.library.recordProgress(WatchState(WatchKey.movie(FILM), WatchKind.Movie, movieTmdbId = FILM, title = TITLE, updatedAt = 0), 0, 60_000)
        container.library.recordProgress(WatchState(WatchKey.movie(OTHER_FILM), WatchKind.Movie, movieTmdbId = OTHER_FILM, title = OTHER_TITLE, updatedAt = 0), 0, 60_000)
        forgetOtherRelease()
        container.library.recordProgress(
            WatchState(WatchKey.episode(SHOW, 1, 1), WatchKind.Episode, showTmdbId = SHOW, season = 1, episode = 1, title = "$SHOW_NAME S01E01", updatedAt = 0), 0, 60_000,
        )
    }

    /**
     * The next-episode card from the episode's start ([episodeLaunch]), with nothing counting down: S01E02 follows S01E01
     * (the seam stands in for TMDB), and no indexer is asked for it, every one switched off until [close].
     */
    suspend fun offerTheNextEpisode() = nextEpisodeKnown(autoPlayNext = false, PlayerActivity.Timings(cardBeforeEndMs = 120_000))

    /**
     * The next-episode card as the episode ([episodeLaunch]) ends, counting down to S01E02 as it would with a release
     * found: from [CARD_BEFORE_END_MS] before the end, for [COUNTDOWN_MS], its search never done (the seam stands in for
     * the indexers, every one switched off until [close]).
     */
    suspend fun countDownToTheNextEpisode() {
        PlayerActivity.prefetchNext = { _, _, _ -> MutableStateFlow(Prefetch.Searching) }
        nextEpisodeKnown(autoPlayNext = true, PlayerActivity.Timings(cardBeforeEndMs = CARD_BEFORE_END_MS, countdownMs = COUNTDOWN_MS))
    }

    private suspend fun nextEpisodeKnown(autoPlayNext: Boolean, timings: PlayerActivity.Timings) {
        PlayerActivity.nextEpisode = { _, playing -> nextEpisode.takeIf { playing == AutoAdvance.Episode(SHOW, 1, 1) } }
        PlayerActivity.previousEpisode = { _, _ -> null }
        PlayerActivity.timings = timings
        val read = PlayerActivity.readPlayback
        PlayerActivity.readPlayback = { c -> read(c).copy(autoPlayNext = autoPlayNext) }
        switchedOff = container.settings.indexers.first().filter { it.enabled }.map { it.id }
        switchedOff.forEach { container.settings.setIndexerEnabled(it, false) }
    }

    /**
     * The film as the release [guid], its parts at [seconds] into it missing on the server: the player skips them, and three
     * within ten minutes bring the missing-parts prompt, here the count alone (no other release to try), which goes after
     * [COUNT_ONLY_FOR_MS] of playback in sight. The parts are about a second each.
     */
    suspend fun missingPartsLaunch(guid: String, vararg seconds: Int): PlayerLaunch {
        PlayerActivity.timings = PlayerActivity.Timings(missingPartsCountOnlyForMs = COUNT_ONLY_FOR_MS)
        val release = fixtures.release("gaps-faststart.mp4", mp4, SMALL_PART, guid, RELEASE_TITLE) { posted -> seconds.map { posted.idAt(atSecond(it)) }.toSet() }
        return fixtures.launch(release.prepared, guid, 0)
    }

    /** The byte of gaps-faststart.mp4 [seconds] into the film: its samples run evenly through its mdat, after its index. */
    private fun atSecond(seconds: Int): Long {
        var at = 0
        while (true) {
            val size = (mp4[at].toInt() and 0xFF shl 24) or (mp4[at + 1].toInt() and 0xFF shl 16) or (mp4[at + 2].toInt() and 0xFF shl 8) or (mp4[at + 3].toInt() and 0xFF)
            if (String(mp4, at + 4, 4, Charsets.US_ASCII) == "mdat") return at + size.toLong() * seconds / FILM_SECONDS
            at += size
        }
    }

    /** The film posted as the release [guid], played as an invented show's first episode ("Lowland Signal" S01E01). */
    suspend fun episodeLaunch(guid: String): PlayerLaunch {
        val watch = WatchTarget(WatchKind.Episode, "$SHOW_NAME S01E01", showTmdbId = SHOW, season = 1, episode = 1)
        return launch(guid).copy(title = SHOW_NAME, subtitle = "S01E01 · Low Water", watchKey = WatchKey.episode(SHOW, 1, 1), watch = watch)
    }

    private val nextEpisode = EpisodePlay(
        showTmdbId = SHOW, showName = SHOW_NAME, showYear = 2026, aliases = emptyList(), ids = TvIds(tvdb = 900104, tmdb = SHOW, imdb = "tt9001030"),
        season = 1, episode = 2, episodeName = "High Water", airDate = null, runtimeMinutes = 45,
    )

    /**
     * A second film, played from the app's screens as a release list's Play plays it ("Tin Lantern", TMDB id 900105):
     * gaps-faststart.mp4 posted again as the release [guid], through the coordinator, the play sheet and the launch
     * MainActivity starts. Its NZB is served in the indexer's place once [served] completes, so the play waits in the sheet
     * until then. The release that played it before is forgotten first: the play would take the NZB the store keeps for it.
     */
    suspend fun otherFilm(guid: String, served: Deferred<Unit>): PlayRequest {
        forgetOtherRelease()
        val nzb = local.nzb(local.post("gaps-faststart.mp4", mp4, PART, guid))
        val candidate = fixtures.candidate(guid, OTHER_RELEASE_TITLE)
        PlaybackCoordinator.nzbSource = { _, grab ->
            NzbSource { c ->
                if (c.id == candidate.key) {
                    served.await()
                    nzb
                } else {
                    grab.fetch(c)
                }
            }
        }
        return PlayRequest(OTHER_TITLE, "2026", listOf(candidate), watch = WatchTarget(WatchKind.Movie, OTHER_TITLE, movieTmdbId = OTHER_FILM))
    }

    private suspend fun forgetOtherRelease() {
        val key = WatchKey.movie(OTHER_FILM)
        container.library.lastRelease(key)?.let { container.library.forgetRelease(key, it) }
    }

    /** Where the film's progress was saved last; 0 for none. */
    suspend fun savedMs(): Long = container.library.state(WatchKey.movie(FILM)).first()?.positionMs ?: 0

    /** The film as the release [guid], posted under its own message ids, from its start. */
    suspend fun launch(guid: String): PlayerLaunch {
        val release = fixtures.release("gaps-faststart.mp4", mp4, PART, guid, RELEASE_TITLE) { emptySet() }
        return fixtures.launch(release.prepared, guid, 0)
    }

    /** Waits for the player to play; it, then. */
    suspend fun playing(scenario: ActivityScenario<PlayerActivity>): ExoPlayer =
        waitFor("the film to play", timeoutMs = 30_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying } } }

    /**
     * Home, then the player stops behind the home screen as it did before the window: its player released, not in the
     * window. It is finished then, as closing a stopped activity would wait out ActivityScenario's 45 s.
     */
    suspend fun homeStops(scenario: ActivityScenario<PlayerActivity>) {
        pressHome()
        waitFor("the player to stop behind the home screen") { true.takeIf { scenario.state == Lifecycle.State.CREATED } }
        assertNoWindow(scenario)
        assertNullPlayer(scenario)
        finish(scenario)
    }

    /** Finishes the player, in the window or not, and waits for it to close. */
    suspend fun finish(scenario: ActivityScenario<PlayerActivity>) {
        if (scenario.state != Lifecycle.State.DESTROYED) scenario.onActivity { it.finish() }
        waitFor<Boolean>("the player to close") { true.takeIf { scenario.state == Lifecycle.State.DESTROYED } }
    }

    /** The player's streams refused by the provider once [refuse] is set ([failByASeek]). */
    fun refusing(refuse: AtomicBoolean) {
        PlayerActivity.dataSources = { c -> DataSource.Factory { Refusing(c.dataSourceFactory.createDataSource(), refuse) } }
    }

    /**
     * With [refusing]'s provider refusing, back to the film's start, a few seconds in: the player has read the whole small
     * file at once, but keeps nothing behind where it plays, so it opens the stream there again, which the provider
     * refuses, and playback stops for good ("Playback stopped").
     */
    suspend fun failByASeek(scenario: ActivityScenario<PlayerActivity>, refuse: AtomicBoolean) {
        waitFor("the film a few seconds in", timeoutMs = 15_000) { scenario.read { a -> a.player?.takeIf { it.currentPosition >= 4_000 } } }
        refuse.set(true)
        scenario.onActivity { it.player!!.seekTo(0) }
    }

    private fun assertNoWindow(scenario: ActivityScenario<PlayerActivity>) {
        if (scenario.read { it.isInPictureInPictureMode }) throw AssertionError("in picture-in-picture")
    }

    private fun assertNullPlayer(scenario: ActivityScenario<PlayerActivity>) {
        if (scenario.read { it.player } != null) throw AssertionError("a player is left")
    }

    /** A data source the provider refuses to open while [refusing] is set. */
    private class Refusing(private val source: DataSource, private val refusing: AtomicBoolean) : DataSource by source {
        override fun open(dataSpec: DataSpec): Long {
            if (refusing.get()) {
                val error = UsenetError.AuthFailed("481 Authentication rejected")
                throw UsenetDataSourceException(error, IOException(error.detail), PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
            }
            return source.open(dataSpec)
        }
    }

    companion object {
        const val FILM = 900102
        const val TITLE = "Copper Tide"
        const val RELEASE_TITLE = "Copper.Tide.2026.1080p.WEB.h264-TIDE"
        const val SHOW = 900103
        const val SHOW_NAME = "Lowland Signal"
        const val OTHER_FILM = 900105
        const val OTHER_TITLE = "Tin Lantern"
        const val OTHER_RELEASE_TITLE = "Tin.Lantern.2026.1080p.WEB.h264-TIN"

        /** gaps-faststart.mp4 (136 KB) in five parts. */
        const val PART = 32_000

        /** About a second of gaps-faststart.mp4, for its missing parts ([missingPartsLaunch]). */
        const val SMALL_PART = 2_000
        const val FILM_SECONDS = 60

        /** The next-episode card's timings for [countDownToTheNextEpisode]. */
        const val CARD_BEFORE_END_MS = 8_000L
        const val COUNTDOWN_MS = 3_000L

        /** How long the count alone of missing parts shows for [missingPartsLaunch]. */
        const val COUNT_ONLY_FOR_MS = 6_000L
    }
}
