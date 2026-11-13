package io.skjaere.usenet.app.play

import android.view.InputDevice
import android.view.KeyEvent
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SeekHoldTest {
    /**
     * A key's event as the player gets it: down or up, when, its repeat count, where from, and which key; [seeks]: whether
     * a down of it seeks where it lands ([RemoteKeys]): Left and Right do here, as with the controls hidden; any other key
     * (Up, OK) doesn't.
     */
    private data class Key(val down: Boolean, val at: Long, val repeat: Int = 0, val source: Int = HDMI, val code: Int = RIGHT, val seeks: Boolean = code == LEFT || code == RIGHT)

    /**
     * The player's side of a hold, as PlayerActivity steps it: each down taken by [SeekHold.repeatCount], a press that
     * seeks timing the hold (or a repeat that made its key the one held to seek, from its press), each down that seeks
     * moving the target as far as [SeekHold.stepMs] says, Left back; at the up of the key held to seek, the burst's wait
     * ([ScrubRate.settleMs]) and how long until the hold is surely over, the hold's words said then, or as the next down
     * comes unless it goes on with the hold ([SeekHold.goesOn]). The key held to seek follows [RemoteKeys.seekingKey]; a
     * repeat of another key is timed from the last press, as the player times it.
     */
    private class Player(private val durationMs: Long = HOURS_2) {
        private val hold = SeekHold()
        private var target = 0L
        /** The key held to seek, from its down to its up (PlayerActivity's seekingKey). */
        private var seeking: Int? = null
        /** When the words an up put off are due, while they wait. */
        private var wordsAt: Long? = null
        /** Each down's repeat count as the player takes it, by its time. */
        val repeats = linkedMapOf<Long, Int>()
        /** Each seeking down's target, from 0, by its time. */
        val targets = linkedMapOf<Long, Long>()
        /** How long each seeking down's key has been held, as the player times it for the speed cue, by its time. */
        val helds = linkedMapOf<Long, Long>()
        /** Each up's wait for the burst's seek. */
        val settles = mutableListOf<Long>()
        /** Each up's wait before the hold is surely over. */
        val overIn = mutableListOf<Long>()
        /** When a hold's words were said, how far and where to. */
        val said = mutableListOf<Long>()

        fun play(keys: List<Key>): Player {
            keys.forEach { k ->
                wordsAt?.takeIf { it <= k.at }?.let { at ->
                    said += at
                    wordsAt = null
                }
                if (!k.down) {
                    if (k.code == seeking) {
                        settles += ScrubRate.settleMs(hold.heldMs(k.at))
                        val over = hold.up(k.code, k.source, k.at)
                        overIn += over
                        if (over == 0L) said += k.at else wordsAt = k.at + over
                        seeking = null
                    }
                    return@forEach
                }
                val repeat = hold.repeatCount(k.code, k.repeat, k.source, k.at)
                repeats[k.at] = repeat
                if (repeat != k.repeat) seeking = k.code
                wordsAt?.let {
                    if (!hold.goesOn) said += k.at
                    wordsAt = null
                }
                val next = RemoteKeys.seekingKey(seeking, k.code, true, repeat, if (k.seeks) RemoteKeys.Action.Seek else RemoteKeys.Action.Pass)
                if (k.seeks && (repeat == 0 || next != seeking)) hold.pressed()
                seeking = next
                if (!k.seeks) return@forEach
                val held = if (repeat == 0) 0 else if (k.code == seeking) hold.heldMs(k.at) else hold.sincePressMs(k.at)
                helds[k.at] = held
                val step = hold.stepMs(held, durationMs)
                target += if (k.code == LEFT) -step else step
                targets[k.at] = target
            }
            wordsAt?.let { said += it }
            return this
        }
    }

    /** A TV's remote held over HDMI-CEC as many send it: a press and a release [pressMs] later, every [everyMs], from [fromMs] until [untilMs]. */
    private fun cec(untilMs: Long, fromMs: Long = 0, everyMs: Long = 250, pressMs: Long = 71, code: Int = RIGHT, source: Int = HDMI): List<Key> =
        (fromMs..untilMs step everyMs).flatMap { t -> listOf(Key(true, t, source = source, code = code), Key(false, t + pressMs, source = source, code = code)) }

    /** Presses over HDMI-CEC at [downs], each let go 71 ms later. */
    private fun cecAt(vararg downs: Long, code: Int = RIGHT): List<Key> = downs.flatMap { t -> listOf(Key(true, t, code = code), Key(false, t + 71, code = code)) }

    /** The Shield's own remote held until [untilMs]: its press, Android's repeats from 500 ms every 50 ms, and its up. */
    private fun shield(untilMs: Long): List<Key> =
        listOf(Key(true, 0, source = REMOTE)) + (500L..untilMs step 50).mapIndexed { n, t -> Key(true, t, repeat = n + 1, source = REMOTE) } + Key(false, untilMs + 20, source = REMOTE)

    /**
     * A run of one key's presses over HDMI-CEC as a TV's remote sent them: its name, the key, each press's down and up in
     * ms from its first ([times], in pairs), and the press it is a hold from ([holdAt]), null for taps.
     */
    private class Run(val name: String, val key: String, val code: Int, val holdAt: Int?, vararg times: Long) {
        val presses: List<List<Long>> = times.toList().chunked(2)

        /** Its presses and releases from [source], replayed as [code] from [fromMs] on, the timing being what counts. */
        fun keys(source: Int = HDMI, fromMs: Long = 0): List<Key> =
            presses.flatMap { (down, up) -> listOf(Key(true, fromMs + down, source = source, code = code), Key(false, fromMs + up, source = source, code = code)) }

        override fun toString() = "the $name run of $key"
    }

    /**
     * Every run of four presses or more of one key in the Shield's HDMI-CEC message history (`dumpsys hdmi_control`) from
     * a TV's remote (CEC-2): held Up and Down in menus, Right taps (most likely), and Right and Left held in the player
     * after the install of the first fix. Up and Down are replayed as Right. The first repeat comes about 500 ms after the
     * press, the beat after it jitters between 166 and 380 ms (mostly 250), a press is mostly 71 ms long, once 30.
     */
    private val cecRuns = listOf(
            Run("Up hold", "Up", RIGHT, holdAt = 3,
                0, 71, 356, 427, 541, 611, 783, 854, 1042, 1113),
            Run("Down hold", "Down", RIGHT, holdAt = 4,
                0, 71, 401, 472, 640, 711, 880, 951, 1100, 1171, 1340, 1413, 1604, 1675, 1821, 1892, 2201, 2272, 2561, 2632),
            Run("Right taps", "Right", RIGHT, holdAt = null,
                0, 71, 399, 470, 799, 870, 1139, 1210, 1538, 1609),
            Run("first Right hold", "Right", RIGHT, holdAt = 4,
                0, 71, 501, 573, 752, 823, 955, 1026, 1205, 1276, 1457, 1528, 1729, 1759, 1909, 1980, 2205, 2275, 2412, 2483, 2688, 2759, 2917, 2988,
                3169, 3240, 3420, 3491, 3688, 3759, 3924, 3995, 4176, 4247, 4427, 4498, 4629, 4700, 4881, 4952, 5132, 5203, 5383, 5454, 5636, 5707,
                5887, 5958, 6140, 6211, 6348, 6419, 6593, 6664, 6844, 6915, 7097, 7168, 7348, 7419, 7600, 7671, 7851, 7922, 8053, 8127, 8304, 8375,
                8556, 8627, 8759, 8830, 9013, 9084, 9260, 9331, 9466, 9537, 9713, 9784, 9965, 10036, 10217, 10288, 10468, 10539, 10721, 10792, 10971,
                11042, 11223, 11294, 11476, 11547, 11833, 11904, 11999, 12070, 12187, 12258, 12432, 12503, 12689, 12760, 12935, 13006, 13138, 13209,
                13388, 13459, 13640, 13711, 13890, 13961, 14093, 14164, 14296, 14368, 14546, 14617, 14798, 14869, 15050, 15121, 15301, 15372, 15503,
                15574, 15755, 15827, 16005, 16076, 16258, 16328, 16508, 16579, 16760, 16831, 17012, 17084, 17263, 17334, 17720, 17792, 18039, 18110,
                18280, 18351, 18620, 18691, 18859, 18930, 19221, 19292, 19561, 19632, 19859, 19930, 20279, 20350, 20691, 20762, 21160, 21230, 21411,
                21482, 21692, 21763, 21915, 21986, 22169, 22240, 22419, 22490, 22671, 22742, 22923, 22994, 23175, 23245, 23380, 23451, 23628, 23699,
                23879, 23950, 24130, 24201, 24382, 24454, 24584, 24655, 24836, 24907, 25089, 25161, 25340, 25411, 25594, 25665, 25951, 26022, 26117,
                26188, 26345, 26416, 26554, 26625, 26799, 26870, 27050, 27121, 27301, 27372, 27553, 27624, 27806, 27877, 28058, 28129, 28309, 28380,
                28560, 28631, 28811, 28882),
            Run("second Right hold", "Right", RIGHT, holdAt = 4,
                0, 75, 501, 572, 858, 929, 1024, 1095, 1257, 1328, 1508, 1579, 1761, 1831, 2011, 2082, 2263, 2334, 2517, 2588, 2766, 2838, 3018, 3089,
                3270, 3341, 3521, 3593, 3773, 3844, 4025, 4096, 4278, 4349, 4530, 4601, 4780, 4851, 5031, 5102, 5282, 5353, 5535, 5605, 5790, 5861,
                6037, 6108, 6290, 6360, 6543, 6613, 6796, 6863),
            Run("Left hold", "Left", LEFT, holdAt = 4,
                0, 71, 500, 571, 752, 823, 1004, 1076, 1206, 1277, 1457, 1528, 1708, 1780, 1960, 2031, 2165, 2236, 2417, 2484, 2665, 2736),
    )

    /**
     * A TV's remote over HDMI-CEC, run by run: each of its holds is a hold from its third or fourth press (three presses
     * within [ScrubRate.CEC_HOLD_WITHIN_MS]), and from 2 s in goes faster than its first 2 s, its speed cue showing. Until
     * it is one, each press goes 10 s as a tap does; from it, as far as the curve says for the time since the run's first
     * press, never less far than its taps went, but 10 s at most where it came [ScrubRate.CEC_NUDGE_MS] or more after the
     * up before (five of them, none a hold's last). The taps 400 ms apart are never one. TalkBack hears each run once, a
     * moment after its last up.
     */
    @Test
    fun `a TV remote over HDMI-CEC, its holds speeding up from their third or fourth press, its taps 10 s each`() {
        val played = cecRuns.associateWith { run -> Player().play(run.keys()) }
        played.filterKeys { it.holdAt != null }.forEach { (run, p) ->
            run.presses.map { it.first() }.filter { it >= 2_000 }.forEach { t ->
                assertNotEquals(ScrubRate.Speed.Fine, ScrubRate.speed(p.helds.getValue(t), HOURS_2), "$run: still at its first 2 s' speed at its press $t ms in, held ${p.helds.getValue(t)} ms as the player times it")
            }
        }
        played.forEach { (run, p) ->
            val downs = run.presses.map { it.first() }
            assertEquals(run.holdAt, downs.indexOfFirst { p.repeats.getValue(it) > 0 }.takeIf { it >= 0 }?.plus(1), "$run: the press it is a hold from")
            var went = 0L
            downs.forEachIndexed { n, t ->
                val curve = maxOf(went, ScrubRate.offsetMs(t, HOURS_2))
                went = when {
                    run.holdAt == null || n + 1 < run.holdAt -> went + 10_000
                    n + 1 > run.holdAt && t - run.presses[n - 1].last() >= ScrubRate.CEC_NUDGE_MS -> minOf(curve, went + 10_000)
                    else -> curve
                }
                assertEquals(if (run.code == LEFT) -went else went, p.targets.getValue(t), "$run: its press ${n + 1}, $t ms in")
            }
            assertEquals(listOf(run.presses.last().last() + ScrubRate.CEC_LIFT_MS), p.said, "$run: when TalkBack hears it")
        }
    }

    /**
     * CSK-1: taps over HDMI-CEC, then the key held within the lift of the last: one run, but the hold is timed from its
     * own first press, the one its first repeat came half a second after, and goes on from the taps before it, not from
     * the first tap as though held since. The TV's recorded taps, or 25 taps 400 ms apart, then its recorded hold
     * from 329 ms after their last up: each press goes 10 s until the hold's fourth (1,024 ms into it), then as far as
     * the taps before it and the curve for the time since its first press say, its speed cue none for its first 2 s.
     */
    @Test
    fun `taps then a hold in one run over HDMI-CEC, the hold timed from its own first press and on from the taps`() {
        val hold = cecRuns.single { it.name == "second Right hold" }
        val cecTaps = cecRuns.single { it.name == "Right taps" }.keys()
        listOf(cecTaps, cec(untilMs = 9_600, everyMs = 400)).forEach { taps ->
            val tapped = taps.count { it.down }
            val from = taps.last().at + 329
            val p = Player(MINUTES_45).play(taps + hold.keys(fromMs = from))
            val downs = p.targets.keys.toList()
            var went = 0L
            downs.forEachIndexed { n, t ->
                val held = t - from
                went = if (held < 1_024) went + 10_000 else maxOf(went, 10_000L * tapped + ScrubRate.offsetMs(held, MINUTES_45))
                assertEquals(went, p.targets.getValue(t), "after $tapped taps, press ${n + 1}, $held ms into the hold")
                if (held >= 1_024) {
                    assertEquals(held, p.helds.getValue(t), "after $tapped taps, how long held at $held ms into the hold")
                    assertEquals(held < 2_000, ScrubRate.speed(p.helds.getValue(t), MINUTES_45) == ScrubRate.Speed.Fine, "after $tapped taps, the speed cue at $held ms into the hold")
                }
            }
        }
    }

    /**
     * CSK-2: the held key pressed again over HDMI-CEC [ScrubRate.CEC_NUDGE_MS] or more after a hold's up, within the lift,
     * may be the key let go and pressed at once to nudge the target on: it goes on with the hold, but 10 s at most, as a
     * tap does, and the hold's next press on its beat catches up with the curve. Right tapped 350 ms after the last up of
     * the TV's recorded hold (27 presses, at the top speed by then): 10 s on, not as far as the curve says for the time
     * since the hold's first press. Sooner after the up, the hold's next repeat: the curve.
     */
    @Test
    fun `the held key pressed again late in the lift after a hold's up over HDMI-CEC goes 10 s at most`() {
        val hold = cecRuns.single { it.name == "second Right hold" }
        val lastUp = hold.presses.last().last()
        val went = Player(MINUTES_45).play(hold.keys()).targets.values.last()
        val nudge = lastUp + 350
        val nudged = Player(MINUTES_45).play(hold.keys() + cecAt(nudge))
        assertTrue(nudged.repeats.getValue(nudge) > 0, "goes on with the hold")
        assertEquals(went + 10_000, nudged.targets.getValue(nudge), "pressed again 350 ms after the hold's last up")
        val held = Player(MINUTES_45).play(hold.keys() + cecAt(nudge, nudge + 250))
        assertEquals(ScrubRate.offsetMs(nudge + 250, MINUTES_45), held.targets.getValue(nudge + 250), "on the beat after it")
        val soon = lastUp + ScrubRate.CEC_NUDGE_MS - 1
        assertEquals(ScrubRate.offsetMs(soon, MINUTES_45), Player(MINUTES_45).play(hold.keys() + cecAt(soon)).targets.getValue(soon), "${soon - lastUp} ms after the up")
        assertEquals(went + 10_000, Player(MINUTES_45).play(hold.keys() + cecAt(lastUp + ScrubRate.CEC_NUDGE_MS)).targets.getValue(lastUp + ScrubRate.CEC_NUDGE_MS))
    }

    /**
     * The lift: over the longest gap in a TV's runs over HDMI-CEC from an up to the next press, the one before its
     * first repeat (430 ms), and under the burst's wait ([SeekBurst.WINDOW_MS]), so a hold's seek never goes out
     * between its presses.
     */
    @Test
    fun `the lift is over a CEC remote's longest gap within a run and under the burst's wait`() {
        val longest = cecRuns.maxOf { run -> run.presses.zipWithNext().maxOf { (a, b) -> b.first() - a.last() } }
        assertEquals(430, longest)
        assertTrue(ScrubRate.CEC_LIFT_MS > longest, "a CEC remote's first repeat would start a run of its own")
        assertTrue(ScrubRate.CEC_LIFT_MS < SeekBurst.WINDOW_MS, "the hold's seek would go out between its presses")
    }

    @Test
    fun `a TV's remote held over HDMI-CEC, a press and a release four times a second, goes on the curve as the Shield's held remote does`() {
        val cec = Player().play(cec(untilMs = 5_000))
        val shield = Player().play(shield(untilMs = 5_000))
        cec.targets.forEach { (t, target) -> assertEquals(ScrubRate.offsetMs(t, HOURS_2), target, "at $t ms") }
        cec.targets.filterKeys { it in shield.targets }.forEach { (t, target) -> assertEquals(shield.targets.getValue(t), target, "the Shield's at $t ms") }
    }

    /**
     * Three presses within [ScrubRate.CEC_HOLD_WITHIN_MS] make a hold from the third, timed from the first. Where taps that
     * quick went further than the curve by then, the hold goes no less far, and on once the curve passes them.
     */
    @Test
    fun `a run over HDMI-CEC is a hold from its third press within CEC_HOLD_WITHIN_MS, never going back on its taps`() {
        val hold = Player().play(cecAt(0, 280, 560, 840))
        assertEquals(listOf(0, 0, 2, 3), hold.repeats.values.toList())
        assertEquals(listOf(10_000L, 20_000L, ScrubRate.offsetMs(560, HOURS_2), ScrubRate.offsetMs(840, HOURS_2)), hold.targets.values.toList())
        val within = ScrubRate.CEC_HOLD_WITHIN_MS
        assertTrue(Player().play(cecAt(0, within / 2, within)).repeats.getValue(within) > 0, "three in $within ms")
        assertEquals(0, Player().play(cecAt(0, within / 2, within + 1)).repeats.getValue(within + 1), "three in ${within + 1} ms")
        // Taps 100 ms apart went 20 s by the third, the curve 18 s by then: the hold stays there until the curve passes it.
        val quick = Player().play(cecAt(0, 100, 200, 300, 400, 500))
        assertEquals(listOf(10_000L, 20_000L, 20_000L, ScrubRate.offsetMs(300, HOURS_2), ScrubRate.offsetMs(400, HOURS_2), ScrubRate.offsetMs(500, HOURS_2)), quick.targets.values.toList())
    }

    /** The burst's seek waits a second after a fast hold's last up, as after the Shield's; the hold's words wait to be sure it is over. */
    @Test
    fun `each up of a hold over HDMI-CEC waits to be sure it is over, and the seek waits after the last as after the Shield's up`() {
        val cec = Player().play(cec(untilMs = 3_000))
        val shield = Player().play(shield(untilMs = 3_000))
        assertEquals(ScrubRate.settleMs(3_071), cec.settles.last())
        assertEquals(1_000, cec.settles.last())
        assertEquals(1_000, shield.settles.single())
        assertTrue(cec.overIn.all { it == ScrubRate.CEC_LIFT_MS }, "${cec.overIn}")
        assertEquals(listOf(0L), shield.overIn)
    }

    /**
     * A hold over HDMI-CEC goes on while its key is pressed again within the lift of its up, whatever the beat or the
     * press's length, one [ScrubRate.CEC_NUDGE_MS] or more after the up 10 s at most; a longer gap is the key let go: the
     * press after it is one of its own, and a hold after it is timed from its own first press.
     */
    @Test
    fun `a press past the lift after a hold's up is one of its own, and a hold after it is timed from its own first press`() {
        val lift = ScrubRate.CEC_LIFT_MS
        val hold = cec(untilMs = 2_000)
        val held = ScrubRate.offsetMs(2_000, HOURS_2)
        // Pressed again the lift after the up: the hold's, 10 s on; 180 ms after its up, held 30 ms, and 150 ms after that,
        // the hold's beat again: the curve.
        val again = 2_071 + lift
        val within = Player().play(hold + cecAt(again) + listOf(Key(true, again + 251), Key(false, again + 281)) + cecAt(again + 431))
        assertTrue(within.repeats.getValue(again) > 0, "the lift after the up")
        assertEquals(held + 10_000, within.targets.getValue(again))
        assertEquals(ScrubRate.offsetMs(again + 251, HOURS_2), within.targets.getValue(again + 251))
        assertEquals(ScrubRate.offsetMs(again + 431, HOURS_2), within.targets.getValue(again + 431))
        val after = Player().play(hold + cec(fromMs = 2_072 + lift, untilMs = 3_072 + lift))
        assertEquals(0, after.repeats.getValue(2_072 + lift))
        assertEquals(held + 10_000, after.targets.getValue(2_072 + lift))
        assertEquals(held + ScrubRate.offsetMs(1_000, HOURS_2), after.targets.getValue(3_072 + lift))
    }

    @Test
    fun `a tap, then another after a pause, goes 10 s each, and a hold after a pause is timed from its own press`() {
        val taps = Player().play(listOf(Key(true, 0), Key(false, 71), Key(true, 700), Key(false, 771), Key(true, 1_500), Key(false, 1_571)))
        assertEquals(listOf(10_000L, 20_000L, 30_000L), taps.targets.values.toList())
        val holds = Player().play(cec(untilMs = 1_000) + cec(fromMs = 2_000, untilMs = 3_000))
        assertEquals(ScrubRate.offsetMs(1_000, HOURS_2), holds.targets.getValue(1_000))
        assertEquals(ScrubRate.offsetMs(1_000, HOURS_2) + ScrubRate.offsetMs(1_000, HOURS_2), holds.targets.getValue(3_000))
    }

    /**
     * Taps over HDMI-CEC come as a TV's held key does, a press and a release, each soon after the last: while no three come
     * within [ScrubRate.CEC_HOLD_WITHIN_MS] they go 10 s each however long they go on, and the seek half a second after the
     * last. So do a TV's repeats slower than that. Two quick ones go 20 s, as two quick Rights always have. TalkBack hears
     * them once, a moment after the last up, as it does a hold.
     */
    @Test
    fun `taps over HDMI-CEC, never three within CEC_HOLD_WITHIN_MS, go 10 s each however long they go on`() {
        val steady = Player().play(cec(untilMs = 12_000, everyMs = 400))
        steady.targets.values.forEachIndexed { n, target -> assertEquals(10_000L * (n + 1), target, "tap ${n + 1}, 400 ms apart") }
        assertEquals(listOf(12_071 + ScrubRate.CEC_LIFT_MS), steady.said)
        val slowTv = Player().play(cec(untilMs = 4_500, everyMs = 450))
        slowTv.targets.values.forEachIndexed { n, target -> assertEquals(10_000L * (n + 1), target, "press ${n + 1}, 450 ms apart") }
        val downs = listOf(300L, 380, 270, 350, 290, 400, 280, 360, 310, 390, 260).runningFold(0L, Long::plus)
        val taps = Player().play(downs.flatMap { t -> listOf(Key(true, t), Key(false, t + 71)) })
        taps.targets.values.forEachIndexed { n, target -> assertEquals(10_000L * (n + 1), target, "tap ${n + 1}, of ${downs.size} in ${downs.last()} ms") }
        assertEquals(SeekBurst.WINDOW_MS, taps.settles.last())
        val two = Player().play(listOf(Key(true, 0), Key(false, 71), Key(true, 200), Key(false, 271)))
        assertEquals(listOf(10_000L, 20_000L), two.targets.values.toList())
    }

    /**
     * Left after a hold of Right, in the burst's wait after its last up: a press of its own, 10 s back, though within
     * Right's lift; Left's own taps 10 s each, and Left held, a hold of its own timed from its first press. Right again
     * in Left's lift is a press of its own too.
     */
    @Test
    fun `the other key's presses after a hold go 10 s each, and held, are a hold of their own`() {
        val hold = cec(untilMs = 3_000)
        val left = listOf(Key(true, 3_200, code = LEFT), Key(false, 3_271, code = LEFT), Key(true, 3_700, code = LEFT), Key(false, 3_771, code = LEFT))
        val p = Player().play(hold + left)
        val held = p.targets.getValue(3_000)
        assertEquals(ScrubRate.offsetMs(3_000, HOURS_2), held)
        assertEquals(0, p.repeats.getValue(3_200))
        assertEquals(held - 10_000, p.targets.getValue(3_200))
        assertEquals(held - 20_000, p.targets.getValue(3_700))
        val heldLeft = Player().play(hold + cec(fromMs = 3_200, untilMs = 5_200, code = LEFT))
        assertEquals(held - 20_000, heldLeft.targets.getValue(3_450))
        assertEquals(held - ScrubRate.offsetMs(2_000, HOURS_2), heldLeft.targets.getValue(5_200))
        val back = Player().play(hold + listOf(Key(true, 3_200, code = LEFT), Key(false, 3_271, code = LEFT), Key(true, 3_400)))
        assertEquals(0, back.repeats.getValue(3_400))
        assertEquals(held, back.targets.getValue(3_400))
    }

    /**
     * A key that doesn't seek (Up bringing the controls), pressed in the lift after a hold over HDMI-CEC, ends it: Right
     * again soon after, on the hold's beat, is a press of its own.
     */
    @Test
    fun `a key that doesn't seek, pressed in the lift, ends the hold, and Right again on its beat is a press of its own`() {
        val hold = cec(untilMs = 2_000)
        val held = Player().play(hold).targets.getValue(2_000)
        val p = Player().play(hold + listOf(Key(true, 2_100, code = UP), Key(false, 2_171, code = UP), Key(true, 2_250)))
        assertEquals(0, p.repeats.getValue(2_250))
        assertEquals(held + 10_000, p.targets.getValue(2_250))
    }

    /** Infrared and Bluetooth remotes, and a keyboard, as before: each press 10 s, however soon after the last, a TV's timing over HDMI-CEC too. */
    @Test
    fun `presses from anywhere but HDMI-CEC are presses, however soon after an up`() {
        listOf(REMOTE, InputDevice.SOURCE_KEYBOARD, InputDevice.SOURCE_DPAD, InputDevice.SOURCE_GAMEPAD).forEach { source ->
            val p = Player().play(cec(untilMs = 5_000, source = source))
            p.targets.values.forEachIndexed { n, target -> assertEquals(10_000L * (n + 1), target, "source $source, press ${n + 1}") }
            assertTrue(p.overIn.all { it == 0L }, "source $source: ${p.overIn}")
            cecRuns.forEach { run ->
                val replayed = Player().play(run.keys(source))
                assertTrue(replayed.repeats.values.all { it == 0 }, "$run from source $source")
                assertEquals(10_000L * run.presses.size, replayed.targets.values.last().let { if (run.code == LEFT) -it else it }, "$run from source $source")
            }
        }
    }

    /**
     * #321: a key whose press didn't seek but whose repeats do (the focus came to the time bar as it was held, or the skip
     * button that had it went): its repeats go as far as the curve says for the time since its press, from nothing, not
     * from as far as the hold before went, whichever way that was, after a long hold or a tap. So too over HDMI-CEC from
     * a TV that sends a held key as repeats without a release (NXT-1), each with its own time as its downTime (CST-1).
     * Its up ends the hold as any held key's does (CSK-3): the burst's wait for the time since its press, and TalkBack
     * hears where it went, over HDMI-CEC once the lift has passed.
     */
    @Test
    fun `a key whose press didn't seek seeks from nothing as its repeats do, not from as far as the hold before went`() {
        val tap = listOf(Key(true, 0, source = REMOTE), Key(false, 71, source = REMOTE))
        val holds = listOf(shield(untilMs = 3_000) to ScrubRate.offsetMs(3_000, HOURS_2), cec(untilMs = 3_000) to ScrubRate.offsetMs(3_000, HOURS_2), tap to 10_000L)
        listOf(REMOTE, HDMI).forEach { source ->
            holds.forEach { (before, went) ->
                listOf(RIGHT, LEFT).forEach { code ->
                    val repeats = (1..3).map { n -> Key(true, 10_450 + 50L * n, repeat = n, source = source, code = code) }
                    val p = Player().play(before + Key(true, 10_000, source = source, code = code, seeks = false) + repeats + Key(false, 10_700, source = source, code = code))
                    val key = if (code == LEFT) "Left" else "Right"
                    repeats.forEach { k ->
                        val on = ScrubRate.offsetMs(k.at - 10_000, HOURS_2)
                        assertEquals(went + if (code == LEFT) -on else on, p.targets.getValue(k.at), "source $source, after a hold that went $went ms, $key's repeat ${k.repeat}, ${k.at - 10_000} ms after its press")
                    }
                    val alone = Player().play(before)
                    assertEquals(alone.settles + ScrubRate.settleMs(700), p.settles, "source $source, after a hold that went $went ms, the burst's wait after $key's up")
                    assertEquals(alone.said + (10_700 + if (source == HDMI) ScrubRate.CEC_LIFT_MS else 0), p.said, "source $source, after a hold that went $went ms, when TalkBack hears $key's")
                }
            }
        }
    }

    /**
     * NXT-1: a TV that sends its press again without a release gives its repeats their own downTime; still timed from the
     * press. Android lets such a key go when no press comes for 550 ms (AOSP's FOLLOWER_SAFETY_TIMEOUT): pressed again
     * within the lift, it goes on with the hold.
     */
    @Test
    fun `a TV's repeats over HDMI-CEC without a release are timed from the press`() {
        val p = Player().play(listOf(Key(true, 0)) + (1..10).map { n -> Key(true, n * 300L, repeat = n) } + Key(false, 3_050) + Key(true, 3_200))
        p.targets.forEach { (t, target) -> assertEquals(ScrubRate.offsetMs(t, HOURS_2), target, "at $t ms") }
        assertEquals(1_000, p.settles.single())
    }

    private companion object {
        const val HDMI = InputDevice.SOURCE_HDMI
        /** The Shield's remote: a keyboard with a D-pad, over Bluetooth. */
        const val REMOTE = InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_DPAD
        const val RIGHT = KeyEvent.KEYCODE_DPAD_RIGHT
        const val LEFT = KeyEvent.KEYCODE_DPAD_LEFT
        const val UP = KeyEvent.KEYCODE_DPAD_UP
        const val HOURS_2 = 7_200_000L
        const val MINUTES_45 = 2_700_000L
    }
}
