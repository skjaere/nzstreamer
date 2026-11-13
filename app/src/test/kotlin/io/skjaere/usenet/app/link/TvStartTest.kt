package io.skjaere.usenet.app.link

import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.PipelineStep
import io.skjaere.usenet.core.StepStatus
import io.skjaere.usenet.data.TvSession
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.LinkMessage.Ack
import io.skjaere.usenet.link.LinkMessage.Play
import io.skjaere.usenet.link.LinkMessage.Rejected
import io.skjaere.usenet.link.LinkMessage.Status
import io.skjaere.usenet.link.Playback
import io.skjaere.usenet.link.Rejection
import io.skjaere.usenet.link.SheetKind
import io.skjaere.usenet.link.SheetStep
import io.skjaere.usenet.link.StepState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L37, item 24: a play started on a TV, as the phone's own play sheet shows it, fed by the TV. Play goes with a number of
 * its own; the TV's Ack names its session, which this phone keeps (L33); the TV's sheet is mirrored in the TV's words,
 * then Buffering until its picture starts, when the sheet closes. A start that didn't start offers Close alone (W13);
 * each refusal has its words (L41); Cancel asks the TV to stop, and says so when it doesn't answer in 5 s; 20 s without
 * word, the TV's link down, reads lost, and the TV heard again goes on. A clock of the test's own; the titles invented.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TvStartTest {
    private val living = KeyId.of(Random.nextBytes(91))
    private val bedroom = KeyId.of(Random.nextBytes(91))

    private val episode = Play.Episode(showTmdbId = 4_101, showName = "Harbor Lights", season = 2, episode = 4, episodeName = "Low Water")
    private val ask = TvAsk(episode = episode, from = Play.From(1_930_000, watched = false, ageMs = 60_000))

    private inner class Fixture(private val test: TestScope, private val up: Boolean = true, private val debridOnly: Set<KeyId> = emptySet()) {
        val sent = mutableListOf<LinkMessage>()
        private var seq = 0
        val heard = MutableSharedFlow<TvHeard>(extraBufferCapacity = 64)
        val linked = MutableStateFlow(true)
        val holds = mutableListOf<String>()
        val sessions = mutableListOf<TvSession>()
        val here = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var dismissed = 0
        val took = mutableListOf<String>()
        val start = TvStart(
            test.backgroundScope,
            TvStartDeps(
                command = { _, build -> if (!up) null else (++seq).also { sent += build(it) } },
                heard = heard,
                linked = { linked },
                hold = { key ->
                    holds += "hold ${key.short}"
                    AutoCloseable { holds += "let go ${key.short}" }
                },
                name = { if (it == living) "Living room TV" else "Bedroom TV" },
                fromService = { it in debridOnly },
                keep = { _, session -> sessions += session },
                startingHere = here,
                dismissHere = { dismissed++ },
                now = { NOW },
                clock = { test.testScheduler.currentTime },
                timings = { PlayOnTimings() },
                took = { now, session -> took += "${now.tvName}: ${now.title} in $session" },
            ),
        )

        val now: TvStartNow? get() = start.now.value
        val phase: TvPhase? get() = now?.phase

        fun play(what: TvAsk = ask, on: KeyId = living) {
            start.start(on, "Harbor Lights", "S02E04 · Low Water") { what }
            test.runCurrent()
        }

        /** The rows the sheet lists now ([mirroredRows]), as TvPlaySheet draws them. */
        val rows: List<PipelineStep>
            get() = now!!.let { now -> mirroredRows(now.sheet?.takeIf { it.kind == SheetKind.Running }?.let(::mirroredSteps).orEmpty(), now.rows) }

        suspend fun tv(message: LinkMessage, from: KeyId = living) {
            heard.emit(TvHeard(from, message))
            test.runCurrent()
        }

        fun starting(sheet: Status.Sheet, session: String = "s-1") = Status(session, Playback.Starting, mine = true, title = "Harbor Lights", sheet = sheet)
    }

    private val searching = Status.Sheet(SheetKind.Searching, "Harbor Lights", "S02E04 · Low Water", line = "Searching 2 indexers for the episode and season packs · 4 found · 1 of 2 answered")
    private val running = Status.Sheet(
        SheetKind.Running, "Harbor Lights", "S02E04 · Low Water", line = "Searched 2 indexers by TVDB id · 6 found",
        steps = listOf(Status.Step(SheetStep.FetchNzb, StepState.Done, millis = 600, detail = "2.9 MB"), Status.Step(SheetStep.CheckAvailability, StepState.Running, done = 112, total = 256)),
        attempt = 1, of = 3, release = "Harbor.Lights.S02E04.Low.Water.2160p.WEB-DL-GRP", chips = listOf("2160p", "WEB-DL"), playsHere = true,
    )

    @Test
    fun `Play goes with a number of its own, the sheet waits for the TV, and its Ack keeps the session`() = runTest {
        val f = Fixture(this)
        f.play()
        assertEquals(listOf<LinkMessage>(Play(1, episode, from = ask.from)), f.sent)
        val now = assertNotNull(f.now)
        assertEquals(TvPhase.Asking, now.phase)
        assertEquals("Living room TV", now.tvName)
        assertEquals("Harbor Lights" to "S02E04 · Low Water", now.title to now.subtitle)
        assertTrue(now.expanded)
        assertEquals(listOf("hold ${living.short}"), f.holds, "its link held while it starts (L36)")
        assertEquals(1, f.dismissed, "a failure this phone's sheet still showed is put away")
        f.tv(Ack(1, "s-1"))
        assertEquals(TvPhase.Starting("s-1"), f.phase)
        assertEquals(listOf(TvSession("s-1", NOW, showTmdbId = 4_101)), f.sessions)
        assertEquals("Starting on Living room TV", startingOn("Living room TV"))
        assertEquals("Waiting for Living room TV…", waitingFor("Living room TV"))
    }

    @Test
    fun `the TV taking the start hands its session to the bar, once`() = runTest {
        val f = Fixture(this)
        f.play()
        assertEquals(emptyList(), f.took, "not before the TV names the session")
        f.tv(Ack(1, "s-1"))
        f.tv(Ack(1, "s-1"))
        assertEquals(listOf("Living room TV: Harbor Lights in s-1"), f.took)
    }

    @Test
    fun `a movie's session is kept by the movie`() = runTest {
        val f = Fixture(this)
        f.play(TvAsk(movie = Play.Movie(7_707, "North Quay", 2023)))
        f.tv(Ack(1, "s-2"))
        assertEquals(listOf(TvSession("s-2", NOW, movieTmdbId = 7_707)), f.sessions)
    }

    @Test
    fun `the TV's sheet is mirrored in the TV's words, then Buffering, and it closes as the picture starts`() = runTest {
        val f = Fixture(this)
        f.play()
        f.tv(Ack(1, "s-1"))
        f.tv(f.starting(searching))
        assertEquals("Searching the TV's 2 indexers for the episode and season packs · 4 found · 1 of 2 answered", f.now?.sheet?.line)
        f.tv(f.starting(running))
        val sheet = assertNotNull(f.now?.sheet)
        assertEquals("Searched the TV's 2 indexers by TVDB id · 6 found", sheet.line)
        assertEquals(running.release, sheet.release)
        assertEquals("Living room TV chose · release 1 of 3", tvChose("Living room TV", sheet.attempt, sheet.of))
        assertEquals("Living room TV chose", tvChose("Living room TV", 1, 1))
        assertEquals("✓ Plays on Living room TV", playsOn("Living room TV"))
        f.tv(Status("s-1", Playback.Buffering, mine = true, title = "Harbor Lights"))
        assertEquals(TvPhase.Buffering("s-1"), f.phase)
        assertEquals(running.release, f.now?.sheet?.release, "the TV's last sheet stays, Buffering after its steps")
        f.tv(Status("s-1", Playback.Playing, mine = true, title = "Harbor Lights", positionMs = 1_000))
        assertNull(f.now, "the sheet closes as the TV's picture starts")
        assertEquals("let go ${living.short}", f.holds.last())
    }

    @Test
    fun `the TV's steps as the sheet's rows, a failed one in the TV's words`() {
        val steps = mirroredSteps(
            running.copy(
                steps = listOf(
                    Status.Step(SheetStep.FetchNzb, StepState.Done, millis = 600, detail = "2.9 MB"),
                    Status.Step(SheetStep.CheckAvailability, StepState.Failed, millis = 1_800, failure = "Incomplete on the TV's providers · 41 of 256 checked articles missing"),
                    Status.Step(SheetStep.Open, StepState.Running, done = 3, total = 10),
                    Status.Step(SheetStep.Unknown, StepState.Done, millis = 1),
                ),
            ),
        )
        assertEquals(setOf(PipelineStep.FetchNzb, PipelineStep.CheckAvailability, PipelineStep.Open), steps.keys)
        assertEquals(StepStatus.Done(600, "2.9 MB"), steps[PipelineStep.FetchNzb])
        assertEquals(StepStatus.Running(3, 10), steps[PipelineStep.Open])
        val failed = assertIs<StepStatus.Failed>(steps[PipelineStep.CheckAvailability])
        assertEquals(1_800, failed.millis)
        val elsewhere = assertIs<AttemptFailure.Elsewhere>(failed.failure)
        assertEquals("Incomplete on the TV's providers · 41 of 256 checked articles missing", elsewhere.text)
    }

    /**
     * L203, L206: a TV's torrent start names its Preparing step on the wire, which reads as Preparing, and the sheet's rows
     * are the mirrored steps' own: a torrent's three, no Usenet row among them; a Usenet start's four, no Preparing; before
     * the TV names a step, Usenet's, as every TV before D1 sends. The mini bar names the step the TV is on.
     */
    @Test
    fun `a TV's Preparing reads Preparing, and the sheet's rows are the mirrored steps' own`() {
        // As a phone reads it off the wire.
        val status = assertIs<Status>(
            assertIs<io.skjaere.usenet.link.Decoded.Read>(
                io.skjaere.usenet.link.LinkCodec.decode(
                    ("""{"t":"status","session":"s-1","state":"starting","sheet":{"kind":"running","title":"Harbor Lights",""" +
                        """"steps":[{"step":"prepare","state":"done","millis":900},{"step":"open","state":"running"}]}}""").toByteArray(),
                ),
            ).value,
        )
        val torrent = assertNotNull(status.sheet)
        val steps = mirroredSteps(torrent)
        assertEquals(mapOf(PipelineStep.Prepare to StepStatus.Done(900), PipelineStep.Open to StepStatus.Running()), steps)
        assertEquals(listOf(PipelineStep.Prepare, PipelineStep.Open, PipelineStep.ProbeTracks), mirroredRows(steps))
        assertEquals(listOf(PipelineStep.FetchNzb, PipelineStep.CheckAvailability, PipelineStep.Open, PipelineStep.ProbeTracks), mirroredRows(mirroredSteps(running)))
        assertEquals(listOf(PipelineStep.FetchNzb, PipelineStep.CheckAvailability, PipelineStep.Open, PipelineStep.ProbeTracks), mirroredRows(emptyMap()))
        val preparing = torrent.copy(steps = torrent.steps.take(1).map { it.copy(state = StepState.Running, millis = null) })
        assertEquals("Preparing…", miniStep(TvStartNow(living, "Living room TV", "Harbor Lights", null, TvPhase.Starting("s-1"), preparing)))
    }

    /**
     * D2PhoneTvStarting (L223): while a TV searches, before a release names its steps, the phone lists the rows the TV's
     * own sheet lists, which the Searching sheet sends: a debrid-only TV's Preparing, Opening and the probe, not Usenet's
     * four. A TV before D2 sends none, and the phone lists Usenet's, as before; a row it doesn't know is left out. Once the
     * TV names a step, the steps say which rows.
     */
    @Test
    fun `a searching TV's rows are the ones its sheet sends, until it names a step`() {
        val service = listOf(PipelineStep.Prepare, PipelineStep.Open, PipelineStep.ProbeTracks)
        val usenet = listOf(PipelineStep.FetchNzb, PipelineStep.CheckAvailability, PipelineStep.Open, PipelineStep.ProbeTracks)
        assertEquals(service, mirroredRows(emptyMap(), listOf(SheetStep.Prepare, SheetStep.Open, SheetStep.ProbeTracks)))
        assertEquals(service, mirroredRows(emptyMap(), listOf(SheetStep.Prepare, SheetStep.Unknown, SheetStep.Open, SheetStep.ProbeTracks)))
        assertEquals(usenet, mirroredRows(emptyMap(), listOf(SheetStep.FetchNzb, SheetStep.CheckAvailability, SheetStep.Open, SheetStep.ProbeTracks)))
        assertEquals(usenet, mirroredRows(emptyMap(), emptyList()), "a TV before D2")
        assertEquals(usenet, mirroredRows(emptyMap(), listOf(SheetStep.Unknown)))
        // A step named decides, whatever rows came before.
        assertEquals(usenet, mirroredRows(mapOf(PipelineStep.FetchNzb to StepStatus.Running()), listOf(SheetStep.Prepare, SheetStep.Open, SheetStep.ProbeTracks)))
        assertEquals(service, mirroredRows(mapOf(PipelineStep.Prepare to StepStatus.Running()), emptyList()))
    }

    /**
     * D2PhoneTvStarting (CWD-1): before the TV says anything, a start on a TV whose hello said it plays from its debrid
     * service alone lists that service's rows, as the TV's own sheet does, never Usenet's; one on any other TV, Usenet's,
     * as before. Its Searching sheet's rows then take over.
     */
    @Test
    fun `before the TV says anything, a debrid-only TV's start lists its service's rows`() = runTest {
        val f = Fixture(this, debridOnly = setOf(living))
        f.play()
        assertEquals(TvPhase.Asking, f.phase)
        assertEquals(SERVICE_ROWS, f.rows, "waiting for the TV")
        f.tv(Ack(1, "s-1"))
        assertEquals(SERVICE_ROWS, f.rows, "acked, no sheet yet")
        f.tv(f.starting(searching.copy(rows = listOf(SheetStep.Prepare, SheetStep.Open, SheetStep.ProbeTracks))))
        assertEquals(SERVICE_ROWS, f.rows, "searching")
        // A Usenet TV, or one before D2: Usenet's, until its sheet says otherwise.
        f.play(on = bedroom)
        assertEquals(USENET_ROWS, f.rows)
    }

    /**
     * CWR-1, CWD-1: as each release starts, the TV's sheet names no step yet, and its Running sheet sends no rows (they
     * come on Searching sheets alone, L223): the rows its search sent are kept meanwhile, so a debrid-only TV's start never
     * flips to Usenet's four between its search and Preparing, nor before the next release's Preparing.
     */
    @Test
    fun `a running sheet that names no step yet keeps the rows the search sent`() = runTest {
        val f = Fixture(this, debridOnly = setOf(living))
        f.play()
        f.tv(Ack(1, "s-1"))
        f.tv(f.starting(searching.copy(rows = listOf(SheetStep.Prepare, SheetStep.Open, SheetStep.ProbeTracks))))
        val first = running.copy(release = "Harbor.Lights.S02E04.2160p.WEB.H265-KESTREL", steps = emptyList())
        f.tv(f.starting(first))
        assertEquals(1 to 3, f.now!!.sheet!!.attempt to f.now!!.sheet!!.of)
        assertEquals(SERVICE_ROWS, f.rows, "release 1 of 3, before Preparing")
        f.tv(f.starting(first.copy(steps = listOf(Status.Step(SheetStep.Prepare, StepState.Running)))))
        assertEquals(SERVICE_ROWS, f.rows)
        f.tv(f.starting(first.copy(steps = listOf(Status.Step(SheetStep.Prepare, StepState.Done, millis = 900), Status.Step(SheetStep.Open, StepState.Failed, millis = 400, failure = "No longer cached on the TV's debrid service")))))
        // The next release starts: no step named again.
        f.tv(f.starting(first.copy(attempt = 2, release = "Harbor.Lights.S02E04.1080p.WEB.H264-KESTREL", notice = "No longer cached on the TV's debrid service · trying release 2 of 3")))
        assertEquals(SERVICE_ROWS, f.rows, "release 2 of 3, before Preparing")
        // A Usenet TV's search sends Usenet's, and a TV before D2 none: its start lists Usenet's throughout.
        for (rows in listOf(listOf(SheetStep.FetchNzb, SheetStep.CheckAvailability, SheetStep.Open, SheetStep.ProbeTracks), emptyList())) {
            val g = Fixture(this)
            g.play()
            g.tv(Ack(1, "s-1"))
            g.tv(g.starting(searching.copy(rows = rows)))
            g.tv(g.starting(running.copy(steps = emptyList())))
            assertEquals(USENET_ROWS, g.rows)
        }
    }

    /**
     * #316 (L223): the mirror's not-tried line splits as the TV's own sheet does: those left to save the TV's indexer's
     * allowance, and apart from them those left as what they play from failed on the TV (L252's words).
     */
    @Test
    fun `releases not tried as what they play from failed on the TV are said so`() {
        assertEquals(listOf("2 more releases not tried, to save the TV's indexer's download allowance."), notTriedOnTv(2, noRoute = 0))
        assertEquals(listOf("2 more releases not tried: what they play from failed on the TV."), notTriedOnTv(2, noRoute = 2))
        assertEquals(
            listOf("1 more release not tried, to save the TV's indexer's download allowance.", "1 more release not tried: what it plays from failed on the TV."),
            notTriedOnTv(2, noRoute = 1),
        )
        assertEquals(emptyList(), notTriedOnTv(0, noRoute = 0))
    }

    @Test
    fun `a status before the Ack is kept for the session the Ack names`() = runTest {
        val f = Fixture(this)
        f.play()
        f.tv(f.starting(running))
        f.tv(f.starting(searching, session = "s-0"))
        assertEquals(TvPhase.Asking, f.phase)
        f.tv(Ack(1, "s-1"))
        assertEquals(running.release, f.now?.sheet?.release)
    }

    @Test
    fun `a season pack's question waits for the TV's remote`() = runTest {
        val f = Fixture(this)
        f.play()
        f.tv(Ack(1, "s-1"))
        f.tv(f.starting(Status.Sheet(SheetKind.Choosing, "Harbor Lights", "S02E04", lookingFor = "Looking for S02E04 'Low Water'", release = "Harbor.Lights.S02.1080p.BluRay-GRP")))
        assertEquals(SheetKind.Choosing, f.now?.sheet?.kind)
        assertEquals(TvPhase.Starting("s-1"), f.phase)
        assertEquals("Choose the file on the TV", CHOOSE_ON_TV)
        assertEquals("Living room TV shows its files: choose the one it is with the TV's remote.", choosingOn("Living room TV"))
    }

    @Test
    fun `a start that didn't start offers Close alone, and stays when the TV closes its own sheet`() = runTest {
        val f = Fixture(this)
        f.play()
        f.tv(Ack(1, "s-1"))
        for (kind in listOf(SheetKind.NoRelease, SheetKind.Failed, SheetKind.NoProvider)) {
            f.tv(f.starting(Status.Sheet(kind, "Harbor Lights", "S02E04")))
            assertTrue(assertNotNull(f.now).over, "$kind")
        }
        f.tv(Status("s-1", Playback.Stopped, mine = true))
        assertTrue(assertNotNull(f.now, "the TV closing its sheet leaves the phone's").over)
        f.start.close()
        runCurrent()
        assertNull(f.now)
        assertEquals("No playable release found on Living room TV", noReleaseOn("Living room TV"))
        assertEquals("Couldn't start on Living room TV. Each release tried failed:", eachFailedOn("Living room TV", 3))
        assertEquals("Couldn't start on Living room TV. The release failed:", eachFailedOn("Living room TV", 1))
    }

    @Test
    fun `a second Ack for the Play keeps the session once`() = runTest {
        val f = Fixture(this)
        f.play()
        f.tv(Ack(1, "s-1"))
        f.tv(Ack(1, "s-1"))
        assertEquals(listOf(TvSession("s-1", NOW, showTmdbId = 4_101)), f.sessions)
    }

    @Test
    fun `a failure the TV closed stays for this phone to close, its link let go`() = runTest {
        val f = Fixture(this)
        f.play()
        f.tv(Ack(1, "s-1"))
        f.tv(f.starting(Status.Sheet(SheetKind.NoRelease, "Harbor Lights", "S02E04")))
        assertEquals(listOf("hold ${living.short}"), f.holds)
        f.tv(Status("s-1", Playback.Stopped, mine = true))
        assertTrue(assertNotNull(f.now).over)
        assertEquals("let go ${living.short}", f.holds.last(), "nothing more to hear of it")
    }

    @Test
    fun `a start the TV stopped of itself closes`() = runTest {
        val f = Fixture(this)
        f.play()
        f.tv(Ack(1, "s-1"))
        f.tv(f.starting(running))
        f.tv(Status("s-1", Playback.Stopped, mine = true))
        assertNull(f.now)
    }

    @Test
    fun `each refusal in its words, with Close alone`() = runTest {
        val words = mapOf(
            Rejection.BusySetup to ("Living room TV is busy with setup. Try again when it's done." to null),
            Rejection.TooFast to ("Living room TV is busy. Try again in a moment." to null),
            Rejection.NotReadyIndexer to ("Couldn't start on Living room TV. It has no indexer set up yet." to null),
            Rejection.NotReadyProvider to ("Couldn't start on Living room TV. It has no Usenet provider set up yet." to "Set up Living room TV first: Settings › Usenet providers on the TV."),
            // D2PhoneTvStartFail: what the TV's debrid way lacks, naming no service.
            Rejection.NotReadyService to ("Couldn't start on Living room TV. It has no debrid service set up yet." to "Set up Living room TV first: Settings › Debrid services on the TV."),
            Rejection.NotReadyServiceOff to ("Couldn't start on Living room TV. Its debrid service is off." to "Switch it on in Settings › Debrid services on the TV."),
            Rejection.NotReadyTorrentIndexer to ("Couldn't start on Living room TV. It has no torrent indexer set up yet." to null),
            Rejection.Unsupported to ("Couldn't start on Living room TV." to null),
            Rejection.NotCurrent to ("Couldn't start on Living room TV." to null),
            Rejection.Unknown to ("Couldn't start on Living room TV." to null),
        )
        for ((reason, said) in words) {
            val f = Fixture(this)
            f.play()
            f.tv(Rejected(1, reason))
            assertEquals(TvPhase.Rejected(reason), f.phase)
            assertTrue(assertNotNull(f.now).over)
            assertEquals(said, rejectedWords(reason, "Living room TV"))
            assertEquals(emptyList(), f.sessions)
        }
    }

    @Test
    fun `Cancel asks the TV to stop its session, and closes once it says so`() = runTest {
        val f = Fixture(this)
        f.play()
        f.tv(Ack(1, "s-1"))
        f.tv(f.starting(running))
        f.start.cancel()
        runCurrent()
        assertEquals(LinkMessage.Cancel(2, "s-1"), f.sent.last())
        assertEquals(TvPhase.Cancelling("s-1"), f.phase)
        assertEquals("Stopping on Living room TV…", stoppingOn("Living room TV"))
        f.tv(Ack(2, "s-1"))
        assertNull(f.now)
    }

    @Test
    fun `Cancel unanswered for 5 s says it may still start there`() = runTest {
        val f = Fixture(this)
        f.play()
        f.tv(Ack(1, "s-1"))
        f.start.cancel()
        runCurrent()
        advanceTimeBy(4_999)
        assertEquals(TvPhase.Cancelling("s-1"), f.phase)
        advanceTimeBy(2)
        assertEquals(TvPhase.NoAnswer, f.phase)
        assertTrue(assertNotNull(f.now).over)
        assertEquals("Living room TV didn't answer. It may still start there.", didntAnswer("Living room TV"))
    }

    @Test
    fun `Cancel before the TV answered Play goes once it has`() = runTest {
        val f = Fixture(this)
        f.play()
        f.start.cancel()
        runCurrent()
        assertEquals(TvPhase.Cancelling(null), f.phase)
        assertEquals(1, f.sent.size, "no session to name yet")
        f.tv(Ack(1, "s-1"))
        assertEquals(LinkMessage.Cancel(2, "s-1"), f.sent.last())
        f.tv(Rejected(2, Rejection.NotCurrent))
        assertNull(f.now, "nothing left to stop")
    }

    @Test
    fun `20 s without word, the TV's link down, reads lost, and the TV heard again goes on`() = runTest {
        val f = Fixture(this)
        f.play()
        f.tv(Ack(1, "s-1"))
        f.tv(f.starting(running))
        advanceTimeBy(5_000)
        f.linked.value = false
        runCurrent()
        advanceTimeBy(14_999)
        assertEquals(TvPhase.Starting("s-1"), f.phase)
        advanceTimeBy(2)
        assertEquals(TvPhase.Lost("s-1", 20_000), f.phase)
        assertTrue(assertNotNull(f.now).over)
        assertEquals("Lost the connection to Living room TV", lostConnection("Living room TV"))
        assertEquals("Last heard from the TV 20 s ago.", lastHeard(20_000))
        // Reached again: the TV tells its play as the link comes up.
        f.linked.value = true
        f.tv(Status("s-1", Playback.Buffering, mine = true))
        assertEquals(TvPhase.Buffering("s-1"), f.phase)
    }

    /** The start lost: the TV took it, its sheet ran, then 20 s without word, its link down. */
    private suspend fun TestScope.lost(): Fixture {
        val f = Fixture(this)
        f.play()
        f.tv(Ack(1, "s-1"))
        f.tv(f.starting(running))
        f.linked.value = false
        runCurrent()
        advanceTimeBy(20_001)
        assertIs<TvPhase.Lost>(f.phase)
        return f
    }

    private fun hello(session: String?) = LinkMessage.Hello(1, 1, io.skjaere.usenet.link.Role.Tv, "Living room TV", session = session?.let { LinkMessage.Hello.Session(it, mine = true) })

    @Test
    fun `a lost start closes once the TV, reached again, says it stopped`() = runTest {
        val f = lost()
        f.linked.value = true
        f.tv(hello("s-1"))
        assertIs<TvPhase.Lost>(f.phase, "its hello names the start: it may still start there")
        f.tv(Status("s-1", Playback.Stopped, mine = true))
        assertNull(f.now, "over there: the sheet no longer says it may still start")
        assertEquals("let go ${living.short}", f.holds.last())
    }

    @Test
    fun `a lost start closes once the TV, reached again, names another play or none`() = runTest {
        val f = lost()
        f.linked.value = true
        f.tv(hello(null))
        assertNull(f.now)
        val g = lost()
        g.linked.value = true
        g.tv(hello("s-other"))
        assertNull(g.now)
    }

    @Test
    fun `an unanswered Cancel closes once the TV says it stopped, or answers it late`() = runTest {
        suspend fun unanswered(): Fixture {
            val f = Fixture(this)
            f.play()
            f.tv(Ack(1, "s-1"))
            f.start.cancel()
            runCurrent()
            advanceTimeBy(5_001)
            assertEquals(TvPhase.NoAnswer, f.phase)
            return f
        }
        val stopped = unanswered()
        stopped.tv(Status("s-1", Playback.Starting, mine = true, title = "Harbor Lights", sheet = running))
        assertEquals(TvPhase.NoAnswer, stopped.phase, "still starting there: it says so until the TV says more")
        stopped.tv(Status("s-1", Playback.Stopped, mine = true))
        assertNull(stopped.now)
        val late = unanswered()
        late.tv(Ack(2, "s-1"))
        assertNull(late.now)
    }

    @Test
    fun `a failure the TV's sheet showed stays though the TV, reached again, names no play`() = runTest {
        val f = Fixture(this)
        f.play()
        f.tv(Ack(1, "s-1"))
        f.tv(f.starting(Status.Sheet(SheetKind.NoRelease, "Harbor Lights", "S02E04 · Low Water", line = "Searched 2 indexers · 0 found")))
        assertTrue(assertNotNull(f.now).over)
        f.tv(hello(null))
        assertEquals(TvPhase.Starting("s-1"), f.phase, "for this phone to close")
    }

    @Test
    fun `a quiet TV whose link is up is still starting`() = runTest {
        val f = Fixture(this)
        f.play()
        f.tv(Ack(1, "s-1"))
        advanceTimeBy(120_000)
        assertEquals(TvPhase.Starting("s-1"), f.phase)
    }

    @Test
    fun `another session's or another TV's word is none of this sheet's, and another play replaces it`() = runTest {
        val f = Fixture(this)
        f.play()
        f.tv(Ack(1, "s-1"))
        f.tv(f.starting(running))
        f.tv(Status("s-9", Playback.Playing, mine = false))
        f.tv(Status("s-1", Playback.Playing, mine = true), from = bedroom)
        f.tv(Ack(1, "s-7"), from = bedroom)
        assertEquals(TvPhase.Starting("s-1"), f.phase)
        f.tv(Status("s-1", Playback.Replaced, mine = true))
        assertNull(f.now)
    }

    @Test
    fun `a play started here lets the TV's sheet go, and the TV plays on`() = runTest {
        val f = Fixture(this)
        f.play()
        f.tv(Ack(1, "s-1"))
        f.here.emit(Unit)
        runCurrent()
        assertNull(f.now)
        assertTrue(f.sent.none { it is LinkMessage.Cancel })
    }

    @Test
    fun `the sheet minimises to the mini bar and opens again`() = runTest {
        val f = Fixture(this)
        f.play()
        f.start.minimize()
        assertEquals(false, f.now?.expanded)
        f.start.expand()
        assertEquals(true, f.now?.expanded)
        assertEquals("On Living room TV · Checking availability…", onTvStep("Living room TV", "Checking availability…"))
        assertEquals("Couldn't play Harbor Lights on Living room TV", couldntPlayOn("Harbor Lights", "Living room TV"))
    }

    @Test
    fun `nothing goes while the TV's link isn't up, or what to ask can't be worked out`() = runTest {
        val down = Fixture(this, up = false)
        down.play()
        assertEquals(TvPhase.NotSent(null), down.phase)
        assertEquals("Couldn't start on Living room TV." to "Open NZStreamer on Living room TV, then try again.", notSentWords("Living room TV", null))
        val f = Fixture(this)
        f.start.start(living, "Harbor Lights", "S02E05") { throw TvAskFailed("TMDB doesn't list S02E05") }
        runCurrent()
        assertEquals(TvPhase.NotSent("TMDB doesn't list S02E05"), f.phase)
        assertTrue(f.sent.isEmpty())
        // Anything else's words aren't the app's: none are shown.
        val g = Fixture(this)
        g.start.start(living, "Harbor Lights", "S02E05") { throw IllegalStateException("java.io.IOException: tmdb.invalid") }
        runCurrent()
        assertEquals(TvPhase.NotSent(null), g.phase)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L

        /** A debrid service's rows, as a TV that plays from it lists them. */
        val SERVICE_ROWS = listOf(PipelineStep.Prepare, PipelineStep.Open, PipelineStep.ProbeTracks)

        /** Usenet's rows. */
        val USENET_ROWS = listOf(PipelineStep.FetchNzb, PipelineStep.CheckAvailability, PipelineStep.Open, PipelineStep.ProbeTracks)
    }
}
