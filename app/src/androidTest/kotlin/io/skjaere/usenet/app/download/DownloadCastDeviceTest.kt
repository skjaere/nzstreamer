package io.skjaere.usenet.app.download

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.FakeTmdb
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
import io.skjaere.usenet.app.casting.MoveOutcome
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.everyStep
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.shell
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.EpisodeRef
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.metadata.TmdbImages
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

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
 *
 * Put back after each, whatever happened: the rows and their files, the downloads' jobs, the offline override, the cast
 * seams and Play on (This phone), the settings and the marks. The show, its releases and the device are invented.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class DownloadCastDeviceTest {
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
            picture(c, model)?.also { pictureBytes += it }
        }
        // Nothing left over from another test for this one to take.
        onMainThread { container.playback.withdraw() }
        connectKitchen()
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        everyStep(
            { receiver.close() },
            { container.offlineOverride.value = null },
            { onMainThread { container.playback.cancel() } },
            { container.playOn.choose(PlayChoice.Phone) },
            { CastFakes.reset() },
            { seeds.after() },
            // Unwatched again, the releases a cast's saves kept for them forgotten.
            { container.library.markEpisodes((1..EPISODES).map { EpisodeRef(SHOW, 1, it, WatchTitle.episode(NAME, 1, it)) }, watched = false) },
            { made.forEach { row -> row.release?.let { container.library.forgetRelease(row.watchKey, it) } } },
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

    /** L413, L417: a row Delete once watched has taken can't be held: the cast fails as for a file gone, and nothing is sent. */
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
        val loads = remote!!.loads.size
        val failed = withTimeout(LOAD_MILLIS) { cast(row).await() } as? MoveOutcome.Failed ?: throw AssertionError("the cast didn't fail")
        assertEquals(DownloadStorage.FILE_GONE, failed.failure.reason)
        assertEquals("nothing sent", loads, remote!!.loads.size)
    }

    // The download, the cast and the device.

    /**
     * Episode [number] of Quiet Meridian as a finished download is left: its row with its marker (as DownloadsPageSeeds
     * seeds one), then the device tests' chapters.mkv as its file, where storage keeps it.
     */
    private fun finished(number: Int): Download {
        val id = Download.newId()
        val name = "Quiet.Meridian.S01E0$number.720p.WEB.h264-KESTREL.mkv"
        val size = film.size.toLong()
        val row = Download(
            id = id, watchKey = WatchKey.episode(SHOW, 1, number), kind = DownloadKind.Episode, title = NAME, subtitle = "S01E0$number · ${NAMES[number - 1]}",
            showTmdbId = SHOW, season = 1, episode = number, seasonEpisodes = EPISODES, episodeName = NAMES[number - 1], posterPath = POSTER,
            stillPath = STILL, state = DownloadState.Done, position = -1_000L + number,
            release = RememberedRelease(INDEXER, "qm-s01e0$number", name.removeSuffix(".mkv"), null, 0, "nzb-qm-$number", name, size),
            fileName = name, size = size, indexerSize = size, safeOffset = size, relativePath = "downloads/$id/v.mkv", createdAt = 1, completedAt = 2,
            updatedAt = 2,
        )
        seeds.seed(row)
        made += row
        val folder = checkNotNull(storage.videoFolder(id)) { "no primary volume" }
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
        val NAMES = listOf("First Light", "Pilot Boat", "The Sandbar", "Salt Road")
        const val INDEXER = "download-cast-device-test"
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

        const val SEEDING_MILLIS = 60_000L
        const val LOAD_MILLIS = 60_000L
        const val LOAD_WITHIN_MILLIS = 1_000L
        const val SETTLE_MILLIS = 2_000L
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE
    }
}
