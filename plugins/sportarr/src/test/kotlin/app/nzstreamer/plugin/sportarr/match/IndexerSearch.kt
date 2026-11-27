package app.nzstreamer.plugin.sportarr.match

import io.skjaere.usenet.plugin.Found
import io.skjaere.usenet.plugin.IndexerLine
import io.skjaere.usenet.plugin.PluginSearch
import io.skjaere.usenet.plugin.ReleaseInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * mock-seed's fake indexer on loopback [port] as the host searches it for a plugin: the words as a text search
 * (t=search&q=), no category, read back from its RSS; one answer, once it's in, every release under [indexer]'s line.
 */
class IndexerSearch(private val port: Int, private val key: String, private val indexer: String) : PluginSearch {
    /** Each search's words, in the order they were asked, and the guids each found. */
    val asked: MutableList<String> = CopyOnWriteArrayList()
    val byWords: MutableMap<String, Set<String>> = ConcurrentHashMap()
    val found: Set<String> get() = byWords.values.flatten().toSet()

    override fun search(words: String, runtimeMinutes: Int?, fresh: Boolean): Flow<Found> = flow {
        asked += words
        val items = withContext(Dispatchers.IO) { Rss.items(get("t=search&q=${URLEncoder.encode(words, Charsets.UTF_8)}")) }
        byWords[words] = items.map { it.guid }.toSet()
        val releases = items.map { ReleaseInfo(it.title, it.size, it.posted, indexer, playable = true, whyNot = null, handle = TestHandle(it.guid)) }
        emit(Found(listOf(IndexerLine(indexer, IndexerLine.State.Found, releases.size, "${releases.size} found")), releases))
    }

    private fun get(query: String): String {
        val connection = URI("http://127.0.0.1:$port/api?$query&apikey=$key").toURL().openConnection() as HttpURLConnection
        try {
            assertEquals(200, connection.responseCode)
            return connection.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            connection.disconnect()
        }
    }

    /** The fake indexer's RSS as the host reads it: each item's guid, title, size and posted time. */
    private object Rss {
        class Item(val guid: String, val title: String, val size: Long, val posted: Instant)

        fun items(xml: String): List<Item> {
            assertTrue("<rss" in xml && "<error" !in xml, xml.take(200))
            return xml.split("<item>").drop(1).map { item ->
                Item(
                    guid = text(item, "guid"),
                    title = unescape(text(item, "title")),
                    size = attribute(item, "size").toLong(),
                    posted = ZonedDateTime.parse(text(item, "pubDate"), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(),
                )
            }
        }

        private fun text(item: String, tag: String): String = item.substringAfter("<$tag").substringAfter('>').substringBefore("</$tag>")

        private fun attribute(item: String, name: String): String = item.substringAfter("""name="$name" value="""").substringBefore('"')

        private fun unescape(text: String): String =
            text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
    }
}
