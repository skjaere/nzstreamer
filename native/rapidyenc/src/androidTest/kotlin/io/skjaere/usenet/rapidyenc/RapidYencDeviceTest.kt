package io.skjaere.usenet.rapidyenc

import android.os.Build
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.skjaere.yenc.RapidYenc
import io.skjaere.yenc.RapidYencDecoderEnd
import io.skjaere.yenc.RapidYencDecoderState
import io.skjaere.yenc.RapidYencKernel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlin.random.Random

/**
 * Proves the NDK-built librapidyenc.so loads through JNA on a device and that the
 * exact entry points ktor-nntp-client uses (direct-buffer incremental decode + CRC32)
 * behave like the JVM build.
 */
@RunWith(AndroidJUnit4::class)
class RapidYencDeviceTest {

    @Test
    fun nativeLibraryLoadsAndReportsVersion() {
        val (major, minor, patch) = RapidYenc.version()
        Log.i(TAG, "rapidyenc $major.$minor.$patch on ${Build.SUPPORTED_ABIS.first()}")
        assertTrue(major >= 0 && minor >= 0 && patch >= 0)
    }

    @Test
    fun byteArrayRoundTripOneMiB() {
        val data = Random(42).nextBytes(1 shl 20)
        val decoded = RapidYenc.decode(RapidYenc.encode(data))
        assertArrayEquals(data, decoded)
    }

    @Test
    fun directBufferIncrementalDecodeAndCrcMatchJdk() {
        val data = Random(7).nextBytes(300_000)
        val article = RapidYenc.encode(data) + "\r\n=yend size=${data.size}\r\n".toByteArray(Charsets.US_ASCII)

        val src = ByteBuffer.allocateDirect(article.size).apply { put(article); flip() }
        val dest = ByteBuffer.allocateDirect(data.size + 64)

        val result = RapidYenc.decodeIncremental(src, dest, RapidYencDecoderState.CRLF)
        assertEquals(RapidYencDecoderEnd.CONTROL, result.end)
        assertEquals(data.size.toLong(), result.bytesWritten)

        dest.flip()
        val crc = RapidYenc.crc32(dest, length = dest.remaining())
        val decoded = ByteArray(dest.remaining()).also { dest.get(it) }

        assertArrayEquals(data, decoded)
        assertEquals(CRC32().apply { update(data) }.value.toUInt(), crc)
    }

    @Test
    fun simdKernelsSelected() {
        val encode = RapidYenc.encodeKernel()
        val decode = RapidYenc.decodeKernel()
        val crc = RapidYenc.crcKernel()
        Log.i(TAG, "kernels encode=$encode decode=$decode crc=$crc")
        assertNotEquals(RapidYencKernel.GENERIC, encode)
        assertNotEquals(RapidYencKernel.GENERIC, decode)
    }

    /**
     * #210: rapidyenc picks its kernels by asking the processor at run time. The processor's own list in
     * /proc/cpuinfo says what it has, so a processor without CRC32 instructions (an ARMv7-only box) may
     * keep the generic CRC, while one that lists them must not.
     */
    @Test
    fun kernelsMatchWhatTheProcessorLists() {
        val abi = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS.first() else Build.SUPPORTED_32_BIT_ABIS.first()
        val features = cpuFeatures()
        val decode = RapidYenc.decodeKernel()
        val crc = RapidYenc.crcKernel()
        Log.i(TAG, "$abi process; kernels decode=$decode crc=$crc; the processor lists ${features.sorted().joinToString(" ")}")
        assertTrue("/proc/cpuinfo lists no features", features.isNotEmpty())

        val wrong = buildList {
            if (abi.startsWith("arm")) {
                val simd = listOf("neon", "asimd").filter { it in features }
                if (simd.isNotEmpty() && decode != RapidYencKernel.NEON) {
                    add("decode is $decode, though the processor lists ${simd.joinToString()}")
                }
                if ("crc32" in features && crc != RapidYencKernel.ARMCRC && crc != RapidYencKernel.ARMPMULL) {
                    add("CRC32 is $crc, though the processor lists crc32")
                }
            } else if (abi.startsWith("x86")) {
                if (decode == RapidYencKernel.GENERIC) add("decode is GENERIC on x86")
                // rapidyenc's folding CRC needs all three (platform.cc's cpu_supports_crc_isa).
                val clmul = features.containsAll(listOf("pclmulqdq", "sse4_1", "ssse3"))
                if (clmul && crc != RapidYencKernel.PCLMUL && crc != RapidYencKernel.VPCLMUL) {
                    add("CRC32 is $crc, though the processor lists pclmulqdq, sse4_1 and ssse3")
                }
            }
        }
        assertTrue("On this $abi process: ${wrong.joinToString("; ")}", wrong.isEmpty())
    }

    /**
     * The features every core lists ("Features" on ARM, "flags" on x86), so the check never expects an
     * instruction one core lacks.
     */
    private fun cpuFeatures(): Set<String> =
        File("/proc/cpuinfo").readLines()
            .filter { it.substringBefore(':').trim().lowercase() in setOf("features", "flags") }
            .map { line -> line.substringAfter(':').trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet() }
            .reduceOrNull { all, cpu -> all intersect cpu }
            .orEmpty()

    private companion object {
        const val TAG = "RapidYencDeviceTest"
    }
}
