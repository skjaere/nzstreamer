package io.skjaere.usenet.app.search

import io.skjaere.usenet.app.debrid.ServiceMisses
import io.skjaere.usenet.app.play.MissingReleases
import io.skjaere.usenet.app.play.playCandidate
import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.data.TriedRelease
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.Availability
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import io.skjaere.usenet.search.GroupedResults
import io.skjaere.usenet.search.ResultGrouper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * L247 (FGX-6): Search's season rows and a movie's card, whose plays aren't chosen, pass over in their tags the accounts a
 * play of their best release would pass over, remembered as having failed its torrent ([passedOverShown]); a release
 * list's tag, its play chosen, keeps every account. Over the app's own [ServiceMisses] and lists Search's grouper ranks.
 */
class SearchPassedOverTest {
    private val context = RankingContext(StaticDeviceCapabilities(displayHeight = 1080))

    /** The accounts on, in the user's order: as every device starts, A to Z. */
    private val accounts = listOf(NamedAccount("pm-1", "Premiumize"), NamedAccount("tb-1", "TorBox"))

    /** The library's tried releases, none: what a play remembers for an hour is kept in memory. */
    private object NoneTried : MissingReleases.Store {
        override suspend fun get(ids: Collection<String>): Map<String, TriedRelease> = emptyMap()
        override fun observe(ids: Collection<String>): Flow<Map<String, TriedRelease>> = flowOf(emptyMap())
        override suspend fun record(indexerId: String, guid: String, providers: Collection<String>, incomplete: Boolean) = Unit
        override suspend fun found(indexerId: String, guid: String, providers: Collection<String>) = Unit
        override suspend fun recordPasswordNeeded(indexerId: String, guid: String) = Unit
        override suspend fun recordDiscCopy(indexerId: String, guid: String) = Unit
    }

    private val misses = ServiceMisses(NoneTried)

    private fun item(title: String, hash: String? = null) = NewznabItem(
        indexerId = if (hash == null) "indexer-a" else "prowlarr", guid = hash?.let { "btih:$it" } ?: title, title = title, sizeBytes = 2_400_000_000,
        publishedAt = null, postedAt = null, categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null,
        imdbId = null, tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null,
        attributes = emptyMap(), infoHash = hash, availability = hash?.let { Availability.Cached(listOf("pm-1", "tb-1")) },
    )

    private fun GroupedResults.row(label: String) = sections.flatMap { it.groups }.single { it.label == label }.best!!

    @Test
    fun `a season row's tag passes over an account a play failed its torrent on, and a release list's doesn't`() = runBlocking<Unit> {
        val shown = ResultGrouper().group(
            listOf(
                item("Harbor.Lights.S02E05.1080p.WEB-DL.AAC2.0.H.264-GOOD", "1f4c7a2e9b3d5f6081a2c4e6b8d0f1a3c5e7b9d2"),
                item("Harbor.Lights.S02E06.1080p.WEB-DL.AAC2.0.H.264-GOOD", "7d2e4f6a8b0c1e3f5a7b9c2d4e6f8a0b1c3d5e7f"),
                item("Harbor.Lights.S02E07.1080p.WEB-DL.AAC2.0.H.264-GOOD"),
            ),
            context,
        )
        val failed = shown.row("S02E05")
        val other = shown.row("S02E06")
        assertEquals(emptyMap(), passedOverShown(listOf(shown), misses), "nothing remembered")
        // A play of the row failed on Premiumize, which its next play passes over without a call.
        misses.record(failed.playCandidate, "pm-1", AttemptFailure.NoLongerCached)
        val passed = passedOverShown(listOf(shown), misses)
        assertEquals(mapOf(failed.key to setOf("pm-1")), passed)
        assertEquals(SourceTag("TorBox", "From TorBox", dim = false), sourceTag(failed, accounts, passed[failed.key].orEmpty()))
        assertEquals("From Premiumize, also cached on 1 more service", sourceTag(other, accounts, passed[other.key].orEmpty())?.spoken)
        // Its release list's play is chosen and tries every account: that tag still names the first, with its count.
        assertEquals("From Premiumize, also cached on 1 more service", sourceTag(failed, accounts)?.spoken)
    }

    @Test
    fun `a movie's card does too, read with the preview's list beside the results'`() = runBlocking<Unit> {
        val film = ResultGrouper().group(
            listOf(item("The.Glass.Orchard.2019.1080p.WEB-DL.DDP5.1.H.264-KESTREL", "5c1d0b4e2a7f9c3e8d6b1a0f4e2c7d9b3a5e8f10")),
            context, ResultGrouper.KnownTitle("The Glass Orchard", 2019, isTv = false, runtimeMinutes = 118),
        )
        val preview = ResultGrouper().group(listOf(item("Harbor.Lights.S02E05.1080p.WEB-DL.AAC2.0.H.264-GOOD", "1f4c7a2e9b3d5f6081a2c4e6b8d0f1a3c5e7b9d2")), context)
        val card = film.movieGroup!!.best!!
        misses.record(card.playCandidate, "pm-1", AttemptFailure.NoWorkingLink)
        misses.record(preview.row("S02E05").playCandidate, "tb-1", AttemptFailure.NoLongerCached)
        val passed = passedOverShown(listOf(film, preview), misses)
        assertEquals(mapOf(card.key to setOf("pm-1"), preview.row("S02E05").key to setOf("tb-1")), passed)
        assertEquals("From TorBox", sourceTag(card, accounts, passed[card.key].orEmpty())?.spoken)
        assertEquals("From Premiumize", sourceTag(preview.row("S02E05"), accounts, passed[preview.row("S02E05").key].orEmpty())?.spoken)
    }
}
