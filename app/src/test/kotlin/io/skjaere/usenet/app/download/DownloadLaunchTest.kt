package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.PlayOrigin
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.ProbedTracksSerializer
import io.skjaere.usenet.app.play.RemotePlay
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.VideoTiming
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.ProbedTrack
import io.skjaere.usenet.core.ProbedTracks
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadGap
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.player.PlayUris
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L352, L353: the launch a finished download plays by ([DownloadLaunch]), built from its row alone, so it needs no TMDB,
 * indexer or provider: its `download://` URI, the row's names and watch key, the release its file came from with the file's
 * name and exact size, no request behind it, and what Resolve's probe read of the video and its tracks. And which plays
 * look for one: those asked for on this device (a tap, an auto-advance's hand-on), never a cast's, a paired phone's or a
 * TV's. Names and ids are invented.
 */
class DownloadLaunchTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val tracks = ProbedTracks(
        video = listOf(ProbedTrack("video/hevc", "HEVC", width = 1920, height = 1080, bitDepth = 10)),
        audio = listOf(ProbedTrack("audio/eac3", "E-AC-3", "en", default = true, channels = 6)),
        text = listOf(ProbedTrack("application/x-subrip", "SRT", "en")),
    )
    private val release = RememberedRelease(
        "fake-indexer", "hl-s01e05", "Harbor.Lights.S01E05.1080p.WEB.h264-KESTREL", "https://indexer.example.org/api?t=get&id=hl-s01e05&apikey={apikey}", 0,
        "a1b2c3", "Harbor.Lights.S01E05.1080p.WEB.h264-KESTREL.mkv", 1_400_000_000,
    )
    private val episode = Download(
        id = "3f2c9e1a-7b4d-4c2e-9a10-5e6f7a8b9c0d", watchKey = WatchKey.episode(900002, 1, 5), kind = DownloadKind.Episode,
        title = "Harbor Lights", subtitle = "S01E05 · Low Water", showTmdbId = 900002, season = 1, episode = 5, seasonEpisodes = 10,
        episodeName = "Low Water", state = DownloadState.Done, position = 0, release = release, fileName = release.fileName, size = 1_400_000_000,
        safeOffset = 1_400_000_000, gaps = listOf(DownloadGap(10_000, 19_999)), relativePath = "downloads/3f2c9e1a-7b4d-4c2e-9a10-5e6f7a8b9c0d/v.mkv",
        video = json.encodeToString(VideoTiming.serializer(), VideoTiming(23.976, 2_640_000)), tracks = json.encodeToString(ProbedTracksSerializer, tracks),
        createdAt = 1, completedAt = 2, updatedAt = 2,
    )
    private val film = Download(
        id = "9a8b7c6d", watchKey = WatchKey.movie(900101), kind = DownloadKind.Movie, title = "Copper Crown", subtitle = "2019", movieTmdbId = 900101,
        year = 2019, runtimeMinutes = 118, state = DownloadState.Done, position = 1,
        release = RememberedRelease("fake-indexer", "cc-2019", "Copper.Crown.2019.1080p.WEB.h264-WREN", null, 2, "d4e5f6", "Copper.Crown.2019.1080p.WEB.h264-WREN.mkv", 4_100_000_000),
        fileName = "Copper.Crown.2019.1080p.WEB.h264-WREN.mkv", size = 4_100_000_000, safeOffset = 4_100_000_000, relativePath = "downloads/9a8b7c6d/v.mkv",
        createdAt = 3, completedAt = 4, updatedAt = 4,
    )

    @Test
    fun `an episode's launch plays its file by the row's names and watch key`() {
        val launch = assertNotNull(DownloadLaunch.of(episode))
        assertEquals("download://3f2c9e1a-7b4d-4c2e-9a10-5e6f7a8b9c0d", launch.uri)
        assertEquals(PlayUris.Target.Download(episode.id), PlayUris.parse(launch.uri))
        assertEquals("Harbor Lights", launch.title)
        assertEquals("S01E05 · Low Water", launch.subtitle)
        assertEquals(WatchKey.episode(900002, 1, 5), launch.watchKey)
        // The watch target a stream of the episode has (EpisodePlay's): progress and Up next are the same either way.
        assertEquals(WatchTarget(WatchKind.Episode, "Harbor Lights S01E05", showTmdbId = 900002, season = 1, episode = 5), launch.watch)
        assertEquals(launch.watchKey, launch.watch.rememberedKey)
        // The release its file came from, with the file's own name and exact size, as File details names it.
        assertEquals(
            SavedRelease(
                "fake-indexer", "hl-s01e05", "Harbor.Lights.S01E05.1080p.WEB.h264-KESTREL", "https://indexer.example.org/api?t=get&id=hl-s01e05&apikey={apikey}", 0,
                "a1b2c3", "Harbor.Lights.S01E05.1080p.WEB.h264-KESTREL.mkv", 1_400_000_000,
            ),
            launch.release,
        )
        // From where the episode's progress says, on this device, and nothing replaced.
        assertNull(launch.startPositionMs)
        assertEquals(emptyList(), launch.replaces)
        assertNull(launch.remote)
        assertNull(launch.cast)
        assertFalse(launch.imported)
    }

    @Test
    fun `a film's launch plays it as a film`() {
        val launch = assertNotNull(DownloadLaunch.of(film))
        assertEquals(PlayUris.download("9a8b7c6d"), launch.uri)
        assertEquals("Copper Crown" to "2019", launch.title to launch.subtitle)
        assertEquals(WatchKey.movie(900101), launch.watchKey)
        assertEquals(WatchTarget(WatchKind.Movie, "Copper Crown", movieTmdbId = 900101), launch.watch)
        assertEquals(launch.watchKey, launch.watch.rememberedKey)
        assertEquals(2, launch.release.fileIndex)
        assertEquals("Copper.Crown.2019.1080p.WEB.h264-WREN.mkv" to 4_100_000_000L, launch.release.fileName to launch.release.fileSize)
    }

    @Test
    fun `no request is behind it, so nothing searches for another release of it`() {
        assertNull(assertNotNull(DownloadLaunch.of(episode)).requestId)
        assertNull(assertNotNull(DownloadLaunch.of(film)).requestId)
    }

    @Test
    fun `the video and tracks Resolve's probe read go with it, and none where it read none`() {
        val launch = assertNotNull(DownloadLaunch.of(episode))
        assertEquals(VideoTiming(23.976, 2_640_000), launch.video)
        assertEquals(tracks, launch.tracks)
        val bare = assertNotNull(DownloadLaunch.of(film))
        assertNull(bare.video)
        assertNull(bare.tracks)
        // Kept in a form this version can't read: left out, never a crash at the tap.
        val unreadable = assertNotNull(DownloadLaunch.of(episode.copy(video = "{\"fps\":\"many\"}", tracks = "[")))
        assertNull(unreadable.video)
        assertNull(unreadable.tracks)
    }

    @Test
    fun `the launch survives the player's intent`() {
        listOf(episode, film).forEach { row ->
            val launch = assertNotNull(DownloadLaunch.of(row))
            assertEquals(launch, PlayerLaunch.decode(launch.encode()))
        }
    }

    @Test
    fun `a row with no release kept has no launch`() {
        assertNull(DownloadLaunch.of(episode.copy(release = null)))
        assertNull(DownloadLaunch.of(episode.copy(release = release.copy(nzbId = null))))
    }

    @Test
    fun `plays asked for on this device look for a download, a cast's, a phone's or a TV's never`() {
        val here = assertNotNull(DownloadLaunch.of(episode))
        assertTrue(DownloadLaunch.looks(PlayOrigin.Here, television = false))
        // Auto-advance, Previous and Next hand on from a play here.
        assertTrue(DownloadLaunch.looks(PlayOrigin.after(here), television = false))
        assertTrue(DownloadLaunch.looks(PlayOrigin.after(null), television = false))

        val cast = CastTo("route-1", "Invented display")
        val phone = RemotePlay("s-a", "aa".repeat(32), "Invented phone")
        assertFalse(DownloadLaunch.looks(PlayOrigin(cast = cast), television = false))
        assertFalse(DownloadLaunch.looks(PlayOrigin.after(here.copy(cast = cast)), television = false))
        assertFalse(DownloadLaunch.looks(PlayOrigin(remote = phone), television = false))
        assertFalse(DownloadLaunch.looks(PlayOrigin.after(here.copy(remote = phone)), television = false))
        // A TV has none, whatever asks (W108).
        assertFalse(DownloadLaunch.looks(PlayOrigin.Here, television = true))
        assertFalse(DownloadLaunch.looks(PlayOrigin.after(here), television = true))
    }
}
