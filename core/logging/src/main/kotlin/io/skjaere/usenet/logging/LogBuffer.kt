package io.skjaere.usenet.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.event.Level
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A line of the in-app log; [seq] numbers the lines in the order the log took them, from 1. */
data class LogLine(val timeMillis: Long, val level: Level, val tag: String, val message: String, val seq: Long = 0)

/**
 * The last [capacity] lines, oldest first. Adding one is O(1), whatever the size: nothing is copied
 * until a reader takes a [snapshot], which [version] says when to. A tag in [apart] keeps its lines
 * below WARN in a ring of their own, of that many: a line logged on a timer would otherwise push
 * every other line out in time. Its warnings and errors stay with the rest.
 */
class LogRing(val capacity: Int, apart: Map<String, Int> = emptyMap()) {
    /** The last lines of one ring, oldest first. */
    private class Lane(capacity: Int) {
        private val ring = arrayOfNulls<LogLine>(capacity)
        /** Where the next line goes. */
        private var next = 0
        var size = 0
            private set

        fun add(line: LogLine) {
            ring[next] = line
            next = (next + 1) % ring.size
            if (size < ring.size) size++
        }

        /** The [i]th line kept, oldest first. */
        operator fun get(i: Int): LogLine = ring[(next - size + i + ring.size) % ring.size]!!

        fun clear() {
            ring.fill(null)
            size = 0
        }
    }

    private val main = Lane(capacity)
    private val apart = apart.mapValues { (_, lines) -> Lane(lines) }
    private val lanes = listOf(main) + this.apart.values
    private var added = 0L
    private var changes = 0L
    private val _version = MutableStateFlow(0L)

    /** Moves with every line added and every [clear]. */
    val version: StateFlow<Long> = _version.asStateFlow()

    @Synchronized
    fun add(line: LogLine) {
        val lane = if (line.level.toInt() < Level.WARN.toInt()) apart[line.tag] ?: main else main
        lane.add(line.copy(seq = ++added))
        _version.value = ++changes
    }

    /**
     * The lines kept now, oldest first: a copy, which the log adding lines leaves as it is. The rings'
     * lines are merged by their numbers, the order the log took them in.
     */
    @Synchronized
    fun snapshot(): List<LogLine> {
        val at = IntArray(lanes.size)
        return List(lanes.sumOf { it.size }) {
            var pick = -1
            for (l in lanes.indices) {
                if (at[l] < lanes[l].size && (pick < 0 || lanes[l][at[l]].seq < lanes[pick][at[pick]].seq)) pick = l
            }
            lanes[pick][at[pick]++]
        }
    }

    /** Numbering goes on after a clear, so a line is never taken for one that was shown before it. */
    @Synchronized
    fun clear() {
        lanes.forEach(Lane::clear)
        _version.value = ++changes
    }
}

/**
 * The last [CAPACITY] lines logged, for Settings › Logs and the crash log, at the level
 * [AndroidLogConfig] lets through; and the stream-cost line's last [STREAM_COST_LINES] besides, kept
 * apart. It comes every 10 s while a stream plays: in the one ring it would push out every other
 * line within 6 hours of play, the line of a failure that evening among them. Logcat has every one.
 */
object LogBuffer {
    const val CAPACITY = 2_000

    /** 10 minutes of play. */
    const val STREAM_COST_LINES = 60

    /** The tag the stream-cost line (core/usenet's StreamCostMonitor) is logged under: its logger's, StreamCost's. */
    private const val STREAM_COST = "StreamCost"

    private val ring = LogRing(CAPACITY, apart = mapOf(STREAM_COST to STREAM_COST_LINES))

    /** Moves with every line added and every [clear]: take a new [snapshot] then. */
    val version: StateFlow<Long> get() = ring.version

    fun add(line: LogLine) = ring.add(line)

    fun snapshot(): List<LogLine> = ring.snapshot()

    fun clear() = ring.clear()
}

private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

/** The letter logcat shows for [level]. */
fun levelLetter(level: Level): String = when (level) {
    Level.TRACE -> "V"
    Level.DEBUG -> "D"
    Level.INFO -> "I"
    Level.WARN -> "W"
    Level.ERROR -> "E"
}

/** "2026-10-16 14:02:31.123 W PlayPipeline: step failed": the line as a shared log and the last crash's log have it. */
fun LogLine.text(zone: ZoneId = ZoneId.systemDefault()): String =
    "${stamp.format(Instant.ofEpochMilli(timeMillis).atZone(zone))} ${levelLetter(level)} $tag: $message"
