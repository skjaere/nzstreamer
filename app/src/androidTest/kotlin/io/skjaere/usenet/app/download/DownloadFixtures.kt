package io.skjaere.usenet.app.download

import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.compressionutils.generation.ArchiveGenerator
import io.skjaere.compressionutils.generation.ContainerType
import io.skjaere.usenet.app.LocalNntp
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/**
 * The downloads' device tests' releases (T4): [seeded] bytes of 16 to 64 MB, or the test assets' [media] where a test
 * plays it, posted on a server in the test's process ([local]) as an indexer's NZB lists them: the file itself ([Packing.Plain]),
 * a split stored RAR4 set or a split 7z (Copy), the archives made by kotlin-compression-utils' ArchiveGenerator, as
 * DiscCopyDeviceTest's are. Encrypted RAR5 is the library's to cover (the generator makes none).
 *
 * The articles live in [local]'s memory and go with it; what a test writes beside them ([file], a download's file) goes in
 * a folder of the fixtures' own in the app's private storage. [close] ends both. A message id names its article's bytes
 * (a digest of what was posted under it), so a segment the video cache kept from an earlier run is never taken for another.
 */
class DownloadFixtures(val local: LocalNntp = LocalNntp()) : Closeable {
    /** How a release's file is posted. */
    enum class Packing { Plain, Rar4, SevenZip }

    /**
     * A release as posted: its [title] (the release's name, as an indexer lists it), the one file in it ([fileName], the
     * name prepare lists it by) holding [source], what went on the server for it ([posted]: the file itself, or the
     * archive's volumes in their order), and the [nzb] an indexer would hand over.
     */
    class Release(
        val packing: Packing,
        val title: String,
        val fileName: String,
        val source: ByteArray,
        val posted: List<LocalNntp.Posted>,
        val nzb: ByteArray,
    ) {
        val size: Long get() = source.size.toLong()

        /** The source's SHA-256, in hex: what a whole download's file must have. */
        val sha256: String by lazy { sha256(source) }

        /** Every article's id, volume after volume, each in its order. */
        val ids: List<String> get() = posted.flatMap { it.ids }
    }

    private val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "download-fixtures")

    /**
     * Posts [source] as [title]'s video, `<title>.mkv`, [packing] as said: in articles of [partSize] bytes, an archive in
     * volumes of [volumeSize] bytes at most, named as such sets are (`.rar`, `.r00`, …; `.7z.001`, …).
     */
    fun post(packing: Packing, title: String, source: ByteArray, partSize: Int = PART, volumeSize: Long = VOLUME): Release {
        val fileName = "$title.mkv"
        val files = when (packing) {
            Packing.Plain -> listOf(fileName to source)
            Packing.Rar4 -> ArchiveGenerator.generate(source, volumeSize, ContainerType.RAR4, fileName)
                .mapIndexed { i, volume -> (if (i == 0) "$title.rar" else "$title.r%02d".format(i - 1)) to volume.data }
            Packing.SevenZip -> ArchiveGenerator.generate(source, volumeSize, ContainerType.SEVENZIP, fileName)
                .mapIndexed { i, volume -> "$title.7z.%03d".format(i + 1) to volume.data }
        }
        val posted = files.map { (name, data) -> local.post(name, data, partSize, tag(name, data, partSize)) }
        return Release(packing, title, fileName, source, posted, local.nzb(*posted.toTypedArray()))
    }

    /** A file a test writes, in the fixtures' own folder (made if new), deleted by [close]. */
    fun file(name: String): File = File(folder.apply { mkdirs() }, name)

    /** Ends the server, its articles with it, and deletes what the test wrote beside them. */
    override fun close() {
        local.close()
        folder.deleteRecursively()
    }

    companion object {
        const val MB = 1_000_000

        /** The seeded sizes the fixtures take: 16 to 64 MB, the device tests' largest file (L367). */
        val SEEDED = 16 * MB..64 * MB

        /** Articles of 512 KiB, about a poster's (750 KB), so a file of 16 MB has 31 of them. */
        const val PART = 512 * 1024

        /** Volumes of 5 MB at most: a 16 MB file is split in four, each ending part way through its last article. */
        const val VOLUME = 5_000_000L

        /** [size] bytes of [seed]'s: the same for a seed on every run and every device. */
        fun seeded(size: Int, seed: Long): ByteArray {
            require(size in SEEDED) { "Seeded fixtures are 16 to 64 MB, not $size bytes" }
            return Random(seed).nextBytes(size)
        }

        /** The test assets' [name], a film the player plays. */
        fun media(name: String): ByteArray = InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n == -1) break
                    digest.update(chunk, 0, n)
                }
            }
            return digest.digest().hex()
        }

        /** Its articles' tag: their name, bytes and size as a digest, so their ids are another's whenever their bytes are. */
        private fun tag(name: String, data: ByteArray, partSize: Int): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(name.toByteArray())
            digest.update(data)
            return "dl-${digest.digest().hex().take(16)}-$partSize"
        }

        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}
