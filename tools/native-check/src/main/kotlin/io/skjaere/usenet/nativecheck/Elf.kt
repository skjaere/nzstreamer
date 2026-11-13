package io.skjaere.usenet.nativecheck

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Just enough of an ELF reader for the program and section headers of Android's little-endian libraries. */
object Elf {
    private const val MAGIC = 0x464C457F // "\u007FELF" read little-endian
    private const val PT_LOAD = 1

    /** The p_align of each PT_LOAD segment, in program header order. */
    fun loadAlignments(elf: ByteArray): List<Long> {
        val header = Header(elf)
        return header.entries(header.address(0x20, 0x1C), header.half(0x36, 0x2A), header.half(0x38, 0x2C), 56, 32, "program headers")
            .filter { header.buffer.getInt(it) == PT_LOAD }
            .map { header.address(it + 48, it + 28) }
    }

    /** The name of each section, in section header order; empty when the file has no section headers. */
    fun sectionNames(elf: ByteArray): List<String> {
        val header = Header(elf)
        val entries = header.entries(header.address(0x28, 0x20), header.half(0x3A, 0x2E), header.half(0x3C, 0x30), 64, 40, "section headers")
        if (entries.isEmpty()) return emptyList()
        val namesEntry = entries.getOrNull(header.half(0x3E, 0x32))
        require(namesEntry != null) { "has no section name table" }
        val namesStart = header.address(namesEntry + 0x18, namesEntry + 0x10)
        val namesEnd = namesStart + header.address(namesEntry + 0x20, namesEntry + 0x14)
        require(namesStart >= 0 && namesEnd in namesStart..elf.size) { "has a section name table past the end of the file" }
        return entries.map { entry ->
            val start = namesStart + header.buffer.getInt(entry).toUInt().toLong()
            require(start < namesEnd) { "has a section name outside the section name table" }
            val end = (start until namesEnd).firstOrNull { elf[it.toInt()] == 0.toByte() } ?: namesEnd
            String(elf, start.toInt(), (end - start).toInt(), Charsets.US_ASCII)
        }
    }

    private class Header(private val elf: ByteArray) {
        val buffer: ByteBuffer = ByteBuffer.wrap(elf).order(ByteOrder.LITTLE_ENDIAN)
        private val is64: Boolean

        init {
            require(elf.size >= 16 && buffer.getInt(0) == MAGIC) { "is not an ELF file" }
            require(elf[5].toInt() == 1) { "is not a little-endian ELF file" }
            require(elf[4].toInt() in 1..2) { "has an unknown ELF class ${elf[4]}" }
            is64 = elf[4].toInt() == 2
            require(elf.size >= if (is64) 64 else 52) { "is not an ELF file" }
        }

        /** An address or size field: 8 bytes at [offset64] in ELF64, 4 bytes at [offset32] in ELF32. */
        fun address(offset64: Int, offset32: Int): Long =
            if (is64) buffer.getLong(offset64) else buffer.getInt(offset32).toUInt().toLong()

        fun half(offset64: Int, offset32: Int): Int = buffer.getShort(if (is64) offset64 else offset32).toUShort().toInt()

        /** The file offset of each entry of a header table whose entries are at least [minSize64] / [minSize32] bytes. */
        fun entries(tableOffset: Long, entrySize: Int, count: Int, minSize64: Int, minSize32: Int, what: String): List<Int> {
            require(count == 0 || entrySize >= if (is64) minSize64 else minSize32) { "has $what of $entrySize bytes" }
            require(tableOffset >= 0 && tableOffset + entrySize.toLong() * count <= elf.size) { "has $what past the end of the file" }
            return (0 until count).map { (tableOffset + it.toLong() * entrySize).toInt() }
        }
    }
}
