package io.skjaere.usenet.releases

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * One key for a release wherever the app keeps or matches one ([ReleaseKey]): a torrent by its info hash, whichever
 * indexer listed it, and any other by its indexer and guid, as a search result's key has always been. Ids are invented.
 */
class ReleaseKeyTest {
    @Test
    fun `a release without a hash is its indexer and guid`() {
        assertEquals("my-indexer/hl-s02e05", ReleaseKey.of("my-indexer", "hl-s02e05"))
        assertEquals("my-indexer/hl-s02e05", ReleaseKey.of("my-indexer", "hl-s02e05", infoHash = null))
        assertEquals("my-indexer/hl-s02e05", ReleaseKey.of("my-indexer", "hl-s02e05", infoHash = ""))
        // Another indexer's copy is another release.
        assertNotEquals(ReleaseKey.of("my-indexer", "hl-s02e05"), ReleaseKey.of("other-indexer", "hl-s02e05"))
    }

    @Test
    fun `a torrent is its info hash, whatever the indexer`() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        assertEquals("btih:$hash", ReleaseKey.of("home-prowlarr", "btih:$hash", hash))
        assertEquals("btih:$hash", ReleaseKey.of("quarry", "5", hash))
        // As the hex is written in either case.
        assertEquals("btih:$hash", ReleaseKey.of("quarry", "5", hash.uppercase()))
    }

    /**
     * A torrent remembered without its hash (Resume's release, a tried release's row) by the content id its guid is, as a
     * play keeps it (L168, L181): the same key as the search's, so it is matched and kept as one release.
     */
    @Test
    fun `a torrent's guid is its content id, which is its key without the hash`() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        assertEquals("btih:$hash", ReleaseKey.of("home-prowlarr", "btih:$hash"))
        assertEquals(ReleaseKey.of("home-prowlarr", "btih:$hash", hash), ReleaseKey.of("quarry", "btih:$hash"))
        // A guid that only starts the same way is an indexer's own.
        assertEquals("my-indexer/btih:not-a-hash", ReleaseKey.of("my-indexer", "btih:not-a-hash"))
        assertEquals("my-indexer/btih:${hash.uppercase()}x", ReleaseKey.of("my-indexer", "btih:${hash.uppercase()}x"))
    }
}
