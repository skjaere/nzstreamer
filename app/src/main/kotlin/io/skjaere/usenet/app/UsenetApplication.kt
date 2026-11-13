package io.skjaere.usenet.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import io.skjaere.usenet.core.ConnectionMeter
import io.skjaere.usenet.core.EngineAvailabilityBackend
import io.skjaere.usenet.core.EnginePrepareBackend
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.core.NzbPreparer
import io.skjaere.usenet.core.NzbStreamOpener
import io.skjaere.usenet.core.PreparedNzbStore
import io.skjaere.usenet.core.ProviderConfig
import io.skjaere.usenet.core.StreamCostMonitor
import io.skjaere.usenet.core.StreamCostSampler
import io.skjaere.usenet.core.StreamTraffic
import io.skjaere.usenet.core.ThreadCpuReader
import io.skjaere.usenet.core.UsenetEngine
import io.skjaere.usenet.core.VideoCache
import io.skjaere.usenet.app.collection.CollectionStore
import io.skjaere.usenet.app.debrid.DebridAdds
import io.skjaere.usenet.app.debrid.DebridCacheCheck
import io.skjaere.usenet.app.debrid.DebridListingStore
import io.skjaere.usenet.app.debrid.ServiceLinks
import io.skjaere.usenet.app.debrid.ServiceMisses
import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.app.debrid.ServiceRegistry
import io.skjaere.usenet.app.debrid.nameOf
import io.skjaere.usenet.app.debrid.seedDevDebrid
import io.skjaere.usenet.app.home.SetupState
import io.skjaere.usenet.app.search.IndexerConfigs
import io.skjaere.usenet.app.search.NamedAccount
import io.skjaere.usenet.app.search.accountsInOrder
import io.skjaere.usenet.app.link.InFront
import io.skjaere.usenet.app.link.PhoneFinals
import io.skjaere.usenet.app.link.PhoneFinalsDeps
import io.skjaere.usenet.app.casting.CastLauncher
import io.skjaere.usenet.app.casting.CastProfile
import io.skjaere.usenet.app.casting.CastProfiles
import io.skjaere.usenet.app.casting.CastRanking
import io.skjaere.usenet.app.casting.CastPlaying
import io.skjaere.usenet.app.casting.CastPlayingDeps
import io.skjaere.usenet.app.casting.NoCast
import io.skjaere.usenet.app.casting.listRankingOf
import io.skjaere.usenet.app.casting.listRankings
import io.skjaere.usenet.app.casting.rankingTargetOf
import io.skjaere.usenet.app.link.PlayOn
import io.skjaere.usenet.app.link.PlayRouter
import io.skjaere.usenet.app.link.PlayRouterDeps
import io.skjaere.usenet.app.link.NoRemote
import io.skjaere.usenet.app.link.RemotePlaying
import io.skjaere.usenet.app.link.RemotePlays
import io.skjaere.usenet.app.link.TvStart
import io.skjaere.usenet.app.link.TvStartDeps
import io.skjaere.usenet.app.link.TvFinals
import io.skjaere.usenet.app.link.TvLinkDeps
import io.skjaere.usenet.app.link.TvLinkHost
import io.skjaere.usenet.app.link.TvLinks
import io.skjaere.usenet.app.link.TvLinksDeps
import io.skjaere.usenet.app.link.TvPhoneLinks
import io.skjaere.usenet.app.link.TvPlaying
import io.skjaere.usenet.app.link.TvPlayingDeps
import io.skjaere.usenet.app.link.TvPlays
import io.skjaere.usenet.app.link.TvPlaysDeps
import io.skjaere.usenet.app.play.DisplayModeHold
import io.skjaere.usenet.app.play.MissingReleases
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.PlayOrigin
import io.skjaere.usenet.app.play.PlayerInFront
import io.skjaere.usenet.app.play.UsableRoutes
import io.skjaere.usenet.app.play.noUsableSource
import io.skjaere.usenet.app.play.servicesSaved
import io.skjaere.usenet.app.refresh.LibrarySummaryStore
import io.skjaere.usenet.app.refresh.RateGate
import io.skjaere.usenet.app.refresh.SummaryRefresher
import io.skjaere.usenet.app.refresh.TmdbTitleSource
import io.skjaere.usenet.app.settings.NoUsableProvider
import io.skjaere.usenet.app.settings.ProviderTests
import io.skjaere.usenet.app.settings.StoredData
import io.skjaere.usenet.app.settings.ThisDevice
import io.skjaere.usenet.app.settings.displayName
import io.skjaere.usenet.app.settings.headerLinesToLog
import io.skjaere.usenet.app.settings.loginResult
import io.skjaere.usenet.app.setup.SetupPages
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.data.DataContainer
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.ProviderWithPassword
import io.skjaere.usenet.debrid.ServiceHttp
import io.skjaere.usenet.logging.AndroidLogConfig
import io.skjaere.usenet.logging.CrashLog
import io.skjaere.usenet.metadata.FileMetadataCache
import io.skjaere.usenet.metadata.IntroDbClient
import io.skjaere.usenet.metadata.TmdbClient
import io.skjaere.usenet.newznab.CapsCache
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.MultiIndexerSearch
import io.skjaere.usenet.newznab.NewznabClient
import io.skjaere.usenet.player.AudioRouteWatcher
import io.skjaere.usenet.player.DeviceCapabilitiesLoader
import io.skjaere.usenet.player.DisplayModes
import io.skjaere.usenet.player.ExoTrackProbe
import io.skjaere.usenet.player.GapReports
import io.skjaere.usenet.player.LinkResolver
import io.skjaere.usenet.player.NativeFactsLoader
import io.skjaere.usenet.player.PlayUris
import io.skjaere.usenet.player.ReadAheadDataSource
import io.skjaere.usenet.player.ServiceDataSource
import io.skjaere.usenet.player.SourceDataSource
import io.skjaere.usenet.player.UsenetDataSource
import io.skjaere.usenet.player.UsenetStreamReader
import io.skjaere.usenet.player.androidKeptFiles
import io.skjaere.usenet.player.artGcStats
import io.skjaere.usenet.player.clockTicksPerSecond
import io.skjaere.usenet.player.tv.DeviceMemory
import io.skjaere.usenet.player.tv.PlaybackBudgets
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.RankingTarget
import io.skjaere.usenet.search.CheckedSearch
import io.skjaere.usenet.search.EpisodeReleaseFinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDate
import java.util.Locale

/** Manual dependency wiring; one instance per process. */
class AppContainer(private val context: Context) {
    val appScope = AppScope()

    /**
     * Settings › Cache's watched video, in the cache directory (the system may clear it when storage
     * runs low): one per process, lent to every streamer the engine builds. One opened since the
     * streamer was built reaches it as the engine builds it again, once nothing uses it.
     */
    val videoCache: VideoCache = VideoCache(File(context.cacheDir, "segments"), opened = { engine.rebuildWhenIdle() })

    /**
     * With several providers, the engine logs in to each before it uses them and leaves out one whose
     * login is rejected or that can't be reached while another serves; the providers list shows how
     * that went, as it shows a Test, so a provider left out says why.
     */
    val engine: UsenetEngine = UsenetEngine(
        factory = { settings -> UsenetEngine.defaultStreamer(settings, videoCache.forStreamer()) },
        probe = { config -> UsenetEngine.probeProvider(config).also { providerTests.record(config.id, config.loginResult(it)) } },
    )
    val data = DataContainer(context, appScope)
    val settings get() = data.settings
    val library get() = data.library

    /** Shared by the metadata services (TMDB, TheIntroDB); indexers have their own. */
    private val metadataHttp = TmdbClient.defaultHttpClient()
    /** What Settings › Metadata and Settings › Cache show the size of and clear ([storedData]). */
    private val metadataCache = FileMetadataCache(File(context.cacheDir, "tmdb"))

    val tmdb = TmdbClient(
        http = metadataHttp,
        credentials = { settings.tmdbKey() },
        language = { settings.metadata.first().language ?: Locale.getDefault().toLanguageTag() },
        cache = metadataCache,
        // The device's zone as it is now (as the refresher's): today's date decides which shows are airing.
        clock = DeviceClock,
        // The lists' refresher spaces its requests (only those that go to TMDB) by its rate gate.
        beforeNetwork = { RateGate.beforeNetwork() },
    )

    /**
     * Intro and credits times for TMDB episodes; asks only while the user has turned it on. Its
     * answers stay in memory (TheIntroDB's terms), so they aren't part of the metadata cache.
     */
    val introDb = IntroDbClient(
        http = metadataHttp,
        enabled = { settings.playback.first().lookUpSegmentsOnline },
    )

    val store = PreparedNzbStore(File(context.filesDir, "nzb"))

    /** Settings › Cache's "Stored data": posters and images, TMDB metadata, NZB files kept for resume, the releases found incomplete. */
    val storedData = StoredData(context, metadataCache, store, library)
    val preparer = NzbPreparer(EnginePrepareBackend(engine))
    val opener = NzbStreamOpener(engine)

    /** What each stream hands the player, for the stream-cost line ([streamCost]). */
    val streamTraffic = StreamTraffic()
    /** The missing stretches the player's streams find, by file: the player of a file counts its own (MissingParts). */
    val gapReports = GapReports()

    private val usenetSources = UsenetDataSource.Factory { UsenetStreamReader(store, preparer, opener, streamTraffic, gapReports) }

    /**
     * A debrid play's sources over HTTPS, by the links [serviceLinks] gives (L172): one factory, so every reader of a file
     * holds one link (L249), the cast relay's reads too ([io.skjaere.usenet.app.serve.SourceReads], L217), which it aborts
     * as their own type.
     */
    val serviceSources = ServiceDataSource.Factory(LinkResolver { target, stale -> serviceLinks.link(target, stale) }, streamTraffic)

    /**
     * Where the track probe and the cast relay read a play's file, by its URI's scheme ([PlayUris]): Usenet's, from the
     * store's NZB; a debrid service's over HTTPS, as the CDN gives it.
     */
    val dataSourceFactory = SourceDataSource.Factory(mapOf(PlayUris.USENET to usenetSources, PlayUris.SERVICE to serviceSources))

    /**
     * Where the player reads it: as [dataSourceFactory], but a debrid play's file is read ahead of the player as far as a
     * Usenet play's streams read ahead (L239, L249, W88), the cap asked as each player's source is made, as the engine's
     * is ([start]); with the memory unread, 8 MiB.
     */
    val playerDataSources = SourceDataSource.Factory(
        mapOf(
            PlayUris.USENET to usenetSources,
            PlayUris.SERVICE to ReadAheadDataSource.Factory(serviceSources) { memory?.let(PlaybackBudgets::streamBytes) },
        ),
    )

    /** Made by the line's first sample, off the main thread: it adds a registry to Micrometer's and reads /proc. */
    private val streamCostSampler = lazy {
        StreamCostSampler(
            streamTraffic, ThreadCpuReader(ticksPerSecond = clockTicksPerSecond(), files = androidKeptFiles), ConnectionMeter(), ::artGcStats,
            threadCpuNanos = Debug::threadCpuTimeNanos,
        )
    }

    /**
     * The stream-cost line in the log, every 10 s while a stream plays, as the player says ([StreamCostMonitor]): what
     * each stream and connection moves, and the CPU per MB by thread group, for a Shield run to need only logcat. The
     * threads' files the sampler keeps open go when the play stops (none were opened if it stopped before a sample).
     */
    val streamCost = StreamCostMonitor(
        appScope,
        sample = { streamCostSampler.value.sample() },
        done = { if (streamCostSampler.isInitialized()) streamCostSampler.value.close() },
    )
    val availability = EngineAvailabilityBackend(engine, checkAll = { settings.playback.first().checkAllArticles })
    val trackProbe by lazy { ExoTrackProbe(context, dataSourceFactory) }

    val newznab = NewznabClient(NewznabClient.defaultHttpClient())
    val caps = CapsCache(newznab)
    val multiSearch = MultiIndexerSearch(newznab, caps)

    /**
     * Every search's: [multiSearch], each torrent indexer's torrents checked against the service accounts that are on and
     * open as its answer comes (L199), never holding Usenet's results or another indexer's.
     */
    val checkedSearch by lazy { CheckedSearch(multiSearch, check = DebridCacheCheck({ services.usable() })) }
    val finder by lazy { EpisodeReleaseFinder(checkedSearch) }

    val playback by lazy { io.skjaere.usenet.app.play.PlaybackCoordinator(this, appScope) }

    /**
     * The releases found missing on the providers the engine uses, which plays skip without a grab until one it wasn't
     * checked on is; looked up by the providers once the engine has checked them, as a play's first use would.
     */
    val missingReleases = MissingReleases(MissingReleases.Store.of(library), inUse = { engine.inUse }, checkedInUse = engine::checkedInUse)

    /** What plays remember of the torrents service accounts failed (L174): an hour in memory, a removal 30 days in the library. */
    val serviceMisses = ServiceMisses(MissingReleases.Store.of(library))

    /** .nzb files other apps opened with this one, on their way to the Import screen. */
    val nzbHandoff = NzbHandoff()

    /** How each provider's last Test went during this app session, for the providers list. */
    val providerTests = ProviderTests()

    /**
     * MediaCodecList is slow to query: probed once, off the main thread, warmed up by [start]. The display is read
     * on every call, as it changes while the app runs (a box's output mode, a TV's HDR after it wakes).
     */
    val device = DeviceCapabilitiesLoader(context)

    /** The TV's mode kept across a hand-over between two plays (auto-advance, Previous/Next, Try another). */
    val displayHold = DisplayModeHold(appScope, { log.info(it) })

    /** The HDMI audio route as the app follows it ([followAudioRoute]); null until [start] has registered it. */
    @Volatile
    var audioRoute: AudioRouteWatcher? = null
        private set

    /**
     * Follows the HDMI audio route for the process's life, on every form factor, so a receiver or soundbar switched on
     * after the app started (or a TV that wakes) counts for the next ranking, the log header and This device without a
     * restart ([DeviceCapabilitiesLoader.audioRouteChanged]). On the main looper, as Media3's receiver wants; after the
     * decoder probe, whose reading its first is, so that changes nothing. A phone's route seldom changes, and nothing
     * runs until it does.
     */
    private suspend fun followAudioRoute() = withContext(Dispatchers.Main) {
        if (audioRoute != null) return@withContext
        val watcher = AudioRouteWatcher(context, device::audioRouteChanged)
        device.audioRouteChanged(watcher.start())
        audioRoute = watcher
    }

    /**
     * The device's memory, asked of the system once (a call to another process), off the main thread
     * as [start] begins: it caps the streams' read-ahead and sizes the image loader's memory cache.
     * Null when the system can't say: the streams then hold ahead with no cap in bytes, and the
     * images get Coil's default cache.
     */
    private val memoryRead: Lazy<DeviceMemory?> = lazy {
        try {
            DeviceMemory.of(context)
        } catch (e: Exception) {
            log.warn("Couldn't read the device's memory", e)
            null
        }
    }
    val memory: DeviceMemory? by memoryRead

    /**
     * rapidyenc's version and the kernels it picked, read once a process by [start] after the decoders (loading the
     * library is a read of storage), a failure to load kept as the fact: for This device and the log header.
     */
    val native = NativeFactsLoader()

    /** The display's modes and its frame-rate matching as last read ([readDisplayModes]); null before. */
    @Volatile
    var displayModes: DisplayModes? = null
        private set

    /** The default display's modes now, kept for the log header: read by [start], and by This device as it opens and resumes. A few cheap calls. */
    fun readDisplayModes(): DisplayModes = DisplayModes.read(context).also { displayModes = it }

    /**
     * What This device shows and a log's header says of the device, from what has been read: the memory once [start]
     * has read it, the display and decoders as the last ranking (or [start]) found them, the modes and rapidyenc as last
     * read. Never read here, so that writing a crash's log doesn't ask the system. [refreshRate] and [tvLayouts] are the
     * page's own; [settled]: every read has been tried.
     */
    fun thisDevice(refreshRate: Float? = null, tvLayouts: Boolean? = null, settled: Boolean = false): ThisDevice {
        val capabilities = device.latest
        return ThisDevice(
            Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty(), Build.VERSION.RELEASE.orEmpty(), Build.VERSION.SDK_INT,
            television = if (televisionRead.isInitialized()) television else null,
            abis64 = Build.SUPPORTED_64_BIT_ABIS.toList(), abis32 = Build.SUPPORTED_32_BIT_ABIS.toList(),
            memory = if (memoryRead.isInitialized()) memory else null,
            display = capabilities?.display, refreshRate = refreshRate, modes = displayModes, decoders = capabilities?.decoderSummary,
            native = native.latest, tvLayouts = tvLayouts, settled = settled,
        )
    }

    /** What app and device a log is from, atop a shared log and the last crash's: never anything about the user ([thisDevice]). */
    val logHeader: String
        get() = thisDevice().header(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), BuildConfig.DEBUG)

    /** Settings › Logs' log of the last crash, among the app's files: the system may clear the cache before it is read. */
    val crashLog = CrashLog(File(context.filesDir, "$LOGS_DIR/last-crash.txt")) { logHeader }

    /** Whether MainActivity is started: the lists' refresher asks TMDB only then (not while the player streams). */
    val foreground = MutableStateFlow(false)

    /** Whether any of the app's activities is in front, the player's too (L31): Play on TV's listener runs only then. */
    val inFront = InFront(appScope)

    /** Play on TV's listener on a TV (L4); made by [start] on a TV alone. */
    val tvLink: TvLinkHost by lazy { TvLinkHost(appScope, TvLinkDeps.of(this, context)) }

    /** What a TV does with its paired phones' commands (L8, L32), on the main thread. */
    val tvPlays: TvPlays by lazy { TvPlays(TvPlaysDeps.of(this), appScope) }

    /** What a TV owes its paired phones of where their plays stopped (W5, L33), in link.json. */
    val tvFinals: TvFinals by lazy { TvFinals.of(this) }

    /** What a TV says of its plays to its paired phones' links (L34), and what it owes them (L33). */
    val tvPhoneLinks: TvPhoneLinks by lazy { TvPhoneLinks.of(this) }

    /** The player in front, while one is started, and what it plays (L8, L34). */
    val playerInFront = PlayerInFront()

    /** Whether "Set up with your phone" is started, which a phone's Play waits out (L32). */
    val setupPages = SetupPages()

    /** Play on TV's links to the paired TVs, on a phone or tablet (L36); started by [start] there alone. */
    val tvLinks: TvLinks by lazy { TvLinks(appScope, TvLinksDeps.of(this, context)) }

    /**
     * Where Play goes, This phone, a paired TV or a Cast device (W11, L77), in memory alone (W18); always This phone on a TV,
     * which has no choice. A Cast session is castExtras' (none in a build without Cast), and Play on ends it as This phone
     * or a TV is chosen (L85).
     */
    val playOn: PlayOn by lazy {
        if (television) PlayOn(appScope, flowOf(emptyList()), flowOf(emptyMap())) { AutoCloseable {} }
        else PlayOn(appScope, data.link.tvs, tvLinks.states, castExtras?.now ?: NoCast, endCast = { castExtras?.stop() }, hold = tvLinks::holdTv)
    }

    /**
     * What starts a cast play on its device (L65, L85): castExtras', asked each time, so a device test's stands in; none on
     * a TV, nor in a build without Cast.
     */
    fun castLauncher(): CastLauncher? = if (television) null else castExtras?.launcher(context)

    /**
     * Whether this phone looks for Cast devices (W46, L112): "Look for Cast devices", as settings.json has it, null until
     * it is read. The Play on list's gate reads it as the list opens, null as off, so Google's Cast software never starts
     * before the choice is known. Read from [start] on, on a phone alone: a TV's is null for good.
     */
    val castLooking: StateFlow<Boolean?> by lazy {
        if (television) MutableStateFlow<Boolean?>(null) else settings.cast.map { it.look }.distinctUntilChanged().stateIn(appScope, SharingStarted.Eagerly, null)
    }

    /**
     * Whether the Cast button's introduction was shown on this phone (W48, L123), as settings.json has it, null until it is
     * read: the shell shows it once, while this says false. Read from [start] on, on a phone alone: a TV's is null for good.
     */
    val castIntroShown: StateFlow<Boolean?> by lazy {
        if (television) MutableStateFlow<Boolean?>(null) else settings.cast.map { it.introShown }.distinctUntilChanged().stateIn(appScope, SharingStarted.Eagerly, null)
    }

    private val castAvailableNow = MutableStateFlow<Boolean?>(null)

    /**
     * Whether Google Play services is on this phone and up to date (L146), which the Cast button's face and the
     * introduction need before anyone looks for Cast devices: read through castExtras ([refreshCastAvailable]), which
     * reads package facts and starts nothing of Google's. Null until read (and on a TV, never read); false in a build
     * without Cast's extras.
     */
    val castAvailable: StateFlow<Boolean?> = castAvailableNow.asStateFlow()

    /**
     * Reads [castAvailable] again: as the app starts on a phone, and at each MainActivity start, as Play services may have
     * been installed or updated meanwhile. Never on a TV. Off the main thread. A device test's fakes read it through here
     * too, as a test that composes its screens never starts MainActivity.
     */
    @WorkerThread
    fun refreshCastAvailable() {
        if (television) return
        castAvailableNow.value = castExtras?.available(context) ?: false
    }

    /** The app's name, as the system shows it: what a media session says of a play not TMDB's (L63, L89). */
    val appName: String get() = context.getString(R.string.app_name)

    /** The Show and Movie pages' and Up next's plays, to the coordinator or the TV Play on names (L7). */
    val playRouter: PlayRouter by lazy { PlayRouter(PlayRouterDeps.of(this)) }

    /** A play started on a TV from this phone, which its play sheet mirrors (L37). */
    val tvStart: TvStart by lazy { TvStart(appScope, TvStartDeps.of(this)) }

    /** The play this phone started on a TV, which the Playing on bar and the Show page follow (L38, W14). */
    val tvPlaying: TvPlaying by lazy { TvPlaying(appScope, TvPlayingDeps.of(this)) }

    /** The cast play the bar, the remote and the Show page follow (L91): castExtras' feed; none on a TV, nor in a build without Cast. */
    val castPlaying: CastPlaying? by lazy { if (television) null else castExtras?.let { CastPlaying(appScope, CastPlayingDeps.of(this, it, context)) } }

    /**
     * What the Playing on bar, the remote and the Show page follow (L91): the cast while one lives, else the play this phone
     * started on a TV (L38). A cast's notification opens the remote only while it has a play (L86); on a TV, never.
     */
    val remotePlaying: RemotePlaying by lazy { if (television) NoRemote else RemotePlays(appScope, tvPlaying, castPlaying) }

    /** Where this phone's plays on TVs stopped, saved as its own progress (W5, L33); started by [start] on phones alone. */
    val phoneFinals: PhoneFinals by lazy { PhoneFinals(appScope, PhoneFinalsDeps.of(this)) }

    /** The device's local date, for the lists and Home's Up next. */
    val today: Flow<LocalDate> = Today(DeviceClock, foreground).dates.shareIn(appScope, SharingStarted.WhileSubscribed(), replay = 1)

    /**
     * What Home shows by, the first-run screen or Home itself: null until the settings are read. One
     * for Home and for MainActivity's splash, which waits for it, so Home has it as the splash goes;
     * read from the first ask on, for as long as the process lives.
     */
    val setup: StateFlow<SetupState?> =
        flow {
            // A debug or beta build's first launch saves its dev settings first, so the first-run screens don't show for
            // the moment that takes (the splash's timeout still bounds the wait).
            devSettingsSaved.await()
            emitAll(
                combine(settings.settings, settings.hasTmdbKey) { s, key ->
                    // Usenet's pair, or the torrent pair while debrid shows (L165, L192).
                    SetupState(
                        s.activeProviders.map { it.displayName }, s.activeIndexers.isNotEmpty(), key, s.indexerSetupSkipped,
                        services = s.activeServices.map(::nameOf).sorted(), hasTorrentIndexer = s.activeTorrentIndexers.isNotEmpty(),
                        shown = DebridGate.shown,
                    )
                },
            )
        }.stateIn(appScope, SharingStarted.Lazily, null)

    /** Done once UsenetApplication's seedDevSettings has run (debug and beta builds); done from the start in the others. */
    val devSettingsSaved = CompletableDeferred<Unit>().apply { if (!BuildConfig.SEED_DEV_SETTINGS) complete(Unit) }

    /** The collection's rows, one set for Home and the Collection tab. */
    val collection = CollectionStore(library, today, settings.hasTmdbKey, appScope)

    /** Keeps the lists' TMDB facts fresh while the app is in front with a key: four at a time, at most 20 requests a second. */
    val refresher = SummaryRefresher(
        LibrarySummaryStore(library), TmdbTitleSource(this), DeviceClock, appScope,
        RateGate(minIntervalMs = 50) { SystemClock.elapsedRealtime() },
    )

    /** The indexers a search asks: the Usenet ones, and the torrent ones while a service account is on and debrid shows (L194). */
    suspend fun indexerConfigs(): List<IndexerConfig> {
        val serviceOn = accountsOn().isNotEmpty()
        val shown = DebridGate.shown
        // The torrent indexers' keys are read only when they will be searched.
        val torznab = if (serviceOn && shown) settings.searchableTorrentIndexers() else emptyList()
        return IndexerConfigs.searchable(settings.searchableIndexers(), torznab, serviceOn, shown)
    }

    /**
     * Why no provider is usable right now, said at once rather than after waiting on the engine; null while one is. What
     * plays from Usenet alone asks it: Import's file, played here or cast.
     */
    suspend fun noUsableProvider(): NoUsableProvider? = NoUsableProvider.of(settings.providers.first(), settings.usableProviders().size)

    /** The debrid services' HTTP, one client for every account (L156), made when a service is first asked. */
    private val serviceHttp = lazy { ServiceHttp.defaultHttpClient() }

    /**
     * The user's debrid service accounts and their clients (L196), at the services' own addresses unless a test points them
     * elsewhere; a TorBox account's client tells [debridAdds] what its plays add (#318).
     */
    val services: ServiceRegistry = ServiceRegistry(
        settings::servicesWithKeys, settings::markKeyRefused, ServiceRegistry.clients({ serviceHttp.value }) { debridListings.handleOf(it) },
        addWatch = { debridAdds.watch(it) },
    )

    /** What the debrid services prepared for the releases played from them (L181), kept as the NZB store keeps NZBs. */
    val debridListings = DebridListingStore(File(context.filesDir, "$DEBRID_DIR/listings.json"))

    /**
     * What this device's plays added to a TorBox account (#318, L232-L234, L245): removed a couple of minutes after nobody
     * plays it, by the account's client while it is on and open, never while debrid is hidden.
     */
    val debridAdds: DebridAdds = DebridAdds(
        File(context.filesDir, "$DEBRID_DIR/added.json"),
        accounts = { settings.services.first() },
        usable = { id -> services.usable().firstOrNull { it.account.id == id } },
        scope = appScope,
    )

    /** The links debrid plays read their files by (L172): Opening's first, then fresh ones from the account, in memory only. */
    val serviceLinks by lazy { ServiceLinks(debridListings, { id -> services.service(id)?.service }) }

    /** The service accounts that are on, by id, in the user's order: none while debrid is hidden (L192). */
    private suspend fun accountsOn(): List<String> = services.on().map { it.id }

    /**
     * Whether a service account is on, as it changes, for the lines that tell whether torrent indexers are searched
     * (Settings' Torrent indexers row, L166): [accountsOn], never while debrid is hidden.
     */
    val serviceOn: Flow<Boolean> = settings.services.map { accountsOn().isNotEmpty() }.distinctUntilChanged()

    /**
     * The service accounts on, in the user's order, by id with their services' names, as they change (a move included):
     * what a release's tag says it plays from (L201, L247). Empty while none is on, or debrid is hidden, and then the lists
     * show no tags or marks.
     */
    val serviceNames: Flow<List<NamedAccount>> = accountsInOrder(settings.services) { services.on() }

    /** The routes a play may take now (L165): Usenet's while a provider is usable, and each service account's that is on. */
    suspend fun usableRoutes(): UsableRoutes = UsableRoutes.of(settings.usableProviders().isNotEmpty(), accountsOn())

    /**
     * Why nothing can be streamed right now, said before a play searches; null while a provider is usable or a service
     * account is on. A user with only service accounts, all off, is told so (none while debrid is hidden).
     */
    suspend fun noUsableSource(): NoUsableProvider? {
        val usable = settings.usableProviders().size
        val saved = servicesSaved(settings.services.first(), DebridGate.shown)
        return noUsableSource(UsableRoutes.of(usable > 0, accountsOn()), settings.providers.first(), usable, saved)
    }

    /** Whether this is a TV (not a layout's choice: a phone showing the TV's layouts still ranks as a phone); asked once, by [start] first. */
    private val televisionRead = lazy { FormFactor.of(context) == FormFactor.Tv }
    val television: Boolean by televisionRead

    /**
     * What releases are ranked by, for whom Play goes to (L127): inside a play's job, the job's own device; else the Cast
     * device chosen in Play on; else this device. Never a Cast device on a TV.
     */
    suspend fun rankingContext(): RankingContext =
        rankingContext(if (television) null else rankingTargetOf(currentCoroutineContext()[PlayOrigin], playOn.target.value, castProfiles()))

    /**
     * What releases are ranked by for the Cast device [cast] (its profile, by its name, L126), or for this device (null):
     * the device as it is now (its display read again each time), the settings, and whether it is a TV.
     */
    suspend fun rankingContext(cast: CastRanking?): RankingContext {
        val playback = settings.playback.first()
        return RankingContext(
            device = cast?.profile ?: device.get(),
            prefs = playback.rankingPrefs(Locale.getDefault().language),
            tv = television,
            target = cast?.target ?: RankingTarget.Device,
        )
    }

    /** The Cast devices' profiles, with what each has shown (L128). */
    private suspend fun castProfiles() = CastProfiles(settings.cast.first().devices)

    /** What the Cast device a play goes to ([to]) plays, by its model and what it has shown (L128): what its file is judged by (L129). */
    suspend fun castProfile(to: CastTo): CastProfile = castProfiles().of(to.device, to.model)

    /**
     * What a release list on screen ranks again by as a choice is made in Play on (L127): the Cast device chosen once its
     * session says what it is, by what it has shown by then, or this device for This phone or a TV. Nothing as This phone
     * comes back by itself ([listRankingOf]), nor as the device shows more of itself ([listRankings]). None on a TV.
     */
    val listRankings: Flow<RankingContext> by lazy {
        if (television) {
            emptyFlow()
        } else {
            listRankings(playOn.picked, playOn.target, settings.cast.map { it.devices }).map { rankingContext(it.cast) }
        }
    }

    /**
     * Keeps the engine's providers, the video cache and the logging in step with the saved settings;
     * probes the decoders early so the first search rarely waits.
     */
    fun start() {
        appScope.launch {
            // After the decoders, which the first search waits for; then the rest of what the header says, read once
            // here so that it never asks, and its lines the log doesn't otherwise get, once a start (This device's
            // "The same facts go into the log each time the app starts"; on a TV the header can't be read elsewhere).
            readDeviceFacts(
                listOf(
                    "probe the decoders" to suspend { device.get() },
                    "follow the audio route" to suspend { followAudioRoute() },
                    "read rapidyenc's facts" to suspend { native.get() },
                    "read the display's modes" to suspend { readDisplayModes() },
                    "read the device's kind" to suspend { television },
                    "read the device's memory" to suspend { memory },
                ),
            ) { headerLinesToLog(logHeader, device.latest?.lines.orEmpty()).forEach { log.info(it) } }
        }
        // Off the main thread, before a screen's first image needs it.
        appScope.launch { memory }
        // A TV listens for paired phones while the app is in front (L4); a phone reaches its TVs (L36), hears where its
        // plays there stopped (L33), and follows again a play of its own a TV says it plays.
        appScope.launch {
            if (television) {
                tvLink.start()
            } else {
                tvLinks.start()
                phoneFinals.start()
                tvPlaying.start()
                // Read now, so the Play on list's gate has the opt-in (L112), the Cast button its face (L146) and the shell
                // whether to introduce it (L123) as the first screen shows.
                castLooking.value
                castIntroShown.value
                refreshCastAvailable()
            }
        }
        appScope.launch {
            saveOrLog("apply the logging setting") {
                settings.logs.map { it.isDetailed(BuildConfig.DEBUG) }.distinctUntilChanged().collect { AndroidLogConfig.detailed = it }
            }
        }
        appScope.launch { saveOrLog("forget the releases found incomplete a month ago") { library.pruneTriedReleases() } }
        // What plays added to a TorBox account that an earlier process left goes a grace from now (L245), and the records
        // of an account signed out of, switched to keep its adds or given a new key go as it changes.
        debridAdds.start(settings.services)
        appScope.syncEngine(
            settings.providers, settings::usableProviders, engine::configure,
            settings.cache.map { it.keepVideo to it.limitBytes }, videoCache::apply,
            // The cap on every play's streams' read-ahead.
            readAheadBytes = { memory?.let(PlaybackBudgets::streamBytes)?.also { log.debug("Streams' read-ahead is capped at {} bytes", it) } },
        )
        // A new key emits again, which starts the refresher over (one TMDB rejected stops it).
        refresher.start(combine(foreground, settings.hasTmdbKey) { front, key -> front && key })
        appScope.launch {
            saveOrLog("refresh the lists in the new TMDB language") {
                settings.metadata.map { it.language }.distinctUntilChanged().drop(1).collect { refresher.invalidateAll() }
            }
        }
    }

    companion object {
        /**
         * The last crash's log's folder under filesDir: the log before the crash and the device's header, which leave the
         * device only when shared from Settings › Logs (L139), so backups and transfers exclude it by this path.
         */
        const val LOGS_DIR = "logs"

        /**
         * The debrid folder under filesDir: the listings kept for this device's plays and the records of what they added to
         * a service account, the device's own (L233), so backups and transfers exclude it by this path.
         */
        const val DEBRID_DIR = "debrid"
    }
}

/**
 * Keeps the engine's [providers] and the video [cache] (whether to keep video, the limit) in step with
 * the saved settings, side by side: opening the cache walks its directory, seconds for a large one on
 * slow storage, and a play started meanwhile needs the providers, not the cache. A cache that opens
 * after the streamer is built reaches it as the engine builds it again, once idle (VideoCache's opened).
 */
internal fun CoroutineScope.syncEngine(
    providers: Flow<List<ProviderRecord>>,
    usable: suspend () -> List<ProviderWithPassword>,
    configure: suspend (EngineSettings) -> Unit,
    cache: Flow<Pair<Boolean, Long>>,
    applyCache: suspend (keep: Boolean, limitBytes: Long) -> Unit,
    readAheadBytes: suspend () -> Long? = { null },
) {
    // Named for the engine's lock lines, which name who holds it and who waits for it (#273).
    launch(CoroutineName("provider sync")) { syncProviders(providers, usable, configure, readAheadBytes) }
    launch(CoroutineName("video cache sync")) {
        cache.distinctUntilChanged().collect { (keep, limit) -> saveOrLog("apply the video cache settings") { applyCache(keep, limit) } }
    }
}

private val log = LoggerFactory.getLogger(AppContainer::class.java)

/**
 * The app's scope, for work that outlives the screen that starts it: saves on leaving the player,
 * the play sheet's jobs, the provider sync. Each handles its own failures; [onFailure] gets one
 * that slipped through, which would otherwise crash the app, and the scope's other work goes on.
 */
internal fun AppScope(onFailure: (Throwable) -> Unit = { log.error("Background work failed", it) }): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> onFailure(e) })

/**
 * [AppContainer.start]'s [reads] of what This device and the log header say, in order, each named by what it reads;
 * then [logged], once they are done. Each on its own: one that fails (a box's codec service gone under the decoder
 * probe) is logged as "Couldn't …", and the rest are still read, so the header and the log still say them.
 */
internal suspend fun readDeviceFacts(reads: List<Pair<String, suspend () -> Any?>>, logged: () -> Unit) {
    reads.forEach { (what, read) -> saveOrLog(what) { read() } }
    logged()
}

/**
 * Runs [save], a write the app can go on without (progress, a track choice, a release to forget):
 * a failure such as a full disk is logged, as "Couldn't [what]", and the app goes on.
 */
internal suspend fun saveOrLog(what: String, save: suspend () -> Unit) {
    try {
        save()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Couldn't {}", what, e)
    }
}

/**
 * Configures [configure]'s engine with the [usable] providers, now and whenever the [saved] ones
 * change, its streams' read-ahead capped at [readAheadBytes] (the device's budget, read once). A
 * change that can't be applied (a password unreadable) is logged; the next is applied. A budget
 * that can't be read is logged too, and the streams then have no cap in bytes: the providers are
 * configured all the same.
 */
internal suspend fun syncProviders(
    saved: Flow<List<ProviderRecord>>,
    usable: suspend () -> List<ProviderWithPassword>,
    configure: suspend (EngineSettings) -> Unit,
    readAheadBytes: suspend () -> Long? = { null },
) {
    val ahead = try {
        readAheadBytes()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Couldn't read the device's memory: streams hold ahead with no cap in bytes", e)
        null
    }
    saved.distinctUntilChanged().collect {
        try {
            val providers = usable().mapIndexed { index, p ->
                ProviderConfig(p.record.id, p.record.name, p.record.host, p.record.port, p.record.username, p.password.orEmpty(), p.record.useTls, p.record.connections, priority = index)
            }
            configure(EngineSettings(providers, readAheadBytes = ahead))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Couldn't apply the saved providers to the engine", e)
        }
    }
}

class UsenetApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    /**
     * The app went to the background: the video cache's index is written, as Android kills processes
     * there without warning (what the index doesn't list is taken in again on the next start, but
     * when each segment was last watched is only in the index).
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) container.appScope.launch { saveOrLog("save the video cache") { container.videoCache.save() } }
    }

    /**
     * The memory the image loader is sized for: the device's. A test seam: a test stands in a low-RAM
     * box's, as the lane's devices aren't, to check the loader this makes.
     */
    @VisibleForTesting
    @Volatile
    internal var imageLoaderMemory: () -> DeviceMemory? = { container.memory }

    /** Coil's image loader for every screen, made on first use: its memory cache follows the device's memory. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = appImageLoader(context, imageLoaderMemory())

    override fun onCreate() {
        super.onCreate()
        // Detailed in debug builds until the saved choice is read (AppContainer.start).
        AndroidLogConfig.detailed = BuildConfig.DEBUG
        container = AppContainer(this)
        container.crashLog.install()
        // Before any activity starts: the first one counts.
        registerActivityLifecycleCallbacks(container.inFront)
        if (BuildConfig.SEED_DEV_SETTINGS) {
            container.appScope.launch {
                try {
                    seedDevSettings(container)
                } finally {
                    container.devSettingsSaved.complete(Unit)
                }
            }
        }
        container.start()
    }
}

/**
 * Debug and beta builds: a provider from local.properties' devProvider.* (else, in debug, the mock NNTP
 * server on the emulator host) while none was ever saved, the devIndexer.N.* indexers likewise (a
 * removed one leaves its record behind, so neither comes back), and the devTmdbKey on the first launch.
 * Debug saves the key again at every start without one, as its device tests expect. Debug alone saves
 * the debrid dev accounts and torrent indexers ([seedDevDebrid], L182), unless a device run turned that
 * off. Never in release or bench builds.
 */
private suspend fun seedDevSettings(container: AppContainer) {
    val settings = container.settings
    val dev = BuildConfig.DEV_PROVIDER_HOST.isNotBlank()
    val saved = settings.settings.first()
    val firstLaunch = saved.providers.isEmpty() && saved.indexers.isEmpty()
    if (saved.providers.isEmpty() && (dev || BuildConfig.DEBUG)) {
        settings.saveProvider(
            ProviderRecord(
                id = "",
                name = if (dev) BuildConfig.DEV_PROVIDER_HOST else "Mock (10.0.2.2)",
                host = if (dev) BuildConfig.DEV_PROVIDER_HOST else "10.0.2.2",
                port = (if (dev) BuildConfig.DEV_PROVIDER_PORT else "1119").toIntOrNull() ?: 563,
                username = if (dev) BuildConfig.DEV_PROVIDER_USERNAME else "",
                useTls = if (dev) BuildConfig.DEV_PROVIDER_TLS.ifBlank { "true" }.toBoolean() else false,
                connections = (if (dev) BuildConfig.DEV_PROVIDER_CONNECTIONS else "4").toIntOrNull() ?: 8,
            ),
            password = if (dev) BuildConfig.DEV_PROVIDER_PASSWORD else "",
        )
    }
    if (saved.indexers.isEmpty()) {
        DevIndexers.parse(BuildConfig.DEV_INDEXERS).forEach { settings.saveIndexer(id = null, name = it.name, baseUrl = it.baseUrl, apiKey = it.apiKey) }
    }
    if (BuildConfig.DEV_TMDB_KEY.isNotEmpty() && (firstLaunch || BuildConfig.DEBUG) && settings.tmdbKey() == null) {
        settings.setTmdbKey(BuildConfig.DEV_TMDB_KEY)
    }
    if (BuildConfig.DEBUG) seedDevDebrid(settings)
}

val Context.container: AppContainer
    get() = (applicationContext as UsenetApplication).container
