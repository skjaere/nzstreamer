package io.skjaere.usenet.app.download

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * L418, L470: every download row plays where Play on points, through the router (PlayRouter.playDownload): the Downloads
 * page's rows and its film's ▶, a show's downloads' rows and Resume, both notifications' Play (each through its page's
 * ask) and Home's Downloads rows. So the title's play of a row here (the hook then turns it to the file) is
 * DownloadsViewModel.kt's private playHere alone, which only the router's here runs; DownloadsDeps.play is the one way
 * in, and the pages and their view models play through it, never the coordinator. And only the two Downloads pages take
 * TopBar's stacked form (L470). Every source the app builds from is read as text, as DownloadEntrySourcesTest reads them.
 */
class DownloadPlaySourcesTest {
    private val roots = listOf("src/main/kotlin", "src/discover/kotlin", "src/release/kotlin").map(::File)

    private val sources: Map<String, String> = roots.flatMap { root -> root.walk().filter { it.isFile && it.extension == "kt" }.toList() }
        .associate { it.invariantSeparatorsPath to it.readText() }

    private fun source(path: String): String = assertNotNull(sources["$APP/$path"], "no $APP/$path")

    @Test
    fun `the title's play of a download row here is playHere's alone, and only the router's here runs it`() {
        assertTrue(sources.size > 100, "found ${sources.size} files from ${File(".").absolutePath}")
        val model = source("download/DownloadsViewModel.kt")
        val here = assertNotNull(blockIn(model, "playHere"), "no playHere in DownloadsViewModel.kt")
        assertTrue(here.text.startsWith("private fun playHere"), "playHere isn't private: ${here.text.lines().first()}")
        // The coordinator named in the file only inside playHere.
        assertEquals(emptyList(), lines(model, PLAYBACK).filter { it.first !in here.range }.map { "DownloadsViewModel.kt:${it.second}" }, "a play past the router")
        // playHere (private to the file) run only as the router's here, in DownloadsDeps.play.
        val calls = lines(model, PLAY_HERE_CALL)
        val routed = assertNotNull(ROUTED.find(model), "DownloadsDeps.play doesn't run playHere as the router's here")
        assertEquals(1, calls.size, "playHere called at DownloadsViewModel.kt:${calls.map { it.second }}")
        assertTrue(calls.single().first in routed.range, "playHere called outside the router's here")
        val play = assertNotNull(blockIn(model, "play"), "no DownloadsDeps.play")
        assertTrue(routed.range.first in play.range, "the router's here isn't DownloadsDeps.play's")
    }

    @Test
    fun `every way a download row plays goes through DownloadsDeps play, never the coordinator`() {
        val model = source("download/DownloadsViewModel.kt")
        // The pages' rows, their film's ▶ and Resume, and both notifications' asks (each a page's take): the deps' play.
        assertTrue(DEPS_PLAY.containsMatchIn(model), "DownloadsDeps.of's play isn't play(container, row)")
        val takes = TAKE.findAll(model).mapNotNull { blockAt(model, it.range.first) }.toList()
        assertEquals(2, takes.size, "the two pages' takes")
        takes.forEach { take -> assertTrue("deps.play" in take.text || Regex("""\.let\s*\(\s*deps\.play\s*\)""").containsMatchIn(take.text), take.text) }
        // Home's Downloads rows.
        val home = assertNotNull(blockIn(source("home/HomeViewModel.kt"), "playDownload"), "no Home playDownload")
        assertTrue("DownloadsDeps.play(container, row)" in home.text, home.text)
        assertFalse(PAST.containsMatchIn(home.text), home.text)
        // The pages never reach the coordinator or the router themselves, and nothing names playHere from outside.
        listOf("download/DownloadsScreen.kt", "download/DownloadsShowScreen.kt").forEach { path ->
            assertEquals(emptyList(), lines(source(path), PAST).map { "$path:${it.second}" })
        }
        assertEquals(emptyList(), sources.filter { (_, text) -> "DownloadsDeps.playHere" in text }.keys.toList())
    }

    /**
     * L458, L473: a file opened on Import has no title whose play the hook turns to its file, so a finished one plays here
     * through the hook by its watch key (PlaybackCoordinator.playDownloaded): the pages' rows and their notifications through
     * playHere's branch for it, and Import's tap through the router, with its finished download, whose here plays it first.
     */
    @Test
    fun `an imported file's finished download plays here through the hook by its watch key, from the pages and from Import`() {
        val here = assertNotNull(blockIn(source("download/DownloadsViewModel.kt"), "playHere"), "no playHere in DownloadsViewModel.kt")
        assertTrue(RELEASE_HERE.containsMatchIn(here.text), here.text)
        val import = source("ImportScreen.kt")
        assertTrue(IMPORT_TAP.containsMatchIn(import), "Import's tap doesn't hand the router its finished download")
        assertTrue(IMPORT_HERE.containsMatchIn(import), "Import's here doesn't play the finished download first")
    }

    @Test
    fun `only the two Downloads pages take TopBar's stacked form`() {
        // TopBar is the UI kit's, in its own module.
        val bar = assertNotNull(blockIn(File(KIT_COMPONENTS).readText(), "TopBar"), "no TopBar")
        assertTrue(Regex("""\bstacked\s*:\s*Boolean\s*=\s*false\b""").containsMatchIn(bar.text.substringBefore('{')), "TopBar's stacked isn't an opt-in")
        val takers = sources.filter { (_, text) -> STACKED.containsMatchIn(text) }.keys.sorted()
        assertEquals(listOf("$APP/download/DownloadsScreen.kt", "$APP/download/DownloadsShowScreen.kt"), takers)
    }

    @Test
    fun `the patterns find what they look for`() {
        // The checks above would pass on patterns that find nothing: as the code would write each.
        assertTrue(ROUTED.containsMatchIn("internal fun play(container: AppContainer, row: Download) = container.playRouter.playDownload(row) { playHere(container, row) }"))
        assertTrue(PLAYBACK.containsMatchIn("container.playback.playEpisode(target, PlayOrigin.Here)"))
        assertTrue(PLAY_HERE_CALL.containsMatchIn("play = { row -> playHere(container, row) },"))
        assertTrue(DEPS_PLAY.containsMatchIn("play = { row -> play(container, row) },"))
        assertTrue(STACKED.containsMatchIn("TopBar(DownloadsPage.TITLE, onBack, stacked = true) {"))
        assertTrue(TAKE.containsMatchIn("fun take(ask: String?, play: Boolean) {"))
        assertTrue(PAST.containsMatchIn("container.playRouter.upNext(row.play)") && PAST.containsMatchIn("container.playback.playMovie(target, origin)"))
        assertFalse(PLAY_HERE_CALL.containsMatchIn("private fun playHere(container: AppContainer, row: Download) {"))
        assertTrue(RELEASE_HERE.containsMatchIn("DownloadKind.Release -> container.playback.playDownloaded(row.watchKey, PlayOrigin.Here)"))
        assertTrue(IMPORT_TAP.containsMatchIn("container.playRouter.playFile(ready.nzb.id, file, done) {"))
        assertTrue(IMPORT_HERE.containsMatchIn("val played = done != null && container.playback.playDownloaded(done.watchKey)"))
        val text = "class A {\n    private fun playHere(c: C) {\n        c.go()\n    }\n}\n"
        assertEquals("private fun playHere(c: C) {\n        c.go()\n    }", blockIn(text, "playHere")?.text)
    }

    /** A declaration's [text], from its keyword to its body's last character, and where it is. */
    private class Block(val range: IntRange, val text: String)

    /** Each match of [pattern] in [text]: where it starts, and its line number. */
    private fun lines(text: String, pattern: Regex): List<Pair<Int, Int>> =
        pattern.findAll(text).map { it.range.first to text.substring(0, it.range.first).count { c -> c == '\n' } + 1 }.toList()

    /** [name]'s declaration in [text], with its modifiers: its block body to the closing brace, or its expression to the line's end. */
    private fun blockIn(text: String, name: String): Block? {
        val start = Regex("""(?:(?:private|internal)\s+)?fun\s+(?:\w+\.)?${Regex.escape(name)}\s*\(""").find(text) ?: return null
        return blockAt(text, start.range.first)
    }

    /** The declaration starting at [from]: past its parameter list, a block body or an expression body to its line's end. */
    private fun blockAt(text: String, from: Int): Block? {
        val open = text.indexOf('(', from).takeIf { it >= 0 } ?: return null
        val params = closing(text, open, '(', ')') ?: return null
        val rest = text.substring(params + 1)
        val body = rest.indexOfFirst { it == '{' || it == '=' }.takeIf { it >= 0 }?.plus(params + 1) ?: return null
        val end = if (text[body] == '{') closing(text, body, '{', '}') ?: return null else text.indexOf('\n', body).takeIf { it >= 0 }?.minus(1) ?: text.lastIndex
        return Block(from..end, text.substring(from, end + 1))
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
        const val KIT_COMPONENTS = "../core/ui/src/main/kotlin/io/skjaere/usenet/app/ui/Components.kt"

        /** The coordinator's title plays. */
        val PLAYBACK = Regex("""\bplayback\s*\.\s*play(Episode|Movie)\s*\(""")

        /** A call of playHere. */
        val PLAY_HERE_CALL = Regex("""(?<!fun\s)\bplayHere\s*\(""")

        /** The router's play of a download row, whose here is playHere. */
        val ROUTED = Regex("""\bplayRouter\s*\.\s*playDownload\s*\(\s*row\s*\)\s*\{\s*playHere\s*\(\s*container\s*,\s*row\s*\)\s*\}""")

        /** DownloadsDeps.of's play: the one way in. */
        val DEPS_PLAY = Regex("""\bplay\s*=\s*\{\s*row\s*->\s*play\s*\(\s*container\s*,\s*row\s*\)\s*}""")

        /** A page's take of a notification's ask. */
        val TAKE = Regex("""\bfun\s+take\s*\(""")

        /** A play past the router: the coordinator's title plays, or the router itself. */
        val PAST = Regex("""\bplayback\s*\.\s*play(Episode|Movie)\b|\bplayRouter\b""")

        /** playHere's branch for a file opened on Import: the hook by its watch key. */
        val RELEASE_HERE = Regex("""\bDownloadKind\.Release\s*->\s*container\.playback\.playDownloaded\s*\(\s*row\.watchKey\b""")

        /** Import's tap, through the router with the file's finished download. */
        val IMPORT_TAP = Regex("""\bplayRouter\s*\.\s*playFile\s*\(\s*ready\.nzb\.id\s*,\s*file\s*,\s*done\s*\)""")

        /** Import's here: the finished download played first. */
        val IMPORT_HERE = Regex("""\bplayback\s*\.\s*playDownloaded\s*\(\s*done\.watchKey\b""")

        /** TopBar's stacked form taken. */
        val STACKED = Regex("""\bstacked\s*=\s*true\b""")
    }
}
