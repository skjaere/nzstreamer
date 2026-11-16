package io.skjaere.usenet.app.download

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * L369: downloads show on phones and tablets alone, and their screens are reached by their routes. So a download route or
 * screen is named only in the download package (`app/download/`) and in the block that decides, from where downloads
 * show, what the phone's graph has: AppNav's `downloadScreens(nav, shown)`, which registers them while `shown` and gives
 * back no way in elsewhere (DownloadIntentsTest has its case). Named anywhere else, a route would be a way in that
 * DownloadPlaces.shown doesn't see: a TV, or a phone's layout on one, could open it. Every source the app builds from is
 * read as text, comments included, as DebridEntrySourcesTest reads them: main's, Discover's and the release (Play) build's.
 */
class DownloadEntrySourcesTest {
    private val roots = listOf("src/main/kotlin", "src/discover/kotlin", "src/release/kotlin").map(::File)

    private val sources: Map<String, String> = roots.flatMap { root -> root.walk().filter { it.isFile && it.extension == "kt" }.toList() }
        .associate { it.invariantSeparatorsPath to it.readText() }

    @Test
    fun `the sources are read, and the block that stands today is found`() {
        roots.forEach { assertTrue(it.isDirectory, "no sources under ${it.absolutePath}") }
        assertTrue(sources.size > 100, "found ${sources.size} files from ${File(".").absolutePath}")
        // The check would pass on a block it can't find: the one that stands today is found, body and all.
        val block = assertNotNull(blockIn(sources.getValue("$APP/AppNav.kt"), "downloadScreens"))
        assertTrue("composable<io.skjaere.usenet.app.download.DownloadsRoute>" in block.text, block.text)
        assertTrue(block.takesShown, block.text.lines().first())
        // And the package's own routes and screens, which the rule guards by name.
        val declared = declaredIn(sources)
        assertTrue(declared.containsAll(listOf("DownloadsScreen", "DownloadsShowScreen")), declared.toString())
    }

    @Test
    fun `no download route or screen is named outside the download package and the block that takes shown`() {
        assertEquals(emptyList(), misplaced(sources), "a way into downloads that DownloadPlaces.shown doesn't close (L369)")
    }

    @Test
    fun `the rule finds a route or screen named where the gate can't see it`() {
        val nav = """
            |fun NavGraphBuilder.downloadScreens(nav: NavController, shown: Boolean) {
            |    if (!shown) return
            |    composable<DownloadsRoute> { DownloadsScreen(onShow = { nav.navigate(DownloadsShowRoute(it)) }) }
            |}
            |
            |fun NavGraphBuilder.appGraph(nav: NavController) {
            |    composable<HomeRoute> { HomeScreen(onDownloads = { nav.navigate(DownloadsRoute()) }) }
            |}
        """.trimMargin()
        val files = mapOf(
            "$APP/download/DownloadRoutes.kt" to "@Serializable data class DownloadsRoute(val choose: String? = null)\n@Serializable data object DownloadSettingsRoute",
            "$APP/download/DownloadsScreen.kt" to "@Composable\nfun DownloadsScreen(onShow: (Int) -> Unit) {\n}\n",
            "$APP/AppNav.kt" to nav,
            "$APP/home/HomeScreen.kt" to "// Opens the DownloadsScreen.\nval route = DownloadSettingsRoute\n",
            "$APP/settings/SettingsScreen.kt" to "fun settingsHub(television: Boolean) {\n    val route = DownloadSettingsRoute\n}\n",
        )
        assertEquals(
            listOf(
                // In AppNav, but outside downloadScreens.
                "$APP/AppNav.kt:7: DownloadsRoute",
                // A screen of the package's own, found by its declaration, and a route, in a file with no block.
                "$APP/home/HomeScreen.kt:1: DownloadsScreen",
                "$APP/home/HomeScreen.kt:2: DownloadSettingsRoute",
                // In a function that isn't the allowed block.
                "$APP/settings/SettingsScreen.kt:2: DownloadSettingsRoute",
            ),
            misplaced(files),
        )
        // The allowed block that doesn't take shown is no gate.
        val ungated = "fun NavGraphBuilder.downloadScreens(nav: NavController) {\n    composable<DownloadsRoute> {}\n}\n"
        assertEquals(listOf("$APP/AppNav.kt:2: DownloadsRoute (in downloadScreens, which doesn't take shown)"), misplaced(mapOf("$APP/AppNav.kt" to ungated)))
        // Whole names alone: a name that only starts with one is someone else's.
        assertEquals(emptyList(), misplaced(mapOf("$APP/Routes.kt" to "@Serializable data object DownloadsRouteHelp")))
    }

    /** A declaration's [text], from its keyword to its body's closing brace, and whether its parameters take `shown`. */
    private class Block(val range: IntRange, val text: String, val takesShown: Boolean)

    /** The routes and screens the download package declares in [files]. */
    private fun declaredIn(files: Map<String, String>): List<String> =
        files.filterKeys { it.startsWith("$APP/download/") }.values.flatMap { declared.findAll(it).map { m -> m.groupValues[1] } }.distinct()

    /** Each download route or screen named outside `app/download/` and the allowed block taking `shown`: "path:line: name". */
    private fun misplaced(files: Map<String, String>): List<String> {
        val names = (routes + declaredIn(files)).distinct()
        val pattern = Regex("""\b(${names.joinToString("|") { Regex.escape(it) }})\b""")
        return files.filterKeys { !it.startsWith("$APP/download/") }.toSortedMap().flatMap { (path, text) ->
            val blocks = allowed.filter { it.first == path.substringAfterLast('/') }.mapNotNull { (_, name) -> blockIn(text, name)?.let { name to it } }
            pattern.findAll(text).mapNotNull { found ->
                val at = "$path:${text.substring(0, found.range.first).count { it == '\n' } + 1}: ${found.value}"
                when (val block = blocks.firstOrNull { found.range.first in it.second.range }) {
                    null -> at
                    else -> if (block.second.takesShown) null else "$at (in ${block.first}, which doesn't take shown)"
                }
            }.toList()
        }
    }

    /**
     * [name]'s declaration in [text] (a function or an extension function), from its keyword to the brace that closes the
     * body opened after its parameter list; null where there is none.
     */
    private fun blockIn(text: String, name: String): Block? {
        val start = Regex("""\bfun\s+(\w+\.)?${Regex.escape(name)}\b""").find(text) ?: return null
        val open = text.indexOf('(', start.range.last).takeIf { it >= 0 } ?: return null
        val params = closing(text, open, '(', ')') ?: return null
        val body = text.indexOf('{', params).takeIf { it >= 0 } ?: return null
        val end = closing(text, body, '{', '}') ?: return null
        return Block(start.range.first..end, text.substring(start.range.first, end + 1), Regex("""\bshown\b""").containsMatchIn(text.substring(open, params)))
    }

    /** The index of the [close] that matches the [open] at [from]. */
    private fun closing(text: String, from: Int, open: Char, close: Char): Int? {
        var depth = 0
        for (i in from until text.length) {
            when (text[i]) {
                open -> depth++
                close -> if (--depth == 0) return i
            }
        }
        return null
    }

    private companion object {
        const val APP = "src/main/kotlin/io/skjaere/usenet/app"

        /** The downloads' routes (L369), whether declared yet or not. */
        val routes = listOf("DownloadsRoute", "DownloadsShowRoute", "DownloadSettingsRoute")

        /** A route or screen declared in the download package: guarded as the routes are. */
        val declared = Regex("""\b(?:class|object|fun)\s+(?:\w+\.)?(\w+(?:Route|Screen))\b""")

        /** The block outside the download package that may name its routes and screens, by file name and declaration. */
        val allowed = listOf("AppNav.kt" to "downloadScreens")
    }
}
