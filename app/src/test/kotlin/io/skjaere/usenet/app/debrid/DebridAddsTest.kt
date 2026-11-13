package io.skjaere.usenet.app.debrid

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.skjaere.usenet.data.ServiceAccount
import io.skjaere.usenet.debrid.Account
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
import io.skjaere.usenet.debrid.torbox.TorBoxService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What plays added to a TorBox account and the sweep that removes it (#318, L232-L234, L245, W82): an add is removed a
 * grace after its last lease goes, never while a lease holds it; leases are by owner, each holding once however often it
 * holds; a play's add is born held by its play; a sweep runs a grace after the process starts for what an earlier one
 * left; a delete the service doesn't confirm keeps the record; and the records of an account signed out of, switched to
 * keep its adds or given a new key, and any past a day after its grace, are dropped with no call, as nothing is asked
 * while debrid is hidden or the account's gate is closed. On the test's clock, over fake clients; invented accounts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebridAddsTest {
    @TempDir
    lateinit var dir: File

    /**
     * An account's client that counts the torrents it is asked to remove; [failure] fails each delete, and [held] holds
     * the next one until it is completed, as a delete that takes its time does.
     */
    private class FakeService : DebridService {
        override val kind = ServiceKind.TorBox
        var failure: ServiceError? = null
        var held: CompletableDeferred<Unit>? = null
        val removed = CopyOnWriteArrayList<String>()
        val asked = CopyOnWriteArrayList<String>()

        override suspend fun test(): Account = error("not asked")
        override suspend fun check(hashes: List<InfoHash>): Map<InfoHash, Boolean> = error("not asked")
        override suspend fun prepare(hash: InfoHash, pick: suspend (ServiceListing) -> ServiceFile?): Prepared = error("not asked")
        override suspend fun link(prepared: Prepared, file: ServiceFile): ServiceLink = error("not asked")

        override suspend fun remove(handle: String) {
            asked += handle
            held?.let {
                held = null
                it.await()
            }
            failure?.let { throw ServiceException(it) }
            removed += handle
        }
    }

    private val service = FakeService()
    private val accounts = MutableStateFlow(listOf(ServiceAccount(TB, "TorBox", accountName = "quay.viewer@example.org", keySavedAt = KEY_AT)))

    /** Whether the account is on with its gate open, as the registry's usable clients say. */
    private var open = true
    private var shown = true
    private var client: DebridService = service

    /** When the key of the client the registry gives now was saved, where it isn't the account's as [accounts] last said. */
    private var clientKeySavedAt: Long? = null
    private val lines = CopyOnWriteArrayList<String>()
    private val file by lazy { File(dir, "debrid/added.json") }

    /** The app's records over [file], as a process makes them, on the test's clock. */
    private fun TestScope.adds() = DebridAdds(
        file, accounts = { accounts.value }, usable = ::usable, scope = backgroundScope,
        clock = { testScheduler.currentTime }, shown = { shown }, io = Dispatchers.Unconfined, log = lines::add,
    )

    /** The account's client as the registry's usable clients give it: with the account as read with the client's key. */
    private fun usable(id: String): ServiceRegistry.Client? {
        val account = accounts.value.firstOrNull { it.id == id }?.takeIf { open && id == TB } ?: return null
        return ServiceRegistry.Client(clientKeySavedAt?.let { account.copy(keySavedAt = it) } ?: account, ServiceKind.TorBox, client, ServiceGate())
    }

    /** The watch for the account as it is saved now. */
    private fun DebridAdds.torBox() = watch(accounts.value.single { it.id == TB })

    private fun listing(hash: InfoHash = PACK) = DebridListingStore.idFor(TB, hash)

    private fun TestScope.wait(duration: Duration) {
        advanceTimeBy(duration.toMillis())
        runCurrent()
    }

    @Test
    fun `an add no lease holds is removed once its grace is over, with one call for its torrent alone`() = runTest {
        val adds = adds()
        adds.torBox().added(PACK, "42")
        assertEquals(listOf("42"), adds.records().map { it.handle })
        assertEquals(listing(), adds.records().single().listingId, "the store's id for the account and the torrent")
        wait(GRACE.minusSeconds(1))
        assertEquals(emptyList(), service.asked.toList(), "within its grace")
        wait(Duration.ofSeconds(2))
        assertEquals(listOf("42"), service.removed.toList())
        assertEquals(emptyList(), adds.records())
        assertEquals("[]", file.readText(), "gone from the file too")
    }

    @Test
    fun `an add is kept while a lease holds it and for a grace after it goes`() = runTest {
        val adds = adds()
        adds.torBox().added(PACK, "42")
        adds.hold(PLAYER, listing())
        wait(Duration.ofHours(3))
        assertEquals(emptyList(), service.asked.toList(), "held: a long play")
        adds.release(PLAYER)
        wait(GRACE.minusSeconds(1))
        assertEquals(emptyList(), service.asked.toList(), "within the grace after it went")
        wait(Duration.ofSeconds(2))
        assertEquals(listOf("42"), service.removed.toList())
    }

    @Test
    fun `a lease taken within the grace keeps it, and its grace begins again as that lease goes`() = runTest {
        val adds = adds()
        adds.torBox().added(PACK, "42")
        wait(Duration.ofSeconds(90))
        // A Resume, or the next episode of the pack, played within the grace.
        adds.hold(PLAYER, listing())
        wait(Duration.ofMinutes(30))
        assertEquals(emptyList(), service.asked.toList())
        adds.release(PLAYER)
        wait(GRACE.minusSeconds(1))
        assertEquals(emptyList(), service.asked.toList())
        wait(Duration.ofSeconds(2))
        assertEquals(listOf("42"), service.removed.toList())
    }

    @Test
    fun `two owners on one listing, one letting go, leave it held`() = runTest {
        val adds = adds()
        adds.torBox().added(PACK, "42")
        adds.hold(PLAYER, listing())
        adds.hold(CAST, listing())
        adds.release(PLAYER)
        wait(GRACE.multipliedBy(5))
        assertEquals(emptyList(), service.asked.toList(), "the cast holds it")
        adds.release(CAST)
        wait(GRACE.plusSeconds(1))
        assertEquals(listOf("42"), service.removed.toList())
    }

    @Test
    fun `one owner holding twice lets go once, and letting go again changes nothing`() = runTest {
        val adds = adds()
        adds.torBox().added(PACK, "42")
        // The player's setup runs again after each onStart: holding again is the same lease.
        adds.hold(PLAYER, listing())
        adds.hold(PLAYER, listing())
        adds.release(PLAYER)
        adds.release(PLAYER)
        wait(GRACE.plusSeconds(1))
        assertEquals(listOf("42"), service.removed.toList())
    }

    /**
     * L234: a play's add is born held by its play ([DebridAdds.Play] in the add's coroutine), so a probe that runs long, or
     * a cast's pre-check question left open, never starts the grace; the player's lease, taken as the play ends, falls
     * within the grace that then begins. A play adding none holds nothing.
     */
    @Test
    fun `an add held by its play across a long probe or pre-check, then by the player within the grace, is kept`() = runTest {
        val adds = adds()
        val play = DebridAdds.Play()
        withContext(play) { adds.torBox().added(PACK, "42") }
        wait(Duration.ofMinutes(45))
        // Another add's grace ending meanwhile sweeps too.
        adds.sweep()
        assertEquals(emptyList(), service.asked.toList(), "held by its play while the question waits")
        adds.release(play)
        wait(Duration.ofSeconds(3))
        adds.hold(PLAYER, listing())
        wait(Duration.ofMinutes(10))
        assertEquals(emptyList(), service.asked.toList())
        adds.release(PLAYER)
        wait(GRACE.plusSeconds(1))
        assertEquals(listOf("42"), service.removed.toList())
    }

    /** L234, DIP-1: the add of a release that failed its probe goes a grace after its play ends, while the one that played is the player's. */
    @Test
    fun `a failed attempt's add goes a grace after its play ends, the one that plays kept by its player`() = runTest {
        val adds = adds()
        val play = DebridAdds.Play()
        withContext(play) {
            adds.torBox().added(PACK, "41")
            // The next release, which plays.
            adds.torBox().added(GOOD, "42")
        }
        adds.hold(PLAYER, listing(GOOD))
        adds.release(play)
        wait(GRACE.minusSeconds(1))
        assertEquals(emptyList(), service.asked.toList())
        wait(Duration.ofSeconds(2))
        assertEquals(listOf("41"), service.removed.toList())
        assertEquals(listOf("42"), adds.records().map { it.handle })
    }

    /**
     * L234: a play holds the listing it prepares until it ends, though it added nothing (the next episode of a pack, its
     * torrent added by the play before): a slow probe or a file question never lets the grace of the earlier add end.
     * Outside a play nothing is held.
     */
    @Test
    fun `a listing a play prepares is held by it until it ends, and outside a play by nobody`() = runTest {
        val adds = adds()
        adds.torBox().added(PACK, "42")
        val play = DebridAdds.Play()
        withContext(play) { adds.holdForPlay(listing()) }
        adds.holdForPlay(listing())
        wait(GRACE.multipliedBy(3))
        adds.sweep()
        assertEquals(emptyList(), service.asked.toList(), "the next episode's play holds what the one before added")
        adds.release(play)
        wait(GRACE.plusSeconds(1))
        assertEquals(listOf("42"), service.removed.toList())
    }

    /**
     * L234: a torrent added again for a link (the player's, or a cast's, as the account no longer had it) is recorded with
     * no play to hold it; the lease on its listing does, and its grace begins as that lease goes.
     */
    @Test
    fun `an add made again for a link is held by the lease on its listing`() = runTest {
        val adds = adds()
        adds.hold(PLAYER, listing())
        adds.torBox().added(PACK, "77")
        wait(GRACE.multipliedBy(3))
        adds.sweep()
        assertEquals(emptyList(), service.asked.toList())
        adds.release(PLAYER)
        wait(GRACE.plusSeconds(1))
        assertEquals(listOf("77"), service.removed.toList())
    }

    /**
     * The device tests' seam for a process that died: its leases go, the file is read again, and the start sweep runs a
     * grace on; a sweep the gone process had waiting goes with it.
     */
    @Test
    fun `as after the process went, a leftover is removed a grace on though a player held it`() = runTest {
        val adds = adds()
        adds.start(accounts)
        adds.torBox().added(PACK, "42")
        adds.hold(PLAYER, listing())
        // The add's own sweep still waiting as the process goes.
        wait(GRACE.dividedBy(2))
        adds.restart()
        wait(GRACE.minusSeconds(1))
        assertEquals(emptyList(), service.asked.toList())
        wait(Duration.ofSeconds(2))
        assertEquals(listOf("42"), service.removed.toList())
    }

    /** L245: the start sweep runs a grace after the process starts, so a player made again first takes its lease; the file is what carries them. */
    @Test
    fun `what an earlier process left is read back, and removed a grace after the next start unless a player took it`() = runTest {
        val earlier = adds()
        val play = DebridAdds.Play()
        withContext(play) {
            earlier.torBox().added(PACK, "41")
            earlier.torBox().added(GOOD, "42")
        }
        val kept = earlier.records()
        // The process went while the play held them.
        val next = adds()
        assertEquals(kept, next.records(), "read back as they were")
        next.start(accounts)
        runCurrent()
        // A player made again after the process went takes its lease first.
        next.hold(PLAYER, listing(GOOD))
        wait(GRACE.minusSeconds(1))
        assertEquals(emptyList(), service.asked.toList())
        wait(Duration.ofSeconds(2))
        assertEquals(listOf("41"), service.removed.toList())
        assertEquals(listOf("42"), next.records().map { it.handle })
    }

    @Test
    fun `the same add said twice, two plays of one torrent at once, is one record`() = runTest {
        val adds = adds()
        adds.torBox().added(PACK, "42")
        adds.torBox().added(PACK, "42")
        assertEquals(1, adds.records().size)
        wait(GRACE.plusSeconds(1))
        assertEquals(listOf("42"), service.asked.toList(), "one delete")
    }

    /** L232: only what the service confirms is done; a delete that errs keeps its record for a later sweep. */
    @Test
    fun `a delete that errs keeps the record for the next sweep`() = runTest {
        val adds = adds()
        adds.torBox().added(PACK, "42")
        service.failure = ServiceError.Down
        wait(GRACE.plusSeconds(1))
        assertEquals(listOf("42"), service.asked.toList())
        assertEquals(listOf("42"), adds.records().map { it.handle }, "kept")
        service.failure = null
        adds.sweep()
        assertEquals(listOf("42"), service.removed.toList())
        assertEquals(emptyList(), adds.records())
    }

    /**
     * ELC-2: a record a sweep couldn't remove (a delete that erred, a gate closed by a limit) is tried again on its own, a
     * grace on and then less often, though nothing else sweeps (no play ends, no new start), until it is removed.
     */
    @Test
    fun `a record a sweep couldn't remove is tried again on its own, less often each time, until it goes`() = runTest {
        val adds = adds()
        adds.torBox().added(PACK, "42")
        service.failure = ServiceError.Down
        wait(GRACE.plusSeconds(1))
        assertEquals(listOf("42"), service.asked.toList())
        wait(GRACE.plusSeconds(1))
        assertEquals(listOf("42", "42"), service.asked.toList(), "tried again a grace on")
        wait(GRACE.multipliedBy(2).plusSeconds(1))
        assertEquals(3, service.asked.size, "then after two")
        service.failure = null
        wait(GRACE.multipliedBy(4).plusSeconds(1))
        assertEquals(listOf("42"), service.removed.toList(), "removed once the service answers")
        assertEquals(emptyList(), adds.records())
        wait(Duration.ofDays(2))
        assertEquals(4, service.asked.size, "nothing more once it went")

        // An account behind a closed gate (a limit) waits the same way, with no call, and is removed once it opens.
        adds.torBox().added(GOOD, "43")
        open = false
        wait(GRACE.plusSeconds(1))
        open = true
        wait(GRACE.plusSeconds(1))
        assertEquals(listOf("42", "43"), service.removed.toList())
    }

    /**
     * ECS-2: a delete the service doesn't confirm costs a whole list (L232), so a sweep leaves the account's other records
     * for its next try, which begins with those tried longest ago: while the service errs a try costs one delete and one
     * list, not one of each per record, and a record that always errs never keeps the others from being tried.
     */
    @Test
    fun `a sweep whose delete isn't confirmed leaves the account's other records for the next try, which begins with them`() = runTest {
        val adds = adds()
        listOf(PACK to "41", GOOD to "42", ORBIT to "43").forEach { (hash, handle) -> adds.torBox().added(hash, handle) }
        service.failure = ServiceError.Down
        wait(GRACE.plusSeconds(1))
        assertEquals(listOf("41"), service.asked.toList(), "one delete a try while the service errs")
        wait(GRACE.plusSeconds(1))
        assertEquals(listOf("41", "42"), service.asked.toList(), "the next try begins with one not tried yet")
        wait(GRACE.multipliedBy(2).plusSeconds(1))
        assertEquals(listOf("41", "42", "43"), service.asked.toList())
        service.failure = null
        wait(GRACE.multipliedBy(4).plusSeconds(1))
        assertEquals(listOf("41", "42", "43"), service.removed.toList(), "all, once the service answers")
        assertEquals(emptyList(), adds.records())
    }

    /**
     * L232, through TorBox's own client: a delete answered HTTP 500 DATABASE_ERROR is done only when the whole list, read
     * then, no longer holds the id; still listed, the record is kept.
     */
    @Test
    fun `a delete that errs is done only when the whole list shows the torrent gone`() = runTest {
        listOf(listOf(7L, 43L) to true, listOf(7L, 42L) to false).forEach { (listed, gone) ->
            val requests = CopyOnWriteArrayList<String>()
            client = TorBoxService(
                "TB-KEY", torbox(this, requests, listed), baseUrl = "https://api.torbox.test/v1/api",
                limit = ServiceRateLimit(Duration.ofMillis(250)) { testScheduler.currentTime }, log = lines::add,
            )
            File(dir, "debrid").deleteRecursively()
            val adds = adds()
            adds.torBox().added(PACK, "42")
            adds.sweep()
            assertTrue(requests.isEmpty(), "within its grace: nothing asked")
            wait(GRACE.plusSeconds(1))
            assertEquals(listOf("torrents/controltorrent", "torrents/mylist"), requests.toList(), "$listed")
            assertEquals(if (gone) emptyList() else listOf("42"), adds.records().map { it.handle }, "$listed")
        }
    }

    /** L232: a record not removed within a day of its grace's end goes with no call, whatever kept it, deletes that erred among it. */
    @Test
    fun `past its day a record is dropped with no call, after deletes that erred or a gate that stayed closed`() = runTest {
        val adds = adds()
        adds.torBox().added(PACK, "41")
        service.failure = ServiceError.Down
        wait(GRACE.plusSeconds(1))
        assertEquals(listOf("41"), service.asked.toList())
        // Tried again on its own now and then over its day (ELC-2), a dozen times or so; its day's last second, still asked.
        wait(DebridAdds.STALE.minus(GRACE).minusSeconds(2))
        val tries = service.asked.size
        assertTrue(tries in 8..16, "tries over its day: $tries")
        adds.sweep()
        assertEquals(tries + 1, service.asked.size)
        assertEquals(listOf("41"), adds.records().map { it.handle })
        wait(Duration.ofSeconds(2))
        adds.sweep()
        assertEquals(tries + 1, service.asked.size, "past its day: no call")
        assertEquals(emptyList(), adds.records())
        assertTrue(lines.any { it == "debrid: the record of 1 add not removed within a day, dropped with no call" }, "$lines")
        wait(Duration.ofDays(1))
        assertEquals(tries + 1, service.asked.size, "nothing tried once it went")

        // An account whose gate stays closed (a limit, a refused key) all that time.
        service.failure = null
        adds.torBox().added(GOOD, "42")
        open = false
        wait(DebridAdds.STALE.minusSeconds(1))
        adds.sweep()
        assertEquals(listOf("42"), adds.records().map { it.handle })
        open = true
        wait(Duration.ofSeconds(2))
        adds.sweep()
        assertEquals(tries + 1, service.asked.size, "42 past its day: no call")
        assertEquals(emptyList(), adds.records())
    }

    /**
     * L232, L246: Sign out (or the account removed on the setup page), Remove after playing switched off, and a new key
     * (Replace key, a second sign-in, the TV's setup form) drop the account's records with no call, held or not, as the
     * accounts change.
     */
    @Test
    fun `sign out, the switch off and a new key drop the account's records with no call`() = runTest {
        val before = accounts.value
        listOf<(ServiceAccount) -> ServiceAccount?>(
            { null },
            { it.copy(removeAfterPlay = false) },
            { it.copy(keySavedAt = KEY_AT + 60_000) },
        ).forEachIndexed { case, change ->
            accounts.value = before
            File(dir, "debrid").deleteRecursively()
            val adds = adds()
            adds.start(accounts)
            adds.torBox().added(PACK, "41")
            adds.torBox().added(GOOD, "42")
            adds.hold(PLAYER, listing(GOOD))
            accounts.value = before.mapNotNull(change)
            runCurrent()
            assertEquals(emptyList(), adds.records(), "case $case")
            wait(GRACE.multipliedBy(2))
            adds.release(PLAYER)
            wait(GRACE.multipliedBy(2))
            assertEquals(emptyList(), service.asked.toList(), "case $case: no call")
        }
        assertEquals(
            listOf("signed out", "Remove after playing off", "a new key").map { "debrid: the records of 2 adds dropped with no call: $it" },
            lines.filter { "dropped with no call:" in it },
        )
    }

    /**
     * EDL-2, ELC-1, ESW-1 (L232, L246): a sweep's deletes go one after another, each taking its time (a delete that errs
     * reads the whole list). Remove after playing switched off, a new key saved or the account signed out meanwhile, the
     * records after it are dropped with no call, as they would have been before the sweep began.
     */
    @Test
    fun `a change to the account while a sweep's delete is under way sends nothing for the records after it`() = runTest {
        val before = accounts.value
        listOf<Pair<String, (ServiceAccount) -> ServiceAccount?>>(
            "Remove after playing off" to { it.copy(removeAfterPlay = false) },
            "a new key" to { it.copy(keySavedAt = KEY_AT + 60_000) },
            "signed out" to { null },
        ).forEach { (case, change) ->
            accounts.value = before
            File(dir, "debrid").deleteRecursively()
            service.asked.clear()
            service.removed.clear()
            val adds = adds()
            adds.start(accounts)
            adds.torBox().added(PACK, "41")
            adds.torBox().added(GOOD, "42")
            val first = CompletableDeferred<Unit>()
            service.held = first
            wait(GRACE.plusSeconds(1))
            assertEquals(listOf("41"), service.asked.toList(), "$case: the first delete under way")
            accounts.value = before.mapNotNull(change)
            runCurrent()
            first.complete(Unit)
            runCurrent()
            wait(GRACE.multipliedBy(2))
            assertEquals(listOf("41"), service.asked.toList(), "$case: nothing sent for the record after it")
            assertEquals(emptyList(), adds.records(), case)
        }
    }

    /**
     * EDL-2, ELC-1: the client asked is the one of the key the add was made under: the registry's client for a key saved
     * after the accounts were last read (Replace key landing between the two) is never asked, and its record waits for the
     * drop that the new key brings.
     */
    @Test
    fun `a client made for another key than the add's is never asked`() = runTest {
        val adds = adds()
        adds.torBox().added(PACK, "42")
        clientKeySavedAt = KEY_AT + 1
        wait(GRACE.plusSeconds(1))
        adds.sweep()
        assertEquals(emptyList(), service.asked.toList(), "the new key's client")
        assertEquals(listOf("42"), adds.records().map { it.handle }, "kept until the accounts say the key changed")
        accounts.value = listOf(accounts.value.single().copy(keySavedAt = KEY_AT + 1))
        adds.sweep()
        assertEquals(emptyList(), service.asked.toList())
        assertEquals(emptyList(), adds.records(), "dropped with no call")
    }

    /** The sweep reads the accounts as they are when it runs, though nothing followed them. */
    @Test
    fun `a sweep drops the records of an account signed out of meanwhile, with no call`() = runTest {
        val adds = adds()
        adds.torBox().added(PACK, "42")
        accounts.value = emptyList()
        wait(GRACE.plusSeconds(1))
        assertEquals(emptyList(), service.asked.toList())
        assertEquals(emptyList(), adds.records())
    }

    /** L192, L254: hidden, nothing is asked; the record waits, and is removed once debrid shows again. */
    @Test
    fun `hidden, nothing is removed, and the record waits`() = runTest {
        val adds = adds()
        adds.torBox().added(PACK, "42")
        shown = false
        wait(GRACE.plusSeconds(1))
        assertEquals(emptyList(), service.asked.toList())
        assertEquals(listOf("42"), adds.records().map { it.handle })
        shown = true
        adds.sweep()
        assertEquals(listOf("42"), service.removed.toList())
    }

    /** A limit or a refused key closes the account's gate, and switching it off takes it out: the record waits, with no call. */
    @Test
    fun `an account off or behind a closed gate waits`() = runTest {
        val adds = adds()
        adds.torBox().added(PACK, "42")
        open = false
        wait(GRACE.plusSeconds(1))
        adds.sweep()
        assertEquals(emptyList(), service.asked.toList())
        assertEquals(listOf("42"), adds.records().map { it.handle })
        open = true
        adds.sweep()
        assertEquals(listOf("42"), service.removed.toList())
    }

    /** L232, L246: the watch wants records only while debrid shows, and the account is saved, its switch on, under the key it was made for. */
    @Test
    fun `the watch wants records while debrid shows and the account keeps them, under the key it was made for`() = runTest {
        val adds = adds()
        val watch = adds.torBox()
        assertTrue(watch.wanted())
        shown = false
        assertFalse(watch.wanted(), "hidden")
        shown = true
        val saved = accounts.value.single()
        accounts.value = listOf(saved.copy(removeAfterPlay = false))
        assertFalse(watch.wanted(), "Remove after playing off")
        accounts.value = listOf(saved.copy(keySavedAt = KEY_AT + 1))
        assertFalse(watch.wanted(), "a new key since the client was made")
        assertTrue(adds.torBox().wanted(), "the new key's client")
        accounts.value = emptyList()
        assertFalse(watch.wanted(), "signed out")
    }

    @Test
    fun `an add said under an old key is dropped with no call`() = runTest {
        val adds = adds()
        val old = adds.torBox()
        accounts.value = listOf(accounts.value.single().copy(keySavedAt = KEY_AT + 1))
        // The old key's client, still preparing as the new key was saved.
        old.added(PACK, "42")
        wait(GRACE.plusSeconds(1))
        assertEquals(emptyList(), service.asked.toList())
        assertEquals(emptyList(), adds.records())
    }

    @Test
    fun `a record names no account, handle or listing in what a log line would print`() = runTest {
        val adds = adds()
        adds.torBox().added(PACK, "42")
        val printed = adds.records().toString()
        listOf(TB, "42", listing(), PACK.hex).forEach { assertFalse(it in printed, printed) }
    }

    /**
     * A TorBox API on the test's dispatcher: a delete answered HTTP 500 DATABASE_ERROR, as a second delete of the same id
     * got in the live check, and the whole list holding the entries [listed]; each call's path into [requests].
     */
    private fun torbox(scope: TestScope, requests: MutableList<String>, listed: List<Long>) = HttpClient(
        MockEngine(
            MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(scope.testScheduler)
                addHandler { request ->
                    val call = request.url.encodedPath.removePrefix("/v1/api/")
                    requests += call
                    when (call) {
                        "torrents/controltorrent" -> json("""{"success":false,"error":"DATABASE_ERROR","detail":"Database error.","data":null}""", HttpStatusCode.InternalServerError)
                        "torrents/mylist" -> json(
                            """{"success":true,"error":null,"detail":"Torrent list retrieved.","data":[${listed.joinToString(",") { """{"id":$it,"hash":"${"%040d".format(it)}","files":[]}""" }}]}""",
                        )
                        else -> json("""{"success":false,"error":"ENDPOINT_NOT_FOUND","data":null}""", HttpStatusCode.NotFound)
                    }
                }
            },
        ),
    ) {
        install(HttpTimeout)
        expectSuccess = false
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
        respond(body, status, headersOf(HttpHeaders.ContentType to listOf("application/json")))

    private companion object {
        const val TB = "tb-7f3c"
        const val KEY_AT = 1_762_000_000_000L
        val GRACE: Duration = DebridAdds.GRACE
        val PACK: InfoHash = InfoHash.of("d3adb33fd3adb33fd3adb33fd3adb33fd3adb33f")
        val GOOD: InfoHash = InfoHash.of("1bb5795cb9f72aa684193ddf405249b071b2ba5f")
        val ORBIT: InfoHash = InfoHash.of("0a1b2c3d4e5f60718293a4b5c6d7e8f901234567")

        /** Owners as the player and a cast hold their leases. */
        val PLAYER = Any()
        val CAST = Any()
    }
}
