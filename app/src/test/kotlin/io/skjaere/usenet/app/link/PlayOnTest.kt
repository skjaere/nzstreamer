package io.skjaere.usenet.app.link

import io.skjaere.usenet.app.casting.CastDeviceFacts
import io.skjaere.usenet.app.casting.CastDeviceKind
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.casting.CastLinkNow
import io.skjaere.usenet.data.LinkFile
import io.skjaere.usenet.data.LinkStore
import io.skjaere.usenet.link.KeyId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where Play goes (W11, W18, L30, L36, L64, L77): held in memory alone, never in link.json; a new launch starts on This
 * phone, a recreation keeps the choice through its saved state; the chosen TV's link is held while it is chosen; forgetting
 * it makes This phone the choice again. A Cast device is chosen while its session lives: connecting, connected, or
 * reconnecting, as the Cast connection says; the session's end, a failed connect or a session with another device chooses
 * This phone (W36); a live session wins over a new launch and a recreation (W38, L64), and is never saved. This phone or a
 * TV chosen while casting ends the cast first (L85). The chosen device's target carries what its session's route said of
 * it (L153), never another device's. Cast anyway, told for a speaker's session, lasts that session alone, and a speaker's
 * question goes with it (L122). The names and addresses are invented.
 */
class PlayOnTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val file = MemoryStore(LinkFile())
    private val store = LinkStore(file)
    private val states = MutableStateFlow<Map<KeyId, TvNow>>(emptyMap())
    private val holds = CopyOnWriteArrayList<String>()
    private val cast = MutableStateFlow<CastLinkNow?>(null)
    private val ended = AtomicInteger()
    private val kitchen = PlayChoice.Cast("route:kitchen", "Kitchen display")

    @AfterEach
    fun stop() {
        scope.cancel()
    }

    /** Over the Cast connection [cast]; Stop casting ends its session, as Google's software would say. */
    private fun playOn() = PlayOn(
        scope, store.tvs, states, cast,
        endCast = {
            ended.incrementAndGet()
            cast.value = null
        },
    ) { key ->
        holds += "hold ${key.short}"
        AutoCloseable { holds += "let go ${key.short}" }
    }

    /** [kitchen]'s session, [link]: what the Cast connection says once its row is tapped. */
    private fun kitchen(link: CastLink) {
        cast.value = CastLinkNow(kitchen.route, kitchen.name, link)
    }

    private fun PlayOn.casts(link: CastLink) = targets(PlayTarget.Cast(kitchen.route, kitchen.name, link))

    private fun tv(name: String): KeyId = runBlocking {
        val key = KeyId.of(Random.nextBytes(91))
        store.addTv(key, name, "192.168.1.42", PortChoice.DEFAULT)
        key
    }

    private fun PlayOn.targets(target: PlayTarget) = eventually("the target $target", now = { this.target.value }) { this.target.value == target }

    /**
     * L148: the list says where it was opened from, Search's, a release list's or Import's ([SheetPlace.PhoneOnly]), and
     * closed it is none. Play's own openings (a TV not open, a Cast device connecting: the router's) name no place: a
     * title's page's.
     */
    @Test
    fun `the list opens from where it is asked, closed it is none, and the router's own opening is a title page's`() {
        val on = playOn()
        assertNull(on.sheet.value)
        on.openSheet(SheetPlace.PhoneOnly)
        assertEquals(SheetPlace.PhoneOnly, on.sheet.value)
        on.closeSheet()
        assertNull(on.sheet.value)
        on.openSheet(SheetPlace.Player)
        assertEquals(SheetPlace.Player, on.sheet.value)
        on.closeSheet()
        on.openSheet()
        assertEquals(SheetPlace.Title, on.sheet.value)
    }

    @Test
    fun `This phone until a TV is chosen, and the chosen TV by its kept name and its link's state`() {
        val living = tv("Living room TV")
        val on = playOn()
        on.targets(PlayTarget.Phone)
        on.choose(living)
        on.targets(PlayTarget.Tv(living, "Living room TV", null))
        states.value = mapOf(living to TvNow(TvState.Ready, linked = true))
        on.targets(PlayTarget.Tv(living, "Living room TV", TvState.Ready))
        on.choose(null)
        on.targets(PlayTarget.Phone)
    }

    @Test
    fun `a new launch starts on This phone, whatever was chosen before`() {
        val living = tv("Living room TV")
        val on = playOn()
        on.choose(living)
        on.targets(PlayTarget.Tv(living, "Living room TV", null))
        // MainActivity made without saved state: Back out of the app, then its icon.
        on.restore(null)
        on.targets(PlayTarget.Phone)
        assertEquals(PlayChoice.Phone, on.chosen.value)
    }

    @Test
    fun `a recreation keeps the choice through its saved state, in this process or a new one`() {
        val living = tv("Living room TV")
        val on = playOn()
        on.choose(living)
        val saved = on.saved
        assertEquals(living.hex, saved)
        // A rotation: the same process.
        on.restore(saved)
        on.targets(PlayTarget.Tv(living, "Living room TV", null))
        // Back from Recents after the process went: a new one, its MainActivity given the state it saved.
        val again = playOn()
        again.targets(PlayTarget.Phone)
        again.restore(saved)
        again.targets(PlayTarget.Tv(living, "Living room TV", null))
    }

    @Test
    fun `the choice is never written to link json`() = runBlocking<Unit> {
        val living = tv("Living room TV")
        val before = file.data.first()
        val on = playOn()
        on.choose(living)
        on.targets(PlayTarget.Tv(living, "Living room TV", null))
        on.restore(on.saved)
        on.choose(null)
        on.targets(PlayTarget.Phone)
        assertEquals(before, file.data.first())
    }

    @Test
    fun `the chosen TV's link is held while it is chosen, and let go as another or This phone is`() {
        val living = tv("Living room TV")
        val bedroom = tv("Bedroom TV")
        val on = playOn()
        on.choose(living)
        eventually("held", now = { holds }) { holds == listOf("hold ${living.short}") }
        on.choose(bedroom)
        eventually("the other held", now = { holds }) { holds == listOf("hold ${living.short}", "let go ${living.short}", "hold ${bedroom.short}") }
        on.choose(null)
        eventually("let go", now = { holds }) { holds.last() == "let go ${bedroom.short}" }
    }

    @Test
    fun `forgetting the chosen TV makes This phone the choice`() = runBlocking<Unit> {
        val living = tv("Living room TV")
        val on = playOn()
        on.choose(living)
        on.targets(PlayTarget.Tv(living, "Living room TV", null))
        store.forgetTv(living)
        on.targets(PlayTarget.Phone)
        eventually("nothing chosen") { on.chosen.value == PlayChoice.Phone }
        eventually("its link let go", now = { holds }) { holds.last() == "let go ${living.short}" }
    }

    @Test
    fun `a question about a TV goes with it as it is forgotten`() = runBlocking<Unit> {
        val living = tv("Living room TV")
        val bedroom = tv("Bedroom TV")
        val on = playOn()
        on.choose(living)
        on.targets(PlayTarget.Tv(living, "Living room TV", null))
        on.ask(PlayOnAsk.Removed(bedroom, "Bedroom TV"))
        store.forgetTv(living)
        on.targets(PlayTarget.Phone)
        kotlin.test.assertEquals(PlayOnAsk.Removed(bedroom, "Bedroom TV"), on.asking.value, "another TV's question stays")
        store.forgetTv(bedroom)
        eventually("its question gone", now = { on.asking.value }) { on.asking.value == null }
        // The chosen TV's update question goes with it too.
        val study = tv("Study TV")
        on.choose(study)
        on.targets(PlayTarget.Tv(study, "Study TV", null))
        on.ask(PlayOnAsk.Update(tv = true, name = "Study TV"))
        store.forgetTv(study)
        eventually("its question gone", now = { on.asking.value }) { on.asking.value == null }
    }

    @Test
    fun `a saved TV this phone no longer keeps restores This phone`() = runBlocking<Unit> {
        val living = tv("Living room TV")
        store.forgetTv(living)
        val on = playOn()
        on.restore(living.hex)
        eventually("nothing chosen", now = { on.chosen.value }) { on.chosen.value == PlayChoice.Phone }
        on.targets(PlayTarget.Phone)
    }

    @Test
    fun `a Cast device chosen connects, then plays there`() {
        val on = playOn()
        // The row tapped: the connection says connecting, and the device is chosen at once (This phone unticked).
        kitchen(CastLink.Connecting)
        on.choose(kitchen)
        on.casts(CastLink.Connecting)
        assertEquals(kitchen, on.chosen.value)
        kitchen(CastLink.Connected)
        on.casts(CastLink.Connected)
        // The phone's Wi-Fi blinks: suspended, then back. Chosen throughout.
        kitchen(CastLink.Reconnecting)
        on.casts(CastLink.Reconnecting)
        kitchen(CastLink.Connected)
        on.casts(CastLink.Connected)
        assertEquals(0, ended.get(), "nothing ended")
    }

    /** L153: once connected, Play's target carries what the session's route said of the device, for the play and its ranking. */
    @Test
    fun `the connected target carries the session's facts`() {
        val facts = CastDeviceFacts("device-kitchen", "Chromecast Ultra", "192.168.1.31", CastDeviceKind.Video, fixedVolume = false)
        val on = playOn()
        cast.value = CastLinkNow(kitchen.route, kitchen.name, CastLink.Connecting, facts)
        on.choose(kitchen)
        on.targets(PlayTarget.Cast(kitchen.route, kitchen.name, CastLink.Connecting, facts))
        cast.value = CastLinkNow(kitchen.route, kitchen.name, CastLink.Connected, facts)
        on.targets(PlayTarget.Cast(kitchen.route, kitchen.name, CastLink.Connected, facts))
        assertEquals("Cast(Connected)", on.target.value.toString(), "the facts print nowhere")
    }

    /** Another device's session never lends its facts to the device chosen, nor does a choice with no session yet. */
    @Test
    fun `a session for another route gives none`() {
        val den = CastDeviceFacts("device-den", "Chromecast", "192.168.1.32", CastDeviceKind.Video, fixedVolume = true)
        assertEquals(
            PlayTarget.Cast(kitchen.route, kitchen.name, CastLink.Connecting, device = null),
            castTarget(kitchen, CastLinkNow("route:den", "Den TV", CastLink.Connected, den)),
        )
        assertEquals(PlayTarget.Cast(kitchen.route, kitchen.name, CastLink.Connecting, device = null), castTarget(kitchen, null))
        assertEquals(
            PlayTarget.Cast(kitchen.route, kitchen.name, CastLink.Reconnecting, den),
            castTarget(kitchen, CastLinkNow(kitchen.route, kitchen.name, CastLink.Reconnecting, den)),
        )
    }

    @Test
    fun `the session's end, a failed connect or another device's session chooses This phone`() {
        val on = playOn()
        kitchen(CastLink.Connected)
        on.choose(kitchen)
        on.casts(CastLink.Connected)
        // Ended by the device (its own timers), by another sender, or given up after a suspension.
        cast.value = null
        on.targets(PlayTarget.Phone)
        eventually("This phone chosen", now = { on.chosen.value }) { on.chosen.value == PlayChoice.Phone }
        // Refused, or not connected in time: the connection says none.
        kitchen(CastLink.Connecting)
        on.choose(kitchen)
        on.casts(CastLink.Connecting)
        cast.value = null
        on.targets(PlayTarget.Phone)
        // A session with another device (its row tapped elsewhere): not the one chosen.
        kitchen(CastLink.Connected)
        on.choose(kitchen)
        on.casts(CastLink.Connected)
        cast.value = CastLinkNow("route:den", "Den TV", CastLink.Connected)
        on.targets(PlayTarget.Phone)
        assertEquals(0, ended.get(), "nothing more to end")
    }

    /**
     * L127: the choice made last on purpose, which a list on screen ranks for: chosen on the sheet, or by a new launch, but
     * not This phone coming back by itself as the session ends, fails or goes to another device (W36).
     */
    @Test
    fun `the choice made on purpose stays as This phone comes back by itself`() {
        val living = tv("Living room TV")
        val on = playOn()
        assertEquals(PlayChoice.Phone, on.picked.value)
        kitchen(CastLink.Connected)
        on.choose(kitchen)
        assertEquals(kitchen, on.picked.value)
        on.casts(CastLink.Connected)
        // The device ended the session: This phone is chosen by itself, and the choice made stays.
        cast.value = null
        on.targets(PlayTarget.Phone)
        eventually("This phone chosen", now = { on.chosen.value }) { on.chosen.value == PlayChoice.Phone }
        assertEquals(kitchen, on.picked.value)
        // Chosen on purpose: This phone, then a TV.
        on.choose(PlayChoice.Phone)
        assertEquals(PlayChoice.Phone, on.picked.value)
        on.choose(living)
        assertEquals(PlayChoice.Tv(living), on.picked.value)
        // A new launch chooses This phone; one with a live session, the device.
        on.restore(null)
        assertEquals(PlayChoice.Phone, on.picked.value)
        kitchen(CastLink.Connected)
        on.choose(kitchen)
        on.restore(null)
        assertEquals(kitchen, on.picked.value)
    }

    @Test
    fun `while the session lives the device stays chosen, whatever its plays do`() {
        val on = playOn()
        kitchen(CastLink.Connected)
        on.choose(kitchen)
        on.casts(CastLink.Connected)
        // A play's end (FINISHED, a failure, Cancel) leaves the session connected (L85): only its end chooses This phone.
        Thread.sleep(300)
        on.casts(CastLink.Connected)
        assertEquals(kitchen, on.chosen.value)
    }

    /**
     * After a cast (W36, L85; CsPhoneStopped): Stop casting on the remote or the bar, and the device closing the session
     * after a long pause, end the session with the play, so Play goes back to this phone and Resume plays here. Watched
     * there, the session lives on, and Play still goes there, until the device closes it about 5 minutes later.
     */
    @Test
    fun `after a cast Play goes back to this phone with its session, and stays on the device while the session lives`() {
        val on = playOn()
        for (end in listOf("Stop casting", "the long pause")) {
            kitchen(CastLink.Connected)
            on.choose(kitchen)
            on.casts(CastLink.Connected)
            // The cast's own Stop (castExtras.stop, not a choice here) or the device's timer: the session is gone.
            cast.value = null
            on.targets(PlayTarget.Phone)
            assertEquals(PlayChoice.Phone, on.chosen.value, end)
        }
        // Watched there: the play ended, the session not.
        kitchen(CastLink.Connected)
        on.choose(kitchen)
        on.casts(CastLink.Connected)
        Thread.sleep(300)
        assertEquals(kitchen, on.chosen.value, "Play still goes there")
        // The device closes its idle session: This phone from then.
        cast.value = null
        on.targets(PlayTarget.Phone)
        assertEquals(0, ended.get(), "nothing ended from here")
    }

    @Test
    fun `a new launch with a live cast chooses it, and without one This phone`() {
        val on = playOn()
        kitchen(CastLink.Connected)
        on.choose(kitchen)
        // MainActivity made afresh while the session lives in this process (W38, L64).
        on.restore(null)
        assertEquals(kitchen, on.chosen.value)
        on.casts(CastLink.Connected)
        // The session gone: a new launch is This phone again (W18).
        cast.value = null
        on.targets(PlayTarget.Phone)
        on.restore(null)
        assertEquals(PlayChoice.Phone, on.chosen.value)
    }

    @Test
    fun `a recreation keeps a TV, but a live cast wins, and is never saved`() {
        val living = tv("Living room TV")
        val on = playOn()
        on.choose(living)
        val saved = on.saved
        on.restore(saved)
        on.targets(PlayTarget.Tv(living, "Living room TV", null))
        // A cast started since: a recreation with the TV's saved state still casts.
        kitchen(CastLink.Connected)
        on.choose(kitchen)
        assertNull(on.saved, "a Cast choice is never saved")
        on.restore(saved)
        assertEquals(kitchen, on.chosen.value)
        on.casts(CastLink.Connected)
    }

    @Test
    fun `This phone or a TV chosen while casting ends the cast first`() {
        val living = tv("Living room TV")
        val on = playOn()
        kitchen(CastLink.Connected)
        on.choose(kitchen)
        on.casts(CastLink.Connected)
        on.choose(PlayChoice.Phone)
        assertEquals(1, ended.get())
        on.targets(PlayTarget.Phone)
        kitchen(CastLink.Connected)
        on.choose(kitchen)
        on.casts(CastLink.Connected)
        on.choose(living)
        assertEquals(2, ended.get())
        on.targets(PlayTarget.Tv(living, "Living room TV", null))
        // With no cast, choosing ends nothing.
        on.choose(PlayChoice.Phone)
        assertEquals(2, ended.get())
    }

    /** [flow], each value told to a collector [lagMillis] late, as to one on a busy thread; its value is always the latest. */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class LateFlow<T>(private val flow: StateFlow<T>, private val lagMillis: Long) : StateFlow<T> by flow {
        override suspend fun collect(collector: FlowCollector<T>): Nothing = flow.collect {
            delay(lagMillis)
            collector.emit(it)
        }
    }

    @Test
    fun `a session told late never unchooses the device it is with`() {
        // The row says connecting before it chooses the device; the choice's watcher may hear the choice first.
        val on = PlayOn(scope, store.tvs, states, LateFlow(cast, lagMillis = 300)) { AutoCloseable {} }
        Thread.sleep(100)
        kitchen(CastLink.Connecting)
        on.choose(kitchen)
        Thread.sleep(800)
        assertEquals(kitchen, on.chosen.value)
        on.casts(CastLink.Connecting)
    }

    /** L122: Cast anyway is told for a speaker's session and lasts it, reconnecting too; the session's end forgets it. */
    @Test
    fun `Cast anyway lasts the speaker's session alone`() {
        val on = playOn()
        kitchen(CastLink.Connected)
        assertFalse(on.castAnywayTold(kitchen.route))
        on.castAnyway(kitchen.route)
        assertTrue(on.castAnywayTold(kitchen.route))
        assertFalse(on.castAnywayTold("route:den"))
        kitchen(CastLink.Reconnecting)
        kitchen(CastLink.Connected)
        Thread.sleep(100)
        assertTrue(on.castAnywayTold(kitchen.route), "the same session, come back")
        cast.value = null
        eventually("forgotten with its session") { !on.castAnywayTold(kitchen.route) }
        kitchen(CastLink.Connected)
        Thread.sleep(100)
        assertFalse(on.castAnywayTold(kitchen.route), "a new session")
    }

    /** L122: a speaker's question goes as its session ends, as if cancelled: nothing it would have played plays. */
    @Test
    fun `a speaker's question goes with its session`() {
        val on = playOn()
        kitchen(CastLink.Connected)
        val cancelled = AtomicInteger()
        val ask = PlayOnAsk.SoundOnly(kitchen.route, kitchen.name, castAnyway = { error("never") }, playHere = { error("never") }, cancel = { cancelled.incrementAndGet() })
        on.ask(ask)
        Thread.sleep(100)
        assertEquals(ask, on.asking.value, "asked while its session lives")
        cast.value = null
        eventually("the question cancelled") { cancelled.get() == 1 }
        assertNull(on.asking.value)
    }
}
