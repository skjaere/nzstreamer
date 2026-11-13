package io.skjaere.usenet.app.debrid

import io.skjaere.usenet.data.ServiceAccount
import io.skjaere.usenet.data.ServiceWithKey
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
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The service accounts' clients (L196, L175): the accounts that are on, in the stored order (L228); one client per account, with its own gate,
 * kept while its key and address stay; a refused key keeps its account out until a new one is saved; a key not saved yet
 * tried on a client of its own. Over fake clients and a mutable list of saved accounts, as the repository gives them.
 */
class ServiceRegistryTest {
    /** A client that answers as [answer] says, counting its calls. */
    private class FakeService(override val kind: ServiceKind, val key: String, val baseUrl: String, val gate: ServiceGate, val watch: AddWatch? = null) : DebridService {
        var answer: ServiceError? = null
        var calls = 0
        val removed = mutableListOf<String>()

        private suspend fun <T> call(value: T): T = gate.guarded {
            calls++
            answer?.let { throw ServiceException(it) }
            value
        }

        override suspend fun test(): Account = call(Account("quay.viewer@example.org"))
        override suspend fun check(hashes: List<InfoHash>): Map<InfoHash, Boolean> = call(hashes.associateWith { true })
        override suspend fun prepare(hash: InfoHash, pick: suspend (ServiceListing) -> ServiceFile?): Prepared = error("not asked")
        override suspend fun link(prepared: Prepared, file: ServiceFile): ServiceLink = error("not asked")
        override suspend fun remove(handle: String) = call(Unit).also { removed += handle }
    }

    private var now = Instant.parse("2026-11-05T12:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }

    private var saved = listOf(
        ServiceWithKey(ServiceAccount("tb", "TorBox", accountName = "quay.viewer@example.org"), "TB-KEY"),
        ServiceWithKey(ServiceAccount("pm", "Premiumize", accountName = "customer 900000042"), "PM-KEY"),
    )
    private val marked = mutableListOf<String>()
    private val made = mutableListOf<FakeService>()
    private var address = "https://fake.example"

    private val registry = ServiceRegistry(
        accounts = { saved },
        markRefused = { id ->
            marked += id
            saved = saved.map { if (it.record.id == id) it.copy(record = it.record.copy(keyRefused = true)) else it }
        },
        make = { kind, key, baseUrl, gate, watch -> FakeService(kind, key, baseUrl, gate, watch).also { made += it } },
        endpoints = { kind -> "$address/${kind.name.lowercase()}" },
        newGate = { ServiceGate(clock) },
        addWatch = { account -> NamedWatch(account.id, account.keySavedAt) },
    )

    /** The app's watch for an account, as the registry is given it: named by the account and its key's time. */
    private class NamedWatch(val account: String, val keySavedAt: Long) : AddWatch {
        override suspend fun wanted() = true
        override suspend fun added(hash: InfoHash, handle: String) = Unit
        override fun toString() = "watch($account, $keySavedAt)"
    }

    private fun ServiceRegistry.Client.fake() = service.unwrapped() as FakeService

    @Test
    fun `the accounts that are on come in the stored order, leaving out one off, one without a readable key, one refused and a service this version doesn't know`() = runBlocking<Unit> {
        assertEquals(listOf("tb", "pm"), registry.on(shown = true).map { it.id })
        saved = saved + listOf(
            ServiceWithKey(ServiceAccount("off", "TorBox", enabled = false), "K1"),
            ServiceWithKey(ServiceAccount("nokey", "Premiumize"), null),
            ServiceWithKey(ServiceAccount("refused", "TorBox", keyRefused = true), "K2"),
            ServiceWithKey(ServiceAccount("later", "SomeLaterService"), "K3"),
        )
        assertEquals(listOf("tb", "pm"), registry.on(shown = true).map { it.id })
        assertEquals(listOf("tb", "pm"), registry.usable(shown = true).map { it.account.id })
        assertEquals(listOf(ServiceKind.TorBox, ServiceKind.Premiumize), registry.usable(shown = true).map { it.kind })
    }

    /** L228, W85: the user's order is the stored one, not A to Z: Premiumize first only where it is stored first. */
    @Test
    fun `the accounts come in the order they are stored, as the user moved them`() = runBlocking<Unit> {
        saved = saved.reversed()
        assertEquals(listOf("pm", "tb"), registry.on(shown = true).map { it.id })
        assertEquals(listOf("pm", "tb"), registry.usable(shown = true).map { it.account.id })
        saved = saved.reversed()
        assertEquals(listOf("tb", "pm"), registry.on(shown = true).map { it.id })
        assertEquals(listOf("tb", "pm"), registry.usable(shown = true).map { it.account.id })
    }

    /** L192: hidden, an account counts for no gate, whatever a shown build saved. */
    @Test
    fun `hidden, no account is on`() = runBlocking<Unit> {
        assertEquals(emptyList(), registry.on(shown = false))
        assertEquals(emptyList(), registry.usable(shown = false))
    }

    @Test
    fun `a limit closes an account's gate, so it isn't usable until the wait is over, though it is still on`() = runBlocking<Unit> {
        val torBox = registry.usable(shown = true).single { it.kind == ServiceKind.TorBox }
        torBox.fake().answer = ServiceError.Limit(Duration.ofMinutes(20))
        assertFailsWith<ServiceException> { torBox.service.check(emptyList()) }
        assertEquals(listOf("pm"), registry.usable(shown = true).map { it.account.id })
        assertEquals(listOf("tb", "pm"), registry.on(shown = true).map { it.id }, "still on: Settings says so")
        // Not asked meanwhile: the gate answers.
        assertFailsWith<ServiceException> { torBox.service.check(emptyList()) }
        assertEquals(1, torBox.fake().calls)
        now = now.plus(Duration.ofMinutes(21))
        assertEquals(listOf("tb", "pm"), registry.usable(shown = true).map { it.account.id })
        assertEquals(emptyList(), marked)
    }

    @Test
    fun `a refused key marks its account, which stays out until a new key is saved, on a new client`() = runBlocking<Unit> {
        val torBox = registry.usable(shown = true).single { it.kind == ServiceKind.TorBox }
        torBox.fake().answer = ServiceError.KeyRefused
        val refused = assertFailsWith<ServiceException> { torBox.service.check(emptyList()) }
        assertEquals(ServiceError.KeyRefused, refused.error)
        assertEquals(listOf("tb"), marked)
        assertEquals(listOf("pm"), registry.on(shown = true).map { it.id })
        assertEquals(listOf("pm"), registry.usable(shown = true).map { it.account.id })
        // A new key saved (the repository clears the mark as it saves it).
        saved = saved.map { if (it.record.id == "tb") ServiceWithKey(it.record.copy(keyRefused = false), "TB-NEW") else it }
        val again = registry.usable(shown = true).single { it.kind == ServiceKind.TorBox }
        assertNotSame(torBox.fake(), again.fake())
        assertEquals("TB-NEW", again.fake().key)
        assertTrue(again.fake().gate.closed() == null, "a new key opens a new gate")
    }

    /** D2's #318 (item 16): a delete reaches the account's own client, and a key it finds refused marks the account. */
    @Test
    fun `a remove reaches the account's client, and a refused key on it marks the account`() = runBlocking<Unit> {
        val torBox = registry.service("tb")!!
        torBox.service.remove("5021337")
        assertEquals(listOf("5021337"), torBox.fake().removed)
        assertEquals(emptyList(), marked)
        torBox.fake().answer = ServiceError.KeyRefused
        assertEquals(ServiceError.KeyRefused, assertFailsWith<ServiceException> { torBox.service.remove("5021338") }.error)
        assertEquals(listOf("tb"), marked)
        assertEquals(listOf("5021337"), torBox.fake().removed)
    }

    /**
     * D2's #318 (item 17, L232): a saved account's client is made with the app's watch for that account where its service
     * adds to the account (TorBox), so what it adds is recorded under the key it was made with; Premiumize's, which adds
     * nothing, gets none, and so does a key not saved yet's. A new key's client gets the watch for the account as it is then.
     */
    @Test
    fun `a saved TorBox account's client gets its account's watch, Premiumize's and a key not saved yet's none`() = runBlocking<Unit> {
        registry.usable(shown = true)
        assertEquals(listOf(ServiceKind.TorBox to "watch(tb, 0)", ServiceKind.Premiumize to null), made.map { it.kind to it.watch?.toString() })
        registry.trial(ServiceKind.TorBox, "TB-TYPED")
        assertNull(made.last().watch, "a key not saved yet")
        saved = saved.map { if (it.record.id == "tb") ServiceWithKey(it.record.copy(keySavedAt = 1_762_000_000_000), "TB-NEW") else it }
        registry.usable(shown = true)
        assertEquals("watch(tb, 1762000000000)", made.last { it.kind == ServiceKind.TorBox }.watch.toString())
    }

    /**
     * EDL-2: a client is made for the account as it is read with its key; when the time its key was saved moves, the client
     * is made again with the watch for that time, though the key reads the same (another key saved and the first saved back
     * before anyone asked), so the watch never stays on a time the account no longer has.
     */
    @Test
    fun `a client is made again when the time the key was saved moves, though the key reads the same`() = runBlocking<Unit> {
        val first = registry.service("tb")!!.fake()
        saved = saved.map { if (it.record.id == "tb") it.copy(record = it.record.copy(keySavedAt = 1_762_000_000_007)) else it }
        val again = registry.service("tb")!!
        assertNotSame(first, again.fake())
        assertEquals("watch(tb, 1762000000007)", again.fake().watch.toString())
        assertEquals(1_762_000_000_007, again.account.keySavedAt)
        assertSame(again.fake(), registry.service("tb")!!.fake(), "kept while the key and its time stay")
    }

    @Test
    fun `a refusal of something else marks nothing`() = runBlocking<Unit> {
        val premiumize = registry.usable(shown = true).single { it.kind == ServiceKind.Premiumize }
        listOf(ServiceError.Refused(), ServiceError.Down, ServiceError.NotCached).forEach { error ->
            premiumize.fake().answer = error
            assertFailsWith<ServiceException> { premiumize.service.check(emptyList()) }
        }
        assertEquals(emptyList(), marked)
        assertEquals(listOf("tb", "pm"), registry.usable(shown = true).map { it.account.id })
    }

    @Test
    fun `one client per account, kept while its key and address stay, made again for a new address`() = runBlocking<Unit> {
        val first = registry.usable(shown = true).map { it.fake() }
        assertEquals(2, made.size)
        assertEquals(listOf("https://fake.example/torbox", "https://fake.example/premiumize"), first.map { it.baseUrl })
        assertEquals(listOf("TB-KEY", "PM-KEY"), first.map { it.key })
        registry.usable(shown = true).zip(first).forEach { (now, before) -> assertSame(before, now.fake()) }
        assertSame(first.first(), registry.service("tb")?.fake())
        assertEquals(2, made.size)
        address = "http://10.0.2.2:8097"
        assertEquals(listOf("http://10.0.2.2:8097/torbox", "http://10.0.2.2:8097/premiumize"), registry.usable(shown = true).map { it.fake().baseUrl })
        assertEquals(4, made.size)
    }

    @Test
    fun `a saved account's own page reaches its client while it is off, never one without a key`() = runBlocking<Unit> {
        saved = saved.map { if (it.record.id == "tb") it.copy(record = it.record.copy(enabled = false)) else it } +
            ServiceWithKey(ServiceAccount("nokey", "Premiumize"), null)
        assertEquals(ServiceKind.TorBox, registry.service("tb")?.kind)
        assertNull(registry.service("nokey"))
        assertNull(registry.service("gone"))
    }

    @Test
    fun `a key not saved yet is tried on a client of its own, and its refusal marks nothing`() = runBlocking<Unit> {
        val trial = registry.trial(ServiceKind.TorBox, "TB-TYPED")
        val fake = trial as FakeService
        assertEquals("TB-TYPED", fake.key)
        assertEquals("https://fake.example/torbox", fake.baseUrl)
        fake.answer = ServiceError.KeyRefused
        assertFailsWith<ServiceException> { trial.test() }
        assertEquals(emptyList(), marked)
        assertEquals(listOf("tb", "pm"), registry.usable(shown = true).map { it.account.id })
        assertTrue(made.none { it.key == "TB-TYPED" && it !== fake })
    }

    /**
     * TorBox's client is given the torrent an earlier play added to the account, from the listings the app keeps (L184,
     * L181): a Resume's Preparing confirms it with `mylist` and adds nothing. Over a fake TorBox, never TorBox.
     */
    @Test
    fun `TorBox's client takes the torrent an earlier play added from the kept listings, and adds nothing`() = runBlocking<Unit> {
        val hash = "c0ffee11c0ffee22c0ffee33c0ffee44c0ffee55"
        val file = "Harbor.Lights.S01.1080p.WEB-DL.H.264-TIDE/Harbor.Lights.S01E02.1080p.WEB-DL.H.264-TIDE.mkv"
        val calls = mutableListOf<String>()
        val http = HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath.substringAfter("/v1/api/")
                calls += path
                val body = when (path) {
                    "torrents/checkcached" -> """{"success":true,"data":[{"hash":"$hash","files":[{"name":"$file","size":950}]}]}"""
                    "torrents/mylist" -> """{"success":true,"data":{"id":5021337,"hash":"$hash","files":[{"id":3,"name":"$file","size":950}]}}"""
                    else -> """{"success":false,"error":"ENDPOINT_NOT_FOUND"}"""
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
        val asked = mutableListOf<InfoHash>()
        val make = ServiceRegistry.clients({ http }) { known -> asked += known; "5021337".takeIf { known.hex == hash } }
        val prepared = make(ServiceKind.TorBox, "TB-KEY", "https://torbox.fake.example/v1/api", ServiceGate(), null).prepare(InfoHash.of(hash)) { it.files.single() }
        assertEquals("5021337", prepared.handle)
        assertEquals(listOf("torrents/checkcached", "torrents/mylist"), calls)
        assertEquals(listOf(InfoHash.of(hash)), asked)
    }

    /**
     * D2's #318 (item 17): the app's TorBox client is given the account's watch, and tells it of an add the account's whole
     * list didn't hold, by the id TorBox answered; one the list holds it is told nothing of. Over a fake TorBox, never TorBox.
     */
    @Test
    fun `the app's TorBox client tells its watch of an add the whole list didn't hold, and of none it held`() = runBlocking<Unit> {
        val hash = "c0ffee11c0ffee22c0ffee33c0ffee44c0ffee55"
        val file = "Harbor.Lights.S01.1080p.WEB-DL.H.264-TIDE/Harbor.Lights.S01E02.1080p.WEB-DL.H.264-TIDE.mkv"
        var listed = "[]"
        val http = HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath.substringAfter("/v1/api/")
                val body = when {
                    path == "torrents/checkcached" -> """{"success":true,"data":[{"hash":"$hash","files":[{"name":"$file","size":950}]}]}"""
                    path == "torrents/mylist" && request.url.parameters["id"] == null -> """{"success":true,"data":$listed}"""
                    path == "torrents/createtorrent" -> """{"success":true,"data":{"hash":"$hash","torrent_id":5021338}}"""
                    path == "torrents/mylist" -> """{"success":true,"data":{"id":${request.url.parameters["id"]},"hash":"$hash","files":[{"id":3,"name":"$file","size":950}]}}"""
                    else -> """{"success":false,"error":"ENDPOINT_NOT_FOUND"}"""
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
        val told = mutableListOf<String>()
        val watch = object : AddWatch {
            override suspend fun wanted() = true
            override suspend fun added(hash: InfoHash, handle: String) {
                told += handle
            }
        }
        val torBox = ServiceRegistry.clients({ http })(ServiceKind.TorBox, "TB-KEY", "https://torbox.fake.example/v1/api", ServiceGate(), watch)
        assertEquals("5021338", torBox.prepare(InfoHash.of(hash)) { it.files.single() }.handle)
        assertEquals(listOf("5021338"), told)
        listed = """[{"id":5021338,"hash":"$hash","files":[{"id":3,"name":"$file","size":950}]}]"""
        assertEquals("5021338", torBox.prepare(InfoHash.of(hash)) { it.files.single() }.handle)
        assertEquals(listOf("5021338"), told, "held: nothing added, nothing told")
    }
}
