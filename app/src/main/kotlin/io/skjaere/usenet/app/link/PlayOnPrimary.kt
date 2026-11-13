package io.skjaere.usenet.app.link

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.skjaere.usenet.app.casting.connectingTo
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.colors

/**
 * Play, Resume or Play again where Play on says (PtPhoneWhere B, PtPhoneMovie, CsPhoneConnecting): on This phone as
 * before, ▶ and [label]; with a TV or a Cast device chosen, its icon in place of ▶ (Google's Cast icon for a Cast device)
 * and "on Living room TV" under the words; greyed while that TV's NZStreamer isn't open, with "Open NZStreamer on Living
 * room TV" under the button, or while the Cast device connects, with "Connecting to Kitchen display…" (L97). Both light
 * up by themselves once it opens or connects.
 */
@Composable
fun PlayOnPrimary(label: String, target: PlayTarget, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val lead: ImageVector = when (target) {
        PlayTarget.Phone -> Icons.Filled.PlayArrow
        is PlayTarget.Tv -> TvIcon
        is PlayTarget.Cast -> CastConnectedIcon
    }
    val waiting: String? = when (target) {
        PlayTarget.Phone -> null
        is PlayTarget.Tv -> openNzStreamerOn(target.name)
        is PlayTarget.Cast -> connectingTo(target.name)
    }?.takeIf { target.greyed }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PrimaryButton(
            label, onClick, modifier, enabled = !target.greyed, second = target.deviceName?.let(::onTvLine),
            leading = { Icon(lead, contentDescription = null, modifier = Modifier.padding(end = 6.dp)) },
        )
        waiting?.let { Text(it, style = AppType.small, color = if (target is PlayTarget.Tv) colors.warn else colors.muted) }
    }
}
