package io.skjaere.usenet.app.search

import io.skjaere.usenet.data.ServiceAccount
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.Availability
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGrouper
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * L201, L214, L247 (W84): each release says where it plays from in an outlined tag after its rank: "Usenet", the service
 * of the account a play tries first (the first in the user's order that has it cached and is on), with "+1" when one more
 * has it, or "Torrent" on a hidden one no service will play; none while no service is on, so every list is as today.
 * TalkBack reads "From Usenet", "From TorBox", "From Premiumize, also cached on 1 more service", "Torrent, not playable".
 */
class SourceTagTest {
    private val context = RankingContext(StaticDeviceCapabilities(displayHeight = 1080))
    private val known = ResultGrouper.KnownTitle("The Glass Orchard", 2019, isTv = false, runtimeMinutes = 118)

    /** The accounts on, in the user's order, by id with their services' names: as every device starts, A to Z. */
    private val accounts = listOf(NamedAccount("pm-1", "Premiumize"), NamedAccount("tb-1", "TorBox"))

    /** The same after Move TorBox up. */
    private val torBoxFirst = accounts.reversed()

    private fun item(title: String, hash: String? = null, availability: Availability? = null) = NewznabItem(
        indexerId = if (hash == null) "indexer-a" else "prowlarr", guid = hash?.let { "btih:$it" } ?: title, title = title, sizeBytes = 6_200_000_000,
        publishedAt = null, postedAt = null, categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null,
        imdbId = null, tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null,
        attributes = emptyMap(), infoHash = hash, availability = availability,
    )

    /** [item] ranked as a list of the film ranks it. */
    private fun ranked(item: NewznabItem): RankedItem = ResultGrouper().group(listOf(item), context, known).movieGroup!!.let { (it.playable + it.hidden).single() }

    private val name = "The.Glass.Orchard.2019.1080p.WEB-DL.DDP5.1.H.264-KESTREL"
    private val hash = "5c1d0b4e2a7f9c3e8d6b1a0f4e2c7d9b3a5e8f10"

    @Test
    fun `a Usenet release says Usenet`() {
        assertEquals(SourceTag("Usenet", "From Usenet", dim = false), sourceTag(ranked(item(name)), accounts))
    }

    /** DDC-4: a hidden Usenet release's tag is muted with the rest of its card, as DbPhoneReleases draws the disc image's. */
    @Test
    fun `a hidden Usenet release says Usenet, dimmed as its name is`() {
        val disc = ranked(item("The.Glass.Orchard.2019.COMPLETE.BLURAY-DRIFTWOOD"))
        assertEquals(false, disc.ranked.playable, "a disc image is hidden")
        assertEquals(SourceTag("Usenet", "From Usenet", dim = true), sourceTag(disc, accounts))
    }

    @Test
    fun `a cached torrent says the service a play tries first, and how many more have it`() {
        assertEquals(SourceTag("TorBox", "From TorBox", dim = false), sourceTag(ranked(item(name, hash, Availability.Cached(listOf("tb-1")))), accounts))
        assertEquals(
            SourceTag("Premiumize", "From Premiumize, also cached on 1 more service", dim = false, more = 1),
            sourceTag(ranked(item(name, hash, Availability.Cached(listOf("pm-1", "tb-1")))), accounts),
        )
        // An account switched off since the check: the next that has it, and no "+1" for the one that is off.
        assertEquals(
            SourceTag("TorBox", "From TorBox", dim = false),
            sourceTag(ranked(item(name, hash, Availability.Cached(listOf("pm-1", "tb-1")))), listOf(NamedAccount("tb-1", "TorBox"))),
        )
    }

    /** W84, W85: the first in the user's order, not A to Z, nor the order the check answered in. */
    @Test
    fun `the tag names the first in the user's order, and a move changes the tag alone`() {
        val onBoth = ranked(item(name, hash, Availability.Cached(listOf("pm-1", "tb-1"))))
        assertEquals(SourceTag("TorBox", "From TorBox, also cached on 1 more service", dim = false, more = 1), sourceTag(onBoth, torBoxFirst))
        // The check's answer in the other order changes nothing.
        assertEquals("Premiumize", sourceTag(ranked(item(name, hash, Availability.Cached(listOf("tb-1", "pm-1")))), accounts)?.text)
        // A move: the same release, the same count and look, another name.
        val before = sourceTag(onBoth, accounts)!!
        val after = sourceTag(onBoth, torBoxFirst)!!
        assertEquals(before.copy(text = "TorBox", spoken = "From TorBox, also cached on 1 more service"), after)
    }

    @Test
    fun `TalkBack reads the tag and its count as one`() {
        val three = accounts + NamedAccount("qo-1", "Quiet Orbit")
        val onAll = ranked(item(name, hash, Availability.Cached(listOf("pm-1", "tb-1", "qo-1"))))
        assertEquals(SourceTag("Premiumize", "From Premiumize, also cached on 2 more services", dim = false, more = 2), sourceTag(onAll, three))
        assertEquals("From TorBox", sourceTag(ranked(item(name, hash, Availability.Cached(listOf("tb-1")))), accounts)?.spoken)
    }

    /**
     * L247: Search's season rows and a movie's card play what they show unchosen, so a play passes over an account
     * remembered for the torrent ([ServiceMisses]): their tag passes over it too, and doesn't count it. A release list's play
     * is chosen and tries every account, so its tag doesn't. Remembered on every account, the play passes over the release
     * itself, and its tag names them as the list's does.
     */
    @Test
    fun `an account remembered missed is passed over on a season row, not on a release list`() {
        val onBoth = ranked(item(name, hash, Availability.Cached(listOf("pm-1", "tb-1"))))
        assertEquals(SourceTag("TorBox", "From TorBox", dim = false), sourceTag(onBoth, accounts, passedOver = setOf("pm-1")))
        assertEquals(SourceTag("Premiumize", "From Premiumize, also cached on 1 more service", dim = false, more = 1), sourceTag(onBoth, accounts))
        assertEquals(sourceTag(onBoth, accounts), sourceTag(onBoth, accounts, passedOver = setOf("pm-1", "tb-1")))
    }

    /** DEP-10, L247: the play sheet names only the account trying the release now, with no count. */
    @Test
    fun `the play sheet's tag names the account trying it, never with a count`() {
        val onBoth = ranked(item(name, hash, Availability.Cached(listOf("pm-1", "tb-1"))))
        assertEquals(SourceTag("Premiumize", "From Premiumize", dim = false), playTag(onBoth, accounts, from = null))
        assertEquals(SourceTag("TorBox", "From TorBox", dim = false), playTag(onBoth, accounts, from = "tb-1"))
        assertEquals(SourceTag("TorBox", "From TorBox", dim = false), playTag(onBoth, torBoxFirst, from = null))
        // An account no longer on says nothing: the first that is.
        assertEquals(SourceTag("Premiumize", "From Premiumize", dim = false), playTag(onBoth, accounts, from = "gone-1"))
        assertEquals(SourceTag("Usenet", "From Usenet", dim = false), playTag(ranked(item(name)), accounts, from = null))
        assertNull(playTag(onBoth, emptyList(), from = "tb-1"))
    }

    /**
     * COT-2: until an account's Preparing starts, the sheet names the first the play will try: a play not chosen passes
     * over an account remembered as having failed the torrent, and so does its tag; once one is tried, that one.
     */
    @Test
    fun `before an account is tried the play sheet names the first the play tries, passing over one remembered failed`() {
        val onBoth = ranked(item(name, hash, Availability.Cached(listOf("pm-1", "tb-1"))))
        assertEquals(SourceTag("TorBox", "From TorBox", dim = false), playTag(onBoth, accounts, from = null, passedOver = setOf("pm-1")))
        assertEquals(SourceTag("TorBox", "From TorBox", dim = false), playTag(onBoth, accounts, from = "tb-1", passedOver = setOf("pm-1")))
        // Remembered on every account: a play not chosen skips the release; one chosen tries the first.
        assertEquals(SourceTag("Premiumize", "From Premiumize", dim = false), playTag(onBoth, accounts, from = null, passedOver = setOf("pm-1", "tb-1")))
    }

    /**
     * L247: the order reaches the lists as a list, not a map, whose equality would ignore it: a move of the same accounts
     * is a change, and the tags follow it; the same order again is none.
     */
    @Test
    fun `a move of the same accounts reaches the lists, the same order again doesn't`() = runBlocking<Unit> {
        val pm = ServiceAccount("pm-1", "Premiumize")
        val tb = ServiceAccount("tb-1", "TorBox")
        var on = listOf(pm, tb)
        val writes = flow {
            emit(Unit)
            on = listOf(tb, pm)
            emit(Unit)
            emit(Unit)
        }
        assertEquals(listOf(accounts, torBoxFirst), accountsInOrder(writes) { on }.toList())
    }

    @Test
    fun `a hidden torrent no service will play says Torrent, dimmed`() {
        val torrent = SourceTag("Torrent", "Torrent, not playable", dim = true)
        listOf(Availability.NotCached, Availability.NotChecked, Availability.NotForService).forEach { availability ->
            assertEquals(torrent, sourceTag(ranked(item(name, hash, availability)), accounts), "$availability")
        }
        // Cached only on an account that is off now: nothing here will play it.
        assertEquals(torrent, sourceTag(ranked(item(name, hash, Availability.Cached(listOf("tb-1")))), listOf(NamedAccount("pm-1", "Premiumize"))))
    }

    @Test
    fun `no tag while no service is on`() {
        assertNull(sourceTag(ranked(item(name)), emptyList()))
        assertNull(sourceTag(ranked(item(name, hash, Availability.Cached(listOf("tb-1")))), emptyList()))
    }
}
