package io.skjaere.usenet.app.download

import android.annotation.SuppressLint
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.ProbedTracksSerializer
import io.skjaere.usenet.app.play.Replay
import io.skjaere.usenet.app.play.STEP_LABELS
import io.skjaere.usenet.app.play.VideoTiming
import io.skjaere.usenet.app.play.indexerGrab
import io.skjaere.usenet.app.play.playCandidate
import io.skjaere.usenet.app.play.savedRelease
import io.skjaere.usenet.app.play.shortFailure
import io.skjaere.usenet.app.play.skippedNotice
import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.AvailabilityBackend
import io.skjaere.usenet.core.EngineAvailabilityBackend
import io.skjaere.usenet.core.FileCandidate
import io.skjaere.usenet.core.FetchedNzb
import io.skjaere.usenet.core.FileChooser
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.NzbPreparer
import io.skjaere.usenet.core.NzbSource
import io.skjaere.usenet.core.PipelineEvent
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.core.PlayPipeline
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
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.NewznabError
import io.skjaere.usenet.newznab.NewznabException
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.NzbDownload
import io.skjaere.usenet.search.RankedItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

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
 * device is checked again for its exact size ("Won't fit" where it doesn't fit); its NZB and password go in the download's
 * own folder (L346); its release, file, exact size, whether it is the release's one video (L342) and what the probe read
 * go in the row; and the entry the probe put in the NZB store is taken out again (L383), so downloads take none of the
 * places Resume needs there.
 *
 * A download resolved before whose process died isn't grabbed again: its NZB is prepared again from its own copy and its
 * file found by name. One whose size no longer matches starts again from its first byte.
 *
 * Its own log lines name the download by its id, never the release; the pipeline's and the memory of tried releases' name
 * the release as a play's do.
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
    /** What is remembered of releases found missing, for a resolve of these items (by key), as a play's that wasn't chosen. */
    private val tried: (items: Map<String, NewznabItem>, indexerName: (String) -> String?) -> TriedReleases,
    /** Settings' "Releases to try". */
    private val attempts: suspend () -> Int,
    /** Waits for the engine's check of the providers, as a play does before its first release: whether one is in use. */
    private val providersChecked: suspend () -> Boolean,
    private val providersLeftOut: () -> Boolean = { false },
    private val providerFallback: () -> Boolean = { false },
    private val clock: Clock = Clock.System,
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
            }
        }
    }

    /** How a resolve ended. */
    sealed interface Resolution {
        /**
         * Found: [row] as it is now kept, its release, file and exact size, to be written from its safe offset (0 for a
         * release other than the one its file was made from); [nzb] prepared, and its [file] in it.
         */
        class Ready(val row: Download, val nzb: PreparedNzb, val file: FileCandidate) : Resolution

        /** Ended: the row is Failed, for [reason] in its words. */
        data class Failed(val reason: String) : Resolution

        /** Can't go on until something changes: the row waits, for [reason] (the providers, storage), and the host may end (L388). */
        data class Waiting(val reason: String) : Resolution

        /**
         * The providers couldn't be reached ([error]'s kind), or the indexers: the row says so ([words]) while the host backs
         * off and resolves it again, as the writer does for a reopen that got no connection (L382). Nothing was counted.
         */
        data class Unreachable(val error: UsenetError, val words: String = DownloadHostRules.CANT_REACH_PROVIDER) : Resolution

        /** The row is gone: removed meanwhile. */
        data object Gone : Resolution
    }

    /**
     * Resolves download [id] (L338): opened again from its own NZB where it has one and the file is still there; else its
     * candidates not tried yet, as the class says.
     */
    suspend fun resolve(id: String): Resolution {
        val row = downloads.get(id) ?: return Resolution.Gone
        reopened(row)?.let { return it }
        return fromCandidates(downloads.get(id) ?: return Resolution.Gone)
    }

    /**
     * [row] opened again from its own NZB (L346): prepared from the copy, no grab, its file found by its name (a position
     * can name another file once prepared again). The same size goes on from the safe offset; another starts again from
     * its first byte, as nzb-streamer now lists the file otherwise. Null where it has no copy, or the copy no longer gives
     * the file: then its candidates are resolved, the copy dropped first so that is never tried again.
     */
    private suspend fun reopened(row: Download): Resolution? {
        val release = row.release ?: return null
        val size = row.size ?: return null
        val source = storage.source(row.id) ?: return null
        val prepared = when (val outcome = preparer.prepare(source.nzb, release.title, source.password)) {
            is PrepareOutcome.Ready -> outcome.nzb
            is PrepareOutcome.Failed -> providers(row.id, outcome.error)?.let { return it }
            else -> null
        }
        val file = prepared?.find(release.fileIndex, row.fileName ?: release.fileName, null)
        if (prepared == null || file == null) {
            log.info("download {}: its own NZB no longer opens to its file: resolving it again", row.id)
            storage.dropSource(row.id)
            return null
        }
        if (file.size == size) {
            log.info("download {}: opened again from its own NZB at {} of {} bytes", row.id, row.safeOffset, size)
            return Resolution.Ready(row, prepared, file)
        }
        // What was written can't be told to be this file's: it starts again from its first byte.
        log.info("download {}: its file is listed at {} bytes now, not {}: starting again from 0", row.id, file.size, size)
        val again = startOver(row)?.copy(
            release = release.copy(fileIndex = file.index, fileSize = file.size), size = file.size, updatedAt = clock.now(),
        ) ?: return waiting(row.id, DownloadHostRules.STORAGE_UNAVAILABLE)
        downloads.update(again)
        return room(again) ?: Resolution.Ready(again, prepared, file)
    }

    /**
     * [row]'s candidates not tried yet, through the play's pipeline, as the class says. One whose attempt had no answer
     * (the providers out of reach, the attempt cut short, the volume away as it was picked) is tried again first, from the
     * NZB kept for it ([Storage.keepGrab]) and counted once: its grab is spent already.
     */
    private suspend fun fromCandidates(row: Download): Resolution {
        val id = row.id
        val kept = storage.grab(id)?.takeIf { grab -> row.candidates.any { ranked(it).key == grab.key } }
        val counted = row.tried.filter { it != kept?.key }
        val left = minOf(MAX_TRIES, attempts()) - counted.size
        val items = row.candidates.map(::ranked).filter { it.key !in counted }.distinctBy { it.key }
        if (left <= 0 || items.isEmpty()) {
            log.info("download {}: no release left to try, {} tried", id, row.tried.size)
            return failed(id, NO_OTHER_RELEASE)
        }
        var said: String? = null
        suspend fun say(words: String) {
            if (words == said) return
            said = words
            downloads.setState(id, DownloadState.Resolving, words, clock.now())
        }
        say(DownloadHostRules.FINDING_RELEASES)
        // As a play's: a release is looked up by the providers the engine has checked, which it does first when it hasn't
        // since they changed (a new process, a provider saved). With none saved and on, nothing is grabbed: the download
        // waits for one (L382).
        if (!providersChecked()) return waiting(id, DownloadHostRules.CHECK_PROVIDER)
        val byKey = items.associateBy { it.key }
        val newznab = byKey.mapValues { it.value.item }
        val configs = indexers().associateBy { it.id }
        // Whether the last grab failed for an outage on the indexer's side (out of reach, a 5xx): no NZB came, nothing spent.
        var indexerOut = false
        val grabs = indexerGrab(newznab, configs) { indexer, item ->
            try {
                fetchNzb(indexer, item).also { indexerOut = false }
            } catch (e: NewznabException) {
                indexerOut = e.error.isOutage()
                throw e
            }
        }
        // Each NZB grabbed kept until its attempt has its answer; the one kept before served from it, with no grab.
        val nzbs = object : NzbSource {
            override suspend fun fetch(candidate: PlayCandidate): ByteArray = fetchWithPassword(candidate).bytes

            override suspend fun fetchWithPassword(candidate: PlayCandidate): FetchedNzb {
                kept?.takeIf { it.key == candidate.id }?.let { grab ->
                    log.info("download {}: its release tried again from the NZB kept for it, no grab", id)
                    return FetchedNzb(grab.source.nzb, grab.source.password)
                }
                return grabs.fetchWithPassword(candidate).also { storage.keepGrab(id, candidate.id, it.bytes, it.password) }
            }
        }
        val usenet = UsenetSource(nzbs, availability, preparer, store, providersLeftOut, providerFallback, FileChooser.None)
        val pipeline = PlayPipeline({ usenet }, probe, maxAttempts = left, tried = tried(newznab) { indexerId -> configs[indexerId]?.name })
        // Counted across its resolves: the second release in all is "release 2", whichever resolve tries it.
        val before = counted.size
        var counting = counted
        var attempt = 0
        var of = 0
        // The attempt under way, and whether it has had its answer about its release (a step of it failed for the release).
        var current: String? = null
        var answered = false
        // The connection lost or timed out in the attempt before: no other release is grabbed while it lasts (L382).
        var lost: UsenetError? = null
        // Grabs that failed for an outage on the indexer's side, uncounted.
        var indexerOutages = 0
        val skipped = mutableListOf<String>()
        var ended: PipelineEvent? = null
        log.info("download {}: resolving: up to {} of the {} release{} not tried yet", id, minOf(left, items.size), items.size, if (items.size == 1) "" else "s")
        try {
            pipeline.play(items.map { it.playCandidate }, picker(row)).collect { event ->
                // Ended at the next event, once the attempt before has let go of what it held.
                lost?.let { throw OutOfReach(it) }
                when (event) {
                    is PipelineEvent.AttemptStarted -> {
                        attempt = before + event.attempt
                        of = before + event.of
                        current = event.candidate.id
                        answered = false
                        // Before its grab: a process that dies mid-attempt never grabs it a fourth time (L340).
                        counting = counting + event.candidate.id
                        count(id, counting)
                    }
                    is PipelineEvent.Step -> when (val status = event.status) {
                        is StepStatus.Running -> say(DownloadHostRules.resolving(STEP_LABELS.getValue(event.step), attempt, of))
                        is StepStatus.Failed -> when (val failure = status.failure) {
                            // The providers failing is no answer about the release, nor a connection lost.
                            is AttemptFailure.Provider -> Unit
                            is AttemptFailure.Failed -> if (failure.error.isLostConnection()) lost = failure.error else answered(id).also { answered = true }
                            is AttemptFailure.FetchFailed -> if (indexerOut) {
                                // No NZB came: the release isn't counted, and the next may be another indexer's.
                                current?.let { counting = counting - it }
                                count(id, counting)
                                indexerOutages++
                            } else {
                                answered(id).also { answered = true }
                            }
                            // The release's answer: its NZB is no use kept.
                            else -> answered(id).also { answered = true }
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
            if (cut != null && !answered) withContext(NonCancellable) { count(id, counting - cut) }
            throw e
        } catch (e: OutOfReach) {
            // Nothing known of the release: not counted, and tried again from its NZB once the wait is over.
            current?.let { count(id, counting - it) }
            return unreachable(id, e.error, DownloadHostRules.CANT_REACH_PROVIDER)
        }
        return when (val end = ended) {
            is PipelineEvent.Succeeded -> resolved(id, end, byKey)
            is PipelineEvent.Exhausted -> exhausted(id, end, counting, skipped, indexerOutages > 0)
            else -> failed(id, NO_OTHER_RELEASE)
        }
    }

    /** The attempt under way had its answer about its release: the NZB kept for it is no use now. */
    private suspend fun answered(id: String) = storage.dropGrab(id)

    /** A connection lost in an attempt ([lost]): it ends the resolve before another release is grabbed. */
    private class OutOfReach(val error: UsenetError) : Exception(error::class.simpleName)

    /**
     * [succeeded]'s release kept as the download's, as the class says: the room checked for its exact size, then its NZB
     * and password copied. The release its file was made from, its file of the same name and size, goes on from the safe
     * offset (Try again after "Storage is full"); another starts from 0, any file made for the one before deleted.
     */
    private suspend fun resolved(id: String, succeeded: PipelineEvent.Succeeded, byKey: Map<String, RankedItem>): Resolution {
        val target = succeeded.target as UsenetTarget
        try {
            val row = downloads.get(id) ?: return Resolution.Gone
            val release = savedRelease(byKey.getValue(target.candidate.id).item, target).toRemembered()
            val size = target.file.size
            val same = row.size == size && row.release?.let { Replay.key(it) == target.candidate.id && it.fileName == release.fileName } == true
            val base = (if (same) row else startOver(row)) ?: return waiting(id, DownloadHostRules.STORAGE_UNAVAILABLE)
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
            // A wait for the volume keeps the NZB grabbed for it: the release is taken up again once the volume is back.
            room(kept)?.let { return it }
            // The archive's password as the NZB store keeps one (the NZB's own, else the indexer's), never logged.
            storage.keepSource(id, target.nzbBytes, target.prepared.metadata.orderedArchiveNzb.password ?: target.password)
            storage.dropGrab(id)
            log.info("download {}: resolved, {} bytes, {}", id, size, if (kept.oneVideo) "its release's one video" else "one of its release's videos")
            return Resolution.Ready(kept, target.prepared, target.file)
        } finally {
            // The probe kept its NZB in the store to read it: out again, unless a play kept it there before (L383).
            withContext(NonCancellable) {
                try {
                    target.drop()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("download {}: couldn't take its NZB out of the store: {}", id, e::class.simpleName)
                }
            }
        }
    }

    /**
     * The releases ran out (L340). The providers failing, not the release (the last failure stops their route): it isn't
     * counted, and the download waits for them. Else it fails, in the last failure's words, or, every release left passed
     * over as found missing before, in the play sheet's words for those.
     */
    private suspend fun exhausted(id: String, ended: PipelineEvent.Exhausted, counted: List<String>, skipped: List<String>, indexerOut: Boolean): Resolution {
        val last = ended.failedAttempts.lastOrNull()
            ?: return failed(id, skipped.takeIf { it.isNotEmpty() }?.let(::skippedNotice) ?: NO_OTHER_RELEASE)
        val (candidate, failure) = last
        if (failure is AttemptFailure.Provider) {
            count(id, counted - candidate.id)
            providers(id, failure.error)?.let { return it }
        }
        // Releases left untried for want of their NZBs: the indexers are asked again once the wait is over (L382).
        if (indexerOut) return unreachable(id, UsenetError.Network(null), DownloadHostRules.CANT_REACH_INDEXER)
        log.info("download {}: {} release{} tried, the last failing as {}", id, counted.size, if (counted.size == 1) "" else "s", failure::class.simpleName)
        return failed(id, reason(failure))
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

    /** Out of reach for [error], the row saying so in [words] while the host backs off and resolves it again. */
    private suspend fun unreachable(id: String, error: UsenetError, words: String): Resolution {
        log.info("download {}: {} ({})", id, if (words == DownloadHostRules.CANT_REACH_INDEXER) "the indexers couldn't give an NZB" else "the providers couldn't be reached", error::class.simpleName)
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

    /** [row] with nothing written, any file it made deleted; null while its volume isn't there to delete it from. */
    private suspend fun startOver(row: Download): Download? {
        if (row.relativePath != null) {
            try {
                storage.deleteVideo(row.id)
            } catch (e: DownloadStorage.Unavailable) {
                return null
            }
        }
        return row.copy(safeOffset = 0, gaps = emptyList(), relativePath = null)
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

    /** The episode's file in a release, else its largest video. */
    private fun picker(row: Download): FilePicker {
        val season = row.season
        val episode = row.episode
        return if (row.kind == DownloadKind.Episode && season != null && episode != null) FilePicker.Episode(season, episode) else FilePicker.Largest
    }

    companion object {
        /** At most this many releases are tried for one download (L340). */
        const val MAX_TRIES = 3

        /** The exact size is more than the room on the device (L338). */
        const val WONT_FIT = "Won't fit"

        /** Nothing left to try: every candidate tried, or its tries used up. */
        const val NO_OTHER_RELEASE = "No other release to try"

        /** A release that lacks articles: the play sheet's words for it, and the runner's for one given up as it was written. */
        const val INCOMPLETE = "Release incomplete"

        /** A release whose articles can't be read. */
        const val DAMAGED = "Release damaged"

        /** As the player's launch reads its own: what a later version adds is passed over. */
        private val json = Json { ignoreUnknownKeys = true }

        /** The connection lost or timed out: said of no release, which may be whole (L382). */
        private fun UsenetError.isLostConnection() = this is UsenetError.Network || this is UsenetError.Timeout

        /** An indexer out of reach, or failing on its side (a 5xx): no answer about the release, nor a refusal of the account. */
        private fun NewznabError.isOutage() = this is NewznabError.Network || this is NewznabError.Timeout || this is NewznabError.Server

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

        /**
         * [candidate] as a play tries it: rebuilt as Replay rebuilds a release no search returned ([Replay.candidate]), its
         * size and its indexer's passworded flag kept.
         */
        private fun ranked(candidate: DownloadCandidate): RankedItem =
            Replay.candidate(RememberedRelease(candidate.indexerId, candidate.guid, candidate.title, candidate.downloadTemplate, fileIndex = 0))
                .let { it.copy(item = it.item.copy(sizeBytes = candidate.size, passworded = candidate.passworded)) }

        /**
         * The app's: the engine checking every article of what would be written (L341), the container's preparer, store and
         * track probe, the indexers the search asks, the coordinator's grab (its test seam), the memory of releases found
         * missing as a play that wasn't chosen reads it, and Settings' "Releases to try". A test gives its own [indexers] and
         * [attempts], and a [probe] that reads nothing where its releases are no film.
         */
        @SuppressLint("VisibleForTests") // The coordinator's grab, read as each grab is made, so a test serves both.
        fun of(
            container: AppContainer,
            storage: DownloadStorage,
            indexers: suspend () -> List<IndexerConfig> = container::indexerConfigs,
            attempts: suspend () -> Int = { container.settings.playback.first().attempts },
            probe: TrackProbe = TrackProbe { target -> container.trackProbe.probe(target) },
        ) = DownloadResolver(
            downloads = container.data.downloads,
            storage = Storage.of(storage),
            availability = EngineAvailabilityBackend(container.engine, checkAll = { true }),
            preparer = container.preparer,
            store = container.store,
            probe = probe,
            indexers = indexers,
            fetchNzb = { indexer, item -> PlaybackCoordinator.fetchNzb(container, indexer, item) },
            tried = { items, indexerName -> container.missingReleases.forPlay(items, chosen = false, indexerName) },
            attempts = attempts,
            providersChecked = { container.engine.checkedInUse().isNotEmpty() },
            providersLeftOut = { container.engine.leftOut.value.isNotEmpty() },
            providerFallback = { container.engine.fallback.value },
        )
    }
}

private val log = LoggerFactory.getLogger(DownloadResolver::class.java)
