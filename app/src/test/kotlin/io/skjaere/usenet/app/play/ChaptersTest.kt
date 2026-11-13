package io.skjaere.usenet.app.play

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Label
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.extractor.metadata.Chapter
import io.skjaere.usenet.metadata.FileChapter
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ChaptersTest {
    private fun chapter(title: String?, startMs: Long, endMs: Long, hidden: Boolean = false): Chapter =
        Chapter.Builder().setStartTimeMs(startMs).setEndTimeMs(endMs).setHidden(hidden)
            .apply { title?.let { setTitle(Label(null, it)) } }.build()

    private fun group(mimeType: String, vararg entries: Metadata.Entry) = Tracks.Group(
        TrackGroup(Format.Builder().setSampleMimeType(mimeType).apply { if (entries.isNotEmpty()) setMetadata(Metadata(*entries)) }.build()),
        false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true),
    )

    /** Some other kind of metadata a track's format can carry. */
    private val other = object : Metadata.Entry {}

    @Test
    fun `Matroska's chapters come with every track's format and are read once each`() {
        // MatroskaExtractor adds the file's chapters to each track it applies them to.
        val chapters = arrayOf(chapter("Prologue", 0, 5_000), chapter("Intro", 5_000, 25_000), chapter("Credits", 330_000, 360_000, hidden = true), chapter(null, 25_000, 330_000))
        val tracks = Tracks(listOf(group(MimeTypes.VIDEO_H264, *chapters), group(MimeTypes.AUDIO_AAC, other, *chapters), group(MimeTypes.TEXT_SSA)))
        assertEquals(
            listOf(
                FileChapter("Prologue", 0, 5_000),
                FileChapter("Intro", 5_000, 25_000),
                FileChapter("Credits", 330_000, 360_000, hidden = true),
                FileChapter(null, 25_000, 330_000),
            ),
            tracks.fileChapters(),
        )
    }

    @Test
    fun `a chapter without an end time has none`() {
        val tracks = Tracks(listOf(group(MimeTypes.VIDEO_H264, chapter("Intro", 5_000, C.TIME_UNSET))))
        assertEquals(listOf(FileChapter("Intro", 5_000, null)), tracks.fileChapters())
    }

    @Test
    fun `a file without chapters has none`() {
        assertEquals(emptyList(), Tracks(listOf(group(MimeTypes.VIDEO_H264, other), group(MimeTypes.AUDIO_AAC))).fileChapters())
        assertEquals(emptyList(), Tracks.EMPTY.fileChapters())
    }
}
