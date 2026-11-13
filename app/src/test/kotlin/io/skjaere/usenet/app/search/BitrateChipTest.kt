package io.skjaere.usenet.app.search

import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.RankingPrefs
import io.skjaere.usenet.releases.RankingTarget
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.releases.ReleaseReason
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGrouper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The chip after a release's size: the ranker's own estimate of its bitrate, "≈ 12 Mbps", with one decimal under 10, and
 * none where the ranker has none. Beside the maximum bitrate's "Above your N Mbps", or the maximum when casting's "Above
 * your N Mbps for casting", it never reads N or under. Invented release names.
 */
class BitrateChipTest {
    private val saved = Locale.getDefault()

    // The chips follow the default locale, as the size does; pin one so the decimal separator is predictable.
    @BeforeEach
    fun pin() = Locale.setDefault(Locale.US)

    @AfterEach
    fun unpin() = Locale.setDefault(saved)

    private val phone = StaticDeviceCapabilities(displayHeight = 1080)

    private fun item(title: String, size: Long) = NewznabItem(
        indexerId = "chips", guid = title, title = title, sizeBytes = size, publishedAt = null, postedAt = null,
        categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
        tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
    )

    /** [releases] (names and sizes) as Search groups and ranks them for a picked show of [runtime]-minute episodes. */
    private fun ranked(context: RankingContext, runtime: Int?, vararg releases: Pair<String, Long>): Map<String, RankedItem> =
        ResultGrouper().group(releases.map { (title, size) -> item(title, size) }, context, ResultGrouper.KnownTitle("Harbor Lights", 2021, isTv = true, runtimeMinutes = runtime))
            .sections.flatMap { s -> s.groups.flatMap { it.playable + it.hidden } }.associateBy { it.item.title }

    /** The chip for an estimate of [bits], with [reasons] beside it. */
    private fun chip(bits: Long?, vararg reasons: ReleaseReason): String? {
        val title = "Harbor.Lights.S01E01.1080p.WEB-DL.H.264-NZST"
        return bitrateChip(RankedRelease(ReleaseFacts(title, title, 1), ReleaseParser.parse(title), playable = true, score = 0.0, reasons = reasons.toList(), estimatedBitrate = bits))
    }

    @Test
    fun `the estimate reads in Mbps after an approximately sign, with one decimal under 10 and whole from there`() {
        assertEquals("≈ 12 Mbps", chip(12_000_000))
        assertEquals("≈ 8.4 Mbps", chip(8_400_000))
        assertEquals("≈ 1.0 Mbps", chip(1_000_000))
        assertEquals("≈ 0.2 Mbps", chip(150_000))
        // Rounded half up, as the ranker's "May buffer: ~N Mbps" and "Above your N Mbps" are.
        assertEquals("≈ 8.4 Mbps", chip(8_449_999))
        assertEquals("≈ 8.5 Mbps", chip(8_450_000))
        assertEquals("≈ 9.9 Mbps", chip(9_949_999))
        // Where one decimal would read 10.0, it is whole.
        assertEquals("≈ 10 Mbps", chip(9_950_000))
        assertEquals("≈ 12 Mbps", chip(12_499_999))
        assertEquals("≈ 13 Mbps", chip(12_500_000))
        assertEquals("≈ 100 Mbps", chip(99_600_000))
        // Too little to read as a tenth (a tiny test clip), rather than "≈ 0.0".
        assertEquals("< 0.1 Mbps", chip(40_000))
    }

    @Test
    fun `no estimate, no chip`() {
        assertNull(chip(null))
        assertNull(chip(0))
    }

    /**
     * At most 20 Mbps, episodes of 60 minutes: 20.3 Mbps would round to 20 beside "Above your 20 Mbps", so it rounds up; one
     * that already reads above it keeps the nearest, as "May buffer" has it; one within the maximum may read 20.
     */
    @Test
    fun `beside the maximum bitrate's warning the chip never reads the maximum or under`() {
        val upTo20 = RankingContext(phone, RankingPrefs(maxBitrate = 20_000_000), throughputBitsPerSecond = 40_000_000)
        // Bits per second over an hour, in bytes.
        fun size(bits: Long) = bits * 3600 / 8
        val items = ranked(
            upTo20, 60,
            "Harbor.Lights.S01E01.1080p.WEB-DL.H.264-JUST" to size(20_300_000),
            "Harbor.Lights.S01E01.1080p.BluRay.x264-HALF" to size(20_600_000),
            "Harbor.Lights.S01E01.2160p.WEB-DL.H.264-FAR" to size(35_200_000),
            "Harbor.Lights.S01E01.720p.WEB-DL.H.264-UNDER" to size(19_700_000),
        )
        fun chipsOf(title: String) = items.getValue(title).let { listOfNotNull(bitrateChip(it.ranked)) + it.ranked.reasons.filter { r -> r is ReleaseReason.AboveMaxBitrate || r is ReleaseReason.MayBuffer }.map { r -> r.text } }
        assertEquals(listOf("≈ 21 Mbps", "Above your 20 Mbps"), chipsOf("Harbor.Lights.S01E01.1080p.WEB-DL.H.264-JUST"))
        assertEquals(listOf("≈ 21 Mbps", "Above your 20 Mbps"), chipsOf("Harbor.Lights.S01E01.1080p.BluRay.x264-HALF"))
        assertEquals(listOf("≈ 35 Mbps", "May buffer: ~35 Mbps", "Above your 20 Mbps"), chipsOf("Harbor.Lights.S01E01.2160p.WEB-DL.H.264-FAR"))
        assertEquals(listOf("≈ 20 Mbps"), chipsOf("Harbor.Lights.S01E01.720p.WEB-DL.H.264-UNDER"))
    }

    /**
     * W52, L132: beside "Above your 40 Mbps for casting" the chip never reads 40 or under either: 40.3 Mbps reads "≈ 41 Mbps",
     * also beside both maximums' chips; as a list ranked for a Cast device with 40 set for casting draws it, beside its
     * warning (CsPhoneRanked: "≈ 44 Mbps", "⚠ Above your 40 Mbps for casting").
     */
    @Test
    fun `beside the maximum when casting's warning the chip never reads the maximum or under`() {
        assertEquals("≈ 41 Mbps", chip(40_300_000, ReleaseReason.AboveCastMax(40)))
        assertEquals("≈ 41 Mbps", chip(40_300_000, ReleaseReason.AboveMaxBitrate(30), ReleaseReason.AboveCastMax(40)))
        assertEquals("≈ 41 Mbps", chip(40_300_000, ReleaseReason.AboveMaxBitrate(40), ReleaseReason.AboveCastMax(30)))
        // Already reading above it: the nearest.
        assertEquals("≈ 41 Mbps", chip(40_600_000, ReleaseReason.AboveCastMax(40)))

        val kitchen = RankingContext(phone, RankingPrefs(castMaxBitrate = 40_000_000), target = RankingTarget.Cast("Kitchen display"))
        // Bits per second over an hour, in bytes.
        fun size(bits: Long) = bits * 3600 / 8
        val items = ranked(
            kitchen, 60,
            "Harbor.Lights.S01E01.1080p.WEB-DL.H.264-JUST" to size(40_300_000),
            "Harbor.Lights.S01E01.1080p.BluRay.REMUX.AVC-FAR" to size(44_000_000),
            "Harbor.Lights.S01E01.720p.WEB-DL.H.264-UNDER" to size(19_700_000),
        )
        fun bitrateChips(title: String) = reasonChips(items.getValue(title), bitrate = true).map { it.text }.filter { "Mbps" in it }
        assertEquals(listOf("≈ 41 Mbps", "⚠ Above your 40 Mbps for casting"), bitrateChips("Harbor.Lights.S01E01.1080p.WEB-DL.H.264-JUST"))
        assertEquals(listOf("≈ 44 Mbps", "⚠ Above your 40 Mbps for casting"), bitrateChips("Harbor.Lights.S01E01.1080p.BluRay.REMUX.AVC-FAR"))
        assertEquals(listOf("≈ 20 Mbps"), bitrateChips("Harbor.Lights.S01E01.720p.WEB-DL.H.264-UNDER"))
    }

    /**
     * The chip is the ranker's figure for the ranking the list was made with: the same 2.7 GB episode is 6 Mbps over the
     * show's 60-minute episodes, 8 over the 45 minutes taken when the runtime isn't known. A season pack's is per episode,
     * over the season's episodes when TMDB has their count, and there is none without it.
     */
    @Test
    fun `the chip after the size is the ranked estimate, from the ranking the list was made with`() {
        val episode = "Harbor.Lights.S01E01.1080p.WEB-DL.H.264-NZST" to 2_700_000_000L
        val known = ranked(RankingContext(phone), 60, episode).getValue(episode.first)
        assertEquals(6_000_000, known.ranked.estimatedBitrate)
        assertEquals(listOf("1080p", "WEB-DL", "2.7 GB", "≈ 6.0 Mbps"), summaryChips(known))
        val unknown = ranked(RankingContext(phone), null, episode).getValue(episode.first)
        assertEquals(8_000_000, unknown.ranked.estimatedBitrate)
        assertEquals(listOf("1080p", "WEB-DL", "2.7 GB", "≈ 8.0 Mbps"), summaryChips(unknown))

        val pack = "Harbor.Lights.S01.1080p.WEB-DL.H.264-NZST" to 30_000_000_000L
        // 30 GB over 10 episodes of 45 minutes: 3 GB an episode, about 8.9 Mbps.
        val counted = ranked(RankingContext(phone, seasonEpisodes = 10), 45, pack).getValue(pack.first)
        assertEquals(listOf("1080p", "WEB-DL", "30.0 GB", "≈ 8.9 Mbps"), summaryChips(counted))
        val uncounted = ranked(RankingContext(phone), 45, pack).getValue(pack.first)
        assertNull(uncounted.ranked.estimatedBitrate)
        assertEquals(listOf("1080p", "WEB-DL", "30.0 GB"), summaryChips(uncounted))
    }
}
