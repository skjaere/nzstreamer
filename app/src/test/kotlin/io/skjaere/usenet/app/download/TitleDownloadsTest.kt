package io.skjaere.usenet.app.download

import io.skjaere.nzbstreamer.stream.StreamableFile
import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.app.debrid.ServiceRegistry
import io.skjaere.usenet.app.download.DownloadHostRules.ScheduleCause
import io.skjaere.usenet.app.settings.TestCipher
import io.skjaere.usenet.app.settings.memorySettings
import io.skjaere.usenet.core.FileCandidate
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadGap
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.ServiceSignIn
import io.skjaere.usenet.data.SettingsRepository
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.debrid.ServiceKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A title's download as the Show page's rows, the Movie page's button and Search's card read it (L384): only what they say,
 * from the runner's row and its live bytes; and what their entries do, as the Downloads page does (L379, L380): Pause and
 * Resume the queue's, Download again, and Remove with the Undo bar, its files going a second after the bar has.
 */
class TitleDownloadsTest {
    private val saved = Locale.getDefault()

    // Sizes are said with the device's decimal mark: these read as written here.
    @BeforeEach
    fun pin() = Locale.setDefault(Locale.US)

    @AfterEach
    fun unpin() = Locale.setDefault(saved)

    private fun row(
        id: String,
        episode: Int,
        state: DownloadState,
        size: Long? = null,
        indexerSize: Long = 1_400_000_000,
        file: Boolean = false,
        offset: Long = 0,
        created: Long = 1,
    ) = Download(
        id = id, watchKey = WatchKey.episode(HL, 2, episode), kind = DownloadKind.Episode, title = "Harbor Lights", showTmdbId = HL, season = 2,
        episode = episode, state = state, position = created, size = size, indexerSize = indexerSize, safeOffset = offset,
        relativePath = if (file) "downloads/$id/v.mkv" else null, createdAt = created, updatedAt = created,
    )

    @Test
    fun `a title's download says its state, how far once its file is made, its size, its missing parts and its tries`() {
        val written = row("a", 4, DownloadState.Running, size = 1_300_000_000, file = true, offset = 100_000_000)
        // The live write's bytes, not the record's: 546 of 1300 MB.
        assertEquals(TitleDownload("a", DownloadState.Running, null, 42, 1_300_000_000), TitleDownload.of(DownloadRunner.Item(written, 546_000_000, 8_000_000)))
        // No file made yet: no share of it, and the indexer's size until Resolve has the exact one.
        assertEquals(TitleDownload("b", DownloadState.Queued, null, null, 1_400_000_000), TitleDownload.of(DownloadRunner.Item(row("b", 5, DownloadState.Queued), 0, null)))
        val done = row("c", 3, DownloadState.Done, size = 1_300_000_000, file = true, offset = 1_300_000_000)
            .copy(gaps = listOf(DownloadGap(10, 20), DownloadGap(40, 50)), tried = listOf("x", "y"))
        assertEquals(TitleDownload("c", DownloadState.Done, null, 100, 1_300_000_000, partsMissing = 2, tried = 2), TitleDownload.of(DownloadRunner.Item(done, done.safeOffset, null)))
    }

    @Test
    fun `a show's downloads by episode, the latest added for an episode downloaded twice`() {
        val items = listOf(
            row("old", 4, DownloadState.Failed, created = 1),
            row("new", 4, DownloadState.Queued, created = 2),
            row("e5", 5, DownloadState.Done, size = 1_300_000_000, file = true, offset = 1_300_000_000),
        ).map { DownloadRunner.Item(it, it.safeOffset, null) }
        val byEpisode = TitleDownloads.byEpisode(items)
        assertEquals(setOf(2 to 4, 2 to 5), byEpisode.keys)
        assertEquals("new", byEpisode.getValue(2 to 4).id)
        assertEquals(DownloadState.Done, byEpisode.getValue(2 to 5).state)
    }

    /**
     * L434: on the Show page an episode waiting on its indexer's limit says the limit's words in the warning colour, under
     * the queued arrow, and its ⋮ offers Resume download and Remove download, no Pause, as for any wait that needs the user;
     * the Movie page's button says it under "Queued for download". Neither names the indexer.
     */
    @Test
    fun `an episode waiting on its indexer's limit says so in the warning colour, with Resume download and Remove download`() {
        val waiting = TitleDownload.of(DownloadRunner.Item(row("l", 6, DownloadState.Waiting).copy(reason = DownloadHostRules.LIMIT_REACHED), 0, null))
        assertEquals(listOf(PageLine("Your indexer's limit is reached", Tone.Warn)), TitleDownloads.line(waiting))
        assertEquals(DownloadMark.Queued, TitleDownloads.mark(waiting))
        assertEquals(listOf(DownloadEntry.Resume, DownloadEntry.Remove), TitleDownloads.entries(waiting, source = true))
        assertEquals(DownloadHostRules.LIMIT_REACHED, TitleDownloads.spoken(waiting))
        val button = TitleDownloads.button(waiting)
        assertEquals(TitleDownloads.QUEUED, button.text)
        assertEquals(PageLine(DownloadHostRules.LIMIT_REACHED, Tone.Warn), button.second)
        assertEquals(listOf(DownloadEntry.Resume, DownloadEntry.Remove, DownloadEntry.OpenDownloads), TitleDownloads.menu(waiting))
    }

    /**
     * L471, L474: a debrid download waiting while something streams says so muted, as a need's wait is, with how far it is,
     * on its episode's row, the Movie page's and Search's button, Home's and Import's rows (all read this line), and keeps
     * Pause; its service out of reach while its writer backs off is said in the warning colour, as the provider's is; its
     * account's waits that only Resume or a save end say their words in the warning colour, with Resume download.
     */
    @Test
    fun `a debrid download's stream wait is said muted with how far it is, its account's waits in the warning colour`() {
        val streams = TitleDownload.of(
            DownloadRunner.Item(row("s", 5, DownloadState.Running, size = 1_300_000_000, file = true).copy(reason = DownloadHostRules.WAITS_FOR_STREAM), 546_000_000, null),
        )
        assertEquals(listOf(PageLine("Waits while something streams · 42%", Tone.Muted)), TitleDownloads.line(streams))
        assertEquals("Waits while something streams, 42 percent", TitleDownloads.spoken(streams))
        assertEquals(listOf(DownloadEntry.Pause, DownloadEntry.Remove), TitleDownloads.entries(streams, source = true))
        val button = TitleDownloads.button(streams)
        assertEquals(TitleDownloads.DOWNLOADING, button.text)
        assertEquals(PageLine("Waits while something streams · 42%", Tone.Muted), button.second)

        assertEquals(listOf(PageLine("Can't reach your debrid service · 42%", Tone.Warn)), TitleDownloads.line(streams.copy(reason = DownloadHostRules.CANT_REACH_SERVICE)))
        DownloadHostRules.SERVICE_WAITS.forEach { reason ->
            val waiting = TitleDownload.of(DownloadRunner.Item(row("w", 6, DownloadState.Waiting).copy(reason = reason), 0, null))
            assertEquals(listOf(PageLine(reason, Tone.Warn)), TitleDownloads.line(waiting), reason)
            assertEquals(listOf(DownloadEntry.Resume, DownloadEntry.Remove), TitleDownloads.entries(waiting, source = true), reason)
            assertEquals(PageLine(reason, Tone.Warn), TitleDownloads.button(waiting).second, reason)
        }
    }

    /**
     * C4, L415: with Delete once watched on, a finished download that will go and isn't due yet says "Deleting tomorrow": on
     * the episode row after its size (or its missing parts), and TalkBack hears it as part of the row's state; on the Movie
     * page's and Search's card's button under "Downloaded", in its detail's place. A show's downloads by episode carry it,
     * a finished one's alone. One not going says what it said before.
     */
    @Test
    fun `a finished download going tomorrow says Deleting tomorrow on its row and its button`() {
        val done = row("c", 3, DownloadState.Done, size = 1_300_000_000, file = true, offset = 1_300_000_000)
        val going = TitleDownload.of(DownloadRunner.Item(done, done.safeOffset, null), deleting = true)
        assertTrue(going.deleting)
        assertEquals(listOf(PageLine("Downloaded · 1.3 GB · Deleting tomorrow", Tone.Muted)), TitleDownloads.line(going))
        assertEquals("Downloaded, deleting tomorrow", TitleDownloads.spoken(going))
        assertEquals(DownloadButtonWords("Downloaded", PageLine("Deleting tomorrow", Tone.Muted), DownloadMark.Done), TitleDownloads.button(going))
        // Its parts missing still said, in the warning colour, before it.
        val missing = going.copy(partsMissing = 2)
        assertEquals(
            listOf(PageLine("Downloaded · ", Tone.Muted), PageLine("2 parts missing", Tone.Warn), PageLine(" · Deleting tomorrow", Tone.Muted)),
            TitleDownloads.line(missing),
        )
        assertEquals("Downloaded, 2 parts missing, deleting tomorrow", TitleDownloads.spoken(missing))
        assertEquals(PageLine("Deleting tomorrow", Tone.Muted), TitleDownloads.button(missing).second)
        // Not going: as before.
        val kept = TitleDownload.of(DownloadRunner.Item(done, done.safeOffset, null))
        assertFalse(kept.deleting)
        assertEquals(listOf(PageLine("Downloaded · 1.3 GB", Tone.Muted)), TitleDownloads.line(kept))
        assertEquals("Downloaded", TitleDownloads.spoken(kept))
        assertEquals(PageLine("1.3 GB", Tone.Muted), TitleDownloads.button(kept).second)
        // Only a finished one is going: one still to finish, said to be, isn't.
        val queued = row("q", 5, DownloadState.Queued)
        assertFalse(TitleDownload.of(DownloadRunner.Item(queued, 0, null), deleting = true).deleting)

        val items = listOf(done, queued, row("e6", 6, DownloadState.Done, size = 1_300_000_000, file = true)).map { DownloadRunner.Item(it, it.safeOffset, null) }
        val byEpisode = TitleDownloads.byEpisode(items, deleting = setOf("c", "q"))
        assertTrue(byEpisode.getValue(2 to 3).deleting)
        assertFalse(byEpisode.getValue(2 to 5).deleting)
        assertFalse(byEpisode.getValue(2 to 6).deleting)
    }

    /**
     * L409: the Show page's line under the watched bar: how many of the show's episodes are downloaded and their size, and
     * while some are still to finish how many, the one being written among them; none until one is downloaded. Only a
     * finished one whose file plays from its row counts as downloaded (L407), as the play hook's launch takes it.
     */
    @Test
    fun `the show's line counts its finished episodes and their size, and those still to finish`() {
        fun d(state: DownloadState, size: Long = 1_300_000_000, plays: Boolean = state == DownloadState.Done) = TitleDownload("x", state, size = size, plays = plays)
        val done = listOf(d(DownloadState.Done), d(DownloadState.Done, 1_400_000_000), d(DownloadState.Done, 1_400_000_000))
        assertEquals("3 downloaded · 4.1 GB", TitleDownloads.showLine(done + d(DownloadState.Failed) + d(DownloadState.Deleting)))
        val going = listOf(DownloadState.Queued, DownloadState.Resolving, DownloadState.Running, DownloadState.Paused, DownloadState.Waiting).map { d(it) }
        assertEquals("3 downloaded · 4.1 GB · 5 in the queue", TitleDownloads.showLine(done + going + d(DownloadState.Failed)))
        assertEquals("1 downloaded · 1.3 GB · 1 in the queue", TitleDownloads.showLine(listOf(d(DownloadState.Done), d(DownloadState.Stopped))))
        // One whose file the play hook can't take doesn't count; with none counted, there is no line, whatever is queued.
        assertEquals("1 downloaded · 1.3 GB", TitleDownloads.showLine(listOf(d(DownloadState.Done), d(DownloadState.Done, plays = false))))
        assertNull(TitleDownloads.showLine(going + d(DownloadState.Done, plays = false)))
        assertNull(TitleDownloads.showLine(emptyList()))
    }

    /** A title's download says whether its file plays from its row: finished, with the release it came from kept with its NZB's id. */
    @Test
    fun `a finished download plays from its row only with its release kept`() {
        val release = RememberedRelease("fake-indexer", "hl-s02e03", "Harbor.Lights.S02E03.1080p.WEB-KESTREL", null, 0, "nzb-c")
        val done = row("c", 3, DownloadState.Done, size = 1_300_000_000, file = true, offset = 1_300_000_000).copy(release = release)
        assertTrue(TitleDownload.of(DownloadRunner.Item(done, done.safeOffset, null)).plays)
        assertFalse(TitleDownload.of(DownloadRunner.Item(done.copy(release = null), done.safeOffset, null)).plays)
        assertFalse(TitleDownload.of(DownloadRunner.Item(done.copy(release = release.copy(nzbId = null)), done.safeOffset, null)).plays)
        // Resolve keeps its release before the file is written: a file still to finish doesn't play.
        assertFalse(TitleDownload.of(DownloadRunner.Item(done.copy(state = DownloadState.Running), 0, null)).plays)
    }

    /** The runner's calls and the host's schedule, as the actions make them, in order. */
    private val calls = mutableListOf<String>()

    private fun TestScope.actions(): TitleDownloadActions {
        val rows = mapOf("a" to row("a", 4, DownloadState.Done, size = 1_300_000_000, file = true, offset = 1_300_000_000))
        val deps = TitleDownloadActions.Deps(
            get = { rows[it] },
            pause = { calls += "pause" },
            resume = { calls += "resume" },
            remove = { ids, keepFor -> calls += "remove $ids $keepFor" },
            undoRemove = { ids -> calls += "undo $ids" },
            retry = { id -> calls += "retry $id" },
            schedule = { cause -> calls += "schedule $cause" },
            undoMillis = 10_000,
        )
        return TitleDownloadActions(deps, backgroundScope, backgroundScope)
    }

    private fun d(state: DownloadState) = TitleDownload("a", state)

    @Test
    fun `Pause and Resume are the queue's, each asking for the host from here, and Download again tries it again`() = runTest {
        val actions = actions()
        actions.take(DownloadEntry.Pause, d(DownloadState.Running), keepFor = 10_000)
        actions.take(DownloadEntry.Resume, d(DownloadState.Paused), keepFor = 10_000)
        actions.take(DownloadEntry.DownloadAgain, d(DownloadState.Failed), keepFor = 10_000)
        // The sheet and the page are the page's own: nothing of the runner's.
        actions.take(DownloadEntry.Download, d(DownloadState.Done), keepFor = 10_000)
        actions.take(DownloadEntry.OpenDownloads, d(DownloadState.Done), keepFor = 10_000)
        runCurrent()
        assertEquals(listOf("pause", "resume", "schedule ${ScheduleCause.Resume}", "retry a", "schedule ${ScheduleCause.Download}"), calls)
    }

    /**
     * L469: on Import, Download again queues the failed file again with the NZB Import holds for it, through the runner's
     * enqueueImport, which writes its copy again and puts the row back; never the runner's Try again, which would find the
     * copy gone. Then the host is asked for from here. With Import no longer showing its NZB, nothing is queued.
     */
    @Test
    fun `Download again on Import queues the file again with its NZB through enqueueImport`() = runTest {
        val source = DownloadStorage.Source("<nzb/>".toByteArray(), "typed-on-import")
        val file = FileCandidate(1, StreamableFile("Quiet.Meridian.S01E02.1080p.WEB-DL.H264.mkv", 1_300_000_000, 1, 0, 0, 0))
        val request = DownloadRequest.File("nzb-qm", file, "Quiet.Meridian.S01.1080p.WEB-DL.H264", videos = 2, source)
        val failed = row("b", 2, DownloadState.Failed)
        val queued = mutableListOf<String>()
        var shown: DownloadRequest.File? = request
        val deps = TitleDownloadActions.Deps.importing(
            TitleDownloadActions.Deps(
                get = { if (it == "b") failed else null }, pause = {}, resume = {}, remove = { _, _ -> }, undoRemove = {},
                retry = { error("Try again finds the copy gone") }, schedule = { cause -> calls += "schedule $cause" },
            ),
            request = { row -> shown.takeIf { row.id == "b" } },
            enqueueImport = { target, candidate, pick, from ->
                queued += "$target ${candidate.guid} ${pick.index} ${pick.name} ${from.nzb.size} ${from.password}"
                failed
            },
        )
        val actions = TitleDownloadActions(deps, backgroundScope, backgroundScope)
        actions.take(DownloadEntry.DownloadAgain, d(DownloadState.Failed).copy(id = "b"), keepFor = 10_000)
        runCurrent()
        assertEquals(listOf("${request.target} nzb-qm 1 ${file.name} 6 typed-on-import"), queued)
        assertEquals(listOf("schedule ${ScheduleCause.Download}"), calls)
        // Import shows another NZB, or none: nothing is queued.
        shown = null
        actions.take(DownloadEntry.DownloadAgain, d(DownloadState.Failed).copy(id = "b"), keepFor = 10_000)
        runCurrent()
        assertEquals(1, queued.size)
    }

    @Test
    fun `Remove hides it at once with the Undo bar, its files going a second after the bar, and Undo brings it back`() = runTest {
        val actions = actions()
        actions.take(DownloadEntry.Remove, d(DownloadState.Done), keepFor = 12_000)
        runCurrent()
        assertEquals(listOf("remove [a] 13000"), calls)
        assertEquals("Deleted Harbor Lights S02E04 · 1.3 GB", actions.offer.value?.message)
        actions.undo()
        runCurrent()
        // Back in the queue, then the host asked for from here: one removed as it ran ended its host's walk (L335).
        assertEquals(listOf("remove [a] 13000", "undo [a]", "schedule ${ScheduleCause.Resume}"), calls)
        assertNull(actions.offer.value)
        // The bar goes by itself once it has shown its time.
        actions.take(DownloadEntry.Remove, d(DownloadState.Done), keepFor = 12_000)
        runCurrent()
        actions.shown(12_000)
        advanceTimeBy(12_001)
        assertNull(actions.offer.value)
    }

    /** The service accounts' registry over [settings], as the container keeps it: nothing here makes a client or calls one. */
    private fun services(settings: SettingsRepository) =
        ServiceRegistry(settings::servicesWithKeys, settings::markKeyRefused, make = { _, _, _, _, _ -> error("no client is made") })

    @Test
    fun `Download is offered while a Usenet provider is saved and on, with its password readable`() = runBlocking<Unit> {
        // The settings as the app keeps them, in memory, its secrets sealed by a stand-in that can lose one; no account.
        val cipher = TestCipher()
        val settings = memorySettings(cipher = cipher)
        val source = downloadSource(settings, services(settings).anyOn(settings.services))
        assertFalse(source.first(), "nothing saved")
        val id = settings.saveProvider(ProviderRecord("", "Provider A", "news.provider-a.invalid", 563, "user-a"), "secret-a")
        assertTrue(source.first())
        settings.setProviderEnabled(id, false)
        assertFalse(source.first(), "switched off")
        settings.setProviderEnabled(id, true)
        assertTrue(source.first())
        cipher.unreadable += "secret-a"
        assertFalse(source.first(), "its password can't be read, as after a device move")
        cipher.unreadable.clear()
        settings.deleteProvider(id)
        assertFalse(source.first(), "deleted")
    }

    /**
     * L422: a debrid service account on is a source too, wherever debrid shows, so a user with only TorBox has the Download
     * entries; none while debrid is hidden, whatever a shown build saved, and none with nothing on: the account switched
     * off, or its key refused by the service.
     */
    @Test
    fun `Download is offered with only a debrid account on, and not while debrid is hidden or nothing is on`() = runBlocking<Unit> {
        val settings = memorySettings()
        val source = downloadSource(settings, services(settings).anyOn(settings.services))
        DebridGate.showing(true) {
            assertFalse(source.first(), "nothing saved")
            val id = settings.saveService(ServiceKind.TorBox.name, "tb-key") { ServiceSignIn("viewer@example.invalid") }
            assertTrue(source.first(), "TorBox's account alone, no Usenet provider")
            settings.setServiceEnabled(id, false)
            assertFalse(source.first(), "switched off")
            settings.setServiceEnabled(id, true)
            settings.markKeyRefused(id)
            assertFalse(source.first(), "its key refused")
            settings.updateSignIn(id, ServiceSignIn("viewer@example.invalid"))
            assertTrue(source.first(), "the key taken again")
        }
        DebridGate.showing(false) {
            assertFalse(source.first(), "debrid hidden: the account a shown build saved counts for nothing")
        }
    }

    private companion object {
        const val HL = 990_717
    }
}
