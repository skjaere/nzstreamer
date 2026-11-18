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
import io.skjaere.usenet.app.debrid.DebridAdds
import io.skjaere.usenet.app.debrid.DebridListingStore
import io.skjaere.usenet.app.debrid.ServiceMisses
import io.skjaere.usenet.app.debrid.ServiceRegistry
import io.skjaere.usenet.app.play.IMPORTED
import io.skjaere.usenet.app.play.IMPORT_GONE
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
import io.skjaere.usenet.data.ServiceAccount
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.debrid.DebridService
import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.Prepared
import io.skjaere.usenet.debrid.ServiceError
import io.skjaere.usenet.debrid.ServiceException
import io.skjaere.usenet.debrid.ServiceFile
import io.skjaere.usenet.debrid.ServiceGate
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.debrid.ServiceLink
import io.skjaere.usenet.debrid.ServiceListing
import io.skjaere.usenet.logging.LogBuffer
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.NewznabError
import io.skjaere.usenet.newznab.NewznabException
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.NzbDownload
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseKey
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.releases.StaticDeviceCapabilities
import io.skjaere.usenet.search.EpisodeRequest
import io.skjaere.usenet.search.FindResult
import io.skjaere.usenet.search.FinderEvent
import io.skjaere.usenet.search.FoundRelease
import io.skjaere.usenet.search.IndexerReport
import io.skjaere.usenet.search.SearchStage
import io.skjaere.usenet.search.StageReport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ConnectException
import java.time.Duration
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

    /** A second indexer's, set up beside the first: its releases are its own (L399's "another indexer's releases"). */
    private val other = IndexerConfig(OTHER_INDEXER, "Other indexer", "http://other-indexer.invalid/api", "key-2")

    /** Each indexer's own answer to its grabs instead of the NZB, by its id, while set: a download limit. */
    private val refusing = mutableMapOf<String, NewznabError>()

    /** Each grab asked of an indexer, by guid, in order: those it refused too. */
    private val asks: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private suspend fun grab(item: NewznabItem, from: IndexerConfig = indexer): NzbDownload {
        onGrab(item)
        asks += item.guid
        refusing[from.id]?.let { throw NewznabException(it) }
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

    /** What the turn's search finds, in its order (L392): each as the indexer lists it. */
    private var finds: List<Fake> = emptyList()

    /** The torrents the turn's search finds after [finds], each as the Torznab indexer lists it (L459). */
    private var torrentFinds: List<Torrent> = emptyList()

    /** What the app would say kept the service accounts from checking a turn's torrents, where its search found nothing. */
    private var serviceStop: DownloadSearch.ServiceStop? = null

    /** The sizes the indexer lists for some of [finds], by guid, where they aren't their files' (a pack's share is an average). */
    private val listed = mutableMapOf<String, Long>()

    /** What each indexer said to the turn's search: by default, the one indexer answering. */
    private var findReports: List<IndexerReport> = listOf(IndexerReport(indexer, "TVDB id"))

    /** The episodes the turn's search asked for, in order. */
    private val searched: MutableList<EpisodeRequest> = Collections.synchronizedList(mutableListOf())

    /** The indexers each turn's search asked, by id, in order. */
    private val searchedAt: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())

    /** The indexer that lists each of [finds], by guid, where it isn't the first ([indexer]). */
    private val listedBy = mutableMapOf<String, String>()

    /** The release that played each episode last, by its watch key, as the library remembers it (L402). */
    private val lastPlayed = mutableMapOf<String, RememberedRelease>()

    /** The watch keys whose release that played last was asked for. */
    private val lastAsked = mutableListOf<String>()

    private fun context(cap: Int?) = DownloadRanking.context(StaticDeviceCapabilities(displayHeight = 1080), PlaybackPrefs(), DownloadPrefs(sizeCapGbPerHour = cap), "en")

    /**
     * Resolve over these fakes, what is known of releases tried [tried], or, where [remembered] is given, the app's memory of
     * them as the app's Resolve reads it. Download size is [cap] GB an hour, none when null; a prepare held for a season's
     * copy is handed back as the app's Resolve hands it back ([PlayingPack.preparer]), over the same fake prepare. The turn's
     * search asks [searchIndexers] (those the walk keeps out left out) and finds [finds], each indexer saying [findReports],
     * in the room the storage gives; the walk's memory of limits is [limits]. Its grabs are [fetchNzb]: the fake indexers'.
     */
    private fun resolver(
        attempts: Int = 10,
        tried: TriedReleases = TriedReleases.None,
        providers: Boolean? = true,
        remembered: MissingReleases? = null,
        cap: Int? = null,
        limits: DownloadSearch.Limits = DownloadSearch.Limits.None,
        searchIndexers: List<IndexerConfig> = listOf(indexer),
        fetchNzb: suspend (IndexerConfig, NewznabItem) -> NzbDownload = { from, item -> grab(item, from) },
        shown: Boolean = true,
        on: List<String> = listOf(ACCOUNT_A),
        offService: Set<String> = emptySet(),
        adds: DebridAdds? = null,
    ) = DownloadResolver(
        downloads = dao,
        storage = storage,
        availability = availability,
        preparer = NzbPreparer(prepareBackend, Dispatchers.Unconfined),
        store = store,
        probe = probe,
        indexers = { listOf(indexer, other) },
        fetchNzb = fetchNzb,
        tried = remembered?.let(DownloadResolver::remembered) ?: { _, _, _ -> tried },
        attempts = { attempts },
        providersChecked = { providers },
        clock = { NOW },
        heldPreparer = { held -> PlayingPack.preparer(held, prepareBackend, Dispatchers.Unconfined) },
        downloadContext = { context(cap) },
        search = DownloadSearch(
            downloads = dao,
            find = { request, asked, _ ->
                flow {
                    searched += request
                    searchedAt += asked.map { it.id }
                    finds.forEach { fakes[it.guid] = it }
                    val found = finds.map(::foundOf) + torrentFinds.map(::foundOf)
                    emit(FinderEvent.Finished(FindResult(request, found, emptyList(), listOf(StageReport(SearchStage.Episode, findReports)))))
                }
            },
            indexers = { searchIndexers },
            context = { context(cap) },
            space = { id -> storage.space(id) },
            limits = limits,
            clock = { NOW },
            stopped = { _, _ -> serviceStop },
        ),
        lastRelease = { key ->
            lastAsked += key
            lastPlayed[key]
        },
        limits = limits,
        listings = listings,
        accounts = { id ->
            lookups += id
            services[id]?.takeIf { shown && id in on }
        },
        accountsOn = {
            onAsked.incrementAndGet()
            if (shown) on else emptyList()
        },
        offService = { offService },
        misses = misses,
        adds = adds,
        shown = { shown },
    )

    /**
     * A torrent a debrid service may have cached (L460), as the Torznab indexer listed it: its [hash], its release's [title]
     * and its files (paths to sizes) as a service lists them, each with the service's own id.
     */
    private class Torrent(val hash: String, val title: String, files: List<Pair<String, Long>>) {
        val files: List<ServiceFile> = files.mapIndexed { i, (path, size) -> ServiceFile(i, path, size, id = "${700 + i}") }
        val key: String get() = "btih:$hash"
        val candidate: DownloadCandidate get() = DownloadCandidate(TORZNAB, key, title, size = files.sumOf { it.size }, source = ReleaseFacts.TORRENT)

        /** Its video for S02E04, by its name. */
        fun episode(): ServiceFile = episode(4)

        /** Its video for S02E0[episode], by its name. */
        fun episode(episode: Int): ServiceFile = files.single { "S02E%02d".format(episode) in it.path && it.path.endsWith(".mkv") }
    }

    /** A single episode's torrent: its info file, then its video. */
    private val goodTorrent = Torrent(
        "c0ffee01c0ffee02c0ffee03c0ffee04c0ffee05", "Harbor.Lights.S02E04.1080p.WEB-DL.AAC2.0.H.264-WREN",
        listOf("Harbor.Lights.S02E04.1080p.WEB-DL.AAC2.0.H.264-WREN/wren.nfo" to 4_096L, "Harbor.Lights.S02E04.1080p.WEB-DL.AAC2.0.H.264-WREN/Harbor.Lights.S02E04.1080p.WEB-DL.AAC2.0.H.264-WREN.mkv" to 1_250_000_000L),
    )

    /** Another release of the episode, as a torrent. */
    private val otherTorrent = Torrent(
        "c0ffee11c0ffee12c0ffee13c0ffee14c0ffee15", "Harbor.Lights.S02E04.720p.WEB-DL.AAC2.0.H.264-WREN",
        listOf("Harbor.Lights.S02E04.720p.WEB-DL.AAC2.0.H.264-WREN.mkv" to 700_000_000L),
    )

    /** The season in a folder, each episode's video named for it. */
    private val packTorrent = Torrent(
        "c0ffee21c0ffee22c0ffee23c0ffee24c0ffee25", "Harbor.Lights.S02.1080p.WEB-DL.AAC2.0.H.264-PACK",
        (1..6).map { "Harbor.Lights.S02.1080p.WEB-DL.AAC2.0.H.264-PACK/Harbor.Lights.S02E%02d.1080p.WEB-DL.AAC2.0.H.264-PACK.mkv".format(it) to 1_100_000_000L + it },
    )

    /** A season pack whose videos are named for no episode: which is S02E04 is a question nobody is there to answer. */
    private val unnamedTorrent = Torrent(
        "c0ffee31c0ffee32c0ffee33c0ffee34c0ffee35", "Harbor.Lights.S02.720p.WEB.h264-PACK",
        (1..6).map { "Harbor.Lights.S02.720p.WEB.h264-PACK/hl.part$it.mkv" to 800_000_000L + it },
    )

    /**
     * A debrid account's service as Resolve asks it (L460): the torrents it has [cached], by hash, prepared as a service
     * prepares one (its files handed to the pick before anything is added, nothing added for a pick of none), each add
     * told to [watching]'s watch as TorBox's client tells its own; and a link to the picked file. [failsFor] fails a
     * torrent's prepare with its error. A removal is [removed].
     */
    private inner class FakeService(private val id: String) : DebridService {
        override val kind = ServiceKind.TorBox
        val cached = mutableMapOf<String, List<ServiceFile>>()
        val failsFor = mutableMapOf<String, ServiceError>()
        val prepares: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val added: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val links = java.util.concurrent.atomic.AtomicInteger()
        val removed: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var watching: DebridAdds? = null

        override suspend fun test() = throw AssertionError("a download never tests a key")

        override suspend fun check(hashes: List<InfoHash>) = throw AssertionError("a download never checks the cache")

        override suspend fun prepare(hash: InfoHash, pick: suspend (ServiceListing) -> ServiceFile?): Prepared {
            prepares += hash.hex
            failsFor[hash.hex]?.let { throw ServiceException(it) }
            val listing = ServiceListing(cached[hash.hex] ?: throw ServiceException(ServiceError.NotCached))
            val picked = pick(listing) ?: return Prepared(hash, listing, null)
            // The torrents' hashes share their first digits: each one's own handle in the account by its last.
            val handle = "${id.take(4)}-${hash.hex.takeLast(6)}"
            added += hash.hex
            watching?.watch(accountOf(id))?.added(hash, handle)
            return Prepared(hash, listing, picked, handle)
        }

        override suspend fun link(prepared: Prepared, file: ServiceFile): ServiceLink {
            links.incrementAndGet()
            return ServiceLink("https://cdn.example.org/dl/${file.index}", java.time.Instant.ofEpochMilli(NOW))
        }

        override suspend fun remove(handle: String) {
            removed += handle
        }
    }

    private val serviceA = FakeService(ACCOUNT_A)
    private val serviceB = FakeService(ACCOUNT_B)
    private val services = mapOf(ACCOUNT_A to serviceA, ACCOUNT_B to serviceB)

    /** Each account the gated lookup was asked for, and how often the accounts on were read. */
    private val lookups: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val onAsked = java.util.concurrent.atomic.AtomicInteger()

    /** The listings the services prepared, as the app's store keeps them. */
    private val listings by lazy { DebridListingStore(File(nzbDir, "debrid/listings.json"), io = Dispatchers.Unconfined) }

    /** What plays remember of the torrents the accounts failed, in the library's table in memory: a case's own. */
    private var misses = ServiceMisses(TriedTable(NOW), now = { NOW })

    /** [id]'s account as the settings save it: Remove after playing on, its key saved at [KEY_AT]. */
    private fun accountOf(id: String) = ServiceAccount(id, ServiceKind.TorBox.name, keySavedAt = KEY_AT)

    /** The wall clock of what plays and downloads added ([adds]): moved on by a test past a grace. */
    private var addedNow = NOW

    /** Where [adds] runs its sweeps on its own: cancelled at each case's end, so no sweep it began outlives the case. */
    private val addsScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())

    /** What this device added to the accounts, in a file of the test's own, every account on with its gate open. */
    private fun adds(): DebridAdds = DebridAdds(
        File(nzbDir, "debrid/added.json"), accounts = { services.keys.map(::accountOf) },
        usable = { id -> services[id]?.let { ServiceRegistry.Client(accountOf(id), ServiceKind.TorBox, it, ServiceGate()) } },
        scope = addsScope, clock = { addedNow }, shown = { true }, io = Dispatchers.Unconfined, log = {},
    ).also { adds -> services.values.forEach { it.watching = adds } }

    /** A grace on, then a sweep of what plays and downloads added, as one runs then. */
    private suspend fun sweptAfterGrace(adds: DebridAdds) {
        addedNow += DebridAdds.GRACE.toMillis() + 1_000
        adds.sweep()
    }

    @org.junit.jupiter.api.AfterEach
    fun cancelSweeps() {
        addsScope.cancel()
    }

    /** Harbor Lights S02E04's download of [candidates] in download order (a Usenet fake's as [usenet] gives it). */
    private suspend fun rowOf(vararg candidates: DownloadCandidate, tried: List<String> = emptyList(), edit: (Download) -> Download = { it }): Download {
        val row = Download(
            id = ID, watchKey = WatchKey.episode(SHOW, 2, 4), kind = DownloadKind.Episode, title = "Harbor Lights", showTmdbId = SHOW,
            season = 2, episode = 4, seasonEpisodes = 6, episodeName = "Low Water", position = 0, candidates = candidates.toList(),
            tried = tried, indexerSize = candidates.firstOrNull()?.size ?: 0, createdAt = 0, updatedAt = 0,
        ).let(edit)
        dao.insert(row)
        return row
    }

    /** [fake] as a row's candidate, the indexer's release. */
    private fun usenet(fake: Fake): DownloadCandidate {
        fakes[fake.guid] = fake
        return DownloadCandidate(INDEXER, fake.guid, fake.title, "http://indexer.invalid/api?t=get&id=${fake.guid}&apikey={apikey}", fake.size)
    }

    /** [torrent]'s file for S02E04 as a row keeps it once resolved on [account]: its release, file and size, and its listing's copy. */
    private suspend fun resolvedOn(torrent: Torrent, account: String = ACCOUNT_A, offset: Long = 0): Download {
        val file = torrent.episode()
        val hash = InfoHash.of(torrent.hash)
        val entry = DebridListingStore.Entry(DebridListingStore.idFor(account, hash), account, torrent.title, Prepared(hash, ServiceListing(torrent.files), file, "${account.take(4)}-1"))
        val row = rowOf(torrent.candidate, tried = listOf(torrent.key)) {
            it.copy(
                release = RememberedRelease(TORZNAB, torrent.key, torrent.title, null, file.index, torrent.key, file.name, file.size, source = ReleaseFacts.TORRENT),
                fileName = file.name, size = file.size, safeOffset = offset, relativePath = "downloads/$ID/v.mkv",
            )
        }
        storage.keepListing(ID, entry)
        storage.events.clear()
        return row
    }

    /** [fake] as the finder gives it: as its indexer lists it, ranked first-come. */
    private fun foundOf(fake: Fake): FoundRelease {
        val size = listed[fake.guid] ?: fake.size
        val item = NewznabItem(
            indexerId = listedBy[fake.guid] ?: INDEXER, guid = fake.guid, title = fake.title, sizeBytes = size, publishedAt = null, postedAt = null, categories = emptyList(),
            grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null, tvmazeId = null, season = null, episode = null,
            coverUrl = null, mediaInfo = null, downloadTemplate = "http://indexer.invalid/api?t=get&id=${fake.guid}&apikey={apikey}", attributes = emptyMap(),
        )
        val ranked = RankedRelease(ReleaseFacts(item.key, fake.title, size), ReleaseParser.parse(fake.title), playable = true, score = 1.0, reasons = emptyList(), estimatedBitrate = null)
        return FoundRelease(item, ranked, SearchStage.Episode)
    }

    /** [torrent] as the turn's search finds it: as the Torznab indexer lists it, its hash given, ranked first-come. */
    private fun foundOf(torrent: Torrent): FoundRelease {
        val size = torrent.candidate.size
        val item = NewznabItem(
            indexerId = TORZNAB, guid = torrent.key, title = torrent.title, sizeBytes = size, publishedAt = null, postedAt = null, categories = emptyList(),
            grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null, tvmazeId = null, season = null, episode = null,
            coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(), infoHash = torrent.hash,
        )
        val ranked = RankedRelease(
            ReleaseFacts(item.key, torrent.title, size, source = ReleaseFacts.TORRENT), ReleaseParser.parse(torrent.title), playable = true, score = 1.0,
            reasons = emptyList(), estimatedBitrate = null,
        )
        return FoundRelease(item, ranked, SearchStage.Episode)
    }

    /** [torrent]'s meta kept for season 2 of the show, leading or not, as an earlier row of the season kept it (L462). */
    private suspend fun keptForSeason(torrent: Torrent, leads: Boolean = true) {
        storage.packs.write(DownloadNzbs.Meta(torrent.key, torrent.title, torrent.candidate, SHOW, 2, leads), null, null)
    }

    /** Harbor Lights S02E0[episode]'s download, [id], of the torrents [candidates] in download order, a row of [ID]'s season. */
    private suspend fun torrentEpisode(id: String, episode: Int, vararg candidates: DownloadCandidate, edit: (Download) -> Download = { it }): Download =
        rowOf(*candidates) { it.copy(id = id, watchKey = WatchKey.episode(SHOW, 2, episode), episode = episode, episodeName = null).let(edit) }

    /** [this] a season row (L392): the season sheet's search facts for its episode, its turn not yet searched. */
    private fun Download.withFacts(searched: Boolean = false) = copy(search = DownloadColumns.text(FACTS.copy(searched = searched)))

    /** [fake]'s copy kept for season 2 of the show (L394), leading or not, as an earlier row of the season kept it. */
    private suspend fun keptForSeason(fake: Fake, leads: Boolean = true) {
        fakes[fake.guid] = fake
        val candidate = DownloadCandidate(INDEXER, fake.guid, fake.title, "http://indexer.invalid/api?t=get&id=${fake.guid}&apikey={apikey}", fake.size)
        storage.packs.write(DownloadNzbs.Meta(key(fake), fake.title, candidate, SHOW, 2, leads), fake.nzb, fake.password)
    }

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
            tried = tried.map(::key), indexerSize = candidates.firstOrNull()?.size ?: 0, createdAt = 0, updatedAt = 0,
        ).let(edit)
        dao.insert(row)
        return row
    }

    /** [fakes]' releases listed by the second indexer, [other], rather than the first. */
    private fun otherIndexer(vararg fakes: Fake): (Download) -> Download = { row ->
        row.copy(candidates = row.candidates.map { c -> if (fakes.any { it.guid == c.guid }) c.copy(indexerId = OTHER_INDEXER) else c })
    }

    private suspend fun stored(): Download = assertNotNull(dao.get(ID))

    private suspend fun stored(id: String): Download = assertNotNull(dao.get(id))

    /** Harbor Lights S02E0[episode]'s download, [id], of [candidates] in download order, a row of the same season as [ID]'s. */
    private suspend fun episode(id: String, episode: Int, vararg candidates: Fake, edit: (Download) -> Download = { it }): Download =
        row(*candidates) { it.copy(id = id, watchKey = WatchKey.episode(SHOW, 2, episode), episode = episode, episodeName = null).let(edit) }

    @Test
    fun `a release missing articles in the full check falls to the next, which is grabbed checked opened and probed`() = runBlocking<Unit> {
        row(broken, single)
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
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
        assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
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
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
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
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
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
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver(attempts = 1).resolve(ID))
        assertEquals(single.guid, ready.row.release?.guid)
        assertEquals(emptyList(), grabbed)
        assertEquals(listOf(key(single)), stored().tried)
    }

    @Test
    fun `the store's entry the probe put is dropped and the NZB and its password are copied to the download's own folder`() = runBlocking<Unit> {
        row(passworded)
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
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
        assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
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
            val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver.resolve(id))
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

    /**
     * A process that dies between two rows of a season loses the prepare it held, never the copy: no second grab. The next
     * row prepares the pack again from the copy and holds that prepare in its turn, so the season's later rows in the new
     * process prepare nothing: one prepare per process.
     */
    @Test
    fun `after a process death between a season's rows the next takes the pack from its copy, prepared again`() = runBlocking<Unit> {
        episode(E1, 1, pack, single)
        episode(E2, 2, pack, single)
        episode(E3, 3, pack, single)
        assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(E1))
        dao.setState(E1, DownloadState.Done, null, NOW)

        // A new process: the copies on disk, no prepare held.
        storage.packs = packs()
        val resolver = resolver()
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver.resolve(E2))
        assertEquals("Harbor.Lights.S02E02.1080p.WEB.h264-GRP.mkv", ready.file.name)
        assertEquals(listOf(pack.guid), grabbed)
        assertEquals(listOf(pack.guid, pack.guid), opened.map { it.first })
        dao.setState(E2, DownloadState.Done, null, NOW)

        val third = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver.resolve(E3))
        assertEquals("Harbor.Lights.S02E03.1080p.WEB.h264-GRP.mkv", third.file.name)
        assertEquals(listOf(pack.guid), grabbed)
        assertEquals(listOf(pack.guid, pack.guid), opened.map { it.first })
    }

    /**
     * L394: one prepare of the pack per process. A process that dies as a row of the season is written loses the prepare it
     * held; the row, opened again from its own NZB, prepares the pack again and holds that prepare for the season's next
     * row, which takes the pack from the copy with no grab and no prepare of its own.
     */
    @Test
    fun `a row opened again from its own NZB after a process death holds its prepare for the season's next row`() = runBlocking<Unit> {
        episode(E1, 1, pack, single)
        episode(E2, 2, pack, single)
        assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(E1))

        // A new process as E1 is written: the copies on disk, no prepare held.
        storage.packs = packs()
        val resolver = resolver()
        val reopened = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver.resolve(E1))
        assertEquals("Harbor.Lights.S02E01.1080p.WEB.h264-GRP.mkv", reopened.file.name)
        dao.setState(E1, DownloadState.Done, null, NOW)

        val next = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver.resolve(E2))
        assertEquals("Harbor.Lights.S02E02.1080p.WEB.h264-GRP.mkv", next.file.name)
        assertEquals(listOf(pack.guid), grabbed)
        assertEquals(listOf(pack.guid, pack.guid), opened.map { it.first })
        assertEquals(1, storage.packWrites, "the copy written once, by the first resolve")
    }

    /**
     * L432 at a resolve's success, whichever way it succeeds: a process that died after a row kept its season pack's NZB as
     * its own and before the copy was written leaves no copy. The row, opened again from its own NZB, keeps it for the
     * season's rows still to finish then, and holds its prepare: the next row takes both, with no grab and no prepare.
     */
    @Test
    fun `a row opened again from its own NZB of a season pack keeps the copy a process death cut short`() = runBlocking<Unit> {
        episode(E1, 1, pack, single) { it.resolvedAs(pack, "Harbor.Lights.S02E01.1080p.WEB.h264-GRP.mkv", 1_000_000_001L).copy(tried = listOf(key(pack))) }
        storage.sources[E1] = DownloadStorage.Source(pack.nzb, null)
        episode(E2, 2, pack, single)
        assertNull(storage.packs.find(key(pack)))

        val resolver = resolver()
        assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver.resolve(E1))
        val copy = assertNotNull(storage.packs.find(key(pack)))
        assertEquals(DownloadNzbs.Meta(key(pack), pack.title, stored(E1).candidates.first(), SHOW, 2, leads = true), copy.meta)
        assertContentEquals(pack.nzb, assertNotNull(copy.source).nzb)
        dao.setState(E1, DownloadState.Done, null, NOW)

        val next = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver.resolve(E2))
        assertEquals("Harbor.Lights.S02E02.1080p.WEB.h264-GRP.mkv", next.file.name)
        assertEquals(emptyList(), grabbed)
        assertEquals(listOf(pack.guid), opened.map { it.first })
    }

    /** A row whose own release ranks first isn't served the season's pack: it grabs its own, and the copy stays for the rest. */
    @Test
    fun `a row whose single ranks first grabs the single and leaves the season's copy`() = runBlocking<Unit> {
        episode(E1, 1, pack)
        episode(E2, 2, pack)
        episode(E4, 4, single, pack)
        assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(E1))
        dao.setState(E1, DownloadState.Done, null, NOW)

        val own = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(E4))
        assertEquals(single.guid, own.row.release?.guid)
        assertEquals(listOf(pack.guid, single.guid), grabbed)
        assertNotNull(storage.packs.find(key(pack)))
        dao.setState(E4, DownloadState.Done, null, NOW)

        assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(E2))
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
        assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(E1))
        assertNull(storage.packs.find(key(pack)))

        // The season's others done, failed or removed; another season's, another show's and a film's still to finish.
        reset()
        episode(E1, 1, pack)
        episode(E2, 2, pack) { it.copy(state = DownloadState.Done) }
        episode(E3, 3, pack) { it.copy(state = DownloadState.Failed) }
        episode(E4, 4, pack) { it.copy(state = DownloadState.Deleting) }
        episode(OTHER_SEASON, 1, pack) { it.copy(season = 3, watchKey = WatchKey.episode(SHOW, 3, 1)) }
        episode(OTHER_SHOW, 1, pack) { it.copy(showTmdbId = 900003, watchKey = WatchKey.episode(900003, 2, 1)) }
        assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(E1))
        assertNull(storage.packs.find(key(pack)))

        // An episode's own release, with the season's others to come: no copy.
        reset()
        episode(E4, 4, single)
        episode(E2, 2, pack)
        assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(E4))
        assertNull(storage.packs.find(key(single)))

        // One of the season paused: kept for it, leading, with the indexer's password.
        reset()
        val locked = Fake(
            "hl-s02-pw-pack", "Harbor.Lights.S02.1080p.WEB.h264-QUILL",
            (1..6).map { "Harbor.Lights.S02E%02d.1080p.WEB.h264-QUILL.mkv".format(it) to 1_000_000_000L + it }, password = "s3cret-from-indexer",
        )
        episode(E1, 1, locked) { it.copy(runtimeMinutes = 44) }
        episode(E2, 2, locked) { it.copy(state = DownloadState.Paused) }
        assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(E1))
        val copy = assertNotNull(storage.packs.find(key(locked)))
        assertEquals(DownloadNzbs.Meta(key(locked), locked.title, stored(E1).candidates.single(), SHOW, 2, leads = true), copy.meta)
        assertContentEquals(locked.nzb, assertNotNull(copy.source).nzb)
        assertEquals("s3cret-from-indexer", assertNotNull(copy.source).password)

        // Above Download size for the episode (its share of 6 GB over 44 minutes is about 1.4 GB an hour): kept, not leading.
        reset()
        episode(E1, 1, locked) { it.copy(runtimeMinutes = 44) }
        episode(E2, 2, locked)
        assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver(cap = 1).resolve(E1))
        assertEquals(false, storage.packs.find(key(locked))?.meta?.leads)
        reset()
        episode(E1, 1, locked) { it.copy(runtimeMinutes = 44) }
        episode(E2, 2, locked)
        assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver(cap = 2).resolve(E1))
        assertEquals(true, storage.packs.find(key(locked))?.meta?.leads)
    }

    /** Resolve's lines name a release taken from its season's copy by its place, never by its name or the copy's key. */
    @Test
    fun `a release taken from the season's copy is named by its place in the log`() = runBlocking<Unit> {
        episode(E1, 1, pack)
        episode(E2, 2, single, pack)
        assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(E1))
        dao.setState(E1, DownloadState.Done, null, NOW)
        // The single is missing on the providers: the pack, its second, comes from the copy.
        val gone = Fake(single.guid, single.title, single.files, gone = true)
        fakes[single.guid] = gone
        val from = LogBuffer.snapshot().lastOrNull()?.seq ?: 0L
        assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(E2))
        val lines = LogBuffer.snapshot().filter { it.seq > from }.map { it.message }
        assertTrue(lines.any { "download $E2, release 2" in it && "copy" in it }, lines.toString())
        assertEquals(emptyList(), lines.filter { line -> listOf(pack.title, pack.guid, key(pack)).any { it in line } })
        assertEquals(listOf(pack.guid, single.guid), grabbed)
    }

    /**
     * L340, L394: a row's attempt of its season's pack, taken from the copy, is counted as it starts, as any attempt is, and
     * its NZB kept for it as a grab is. A process that dies mid-attempt leaves both on disk, and the next resolve tries the
     * pack again first from what was kept, counted once and with no grab: never passed over for a release grabbed instead.
     */
    @Test
    fun `a pack taken from its season's copy and cut off by a process death is tried again from what was kept, with no grab`() = runBlocking<Unit> {
        episode(E1, 1, pack, single)
        episode(E2, 2, pack, single)
        assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(E1))
        dao.setState(E1, DownloadState.Done, null, NOW)

        // E2's attempt of the pack, from the copy; the process dies as its articles are checked: what is on disk then.
        val dying = CompletableDeferred<Pair<Map<String, Download>, Map<String, DownloadStorage.Grab>>>()
        onCheck = {
            dying.complete(dao.rows.value to storage.grabs.toMap())
            awaitCancellation()
        }
        val resolve = launch(Dispatchers.Default) { resolver().resolve(E2) }
        val (rows, grabs) = withTimeout(10_000) { dying.await() }
        resolve.cancelAndJoin()
        onCheck = {}
        assertEquals(listOf(key(pack)), rows.getValue(E2).tried)
        dao.rows.value = rows
        storage.grabs.clear()
        storage.grabs += grabs
        storage.packs = packs()

        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(E2))
        assertEquals(pack.guid, ready.row.release?.guid)
        assertEquals("Harbor.Lights.S02E02.1080p.WEB.h264-GRP.mkv", ready.file.name)
        assertEquals(listOf(pack.guid), grabbed)
        assertEquals(listOf(key(pack)), stored(E2).tried)
        // Its answer had: what was kept for the attempt goes, the row's own copy of the NZB kept for a resume.
        assertNull(storage.grabs[E2])
        assertContentEquals(pack.nzb, storage.sources[E2]?.nzb)
    }

    /**
     * The same for the volume away as a pack taken from the copy is picked (L340): the row waits for it, and once it is
     * back the pack is taken up again from what was kept for its attempt, counted once and with no grab. The season's copy
     * stays as it was written, its NZB the one kept.
     */
    @Test
    fun `a pack taken from its season's copy that waited for the volume is taken up again from what was kept, with no grab`() = runBlocking<Unit> {
        episode(E1, 1, pack, single)
        episode(E2, 2, pack, single)
        episode(E3, 3, pack, single)
        assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(E1))
        dao.setState(E1, DownloadState.Done, null, NOW)

        storage.space = null
        assertIs<DownloadResolver.Resolution.Waiting>(resolver().resolve(E2))
        assertEquals(DownloadHostRules.STORAGE_UNAVAILABLE, stored(E2).reason)

        storage.space = ROOM
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(E2))
        assertEquals(pack.guid, ready.row.release?.guid)
        assertEquals(listOf(pack.guid), grabbed)
        assertEquals(listOf(key(pack)), stored(E2).tried)
        assertNull(storage.grabs[E2])
        assertEquals(1, storage.packWrites, "the copy written once, by the first")
        assertNotNull(storage.packs.find(key(pack)), "kept for the third")
    }

    @Test
    fun `an exact size over the room on the device fails as Won't fit, with nothing copied`() = runBlocking<Unit> {
        row(single)
        storage.space = DownloadSpace(available = 2_000_000_000L + DownloadSpace.KEPT_FREE, used = 0, queued = 900_000_000L)
        val failed = assertIs<DownloadResolver.Resolution.Failed>(resolver().resolve(ID))
        assertEquals(DownloadResolver.WONT_FIT, failed.reason)
        assertEquals("Won't fit", stored().reason)
        assertEquals(DownloadState.Failed, stored().state)
        // Passed over before anything of it was written into the row (L401): no release, file or size of its own yet.
        assertNull(stored().release)
        assertNull(stored().size)
        assertEquals(listOf(key(single)), stored().tried)
        assertNull(storage.sources[ID])
        assertEquals(listOf(ID), storage.spaceAsked)
        // Its store entry went all the same.
        assertEquals(0, store.storedFiles().count)

        // Room for it: it fits.
        reset()
        row(single)
        storage.space = DownloadSpace(available = 1_200_000_000L + DownloadSpace.KEPT_FREE, used = 0, queued = 0)
        assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
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
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
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
        assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver(tried = known).resolve(ID))
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
        assertEquals(400_000_000L, assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID)).row.safeOffset)

        reset()
        row(single)
        assertIs<DownloadResolver.Resolution.Unreachable>(resolver(providers = null).resolve(ID))
        assertEquals(emptyList(), grabbed)
        assertEquals(emptyList(), stored().tried)
        assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))

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
        assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
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
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
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
        assertEquals(single.guid, assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID)).row.release?.guid)
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
            val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID), "for $failed")
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
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
        assertEquals(five[0].guid, ready.row.release?.guid)
        assertEquals(listOf(key(five[0])), stored().tried)

        // Its refusal to serve more today is no answer about the release either (L399): a wait for its limit, nothing counted.
        reset()
        row(*five.toTypedArray())
        indexerFails = NewznabError.DownloadLimitReached("Download limit reached")
        assertEquals(DownloadHostRules.LIMIT_REACHED, assertIs<DownloadResolver.Resolution.Waiting>(resolver().resolve(ID)).reason)
        assertEquals(emptyList(), stored().tried)
    }

    /**
     * L399, L434: a grab refused for the indexer's download limit (its 501) counts no try, as an outage doesn't; with the
     * indexer's other releases left untried behind it and nothing else to try, the download waits as the limit, which ends
     * the walk and its host, the limit's facts kept for the page (which indexer, its download limit, since when, no end).
     * The indexer refuses its other releases alike, so they aren't asked. Asked again (Resume, the app's start), the same
     * while it lasts, nothing counted; once it has gone, its first release, as it ranked. Its lines name the download and
     * the limit's kind, never the indexer or what it said.
     */
    @Test
    fun `a grab refused for a download limit counts no try and waits as the limit, the same while it lasts`() = runBlocking<Unit> {
        val five = (1..5).map { Fake("hl-limit-$it", "Harbor.Lights.S02E04.720p.WEB.h264-GRP$it", listOf("l$it.mkv" to 800_000_000L)) }
        row(*five.toTypedArray())
        indexerFails = NewznabError.DownloadLimitReached("Download limit reached for plover.indexer.invalid")
        val from = LogBuffer.snapshot().lastOrNull()?.seq ?: 0L
        val limited = assertIs<DownloadResolver.Resolution.Waiting>(resolver().resolve(ID))
        assertEquals(DownloadHostRules.LIMIT_REACHED, limited.reason)
        assertEquals(DownloadState.Waiting, stored().state)
        assertEquals(DownloadHostRules.LIMIT_REACHED, stored().reason)
        assertEquals(emptyList(), stored().tried)
        assertEquals(LimitWait(INDEXER, LimitWait.Kind.Download, NOW, null), DownloadColumns.limitWait(stored()))
        assertEquals(listOf(five[0].guid), asks, "the indexer's other releases aren't asked")
        assertEquals(emptyList(), grabbed)
        assertNull(storage.grabs[ID])
        val lines = LogBuffer.snapshot().filter { it.seq > from }.map { it.message }
        assertTrue(lines.any { "download $ID" in it && "download limit" in it }, lines.toString())
        val secret = listOf("plover.indexer", indexer.name, "Harbor.Lights")
        assertEquals(emptyList(), lines.filter { line -> secret.any { it in line } })

        // Asked again while it lasts: the same, nothing counted.
        asks.clear()
        assertEquals(DownloadHostRules.LIMIT_REACHED, assertIs<DownloadResolver.Resolution.Waiting>(resolver().resolve(ID)).reason)
        assertEquals(emptyList(), stored().tried)
        assertEquals(listOf(five[0].guid), asks)

        // Gone: its first release, as it ranked, counted once.
        indexerFails = null
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
        assertEquals(five[0].guid, ready.row.release?.guid)
        assertEquals(listOf(key(five[0])), stored().tried)
    }

    /**
     * L399: one indexer's download limit leaves the other indexers' releases to try, within the download's tries, however
     * few the tries left are. One of theirs that plays is the download's. Where theirs fail for their own sake, counted,
     * the download waits for the limited indexer's, untried; with no try left for them, it fails in the last failure's words.
     */
    @Test
    fun `one indexer's download limit leaves another indexer's releases to try, and waits for its own untried`() = runBlocking<Unit> {
        val elsewhere = Fake("hl-s02e04-other", "Harbor.Lights.S02E04.1080p.WEB.h264-WREN", listOf("o1.mkv" to 1_100_000_000L))
        refusing[INDEXER] = NewznabError.DownloadLimitReached("Download limit reached")
        row(single, elsewhere, edit = otherIndexer(elsewhere))
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
        assertEquals(elsewhere.guid, ready.row.release?.guid)
        assertEquals(listOf(single.guid, elsewhere.guid), asks)
        assertEquals(listOf(ReleaseKey.of(OTHER_INDEXER, elsewhere.guid)), stored().tried)

        // The other's fails for its own sake, counted: a wait for the limited one's, untried.
        reset()
        val brokenElsewhere = Fake("hl-s02e04-other-gone", "Harbor.Lights.S02E04.2160p.WEB.h265-WREN", listOf("o2.mkv" to 3_000_000_000L), gone = true)
        refusing[INDEXER] = NewznabError.DownloadLimitReached("Download limit reached")
        row(single, brokenElsewhere, edit = otherIndexer(brokenElsewhere))
        assertEquals(DownloadHostRules.LIMIT_REACHED, assertIs<DownloadResolver.Resolution.Waiting>(resolver().resolve(ID)).reason)
        assertEquals(listOf(ReleaseKey.of(OTHER_INDEXER, brokenElsewhere.guid)), stored().tried)
        assertEquals(LimitWait(INDEXER, LimitWait.Kind.Download, NOW, null), DownloadColumns.limitWait(stored()))

        // One try in all: the refused grab spends none of it, so the other's release is still tried; failing, nothing is
        // left to try the limited one's with, and the download fails as it did.
        reset()
        refusing[INDEXER] = NewznabError.DownloadLimitReached("Download limit reached")
        row(single, brokenElsewhere, edit = otherIndexer(brokenElsewhere))
        val failed = assertIs<DownloadResolver.Resolution.Failed>(resolver(attempts = 1).resolve(ID))
        assertEquals(DownloadResolver.INCOMPLETE, failed.reason)
        assertEquals(listOf(single.guid, brokenElsewhere.guid), asks)
        assertEquals(listOf(ReleaseKey.of(OTHER_INDEXER, brokenElsewhere.guid)), stored().tried)
    }

    /**
     * L399, L443: one indexer's download limit, and the other indexer out of reach (a 5xx) as its release is grabbed, in the
     * passes after the limit's too. The limit wins over the outage across the resolve, as within one pass: the download waits
     * as the limit, its facts kept, rather than backing off, which would ask the limited indexer again at every retry for an
     * hour. Nothing is counted, and the limited indexer is asked once.
     */
    @Test
    fun `one indexer's download limit and another indexer out of reach wait as the limit, the limited one asked once`() = runBlocking<Unit> {
        val elsewhere = Fake("hl-s02e04-other", "Harbor.Lights.S02E04.1080p.WEB.h264-WREN", listOf("o1.mkv" to 1_100_000_000L))
        refusing[INDEXER] = NewznabError.DownloadLimitReached("Download limit reached")
        refusing[OTHER_INDEXER] = NewznabError.Server("HTTP 503")
        row(single, elsewhere, edit = otherIndexer(elsewhere))
        val waiting = assertIs<DownloadResolver.Resolution.Waiting>(resolver().resolve(ID))
        assertEquals(DownloadHostRules.LIMIT_REACHED, waiting.reason)
        assertEquals(DownloadState.Waiting, stored().state)
        assertEquals(DownloadHostRules.LIMIT_REACHED, stored().reason)
        assertEquals(LimitWait(INDEXER, LimitWait.Kind.Download, NOW, null), DownloadColumns.limitWait(stored()))
        assertEquals(emptyList(), stored().tried)
        assertEquals(1, asks.count { it == single.guid }, asks.toString())
        assertEquals(emptyList(), grabbed)

        // The other indexer back while the limit lasts: its release is the download's.
        refusing.remove(OTHER_INDEXER)
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
        assertEquals(elsewhere.guid, ready.row.release?.guid)
    }

    /**
     * L399, L443: a grab refused with the indexer's API limit (Newznab's 429 or 500, an HTTP 429) is its limit too, as that
     * answer to a turn's search is: no try counted, the indexer's other releases not asked, another indexer's tried, and with
     * none left elsewhere the download waits as the limit, its end where the indexer said, the walk told so that its later
     * searches leave the indexer out. Its lines say the limit's kind, never what the indexer said.
     */
    @Test
    fun `a grab refused for the indexer's API limit counts no try and waits as its limit, the walk keeping the indexer out`() = runBlocking<Unit> {
        val five = (1..5).map { Fake("hl-api-$it", "Harbor.Lights.S02E04.720p.WEB.h264-GRP$it", listOf("a$it.mkv" to 800_000_000L)) }
        row(*five.toTypedArray())
        val memory = DownloadLimits()
        indexerFails = NewznabError.ApiLimitReached("Request limit reached for plover.indexer.invalid", Duration.ofHours(2))
        val from = LogBuffer.snapshot().lastOrNull()?.seq ?: 0L
        assertEquals(DownloadHostRules.LIMIT_REACHED, assertIs<DownloadResolver.Resolution.Waiting>(resolver(limits = memory).resolve(ID)).reason)
        assertEquals(DownloadState.Waiting, stored().state)
        assertEquals(emptyList(), stored().tried)
        val wait = LimitWait(INDEXER, LimitWait.Kind.Search, NOW, NOW + Duration.ofHours(2).toMillis())
        assertEquals(wait, DownloadColumns.limitWait(stored()))
        assertEquals(listOf(five[0].guid), asks, "the indexer's other releases aren't asked")
        assertEquals(mapOf(INDEXER to wait), memory.keptOut(listOf(indexer, other), NOW + 60_000))
        val lines = LogBuffer.snapshot().filter { it.seq > from }.map { it.message }
        assertTrue(lines.any { "download $ID" in it && "search limit" in it }, lines.toString())
        val secret = listOf("plover.indexer", "Request limit", indexer.name, "Harbor.Lights")
        assertEquals(emptyList(), lines.filter { line -> secret.any { it in line } })

        // With no Retry-After its end is the walk's guess; another indexer's release beside it is tried, and plays.
        reset()
        val elsewhere = Fake("hl-s02e04-other", "Harbor.Lights.S02E04.1080p.WEB.h264-WREN", listOf("o1.mkv" to 1_100_000_000L))
        refusing[INDEXER] = NewznabError.ApiLimitReached("Request limit reached")
        row(single, elsewhere, edit = otherIndexer(elsewhere))
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
        assertEquals(elsewhere.guid, ready.row.release?.guid)
        assertEquals(listOf(single.guid, elsewhere.guid), asks)
        assertEquals(listOf(ReleaseKey.of(OTHER_INDEXER, elsewhere.guid)), stored().tried)
    }

    /**
     * L443, for grabs: the walk keeps an indexer that refused a grab for its limit out of the later turns' grabs until the
     * limit's end (30 minutes for a download limit, which says none), so the season's next row asks it nothing: it takes
     * another indexer's release, or, with only the limited indexer's left to try, waits as that limit with no request.
     * Past its end the indexer is asked again, and Resume forgets it ([DownloadLimits.forget]).
     */
    @Test
    fun `the walk keeps an indexer that refused a grab for its limit out of the later rows' grabs until the limit's end`() = runBlocking<Unit> {
        val memory = DownloadLimits()
        val elsewhere = Fake("hl-s02e04-other", "Harbor.Lights.S02E04.1080p.WEB.h264-WREN", listOf("o1.mkv" to 1_100_000_000L))
        refusing[INDEXER] = NewznabError.DownloadLimitReached("Download limit reached")
        row(single, elsewhere, edit = otherIndexer(elsewhere))
        assertEquals(elsewhere.guid, assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver(limits = memory).resolve(ID)).row.release?.guid)
        assertEquals(listOf(single.guid, elsewhere.guid), asks)

        // The walk's next row: the limited indexer isn't asked.
        reset()
        refusing[INDEXER] = NewznabError.DownloadLimitReached("Download limit reached")
        row(single, elsewhere, edit = otherIndexer(elsewhere))
        assertEquals(elsewhere.guid, assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver(limits = memory).resolve(ID)).row.release?.guid)
        assertEquals(listOf(elsewhere.guid), asks)

        // Only the limited indexer's left to try: a wait for its limit, the one its refusal began, with no request.
        reset()
        row(single)
        assertEquals(DownloadHostRules.LIMIT_REACHED, assertIs<DownloadResolver.Resolution.Waiting>(resolver(limits = memory).resolve(ID)).reason)
        assertEquals(emptyList(), asks)
        assertEquals(emptyList(), stored().tried)
        assertEquals(LimitWait(INDEXER, LimitWait.Kind.Download, NOW, null), DownloadColumns.limitWait(stored()))

        // Resume forgets it at once; past its end it is asked again.
        assertEquals(setOf(INDEXER), memory.grabsKeptOut(listOf(indexer, other), NOW).keys)
        assertEquals(emptyMap(), DownloadLimits().apply { refused(indexer, LimitWait(INDEXER, LimitWait.Kind.Download, NOW)); forget() }.grabsKeptOut(listOf(indexer), NOW))
        assertEquals(emptyMap(), memory.grabsKeptOut(listOf(indexer, other), NOW + LimitWait.GUESS_MILLIS))
    }

    /**
     * L403, L431, L443: a limit never holds back a release whose NZB needs no request. A file chosen by hand in a pack, the
     * pack's NZB kept with its row, whose indexer the walk keeps out of grabs is picked from that NZB with no grab, and the
     * release listed after it isn't grabbed. A season pack with a copy, one that doesn't lead among them, is taken from it
     * likewise, whether the walk keeps its indexer out or the indexer refused this turn's grab of another release.
     */
    @Test
    fun `a release whose NZB needs no request is tried from it whatever limit its indexer is under`() = runBlocking<Unit> {
        val memory = DownloadLimits().apply { refused(indexer, LimitWait(INDEXER, LimitWait.Kind.Download, NOW)) }
        val elsewhere = Fake("hl-s02e04-other", "Harbor.Lights.S02E04.1080p.WEB.h264-WREN", listOf("o1.mkv" to 1_100_000_000L))
        val pick = DownloadPick(3, "Harbor.Lights.4_6.mp4", 900_000_004L, PreparedNzb.idFor(unnamedPack.nzb))
        row(unnamedPack, elsewhere) { otherIndexer(elsewhere)(it).copy(pick = DownloadColumns.text(pick)) }
        storage.keepGrab(ID, key(unnamedPack), unnamedPack.nzb, null)
        val chosen = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver(limits = memory).resolve(ID))
        assertEquals(unnamedPack.guid, chosen.row.release?.guid)
        assertEquals("Harbor.Lights.4_6.mp4", chosen.file.name)
        assertEquals(emptyList(), asks)
        assertEquals(listOf(unnamedPack.guid), opened.map { it.first })
        assertContentEquals(unnamedPack.nzb, storage.sources[ID]?.nzb, "the kept NZB is the one kept as the download's")
        assertEquals(listOf(key(unnamedPack)), stored().tried)

        // A season row's pack with a copy that doesn't lead, its indexer kept out by the walk: from the copy, nothing asked.
        reset()
        keptForSeason(pack, leads = false)
        row(pack, single) { it.withFacts(searched = true) }
        val copied = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver(limits = memory).resolve(ID))
        assertEquals(pack.guid, copied.row.release?.guid)
        assertEquals(emptyList(), asks)
        assertEquals(listOf(key(pack)), stored().tried)

        // Its indexer refusing this turn's grab of the release before it: the pack from its copy all the same, the limit
        // costing nothing more.
        reset()
        keptForSeason(pack, leads = false)
        refusing[INDEXER] = NewznabError.DownloadLimitReached("Download limit reached")
        row(single, pack) { it.withFacts(searched = true) }
        val after = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
        assertEquals(pack.guid, after.row.release?.guid)
        assertEquals(listOf(single.guid), asks)
        assertEquals(listOf(key(pack)), stored().tried)
    }

    /**
     * L399b, L431, L443: a season's rows as one walk takes them in turn, the walk's one memory of the indexers' limits between
     * them. S02E04, queued from its ⋮ with the indexer's single, has its grab refused for the indexer's download limit: it
     * waits as the limit, nothing counted. S02E05, a season row, takes its season's copy of the pack that leads, with no
     * request to that indexer, no search and nothing counted against the limit: it is Ready from the copy. S02E06, queued from
     * its ⋮ with only the limited indexer's release, waits as the same limit, the indexer asked nothing.
     */
    @Test
    fun `under one walk a limited indexer's download waits, the next its season's copy serves, and one needing only that indexer waits unasked`() = runBlocking<Unit> {
        val memory = DownloadLimits()
        val sixth = Fake("hl-s02e06-kestrel", "Harbor.Lights.S02E06.1080p.WEB.h264-KESTREL", listOf("e6.mkv" to 1_100_000_000L))
        keptForSeason(pack)
        row(single)
        episode(E5, 5) { it.withFacts() }
        episode(E6, 6, sixth)
        refusing[INDEXER] = NewznabError.DownloadLimitReached("Download limit reached")
        val limit = LimitWait(INDEXER, LimitWait.Kind.Download, NOW, null)

        assertEquals(DownloadHostRules.LIMIT_REACHED, assertIs<DownloadResolver.Resolution.Waiting>(resolver(limits = memory).resolve(ID)).reason)
        assertEquals(emptyList(), stored().tried)
        assertEquals(limit, DownloadColumns.limitWait(stored()))
        assertEquals(listOf(single.guid), asks)

        val fromCopy = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver(limits = memory).resolve(E5))
        assertEquals(pack.guid, fromCopy.row.release?.guid)
        assertEquals("Harbor.Lights.S02E05.1080p.WEB.h264-GRP.mkv", fromCopy.file.name)
        assertEquals(listOf(single.guid), asks, "the copy asks the indexer nothing")
        assertEquals(emptyList(), searched)
        assertEquals(listOf(key(pack)), stored(E5).tried)

        assertEquals(DownloadHostRules.LIMIT_REACHED, assertIs<DownloadResolver.Resolution.Waiting>(resolver(limits = memory).resolve(E6)).reason)
        assertEquals(DownloadState.Waiting, stored(E6).state)
        assertEquals(emptyList(), stored(E6).tried)
        assertEquals(limit, DownloadColumns.limitWait(stored(E6)), "the wait the refusal began")
        assertEquals(listOf(single.guid), asks, "the limited indexer asked nothing more")
        assertEquals(emptyList(), grabbed)
    }

    /**
     * L399b, L443: a season row behind an indexer's search limit, met by an earlier row's turn, searches the other indexers
     * alone and takes what they found, asking the limited one nothing; with nothing found there, it waits as that limit.
     */
    @Test
    fun `a season row behind an indexer's search limit searches the others alone and takes theirs, asking the limited one nothing`() = runBlocking<Unit> {
        val memory = DownloadLimits().apply { answered(indexer, null, NOW - 60_000) }
        val elsewhere = Fake("hl-s02e04-other", "Harbor.Lights.S02E04.1080p.WEB.h264-WREN", listOf("o1.mkv" to 1_100_000_000L))
        finds = listOf(elsewhere)
        listedBy[elsewhere.guid] = OTHER_INDEXER
        findReports = listOf(IndexerReport(other, "TVDB id"))
        row { it.withFacts() }
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver(limits = memory, searchIndexers = listOf(indexer, other)).resolve(ID))
        assertEquals(elsewhere.guid, ready.row.release?.guid)
        assertEquals(listOf(listOf(OTHER_INDEXER)), searchedAt, "the limited indexer left out of the search")
        assertEquals(listOf(elsewhere.guid), asks)
        assertEquals(listOf(ReleaseKey.of(OTHER_INDEXER, elsewhere.guid)), stored().tried)

        // Nothing found at the others: a wait for the limited indexer's search limit, its end the walk's guess.
        reset()
        findReports = listOf(IndexerReport(other, "TVDB id"))
        row { it.withFacts() }
        val waiting = assertIs<DownloadResolver.Resolution.Waiting>(resolver(limits = memory, searchIndexers = listOf(indexer, other)).resolve(ID))
        assertEquals(DownloadHostRules.LIMIT_REACHED, waiting.reason)
        assertEquals(listOf(listOf(OTHER_INDEXER)), searchedAt)
        assertEquals(LimitWait(INDEXER, LimitWait.Kind.Search, NOW - 60_000, null), DownloadColumns.limitWait(stored()))
        assertEquals(emptyList(), asks)
    }

    /**
     * L399b: a season row after another row's grab was refused for an indexer's download limit leaves that indexer out of
     * its search too, asking it nothing, so its releases, which couldn't be grabbed meanwhile, crowd none of the others' out
     * of those kept: it takes another indexer's release, and with nothing found elsewhere waits as that limit, unasked.
     */
    @Test
    fun `a season row behind an indexer's download limit searches the others alone and takes theirs, asking the limited one nothing`() = runBlocking<Unit> {
        val refusal = LimitWait(INDEXER, LimitWait.Kind.Download, NOW - 60_000, null)
        val memory = DownloadLimits().apply { refused(indexer, refusal) }
        val elsewhere = Fake("hl-s02e04-other", "Harbor.Lights.S02E04.1080p.WEB.h264-WREN", listOf("o1.mkv" to 1_100_000_000L))
        finds = listOf(elsewhere)
        listedBy[elsewhere.guid] = OTHER_INDEXER
        findReports = listOf(IndexerReport(other, "TVDB id"))
        row { it.withFacts() }
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver(limits = memory, searchIndexers = listOf(indexer, other)).resolve(ID))
        assertEquals(elsewhere.guid, ready.row.release?.guid)
        assertEquals(listOf(listOf(OTHER_INDEXER)), searchedAt, "the limited indexer left out of the search")
        assertEquals(listOf(elsewhere.guid), asks)

        // Nothing found at the others: a wait for the limited indexer's download limit, as it refused.
        reset()
        findReports = listOf(IndexerReport(other, "TVDB id"))
        row { it.withFacts() }
        val waiting = assertIs<DownloadResolver.Resolution.Waiting>(resolver(limits = memory, searchIndexers = listOf(indexer, other)).resolve(ID))
        assertEquals(DownloadHostRules.LIMIT_REACHED, waiting.reason)
        assertEquals(listOf(listOf(OTHER_INDEXER)), searchedAt)
        assertEquals(refusal, DownloadColumns.limitWait(stored()))
        assertEquals(emptyList(), asks)
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
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
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
            val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID), "for $failure")
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
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
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
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
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
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
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
                search = DownloadSearch(dao, { _, _, _ -> error("No search for a row without search facts") }, { listOf(indexer) }, { context(null) }, { storage.space(it) }),
                listings = listings,
            ).resolve(ID),
        )
        assertNull(storage.sources[ID])
        assertEquals(listOf(single.guid), opened.map { it.first })
    }

    @Test
    fun `the release its file came from goes on from the offset, another starts from 0 with the old file deleted`() = runBlocking<Unit> {
        // Try again after "Storage is full": no copy, the same release picked again.
        row(single) { it.resolvedAs(single, "a8f3c1.mkv", 1_200_000_000L).copy(safeOffset = 700_000_000L, relativePath = "downloads/$ID/v.mkv") }
        val same = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
        assertEquals(listOf(single.guid), grabbed)
        assertEquals(700_000_000L, same.row.safeOffset)
        assertEquals("downloads/$ID/v.mkv", same.row.relativePath)
        assertEquals(emptyList(), storage.deletedVideos)

        reset()
        row(single, pack) { it.resolvedAs(broken, "b71d0e.mkv", 4_000_000_000L).copy(safeOffset = 700_000_000L, relativePath = "downloads/$ID/v.mkv") }
        val other = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
        assertEquals(0, other.row.safeOffset)
        assertNull(other.row.relativePath)
        assertEquals(listOf(ID), storage.deletedVideos)
    }

    /**
     * L392: a season row's turn searches its episode by the facts its row keeps, and what it finds replaces the sheet's list,
     * written into the row with what the first needs and the facts as searched before anything is grabbed.
     */
    @Test
    fun `a season row's turn searches its episode and writes what it found before any grab`() = runBlocking<Unit> {
        finds = listOf(broken, single)
        row(unnamedPack) { it.withFacts() }
        var atGrab: Download? = null
        onGrab = { if (atGrab == null) atGrab = stored() }
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
        assertEquals(listOf(FACTS.request(2, 4)), searched)
        val first = assertNotNull(atGrab, "nothing was grabbed")
        assertEquals(listOf(broken.guid, single.guid), first.candidates.map { it.guid })
        assertEquals(broken.size, first.indexerSize)
        assertEquals(true, DownloadColumns.search(first)?.searched)
        assertEquals(listOf(broken.guid, single.guid), grabbed)
        assertEquals(single.guid, ready.row.release?.guid)
        // Said meanwhile, as Resolve's first step is.
        assertEquals(DownloadHostRules.FINDING_RELEASES, dao.reasons.first())
    }

    /** L392: the search's list is in the row before any grab, so a process that dies after it resolves from it, unsearched. */
    @Test
    fun `a process that dies after the turn's search resolves from what it found, with no second search`() = runBlocking<Unit> {
        finds = listOf(single)
        row { it.withFacts() }
        val grabbing = CompletableDeferred<Unit>()
        onGrab = {
            grabbing.complete(Unit)
            awaitCancellation()
        }
        val resolve = launch(Dispatchers.Default) { resolver().resolve(ID) }
        withTimeout(10_000) { grabbing.await() }
        resolve.cancelAndJoin()
        onGrab = {}
        // A new process.
        storage.packs = packs()
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
        assertEquals(single.guid, ready.row.release?.guid)
        assertEquals(1, searched.size, "searched once")
        assertEquals(listOf(single.guid), grabbed)
    }

    /**
     * Resolve's order (L392, L431): the row's own NZB; a file chosen by hand, which neither searches nor takes the season's
     * pack first; the season's pack that leads, from its copy; the turn's search where it hasn't searched yet; its releases.
     */
    @Test
    fun `a season row's turn opens its own NZB, else a pick's releases, else its season's pack, else searches, then its releases`() = runBlocking<Unit> {
        finds = listOf(broken)
        keptForSeason(pack)
        // Its own NZB, a resolve before having found its release.
        row(single) { it.withFacts().resolvedAs(passworded, "c90a4f.mkv", 1_300_000_000L) }
        storage.sources[ID] = DownloadStorage.Source(passworded.nzb, "s3cret-from-indexer")
        fakes[passworded.guid] = passworded
        assertEquals(passworded.guid, assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID)).row.release?.guid)
        assertEquals(emptyList(), grabbed)
        assertEquals(emptyList(), searched)

        // A file chosen by hand: its releases as chosen, with no search and the season's pack left alone.
        reset()
        keptForSeason(pack)
        row(single) { it.withFacts().copy(pick = DownloadColumns.text(DownloadPick(0, "a8f3c1.mkv", 1_200_000_000L, PreparedNzb.idFor(single.nzb)))) }
        assertEquals(single.guid, assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID)).row.release?.guid)
        assertEquals(listOf(single.guid), grabbed)
        assertEquals(emptyList(), searched)

        // The season's pack, from its copy: no grab and no search.
        reset()
        keptForSeason(pack)
        row(single) { it.withFacts() }
        val packed = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
        assertEquals(pack.guid, packed.row.release?.guid)
        assertEquals("Harbor.Lights.S02E04.1080p.WEB.h264-GRP.mkv", packed.file.name)
        assertEquals(emptyList(), grabbed)
        assertEquals(emptyList(), searched)
        assertEquals(listOf(key(pack)), stored().tried)

        // No copy: the search, whose list replaces the sheet's.
        reset()
        finds = listOf(passworded)
        row(single) { it.withFacts() }
        assertEquals(passworded.guid, assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID)).row.release?.guid)
        assertEquals(listOf(passworded.guid), grabbed)
        assertEquals(1, searched.size)

        // Searched already: its releases.
        reset()
        finds = listOf(passworded)
        row(single) { it.withFacts(searched = true) }
        assertEquals(single.guid, assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID)).row.release?.guid)
        assertEquals(emptyList(), searched)

        // An episode queued from its own ⋮ keeps its releases' order: no pack first, no search.
        reset()
        keptForSeason(pack)
        row(single)
        assertEquals(single.guid, assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID)).row.release?.guid)
        assertEquals(listOf(single.guid), grabbed)
        assertEquals(emptyList(), searched)
    }

    /**
     * L431: the season's pack goes first only where its share of the episode fits the room now and, ranked alone for
     * downloading with the episode's runtime, it isn't above Download size; else the turn searches as it would without it.
     */
    @Test
    fun `the season's pack isn't first above Download size, where its share doesn't fit, or where it doesn't lead`() = runBlocking<Unit> {
        val small = Fake("hl-s02e04-small", "Harbor.Lights.S02E04.480p.WEB.h264-WREN", listOf("s.mkv" to 500_000_000L))
        // About 1.4 GB an hour for a 44-minute episode, its share of the pack: above 1 GB an hour.
        finds = listOf(small)
        keptForSeason(pack)
        row(single) { it.withFacts().copy(runtimeMinutes = 44) }
        assertEquals(small.guid, assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver(cap = 1).resolve(ID)).row.release?.guid)
        assertEquals(listOf(small.guid), grabbed)
        assertEquals(1, searched.size)
        // Under 2 GB an hour it leads.
        reset()
        keptForSeason(pack)
        row(single) { it.withFacts().copy(runtimeMinutes = 44) }
        assertEquals(pack.guid, assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver(cap = 2).resolve(ID)).row.release?.guid)

        // Its share, about 1 GB, more than the 0.9 GB free.
        reset()
        finds = listOf(small)
        keptForSeason(pack)
        row(single) { it.withFacts() }
        storage.space = DownloadSpace(available = 900_000_000L + DownloadSpace.KEPT_FREE, used = 0, queued = 0)
        assertEquals(small.guid, assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID)).row.release?.guid)
        assertEquals(listOf(small.guid), grabbed)

        // A copy kept as not leading.
        reset()
        finds = listOf(small)
        keptForSeason(pack, leads = false)
        row(single) { it.withFacts() }
        assertEquals(small.guid, assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID)).row.release?.guid)
    }

    /**
     * L431: a season's pack that fails for its own sake (its articles gone) is one of the row's three tries, and the turn's
     * search runs then, the pack left out of what it finds; one that finds nothing else fails in the pack's words.
     */
    @Test
    fun `a season's pack that fails for its own sake moves on to the search, counted once and left out of what it finds`() = runBlocking<Unit> {
        val gonePack = Fake(
            "hl-s02-pack-gone", "Harbor.Lights.S02.1080p.WEB.h264-GRP", (1..6).map { "Harbor.Lights.S02E%02d.1080p.WEB.h264-GRP.mkv".format(it) to 1_000_000_000L + it },
            gone = true,
        )
        keptForSeason(gonePack)
        finds = listOf(gonePack, single)
        row { it.withFacts() }
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
        assertEquals(single.guid, ready.row.release?.guid)
        // The pack from its copy, no grab; the single grabbed.
        assertEquals(listOf(single.guid), grabbed)
        assertEquals(1, searched.size)
        assertEquals(listOf(key(gonePack), key(single)), stored().tried)
        assertEquals(listOf(single.guid), stored().candidates.map { it.guid })
        assertTrue(dao.reasons.contains("Trying release 2 of 2 · Fetching NZB"), dao.reasons.toString())

        // Nothing else found: it fails in the pack's words.
        reset()
        keptForSeason(gonePack)
        finds = listOf(gonePack)
        row { it.withFacts() }
        val failed = assertIs<DownloadResolver.Resolution.Failed>(resolver().resolve(ID))
        assertEquals(DownloadResolver.INCOMPLETE, failed.reason)
        assertEquals(listOf(key(gonePack)), stored().tried)
        assertEquals(emptyList(), grabbed)
    }

    /**
     * L340, L431: the season's pack tried first and cut off by a process death is counted as its attempt started, its NZB
     * kept for it. The next process's turn takes it up again first from what was kept, counted once, with no grab and no
     * search, as any attempt with no answer is taken up again.
     */
    @Test
    fun `a season's pack tried first and cut off by a process death is taken up again first, with no grab and no search`() = runBlocking<Unit> {
        keptForSeason(pack)
        finds = listOf(single)
        row(single) { it.withFacts() }
        val dying = CompletableDeferred<Pair<Map<String, Download>, Map<String, DownloadStorage.Grab>>>()
        onCheck = {
            dying.complete(dao.rows.value to storage.grabs.toMap())
            awaitCancellation()
        }
        val resolve = launch(Dispatchers.Default) { resolver().resolve(ID) }
        val (rows, grabs) = withTimeout(10_000) { dying.await() }
        resolve.cancelAndJoin()
        onCheck = {}
        // What a death mid-check leaves on disk: the pack counted, its NZB kept for its attempt.
        assertEquals(listOf(key(pack)), rows.getValue(ID).tried)
        dao.rows.value = rows
        storage.grabs.clear()
        storage.grabs += grabs
        storage.packs = packs()

        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
        assertEquals(pack.guid, ready.row.release?.guid)
        assertEquals("Harbor.Lights.S02E04.1080p.WEB.h264-GRP.mkv", ready.file.name)
        assertEquals(emptyList(), grabbed)
        assertEquals(emptyList(), searched)
        assertEquals(listOf(key(pack)), stored().tried)
    }

    /**
     * L340, L431: a season row whose release's attempt had no answer, its NZB kept for it (cut short by a process death or a
     * pause after its turn's search), takes that release up again first, from what was kept, with no grab, before a copy of
     * its season's pack that leads: tried first, the pack would count that release as tried with no answer and write over
     * its NZB, and with one try in all fail the row "No other release to try".
     */
    @Test
    fun `a release whose attempt had no answer goes on from its kept NZB before its season's pack`() = runBlocking<Unit> {
        keptForSeason(pack)
        row(single, tried = listOf(single)) { it.withFacts(searched = true) }
        storage.grabs[ID] = DownloadStorage.Grab(key(single), DownloadStorage.Source(single.nzb, null))
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver(attempts = 1).resolve(ID))
        assertEquals(single.guid, ready.row.release?.guid)
        assertEquals(emptyList(), grabbed)
        assertEquals(emptyList(), searched)
        assertEquals(listOf(key(single)), stored().tried)

        // Three tries: the same, the pack's copy left for the season's other rows.
        reset()
        keptForSeason(pack)
        row(single, tried = listOf(single)) { it.withFacts(searched = true) }
        storage.grabs[ID] = DownloadStorage.Grab(key(single), DownloadStorage.Source(single.nzb, null))
        assertEquals(single.guid, assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID)).row.release?.guid)
        assertEquals(emptyList(), grabbed)
        assertEquals(listOf(key(single)), stored().tried)
    }

    /**
     * L398, L443: a turn whose search finds nothing fails "No playable release found"; one whose indexer answered its limit,
     * with nothing found elsewhere, waits as the limit, its wait kept; the indexers out of reach are backed off from, nothing
     * counted and the search still to come; found and none fits, it won't fit; the volume away, it waits for storage.
     */
    @Test
    fun `a turn's search that finds nothing fails, one at an indexer's limit waits, and indexers out of reach are backed off from`() = runBlocking<Unit> {
        row(single) { it.withFacts() }
        val none = assertIs<DownloadResolver.Resolution.Failed>(resolver().resolve(ID))
        assertEquals(DownloadSheetModel.NO_RELEASE, none.reason)
        assertEquals(DownloadState.Failed, stored().state)
        assertEquals("No playable release found", stored().reason)
        assertEquals(emptyList(), grabbed)

        reset()
        findReports = listOf(IndexerReport(indexer, null, error = NewznabError.ApiLimitReached("Request limit reached", java.time.Duration.ofMinutes(90))))
        row(single) { it.withFacts() }
        val limited = assertIs<DownloadResolver.Resolution.Waiting>(resolver().resolve(ID))
        assertEquals(DownloadHostRules.LIMIT_REACHED, limited.reason)
        assertEquals(DownloadState.Waiting, stored().state)
        assertEquals(DownloadHostRules.LIMIT_REACHED, stored().reason)
        assertEquals(LimitWait(INDEXER, LimitWait.Kind.Search, NOW, NOW + 90 * 60_000L), DownloadColumns.limitWait(stored()))
        assertEquals(emptyList(), stored().tried)

        reset()
        findReports = listOf(IndexerReport(indexer, null, error = NewznabError.Server("HTTP 503")))
        row(single) { it.withFacts() }
        val out = assertIs<DownloadResolver.Resolution.Unreachable>(resolver().resolve(ID))
        assertEquals(DownloadHostRules.CANT_REACH_INDEXER, out.words)
        assertEquals(DownloadState.Resolving, stored().state)
        assertEquals(DownloadHostRules.CANT_REACH_INDEXER, stored().reason)
        assertEquals(emptyList(), stored().tried)
        assertEquals(false, DownloadColumns.search(stored())?.searched, "searched again once the wait is over")

        reset()
        finds = listOf(broken)
        storage.space = DownloadSpace(available = 1_000_000_000L + DownloadSpace.KEPT_FREE, used = 0, queued = 0)
        row(single) { it.withFacts() }
        assertEquals(DownloadResolver.WONT_FIT, assertIs<DownloadResolver.Resolution.Failed>(resolver().resolve(ID)).reason)
        assertEquals(emptyList(), grabbed)

        reset()
        finds = listOf(single)
        storage.space = null
        row(single) { it.withFacts() }
        assertEquals(DownloadHostRules.STORAGE_UNAVAILABLE, assertIs<DownloadResolver.Resolution.Waiting>(resolver().resolve(ID)).reason)
        assertEquals(emptyList(), grabbed)
    }

    /**
     * L401: a release that passed every step whose exact size won't fit is passed over, counted, its grab dropped and nothing
     * of it written into the row, and the next is tried within the three tries; the row fails "Won't fit" only once none
     * that fits is left. A row resolved before keeps its release, its file and what it wrote.
     */
    @Test
    fun `a release that won't fit is passed over, the row as it was, and the next tried, Won't fit once none that fits is left`() = runBlocking<Unit> {
        val smaller = Fake("hl-s02e04-smaller", "Harbor.Lights.S02E04.720p.WEB.h264-WREN", listOf("e9.mkv" to 900_000_000L))
        row(single, smaller)
        storage.space = DownloadSpace(available = 1_000_000_000L + DownloadSpace.KEPT_FREE, used = 0, queued = 0)
        val written = mutableListOf<String?>()
        dao.onUpdate = { written += it.release?.guid }
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
        assertEquals(smaller.guid, ready.row.release?.guid)
        assertEquals(listOf(single.guid, smaller.guid), grabbed)
        assertEquals(listOf(key(single), key(smaller)), stored().tried)
        assertFalse(single.guid in written, "the release passed over was written into the row")
        assertNull(storage.grabs[ID])
        // Its probe's entry went as for any release.
        assertEquals(0, store.storedFiles().count)

        // A row resolved before (Try again after "Storage is full"): every release too big; its own kept as it was.
        reset()
        val big = (1..4).map { Fake("hl-s02e04-big-$it", "Harbor.Lights.S02E04.2160p.WEB.h265-GRP$it", listOf("b$it.mkv" to 2_000_000_000L + it)) }
        row(*big.toTypedArray()) { it.resolvedAs(passworded, "c90a4f.mkv", 1_300_000_000L).copy(safeOffset = 700_000_000L, relativePath = "downloads/$ID/v.mkv") }
        storage.space = DownloadSpace(available = 1_500_000_000L + DownloadSpace.KEPT_FREE, used = 0, queued = 0)
        val failed = assertIs<DownloadResolver.Resolution.Failed>(resolver().resolve(ID))
        assertEquals(DownloadResolver.WONT_FIT, failed.reason)
        assertEquals(big.take(3).map { it.guid }, grabbed)
        val kept = stored()
        assertEquals(passworded.guid, kept.release?.guid)
        assertEquals("c90a4f.mkv", kept.fileName)
        assertEquals(1_300_000_000L, kept.size)
        assertEquals(700_000_000L, kept.safeOffset)
        assertEquals("downloads/$ID/v.mkv", kept.relativePath)
        assertEquals(emptyList(), storage.deletedVideos)
    }

    /**
     * L402: a pack whose files name no episode, from which the user chose the episode's file while streaming it, has that
     * file picked again with no question, as a play's replay picks it (Replay.picker).
     */
    @Test
    fun `a file chosen while streaming the same pack is picked from it with no question`() = runBlocking<Unit> {
        row(unnamedPack)
        lastPlayed[WatchKey.episode(SHOW, 2, 4)] = RememberedRelease(
            INDEXER, unnamedPack.guid, unnamedPack.title, null, fileIndex = 3, nzbId = PreparedNzb.idFor(unnamedPack.nzb), fileName = "Harbor.Lights.4_6.mp4",
            fileSize = 900_000_004L,
        )
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
        assertEquals("Harbor.Lights.4_6.mp4", ready.file.name)
        assertEquals(listOf(unnamedPack.guid), grabbed)
        assertEquals(listOf(key(unnamedPack)), probed)
        assertEquals(listOf(WatchKey.episode(SHOW, 2, 4)), lastAsked)
        // Only that file's volume was checked, after the pack was opened.
        assertEquals(listOf("${unnamedPack.guid}/Harbor.Lights.4_6.mp4@fake"), checked)
    }

    /**
     * L403: a file chosen by hand in a pack (Choose file) is picked from the pack's NZB kept with its row, with no question
     * and no grab: its volumes checked once it is picked, and probed, as any release's file is. The pick wins over the file
     * that played the episode last.
     */
    @Test
    fun `a file chosen by hand in a pack is picked from the NZB kept with its row, with no question and no grab`() = runBlocking<Unit> {
        row(unnamedPack, single) { it.copy(pick = DownloadColumns.text(DownloadPick(3, "Harbor.Lights.4_6.mp4", 900_000_004L, PreparedNzb.idFor(unnamedPack.nzb)))) }
        storage.keepGrab(ID, key(unnamedPack), unnamedPack.nzb, null)
        lastPlayed[WatchKey.episode(SHOW, 2, 4)] = RememberedRelease(
            INDEXER, unnamedPack.guid, unnamedPack.title, null, fileIndex = 1, nzbId = PreparedNzb.idFor(unnamedPack.nzb), fileName = "Harbor.Lights.2-6.mp4",
            fileSize = 900_000_002L,
        )
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
        assertEquals("Harbor.Lights.4_6.mp4", ready.file.name)
        assertEquals(emptyList(), grabbed)
        assertEquals(listOf(unnamedPack.guid), opened.map { it.first })
        assertEquals(listOf(key(unnamedPack)), probed)
        // Only the chosen file's volume, once the pack was open.
        assertEquals(listOf("${unnamedPack.guid}/Harbor.Lights.4_6.mp4@fake"), checked)
        assertEquals(unnamedPack.guid, stored().release?.guid)
        assertEquals("Harbor.Lights.4_6.mp4", stored().fileName)
    }

    /**
     * L403 with DN1's L339: a chosen file no longer in its pack's NZB (a pack grabbed again, listed otherwise) falls back to
     * the episode's file there; with none named for the episode, that attempt fails, and the next release is tried.
     */
    @Test
    fun `a chosen file no longer in its pack falls back to the episode's file, else that attempt fails`() = runBlocking<Unit> {
        row(pack) { it.copy(pick = DownloadColumns.text(DownloadPick(9, "Harbor.Lights.S02E04.REPACK.mkv", 1_000_000_009L, PreparedNzb.idFor(pack.nzb)))) }
        storage.keepGrab(ID, key(pack), pack.nzb, null)
        assertEquals("Harbor.Lights.S02E04.1080p.WEB.h264-GRP.mkv", assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID)).file.name)

        reset()
        row(unnamedPack, single) { it.copy(pick = DownloadColumns.text(DownloadPick(9, "Harbor.Lights.9_6.mp4", 900_000_009L, PreparedNzb.idFor(unnamedPack.nzb)))) }
        storage.keepGrab(ID, key(unnamedPack), unnamedPack.nzb, null)
        assertEquals("a8f3c1.mkv", assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID)).file.name)
        assertEquals(listOf(single.guid), grabbed)
        assertEquals(listOf(key(single)), probed)
    }

    /** [fake]'s NZB as Import opened it: its id, the SHA-256 of its bytes. */
    private fun nzbIdOf(fake: Fake) = PreparedNzb.idFor(fake.nzb)

    /** The key of Import's own release of [fake]'s NZB: its rows' one candidate, and what its copy is kept under (L423). */
    private fun importKey(fake: Fake) = ReleaseKey.of(IMPORTED, nzbIdOf(fake))

    /**
     * File [name] of [fake]'s NZB, opened on Import and queued as enqueueImport queues it (L423): a Release row [id] whose one
     * candidate is Import's release of the NZB and whose pick is the file at [index] (Import's place for it) of [size]
     * bytes; with [copy], the NZB kept in `.packs/` with the password typed for it, [password].
     */
    private suspend fun imported(
        fake: Fake,
        name: String,
        id: String = ID,
        index: Int = fake.files.indexOfFirst { it.first == name },
        size: Long = fake.files.toMap().getValue(name),
        copy: Boolean = true,
        password: String? = TYPED,
    ): Download {
        fakes[fake.guid] = fake
        val nzbId = nzbIdOf(fake)
        val target = DownloadTarget.release(nzbId, index, name, fake.title)
        val candidate = DownloadCandidate(IMPORTED, nzbId, fake.title, size = size)
        val row = Download(
            id = id, watchKey = target.watchKey, kind = DownloadKind.Release, title = target.title, subtitle = target.subtitle, position = 0,
            candidates = listOf(candidate), indexerSize = size, pick = DownloadColumns.text(DownloadPick(index, name, size, nzbId)), createdAt = 0, updatedAt = 0,
        )
        dao.insert(row)
        if (copy) storage.packs.write(DownloadNzbs.Meta.imported(candidate), fake.nzb, password)
        return row
    }

    /** The grab of an imported file's resolve: none may be asked, as no indexer has its NZB. */
    private val unasked: suspend (IndexerConfig, NewznabItem) -> NzbDownload = { from, _ -> throw AssertionError("a grab asked of ${from.id}") }

    /** As the runner's Try again puts a failed row back: queued, its tries and reason gone, its candidate and pick kept. */
    private suspend fun triedAgain() = dao.update(stored().copy(state = DownloadState.Queued, reason = null, tried = emptyList()))

    /**
     * L423, L457: a file of an NZB opened on Import resolves from the NZB's copy in `.packs/`, its grab, as no indexer has
     * it: nothing asked of an indexer, nothing searched. Then the full path, as any release's: the file's volumes checked once
     * the NZB is open, prepared with the password typed on Import, and probed. Its release is Import's, and the NZB and the
     * password are its own until it is written, as any row's; the copy stays for the NZB's other files.
     */
    @Test
    fun `an imported file resolves from its NZB's copy with no indexer asked, its volumes checked, opened with its password and probed`() = runBlocking<Unit> {
        imported(pack, E03)
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver(fetchNzb = unasked).resolve(ID))
        assertEquals(E03, ready.file.name)
        assertEquals(1_000_000_003L, ready.file.size)
        assertEquals(emptyList(), asks)
        assertEquals<List<Pair<String, String?>>>(listOf(pack.guid to TYPED), opened)
        assertEquals(listOf("${pack.guid}/$E03@fake"), checked)
        assertEquals(listOf(importKey(pack)), probed)
        val kept = stored()
        assertEquals(IMPORTED, kept.release?.indexerId)
        assertEquals(nzbIdOf(pack), kept.release?.guid)
        assertEquals(E03, kept.fileName)
        assertEquals(1_000_000_003L, kept.size)
        assertEquals(listOf(importKey(pack)), kept.tried)
        assertContentEquals(pack.nzb, storage.sources[ID]?.nzb)
        assertEquals(TYPED, storage.sources[ID]?.password)
        assertNotNull(storage.packs.find(importKey(pack)))
        assertEquals(0, storage.packWrites)
    }

    /**
     * L454: nothing is searched for an imported file, so its row says "Opening…" from its start and through its NZB's fetch
     * and open, which say nothing else; then the play's words for the check and the probe. Its volumes are checked once the
     * NZB is open, a pack's as an NZB of one file's: nothing tells the one from the other before.
     */
    @Test
    fun `an imported file says Opening through its NZB's fetch and open, then the check's and the probe's words`() = runBlocking<Unit> {
        listOf(pack to E03, single to "a8f3c1.mkv").forEach { (fake, name) ->
            reset()
            imported(fake, name)
            assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver(fetchNzb = unasked).resolve(ID), name)
            assertEquals<List<String?>>(listOf("Opening…", "Checking availability", "Checking audio and video"), dao.reasons, name)
            assertTrue(dao.states.all { it == DownloadState.Resolving }, name)
        }
    }

    /**
     * L457: an imported file is the one chosen on Import, picked in its NZB by its name and size wherever the NZB lists it now
     * (Import's place for it may hold another here). One no longer there by both fails its attempt, and so the download: never
     * another file of the NZB, as the largest video would be, written under the chosen file's watch key. Nothing else is
     * tried, as its NZB is its one release.
     */
    @Test
    fun `an imported file is picked by its name and size, and one missing from its NZB fails, never taking the largest`() = runBlocking<Unit> {
        imported(pack, E03, index = 0)
        assertEquals(E03, assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver(fetchNzb = unasked).resolve(ID)).file.name)

        // Not in the pack; there by its name at another size; in an NZB of one video, another name.
        listOf(
            Triple(pack, "Harbor.Lights.S02E09.1080p.WEB.h264-GRP.mkv", 1_000_000_009L),
            Triple(pack, E03, 1_000_000_033L),
            Triple(single, "b5e2d7.mkv", 1_200_000_000L),
        ).forEach { (fake, name, size) ->
            reset()
            imported(fake, name, index = 2, size = size)
            val failed = assertIs<DownloadResolver.Resolution.Failed>(resolver(fetchNzb = unasked).resolve(ID), "$name of $size")
            assertEquals("Couldn't open the release", failed.reason, "$name of $size")
            assertEquals(DownloadState.Failed, stored().state, "$name of $size")
            assertEquals<List<Pair<String, String?>>>(listOf(fake.guid to TYPED), opened, "$name of $size")
            assertEquals(emptyList(), checked, "$name of $size")
            assertEquals(emptyList(), probed, "$name of $size")
            assertNull(stored().release, "$name of $size")
            assertEquals(listOf(importKey(fake)), stored().tried, "$name of $size")
        }
    }

    /**
     * C3 of DN2: an imported file whose attempt failed for its release (the probe couldn't read it) is tried again from its
     * NZB's copy on Try again: the NZB kept for the attempt went with its answer, and no indexer has it.
     */
    @Test
    fun `a failed imported file tried again resolves from its NZB's copy again`() = runBlocking<Unit> {
        imported(pack, E03)
        probeAnswer = { ProbeResult.Failed(UsenetError.Timeout("No track list within 20 s")) }
        assertIs<DownloadResolver.Resolution.Failed>(resolver(fetchNzb = unasked).resolve(ID))
        assertNull(storage.grabs[ID])

        triedAgain()
        probeAnswer = { null }
        assertEquals(E03, assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver(fetchNzb = unasked).resolve(ID)).file.name)
        assertEquals(emptyList(), asks)
        assertEquals<List<Pair<String, String?>>>(listOf(pack.guid to TYPED, pack.guid to TYPED), opened)
        assertEquals(listOf(importKey(pack)), stored().tried)
    }

    /**
     * L457, L469: an imported file whose NZB's copy is gone (it went with the NZB's last download) has nothing to open, and no
     * indexer has it, so none is asked. It fails in Import's words for an NZB no longer kept, its one try counted once; Try
     * again fails so again.
     */
    @Test
    fun `an imported file whose NZB's copy is gone fails in Import's words, counted once, with no indexer asked`() = runBlocking<Unit> {
        imported(pack, E03, copy = false)
        repeat(2) { time ->
            if (time > 0) triedAgain()
            val failed = assertIs<DownloadResolver.Resolution.Failed>(resolver(fetchNzb = unasked).resolve(ID), "resolve ${time + 1}")
            assertEquals(IMPORT_GONE, failed.reason, "resolve ${time + 1}")
            assertEquals(DownloadState.Failed, stored().state, "resolve ${time + 1}")
            assertEquals(IMPORT_GONE, stored().reason, "resolve ${time + 1}")
            assertEquals(listOf(importKey(pack)), stored().tried, "resolve ${time + 1}")
        }
        assertEquals(emptyList(), asks)
        assertEquals(emptyList(), opened)
    }

    /**
     * L457: the memory of releases found missing never skips or records an imported NZB's release. Remembered as missing on
     * the provider in use, it is tried all the same; found whole, or found missing as it is checked, nothing of it is
     * remembered or forgotten.
     */
    @Test
    fun `an imported file remembered as missing is still tried, and nothing of it is remembered`() = runBlocking<Unit> {
        val provider = ProviderConfig("p-wren", "", "reader.wren.invalid", 563, "", "", useTls = true)
        val table = TriedTable(NOW)
        val remembered = MissingReleases(table, inUse = { listOf(provider) }, checkedInUse = { listOf(provider) }, now = { NOW })
        table.record(IMPORTED, nzbIdOf(pack), listOf(ReleaseChecks.provider(provider.id, provider.host, provider.port)), incomplete = false)
        val before = table.entries.toMap()
        imported(pack, E03)
        assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver(remembered = remembered, fetchNzb = unasked).resolve(ID))
        assertEquals(before, table.entries)

        // Its one file found missing on the provider as it is checked: nothing remembered of it either.
        val gone = Fake("qm-s01e02-wren", "Quiet.Meridian.S01E02.1080p.WEB.h264-WREN", listOf("Quiet.Meridian.S01E02.1080p.WEB.h264-WREN.mkv" to 1_300_000_000L), gone = true)
        reset()
        imported(gone, "Quiet.Meridian.S01E02.1080p.WEB.h264-WREN.mkv")
        val failed = assertIs<DownloadResolver.Resolution.Failed>(resolver(remembered = remembered, fetchNzb = unasked).resolve(ID))
        assertEquals("Release incomplete", failed.reason)
        assertEquals(before, table.entries)
    }

    /**
     * L457: an imported NZB's next file in this process takes the prepare held from the one before, as a season pack's next
     * row does: one prepare of the NZB in all, each file from its copy. Resolve writes no copy of it: the import wrote it.
     */
    @Test
    fun `an imported NZB's second file takes the prepare held from its first, one prepare in all`() = runBlocking<Unit> {
        imported(pack, E02, id = E1)
        imported(pack, E03, id = E2)
        val resolver = resolver(fetchNzb = unasked)
        assertEquals(E02, assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver.resolve(E1)).file.name)
        dao.setState(E1, DownloadState.Done, null, NOW)
        assertEquals(E03, assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver.resolve(E2)).file.name)
        assertEquals<List<Pair<String, String?>>>(listOf(pack.guid to TYPED), opened)
        assertEquals(listOf("${pack.guid}/$E02@fake", "${pack.guid}/$E03@fake"), checked)
        assertEquals(emptyList(), asks)
        assertEquals(0, storage.packWrites)
    }

    /**
     * An imported file's lines, Resolve's and its pipeline's, name the download by its id and its release by its place: never
     * the file, the NZB's title or id, or the password typed for it. Here a file missing from its NZB, whose failure's own
     * words name it.
     */
    @Test
    fun `an imported file's lines never name the file, its NZB or its password`() = runBlocking<Unit> {
        val from = LogBuffer.snapshot().lastOrNull()?.seq ?: 0L
        imported(pack, "Harbor.Lights.S02E09.1080p.WEB.h264-GRP.mkv", index = 8, size = 1_000_000_009L)
        assertIs<DownloadResolver.Resolution.Failed>(resolver(fetchNzb = unasked).resolve(ID))
        val lines = LogBuffer.snapshot().filter { it.seq > from }.map { it.message }
        val secret = listOf("S02E09", "Harbor", pack.title, nzbIdOf(pack), TYPED)
        assertEquals(emptyList(), lines.filter { line -> secret.any { it in line } })
        assertTrue(lines.any { "play_pipeline: download $ID, release 1 failed" in it }, lines.toString())
    }

    /** The turn's lines, the pack's attempt and a release passed over among them, name the row by its id: no release, key or title. */
    @Test
    fun `a turn's lines name the row by its id, never a release, a key or the show`() = runBlocking<Unit> {
        val gonePack = Fake(
            "hl-s02-pack-plover", "Harbor.Lights.S02.1080p.WEB.h264-PLOVER", (1..6).map { "Harbor.Lights.S02E%02d.1080p.WEB.h264-PLOVER.mkv".format(it) to 1_000_000_000L + it },
            gone = true,
        )
        val tooBig = Fake("hl-s02e04-tern", "Harbor.Lights.S02E04.2160p.WEB.h265-TERN", listOf("t.mkv" to 1_200_000_000L))
        val fits = Fake("hl-s02e04-heron", "Harbor.Lights.S02E04.720p.WEB.h264-HERON", listOf("h.mkv" to 400_000_000L))
        keptForSeason(gonePack)
        finds = listOf(tooBig, fits)
        // Listed smaller than its file: found to fit, and too big once its file's own size is known.
        listed[tooBig.guid] = 900_000_000L
        storage.space = DownloadSpace(available = 1_100_000_000L + DownloadSpace.KEPT_FREE, used = 0, queued = 0)
        row { it.withFacts() }
        val from = LogBuffer.snapshot().lastOrNull()?.seq ?: 0L
        assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID))
        assertEquals(listOf(key(gonePack), key(tooBig), key(fits)), stored().tried)
        val lines = LogBuffer.snapshot().filter { it.seq > from && (it.tag == "DownloadResolver" || it.tag == "DownloadSearch") }.map { it.message }
        assertTrue(lines.any { "download $ID" in it && "pack" in it }, lines.toString())
        assertTrue(lines.any { "download $ID" in it && "passed over" in it }, lines.toString())
        val secret = listOf(gonePack, tooBig, fits).flatMap { listOf(it.title, it.guid, key(it)) } + listOf("Harbor Lights", "PLOVER", "TERN", "HERON")
        assertEquals(emptyList(), lines.filter { line -> secret.any { it in line } })
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

    /**
     * L460: a torrent's release is prepared by the service accounts on, in the user's order, its file picked with no
     * question, and opened and probed as a play's is, with no wait for a Usenet provider (none is usable here). The listing
     * it was prepared as is kept in the row's folder before the row takes the release (L420): its release and file by the
     * torrent's content id, its source the torrent's; no NZB kept and no grab.
     */
    @Test
    fun `a torrent is prepared on the accounts on with no provider wait, its listing's copy kept before the row takes it`() = runBlocking<Unit> {
        // Not on the first account any more, on the second.
        serviceB.cached[goodTorrent.hash] = goodTorrent.files
        rowOf(goodTorrent.candidate)
        dao.onUpdate = { row -> row.release?.let { storage.events += "row takes ${it.guid}" } }
        val ready = assertIs<DownloadResolver.Resolution.Ready.Service>(resolver(providers = false, on = listOf(ACCOUNT_A, ACCOUNT_B)).resolve(ID))
        assertEquals(listOf(goodTorrent.hash), serviceA.prepares)
        assertEquals(listOf(goodTorrent.hash), serviceB.prepares)
        assertEquals(ACCOUNT_B, ready.entry.account)
        assertEquals(goodTorrent.episode(), ready.file)
        assertEquals(goodTorrent.episode(), ready.entry.prepared.picked)
        // The copy first, then the row.
        assertEquals(listOf("keep listing $ID", "row takes ${goodTorrent.key}"), storage.events.filter { it.startsWith("keep listing") || it.startsWith("row takes") })
        assertEquals(ready.entry, storage.listings[ID])
        val kept = stored()
        assertEquals(DownloadState.Resolving, kept.state)
        assertEquals(goodTorrent.key, kept.release?.guid)
        assertEquals(goodTorrent.key, kept.release?.nzbId)
        assertEquals(ReleaseFacts.TORRENT, kept.release?.source)
        assertEquals(goodTorrent.episode().name, kept.fileName)
        assertEquals(goodTorrent.episode().size, kept.size)
        assertEquals(goodTorrent.episode().index, kept.release?.fileIndex)
        assertTrue(kept.oneVideo, "the torrent's one video")
        assertNotNull(kept.tracks)
        assertEquals(listOf(goodTorrent.key), kept.tried)
        assertEquals(listOf(goodTorrent.key), probed)
        assertEquals(emptyMap(), storage.sources)
        assertEquals(emptyMap(), storage.grabs)
        assertEquals(emptyList(), grabbed)
        assertEquals(emptyList(), checked)
        // Its steps as the play sheet names a torrent's, never Usenet's.
        assertTrue("Preparing" in dao.reasons, dao.reasons.toString())
        assertFalse("Fetching NZB" in dao.reasons, dao.reasons.toString())
    }

    /**
     * L460: a pack's file is the episode's, picked from the service's listing before anything is added (L179); a pack that
     * names no file for the episode fails its attempt there, with nothing added to the account, and the next is tried.
     */
    @Test
    fun `a pack torrent picks the episode's file, and one that names none fails its attempt with nothing added`() = runBlocking<Unit> {
        serviceA.cached[unnamedTorrent.hash] = unnamedTorrent.files
        serviceA.cached[packTorrent.hash] = packTorrent.files
        rowOf(unnamedTorrent.candidate, packTorrent.candidate)
        val ready = assertIs<DownloadResolver.Resolution.Ready.Service>(resolver().resolve(ID))
        assertEquals("Harbor.Lights.S02E04.1080p.WEB-DL.AAC2.0.H.264-PACK.mkv", ready.file.name)
        assertEquals(listOf(unnamedTorrent.hash, packTorrent.hash), serviceA.prepares)
        assertEquals(listOf(packTorrent.hash), serviceA.added)
        assertEquals(listOf(unnamedTorrent.key, packTorrent.key), stored().tried)
        assertFalse(stored().oneVideo, "one of the pack's videos")
        assertEquals(listOf(packTorrent.key), probed)
    }

    /**
     * L460, L461: the service's own refusals are no answer about the release, and count no try: its outage on every account
     * the torrent plays from backs off as an indexer's does, in the debrid words; a refused key waits; a limit is handed to
     * the runner with its account and when its gate opens (an hour where the answer named no wait), which waits it out in
     * place or has the row wait. An outage on one account alone passes the release on to the next.
     */
    @Test
    fun `a service's outage backs off and its refused key and limit wait, none counted`() = runBlocking<Unit> {
        serviceA.cached[goodTorrent.hash] = goodTorrent.files
        serviceA.failsFor[goodTorrent.hash] = ServiceError.Down
        rowOf(goodTorrent.candidate)
        val out = assertIs<DownloadResolver.Resolution.Unreachable>(resolver().resolve(ID))
        assertEquals(DownloadHostRules.CANT_REACH_SERVICE, out.words)
        assertEquals(DownloadState.Resolving, stored().state)
        assertEquals(DownloadHostRules.CANT_REACH_SERVICE, stored().reason)
        assertEquals(emptyList(), stored().tried)

        reset()
        serviceA.cached[goodTorrent.hash] = goodTorrent.files
        serviceA.failsFor[goodTorrent.hash] = ServiceError.KeyRefused
        rowOf(goodTorrent.candidate)
        assertEquals(DownloadResolver.Resolution.Waiting(DownloadHostRules.CHECK_SERVICE), resolver().resolve(ID))
        assertEquals(DownloadState.Waiting, stored().state)
        assertEquals(DownloadHostRules.CHECK_SERVICE, stored().reason)
        assertEquals(emptyList(), stored().tried)

        listOf(Duration.ofMinutes(30) to 30 * 60_000L, null to 60 * 60_000L).forEach { (wait, left) ->
            reset()
            serviceA.cached[goodTorrent.hash] = goodTorrent.files
            serviceA.failsFor[goodTorrent.hash] = ServiceError.Limit(wait)
            rowOf(goodTorrent.candidate)
            assertEquals(DownloadResolver.Resolution.Limited(ACCOUNT_A, NOW + left), resolver().resolve(ID), "$wait")
            // The runner says how it waits: in place, or as the row's wait.
            assertEquals(DownloadState.Resolving, stored().state, "$wait")
            assertEquals(emptyList(), stored().tried, "$wait")
        }

        // Out of reach on the first account alone: the second prepares it, and it counts.
        reset()
        serviceA.cached[goodTorrent.hash] = goodTorrent.files
        serviceA.failsFor[goodTorrent.hash] = ServiceError.Down
        serviceB.cached[goodTorrent.hash] = goodTorrent.files
        rowOf(goodTorrent.candidate)
        assertEquals(ACCOUNT_B, assertIs<DownloadResolver.Resolution.Ready.Service>(resolver(on = listOf(ACCOUNT_A, ACCOUNT_B)).resolve(ID)).entry.account)
        assertEquals(listOf(goodTorrent.key), stored().tried)
    }

    /**
     * L460: what fails the release on its accounts is the release's, counted, the row failing in the play sheet's words
     * where they name no service, never "Couldn't open the release": not cached any more, taken down, refused for the
     * plan, and no working link to its file. What its account failed is remembered as a play's is (L174): the torrent is
     * passed over with no call by the next resolve that would try it there, and nothing counted.
     */
    @Test
    fun `a torrent's own failures are counted and said in the play sheet's words`() = runBlocking<Unit> {
        val io = ProbeResult.Failed(UsenetError.Network(null), null, java.io.IOException("the CDN's answer broke off"))
        listOf<Pair<(FakeService) -> Unit, String>>(
            { s: FakeService -> s.cached -= goodTorrent.hash } to "This release is no longer cached",
            { s: FakeService -> s.failsFor[goodTorrent.hash] = ServiceError.Removed } to "Removed by the debrid service",
            { s: FakeService -> s.failsFor[goodTorrent.hash] = ServiceError.Refused(tooLarge = true) } to "Too large for your debrid plan",
            { _: FakeService -> probeAnswer = { io } } to "No longer available from the service",
        ).forEach { (fault, words) ->
            reset()
            serviceA.cached[goodTorrent.hash] = goodTorrent.files
            fault(serviceA)
            rowOf(goodTorrent.candidate)
            val failed = assertIs<DownloadResolver.Resolution.Failed>(resolver().resolve(ID))
            assertEquals(words, failed.reason)
            assertEquals(words, stored().reason)
            assertEquals(DownloadState.Failed, stored().state)
            assertEquals(listOf(goodTorrent.key), stored().tried, words)
        }

        // Not cached on its one account, remembered so: the next row of it passes it over with no call.
        reset()
        rowOf(goodTorrent.candidate)
        assertIs<DownloadResolver.Resolution.Failed>(resolver().resolve(ID))
        dao.delete(ID)
        serviceA.prepares.clear()
        rowOf(goodTorrent.candidate)
        assertEquals(DownloadResolver.Resolution.Failed("Skipped 1 release no longer cached"), resolver().resolve(ID))
        assertEquals(emptyList(), serviceA.prepares)
        assertEquals(emptyList(), stored().tried)
    }

    /**
     * L460: a torrent row a process left once its file was begun opens again from the copy of its listing in its folder,
     * with no prepare, no call and no provider wait (none answers here): its file found there by its place, name and size,
     * written on from its offset. With no copy (a death before it was written), or its file no longer listed so, it
     * resolves again from its releases, its file starting again from 0.
     */
    @Test
    fun `a torrent row opened again reads its listing's copy with no call, and resolves again with none`() = runBlocking<Unit> {
        resolvedOn(goodTorrent, offset = 400_000_000)
        val entry = assertNotNull(storage.listings[ID])
        val ready = assertIs<DownloadResolver.Resolution.Ready.Service>(resolver(providers = null).resolve(ID))
        assertEquals(entry, ready.entry)
        assertEquals(goodTorrent.episode(), ready.file)
        assertEquals(400_000_000L, ready.row.safeOffset)
        assertEquals(emptyList(), lookups)
        assertEquals(emptyList(), serviceA.prepares)
        assertEquals(emptyList(), storage.deletedVideos)

        // No copy: its releases again, the next one, its file started again from 0.
        reset()
        resolvedOn(goodTorrent, offset = 400_000_000)
        storage.listings.clear()
        dao.update(stored().copy(candidates = listOf(goodTorrent.candidate, otherTorrent.candidate)))
        serviceA.cached[otherTorrent.hash] = otherTorrent.files
        val again = assertIs<DownloadResolver.Resolution.Ready.Service>(resolver().resolve(ID))
        assertEquals(otherTorrent.files.single(), again.file)
        assertEquals(0L, again.row.safeOffset)
        assertEquals(listOf(ID), storage.deletedVideos)

        // Its file listed at another size now: the copy dropped, and its releases again.
        reset()
        resolvedOn(goodTorrent, offset = 400_000_000)
        dao.update(stored().copy(size = 999, candidates = listOf(goodTorrent.candidate, otherTorrent.candidate)))
        serviceA.cached[otherTorrent.hash] = otherTorrent.files
        assertEquals(otherTorrent.files.single(), assertIs<DownloadResolver.Resolution.Ready.Service>(resolver().resolve(ID)).file)
        assertTrue("drop source $ID" in storage.events, storage.events.toString())
    }

    /**
     * L424, L460: a torrent lost on its account mid-file goes on from another, whose listing the writer keeps as the row's
     * copy, its file found there by its path and size at whatever place that service lists it. A row opened again after the
     * move (Pause and Resume, a process death, a limit waited out in place) finds its file in that copy by its name and
     * size where its place differs, and goes on from its offset with no call: nothing dropped, nothing started again.
     */
    @Test
    fun `a torrent row moved to another account's listing opens again from it, its file listed at another place there`() = runBlocking<Unit> {
        resolvedOn(goodTorrent, offset = 400_000_000)
        // The second account lists the video first, then the info file.
        val moved = listOf(goodTorrent.files[1].copy(index = 0, id = "900"), goodTorrent.files[0].copy(index = 1, id = "901"))
        val hash = InfoHash.of(goodTorrent.hash)
        val entry = DebridListingStore.Entry(
            DebridListingStore.idFor(ACCOUNT_B, hash), ACCOUNT_B, goodTorrent.title, Prepared(hash, ServiceListing(moved), moved[0], "${ACCOUNT_B.take(4)}-1"),
        )
        storage.keepListing(ID, entry)
        storage.events.clear()
        val ready = assertIs<DownloadResolver.Resolution.Ready.Service>(resolver(on = listOf(ACCOUNT_A, ACCOUNT_B)).resolve(ID))
        assertEquals(entry, ready.entry)
        assertEquals(moved[0], ready.file)
        assertEquals(400_000_000L, ready.row.safeOffset)
        assertEquals(emptyList(), lookups)
        assertEquals(emptyList(), serviceB.prepares)
        assertEquals(emptyList(), storage.deletedVideos)
        assertFalse("drop source $ID" in storage.events, storage.events.toString())
    }

    /** L460, L194: a torrent of an indexer whose "Use with your debrid service" is off now goes to no service: left out, uncounted. */
    @Test
    fun `a torrent of an indexer since kept from the service is left out with no call`() = runBlocking<Unit> {
        serviceA.cached[goodTorrent.hash] = goodTorrent.files
        rowOf(goodTorrent.candidate, usenet(single))
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver(offService = setOf(TORZNAB)).resolve(ID))
        assertEquals(single.guid, ready.row.release?.guid)
        assertEquals(listOf(key(single)), stored().tried)
        assertEquals(emptyList(), lookups)
        assertEquals(emptyList(), serviceA.prepares)
        assertEquals(listOf(single.guid), grabbed)
    }

    /**
     * L422: while debrid is hidden a torrent row's turn makes no call of any kind: before any lookup it fails "Not
     * available in this version", nothing counted, and one opened again from its listing's copy too. A row with a Usenet
     * release beside its torrent tries that one.
     */
    @Test
    fun `hidden, a torrent row fails with no call, and a Usenet release beside it is tried`() = runBlocking<Unit> {
        serviceA.cached[goodTorrent.hash] = goodTorrent.files
        rowOf(goodTorrent.candidate)
        val failed = assertIs<DownloadResolver.Resolution.Failed>(resolver(shown = false).resolve(ID))
        assertEquals(DownloadHostRules.NOT_IN_THIS_VERSION, failed.reason)
        assertEquals(DownloadHostRules.NOT_IN_THIS_VERSION, stored().reason)
        assertEquals(emptyList(), stored().tried)
        assertEquals(emptyList(), lookups)
        assertEquals(0, onAsked.get())
        assertEquals(emptyList(), grabbed)

        reset()
        serviceA.cached[goodTorrent.hash] = goodTorrent.files
        rowOf(goodTorrent.candidate, usenet(single))
        assertEquals(single.guid, assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver(shown = false).resolve(ID)).row.release?.guid)
        assertEquals(listOf(key(single)), stored().tried)
        assertEquals(emptyList(), lookups)

        reset()
        resolvedOn(goodTorrent, offset = 400_000_000)
        assertEquals(DownloadResolver.Resolution.Failed(DownloadHostRules.NOT_IN_THIS_VERSION), resolver(shown = false).resolve(ID))
        assertEquals(emptyList(), lookups)
        assertEquals(0, onAsked.get())
    }

    /**
     * L422, L460: while debrid is hidden, a torrent row a shown build left goes to no service, and a Usenet release beside it
     * is tried. Its Try again (its tries and reason cleared, its listing's copy dropped, its torrent release kept) tries the
     * row's Usenet release, the file started again from 0, and a season row's searches its episode afresh; with nothing but
     * torrents left it fails "Not available in this version". None of it asks an account anything.
     */
    @Test
    fun `hidden, Try again of a row whose release was a torrent tries its Usenet release, or searches a season row afresh`() = runBlocking<Unit> {
        resolvedOn(goodTorrent, offset = 400_000_000)
        dao.update(stored().copy(candidates = listOf(goodTorrent.candidate, usenet(single)), tried = emptyList(), state = DownloadState.Queued))
        storage.listings.clear()
        val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver(shown = false).resolve(ID))
        assertEquals(single.guid, ready.row.release?.guid)
        assertEquals(0L, ready.row.safeOffset)
        assertEquals(listOf(ID), storage.deletedVideos)
        assertEquals(listOf(key(single)), stored().tried)
        assertEquals(emptyList(), lookups)
        assertEquals(0, onAsked.get())

        // A season row's: its releases cleared, its search to come.
        reset()
        resolvedOn(goodTorrent, offset = 400_000_000)
        dao.update(stored().copy(candidates = emptyList(), tried = emptyList(), state = DownloadState.Queued).withFacts())
        storage.listings.clear()
        finds = listOf(single)
        assertEquals(single.guid, assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver(shown = false).resolve(ID)).row.release?.guid)
        assertEquals(1, searched.size)
        assertEquals(emptyList(), lookups)
        assertEquals(0, onAsked.get())

        // Nothing but the torrent left.
        reset()
        resolvedOn(goodTorrent, offset = 400_000_000)
        dao.update(stored().copy(tried = emptyList(), state = DownloadState.Queued))
        storage.listings.clear()
        assertEquals(DownloadResolver.Resolution.Failed(DownloadHostRules.NOT_IN_THIS_VERSION), resolver(shown = false).resolve(ID))
        assertEquals(emptyList(), lookups)
        assertEquals(0, onAsked.get())
    }

    /** With every account switched off or signed out of since it was queued, a torrent row waits for one, nothing counted or asked. */
    @Test
    fun `with no account on, a torrent row waits for one`() = runBlocking<Unit> {
        serviceA.cached[goodTorrent.hash] = goodTorrent.files
        rowOf(goodTorrent.candidate)
        assertEquals(DownloadResolver.Resolution.Waiting(DownloadHostRules.CHECK_SERVICE), resolver(on = emptyList()).resolve(ID))
        assertEquals(emptyList(), stored().tried)
        assertEquals(emptyList(), lookups)
    }

    /**
     * L460, L165: a row's torrent goes to its service whatever the providers do, as a play's cut leaves the NZBs out while no
     * provider is usable. With none in use (none saved and on, or none the engine has taken up yet), the row's Usenet
     * releases wait for one, uncounted and never grabbed, while its torrents are tried, and the row waits for the providers
     * only once those have failed. A connection lost on a Usenet release ends Usenet's share of the pass alone: no other
     * Usenet release is grabbed while it lasts, but a torrent after it is still tried.
     */
    @Test
    fun `a torrent beside Usenet releases is tried while no provider is usable, the Usenet ones waiting for one uncounted`() = runBlocking<Unit> {
        // No provider saved and on: the torrent, though the Usenet release ranks first.
        serviceA.cached[goodTorrent.hash] = goodTorrent.files
        rowOf(usenet(single), goodTorrent.candidate)
        val ready = assertIs<DownloadResolver.Resolution.Ready.Service>(resolver(providers = false).resolve(ID))
        assertEquals(goodTorrent.key, ready.row.release?.guid)
        assertEquals(listOf(goodTorrent.key), stored().tried)
        assertEquals(emptyList(), grabbed)

        // The torrent no longer cached: the row waits for a provider then, its Usenet release untried and uncounted.
        reset()
        rowOf(usenet(single), goodTorrent.candidate)
        assertEquals(DownloadResolver.Resolution.Waiting(DownloadHostRules.CHECK_PROVIDER), resolver(providers = false).resolve(ID))
        assertEquals(DownloadHostRules.CHECK_PROVIDER, stored().reason)
        assertEquals(listOf(goodTorrent.key), stored().tried)
        assertEquals(emptyList(), grabbed)
        // A provider saved: its Usenet release, the torrent counted once.
        assertEquals(single.guid, assertIs<DownloadResolver.Resolution.Ready.Usenet>(resolver().resolve(ID)).row.release?.guid)
        assertEquals(listOf(goodTorrent.key, key(single)), stored().tried)

        // Saved, and not taken up by the engine yet: the torrent too, and once it fails the row backs off for the providers.
        reset()
        serviceA.cached[goodTorrent.hash] = goodTorrent.files
        rowOf(usenet(single), goodTorrent.candidate)
        assertEquals(goodTorrent.key, assertIs<DownloadResolver.Resolution.Ready.Service>(resolver(providers = null).resolve(ID)).row.release?.guid)
        reset()
        rowOf(usenet(single), goodTorrent.candidate)
        val notYet = assertIs<DownloadResolver.Resolution.Unreachable>(resolver(providers = null).resolve(ID))
        assertEquals(DownloadHostRules.CANT_REACH_PROVIDER, notYet.words)
        assertEquals(listOf(goodTorrent.key), stored().tried)
        assertEquals(emptyList(), grabbed)

        // The connection lost checking the Usenet release: the torrent after it is tried all the same.
        reset()
        serviceA.cached[goodTorrent.hash] = goodTorrent.files
        rowOf(usenet(single), goodTorrent.candidate, usenet(pack))
        checkFails = java.io.IOException("Connection reset by peer")
        assertEquals(goodTorrent.key, assertIs<DownloadResolver.Resolution.Ready.Service>(resolver().resolve(ID)).row.release?.guid)
        assertEquals(listOf(single.guid), grabbed)
        // With the torrent failing too: backed off for the providers, the Usenet release uncounted, the next never grabbed.
        reset()
        rowOf(usenet(single), goodTorrent.candidate, usenet(pack))
        checkFails = java.io.IOException("Connection reset by peer")
        val lost = assertIs<DownloadResolver.Resolution.Unreachable>(resolver().resolve(ID))
        assertIs<UsenetError.Network>(lost.error)
        assertEquals(DownloadHostRules.CANT_REACH_PROVIDER, lost.words)
        assertEquals(listOf(goodTorrent.key), stored().tried)
        assertEquals(listOf(single.guid), grabbed)
    }

    /**
     * L382, L388: the providers failing a Usenet release is no answer about it, wherever it falls in the pass. Its failure
     * stops Usenet's route alone, so a torrent after it is still tried; one failing for its own sake then leaves the row
     * waiting for the providers (backing off from them out of reach), the Usenet release uncounted, never Failed in the
     * torrent's words.
     */
    @Test
    fun `the providers failing a Usenet release before a torrent that fails leave the row waiting for them, not failed`() = runBlocking<Unit> {
        val unreachable = Fake(
            "hl-s02e04-down", "Harbor.Lights.S02E04.1080p.WEB.h264-WREN", listOf("e2.mkv" to 1_000_000_000L),
            opening = NntpConnectionException("Connection refused", ConnectException("Connection refused")),
        )
        rowOf(usenet(unreachable), goodTorrent.candidate)
        val out = assertIs<DownloadResolver.Resolution.Unreachable>(resolver().resolve(ID))
        assertIs<UsenetError.ConnectionFailed>(out.error)
        assertEquals(DownloadHostRules.CANT_REACH_PROVIDER, out.words)
        assertEquals(DownloadState.Resolving, stored().state)
        assertEquals(listOf(goodTorrent.hash), serviceA.prepares)
        assertEquals(listOf(goodTorrent.key), stored().tried)

        reset()
        val refused = Fake("hl-s02e04-auth", "Harbor.Lights.S02E04.1080p.WEB.h264-WREN", listOf("e1.mkv" to 1_000_000_000L), opening = NntpAuthenticationException("481 Authentication failed"))
        rowOf(usenet(refused), goodTorrent.candidate)
        assertEquals(DownloadResolver.Resolution.Waiting(DownloadHostRules.CHECK_PROVIDER), resolver().resolve(ID))
        assertEquals(DownloadHostRules.CHECK_PROVIDER, stored().reason)
        assertEquals(listOf(goodTorrent.key), stored().tried)
    }

    /**
     * L421: what Resolve's attempts add to an account is held by the resolve while it runs and let go as it ends, however it
     * ends, so each add starts its grace then and a sweep a grace later removes it: an exhausted resolve, one that waits
     * (the next torrent's key refused), and one cut short by a pause, held meanwhile.
     */
    @Test
    fun `each attempt's add is held while Resolve runs and let go as it ends, however it ends`() = runBlocking<Unit> {
        val noAudio = ProbeResult.NoPlayableAudio(listOf("DTS-HD MA"))
        // Exhausted: added, then its audio isn't one this device plays.
        var adds = adds()
        serviceA.cached[goodTorrent.hash] = goodTorrent.files
        probeAnswer = { noAudio }
        rowOf(goodTorrent.candidate)
        assertIs<DownloadResolver.Resolution.Failed>(resolver(adds = adds).resolve(ID))
        assertEquals(listOf(goodTorrent.hash), serviceA.added)
        sweptAfterGrace(adds)
        assertEquals(1, serviceA.removed.size, "let go at the end, removed a grace later")

        // Waiting: the first added and failed, the next's key refused.
        reset()
        java.io.File(nzbDir, "debrid/added.json").delete()
        adds = adds()
        serviceA.cached[goodTorrent.hash] = goodTorrent.files
        serviceA.cached[otherTorrent.hash] = otherTorrent.files
        serviceA.failsFor[otherTorrent.hash] = ServiceError.KeyRefused
        probeAnswer = { key -> noAudio.takeIf { key == goodTorrent.key } }
        rowOf(goodTorrent.candidate, otherTorrent.candidate)
        assertEquals(DownloadResolver.Resolution.Waiting(DownloadHostRules.CHECK_SERVICE), resolver(adds = adds).resolve(ID))
        sweptAfterGrace(adds)
        assertEquals(1, serviceA.removed.size, "let go as it waits")

        // Cut short as its probe reads: held meanwhile, past a grace, then let go.
        reset()
        java.io.File(nzbDir, "debrid/added.json").delete()
        adds = adds()
        serviceA.cached[goodTorrent.hash] = goodTorrent.files
        val probing = CompletableDeferred<Unit>()
        onProbe = {
            probing.complete(Unit)
            awaitCancellation()
        }
        rowOf(goodTorrent.candidate)
        val resolving = launch(Dispatchers.Default) { resolver(adds = adds).resolve(ID) }
        withTimeout(10_000) { probing.await() }
        sweptAfterGrace(adds)
        assertEquals(emptyList(), serviceA.removed.toList(), "held while it resolves")
        resolving.cancelAndJoin()
        sweptAfterGrace(adds)
        assertEquals(1, serviceA.removed.size, "let go as it was cut short")
    }

    /**
     * L421: Resolve takes the download's own lease on the torrent it found before it lets its Play go: the chosen add stays
     * held past any grace (the download writes it), while the attempts before it that added theirs are let go and removed a
     * grace on; the download's end lets the chosen one go too.
     */
    @Test
    fun `the chosen torrent's add is held by the download's lease, the attempt before it let go`() = runBlocking<Unit> {
        val adds = adds()
        serviceA.cached[otherTorrent.hash] = otherTorrent.files
        serviceA.cached[goodTorrent.hash] = goodTorrent.files
        // The first added, then its audio isn't one this device plays; the second found.
        probeAnswer = { key -> ProbeResult.NoPlayableAudio(listOf("DTS-HD MA")).takeIf { key == otherTorrent.key } }
        rowOf(otherTorrent.candidate, goodTorrent.candidate)
        val ready = assertIs<DownloadResolver.Resolution.Ready.Service>(resolver(adds = adds).resolve(ID))
        assertEquals(listOf(otherTorrent.hash, goodTorrent.hash), serviceA.added)
        sweptAfterGrace(adds)
        assertEquals(1, serviceA.removed.size, "the attempt before it let go")
        assertEquals(listOf(ready.entry.id), adds.records().map { it.listingId }, "the chosen one held")
        repeat(3) { sweptAfterGrace(adds) }
        assertEquals(1, serviceA.removed.size, "held past its grace")
        adds.release(DownloadLease(ID))
        sweptAfterGrace(adds)
        assertEquals(2, serviceA.removed.size, "let go at the download's end, removed a grace on")
    }

    /**
     * A torrent's lines in the app's log name the download by its id and the release by its place among the row's, never
     * by its hash, its release's or file's name, or its account (L419, L208), whether it fails or is kept.
     */
    @Test
    fun `a torrent's Resolve names it by its place, never by its hash, names or account`() = runBlocking<Unit> {
        serviceA.cached[unnamedTorrent.hash] = unnamedTorrent.files
        serviceA.cached[goodTorrent.hash] = goodTorrent.files
        rowOf(unnamedTorrent.candidate, goodTorrent.candidate)
        val from = LogBuffer.snapshot().lastOrNull()?.seq ?: 0L
        assertIs<DownloadResolver.Resolution.Ready.Service>(resolver().resolve(ID))
        val lines = LogBuffer.snapshot().filter { it.seq > from }.map { it.message }
        val secret = listOf(goodTorrent.hash, unnamedTorrent.hash, goodTorrent.title, unnamedTorrent.title, "WREN", "hl.part") +
            (goodTorrent.files + unnamedTorrent.files).map { it.name }
        assertEquals(emptyList(), lines.filter { line -> secret.any { it in line } })
        assertTrue(lines.any { "play_pipeline: download $ID, release 2 " in it }, lines.toString())
    }

    /**
     * L462, L432: a season's torrent pack is kept for its season as a Usenet pack's NZB is, by the same rule, as its meta
     * alone (no NZB, no password: its attempt prepares it by its hash): only a torrent named as several episodes, and only
     * while another row of its show and season is still to finish. It leads unless, ranked alone for downloading with the
     * episode's runtime, it is above Download size.
     */
    @Test
    fun `a season's torrent pack is kept as its meta alone, by the rule a Usenet pack is kept by`() = runBlocking<Unit> {
        // Alone in its season.
        serviceA.cached[packTorrent.hash] = packTorrent.files
        torrentEpisode(E1, 1, packTorrent.candidate)
        assertIs<DownloadResolver.Resolution.Ready.Service>(resolver().resolve(E1))
        assertNull(storage.packs.find(packTorrent.key))

        // The season's others done, failed or removed; another season's and another show's still to finish.
        reset()
        serviceA.cached[packTorrent.hash] = packTorrent.files
        torrentEpisode(E1, 1, packTorrent.candidate)
        torrentEpisode(E2, 2, packTorrent.candidate) { it.copy(state = DownloadState.Done) }
        torrentEpisode(E3, 3, packTorrent.candidate) { it.copy(state = DownloadState.Failed) }
        torrentEpisode(E4, 4, packTorrent.candidate) { it.copy(state = DownloadState.Deleting) }
        torrentEpisode(OTHER_SEASON, 1, packTorrent.candidate) { it.copy(season = 3, watchKey = WatchKey.episode(SHOW, 3, 1)) }
        torrentEpisode(OTHER_SHOW, 1, packTorrent.candidate) { it.copy(showTmdbId = 900003, watchKey = WatchKey.episode(900003, 2, 1)) }
        assertIs<DownloadResolver.Resolution.Ready.Service>(resolver().resolve(E1))
        assertNull(storage.packs.find(packTorrent.key))

        // An episode's own torrent, with the season's others to come: none.
        reset()
        serviceA.cached[goodTorrent.hash] = goodTorrent.files
        rowOf(goodTorrent.candidate)
        torrentEpisode(E2, 2, packTorrent.candidate)
        assertIs<DownloadResolver.Resolution.Ready.Service>(resolver().resolve(ID))
        assertEquals(emptyList(), storage.packs.copies.value)

        // One of the season paused: kept for it, its meta alone, leading.
        reset()
        serviceA.cached[packTorrent.hash] = packTorrent.files
        torrentEpisode(E1, 1, packTorrent.candidate) { it.copy(runtimeMinutes = 44) }
        torrentEpisode(E2, 2, packTorrent.candidate) { it.copy(state = DownloadState.Paused) }
        assertIs<DownloadResolver.Resolution.Ready.Service>(resolver().resolve(E1))
        val copy = assertNotNull(storage.packs.find(packTorrent.key))
        assertEquals(DownloadNzbs.Meta(packTorrent.key, packTorrent.title, packTorrent.candidate, SHOW, 2, leads = true), copy.meta)
        assertNull(copy.source)
        assertEquals(setOf(DownloadNzbs.META), storage.packs.folder(packTorrent.key).list()!!.toSet())
        assertEquals(1, storage.packWrites)
        assertEquals(emptyMap(), storage.sources)

        // Above Download size for the episode (its share of 6.6 GB over 44 minutes is about 1.5 GB an hour): kept, not leading.
        reset()
        serviceA.cached[packTorrent.hash] = packTorrent.files
        torrentEpisode(E1, 1, packTorrent.candidate) { it.copy(runtimeMinutes = 44) }
        torrentEpisode(E2, 2, packTorrent.candidate)
        assertIs<DownloadResolver.Resolution.Ready.Service>(resolver(cap = 1).resolve(E1))
        assertEquals(false, storage.packs.find(packTorrent.key)?.meta?.leads)
        reset()
        serviceA.cached[packTorrent.hash] = packTorrent.files
        torrentEpisode(E1, 1, packTorrent.candidate) { it.copy(runtimeMinutes = 44) }
        torrentEpisode(E2, 2, packTorrent.candidate)
        assertIs<DownloadResolver.Resolution.Ready.Service>(resolver(cap = 2).resolve(E1))
        assertEquals(true, storage.packs.find(packTorrent.key)?.meta?.leads)
    }

    /**
     * L462, L431: the season's next row tries the torrent pack's meta first and alone, as a Usenet copy is tried: prepared
     * on the account by its hash, the episode's own file picked from its listing with no question, with no search and no
     * grab. The Downloads page says so of it as of a Usenet copy's ("Queued · from the season pack", then "From the
     * season pack"). A meta kept already stays as the first row wrote it.
     */
    @Test
    fun `a season's torrent pack is tried first and alone for the season's next row, by its hash, with no search`() = runBlocking<Unit> {
        serviceA.cached[packTorrent.hash] = packTorrent.files
        finds = listOf(single)
        torrentEpisode(E1, 1, packTorrent.candidate)
        torrentEpisode(E2, 2) { it.withFacts() }
        assertIs<DownloadResolver.Resolution.Ready.Service>(resolver().resolve(E1))
        dao.setState(E1, DownloadState.Done, null, NOW)
        assertTrue(DownloadsPage.fromPack(stored(E2), DownloadRunner.Packs.of(storage.packs.copies.value)), "Queued · from the season pack")

        val ready = assertIs<DownloadResolver.Resolution.Ready.Service>(resolver().resolve(E2))
        assertEquals(packTorrent.key, ready.row.release?.guid)
        assertEquals(packTorrent.episode(2), ready.file)
        assertEquals(packTorrent.episode(2).index, ready.row.release?.fileIndex)
        assertEquals(ReleaseFacts.TORRENT, ready.row.release?.source)
        assertEquals(listOf(packTorrent.hash, packTorrent.hash), serviceA.prepares)
        assertEquals(emptyList(), searched)
        assertEquals(emptyList(), grabbed)
        assertEquals(listOf(packTorrent.key), stored(E2).tried)
        assertTrue(DownloadsPage.fromPack(stored(E2), DownloadRunner.Packs.of(storage.packs.copies.value)), "From the season pack")
        assertEquals(1, storage.packWrites)
        assertEquals(emptyMap(), storage.sources)
    }

    /**
     * L462, L431: a season's torrent pack that fails for its own sake (no longer cached) is one of the row's three tries,
     * counted once, and the turn's search runs then, the pack left out of what it finds; one that finds nothing else fails
     * in the pack's words. Its meta stays for the season's other rows.
     */
    @Test
    fun `a season's torrent pack that fails for its own sake moves on to the search, counted once and left out of what it finds`() = runBlocking<Unit> {
        keptForSeason(packTorrent)
        serviceA.cached[goodTorrent.hash] = goodTorrent.files
        torrentFinds = listOf(packTorrent, goodTorrent)
        rowOf { it.withFacts() }
        val ready = assertIs<DownloadResolver.Resolution.Ready.Service>(resolver().resolve(ID))
        assertEquals(goodTorrent.key, ready.row.release?.guid)
        assertEquals(listOf(packTorrent.hash, goodTorrent.hash), serviceA.prepares)
        assertEquals(1, searched.size)
        assertEquals(listOf(packTorrent.key, goodTorrent.key), stored().tried)
        assertEquals(listOf(goodTorrent.key), stored().candidates.map { it.guid })
        assertNotNull(storage.packs.find(packTorrent.key))

        // Nothing else found: it fails in the pack's words.
        reset()
        keptForSeason(packTorrent)
        torrentFinds = listOf(packTorrent)
        rowOf { it.withFacts() }
        val failed = assertIs<DownloadResolver.Resolution.Failed>(resolver().resolve(ID))
        assertEquals(DownloadResolver.NO_LONGER_CACHED, failed.reason)
        assertEquals(listOf(packTorrent.key), stored().tried)
        assertEquals(listOf(packTorrent.hash), serviceA.prepares)
    }

    /**
     * L460, L461, L474: a season row's turn whose search found nothing but torrents its accounts couldn't check had no answer
     * about any release: never Failed "No playable release found", nothing counted, its search still to come. Its account's
     * limit goes to the runner with when the gate opens (waited out in place, or the row's wait); its refused key waits for
     * a new one; its outage, or a check out of time, is backed off from in the debrid words.
     */
    @Test
    fun `a season row whose turn's search couldn't check its torrents waits for the account, never failing`() = runBlocking<Unit> {
        listOf(
            DownloadSearch.ServiceStop.Limit(ACCOUNT_A, NOW + 3_600_000) to DownloadHostRules.FINDING_RELEASES,
            DownloadSearch.ServiceStop.KeyRefused to DownloadHostRules.CHECK_SERVICE,
            DownloadSearch.ServiceStop.Out to DownloadHostRules.CANT_REACH_SERVICE,
        ).forEach { (stop, words) ->
            reset()
            serviceStop = stop
            rowOf { it.withFacts() }
            val resolution = resolver().resolve(ID)
            when (stop) {
                is DownloadSearch.ServiceStop.Limit -> assertEquals(DownloadResolver.Resolution.Limited(ACCOUNT_A, NOW + 3_600_000), resolution)
                DownloadSearch.ServiceStop.KeyRefused -> assertEquals(DownloadResolver.Resolution.Waiting(DownloadHostRules.CHECK_SERVICE), resolution)
                DownloadSearch.ServiceStop.Out -> assertEquals(DownloadHostRules.CANT_REACH_SERVICE, assertIs<DownloadResolver.Resolution.Unreachable>(resolution).words)
            }
            assertEquals(words, stored().reason, "$stop")
            assertEquals(emptyList(), stored().tried, "$stop")
            assertEquals(false, DownloadColumns.search(stored())?.searched, "$stop")
            assertEquals(emptyList(), lookups, "$stop")
        }
    }

    /**
     * L462, L422, L194: a season's torrent pack goes first only where it can go to a service now. While debrid is hidden,
     * with no account on, or with its indexer kept from the service since, the turn goes on as with no copy: nothing is
     * asked of an account, nothing counted, and the search finds what the sources on now give.
     */
    @Test
    fun `a season's torrent pack is passed over with no call while debrid is hidden, no account is on or its indexer is kept from the service`() =
        runBlocking<Unit> {
            listOf<Pair<String, () -> DownloadResolver>>(
                "hidden" to { resolver(shown = false) },
                "no account on" to { resolver(on = emptyList()) },
                "kept from the service" to { resolver(offService = setOf(TORZNAB)) },
            ).forEach { (why, made) ->
                reset()
                keptForSeason(packTorrent)
                serviceA.cached[packTorrent.hash] = packTorrent.files
                finds = listOf(single)
                rowOf { it.withFacts() }
                val ready = assertIs<DownloadResolver.Resolution.Ready.Usenet>(made().resolve(ID), why)
                assertEquals(single.guid, ready.row.release?.guid, why)
                assertEquals(emptyList(), serviceA.prepares, why)
                assertEquals(emptyList(), lookups, why)
                assertEquals(1, searched.size, why)
                assertEquals(listOf(key(single)), stored().tried, why)
            }
        }

    /**
     * L462 at a resolve's success, whichever way it succeeds, as a Usenet row's reopen keeps its copy: a process that died
     * after a torrent row took its season pack and before its meta was written leaves none. The row, opened again from its
     * listing's copy with no call, keeps the meta for the season's rows still to finish then.
     */
    @Test
    fun `a torrent row opened again from its listing's copy keeps the season's meta a process death cut short`() = runBlocking<Unit> {
        resolvedOn(packTorrent)
        torrentEpisode(E2, 2, packTorrent.candidate)
        assertNull(storage.packs.find(packTorrent.key))
        assertIs<DownloadResolver.Resolution.Ready.Service>(resolver().resolve(ID))
        assertEquals(emptyList(), lookups)
        assertEquals(DownloadNzbs.Meta(packTorrent.key, packTorrent.title, packTorrent.candidate, SHOW, 2, leads = true), storage.packs.find(packTorrent.key)?.meta)
    }

    private suspend fun reset() {
        services.values.forEach {
            it.cached.clear()
            it.failsFor.clear()
            it.prepares.clear()
            it.added.clear()
            it.removed.clear()
            it.links.set(0)
        }
        lookups.clear()
        onAsked.set(0)
        misses = ServiceMisses(TriedTable(NOW), now = { NOW })
        storage.listings.clear()
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
        storage.packWrites = 0
        indexerFails = null
        refusing.clear()
        asks.clear()
        checkFails = null
        probeAnswer = { null }
        finds = emptyList()
        torrentFinds = emptyList()
        serviceStop = null
        listed.clear()
        findReports = listOf(IndexerReport(indexer, "TVDB id"))
        searched.clear()
        searchedAt.clear()
        listedBy.clear()
        lastPlayed.clear()
        lastAsked.clear()
        dao.onUpdate = {}
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

        override suspend fun insertAll(downloads: List<Download>, putBack: List<Download>) =
            rows.update { all -> all + putBack.filter { it.id in all }.associateBy { it.id } + downloads.associateBy { it.id } }

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
     * The download's places: its own NZB copies, the NZB kept for an attempt, a torrent's listing copy and restart mark, the
     * room on the device, the videos deleted, and the season packs' copies ([packs]: the app's own, in the test's folder),
     * which a test replaces with a new process's.
     */
    private class FakeStorage(var packs: DownloadNzbs) : DownloadResolver.Storage {
        val sources = mutableMapOf<String, DownloadStorage.Source>()
        val grabs = mutableMapOf<String, DownloadStorage.Grab>()
        val listings = mutableMapOf<String, DebridListingStore.Entry>()
        val restarts = mutableSetOf<String>()

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
            listings.remove(id)
            restarts.remove(id)
        }

        override suspend fun keepListing(id: String, entry: DebridListingStore.Entry) {
            events += "keep listing $id"
            listings[id] = entry
        }

        override suspend fun listing(id: String) = listings[id]

        override suspend fun markRestarted(id: String) {
            restarts += id
        }

        override suspend fun restarted(id: String) = id in restarts

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

        /** How many times a season pack's copy was written. */
        var packWrites = 0

        override suspend fun keepPack(meta: DownloadNzbs.Meta, nzb: ByteArray?, password: String?) {
            packWrites++
            packs.write(meta, nzb, password)
        }

        override fun heldPrepare() = packs.held()

        override fun holdPrepare(key: String, prepared: PreparedNzb) = packs.hold(key, prepared)

        override suspend fun packs(show: Int, season: Int) = packs.forSeason(show, season)
    }

    private companion object {
        const val ID = "0b6c1d2e-download"

        /** A season's rows: its episodes 1 to 6, and an episode of another season and of another show. */
        const val E1 = "0b6c1d2e-e1"
        const val E2 = "0b6c1d2e-e2"
        const val E3 = "0b6c1d2e-e3"
        const val E4 = "0b6c1d2e-e4"
        const val E5 = "0b6c1d2e-e5"
        const val E6 = "0b6c1d2e-e6"
        const val OTHER_SEASON = "0b6c1d2e-s3e1"
        const val OTHER_SHOW = "0b6c1d2e-other"
        const val INDEXER = "fake-indexer"

        /** The torrent indexer the torrents came from (L459). */
        const val TORZNAB = "fake-torznab"

        /** Two service accounts, as the settings give their ids, and when their keys were saved. */
        const val ACCOUNT_A = "4c1d9e70-account-a"
        const val ACCOUNT_B = "8f2a6b13-account-b"
        const val KEY_AT = 1_790_000_000_000L

        /** The second indexer set up ([other]). */
        const val OTHER_INDEXER = "other-indexer"

        /** An indexer a candidate came from that is no longer set up. */
        const val GONE_INDEXER = "removed-indexer"
        const val SHOW = 900002
        const val NOW = 1_800_000_000_000L

        /** An article the providers lack. */
        const val GONE = "gone-"

        /** The pack's second and third episodes' files. */
        const val E02 = "Harbor.Lights.S02E02.1080p.WEB.h264-GRP.mkv"
        const val E03 = "Harbor.Lights.S02E03.1080p.WEB.h264-GRP.mkv"

        /** The archive password typed on Import for an NZB it opened, kept in the NZB's copy (L423). */
        const val TYPED = "typed-on-import"

        /** A posted file's obfuscated name, as a release may post one. */
        const val OBFUSCATED = "5d41402abc4b2a76b9719d911017c592.mkv"

        /** Room for any of the fakes: 100 GB. */
        val ROOM = DownloadSpace(available = 100_000_000_000L, used = 0, queued = 0)

        /** What the season's sheet kept for a row of season 2 to search at its turn (L392): the show's names and ids, the season's count. */
        val FACTS = SeasonSearch("Harbor Lights", 2021, tvdb = 900001, tmdb = SHOW, airDate = "2025-03-25", runtimeMinutes = 44, seasonEpisodes = 6)
    }
}
