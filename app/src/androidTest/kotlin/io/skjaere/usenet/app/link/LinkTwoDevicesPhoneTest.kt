package io.skjaere.usenet.app.link

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.container
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.link.LinkMessage.Play
import io.skjaere.usenet.link.PairAttempt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress

/**
 * The phone's half of phone-to-TV across two devices (items 16 and 28, L14): on phone34, a phone's links find the pair
 * TV's record with the platform's NsdManager, pair with it through Add a TV's own pairing and keeping (a seam that says Yes
 * to the number; the TV's half says OK), and choose it, its pinned link Ready. Then Play, as the Movie page's with it
 * chosen: the TV's sheet mirrored, then its player's play, which the bar follows; Stop; and where it stopped, the TV's
 * Final, saved in this phone's library (recordRemoteProgress) and acked. Opt-in (`-e linkPeer phone`, from
 * emu-link-pair.sh): skipped in every other run. Its store and key are its own; the names and the film are invented, and
 * the film's place here is put back after.
 */
@RunWith(AndroidJUnit4::class)
class LinkTwoDevicesPhoneTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private var role: PhoneRole? = null

    @After
    fun stop() {
        role?.close() ?: return
        runBlocking { container.library.recordProgress(WatchState(WatchKey.movie(PAIR_FILM), WatchKind.Movie, movieTmdbId = PAIR_FILM, title = PAIR_FILM_TITLE, updatedAt = 0), 0, 360_000) }
    }

    @Test
    fun findsPairsAndPlaysOnTheTv() = runBlocking<Unit> {
        assumeTrue("opt-in: -e linkPeer phone", linkPeer == "phone")
        val role = PhoneRole(finder = LinkNsdFinder(context), name = "Pair test phone", record = container.library::recordRemoteProgress).also { role = it }
        val links = role.links
        val store = role.store
        val looking = links.look()
        val started = System.nanoTime()
        val found = waitFor(90) { links.discovery.value?.tvs?.firstOrNull { it.record.name == PAIR_TV_NAME && it.record.pairing && it.addresses.isNotEmpty() } }
        assertNotNull("discovery found no pair TV taking pairings in 90 s; found ${links.discovery.value?.tvs?.size}", found)
        pairReport(TAG, "pair phone: found the TV in ${(System.nanoTime() - started) / 1_000_000} ms, ${found!!.addresses.size} address(es)")
        val target = InetSocketAddress(found.record.v4?.takeIf(found.addresses::contains) ?: found.addresses.first(), found.port)
        // Add a TV's own pairing and keeping, its prompt saying Yes to the number.
        val deps = AddTvDeps.of(links, store)
        val result = deps.pair(
            target,
            object : PairPrompt {
                override fun waiting() = Unit

                override fun compare(tvName: String, digits: String, answer: (Boolean) -> Unit) = answer(true)
            },
        )
        looking.close()
        val paired = result as? PairAttempt.Result.Paired
        assertNotNull("pairing ended $result", paired)
        assertTrue(deps.save(paired!!.tv, PeerNames.clean(paired.name, "TV"), target.address as java.net.Inet4Address, target.port))
        pairReport(TAG, "pair phone: paired")
        val key = store.tvs.first().single().key!!
        assertEquals(paired.tv, key)
        assertEquals("the pinned link", TvNow(TvState.Ready, linked = true), role.choose(key))
        pairReport(TAG, "pair phone: linked, pinned")
        // Play (item 28): the TV's sheet, then its player, which the bar follows.
        role.play(key, PAIR_TV_NAME, Play.Movie(PAIR_FILM, PAIR_FILM_TITLE, 2026))
        val playing = waitFor(120) { role.playing.now.value?.takeIf { it.bar == RemoteBar.Playing && it.positionMs >= 2_000 } }
        assertNotNull("not playing on the TV in 120 s: start ${role.start.now.value}, bar ${role.playing.now.value}", playing)
        assertEquals(WatchKey.movie(PAIR_FILM), playing!!.watchKey)
        pairReport(TAG, "pair phone: playing on the TV")
        // Stop: where it stopped comes back, saved here and acked.
        role.playing.stop()
        val final = waitFor(60) { role.recorded.firstOrNull() }
        assertNotNull("no Final from the TV in 60 s", final)
        assertEquals(playing.session to WatchKey.movie(PAIR_FILM), final!!.session to final.watchKey)
        assertTrue("stopped past 2 s: ${final.positionMs} ms", final.positionMs >= 2_000 && !final.watched)
        assertEquals("saved in this phone's library", final.positionMs, container.library.state(WatchKey.movie(PAIR_FILM)).first()?.positionMs)
        assertEquals("acked", listOf(final.id), role.acked)
        assertNotNull("the bar gone with the TV's stopped status", waitFor(30) { (role.playing.now.value == null).takeIf { it } })
        pairReport(TAG, "pair phone: stopped there; where it stopped saved here and acked")
    }

    /** [value]'s first non-null within [seconds]; null when none came. */
    private fun <T> waitFor(seconds: Long, value: () -> T?): T? {
        val until = System.nanoTime() + seconds * 1_000_000_000
        while (System.nanoTime() < until) {
            value()?.let { return it }
            Thread.sleep(100)
        }
        return null
    }

    private companion object {
        const val TAG = "LinkTwoDevices"
    }
}
