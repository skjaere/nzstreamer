package io.skjaere.usenet.newznab

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A torrent indexer's items (L159, L161, L168): each is known by its v1 info hash, read from wherever the feed gives it,
 * and keeps nothing of a magnet's trackers or name, or of a link a passkey can be in. The fixtures are invented, in the
 * shapes Prowlarr and Jackett answer.
 */
class NewznabXmlTorznabTest {
    private val torznab = indexer().copy(kind = IndexerKind.Torznab)

    private fun page(fixture: String, on: IndexerConfig = torznab) = NewznabXml.parseSearch(fixture(fixture), on)

    private fun SearchPage.titled(title: String) = items.single { it.title == title }

    @Test
    fun `the hash comes from the infohash attribute, lower-cased, and is the item's guid and key`() {
        val item = page("torznab-prowlarr.xml").titled("The.Glass.Orchard.S02E05.1080p.WEB-DL.DDP5.1.H.264-KESTREL")
        assertEquals("4b3ab0e0ed54ee8b2f240838244cd8524c3be5c1", item.infoHash)
        assertEquals("btih:4b3ab0e0ed54ee8b2f240838244cd8524c3be5c1", item.guid)
        assertEquals("btih:4b3ab0e0ed54ee8b2f240838244cd8524c3be5c1", item.key)
        assertEquals("application/x-bittorrent", item.enclosureType)
        assertEquals(812, item.grabs)
        assertEquals(1, item.files)
        assertEquals(1_610_612_736, item.sizeBytes)
        assertEquals(401234, item.tvdbId)
    }

    @Test
    fun `else from magneturl, then a magnet in the enclosure, the link or the guid`() {
        val jackett = page("torznab-jackett.xml")
        assertEquals("af7a87d87c99ef8f47b5802b4aa68052a98ca809", jackett.titled("The.Glass.Orchard.S02.1080p.WEB-DL.DDP5.1.H.264-KESTREL").infoHash)
        assertEquals("4d59de26e786129d949fc9ebb40f115b1d69b1b7", jackett.titled("Quiet.Orbit.S02E05.1080p.WEB.H264-GRP").infoHash, "the enclosure's")
        assertEquals("6f0597cc034efa3d715b411dd3e5304ce1e4780e", jackett.titled("Quiet.Orbit.S02E06.1080p.WEB.H264-GRP").infoHash, "the link's")
        assertEquals("d23dc5a7369b2192f83841063bc801d14ae03aa6", jackett.titled("Quiet.Orbit.S02E07.1080p.WEB.H264-GRP").infoHash, "the guid's")
    }

    @Test
    fun `a base32 hash gives its hex, and a hybrid magnet its v1 hash`() {
        val jackett = page("torznab-jackett.xml")
        assertEquals("8e032a12c52dc443eff7ce403f3aefedc107d4eb", jackett.titled("The.Glass.Orchard.S02E05.720p.WEB.H264-QUARRY").infoHash)
        assertEquals("8e032a12c52dc443eff7ce403f3aefedc107d4eb", TorrentHashes.ofMagnet("magnet:?xt=urn:btih:rybsuewffxceh37xzzad6oxp5xaqpvhl"))
        // The hybrid's v2 hash comes first in its magnet; the v1 one is taken.
        assertEquals("4d59de26e786129d949fc9ebb40f115b1d69b1b7", jackett.titled("Quiet.Orbit.S02E05.1080p.WEB.H264-GRP").infoHash)
    }

    /**
     * L237: Prowlarr can copy a magnet's first topic into `infohash`, so it can be base32, or a v2 hash (64 hex, or the
     * multihash `1220…`) where a v1 one belongs. The attribute is read as 40 hex or 32 base32; the others give no
     * hash from it, and a hybrid magnet beside them gives its v1 hash.
     */
    @Test
    fun `a base32 infohash attribute gives its hex, and a 64-hex or multihash one none, a hybrid magnet beside it its btih`() {
        val page = page("torznab-prowlarr-hashes.xml")
        assertEquals(
            listOf("Harbor.Lights.S01E03.1080p.WEB-DL.H.264-KESTREL", "Harbor.Lights.S01E06.1080p.WEB-DL.H.264-KESTREL", "Harbor.Lights.S01E07.1080p.WEB-DL.H.264-KESTREL"),
            page.items.map { it.title },
        )
        assertEquals(mapOf(LeftOut.NoHash to 2), page.leftOut, "the 64-hex and the multihash, with no magnet")
        assertEquals("c0a1e2f3b4d5968778695a4b3c2d1e0f9a8b7c6d", page.titled("Harbor.Lights.S01E03.1080p.WEB-DL.H.264-KESTREL").infoHash)
        assertEquals("5be1f00d2a4c6e8091b3d5f7193b5d7f9a1c3e5f", page.titled("Harbor.Lights.S01E06.1080p.WEB-DL.H.264-KESTREL").infoHash, "the hybrid magnet's, beside 64 hex")
        assertEquals("5be1f00d2a4c6e8091b3d5f7193b5d7f9a1c3e5f", page.titled("Harbor.Lights.S01E07.1080p.WEB-DL.H.264-KESTREL").infoHash, "the hybrid magnet's base32, beside a multihash")

        val v2 = "09824131ed29543c3521a3c749080fab0aeda9654647cb70179beab3148613b5"
        assertEquals("c0a1e2f3b4d5968778695a4b3c2d1e0f9a8b7c6d", TorrentHashes.of("ycq6f45u2wlio6djljftyli6b6niw7dn", emptyList()), "either case")
        assertNull(TorrentHashes.of(v2, emptyList()))
        assertNull(TorrentHashes.of("1220$v2", emptyList()))
        assertNull(TorrentHashes.of(v2.uppercase(), listOf(null, "https://tracker.example.org/torrent/64")))
    }

    @Test
    fun `a v2-only magnet and an item without a hash give none, and are left out and counted`() {
        val v2 = "magnet:?xt=urn:btmh:122009824131ed29543c3521a3c749080fab0aeda9654647cb70179beab3148613b5&dn=Quiet.Orbit.S02E08"
        assertNull(TorrentHashes.ofMagnet(v2))
        assertNull(TorrentHashes.ofMagnet("magnet:?dn=Quiet.Orbit.S02E09"))
        assertNull(TorrentHashes.ofMagnet("magnet:?xt=urn:btih:8e032a12c52dc443eff7ce403f3aefedc107d4e"), "39 digits")
        assertNull(TorrentHashes.ofMagnet("magnet:?xt=urn:btih:8e032a12c52dc443eff7ce403f3aefedc107d4ebb"), "41 digits")
        assertNull(TorrentHashes.ofMagnet("magnet:?xt=urn:btih:8e032a12c52dc443eff7ce403f3aefedc107d4eg"), "not hex")
        assertNull(TorrentHashes.ofMagnet("https://tracker.example.org/torrent/83"), "not a magnet")
        assertNull(TorrentHashes.hex("not-a-hash"))

        val jackett = page("torznab-jackett.xml")
        assertEquals(6, jackett.items.size)
        assertFalse(jackett.items.any { "VTWO" in it.title || "NOHASH" in it.title })
        assertEquals(mapOf(LeftOut.NoHash to 2), jackett.leftOut)
        // Prowlarr's second item has an infohash that isn't one, and no magnet.
        val prowlarr = page("torznab-prowlarr.xml")
        assertEquals(listOf("The.Glass.Orchard.S02E05.1080p.WEB-DL.DDP5.1.H.264-KESTREL"), prowlarr.items.map { it.title })
        assertEquals(mapOf(LeftOut.NoHash to 1), prowlarr.leftOut)
    }

    @Test
    fun `jackett's files and grabs elements are read where there is no attribute`() {
        val season = page("torznab-jackett.xml").titled("The.Glass.Orchard.S02.1080p.WEB-DL.DDP5.1.H.264-KESTREL")
        assertEquals(10, season.files)
        assertEquals(1543, season.grabs)
        assertEquals(9_663_676_416, season.sizeBytes)
        assertEquals(listOf(5040, 100005), season.categories)
    }

    @Test
    fun `a magnet's trackers and name appear nowhere in the item, its toString included`() {
        page("torznab-jackett.xml").items.forEach { item ->
            val text = item.toString()
            listOf("tracker.example.org", "announce", "tr=", "dn=", "magnet:", "jackett.home.lan").forEach { leak ->
                assertFalse(leak in text, "$leak in ${item.title}: $text")
            }
            assertNull(item.downloadTemplate, "no link of a torrent is kept: D1 fetches none (L168)")
            assertNull(item.attributes["magneturl"])
        }
    }

    @Test
    fun `a guid that is a magnet, or a download link with a passkey, is replaced by the hash`() {
        val jackett = page("torznab-jackett.xml")
        val magnetGuid = jackett.titled("The.Glass.Orchard.S02.1080p.WEB-DL.DDP5.1.H.264-KESTREL")
        assertEquals("btih:af7a87d87c99ef8f47b5802b4aa68052a98ca809", magnetGuid.guid)
        val passkeyGuid = jackett.titled("The.Glass.Orchard.2024.2160p.BluRay.REMUX.HEVC-KESTREL")
        assertEquals("btih:6f0597cc034efa3d715b411dd3e5304ce1e47abc", passkeyGuid.guid)
        listOf(magnetGuid, passkeyGuid).forEach { item ->
            assertFalse("torrent_pass" in item.toString())
            assertFalse("0badc0de" in item.toString())
            assertFalse("download/5" in item.toString())
        }
        // Every item, whatever its guid in the feed, is known by its hash.
        assertTrue(jackett.items.all { it.guid == "btih:${it.infoHash}" && it.key == it.guid })
    }

    @Test
    fun `the same feed on a newznab indexer is left out whole, as results that aren't NZBs`() {
        val page = page("torznab-jackett.xml", on = indexer())
        assertEquals(emptyList(), page.items)
        assertEquals(mapOf(LeftOut.NotNzb to 8), page.leftOut)
    }

    /** An item without an enclosure type isn't known for an NZB there: it is left out as having no hash. */
    @Test
    fun `an NZB feed on a torznab indexer is left out whole, its NZBs as such`() {
        val page = page("newznab-search.xml")
        assertEquals(emptyList(), page.items)
        assertEquals(mapOf(LeftOut.Nzb to 1, LeftOut.NoHash to 1), page.leftOut)
    }

    @Test
    fun `a newznab item keeps its guid and link, with no hash, and one without an enclosure type is an NZB`() {
        val nzb = page("newznab-search.xml", on = indexer()).items.first()
        assertEquals("a1b2c3d4", nzb.guid)
        assertNull(nzb.infoHash)
        assertEquals("application/x-nzb", nzb.enclosureType)
        val untyped = NewznabXml.parseSearch(
            """<rss><channel><item><title>Harbor.Lights.S01E02.1080p.WEB-DL.H.264-GRP</title><guid>g1</guid>""" +
                """<enclosure url="https://indexer.example.org/getnzb/g1.nzb" length="10"/></item></channel></rss>""",
            indexer(),
        )
        assertEquals(listOf("g1"), untyped.items.map { it.guid })
        assertEquals(emptyMap(), untyped.leftOut)
    }
}
