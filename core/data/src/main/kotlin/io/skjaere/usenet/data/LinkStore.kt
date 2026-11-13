package io.skjaere.usenet.data

import androidx.datastore.core.DataStore
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.Outbox
import io.skjaere.usenet.link.OutboxFinal
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.security.GeneralSecurityException
import java.util.concurrent.CancellationException

/**
 * Play on TV's device state (L29), `datastore/link.json`: this install's identity, the listener's port, the TVs this
 * phone paired with, the phones this TV paired with, and when Remove last took one off (L4a). Rows are kept by the peer's key id, which is what a pairing
 * pins (L2). It is device state, not sync-shaped (`Sync.kt`): the pins mean nothing without this device's key, so the
 * app's backup and transfer rules leave the file out, as they do the secrets. The TV chosen to play on is never here:
 * the choice is held in memory alone (W18, L36).
 */
@Serializable
data class LinkFile(
    val identity: IdentityRecord? = null,
    /** The port the TV's listener took (L12); null until it took one. */
    val port: Int? = null,
    val tvs: List<PairedTv> = emptyList(),
    val phones: List<PairedPhone> = emptyList(),
    /**
     * When Remove on this TV's Settings › Phones last removed a phone (this TV's clock, L4a): the TV listens for a while
     * after, so that phone hears it was removed. Null when none was; a phone's own Unpair never sets it, and a new
     * identity clears it with the phones.
     */
    val removedAt: Long? = null,
)

/**
 * Which identity this install made (L20): its [kind], its key's [id] (64 hex digits), and for the fallback its sealed
 * key. A keystore key stays in the keystore: nothing of it is here but its id.
 */
@Serializable
data class IdentityRecord(val kind: LinkIdentity.Kind, val id: String, val sealed: SealedLinkKey? = null)

/**
 * A TV this phone paired with: its key's [id] (64 hex digits, the pin), its [name] as it said it at pairing (untrusted,
 * L28), when it was paired and last seen (this phone's clock), and the address and port it was last reached at. The
 * [sessions] this phone started there go with it (L33).
 */
@Serializable
data class PairedTv(
    val id: String,
    val name: String,
    val pairedAt: Long,
    val lastSeen: Long? = null,
    val address: String? = null,
    val port: Int? = null,
    val sessions: List<TvSession> = emptyList(),
) {
    val key: KeyId? get() = KeyId.parse(id)
}

/** A play this phone started on a TV (L33): the TV's session id, and what it was of, when the phone sent that. */
@Serializable
data class TvSession(val id: String, val startedAt: Long, val showTmdbId: Int? = null, val movieTmdbId: Int? = null)

/**
 * A phone this TV paired with: its key's [id] (64 hex digits, the pin), its [name] as it said it at pairing (untrusted,
 * L28), when it was paired and last seen (this TV's clock), and the address it last came from. The [outbox] of Finals
 * owed to it goes with it (L33).
 */
@Serializable
data class PairedPhone(
    val id: String,
    val name: String,
    val pairedAt: Long,
    val lastSeen: Long? = null,
    val address: String? = null,
    val outbox: List<OutboxFinal> = emptyList(),
) {
    val key: KeyId? get() = KeyId.parse(id)
}

/**
 * Play on TV's rows, read and written as one file (L29, L30). A phone keeps the TVs it paired with, a TV the phones;
 * each side keeps at most [MAX_PEERS]. A peer paired again with the same key keeps its row and place, with what it is
 * owed; one forgotten or removed takes its sessions or its outbox with it.
 */
class LinkStore(
    private val store: DataStore<LinkFile>,
    private val clock: Clock = Clock.System,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val identityLock = Mutex()
    @Volatile private var identity: LoadedIdentity? = null

    val file: Flow<LinkFile> = store.data
    val tvs: Flow<List<PairedTv>> = store.data.map { it.tvs }.distinctUntilChanged()
    val phones: Flow<List<PairedPhone>> = store.data.map { it.phones }.distinctUntilChanged()

    /** The paired phones' keys, which the TV's listener looks a key TLS met up in (L23). */
    suspend fun phoneKeys(): Set<KeyId> = store.data.first().phones.mapNotNullTo(HashSet()) { it.key }

    /**
     * Keeps the TV a pairing on this phone met, by its [key]: a new row last, or, paired again, the same row in its place
     * with its new name, address and port. False, with nothing written, when [MAX_PEERS] other TVs are kept.
     */
    suspend fun addTv(key: KeyId, name: String, address: String, port: Int): Boolean {
        var kept = false
        store.updateData { current ->
            val now = clock.now()
            val (rows, added) = upsert(current.tvs, key, { it.id }) { old ->
                old?.copy(name = name, pairedAt = now, address = address, port = port) ?: PairedTv(key.hex, name, now, null, address, port)
            }
            kept = added
            current.copy(tvs = rows)
        }
        return kept
    }

    /** Forget on the phone (L30): the TV goes, and the sessions this phone started there with it. */
    suspend fun forgetTv(key: KeyId) {
        store.updateData { current -> current.copy(tvs = current.tvs.filterNot { it.id == key.hex }) }
    }

    /** The TV [key] was reached at [address] and [port] now; nothing for a TV not kept. */
    suspend fun tvSeen(key: KeyId, address: String, port: Int) {
        store.updateData { current ->
            val now = clock.now()
            current.copy(tvs = current.tvs.map { if (it.id == key.hex) it.copy(lastSeen = now, address = address, port = port) else it })
        }
    }

    /**
     * A play this phone started on the TV [tv] (L33): kept [SESSION_DAYS] days by this phone's clock, and the newest
     * [MAX_SESSIONS] alone, the oldest going first; one told again is kept once, as the newest. Nothing for a TV not kept.
     */
    suspend fun addSession(tv: KeyId, session: TvSession) {
        store.updateData { current ->
            val since = clock.now() - SESSION_DAYS * DAY_MS
            current.copy(
                tvs = current.tvs.map { row ->
                    if (row.id != tv.hex) return@map row
                    row.copy(sessions = (row.sessions.filterNot { it.id == session.id } + session).filter { it.startedAt >= since }.takeLast(MAX_SESSIONS))
                },
            )
        }
    }

    /**
     * Keeps the phone a pairing on this TV met, by its [key]: a new row last, or, paired again, the same row in its place
     * with its new name and address (L22). False, with nothing written, when [MAX_PEERS] other phones are kept.
     */
    suspend fun addPhone(key: KeyId, name: String, address: String): Boolean {
        var kept = false
        store.updateData { current ->
            val now = clock.now()
            val (rows, added) = upsert(current.phones, key, { it.id }) { old ->
                old?.copy(name = name, pairedAt = now, address = address) ?: PairedPhone(key.hex, name, now, null, address)
            }
            kept = added
            current.copy(phones = rows)
        }
        return kept
    }

    /**
     * Remove on the TV, or the phone's Unpair (L30): the phone goes, and the Finals owed to it with it. Remove on this
     * TV's Settings › Phones ([removedHere]) notes its time as [LinkFile.removedAt] in the same write (L4a), so the TV
     * never reads the phone gone with no Remove noted; an Unpair leaves it as it was.
     */
    suspend fun removePhone(key: KeyId, removedHere: Boolean = false) {
        store.updateData { current ->
            val phones = current.phones.filterNot { it.id == key.hex }
            if (removedHere) current.copy(phones = phones, removedAt = clock.now()) else current.copy(phones = phones)
        }
    }

    /** The time of the last Remove (L4a) set to [at]: for a test that puts this device's file back as it found it. */
    suspend fun setRemovedAt(at: Long?) {
        store.updateData { it.copy(removedAt = at) }
    }

    /** The phone [key] came from [address] now; nothing for a phone not kept. */
    suspend fun phoneSeen(key: KeyId, address: String) {
        store.updateData { current ->
            val now = clock.now()
            current.copy(phones = current.phones.map { if (it.id == key.hex) it.copy(lastSeen = now, address = address) else it })
        }
    }

    /** A Final owed to the phone [phone] (L33), made now, as [Outbox.add] keeps it; nothing for a phone not kept. */
    suspend fun queueFinal(phone: KeyId, final: LinkMessage.Final) {
        store.updateData { current ->
            val now = clock.now()
            current.copy(phones = current.phones.map { if (it.id == phone.hex) it.copy(outbox = Outbox(it.outbox).add(final, now).entries) else it })
        }
    }

    /** The Finals owed to the phone [phone] now, oldest first, each with its age by this TV's clock ([Outbox.due]); none for a phone not kept. */
    suspend fun owed(phone: KeyId): List<LinkMessage.Final> =
        store.data.first().phones.firstOrNull { it.id == phone.hex }?.let { Outbox(it.outbox).due(clock.now()) }.orEmpty()

    /** The phone [phone] applied or dropped the Finals [ids] names (its FinalAck): they are owed no more. */
    suspend fun ackFinals(phone: KeyId, ids: List<String>) {
        store.updateData { current ->
            current.copy(phones = current.phones.map { if (it.id == phone.hex) it.copy(outbox = Outbox(it.outbox).ack(ids).entries) else it })
        }
    }

    /**
     * This install's identity (L20), for both roles: the recorded one while its key is still there, else a new one from
     * [LinkIdentity.create], recorded with its kind. Made once; a later process finds it again. When the recorded one is
     * lost (its keystore key gone or another in its place, as a restore or a reset leaves it, or its fallback's seal
     * gone), the new one is [LoadedIdentity.renewed], and the pairings go with the old key they pinned, the last Remove's
     * time with them. A keystore that
     * can't be read now throws, and nothing changes. The keystore work runs on [io].
     */
    suspend fun identity(keys: LinkKeys = LinkKeys()): LoadedIdentity = identityLock.withLock {
        identity ?: withContext(io) { loadOrMake(keys) }.also { identity = it }
    }

    private suspend fun loadOrMake(keys: LinkKeys): LoadedIdentity {
        val record = store.data.first().identity
        record?.let { kept(it, keys) }?.let { return LoadedIdentity(it, renewed = false) }
        val made = LinkIdentity.create(keys.alias, keys.seal, keys.make, keys.delete, keys.selfTest)
        // No phone paired with the new key could have been removed: the last Remove's time goes with the phones (L4a).
        store.updateData { it.copy(identity = IdentityRecord(made.kind, made.key.id.hex, made.sealed), tvs = emptyList(), phones = emptyList(), removedAt = null) }
        return LoadedIdentity(made, renewed = record != null)
    }

    /** The identity [record] names, while its key is still there; null when it is lost. */
    private fun kept(record: IdentityRecord, keys: LinkKeys): LinkIdentity? {
        val identity = when (record.kind) {
            LinkIdentity.Kind.Keystore -> keys.load(keys.alias)?.let(LinkIdentity::kept)
            LinkIdentity.Kind.Fallback -> record.sealed?.let { opened(it, keys.seal) }
        }
        return identity?.takeIf { it.key.id.hex == record.id }
    }

    /** The fallback [sealed] keeps; null when its seal can't open it, as when the keystore key that sealed it is gone. */
    private fun opened(sealed: SealedLinkKey, seal: SecretCipher): LinkIdentity? = try {
        LinkIdentity.open(sealed, seal)
    } catch (e: CancellationException) {
        throw e
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: RuntimeException) {
        // A sealed key the file no longer reads as one (bad Base64, say): lost as well.
        null
    }

    /** The port the TV's listener took (L12), to take again. */
    suspend fun setPort(port: Int) {
        store.updateData { it.copy(port = port) }
    }

    /**
     * [rows] with [key]'s row made by [row] from the old one, in its place, or a new one last while there is room: the
     * rows, and whether [key]'s is among them.
     */
    private fun <T> upsert(rows: List<T>, key: KeyId, id: (T) -> String, row: (T?) -> T): Pair<List<T>, Boolean> {
        val at = rows.indexOfFirst { id(it) == key.hex }
        return when {
            at >= 0 -> rows.toMutableList().also { it[at] = row(it[at]) } to true
            rows.size >= MAX_PEERS -> rows to false
            else -> rows + row(null) to true
        }
    }

    companion object {
        /** TVs a phone keeps, and phones a TV keeps (L29). */
        const val MAX_PEERS = 16

        /** The sessions a phone keeps per TV, and for how long (L33). */
        const val MAX_SESSIONS = 50
        const val SESSION_DAYS = 30L
        private const val DAY_MS = 86_400_000L
    }
}
