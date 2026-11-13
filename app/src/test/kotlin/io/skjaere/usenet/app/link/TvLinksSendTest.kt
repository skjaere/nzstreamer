package io.skjaere.usenet.app.link

import io.skjaere.usenet.data.LinkFile
import io.skjaere.usenet.data.LinkStore
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.Playback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The phone's links as Play on TV uses them (L36, item 22), over real sockets on loopback: a TV held alone (the one Play
 * goes to) is linked though no page lists TVs, and let go its link closes; a command goes out on its link with a number
 * of its own, and what the TV says there is heard as that TV's. The names are invented.
 */
class TvLinksSendTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val phone = softwareLinkKey()
    private val store = LinkStore(MemoryStore(LinkFile()))
    private val tvs = CopyOnWriteArrayList<FakeTv>()
    private val links = TvLinks(
        scope,
        TvLinksDeps(
            store = store, identity = { phone }, finder = { MutableStateFlow(emptyList()) }, inFront = MutableStateFlow(true), name = "Galaxy phone",
            app = "0.1.0", allowPeer = { it.isLoopbackAddress }, lingerMillis = 300, backoff = { 100 },
        ),
    )

    @AfterEach
    fun stop() {
        tvs.forEach { it.close() }
        links.close()
        scope.cancel()
    }

    private fun tv(address: String, name: String) = FakeTv(loopback(address), name, paired = setOf(phone.id)).also { tvs += it }

    private fun paired(tv: FakeTv): KeyId = runBlocking {
        assertTrue(store.addTv(tv.key.id, tv.name, tv.address.hostAddress!!, tv.port))
        tv.key.id
    }

    @Test
    fun `a TV held alone is linked with no page listing TVs, and its link closes once let go`() {
        val living = tv("127.0.0.2", "Living room TV")
        val bedroom = tv("127.0.0.3", "Bedroom TV")
        val key = paired(living)
        paired(bedroom)
        links.start()
        val held = links.holdTv(key)
        eventually("the held TV linked", now = { links.states.value }) { links.states.value[key]?.linked == true }
        Thread.sleep(300)
        assertTrue(bedroom.links.isEmpty(), "a TV not held isn't linked")
        held.close()
        eventually("its link closed once let go") { living.links.isEmpty() }
    }

    @Test
    fun `a command goes out on the TV's link with a number of its own, and what the TV says is heard as that TV's, its hello first`() = runBlocking<Unit> {
        val living = tv("127.0.0.2", "Living room TV")
        val key = paired(living)
        links.start()
        val heard = CopyOnWriteArrayList<TvHeard>()
        val listening = scope.launch { links.heard.collect { heard += it } }
        links.holdTv(key)
        eventually("linked") { links.states.value[key]?.linked == true && living.links.isNotEmpty() }
        val first = assertNotNull(links.command(key) { seq -> LinkMessage.Pause(seq, "s-1") }, "sent")
        val second = assertNotNull(links.command(key) { seq -> LinkMessage.Resume(seq, "s-1") }, "sent")
        assertNotEquals(first, second, "each command its own number")
        eventually("the TV heard both", now = { living.received }) { living.received.filterIsInstance<LinkMessage.Command>().size == 2 }
        assertEquals(listOf(first, second), living.received.filterIsInstance<LinkMessage.Command>().map { it.seq })
        living.links.single().send(LinkMessage.Ack(first, "s-1"))
        living.links.single().send(LinkMessage.Status("s-1", Playback.Playing, mine = true))
        eventually("the phone heard them", now = { heard }) { heard.size == 3 }
        // The hello as the link came up, which names the play there now (item 25).
        assertIs<LinkMessage.Hello>(heard.first().message)
        assertEquals(listOf(LinkMessage.Ack(first, "s-1"), LinkMessage.Status("s-1", Playback.Playing, mine = true)), heard.drop(1).map { it.message })
        assertTrue(heard.all { it.tv == key }, "heard as that TV's")
        listening.cancel()
    }

    @Test
    fun `a command for a TV whose link isn't up isn't sent`() {
        val living = tv("127.0.0.2", "Living room TV")
        val key = paired(living)
        living.close()
        links.start()
        links.holdTv(key)
        Thread.sleep(300)
        assertNull(links.command(key) { seq -> LinkMessage.Pause(seq, "s-1") })
    }
}
