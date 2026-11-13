package io.skjaere.usenet.app.debrid

import androidx.annotation.VisibleForTesting
import io.skjaere.usenet.data.ServiceAccount
import io.skjaere.usenet.debrid.AddWatch
import io.skjaere.usenet.debrid.DebridService
import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.ServiceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.time.Duration
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * What this device's plays added to a service account, which it removes once nobody plays it (#318, L232-L234, L245, W82).
 * TorBox keeps the torrent a play adds in the account's list; its client says which adds were provably this device's own
 * ([watch], an [AddWatch]: the account's whole list, read just before, didn't hold the torrent), and only those are ever
 * removed, from the account they were added to. Each is kept as a [Record]: the account, the service's handle, the
 * listing it plays as, the account's [ServiceAccount.keySavedAt] at the add, when it was added and last used; in memory
 * and in [file] (filesDir's `debrid/added.json`, which no backup or device transfer carries, L233), written as the
 * listings are, so each device removes only what it added.
 *
 * Leases hold an add while it is used ([hold], [release]), each by its owner, idempotent per owner (L234): the play that
 * added it, from its birth ([Play], in the add's coroutine) until the play ends; the player from its launch until it
 * closes; a cast while it plays there. A lease names the listing, so whatever plays it holds every add made for it. Once
 * no lease holds a record and its last use is over [grace] old (W82: [GRACE], 2 minutes), [sweep] removes it: a sweep
 * runs a grace after a record's last lease goes, and a grace after the process starts ([start]), so a player made again
 * after the process went takes its lease first.
 *
 * A removal is asked only of an account that is on with its gate open ([usable]), and never while debrid is hidden
 * ([shown], L192): a record waits for those, and a delete the service doesn't confirm keeps its record; one left waiting
 * so is tried again on its own, a grace on and then less often, [RETRY_AT_MOST] apart at most. Dropped with no
 * call (unsure means not ours): the records of an account signed out of or removed, one switched to keep what its plays
 * add (Remove after playing off, L246), one given a new key since the add (L232: it may open another account), and any
 * record not removed within a day of its grace's end ([STALE]). The accounts are read again before each delete, as one
 * before it can take seconds, and only the client of the key the add was made under is asked (EDL-2).
 */
class DebridAdds(
    private val file: File,
    /** The service accounts saved now (not those signed out of), as the settings hold them. */
    private val accounts: suspend () -> List<ServiceAccount>,
    /**
     * Account [accountId]'s client while it is on and its gate is open, with the account as it was read with the client's
     * key; null otherwise, and while debrid is hidden.
     */
    private val usable: suspend (accountId: String) -> ServiceRegistry.Client?,
    /** Where sweeps and the file's writes run after their caller has gone: the app's. */
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val shown: () -> Boolean = { DebridGate.shown },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = { DEFAULT_LOG.info(it) },
) {
    /**
     * One add a play made to [account]'s list that this device can remove: the service's [handle] for it (TorBox's torrent
     * id), the [listingId] its listing is kept as (the store's id for the account and the torrent, which every lease
     * names), the account's [keySavedAt] at the add, and when it was added and last used (milliseconds).
     */
    @Serializable
    data class Record(val account: String, val handle: String, val listingId: String, val keySavedAt: Long, val addedAt: Long, val lastUsedAt: Long) {
        // No account, handle or listing in a log line (L208).
        override fun toString() = "Record(addedAt=$addedAt, lastUsedAt=$lastUsedAt)"
    }

    /**
     * The coordinator's play a coroutine runs for: in the context of the add's coroutine, the owner the add is born held by
     * (L234), until the play lets it go ([release]) as it ends.
     */
    class Play : AbstractCoroutineContextElement(Play) {
        companion object Key : CoroutineContext.Key<Play>

        override fun toString() = "DebridAdds.Play"
    }

    /** How long a record no lease holds is kept after its last use: [GRACE], or a device test's shorter one. */
    @Volatile
    @VisibleForTesting
    internal var grace: Long = GRACE.toMillis()

    // The leases, by owner, each the listings it holds; and when a lease last began or ended on each listing. Under [held].
    private val held = Any()
    private val leases = HashMap<Any, MutableSet<String>>()
    private val usedAt = HashMap<String, Long>()

    /** When the next sweep [later] began waiting for is due, while one is; under [held]. */
    private var due: Long? = null

    /** Sweeps in a row that left a record waiting (a delete not confirmed, a closed gate): the next try waits longer. */
    @Volatile
    private var waited = 0

    /** When each record's delete was last asked, by account and handle: a sweep begins with those asked longest ago. Under [held]. */
    private val askedAt = HashMap<Pair<String, String>, Long>()

    /** Which process the sweeps waiting are this one's: [restart] stands in a new one, whose waits alone count. */
    @Volatile
    private var era = 0

    // The records, read from [file] on first use; under [lock].
    private val lock = Mutex()
    private val records = ArrayList<Record>()
    private var read = false

    /** One sweep at a time: its calls run outside [lock], so recording a play's add never waits on a delete. */
    private val sweeping = Mutex()

    /**
     * The [AddWatch] for [account]'s client, made with the key [account] has now: told of the adds its client proves this
     * device's, it keeps each as a record under that key's [ServiceAccount.keySavedAt]. It wants records only while debrid
     * shows, the account is saved with Remove after playing on, and its key is still the one the client was made with.
     */
    fun watch(account: ServiceAccount): AddWatch = object : AddWatch {
        private val id = account.id
        private val keySavedAt = account.keySavedAt

        override suspend fun wanted(): Boolean =
            shown() && accounts().any { it.id == id && it.removeAfterPlay && it.keySavedAt == keySavedAt }

        override suspend fun added(hash: InfoHash, handle: String) = added(id, keySavedAt, DebridListingStore.idFor(id, hash), handle)

        override fun toString() = "AddWatch"
    }

    /**
     * [owner] uses the listing [listingId]: every record of it is held until [owner] lets go ([release]). Holding one again
     * changes nothing; an owner may hold several (a play whose attempts added more than one release).
     */
    fun hold(owner: Any, listingId: String) {
        synchronized(held) {
            leases.getOrPut(owner) { HashSet() } += listingId
            usedAt[listingId] = clock()
        }
    }

    /**
     * The listing [listingId] is held by the play this coroutine runs for ([Play]) until it ends, as a release it prepared:
     * one it added nothing for (the next episode of a pack, its torrent added by the play before) as much as one it added.
     * Nothing outside a play.
     */
    suspend fun holdForPlay(listingId: String) {
        currentCoroutineContext()[Play]?.let { hold(it, listingId) }
    }

    /**
     * Test seam: as a new process starts after this one went, its leases and the records in memory go, the file is read
     * again, and the start sweep runs a grace from now.
     */
    @VisibleForTesting
    internal suspend fun restart() {
        synchronized(held) {
            leases.clear()
            usedAt.clear()
            askedAt.clear()
            due = null
            era++
        }
        waited = 0
        lock.withLock {
            records.clear()
            read = false
        }
        later(grace)
    }

    /**
     * [owner] lets go of every listing it held, once whatever number of times it held them: a grace begins for each, after
     * which a sweep removes those no other lease holds. Nothing for an owner holding none.
     */
    fun release(owner: Any) {
        val listings = synchronized(held) {
            val listings = leases.remove(owner) ?: return
            val now = clock()
            listings.forEach { usedAt[it] = now }
            listings
        }
        // When it was let go, kept for a process that goes before the sweep.
        scope.launch {
            quietly("keep when an add was last used") {
                lock.withLock {
                    readEarlier()
                    if (records.any { it.listingId in listings }) write()
                }
            }
        }
        later(grace)
    }

    /**
     * Removes each record no lease holds whose last use is over [grace] old, from an account that is on with its gate
     * open, while debrid shows; drops with no call those [drop] names and those past [STALE]. A delete the service
     * doesn't confirm keeps its record for a later sweep, and the account's records after it wait for the next try (ECS-2:
     * each costs a whole list), which begins with those asked longest ago. One that is still within its grace is swept
     * again as it ends, and one left waiting is tried again ([retryAfter]).
     */
    suspend fun sweep(): Unit = sweeping.withLock {
        val saved = accounts()
        drop(saved)
        val now = clock()
        val kept = lock.withLock {
            readEarlier()
            records.toList()
        }.sortedBy { synchronized(held) { askedAt[it.account to it.handle] } ?: 0 }
        // The accounts whose delete this sweep wasn't confirmed.
        val erring = HashSet<String>()
        val removed = HashSet<Record>()
        var stale = 0
        var next: Long? = null
        var waiting = false
        for (record in kept) {
            if (heldNow(record.listingId)) continue
            val idle = now - lastUsed(record)
            when {
                idle < grace -> next = minOf(next ?: Long.MAX_VALUE, grace - idle)
                idle > grace + DAY -> {
                    removed += record
                    stale++
                }
                // Hidden: no call of any kind (L192, L254), the record kept until it is past its day.
                !shown() -> Unit
                else -> {
                    // Read again before each delete (EDL-2, ESW-1): one before it can take seconds (a delete that errs reads
                    // the whole list), and meanwhile the account may have been switched to keep its adds, given a new key or
                    // signed out of. Its records then go with no call, as they would have before the sweep began.
                    if (!stillKept(record)) continue
                    // Off, or held by its limit or a refused key: it waits. Only the client of the key the add was made under,
                    // Remove after playing on, as read with that key: one made for a key saved since is never asked, and the
                    // record goes at the next drop.
                    val client = usable(record.account)?.takeIf { it.account.keySavedAt == record.keySavedAt && it.account.removeAfterPlay }
                    if (client == null) {
                        waiting = true
                        continue
                    }
                    // Taken again while the account was asked for (a Resume as the grace ended): kept.
                    if (heldNow(record.listingId)) continue
                    // An account erring now likely errs for each: the next try begins with these.
                    if (record.account in erring) {
                        waiting = true
                        continue
                    }
                    synchronized(held) { askedAt[record.account to record.handle] = clock() }
                    if (remove(client.service, record)) {
                        removed += record
                    } else {
                        waiting = true
                        erring += record.account
                    }
                }
            }
        }
        if (removed.isNotEmpty()) {
            lock.withLock {
                // By account and handle: a write meanwhile keeps each with a later last use.
                records.removeAll { record -> removed.any { it.isOf(record) } }
                synchronized(held) { removed.forEach { askedAt.remove(it.account to it.handle) } }
                quietly("forget the adds removed") { write() }
            }
        }
        if (stale > 0) log("debrid: ${adds(stale)} not removed within a day, dropped with no call")
        // ELC-2: nothing else may sweep before a waiting record's day is over (no play ends, no new start), so it is tried
        // again on its own; each try in a row waits longer, as a delete that errs reads the whole list.
        if (waiting) next = minOf(next ?: Long.MAX_VALUE, retryAfter(waited++)) else waited = 0
        next?.let(::later)
    }

    /**
     * How long after the [times]th sweep in a row that left a record waiting it is tried again: a grace, then twice as long
     * each time, [RETRY_AT_MOST] at most.
     */
    private fun retryAfter(times: Int): Long = minOf(grace shl minOf(times, 20), RETRY_AT_MOST.toMillis())

    /**
     * Runs a sweep a grace after the process starts, for what an earlier one left (a player made again first takes its
     * lease), and drops with no call the records of each account as [services] change (signed out, Remove after playing
     * switched off, a new key).
     */
    fun start(services: Flow<List<ServiceAccount>>) {
        later(grace)
        scope.launch { quietly("follow the service accounts for what plays added") { services.distinctUntilChanged().collect { drop(it) } } }
    }

    /** The records kept now (tests). */
    @VisibleForTesting
    internal suspend fun records(): List<Record> = lock.withLock {
        readEarlier()
        records.toList()
    }

    /** The add of [handle] to [account]'s list, kept as this device's, held by the play adding it (L234). */
    private suspend fun added(account: String, keySavedAt: Long, listingId: String, handle: String) {
        val play = currentCoroutineContext()[Play]
        // Held before it is kept: a sweep never finds it free while its play goes on.
        play?.let { hold(it, listingId) }
        val now = clock()
        lock.withLock {
            readEarlier()
            // Two plays of one torrent at once can both find the list without it, and both be told of the one id.
            records.removeAll { it.account == account && it.handle == handle }
            records += Record(account, handle, listingId, keySavedAt, addedAt = now, lastUsedAt = now)
            // Kept in memory whatever the disk does: this process removes it all the same.
            quietly("keep a play's add") { write() }
        }
        // Added outside a play (a link's re-add), as a lease on its listing holds it: one none holds starts its grace now.
        if (play == null && !heldNow(listingId)) later(grace)
    }

    /**
     * Drops with no call the records the accounts [saved] now don't let this device remove: of an account signed out of or
     * removed, one with Remove after playing off, or one whose key has changed since the add. Held or not.
     */
    private suspend fun drop(saved: List<ServiceAccount>) {
        val why = lock.withLock {
            readEarlier()
            val dropped = records.mapNotNull { record ->
                val account = saved.firstOrNull { it.id == record.account }
                when {
                    account == null -> record to "signed out"
                    !account.removeAfterPlay -> record to "Remove after playing off"
                    account.keySavedAt != record.keySavedAt -> record to "a new key"
                    else -> null
                }
            }
            if (dropped.isNotEmpty()) {
                records.removeAll(dropped.map { it.first }.toSet())
                synchronized(held) { dropped.forEach { (record, _) -> askedAt.remove(record.account to record.handle) } }
                quietly("forget the adds of an account") { write() }
            }
            dropped.groupingBy { it.second }.eachCount()
        }
        why.forEach { (reason, count) -> log("debrid: ${adds(count)} dropped with no call: $reason") }
    }

    /** [record]'s torrent removed from the account by [service]: true once done, or never to be (a handle that names none). */
    private suspend fun remove(service: DebridService, record: Record): Boolean = try {
        service.remove(record.handle)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        // Not one torrent's id: nothing was sent, and nothing ever could be.
        true
    } catch (e: ServiceException) {
        // The service's own line said how it went; the record waits for the next sweep, or its day.
        false
    } catch (e: Exception) {
        log("debrid: a removal failed (${e.javaClass.simpleName}), the record kept")
        false
    }

    /** Whether [record] is still kept, once the accounts as they are now have dropped what they don't let this device remove. */
    private suspend fun stillKept(record: Record): Boolean {
        drop(accounts())
        return lock.withLock { records.any { it.isOf(record) } }
    }

    /** Whether this is the record of [other]'s add: the same torrent in the same account. */
    private fun Record.isOf(other: Record) = account == other.account && handle == other.handle

    /** Whether a lease holds [listingId] now. */
    private fun heldNow(listingId: String): Boolean = synchronized(held) { leases.values.any { listingId in it } }

    /** When [record] was last used: now while a lease holds it, else its own time or its listing's last lease, the later. */
    private fun lastUsed(record: Record): Long = synchronized(held) {
        if (leases.values.any { record.listingId in it }) clock() else maxOf(record.lastUsedAt, usedAt[record.listingId] ?: 0)
    }

    /** A sweep [afterMs] from now, in [scope]; none more where one is due by then already. */
    private fun later(afterMs: Long) {
        val wait = afterMs.coerceAtLeast(MIN_WAIT_MS)
        val at = clock() + wait
        val of = synchronized(held) {
            if (due?.let { it <= at } == true) return
            due = at
            era
        }
        scope.launch {
            delay(wait)
            synchronized(held) {
                if (era != of) return@launch
                if (due == at) due = null
            }
            quietly("sweep what plays added") { sweep() }
        }
    }

    /** [block], whose failure (a full disk) is logged by its kind alone, as its words may name the file. */
    private suspend fun quietly(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("debrid: couldn't $what (${e.javaClass.simpleName})")
        }
    }

    /** The records to [file], each with when it was last used, newest last. Under [lock]. */
    private suspend fun write() {
        val now = records.map { it.copy(lastUsedAt = lastUsed(it)) }
        records.clear()
        records += now
        withContext(io) {
            file.parentFile?.mkdirs()
            val written = File(file.parentFile, "${file.name}.new")
            written.writeText(json.encodeToString(ListSerializer(Record.serializer()), now))
            if (!written.renameTo(file)) throw IOException("Couldn't replace ${file.name}")
        }
    }

    /** On first use, what earlier processes wrote; a file that doesn't read is none. Under [lock]. */
    private suspend fun readEarlier() {
        if (read) return
        read = true
        val earlier = withContext(io) {
            try {
                file.takeIf { it.isFile }?.readText()?.let { json.decodeFromString(ListSerializer(Record.serializer()), it) }.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("debrid: the records of what plays added didn't read (${e.javaClass.simpleName})")
                emptyList()
            }
        }
        // Those this process kept before the file was read stand.
        val now = records.toList()
        records.clear()
        records += earlier.filter { old -> now.none { it.account == old.account && it.handle == old.handle } } + now
    }

    private fun adds(count: Int) = if (count == 1) "the record of 1 add" else "the records of $count adds"

    companion object {
        /** How long an add no lease holds is kept after its last use (W82, L245): Resume and Next within it add nothing again. */
        val GRACE: Duration = Duration.ofMinutes(2)

        /** A record not removed within a day of its grace's end is dropped with no call: by then the entry may be relied on. */
        val STALE: Duration = GRACE.plusDays(1)

        /** The longest wait before a record left waiting is tried again: a dozen tries or so in its day. */
        val RETRY_AT_MOST: Duration = Duration.ofHours(4)

        private val DAY = STALE.minus(GRACE).toMillis()

        /** A sweep's wait at the least: one asked for at once still lets its caller's writes go first. */
        private const val MIN_WAIT_MS = 50L

        private val DEFAULT_LOG = LoggerFactory.getLogger(DebridAdds::class.java)
        private val json = Json { ignoreUnknownKeys = true }
    }
}
