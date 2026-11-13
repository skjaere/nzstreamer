package io.skjaere.usenet.newznab

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where an indexer's requests go, to tell whether a key saved for one address may go to another: the host, the port, the
 * path the client asks under and a login before the host, read as the client reads the address and as java.net.URI does,
 * or nothing when they don't agree.
 */
class IndexerEndpointTest {
    private fun of(url: String) = assertNotNull(IndexerEndpoint.of(url), url)

    @Test
    fun `an address reads as its host, port, path and login`() {
        assertEquals(IndexerEndpoint(https = true, host = "indexer.example.org", port = null, path = "", userInfo = null), of(" https://indexer.example.org/api "))
        assertEquals(IndexerEndpoint(https = false, host = "192.168.1.5", port = 9696, path = "/1", userInfo = null), of("http://192.168.1.5:9696/1/api"))
        assertEquals(IndexerEndpoint(https = true, host = "indexer.example.org", port = null, path = "/newznab", userInfo = "u:p"), of("https://u:p@indexer.example.org/newznab/"))
        assertEquals(IndexerEndpoint(https = false, host = "fd12:3456::1", port = 9696, path = "", userInfo = null), of("http://[fd12:3456::1]:9696/api"))
    }

    /** What the client sends to: the default port as none, the host's case and a trailing "/" or one "/api" ignored. */
    @Test
    fun `the same place written another way is the same endpoint`() {
        val same = listOf(
            "https://indexer.example.org/api",
            "https://INDEXER.Example.org/api",
            "https://indexer.example.org:443/api",
            "https://indexer.example.org/api/",
            "https://indexer.example.org",
            "https://indexer.example.org/",
            "https://indexer.example.org/api?t=caps",
            "https://indexer.example.org/api#top",
        )
        same.forEach { assertEquals(of(same.first()), of(it), it) }
        assertEquals(of("http://nas.local:80/api"), of("http://nas.local"))
    }

    @Test
    fun `another host, port, path or login is another endpoint`() {
        val base = of("https://indexer.example.org/api")
        listOf(
            "https://indexer.example.net/api",
            "https://indexer.example.org.evil.example/api",
            "https://indexer.example.org:8443/api",
            "https://indexer.example.org/other/api",
            "https://indexer.example.org/api/api",
            "https://indexer.example.org/API",
            "https://someone@indexer.example.org/api",
        ).forEach { assertNotEquals(base, of(it), it) }
        assertNotEquals(of("http://192.168.1.5:9696/1/api"), of("http://192.168.1.5:9696/2/api"))
    }

    /** Fail closed: an address the two parsers read differently, or that names its host in other letters, is none. */
    @Test
    fun `an address the parsers don't agree on, or can't read, is none`() {
        listOf(
            "indexer.example.org/api",
            "http:/indexer.example.org/api",
            "https://indexer.example.org\\@evil.example/api",
            "https://a@b@indexer.example.org/api",
            "https://ındexer.example.org/api",
            "https://indexer.example.org:99999/api",
            "ftp://indexer.example.org/api",
            "https://indexer.example.org/a b",
            "",
            "not a url",
        ).forEach { assertNull(IndexerEndpoint.of(it), it) }
    }

    /** A key saved for an address may go to the same endpoint, but never from https down to plain http. */
    @Test
    fun `a saved key may go to the same endpoint, not down to plain http`() {
        assertTrue(of("https://indexer.example.org/api").takesKeyOf(of("https://indexer.example.org")))
        assertTrue(of("https://indexer.example.org/api").takesKeyOf(of("http://indexer.example.org/api")), "up to https")
        assertTrue(of("http://nas.local:9696/2").takesKeyOf(of("http://nas.local:9696/2/api")))
        assertFalse(of("http://indexer.example.org/api").takesKeyOf(of("https://indexer.example.org/api")), "down to http")
        assertFalse(of("https://indexer.example.org/other").takesKeyOf(of("https://indexer.example.org/api")))
    }

    /** What a log or a failed assertion prints holds no login, which may be a password, nor a path, which may be a token. */
    @Test
    fun `it prints no login and no path`() {
        val printed = of("https://someone:marker@indexer.example.org/marker-path/api").toString()
        assertFalse("marker" in printed, printed)
    }
}
