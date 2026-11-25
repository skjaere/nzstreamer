package app.nzstreamer.plugin.sportarr.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The Sports tab's icon, a cup: Material's "emoji_events" (a trophy), drawn here because a plugin has only Material's core
 * icons, and plugin-check refuses an icon library (L625). It is Google's, under the Apache License 2.0, as the app's other
 * drawn icons are. The bar and the rail tint it.
 */
internal val CupIcon: ImageVector = ImageVector.Builder("Cup", 24.dp, 24.dp, 24f, 24f).addPath(
    addPathNodes(
        "M19,5h-2V3H7v2H5c-1.1,0 -2,0.9 -2,2v1c0,2.55 1.92,4.63 4.39,4.94 0.63,1.5 1.98,2.63 3.61,2.96V19H7v2h10v-2h-4v-3.1" +
            "c1.63,-0.33 2.98,-1.46 3.61,-2.96C19.08,12.63 21,10.55 21,8V7c0,-1.1 -0.9,-2 -2,-2z" +
            "M5,8V7h2v3.82C5.84,10.4 5,9.3 5,8z" +
            "M19,8c0,1.3 -0.84,2.4 -2,2.82V7h2v1z",
    ),
    fill = SolidColor(Color.Black),
).build()
