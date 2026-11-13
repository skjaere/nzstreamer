package io.skjaere.usenet.serve

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The module serves a film's bytes to anyone on the home network who holds its token, and logs none of it (L75): it says
 * what happened through events and counters, which the app logs as counts and statuses. Nothing here may print or log, so
 * no request, path or token can reach a log from it; and nothing it tells the app holds an exception, whose message can
 * quote what it failed on, or any words at all: an event's fields are numbers, statuses and byte ranges.
 */
class ServeSourcesTest {
    private val sources = File("src/main/kotlin/io/skjaere/usenet/serve").walk().filter { it.extension == "kt" }.toList()

    private fun matches(pattern: Regex, files: List<File> = sources): List<String> = files.flatMap { file ->
        file.readLines().withIndex().filter { pattern.containsMatchIn(it.value) }.map { "${file.name}:${it.index + 1}" }
    }

    @Test
    fun `the sources are found`() {
        assertTrue(sources.size >= 5, "no sources under ${File(".").absolutePath}")
    }

    @Test
    fun `nothing in the module prints or logs`() {
        val banned = Regex("""\bprintln\(|\bprint\(|System\.(out|err)|printStackTrace|\bLogger\b|LoggerFactory|android\.util\.Log|\bLog\.[a-z]+\(|org\.slf4j|java\.util\.logging""")
        assertEquals(emptyList(), matches(banned), "printing or logging in core:serve")
    }

    @Test
    fun `nothing told to the app holds an exception, and nothing reads one's message`() {
        // A property, parameter or return of an exception's type, in any class: events and counters among them.
        val held = Regex("""(\bval|\bvar|\)|\w)\s*:\s*[\w.]*(Throwable|Exception)\b""")
        val catches = Regex("""catch\s*\(""")
        val found = sources.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> held.containsMatchIn(line) && !catches.containsMatchIn(line) }
                .map { "${file.name}:${it.index + 1}" }
        }
        assertEquals(emptyList(), found, "an exception held")
        assertEquals(emptyList(), matches(Regex("""\.(message|localizedMessage|cause|stackTrace|suppressed)\b|getMessage\(|getCause\(""")), "an exception's message read")
    }

    @Test
    fun `events and counters hold no token, path or words, only numbers, statuses and ranges`() {
        val told = sources.filter { it.name == "ServeEvent.kt" || it.name == "ServeCounters.kt" }
        assertEquals(2, told.size, "the events' and counters' sources aren't where they were")
        val allowed = setOf(
            "Int", "Int?", "Long", "Long?", "Boolean", "ServeDrop", "ServeEnd", "ServeMethod?", "Map<ServeDrop, Int>",
        )
        val declared = Regex("""\bva[lr]\s+(\w+)\s*:\s*([\w.]+(?:<[^>]*>)?\??)""")
        val fields = told.flatMap { file -> file.readLines().flatMap { line -> declared.findAll(line).map { file.name to (it.groupValues[1] to it.groupValues[2].trim()) } } }
        assertTrue(fields.size >= 10, "no fields found: $fields")
        assertEquals(emptyList(), fields.filter { (_, field) -> field.second !in allowed }, "a field of another type")
        // Named for whom its answers waited on, L133's time is milliseconds like the other counts, never the peer itself.
        val times = setOf("peerWaitMillis")
        assertEquals(
            emptyList(),
            fields.filter { (_, field) -> field.first !in times && Regex("token|path|url|name|title|address|peer", RegexOption.IGNORE_CASE).containsMatchIn(field.first) },
        )
    }
}
