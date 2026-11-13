package io.skjaere.usenet.player

import android.os.Build
import android.os.Debug
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import io.skjaere.usenet.core.GcStats
import io.skjaere.usenet.core.KeptFile
import io.skjaere.usenet.core.KeptFiles

/** ART's GC counters now ([Debug.getRuntimeStat]), for the stream-cost line ([io.skjaere.usenet.core.StreamCost]); null where the runtime has none. */
fun artGcStats(): GcStats? {
    fun stat(name: String) = Debug.getRuntimeStat(name)?.toLongOrNull()
    return GcStats(
        count = stat("art.gc.gc-count") ?: return null,
        timeMs = stat("art.gc.gc-time") ?: return null,
        blockingCount = stat("art.gc.blocking-gc-count") ?: return null,
        blockingTimeMs = stat("art.gc.blocking-gc-time") ?: return null,
        allocatedBytes = stat("art.gc.bytes-allocated") ?: return null,
    )
}

/** The kernel's clock ticks a second, the unit of /proc's CPU times. */
fun clockTicksPerSecond(): Long = Os.sysconf(OsConstants._SC_CLK_TCK).takeIf { it > 0 } ?: 100

/**
 * /proc files kept open through Android's own calls: a read is one pread, 3.5 µs a thread's stat on the emulator, where a
 * FileInputStream's open, read and close took 27 µs and a kept RandomAccessFile's seek and read 5.8 µs. The stat of a
 * thread that has ended reads as ESRCH. Close-on-exec where Android names the flag (API 27): before that its constant
 * isn't there, and the app runs no other program anyway.
 */
val androidKeptFiles = KeptFiles { path ->
    try {
        val closeOnExec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) OsConstants.O_CLOEXEC else 0
        val fd = Os.open(path, OsConstants.O_RDONLY or closeOnExec, 0)
        object : KeptFile {
            override fun readFromStart(buffer: ByteArray): Int = try {
                Os.pread(fd, buffer, 0, buffer.size, 0)
            } catch (e: ErrnoException) {
                -1
            }

            override fun close() {
                try {
                    Os.close(fd)
                } catch (e: ErrnoException) {
                    // Nothing was written: there is nothing to lose.
                }
            }
        }
    } catch (e: ErrnoException) {
        null
    }
}
