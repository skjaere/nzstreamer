package io.skjaere.usenet.app.play

import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.FetchedNzb
import io.skjaere.usenet.core.FileListing
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.ListedFile
import io.skjaere.usenet.core.NzbSource
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.core.UnreadableFile
import io.skjaere.usenet.core.UsenetError
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.SearchPick
import io.skjaere.usenet.data.TitleType
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.Availability
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseKey
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.releases.ReleaseReason
import io.skjaere.usenet.search.RankedItem
import java.util.Locale

/**
 * The release that played a watch key before ("remembered", see [WatchState.release]): Resume and
 * re-watch try it first, and its NZB comes from the local store while it is still there, which
 * spares the indexer a download.
 */
internal object Replay {
    fun key(release: RememberedRelease): String = ReleaseKey.of(release.indexerId, release.guid)

    /**
     * [candidates] with [remembered] first: moved up when they hold it (same indexer and guid),
     * added ahead of them when they don't but its NZB is [stored], else left out. [first] false
     * (a release chosen by hand, which is tried alone) keeps [candidates] as they are. A torrent the
     * search just returned [hidden] (their keys: not cached, not checked, not for the service) has
     * the search's word on it now, and isn't added back (W71, DDC-2).
     */
    fun order(candidates: List<RankedItem>, remembered: RememberedRelease?, stored: Boolean, first: Boolean = true, hidden: Set<String> = emptySet()): List<RankedItem> {
        if (remembered == null || !first) return candidates
        val (hit, rest) = candidates.partition { it.key == key(remembered) }
        return when {
            hit.isNotEmpty() -> hit + rest
            stored && !(remembered.isTorrent && key(remembered) in hidden) -> listOf(candidate(remembered)) + candidates
            else -> candidates
        }
    }

    /** Whether [this] played from a debrid service's cache, not from Usenet (L181). */
    private val RememberedRelease.isTorrent: Boolean get() = source != null && source != ReleaseFacts.USENET

    /**
     * [picker], except in [remembered]'s NZB (by its id): there the file that played before, and
     * only its volumes are checked. A request's own picker may not find that file, nor check it so:
     * a Search episode group's takes the largest file and checks the whole release, which in a
     * remembered season pack is another episode, and a gap in any episode would reject the pack.
     */
    fun picker(picker: FilePicker, remembered: RememberedRelease?, watch: WatchTarget): FilePicker =
        remembered?.nzbId?.let { id -> file(remembered, picker, watch).copy(nzbId = id, release = remembered.title) } ?: picker

    /**
     * The file [remembered] played for [watch] ([RememberedFile]), in any NZB of it: one grabbed again
     * may not be byte for byte the one that played. Where it isn't there, the episode [watch] is plays
     * from it, or, played without TMDB, the one the release's name or else the file's gives; [picker]'s
     * pick from a film's; the video of a release of one, played without TMDB and named for no episode;
     * nothing else, as nothing tells the episode: from a season pack, an anime batch or a daily pack,
     * the largest file would be another (#179).
     */
    fun file(remembered: RememberedRelease, picker: FilePicker, watch: WatchTarget): RememberedFile {
        val named = ReleaseParser.parse(remembered.title)
        val wanted = watch.episodeTarget?.let { FilePicker.Episode(it.season, it.episode) }
            ?: picker.takeIf { watch.kind == WatchKind.Movie }
            ?: episodeOf(remembered.title)
            ?: remembered.fileName?.let(::episodeOf)
            ?: SoleVideo.takeIf { named.seasons.isEmpty() && named.episodes.isEmpty() }
        return RememberedFile(remembered.fileIndex, picker, name = remembered.fileName, size = remembered.fileSize, wanted = wanted)
    }

    /** The episode [name] (a release's or a file's) says it is, when it says one: "S02E05". */
    private fun episodeOf(name: String): FilePicker.Episode? = ReleaseParser.parse(name).let { parsed ->
        parsed.seasons.singleOrNull()?.let { season -> parsed.episodes.singleOrNull()?.let { FilePicker.Episode(season, it) } }
    }

    /**
     * Whether [remembered] was tried and failed through its own fault (articles gone or damaged,
     * passworded, nothing in it this device can play), not its indexer's, the provider's or the
     * network's: it is then forgotten, so Resume and replay don't choose it again. Articles missing
     * while a provider is left out (its login rejected, or out of reach), or one couldn't answer for
     * them, may be on that one, so they don't count. Where the user chose which of its files plays
     * ([choseFile], Choose file), the file the attempt picked failing ([pickedFile]: null when it
     * failed before picking one) says nothing of the remembered one: it counts only when it is that one.
     */
    fun broke(
        remembered: RememberedRelease,
        failures: List<Pair<PlayCandidate, AttemptFailure>>,
        choseFile: Boolean = false,
        pickedFile: (PlayCandidate) -> String? = { null },
    ): Boolean =
        failures.any { (candidate, failure) ->
            candidate.id == key(remembered) && failure.releaseAtFault &&
                (!choseFile || pickedFile(candidate).let { it == null || it == remembered.fileName })
        }

    private val AttemptFailure.releaseAtFault: Boolean
        get() = when (this) {
            is AttemptFailure.MissingArticles -> !mayBeWhole
            AttemptFailure.Passworded, is AttemptFailure.NoPlayableAudio, is AttemptFailure.NoPlayableVideo, is AttemptFailure.Unsupported,
            AttemptFailure.NoVideo, is AttemptFailure.NoMatchingEpisode, is AttemptFailure.NoRememberedFile -> true
            is AttemptFailure.Failed -> error is UsenetError.Corrupt
            // A file the player can't read is the release's; a reader that crashed may be the app's own fault (L319).
            is AttemptFailure.Unreadable -> kind != UnreadableFile.ReaderCrashed
            // A copy of a disc is the release's shape (L313).
            is AttemptFailure.DiscCopy -> true
            // Elsewhere: a TV's failure a phone mirrors (L37), which never reaches Replay there. NotForCast and CastMiss: a file
            // a Cast device can't play, or can't fully (L57, L129), which this phone plays.
            is AttemptFailure.FetchFailed, is AttemptFailure.Provider, is AttemptFailure.Elsewhere, is AttemptFailure.NotForCast,
            is AttemptFailure.CastMiss -> false
            // A debrid service taking the release down is the release's (L204); the rest are the account's or the moment's.
            AttemptFailure.RemovedByService -> true
            AttemptFailure.NoLongerCached, is AttemptFailure.ServiceLimit, is AttemptFailure.ServiceRefused, is AttemptFailure.KeyRefused,
            is AttemptFailure.ServiceDown, AttemptFailure.NoWorkingLink -> false
        }

    /**
     * The watch key [remembered] is remembered under for [watch]: the TMDB episode's or film's, else
     * the release's own; resuming, the key the progress is saved under ([PlayRequest.resumeKey]).
     */
    fun watchKey(watch: WatchTarget, remembered: RememberedRelease, resumeKey: String? = null): String? =
        resumeKey ?: watch.rememberedKey ?: remembered.nzbId?.let { watch.key(it, remembered.fileIndex) }

    /**
     * [release] as a candidate no search returned: what is known of it is its name and how to
     * download it again (its link with the API key left out, else `t=get` by guid); a torrent's,
     * its info hash, from its content id (L181). It played before, so it counts as playable; the
     * pipeline checks it as any other, a torrent on the accounts on ([onAccounts]).
     */
    fun candidate(release: RememberedRelease): RankedItem {
        val torrent = release.isTorrent
        return RankedItem(
            NewznabItem(
                indexerId = release.indexerId, guid = release.guid, title = release.title, sizeBytes = 0, publishedAt = null, postedAt = null,
                categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
                tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = release.downloadTemplate,
                attributes = emptyMap(), infoHash = release.guid.removePrefix(ReleaseKey.TORRENT).takeIf { torrent && it != release.guid },
            ),
            RankedRelease(
                // Ranked from where it played: a torrent's grabs count among torrents (L176).
                ReleaseFacts(key(release), release.title, 0, source = if (torrent) ReleaseFacts.TORRENT else ReleaseFacts.USENET),
                ReleaseParser.parse(release.title), playable = true, score = 0.0, reasons = emptyList(), estimatedBitrate = null,
            ),
        )
    }

    /**
     * [item] as a play tries it with the service [accounts] on (their ids, in the user's order): a torrent no check answered for (one
     * remembered, [candidate]) plays from every one of them, as a check would have found it there (L181); any other as it is.
     */
    fun onAccounts(item: RankedItem, accounts: List<String>): RankedItem =
        if (item.item.infoHash != null && item.item.availability == null) item.copy(item = item.item.copy(availability = Availability.Cached(accounts))) else item

    /**
     * The release [left] while it still played, as the play goes back to it if no other plays (W3): a candidate no search
     * returned, a torrent's from the service [accountsOn] as a remembered one plays ([onAccounts]), so it has its routes
     * (DEP-3: without them it was never tried).
     */
    fun wayBack(left: Left?, accountsOn: List<String>): RankedItem? = left?.let { onAccounts(candidate(it.release.toRemembered()), accountsOn) }

    /**
     * Why a play leaves [item] out, whatever list or memory it came from and whatever device it is for (a Cast device's
     * debrid play is relayed by the phone, W73), as a search ranked for it now would hide it: a torrent from a torrent
     * indexer whose "Use with your debrid service" is off ([offService], their ids, L194), whose hash goes to no service.
     * Null for one it may try: an NZB, whatever it is for. A remembered torrent skips the search's ranking, so its play asks
     * this (DDC-2, DDR-3).
     */
    fun leftOut(item: RankedItem, offService: Set<String>): ReleaseReason? = when {
        item.item.infoHash == null -> null
        item.item.indexerId in offService -> ReleaseReason.NotForService
        else -> null
    }

    /**
     * What a play tries, in order, and what it leaves out ([leftOut]), as the coordinator's play asks it: [candidates] with
     * [remembered] first ([order], [stored], [first], [hidden]), a torrent on the service accounts on ([accountsOn],
     * [onAccounts]). A remembered torrent skips the search's ranking, so it meets the play's verdicts here: one from an
     * indexer kept from the service ([offService]) is never tried, its hash sent nowhere (DDC-2).
     */
    fun toTry(
        candidates: List<RankedItem>, remembered: RememberedRelease?, stored: Boolean, first: Boolean, hidden: Set<String>,
        accountsOn: List<String>, offService: Set<String>,
    ): Pair<List<RankedItem>, List<RankedItem>> =
        order(candidates, remembered, stored, first, hidden).map { onAccounts(it, accountsOn) }.partition { leftOut(it, offService) == null }
}

/** A release played without TMDB, by what its [name] says: its [title], and its season and episode ("S01E02") or else its year. */
internal class ReleaseName(name: String) {
    private val parsed = ReleaseParser.parse(name)
    val title: String = parsed.title.ifBlank { name }

    /** In ASCII digits whatever the locale: it is searched for too. */
    val tag: String? = parsed.seasons.singleOrNull()
        ?.let { s -> "S%02d".format(Locale.ROOT, s) + parsed.episodes.singleOrNull()?.let { "E%02d".format(Locale.ROOT, it) }.orEmpty() }
        ?: parsed.year?.toString()

    /** "Northbound 2019", "Harbor Lights S01E02". */
    val query: String get() = listOfNotNull(title, tag).joinToString(" ")
}

/**
 * [grab]s each candidate's NZB, except [remembered]'s while the store still has it ([stored], with the password stored with
 * it, [storedPassword]): that one costs the indexer nothing.
 */
internal class ReplaySource(
    private val remembered: RememberedRelease?,
    private val stored: ByteArray?,
    private val grab: NzbSource,
    private val storedPassword: String? = null,
) : NzbSource {
    override suspend fun fetch(candidate: PlayCandidate): ByteArray = fetchWithPassword(candidate).bytes

    /** The stored NZB comes with the password stored with it ([storedPassword]): one from its indexer is there, not in the NZB. */
    override suspend fun fetchWithPassword(candidate: PlayCandidate): FetchedNzb =
        stored?.takeIf { remembered != null && candidate.id == Replay.key(remembered) }?.let { FetchedNzb(it, storedPassword) }
            ?: grab.fetchWithPassword(candidate)
}

/**
 * The file that played before: the video named [name] (of [size] bytes) in the release, else what
 * [wanted] picks from it (null: nothing). With [nzbId], only in that NZB: any other is [fallback]'s.
 *
 * Its position, [index], counts only when no name was saved (a release remembered before names
 * were): positions shift, as prepare leaves out a file that lacks its first article while another
 * stands in (library issue N15), and nzb-streamer can list a release's files otherwise from one
 * version to the next. The file now there may be another episode, which would play and take over
 * this one's progress (#179), so one whose name says it isn't [wanted]'s episode doesn't count, nor
 * a position past the end of the files or holding no video. [fallback]'s pick doesn't stand in, as
 * it did: it would be any file of the release, such as another episode of a season pack.
 *
 * [takesWhole] is [fallback]'s, except that the release named [release] (the remembered one) is
 * never checked whole: what plays from it is the one file, whose volumes are checked once it is
 * picked. [fallback] can't tell: a Search episode group's checks any release whole, which for a
 * season pack would reject it for a gap in an episode that won't play, though it played the
 * remembered one before. Finding nothing in the remembered release is [wanted]'s episode not there
 * ([noMatchIn]), else no video there, else [AttemptFailure.NoRememberedFile]; in any other, as
 * [fallback] says ([noMatch]). Where no file there is named for [wanted]'s episode, which of its
 * videos it is may be asked ([choices]). The file [name]d is [wants]ed besides what the others want,
 * so a release that lists it but left it out is incomplete. A release remembered with no file
 * (an [index] below 0 and no [name]: [PlayingPack]'s question) is [wanted]'s pick of it alone.
 */
internal data class RememberedFile(
    val index: Int,
    val fallback: FilePicker,
    val nzbId: String? = null,
    val release: String? = null,
    val name: String? = null,
    val size: Long? = null,
    val wanted: FilePicker? = fallback,
) : FilePicker {
    override fun <F : ListedFile> pick(listing: FileListing<F>): F? {
        if (nzbId != null && listing.id != nzbId) return fallback.pick(listing)
        listing.find(index, name, size)?.takeIf { it.isVideo && (name != null || !otherEpisode(it, listing)) }?.let { return it }
        return wanted?.pick(listing)
    }

    /**
     * Whether [file]'s name says it is an episode, not [wanted]'s, in [listing] with other videos: a
     * release of one can't have moved another episode to the file's position.
     */
    private fun otherEpisode(file: ListedFile, listing: FileListing<*>): Boolean =
        wanted is FilePicker.Episode && !wanted.wants(file.name) &&
            ReleaseParser.parse(file.name).let { it.seasons.isNotEmpty() || it.episodes.isNotEmpty() } && !listing.oneVideo

    override val noMatch: AttemptFailure get() = fallback.noMatch

    override fun <F : ListedFile> choices(listing: FileListing<F>): List<F> =
        if (nzbId != null && listing.id != nzbId) fallback.choices(listing) else wanted?.choices(listing).orEmpty()

    override fun noMatchIn(listing: FileListing<*>): AttemptFailure = when {
        nzbId != null && listing.id != nzbId -> fallback.noMatchIn(listing)
        wanted is FilePicker.Episode -> wanted.noMatch
        listing.files.none { it.isVideo } -> AttemptFailure.NoVideo
        else -> AttemptFailure.NoRememberedFile(name)
    }

    override fun takesWhole(title: String): Boolean = title != release && fallback.takesWhole(title)
    override fun wants(name: String): Boolean = name == this.name || wanted?.wants(name) == true || fallback.wants(name)
}

/**
 * The video of a release of one (besides samples), as [FilePicker.Largest] picks it; nothing from a
 * release of several, where it would be any of them. What plays in place of a remembered file gone
 * from a release played without TMDB and named for no episode ([Replay.file]), which nothing tells
 * the episode of: a film's, else nothing, which [RememberedFile.noMatchIn] tells from no video.
 */
internal data object SoleVideo : FilePicker {
    override fun <F : ListedFile> pick(listing: FileListing<F>): F? = listing.defaultPick?.takeIf { listing.oneVideo }
    override val noMatch: AttemptFailure = AttemptFailure.NoVideo

    /** What it plays is a release of one video. */
    override fun takesWhole(title: String) = true
}

/**
 * Resuming something in progress from Home: [request] plays the release that played it, if one is
 * remembered (the player then starts from the saved position). If that fails, an [episode] is
 * searched for as Play on the Show screen does; a film or release shows the failure, with a search
 * for [search] (a film's by its TMDB id).
 */
internal data class ResumePlan(
    val request: PlayRequest?,
    val episode: EpisodeTarget?,
    val title: String,
    val subtitle: String?,
    val search: String,
    /** The TMDB title [search] is for; null for a release. */
    val searchPick: SearchPick? = null,
) {
    /**
     * Runs the plan with [play] (null once the player starts, else the failure) and [searchEpisode]
     * (the episode, and what leaves out the release that just failed). Returns the failure to show.
     * A release the play would leave out ([passOver]: a torrent from an indexer kept from the
     * service, [Replay.leftOut]) isn't tried for an episode, which is searched for at once, as if none were remembered; a film's
     * or a release's goes to [play], which says why it can't play there.
     */
    suspend fun run(play: suspend (PlayRequest) -> PlayState.Failed?, searchEpisode: suspend (EpisodeTarget, Retry?) -> Unit, passOver: (RankedItem) -> Boolean = { false }): PlayState.Failed? {
        val tried = request?.takeUnless { episode != null && it.candidates.all(passOver) }
        val failed = tried?.let { play(it) ?: return null }
        episode?.let {
            val retry = tried?.candidates?.firstOrNull()?.key?.let { key -> Retry.afterRemembered(key, failed) }
            searchEpisode(it, retry)
            return null
        }
        return (failed ?: PlayState.Failed(title, subtitle, listOf(title to NOT_REMEMBERED), notTried = 0, remote = listOf(NOT_REMEMBERED))).copy(search = search, searchPick = searchPick)
    }

    companion object {
        /** The sheet's words for a Resume with nothing to play: the app's own, so a phone is told them as they are. */
        private const val NOT_REMEMBERED = "No release is remembered for it"

        fun of(state: WatchState): ResumePlan {
            val watch = WatchTarget(state.kind, state.title, state.showTmdbId, state.season, state.episode, state.movieTmdbId)
            val episode = watch.episodeTarget
            val (title, subtitle, search) = when {
                episode != null -> Triple(episode.showName, episode.label, episode.query)
                state.kind == WatchKind.Movie -> Triple(state.title, null, state.title)
                else -> ReleaseName(state.title).let { Triple(it.title, null, it.query) }
            }
            // By its TMDB id, so what plays from the search is the film or episode: from its saved position, and saving to it.
            val pick = episode?.pick ?: state.movieTmdbId?.takeIf { state.kind == WatchKind.Movie }?.let { SearchPick(TitleType.Movie, it, state.title) }
            val picker = if (episode != null) FilePicker.Episode(episode.season, episode.episode) else FilePicker.Largest
            val request = state.release?.let { release ->
                PlayRequest(title, subtitle, listOf(Replay.candidate(release)), Replay.file(release, picker, watch), watch, remembered = release, resumeKey = state.key)
            }
            return ResumePlan(request, episode, title, subtitle, search, pick)
        }
    }
}
