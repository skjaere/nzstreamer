package io.skjaere.usenet.app

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.logging.LogBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What This device's facts put in the log, on the device it runs on: a shared log's header says the device's kind, its
 * play budget, its display's modes and rapidyenc after the Memory line, once the start has read them; and the start
 * logs the header's lines the decoder loader doesn't, once each.
 */
@RunWith(AndroidJUnit4::class)
class DeviceFactsLogDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container

    /**
     * The header once the start has read every fact it adds. Not the first with a Native line: the start reads the
     * modes and the kind after rapidyenc, so a header can have rapidyenc's line a moment before theirs.
     */
    private fun header(): List<String> {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            val lines = container.logHeader.lines()
            if (START_LINES.all { start -> lines.any { it.startsWith(start) } }) return lines
            Thread.sleep(100)
        }
        throw AssertionError("the header never said every fact the start reads:\n${container.logHeader}")
    }

    @Test
    fun theHeaderSaysTheKindTheBudgetTheModesAndTheNativeLibraryAfterTheMemory() {
        val lines = header()
        val memory = lines.indexOfFirst { it.startsWith("Memory: ") }
        assertTrue(lines.joinToString("\n"), memory == 2)
        val tv = FormFactor.of(context) == FormFactor.Tv
        assertEquals(lines.joinToString("\n"), "Kind: ${if (tv) "TV" else "phone or tablet"}", lines[memory + 1])
        assertTrue(lines.joinToString("\n"), lines[memory + 2].startsWith("Budget: player buffer "))
        assertTrue(lines.joinToString("\n"), lines[memory + 3].startsWith("Display modes: ") && " Hz at " in lines[memory + 3])
        val native = lines[memory + 4]
        assertTrue(lines.joinToString("\n"), native.startsWith("Native: rapidyenc 1.1.1, decode ") && ", CRC " in native)
        // The display and the decoders follow, as before.
        assertTrue(lines.joinToString("\n"), lines[memory + 5].startsWith("Display: ") && lines[memory + 6].startsWith("Video: ") && lines[memory + 7].startsWith("Audio: "))
        assertEquals("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) on ${Build.MANUFACTURER} ${Build.MODEL}".trim(), lines[1])
    }

    @Test
    fun theStartLogsTheHeadersOtherLinesOnceEach() {
        val header = header()
        val logged = header.filterNot { it.startsWith("Display: ") || it.startsWith("Video: ") || it.startsWith("Audio: ") }
        val deadline = SystemClock.uptimeMillis() + 10_000
        var lines = LogBuffer.snapshot()
        // The start's lines may have been pushed out by the time a long run gets here, or cleared by a test of Logs' Clear.
        assumeTrue("the log no longer holds the start's lines", lines.firstOrNull()?.seq == 1L)
        while (SystemClock.uptimeMillis() < deadline && !lines.any { it.message.startsWith("Native: ") }) {
            Thread.sleep(100)
            lines = LogBuffer.snapshot()
        }
        val messages = lines.filter { it.tag == "AppContainer" }.map { it.message }
        // Each of the header's lines but the loader's, as the header says it now (nothing changed since the start).
        logged.filterNot { it.startsWith("Display modes: ") }.forEach { line ->
            assertEquals("\"$line\" in the log: $messages", 1, messages.count { it == line })
        }
        assertEquals("$messages", 1, messages.count { it.startsWith("Display modes: ") })
    }

    private companion object {
        /** The lines the start's reads add to the header: the kind, the play budget, the display's modes and rapidyenc. */
        val START_LINES = listOf("Kind: ", "Budget: ", "Display modes: ", "Native: ")
    }
}
