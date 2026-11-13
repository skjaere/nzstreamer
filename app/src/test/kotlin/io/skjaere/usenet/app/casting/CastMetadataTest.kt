package io.skjaere.usenet.app.casting

import androidx.media3.common.MediaMetadata
import io.skjaere.usenet.app.link.TvArt
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * What a cast play is said as (L62, L89, L120): to the Cast device, which shows it and tells every sender on the network,
 * and to the phone's cast session once the device has it, whose line names the device. The show, film, releases and
 * device are invented.
 */
class CastMetadataTest {
    private val file = "Harbor.Lights.S02E04.1080p.WEB.h264-CAST.mkv"
    private val releaseTitle = "Harbor.Lights.S02E04.1080p.WEB.h264-CAST"
    private val release = SavedRelease("fake", "hl-s02e04", releaseTitle, null, 0, "nzb-hl", file, 1_400_000_000)
    private val uri = UsenetUris.build("nzb-hl", 0, file, 1_400_000_000)

    private val episode = PlayerLaunch(
        uri, "Harbor Lights", "S02E04 · Low Water", WatchKey.episode(900002, 2, 4),
        WatchTarget(WatchKind.Episode, "Harbor Lights S02E04", showTmdbId = 900002, season = 2, episode = 4), release,
    )
    private val film = PlayerLaunch(
        uri, "Copper Crown", "2026", WatchKey.movie(900010), WatchTarget(WatchKind.Movie, "Copper Crown", movieTmdbId = 900010), release,
    )

    /** Played from a search without TMDB: its title is the release's parsed name, its file in the URI. */
    private val bareRelease = PlayerLaunch(
        uri, "Harbor Lights S02E04", "1080p · WEB", WatchKey.release("nzb-hl", 0), WatchTarget(WatchKind.Release, "Harbor Lights S02E04"), release,
    )

    private val still = TvArt("/still-hl-s02e04.jpg")
    private val poster = TvArt("/poster-copper-crown.jpg", poster = true)

    /** Never the stream's URI, the release's name nor its file's: other senders on the network read what the device says. */
    private fun assertNothingOfTheRelease(said: CastMetadata) {
        val words = listOfNotNull(said.title, said.subtitle, said.imageUrl)
        listOf("usenet://", releaseTitle, file, "Harbor.Lights", "nzb-hl").forEach { secret ->
            assertFalse(words.any { secret in it }, "\"$secret\" in what the device is told")
        }
    }

    @Test
    fun `an episode is said by its show over its episode line, as a show's, with its still`() {
        val said = CastMetadata.of(episode, APP, still, showArtwork = true)
        assertEquals("Harbor Lights", said.title)
        assertEquals("S02E04 · Low Water", said.subtitle)
        assertEquals(true, said.episode)
        assertEquals("https://image.tmdb.org/t/p/w300/still-hl-s02e04.jpg", said.imageUrl)
        assertNothingOfTheRelease(said)
    }

    @Test
    fun `a film is said by its title and line, with its poster`() {
        val said = CastMetadata.of(film, APP, poster, showArtwork = true)
        assertEquals("Copper Crown", said.title)
        assertEquals("2026", said.subtitle)
        assertEquals(false, said.episode)
        assertEquals("https://image.tmdb.org/t/p/w500/poster-copper-crown.jpg", said.imageUrl)
        assertNothingOfTheRelease(said)
    }

    /** Its title is a release's parsed name: the device says no more than the app's (L89, M12). */
    @Test
    fun `a release played without TMDB is said by the app's name alone, with no picture`() {
        val said = CastMetadata.of(bareRelease, APP, still, showArtwork = true)
        assertEquals(APP, said.title)
        assertNull(said.subtitle)
        assertEquals(false, said.episode)
        assertNull(said.imageUrl)
        assertNothingOfTheRelease(said)
    }

    @Test
    fun `the picture goes only while Show artwork is on`() {
        assertNull(CastMetadata.of(episode, APP, still, showArtwork = false).imageUrl)
        assertNull(CastMetadata.of(film, APP, poster, showArtwork = false).imageUrl)
        assertNull(CastMetadata.of(film, APP, art = null, showArtwork = true).imageUrl)
    }

    /**
     * The phone's cast session says what the device says, with the still decoded for its notification (L58), its line
     * naming the device (L120).
     */
    @Test
    fun `the session says the same words, with the still's bytes`() {
        val bytes = byteArrayOf(1, 2, 3)
        val session = CastMetadata.of(episode, APP, still, showArtwork = true).session(bytes, DEVICE, sdkInt = 34)
        assertEquals("Harbor Lights", session.title.toString())
        assertEquals(MediaMetadata.MEDIA_TYPE_TV_SHOW, session.mediaType)
        assertContentEquals(bytes, session.artworkData)
        assertNull(session.artworkUri)
        val bare = CastMetadata.of(bareRelease, APP).session(artwork = null, DEVICE, sdkInt = 34)
        assertEquals(APP, bare.title.toString())
        assertEquals(MediaMetadata.MEDIA_TYPE_VIDEO, bare.mediaType)
        assertNull(bare.artworkData)
        assertEquals(MediaMetadata.MEDIA_TYPE_MOVIE, CastMetadata.of(film, APP).session(artwork = null, DEVICE, sdkInt = 34).mediaType)
    }

    /**
     * W62, L120 (CsPhoneNotificationC2): from Android 11 on, where the output chip names the device too, the line under the
     * title ends with it: its subtitle and its artist, which Android's notification, media card and lock screen show there.
     */
    @Test
    fun `from Android 11 on the line under the title ends on the device`() {
        listOf(30, 34, 36).forEach { sdk ->
            val session = CastMetadata.of(episode, APP, still, showArtwork = true).session(artwork = null, DEVICE, sdk)
            assertEquals("Harbor Lights", session.title.toString(), "API $sdk")
            assertEquals("S02E04 · Low Water · on Kitchen display", session.subtitle.toString(), "API $sdk")
            assertEquals("S02E04 · Low Water · on Kitchen display", session.artist.toString(), "API $sdk")
        }
        // A film's line, then the device.
        val filmSession = CastMetadata.of(film, APP).session(artwork = null, DEVICE, sdkInt = 34)
        assertEquals("Copper Crown", filmSession.title.toString())
        assertEquals("2026 · on Kitchen display", filmSession.artist.toString())
    }

    /** W62: up to Android 10 there is no output chip, and the shade cuts the line's end: the device comes first. */
    @Test
    fun `up to Android 10 the line names the device first`() {
        listOf(26, 28, 29).forEach { sdk ->
            val session = CastMetadata.of(episode, APP).session(artwork = null, DEVICE, sdk)
            assertEquals("Harbor Lights", session.title.toString(), "API $sdk")
            assertEquals("On Kitchen display · S02E04 · Low Water", session.subtitle.toString(), "API $sdk")
            assertEquals("On Kitchen display · S02E04 · Low Water", session.artist.toString(), "API $sdk")
        }
        assertEquals("On Kitchen display · 2026", CastMetadata.of(film, APP).session(artwork = null, DEVICE, sdkInt = 29).artist.toString())
    }

    /** A play without TMDB is the app's name alone (L89), and the device goes under it: no "Unknown" there (M1). */
    @Test
    fun `a play without TMDB has the device alone under the app's name`() {
        val later = CastMetadata.of(bareRelease, APP).session(artwork = null, DEVICE, sdkInt = 34)
        assertEquals(APP, later.title.toString())
        assertEquals("on Kitchen display", later.subtitle.toString())
        assertEquals("on Kitchen display", later.artist.toString())
        val earlier = CastMetadata.of(bareRelease, APP).session(artwork = null, DEVICE, sdkInt = 29)
        assertEquals(APP, earlier.title.toString())
        assertEquals("On Kitchen display", earlier.artist.toString())
        // Still nothing of the release on the line.
        listOf(later.artist, earlier.artist).forEach { line -> assertFalse("Harbor" in line.toString() || "1080p" in line.toString(), "$line") }
    }

    /** The device's name is the phone's to say: the device's own words, which every sender on the network reads, never hold it. */
    @Test
    fun `the device's words never name the device`() {
        val said = CastMetadata.of(episode, APP, still, showArtwork = true)
        listOf(said.title, said.subtitle, said.imageUrl).forEach { word -> assertFalse(DEVICE in word.orEmpty(), "\"$word\"") }
    }

    /** L63: before the play has a launch, it is said as its launch will be, so the words never change places at the hand-over. */
    @Test
    fun `a play still starting is said as its launch will be, with no picture yet`() {
        assertEquals(CastMetadata.of(episode, APP), CastMetadata.starting(episode.title, episode.subtitle, episode.watch, APP))
        assertEquals(CastMetadata.of(film, APP), CastMetadata.starting(film.title, film.subtitle, film.watch, APP))
        val bare = CastMetadata.starting(releaseTitle, null, WatchTarget(WatchKind.Release, releaseTitle), APP)
        assertEquals(CastMetadata(APP, subtitle = null, CastMetadata.Kind.Video, imageUrl = null), bare)
        assertNothingOfTheRelease(bare)
        assertEquals(MediaMetadata.MEDIA_TYPE_TV_SHOW, CastMetadata.of(episode, APP).mediaType)
    }

    @Test
    fun `it prints without its words`() {
        val printed = CastMetadata.of(episode, APP, still, showArtwork = true).toString()
        listOf("Harbor Lights", "Low Water", "still-hl").forEach { assertFalse(it in printed, "\"$it\" printed") }
    }

    private companion object {
        const val APP = "NZStreamer"
        const val DEVICE = "Kitchen display"
    }
}
