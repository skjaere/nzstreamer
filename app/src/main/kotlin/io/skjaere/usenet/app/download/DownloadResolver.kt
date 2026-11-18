package io.skjaere.usenet.app.download

import android.annotation.SuppressLint
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.debrid.DebridAdds
import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.app.debrid.DebridListingStore
import io.skjaere.usenet.app.debrid.ServiceLinks
import io.skjaere.usenet.app.debrid.ServiceMisses
import io.skjaere.usenet.app.debrid.ServiceSource
import io.skjaere.usenet.app.debrid.ServiceTarget
import io.skjaere.usenet.app.debrid.triedBySource
import io.skjaere.usenet.app.play.IMPORTED
import io.skjaere.usenet.app.play.IMPORT_GONE
import io.skjaere.usenet.app.play.MissingReleases
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.PlayingPack
import io.skjaere.usenet.app.play.ProbedTracksSerializer
import io.skjaere.usenet.app.play.RememberedFile
import io.skjaere.usenet.app.play.Replay
import io.skjaere.usenet.app.play.STEP_LABELS
import io.skjaere.usenet.app.play.UsableRoutes
import io.skjaere.usenet.app.play.VideoTiming
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.play.indexerGrab
import io.skjaere.usenet.app.play.playCandidate
import io.skjaere.usenet.app.play.savedRelease
import io.skjaere.usenet.app.play.shortFailure
import io.skjaere.usenet.app.play.skippedNotice
import io.skjaere.usenet.app.search.IndexerConfigs
import io.skjaere.usenet.app.search.ReleaseListMode
import io.skjaere.usenet.app.search.offersFileChoice
import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.AvailabilityBackend
import io.skjaere.usenet.core.EngineAvailabilityBackend
import io.skjaere.usenet.core.EnginePrepareBackend
import io.skjaere.usenet.core.FetchException
import io.skjaere.usenet.core.FetchedNzb
import io.skjaere.usenet.core.FileCandidate
import io.skjaere.usenet.core.FileChooser
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.NzbPreparer
import io.skjaere.usenet.core.NzbSource
import io.skjaere.usenet.core.PipelineEvent
import io.skjaere.usenet.core.PipelineStep
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.core.PlayPipeline
import io.skjaere.usenet.core.PlaybackTarget
import io.skjaere.usenet.core.PrepareBackend
import io.skjaere.usenet.core.PrepareOutcome
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.PreparedNzbStore
import io.skjaere.usenet.core.ProbedTracks
import io.skjaere.usenet.core.StepStatus
import io.skjaere.usenet.core.TrackProbe
import io.skjaere.usenet.core.TriedReleases
import io.skjaere.usenet.core.UsenetError
import io.skjaere.usenet.core.UsenetSource
import io.skjaere.usenet.core.UsenetTarget
import io.skjaere.usenet.data.Clock
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadDao
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.debrid.DebridService
import io.skjaere.usenet.debrid.ServiceFile
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.NewznabError
import io.skjaere.usenet.newznab.NewznabException
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.NzbDownload
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseRanker
import io.skjaere.usenet.releases.ReleaseReason
import io.skjaere.usenet.search.RankedItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

/**
 * Finds what a download is made from when its turn comes, in the host (L338): a play's pipeline without a sheet. The
 * releases the Download sheet ranked and kept in the row ([Download.candidates]) are tried as a play tries them: each
 * grabbed from its indexer ([fetchNzb]), every article of what would be written checked ([availability]: the whole
 * release before Open, or a pack's picked file's volumes after it, L341), prepared, the file picked (the episode's, else
 * the largest video) and probed ([probe]). Nobody is there to say which of a pack's files is the episode: a release that
 * needs that question fails its attempt and the next is tried (FileChooser.None, L339, L386). One remembered as missing on
 * the providers in use is passed over without a grab ([tried]), as a play passes it over.
 *
 * At most three releases are tried for a download, or "Releases to try" where that is lower ([attempts]), counted across
 * its resolves ([Download.tried], L340): one counts once its attempt starts, before its grab, so a process that dies
 * mid-attempt never grabs more. Each NZB grabbed is kept in the download's folder until its attempt has its answer about
 * the release ([Storage.keepGrab]). One whose attempt had none (the providers failed it: a login refused, none in reach;
 * the attempt cut short by a pause or the host's end; the volume away as it was picked) is tried again first from that
 * NZB, with no grab, and isn't counted twice; the download waits for the providers meanwhile ([Resolution.Waiting],
 * [Resolution.Unreachable]).
 *
 * The row says where it is: [DownloadState.Resolving], its reason the step's words, as the play sheet names its steps,
 * "Trying release 2 of 3" before them from the second release on. Once a release passes every step, the room on the
 * device is checked for its exact size before anything of it is kept: one that won't fit is passed over, counted, and the
 * next tried, the row failing "Won't fit" only once none that fits is left (L401). Else its NZB and password go in the
 * download's own folder (L346); its release, file, exact size, whether it is the release's one video (L342) and what the
 * probe read go in the row; and the entry the probe put in the NZB store is taken out again (L383), so downloads take
 * none of the places Resume needs there. In the release that played the title last, the file that played is picked
 * ([Replay.picker]), so a file chosen while streaming a pack is picked again with no question (L402).
 *
 * A season row (one with search facts, L392) has a turn of its own ([turn]): a copy of its season's pack that leads is
 * tried first, alone, with no grab and no search (L431); else, or once that has failed for the release, the turn's search
 * ([DownloadSearch]) finds its releases where it hasn't searched yet, written into the row before any grab; then its
 * releases. One whose release or file was chosen by hand is tried as chosen. A search that finds nothing fails it
 * (L398); an indexer's limit makes it wait, its limit's facts kept (L443); the indexers out of reach are backed off from;
 * torrents the service accounts couldn't check have the row wait for the accounts, as their own refusals do (L474).
 *
 * A grab refused for the indexer's download limit (L399), or for its API limit, which its searches meet too (L443), is no
 * answer about the release and counts no try, as an outage isn't: the indexer refuses its other releases alike, so the
 * other indexers' releases are tried, within the tries. Where the limit leaves releases untried and a try is left for
 * them, the download waits as the limit, its facts kept for the page ([LimitWait], L434), and the walk goes on to the next
 * (L399b); Resume or the app's start after its end asks again. A release whose NZB needs no request (the one kept for it,
 * its season's copy) is tried whatever limit its indexer is under.
 *
 * A download resolved before whose process died isn't grabbed again: its NZB is prepared again from its own copy and its
 * file found by name. One whose size no longer matches starts again from its first byte.
 *
 * A season pack is fetched once for its season (L394, L432): resolved for one episode while another row of the show's
 * season is still to finish, its NZB is kept once for the season ([Storage.keepPack]), and a later row's attempt of it takes
 * that copy rather than grab it again, after the NZB kept for an attempt with no answer and before the indexer, and keeps
 * it for its attempt as a grab is kept. The pack as this process last prepared it is kept too ([Storage.holdPrepare]), and
 * handed back for that NZB rather than prepared again ([heldPreparer]); a new process prepares it again, from the copy or
 * from a row's own NZB as it opens again, once, with no grab. A season's torrent pack is kept for its season by the same
 * rule, as its meta alone (L462): a later row's turn tries it first as it tries a Usenet copy, prepared again by its hash
 * on the accounts on, its episode's file picked from the listing, where it can go to a service now.
 *
 * A file of an NZB the user opened on Import (a Release row, L423, L457) has no search and no indexer: its one release is
 * Import's, whose NZB, with the password typed for it, its copy in `.packs/` gives as its grab. Its file is the one chosen on
 * Import, picked by its name and size, never another of the NZB; its row says "Opening…" until the file is checked; a copy
 * gone fails in Import's words, with nothing asked of an indexer; the memory of releases found missing neither skips nor
 * records it; and the NZB's next file in this process takes the prepare held for it ([keepPack]).
 *
 * A torrent a debrid service has cached (L459, L460) is tried by its own source, as a play tries one: prepared on the
 * service accounts on in the user's order ([accounts], the one gated lookup, L422), its file picked from the service's
 * listing before anything is added, with no question, opened and probed. Only a release from Usenet waits for the
 * providers. Its listing is kept in the row's folder before the row takes it (L420), and the debrid writer writes its file
 * from that copy ([Resolution.Ready.Service]); a row opened again reads the copy, with no call. Its account's outage,
 * refused key or limit is no answer about the release: uncounted, the row backs off or waits in the debrid service's words,
 * and its limit goes to the runner with when the account's gate opens ([Resolution.Limited], L461); what fails the release
 * there is counted, in the play sheet's words where they name no service. While debrid is hidden,
 * or for an indexer whose "Use with your debrid service" is off, a torrent goes to no service. What the attempts add to an
 * account is held while the resolve runs and let go as it ends, the chosen one's by the download's own lease first (L421).
 *
 * Its log lines, the pipeline's and the memory of releases tried's among them (which name a play's release by its title),
 * name the download by its id and a release by its place among the row's ([naming]), never by the release's name; the
 * pipeline says an attempt's failure by its kind alone, as its words can name a file.
 */
class DownloadResolver(
    private val downloads: DownloadDao,
    private val storage: Storage,
    private val availability: AvailabilityBackend,
    private val preparer: NzbPreparer,
    private val store: PreparedNzbStore,
    private val probe: TrackProbe,
    /** The indexers the candidates are grabbed from, by id. */
    private val indexers: suspend () -> List<IndexerConfig>,
    /** One grab of an NZB: the coordinator's, so a test serves both a play's and a download's. */
    private val fetchNzb: suspend (IndexerConfig, NewznabItem) -> NzbDownload,
    /**
     * What is remembered of releases found missing, for a resolve of these items (by key), as a play's that wasn't chosen,
     * its lines naming a candidate as the resolve's do ([remembered]).
     */
    private val tried: (items: Map<String, NewznabItem>, indexerName: (String) -> String?, named: (PlayCandidate) -> String) -> TriedReleases,
    /** Settings' "Releases to try". */
    private val attempts: suspend () -> Int,
    /**
     * Waits for the engine's check of the providers, as a play does before its first release: whether one is in use; null
     * where some are saved and on but the engine hasn't taken them up yet.
     */
    private val providersChecked: suspend () -> Boolean?,
    private val providersLeftOut: () -> Boolean = { false },
    private val providerFallback: () -> Boolean = { false },
    private val clock: Clock = Clock.System,
    /**
     * A preparer that hands back a season pack's prepare held from an earlier row ([Storage.heldPrepare]) for its own NZB,
     * and prepares any other as [preparer] does: the app's [PlayingPack.preparer] over the engine.
     */
    private val heldPreparer: (PreparedNzb) -> NzbPreparer,
    /** What a download's releases are ranked by now (W112): whether a season pack's copy leads is read from it (L432). */
    private val downloadContext: suspend () -> RankingContext,
    /** A season row's turn's search for its episode (L392). */
    private val search: DownloadSearch,
    /** The release that played a title last, by its watch key, as the library remembers it: its file is picked (L402). */
    private val lastRelease: suspend (watchKey: String) -> RememberedRelease? = { null },
    /** The walk's memory of the indexers' limits (L443): the runner's, which the turn's search reads too. */
    private val limits: DownloadSearch.Limits = DownloadSearch.Limits.None,
    /**
     * What the debrid services prepared, by listing id (L181): a torrent's listing is put there as it is prepared, as a
     * play's is, and the row keeps its own copy of it ([Storage.keepListing], L420).
     */
    private val listings: DebridListingStore,
    /** Account [accountId]'s service while it is on and debrid shows (L422's one gated lookup); null otherwise. */
    private val accounts: suspend (accountId: String) -> DebridService? = { null },
    /** The service accounts on, by id, in the user's order: the routes a torrent is tried by (L228, L460). */
    private val accountsOn: suspend () -> List<String> = { emptyList() },
    /** The torrent indexers whose "Use with your debrid service" is off now, by id: their torrents go to no service (L194). */
    private val offService: suspend () -> Set<String> = { emptySet() },
    /** What plays remember of the torrents service accounts failed (L174): passed over, and remembered, as a play's are. */
    private val misses: ServiceMisses? = null,
    /** The links a torrent's probe reads its file by, as a play's probe does (L172). */
    private val links: ServiceLinks? = null,
    /** What this device added to a service account: each add of a resolve is held by it until it ends (L421). */
    private val adds: DebridAdds? = null,
    /** Whether this build shows debrid (L192). */
    private val shown: () -> Boolean = { DebridGate.shown },
) {
    /** What Resolve keeps in and asks of the download's places on the device: [DownloadStorage]'s ([of]). */
    interface Storage {
        suspend fun keepSource(id: String, nzb: ByteArray, password: String?)
        suspend fun source(id: String): DownloadStorage.Source?
        suspend fun dropSource(id: String)

        /** Deletes [id]'s video; throws [DownloadStorage.Unavailable] while its volume isn't there. */
        suspend fun deleteVideo(id: String)

        /** The room on the device for [except]'s own size; null while the volume isn't there. */
        suspend fun space(except: String): DownloadSpace?

        /** Keeps the NZB grabbed for [id]'s release [key] until its attempt has its answer. */
        suspend fun keepGrab(id: String, key: String, nzb: ByteArray, password: String?)

        /** The NZB kept for an attempt of [id] that had no answer; null when none is. */
        suspend fun grab(id: String): DownloadStorage.Grab?

        suspend fun dropGrab(id: String)

        /**
         * The season pack's NZB kept once for its season under its release's [key] (L394), or a torrent's meta alone (L462);
         * null where none is.
         */
        suspend fun pack(key: String): DownloadNzbs.Copy?

        /**
         * Keeps a season pack's [nzb] and its [password] (none: no file) once for its season, as [meta] says (L432); a
         * torrent's meta alone, with no NZB (L462).
         */
        suspend fun keepPack(meta: DownloadNzbs.Meta, nzb: ByteArray?, password: String?)

        /** The last season pack's prepare kept for its season's next rows while its copy lasts; null where none is. */
        fun heldPrepare(): PreparedNzb?

        /** Keeps [prepared], the season pack [key]'s, for its season's next rows in this process (L394). */
        fun holdPrepare(key: String, prepared: PreparedNzb)

        /** The season packs' copies kept for [show]'s [season] (L431). */
        suspend fun packs(show: Int, season: Int): List<DownloadNzbs.Copy>

        /** Keeps [entry], the listing of the torrent [id] downloads, in its bookkeeping folder (L420), written whole. */
        suspend fun keepListing(id: String, entry: DebridListingStore.Entry)

        /** The listing [keepListing] kept for [id]; null when it has none. */
        suspend fun listing(id: String): DebridListingStore.Entry?

        /** Marks [id]'s file as started again from 0 once (L419), until its source is dropped. */
        suspend fun markRestarted(id: String)

        /** Whether [id]'s file was started again from 0 ([markRestarted]). */
        suspend fun restarted(id: String): Boolean

        companion object {
            fun of(storage: DownloadStorage): Storage = object : Storage {
                override suspend fun keepSource(id: String, nzb: ByteArray, password: String?) = storage.keepSource(id, nzb, password)
                override suspend fun source(id: String) = storage.source(id)
                override suspend fun dropSource(id: String) = storage.dropSource(id)
                override suspend fun keepGrab(id: String, key: String, nzb: ByteArray, password: String?) = storage.keepGrab(id, key, nzb, password)
                override suspend fun grab(id: String) = storage.grab(id)
                override suspend fun dropGrab(id: String) = storage.dropGrab(id)
                override suspend fun deleteVideo(id: String) = storage.deleteVideo(id)
                override suspend fun space(except: String) = storage.space(except)
                override suspend fun pack(key: String) = storage.packs.find(key)
                override suspend fun keepPack(meta: DownloadNzbs.Meta, nzb: ByteArray?, password: String?) = storage.packs.write(meta, nzb, password)
                override fun heldPrepare() = storage.packs.held()
                override fun holdPrepare(key: String, prepared: PreparedNzb) = storage.packs.hold(key, prepared)
                override suspend fun packs(show: Int, season: Int) = storage.packs.forSeason(show, season)
                override suspend fun keepListing(id: String, entry: DebridListingStore.Entry) = storage.keepListing(id, entry)
                override suspend fun listing(id: String) = storage.listing(id)
                override suspend fun markRestarted(id: String) = storage.markRestarted(id)
                override suspend fun restarted(id: String) = storage.restarted(id)
            }
        }
    }

    /** How a resolve ended. */
    sealed interface Resolution {
        /**
         * Found: [row] as it is now kept, its release, file and exact size, to be written from its safe offset (0 for a
         * release other than the one its file was made from), by the writer of its source (L460).
         */
        sealed interface Ready : Resolution {
            val row: Download

            /** The file's name, as its release lists it. */
            val fileName: String

            /** The file's exact size. */
            val size: Long

            /** A Usenet release: [nzb] prepared, and its [file] in it. */
            class Usenet(override val row: Download, val nzb: PreparedNzb, val file: FileCandidate) : Ready {
                override val fileName: String get() = file.name
                override val size: Long get() = file.size
            }

            /**
             * A torrent a debrid service has cached: [entry], the row's own copy of its listing (L420), and its [file] there,
             * which the debrid writer asks the listing's account for.
             */
            class Service(override val row: Download, val entry: DebridListingStore.Entry, val file: ServiceFile) : Ready {
                override val fileName: String get() = file.name
                override val size: Long get() = file.size
            }
        }

        /** Ended: the row is Failed, for [reason] in its words. */
        data class Failed(val reason: String) : Resolution

        /**
         * Can't go on until something changes: the row waits, for [reason]. The providers or storage: the host may end
         * (L388). An indexer's limit: that row alone, the walk going on to the next (L399b).
         */
        data class Waiting(val reason: String) : Resolution

        /**
         * The providers couldn't be reached ([error]'s kind), or the indexers: the row says so ([words]) while the host backs
         * off and resolves it again, as the writer does for a reopen that got no connection (L382). Nothing was counted.
         */
        data class Unreachable(val error: UsenetError, val words: String = DownloadHostRules.CANT_REACH_PROVIDER) : Resolution

        /**
         * A debrid account's limit stopped what was left to try (L461): no answer about the release, nothing counted, the row
         * as it was. The limited [account] (null where none was named) and when its gate opens ([until], the wall clock's
         * milliseconds: an hour where the service named no wait), for the runner to wait it out in place while that is
         * near, else to have the row wait for it.
         */
        data class Limited(val account: String?, val until: Long) : Resolution {
            // No account in a log line.
            override fun toString() = "Limited(until=$until)"
        }

        /** The row is gone: removed meanwhile. */
        data object Gone : Resolution
    }

    /**
     * Resolves download [id] (L338): opened again from its own NZB, or a torrent's from its listing's copy, where it has one
     * and the file is still there; else its turn ([turn]): its season's pack, its search and its candidates not tried yet,
     * as the class says. What its attempts add to a service account is born held by the resolve, as a play's is
     * ([DebridAdds.Play], L421), and let go as it ends, however it ends: found, failed, waiting, or cut short by a pause.
     */
    suspend fun resolve(id: String): Resolution {
        val play = DebridAdds.Play()
        return try {
            withContext(play) {
                val row = downloads.get(id) ?: return@withContext Resolution.Gone
                reopened(row) ?: turn(downloads.get(id) ?: return@withContext Resolution.Gone)
            }
        } finally {
            adds?.release(play)
        }
    }

    /**
     * [row]'s turn once it has no NZB of its own to open (L392, L431). A file chosen by hand, or a release chosen by hand (a
     * row with no search facts: one queued from its episode's ⋮, or replaced), is tried as chosen: its candidates. A season
     * row takes a copy of its season's pack that leads first, alone ([packFirst]); else, or once that has failed for the
     * release, the turn's search finds its releases where it hasn't searched yet ([search]); then its candidates.
     */
    private suspend fun turn(row: Download): Resolution {
        // The indexers whose download limit refused this turn's grabs (L399), by id, the latest last.
        val limited = LinkedHashMap<String, LimitWait>()
        // Those the walk keeps out of grabs for a limit an earlier row's turn met (L443), by id: their releases wait for it.
        val keptOut = limits.grabsKeptOut(indexers(), clock.now())
        if (DownloadColumns.pick(row) != null || DownloadColumns.search(row) == null) return fromCandidates(row, limited, keptOut)
        var now = row
        // The pack's failure in this turn, in its words: what the row fails with where the search then finds nothing else.
        var packFailed: String? = null
        packFirst(now)?.let { copy ->
            log.info("download {}: its season's pack first, from its copy", row.id)
            val reason = when (val pass = attempt(now, listOf(ranked(copy.meta.candidate)), { "download ${row.id}, its season's pack" }, limited)) {
                is Pass.Ended -> return pass.resolution
                Pass.Out -> return indexersOut(row.id)
                is Pass.Failed -> pass.reason
                Pass.PassedOver -> WONT_FIT
                // Its copy gone meanwhile and its grab refused for the indexer's limit: nothing known of it, nothing counted.
                Pass.Limited -> null
            }
            packFailed = reason
            log.info("download {}: its season's pack {}: its turn searches", row.id, if (reason == null) "wasn't tried" else "failed for the release")
            now = downloads.get(row.id) ?: return Resolution.Gone
            // Its tries used up by the pack: nothing more is searched for or grabbed.
            if (reason != null && tries() - now.tried.size <= 0) return failed(row.id, reason)
        }
        if (search.due(now)) {
            now = when (val found = search.search(now)) {
                is DownloadSearch.Searched.Found -> found.row
                DownloadSearch.Searched.NoneFound -> return limited.values.lastOrNull()?.let { limitReached(row.id, it) }
                    ?: failed(row.id, packFailed ?: DownloadSheetModel.NO_RELEASE)
                DownloadSearch.Searched.WontFit -> return failed(row.id, WONT_FIT)
                is DownloadSearch.Searched.Limited -> return limitReached(row.id, found.wait)
                DownloadSearch.Searched.Out -> return indexersOut(row.id)
                DownloadSearch.Searched.Unavailable -> return waiting(row.id, DownloadHostRules.STORAGE_UNAVAILABLE)
                DownloadSearch.Searched.Gone -> return Resolution.Gone
                // Its torrents unchecked: no answer about them, so nothing fails; the row waits for the account (L474).
                is DownloadSearch.Searched.Unchecked -> return unchecked(row.id, found.stop)
            }
        }
        return fromCandidates(now, limited, keptOut)
    }

    /**
     * The copy of [row]'s season's pack its turn tries first (L431), where it is a season row's: one kept that leads, not
     * tried by it yet (one whose attempt had no answer, its NZB kept for it, is still to try), whose share of the episode
     * fits the room now and which, ranked alone for downloading with the episode's runtime, isn't above Download size. Null
     * where none is, or no try is left; and while another release's attempt had no answer, its NZB kept for it: that goes on
     * first from what was kept, as the class says, as a copy tried before it would count it and write over its NZB. A
     * torrent's meta (L462) only where it can go to a service now ([toService]): else the turn goes on as with no copy.
     */
    private suspend fun packFirst(row: Download): DownloadNzbs.Copy? {
        val show = row.showTmdbId ?: return null
        val season = row.season ?: return null
        val kept = storage.grab(row.id)?.key
        val tried = row.tried.filter { it != kept }
        if (row.kind != DownloadKind.Episode || tries() - tried.size <= 0) return null
        val copies = storage.packs(show, season)
            .filter { it.meta.leads && it.meta.key !in tried && (!it.meta.torrent || toService(it.meta.candidate)) }
        if (copies.isEmpty()) return null
        val space = storage.space(row.id) ?: return null
        val copy = copies.firstOrNull { copy ->
            space.fits(ReleaseListMode.Download.need(ranked(copy.meta.candidate), row.seasonEpisodes)) && leads(row, copy.meta.key, copy.meta.candidate)
        }
        return copy?.takeIf { kept == null || kept == it.meta.key }
    }

    /**
     * Whether the torrent [candidate] can go to a service now (L422, L194): debrid shown, an account on, and its indexer not
     * kept from the service since. Asks no account anything: the settings alone.
     */
    private suspend fun toService(candidate: DownloadCandidate): Boolean = shown() && candidate.indexerId !in offService() && accountsOn().isNotEmpty()

    /** At most this many releases are tried for a download: three, or "Releases to try" where lower (L340). */
    private suspend fun tries(): Int = minOf(MAX_TRIES, attempts())

    /**
     * [row] opened again from its own NZB (L346): prepared from the copy, no grab, its file found by its name (a position
     * can name another file once prepared again). The same size goes on from the safe offset; another starts again from
     * its first byte, as nzb-streamer now lists the file otherwise. A season pack's NZB is kept for its season, and its
     * prepare held, as at a resolve's success ([keepPack]). Null where it has no copy, or the copy no longer gives the file:
     * then its candidates are resolved, the copy dropped first so that is never tried again.
     */
    private suspend fun reopened(row: Download): Resolution? {
        val release = row.release ?: return null
        val size = row.size ?: return null
        if (release.source == ReleaseFacts.TORRENT) return reopenedService(row, release, size)
        val source = storage.source(row.id) ?: return null
        providersReady(row.id)?.let { return it }
        val prepared = when (val outcome = preparerNow().prepare(source.nzb, release.title, source.password)) {
            is PrepareOutcome.Ready -> outcome.nzb
            // Only a failure that is the release's says the copy no longer gives the file; any other (a reply garbled, one
            // nobody named) is tried again after a wait, as the writer reopens on it (L382), the copy and what was written kept.
            is PrepareOutcome.Failed -> providers(row.id, outcome.error)?.let { return it }
                ?: if (outcome.error is UsenetError.Corrupt || outcome.error is UsenetError.MissingArticle) null
                else return unreachable(row.id, outcome.error, DownloadHostRules.CANT_REACH_PROVIDER)
            // Missing on the providers in use, with one left out that may have it: no word on the release yet.
            is PrepareOutcome.MissingArticles -> if (providersLeftOut()) return unreachable(row.id, UsenetError.MissingArticle(null), DownloadHostRules.CANT_REACH_PROVIDER) else null
            is PrepareOutcome.NeedsPassword, is PrepareOutcome.Unsupported -> null
        }
        // By its name and size first, as the player finds it: a file listed under another name at its place and of its size
        // is the same (N54, NR-19). Then by its name alone, at whatever size, which starts it again from 0 below.
        val name = row.fileName ?: release.fileName
        val file = prepared?.let { it.find(release.fileIndex, name, size) ?: it.find(release.fileIndex, name, null) }
        if (prepared == null || file == null) {
            log.info("download {}: its own NZB no longer opens to its file: resolving it again", row.id)
            storage.dropSource(row.id)
            return null
        }
        // A season pack is kept for its season and its prepare held, as at any resolve's success: after a process death, the
        // season's next row takes both rather than grab or prepare it again.
        suspend fun ready(kept: Download) =
            Resolution.Ready.Usenet(kept, prepared, file).also { keepPack(kept, Replay.candidate(release), prepared, source.nzb, source.password, copied = false) }
        if (file.size == size) {
            log.info("download {}: opened again from its own NZB at {} of {} bytes", row.id, row.safeOffset, size)
            return ready(row)
        }
        // What was written can't be told to be this file's: it starts again from its first byte.
        log.info("download {}: its file is listed at {} bytes now, not {}: starting again from 0", row.id, file.size, size)
        val again = startOver(row)?.copy(
            release = release.copy(fileIndex = file.index, fileSize = file.size), size = file.size, updatedAt = clock.now(),
        ) ?: return waiting(row.id, DownloadHostRules.STORAGE_UNAVAILABLE)
        downloads.update(again)
        return room(again) ?: ready(again)
    }

    /**
     * A torrent row [row] opened again from the copy of its listing in its folder (L420, L460), its [release]'s file found
     * there by its place, name and [size], else by its name and size alone: the copy of another account's listing, which
     * the writer keeps once it has moved the torrent there (L424), may list it at another place. No prepare, no call and
     * no provider wait, the writer asking a fresh link as it opens. Null where it has no copy (its process went before it
     * was written) or its file is no longer listed so, the copy dropped first: then its candidates are resolved. While
     * debrid is hidden (L422) its torrent goes to no service, with no call: a Usenet release left beside it, or a season
     * row's search still to come (its Try again), is its turn's to try, the copy dropped first; with nothing else, it
     * fails. One whose indexer has been kept from the service since (L194) goes to no service either, its account asked
     * nothing: the row's other releases, or its season's search, are its turn's to try, its copy dropped first; with none,
     * it fails in the ranking's words. A season's torrent pack is kept for its season, as at a resolve's success
     * ([keepPack], L462).
     */
    private suspend fun reopenedService(row: Download, release: RememberedRelease, size: Long): Resolution? {
        if (!shown()) {
            val usenetLeft = row.candidates.any { it.source != ReleaseFacts.TORRENT && ranked(it).key !in row.tried } || search.due(row)
            if (!usenetLeft) return hidden(row.id)
            log.info("download {}: debrid is hidden in this build: its torrent left, with no call, for what else its turn has", row.id)
            storage.dropSource(row.id)
            return null
        }
        if (release.indexerId in offService()) {
            log.info("download {}: its torrent's indexer kept from the service since: left, with no call", row.id)
            storage.dropSource(row.id)
            val key = Replay.key(release)
            val others = row.candidates.any { ranked(it).key.let { other -> other != key && other !in row.tried } } || search.due(row)
            return if (others) null else failed(row.id, ReleaseReason.NotForService.text)
        }
        val entry = storage.listing(row.id) ?: return null
        val name = row.fileName ?: release.fileName
        val files = entry.prepared.listing.files
        val file = files.firstOrNull { it.index == release.fileIndex && it.name == name && it.size == size } ?: files.firstOrNull { it.name == name && it.size == size }
        if (file == null) {
            log.info("download {}: its listing's copy no longer lists its file: resolving it again", row.id)
            storage.dropSource(row.id)
            return null
        }
        log.info("download {}: opened again from its listing's copy at {} of {} bytes", row.id, row.safeOffset, size)
        // After a process death between the row taking its season's pack and the pack's meta: the season's next row takes it.
        keepPack(row, Replay.candidate(release))
        return Resolution.Ready.Service(row, entry, file)
    }

    /** Debrid hidden in this build (L422): a torrent's download fails, with no call. */
    private suspend fun hidden(id: String): Resolution {
        log.info("download {}: debrid is hidden in this build: its torrent fails, with no call", id)
        return failed(id, DownloadHostRules.NOT_IN_THIS_VERSION)
    }

    /**
     * [row]'s candidates not tried yet, as the class says, run through the play's pipeline again past a release whose exact
     * size won't fit (L401): it fails "Won't fit" only once none is left that fits. Again past a grab refused for its
     * indexer's download limit (L399), the indexers [limited] so left out: their releases are untried, so where the others'
     * fail with a try left, the download waits as the limit. So too where the others' indexers are out of reach then: the
     * limit wins over the outage across the passes, as within one, never the outage's backoff, whose every retry would ask
     * the limited indexer again (L443). The indexers the walk keeps out of grabs ([keptOut]) are left out alike, with no
     * request, their releases left for after their limits. A release whose NZB needs no request is never left out
     * ([heldBack]).
     */
    private suspend fun fromCandidates(row: Download, limited: MutableMap<String, LimitWait>, keptOut: Map<String, LimitWait>): Resolution {
        var now = row
        // A release passed over for want of room: with none left after it, the download won't fit.
        var passedOver = false
        val held = (heldBack(now, emptyMap(), keptOut) - now.tried.toSet()).size
        if (held > 0) log.info("download {}: {} release{} left for after an indexer's limit", row.id, held, if (held == 1) "" else "s")
        while (true) {
            val naming = naming(now)
            // A limited indexer refuses its other releases alike: those are left for after its limit. Asked again at each
            // pass, as an NZB kept or a copy can go meanwhile, and a release whose copy went needs its grab.
            val behind = heldBack(now, limited, keptOut)
            val candidates = now.candidates.map(::ranked).filter { it.key !in behind }
            when (val pass = attempt(now, candidates, naming, limited)) {
                is Pass.Ended -> return pass.resolution
                is Pass.Failed -> {
                    val after = downloads.get(row.id) ?: return Resolution.Gone
                    val wait = limitBehind(after, limited, keptOut)
                    return if (wait != null && tries() - after.tried.size > 0) {
                        limitReached(row.id, wait)
                    } else {
                        failed(now.id, if (pass.none && passedOver) WONT_FIT else pass.reason)
                    }
                }
                Pass.Out -> {
                    val after = downloads.get(row.id) ?: return Resolution.Gone
                    val wait = limitBehind(after, limited, keptOut)
                    return if (wait != null && tries() - after.tried.size > 0) limitReached(row.id, wait) else indexersOut(row.id)
                }
                Pass.PassedOver -> passedOver = true
                Pass.Limited -> Unit
            }
            now = downloads.get(row.id) ?: return Resolution.Gone
        }
    }

    /**
     * The keys of [row]'s releases a limit holds back: those of the indexers limited in this turn ([limited], L399) or kept
     * out of grabs by the walk ([keptOut], L443), but for those whose NZB needs no request, which no limit stops: the one
     * kept for an attempt with no answer (a file chosen by hand's pack among them, L403) and any with its season's copy,
     * leading or not (L431).
     */
    private suspend fun heldBack(row: Download, limited: Map<String, LimitWait>, keptOut: Map<String, LimitWait>): Set<String> {
        val kept = storage.grab(row.id)?.key
        return row.candidates.filter { it.indexerId in limited || it.indexerId in keptOut }
            .map { ranked(it).key }
            .filter { it != kept && storage.pack(it) == null }
            .toSet()
    }

    /**
     * The limit [row]'s releases wait behind, where one does: of the indexers whose releases a limit holds back ([heldBack])
     * and [row] has still to try, the latest met in this turn ([limited]), else one the walk keeps out of grabs ([keptOut]).
     */
    private suspend fun limitBehind(row: Download, limited: Map<String, LimitWait>, keptOut: Map<String, LimitWait>): LimitWait? {
        val behind = heldBack(row, limited, keptOut) - row.tried.toSet()
        val waiting = row.candidates.filter { ranked(it).key in behind }
        val indexers = waiting.map { it.indexerId }.toSet()
        return limited.values.lastOrNull { it.indexerId in indexers } ?: waiting.firstNotNullOfOrNull { keptOut[it.indexerId] }
    }

    /** How one run of the pipeline over some of a row's releases ended ([attempt]). */
    private sealed interface Pass {
        /** The resolve's end: found, waiting, out of reach, or the row gone. */
        class Ended(val resolution: Resolution) : Pass

        /** Each release it tried failed for its own sake, the last in [reason]'s words; [none]: none was left to try. */
        class Failed(val reason: String, val none: Boolean = false) : Pass

        /** Its release passed every step, and its exact size won't fit the room now (L401): counted, nothing of it kept. */
        data object PassedOver : Pass

        /**
         * Releases left untried for want of their NZBs, their indexers out of reach or failing (a 5xx), nothing counted:
         * backed off from and asked again once the wait is over (L382), unless an indexer's limit leaves its own untried.
         */
        data object Out : Pass

        /**
         * A grab refused for its indexer's download limit (L399), uncounted, left releases untried, and none it tried played:
         * the others' releases are tried next, the limited indexers' kept for after their limits.
         */
        data object Limited : Pass
    }

    /**
     * [candidates] not tried yet by [row], through the play's pipeline, as the class says, each named in its lines by
     * [named] (by key). One whose attempt had no answer (the providers out of reach, the attempt cut short, the volume away
     * as it was picked) is tried again first, from the NZB kept for it ([Storage.keepGrab]) and counted once: its grab is
     * spent already. A grab refused for its indexer's download or API limit is counted out, and that limit kept in [limited].
     *
     * A torrent (L460) is tried by its own source, on the service accounts on in the user's order, its file picked from the
     * service's listing with no question. The providers are waited for only where nothing but Usenet's releases is left: with
     * none in use, a torrent beside them is tried first, as a play's cut leaves the NZBs out (L165), and they wait for one,
     * uncounted, once it has failed; a connection lost on one of them ends Usenet's share of the pass alone. While
     * debrid is hidden a torrent goes to no service (L422), nor one of an indexer since kept from the service (L194): left
     * out before any lookup, uncounted; with only such torrents left, the row fails "Not available in this version" while
     * hidden. With no account on, the torrents wait for one. An attempt its account's own refusal ended (its outage, its
     * refused key, its limit) had no answer about the release and isn't counted ([exhausted] says what the row does).
     */
    private suspend fun attempt(row: Download, candidates: List<RankedItem>, named: (String) -> String, limited: MutableMap<String, LimitWait>): Pass {
        val id = row.id
        val kept = storage.grab(id)?.takeIf { grab -> candidates.any { it.key == grab.key } }
        val counted = row.tried.filter { it != kept?.key }
        val left = tries() - counted.size
        val untried = candidates.filter { it.key !in counted }.distinctBy { it.key }
        val torrents = untried.any { it.item.infoHash != null }
        val debrid = !torrents || shown()
        val offService = if (torrents && debrid) offService() else emptySet()
        val sendable = untried.filter { it.item.infoHash == null || (debrid && Replay.leftOut(it, offService) == null) }
        val accountsNow = if (sendable.any { it.item.infoHash != null }) accountsOn() else emptyList()
        // A torrent's routes are the accounts on now: one queued before an account was switched off never asks it.
        val items = sendable.filter { it.item.infoHash == null || accountsNow.isNotEmpty() }.map { Replay.onAccounts(it, accountsNow) }
        if (left <= 0 || items.isEmpty()) {
            if (left > 0 && !debrid) {
                log.info("download {}: debrid is hidden in this build: its torrents left out, with no call", id)
                return Pass.Failed(DownloadHostRules.NOT_IN_THIS_VERSION, none = true)
            }
            if (left > 0 && sendable.isNotEmpty()) return Pass.Ended(waiting(id, DownloadHostRules.CHECK_SERVICE))
            log.info("download {}: no release left to try, {} tried", id, row.tried.size)
            return Pass.Failed(NO_OTHER_RELEASE, none = true)
        }
        var said: String? = null
        suspend fun say(words: String) {
            if (words == said) return
            said = words
            downloads.setState(id, DownloadState.Resolving, words, clock.now())
        }
        // Nothing is searched for an imported file (L454): its NZB is opened, which its row says until its file is checked.
        val opening = row.kind == DownloadKind.Release
        say(if (opening) DownloadHostRules.OPENING else DownloadHostRules.FINDING_RELEASES)
        // The engine's check of the providers, where a Usenet release is left: with none in use, a torrent among them goes on
        // alone, the Usenet ones waiting for a provider, uncounted, until it has failed; with none, the row waits now.
        val providers = if (items.any { it.item.infoHash == null }) providersChecked() else true
        val torrentsFirst = providers != true && items.any { it.item.infoHash != null }
        if (providers != true && !torrentsFirst) return Pass.Ended(providersWait(id, providers))
        if (torrentsFirst) log.info("download {}: no Usenet provider in use: its torrents tried first, its Usenet releases waiting for one", id)
        val trying = if (torrentsFirst) items.filter { it.item.infoHash != null } else items
        val byKey = trying.associateBy { it.key }
        val newznab = byKey.mapValues { it.value.item }
        // Import's own releases of NZBs the user opened (L457): their copy is their one source, and no indexer has them.
        val imported = items.filter { it.item.indexerId == IMPORTED }.mapTo(HashSet()) { it.key }
        val configs = indexers().associateBy { it.id }
        // Whether this attempt's last grab failed for an outage on the indexer's side (out of reach, a 5xx): no NZB came,
        // nothing spent. An attempt whose grab never reached an indexer (none set up for it) had no outage.
        var indexerOut = false
        // The limit that refused this attempt's last grab (L399): no NZB came, nothing spent.
        var limitedBy: LimitWait? = null
        val grabs = indexerGrab(newznab, configs) { indexer, item ->
            try {
                fetchNzb(indexer, item).also {
                    indexerOut = false
                    limitedBy = null
                }
            } catch (e: NewznabException) {
                indexerOut = e.error.isOutage()
                limitedBy = limitOf(indexer, e.error)
                // The limit's kind alone in the pipeline's lines, never what the indexer said of it (L405). Either limit
                // refuses the indexer's other releases alike, so the pipeline passes over them.
                throw when (e.error) {
                    is NewznabError.DownloadLimitReached -> NewznabException(NewznabError.DownloadLimitReached(DOWNLOAD_LIMIT))
                    is NewznabError.ApiLimitReached -> FetchException(API_LIMIT, blocksIndexer = true)
                    else -> e
                }
            }
        }
        // Its lines, and the memory of tried releases', name each release by its place among the row's, never by its name.
        val namedCandidate = { candidate: PlayCandidate -> named(candidate.id) }
        // The releases whose NZB came from their season's copy (L394).
        val copied = HashSet<String>()
        // Each NZB grabbed kept until its attempt has its answer; the one kept before served from it, and a season pack's
        // from its season's copy, with no grab. One from the copy is kept for its attempt too, as a grab is: counted as it
        // starts, an attempt with no answer (a process death, the volume away) is only taken up again from what was kept.
        val nzbs = object : NzbSource {
            override suspend fun fetch(candidate: PlayCandidate): ByteArray = fetchWithPassword(candidate).bytes

            override suspend fun fetchWithPassword(candidate: PlayCandidate): FetchedNzb {
                kept?.takeIf { it.key == candidate.id }?.let { grab ->
                    log.info("download {}: its release tried again from the NZB kept for it, no grab", id)
                    return FetchedNzb(grab.source.nzb, grab.source.password)
                }
                storage.pack(candidate.id)?.source?.let { copy ->
                    log.info("{}: from its season's copy, no grab", namedCandidate(candidate))
                    copied += candidate.id
                    storage.keepGrab(id, candidate.id, copy.nzb, copy.password)
                    return FetchedNzb(copy.nzb, copy.password)
                }
                // An imported NZB's copy gone with its last download: nothing has it now, so nothing is asked.
                if (candidate.id in imported) throw FetchException(IMPORT_GONE)
                return grabs.fetchWithPassword(candidate).also { storage.keepGrab(id, candidate.id, it.bytes, it.password) }
            }
        }
        val usenet = UsenetSource(nzbs, availability, preparerNow(), store, providersLeftOut, providerFallback, FileChooser.None)
        // A torrent's from the accounts that have it cached (L460), its listing kept in the store and held by this resolve.
        val service = ServiceSource(accounts, listings, FileChooser.None, misses = misses, links = links, onListed = { adds?.holdForPlay(it) })
        // The probe keeps its target in the store to read it; the pipeline lets go of it when the probe fails, and resolved()
        // once it is copied. Cut short as it reads, it lets go of it itself (L383).
        val probing = TrackProbe { target ->
            try {
                probe.probe(target)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { letGo(id, target) }
                throw e
            }
        }
        // An imported NZB's release is never skipped as found missing, nor remembered so (L457): it is the file the user chose,
        // and nothing else has it.
        val remembered = tried(newznab.filterKeys { it !in imported }, { indexerId -> configs[indexerId]?.name }, namedCandidate)
        // A torrent's by what its accounts failed of it, as a play's that wasn't chosen (L174).
        val pipeline = PlayPipeline(
            { candidate -> if (candidate.source == PlayCandidate.USENET) usenet else service }, probing, maxAttempts = left,
            tried = triedBySource(remembered, misses?.forPlay(chosen = false) ?: TriedReleases.None), named = namedCandidate,
            failureWords = { it::class.simpleName ?: "Failure" },
        )
        // Counted across its resolves: the second release in all is "release 2", whichever resolve tries it.
        val before = counted.size
        var counting = counted
        var attempt = 0
        var of = 0
        // The attempt under way, and whether it has had its answer about its release (a step of it failed for the release).
        var current: String? = null
        var answered = false

        // The release had its answer: the NZB kept for it is no use now.
        suspend fun answer() {
            answered = true
            storage.dropGrab(id)
        }
        // The connection lost or timed out in a Usenet release's attempt: no other is grabbed while it lasts (L382).
        var lost: UsenetError? = null
        // The Usenet release whose attempt lost it: tried again first, uncounted, from the NZB kept for it.
        var lostOf: String? = null
        // Grabs that failed for an outage on the indexer's side, uncounted.
        var indexerOutages = 0
        // Grabs refused for their indexer's download limit, uncounted.
        var refusals = 0
        val skipped = mutableListOf<String>()
        var ended: PipelineEvent? = null
        // The last failure of the attempt under way, whichever account or step it came from.
        var failedWith: AttemptFailure? = null
        // The attempts their accounts' own refusals ended, uncounted: the last says what the row does (L460).
        val stops = mutableListOf<AttemptFailure>()

        // An attempt that failed on its account's outage, refused key or limit had no answer about its release.
        suspend fun settle() {
            val cut = current ?: return
            val failure = failedWith?.takeIf { it.isServiceStop() } ?: return
            counting = counting - cut
            count(id, counting)
            stops += failure
            log.info("{}: its service's {}, not counted", named(cut), failure::class.simpleName)
        }
        log.info("download {}: resolving: up to {} of the {} release{} not tried yet", id, minOf(left, trying.size), trying.size, if (trying.size == 1) "" else "s")
        try {
            pipeline.play(trying.map { it.playCandidate }, picker(row)).collect { event ->
                // Ended at the next Usenet release, or the pass's end, once the attempt before has let go of what it held: a
                // torrent goes to its service by a route of its own, and is tried meanwhile.
                lost?.let { error ->
                    if (event is PipelineEvent.Exhausted || event is PipelineEvent.AttemptStarted && event.candidate.source == PlayCandidate.USENET) {
                        throw OutOfReach(error)
                    }
                }
                when (event) {
                    is PipelineEvent.AttemptStarted -> {
                        settle()
                        failedWith = null
                        attempt = before + event.attempt
                        of = before + event.of
                        current = event.candidate.id
                        answered = false
                        indexerOut = false
                        limitedBy = null
                        // Before its grab: a process that dies mid-attempt never grabs it a fourth time (L340).
                        counting = counting + event.candidate.id
                        count(id, counting)
                    }
                    is PipelineEvent.Step -> when (val status = event.status) {
                        is StepStatus.Running -> say(
                            if (opening && event.step in OPENS) DownloadHostRules.OPENING else DownloadHostRules.resolving(STEP_LABELS.getValue(event.step), attempt, of),
                        )
                        is StepStatus.Failed -> when (val failure = status.failure.also { failedWith = it }) {
                            // The providers failing is no answer about the release, nor a connection lost.
                            is AttemptFailure.Provider -> Unit
                            // Nor a service's outage, its refusal of the key or its limit (L460): its next account may still
                            // have the release, and the attempt's end says whether it had an answer ([settle]).
                            is AttemptFailure.ServiceDown, is AttemptFailure.KeyRefused, is AttemptFailure.ServiceLimit -> Unit
                            is AttemptFailure.Failed -> if (event.step in READS_PROVIDERS && failure.error.isLostConnection()) {
                                lost = failure.error
                                lostOf = current
                            } else {
                                answer()
                            }
                            is AttemptFailure.FetchFailed -> {
                                val limit = limitedBy
                                when {
                                    indexerOut -> {
                                        // No NZB came: the release isn't counted, and the next may be another indexer's.
                                        current?.let { counting = counting - it }
                                        count(id, counting)
                                        indexerOutages++
                                    }
                                    limit != null -> {
                                        // Its indexer's limit (L399): no answer about the release, and no try spent.
                                        current?.let { counting = counting - it }
                                        count(id, counting)
                                        refusals++
                                        limited[limit.indexerId] = limit
                                        log.info("{}: its indexer's {} limit reached, not counted", named(event.candidate.id), limit.kind.name.lowercase())
                                    }
                                    else -> answer()
                                }
                            }
                            else -> answer()
                        }
                        is StepStatus.Done -> Unit
                    }
                    is PipelineEvent.Skipped -> skipped += event.reason
                    is PipelineEvent.Succeeded, is PipelineEvent.Exhausted -> ended = event
                    is PipelineEvent.Retrying -> Unit
                }
            }
        } catch (e: CancellationException) {
            // Cut short (a pause, the host stopped) before its release had its answer: not counted. Its NZB, kept once it
            // was grabbed, is tried again first.
            val cut = current
            withContext(NonCancellable) {
                if (cut != null && !answered) count(id, counting - cut)
                // Succeeded, and cut short before resolved() took it up: the probe's entry goes all the same.
                (ended as? PipelineEvent.Succeeded)?.let { letGo(id, it.target) }
            }
            throw e
        } catch (e: OutOfReach) {
            // Nothing known of the release: not counted, and tried again from its NZB once the wait is over. A torrent tried
            // since that its account's own refusal ended isn't counted either.
            settle()
            lostOf?.let { count(id, counting - it) }
            return Pass.Ended(unreachable(id, e.error, DownloadHostRules.CANT_REACH_PROVIDER))
        }
        val pass = when (val end = ended) {
            is PipelineEvent.Succeeded -> when (val target = end.target) {
                is ServiceTarget -> resolvedService(id, end, target, byKey, named)
                else -> resolved(id, end, byKey, target.candidate.id in copied, named)
            }
            is PipelineEvent.Exhausted -> {
                settle()
                exhausted(id, end, counting, skipped, indexerOutages > 0, refusals > 0, imported, stops.lastOrNull())
            }
            else -> Pass.Failed(NO_OTHER_RELEASE)
        }
        // The torrents tried first failed for their own sake: the Usenet releases left wait for a provider (L382).
        return if (torrentsFirst && pass is Pass.Failed) Pass.Ended(providersWait(id, providers)) else pass
    }

    /**
     * As a play's: a release is looked up and opened by the providers the engine has checked, which it does first when it
     * hasn't since they changed (a new process, a provider saved). With none saved and on, nothing is grabbed or opened:
     * the download waits for one (L382). With some saved that the engine hasn't taken up yet (a new process, before the
     * app's start has read them), it backs off as from a provider out of reach. Null once one is in use.
     */
    private suspend fun providersReady(id: String): Resolution? {
        val checked = providersChecked()
        return if (checked == true) null else providersWait(id, checked)
    }

    /**
     * What the download does with no provider in use, as the engine's check says ([checked]: false, none saved and on; null,
     * some the engine hasn't taken up yet), as [providersReady] says.
     */
    private suspend fun providersWait(id: String, checked: Boolean?): Resolution =
        if (checked == false) waiting(id, DownloadHostRules.CHECK_PROVIDER) else unreachable(id, UsenetError.NotConfigured, DownloadHostRules.CANT_REACH_PROVIDER)

    /** A connection lost in an attempt ([lost]): it ends the resolve before another release is grabbed. */
    private class OutOfReach(val error: UsenetError) : Exception(error::class.simpleName)

    /**
     * [succeeded]'s release kept as the download's, as the class says: the room checked for its exact size first, nothing of
     * the row changed for one that won't fit, which is passed over (L401: a pack's share is an average); then its NZB and
     * password copied. The release its file was made from, its file of the same name and size, goes on from the safe offset
     * (Try again after "Storage is full"); another starts from 0, any file made for the one before deleted. A season pack is
     * kept for its season's other rows ([keepPack]); [copied]: its NZB came from that copy. [named] names its release.
     */
    private suspend fun resolved(id: String, succeeded: PipelineEvent.Succeeded, byKey: Map<String, RankedItem>, copied: Boolean, named: (String) -> String): Pass {
        val target = succeeded.target as UsenetTarget
        try {
            val row = downloads.get(id) ?: return Pass.Ended(Resolution.Gone)
            val size = target.file.size
            // A wait for the volume keeps the NZB grabbed for it: the release is taken up again once the volume is back.
            val space = storage.space(id) ?: return Pass.Ended(waiting(id, DownloadHostRules.STORAGE_UNAVAILABLE))
            if (!space.fits(size)) {
                // Counted as its attempt started; its release, file and size never the row's, and its NZB no use now.
                log.info("{}: passed over, {} bytes won't fit in the {} free", named(target.candidate.id), size, space.free)
                storage.dropGrab(id)
                return Pass.PassedOver
            }
            val release = savedRelease(byKey.getValue(target.candidate.id).item, target).toRemembered()
            val same = row.size == size && row.release?.let { Replay.key(it) == target.candidate.id && it.fileName == release.fileName } == true
            val base = (if (same) row else startOver(row)) ?: return Pass.Ended(waiting(id, DownloadHostRules.STORAGE_UNAVAILABLE))
            val kept = base.copy(
                release = release,
                fileName = target.file.name,
                size = size,
                oneVideo = target.prepared.oneVideo,
                video = succeeded.video?.let { json.encodeToString(VideoTiming.serializer(), VideoTiming.of(it)) },
                tracks = succeeded.tracks?.let { json.encodeToString(ProbedTracksSerializer, it) },
                updatedAt = clock.now(),
            )
            downloads.update(kept)
            // The archive's password as the NZB store keeps one (the NZB's own, else the indexer's), never logged.
            val password = target.prepared.metadata.orderedArchiveNzb.password ?: target.password
            storage.keepSource(id, target.nzbBytes, password)
            storage.dropGrab(id)
            keepPack(kept, byKey.getValue(target.candidate.id), target.prepared, target.nzbBytes, password, copied)
            log.info("download {}: resolved, {} bytes, {}", id, size, if (kept.oneVideo) "its release's one video" else "one of its release's videos")
            return Pass.Ended(Resolution.Ready.Usenet(kept, target.prepared, target.file))
        } finally {
            // The probe kept its NZB in the store to read it: out again, unless a play kept it there before or holds it
            // now (L383, backlog 353).
            withContext(NonCancellable) { letGo(id, target) }
        }
    }

    /**
     * [succeeded]'s torrent kept as the download's (L460), as the class says of a release: the room checked for its file's
     * exact size first, nothing of the row changed for one that won't fit, which is passed over (L401); then the listing it
     * was prepared as kept in the row's folder (L420), before the row takes the release: its content id for its release and
     * its NZB's id, its source the torrent's, its file's place, name and size, and what the probe read; and its add held by
     * the download's own lease (L421). The release its file was made from goes on from the safe offset; another starts from
     * 0, any file made for the one before deleted. No NZB is kept for it, and it put nothing in the NZB store; a season's
     * torrent pack is kept for its season as its meta alone ([keepPack], L462). [named] names its release.
     */
    private suspend fun resolvedService(
        id: String, succeeded: PipelineEvent.Succeeded, target: ServiceTarget, byKey: Map<String, RankedItem>, named: (String) -> String,
    ): Pass {
        val row = downloads.get(id) ?: return Pass.Ended(Resolution.Gone)
        val size = target.file.size
        val space = storage.space(id) ?: return Pass.Ended(waiting(id, DownloadHostRules.STORAGE_UNAVAILABLE))
        if (!space.fits(size)) {
            log.info("{}: passed over, {} bytes won't fit in the {} free", named(target.candidate.id), size, space.free)
            return Pass.PassedOver
        }
        // Put in the store by this attempt's Preparing, or by its Opening where the account added it again.
        val entry = checkNotNull(listings.get(target.listingId)) { "The listing just prepared is no longer kept" }
        val release = savedRelease(byKey.getValue(target.candidate.id).item, target).toRemembered()
        val same = row.size == size && row.release?.let { Replay.key(it) == target.candidate.id && it.fileName == release.fileName } == true
        val base = (if (same) row else startOver(row)) ?: return Pass.Ended(waiting(id, DownloadHostRules.STORAGE_UNAVAILABLE))
        storage.keepListing(id, entry)
        // Held by the download's own lease before the row takes the release, and before the resolve's Play lets its attempts'
        // adds go (L421): the chosen one stays in the account while the download runs, or is paused as the row takes it, and
        // the others go a grace on.
        adds?.holdOnly(DownloadLease(id), entry.id)
        val kept = base.copy(
            release = release,
            fileName = target.file.name,
            size = size,
            oneVideo = target.listing.files.count { it.isVideo } == 1,
            video = succeeded.video?.let { json.encodeToString(VideoTiming.serializer(), VideoTiming.of(it)) },
            tracks = succeeded.tracks?.let { json.encodeToString(ProbedTracksSerializer, it) },
            updatedAt = clock.now(),
        )
        downloads.update(kept)
        keepPack(kept, byKey.getValue(target.candidate.id))
        log.info("download {}: resolved on a debrid service, {} bytes, {}", id, size, if (kept.oneVideo) "its torrent's one video" else "one of its torrent's videos")
        return Pass.Ended(Resolution.Ready.Service(kept, entry, target.file.file))
    }

    /**
     * [row]'s release, [item], kept once for its season when it is a season pack (L394, L432): its NZB [copied] from the
     * season's copy, or one whose name says it holds several episodes ([offersFileChoice]) while another row of the show's
     * season is still to finish, for which the copy is written now from [nzb]; otherwise it would have no use. A copy kept
     * already of that same NZB (taken again from what its attempt kept of it) stays as it was written. Either way the pack
     * as it was [prepared] is held for the season's next row. The copy leads when it isn't above Download size ([leads]).
     * Nothing of this fails the resolve: a copy that can't be kept costs the next row a grab. An imported file's NZB was
     * kept as it was queued (L423): taken from that copy, its prepare alone is held, for the NZB's next file (L457).
     */
    private suspend fun keepPack(row: Download, item: RankedItem, prepared: PreparedNzb, nzb: ByteArray, password: String?, copied: Boolean) {
        if (row.kind == DownloadKind.Release) {
            if (copied) storage.holdPrepare(item.key, prepared)
            return
        }
        val show = row.showTmdbId ?: return
        val season = row.season ?: return
        if (row.kind != DownloadKind.Episode || !(copied || item.offersFileChoice)) return
        try {
            if (!copied && storage.pack(item.key)?.source?.nzb?.contentEquals(nzb) != true) {
                val meta = seasonMeta(row, item, show, season) ?: return
                storage.keepPack(meta, nzb, password)
                log.info("download {}: its season pack kept for the season's other downloads", row.id)
            }
            storage.holdPrepare(item.key, prepared)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("download {}: couldn't keep its season pack for the season: {}", row.id, e::class.simpleName)
        }
    }

    /**
     * [keepPack]'s torrent branch (L462): [row]'s torrent, [item], kept once for its season by the same rule (L432), a
     * torrent named as several episodes while another row of the show's season is still to finish, as its meta alone: no
     * NZB, as the season's next row prepares it again by its hash on the accounts on. One kept already under its key (the
     * meta this row's turn took first) stays as it was written. It holds nothing on the account (BQ3 b) and no prepare.
     * Nothing of this fails the resolve: a meta that can't be kept costs the next row a search.
     */
    private suspend fun keepPack(row: Download, item: RankedItem) {
        val show = row.showTmdbId ?: return
        val season = row.season ?: return
        if (row.kind != DownloadKind.Episode || !item.offersFileChoice) return
        try {
            if (storage.pack(item.key) != null) return
            val meta = seasonMeta(row, item, show, season) ?: return
            storage.keepPack(meta, nzb = null, password = null)
            log.info("download {}: its season's torrent pack kept for the season's other downloads", row.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("download {}: couldn't keep its season's torrent pack for the season: {}", row.id, e::class.simpleName)
        }
    }

    /**
     * What [row]'s release [item] is kept as for [show]'s [season] (L432): null where no other row of that season is still
     * to finish, or [row] doesn't list it. It leads where it isn't above Download size ([leads]).
     */
    private suspend fun seasonMeta(row: Download, item: RankedItem, show: Int, season: Int): DownloadNzbs.Meta? {
        val others = downloads.queue().first().any { it.id != row.id && it.showTmdbId == show && it.season == season && it.state in DownloadRunner.GOING_ON }
        val candidate = row.candidates.firstOrNull { ranked(it).key == item.key }
        if (!others || candidate == null) return null
        return DownloadNzbs.Meta(item.key, item.item.title, candidate, show, season, leads(row, item.key, candidate))
    }

    /**
     * Whether a season pack's copy leads its season's later rows (L431, L432): ranked alone for downloading, with [row]'s
     * runtime and its season's episode count as the sheet ranks an episode's, it isn't above Download size.
     */
    private suspend fun leads(row: Download, key: String, candidate: DownloadCandidate): Boolean {
        val context = downloadContext().let { it.copy(runtimeMinutes = row.runtimeMinutes ?: it.runtimeMinutes, seasonEpisodes = row.seasonEpisodes ?: it.seasonEpisodes) }
        val ranked = ReleaseRanker().rank(listOf(ReleaseFacts(key, candidate.title, candidate.size, candidate.passworded)), context).single()
        return ranked.reasons.none { it is ReleaseReason.AboveDownloadSize }
    }

    /**
     * The limit [indexer] refused a grab with ([error]), as its row keeps it (L434): its download limit (a 501), with no end;
     * its API limit (Newznab's 429 or 500, an HTTP 429), which its searches meet too, so it is the search limit, ending where
     * it said, and the walk keeps the indexer out of its later searches until then (L443). Either way the walk keeps it out
     * of the later turns' grabs until the limit's end. Null for any other refusal.
     */
    private fun limitOf(indexer: IndexerConfig, error: NewznabError): LimitWait? {
        val at = clock.now()
        val wait = when (error) {
            is NewznabError.DownloadLimitReached -> LimitWait(indexer.id, LimitWait.Kind.Download, at)
            is NewznabError.ApiLimitReached -> {
                limits.answered(indexer, error.retryAfter, at)
                LimitWait(indexer.id, LimitWait.Kind.Search, at, error.retryAfter?.let { at + it.toMillis() })
            }
            else -> null
        }
        // The walk keeps it out of the later turns' grabs until then too, so the season's next rows ask it nothing (L443).
        wait?.let { limits.refused(indexer, it) }
        return wait
    }

    /** The preparer of this resolve: a season pack's prepare held from an earlier row handed back for its NZB (L394). */
    private fun preparerNow(): NzbPreparer = storage.heldPrepare()?.let(heldPreparer) ?: preparer

    /**
     * Lets go of what [target]'s probe held in the store: its NZB goes where the probe put it there, not where a play kept
     * it before (L383), nor while a play holds it too (backlog 353).
     */
    private suspend fun letGo(id: String, target: PlaybackTarget) {
        try {
            target.drop()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("download {}: couldn't take its NZB out of the store: {}", id, e::class.simpleName)
        }
    }

    /**
     * The releases ran out (L340). The providers failing, not the release (the failure stops their route): it isn't
     * counted, and the download waits for them, though a torrent tried after it by a route of its own failed for its own
     * sake. Releases left untried behind an indexer's download limit ([limited], L399):
     * the others' are tried next, never the outage's backoff, which would ask the limited indexer again every few seconds.
     * Else it fails, in the last failure's words, or, every release left passed over as found missing before, in the play
     * sheet's words for those; an [imported] NZB's release that had no NZB, in Import's words for its copy gone (L457).
     * A torrent left with no answer by its account's own refusal ([stop], uncounted, L460) has the row wait for that
     * account: its outage on every account the torrent plays from is backed off from, as an indexer's is, in the debrid
     * service's words; its refused key waits for a new one; its limit, for its end.
     */
    private suspend fun exhausted(
        id: String, ended: PipelineEvent.Exhausted, counted: List<String>, skipped: List<String>, indexerOut: Boolean, limited: Boolean, imported: Set<String>,
        stop: AttemptFailure?,
    ): Pass {
        val last = ended.failedAttempts.lastOrNull()
            ?: return Pass.Failed(skipped.takeIf { it.isNotEmpty() }?.let(::skippedNotice) ?: NO_OTHER_RELEASE)
        val (candidate, failure) = last
        ended.failedAttempts.lastOrNull { it.second is AttemptFailure.Provider }?.let { (usenet, provider) ->
            count(id, counted - usenet.id)
            providers(id, (provider as AttemptFailure.Provider).error)?.let { return Pass.Ended(it) }
        }
        stop?.let { return Pass.Ended(serviceWait(id, it)) }
        if (limited) return Pass.Limited
        // Releases left untried for want of their NZBs: the indexers are asked again once the wait is over (L382).
        if (indexerOut) return Pass.Out
        log.info("download {}: {} release{} tried, the last failing as {}", id, counted.size, if (counted.size == 1) "" else "s", failure::class.simpleName)
        return Pass.Failed(if (failure is AttemptFailure.FetchFailed && candidate.id in imported) IMPORT_GONE else reason(failure))
    }

    /**
     * What a failure of the providers rather than the release ([error]) makes the download do (L382, L388): a login refused
     * or none saved waits for the providers to be saved; one out of reach is backed off from and resolved again. Null for
     * any other, which is the release's.
     */
    private suspend fun providers(id: String, error: UsenetError): Resolution? = when (error) {
        is UsenetError.AuthFailed, UsenetError.NotConfigured -> waiting(id, DownloadHostRules.CHECK_PROVIDER)
        is UsenetError.ConnectionFailed, is UsenetError.Network, is UsenetError.Timeout -> unreachable(id, error, DownloadHostRules.CANT_REACH_PROVIDER)
        else -> null
    }

    /**
     * What a torrent's account's own refusal ([stop]) makes the download do (L460, L474): its key refused waits for a new
     * one; its limit goes to the runner with when the account's gate opens (L461); its outage is backed off from, and the
     * download resolved again.
     */
    private suspend fun serviceWait(id: String, stop: AttemptFailure): Resolution = when (stop) {
        is AttemptFailure.KeyRefused -> waiting(id, DownloadHostRules.CHECK_SERVICE)
        is AttemptFailure.ServiceLimit -> {
            val left = stop.wait ?: ServiceDownloadWriter.LIMIT_WITHOUT_WAIT
            log.info("download {}: its debrid account's limit reached, its gate open again in {} s", id, left.inWholeSeconds)
            Resolution.Limited(stop.route?.let(UsableRoutes::accountOf), clock.now() + left.inWholeMilliseconds)
        }
        else -> unreachable(id, UsenetError.Network(null), DownloadHostRules.CANT_REACH_SERVICE)
    }

    /**
     * A turn's search that found nothing while the service accounts couldn't check its torrents (L460, L461, L474), as
     * [stop] says: an account's limit goes to the runner with when its gate opens; a key refused waits for a new one; out
     * of reach, the host backs off and searches again. Nothing counted.
     */
    private suspend fun unchecked(id: String, stop: DownloadSearch.ServiceStop): Resolution = when (stop) {
        is DownloadSearch.ServiceStop.Limit -> {
            log.info("download {}: its torrents unchecked, its debrid account's limit reached", id)
            Resolution.Limited(stop.account, stop.until)
        }
        DownloadSearch.ServiceStop.KeyRefused -> waiting(id, DownloadHostRules.CHECK_SERVICE)
        DownloadSearch.ServiceStop.Out -> unreachable(id, UsenetError.Network(null), DownloadHostRules.CANT_REACH_SERVICE)
    }

    /** The indexers out of reach or failing: no NZB came, and the host backs off and resolves the download again (L382). */
    private suspend fun indexersOut(id: String): Resolution = unreachable(id, UsenetError.Network(null), DownloadHostRules.CANT_REACH_INDEXER)

    /** Out of reach for [error], the row saying so in [words] while the host backs off and resolves it again. */
    private suspend fun unreachable(id: String, error: UsenetError, words: String): Resolution {
        val who = when (words) {
            DownloadHostRules.CANT_REACH_INDEXER -> "the indexers couldn't give an NZB"
            DownloadHostRules.CANT_REACH_SERVICE -> "its debrid service couldn't be reached"
            else -> "the providers couldn't be reached"
        }
        log.info("download {}: {} ({})", id, who, error::class.simpleName)
        downloads.setState(id, DownloadState.Resolving, words, clock.now())
        return Resolution.Unreachable(error, words)
    }

    /** Fails [row] where its exact size doesn't fit (L338), or waits while the volume isn't there; null where it fits. */
    private suspend fun room(row: Download): Resolution? {
        val space = storage.space(row.id) ?: return waiting(row.id, DownloadHostRules.STORAGE_UNAVAILABLE)
        val size = row.size ?: return null
        if (space.fits(size)) return null
        log.info("download {}: {} bytes won't fit in the {} free", row.id, size, space.free)
        return failed(row.id, WONT_FIT)
    }

    /**
     * [row] with nothing written, any file it made deleted; null while its volume isn't there to delete it from. A row with
     * a file names none, at no offset, before its file goes, whatever stops the call meanwhile: one left naming a deleted
     * file at its offset would open its release again into a file of zeros.
     */
    private suspend fun startOver(row: Download): Download? {
        val reset = row.copy(safeOffset = 0, gaps = emptyList(), relativePath = null)
        if (row.relativePath == null) return reset
        return withContext(NonCancellable) {
            downloads.update(reset.copy(updatedAt = clock.now()))
            try {
                storage.deleteVideo(row.id)
                reset
            } catch (e: DownloadStorage.Unavailable) {
                null
            }
        }
    }

    /** Keeps [counted] as the releases [id] has tried. */
    private suspend fun count(id: String, counted: List<String>) {
        val row = downloads.get(id) ?: return
        downloads.update(row.copy(tried = counted, updatedAt = clock.now()))
    }

    private suspend fun failed(id: String, reason: String): Resolution {
        downloads.setState(id, DownloadState.Failed, reason, clock.now())
        return Resolution.Failed(reason)
    }

    private suspend fun waiting(id: String, reason: String): Resolution {
        log.info("download {}: waiting: {}", id, reason)
        downloads.setState(id, DownloadState.Waiting, reason, clock.now())
        return Resolution.Waiting(reason)
    }

    /**
     * An indexer's limit met with nothing found or left elsewhere (L399, L443): its search limit by the turn's search, or its
     * download limit by a grab. The row waits, the limit's facts kept as its [wait] (L434), which its words name the indexer
     * from at draw time; its reason, and so its notification and this line, name none.
     */
    private suspend fun limitReached(id: String, wait: LimitWait): Resolution {
        downloads.get(id)?.let { downloads.update(it.copy(limitWait = DownloadColumns.text(wait), updatedAt = clock.now())) }
        log.info("download {}: an indexer's {} limit reached", id, wait.kind.name.lowercase())
        return waiting(id, DownloadHostRules.LIMIT_REACHED)
    }

    /**
     * The episode's file in a release, else its largest video; in the release that played it last, the file that played
     * (Replay.picker), so a file chosen while streaming a pack is picked again with no question (L402). A file chosen by
     * hand in the pack (Choose file, L403) wins: in that NZB, the row's kept grab, it is picked with no question, by its
     * name and size, as a remembered file is ([RememberedFile]); where it is no longer there, the episode's, else that
     * attempt fails (L339). Its pack, the row's first release, isn't checked whole: the chosen file's volumes are. A file
     * of an NZB opened on Import is the one chosen there, by its name and size alone: one no longer there fails the attempt,
     * never taking another of the NZB under its watch key (L457), and its NZB isn't checked whole either.
     */
    private suspend fun picker(row: Download): FilePicker {
        if (row.kind == DownloadKind.Release) {
            val pick = DownloadColumns.pick(row)
            log.info("download {}: its file chosen on Import, picked by its name and size", row.id)
            // No NZB id and nothing wanted besides: the file named so is picked in whatever its NZB lists, else none.
            return RememberedFile(
                pick?.index ?: -1, FilePicker.Largest, release = row.candidates.firstOrNull()?.title, name = pick?.name, size = pick?.size, wanted = null,
            )
        }
        val season = row.season
        val episode = row.episode
        val (base, watch) = if (row.kind == DownloadKind.Episode && season != null && episode != null) {
            FilePicker.Episode(season, episode) to WatchTarget(WatchKind.Episode, WatchTitle.episode(row.title, season, episode), row.showTmdbId, season, episode)
        } else {
            FilePicker.Largest to WatchTarget(WatchKind.Movie, row.title, movieTmdbId = row.movieTmdbId)
        }
        DownloadColumns.pick(row)?.let { pick ->
            log.info("download {}: its file chosen by hand in its pack, picked with no question", row.id)
            return RememberedFile(pick.index, base, pick.nzbId, row.candidates.firstOrNull()?.title, pick.name, pick.size)
        }
        val remembered = try {
            lastRelease(row.watchKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("download {}: couldn't read the release that played it last: {}", row.id, e::class.simpleName)
            null
        }
        return Replay.picker(base, remembered, watch)
    }

    companion object {
        /** At most this many releases are tried for one download (L340). */
        const val MAX_TRIES = 3

        /** The exact size is more than the room on the device (L338). */
        const val WONT_FIT = "Won't fit"

        /** Nothing left to try: every candidate tried, or its tries used up. */
        const val NO_OTHER_RELEASE = "No other release to try"

        /** A grab refused for its indexer's download limit, as a download's lines say it, whatever the indexer said (L405). */
        private const val DOWNLOAD_LIMIT = "Download limit reached"
        private const val API_LIMIT = "API limit reached"

        /** A release that lacks articles: the play sheet's words for it, and the runner's for one given up as it was written. */
        const val INCOMPLETE = "Release incomplete"

        /** A release whose articles can't be read. */
        const val DAMAGED = "Release damaged"

        /** A torrent its accounts no longer have cached (L460): the play's failure sheet's words. */
        const val NO_LONGER_CACHED = "This release is no longer cached"

        /** As the player's launch reads its own: what a later version adds is passed over. */
        private val json = Json { ignoreUnknownKeys = true }

        /** The connection lost or timed out: said of no release, which may be whole (L382). */
        private fun UsenetError.isLostConnection() = this is UsenetError.Network || this is UsenetError.Timeout

        /** A service's outage, its refusal of the account's key or its limit: the account's, never the release's (L460). */
        private fun AttemptFailure.isServiceStop() =
            this is AttemptFailure.ServiceDown || this is AttemptFailure.KeyRefused || this is AttemptFailure.ServiceLimit

        /**
         * The steps whose lost connection is no answer about the release: the check and the open, which ask the providers.
         * The probe's are its answer, as a play takes them: its own deadline times out where a file stalls it, and any
         * IOException Media3 meets as it reads says Network, whatever it was.
         */
        private val READS_PROVIDERS = setOf(PipelineStep.CheckAvailability, PipelineStep.Open)

        /** The steps an imported file's row says "Opening…" for (L454): its NZB's fetch, from its copy, and its open. */
        private val OPENS = setOf(PipelineStep.FetchNzb, PipelineStep.Open)

        /** An indexer out of reach, or failing on its side (a 5xx): no answer about the release, nor a refusal of the account. */
        internal fun NewznabError.isOutage() = this is NewznabError.Network || this is NewznabError.Timeout || this is NewznabError.Server

        /** What the probe read of [row]'s video, as Resolve kept it; null where it read none. */
        fun video(row: Download): VideoTiming? = row.video?.let { json.decodeFromString(VideoTiming.serializer(), it) }

        /** What the probe read of [row]'s file's tracks, as Resolve kept them; null where it read none. */
        fun tracks(row: Download): ProbedTracks? = row.tracks?.let { json.decodeFromString(ProbedTracksSerializer, it) }

        /**
         * [failure] in a failed row's words (L366): the play sheet's ([shortFailure]) where those are its own, else words of
         * its kind. The row's reason goes in a notification, so nothing an indexer or a provider said (a cause's message,
         * which may name its host) is kept.
         */
        internal fun reason(failure: AttemptFailure): String = when (failure) {
            is AttemptFailure.MissingArticles, AttemptFailure.Passworded, is AttemptFailure.NoPlayableAudio, is AttemptFailure.NoPlayableVideo,
            is AttemptFailure.Unreadable, is AttemptFailure.DiscCopy -> shortFailure(failure)
            AttemptFailure.NoVideo, is AttemptFailure.NoMatchingEpisode -> failure.text
            // A torrent's (L460), as the play sheet says them where they name no service.
            AttemptFailure.NoLongerCached -> NO_LONGER_CACHED
            AttemptFailure.RemovedByService, is AttemptFailure.ServiceRefused, AttemptFailure.NoWorkingLink -> failure.text
            // Its account's own refusals make the row wait ([serviceWait]); in a row's words where one ends it all the same.
            is AttemptFailure.KeyRefused -> DownloadHostRules.CHECK_SERVICE
            is AttemptFailure.ServiceLimit -> DownloadHostRules.SERVICE_LIMIT
            is AttemptFailure.ServiceDown -> DownloadHostRules.CANT_REACH_SERVICE
            is AttemptFailure.FetchFailed -> "Couldn't get the NZB"
            is AttemptFailure.Unsupported -> "Can't be read: an archive of a kind not supported"
            is AttemptFailure.Failed -> when (failure.error) {
                is UsenetError.Corrupt -> DAMAGED
                is UsenetError.MissingArticle -> INCOMPLETE
                is UsenetError.Timeout -> "Your Usenet provider didn't answer in time"
                is UsenetError.Network -> "The connection was lost"
                else -> OPEN_FAILED
            }
            else -> OPEN_FAILED
        }

        private const val OPEN_FAILED = "Couldn't open the release"

        /** How long Resolve waits for the app's start to give the engine the providers saved, in a new process. */
        private val ENGINE_WAIT = 30.seconds

        /**
         * How a download's log lines name [row]'s releases, by key: "download <id>, release <n>", n its place among the
         * row's candidates, never by the release's name. Resolve's, the pipeline's and the memory of releases tried's
         * lines name them so, as does the runner's for a release found missing as it is written.
         */
        internal fun naming(row: Download): (key: String) -> String {
            val places = row.candidates.map { ranked(it).key }.distinct().withIndex().associate { (i, key) -> key to i + 1 }
            return { key -> "download ${row.id}, release ${places[key] ?: "?"}" }
        }

        /**
         * The app's memory of releases found missing ([missingReleases]) for a resolve, as a play that wasn't chosen reads it,
         * its lines naming each release as the resolve names it.
         */
        fun remembered(missingReleases: MissingReleases): (Map<String, NewznabItem>, (String) -> String?, (PlayCandidate) -> String) -> TriedReleases =
            { items, indexerName, named -> missingReleases.forPlay(items, chosen = false, named, indexerName) }

        /**
         * [candidate] as a play tries it: rebuilt as Replay rebuilds a release no search returned ([Replay.candidate]), its
         * size and its indexer's passworded flag kept; a torrent's as a torrent (L460), its hash from its content id, which
         * [attempt] gives the accounts on.
         */
        private fun ranked(candidate: DownloadCandidate): RankedItem =
            Replay.candidate(
                RememberedRelease(candidate.indexerId, candidate.guid, candidate.title, candidate.downloadTemplate, fileIndex = 0, source = candidate.source),
            ).let { it.copy(item = it.item.copy(sizeBytes = candidate.size, passworded = candidate.passworded)) }

        /**
         * Whether [row] downloads a torrent (L422): its release is one; before it has one, every release it has left to try
         * is one, or, with none left to try and no search to come, it waits on a debrid service in that wait's words. One
         * that waits so with a Usenet release still to try, or its season's search to come, doesn't: [waitsOnService].
         */
        internal fun torrent(row: Download): Boolean {
            row.release?.let { return it.source == ReleaseFacts.TORRENT }
            val left = row.candidates.filter { ranked(it).key !in row.tried }
            return if (left.isNotEmpty()) left.all { it.source == ReleaseFacts.TORRENT } else waitsOnService(row) && !DownloadSearch.searches(row)
        }

        /** Whether [row] waits on a debrid service, in one of its waits' words (L474). */
        internal fun waitsOnService(row: Download): Boolean = row.reason in SERVICE_WAITS

        /** The waits a row has only for a debrid service (L474). */
        private val SERVICE_WAITS = setOf(
            DownloadHostRules.CHECK_SERVICE, DownloadHostRules.CANT_REACH_SERVICE, DownloadHostRules.SERVICE_LIMIT, DownloadHostRules.WAITS_FOR_STREAM,
        )

        /**
         * The app's: the engine checking every article of what would be written (L341), the container's preparer, store and
         * track probe, the indexers the search asks, the coordinator's grab (its test seam), the memory of releases found
         * missing as a play that wasn't chosen reads it, and Settings' "Releases to try"; a season pack's prepare held for its
         * season handed back over the engine's prepare ([PlayingPack.preparer]), the download ranking's context, the turn's
         * search over the indexers a search asks ([DownloadSearch.of]), the indexers' limits kept in the walk's memory
         * ([limits]: the runner's, L443), and the library's release that played a title last; for torrents (L460), the
         * listings the services prepare, the accounts on through the one gated lookup (L422), the torrent indexers kept
         * from the service, the memory of what the accounts failed, the links a probe reads by, and what the accounts were
         * added to. A test gives its own [indexers] and [attempts], a [probe] that reads nothing where its releases are no
         * film, a [prepareBackend] that counts the prepares, and a [search] of its own finder.
         */
        @SuppressLint("VisibleForTests") // The coordinator's grab, read as each grab is made, so a test serves both.
        fun of(
            container: AppContainer,
            storage: DownloadStorage,
            indexers: suspend () -> List<IndexerConfig> = container::indexerConfigs,
            attempts: suspend () -> Int = { container.settings.playback.first().attempts },
            probe: TrackProbe = TrackProbe { target -> container.trackProbe.probe(target) },
            prepareBackend: PrepareBackend? = null,
            limits: DownloadSearch.Limits = DownloadSearch.Limits.None,
            search: DownloadSearch = DownloadSearch.of(container, storage, limits),
        ) = DownloadResolver(
            downloads = container.data.downloads,
            storage = Storage.of(storage),
            availability = EngineAvailabilityBackend(container.engine, checkAll = { true }),
            preparer = prepareBackend?.let { NzbPreparer(it) } ?: container.preparer,
            store = container.store,
            probe = probe,
            indexers = indexers,
            fetchNzb = { indexer, item -> PlaybackCoordinator.fetchNzb(container, indexer, item) },
            tried = remembered(container.missingReleases),
            attempts = attempts,
            providersChecked = {
                when {
                    // As a play decides whether Usenet can play: by the providers saved and on.
                    container.settings.usableProviders().isEmpty() -> false
                    // Saved, and the engine given them by the app's start (AppContainer.start), which a host in a new process
                    // can come before.
                    withTimeoutOrNull(ENGINE_WAIT) { container.engine.configuredProviders.first { it.isNotEmpty() } } == null -> null
                    else -> container.engine.checkedInUse().isNotEmpty()
                }
            },
            providersLeftOut = { container.engine.leftOut.value.isNotEmpty() },
            providerFallback = { container.engine.fallback.value },
            heldPreparer = { held -> PlayingPack.preparer(held, prepareBackend ?: EnginePrepareBackend(container.engine)) },
            downloadContext = container::downloadRankingContext,
            search = search,
            lastRelease = container.library::lastRelease,
            limits = limits,
            listings = container.debridListings,
            accounts = { id -> container.services.gated(id) },
            accountsOn = { container.services.on().map { it.id } },
            offService = { IndexerConfigs.offService(container.settings.settings.first()) },
            misses = container.serviceMisses,
            links = container.serviceLinks,
            adds = container.debridAdds,
        )
    }
}

private val log = LoggerFactory.getLogger(DownloadResolver::class.java)
