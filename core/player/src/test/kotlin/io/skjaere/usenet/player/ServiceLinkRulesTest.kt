package io.skjaere.usenet.player

import androidx.media3.common.C
import io.skjaere.usenet.player.ServiceLinkRules.Answer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a debrid play's source makes of each answer to its open (L172, W81): a dead link (401, 403, 404, 410, or a page in
 * place of the file) gets one fresh link, and a second dead answer is final; a 200 to a ranged read, or a part of the file
 * other than the one asked, fails at once, as Media3 would otherwise read and drop every byte up to the place; anything
 * else is Media3's to retry. A link over 50 minutes old is replaced before an open. Invented addresses.
 */
class ServiceLinkRulesTest {
    private fun answer(position: Long, status: Int, type: String? = MKV, range: String? = null, refreshed: Boolean = false) =
        ServiceLinkRules.answer(position, status, type, range, refreshed)

    @Test
    fun aDeadLinkGetsOneFreshLinkAndASecondDeadAnswerIsFinal() {
        for (status in listOf(401, 403, 404, 410)) {
            assertEquals(Answer.Refresh("$status"), answer(0, status, "text/plain"), "$status")
            assertEquals(Answer.Refresh("$status"), answer(1_000_000, status, null), "$status at a place")
            assertEquals(Answer.Fail(LinkFailure.NoWorkingLink), answer(0, status, refreshed = true), "$status again")
        }
        // A page in place of the file (a CDN's sign-in or error page), whatever its status says.
        assertEquals(Answer.Refresh("text/html"), answer(0, 200, "text/html; charset=utf-8"))
        assertEquals(Answer.Refresh("text/html"), answer(1_000_000, 206, "TEXT/HTML", "bytes 1000000-4999999/5000000"))
        assertEquals(Answer.Fail(LinkFailure.NoWorkingLink), answer(0, 200, "text/html", refreshed = true))
        assertTrue(ServiceLinkRules.isPage("text/html;charset=UTF-8"))
        assertFalse(ServiceLinkRules.isPage("video/x-matroska"))
        assertFalse(ServiceLinkRules.isPage("application/octet-stream"))
        assertFalse(ServiceLinkRules.isPage(null))
    }

    @Test
    fun a200ToARangedReadOrAnotherPartOfTheFileFailsAtOnce() {
        assertEquals(Answer.Fail(LinkFailure.NoWorkingLink), answer(1_000_000, 200))
        assertEquals(Answer.Fail(LinkFailure.NoWorkingLink), answer(1_000_000, 206, range = "bytes 0-4999999/5000000"))
        assertEquals(Answer.Fail(LinkFailure.NoWorkingLink), answer(1_000_000, 206, range = null), "no Content-Range to tell where it starts")
        assertEquals(Answer.Fail(LinkFailure.NoWorkingLink), answer(0, 206, range = "bytes 500-4999999/5000000"))
        // Not refreshed for: a fresh link wouldn't make the server honour a range.
        assertEquals(Answer.Fail(LinkFailure.NoWorkingLink), answer(1_000_000, 200, refreshed = false))
    }

    @Test
    fun theFileFromWhereItWasAskedOpens() {
        assertEquals(Answer.Opened, answer(1_000_000, 206, range = "bytes 1000000-4999999/5000000"))
        assertEquals(Answer.Opened, answer(1_000_000, 206, range = "bytes 1000000-1999999/*"))
        assertEquals(Answer.Opened, answer(0, 200))
        assertEquals(Answer.Opened, answer(0, 206, range = "bytes 0-4999999/5000000"))
        assertEquals(Answer.Opened, answer(0, 200, "application/octet-stream"), "a CDN that names no container")
    }

    @Test
    fun otherAnswersAreMedia3sToRetry() {
        for (status in listOf(429, 500, 502, 503)) assertEquals(Answer.Retry, answer(1_000, status), "$status")
        // At the file's very end there is nothing to read: as Media3's own HTTP source takes it.
        assertEquals(Answer.AtEnd, answer(5_000_000, 416, range = "bytes */5000000"))
        assertEquals(Answer.Retry, answer(6_000_000, 416, range = "bytes */5000000"))
    }

    @Test
    fun aLinkOver50MinutesOldIsReplacedAndAYoungerOneKept() {
        val now = 10 * 3_600_000L
        assertTrue(ServiceLinkRules.isOld(HttpLink(URL, now - 51 * 60_000L), now))
        assertFalse(ServiceLinkRules.isOld(HttpLink(URL, now - 49 * 60_000L), now))
        assertFalse(ServiceLinkRules.isOld(HttpLink(URL, now), now))
    }

    @Test
    fun theRangeAskedStartsWhereTheReadDoes() {
        assertEquals("bytes=0-", ServiceLinkRules.range(0, C.LENGTH_UNSET.toLong()))
        assertEquals("bytes=1000000-", ServiceLinkRules.range(1_000_000, C.LENGTH_UNSET.toLong()))
        assertEquals("bytes=1000-1499", ServiceLinkRules.range(1_000, 500))
    }

    @Test
    fun aLinksTextNeverHoldsItsAddress() {
        assertFalse("cdn.example.org" in HttpLink(URL, 42, "Copper.Crown.2019.1080p.WEB-DL.mkv").toString())
        assertFalse("Copper" in HttpLink(URL, 42, "Copper.Crown.2019.1080p.WEB-DL.mkv").toString())
    }

    private companion object {
        const val MKV = "video/x-matroska"
        const val URL = "https://cdn.example.org/d/0f3e9a1c/Copper.Crown.2019.1080p.WEB-DL.mkv"
    }
}
