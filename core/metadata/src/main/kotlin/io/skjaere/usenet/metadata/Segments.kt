package io.skjaere.usenet.metadata

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

/** Parts of an episode a viewer may want to skip, or leave at. */
enum class SegmentKind { Intro, Recap, Credits, Preview }

/** Where a segment's times come from. */
enum class SegmentSource { Chapters, TheIntroDb }

/** One stretch of the file; [endMs] is [TO_END] for one that runs to the end of the file. */
data class Segment(val kind: SegmentKind, val startMs: Long, val endMs: Long, val source: SegmentSource) {
    companion object {
        const val TO_END = Long.MAX_VALUE
    }
}

/** A chapter as the file has it; [endMs] is null when the file gives none. */
data class FileChapter(val title: String?, val startMs: Long, val endMs: Long?, val hidden: Boolean = false)

/**
 * Which segment a chapter's title names. Whole titles only, ignoring case and the spaces and
 * punctuation around them ("Intro:", "- Intro -"), so "Opening Night" or "The Recap Party" name
 * nothing. Titles that mean different things in different files (prologue, cold open, générique)
 * are left out. The one prefix is "Previously on …", which always introduces a recap.
 */
object ChapterTitles {
    private val titles = mapOf(
        SegmentKind.Intro to listOf("intro", "opening", "opening credits", "opening theme", "vorspann"),
        SegmentKind.Recap to listOf("recap", "previously", "previously on", "rückblick", "was bisher geschah"),
        SegmentKind.Credits to listOf("credits", "end credits", "ending", "ending credits", "outro", "abspann"),
        SegmentKind.Preview to listOf("preview", "next episode"),
    ).flatMap { (kind, names) -> names.map { it to kind } }.toMap()

    /** Anime releases number their openings and endings: "OP", "OP2", "ED 1". */
    private val numbered = Regex("(op|ed) ?\\d{0,2}")
    private val edges = Regex("^[\\s\\p{P}]+|[\\s\\p{P}]+$")
    private val spaces = Regex("\\s+")

    fun kindOf(title: String): SegmentKind? {
        val t = Normalizer.normalize(title, Normalizer.Form.NFC).lowercase(Locale.ROOT).replace(edges, "").replace(spaces, " ")
        return titles[t]
            ?: numbered.matchEntire(t)?.let { if (it.groupValues[1] == "op") SegmentKind.Intro else SegmentKind.Credits }
            ?: SegmentKind.Recap.takeIf { t.startsWith("previously on ") }
    }
}

/**
 * Turns chapters and TheIntroDB's answers into the segments the player acts on, keeping only
 * those that make sense where they are: an intro or recap starts in the first [OPENING_WITHIN_MS],
 * lasts [OPENING_MIN_MS] to [OPENING_MAX_MS] and ends [OPENING_CLEAR_OF_END_MS] before the end;
 * credits start in the last fifth or the last [CREDITS_WITHIN_MS] of the file, whichever reaches
 * further back, and last at most [CREDITS_MAX_MS]. Previews have no use yet and are dropped.
 */
object Segments {
    private const val MINUTE = 60_000L
    const val OPENING_WITHIN_MS = 15 * MINUTE
    const val OPENING_MIN_MS = 5_000L
    const val OPENING_MAX_MS = 180_000L
    const val OPENING_CLEAR_OF_END_MS = 5 * MINUTE
    const val CREDITS_WITHIN_MS = 15 * MINUTE
    const val CREDITS_MAX_MS = 15 * MINUTE

    /**
     * The chapters whose titles name a segment, by [ChapterTitles]; hidden and untitled chapters
     * are ignored. A chapter without an end runs to the next chapter's start, or to the end of the file.
     */
    fun fromChapters(chapters: List<FileChapter>): List<Segment> {
        val visible = chapters.filterNot { it.hidden }.sortedBy { it.startMs }
        return visible.mapIndexedNotNull { i, chapter ->
            val kind = chapter.title?.let(ChapterTitles::kindOf) ?: return@mapIndexedNotNull null
            val end = chapter.endMs ?: visible.getOrNull(i + 1)?.startMs ?: Segment.TO_END
            Segment(kind, chapter.startMs, end, SegmentSource.Chapters)
        }
    }

    /**
     * The segments to use in a file of [durationMs], in order: those passing the checks above,
     * TheIntroDB's ([online]) only for kinds no passing chapter covers. Each one dropped goes to
     * [dropped] with the reason. Nothing passes while the length is unknown (zero or less).
     */
    fun accept(chapters: List<Segment>, online: List<Segment>, durationMs: Long, dropped: (Segment, String) -> Unit = { _, _ -> }): List<Segment> {
        fun passing(segments: List<Segment>) = segments.mapNotNull { s ->
            val clamped = s.copy(endMs = s.endMs.coerceAtMost(durationMs))
            val problem = problem(clamped, durationMs)
            if (problem != null) dropped(s, problem)
            clamped.takeIf { problem == null }
        }
        val fromChapters = passing(chapters)
        val covered = fromChapters.map { it.kind }.toSet()
        val fromOnline = passing(online).filter { it.kind !in covered }
        return (fromChapters + fromOnline).sortedBy { it.startMs }
    }

    private fun problem(s: Segment, durationMs: Long): String? {
        val length = s.endMs - s.startMs
        return when {
            durationMs <= 0 -> "length of the file unknown"
            s.startMs < 0 || length <= 0 -> "empty or outside the file"
            s.kind == SegmentKind.Intro || s.kind == SegmentKind.Recap -> when {
                s.startMs > OPENING_WITHIN_MS -> "starts after the first ${OPENING_WITHIN_MS / MINUTE} min"
                length !in OPENING_MIN_MS..OPENING_MAX_MS -> "lasts ${length / 1000} s, not ${OPENING_MIN_MS / 1000}–${OPENING_MAX_MS / 1000} s"
                s.endMs > durationMs - OPENING_CLEAR_OF_END_MS -> "ends less than ${OPENING_CLEAR_OF_END_MS / MINUTE} min before the end"
                else -> null
            }
            s.kind == SegmentKind.Credits -> when {
                s.startMs < durationMs - max(durationMs / 5, CREDITS_WITHIN_MS) -> "starts before the last fifth and the last ${CREDITS_WITHIN_MS / MINUTE} min"
                length > CREDITS_MAX_MS -> "lasts more than ${CREDITS_MAX_MS / MINUTE} min"
                else -> null
            }
            else -> "previews aren't used"
        }
    }

    /** For the log: "Intro 0:05–0:25 (chapters), Credits 42:00–45:00 (TheIntroDB)", or "none". */
    fun describe(segments: List<Segment>): String = segments.joinToString { s ->
        val source = when (s.source) {
            SegmentSource.Chapters -> "chapters"
            SegmentSource.TheIntroDb -> "TheIntroDB"
        }
        "${s.kind} ${clock(s.startMs)}–${if (s.endMs == Segment.TO_END) "end" else clock(s.endMs)} ($source)"
    }.ifEmpty { "none" }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(Locale.ROOT, s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(Locale.ROOT, s / 60, s % 60)
    }
}
