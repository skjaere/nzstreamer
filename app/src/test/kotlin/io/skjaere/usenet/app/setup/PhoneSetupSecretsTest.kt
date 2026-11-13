package io.skjaere.usenet.app.setup

import io.skjaere.usenet.app.PhoneSetupRoute
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the phone setup's code may do with what the phone sent and with the code itself (D16): keep them in its view
 * model only. Nothing in setup/ saves state across a process (it would write the payload or the code to disk), and its
 * log lines are these reviewed ones, which name an address at most: Redaction matches field names, never a bare code
 * or a key in a message. A new log line fails here until it is reviewed and listed.
 */
class PhoneSetupSecretsTest {
    private val sources = File("src/main/kotlin/io/skjaere/usenet/app/setup").walk().filter { it.extension == "kt" }.toList()

    private val reviewed = listOf(
        """log.info("phone setup: listening on {}", server.address.authority)""",
        """log.info("phone setup: the network changed")""",
        """log.warn("phone setup: couldn't listen on the home network")""",
        """log.info("phone setup: the code expired")""",
        """log.info("phone setup: refused another device")""",
        """log.info("phone setup: closed after too many wrong codes")""",
        """log.info("phone setup: a form arrived")""",
        """log.info("phone setup: a test from the phone ({} {})", item.slot.label, outcome(result.outcome))""",
    )

    @Test
    fun `the sources are found`() {
        assertTrue(sources.size >= 3, "setup's sources weren't found from ${File(".").absolutePath}")
    }

    @Test
    fun `nothing is kept in saved state`() {
        val found = sources.flatMap { file ->
            listOf("rememberSaveable", "SavedStateHandle", "savedStateHandle", "onSaveInstanceState", "Bundle").filter { it in file.readText() }.map { "${file.name}: $it" }
        }
        assertEquals(emptyList(), found)
    }

    /** A route is kept in saved state: the page's carries nothing, so never what was sent or the code. */
    @Test
    fun `the page's route holds nothing`() {
        assertEquals(0, PhoneSetupRoute.serializer().descriptor.elementsCount)
    }

    @Test
    fun `every log line is a reviewed one`() {
        val calls = Regex("""\blog\.(trace|debug|info|warn|error)\(.*""")
        val found = sources.flatMap { file -> file.readLines().mapNotNull { line -> calls.find(line)?.value?.trim() } }
        assertEquals(reviewed.sorted(), found.sorted(), "log lines in setup/; review a new one for codes, keys and passwords, then list it")
    }

    @Test
    fun `nothing prints past the logger, or puts what was sent in an exception`() {
        val found = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                val printing = listOf("println(", "printStackTrace", "android.util.Log", "Log.d(", "Log.i(", "Log.w(", "Log.e(").any { it in line }
                val message = Regex("""(Exception|Error)\("[^"]*\$""").containsMatchIn(line)
                "${file.name}:${i + 1}: ${line.trim()}".takeIf { printing || message }
            }
        }
        assertEquals(emptyList(), found)
    }
}
