package io.skjaere.usenet.app.ui

import androidx.compose.material.icons.materialIcon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes

// Drawn for the app, not Material's path data: no third-party notice.

/**
 * Download (D7): an arrow into a tray, for the Download sheet's button and the ways in. The notification's small icon,
 * res/drawable/ic_stat_download.xml, draws the same strokes.
 */
internal val DownloadIcon: ImageVector by lazy { stroked("Download", DOWNLOAD_PATH) }

/** Downloaded (D7): a tick over the tray. The finished download's notification icon, ic_stat_downloaded.xml, draws the same. */
internal val DownloadedIcon: ImageVector by lazy { stroked("Downloaded", DOWNLOADED_PATH) }

/** The arrow, then the tray, on Material's 24 dp grid, as drawn for the app. */
internal const val DOWNLOAD_PATH = "M12 3.5v10.5M7.5 9.5 12 14l4.5-4.5M4 14.5v3.5a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3.5"

/** The tray, then the tick. */
internal const val DOWNLOADED_PATH = "M4 14.5v3.5a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3.5M7.5 10.2l3.1 3.1 6-6.3"

/**
 * [path] in 2 dp lines with round ends and corners, so it weighs as the core set's filled icons do beside it: black, as
 * theirs are, for Icon's tint to colour it.
 */
private fun stroked(name: String, path: String): ImageVector = materialIcon(name) {
    addPath(
        addPathNodes(path), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}
