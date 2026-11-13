package io.skjaere.usenet.app

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import io.skjaere.usenet.player.DisplayFacts
import io.skjaere.usenet.player.formatRate
import io.skjaere.usenet.releases.Hdr

/**
 * File details' Display row as the platform says it, read apart from DisplayDetails, which fills the panel: the output
 * size Media3 reads for the default display, the refresh rate of the mode it runs, and the HDR it shows as DisplayFacts
 * reads it for the ranking. A broken read of the rate or the size in DisplayDetails then shows as a difference.
 */
@androidx.annotation.OptIn(UnstableApi::class)
fun platformDisplayRow(context: Context): List<String> {
    val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    val size = Util.getCurrentDisplayModeSize(context, display)
    val note = DisplayFacts.read(context).hdr?.let { types ->
        val shown = listOf(Hdr.DolbyVision, Hdr.Hdr10, Hdr.Hdr10Plus, Hdr.Hlg).filter { it in types }
        if (shown.isEmpty()) "can't show HDR" else "can show ${shown.joinToString { it.label }}"
    }
    return listOfNotNull("${size.x} × ${size.y} · ${formatRate(display.mode.refreshRate.toDouble())} Hz", note)
}
