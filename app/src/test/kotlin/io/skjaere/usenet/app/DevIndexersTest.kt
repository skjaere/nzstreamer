package io.skjaere.usenet.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DevIndexersTest {
    @Test
    fun `nothing configured gives no indexers`() {
        assertEquals(emptyList<DevIndexers.Entry>(), DevIndexers.parse(""))
    }

    @Test
    fun `each line is a name, an address and a key, in the order given`() {
        val encoded = "First\thttps://one.example/api\tkey-1\nSecond\thttps://two.example\tkey-2"
        assertEquals(
            listOf(
                DevIndexers.Entry("First", "https://one.example/api", "key-1"),
                DevIndexers.Entry("Second", "https://two.example", "key-2"),
            ),
            DevIndexers.parse(encoded),
        )
    }

    @Test
    fun `a missing name is the address's host`() {
        assertEquals(listOf(DevIndexers.Entry("one.example", "https://one.example/api", "key")), DevIndexers.parse("\thttps://one.example/api\tkey"))
    }

    /** L182: devTorrentIndexer.N.name, .url and .apikey are encoded as devIndexer.N's are, and read the same way. */
    @Test
    fun `torrent indexers parse as indexers do`() {
        assertEquals(
            listOf(DevIndexers.Entry("Quarry", "http://192.0.2.7:9696/1", "quarry-dev-key"), DevIndexers.Entry("192.0.2.7", "http://192.0.2.7:9117/api", "jackett-dev-key")),
            DevIndexers.parse("Quarry\thttp://192.0.2.7:9696/1\tquarry-dev-key\n\thttp://192.0.2.7:9117/api\tjackett-dev-key"),
        )
    }

    @Test
    fun `an entry without an address or a key is left out`() {
        assertEquals(emptyList<DevIndexers.Entry>(), DevIndexers.parse("No address\t\tkey\nNo key\thttps://x.example\t\n\n"))
    }
}
