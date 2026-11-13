package io.skjaere.usenet.app.debrid

import androidx.media3.exoplayer.upstream.Loader
import io.skjaere.usenet.app.link.SheetMirror
import io.skjaere.usenet.app.play.MissingReleases
import io.skjaere.usenet.app.play.playCandidate
import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.DiscKind
import io.skjaere.usenet.core.FileChooser
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.PipelineEvent
import io.skjaere.usenet.core.PipelineStep
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.core.PlayPipeline
import io.skjaere.usenet.core.PlaybackTarget
import io.skjaere.usenet.core.ProbeResult
import io.skjaere.usenet.core.StepStatus
import io.skjaere.usenet.core.TrackProbe
import io.skjaere.usenet.core.UnreadableFile
import io.skjaere.usenet.core.UsenetError
import io.skjaere.usenet.data.TriedRelease
import io.skjaere.usenet.data.ServiceAccount
import io.skjaere.usenet.debrid.Account
import io.skjaere.usenet.debrid.AddWatch
import io.skjaere.usenet.debrid.DebridService
import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.Prepared
import io.skjaere.usenet.debrid.ServiceError
import io.skjaere.usenet.debrid.ServiceException
import io.skjaere.usenet.debrid.ServiceFile
import io.skjaere.usenet.debrid.ServiceGate
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.debrid.ServiceLink
import io.skjaere.usenet.debrid.ServiceListing
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.player.LinkFailure
import io.skjaere.usenet.player.LinkUnavailable
import io.skjaere.usenet.player.PlayUris
import io.skjaere.usenet.releases.Availability
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.RankedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * A release a debrid service has cached, played through the pipeline (L203, L179, W80): one Preparing step for whatever the
 * service needs, the file picked from its listing before anything is added, the accounts that have it tried in order within
 * the one attempt, Opening getting the file's link, and the listing kept by an opaque id. Over fake services that record
 * each call in order.
 */
class ServiceSourceTest {
    @TempDir
    lateinit var dir: File

    /**
     * An account's client: the files it has cached, by hash; [failure] fails its prepare, [linkFailure] its link, and with
     * [addsAgain] a link finds the torrent gone from the account and adds it again (torrent t77, its files' ids g0 on).
     * Its calls in order, and the handles links were asked by. An add is told to [watch], as TorBox's client tells its
     * account's watch of an add it proves this device's (L232); the torrents it is asked to remove are [removed].
     */
    private class FakeService(override val kind: ServiceKind, private val cached: Map<String, List<Pair<String, Long>>>) : DebridService {
        var failure: ServiceError? = null
        var linkFailure: ServiceError? = null
        var addsAgain = false
        var watch: AddWatch? = null
        val calls = mutableListOf<String>()
        val linkedBy = mutableListOf<String?>()
        val removed = CopyOnWriteArrayList<String>()

        override suspend fun test(): Account = error("not asked")
        override suspend fun check(hashes: List<InfoHash>): Map<InfoHash, Boolean> = error("not asked")

        override suspend fun prepare(hash: InfoHash, pick: suspend (ServiceListing) -> ServiceFile?): Prepared {
            calls += "list"
            failure?.let { throw ServiceException(it) }
            val files = cached[hash.hex] ?: throw ServiceException(ServiceError.NotCached)
            val listing = ServiceListing(files.mapIndexed { i, (path, size) -> ServiceFile(i, path, size) })
            val picked = pick(listing)
            calls += "picked ${picked?.name}"
            if (picked == null) return Prepared(hash, listing, null)
            calls += "add"
            watch?.added(hash, "t42")
            val withIds = ServiceListing(listing.files.map { it.copy(id = "f${it.index}") })
            return Prepared(hash, withIds, withIds.files[picked.index], handle = "t42")
        }

        override suspend fun remove(handle: String) {
            removed += handle
        }

        override suspend fun link(prepared: Prepared, file: ServiceFile): ServiceLink {
            calls += "link ${file.name}"
            linkedBy += prepared.handle
            linkFailure?.let { throw ServiceException(it) }
            if (!addsAgain) return ServiceLink(LINK + file.name, Instant.EPOCH)
            val files = ServiceListing(prepared.listing.files.map { it.copy(id = "g${it.index}") })
            return ServiceLink(LINK + file.name, Instant.EPOCH, Prepared(prepared.hash, files, prepared.picked?.let { files.files[it.index] }, handle = "t77"))
        }
    }

    private val store by lazy { DebridListingStore(File(dir, "debrid/listings.json"), io = Dispatchers.Unconfined) }
    private val accounts = mutableMapOf<String, FakeService>()
    private val asked = mutableListOf<List<String>>()

    private fun source(chooser: FileChooser = FileChooser.None) = ServiceSource({ id -> accounts[id] }, store, chooser)

    private fun pipeline(source: ServiceSource, probe: TrackProbe = TrackProbe { ProbeResult.Playable() }) =
        PlayPipeline({ source }, probe, maxAttempts = 5, dispatcher = Dispatchers.Unconfined)

    private fun torrent(hash: String, title: String = FILM, vararg accounts: String = arrayOf(A)) =
        PlayCandidate("btih:$hash", title, "prowlarr", source = ReleaseFacts.TORRENT, routes = accounts.map { "service:$it" })

    private suspend fun play(vararg candidates: PlayCandidate, picker: FilePicker = FilePicker.Largest, chooser: FileChooser = FileChooser.None) =
        pipeline(source(chooser)).play(candidates.toList(), picker).toList()

    @Test
    fun `a cached release prepares on its first account, the file picked before anything is added, its listing kept`() = runBlocking<Unit> {
        accounts[A] = FakeService(ServiceKind.TorBox, mapOf(HASH to film))
        val events = play(torrent(HASH))
        val played = assertIs<PipelineEvent.Succeeded>(events.last())
        val target = assertIs<ServiceTarget>(played.target)
        assertEquals("Copper.Crown.2019.1080p.WEB-DL.mkv", target.file.name)
        // The listing was handed to the pick before the add, and Opening asked for the picked file's link.
        assertEquals(listOf("list", "picked Copper.Crown.2019.1080p.WEB-DL.mkv", "add", "link Copper.Crown.2019.1080p.WEB-DL.mkv"), accounts.getValue(A).calls)
        // A torrent's three steps, Preparing first; Opening's detail is the file's name.
        val done = events.filterIsInstance<PipelineEvent.Step>().filter { it.status is StepStatus.Done }
        assertEquals(listOf(PipelineStep.Prepare, PipelineStep.Open, PipelineStep.ProbeTracks), done.map { it.step })
        assertEquals("Copper.Crown.2019.1080p.WEB-DL.mkv", (done[1].status as StepStatus.Done).detail)
        // The listing is the release's, by its content id; the store keeps it by an opaque id, which the URI names alone.
        assertEquals("btih:$HASH", target.listing.id)
        val kept = assertNotNull(store.get(target.listingId))
        assertEquals(A, kept.account)
        assertEquals("t42", kept.prepared.handle)
        assertEquals("debrid://${target.listingId}/file/1", target.uri)
        assertFalse(HASH in target.uri || A in target.uri, target.uri)
        // Kept for the player: on disk, with the service's ids, never the link.
        target.keep()
        val onDisk = File(dir, "debrid/listings.json").readText()
        assertTrue("f1" in onDisk, onDisk)
        assertFalse(LINK in onDisk, onDisk)
        assertEquals(kept, DebridListingStore(File(dir, "debrid/listings.json"), io = Dispatchers.Unconfined).get(target.listingId))
    }

    @Test
    fun `a pack's episode is picked by its name, and a pack without it fails with nothing added`() = runBlocking<Unit> {
        accounts[A] = FakeService(ServiceKind.TorBox, mapOf(HASH to pack))
        val played = assertIs<PipelineEvent.Succeeded>(play(torrent(HASH, PACK), picker = FilePicker.Episode(1, 2)).last())
        assertEquals("Harbor.Lights.S01E02.mkv", played.target.file.name)

        accounts[A] = FakeService(ServiceKind.TorBox, mapOf(HASH to pack))
        val exhausted = assertIs<PipelineEvent.Exhausted>(play(torrent(HASH, PACK), picker = FilePicker.Episode(1, 4)).last())
        assertEquals(AttemptFailure.NoMatchingEpisode(1, 4), exhausted.failedAttempts.single().second)
        assertEquals(listOf("list", "picked null"), accounts.getValue(A).calls)
    }

    @Test
    fun `a torrent whose video is packed in RAR volumes can't be streamed, and nothing is added`() = runBlocking<Unit> {
        accounts[A] = FakeService(ServiceKind.Premiumize, mapOf(HASH to listOf("Crown/crown.rar" to 500L, "Crown/crown.r00" to 500L, "Crown/crown.nfo" to 1L)))
        val exhausted = assertIs<PipelineEvent.Exhausted>(play(torrent(HASH)).last())
        assertEquals("Can't be streamed: its video is packed in RAR archives", exhausted.failedAttempts.single().second.text)
        assertEquals(listOf("list", "picked null"), accounts.getValue(A).calls)
    }

    /** DEP-1: its sample clip beside the volumes doesn't play as the film, nor is the torrent added for it. */
    @Test
    fun `a torrent packed in RAR volumes with a sample beside them can't be streamed either, and nothing is added`() = runBlocking<Unit> {
        val volumes = listOf("Crown/crown.rar" to 500L, "Crown/crown.r00" to 500L, "Crown/crown.nfo" to 1L, "Crown/Sample/crown-sample.mkv" to 60L)
        accounts[A] = FakeService(ServiceKind.TorBox, mapOf(HASH to volumes))
        val exhausted = assertIs<PipelineEvent.Exhausted>(play(torrent(HASH)).last())
        assertEquals("Can't be streamed: its video is packed in RAR archives", exhausted.failedAttempts.single().second.text)
        assertEquals(listOf("list", "picked null"), accounts.getValue(A).calls)
    }

    /**
     * W103, L311: a torrent of a disc's files fails at Preparing once its listing is known, with nothing added. The account
     * prepared nothing, so nothing remembered of it is forgotten, and no other account is tried: each lists the same files.
     */
    @Test
    fun `a torrent of a disc's files fails at Preparing with nothing added, nothing forgotten and no other account tried`() = runBlocking<Unit> {
        val disc = listOf(
            "$DISC/BDMV/index.bdmv" to 1L, "$DISC/BDMV/PLAYLIST/00800.mpls" to 2L, "$DISC/BDMV/CLIPINF/00001.clpi" to 4L, "$DISC/BDMV/STREAM/00001.m2ts" to 60_000L,
        )
        accounts[A] = FakeService(ServiceKind.TorBox, mapOf(HASH to disc))
        accounts[B] = FakeService(ServiceKind.Premiumize, mapOf(HASH to disc))
        val forgotten = mutableListOf<String>()
        val tried = object : MissingReleases.Store by NothingTried {
            override suspend fun found(indexerId: String, guid: String, providers: Collection<String>) {
                forgotten += guid
            }
        }
        val misses = ServiceMisses(tried)
        val events = pipeline(ServiceSource({ id -> accounts[id] }, store, misses = misses)).play(listOf(torrent(HASH, DISC, A, B)), FilePicker.Largest).toList()
        assertEquals(AttemptFailure.DiscCopy(DiscKind.BluRay), assertIs<PipelineEvent.Exhausted>(events.last()).failedAttempts.single().second)
        assertEquals(PipelineStep.Prepare, events.filterIsInstance<PipelineEvent.Step>().single { it.status is StepStatus.Failed }.step)
        assertEquals(listOf("list", "picked null"), accounts.getValue(A).calls)
        assertEquals(emptyList(), accounts.getValue(B).calls)
        assertEquals(emptyList(), events.filterIsInstance<PipelineEvent.Retrying>())
        assertEquals(emptyList(), forgotten)
        assertEquals(0, misses.changes.value)
    }

    @Test
    fun `where no name tells which file is the episode the user is asked, before anything is added`() = runBlocking<Unit> {
        accounts[A] = FakeService(ServiceKind.TorBox, mapOf(HASH to listOf("Show.1_6.mp4" to 400L, "Show.2-6.mp4" to 500L, "Show.3_6.mp4" to 300L)))
        val chooser = FileChooser { _, _, choices ->
            asked += choices.map { it.name }
            choices.single { it.name == "Show.2-6.mp4" }
        }
        val played = assertIs<PipelineEvent.Succeeded>(play(torrent(HASH, "Show.S01.1080p.WEB-GRP"), picker = FilePicker.Episode(1, 2), chooser = chooser).last())
        assertEquals("Show.2-6.mp4", played.target.file.name)
        assertEquals(listOf(listOf("Show.1_6.mp4", "Show.2-6.mp4", "Show.3_6.mp4")), asked)
        assertEquals(listOf("list", "picked Show.2-6.mp4", "add", "link Show.2-6.mp4"), accounts.getValue(A).calls)
    }

    @Test
    fun `the first account not having it moves on to the second in the same attempt`() = runBlocking<Unit> {
        accounts[A] = FakeService(ServiceKind.Premiumize, emptyMap())
        accounts[B] = FakeService(ServiceKind.TorBox, mapOf(HASH to film))
        val events = play(torrent(HASH, FILM, A, B))
        val target = assertIs<ServiceTarget>(assertIs<PipelineEvent.Succeeded>(events.last()).target)
        assertEquals(1, events.filterIsInstance<PipelineEvent.AttemptStarted>().size)
        assertEquals(listOf("list"), accounts.getValue(A).calls)
        assertEquals(B, store.get(target.listingId)?.account)
    }

    /**
     * L234: the listing a release prepares is said as it is kept, before its link is asked, so the play holds it until it
     * ends: the next episode of a pack, its torrent already in the account, as much as a torrent the play added. An account
     * that fails to prepare it says none.
     */
    @Test
    fun `the listing a release prepares is said as it is kept, before its link is asked`() = runBlocking<Unit> {
        accounts[A] = FakeService(ServiceKind.Premiumize, emptyMap())
        accounts[B] = FakeService(ServiceKind.TorBox, mapOf(HASH to film))
        val listed = mutableListOf<String>()
        val source = ServiceSource({ id -> accounts[id] }, store, onListed = { id -> listed += "$id, ${accounts.getValue(B).calls}" })
        val target = assertIs<ServiceTarget>(assertIs<PipelineEvent.Succeeded>(pipeline(source).play(listOf(torrent(HASH, FILM, A, B))).toList().last()).target)
        assertEquals(listOf("${target.listingId}, [list, picked Copper.Crown.2019.1080p.WEB-DL.mkv, add]"), listed)
    }

    /**
     * ELC-3 (L234): the account no longer had the release as Opening asked its link, so the link came with it added again.
     * That is kept in place of the old, under the same listing id, before the play's target is made: the play, the player
     * made again after the process went and the next fresh link ask by the new handle, not the one the account no longer has.
     */
    @Test
    fun `a release added again for Opening's link is kept by its new handle under the same listing id`() = runBlocking<Unit> {
        accounts[A] = FakeService(ServiceKind.TorBox, mapOf(HASH to film)).apply { addsAgain = true }
        val target = assertIs<ServiceTarget>(assertIs<PipelineEvent.Succeeded>(play(torrent(HASH)).last()).target)
        assertEquals(DebridListingStore.idFor(A, InfoHash.of(HASH)), target.listingId, "the same listing id")
        val kept = assertNotNull(store.get(target.listingId))
        assertEquals("t77", kept.prepared.handle)
        assertEquals("g1", kept.prepared.picked?.id)
        assertEquals("g1", target.file.file.id)
        target.keep()
        val onDisk = DebridListingStore(File(dir, "debrid/listings.json"), io = Dispatchers.Unconfined).get(target.listingId)
        assertEquals("t77", onDisk?.prepared?.handle, "on disk as the play starts")

        // The next fresh link, as the player's source asks one, goes by the new handle.
        accounts.getValue(A).addsAgain = false
        ServiceLinks(store, { id -> accounts[id] }).link(PlayUris.Target.Service(target.listingId, target.file.index), stale = null)
        assertEquals(listOf<String?>("t42", "t77"), accounts.getValue(A).linkedBy.toList())
    }

    /**
     * DEP-10, COT-2: the account a play tries is said as its Preparing starts there, before the service is asked, and the
     * step said running again, so the sheet's tag names it at once, not once it has prepared; one the play passes over,
     * remembered as having failed the torrent, is never said.
     */
    @Test
    fun `the account a play tries is said as its Preparing starts there, one passed over never`() = runBlocking<Unit> {
        accounts[A] = FakeService(ServiceKind.Premiumize, emptyMap())
        accounts[B] = FakeService(ServiceKind.TorBox, mapOf(HASH to film))
        val said = mutableListOf<String>()
        val trying = { candidate: PlayCandidate, account: String -> said += "${candidate.id} on $account, its calls ${accounts.getValue(account).calls}" }
        val events = pipeline(ServiceSource({ id -> accounts[id] }, store, onTrying = trying)).play(listOf(torrent(HASH, FILM, A, B)), FilePicker.Largest).toList()
        assertIs<PipelineEvent.Succeeded>(events.last())
        assertEquals(listOf("btih:$HASH on $A, its calls []", "btih:$HASH on $B, its calls []"), said)
        val preparing = events.filterIsInstance<PipelineEvent.Step>().filter { it.step == PipelineStep.Prepare && it.status is StepStatus.Running }
        assertEquals(3, preparing.size, "Preparing running as it starts, and again as each account is tried")

        // A play not chosen passes over an account remembered as having failed the torrent, without a call or a word.
        accounts[A] = FakeService(ServiceKind.Premiumize, mapOf(HASH to film))
        accounts[B] = FakeService(ServiceKind.TorBox, mapOf(HASH to film))
        val misses = ServiceMisses(NothingTried)
        misses.record(torrent(HASH, FILM, A, B), A, AttemptFailure.NoLongerCached)
        said.clear()
        val again = pipeline(ServiceSource({ id -> accounts[id] }, store, misses = misses, onTrying = trying)).play(listOf(torrent(HASH, FILM, A, B)), FilePicker.Largest).toList()
        assertIs<PipelineEvent.Succeeded>(again.last())
        assertEquals(listOf("btih:$HASH on $B, its calls []"), said)
        assertEquals(emptyList(), accounts.getValue(A).calls)
    }

    /**
     * An account whose limit is reached stops its route for the rest of the play (L164): the release goes on to its next
     * account, and a release cached on that account alone isn't tried; the account's other failures are the release's.
     */
    @Test
    fun `a limit on the first account moves on to the next, and that account's other releases are passed over`() = runBlocking<Unit> {
        accounts[A] = FakeService(ServiceKind.TorBox, mapOf(HASH to film, OTHER to film)).apply { failure = ServiceError.Limit(Duration.ofMinutes(20)) }
        accounts[B] = FakeService(ServiceKind.Premiumize, mapOf(THIRD to film))
        val events = play(torrent(HASH, FILM, A, B), torrent(OTHER, FILM, A), torrent(THIRD, FILM, B))
        val played = assertIs<PipelineEvent.Succeeded>(events.last())
        assertEquals("btih:$THIRD", played.target.candidate.id)
        assertEquals(listOf("list"), accounts.getValue(A).calls)
        assertEquals(listOf(AttemptFailure.NoLongerCached), played.failedAttempts.map { it.second })
        assertEquals(listOf("btih:$HASH", "btih:$THIRD"), events.filterIsInstance<PipelineEvent.AttemptStarted>().map { it.candidate.id })
    }

    @Test
    fun `each service failure has its own kind, and no failure's words hold the torrent, a name, the account or a link`() = runBlocking<Unit> {
        val failures = listOf(
            ServiceError.NotCached, ServiceError.Removed, ServiceError.Limit(Duration.ofMinutes(20)), ServiceError.Limit(), ServiceError.KeyRefused,
            ServiceError.Down, ServiceError.Refused(tooLarge = true), ServiceError.Refused(),
        ).flatMap { error ->
            listOf(true, false).map { atLink ->
                accounts[A] = FakeService(ServiceKind.TorBox, mapOf(HASH to film)).apply { if (atLink) linkFailure = error else failure = error }
                val exhausted = assertIs<PipelineEvent.Exhausted>(play(torrent(HASH)).last())
                exhausted.failedAttempts.single().second
            }
        }
        assertEquals(
            listOf(
                "No longer cached", "Removed by the debrid service", "The debrid service’s limit was reached · try again in about 20 min",
                "The debrid service’s limit was reached · try again later", "TorBox refused your API key", "The debrid service stopped answering",
                "Too large for your debrid plan", "The debrid service won’t play this release",
            ).flatMap { listOf(it, it) },
            failures.map { it.text },
        )
        // The account's route stops for a limit, a refused key and an outage, never for the release's own failures.
        assertEquals(
            listOf(null, null, "service:$A", "service:$A", "service:$A", "service:$A", null, null).flatMap { listOf(it, it) },
            failures.map { it.route },
        )
        failures.forEach { failure ->
            listOf(HASH, "Copper", "Crown", ".mkv", LINK, A).forEach { secret -> assertFalse(secret in failure.text, "$secret in ${failure.text}") }
        }
    }

    /** The library's tried releases, for a play that remembers nothing there. */
    private object NothingTried : MissingReleases.Store {
        override suspend fun get(ids: Collection<String>) = emptyMap<String, TriedRelease>()
        override fun observe(ids: Collection<String>): Flow<Map<String, TriedRelease>> = flowOf(emptyMap())
        override suspend fun record(indexerId: String, guid: String, providers: Collection<String>, incomplete: Boolean) = Unit
        override suspend fun found(indexerId: String, guid: String, providers: Collection<String>) = Unit
        override suspend fun recordPasswordNeeded(indexerId: String, guid: String) = Unit
        override suspend fun recordDiscCopy(indexerId: String, guid: String) = Unit
    }

    /** A probe that reads [target]'s file through [links], failing for [failure] where it gives one: the links it read, in order. */
    private class LinkProbe(private val links: ServiceLinks, private val failure: (PlaybackTarget) -> LinkFailure?) : TrackProbe {
        val read = mutableListOf<String>()

        override suspend fun probe(target: PlaybackTarget): ProbeResult {
            target.keep()
            read += links.link(PlayUris.parse(target.uri) as PlayUris.Target.Service, stale = null).url
            return failure(target)?.let { ProbeResult.Failed(UsenetError.Network("unexpected end of stream"), null, LinkUnavailable(it)) } ?: ProbeResult.Playable()
        }
    }

    /**
     * The probe reads the file through the link Opening got, with no call of the service's for it (L172); a link no longer
     * to be had fails the attempt as the service's own failure, by the account that prepared it: its limit stops its route,
     * so its other releases are passed over and another account's plays.
     */
    @Test
    fun `the probe reads through Opening's link, and the service's limit at the probe stops its account's route`() = runBlocking<Unit> {
        accounts[A] = FakeService(ServiceKind.TorBox, mapOf(HASH to film, OTHER to film))
        accounts[B] = FakeService(ServiceKind.Premiumize, mapOf(THIRD to film))
        val links = ServiceLinks(store, { id -> accounts[id] }, now = { 0L }, httpsOnly = { true })
        val probe = LinkProbe(links) { target -> LinkFailure.Limit(20 * 60_000L).takeIf { target.candidate.id == "btih:$HASH" } }
        val source = ServiceSource({ id -> accounts[id] }, store, links = links)
        val events = pipeline(source, probe).play(listOf(torrent(HASH, FILM, A), torrent(OTHER, FILM, A), torrent(THIRD, FILM, B)), FilePicker.Largest).toList()
        val played = assertIs<PipelineEvent.Succeeded>(events.last())
        assertEquals("btih:$THIRD", played.target.candidate.id)
        val limit = assertIs<AttemptFailure.ServiceLimit>(played.failedAttempts.single().second)
        assertEquals(20.minutes, limit.wait)
        assertEquals("service:$A", limit.route)
        assertEquals(listOf("btih:$HASH", "btih:$THIRD"), events.filterIsInstance<PipelineEvent.AttemptStarted>().map { it.candidate.id })
        // Opening's links, each asked of its service once: the probe was given them without another call.
        assertEquals(listOf(LINK + "Copper.Crown.2019.1080p.WEB-DL.mkv", LINK + "Copper.Crown.2019.1080p.WEB-DL.mkv"), probe.read)
        assertEquals(1, accounts.getValue(A).calls.count { it.startsWith("link") })
        assertEquals(1, accounts.getValue(B).calls.count { it.startsWith("link") })
    }

    /** No working link to be had at the probe (W81): the release fails with the player's words, remembered an hour against its account. */
    @Test
    fun `no working link at the probe is remembered an hour against the account`() = runBlocking<Unit> {
        accounts[A] = FakeService(ServiceKind.TorBox, mapOf(HASH to film))
        val links = ServiceLinks(store, { id -> accounts[id] }, now = { 0L }, httpsOnly = { true })
        val misses = ServiceMisses(NothingTried)
        val source = ServiceSource({ id -> accounts[id] }, store, misses = misses, links = links)
        val candidate = torrent(HASH)
        val exhausted = assertIs<PipelineEvent.Exhausted>(pipeline(source, LinkProbe(links) { LinkFailure.NoWorkingLink }).play(listOf(candidate), FilePicker.Largest).toList().last())
        assertEquals(AttemptFailure.NoWorkingLink, exhausted.failedAttempts.single().second)
        assertEquals(AttemptFailure.NoWorkingLink, misses.known(candidate, A))
    }

    /**
     * DFH-1: the probe failing to read a torrent's file for anything but a link no longer to be had (the CDN's host not
     * found, its TLS refused, its 5xx past Media3's retries) says so by its kind alone, never in the exception's words, which
     * name the host: no working link from that account, remembered an hour against it, and a phone told it in the TV's
     * neutral words. A file no extractor reads is the release's own, said in plain words as Usenet's is (L318) and held
     * against no account; the probe's own time-out is said in its words, which a phone is told as a failure to read the
     * release, not of a provider.
     */
    @Test
    fun `a probe failure that isn't the service's is said by its kind, never in the exception's words`() = runBlocking<Unit> {
        val host = "store-030.cdn.example"
        val words = "java.net.UnknownHostException: Unable to resolve host \"$host\": No address associated with hostname"
        accounts[A] = FakeService(ServiceKind.TorBox, mapOf(HASH to film, OTHER to film, THIRD to film, FOURTH to film))
        val misses = ServiceMisses(NothingTried)
        fun failing(failed: ProbeResult.Failed, hash: String): AttemptFailure = runBlocking {
            val source = ServiceSource({ id -> accounts[id] }, store, misses = misses)
            val events = pipeline(source, TrackProbe { failed }).play(listOf(torrent(hash)), FilePicker.Largest).toList()
            assertIs<PipelineEvent.Exhausted>(events.last()).failedAttempts.single().second
        }

        val unreached = failing(ProbeResult.Failed(UsenetError.Network(words), null, java.io.IOException(java.net.UnknownHostException(words.substringAfter(": ")))), HASH)
        assertEquals(AttemptFailure.NoWorkingLink, unreached)
        assertFalse(host in unreached.text || "Exception" in unreached.text, unreached.text)
        assertEquals(AttemptFailure.NoWorkingLink, misses.known(torrent(HASH), A))
        assertEquals("No longer available from the TV's debrid service", SheetMirror.failure(unreached))

        val unreadable = failing(ProbeResult.Failed(UsenetError.Unknown(null), UnreadableFile.UnknownFormat, java.io.IOException("parsing")), OTHER)
        assertEquals("The player can't read this MKV file", unreadable.text)
        assertEquals("The TV's player can't read this MKV file", SheetMirror.failure(unreadable))
        assertEquals(null, misses.known(torrent(OTHER), A))

        val slow = failing(ProbeResult.Failed(UsenetError.Timeout("No track list within 20 s")), THIRD)
        assertEquals("No track list within 20 s", slow.text)
        assertEquals("Reading the release failed on the TV", SheetMirror.failure(slow))

        // The player's own failure, no read's (a decoder that wouldn't start): said by its class, the account not blamed.
        val decoder = failing(ProbeResult.Failed(UsenetError.Unknown("Decoder init failed: c2.example.hevc.decoder"), null, IllegalStateException("Decoder init failed: c2.example.hevc.decoder")), FOURTH)
        assertEquals("IllegalStateException", decoder.text)
        assertEquals(null, misses.known(torrent(FOURTH), A))
        assertEquals("Reading the release failed on the TV", SheetMirror.failure(decoder))
    }

    /** A search's torrent plays from the service accounts that have it, A to Z, as the cache check found them; an NZB from Usenet. */
    @Test
    fun `a search's torrent plays by the accounts that have it cached, an NZB by Usenet`() {
        fun item(infoHash: String?, availability: Availability?) = RankedItem(
            NewznabItem(
                indexerId = "prowlarr", guid = infoHash?.let { "btih:$it" } ?: "g1", title = FILM, sizeBytes = 0, publishedAt = null, postedAt = null,
                categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null, tvmazeId = null,
                season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(), infoHash = infoHash,
                availability = availability,
            ),
            RankedRelease(ReleaseFacts(infoHash ?: "g1", FILM, 0), ReleaseParser.parse(FILM), playable = true, score = 0.0, reasons = emptyList(), estimatedBitrate = null),
        )
        val cached = item(HASH, Availability.Cached(listOf(B, A))).playCandidate
        assertEquals(PlayCandidate("btih:$HASH", FILM, "prowlarr", source = ReleaseFacts.TORRENT, routes = listOf("service:$B", "service:$A")), cached)
        val nzb = item(null, null).playCandidate
        assertEquals(PlayCandidate.USENET, nzb.source)
        assertEquals(listOf(PlayCandidate.USENET), nzb.routes)
    }

    /** [events] as the sheet hears them: each step as "Open Failed", a retry as "Retrying: <its failure's words>". */
    private fun said(events: List<PipelineEvent>): List<String> = events.mapNotNull { event ->
        when (event) {
            is PipelineEvent.Step -> "${event.step} ${event.status::class.simpleName}"
            is PipelineEvent.Retrying -> "Retrying: ${event.failure.text}"
            else -> null
        }
    }

    /**
     * DEP-6 (L231, W86): the first account's Opening failing as its service stops answering tries the same release on the
     * next account that has it cached, before the next release: within the one attempt, Preparing again on that account,
     * which the sheet is told of as it starts there, and the file the user answered for the first account's listing found
     * again in the next one's, not asked again. The failed account's route stops for the rest of the play.
     */
    @Test
    fun `Premiumize's Opening failing tries TorBox on the same release, the answered file not asked again`() = runBlocking<Unit> {
        val files = listOf("Show.1_6.mp4" to 400L, "Show.2-6.mp4" to 500L, "Show.3_6.mp4" to 300L)
        accounts[A] = FakeService(ServiceKind.Premiumize, mapOf(HASH to files)).apply { linkFailure = ServiceError.Down }
        accounts[B] = FakeService(ServiceKind.TorBox, mapOf(HASH to files))
        val chooser = FileChooser { _, _, choices ->
            asked += choices.map { it.name }
            choices.single { it.name == "Show.2-6.mp4" }
        }
        val tried = mutableListOf<String>()
        val source = ServiceSource({ id -> accounts[id] }, store, chooser, onTrying = { _, account -> tried += account })
        val events = pipeline(source).play(listOf(torrent(HASH, "Show.S01.1080p.WEB-GRP", A, B)), FilePicker.Episode(1, 2)).toList()
        val played = assertIs<PipelineEvent.Succeeded>(events.last())
        val target = assertIs<ServiceTarget>(played.target)
        assertEquals("Show.2-6.mp4", target.file.name)
        assertEquals(B, store.get(target.listingId)?.account)
        assertEquals(emptyList(), played.failedAttempts)
        assertEquals(1, events.filterIsInstance<PipelineEvent.AttemptStarted>().size, "the same attempt")
        assertEquals(listOf(listOf("Show.1_6.mp4", "Show.2-6.mp4", "Show.3_6.mp4")), asked, "asked once")
        assertEquals(listOf("list", "picked Show.2-6.mp4", "add", "link Show.2-6.mp4"), accounts.getValue(A).calls)
        assertEquals(listOf("list", "picked Show.2-6.mp4", "add", "link Show.2-6.mp4"), accounts.getValue(B).calls)
        assertEquals(
            listOf(
                "Prepare Running", "Prepare Running", "Prepare Done", "Open Running", "Open Failed", "Retrying: The debrid service stopped answering",
                "Prepare Running", "Prepare Running", "Prepare Done", "Open Running", "Open Done", "ProbeTracks Running", "ProbeTracks Done",
            ),
            said(events),
        )
        // The tag follows: each account named as its Preparing starts.
        assertEquals(listOf(A, B), tried)
        assertEquals(setOf("service:$A"), played.stopped)
    }

    /** DEP-6: the probe finding no working link from TorBox, first in the order, tries Premiumize next, which plays. */
    @Test
    fun `a probe's no working link on TorBox tries Premiumize next`() = runBlocking<Unit> {
        accounts[A] = FakeService(ServiceKind.TorBox, mapOf(HASH to film))
        accounts[B] = FakeService(ServiceKind.Premiumize, mapOf(HASH to film))
        val links = ServiceLinks(store, { id -> accounts[id] }, now = { 0L }, httpsOnly = { true })
        val misses = ServiceMisses(NothingTried)
        val probe = LinkProbe(links) { target -> LinkFailure.NoWorkingLink.takeIf { (target as ServiceTarget).account == A } }
        val source = ServiceSource({ id -> accounts[id] }, store, misses = misses, links = links)
        val events = pipeline(source, probe).play(listOf(torrent(HASH, FILM, A, B)), FilePicker.Largest).toList()
        val target = assertIs<ServiceTarget>(assertIs<PipelineEvent.Succeeded>(events.last()).target)
        assertEquals(B, store.get(target.listingId)?.account)
        assertEquals(2, probe.read.size, "each account's file probed")
        assertEquals(listOf("Retrying: No longer available from the service"), said(events).filter { it.startsWith("Retrying") })
        assertEquals("ProbeTracks Failed", said(events)[said(events).indexOf("Retrying: No longer available from the service") - 1])
        // Remembered against TorBox alone, an hour (L174); the play goes on from Premiumize.
        assertEquals(AttemptFailure.NoWorkingLink, misses.known(torrent(HASH, FILM, A, B), A))
        assertEquals(null, misses.known(torrent(HASH, FILM, A, B), B))
    }

    /**
     * L231: only the service's own failures try the next account; the release's own (no video this device plays, a file
     * the player can't read, no file for the episode) end the attempt as before, the next account never asked.
     */
    @Test
    fun `a release's own failure doesn't try the next account`() = runBlocking<Unit> {
        val failures = listOf<suspend () -> AttemptFailure>(
            {
                accounts[A] = FakeService(ServiceKind.TorBox, mapOf(HASH to film))
                accounts[B] = FakeService(ServiceKind.Premiumize, mapOf(HASH to film))
                val events = pipeline(source(), TrackProbe { ProbeResult.NoPlayableVideo(listOf("2160p Dolby Vision profile 7")) }).play(listOf(torrent(HASH, FILM, A, B)), FilePicker.Largest).toList()
                assertEquals(emptyList(), events.filterIsInstance<PipelineEvent.Retrying>())
                assertIs<PipelineEvent.Exhausted>(events.last()).failedAttempts.single().second
            },
            {
                accounts[A] = FakeService(ServiceKind.TorBox, mapOf(HASH to film))
                accounts[B] = FakeService(ServiceKind.Premiumize, mapOf(HASH to film))
                val unreadable = TrackProbe { ProbeResult.Failed(UsenetError.Unknown(null), UnreadableFile.UnknownFormat, java.io.IOException("parsing")) }
                val events = pipeline(source(), unreadable).play(listOf(torrent(HASH, FILM, A, B)), FilePicker.Largest).toList()
                assertEquals(emptyList(), events.filterIsInstance<PipelineEvent.Retrying>())
                assertIs<PipelineEvent.Exhausted>(events.last()).failedAttempts.single().second
            },
            {
                accounts[A] = FakeService(ServiceKind.TorBox, mapOf(HASH to pack))
                accounts[B] = FakeService(ServiceKind.Premiumize, mapOf(HASH to pack))
                val events = pipeline(source()).play(listOf(torrent(HASH, PACK, A, B)), FilePicker.Episode(1, 4)).toList()
                assertEquals(emptyList(), events.filterIsInstance<PipelineEvent.Retrying>())
                assertIs<PipelineEvent.Exhausted>(events.last()).failedAttempts.single().second
            },
        ).map { it() to accounts.getValue(B).calls.toList() }
        assertEquals(
            listOf(
                "No video this device can play: 2160p Dolby Vision profile 7" to emptyList(),
                "The player can't read this MKV file" to emptyList(),
                "No file for S01E04" to emptyList<String>(),
            ),
            failures.map { (failure, calls) -> failure.text to calls },
        )
    }

    /**
     * L319: a reader that crashed on the file may be the app's own fault, neither the release's nor the account's: said as
     * a read that failed, held against no account, and no other account is tried, as each would read the same file.
     */
    @Test
    fun `a reader that crashed on the file is held against no account, and tries no other`() = runBlocking<Unit> {
        accounts[A] = FakeService(ServiceKind.TorBox, mapOf(HASH to film))
        accounts[B] = FakeService(ServiceKind.Premiumize, mapOf(HASH to film))
        val misses = ServiceMisses(NothingTried)
        val source = ServiceSource({ id -> accounts[id] }, store, misses = misses)
        val crash = Loader.UnexpectedLoaderException(IllegalStateException("Top bit not zero: -2147483648"))
        val crashed = TrackProbe { ProbeResult.Failed(UsenetError.Unknown(null), UnreadableFile.ReaderCrashed, crash) }
        val events = pipeline(source, crashed).play(listOf(torrent(HASH, FILM, A, B)), FilePicker.Largest).toList()
        val failure = assertIs<PipelineEvent.Exhausted>(events.last()).failedAttempts.single().second
        assertEquals("Reading the release failed", failure.text)
        assertEquals("Reading the release failed on the TV", SheetMirror.failure(failure))
        assertEquals(emptyList(), events.filterIsInstance<PipelineEvent.Retrying>())
        assertEquals(emptyList(), accounts.getValue(B).calls)
        assertEquals(null, misses.known(torrent(HASH, FILM, A, B), A))
    }

    /**
     * DEP-6: the next account is one not stopped nor remembered as having failed the torrent; with only such accounts
     * left, the attempt ends with its failure, those never asked.
     */
    @Test
    fun `a stopped or remembered account is passed over, and with none other left the attempt ends`() = runBlocking<Unit> {
        val third = "acct55d0"
        accounts[A] = FakeService(ServiceKind.TorBox, mapOf(HASH to film, OTHER to film)).apply { linkFailure = ServiceError.Down }
        accounts[B] = FakeService(ServiceKind.Premiumize, mapOf(HASH to film, OTHER to film))
        accounts[third] = FakeService(ServiceKind.TorBox, mapOf(OTHER to film)).apply { failure = ServiceError.Limit(Duration.ofMinutes(20)) }
        val misses = ServiceMisses(NothingTried)
        misses.record(torrent(HASH, FILM, A, B), B, AttemptFailure.NoLongerCached)
        val source = ServiceSource({ id -> accounts[id] }, store, misses = misses)
        // Release 1 on the third account alone stops its route; release 2's Opening fails on A, B is remembered and the
        // third stopped: nothing left, so release 2 ends with A's failure.
        val events = pipeline(source).play(listOf(torrent(OTHER, FILM, third), torrent(HASH, FILM, A, B, third))).toList()
        val exhausted = assertIs<PipelineEvent.Exhausted>(events.last())
        assertEquals(listOf("btih:$OTHER" to "The debrid service’s limit was reached · try again in about 20 min", "btih:$HASH" to "The debrid service stopped answering"),
            exhausted.failedAttempts.map { (candidate, failure) -> candidate.id to failure.text })
        assertEquals(emptyList(), events.filterIsInstance<PipelineEvent.Retrying>())
        assertEquals(emptyList(), accounts.getValue(B).calls)
        assertEquals(listOf("list"), accounts.getValue(third).calls)

        // With a fourth account fresh, the remembered and the stopped ones are passed over for it.
        val fourth = "acct8e21"
        accounts.getValue(A).calls.clear()
        accounts[fourth] = FakeService(ServiceKind.Premiumize, mapOf(HASH to film))
        val again = pipeline(source).play(listOf(torrent(OTHER, FILM, third), torrent(HASH, FILM, A, B, third, fourth))).toList()
        val target = assertIs<ServiceTarget>(assertIs<PipelineEvent.Succeeded>(again.last()).target)
        assertEquals(fourth, store.get(target.listingId)?.account)
        assertEquals(emptyList(), accounts.getValue(B).calls)
    }

    /** DEP-6: the last account failing too ends the attempt with its own failure's words, and the play moves on as before. */
    @Test
    fun `the last account's failure ends the attempt with its words`() = runBlocking<Unit> {
        accounts[A] = FakeService(ServiceKind.Premiumize, mapOf(HASH to film)).apply { linkFailure = ServiceError.Down }
        accounts[B] = FakeService(ServiceKind.TorBox, mapOf(HASH to film)).apply { linkFailure = ServiceError.Limit(Duration.ofMinutes(20)) }
        accounts["acct3c3c"] = FakeService(ServiceKind.TorBox, mapOf(OTHER to film))
        val events = play(torrent(HASH, FILM, A, B), torrent(OTHER, FILM, "acct3c3c"))
        val played = assertIs<PipelineEvent.Succeeded>(events.last())
        assertEquals("btih:$OTHER", played.target.candidate.id)
        assertEquals(listOf("The debrid service’s limit was reached · try again in about 20 min"), played.failedAttempts.map { it.second.text })
        assertEquals(1, events.filterIsInstance<PipelineEvent.Retrying>().size)
        assertEquals(listOf(1 to 2, 2 to 2), events.filterIsInstance<PipelineEvent.AttemptStarted>().map { it.attempt to it.of })
    }

    /**
     * DEP-6: what the next account's try reads before it says so failing to read (what is remembered of the torrent, from
     * the library, or the account's client, from the settings: a full or locked disk) ends the release's attempt alone,
     * with its own failure, as that read failing in Preparing would. The play moves on to its next release, never ended
     * whole in the read's words, and the next account isn't asked.
     */
    @Test
    fun `a failed read while looking for the next account ends the release's attempt alone, and the play moves on`() = runBlocking<Unit> {
        val third = "acct3c3c"
        // The library's tried releases: read for the first account's Preparing, then failing once as the next is looked for.
        val reads = AtomicInteger()
        val flaky = object : MissingReleases.Store by NothingTried {
            override suspend fun get(ids: Collection<String>): Map<String, TriedRelease> {
                if (reads.incrementAndGet() == 2) throw IllegalStateException("database is locked")
                return emptyMap()
            }
        }
        val misreads = listOf<Pair<ServiceMisses?, suspend (String) -> DebridService?>>(
            ServiceMisses(flaky) to { id -> accounts[id] },
            null to { id -> if (id == B) throw IllegalStateException("the settings didn't read") else accounts[id] },
        )
        misreads.forEach { (misses, read) ->
            accounts[A] = FakeService(ServiceKind.Premiumize, mapOf(HASH to film)).apply { linkFailure = ServiceError.Down }
            accounts[B] = FakeService(ServiceKind.TorBox, mapOf(HASH to film))
            accounts[third] = FakeService(ServiceKind.TorBox, mapOf(OTHER to film))
            val source = ServiceSource(read, store, misses = misses)
            val events = pipeline(source).play(listOf(torrent(HASH, FILM, A, B), torrent(OTHER, FILM, third))).toList()
            val played = assertIs<PipelineEvent.Succeeded>(events.last())
            assertEquals("btih:$OTHER", played.target.candidate.id)
            assertEquals(listOf("btih:$HASH" to "The debrid service stopped answering"), played.failedAttempts.map { (candidate, failure) -> candidate.id to failure.text })
            assertEquals(emptyList(), events.filterIsInstance<PipelineEvent.Retrying>())
            assertEquals(emptyList(), accounts.getValue(B).calls)
            assertEquals(listOf(1 to 2, 2 to 2), events.filterIsInstance<PipelineEvent.AttemptStarted>().map { it.attempt to it.of })
        }
        assertEquals(3, reads.get(), "read again for the next release")
    }

    /**
     * L232, L234: an account the next-account try reaches may add the torrent to its list (TorBox): that add is recorded as
     * this device's, born held by the play as a first account's is, kept while the play goes on however long, and removed
     * a grace after the play lets go.
     */
    @Test
    fun `an add made on the next account is recorded and held by the play, as a first account's is`() = runTest {
        accounts[A] = FakeService(ServiceKind.Premiumize, mapOf(HASH to film)).apply { linkFailure = ServiceError.Down }
        val torBox = FakeService(ServiceKind.TorBox, mapOf(HASH to film))
        accounts[B] = torBox
        val saved = ServiceAccount(B, ServiceKind.TorBox.name, accountName = "quay.viewer@example.org", keySavedAt = 1_762_000_000_000L)
        val adds = DebridAdds(
            File(dir, "debrid/added.json"), accounts = { listOf(saved) },
            usable = { id -> if (id == B) ServiceRegistry.Client(saved, ServiceKind.TorBox, torBox, ServiceGate()) else null },
            scope = backgroundScope, clock = { testScheduler.currentTime }, shown = { true }, io = Dispatchers.Unconfined,
        )
        torBox.watch = adds.watch(saved)
        val source = ServiceSource({ id -> accounts[id] }, store, onListed = { id -> adds.holdForPlay(id) })
        val play = DebridAdds.Play()
        val events = withContext(play) { pipeline(source).play(listOf(torrent(HASH, FILM, A, B))).toList() }
        val target = assertIs<ServiceTarget>(assertIs<PipelineEvent.Succeeded>(events.last()).target)
        assertEquals(1, events.filterIsInstance<PipelineEvent.Retrying>().size)
        val record = adds.records().single()
        assertEquals(B to "t42", record.account to record.handle)
        assertEquals(target.listingId, record.listingId, "the listing the play plays from")
        // Held by the play while it goes on (a long probe, a cast's question): no removal however long.
        advanceTimeBy(Duration.ofHours(3).toMillis())
        runCurrent()
        adds.sweep()
        assertEquals(emptyList(), torBox.removed.toList())
        adds.release(play)
        advanceTimeBy(DebridAdds.GRACE.plusSeconds(1).toMillis())
        runCurrent()
        assertEquals(listOf("t42"), torBox.removed.toList())
    }

    private companion object {
        const val HASH = "c0ffee11c0ffee22c0ffee33c0ffee44c0ffee55"
        const val OTHER = "0ddba11000000000000000000000000000000001"
        const val THIRD = "0ddba11000000000000000000000000000000002"
        const val FOURTH = "0ddba11000000000000000000000000000000003"
        const val A = "acct7f3e"
        const val B = "acct91c2"
        const val LINK = "https://cdn.example.org/dl/s3cr3t-token/"
        const val FILM = "Copper.Crown.2019.1080p.WEB-DL"
        const val PACK = "Harbor.Lights.S01.1080p.WEB-DL"
        const val DISC = "Copper.Crown.2019.2160p.UHD.BluRay.HEVC-GRP"
        val film = listOf(
            "Copper.Crown.2019.1080p.WEB-DL/Sample/Copper.Crown.2019.sample.mkv" to 40L,
            "Copper.Crown.2019.1080p.WEB-DL/Copper.Crown.2019.1080p.WEB-DL.mkv" to 4_000L,
            "Copper.Crown.2019.1080p.WEB-DL/Copper.Crown.2019.nfo" to 1L,
        )
        val pack = listOf(
            "$PACK/Harbor.Lights.S01E01.mkv" to 900L, "$PACK/Harbor.Lights.S01E02.mkv" to 950L, "$PACK/Harbor.Lights.S01E03.mkv" to 920L,
        )
    }
}
