package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.ui.FormFactor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MainActivity's download extras (item 11, L369, L380, C4): the activity is exported, so anything may send them. They are
 * read only where downloads show and never from Recents; Resume and Play act only with the app's own token, which only
 * its own notifications carry, and any other sender's ask only opens the page. The token is a random value in the app's
 * private files. The ids are invented.
 */
class DownloadIntentsTest {
    @TempDir
    lateinit var dir: File

    private val token = "0f".repeat(32)

    private fun ask(extras: DownloadExtras, shown: Boolean = true, fromRecents: Boolean = false) =
        DownloadIntents.ask(extras, shown, fromRecents, matches = { it == token })

    @Test
    fun `the page, a resume, a play and a release to choose, as the app's notifications ask them`() {
        assertEquals(DownloadAsk.Open, ask(DownloadExtras(open = true)))
        assertEquals(DownloadAsk.Resume, ask(DownloadExtras(open = true, resume = true, token = token)))
        assertEquals(DownloadAsk.Play(ID), ask(DownloadExtras(open = true, play = ID, token = token)))
        // Choose release opens a row's list, which acts only once the user picks there: no token needed.
        assertEquals(DownloadAsk.Choose(ID), ask(DownloadExtras(open = true, choose = ID)))
        assertNull(ask(DownloadExtras()), "no download extra: not the downloads' ask")
    }

    @Test
    fun `a resume or a play without the app's token only opens the page`() {
        listOf(null, "", "0f".repeat(31) + "0e", token.uppercase()).forEach { other ->
            assertEquals(DownloadAsk.Open, ask(DownloadExtras(open = true, resume = true, token = other)), "$other")
            assertEquals(DownloadAsk.Open, ask(DownloadExtras(open = true, play = ID, token = other)), "$other")
        }
        // The page's own extra left out: the same.
        assertEquals(DownloadAsk.Open, ask(DownloadExtras(resume = true)))
        assertEquals(DownloadAsk.Open, ask(DownloadExtras(play = ID)))
    }

    @Test
    fun `an id that isn't a download's only opens the page`() {
        listOf("", "../row", "a b", "x".repeat(65), "id?play=1").forEach { bad ->
            assertEquals(DownloadAsk.Open, ask(DownloadExtras(open = true, play = bad, token = token)), bad)
            assertEquals(DownloadAsk.Open, ask(DownloadExtras(open = true, choose = bad)), bad)
        }
    }

    @Test
    fun `nothing is read where downloads don't show, nor when Recents starts the app with the intent`() {
        val asks = listOf(
            DownloadExtras(open = true), DownloadExtras(open = true, resume = true, token = token), DownloadExtras(open = true, play = ID, token = token),
            DownloadExtras(open = true, choose = ID),
        )
        asks.forEach { extras ->
            assertNull(ask(extras, shown = false), "$extras not shown")
            assertNull(ask(extras, fromRecents = true), "$extras from Recents")
        }
        // Where downloads show is DownloadPlaces' one rule: a phone's layout on a device that isn't a TV.
        assertTrue(DownloadPlaces.shown(FormFactor.Phone, television = false))
        assertFalse(DownloadPlaces.shown(FormFactor.Tv, television = false))
        assertFalse(DownloadPlaces.shown(FormFactor.Phone, television = true))
    }

    @Test
    fun `each ask goes to the Downloads page's ways in, a resume resuming as well, and those not there yet open the page`() {
        val opened = mutableListOf<String>()
        var resumed = 0
        val pages = DownloadPages(
            downloads = { opened += "downloads" },
            chooseRelease = { id -> opened += "choose $id" },
            play = { id -> opened += "play $id" },
        )
        pages.take(DownloadAsk.Open) { resumed++ }
        pages.take(DownloadAsk.Resume) { resumed++ }
        pages.take(DownloadAsk.Play(ID)) { resumed++ }
        pages.take(DownloadAsk.Choose(ID)) { resumed++ }
        assertEquals(listOf("downloads", "downloads", "play $ID", "choose $ID"), opened)
        assertEquals(1, resumed)
        // Before the page is there (item 19), a play or a choice opens what there is; Resume still resumes.
        val page = mutableListOf<String>()
        val bare = DownloadPages(downloads = { page += "downloads" })
        bare.take(DownloadAsk.Play(ID)) {}
        bare.take(DownloadAsk.Choose(ID)) {}
        assertEquals(listOf("downloads", "downloads"), page)
        DownloadPages().take(DownloadAsk.Resume) { resumed++ }
        assertEquals(2, resumed)
    }

    @Test
    fun `the downloads' pages are none where downloads don't show`() {
        var registered = 0
        val hidden = downloadPages(shown = false, register = { registered++ }, open = {})
        assertEquals(DownloadPages(), hidden)
        assertEquals(0, registered)
        downloadPages(shown = true, register = { registered++ }, open = {})
        assertEquals(1, registered)
    }

    @Test
    fun `where downloads show, the ways in open the Downloads page, at a download's releases or playing it, a show's downloads and its settings`() {
        val opened = mutableListOf<Any>()
        val pages = downloadPages(shown = true, register = {}, open = { opened += it })
        pages.take(DownloadAsk.Open) {}
        pages.take(DownloadAsk.Choose(ID)) {}
        pages.take(DownloadAsk.Play(ID)) {}
        // Home's row for a show's finished episodes (L384).
        pages.show!!.invoke(SHOW)
        pages.settings!!.invoke()
        assertEquals(
            listOf(DownloadsRoute(), DownloadsRoute(choose = ID), DownloadsRoute(play = ID), DownloadsShowRoute(SHOW), DownloadSettingsRoute),
            opened,
        )
        val hidden = downloadPages(shown = false, register = {}, open = { opened += it })
        assertNull(hidden.settings)
        assertNull(hidden.show)
    }

    @Test
    fun `the token is random, kept in the app's own files, and the same for the next process`() {
        val first = DownloadToken(dir)
        val value = first.value
        assertTrue(Regex("[0-9a-f]{64}").matches(value), value)
        assertEquals(value, File(dir, DownloadToken.FILE).readText())
        assertEquals(value, DownloadToken(dir).value, "read back")
        assertTrue(first.matches(value))
        assertFalse(first.matches(null))
        assertFalse(first.matches(value.dropLast(1) + if (value.last() == '0') "1" else "0"))
        assertNotEquals(value, DownloadToken(File(dir, "other")).value, "another folder, another token")
    }

    @Test
    fun `a token file of another shape is made again`() {
        File(dir, DownloadToken.FILE).writeText("guessable")
        val value = DownloadToken(dir).value
        assertTrue(Regex("[0-9a-f]{64}").matches(value), value)
        assertEquals(value, File(dir, DownloadToken.FILE).readText())
    }

    private companion object {
        const val ID = "c0ffee00-0000-4000-8000-000000000001"
        const val SHOW = 990_717
    }
}
