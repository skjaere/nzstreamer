package io.skjaere.usenet.app.debrid

import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.WatchTarget
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
import io.skjaere.usenet.player.HttpLink
import io.skjaere.usenet.player.LinkFailure
import io.skjaere.usenet.player.LinkUnavailable
import io.skjaere.usenet.player.PlayUris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The app's side of a debrid play's links (L172, W81): the link Opening got is the first the player opens with, a link is
 * held per listing and file, one over 50 minutes old or one the player found dead is asked of the account afresh, and the
 * service's refusals reach the player in its own buckets, only a refused key naming the service. A player made again
 * after the process went finds its listing on disk ([DebridListingStore.ensure]). Over a fake service; invented names.
 */
class ServiceLinksTest {
    @TempDir
    lateinit var dir: File

    /** An account's client whose links say how many it has given; [failure] fails them. */
    private class FakeService(override val kind: ServiceKind = ServiceKind.TorBox) : DebridService {
        var failure: ServiceError? = null
        var made = 0
        var url: (ServiceFile) -> String = { file -> "https://cdn.example.org/d/t${made}/${file.name}" }

        /** The release as the next link comes with it, added again (L234), once. */
        var again: Prepared? = null

        /** The handle each link was asked by. */
        val handles = mutableListOf<String?>()

        override suspend fun test(): Account = error("not asked")
        override suspend fun check(hashes: List<InfoHash>): Map<InfoHash, Boolean> = error("not asked")
        override suspend fun prepare(hash: InfoHash, pick: suspend (ServiceListing) -> ServiceFile?): Prepared = error("not asked")

        override suspend fun link(prepared: Prepared, file: ServiceFile): ServiceLink {
            failure?.let { throw ServiceException(it) }
            handles += prepared.handle
            made++
            return ServiceLink(url(file), Instant.ofEpochMilli(NOW), prepared = again.also { again = null })
        }
    }

    private val file by lazy { File(dir, "debrid/listings.json") }
    private val store by lazy { DebridListingStore(file, io = Dispatchers.Unconfined) }
    private val service = FakeService()
    private var now = NOW
    private var accounts = mapOf<String, DebridService>(ACCOUNT to service)
    private val links by lazy { ServiceLinks(store, { id -> accounts[id] }, now = { now }, httpsOnly = { true }) }

    private val listing = ServiceListing(
        listOf(ServiceFile(0, "Pack/Harbor.Lights.S01E01.mkv", 1_000, "f0"), ServiceFile(1, "Pack/Harbor.Lights.S01E02.mkv", 2_000, "f1")),
    )

    private fun kept(hash: InfoHash = HASH): DebridListingStore.Entry = runBlocking { store.put(ACCOUNT, "Harbor.Lights.S01.1080p.WEB-DL-TIDE", Prepared(hash, listing, listing.files[1], "t42")) }

    private fun target(entry: DebridListingStore.Entry, index: Int = 1) = PlayUris.Target.Service(entry.id, index)

    @Test
    fun `the link Opening got is the first the player opens with, and a dead one is asked afresh`() {
        val entry = kept()
        links.hold(entry.id, listing.files[1], ServiceLink("https://cdn.example.org/d/opening/Harbor.Lights.S01E02.mkv", Instant.ofEpochMilli(NOW)))
        val first = links.link(target(entry), stale = null)
        assertEquals("https://cdn.example.org/d/opening/Harbor.Lights.S01E02.mkv", first.url)
        assertEquals("Harbor.Lights.S01E02.mkv", first.name)
        assertEquals(0, service.made, "no call for the link Opening got")
        // The player found it dead: one from the account, held from then on.
        val fresh = links.link(target(entry), stale = first)
        assertEquals("https://cdn.example.org/d/t1/Harbor.Lights.S01E02.mkv", fresh.url)
        assertEquals(1, service.made)
        assertEquals(fresh, links.link(target(entry), stale = null))
        assertEquals(1, service.made)
    }

    @Test
    fun `a link is held per listing and file`() {
        val one = kept()
        val other = kept(OTHER)
        val e2 = links.link(target(one, 1), null)
        val e1 = links.link(target(one, 0), null)
        val otherE2 = links.link(target(other, 1), null)
        assertEquals(listOf("t1/Harbor.Lights.S01E02.mkv", "t2/Harbor.Lights.S01E01.mkv", "t3/Harbor.Lights.S01E02.mkv"), listOf(e2, e1, otherE2).map { it.url.substringAfter("/d/") })
        assertEquals(e2, links.link(target(one, 1), null))
        assertEquals(e1, links.link(target(one, 0), null))
        assertEquals(3, service.made)
    }

    @Test
    fun `a link over 50 minutes old is asked afresh, a younger one kept`() {
        val entry = kept()
        val first = links.link(target(entry), null)
        now += 49 * 60_000L
        assertEquals(first, links.link(target(entry), null))
        now += 2 * 60_000L
        val fresh = links.link(target(entry), null)
        assertEquals(2, service.made)
        assertEquals("https://cdn.example.org/d/t2/Harbor.Lights.S01E02.mkv", fresh.url)
    }

    @Test
    fun `a refresh the service refuses reaches the player in its buckets, only a refused key naming the service`() {
        val entry = kept()
        val stale = HttpLink("https://cdn.example.org/d/old/Harbor.Lights.S01E02.mkv", NOW)
        val buckets = listOf(
            ServiceError.NotCached to LinkFailure.NotCached,
            ServiceError.Removed to LinkFailure.Removed,
            ServiceError.Limit(Duration.ofMinutes(20)) to LinkFailure.Limit(20 * 60_000L),
            ServiceError.Limit() to LinkFailure.Limit(null),
            ServiceError.KeyRefused to LinkFailure.KeyRefused("TorBox"),
            ServiceError.Down to LinkFailure.Down,
            ServiceError.Refused(tooLarge = true) to LinkFailure.Refused(tooLarge = true),
            ServiceError.Refused() to LinkFailure.Refused(tooLarge = false),
        )
        for ((error, failure) in buckets) {
            service.failure = error
            assertEquals(failure, assertFailsWith<LinkUnavailable> { links.link(target(entry), stale) }.failure, "$error")
        }
        // Premiumize's refused key says its own name.
        accounts = mapOf(ACCOUNT to FakeService(ServiceKind.Premiumize).apply { failure = ServiceError.KeyRefused })
        assertEquals(LinkFailure.KeyRefused("Premiumize"), assertFailsWith<LinkUnavailable> { links.link(target(entry), stale) }.failure)
    }

    @Test
    fun `a listing no longer kept, an account since signed out of, or a link not over HTTPS gives no working link`() {
        assertEquals(LinkFailure.NoWorkingLink, assertFailsWith<LinkUnavailable> { links.link(PlayUris.Target.Service("0f3e9a1c5b7d2e4f6a8b0c1d2e3f4a5b", 0), null) }.failure)
        val entry = kept()
        accounts = emptyMap()
        assertEquals(LinkFailure.NoWorkingLink, assertFailsWith<LinkUnavailable> { links.link(target(entry), null) }.failure)
        accounts = mapOf(ACCOUNT to service)
        service.url = { f -> "http://cdn.example.org/d/plain/${f.name}" }
        assertEquals(LinkFailure.NoWorkingLink, assertFailsWith<LinkUnavailable> { links.link(target(entry), null) }.failure)
        // At the fakes' address (a device test's seam) a plain link is the fake's own.
        val atTheFakes = ServiceLinks(store, { id -> accounts[id] }, now = { now }, httpsOnly = { false })
        assertEquals("http://cdn.example.org/d/plain/Harbor.Lights.S01E02.mkv", atTheFakes.link(target(entry), null).url)
    }

    /**
     * L234: a link of a torrent the account no longer had comes with the release added again (a new handle and file ids),
     * which is kept in place of the old under the same listing id, so the player's URI and the add's record hold, and on
     * disk, so a player made again finds it; its link is handed out, and the next is asked by the new handle.
     */
    @Test
    fun `a release added again for a link is kept under the same listing id, and its link handed out`() = runBlocking<Unit> {
        val entry = kept()
        store.keep(entry.id)
        val renewed = ServiceListing(listing.files.map { it.copy(id = "n${it.index}") })
        val again = Prepared(HASH, renewed, renewed.files[1], "t77")
        service.again = again
        val link = links.link(target(entry), stale = null)
        assertEquals("https://cdn.example.org/d/t1/Harbor.Lights.S01E02.mkv", link.url)
        assertEquals(DebridListingStore.Entry(entry.id, ACCOUNT, entry.title, again), store.get(entry.id))
        // A new process finds the new handle.
        assertEquals(again, DebridListingStore(file, io = Dispatchers.Unconfined).get(entry.id)?.prepared)
        // The next link is asked by it, and one that comes with nothing changes nothing kept.
        val fresh = links.link(target(entry), stale = link)
        assertEquals(listOf<String?>("t42", "t77"), service.handles)
        assertEquals("https://cdn.example.org/d/t2/Harbor.Lights.S01E02.mkv", fresh.url)
        assertEquals(again, store.get(entry.id)?.prepared)
    }

    @Test
    fun `a player made again after the process went finds its listing on disk`() = runBlocking<Unit> {
        val entry = kept()
        store.keep(entry.id)
        // A new process: the store holds nothing in memory until asked.
        val again = DebridListingStore(file, io = Dispatchers.Unconfined)
        assertNull(again.kept(HASH))
        val launch = PlayerLaunch(
            PlayUris.service(entry.id, 1), "Harbor Lights S01E02", null, WatchKey.release("btih:${HASH.hex}", 1),
            WatchTarget(WatchKind.Release, "Harbor Lights S01E02"),
            SavedRelease("prowlarr", "btih:${HASH.hex}", "Harbor.Lights.S01.1080p.WEB-DL-TIDE", null, 1, "btih:${HASH.hex}", "Harbor.Lights.S01E02.mkv", 2_000, source = "torrent"),
        )
        assertEquals(entry, again.ensure(launch))
        assertNotNull(again.kept(HASH))
        // A Usenet launch, or one whose listing is gone, has none to put back.
        assertNull(again.ensure(launch.copy(uri = PlayUris.service("0f3e9a1c5b7d2e4f6a8b0c1d2e3f4a5b", 1))))
        assertNull(again.ensure(launch.copy(uri = "usenet://nzb/abc123/file/0")))
    }

    private companion object {
        const val ACCOUNT = "acct-7"
        const val NOW = 1_800_000_000_000L
        val HASH = InfoHash.parse("2f5a3c9e1b7d4f6a8c0e2b4d6f8a1c3e5b7d9f0a")!!
        val OTHER = InfoHash.parse("9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b")!!
    }
}
