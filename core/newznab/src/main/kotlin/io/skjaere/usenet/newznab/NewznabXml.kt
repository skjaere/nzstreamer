package io.skjaere.usenet.newznab

import io.ktor.http.Url
import io.skjaere.usenet.releases.MediaInfo
import io.skjaere.usenet.releases.ReleaseKey
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URLEncoder
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses Newznab XML (RSS + `newznab:attr`). Attribute elements are matched by local name,
 * so the `newznab:` and `torznab:` prefixes some proxies use both work. A search's items are
 * read as the indexer's [IndexerKind] wants them: NZBs from a Newznab indexer, torrents with a
 * v1 info hash from a Torznab one; the others are left out of the page and counted ([LeftOut]).
 */
internal object NewznabXml {

    fun parseCaps(xml: String): IndexerCaps {
        val root = parse(xml).documentElement
        checkError(root)
        val server = root.child("server")
        val limits = root.child("limits")
        val searching = root.child("searching")?.children()
            ?.mapNotNull { element ->
                val type = SearchType.entries.firstOrNull { it.capsElement == element.localName() } ?: return@mapNotNull null
                if (!element.getAttribute("available").equals("yes", ignoreCase = true)) return@mapNotNull null
                type to element.getAttribute("supportedParams").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            }
            ?.toMap()
            .orEmpty()
        val categories = root.child("categories")?.children("category")?.mapNotNull(::category).orEmpty()
        return IndexerCaps(
            serverTitle = server?.attr("title"),
            serverVersion = server?.attr("version"),
            maxLimit = limits?.attr("max")?.toIntOrNull(),
            defaultLimit = limits?.attr("default")?.toIntOrNull(),
            searching = searching,
            categories = categories,
            limits = apiLimits(root),
        )
    }

    fun parseSearch(xml: String, indexer: IndexerConfig): SearchPage {
        val root = parse(xml).documentElement
        checkError(root)
        val channel = root.child("channel") ?: throw NewznabException(NewznabError.BadResponse("No <channel> in search response"))
        val response = channel.child("response")
        val read = channel.children("item").mapNotNull { item(it, indexer) }
        return SearchPage(
            items = read.mapNotNull { it.item },
            offset = response?.attr("offset")?.toIntOrNull() ?: 0,
            total = response?.attr("total")?.toIntOrNull(),
            limits = apiLimits(channel) ?: apiLimits(root),
            leftOut = read.mapNotNull { it.leftOut }.groupingBy { it }.eachCount(),
            feed = read.map { it.leftOut },
        )
    }

    /** One feed item as read: the [item] kept, or why it was [leftOut]. */
    private class Read(val item: NewznabItem? = null, val leftOut: LeftOut? = null)

    /** Throws for an `<error code description/>` document; NZB downloads can answer with one too. */
    fun checkError(xml: String) {
        if (xml.trimStart().let { it.startsWith("<?xml") || it.startsWith("<error") }) {
            runCatching { parse(xml).documentElement }.getOrNull()?.let(::checkError)
        }
    }

    private fun checkError(root: Element) {
        if (root.localName() == "error") {
            val code = root.attr("code")?.toIntOrNull() ?: 900
            throw NewznabException(NewznabError.fromCode(code, root.attr("description") ?: "Error $code"))
        }
    }

    /** [element] as [indexer]'s kind reads it; null for an item without a title, or an NZB without a guid. */
    private fun item(element: Element, indexer: IndexerConfig): Read? {
        val title = element.child("title")?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val attrs = element.children("attr")
            .mapNotNull { a -> a.attr("name")?.lowercase()?.let { it to (a.attr("value") ?: "") } }
            .groupBy({ it.first }, { it.second })
        fun attr(name: String) = attrs[name]?.firstOrNull()?.takeIf { it.isNotBlank() }
        fun intAttr(name: String) = attr(name)?.toIntOrNull()?.takeIf { it > 0 }
        // Jackett writes these as plain elements; Prowlarr and Newznab indexers as attributes.
        fun intElement(name: String) = element.child(name)?.textContent?.trim()?.toIntOrNull()

        val guidText = element.child("guid")?.textContent?.trim()
        val enclosure = element.child("enclosure")
        val enclosureUrl = enclosure?.attr("url")
        val linkText = element.child("link")?.textContent?.trim()
        val enclosureType = enclosure?.attr("type")?.trim()?.takeIf { it.isNotEmpty() }
        val nzb = enclosureType == null || isNzbType(enclosureType)
        val infoHash: String?
        val guid: String
        when (indexer.kind) {
            IndexerKind.Newznab -> {
                // A torrent feed added as a Newznab indexer is searched, but nothing of it reaches the NZB path (L161).
                if (!nzb) return Read(leftOut = LeftOut.NotNzb)
                infoHash = null
                guid = attr("guid") ?: guidText?.let { guidFromText(it, indexer.apiKey) } ?: return null
            }
            IndexerKind.Torznab -> {
                if (enclosureType != null && nzb) return Read(leftOut = LeftOut.Nzb)
                infoHash = TorrentHashes.of(attr("infohash"), attrs["magneturl"].orEmpty() + listOf(enclosureUrl, linkText, guidText))
                    ?: return Read(leftOut = LeftOut.NoHash)
                // Never the feed's guid: Jackett fills a missing one with the tracker's download link, a passkey in it,
                // or the whole magnet, its trackers in it (L168). The hash is what the release is known by.
                guid = ReleaseKey.TORRENT + infoHash
            }
        }
        val published = element.child("pubDate")?.textContent?.let(::date)
        val mediaInfo = MediaInfo.fromNewznab(
            video = attr("video"),
            audio = attrs["audio"].orEmpty(),
            resolution = attr("resolution"),
            languages = attrs["language"].orEmpty(),
            subs = attrs["subs"].orEmpty(),
        ).takeUnless { it.isEmpty }

        val item = NewznabItem(
            indexerId = indexer.id,
            guid = guid,
            title = title,
            sizeBytes = attr("size")?.toLongOrNull()
                ?: enclosure?.attr("length")?.toLongOrNull()
                ?: element.child("size")?.textContent?.trim()?.toLongOrNull()
                ?: 0,
            publishedAt = published,
            postedAt = attr("usenetdate")?.let(::date) ?: published,
            categories = (attrs["category"].orEmpty() + element.children("category").map { it.textContent })
                .flatMap { it.split(',') }.mapNotNull { it.trim().toIntOrNull() }.distinct(),
            grabs = attr("grabs")?.toIntOrNull() ?: intElement("grabs"),
            passworded = attr("password")?.let { it == "1" || it == "2" || it.equals("true", ignoreCase = true) } == true,
            files = intAttr("files") ?: intElement("files")?.takeIf { it > 0 },
            tvdbId = intAttr("tvdbid"),
            tmdbId = intAttr("tmdbid") ?: intAttr("tmdb"),
            imdbId = (attr("imdbid") ?: attr("imdb"))?.let(::normalizeImdb),
            tvmazeId = intAttr("tvmazeid"),
            season = attr("season")?.removePrefix("S")?.removePrefix("s")?.toIntOrNull(),
            episode = attr("episode")?.removePrefix("E")?.removePrefix("e")?.toIntOrNull(),
            coverUrl = attr("coverurl"),
            mediaInfo = mediaInfo,
            // A torrent keeps no link: D1 fetches no .torrent (L168), and a magnet's trackers or a tracker's own download
            // link can hold a private tracker's passkey.
            downloadTemplate = (enclosureUrl ?: linkText).takeIf { indexer.kind == IndexerKind.Newznab }?.let { redactApiKey(it, indexer.apiKey) },
            attributes = if (indexer.kind == IndexerKind.Torznab) attrs - "magneturl" else attrs,
            infoHash = infoHash,
            enclosureType = enclosureType,
        )
        return Read(item = item)
    }

    /** An NZB's media type, whatever its case or parameters. */
    private fun isNzbType(type: String) = type.substringBefore(';').trim().equals("application/x-nzb", ignoreCase = true)

    /**
     * Some indexers use a details URL as the guid. The id is its `guid` or `id` query parameter
     * (a `details.php?guid=` link), else, without a query, its last path segment. A URL with
     * some other query is kept whole (key redacted): its last segment is the same for every item.
     */
    private fun guidFromText(text: String, apiKey: String): String? = when {
        !text.startsWith("http://") && !text.startsWith("https://") -> text.takeIf { it.isNotEmpty() }
        '?' !in text -> text.trimEnd('/').substringAfterLast('/').takeIf { it.isNotEmpty() }
        else -> runCatching { Url(text).parameters }.getOrNull()
            ?.let { query -> listOf("guid", "id").firstNotNullOfOrNull { name -> query[name]?.takeIf { it.isNotBlank() } } }
            ?: redactApiKey(text, apiKey)
    }

    // The charset-name overload of URLEncoder.encode: the Charset one needs Android API 33.
    fun redactApiKey(url: String, apiKey: String): String =
        if (apiKey.isEmpty()) url else listOf(apiKey, URLEncoder.encode(apiKey, Charsets.UTF_8.name()))
            .distinct()
            .fold(url) { acc, key -> acc.replace(key, NewznabItem.API_KEY_PLACEHOLDER) }

    private fun normalizeImdb(value: String): String? =
        value.trim().removePrefix("tt").toIntOrNull()?.takeIf { it > 0 }?.let { "tt%07d".format(Locale.ROOT, it) }

    private fun category(element: Element): Category? {
        val id = element.attr("id")?.toIntOrNull() ?: return null
        return Category(id, element.attr("name") ?: id.toString(), element.children("subcat").mapNotNull(::category))
    }

    private fun apiLimits(parent: Element): ApiLimits? = parent.child("apilimits")?.let {
        ApiLimits(
            apiCurrent = it.attr("apicurrent")?.toIntOrNull(),
            apiMax = it.attr("apimax")?.toIntOrNull(),
            grabCurrent = it.attr("grabcurrent")?.toIntOrNull(),
            grabMax = it.attr("grabmax")?.toIntOrNull(),
        )
    }

    private fun date(text: String): Instant? = text.trim().let { value ->
        runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
            .recoverCatching { ZonedDateTime.parse(value).toInstant() }
            .recoverCatching { Instant.ofEpochSecond(value.toLong()) }
            .getOrNull()
    }

    private val factory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        // Responses come from servers the user typed in: no DTDs, no external entities.
        listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            XMLConstants.FEATURE_SECURE_PROCESSING to true,
        ).forEach { (feature, value) -> runCatching { setFeature(feature, value) } }
    }

    private fun parse(xml: String): Document = try {
        factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    } catch (e: Exception) {
        throw NewznabException(NewznabError.BadResponse("Not a Newznab XML response: ${e.message}"), e)
    }

    private fun Element.localName(): String = localName ?: tagName.substringAfter(':')
    private fun Element.attr(name: String): String? = getAttribute(name).takeIf { it.isNotEmpty() }
    private fun Element.children(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
    private fun Element.children(localName: String) = children().filter { it.localName() == localName }
    private fun Element.child(localName: String) = children().firstOrNull { it.localName() == localName }
}
