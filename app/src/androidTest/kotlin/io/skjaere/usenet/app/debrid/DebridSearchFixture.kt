package io.skjaere.usenet.app.debrid

import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.everyStep
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.EpisodeRef
import io.skjaere.usenet.data.LibraryRepository
import io.skjaere.usenet.data.MovieMark
import io.skjaere.usenet.data.ServiceSignIn
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.debrid.ServiceKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.net.InetSocketAddress
import java.net.Socket
import java.time.LocalDate

/**
 * Search over the fakes (L189): the shared fake indexer (10.0.2.2:8089, mock-seed indexer) as "Indexer A", the Torznab
 * fake's Prowlarr feed as "Home Prowlarr" and, with [quarry], its Jackett feed as "Quarry" with "Use with your debrid
 * service" off; TorBox signed in on the debrid fake (and Premiumize, first in the order, by [premiumizeFirst]). Every
 * other indexer is switched off, and the saved torrent indexers and service accounts are hidden for the test (tombstoned,
 * their keys untouched). TMDB is answered by [FakeTmdb]: no
 * titles for anything typed, and Harbor Lights (FakeCatalog's ids) with its first season's three episodes. The debrid
 * fake is held for the test, as its switches are one set for every emulator. What the library remembers of the plays of
 * the debrid classes' titles is forgotten before the test and after it ([forgetPlays]). [close] puts everything back, the
 * test's own keys removed.
 */
class DebridSearchFixture(private val quarry: Boolean = false) : AutoCloseable {
    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container
    private val settings = container.settings
    private val home = HomeContentShown()
    private lateinit var saved: AppSettings
    private var tmdb: FakeTmdb? = null
    private var giveBack: (() -> Unit)? = null
    private var pointBack: (() -> Unit)? = null
    private var service: String? = null
    private var premiumizeAccount: String? = null

    /** TorBox's account, signed in on the fake. */
    val torBox: String get() = checkNotNull(service) { "not open" }
    private val torrentIndexers = mutableListOf<String>()

    val today: LocalDate = LocalDate.now()

    val show = ShowDetails(
        HARBOR_LIGHTS, "Harbor Lights", null, 2021, null, null, null, "Returning Series", true, 45,
        listOf(SeasonSummary(1, null, 3, today.minusDays(60), null)),
        lastEpisode = EpisodeSummary(1, 3, EPISODES[2], today.minusDays(46)), nextEpisode = null,
        ids = ExternalIds(tvdb = 900001, imdb = "tt9000003"),
    )

    private val season = Season(HARBOR_LIGHTS, 1, null, null, EPISODES.mapIndexed { i, name -> Episode(1, i + 1, name, null, today.minusDays(60L - 7 * i), 45, null) })

    fun open() = runBlocking<Unit> {
        fun reachable(port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress(DebridFakes.HOST, port), 2_000) } }.isSuccess
        assumeTrue("the fake indexer isn't reachable at 10.0.2.2:8089 (mock-seed indexer)", reachable(8089))
        DebridFakes.assumeTorznab()
        DebridFakes.assumeDebrid()
        assumeTrue("debrid is hidden in this build", DebridGate.shown)
        // A fresh install's first test can come before the debug build's own settings are saved, which Home's content needs.
        container.devSettingsSaved.await()
        forgetPlays(container.library)
        home.show()
        saved = settings.settings.first()
        settings.restore(saved.copy(torrentIndexers = saved.torrentIndexers.map { it.copy(deleted = true) }, services = saved.services.map { it.copy(deleted = true) }))
        settings.indexers.first().filter { it.enabled }.forEach { settings.setIndexerEnabled(it.id, false) }
        settings.saveIndexer(INDEXER_A, "Indexer A", "http://10.0.2.2:8089", "fake-indexer-key")
        torrentIndexers += settings.saveTorrentIndexer(null, "Home Prowlarr", DebridFakes.PROWLARR_URL, DebridFakes.TORZNAB_KEY)
        if (quarry) torrentIndexers += settings.saveTorrentIndexer(null, "Quarry", JACKETT_URL, DebridFakes.TORZNAB_KEY, useWithService = false)
        giveBack = DebridFakes.holdTheFake()
        DebridFakes.control("reset")
        pointBack = DebridFakes.pointServicesAtTheFake()
        service = settings.saveService(ServiceKind.TorBox.name, DebridFakes.TORBOX_KEY) { ServiceSignIn(DebridFakes.TORBOX_ACCOUNT) }
        tmdb = FakeTmdb(shows = mapOf(HARBOR_LIGHTS to show), seasons = { id, number -> season.takeIf { id == HARBOR_LIGHTS && number == 1 } }, suggestions = { emptyList() })
    }

    /** Holds the debrid fake's cache checks, or lets them go ([on] false): a search's torrents stay being checked meanwhile. */
    fun holdChecks(on: Boolean) {
        DebridFakes.control("hold-check?on=$on")
    }

    /**
     * Premiumize signed in on the fake too, and moved first (L228): the order as every device's starts, A to Z, Premiumize
     * tried before TorBox. Its account's id.
     */
    fun premiumizeFirst(): String = runBlocking {
        val id = settings.saveService(ServiceKind.Premiumize.name, DebridFakes.PREMIUMIZE_KEY) { ServiceSignIn(DebridFakes.PREMIUMIZE_ACCOUNT) }
        premiumizeAccount = id
        settings.moveService(id, -1)
        id
    }

    /** Switches TorBox off, as its page's "Use this service" does, or back on. */
    fun serviceOn(on: Boolean) = runBlocking<Unit> { service?.let { settings.setServiceEnabled(it, on) } }

    /** Indexer A, Usenet's: off for a play of torrents alone (L189), or back on. */
    fun usenetIndexerOn(on: Boolean) = runBlocking<Unit> { settings.setIndexerEnabled(INDEXER_A, on) }

    /** Home Prowlarr's "Use with your debrid service", as its form's switch sets it. */
    fun prowlarrWithService(on: Boolean) = runBlocking<Unit> { settings.setUseWithService(torrentIndexers.first(), on) }

    /** Each step whatever the one before it did: a debrid fake that stopped answering mustn't leave the device's sources away. */
    override fun close() = runBlocking<Unit> {
        everyStep(
            { tmdb?.close() },
            { giveBack?.let { DebridFakes.control("reset") } },
            { giveBack?.invoke() },
            { pointBack?.invoke() },
            {
                if (::saved.isInitialized) {
                    // The keys go with them (restore only puts back the settings document).
                    service?.let { settings.signOut(it) }
                    premiumizeAccount?.let { settings.signOut(it) }
                    torrentIndexers.forEach { settings.deleteTorrentIndexer(it) }
                    if (saved.indexers.none { it.id == INDEXER_A } && settings.indexer(INDEXER_A) != null) settings.deleteIndexer(INDEXER_A)
                    settings.restore(saved)
                    // Nor does a class after this one find the torrents it played remembered.
                    forgetPlays(container.library)
                }
            },
            { home.restore() },
        )
    }

    companion object {
        /** FakeCatalog's Harbor Lights, as TMDB would number it. */
        const val HARBOR_LIGHTS = 900002
        /** FakeCatalog's Copper Crown, as TMDB would number it. */
        const val COPPER_CROWN = 900011
        const val INDEXER_A = "debrid-search-indexer-a"
        /** Invented episode names. */
        val EPISODES = listOf("Pilot Light", "Salt Road", "Night Ferry")

        /** The Torznab fake's Jackett-shaped feed. */
        const val JACKETT_URL = "http://${DebridFakes.HOST}:${DebridFakes.TORZNAB_PORT}/jackett/api/v2.0/indexers/fake/results/torznab"

        /**
         * Forgets, through the app's own [library], the release each title the debrid classes play last played from and
         * where it stopped (left unwatched): Harbor Lights S01E01-03 and Copper Crown, as FakeCatalog numbers them. A class
         * that played them earlier in the same run (PreviousNextDeviceTest plays Harbor Lights from Usenet) leaves its
         * release remembered with its NZB stored, which a play tries first, so a debrid case played that instead of the
         * torrent it is about (DFX-1). When each last played stays, and nothing is made for one never played.
         */
        suspend fun forgetPlays(library: LibraryRepository) {
            val episodes = (1..3).map { EpisodeRef(HARBOR_LIGHTS, 1, it, WatchTitle.episode("Harbor Lights", 1, it)) }
            (episodes.map { it.key } + WatchKey.movie(COPPER_CROWN)).forEach { key -> library.lastRelease(key)?.let { library.forgetRelease(key, it) } }
            // Marking an episode would make it a state: only those with one. Marking a film changes only one that has one.
            episodes.filter { library.state(it.key).first()?.deleted == false }.takeIf { it.isNotEmpty() }?.let { library.markEpisodes(it, watched = false) }
            library.markMovies(listOf(MovieMark(COPPER_CROWN, "Copper Crown")), watched = false)
        }
    }
}
