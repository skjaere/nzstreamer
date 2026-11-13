package io.skjaere.usenet.app.cast

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L221 (DFH-1): a debrid cast's link never reaches a receiver, a log or another device. The cast path's files take a
 * debrid file's bytes, never its link: none under cast/, casting/ or serve/ (where the relay, ServiceByteSource, lives)
 * names HttpLink, the one type that holds a link; the relay reads through the container's ServiceDataSource, which keeps
 * the link inside core:player. Read as CastStartSourcesTest reads its sources: whole files, comments included, the name a
 * whole word.
 */
class CastLinkSourcesTest {
    private val base = File("src/main/kotlin/io/skjaere/usenet/app")
    private val roots = listOf("cast", "casting", "serve").map { File(base, it) }
    private val sources = roots.flatMap { root -> root.walk().filter { it.extension == "kt" || it.extension == "java" }.toList() }

    private val link = Regex("""\bHttpLink\b""")

    private fun lineOf(text: String, at: Int) = text.substring(0, at).count { it == '\n' } + 1

    @Test
    fun `the cast path's sources are read`() {
        roots.forEach { assertTrue(it.isDirectory, "no sources under ${it.absolutePath}") }
        val names = sources.map { it.name }
        listOf("Casting.kt", "CastOutcomes.kt", "CastRefusal.kt", "ServedStream.kt", "ServiceByteSource.kt").forEach { assertTrue(it in names, "$it not read from $names") }
    }

    @Test
    fun `no file of the cast path names a debrid link`() {
        val named = sources.flatMap { file ->
            val text = file.readText()
            link.findAll(text).map { "${file.path}:${lineOf(text, it.range.first)}" }.toList()
        }
        assertEquals(emptyList(), named, "a debrid link named on the cast path (L221)")
    }

    @Test
    fun `the name is found where it is written`() {
        // The check above would pass on a pattern that finds nothing: as an import, a type and a comment write it.
        listOf("import io.skjaere.usenet.player.HttpLink", "val held: HttpLink? = null", "// the HttpLink it was given").forEach { assertTrue(link.containsMatchIn(it), it) }
        listOf("HttpLinks", "OkHttpLinker", "httpLink").forEach { assertFalse(link.containsMatchIn(it), it) }
    }
}
