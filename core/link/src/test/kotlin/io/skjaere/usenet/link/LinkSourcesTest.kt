package io.skjaere.usenet.link

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The module carries titles, names and keys between a phone and a TV, and logs none of it (L13): it says what happened
 * through events and results, and the app words those. Nothing here may print or log, so no frame can reach a log from
 * it; and nothing it tells the app holds an exception, whose message can quote what it failed on (kotlinx-serialization's
 * "JSON input: …"), so nothing reads one.
 */
class LinkSourcesTest {
    private val sources = File("src/main/kotlin/io/skjaere/usenet/link").walk().filter { it.extension == "kt" }.toList()

    private fun matches(pattern: Regex): List<String> = sources.flatMap { file ->
        file.readLines().withIndex().filter { pattern.containsMatchIn(it.value) }.map { "${file.name}:${it.index + 1}" }
    }

    @Test
    fun `the sources are found`() {
        assertTrue(sources.size >= 5, "no sources under ${File(".").absolutePath}")
    }

    @Test
    fun `nothing in the module prints or logs`() {
        val banned = Regex("""\bprintln\(|\bprint\(|System\.(out|err)|printStackTrace|\bLogger\b|LoggerFactory|android\.util\.Log|\bLog\.[a-z]+\(|org\.slf4j|java\.util\.logging""")
        assertEquals(emptyList(), matches(banned), "printing or logging in core:link")
    }

    @Test
    fun `nothing told to the app holds an exception, and nothing reads one's message`() {
        // A property, parameter or return of an exception's type, in any class: events and results among them.
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
}
