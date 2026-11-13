package io.skjaere.usenet.metadata

import io.skjaere.usenet.metadata.SegmentKind.Credits
import io.skjaere.usenet.metadata.SegmentKind.Intro
import io.skjaere.usenet.metadata.SegmentKind.Preview
import io.skjaere.usenet.metadata.SegmentKind.Recap
import io.skjaere.usenet.metadata.SegmentSource.Chapters
import io.skjaere.usenet.metadata.SegmentSource.TheIntroDb
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SegmentsTest {
    private val minute = 60_000L
    /** A 45-minute episode. */
    private val duration = 45 * minute

    @Test
    fun `chapter titles that name a segment`() {
        val named = mapOf(
            Intro to listOf("Intro", "INTRO", "intro", "Opening", "Opening Credits", "opening theme", "OP", "OP1", "OP 2", "op01", "Vorspann", " Intro ", "Intro:", "- Intro -", "Opening  Credits"),
            Recap to listOf("Recap", "Previously", "Previously on", "Previously On…", "Previously on Harbor Lights", "Rückblick", "RÜCKBLICK", "Was bisher geschah", "Was bisher geschah..."),
            Credits to listOf("Credits", "End Credits", "Ending", "Ending Credits", "Outro", "ED", "ED2", "Abspann"),
            Preview to listOf("Preview", "Next Episode", "next episode"),
        )
        named.forEach { (kind, titles) -> titles.forEach { assertEquals(kind, ChapterTitles.kindOf(it), "\"$it\"") } }
        // "ü" written as u and a combining diaeresis.
        assertEquals(Recap, ChapterTitles.kindOf("Rückblick"))
    }

    @Test
    fun `chapter titles that only look like one, or are ambiguous, name nothing`() {
        listOf(
            "Opening Night", "The Recap Party", "Chapter 1", "Part A", "Introduction", "Intro to the Harbour", "Credits Roll", "The Ending",
            "Endings", "Opening Act", "OPEN", "Operation", "Edwin", "OP A", "Previously unseen", "Recapitulation", "Abspannung",
            "Prologue", "Cold Open", "Générique", "Generique", "Teaser", "", "   ", "1", "Next", "Episode",
        ).forEach { assertNull(ChapterTitles.kindOf(it), "\"$it\"") }
    }

    @Test
    fun `chapters become segments by their titles, hidden ones and untitled ones are ignored`() {
        val chapters = listOf(
            FileChapter("Prologue", 0, 5_000),
            FileChapter("Intro", 5_000, 25_000),
            FileChapter("Part A", 25_000, 40 * minute),
            FileChapter("Recap", 40 * minute, 41 * minute, hidden = true),
            FileChapter(null, 41 * minute, 42 * minute),
            FileChapter("Credits", 42 * minute, duration),
        )
        assertEquals(
            listOf(Segment(Intro, 5_000, 25_000, Chapters), Segment(Credits, 42 * minute, duration, Chapters)),
            Segments.fromChapters(chapters),
        )
    }

    @Test
    fun `a chapter without an end runs to the next chapter, or to the end of the file`() {
        val chapters = listOf(
            FileChapter("Credits", 42 * minute, null),
            FileChapter("Intro", 5_000, null),
            FileChapter("Part A", 25_000, null),
        )
        assertEquals(
            listOf(Segment(Intro, 5_000, 25_000, Chapters), Segment(Credits, 42 * minute, Segment.TO_END, Chapters)),
            Segments.fromChapters(chapters),
        )
    }

    @Test
    fun `an intro or recap starts in the first fifteen minutes, lasts five seconds to three minutes and ends five minutes before the end`() {
        fun intro(start: Long, end: Long, kind: SegmentKind = Intro) = Segment(kind, start, end, Chapters)
        val good = listOf(
            intro(0, 5_000), intro(15 * minute, 15 * minute + 180_000), intro(5_000, 185_000), intro(30_000, 90_000, Recap),
        )
        good.forEach { assertEquals(listOf(it), Segments.accept(listOf(it), emptyList(), duration), "$it") }
        val bad = listOf(
            intro(15 * minute + 1, 15 * minute + 60_000), intro(0, 4_999), intro(0, 180_001), intro(10_000, 9_000),
            // In a six-minute file an intro at the very start still ends less than five minutes before the end.
            intro(30_000, 90_000, Recap).copy(startMs = 50_000, endMs = 70_000),
        )
        val dropped = mutableListOf<Pair<Segment, String>>()
        bad.dropLast(1).forEach { assertEquals(emptyList(), Segments.accept(listOf(it), emptyList(), duration) { s, why -> dropped += s to why }, "$it") }
        assertEquals(emptyList(), Segments.accept(listOf(bad.last()), emptyList(), 6 * minute) { s, why -> dropped += s to why })
        assertEquals(bad, dropped.map { it.first })
        assertEquals(listOf(intro(5_000, 25_000)), Segments.accept(listOf(intro(5_000, 25_000)), emptyList(), 5 * minute + 25_000))
    }

    @Test
    fun `credits start in the last fifth or the last fifteen minutes and last at most fifteen minutes`() {
        fun credits(start: Long, end: Long) = Segment(Credits, start, end, Chapters)
        val hour = 60 * minute
        // In 45 minutes the last fifteen minutes reach further back than the last fifth; in two
        // hours the last fifth does.
        assertEquals(1, Segments.accept(listOf(credits(30 * minute, 31 * minute)), emptyList(), duration).size)
        assertEquals(0, Segments.accept(listOf(credits(30 * minute - 1, 31 * minute)), emptyList(), duration).size)
        assertEquals(1, Segments.accept(listOf(credits(96 * minute, 100 * minute)), emptyList(), 2 * hour).size)
        assertEquals(0, Segments.accept(listOf(credits(96 * minute - 1, 100 * minute)), emptyList(), 2 * hour).size)
        assertEquals(1, Segments.accept(listOf(credits(100 * minute, 115 * minute)), emptyList(), 2 * hour).size)
        assertEquals(0, Segments.accept(listOf(credits(100 * minute, 115 * minute + 1)), emptyList(), 2 * hour).size)
        // Past the end of the file, or empty.
        assertEquals(0, Segments.accept(listOf(credits(duration, duration + minute)), emptyList(), duration).size)
        assertEquals(0, Segments.accept(listOf(credits(40 * minute, 40 * minute)), emptyList(), duration).size)
    }

    @Test
    fun `a segment running to the end, or past it, ends with the file`() {
        val toEnd = Segment(Credits, 42 * minute, Segment.TO_END, TheIntroDb)
        val past = Segment(Credits, 42 * minute, duration + 2_000, Chapters)
        assertEquals(listOf(toEnd.copy(endMs = duration)), Segments.accept(emptyList(), listOf(toEnd), duration))
        assertEquals(listOf(past.copy(endMs = duration)), Segments.accept(listOf(past), emptyList(), duration))
    }

    @Test
    fun `previews are not used, and nothing is accepted while the length is unknown`() {
        val preview = Segment(Preview, 44 * minute, duration, Chapters)
        assertEquals(emptyList(), Segments.accept(listOf(preview), emptyList(), duration))
        val intro = Segment(Intro, 5_000, 25_000, Chapters)
        assertEquals(emptyList(), Segments.accept(listOf(intro), emptyList(), 0))
        assertEquals(emptyList(), Segments.accept(listOf(intro), emptyList(), -9_223_372_036_854_775_807L))
    }

    @Test
    fun `per kind the file's chapters win over TheIntroDB`() {
        val chapterIntro = Segment(Intro, 5_000, 25_000, Chapters)
        val onlineIntro = Segment(Intro, 6_000, 30_000, TheIntroDb)
        val onlineRecap = Segment(Recap, 0, 40_000, TheIntroDb)
        val onlineCredits = Segment(Credits, 43 * minute, Segment.TO_END, TheIntroDb)
        val chapterCredits = Segment(Credits, 42 * minute, duration, Chapters)
        assertEquals(
            listOf(onlineRecap, chapterIntro, chapterCredits),
            Segments.accept(listOf(chapterIntro, chapterCredits), listOf(onlineIntro, onlineRecap, onlineCredits), duration),
        )
        // A chapter that fails the checks leaves the kind to TheIntroDB.
        val tooLong = Segment(Intro, 5_000, 5 * minute, Chapters)
        assertEquals(listOf(onlineIntro), Segments.accept(listOf(tooLong), listOf(onlineIntro), duration))
    }

    @Test
    fun `several segments of a kind are all kept, in order`() {
        val first = Segment(Recap, 0, 30_000, TheIntroDb)
        val second = Segment(Recap, 60_000, 90_000, TheIntroDb)
        assertEquals(listOf(first, second), Segments.accept(emptyList(), listOf(second, first), duration))
    }

    @Test
    fun `the log line lists each segment with its times and source`() {
        assertEquals("none", Segments.describe(emptyList()))
        assertEquals(
            "Intro 0:05–0:25 (chapters), Credits 42:00–45:00 (TheIntroDB)",
            Segments.describe(listOf(Segment(Intro, 5_000, 25_000, Chapters), Segment(Credits, 42 * minute, duration, TheIntroDb))),
        )
    }
}
