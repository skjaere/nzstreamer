package io.skjaere.usenet.app

import java.net.URI

/**
 * Debug builds' indexers from local.properties (devIndexer.N.name, .url, .apikey), as the build
 * encodes them into BuildConfig.DEV_INDEXERS: one line per indexer, its name, address and key
 * separated by tabs. A blank name becomes the address's host; an entry without an address or a
 * key is left out.
 */
object DevIndexers {
    data class Entry(val name: String, val baseUrl: String, val apiKey: String)

    fun parse(encoded: String): List<Entry> =
        encoded.lineSequence()
            .map { it.split('\t').map(String::trim) }
            .filter { it.size >= 3 && it[1].isNotEmpty() && it[2].isNotEmpty() }
            .map { (name, url, key) -> Entry(name.ifEmpty { hostOf(url) }, url, key) }
            .toList()

    private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url
}
