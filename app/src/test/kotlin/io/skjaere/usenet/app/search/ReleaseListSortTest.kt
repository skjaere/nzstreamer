package io.skjaere.usenet.app.search

import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.GroupKind
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

/**
 * L628: Choose release's orders. Best match, Size and Age order a plugin's releases and its possible matches each on
 * their own, the possible matches staying under the others whatever their sizes and ages; a list with no possible
 * matches (a film's, an episode's) is ordered as it always was. The releases are invented.
 */
class ReleaseListSortTest {
    private val now = Instant.parse("2026-11-23T20:41:00Z")

    private fun ranked(guid: String, gb: Long, daysOld: Long, indexer: String = "a"): RankedItem {
        val title = "Kestrel.Cup.2026.Final.1080p.WEB.h264-${guid.uppercase()}"
        return RankedItem(
            NewznabItem(
                indexerId = indexer, guid = guid, title = title, sizeBytes = gb * 1_000_000_000, publishedAt = null,
                postedAt = now.minus(Duration.ofDays(daysOld)), categories = emptyList(), grabs = null, passworded = false, files = null,
                tvdbId = null, tmdbId = null, imdbId = null, tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null,
                downloadTemplate = null, attributes = emptyMap(),
            ),
            RankedRelease(ReleaseFacts("$indexer/$guid", title, gb * 1_000_000_000), ReleaseParser.parse(title), playable = true, score = 1.0, reasons = emptyList(), estimatedBitrate = null),
        )
    }

    private fun group(key: String, vararg items: RankedItem) = ResultGroup(key, GroupKind.Other, null, emptyList(), null, null, items.toList(), emptyList())

    // As ranked: the best first. Sizes and ages chosen so each order differs, and so the possible matches are the largest
    // and the newest of all.
    private val best = ranked("best", gb = 6, daysOld = 2)
    private val second = ranked("second", gb = 3, daysOld = 1)
    private val third = ranked("third", gb = 5, daysOld = 3)
    private val dateless = ranked("dateless", gb = 9, daysOld = 0)
    private val codes = ranked("codes", gb = 7, daysOld = 0, indexer = "b").let { it.copy(item = it.item.copy(postedAt = now.minusSeconds(60))) }
    private val accepted = group("plugin:t", best, second, third)
    private val possible = group("plugin:t|possible", codes, dateless)

    @Test
    fun `Best match keeps each section as ranked, the possible matches under the others`() {
        assertEquals(listOf(best, second, third) to listOf(codes, dateless), releaseSections(accepted, possible, ReleaseSort.Best))
    }

    @Test
    fun `Size orders each section on its own, largest first, the possible matches staying under the others`() {
        assertEquals(listOf(best, third, second) to listOf(dateless, codes), releaseSections(accepted, possible, ReleaseSort.Size))
    }

    @Test
    fun `Age orders each section on its own, newest first, the possible matches staying under the others`() {
        assertEquals(listOf(second, best, third) to listOf(dateless, codes), releaseSections(accepted, possible, ReleaseSort.Age))
    }

    @Test
    fun `with nothing accepted the possible matches alone are ordered`() {
        val none = group("plugin:t")
        assertEquals(emptyList<RankedItem>() to listOf(dateless, codes), releaseSections(none, possible, ReleaseSort.Size))
        assertEquals(emptyList<RankedItem>() to listOf(codes, dateless), releaseSections(none, possible, ReleaseSort.Best))
    }

    /** A film's or an episode's list, which has no possible matches: ordered as it always was. */
    @Test
    fun `a list with no possible matches is ordered as before`() {
        val film = group("copper crown|movie:2019", best, second, third, dateless)
        assertEquals(listOf(best, second, third, dateless) to emptyList<RankedItem>(), releaseSections(film, null, ReleaseSort.Best))
        assertEquals(listOf(dateless, best, third, second) to emptyList<RankedItem>(), releaseSections(film, null, ReleaseSort.Size))
        assertEquals(listOf(dateless, second, best, third) to emptyList<RankedItem>(), releaseSections(film, null, ReleaseSort.Age))
        assertEquals(listOf("Best match", "Size", "Age"), ReleaseSort.entries.map { it.label })
    }

    /** The bar's line counts the possible matches too: "5 releases from 2 indexers"; a film's as before. */
    @Test
    fun `the bar counts every release the list shows, and its indexers`() {
        assertEquals("5 releases from 2 indexers", releasesFrom(accepted, possible))
        assertEquals("3 releases from 1 indexer", releasesFrom(accepted))
        assertEquals("2 releases from 2 indexers", releasesFrom(group("plugin:t"), possible))
        val hidden = accepted.copy(playable = listOf(best), hidden = listOf(ranked("disc", gb = 40, daysOld = 5, indexer = "c")))
        assertEquals("2 releases from 2 indexers", releasesFrom(hidden))
        // A film's bar: its year, then its title and the count, as today.
        val film = ResultGroup("copper crown|movie:2019", GroupKind.Movie, null, emptyList(), null, 2019, listOf(best, second), emptyList())
        assertEquals("2019" to "Copper Crown · 2 releases from 1 indexer", releaseListBar(film, "Copper Crown"))
        // A plugin's item: its title over its subtitle and the count of both sections; with no subtitle, the count alone.
        assertEquals("Kestrel Cup · Final" to "KCL · 5 releases from 2 indexers", releaseListBar(accepted, "KCL", heading = "Kestrel Cup · Final", possible = possible))
        assertEquals("Kestrel Cup · Final" to "2 releases from 2 indexers", releaseListBar(group("plugin:t"), "", heading = "Kestrel Cup · Final", possible = possible))
    }
}
