package io.skjaere.usenet.app.link

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.link.KeyId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * How a phone reaches a paired TV (item 13, L12, L36) on a real device: the app's TvLinks with the platform's NsdManager
 * looking, against a TV in the test's process on this device's own private address, its record advertised through the
 * app's LinkNsd under an id of its own (L14). The TV reads Ready, NotOpen when NZStreamer there closes, Ready again when
 * it reopens, and SetUpAgain once another key at its address gives its name and mDNS has gone 10 s without its own id.
 * The store is the test's own, in memory; the names are invented.
 */
@RunWith(AndroidJUnit4::class)
class TvLinksDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val phone = softwareKey()
    private val store = memoryLinkStore()
    private val tvs = CopyOnWriteArrayList<FakeTv>()
    private val links = TvLinks(
        scope,
        TvLinksDeps(store = store, identity = { phone }, finder = LinkNsdFinder(context), inFront = MutableStateFlow(true), name = "Test phone", app = "0.1.0"),
    )

    @After
    fun stop() {
        tvs.forEach { it.close() }
        scope.cancel()
    }

    private fun tv(name: String, port: Int = 0, key: io.skjaere.usenet.link.LinkKey = softwareKey()) =
        FakeTv(context, name, key = key, paired = setOf(phone.id), port = port).also { tvs += it }

    private fun reads(key: KeyId, state: TvState, linked: Boolean, seconds: Long) {
        val until = System.nanoTime() + seconds * 1_000_000_000
        while (links.states.value[key] != TvNow(state, linked)) {
            assertTrue("$state (linked $linked) within $seconds s; now ${links.states.value[key]}", System.nanoTime() < until)
            Thread.sleep(50)
        }
    }

    @Test
    fun readyNotOpenWhenItClosesAndReadyWhenItReopens() = runBlocking<Unit> {
        val living = tv(inventedTvName("Living room TV"))
        val key = living.key.id
        assertTrue(store.addTv(key, living.name, living.address.hostAddress!!, living.port))
        links.start()
        val page = links.hold()
        reads(key, TvState.Ready, linked = true, seconds = 15)
        living.close()
        reads(key, TvState.NotOpen, linked = false, seconds = 15)
        living.reopen()
        reads(key, TvState.Ready, linked = true, seconds = 20)
        assertEquals(1, living.links.size)
        page.close()
    }

    @Test
    fun anotherKeyGivingTheSavedNameReadsSetUpAgain() = runBlocking<Unit> {
        val name = inventedTvName("Living room TV")
        val living = tv(name)
        val key = living.key.id
        assertTrue(store.addTv(key, name, living.address.hostAddress!!, living.port))
        links.start()
        val page = links.hold()
        reads(key, TvState.Ready, linked = true, seconds = 15)
        // NZStreamer there set up again: a new key (its record a new id), the same name, address and port.
        living.close()
        tv(name, port = living.port)
        reads(key, TvState.SetUpAgain, linked = false, seconds = 45)
        page.close()
    }
}
