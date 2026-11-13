package io.skjaere.usenet.newznab

import java.net.URI

/** How exposed an indexer address leaves the API key. */
enum class UrlSecurity {
    /** https: encrypted. */
    Encrypted,
    /** http to a local-network address: unencrypted, but only on the user's own network. */
    LocalHttp,
    /** http over the internet: anyone on the path can read the API key. Warn. */
    PublicHttp,
    Invalid;

    val warn: Boolean get() = this == PublicHttp

    companion object {
        /**
         * Judged from the address as typed; a public-looking host name that resolves to a
         * private address still counts as public (no DNS lookup here).
         */
        fun of(url: String): UrlSecurity {
            if (NewznabClient.parseBaseUrl(url) == null) return Invalid
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return Invalid
            val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return Invalid
            return when (uri.scheme?.lowercase()) {
                "https" -> Encrypted
                "http" -> if (isLocal(host)) LocalHttp else PublicHttp
                else -> Invalid
            }
        }

        private fun isLocal(host: String): Boolean {
            if (host == "localhost" || LOCAL_SUFFIXES.any(host::endsWith) || '.' !in host && ':' !in host) return true
            val v4 = host.split('.').takeIf { it.size == 4 }?.map { it.toIntOrNull() ?: return false }
            if (v4 != null) {
                val (a, b) = v4
                return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) ||
                    (a == 100 && b in 64..127) // CGNAT, used by Tailscale
            }
            // IPv6 loopback, unique-local (fc00::/7) and link-local (fe80::/10); for an address only, as a
            // host name may start with the same letters (fdx-indexer.example).
            if (':' !in host) return false
            return host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe8") ||
                host.startsWith("fe9") || host.startsWith("fea") || host.startsWith("feb")
        }

        private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal", ".localdomain", ".ts.net")
    }
}
