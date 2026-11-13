package io.skjaere.usenet.core

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * `usenet://nzb/<id>/file/<index>?name=<name>&size=<bytes>`: identifies one file of a prepared NZB,
 * by its name and size where the URI has them (one built before they were has only the position).
 */
object UsenetUris {
    const val SCHEME = "usenet"

    data class Target(val nzbId: String, val fileIndex: Int, val name: String? = null, val size: Long? = null)

    fun build(nzbId: String, fileIndex: Int, name: String? = null, size: Long? = null): String {
        val query = listOfNotNull(name?.let { "name=" + URLEncoder.encode(it, "UTF-8") }, size?.let { "size=$it" })
        return "$SCHEME://nzb/$nzbId/file/$fileIndex" + if (query.isEmpty()) "" else query.joinToString("&", prefix = "?")
    }

    /** [file] of the NZB [nzbId], by its position, name and size. */
    fun of(nzbId: String, file: FileCandidate) = build(nzbId, file.index, file.name, file.size)

    fun parse(uri: String): Target? {
        val match = Regex("""^$SCHEME://nzb/([0-9a-fA-F]+)/file/(\d+)(?:\?(.*))?$""").matchEntire(uri) ?: return null
        val query = match.groupValues[3].split('&').filter { it.isNotEmpty() }.associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val name = query["name"]?.let { encoded ->
            try {
                URLDecoder.decode(encoded, "UTF-8")
            } catch (e: IllegalArgumentException) {
                return null
            }
        }
        return Target(match.groupValues[1], match.groupValues[2].toInt(), name, query["size"]?.toLongOrNull())
    }
}
