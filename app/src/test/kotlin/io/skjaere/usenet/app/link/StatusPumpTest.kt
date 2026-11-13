package io.skjaere.usenet.app.link

import io.skjaere.usenet.app.debrid.ServiceSource
import io.skjaere.usenet.app.play.Launching
import io.skjaere.usenet.app.play.NowPlaying
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.RemotePlay
import io.skjaere.usenet.app.play.SheetNow
import io.skjaere.usenet.core.PipelineStep
import io.skjaere.usenet.core.StepStatus
import io.skjaere.usenet.core.UsenetSource
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.LinkMessage.Hello
import io.skjaere.usenet.link.LinkMessage.Status
import io.skjaere.usenet.link.Playback
import io.skjaere.usenet.link.SheetStep
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L34: what the TV says of the play it shows, to its paired phones' links. The phone whose session it is hears it titled,
 * its sheet mirrored while it starts; every other paired phone hears the state alone, and of the TV's own plays no phone
 * hears anything. Sent on change, every second while it plays (the player says where it is each second), and never more
 * than 4 a second to a link, the last change always going out. A session that ends is told so: replaced by another play,
 * or stopped. The phones and titles are invented.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusPumpTest {
    private val galaxy = KeyId.of(byteArrayOf(1, 2, 3))
    private val tablet = KeyId.of(byteArrayOf(4, 5, 6))
    private val sheet = MutableStateFlow(SheetNow(PlayState.Idle, null))
    private val player = MutableStateFlow<NowPlaying?>(null)
    private val launching = MutableStateFlow<Launching?>(null)

    private fun session(id: String, phone: KeyId = galaxy) = RemotePlay(id, phone.hex, if (phone == galaxy) "Galaxy phone" else "Invented tablet")

    /** A paired phone's link: what it heard, and when. */
    private class Link(override val phone: KeyId, private val clock: () -> Long) : PhoneLink {
        val heard = mutableListOf<Pair<Long, LinkMessage>>()
        val statuses: List<Status> get() = heard.map { it.second }.filterIsInstance<Status>()

        override fun send(message: LinkMessage) {
            heard += clock() to message
        }
    }

    private fun TestScope.pump(searchSteps: () -> List<PipelineStep> = { UsenetSource.STEPS }): StatusPump {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return StatusPump(backgroundScope, StatusPumpDeps(sheet, player, launching, clock = { testScheduler.currentTime }, context = dispatcher, searchSteps = searchSteps))
            .also { runCurrent() }
    }

    private fun TestScope.link(phone: KeyId, pump: StatusPump): Link = Link(phone) { testScheduler.currentTime }.also {
        pump.linked(it)
        runCurrent()
    }

    private val running = PlayState.Running(
        "Harbor Lights", "S02E04 · Low Water", "Searched 2 indexers by TVDB id · 6 found", 1, 3, "Harbor.Lights.S02E04.2160p.WEB-DL-GRP", listOf("2160p"),
        mapOf(PipelineStep.FetchNzb to StepStatus.Running()), null,
    )

    private fun playing(remote: RemotePlay?, positionMs: Long = 0, state: Playback = Playback.Playing, failure: String? = null) =
        NowPlaying(state, positionMs, 3_120_000, "Harbor Lights", "S02E04 · Low Water", "episode:900002:2:4", failure, remote)

    @Test
    fun `the owner hears the sheet titled, another paired phone the state alone`() = runTest {
        val pump = pump()
        val owner = link(galaxy, pump)
        val other = link(tablet, pump)
        sheet.value = SheetNow(running, session("s-1"))
        runCurrent()
        assertEquals(
            listOf(Status("s-1", Playback.Starting, mine = true, title = "Harbor Lights", subtitle = "S02E04 · Low Water", sheet = SheetMirror.of(running))),
            owner.statuses,
        )
        assertEquals(listOf(Status("s-1", Playback.Starting)), other.statuses)
    }

    /**
     * D2PhoneTvStarting (L223): a search's mirrored sheet carries the rows the TV's own sheet lists while it searches, as
     * the coordinator says them for the play: a debrid service's on a TV that plays from its service alone.
     */
    @Test
    fun `a search's sheet carries the rows the TV's own sheet lists`() = runTest {
        var steps = ServiceSource.STEPS
        val pump = pump { steps }
        val owner = link(galaxy, pump)
        val searching = PlayState.Searching("Harbor Lights", "S02E04 · Low Water", "Searching your indexers…")
        sheet.value = SheetNow(searching, session("s-1"))
        runCurrent()
        assertEquals(listOf(SheetStep.Prepare, SheetStep.Open, SheetStep.ProbeTracks), owner.statuses.last().sheet?.rows)
        // The next play's, Usenet's: what the coordinator says as it starts.
        steps = UsenetSource.STEPS
        sheet.value = SheetNow(searching.copy(line = "Searching 2 indexers for the episode and season packs"), session("s-2"))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(SheetStep.FetchNzb, SheetStep.CheckAvailability, SheetStep.Open, SheetStep.ProbeTracks), owner.statuses.last { it.session == "s-2" }.sheet?.rows)
    }

    @Test
    fun `the TV's own plays are told to no phone`() = runTest {
        val pump = pump()
        val owner = link(galaxy, pump)
        sheet.value = SheetNow(running, null)
        runCurrent()
        player.value = playing(null)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(emptyList(), owner.heard)
        assertNull(pump.session(galaxy))
    }

    @Test
    fun `the player's play goes out with where it is, its length, its watch key and its failure`() = runTest {
        val pump = pump()
        val owner = link(galaxy, pump)
        player.value = playing(session("s-1"), positionMs = 1_930_000)
        runCurrent()
        assertEquals(
            Status(
                "s-1", Playback.Playing, mine = true, title = "Harbor Lights", subtitle = "S02E04 · Low Water", watchKey = "episode:900002:2:4",
                positionMs = 1_930_000, durationMs = 3_120_000,
            ),
            owner.statuses.single(),
        )
        advanceTimeBy(1_000)
        player.value = playing(session("s-1"), positionMs = 1_935_000, state = Playback.Failed, failure = "Part of this release arrived damaged.")
        runCurrent()
        assertEquals(
            Status(
                "s-1", Playback.Failed, mine = true, title = "Harbor Lights", subtitle = "S02E04 · Low Water", watchKey = "episode:900002:2:4",
                positionMs = 1_935_000, durationMs = 3_120_000, failure = "Part of this release arrived damaged.",
            ),
            owner.statuses.last(),
        )
    }

    @Test
    fun `while it plays, the owner hears where it is each second, the others only its changes of state`() = runTest {
        val pump = pump()
        val owner = link(galaxy, pump)
        val other = link(tablet, pump)
        repeat(10) { second ->
            player.value = playing(session("s-1"), positionMs = second * 1_000L)
            runCurrent()
            advanceTimeBy(1_000)
        }
        assertEquals((0 until 10).map { it * 1_000L }, owner.statuses.map { it.positionMs })
        assertEquals(listOf(Status("s-1", Playback.Playing)), other.statuses)
    }

    @Test
    fun `changes faster than 4 a second reach a link at most 4 a second, the last one always`() = runTest {
        val pump = pump()
        val owner = link(galaxy, pump)
        // The availability check counting up, 50 changes a second for two seconds.
        repeat(100) { done ->
            sheet.value = SheetNow(running.copy(steps = mapOf(PipelineStep.CheckAvailability to StepStatus.Running(done, 256))), session("s-1"))
            runCurrent()
            advanceTimeBy(20)
        }
        advanceTimeBy(2_000)
        runCurrent()
        val times = owner.heard.map { it.first }
        for (at in times) assertTrue(times.count { it in at until at + 1_000 } <= 4, "more than 4 within a second of $at: $times")
        assertTrue(times.size >= 6, "it kept up: $times")
        assertEquals(99, owner.statuses.last().sheet?.steps?.single()?.done)
    }

    @Test
    fun `a session that ends is told so, replaced by another play or stopped, before what comes next`() = runTest {
        val pump = pump()
        val owner = link(galaxy, pump)
        val other = link(tablet, pump)
        player.value = playing(session("s-1"), positionMs = 600_000)
        runCurrent()
        // The tablet asks: its play's sheet takes the player's place.
        sheet.value = SheetNow(running, session("s-2", tablet))
        player.value = null
        runCurrent()
        advanceTimeBy(1_000)
        val titled = Status(
            "s-1", Playback.Playing, mine = true, title = "Harbor Lights", subtitle = "S02E04 · Low Water", watchKey = "episode:900002:2:4",
            positionMs = 600_000, durationMs = 3_120_000,
        )
        assertEquals(listOf(titled, titled.copy(state = Playback.Replaced), Status("s-2", Playback.Starting)), owner.statuses)
        val tablets = Status("s-2", Playback.Starting, mine = true, title = "Harbor Lights", subtitle = "S02E04 · Low Water", sheet = SheetMirror.of(running))
        assertEquals(listOf(Status("s-1", Playback.Playing), Status("s-1", Playback.Replaced), tablets), other.statuses)
        // The tablet's play stopped on the TV, nothing after it: told once nothing has shown for a moment.
        sheet.value = SheetNow(PlayState.Idle, session("s-2", tablet))
        runCurrent()
        assertEquals(Playback.Starting, owner.statuses.last().state)
        advanceTimeBy(StatusPump.STOP_GRACE_MS + 1)
        runCurrent()
        assertEquals(Status("s-2", Playback.Stopped), owner.statuses.last())
        assertEquals(Status("s-2", Playback.Stopped, mine = true, title = "Harbor Lights", subtitle = "S02E04 · Low Water"), other.statuses.last())
    }

    /** A movie handed over: the player leaves a moment before the new play's sheet shows anything. */
    @Test
    fun `a moment with nothing showing is no end of the session, and what shows next replaces it`() = runTest {
        val pump = pump()
        val owner = link(galaxy, pump)
        player.value = playing(session("s-1"))
        runCurrent()
        player.value = null
        runCurrent()
        advanceTimeBy(StatusPump.STOP_GRACE_MS / 2)
        sheet.value = SheetNow(running, session("s-2", tablet))
        runCurrent()
        advanceTimeBy(5_000)
        assertEquals(listOf(Playback.Playing, Playback.Replaced, Playback.Starting), owner.statuses.map { it.state })
        // And its own play back in that moment goes on as it was.
        player.value = playing(session("s-3"))
        sheet.value = SheetNow(PlayState.Idle, session("s-2", tablet))
        runCurrent()
        advanceTimeBy(1_000)
        player.value = null
        runCurrent()
        advanceTimeBy(StatusPump.STOP_GRACE_MS / 2)
        player.value = playing(session("s-3"), positionMs = 1_000)
        advanceTimeBy(5_000)
        assertTrue(owner.statuses.none { it.session == "s-3" && it.state == Playback.Stopped }, "${owner.statuses.map { it.session to it.state }}")
    }

    @Test
    fun `the TV's own play taking a session's place replaces it`() = runTest {
        val pump = pump()
        val owner = link(galaxy, pump)
        player.value = playing(session("s-1"))
        runCurrent()
        sheet.value = SheetNow(running, null)
        player.value = null
        runCurrent()
        advanceTimeBy(1_000)
        assertEquals(listOf(Playback.Playing, Playback.Replaced), owner.statuses.map { it.state })
    }

    @Test
    fun `between the launch and its player the session is buffering, not stopped`() = runTest {
        val pump = pump()
        val owner = link(galaxy, pump)
        sheet.value = SheetNow(running, session("s-1"))
        runCurrent()
        advanceTimeBy(1_000)
        launching.value = Launching(session("s-1"))
        sheet.value = SheetNow(PlayState.Idle, session("s-1"))
        runCurrent()
        assertEquals(Status("s-1", Playback.Buffering, mine = true, title = "Harbor Lights", subtitle = "S02E04 · Low Water"), owner.statuses.last())
        advanceTimeBy(1_000)
        player.value = playing(session("s-1"))
        launching.value = null
        runCurrent()
        assertEquals(listOf(Playback.Starting, Playback.Buffering, Playback.Playing), owner.statuses.map { it.state })
    }

    @Test
    fun `the session's next play says it continues`() = runTest {
        val pump = pump()
        val owner = link(galaxy, pump)
        player.value = playing(session("s-1"))
        runCurrent()
        advanceTimeBy(1_000)
        // Auto-advance: the player hands the next episode to the sheet, in the same session.
        sheet.value = SheetNow(running, session("s-1"))
        player.value = null
        runCurrent()
        assertEquals(
            Status("s-1", Playback.Starting, mine = true, continued = true, title = "Harbor Lights", subtitle = "S02E04 · Low Water", sheet = SheetMirror.of(running)),
            owner.statuses.last(),
        )
        assertTrue(owner.statuses.none { it.state == Playback.Replaced || it.state == Playback.Stopped })
    }

    @Test
    fun `a phone linking hears the play now at once, and its hello names the session, mine when its own`() = runTest {
        val pump = pump()
        player.value = playing(session("s-1"))
        runCurrent()
        val owner = link(galaxy, pump)
        val other = link(tablet, pump)
        assertEquals(Playback.Playing, owner.statuses.single().state)
        assertEquals(listOf(Status("s-1", Playback.Playing)), other.statuses)
        assertEquals(Hello.Session("s-1", mine = true), pump.session(galaxy))
        assertEquals(Hello.Session("s-1", mine = false), pump.session(tablet))
    }

    @Test
    fun `a link that closed hears nothing more`() = runTest {
        val pump = pump()
        val owner = link(galaxy, pump)
        pump.closed(owner)
        runCurrent()
        player.value = playing(session("s-1"))
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(emptyList(), owner.heard)
    }

    @Test
    fun `an event that comes as a paced status's turn comes is never lost`() = runTest {
        val pump = pump()
        link(galaxy, pump)
        player.value = playing(session("s-1"), positionMs = 0)
        runCurrent()
        // A change that waits for its turn: the pump waits for that moment, or what comes before it.
        player.value = playing(session("s-1"), positionMs = 1_000)
        runCurrent()
        advanceTimeBy(300)
        // At that very moment a phone links: handed to the pump just before its wait runs out.
        val late = Link(tablet) { testScheduler.currentTime }
        pump.linked(late)
        runCurrent()
        assertEquals(listOf(Status("s-1", Playback.Playing)), late.statuses, "the phone that linked hears the play")
    }
}
