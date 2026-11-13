package io.skjaere.usenet.app.debrid

import io.skjaere.usenet.data.ServiceAccount
import io.skjaere.usenet.data.ServiceWithKey
import io.skjaere.usenet.debrid.Account
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
import io.skjaere.usenet.newznab.IndexerCaps
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.IndexerKind
import io.skjaere.usenet.newznab.IndexerOutcome
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.ReleaseSearch
import io.skjaere.usenet.newznab.SearchPage
import io.skjaere.usenet.newznab.SearchQuery
import io.skjaere.usenet.releases.Availability
import io.skjaere.usenet.search.CheckedSearch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app's cache check (L199): one batch per account that is on and open, per indexer answer; a torrent is cached when any
 * account says so, with the accounts that did, in the user's order (L228); not cached when all asked say no; not checked when none says yes
 * and one couldn't be asked. Answers are kept 10 minutes; an indexer whose switch is off sends nothing. Over the app's
 * registry with fake clients, so a gate a limit closed is the registry's own.
 */
class DebridCacheCheckTest {
    /**
     * A client whose service has [cached] (hashes), or fails with [failure], answering once [held] is let go (as one waiting
     * out a 429 does); the hashes of every check it was asked.
     */
    private class FakeService(override val kind: ServiceKind, private val gate: ServiceGate) : DebridService {
        var cached = emptySet<String>()
        var failure: ServiceError? = null
        var held: CompletableDeferred<Unit>? = null
        val asked = mutableListOf<List<String>>()

        override suspend fun test(): Account = error("not asked")
        override suspend fun check(hashes: List<InfoHash>): Map<InfoHash, Boolean> = gate.guarded {
            asked += hashes.map { it.hex }
            held?.await()
            failure?.let { throw ServiceException(it) }
            hashes.associateWith { it.hex in cached }
        }
        override suspend fun prepare(hash: InfoHash, pick: suspend (ServiceListing) -> ServiceFile?): Prepared = error("not asked")
        override suspend fun link(prepared: Prepared, file: ServiceFile): ServiceLink = error("not asked")
    }

    private var now = Instant.parse("2026-11-05T12:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }

    private val made = mutableMapOf<ServiceKind, FakeService>()
    private var saved = listOf(
        ServiceWithKey(ServiceAccount("tb", "TorBox", accountName = "quay.viewer@example.org"), "TB-KEY"),
        ServiceWithKey(ServiceAccount("pm", "Premiumize", accountName = "customer 900000042"), "PM-KEY"),
    )
    private val registry = ServiceRegistry(
        accounts = { saved },
        // As the settings mark it: the account is out until a new key is saved.
        markRefused = { id -> saved = saved.map { if (it.record.id == id) it.copy(record = it.record.copy(keyRefused = true)) else it } },
        make = { kind, _, _, gate, _ -> FakeService(kind, gate).also { made[kind] = it } },
        endpoints = { "https://fake.example" },
        newGate = { ServiceGate(clock) },
    )
    private val check = DebridCacheCheck({ registry.usable(shown = true) }, clock)

    private val prowlarr = IndexerConfig("p", "Home Prowlarr", "https://p.invalid/api", "k", kind = IndexerKind.Torznab)
    private val quarry = prowlarr.copy(id = "q", name = "Quarry", useWithService = false)

    private val both = "1111111111111111111111111111111111111111"
    private val torboxOnly = "2222222222222222222222222222222222222222"
    private val neither = "3333333333333333333333333333333333333333"

    private fun torrent(hash: String, indexer: IndexerConfig = prowlarr) = NewznabItem(
        indexerId = indexer.id, guid = "btih:$hash", title = "The.Glass.Orchard.2019.1080p.WEB-DL.H.264-$hash", sizeBytes = 6_000_000_000,
        publishedAt = null, postedAt = null, categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null,
        tmdbId = null, imdbId = null, tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null,
        downloadTemplate = null, attributes = emptyMap(), infoHash = hash,
    )

    /** Every item [items] settled as, by hash, and the chunks they came in. */
    private fun checked(indexer: IndexerConfig, vararg hashes: String): Pair<Map<String, Availability?>, Int> = runBlocking {
        val chunks = check.check(indexer, hashes.map { torrent(it, indexer) }).toList()
        chunks.flatten().associate { it.infoHash!! to it.availability } to chunks.size
    }

    /** The services' clients, made at the first check. */
    private fun clients(): Pair<FakeService, FakeService> = runBlocking {
        registry.usable(shown = true)
        made.getValue(ServiceKind.TorBox) to made.getValue(ServiceKind.Premiumize)
    }

    @Test
    fun `one batch per account per answer, and a torrent either account has is cached on those that have it, in the stored order`() {
        val (torbox, premiumize) = clients()
        torbox.cached = setOf(both, torboxOnly)
        premiumize.cached = setOf(both)
        val (settled, _) = checked(prowlarr, both, torboxOnly, neither)
        assertEquals(
            mapOf(both to Availability.Cached(listOf("tb", "pm")), torboxOnly to Availability.Cached(listOf("tb")), neither to Availability.NotCached),
            settled,
        )
        assertEquals(listOf(listOf(both, torboxOnly, neither)), torbox.asked)
        assertEquals(listOf(listOf(both, torboxOnly, neither)), premiumize.asked)
    }

    @Test
    fun `a no from one account and a failure from the other is not checked, and a yes is cached whatever the other did`() {
        val (torbox, premiumize) = clients()
        torbox.cached = setOf(torboxOnly)
        premiumize.failure = ServiceError.Down
        val (settled, _) = checked(prowlarr, torboxOnly, neither)
        assertEquals(mapOf(torboxOnly to Availability.Cached(listOf("tb")), neither to Availability.NotChecked), settled)
    }

    @Test
    fun `an account its limit closed isn't asked until its gate opens`() {
        val (torbox, premiumize) = clients()
        premiumize.failure = ServiceError.Limit(Duration.ofMinutes(20))
        checked(prowlarr, both)
        premiumize.failure = null
        torbox.cached = setOf(neither)
        val (settled, _) = checked(prowlarr, neither)
        assertEquals(mapOf(neither to Availability.Cached(listOf("tb"))), settled)
        assertEquals(1, premiumize.asked.size, "asked while its limit held it")

        now = now.plus(Duration.ofMinutes(21))
        checked(prowlarr, torboxOnly)
        assertEquals(2, premiumize.asked.size)
    }

    @Test
    fun `an indexer whose switch is off sends nothing, its torrents not for the service at once`() {
        val (torbox, premiumize) = clients()
        val (settled, chunks) = checked(quarry, both, neither)
        assertEquals(mapOf(both to Availability.NotForService, neither to Availability.NotForService), settled)
        assertEquals(1, chunks)
        assertEquals(emptyList(), torbox.asked + premiumize.asked)
    }

    @Test
    fun `answers are kept 10 minutes, so another list of the same torrents asks nothing until then`() {
        val (torbox, premiumize) = clients()
        torbox.cached = setOf(both)
        checked(prowlarr, both, neither)
        val (again, chunks) = checked(prowlarr, both, neither)
        assertEquals(mapOf(both to Availability.Cached(listOf("tb")), neither to Availability.NotCached), again)
        assertEquals(1, chunks, "all at once, from what was kept")
        assertEquals(1 to 1, torbox.asked.size to premiumize.asked.size)

        // Only what isn't kept is asked: a new torrent beside two kept ones.
        checked(prowlarr, both, neither, torboxOnly)
        assertEquals(listOf(torboxOnly), torbox.asked.last())

        now = now.plus(Duration.ofMinutes(11))
        checked(prowlarr, both)
        assertEquals(listOf(both), torbox.asked.last())
        assertEquals(3 to 3, torbox.asked.size to premiumize.asked.size)
    }

    /**
     * DDC-1: an account still asking when the search's bound comes (its 429s waited out, its call slow) doesn't take another
     * account's yes with it. The torrent that one has joins as cached there, a few seconds on rather than at the bound;
     * the one it hasn't, with no other answer, is not checked.
     */
    @Test
    fun `a yes already given joins the list though the other account is still asking at the search's bound`() = runTest {
        val (torbox, premiumize) = clients()
        torbox.cached = setOf(both)
        premiumize.held = CompletableDeferred()
        val answer = IndexerOutcome.Results(prowlarr, SearchPage(listOf(torrent(both), torrent(neither)), 0, null, null), SearchQuery.Text("the glass orchard"))
        val search = object : ReleaseSearch {
            override fun search(indexers: List<IndexerConfig>, fresh: Boolean, plan: (IndexerCaps) -> SearchQuery?): Flow<IndexerOutcome> = flowOf(answer)
        }
        val joined = mutableMapOf<String, Pair<Long, Availability?>>()
        CheckedSearch(search, check = check).search(listOf(prowlarr)) { answer.query }.collect { outcome ->
            (outcome as? IndexerOutcome.Checked)?.items?.forEach { joined[it.infoHash!!] = testScheduler.currentTime to it.availability }
        }
        assertEquals(Availability.Cached(listOf("tb")) as Availability?, joined.getValue(both).second, "TorBox's yes, whatever Premiumize does")
        assertTrue(joined.getValue(both).first < CheckedSearch.BOUND.toMillis(), "joined at ${joined.getValue(both).first} ms, not at the bound")
        assertEquals(Availability.NotChecked as Availability?, joined.getValue(neither).second, "TorBox's no, and nothing from Premiumize")
        assertEquals(listOf(listOf(both, neither)), torbox.asked)
    }

    @Test
    fun `with no account open, nothing is asked and every torrent is not checked`() {
        val (torbox, premiumize) = clients()
        torbox.failure = ServiceError.KeyRefused
        premiumize.failure = ServiceError.Limit(null)
        checked(prowlarr, both)
        val (settled, _) = checked(prowlarr, neither)
        assertEquals(mapOf(neither to Availability.NotChecked), settled)
        assertEquals(1 to 1, torbox.asked.size to premiumize.asked.size)
    }
}
