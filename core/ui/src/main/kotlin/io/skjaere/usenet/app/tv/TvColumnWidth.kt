package io.skjaere.usenet.app.tv

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import io.skjaere.usenet.app.ui.TvSafeArea

/**
 * The width of the TV shell's content beside the rail. The phone's screens take a centred column of it
 * ([TvSafeArea.sharedMax]); a screen laid out for a TV's width (Search's two panes) takes it all. Unspecified outside
 * the shell, and until it is laid out.
 */
val LocalTvColumnWidth = compositionLocalOf { Dp.Unspecified }

/**
 * A screen laid out for a TV's width takes the shell's whole content column ([LocalTvColumnWidth]: 864 dp on a page,
 * 784 dp beside the rail), not the phone screens' centred [TvSafeArea.sharedMax]; until it is laid out, the width it has.
 */
fun Modifier.tvFullWidth(): Modifier = composed {
    val column = LocalTvColumnWidth.current
    if (column.isSpecified) requiredWidth(column) else fillMaxWidth()
}
