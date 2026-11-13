package io.skjaere.usenet.app.search

import io.skjaere.usenet.app.casting.CastModel
import io.skjaere.usenet.app.ui.StatusKind
import io.skjaere.usenet.data.CastDeviceMemory
import io.skjaere.usenet.data.CastVideoCodec
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.RankingPrefs
import io.skjaere.usenet.releases.RankingTarget
import io.skjaere.usenet.releases.ReleaseRanker
import io.skjaere.usenet.releases.ReleaseReason
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import io.skjaere.usenet.search.EpisodeRequest
import io.skjaere.usenet.search.GroupKind
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup
import io.skjaere.usenet.search.ShowRef
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L127: what a release list ranks by, and the releases it holds ranked again for another device, as Play on's choice
 * changes: no indexer asked, the group's place kept. An episode's list ranks as the episode finder ranks it, by the
 * episode's runtime, the show's language and the season's size. The releases are invented.
 */
class RankedListsTest {
    private val here = RankingContext(StaticDeviceCapabilities())
    private val kitchen = here.copy(device = CastModel.Chromecast.profile(null), target = RankingTarget.Cast("Kitchen display"))

    private fun item(title: String) = NewznabItem(
        indexerId = "a", guid = title, title = title, sizeBytes = 4_000_000_000, publishedAt = null, postedAt = null,
        categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
        tvmazeId = null, season = 2, episode = 5, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
    )

    /** The releases as the episode's list holds them, ranked by [context]. */
    private fun group(context: RankingContext, vararg titles: String): ResultGroup {
        val items = titles.map(::item).associateBy { "${it.indexerId}/${it.guid}" }
        val ranked = ReleaseRanker().rank(items.values.map { io.skjaere.usenet.releases.ReleaseFacts("${it.indexerId}/${it.guid}", it.title, it.sizeBytes) }, context)
            .map { RankedItem(items.getValue(it.facts.id), it) }
        return ResultGroup("S02E05", GroupKind.Episode, 2, listOf(5), null, null, ranked.filter { it.ranked.playable }, ranked.filterNot { it.ranked.playable })
    }

    @Test
    fun `a list is ranked for the Cast device its context names, else for this device`() {
        assertEquals("Kitchen display", kitchen.rankedFor)
        assertNull(here.rankedFor)
    }

    @Test
    fun `two contexts rank alike for this device, or for one Cast device as one profile has it`() {
        assertTrue(here.ranksAs(here.copy(device = StaticDeviceCapabilities(displayHeight = 2160), prefs = RankingPrefs(maxBitrate = 40_000_000))))
        assertTrue(kitchen.ranksAs(kitchen.copy(prefs = RankingPrefs(maxBitrate = 40_000_000))))
        assertFalse(here.ranksAs(kitchen))
        assertFalse(kitchen.ranksAs(here))
        assertFalse(kitchen.ranksAs(kitchen.copy(target = RankingTarget.Cast("Den TV"))))
        // What the device has shown since: another profile, so it ranks again.
        val shown4k = CastModel.Chromecast.profile(CastDeviceMemory("device-kitchen", reportsVideo = true, shown2160 = listOf(CastVideoCodec("H264"))))
        assertFalse(kitchen.ranksAs(kitchen.copy(device = shown4k)))
    }

    @Test
    fun `a group's releases rank again as they would have been found, in their group`() {
        val titles = arrayOf(DTS, ORCHID)
        val again = group(here, *titles).rankedAgain(kitchen)
        assertEquals(group(kitchen, *titles), again)
        assertEquals(listOf("a/$ORCHID", "a/$DTS"), again.playable.map { it.key })
        assertEquals(listOf(ReleaseReason.NoSoundOn("DTS-HD MA", "Kitchen display")), again.playable.last().ranked.reasons.filterIsInstance<ReleaseReason.NoSoundOn>())
        assertEquals(group(here, *titles), again.rankedAgain(here))
        assertEquals(listOf("a/$DTS"), group(here, *titles).hidden.map { it.key }, "no DTS decoder here")
    }

    @Test
    fun `an episode's list ranks by its runtime, the show's language and the season's size, as the finder does`() {
        val request = EpisodeRequest(ShowRef("Harbor Lights", originalLanguage = "ko"), 2, 5, runtimeMinutes = 43, seasonEpisodes = 10)
        val ranking = here.copy(runtimeMinutes = 52, originalLanguage = "en", seasonEpisodes = 8).forEpisode(request)
        assertEquals(43, ranking.runtimeMinutes)
        assertEquals("ko", ranking.originalLanguage)
        assertEquals(10, ranking.seasonEpisodes)
        // What the request doesn't say, the context does.
        val bare = here.copy(runtimeMinutes = 52, originalLanguage = "en", seasonEpisodes = 8).forEpisode(EpisodeRequest(ShowRef("Harbor Lights"), 2, 5))
        assertEquals(Triple(52, "en", 8), Triple(bare.runtimeMinutes, bare.originalLanguage, bare.seasonEpisodes))
    }

    private companion object {
        const val DTS = "Harbor.Lights.S02E05.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-FERRY"
        const val ORCHID = "Harbor.Lights.S02E05.1080p.BluRay.DD5.1.x264-ORCHID"
    }

    /**
     * L199, L214 (DbPhoneSearch's pills): a torrent indexer whose torrents are being checked says so at the end of its pill,
     * quietly, read as "Home Prowlarr, 34, checking 12 torrents"; once checked, one whose check failed warns, read as a
     * warning. Every other pill is as today.
     */
    @Test
    fun `a torrent indexer's pill says quietly how many torrents are being checked, and warns of those it couldn't check`() {
        fun pill(state: IndexerStatus.State) = pillLook(IndexerStatus("p", "Home Prowlarr", state))
        assertEquals(
            PillLook("Home Prowlarr · 34", StatusKind.Ok, quiet = "· checking 12 torrents", spoken = "Home Prowlarr, 34, checking 12 torrents"),
            pill(IndexerStatus.State.Checking(results = 34, waiting = 12)),
        )
        assertEquals("· checking 1 torrent", pill(IndexerStatus.State.Checking(results = 4, waiting = 1)).quiet)
        assertEquals(
            PillLook("Home Prowlarr · 34 · not checked", StatusKind.Warn, spoken = "Warning: Home Prowlarr, 34, not checked"),
            pill(IndexerStatus.State.Answered(34, unchecked = 3)),
        )
        // Today's.
        assertEquals(PillLook("Home Prowlarr · 34", StatusKind.Ok), pill(IndexerStatus.State.Answered(34)))
        assertEquals(PillLook("Home Prowlarr · 0", StatusKind.None), pill(IndexerStatus.State.Answered(0)))
        assertEquals(PillLook("Home Prowlarr · searching", StatusKind.Busy), pill(IndexerStatus.State.Searching))
        assertEquals(PillLook("Home Prowlarr · No answer within 25 s", StatusKind.Warn), pill(IndexerStatus.State.Failed("No answer within 25 s")))
        assertEquals(PillLook("Home Prowlarr · skipped", StatusKind.None), pill(IndexerStatus.State.Skipped("Doesn't support this search")))
    }
}
