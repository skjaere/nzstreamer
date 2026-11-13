package io.skjaere.usenet.player

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The links a debrid play's sources share (L172, L208): one asked for per file and held, a refresh in place of a dead one or
 * one over 50 minutes old, and each refresh a log line that says why and how old the link was, never the link, its
 * file's name or the listing's id. Invented addresses.
 */
class HeldLinksTest {
    private var now = 1_000_000_000L
    private val lines = mutableListOf<String>()
    private var made = 0
    private val links = HeldLinks(
        { _, _ -> HttpLink("https://cdn.example.org/d/t${++made}/Harbor.Lights.S01E02.mkv?sig=s3cr3t", now, "Harbor.Lights.S01E02.mkv") },
        { now },
        lines::add,
    )
    private val target = PlayUris.Target.Service("0f3e9a1c5b7d2e4f6a8b0c1d2e3f4a5b", 1)

    @Test
    fun aRefreshSaysWhyAndHowOldTheLinkWasNeverTheLink() {
        val first = links.current(target)
        assertSame(first, links.current(target), "held")
        assertTrue(lines.isEmpty(), "the first link is no refresh: $lines")
        now += 52 * 60_000L
        links.refresh(target, first, "403")
        assertEquals(listOf("debrid: link refreshed (403, after 52 min)"), lines)
        now += 51 * 60_000L
        links.current(target)
        assertEquals("debrid: link refreshed (old, after 51 min)", lines.last())
        assertEquals(3, made)
        lines.forEach { line -> listOf("cdn.example.org", "s3cr3t", "Harbor", "0f3e9a1c").forEach { secret -> assertFalse(secret in line, line) } }
    }

    @Test
    fun aDeadLinkAnotherOpenAlreadyReplacedIsntAskedForAgain() {
        val first = links.current(target)
        val fresh = links.refresh(target, first, "403")
        assertSame(fresh, links.refresh(target, first, "403"), "the other open's fresh link")
        assertEquals(2, made)
    }
}
