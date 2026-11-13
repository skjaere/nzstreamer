package io.skjaere.usenet.app.play

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.skjaere.usenet.app.casting.CastDip
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.data.PlaybackPrefs

/**
 * What the slow-stream banner says of a dip ([DipDetector]), in Mbps as Maximum bitrate and the release chips say it
 * (W1): "Streaming is slower than this video needs", "48 of 80 Mbps", and a hint about Maximum bitrate (L7). Not "your
 * connection": the provider, the device's read-ahead or its CPU can be why.
 */
internal object SlowStream {
    const val HEADLINE = "Streaming is slower than this video needs"
    const val TRY_SMALLER = "Try a smaller release"

    /** A hint, as it shows and as TalkBack says it. */
    data class Hint(val text: String, val spoken: String)

    /** The banner's words for a dip: the numbers, the hint (null: none to give), and everything as TalkBack reads it once. */
    data class Shown(val numbers: String, val hint: String?, val spoken: String)

    /** The largest of [choices] at or under 0.8 of [rateBps]: room left for scenes that need more than the average; null under the lowest. */
    fun suggested(rateBps: Long, choices: List<Int>): Int? = choices.filter { it * 1_000_000L * 5 <= rateBps * 4 }.maxOrNull()

    /** "48 of 80 Mbps". */
    fun numbers(rateBps: Long, bitrateBps: Long): String = "${mbps(rateBps)} of ${mbps(bitrateBps)} Mbps"

    /** "48 of 80 megabits per second". */
    fun spokenNumbers(rateBps: Long, bitrateBps: Long): String = "${mbps(rateBps)} of ${mbps(bitrateBps)} $SPOKEN_UNIT"

    /**
     * What Maximum bitrate could do, with [max] set (null: not set), for a stream read at [rateBps] of a release at
     * [playingBps] (the file's bitrate), among Settings' [choices]. Its value is the largest choice at or under 0.8 of the
     * rate, room left for scenes that need more than the average (48 Mbps measured: 30). A release above the maximum was
     * tried last or chosen by hand, and the hint says so; under the lowest choice it says that much may stall too; with
     * none set it suggests one, and above that value it suggests lowering it. Null when there is nothing to suggest.
     */
    fun hint(rateBps: Long, playingBps: Long, max: Int?, choices: List<Int> = PlaybackPrefs.MAX_BITRATES_MBPS): Hint? {
        val value = suggested(rateBps, choices)
        val lowest = choices.minOrNull() ?: return null
        fun suggest(verb: String, mbps: Int) = Hint(
            "$verb Maximum bitrate to $mbps Mbps (Settings › Quality and languages) picks smaller releases next time.",
            "$verb Maximum bitrate to $mbps $SPOKEN_UNIT, in Settings, Quality and languages, picks smaller releases next time.",
        )
        return when {
            max != null && playingBps > max * 1_000_000L ->
                Hint("This release is above your $max Mbps maximum.", "This release is above your $max $SPOKEN_UNIT maximum.")
            value == null ->
                Hint("Even the lowest Maximum bitrate, $lowest Mbps, may stall at this speed.", "Even the lowest Maximum bitrate, $lowest $SPOKEN_UNIT, may stall at this speed.")
            max == null -> suggest("Setting", value)
            max > value -> suggest("Lowering", value)
            else -> null
        }
    }

    /** The banner's words for [dip], with the maximum bitrate set ([max], null: not set). */
    fun shown(dip: DipDetector.Dip, max: Int?, choices: List<Int> = PlaybackPrefs.MAX_BITRATES_MBPS): Shown {
        val hint = hint(dip.rateBps, dip.bitrateBps, max, choices)
        val spoken = listOfNotNull(HEADLINE, spokenNumbers(dip.rateBps, dip.bitrateBps), hint?.spoken?.removeSuffix(".")).joinToString(". ") + "."
        return Shown(numbers(dip.rateBps, dip.bitrateBps), hint?.text, spoken)
    }

    /** "48", "8.4" under 10, "< 0.1" for less, as the release list's chips round them; in the locale's digits. */
    private fun mbps(bitsPerSecond: Long): String {
        val tenths = (bitsPerSecond + 50_000) / 100_000
        return when {
            tenths == 0L -> "< 0.1"
            tenths < 100 -> "%.1f".format(tenths / 10.0)
            else -> "%d".format((bitsPerSecond + 500_000) / 1_000_000)
        }
    }

    const val SPOKEN_UNIT = "megabits per second"
}

/**
 * What the slow-stream banner says during a cast (W52, W64, L134; CsPhoneSlowCast): [SlowStream]'s headline and numbers
 * (what reached the device against the file's bitrate), which part is slow ([cause]), then a hint about Maximum bitrate
 * when casting ([hint]); for a release above a maximum, only that it is (a hand pick, or one tried last).
 */
internal object CastSlowStream {
    /**
     * Which part is slow (W64): the Wi-Fi from this phone to [device], or where the phone reads the film from, the providers
     * or, for a play from a debrid service ([fromService], L220), the user's debrid service, which it names no service of.
     */
    fun cause(cause: CastDip.Cause, device: String, fromService: Boolean = false): String = when (cause) {
        CastDip.Cause.WiFi -> "The Wi-Fi to $device is the slow part."
        CastDip.Cause.Providers -> if (fromService) "Your debrid service is the slow part." else "Your providers are the slow part."
    }

    /**
     * What Maximum bitrate when casting could do, read as [SlowStream.hint] reads Maximum bitrate, over the lower of the
     * two set (L132: the phone's [max] counts while casting too, the phone still downloading the film; [castMax] the
     * casting one; null: not set): a release above it says so, in the words of the one that is lower (casting's when they
     * are the same); under the lowest choice, that much may stall too; else the casting value at 0.8 of the rate, set or
     * lowered, only where it is under the lower maximum and so changes something. Null when there is nothing to suggest.
     */
    fun hint(rateBps: Long, playingBps: Long, max: Int?, castMax: Int?, choices: List<Int> = PlaybackPrefs.MAX_BITRATES_MBPS): SlowStream.Hint? {
        above(playingBps, max, castMax)?.let { return it }
        val limit = listOfNotNull(max, castMax).minOrNull()
        val value = SlowStream.suggested(rateBps, choices)
        val lowest = choices.minOrNull() ?: return null
        fun suggest(verb: String, mbps: Int) = SlowStream.Hint(
            "$verb Maximum bitrate when casting to $mbps Mbps (Settings › Quality and languages) picks smaller releases next time.",
            "$verb Maximum bitrate when casting to $mbps ${SlowStream.SPOKEN_UNIT}, in Settings, Quality and languages, picks smaller releases next time.",
        )
        return when {
            value == null -> SlowStream.Hint(
                "Even the lowest Maximum bitrate when casting, $lowest Mbps, may stall at this speed.",
                "Even the lowest Maximum bitrate when casting, $lowest ${SlowStream.SPOKEN_UNIT}, may stall at this speed.",
            )
            limit != null && value >= limit -> null
            else -> suggest(if (castMax == null) "Setting" else "Lowering", value)
        }
    }

    /**
     * The banner's words for [slow] on [device], with the maximums set (null: not set), a play [fromService] or the
     * providers'; TalkBack's once, the numbers in words.
     */
    fun shown(
        slow: CastDip.Slow, device: String, max: Int?, castMax: Int?, choices: List<Int> = PlaybackPrefs.MAX_BITRATES_MBPS, fromService: Boolean = false,
    ): SlowStream.Shown {
        val dip = slow.dip
        val cause = cause(slow.cause, device, fromService)
        // Above a maximum, that alone: which part is slow says nothing a release the user let through needs.
        val said = above(dip.bitrateBps, max, castMax)?.let(::listOf)
            ?: listOfNotNull(SlowStream.Hint(cause, cause), hint(dip.rateBps, dip.bitrateBps, max, castMax, choices))
        val spoken = (listOf(SlowStream.HEADLINE, SlowStream.spokenNumbers(dip.rateBps, dip.bitrateBps)) + said.map { it.spoken.removeSuffix(".") })
            .joinToString(". ") + "."
        return SlowStream.Shown(SlowStream.numbers(dip.rateBps, dip.bitrateBps), said.joinToString(" ") { it.text }, spoken)
    }

    /** A release at [playingBps] above the lower set maximum: the words of the one that is lower, casting's when both are. */
    private fun above(playingBps: Long, max: Int?, castMax: Int?): SlowStream.Hint? {
        val limit = listOfNotNull(max, castMax).minOrNull() ?: return null
        if (playingBps <= limit * 1_000_000L) return null
        return if (castMax == limit) {
            SlowStream.Hint("This release is above your $castMax Mbps for casting.", "This release is above your $castMax ${SlowStream.SPOKEN_UNIT} for casting.")
        } else {
            SlowStream.Hint("This release is above your $max Mbps maximum.", "This release is above your $max ${SlowStream.SPOKEN_UNIT} maximum.")
        }
    }
}

/**
 * The player's slow-stream banner (QlPhoneDip, QlTvDip): [shown]'s headline, numbers and hint, and Try a smaller release
 * where [onTrySmaller] is given (W2: a play started from a list of releases with a smaller one in it). It sits where the
 * next-episode card does, on its surface; during a cast (CsPhoneSlowCast) over the remote's picture on the same surface,
 * or above the bar on the app's other screens on [background] (the chip's colour there), across the width it is given.
 * [compact] (while the controls show) is one row, the headline, the numbers and the button, as the card's
 * (QlPhoneDipControls). It never asks for the focus: a remote reaches its button from the controls. At a large font the
 * words scroll and the button stays whole ([withinHeight]: it has a height it mustn't pass, as in the player and above
 * the bar; the remote's page scrolls instead): the card grows to its button rather than cut its label. TalkBack reads it
 * once as it appears, a polite live region with the numbers in words, and not again as it turns compact (the same words
 * in both forms). Its surface takes its taps ([takesTaps]). [trySmaller] is its button's requester.
 */
@Composable
internal fun SlowStreamBanner(
    shown: SlowStream.Shown,
    compact: Boolean,
    onTrySmaller: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trySmaller: FocusRequester = remember { FocusRequester() },
    background: Color = colors.surface.copy(alpha = 0.94f),
    withinHeight: Boolean = true,
) {
    val surface = modifier.clip(RoundedCornerShape(16.dp)).background(background).takesTaps()
    // One node for TalkBack, the same in both forms, so turning compact isn't news.
    val spoken = Modifier.clearAndSetSemantics {
        liveRegion = LiveRegionMode.Polite
        contentDescription = shown.spoken
    }
    val heading = @Composable { Text(SlowStream.HEADLINE, style = AppType.small.copy(fontWeight = FontWeight.SemiBold), color = colors.warnStrong) }
    val button = onTrySmaller?.let { press ->
        @Composable { CardButton(SlowStream.TRY_SMALLER, press, Modifier.focusRequester(trySmaller), filled = true, horizontalPadding = 16.dp) }
    }
    if (compact) {
        Row(surface.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(spoken.weight(1f, fill = false)) {
                Text(SlowStream.HEADLINE, style = AppType.small.copy(fontWeight = FontWeight.SemiBold), color = colors.warnStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(shown.numbers, style = AppType.small, color = colors.textSecondary)
            }
            button?.invoke()
        }
    } else {
        WordsOverButton(
            CARD_WIDTH - 24.dp,
            words = {
                val scrolls = if (withinHeight) Modifier.verticalScroll(rememberScrollState()) else Modifier
                Column(spoken.then(scrolls), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    heading()
                    Text(shown.numbers, style = AppType.bodyStrong.copy(fontSize = 14.sp), color = colors.text)
                    shown.hint?.let { Text(it, Modifier.padding(top = 4.dp), style = AppType.small, color = colors.muted) }
                }
            },
            button = button,
            modifier = surface.padding(12.dp),
        )
    }
}

/** The card's width at the default font: as the next-episode card's and "Playback stopped"'s. */
private val CARD_WIDTH = 300.dp

/**
 * [words] over [button], 10 dp apart, as wide as [minWidth], the button or the width it must fill, whichever is widest (a
 * large font's label isn't cut). The button is measured first; the words get the height left, and scroll in it.
 */
@Composable
private fun WordsOverButton(minWidth: Dp, words: @Composable () -> Unit, button: (@Composable () -> Unit)?, modifier: Modifier) {
    Layout(content = { words(); button?.invoke() }, modifier = modifier) { measurables, constraints ->
        val gap = 10.dp.roundToPx()
        val pressed = measurables.getOrNull(1)?.measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight))
        val below = pressed?.let { it.height + gap } ?: 0
        val width = maxOf(minWidth.roundToPx(), pressed?.width ?: 0, constraints.minWidth).coerceAtMost(constraints.maxWidth)
        val left = if (constraints.hasBoundedHeight) (constraints.maxHeight - below).coerceAtLeast(0) else Constraints.Infinity
        val said = measurables[0].measure(Constraints(minWidth = width, maxWidth = width, maxHeight = left))
        layout(width, said.height + below) {
            said.place(0, 0)
            pressed?.place(0, said.height + gap)
        }
    }
}
