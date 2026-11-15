package io.skjaere.usenet.app.download

import io.skjaere.usenet.data.DownloadPrefs
import io.skjaere.usenet.data.PlaybackPrefs
import io.skjaere.usenet.releases.DeviceCapabilities
import io.skjaere.usenet.releases.DownloadSize
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.RankingTarget

/**
 * What a download's releases are ranked by (W112, L355, L371): this [device] as it is now, a play's quality and audio
 * languages ([playback]), and Download size ([downloads]), for [RankingTarget.Download]. Never Maximum bitrate or the
 * maximum when casting, which are about streaming, and never a TV's rules: downloads run on phones and tablets alone
 * (W108). Nor a Cast device's, whatever Play on chose: a download is for this device.
 */
object DownloadRanking {
    fun context(device: DeviceCapabilities, playback: PlaybackPrefs, downloads: DownloadPrefs, deviceLanguage: String): RankingContext = RankingContext(
        device = device,
        prefs = playback.rankingPrefs(deviceLanguage).copy(
            maxBitrate = null,
            castMaxBitrate = null,
            // A size that can't be one (an edited file) reads as not set, as Maximum bitrate's does.
            downloadCapBitsPerSecond = downloads.sizeCapGbPerHour?.takeIf { it > 0 }?.let(DownloadSize::bitsPerSecond),
        ),
        tv = false,
        target = RankingTarget.Download,
    )
}
