package io.skjaere.usenet.app.search

import io.skjaere.usenet.data.IndexerRecord
import io.skjaere.usenet.data.IndexerWithKey
import io.skjaere.usenet.data.TorrentIndexerRecord
import io.skjaere.usenet.data.TorrentIndexerWithKey
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.IndexerKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * What a search asks (L194, L166): the Usenet indexers always, and the torrent indexers only while a service account is
 * on and debrid shows, each read as its kind.
 */
class IndexerConfigsTest {
    private val newznab = listOf(
        IndexerWithKey(IndexerRecord("u1", "Indexer A", "https://a.example/api"), "KA"),
        IndexerWithKey(IndexerRecord("u2", "Indexer B", "https://b.example/api", enabled = false), "KB"),
    )
    private val torznab = listOf(
        TorrentIndexerWithKey(TorrentIndexerRecord("t1", "Home Prowlarr", "http://192.168.1.20:9696/1"), "KP"),
        TorrentIndexerRecord("t2", "Quarry", "http://192.168.1.20:9696/2", useWithService = false).let { TorrentIndexerWithKey(it, "KQ") },
    )
    private val usenetOnly = listOf(
        IndexerConfig("u1", "Indexer A", "https://a.example/api", "KA"),
        IndexerConfig("u2", "Indexer B", "https://b.example/api", "KB", enabled = false),
    )

    @Test
    fun `without a service account on, the torrent indexers aren't searched`() {
        assertEquals(usenetOnly, IndexerConfigs.searchable(newznab, torznab, serviceOn = false, shown = true))
    }

    @Test
    fun `while debrid is hidden, the torrent indexers aren't searched whatever was saved`() {
        assertEquals(usenetOnly, IndexerConfigs.searchable(newznab, torznab, serviceOn = true, shown = false))
    }

    @Test
    fun `with a service on, the torrent indexers follow the usenet ones, each as a torznab indexer with its switch`() {
        val configs = IndexerConfigs.searchable(newznab, torznab, serviceOn = true, shown = true)
        assertEquals(usenetOnly, configs.take(2))
        assertEquals(
            listOf(
                IndexerConfig("t1", "Home Prowlarr", "http://192.168.1.20:9696/1", "KP", kind = IndexerKind.Torznab),
                IndexerConfig("t2", "Quarry", "http://192.168.1.20:9696/2", "KQ", kind = IndexerKind.Torznab, useWithService = false),
            ),
            configs.drop(2),
        )
        assertEquals(listOf(IndexerKind.Newznab, IndexerKind.Newznab, IndexerKind.Torznab, IndexerKind.Torznab), configs.map { it.kind })
    }

    /** L552: every search, play, download and replay reads its indexers here, so each sends its own User-Agent, of either kind. */
    @Test
    fun `each indexer's own User-Agent is carried, for both kinds`() {
        val usenet = listOf(newznab[0].let { it.copy(record = it.record.copy(userAgent = "ExampleReader/2.1")) }, newznab[1])
        val torrents = listOf(torznab[0], torznab[1].let { it.copy(record = it.record.copy(userAgent = "QuarryFetch/1.0")) })
        val configs = IndexerConfigs.searchable(usenet, torrents, serviceOn = true, shown = true)
        assertEquals(listOf("ExampleReader/2.1", "", "", "QuarryFetch/1.0"), configs.map { it.userAgent })
        assertEquals(listOf(IndexerKind.Newznab, IndexerKind.Newznab, IndexerKind.Torznab, IndexerKind.Torznab), configs.map { it.kind })
    }

    @Test
    fun `a key that couldn't be read is sent empty, as a usenet indexer's is`() {
        val unreadable = listOf(TorrentIndexerWithKey(TorrentIndexerRecord("t1", "Home Prowlarr", "http://192.168.1.20:9696/1"), null))
        assertEquals("", IndexerConfigs.searchable(emptyList(), unreadable, serviceOn = true, shown = true).single().apiKey)
    }
}
