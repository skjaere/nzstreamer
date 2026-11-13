package io.skjaere.usenet.app.link

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.BuildConfig
import io.skjaere.usenet.app.saveOrLog
import io.skjaere.usenet.data.LinkStore
import io.skjaere.usenet.data.PairedTv
import io.skjaere.usenet.handoff.SetupServer
import io.skjaere.usenet.link.Connect
import io.skjaere.usenet.link.ConnectFailure
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkClient
import io.skjaere.usenet.link.LinkClose
import io.skjaere.usenet.link.LinkHandler
import io.skjaere.usenet.link.LinkKey
import io.skjaere.usenet.link.LinkLimits
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.LinkMessage.Hello
import io.skjaere.usenet.link.LinkVersions
import io.skjaere.usenet.link.PairAttempt
import io.skjaere.usenet.link.PairCheck
import io.skjaere.usenet.link.PairHandler
import io.skjaere.usenet.link.PeerLink
import io.skjaere.usenet.link.Refusal
import io.skjaere.usenet.link.Sent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A TV's record as discovery found it: what it says ([ServiceRecord.read]), and the private IPv4 addresses it answers on. */
data class FoundTv(val record: ServiceRecord, val addresses: List<Inet4Address>, val port: Int)

/** Discovery's look for TVs' records (L12, L27): null while it isn't running, else what it has found so far. */
fun interface TvFinder {
    fun find(): Flow<List<FoundTv>?>
}

/** Discovery running since [since] (the links' clock), with the records it has found. */
class Discovery(val since: Long, val tvs: List<FoundTv>)

/** What the TV [tv] said on its link: its hello as the link came up, an answer to a command, a status, a Final. */
data class TvHeard(val tv: KeyId, val message: LinkMessage) {
    override fun toString() = "TvHeard(${tv.short}, $message)"
}

/**
 * A paired TV now: its [state], whether its link is up ("Seen now"), and whether its hello said it plays from its debrid
 * service alone ([fromService], [TvReach.fromService]): a start there lists the service's rows before the TV says them.
 */
data class TvNow(val state: TvState, val linked: Boolean, val fromService: Boolean = false)

/** What a phone's links to its TVs run on: the app's ([of]), or stand-ins, so they run on the JVM over loopback. */
class TvLinksDeps(
    val store: LinkStore,
    /** This install's link key (L20); keystore work, off the main thread. */
    val identity: suspend () -> LinkKey,
    val finder: TvFinder,
    /** Whether one of the app's activities is in front (L31). */
    val inFront: StateFlow<Boolean>,
    /** This phone's own name (L28), which the TVs are told. */
    val name: String,
    /** The app's version. */
    val app: String,
    val versions: LinkVersions = LinkVersions.V1,
    /** RFC 1918 IPv4 alone (L12); a test lets loopback in. */
    val allowPeer: (InetAddress) -> Boolean = SetupServer::isPrivateIpv4,
    val limits: LinkLimits = LinkLimits(),
    /** Monotonic milliseconds. */
    val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Where the blocking connects run. */
    val io: CoroutineDispatcher = Dispatchers.IO,
    /** Links close this long after the app leaves the front (L36). */
    val lingerMillis: Long = LINGER_MS,
    /** How long mDNS runs without the pinned id before another key under the saved name reads SetUpAgain (L12). */
    val setUpAgainMillis: Long = SET_UP_AGAIN_MS,
    /** The wait before the next try after so many (L36). */
    val backoff: (Int) -> Long = TvReach::backoff,
) {
    companion object {
        const val LINGER_MS = 10_000L
        const val SET_UP_AGAIN_MS = 10_000L

        fun of(c: AppContainer, context: Context): TvLinksDeps {
            val logged = AtomicBoolean()
            return TvLinksDeps(
                store = c.data.link,
                identity = {
                    val loaded = c.data.link.identity()
                    if (logged.compareAndSet(false, true)) {
                        log.info("Play on TV: the link identity is the {} one{}", loaded.identity.kind, if (loaded.renewed) "; a new one, so the pairings went with the old" else "")
                    }
                    loaded.identity.key
                },
                finder = LinkNsdFinder(context),
                inFront = c.inFront.value,
                name = PeerNames.own(Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME), Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty(), fallback = "Phone"),
                app = BuildConfig.VERSION_NAME,
            )
        }
    }
}

/**
 * The phone's side of Play on TV (L12, L36): its links to the TVs it paired with, and what each TV is to it now
 * ([states]). It holds one link per TV while the app is in front and a page lists the TVs ([hold]: Settings › TVs, the
 * Play on sheet), or that TV alone is held ([holdTv]: the TV Play goes to, a play started there), and closes them
 * [TvLinksDeps.lingerMillis] after the app leaves the front. Commands go out on a TV's link with numbers of their own
 * ([command]), and other words too ([send]: a FinalAck); what the TVs say is [heard], each TV's hello as its link comes
 * up. Each TV is tried at the address and port it was last reached at first, then wherever mDNS finds its key id, which
 * anyone may copy: places where another key answered, or nothing did in time, go last. Only RFC 1918
 * IPv4, and only the pinned key makes a link. Tries back off (2, 4, 8, 16, then every 30 s), and come at once when mDNS
 * sees the TV somewhere it wasn't tried. Discovery runs while in front with a TV paired or Add a TV looking ([look]).
 * Last seen and the address are written as a link comes up and as it closes, whichever end closes it. Logged at INFO:
 * links (the handshake's time), refusals and the identity's kind; never a frame or a name (L13).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TvLinks(private val scope: CoroutineScope, private val deps: TvLinksDeps) {
    private val _states = MutableStateFlow<Map<KeyId, TvNow>>(emptyMap())

    /** Each TV whose link is wanted, as it was found last; none for a TV not tried yet. */
    val states: StateFlow<Map<KeyId, TvNow>> = _states.asStateFlow()

    private val _discovery = MutableStateFlow<Discovery?>(null)

    /** What discovery has found while it runs; null while it doesn't. */
    val discovery: StateFlow<Discovery?> = _discovery.asStateFlow()

    /** Pages listing the TVs now, and Add a TV pages looking. */
    private val listing = MutableStateFlow(0)
    private val looking = MutableStateFlow(0)

    /** TVs whose link is held alone ([holdTv]), each with how many hold it. */
    private val single = MutableStateFlow<Map<KeyId, Int>>(emptyMap())

    private val _heard = MutableSharedFlow<TvHeard>(extraBufferCapacity = HEARD, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** What the TVs say on their links, as each comes, for whoever listens then (nothing is kept for later). */
    val heard: SharedFlow<TvHeard> = _heard.asSharedFlow()

    /** The commands' numbers: each one's own, across the TVs. */
    private val seqs = AtomicInteger()

    /** The paired TVs, by key, as the store has them. */
    private val rows = MutableStateFlow<Map<KeyId, PairedTv>>(emptyMap())

    /** When each record id was last known found: among discovery's finds, or as it went from them (the links' clock). */
    private val seenIds = ConcurrentHashMap<String, Long>()

    /** The links up now, by TV. */
    private val links = ConcurrentHashMap<KeyId, PeerLink>()

    /** The TVs being tried or linked, by key: touched by the one coroutine that follows what is wanted. */
    private val runners = HashMap<KeyId, Job>()
    private val clientLock = Mutex()
    @Volatile private var client: LinkClient? = null
    private val started = AtomicBoolean()

    /** Follows the app from now, for its life: what is wanted, linked, and discovered. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { deps.store.tvs.collect { tvs -> rows.value = tvs.mapNotNull { row -> row.key?.let { it to row } }.toMap() } }
        // Discovery (L36): in front, with a TV paired or Add a TV looking.
        scope.launch {
            combine(deps.inFront, rows.map { it.isNotEmpty() }, looking.map { it > 0 }) { front, paired, look -> front && (paired || look) }
                .distinctUntilChanged()
                .collectLatest { on -> if (on) discover() else _discovery.value = null }
        }
        // A recreation passes through out of front: the links close only a while after the app leaves it (L36).
        val front = deps.inFront.transformLatest { inFront ->
            if (!inFront) delay(deps.lingerMillis)
            emit(inFront)
        }.distinctUntilChanged()
        scope.launch {
            combine(front, listing.map { it > 0 }, single, rows) { inFront, listed, held, tvs ->
                when {
                    !inFront -> emptySet()
                    listed -> tvs.keys
                    else -> held.keys intersect tvs.keys
                }
            }
                .distinctUntilChanged()
                .collect { wanted ->
                    // A runner no longer wanted is cancelled and kept until it ends: one wanted again waits for it.
                    runners.forEach { (key, job) -> if (key !in wanted) job.cancel() }
                    runners.entries.removeAll { (key, job) -> key !in wanted && job.isCompleted }
                    for (key in wanted) {
                        val before = runners[key]
                        if (before?.isActive == true) continue
                        // One runner a TV: a new one waits for the one before to have closed its link, and keeps waiting
                        // though it is cancelled itself, so one after it waits for that one too.
                        runners[key] = scope.launch {
                            withContext(NonCancellable) { before?.join() }
                            ensureActive()
                            run(key)
                        }
                    }
                }
        }
    }

    /** A page lists the TVs (Settings › TVs): their links are held while it does, and the app is in front. */
    fun hold(): AutoCloseable = count(listing)

    /** Add a TV looks for TVs: discovery runs while it does, and the app is in front. */
    fun look(): AutoCloseable = count(looking)

    /**
     * One TV's link is wanted while the app is in front, though no page lists the TVs (L36): the TV Play goes to, and one
     * with a play this phone started there.
     */
    fun holdTv(key: KeyId): AutoCloseable {
        single.update { it + (key to (it[key] ?: 0) + 1) }
        val once = AtomicBoolean()
        return AutoCloseable {
            if (once.compareAndSet(false, true)) single.update { held -> held[key]?.let { n -> if (n > 1) held + (key to n - 1) else held - key } ?: held }
        }
    }

    /**
     * Sends the command [build] makes with a number of its own on the TV [key]'s link, without waiting: the number, which
     * the TV's answer names (L25); null when the link isn't up, or didn't take it.
     */
    fun command(key: KeyId, build: (Int) -> LinkMessage): Int? {
        val link = links[key]?.takeIf { it.isOpen } ?: return null
        val seq = seqs.incrementAndGet()
        return seq.takeIf { link.send(build(seq)) == Sent.Queued }
    }

    /** Sends [message], which isn't a command (a FinalAck), on the TV [key]'s link, without waiting: whether it went. */
    fun send(key: KeyId, message: LinkMessage): Boolean = links[key]?.takeIf { it.isOpen }?.send(message) == Sent.Queued

    private fun count(counter: MutableStateFlow<Int>): AutoCloseable {
        counter.update { it + 1 }
        val once = AtomicBoolean()
        return AutoCloseable { if (once.compareAndSet(false, true)) counter.update { it - 1 } }
    }

    /**
     * Pairs with the TV at [address] (W3, L21, L22), [prompt] showing the wait and the number on the way; cancelling the
     * coroutine stops it, and the TV sees this phone leave. Blocks on [TvLinksDeps.io]. The caller keeps the TV it gives.
     */
    suspend fun pair(address: InetSocketAddress, prompt: PairPrompt): PairAttempt.Result {
        val client = try {
            client()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Play on TV: no link key, so this phone can't pair", e)
            return PairAttempt.Result.Failed(ConnectFailure.Tls)
        }
        val attempt = client.pairing(address)
        val handler = object : PairHandler {
            override fun waiting() = prompt.waiting()

            override fun compare(check: PairCheck) = prompt.compare(check.tvName, check.number.digits) { yes -> if (yes) check.yes() else check.no() }
        }
        return coroutineScope {
            val run = async(deps.io) { attempt.run(handler) }
            try {
                run.await()
            } catch (e: CancellationException) {
                // A blocking read ignores the coroutine: closing the connection ends it.
                attempt.cancel()
                throw e
            }
        }
    }

    /**
     * Forget (L30): the TV goes here, the sessions this phone started there with it; and when its link is up, the TV is
     * told first (Unpair), so it removes this phone too, as Remove on the TV does. A TV out of reach is told nothing:
     * the phone is removed there under Settings › Phones.
     */
    suspend fun forget(key: KeyId) {
        links.remove(key)?.let { link ->
            link.finish(LinkMessage.Unpair)
            log.info("Play on TV: told a TV that this phone forgot it")
        }
        deps.store.forgetTv(key)
    }

    /** Ends every link and pairing now (a test's own links; the app's last as long as its process). */
    fun close() {
        client?.close()
    }

    /** The client this phone's links and pairings go through, made once with this install's key. */
    internal suspend fun client(): LinkClient = clientLock.withLock {
        client ?: LinkClient(deps.identity(), deps.name, deps.app, deps.versions, deps.allowPeer, limits = deps.limits).also { client = it }
    }

    /** Discovery's finds, while it runs. */
    private suspend fun discover() {
        var present = emptySet<String>()
        try {
            deps.finder.find().collect { tvs ->
                if (tvs == null) {
                    _discovery.value = null
                } else {
                    // Seen until now: those found, and those found until this moment (lost now).
                    val now = deps.clock()
                    val ids = tvs.mapTo(HashSet()) { it.record.id }
                    (ids + present).forEach { seenIds[it] = now }
                    present = ids
                    _discovery.value = Discovery(_discovery.value?.since ?: now, tvs)
                }
            }
        } finally {
            _discovery.value = null
        }
    }

    /** What discovery has found of the TV [key] now: every record under its id, since anyone may copy an id (L27). */
    private fun found(key: KeyId): List<FoundTv> = _discovery.value?.tvs?.filter { it.record.id == key.short }.orEmpty()

    /** Where [found]'s records say the TV is. */
    private fun places(found: List<FoundTv>): Set<InetSocketAddress> =
        found.flatMapTo(LinkedHashSet()) { tv -> tv.addresses.map { InetSocketAddress(it, tv.port) } }

    /** How long a TV unseen by mDNS must be before another key under its name is it set up again (L12). */
    internal val setUpAgainMillis: Long get() = deps.setUpAgainMillis

    /**
     * How long discovery has run without finding [key]'s id, by now, a record lost a moment ago counting as seen until
     * it went; null while it isn't running. The states' SetUpAgain reads it, and so does Add a TV's (L12).
     */
    internal fun unseenMillis(key: KeyId): Long? {
        val running = _discovery.value ?: return null
        if (running.tvs.any { it.record.id == key.short }) return 0
        return deps.clock() - maxOf(running.since, seenIds[key.short] ?: running.since)
    }

    /** The TV [key] tried, linked and tried again, until its link isn't wanted or the TV is forgotten. */
    private suspend fun run(key: KeyId) {
        val client = try {
            client()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Play on TV: no link key, so this phone reaches no TV", e)
            return
        }
        var tries = 0
        // The link up now, while this waits on it.
        var up: Tried.Up? = null
        // Where another key answered, or nothing did in time: tried last from then on.
        val misses = HashSet<InetSocketAddress>()
        try {
            while (true) {
                val row = rows.value[key] ?: return
                val known = places(found(key))
                val tried = attempt(client, key, row, known, misses)
                if (tried is Tried.Up) {
                    tries = 0
                    _states.update { it + (key to TvNow(TvReach.linked(tried.hello.ready), linked = true, fromService = TvReach.fromService(tried.hello.ready))) }
                    seen(key, tried.link, tried.port)
                    up = tried
                    val why = tried.closed.await()
                    up = null
                    links.remove(key, tried.link)
                    seen(key, tried.link, tried.port)
                    log.debug("Play on TV: a TV's link closed ({})", why)
                    _states.update { it + (key to TvNow(TvReach.closed(why), linked = false)) }
                } else if (tried is Tried.Down) {
                    _states.update { it + (key to TvNow(tried.state, linked = false)) }
                }
                pause(key, deps.backoff(tries++), known, tried)
            }
        } finally {
            // Most links end here, this phone closing its own (the page left, the app out of front): seen until now.
            up?.let { seen(key, it.link, it.port) }
            links.remove(key)?.close()
            _states.update { it - key }
        }
    }

    /**
     * Waits [millis] before the next try, or less: at once when mDNS sees the TV somewhere this try didn't go ([known]:
     * not as a record merely changes, which anyone's can), and at the moment another key under the saved name
     * ([Tried.Down.savedName]) would read SetUpAgain.
     */
    private suspend fun pause(key: KeyId, millis: Long, known: Set<InetSocketAddress>, tried: Tried) {
        var wait = millis
        if (tried is Tried.Down && tried.savedName) unseenMillis(key)?.let { unseen -> wait = minOf(wait, (deps.setUpAgainMillis - unseen).coerceAtLeast(0) + SETTLE_MS) }
        withTimeoutOrNull(wait) { _discovery.first { it != null && places(found(key)).any { place -> place !in known } } }
    }

    /**
     * One try at the TV [key]: where it was last reached ([row]) first (L12), then where mDNS finds its id ([known]),
     * those in [misses] last, until one presents the pinned key. Each connect blocks (2 s to connect, 10 s for TLS and the
     * hellos), on [TvLinksDeps.io].
     */
    private suspend fun attempt(client: LinkClient, key: KeyId, row: PairedTv, known: Set<InetSocketAddress>, misses: MutableSet<InetSocketAddress>): Tried {
        val remembered = row.address?.let(::parseIpv4)?.let { address -> row.port?.let { InetSocketAddress(address, it) } }
        val (fresh, missed) = (known - setOfNotNull(remembered)).partition { it !in misses }
        val targets = (listOfNotNull(remembered) + fresh + missed).filter { deps.allowPeer(it.address) }
        var refused: Refusal? = null
        var changed: Connect.KeyChanged? = null
        for (target in targets) {
            val closed = CompletableDeferred<LinkClose>()
            val handler = object : LinkHandler {
                // Posted on, never handled on the link's own thread.
                override fun received(link: PeerLink, message: LinkMessage) {
                    _heard.tryEmit(TvHeard(key, message))
                }

                override fun closed(link: PeerLink, reason: LinkClose) {
                    closed.complete(reason)
                }
            }
            val result = withContext(deps.io) {
                // Kept before the try can be cancelled, so the runner's end closes it.
                client.connect(target, key, handler).also { if (it is Connect.Linked) links[key] = it.link }
            }
            when (result) {
                is Connect.Linked -> {
                    log.info("Play on TV: linked to a TV ({}, {}, TLS in {} ms)", result.link.protocol, result.link.cipherSuite, result.link.handshakeMillis)
                    // Its hello names the play there now: a play this phone follows that has ended meanwhile is told so.
                    _heard.tryEmit(TvHeard(key, result.hello))
                    misses -= target
                    return Tried.Up(result.link, result.hello, target.port, closed)
                }
                // The pinned key spoke: its word stands.
                is Connect.Refused -> {
                    log.info("Play on TV: a TV refused this phone ({})", result.reason)
                    refused = result.reason
                    break
                }
                is Connect.KeyChanged -> {
                    log.debug("Play on TV: another key at a TV's address")
                    changed = result
                    misses += target
                }
                is Connect.Failed -> {
                    log.debug("Play on TV: a TV not reached ({})", result.why)
                    if (result.why == ConnectFailure.TimedOut) misses += target
                }
            }
        }
        val other = changed?.let { PeerNames.clean(it.name, "TV") }
        val state = when {
            refused != null -> TvReach.refused(refused)
            other != null -> TvReach.keyChanged(other, row.name, unseenMillis(key), deps.setUpAgainMillis)
            else -> TvState.NotOpen
        }
        return Tried.Down(state, savedName = other == row.name)
    }

    /** When and where the TV [key] was last seen: as its link comes up and as it goes (never on a failed try). */
    private fun seen(key: KeyId, link: PeerLink, port: Int) {
        scope.launch { saveOrLog("note when a TV was seen") { deps.store.tvSeen(key, link.address.hostAddress.orEmpty(), port) } }
    }

    /** How a try went. */
    private sealed interface Tried {
        /** Linked on [port]; [closed] completes as the link closes. */
        class Up(val link: PeerLink, val hello: Hello, val port: Int, val closed: CompletableDeferred<LinkClose>) : Tried

        /** Not linked, [state] for it; [savedName]: another key there gave the TV's saved name. */
        class Down(val state: TvState, val savedName: Boolean) : Tried
    }

    private companion object {
        /** A little past the moment, so the time has run by the next try. */
        const val SETTLE_MS = 50L

        /** What the TVs said that listeners haven't taken yet: a TV sends 4 statuses a second at most (L24). */
        const val HEARD = 64
    }
}

/** [text] as an IPv4 address written as four numbers; null for anything else (never a name to look up). */
internal fun parseIpv4(text: String): Inet4Address? {
    val parts = text.split('.')
    if (parts.size != 4) return null
    val bytes = ByteArray(4)
    for ((i, part) in parts.withIndex()) {
        if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' } || (part.length > 1 && part[0] == '0')) return null
        val value = part.toInt()
        if (value > 255) return null
        bytes[i] = value.toByte()
    }
    return InetAddress.getByAddress(bytes) as Inet4Address
}

private val log = LoggerFactory.getLogger(TvLinks::class.java)
