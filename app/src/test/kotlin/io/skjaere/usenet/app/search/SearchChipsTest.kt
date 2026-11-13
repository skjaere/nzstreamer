package io.skjaere.usenet.app.search

import io.skjaere.usenet.app.ui.ChipKind
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.Availability
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.RankingPrefs
import io.skjaere.usenet.releases.RankingTarget
import io.skjaere.usenet.releases.ReleaseReason
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import io.skjaere.usenet.releases.VideoCodec
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGrouper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The chips Search and the release lists draw for a release, by its ranking (L126): ranked for this phone, the best
 * release's "✓ Plays"; ranked for a Cast device, whose player the phone can't check, none, and what it can't play as
 * warnings in its name, "2160p: Den TV hasn’t played 4K yet" plain. Invented release and device names.
 */
class SearchChipsTest {
    private val saved = Locale.getDefault()

    // The chips follow the default locale, as the size does; pin one so the decimal separator is predictable.
    @BeforeEach
    fun pin() = Locale.setDefault(Locale.US)

    @AfterEach
    fun unpin() = Locale.setDefault(saved)

    private val phone = StaticDeviceCapabilities(displayHeight = 1080)

    /** A Cast device's profile as the app gives one: no DTS or TrueHD, no VC-1, 1080p until it has shown 4K. */
    private val castProfile = StaticDeviceCapabilities(
        displayHeight = 1080,
        video = setOf(VideoCodec.H264, VideoCodec.Hevc, VideoCodec.Vp9),
        videoTenBit = setOf(VideoCodec.Hevc, VideoCodec.Vp9),
    )
    private val toKitchen = RankingContext(castProfile, target = RankingTarget.Cast("Kitchen display"))
    private val toDen = RankingContext(castProfile, target = RankingTarget.Cast("Den TV"))

    private fun item(title: String, size: Long) = NewznabItem(
        indexerId = "chips", guid = title, title = title, sizeBytes = size, publishedAt = null, postedAt = null,
        categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
        tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
    )

    /** [title]'s release as Search ranks it for Lantern Field, a film of 118 minutes, with [context]. */
    private fun ranked(context: RankingContext, title: String, size: Long = 6_200_000_000): RankedItem =
        ResultGrouper().group(listOf(item(title, size)), context, ResultGrouper.KnownTitle("Lantern Field", 2008, isTv = false, runtimeMinutes = 118))
            .sections.flatMap { s -> s.groups.flatMap { it.playable + it.hidden } }.single()

    private val plays = ChipLook("✓ Plays", ChipKind.Good)

    @Test
    fun `ranked for this phone, a search group's best release and a film's hero card say it plays`() {
        val best = ranked(RankingContext(phone), "Lantern.Field.2008.1080p.WEB-DL.DDP5.1.H.264-KESTREL")
        assertEquals(listOf(ChipLook("1080p"), ChipLook("WEB-DL"), ChipLook("6.2 GB"), ChipLook("≈ 7.0 Mbps"), plays), groupChips(best))
        assertEquals(listOf(ChipLook("1080p"), ChipLook("WEB-DL"), ChipLook("6.2 GB"), ChipLook("≈ 7.0 Mbps"), plays), heroChips(best))
        // A release that may buffer says so instead, on the group's row.
        val slow = ranked(RankingContext(phone, throughputBitsPerSecond = 5_000_000), "Lantern.Field.2008.1080p.WEB-DL.DDP5.1.H.264-KESTREL")
        assertEquals(ChipLook("⚠ May buffer", ChipKind.Warn), groupChips(slow).last())
        assertFalse(plays in groupChips(slow))
    }

    /** L126: the phone can't check the Cast device's player, so Search's "✓ Plays" goes too (CsPhoneRanked). */
    @Test
    fun `ranked for a Cast device, nothing says the best release plays`() {
        val best = ranked(toKitchen, "Lantern.Field.2008.1080p.WEB-DL.DDP5.1.H.264-KESTREL")
        assertEquals(listOf(ChipLook("1080p"), ChipLook("WEB-DL"), ChipLook("6.2 GB"), ChipLook("≈ 7.0 Mbps")), groupChips(best))
        assertEquals(listOf(ChipLook("1080p"), ChipLook("WEB-DL"), ChipLook("6.2 GB"), ChipLook("≈ 7.0 Mbps")), heroChips(best))
        assertTrue(reasonChips(best, bitrate = true).none { it.text.startsWith("✓") }, "${reasonChips(best, bitrate = true)}")
        // "⚠ May buffer" still says so for a cast.
        val slow = ranked(toKitchen.copy(throughputBitsPerSecond = 5_000_000), "Lantern.Field.2008.1080p.WEB-DL.DDP5.1.H.264-KESTREL")
        assertEquals(ChipLook("⚠ May buffer", ChipKind.Warn), groupChips(slow).last())
    }

    @Test
    fun `a release list's chips for a Cast device draw what it can't play as warnings, and its 4K chip plain`() {
        val dts = ranked(toKitchen, "Lantern.Field.2008.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-FERRY", 31_800_000_000)
        assertEquals(
            listOf(ChipLook("≈ 36 Mbps"), ChipLook("⚠ DTS-HD MA audio: no sound on Kitchen display", ChipKind.Warn, "Warning: DTS-HD MA audio, no sound on Kitchen display"), ChipLook("DTS-HD MA 5.1")),
            reasonChips(dts, bitrate = true),
        )
        val vc1 = ranked(toKitchen, "Lantern.Field.2008.1080p.BluRay.REMUX.VC-1.DD5.1-LUMEN", 24_700_000_000)
        assertEquals(listOf(ChipLook("⚠ VC-1: no picture on Kitchen display", ChipKind.Warn, "Warning: VC-1, no picture on Kitchen display"), ChipLook("DD 5.1")), reasonChips(vc1))
        val uhd = ranked(toDen, "Lantern.Field.2008.German.DL.2160p.UHD.BluRay.DDP5.1.x265-WATTWURM", 18_900_000_000)
        assertEquals(
            listOf(ChipLook("≈ 21 Mbps"), ChipLook("2160p: Den TV hasn’t played 4K yet"), ChipLook("German DL", ChipKind.Lang), ChipLook("DD+ 5.1")),
            reasonChips(uhd, bitrate = true),
        )
    }

    /**
     * L150 (CsPhoneTalkBackC2 17, 18): a warning's chip is read as words, ⚠ as "Warning", and a maximum's megabits as the
     * slow-stream banner says them; the chip shows its own words as before.
     */
    @Test
    fun `a warning's chip is read as words, its megabits as the banner says them`() {
        assertEquals("Warning: above your 40 megabits per second for casting", spokenWarning(ReleaseReason.AboveCastMax(40)))
        assertEquals("Warning: above your 30 megabits per second", spokenWarning(ReleaseReason.AboveMaxBitrate(30)))
        assertEquals("Warning: VC-1, no picture on Kitchen display", spokenWarning(ReleaseReason.NoPictureOn("VC-1", "Kitchen display")))
        val flac = ranked(toKitchen.copy(prefs = RankingPrefs(castMaxBitrate = 40_000_000)), "Lantern.Field.2008.1080p.BluRay.REMUX.AVC.FLAC.2.0-HALYARD", 38_600_000_000)
        val chip = reasonChips(flac).single { it.kind == ChipKind.Warn }
        assertEquals("⚠ Above your 40 Mbps for casting", chip.text)
        assertEquals("Warning: above your 40 megabits per second for casting", chip.spoken)
        assertTrue(reasonChips(flac).filter { it.kind != ChipKind.Warn }.all { it.spoken == null }, "only a warning has words of its own")
    }

    @Test
    fun `a release list's chips for this phone are as before`() {
        val web = ranked(RankingContext(phone), "Lantern.Field.2008.German.DL.1080p.WEB-DL.DDP5.1.H.264-KESTREL")
        assertEquals(
            listOf(ChipLook("≈ 7.0 Mbps"), ChipLook("✓ Plays on this device", ChipKind.Good), ChipLook("German DL", ChipKind.Lang), ChipLook("DD+ 5.1")),
            reasonChips(web, bitrate = true),
        )
        val dts = ranked(RankingContext(phone), "Lantern.Field.2008.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-FERRY", 31_800_000_000)
        assertEquals(listOf(ChipLook("⚠ DTS-HD MA audio: no decoder", ChipKind.Warn, "Warning: DTS-HD MA audio, no decoder"), ChipLook("DTS-HD MA 5.1")), reasonChips(dts))
        val uhd = ranked(RankingContext(phone), "Lantern.Field.2008.2160p.WEB-DL.DDP5.1.x265-KESTREL")
        assertEquals(
            listOf(ChipLook("2160p is above this screen"), ChipLook("✓ Plays on this device", ChipKind.Good), ChipLook("DD+ 5.1")),
            reasonChips(uhd),
        )
    }

    /** [title] as a torrent its check settled as [availability], ranked as Search ranks Lantern Field's releases for this phone. */
    private fun torrent(title: String, availability: Availability): RankedItem {
        val hash = "%040x".format(title.hashCode().toLong() and 0xffffffffL)
        val torrent = item(title, 6_200_000_000).copy(indexerId = "prowlarr", guid = "btih:$hash", infoHash = hash, availability = availability)
        return ResultGrouper().group(listOf(torrent), RankingContext(phone), ResultGrouper.KnownTitle("Lantern Field", 2008, isTv = false, runtimeMinutes = 118))
            .sections.flatMap { s -> s.groups.flatMap { it.playable + it.hidden } }.single()
    }

    /**
     * L201, L214: a cached torrent's "✓ Cached" among its chips, before Search's "✓ Plays", read as "Cached"; a hidden one says
     * why, as a warning read as words. None names a service (W80).
     */
    @Test
    fun `a cached torrent says so among its chips, and a hidden one why, naming no service`() {
        val title = "Lantern.Field.2008.1080p.WEB-DL.DDP5.1.H.264-KESTREL"
        val cachedChip = ChipLook("✓ Cached", ChipKind.Good, "Cached")
        val cached = torrent(title, Availability.Cached(listOf("tb-1")))
        assertEquals(
            listOf(ChipLook("≈ 7.0 Mbps"), ChipLook("✓ Plays on this device", ChipKind.Good), cachedChip, ChipLook("DD+ 5.1")),
            reasonChips(cached, bitrate = true),
        )
        assertEquals(listOf(ChipLook("1080p"), ChipLook("WEB-DL"), ChipLook("6.2 GB"), ChipLook("≈ 7.0 Mbps"), cachedChip, plays), groupChips(cached))
        assertEquals(listOf(ChipLook("1080p"), ChipLook("WEB-DL"), ChipLook("6.2 GB"), ChipLook("≈ 7.0 Mbps"), cachedChip, plays), heroChips(cached))
        listOf(
            Availability.NotCached to "Not cached",
            Availability.NotChecked to "Not checked",
            Availability.NotForService to "Not used with your debrid service",
        ).forEach { (availability, words) ->
            assertEquals(
                listOf(ChipLook("≈ 7.0 Mbps"), ChipLook("⚠ $words", ChipKind.Warn, "Warning: $words"), ChipLook("DD+ 5.1")),
                reasonChips(torrent(title, availability), bitrate = true),
            )
        }
    }

    /**
     * L251 (D2PhoneSearchTags): a season row with a tag reads its episode, then the tag and its count as one, then its
     * chips, wherever the tag sits (beside the episode, or leading the chips where it has no room); a pack's "pack" after
     * its label.
     */
    @Test
    fun `a season row reads its episode, then its tag and count as one, then its chips`() {
        val cached = torrent("Lantern.Field.2008.1080p.WEB-DL.DDP5.1.H.264-KESTREL", Availability.Cached(listOf("pm-1", "tb-1")))
        val tag = SourceTag("Premiumize", "From Premiumize, also cached on 1 more service", dim = false, more = 1)
        assertEquals(
            "S02E05, From Premiumize, also cached on 1 more service, 1080p, WEB-DL, 6.2 GB, ≈ 7.0 Mbps, Cached, ✓ Plays",
            groupRowSpoken("S02E05", null, tag, groupChips(cached)),
        )
        assertEquals(
            "Full season, pack, From TorBox, 1080p, WEB-DL, 6.2 GB, ≈ 7.0 Mbps, Cached, ✓ Plays",
            groupRowSpoken("Full season", "pack", SourceTag("TorBox", "From TorBox", dim = false), groupChips(cached)),
        )
    }

    /** L199: the hidden line keeps today's words and names its commonest reasons, the torrents' among them. */
    @Test
    fun `the hidden line names its commonest reasons`() {
        val hidden = listOf(
            torrent("Lantern.Field.2008.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-FERRY", Availability.NotCached),
            torrent("Lantern.Field.2008.720p.BluRay.x264-DRIFTWOOD", Availability.NotCached),
            torrent("Lantern.Field.2008.1080p.WEB-DL.DDP5.1.H.264-KESTREL", Availability.NotForService),
            torrent("Lantern.Field.2008.1080p.BluRay.x264-HALYARD", Availability.NotForService),
            torrent("Lantern.Field.2008.1080p.BluRay.x264-ORCHID", Availability.NotCached),
            ranked(RankingContext(phone), "Lantern.Field.2008.COMPLETE.BLURAY-DRIFTWOOD"),
        )
        assertEquals("6 hidden · not playable (Not cached, Not used with your debrid service)", hiddenLine(hidden, withReasons = true))
        assertEquals("6 hidden · not playable", hiddenLine(hidden, withReasons = false))
    }
}
