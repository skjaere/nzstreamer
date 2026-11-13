package io.skjaere.usenet.app

import android.app.ActivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.player.tv.DeviceMemory
import io.skjaere.usenet.player.tv.PlaybackBudgets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A 4K remux's buffers outgrew a TV box's 192 MB heap class and the app ran out of memory: it asks
 * for the large heap (512 MB on the Shield, 576 MB on these emulators), and a play is sized from it.
 */
@RunWith(AndroidJUnit4::class)
class LargeHeapDeviceTest {
    @Test
    fun theAppRunsOnTheLargeHeapAndAPlayIsSizedFromIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activities = context.getSystemService(ActivityManager::class.java)
        val memory = DeviceMemory.of(context)
        assertTrue("the emulator gives a large heap: ${activities.largeMemoryClass} MB over ${activities.memoryClass} MB", activities.largeMemoryClass > activities.memoryClass)
        assertEquals(activities.largeMemoryClass * 1024L * 1024, memory.heapLimitBytes)
        assertEquals((memory.heapLimitBytes / 10).coerceIn(16L shl 20, 64L shl 20), PlaybackBudgets.of(memory, uhdDisplay = false).streamBytes)
    }
}
