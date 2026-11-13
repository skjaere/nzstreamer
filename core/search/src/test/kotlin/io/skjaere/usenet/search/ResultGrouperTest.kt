package io.skjaere.usenet.search

import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.Availability
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.RankingPrefs
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueryParserTest {
    @Test
    fun `episode, season, year and plain titles`() {
        assertEquals(ParsedQuery("harbor lights s02e05", "harbor lights", 2, 5), QueryParser.parse("harbor lights s02e05"))
        assertEquals(ParsedQuery("Harbor Lights 2x05", "Harbor Lights", 2, 5), QueryParser.parse("  Harbor   Lights 2x05 "))
        assertEquals(ParsedQuery("harbor lights season 2", "harbor lights", 2, null), QueryParser.parse("harbor lights season 2"))
        assertEquals(ParsedQuery("copper crown 2019", "copper crown", year = 2019), QueryParser.parse("copper crown 2019"))
        assertEquals(ParsedQuery("1999", "1999"), QueryParser.parse("1999"))
        assertNull(QueryParser.parse("Harbor.Lights.S01E02.1080p.WEB-DL").year)
    }

    @Test
    fun `release names and typed words`() {
        listOf("Harbor.Lights.S02E05.1080p.WEB.h264-GRP", "Copper.Crown.2019.1080p.BluRay.x264-GRP", "harbor_lights_s02e05", "Harbor Lights S02E05 720p HDTV")
            .forEach { assertTrue(QueryParser.parse(it).isReleaseName, it) }
        listOf("harbor lights s02e05", "Harbor Lights 2x05", "K.E.L.P. s01e02", "Tide-Walker", "copper crown 2019", "1999")
            .forEach { assertFalse(QueryParser.parse(it).isReleaseName, it) }
    }
}

class ResultGrouperTest {
    private val context = RankingContext(StaticDeviceCapabilities(videoTenBit = emptySet()))
    private var n = 0

    private fun item(title: String, indexer: String = "a", size: Long = 2_000_000_000) = NewznabItem(
        indexerId = indexer, guid = "g${n++}", title = title, sizeBytes = size, publishedAt = null, postedAt = null,
        categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
        tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
    )

    @Test
    fun `titles, episodes newest first, packs after their season's episodes`() {
        val results = ResultGrouper().group(
            listOf(
                item("Harbor.Lights.S02E04.1080p.WEB-DL.H.264-A"),
                item("Harbor.Lights.S02.1080p.WEB-DL.H.264-PACK", size = 17_000_000_000),
                item("Harbor.Lights.S02E05.1080p.WEB-DL.H.264-A"),
                item("Harbor.Lights.S02E05.720p.HDTV.x264-B", indexer = "b"),
                item("Harbor.Lights.S01E01.1080p.WEB-DL.H.264-A"),
                item("Harbor.Lights.1998.S01E03.576p.DVDRip.XviD-OLD"),
            ),
            context,
        )
        assertEquals(listOf("Harbor Lights" to null, "Harbor Lights" to 1998), results.sections.map { it.title to it.year })
        val main = results.sections.first()
        assertEquals(listOf("S02E05", "S02E04", "Season 2", "S01E01"), main.groups.map { it.label })
        assertEquals(2, main.groups.first().size)
        assertEquals("Harbor.Lights.S02E05.1080p.WEB-DL.H.264-A", main.groups.first().best?.item?.title)
    }

    /** [item] as a torrent of [hash], its check settled as [availability]. */
    private fun torrent(title: String, indexer: String, hash: String, availability: Availability) =
        item(title, indexer).copy(guid = "btih:$hash", infoHash = hash, availability = availability)

    /**
     * L200: one torrent listed by two indexers is one release, counted once, from the first indexer in Settings' order whose
     * "Use with your debrid service" is on, so it plays; with neither on, from the first in that order.
     */
    @Test
    fun `one torrent from two indexers is one release, from the first indexer in Settings' order that uses the service`() {
        val hash = "5c1d0b4e2a7f9c3e8d6b1a0f4e2c7d9b3a5e8f10"
        val cached = Availability.Cached(listOf("torbox-1"))
        val name = "The.Glass.Orchard.2019.1080p.WEB-DL.DDP5.1.H.264-KESTREL"
        val fromQuarry = torrent(name, "quarry", hash, Availability.NotForService)
        val fromProwlarr = torrent(name, "prowlarr", hash, cached)
        val usenet = item(name, "indexer-a")
        val known = ResultGrouper.KnownTitle("The Glass Orchard", 2019, isTv = false)
        // Quarry answered first, and comes first in Settings, but its switch is off.
        val movie = ResultGrouper().group(listOf(fromQuarry, usenet, fromProwlarr), context, known, indexerOrder = listOf("indexer-a", "quarry", "prowlarr")).movieGroup!!
        assertEquals(2, movie.size, "the torrent counted once, Usenet's release apart")
        val merged = (movie.playable + movie.hidden).single { it.item.infoHash == hash }
        assertEquals("prowlarr" to cached, merged.item.indexerId to merged.item.availability)

        // Both on: the first in Settings' order, whichever answered first.
        val other = torrent(name, "quarry", hash, cached)
        val both = ResultGrouper().group(listOf(fromProwlarr, other), context, known, indexerOrder = listOf("quarry", "prowlarr")).movieGroup!!
        assertEquals("quarry", both.playable.single().item.indexerId)
        // Neither on: the first in Settings' order.
        val off = fromProwlarr.copy(availability = Availability.NotForService)
        val neither = ResultGrouper().group(listOf(off, fromQuarry), context, known, indexerOrder = listOf("quarry", "prowlarr")).movieGroup!!
        assertEquals("quarry", (neither.playable + neither.hidden).single().item.indexerId)
    }

    /**
     * DDC-3: two indexers using the service list one torrent, and their checks settled apart: the first in Settings' order
     * couldn't be checked (its call ran past the bound), the other's said cached. The release is the cached copy, which
     * plays, not the first's, which would hide it.
     */
    @Test
    fun `one torrent from two indexers is the copy a service said it has, whichever comes first in Settings`() {
        val hash = "5c1d0b4e2a7f9c3e8d6b1a0f4e2c7d9b3a5e8f10"
        val name = "The.Glass.Orchard.2019.1080p.WEB-DL.DDP5.1.H.264-KESTREL"
        val unchecked = torrent(name, "prowlarr", hash, Availability.NotChecked)
        val cached = torrent(name, "quarry", hash, Availability.Cached(listOf("torbox-1")))
        val known = ResultGrouper.KnownTitle("The Glass Orchard", 2019, isTv = false)
        val movie = ResultGrouper().group(listOf(unchecked, cached), context, known, indexerOrder = listOf("prowlarr", "quarry")).movieGroup!!
        val merged = movie.playable.singleOrNull()
        assertEquals("quarry" to cached.availability, merged?.let { it.item.indexerId to it.item.availability }, "hidden: ${movie.hidden.map { it.item.indexerId to it.item.availability }}")
        assertEquals(1, movie.size)
    }

    /**
     * L176, L177: the grouper ranks a torrent as one, by its cache check: an uncached one hidden as such, a cached one just
     * under Usenet's release of the same name.
     */
    @Test
    fun `a torrent ranks by its source and its check, Usenet's same release first`() {
        val name = "The.Glass.Orchard.2019.1080p.WEB-DL.DDP5.1.H.264-KESTREL"
        val known = ResultGrouper.KnownTitle("The Glass Orchard", 2019, isTv = false)
        val cached = torrent(name, "prowlarr", "5c1d0b4e2a7f9c3e8d6b1a0f4e2c7d9b3a5e8f10", Availability.Cached(listOf("torbox-1")))
        val uncached = torrent("The.Glass.Orchard.2019.720p.WEB-DL.H.264-FERRY", "prowlarr", "6d2e1c5f3b8a0d4f9e7c2b1a5f3d8e0c4b6f9a21", Availability.NotCached)
        val movie = ResultGrouper().group(listOf(cached, item(name, "indexer-a"), uncached), context, known).movieGroup!!
        assertEquals(listOf("indexer-a", "prowlarr"), movie.playable.map { it.item.indexerId })
        assertEquals(listOf(uncached.title), movie.hidden.map { it.item.title })
        assertEquals("Not cached", movie.hidden.single().ranked.reasons.first().text)
    }

    @Test
    fun `unplayable releases are hidden with a reason summary`() {
        val results = ResultGrouper().group(
            listOf(
                item("Harbor.Lights.S02E05.1080p.WEB-DL.H.264-A"),
                item("Harbor.Lights.S02E05.2160p.WEB.H265.10bit-B"),
                item("Harbor.Lights.S02E04.2160p.WEB.H265.10bit-C"),
            ),
            context,
        )
        val groups = results.sections.single().groups
        assertEquals(listOf(1 to 1, 0 to 1), groups.map { it.playable.size to it.hidden.size })
        assertNull(groups[1].best)
        assertEquals(2, results.hidden.size)
        assertEquals(listOf("HEVC 10-bit"), results.hiddenReasons())
    }

    @Test
    fun `scene and umlaut spellings of a title share a section`() {
        val results = ResultGrouper().group(
            listOf(
                item("Wattwuermer.fuer.Anfaenger.S01E01.German.720p.WEB.x264-A"),
                item("Wattwürmer.für.Anfänger.S01E01.German.1080p.WEB.x264-B"),
                item("Wattwurmer.fur.Anfanger.S01E02.German.1080p.WEB.x264-C"),
            ),
            context,
        )
        val section = results.sections.single()
        assertEquals(listOf("S01E02", "S01E01"), section.groups.map { it.label })
    }

    @Test
    fun `a known title puts everything in one section`() {
        val results = ResultGrouper().group(
            listOf(item("Copper.Crown.2019.1080p.BluRay.x264-A"), item("Copper.Crown.2019.German.DL.720p.WEB.h264-B"), item("Kupferkrone.2019.1080p.WEB.h264-C")),
            context,
            ResultGrouper.KnownTitle("Copper Crown", 2019, isTv = false),
        )
        val section = results.sections.single()
        assertEquals("Copper Crown", section.title)
        assertEquals(listOf(GroupKind.Movie), section.groups.map { it.kind })
        assertEquals(3, section.groups.single().size)
    }

    /** With a maximum bitrate, a picked film's releases are estimated over its runtime: 38 GB over 165 minutes is about 31 Mbps. */
    @Test
    fun `a picked title's releases are estimated over its runtime`() {
        val upTo40 = context.copy(prefs = RankingPrefs(maxBitrate = 40_000_000))
        val releases = listOf(item("Copper.Crown.2019.1080p.BluRay.REMUX.AVC-A", size = 38_000_000_000), item("Copper.Crown.2019.1080p.WEB-DL.H.264-B", size = 10_000_000_000))
        val long = ResultGrouper().group(releases, upTo40, ResultGrouper.KnownTitle("Copper Crown", 2019, isTv = false, runtimeMinutes = 165)).movieGroup
        assertEquals("Copper.Crown.2019.1080p.BluRay.REMUX.AVC-A", long?.best?.item?.title)
        // Over the 110 minutes taken for a film whose runtime isn't known, it is about 46 Mbps: above the maximum, tried last.
        val unknown = ResultGrouper().group(releases, upTo40, ResultGrouper.KnownTitle("Copper Crown", 2019, isTv = false)).movieGroup
        assertEquals("Copper.Crown.2019.1080p.WEB-DL.H.264-B", unknown?.best?.item?.title)
    }

    @Test
    fun `a picked title's untagged releases speak its original language`() {
        val releases = listOf(item("Moonwell.Village.2021.1080p.BluRay.x264-A"), item("Moonwell.Village.2021.ENGLISH.720p.BluRay.x264-B"))
        // English is preferred: the dub beats a Korean film's untagged release, which an unknown original language takes for English.
        assertEquals(
            "Moonwell.Village.2021.ENGLISH.720p.BluRay.x264-B",
            ResultGrouper().group(releases, context, ResultGrouper.KnownTitle("Moonwell Village", 2021, isTv = false, originalLanguage = "ko")).movieGroup?.best?.item?.title,
        )
        assertEquals(
            "Moonwell.Village.2021.1080p.BluRay.x264-A",
            ResultGrouper().group(releases, context, ResultGrouper.KnownTitle("Moonwell Village", 2021, isTv = false)).movieGroup?.best?.item?.title,
        )
    }

    @Test
    fun `a picked movie's releases form one group ranked together, whatever year they are tagged with`() {
        val releases = listOf(
            item("Copper.Crown.2010.720p.WEB.h264-A"),
            item("Copper.Crown.2011.1080p.WEB-DL.H.264-B"),
            item("Copper.Crown.1080p.BluRay.x264-C"),
            item("Copper.Crown.2010.2160p.WEB.H265.10bit-D"),
        )
        val results = ResultGrouper().group(releases, context, ResultGrouper.KnownTitle("Copper Crown", 2010, isTv = false))
        val section = results.sections.single()
        assertEquals(listOf(GroupKind.Movie), section.groups.map { it.kind })
        val movie = section.groups.single()
        assertEquals(2010, movie.year)
        assertEquals("2010", movie.label)
        assertEquals(releases.map { it.title }.toSet(), (movie.playable + movie.hidden).map { it.item.title }.toSet())
        assertEquals(3 to 1, movie.playable.size to movie.hidden.size)
        // The yearless BluRay is the best of them all, so the hero's Play starts it.
        assertEquals("Copper.Crown.1080p.BluRay.x264-C", movie.best?.item?.title)
        assertEquals(movie, results.movieGroup)
        assertEquals(movie, results.group(movie.key))
    }

    @Test
    fun `free text keeps a title per year and a group per yearless release`() {
        val results = ResultGrouper().group(
            listOf(
                item("Copper.Crown.2010.720p.WEB.h264-A"),
                item("Copper.Crown.2011.1080p.WEB-DL.H.264-B"),
                item("Copper.Crown.1080p.BluRay.x264-C"),
                item("Copper.Crown.720p.WEB.h264-D"),
            ),
            context,
        )
        assertEquals(
            mapOf(2010 to listOf(GroupKind.Movie), 2011 to listOf(GroupKind.Movie), null to listOf(GroupKind.Other, GroupKind.Other)),
            results.sections.associate { s -> s.year to s.groups.map { it.kind } },
        )
    }

    @Test
    fun `a picked movie's hero shows the movie, not a same-named show's larger episode group`() {
        val results = ResultGrouper().group(
            listOf(
                item("Copper.Crown.2010.1080p.BluRay.x264-A"),
                item("Copper.Crown.720p.WEB.h264-B"),
                item("Copper.Crown.S01E01.1080p.WEB-DL.H.264-C"),
                item("Copper.Crown.S01E01.720p.WEB.h264-D"),
                item("Copper.Crown.S01E01.720p.HDTV.x264-E"),
            ),
            context,
            ResultGrouper.KnownTitle("Copper Crown", 2010, isTv = false),
        )
        val movie = results.movieGroup
        assertEquals(GroupKind.Movie, movie?.kind)
        assertEquals(setOf("Copper.Crown.2010.1080p.BluRay.x264-A", "Copper.Crown.720p.WEB.h264-B"), movie?.playable?.map { it.item.title }?.toSet())
    }

    @Test
    fun `group keys are unique across titles, so an opened group resolves to its own title`() {
        val results = ResultGrouper().group(
            listOf(
                item("The.Ledger.US.S02E01.720p.WEB.x264-GRP"),
                item("The.Ledger.S02E01.720p.HDTV.x264-GRP"),
                item("Copper.Crown.2019.1080p.BluRay.x264-A"),
                item("Silver.Tide.2019.1080p.WEB.h264-B"),
            ),
            context,
        )
        val groups = results.sections.flatMap { s -> s.groups.map { s to it } }
        assertEquals(
            setOf("The Ledger US" to "S02E01", "The Ledger" to "S02E01", "Copper Crown" to "2019", "Silver Tide" to "2019"),
            groups.map { (s, g) -> s.title to g.label }.toSet(),
        )
        groups.forEach { (s, g) ->
            val opened = results.group(g.key)
            assertEquals(g.best?.item?.title, opened?.best?.item?.title)
            assertEquals(s.title, results.sections.single { opened in it.groups }.title)
        }
        assertEquals(groups.size, groups.map { (_, g) -> g.key }.distinct().size, "keys: ${groups.map { (_, g) -> g.key }}")
    }
}
