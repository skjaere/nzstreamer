package io.skjaere.usenet.app.download

import io.skjaere.usenet.data.DownloadPrefs
import io.skjaere.usenet.data.PlaybackPrefs
import io.skjaere.usenet.releases.DownloadSize
import io.skjaere.usenet.releases.QualityPreference
import io.skjaere.usenet.releases.RankingPrefs
import io.skjaere.usenet.releases.RankingTarget
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * What a download's releases are ranked by (L355, L371): this device as it is, a play's quality and languages, and Download
 * size in the ranker's bits a second; never Maximum bitrate or the maximum when casting, never a TV's rules.
 */
class DownloadRankingTest {
    private val phone = StaticDeviceCapabilities(displayHeight = 1080)

    @Test
    fun `a download ranks for this device by a play's quality and languages and by Download size, never by a maximum bitrate`() {
        val playback = PlaybackPrefs(quality = QualityPreference.P1080.name, audioLanguages = listOf("de", "en"), maxBitrateMbps = 40, castMaxBitrateMbps = 20)
        val context = DownloadRanking.context(phone, playback, DownloadPrefs(sizeCapGbPerHour = 4), deviceLanguage = "nb")
        assertEquals(RankingTarget.Download, context.target)
        assertEquals(phone, context.device)
        assertFalse(context.tv)
        assertEquals(RankingPrefs(QualityPreference.P1080, listOf("de", "en"), downloadCapBitsPerSecond = DownloadSize.bitsPerSecond(4)), context.prefs)
    }

    @Test
    fun `with no Download size set, or one that can't be a size, nothing is held back`() {
        listOf(null, 0, -4).forEach { cap ->
            assertNull(DownloadRanking.context(phone, PlaybackPrefs(), DownloadPrefs(sizeCapGbPerHour = cap), "en").prefs.downloadCapBitsPerSecond, "$cap")
        }
        // The audio languages not chosen: the device's, then English, as a play's.
        assertEquals(listOf("nb", "en"), DownloadRanking.context(phone, PlaybackPrefs(), DownloadPrefs(), "nb").prefs.audioLanguages)
    }
}
