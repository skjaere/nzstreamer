package io.skjaere.usenet.data

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Whether a play skips a release it remembers as missing, without grabbing it, or tries it: skipped
 * while every provider in use lacks its articles (or its NZB lacks files), tried once a provider it
 * wasn't checked on is in use, once the checks are old, and whenever the user chose it.
 */
class ReleaseChecksTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 100 * day

    private val a = ReleaseChecks.provider("p-a", "news.example.com", 563)
    private val b = ReleaseChecks.provider("p-b", "eu.example.net", 443)

    /** The release found missing on [providers] at [at], as a first play records it. */
    private fun missingOn(vararg providers: String, at: Long = now - 3 * day) =
        ReleaseChecks.record(null, "fake", "hl-s01e01-broken", providers.toList(), incomplete = false, now = at)

    @Test
    fun `missing on every provider in use, it is skipped, as last checked`() {
        val entry = ReleaseChecks.record(missingOn(a, at = now - 5 * day), "fake", "hl-s01e01-broken", listOf(b), incomplete = false, now = now - 3 * day)
        assertEquals(KnownMissing(checkedAt = now - 3 * day, incompleteNzb = false), ReleaseChecks.skip(entry, listOf(a, b), now, chosen = false))
        assertEquals(KnownMissing(checkedAt = now - 3 * day, incompleteNzb = false), ReleaseChecks.skip(missingOn(a), listOf(a), now, chosen = false))
    }

    @Test
    fun `a provider added since is tried, the release with it`() {
        assertNull(ReleaseChecks.skip(missingOn(a), listOf(a, b), now, chosen = false))
        assertNull(ReleaseChecks.skip(missingOn(a), listOf(b), now, chosen = false))
    }

    /** A provider is its settings id with its server: edited to another host or port, it is another, which may have the articles. */
    @Test
    fun `a provider edited to another server is tried as a new one`() {
        val moved = ReleaseChecks.provider("p-a", "eu.example.com", 563)
        val otherPort = ReleaseChecks.provider("p-a", "news.example.com", 119)
        listOf(moved, otherPort).forEach { edited ->
            assertNotEquals(a, edited)
            assertNull(ReleaseChecks.skip(missingOn(a), listOf(edited), now, chosen = false))
        }
        // The same server written otherwise is the same provider.
        assertEquals(a, ReleaseChecks.provider("p-a", " News.Example.COM ", 563))
        // Another provider of the same server is another account, which may have it.
        assertNotEquals(a, ReleaseChecks.provider("p-c", "news.example.com", 563))
    }

    /**
     * Only the providers that answered are recorded: one left out at check time (its login rejected, out of reach) wasn't
     * asked, and one that failed recorded nothing. Either is tried again once it is in use.
     */
    @Test
    fun `a provider left out or failing at check time isn't recorded, and is tried once it is in use`() {
        // b was left out: only a, in use, answered.
        val leftOut = missingOn(a)
        assertEquals(listOf(a), leftOut.missingOn.map { it.provider })
        assertEquals(now - 3 * day, ReleaseChecks.skip(leftOut, listOf(a), now, chosen = false)?.checkedAt)
        assertNull(ReleaseChecks.skip(leftOut, listOf(a, b), now, chosen = false))
        // b failed while a lacked the article: nothing was recorded.
        assertNull(ReleaseChecks.skip(null, listOf(a, b), now, chosen = false))
    }

    @Test
    fun `an NZB that lacks files is skipped whatever providers are in use`() {
        val incomplete = ReleaseChecks.record(null, "fake", "nzb-lacks-a-volume", emptyList(), incomplete = true, now = now - 2 * day)
        assertEquals(emptyList(), incomplete.missingOn)
        listOf(listOf(a), listOf(a, b), listOf(ReleaseChecks.provider("p-new", "new.example.org", 563))).forEach { inUse ->
            assertEquals(KnownMissing(checkedAt = now - 2 * day, incompleteNzb = true), ReleaseChecks.skip(incomplete, inUse, now, chosen = false))
        }
    }

    /**
     * A release found to need a password it didn't come with (its archive encrypted, none in its NZB or from its indexer)
     * is skipped whatever providers are in use, for 30 days, unless the user chooses it; one that played, as a chosen one
     * may once a password comes with it, is tried again. What was known of it besides stays.
     */
    @Test
    fun `a release that needed a password is skipped whatever the providers, for thirty days, unless chosen`() {
        val passworded = ReleaseChecks.record(null, "fake", "hl-s01e01-passwd", emptyList(), incomplete = false, now = now - 2 * day, needsPassword = true)
        assertEquals(TriedRelease("fake/hl-s01e01-passwd", "fake", "hl-s01e01-passwd", emptyList(), null, now - 2 * day, passwordAt = now - 2 * day), passworded)
        listOf(listOf(a), listOf(a, b), emptyList()).forEach { inUse ->
            assertEquals(KnownMissing(now - 2 * day, incompleteNzb = false, passwordNeeded = true), ReleaseChecks.skip(passworded, inUse, now, chosen = false))
        }
        assertNull(ReleaseChecks.skip(passworded, listOf(a), now, chosen = true))
        assertEquals(KnownMissing(now - 2 * day, incompleteNzb = false, passwordNeeded = true), ReleaseChecks.known(passworded, listOf(a), now))
        assertNull(ReleaseChecks.skip(passworded, listOf(a), now - 2 * day + ReleaseChecks.EXPIRY_MS, chosen = false))
        // A later check of the providers keeps it, and it is kept until it expires.
        val alsoMissing = ReleaseChecks.record(passworded, "fake", "hl-s01e01-passwd", listOf(a), incomplete = false, now = now - day)
        assertEquals(now - 2 * day, alsoMissing.passwordAt)
        assertNull(ReleaseChecks.record(passworded, "fake", "hl-s01e01-passwd", listOf(a), incomplete = false, now = now - 2 * day + ReleaseChecks.EXPIRY_MS).passwordAt)
        // Played: nothing is left of it.
        assertNull(ReleaseChecks.found(passworded, listOf(a), now))
        assertEquals(null, ReleaseChecks.found(alsoMissing, listOf(b), now)?.passwordAt)
        assertEquals(listOf(a), ReleaseChecks.found(alsoMissing, listOf(b), now)?.missingOn?.map { it.provider })
    }

    /**
     * W103, L314: a release whose files were a copy of a disc is skipped whatever the providers, before anything else known
     * of it, for 30 days, unless the user chooses it. A later check keeps it until it expires; one that played (a false
     * one) is tried again.
     */
    @Test
    fun `a disc copy is skipped first whatever the providers, for thirty days, unless chosen`() {
        val disc = ReleaseChecks.record(missingOn(a, at = now - 5 * day), "fake", "hl-s01e01-broken", emptyList(), incomplete = false, now = now - 2 * day, discCopy = true)
        assertEquals(now - 2 * day, disc.discAt)
        assertEquals(listOf(a), disc.missingOn.map { it.provider })
        listOf(listOf(a), listOf(a, b), emptyList()).forEach { inUse ->
            assertEquals(KnownMissing(now - 2 * day, incompleteNzb = false, discCopy = true), ReleaseChecks.skip(disc, inUse, now, chosen = false))
        }
        // First, before an NZB lacking files or a password needed, found since.
        val since = ReleaseChecks.record(disc, "fake", "hl-s01e01-broken", emptyList(), incomplete = true, now = now - day, needsPassword = true)
        assertEquals(KnownMissing(now - 2 * day, incompleteNzb = false, discCopy = true), ReleaseChecks.known(since, listOf(a), now))
        assertNull(ReleaseChecks.skip(disc, listOf(a), now, chosen = true))
        // Thirty days on, what was found of the providers is all that is left of it, and that has expired too.
        assertNull(ReleaseChecks.skip(disc, listOf(a), now - 2 * day + ReleaseChecks.EXPIRY_MS, chosen = false))
        // A later check keeps it, until it expires.
        val alsoMissing = ReleaseChecks.record(disc, "fake", "hl-s01e01-broken", listOf(b), incomplete = false, now = now - day)
        assertEquals(now - 2 * day, alsoMissing.discAt)
        assertNull(ReleaseChecks.record(disc, "fake", "hl-s01e01-broken", listOf(b), incomplete = false, now = now - 2 * day + ReleaseChecks.EXPIRY_MS).discAt)
        // Played: it was no disc copy.
        val played = ReleaseChecks.found(alsoMissing, listOf(b), now)
        assertEquals(null, played?.discAt)
        assertEquals(listOf(a), played?.missingOn?.map { it.provider })
        assertNull(ReleaseChecks.found(ReleaseChecks.record(null, "fake", "hl-s01e01-disc", emptyList(), incomplete = false, now = now, discCopy = true), listOf(a), now))
    }

    /** A check counts for 30 days, so a false "missing" from a provider's bad day heals by itself. */
    @Test
    fun `checks expire after thirty days`() {
        val expiry = ReleaseChecks.EXPIRY_MS
        assertEquals(30 * day, expiry)
        assertEquals(now - expiry + 1, ReleaseChecks.skip(missingOn(a, at = now - expiry + 1), listOf(a), now, chosen = false)?.checkedAt)
        assertNull(ReleaseChecks.skip(missingOn(a, at = now - expiry), listOf(a), now, chosen = false))
        val oldNzb = ReleaseChecks.record(null, "fake", "nzb-lacks-a-volume", emptyList(), incomplete = true, now = now - expiry)
        assertNull(ReleaseChecks.skip(oldNzb, listOf(a), now, chosen = false))
        // One provider's check expired: that one is tried again, and the release with it.
        val mixed = ReleaseChecks.record(missingOn(a, at = now - 31 * day), "fake", "hl-s01e01-broken", listOf(b), incomplete = false, now = now - day)
        assertNull(ReleaseChecks.skip(mixed, listOf(a, b), now, chosen = false))
        assertEquals(now - day, ReleaseChecks.skip(mixed, listOf(b), now, chosen = false)?.checkedAt)
        // A new record drops the expired checks.
        assertEquals(listOf(b), mixed.missingOn.map { it.provider })
    }

    /** Chosen by hand (Choose release, a release list's Play), a release is always tried; what is known of it still shows. */
    @Test
    fun `a release the user chose is tried`() {
        assertNull(ReleaseChecks.skip(missingOn(a), listOf(a), now, chosen = true))
        assertEquals(now - 3 * day, ReleaseChecks.known(missingOn(a), listOf(a), now)?.checkedAt)
    }

    /**
     * A release that passed a play's checks with the providers in use isn't missing on them: their checks go, and so does
     * its NZB lacking files, so the next play tries it. A provider not in use keeps its own; with nothing left, the entry goes.
     */
    @Test
    fun `a release that played is no longer known missing on the providers it played with`() {
        val both = ReleaseChecks.record(missingOn(a, at = now - 5 * day), "fake", "hl-s01e01-broken", listOf(b), incomplete = true, now = now - 3 * day)
        val kept = ReleaseChecks.found(both, listOf(b), now)
        assertEquals(TriedRelease("fake/hl-s01e01-broken", "fake", "hl-s01e01-broken", listOf(ProviderCheck(a, now - 5 * day)), null, now - 5 * day), kept)
        assertNull(ReleaseChecks.skip(kept, listOf(b), now, chosen = false))
        assertEquals(now - 5 * day, ReleaseChecks.skip(kept, listOf(a), now, chosen = false)?.checkedAt)
        assertNull(ReleaseChecks.found(both, listOf(a, b), now))
        // A check expired by then is nothing left.
        assertNull(ReleaseChecks.found(both, listOf(b), now - 5 * day + ReleaseChecks.EXPIRY_MS))
    }

    /** An NZB imported from a file has no indexer and guid: nothing is remembered of it. */
    @Test
    fun `an imported NZB has no key, so it is never remembered`() {
        listOf(null to null, "" to "", "fake" to null, null to "hl-s01e01-broken", "fake" to " ").forEach { (indexer, guid) ->
            assertNull(ReleaseChecks.key(indexer, guid), "$indexer/$guid")
        }
        assertEquals("fake/hl-s01e01-broken", ReleaseChecks.key("fake", "hl-s01e01-broken"))
    }

    @Test
    fun `without a provider in use nothing is known to be missing`() {
        assertNull(ReleaseChecks.skip(missingOn(a), emptyList(), now, chosen = false))
    }

    /** Each provider keeps when it was last found lacking the articles; the entry is updated with the last check. */
    @Test
    fun `a record adds the providers checked to what is remembered, each with when`() {
        val first = missingOn(a, at = now - 5 * day)
        assertEquals(TriedRelease("fake/hl-s01e01-broken", "fake", "hl-s01e01-broken", listOf(ProviderCheck(a, now - 5 * day)), null, now - 5 * day), first)
        val second = ReleaseChecks.record(first, "fake", "hl-s01e01-broken", listOf(b), incomplete = false, now = now - 3 * day)
        assertEquals(listOf(ProviderCheck(a, now - 5 * day), ProviderCheck(b, now - 3 * day)), second.missingOn)
        val third = ReleaseChecks.record(second, "fake", "hl-s01e01-broken", listOf(a), incomplete = false, now = now - day)
        assertEquals(listOf(ProviderCheck(a, now - day), ProviderCheck(b, now - 3 * day)), third.missingOn)
        assertEquals(now - day, third.updatedAt)
        assertNull(third.incompleteAt)
        assertEquals(now - day, ReleaseChecks.record(third, "fake", "hl-s01e01-broken", emptyList(), incomplete = true, now = now - day).incompleteAt)
    }
}
