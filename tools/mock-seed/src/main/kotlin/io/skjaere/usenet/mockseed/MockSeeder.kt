package io.skjaere.usenet.mockseed

import io.skjaere.yenc.RapidYenc
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.Base64

/** One file to publish: its name as it appears in yEnc headers and the NZB subject. */
class SeedFile(val name: String, val data: ByteArray)

/**
 * Publishes files to the mock NNTP server as multipart yEnc articles, the way real
 * posters do (`=ybegin part= total=` + `=ypart begin= end=`), and renders the NZB.
 *
 * The mock's own `prepareNzb` posts every chunk as a standalone single-part article.
 * nzb-streamer derives a file's part size from the first segment's `=ypart end`, so
 * single-part articles make any multi-segment file look one segment long.
 */
class MockSeeder(
    private val mockHttpUrl: String,
    private val segmentSize: Int = 750_000,
    private val group: String = "alt.binaries.test",
    private val poster: String = "seed@mock",
) {
    private val http = HttpClient.newHttpClient()

    /**
     * [tag] keeps article ids apart when the same bytes are published as several releases.
     * Parts in [missingParts] are listed in the NZB but never posted, like expired articles.
     */
    fun seed(files: List<SeedFile>, tag: String = "", missingParts: Set<Int> = emptySet()): String {
        val dateSeconds = System.currentTimeMillis() / 1000
        val fileElements = files.mapIndexed { fileIndex, file ->
            val parts = file.data.asList().chunked(segmentSize).map { it.toByteArray() }
            val idPrefix = listOf("seed", tag, sha8(file.data), fileIndex.toString()).filter { it.isNotEmpty() }.joinToString("-")
            var offset = 0L
            val segments = parts.mapIndexed { index, part ->
                val partNumber = index + 1
                val articleId = "$idPrefix-$partNumber@mock"
                val article = yencArticle(file, part, partNumber, parts.size, begin = offset + 1)
                offset += part.size
                if (partNumber in missingParts) postStatMissing("<$articleId>") else postRawArticle("<$articleId>", article)
                """      <segment bytes="${article.size}" number="$partNumber">$articleId</segment>"""
            }
            val subject = "[${fileIndex + 1}/${files.size}] - &quot;${xmlEscape(file.name)}&quot; yEnc (1/${parts.size})"
            buildString {
                appendLine("""  <file poster="$poster" date="$dateSeconds" subject="$subject">""")
                appendLine("    <groups>\n      <group>$group</group>\n    </groups>")
                appendLine("    <segments>")
                segments.forEach(::appendLine)
                appendLine("    </segments>")
                append("  </file>")
            }
        }
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<!DOCTYPE nzb PUBLIC "-//newzBin//DTD NZB 1.1//EN" "http://www.newzbin.com/DTD/nzb/nzb-1.1.dtd">""")
            appendLine("""<nzb xmlns="http://www.newzbin.com/DTD/2003/nzb">""")
            fileElements.forEach(::appendLine)
            appendLine("</nzb>")
        }
    }

    private fun yencArticle(file: SeedFile, part: ByteArray, partNumber: Int, total: Int, begin: Long): ByteArray {
        val end = begin + part.size - 1
        val header = "=ybegin part=$partNumber total=$total line=128 size=${file.data.size} name=${file.name}\r\n" +
            "=ypart begin=$begin end=$end\r\n"
        val body = RapidYenc.encodeEx(part, lineSize = 128, column = 0, isEnd = true).data
        val crc = RapidYenc.crc32(part).toString(16).padStart(8, '0')
        val trailer = "\r\n=yend size=${part.size} part=$partNumber pcrc32=$crc\r\n"
        return header.toByteArray(Charsets.ISO_8859_1) + body + trailer.toByteArray(Charsets.ISO_8859_1)
    }

    private fun postRawArticle(articleId: String, article: ByteArray) {
        val json = """{"articleId":"$articleId","data":"${Base64.getEncoder().encodeToString(article)}"}"""
        val request = HttpRequest.newBuilder(URI.create("$mockHttpUrl/mocks/yenc-body/raw"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "Mock rejected $articleId: HTTP ${response.statusCode()} ${response.body()}"
        }
    }

    // The mock answers STAT for an unknown article with "500 Command not recognized"; real
    // servers say "430 No such article". Register the 430 for articles left out on purpose.
    private fun postStatMissing(articleId: String) = post("/mocks/stat", """{"articleId":"$articleId","exists":false}""")

    private fun post(path: String, json: String) {
        val request = HttpRequest.newBuilder(URI.create("$mockHttpUrl$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "Mock rejected $path: HTTP ${response.statusCode()} ${response.body()}" }
    }

    private fun sha8(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).take(4).joinToString("") { "%02x".format(it) }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
