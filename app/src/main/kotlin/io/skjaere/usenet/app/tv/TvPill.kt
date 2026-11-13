package io.skjaere.usenet.app.tv

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.OwnedRipple
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusScale
import io.skjaere.usenet.app.ui.turnsOver

/**
 * A TV's pill (the mockup's 40 dp, the chip's fill), turned over and grown a little when focused; [said] what TalkBack
 * hears in place of its words, [icon] before them (alone when there are none).
 */
@Composable
internal fun TvPill(
    text: String,
    modifier: Modifier,
    style: TextStyle = AppType.body,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    said: String? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val turned = interaction.turnsOver()
    val words = when {
        turned -> colors.background
        enabled -> colors.text
        else -> colors.muted
    }
    OwnedRipple {
        Row(
            modifier.heightIn(min = 40.dp).focusScale(interaction, { it.buttonScale }).clip(RoundedCornerShape(20.dp))
                .background(if (turned) colors.text else colors.chip)
                .then(said?.let { s -> Modifier.semantics { contentDescription = s } } ?: Modifier)
                .clickable(interaction, LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = if (text.isEmpty()) 10.dp else 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            icon?.let { Icon(it, contentDescription = null, tint = words, modifier = Modifier.size(if (text.isEmpty()) 20.dp else 16.dp)) }
            if (text.isNotEmpty()) Text(text, style = style, color = words, maxLines = 1)
        }
    }
}
