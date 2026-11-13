package io.skjaere.usenet.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import io.skjaere.usenet.releases.Hdr

/** A display mode's size in pixels. */
data class ModeSize(val width: Int, val height: Int) {
    /** Its landscape height, what "1080p" names. */
    val shorter: Int get() = minOf(width, height)

    override fun toString() = "${width}x$height"
}

/** What [DisplayFacts.read] asks of a display, each read when asked: Media3's and the platform's calls, or a test's. */
internal class DisplayReadings(
    /** The mode it outputs now, as Media3 reads it: on a TV the display-size property's size first. */
    val output: () -> ModeSize,
    /** The modes it lists. */
    val modes: () -> List<ModeSize>,
    /** Display.HdrCapabilities' types; null when it has none. It may throw. */
    val hdrTypes: () -> IntArray?,
    /** The refresh rate of the mode it runs now (Display.Mode's), which only File details read ([DisplayDetails]). */
    val refreshRate: () -> Float?,
)

/**
 * The default display as it is now. A few Display calls, cheap enough to read for every ranking, and they need to be:
 * a box's output mode changes when the user sets it, and a TV's HDR when it wakes (a Shield's TV reports HDR10 without
 * Dolby Vision for about 8 s after), so facts read once would stick at a wrong answer for the rest of the process. Its
 * refresh rate isn't one of them ([DisplayDetails]).
 */
data class DisplayFacts(
    /** Whether the device is a TV (Media3's Util.isTv): only there Auto quality follows the output mode. */
    val tv: Boolean,
    /** The mode it outputs now, as the player reads it (Media3's Util.getCurrentDisplayModeSize); null when unknown. */
    val output: ModeSize?,
    /**
     * Whether [output] is the TV's own word, its display-size system property, which Media3 reads first on a TV. It is
     * trusted above the modes the display lists, which on some 4K TVs are only the 1080p UI's (#65).
     */
    val fromTv: Boolean,
    /** The largest mode the display lists. */
    val largest: ModeSize?,
    /** The app's window. On a TV it is the UI's size, not the output's, so it counts only when the display says nothing. */
    val window: ModeSize,
    /** The HDR formats it shows now, less those the user turned off; empty without HDR, null when unknown. */
    val hdr: Set<Hdr>?,
) {
    /**
     * The height Auto quality aims for. On a TV the output's, as a box set to 1080p output wants 1080p releases, but never
     * above the largest mode listed (a mode Media3 can't read well stays at what the display lists) unless the TV itself
     * says so. On a phone or tablet the largest mode's, as before the output was read: its resolution setting is a mode
     * too (a Pixel Pro runs 1008x2244 on its 1344x2992 panel by default), and 1080p still looks sharper there than 720p.
     * The window's when the display says nothing.
     */
    val height: Int
        get() = output?.shorter?.takeIf { fromTv }
            ?: (if (tv) listOfNotNull(output?.shorter, largest?.shorter).minOrNull() else largest?.shorter ?: output?.shorter)
            ?: window.shorter

    /**
     * Facts that say the same of the display are equal: the window counts only where the height comes from it, as a phone's
     * turns with the phone while the display stays. The loader logs a line only for a change, and a search ranked before a
     * turn is still handed to the play after it.
     */
    override fun equals(other: Any?): Boolean = other is DisplayFacts && key == other.key

    override fun hashCode(): Int = key.hashCode()

    private val key get() = listOf(tv, output, fromTv, largest, window.takeIf { output == null && largest == null }, hdr)

    override fun toString(): String {
        val now = output?.let { "$it output" + if (fromTv) " (the TV's display-size property)" else "" } ?: "output unknown"
        val window = if (output == null && largest == null) ", window $window" else ""
        val hdr = hdr?.let { types -> HDR_TYPES.values.filter { it in types }.joinToString { it.label }.ifEmpty { "none" } } ?: "unknown"
        return "Display: $now, largest mode ${largest ?: "unknown"}$window: ${height}p; HDR: $hdr"
    }

    companion object {
        @OptIn(UnstableApi::class)
        fun read(context: Context): DisplayFacts = of(Util.isTv(context), Build.VERSION.SDK_INT, Util::getSystemProperty, windowOf(context), readingsOf(context))

        /** The app's window. */
        internal fun windowOf(context: Context) = context.resources.displayMetrics.let { ModeSize(it.widthPixels, it.heightPixels) }

        /** What [read] asks of the default display, each read when asked; null when there is none. */
        @OptIn(UnstableApi::class)
        internal fun readingsOf(context: Context): DisplayReadings? =
            context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.let { d ->
                DisplayReadings(
                    output = { Util.getCurrentDisplayModeSize(context, d).let { ModeSize(it.x, it.y) } },
                    modes = { d.supportedModes.map { ModeSize(it.physicalWidth, it.physicalHeight) } },
                    // Display.Mode.getSupportedHdrTypes (API 34) keeps the types the user turned off; this leaves them out.
                    hdrTypes = { @Suppress("DEPRECATION") d.hdrCapabilities?.supportedHdrTypes },
                    refreshRate = { d.mode.refreshRate },
                )
            }

        /**
         * [read] from what the device answers: whether it is a [tv] and its [sdk] level as Media3 reads them, a system
         * [property], the app's [window], and the default [display]'s readings (null when there is none).
         */
        internal fun of(tv: Boolean, sdk: Int, property: (String) -> String?, window: ModeSize, display: DisplayReadings?): DisplayFacts {
            display ?: return DisplayFacts(tv, null, false, null, window, null)
            val output = display.output().takeIf { it.shorter > 0 }
            // Media3 reads the property only on a TV's default display, as this does.
            val fromTv = tv && !property(sizeProperty(sdk)).isNullOrBlank()
            val largest = display.modes().maxByOrNull { it.shorter }
            val hdr = runCatching { hdrOf(display.hdrTypes()) }.getOrNull()
            return DisplayFacts(tv, output, fromTv, largest, window, hdr)
        }

        /** The system property a TV says its output's size in, where Media3 reads it: renamed in Android 9 (API 28). */
        internal fun sizeProperty(sdk: Int) = if (sdk < 28) "sys.display-size" else "vendor.display-size"

        /** Display.HdrCapabilities' types; none when the display has no capabilities, as Display.isHdr reads them. */
        internal fun hdrOf(types: IntArray?): Set<Hdr> = types?.asList()?.mapNotNull { HDR_TYPES[it] }?.toSet().orEmpty()

        // Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION, _HDR10, _HLG, _HDR10_PLUS (API 29), in the log in this order.
        private val HDR_TYPES = mapOf(1 to Hdr.DolbyVision, 2 to Hdr.Hdr10, 3 to Hdr.Hlg, 4 to Hdr.Hdr10Plus)
    }
}

/**
 * The display as File details show it: [facts] as the ranking reads them, and the [refreshRate] of the mode it runs now
 * (Hz; null when unknown). The rate is read beside the facts, never in them: equal facts let auto-advance hand its
 * prefetched search to the play (TV-5's TCR-1), and a TV matching a film's frame rate, or a phone's adaptive refresh,
 * would make them unequal and write a log line each time.
 */
data class DisplayDetails(val facts: DisplayFacts, val refreshRate: Float?) {
    /**
     * "3840 × 2160 · 59.94 Hz", noting the HDR it can show ("can show Dolby Vision, HDR10"), "can't show HDR", or nothing
     * when that can't be read; null when neither the output nor the rate is known.
     */
    val fact: Fact?
        get() {
            val value = listOfNotNull(facts.output?.let { "${it.width} × ${it.height}" }, refreshRate?.let { "${formatRate(it.toDouble())} Hz" })
            if (value.isEmpty()) return null
            val note = facts.hdr?.let { types -> SHOWN.filter { it in types }.joinToString { it.label }.ifEmpty { null }?.let { "can show $it" } ?: "can't show HDR" }
            return Fact(value.joinToString(" · "), note)
        }

    companion object {
        /** The default display now, as [DisplayFacts.read] reads it, and its refresh rate. */
        @OptIn(UnstableApi::class)
        fun read(context: Context): DisplayDetails =
            of(Util.isTv(context), Build.VERSION.SDK_INT, Util::getSystemProperty, DisplayFacts.windowOf(context), DisplayFacts.readingsOf(context))

        /** [read] from what the device answers, as [DisplayFacts.of] takes it. */
        internal fun of(tv: Boolean, sdk: Int, property: (String) -> String?, window: ModeSize, display: DisplayReadings?) =
            DisplayDetails(DisplayFacts.of(tv, sdk, property, window, display), display?.refreshRate?.invoke()?.takeIf { it > 0 })

        // The HDR formats in the order File details name them.
        private val SHOWN = listOf(Hdr.DolbyVision, Hdr.Hdr10, Hdr.Hdr10Plus, Hdr.Hlg)
    }
}
