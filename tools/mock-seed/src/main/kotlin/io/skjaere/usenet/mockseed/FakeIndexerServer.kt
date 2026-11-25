package io.skjaere.usenet.mockseed

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A minimal Newznab API over [releases] (`caps`, `search`, `tvsearch`, `movie`, `get`),
 * answering the way common indexers do: XML, error documents with Newznab codes, API and
 * grab counters. Its port is taken when it is made; [serve] can give it its releases later,
 * once they are seeded. It listens on [bind] alone where one is given (the sports indexer, on the loopback address), else on
 * every address. A search finds a release when every word of the query is a whole word of its name; with [repeatedWords]
 * (the sports indexer's) a word the query holds twice must stand twice in the name, so a search dated with three numbers
 * whose day and month are one ("2026 12 12") doesn't find the whole month. Development only.
 */
class FakeIndexerServer(
    releases: List<Pair<FakeRelease, String>>,
    private val apiKey: String,
    port: Int,
    private val grabLimit: Int? = null,
    private val apiLimit: Int = 1000,
    bind: InetAddress? = null,
    private val repeatedWords: Boolean = false,
) : AutoCloseable {
    @Volatile
    private var releases: List<Pair<FakeRelease, String>> = releases
    private val server = HttpServer.create(InetSocketAddress(bind, port), 0)
    private val apiCalls = AtomicInteger()
    private val grabs = AtomicInteger()
    private val now = ZonedDateTime.now()

    val port: Int get() = server.address.port
    val address: InetAddress get() = server.address.address

    init {
        server.createContext("/api") { exchange -> exchange.use { handle(it) } }
        server.executor = Executors.newFixedThreadPool(4)
        server.start()
    }

    /**
     * Whether a search for [query] finds a release named [title]: every word of the query is a whole word of the title,
     * as often as the query has it where [repeatedWords] counts them.
     */
    fun finds(query: String, title: String): Boolean {
        val titleWords = words(title).groupingBy { it }.eachCount()
        return words(query).groupingBy { it }.eachCount().all { (word, times) -> (titleWords[word] ?: 0) >= if (repeatedWords) times else 1 }
    }

    /** Lists [releases] from now on, in place of the ones it had. */
    fun serve(releases: List<Pair<FakeRelease, String>>) {
        this.releases = releases
    }

    override fun close() {
        server.stop(0)
        (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        val params = queryOf(exchange.requestURI.rawQuery)
        if (params["apikey"] != apiKey) return exchange.reply(error(100, "Incorrect user credentials"))
        if (apiCalls.incrementAndGet() > apiLimit) return exchange.reply(error(500, "Request limit reached"))
        when (params["t"]) {
            "caps" -> exchange.reply(caps())
            "search" -> exchange.reply(results(exchange, search(params, typeFilter = null)))
            "tvsearch" -> exchange.reply(results(exchange, search(params, typeFilter = { it.season != null })))
            "movie" -> exchange.reply(results(exchange, search(params, typeFilter = { it.season == null })))
            "get" -> get(exchange, params["id"])
            else -> exchange.reply(error(202, "No such function"))
        }
    }

    private fun search(params: Map<String, String>, typeFilter: ((FakeRelease) -> Boolean)?): List<Pair<FakeRelease, String>> {
        val season = params["season"]?.removePrefix("S")?.toIntOrNull()
        val episode = params["ep"]?.removePrefix("E")?.toIntOrNull()
        val categories = params["cat"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        return releases.filter { (release, _) ->
            (typeFilter?.invoke(release) ?: true) &&
                finds(params["q"].orEmpty(), release.title) &&
                params["tvdbid"]?.let { it.toIntOrNull() == release.ids.tvdb } ?: true &&
                params["tmdbid"]?.let { it.toIntOrNull() == release.ids.tmdb } ?: true &&
                params["imdbid"]?.let { "tt" + it.removePrefix("tt").padStart(7, '0') == release.ids.imdb } ?: true &&
                params["tvmazeid"]?.let { it.toIntOrNull() == release.ids.tvmaze } ?: true &&
                (season == null || release.season == season) &&
                // As on real indexers, an episode search doesn't return season packs.
                (episode == null || release.episode == episode) &&
                (categories.isEmpty() || categories.any { it == release.category || it == release.category / 1000 * 1000 })
        }
    }

    private fun get(exchange: HttpExchange, id: String?) {
        val nzb = releases.firstOrNull { it.first.guid == id }?.second
            ?: return exchange.reply(error(300, "No such item"))
        if (grabLimit != null && grabs.incrementAndGet() > grabLimit) return exchange.reply(error(501, "Download limit reached"))
        exchange.reply(nzb, "application/x-nzb")
    }

    private fun words(text: String): List<String> = text.lowercase().split(NOT_A_WORD).filter { it.isNotEmpty() }

    private fun caps() = """
        <?xml version="1.0" encoding="UTF-8"?>
        <caps>
          <server version="1.0" title="Fake Indexer"/>
          <limits max="100" default="100"/>
          <searching>
            <search available="yes" supportedParams="q"/>
            <tv-search available="yes" supportedParams="q,tvdbid,tmdbid,imdbid,tvmazeid,season,ep"/>
            <movie-search available="yes" supportedParams="q,imdbid,tmdbid"/>
          </searching>
          <categories>
            <category id="2000" name="Movies"><subcat id="${FakeCatalog.MOVIES_HD}" name="HD"/></category>
            <category id="5000" name="TV"><subcat id="${FakeCatalog.TV_HD}" name="HD"/><subcat id="${FakeCatalog.TV_UHD}" name="UHD"/><subcat id="${FakeCatalog.TV_SPORT}" name="Sport"/></category>
          </categories>
        </caps>
    """.trimIndent()

    private fun results(exchange: HttpExchange, items: List<Pair<FakeRelease, String>>): String {
        val host = exchange.requestHeaders.getFirst("Host") ?: "localhost:$port"
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<rss version="2.0" xmlns:newznab="http://www.newznab.com/DTD/2010/feeds/attributes/">""")
            appendLine("<channel><title>Fake Indexer</title>")
            appendLine("""<newznab:apilimits apicurrent="${apiCalls.get()}" apimax="$apiLimit" grabcurrent="${grabs.get()}"${grabLimit?.let { " grabmax=\"$it\"" } ?: ""}/>""")
            appendLine("""<newznab:response offset="0" total="${items.size}"/>""")
            items.forEach { (release, nzb) -> append(item(release, nzb, host)) }
            appendLine("</channel></rss>")
        }
    }

    private fun item(release: FakeRelease, nzb: String, host: String): String {
        val size = release.sizeBytes ?: Regex("""bytes="(\d+)"""").findAll(nzb).sumOf { it.groupValues[1].toLong() }
        val date = (release.postedAt?.atZone(ZoneOffset.UTC) ?: now.minusDays(release.ageDays.toLong())).format(DateTimeFormatter.RFC_1123_DATE_TIME)
        val link = xmlText("http://$host/api?t=get&id=${release.guid}&apikey=$apiKey")
        val attrs = listOfNotNull(
            "category" to release.category.toString(),
            "size" to size.toString(),
            "guid" to release.guid,
            "grabs" to release.grabs.toString(),
            "files" to release.fileNames.size.toString(),
            "password" to if (release.passworded) "1" else "0",
            "usenetdate" to date,
            release.ids.tvdb?.let { "tvdbid" to it.toString() },
            release.ids.tmdb?.let { "tmdbid" to it.toString() },
            release.ids.imdb?.let { "imdb" to it.removePrefix("tt") },
            release.ids.tvmaze?.let { "tvmazeid" to it.toString() },
            release.season?.let { "season" to "S%02d".format(it) },
            release.episode?.let { "episode" to "E%02d".format(it) },
            release.audio?.let { "audio" to it },
            release.language?.let { "language" to it },
        )
        return buildString {
            appendLine("<item>")
            appendLine("  <title>${xmlText(release.title)}</title>")
            appendLine("""  <guid isPermaLink="false">${release.guid}</guid>""")
            appendLine("  <link>$link</link>")
            appendLine("  <pubDate>$date</pubDate>")
            appendLine("""  <enclosure url="$link" length="$size" type="application/x-nzb"/>""")
            attrs.forEach { (name, value) -> appendLine("""  <newznab:attr name="$name" value="${xmlText(value)}"/>""") }
            appendLine("</item>")
        }
    }

    private fun error(code: Int, description: String) =
        """<?xml version="1.0" encoding="UTF-8"?>""" + "\n" + """<error code="$code" description="$description"/>"""

    private companion object {
        val NOT_A_WORD = Regex("[^a-z0-9]+")
    }
}
