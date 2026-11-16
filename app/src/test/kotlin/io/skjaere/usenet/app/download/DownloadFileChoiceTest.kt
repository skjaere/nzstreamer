package io.skjaere.usenet.app.download

import io.skjaere.compressionutils.EncryptionInfo
import io.skjaere.nntp.YencHeaders
import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.NzbMetadataResponse
import io.skjaere.nzbstreamer.metadata.PrepareResult
import io.skjaere.nzbstreamer.nzb.FilenameResolver
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.nzb.NzbParser
import io.skjaere.nzbstreamer.stream.StreamableFile
import io.skjaere.usenet.app.download.SheetFixtures.GB
import io.skjaere.usenet.app.download.SheetFixtures.MOBILE
import io.skjaere.usenet.app.download.SheetFixtures.inOrder
import io.skjaere.usenet.app.download.SheetFixtures.nzb
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.FileChoice
import io.skjaere.usenet.app.search.ReleaseListMode
import io.skjaere.usenet.core.FetchException
import io.skjaere.usenet.core.FetchedNzb
import io.skjaere.usenet.core.NzbPreparer
import io.skjaere.usenet.core.PrepareBackend
import io.skjaere.usenet.core.PrepareOutcome
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.StepStatus
import io.skjaere.usenet.core.UsenetError
import io.skjaere.usenet.core.formatSize
import io.skjaere.usenet.logging.LogBuffer
import io.skjaere.usenet.newznab.TvIds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate
import java.util.Locale
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Choose file in download mode (L403, L435), over the Download sheet's model and fakes of the grab and the prepare: the
 * pack's NZB grabbed once and prepared once as the sheet waits, saying each step; its videos with their exact sizes, the
 * episode's named, those that won't fit greyed with what they need; a pick back on the sheet as its Chosen file, whose
 * Download queues the pack first with the pick, the pack's NZB kept with the row and the file's size as its need; a pack
 * that can't be fetched or opened in the play's words, marked in the list; Cancel and Close; and from the Downloads page
 * a pick replacing that download's release at once. Its log lines name a download by its id alone. Invented releases.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadFileChoiceTest {
    private val saved = Locale.getDefault()

    // formatSize follows the default locale; pin one so the decimal separator is predictable.
    @BeforeEach
    fun pin() = Locale.setDefault(Locale.US)

    @AfterEach
    fun unpin() = Locale.setDefault(saved)

    /** 1.5 GB free for a new download: after the 1.0 GB kept free for the phone. */
    private val room = DownloadSpace(available = 1_500_000_000L + DownloadSpace.KEPT_FREE, used = 0, queued = 0)

    /** The sheet's search found S02E04's single, the season's pack and a larger single; the pack's NZB grabbed with the indexer's password. */
    private fun fakes() = SheetFakes().apply {
        space = room
        finder = inOrder(nzb(KESTREL, 1_200_000_000), nzb(PACK, 11_200_000_000, indexer = "idx-b"), nzb(ATMOS, 3_400_000_000))
        nzbs = { FetchedNzb(PACK_NZB, INDEXERS_PASSWORD) }
        opens = { bytes, title, password -> preparer.prepare(bytes, title, password) }
    }

    /** The sheet for S02E04, as an episode's ⋮ opens it ([replacing]: as the Downloads page's Choose release opens it), its search run. */
    private fun TestScope.open(fakes: SheetFakes, replacing: String? = null): DownloadSheetModel =
        DownloadSheetModel(EPISODE, fakes.deps(this), backgroundScope, replacing).also {
            it.start()
            runCurrent()
        }

    /** The pack's card in the sheet's list, and its Choose file chosen there, as its ⋮ does. */
    private fun TestScope.chooseFile(sheet: DownloadSheetModel): DownloadFileChoice {
        val pick = assertIs<DownloadSheetState.Pick>(sheet.state.value)
        val pack = pick.group.playable.single { it.item.title == PACK }
        assertEquals(ReleaseListMode.Download.FileChoiceOffer.Offered, pick.mode.fileChoice(pack))
        assertNotNull(pick.mode.onChooseFile).invoke(pack)
        runCurrent()
        return assertNotNull(sheet.choice.value)
    }

    private fun DownloadFileChoice.files(): DownloadFileChoice.State.Files = assertIs(state.value)

    private fun DownloadFileChoice.named(name: String): FileChoice = files().files.single { it.file.name == name }.file

    @Test
    fun `Choose file grabs the pack's NZB once and opens it once, step by step, and queues nothing`() = runTest {
        val grab = CompletableDeferred<Unit>()
        val opened = CompletableDeferred<Unit>()
        val fakes = fakes().apply {
            nzbs = {
                grab.await()
                FetchedNzb(PACK_NZB, INDEXERS_PASSWORD)
            }
            opens = { bytes, title, password ->
                opened.await()
                preparer.prepare(bytes, title, password)
            }
        }
        val choice = chooseFile(open(fakes))
        assertEquals(PACK, choice.release.item.title)
        assertEquals("Harbor Lights · S02E04 · Low Water", choice.subtitle)
        assertEquals(DownloadFileChoice.State.Opening(StepStatus.Running()), choice.state.value)

        grab.complete(Unit)
        runCurrent()
        val opening = assertIs<DownloadFileChoice.State.Opening>(choice.state.value)
        assertEquals(formatSize(PACK_NZB.size.toLong()), assertIs<StepStatus.Done>(opening.fetch).detail)
        assertEquals(StepStatus.Running(), opening.open)

        opened.complete(Unit)
        runCurrent()
        choice.files()
        assertEquals(listOf("idx-b/$PACK"), fakes.grabbed)
        // The pack's NZB, several MB of it, read for its own password off the sheet's main thread.
        assertTrue(fakes.readsAway > 0, "the NZB read where the deps say, not on the sheet's thread")
        // The indexer's password beside the NZB, which has none of its own, as a play gives it.
        assertEquals(INDEXERS_PASSWORD, fakes.prepared.single().second)
        assertContentEquals(PACK_NZB, fakes.prepared.single().first)
        assertEquals(listOf("grab", "prepare"), fakes.calls, "nothing queued, asked or scheduled while the pack is opened")
    }

    @Test
    fun `the pack's videos are listed largest first with their exact sizes, the episode's named, those too big greyed with what they need`() = runTest {
        val choice = chooseFile(open(fakes()))
        val files = choice.files()
        assertEquals("Looking for S02E04 'Low Water'", choice.lookingFor)
        assertEquals("Choose the file to download as S02E04:", choice.prompt)
        assertEquals(1_500_000_000L, files.free)
        assertEquals("1.5 GB free on this device. Files that won't fit are greyed.", files.room)
        assertEquals(
            listOf(
                "Harbor.Lights.S02E08.mkv", "Harbor.Lights.S02E06.mkv", "Harbor.Lights.S02E01.mkv", "Harbor.Lights.S02E03.mkv", "Harbor.Lights.S02E05.mkv",
                "Harbor.Lights.S02E04.mkv", "Harbor.Lights.S02E02.mkv", "Harbor.Lights.S02E07.mkv", "Harbor.Lights.S02.Sample.mkv",
            ),
            files.files.map { it.file.name },
        )
        // Exact sizes, read from the release: no "about".
        assertEquals(VIDEOS.toMap(), files.files.associate { it.file.name to it.file.sizeBytes })
        assertEquals(listOf("Harbor.Lights.S02E04.mkv"), files.files.filter { it.file.named == "S02E04" }.map { it.file.name })
        assertTrue(files.files.last().file.sample)
        assertEquals(mapOf("Harbor.Lights.S02E08.mkv" to "Won't fit: needs 1.6 GB"), files.files.filter { it.wontFit != null }.associate { it.file.name to it.wontFit })
        // With room for every one, none is greyed, and the line says only what is free.
        val roomy = chooseFile(open(fakes().apply { space = DownloadSpace(available = 15 * GB, used = 0, queued = 0) })).files()
        assertTrue(roomy.files.none { it.wontFit != null })
        assertEquals("14.0 GB free on this device.", roomy.room)
    }

    @Test
    fun `a file chosen goes back to the sheet as its Chosen file with its exact size, and one too big can't be chosen`() = runTest {
        val fakes = fakes()
        val sheet = open(fakes)
        val choice = chooseFile(sheet)
        choice.pick(choice.named("Harbor.Lights.S02E08.mkv").index)
        runCurrent()
        assertIs<DownloadFileChoice.State.Files>(choice.state.value)
        assertIs<DownloadSheetState.Pick>(sheet.state.value)

        choice.pick(choice.named("Harbor.Lights.S02E04.mkv").index)
        runCurrent()
        assertNull(sheet.choice.value, "the files' sheet gone")
        val chosen = assertIs<DownloadSheetState.Chosen>(sheet.state.value)
        assertEquals(PACK, chosen.item.item.title)
        assertEquals("Harbor.Lights.S02E04.mkv", chosen.file.name)
        assertEquals(1_310_000_000L, chosen.file.sizeBytes)
        assertEquals("1080p · WEB-DL · 1.3 GB", DownloadSheetModel.chosenQuality(chosen))
        assertEquals("Season pack · only this file is downloaded:", DownloadSheetModel.onlyThisFile(chosen.item))
        assertEquals("1.5 GB free on this device", DownloadSheetModel.free(chosen.free))
        assertEquals(DownloadStart.Now, chosen.start)
        assertEquals(listOf("grab", "prepare"), fakes.calls, "nothing queued before Download")
        // Back from Android's storage page, the chosen file stays chosen.
        sheet.shown()
        runCurrent()
        assertIs<DownloadSheetState.Chosen>(sheet.state.value)
    }

    @Test
    fun `Download queues the pack first, then the others that fit, with the pick, the pack's NZB as its kept grab and the file's size as its need`() = runTest {
        val fakes = fakes()
        val sheet = open(fakes)
        val choice = chooseFile(sheet)
        val file = choice.named("Harbor.Lights.S02E04.mkv")
        choice.pick(file.index)
        runCurrent()
        sheet.download()
        advanceUntilIdle()
        val (target, candidates) = fakes.enqueued.single()
        assertEquals(EPISODE.target, target)
        // The 3.4 GB single doesn't fit the 1.5 GB free: left out.
        assertEquals(listOf(PACK, KESTREL), candidates.map { it.title })
        assertEquals(listOf(1_310_000_000L), fakes.needs)
        val chosen = assertNotNull(fakes.chosen.single())
        assertEquals(DownloadPick(file.index, "Harbor.Lights.S02E04.mkv", 1_310_000_000L, PreparedNzb.idFor(PACK_NZB)), chosen.pick)
        assertEquals("idx-b/$PACK", chosen.grab.key)
        assertContentEquals(PACK_NZB, chosen.grab.source.nzb)
        assertEquals(INDEXERS_PASSWORD, chosen.grab.source.password)
        assertEquals(listOf("grab", "prepare", "enqueue", "ask", "schedule"), fakes.calls)
        assertTrue(sheet.closed.value)
        // The password is the release's: never in what the pick says of itself.
        assertFalse(INDEXERS_PASSWORD in chosen.toString())
    }

    @Test
    fun `a pack that can't be fetched or opened says why in the play's words, and the list marks it`() = runTest {
        fun case(words: String, setUp: SheetFakes.() -> Unit) = setUp to words
        val disc = listOf("Harbor.Lights.S02.iso" to 9 * GB)
        val cases = listOf(
            case("Couldn't get the NZB: Indexer no longer set up") { nzbs = { throw FetchException("Indexer no longer set up") } },
            case("Incomplete on your providers") { opens = { _, _, _ -> PrepareOutcome.MissingArticles("article gone") } },
            case("Incomplete on the providers in use") {
                leftOut = true
                opens = { _, _, _ -> PrepareOutcome.MissingArticles("article gone") }
            },
            // A volume of the archive missing, as nzb-streamer's prepare fails on it: the same words as a play's.
            case("Incomplete on your providers") { opens = { _, _, _ -> PrepareOutcome.Failed(UsenetError.MissingArticle("volume gone")) } },
            case("Incomplete on the providers in use") {
                leftOut = true
                opens = { _, _, _ -> PrepareOutcome.Failed(UsenetError.MissingArticle("volume gone")) }
            },
            case("Passworded") { opens = { _, _, _ -> PrepareOutcome.NeedsPassword(EncryptionInfo("a.rar", 0, 0, 15, null, true), passwordWasTried = true) } },
            case("Provider problem: login refused") { opens = { _, _, _ -> PrepareOutcome.Failed(UsenetError.AuthFailed("login refused")) } },
            case("No video file") {
                opens = { bytes, title, password -> NzbPreparer(backend(listOf("Harbor.Lights.S02.nfo" to 4_000L)), Dispatchers.Unconfined).prepare(bytes, title, password) }
            },
            case("A disc image (ISO): NZStreamer plays single video files") {
                nzbs = { FetchedNzb(nzbOf(disc), null) }
                opens = { bytes, title, password -> NzbPreparer(backend(disc), Dispatchers.Unconfined).prepare(bytes, title, password) }
            },
        )
        cases.forEach { (setUp, words) ->
            val fakes = fakes().apply(setUp)
            val sheet = open(fakes)
            val choice = chooseFile(sheet)
            assertEquals(DownloadFileChoice.State.Failed(words), choice.state.value, words)
            // Its card in the list says why, as a play's chosen release that failed does.
            assertEquals(mapOf("idx-b/$PACK" to words), sheet.failed.value)
            // Try another release, or Close: the files' sheet gone, the sheet's pick as it was, nothing queued.
            sheet.closeChoice()
            runCurrent()
            assertNull(sheet.choice.value)
            assertIs<DownloadSheetState.Pick>(sheet.state.value)
            assertEquals(mapOf("idx-b/$PACK" to words), sheet.failed.value)
            assertTrue("enqueue" !in fakes.calls)
        }
    }

    @Test
    fun `Cancel while the pack opens stops it, and Cancel on its files goes back to the list, nothing queued`() = runTest {
        var stopped = false
        val fakes = fakes().apply {
            nzbs = {
                try {
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            }
        }
        val sheet = open(fakes)
        chooseFile(sheet)
        sheet.closeChoice()
        runCurrent()
        assertTrue(stopped, "its grab stopped")
        assertNull(sheet.choice.value)
        assertEquals(emptyMap(), sheet.failed.value, "nothing known of the pack")

        val again = fakes()
        val listed = open(again)
        chooseFile(listed).files()
        listed.closeChoice()
        runCurrent()
        assertNull(listed.choice.value)
        assertIs<DownloadSheetState.Pick>(listed.state.value)
        assertEquals(listOf("grab", "prepare"), again.calls)
    }

    @Test
    fun `on mobile data with Wi-Fi only on Choose file is greyed, and opens nothing`() = runTest {
        val fakes = fakes().apply { network = MOBILE }
        val sheet = open(fakes)
        val pick = assertIs<DownloadSheetState.Pick>(sheet.state.value)
        val pack = pick.group.playable.single { it.item.title == PACK }
        assertEquals(ReleaseListMode.Download.FileChoiceOffer.NeedsWifi, pick.mode.fileChoice(pack))
        sheet.chooseFile(pack)
        runCurrent()
        assertNull(sheet.choice.value)
        assertEquals(emptyList(), fakes.calls)
    }

    /**
     * L403, W110: a file chosen on Wi-Fi, and the phone on mobile data since. Back in front, the Chosen file's sheet reads
     * the network again: it says it starts on Wi-Fi, its file still chosen, and its list greys Choose file "Needs Wi-Fi".
     * Choose file reads the network as it is tapped, so a list drawn before opens nothing over mobile data either.
     */
    @Test
    fun `a Chosen file's sheet back in front on mobile data says it starts on Wi-Fi, and Choose file opens nothing then`() = runTest {
        val fakes = fakes()
        val sheet = open(fakes)
        val choice = chooseFile(sheet)
        choice.pick(choice.named("Harbor.Lights.S02E04.mkv").index)
        runCurrent()
        val before = assertIs<DownloadSheetState.Chosen>(sheet.state.value)
        assertEquals(DownloadStart.Now, before.start)

        fakes.network = MOBILE
        sheet.shown()
        runCurrent()
        val again = assertIs<DownloadSheetState.Chosen>(sheet.state.value)
        assertEquals(before.file, again.file)
        assertEquals(DownloadStart.OnWifi, again.start)
        assertEquals(ReleaseListMode.Download.FileChoiceOffer.NeedsWifi, again.mode.fileChoice(before.item))

        // The list as it was drawn before: its Choose file opens nothing, nothing grabbed.
        fakes.calls.clear()
        assertNotNull(before.mode.onChooseFile).invoke(before.item)
        runCurrent()
        assertNull(sheet.choice.value)
        assertEquals(emptyList(), fakes.calls)
    }

    /**
     * DN1's L383, L403: the app's Choose file grabs the pack as Resolve grabs (the indexer's, through the coordinator's
     * fetch) and opens it outside the NZB store: its prepare is the container's preparer, an NzbPreparer over the engine,
     * which has no store to keep it in, and nothing of the sheet's deps touches the store. Read from the sources, as the
     * app's deps are the container's, which a JVM test can't build.
     */
    @Test
    fun `the app's Choose file grabs as Resolve does and opens the pack outside the NZB store`() {
        val deps = File("src/main/kotlin/io/skjaere/usenet/app/download/DownloadSheetModel.kt").readText()
            .substringAfter("fun of(container: AppContainer").substringBefore("\n        )\n")
        assertTrue("prepare = { nzb, title, password -> container.preparer.prepare(nzb, title, password) }" in deps, deps)
        assertTrue("indexerGrab(" in deps && "PlaybackCoordinator.fetchNzb(container, indexer, nzb)" in deps, deps)
        assertFalse("container.store" in deps, "the sheet's deps touch the NZB store")
        val app = File("src/main/kotlin/io/skjaere/usenet/app/UsenetApplication.kt").readText()
        assertTrue("val preparer = NzbPreparer(EnginePrepareBackend(engine))" in app, "the container's preparer is one with no store")
    }

    @Test
    fun `from the Downloads page a file chosen replaces that download's release at once`() = runTest {
        val fakes = fakes()
        val sheet = open(fakes, replacing = "dl-7")
        val choice = chooseFile(sheet)
        val file = choice.named("Harbor.Lights.S02E04.mkv")
        choice.pick(file.index)
        advanceUntilIdle()
        assertNull(sheet.choice.value)
        assertEquals(listOf(PACK, KESTREL), fakes.enqueued.single().second.map { it.title })
        assertEquals(listOf(1_310_000_000L), fakes.needs)
        assertEquals(DownloadPick(file.index, "Harbor.Lights.S02E04.mkv", 1_310_000_000L, PreparedNzb.idFor(PACK_NZB)), fakes.chosen.single()?.pick)
        assertTrue(sheet.closed.value, "the list closes, its download queued again")
        assertTrue(sheet.state.value !is DownloadSheetState.Chosen)
    }

    @Test
    fun `its log lines name the download it replaces by its id, never a release, a file, an indexer or a password`() = runTest {
        val from = LogBuffer.snapshot().lastOrNull()?.seq ?: 0L
        val sheet = open(fakes())
        chooseFile(sheet).let { it.pick(it.named("Harbor.Lights.S02E04.mkv").index) }
        runCurrent()
        val page = open(fakes().apply { opens = { _, _, _ -> PrepareOutcome.MissingArticles("article gone") } }, replacing = "dl-7")
        chooseFile(page)
        val replaced = open(fakes(), replacing = "dl-8")
        chooseFile(replaced).let { it.pick(it.named("Harbor.Lights.S02E04.mkv").index) }
        advanceUntilIdle()
        val lines = LogBuffer.snapshot().filter { it.seq > from && it.tag in setOf("DownloadFileChoice", "DownloadSheetModel") }.map { it.message }
        assertTrue(lines.any { "download dl-7" in it }, lines.toString())
        assertTrue(lines.any { "download dl-8" in it }, lines.toString())
        // The sheet's own, before any download is there, name none.
        assertTrue(lines.any { "download" !in it }, lines.toString())
        val secret = listOf(PACK, KESTREL, "idx-b", INDEXERS_PASSWORD, "Harbor Lights", "Low Water") + VIDEOS.map { it.first }
        assertEquals(emptyList(), lines.filter { line -> secret.any { it in line } })
    }

    private companion object {
        const val KESTREL = "Harbor.Lights.S02E04.Low.Water.1080p.WEB-DL.DDP5.1.H.265-KESTREL"
        const val PACK = "Harbor.Lights.S02.1080p.WEB-DL.DDP5.1.H.264-DRIFTWOOD"
        const val ATMOS = "Harbor.Lights.S02E04.Low.Water.1080p.WEB-DL.DDP5.1.Atmos.H.264-TIDEPOOL"
        const val INDEXERS_PASSWORD = "pw-from-indexer"

        val EPISODE = DownloadRequest.Episode(
            EpisodePlay(
                showTmdbId = 990_917, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(), ids = TvIds(tmdb = 990_917), season = 2,
                episode = 4, episodeName = "Low Water", airDate = LocalDate.of(2025, 3, 25), runtimeMinutes = 52, seasonEpisodes = 8,
            ),
        )

        /** The pack's videos as nzb-streamer lists them, in the NZB's order, and a sample. */
        val VIDEOS = listOf(
            "Harbor.Lights.S02E01.mkv" to 1_410_000_000L, "Harbor.Lights.S02E02.mkv" to 1_300_000_000L, "Harbor.Lights.S02E03.mkv" to 1_400_000_000L,
            "Harbor.Lights.S02E04.mkv" to 1_310_000_000L, "Harbor.Lights.S02E05.mkv" to 1_390_000_000L, "Harbor.Lights.S02E06.mkv" to 1_420_000_000L,
            "Harbor.Lights.S02E07.mkv" to 1_290_000_000L, "Harbor.Lights.S02E08.mkv" to 1_600_000_000L, "Harbor.Lights.S02.Sample.mkv" to 50_000_000L,
        )

        /** The pack's NZB: a volume of its own for each file, as it is posted. */
        val PACK_NZB: ByteArray = nzbOf(VIDEOS + ("Harbor.Lights.S02.nfo" to 4_000L))

        fun nzbOf(files: List<Pair<String, Long>>): ByteArray = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<nzb xmlns=\"http://www.newzbin.com/DTD/2003/nzb\">\n")
            files.forEach { (name, size) ->
                append("<file poster=\"fake\" date=\"0\" subject=\"&quot;$name&quot; yEnc (1/1)\"><groups><group>alt.binaries.fake</group></groups>")
                append("<segments><segment bytes=\"$size\" number=\"1\">hl-s02-pack/$name@fake</segment></segments></file>\n")
            }
            append("</nzb>\n")
        }.encodeToByteArray()

        /**
         * nzb-streamer's prepare as a release posted so lists it: each file of [files] in the NZB a volume and a file to
         * stream, at its size; the NZB's own other files left out.
         */
        fun backend(files: List<Pair<String, Long>>) = object : PrepareBackend {
            override suspend fun prepare(nzbBytes: ByteArray, password: String?): PrepareResult {
                val sizes = files.toMap()
                val volumes = NzbParser.parse(nzbBytes).files.mapNotNull { file ->
                    val name = checkNotNull(FilenameResolver.extractSubjectFilename(file.subject))
                    sizes[name]?.let { size -> file.also { it.yencHeaders = YencHeaders(line = 128, size = size, name = name) } }
                }
                val response = NzbMetadataResponse(volumes = volumes.map { it.yencHeaders!!.name }, obfuscated = false, entries = emptyList())
                return PrepareResult.Success(ExtractedMetadata.Raw(response = response, orderedArchiveNzb = NzbDocument(volumes, password)))
            }

            override suspend fun streamableFiles(metadata: ExtractedMetadata): List<StreamableFile> =
                metadata.orderedArchiveNzb.files.mapIndexed { i, volume -> StreamableFile(volume.yencHeaders!!.name, volume.yencHeaders!!.size, i, 0, 0, 0) }
        }

        /** The app's preparer over the fake, as the container's prepares: the pack's videos and sample. */
        val preparer = NzbPreparer(backend(VIDEOS), Dispatchers.Unconfined)
    }
}
