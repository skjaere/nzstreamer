package io.skjaere.usenet.core

import io.ktor.utils.io.readAvailable
import io.skjaere.nntp.YencHeaders
import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.NzbMetadataResponse
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.nzb.NzbFile
import io.skjaere.nzbstreamer.nzb.NzbSegment
import io.skjaere.nzbstreamer.stream.StreamableFile
import io.skjaere.yenc.YencEncoder
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.net.ssl.SSLContext

/**
 * "clip.mkv" in six articles (seg0@test to seg5@test), as an NZB of that one file ready to
 * stream: what the streaming tests serve from [FakeNntpServer] and read back. Another [domain]
 * posts the same clip under other message ids (seg0@domain and on): another release of it.
 */
class TestClip(val segmentSize: Int = 16 * 1024, count: Int = 6, domain: String = "test") {
    val segments = (0 until count).map { i -> ByteArray(segmentSize) { (it * 7 + i).toByte() } }
    val data = segments.reduce(ByteArray::plus)

    val file = NzbFile(
        poster = "test", date = 0, subject = "\"clip.mkv\" yEnc (1/$count)", groups = listOf("alt.binaries.test"),
        segments = segments.indices.map { NzbSegment(segmentSize.toLong(), it + 1, "seg$it@$domain") },
        yencHeaders = YencHeaders(line = 128, size = data.size.toLong(), name = "clip.mkv", partEnd = segmentSize.toLong()),
    )

    val document = NzbDocument(listOf(file))

    val nzb = PreparedNzb(
        id = "ab12",
        title = "Clip",
        metadata = ExtractedMetadata.Raw(NzbMetadataResponse(volumes = listOf("clip.mkv"), obfuscated = false, entries = emptyList()), document),
        candidates = listOf(FileCandidate(0, StreamableFile("clip.mkv", data.size.toLong(), 0, 0, 0, 0))),
    )

    /** The clip's NZB as an indexer hands it over. */
    val nzbBytes: ByteArray = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<nzb xmlns=\"http://www.newzbin.com/DTD/2003/nzb\">\n")
        append("<file poster=\"test\" date=\"0\" subject=\"&quot;clip.mkv&quot; yEnc (1/$count)\">")
        append("<groups><group>alt.binaries.test</group></groups><segments>")
        segments.indices.forEach { append("<segment bytes=\"$segmentSize\" number=\"${it + 1}\">seg$it@$domain</segment>") }
        append("</segments></file>\n</nzb>\n")
    }.encodeToByteArray()

    /** The clip posted in [count] parts of [segmentSize], as a poster encodes it: what prepare reads the file's size from. */
    private val articles = YencEncoder.encodeMultiPart(data, "clip.mkv", segmentSize).map { it.data }

    /** Article [i] as a server sends it: part i + 1 of the clip, yEnc-encoded. */
    fun article(i: Int): ByteArray = articles[i]

    /** Which article a message id names, as BODY and STAT send it ("<seg3@test>" is 3), whatever its domain. */
    fun index(id: String): Int = id.removePrefix("<seg").substringBefore('@').toInt()

    /**
     * A server that has the articles [has] says (by index, in any domain), for BODY and STAT, and answers
     * 430 for the rest, and for articles not of the clip (such as the one a provider check asks for).
     */
    fun server(password: String = "secret", tls: SSLContext? = null, has: (Int) -> Boolean = { true }): FakeNntpServer {
        fun hasArticle(id: String) = id.startsWith("<seg") && has(index(id))
        return FakeNntpServer(
            password = password,
            has = ::hasArticle,
            body = { id -> if (hasArticle(id)) FakeNntpServer.Body.Article(article(index(id))) else FakeNntpServer.Body.Missing },
            tls = tls,
        )
    }
}

/**
 * Everything the stream delivers, and why it ended, as UsenetStreamReader learns it: the failure a
 * read throws, or the channel's close cause once it reads as ended (null at a plain end).
 * [onRead] gets the count read so far after each read.
 */
suspend fun OpenedStream.readToEnd(onRead: (Int) -> Unit = {}): Pair<ByteArray, Throwable?> {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = try {
            channel.readAvailable(buffer)
        } catch (e: IOException) {
            return out.toByteArray() to e
        }
        if (n == -1) return out.toByteArray() to channel.closedCause
        out.write(buffer, 0, n)
        onRead(out.size())
    }
}
