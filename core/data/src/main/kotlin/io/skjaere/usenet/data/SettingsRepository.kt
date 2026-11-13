package io.skjaere.usenet.data

import androidx.datastore.core.DataStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** A provider with its password: "" for a provider without login, null when the saved one can't be read. */
data class ProviderWithPassword(val record: ProviderRecord, val password: String?) {
    override fun toString() = "ProviderWithPassword(record=$record, password=${if (password.isNullOrEmpty()) "none" else "set"})"
}

/** An indexer with its API key, as the search code needs it. */
data class IndexerWithKey(val record: IndexerRecord, val apiKey: String?) {
    override fun toString() = "IndexerWithKey(record=$record, apiKey=${if (apiKey == null) "none" else "set"})"
}

/** A torrent indexer with its API key, as the search code needs it. */
data class TorrentIndexerWithKey(val record: TorrentIndexerRecord, val apiKey: String?) {
    override fun toString() = "TorrentIndexerWithKey(record=$record, apiKey=${if (apiKey == null) "none" else "set"})"
}

/** A service account with its API key: null when the saved one can't be read, or is missing after a device transfer. */
data class ServiceWithKey(val record: ServiceAccount, val apiKey: String?) {
    override fun toString() = "ServiceWithKey(record=$record, apiKey=${if (apiKey == null) "none" else "set"})"
}

/**
 * Whose account a key opens, as the service said when the key was tested: what a [ServiceAccount] keeps of the answer
 * ([planUntil] in milliseconds since the epoch).
 */
data class ServiceSignIn(val accountName: String, val plan: String? = null, val planUntil: Long? = null) {
    // The account's name is the user's own: never in a log line (L208).
    override fun toString() = "ServiceSignIn(plan=$plan, planUntil=$planUntil)"
}

class SettingsRepository(
    private val store: DataStore<AppSettings>,
    private val secrets: SecretStore,
    private val clock: Clock = Clock.System,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    val settings: Flow<AppSettings> = store.data
    val indexers: Flow<List<IndexerRecord>> = store.data.map { it.activeIndexers }
    val torrentIndexers: Flow<List<TorrentIndexerRecord>> = store.data.map { it.activeTorrentIndexers }
    val services: Flow<List<ServiceAccount>> = store.data.map { it.activeServices }
    val playback: Flow<PlaybackPrefs> = store.data.map { it.playback }
    val metadata: Flow<MetadataPrefs> = store.data.map { it.metadata }
    val cache: Flow<CachePrefs> = store.data.map { it.cache }
    val logs: Flow<LogPrefs> = store.data.map { it.logs }
    val hasTmdbKey: Flow<Boolean> = secrets.has(TMDB_KEY)
    val providers: Flow<List<ProviderRecord>> = store.data.map { it.activeProviders }
    val indexerSetupSkipped: Flow<Boolean> = store.data.map { it.indexerSetupSkipped }

    /**
     * The saved providers in priority order, each with its password, read again whenever they
     * change: what the providers screen shows (a password that can't be read is null).
     */
    val providersWithPasswords: Flow<List<ProviderWithPassword>> = providers.map { list -> list.map { withPassword(it) } }

    /**
     * Enabled providers in priority order, with passwords, as the engine needs them. Ones whose
     * password can't be read are left out rather than logged in to without one; the provider
     * screen asks for it again.
     */
    suspend fun usableProviders(): List<ProviderWithPassword> =
        store.data.first().activeProviders.filter { it.enabled }
            .map { withPassword(it) }
            .filter { it.password != null }

    suspend fun provider(id: String): ProviderWithPassword? =
        store.data.first().activeProviders.firstOrNull { it.id == id }?.let { withPassword(it) }

    /**
     * [p] with its password. One without a login is never asked for a password (the engine logs in
     * only with a username), so it stays usable when its saved "" is missing, as it is after a
     * device transfer leaves the secrets behind, or can't be read (#131).
     */
    private suspend fun withPassword(p: ProviderRecord) =
        ProviderWithPassword(p, secrets.get(p.passwordSecret) ?: "".takeIf { p.username.isBlank() })

    /**
     * Creates ([ProviderRecord.id] blank) or updates a provider; a null [password] keeps the stored
     * one. A new provider goes last, so it is tried last. A blank name stays blank: the host is
     * shown in its place, and follows the host when that changes.
     */
    suspend fun saveProvider(record: ProviderRecord, password: String?): String {
        val id = record.id.ifBlank { newId() }
        val saved = record.copy(id = id, name = record.name.trim(), host = record.host.trim(), username = record.username.trim(), updatedAt = clock.now(), deleted = false)
        password?.let { secrets.put(saved.passwordSecret, it) }
        store.updateData { current ->
            val existing = current.providers.indexOfFirst { it.id == id }
            current.copy(providers = if (existing >= 0) current.providers.toMutableList().also { it[existing] = saved } else current.providers + saved)
        }
        return id
    }

    /**
     * Moves provider [id] [by] places in the priority order (negative is up, towards first), as
     * far as the list goes. Deleted providers keep their place in the stored list but don't count
     * as places.
     */
    suspend fun moveProvider(id: String, by: Int) = reorderProvider(id) { from, last -> (from + by).coerceIn(0, last) }

    /** Moves provider [id] to [index] in the priority order (0 is first), as far as the list goes. */
    suspend fun moveProviderTo(id: String, index: Int) = reorderProvider(id) { _, last -> index.coerceIn(0, last) }

    /** Moves provider [id] from its place among the active providers to the one [to] gives, in one write; nothing when that is where it is. */
    private suspend fun reorderProvider(id: String, to: (from: Int, last: Int) -> Int) {
        store.updateData { current ->
            val moved = current.providers.reordered(id, ProviderRecord::id, ProviderRecord::deleted, { it.copy(updatedAt = clock.now()) }, to)
            moved?.let { current.copy(providers = it) } ?: current
        }
    }

    /**
     * [id]'s item of this list moved from its place among the active ones (not [deleted]) to the one [to] gives, [stamp]ed;
     * null when that is where it is, or no active item has [id]. The deleted ones stay where they are, and the others fill
     * the remaining places in their new order.
     */
    private fun <T> List<T>.reordered(id: String, idOf: (T) -> String, deleted: (T) -> Boolean, stamp: (T) -> T, to: (from: Int, last: Int) -> Int): List<T>? {
        val active = filterNot(deleted)
        val from = active.indexOfFirst { idOf(it) == id }
        val target = if (from < 0) from else to(from, active.lastIndex)
        if (target == from) return null
        val moved = active.toMutableList().apply { add(target, stamp(removeAt(from))) }.iterator()
        return map { if (deleted(it)) it else moved.next() }
    }

    /** Switches provider [id] on or off: one that is off keeps its settings and place but isn't used. */
    suspend fun setProviderEnabled(id: String, enabled: Boolean) {
        store.updateData { current ->
            current.copy(
                providers = current.providers.map {
                    if (it.id == id && !it.deleted && it.enabled != enabled) it.copy(enabled = enabled, updatedAt = clock.now()) else it
                },
            )
        }
    }

    suspend fun deleteProvider(id: String) {
        store.updateData { current ->
            current.copy(providers = current.providers.map { if (it.id == id) it.copy(deleted = true, enabled = false, updatedAt = clock.now()) else it })
        }
        secrets.put(ProviderRecord(id, "", "").passwordSecret, null)
    }

    /** Enabled indexers with their keys; ones whose key can't be read are skipped. */
    suspend fun searchableIndexers(): List<IndexerWithKey> =
        store.data.first().activeIndexers.filter { it.enabled }
            .map { IndexerWithKey(it, secrets.get(it.apiKeySecret)) }
            .filter { it.apiKey != null }

    suspend fun indexer(id: String): IndexerWithKey? =
        store.data.first().activeIndexers.firstOrNull { it.id == id }?.let { IndexerWithKey(it, secrets.get(it.apiKeySecret)) }

    /**
     * Whether an API key is stored for indexer [id], whether or not it can be read: with [indexer]'s key null, it tells
     * "none saved" from "saved, but this device can't read it".
     */
    suspend fun indexerKeyStored(id: String): Boolean = secrets.sealed(IndexerRecord(id, "", "").apiKeySecret) != null

    /**
     * Creates ([id] null) or updates an indexer. A null [apiKey] keeps the stored key. A new indexer goes last; one
     * saved again keeps its place, the order Search lists them in. Returns the id.
     */
    suspend fun saveIndexer(id: String?, name: String, baseUrl: String, apiKey: String?, enabled: Boolean = true): String {
        val indexerId = id ?: newId()
        val record = IndexerRecord(indexerId, name.trim(), baseUrl.trim(), enabled, updatedAt = clock.now())
        apiKey?.let { secrets.put(record.apiKeySecret, it.trim()) }
        store.updateData { current ->
            val existing = current.indexers.indexOfFirst { it.id == indexerId }
            current.copy(indexers = if (existing >= 0) current.indexers.toMutableList().also { it[existing] = record } else current.indexers + record)
        }
        return indexerId
    }

    suspend fun setIndexerEnabled(id: String, enabled: Boolean) = updateIndexer(id) { it.copy(enabled = enabled) }

    suspend fun deleteIndexer(id: String) {
        updateIndexer(id) { it.copy(deleted = true, enabled = false) }
        secrets.put(IndexerRecord(id, "", "").apiKeySecret, null)
    }

    private suspend fun updateIndexer(id: String, change: (IndexerRecord) -> IndexerRecord) {
        store.updateData { current ->
            current.copy(indexers = current.indexers.map { if (it.id == id) change(it).copy(updatedAt = clock.now()) else it })
        }
    }

    /** Enabled torrent indexers with their keys; ones whose key can't be read are skipped, as [searchableIndexers]. */
    suspend fun searchableTorrentIndexers(): List<TorrentIndexerWithKey> =
        store.data.first().activeTorrentIndexers.filter { it.enabled }
            .map { TorrentIndexerWithKey(it, secrets.get(it.apiKeySecret)) }
            .filter { it.apiKey != null }

    suspend fun torrentIndexer(id: String): TorrentIndexerWithKey? =
        store.data.first().activeTorrentIndexers.firstOrNull { it.id == id }?.let { TorrentIndexerWithKey(it, secrets.get(it.apiKeySecret)) }

    /** Whether an API key is stored for torrent indexer [id], readable or not, as [indexerKeyStored] tells for an indexer. */
    suspend fun torrentIndexerKeyStored(id: String): Boolean = secrets.sealed(TorrentIndexerRecord(id, "", "").apiKeySecret) != null

    /**
     * Creates ([id] null) or updates a torrent indexer, as [saveIndexer] does an indexer: a null [apiKey] keeps the
     * stored key, a new one goes last and one saved again keeps its place. Returns the id.
     */
    suspend fun saveTorrentIndexer(
        id: String?,
        name: String,
        baseUrl: String,
        apiKey: String?,
        enabled: Boolean = true,
        useWithService: Boolean = true,
    ): String {
        val indexerId = id ?: newId()
        val record = TorrentIndexerRecord(indexerId, name.trim(), baseUrl.trim(), enabled, useWithService, updatedAt = clock.now())
        apiKey?.let { secrets.put(record.apiKeySecret, it.trim()) }
        store.updateData { current ->
            val existing = current.torrentIndexers.indexOfFirst { it.id == indexerId }
            current.copy(
                torrentIndexers = if (existing >= 0) current.torrentIndexers.toMutableList().also { it[existing] = record } else current.torrentIndexers + record,
            )
        }
        return indexerId
    }

    suspend fun setTorrentIndexerEnabled(id: String, enabled: Boolean) = updateTorrentIndexer(id) { it.copy(enabled = enabled) }

    /** "Use with your debrid service": off, the indexer is still searched, and nothing of its results goes to a service. */
    suspend fun setUseWithService(id: String, use: Boolean) = updateTorrentIndexer(id) { it.copy(useWithService = use) }

    suspend fun deleteTorrentIndexer(id: String) {
        updateTorrentIndexer(id) { it.copy(deleted = true, enabled = false) }
        secrets.put(TorrentIndexerRecord(id, "", "").apiKeySecret, null)
    }

    private suspend fun updateTorrentIndexer(id: String, change: (TorrentIndexerRecord) -> TorrentIndexerRecord) {
        store.updateData { current ->
            current.copy(torrentIndexers = current.torrentIndexers.map { if (it.id == id) change(it).copy(updatedAt = clock.now()) else it })
        }
    }

    /**
     * The service accounts, each with its key (null where it can't be read), in the user's order: their places in the
     * list, a new one last, moved by [moveService] (L228). Each key is read with its account as one ([withKeys]).
     */
    suspend fun servicesWithKeys(): List<ServiceWithKey> = withKeys { it.activeServices }

    suspend fun service(id: String): ServiceWithKey? = withKeys { settings -> settings.activeServices.filter { it.id == id } }.firstOrNull()

    /**
     * The accounts [which] picks, each with its key, the record and the key as stored read together, between a service's
     * writes ([serviceWrites], EDL-2): a key being replaced is written before [ServiceAccount.keySavedAt] moves, and a read
     * between the two would pair the new key with the old key's time, through which the old key's adds would be removed.
     * The keys are opened (the keystore's work) after.
     */
    private suspend fun withKeys(which: (AppSettings) -> List<ServiceAccount>): List<ServiceWithKey> =
        serviceWrites.withLock { which(store.data.first()).map { it to secrets.sealed(it.apiKeySecret) } }
            .map { (record, sealed) -> ServiceWithKey(record, sealed?.let { secrets.open(it) }) }

    /** Whether an API key is stored for account [id], readable or not: with [service]'s key null, none from one this device can't read. */
    suspend fun serviceKeyStored(id: String): Boolean = secrets.sealed(ServiceAccount(id, "").apiKeySecret) != null

    /**
     * Signs in to the service [kind] with [key] (trimmed) and keeps the account (L196): [signIn] tests the key first,
     * throwing when the service doesn't take it, and nothing is saved unless it passes. One account per service: one of
     * [kind] already signed in takes the new key in place ([replaceKey]), its place and its switch kept. Returns the id.
     */
    suspend fun saveService(kind: String, key: String, signIn: suspend (String) -> ServiceSignIn): String {
        val trimmed = key.trim()
        require(trimmed.isNotEmpty()) { "No key to sign in with" }
        store.data.first().activeServices.firstOrNull { it.kind == kind }?.let { existing ->
            replaceKey(existing.id, trimmed, signIn)
            return existing.id
        }
        val account = signIn(trimmed)
        return serviceWrite {
            // Signed in to meanwhile (Save pressed again while this one ran): the key goes to that account, one per service.
            val now = store.data.first().activeServices.firstOrNull { it.kind == kind }
            if (now != null) {
                keepKey(now.id, trimmed, account)
                now.id
            } else {
                val now = clock.now()
                val record = ServiceAccount(newId(), kind).withSignIn(account).copy(updatedAt = now, keySavedAt = now)
                secrets.put(record.apiKeySecret, trimmed)
                store.updateData { it.copy(services = it.services + record) }
                record.id
            }
        }
    }

    /**
     * A new key for account [id], tested by [signIn] first: the old key stays until the service takes the new one, which
     * then clears a refused key's mark. An account signed out of meanwhile keeps nothing.
     */
    suspend fun replaceKey(id: String, key: String, signIn: suspend (String) -> ServiceSignIn) {
        val trimmed = key.trim()
        require(trimmed.isNotEmpty()) { "No key to sign in with" }
        val account = signIn(trimmed)
        serviceWrite {
            if (store.data.first().activeServices.any { it.id == id }) keepKey(id, trimmed, account)
        }
    }

    /**
     * [key] as account [id]'s, with what the service said of it: a key the service took is no longer refused. One other
     * than the key stored (or one that can't be read) moves [ServiceAccount.keySavedAt] on, as it may open another
     * account than the adds recorded under the old one (L232); the same key leaves it.
     */
    private suspend fun keepKey(id: String, key: String, account: ServiceSignIn) {
        val secret = ServiceAccount(id, "").apiKeySecret
        val changed = secrets.get(secret) != key
        secrets.put(secret, key)
        updateService(id) { saved ->
            saved.withSignIn(account).copy(keyRefused = false).let { if (changed) it.copy(keySavedAt = maxOf(clock.now(), saved.keySavedAt + 1)) else it }
        }
    }

    /**
     * What the service says now of account [id]'s saved key (its page's Test passed): the account's name and plan as it
     * gives them. The service took the key, so a refusal it was marked for is over (a proxy's 403, an outage's 401).
     */
    suspend fun updateSignIn(id: String, signIn: ServiceSignIn) = updateService(id) { it.withSignIn(signIn).copy(keyRefused = false) }

    /**
     * Moves service account [id] [by] places in the user's order (negative is up, towards first), as far as the list goes,
     * as [moveProvider] moves a provider: the accounts signed out of keep their places in the stored list, but don't count
     * as places. Plays try the accounts in this order (L228).
     */
    suspend fun moveService(id: String, by: Int) {
        store.updateData { current ->
            val moved = current.services.reordered(id, ServiceAccount::id, ServiceAccount::deleted, { it.copy(updatedAt = clock.now()) }) { from, last ->
                (from + by).coerceIn(0, last)
            }
            moved?.let { current.copy(services = it) } ?: current
        }
    }

    /** Switches account [id] on or off: one that is off keeps its key, and nothing is checked or played on it. */
    suspend fun setServiceEnabled(id: String, enabled: Boolean) = updateService(id) { it.copy(enabled = enabled) }

    /** "Remove after playing" on account [id]'s page (L246): off, what this device's plays add to the account stays there. */
    suspend fun setRemoveAfterPlay(id: String, on: Boolean) = updateService(id) { it.copy(removeAfterPlay = on) }

    /**
     * The service refused account [id]'s saved key: nothing is asked with it until a new key is saved ([replaceKey]) or the
     * service takes it on the account's page ([updateSignIn]).
     */
    suspend fun markKeyRefused(id: String) = updateService(id) { it.copy(keyRefused = true) }

    /**
     * Signs out of account [id] on this device: its key is removed, and its record left as a tombstone without the
     * account's name or plan. The account itself, at the service, stays as it is.
     */
    suspend fun signOut(id: String) = serviceWrite {
        // The key first: should the record's write fail, the account shows its key missing rather than keeping one unseen.
        secrets.put(ServiceAccount(id, "").apiKeySecret, null)
        updateService(id) { it.copy(deleted = true, enabled = false, accountName = "", plan = null, planUntil = null) }
    }

    /**
     * A service account's writes of its key and record, one at a time and each whole once begun: a screen left, or Save
     * pressed again, while one runs can't leave a key without its account (which nothing would ever remove) or an account
     * without its key.
     */
    private val serviceWrites = Mutex()

    private suspend fun <T> serviceWrite(write: suspend () -> T): T = withContext(NonCancellable) { serviceWrites.withLock { write() } }

    private fun ServiceAccount.withSignIn(signIn: ServiceSignIn) = copy(accountName = signIn.accountName, plan = signIn.plan, planUntil = signIn.planUntil)

    private suspend fun updateService(id: String, change: (ServiceAccount) -> ServiceAccount) {
        store.updateData { current ->
            current.copy(services = current.services.map { if (it.id == id && !it.deleted) change(it).copy(updatedAt = clock.now()) else it })
        }
    }

    // The TMDB key, decrypted once for each one stored: every TMDB request asks for it, and each
    // decryption is a keystore call. Kept with what it was decrypted from, so a new key is read as soon
    // as it is stored (hasTmdbKey tells of it then), not only once setTmdbKey has returned.
    @Volatile private var tmdbKeyMemo: Pair<Sealed, String>? = null

    /** The TMDB key; null when none is set or it can't be decrypted (which isn't kept: it is tried again). */
    suspend fun tmdbKey(): String? {
        val sealed = secrets.sealed(TMDB_KEY) ?: return null
        tmdbKeyMemo?.let { (from, key) -> if (from == sealed) return key }
        return secrets.open(sealed)?.also { tmdbKeyMemo = sealed to it }
    }

    /** Whether a TMDB key is stored, whether or not it can be read: [hasTmdbKey] counts only one that can. */
    suspend fun tmdbKeyStored(): Boolean = secrets.sealed(TMDB_KEY) != null

    suspend fun setTmdbKey(key: String?) {
        secrets.put(TMDB_KEY, key?.trim()?.takeIf { it.isNotEmpty() })
    }

    suspend fun updatePlayback(change: (PlaybackPrefs) -> PlaybackPrefs) {
        store.updateData { it.copy(playback = change(it.playback).copy(updatedAt = clock.now())) }
    }

    suspend fun updateMetadata(change: (MetadataPrefs) -> MetadataPrefs) {
        store.updateData { it.copy(metadata = change(it.metadata).copy(updatedAt = clock.now())) }
    }

    suspend fun updateCache(change: (CachePrefs) -> CachePrefs) {
        store.updateData { it.copy(cache = change(it.cache).copy(updatedAt = clock.now())) }
    }

    suspend fun updateLogs(change: (LogPrefs) -> LogPrefs) {
        store.updateData { it.copy(logs = change(it.logs).copy(updatedAt = clock.now())) }
    }

    /** "Not now" on the first-run screen (true), or setup asked for again (false). */
    suspend fun setIndexerSetupSkipped(skipped: Boolean) {
        store.updateData { it.copy(indexerSetupSkipped = skipped) }
    }

    /** Collection › Shows or Movies, and each side's filter and sort. */
    val collectionView: Flow<CollectionViewPrefs> = store.data.map { it.collectionView }.distinctUntilChanged()

    suspend fun updateCollectionView(change: (CollectionViewPrefs) -> CollectionViewPrefs) {
        store.updateData { it.copy(collectionView = change(it.collectionView).copy(updatedAt = clock.now())) }
    }

    /**
     * Google Cast on this phone (L112): "Look for Cast devices", whether its introduction was shown, and what each Cast
     * device has shown it plays (L128).
     */
    val cast: Flow<CastPrefs> = store.data.map { it.cast }

    suspend fun updateCast(change: (CastPrefs) -> CastPrefs) {
        store.updateData { it.copy(cast = change(it.cast).copy(updatedAt = clock.now())) }
    }

    /**
     * Puts back [saved], read from [settings] earlier, as it was: for device tests, which change the
     * app's real settings and must leave them as they found them. Secrets are not part of it; they
     * stay in [SecretStore].
     */
    suspend fun restore(saved: AppSettings) {
        store.updateData { saved }
    }

    private companion object {
        const val TMDB_KEY = "tmdb:key"
    }
}
