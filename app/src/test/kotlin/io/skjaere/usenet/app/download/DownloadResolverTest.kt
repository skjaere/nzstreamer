package io.skjaere.usenet.app.download

import io.skjaere.compressionutils.EncryptionInfo
import io.skjaere.nntp.NntpAuthenticationException
import io.skjaere.nntp.NntpConnectionException
import io.skjaere.nntp.YencHeaders
import io.skjaere.nzbstreamer.enrichment.VerificationResult
import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.NzbMetadataResponse
import io.skjaere.nzbstreamer.metadata.PrepareResult
import io.skjaere.nzbstreamer.nzb.FilenameResolver
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.nzb.NzbParser
import io.skjaere.nzbstreamer.stream.StreamableFile
import io.skjaere.usenet.app.play.MissingReleases
import io.skjaere.usenet.app.play.PlayingPack
import io.skjaere.usenet.app.play.VideoTiming
import io.skjaere.usenet.core.AvailabilityBackend
import io.skjaere.usenet.core.MissingOn
import io.skjaere.usenet.core.NzbPreparer
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.core.PrepareBackend
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.PreparedNzbStore
import io.skjaere.usenet.core.ProbeResult
import io.skjaere.usenet.core.ProbedTrack
import io.skjaere.usenet.core.ProbedTracks
import io.skjaere.usenet.core.ProbedVideo
import io.skjaere.usenet.core.ProviderConfig
import io.skjaere.usenet.core.TrackProbe
import io.skjaere.usenet.core.TriedReleases
import io.skjaere.usenet.core.UsenetError
import io.skjaere.usenet.core.UsenetTarget
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadDao
import io.skjaere.usenet.data.DownloadGap
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadPrefs
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.PlaybackPrefs
import io.skjaere.usenet.data.ReleaseChecks
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.logging.LogBuffer
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.NewznabError
import io.skjaere.usenet.newznab.NewznabException
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.NzbDownload
import io.skjaere.usenet.releases.ReleaseKey
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ConnectException
import java.util.Collections
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A download's Resolve (item 5, L338-L341, L383): a play's pipeline without a sheet, over fakes of the indexer, the
 * providers, prepare and the probe, as PlayPipelineTest's are, with the app's own pieces between them (the grab, the
 * Usenet source, the pipeline, the store). The episode is Harbor Lights S02E04; releases are invented.
 */
class DownloadResolverTest {
    @TempDir
    lateinit var nzbDir: File

    /** Where the season packs' copies go, as `downloads/.packs/` (L394). */
    @TempDir
    lateinit var packsDir: File

    /**
     * A release as the fake indexer and providers have it: its [title], its videos ([files], names to sizes) each in a
     * volume of its own, its articles [gone] from the providers, a [password] its indexer sends beside its NZB, a
     * provider that refuses the login or can't be reached as it is opened ([opening]), and an archive whose headers are
     * [encrypted], which nothing opens without its password.
     */
    private class Fake(
        val guid: String,
        val title: String,
        val files: List<Pair<String, Long>>,
        val gone: Boolean = false,
        val password: String? = null,
        var opening: Exception? = null,
        val encrypted: Boolean = false,
    ) {
        val size: Long get() = files.sumOf { it.second }

        val nzb: ByteArray = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!-- $guid -->\n<nzb xmlns=\"http://www.newzbin.com/DTD/2003/nzb\">\n")
            files.forEach { (name, size) ->
                append("<file poster=\"fake\" date=\"0\" subject=\"&quot;$name&quot; yEnc (1/1)\"><groups><group>alt.binaries.fake</group></groups>")
                append("<segments><segment bytes=\"$size\" number=\"1\">${if (gone) GONE else ""}$guid/$name@fake</segment></segments></file>\n")
            }
            append("</nzb>\n")
        }.encodeToByteArray()
    }

    private val single = Fake("hl-s02e04-kestrel", "Harbor.Lights.S02E04.1080p.WEB.h264-KESTREL", listOf("a8f3c1.mkv" to 1_200_000_000L))
    private val broken = Fake("hl-s02e04-grp", "Harbor.Lights.S02E04.2160p.WEB.h265-GRP", listOf("b71d0e.mkv" to 4_000_000_000L), gone = true)
    private val pack = Fake(
        "hl-s02-pack", "Harbor.Lights.S02.1080p.WEB.h264-GRP",
        (1..6).map { "Harbor.Lights.S02E%02d.1080p.WEB.h264-GRP.mkv".format(it) to 1_000_000_000L + it },
    )

    /** A season pack whose videos are named for no episode the parser reads: which is S02E04 is a question for the user. */
    private val unnamedPack = Fake(
        "hl-s02-unnamed", "Harbor.Lights.S02.720p.WEB.h264-GRP",
        (1..6).map { (if (it == 2) "Harbor.Lights.2-6.mp4" else "Harbor.Lights.${it}_6.mp4") to 900_000_000L + it },
    )

    private val passworded = Fake("hl-s02e04-pw", "Harbor.Lights.S02E04.1080p.WEB.h264-QUILL", listOf("c90a4f.mkv" to 1_300_000_000L), password = "s3cret-from-indexer")

    private val fakes = mutableMapOf<String, Fake>()

    private fun fakeOf(nzb: ByteArray) = fakes.getValue(nzb.decodeToString().substringAfter("<!-- ").substringBefore(" -->"))

    /** The indexer's grabs, by guid, in order. */
    private val grabbed: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val indexer = IndexerConfig(INDEXER, "Fake indexer", "http://indexer.invalid/api", "key")

    /** Called as each grab is made, before it is answered. */
    private var onGrab: suspend (NewznabItem) -> Unit = {}

    /** The indexer's answer to a grab instead of the NZB, while set: a 503, a dropped connection. */
    private var indexerFails: NewznabError? = null

    private suspend fun grab(item: NewznabItem): NzbDownload {
        onGrab(item)
        indexerFails?.let { throw NewznabException(it) }
        grabbed += item.guid
        val fake = fakes.getValue(item.guid)
        return NzbDownload(fake.nzb, fake.password)
    }

    /** Every article the providers were asked about: the check STATs all it is given (L341). */
    private val checked: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Called as each check starts. */
    private var onCheck: suspend () -> Unit = {}

    /** What fails each check before an answer, while set: the connection lost. */
    private var checkFails: Throwable? = null

    private val availability = AvailabilityBackend { nzb, progress ->
        onCheck()
        checkFails?.let { return@AvailabilityBackend VerificationResult.Failure("the check couldn't finish", it) }
        val articles = nzb.files.flatMap { file -> file.segments.map { it.articleId } }
        checked += articles
        articles.indices.forEach { progress(it + 1, articles.size) }
        val gone = articles.firstOrNull { it.startsWith(GONE) }
        if (gone != null) VerificationResult.MissingArticles("Missing article $gone", io.skjaere.nntp.ArticleNotFoundException(gone)) else VerificationResult.Success
    }

    /** What prepare opened, by guid, with the password it was given besides the NZB's own. */
    private val opened: MutableList<Pair<String, String?>> = Collections.synchronizedList(mutableListOf())

    /** A fake's NZB as nzb-streamer prepares a release posted as it is: each video a volume and a file to stream. */
    private val prepareBackend = object : PrepareBackend {
        override suspend fun prepare(nzbBytes: ByteArray, password: String?): PrepareResult {
            val fake = fakeOf(nzbBytes)
            opened += fake.guid to password
            fake.opening?.let { return PrepareResult.Failure(it.message.orEmpty(), it) }
            if (fake.encrypted) return PrepareResult.Encrypted("encrypted", EncryptionInfo("${fake.guid}.rar", 0, 0, 15, null, true))
            val parsed = NzbParser.parse(nzbBytes)
            val volumes = parsed.files.onEach { file ->
                val name = checkNotNull(FilenameResolver.extractSubjectFilename(file.subject))
                file.yencHeaders = YencHeaders(line = 128, size = fake.files.toMap().getValue(name), name = name)
            }
            val response = NzbMetadataResponse(volumes = volumes.map { it.yencHeaders!!.name }, obfuscated = false, entries = emptyList())
            return PrepareResult.Success(ExtractedMetadata.Raw(response = response, orderedArchiveNzb = NzbDocument(volumes, password ?: parsed.password)))
        }

        override suspend fun streamableFiles(metadata: ExtractedMetadata): List<StreamableFile> =
            metadata.orderedArchiveNzb.files.mapIndexed { i, volume -> StreamableFile(volume.yencHeaders!!.name, volume.yencHeaders!!.size, i, 0, 0, 0) }
    }

    private val video = ProbedVideo(fps = 24_000.0 / 1001, durationMs = 2_640_000, dolbyVision = false)
    private val tracks = ProbedTracks(
        video = listOf(ProbedTrack("video/avc", "H.264", width = 1920, height = 1080)),
        audio = listOf(ProbedTrack("audio/eac3", "E-AC-3", "en", default = true, channels = 6)),
    )

    /** What the probe read, by candidate; it keeps the target first, as the app's probe does to read it by its address. */
    private val probed: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Called once the probe has kept its target, before it reads it. */
    private var onProbe: suspend () -> Unit = {}

    /** The probe's answer for a candidate (by key) in place of what it read, where it gives one: its own deadline, say. */
    private var probeAnswer: (String) -> ProbeResult? = { null }

    private val probe = TrackProbe { target ->
        target.keep()
        onProbe()
        probed += target.candidate.id
        probeAnswer(target.candidate.id) ?: ProbeResult.Playable(video, tracks)
    }

    private val dao = FakeDao()
    private val storage by lazy { FakeStorage(packs()) }
    private val store by lazy { PreparedNzbStore(nzbDir) }

    /**
     * Resolve over these fakes, what is known of releases tried [tried], or, where [remembered] is given, the app's memory of
     * them as the app's Resolve reads it. Download size is [cap] GB an hour, none when null; a prepare held for a season's
     * copy is handed back as the app's Resolve hands it back ([PlayingPack.preparer]), over the same fake prepare.
     */
    private fun resolver(attempts: Int = 10, tried: TriedReleases = TriedReleases.None, providers: Boolean? = true, remembered: MissingReleases? = null, cap: Int? = null) = DownloadResolver(
        downloads = dao,
        storage = storage,
        availability = availability,
        preparer = NzbPreparer(prepareBackend, Dispatchers.Unconfined),
        store = store,
        probe = probe,
        indexers = { listOf(indexer) },
        fetchNzb = { _, item -> grab(item) },
        tried = remembered?.let(DownloadResolver::remembered) ?: { _, _, _ -> tried },
        attempts = { attempts },
        providersChecked = { providers },
        clock = { NOW },
        heldPreparer = { held -> PlayingPack.preparer(held, prepareBackend, Dispatchers.Unconfined) },
        downloadContext = { DownloadRanking.context(StaticDeviceCapabilities(displayHeight = 1080), PlaybackPrefs(), DownloadPrefs(sizeCapGbPerHour = cap), "en") },
    )

    /** The season packs' copies in [packsDir], as a process finds them: none of their prepares held. */
    private fun packs() = DownloadNzbs(File(packsDir, DownloadNzbs.PATH), Dispatchers.Unconfined)

    private fun key(fake: Fake) = ReleaseKey.of(INDEXER, fake.guid)

    /** Harbor Lights S02E04's download, of [candidates] in download order, [tried] those tried by earlier resolves. */
    private suspend fun row(vararg candidates: Fake, tried: List<Fake> = emptyList(), edit: (Download) -> Download = { it }): Download {
        candidates.forEach { fakes[it.guid] = it }
        val row = Download(
            id = ID, watchKey = WatchKey.episode(SHOW, 2, 4), kind = DownloadKind.Episode, title = "Harbor Lights", showTmdbId = SHOW,
            season = 2, episode = 4, seasonEpisodes = 6, episodeName = "Low Water", position = 0,
            candidates = candidates.map { DownloadCandidate(INDEXER, it.guid, it.title, "http://indexer.invalid/api?t=get&id=${it.guid}&apikey={apikey}", it.size) },
            tried = tried.map(::key), indexerSize = candidates.first().size, createdAt = 0, updatedAt = 0,
        ).let(edit)
        dao.insert(row)
        return row
    }

    private suspend fun stored(): Download = assertNotNull(dao.get(ID))

    private suspend fun stored(id: String): Download = assertNotNull(dao.get(id))

    /** Harbor Lights S02E0[episode]'s download, [id], of [candidates] in download order, a row of the same season as [ID]'s. */
    private suspend fun episode(id: String, episode: Int, vararg candidates: Fake, edit: (Download) -> Download = { it }): Download =
        row(*candidates) { it.copy(id = id, watchKey = WatchKey.episode(SHOW, 2, episode), episode = episode, episodeName = null).let(edit) }

    @Test
    fun `a release missing articles in the full check falls to the next, which is grabbed checked opened and probed`() = runBlocking<Unit> {
        row(broken, single)
        val ready = assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(ID))
        assertEquals(listOf(broken.guid, single.guid), grabbed)
        // Every article of the release was checked before it was opened: the broken one's, then the next one's.
        assertTrue(checked.any { it.startsWith(GONE) })
        assertTrue(checked.contains("${single.guid}/a8f3c1.mkv@fake"))
        assertEquals(listOf(single.guid), opened.map { it.first })
        assertEquals(listOf(key(single)), probed)
        assertEquals("a8f3c1.mkv", ready.file.name)
        assertEquals(1_200_000_000L, ready.file.size)
        val kept = stored()
        assertEquals(DownloadState.Resolving, kept.state)
        assertEquals(single.guid, kept.release?.guid)
        assertEquals(single.title, kept.release?.title)
        assertEquals("a8f3c1.mkv", kept.fileName)
        assertEquals(1_200_000_000L, kept.size)
        assertEquals(listOf(key(broken), key(single)), kept.tried)
    }

    /**
     * A download's lines in the app's log (logcat, and Settings › Logs, which a user may share) name it by its id and its
     * release by its place among the row's, never by the release's name, its indexer's or a provider's host: Resolve runs a
     * play's pipeline and the app's memory of releases tried, whose own lines name a play's release by its title and its
     * indexer, and the providers it is missing on by their names, else their hosts. Over that memory as the app's Resolve
     * reads it, on a fake table, with one provider in use that has no name: a release missing on it, one that needs a
     * password, and a copy of a disc, each remembered all the same. Releases and a provider of their own here, so no other
     * test's lines can name them.
     */
    @Test
    fun `a download's log lines name its releases by their place, never by their names, indexers or providers`() = runBlocking<Unit> {
        val missing = Fake("hl-s02e04-plover", "Harbor.Lights.S02E04.2160p.WEB.h265-PLOVER", listOf("e81f07.mkv" to 4_000_000_000L), gone = true)
        val locked = Fake("hl-s02e04-tern", "Harbor.Lights.S02E04.1080p.WEB.h264-TERN", listOf("f92a18.rar" to 1_200_000_000L), encrypted = true)
        val disc = Fake("hl-s02e04-heron", "Harbor.Lights.S02E04.1080p.BluRay.ISO-HERON", listOf("a03b29.iso" to 8_000_000_000L))
        row(missing, locked, disc)
        val provider = ProviderConfig("p-meridian", "", "reader.quiet-meridian.invalid", 563, "", "", useTls = true)
        val table = TriedTable(NOW)
        val remembered = MissingReleases(table, inUse = { listOf(provider) }, checkedInUse = { listOf(provider) }, now = { NOW })
        val from = LogBuffer.snapshot().lastOrNull()?.seq ?: 0L
        assertIs<DownloadResolver.Resolution.Failed>(resolver(remembered = remembered).resolve(ID))
        val lines = LogBuffer.snapshot().filter { it.seq > from }.map { it.message }
        val secret = listOf(missing.title, locked.title, disc.title, indexer.name, provider.host)
        assertEquals(emptyList(), lines.filter { line -> secret.any { it in line } })
        // Each remembered all the same: missing on the provider, needing a password, a disc copy.
        val kept = table.entries
        assertEquals(listOf(ReleaseChecks.provider(provider.id, provider.host, provider.port)), kept.getValue(key(missing)).missingOn.map { it.provider })
        assertNotNull(kept.getValue(key(locked)).passwordAt)
        assertNotNull(kept.getValue(key(disc)).discAt)
        // Their lines are there, each release by its place, as are the pipeline's.
        listOf(
            "download $ID, release 1 missing on 1 provider",
            "download $ID, release 2 needs a password it didn't come with",
            "download $ID, release 3 is a disc copy",
        ).forEach { assertTrue(it in lines, lines.toString()) }
        (1..3).forEach { place -> assertTrue(lines.any { "play_pipeline: download $ID, release $place " in it }, lines.toString()) }
    }

    @Test
    fun `a pack that needs the file question fails that attempt and the next is tried`() = runBlocking<Unit> {
        row(unnamedPack, single)
        assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(ID))
        // Opened, then nobody asked which file is the episode: no probe of it, and the next release plays its part.
        assertEquals(listOf(unnamedPack.guid, single.guid), opened.map { it.first })
        assertEquals(listOf(key(single)), probed)
        assertEquals(single.guid, stored().release?.guid)
    }

    @Test
    fun `whether the release is its file's one video is kept as the prepared NZB says`() = runBlocking<Unit> {
        row(single)
        resolver().resolve(ID)
        assertTrue(stored().oneVideo)

        reset()
        row(pack)
        val ready = assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(ID))
        // The pack's episode, by its name, and not the release's one video: its others may be whole.
        assertEquals("Harbor.Lights.S02E04.1080p.WEB.h264-GRP.mkv", ready.file.name)
        assertFalse(stored().oneVideo)
        assertEquals(1_000_000_004L, stored().size)
        // Only the picked episode's volume was checked, after it was opened.
        assertEquals(listOf("${pack.guid}/Harbor.Lights.S02E04.1080p.WEB.h264-GRP.mkv@fake"), checked)
    }

    @Test
    fun `at most 3 releases are tried, or Releases to try when lower`() = runBlocking<Unit> {
        val five = (1..5).map { Fake("hl-broken-$it", "Harbor.Lights.S02E04.720p.WEB.h264-GRP$it", listOf("d$it.mkv" to 800_000_000L), gone = true) }
        row(*five.toTypedArray())
        val failed = assertIs<DownloadResolver.Resolution.Failed>(resolver(attempts = 10).resolve(ID))
        assertEquals(five.take(3).map { it.guid }, grabbed)
        assertEquals("Release incomplete", failed.reason)
        assertEquals(DownloadState.Failed, stored().state)
        assertEquals("Release incomplete", stored().reason)
        assertEquals(five.take(3).map(::key), stored().tried)

        reset()
        row(*five.toTypedArray())
        assertIs<DownloadResolver.Resolution.Failed>(resolver(attempts = 2).resolve(ID))
        assertEquals(five.take(2).map { it.guid }, grabbed)
    }

    @Test
    fun `the releases tried are counted across one download's resolves`() = runBlocking<Unit> {
        val five = (1..5).map { Fake("hl-broken-$it", "Harbor.Lights.S02E04.720p.WEB.h264-GRP$it", listOf("d$it.mkv" to 800_000_000L), gone = true) }
        // The first was resolved before and gave up while it was written (a long gap): two more, from the second on.
        row(*five.toTypedArray(), tried = five.take(1))
        assertIs<DownloadResolver.Resolution.Failed>(resolver().resolve(ID))
        assertEquals(listOf(five[1].guid, five[2].guid), grabbed)
        assertEquals(five.take(3).map(::key), stored().tried)
        assertTrue(dao.reasons.contains("Trying release 2 of 3 · Fetching NZB"), dao.reasons.toString())
        assertTrue(dao.reasons.contains("Trying release 3 of 3 · Checking availability"), dao.reasons.toString())

        // Three tried: nothing more is grabbed.
        grabbed.clear()
        val none = assertIs<DownloadResolver.Resolution.Failed>(resolver().resolve(ID))
        assertEquals(emptyList(), grabbed)
        assertEquals(DownloadResolver.NO_OTHER_RELEASE, none.reason)
    }

    @Test
    fun `a release is counted once its attempt starts, before its grab, so a process that dies mid-attempt never grabs it again`() = runBlocking<Unit> {
        row(single, pack)
        val counted = mutableListOf<List<String>>()
        onGrab = { counted += stored().tried }
        resolver().resolve(ID)
        assertEquals(listOf(listOf(key(single))), counted)
    }

    @Test
    fun `a resolve cut short mid-attempt tries that release again from the NZB kept for it, uncounted and with no second grab`() = runBlocking<Unit> {
        row(single, pack)
        val checking = CompletableDeferred<Unit>()
        onCheck = {
            checking.complete(Unit)
            awaitCancellation()
        }
        val resolve = launch(Dispatchers.Default) { resolver().resolve(ID) }
        withTimeout(10_000) { checking.await() }
        // Paused, or the host stopped, while its articles are checked.
        resolve.cancelAndJoin()
        assertEquals(emptyList(), stored().tried)
        assertEquals(listOf(single.guid), grabbed)

        onCheck = {}
        val ready = assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(ID))
        assertEquals(single.guid, ready.row.release?.guid)
        assertEquals(listOf(single.guid), grabbed)
        assertEquals(listOf(key(single)), stored().tried)
        // Taken into the download's own copy, and not kept a second time.
        assertContentEquals(single.nzb, storage.sources[ID]?.nzb)
        assertNull(storage.grabs[ID])
        assertTrue(dao.reasons.none { it.orEmpty().startsWith("Trying release 2") }, dao.reasons.toString())
    }

    @Test
    fun `a process that died mid-attempt tries that release again from the NZB kept for it, counted once`() = runBlocking<Unit> {
        // As a death mid-check leaves it: counted, its NZB kept.
        row(single, pack, tried = listOf(single))
        storage.grabs[ID] = DownloadStorage.Grab(key(single), DownloadStorage.Source(single.nzb, null))
        val ready = assertIs<DownloadResolver.Resolution.Ready>(resolver(attempts = 1).resolve(ID))
        assertEquals(single.guid, ready.row.release?.guid)
        assertEquals(emptyList(), grabbed)
        assertEquals(listOf(key(single)), stored().tried)
    }

    @Test
    fun `the store's entry the probe put is dropped and the NZB and its password are copied to the download's own folder`() = runBlocking<Unit> {
        row(passworded)
        val ready = assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(ID))
        // The probe read it through the store; once resolved, downloads take no place there (L383).
        assertNull(store.get(ready.nzb.id))
        assertNull(store.stored(ready.nzb.id))
        assertEquals(0, store.storedFiles().count)
        val copy = assertNotNull(storage.sources[ID])
        assertContentEquals(passworded.nzb, copy.nzb)
        assertEquals("s3cret-from-indexer", copy.password)
        // The password reaches no log line through the copy's own words.
        assertFalse(copy.toString().contains("s3cret"))
    }

    /** A resolve cut short as the probe reads the file takes out of the store what the probe put there (L383), and only that. */
    @Test
    fun `a resolve cancelled as its file is probed leaves nothing in the store but what a play kept there`() = runBlocking<Unit> {
        row(single)
        val probing = CompletableDeferred<Unit>()
        onProbe = {
            probing.complete(Unit)
            awaitCancellation()
        }
        val resolve = launch(Dispatchers.Default) { resolver().resolve(ID) }
        withTimeout(10_000) { probing.await() }
        assertEquals(1, store.storedFiles().count)
        resolve.cancelAndJoin()
        assertEquals(0, store.storedFiles().count)

        // One a play kept before stays.
        reset()
        row(single)
        val kept = (NzbPreparer(prepareBackend, Dispatchers.Unconfined).prepare(single.nzb, single.title) as io.skjaere.usenet.core.PrepareOutcome.Ready).nzb
        store.put(kept, single.nzb)
        val again = CompletableDeferred<Unit>()
        onProbe = {
            again.complete(Unit)
            awaitCancellation()
        }
        val second = launch(Dispatchers.Default) { resolver().resolve(ID) }
        withTimeout(10_000) { again.await() }
        second.cancelAndJoin()
        assertNotNull(store.stored(kept.id))
    }

    @Test
    fun `an NZB a play kept in the store before stays there`() = runBlocking<Unit> {
        row(single)
        fakes[single.guid] = single
        val kept = (NzbPreparer(prepareBackend, Dispatchers.Unconfined).prepare(single.nzb, single.title) as io.skjaere.usenet.core.PrepareOutcome.Ready).nzb
        store.put(kept, single.nzb)
        resolver().resolve(ID)
        assertNotNull(store.get(kept.id))
        assertNotNull(store.stored(kept.id))
    }

    /**
     * Backlog 353: a play of the same release keeps its NZB (its probe's keep and the coordinator's) while Resolve's probe
     * reads it. Resolve's let-go takes off its own hold alone: the play's entry stays, for the player's data source and a
     * Resume. Once the play lets go too, as a failed attempt does, nothing holds it, and it goes, as a hold added it.
     */
    @Test
    fun `Resolve's let-go leaves an entry a play holds`() = runBlocking<Unit> {
        row(single)
        val id = PreparedNzb.idFor(single.nzb)
        var play: UsenetTarget? = null
        onProbe = {
            val prepared = assertNotNull(store.get(id))
            play = UsenetTarget(PlayCandidate(key(single), single.title, INDEXER), single.nzb, prepared, prepared.candidates.single(), store).apply {
                keep()
                keep()
            }
        }
        assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(ID))
        assertNotNull(store.get(id), "the play's entry is gone from memory")
        assertNotNull(store.stored(id), "the play's entry is gone from disk")

        assertNotNull(play).drop()
        assertNull(store.get(id))
        assertNull(store.stored(id))
    }

    /**
     * L394, C1: a season's rows whose candidates rank its pack first grab it once in all. The first keeps it for the season
     * as it is resolved, the others of the season still to finish; the next take it from that copy, with no grab, and from
     * the prepare held for it, with no second prepare in this process. Each gets its own episode's file, and its own copy of
     * the NZB for a resume, and counts it as one of its tries.
     */
    @Test
    fun `a season's rows whose pack ranks first grab it once and prepare it once`() = runBlocking<Unit> {
        val episodes = listOf(E1 to 1, E2 to 2, E3 to 3)
        episodes.forEach { (id, e) -> episode(id, e, pack, single) }
        val resolver = resolver()
        episodes.forEach { (id, e) ->
            val ready = assertIs<DownloadResolver.Resolution.Ready>(resolver.resolve(id))
            assertEquals("Harbor.Lights.S02E%02d.1080p.WEB.h264-GRP.mkv".format(e), ready.file.name)
            assertEquals(pack.guid, ready.row.release?.guid)
            assertContentEquals(pack.nzb, storage.sources[id]?.nzb)
            assertEquals(listOf(key(pack)), dao.get(id)?.tried)
            // Written, as the runner leaves it.
            dao.setState(id, DownloadState.Done, null, NOW)
        }
        assertEquals(listOf(pack.guid), grabbed)
        assertEquals(listOf(pack.guid), opened.map { it.first })
        // Only each episode's own volume checked, from the copy as from the grab.
        assertEquals(episodes.map { (_, e) -> "${pack.guid}/Harbor.Lights.S02E%02d.1080p.WEB.h264-GRP.mkv@fake".format(e) }, checked)
    }

    /** A process that dies between two rows of a season loses the prepare it held, never the copy: no second grab. */
    @Test
    fun `after a process death between a season's rows the next takes the pack from its copy, prepared again`() = runBlocking<Unit> {
        episode(E1, 1, pack, single)
        episode(E2, 2, pack, single)
        assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(E1))
        dao.setState(E1, DownloadState.Done, null, NOW)

        // A new process: the copies on disk, no prepare held.
        storage.packs = packs()
        val ready = assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(E2))
        assertEquals("Harbor.Lights.S02E02.1080p.WEB.h264-GRP.mkv", ready.file.name)
        assertEquals(listOf(pack.guid), grabbed)
        assertEquals(listOf(pack.guid, pack.guid), opened.map { it.first })
    }

    /** A row whose own release ranks first isn't served the season's pack: it grabs its own, and the copy stays for the rest. */
    @Test
    fun `a row whose single ranks first grabs the single and leaves the season's copy`() = runBlocking<Unit> {
        episode(E1, 1, pack)
        episode(E2, 2, pack)
        episode(E4, 4, single, pack)
        assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(E1))
        dao.setState(E1, DownloadState.Done, null, NOW)

        val own = assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(E4))
        assertEquals(single.guid, own.row.release?.guid)
        assertEquals(listOf(pack.guid, single.guid), grabbed)
        assertNotNull(storage.packs.find(key(pack)))
        dao.setState(E4, DownloadState.Done, null, NOW)

        assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(E2))
        assertEquals(listOf(pack.guid, single.guid), grabbed)
    }

    /**
     * L432: a copy is kept only where it has a use, a release of several episodes and another row of its show and season still
     * to finish, and it leads unless, ranked alone for downloading with the episode's runtime, it is above Download size.
     */
    @Test
    fun `a copy is kept only while another row of the season is still to finish`() = runBlocking<Unit> {
        // Alone in its season.
        episode(E1, 1, pack)
        assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(E1))
        assertNull(storage.packs.find(key(pack)))

        // The season's others done, failed or removed; another season's, another show's and a film's still to finish.
        reset()
        episode(E1, 1, pack)
        episode(E2, 2, pack) { it.copy(state = DownloadState.Done) }
        episode(E3, 3, pack) { it.copy(state = DownloadState.Failed) }
        episode(E4, 4, pack) { it.copy(state = DownloadState.Deleting) }
        episode(OTHER_SEASON, 1, pack) { it.copy(season = 3, watchKey = WatchKey.episode(SHOW, 3, 1)) }
        episode(OTHER_SHOW, 1, pack) { it.copy(showTmdbId = 900003, watchKey = WatchKey.episode(900003, 2, 1)) }
        assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(E1))
        assertNull(storage.packs.find(key(pack)))

        // An episode's own release, with the season's others to come: no copy.
        reset()
        episode(E4, 4, single)
        episode(E2, 2, pack)
        assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(E4))
        assertNull(storage.packs.find(key(single)))

        // One of the season paused: kept for it, leading, with the indexer's password.
        reset()
        val locked = Fake(
            "hl-s02-pw-pack", "Harbor.Lights.S02.1080p.WEB.h264-QUILL",
            (1..6).map { "Harbor.Lights.S02E%02d.1080p.WEB.h264-QUILL.mkv".format(it) to 1_000_000_000L + it }, password = "s3cret-from-indexer",
        )
        episode(E1, 1, locked) { it.copy(runtimeMinutes = 44) }
        episode(E2, 2, locked) { it.copy(state = DownloadState.Paused) }
        assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(E1))
        val copy = assertNotNull(storage.packs.find(key(locked)))
        assertEquals(DownloadNzbs.Meta(key(locked), locked.title, stored(E1).candidates.single(), SHOW, 2, leads = true), copy.meta)
        assertContentEquals(locked.nzb, copy.source.nzb)
        assertEquals("s3cret-from-indexer", copy.source.password)

        // Above Download size for the episode (its share of 6 GB over 44 minutes is about 1.4 GB an hour): kept, not leading.
        reset()
        episode(E1, 1, locked) { it.copy(runtimeMinutes = 44) }
        episode(E2, 2, locked)
        assertIs<DownloadResolver.Resolution.Ready>(resolver(cap = 1).resolve(E1))
        assertEquals(false, storage.packs.find(key(locked))?.meta?.leads)
        reset()
        episode(E1, 1, locked) { it.copy(runtimeMinutes = 44) }
        episode(E2, 2, locked)
        assertIs<DownloadResolver.Resolution.Ready>(resolver(cap = 2).resolve(E1))
        assertEquals(true, storage.packs.find(key(locked))?.meta?.leads)
    }

    /** Resolve's lines name a release taken from its season's copy by its place, never by its name or the copy's key. */
    @Test
    fun `a release taken from the season's copy is named by its place in the log`() = runBlocking<Unit> {
        episode(E1, 1, pack)
        episode(E2, 2, single, pack)
        assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(E1))
        dao.setState(E1, DownloadState.Done, null, NOW)
        // The single is missing on the providers: the pack, its second, comes from the copy.
        val gone = Fake(single.guid, single.title, single.files, gone = true)
        fakes[single.guid] = gone
        val from = LogBuffer.snapshot().lastOrNull()?.seq ?: 0L
        assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(E2))
        val lines = LogBuffer.snapshot().filter { it.seq > from }.map { it.message }
        assertTrue(lines.any { "download $E2, release 2" in it && "copy" in it }, lines.toString())
        assertEquals(emptyList(), lines.filter { line -> listOf(pack.title, pack.guid, key(pack)).any { it in line } })
        assertEquals(listOf(pack.guid, single.guid), grabbed)
    }

    @Test
    fun `an exact size over the room on the device fails as Won't fit, with nothing copied`() = runBlocking<Unit> {
        row(single)
        storage.space = DownloadSpace(available = 2_000_000_000L + DownloadSpace.KEPT_FREE, used = 0, queued = 900_000_000L)
        val failed = assertIs<DownloadResolver.Resolution.Failed>(resolver().resolve(ID))
        assertEquals(DownloadResolver.WONT_FIT, failed.reason)
        assertEquals("Won't fit", stored().reason)
        assertEquals(DownloadState.Failed, stored().state)
        // The size it needs is the row's, for the page to say.
        assertEquals(1_200_000_000L, stored().size)
        assertNull(storage.sources[ID])
        assertEquals(listOf(ID), storage.spaceAsked)
        // Its store entry went all the same.
        assertEquals(0, store.storedFiles().count)

        // Room for it: it fits.
        reset()
        row(single)
        storage.space = DownloadSpace(available = 1_200_000_000L + DownloadSpace.KEPT_FREE, used = 0, queued = 0)
        assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(ID))
    }

    @Test
    fun `with the volume gone the download waits for storage, and its release is taken up again once it is back`() = runBlocking<Unit> {
        row(single)
        storage.space = null
        val waiting = assertIs<DownloadResolver.Resolution.Waiting>(resolver().resolve(ID))
        assertEquals(DownloadHostRules.STORAGE_UNAVAILABLE, waiting.reason)
        assertEquals(DownloadState.Waiting, stored().state)

        // Back: the release that passed every step, from the NZB kept for it, with no second grab.
        storage.space = ROOM
        val ready = assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(ID))
        assertEquals(single.guid, ready.row.release?.guid)
        assertEquals(listOf(single.guid), grabbed)
        assertEquals(listOf(key(single)), stored().tried)
    }

    @Test
    fun `the row's words at each event`() = runBlocking<Unit> {
        row(broken, single)
        resolver().resolve(ID)
        assertEquals<List<String?>>(
            listOf(
                "Finding releases…",
                "Fetching NZB",
                "Checking availability",
                "Trying release 2 of 2 · Fetching NZB",
                "Trying release 2 of 2 · Checking availability",
                "Trying release 2 of 2 · Opening",
                "Trying release 2 of 2 · Checking audio and video",
            ),
            dao.reasons,
        )
        assertTrue(dao.states.all { it == DownloadState.Resolving })
    }

    @Test
    fun `what the probe read is kept as the launch keeps it`() = runBlocking<Unit> {
        row(single)
        resolver().resolve(ID)
        val kept = stored()
        assertEquals(VideoTiming.of(video), DownloadResolver.video(kept))
        assertEquals(tracks, DownloadResolver.tracks(kept))
        assertEquals(single.title, kept.release?.title)
        assertEquals("http://indexer.invalid/api?t=get&id=${single.guid}&apikey={apikey}", kept.release?.downloadTemplate)
    }

    @Test
    fun `a release remembered as missing is passed over without a grab, its words the play's`() = runBlocking<Unit> {
        row(broken, single)
        val known = object : TriedReleases by TriedReleases.None {
            override suspend fun knownMissing(candidate: PlayCandidate) = if (candidate.id == key(broken)) "Skipped: missing on your providers (checked today)" else null
        }
        assertIs<DownloadResolver.Resolution.Ready>(resolver(tried = known).resolve(ID))
        assertEquals(listOf(single.guid), grabbed)

        // Every release passed over so: it fails in the play sheet's words for them.
        reset()
        row(broken)
        val failed = assertIs<DownloadResolver.Resolution.Failed>(resolver(tried = known).resolve(ID))
        assertEquals(emptyList(), grabbed)
        assertEquals("Skipped 1 release found incomplete before", failed.reason)
    }

    @Test
    fun `a release found missing is remembered as a play remembers it`() = runBlocking<Unit> {
        row(broken, single)
        val remembered = mutableListOf<Pair<String, MissingOn>>()
        val memory = object : TriedReleases by TriedReleases.None {
            override suspend fun missing(candidate: PlayCandidate, on: MissingOn) {
                remembered += candidate.id to on
            }
        }
        resolver(tried = memory).resolve(ID)
        assertEquals(listOf(key(broken) to MissingOn.ProvidersInUse), remembered)
    }

    @Test
    fun `the providers refusing the login wait for them, the release not counted`() = runBlocking<Unit> {
        val refused = Fake("hl-s02e04-auth", "Harbor.Lights.S02E04.1080p.WEB.h264-WREN", listOf("e1.mkv" to 1_000_000_000L), opening = NntpAuthenticationException("481 Authentication failed"))
        row(refused, single)
        val waiting = assertIs<DownloadResolver.Resolution.Waiting>(resolver().resolve(ID))
        assertEquals(DownloadHostRules.CHECK_PROVIDER, waiting.reason)
        assertEquals(DownloadState.Waiting, stored().state)
        assertEquals(DownloadHostRules.CHECK_PROVIDER, stored().reason)
        // The route stopped: the next release isn't grabbed, and the one tried isn't counted against the download.
        assertEquals(listOf(refused.guid), grabbed)
        assertEquals(emptyList(), stored().tried)
    }

    @Test
    fun `with no provider saved the download waits for one before any grab`() = runBlocking<Unit> {
        row(single)
        val waiting = assertIs<DownloadResolver.Resolution.Waiting>(resolver(providers = false).resolve(ID))
        assertEquals(DownloadHostRules.CHECK_PROVIDER, waiting.reason)
        assertEquals(DownloadState.Waiting, stored().state)
        assertEquals(emptyList(), grabbed)
        assertEquals(emptyList(), stored().tried)
    }

    /**
     * In a new process (the boot receiver's service, the job brought back after a kill) the providers saved reach the engine
     * once the app's start has read and decrypted them: until then Resolve neither grabs nor opens, and backs off as from a
     * provider out of reach, never parking the download as "Check your Usenet provider".
     */
    @Test
    fun `providers saved that the engine hasn't taken up yet are waited for, on both the reopen and the grab`() = runBlocking<Unit> {
        row(passworded) { it.resolvedAs(passworded, "c90a4f.mkv", 1_300_000_000L).copy(safeOffset = 400_000_000L) }
        storage.sources[ID] = DownloadStorage.Source(passworded.nzb, "s3cret-from-indexer")
        val out = assertIs<DownloadResolver.Resolution.Unreachable>(resolver(providers = null).resolve(ID))
        assertEquals(UsenetError.NotConfigured, out.error)
        assertEquals(DownloadState.Resolving, stored().state)
        assertEquals(DownloadHostRules.CANT_REACH_PROVIDER, stored().reason)
        assertEquals(emptyList(), opened)
        assertNotNull(storage.sources[ID])
        // Taken up: it goes on from its offset.
        assertEquals(400_000_000L, assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(ID)).row.safeOffset)

        reset()
        row(single)
        assertIs<DownloadResolver.Resolution.Unreachable>(resolver(providers = null).resolve(ID))
        assertEquals(emptyList(), grabbed)
        assertEquals(emptyList(), stored().tried)
        assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(ID))

        // None saved: the reopen waits for one too, before it opens anything.
        reset()
        row(passworded) { it.resolvedAs(passworded, "c90a4f.mkv", 1_300_000_000L) }
        storage.sources[ID] = DownloadStorage.Source(passworded.nzb, null)
        assertEquals(DownloadHostRules.CHECK_PROVIDER, assertIs<DownloadResolver.Resolution.Waiting>(resolver(providers = false).resolve(ID)).reason)
        assertEquals(emptyList(), opened)
    }

    @Test
    fun `providers out of reach are backed off from, nothing counted`() = runBlocking<Unit> {
        val unreachable = Fake(
            "hl-s02e04-down", "Harbor.Lights.S02E04.1080p.WEB.h264-WREN", listOf("e2.mkv" to 1_000_000_000L),
            opening = NntpConnectionException("Connection refused", ConnectException("Connection refused")),
        )
        row(unreachable)
        val out = assertIs<DownloadResolver.Resolution.Unreachable>(resolver().resolve(ID))
        assertIs<UsenetError.ConnectionFailed>(out.error)
        assertEquals(DownloadState.Resolving, stored().state)
        assertEquals(DownloadHostRules.CANT_REACH_PROVIDER, stored().reason)
        assertEquals(emptyList(), stored().tried)

        // Each resolve after a wait opens the NZB grabbed the first time: no grab is spent while the providers are away.
        assertIs<DownloadResolver.Resolution.Unreachable>(resolver().resolve(ID))
        unreachable.opening = null
        assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(ID))
        assertEquals(listOf(unreachable.guid), grabbed)
        assertEquals(listOf(key(unreachable)), stored().tried)
    }

    /**
     * The connection lost or a timeout as a release is checked or opened says nothing of the release (L382): it isn't
     * counted, no other release is grabbed while it lasts, and the download backs off and asks again, from the NZB kept.
     */
    @Test
    fun `a connection lost while a release is checked or opened is backed off from, nothing counted and nothing more grabbed`() = runBlocking<Unit> {
        row(single, pack)
        checkFails = java.io.IOException("Connection reset by peer")
        val out = assertIs<DownloadResolver.Resolution.Unreachable>(resolver().resolve(ID))
        assertIs<UsenetError.Network>(out.error)
        assertEquals(DownloadHostRules.CANT_REACH_PROVIDER, out.words)
        assertEquals(DownloadState.Resolving, stored().state)
        assertEquals(DownloadHostRules.CANT_REACH_PROVIDER, stored().reason)
        assertEquals(emptyList(), stored().tried)
        assertEquals(listOf(single.guid), grabbed)

        checkFails = null
        val ready = assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(ID))
        assertEquals(single.guid, ready.row.release?.guid)
        assertEquals(listOf(single.guid), grabbed)
        assertEquals(listOf(key(single)), stored().tried)

        // The same as it is opened.
        reset()
        row(single, pack)
        single.opening = java.io.IOException("Connection reset by peer")
        assertIs<UsenetError.Network>(assertIs<DownloadResolver.Resolution.Unreachable>(resolver().resolve(ID)).error)
        assertEquals(emptyList(), stored().tried)
        single.opening = null
        assertEquals(single.guid, assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(ID)).row.release?.guid)
        assertEquals(listOf(single.guid), grabbed)
    }

    /**
     * The probe's own deadline ("No track list within 20 s", where a file stalls it) and an IOException Media3 met as it
     * read, which reads as a lost connection whatever it was, are the probe's answer about the release, as a play takes
     * them: counted, and the next release tried. Backed off from instead, a release whose probe stalls would be checked,
     * opened and probed again for an hour, and the others never tried.
     */
    @Test
    fun `a probe that times out or fails as it reads is the release's answer, counted, and the next release is tried`() = runBlocking<Unit> {
        val next = Fake("hl-s02e04-wren", "Harbor.Lights.S02E04.720p.WEB.h264-WREN", listOf("d4.mkv" to 900_000_000L))
        listOf(
            ProbeResult.Failed(UsenetError.Timeout("No track list within 20 s")),
            ProbeResult.Failed(UsenetError.Network("Source error"), null, java.io.IOException("Source error")),
        ).forEach { failed ->
            reset()
            row(single, next)
            probeAnswer = { key -> failed.takeIf { key == key(single) } }
            val ready = assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(ID), "for $failed")
            assertEquals(next.guid, ready.row.release?.guid, "for $failed")
            assertEquals(listOf(single.guid, next.guid), grabbed, "for $failed")
            assertEquals(listOf(key(single), key(next)), stored().tried, "for $failed")
            assertEquals(listOf(key(single), key(next)), probed, "for $failed")
            assertNull(storage.grabs[ID], "for $failed")
        }
    }

    /** An indexer that can't be reached or answers 5xx gave no NZB: nothing was grabbed, so nothing is counted (L340). */
    @Test
    fun `an indexer out of reach counts nothing, and the download backs off saying so`() = runBlocking<Unit> {
        val five = (1..5).map { Fake("hl-later-$it", "Harbor.Lights.S02E04.720p.WEB.h264-GRP$it", listOf("f$it.mkv" to 800_000_000L)) }
        row(*five.toTypedArray())
        indexerFails = NewznabError.Server("HTTP 503")
        val out = assertIs<DownloadResolver.Resolution.Unreachable>(resolver().resolve(ID))
        assertEquals(DownloadHostRules.CANT_REACH_INDEXER, out.words)
        assertEquals(DownloadHostRules.CANT_REACH_INDEXER, stored().reason)
        assertEquals(DownloadState.Resolving, stored().state)
        assertEquals(emptyList(), stored().tried)

        // A dropped connection and a timeout are outages as a 5xx is; a refusal of the account or its allowance isn't. Their
        // kinds alone: a grab retries the first two after a second's wait of its own.
        with(DownloadResolver) {
            listOf(NewznabError.Network("Unable to resolve host"), NewznabError.Timeout("Read timed out"), NewznabError.Server("HTTP 503")).forEach {
                assertTrue(it.isOutage(), "for $it")
            }
            listOf(NewznabError.Auth("Bad key"), NewznabError.ApiLimitReached("Request limit reached"), NewznabError.DownloadLimitReached("Download limit reached")).forEach {
                assertFalse(it.isOutage(), "for $it")
            }
        }

        // Back: the first release, as it ranked.
        indexerFails = null
        val ready = assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(ID))
        assertEquals(five[0].guid, ready.row.release?.guid)
        assertEquals(listOf(key(five[0])), stored().tried)

        // Its refusal to serve more today is the indexer's answer: counted as before.
        reset()
        row(*five.toTypedArray())
        indexerFails = NewznabError.DownloadLimitReached("Download limit reached")
        assertIs<DownloadResolver.Resolution.Failed>(resolver().resolve(ID))
    }

    /**
     * A grab that never reached an indexer (one no longer set up) is no outage of it, whatever the grab before it met: its
     * release is counted, as a play counts it, while the one whose indexer was out isn't.
     */
    @Test
    fun `a release whose indexer is no longer set up is counted after another's indexer was out`() = runBlocking<Unit> {
        val elsewhere = Fake("hl-s02e04-gone", "Harbor.Lights.S02E04.1080p.WEB.h264-GRP", listOf("g.mkv" to 900_000_000L))
        row(single, elsewhere) { row ->
            row.copy(candidates = row.candidates.map { if (it.guid == elsewhere.guid) it.copy(indexerId = GONE_INDEXER) else it })
        }
        indexerFails = NewznabError.Server("HTTP 503")
        val out = assertIs<DownloadResolver.Resolution.Unreachable>(resolver().resolve(ID))
        assertEquals(DownloadHostRules.CANT_REACH_INDEXER, out.words)
        assertEquals(listOf(ReleaseKey.of(GONE_INDEXER, elsewhere.guid)), stored().tried)
    }

    @Test
    fun `a failure's words name nothing an indexer or a provider said`() {
        val words = listOf(
            io.skjaere.usenet.core.AttemptFailure.FetchFailed("Unable to resolve host indexer.invalid"),
            io.skjaere.usenet.core.AttemptFailure.Failed(UsenetError.Unknown("news.provider.invalid: handshake failed")),
            io.skjaere.usenet.core.AttemptFailure.Failed(UsenetError.Corrupt("crc mismatch in a8f3c1.mkv")),
            io.skjaere.usenet.core.AttemptFailure.Unsupported("No streamable files in Harbor.Lights.S02E04.rar"),
        ).map(DownloadResolver::reason)
        assertEquals(listOf("Couldn't get the NZB", "Couldn't open the release", "Release damaged", "Can't be read: an archive of a kind not supported"), words)
    }

    @Test
    fun `a download resolved before is opened again from its own NZB, with no grab, and goes on from its offset`() = runBlocking<Unit> {
        row(passworded) { it.resolvedAs(passworded, "c90a4f.mkv", 1_300_000_000L).copy(safeOffset = 400_000_000L, gaps = listOf(DownloadGap(10, 20))) }
        storage.sources[ID] = DownloadStorage.Source(passworded.nzb, "s3cret-from-indexer")
        val ready = assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(ID))
        assertEquals(emptyList(), grabbed)
        assertEquals<List<Pair<String, String?>>>(listOf(passworded.guid to "s3cret-from-indexer"), opened)
        assertEquals("c90a4f.mkv", ready.file.name)
        assertEquals(400_000_000L, ready.row.safeOffset)
        assertEquals(listOf(DownloadGap(10, 20)), ready.row.gaps)
        assertEquals(emptyList(), storage.deletedVideos)
        // No probe, and nothing in the store.
        assertEquals(emptyList(), probed)
        assertEquals(0, store.storedFiles().count)
    }

    /**
     * A reopen whose prepare fails for something that says nothing of the release (a reply the provider garbled, a failure
     * nobody named) keeps the download's own NZB and what it wrote, and backs off to try it again, as the writer reopens on
     * these (L382): never another release, which would start again from 0.
     */
    @Test
    fun `a reopen whose prepare fails for no fault of the release keeps its own NZB and its offset`() = runBlocking<Unit> {
        listOf(io.skjaere.nntp.NntpProtocolException("500 what?"), IllegalStateException("a check that failed")).forEach { failure ->
            reset()
            row(passworded, single, tried = listOf(passworded)) {
                it.resolvedAs(passworded, "c90a4f.mkv", 1_300_000_000L).copy(safeOffset = 400_000_000L, relativePath = "downloads/$ID/v.mkv")
            }
            storage.sources[ID] = DownloadStorage.Source(passworded.nzb, "s3cret-from-indexer")
            passworded.opening = failure
            assertIs<DownloadResolver.Resolution.Unreachable>(resolver().resolve(ID), "for $failure")
            assertNotNull(storage.sources[ID], "for $failure")
            assertEquals(emptyList(), grabbed, "for $failure")
            assertEquals(emptyList(), storage.deletedVideos, "for $failure")
            assertEquals(400_000_000L, stored().safeOffset, "for $failure")

            passworded.opening = null
            val ready = assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(ID), "for $failure")
            assertEquals(400_000_000L, ready.row.safeOffset, "for $failure")
            assertEquals(passworded.guid, ready.row.release?.guid, "for $failure")
        }
    }

    /**
     * Another nzb-streamer (an app update mid-download) can list an obfuscated file by its PAR2 name, or the other way
     * round (N54, NR-19): the file at its place, of its size, is the same one, and the download goes on from its offset.
     */
    @Test
    fun `a reopen finds its file listed under another name at its place and size`() = runBlocking<Unit> {
        row(single) { it.resolvedAs(single, OBFUSCATED, 1_200_000_000L).copy(safeOffset = 500_000_000L, relativePath = "downloads/$ID/v.mkv") }
        storage.sources[ID] = DownloadStorage.Source(single.nzb, null)
        val ready = assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(ID))
        assertEquals("a8f3c1.mkv", ready.file.name)
        assertEquals(500_000_000L, ready.row.safeOffset)
        assertEquals(emptyList(), grabbed)
        assertEquals(emptyList(), storage.deletedVideos)
        assertNotNull(storage.sources[ID])
    }

    @Test
    fun `a file whose size no longer matches starts again from 0`() = runBlocking<Unit> {
        row(single) { it.resolvedAs(single, "a8f3c1.mkv", 1_200_000_001L).copy(safeOffset = 600_000_000L, relativePath = "downloads/$ID/v.mkv") }
        storage.sources[ID] = DownloadStorage.Source(single.nzb, null)
        val ready = assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(ID))
        assertEquals(emptyList(), grabbed)
        assertEquals(0, ready.row.safeOffset)
        assertNull(ready.row.relativePath)
        assertEquals(1_200_000_000L, ready.row.size)
        assertEquals(1_200_000_000L, stored().size)
        assertEquals(0, stored().safeOffset)
        assertEquals(listOf(ID), storage.deletedVideos)
    }

    /** A start again from 0 cut short as the old file goes leaves the row naming no file and no offset (the file's own gone). */
    @Test
    fun `a start from 0 stopped as its file is deleted leaves the row at 0 naming no file`() = runBlocking<Unit> {
        row(single) { it.resolvedAs(single, "a8f3c1.mkv", 1_200_000_001L).copy(safeOffset = 600_000_000L, relativePath = "downloads/$ID/v.mkv") }
        storage.sources[ID] = DownloadStorage.Source(single.nzb, null)
        storage.afterDeleteVideo = { throw kotlinx.coroutines.CancellationException("the host stopped") }
        assertFailsWith<kotlinx.coroutines.CancellationException> { resolver().resolve(ID) }
        assertEquals(listOf(ID), storage.deletedVideos)
        assertEquals(0L, stored().safeOffset)
        assertNull(stored().relativePath)
        assertEquals(emptyList(), stored().gaps)
    }

    @Test
    fun `an own NZB that no longer gives the file is dropped and the next release resolved`() = runBlocking<Unit> {
        // No file of its name, nor one of its size at its place.
        row(single, pack, tried = listOf(single)) { it.resolvedAs(single, "gone.mkv", 1_300_000_000L) }
        storage.sources[ID] = DownloadStorage.Source(single.nzb, null)
        onGrab = { item -> storage.events += "grab ${item.guid}" }
        val ready = assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(ID))
        assertEquals(listOf(pack.guid), grabbed)
        assertEquals(pack.guid, ready.row.release?.guid)
        assertContentEquals(pack.nzb, storage.sources[ID]?.nzb)
        // Dropped before the next release was grabbed: a resolve that ends before it keeps a new one never opens it again.
        assertEquals(listOf("drop source $ID", "grab ${pack.guid}"), storage.events.filter { it.startsWith("drop source") || it.startsWith("grab") })

        // The candidates' resolve waiting (no provider saved after all): the copy is gone, not opened again at the next.
        reset()
        row(single, pack, tried = listOf(single)) { it.resolvedAs(single, "gone.mkv", 1_300_000_000L) }
        storage.sources[ID] = DownloadStorage.Source(single.nzb, null)
        var checks = 0
        assertIs<DownloadResolver.Resolution.Waiting>(
            DownloadResolver(
                dao, storage, availability, NzbPreparer(prepareBackend, Dispatchers.Unconfined), store, probe, { listOf(indexer) }, { _, item -> grab(item) },
                { _, _, _ -> TriedReleases.None }, { 10 }, providersChecked = { ++checks == 1 }, clock = { NOW },
                heldPreparer = { NzbPreparer(prepareBackend, Dispatchers.Unconfined) }, downloadContext = { DownloadRanking.context(StaticDeviceCapabilities(), PlaybackPrefs(), DownloadPrefs(), "en") },
            ).resolve(ID),
        )
        assertNull(storage.sources[ID])
        assertEquals(listOf(single.guid), opened.map { it.first })
    }

    @Test
    fun `the release its file came from goes on from the offset, another starts from 0 with the old file deleted`() = runBlocking<Unit> {
        // Try again after "Storage is full": no copy, the same release picked again.
        row(single) { it.resolvedAs(single, "a8f3c1.mkv", 1_200_000_000L).copy(safeOffset = 700_000_000L, relativePath = "downloads/$ID/v.mkv") }
        val same = assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(ID))
        assertEquals(listOf(single.guid), grabbed)
        assertEquals(700_000_000L, same.row.safeOffset)
        assertEquals("downloads/$ID/v.mkv", same.row.relativePath)
        assertEquals(emptyList(), storage.deletedVideos)

        reset()
        row(single, pack) { it.resolvedAs(broken, "b71d0e.mkv", 4_000_000_000L).copy(safeOffset = 700_000_000L, relativePath = "downloads/$ID/v.mkv") }
        val other = assertIs<DownloadResolver.Resolution.Ready>(resolver().resolve(ID))
        assertEquals(0, other.row.safeOffset)
        assertNull(other.row.relativePath)
        assertEquals(listOf(ID), storage.deletedVideos)
    }

    @Test
    fun `a removed download resolves to nothing`() = runBlocking<Unit> {
        assertEquals(DownloadResolver.Resolution.Gone, resolver().resolve(ID))
    }

    /** [this] as an earlier resolve left it: [fake]'s release, its file [fileName] of [size] bytes. */
    private fun Download.resolvedAs(fake: Fake, fileName: String, size: Long) = copy(
        release = RememberedRelease(INDEXER, fake.guid, fake.title, null, 0, PreparedNzb.idFor(fake.nzb), fileName, size),
        fileName = fileName, size = size, oneVideo = true,
    )

    private suspend fun reset() {
        dao.rows.value = emptyMap()
        dao.reasons.clear()
        dao.states.clear()
        storage.sources.clear()
        storage.grabs.clear()
        storage.events.clear()
        storage.afterDeleteVideo = {}
        onGrab = {}
        storage.deletedVideos.clear()
        storage.spaceAsked.clear()
        storage.space = ROOM
        storage.packs.sweep(emptyList())
        indexerFails = null
        checkFails = null
        probeAnswer = { null }
        grabbed.clear()
        checked.clear()
        opened.clear()
        probed.clear()
    }

    /** The downloads' table in memory: what Resolve reads and writes, and the words it set, in order. */
    private class FakeDao : DownloadDao {
        val rows = MutableStateFlow<Map<String, Download>>(emptyMap())
        val reasons = mutableListOf<String?>()
        val states = mutableListOf<DownloadState>()
        var onUpdate: (Download) -> Unit = {}

        private fun sorted(filter: (Download) -> Boolean): Flow<List<Download>> = rows.map { it.values.filter(filter).sortedBy(Download::position) }

        override fun queue() = sorted { it.state != DownloadState.Done && it.state != DownloadState.Deleting }
        override fun all() = sorted { true }
        override suspend fun get(id: String) = rows.value[id]
        override fun done() = sorted { it.state == DownloadState.Done }
        override suspend fun doneFor(watchKey: String) = rows.value.values.firstOrNull { it.state == DownloadState.Done && it.watchKey == watchKey }
        override fun forShow(showTmdbId: Int) = sorted { it.showTmdbId == showTmdbId }
        override fun forMovie(movieTmdbId: Int) = rows.map { it.values.firstOrNull { row -> row.movieTmdbId == movieTmdbId } }

        override suspend fun insert(download: Download) = rows.update { it + (download.id to download) }

        override suspend fun update(download: Download) {
            onUpdate(download)
            rows.update { if (download.id in it) it + (download.id to download) else it }
        }

        override suspend fun progress(id: String, safeOffset: Long, gaps: List<DownloadGap>, updatedAt: Long) =
            change(id) { it.copy(safeOffset = safeOffset, gaps = gaps, updatedAt = updatedAt) }

        override suspend fun setState(id: String, state: DownloadState, reason: String?, updatedAt: Long) {
            states += state
            reasons += reason
            change(id) { it.copy(state = state, reason = reason, updatedAt = updatedAt) }
        }

        override suspend fun move(id: String, position: Long) = change(id) { it.copy(position = position) }
        override suspend fun delete(id: String) = rows.update { it - id }

        private fun change(id: String, block: (Download) -> Download) = rows.update { all -> all[id]?.let { all + (id to block(it)) } ?: all }
    }

    /**
     * The download's places: its own NZB copies, the NZB kept for an attempt, the room on the device, the videos deleted, and
     * the season packs' copies ([packs]: the app's own, in the test's folder), which a test replaces with a new process's.
     */
    private class FakeStorage(var packs: DownloadNzbs) : DownloadResolver.Storage {
        val sources = mutableMapOf<String, DownloadStorage.Source>()
        val grabs = mutableMapOf<String, DownloadStorage.Grab>()

        /** What was asked of it, in order, with the test's own steps beside. */
        val events = mutableListOf<String>()
        var space: DownloadSpace? = ROOM
        val deletedVideos = mutableListOf<String>()
        val spaceAsked = mutableListOf<String>()

        override suspend fun keepSource(id: String, nzb: ByteArray, password: String?) {
            sources[id] = DownloadStorage.Source(nzb, password)
        }

        override suspend fun source(id: String) = sources[id]

        override suspend fun dropSource(id: String) {
            events += "drop source $id"
            sources.remove(id)
            grabs.remove(id)
        }

        override suspend fun keepGrab(id: String, key: String, nzb: ByteArray, password: String?) {
            grabs[id] = DownloadStorage.Grab(key, DownloadStorage.Source(nzb, password))
        }

        override suspend fun grab(id: String) = grabs[id]

        override suspend fun dropGrab(id: String) {
            grabs.remove(id)
        }

        /** Called once a video is deleted, before the call returns: where a cancel finds a delete that has happened. */
        var afterDeleteVideo: suspend () -> Unit = {}

        override suspend fun deleteVideo(id: String) {
            deletedVideos += id
            afterDeleteVideo()
        }

        override suspend fun space(except: String): DownloadSpace? {
            spaceAsked += except
            return space
        }

        override suspend fun pack(key: String) = packs.find(key)

        override suspend fun keepPack(meta: DownloadNzbs.Meta, nzb: ByteArray, password: String?) = packs.write(meta, nzb, password)

        override fun heldPrepare() = packs.held()

        override fun holdPrepare(key: String, prepared: PreparedNzb) = packs.hold(key, prepared)
    }

    private companion object {
        const val ID = "0b6c1d2e-download"

        /** A season's rows: its episodes 1 to 4, and an episode of another season and of another show. */
        const val E1 = "0b6c1d2e-e1"
        const val E2 = "0b6c1d2e-e2"
        const val E3 = "0b6c1d2e-e3"
        const val E4 = "0b6c1d2e-e4"
        const val OTHER_SEASON = "0b6c1d2e-s3e1"
        const val OTHER_SHOW = "0b6c1d2e-other"
        const val INDEXER = "fake-indexer"

        /** An indexer a candidate came from that is no longer set up. */
        const val GONE_INDEXER = "removed-indexer"
        const val SHOW = 900002
        const val NOW = 1_800_000_000_000L

        /** An article the providers lack. */
        const val GONE = "gone-"

        /** A posted file's obfuscated name, as a release may post one. */
        const val OBFUSCATED = "5d41402abc4b2a76b9719d911017c592.mkv"

        /** Room for any of the fakes: 100 GB. */
        val ROOM = DownloadSpace(available = 100_000_000_000L, used = 0, queued = 0)
    }
}
