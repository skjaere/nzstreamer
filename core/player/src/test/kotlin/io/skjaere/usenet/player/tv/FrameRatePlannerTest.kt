package io.skjaere.usenet.player.tv

import io.skjaere.usenet.player.MatchContentFrameRate
import io.skjaere.usenet.player.ModeSize
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [FrameRatePlanner]: whether a play's window asks the TV for a mode that shows the video's frame rate, and which, on
 * the Shield's own mode list (its dumpsys display: ids 16 and 22-41, the 2160p 59.94 Hz mode 41 running).
 * The setting on, a TV, Android 11 and a 45-minute episode unless a test says otherwise.
 */
class FrameRatePlannerTest {
    private val uhd = ModeSize(3840, 2160)
    private val fullHd = ModeSize(1920, 1080)
    private val shield = listOf(
        ScreenMode(16, ModeSize(1280, 720), 60.000004f),
        ScreenMode(22, uhd, 29.97003f),
        ScreenMode(23, uhd, 23.976025f),
        ScreenMode(24, fullHd, 29.97003f),
        ScreenMode(25, fullHd, 23.976025f),
        ScreenMode(26, ModeSize(1280, 720), 59.94006f),
        ScreenMode(27, fullHd, 59.94006f),
        ScreenMode(28, fullHd, 120.00001f),
        ScreenMode(29, uhd, 30.000002f),
        ScreenMode(30, uhd, 25f),
        ScreenMode(31, uhd, 24.000002f),
        ScreenMode(32, fullHd, 30.000002f),
        ScreenMode(33, fullHd, 25f),
        ScreenMode(34, fullHd, 24.000002f),
        ScreenMode(35, ModeSize(720, 480), 60.000004f),
        ScreenMode(36, ModeSize(1280, 720), 50f),
        ScreenMode(37, fullHd, 50f),
        ScreenMode(38, uhd, 50f),
        ScreenMode(39, fullHd, 60.000004f),
        ScreenMode(40, uhd, 60.000004f),
        ScreenMode(41, uhd, 59.94006f),
    )

    private fun mode(id: Int) = shield.single { it.id == id }

    private fun screen(
        active: ScreenMode? = mode(41),
        modes: List<ScreenMode> = shield,
        sdk: Int = 30,
        match: MatchContentFrameRate? = null,
        output: ModeSize? = active?.size,
        fromTv: Boolean = true,
    ) = ScreenModes(sdk, active, modes, match, output, fromTv)

    private fun plan(fps: Double?, screen: ScreenModes = screen(), durationMs: Long? = EPISODE_MS, on: Boolean = true, tv: Boolean = true) =
        FrameRatePlanner.plan(on, tv, fps, durationMs, screen)

    private fun switchesTo(id: Int) = FrameRatePlan.Hold(mode(id), wait = true)
    private fun keeps(id: Int) = FrameRatePlan.Hold(mode(id), wait = false)
    private fun leaves(plan: FrameRatePlan): LeaveReason = (plan as? FrameRatePlan.Leave)?.reason ?: error("not left: $plan")

    @Test
    fun `off, or not a TV, leaves the display as it is`() {
        assertEquals(FrameRatePlan.Leave(LeaveReason.Off, "frame rate matching is off"), plan(FILM, on = false))
        assertEquals(LeaveReason.Off, leaves(plan(FILM, on = false, tv = false)))
        assertEquals(FrameRatePlan.Leave(LeaveReason.NotTv, "not a TV"), plan(FILM, tv = false))
    }

    /** Matroska's DefaultDuration of 41,708,333 ns; 23.976 is 0.1 % from 24, which repeats a frame every 42 s. */
    @Test
    fun `a 23,976 film on the Shield's 59,94 takes its 23,976 Hz mode, never 24, in whatever order they are listed`() {
        assertEquals(switchesTo(23), plan(FILM))
        assertEquals(switchesTo(23), plan(FILM, screen(modes = shield.shuffled(java.util.Random(7)))))
        assertEquals(switchesTo(23), plan(FILM, screen(modes = listOf(mode(31), mode(34)) + shield.reversed())))
        // As an MP4 states it.
        assertEquals(switchesTo(23), plan(23.976))
    }

    @Test
    fun `24 fps takes the 24 Hz mode, never 23,976`() {
        assertEquals(switchesTo(31), plan(24.0))
        assertEquals(switchesTo(31), plan(24.0, screen(modes = listOf(mode(23)) + shield)))
    }

    @Test
    fun `a 23,976 film on a 60 Hz output with 23,976, 24 and 60 Hz modes takes 23,976`() {
        val modes = listOf(ScreenMode(1, uhd, 24f), ScreenMode(2, uhd, 60f), ScreenMode(3, uhd, 23.976f))
        assertEquals(FrameRatePlan.Hold(modes[2], wait = true), plan(FILM, screen(active = modes[1], modes = modes)))
    }

    /** The mapping: 25 to 50 Hz, as common TV players do; 25 Hz only where a TV lists no 50. */
    @Test
    fun `25 fps on 59,94 takes 50 Hz, and on 50 already fits`() {
        assertEquals(switchesTo(38), plan(25.0))
        assertEquals(keeps(38), plan(25.0, screen(active = mode(38))))
        val no50 = listOf(ScreenMode(1, uhd, 60f), ScreenMode(2, uhd, 25f))
        assertEquals(FrameRatePlan.Hold(no50[1], wait = true), plan(25.0, screen(active = no50[0], modes = no50)))
    }

    @Test
    fun `29,97 fits 59,94 and takes it from 60, and 30 takes 60 from 59,94 and fits 60`() {
        assertEquals(keeps(41), plan(NTSC))
        assertEquals(switchesTo(41), plan(NTSC, screen(active = mode(40))))
        assertEquals(switchesTo(40), plan(30.0))
        assertEquals(keeps(40), plan(30.0, screen(active = mode(40))))
    }

    @Test
    fun `50, 59,94 and 60 fps take their own rate`() {
        assertEquals(switchesTo(38), plan(50.0))
        assertEquals(switchesTo(41), plan(NTSC_60, screen(active = mode(40))))
        assertEquals(keeps(41), plan(NTSC_60))
        assertEquals(switchesTo(40), plan(60.0))
    }

    /** The same resolution, by design: the planner never sees the file's, only the output's. */
    @Test
    fun `the resolution never changes, so a rate listed only at another size switches nothing`() {
        val modes = listOf(mode(41), mode(40), mode(25), mode(28))
        assertEquals(FrameRatePlan.Leave(LeaveReason.NoFittingMode, "no 23.976 Hz mode at 3840x2160"), plan(FILM, screen(modes = modes)))
        assertEquals(LeaveReason.NoFittingMode, leaves(plan(24.0, screen(modes = modes))))
    }

    /** A 1080p file on the Shield's 4K output, and a 4K remux with the box set to 1080p output. */
    @Test
    fun `1080p content on a 2160p output switches among the 2160p modes, and 2160p content on a 1080p output among the 1080p ones`() {
        assertEquals(switchesTo(23), plan(FILM, screen(active = mode(41))))
        assertEquals(switchesTo(25), plan(FILM, screen(active = mode(27))))
        assertEquals(switchesTo(37), plan(25.0, screen(active = mode(27))))
        // 1080p's 120 Hz is a multiple of 23.976 and 24 too, but the lowest fitting rate wins.
        assertEquals(switchesTo(34), plan(24.0, screen(active = mode(39))))
    }

    /** tv36 and the phone emulators list one 60 Hz mode; a TV may offer no 24p. */
    @Test
    fun `without a fitting mode at the size it leaves the display, saying which`() {
        val one = ScreenMode(1, fullHd, 60f)
        assertEquals(FrameRatePlan.Leave(LeaveReason.NoFittingMode, "no 23.976 Hz mode at 1920x1080"), plan(FILM, screen(active = one, modes = listOf(one))))
        assertEquals(FrameRatePlan.Leave(LeaveReason.NoFittingMode, "no 25 Hz mode at 1920x1080"), plan(25.0, screen(active = one, modes = listOf(one))))
        // 23.976 is never shown at 24 Hz.
        val noFilm = listOf(ScreenMode(1, uhd, 59.94f), ScreenMode(2, uhd, 24f))
        assertEquals(LeaveReason.NoFittingMode, leaves(plan(FILM, screen(active = noFilm[0], modes = noFilm))))
    }

    /** A rate the display switches to seamlessly (Display.Mode.getAlternativeRefreshRates, API 31) is Media3's own call, with no blank screen. */
    @Test
    fun `a seamless alternative rate of the mode it runs is left to Media3, from Android 12`() {
        val active = ScreenMode(1, fullHd, 60f, alternativeRates = listOf(120f))
        val modes = listOf(active, ScreenMode(2, fullHd, 24f), ScreenMode(3, fullHd, 120f, alternativeRates = listOf(60f)))
        assertEquals(FrameRatePlan.Leave(LeaveReason.Seamless, "120 Hz is a seamless switch, left to the player"), plan(24.0, screen(active, modes, sdk = 31)))
        // Below API 31 nothing is seamless to the app: it reads no alternatives.
        assertEquals(FrameRatePlan.Hold(modes[1], wait = true), plan(24.0, screen(active, modes, sdk = 30)))
    }

    @Test
    fun `Android's Match content frame rate set to Never leaves the display, from Android 12`() {
        assertEquals(
            FrameRatePlan.Leave(LeaveReason.SystemNever, "Android's Match content frame rate is set to Never"),
            plan(FILM, screen(sdk = 31, match = MatchContentFrameRate.Never)),
        )
        // Before the rate is asked.
        assertEquals(LeaveReason.SystemNever, leaves(plan(null, screen(sdk = 34, match = MatchContentFrameRate.Never))))
        listOf(MatchContentFrameRate.OnlyWhenSeamless, MatchContentFrameRate.Always, MatchContentFrameRate.Unknown).forEach {
            assertEquals(switchesTo(23), plan(FILM, screen(sdk = 31, match = it)), "$it")
        }
    }

    /** No DefaultDuration (a variable frame rate), Media3's NO_VALUE, a stream's odd average, a broken header. */
    @Test
    fun `an unknown or unusual frame rate leaves the display`() {
        assertEquals(FrameRatePlan.Leave(LeaveReason.RateUnknown, "frame rate unknown before playback"), plan(null))
        listOf(Double.NaN, 0.0, -1.0, Double.POSITIVE_INFINITY).forEach {
            assertEquals(FrameRatePlan.Leave(LeaveReason.RateUnknown, "frame rate unknown before playback"), plan(it), "$it")
        }
        assertEquals(FrameRatePlan.Leave(LeaveReason.RateUnknown, "1000 fps isn't a usual frame rate"), plan(1000.0))
        listOf(12.5, 23.9, 119.88, 15.0).forEach { assertEquals(LeaveReason.RateUnknown, leaves(plan(it)), "$it") }
    }

    /** Google's advice and VLC's 300,000 ms: a clip isn't worth a blank screen. A length not known counts as long. */
    @Test
    fun `under 5 minutes leaves the display, and 5 minutes or an unknown length switch`() {
        assertEquals(FrameRatePlan.Leave(LeaveReason.Short, "under 5 minutes long"), plan(FILM, durationMs = 299_000))
        assertEquals(LeaveReason.Short, leaves(plan(FILM, durationMs = 299_999)))
        assertEquals(switchesTo(23), plan(FILM, durationMs = 300_000))
        assertEquals(switchesTo(23), plan(FILM, durationMs = null))
    }

    /** The next episode at the same rate, the TV still in the last one's mode (the hand-over hold): no switch, no wait. */
    @Test
    fun `the mode already matched is kept without a wait`() {
        assertEquals(keeps(23), plan(FILM, screen(active = mode(23))))
        assertEquals(keeps(31), plan(24.0, screen(active = mode(31))))
    }

    /**
     * #65: the TV's display-size property says it outputs 4K while the box lists only the 1080p UI's modes as running;
     * asking for a listed 1080p mode might drop the output to 1080p.
     */
    @Test
    fun `an output the TV states unlike the mode it runs leaves the display`() {
        assertEquals(
            FrameRatePlan.Leave(LeaveReason.OutputNotMode, "the TV outputs 3840x2160, the display's mode is 1920x1080"),
            plan(FILM, screen(active = mode(27), output = uhd, fromTv = true)),
        )
        // Only the TV's own word counts: Media3's read of the modes is the mode.
        assertEquals(switchesTo(25), plan(FILM, screen(active = mode(27), output = uhd, fromTv = false)))
    }

    @Test
    fun `without a display mode it leaves the display`() {
        assertEquals(FrameRatePlan.Leave(LeaveReason.ModeUnknown, "the display's mode is unknown"), plan(FILM, screen(active = null, modes = emptyList(), output = null)))
    }

    /** A whole multiple within 0.05 %: 23.976 and 24 (and 29.97 and 30, 59.94 and 60) are exactly 0.1 % apart. */
    @Test
    fun `a rate fits a multiple of itself within 0,05 percent`() {
        assertFalse(FrameRatePlanner.fits(23.976, 24f))
        assertFalse(FrameRatePlanner.fits(24.0, 23.976f))
        assertFalse(FrameRatePlanner.fits(29.97, 60f))
        assertFalse(FrameRatePlanner.fits(23.976, 59.94f))
        assertTrue(FrameRatePlanner.fits(25.0, 50f))
        assertTrue(FrameRatePlanner.fits(23.976, 119.88f))
        assertTrue(FrameRatePlanner.fits(23.976, 23.976025f))
        assertTrue(FrameRatePlanner.fits(FILM, 47.952f))
        assertTrue(FrameRatePlanner.fits(NTSC, 59.94006f))
        assertFalse(FrameRatePlanner.fits(60.0, 30f))
        assertFalse(FrameRatePlanner.fits(Double.NaN, 60f))
        assertFalse(FrameRatePlanner.fits(0.0, 60f))
    }

    /** One INFO line per play, device facts only: a check on the Shield reads them. */
    @Test
    fun `the log line says the rate, the display and what was done`() {
        fun line(fps: Double?, screen: ScreenModes = screen()) = FrameRatePlanner.line(fps, screen, plan(fps, screen))
        assertEquals("Frame rate: 23.976 fps; display 3840x2160 at 59.94 Hz: switching to 23.976 Hz (mode 23)", line(FILM))
        assertEquals("Frame rate: 25 fps; display 3840x2160 at 59.94 Hz: switching to 50 Hz (mode 38)", line(25.0))
        assertEquals("Frame rate: 23.976 fps; display 3840x2160 at 23.976 Hz: already fits", line(FILM, screen(active = mode(23))))
        val one = ScreenMode(1, fullHd, 60f)
        assertEquals("Frame rate: 23.976 fps; display 1920x1080 at 60 Hz: left: no 23.976 Hz mode at 1920x1080", line(FILM, screen(active = one, modes = listOf(one))))
        assertEquals("Frame rate: unknown; display 3840x2160 at 59.94 Hz: left: frame rate unknown before playback", line(null))
        assertEquals("Frame rate: unknown; display 3840x2160 at 59.94 Hz: left: frame rate unknown before playback", line(Double.NaN))
        assertEquals("Frame rate: 23.976 fps; display unknown: left: the display's mode is unknown", line(FILM, screen(active = null, modes = emptyList(), output = null)))
    }

    private companion object {
        const val EPISODE_MS = 45 * 60_000L
        /** Matroska's DefaultDuration for 24000/1001 (rate-23976.mkv's). */
        const val FILM = 1e9 / 41_708_333
        const val NTSC = 30_000.0 / 1001
        const val NTSC_60 = 60_000.0 / 1001
    }
}
