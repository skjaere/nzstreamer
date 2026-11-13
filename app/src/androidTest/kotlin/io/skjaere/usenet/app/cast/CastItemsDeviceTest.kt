package io.skjaere.usenet.app.cast

import android.os.Build
import androidx.media3.cast.Cast
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import io.skjaere.usenet.app.casting.CastMetadata
import io.skjaere.usenet.app.link.TvArt
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L62, L89, L120: what a load asks of the device, read as the device gets it, with a real Bundle (CastItemsTest reads the
 * plain fields on the JVM): the play's words, a show's or a film's, TMDB's picture only while "Show artwork" is on, the
 * container's type, a buffered stream and no custom data; and nowhere in it a file's or a release's name, nor the device's
 * own, which only the phone's session says. Nothing of Google's is started for it. The show, the film, the releases, the
 * device and the address are invented.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class CastItemsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = "Harbor.Lights.S02E04.1080p.WEB.h264-CAST.mkv"
    private val releaseTitle = "Harbor.Lights.S02E04.1080p.WEB.h264-CAST"
    private val release = SavedRelease("fake", "hl-s02e04", releaseTitle, null, 0, "abc123", file, 1_400_000_000)
    private val uri = UsenetUris.build("abc123", 0, file, 1_400_000_000)
    private val url = "http://192.168.0.20:41234/0123456789abcdef0123456789abcdef/v.mkv"

    private val episode = PlayerLaunch(
        uri, "Harbor Lights", "S02E04 · Low Water", WatchKey.episode(900002, 2, 4),
        WatchTarget(WatchKind.Episode, "Harbor Lights S02E04", showTmdbId = 900002, season = 2, episode = 4), release,
    )
    private val film = PlayerLaunch(
        uri, "Copper Crown", "2026", WatchKey.movie(900010), WatchTarget(WatchKind.Movie, "Copper Crown", movieTmdbId = 900010), release,
    )
    private val bareRelease = PlayerLaunch(
        uri, "Harbor Lights S02E04", "1080p · WEB", WatchKey.release("abc123", 0), WatchTarget(WatchKind.Release, "Harbor Lights S02E04"), release,
    )

    private fun sent(said: CastMetadata, type: String = "video/x-matroska") = CastItems.toMediaQueueItem(CastItems.item(url, type, said, artwork = null, DEVICE, Build.VERSION.SDK_INT))

    private fun MediaInfo.images(): List<String> = metadata?.images.orEmpty().map { it.url.toString() }

    /** Neither the stream's file, its release, nor the usenet:// URI, anywhere in what the device is sent. */
    private fun assertNothingOfTheRelease(json: String) {
        listOf(file, releaseTitle, "Harbor.Lights", "usenet://", "abc123").forEach { secret ->
            assertFalse("\"$secret\" in what the device is sent", secret in json)
        }
    }

    @Test
    fun anEpisodeGoesAsAShowWithItsLineAndItsStillOnlyWithShowArtwork() {
        val withArt = sent(CastMetadata.of(episode, APP, TvArt("/still-hl-s02e04.jpg"), showArtwork = true))
        val media = withArt.media ?: throw AssertionError("no media")
        val metadata = media.metadata ?: throw AssertionError("no metadata")
        assertEquals(MediaMetadata.MEDIA_TYPE_TV_SHOW, metadata.mediaType)
        assertEquals("Harbor Lights", metadata.getString(MediaMetadata.KEY_TITLE))
        assertEquals("S02E04 · Low Water", metadata.getString(MediaMetadata.KEY_SUBTITLE))
        assertEquals(listOf("https://image.tmdb.org/t/p/w300/still-hl-s02e04.jpg"), media.images())
        assertEquals("video/x-matroska", media.contentType)
        assertEquals(MediaInfo.STREAM_TYPE_BUFFERED, media.streamType)
        assertNull(media.customData)
        assertNull(withArt.customData)
        assertNothingOfTheRelease(withArt.toJson().toString())

        val without = sent(CastMetadata.of(episode, APP, TvArt("/still-hl-s02e04.jpg"), showArtwork = false)).media ?: throw AssertionError("no media")
        assertEquals(emptyList<String>(), without.images())
        assertEquals("Harbor Lights", without.metadata?.getString(MediaMetadata.KEY_TITLE))
    }

    @Test
    fun aFilmGoesAsAFilmWithItsPoster() {
        val item = sent(CastMetadata.of(film, APP, TvArt("/poster-copper-crown.jpg", poster = true), showArtwork = true), "video/mp4")
        val media = item.media ?: throw AssertionError("no media")
        assertEquals(MediaMetadata.MEDIA_TYPE_MOVIE, media.metadata?.mediaType)
        assertEquals("Copper Crown", media.metadata?.getString(MediaMetadata.KEY_TITLE))
        assertEquals("2026", media.metadata?.getString(MediaMetadata.KEY_SUBTITLE))
        assertEquals(listOf("https://image.tmdb.org/t/p/w500/poster-copper-crown.jpg"), media.images())
        assertEquals("video/mp4", media.contentType)
        assertNothingOfTheRelease(item.toJson().toString())
    }

    /** Its title is a release's parsed name, which other senders on the network would read: the app's name alone. */
    @Test
    fun aReleasePlayedWithoutTmdbGoesAsTheAppAloneWithNoPicture() {
        val item = sent(CastMetadata.of(bareRelease, APP, TvArt("/still-hl-s02e04.jpg"), showArtwork = true))
        val media = item.media ?: throw AssertionError("no media")
        assertEquals(APP, media.metadata?.getString(MediaMetadata.KEY_TITLE))
        assertNull(media.metadata?.getString(MediaMetadata.KEY_SUBTITLE))
        assertEquals(emptyList<String>(), media.images())
        val json = item.toJson().toString()
        assertNothingOfTheRelease(json)
        assertFalse("the parsed name in what the device is sent", "Harbor Lights S02E04" in json)
        assertFalse("the parsed line in what the device is sent", "1080p" in json)
    }

    /**
     * W62, L120: the device's name is for this phone's notification and lock screen, on the session's item alone; what the
     * device is told, which every sender on the network reads, keeps the play's own words.
     */
    @Test
    fun theDeviceIsNeverToldItsOwnName() {
        val item = CastItems.item(url, "video/x-matroska", CastMetadata.of(episode, APP), artwork = null, DEVICE, Build.VERSION.SDK_INT)
        assertTrue("the session's line names the device", DEVICE in item.mediaMetadata.artist.toString())
        val sent = CastItems.toMediaQueueItem(item)
        val metadata = sent.media?.metadata ?: throw AssertionError("no metadata")
        assertEquals("Harbor Lights", metadata.getString(MediaMetadata.KEY_TITLE))
        assertEquals("S02E04 · Low Water", metadata.getString(MediaMetadata.KEY_SUBTITLE))
        assertFalse("the device's name in what the device is sent", DEVICE in sent.toJson().toString())
    }

    @Test
    fun nothingOfGooglesIsStartedForIt() {
        sent(CastMetadata.of(film, APP))
        assertTrue("Google's Cast software unstarted", onMainThread { Cast.getSingletonInstance(context).needsInitialization() })
    }

    private companion object {
        const val APP = "NZStreamer"
        const val DEVICE = "Kitchen display"
    }
}
