package io.skjaere.usenet.app.link

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.BuildConfig
import io.skjaere.usenet.app.saveOrLog
import io.skjaere.usenet.app.setup.LanAddress
import io.skjaere.usenet.app.setup.LanBinding
import io.skjaere.usenet.data.LinkStore
import io.skjaere.usenet.handoff.SetupServer
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkClose
import io.skjaere.usenet.link.LinkEvent
import io.skjaere.usenet.link.LinkKey
import io.skjaere.usenet.link.LinkLimits
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.LinkMessage.Hello
import io.skjaere.usenet.link.LinkServer
import io.skjaere.usenet.link.LinkVersions
import io.skjaere.usenet.link.NewPhone
import io.skjaere.usenet.link.PairOutcome
import io.skjaere.usenet.link.PairQuestion
import io.skjaere.usenet.link.PairedHello
import io.skjaere.usenet.link.PairingEvent
import io.skjaere.usenet.link.PairingHost
import io.skjaere.usenet.link.PeerLink
import io.skjaere.usenet.link.Refusal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Where the TV's listener is (L4): off, wanted with no home network to listen on, wanted with no link key to listen with,
 * or listening at [address]:[port].
 */
sealed interface TvListener {
    data object Off : TvListener

    data object NoNetwork : TvListener

    /** This TV's link key couldn't be loaded (its keystore unreadable now, L20): tried again as the policy changes. */
    data object NoKey : TvListener

    data class Listening(val address: Inet4Address, val port: Int) : TvListener
}

/** What the TV's listener runs on: the app's ([of]), or stand-ins, so it runs on the JVM over loopback. */
class TvLinkDeps(
    /** Whether this is a TV, by [AppContainer.television], never by layout (L36). */
    val television: Boolean,
    /** Whether one of the app's activities is in front (L31). */
    val inFront: StateFlow<Boolean>,
    val store: LinkStore,
    /** This install's link key (L20); keystore work, off the main thread. */
    val identity: suspend () -> LinkKey,
    /** The TV's private IPv4 on its home network as it changes, and its network; null while it has none. */
    val addresses: Flow<LanBinding?>,
    val advertiser: Advertiser,
    /** The TV's own name (L28), which every phone that finishes TLS is told. */
    val name: String,
    /** The app's version, for paired phones alone. */
    val app: String,
    /** Whether the TV can play, by Usenet or by its debrid service: a paired phone's hello says so (L25, L223). */
    val ready: Flow<Hello.Ready>,
    /** RFC 1918 IPv4 peers alone (L23); a test lets loopback in. */
    val allowPeer: (InetAddress) -> Boolean = SetupServer::isPrivateIpv4,
    val limits: () -> LinkLimits = { LinkLimits() },
    /** The port taken unless another is remembered (L12); tests take any free one (0). */
    val defaultPort: Int = PortChoice.DEFAULT,
    /** How long the listener waits for the system to tell of a network, as it starts, before it says there is none. */
    val noNetworkGraceMillis: Long = NO_NETWORK_GRACE_MS,
    /** Wall-clock milliseconds, the clock link.json's `removedAt` is written by (L4a). */
    val now: () -> Long = System::currentTimeMillis,
    /** What the TV does with a paired phone's commands (L8, L32): the app's plays; told it doesn't, without them. */
    val commands: TvCommands = TvCommands.Unsupported,
    /** What else hears of the paired phones' links: the TV's status and its Finals (L33, L34); nothing, without them. */
    val links: PhoneLinks = PhoneLinks.None,
) {
    companion object {
        fun of(c: AppContainer, context: Context): TvLinkDeps {
            val logged = AtomicBoolean()
            return TvLinkDeps(
                television = c.television,
                inFront = c.inFront.value,
                store = c.data.link,
                identity = {
                    val loaded = c.data.link.identity()
                    if (logged.compareAndSet(false, true)) {
                        log.info("Play on TV: the link identity is the {} one{}", loaded.identity.kind, if (loaded.renewed) "; a new one, so the pairings went with the old" else "")
                    }
                    loaded.identity.key
                },
                addresses = LanAddress.bindings(context.applicationContext),
                advertiser = LinkNsd(context),
                name = PeerNames.own(Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME), Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty()),
                app = BuildConfig.VERSION_NAME,
                // The rule each Play is checked by (L224), so a phone is told what a Play would find.
                ready = TvReadiness.hellos(c.settings),
                // The question's and pairing's times from the timings' seam (L46), as each listener binds.
                limits = ::pairingLimits,
                commands = c.tvPlays,
                links = c.tvPhoneLinks,
            )
        }
    }
}

/**
 * The TV's side of Play on TV (L4, L4a, L12, L27): while the policy says so ([TvLinkPolicy.listens]: a TV, in front, a
 * phone paired, Pair a phone taking pairings, or a Remove in the last 30 days) a [LinkServer] on the TV's home-network
 * address and its remembered port, and the TV's mDNS record. A new address binds it again there; leaving the front, or
 * the last phone going with no pairing under way and no recent Remove, closes it and takes the record down, as do a
 * Remove's 30 days running out while it listens. Each connection's key is looked up among the paired phones as the
 * file has them, and a paired phone is told whether the TV can play and the play now; its commands go to the TV's plays
 * ([TvLinkDeps.commands]), which wait while Pair a phone's question shows ([asking]), and its link to what the TV says of
 * its plays ([TvLinkDeps.links]). Pair a phone's pairings go through it ([openPairing]), and Remove and a phone's Unpair
 * close that phone's links at once ([remove]). Logged at INFO: how phones link (counts and the handshake's time),
 * refusals, pairing outcomes and the identity's kind; never a frame, a name or a number shown for pairing (L13).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TvLinkHost(private val scope: CoroutineScope, private val deps: TvLinkDeps) {
    private val _state = MutableStateFlow<TvListener>(TvListener.Off)
    val state: StateFlow<TvListener> = _state.asStateFlow()

    private val _connected = MutableStateFlow<Set<KeyId>>(emptySet())

    /** The phones with a link up now: "Seen now" on Settings › Phones. */
    val connected: StateFlow<Set<KeyId>> = _connected.asStateFlow()

    /** The paired phones' keys, as the listener looks the key TLS met up in (L23): the file's, read as it changes. */
    @Volatile private var paired: Set<KeyId> = emptySet()

    /** The names the paired phones are kept under, which a phone's play is said to be started from. */
    @Volatile private var names: Map<KeyId, String> = emptyMap()

    /** Pair a phone's question shows, as its page was last told; a page that goes takes it with it, unheard. */
    @Volatile private var questionShows = false

    /** Whether Pair a phone's question shows now: a phone's Play waits it out (L32). */
    val asking: Boolean get() = questionShows && page.value != null

    /** What a paired phone's hello says of the TV's readiness, as the settings have it now. */
    @Volatile private var ready = Hello.Ready(provider = false, indexer = false, tmdb = false)

    /** The links up, by phone: a phone linking again has its newer link up a moment before its older one is told it closed. */
    private val links = HashMap<KeyId, MutableSet<PeerLink>>()

    /** The Pair a phone page taking pairings now, if any (L22). */
    private val page = MutableStateFlow<PairPageListener?>(null)
    private val pairing = page.map { it != null }.distinctUntilChanged()
    private val started = AtomicBoolean()

    /** The listener now, for Remove and Unpair to close a phone's links on. */
    @Volatile private var server: LinkServer? = null

    /** The TV's own name (L28), as phones are told it. */
    val name: String get() = deps.name

    /**
     * Pair a phone is in front: the TV takes pairings for [listener] from now, listening for them if it wasn't, until
     * [closePairing] or until pairing closes by itself ([PairPageEvent.Closed]). Its record says `pair=1` meanwhile.
     */
    fun openPairing(listener: PairPageListener) {
        questionShows = false
        page.value = listener
    }

    /** [listener]'s page is gone: no more pairings for it, and a ceremony under way ends, nothing saved. */
    fun closePairing(listener: PairPageListener) {
        if (page.compareAndSet(listener, null)) questionShows = false
    }

    /**
     * Remove on the TV (L30, L4a): the phone goes, its outbox with it, and the time is noted in the same write, so the
     * TV goes on listening for a while with no phone paired. Each of its links is told `not-paired` and closed at once, so
     * a phone linked now reads "removed this phone" straight away; it is refused `not-paired` from then on. Once asked,
     * it goes through though the page that asked is gone first: a write that is in can't leave the phone's links up.
     */
    suspend fun remove(phone: KeyId) = withContext(NonCancellable) {
        deps.store.removePhone(phone, removedHere = true)
        paired = paired - phone
        server?.disconnect(phone, Refusal.NotPaired)
        log.info("Play on TV: a phone removed; {} paired", paired.size)
    }

    /**
     * The phone's own Unpair (L30): as [remove], but its links just close, and no Remove is noted (L4a): the phone knows
     * already, so the TV needn't listen on for it.
     */
    private suspend fun unpaired(phone: KeyId) = withContext(NonCancellable) {
        deps.store.removePhone(phone)
        paired = paired - phone
        server?.disconnect(phone)
        log.info("Play on TV: a phone unpaired itself; {} paired", paired.size)
    }

    /** Follows the policy from now, for the app's life. Nothing on a phone. */
    fun start() {
        if (!deps.television || !started.compareAndSet(false, true)) return
        scope.launch {
            deps.store.phones.collect { rows ->
                paired = rows.mapNotNullTo(HashSet()) { it.key }
                names = rows.mapNotNull { row -> row.key?.let { it to row.name } }.toMap()
            }
        }
        scope.launch { deps.ready.collect { ready = it } }
        scope.launch {
            // The phones and the last Remove from one read of the file: a Remove changes both in one write, and the
            // listener mustn't close in between for want of either.
            val file = deps.store.file.map { Kept(it.phones.isNotEmpty(), it.removedAt) }.distinctUntilChanged()
            combine(deps.inFront, file, pairing) { front, kept, pairs -> Triple(front, kept, pairs) }
                .transformLatest { (front, kept, pairs) ->
                    while (true) {
                        val now = deps.now()
                        emit(TvLinkPolicy.listens(deps.television, front, kept.phones, pairs, TvLinkPolicy.removedRecently(kept.removedAt, now)))
                        // A Remove keeping it listening: asked again as its time is up (L4a).
                        delay(TvLinkPolicy.removedListenLeft(kept.removedAt, now) ?: break)
                    }
                }
                .distinctUntilChanged().collectLatest { wanted -> if (wanted) listen() else _state.value = TvListener.Off }
        }
    }

    /** What the policy reads of the file: whether a phone is paired, and when Remove last took one off. */
    private data class Kept(val phones: Boolean, val removedAt: Long?)

    /** The listener on each address the TV has in turn, until it is no longer wanted. */
    private suspend fun listen() {
        val key = try {
            deps.identity()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Play on TV: no link key, so this TV doesn't listen for phones", e)
            _state.value = TvListener.NoKey
            return
        }
        deps.addresses.collectLatest { binding ->
            if (binding == null) {
                // The system tells of the networks a moment after it is asked: no "no network" for that moment.
                if (_state.value == TvListener.Off) delay(deps.noNetworkGraceMillis)
                _state.value = TvListener.NoNetwork
            } else {
                serve(binding, key)
            }
        }
    }

    /** The listener on [binding]'s address, and the record, until the address changes or the listener isn't wanted. */
    private suspend fun serve(binding: LanBinding, key: LinkKey) {
        val remembered = deps.store.file.first().port
        val bound = try {
            PortChoice.bind(remembered, deps.defaultPort) { port ->
                val server = server(binding.address, port, key)
                try {
                    server to server.start()
                } catch (e: IOException) {
                    server.close()
                    throw e
                }
            }
        } catch (e: IOException) {
            // The address went as it was bound: the next one the network gives is tried.
            log.warn("Play on TV: couldn't listen on the home network")
            _state.value = TvListener.NoNetwork
            return
        }
        val server = bound.value
        this.server = server
        // The page's pairings on this listener now, if it takes them.
        val taking = AtomicReference<PageHost?>(null)
        try {
            if (bound.save) saveOrLog("keep the link's port") { deps.store.setPort(bound.port) }
            log.info("Play on TV: listening on {}:{}", binding.address.hostAddress, bound.port)
            _state.value = TvListener.Listening(binding.address, bound.port)
            coroutineScope {
                // The page's pairings on this listener; a new listener (a new address) takes them up again.
                launch {
                    page.collect { listener ->
                        if (listener != null) {
                            server.openPairing(PageHost(listener).also(taking::set))
                        } else {
                            taking.set(null)
                            server.closePairing()
                        }
                    }
                }
                pairing.collectLatest { pairs ->
                    val advertised = deps.advertiser.advertise(ServiceRecord.of(deps.name, LinkVersions.V1.pv, key.id, pairs, binding.address), bound.port, binding.network)
                    try {
                        awaitCancellation()
                    } finally {
                        advertised.close()
                    }
                }
            }
        } finally {
            // Nothing is heard of its links once it is closed.
            this.server = null
            server.close()
            // Nor of its pairing: a question it asked is over, and the page is told so.
            taking.get()?.closed()
            val up = synchronized(links) {
                links.values.flatten().also {
                    links.clear()
                    _connected.value = emptySet()
                }
            }
            // The links up went with it, their ends unheard: each phone was last seen now, and nothing more goes out on it.
            up.forEach { link ->
                seen(link)
                deps.links.closed(Linked(link))
            }
        }
    }

    private fun server(address: Inet4Address, port: Int, key: LinkKey) = LinkServer(
        bind = address, port = port, key = key, name = deps.name, app = deps.app,
        paired = { paired }, pairedHello = { PairedHello(ready, deps.links.session(it)) }, allowPeer = deps.allowPeer, limits = deps.limits(),
        handler = handler, onEvent = ::heard,
    )

    private val handler = object : LinkServer.Handler {
        override fun linked(link: PeerLink, hello: Hello) {
            val phones = synchronized(links) {
                links.getOrPut(link.peer) { HashSet() } += link
                _connected.value = links.keys.toSet()
                links.size
            }
            log.info("Play on TV: a phone linked ({}, {}, TLS in {} ms); {} linked", link.protocol, link.cipherSuite, link.handshakeMillis, phones)
            seen(link)
            deps.links.linked(Linked(link))
        }

        override fun received(link: PeerLink, message: LinkMessage) {
            when (val answer = TvLinkPolicy.answer(message)) {
                TvAnswer.Unpaired -> scope.launch { saveOrLog("remove a phone that unpaired") { unpaired(link.peer) } }
                is TvAnswer.Command -> deps.commands.received(AskingPhone(link.peer, names[link.peer] ?: PHONE), answer.command) { link.send(it) }
                is TvAnswer.Acked -> deps.links.acked(link.peer, answer.ids)
                TvAnswer.Ignore -> Unit
            }
        }

        override fun closed(link: PeerLink, reason: LinkClose) {
            synchronized(links) {
                links[link.peer]?.let { open ->
                    open -= link
                    if (open.isEmpty()) links -= link.peer
                }
                _connected.value = links.keys.toSet()
            }
            log.debug("Play on TV: a phone's link closed ({})", reason)
            seen(link)
            deps.links.closed(Linked(link))
        }
    }

    /** A link as what the TV says of its plays goes out on it: equal for the same link. */
    private data class Linked(val link: PeerLink) : PhoneLink {
        override val phone: KeyId get() = link.peer

        override fun send(message: LinkMessage) {
            link.send(message)
        }
    }

    /**
     * Pair a phone's side of the listener's pairing (L22): a phone both said yes to is saved here, by the key TLS met,
     * under its own name cleaned (L28), and the listener knows it at once, so its first link right after pairing is
     * taken. The page hears the rest in the app's own words. Pairing closing by itself ends the page's opening, and
     * `pair=1` leaves the record; the listener closing ends the question it showed ([closed]).
     */
    private inner class PageHost(private val listener: PairPageListener) : PairingHost {
        /** The question showing, and the page's words for it: one ceremony at a time, its events in order. */
        @Volatile private var asked: Pair<PairQuestion, PhoneQuestion>? = null

        /** The listener closed, and nothing more comes of its pairing: the question showing ended with it. */
        fun closed() {
            val shown = asked?.second ?: return
            asked = null
            questionShows = false
            listener.heard(PairPageEvent.Ended(shown, PairOutcome.Closed))
        }

        override fun save(phone: NewPhone): Boolean = try {
            val saved = runBlocking { deps.store.addPhone(phone.key, PeerNames.clean(phone.name, "Phone"), phone.address.hostAddress.orEmpty()) }
            if (saved) paired = paired + phone.key else log.info("Play on TV: a phone not paired: {} are paired already", LinkStore.MAX_PEERS)
            saved
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Play on TV: couldn't save a phone that paired", e)
            false
        }

        override fun heard(event: PairingEvent) {
            when (event) {
                is PairingEvent.Asking -> {
                    val question = event.question
                    val shown = PhoneQuestion(PeerNames.clean(question.name, "Phone"), question.address.hostAddress.orEmpty(), question.number.digits) { ok ->
                        if (ok) question.ok() else question.cancel()
                    }
                    asked = question to shown
                    questionShows = true
                    listener.heard(PairPageEvent.Asking(shown))
                }
                is PairingEvent.Waiting -> listener.heard(PairPageEvent.Waiting(event.another))
                is PairingEvent.Ended -> {
                    log.info("Play on TV: a pairing ended ({})", event.outcome)
                    val shown = asked?.takeIf { it.first === event.question }?.second
                    if (event.question != null) {
                        asked = null
                        questionShows = false
                    }
                    listener.heard(PairPageEvent.Ended(shown, event.outcome))
                }
                PairingEvent.Closed -> {
                    log.info("Play on TV: pairing closed by itself")
                    listener.heard(PairPageEvent.Closed)
                    page.compareAndSet(listener, null)
                }
            }
        }
    }

    /** When and where a paired phone was last seen: as its link comes up and as it goes. */
    private fun seen(link: PeerLink) {
        scope.launch { saveOrLog("note when a phone was seen") { deps.store.phoneSeen(link.peer, link.address.hostAddress.orEmpty()) } }
    }

    /** What became of connections that never became links: none holds a frame or a name. */
    private fun heard(event: LinkEvent) {
        when (event) {
            is LinkEvent.Refused -> log.info("Play on TV: refused a phone ({})", event.reason)
            else -> log.debug("Play on TV: {}", event)
        }
    }
}

/** What a phone is called with no name kept for it (L28). */
private const val PHONE = "Phone"

/** As TV-8's page waits for its network (PhoneSetupViewModel.NO_NETWORK_GRACE_MS). */
internal const val NO_NETWORK_GRACE_MS = 1_500L

private val log = LoggerFactory.getLogger(TvLinkHost::class.java)
