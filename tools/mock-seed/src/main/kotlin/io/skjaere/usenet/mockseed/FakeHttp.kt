package io.skjaere.usenet.mockseed

import com.sun.net.httpserver.HttpExchange
import java.net.URLDecoder

// What the fake servers share: reading a request's query, escaping XML, and answering.

/** [raw]'s parameters, decoded; a name given twice keeps its last value. */
internal fun queryOf(raw: String?): Map<String, String> =
    raw.orEmpty().split('&').filter { it.isNotEmpty() }.associate { pair ->
        val (k, v) = (pair.split('=', limit = 2) + "").take(2)
        URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
    }

/** [s] escaped for XML text and attribute values. */
internal fun xmlText(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** Answers with [body] in UTF-8, [status] and [headers]. */
internal fun HttpExchange.reply(body: String, contentType: String = "application/xml", status: Int = 200, headers: Map<String, String> = emptyMap()) {
    val bytes = body.toByteArray()
    responseHeaders.add("Content-Type", "$contentType; charset=utf-8")
    headers.forEach { (name, value) -> responseHeaders.add(name, value) }
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
