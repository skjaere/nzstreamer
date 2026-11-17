package io.skjaere.usenet.app.cast

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L417 (C5): a cast's load finds a download's file by the player's own rule, as a coroutine off the main thread the load
 * runs on: Casting.kt calls DownloadPlayFiles' suspending found under Dispatchers.IO, never its find, whose runBlocking is
 * the player's loading thread's, nor runBlocking itself. Read as CastLinkSourcesTest reads its sources: the whole file,
 * comments included.
 */
class CastDownloadSourcesTest {
    private val casting = File("src/main/kotlin/io/skjaere/usenet/app/cast/Casting.kt")

    /** The suspending find, inside an IO block of its own. */
    private val found = Regex("""withContext\s*\(\s*Dispatchers\.IO\s*\)\s*\{[^}]*\bdownloadFiles\s*\.\s*found\s*\(""")
    private val find = Regex("""\bdownloadFiles\s*\.\s*find\s*\(""")
    private val blocking = Regex("""\brunBlocking\b""")

    private fun lineOf(text: String, at: Int) = text.substring(0, at).count { it == '\n' } + 1

    @Test
    fun `Casting finds a download's file with found under Dispatchers IO, never find or runBlocking`() {
        assertTrue(casting.isFile, "no ${casting.absolutePath}")
        val text = casting.readText()
        assertTrue(found.containsMatchIn(text), "Casting.kt finds no download's file through found under Dispatchers.IO")
        assertEquals(emptyList(), find.findAll(text).map { "Casting.kt:${lineOf(text, it.range.first)}" }.toList(), "the player's find, which blocks")
        assertEquals(emptyList(), blocking.findAll(text).map { "Casting.kt:${lineOf(text, it.range.first)}" }.toList(), "runBlocking on the load's thread")
    }

    @Test
    fun `the patterns find what they look for`() {
        // The checks above would pass on patterns that find nothing: as the code would write each.
        assertTrue(found.containsMatchIn("withContext(Dispatchers.IO) { container.downloadFiles.found(target.id) }"))
        assertFalse(found.containsMatchIn("container.downloadFiles.found(target.id)"))
        assertTrue(find.containsMatchIn("container.downloadFiles.find(id)"))
        assertTrue(blocking.containsMatchIn("val row = runBlocking { dao.get(id) }"))
        assertFalse(blocking.containsMatchIn("val blockingRead = 1"))
    }
}
