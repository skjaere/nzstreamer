package io.skjaere.usenet.player.tv

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * What the device has for a play to use: whether the system calls it low on RAM, the app's heap
 * class (ActivityManager's memoryClass, in MB), the RAM, and whether the app runs as a 64-bit
 * process, with the device's ABIs; and the heap the process may grow to ([heapLimitBytes]: the
 * large heap the manifest asks for where the device gives one). Read once ([of]); [PlaybackBudgets]
 * sizes a play from it.
 */
data class DeviceMemory(
    val lowRam: Boolean,
    val memoryClassMb: Int,
    val totalRamBytes: Long,
    val process64Bit: Boolean,
    val abis: List<String>,
    val heapLimitBytes: Long = memoryClassMb * 1024L * 1024,
) {
    companion object {
        /** Asks the system, a call to another process: off the main thread. */
        fun of(context: Context): DeviceMemory {
            val activities = context.getSystemService(ActivityManager::class.java)
            val ram = ActivityManager.MemoryInfo().also(activities::getMemoryInfo)
            return DeviceMemory(
                activities.isLowRamDevice, activities.memoryClass, ram.totalMem, Process.is64Bit(), Build.SUPPORTED_ABIS.toList(),
                Runtime.getRuntime().maxMemory(),
            )
        }
    }
}
