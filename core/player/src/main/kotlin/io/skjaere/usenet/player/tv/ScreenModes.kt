package io.skjaere.usenet.player.tv

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import io.skjaere.usenet.player.DisplayFacts
import io.skjaere.usenet.player.MatchContentFrameRate
import io.skjaere.usenet.player.ModeSize

/**
 * A display mode as a frame-rate switch asks for it: the [id] a window's preferredDisplayModeId names, its physical
 * [size], its [refreshRate] in Hz and, from Android 12 (API 31), the [alternativeRates] it switches to seamlessly. An id
 * holds only for the reading it came from: a box numbers its modes again when their list changes (a TV's standby list,
 * another TV), so a mode kept for later is found again by its size and rate.
 */
data class ScreenMode(val id: Int, val size: ModeSize, val refreshRate: Float, val alternativeRates: List<Float> = emptyList())

/** A display mode as [ScreenModes.of] asks of it; [alternativeRates] only from API 31, and it may throw. */
internal class ScreenModeReading(val id: Int, val size: ModeSize, val refreshRate: Float, val alternativeRates: () -> List<Float> = { emptyList() })

/** What [ScreenModes.read] asks of the default display, each read when asked: the platform's calls, or a test's. */
internal class ScreenReadings(
    /** The mode it runs now. */
    val active: () -> ScreenModeReading,
    /** The modes it lists. */
    val modes: () -> List<ScreenModeReading>,
    /** DisplayManager.getMatchContentFrameRateUserPreference(), asked from API 31 only. It may throw. */
    val matchPreference: () -> Int,
)

/**
 * The default display's modes as a frame-rate switch plans from them ([FrameRatePlanner]): the one it runs now and those
 * it lists, with their ids; Android's Match content frame rate; and [DisplayFacts]' output, for #65's guard. Read at the
 * moment of a switch, as the ids may have changed since, and kept apart from [DisplayFacts] and DisplayModes, whose
 * equality auto-advance's prefetched search goes by (TCR-1).
 */
data class ScreenModes(
    /** The API level they were read at: the alternative rates and the match preference are read from 31. */
    val sdk: Int,
    /** The mode the display runs now; null without a default display. */
    val active: ScreenMode?,
    /** The modes it lists, in its order. */
    val modes: List<ScreenMode>,
    /** Android's setting; null below Android 12 (API 31), which doesn't tell apps. */
    val matchContentFrameRate: MatchContentFrameRate?,
    /** [DisplayFacts.output]: the mode it outputs as the player reads it, on a TV the TV's display-size property first; null when unknown. */
    val output: ModeSize?,
    /** [DisplayFacts.fromTv]: whether [output] is the TV's own word, trusted above the modes listed, which on some 4K TVs are only the 1080p UI's (#65). */
    val outputFromTv: Boolean,
) {
    companion object {
        fun read(context: Context): ScreenModes = of(Build.VERSION.SDK_INT, readingsOf(context), DisplayFacts.read(context))

        private fun readingsOf(context: Context): ScreenReadings? {
            val displays = context.getSystemService(DisplayManager::class.java) ?: return null
            val d = displays.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
            fun Display.Mode.reading() = ScreenModeReading(
                modeId, ModeSize(physicalWidth, physicalHeight), refreshRate,
                // of() asks only from API 31; the checks here are for the compiler's sake.
                alternativeRates = { if (Build.VERSION.SDK_INT >= 31) alternativeRefreshRates.toList() else emptyList() },
            )
            return ScreenReadings(
                active = { d.mode.reading() },
                modes = { d.supportedModes.map { it.reading() } },
                matchPreference = { if (Build.VERSION.SDK_INT >= 31) displays.matchContentFrameRateUserPreference else -1 },
            )
        }

        /** [read] from what the device answers at its [sdk] level: the default [display]'s readings (null when there is none), and its [facts]. */
        internal fun of(sdk: Int, display: ScreenReadings?, facts: DisplayFacts): ScreenModes {
            display ?: return ScreenModes(sdk, null, emptyList(), null, facts.output, facts.fromTv)
            fun ScreenModeReading.mode() =
                ScreenMode(id, size, refreshRate, if (sdk < 31) emptyList() else runCatching(alternativeRates).getOrNull().orEmpty())
            val match = if (sdk < 31) null else MatchContentFrameRate.of(runCatching { display.matchPreference() }.getOrNull())
            return ScreenModes(sdk, display.active().mode(), display.modes().map { it.mode() }, match, facts.output, facts.fromTv)
        }
    }
}
