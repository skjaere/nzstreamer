package io.skjaere.usenet.mockseed

import com.sun.net.httpserver.HttpServer
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.NewznabClient
import io.skjaere.usenet.newznab.SearchQuery
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseRanker
import io.skjaere.usenet.releases.ReleaseReason
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import io.skjaere.usenet.releases.VideoCodec
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The README screenshots' indexer catalogue (`indexer --catalog screens`): its releases name the screens catalogue's
 * titles by the ids the device tests' strict FakeTmdb answers for, ids past every real service's; the fake indexer
 * lists them with sizes of their own; and the command seeds the clip whole only under the releases that play, every
 * other one with each article missing. The mock NNTP server is a stand-in here that records what is posted to it.
 */
class ScreensCatalogTest {
    /** The catalogue the device tests read, from the repository (the test runs in tools/mock-seed). */
    private val catalog = File("../../${ScreensCatalog.FILE}")
    private val titles = ScreensCatalog.titles(catalog)
    private val releases = ScreensCatalog.releases(catalog)

    @Test
    fun `every release names a catalogue title by its ids, and every id lies past the real services' ranges`() {
        assertEquals(18, titles.size)
        releases.forEach { release ->
            val title = titles.values.single { it.ids == release.ids }
            assertTrue(release.title.startsWith(ScreensCatalog.dotted(title.name) + "."), release.title)
        }
        val shows = titles.values.filter { it.ids.tvdb != null }
        val movies = titles.values.filter { it.ids.tvdb == null }
        assertEquals(14 to 4, shows.size to movies.size)
        // TMDB: TV ids are well under 900 000 today and film ids past a million; TheTVDB's and TVmaze's are far under
        // 9.9 million, and IMDb's under tt99000000.
        shows.forEach { assertTrue(it.ids.tmdb!! in 990_001..999_999 && it.ids.tvdb!! >= 9_900_001 && it.ids.tvmaze!! >= 9_900_001, it.name) }
        movies.forEach { assertTrue(it.ids.tmdb!! >= 9_900_101, it.name) }
        titles.values.forEach { assertTrue(it.ids.imdb!!.removePrefix("tt").toInt() >= 99_000_001, it.name) }
        assertEquals(titles.size, titles.values.map { it.ids.tmdb }.toSet().size, "TMDB ids apart")
        assertEquals(titles.size, titles.values.map { it.ids.imdb }.toSet().size, "IMDb ids apart")
    }

    @Test
    fun `episode releases are episodes the catalogue lists, named in the scene's shape with an invented group`() {
        val shape = Regex("""[A-Z][A-Za-z0-9]*(\.[A-Za-z0-9-]+)+-NZS(DEMO|ALT)""")
        releases.forEach { release ->
            assertTrue(shape.matches(release.title), release.title)
            assertEquals(listOf("${release.title}.mkv"), release.fileNames)
            release.season?.let { season -> assertTrue(season to release.episode!! in titles.values.single { it.ids == release.ids }.episodes, release.title) }
        }
        assertEquals("Kelpmoor.S01E05.REPACK.1080p.WEB-DL.AAC2.0.H.264-NZSALT", releases.single { it.guid == "kelpmoor-s01e05-repack" }.title)
        assertEquals("The.Ninth.Bollard.2022.1080p.BluRay.x264-NZSDEMO", releases.single { it.guid == "the-ninth-bollard-1080p" }.title)
        assertEquals(releases.size, releases.map { it.guid }.toSet().size, "guids apart")
        assertTrue(ScreensCatalog.PLAYABLE.all { guid -> releases.any { it.guid == guid } })
    }

    @Test
    fun `the fake indexer lists an episode's five releases with their own sizes, and the first a phone or a TV picks plays`() = runBlocking<Unit> {
        val nzbs = releases.map { it to fakeNzb(it.guid) }
        FakeIndexerServer(nzbs, apiKey = "k", port = 0).use { server ->
            val indexer = IndexerConfig("screens", "Screens", "http://localhost:${server.port}", "k")
            val kelpmoor = titles.getValue("kelpmoor").ids
            val page = NewznabClient(NewznabClient.defaultHttpClient()).search(indexer, SearchQuery.Tv(ids = TvIds(tvdb = kelpmoor.tvdb), season = 1, episode = 5))
            assertEquals(5, page.items.size)
            page.items.forEach { item -> assertEquals(releases.single { it.guid == item.guid }.sizeBytes, item.sizeBytes, item.title) }

            val facts = page.items.map { ReleaseFacts(it.guid, it.title, it.sizeBytes, it.passworded, it.grabs, mediaInfo = it.mediaInfo) }
            val phone = ReleaseRanker().rank(facts, RankingContext(StaticDeviceCapabilities(videoTenBit = emptySet(), hdrTypes = emptySet())))
            assertTrue(phone.first().facts.id in ScreensCatalog.PLAYABLE, phone.first().facts.id)
            assertContains(phone.first().reasons, ReleaseReason.Proper)
            assertTrue(phone.any { r -> r.reasons.any { it is ReleaseReason.VideoUnsupported } }, "an HEVC 10-bit release the phone can't decode")
            val tv = ReleaseRanker().rank(
                facts,
                RankingContext(StaticDeviceCapabilities(videoTenBitHardwareMaxHeight = mapOf(VideoCodec.Hevc to 2160), hdrTypes = emptySet()), tv = true),
            )
            assertTrue(tv.first().facts.id in ScreensCatalog.PLAYABLE, tv.first().facts.id)
        }
    }

    @Test
    fun `the command seeds the clip whole under the releases that play and lists every other one with each article missing`(@TempDir dir: File) {
        val raw = CopyOnWriteArrayList<String>()
        val missing = CopyOnWriteArrayList<String>()
        val mock = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/mocks/") { exchange ->
                val id = Regex(""""articleId":"<([^>]+)>"""").find(exchange.requestBody.readBytes().decodeToString())!!.groupValues[1]
                when (exchange.requestURI.path) {
                    "/mocks/yenc-body/raw" -> raw += id
                    "/mocks/stat" -> missing += id
                    else -> error("unexpected ${exchange.requestURI.path}")
                }
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            start()
        }
        try {
            val clip = File(dir, "clip.mkv").apply { writeBytes(Random(7).nextBytes(350_000)) }
            val options = mapOf(
                "catalog" to "screens", "video" to clip.path, "mock" to "http://127.0.0.1:${mock.address.port}", "port" to "0",
                "segment" to "100000", "catalog-file" to catalog.path,
            )
            val log = CopyOnWriteArrayList<String>()
            screensIndexer(options, log = { log += it }).use { server ->
                val parts = 4
                releases.forEach { release ->
                    val posted = raw.count { "-${release.guid}-" in it }
                    val absent = missing.count { "-${release.guid}-" in it }
                    if (release.guid in ScreensCatalog.PLAYABLE) assertEquals(parts to 0, posted to absent, release.guid)
                    else assertEquals(0 to parts, posted to absent, release.guid)
                }
                val indexer = IndexerConfig("screens", "Screens", "http://localhost:${server.port}", "fake-indexer-key")
                val films = runBlocking { NewznabClient(NewznabClient.defaultHttpClient()).search(indexer, SearchQuery.Movie(imdb = titles.getValue("the-ninth-bollard").ids.imdb)) }
                assertEquals(listOf("the-ninth-bollard-1080p", "the-ninth-bollard-2160p"), films.items.map { it.guid })
            }
            assertTrue(log.any { "seeded whole" in it && "kelpmoor-s01e05-repack" in it }, log.joinToString("\n"))
        } finally {
            mock.stop(0)
        }
    }

    @Test
    fun `the command refuses to start without a clip, or with a segment size that isn't one`(@TempDir dir: File) {
        // Each case is otherwise whole (the catalogue given, as the test runs in tools/mock-seed), so each fails for its own
        // reason. The mock's address is one where nothing listens: a case that got past its check posts nowhere.
        fun refused(vararg options: Pair<String, String>): String = assertFailsWith<IllegalArgumentException> {
            val whole = mapOf("catalog" to "screens", "port" to "0", "catalog-file" to catalog.path, "mock" to "http://127.0.0.1:1")
            screensIndexer(whole + options, log = {}).close()
        }.message!!
        val clip = File(dir, "clip.mkv").apply { writeBytes(ByteArray(10)) }
        assertContains(refused(), "--video")
        assertContains(refused("video" to File(dir, "none.mkv").path), "none.mkv missing")
        assertContains(refused("video" to clip.path, "segment" to "a lot"), "--segment")
        assertContains(refused("video" to clip.path, "segment" to "0"), "--segment")
    }

    private fun fakeNzb(guid: String) =
        """<?xml version="1.0"?><nzb xmlns="http://www.newzbin.com/DTD/2003/nzb"><file subject="$guid"><segments><segment bytes="1000" number="1">$guid@x</segment></segments></file></nzb>"""
}
