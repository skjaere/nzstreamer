package io.skjaere.usenet.app.tv

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.OwnedRipple
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusScale
import io.skjaere.usenet.app.ui.turnsOver

/**
 * A TV's chip, the Show page's seasons' and the collection's filters': a pill turned over while
 * focused, the selected one's tinted in the accent, and with its amber line while turned over. Its words are in bold then,
 * and it is as wide as they are in bold whether it is or not: showing the focus never moves the chips after it
 * (FocusLook's rule). [style] its words' size (the body's; the collection's filters the mockup's 13 sp), [role] what
 * TalkBack calls it, [dimmed] a chip with nothing in it, which can still be picked.
 */
@Composable
internal fun TvChip(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    style: TextStyle = AppType.body,
    role: Role? = null,
    dimmed: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val turned = interaction.turnsOver()
    val accent = colors.accent
    val bold = style.copy(fontWeight = AppType.bodyStrong.fontWeight)
    val measurer = rememberTextMeasurer()
    val boldWidth = with(LocalDensity.current) { remember(measurer, label, bold) { measurer.measure(label, bold, maxLines = 1).size.width }.toDp() }
    OwnedRipple {
        Text(
            label, style = if (selected || turned) bold else style, maxLines = 1, textAlign = TextAlign.Center,
            color = when {
                turned -> colors.background
                selected -> colors.accent
                else -> colors.textSecondary
            },
            modifier = modifier.heightIn(min = 40.dp).focusScale(interaction, { it.buttonScale }).clip(RoundedCornerShape(20.dp))
                .background(
                    when {
                        turned -> colors.text
                        selected -> colors.accentTint
                        else -> colors.chip
                    },
                )
                .drawWithContent {
                    drawContent()
                    // The selected chip's tint is gone while it is turned over: the line says it is the selected one.
                    if (selected && turned) {
                        val inset = 14.dp.toPx()
                        drawRect(accent, Offset(inset, size.height - 6.dp.toPx()), Size(size.width - 2 * inset, 2.dp.toPx()))
                    }
                }
                .semantics { this.selected = selected }
                .clickable(interaction, LocalIndication.current, role = role, onClick = onClick)
                .alpha(if (dimmed && !selected && !turned) DIMMED else 1f)
                .padding(horizontal = 16.dp, vertical = 10.dp).widthIn(min = boldWidth),
        )
    }
}

/** A chip with nothing in it, as the phone's dims it. */
private const val DIMMED = 0.5f
