package io.skjaere.usenet.handoff

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The module handles the token, what the phone sent and requests from the home network, and logs
 * none of it: it says what happened through events, and the app words those. Nothing here may
 * print or log, so no request line, path, header, body or token can reach a log from it.
 */
class HandoffSourcesTest {
    private val sources = File("src/main/kotlin/io/skjaere/usenet/handoff")

    @Test
    fun `nothing in the module prints or logs`() {
        val files = sources.walk().filter { it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty(), "no sources under ${sources.absolutePath}")
        val banned = Regex("""\bprintln\(|\bprint\(|System\.(out|err)|printStackTrace|\bLogger\b|LoggerFactory|android\.util\.Log|\bLog\.[a-z]+\(|org\.slf4j|java\.util\.logging""")
        val found = files.flatMap { file ->
            file.readLines().withIndex().filter { banned.containsMatchIn(it.value) }.map { "${file.name}:${it.index + 1}" }
        }
        assertEquals(emptyList(), found, "printing or logging in core:handoff")
    }
}
