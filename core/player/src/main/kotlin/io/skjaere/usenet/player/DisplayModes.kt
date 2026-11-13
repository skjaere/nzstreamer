package io.skjaere.usenet.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import kotlin.math.roundToInt

/** A display mode as [DisplayModes] reads it: its physical size and its refresh rate (Hz). */
internal data class ModeReading(val size: ModeSize, val refreshRate: Float)

/** What [DisplayModes.read] asks of a display, each read when asked: the platform's calls, or a test's. */
internal class ModeReadings(
    /** The mode it runs now. */
    val active: () -> ModeReading,
    /** The modes it lists. */
    val modes: () -> List<ModeReading>,
    /** DisplayManager.getMatchContentFrameRateUserPreference(), asked from API 31 only. It may throw. */
    val matchPreference: () -> Int,
)

/** How the user set Android's "Match content frame rate" (DisplayManager's MATCH_CONTENT_FRAMERATE_*). */
enum class MatchContentFrameRate {
    Always, OnlyWhenSeamless, Never, Unknown;

    internal companion object {
        /** DisplayManager.getMatchContentFrameRateUserPreference()'s answer; null when it couldn't be asked. */
        fun of(preference: Int?): MatchContentFrameRate = MATCH[preference] ?: Unknown

        // DisplayManager.MATCH_CONTENT_FRAMERATE_NEVER, _SEAMLESSS_ONLY (three S's, as Android spells it) and _ALWAYS;
        // _UNKNOWN (-1) and anything else is Unknown.
        private val MATCH = mapOf(0 to Never, 1 to OnlyWhenSeamless, 2 to Always)
    }
}

/**
 * The refresh rates the default display offers at the size it runs now, and how its frame-rate matching is set, for This
 * device and the log header. Read beside [DisplayFacts], never in them, as [DisplayDetails]' rate is: equal facts let
 * auto-advance hand its prefetched search to the play (TCR-1), and a TV switching to a film's rate mustn't make them unequal.
 * A few cheap calls: read on This device's open and resume, and at start for the header.
 */
data class DisplayModes(
    /** The physical size of the mode the display runs now; null without a display. */
    val size: ModeSize?,
    /** The refresh rates of the modes it lists at [size], in Hz, ascending, each once. */
    val rates: List<Float>,
    /** Android's setting; null below Android 12 (API 31), which doesn't tell apps. */
    val matchContentFrameRate: MatchContentFrameRate?,
) {
    companion object {
        fun read(context: Context): DisplayModes = of(Build.VERSION.SDK_INT, readingsOf(context))

        private fun readingsOf(context: Context): ModeReadings? {
            val displays = context.getSystemService(DisplayManager::class.java) ?: return null
            val d = displays.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
            fun Display.Mode.reading() = ModeReading(ModeSize(physicalWidth, physicalHeight), refreshRate)
            return ModeReadings(
                active = { d.mode.reading() },
                modes = { d.supportedModes.map { it.reading() } },
                // of() asks only from API 31; the check here is for the compiler's sake.
                matchPreference = { if (Build.VERSION.SDK_INT >= 31) displays.matchContentFrameRateUserPreference else -1 },
            )
        }

        /** [read] from what the device answers at its [sdk] level; [display] null when there is no default display. */
        internal fun of(sdk: Int, display: ModeReadings?): DisplayModes {
            display ?: return DisplayModes(null, emptyList(), null)
            val size = display.active().size
            // Some displays list a rate twice at one size (modes that differ in what the app can't see); a rate read
            // as a float a hair apart is the same one.
            val rates = display.modes().filter { it.size == size }.map { it.refreshRate }.distinctBy { (it * 1000).roundToInt() }.sorted()
            val match = if (sdk < 31) null else MatchContentFrameRate.of(runCatching { display.matchPreference() }.getOrNull())
            return DisplayModes(size, rates, match)
        }
    }
}
