package io.skjaere.usenet.player

import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.player.tv.ScreenModes
import io.skjaere.yenc.RapidYenc
import io.skjaere.yenc.RapidYencKernel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The facts This device and the log header read, from the platform's own calls on the device it runs on: the display's
 * modes and its frame-rate matching, rapidyenc's version and kernels, and the decoders' summary; and the modes with
 * their ids as a frame-rate switch reads them. Each is logged under [TAG] for the record of what a device answers.
 */
@RunWith(AndroidJUnit4::class)
class DeviceFactsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theDisplaysModesAreItsRatesAtTheSizeItRuns() {
        val modes = DisplayModes.read(context)
        Log.i(TAG, "$modes")

        val size = modes.size
        assertNotNull("the default display's mode", size)
        assertTrue("$modes", size!!.width > 0 && size.height > 0)
        assertTrue("$modes", modes.rates.isNotEmpty() && modes.rates == modes.rates.sorted())
        val now = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).mode.refreshRate
        assertTrue("$modes runs at $now Hz", modes.rates.any { abs(it - now) < 0.01f })
        if (Build.VERSION.SDK_INT >= 31) assertNotNull("$modes", modes.matchContentFrameRate) else assertNull("$modes", modes.matchContentFrameRate)
    }

    /** The ids a window's preferredDisplayModeId names, read as a switch reads them: the mode it runs is among those listed. */
    @Test
    fun theScreenModesListTheModeItRunsWithItsId() {
        val screen = ScreenModes.read(context)
        Log.i(TAG, "$screen")

        val mode = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).mode
        val active = screen.active
        assertNotNull("the default display's mode", active)
        assertEquals(mode.modeId, active!!.id)
        assertEquals(ModeSize(mode.physicalWidth, mode.physicalHeight), active.size)
        assertTrue("$screen", active in screen.modes)
        assertEquals(Build.VERSION.SDK_INT, screen.sdk)
        assertEquals(DisplayFacts.read(context).output, screen.output)
        if (Build.VERSION.SDK_INT >= 31) assertNotNull("$screen", screen.matchContentFrameRate) else assertNull("$screen", screen.matchContentFrameRate)
    }

    @Test
    fun rapidyencSaysItsVersionAndKernels() = runBlocking<Unit> {
        val facts = NativeFactsLoader().get()
        Log.i(TAG, "$facts")

        val loaded = facts as NativeFacts.Loaded
        assertEquals(RapidYenc.version().toList().joinToString("."), loaded.version)
        assertNotEquals(RapidYencKernel.UNKNOWN, loaded.decode)
        assertNotEquals(RapidYencKernel.UNKNOWN, loaded.crc)
    }

    @Test
    fun theDecoderSummaryIsWhatTheCapabilitiesAnswer() {
        val capabilities = MediaCodecDeviceCapabilities(context)
        val summary = capabilities.decoderSummary
        Log.i(TAG, "$summary")

        assertEquals(capabilities.decoderLines, summary.lines)
        summary.video.forEach { decoding ->
            assertEquals("$decoding", capabilities.maxHeight(decoding.codec, decoding.tenBit), decoding.maxHeight)
            assertEquals("$decoding", capabilities.hardwareMaxHeight(decoding.codec, decoding.tenBit), decoding.hardwareMaxHeight)
        }
        assertEquals(capabilities.dolbyVisionProfiles, summary.dolbyVisionProfiles.map { it.codecs })
        assertTrue("$summary", summary.noDecoder.none { codec -> summary.video.any { it.codec == codec } })
    }

    private companion object {
        const val TAG = "DeviceFacts"
    }
}
