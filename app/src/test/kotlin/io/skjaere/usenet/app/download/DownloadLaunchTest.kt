package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.link.TvArt
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.IMPORTED
import io.skjaere.usenet.app.play.MovieTarget
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
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadGap
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.player.PlayUris
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
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
 * look for one: those asked for on this device (a tap, an auto-advance's hand-on) and those cast from it to a Google Cast
 * device (W121 c, L446), never a paired phone's or a TV's. Names and ids are invented.
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

    private val imported = Download(
        id = "7c1d2e3f-0a1b-4c2d-8e3f-405162738495", watchKey = WatchKey.release(NZB_SHA, 1), kind = DownloadKind.Release, title = FILE_NAME,
        subtitle = "Quiet.Meridian.S01.1080p.WEB.h264-WREN", state = DownloadState.Done, position = 2,
        candidates = listOf(DownloadCandidate(IMPORTED, NZB_SHA, "Quiet.Meridian.S01.1080p.WEB.h264-WREN", size = 1_300_000_000)),
        release = RememberedRelease(IMPORTED, NZB_SHA, "Quiet.Meridian.S01.1080p.WEB.h264-WREN", null, 1, NZB_SHA, FILE_NAME, 1_300_000_000),
        fileName = FILE_NAME, size = 1_300_000_000, indexerSize = 1_300_000_000, safeOffset = 1_300_000_000,
        relativePath = "downloads/7c1d2e3f-0a1b-4c2d-8e3f-405162738495/v.mkv", createdAt = 5, completedAt = 6, updatedAt = 6,
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

    /**
     * L423: a file of an NZB opened on Import and downloaded plays as Import's own release of it ([IMPORTED], the NZB's id),
     * under its `release:` key, the NZB's SHA-256 and the file's place, with a release's watch named by the file: so its
     * progress saves, never as an Import stream's, which saves none, and Up next names it by the file. It has no picture.
     */
    @Test
    fun `an imported file's launch plays its file under its release key, saving its progress`() {
        val launch = assertNotNull(DownloadLaunch.of(imported))
        assertEquals(PlayUris.download(imported.id), launch.uri)
        assertEquals(WatchKey.release(NZB_SHA, 1), launch.watchKey)
        assertEquals("release:$NZB_SHA:1", launch.watchKey)
        assertEquals(WatchTarget(WatchKind.Release, FILE_NAME), launch.watch)
        // A release's watch is keyed by the NZB and the file, the same key either way.
        assertEquals(launch.watchKey, launch.watch.key(launch.release.nzbId, launch.release.fileIndex))
        assertEquals(FILE_NAME to "Quiet.Meridian.S01.1080p.WEB.h264-WREN", launch.title to launch.subtitle)
        assertEquals(SavedRelease(IMPORTED, NZB_SHA, "Quiet.Meridian.S01.1080p.WEB.h264-WREN", null, 1, NZB_SHA, FILE_NAME, 1_300_000_000), launch.release)
        assertFalse(launch.imported, "saved as an Import stream, which saves nothing")
        assertNull(launch.requestId)
        assertTrue(DownloadLaunch.accepts(imported))
        assertEquals(launch, PlayerLaunch.decode(launch.encode()))
        assertNull(DownloadLaunch.art(imported))
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

    /** And the marks, which count what the launch takes (L407), don't count it: accepts says what of says, without building it. */
    @Test
    fun `a row with no release kept has no launch`() {
        assertNull(DownloadLaunch.of(episode.copy(release = null)))
        assertNull(DownloadLaunch.of(episode.copy(release = release.copy(nzbId = null))))
        listOf(episode, film, episode.copy(release = null), episode.copy(release = release.copy(nzbId = null)), film.copy(release = null)).forEach { row ->
            assertEquals(DownloadLaunch.of(row) != null, DownloadLaunch.accepts(row), "${row.id}: ${row.release}")
        }
        assertTrue(DownloadLaunch.accepts(episode))
        assertFalse(DownloadLaunch.accepts(film.copy(release = null)))
    }

    /**
     * A download's failed play forgets its release as the one that played the title (a file it can't read), and a failure
     * to save that is logged: the line names the release by its download, never by its title, as the downloads' lines do.
     */
    @Test
    fun `a line about a download's release names its download, never the release, and a stream's names it as before`() {
        listOf(episode, film).forEach { row ->
            val named = DownloadLaunch.releaseNamed(assertNotNull(DownloadLaunch.of(row)))
            val release = assertNotNull(row.release)
            assertEquals("the release of download ${row.id}", named)
            assertFalse(release.title in named || release.fileName.orEmpty() in named || release.guid in named, named)
        }
        val stream = assertNotNull(DownloadLaunch.of(film)).copy(uri = "usenet://nzb/d4e5f6/0")
        assertEquals("Copper.Crown.2019.1080p.WEB.h264-WREN", DownloadLaunch.releaseNamed(stream))
        // The coordinator's forgets for a launch go through it.
        val source = File("src/main/kotlin/io/skjaere/usenet/app/play/PlaybackCoordinator.kt").readText()
        val forgets = Regex("""forget\(launch\.watchKey, launch\.release\.toRemembered\(\)\)""").findAll(source).count()
        assertEquals(0, forgets, "a forget for a launch naming its release by its title")
        assertTrue("DownloadLaunch.releaseNamed(launch)" in source)
    }

    /**
     * Stream instead of a film's download (L354): its year and length, which its search and the ranker's estimate go by
     * where TMDB doesn't answer, come from its download's row, read by the launch's id in any state. By the time Stream
     * instead is tapped on "The downloaded file is gone", the player's open has failed the row as gone, out of the finished
     * ones, and it still says both.
     */
    @Test
    fun `a film streamed instead takes its year and length from its download's row, failed as gone or not`() = runBlocking<Unit> {
        val launch = assertNotNull(DownloadLaunch.of(film))
        val gone = DownloadStorage.failedGone(film, now = 10)
        assertEquals(DownloadState.Failed to DownloadStorage.FILE_GONE, gone.state to gone.reason)
        val known = MovieTarget(900101, "Copper Crown", year = null, imdb = null, runtimeMinutes = null)
        val asked = mutableListOf<String>()
        listOf(film, gone).forEach { row ->
            val target = DownloadLaunch.filmFacts(known, launch) { id -> asked += id; row.takeIf { it.id == id } }
            assertEquals(known.copy(year = 2019, runtimeMinutes = 118), target, "${row.state}")
        }
        assertEquals(listOf("9a8b7c6d", "9a8b7c6d"), asked)
        // Its row gone since (deleted), or nothing of it known: as it was.
        assertEquals(known, DownloadLaunch.filmFacts(known, launch) { null })
        assertEquals(known, DownloadLaunch.filmFacts(known, launch) { gone.copy(year = null, runtimeMinutes = null) })
        // A launch that isn't a download's has no row to ask.
        assertEquals(known, DownloadLaunch.filmFacts(known, launch.copy(uri = "usenet://nzb/d4e5f6/0")) { error("asked for $it") })
    }

    /**
     * L446 (W121 c): a play cast from this phone looks for a download too, as the hook judges whether its file is cast;
     * a paired phone's never, nor anything on a TV.
     */
    @Test
    fun `plays asked for on this device or cast from it look for a download, a phone's or a TV's never`() {
        val here = assertNotNull(DownloadLaunch.of(episode))
        assertTrue(DownloadLaunch.looks(PlayOrigin.Here, television = false))
        // Auto-advance, Previous and Next hand on from a play here.
        assertTrue(DownloadLaunch.looks(PlayOrigin.after(here), television = false))
        assertTrue(DownloadLaunch.looks(PlayOrigin.after(null), television = false))

        val cast = CastTo("route-1", "Invented display")
        val phone = RemotePlay("s-a", "aa".repeat(32), "Invented phone")
        assertTrue(DownloadLaunch.looks(PlayOrigin(cast = cast), television = false))
        assertTrue(DownloadLaunch.looks(PlayOrigin.after(here.copy(cast = cast)), television = false), "a cast's next episode")
        assertFalse(DownloadLaunch.looks(PlayOrigin(remote = phone), television = false))
        assertFalse(DownloadLaunch.looks(PlayOrigin.after(here.copy(remote = phone)), television = false))
        // A TV has none, whatever asks (W108).
        assertFalse(DownloadLaunch.looks(PlayOrigin.Here, television = true))
        assertFalse(DownloadLaunch.looks(PlayOrigin.after(here), television = true))
        assertFalse(DownloadLaunch.looks(PlayOrigin(cast = cast), television = true))
    }

    /**
     * L451: what a cast of a download shows of TMDB's, from its row alone, with no lookup: an episode's still, else its
     * show's poster; a film's poster (a still is an episode's alone, as its stored pictures are); none where the row has
     * neither.
     */
    @Test
    fun `a download's picture for a cast is its row's still, else its poster, with no lookup`() {
        assertEquals(TvArt("/hl-s01e05.jpg"), DownloadLaunch.art(episode.copy(stillPath = "/hl-s01e05.jpg", posterPath = "/hl.jpg")))
        assertEquals(TvArt("/hl.jpg", poster = true), DownloadLaunch.art(episode.copy(stillPath = null, posterPath = "/hl.jpg")))
        assertEquals(TvArt("/cc.jpg", poster = true), DownloadLaunch.art(film.copy(stillPath = "/cc-still.jpg", posterPath = "/cc.jpg")))
        assertNull(DownloadLaunch.art(episode.copy(stillPath = null, posterPath = null)))
        assertNull(DownloadLaunch.art(film.copy(posterPath = null)))
    }

    private companion object {
        /** The imported NZB's id: the SHA-256 of its bytes, as Import names it. */
        const val NZB_SHA = "5b1c9d0e7f3a2b4c6d8e0f1a3b5c7d9e1f2a4b6c8d0e2f4a6b8c0d2e4f6a8b0c"
        const val FILE_NAME = "Quiet.Meridian.S01E02.1080p.WEB.h264-WREN.mkv"
    }
}
