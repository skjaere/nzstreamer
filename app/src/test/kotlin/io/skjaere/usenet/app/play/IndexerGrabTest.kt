package io.skjaere.usenet.app.play

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.skjaere.usenet.core.FetchException
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.NewznabClient
import io.skjaere.usenet.newznab.NewznabError
import io.skjaere.usenet.newznab.NewznabException
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.NzbDownload
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A play's grabs from its indexers: the NZB with the archive password the indexer sent beside it, and what a failure means. */
class IndexerGrabTest {
    private val indexer = IndexerConfig("fake", "Fake", "https://indexer.example.org", "key")
    private val item = NewznabItem(
        indexerId = "fake", guid = "hl-s01e01-passwd", title = "Harbor.Lights.S01E01.1080p.BluRay.x264-PASSWD", sizeBytes = 1_000_000_000,
        publishedAt = null, postedAt = null, categories = emptyList(), grabs = null, passworded = true, files = null, tvdbId = null,
        tmdbId = null, imdbId = null, tvmazeId = null, season = 1, episode = 1, coverUrl = null, mediaInfo = null, downloadTemplate = null,
        attributes = emptyMap(),
    )
    private val candidate = PlayCandidate("fake/hl-s01e01-passwd", item.title, "fake")
    private val nzb = "<nzb/>".encodeToByteArray()

    private fun grab(download: suspend (IndexerConfig, NewznabItem) -> NzbDownload) =
        indexerGrab(mapOf(candidate.id to item), mapOf(indexer.id to indexer), download)

    @Test
    fun `the NZB comes with the password its indexer sent beside it`() = runTest {
        val grab = grab { _, _ -> NzbDownload(nzb, "salt-and-signal") }
        val fetched = grab.fetchWithPassword(candidate)
        assertContentEquals(nzb, fetched.bytes)
        assertEquals("salt-and-signal", fetched.password)
        assertContentEquals(nzb, grab.fetch(candidate))
    }

    @Test
    fun `a dropped connection is tried once more, and a used-up allowance blocks the indexer's other releases`() = runTest {
        var asked = 0
        val flaky = grab { _, _ -> if (asked++ == 0) throw NewznabException(NewznabError.Network("Connection reset")) else NzbDownload(nzb, null) }
        assertContentEquals(nzb, flaky.fetchWithPassword(candidate).bytes)
        assertEquals(2, asked)
        val limited = grab { _, _ -> throw NewznabException(NewznabError.DownloadLimitReached("Download limit reached")) }
        assertTrue(assertFailsWith<FetchException> { limited.fetchWithPassword(candidate) }.blocksIndexer)
        val gone = indexerGrab(mapOf(candidate.id to item), emptyMap()) { _, _ -> NzbDownload(nzb, null) }
        assertEquals("Indexer no longer set up", assertFailsWith<FetchException> { gone.fetchWithPassword(candidate) }.reason)
    }

    /** NNTmux says a used-up grab allowance with HTTP 429 and its code 501: the indexer's other releases are skipped all the same. */
    @Test
    fun `an allowance used up and answered with a 429 blocks the indexer's other releases`() = runTest {
        val client = NewznabClient(
            HttpClient(
                MockEngine {
                    respond(
                        """<?xml version="1.0" encoding="UTF-8"?><error code="501" description="Download limit reached"/>""",
                        HttpStatusCode.TooManyRequests,
                        headersOf(HttpHeaders.ContentType, "application/xml"),
                    )
                },
            ) { expectSuccess = false },
        )
        val grab = grab { indexer, item -> client.download(indexer, item) }
        val failed = assertFailsWith<FetchException> { grab.fetchWithPassword(candidate) }
        assertTrue(failed.blocksIndexer, "blocks the indexer")
        assertEquals("Download limit reached", failed.reason)
    }
}
