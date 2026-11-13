package io.skjaere.usenet.newznab

import java.net.URI
import java.net.URISyntaxException

/**
 * Where an indexer address's requests go, to tell whether a key saved for one address may go to another: the [host] in
 * lower case, the [port] (the scheme's default as none), the [path] the client asks under (without a trailing "/" or
 * the one "/api" it adds itself) and a login before the host ([userInfo]). Whether it is [https] is kept apart: the same
 * endpoint over http and https is the same server, but a key may only go up to https ([takesKeyOf]). A query or a
 * fragment doesn't count: neither sends a request elsewhere.
 */
data class IndexerEndpoint(val https: Boolean, val host: String, val port: Int?, val path: String, val userInfo: String?) {
    /** Whether a key saved for [saved] may go here: the same endpoint, and not plain http where it was https. */
    fun takesKeyOf(saved: IndexerEndpoint): Boolean = copy(https = saved.https) == saved && (https || !saved.https)

    // The login may be a password and the path a token: neither is printed.
    override fun toString() = "IndexerEndpoint(https=$https, port=$port, login=${userInfo != null})"

    companion object {
        /**
         * [url]'s endpoint, read as the client reads it ([NewznabClient.parseBaseUrl]) and as java.net.URI does: null
         * when either can't read it, they read it differently, it isn't http or https, or its host isn't ASCII (a
         * look-alike letter mustn't pass for the host it imitates).
         */
        fun of(url: String): IndexerEndpoint? {
            val address = url.trim()
            val client = NewznabClient.parseBaseUrl(address) ?: return null
            val uri = try {
                URI(address)
            } catch (_: URISyntaxException) {
                return null
            }
            val scheme = uri.scheme?.takeIf(::ascii)?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https" || client.protocol.name != scheme) return null
            val host = uri.host?.let(::bare)?.takeIf { it.isNotEmpty() && ascii(it) } ?: return null
            val clientHost = bare(client.host)
            if (!ascii(clientHost) || clientHost.lowercase() != host.lowercase()) return null
            val default = if (scheme == "https") HTTPS_PORT else HTTP_PORT
            val port = if (uri.port == -1) default else uri.port
            if (port != client.port) return null
            val path = path(uri.rawPath.orEmpty())
            if (path != path(client.encodedPath)) return null
            val login = client.encodedUser?.let { user -> user + client.encodedPassword?.let { ":$it" }.orEmpty() }
            if (login != uri.rawUserInfo) return null
            return IndexerEndpoint(scheme == "https", host.lowercase(), port.takeUnless { it == default }, path, login)
        }

        /** The path the client's requests go under: no trailing "/", and the "/api" it adds when it isn't there taken off. */
        private fun path(raw: String): String = raw.trimEnd('/').removeSuffix("/api").trimEnd('/')

        /** An IPv6 address without the brackets one parser keeps and the other may not. */
        private fun bare(host: String) = host.removePrefix("[").removeSuffix("]")

        private fun ascii(text: String) = text.all { it.code < 0x80 }

        private const val HTTP_PORT = 80
        private const val HTTPS_PORT = 443
    }
}
