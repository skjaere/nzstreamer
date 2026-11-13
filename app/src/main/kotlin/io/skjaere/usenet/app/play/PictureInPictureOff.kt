package io.skjaere.usenet.app.play

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.colors
import kotlin.math.ceil

/**
 * What the player says where Android refused its picture-in-picture window (PipPlayerOff, proposal A): the user turned
 * picture-in-picture off for the app in Android's settings, its only switch (W3).
 */
internal object WindowOff {
    /** The words, with the app's name as Android's settings list it ([app]): "NZStreamer". */
    fun words(app: String) = "Picture-in-picture is off for $app in Android’s settings"

    const val OPEN_SETTINGS = "Open settings"
}

/**
 * The player's words that picture-in-picture is off ([words], [WindowOff]) under its top bar, in the moment label's place
 * and look: a pill on the card's surface, the words in 14 sp, and the app's filled button, Open settings
 * ([onOpenSettings]). Where the words don't fit on one line beside it (a 640 dp wide phone), they break into two even
 * lines, and the pill hugs them. TalkBack reads them once as they appear, a polite live region; nothing takes the focus.
 * Its surface takes its taps ([takesTaps]).
 */
@Composable
internal fun PictureInPictureOff(words: String, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(30.dp)).background(colors.surface.copy(alpha = 0.94f)).takesTaps()
            .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HuggingText(words, AppType.bodyStrong.copy(fontSize = 14.sp, lineHeight = 20.sp, lineBreak = LineBreak.Heading), Modifier.weight(1f, fill = false))
        CardButton(WindowOff.OPEN_SETTINGS, onOpenSettings, Modifier, filled = true, horizontalPadding = 16.dp)
    }
}

/**
 * [text] as wide as its widest line, where a Text alone takes all the width it may once it breaks a line; TalkBack reads
 * it once as it appears (a polite live region).
 */
@Composable
private fun HuggingText(text: String, style: TextStyle, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val said = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    Layout(content = { Text(text, said, style = style, color = colors.text) }, modifier = modifier) { measurables, constraints ->
        val laid = measurer.measure(text, style, constraints = Constraints(maxWidth = constraints.maxWidth))
        val widest = (0 until laid.lineCount).maxOf { ceil(laid.getLineRight(it) - laid.getLineLeft(it)).toInt() }.coerceAtMost(constraints.maxWidth)
        val words = measurables.single().measure(Constraints(minWidth = widest, maxWidth = widest))
        layout(words.width, words.height) { words.place(0, 0) }
    }
}
