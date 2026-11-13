package io.skjaere.usenet.app.debrid

import io.skjaere.usenet.app.play.UsableRoutes
import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.AttemptSteps
import io.skjaere.usenet.core.FileChooser
import io.skjaere.usenet.core.FileListing
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.ListedFile
import io.skjaere.usenet.core.PipelineEvent
import io.skjaere.usenet.core.PipelineStep
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.core.PlaySource
import io.skjaere.usenet.core.PlaybackTarget
import io.skjaere.usenet.core.ProbeResult
import io.skjaere.usenet.core.StepResult
import io.skjaere.usenet.core.StepStatus
import io.skjaere.usenet.core.StepWatch
import io.skjaere.usenet.core.UsenetError
import io.skjaere.usenet.core.discCopy
import io.skjaere.usenet.core.unreadableFailure
import io.skjaere.usenet.core.unstreamable
import io.skjaere.usenet.debrid.DebridService
import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.ServiceError
import io.skjaere.usenet.debrid.ServiceException
import io.skjaere.usenet.debrid.ServiceFile
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.debrid.ServiceListing
import io.skjaere.usenet.player.LinkFailure
import io.skjaere.usenet.player.LinkUnavailable
import io.skjaere.usenet.player.PlayUris
import io.skjaere.usenet.releases.ReleaseKey
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toKotlinDuration

/**
 * The releases debrid services have cached, for the play pipeline (L203, W80): whatever a service needs before a link is
 * one Preparing step, whichever service it is (TorBox adds the torrent to the account first; Premiumize adds nothing),
 * then Opening gets the picked file's link, and the pipeline probes the file. The accounts that said the release is
 * cached ([PlayCandidate.routes], in the user's order, L228) are tried in their order within the one attempt: one that
 * no longer has it, or fails it, or whose route a failure has stopped, passes it on to the next; one whose limit,
 * refused key or outage stops it stops it for the rest of the play ([AttemptSteps.stop]). The service's own failure at
 * Opening or at the probe does too, before the next release (DEP-6, [retarget]): the steps run again from Preparing on
 * the next account left, which the sheet says. [accounts] gives an account's client by its id; null for one since signed
 * out of.
 *
 * The file is picked from the service's listing before anything is added (L179), with the play's [FilePicker], and where
 * names don't tell, by the user ([chooser]); a release whose video is only in RAR volumes can't be streamed (L180), and one
 * whose files are a copy of a disc fails there too (W103, L311), nothing added and no other account tried. What
 * the service prepared is kept in [listings] by an opaque id, which the file's URI names (L171, L181). A failure's words
 * name no torrent, release, file, account or link; only a refused key names the account's service.
 *
 * What an account failed of a release is remembered in [misses] (L174), and an account remembered so for it is passed
 * over without a call, unless the user [chosen] the release; an account that prepares it forgets it. The link Opening got
 * is held in [links] for the probe and the player to open the file with (L172), never in the target or the store. The
 * account a release is tried on is said to [onTrying] as its Preparing starts there, and the step said running again, for
 * the sheet's tag to name its service at once, not once it has prepared (DEP-10, COT-2). The listing kept is said to
 * [onListed] as Preparing keeps it, for the play to hold what it plays from (L234).
 */
class ServiceSource(
    private val accounts: suspend (accountId: String) -> DebridService?,
    private val listings: DebridListingStore,
    private val chooser: FileChooser = FileChooser.None,
    private val misses: ServiceMisses? = null,
    private val chosen: Boolean = false,
    private val links: ServiceLinks? = null,
    private val onTrying: (candidate: PlayCandidate, account: String) -> Unit = { _, _ -> },
    private val onListed: suspend (listingId: String) -> Unit = {},
) : PlaySource<ServiceTarget> {
    override val steps: List<PipelineStep> get() = STEPS

    /** What Preparing made ready: the account's [service] by its [route], the [entry] it is kept as, and the picked [file] of its [files]. */
    private class Ready(val service: DebridService, val route: String, val entry: DebridListingStore.Entry, val files: ServiceFiles, val file: ServiceFiles.File)

    /**
     * What one release's attempt has come to so far (DEP-6): the accounts asked to prepare it, which the next account's try
     * passes over, and the file the user chose from one account's listing ([answered]), found again in the next one's
     * rather than asked again.
     */
    private class Release {
        val tried = HashSet<String>()
        var answered: ListedFile? = null
    }

    /** Each release's, by its id, from its attempt's start: the pipeline runs one attempt at a time, each its own. */
    private val releases = ConcurrentHashMap<String, Release>()

    override suspend fun AttemptSteps.attempt(candidate: PlayCandidate, pickFile: FilePicker): ServiceTarget =
        open(candidate, pickFile, Release().also { releases[candidate.id] = it })

    /**
     * DEP-6 (L231, W86): the release again, after its service's own [failure] at Opening or at the probe ([triesNextAccount]),
     * on the next account left that has it cached, in the user's order: one not tried yet in this attempt, whose route no
     * failure has stopped, that isn't remembered as having failed it (unless the user [chosen] it) and still signed in. Its
     * steps run again from Preparing ([open]), once the sheet is told ([AttemptSteps.retrying]); the failed account's route
     * stops first, as the attempt ending would have stopped it, so the play's later releases pass it over too. None for the
     * release's own failures, or with no such account left: the attempt ends with [failure], and the play moves on.
     */
    override suspend fun AttemptSteps.retarget(candidate: PlayCandidate, failure: AttemptFailure, pickFile: FilePicker): ServiceTarget? {
        val release = releases[candidate.id] ?: return null
        if (!triesNextAccount(failure) || !anyLeft(candidate, release)) return null
        failure.route?.let { stop(it) }
        retrying(failure)
        return open(candidate, pickFile, release)
    }

    /**
     * Whether [candidate] has an account left that [prepare] would ask for [release]: one not tried, stopped or remembered
     * as having failed it, and still signed in. No service is asked. What is remembered (the library's) or an account's
     * client (the settings') failing to read counts as none left: this runs outside any step, where the failure would end
     * the whole play, so the release's attempt ends with its own failure instead, as that read failing in Preparing would.
     */
    private suspend fun AttemptSteps.anyLeft(candidate: PlayCandidate, release: Release): Boolean = try {
        candidate.routes.any { route ->
            val account = UsableRoutes.accountOf(route)
            route !in stopped && account != null && account !in release.tried &&
                misses?.takeUnless { chosen }?.known(candidate, account) == null && accounts(account) != null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A full or locked disk; its words may name what a log mustn't (L208), so its kind alone.
        log.warn("debrid: the next account to try didn't read ({})", e.javaClass.simpleName)
        false
    }

    /**
     * [candidate]'s release opened from the first of its accounts [release] hasn't tried that prepares it ([prepare]), then
     * its picked file's link asked of that account (Opening): the target the pipeline probes.
     */
    private suspend fun AttemptSteps.open(candidate: PlayCandidate, pickFile: FilePicker, release: Release): ServiceTarget {
        val ready = step(candidate, PipelineStep.Prepare) { watch -> prepare(candidate, pickFile, release, watch) }
        // What plays: what Preparing made ready, or what the account added again for Opening's link.
        var entry = ready.entry
        var files = ready.files
        var file = ready.file
        step(candidate, PipelineStep.Open) {
            try {
                val link = ready.service.link(ready.entry.prepared, ready.file.file)
                // Added again for this link, the account no longer having it (L234, ELC-3): kept in place of the old under the
                // same listing id, as a refresh's is, so the play and its next fresh link ask by the new handle.
                link.prepared?.let { again ->
                    entry = listings.put(ready.entry.account, ready.entry.title, again)
                    files = ServiceFiles(again.hash, candidate.title, again.listing)
                    file = files.files[ready.file.index]
                }
                // The link the probe and the player open with first: no call of the service's for it.
                links?.hold(entry.id, file.file, link)
                StepResult.Ok(Unit, file.name)
            } catch (e: ServiceException) {
                StepResult.Fail(failureOf(e.error, ready.route, ready.service.kind).also { failure -> misses?.record(candidate, ready.entry.account, failure) })
            }
        }
        return ServiceTarget(candidate, files, file, entry.id, entry.account, listings)
    }

    /**
     * Preparing: the release from its accounts in the user's order, but those [release] has tried, those a failure has
     * stopped, and those remembered as having failed it; each one asked is [release]'s from then on. The first that
     * prepares it is ready, its listing kept; one that no longer has it or fails passes it on, and the last's failure is
     * the step's.
     */
    private suspend fun AttemptSteps.prepare(candidate: PlayCandidate, pickFile: FilePicker, release: Release, watch: StepWatch): StepResult<Ready> {
        val hash = checkNotNull(InfoHash.parse(candidate.id.removePrefix(ReleaseKey.TORRENT))) { "Not a torrent's release" }
        var last: AttemptFailure? = null
        for (route in candidate.routes) {
            if (route in stopped) continue
            val account = UsableRoutes.accountOf(route) ?: continue
            if (account in release.tried) continue
            misses?.takeUnless { chosen }?.known(candidate, account)?.let {
                last = it
                continue
            }
            val service = accounts(account)
            if (service == null) {
                // Signed out of since the search: nothing more of this play goes to it.
                stop(route)
                last = AttemptFailure.ServiceDown(route)
                continue
            }
            release.tried += account
            // The sheet reads the account now: its tag names the one being tried, the ones passed over unsaid.
            onTrying(candidate, account)
            send(PipelineEvent.Step(candidate, PipelineStep.Prepare, StepStatus.Running()))
            // Why nothing was picked from the listing: then nothing was added, and no other account lists other files.
            var unpicked: AttemptFailure? = null
            val prepared = try {
                service.prepare(hash) { listing ->
                    val files = ServiceFiles(hash, candidate.title, listing)
                    files.unstreamable()?.let {
                        unpicked = it
                        return@prepare null
                    }
                    // A copy of a disc plays as no single video (W103), from any account: each lists the same files.
                    files.discCopy()?.let {
                        unpicked = it
                        return@prepare null
                    }
                    // A file the user chose from one account's listing: found again in the next account's, not asked again.
                    val file = release.answered?.let { files.find(it.index, it.name, it.size) }
                        ?: pickFile.pick(files)
                        ?: chosen(candidate, files, pickFile, watch, chooser)?.also { release.answered = it }
                    if (file == null) unpicked = pickFile.noMatchIn(files)
                    file?.file
                }
            } catch (e: ServiceException) {
                val failure = failureOf(e.error, route, service.kind)
                failure.route?.let { stop(it) }
                misses?.record(candidate, account, failure)
                last = failure
                continue
            }
            unpicked?.let { return StepResult.Fail(it) }
            misses?.forget(candidate, account)
            val picked = prepared.picked ?: return StepResult.Fail(pickFile.noMatchIn(ServiceFiles(hash, candidate.title, prepared.listing)))
            val entry = listings.put(account, candidate.title, prepared)
            // Held by the play from now until it ends, whether it added the torrent or found it in the account (L234).
            onListed(entry.id)
            val files = ServiceFiles(hash, candidate.title, prepared.listing)
            return StepResult.Ok(Ready(service, route, entry, files, files.files[picked.index]))
        }
        return StepResult.Fail(last ?: AttemptFailure.NoLongerCached)
    }

    /**
     * The probe couldn't read the file. A link no longer to be had ([LinkUnavailable], in its cause) fails as the service's
     * own failure, by the account that prepared the listing ([linkFailureOf]), remembered as a play's failures of that
     * account are. A file the player can't read is said as every source says it ([unreadableFailure]): its bytes are the
     * release's own, and a reader that crashed may be the app's (L319), so neither is held against the account, and no
     * other account is tried, as each would list the same file. A read of the file that failed otherwise (an I/O failure
     * in its cause: the CDN's host not found, its TLS refused, its 5xx past Media3's retries) is said by its kind alone, as
     * the exception's words name the host (DFH-1): no working link from that account, remembered so. Any other of the
     * player's failures is said by its class, as [failed] says one; the probe's own time-out keeps its words, as a failure
     * to read the release, never a provider's. No route is stopped.
     */
    override suspend fun AttemptSteps.probeFailed(failed: ProbeResult.Failed, target: ServiceTarget): AttemptFailure {
        val causes = generateSequence(failed.cause) { it.cause }.take(16).toList()
        val link = causes.filterIsInstance<LinkUnavailable>().firstOrNull()
        val unreadable = unreadableFailure(failed, target.file.name)
        val failure = when {
            link != null -> linkFailureOf(link.failure, UsableRoutes.serviceRoute(target.account))
            unreadable != null -> return unreadable
            causes.any { it is IOException } -> AttemptFailure.NoWorkingLink
            else -> return AttemptFailure.Failed(UsenetError.Unknown(failed.cause?.javaClass?.simpleName ?: failed.error.detail))
        }
        return failure.also { misses?.record(target.candidate, target.account, it) }
    }

    /** Something other than the service failed a step: said by its kind alone, as its words may hold what they mustn't. */
    override fun failed(error: Exception): AttemptFailure = AttemptFailure.Failed(UsenetError.Unknown(error.javaClass.simpleName))

    companion object {
        private val log = LoggerFactory.getLogger(ServiceSource::class.java)

        /** A release's steps from a debrid service, in their order (L203): Preparing, Opening, Checking audio and video. */
        val STEPS = listOf(PipelineStep.Prepare, PipelineStep.Open, PipelineStep.ProbeTracks)

        /**
         * Whether [failure] is one of the service's own (L231), which another account that has the release cached may not
         * meet: the release then tries the next one ([retarget]). Never the release's own failures (a file it can't stream,
         * no audio or video this device plays, no file for the episode, one a Cast device can't play, anything else that
         * failed), which every account would meet.
         */
        fun triesNextAccount(failure: AttemptFailure): Boolean = when (failure) {
            AttemptFailure.NoWorkingLink, AttemptFailure.NoLongerCached, AttemptFailure.RemovedByService, is AttemptFailure.ServiceLimit,
            is AttemptFailure.ServiceRefused, is AttemptFailure.KeyRefused, is AttemptFailure.ServiceDown -> true
            else -> false
        }

        /**
         * What the service's [error] for an account of [kind], by its [route], fails a release as (L204): its limit, its
         * refusal of the key and its outage stop the route; the rest are the release's in that account alone.
         */
        fun failureOf(error: ServiceError, route: String, kind: ServiceKind): AttemptFailure = when (error) {
            ServiceError.NotCached -> AttemptFailure.NoLongerCached
            ServiceError.Removed -> AttemptFailure.RemovedByService
            is ServiceError.Limit -> AttemptFailure.ServiceLimit(error.retryAfter?.toKotlinDuration(), route)
            ServiceError.KeyRefused -> AttemptFailure.KeyRefused(kind.displayName, route)
            ServiceError.Down -> AttemptFailure.ServiceDown(route)
            is ServiceError.Refused -> AttemptFailure.ServiceRefused(error.tooLarge)
        }

        /**
         * What the player's source found of the link to a file the account by [route] gave ([failure], the service's own
         * answer to a refresh, or no working link) fails a release as: as [failureOf] would the service's answer.
         */
        fun linkFailureOf(failure: LinkFailure, route: String): AttemptFailure = when (failure) {
            LinkFailure.NoWorkingLink -> AttemptFailure.NoWorkingLink
            LinkFailure.NotCached -> AttemptFailure.NoLongerCached
            LinkFailure.Removed -> AttemptFailure.RemovedByService
            is LinkFailure.Limit -> AttemptFailure.ServiceLimit(failure.waitMs?.milliseconds, route)
            is LinkFailure.KeyRefused -> AttemptFailure.KeyRefused(failure.serviceName, route)
            LinkFailure.Down -> AttemptFailure.ServiceDown(route)
            is LinkFailure.Refused -> AttemptFailure.ServiceRefused(failure.tooLarge)
        }
    }
}

/**
 * A service's [listing] of the torrent [hash]'s files as a play picks from it, named [title] (the release's): known by the
 * release's content id, `btih:<hex>`, as a play remembers it (L181), whichever account listed it.
 */
class ServiceFiles(val hash: InfoHash, override val title: String, val listing: ServiceListing) : FileListing<ServiceFiles.File> {
    override val id: String = ReleaseKey.TORRENT + hash.hex
    override val files: List<File> = listing.files.map(::File)

    /** One of the service's files, as the pickers read it. */
    data class File(val file: ServiceFile) : ListedFile {
        override val index: Int get() = file.index
        override val path: String get() = file.path
        override val size: Long get() = file.size
    }

    // No hash or name in a log line (L208).
    override fun toString() = "ServiceFiles(${files.size} files)"
}

/**
 * The [file] of [listing] a play opened from a service: the player reads it at [uri], which names the listing by the id the
 * store keeps it as ([listingId]) and nothing else, once [keep] has written it there. [account]: the service account that
 * prepared it, whose links the player reads it by.
 */
class ServiceTarget(
    override val candidate: PlayCandidate,
    override val listing: ServiceFiles,
    override val file: ServiceFiles.File,
    val listingId: String,
    internal val account: String,
    private val listings: DebridListingStore,
) : PlaybackTarget {
    override val uri: String get() = PlayUris.service(listingId, file.index)

    override suspend fun keep() = listings.keep(listingId)

    override fun toString() = "ServiceTarget(${file.index})"
}
