package io.skjaere.usenet.app.download

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L422: the downloads ask one gated lookup for a service account (Resolve, their writer, their holds): the account's
 * service while it is on and debrid shows ([io.skjaere.usenet.app.debrid.ServiceRegistry.gated]). The registry's other
 * one gives an account's client on or off, for its own page: nothing in the download package calls it. The download
 * package's sources are read as text, comments with the code; the app's Resolve and runner ask the gated lookup, and the
 * debrid writer's refused key is read from the settings, never taken as refused by no account.
 */
class DownloadLookupSourcesTest {
    private val sources: Map<String, String> = File("src/main/kotlin/io/skjaere/usenet/app/download").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }.associate { it.name to it.readText() }

    @Test
    fun `nothing in the download package calls the lookup that gives an account on or off`() {
        assertTrue(sources.size > 30, "the download package's sources read: ${sources.size}")
        val found = sources.flatMap { (name, text) -> text.lines().mapIndexedNotNull { i, line -> "$name:${i + 1}".takeIf { UNGATED.containsMatchIn(line) } } }
        assertEquals(emptyList(), found)
    }

    @Test
    fun `the app's Resolve and runner ask the gated lookup, and the writer's refused key is the settings'`() {
        assertTrue(GATED in sources.getValue("DownloadResolver.kt"), "Resolve's of()")
        val runner = sources.getValue("DownloadRunner.kt")
        assertTrue(GATED in runner, "the runner's of(), for the debrid writer")
        assertFalse(NEVER_REFUSED.containsMatchIn(runner), "a refused key read as never refused")
        assertTrue("keyRefused" in runner)
    }

    @Test
    fun `the rules find what they look for`() {
        assertTrue(UNGATED.containsMatchIn("val client = container.services.service(id)?.service"))
        assertFalse(UNGATED.containsMatchIn("val client = container.services.gated(id)"))
        assertTrue(NEVER_REFUSED.containsMatchIn("keyRefused = { false },"))
        assertTrue(NEVER_REFUSED.containsMatchIn("keyRefused = { _ -> false }"))
        assertFalse(NEVER_REFUSED.containsMatchIn("keyRefused = { id -> refused(id) },"))
    }

    private companion object {
        const val GATED = "services.gated("
        val UNGATED = Regex("""\bservices\.service\(""")
        val NEVER_REFUSED = Regex("""keyRefused\s*=\s*\{\s*(\w+\s*->\s*)?false\s*}""")
    }
}
