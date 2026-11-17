package io.skjaere.usenet.app.download

import android.app.Activity
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.nzbstreamer.stream.StreamableFile
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.cast.CastFakes
import io.skjaere.usenet.app.cast.CastItems
import io.skjaere.usenet.app.cast.CastLoader
import io.skjaere.usenet.app.cast.CastSeams
import io.skjaere.usenet.app.cast.CastService
import io.skjaere.usenet.app.cast.Casting
import io.skjaere.usenet.app.cast.FakeConnector
import io.skjaere.usenet.app.cast.FakeRemotePlayer
import io.skjaere.usenet.app.cast.FakeRoutes
import io.skjaere.usenet.app.cast.GoogleCast
import io.skjaere.usenet.app.cast.StandInReceiver
import io.skjaere.usenet.app.cast.castRoute
import io.skjaere.usenet.app.casting.CastEvent
import io.skjaere.usenet.app.casting.CastEventHub
import io.skjaere.usenet.app.casting.CastFailKind
import io.skjaere.usenet.app.casting.CastIdleReason
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.casting.CastPlayerState
import io.skjaere.usenet.app.casting.CastPrecheck
import io.skjaere.usenet.app.casting.CastPrompt
import io.skjaere.usenet.app.casting.MoveOutcome
import io.skjaere.usenet.app.casting.castingTo
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.everyStep
import io.skjaere.usenet.app.home.HomeDownloadOpen
import io.skjaere.usenet.app.home.HomeDownloads
import io.skjaere.usenet.app.link.CastButtonLook
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.overlayNodes
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.IMPORTED
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.ProbedTracksSerializer
import io.skjaere.usenet.app.read
import io.skjaere.usenet.app.shell
import io.skjaere.usenet.app.views
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.core.FileCandidate
import io.skjaere.usenet.core.ProbedTrack
import io.skjaere.usenet.core.ProbedTracks
import io.skjaere.usenet.core.StepStatus
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadGap
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.EpisodeRef
import io.skjaere.usenet.data.MovieMark
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.metadata.TmdbImages
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.player.PlayUris
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A finished download cast from this phone (W121 c; L417, L448, L451), through the real cast service, its server and the
 * real load ([Casting]), over a fake Cast session whose device player's LOAD a stand-in receiver in the test's process
 * fetches ([StandInReceiver]) at the emulator's Wi-Fi address, as ServiceCastDeviceTest casts a debrid play. The download
 * is seeded as the Downloads page's tests seed one ([DownloadsPageSeeds]), its file the device tests' chapters.mkv
 * ([DownloadFixtures]); its launch is moved to Kitchen display as the player's Cast row moves a play (the coordinator's
 * moveToCast). No mock and no indexer; TMDB's seams strict, so a lookup fails the class.
 * - The LOAD's address serves the file byte for byte, from its head and from a middle byte, and the device plays it.
 * - Nothing in the LOAD or in the answers' headers names the file, its path, the release or the download.
 * - A Remove during the cast keeps the file while the cast plays, and it goes as the cast ends; Delete all the same.
 * - The file deleted from outside first: "The downloaded file is gone", nothing sent, and the row Failed.
 * - Offline (the container's own override, L438): the LOAD goes within a second with no TMDB lookup, the session's
 *   picture the stored still, and the LOAD's picture TMDB's address for it.
 * - A row Delete once watched has taken: its hold refused, "The downloaded file is gone", nothing sent.
 * - The player's Cast row (W121 c): a download playing here moves there, served byte for byte, and the player closes.
 * - A download with recorded gaps: the cast counts them as the device is sent past them, none before, and its prompt
 *   offers Stream instead only while online (L449).
 * - A title's Play with Kitchen display connected (the router's, as the Show page's button plays it): the file is cast,
 *   "Starting on Kitchen display", nothing grabbed, with no provider or service usable (L446, L447).
 * - Offline, a file the device can't fully play asks the pre-check over it, the file held past the hook's 30 s by the
 *   cast's own hold (C1); Play on this phone plays it here, held for its launch.
 * - Try again after the device couldn't reach the phone loads the file again with no provider or service.
 * - Where download rows play (L418): the Downloads page's film row, a show's downloads' Resume (its next episode) and
 *   Home's Downloads row, with Kitchen display connected, each cast their file, nothing grabbed; from the page, a file
 *   the device can't fully play asks the pre-check even online, starting no stream (L447).
 *
 * The session's picture is never fetched from TMDB's image host: an address is answered with none.
 *
 * Put back after each, whatever happened: the rows and their files, the downloads' jobs, the offline override, the cast
 * seams and Play on (This phone), the app's screens, the settings and the marks. The show, its releases and the device
 * are invented.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class DownloadCastDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its keys. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val storage = container.downloadStorage
    private val dao = container.data.downloads
    private val seeds = DownloadsPageSeeds(context, container)
    private val routes = FakeRoutes()
    private val connector = FakeConnector()
    private val kitchen = castRoute("Kitchen display")
    private val device = CastTo(kitchen.id, kitchen.name, kitchen.deviceId, kitchen.model)
    private val events = CastEventHub()
    private val receiver = StandInReceiver()
    private val film = DownloadFixtures.media(MEDIA)
    private var remote: FakeRemotePlayer? = null
    private var tmdb: FakeTmdb? = null
    private var saved: AppSettings? = null

    /** The rows the test made, whose watches its casts may save. */
    private val made = CopyOnWriteArrayList<Download>()

    /** When each load began (elapsedRealtime), as the cast service called it. */
    private val begun = CopyOnWriteArrayList<Long>()

    /** What each session picture was made from, and the bytes it came to. */
    private val pictures = CopyOnWriteArrayList<Any>()
    private val pictureBytes = CopyOnWriteArrayList<ByteArray>()

    /** The coordinator's NZB grabs, counted through its seam: a cast of a file grabs nothing. */
    private val realFetch = PlaybackCoordinator.fetchNzb
    private val grabs = AtomicInteger()
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() = runBlocking<Unit> {
        assumeFalse("a TV never casts, and has no downloads (tv.TvNoCastTest, tv.TvNoDownloadsTest)", container.television)
        // The debug build's start saves its dev settings: before the test reads or takes any.
        withTimeout(SEEDING_MILLIS) { container.devSettingsSaved.await() }
        saved = container.settings.settings.first()
        container.settings.updateMetadata { it.copy(showArtwork = true) }
        tmdb = FakeTmdb(strict = true)
        seeds.before()
        CastFakes.started(routes, connector, CopyOnWriteArrayList())
        CastSeams.loader = { app ->
            CastLoader { launch, serving ->
                begun += SystemClock.elapsedRealtime()
                Casting.with(app).load(launch, serving)
            }
        }
        CastSeams.events = { events }
        val picture = Casting.picture
        Casting.picture = { c, model ->
            pictures += model
            // A stored file is read as the app reads it; TMDB's address is never fetched from a test.
            (if (model is String) null else picture(c, model))?.also { pictureBytes += it }
        }
        PlaybackCoordinator.fetchNzb = { c, indexer, item ->
            grabs.incrementAndGet()
            realFetch(c, indexer, item)
        }
        // Nothing left over from another test for this one to take.
        onMainThread { container.playback.withdraw() }
        connectKitchen()
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        everyStep(
            { receiver.close() },
            { PlaybackCoordinator.fetchNzb = realFetch },
            { container.offlineOverride.value = null },
            { onMainThread { container.playback.cancel() } },
            { alive().forEach { activity -> onMainThread { activity.finish() } } },
            { waitFor("the app's screens closed", 30_000) { Unit.takeIf { alive().isEmpty() } } },
            { container.playOn.choose(PlayChoice.Phone) },
            { CastFakes.reset() },
            { seeds.after() },
            // Unwatched again, the releases a cast's saves kept for them forgotten.
            { container.library.markEpisodes((1..EPISODES).map { EpisodeRef(SHOW, 1, it, WatchTitle.episode(NAME, 1, it)) }, watched = false) },
            { made.forEach { row -> row.release?.let { container.library.forgetRelease(row.watchKey, it) } } },
            { container.library.markMovies(listOf(MovieMark(FILM, FILM_NAME)), watched = false) },
            { saved?.let { container.settings.restore(it) } },
            { tmdb?.close() },
            { waitFor("the cast service gone", 30_000) { Unit.takeIf { !serviceRunning() } } },
        )
    }

    /** L417: the LOAD's address serves the downloaded file byte for byte, its head and a middle byte's, and the device plays it. */
    @Test
    fun aFinishedDownloadsLaunchLoadedThereIsServedByteForByteAndPlays() = runBlocking<Unit> {
        val row = finished(2)
        val (moving, item) = castToTheLoad(row)
        val url = standIn(item)
        val head = receiver.ask(0) ?: throw AssertionError("the head's request unanswered")
        assertEquals(206, head.status)
        assertEquals("bytes 0-${film.size - 1}/${film.size}", head.header("content-range"))
        assertEquals("video/x-matroska", head.header("content-type"))
        head.toEnd()
        val middle = receiver.ask(MIDDLE) ?: throw AssertionError("a middle byte's request unanswered")
        assertEquals(206, middle.status)
        middle.read(SEEK_READ)
        receiver.cut(middle)
        assertTrue("the LOAD's address is the phone's server's", url.startsWith("http://"))
        plays(item)
        assertEquals(MoveOutcome.Moved, withTimeout(LOAD_MILLIS) { moving.await() })
        end(item)
    }

    /** L417, L55: what a receiver gets of the phone names nothing of the download: not its file, path, release or id. */
    @Test
    fun nothingTheDeviceGetsNamesTheFileItsPathOrTheRelease() = runBlocking<Unit> {
        val row = finished(2)
        val (_, item) = castToTheLoad(row)
        val load = onMainThread { CastItems.toMediaQueueItem(item).toJson().toString() }
        standIn(item)
        (receiver.ask(0) ?: throw AssertionError("the head's request unanswered")).toEnd()
        val tail = receiver.ask(film.size - TAIL) ?: throw AssertionError("the tail's request unanswered")
        tail.toEnd()
        val banned = listOf(
            checkNotNull(row.fileName), checkNotNull(row.release).title, row.id, "downloads/", checkNotNull(storage.file(row)).path, "Quiet.Meridian",
            "KESTREL",
        )
        (listOf("the LOAD" to load) + receiver.headers.map { "an answer's headers" to it.toString() }).forEach { (what, words) ->
            banned.forEach { assertFalse("$what names $it", it in words) }
        }
        assertTrue("the answers' headers seen", receiver.headers.size >= 2)
    }

    /**
     * L417, L413: a Remove during the cast hides the row at once, but the cast holds its file, which is still served, until
     * following ends; then it goes, row and all. Its Undo's time is none here, so only the cast's hold keeps it.
     */
    @Test
    fun aRemoveDuringTheCastKeepsTheFileUntilTheCastEndsThenItGoes() = runBlocking<Unit> {
        val row = finished(2)
        val file = checkNotNull(storage.file(row))
        val (moving, item) = castToTheLoad(row)
        standIn(item)
        (receiver.ask(0) ?: throw AssertionError("the head's request unanswered")).read(SEEK_READ)
        plays(item)
        assertEquals(MoveOutcome.Moved, withTimeout(LOAD_MILLIS) { moving.await() })
        container.downloads.remove(listOf(row.id), keepFor = 0)
        waitFor("removed, hidden") { Unit.takeIf { runBlocking { dao.get(row.id) }?.state == DownloadState.Deleting } }
        delay(SETTLE_MILLIS)
        assertTrue("its file, while it casts", file.isFile)
        assertEquals(DownloadState.Deleting, dao.get(row.id)?.state)
        val seek = receiver.ask(MIDDLE) ?: throw AssertionError("a seek's request unanswered")
        assertEquals("still served", 206, seek.status)
        seek.read(SEEK_READ)
        receiver.cut(seek)
        end(item)
        waitFor("the download gone as the cast ended", 30_000) { Unit.takeIf { runBlocking { dao.get(row.id) } == null && !file.exists() } }
        assertFalse(storage.bookkeeping(row.id).exists())
    }

    /** L413, L417: Delete all during the cast deletes the rest at once and leaves the cast's file until the cast ends. */
    @Test
    fun deleteAllDuringTheCastKeepsTheFileUntilTheCastEndsThenItGoes() = runBlocking<Unit> {
        val row = finished(2)
        val other = finished(3)
        val file = checkNotNull(storage.file(row))
        val otherFile = checkNotNull(storage.file(other))
        // Delete all deletes every download on the device: only the test's own may be there.
        val others = dao.all().first().filter { !seeds.isMine(it.id) }
        if (others.isNotEmpty()) {
            throw AssertionError("${others.size} download(s) not the test's, which Delete all would delete: ${others.map { it.id }}")
        }
        val (moving, item) = castToTheLoad(row)
        standIn(item)
        (receiver.ask(0) ?: throw AssertionError("the head's request unanswered")).read(SEEK_READ)
        plays(item)
        assertEquals(MoveOutcome.Moved, withTimeout(LOAD_MILLIS) { moving.await() })
        container.downloads.deleteAll()
        assertEquals(null, dao.get(other.id))
        assertFalse(otherFile.exists())
        assertEquals("hidden at once", DownloadState.Deleting, dao.get(row.id)?.state)
        delay(SETTLE_MILLIS)
        assertTrue("its file, while it casts", file.isFile)
        val seek = receiver.ask(MIDDLE) ?: throw AssertionError("a seek's request unanswered")
        assertEquals("still served", 206, seek.status)
        seek.read(SEEK_READ)
        receiver.cut(seek)
        end(item)
        waitFor("the download gone as the cast ended", 30_000) { Unit.takeIf { runBlocking { dao.get(row.id) } == null && !file.exists() } }
    }

    /** L417: a file deleted from outside the app fails the load before anything is sent, and its row fails as reconcile would fail it. */
    @Test
    fun aFileDeletedFromOutsideFailsTheLoadWithNothingSentAndTheRowFails() = runBlocking<Unit> {
        val row = finished(2)
        assertTrue(checkNotNull(storage.file(row)).delete())
        val loads = remote!!.loads.size
        val failed = withTimeout(LOAD_MILLIS) { cast(row).await() } as? MoveOutcome.Failed ?: throw AssertionError("the cast didn't fail")
        assertEquals(CastFailKind.CouldNotPlay, failed.failure.kind)
        assertEquals(DownloadStorage.FILE_GONE, failed.failure.reason)
        assertFalse("not the release's fault", failed.failure.providers)
        assertEquals("nothing sent", loads, remote!!.loads.size)
        val after = waitFor("the row failed") { runBlocking { dao.get(row.id) }?.takeIf { it.state == DownloadState.Failed } }
        assertEquals(DownloadStorage.FILE_GONE, after.reason)
    }

    /**
     * L451: offline, a download's cast waits for no picture: its art is the row's still, with no TMDB lookup (the strict
     * fake would fail), the session's picture the stored still, and the LOAD names TMDB's address for it, as every cast
     * does while Show artwork is on. The LOAD goes within a second of the load's start.
     */
    @Test
    fun offlineTheLoadGoesWithinASecondWithTheStoredStillAndNoTmdbLookup() = runBlocking<Unit> {
        val row = finished(2)
        val still = File(storage.bookkeeping(row.id), DownloadImages.Kind.Still.file)
        still.writeBytes(DownloadFixtures.media(JPEG))
        container.offlineOverride.value = true
        waitFor("offline") { Unit.takeIf { container.offline.value } }
        val loads = remote!!.loads.size
        cast(row)
        val item = loadSoon(loads)
        val took = SystemClock.elapsedRealtime() - begun.last()
        assertTrue("the LOAD went $took ms after its load began", took < LOAD_WITHIN_MILLIS)
        assertEquals("TMDB asked", emptyList<String>(), tmdb!!.misses.toList())
        assertEquals("TMDB answered", 0, tmdb!!.lookups.get())
        assertEquals("the session's picture made from the stored still", listOf<Any>(still), pictures.toList())
        assertArrayEquals("the session's picture", pictureBytes.single(), item.mediaMetadata.artworkData)
        val image = onMainThread { CastItems.toMediaQueueItem(item).media?.metadata?.images?.singleOrNull()?.url?.toString() }
        assertEquals("the LOAD's picture, TMDB's", TmdbImages.still(STILL), image)
    }

    /**
     * L413, L417: a row Delete once watched has taken can't be held: the cast fails as for a file gone, and nothing is sent.
     * The row and its file are put back under the same id after the run deleted them, so the file is there to serve: only
     * the refused hold keeps it from the device, and the row isn't failed as one whose file is gone.
     */
    @Test
    fun aRowDeleteOnceWatchedHasTakenIsRefusedWithNothingSent() = runBlocking<Unit> {
        val row = finished(3)
        container.settings.setDownloadsDeleteOnceWatched(true)
        container.library.markEpisodes(listOf(EpisodeRef(SHOW, 1, 3, WatchTitle.episode(NAME, 1, 3))), watched = true)
        // The run, as of a day ahead, would delete any finished download watched so: only the test's own may be.
        val watched = container.data.downloadWatches.watched().first().filter { !seeds.isMine(it.id) }
        if (watched.isNotEmpty()) {
            throw AssertionError("${watched.size} watched download(s) not the test's, which the run would delete: ${watched.map { it.id }}")
        }
        assertEquals(listOf(row.id), container.downloads.deleteWatched(System.currentTimeMillis() + DAY + MINUTE).filter { it == row.id })
        assertFalse("a hold refused", container.downloads.hold(Any(), row.id))
        // Taken for good (the runner keeps it so for the process's life), though its row and file are back.
        seeded(row)
        assertFalse("a hold refused with its file back", container.downloads.hold(Any(), row.id))
        val loads = remote!!.loads.size
        val failed = withTimeout(LOAD_MILLIS) { cast(row).await() } as? MoveOutcome.Failed ?: throw AssertionError("the cast didn't fail")
        assertEquals(DownloadStorage.FILE_GONE, failed.failure.reason)
        assertEquals("nothing sent", loads, remote!!.loads.size)
        delay(SETTLE_MILLIS)
        assertEquals("failed as a file gone", DownloadState.Done, dao.get(row.id)?.state)
    }

    /**
     * W121 (c), L417: a download playing in the player moves to Kitchen display by the player's Cast row, its session
     * connected already: the LOAD's address serves the file byte for byte, and once the device plays it the player
     * closes, as any film's move closes it.
     */
    @Test
    fun thePlayersCastRowMovesAPlayingDownloadThereByteForByteAndThePlayerCloses() = runBlocking<Unit> {
        // The player's next and previous episodes, which TMDB would be asked for: none (the strict fake puts them back).
        PlayerActivity.nextEpisode = { _, _ -> null }
        PlayerActivity.previousEpisode = { _, _ -> null }
        // Kitchen display in the list as Google's discovery finds it, its session connected already.
        routes.found(kitchen)
        val row = finished(2)
        val loads = remote!!.loads.size
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, checkNotNull(DownloadLaunch.of(row)))).use { scenario ->
            waitFor("the download to play here", LOAD_MILLIS) { scenario.read { a -> a.player?.takeIf { it.isPlaying } } }
            tapRow(scenario, kitchen.name)
            val item = waitFor("the LOAD", LOAD_MILLIS) { remote!!.loads.drop(loads).lastOrNull() }
            standIn(item)
            val head = receiver.ask(0) ?: throw AssertionError("the head's request unanswered")
            assertEquals(206, head.status)
            assertEquals("bytes 0-${film.size - 1}/${film.size}", head.header("content-range"))
            head.toEnd()
            val middle = receiver.ask(MIDDLE) ?: throw AssertionError("a middle byte's request unanswered")
            assertEquals(206, middle.status)
            middle.read(SEEK_READ)
            receiver.cut(middle)
            plays(item)
            waitFor("the player closed", LOAD_MILLIS) { Unit.takeIf { players().all { it.isFinishing || it.isDestroyed } } }
            assertEquals("the download's file there", PlayUris.download(row.id), Casting.feed.value?.launch?.uri)
            end(item)
        }
    }

    /**
     * L449, L94: a download whose file has parts its download had no article for, cast: its file tells every recorded gap
     * as it opens (L417), and the cast counts those the device was sent past, as the player counts a download's as its play
     * goes past them (L354): none while the device has been sent nothing, then three within ten minutes of film ask. The
     * prompt offers Stream instead while online, and the count alone offline, never another release. The device paused,
     * so the prompt's time doesn't run while the test reads it.
     */
    @Test
    fun aDownloadsRecordedGapsAreCountedThereAndItsPromptOffersStreamInsteadOnlyOnline() = runBlocking<Unit> {
        val gaps = listOf(GAP_AT, GAP_AT + 100_000, GAP_AT + 200_000).map { DownloadGap(it, it + GAP - 1) }
        val row = finished(2, gaps)
        val (moving, item) = castToTheLoad(row)
        standIn(item)
        plays(item)
        assertEquals(MoveOutcome.Moved, withTimeout(LOAD_MILLIS) { moving.await() })
        tell(CastPlayerState.Paused, item.mediaId, 0)
        val casting = checkNotNull(container.castPlaying) { "no cast followed" }
        // Every gap told as the head opened, none sent: the cast's ticks pass over them meanwhile.
        waitFor("the gaps told", LOAD_MILLIS) { Casting.feed.value?.takeIf { it.gaps.size == gaps.size && it.passed.isEmpty() } }
        delay(SETTLE_MILLIS)
        assertEquals("a prompt before the device was sent past a gap", null, casting.missing.value)
        // The device reads the file through them.
        (receiver.ask(0) ?: throw AssertionError("the head's request unanswered")).toEnd()
        val online = waitFor("the prompt", LOAD_MILLIS) { casting.missing.value }
        assertEquals(CastPrompt(3, tryAnother = true, stuck = false, device = kitchen.name, streamInstead = true), online)
        container.offlineOverride.value = true
        val offline = waitFor("the count alone offline", LOAD_MILLIS) { casting.missing.value?.takeIf { !it.tryAnother } }
        assertEquals(CastPrompt(3, tryAnother = false, stuck = false, device = kitchen.name), offline)
        container.offlineOverride.value = false
        waitFor("Stream instead with the network back", LOAD_MILLIS) { casting.missing.value?.takeIf { it.tryAnother && it.streamInstead } }
        end(item)
    }

    /**
     * W121 (c), L446, L447: a title's Play of a downloaded episode with Kitchen display connected (the router's play, as the
     * Show page's Play calls it) casts its file: the sheet says "Starting on Kitchen display" with the release under it and
     * no release steps, the LOAD serves the file, and nothing is grabbed or looked up. No provider, service or indexer is
     * usable, as a file on this phone needs none: a stream couldn't have started.
     */
    @Test
    fun aTitlesPlayWithKitchenDisplayConnectedCastsTheFileWithNothingGrabbed() = runBlocking<Unit> {
        val row = finished(2)
        nothingToStreamFrom()
        val loads = remote!!.loads.size
        onMainThread { container.playRouter.playEpisode(episode(2)) }
        val item = waitFor("the LOAD", LOAD_MILLIS) { remote!!.loads.drop(loads).lastOrNull() }
        val starting = container.playback.state.value as? PlayState.Running ?: throw AssertionError("not starting: ${container.playback.state.value}")
        assertEquals(StepStatus.Running(), starting.castStep)
        assertEquals(NAME to "S01E02 · ${NAMES[1]}", starting.title to starting.subtitle)
        assertEquals(checkNotNull(row.release).title, starting.release)
        assertEquals("no release steps", emptyMap<Any, Any>(), starting.steps)
        assertEquals("Casting to ${kitchen.name}", container.playback.castTo.value?.let { castingTo(it.name) })
        standIn(item)
        val head = receiver.ask(0) ?: throw AssertionError("the head's request unanswered")
        assertEquals(206, head.status)
        head.toEnd()
        plays(item)
        waitFor("the sheet closed", LOAD_MILLIS) { Unit.takeIf { container.playback.state.value == PlayState.Idle } }
        assertEquals("the download's file there", PlayUris.download(row.id), Casting.feed.value?.launch?.uri)
        assertEquals("grabs", 0, grabs.get())
        assertEquals("TMDB asked", 0, tmdb!!.lookups.get())
        end(item)
    }

    /**
     * L446, L447 (C1): offline, a title's Play to Kitchen display of a file whose only sound it can't play asks the
     * pre-check over the file, nothing sent; the file stays held past the hook's 30 s by the cast's own hold while it asks.
     * Play on this phone chooses This phone and plays the file here, held for its launch.
     */
    @Test
    fun offlineAMissAsksThePrecheckAndPlayOnThisPhonePlaysTheFileHere() = runBlocking<Unit> {
        val row = finished(2, tracks = SILENT)
        nothingToStreamFrom()
        container.offlineOverride.value = true
        waitFor("offline") { Unit.takeIf { container.offline.value } }
        val loads = remote!!.loads.size
        onMainThread { container.playRouter.playEpisode(episode(2)) }
        val asking = waitFor("the pre-check", LOAD_MILLIS) { container.playback.state.value as? PlayState.Prechecking }
        assertEquals("No sound on ${kitchen.name}", asking.precheck.headline)
        assertEquals(listOf(CastPrecheck.Button.PlayHere, CastPrecheck.Button.CastAnyway, CastPrecheck.Button.Cancel), asking.precheck.buttons())
        assertTrue("held as it asks", row.id in container.downloads.held.value)
        // Past the hook's own 30 s: the cast's hold keeps the file while the question waits.
        delay(DownloadHook.LAUNCH_HOLD_MILLIS + SETTLE_MILLIS)
        assertTrue("held past the hook's 30 s", row.id in container.downloads.held.value)
        onMainThread { container.playback.precheck(CastPrecheck.Button.PlayHere) }
        val here = withTimeout(LOAD_MILLIS) { container.playback.launches.first() }
        assertEquals(PlayUris.download(row.id), here.uri)
        assertEquals("played here", null, here.cast)
        assertEquals(PlayChoice.Phone, container.playOn.chosen.value)
        assertTrue("held for its launch", row.id in container.downloads.held.value)
        assertEquals("nothing sent", loads, remote!!.loads.size)
    }

    /** L447 (W39): Try again after the device couldn't reach this phone loads a download's file again, with no provider or service. */
    @Test
    fun tryAgainLoadsADownloadsFileAgainWithNoProviderOrService() = runBlocking<Unit> {
        val row = finished(2)
        nothingToStreamFrom()
        val loads = remote!!.loads.size
        onMainThread { container.playback.castAgain(checkNotNull(DownloadLaunch.of(row)).copy(cast = device)) }
        val item = waitFor("the LOAD", LOAD_MILLIS) { remote!!.loads.drop(loads).lastOrNull() }
        standIn(item)
        (receiver.ask(0) ?: throw AssertionError("the head's request unanswered")).toEnd()
        plays(item)
        waitFor("the sheet closed", LOAD_MILLIS) { Unit.takeIf { container.playback.state.value == PlayState.Idle } }
        end(item)
    }

    /**
     * L418, L447: the Downloads page's film row with Kitchen display connected (its deps' play,
     * which the row's tap and its ▶ call) casts the film's file: the one-step sheet, the LOAD serving the file, nothing
     * grabbed or looked up, with no provider or service usable.
     */
    @Test
    fun theDownloadsPagesFilmRowCastsItsFile() = runBlocking<Unit> {
        val row = finishedFilm()
        nothingToStreamFrom()
        castsTheFile(row) { DownloadsDeps.of(container, context).play(row) }
    }

    /**
     * L418, L372: a show's downloads' Resume with Kitchen display connected casts what it
     * plays, the next downloaded episode (S01E02, S01E01 watched), through the deps' play its button calls.
     */
    @Test
    fun aShowsDownloadsResumeCastsTheNextEpisodesFile() = runBlocking<Unit> {
        val rows = listOf(finished(1), finished(2))
        container.library.markEpisodes(listOf(EpisodeRef(SHOW, 1, 1, WatchTitle.episode(NAME, 1, 1))), watched = true)
        nothingToStreamFrom()
        val states = waitFor("S01E01 watched") { runBlocking { container.library.episodeStates(SHOW).first() }.takeIf { it[1 to 1]?.watched == true } }
        val next = checkNotNull(DownloadsShow.next(rows, states)) { "nothing for Resume" }
        assertEquals(rows[1].id, next.row.id)
        castsTheFile(rows[1]) { DownloadsDeps.of(container, context).play(next.row) }
    }

    /**
     * L418 (added by the checks): Home's Downloads row of a film with Kitchen display connected (DownloadsDeps.play, which
     * Home's row calls) casts its file, nothing grabbed.
     */
    @Test
    fun homesDownloadsRowCastsTheFileWithNothingGrabbed() = runBlocking<Unit> {
        val row = finishedFilm()
        nothingToStreamFrom()
        castsTheFile(row) { DownloadsDeps.play(container, row) }
    }

    /**
     * L458, L447, L473: Import's tap on a file whose download is finished, with Kitchen display connected (the router's
     * play of Import's file, handed its finished download, as Import's row calls it), casts the downloaded file, never the
     * NZB's stream: the one-step sheet, the LOAD serving the file, nothing grabbed, with no provider or service usable.
     */
    @Test
    fun importsFinishedRowCastsTheDownloadedFile() = runBlocking<Unit> {
        val row = finishedImport()
        nothingToStreamFrom()
        val file = FileCandidate(1, StreamableFile(checkNotNull(row.fileName), film.size.toLong(), 1, 0, 0, 0))
        castsTheFile(row) { container.playRouter.playFile(IMPORT_NZB, file, row) { throw AssertionError("never here") } }
    }

    /**
     * L418, L472: Home's Downloads row of a file from Import, which plays (named as its page names it, "S01E02 ·
     * downloaded"), with Kitchen display connected casts its file (DownloadsDeps.play, which Home's row calls), nothing
     * grabbed or looked up.
     */
    @Test
    fun homesDownloadsRowOfAFileFromImportCastsItsFile() = runBlocking<Unit> {
        val row = finishedImport()
        nothingToStreamFrom()
        val home = checkNotNull(HomeDownloads.of(listOf(DownloadRunner.Item(row, row.safeOffset, null)))).rows.single()
        assertEquals("Quiet Meridian", home.title)
        val plays = home.opens as? HomeDownloadOpen.Play ?: throw AssertionError("Home's row opens ${home.opens}")
        castsTheFile(row) { DownloadsDeps.play(container, plays.row) }
    }

    /**
     * L447, L473: from the Downloads pages a file Kitchen display can't fully play asks the
     * pre-check over the file even online, where a title's Play would stream for the device: Play on this phone, Cast
     * anyway and Cancel, never a compatible release. Nothing sent, nothing grabbed, no stream started; Cancel ends it.
     */
    @Test
    fun aMissFromThePageAsksThePrecheckOnlineAndStartsNoStream() = runBlocking<Unit> {
        val row = finished(2, tracks = SILENT)
        nothingToStreamFrom()
        container.offlineOverride.value = false
        waitFor("online") { Unit.takeIf { !container.offline.value } }
        val loads = remote!!.loads.size
        onMainThread { DownloadsDeps.of(container, context).play(row) }
        val asking = waitFor("the pre-check", LOAD_MILLIS) { container.playback.state.value as? PlayState.Prechecking }
        assertEquals("No sound on ${kitchen.name}", asking.precheck.headline)
        assertEquals(listOf(CastPrecheck.Button.PlayHere, CastPrecheck.Button.CastAnyway, CastPrecheck.Button.Cancel), asking.precheck.buttons())
        assertEquals("nothing sent", loads, remote!!.loads.size)
        onMainThread { container.playback.precheck(CastPrecheck.Button.Cancel) }
        waitFor("the sheet closed", LOAD_MILLIS) { Unit.takeIf { container.playback.state.value == PlayState.Idle } }
        assertEquals("nothing sent", loads, remote!!.loads.size)
        assertEquals("grabs", 0, grabs.get())
        assertEquals("TMDB asked", 0, tmdb!!.lookups.get())
    }

    /**
     * [play], with Kitchen display connected, casts [row]'s file: "Starting on Kitchen display" with no release steps, the
     * LOAD's address serving the file from its head, the device playing it, and the sheet closed; nothing grabbed or
     * looked up.
     */
    private suspend fun castsTheFile(row: Download, play: () -> Unit) {
        val loads = remote!!.loads.size
        onMainThread(play)
        val item = waitFor("the LOAD", LOAD_MILLIS) { remote!!.loads.drop(loads).lastOrNull() }
        val starting = container.playback.state.value as? PlayState.Running ?: throw AssertionError("not starting: ${container.playback.state.value}")
        assertEquals(StepStatus.Running(), starting.castStep)
        assertEquals("no release steps", emptyMap<Any, Any>(), starting.steps)
        standIn(item)
        val head = receiver.ask(0) ?: throw AssertionError("the head's request unanswered")
        assertEquals(206, head.status)
        assertEquals("bytes 0-${film.size - 1}/${film.size}", head.header("content-range"))
        head.toEnd()
        plays(item)
        waitFor("the sheet closed", LOAD_MILLIS) { Unit.takeIf { container.playback.state.value == PlayState.Idle } }
        assertEquals("the download's file there", PlayUris.download(row.id), Casting.feed.value?.launch?.uri)
        assertEquals("grabs", 0, grabs.get())
        assertEquals("TMDB asked", 0, tmdb!!.lookups.get())
        end(item)
    }

    // The download, the cast and the device.

    /**
     * Episode [number] of Quiet Meridian as a finished download is left: its row with its marker (as DownloadsPageSeeds
     * seeds one), then the device tests' chapters.mkv as its file, where storage keeps it.
     */
    private fun finished(number: Int, gaps: List<DownloadGap> = emptyList(), tracks: ProbedTracks? = null): Download {
        val id = Download.newId()
        val name = "Quiet.Meridian.S01E0$number.720p.WEB.h264-KESTREL.mkv"
        val size = film.size.toLong()
        return seeded(
            Download(
                id = id, watchKey = WatchKey.episode(SHOW, 1, number), kind = DownloadKind.Episode, title = NAME, subtitle = "S01E0$number · ${NAMES[number - 1]}",
                showTmdbId = SHOW, season = 1, episode = number, seasonEpisodes = EPISODES, episodeName = NAMES[number - 1], posterPath = POSTER,
                stillPath = STILL, state = DownloadState.Done, position = -1_000L + number,
                release = RememberedRelease(INDEXER, "qm-s01e0$number", name.removeSuffix(".mkv"), null, 0, "nzb-qm-$number", name, size),
                fileName = name, size = size, indexerSize = size, safeOffset = size, gaps = gaps, relativePath = "downloads/$id/v.mkv",
                tracks = tracks?.let { json.encodeToString(ProbedTracksSerializer, it) }, createdAt = 1, completedAt = 2, updatedAt = 2,
            ),
        )
    }

    /** Copper Crown as a finished download is left, as [finished] leaves an episode: its row, then chapters.mkv as its file. */
    private fun finishedFilm(): Download {
        val id = Download.newId()
        val name = "Copper.Crown.2022.720p.WEB.h264-KESTREL.mkv"
        val size = film.size.toLong()
        return seeded(
            Download(
                id = id, watchKey = WatchKey.movie(FILM), kind = DownloadKind.Movie, title = FILM_NAME, subtitle = "2022", movieTmdbId = FILM, year = 2022,
                runtimeMinutes = 6, posterPath = POSTER, state = DownloadState.Done, position = -2_000L,
                release = RememberedRelease(INDEXER, "cc-2022", name.removeSuffix(".mkv"), null, 0, "nzb-cc-2022", name, size),
                fileName = name, size = size, indexerSize = size, safeOffset = size, relativePath = "downloads/$id/v.mkv", createdAt = 1, completedAt = 2,
                updatedAt = 2,
            ),
        )
    }

    /**
     * Quiet Meridian's S01E02 as a file of an NZB opened on Import, finished (L423), as [finished] leaves an episode: its row,
     * under its release's watch key, then chapters.mkv as its file.
     */
    private fun finishedImport(): Download {
        val id = Download.newId()
        val name = "Quiet.Meridian.S01E02.720p.WEB.h264-KESTREL.mkv"
        val size = film.size.toLong()
        return seeded(
            Download(
                id = id, watchKey = WatchKey.release(IMPORT_NZB, 1), kind = DownloadKind.Release, title = name, subtitle = IMPORT_TITLE,
                state = DownloadState.Done, position = -3_000L, candidates = listOf(DownloadCandidate(IMPORTED, IMPORT_NZB, IMPORT_TITLE, size = size)),
                release = RememberedRelease(IMPORTED, IMPORT_NZB, IMPORT_TITLE, null, 1, IMPORT_NZB, name, size),
                fileName = name, size = size, indexerSize = size, safeOffset = size, relativePath = "downloads/$id/v.mkv", createdAt = 1, completedAt = 2,
                updatedAt = 2,
            ),
        )
    }

    /** [row] seeded with its marker (as DownloadsPageSeeds seeds one), then the device tests' chapters.mkv as its file, where storage keeps it. */
    private fun seeded(row: Download): Download {
        seeds.seed(row)
        made += row
        val folder = checkNotNull(storage.videoFolder(row.id)) { "no primary volume" }
        folder.mkdirs()
        File(folder, "v.mkv").writeBytes(film)
        return row
    }

    /** [row]'s launch moved to Kitchen display, as the player's Cast row moves a play: what the move comes to. */
    private fun cast(row: Download): Deferred<MoveOutcome> {
        val launch = checkNotNull(DownloadLaunch.of(row)) { "no launch for the row" }
        return onMainThread { container.playback.moveToCast(launch, 0, device) }
    }

    /** [cast], and the LOAD it sends Kitchen display. */
    private suspend fun castToTheLoad(row: Download): Pair<Deferred<MoveOutcome>, MediaItem> {
        val loads = remote!!.loads.size
        val moving = cast(row)
        val item = waitFor("the LOAD", LOAD_MILLIS) {
            // A move that came out with no LOAD says why at once.
            remote!!.loads.drop(loads).lastOrNull()
                ?: moving.takeIf { it.isCompleted }?.let { done -> throw AssertionError("no LOAD: ${runBlocking { done.await() }}") }
        }
        return moving to item
    }

    /** The LOAD after the first [loads], looked for every 10 ms: when it went is what is measured. */
    private suspend fun loadSoon(loads: Int): MediaItem {
        val until = SystemClock.elapsedRealtime() + LOAD_MILLIS
        while (SystemClock.elapsedRealtime() < until) {
            remote!!.loads.drop(loads).lastOrNull()?.let { return it }
            delay(10)
        }
        throw AssertionError("no LOAD in ${LOAD_MILLIS / 1_000} s")
    }

    /** The stand-in at the address [item]'s LOAD carries, the file's bytes known: each answer is read against them. */
    private fun standIn(item: MediaItem): String {
        val url = onMainThread { CastItems.toMediaQueueItem(item).media?.contentUrl } ?: throw AssertionError("no address in the LOAD")
        receiver.at(url)
        receiver.knows(film)
        return url
    }

    /** The device says it plays [item]: the move's load came out playing, and the play is followed there. */
    private fun plays(item: MediaItem) {
        remote!!.lasting(DURATION_MS)
        remote!!.playing()
        tell(CastPlayerState.Playing, item.mediaId, 0)
    }

    /** The device's player finished [item]: following ends, and the cast service with it. */
    private suspend fun end(item: MediaItem) {
        tell(CastPlayerState.Idle, item.mediaId, DURATION_MS, CastIdleReason.Finished)
        waitFor("the cast service stopped", 30_000) { Unit.takeIf { !serviceRunning() } }
    }

    /** The device says it is [state] in [id] at [positionMs] ([idle]: why it is idle). */
    private fun tell(state: CastPlayerState, id: String, positionMs: Long, idle: CastIdleReason? = null) =
        onMainThread { events.tell(CastEvent.Status(state, idle, id, positionMs, SystemClock.elapsedRealtime())) }

    /** The Cast device [name]'s row tapped in the player's list, opened from its button with the controls up. */
    private suspend fun tapRow(scenario: ActivityScenario<PlayerActivity>, name: String) {
        scenario.onActivity { a ->
            playerView(a).controllerShowTimeoutMs = 0
            playerView(a).showController()
        }
        waitFor("the controls") { scenario.read { a -> playerView(a).isControllerFullyVisible.takeIf { it } } }
        waitFor("the Cast button") { scenario.read { a -> castNode(a) } }
        scenario.onActivity { a -> castNode(a)!!.config[SemanticsActions.OnClick].action!!.invoke() }
        waitFor("$name's row") { scenario.read { a -> castRow(a, name) } }
        scenario.onActivity { a -> castRow(a, name)!!.config[SemanticsActions.OnClick].action!!.invoke() }
    }

    private fun castNode(activity: Activity): SemanticsNode? = overlayNodes(activity).firstOrNull { it.config.getOrNull(CastButtonLook) != null }

    private fun castRow(activity: Activity, name: String): SemanticsNode? = overlayNodes(activity).firstOrNull { n ->
        n.config.getOrNull(SemanticsActions.OnClick) != null && n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith("$name, Cast device") }
    }

    private fun playerView(activity: Activity) = views(activity.window.decorView).filterIsInstance<PlayerView>().single()

    private fun players(): List<PlayerActivity> = alive().filterIsInstance<PlayerActivity>()

    /** The app's players and MainActivity instances alive: a move's close opens the remote in the app. */
    private fun alive(): List<Activity> = onMainThread {
        ALIVE.flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.filter { it is PlayerActivity || it is MainActivity }.distinct()
    }

    /**
     * Every provider, debrid service account and indexer switched off (the settings put back after): no stream could start
     * or search, so what plays is the file. A play that streamed fails at once.
     */
    private suspend fun nothingToStreamFrom() {
        val settings = container.settings
        settings.providers.first().filter { it.enabled }.forEach { settings.setProviderEnabled(it.id, false) }
        settings.services.first().filter { it.enabled }.forEach { settings.setServiceEnabled(it.id, false) }
        settings.indexers.first().filter { it.enabled }.forEach { settings.setIndexerEnabled(it.id, false) }
        settings.torrentIndexers.first().filter { it.enabled }.forEach { settings.setTorrentIndexerEnabled(it.id, false) }
        assertTrue("a provider or service usable", container.noUsableSource() != null)
    }

    /** Episode [n] of Quiet Meridian as the Show page plays it, known to TMDB's fake as nothing. */
    private fun episode(n: Int) = EpisodePlay(
        showTmdbId = SHOW, showName = NAME, showYear = 2024, aliases = emptyList(), ids = TvIds(tmdb = SHOW), season = 1, episode = n,
        episodeName = NAMES[n - 1], airDate = null, runtimeMinutes = 6,
    )

    /** Kitchen display connected and chosen in Play on, with no cast service left over. */
    private suspend fun connectKitchen() {
        CastFakes.connected(kitchen, connector)
        remote = onMainThread { GoogleCast.connection.player(context) } as FakeRemotePlayer
        container.playOn.choose(PlayChoice.Cast(kitchen.id, kitchen.name))
        val connected = PlayTarget.Cast(kitchen.id, kitchen.name, CastLink.Connected, kitchen.facts)
        waitFor("Kitchen display connected") { Unit.takeIf { container.playOn.target.value == connected } }
        waitFor("no cast service left over", 30_000) { Unit.takeIf { !serviceRunning() } }
    }

    /** Android's record of the cast service, read and never printed: it names the app's process. */
    private fun serviceRunning(): Boolean = instrumentation.uiAutomation.shell("dumpsys activity services ${context.packageName}/${CastService::class.java.name}")
        .let { "ServiceRecord{" in it && "CastService" in it }

    private companion object {
        /** An invented show nothing real or fake knows, and its names. */
        const val SHOW = 900_271
        const val NAME = "Quiet Meridian"
        const val EPISODES = 4

        /** An invented film nothing real or fake knows. */
        const val FILM = 900_272
        const val FILM_NAME = "Copper Crown"
        val NAMES = listOf("First Light", "Pilot Boat", "The Sandbar", "Salt Road")
        const val INDEXER = "download-cast-device-test"

        /** An NZB opened on Import, invented: its id, as Import keeps it, and its name. */
        const val IMPORT_NZB = "download-cast-device-test-nzb"
        const val IMPORT_TITLE = "Quiet.Meridian.S01.720p.WEB.h264-KESTREL"
        const val POSTER = "/quiet-meridian.jpg"
        const val STILL = "/quiet-meridian-s01e02.jpg"

        /** The device tests' film with chapters: six minutes at 10 fps, 0.7 MB; and a small JPEG for the stored still. */
        const val MEDIA = "chapters.mkv"
        const val JPEG = "scale-poster.jpg"
        const val DURATION_MS = 360_000L

        /** Where a seek reads from, in the film's middle on the receiver's 32 KiB boundaries, and how much. */
        const val MIDDLE = 327_680L
        const val SEEK_READ = 64L * 1024
        const val TAIL = 96L * 1024

        /** What Resolve's probe read of a file whose only sound is DTS, which no Cast device plays. */
        val SILENT = ProbedTracks(
            listOf(ProbedTrack(MimeTypes.VIDEO_H264, "H.264", width = 1280, height = 720, bitDepth = 8)), listOf(ProbedTrack(MimeTypes.AUDIO_DTS, "DTS", "en", channels = 6)),
        )

        /** Three recorded gaps a minute or so into the film, 1000 bytes each. */
        const val GAP_AT = 120_000L
        const val GAP = 1_000L

        val ALIVE = arrayOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.RESTARTED)

        const val SEEDING_MILLIS = 60_000L
        const val LOAD_MILLIS = 60_000L
        const val LOAD_WITHIN_MILLIS = 1_000L
        const val SETTLE_MILLIS = 2_000L
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE
    }
}
