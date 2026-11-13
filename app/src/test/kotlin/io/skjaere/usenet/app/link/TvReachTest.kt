package io.skjaere.usenet.app.link

import io.skjaere.usenet.data.LinkFile
import io.skjaere.usenet.data.LinkStore
import io.skjaere.usenet.data.PairedTv
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkClose
import io.skjaere.usenet.link.LinkKey
import io.skjaere.usenet.link.LinkLimits
import io.skjaere.usenet.link.LinkMessage.Hello
import io.skjaere.usenet.link.Need
import io.skjaere.usenet.link.LinkVersions
import io.skjaere.usenet.link.Refusal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.Inet4Address
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How a phone reaches its paired TVs (item 13, L12, L30, L36), over real sockets on loopback: fake TVs are real
 * LinkServers with keys of their own, discovery is what a test says it found, and the store is in memory. Each state
 * comes from what a TV says (its hello, a refusal) or the key TLS meets; another key at a TV's remembered address
 * reads NotOpen, or SetUpAgain once it gives the saved name and mDNS has run its time without the pinned id; mDNS
 * seeing the pinned TV elsewhere links there; mDNS losing it leaves a live link alone; last seen is written as a link
 * comes up and as it closes, never on a failed try. Waits are shrunk. The names are invented.
 */
class TvReachTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val phone = softwareLinkKey()
    private val store = LinkStore(MemoryStore(LinkFile()))
    private val inFront = MutableStateFlow(true)
    private val found = MutableStateFlow<List<FoundTv>?>(emptyList())
    private val tvs = CopyOnWriteArrayList<FakeTv>()
    private var versions = LinkVersions.V1
    private var limits = LinkLimits()
    private var backoff: (Int) -> Long = { 100 }
    private val links by lazy {
        TvLinks(
            scope,
            TvLinksDeps(
                store = store, identity = { phone }, finder = { found }, inFront = inFront, name = "Galaxy phone", app = "0.1.0",
                versions = versions, allowPeer = { it.isLoopbackAddress }, limits = limits, lingerMillis = 300, setUpAgainMillis = 600, backoff = backoff,
            ),
        )
    }
    private val listeners = CopyOnWriteArrayList<java.net.ServerSocket>()

    @AfterEach
    fun stop() {
        tvs.forEach { it.close() }
        listeners.forEach { it.close() }
        scope.cancel()
    }

    /** Something on the LAN at [address] that takes connections and says nothing, counting them in [accepts]. */
    private fun stalling(address: String, accepts: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger()): java.net.ServerSocket {
        val server = java.net.ServerSocket(0, 8, loopback(address)).also { listeners += it }
        thread(isDaemon = true) {
            val held = mutableListOf<java.net.Socket>()
            while (!server.isClosed) {
                held += runCatching { server.accept() }.getOrNull() ?: break
                accepts.incrementAndGet()
            }
            held.forEach { it.close() }
        }
        return server
    }

    /** A record anyone on the LAN can publish: [tv]'s id, copied, at [address]:[port]. */
    private fun decoy(tv: FakeTv, address: String, port: Int, pairing: Boolean = false) =
        FoundTv(ServiceRecord.of(tv.name, 1, tv.key.id, pairing, loopback(address)), listOf(loopback(address)), port)

    private fun tv(
        address: String,
        name: String = "Living room TV",
        knowsPhone: Boolean = true,
        ready: Hello.Ready = READY,
        versions: LinkVersions = LinkVersions.V1,
        key: LinkKey = softwareLinkKey(),
        port: Int = 0,
    ) = FakeTv(loopback(address), name, key, if (knowsPhone) setOf(phone.id) else emptySet(), ready, versions, port = port).also { tvs += it }

    /** The TV as this phone paired with it: [tv]'s key under [name], remembered at its address and port. */
    private fun paired(tv: FakeTv, name: String = tv.name, key: KeyId = tv.key.id) = runBlocking {
        assertTrue(store.addTv(key, name, tv.address.hostAddress!!, tv.port))
        key
    }

    private fun stateOf(key: KeyId) = links.states.value[key]

    private fun reads(key: KeyId, state: TvState, linked: Boolean? = null, millis: Long = 5_000) =
        eventually("$state${linked?.let { if (it) ", linked" else ", not linked" } ?: ""}", millis, now = { stateOf(key) }) {
            stateOf(key)?.let { it.state == state && (linked == null || it.linked == linked) } == true
        }

    private fun record(tv: FakeTv, at: Inet4Address = tv.address, pairing: Boolean = false) =
        FoundTv(ServiceRecord.of(tv.name, 1, tv.key.id, pairing, at), listOf(at), tv.port)

    private fun row(key: KeyId): PairedTv = runBlocking { store.tvs.first().single { it.key == key } }

    @Test
    fun `the waits between tries are 2, 4, 8 and 16 s, then every 30 s`() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), (0..6).map(TvReach::backoff))
    }

    /** L36: each failed try waits longer by its count, and a link starts the count again. */
    @Test
    fun `the tries' count gives each wait, and a link starts it again`() {
        val counts = CopyOnWriteArrayList<Int>()
        backoff = { tries -> counts += tries; 100 }
        val living = tv("127.0.0.2")
        val key = paired(living)
        living.close()
        links.start()
        links.hold()
        eventually("three tries failed") { counts.size >= 3 }
        living.reopen()
        reads(key, TvState.Ready, linked = true)
        val before = counts.size
        living.close()
        eventually("a try failed after the link") { counts.size >= before + 2 }
        val afterLink = counts.drop(before)
        assertEquals(listOf(0, 1, 2), counts.take(3), "the count by tries")
        // The link's end waits by a count started again, and the try after it goes on from there.
        assertEquals(listOf(0, 1), afterLink.take(2), "after the link, counted from the start")
    }

    /** L36: a try comes at once when mDNS sees the TV somewhere new, not after the wait (30 s at its longest). */
    @Test
    fun `a try comes at once when mDNS sees the TV somewhere new`() {
        backoff = { 5_000 }
        val living = tv("127.0.0.2")
        val key = paired(living)
        living.close()
        links.start()
        links.hold()
        reads(key, TvState.NotOpen, linked = false)
        Thread.sleep(200)
        val moved = tv("127.0.0.3", key = living.key)
        val seen = System.nanoTime()
        found.value = listOf(record(moved))
        reads(key, TvState.Ready, linked = true, millis = 2_000)
        assertTrue((System.nanoTime() - seen) / 1_000_000 < 2_000)
    }

    @Test
    fun `a TV's state from its hello is Ready, or what it needs to play`() {
        assertEquals(TvState.Ready, TvReach.linked(Hello.Ready(provider = true, indexer = true, tmdb = false)))
        assertEquals(TvState.NeedsProvider, TvReach.linked(Hello.Ready(provider = false, indexer = false, tmdb = true)))
        assertEquals(TvState.NeedsIndexer, TvReach.linked(Hello.Ready(provider = true, indexer = false, tmdb = true)))
        // D2 (L223): a TV that plays from its debrid service alone is ready; one whose debrid way lacks a part says which.
        assertEquals(TvState.Ready, TvReach.linked(Hello.Ready(provider = false, indexer = false, tmdb = false, debrid = true)))
        assertEquals(TvState.Ready, TvReach.linked(Hello.Ready(provider = true, indexer = false, tmdb = false, debrid = true)))
        assertEquals(TvState.NeedsService, TvReach.linked(Hello.Ready(provider = false, indexer = false, tmdb = false, needs = Need.Service)))
        assertEquals(TvState.ServiceOff, TvReach.linked(Hello.Ready(provider = false, indexer = false, tmdb = false, needs = Need.ServiceOff)))
        assertEquals(TvState.NeedsTorrentIndexer, TvReach.linked(Hello.Ready(provider = false, indexer = false, tmdb = false, needs = Need.TorrentIndexer)))
        // A newer TV's lack this phone doesn't know: Usenet's words, by provider and indexer.
        assertEquals(TvState.NeedsProvider, TvReach.linked(Hello.Ready(provider = false, indexer = true, tmdb = false, needs = Need.Unknown)))
        assertEquals(TvState.NeedsIndexer, TvReach.linked(Hello.Ready(provider = true, indexer = false, tmdb = false, needs = Need.Unknown)))
        val ready = tv("127.0.0.2")
        val noProvider = tv("127.0.0.3", "Bedroom TV", ready = Hello.Ready(provider = false, indexer = true, tmdb = true))
        val noIndexer = tv("127.0.0.4", "Guest room TV", ready = Hello.Ready(provider = true, indexer = false, tmdb = true))
        val keys = listOf(ready, noProvider, noIndexer).map { paired(it) }
        links.start()
        links.hold()
        reads(keys[0], TvState.Ready, linked = true)
        reads(keys[1], TvState.NeedsProvider, linked = true)
        reads(keys[2], TvState.NeedsIndexer, linked = true)
    }

    /**
     * L223, CWD-1: a TV whose hello says it plays from its debrid service alone, its service's way ready and no usable
     * provider, is kept so, as a start there lists its service's rows before the TV says them. One with a usable provider
     * searches as a Usenet TV does, its debrid way ready or not; one not ready, or a TV before D2, says nothing of it.
     */
    @Test
    fun `a TV that plays from its debrid service alone is kept so as its link comes up`() {
        assertTrue(TvReach.fromService(Hello.Ready(provider = false, indexer = false, tmdb = true, debrid = true)))
        assertTrue(TvReach.fromService(Hello.Ready(provider = false, indexer = true, tmdb = false, debrid = true)))
        assertEquals(false, TvReach.fromService(Hello.Ready(provider = true, indexer = false, tmdb = true, debrid = true)))
        assertEquals(false, TvReach.fromService(Hello.Ready(provider = true, indexer = true, tmdb = true, debrid = true)))
        assertEquals(false, TvReach.fromService(Hello.Ready(provider = false, indexer = false, tmdb = true, needs = Need.ServiceOff)))
        assertEquals(false, TvReach.fromService(READY))
        assertEquals(false, TvReach.fromService(null))
        val living = paired(tv("127.0.0.2", ready = Hello.Ready(provider = false, indexer = false, tmdb = true, debrid = true)))
        val bedroom = paired(tv("127.0.0.3", "Bedroom TV", ready = Hello.Ready(provider = true, indexer = true, tmdb = true, debrid = true)))
        links.start()
        links.hold()
        reads(living, TvState.Ready, linked = true)
        reads(bedroom, TvState.Ready, linked = true)
        assertEquals(TvNow(TvState.Ready, linked = true, fromService = true), stateOf(living))
        assertEquals(TvNow(TvState.Ready, linked = true, fromService = false), stateOf(bedroom))
    }

    @Test
    fun `a TV that doesn't know the phone reads removed here, versions too far apart an update on whichever end is older`() {
        assertEquals(TvState.RemovedHere, TvReach.refused(Refusal.NotPaired))
        assertEquals(TvState.UpdateTv, TvReach.refused(Refusal.UpdateTv))
        assertEquals(TvState.UpdatePhone, TvReach.refused(Refusal.UpdatePhone))
        assertEquals(TvState.UpdatePhone, TvReach.refused(Refusal.Unknown), "a refusal this version doesn't know (L26)")
        assertEquals(TvState.NotOpen, TvReach.refused(Refusal.Busy))
        assertEquals(TvState.RemovedHere, TvReach.closed(LinkClose.Refused(Refusal.NotPaired)))
        assertEquals(TvState.NotOpen, TvReach.closed(LinkClose.PeerLeft))
        val removed = paired(tv("127.0.0.2", knowsPhone = false))
        val newer = paired(tv("127.0.0.3", "Bedroom TV", versions = LinkVersions(pv = 3, minPv = 2)))
        links.start()
        links.hold()
        reads(removed, TvState.RemovedHere, linked = false)
        reads(newer, TvState.UpdatePhone, linked = false)
    }

    @Test
    fun `an older TV reads update the TV`() {
        versions = LinkVersions(pv = 3, minPv = 2)
        val older = paired(tv("127.0.0.2"))
        links.start()
        links.hold()
        reads(older, TvState.UpdateTv, linked = false)
    }

    @Test
    fun `Remove on the TV closes the link, and the phone then reads removed here`() {
        val living = tv("127.0.0.2")
        val key = paired(living)
        links.start()
        links.hold()
        reads(key, TvState.Ready, linked = true)
        living.remove(phone.id)
        reads(key, TvState.RemovedHere, linked = false)
    }

    @Test
    fun `NotOpen while nothing answers, Ready again once the TV is open`() {
        val living = tv("127.0.0.2")
        val key = paired(living)
        links.start()
        links.hold()
        reads(key, TvState.Ready, linked = true)
        living.close()
        reads(key, TvState.NotOpen, linked = false)
        living.reopen()
        reads(key, TvState.Ready, linked = true)
    }

    @Test
    fun `another key at the remembered address reads NotOpen, and Ready once mDNS sees the pinned TV elsewhere`() {
        val living = tv("127.0.0.2")
        val key = paired(living)
        living.close()
        // DHCP gave the address to another TV of another name: the remembered port, another key.
        tv("127.0.0.2", "Kitchen TV", port = living.port)
        val moved = tv("127.0.0.3", key = living.key)
        links.start()
        links.hold()
        reads(key, TvState.NotOpen, linked = false)
        found.value = listOf(record(moved))
        reads(key, TvState.Ready, linked = true)
        assertEquals("127.0.0.3", row(key).address, "the address is changed by a pinned connection alone")
    }

    /** L12: where the TV was last reached comes first; a record that copies its id and stalls can't hold the link up. */
    @Test
    fun `the remembered address comes first, before a record under the TV's id`() {
        val living = tv("127.0.0.2")
        val key = paired(living)
        found.value = listOf(decoy(living, "127.0.0.5", stalling("127.0.0.5").localPort))
        links.start()
        links.hold()
        reads(key, TvState.Ready, linked = true, millis = 3_000)
    }

    /** A TV that moved is reached where its own record says, though a record that copies its id comes first. */
    @Test
    fun `every record under the TV's id is tried, not only the first`() {
        limits = LinkLimits(helloMillis = 1_000)
        val living = tv("127.0.0.2")
        val key = paired(living)
        living.close()
        val moved = tv("127.0.0.3", key = living.key)
        found.value = listOf(decoy(living, "127.0.0.5", stalling("127.0.0.5").localPort), record(moved))
        links.start()
        links.hold()
        reads(key, TvState.Ready, linked = true, millis = 5_000)
        assertEquals("127.0.0.3", row(key).address)
    }

    /** A record that changes again and again (its TXT flapping) doesn't make the phone try it again and again. */
    @Test
    fun `a record that keeps changing doesn't bring the next try on at once`() {
        limits = LinkLimits(helloMillis = 300)
        backoff = { 5_000 }
        val living = tv("127.0.0.2")
        val key = paired(living)
        living.close()
        val accepts = java.util.concurrent.atomic.AtomicInteger()
        val port = stalling("127.0.0.5", accepts).localPort
        found.value = listOf(decoy(living, "127.0.0.5", port))
        links.start()
        links.hold()
        eventually("tried once") { accepts.get() == 1 }
        val until = System.nanoTime() + 1_500_000_000
        var pairing = false
        while (System.nanoTime() < until) {
            pairing = !pairing
            found.value = listOf(decoy(living, "127.0.0.5", port, pairing))
            Thread.sleep(50)
        }
        assertEquals(1, accepts.get(), "tried again as its record changed")
        assertEquals(TvState.NotOpen, stateOf(key)?.state)
    }

    @Test
    fun `another key giving another name reads NotOpen, however long mDNS runs`() {
        val living = tv("127.0.0.2")
        val key = paired(living)
        living.close()
        tv("127.0.0.2", "Kitchen TV", port = living.port)
        links.start()
        links.hold()
        reads(key, TvState.NotOpen, linked = false)
        Thread.sleep(1_000)
        assertEquals(TvState.NotOpen, stateOf(key)?.state)
    }

    @Test
    fun `another key giving the saved name reads SetUpAgain once mDNS has run its time without the pinned id`() {
        val living = tv("127.0.0.2")
        val key = paired(living)
        living.close()
        // NZStreamer there set up again: a new key, the same name.
        tv("127.0.0.2", "Living room TV", port = living.port)
        val started = System.nanoTime()
        links.start()
        links.hold()
        // Not before mDNS has had its time to find the pinned TV elsewhere (DHCP may have moved it).
        reads(key, TvState.NotOpen, linked = false)
        reads(key, TvState.SetUpAgain, linked = false)
        val after = (System.nanoTime() - started) / 1_000_000
        assertTrue(after >= 550, "SetUpAgain $after ms after discovery started, not its 600 ms")
        assertEquals(living.key.id, row(key).key, "the saved pin is kept")
    }

    @Test
    fun `a TV's record lost after a long quiet counts as unseen from the moment it went`() {
        val living = tv("127.0.0.2")
        val key = paired(living)
        found.value = listOf(record(living))
        links.start()
        links.hold()
        reads(key, TvState.Ready, linked = true)
        // Longer than the wait without a new word from discovery, the record still there.
        Thread.sleep(900)
        living.close()
        tv("127.0.0.2", "Living room TV", port = living.port)
        val lost = System.nanoTime()
        found.value = emptyList()
        reads(key, TvState.SetUpAgain, linked = false)
        val after = (System.nanoTime() - lost) / 1_000_000
        assertTrue(after >= 500, "SetUpAgain $after ms after the record went, not its 600 ms")
    }

    /**
     * Add a TV takes a new key under a kept TV's name, at its address, for that TV set up again only by L12's check
     * (L30): its deps count the kept TV's time out of mDNS as the TV's own state does, from the moment its record went.
     */
    @Test
    fun `Add a TV counts a kept TV's time unseen by mDNS as its state does`() {
        val living = tv("127.0.0.2")
        val key = paired(living)
        val add = AddTvDeps.of(links, store)
        assertEquals(600, add.setUpAgainMillis, "the links' own wait")
        assertNull(add.unseen(key), "discovery not running")
        found.value = listOf(record(living))
        links.start()
        eventually("found") { links.discovery.value?.tvs?.isNotEmpty() == true }
        Thread.sleep(700)
        assertEquals(0L, add.unseen(key), "found now, however long discovery has run")
        val lost = System.nanoTime()
        found.value = emptyList()
        eventually("lost") { links.discovery.value?.tvs?.isEmpty() == true }
        val unseen = assertNotNull(add.unseen(key))
        assertTrue(unseen <= (System.nanoTime() - lost) / 1_000_000 + 1, "counted from the moment the record went ($unseen ms), not from discovery's start")
        eventually("its time unseen", now = { add.unseen(key) }) { (add.unseen(key) ?: 0) >= 600 }
        inFront.value = false
        eventually("discovery stopped") { links.discovery.value == null }
        assertNull(add.unseen(key), "discovery not running")
    }

    @Test
    fun `while mDNS sees the pinned id, another key with the saved name isn't taken for a TV set up again`() {
        assertEquals(TvState.NotOpen, TvReach.keyChanged("Living room TV", "Living room TV", unseenMillis = 300, setUpAgainMillis = 10_000))
        assertEquals(TvState.NotOpen, TvReach.keyChanged("Living room TV", "Living room TV", unseenMillis = null, setUpAgainMillis = 10_000), "discovery not running")
        assertEquals(TvState.NotOpen, TvReach.keyChanged("Kitchen TV", "Living room TV", unseenMillis = 60_000, setUpAgainMillis = 10_000))
        assertEquals(TvState.SetUpAgain, TvReach.keyChanged("Living room TV", "Living room TV", unseenMillis = 10_000, setUpAgainMillis = 10_000))
    }

    @Test
    fun `mDNS losing the TV leaves its live link alone`() {
        val living = tv("127.0.0.2")
        val key = paired(living)
        found.value = listOf(record(living))
        links.start()
        links.hold()
        reads(key, TvState.Ready, linked = true)
        found.value = emptyList()
        Thread.sleep(500)
        assertEquals(TvNow(TvState.Ready, linked = true), stateOf(key))
        assertEquals(1, living.links.size)
    }

    @Test
    fun `last seen is written as a link comes up and as it closes, not on a failed try`() {
        val living = tv("127.0.0.2")
        val key = paired(living)
        assertNull(row(key).lastSeen)
        links.start()
        links.hold()
        reads(key, TvState.Ready, linked = true)
        eventually("seen as it linked") { row(key).lastSeen != null }
        val linked = row(key).lastSeen!!
        Thread.sleep(50)
        living.close()
        reads(key, TvState.NotOpen, linked = false)
        eventually("seen as it closed") { row(key).lastSeen!! > linked }
        val closed = row(key).lastSeen
        // Several tries fail meanwhile (100 ms apart here).
        Thread.sleep(600)
        assertEquals(closed, row(key).lastSeen)
        assertEquals("127.0.0.2", row(key).address)
    }

    /** Most links end this way: the phone closes its own as the page leaves, and the TV was seen until then. */
    @Test
    fun `last seen is written as the phone closes a link itself`() {
        val living = tv("127.0.0.2")
        val key = paired(living)
        links.start()
        val page = links.hold()
        reads(key, TvState.Ready, linked = true)
        eventually("seen as it linked") { row(key).lastSeen != null }
        val linked = row(key).lastSeen!!
        Thread.sleep(300)
        page.close()
        eventually("closed with the page") { living.links.isEmpty() }
        eventually("seen as this phone closed it, not as it linked") { row(key).lastSeen!! >= linked + 250 }
    }

    @Test
    fun `links are held while a page lists TVs and the app is in front, and close a while after it leaves`() {
        val living = tv("127.0.0.2")
        val key = paired(living)
        links.start()
        Thread.sleep(300)
        assertNull(stateOf(key), "nothing held, no link")
        assertEquals(0, living.links.size)
        val page = links.hold()
        reads(key, TvState.Ready, linked = true)
        inFront.value = false
        Thread.sleep(100)
        assertEquals(1, living.links.size, "not at once: a recreation passes through")
        eventually("closed after the linger") { living.links.isEmpty() }
        inFront.value = true
        reads(key, TvState.Ready, linked = true)
        page.close()
        eventually("closed with the page") { living.links.isEmpty() }
        assertNotNull(store)
    }

    /** A TV wanted again while its last try still waits (a TV that never answers TLS) gets one try at a time, never two. */
    @Test
    fun `a TV wanted again waits for its last try to end`() = runBlocking<Unit> {
        val open = java.util.concurrent.atomic.AtomicInteger()
        val most = java.util.concurrent.atomic.AtomicInteger()
        val silent = java.net.ServerSocket(0, 8, loopback("127.0.0.2"))
        thread(isDaemon = true) {
            while (!silent.isClosed) {
                val socket = runCatching { silent.accept() }.getOrNull() ?: break
                most.accumulateAndGet(open.incrementAndGet(), ::maxOf)
                // Says nothing; counts the connection until the phone gives up on it.
                thread(isDaemon = true) {
                    runCatching { while (socket.getInputStream().read() >= 0) Unit }
                    open.decrementAndGet()
                    socket.close()
                }
            }
        }
        try {
            limits = LinkLimits(helloMillis = 1_500)
            assertTrue(store.addTv(softwareLinkKey().id, "Living room TV", "127.0.0.2", silent.localPort))
            links.start()
            val page = links.hold()
            eventually("trying") { open.get() == 1 }
            page.close()
            Thread.sleep(50)
            val again = links.hold()
            Thread.sleep(600)
            assertEquals(1, most.get(), "two tries at one TV at once")
            // Twice more while the first still waits: the page shown, left, and shown again.
            again.close()
            Thread.sleep(50)
            links.hold().close()
            Thread.sleep(50)
            val third = links.hold()
            Thread.sleep(600)
            assertEquals(1, most.get(), "two tries at one TV at once, after two quick toggles")
            third.close()
        } finally {
            silent.close()
        }
    }

    @Test
    fun `discovery runs while in front with a TV paired or Add a TV looking`() = runBlocking<Unit> {
        links.start()
        Thread.sleep(200)
        assertNull(links.discovery.value, "no TV paired, nobody looking")
        val looking = links.look()
        eventually("discovering for Add a TV") { links.discovery.value != null }
        looking.close()
        eventually("stopped") { links.discovery.value == null }
        paired(tv("127.0.0.2"))
        eventually("discovering with a TV paired") { links.discovery.value != null }
        inFront.value = false
        eventually("not out of front") { links.discovery.value == null }
    }

    private companion object {
        val READY = Hello.Ready(provider = true, indexer = true, tmdb = true)
    }
}
