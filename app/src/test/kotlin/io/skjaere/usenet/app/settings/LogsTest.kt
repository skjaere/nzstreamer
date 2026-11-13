package io.skjaere.usenet.app.settings

import io.skjaere.usenet.logging.LogLine
import io.skjaere.usenet.player.tv.DeviceMemory
import org.junit.jupiter.api.Test
import org.slf4j.event.Level
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What Settings › Logs shows of the in-app log, and what its Copy and Share hand over. */
class LogsTest {
    private val zone = ZoneOffset.UTC
    private val at = LocalDateTime.of(2026, 10, 16, 14, 2, 31, 123_000_000).toInstant(zone).toEpochMilli()

    private val lines = listOf(
        LogLine(at, Level.DEBUG, "NntpClientPool", "conn=3 acquired", seq = 1),
        LogLine(at + 1_000, Level.INFO, "UsenetEngine", "Building the streamer again", seq = 2),
        LogLine(at + 2_000, Level.WARN, "NntpStreamingService", "Segment timed out on pool[0]", seq = 3),
        LogLine(at + 3_000, Level.ERROR, "PlayPipeline", "open failed: Missing articles", seq = 4),
    )

    @Test
    fun `all lines, or warnings and errors only, narrowed by the text in their tag or message`() {
        assertEquals(lines, LogFilter().apply(lines))
        assertFalse(LogFilter().narrows)
        assertEquals(listOf(3L, 4L), LogFilter(warningsAndErrors = true).apply(lines).map { it.seq })
        assertEquals(listOf(2L, 3L), LogFilter(text = "  STREAM").apply(lines).map { it.seq })
        assertEquals(listOf(4L), LogFilter(text = "pipeline").apply(lines).map { it.seq })
        assertEquals(listOf(3L), LogFilter(warningsAndErrors = true, text = "pool").apply(lines).map { it.seq })
        assertEquals(emptyList(), LogFilter(text = "nothing like it").apply(lines))
        assertTrue(LogFilter(text = "x").narrows)
        assertTrue(LogFilter(warningsAndErrors = true).narrows)
        // Spaces alone narrow nothing.
        assertFalse(LogFilter(text = "  ").narrows)
        assertEquals(lines, LogFilter(text = "  ").apply(lines))
    }

    @Test
    fun `under a filter, the note names the actions there are`() {
        assertEquals("Copy and Share take the 2 lines shown.", shownNote(2, canShare = true))
        assertEquals("Copy and Share take the 1 line shown.", shownNote(1, canShare = true))
        // A TV with nothing to share to has no Share button.
        assertEquals("Copy takes the 12 lines shown.", shownNote(12, canShare = false))
        assertEquals("Copy takes the 1 line shown.", shownNote(1, canShare = false))
    }

    @Test
    fun `a shared log starts with the app, Android and the device, then a line each, oldest first`() {
        val header = logHeader(appVersion = "0.1.0", versionCode = 1, debugBuild = true, androidRelease = "15", sdk = 35, manufacturer = "Google", model = "Pixel 9")
        assertEquals("NZStreamer 0.1.0 (1, debug build)\nAndroid 15 (API 35) on Google Pixel 9", header)
        assertEquals(
            "NZStreamer 0.1.0 (7)\nAndroid 8.0.0 (API 26) on unknown device",
            logHeader("0.1.0", 7, debugBuild = false, androidRelease = "8.0.0", sdk = 26, manufacturer = "", model = ""),
        )
        assertEquals(
            """
            |$header
            |
            |2026-10-16 14:02:32.123 I UsenetEngine: Building the streamer again
            |2026-10-16 14:02:33.123 W NntpStreamingService: Segment timed out on pool[0]
            |
            """.trimMargin(),
            logText(header, lines.subList(1, 3), zone = zone),
        )
        assertEquals("$header\n\nNothing logged.\n", logText(header, emptyList(), zone = zone))
    }

    /** A box's memory decides how much a play holds (PlaybackBudgets): a shared log says it under the device. */
    @Test
    fun `the header says the device's memory once it is known`() {
        val box = DeviceMemory(
            lowRam = true, memoryClassMb = 192, totalRamBytes = 1_932_735_283, process64Bit = false,
            abis = listOf("armeabi-v7a", "armeabi"), heapLimitBytes = 512L * 1024 * 1024,
        )
        assertEquals(
            "NZStreamer 0.1.0 (7)\nAndroid 11 (API 30) on Example TV box\n" +
                "Memory: 1.8 GB RAM (low RAM), heap 512 MB (class 192 MB), 32-bit process on armeabi-v7a, armeabi",
            logHeader("0.1.0", 7, debugBuild = false, androidRelease = "11", sdk = 30, manufacturer = "Example", model = "TV box", memory = box),
        )
        val phone = box.copy(lowRam = false, totalRamBytes = 8_000_000_000, process64Bit = true, abis = listOf("arm64-v8a"), heapLimitBytes = 256L * 1024 * 1024, memoryClassMb = 256)
        assertEquals(
            "Memory: 7.5 GB RAM, heap 256 MB (class 256 MB), 64-bit process on arm64-v8a",
            logHeader("0.1.0", 7, debugBuild = false, androidRelease = "15", sdk = 35, manufacturer = "Example", model = "Phone", memory = phone).lines().last(),
        )
    }

    /** What decides which releases play (the display, the decoders): a shared log says it under the memory, once read. */
    @Test
    fun `the header says the device's display and decoders once they are known`() {
        val device = listOf(
            "Display: 3840x2160 output, largest mode 3840x2160: 2160p; HDR: Dolby Vision, HDR10",
            "Video: H.264 2160p (hardware 2160p), HEVC 2160p (hardware 2160p); Dolby Vision dvhe.08.09; no decoder for AV1, VC-1",
            "Audio: AAC, DD, DD+ decoded; DD, DD+ passed through over HDMI",
        )
        val box = DeviceMemory(
            lowRam = false, memoryClassMb = 192, totalRamBytes = 3_096_188_928, process64Bit = true,
            abis = listOf("arm64-v8a"), heapLimitBytes = 512L * 1024 * 1024,
        )
        assertEquals(
            "NZStreamer 0.1.0 (7)\nAndroid 11 (API 30) on Example TV box\n" +
                "Memory: 2.9 GB RAM, heap 512 MB (class 192 MB), 64-bit process on arm64-v8a\n" + device.joinToString("\n"),
            logHeader("0.1.0", 7, debugBuild = false, androidRelease = "11", sdk = 30, manufacturer = "Example", model = "TV box", memory = box, device = device),
        )
        assertEquals(
            "NZStreamer 0.1.0 (7)\nAndroid 11 (API 30) on Example TV box\n" + device.joinToString("\n"),
            logHeader("0.1.0", 7, debugBuild = false, androidRelease = "11", sdk = 30, manufacturer = "Example", model = "TV box", device = device),
        )
    }

    @Test
    fun `a log too long for the clipboard leaves out its oldest lines and says how many`() {
        val many = List(100) { i -> LogLine(at, Level.INFO, "Tag", "line $i".padEnd(60, '.'), seq = i.toLong()) }
        val text = logText("H", many, maxChars = 2_000, zone = zone)
        assertTrue(text.length <= 2_000, "${text.length} characters")
        assertTrue(text.startsWith("H\n\n("), text)
        val left = Regex("""\((\d+) older lines left out\)""").find(text)!!.groupValues[1].toInt()
        val kept = text.lines().filter { " I Tag: line " in it }
        assertEquals(100, left + kept.size)
        assertTrue(kept.last().contains("line 99"))
        assertTrue(kept.first().contains("line $left."))
        // Room for every line: all of them, nothing said.
        assertFalse("left out" in logText("H", many, maxChars = 1_000_000, zone = zone))
    }

    @Test
    fun `Copy says it copied where the system doesn't, before Android 13 and on a TV`() {
        assertTrue(copySaysCopied(sdk = 26, television = false))
        assertTrue(copySaysCopied(sdk = 32, television = false))
        // A phone or tablet confirms a copy itself from Android 13.
        assertFalse(copySaysCopied(sdk = 33, television = false))
        assertFalse(copySaysCopied(sdk = 35, television = false))
        // A TV's system UI never does (S11-17), and there Copy is often the only export.
        assertTrue(copySaysCopied(sdk = 33, television = true))
        assertTrue(copySaysCopied(sdk = 34, television = true))
        assertTrue(copySaysCopied(sdk = 28, television = true))
    }

    @Test
    fun `the screen shows each line's time of day`() {
        assertEquals("14:02:31", clockTime(at, zone))
    }
}
