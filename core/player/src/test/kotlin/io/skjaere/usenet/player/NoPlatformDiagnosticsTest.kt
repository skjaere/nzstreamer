package io.skjaere.usenet.player

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ExoPlayer reports to the platform's diagnostics unless told not to: `ExoPlayer.Builder` defaults
 * `usePlatformDiagnostics` to true, and its KDoc says the data "may also be collected by Google if sharing usage and
 * diagnostics data is enabled" (L17). The app says it sends no telemetry, so every player it builds, a play's and the
 * track probe's, turns it off. A new `ExoPlayer.Builder(` in this module's or the app's main sources fails here until
 * its chain does the same.
 *
 * The app's Google Cast code (its cast package, in main since C2, L109) builds no `CastPlayer` (K8): Media3's CastPlayer
 * hands a cast back to a local ExoPlayer it builds itself, with diagnostics on. A cast plays on its device alone, through
 * RemoteCastPlayer (L48).
 */
class NoPlatformDiagnosticsTest {
    private val roots = listOf(File("src/main/kotlin"), File("../../app/src/main/kotlin"))

    private val sources = roots.flatMap { root -> root.walk().filter { it.extension == "kt" || it.extension == "java" }.toList() }

    /** Each builder: where it starts, and its chain up to its own `.build()`. */
    private val builders: List<Pair<String, String>> = sources.flatMap { file ->
        val text = file.readText()
        Regex("""ExoPlayer\.Builder\(""").findAll(text).map { match ->
            val line = text.substring(0, match.range.first).count { it == '\n' } + 1
            "${file.name}:$line" to text.substring(match.range.first, chainEnd(text, match.range.last + 1))
        }.toList()
    }

    /**
     * Where the chain whose builder's arguments open just before [from] ends: at its `.build()`, the first outside every
     * bracket. One inside the chain (an AudioAttributes' or a LoadControl's) is an argument's own.
     */
    private fun chainEnd(text: String, from: Int): Int {
        var depth = 1
        var i = from
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
                '.' -> if (depth == 0 && text.startsWith(".build()", i)) return i
            }
            i++
        }
        return text.length
    }

    @Test
    fun `the builders are found`() {
        roots.forEach { assertTrue(it.isDirectory, "no sources under ${it.absolutePath}") }
        // A play's (PlayerFactory.create) and the track probe's (ExoTrackProbe.build), today.
        assertTrue(builders.size >= 2, "found ${builders.map { it.first }} from ${File(".").absolutePath}")
    }

    @Test
    fun `every player turns platform diagnostics off`() {
        val missing = builders.filter { (_, chain) -> ".setUsePlatformDiagnostics(false)" !in chain }.map { it.first }
        assertEquals(emptyList(), missing, "ExoPlayer.Builder without setUsePlatformDiagnostics(false)")
    }

    @Test
    fun `no Cast player builds a local player of its own`() {
        val castPlayers = sources.flatMap { file ->
            val text = file.readText()
            Regex("""\bCastPlayer\s*\.\s*Builder\s*\(""").findAll(text).map { "${file.name}:${text.substring(0, it.range.first).count { c -> c == '\n' } + 1}" }.toList()
        }
        assertEquals(emptyList(), castPlayers, "CastPlayer.Builder builds an ExoPlayer with platform diagnostics on")
    }

    @Test
    fun `no builder hides behind an import`() {
        // `import …ExoPlayer.Builder` would let a bare `Builder(context)` past the check above.
        val imports = sources.filter { file ->
            file.readLines().any { it.trim().startsWith("import androidx.media3.exoplayer.ExoPlayer.Builder") }
        }.map { it.name }
        assertEquals(emptyList(), imports)
    }
}
