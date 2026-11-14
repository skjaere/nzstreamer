package io.skjaere.usenet.data

import io.skjaere.usenet.releases.QualityPreference
import io.skjaere.usenet.releases.RankingPrefs
import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    /** The file's version: 2 since the services' order is their place in [services] ([ServicesInOrder]); a new file's. */
    val schemaVersion: Int = 2,
    val indexers: List<IndexerRecord> = emptyList(),
    /**
     * Torrent indexers (Torznab: Prowlarr's or Jackett's), a list apart from [indexers], so what reads the Usenet
     * indexers never counts these (L194). Searched only while a debrid service account is on.
     */
    val torrentIndexers: List<TorrentIndexerRecord> = emptyList(),
    /**
     * The user's own debrid service accounts (L196), at most one active per service, in the user's order (first is tried
     * first, L228). Their keys are in [SecretStore], never here, so never in a device transfer or a backup.
     */
    val services: List<ServiceAccount> = emptyList(),
    /** Usenet providers in priority order (first is tried first). Device-specific, never synced. */
    val providers: List<ProviderRecord> = emptyList(),
    val playback: PlaybackPrefs = PlaybackPrefs(),
    val metadata: MetadataPrefs = MetadataPrefs(),
    val cache: CachePrefs = CachePrefs(),
    val logs: LogPrefs = LogPrefs(),
    /** "Not now" on the first-run screen: Home opens without an indexer until one is added. */
    val indexerSetupSkipped: Boolean = false,
    val collectionView: CollectionViewPrefs = CollectionViewPrefs(),
    val cast: CastPrefs = CastPrefs(),
) {
    val activeIndexers: List<IndexerRecord> get() = indexers.filterNot { it.deleted }
    val activeTorrentIndexers: List<TorrentIndexerRecord> get() = torrentIndexers.filterNot { it.deleted }
    val activeServices: List<ServiceAccount> get() = services.filterNot { it.deleted }
    val activeProviders: List<ProviderRecord> get() = providers.filterNot { it.deleted }
}

/** An indexer without its API key, which is in [SecretStore] under [apiKeySecret]. */
@Serializable
data class IndexerRecord(
    val id: String,
    val name: String,
    val baseUrl: String,
    val enabled: Boolean = true,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
) {
    val apiKeySecret: String get() = "indexer:$id:apikey"
}

/**
 * A torrent indexer without its API key, which is in [SecretStore] under [apiKeySecret]. [useWithService] off ("Use with
 * your debrid service", for private trackers whose rules forbid debrid services): still searched, but nothing of its
 * results goes to any service.
 */
@Serializable
data class TorrentIndexerRecord(
    val id: String,
    val name: String,
    val baseUrl: String,
    val enabled: Boolean = true,
    val useWithService: Boolean = true,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
) {
    val apiKeySecret: String get() = "torrentIndexer:$id:apikey"
}

/**
 * A debrid service account without its API key, which is in [SecretStore] under [apiKeySecret] (L196). [kind] is the
 * service's name (a ServiceKind's), kept as a name so one only a newer version knows reads, as an account this one can't
 * use. [accountName], [plan] and [planUntil] (milliseconds since the epoch) are what the service said whose account the key
 * opens, the last time it was asked; blank where it said nothing. [keyRefused]: the service has refused the saved key
 * since, so nothing is asked with it until a new one is saved, or the service takes it again on the account's page's
 * Test. Signing out leaves a tombstone ([deleted]) without them.
 *
 * [removeAfterPlay] ("Remove after playing", L246, W83): whether this device removes from the account what its plays
 * added there, a couple of minutes after they stop; on unless switched off, for an account saved before it existed too.
 * [keySavedAt]: when the key was last saved as a different one (milliseconds; 0 for an account saved before it was kept),
 * which each record of an add keeps, as a new key may open another account (L232).
 */
@Serializable
data class ServiceAccount(
    val id: String,
    val kind: String,
    val enabled: Boolean = true,
    val accountName: String = "",
    val plan: String? = null,
    val planUntil: Long? = null,
    val keyRefused: Boolean = false,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
    val removeAfterPlay: Boolean = true,
    val keySavedAt: Long = 0,
) {
    val apiKeySecret: String get() = "service:$id:apikey"

    // The account's name is the user's own (an e-mail address): never in a log line (L208).
    override fun toString() =
        "ServiceAccount(id=$id, kind=$kind, enabled=$enabled, plan=$plan, planUntil=$planUntil, keyRefused=$keyRefused, updatedAt=$updatedAt, " +
            "deleted=$deleted, removeAfterPlay=$removeAfterPlay, keySavedAt=$keySavedAt)"
}

/** A Usenet (NNTP) provider without its password, which is in [SecretStore] under [passwordSecret]. */
@Serializable
data class ProviderRecord(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 563,
    val username: String = "",
    val useTls: Boolean = true,
    val connections: Int = 8,
    val enabled: Boolean = true,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
) {
    val passwordSecret: String get() = "provider:$id:password"
}

@Serializable
data class PlaybackPrefs(
    /** A [QualityPreference] name. */
    val quality: String = QualityPreference.Auto.name,
    /** ISO 639-1, most preferred first. Empty means the device language, then English. */
    val audioLanguages: List<String> = emptyList(),
    val subtitlesWhenForeign: Boolean = true,
    val autoPlayNext: Boolean = true,
    /** STAT every article before playing instead of a sample: slower start, no surprise gaps. */
    val checkAllArticles: Boolean = false,
    /** Ask TheIntroDB for an episode's intro, recap and credits times (sends its TMDB id, season and episode, and the file's length). Opt-in. */
    val lookUpSegmentsOnline: Boolean = false,
    /** How the player sized the picture last, by its aspect button or a pinch: an [AspectMode] name. */
    val aspectMode: String = AspectMode.Fit.name,
    /** With [AspectMode.Zoom], the pinch's scale as a multiple of Fit's. */
    val zoomScale: Float = 1f,
    /**
     * How many releases a play tries before it gives up, from either source (L205): each Usenet one
     * an NZB download that counts against the indexer's allowance, each torrent calls to the user's
     * debrid service rather than a grab; one of [RELEASES_TO_TRY] (read through [attempts]). 10 on a new install, and in
     * a settings file without the field; one that has it keeps its value, as every field is written (defaults too), so an
     * install that saved its settings while the default was 3 keeps 3 until another is chosen.
     */
    val releasesToTry: Int = 10,
    /**
     * "Maximum bitrate": the speed this device can stream at, in Mbps, one of [MAX_BITRATES_MBPS]; null when not set.
     * Releases estimated above it are tried last ([RankingPrefs.maxBitrate]).
     */
    val maxBitrateMbps: Int? = null,
    /**
     * "Maximum bitrate when casting" (W52, L132): what this phone's Wi-Fi carries to a Cast device, in Mbps, one of
     * [MAX_BITRATES_MBPS]; null when not set. A cast's video crosses the Wi-Fi about three times, so it is a setting of its
     * own, read only while casting ([RankingPrefs.castMaxBitrate]), where [maxBitrateMbps] counts too.
     */
    val castMaxBitrateMbps: Int? = null,
    /**
     * "Match the TV to the video's frame rate": before a play the TV switches to a mode that shows the video's rate (TVs
     * only, the player's). Opt-in, as the screen goes dark for a second or two while it switches.
     */
    val matchFrameRate: Boolean = false,
    val updatedAt: Long = 0,
) {
    /** [maxBitrateMbps] in bits per second; a value that can't be a speed (an edited file) reads as not set. */
    val maxBitrate: Long?
        get() = maxBitrateMbps?.takeIf { it > 0 }?.let { it * 1_000_000L }

    /** [castMaxBitrateMbps] in bits per second, read as [maxBitrate] is. */
    val castMaxBitrate: Long?
        get() = castMaxBitrateMbps?.takeIf { it > 0 }?.let { it * 1_000_000L }

    /** What ranking releases takes from these preferences, with [deviceLanguage] for the audio languages not chosen. */
    fun rankingPrefs(deviceLanguage: String): RankingPrefs =
        RankingPrefs(qualityPreference, effectiveAudioLanguages(deviceLanguage), maxBitrate = maxBitrate, castMaxBitrate = castMaxBitrate)

    val qualityPreference: QualityPreference
        get() = QualityPreference.entries.firstOrNull { it.name == quality } ?: QualityPreference.Auto

    /** [releasesToTry], held to the choices' range for a value no version offers. */
    val attempts: Int
        get() = releasesToTry.coerceIn(RELEASES_TO_TRY.first(), RELEASES_TO_TRY.last())

    /** Fit for a mode this version doesn't know: one a newer version saved, or Stretch, which earlier versions offered. */
    val aspect: AspectMode
        get() = AspectMode.entries.firstOrNull { it.name == aspectMode } ?: AspectMode.Fit

    fun effectiveAudioLanguages(deviceLanguage: String): List<String> =
        audioLanguages.ifEmpty { listOf(deviceLanguage, "en").distinct() }

    companion object {
        /** The counts Settings offers for [releasesToTry], in order. */
        val RELEASES_TO_TRY = listOf(1, 3, 5, 10)

        /**
         * The maximum bitrates Settings offers, in Mbps, besides Not set: from a slow line's (a phone on mobile data: 5, W4)
         * to a UHD disc copy's estimate (W102, L309). Above 200 a maximum would act as Not set, so the list ends there.
         */
        val MAX_BITRATES_MBPS = listOf(5, 10, 15, 20, 30, 40, 60, 80, 100, 150, 200)
    }
}

/**
 * How the player sizes the picture to the screen: [Fit] shows all of it, with bars where its shape
 * isn't the screen's; [Fill] fills the screen, cropping what doesn't fit; [FillBesideCamera] fills
 * it but for a strip along the edge the front camera's display cutout is on (as [Fill] where there
 * is none); [Zoom] is a pinch's own scale. None changes the picture's shape.
 */
enum class AspectMode { Fit, Fill, FillBesideCamera, Zoom }

/**
 * Edits of [PlaybackPrefs.audioLanguages] from the settings screen. They name the language, not its row:
 * they run on the stored list, which an earlier tap may have changed since the rows were drawn.
 */
object AudioLanguages {
    /** One place up from where [code] is now; nothing when it's first or no longer listed. */
    fun moveUp(languages: List<String>, code: String): List<String> {
        val i = languages.indexOf(code)
        return if (i > 0) languages.toMutableList().apply { add(i - 1, removeAt(i)) } else languages
    }

    /** Keeps at least one language: an empty list would silently fall back to the defaults. */
    fun remove(languages: List<String>, code: String): List<String> = (languages - code).ifEmpty { languages }
}

/** Settings › Playback › Cache: the video segments played, kept on this device (in its cache directory). */
@Serializable
data class CachePrefs(
    /** Keep watched video, so a seek back, a replay or a resume reads it from the device rather than the provider. Turning it off clears it. */
    val keepVideo: Boolean = true,
    /** At most this many GB (10^9 bytes), one of [LIMITS_GB]; less when the device hasn't the room. */
    val limitGb: Int = 2,
    val updatedAt: Long = 0,
) {
    /** The limit in bytes; one below the smallest choice (an edited file) reads as the smallest. */
    val limitBytes: Long
        get() = limitGb.coerceAtLeast(LIMITS_GB.first()) * GB

    companion object {
        /** The limits the Cache screen offers. */
        val LIMITS_GB = listOf(1, 2, 5, 10)
        const val GB = 1_000_000_000L
    }
}

/** Settings › App › Logs. */
@Serializable
data class LogPrefs(
    /** "Detailed logging": debug lines too, in logcat and the in-app log. Null until chosen: the build's default. */
    val detailed: Boolean? = null,
    val updatedAt: Long = 0,
) {
    /** Whether to log in detail: as chosen, else in debug builds only. */
    fun isDetailed(debugBuild: Boolean): Boolean = detailed ?: debugBuild
}

@Serializable
data class MetadataPrefs(
    /** BCP 47 tag for TMDB results; null follows the device. */
    val language: String? = null,
    val showArtwork: Boolean = true,
    val updatedAt: Long = 0,
)

/**
 * Google Cast on this phone (W46, L112), kept on this device alone, never synced (a transfer to a new phone copies it, as
 * any setting). [look]: "Look for Cast devices" was chosen, so the Play on list starts Google's Cast software as it opens;
 * until then nothing of Google's starts. [introShown]: the introduction to the Cast button was shown, so it never shows
 * again (W48). [devices]: what each Cast device has shown it plays (L128), which the ranking for a cast reads, for at
 * most [MAX_DEVICES] devices.
 */
@Serializable
data class CastPrefs(
    val look: Boolean = false,
    val introShown: Boolean = false,
    val devices: List<CastDeviceMemory> = emptyList(),
    val updatedAt: Long = 0,
) {
    /** These prefs with [device] in place of what was kept of it, the device seen longest ago dropped past [MAX_DEVICES]. */
    fun remembering(device: CastDeviceMemory): CastPrefs =
        // First among its equals, so one written at the same moment as the oldest still goes in.
        copy(devices = (listOf(device) + devices.filterNot { it.id == device.id }).sortedByDescending { it.seenAt }.take(MAX_DEVICES))

    companion object {
        /** How many Cast devices' facts are kept (L128). */
        const val MAX_DEVICES = 20
    }
}

/**
 * What the Cast device [id] (its Cast device id) has shown it plays, from its media status as ours played (L128): it
 * reports the picture's size at all ([reportsVideo]); it showed a 2160-line picture of these videos ([shown2160]); it
 * showed an HDR file as SDR ([hdrSdr]); it showed Dolby Vision ([dolbyVision]); it played these videos with no picture
 * ([noPicture], learned only on a device that [reportsVideo], so one that never says is never marked wrong). [seenAt]:
 * when it last taught something, in milliseconds since the epoch, so the one seen longest ago goes first. Nothing about
 * its sound: a device's silence is never an input (W41).
 */
@Serializable
data class CastDeviceMemory(
    val id: String,
    val reportsVideo: Boolean = false,
    val shown2160: List<CastVideoCodec> = emptyList(),
    val hdrSdr: Boolean = false,
    val dolbyVision: Boolean = false,
    val noPicture: List<CastVideoCodec> = emptyList(),
    val seenAt: Long = 0,
)

/**
 * A video as a Cast device's facts name it: its [codec] by a VideoCodec's name, and whether it is [tenBit]. A name, not
 * the enum: one only a newer version knows reads in this one, where an unknown enum would make the whole file unreadable.
 */
@Serializable
data class CastVideoCodec(val codec: String, val tenBit: Boolean = false)

/**
 * Collection › Shows or Movies, and each side's filter and sort, as last chosen. Names rather than
 * enums: a name only a newer version knows reads as the default in the app, where an unknown enum
 * value would make the whole file unreadable and reset every setting. Per device, never synced.
 */
@Serializable
data class CollectionViewPrefs(
    /** "Shows" or "Movies". */
    val side: String = "Shows",
    val showFilter: String = "All",
    val showSort: String = "RecentlyWatched",
    val movieFilter: String = "All",
    val movieSort: String = "RecentlyAdded",
    val updatedAt: Long = 0,
)
