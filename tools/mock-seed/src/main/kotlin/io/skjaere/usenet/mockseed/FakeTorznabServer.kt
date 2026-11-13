package io.skjaere.usenet.mockseed

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The fake indexer's Torznab mode (L189): torrents ([FakeTorrents], plus [extra]) through two feeds shaped as the
 * servers users run answer, so the app's reading of each can be tried from an emulator (http://10.0.2.2:8096):
 * - Prowlarr's, at `/prowlarr/1/api`: `torznab:attr` with `infohash`, `seeders`, `peers`, `files` and `grabs`, and
 *   the enclosure `application/x-bittorrent` at its own `/download` link, as Prowlarr wraps every link;
 * - Jackett's, at `/jackett/api/v2.0/indexers/fake/results/torznab/api`: the raw magnet as `link`, `enclosure` and
 *   `magneturl`, and `size`, `files` and `grabs` as plain elements; TV searched by name, season and episode alone.
 *
 * Both answer newest first, by age, and honour `offset` and `limit` (L238), as the torrent indexer's Test reads the newest
 * 10.
 *
 * It seeds nothing and needs no mock NNTP server. It listens on the loopback address alone, which the emulators reach as
 * 10.0.2.2. Development only.
 */
class FakeTorznabServer(
    private val apiKey: String,
    port: Int,
    private val extra: FakeTorrent? = null,
    private val fail: Fail? = null,
    /** How long each search's answer waits; caps and links answer at once. */
    private val delayMs: Long = 0,
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    enum class Feed(val path: String) {
        Prowlarr("/prowlarr/1"),
        Jackett("/jackett/api/v2.0/indexers/fake/results/torznab"),
    }

    enum class Fail {
        /** `--fail 429-900`: Jackett's answer to a site's rate limit, its code 900 in an HTTP 429 with `Retry-After: 30`. */
        Limit429Code900,
    }

    /** One search as it arrived: which feed, its function (`search`, `tvsearch`, `movie`) and its `cat` as sent. */
    data class Search(val feed: Feed, val function: String, val categories: String?)

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
    private val recorded = CopyOnWriteArrayList<Search>()
    private val linksFetched = AtomicInteger()
    private val torrents = FakeTorrents.all + listOfNotNull(extra)
    private val now = ZonedDateTime.now()

    val port: Int get() = server.address.port
    val address: InetAddress get() = server.address.address

    /** Every search asked so far, in order. */
    val searches: List<Search> get() = recorded.toList()

    /** Download links asked for (`.torrent` files): the app fetches none in D1 (L168), so a run should leave this 0. */
    val downloads: Int get() = linksFetched.get()

    init {
        Feed.entries.forEach { feed -> server.createContext("${feed.path}/api") { exchange -> exchange.use { api(it, feed) } } }
        server.createContext("${Feed.Prowlarr.path}/download") { exchange -> exchange.use { download(it, "apikey") } }
        server.createContext("/jackett/dl/") { exchange -> exchange.use { download(it, "jackett_apikey") } }
        // A delayed search mustn't hold the caps or another search up.
        server.executor = Executors.newCachedThreadPool()
        server.start()
    }

    override fun close() {
        server.stop(0)
        (server.executor as? ExecutorService)?.shutdownNow()
    }

    private fun api(exchange: HttpExchange, feed: Feed) {
        val params = queryOf(exchange.requestURI.rawQuery)
        if (params["apikey"] != apiKey) return exchange.reply(error(100, "Invalid API Key"), status = 401)
        val function = params["t"]
        if (function == "caps") return exchange.reply(caps(feed))
        val typeFilter: ((FakeTorrent) -> Boolean)? = when (function) {
            "search" -> null
            "tvsearch" -> { t -> t.season != null }
            "movie" -> { t -> t.season == null }
            else -> return exchange.reply(error(202, "No such function"))
        }
        recorded += Search(feed, function, params["cat"])
        if (fail == Fail.Limit429Code900) {
            log("torznab: ${feed.name} $function: answered 429 (code 900)")
            return exchange.reply(error(900, "Request limit reached for this indexer"), status = 429, headers = mapOf("Retry-After" to "30"))
        }
        if (delayMs > 0) Thread.sleep(delayMs)
        val found = search(params, typeFilter)
        log("torznab: ${feed.name} $function cat=${params["cat"] ?: "none"}: ${found.size} items")
        exchange.reply(results(exchange, feed, found))
    }

    private fun download(exchange: HttpExchange, keyName: String) {
        if (queryOf(exchange.requestURI.rawQuery)[keyName] != apiKey) return exchange.reply(error(100, "Invalid API Key"), status = 401)
        linksFetched.incrementAndGet()
        log("torznab: a .torrent link was fetched")
        exchange.reply("This fake serves no .torrent files.", contentType = "text/plain", status = 404)
    }

    private fun search(params: Map<String, String>, typeFilter: ((FakeTorrent) -> Boolean)?): List<FakeTorrent> {
        val words = params["q"]?.lowercase()?.split(Regex("[^a-z0-9]+"))?.filter { it.isNotEmpty() }.orEmpty()
        val season = params["season"]?.removePrefix("S")?.toIntOrNull()
        val episode = params["ep"]?.removePrefix("E")?.toIntOrNull()
        val categories = params["cat"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        val offset = params["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val limit = params["limit"]?.toIntOrNull()?.coerceAtLeast(0) ?: Int.MAX_VALUE
        return torrents.filter { t ->
            val titleWords = t.title.lowercase().split(Regex("[^a-z0-9]+")).toSet()
            (typeFilter?.invoke(t) ?: true) &&
                words.all { it in titleWords } &&
                params["tvdbid"]?.let { it.toIntOrNull() == t.ids.tvdb } ?: true &&
                params["tmdbid"]?.let { it.toIntOrNull() == t.ids.tmdb } ?: true &&
                params["imdbid"]?.let { "tt" + it.removePrefix("tt").padStart(7, '0') == t.ids.imdb } ?: true &&
                (season == null || t.season == season) &&
                // As on the sites, "S01E02" finds no season pack.
                (episode == null || t.episode == episode) &&
                // A standard parent takes its children in; a site category only itself, as Jackett matches them.
                (categories.isEmpty() || categories.any { it == t.category || it == t.category / 1000 * 1000 || it == FakeTorrents.SITE_CATEGORY })
        }.sortedBy { it.ageDays }.drop(offset).take(limit)
    }

    private fun caps(feed: Feed): String {
        val (title, tv, movie) = when (feed) {
            Feed.Prowlarr -> Triple("Prowlarr", "q,season,ep,imdbid,tvdbid,tmdbid", "q,imdbid,tmdbid")
            Feed.Jackett -> Triple("Jackett", "q,season,ep", "q")
        }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <caps>
              <server title="$title"/>
              <limits default="100" max="100"/>
              <searching>
                <search available="yes" supportedParams="q"/>
                <tv-search available="yes" supportedParams="$tv"/>
                <movie-search available="yes" supportedParams="$movie"/>
              </searching>
              <categories>
                <category id="2000" name="Movies"><subcat id="2040" name="Movies/HD"/><subcat id="2045" name="Movies/UHD"/></category>
                <category id="5000" name="TV"><subcat id="5040" name="TV/HD"/><subcat id="5045" name="TV/UHD"/></category>
                <category id="${FakeTorrents.SITE_CATEGORY}" name="Video"/>
              </categories>
            </caps>
        """.trimIndent()
    }

    private fun results(exchange: HttpExchange, feed: Feed, items: List<FakeTorrent>): String {
        val host = exchange.requestHeaders.getFirst("Host") ?: "127.0.0.1:$port"
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom" xmlns:torznab="http://torznab.com/schemas/2015/feed">""")
            appendLine("<channel><title>Fake Tracker</title>")
            items.forEach { t ->
                val n = torrents.indexOf(t) + 1
                append(if (feed == Feed.Prowlarr) prowlarrItem(t, n, host) else jackettItem(t, n, host))
            }
            appendLine("</channel></rss>")
        }
    }

    private fun prowlarrItem(t: FakeTorrent, n: Int, host: String): String {
        val link = xmlText("http://$host${Feed.Prowlarr.path}/download?apikey=$apiKey&link=t$n&file=${encode(t.title)}")
        val attrs = common(t) + listOfNotNull(
            t.hash?.let { "infohash" to it },
            "files" to t.files.size.toString(),
            "grabs" to t.grabs.toString(),
            t.ids.tvdb?.let { "tvdbid" to it.toString() },
            t.ids.tmdb?.let { "tmdbid" to it.toString() },
            t.ids.imdb?.let { "imdb" to it.removePrefix("tt") },
        )
        return item(t, guid = "https://tracker.example.org/torrent/$n", link = link, attrs = attrs) {
            appendLine("""  <prowlarrindexer id="1" type="public">Fake Tracker</prowlarrindexer>""")
        }
    }

    private fun jackettItem(t: FakeTorrent, n: Int, host: String): String {
        val magnet = magnet(t)
        val link = magnet ?: "http://$host/jackett/dl/fake/?jackett_apikey=$apiKey&path=t$n&file=${encode(t.title)}"
        val guid = when {
            t.passkeyGuid -> "https://tracker.example.org/download/$n?torrent_pass=f00dfacef00dfacef00dfacef00dface"
            magnet != null -> magnet
            else -> "https://tracker.example.org/torrent/$n/${t.title.lowercase().replace('.', '-')}/"
        }
        val attrs = common(t) + listOfNotNull(t.hash?.takeIf { t.form == HashForm.Hex }?.let { "infohash" to it }, magnet?.let { "magneturl" to it })
        return item(t, guid = xmlText(guid), link = xmlText(link), attrs = attrs) {
            appendLine("""  <jackettindexer id="fake">Fake Tracker</jackettindexer>""")
            appendLine("  <type>public</type>")
            appendLine("  <files>${t.files.size}</files>")
            appendLine("  <grabs>${t.grabs}</grabs>")
        }
    }

    /** The magnet Jackett gives for [t], with its name and a tracker as it builds them, in [t]'s [HashForm]; null for none. */
    private fun magnet(t: FakeTorrent): String? {
        val xt = when (t.form) {
            HashForm.Hex -> listOf("urn:btih:${t.hash}")
            HashForm.Base32 -> listOf("urn:btih:${FakeTorrents.base32(requireNotNull(t.hash))}")
            HashForm.Hybrid -> listOf("urn:btih:${t.hash}", "urn:btmh:1220${t.v2Hash}")
            HashForm.V2Only -> listOf("urn:btmh:1220${t.v2Hash}")
            HashForm.None -> return null
        }
        return "magnet:?" + (xt.map { "xt=$it" } + "dn=${encode(t.title)}" + "tr=${encode(TRACKER)}").joinToString("&")
    }

    private fun common(t: FakeTorrent) = listOf(
        "category" to t.category.toString(),
        "category" to FakeTorrents.SITE_CATEGORY.toString(),
        "seeders" to t.seeders.toString(),
        "peers" to t.peers.toString(),
        "downloadvolumefactor" to "0",
        "uploadvolumefactor" to "1",
    )

    private fun item(t: FakeTorrent, guid: String, link: String, attrs: List<Pair<String, String>>, extra: StringBuilder.() -> Unit) = buildString {
        val date = now.minusDays(t.ageDays.toLong()).format(DateTimeFormatter.RFC_1123_DATE_TIME)
        appendLine("<item>")
        appendLine("  <title>${xmlText(t.title)}</title>")
        appendLine("  <guid>$guid</guid>")
        extra()
        appendLine("  <pubDate>$date</pubDate>")
        appendLine("  <size>${t.sizeBytes}</size>")
        appendLine("  <link>$link</link>")
        appendLine("  <category>${t.category}</category>")
        appendLine("  <category>${FakeTorrents.SITE_CATEGORY}</category>")
        appendLine("""  <enclosure url="$link" length="${t.sizeBytes}" type="application/x-bittorrent"/>""")
        attrs.forEach { (name, value) -> appendLine("""  <torznab:attr name="$name" value="${xmlText(value)}"/>""") }
        appendLine("</item>")
    }

    private fun error(code: Int, description: String) =
        """<?xml version="1.0" encoding="UTF-8"?>""" + "\n" + """<error code="$code" description="$description"/>"""

    private fun encode(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    private companion object {
        const val TRACKER = "udp://tracker.example.org:6969/announce"
    }
}
