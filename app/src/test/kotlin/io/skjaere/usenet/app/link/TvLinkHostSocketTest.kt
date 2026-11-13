package io.skjaere.usenet.app.link

import androidx.datastore.core.DataStore
import io.skjaere.usenet.app.settings.TestCipher
import io.skjaere.usenet.app.settings.memorySettings
import io.skjaere.usenet.app.setup.LanBinding
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.ServiceSignIn
import io.skjaere.usenet.data.LinkFile
import io.skjaere.usenet.data.LinkStore
import io.skjaere.usenet.link.Connect
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkClient
import io.skjaere.usenet.link.LinkClose
import io.skjaere.usenet.link.LinkHandler
import io.skjaere.usenet.link.LinkKey
import io.skjaere.usenet.link.LinkLimits
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.PairAttempt
import io.skjaere.usenet.link.PairCheck
import io.skjaere.usenet.link.PairHandler
import io.skjaere.usenet.link.PairOutcome
import io.skjaere.usenet.link.PeerLink
import io.skjaere.usenet.link.Playback
import io.skjaere.usenet.link.Refusal
import io.skjaere.usenet.link.Rejection
import io.skjaere.usenet.link.SelfSigned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The TV's listener (L4, L12, L27) over real sockets on loopback, with the app's pieces stood in for: a store in memory,
 * a software key, the addresses a test sets and an advertiser that records. It listens while the policy says so, on the
 * remembered port, advertises its record, and binds again when the address changes. The names are invented.
 */
class TvLinkHostSocketTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tvKey = linkKey()
    private val phoneKey = linkKey()
    private val otherKey = linkKey()
    private val memory = MemoryStore(LinkFile())
    private val store = LinkStore(memory)
    private val inFront = MutableStateFlow(true)
    private val addresses = MutableStateFlow<LanBinding?>(LanBinding(ip("127.0.0.1"), null))
    private val ready = MutableStateFlow(LinkMessage.Hello.Ready(provider = true, indexer = false, tmdb = true))

    /** What the TV's hello says of its readiness: [ready], unless a test reads it from settings before the host is first used. */
    private var hellos: Flow<LinkMessage.Hello.Ready> = ready
    private val advertiser = Recorder()
    private val clients = CopyOnWriteArrayList<LinkClient>()

    /** What the TV's plays were given, and how they answer: unsupported, as without them, unless a test says otherwise. */
    private val commanded = CopyOnWriteArrayList<Pair<AskingPhone, LinkMessage.Command>>()
    @Volatile private var commands: TvCommands = TvCommands.Unsupported

    /**
     * What hears of the phones' links besides the listener (the TV's status and Finals, L33, L34): which are up, the
     * session a test sets, and the Finals acked.
     */
    private val phoneLinks = object : PhoneLinks {
        val up = CopyOnWriteArrayList<PhoneLink>()
        val acked = CopyOnWriteArrayList<Pair<KeyId, List<String>>>()
        @Volatile var session: LinkMessage.Hello.Session? = null

        override fun linked(link: PhoneLink) {
            up += link
        }

        override fun closed(link: PhoneLink) {
            up.removeIf { it == link }
        }

        override fun session(phone: KeyId): LinkMessage.Hello.Session? = session?.takeIf { phone == phoneKey.id }

        override fun acked(phone: KeyId, ids: List<String>) {
            acked += phone to ids
        }
    }
    private val host by lazy {
        TvLinkHost(
            scope,
            TvLinkDeps(
                television = true, inFront = inFront, store = store, identity = { tvKey }, addresses = addresses, advertiser = advertiser,
                name = "Living room TV", app = "0.1.0", ready = hellos, allowPeer = { it.isLoopbackAddress }, limits = { limits }, defaultPort = 0,
                commands = { phone, command, answer ->
                    commanded += phone to command
                    commands.received(phone, command, answer)
                },
                links = phoneLinks,
            ),
        )
    }

    /** The listener's limits, read as it binds: a test shrinks pairing's window. */
    @Volatile private var limits = LinkLimits()

    @AfterEach
    fun stop() {
        clients.forEach { it.close() }
        scope.cancel()
    }

    private fun ip(address: String) = InetAddress.getByName(address) as Inet4Address

    private fun linkKey(): LinkKey {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        return LinkKey(pair.private, SelfSigned.certificate(pair))
    }

    private fun eventually(what: String, millis: Long = 5_000, condition: () -> Boolean) {
        val until = System.nanoTime() + millis * 1_000_000
        while (!condition()) {
            assertTrue(System.nanoTime() < until, what)
            Thread.sleep(20)
        }
    }

    private fun listening(): TvListener.Listening {
        eventually("listening") { host.state.value is TvListener.Listening }
        return host.state.value as TvListener.Listening
    }

    private fun open(address: Inet4Address, port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress(address, port), 1_000) } }.isSuccess

    private class Heard : LinkHandler {
        val closed = CopyOnWriteArrayList<LinkClose>()
        val received = CopyOnWriteArrayList<LinkMessage>()
        override fun received(link: PeerLink, message: LinkMessage) {
            received += message
        }
        override fun closed(link: PeerLink, reason: LinkClose) {
            closed += reason
        }
    }

    /** [key]'s link to the TV at [address]:[port], pinned to the TV's key. */
    private fun link(address: Inet4Address, port: Int, heard: Heard = Heard(), key: LinkKey = phoneKey): Connect {
        val client = LinkClient(key, "Galaxy phone", "0.1.0", allowPeer = { it.isLoopbackAddress }).also { clients += it }
        return client.connect(InetSocketAddress(address, port), tvKey.id, heard)
    }

    /** What Pair a phone hears, in order. */
    private class Page : PairPageListener {
        val heard = CopyOnWriteArrayList<PairPageEvent>()
        override fun heard(event: PairPageEvent) {
            heard += event
        }

        fun asked(): PhoneQuestion {
            val until = System.nanoTime() + 10_000_000_000
            while (System.nanoTime() < until) {
                heard.filterIsInstance<PairPageEvent.Asking>().lastOrNull()?.let { return it.question }
                Thread.sleep(20)
            }
            throw AssertionError("no question; heard $heard")
        }
    }

    /** A phone named [name] pairing with the TV at [address]:[port] on a thread of its own, saying yes to the number it shows. */
    private class Pairing(client: LinkClient, address: Inet4Address, port: Int) : PairHandler {
        val check = CompletableFuture<PairCheck>()
        val result = CompletableFuture<PairAttempt.Result>()
        private val attempt = client.pairing(InetSocketAddress(address, port))

        init {
            thread(isDaemon = true) { result.complete(attempt.run(this)) }
        }

        override fun waiting() = Unit

        override fun compare(check: PairCheck) {
            this.check.complete(check)
            check.yes()
        }
    }

    private fun pairing(on: TvListener.Listening, name: String = "Galaxy phone", key: LinkKey = phoneKey) =
        Pairing(LinkClient(key, name, "0.1.0", allowPeer = { it.isLoopbackAddress }).also { clients += it }, on.address, on.port)

    @Test
    fun `listens while in front with a phone paired, on a port it saves, its record advertised`() = runBlocking<Unit> {
        store.addPhone(phoneKey.id, "Galaxy phone", "127.0.0.1")
        host.start()
        val on = listening()
        assertEquals(ip("127.0.0.1"), on.address)
        eventually("the port saved") { runBlocking { store.file.first().port } == on.port }
        val record = advertiser.now()
        assertEquals(on.port, record.port)
        assertEquals(mapOf("pv" to "1", "id" to tvKey.id.short, "v4" to "127.0.0.1"), record.record.txt)
        assertEquals("Living room TV", record.record.name)

        val linked = assertIs<Connect.Linked>(link(on.address, on.port))
        assertEquals("Living room TV", linked.hello.name)
        assertEquals(LinkMessage.Hello.Ready(provider = true, indexer = false, tmdb = true), linked.hello.ready, "what it can play with, to a paired phone")
        eventually("the phone counted as connected") { host.connected.value == setOf(phoneKey.id) }
        linked.link.close()
        eventually("and no more once it left") { host.connected.value.isEmpty() }
    }

    /**
     * L224: the hello over real sockets says what the TV's settings say, by the one readiness rule: a TV with a service
     * on and a torrent indexer, and nothing of Usenet, plays from its service.
     */
    @Test
    fun `a TV set up only for its debrid service says it plays from it`() = runBlocking<Unit> {
        val settings = memorySettings()
        settings.saveService("TorBox", "TB-KEY") { ServiceSignIn("quay.viewer@example.org") }
        settings.saveTorrentIndexer(null, "Home Prowlarr", "http://192.168.1.20:9696/1/api", "TP-KEY")
        hellos = TvReadiness.hellos(settings) { true }
        store.addPhone(phoneKey.id, "Galaxy phone", "127.0.0.1")
        host.start()
        val on = listening()
        val linked = assertIs<Connect.Linked>(link(on.address, on.port))
        assertEquals(LinkMessage.Hello.Ready(provider = false, indexer = false, tmdb = false, debrid = true), linked.hello.ready)
        linked.link.close()
    }

    /**
     * L223: a TV whose enabled providers can't be used (here, a password it can't read) says it has no provider, as its
     * Play check has always found; the hello said it had one before.
     */
    @Test
    fun `a TV whose providers can't be used says it has no provider`() = runBlocking<Unit> {
        val cipher = TestCipher()
        val settings = memorySettings(cipher = cipher)
        settings.saveProvider(ProviderRecord("", "Quay News", "news.quay.invalid", username = "quay"), "harbor-bell-7")
        settings.saveIndexer(null, "Quay Indexer", "https://indexer.quay.invalid/api", "IX-KEY")
        cipher.unreadable += "harbor-bell-7"
        hellos = TvReadiness.hellos(settings) { true }
        store.addPhone(phoneKey.id, "Galaxy phone", "127.0.0.1")
        host.start()
        val on = listening()
        val linked = assertIs<Connect.Linked>(link(on.address, on.port))
        assertEquals(LinkMessage.Hello.Ready(provider = false, indexer = true, tmdb = false), linked.hello.ready)
        linked.link.close()
    }

    @Test
    fun `nothing listens with no phone paired and no pairing`() = runBlocking<Unit> {
        host.start()
        Thread.sleep(500)
        assertEquals(TvListener.Off, host.state.value)
        assertTrue(advertiser.records.isEmpty())
        // Paired now: it listens.
        store.addPhone(phoneKey.id, "Galaxy phone", "127.0.0.1")
        listening()
        store.removePhone(phoneKey.id)
        eventually("off again") { host.state.value == TvListener.Off }
    }

    /** Out of front (the screensaver, Home): the port closes and the record goes; back in front, both again on the same port. */
    @Test
    fun `closed out of front, the record gone`() = runBlocking<Unit> {
        store.addPhone(phoneKey.id, "Galaxy phone", "127.0.0.1")
        host.start()
        val on = listening()
        val heard = Heard()
        assertIs<Connect.Linked>(link(on.address, on.port, heard))
        inFront.value = false
        eventually("off") { host.state.value == TvListener.Off }
        assertTrue(!open(on.address, on.port), "the port closed")
        eventually("the phone's link closed") { heard.closed.isNotEmpty() }
        eventually("the record taken off") { advertiser.live.isEmpty() }
        inFront.value = true
        assertEquals(on.port, listening().port, "the remembered port again")
        assertIs<Connect.Linked>(link(on.address, on.port))
    }

    /** Settings › Phones' "Last seen": when the phone's link went, though it went as the listener closed (Home, the screensaver). */
    @Test
    fun `a phone's last seen is when its link went, though the listener closed with it`() = runBlocking<Unit> {
        store.addPhone(phoneKey.id, "Galaxy phone", "127.0.0.1")
        host.start()
        val on = listening()
        assertIs<Connect.Linked>(link(on.address, on.port))
        fun lastSeen() = runBlocking { store.phones.first() }.single().lastSeen
        eventually("seen as it linked") { lastSeen() != null }
        val linkedAt = lastSeen()!!
        Thread.sleep(300)
        inFront.value = false
        eventually("off") { host.state.value == TvListener.Off }
        eventually("seen as the listener closed, not as it linked") { lastSeen()!! >= linkedAt + 250 }
    }

    /** A new address (DHCP, Wi-Fi to Ethernet): the listener binds there, on its remembered port, and says so in its record. */
    @Test
    fun `binds again when the address changes`() = runBlocking<Unit> {
        store.addPhone(phoneKey.id, "Galaxy phone", "127.0.0.1")
        host.start()
        val first = listening()
        addresses.value = LanBinding(ip("127.0.0.2"), null)
        eventually("on the new address") { (host.state.value as? TvListener.Listening)?.address == ip("127.0.0.2") }
        val second = host.state.value as TvListener.Listening
        assertEquals(first.port, second.port)
        assertTrue(!open(ip("127.0.0.1"), first.port), "not on the old one")
        assertIs<Connect.Linked>(link(second.address, second.port))
        eventually("the record says the new one") { advertiser.live.singleOrNull()?.record?.txt?.get("v4") == "127.0.0.2" }
        addresses.value = null
        eventually("no network") { host.state.value == TvListener.NoNetwork }
        eventually("no record") { advertiser.live.isEmpty() }
    }

    /**
     * The system tells of the networks a moment after it is asked: Pair a phone says "no home network" only if none comes
     * within a grace, not for that moment.
     */
    @Test
    fun `no word of no network while the system tells of the networks`() = runBlocking<Unit> {
        addresses.value = null
        store.addPhone(phoneKey.id, "Galaxy phone", "127.0.0.1")
        val seen = CopyOnWriteArrayList<TvListener>()
        val watching = scope.launch { host.state.collect { seen += it } }
        host.start()
        Thread.sleep(500)
        addresses.value = LanBinding(ip("127.0.0.1"), null)
        listening()
        assertTrue(TvListener.NoNetwork !in seen, "no network on the way: $seen")
        watching.cancel()

        // With none for longer, it says so.
        inFront.value = false
        eventually("off") { host.state.value == TvListener.Off }
        addresses.value = null
        val back = System.nanoTime()
        inFront.value = true
        eventually("no network") { host.state.value == TvListener.NoNetwork }
        assertTrue(System.nanoTime() - back >= 1_000_000_000, "no network said before its grace")
    }

    /** A keystore that can't be read now (L20): Pair a phone says so instead of a blank page, and a later try may work. */
    @Test
    fun `a link key that can't be loaded is said so, and tried again as the policy changes`() = runBlocking<Unit> {
        val broken = java.util.concurrent.atomic.AtomicBoolean(true)
        val keyless = TvLinkHost(
            scope,
            TvLinkDeps(
                television = true, inFront = inFront, store = store, identity = { if (broken.get()) throw java.security.KeyStoreException("unreadable now") else tvKey },
                addresses = addresses, advertiser = advertiser, name = "Living room TV", app = "0.1.0", ready = ready, allowPeer = { it.isLoopbackAddress },
                defaultPort = 0,
            ),
        )
        store.addPhone(phoneKey.id, "Galaxy phone", "127.0.0.1")
        keyless.start()
        Thread.sleep(500)
        kotlin.test.assertNotEquals<TvListener>(TvListener.Off, keyless.state.value, "the page would show nothing at all")
        assertEquals(TvListener.NoKey, keyless.state.value)
        // Readable again: the next time the policy says listen, it listens.
        broken.set(false)
        inFront.value = false
        eventually("off") { keyless.state.value == TvListener.Off }
        inFront.value = true
        eventually("listening") { keyless.state.value is TvListener.Listening }
    }

    @Test
    fun `nothing on a phone`() = runBlocking<Unit> {
        store.addPhone(phoneKey.id, "Galaxy phone", "127.0.0.1")
        val phone = TvLinkHost(
            scope,
            TvLinkDeps(
                television = false, inFront = inFront, store = store, identity = { error("no key is needed") }, addresses = addresses,
                advertiser = advertiser, name = "Galaxy phone", app = "0.1.0", ready = ready, allowPeer = { true }, defaultPort = 0,
            ),
        )
        phone.start()
        Thread.sleep(500)
        assertEquals(TvListener.Off, phone.state.value)
        assertTrue(advertiser.records.isEmpty())
    }

    /** L22: a phone pairs through the page: the numbers agree, the TV's Pair and the phone's yes save it, and it links at once. */
    @Test
    fun `a phone pairs through the page and links at once`() = runBlocking<Unit> {
        host.start()
        val page = Page()
        host.openPairing(page)
        val on = listening()
        eventually("the record says pair=1") { advertiser.live.singleOrNull()?.record?.txt?.get("pair") == "1" }
        // The phone's own name, with a bidi override in it (L28).
        val phone = pairing(on, name = "Galaxy\u202E phone")
        val question = page.asked()
        assertEquals(phone.check.get(10, TimeUnit.SECONDS).number.digits, question.digits, "the same six digits on both")
        assertEquals("Galaxy phone", question.name, "cleaned")
        question.ok()
        val paired = assertIs<PairAttempt.Result.Paired>(phone.result.get(10, TimeUnit.SECONDS))
        assertEquals(tvKey.id, paired.tv)
        val saved = store.phones.first().single()
        assertEquals(phoneKey.id, saved.key)
        assertEquals("Galaxy phone", saved.name)
        assertEquals("127.0.0.1", saved.address)
        eventually("the page heard it paired") { page.heard.any { it is PairPageEvent.Ended && it.outcome == PairOutcome.Paired && it.question === question } }
        assertIs<Connect.Linked>(link(on.address, on.port), "linked at once, with its key pinned")
    }

    /** L22: pairing closes by itself (10 minutes, shrunk here): the page is told, and pair=1 leaves the record. */
    @Test
    fun `pairing closes by itself and the record drops pair=1`() = runBlocking<Unit> {
        limits = LinkLimits(pairingMillis = 1_000)
        store.addPhone(otherKey.id, "Pixel tablet", "127.0.0.1")
        host.start()
        val page = Page()
        host.openPairing(page)
        listening()
        eventually("pair=1") { advertiser.live.singleOrNull()?.record?.txt?.get("pair") == "1" }
        eventually("the page told pairing closed", millis = 5_000) { PairPageEvent.Closed in page.heard }
        eventually("pair=1 gone") { advertiser.live.singleOrNull()?.record?.txt?.containsKey("pair") == false }
        val on = listening()
        assertIs<PairAttempt.Result.Refused>(pairing(on).result.get(10, TimeUnit.SECONDS)).also { assertEquals(Refusal.NotPairing, it.reason) }
    }

    /**
     * A question showing as the listener binds again (a new address) or loses its network: the closed listener's
     * question is over, and the page hears so, so it shows the new address, or no network, not a question nobody can answer.
     */
    @Test
    fun `a question showing as the listener closes ends on the page`() = runBlocking<Unit> {
        host.start()
        val page = Page()
        host.openPairing(page)
        val on = listening()
        pairing(on)
        val first = page.asked()
        addresses.value = LanBinding(ip("127.0.0.2"), null)
        eventually("the page told the first question ended") {
            page.heard.any { it is PairPageEvent.Ended && it.question === first && it.outcome == PairOutcome.Closed }
        }
        eventually("on the new address") { (host.state.value as? TvListener.Listening)?.address == ip("127.0.0.2") }
        pairing(host.state.value as TvListener.Listening, key = otherKey)
        eventually("asked again") { page.heard.filterIsInstance<PairPageEvent.Asking>().size == 2 }
        val second = page.asked()
        addresses.value = null
        eventually("the page told the second question ended") {
            page.heard.any { it is PairPageEvent.Ended && it.question === second && it.outcome == PairOutcome.Closed }
        }
    }

    /**
     * L32: while Pair a phone's question shows, a phone's Play waits ([TvLinkHost.asking]); once it is answered, or the
     * page goes with it showing (which tells the page nothing more), or the listener closes under it, it no longer does.
     */
    @Test
    fun `a pairing question showing keeps a phone's Play waiting, until it ends or its page goes`() = runBlocking<Unit> {
        store.addPhone(otherKey.id, "Pixel tablet", "127.0.0.1")
        host.start()
        val page = Page()
        host.openPairing(page)
        val on = listening()
        assertEquals(false, host.asking, "no question yet")
        pairing(on)
        val first = page.asked()
        assertEquals(true, host.asking, "the question shows")
        first.cancel()
        eventually("no question once it is answered") { !host.asking }
        // Asked again, then the page left with it showing.
        eventually("pairing again after the pause", millis = 8_000) { pairing(on, key = linkKey()); true }
        eventually("asked again", millis = 8_000) { page.heard.filterIsInstance<PairPageEvent.Asking>().size == 2 }
        assertEquals(true, host.asking)
        host.closePairing(page)
        assertEquals(false, host.asking, "the page gone, with its question")
    }

    /** Leaving the page ends pairing mode; with no phone paired, the listener goes with it. */
    @Test
    fun `leaving the page ends pairing`() = runBlocking<Unit> {
        host.start()
        val page = Page()
        host.openPairing(page)
        val on = listening()
        host.closePairing(page)
        eventually("off: no phone and no pairing") { host.state.value == TvListener.Off }
        eventually("the record gone") { advertiser.live.isEmpty() }
        assertTrue(!open(on.address, on.port))
        // Another page's close doesn't close this one's.
        host.openPairing(page)
        listening()
        host.closePairing(Page())
        Thread.sleep(300)
        assertIs<TvListener.Listening>(host.state.value)
    }

    /** L30: Remove closes the phone's links at once, and it is refused after; another phone goes on. */
    @Test
    fun `Remove closes a phone's links at once`() = runBlocking<Unit> {
        store.addPhone(phoneKey.id, "Galaxy phone", "127.0.0.1")
        store.addPhone(otherKey.id, "Pixel tablet", "127.0.0.1")
        host.start()
        val on = listening()
        val heard = Heard()
        assertIs<Connect.Linked>(link(on.address, on.port, heard))
        eventually("connected") { phoneKey.id in host.connected.value }
        host.remove(phoneKey.id)
        eventually("its link closed") { heard.closed.isNotEmpty() }
        assertEquals(listOf(otherKey.id), store.phones.first().map { it.key })
        eventually("not connected") { phoneKey.id !in host.connected.value }
        assertEquals(Connect.Refused(Refusal.NotPaired), link(on.address, on.port))
        assertIs<Connect.Linked>(link(on.address, on.port, key = otherKey))
    }

    /**
     * L30: Remove's write is in, and the page that asked is gone before the write returns (Back on a slow box): the phone's
     * links still close, since the page can't cancel what Remove does.
     */
    @Test
    fun `Remove closes the phone's links though the page that asked is gone before its write returns`() = runBlocking<Unit> {
        store.addPhone(phoneKey.id, "Galaxy phone", "127.0.0.1")
        store.addPhone(otherKey.id, "Pixel tablet", "127.0.0.1")
        host.start()
        val on = listening()
        val heard = Heard()
        assertIs<Connect.Linked>(link(on.address, on.port, heard))
        eventually("connected") { phoneKey.id in host.connected.value }
        eventually("its last seen written") { runBlocking { store.phones.first() }.first { it.key == phoneKey.id }.lastSeen != null }
        val fsync = CompletableDeferred<Unit>()
        memory.written = { fsync.await() }
        // The page's own scope, cleared once the row is gone from the file.
        val page = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        page.launch { host.remove(phoneKey.id) }
        eventually("the row gone") { runBlocking { store.phones.first() }.map { it.key } == listOf(otherKey.id) }
        page.cancel()
        fsync.complete(Unit)
        memory.written = {}
        eventually("its link closed") { heard.closed.isNotEmpty() }
        eventually("not connected") { phoneKey.id !in host.connected.value }
    }

    /** L30: an Unpair from a paired phone does what Remove does. */
    @Test
    fun `an Unpair from a paired phone removes it and closes its links`() = runBlocking<Unit> {
        store.addPhone(phoneKey.id, "Galaxy phone", "127.0.0.1")
        store.addPhone(otherKey.id, "Pixel tablet", "127.0.0.1")
        host.start()
        val on = listening()
        val heard = Heard()
        val linked = assertIs<Connect.Linked>(link(on.address, on.port, heard))
        linked.link.send(LinkMessage.Unpair)
        eventually("its link closed") { heard.closed.isNotEmpty() }
        eventually("removed") { runBlocking { store.phones.first() }.map { it.key } == listOf(otherKey.id) }
    }

    /**
     * L4a: Remove of the only phone (the common household). The TV goes on listening, its record up without pair=1, so the
     * phone hears it was removed: its live link ends with not-paired, which the phone reads as RemovedHere, and so does its
     * next try. Anyone else gets the short hello and not-paired, or not-pairing when it came to pair.
     */
    @Test
    fun `with one phone paired, Remove keeps the TV listening, and the phone hears it was removed`() = runBlocking<Unit> {
        store.addPhone(phoneKey.id, "Galaxy phone", "127.0.0.1")
        host.start()
        val on = listening()
        val heard = Heard()
        assertIs<Connect.Linked>(link(on.address, on.port, heard))
        eventually("connected") { phoneKey.id in host.connected.value }
        host.remove(phoneKey.id)
        eventually("its link closed") { heard.closed.isNotEmpty() }
        assertEquals(TvState.RemovedHere, TvReach.closed(heard.closed.single()), "the phone's link ended with ${heard.closed}")
        assertEquals(emptyList(), store.phones.first())
        assertTrue(store.file.first().removedAt != null, "the Remove noted")

        Thread.sleep(300)
        assertEquals(on, host.state.value, "still listening, where it was")
        val record = advertiser.now()
        assertEquals(mapOf("pv" to "1", "id" to tvKey.id.short, "v4" to "127.0.0.1"), record.record.txt, "advertised, and not taking pairings")
        val again = assertIs<Connect.Refused>(link(on.address, on.port))
        assertEquals(TvState.RemovedHere, TvReach.refused(again.reason))
        assertEquals(Connect.Refused(Refusal.NotPaired), link(on.address, on.port, key = otherKey), "another key: not paired either")
        assertIs<PairAttempt.Result.Refused>(pairing(on, key = otherKey).result.get(10, TimeUnit.SECONDS)).also { assertEquals(Refusal.NotPairing, it.reason) }
        assertIs<TvListener.Listening>(host.state.value)
    }

    /** L4a: with one phone paired, that phone's own Unpair notes no Remove, and the TV stops listening as before (L4). */
    @Test
    fun `with one phone paired, its Unpair stops the listener as before`() = runBlocking<Unit> {
        store.addPhone(phoneKey.id, "Galaxy phone", "127.0.0.1")
        host.start()
        val on = listening()
        val heard = Heard()
        val linked = assertIs<Connect.Linked>(link(on.address, on.port, heard))
        linked.link.send(LinkMessage.Unpair)
        eventually("its link closed") { heard.closed.isNotEmpty() }
        eventually("off: no phone and no pairing") { host.state.value == TvListener.Off }
        eventually("the record gone") { advertiser.live.isEmpty() }
        assertEquals(null, store.file.first().removedAt, "no Remove noted")
        assertTrue(!open(on.address, on.port))
    }

    /** L4a: the 30 days end while the TV listens: it stops, as L4 alone says. */
    @Test
    fun `the TV stops listening when 30 days since the Remove are up`() = runBlocking<Unit> {
        store.setRemovedAt(System.currentTimeMillis() - TvLinkPolicy.REMOVED_LISTEN_MS + 1_500)
        host.start()
        listening()
        eventually("off once the 30 days are up", millis = 6_000) { host.state.value == TvListener.Off }
        eventually("the record gone") { advertiser.live.isEmpty() }
    }

    /** L4a: a Remove noted by a clock far ahead of this one (more than a day) keeps nothing listening. */
    @Test
    fun `a Remove noted more than a day ahead of the clock keeps nothing listening`() = runBlocking<Unit> {
        store.setRemovedAt(System.currentTimeMillis() + 2 * 24 * 60 * 60 * 1_000L)
        host.start()
        Thread.sleep(500)
        assertEquals(TvListener.Off, host.state.value)
        // A day back or less still counts.
        store.setRemovedAt(System.currentTimeMillis() + 12 * 60 * 60 * 1_000L)
        listening()
    }

    /** Until the TV plays what a phone asks (S1b), a command is answered, not left waiting. */
    @Test
    fun `a command is answered unsupported`() = runBlocking<Unit> {
        store.addPhone(phoneKey.id, "Galaxy phone", "127.0.0.1")
        host.start()
        val on = listening()
        val heard = Heard()
        val linked = assertIs<Connect.Linked>(link(on.address, on.port, heard))
        linked.link.send(LinkMessage.Pause(9, "s-1"))
        eventually("answered") { LinkMessage.Rejected(9, Rejection.Unsupported) in heard.received }
    }

    /**
     * L8: a paired phone's command goes to the TV's plays, with the phone as the TV keeps it (its key, and the name it was
     * paired under, not what its hello says), and what they answer reaches that phone.
     */
    @Test
    fun `a command goes to the TV's plays with the phone as kept, and their answer reaches it`() = runBlocking<Unit> {
        store.addPhone(phoneKey.id, "Galaxy phone", "127.0.0.1")
        commands = TvCommands { _, command, answer -> answer(LinkMessage.Ack(command.seq, "s-1")) }
        host.start()
        val on = listening()
        val heard = Heard()
        val linked = assertIs<Connect.Linked>(link(on.address, on.port, heard))
        linked.link.send(LinkMessage.Pause(9, "s-1"))
        eventually("answered") { LinkMessage.Ack(9, "s-1") in heard.received }
        assertEquals(listOf<Pair<AskingPhone, LinkMessage.Command>>(AskingPhone(phoneKey.id, "Galaxy phone") to LinkMessage.Pause(9, "s-1")), commanded.toList())
        // Not a command: nothing for the plays, but the Finals it names go (L33), the phone's own.
        linked.link.send(LinkMessage.FinalAck(listOf("f-1")))
        eventually("the Final acked") { phoneLinks.acked.toList() == listOf(phoneKey.id to listOf("f-1")) }
        assertEquals(1, commanded.size)
    }

    /**
     * L25, L34: a paired phone's hello names the play now, as what the TV says of its plays has it; that hears of each link
     * as it comes up, can send on it, and hears it go, as the phone leaves or the listener closes.
     */
    @Test
    fun `what the TV says of its plays reaches a paired phone's link, and its hello names the session`() = runBlocking<Unit> {
        store.addPhone(phoneKey.id, "Galaxy phone", "127.0.0.1")
        store.addPhone(otherKey.id, "Pixel tablet", "127.0.0.1")
        phoneLinks.session = LinkMessage.Hello.Session("s-1", mine = true)
        host.start()
        val on = listening()
        val heard = Heard()
        val linked = assertIs<Connect.Linked>(link(on.address, on.port, heard))
        assertEquals(LinkMessage.Hello.Session("s-1", mine = true), linked.hello.session)
        assertEquals(null, assertIs<Connect.Linked>(link(on.address, on.port, key = otherKey)).hello.session, "none for another phone, as the session says")
        eventually("both links heard of") { phoneLinks.up.size == 2 }
        val galaxy = phoneLinks.up.single { it.phone == phoneKey.id }
        galaxy.send(LinkMessage.Status("s-1", Playback.Playing, mine = true, title = "Harbor Lights"))
        eventually("the status reached the phone") { heard.received.any { it is LinkMessage.Status } }
        linked.link.close()
        eventually("its link gone") { phoneLinks.up.none { it.phone == phoneKey.id } }
        inFront.value = false
        eventually("the other's gone with the listener") { phoneLinks.up.isEmpty() }
    }

    /** What was advertised, in order, and what is up now. */
    private class Recorder : Advertiser {
        class Ad(val record: ServiceRecord, val port: Int)

        val records = CopyOnWriteArrayList<Ad>()
        val live = CopyOnWriteArrayList<Ad>()

        override fun advertise(record: ServiceRecord, port: Int, network: android.net.Network?): AutoCloseable {
            val ad = Ad(record, port)
            records += ad
            live += ad
            return AutoCloseable { live -= ad }
        }

        fun now(): Ad {
            val until = System.nanoTime() + 5_000_000_000
            while (live.isEmpty() && System.nanoTime() < until) Thread.sleep(20)
            return live.single()
        }
    }

    private class MemoryStore<T>(initial: T) : DataStore<T> {
        private val state = MutableStateFlow(initial)
        private val mutex = Mutex()

        /** What a write does once its data is in, as a file's fsync: a test holds it there. */
        @Volatile var written: suspend () -> Unit = {}
        override val data: Flow<T> = state
        override suspend fun updateData(transform: suspend (t: T) -> T): T = mutex.withLock {
            transform(state.value).also {
                state.value = it
                written()
            }
        }
    }
}
