package io.skjaere.usenet.app.download

import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.read
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.EpisodeRef
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchTitle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/**
 * Delete once watched on a phone (W111; L412-L416), over the app's runner, storage, library and settings. Nothing waits a
 * real day: the run is called in the test's process ([DownloadRunner.deleteWatched]) as of a time a day and more after
 * the marks, while the app's own run, on the device's clock, finds nothing due yet.
 * - Switched on, a finished episode marked watched says it goes tomorrow, then goes: its file, both its folders and its
 *   row, as storage deletes a download; one not marked stays.
 * - One an open player plays stays while it plays, whatever is due, and goes once the player has finished.
 * - A title downloaded again after it was marked watched stays.
 * - Switched off, nothing goes and nothing is said.
 *
 * The downloads are seeded as finished ones are left, through the app's own storage. Put back after each, whatever
 * happened: the settings, the rows and their files, the marks (unwatched), the player's seams, the downloads' jobs. The
 * show and its releases are invented.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class DownloadAutoDeleteDeviceTest {
    /** The player hides the system bars: Android's hint about that would take its window's focus. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val storage = container.downloadStorage
    private val dao = container.data.downloads
    private val runner = container.downloads
    private val settings = container.settings
    private val realNext = PlayerActivity.nextEpisode
    private val realPrevious = PlayerActivity.previousEpisode
    private val realLookUp = PlayerActivity.lookUpSegments
    private val made = mutableListOf<Download>()
    private var saved: AppSettings? = null

    @Before
    fun setUp() = runBlocking<Unit> {
        assumeFalse("downloads show on phones and tablets alone (L369)", container.television)
        // The debug build's start saves its dev settings: before the test reads or takes any.
        withTimeout(SEEDING_MILLIS) { container.devSettingsSaved.await() }
        saved = settings.settings.first().also { settings.restore(it.copy(downloads = it.downloads.copy(deleteOnceWatched = false, deleteOnceWatchedSince = null))) }
        // The player asks TMDB nothing of the invented show.
        PlayerActivity.nextEpisode = { _, _ -> null }
        PlayerActivity.previousEpisode = { _, _ -> null }
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        PlayerActivity.nextEpisode = realNext
        PlayerActivity.previousEpisode = realPrevious
        PlayerActivity.lookUpSegments = realLookUp
        saved?.let { settings.restore(it) }
        made.forEach { storage.delete(it.id) }
        container.library.markEpisodes((1..EPISODES).map { EpisodeRef(SHOW, 1, it, WatchTitle.episode(NAME, 1, it)) }, watched = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) DownloadJobService.scheduler(context).cancelAll()
    }

    @Test
    fun aDownloadMarkedWatchedSaysItGoesTomorrowThenGoesFilesFoldersAndRow() = runBlocking<Unit> {
        val going = seed(episode(1))
        val staying = seed(episode(2))
        val file = checkNotNull(storage.file(going))
        settings.setDownloadsDeleteOnceWatched(true)
        mark(1)
        waitFor("it says it goes tomorrow") { runner.deletingTomorrow.value.takeIf { going.id in it } }
        assertFalse(staying.id in runner.deletingTomorrow.value)

        val deleted = runner.deleteWatched(System.currentTimeMillis() + DAY + MINUTE)
        assertEquals(listOf(going.id), deleted.filter { id -> made.any { it.id == id } })
        assertEquals(null, dao.get(going.id))
        assertFalse("its file", file.exists())
        assertFalse("its video's folder", storage.videoFolder(going.id)?.exists() ?: false)
        assertFalse("its bookkeeping", storage.bookkeeping(going.id).exists())
        waitFor("it says nothing more") { Unit.takeIf { going.id !in runner.deletingTomorrow.value } }
        // Not marked: kept.
        assertEquals(DownloadState.Done, dao.get(staying.id)?.state)
        assertTrue(checkNotNull(storage.file(staying)).isFile)
    }

    /** L413: what plays is never deleted; held by its player, it goes once the player has finished. */
    @Test
    fun aDownloadAnOpenPlayerPlaysStaysUntilThePlayerHasFinished() = runBlocking<Unit> {
        val playing = seed(episode(1), playable = true)
        val file = checkNotNull(storage.file(playing))
        settings.setDownloadsDeleteOnceWatched(true)
        mark(1)
        waitFor("it says it goes tomorrow") { runner.deletingTomorrow.value.takeIf { playing.id in it } }
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, checkNotNull(DownloadLaunch.of(playing)))).use { scenario ->
            waitFor("the file playing", 30_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying } } }
            assertTrue("held by its player", playing.id in runner.held.value)
            assertFalse(playing.id in runner.deleteWatched(System.currentTimeMillis() + DAY + MINUTE))
            assertEquals(DownloadState.Done, dao.get(playing.id)?.state)
            assertTrue("its file, while it plays", file.isFile)
            assertTrue(scenario.read { a -> a.player?.isPlaying == true })
        }
        waitFor("its player let go", 30_000) { Unit.takeIf { playing.id !in runner.held.value } }
        // Its first seconds played again: still watched, as of the player's last save.
        assertTrue(container.library.episodeStates(SHOW).first()[1 to 1]?.watched == true)
        assertEquals(listOf(playing.id), runner.deleteWatched(System.currentTimeMillis() + DAY + MINUTE).filter { id -> made.any { it.id == id } })
        assertEquals(null, dao.get(playing.id))
        assertFalse(file.exists())
    }

    /** L412: a title marked watched, then downloaded again, keeps its new download, whenever the run comes. */
    @Test
    fun aTitleDownloadedAgainAfterItWasMarkedWatchedStays() = runBlocking<Unit> {
        settings.setDownloadsDeleteOnceWatched(true)
        mark(3)
        val markedAt = checkNotNull(container.library.episodeStates(SHOW).first()[1 to 3]).updatedAt
        val again = seed(episode(3, completedAt = markedAt + MINUTE))
        assertEquals(emptyList<String>(), runner.deleteWatched(System.currentTimeMillis() + 10 * DAY).filter { id -> made.any { it.id == id } })
        assertEquals(DownloadState.Done, dao.get(again.id)?.state)
        assertTrue(checkNotNull(storage.file(again)).isFile)
        assertFalse(again.id in runner.deletingTomorrow.value)
    }

    /** W111: off by default; off, nothing goes and nothing says it will. */
    @Test
    fun withTheSettingOffNothingGoes() = runBlocking<Unit> {
        val kept = seed(episode(1))
        mark(1)
        assertEquals(emptyList<String>(), runner.deleteWatched(System.currentTimeMillis() + 10 * DAY).filter { id -> made.any { it.id == id } })
        assertEquals(DownloadState.Done, dao.get(kept.id)?.state)
        assertTrue(checkNotNull(storage.file(kept)).isFile)
        assertFalse(kept.id in runner.deletingTomorrow.value)
    }

    /**
     * Episode [number] marked watched now. The run, as of a day ahead, would delete any finished download watched so: only
     * the test's own may be, or it would take another's with them.
     */
    private suspend fun mark(number: Int) {
        container.library.markEpisodes(listOf(EpisodeRef(SHOW, 1, number, WatchTitle.episode(NAME, 1, number))), watched = true)
        val others = container.data.downloadWatches.watched().first().filter { row -> made.none { it.id == row.id } }
        if (others.isNotEmpty()) throw AssertionError("${others.size} watched download(s) not the test's, which the run would delete: ${others.map { it.id }}")
    }

    /**
     * [row] as a finished download is left: its file written (the device tests' chapters.mkv where it is [playable], else a
     * small one), renamed done, then its row, Done, with its marker.
     */
    private suspend fun seed(row: Download, playable: Boolean = false): Download {
        val bytes = if (playable) DownloadFixtures.media(MEDIA) else ByteArray(SMALL) { it.toByte() }
        val name = checkNotNull(row.fileName)
        storage.open(row.id, name, bytes.size.toLong()).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        storage.finish(row.id, name)
        val size = bytes.size.toLong()
        val done = row.copy(
            release = checkNotNull(row.release).copy(fileSize = size), size = size, indexerSize = size, safeOffset = size,
            relativePath = storage.relativePath(row.id, name),
        )
        made += done
        storage.add(done)
        // The player asks the runner's map, which follows the library.
        waitFor("download ${done.id} among the finished ones") { runner.done.value[done.watchKey]?.takeIf { it.id == done.id } }
        return done
    }

    private fun episode(number: Int, completedAt: Long = 2): Download {
        val name = "Quiet.Meridian.S01E0$number.720p.WEB.h264-KESTREL.mkv"
        return Download(
            id = Download.newId(), watchKey = WatchKey.episode(SHOW, 1, number), kind = DownloadKind.Episode, title = NAME,
            subtitle = "S01E0$number · Episode $number", showTmdbId = SHOW, season = 1, episode = number, seasonEpisodes = EPISODES,
            episodeName = "Episode $number", state = DownloadState.Done, position = -1_000L + number,
            release = RememberedRelease(INDEXER, "dl-auto-delete-$number", name.removeSuffix(".mkv"), null, 0, "nzb-auto-delete-$number", name),
            fileName = name, createdAt = 1, completedAt = completedAt, updatedAt = 2,
        )
    }

    private companion object {
        /** An invented show nothing real or fake knows. */
        const val SHOW = 900_251
        const val NAME = "Quiet Meridian"
        const val EPISODES = 4
        const val INDEXER = "download-auto-delete-device-test"

        /** The device tests' film with chapters: six minutes at 10 fps, 0.7 MB. */
        const val MEDIA = "chapters.mkv"

        /** A file no player opens: its row says its size. */
        const val SMALL = 64 * 1024

        const val SEEDING_MILLIS = 60_000L
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE
    }
}
