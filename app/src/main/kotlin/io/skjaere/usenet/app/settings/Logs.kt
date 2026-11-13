package io.skjaere.usenet.app.settings

import io.skjaere.usenet.logging.LogLine
import io.skjaere.usenet.logging.text
import io.skjaere.usenet.player.tv.DeviceMemory
import org.slf4j.event.Level
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Which lines Settings › Logs shows: all, or warnings and errors only; those whose tag or message holds [text] (any case). */
data class LogFilter(val warningsAndErrors: Boolean = false, val text: String = "") {
    private val needle = text.trim()

    /** Whether it leaves any line out. */
    val narrows: Boolean get() = warningsAndErrors || needle.isNotEmpty()

    fun apply(lines: List<LogLine>): List<LogLine> = if (!narrows) lines else lines.filter { line ->
        (!warningsAndErrors || line.level.toInt() >= Level.WARN.toInt()) &&
            (needle.isEmpty() || line.tag.contains(needle, ignoreCase = true) || line.message.contains(needle, ignoreCase = true))
    }
}

/**
 * Said beside Copy and Share, and on the last crash's log: what a log handed over tells whoever reads
 * it. The lines name releases and shows, and hosts; the logger keeps logins and keys out of them.
 */
const val LOG_CONTENTS_NOTE = "The log names what you searched for and played, and your providers' and indexers' addresses. " +
    "It never holds passwords or keys."

/** Under the actions while a filter leaves lines out: the [shown] lines are what Copy, and Share where it is offered, take. */
fun shownNote(shown: Int, canShare: Boolean): String =
    "${if (canShare) "Copy and Share take" else "Copy takes"} the $shown line${if (shown == 1) "" else "s"} shown."

/**
 * The top of a shared log: the app's version and build, Android's version and the device, the
 * device's [memory] when it is known, which decides how much a play may hold (PlaybackBudgets), and
 * its [device] facts when known (This device's [ThisDevice.headerLines]: its kind, budget, display
 * modes and native library, the display and the decoders, which decide what releases play).
 * Nothing about the user or their accounts.
 */
fun logHeader(
    appVersion: String, versionCode: Long, debugBuild: Boolean, androidRelease: String, sdk: Int, manufacturer: String, model: String,
    memory: DeviceMemory? = null,
    device: List<String> = emptyList(),
): String {
    val app = "NZStreamer $appVersion ($versionCode${if (debugBuild) ", debug build" else ""})\nAndroid $androidRelease (API $sdk) on ${deviceName(manufacturer, model)}"
    return (listOf(app) + listOfNotNull(memory?.let(::memoryLine)) + device).joinToString("\n")
}

/** "Memory: 1.8 GB RAM (low RAM), heap 512 MB (class 192 MB), 32-bit process on armeabi-v7a, armeabi". */
private fun memoryLine(memory: DeviceMemory): String {
    val ram = String.format(Locale.ROOT, "%.1f GB RAM", memory.totalRamBytes / GB)
    val heap = "heap ${memory.heapLimitBytes / MB} MB (class ${memory.memoryClassMb} MB)"
    val process = "${if (memory.process64Bit) 64 else 32}-bit process on ${memory.abis.joinToString()}"
    return "Memory: $ram${if (memory.lowRam) " (low RAM)" else ""}, $heap, $process"
}

private const val MB = 1024L * 1024
private const val GB = 1024.0 * MB

/**
 * What Copy and Share hand over: [header], then [lines] oldest first, a line each. Past [maxChars]
 * (the clipboard's room) the oldest are left out, and how many is said.
 */
fun logText(header: String, lines: List<LogLine>, maxChars: Int = Int.MAX_VALUE, zone: ZoneId = ZoneId.systemDefault()): String {
    if (lines.isEmpty()) return "$header\n\nNothing logged.\n"
    val texts = lines.map { it.text(zone) + "\n" }
    val room = maxChars.toLong() - header.length - 2
    val kept = if (texts.sumOf { it.length.toLong() } <= room) texts.size else {
        // The newest that fit beside a note of how many older ones were left out.
        val note = "(${lines.size} older lines left out)\n".length
        texts.asReversed().runningFold(0L) { used, text -> used + text.length }.drop(1).takeWhile { it <= room - note }.size
    }
    return buildString {
        append(header).append("\n\n")
        if (kept < lines.size) append("(${lines.size - kept} older lines left out)\n")
        texts.takeLast(kept).forEach(::append)
    }
}

private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

/** "14:02:31": a line's time on the Logs screen. */
fun clockTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String = clock.format(Instant.ofEpochMilli(millis).atZone(zone))

/**
 * Whether Copy says "Copied" itself: where the system doesn't. A phone or tablet confirms a copy from
 * Android 13; a TV's system UI never does (S11-17), and there Copy is often the only export.
 */
fun copySaysCopied(sdk: Int, television: Boolean): Boolean = sdk < 33 || television
