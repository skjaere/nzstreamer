package io.skjaere.usenet.app.play

import io.skjaere.usenet.app.casting.CastDip
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the slow-stream banner says (W1, L7, QlPhoneDip and its variants), and what TalkBack hears of it. */
class SlowStreamTest {
    private val saved = Locale.getDefault()

    // The numbers follow the default locale, as the release list's chips do; pin one so the decimal separator is predictable.
    @BeforeEach
    fun pin() = Locale.setDefault(Locale.US)

    @AfterEach
    fun unpin() = Locale.setDefault(saved)

    /** Settings' Maximum bitrate choices with W4's 5 Mbps, as the mockups have them. */
    private val choices = listOf(5, 10, 15, 20, 30, 40, 60, 80, 100)

    private fun mbps(n: Double) = (n * 1_000_000).toLong()

    private fun hint(measured: Double, playing: Double, max: Int?) = SlowStream.hint(mbps(measured), mbps(playing), max, choices)

    @Test
    fun `the numbers in Mbps, whole from 10, a tenth below`() {
        assertEquals("48 of 80 Mbps", SlowStream.numbers(mbps(48.2), mbps(79.6)))
        assertEquals("8.4 of 12 Mbps", SlowStream.numbers(mbps(8.44), mbps(12.0)))
        assertEquals("0.3 of 0.8 Mbps", SlowStream.numbers(mbps(0.32), mbps(0.84)))
        assertEquals("< 0.1 of 0.8 Mbps", SlowStream.numbers(40_000, mbps(0.84)))
        assertEquals("48 of 80 megabits per second", SlowStream.spokenNumbers(mbps(48.0), mbps(80.0)))
    }

    /** The largest choice at or under 0.8 times the rate measured: 48 gives 38.4, so 30. */
    @Test
    fun `with no maximum set, the largest choice at or under 0 point 8 of the rate`() {
        val h = hint(48.0, 80.0, max = null)!!
        assertEquals("Setting Maximum bitrate to 30 Mbps (Settings › Quality and languages) picks smaller releases next time.", h.text)
        assertEquals(
            "Setting Maximum bitrate to 30 megabits per second, in Settings, Quality and languages, picks smaller releases next time.",
            h.spoken,
        )
        // At the edge: 50 measured is 40 exactly.
        assertEquals("Setting Maximum bitrate to 40 Mbps (Settings › Quality and languages) picks smaller releases next time.", hint(50.0, 80.0, max = null)?.text)
    }

    /** QlPhoneDipPlain: 52 measured, a maximum of 100 over the 80 Mbps release: lower it to 40. */
    @Test
    fun `a maximum above the rate is to be lowered`() {
        val h = hint(52.0, 80.0, max = 100)!!
        assertEquals("Lowering Maximum bitrate to 40 Mbps (Settings › Quality and languages) picks smaller releases next time.", h.text)
        assertEquals("Lowering Maximum bitrate to 40 megabits per second, in Settings, Quality and languages, picks smaller releases next time.", h.spoken)
    }

    /** It was tried last, or chosen by hand: lowering the maximum wouldn't have kept it out. */
    @Test
    fun `a release above the maximum says so`() {
        val h = hint(48.0, 80.0, max = 60)!!
        assertEquals("This release is above your 60 Mbps maximum.", h.text)
        assertEquals("This release is above your 60 megabits per second maximum.", h.spoken)
        // Whatever the rate.
        assertEquals("This release is above your 5 Mbps maximum.", hint(2.0, 8.0, max = 5)?.text)
    }

    /** Under 6.25 Mbps measured, 0.8 of it is under the lowest choice (W4's 5 Mbps). */
    @Test
    fun `too slow for the lowest choice says so`() {
        val h = hint(6.0, 8.0, max = null)!!
        assertEquals("Even the lowest Maximum bitrate, 5 Mbps, may stall at this speed.", h.text)
        assertEquals("Even the lowest Maximum bitrate, 5 megabits per second, may stall at this speed.", h.spoken)
        assertEquals(h, hint(6.0, 8.0, max = 10))
        assertEquals("Setting Maximum bitrate to 5 Mbps (Settings › Quality and languages) picks smaller releases next time.", hint(6.25, 8.0, max = null)?.text)
    }

    /** A maximum already at or under what the rate suggests over a release under it: nothing to suggest (a slow stream can't be one then). */
    @Test
    fun `nothing to suggest, no hint`() {
        assertNull(hint(48.0, 30.0, max = 30))
    }

    /**
     * Settings' own choices, 150 and 200 among them (W102, L309): 200 Mbps measured is 160 at 0.8, so 150. It takes 187.5
     * measured to reach 150, so the hints on a slower line are as they were.
     */
    @Test
    fun `with the default choices, 200 Mbps measured suggests 150`() {
        fun suggested(measured: Double) = SlowStream.hint(mbps(measured), mbps(measured + 50), max = null)?.text
        assertEquals("Setting Maximum bitrate to 150 Mbps (Settings › Quality and languages) picks smaller releases next time.", suggested(200.0))
        assertEquals("Setting Maximum bitrate to 150 Mbps (Settings › Quality and languages) picks smaller releases next time.", suggested(187.5))
        assertEquals("Setting Maximum bitrate to 100 Mbps (Settings › Quality and languages) picks smaller releases next time.", suggested(187.0))
        assertEquals("Setting Maximum bitrate to 200 Mbps (Settings › Quality and languages) picks smaller releases next time.", suggested(250.0))
    }

    private fun castHint(measured: Double, playing: Double, max: Int?, castMax: Int?) = CastSlowStream.hint(mbps(measured), mbps(playing), max, castMax, choices)

    /** CsPhoneSlowCast: 19 of 31 Mbps reached Kitchen display with 40 set for casting; 0.8 of 19 is 15.2, so 15. */
    @Test
    fun `during a cast, a maximum when casting above what the rate suggests is to be lowered`() {
        val h = castHint(19.0, 31.0, max = null, castMax = 40)!!
        assertEquals("Lowering Maximum bitrate when casting to 15 Mbps (Settings › Quality and languages) picks smaller releases next time.", h.text)
        assertEquals(
            "Lowering Maximum bitrate when casting to 15 megabits per second, in Settings, Quality and languages, picks smaller releases next time.",
            h.spoken,
        )
        // The phone's own maximum above it changes nothing: the lower of the two is the limit (L132).
        assertEquals(h, castHint(19.0, 31.0, max = 60, castMax = 40))
    }

    /** Not set for casting: setting it is suggested, the phone's maximum or not (W52: the casting one is the one to set). */
    @Test
    fun `during a cast with no maximum when casting set, setting one is suggested`() {
        val h = castHint(13.0, 31.0, max = null, castMax = null)!!
        assertEquals("Setting Maximum bitrate when casting to 10 Mbps (Settings › Quality and languages) picks smaller releases next time.", h.text)
        assertEquals("Setting Maximum bitrate when casting to 10 megabits per second, in Settings, Quality and languages, picks smaller releases next time.", h.spoken)
        assertEquals(h, castHint(13.0, 31.0, max = 60, castMax = null), "the phone's 60 is over it")
    }

    /** It was tried last, or picked by hand: the casting maximum's own words, whatever the rate. */
    @Test
    fun `during a cast, a release above the maximum when casting says so`() {
        val h = castHint(31.0, 44.0, max = null, castMax = 40)!!
        assertEquals("This release is above your 40 Mbps for casting.", h.text)
        assertEquals("This release is above your 40 megabits per second for casting.", h.spoken)
        assertEquals(h, castHint(31.0, 44.0, max = 60, castMax = 40), "the phone's higher")
        assertEquals(h, castHint(31.0, 44.0, max = 40, castMax = 40), "the same: casting's words while casting")
        assertEquals("This release is above your 5 Mbps for casting.", castHint(2.0, 8.0, max = null, castMax = 5)?.text)
    }

    @Test
    fun `during a cast, too slow for the lowest choice says so`() {
        val h = castHint(6.0, 8.0, max = null, castMax = null)!!
        assertEquals("Even the lowest Maximum bitrate when casting, 5 Mbps, may stall at this speed.", h.text)
        assertEquals("Even the lowest Maximum bitrate when casting, 5 megabits per second, may stall at this speed.", h.spoken)
        assertEquals(h, castHint(6.0, 8.0, max = 10, castMax = 10))
    }

    /**
     * L132, L134: while casting the phone's Maximum bitrate counts too, and the lower wins. Below the casting one, a release
     * above it gets the phone's own words, and a casting value is suggested only under it, where it changes something.
     */
    @Test
    fun `during a cast, the phone's maximum below the casting one is the limit`() {
        assertEquals("This release is above your 30 Mbps maximum.", castHint(25.0, 35.0, max = 30, castMax = 40)?.text)
        assertEquals("This release is above your 30 megabits per second maximum.", castHint(25.0, 35.0, max = 30, castMax = 40)?.spoken)
        // 44 measured suggests 35: at or over the phone's 30, setting casting's there would change nothing.
        assertNull(castHint(44.0, 30.0, max = 30, castMax = 40))
        assertNull(castHint(40.0, 30.0, max = 30, castMax = null), "30 suggested, the phone's 30 already")
        assertEquals(
            "Lowering Maximum bitrate when casting to 15 Mbps (Settings › Quality and languages) picks smaller releases next time.",
            castHint(19.0, 28.0, max = 30, castMax = 40)?.text,
        )
        assertEquals(
            "Setting Maximum bitrate when casting to 15 Mbps (Settings › Quality and languages) picks smaller releases next time.",
            castHint(19.0, 28.0, max = 30, castMax = null)?.text,
        )
    }

    /** CsPhoneSlowCast and CsPhoneTalkBackC2 19: which part is slow, then the hint; read once, the numbers in words. */
    @Test
    fun `the banner during a cast says which part is slow, and what TalkBack hears`() {
        val wifi = CastSlowStream.shown(CastDip.Slow(DipDetector.Dip(mbps(19.0), mbps(31.0)), CastDip.Cause.WiFi), "Kitchen display", max = null, castMax = 40, choices = choices)
        assertEquals("19 of 31 Mbps", wifi.numbers)
        assertEquals(
            "The Wi-Fi to Kitchen display is the slow part. Lowering Maximum bitrate when casting to 15 Mbps (Settings › Quality and languages) " +
                "picks smaller releases next time.",
            wifi.hint,
        )
        assertEquals(
            "Streaming is slower than this video needs. 19 of 31 megabits per second. The Wi-Fi to Kitchen display is the slow part. Lowering " +
                "Maximum bitrate when casting to 15 megabits per second, in Settings, Quality and languages, picks smaller releases next time.",
            wifi.spoken,
        )
        val providers = CastSlowStream.shown(CastDip.Slow(DipDetector.Dip(mbps(13.0), mbps(31.0)), CastDip.Cause.Providers), "Kitchen display", max = null, castMax = 40, choices = choices)
        assertEquals("13 of 31 Mbps", providers.numbers)
        assertEquals(
            "Your providers are the slow part. Lowering Maximum bitrate when casting to 10 Mbps (Settings › Quality and languages) picks smaller releases next time.",
            providers.hint,
        )
        // A release above the setting, picked by hand: its sentence alone.
        val above = CastSlowStream.shown(CastDip.Slow(DipDetector.Dip(mbps(31.0), mbps(44.0)), CastDip.Cause.WiFi), "Kitchen display", max = null, castMax = 40, choices = choices)
        assertEquals("31 of 44 Mbps", above.numbers)
        assertEquals("This release is above your 40 Mbps for casting.", above.hint)
        assertEquals("Streaming is slower than this video needs. 31 of 44 megabits per second. This release is above your 40 megabits per second for casting.", above.spoken)
        assertEquals("Your providers are the slow part.", CastSlowStream.cause(CastDip.Cause.Providers, "Kitchen display"))
        assertEquals("The Wi-Fi to Den TV is the slow part.", CastSlowStream.cause(CastDip.Cause.WiFi, "Den TV"))
    }

    /**
     * L220 (choice 1; D2PhoneCastWords 2, its TalkBack): a debrid cast's slow source is the user's debrid service, in words
     * that name no service; the Wi-Fi's words stay.
     */
    @Test
    fun `during a debrid cast the debrid service is the slow part, and TalkBack hears it so`() {
        assertEquals("Your debrid service is the slow part.", CastSlowStream.cause(CastDip.Cause.Providers, "Kitchen display", fromService = true))
        assertEquals("The Wi-Fi to Kitchen display is the slow part.", CastSlowStream.cause(CastDip.Cause.WiFi, "Kitchen display", fromService = true))
        val service = CastSlowStream.shown(
            CastDip.Slow(DipDetector.Dip(mbps(13.0), mbps(31.0)), CastDip.Cause.Providers), "Kitchen display", max = null, castMax = 40, choices = choices, fromService = true,
        )
        assertEquals("13 of 31 Mbps", service.numbers)
        assertEquals(
            "Your debrid service is the slow part. Lowering Maximum bitrate when casting to 10 Mbps (Settings › Quality and languages) picks smaller releases next time.",
            service.hint,
        )
        assertEquals(
            "Streaming is slower than this video needs. 13 of 31 megabits per second. Your debrid service is the slow part. Lowering Maximum bitrate when " +
                "casting to 10 megabits per second, in Settings, Quality and languages, picks smaller releases next time.",
            service.spoken,
        )
        val wifi = CastSlowStream.shown(CastDip.Slow(DipDetector.Dip(mbps(19.0), mbps(31.0)), CastDip.Cause.WiFi), "Kitchen display", max = null, castMax = 40, choices = choices, fromService = true)
        assertTrue(wifi.hint.orEmpty().startsWith("The Wi-Fi to Kitchen display is the slow part."), wifi.hint)
        listOf(service.hint.orEmpty(), service.spoken).forEach { assertFalse("TorBox" in it || "Premiumize" in it || "providers" in it, it) }
    }

    /** What the banner shows of a dip, and what TalkBack reads once as it appears (QlPhoneTalkBack). */
    @Test
    fun `the banner's words and what TalkBack hears`() {
        val shown = SlowStream.shown(DipDetector.Dip(mbps(48.0), mbps(80.0)), max = null, choices = choices)
        assertEquals("48 of 80 Mbps", shown.numbers)
        assertEquals("Setting Maximum bitrate to 30 Mbps (Settings › Quality and languages) picks smaller releases next time.", shown.hint)
        assertEquals(
            "Streaming is slower than this video needs. 48 of 80 megabits per second. Setting Maximum bitrate to 30 megabits per second, " +
                "in Settings, Quality and languages, picks smaller releases next time.",
            shown.spoken,
        )
        val bare = SlowStream.shown(DipDetector.Dip(mbps(48.0), mbps(30.0)), max = 30, choices = choices)
        assertEquals(null, bare.hint)
        assertEquals("Streaming is slower than this video needs. 48 of 30 megabits per second.", bare.spoken)
    }
}
