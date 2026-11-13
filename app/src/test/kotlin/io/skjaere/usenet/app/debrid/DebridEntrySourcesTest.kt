package io.skjaere.usenet.app.debrid

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * L192: a build with debrid hidden offers no way in, and its screens are reached by their routes. So a debrid route or
 * screen is named only in debrid's own package (`app/debrid/`) and in the blocks that decide, from the gate, what shows:
 * AppNav's `debridScreens(shown)`, [settingsHub][io.skjaere.usenet.app.settings.settingsHub] and
 * [SetupState][io.skjaere.usenet.app.home.SetupState], each taking `shown`, with its case in DebridHiddenTest. The setup
 * page's section list (`setupSeed`, which takes `shown` and has its case there too) names none: its debrid cards are the
 * seed's data, never a route (L209). Named anywhere else, a route would be a way in the gate doesn't see. Every source
 * the app builds from is read as text, comments included, as CastStartSourcesTest reads them: main's, Discover's and the
 * release (Play) build's own.
 */
class DebridEntrySourcesTest {
    private val roots = listOf("src/main/kotlin", "src/discover/kotlin", "src/release/kotlin").map(::File)

    private val sources: Map<String, String> = roots.flatMap { root -> root.walk().filter { it.isFile && it.extension == "kt" }.toList() }
        .associate { it.invariantSeparatorsPath to it.readText() }

    @Test
    fun `the sources are read, and the blocks that stand today are found`() {
        roots.forEach { assertTrue(it.isDirectory, "no sources under ${it.absolutePath}") }
        assertTrue(sources.size > 100, "found ${sources.size} files from ${File(".").absolutePath}")
        // The check would pass on blocks it can't find: the two that stand today are found, bodies and all.
        val hub = assertNotNull(blockIn(sources.getValue("$APP/settings/SettingsScreen.kt"), "settingsHub"))
        assertTrue("HubGroup(\"Sources\"" in hub.text, hub.text)
        val setup = assertNotNull(blockIn(sources.getValue("$APP/home/Setup.kt"), "SetupState"))
        assertTrue("val rows: List<SetupRow>" in setup.text, setup.text)
    }

    @Test
    fun `no debrid route or screen is named outside debrid's package and the blocks that take shown`() {
        assertEquals(emptyList(), misplaced(sources), "a way into debrid that DebridGate doesn't close (L192)")
    }

    @Test
    fun `the rule finds a route or screen named where the gate can't see it`() {
        val nav = """
            |fun NavGraphBuilder.debridScreens(nav: NavController, shown: Boolean) {
            |    if (!shown) return
            |    composable<TorrentIndexersRoute> { TorrentIndexersScreen(onOpen = { nav.navigate(TorrentIndexerRoute(it)) }) }
            |}
            |
            |fun NavGraphBuilder.mainScreens(nav: NavController) {
            |    composable<SettingsRoute> { SettingsScreen(onTorrentIndexers = { nav.navigate(TorrentIndexersRoute) }) }
            |}
        """.trimMargin()
        val files = mapOf(
            "$APP/debrid/DebridRoutes.kt" to "@Serializable data object TorrentIndexersRoute\n@Serializable data class TorrentIndexerRoute(val id: String? = null)",
            "$APP/debrid/TorrentIndexerScreens.kt" to "@Composable\nfun TorrentIndexersScreen(onOpen: (String) -> Unit) {\n}\n",
            "$APP/AppNav.kt" to nav,
            "$APP/settings/SettingsScreen.kt" to "fun settingsHub(television: Boolean, optional: Set<HubRow>): List<HubGroup> {\n    // ServiceKeyRoute\n    return emptyList()\n}\n",
            "$APP/search/SearchScreen.kt" to "// Opens the TorrentIndexersScreen.\nval route = DebridServicesRoute\n",
            "$APP/home/Setup.kt" to "data class SetupState(val providers: List<String>, val shown: Boolean = true) {\n    val next get() = if (shown) ServiceRoute else null\n}\n",
        )
        assertEquals(
            listOf(
                // In AppNav, but outside debridScreens.
                "$APP/AppNav.kt:7: TorrentIndexersRoute",
                // A screen of debrid's own, found by its declaration, and a route, in a file with no block.
                "$APP/search/SearchScreen.kt:1: TorrentIndexersScreen",
                "$APP/search/SearchScreen.kt:2: DebridServicesRoute",
                // In an allowed block that doesn't take shown.
                "$APP/settings/SettingsScreen.kt:2: ServiceKeyRoute (in settingsHub, which doesn't take shown)",
            ),
            misplaced(files),
        )
        // Whole names alone: a name that only starts with one is someone else's.
        assertEquals(emptyList(), misplaced(mapOf("$APP/Routes.kt" to "@Serializable data object TorrentIndexersRouteHelp")))
    }

    /** A declaration's [text], from its keyword to its body's closing brace, and whether its parameters take `shown`. */
    private class Block(val range: IntRange, val text: String, val takesShown: Boolean)

    /** Each name of debrid's [routes] named outside `app/debrid/` and the allowed blocks taking `shown`: "path:line: name". */
    private fun misplaced(files: Map<String, String>): List<String> {
        val names = routes + files.filterKeys { it.startsWith("$APP/debrid/") }.values.flatMap { declared.findAll(it).map { m -> m.groupValues[1] } }
        val pattern = Regex("""\b(${names.joinToString("|") { Regex.escape(it) }})\b""")
        return files.filterKeys { !it.startsWith("$APP/debrid/") }.toSortedMap().flatMap { (path, text) ->
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
     * [name]'s declaration in [text] (a function, an extension function or a class), from its keyword to the brace that
     * closes the body opened after its parameter list; null where there is none.
     */
    private fun blockIn(text: String, name: String): Block? {
        val start = Regex("""\b(fun|class)\s+(\w+\.)?${Regex.escape(name)}\b""").find(text) ?: return null
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

        /** Debrid's routes (L192), whether declared yet or not. */
        val routes = listOf("DebridServicesRoute", "ServiceRoute", "ServiceKeyRoute", "TorrentIndexersRoute", "TorrentIndexerRoute")

        /** A route or screen declared in debrid's package: guarded as the routes are. */
        val declared = Regex("""\b(?:class|object|fun)\s+(?:\w+\.)?(\w+(?:Route|Screen))\b""")

        /** The blocks outside debrid's package that may name its routes and screens, by file name and declaration. */
        val allowed = listOf("AppNav.kt" to "debridScreens", "SettingsScreen.kt" to "settingsHub", "Setup.kt" to "SetupState")
    }
}
