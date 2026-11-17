package io.skjaere.usenet.app.download

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The downloads' sources point at nothing a reader of the repository can't open: no drawing of the private design canvas,
 * and every Python file they name named by its path in the repository. Their own folders (main, the JVM tests and the
 * device tests, the notifications among them), the drawn icons, Settings › Downloads' page, Home's Downloads rows and
 * Import's rows' downloads (L472) with their tests, read as text, comments with the code.
 */
class DownloadSourceWordsTest {
    private val sources: List<File> = listOf(
        "src/main/kotlin/io/skjaere/usenet/app/download", "src/test/kotlin/io/skjaere/usenet/app/download",
        "src/androidTest/kotlin/io/skjaere/usenet/app/download", "src/main/kotlin/io/skjaere/usenet/app/ui/DownloadIcons.kt",
        "src/test/kotlin/io/skjaere/usenet/app/ui/DownloadIconsTest.kt", "src/main/kotlin/io/skjaere/usenet/app/settings/DownloadSettingsScreen.kt",
        "src/test/kotlin/io/skjaere/usenet/app/settings/DownloadSettingsTest.kt", "src/main/kotlin/io/skjaere/usenet/app/home/HomeDownloads.kt",
        "src/test/kotlin/io/skjaere/usenet/app/home/HomeDownloadsTest.kt", "src/main/kotlin/io/skjaere/usenet/app/ImportDownloads.kt",
        "src/test/kotlin/io/skjaere/usenet/app/ImportDownloadsTest.kt",
    ).flatMap { File(it).walkTopDown().filter { file -> file.isFile && file.extension == "kt" && file.name != OWN }.toList() }

    @Test
    fun `no line points at the design canvas's drawings`() {
        assertTrue(sources.size > 40, "the downloads' sources read: ${sources.size}")
        val found = sources.flatMap { file -> file.readLines().mapIndexedNotNull { i, line -> "${file.name}:${i + 1}".takeIf { DRAWINGS.containsMatchIn(line) } } }
        assertEquals(emptyList(), found)
    }

    @Test
    fun `every Python file named is one the repository has, at the path given`() {
        val repository = File("..")
        val missing = sources.flatMap { file -> PYTHON.findAll(file.readText()).map { it.value }.filterNot { File(repository, it).isFile }.map { "${file.name}: $it" }.toList() }
        assertEquals(emptyList(), missing)
    }

    private companion object {
        /** This file, whose own words name what it looks for. */
        const val OWN = "DownloadSourceWordsTest.kt"

        val DRAWINGS = Regex("""\bboards?\b""", RegexOption.IGNORE_CASE)
        val PYTHON = Regex("""[A-Za-z0-9_./-]+\.py\b""")
    }
}
