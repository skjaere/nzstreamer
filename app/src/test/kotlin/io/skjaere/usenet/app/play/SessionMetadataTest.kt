package io.skjaere.usenet.app.play

import androidx.media3.common.MediaMetadata
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.player.PlayUris
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SessionMetadataTest {
    private val file = "Harbor.Lights.S02E05.1080p.WEB.h264-GRP.mkv"
    private val releaseTitle = "Harbor.Lights.S02E05.1080p.WEB.h264-GRP"
    private val release = SavedRelease("fake", "hl-s02e05", releaseTitle, null, 0, "abc123", file, 1_000_005_000)
    private val uri = UsenetUris.build("abc123", 0, file, 1_000_005_000)

    private val episode = PlayerLaunch(
        uri, "Harbor Lights", "S02E05 · The Long Tide", WatchKey.episode(900002, 2, 5),
        WatchTarget(WatchKind.Episode, "Harbor Lights S02E05", showTmdbId = 900002, season = 2, episode = 5), release,
    )
    private val film = PlayerLaunch(
        uri, "Copper Crown", "2024", WatchKey.movie(900010), WatchTarget(WatchKind.Movie, "Copper Crown", movieTmdbId = 900010), release,
    )

    /** Played from a search without TMDB: the release's parsed name, which the top bar shows, and its file in the URI. */
    private val bareRelease = PlayerLaunch(
        uri, "Harbor Lights S02E05", "1080p · WEB", WatchKey.release("abc123", 0), WatchTarget(WatchKind.Release, "Harbor Lights S02E05"), release,
    )

    /** Neither the stream's URI, the release's name nor its file's: watches, car displays and notification listeners read what a session says (M12). */
    private fun assertNothingOfTheRelease(said: SessionMetadata) {
        val words = listOfNotNull(said.mediaId, said.title, said.subtitle)
        listOf("usenet://", releaseTitle, file, "Harbor.Lights", "abc123/file").forEach { secret ->
            assertFalse(words.any { secret in it }, "\"$secret\" in $said")
        }
    }

    /** As the top bar shows it: the episode's name, its show's under it. */
    @Test
    fun `an episode of a TMDB show is said as the top bar shows it, by its watch key`() {
        val said = SessionMetadata.of(episode, APP)
        assertEquals(SessionMetadata(WatchKey.episode(900002, 2, 5), "S02E05 · The Long Tide", "Harbor Lights", MediaMetadata.MEDIA_TYPE_TV_SHOW), said)
        assertNothingOfTheRelease(said)
        assertEquals(PlayerTitles("S02E05 · The Long Tide", "Harbor Lights"), PlayerTitles.of(episode, bareTitle = null))
    }

    @Test
    fun `a TMDB film is said by its title`() {
        val said = SessionMetadata.of(film, APP)
        assertEquals(SessionMetadata(WatchKey.movie(900010), "Copper Crown", "2024", MediaMetadata.MEDIA_TYPE_MOVIE), said)
        assertNothingOfTheRelease(said)
        assertEquals(PlayerTitles("Copper Crown", "2024"), PlayerTitles.of(film, bareTitle = null))
    }

    /** The top bar shows a release's parsed name; a session says no more than the app's. */
    @Test
    fun `a release played without TMDB is said as the app`() {
        val said = SessionMetadata.of(bareRelease, APP)
        assertEquals(SessionMetadata(WatchKey.release("abc123", 0), APP, null, MediaMetadata.MEDIA_TYPE_VIDEO), said)
        assertNothingOfTheRelease(said)
        assertEquals(PlayerTitles("Harbor Lights S02E05", "1080p · WEB"), PlayerTitles.of(bareRelease, bareTitle = null))
    }

    /**
     * A torrent played without TMDB (L181): its watch key holds the torrent's content id, so the session says it by the
     * opaque places of its URI instead, the listing the app keeps and the file in it, never its hash.
     */
    @Test
    fun `a torrent played without TMDB is said by its listing's opaque id and its file, never its hash`() {
        val hash = "c0ffee11c0ffee22c0ffee33c0ffee44c0ffee55"
        val torrent = bareRelease.copy(
            uri = PlayUris.service("0f3e9a1c5b7d2e4f6a8b0c1d2e3f4a5b", 1), watchKey = WatchKey.release("btih:$hash", 1),
            release = SavedRelease("prowlarr", "btih:$hash", releaseTitle, null, 1, "btih:$hash", file, 1_000_005_000, source = "torrent"),
        )
        val said = SessionMetadata.of(torrent, APP)
        assertEquals(SessionMetadata("debrid:0f3e9a1c5b7d2e4f6a8b0c1d2e3f4a5b:1", APP, null, MediaMetadata.MEDIA_TYPE_VIDEO), said)
        assertFalse(hash in said.toString())
        assertNothingOfTheRelease(said)
        // An episode of TMDB's is said by its episode's key, which names no torrent either.
        assertEquals(SessionMetadata.of(episode, APP).mediaId, SessionMetadata.of(episode.copy(uri = torrent.uri, release = torrent.release), APP).mediaId)
    }

    /** An NZB opened as a file plays without a launch, under the file's name: the session says the app's. */
    @Test
    fun `a file opened without a launch is said as the app, by no key of its own`() {
        val said = SessionMetadata.of(null, APP)
        assertEquals(SessionMetadata(SessionMetadata.NO_KEY, APP, null, MediaMetadata.MEDIA_TYPE_VIDEO), said)
        assertNothingOfTheRelease(said)
        assertEquals(PlayerTitles(file, null), PlayerTitles.of(null, bareTitle = file))
    }

    /**
     * L63, L86, L89: a cast's notification before the device has the release says the request as the device's item will
     * say its launch, the show over its episode's line (not the player's top bar's order), the app's name alone for a play
     * without TMDB, whose title is a release's name.
     */
    @Test
    fun `a play still starting is said as its launch will be, never by a release's name`() {
        val starting = SessionMetadata.starting(episode.title, episode.subtitle, episode.watch, APP)
        assertEquals(SessionMetadata(WatchKey.episode(900002, 2, 5), "Harbor Lights", "S02E05 · The Long Tide", MediaMetadata.MEDIA_TYPE_TV_SHOW), starting)
        assertEquals(SessionMetadata.of(film, APP), SessionMetadata.starting(film.title, film.subtitle, film.watch, APP))
        val bare = SessionMetadata.starting(releaseTitle, null, WatchTarget(WatchKind.Release, releaseTitle), APP)
        assertEquals(SessionMetadata(SessionMetadata.NO_KEY, APP, null, MediaMetadata.MEDIA_TYPE_VIDEO), bare)
        assertNothingOfTheRelease(bare)
    }

    @Test
    fun `the session's metadata is what it says`() {
        val metadata = SessionMetadata.of(episode, APP).metadata()
        assertEquals("S02E05 · The Long Tide", metadata.title)
        assertEquals("Harbor Lights", metadata.subtitle)
        assertEquals(MediaMetadata.MEDIA_TYPE_TV_SHOW, metadata.mediaType)
        assertNull(metadata.artworkUri, "no artwork")
        assertNull(SessionMetadata.of(null, APP).metadata().subtitle)
    }

    private companion object {
        const val APP = "NZStreamer"
    }
}
