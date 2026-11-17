package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.WatchKey
import kotlinx.coroutines.runBlocking
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
 * read only where downloads show and never from Recents; Resume, Play and a show's Play act only with the app's own token,
 * which only its own notifications carry, and any other sender's ask only opens the page. A show's notification names one
 * of its downloads, never the show (L445): the show is read from that row, and a row gone since opens the page. The token
 * is a random value in the app's private files. The ids are invented.
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
    fun `a show's tap opens its downloads and its Play plays its next, the Play only with the app's token`() {
        assertEquals(DownloadAsk.OpenShow(ID), ask(DownloadExtras(open = true, show = ID)))
        assertEquals(DownloadAsk.PlayShow(ID), ask(DownloadExtras(open = true, playShow = ID, token = token)))
        // Its tap only opens a page, as Choose release does: no token needed. Its Play without the app's token only opens the page.
        listOf(null, "", "0f".repeat(31) + "0e").forEach { other ->
            assertEquals(DownloadAsk.Open, ask(DownloadExtras(open = true, playShow = ID, token = other)), "$other")
        }
        assertEquals(DownloadAsk.Open, ask(DownloadExtras(playShow = ID)))
        assertEquals(DownloadAsk.OpenShow(ID), ask(DownloadExtras(show = ID)))
        listOf("", "../row", "a b", "x".repeat(65)).forEach { bad ->
            assertEquals(DownloadAsk.Open, ask(DownloadExtras(open = true, show = bad)), bad)
            assertEquals(DownloadAsk.Open, ask(DownloadExtras(open = true, playShow = bad, token = token)), bad)
        }
    }

    @Test
    fun `a show's asks read the show from the row they name, and a row gone since opens the Downloads page`() = runBlocking<Unit> {
        val opened = mutableListOf<String>()
        val pages = DownloadPages(
            downloads = { opened += "downloads" },
            show = { show -> opened += "show $show" },
            playShow = { show -> opened += "play show $show" },
        )
        val rows = mapOf(ID to row(ID, SHOW), FILM to row(FILM, show = null))
        val rowOf: suspend (String) -> Download? = { rows[it] }
        pages.take(DownloadAsk.OpenShow(ID), rowOf) {}
        pages.take(DownloadAsk.PlayShow(ID), rowOf) {}
        assertEquals(listOf("show $SHOW", "play show $SHOW"), opened)
        // A row id no row has: removed or deleted since the notification was posted. One that names no show: the same.
        opened.clear()
        pages.take(DownloadAsk.OpenShow(GONE), rowOf) {}
        pages.take(DownloadAsk.PlayShow(GONE), rowOf) {}
        pages.take(DownloadAsk.PlayShow(FILM), rowOf) {}
        assertEquals(listOf("downloads", "downloads", "downloads"), opened)
        // Where a show's ways in aren't there, the page.
        opened.clear()
        DownloadPages(downloads = { opened += "downloads" }).take(DownloadAsk.PlayShow(ID), rowOf) {}
        assertEquals(listOf("downloads"), opened)
    }

    @Test
    fun `nothing is read where downloads don't show, nor when Recents starts the app with the intent`() {
        val asks = listOf(
            DownloadExtras(open = true), DownloadExtras(open = true, resume = true, token = token), DownloadExtras(open = true, play = ID, token = token),
            DownloadExtras(open = true, choose = ID), DownloadExtras(open = true, show = ID), DownloadExtras(open = true, playShow = ID, token = token),
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
    fun `each ask goes to the Downloads page's ways in, a resume resuming as well, and those not there yet open the page`() = runBlocking<Unit> {
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
    fun `where downloads show, the ways in open the Downloads page, at a download's releases or playing it, a show's downloads and its settings`() = runBlocking<Unit> {
        val opened = mutableListOf<Any>()
        var asks = 0
        val pages = downloadPages(shown = true, register = {}, open = { opened += it }, newAsk = { "ask-${++asks}" })
        pages.take(DownloadAsk.Open) {}
        pages.take(DownloadAsk.Choose(ID)) {}
        pages.take(DownloadAsk.Play(ID)) {}
        // Home's row for a show's finished episodes (L384).
        pages.show!!.invoke(SHOW)
        pages.settings!!.invoke()
        // A show's notification: its tap, and its Play, which plays the show's next there (L437); twice, each its own ask,
        // so a page already on top takes the second too.
        val rowOf: suspend (String) -> Download? = { row(it, SHOW) }
        pages.take(DownloadAsk.OpenShow(ID), rowOf) {}
        pages.take(DownloadAsk.PlayShow(ID), rowOf) {}
        pages.take(DownloadAsk.PlayShow(ID), rowOf) {}
        assertEquals(
            listOf(
                DownloadsRoute(), DownloadsRoute(choose = ID, ask = "ask-1"), DownloadsRoute(play = ID, ask = "ask-2"), DownloadsShowRoute(SHOW), DownloadSettingsRoute,
                DownloadsShowRoute(SHOW), DownloadsShowRoute(SHOW, play = true, ask = "ask-3"), DownloadsShowRoute(SHOW, play = true, ask = "ask-4"),
            ),
            opened,
        )
        // The app's asks are random ids, each its own.
        val made = mutableListOf<Any>()
        val app = downloadPages(shown = true, register = {}, open = { made += it })
        repeat(2) { app.take(DownloadAsk.Play(ID)) {} }
        val ids = made.map { (it as DownloadsRoute).ask }
        assertEquals(2, ids.filterNotNull().toSet().size)
        val hidden = downloadPages(shown = false, register = {}, open = { opened += it })
        assertNull(hidden.settings)
        assertNull(hidden.show)
        assertNull(hidden.playShow)
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

    /** A finished download of id [id]: an episode of [show], or a film where [show] is null. */
    private fun row(id: String, show: Int?) = Download(
        id = id, watchKey = show?.let { WatchKey.episode(it, 2, 4) } ?: WatchKey.movie(900_003), kind = if (show != null) DownloadKind.Episode else DownloadKind.Movie,
        title = if (show != null) "Harbor Lights" else "Copper Crown", showTmdbId = show, season = show?.let { 2 }, episode = show?.let { 4 },
        movieTmdbId = if (show == null) 900_003 else null, state = DownloadState.Done, position = 0, createdAt = 1, completedAt = 2, updatedAt = 2,
    )

    private companion object {
        const val ID = "c0ffee00-0000-4000-8000-000000000001"
        const val GONE = "c0ffee00-0000-4000-8000-000000000002"
        const val FILM = "c0ffee00-0000-4000-8000-000000000003"
        const val SHOW = 990_717
    }
}
