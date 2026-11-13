package io.skjaere.usenet.app.play

import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.Chapter
import io.skjaere.usenet.metadata.FileChapter

/**
 * The file's chapters, as media3's MatroskaExtractor reports them: as [Chapter] entries in the
 * metadata of each track's format (every track's, for chapters not tied to one), once the
 * extractor has read them ahead of the first frames. Each is listed once.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun Tracks.fileChapters(): List<FileChapter> =
    groups.asSequence()
        .flatMap { g -> (0 until g.length).asSequence().map(g::getTrackFormat) }
        .flatMap { f -> f.metadata?.let { m -> (0 until m.length()).asSequence().map(m::get) }.orEmpty() }
        .filterIsInstance<Chapter>()
        .map { FileChapter(it.title?.value, it.startTimeMs, it.endTimeMs.takeIf { end -> end != C.TIME_UNSET }, it.isHidden) }
        .distinct()
        .toList()
