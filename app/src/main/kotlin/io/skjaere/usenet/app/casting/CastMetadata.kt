package io.skjaere.usenet.app.casting

import androidx.media3.common.MediaMetadata
import io.skjaere.usenet.app.link.TvArt
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SessionMetadata
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.metadata.TmdbImages

/**
 * What a cast play is said as (L62, L89): to the Cast device, which shows it and tells every sender on the network what
 * plays, and to the phone's cast session once the device has it (L63). A play of TMDB's by its launch's [title] and
 * [subtitle] ("Harbor Lights" over "S02E04 · Low Water"), an episode as a show's, a film as a film ([kind]); any other by
 * the app's name alone, as a video, as [SessionMetadata] says it, for its title is a release's name. Never the stream's
 * URI, a file's name or a release's. TMDB's still or poster ([imageUrl]) only while "Show artwork" is on: the device
 * fetches it from TMDB itself. It prints without its words.
 */
data class CastMetadata(val title: String, val subtitle: String?, val kind: Kind, val imageUrl: String?) {
    enum class Kind { Episode, Film, Video }

    val episode: Boolean get() = kind == Kind.Episode

    /** Media3's type for it, as the phone's sessions say it: a show's, a film's or a video's. */
    val mediaType: Int get() = when (kind) {
        Kind.Episode -> MediaMetadata.MEDIA_TYPE_TV_SHOW
        Kind.Film -> MediaMetadata.MEDIA_TYPE_MOVIE
        Kind.Video -> MediaMetadata.MEDIA_TYPE_VIDEO
    }

    /**
     * As the phone's cast session says it (L63): the same words, a show's, a film's or a video's, with the still decoded
     * for its notification ([artwork], L58), never the picture's address: the session's own loader takes no URI. The line
     * under the title, which is its artist too, what Android's notification, media card and lock screen show there (L86;
     * CsPhoneNotification, CsPhoneLockScreen), names the [device] it plays on, as [line] puts it on Android [sdkInt] (W62,
     * L120; CsPhoneNotificationC2).
     */
    fun session(artwork: ByteArray?, device: String, sdkInt: Int): MediaMetadata {
        val line = line(subtitle, device, sdkInt)
        return MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(line)
            .setArtist(line)
            .setMediaType(mediaType)
            .apply { if (artwork != null) setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
            .build()
    }

    override fun toString() = "CastMetadata($kind, picture=${imageUrl != null})"

    companion object {
        /** A poster as wide as the remote shows it; a still at TMDB's widest size short of its original. */
        private const val POSTER_PX = 500

        /** Android 11, from which the media card's output chip names where the session plays. */
        private const val CHIP_SDK = 30

        /**
         * The line under a cast play's title on the phone (W62, L120): its own [subtitle], if any, and the [device] it plays
         * on. From Android 11 on, where the output chip names the device too, after it ("S02E04 · Low Water · on Kitchen
         * display"); up to Android 10, which has no chip and whose shade cuts the line's end, first ("On Kitchen display ·
         * S02E04 · Low Water"). Never sent to the device, whose words every sender on the network reads.
         */
        fun line(subtitle: String?, device: String, sdkInt: Int): String =
            if (sdkInt >= CHIP_SDK) listOfNotNull(subtitle, "on $device").joinToString(LINE_DOT) else listOfNotNull("On $device", subtitle).joinToString(LINE_DOT)

        private const val LINE_DOT = " · "

        /**
         * [launch] as the device and the session say it; [appName] for a play without TMDB. [art]: what this phone has of
         * TMDB's for it, sent while [showArtwork].
         */
        fun of(launch: PlayerLaunch, appName: String, art: TvArt? = null, showArtwork: Boolean = false): CastMetadata {
            val said = starting(launch.title, launch.subtitle, launch.watch, appName)
            if (said.kind == Kind.Video) return said
            val image = art?.takeIf { showArtwork }?.let { if (it.poster) TmdbImages.poster(it.path, POSTER_PX) else TmdbImages.still(it.path) }
            return said.copy(imageUrl = image)
        }

        /**
         * A cast play still starting, before it has a launch (L63): the play asked for [watch], named [title] and
         * [subtitle], said as [of] will say its launch, with no picture yet; [appName] alone for a play without TMDB, whose
         * title is a release's name.
         */
        fun starting(title: String, subtitle: String?, watch: WatchTarget, appName: String): CastMetadata {
            if (watch.rememberedKey == null) return CastMetadata(appName, subtitle = null, Kind.Video, imageUrl = null)
            return CastMetadata(title, subtitle, if (watch.kind == WatchKind.Episode) Kind.Episode else Kind.Film, imageUrl = null)
        }
    }
}
