package io.skjaere.usenet.app.debrid

import io.skjaere.usenet.app.play.MissingReleases
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.DiscKind
import io.skjaere.usenet.core.PipelineEvent
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.core.PlayPipeline
import io.skjaere.usenet.core.ProbeResult
import io.skjaere.usenet.core.TrackProbe
import io.skjaere.usenet.core.TriedReleases
import io.skjaere.usenet.data.ReleaseChecks
import io.skjaere.usenet.data.TriedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.debrid.Account
import io.skjaere.usenet.debrid.DebridService
import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.Prepared
import io.skjaere.usenet.debrid.ServiceError
import io.skjaere.usenet.debrid.ServiceException
import io.skjaere.usenet.debrid.ServiceFile
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.debrid.ServiceLink
import io.skjaere.usenet.debrid.ServiceListing
import io.skjaere.usenet.player.LinkFailure
import io.skjaere.usenet.player.PlayUris
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What plays remember of the torrents service accounts failed (L174): not cached on an account, or refused by it, for an
 * hour in memory; removed by the service for 30 days in the library's tried releases, under the torrent's content id. A
 * play passes over an account so remembered, and skips a release so remembered on every account it plays from without
 * spending an attempt, unless the user chose it; one that plays is forgotten. Through the pipeline, with [ServiceSource]
 * over fake services and the misses routed as a play routes them.
 */
class ServiceMissesTest {
    @TempDir
    lateinit var dir: File

    private var now = Instant.parse("2026-11-05T12:00:00Z").toEpochMilli()

    /** The library's tried releases as the repository keeps them: by each release's key. */
    private inner class Tried : MissingReleases.Store {
        val entries = mutableMapOf<String, TriedRelease>()

        override suspend fun get(ids: Collection<String>) = ids.mapNotNull { entries[it] }.associateBy { it.id }
        override fun observe(ids: Collection<String>): Flow<Map<String, TriedRelease>> = flowOf(ids.mapNotNull { entries[it] }.associateBy { it.id })

        override suspend fun record(indexerId: String, guid: String, providers: Collection<String>, incomplete: Boolean) {
            val id = ReleaseKey.of(indexerId, guid)
            entries[id] = ReleaseChecks.record(entries[id], indexerId, guid, providers, incomplete, now)
        }

        override suspend fun found(indexerId: String, guid: String, providers: Collection<String>) {
            val id = ReleaseKey.of(indexerId, guid)
            val kept = entries[id]?.let { ReleaseChecks.found(it, providers, now) }
            if (kept == null) entries -= id else entries[id] = kept
        }

        override suspend fun recordPasswordNeeded(indexerId: String, guid: String) = error("not a torrent's")

        override suspend fun recordDiscCopy(indexerId: String, guid: String) {
            val id = ReleaseKey.of(indexerId, guid)
            entries[id] = ReleaseChecks.record(entries[id], indexerId, guid, emptyList(), incomplete = false, now, discCopy = true)
        }
    }

    /**
     * An account's client: [cached] lists the hashes it has, each listing [files]; [failure] fails every prepare. How many
     * prepares it was asked.
     */
    private class FakeService(var cached: Set<String>, private val files: List<Pair<String, Long>> = listOf("Copper.Crown.2019.1080p.WEB-DL.mkv" to 4_000L)) : DebridService {
        override val kind = ServiceKind.TorBox
        var failure: ServiceError? = null
        val prepares = mutableListOf<String>()

        override suspend fun test(): Account = error("not asked")
        override suspend fun check(hashes: List<InfoHash>): Map<InfoHash, Boolean> = error("not asked")

        override suspend fun prepare(hash: InfoHash, pick: suspend (ServiceListing) -> ServiceFile?): Prepared {
            prepares += hash.hex
            failure?.let { throw ServiceException(it) }
            if (hash.hex !in cached) throw ServiceException(ServiceError.NotCached)
            val listing = ServiceListing(files.mapIndexed { i, (path, size) -> ServiceFile(i, path, size, "f$i") })
            return Prepared(hash, listing, pick(listing), handle = "t1")
        }

        override suspend fun link(prepared: Prepared, file: ServiceFile) = ServiceLink("https://cdn.example.org/x", Instant.EPOCH)
    }

    private val tried = Tried()
    private val misses = ServiceMisses(tried, now = { now }, zone = { ZoneOffset.UTC })
    private val listings by lazy { DebridListingStore(File(dir, "listings.json"), io = Dispatchers.Unconfined) }
    private val accounts = mutableMapOf<String, FakeService>()

    private fun torrent(hash: String, vararg accounts: String = arrayOf(A)) =
        PlayCandidate("btih:$hash", "Copper.Crown.2019.1080p.WEB-DL", "prowlarr", source = ReleaseFacts.TORRENT, routes = accounts.map { "service:$it" })

    private suspend fun play(vararg candidates: PlayCandidate, chosen: Boolean = false): List<PipelineEvent> {
        val source = ServiceSource({ accounts[it] }, listings, misses = misses, chosen = chosen)
        return PlayPipeline({ source }, TrackProbe { ProbeResult.Playable() }, dispatcher = Dispatchers.Unconfined, tried = misses.forPlay(chosen))
            .play(candidates.toList()).toList()
    }

    private fun attempts(events: List<PipelineEvent>) = events.filterIsInstance<PipelineEvent.AttemptStarted>().map { it.candidate.id }

    @Test
    fun `a release an account no longer has cached is skipped for an hour without an attempt, then tried`() = runBlocking<Unit> {
        accounts[A] = FakeService(emptySet())
        val first = assertIs<PipelineEvent.Exhausted>(play(torrent(HASH)).last())
        assertEquals(listOf(AttemptFailure.NoLongerCached), first.failedAttempts.map { it.second })

        now += 30 * MINUTE
        val again = play(torrent(HASH))
        assertEquals(emptyList(), attempts(again))
        assertEquals(listOf("btih:$HASH" to "No longer cached"), again.filterIsInstance<PipelineEvent.Skipped>().map { it.candidate.id to it.reason })
        assertEquals(1, accounts.getValue(A).prepares.size)

        now += 31 * MINUTE
        assertEquals(listOf("btih:$HASH"), attempts(play(torrent(HASH))))
        assertEquals(2, accounts.getValue(A).prepares.size)
    }

    /**
     * DEP-7, L174: a list marks a torrent a play would skip, as every account it plays from failed it before, in the
     * failure's words ("✕ No longer cached"); not one another account may still play, nor one past its hour.
     */
    @Test
    fun `a list marks the torrents every account they play from failed before, in the failure's words`() = runBlocking<Unit> {
        misses.record(torrent(HASH), A, AttemptFailure.NoLongerCached)
        misses.record(torrent(OTHER), A, AttemptFailure.NoLongerCached)
        val marks = misses.marks(listOf(torrent(HASH), torrent(OTHER, A, B), torrent("1111111111111111111111111111111111111111")))
        assertEquals(mapOf("btih:$HASH" to "No longer cached"), marks)
        now += 61 * MINUTE
        assertEquals(emptyMap(), misses.marks(listOf(torrent(HASH))))
    }

    /**
     * L247: Search's season rows and a movie's card, whose plays aren't chosen, pass over the accounts remembered for a
     * torrent as its play would: by the torrent, those accounts; none for one no account failed, or past its hour.
     */
    @Test
    fun `the accounts a play would pass over for each torrent, by the torrent`() = runBlocking<Unit> {
        misses.record(torrent(HASH, A, B), A, AttemptFailure.NoLongerCached)
        misses.record(torrent(OTHER, A, B), A, AttemptFailure.NoWorkingLink)
        misses.record(torrent(OTHER, A, B), B, AttemptFailure.ServiceRefused(tooLarge = false))
        val passed = misses.passedOver(listOf(torrent(HASH, A, B), torrent(OTHER, A, B), torrent("1111111111111111111111111111111111111111", A, B)))
        assertEquals(mapOf("btih:$HASH" to setOf(A), "btih:$OTHER" to setOf(A, B)), passed)
        now += 61 * MINUTE
        assertEquals(emptyMap(), misses.passedOver(listOf(torrent(HASH, A, B))))
    }

    /**
     * COT-1: what plays remember of the torrents, and forget, is said as it changes: Search, which shows under the player,
     * reads what its rows' tags pass over again. Nothing for a failure no play remembers (a limit stops its route instead).
     */
    @Test
    fun `what is remembered or forgotten is said as it changes, for Search's tags`() = runBlocking<Unit> {
        val start = misses.changes.value
        misses.record(torrent(HASH, A, B), A, AttemptFailure.NoLongerCached)
        assertEquals(start + 1, misses.changes.value, "a failure remembered for an hour")
        misses.record(torrent(HASH, A, B), B, AttemptFailure.ServiceLimit(null, "service:$B"))
        assertEquals(start + 1, misses.changes.value, "a limit isn't remembered")
        misses.record(torrent(OTHER, A, B), B, AttemptFailure.RemovedByService)
        assertEquals(start + 2, misses.changes.value, "one for 30 days")
        misses.forget(torrent(HASH, A, B), A)
        assertEquals(start + 3, misses.changes.value, "one forgotten as the account prepares it")
        assertEquals(emptyMap(), misses.passedOver(listOf(torrent(HASH, A, B))))
    }

    /**
     * DDX-2, L174, L204: a debrid play that fails mid-play is remembered against the account its listing came from, as a
     * play's failures of that account are (no working link, not cached or refused for an hour), so the next play passes it
     * over; a limit, a refused key or an outage stops that account's route instead, which Try another release leaves out.
     * Nothing for a Usenet play, nor for one whose listing this process doesn't hold.
     */
    @Test
    fun `a debrid play's failure is remembered against its account, and the routes it stops go to Try another`() = runBlocking<Unit> {
        val listing = "0f3e9a1c5b7d2e4f6a8b0c1d2e3f4a5b"
        val launch = PlayerLaunch(
            PlayUris.service(listing, 0), "Copper Crown", "2019", WatchKey.movie(900011), WatchTarget(WatchKind.Movie, "Copper Crown", movieTmdbId = 900011),
            SavedRelease("prowlarr", "btih:$HASH", "Copper.Crown.2019.1080p.WEB-DL", null, 0, "btih:$HASH", "Copper.Crown.2019.1080p.WEB-DL.mkv", 4_000, source = ReleaseFacts.TORRENT),
        )
        val accountOf = { id: String -> A.takeIf { id == listing } }
        val gone = assertNotNull(ServicePlayFailure.of(launch, LinkFailure.NoWorkingLink, accountOf))
        assertEquals(emptySet(), gone.stopped)
        misses.record(gone.candidate, gone.account, gone.failure)
        assertEquals(AttemptFailure.NoWorkingLink, misses.known(torrent(HASH), A))
        // The next play passes it over on that account, without an attempt.
        accounts[A] = FakeService(setOf(HASH))
        assertEquals(listOf("btih:$HASH" to "No longer available from the service"), play(torrent(HASH)).filterIsInstance<PipelineEvent.Skipped>().map { it.candidate.id to it.reason })
        assertEquals(emptyList(), accounts.getValue(A).prepares)

        listOf(LinkFailure.Limit(20 * MINUTE), LinkFailure.KeyRefused("TorBox"), LinkFailure.Down).forEach { failure ->
            assertEquals(setOf("service:$A"), ServicePlayFailure.of(launch, failure, accountOf)?.stopped, "$failure")
        }
        assertEquals(emptySet(), ServicePlayFailure.of(launch, LinkFailure.NotCached, accountOf)?.stopped)
        assertEquals(null, ServicePlayFailure.of(launch.copy(uri = PlayUris.service("1f3e9a1c5b7d2e4f6a8b0c1d2e3f4a5b", 0)), LinkFailure.Down, accountOf))
        assertEquals(null, ServicePlayFailure.of(launch.copy(uri = "usenet://nzb/abc123/file/0"), LinkFailure.Down, accountOf))
    }

    @Test
    fun `an account remembered without the release is passed over, and it plays from the other`() = runBlocking<Unit> {
        accounts[A] = FakeService(emptySet())
        accounts[B] = FakeService(setOf(HASH))
        assertIs<PipelineEvent.Succeeded>(play(torrent(HASH, A, B)).last())
        now += 10 * MINUTE
        assertIs<PipelineEvent.Succeeded>(play(torrent(HASH, A, B)).last())
        assertEquals(1, accounts.getValue(A).prepares.size)
        assertEquals(2, accounts.getValue(B).prepares.size)
    }

    @Test
    fun `the user's choice tries a remembered release anyway, and one that plays is forgotten`() = runBlocking<Unit> {
        accounts[A] = FakeService(emptySet())
        play(torrent(HASH))
        // The account has it again; chosen, it is asked, plays, and is no longer remembered without it.
        accounts.getValue(A).cached = setOf(HASH)
        assertIs<PipelineEvent.Succeeded>(play(torrent(HASH), chosen = true).last())
        assertEquals(2, accounts.getValue(A).prepares.size)
        assertEquals(listOf("btih:$HASH"), attempts(play(torrent(HASH))))
    }

    @Test
    fun `a release the service removed is skipped for 30 days, kept in the tried releases by its content id`() = runBlocking<Unit> {
        accounts[A] = FakeService(setOf(HASH)).apply { failure = ServiceError.Removed }
        val first = assertIs<PipelineEvent.Exhausted>(play(torrent(HASH)).last())
        assertEquals(listOf(AttemptFailure.RemovedByService), first.failedAttempts.map { it.second })
        val row = assertNotNull(tried.entries["btih:$HASH"])
        assertEquals(listOf("service:$A"), row.missingOn.map { it.provider })

        now += 29 * DAY
        val skipped = play(torrent(HASH))
        assertEquals(listOf("Removed by the debrid service"), skipped.filterIsInstance<PipelineEvent.Skipped>().map { it.reason })
        now += 2 * DAY
        assertEquals(listOf("btih:$HASH"), attempts(play(torrent(HASH))))
    }

    /**
     * W103, L314, L328: a torrent found a copy of a disc is remembered for 30 days in the tried releases, by its content id,
     * whichever account listed it: a play skips it before asking any account, in a Usenet release's skip words, unless the
     * user chose it. A list marks it with the chip its row reads from the same table, never with a "✕" besides. Without an
     * indexer, as a disc torrent from nowhere, nothing is kept.
     */
    @Test
    fun `a disc torrent is remembered by its content id, and skipped before any account unless chosen`() = runBlocking<Unit> {
        val disc = listOf(
            "Copper.Crown.2019.2160p.UHD.BluRay.HEVC-GRP/BDMV/index.bdmv" to 1L, "Copper.Crown.2019.2160p.UHD.BluRay.HEVC-GRP/BDMV/STREAM/00001.m2ts" to 60_000L,
        )
        accounts[A] = FakeService(setOf(HASH), disc)
        accounts[B] = FakeService(setOf(HASH), disc)
        val first = assertIs<PipelineEvent.Exhausted>(play(torrent(HASH, A, B)).last())
        assertEquals(listOf(AttemptFailure.DiscCopy(DiscKind.BluRay)), first.failedAttempts.map { it.second })
        assertEquals(now, assertNotNull(tried.entries["btih:$HASH"]).discAt)
        assertEquals(1, accounts.getValue(A).prepares.size)

        now += 3 * DAY
        val skipped = play(torrent(HASH, A, B))
        assertEquals(listOf("Skipped: a disc copy (checked 3 days ago)"), skipped.filterIsInstance<PipelineEvent.Skipped>().map { it.reason })
        assertEquals(1, accounts.getValue(A).prepares.size)
        assertEquals(0, accounts.getValue(B).prepares.size)
        assertEquals(emptyMap(), misses.marks(listOf(torrent(HASH, A, B))))
        assertEquals(null, misses.forPlay(chosen = true).knownMissing(torrent(HASH, A, B)))
        now += 28 * DAY
        assertEquals(listOf("btih:$HASH"), attempts(play(torrent(HASH, A, B))))

        val nowhere = PlayCandidate("btih:$OTHER", "Copper.Crown.2019.2160p.UHD.BluRay.HEVC-GRP", null, source = ReleaseFacts.TORRENT, routes = listOf("service:$A"))
        misses.forPlay(chosen = false).discCopy(nowhere)
        assertEquals(null, tried.entries["btih:$OTHER"])
    }

    /**
     * C4: a play remembers each release by its source's own memory, a disc copy as the rest: a torrent's goes to the
     * torrents' table row, a Usenet release's to Usenet's memory, never dropped between them.
     */
    @Test
    fun `a play's memory passes a disc copy on to the memory of the release's source`() = runBlocking<Unit> {
        val usenetDiscs = mutableListOf<String>()
        val usenet = object : TriedReleases by TriedReleases.None {
            override suspend fun discCopy(candidate: PlayCandidate) {
                usenetDiscs += candidate.id
            }
        }
        val both = triedBySource(usenet, misses.forPlay(chosen = false))
        both.discCopy(torrent(HASH))
        both.discCopy(PlayCandidate("prowlarr/cc-2019-drift", "Copper.Crown.2019.2160p.UHD.BluRay.HEVC.TrueHD.7.1-DRIFT", "prowlarr"))
        assertEquals(now, tried.entries["btih:$HASH"]?.discAt)
        assertEquals(listOf("prowlarr/cc-2019-drift"), usenetDiscs)
        assertEquals("Skipped: a disc copy (checked today)", both.knownMissing(torrent(HASH)))
    }

    /** A release the service won't take is that release's alone: the same account's next is tried at once, and the refused one is remembered an hour. */
    @Test
    fun `a release its account refused is skipped for an hour, and the same account's next release is tried`() = runBlocking<Unit> {
        accounts[A] = FakeService(setOf(OTHER))
        val refusing = object : DebridService by accounts.getValue(A) {
            override suspend fun prepare(hash: InfoHash, pick: suspend (ServiceListing) -> ServiceFile?): Prepared =
                if (hash.hex == HASH) throw ServiceException(ServiceError.Refused(tooLarge = true)) else accounts.getValue(A).prepare(hash, pick)
        }
        val source = { chosen: Boolean -> ServiceSource({ refusing }, listings, misses = misses, chosen = chosen) }
        suspend fun playBoth() = PlayPipeline({ source(false) }, TrackProbe { ProbeResult.Playable() }, dispatcher = Dispatchers.Unconfined, tried = misses.forPlay(false))
            .play(listOf(torrent(HASH), torrent(OTHER))).toList()
        val played = assertIs<PipelineEvent.Succeeded>(playBoth().last())
        assertEquals("btih:$OTHER", played.target.candidate.id)
        assertEquals(listOf(AttemptFailure.ServiceRefused(tooLarge = true).text), played.failedAttempts.map { it.second.text })
        now += 20 * MINUTE
        val again = playBoth()
        assertEquals(listOf("Too large for your debrid plan"), again.filterIsInstance<PipelineEvent.Skipped>().map { it.reason })
        assertTrue(again.last() is PipelineEvent.Succeeded)
    }

    private companion object {
        const val HASH = "c0ffee11c0ffee22c0ffee33c0ffee44c0ffee55"
        const val OTHER = "0ddba11000000000000000000000000000000001"
        const val A = "acct7f3e"
        const val B = "acct91c2"
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE
    }
}
