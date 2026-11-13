package io.skjaere.usenet.data

import androidx.datastore.core.DataStoreFactory
import io.skjaere.usenet.link.FinalKind
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.Outbox
import io.skjaere.usenet.link.OutboxFinal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Play on TV's rows in `link.json` (L29, L30), over a real DataStore in a temp directory: TVs and phones added,
 * replaced by key id, forgotten or removed with what goes with them, seen, and held to 16 a side. The names are
 * invented.
 */
class LinkStoreTest {
    @TempDir
    lateinit var dir: File

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var time = 1_000L
    private val clock = Clock { time }

    private val file get() = File(dir, "link.json")
    private val store by lazy { store() }

    /** A store over the file; a second one is for a second process, after the first's scope is gone. */
    private fun store(scope: CoroutineScope = this.scope) =
        LinkStore(DataStoreFactory.create(JsonFileSerializer(LinkFile.serializer(), LinkFile()), scope = scope) { file }, clock)

    @AfterEach
    fun stop() = scope.cancel()

    /**
     * [scope]'s store gone, as its process ends: waited for, since DataStore lets go of the file only as the scope's work
     * ends, and a store opened on it before that is refused ("multiple DataStores active for the same file").
     */
    private suspend fun gone(scope: CoroutineScope) = scope.coroutineContext.job.cancelAndJoin()

    private fun key(): KeyId = KeyId.of(Random.nextBytes(91))

    private fun final(id: String) = LinkMessage.Final(
        id = id, session = "s-81", watchKey = "episode:4101:2:4", kind = FinalKind.Episode, title = "The Glass Orchard",
        showTmdbId = 4_101, season = 2, episode = 4, positionMs = 1_930_000, durationMs = 3_120_000, watched = false, ageMs = 0,
    )

    @Test
    fun `a TV is added, replaced by its key id, and forgotten with its sessions`() = runBlocking<Unit> {
        val living = key()
        val bedroom = key()
        assertTrue(store.addTv(living, "Living room TV", "192.168.1.20", 28_462))
        time = 2_000
        assertTrue(store.addTv(bedroom, "Bedroom TV", "192.168.1.31", 28_462))
        assertEquals(
            listOf(PairedTv(living.hex, "Living room TV", 1_000, null, "192.168.1.20", 28_462), PairedTv(bedroom.hex, "Bedroom TV", 2_000, null, "192.168.1.31", 28_462)),
            store.tvs.first(),
        )
        assertEquals(living, store.tvs.first().first().key)
        store.addSession(living, TvSession("s-81", startedAt = 2_500, showTmdbId = 4_101))

        // Paired again with the same key: the same row, in its place, its sessions kept.
        time = 3_000
        assertTrue(store.addTv(living, "Lounge TV", "192.168.1.21", 28_463))
        val again = store.tvs.first()
        assertEquals(listOf(living.hex, bedroom.hex), again.map { it.id })
        assertEquals(PairedTv(living.hex, "Lounge TV", 3_000, null, "192.168.1.21", 28_463, listOf(TvSession("s-81", 2_500, showTmdbId = 4_101))), again.first())

        // Forget: the TV and its sessions go, from the file too.
        store.forgetTv(living)
        assertEquals(listOf(bedroom.hex), store.tvs.first().map { it.id })
        val text = file.readText()
        assertFalse(living.hex in text || "s-81" in text || "Lounge" in text, text)
    }

    /** L33: the sessions a phone started on a TV are kept 30 days and 50 at most, the oldest going first. */
    @Test
    fun `a TV's sessions are kept 30 days, and 50 at most, the oldest going first`() = runBlocking<Unit> {
        val living = key()
        assertTrue(store.addTv(living, "Living room TV", "192.168.1.20", 28_462))
        val day = 86_400_000L
        time = 40 * day
        // One started 31 days ago (by this phone's clock), one 29 days ago, then 50 more.
        store.addSession(living, TvSession("s-old", startedAt = time - 31 * day, showTmdbId = 4_101))
        store.addSession(living, TvSession("s-29", startedAt = time - 29 * day, movieTmdbId = 7_707))
        assertEquals(listOf("s-29"), store.tvs.first().single().sessions.map { it.id }, "past 30 days it goes")
        repeat(50) { store.addSession(living, TvSession("s-$it", startedAt = time - 1_000 + it, showTmdbId = 4_101)) }
        val kept = store.tvs.first().single().sessions.map { it.id }
        assertEquals((0 until 50).map { "s-$it" }, kept, "50 at most, the oldest gone")
        // Another TV's, or one not kept: nothing.
        store.addSession(key(), TvSession("s-x", startedAt = time))
        assertEquals(kept, store.tvs.first().single().sessions.map { it.id })
        // One told again is kept once, the newest.
        store.addSession(living, TvSession("s-3", startedAt = time, showTmdbId = 4_101))
        assertEquals(kept - "s-3" + "s-3", store.tvs.first().single().sessions.map { it.id })
    }

    @Test
    fun `a phone is added, replaced by its key id, and removed with its outbox, on Remove and on Unpair alike`() = runBlocking<Unit> {
        val galaxy = key()
        val pixel = key()
        assertTrue(store.addPhone(galaxy, "Galaxy phone", "192.168.1.57"))
        assertTrue(store.addPhone(pixel, "Pixel phone", "192.168.1.58"))
        store.queueFinal(galaxy, final("f-1"))
        store.queueFinal(pixel, final("f-2"))
        assertEquals(listOf(OutboxFinal(final("f-1"), 1_000)), store.phones.first().first().outbox)
        assertEquals(setOf(galaxy, pixel), store.phoneKeys())

        time = 2_000
        assertTrue(store.addPhone(galaxy, "Invented phone", "192.168.1.60"))
        val again = store.phones.first().first()
        assertEquals(PairedPhone(galaxy.hex, "Invented phone", 2_000, null, "192.168.1.60", listOf(OutboxFinal(final("f-1"), 1_000))), again)

        // Remove on the TV, or the phone's Unpair, which the TV handles the same way (L30).
        store.removePhone(galaxy)
        assertEquals(listOf(pixel.hex), store.phones.first().map { it.id })
        assertEquals(setOf(pixel), store.phoneKeys())
        val text = file.readText()
        assertFalse(galaxy.hex in text || "\"f-1\"" in text, text)
        assertTrue("\"f-2\"" in text, "another phone's outbox went too")
    }

    /**
     * L33: the Finals a TV owes a phone, as the TV sends them: each with its age by the TV's clock, until the phone's
     * FinalAck lets them go; 50 at most, the oldest going first; kept for a phone that is paired alone, and owed as before
     * after a restart. Remove and Unpair take them with the phone (above).
     */
    @Test
    fun `a phone's Finals are owed with their ages until it acks them, and outlive the store`() = runBlocking<Unit> {
        val galaxy = key()
        val pixel = key()
        store.addPhone(galaxy, "Galaxy phone", "192.168.1.57")
        store.addPhone(pixel, "Pixel phone", "192.168.1.58")
        store.queueFinal(galaxy, final("f-1"))
        time = 3_000
        store.queueFinal(galaxy, final("f-2"))
        store.queueFinal(pixel, final("f-3"))
        // A phone not kept is owed nothing.
        store.queueFinal(key(), final("f-4"))
        time = 8_000
        assertEquals(listOf(final("f-1").copy(ageMs = 7_000), final("f-2").copy(ageMs = 5_000)), store.owed(galaxy))
        store.ackFinals(galaxy, listOf("f-1", "f-3"))
        assertEquals(listOf("f-2"), store.owed(galaxy).map { it.id })
        assertEquals(listOf("f-3"), store.owed(pixel).map { it.id }, "another phone's ack lets none of this one's go")
        assertFalse("\"f-4\"" in file.readText())
        gone(scope)

        // Another process: owed as before, by the same clock.
        val next = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val again = store(next)
            assertEquals(listOf(final("f-2").copy(ageMs = 5_000)), again.owed(galaxy))
            repeat(Outbox.MAX + 2) { again.queueFinal(galaxy, final("g-$it")) }
            assertEquals(Outbox.MAX, again.owed(galaxy).size)
            assertEquals("g-2", again.owed(galaxy).first().id, "the oldest went first")
        } finally {
            next.cancel()
        }
    }

    /**
     * L4a: Remove on the TV notes its time with the removal, in the one write, so the TV never listens on a file that has
     * the phone gone and no Remove; the phone's own Unpair notes none and leaves an earlier one as it was.
     */
    @Test
    fun `Remove on the TV notes its time in the same write, and an Unpair notes none`() = runBlocking<Unit> {
        val writes = java.util.concurrent.atomic.AtomicInteger()
        val real = DataStoreFactory.create(JsonFileSerializer(LinkFile.serializer(), LinkFile()), scope = scope) { file }
        val counted = LinkStore(
            object : androidx.datastore.core.DataStore<LinkFile> {
                override val data = real.data
                override suspend fun updateData(transform: suspend (t: LinkFile) -> LinkFile): LinkFile {
                    writes.incrementAndGet()
                    return real.updateData(transform)
                }
            },
            clock,
        )
        val galaxy = key()
        val pixel = key()
        val tablet = key()
        counted.addPhone(galaxy, "Galaxy phone", "192.168.1.57")
        counted.addPhone(pixel, "Pixel phone", "192.168.1.58")
        counted.addPhone(tablet, "Pixel tablet", "192.168.1.59")
        assertEquals(null, counted.file.first().removedAt)

        // Unpair: the phone goes, and no Remove is noted.
        time = 4_000
        counted.removePhone(pixel)
        assertEquals(null, counted.file.first().removedAt)

        time = 5_000
        writes.set(0)
        counted.removePhone(galaxy, removedHere = true)
        assertEquals(1, writes.get(), "one write")
        val after = counted.file.first()
        assertEquals(5_000L, after.removedAt)
        assertEquals(listOf(tablet.hex), after.phones.map { it.id })

        // An Unpair later leaves the Remove's time as it was.
        time = 6_000
        counted.removePhone(tablet)
        assertEquals(5_000L, counted.file.first().removedAt)
        assertTrue("\"removedAt\":5000" in file.readText().replace(" ", ""), file.readText())
    }

    /** L4a: the Remove's time outlives the store, and a link.json written before there was one reads as no Remove. */
    @Test
    fun `the Remove's time outlives the store, and a file from before it reads with none`() = runBlocking<Unit> {
        val galaxy = key()
        store.addPhone(galaxy, "Galaxy phone", "192.168.1.57")
        time = 7_000
        store.removePhone(galaxy, removedHere = true)
        gone(scope)
        val next = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            assertEquals(7_000L, store(next).file.first().removedAt)
        } finally {
            next.cancel()
        }

        // As an earlier version wrote it: no removedAt at all.
        val pixel = key()
        File(dir, "old").mkdirs()
        val old = File(dir, "old/link.json")
        old.writeText(
            """{"identity":null,"port":28462,"tvs":[],"phones":[{"id":"${pixel.hex}","name":"Pixel phone","pairedAt":1000,"lastSeen":null,"address":"192.168.1.58","outbox":[]}]}""",
        )
        val oldScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val read = LinkStore(DataStoreFactory.create(JsonFileSerializer(LinkFile.serializer(), LinkFile()), scope = oldScope) { old }, clock).file.first()
            assertEquals(null, read.removedAt)
            assertEquals(28_462, read.port)
            assertEquals(listOf(pixel.hex), read.phones.map { it.id }, "the rest read as it was, not replaced as corrupt")
        } finally {
            oldScope.cancel()
        }
    }

    @Test
    fun `last seen is written with where the peer was seen, and only for a peer kept`() = runBlocking<Unit> {
        val tv = key()
        val phone = key()
        store.addTv(tv, "Living room TV", "192.168.1.20", 28_462)
        store.addPhone(phone, "Galaxy phone", "192.168.1.57")
        time = 5_000
        store.tvSeen(tv, "192.168.1.22", 28_470)
        store.phoneSeen(phone, "192.168.1.59")
        val seenTv = store.tvs.first().single()
        assertEquals(Triple(5_000L, "192.168.1.22", 28_470), Triple(seenTv.lastSeen, seenTv.address, seenTv.port))
        assertEquals(1_000, seenTv.pairedAt)
        val seenPhone = store.phones.first().single()
        assertEquals(5_000L to "192.168.1.59", seenPhone.lastSeen to seenPhone.address)
        // A peer not kept isn't written.
        store.tvSeen(key(), "192.168.1.40", 28_462)
        store.phoneSeen(key(), "192.168.1.41")
        assertEquals(1, store.tvs.first().size)
        assertEquals(1, store.phones.first().size)
    }

    @Test
    fun `at most 16 TVs and 16 phones`() = runBlocking<Unit> {
        val tvs = List(LinkStore.MAX_PEERS) { key() }
        val phones = List(LinkStore.MAX_PEERS) { key() }
        tvs.forEachIndexed { i, k -> assertTrue(store.addTv(k, "TV $i", "192.168.1.${i + 1}", 28_462)) }
        phones.forEachIndexed { i, k -> assertTrue(store.addPhone(k, "Phone $i", "192.168.2.${i + 1}")) }
        assertFalse(store.addTv(key(), "TV 17", "192.168.1.99", 28_462))
        assertFalse(store.addPhone(key(), "Phone 17", "192.168.2.99"))
        assertEquals(16, store.tvs.first().size)
        assertEquals(16, store.phones.first().size)
        // One kept may still be paired again, and one gone makes room.
        assertTrue(store.addTv(tvs[3], "TV 3 again", "192.168.1.4", 28_462))
        assertTrue(store.addPhone(phones[3], "Phone 3 again", "192.168.2.4"))
        store.forgetTv(tvs[0])
        store.removePhone(phones[0])
        assertTrue(store.addTv(key(), "TV 17", "192.168.1.99", 28_462))
        assertTrue(store.addPhone(key(), "Phone 17", "192.168.2.99"))
    }

    @Test
    fun `the rows and the port outlive the store, and no TV is ever kept as chosen`() = runBlocking<Unit> {
        val tv = key()
        val phone = key()
        store.addTv(tv, "Living room TV", "192.168.1.20", 28_462)
        store.addPhone(phone, "Galaxy phone", "192.168.1.57")
        store.setPort(28_463)
        val written = store.file.first()
        assertEquals(28_463, written.port)
        gone(scope)

        // Another process: the same rows.
        val next = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            assertEquals(written, store(next).file.first())
        } finally {
            next.cancel()
        }
        // The choice of TV is held in memory alone (W18, L36): the file has no place for it.
        assertEquals(setOf("identity", "port", "tvs", "phones", "removedAt"), Json.parseToJsonElement(file.readText()).jsonObject.keys)
    }
}
