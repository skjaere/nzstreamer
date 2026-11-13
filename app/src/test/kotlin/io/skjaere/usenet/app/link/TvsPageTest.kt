package io.skjaere.usenet.app.link

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.skjaere.usenet.data.LinkFile
import io.skjaere.usenet.data.LinkStore
import io.skjaere.usenet.data.PairedTv
import io.skjaere.usenet.data.TvSession
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Settings › TVs on a phone (item 15, PtPhoneSettings, L30, L41): the hub's line, each TV's lines (paired and seen,
 * its address, its state in words), and Forget, which tells a TV it reaches to remove this phone (Unpair) before the
 * TV goes here with its sessions; over a real link on loopback for that. The names are invented.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TvsPageTest {
    private val zone = ZoneOffset.UTC
    private val now = at(2026, 11, 1, 20, 0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun stop() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) = LocalDateTime.of(y, m, d, h, min).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun tv(name: String, pairedAt: Long = at(2026, 11, 1, 9, 0), lastSeen: Long? = null, address: String? = "192.168.1.42", port: Int? = PortChoice.DEFAULT) =
        PairedTv(KeyId.of(Random.nextBytes(91)).hex, name, pairedAt, lastSeen, address, port)

    @Test
    fun `the hub's line says how many TVs and their names, or None yet`() {
        assertEquals("None yet", tvsLine(emptyList()))
        assertEquals("1 · Living room TV", tvsLine(listOf(tv("Living room TV"))))
        assertEquals("2 · Living room TV, Bedroom TV", tvsLine(listOf(tv("Living room TV"), tv("Bedroom TV"))))
        assertEquals("5 · A TV, B TV, C TV and 2 more", tvsLine(listOf("A TV", "B TV", "C TV", "D TV", "E TV").map { tv(it) }))
    }

    @Test
    fun `a TV's line says when it was paired, and Seen now while linked, else when it was last seen`() {
        assertEquals("Paired 1 Nov · Seen now", tvLine(tv("Living room TV", lastSeen = now - 1_000), linked = true, now = now, zone = zone))
        assertEquals("Paired 1 Nov · Last seen 5 min ago", tvLine(tv("Bedroom TV", lastSeen = now - 5 * 60_000), linked = false, now = now, zone = zone))
        assertEquals("Paired 1 Nov", tvLine(tv("Bedroom TV"), linked = false, now = now, zone = zone))
        assertEquals("Paired 24 Dec 2025 · Last seen 3 h ago", tvLine(tv("Bedroom TV", pairedAt = at(2025, 12, 24, 9, 0), lastSeen = now - 3 * 3_600_000), linked = false, now = now, zone = zone))
    }

    @Test
    fun `the address as the TV's Pair a phone page shows it`() {
        assertEquals("192.168.1.42", tvAddressLine(tv("Living room TV")))
        assertEquals("192.168.1.42:30123", tvAddressLine(tv("Living room TV", port = 30_123)))
        assertEquals(null, tvAddressLine(tv("Living room TV", address = null)))
    }

    @Test
    fun `each state in its words`() {
        val name = "Living room TV"
        assertEquals("Ready", tvStateLine(TvState.Ready, name))
        assertEquals("NZStreamer isn’t open there", tvStateLine(TvState.NotOpen, name))
        assertEquals("Needs a Usenet provider on the TV", tvStateLine(TvState.NeedsProvider, name))
        assertEquals("Needs an indexer on the TV", tvStateLine(TvState.NeedsIndexer, name))
        // D2PhoneTvStates: what a TV's debrid way lacks, naming no service.
        assertEquals("Needs a debrid service on the TV", tvStateLine(TvState.NeedsService, name))
        assertEquals("The TV’s debrid service is off", tvStateLine(TvState.ServiceOff, name))
        assertEquals("Needs a torrent indexer on the TV", tvStateLine(TvState.NeedsTorrentIndexer, name))
        assertEquals("Set up again: pair again", tvStateLine(TvState.SetUpAgain, name))
        assertEquals("Living room TV removed this phone", tvStateLine(TvState.RemovedHere, name))
        assertEquals("Update NZStreamer on the TV", tvStateLine(TvState.UpdateTv, name))
        assertEquals("Update NZStreamer on this phone", tvStateLine(TvState.UpdatePhone, name))
        assertEquals("Living room TV was set up again", setUpAgainTitle(name))
        assertEquals("Forget Living room TV?", forgetAsk(name))
    }

    @Test
    fun `the page's rows follow the TVs kept and their states, and hold their links while it shows`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val living = tv("Living room TV", lastSeen = now - 5 * 60_000)
        val bedroom = tv("Bedroom TV", port = 30_123)
        val tvs = MutableStateFlow(listOf(living, bedroom))
        val states = MutableStateFlow(mapOf(living.key!! to TvNow(TvState.Ready, linked = true)))
        var held = 0
        val forgotten = mutableListOf<KeyId>()
        val deps = TvsDeps(tvs, states, hold = { held++; AutoCloseable { held-- } }, forget = { forgotten += it }, now = { now }, zone = { zone })
        val store = ViewModelStore()
        val vm = ViewModelProvider.create(store, viewModelFactory { initializer { TvsViewModel(deps) } })[TvsViewModel::class]
        val collected = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.rows.collect {} }
        vm.start()
        runCurrent()
        assertEquals(1, held)
        assertEquals(
            listOf(
                TvRow(living.key!!, "Living room TV", "Paired 1 Nov · Seen now", "192.168.1.42", TvState.Ready),
                TvRow(bedroom.key!!, "Bedroom TV", "Paired 1 Nov", "192.168.1.42:30123", null),
            ),
            vm.rows.value,
        )
        states.value = mapOf(living.key!! to TvNow(TvState.NotOpen, linked = false), bedroom.key!! to TvNow(TvState.SetUpAgain, linked = false))
        runCurrent()
        assertEquals(listOf("Paired 1 Nov · Last seen 5 min ago", "Paired 1 Nov"), vm.rows.value!!.map { it.line })
        assertEquals(listOf(TvState.NotOpen, TvState.SetUpAgain), vm.rows.value!!.map { it.state })
        vm.forget(bedroom.key!!)
        runCurrent()
        assertEquals(listOf(bedroom.key!!), forgotten)
        vm.stop()
        assertEquals(0, held)
        collected.cancel()
        store.clear()
    }

    @Test
    fun `Forget tells a TV it reaches to remove this phone, then the TV goes here with its sessions`() = runBlocking<Unit> {
        val phone = softwareLinkKey()
        val store = LinkStore(MemoryStore(LinkFile()))
        val living = FakeTv(loopback("127.0.0.2"), "Living room TV", paired = setOf(phone.id))
        val bedroom = FakeTv(loopback("127.0.0.3"), "Bedroom TV", paired = setOf(phone.id))
        try {
            store.addTv(living.key.id, living.name, "127.0.0.2", living.port)
            store.addTv(bedroom.key.id, bedroom.name, "127.0.0.3", bedroom.port)
            store.addSession(living.key.id, TvSession("s-1", startedAt = 1L, showTmdbId = 1399))
            val links = TvLinks(
                scope,
                TvLinksDeps(store = store, identity = { phone }, finder = { MutableStateFlow(emptyList()) }, inFront = MutableStateFlow(true), name = "Galaxy phone", app = "0.1.0", allowPeer = { it.isLoopbackAddress }),
            )
            links.start()
            links.hold()
            eventually("both linked") { links.states.value.values.count { it.linked } == 2 }
            links.forget(living.key.id)
            eventually("the TV told") { LinkMessage.Unpair in living.received }
            eventually("its link closed") { living.links.isEmpty() }
            assertEquals(listOf(bedroom.key.id), store.tvs.first().map { it.key })
            assertTrue(store.file.first().tvs.none { it.sessions.isNotEmpty() }, "its sessions went with it")
            eventually("not listed") { living.key.id !in links.states.value }
            // One out of reach: removed here alone.
            bedroom.close()
            eventually("out of reach") { links.states.value[bedroom.key.id]?.linked == false }
            links.forget(bedroom.key.id)
            assertTrue(store.tvs.first().isEmpty())
            links.close()
        } finally {
            living.close()
            bedroom.close()
        }
    }
}
