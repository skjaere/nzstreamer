package io.skjaere.usenet.app.download

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * L471: a downloaded file played here or cast is no stream, so a debrid download never waits for it: neither the reader
 * of a finished download's file for the player and the probe (core:player's DownloadDataSource) nor the cast server's
 * (core:serve's FileByteSource) takes a StreamTraffic or opens a meter, and the container makes the downloads' factory
 * with the files alone. The modules' sources are read as text from the app's folder, as DownloadPlaySourcesTest reads the
 * app's own.
 */
class DownloadStreamingSourcesTest {
    private fun source(path: String): String = File(path).also { assertTrue(it.isFile, "no $path from ${File(".").absolutePath}") }.readText()

    @Test
    fun `a download's file played here or cast takes no stream meter`() {
        val readers = mapOf(
            "DownloadDataSource" to "../core/player/src/main/kotlin/io/skjaere/usenet/player/DownloadDataSource.kt",
            "FileByteSource" to "../core/serve/src/main/kotlin/io/skjaere/usenet/serve/FileByteSource.kt",
        )
        readers.forEach { (name, path) ->
            val text = source(path)
            assertTrue(Regex("""\bclass $name\b""").containsMatchIn(text), "no class $name in $path")
            val named = text.lines().withIndex().filter { (_, line) -> METERED.containsMatchIn(line) }.map { "$name:${it.index + 1}" }
            assertEquals(emptyList(), named, "$name takes a stream meter")
        }
    }

    @Test
    fun `the container makes the downloads' factory with the files alone`() {
        val container = source("src/main/kotlin/io/skjaere/usenet/app/UsenetApplication.kt")
        val made = MADE.findAll(container).map { it.groupValues[1].trim() }.toList()
        assertEquals(listOf("downloadFiles"), made, "DownloadDataSource.Factory made with $made")
        val casting = source("src/main/kotlin/io/skjaere/usenet/app/cast/Casting.kt")
        val cast = assertNotNull(Regex("""FileByteSource\(([^)]*)\)""").find(casting), "Casting makes no FileByteSource").groupValues[1]
        assertFalse(METERED.containsMatchIn(cast), "Casting's FileByteSource is made with $cast")
    }

    private companion object {
        /** A stream meter, by its type or the container's one, or a parameter named for it. */
        val METERED = Regex("""\bStreamTraffic\b|\bstreamTraffic\b|\btraffic\b|\bMeter\b""")

        /** The downloads' factory as the container makes it, and with what. */
        val MADE = Regex("""DownloadDataSource\.Factory\(([^)]*)\)""")
    }
}
