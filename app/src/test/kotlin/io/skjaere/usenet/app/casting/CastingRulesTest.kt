package io.skjaere.usenet.app.casting

import io.skjaere.usenet.app.casting.CastingRules.Act
import io.skjaere.usenet.app.casting.CastServiceRules.Why
import io.skjaere.usenet.serve.ServeCounters
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A cast load's rules (L60, L68, L88, L102, L103), over fakes for the device's events, the server's counters and the
 * clock: how the load goes to the device, when it plays or how it failed, and how the play ends there once it played. The
 * device and the other sender are invented.
 */
class CastingRulesTest {
    private val name = "Kitchen display"
    private val ours = "load-ours"
    private val theirs = "another-senders-media"
    private val mkv = CastFile("mkv", video = true)
    private val timings = CastTimings()

    /** The film is 52 minutes long, as the probe read it. */
    private val length = 3_120_000L

    private val none = ServeCounters()
    private val fetched = ServeCounters(requests = 2, partials = 2, firstRequestAt = 1)

    private fun rules(taken: Boolean = false, file: CastFile = mkv, probed: Long? = length) =
        CastingRules(name, ours, file, deviceHasStatus = taken, loadedAt = 0, probedDurationMs = probed, timings = timings)

    private fun status(state: CastPlayerState, at: Long, position: Long = 0, idle: CastIdleReason? = null, content: String? = ours) =
        CastEvent.Status(state, idle, content, position, at)

    private fun List<Act>.outcome(): CastLoadOutcome? = filterIsInstance<Act.Outcome>().singleOrNull()?.outcome

    private fun List<Act>.failure(): CastLoadFailure = (outcome() as? CastLoadOutcome.Failed)?.failure ?: throw AssertionError("no failure in $this")

    private fun List<Act>.ended(): CastEnd = filterIsInstance<Act.Ended>().singleOrNull()?.end ?: throw AssertionError("no end in $this")

    /** The device plays the load from [at], at [position]. */
    private fun CastingRules.playing(at: Long, position: Long = 0): List<Act> = heard(status(CastPlayerState.Playing, at, position), fetched, at)

    @Test
    fun `thirty seconds with no request from the device is can't reach`() {
        val rules = rules()
        assertEquals(null, rules.tick(none, 29_999).outcome())
        assertEquals(CastLoadOutcome.Failed(CastLoadFailure(CastFailKind.CannotReach)), rules.tick(none, 30_000).outcome())
        // A request came: the device reaches this phone, however long it takes after.
        val asked = rules()
        assertEquals(null, asked.tick(fetched, 10_000).outcome())
        assertEquals(null, asked.tick(fetched, 60_000).outcome())
    }

    /** L88: an error status of ours before it played waits for its code, which comes in a message of its own. */
    @Test
    fun `an error status before it played waits a moment for its code`() {
        val rules = rules()
        assertEquals(emptyList(), rules.heard(status(CastPlayerState.Idle, 2_000, idle = CastIdleReason.Error), fetched, 2_000))
        assertEquals(emptyList(), rules.tick(fetched, 2_400))
        assertEquals(
            CastLoadFailure(CastFailKind.CouldNotPlay, "The device's player stopped with a decoding error", format = true),
            rules.heard(CastEvent.MediaError(102), fetched, 2_500).failure(),
        )
    }

    /** L102: no code within the moment: the error is read as a media session that vanished, from the server and the file. */
    @Test
    fun `an error status with no code is read as vanished once its moment is over`() {
        fun erred(served: ServeCounters, file: CastFile = mkv) = rules(file = file).apply {
            heard(status(CastPlayerState.Idle, 2_000, idle = CastIdleReason.Error), served, 2_000)
            assertEquals(emptyList(), tick(served, 2_999), "within the moment")
        }
        assertEquals(CastLoadFailure(CastFailKind.CannotReach), erred(none).tick(none, 3_000).failure())
        assertEquals(
            CastLoadFailure(CastFailKind.CouldNotPlay, "Couldn't read the release from your providers", providers = true),
            erred(fetched.copy(upstreamFailures = 1)).tick(fetched.copy(upstreamFailures = 1), 3_000).failure(),
        )
        val mov = CastFile("mov", video = true)
        assertEquals(CastLoadFailure(CastFailKind.FileType, "MOV file. Kitchen display refused it as it opened."), erred(fetched, mov).tick(fetched, 3_000).failure())
    }

    /** While an error status of ours waits for its code, a status with no media is that error's, not a vanish of its own. */
    @Test
    fun `no media while an error waits for its code says nothing until the moment is over`() {
        val rules = rules()
        rules.heard(status(CastPlayerState.Idle, 2_000, idle = CastIdleReason.Error), fetched, 2_000)
        assertEquals(emptyList(), rules.heard(status(CastPlayerState.Idle, 2_100, content = null), fetched, 2_100))
        assertEquals(emptyList(), rules.tick(fetched, 2_999))
        assertEquals(CastLoadFailure(CastFailKind.CouldNotPlay, "The device's player closed the file without an error"), rules.tick(fetched, 3_000).failure())
    }

    @Test
    fun `a 104 after 206s is can't open, and with no request can't reach`() {
        assertEquals(
            CastLoadFailure(CastFailKind.CouldNotPlay, "The device's player can't open this file", format = true),
            rules().heard(CastEvent.MediaError(104), fetched, 2_000).failure(),
        )
        assertEquals(CastLoadFailure(CastFailKind.CannotReach), rules().heard(CastEvent.MediaError(104), none, 2_000).failure())
    }

    @Test
    fun `the device playing it is the load's outcome, once`() {
        val rules = rules()
        assertEquals(null, rules.heard(status(CastPlayerState.Buffering, 900), fetched, 900).outcome())
        assertEquals(CastLoadOutcome.Playing, rules.playing(1_500).outcome())
        assertEquals(null, rules.playing(2_500, 1_000).outcome())
    }

    @Test
    fun `FINISHED within 2 minutes of the end is watched, earlier is a stop where it was`() {
        val near = rules()
        near.playing(0)
        near.tick(fetched, 1_000, positionMs = length - 90_000, durationMs = length)
        assertTrue(Act.End(Why.Finished) in near.heard(status(CastPlayerState.Idle, 2_000, idle = CastIdleReason.Finished), fetched, 2_000))
        assertEquals(CastEnd(CastEnd.How.Watched, length), near.over(Why.Finished, fetched, 2_000).ended())
        assertEquals("Watched on Kitchen display", CastEnd(CastEnd.How.Watched, length).said(name))

        // A 120-minute film whose FINISHED came 10 minutes early: a stop at 110 minutes, said as one.
        val film = 7_200_000L
        val early = rules(probed = film)
        early.playing(0)
        early.tick(fetched, 1_000, positionMs = 6_600_000, durationMs = film)
        val stop = early.over(Why.Finished, fetched, 2_000).ended()
        assertEquals(CastEnd(CastEnd.How.Stopped, 6_600_000), stop)
        assertEquals("Stopped on Kitchen display at 1:50:00", stop.said(name))
    }

    /** Another sender's load replaced this one's there (W32 the other way round): it ends, nothing of theirs stopped. */
    @Test
    fun `INTERRUPTED ends the play where it was`() {
        val rules = rules()
        rules.playing(0)
        rules.tick(fetched, 1_000, positionMs = 1_930_000, durationMs = length)
        val acts = rules.heard(status(CastPlayerState.Idle, 2_000, idle = CastIdleReason.Interrupted), fetched, 2_000)
        assertEquals(listOf(Act.End(Why.Interrupted)), acts)
        assertEquals(CastEnd(CastEnd.How.Stopped, 1_930_000), rules.over(Why.Interrupted, fetched, 2_000).ended())
        // Before it played: the load fails, saying so.
        assertEquals(
            CastLoadFailure(CastFailKind.CouldNotPlay, "Something else started playing on Kitchen display"),
            rules().heard(status(CastPlayerState.Idle, 2_000, idle = CastIdleReason.Interrupted), fetched, 2_000).failure(),
        )
    }

    /** The session's end ends the play: its address revoked and the service told, as any end of the play is (L55, L85). */
    @Test
    fun `the session's end ends the play, which revokes the token and tells the service`() {
        val rules = rules()
        rules.playing(0)
        rules.tick(fetched, 1_000, positionMs = 1_930_000, durationMs = length)
        assertEquals(listOf(Act.End(Why.SessionEnded)), rules.heard(CastEvent.Ended, fetched, 5_000))
        val end = rules.over(Why.SessionEnded, fetched, 5_000).ended()
        assertEquals(CastEnd(CastEnd.How.Stopped, 1_930_000), end)
        assertEquals("Stopped on Kitchen display at 32:10", end.said(name))
        // Over once: nothing after.
        assertEquals(emptyList(), rules.heard(CastEvent.Ended, fetched, 6_000))
        assertEquals(emptyList(), rules.tick(fetched, 7_000))
    }

    /** L68: autoplay only where play() would start nothing of another sender's (Terms 3.4.8). */
    @Test
    fun `the load goes with autoplay on a device with no media, and play is sent at our first status on one taken over`() {
        val idle = rules(taken = false)
        assertEquals(listOf(LoadStep.PlayWhenReady, LoadStep.SetMediaItem), idle.steps)
        assertEquals(emptyList(), idle.heard(status(CastPlayerState.Buffering, 500), fetched, 500))

        val taken = rules(taken = true)
        assertEquals(listOf(LoadStep.SetMediaItem), taken.steps)
        // The other sender's media, paused: never played from here.
        assertEquals(emptyList(), taken.heard(status(CastPlayerState.Paused, 100, content = theirs), none, 100))
        assertEquals(emptyList(), taken.heard(status(CastPlayerState.Idle, 200, idle = CastIdleReason.Interrupted, content = theirs), none, 200))
        // Ours, loaded paused as theirs was: played once, at its first status.
        assertEquals(listOf(Act.Play), taken.heard(status(CastPlayerState.Paused, 300), fetched, 300))
        assertEquals(emptyList(), taken.heard(status(CastPlayerState.Paused, 400), fetched, 400))
        // Ours, playing at once: nothing more to send.
        val playingAtOnce = rules(taken = true)
        assertEquals(listOf(Act.Outcome(CastLoadOutcome.Playing)), playingAtOnce.playing(300))
    }

    /** L60: the Shield ended a paused session 20 min 02 s into the pause, with no IDLE first. */
    @Test
    fun `a session that ends after a long pause saves where it was and says the device closed`() {
        val rules = rules()
        rules.playing(0, 1_920_000)
        rules.tick(fetched, 10_000, positionMs = 1_930_000, durationMs = length)
        rules.heard(status(CastPlayerState.Paused, 10_500, 1_930_000), fetched, 10_500)
        val closedAt = 10_500 + 20 * 60_000 + 2_000L
        assertEquals(listOf(Act.End(Why.SessionEnded)), rules.heard(CastEvent.Ended, fetched, closedAt))
        val end = rules.over(Why.SessionEnded, fetched, closedAt).ended()
        assertEquals(CastEnd(CastEnd.How.LongPause, 1_930_000), end)
        assertEquals("Kitchen display closed after a long pause. Stopped at 32:10.", end.said(name))

        // Paused under 19 minutes: a plain stop.
        val short = rules()
        short.playing(0, 1_920_000)
        short.tick(fetched, 10_000, positionMs = 1_930_000, durationMs = length)
        short.heard(status(CastPlayerState.Paused, 10_500, 1_930_000), fetched, 10_500)
        assertEquals(CastEnd(CastEnd.How.Stopped, 1_930_000), short.over(Why.SessionEnded, fetched, 10_500 + 18 * 60_000L).ended())
    }

    /** L102: the LOAD taken, then the device's media gone with no idle reason and no code. */
    @Test
    fun `a media session that vanished is read from the server and the file`() {
        fun vanished(served: ServeCounters, file: CastFile = mkv): CastLoadFailure {
            val rules = rules(file = file)
            assertEquals(emptyList(), rules.heard(status(CastPlayerState.Loading, 100), served, 100))
            return rules.heard(status(CastPlayerState.Idle, 200, content = null), served, 200).failure()
        }
        assertEquals(CastLoadFailure(CastFailKind.CannotReach), vanished(none))
        assertEquals(
            CastLoadFailure(CastFailKind.CouldNotPlay, "Couldn't read the release from your providers", providers = true),
            vanished(fetched.copy(upstreamFailures = 1)),
        )
        assertEquals(CastLoadFailure(CastFailKind.FileType, "MOV file. Kitchen display refused it as it opened."), vanished(fetched, CastFile("mov", video = true)))
        assertEquals(CastLoadFailure(CastFailKind.FileType, "Audio-only file. Kitchen display refused it as it opened."), vanished(fetched, CastFile("mkv", video = false)))
        assertEquals(CastLoadFailure(CastFailKind.CouldNotPlay, "The device's player closed the file without an error"), vanished(fetched))
    }

    /** W32, L68: before the device has our load, what it says is its state before it, never a vanish. */
    @Test
    fun `another sender's status or no media before our load showed is no vanish, while no media after the first request is`() {
        val before = rules(taken = true)
        assertEquals(emptyList(), before.heard(status(CastPlayerState.Playing, 100, content = theirs), none, 100))
        assertEquals(emptyList(), before.heard(status(CastPlayerState.Idle, 200, content = null), none, 200))
        // Our LOAD interrupting theirs, heard after the device's first request: theirs ending, not ours.
        assertEquals(emptyList(), before.heard(status(CastPlayerState.Idle, 300, idle = CastIdleReason.Interrupted, content = theirs), fetched, 300))

        val after = rules()
        assertEquals(
            CastLoadFailure(CastFailKind.CouldNotPlay, "The device's player closed the file without an error"),
            after.heard(status(CastPlayerState.Idle, 300, content = null), fetched, 300).failure(),
        )
    }

    /** L60: before it played, the session's end fails the load; nothing is saved, so the resume point stays. */
    @Test
    fun `a session that ends before the device played fails the load and saves nothing`() {
        // A Resume at 32:10: the device asked for the head, the tail and the middle, then closed.
        val resumed = rules()
        resumed.heard(status(CastPlayerState.Buffering, 1_000, 1_930_000), fetched, 1_000)
        val acts = resumed.heard(CastEvent.Ended, fetched, 5_000)
        assertEquals(CastLoadFailure(CastFailKind.CouldNotPlay, "Kitchen display closed before it played", sessionEnded = true), acts.failure())
        assertTrue(Act.End(Why.SessionEnded) in acts)
        assertEquals(emptyList(), resumed.over(Why.SessionEnded, fetched, 5_000))

        // A device taken over that never asked: can't reach, and the device is no longer chosen.
        val taken = rules(taken = true)
        taken.heard(status(CastPlayerState.Paused, 100, content = theirs), none, 100)
        assertEquals(CastLoadFailure(CastFailKind.CannotReach, sessionEnded = true), taken.heard(CastEvent.Ended, none, 4_000).failure())

        // Told of the end by the play's own end first: the same.
        val told = rules()
        assertEquals(CastLoadFailure(CastFailKind.CouldNotPlay, "Kitchen display closed before it played", sessionEnded = true), told.over(Why.SessionEnded, fetched, 4_000).failure())
        assertEquals(emptyList(), told.heard(CastEvent.Ended, fetched, 4_000))
    }

    /** L103: a file whose audio ended before its video sat at its last frame, PLAYING, never FINISHED. */
    @Test
    fun `stuck in PLAYING within 3 s of the end for 10 s is the end, the device's media stopped, watched`() {
        val rules = rules()
        rules.playing(0, length - 30_000)
        rules.tick(fetched, 1_000, positionMs = length - 29_000, durationMs = length)
        rules.heard(status(CastPlayerState.Playing, 28_000, length - 2_000), fetched, 28_000)
        rules.heard(status(CastPlayerState.Playing, 32_000, length - 2_000), fetched, 32_000)
        assertTrue(Act.End(Why.Finished, stopDevice = true) !in rules.tick(fetched, 37_999))
        assertTrue(Act.End(Why.Finished, stopDevice = true) in rules.tick(fetched, 38_000))
        assertEquals(CastEnd(CastEnd.How.Watched, length), rules.over(Why.Finished, fetched, 38_100).ended())
    }

    @Test
    fun `still for 9 s, or 4 s from the end, is no stuck end`() {
        val nine = rules()
        nine.playing(0, length - 30_000)
        nine.heard(status(CastPlayerState.Playing, 28_000, length - 2_000), fetched, 28_000)
        assertTrue(nine.tick(fetched, 37_000).none { it is Act.End })
        // It moved on: the stillness starts again.
        nine.heard(status(CastPlayerState.Playing, 37_500, length - 1_000), fetched, 37_500)
        assertTrue(nine.tick(fetched, 39_000).none { it is Act.End })

        val far = rules()
        far.playing(0, length - 30_000)
        far.heard(status(CastPlayerState.Playing, 28_000, length - 4_000), fetched, 28_000)
        assertTrue(far.tick(fetched, 60_000).none { it is Act.End })
    }

    /** The receiver's own position, never Media3's, which moves on by itself while the status says PLAYING. */
    @Test
    fun `an extrapolated position that moves while the reported one stands still is stuck`() {
        val rules = rules()
        rules.playing(0, length - 30_000)
        rules.heard(status(CastPlayerState.Playing, 28_000, length - 2_500), fetched, 28_000)
        var stuck = false
        for (second in 29..38) {
            stuck = rules.tick(fetched, second * 1_000L, positionMs = length - 2_500 + (second - 28) * 1_000L, durationMs = length).any { it is Act.End }
        }
        assertTrue(stuck)
    }

    /**
     * L103: the receiver says where it is only now and then, never while it plays on steadily, so the play is heard of
     * at its start alone. As it should be near the end (its last word moved on, as Media3's poll is), the device is asked
     * every 2 s, and its answers standing still there for 10 s are the end.
     */
    @Test
    fun `a play heard of only at its start is asked near its end, and its answers standing still are the end`() {
        val rules = rules()
        rules.playing(0, 0)
        assertTrue(rules.tick(fetched, 600_000, positionMs = 600_000, durationMs = length).none { it == Act.RequestStatus }, "steady, mid-film")
        assertTrue(Act.RequestStatus in rules.tick(fetched, length - 2_000, positionMs = length - 2_000, durationMs = length), "asked as the end nears")
        // Its answers: PLAYING at its last frame, standing still.
        val stuck = Act.End(Why.Finished, stopDevice = true)
        rules.heard(status(CastPlayerState.Playing, length - 1_900, length - 500), fetched, length - 1_900)
        var t = length - 1_000
        while (t < length + 8_100) {
            val acts = rules.tick(fetched, t, positionMs = t, durationMs = length)
            assertTrue(stuck !in acts, "still for less than 10 s at ${t - (length - 1_900)}")
            rules.heard(status(CastPlayerState.Playing, t + 100, length - 500), fetched, t + 100)
            t += 1_000
        }
        assertTrue(stuck in rules.tick(fetched, length + 8_100, positionMs = length + 8_100, durationMs = length), "10 s still at its end")
    }

    /** One that plays on to its end is asked as it nears it, and its answers moving on are no stuck end. */
    @Test
    fun `a play that moves on near its end is asked, and isn't stuck`() {
        val rules = rules()
        rules.playing(0, 0)
        var t = length - 3_000
        while (t < length) {
            val acts = rules.tick(fetched, t, positionMs = t, durationMs = length)
            assertTrue(acts.none { it is Act.End }, "at $t")
            rules.heard(status(CastPlayerState.Playing, t + 100, t + 100), fetched, t + 100)
            t += 1_000
        }
        assertEquals(CastEnd(CastEnd.How.Watched, length), rules.heard(status(CastPlayerState.Idle, length + 200, idle = CastIdleReason.Finished), fetched, length + 200).let { rules.over(Why.Finished, fetched, length + 300).ended() })
    }

    @Test
    fun `the status is asked every 2 s while the end is near, never before`() {
        val rules = rules()
        rules.playing(0, length - 30_000)
        assertEquals(emptyList(), rules.tick(fetched, 1_000, positionMs = length - 29_000, durationMs = length))
        rules.heard(status(CastPlayerState.Playing, 27_500, length - 2_500), fetched, 27_500)
        val asked = (28..34).filter { second -> Act.RequestStatus in rules.tick(fetched, second * 1_000L) }
        assertEquals(listOf(28, 30, 32, 34), asked)
    }

    /**
     * L103, L104: inside a served gap's window (5 s each side of its place by the file's share) the device is asked for its
     * status every 2 s too, by where the play should be, so the gap watch hears its answers standing still, and on past the
     * window as far as the gap watch's seeks can send it (2 s, then 10 s more), where it hears it at its own targets; not
     * before the window, not past that, and not for a gap not served.
     */
    @Test
    fun `the status is asked every 2 s inside a served gap's window too, by where the play should be`() {
        // A thousand bytes a millisecond: the gap 33:20 in, its window from 33:15 to 33:26, the watch's seeks to 33:38 at most.
        val size = length * 1_000
        val gap = 2_000_000_000L..2_000_999_999L
        val rules = CastingRules(name, ours, mkv, deviceHasStatus = false, loadedAt = 0, probedDurationMs = length, size = size, timings = timings)
        rules.playing(0, 1_900_000)
        assertTrue(Act.RequestStatus !in rules.tick(fetched, 94_000, positionMs = 1_994_000, durationMs = length, gaps = listOf(gap)), "before its window")
        val asked = (95..108).filter { second -> Act.RequestStatus in rules.tick(fetched, second * 1_000L, gaps = listOf(gap)) }
        assertEquals((95..107 step 2).toList(), asked)
        // Its answers PLAYING at a frame inside the window, standing still: asked on, every 2 s.
        val still = (109..129).filter { second ->
            if (second % 2 == 1) rules.heard(status(CastPlayerState.Playing, second * 1_000L, 2_000_300), fetched, second * 1_000L)
            Act.RequestStatus in rules.tick(fetched, second * 1_000L + 100, gaps = listOf(gap))
        }
        assertEquals((109..129 step 2).toList(), still)
        // Sent by the gap watch past the window, 33:33 (its second seek), its answers standing still there: asked on too.
        val sent = CastingRules(name, ours, mkv, deviceHasStatus = false, loadedAt = 0, probedDurationMs = length, size = size, timings = timings)
        sent.playing(0, 1_900_000)
        val atTarget = (200..220).filter { second ->
            if (second % 2 == 0) sent.heard(status(CastPlayerState.Playing, second * 1_000L, 2_013_000), fetched, second * 1_000L)
            Act.RequestStatus in sent.tick(fetched, second * 1_000L + 100, gaps = listOf(gap))
        }
        assertEquals((200..220 step 2).toList(), atTarget)
        // Standing past where the watch's seeks can send it: not asked.
        val past = CastingRules(name, ours, mkv, deviceHasStatus = false, loadedAt = 0, probedDurationMs = length, size = size, timings = timings)
        past.playing(0, 1_900_000)
        assertTrue(
            (200..220).none { second ->
                if (second % 2 == 0) past.heard(status(CastPlayerState.Playing, second * 1_000L, 2_019_000), fetched, second * 1_000L)
                Act.RequestStatus in past.tick(fetched, second * 1_000L + 100, gaps = listOf(gap))
            },
        )
        // No gap served: never asked mid-film.
        val none = CastingRules(name, ours, mkv, deviceHasStatus = false, loadedAt = 0, probedDurationMs = length, size = size, timings = timings)
        none.playing(0, 1_900_000)
        assertTrue((95..108).none { second -> Act.RequestStatus in none.tick(fetched, second * 1_000L) })
    }

    /**
     * L103, L104: a device the gap watch sent near the furthest it goes, standing PLAYING still there, says where it is only
     * when asked, and its word moved on between two asks reads past the watch's reach though it never moved. Asked by its
     * own word, it is asked on every 2 s, so the watch hears it standing; one that plays on answers further on each time,
     * and once an answer is past the reach it isn't asked.
     */
    @Test
    fun `a device standing still at the gap watch's furthest target is asked on by its own word, one playing on not past it`() {
        // A thousand bytes a millisecond: the gap 33:20 in, the watch's seeks reaching 33:38 at most.
        val size = length * 1_000
        val gap = 2_000_000_000L..2_000_999_999L
        // At 33:37.5, sent there by the watch's second seek: half a second short of its reach.
        assertEquals((200L..230L step 2).toList(), askedOfADeviceThatAnswersWhenAsked(size, gap, 200_000) { 2_017_500 })
        // From 33:30, playing on: its answer at 308.4 s is at 33:38.4.
        assertEquals((300L..308L step 2).toList(), askedOfADeviceThatAnswersWhenAsked(size, gap, 300_000) { t -> 2_010_000 + (t - 300_000) })
    }

    /**
     * A device that says it plays at [where] at [from] (the gap watch's seek), and after that only when asked, 300 ms after
     * each ask, at [where] it is then; Media3's poll moving on from its last word, as it does while the device says
     * PLAYING. The seconds in which it was asked, from [from] for 30 s.
     */
    private fun askedOfADeviceThatAnswersWhenAsked(size: Long, gap: LongRange, from: Long, where: (Long) -> Long): List<Long> {
        val rules = CastingRules(name, ours, mkv, deviceHasStatus = false, loadedAt = 0, probedDurationMs = length, size = size, timings = timings)
        rules.playing(0, 1_900_000)
        var heardAt = from
        var at = where(from)
        rules.heard(status(CastPlayerState.Playing, heardAt, at), fetched, heardAt)
        val asked = mutableListOf<Long>()
        for (second in from / 1_000..from / 1_000 + 30) {
            val now = second * 1_000 + 100
            if (Act.RequestStatus in rules.tick(fetched, now, positionMs = at + (now - heardAt), durationMs = length, gaps = listOf(gap))) {
                asked += second
                heardAt = now + 300
                at = where(heardAt)
                rules.heard(status(CastPlayerState.Playing, heardAt, at), fetched, heardAt)
            }
        }
        return asked
    }

    /** L88: after it played, an error ends the play there ("Playback stopped on Kitchen display"), saved where it was. */
    @Test
    fun `an error after it played ends the play, the release kept unless the providers failed it`() {
        val rules = rules()
        rules.playing(0)
        rules.tick(fetched, 1_000, positionMs = 600_000, durationMs = length)
        assertEquals(listOf(Act.End(Why.Failed)), rules.heard(CastEvent.MediaError(102), fetched, 2_000))
        assertEquals(CastEnd(CastEnd.How.Failed, 600_000), rules.over(Why.Failed, fetched, 2_000).ended())

        val providers = rules()
        providers.playing(0)
        providers.tick(fetched, 1_000, positionMs = 600_000, durationMs = length)
        val failed = fetched.copy(upstreamFailures = 1)
        assertEquals(listOf(Act.End(Why.Failed)), providers.heard(status(CastPlayerState.Idle, 2_000, idle = CastIdleReason.Error), failed, 2_000))
        assertEquals(CastEnd(CastEnd.How.Failed, 600_000, providers = true), providers.over(Why.Failed, failed, 2_000).ended())
    }

    /** L84: the next play's load took its place there: where it was is saved, and nothing is said. */
    @Test
    fun `replaced by the next play's load, it is saved where it was`() {
        val rules = rules()
        rules.playing(0)
        rules.tick(fetched, 1_000, positionMs = 900_000, durationMs = length)
        val end = rules.over(null, fetched, 2_000).ended()
        assertEquals(CastEnd(CastEnd.How.Replaced, 900_000), end)
        assertEquals(null, end.said(name))
    }

    /** A position heard before a timeline cleared at the end stays the last one, as does the length (L61). */
    @Test
    fun `the last position and length known are kept as the device's timeline clears`() {
        val rules = rules(probed = null)
        rules.playing(0)
        rules.tick(fetched, 1_000, positionMs = length - 60_000, durationMs = length)
        rules.tick(fetched, 2_000, positionMs = null, durationMs = null)
        assertEquals(CastEnd(CastEnd.How.Watched, length), rules.over(Why.Finished, fetched, 2_500).ended())
    }
}
