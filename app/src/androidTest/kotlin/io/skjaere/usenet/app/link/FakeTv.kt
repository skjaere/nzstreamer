package io.skjaere.usenet.app.link

import android.content.Context
import androidx.datastore.core.DataStore
import io.skjaere.usenet.data.LinkFile
import io.skjaere.usenet.data.LinkStore
import io.skjaere.usenet.handoff.SetupServer
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkClose
import io.skjaere.usenet.link.LinkKey
import io.skjaere.usenet.link.LinkLimits
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.LinkMessage.Hello
import io.skjaere.usenet.link.LinkServer
import io.skjaere.usenet.link.LinkVersions
import io.skjaere.usenet.link.NewPhone
import io.skjaere.usenet.link.PairQuestion
import io.skjaere.usenet.link.PairedHello
import io.skjaere.usenet.link.PairingEvent
import io.skjaere.usenet.link.PairingHost
import io.skjaere.usenet.link.PeerLink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet4Address
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/** A DataStore in memory: a test's own link.json, so the app's is never touched. */
class MemoryStore<T>(initial: T) : DataStore<T> {
    private val state = MutableStateFlow(initial)
    private val mutex = Mutex()
    override val data: Flow<T> = state
    override suspend fun updateData(transform: suspend (t: T) -> T): T = mutex.withLock { transform(state.value).also { state.value = it } }
}

/** A phone's store of its TVs, in memory. */
fun memoryLinkStore() = LinkStore(MemoryStore(LinkFile()))

/** This device's first private IPv4 address: where a fake TV in the test's process listens, as a TV would on its LAN. */
fun lanAddress(): Inet4Address = ownAddresses().filterIsInstance<Inet4Address>().first { it.isSiteLocalAddress }

/** An invented TV name with a random tail, so a test finds its own TV and no other (L14: the shards share one Wi-Fi). */
fun inventedTvName(base: String = "Test TV") = "$base ${Random.nextInt(1_000, 9_999)}"

/**
 * A TV in the test's process (L14): a real LinkServer on [address], its own [key] (so its own random `id`), knowing the
 * phones in [paired] and saying [ready] to them, and the play now ([session]) as their links come up, its record advertised through the app's own LinkNsd while [advertised].
 * One LinkNsd for its life, as the TV has, so its record's changes go to NSD one call at a time (L47): a record closed
 * a moment after it was made is taken down only once NSD has said it is up, and [close] returns once it is down. [port]
 * 0 takes any; [reopen] binds the same one again. In pairing mode ([pairing]) [answer] decides each question: true OKs it, false cancels it, null
 * leaves it to the test ([questions]).
 */
class FakeTv(
    private val context: Context,
    val name: String,
    val address: Inet4Address = lanAddress(),
    val key: LinkKey = softwareKey(),
    @Volatile var paired: Set<KeyId> = emptySet(),
    @Volatile var ready: Hello.Ready = Hello.Ready(provider = true, indexer = true, tmdb = true),
    @Volatile var session: Hello.Session? = null,
    private val versions: LinkVersions = LinkVersions.V1,
    port: Int = 0,
    private val advertised: Boolean = true,
    private val limits: LinkLimits = LinkLimits(),
) : AutoCloseable {
    val links = CopyOnWriteArrayList<PeerLink>()
    val received = CopyOnWriteArrayList<LinkMessage>()
    val questions = CopyOnWriteArrayList<PairQuestion>()
    val ended = CopyOnWriteArrayList<PairingEvent.Ended>()
    val saved = CopyOnWriteArrayList<NewPhone>()
    @Volatile var answer: Boolean? = true
    @Volatile private var server: LinkServer? = null
    private val nsd = LinkNsd(context)
    @Volatile private var record: AutoCloseable? = null
    @Volatile private var pairs = false
    @Volatile var port: Int = port
        private set

    /** Whether its record is up, wanted or waited on in NSD. */
    val advertising: Boolean get() = !nsd.settled

    private val handler = object : LinkServer.Handler {
        override fun linked(link: PeerLink, hello: Hello) {
            links += link
        }

        override fun received(link: PeerLink, message: LinkMessage) {
            received += message
        }

        override fun closed(link: PeerLink, reason: LinkClose) {
            links -= link
        }
    }

    private val host = object : PairingHost {
        override fun save(phone: NewPhone): Boolean {
            saved += phone
            paired = paired + phone.key
            return true
        }

        override fun heard(event: PairingEvent) {
            when (event) {
                is PairingEvent.Asking -> {
                    questions += event.question
                    when (answer) {
                        true -> event.question.ok()
                        false -> event.question.cancel()
                        null -> Unit
                    }
                }
                is PairingEvent.Ended -> ended += event
                else -> Unit
            }
        }
    }

    init {
        open()
    }

    private fun open() {
        val s = LinkServer(
            bind = address, port = port, key = key, name = name, app = "0.1.0", versions = versions,
            paired = { paired }, pairedHello = { PairedHello(ready, session) }, allowPeer = SetupServer::isPrivateIpv4, limits = limits,
            handler = handler, onEvent = {},
        )
        port = s.start()
        server = s
        if (pairs) s.openPairing(host)
        advertise()
    }

    private fun advertise() {
        record?.close()
        record = if (advertised) nsd.advertise(ServiceRecord.of(name, LinkVersions.V1.pv, key.id, pairs, address), port, null) else null
    }

    /** Its Pair a phone page opens: it takes pairings, and its record says `pair=1`. */
    fun pairing() {
        pairs = true
        server?.openPairing(host)
        advertise()
    }

    /**
     * NZStreamer on it closed: nothing listens, and its record is gone. Returns once NSD has said so, within two give-ups:
     * a run that ends right after would otherwise leave the record to the system as the process dies, mid-probe maybe,
     * the removal L47 keeps from the advertiser.
     */
    override fun close() {
        shut()
        val until = System.nanoTime() + RECORD_DOWN_MS * 1_000_000
        while (advertising && System.nanoTime() < until) Thread.sleep(50)
    }

    private fun shut() {
        record?.close()
        record = null
        server?.close()
        server = null
        // A closed server tells its handler nothing more.
        links.clear()
    }

    /** Opened again, on the same port, its record back: the old one goes down first, in the advertiser's own time. */
    fun reopen() {
        shut()
        open()
    }

    private companion object {
        /** Two of the advertiser's give-ups, and a little more. */
        const val RECORD_DOWN_MS = 2 * LinkNsd.GIVE_UP_MS + 5_000
    }
}
