package io.skjaere.usenet.plugin

import java.io.IOException

/**
 * GET requests for a plugin, through NZStreamer's own client: its timeouts (8 s to connect, 15 s for the whole request)
 * and its User-Agent. It logs no header and no body; its log lines name the host and the status alone, so a key in a
 * header (X-Api-Key) never reaches the logs.
 */
interface PluginHttp {
    /**
     * Gets [url] with [headers], reading at most [maxBytes] of its body. A redirect is followed only to the same scheme,
     * host and port, three at most. Any status is an answer, a 404 or a 500 too; a request that gets none throws a
     * [PluginHttpException].
     */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Int = 4 * 1024 * 1024): HttpAnswer
}

/**
 * An answer: its [status], its [headers] by lower-case name (a header sent more than once, its values joined by ", "),
 * and its [body].
 */
class HttpAnswer(val status: Int, val headers: Map<String, String>, val body: ByteArray)

/** A request that got no answer, for the [kind] of reason. Its message names no address. */
class PluginHttpException(val kind: Kind, message: String) : IOException(message) {
    enum class Kind {
        /** No answer in time. */
        Timeout,

        /** The address couldn't be reached: no network, a name that doesn't resolve, a refused connection. */
        Unreachable,

        /** The body was longer than the request's maxBytes. */
        TooLarge,

        /** A redirect to another scheme, host or port, or a fourth one. */
        RedirectRefused,

        /** Not an http or https address. */
        InvalidAddress,
    }
}
