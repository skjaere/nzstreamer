package io.skjaere.usenet.nativecheck

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals

class NativeLibsCheckerTest {

    private val checker = NativeLibsChecker(
        requiredAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64"),
        requiredLibs = listOf("librapidyenc.so", "libjnidispatch.so"),
    )

    private fun abi(abi: String, vararg names: String) = names.map { NativeLib(abi, it, if ("64" in abi) ELF64_16K else ELF32_4K) }

    @Test
    fun `an APK with every library for every required ABI passes`() {
        val libs = listOf("arm64-v8a", "armeabi-v7a", "x86_64").flatMap { abi(it, "librapidyenc.so", "libjnidispatch.so") }
        assertEquals(emptyList(), checker.check(libs))
    }

    @Test
    fun `a required ABI the APK lacks is reported with the libraries it needs`() {
        val libs = abi("arm64-v8a", "librapidyenc.so", "libjnidispatch.so") + abi("x86_64", "librapidyenc.so", "libjnidispatch.so")
        assertEquals(listOf("lib/armeabi-v7a/ lacks libjnidispatch.so, librapidyenc.so"), checker.check(libs))
    }

    @Test
    fun `every ABI needs each library any ABI ships, not only the required ones`() {
        // The installer extracts one ABI directory per device; a library only another ABI carries never loads.
        val full = arrayOf("librapidyenc.so", "libjnidispatch.so", "libdatastore_shared_counter.so")
        val libs = abi("arm64-v8a", *full) + abi("x86_64", *full) + abi("armeabi-v7a", "librapidyenc.so", "libjnidispatch.so")
        assertEquals(listOf("lib/armeabi-v7a/ lacks libdatastore_shared_counter.so"), checker.check(libs))
    }

    @Test
    fun `an ABI outside the required list is checked for completeness too`() {
        // Without abiFilters JNA's AAR alone would add lib/x86/ and lib/armeabi/; a device of that ABI picks them.
        val libs = listOf("arm64-v8a", "armeabi-v7a", "x86_64").flatMap { abi(it, "librapidyenc.so", "libjnidispatch.so") } +
            abi("x86", "libjnidispatch.so")
        assertEquals(listOf("lib/x86/ lacks librapidyenc.so"), checker.check(libs))
    }

    @Test
    fun `a 64-bit library with 4 KB LOAD alignment is reported, a 32-bit one is not`() {
        val libs = listOf("arm64-v8a", "armeabi-v7a", "x86_64").flatMap { abi(it, "librapidyenc.so", "libjnidispatch.so") }
            .map { if (it.abi == "x86_64" && it.name == "librapidyenc.so") NativeLib(it.abi, it.name, ELF64_4K) else it }
        assertEquals(
            listOf("lib/x86_64/librapidyenc.so has LOAD segments aligned to 4096 bytes; 16 KB page devices need 16384"),
            checker.check(libs),
        )
    }

    @Test
    fun `a 64-bit library that is not an ELF file is reported`() {
        val libs = listOf("arm64-v8a", "armeabi-v7a", "x86_64").flatMap { abi(it, "librapidyenc.so", "libjnidispatch.so") }
            .map { if (it.abi == "arm64-v8a" && it.name == "libjnidispatch.so") NativeLib(it.abi, it.name, ByteArray(64)) else it }
        assertEquals(listOf("lib/arm64-v8a/libjnidispatch.so is not an ELF file"), checker.check(libs))
    }

    @Test
    fun `a library that still carries debug sections is reported, on every ABI`() {
        // AGP packages a library unstripped when the NDK it strips with is not installed.
        val unstripped = mapOf(
            "arm64-v8a" to elf(is64 = true, PT_LOAD to 0x4000L, sections = listOf(".text", ".debug_info", ".debug_line")),
            "armeabi-v7a" to elf(is64 = false, PT_LOAD to 0x1000L, sections = listOf(".text", ".debug_info")),
        )
        val libs = listOf("arm64-v8a", "armeabi-v7a", "x86_64").flatMap { abi(it, "librapidyenc.so", "libjnidispatch.so") }
            .map { lib -> unstripped[lib.abi]?.takeIf { lib.name == "librapidyenc.so" }?.let { NativeLib(lib.abi, lib.name, it) } ?: lib }
        assertEquals(
            listOf(
                "lib/arm64-v8a/librapidyenc.so is not stripped: .debug_info, .debug_line",
                "lib/armeabi-v7a/librapidyenc.so is not stripped: .debug_info",
            ),
            checker.check(libs),
        )
    }

    @Test
    fun `a stripped library with a symbol table passes`() {
        val stripped = elf(is64 = true, PT_LOAD to 0x4000L, sections = listOf(".dynsym", ".text", ".symtab", ".gnu_debugdata"))
        val libs = listOf("arm64-v8a", "armeabi-v7a", "x86_64").flatMap { abi(it, "librapidyenc.so", "libjnidispatch.so") }
            .map { if (it.abi == "arm64-v8a") NativeLib(it.abi, it.name, stripped) else it }
        assertEquals(emptyList(), checker.check(libs))
    }

    @Test
    fun `the ELF parser reads section names`() {
        assertEquals(listOf("", ".text", ".debug_info", ".shstrtab"), Elf.sectionNames(elf(is64 = true, sections = listOf(".text", ".debug_info"))))
        assertEquals(listOf("", ".text", ".shstrtab"), Elf.sectionNames(elf(is64 = false, PT_LOAD to 0x1000L, sections = listOf(".text"))))
        assertEquals(emptyList(), Elf.sectionNames(ELF64_16K))
    }

    @Test
    fun `a minimal ELF32 file, a bare 52-byte header, is read`() {
        val header = elf(is64 = false)
        assertEquals(52, header.size)
        assertEquals(emptyList(), Elf.loadAlignments(header))
        assertEquals(emptyList(), Elf.sectionNames(header))
    }

    @Test
    fun `the ELF parser reads the alignment of LOAD segments only`() {
        assertEquals(listOf(0x4000L, 0x4000L), Elf.loadAlignments(ELF64_16K))
        assertEquals(listOf(0x1000L, 0x1000L), Elf.loadAlignments(ELF32_4K))
        assertEquals(listOf(0x1000L), Elf.loadAlignments(elf(is64 = true, PT_PHDR to 8L, PT_LOAD to 0x1000L, PT_DYNAMIC to 8L)))
    }

    @Test
    fun `native libraries are read from the APK's lib directories only`(@TempDir dir: File) {
        val apk = File(dir, "app.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            mapOf(
                "lib/arm64-v8a/librapidyenc.so" to ELF64_16K,
                "lib/armeabi-v7a/librapidyenc.so" to ELF32_4K,
                "assets/lib/x86/libnot.so" to ELF32_4K,
                "lib/x86_64/README.txt" to ByteArray(1),
            ).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val libs = Apk.nativeLibs(apk)
        assertEquals(listOf("lib/arm64-v8a/librapidyenc.so", "lib/armeabi-v7a/librapidyenc.so"), libs.map { it.path })
        assertEquals(ELF64_16K.toList(), libs.first().bytes.toList())
    }

    private companion object {
        const val PT_LOAD = 1
        const val PT_DYNAMIC = 2
        const val PT_PHDR = 6

        val ELF64_16K = elf(is64 = true, PT_LOAD to 0x4000L, PT_LOAD to 0x4000L)
        val ELF64_4K = elf(is64 = true, PT_LOAD to 0x1000L, PT_LOAD to 0x1000L)
        val ELF32_4K = elf(is64 = false, PT_LOAD to 0x1000L, PT_LOAD to 0x1000L)

        /**
         * A little-endian ELF header followed by program headers of the given (p_type, p_align) and,
         * when [sections] are given, a section name table and section headers for them: the null
         * section first and `.shstrtab` last, like a linker writes them.
         */
        fun elf(is64: Boolean, vararg segments: Pair<Int, Long>, sections: List<String> = emptyList()): ByteArray {
            val headerSize = if (is64) 64 else 52
            val entrySize = if (is64) 56 else 32
            val sectionEntrySize = if (is64) 64 else 40
            val names = if (sections.isEmpty()) emptyList() else listOf("") + sections + ".shstrtab"
            val nameOffsets = names.runningFold(0) { offset, name -> offset + name.length + 1 }
            val nameTable = names.joinToString("") { "$it\u0000" }.toByteArray(Charsets.US_ASCII)
            val nameTableOffset = headerSize + entrySize * segments.size
            val sectionTableOffset = nameTableOffset + nameTable.size
            val buffer = ByteBuffer.allocate(sectionTableOffset + sectionEntrySize * names.size).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), if (is64) 2 else 1, 1, 1))
            if (is64) {
                buffer.putLong(0x20, headerSize.toLong()).putShort(0x36, entrySize.toShort()).putShort(0x38, segments.size.toShort())
            } else {
                buffer.putInt(0x1C, headerSize).putShort(0x2A, entrySize.toShort()).putShort(0x2C, segments.size.toShort())
            }
            segments.forEachIndexed { index, (type, align) ->
                val entry = headerSize + index * entrySize
                buffer.putInt(entry, type)
                if (is64) buffer.putLong(entry + 48, align) else buffer.putInt(entry + 28, align.toInt())
            }
            if (names.isNotEmpty()) {
                buffer.put(nameTableOffset, nameTable)
                if (is64) {
                    buffer.putLong(0x28, sectionTableOffset.toLong()).putShort(0x3A, sectionEntrySize.toShort())
                        .putShort(0x3C, names.size.toShort()).putShort(0x3E, names.lastIndex.toShort())
                } else {
                    buffer.putInt(0x20, sectionTableOffset).putShort(0x2E, sectionEntrySize.toShort())
                        .putShort(0x30, names.size.toShort()).putShort(0x32, names.lastIndex.toShort())
                }
                names.indices.forEach { index ->
                    val entry = sectionTableOffset + index * sectionEntrySize
                    buffer.putInt(entry, nameOffsets[index])
                    if (index == names.lastIndex) {
                        if (is64) {
                            buffer.putLong(entry + 0x18, nameTableOffset.toLong()).putLong(entry + 0x20, nameTable.size.toLong())
                        } else {
                            buffer.putInt(entry + 0x10, nameTableOffset).putInt(entry + 0x14, nameTable.size)
                        }
                    }
                }
            }
            return buffer.array()
        }
    }
}
