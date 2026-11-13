package io.skjaere.usenet.app.debrid

import io.ktor.client.HttpClient
import io.skjaere.usenet.app.saveOrLog
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
import io.skjaere.usenet.debrid.ServiceRateLimit
import io.skjaere.usenet.debrid.premiumize.PremiumizeService
import io.skjaere.usenet.debrid.torbox.TorBoxService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The service accounts' clients (L196, L175). An account is on while it is switched on, its service is one this version
 * knows, its key can be read, and the service hasn't refused that key since it was saved; [on] gives those in the user's
 * order (their place in the settings, first tried first, L228), and nothing while debrid is hidden (L192). Each account has one client for the process ([make], at
 * [endpoints]' address), with its own gate and pace, kept while its key and address stay: a new key makes a new client,
 * with an open gate. A call any client finds the key refused on marks its account ([markRefused]), which then stays out
 * until a new key is saved or its page's Test passes. A key not saved yet is tried on a client of its own ([trial]).
 * The client of an account whose service adds to it (TorBox) is made with the app's watch for that account
 * ([addWatch], #318), which records the adds it proves this device's under the key it was made with; a trial's, none.
 * So a client is made again, with the watch for the account as it is then, whenever the time its key was saved
 * ([ServiceAccount.keySavedAt]) moves, though the key reads the same.
 */
class ServiceRegistry(
    private val accounts: suspend () -> List<ServiceWithKey>,
    private val markRefused: suspend (id: String) -> Unit,
    private val make: (kind: ServiceKind, key: String, baseUrl: String, gate: ServiceGate, watch: AddWatch?) -> DebridService,
    private val endpoints: (ServiceKind) -> String = ServiceEndpoints::of,
    private val newGate: () -> ServiceGate = { ServiceGate() },
    private val addWatch: (ServiceAccount) -> AddWatch? = { null },
) {
    /** A saved account's client: [service] marks the account when the service refuses its key. */
    class Client(val account: ServiceAccount, val kind: ServiceKind, val service: DebridService, internal val gate: ServiceGate) {
        override fun toString() = "Client($account)"
    }

    /** What an account's client was made with, so a new key, a new time it was saved or a new address makes another. */
    private class Made(val key: String, val keySavedAt: Long, val baseUrl: String, val service: DebridService, val gate: ServiceGate)

    private val made = HashMap<String, Made>()
    private val lock = Mutex()

    /** The accounts that are on, in the user's order; none while debrid is hidden ([shown] false). */
    suspend fun on(shown: Boolean = DebridGate.shown): List<ServiceAccount> =
        if (!shown) emptyList() else onWithKeys().map { it.record }

    /** The accounts that are on and whose gate is open (no limit or refused key holding them), with their clients, in the user's order. */
    suspend fun usable(shown: Boolean = DebridGate.shown): List<Client> =
        if (!shown) emptyList() else onWithKeys().map { client(it) }.filter { it.gate.closed() == null }

    /** Account [id]'s client, on or off, for its own page; null for none, or one whose key can't be read. */
    suspend fun service(id: String): Client? = accounts().firstOrNull { it.record.id == id }?.takeIf { kindOf(it.record) != null && it.apiKey != null }?.let { client(it) }

    /** A client for [key], not saved yet (the key form's Test and Save): its own gate and pace, and nothing marked. */
    fun trial(kind: ServiceKind, key: String): DebridService = make(kind, key, endpoints(kind), newGate(), null)

    private suspend fun onWithKeys(): List<ServiceWithKey> = accounts().filter(::isOn)

    private suspend fun client(saved: ServiceWithKey): Client {
        val kind = kindOf(saved.record)!!
        val key = saved.apiKey!!
        val baseUrl = endpoints(kind)
        val id = saved.record.id
        val keySavedAt = saved.record.keySavedAt
        val made = lock.withLock {
            made[id]?.takeIf { it.key == key && it.keySavedAt == keySavedAt && it.baseUrl == baseUrl }
                ?: newGate().let { gate ->
                    val watch = if (kind.addsToAccount) addWatch(saved.record) else null
                    Made(key, keySavedAt, baseUrl, KeyWatch(make(kind, key, baseUrl, gate, watch)) { markRefused(id) }, gate)
                }.also { made[id] = it }
        }
        // The same key taken again (Replace key with the key it had, or the page's Test): the refusal it closed the gate for is over.
        if (!saved.record.keyRefused && made.gate.closed() == ServiceError.KeyRefused) made.gate.reset()
        return Client(saved.record, kind, made.service, made.gate)
    }

    companion object {
        /** The service of [account], or null for one this version doesn't know (a newer version's). */
        fun kindOf(account: ServiceAccount): ServiceKind? = ServiceKind.entries.firstOrNull { it.name == account.kind }

        /** Whether [saved] is on: switched on, a service this version knows, its key readable and not refused since. */
        fun isOn(saved: ServiceWithKey): Boolean =
            saved.record.enabled && !saved.record.keyRefused && saved.apiKey != null && kindOf(saved.record) != null

        /**
         * The app's clients for each service, on one shared [http] client, each at its service's pace. TorBox's asks
         * [knownTorrent] for the torrent an earlier play added to the account (the listings kept, L181), so a play of it
         * again adds nothing while the account still has it (L184), and tells its account's watch, where it has one, of
         * each add it proves this device's (L232).
         */
        fun clients(http: () -> HttpClient, knownTorrent: suspend (InfoHash) -> String? = { null }): (ServiceKind, String, String, ServiceGate, AddWatch?) -> DebridService =
            { kind, key, baseUrl, gate, watch ->
                when (kind) {
                    ServiceKind.Premiumize -> PremiumizeService(key, http(), baseUrl, ServiceRateLimit(PremiumizeService.CALL_INTERVAL), gate)
                    ServiceKind.TorBox -> TorBoxService(key, http(), baseUrl, ServiceRateLimit(TorBoxService.CALL_INTERVAL), gate, knownTorrent, watch)
                }
            }
    }
}

/** [inner], marking its account ([refused]) when the service refuses its key; every call and answer otherwise as it is. */
private class KeyWatch(val inner: DebridService, private val refused: suspend () -> Unit) : DebridService {
    override val kind: ServiceKind get() = inner.kind

    override suspend fun test(): Account = watched { inner.test() }
    override suspend fun check(hashes: List<InfoHash>): Map<InfoHash, Boolean> = watched { inner.check(hashes) }
    override suspend fun prepare(hash: InfoHash, pick: suspend (ServiceListing) -> ServiceFile?): Prepared = watched { inner.prepare(hash, pick) }
    override suspend fun link(prepared: Prepared, file: ServiceFile): ServiceLink = watched { inner.link(prepared, file) }
    override suspend fun remove(handle: String) = watched { inner.remove(handle) }

    private suspend fun <T> watched(call: suspend () -> T): T = try {
        call()
    } catch (e: ServiceException) {
        if (e.error == ServiceError.KeyRefused) saveOrLog("mark a refused key") { refused() }
        throw e
    }
}

/** The client a registry's [ServiceRegistry.Client.service] wraps (tests). */
internal fun DebridService.unwrapped(): DebridService = (this as? KeyWatch)?.inner ?: this
