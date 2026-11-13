package io.skjaere.usenet.search

import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.NewznabError
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.QualityPreference
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.RankingPrefs
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseRanker
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EpisodeFindHandoffTest {
    private val indexers = listOf(
        IndexerConfig("a", "Indexer A", "https://a.example/api", "key-a"),
        IndexerConfig("b", "Indexer B", "https://b.example/api", "key-b"),
    )
    private val context = RankingContext(StaticDeviceCapabilities(videoTenBit = emptySet()))
    private val search = EpisodeSearch(
        EpisodeRequest(ShowRef("Harbor Lights", 2021, ids = TvIds(tvdb = 900001)), season = 1, episode = 2),
        indexers,
        context,
    )

    private fun found(title: String, indexer: String = "a"): FoundRelease {
        val item = NewznabItem(
            indexerId = indexer, guid = title.hashCode().toString(), title = title, sizeBytes = 2_000_000_000, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = 900001, tmdbId = null, imdbId = null,
            tvmazeId = null, season = 1, episode = 2, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        )
        val ranked = ReleaseRanker().rank(listOf(ReleaseFacts(item.guid, title, item.sizeBytes)), context).single()
        return FoundRelease(item, ranked, SearchStage.Episode)
    }

    private val answered = StageReport(SearchStage.Episode, indexers.map { IndexerReport(it, "TVDB id", found = 1, accepted = 1) })
    private val good = FindResult(
        search.request,
        playable = listOf(found("Harbor.Lights.S01E02.1080p.WEB-DL.H.264-A"), found("Harbor.Lights.S01E02.720p.HDTV.x264-B", indexer = "b")),
        hidden = emptyList(),
        stages = listOf(answered),
    )

    @Test
    fun `a good prefetched result is used once`() {
        val handoff = EpisodeFindHandoff()
        handoff.offer(search, good)
        assertSame(good, handoff.take(search))
        assertNull(handoff.take(search), "a second play searches again")
    }

    @Test
    fun `an empty result is not handed on`() {
        val handoff = EpisodeFindHandoff()
        handoff.offer(search, good.copy(playable = emptyList(), hidden = listOf(found("Harbor.Lights.S01E02.2160p.WEB.H265.10bit-C"))))
        assertNull(handoff.take(search))
    }

    @Test
    fun `a result with an indexer error in any stage is not handed on`() {
        val timedOut = good.copy(
            stages = listOf(StageReport(SearchStage.Episode, listOf(IndexerReport(indexers[0], "TVDB id", 2, 2), IndexerReport(indexers[1], "TVDB id", error = NewznabError.Timeout("Timed out"))))),
        )
        val packStageOffline = good.copy(
            stages = listOf(answered, StageReport(SearchStage.SeasonPack, listOf(IndexerReport(indexers[1], "TVDB id", error = NewznabError.Network("No connection"))))),
        )
        listOf(timedOut, packStageOffline).forEach { result ->
            val handoff = EpisodeFindHandoff()
            handoff.offer(search, result)
            assertNull(handoff.take(search), "${result.stages}")
        }
    }

    @Test
    fun `a result found with other indexers, keys, ranking or remembered release is not used`() {
        val changed = listOf(
            search.copy(indexers = listOf(indexers[0].copy(apiKey = "key-a2"), indexers[1])),
            search.copy(indexers = listOf(indexers[0].copy(baseUrl = "https://a2.example/api"), indexers[1])),
            search.copy(indexers = listOf(indexers[0], indexers[1].copy(enabled = false))),
            search.copy(indexers = indexers.take(1)),
            search.copy(indexers = indexers + IndexerConfig("c", "Indexer C", "https://c.example/api", "key-c")),
            search.copy(context = context.copy(prefs = RankingPrefs(quality = QualityPreference.P720))),
            search.copy(context = context.copy(prefs = RankingPrefs(audioLanguages = listOf("de", "en")))),
            search.copy(context = context.copy(device = StaticDeviceCapabilities())),
            search.copy(remembered = RememberedPick("b", "g9", "Harbor.Lights.S01E02.720p.HDTV.x264-B", null)),
        )
        changed.forEach { other ->
            val handoff = EpisodeFindHandoff()
            handoff.offer(search, good)
            assertNull(handoff.take(other), "$other")
        }
    }

    @Test
    fun `a play of another episode drops the prefetched result`() {
        val handoff = EpisodeFindHandoff()
        handoff.offer(search, good)
        assertNull(handoff.take(search.copy(request = search.request.copy(episode = 3))))
        assertNull(handoff.take(search))
    }

    @Test
    fun `a result older than the limit is not used, nor one cleared`() {
        var clock = 0L
        val handoff = EpisodeFindHandoff(maxAgeMs = 10 * 60_000L, now = { clock })
        handoff.offer(search, good)
        clock = 10 * 60_000L
        assertNull(handoff.take(search))

        handoff.offer(search, good)
        handoff.clear()
        assertNull(handoff.take(search))
    }

    @Test
    fun `holds tells whether a take for the episode could still get the result, now or a while from now`() {
        var clock = 0L
        val handoff = EpisodeFindHandoff(maxAgeMs = 10 * 60_000L, now = { clock })
        assertFalse(handoff.holds(search.request))
        handoff.offer(search, good)
        assertTrue(handoff.holds(search.request))
        assertFalse(handoff.holds(search.request.copy(episode = 3)))
        clock = 9 * 60_000L
        assertTrue(handoff.holds(search.request, forMs = 59_999))
        assertFalse(handoff.holds(search.request, forMs = 60_000))
        clock = 10 * 60_000L
        assertFalse(handoff.holds(search.request))

        clock = 0
        handoff.offer(search, good)
        handoff.take(search)
        assertFalse(handoff.holds(search.request), "taken")
        handoff.offer(search, good.copy(playable = emptyList()))
        assertFalse(handoff.holds(search.request), "never held")
    }
}
