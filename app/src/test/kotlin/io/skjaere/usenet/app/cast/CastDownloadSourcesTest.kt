package io.skjaere.usenet.app.cast

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L417 (C5): a cast's load finds a download's file by the player's own rule, as a coroutine off the main thread the load
 * runs on: Casting.kt calls DownloadPlayFiles' suspending found under Dispatchers.IO, never its find, whose runBlocking is
 * the player's loading thread's, nor runBlocking itself. And L448: a head that couldn't be opened ahead fails a download's
 * load before the LOAD, its file read as a download's. Read as CastLinkSourcesTest reads its sources: the whole file,
 * comments included.
 */
class CastDownloadSourcesTest {
    private val casting = File("src/main/kotlin/io/skjaere/usenet/app/cast/Casting.kt")

    /** The suspending find, inside an IO block of its own. */
    private val found = Regex("""withContext\s*\(\s*Dispatchers\.IO\s*\)\s*\{[^}]*\bdownloadFiles\s*\.\s*found\s*\(""")
    private val find = Regex("""\bdownloadFiles\s*\.\s*find\s*\(""")
    private val blocking = Regex("""\brunBlocking\b""")

    /** A download's branch for a head that couldn't be opened ahead, and a download's file read as one. */
    private val headFailed = Regex("""if\s*\(\s*headFailed\s*!=\s*null\s*&&\s*file\.download\s*!=\s*null\s*\)""")
    private val readAsADownload = Regex("""FileByteSource\([^)]*\)\s*,\s*CastSource\.Download\b""")

    private fun lineOf(text: String, at: Int) = text.substring(0, at).count { it == '\n' } + 1

    @Test
    fun `Casting finds a download's file with found under Dispatchers IO, never find or runBlocking`() {
        assertTrue(casting.isFile, "no ${casting.absolutePath}")
        val text = casting.readText()
        assertTrue(found.containsMatchIn(text), "Casting.kt finds no download's file through found under Dispatchers.IO")
        assertEquals(emptyList(), find.findAll(text).map { "Casting.kt:${lineOf(text, it.range.first)}" }.toList(), "the player's find, which blocks")
        assertEquals(emptyList(), blocking.findAll(text).map { "Casting.kt:${lineOf(text, it.range.first)}" }.toList(), "runBlocking on the load's thread")
    }

    /**
     * L448, L417: a download's head that couldn't be opened ahead (its file gone or cut short between the find and the
     * open) fails the load as a file gone and fails its row, before the LOAD goes; and what the load serves is read as a
     * download's file, so a read failure says so and Resume keeps the release (CastOutcomes). Read in the source, as no
     * device case can time a file going between the find and the head's open.
     */
    @Test
    fun `a download's head that can't be opened fails the load before the LOAD, and its file is read as a download's`() {
        val text = casting.readText()
        val head = headFailed.find(text) ?: throw AssertionError("Casting.kt has no download's branch for a head that couldn't be opened")
        val block = text.substring(head.range.first, text.indexOf("\n        }", head.range.first))
        assertTrue("downloadStorage.fileGone(" in block, "its row isn't failed: $block")
        assertTrue("return fileGone()" in block, "its load doesn't fail as a file gone: $block")
        assertTrue(text.indexOf("serving.sent(") > head.range.first, "the LOAD goes before the head's check")
        assertTrue(readAsADownload.containsMatchIn(text), "a download's file isn't read as a download's")
    }

    @Test
    fun `the patterns find what they look for`() {
        // The checks above would pass on patterns that find nothing: as the code would write each.
        assertTrue(found.containsMatchIn("withContext(Dispatchers.IO) { container.downloadFiles.found(target.id) }"))
        assertFalse(found.containsMatchIn("container.downloadFiles.found(target.id)"))
        assertTrue(find.containsMatchIn("container.downloadFiles.find(id)"))
        assertTrue(blocking.containsMatchIn("val row = runBlocking { dao.get(id) }"))
        assertFalse(blocking.containsMatchIn("val blockingRead = 1"))
        assertTrue(headFailed.containsMatchIn("if (headFailed != null && file.download != null) {"))
        assertTrue(readAsADownload.containsMatchIn("Found(name, file.size, FileByteSource(file.file, file.size, file.gaps), CastSource.Download, download = row)"))
        assertFalse(readAsADownload.containsMatchIn("Found(name, file.size, FileByteSource(file.file, file.size, file.gaps), CastSource.Providers, download = row)"))
    }
}
