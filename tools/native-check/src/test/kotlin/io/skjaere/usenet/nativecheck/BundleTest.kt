package io.skjaere.usenet.nativecheck

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals

/** An App Bundle (.aab) carries each module's libraries at `<module>/lib/<abi>/`; Play cuts the per-ABI APKs from them. */
class BundleTest {
    private val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
    private val libs = listOf("librapidyenc.so", "libjnidispatch.so")

    private fun bundle(dir: File, entries: Map<String, ByteArray>): File = File(dir, "app.aab").also { aab ->
        ZipOutputStream(aab.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun complete(module: String = "base") =
        abis.flatMap { abi -> libs.map { "$module/lib/$abi/$it" to if ("64" in abi) ELF64_16K else ELF32_4K } }.toMap()

    @Test
    fun `native libraries are read from each module's lib directories only`(@TempDir dir: File) {
        val aab = bundle(
            dir,
            mapOf(
                "BundleConfig.pb" to ByteArray(1),
                "base/manifest/AndroidManifest.xml" to ByteArray(1),
                "base/lib/arm64-v8a/librapidyenc.so" to ELF64_16K,
                "base/assets/lib/x86/libnot.so" to ELF32_4K,
                "base/lib/x86_64/README.txt" to ByteArray(1),
                "lib/armeabi-v7a/libtop.so" to ELF32_4K,
                "feature/lib/armeabi-v7a/libfeature.so" to ELF32_4K,
            ),
        )
        val modules = Bundle.nativeLibs(aab)
        assertEquals(setOf("base", "feature"), modules.keys)
        assertEquals(listOf("base/lib/arm64-v8a/librapidyenc.so"), modules.getValue("base").map { it.path })
        assertEquals(listOf("feature/lib/armeabi-v7a/libfeature.so"), modules.getValue("feature").map { it.path })
        assertEquals(ELF64_16K.toList(), modules.getValue("base").single().bytes.toList())
    }

    @Test
    fun `a bundle with every library for every ABI passes`(@TempDir dir: File) {
        val report = File(dir, "report.txt")
        val code = run(arrayOf("--aab", bundle(dir, complete()).path, "--report", report.path) + requirements())
        assertEquals(0, code, report.readText())
        assertEquals(true, report.readText().startsWith("app.aab base: every ABI carries every native library"), report.readText())
    }

    @Test
    fun `a bundle's problems name the module they are in`(@TempDir dir: File) {
        val entries = complete().filterKeys { !it.startsWith("base/lib/armeabi-v7a/") } +
            ("base/lib/x86_64/librapidyenc.so" to ELF64_4K)
        val report = File(dir, "report.txt")
        assertEquals(1, run(arrayOf("--aab", bundle(dir, entries).path, "--report", report.path) + requirements()))
        val lines = report.readLines()
        assertEquals("app.aab base: native library problems:", lines.first())
        assertEquals(
            listOf(
                "  base/lib/armeabi-v7a/ lacks libjnidispatch.so, librapidyenc.so",
                "  base/lib/x86_64/librapidyenc.so has LOAD segments aligned to 4096 bytes; 16 KB page devices need 16384",
            ),
            lines.drop(1).takeWhile { it.startsWith("  ") },
        )
    }

    @Test
    fun `a bundle without native libraries lacks them all`(@TempDir dir: File) {
        val report = File(dir, "report.txt")
        assertEquals(1, run(arrayOf("--aab", bundle(dir, mapOf("base/manifest/AndroidManifest.xml" to ByteArray(1))).path, "--report", report.path) + requirements()))
        assertEquals(
            abis.map { "  base/lib/$it/ lacks libjnidispatch.so, librapidyenc.so" },
            report.readLines().drop(1).takeWhile { it.startsWith("  ") },
        )
    }

    private fun requirements() = (abis.flatMap { listOf("--abi", it) } + libs.flatMap { listOf("--lib", it) }).toTypedArray()

    private companion object {
        const val PT_LOAD = 1
        val ELF64_16K = elf(is64 = true, 0x4000L)
        val ELF64_4K = elf(is64 = true, 0x1000L)
        val ELF32_4K = elf(is64 = false, 0x1000L)

        /** A little-endian ELF header and one LOAD program header aligned to [align]. */
        fun elf(is64: Boolean, align: Long): ByteArray {
            val headerSize = if (is64) 64 else 52
            val entrySize = if (is64) 56 else 32
            val buffer = ByteBuffer.allocate(headerSize + entrySize).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), if (is64) 2 else 1, 1, 1))
            if (is64) {
                buffer.putLong(0x20, headerSize.toLong()).putShort(0x36, entrySize.toShort()).putShort(0x38, 1)
                buffer.putInt(headerSize, PT_LOAD).putLong(headerSize + 48, align)
            } else {
                buffer.putInt(0x1C, headerSize).putShort(0x2A, entrySize.toShort()).putShort(0x2C, 1)
                buffer.putInt(headerSize, PT_LOAD).putInt(headerSize + 28, align.toInt())
            }
            return buffer.array()
        }
    }
}
