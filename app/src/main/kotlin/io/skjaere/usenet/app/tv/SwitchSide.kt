package io.skjaere.usenet.app.tv

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.OwnedRipple
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusScale
import io.skjaere.usenet.app.ui.turnsOver

/**
 * One side of the switch: the selected one tinted in the accent, turned over when focused; OK selects it. Its words are in
 * bold then, and it is as wide as they are in bold whether it is or not: showing the focus never moves the chips after it.
 */
@Composable
internal fun SwitchSide(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val turned = interaction.turnsOver()
    val bold = AppType.bodyStrong
    val measurer = rememberTextMeasurer()
    val boldWidth = with(LocalDensity.current) { remember(measurer, label, bold) { measurer.measure(label, bold, maxLines = 1).size.width }.toDp() }
    OwnedRipple {
        Box(
            modifier.heightIn(min = 36.dp).focusScale(interaction, { it.buttonScale }).clip(RoundedCornerShape(18.dp))
                .background(
                    when {
                        turned -> colors.text
                        selected -> colors.accentTint
                        else -> Color.Transparent
                    },
                )
                .selectable(selected, interaction, LocalIndication.current, role = Role.Tab, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label, style = if (selected || turned) bold else AppType.body, maxLines = 1, textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = boldWidth),
                color = when {
                    turned -> colors.background
                    selected -> colors.accent
                    else -> colors.textSecondary
                },
            )
        }
    }
}
