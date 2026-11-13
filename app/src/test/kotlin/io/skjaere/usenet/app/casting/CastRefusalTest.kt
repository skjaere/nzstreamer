package io.skjaere.usenet.app.casting

import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.player.LinkFailure
import io.skjaere.usenet.player.LinkUnavailable
import io.skjaere.usenet.player.PlayUris
import io.skjaere.usenet.releases.ReleaseFacts
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * L218 (C1): a debrid cast's head, opened ahead of the LOAD, that a refused link failed fails the load there, before
 * anything is sent to the device, in the words of the refusal's kind, by the account the listing came from, and is
 * remembered as a play's failure is (ServicePlayFailure); a head that failed otherwise sends the LOAD as for Usenet. The
 * card's words hold no host, link, release or account. The film, the account and the hash are invented.
 */
class CastRefusalTest {
    private val launch = PlayerLaunch(
        PlayUris.service(LISTING, 0), "Copper Crown", "2019", WatchKey.movie(900011), WatchTarget(WatchKind.Movie, "Copper Crown", movieTmdbId = 900011),
        SavedRelease("prowlarr", "btih:$HASH", RELEASE, null, 0, "btih:$HASH", "$RELEASE.mkv", 4_000, source = ReleaseFacts.TORRENT),
    )

    /** How Media3's HTTP source words a failure: the link, its host and the file's name in it. */
    private fun http(cause: Throwable) = IOException("Unable to connect to https://cdn.example.org/dl/0123abcd/$RELEASE.mkv", cause)

    private fun card(failure: LinkFailure): CastLoadFailure = CastRefusal.card(assertNotNull(CastRefusal.of(launch, http(LinkUnavailable(failure)), ACCOUNT), "$failure"))

    @Test
    fun `a link refused deep in the head's causes fails the cast in its kind's words, remembered as a play's failure`() {
        val deep = IOException("the head", http(LinkUnavailable(LinkFailure.NotCached)))
        val refused = assertNotNull(CastRefusal.of(launch, deep, ACCOUNT))
        assertEquals("btih:$HASH", refused.candidate.id)
        assertEquals("prowlarr", refused.candidate.indexer)
        assertEquals(listOf("service:$ACCOUNT"), refused.candidate.routes)
        assertEquals(ACCOUNT, refused.account)
        assertEquals(AttemptFailure.NoLongerCached, refused.failure)
        // The release's fault: Try another release leaves it out.
        assertEquals(CastLoadFailure(CastFailKind.CouldNotPlay, "No longer cached", providers = true), CastRefusal.card(refused))
        // The refusal on its own is as good.
        assertEquals(AttemptFailure.NoLongerCached, CastRefusal.of(launch, LinkUnavailable(LinkFailure.NotCached), ACCOUNT)?.failure)
    }

    @Test
    fun `each kind's words, a refused key naming its service from data, and the routes a refusal stops`() {
        val words = mapOf(
            LinkFailure.NoWorkingLink to "No longer available from the service",
            LinkFailure.NotCached to "No longer cached",
            LinkFailure.Removed to "Removed by the debrid service",
            LinkFailure.Limit(20 * 60_000L) to "The debrid service’s limit was reached · try again in about 20 min",
            LinkFailure.Limit() to "The debrid service’s limit was reached · try again later",
            LinkFailure.KeyRefused(ServiceKind.TorBox.displayName) to "TorBox refused your API key",
            LinkFailure.KeyRefused(ServiceKind.Premiumize.displayName) to "Premiumize refused your API key",
            LinkFailure.Down to "The debrid service stopped answering",
            LinkFailure.Refused(tooLarge = true) to "Too large for your debrid plan",
            LinkFailure.Refused() to "The debrid service won’t play this release",
        )
        words.forEach { (failure, said) -> assertEquals(CastLoadFailure(CastFailKind.CouldNotPlay, said, providers = true), card(failure), "$failure") }
        // A limit, a refused key and an outage stop the account for the play, as a play's do.
        listOf(LinkFailure.Limit(), LinkFailure.KeyRefused("TorBox"), LinkFailure.Down).forEach { failure ->
            assertEquals(setOf("service:$ACCOUNT"), CastRefusal.of(launch, LinkUnavailable(failure), ACCOUNT)?.stopped, "$failure")
        }
        assertEquals(emptySet(), CastRefusal.of(launch, LinkUnavailable(LinkFailure.Removed), ACCOUNT)?.stopped)
    }

    @Test
    fun `a head that failed otherwise gives none, and the LOAD goes as today`() {
        assertNull(CastRefusal.of(launch, IOException("connection reset"), ACCOUNT))
        assertNull(CastRefusal.of(launch, http(SocketTimeoutException("timeout")), ACCOUNT))
        assertNull(CastRefusal.of(launch, IllegalStateException("closed"), ACCOUNT))
        // A Usenet launch's head is never a service's refusal.
        assertNull(CastRefusal.of(launch.copy(uri = "usenet://nzb/abc123/file/0"), LinkUnavailable(LinkFailure.Down), ACCOUNT))
    }

    @Test
    fun `no host, link, release or account in any word`() {
        val failures = listOf(
            LinkFailure.NoWorkingLink, LinkFailure.NotCached, LinkFailure.Removed, LinkFailure.Limit(5 * 60_000L), LinkFailure.KeyRefused("TorBox"),
            LinkFailure.Down, LinkFailure.Refused(tooLarge = true), LinkFailure.Refused(),
        )
        for (failure in failures) {
            val said = card(failure).reason.orEmpty()
            listOf("http", "cdn.example.org", "0123abcd", "Copper", RELEASE, HASH, ACCOUNT, LISTING, "btih").forEach { banned ->
                assertFalse(banned in said, "$failure says $banned: $said")
            }
        }
    }

    private companion object {
        const val LISTING = "0f3e9a1c5b7d2e4f6a8b0c1d2e3f4a5b"
        const val ACCOUNT = "a1b2c3d4-tb"
        const val HASH = "c0ffee11c0ffee22c0ffee33c0ffee44c0ffee55"
        const val RELEASE = "Copper.Crown.2019.1080p.WEB-DL"
    }
}
