package io.skjaere.usenet.app.plugins

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.utils.io.readRemaining
import io.skjaere.usenet.debrid.ServiceHttp
import io.skjaere.usenet.plugin.HttpAnswer
import io.skjaere.usenet.plugin.PluginHttp
import io.skjaere.usenet.plugin.PluginHttpException
import io.skjaere.usenet.plugin.PluginHttpException.Kind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * A plugin's GETs (L631) through the app's service client ([client], ServiceHttp's, which follows no redirect itself), with
 * its User-Agent and its timeouts: [connectTimeoutMs] to connect, and [requestTimeoutMs] for each request, its body
 * included. Its own redirect rule on top: a redirect is followed only to the same scheme, host and port as the address
 * asked, [MAX_REDIRECTS] at most, the plugin's headers going along; any other is refused, so a key in a header never goes
 * to another address. A body is read no further than the request's maxBytes. Each request is one log line naming
 * [version], the host and the status, or the kind of failure: never a header's value, the path, the query or the body
 * (L606), and an exception's message names no address.
 */
class PluginHttpImpl(
    private val version: PluginVersion,
    private val client: () -> HttpClient,
    private val connectTimeoutMs: Long = ServiceHttp.CONNECT_TIMEOUT_MS,
    private val requestTimeoutMs: Long = ServiceHttp.REQUEST_TIMEOUT_MS,
) : PluginHttp {
    override suspend fun get(url: String, headers: Map<String, String>, maxBytes: Int): HttpAnswer {
        val asked = originOf(url) ?: throw failure(Kind.InvalidAddress, null)
        var address = url.trim()
        try {
            repeat(MAX_REDIRECTS + 1) { hop ->
                val answer = withTimeoutOrNull(requestTimeoutMs) { once(address, headers, maxBytes.coerceAtLeast(0)) }
                    ?: throw failure(Kind.Timeout, asked)
                val next = answer.headers["location"]?.takeIf { answer.status in REDIRECTS }
                if (next == null) {
                    log.info("Plugin {} GET {}: {}", version, asked.host, answer.status)
                    return answer
                }
                // Resolved against the hop that gave it, and followed only to the address asked.
                val to = resolve(address, next)
                if (to == null || originOf(to) != asked || hop == MAX_REDIRECTS) throw failure(Kind.RedirectRefused, asked)
                address = to
            }
        } catch (e: PluginHttpException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: TooLarge) {
            throw failure(Kind.TooLarge, asked)
        } catch (e: Exception) {
            // A refused connection, a name that doesn't resolve, a broken TLS, a read cut short: only its kind is said,
            // as a client's own words can hold the address.
            throw failure(if (e.isTimeout()) Kind.Timeout else Kind.Unreachable, asked)
        }
        throw failure(Kind.RedirectRefused, asked)
    }

    /** One request to [address], its answer read whole as long as it is no longer than [maxBytes]. */
    private suspend fun once(address: String, headers: Map<String, String>, maxBytes: Int): HttpAnswer =
        client().prepareGet(address) {
            headers.filterKeys { !it.equals(HttpHeaders.UserAgent, ignoreCase = true) }.forEach { (name, value) -> header(name, value) }
            header(HttpHeaders.UserAgent, ServiceHttp.USER_AGENT)
            // The request's whole time, its body included, is this client's own timeout around it.
            timeout {
                connectTimeoutMillis = connectTimeoutMs
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            }
        }.execute { response ->
            val body = response.bodyAtMost(maxBytes) ?: throw TooLarge()
            HttpAnswer(
                response.status.value,
                response.headers.entries().associate { (name, values) -> name.lowercase() to values.joinToString(", ") },
                body,
            )
        }

    /** The body, read no further than one byte past [limit]; null when it is longer. */
    private suspend fun HttpResponse.bodyAtMost(limit: Int): ByteArray? {
        if ((contentLength() ?: 0) > limit) return null
        val body = bodyAsChannel().readRemaining(limit.toLong() + 1).readByteArray()
        return body.takeIf { it.size <= limit }
    }

    /** [kind]'s exception, its message the kind's own words, logged by the plugin's version, [origin]'s host and the kind. */
    private fun failure(kind: Kind, origin: Origin?): PluginHttpException {
        log.info("Plugin {} GET {}: {}", version, origin?.host ?: "an invalid address", kind)
        return PluginHttpException(kind, words.getValue(kind))
    }

    /** A body longer than the request's maxBytes. */
    private class TooLarge : Exception(null, null, false, false)

    /** An address's scheme, host and port, its port given where it is the scheme's own. */
    private data class Origin(val scheme: String, val host: String, val port: Int)

    companion object {
        /** Redirects followed at most, each to the address asked. */
        const val MAX_REDIRECTS = 3

        private val log = LoggerFactory.getLogger(PluginHttpImpl::class.java)
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)

        private val words = mapOf(
            Kind.Timeout to "No answer in time",
            Kind.Unreachable to "Couldn't reach the address",
            Kind.TooLarge to "The answer is longer than the request's maxBytes",
            Kind.RedirectRefused to "A redirect to another address, or more than three",
            Kind.InvalidAddress to "Not an http or https address",
        )

        /** [url]'s scheme, host and port, for an http or https address the client can ask; null for any other. */
        private fun originOf(url: String): Origin? {
            val uri = try {
                URI(url.trim())
            } catch (e: Exception) {
                return null
            }
            val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
            val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            val port = when (uri.port) {
                -1 -> if (scheme == "https") 443 else 80
                in 1..65_535 -> uri.port
                else -> return null
            }
            return Origin(scheme, host, port)
        }

        /** A redirect's [location] against the [address] that gave it; null where it doesn't read as an address. */
        private fun resolve(address: String, location: String): String? = try {
            URI(address).resolve(location.trim()).toString()
        } catch (e: Exception) {
            null
        }

        /** Whether this, or what caused it, is a timeout: the client's names for one differ by where it ran out. */
        private fun Throwable.isTimeout(): Boolean =
            generateSequence(this) { it.cause }.take(8).any { it is java.io.InterruptedIOException || "Timeout" in it.javaClass.simpleName }
    }
}
