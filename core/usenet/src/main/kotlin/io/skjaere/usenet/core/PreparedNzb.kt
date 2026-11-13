package io.skjaere.usenet.core

import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.nzb.FilenameResolver
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.nzb.ObfuscationHeuristic
import io.skjaere.nzbstreamer.stream.StreamableFile
import io.skjaere.nzbstreamer.stream.toSplits
import java.security.MessageDigest

/** A file of a prepared NZB, as nzb-streamer streams it ([file]), at its place in the NZB's list ([index]). */
data class FileCandidate(
    override val index: Int,
    val file: StreamableFile,
) : ListedFile {
    override val path: String get() = file.path
    override val size: Long get() = file.totalSize
}

/** An NZB that has been enriched and listed, ready to stream. */
class PreparedNzb(
    override val id: String,
    override val title: String,
    val metadata: ExtractedMetadata,
    val candidates: List<FileCandidate>,
) : FileListing<FileCandidate> {
    override val files: List<FileCandidate> get() = candidates

    /**
     * The file a play picked, as any listing finds it ([FileListing.find]). A position alone can
     * name another file once the NZB is prepared again: prepare leaves out a file that lacks its
     * first article while another stands in (library issue N15), and another nzb-streamer can list
     * an NZB's files otherwise (#179). A file of the name but another size isn't taken for it: that
     * holds while nzb-streamer gives a file the same size ([StreamableFile.totalSize]) from one
     * version to the next, as it does now. When none has the name, it may be the file at [index]
     * under another ([renamed]).
     */
    override fun find(index: Int, name: String?, size: Long?): FileCandidate? {
        if (name != null && candidates.none { it.name == name }) return renamed(index, name, size)
        return candidates.named(index, name, size)
    }

    /**
     * The file at [index], taken for the one named [name] of [size] bytes that no file has the name
     * of, when it is as big, no other file is, and one of the two names is obfuscated: nzb-streamer
     * lists a file posted under an obfuscated name by the name its PAR2 data gives it (library issue
     * N54), and by the posted one again where that data can't be read, in its place and as big as it
     * was (NR-19). Two names that say what they are, neither obfuscated, are two files (#179).
     */
    private fun renamed(index: Int, name: String, size: Long?): FileCandidate? {
        val at = candidates.getOrNull(index)?.takeIf { it.size == size } ?: return null
        if (candidates.count { it.size == size } > 1) return null
        return at.takeIf { ObfuscationHeuristic.isProbablyObfuscated(name) || ObfuscationHeuristic.isProbablyObfuscated(it.name) }
    }

    /**
     * The NZB files (archive volumes, or the file itself) that [file]'s bytes lie in. Splits are
     * positions in the volumes laid end to end, and one can run on into the next volume (a 7z
     * entry's does), so they are matched by position rather than by their volume index.
     */
    fun volumesOf(file: FileCandidate): NzbDocument {
        val archive = metadata.orderedArchiveNzb
        val starts = archive.files.runningFold(0L) { offset, volume -> offset + (volume.yencHeaders?.size ?: 0L) }
        val splits = file.file.toSplits(archive)
        return archive.copy(
            files = archive.files.filterIndexed { i, _ ->
                splits.any { it.dataStartPosition < starts[i + 1] && it.dataStartPosition + it.dataSize > starts[i] }
            },
        )
    }

    companion object {
        fun idFor(nzbBytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(nzbBytes).joinToString("") { "%02x".format(it) }
    }
}

/**
 * A copy of a disc's own files (W103, L310), which plays as no single video: a Blu-ray's, by its `BDMV` folder, its
 * navigation files or its streams beside them; a DVD's, by its `VIDEO_TS` folder or its own files; a disc image, `.iso`.
 * Told by the files a release lists once they are known, never by its name: a folder's name compares whole, whatever its
 * case, split on either separator (a 7z made on Windows may keep its own).
 */
object DiscFiles {
    /** A Blu-ray's navigation files: its index and movie objects, playlists, clip information and BD-J objects. */
    private val navigation = setOf("bdmv", "mpls", "clpi", "bdjo")

    /** A Blu-ray's streams, 2D and 3D: a disc's only beside its navigation files, as a lone one may be any recording. */
    private val streams = setOf("m2ts", "ssif")

    /** A DVD's own files: its information, their backups and its video objects. */
    private val dvd = setOf("ifo", "bup", "vob")

    /** [path]'s folders and its name, lower case. */
    private fun parts(path: String): List<String> = VideoFileFilter.parts(path.lowercase())

    private fun extension(path: String): String = parts(path).last().substringAfterLast('.', "")

    /** Whether one of [path]'s folders is a Blu-ray's `BDMV`, by its whole name: nothing in it is a video (L312). */
    fun inBdmv(path: String): Boolean = "bdmv" in parts(path).dropLast(1)

    /**
     * The kind of disc [files] are a copy of: they hold a disc's file, and no video of the release's own besides (one
     * that isn't a disc's, a sample or an extra, [VideoFileFilter.isSample]), which plays instead (L312). Its largest disc
     * file says which. Null when they aren't one.
     */
    fun kindOf(files: List<ListedFile>): DiscKind? {
        val withNavigation = files.any { extension(it.path) in navigation }
        val kinds = files.map { it to kindOf(it.path, withNavigation) }
        if (kinds.any { (file, kind) -> kind == null && file.isVideo && !VideoFileFilter.isSample(file.path) }) return null
        return kinds.filter { it.second != null }.maxByOrNull { it.first.size }?.second
    }

    /** The kind of disc [path] is a file of, in a listing that holds a Blu-ray's navigation files or not ([withNavigation]). */
    private fun kindOf(path: String, withNavigation: Boolean): DiscKind? {
        val folders = parts(path).dropLast(1)
        val extension = extension(path)
        return when {
            "bdmv" in folders || extension in navigation || extension in streams && withNavigation -> DiscKind.BluRay
            "video_ts" in folders || extension in dvd -> DiscKind.Dvd
            extension == "iso" -> DiscKind.Image
            else -> null
        }
    }
}

object VideoFileFilter {
    private val extensions = setOf("mkv", "mp4", "m4v", "avi", "ts", "m2ts", "wmv", "mov", "webm", "mpg", "mpeg")
    private val sampleName = Regex("""(^|[._\-\s\[(])sample([._\-\s\])]|$)""", RegexOption.IGNORE_CASE)

    /** The folders a release keeps its samples and extras in (L163), by name, whatever their case. */
    private val extrasFolders = setOf("sample", "samples", "featurettes", "extras", "bonus")

    /** A RAR set's volumes: `.rar` (`.part01.rar` among them) and the old `.r00` on. */
    private val rarVolume = Regex("""\.(rar|r\d{2})$""", RegexOption.IGNORE_CASE)

    /** Whether [path] is a video by its extension; nothing in a Blu-ray's `BDMV` folder is one, its streams no film the player reads (L312). */
    fun isVideo(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in extensions && !DiscFiles.inBdmv(path)

    /**
     * Whether [path] is a video that isn't what a release is for: a sample clip by its name, or any video in a folder of
     * samples or extras (featurettes, deleted scenes, a trailer), which a release with folders keeps apart, whatever the
     * files' own names say ([inExtras]).
     */
    fun isSample(path: String): Boolean =
        isVideo(path) && (sampleName.containsMatchIn(parts(path).last().substringBeforeLast('.')) || inExtras(path))

    /** Whether one of [path]'s folders is one a release keeps its samples or extras in: "Sample/", "Featurettes/", "Extras/". */
    fun inExtras(path: String): Boolean = parts(path).dropLast(1).any { it.lowercase() in extrasFolders }

    /**
     * [path]'s folders and its name, split on either separator: a 7z made on Windows may keep its own (L310), and a disc's
     * sample or extras beside it are told as its folders are (RFD-1).
     */
    internal fun parts(path: String): List<String> = path.split('/', '\\')

    /** Whether [path] is a volume of a RAR set. */
    fun isRarVolume(path: String): Boolean = rarVolume.containsMatchIn(path)

    /** Largest video that is no sample clip or extra ([isSample]); falls back to the largest video. */
    fun <F : ListedFile> defaultPick(files: List<F>): F? {
        val videos = files.filter { it.isVideo }
        return videos.filterNot { isSample(it.path) && videos.size > 1 }
            .maxByOrNull { it.size }
            ?: videos.maxByOrNull { it.size }
    }
}

/**
 * What a release carries besides what can play: repair volumes, checksums, notes, artwork, and
 * sample clips and the videos in its extras' folders ([VideoFileFilter.isSample]). A gap in them
 * doesn't stop playback, so the availability check leaves them out.
 */
object ExtraFileFilter {
    private val extensions = setOf("par2", "nfo", "sfv", "nzb", "txt", "jpg", "jpeg", "png", "srr", "srs", "md5", "url")

    fun isExtra(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in extensions || VideoFileFilter.isSample(name)

    /** [nzb] without the files whose subjects name an extra; a subject that names no file is kept. */
    fun withoutExtras(nzb: NzbDocument): NzbDocument =
        nzb.copy(files = nzb.files.filterNot { file -> FilenameResolver.extractSubjectFilename(file.subject)?.let(::isExtra) == true })
}
