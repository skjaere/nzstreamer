package io.skjaere.usenet.newznab

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class UrlSecurityTest {
    @Test
    fun `classifies indexer addresses`() {
        mapOf(
            "https://indexer.example.org/api" to UrlSecurity.Encrypted,
            "http://indexer.example.org/api" to UrlSecurity.PublicHttp,
            "http://203.0.113.7:9696" to UrlSecurity.PublicHttp,
            "http://192.168.1.20:9696/3" to UrlSecurity.LocalHttp,
            "http://10.0.2.2:8089" to UrlSecurity.LocalHttp,
            "http://172.20.0.5:5076" to UrlSecurity.LocalHttp,
            "http://172.32.0.5:5076" to UrlSecurity.PublicHttp,
            "http://100.101.102.103:9696" to UrlSecurity.LocalHttp,
            "http://nas.local:9696" to UrlSecurity.LocalHttp,
            "http://prowlarr:9696" to UrlSecurity.LocalHttp,
            "http://box.tail1234.ts.net:9696" to UrlSecurity.LocalHttp,
            "http://[fd12:3456::1]:9696" to UrlSecurity.LocalHttp,
            "http://[2001:db8::1]:9696" to UrlSecurity.PublicHttp,
            "ftp://indexer.example.org" to UrlSecurity.Invalid,
            "not a url" to UrlSecurity.Invalid,
        ).forEach { (url, expected) -> assertEquals(expected, UrlSecurity.of(url), url) }
    }

    /** The IPv6 prefixes (fc00::/7, fe80::/10) are for addresses: a host name that starts the same way is on the internet. */
    @Test
    fun `host names that start like a local IPv6 address are public`() {
        mapOf(
            "http://fdx-indexer.example" to UrlSecurity.PublicHttp,
            "http://fc.example.org/api" to UrlSecurity.PublicHttp,
            "http://fd.example.org:9696" to UrlSecurity.PublicHttp,
            "http://fe80.example.net" to UrlSecurity.PublicHttp,
            "http://feb-box.example.net" to UrlSecurity.PublicHttp,
            "http://[fc00::1]:9696" to UrlSecurity.LocalHttp,
            "http://[fe80::1]:9696" to UrlSecurity.LocalHttp,
            "http://[feb0::1]" to UrlSecurity.LocalHttp,
            "http://[::1]:9696" to UrlSecurity.LocalHttp,
            "http://[fec0::1]" to UrlSecurity.PublicHttp,
            "http://[2001:db8::fd]" to UrlSecurity.PublicHttp,
        ).forEach { (url, expected) -> assertEquals(expected, UrlSecurity.of(url), url) }
    }

    @Test
    fun `addresses the client cannot parse are invalid`() {
        mapOf(
            "http://indexer.example.org:96960/api" to UrlSecurity.Invalid,
            "https://indexer.example.org:65536" to UrlSecurity.Invalid,
            "http://192.168.1.20:99999" to UrlSecurity.Invalid,
            "https://indexer.example.org:65535/api" to UrlSecurity.Encrypted,
        ).forEach { (url, expected) -> assertEquals(expected, UrlSecurity.of(url), url) }
    }
}
