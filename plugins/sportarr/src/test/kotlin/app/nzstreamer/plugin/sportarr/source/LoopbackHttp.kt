package app.nzstreamer.plugin.sportarr.source

import io.skjaere.usenet.plugin.HttpAnswer
import io.skjaere.usenet.plugin.PluginHttp
import io.skjaere.usenet.plugin.PluginHttpException
import io.skjaere.usenet.plugin.PluginHttpException.Kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/**
 * The host's HTTP for plugins as the source's tests against the fakes need it, on HttpURLConnection over loopback: the
 * plugin's headers and the app's User-Agent; a redirect followed only to the same scheme, host and port, three at most;
 * the body read no further than one byte past maxBytes; 8 s to connect and 15 s to read; and a request with no answer
 * thrown as the host throws it, by its kind alone. It logs nothing.
 */
class LoopbackHttp : PluginHttp {
    /** Requests made, every redirect counted. */
    val requests = AtomicInteger()

    override suspend fun get(url: String, headers: Map<String, String>, maxBytes: Int): HttpAnswer = withContext(Dispatchers.IO) {
        val asked = originOf(url) ?: throw PluginHttpException(Kind.InvalidAddress, "Not an http or https address")
        var hop = url.trim()
        repeat(MAX_REDIRECTS + 1) { followed ->
            val answer = once(hop, headers, maxBytes)
            val next = answer.headers["location"]?.takeIf { answer.status in REDIRECTS } ?: return@withContext answer
            val to = URI(hop).resolve(next).toString()
            if (originOf(to) != asked || followed == MAX_REDIRECTS) throw PluginHttpException(Kind.RedirectRefused, "A redirect to another address")
            hop = to
        }
        throw PluginHttpException(Kind.RedirectRefused, "More than three redirects")
    }

    private fun once(address: String, headers: Map<String, String>, maxBytes: Int): HttpAnswer {
        requests.incrementAndGet()
        val connection = URI(address).toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val status = connection.responseCode
            if (connection.contentLengthLong > maxBytes) throw PluginHttpException(Kind.TooLarge, "Too large")
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { it.readNBytes(maxBytes + 1) } ?: ByteArray(0)
            if (body.size > maxBytes) throw PluginHttpException(Kind.TooLarge, "Too large")
            val answered = connection.headerFields.filterKeys { it != null }
                .map { (name, values) -> name.lowercase() to values.joinToString(", ") }.toMap()
            return HttpAnswer(status, answered, body)
        } catch (e: PluginHttpException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw PluginHttpException(Kind.Timeout, "No answer in time")
        } catch (e: ConnectException) {
            throw PluginHttpException(Kind.Unreachable, "Couldn't reach the address")
        } catch (e: IOException) {
            throw PluginHttpException(Kind.Unreachable, "Couldn't reach the address")
        } finally {
            connection.disconnect()
        }
    }

    private data class Origin(val scheme: String, val host: String, val port: Int)

    private companion object {
        const val MAX_REDIRECTS = 3
        const val USER_AGENT = "NZStreamer-test"
        val REDIRECTS = setOf(301, 302, 303, 307, 308)

        fun originOf(url: String): Origin? {
            val uri = try {
                URI(url.trim())
            } catch (e: Exception) {
                return null
            }
            val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
            val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return Origin(scheme, host, if (uri.port == -1) (if (scheme == "https") 443 else 80) else uri.port)
        }
    }
}
