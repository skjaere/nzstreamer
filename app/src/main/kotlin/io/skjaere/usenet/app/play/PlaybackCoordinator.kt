@file:UseSerializers(ProbedTracksSerializer::class)

package io.skjaere.usenet.app.play

import androidx.annotation.VisibleForTesting
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.TmdbLookup
import io.skjaere.usenet.app.casting.CastAction
import io.skjaere.usenet.app.casting.CastFailKind
import io.skjaere.usenet.app.casting.CastFailed
import io.skjaere.usenet.app.casting.CastLine
import io.skjaere.usenet.app.casting.CastLoad
import io.skjaere.usenet.app.casting.CastLoadFailure
import io.skjaere.usenet.app.casting.CastMove
import io.skjaere.usenet.app.casting.CastPrecheck
import io.skjaere.usenet.app.casting.CastProfile
import io.skjaere.usenet.app.casting.CastRanking
import io.skjaere.usenet.app.casting.CastStart
import io.skjaere.usenet.app.casting.CastVerdict
import io.skjaere.usenet.app.casting.MoveOutcome
import io.skjaere.usenet.app.casting.noPictureOn
import io.skjaere.usenet.app.casting.noSoundOn
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.collection.shortDate
import io.skjaere.usenet.app.link.SheetMirror
import io.skjaere.usenet.app.debrid.DebridAdds
import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.app.debrid.ServiceFiles
import io.skjaere.usenet.app.debrid.ServicePlayFailure
import io.skjaere.usenet.app.debrid.ServiceSource
import io.skjaere.usenet.app.debrid.triedBySource
import io.skjaere.usenet.app.search.IndexerConfigs
import io.skjaere.usenet.app.search.SourceTag
import io.skjaere.usenet.app.search.playTag
import io.skjaere.usenet.app.saveOrLog
import io.skjaere.usenet.app.settings.NoUsableProvider
import io.skjaere.usenet.app.search.rankedAgain
import io.skjaere.usenet.app.search.summaryChips
import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.DiscKind
import io.skjaere.usenet.core.EnginePrepareBackend
import io.skjaere.usenet.core.FileCandidate
import io.skjaere.usenet.core.FileListing
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.ListedFile
import io.skjaere.usenet.core.MissingOn
import io.skjaere.usenet.core.NzbSource
import io.skjaere.usenet.core.PipelineEvent
import io.skjaere.usenet.core.PipelineStep
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.core.PlayPipeline
import io.skjaere.usenet.core.PlaybackTarget
import io.skjaere.usenet.core.PrepareOutcome
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.ProbeResult
import io.skjaere.usenet.core.ProbedTracks
import io.skjaere.usenet.core.ProbedVideo
import io.skjaere.usenet.core.ProviderConfig
import io.skjaere.usenet.core.StepStatus
import io.skjaere.usenet.core.UnreadableFile
import io.skjaere.usenet.core.UsenetSource
import io.skjaere.usenet.core.UsenetTarget
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.core.unreadableFailure
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.SearchPick
import io.skjaere.usenet.data.TitleType
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.metadata.TmdbException
import io.skjaere.usenet.metadata.hasAired
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.NzbDownload
import io.skjaere.usenet.player.LinkFailure
import io.skjaere.usenet.releases.Availability
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseKey
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.releases.ReleaseReason
import io.skjaere.usenet.search.RankedItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import org.slf4j.LoggerFactory
import java.util.Locale

/** What progress is recorded against: an episode or movie from TMDB, else the release itself. */
@Serializable
data class WatchTarget(
    val kind: WatchKind,
    val title: String,
    val showTmdbId: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val movieTmdbId: Int? = null,
) {
    /**
     * The key of the TMDB episode or movie, under which the release that played it is remembered;
     * null for a release played without TMDB, whose key is the release itself.
     */
    val rememberedKey: String?
        get() = when {
            kind == WatchKind.Episode && showTmdbId != null && season != null && episode != null -> WatchKey.episode(showTmdbId, season, episode)
            kind == WatchKind.Movie && movieTmdbId != null -> WatchKey.movie(movieTmdbId)
            else -> null
        }

    fun key(nzbId: String, fileIndex: Int): String = rememberedKey ?: WatchKey.release(nzbId, fileIndex)

    /**
     * A TMDB episode, to look up and search for again; null for anything else. The show's name is
     * the one saved with it ("Harbor Lights S02E05"); TMDB is asked by the show's id.
     */
    val episodeTarget: EpisodeTarget?
        get() = if (kind == WatchKind.Episode && showTmdbId != null && season != null && episode != null) {
            EpisodeTarget(showTmdbId, title.removeSuffix(" " + EpisodeTarget.label(season, episode)), season, episode)
        } else {
            null
        }
}

/**
 * Everything the player needs: the stream, how to label it, and where to save progress. Kept in
 * the player's intent, which outlives the process, so fields added later have defaults.
 */
@Serializable
data class PlayerLaunch(
    val uri: String,
    val title: String,
    val subtitle: String?,
    val watchKey: String,
    val watch: WatchTarget,
    val release: SavedRelease,
    /** The coordinator's id of the request behind it, for "Try another release" ([PlaybackCoordinator.tryAnother]). */
    val requestId: String? = null,
    /** Where to start: where the release before it failed. Wins over the position saved for [watchKey]. */
    val startPositionMs: Long? = null,
    /**
     * The watch keys of the releases before it, which failed mid-play, where they differ from
     * [watchKey] (a release played without TMDB is its own key): this one took over their
     * progress, which leaves Up next as this one's is saved.
     */
    val replaces: List<String> = emptyList(),
    /** What the track probe read of the video, for the TV's frame-rate match; null when it read nothing, or the launch is older. */
    val video: VideoTiming? = null,
    /** The paired phone's session this play is in (L33), when one asked for it or for the play it follows; null for a play here. */
    val remote: RemotePlay? = null,
    /** The Google Cast device this play goes to (L81), so what it hands on goes there too; null for a play on this device. */
    val cast: CastTo? = null,
    /** What the track probe read of the file's tracks (L129), for Play & connect's verdict; null when it read none, or the launch is older. */
    val tracks: ProbedTracks? = null,
    /** A file Import opened (L147): it saves no progress, here or cast, as Import's play here never has. False for a launch from before. */
    val imported: Boolean = false,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        fun decode(text: String): PlayerLaunch = json.decodeFromString(serializer(), text)
    }
}

/**
 * What the track probe read of the video that plays ([ProbedVideo]), as the launch keeps it: the frame rate ([fps]) and
 * length ([durationMs]), null when unknown, and whether it is Dolby Vision. The TV's frame-rate match plans from it before
 * the player exists.
 */
@Serializable
data class VideoTiming(val fps: Double? = null, val durationMs: Long? = null, val dolbyVision: Boolean = false) {
    companion object {
        /** [video] as the launch keeps it; a rate that isn't a number is none, as the launch's JSON can't hold one. */
        fun of(video: ProbedVideo) = VideoTiming(video.fps?.takeIf { it.isFinite() }, video.durationMs, video.dolbyVision)
    }
}

/**
 * The launch of [release] (the file picked in its NZB) for [request], kept under [requestId]: the player opens [uri], the
 * one the play's target gave ([io.skjaere.usenet.core.PlaybackTarget.uri]). A [retry] starts where the failed release
 * stopped and takes over its progress. A release played without TMDB is named after itself, not after the request's
 * first release; resumed, it keeps the key its progress is saved under ([PlayRequest.resumeKey]). [video] and [tracks]:
 * what the track probe read of it. A phone's play ([origin], L33) keeps its session, and starts where the phone's place
 * says (L39) unless the retry has a place of its own; a cast keeps its device (L81).
 */
internal fun launchOf(
    request: PlayRequest, release: SavedRelease, uri: String, requestId: String, retry: Retry?, video: ProbedVideo? = null, tracks: ProbedTracks? = null,
    origin: PlayOrigin = PlayOrigin.Here,
): PlayerLaunch {
    val watchKey = request.resumeKey?.takeIf { request.remembered?.let(Replay::key) == release.key }
        ?: request.watch.key(release.nzbId, release.fileIndex)
    return PlayerLaunch(
        uri = uri,
        title = request.title,
        subtitle = request.subtitle,
        watchKey = watchKey,
        watch = if (request.watch.kind == WatchKind.Release) request.watch.copy(title = release.title) else request.watch,
        release = release,
        requestId = requestId,
        startPositionMs = retry?.startPositionMs ?: origin.start,
        replaces = retry?.failedKeys.orEmpty().filter { it != watchKey },
        video = video?.let(VideoTiming::of),
        remote = origin.remote,
        cast = origin.cast,
        tracks = tracks,
        imported = request.imported,
    )
}

/** [launchOf] inside a play's job: with the origin the job was started with ([PlayOrigin]), this device's without one. */
internal suspend fun launchInJob(
    request: PlayRequest, release: SavedRelease, uri: String, requestId: String, retry: Retry?, video: ProbedVideo? = null, tracks: ProbedTracks? = null,
): PlayerLaunch = launchOf(request, release, uri, requestId, retry, video, tracks, currentCoroutineContext()[PlayOrigin] ?: PlayOrigin.Here)

/**
 * The release [item] as it played, [target]'s file in its NZB or its listing: what the player and Resume keep of it. Only
 * what a search's item and Replay's rebuilt one both have, its names and how to download it again, so the player shows a
 * Resume as it shows the play from search (File details). A torrent's, from where it played: its guid and its listing's id
 * are its content id, `btih:<hex>`, and it has no link to download it by (L181).
 */
internal fun savedRelease(item: NewznabItem, target: PlaybackTarget) = SavedRelease(
    item.indexerId, item.guid, item.title, item.downloadTemplate, target.file.index, target.listing.id, target.file.name, target.file.size,
    source = target.candidate.source.takeIf { it != PlayCandidate.USENET },
)

/**
 * The release that played and its file: [fileName], of [fileSize] bytes, at [fileIndex] (a launch from before names were has
 * only that). [source]: where it played from, as its candidate names it ([io.skjaere.usenet.core.PlayCandidate.source]);
 * null for Usenet, as a launch from before sources were reads; a torrent's, its [guid] and [nzbId] its content id,
 * `btih:<hex>`, with no [downloadTemplate] (L181).
 */
@Serializable
data class SavedRelease(
    val indexerId: String,
    val guid: String,
    val title: String,
    val downloadTemplate: String?,
    val fileIndex: Int,
    val nzbId: String,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val source: String? = null,
) {
    fun toRemembered() = RememberedRelease(indexerId, guid, title, downloadTemplate, fileIndex, nzbId, fileName, fileSize, source)

    /** As a search result's ([RankedItem.key]) and [Replay.key]: the release, whichever of its files played. */
    val key: String get() = ReleaseKey.of(indexerId, guid)
}

/** A request to play: ranked releases (best first) and how to pick the file inside. */
data class PlayRequest(
    val title: String,
    val subtitle: String?,
    val candidates: List<RankedItem>,
    val picker: FilePicker = FilePicker.Largest,
    val watch: WatchTarget,
    /** A line shown above the steps, e.g. "Searching 3 indexers by TVDB id · 6 found". */
    val searchLine: String? = null,
    /** The release that played [watch] before, when the caller has it (resuming from Home); else it is looked up by [watch]'s key. */
    val remembered: RememberedRelease? = null,
    /**
     * Whether the remembered release goes first ([Replay.order]): false for a release chosen by
     * hand, which is tried alone, and once the remembered release has just failed. Its stored NZB
     * is used either way.
     */
    val rememberedFirst: Boolean = true,
    /**
     * Resuming from Home: the watch key the progress is saved under, which [remembered] keeps as it
     * plays again. A release played without TMDB is keyed by its NZB and file, and its NZB grabbed
     * again may not be byte for byte the one that played, nor list the file at the same index.
     */
    val resumeKey: String? = null,
    /**
     * The user chose the release (Choose release, a release list's Play): it is tried even when it was
     * found missing on every provider in use before ([MissingReleases]), which its row says.
     */
    val chosen: Boolean = false,
    /** The episode's name (TMDB's), which the question of which file is the episode names ([lookingFor]); null when not known. */
    val episodeName: String? = null,
    /**
     * Cast anyway (L149): a cast's device is sent the release whatever the verdict on its tracks says ([CastProbe]), its
     * type still checked; false for any other play.
     */
    val castAnyway: Boolean = false,
    /**
     * A release picked by hand from a list: the list's other playable releases in its order, as ranked for whom it showed
     * them (the Cast device chosen), which the pre-check's Play a compatible release plays (L152); none for any other play.
     */
    val alternatives: List<RankedItem> = emptyList(),
    /** A file Import opened ([importRequest]): its launch says so ([PlayerLaunch.imported]), and saves no progress. */
    val imported: Boolean = false,
    /**
     * The releases the request's search found but hid (their keys): a remembered torrent among them isn't added back ahead
     * of [candidates] ([Replay.order]), the search's check having just said it doesn't play (W71).
     */
    val hidden: Set<String> = emptySet(),
)

/** The indexer a file Import opened is kept under (L147): none of the user's, so no indexer is ever asked for it. */
const val IMPORTED = "import"

/** Why a file Import opened can't be cast: the NZB went from the store since, as older ones do. */
internal const val IMPORT_GONE = "No longer kept on this phone: open the file again"

/**
 * The play of a file Import opened, prepared and stored, named [title] as its NZB is (L147): played without TMDB, nothing
 * searched for it, its launch saving no progress.
 */
internal fun importRequest(title: String) = PlayRequest(title, null, emptyList(), watch = WatchTarget(WatchKind.Release, title), imported = true)

/** [file] of the NZB [nzbId] that Import opened, named [title], as its launch keeps it: the NZB's own release, under [IMPORTED] by its id. */
internal fun importedRelease(nzbId: String, file: FileCandidate, title: String) =
    SavedRelease(IMPORTED, nzbId, title, null, file.index, nzbId, file.name, file.size)

/**
 * The launch of [file] of the NZB [nzbId] that Import opened, played here for its [request] ([importRequest]), kept under
 * [requestId]: Import's release ([importedRelease]) at the URI of the file in the NZB Import keeps, nothing searched,
 * grabbed or probed.
 */
internal fun importedLaunch(nzbId: String, file: FileCandidate, request: PlayRequest, requestId: String) =
    launchOf(request, importedRelease(nzbId, file, request.title), UsenetUris.of(nzbId, file), requestId, retry = null)

/** An episode known by its TMDB numbers, looked up on TMDB and then found and played as the Show screen's Play does. */
data class EpisodeTarget(val showTmdbId: Int, val showName: String, val season: Int, val episode: Int) {
    val label: String get() = label(season, episode)

    /** What a search for it by name types: in ASCII digits whatever the locale, as [EpisodePlay.nameQuery]. */
    val query: String get() = "$showName ${"S%02dE%02d".format(Locale.ROOT, season, episode)}"

    /** The show as a pick from TMDB's suggestions, so a search for [query] is by its id. */
    val pick: SearchPick get() = SearchPick(TitleType.Tv, showTmdbId, showName, season, episode)

    companion object {
        /** As [WatchTitle.episode] writes it after the show's name. */
        fun label(season: Int, episode: Int) = "S%02dE%02d".format(season, episode)
    }
}

/** An episode of a show in the collection (or picked from TMDB), to find a release for and play. */
data class EpisodePlay(
    val showTmdbId: Int,
    val showName: String,
    val showYear: Int?,
    val aliases: List<String>,
    val ids: io.skjaere.usenet.newznab.TvIds,
    val season: Int,
    val episode: Int,
    val episodeName: String?,
    val airDate: java.time.LocalDate?,
    val runtimeMinutes: Int?,
    /** TMDB's still for the episode, shown on the player's next-episode card. */
    val stillPath: String? = null,
    /**
     * Whether TMDB listed it as aired ([io.skjaere.usenet.metadata.hasAired]) when it was picked to
     * play. One it didn't is searched for and played all the same, since releases often appear
     * before TMDB's date; only the no-release sheet's note ([noReleaseNote]) tells them apart.
     */
    val aired: Boolean = true,
    /** The show's original language (ISO 639-1, from TMDB): its releases without a language tag speak it. */
    val originalLanguage: String? = null,
    /** How many episodes TMDB lists for its season, which a season pack is taken to hold; null when not known. */
    val seasonEpisodes: Int? = null,
) {
    val label: String get() = "S%02dE%02d".format(season, episode)
    val subtitle: String get() = listOfNotNull(label, episodeName).joinToString(" · ")
    /** Sent to the indexers, so in ASCII digits whatever the device locale; [label] is display text. */
    val nameQuery: String get() = "$showName ${"s%02de%02d".format(Locale.ROOT, season, episode)}"
    val key: String get() = io.skjaere.usenet.data.WatchKey.episode(showTmdbId, season, episode)

    /** What its progress is recorded against: the TMDB episode. */
    val watch: WatchTarget
        get() = WatchTarget(WatchKind.Episode, WatchTitle.episode(showName, season, episode), showTmdbId = showTmdbId, season = season, episode = episode)

    /** What the finder is asked for it, by Play and by Choose release alike. */
    val request: io.skjaere.usenet.search.EpisodeRequest
        get() = io.skjaere.usenet.search.EpisodeRequest(
            io.skjaere.usenet.search.ShowRef(showName, showYear, aliases, ids, originalLanguage), season, episode, airDate, runtimeMinutes, seasonEpisodes,
        )

    companion object {
        fun of(show: io.skjaere.usenet.metadata.ShowDetails, e: io.skjaere.usenet.metadata.Episode, today: java.time.LocalDate = java.time.LocalDate.now()) = EpisodePlay(
            showTmdbId = show.tmdbId, showName = show.name, showYear = show.year,
            aliases = listOfNotNull(show.originalName?.takeIf { it != show.name }),
            ids = io.skjaere.usenet.newznab.TvIds(tvdb = show.ids.tvdb, tmdb = show.tmdbId, imdb = show.ids.imdb),
            season = e.season, episode = e.episode, episodeName = e.name, airDate = e.airDate,
            runtimeMinutes = e.runtimeMinutes ?: show.episodeRuntimeMinutes, stillPath = e.stillPath, aired = show.hasAired(e, today),
            originalLanguage = show.originalLanguage,
            seasonEpisodes = show.seasons.firstOrNull { it.number == e.season }?.episodeCount?.takeIf { it > 0 },
        )
    }
}

/**
 * The episode after [season]x[episode] that has aired: the next one in the season, else the
 * first of the next regular season (specials only lead on to the next special). Null at the end
 * of what has aired (or if TMDB fails).
 */
suspend fun nextAiredEpisode(tmdb: io.skjaere.usenet.metadata.TmdbClient, showTmdbId: Int, season: Int, episode: Int, today: java.time.LocalDate = java.time.LocalDate.now()): EpisodePlay? =
    nextAiredEpisode(showTmdbId, season, episode, today, loadShow = { tmdb.show(it) }, loadSeason = { id, number -> tmdb.season(id, number) })

internal suspend fun nextAiredEpisode(
    showTmdbId: Int,
    season: Int,
    episode: Int,
    today: java.time.LocalDate,
    loadShow: suspend (Int) -> io.skjaere.usenet.metadata.ShowDetails,
    loadSeason: suspend (Int, Int) -> io.skjaere.usenet.metadata.Season,
): EpisodePlay? = try {
    val show = loadShow(showTmdbId)
    val next = loadSeason(showTmdbId, season).episodes.firstOrNull { it.episode == episode + 1 }
        ?: show.seasons.filter { !it.isSpecials && it.number > season && it.episodeCount > 0 }.minByOrNull { it.number }
            ?.takeIf { season > 0 }
            ?.let { s -> loadSeason(showTmdbId, s.number).episodes.firstOrNull { it.episode == 1 } }
    next?.takeIf { show.hasAired(it, today) }?.let { EpisodePlay.of(show, it, today) }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/**
 * The episode before [season]x[episode] that has aired, for the player's Previous: the one before
 * it in the season, else the last of the regular season before (specials lead back only to the
 * special before). Null for a show's first episode (or if TMDB fails).
 */
suspend fun previousAiredEpisode(tmdb: io.skjaere.usenet.metadata.TmdbClient, showTmdbId: Int, season: Int, episode: Int, today: java.time.LocalDate = java.time.LocalDate.now()): EpisodePlay? =
    previousAiredEpisode(showTmdbId, season, episode, today, loadShow = { tmdb.show(it) }, loadSeason = { id, number -> tmdb.season(id, number) })

internal suspend fun previousAiredEpisode(
    showTmdbId: Int,
    season: Int,
    episode: Int,
    today: java.time.LocalDate,
    loadShow: suspend (Int) -> io.skjaere.usenet.metadata.ShowDetails,
    loadSeason: suspend (Int, Int) -> io.skjaere.usenet.metadata.Season,
): EpisodePlay? = try {
    val show = loadShow(showTmdbId)
    val previous = loadSeason(showTmdbId, season).episodes.firstOrNull { it.episode == episode - 1 }
        ?: show.seasons.filter { !it.isSpecials && it.number < season && it.episodeCount > 0 }.maxByOrNull { it.number }
            ?.takeIf { season > 0 }
            ?.let { s -> loadSeason(showTmdbId, s.number).episodes.maxByOrNull { it.episode } }
    previous?.takeIf { show.hasAired(it, today) }?.let { EpisodePlay.of(show, it, today) }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/**
 * Runs [block], one of the play sheet's jobs, and returns the failure to show for [title] if it
 * throws (reading the settings or the library failing, say), else null: the sheet says so rather
 * than staying on "Searching…", and the app doesn't crash.
 */
internal suspend fun failureOf(title: String, subtitle: String?, block: suspend () -> Unit): PlayState.Failed? = try {
    block()
    null
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    log.warn("Playing {} failed", title, e)
    PlayState.Failed(title, subtitle, listOf(title to "Something went wrong" + (e.message?.let { ": $it" } ?: "")), notTried = 0, remote = listOf("Something went wrong on the TV"))
}

private val log = LoggerFactory.getLogger(PlaybackCoordinator::class.java)

/** What a play's work is called, as the engine's lock lines name who holds it and who waits for it (#273). */
private val PLAY = CoroutineName("play")

/** The sheet's words when a retry has no release left: the app's own, so a phone is told them as they are. */
private const val NO_OTHER_RELEASE = "No other release to try"

/**
 * Runs [block], and once it has taken [graceMs] calls [say] (the sheet saying what the play waits on):
 * not for a block done sooner, the usual case, which the sheet would only flash, nor after it.
 */
internal suspend fun <T> sayingIfSlow(graceMs: Long, say: () -> Unit, block: suspend () -> T): T = coroutineScope {
    val saying = launch {
        delay(graceMs)
        say()
    }
    try {
        block()
    } finally {
        saying.cancel()
    }
}

/** How long a play waits on the engine's check of its providers before the sheet says so ([sayingIfSlow]). */
private const val PROVIDER_CHECK_GRACE_MS = 500L

/**
 * What the sheet says while a play waits on the engine's check of its providers, after what it says of the release
 * before a [retry]: the one that stopped playing ([Retry.notice]), or, as another is looked for, the one that played it
 * ([Retry.searchingNotice], as the search before said it); nothing of one that only didn't hold the episode.
 */
internal fun checkingProvidersLine(retry: Retry?): String =
    listOfNotNull(retry?.takeUnless { it.playingLacksIt }?.let { it.notice ?: it.searchingNotice }, "Checking your providers…").joinToString(" · ")

/** [target] couldn't be looked up on TMDB, for [reason] ([remote] as a phone is told it): the sheet offers a search for it instead. */
internal fun lookUpFailed(target: EpisodeTarget, reason: String, remote: String = reason) =
    PlayState.Failed(target.showName, target.label, listOf(target.label to reason), notTried = 0, search = target.query, searchPick = target.pick, remote = listOf(remote))

/**
 * [title] can't be played because no provider is usable ([missing]): the sheet says so and offers
 * the providers list, with nothing searched, grabbed or tried.
 */
internal fun noProviderFailure(title: String, subtitle: String?, missing: NoUsableProvider) =
    PlayState.Failed(title, subtitle, emptyList(), notTried = 0, noProvider = missing)

/**
 * What the sheet shows once the play's steps say [running], over what it shows ([shown]): the steps, unless the play is [asking] a
 * question and it is up, which file it plays or the pre-check's. The steps are published from the pipeline's collector and
 * the question from its producer, on other threads: one that comes late mustn't hide the question the play waits on.
 */
internal fun runningOver(shown: PlayState, running: PlayState.Running, asking: Boolean): PlayState =
    if (asking && (shown is PlayState.ChoosingFile || shown is PlayState.Prechecking)) shown else running

/**
 * What the card says the search for the next episode found in [r]: its best release but those [leftOut] (the release playing,
 * where it plainly lacks the episode), or nothing.
 */
internal fun prefetchOf(r: io.skjaere.usenet.search.FindResult?, leftOut: Set<String> = emptySet()): Prefetch =
    r?.playable?.firstOrNull { it.key !in leftOut }?.item?.let { Prefetch.Ready(it.title, it.sizeBytes) } ?: Prefetch.NothingFound

/**
 * [this] release as the play pipeline tries it: by its key, name and indexer, and whether its indexer says it needs a
 * password, which says what an archive that opens but can't be read is ([PlayCandidate.passworded]). A torrent plays from
 * the service accounts its cache check found it on, in their order (the user's), each its own route (L164); an NZB from Usenet.
 */
internal val RankedItem.playCandidate: PlayCandidate
    get() = if (item.infoHash == null) {
        PlayCandidate(key, item.title, item.indexerId, passworded = item.passworded)
    } else {
        val accounts = (item.availability as? Availability.Cached)?.accounts.orEmpty()
        PlayCandidate(key, item.title, item.indexerId, source = ReleaseFacts.TORRENT, routes = accounts.map(UsableRoutes::serviceRoute))
    }

/**
 * [f] as the play sheet's notice names the release that failed, before it says which release is
 * tried next: one incomplete only on some providers says so, as its failure line does (#172). What
 * the Cast device of a play [cast] to it can't play there is said in its name (L129, L149), which
 * the failure's own words, written to the log, leave out. A file the player can't read is named by
 * its type, as one a Cast device can't play is (L318); a disc copy by what it is a copy of (L313).
 */
internal fun shortFailure(f: AttemptFailure, cast: CastTo? = null): String = when (f) {
    is AttemptFailure.MissingArticles -> when {
        f.unanswered != null -> "Incomplete on the providers that answered"
        f.providersLeftOut -> "Incomplete on the providers in use"
        else -> "Release incomplete"
    }
    is AttemptFailure.Passworded -> "Release passworded"
    is AttemptFailure.NoPlayableAudio -> "No playable audio"
    is AttemptFailure.NoPlayableVideo -> "No playable video"
    is AttemptFailure.NotForCast -> "${f.kind} can't be cast"
    is AttemptFailure.Unreadable -> when (f.kind) {
        UnreadableFile.UnknownFormat, UnreadableFile.Broken -> f.extension?.let { "$it file can't be read" } ?: "File can't be read"
        UnreadableFile.ReaderCrashed -> f.text
    }
    is AttemptFailure.DiscCopy -> when (f.kind) {
        DiscKind.BluRay -> "Blu-ray disc copy"
        DiscKind.Dvd -> "DVD copy"
        DiscKind.Image -> "Disc image"
    }
    is AttemptFailure.NoCastAudio -> cast?.let { noSoundOn(it.name) } ?: f.text
    is AttemptFailure.NoCastVideo -> cast?.let { noPictureOn(it.name) } ?: f.text
    is AttemptFailure.TooBigForCast -> cast?.let { ReleaseReason.TooBigFor.shows(it.name, f.maxHeight) } ?: f.text
    // Its wait is for the failure sheet: the next release is tried now (DbTvPlaySheet 2).
    is AttemptFailure.ServiceLimit -> SERVICE_LIMIT
    else -> f.text
}

/**
 * What Import's cast of [fileName] fails as, by its probe's [result] ([PlaybackCoordinator]'s castImported, which a move
 * from the player takes too); null when it plays. A file the player can't read is said as a play says it
 * ([unreadableFailure], L330), never in the player's own words.
 */
internal fun castProbeFailure(result: ProbeResult, fileName: String): AttemptFailure? = when (result) {
    is ProbeResult.Playable -> null
    is ProbeResult.NotForCast -> AttemptFailure.NotForCast(result.extension)
    is ProbeResult.CastMiss -> result.failure
    is ProbeResult.NoPlayableAudio -> AttemptFailure.NoPlayableAudio(result.found)
    is ProbeResult.NoPlayableVideo -> AttemptFailure.NoPlayableVideo(result.found)
    is ProbeResult.Failed -> unreadableFailure(result, fileName) ?: AttemptFailure.Failed(result.error)
}

/** A debrid service's limit, as the notice names it (L204): no service, no wait. */
private const val SERVICE_LIMIT = "The debrid service’s limit was reached"

/** The sheet's notice as the play moves on to release [attempt] of [of], after the one before failed with [last] (none: its step said nothing). */
internal fun retryNotice(last: AttemptFailure?, attempt: Int, of: Int, cast: CastTo?): String =
    "${last?.let { shortFailure(it, cast) } ?: "That release failed"} · trying release $attempt of $of"

/**
 * The sheet's notice as the play tries the release that failed with [last] on the next debrid service that has it cached,
 * before any other release (DEP-6, L231; D2PhonePlaySheetRetry 2): the failure as [retryNotice] names it, and no service
 * (choice 3), though a refused key's own words name its account's.
 */
internal fun nextServiceNotice(last: AttemptFailure, cast: CastTo?): String = "${shortFailure(last, cast)} · trying your next debrid service"

/**
 * [steps] as a release's source runs its steps again for another target of it (DEP-6, [PipelineEvent.Retrying]): those
 * after its first ([sourceSteps]; a debrid service's Preparing) go back to waiting, so the sheet, and the step its
 * minimised line names, are at Preparing again.
 */
internal fun retriedSteps(steps: Map<PipelineStep, StepStatus>, sourceSteps: List<PipelineStep>): Map<PipelineStep, StepStatus> =
    steps.filterKeys { it == sourceSteps.firstOrNull() }

/**
 * The no-release sheet's note for [ep], under what was searched, on [today]. An episode TMDB
 * doesn't list as aired yet gets the date TMDB has for it, since releases often appear around
 * then (some before); one that aired in the last few days, that releases may still appear.
 */
internal fun noReleaseNote(ep: EpisodePlay, today: java.time.LocalDate): String = listOfNotNull(
    if (!ep.aired) {
        ep.airDate?.let { "TMDB lists this episode for ${shortDate(it)}. Releases often appear around then." }
            ?: "This episode hasn't aired yet according to TMDB."
    } else {
        ep.airDate?.let { aired ->
            val days = java.time.temporal.ChronoUnit.DAYS.between(aired, today)
            if (days in 0L..3L) "This episode aired ${if (days == 0L) "today" else if (days == 1L) "yesterday" else "$days days ago"}, so releases may still appear." else null
        }
    },
    "Indexers can also number some shows differently from TMDB.",
).joinToString(" ")

/**
 * The sheet for [ep] when the finder found nothing playable ([r]): what each search did, a note, and the Search it offers. A
 * paired phone is told what each search did without the indexers' errors, as how many didn't answer (L37).
 */
internal fun noRelease(ep: EpisodePlay, r: io.skjaere.usenet.search.FindResult, today: java.time.LocalDate = java.time.LocalDate.now()): PlayState.NoRelease {
    val stages = r.stages.map { st ->
        val failures = st.indexers.mapNotNull { it.error?.message }.distinct()
        val failed = st.indexers.count { it.error != null }
        val by = st.indexers.mapNotNull { it.by }.distinct().joinToString(" / ")
        val title = when (st.stage) {
            io.skjaere.usenet.search.SearchStage.Episode -> "Searched ${st.indexers.size} indexer${if (st.indexers.size == 1) "" else "s"}" + (if (by.isNotEmpty()) " by $by" else "")
            io.skjaere.usenet.search.SearchStage.AirDate -> "Searched by air date"
            io.skjaere.usenet.search.SearchStage.SeasonPack -> "Looked for season ${ep.season} packs"
            io.skjaere.usenet.search.SearchStage.ByName -> "Searched by name"
        }
        // What the indexers answered, else their errors: as the TV's sheet says it, and as a phone is told it.
        val outcome = when {
            st.accepted > 0 -> "${st.accepted} found, none playable"
            st.found > 0 -> "${st.found} results, none for ${ep.label}"
            failures.isNotEmpty() -> null
            st.stage == io.skjaere.usenet.search.SearchStage.SeasonPack -> "None containing E%02d".format(ep.episode)
            else -> "Nothing yet"
        }
        Triple(title, outcome ?: ("⚠ " + failures.joinToString()), outcome ?: "⚠ $failed indexer${if (failed == 1) "" else "s"} didn't answer")
    }
    // A torrent indexer whose releases none of the services has cached (L213): a line of its own, naming no service. Not told
    // to a paired phone, which is told no indexer by name (L37).
    val torrentIndexers = r.stages.flatMap { it.indexers }.map { it.indexer }.filter { it.kind == io.skjaere.usenet.newznab.IndexerKind.Torznab }.distinctBy { it.id }
    val found = (r.playable + r.hidden).map { it.item }
    val noneCached = torrentIndexers.mapNotNull { indexer -> io.skjaere.usenet.app.search.noneCached(found.filter { it.indexerId == indexer.id })?.let { indexer.name to it } }
    val note = noReleaseNote(ep, today)
    // Searched by its words, not by the show (its id searches just failed), but ranked as the show's releases are.
    return PlayState.NoRelease(
        ep.showName, ep.subtitle, stages.map { it.first to it.second } + noneCached, note, ep.nameQuery, r.hidden.map { io.skjaere.usenet.search.RankedItem(it.item, it.ranked) },
        searchLanguage = ep.originalLanguage, remoteStages = stages.map { it.first to it.third },
    )
}

/**
 * What the play sheet shows ([state]), and the paired phone's session of the play it shows it for ([remote], L33): null for
 * none, or a play asked for on this device.
 */
data class SheetNow(val state: PlayState, val remote: RemotePlay?) {
    override fun toString() = "SheetNow(${state::class.simpleName})"
}

/** What a background search for the next episode has found so far. */
sealed interface Prefetch {
    data object Searching : Prefetch
    /** [choose]: in the release playing, which names no file for it; the play asks which it is ([PlayingPack]). */
    data class Ready(val releaseTitle: String, val sizeBytes: Long, val choose: Boolean = false) : Prefetch
    data object NothingFound : Prefetch
}

sealed interface PlayState {
    data object Idle : PlayState

    /** The episode finder is asking the indexers. */
    data class Searching(val title: String, val subtitle: String?, val line: String) : PlayState

    /** The finder found nothing playable; [stages] explain what each search did. */
    data class NoRelease(
        val title: String,
        val subtitle: String?,
        val stages: List<Pair<String, String>>,
        val note: String?,
        val nameQuery: String,
        val hidden: List<io.skjaere.usenet.search.RankedItem>,
        /** The TMDB title [nameQuery] is searched as (a movie, by its id); null searches the words. */
        val searchPick: SearchPick? = null,
        /** For a search of the words: the original language of the show they name (ISO 639-1), which its untagged releases speak. */
        val searchLanguage: String? = null,
        /**
         * [stages] as a paired phone is told them (L37): an indexer's error as how many didn't answer, and no indexer by
         * name, which is its address unless the user named it. None where the play wasn't a search's.
         */
        val remoteStages: List<Pair<String, String>> = emptyList(),
    ) : PlayState

    data class Running(
        val title: String,
        val subtitle: String?,
        val searchLine: String?,
        val attempt: Int,
        val of: Int,
        val release: String,
        val chips: List<String>,
        val steps: Map<PipelineStep, StepStatus>,
        val notice: String?,
        /** The release's failure [notice] names, before it says which release is tried next; a phone is told it in the TV's words (L37). */
        val noticeFailure: AttemptFailure? = null,
        /**
         * Whether [notice] says the same release is tried on the next debrid service ([nextServiceNotice], DEP-6), not that
         * the next release is: a phone is told it in its own words (L226).
         */
        val noticeNextService: Boolean = false,
        /** A cast's last step, "Starting on Kitchen display" (L65): null until the device is handed the release. */
        val castStep: StepStatus? = null,
        /**
         * A cast's lines under "Casting to Kitchen display" once its file is checked (W63, L142): the audio language the
         * device will play and that subtitles won't show, for a file with nothing else it can't play.
         */
        val castLines: List<CastLine> = emptyList(),
        /** The release's source's steps, in their order (L203): Usenet's four, or a debrid service's three, Preparing first. */
        val sourceSteps: List<PipelineStep> = UsenetSource.STEPS,
        /** Where the release plays from, as the lists tag it (L201): none while no service account is on. */
        val tag: SourceTag? = null,
    ) : PlayState

    /**
     * A release picked by hand that its Cast device can't fully play (L130, CsPhonePrecheck): the play waits for the user
     * to answer [precheck] ([PlaybackCoordinator.precheck]), or to cancel, before anything is sent to the device.
     */
    data class Prechecking(val title: String, val subtitle: String?, val precheck: CastPrecheck) : PlayState

    /**
     * A release of several videos names none of them for what plays ([lookingFor], e.g. "Looking for S02E01 'Brine'"), or
     * the user [asked] to choose (Choose release's "Choose file"), where one may be: the play waits for the user to choose
     * its file from [files] ([PlaybackCoordinator.chooseFile]), or to cancel. [prompt] says why the sheet asks.
     */
    data class ChoosingFile(
        val title: String,
        val subtitle: String?,
        val lookingFor: String,
        val release: String,
        val files: List<FileChoice>,
        val prompt: String = NONE_NAMED,
        val asked: Boolean = false,
    ) : PlayState {
        companion object {
            /** Why the sheet asks where nothing tells which file it is. */
            const val NONE_NAMED = "No file in this release is named for it. Choose the one it is:"
        }
    }

    /**
     * [notTried] were left to save grabs; [skipped] were from an indexer that refused a grab. [notReady]: a paired phone
     * asked for it on a TV that can't play yet (PtTvNotReady), nothing tried.
     */
    data class Failed(
        val title: String,
        val subtitle: String?,
        val failures: List<Pair<String, String>>,
        val notTried: Int,
        val skipped: Int = 0,
        /** What the sheet's Search searches for: set when nothing else would find another release (resuming a film or release). */
        val search: String? = null,
        /** The TMDB title [search] is for, so it is searched by its id and what plays saves progress to it; null for a release. */
        val searchPick: SearchPick? = null,
        /** Set when nothing was tried because no Usenet provider is usable ([noProviderFailure]). */
        val noProvider: NoUsableProvider? = null,
        /** Releases skipped without a grab as found missing on the providers in use before, each with why ([skippedLine]). */
        val missing: List<Pair<String, String>> = emptyList(),
        val notReady: NotReady? = null,
        /**
         * [failures]' reasons as a paired phone is told them (L37), in the same order: written from each failure's kind
         * and counts, never a library's or a server's words, which can name a provider's host. One left out is told as
         * the plain [io.skjaere.usenet.app.link.SheetMirror.COULDNT].
         */
        val remote: List<String> = emptyList(),
        /** A cast play's failure on its device, or of its file type (L83): its own words and buttons; null for any other. */
        val cast: CastFailed? = null,
        /** Of [notTried], those left as what they play from failed (a provider down, a debrid account's limit): no grab saved by it. */
        val noRoute: Int = 0,
    ) : PlayState {
        /** Nothing was tried: each release was skipped without a grab as found incomplete before ([missing]). */
        val onlySkipped: Boolean get() = failures.isEmpty() && missing.isNotEmpty()
    }
}

/**
 * How a play [PlaybackCoordinator.play] started came out: the player launched, it failed before playback started
 * ([Failed.failure], as the play sheet shows it), or it was stopped first (Cancel, another play).
 */
sealed interface PlayOutcome {
    data object Launched : PlayOutcome

    data class Failed(val failure: PlayState.Failed) : PlayOutcome {
        /**
         * Why [release], played alone as a release list's choice, didn't start, in the play sheet's words: its line, else
         * what the sheet says in its place (no usable provider, the play's job failing).
         */
        fun reason(release: String): String =
            (failure.failures + failure.missing).firstOrNull { it.first == release }?.second
                ?: failure.noProvider?.message
                ?: failure.failures.firstOrNull()?.second
                ?: "Couldn't start playback"
    }

    data object Stopped : PlayOutcome
}

/**
 * Runs the play pipeline for whichever screen asked (search results, an episode) and tells the
 * play sheet how it's going. On success it emits a [PlayerLaunch] for the activity to start.
 */
class PlaybackCoordinator(private val container: AppContainer, private val scope: CoroutineScope) {
    /**
     * The sheet's state: each write sets [state] and [sheet] together, and a job another play replaced writes nothing more
     * ([SheetWrites]).
     */
    private val _state = SheetWrites()
    val state: StateFlow<PlayState> = _state.state

    /**
     * What the sheet shows, with the paired phone's session of the play it shows it for (L33): what the TV tells phones of
     * a start (L34). Set with [state], in one step.
     */
    val sheet: StateFlow<SheetNow> = _state.sheet

    // A channel, not a SharedFlow: a launch emitted while no activity is collecting (the player
    // just finished for auto-advance, the app is in the background) waits instead of being lost.
    private val _launches = kotlinx.coroutines.channels.Channel<PlayerLaunch>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val launches: kotlinx.coroutines.flow.Flow<PlayerLaunch> = _launches.receiveAsFlow()

    // Heard only by whoever listens as a play starts, and never waited on: a play starting is no news later.
    private val _starting = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Each play the sheet starts, as it starts: a player in the picture-in-picture window pauses for it (L9). */
    val starting: SharedFlow<Unit> = _starting.asSharedFlow()

    /** Whether the sheet is open; tapping outside it only minimises it to the mini bar. */
    private val _expanded = MutableStateFlow(true)
    val expanded: StateFlow<Boolean> = _expanded.asStateFlow()

    private val _startedFrom = MutableStateFlow<StartedFrom?>(null)

    /**
     * What the sheet says of the play starting, when a paired phone asked for it (PtTvIncoming): "Started from Galaxy
     * phone", and after a hand-over what stopped for it; null for a play asked for here. Set as each play starts.
     */
    val startedFrom: StateFlow<StartedFrom?> = _startedFrom.asStateFlow()

    private val _searchSteps = MutableStateFlow(UsenetSource.STEPS)

    /**
     * The steps the sheet lists while a play searches (L203): Usenet's, or a debrid service's for a user with a service
     * account on and no usable provider ([searchingSteps]). Set as each play starts.
     */
    val searchSteps: StateFlow<List<PipelineStep>> = _searchSteps.asStateFlow()

    private val _castTo = MutableStateFlow<CastTo?>(null)

    /**
     * The Google Cast device the sheet's play goes to (L65): "Casting to Kitchen display" under its title, and its last
     * step; null for a play on this device. Set as each play starts.
     */
    val castTo: StateFlow<CastTo?> = _castTo.asStateFlow()

    private val _castGoesOn = MutableStateFlow<String?>(null)

    /**
     * Under "Casting to Kitchen display" while the next release starts from the missing-parts prompt, or a smaller one from
     * the slow-stream banner, during a cast (L84, L94, L135): the release playing goes on there until this one is ready, at
     * the place. Set as each play starts.
     */
    val castGoesOn: StateFlow<String?> = _castGoesOn.asStateFlow()

    /** The failure of the cast play [replaceJob] just began, whose service Android wouldn't start (L85, L97): its job shows it at once. */
    private var notStarted: PlayState.Failed? = null

    /**
     * The sheet's play asked for again from another origin: Play on this phone, where nothing was made for the device
     * (L83). Set as each play starts; null for one that has no such way back.
     */
    private var again: ((PlayOrigin) -> Unit)? = null

    private var job: Job? = null
    private val sessions = PlaySessions()

    /** Whether the sheet is starting a play now: searching, trying releases, or asking which file plays. */
    val startingPlay: Boolean get() = job?.isActive == true

    /**
     * The paired phone's session (L33) of the play on the sheet, while it starts or shows why it didn't; null for none, or
     * a play asked for here.
     */
    val sheetSession: RemotePlay? get() = _state.origin.remote?.takeIf { startingPlay || _state.value != PlayState.Idle }

    init {
        // A play that ends without starting lets the TV's mode held for it go at once (DisplayModeHold), rather than
        // keep the play sheet at a film's rate for its minute.
        scope.launch {
            state.collect { if (it is PlayState.Failed || it is PlayState.NoRelease) container.displayHold.clear("the next play didn't start") }
        }
    }

    fun minimize() {
        _expanded.value = false
        container.displayHold.clear("the play sheet was minimised")
    }

    fun expand() {
        _expanded.value = true
    }

    /**
     * The sheet's job is replaced by a play from [origin]: the one before stops (the entry points cancel it, L32), the
     * sheet opens, and says who asked, or which Cast device it goes to. A cast's service starts here, at the tap, saying
     * [title], [subtitle] and what [watch] they are as the session will (L63, L85); Android refusing it fails the play
     * before anything is searched. [goesOn]: what plays on there meanwhile, said under the cast's line (L84). [again]: the
     * play asked for again from another origin.
     */
    private fun replaceJob(
        origin: PlayOrigin, title: String = "", subtitle: String? = null, watch: WatchTarget? = null, goesOn: String? = null,
        again: ((PlayOrigin) -> Unit)? = null,
    ) {
        // A cast play still on its way, replaced by a play here, is stopped there (L85); one replaced by another cast is
        // given up by that one's start.
        val casting = _state.origin.cast != null && job?.isActive == true
        // The sheet is this play's before the one before is cancelled: what that one still does as it ends is dropped.
        _state.begin(origin)
        job?.cancel()
        if (casting && origin.cast == null) container.castLauncher()?.stop()
        // A launch the one before sent and no screen took is no longer coming (withdraw drops those it can).
        container.playerInFront.notLaunching()
        _expanded.value = true
        _startedFrom.value = StartedFrom.of(origin)
        _castTo.value = origin.cast
        _castGoesOn.value = goesOn?.takeIf { origin.cast != null }
        this.again = again
        notStarted = origin.cast?.let { cast ->
            val said = SessionMetadata.starting(title, subtitle, watch ?: WatchTarget(WatchKind.Release, title), container.appName)
            if (container.castLauncher()?.starting(cast, said) == true) null else CastStart.notStarted(title, subtitle, cast)
        }
    }

    /** Shows [failure] on the sheet, open, in place of what it showed: a paired phone's Play this TV can't start yet (L32). */
    fun tell(failure: PlayState.Failed) {
        replaceJob(PlayOrigin.Here)
        _state.value = failure
    }

    /**
     * Plays [request]; what comes of it is for the release list it was chosen on, which marks a release that failed to start.
     * [origin]: a cast's (L80), or this device's.
     */
    fun play(request: PlayRequest, origin: PlayOrigin = PlayOrigin.Here): Deferred<PlayOutcome> {
        replaceJob(origin, request.title, request.subtitle, request.watch) { play(request, it) }
        val outcome = CompletableDeferred<PlayOutcome>()
        fun failed(f: PlayState.Failed) {
            outcome.complete(PlayOutcome.Failed(f))
        }
        job = launchPlay(request.title, request.subtitle, ::failed, origin) {
            runPipeline(request)?.let {
                _state.value = it
                failed(it)
            }
        }.apply {
            // Ended without a failure, the player launched, or the Cast device plays it; else the failure came first, or Cancel or
            // another play stopped it.
            invokeOnCompletion { cause -> outcome.complete(if (cause == null) PlayOutcome.Launched else PlayOutcome.Stopped) }
        }
        return outcome
    }

    /**
     * A file Import opened, cast (L147, W44 (i)): [file] of the NZB [nzbId], which Import prepared and stored, played from
     * [origin], the cast's. A short play with nothing searched, grabbed or prepared again ([castImported]): the file's type,
     * its tracks and the device's verdict on them, asked about first where the device can't fully play them (L130), as for
     * any file picked by hand, then the hand-over (L65). Its launch is made for the file ([importRequest], [importedRelease])
     * and saves no progress. Play on this phone, after a failure that sent nothing to the device, plays the file here.
     */
    fun castFile(nzbId: String, file: FileCandidate, origin: PlayOrigin) {
        val to = origin.cast ?: return
        val request = importRequest(container.store.get(nzbId)?.title ?: file.name)
        replaceJob(origin, request.title, request.subtitle, request.watch) { again ->
            if (again.cast == null) playFileHere(nzbId, file, request) else castFile(nzbId, file, again)
        }
        // Import's file plays from Usenet alone (L165): it needs a provider, whatever else could play.
        job = launchPlay(request.title, request.subtitle, origin = origin, gate = { container.noUsableProvider() }) {
            castImported(nzbId, file, request, to)?.let { _state.value = it }
        }
    }

    /**
     * Resumes [state] from Home: the release that played it, from where it stopped, its NZB from
     * the store while it is there and else grabbed again. An episode whose release fails is
     * searched for as Play on the Show screen does; a film or release shows the failure. [origin]: a cast's (L80), or
     * this device's.
     */
    fun resume(state: WatchState, origin: PlayOrigin = PlayOrigin.Here) {
        val plan = ResumePlan.of(state)
        val watch = WatchTarget(state.kind, state.title, state.showTmdbId, state.season, state.episode, state.movieTmdbId)
        replaceJob(origin, plan.title, plan.subtitle, watch) { resume(state, it) }
        job = launchPlay(plan.title, plan.subtitle, origin = origin) {
            val offService = offService()
            plan.run(
                play = { runPipeline(it) },
                searchEpisode = { target, retry -> lookUpAndPlay(target, retry) },
                // A torrent from an indexer kept from the service since: the episode is searched for (DDR-3).
                passOver = { Replay.leftOut(it, offService) != null },
            )?.let { _state.value = it }
        }
    }

    /**
     * The torrent indexers whose "Use with your debrid service" is off, by id, from every one saved, whether a search asks it
     * now or not ([IndexerConfigs.offService]): nothing of theirs goes to a service (L194, DDC-2). Not from
     * container.indexerConfigs(), which lists only what a search asks: a disabled or removed indexer, or one whose key
     * can't be read, would be missing. None while debrid is hidden, whose plays name no service (DDC-2b).
     */
    private suspend fun offService(): Set<String> = IndexerConfigs.offService(container.settings.settings.first(), DebridGate.shown)

    /**
     * Play on a movie's page ([MoviePlay]): the release that played it before, alone, while its NZB
     * is stored; else, or when that fails, the best one the Search tab's search for the movie finds.
     * [origin]: a paired phone's play (L33), which the TV starts as from its own Movie page.
     */
    fun playMovie(target: MovieTarget, origin: PlayOrigin = PlayOrigin.Here) {
        replaceJob(origin, target.title, target.subtitle, target.watch) { playMovie(target, it) }
        job = launchPlay(target.title, target.subtitle, origin = origin) {
            val remembered = container.library.lastRelease(WatchKey.movie(target.tmdbId))
            // A torrent the play would leave out (kept from the service since) isn't played alone: the film is searched for.
            val playable = remembered?.let { Replay.leftOut(Replay.candidate(it), offService()) == null } == true
            val stored = remembered?.let { if (it.source == null) it.nzbId?.let { id -> container.store.stored(id) } != null else playable && kept(it) } == true
            MoviePlay.run(
                target, remembered, stored, java.time.LocalDate.now(),
                play = { request, retry -> runPipeline(request, retry) },
                searching = { line -> _state.value = PlayState.Searching(target.title, target.subtitle, line) },
                // Play's own pick shows no list, and can't take one back: it waits for torrents still checked, 10 s at most (L199).
                find = { io.skjaere.usenet.app.search.findMovieReleases(container, target).last() },
            )?.let { _state.value = it }
        }
    }

    /**
     * Plays an episode known by its TMDB numbers (a next episode on Home): looks it up on TMDB, then as [playEpisode].
     * [origin]: a cast's (L80), or this device's.
     */
    fun playEpisode(target: EpisodeTarget, origin: PlayOrigin = PlayOrigin.Here) {
        val watch = WatchTarget(WatchKind.Episode, WatchTitle.episode(target.showName, target.season, target.episode), showTmdbId = target.showTmdbId, season = target.season, episode = target.episode)
        replaceJob(origin, target.showName, target.label, watch) { playEpisode(target, it) }
        job = launchPlay(target.showName, target.label, origin = origin) { lookUpAndPlay(target, retry = null) }
    }

    /**
     * "Try another release" after [launch]'s release failed mid-stream at [positionMs]: forgets it
     * as the release that played the watch key, then plays the same request without it and without
     * any release that failed before during this play, starting where it stopped. Without the
     * request (the process died since) an episode is searched for again; anything else has nothing
     * to try, which [canTryAnother] tells the player beforehand. After a Cast device couldn't play it (L83), the play stays
     * on the device, and the release is forgotten only where the providers failed it ([forget]): this phone plays it. A
     * debrid failure that [stopped] its service account's route (its key refused, its limit, an outage) leaves out the
     * releases that play from that account alone, as each would fail the same way (L204).
     */
    fun tryAnother(launch: PlayerLaunch, positionMs: Long?, forget: Boolean = true, stopped: Set<String> = emptySet()) {
        val plan = sessions.retry(launch, positionMs, stopped = stopped)
        val origin = plan.origin ?: return
        replaceJob(origin, launch.title, launch.subtitle, launch.watch) { tryAnother(launch.copy(cast = it.cast), positionMs, forget, stopped) }
        job = launchPlay(launch.title, launch.subtitle, origin = origin) {
            if (forget) forget(launch.watchKey, launch.release.toRemembered())
            when (plan) {
                is RetryPlan.Again -> runPipeline(plan.request, plan.retry)?.let { _state.value = it }
                is RetryPlan.SearchEpisode -> lookUpAndPlay(plan.target, plan.retry)
                RetryPlan.None -> Unit
            }
        }
    }

    /**
     * "Try another release" from the player's prompt (L10): [launch]'s release still plays, with parts missing, at
     * [positionMs]. The same request plays again without it, from there, as for a release that failed; but it stays the
     * release Resume plays, and isn't remembered as missing on the providers its stream was fetched from ([asked]), until
     * another release plays. If none does, the play goes back to it from where it was (W3, [Left]). During a cast the
     * release plays on there until the next one loads, which the sheet says (L84, L94), and nothing loads if the play goes
     * back to it.
     */
    fun tryAnotherForMissingParts(launch: PlayerLaunch, positionMs: Long, asked: List<ProviderConfig>) {
        val plan = sessions.retry(launch, positionMs, Left(launch.release, launch.watchKey, asked))
        val origin = plan.origin ?: return
        replaceJob(origin, launch.title, launch.subtitle, launch.watch, goesOn = CastStart.goesOn(launch, plan)) {
            tryAnotherForMissingParts(launch.copy(cast = it.cast), positionMs, asked)
        }
        job = launchPlay(launch.title, launch.subtitle, origin = origin) {
            when (plan) {
                is RetryPlan.Again -> runPipeline(plan.request, plan.retry)?.let { _state.value = it }
                is RetryPlan.SearchEpisode -> lookUpAndPlay(plan.target, plan.retry)
                RetryPlan.None -> Unit
            }
        }
    }

    /**
     * "Try a smaller release" from the player's slow-stream banner (L8): [launch]'s release still plays, at [positionMs],
     * read at [measuredBps] where the file needs [playingBps]. The request's releases the ranker estimates well under it play
     * from there, those the stream carries first ([PlaySessions.smaller]). Each is an NZB grab, so it is never done without
     * the user asking. The release that plays stays the one Resume plays, and is neither remembered as missing nor taken
     * for failed; if no smaller one plays, the play goes back to it from where it was (W3). During a cast the release plays
     * on there until the smaller one loads, which the sheet says (L84, L135), and nothing loads if the play goes back to it.
     */
    fun trySmaller(launch: PlayerLaunch, positionMs: Long, measuredBps: Long, playingBps: Long) {
        val plan = sessions.smaller(launch, positionMs, measuredBps, playingBps) as? RetryPlan.Again ?: return
        replaceJob(plan.origin, launch.title, launch.subtitle, launch.watch, goesOn = CastStart.goesOn(launch, plan)) {
            trySmaller(launch.copy(cast = it.cast), positionMs, measuredBps, playingBps)
        }
        job = launchPlay(launch.title, launch.subtitle, origin = plan.origin) { runPipeline(plan.request, plan.retry)?.let { _state.value = it } }
    }

    /**
     * Whether [trySmaller] has a smaller release to try for [launch], read at [measuredBps] where it needs [playingBps] (W2):
     * not one remembered as missing on the providers in use, which the play would skip and go straight back to this one
     * ([MissingReleases.skipped]). A lookup that fails leaves them in: the play skips them if it must.
     */
    suspend fun canTrySmaller(launch: PlayerLaunch, measuredBps: Long, playingBps: Long): Boolean {
        val plan = sessions.smaller(launch, positionMs = null, measuredBps, playingBps) as? RetryPlan.Again ?: return false
        val known = try {
            container.missingReleases.skipped(plan.request.candidates.associate { it.key to it.item }, plan.request.chosen)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("play: couldn't look up the smaller releases found missing", e)
            emptySet()
        }
        return known.isEmpty() || sessions.smaller(launch, positionMs = null, measuredBps, playingBps, known) != RetryPlan.None
    }

    /** Test seam: keeps [request] as the one behind a launch a test makes itself, as a launch from the pipeline is kept; returns its id. */
    @VisibleForTesting
    internal fun keep(request: PlayRequest, failed: Set<String> = emptySet()): String = sessions.start(request, failed)

    /** Whether [tryAnother] has anything to try for [launch]. */
    fun canTryAnother(launch: PlayerLaunch, stopped: Set<String> = emptySet()): Boolean = sessions.retry(launch, positionMs = null, stopped = stopped) != RetryPlan.None

    /**
     * [launch]'s release failed in a way trying it again can't fix (articles missing, a format the
     * device can't play): it is no longer the release Resume and replay choose for its watch key.
     */
    fun forgetFailed(launch: PlayerLaunch) {
        scope.launch { forget(launch.watchKey, launch.release.toRemembered()) }
    }

    /**
     * [launch]'s release stopped mid-play on an article the providers it was fetched from lack: it is
     * remembered as missing on the one in use, so a play skips it without a grab until a provider it
     * wasn't checked on is in use. Only while it is the one [asked] (in use as the player started), and
     * not while several are, as one may have been passed over without being asked
     * ([MissingOn.ProvidersFetchedFrom]), nor when what played is one of several videos in it (an
     * episode of a season pack): the others may be whole.
     */
    fun rememberMissing(launch: PlayerLaunch, asked: List<ProviderConfig>) = rememberMissing(launch.release, asked)

    private fun rememberMissing(release: SavedRelease, asked: List<ProviderConfig>) {
        if (container.store.get(release.nzbId)?.oneVideo != true) return
        scope.launch {
            saveOrLog("remember ${release.title} as missing on the providers") {
                val indexer = container.settings.indexers.first().firstOrNull { it.id == release.indexerId }?.name
                container.missingReleases.record(release.indexerId, release.guid, release.title, indexer, MissingOn.ProvidersFetchedFrom, asked)
            }
        }
    }

    /**
     * [launch]'s debrid play stopped, its file no longer to be had ([failure], L174, L204): remembered against the account
     * that played it as a play's failures of that account are (no working link, not cached or refused for an hour; a
     * removal 30 days), so plays pass it over meanwhile. The routes it stops (the account's limit, refused key or outage),
     * which Try another release leaves out; none where this process doesn't hold the play's listing.
     */
    fun serviceFailed(launch: PlayerLaunch, failure: LinkFailure): Set<String> {
        val failed = ServicePlayFailure.of(launch, failure) { id -> container.debridListings.keptAs(id)?.account } ?: return emptySet()
        scope.launch { saveOrLog("remember a debrid play's failure") { container.serviceMisses.record(failed.candidate, failed.account, failed.failure) } }
        return failed.stopped
    }

    /** Forgets [release] as the one that played [watchKey]; a failure (a full disk) is logged, and the play goes on. */
    private suspend fun forget(watchKey: String, release: RememberedRelease) =
        saveOrLog("forget ${release.title} for $watchKey") { container.library.forgetRelease(watchKey, release) }

    /**
     * Starts [block] as the sheet's job; a failure it doesn't handle ends in [PlayState.Failed] for
     * [title] ([failureOf]), unless the job was cancelled for another meanwhile. With nothing to
     * play from ([gate]: no usable provider, nor a service account on) nothing could play, so the
     * sheet says so at once, before any search or grab. Each
     * failure shown here is also handed to [onFailed]. Those listening hear it start ([starting]). The job keeps its
     * [origin] (L33), which each launch it makes takes ([launchInJob]).
     *
     * A cast play's job ends at a failure on its device ([EndedOnDevice], L83), and one whose service didn't start fails at
     * once. One that ends without the device playing it stops its service (L85), while it still has the sheet: Cancel and
     * another play see to their own. What the job's attempts added to a service account, and the listings they prepared,
     * are held by the play ([DebridAdds.Play]) until the job ends, however it ends (L234): the player's lease, or a cast's,
     * taken as the release launches, falls within the grace that then begins.
     */
    private fun launchPlay(
        title: String,
        subtitle: String?,
        onFailed: (PlayState.Failed) -> Unit = {},
        origin: PlayOrigin = PlayOrigin.Here,
        gate: suspend () -> NoUsableProvider? = { container.noUsableSource() },
        block: suspend () -> Unit,
    ): Job {
        val refused = notStarted.also { notStarted = null }
        // What this play's attempts add to a service account is born held by the play (L234).
        val play = DebridAdds.Play()
        return scope.launch(PLAY + origin + _state.writer() + play) {
            _starting.tryEmit(Unit)
            try {
                if (refused != null) {
                    _state.value = refused
                    onFailed(refused)
                    return@launch
                }
                failureOf(title, subtitle) {
                    val missing = gate()
                    if (missing == null) {
                        _searchSteps.value = searchingSteps(container.usableRoutes())
                        try {
                            block()
                        } catch (e: EndedOnDevice) {
                            ensureActive()
                            _state.value = e.failed
                            onFailed(e.failed)
                        }
                    } else {
                        ensureActive()
                        noProviderFailure(title, subtitle, missing).let {
                            _state.value = it
                            onFailed(it)
                        }
                    }
                }?.let {
                    ensureActive()
                    _state.value = it
                    onFailed(it)
                }
            } finally {
                // Played, failed or cancelled: the grace of what it added begins (L234, L245).
                container.debridAdds.release(play)
                if (origin.cast != null) _state.ifCurrent { if (_state.value !is PlayState.Idle) container.castLauncher()?.stop() }
            }
        }
    }

    /** A cast play's failure on its device (L83): its job ends with it, past the fallbacks meant for a release this phone can't play. */
    private class EndedOnDevice(val failed: PlayState.Failed) : Exception()

    /** Looks [target] up on TMDB and finds and plays it; a [retry] leaves out the releases that just failed to play it. */
    private suspend fun lookUpAndPlay(target: EpisodeTarget, retry: Retry?) {
        val line = "Looking up the episode on TMDB…"
        _state.value = PlayState.Searching(target.showName, target.label, if (retry != null) "${retry.searchingNotice} · $line" else line)
        val ep = try {
            val show = TmdbLookup.show(container, target.showTmdbId)
            val episode = TmdbLookup.season(container, target.showTmdbId, target.season).episodes.firstOrNull { it.episode == target.episode }
            if (episode == null) {
                _state.value = lookUpFailed(target, "TMDB doesn't list ${target.label}")
                return
            }
            EpisodePlay.of(show, episode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = lookUpFailed(target, "Couldn't look it up on TMDB: ${(e as? TmdbException)?.error?.message ?: e.message}", "Couldn't look it up on TMDB")
            return
        }
        _state.value = PlayState.Searching(ep.showName, ep.subtitle, "Searching your indexers…")
        findAndPlay(ep, retry)
    }

    /**
     * Runs the pipeline for [request], the release that played its watch key before first
     * ([PlayRequest.rememberedFirst]), with its NZB from the store while it is there and the file
     * that played in it ([Replay.picker]). A [retry]
     * leaves out the releases that failed during the play and starts the player where the last one
     * stopped. A release found missing on every provider in use before is skipped without a grab,
     * unless the user chose it, and one found missing now is remembered ([MissingReleases]). With
     * [prepared], the NZB the request's release plays from as it was prepared for the play before,
     * that NZB isn't prepared again ([PlayingPack]). Where a release of several videos names none for
     * the episode, the play asks which it is ([askForFile]). Returns the failure to show, or null once
     * the player is launched. A [retry] from a release left while it still played ([Retry.left]) goes back to that release
     * if no other plays: its last try, from its stored NZB, from where it was. Once another plays, one left for its missing
     * parts is remembered as missing on the providers it was fetched from, and no longer the release Resume plays; one left
     * for a smaller release is neither, and the one that plays takes Resume over as its player saves. [againHere]: whether
     * the play asked for, which a cast's all-failed or file type's sheet's Play on this phone asks for again here, may play
     * here: false for Play a compatible release after a release picked by hand whose file this phone's own probe refused
     * (CFU-1).
     */
    private suspend fun runPipeline(request: PlayRequest, retry: Retry? = null, prepared: PreparedNzb? = null, againHere: Boolean = true): PlayState.Failed? {
        val cast = currentCoroutineContext()[PlayOrigin]?.cast
        val indexers = container.indexerConfigs().associateBy { it.id }
        val offService = offService()
        val remembered = request.remembered ?: request.watch.rememberedKey?.let { container.library.lastRelease(it) }
        val stored = remembered?.takeIf { it.source == null }?.nzbId?.let { id -> container.store.stored(id) }
        // A remembered torrent plays from the service accounts on (L181), first while its listing is kept, as a stored NZB does.
        val accountsOn = container.services.on().map { it.id }
        // A torrent is never sent to a service from an indexer kept from it, whatever list or memory it came from: a
        // remembered one skips the search's ranking, which would hide it (L194). A Cast device's is relayed by the phone (W73).
        val (ordered, leftOut) = Replay.toTry(
            request.candidates, remembered, stored != null || remembered?.let { kept(it) } == true, request.rememberedFirst, request.hidden,
            accountsOn, offService,
        )
        // Only what a route usable now plays is tried (L165): Usenet's releases while a provider is usable.
        val routes = container.usableRoutes()
        // A torrent plays only from the accounts on now, whatever list it came from (DEP-4).
        val candidates = routes.cut((retry?.leaveOut(ordered) ?: ordered).map(routes::narrow)) { it.playCandidate }
        val left = retry?.left
        val wayBack = Replay.wayBack(left, accountsOn)?.let(routes::narrow)
            ?.takeIf { routes.keeps(it.playCandidate) && Replay.leftOut(it, offService) == null }
        // Times the play's start in the log, which had nothing to time a click by (#273); the engine's lock lines say if it then waits.
        log.info(
            "play: {}: {} release{} to try{}{}", request.title, candidates.size, if (candidates.size == 1) "" else "s", if (request.chosen) ", chosen" else "",
            if (wayBack != null) ", then back to the one that was playing" else "",
        )
        if (candidates.isEmpty() && wayBack == null) {
            // Only what was left out: why each was, in the ranking's words (a film's Resume, from a torrent kept from the service).
            val why = leftOut.mapNotNull { item -> Replay.leftOut(item, offService)?.let { item.item.title to it.text } }
                .ifEmpty { listOf(request.title to NO_OTHER_RELEASE) }
            return PlayState.Failed(request.title, request.subtitle, why, notTried = 0, remote = why.map { it.second })
        }
        val byKey = (candidates + listOfNotNull(wayBack)).associateBy { it.key }
        val grab = indexerGrab(byKey.mapValues { it.value.item }, indexers) { indexer, item -> fetchNzb(container, indexer, item) }
        // The release that was playing, from the NZB the store keeps for it (it was just playing): grabbed again only if gone.
        val leftStored = left?.let { container.store.stored(it.release.nzbId) }
        val source = ReplaySource(remembered, stored?.first, nzbSource(container, grab), storedPassword = stored?.third).let { source ->
            if (left != null && leftStored != null) ReplaySource(left.release.toRemembered(), leftStored.first, source, storedPassword = leftStored.third) else source
        }
        // Its file is the one that played, wherever the request's picker would look.
        val picker = playPicker(request, remembered).let { picker -> left?.let { Replay.picker(picker, it.release.toRemembered(), request.watch) } ?: picker }
        // A cast's file of a type no Cast device plays is refused before it is read (L57); the rest is its device's verdict on
        // the file's tracks, by the device's profile (L129), which Cast anyway doesn't ask, and what the user wants of them
        // says the lines before it plays (L142). A release picked by hand that the device can't fully play asks first (L130):
        // it may play here instead ([here], the release's id), or the list's others in its place ([compatible]), the release
        // picked playing here or not, by this phone's own probe ([pickedHere]).
        val judged = cast?.let { to -> Judged(to, container.castProfile(to), castPrefs(request.watch)) }
        var here: String? = null
        var compatible: PlayRequest? = null
        var pickedHere = true
        val probe = judged?.let { (to, profile, prefs) ->
            val ask: (suspend (PlaybackTarget, ProbeResult.CastMiss, ProbedTracks, Boolean) -> CastPrecheck.Button)? = if (!request.chosen || request.castAnyway) null else ask@{ target, _, tracks, playsHere ->
                val item = byKey.getValue(target.candidate.id)
                val others = CastPrecheck.compatible(request)
                val precheck = CastPrecheck.of(CastVerdict.judge(tracks, profile, prefs), tracks, to.name, item.item.title, item.parsed.audio, others != null, playsHere)
                    ?: return@ask CastPrecheck.Button.CastAnyway
                askBeforeCasting(request, precheck).also { answer ->
                    if (answer == CastPrecheck.Button.PlayHere) here = target.candidate.id
                    if (answer == CastPrecheck.Button.Compatible) {
                        compatible = others
                        pickedHere = playsHere
                    }
                }
            }
            CastProbe(container.trackProbe, judge = { tracks -> if (request.castAnyway) null else CastVerdict.miss(tracks, profile) }, ask = ask)
        } ?: container.trackProbe
        val usenet = UsenetSource(
            source,
            container.availability, prepared?.let { PlayingPack.preparer(it, EnginePrepareBackend(container.engine)) } ?: container.preparer,
            container.store,
            providersLeftOut = { container.engine.leftOut.value.isNotEmpty() },
            providerFallback = { container.engine.fallback.value },
            chooser = { candidate, _, choices -> askForFile(request, candidate, choices) },
        )
        // The account each torrent's Preparing is trying, by the release's key: what its tag on the sheet names (DEP-10).
        val tryingOn = java.util.concurrent.ConcurrentHashMap<String, String>()
        // A torrent's release from the service accounts that have it (L203), its file asked about as a pack's on Usenet is.
        val service = ServiceSource(
            { id -> container.services.service(id)?.service }, container.debridListings,
            chooser = { candidate, _, choices -> askForFile(request, candidate, choices) }, misses = container.serviceMisses, chosen = request.chosen,
            links = container.serviceLinks, onTrying = { candidate, account -> tryingOn[candidate.id] = account },
            onListed = { id -> container.debridAdds.holdForPlay(id) },
        )
        val pipeline = PlayPipeline(
            { candidate -> if (candidate.source == PlayCandidate.USENET) usenet else service },
            probe,
            maxAttempts = container.settings.playback.first().attempts,
            // Usenet's releases by what is known of them on the providers; a torrent's by what its accounts failed of it (L174).
            tried = triedBySource(
                container.missingReleases.forPlay(byKey.mapValues { it.value.item }, request.chosen) { id -> indexers[id]?.name },
                container.serviceMisses.forPlay(request.chosen),
            ),
        )
        val steps = mutableMapOf<PipelineStep, StepStatus>()
        // Releases skipped as found missing before: the sheet lists them, release and why.
        val skipped = mutableListOf<Pair<String, String>>()
        var notice: String? = retry?.notice
        var noticeFailure: AttemptFailure? = null
        var noticeNextService = false
        var current: RankedItem? = null
        var castLines: List<CastLine> = emptyList()
        var attempt = 0
        var of = 0
        var failed: PlayState.Failed? = null
        var failedAttempts: List<Pair<PlayCandidate, AttemptFailure>> = emptyList()
        // The file each release's attempt picked (its Open step's detail), so a file the user chose failing isn't taken for another's.
        val picked = mutableMapOf<String, String?>()
        // The launch handed to its Cast device, whose outcome the play waits on once the pipeline is done.
        var handed: Handed? = null
        // The service accounts on, in the user's order, with their services' names: what the release's tag says it plays from (L201).
        val accounts = container.serviceNames.first()
        // A play not chosen passes over an account remembered as having failed a torrent, so its tag on the sheet does too,
        // until Preparing starts on one (L247, COT-2).
        val passedOver = if (request.chosen) emptyMap() else try {
            container.serviceMisses.passedOver((candidates + listOfNotNull(wayBack)).filter { it.item.infoHash != null }.map { it.playCandidate })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unread, the tag names the first account that has it until one is tried, as before.
            emptyMap()
        }

        fun running(): PlayState.Running? {
            val item = current ?: return null
            return PlayState.Running(
                request.title, request.subtitle, request.searchLine, attempt, of, item.item.title,
                summaryChips(item) + listOfNotNull(item.parsed.audio.firstOrNull()?.let { a -> a.label + (item.parsed.channels?.let { " $it" } ?: "") }),
                steps.toMap(), notice, noticeFailure, noticeNextService, castLines = castLines,
                sourceSteps = if (item.playCandidate.source == PlayCandidate.USENET) usenet.steps else service.steps,
                tag = playTag(item, accounts, tryingOn[item.key], passedOver[item.key].orEmpty()),
            )
        }

        fun publishRunning() {
            // Moving on to a compatible release: this one's failed probe isn't shown on the way.
            if (compatible != null) return
            val running = running() ?: return
            // In one step with the read of what shows: the question may be put up between the two otherwise.
            _state.update { shown -> runningOver(shown, running, asking = choosing != null || prechecking != null) }
        }

        // A release is looked up by the providers the engine has checked, which it does first when it hasn't since they
        // changed (a new process, a provider edited, one left out due its recheck): seconds while one is out of reach,
        // which the sheet says rather than nothing until the first release is tried (TR-11). Here only: the play's
        // lookups go by what this check leaves in use ([MissingReleases.forPlay]). Only a play with a release that may play
        // from Usenet waits for it (L165).
        if (waitsForProviders(byKey.values.map { it.playCandidate })) {
            sayingIfSlow(PROVIDER_CHECK_GRACE_MS, { _state.value = PlayState.Searching(request.title, request.subtitle, checkingProvidersLine(retry)) }) {
                container.engine.checkedInUse()
            }
        }
        pipeline.play(candidates.map { it.playCandidate }, picker, last = wayBack?.playCandidate?.copy(wasPlaying = true)).collect { event ->
            when (event) {
                is PipelineEvent.AttemptStarted -> {
                    noticeFailure = null
                    noticeNextService = false
                    if (left != null && event.candidate.id == wayBack?.key) {
                        notice = left.backNotice
                    } else if (event.attempt > 1) {
                        val last = steps.values.filterIsInstance<StepStatus.Failed>().lastOrNull()?.failure
                        notice = retryNotice(last, event.attempt, event.of, cast)
                        noticeFailure = last
                    } else if (skipped.isNotEmpty()) {
                        notice = listOfNotNull(retry?.notice, skippedNotice(skipped.map { it.second })).joinToString(" · ")
                    }
                    steps.clear()
                    castLines = emptyList()
                    attempt = event.attempt
                    of = event.of
                    current = byKey.getValue(event.candidate.id)
                    publishRunning()
                }
                is PipelineEvent.Step -> {
                    steps[event.step] = event.status
                    (event.status as? StepStatus.Done)?.takeIf { event.step == PipelineStep.Open }?.let { picked[event.candidate.id] = it.detail }
                    publishRunning()
                }
                is PipelineEvent.Skipped -> skipped += event.candidate.title to event.reason
                // The same release on the next debrid service that has it (DEP-6): the notice says so, naming none, and the
                // steps after Preparing go back to waiting as Preparing runs again there; the tag follows as it starts.
                is PipelineEvent.Retrying -> {
                    notice = nextServiceNotice(event.failure, cast)
                    noticeFailure = event.failure
                    noticeNextService = true
                    steps.keys.retainAll(retriedSteps(steps, if (event.candidate.source == PlayCandidate.USENET) usenet.steps else service.steps).keys)
                    publishRunning()
                }
                is PipelineEvent.Succeeded -> {
                    failedAttempts = event.failedAttempts
                    val target = event.target
                    val release = savedRelease(byKey.getValue(target.candidate.id).item, target)
                    try {
                        target.keep()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Disk full or an I/O error: the player finds the NZB, or a service's listing, in its store, so it can't play this one.
                        val failures = event.failedAttempts.map { (candidate, failure) -> candidate.title to failure.text }
                        val nzb = target.candidate.source == PlayCandidate.USENET
                        prefetched.clear()
                        failed = PlayState.Failed(
                            request.title, request.subtitle,
                            failures + (target.candidate.title to "${if (nzb) "Couldn't save the NZB" else "Couldn't save the release's file list"}: ${e.message}"), notTried = 0,
                            remote = event.failedAttempts.map { SheetMirror.failure(it.second) } + if (nzb) "Couldn't save the NZB on the TV" else "Couldn't save the release on the TV",
                        )
                        return@collect
                    }
                    // Another release plays in place of the one left for its missing parts: that one is missing on
                    // the providers it was fetched from, and Resume no longer plays it.
                    if (left?.why == Left.Why.MissingParts && target.candidate.id != wayBack?.key) {
                        rememberMissing(left.release, left.asked)
                        forget(left.watchKey, left.release.toRemembered())
                    }
                    // Kept for "Try another release", with the releases that failed so far: an indexer
                    // refusing a grab is no fault of its release, which may be tried again. Gone back to the
                    // release that was playing, the play's whole request again.
                    val failedReleases = retry?.failed.orEmpty() + event.failedAttempts.filterNot { (_, f) -> f.blocksIndexer }.map { it.first.id }
                    // The service accounts' routes the play stopped, inside the attempt that played too (DEP-5): Try another
                    // leaves out what plays from those alone.
                    val stoppedRoutes = retry?.stopped.orEmpty() + (event.stopped + event.failedAttempts.mapNotNull { it.second.route }).filter { it != PlayCandidate.USENET }
                    val kept = retry?.kept(request, wentBack = target.candidate.id == wayBack?.key) ?: request
                    // Played here at the pre-check's word: this phone's, from the store, nothing sent to the device (L130).
                    val launch = launchInJob(request, release, target.uri, sessions.start(kept, failedReleases, stoppedRoutes), retry, event.video, event.tracks)
                        .let { if (target.candidate.id == here) it.copy(cast = null) else it }
                    // W63: with nothing else the device can't play, what it will play is said under where it plays.
                    castLines = judged?.takeIf { launch.cast != null }
                        ?.let { (to, profile, prefs) -> CastPrecheck.startingLines(event.tracks?.let { CastVerdict.judge(it, profile, prefs) }, to.name) }.orEmpty()
                    when (CastStart.handover(launch, wentBack = left != null && target.candidate.id == wayBack?.key)) {
                        CastStart.Handover.Player -> {
                            // Before the sheet closes: until its player starts, the play is on its way, not over (L34). Only
                            // while this play has the sheet: one replaced in this moment sends nothing, its cancel coming too
                            // late to stop it.
                            _state.ifCurrent {
                                container.playerInFront.launched(launch.remote)
                                _launches.trySend(launch)
                            }
                            _state.value = PlayState.Idle
                        }
                        // The sheet stays on the device's step until it plays the release (L65).
                        is CastStart.Handover.Device -> handed = running()?.let { handOver(launch, it) }
                        // The release left for its missing parts still plays there (L84): nothing to load, and the play
                        // started for another ends.
                        CastStart.Handover.Stays -> {
                            _state.ifCurrent { container.castLauncher()?.stop() }
                            _state.value = PlayState.Idle
                        }
                    }
                }
                is PipelineEvent.Exhausted -> {
                    failedAttempts = event.failedAttempts
                    prefetched.clear()
                    val exhausted = PlayState.Failed(
                        request.title, request.subtitle,
                        event.failedAttempts.map { (candidate, failure) -> candidate.title to failure.text },
                        event.notTried,
                        event.skipped,
                        missing = skipped.toList(),
                        remote = event.failedAttempts.map { SheetMirror.failure(it.second) },
                        noRoute = event.noRoute,
                    )
                    // Every release refused for its file type, or one the device can't fully play: nothing was sent to it (L57, L83, L149).
                    failed = cast?.let {
                        CastStart.refused(exhausted, event.failedAttempts.map { it.second }, it, request.chosen, againHere)
                            ?: CastStart.notFully(exhausted, event.failedAttempts, it, request, byKey, againHere)
                    } ?: exhausted
                }
            }
        }
        // Broken (articles gone, passworded, ...): Resume and replay no longer choose it.
        if (remembered != null && Replay.broke(remembered, failedAttempts, choseFile = request.picker is ChooseFile, pickedFile = { picked[it.id] })) {
            Replay.watchKey(request.watch, remembered, request.resumeKey)?.let { forget(it, remembered) }
        }
        handed?.let { onDevice(it) }
        // Play a compatible release (L152): the list's others in the picked one's place, in the same play, an automatic pick.
        // Play on this phone, should none of them play fully there, asks again for the release picked: only where it plays.
        compatible?.let { return runPipeline(it, againHere = pickedHere) }
        return failed
    }

    /** What a cast's file is judged by (L129, L142): its device [to], that device's [profile], and what the user wants of its tracks. */
    private data class Judged(val to: CastTo, val profile: CastProfile, val prefs: CastVerdict.Prefs)

    /**
     * What the user wants of a file's tracks, as this phone's player reads it as it starts: the audio languages, Subtitles
     * when audio isn't your first language, and the subtitles [watch]'s show remembers, for an episode.
     */
    private suspend fun castPrefs(watch: WatchTarget): CastVerdict.Prefs {
        val playback = container.settings.playback.first()
        val show = watch.showTmdbId?.takeIf { watch.kind == WatchKind.Episode }?.let { container.library.trackChoice(it) }?.let(TrackChoices::fromShow)
        return CastVerdict.Prefs(playback.effectiveAudioLanguages(Locale.getDefault().language), playback.subtitlesWhenForeign, show?.subtitles)
    }

    /** [launch] handed to its device as [load], the sheet on [sent], since [since] (System.nanoTime). */
    private class Handed(val launch: PlayerLaunch, val load: CastLoad, val sent: PlayState.Running, val since: Long)

    /**
     * Hands [launch] to its Cast device (L65), in one step with the sheet still being this play's: the launcher takes it at
     * once, and the sheet's last step, "Starting on Kitchen display", runs over [running]. Null where another play has the
     * sheet now.
     */
    private fun handOver(launch: PlayerLaunch, running: PlayState.Running): Handed? {
        var load: CastLoad? = null
        _state.ifCurrent { load = container.castLauncher()?.load(launch) }
        val handed = Handed(launch, load ?: return null, CastStart.loading(running), System.nanoTime())
        _state.value = handed.sent
        return handed
    }

    /**
     * Waits for the device to play what was [handed] to it: the sheet then closes; a failure there ends the play's job with
     * its own sheet (L83).
     */
    private suspend fun onDevice(handed: Handed) {
        val outcome = handed.load.outcome()
        val millis = (System.nanoTime() - handed.since) / 1_000_000
        val after = CastStart.after(outcome, handed.launch, handed.sent, millis, canTryAnother(handed.launch))
        if (after is PlayState.Failed) throw EndedOnDevice(after)
        _state.value = after
    }

    /**
     * [castFile]'s play of [file] in the NZB [nzbId], as [request] names it, to [to]: Import fetched the NZB and opened it,
     * so the sheet starts at the file's tracks, which the probe reads and the device's verdict judges (L129); a type no
     * Cast device opens fails as a file type's, before anything is sent (L57). Returns the failure to show, or null once
     * the device plays the file, or this phone does at the pre-check's word. Once handed over, [onTheDevice] waits for the
     * device: a cast play's sheet's ([onDevice]), or a move's ([moveToCast]), whose file is judged by nothing ([judged]
     * false), as the player's other moves aren't.
     */
    private suspend fun castImported(
        nzbId: String, file: FileCandidate, request: PlayRequest, to: CastTo, judged: Boolean = true, onTheDevice: suspend (Handed) -> Unit = { onDevice(it) },
    ): PlayState.Failed? {
        val prepared = (container.store.getOrPrepare(nzbId, container.preparer) as? PrepareOutcome.Ready)?.nzb
        val bytes = container.store.stored(nzbId)?.first
        // The store let it go since Import opened it: nothing to play from.
        if (prepared == null || bytes == null) return PlayState.Failed(request.title, null, listOf(request.title to IMPORT_GONE), notTried = 0)
        val release = importedRelease(nzbId, file, request.title)
        val steps = mutableMapOf<PipelineStep, StepStatus>(PipelineStep.FetchNzb to StepStatus.Done(0), PipelineStep.Open to StepStatus.Done(0, file.name))
        var castLines: List<CastLine> = emptyList()
        fun running() = PlayState.Running(request.title, null, null, 1, 1, request.title, emptyList(), steps.toMap(), null, castLines = castLines)
        steps[PipelineStep.ProbeTracks] = StepStatus.Running()
        _state.value = running()
        val profile = container.castProfile(to)
        val prefs = castPrefs(request.watch)
        // Play on this phone, at the pre-check's word: the file plays here, from the store, nothing sent to the device.
        var here = false
        val probe = CastProbe(container.trackProbe, judge = { tracks -> if (judged) CastVerdict.miss(tracks, profile) else null }) ask@{ _, _, tracks, playsHere ->
            val precheck = CastPrecheck.of(
                CastVerdict.judge(tracks, profile, prefs), tracks, to.name, request.title, ReleaseParser.parse(request.title).audio, compatible = false, here = playsHere,
            ) ?: return@ask CastPrecheck.Button.CastAnyway
            askBeforeCasting(request, precheck).also { if (it == CastPrecheck.Button.PlayHere) here = true }
        }
        val since = System.nanoTime()
        val target = UsenetTarget(PlayCandidate(release.key, request.title), bytes, prepared, file, container.store)
        val result = probe.probe(target)
        val millis = (System.nanoTime() - since) / 1_000_000
        val failure = castProbeFailure(result, file.name)
        if (failure != null) {
            steps[PipelineStep.ProbeTracks] = StepStatus.Failed(millis, failure)
            val failed = PlayState.Failed(request.title, null, listOf(request.title to failure.text), notTried = 0)
            // No list to go back to for another: the file type's sheet offers this phone, and Close.
            return CastStart.refused(failed, listOf(failure), to, chosen = false) ?: failed
        }
        val playable = result as ProbeResult.Playable
        steps[PipelineStep.ProbeTracks] = StepStatus.Done(millis)
        val launch = launchInJob(request, release, target.uri, sessions.start(request, emptySet()), retry = null, playable.video, playable.tracks)
            .let { if (here) it.copy(cast = null) else it }
        castLines = if (launch.cast == null) emptyList() else CastPrecheck.startingLines(playable.tracks?.let { CastVerdict.judge(it, profile, prefs) }, to.name)
        if (launch.cast == null) {
            _state.ifCurrent {
                container.playerInFront.launched(null)
                _launches.trySend(launch)
            }
            _state.value = PlayState.Idle
            return null
        }
        handOver(launch, running())?.let { onTheDevice(it) }
        return null
    }

    /** [request]'s file, [file] of the NZB [nzbId], played here as Import plays it, its launch saving no progress. */
    private fun playFileHere(nzbId: String, file: FileCandidate, request: PlayRequest) {
        val launch = importedLaunch(nzbId, file, request, sessions.start(request, emptySet()))
        _castTo.value = null
        _state.ifCurrent {
            container.playerInFront.launched(null)
            _launches.trySend(launch)
        }
        _state.value = PlayState.Idle
    }

    /** Try again after the device couldn't reach this phone (W39): [launch] loaded again on its device, the sheet on its last step. */
    fun castAgain(launch: PlayerLaunch) {
        if (launch.cast == null) return
        val shown = (_state.value as? PlayState.Failed)?.cast?.takeIf { it.launch == launch }?.running
        val running = (shown ?: PlayState.Running(launch.title, launch.subtitle, null, 1, 1, launch.release.title, emptyList(), emptyMap(), null)).copy(castStep = null)
        val origin = PlayOrigin.after(launch)
        replaceJob(origin, launch.title, launch.subtitle, launch.watch)
        job = launchPlay(launch.title, launch.subtitle, origin = origin) { handOver(launch, running)?.let { onDevice(it) } }
    }

    /**
     * Play & connect (W45, L124 steps 4-6): the player's [launch], playing here, moves to the Cast device [to], its
     * session connected, to play on there from [positionMs]: the same prepared file loaded there ([CastStart.moving]),
     * nothing searched, as [castAgain] loads one again. The sheet takes it as a cast play's last step, "Starting on Kitchen
     * display", and goes back to Idle whatever comes of it: never a failure's sheet, as the player plays on here and says
     * why ([launchMove]). Its launch never goes to this phone's player. What comes of it is the player's.
     */
    fun moveToCast(launch: PlayerLaunch, positionMs: Long, to: CastTo): Deferred<MoveOutcome> {
        val moving = CastStart.moving(launch, positionMs, to)
        val origin = PlayOrigin.after(moving)
        replaceJob(origin, launch.title, launch.subtitle, launch.watch)
        val running = PlayState.Running(launch.title, launch.subtitle, null, 1, 1, launch.release.title, emptyList(), emptyMap(), null)
        return launchMove(launch.title, launch.subtitle, origin) { handOver(moving, running)?.let { CastStart.moveEnd(it.load.outcome()).outcome } }
    }

    /**
     * Play & connect for a file Import opened, which the player plays with no launch (L124, L147): [file] of the NZB [nzbId]
     * moved to [to] from [positionMs], through Import's cast ([castImported], as [castFile] plays it) with its launch made
     * for the file from that place. As a move: its tracks are judged by nothing, as the player's other moves aren't, and
     * whatever comes of it the sheet goes back to Idle and the player hears it, a failure before the hand-over as one after.
     */
    fun moveToCast(nzbId: String, file: FileCandidate, positionMs: Long, to: CastTo): Deferred<MoveOutcome> {
        val request = importRequest(container.store.get(nzbId)?.title ?: file.name)
        val origin = PlayOrigin(start = positionMs, cast = to)
        replaceJob(origin, request.title, request.subtitle, request.watch)
        return launchMove(request.title, request.subtitle, origin) {
            var moved: MoveOutcome? = null
            val failed = castImported(nzbId, file, request, to, judged = false) { handed -> moved = CastStart.moveEnd(handed.load.outcome()).outcome }
            failed?.let { MoveOutcome.Failed(it.cast?.failure ?: CastLoadFailure(CastFailKind.CouldNotPlay, it.failures.firstOrNull()?.second)) } ?: moved
        }
    }

    /**
     * What the Cast device [to] makes of the film the player plays here, before the film moves there (L124 steps 2 and 3,
     * item 44): its [tracks] (the launch's, as the probe read them, or what the player reads of an opened file's) judged by
     * the device's profile and what the user wants of them, as a cast's probe judges them. The pre-check's question where
     * it can't fully play them, Play a compatible release offered where [launch]'s request has another release the device
     * should play ([playCompatible]); else the language it will play. [release]: the release's name, the card's; [launch]
     * null for a file Import opened, whose question has no compatible release. Nothing judged without tracks.
     */
    suspend fun castFit(launch: PlayerLaunch?, tracks: ProbedTracks?, release: String, to: CastTo): CastMove.Fit {
        tracks ?: return CastMove.Fit.None
        val watch = launch?.watch ?: WatchTarget(WatchKind.Release, release)
        val verdict = CastVerdict.judge(tracks, container.castProfile(to), castPrefs(watch))
        return CastMove.Fit.of(verdict, tracks, to.name, release, compatible = launch != null && compatiblePlan(launch, null, to) != null)
    }

    /**
     * Play a compatible release, from the player's pre-check (L152, L124 step 2): [launch], playing here at [positionMs],
     * is left for its request's other releases the Cast device [to] should play, ranked for it, from that place, as an
     * automatic pick there ([PlaySessions.compatible]); the device's session is connected. The sheet says how it goes, as
     * for Try another release; with none left, that there is no other. Play on this phone, after a failure that sent
     * nothing there, brings back the film [launch] here from that place, as the player played it.
     */
    fun playCompatible(launch: PlayerLaunch, positionMs: Long, to: CastTo) = playCompatible(launch, positionMs, PlayOrigin(cast = to))

    private fun playCompatible(launch: PlayerLaunch, positionMs: Long, origin: PlayOrigin) {
        // Play on this phone, after none of them played fully there: the film the player was playing, which plays here.
        replaceJob(origin, launch.title, launch.subtitle, launch.watch) { again ->
            if (again.cast == null) playHere(launch.copy(startPositionMs = positionMs)) else playCompatible(launch, positionMs, again)
        }
        job = launchPlay(launch.title, launch.subtitle, origin = origin) {
            val plan = compatiblePlan(launch, positionMs, origin.cast)
            runPipeline(plan?.request ?: PlayRequest(launch.title, launch.subtitle, emptyList(), watch = launch.watch), plan?.retry)?.let { _state.value = it }
        }
    }

    /**
     * [launch]'s request again for Play a compatible release from [positionMs] ([PlaySessions.compatible]), its releases
     * ranked for the Cast device [to] by its profile, or for this device without one; null with none left.
     */
    private suspend fun compatiblePlan(launch: PlayerLaunch, positionMs: Long?, to: CastTo?): RetryPlan.Again? {
        val context = container.rankingContext(to?.let { CastRanking(it.name, container.castProfile(it)) })
        return sessions.compatible(launch.copy(cast = to), positionMs) { it.rankedAgain(context) } as? RetryPlan.Again
    }

    /**
     * A move ([moveToCast]) as the sheet's job, from [origin], the cast's: [block] hands the film to its device and says
     * how it came out there, null where another play took the sheet first. Whatever came of it, the sheet goes back to
     * Idle while it is still the move's, never a failure's sheet ([CastStart.moveEnd]); a failure first stops what was
     * started for the device, then chooses This phone, which ends the session (L85), in that order, so the session's end
     * isn't taken for a cast play's still starting, which would cancel this job. A service Android wouldn't start fails it
     * at once, and so does the job's own failure, in the words the sheet would have said. Cancel or another play: Stopped.
     */
    private fun launchMove(title: String, subtitle: String?, origin: PlayOrigin, block: suspend () -> MoveOutcome?): Deferred<MoveOutcome> {
        val refused = notStarted.also { notStarted = null }
        val outcome = CompletableDeferred<MoveOutcome>()
        job = scope.launch(PLAY + origin + _state.writer()) {
            _starting.tryEmit(Unit)
            var came: MoveOutcome? = refused?.cast?.failure?.let(MoveOutcome::Failed)
            if (came == null) {
                failureOf(title, subtitle) { came = block() }?.let { failed ->
                    came = MoveOutcome.Failed(CastLoadFailure(CastFailKind.CouldNotPlay, failed.failures.firstOrNull()?.second))
                }
            }
            val end = came ?: MoveOutcome.Stopped
            _state.ifCurrent {
                if (end is MoveOutcome.Failed) {
                    container.castLauncher()?.stop()
                    container.playOn.choose(PlayChoice.Phone)
                }
            }
            _state.value = PlayState.Idle
            outcome.complete(end)
        }.apply { invokeOnCompletion { outcome.complete(MoveOutcome.Stopped) } }
        return outcome
    }

    /**
     * Play on this phone after a cast play failed (L83): This phone is chosen, which ends the cast (L85), and [launch], made
     * for the device, plays here, its NZB in the store; with none, nothing was made for the device, and the play is asked
     * for again here.
     */
    fun playHere(launch: PlayerLaunch?) {
        container.playOn.choose(PlayChoice.Phone)
        if (launch == null) {
            again?.invoke(PlayOrigin.Here)
            return
        }
        val here = launch.copy(cast = null)
        _castTo.value = null
        _state.ifCurrent {
            container.playerInFront.launched(here.remote)
            _launches.trySend(here)
        }
        _state.value = PlayState.Idle
    }

    /**
     * What a cast failure's button asks for ([CastStart.press]). Nothing more goes to a device no longer chosen and
     * connected (W36), for a button pressed as it went: its sheet closes.
     */
    fun act(action: CastAction) {
        when (action) {
            is CastAction.TryAnother -> if (stillCasts(action.launch)) tryAnother(action.launch, action.positionMs, action.forget) else dismiss()
            is CastAction.CastAgain -> if (stillCasts(action.launch)) castAgain(action.launch) else dismiss()
            is CastAction.PlayHere -> playHere(action.launch)
            is CastAction.CastAnyway -> if (CastStart.live(action.device, container.playOn.target.value)) play(action.request, PlayOrigin(cast = action.device)) else dismiss()
            CastAction.BackToList, CastAction.Close -> dismiss()
        }
    }

    /** Whether [launch]'s Cast device is still where Play goes, connected (W36). */
    private fun stillCasts(launch: PlayerLaunch): Boolean = launch.cast?.let { CastStart.live(it, container.playOn.target.value) } == true

    /** The question the play waits on ([PlayState.ChoosingFile]): its answer, and the files it may be. */
    @Volatile
    private var choosing: Pair<CompletableDeferred<ListedFile?>, List<ListedFile>>? = null

    /** The pre-check's question the play waits on ([PlayState.Prechecking]): its answer. */
    @Volatile
    private var prechecking: CompletableDeferred<CastPrecheck.Button>? = null

    /**
     * Asks [precheck] of [request]'s release, picked by hand, which its Cast device can't fully play (L130): the sheet says
     * it, opened if it was minimised, as the play waits on the answer ([precheck] below), with nothing sent to the device.
     * Cancel, Back or another play cancels the play, and with it the question.
     */
    private suspend fun askBeforeCasting(request: PlayRequest, precheck: CastPrecheck): CastPrecheck.Button {
        val answer = CompletableDeferred<CastPrecheck.Button>()
        prechecking = answer
        try {
            _state.value = PlayState.Prechecking(request.title, request.subtitle, precheck)
            _expanded.value = true
            return answer.await()
        } finally {
            if (prechecking === answer) prechecking = null
        }
    }

    /**
     * The pre-check's [button], as its sheet answers it ([PlayState.Prechecking]): Cancel stops the play. Play on this
     * phone chooses This phone, which ends the cast (L85); what was started for the device stops first, so the session's
     * end isn't taken for a cast play's and doesn't cancel this one, which goes on to play the file here. The others go on
     * as the cast's probe takes them ([CastProbe]).
     */
    fun precheck(button: CastPrecheck.Button) {
        val answer = prechecking ?: return
        when (button) {
            CastPrecheck.Button.Cancel -> return cancel()
            CastPrecheck.Button.PlayHere -> {
                container.castLauncher()?.stop()
                container.playOn.choose(PlayChoice.Phone)
                _castTo.value = null
            }
            CastPrecheck.Button.Compatible, CastPrecheck.Button.CastAnyway -> Unit
        }
        answer.complete(button)
    }

    /**
     * Asks which of [choices], the videos of [candidate]'s release, is the file [request] plays: the sheet lists them
     * ([fileChoice]), opened if it was minimised, as the play waits on the answer ([chooseFile]), or null where none is
     * ([notInRelease]): the attempt then fails as finding no file, and the play goes on to its next release, or from the
     * release that was playing to a search. Cancel, Back or another play cancels the play, and with it the question.
     */
    private suspend fun askForFile(request: PlayRequest, candidate: PlayCandidate, choices: List<ListedFile>): ListedFile? {
        val answer = CompletableDeferred<ListedFile?>()
        choosing = answer to choices
        try {
            _state.value = fileChoice(request, candidate.title, choices)
            _expanded.value = true
            return answer.await()
        } finally {
            if (choosing?.first === answer) choosing = null
        }
    }

    /** The file at [index] of the release ([FileChoice.index]) is the one to play, as the play sheet's question asked. */
    fun chooseFile(index: Int) {
        val (answer, choices) = choosing ?: return
        choices.firstOrNull { it.index == index }?.let(answer::complete)
    }

    /** None of the release's videos is the one to play, as the play sheet's question is answered: the play goes on without it. */
    fun notInRelease() {
        choosing?.first?.complete(null)
    }

    private val prefetched = io.skjaere.usenet.search.EpisodeFindHandoff()
    private var prefetchJob: Job? = null
    /** The episode ([EpisodePlay.key]) [prefetchJob] searches for. */
    private var prefetchKey: String? = null
    /** The episode ([EpisodePlay.key]) [prefetchEpisode] found in the release playing, which plays it without a search. */
    private var foundInPlaying: String? = null

    /**
     * Searches for [ep]'s release in the background (auto-advance starts this before the current
     * episode ends); the next [playEpisode] uses the result if it's for the same episode and
     * settings, found something playable and no indexer failed, and otherwise searches again. In
     * the release [playing] plays, a season pack that holds [ep], it is found at once, with no
     * search: the play takes it from there ([PlayingPack]), asking which file it is where the pack
     * names none for it.
     */
    fun prefetchEpisode(ep: EpisodePlay, playing: SavedRelease? = null): StateFlow<Prefetch> {
        prefetchJob?.cancel()
        prefetchKey = ep.key
        val found = playing?.let { fileInPlaying(ep, it) }
        foundInPlaying = ep.key.takeIf { found != null }
        // A pack named per episode that plainly lacks this one isn't what the search finds for it.
        val lacking = playing?.takeIf { found == null && lacksIt(ep, it) }?.key
        if (playing != null && found != null) {
            val ready = MutableStateFlow<Prefetch>(Prefetch.Ready(playing.title, found.file?.size ?: 0, choose = found.file == null))
            // A file chosen for it from this release before plays without the question: the card says so once that is read.
            prefetchJob = if (found.file != null) null else scope.launch {
                val before = try {
                    container.library.lastRelease(ep.key)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                before?.let { fileInPlaying(ep, playing, it)?.file }?.let { ready.value = Prefetch.Ready(playing.title, it.size) }
            }
            return ready.asStateFlow()
        }
        val status = MutableStateFlow<Prefetch>(Prefetch.Searching)
        // Ranked for this phone, whose player plays it next, whatever Play on chooses meanwhile (L127).
        prefetchJob = scope.launch(PlayOrigin.Here) {
            val r = try {
                val search = episodeSearch(ep)
                findEpisode(search) {}?.also { prefetched.offer(search, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            status.value = prefetchOf(r, setOfNotNull(lacking))
        }
        return status.asStateFlow()
    }

    /**
     * Whether what [prefetchEpisode] found for [ep] is held for [playEpisode], and still will be
     * [forMs] from now: it is handed on for ten minutes, and after that the play searches again.
     * What it found in the release playing is that release, which needs no handing on.
     */
    fun holdsPrefetched(ep: EpisodePlay, forMs: Long = 0): Boolean = foundInPlaying == ep.key || prefetched.holds(ep.request, forMs)

    /**
     * The files of [playing]'s release as this process holds them: its NZB as prepared in the store, or a torrent's listing
     * as its service gave it, kept (L181); null when it holds neither.
     */
    private fun playingListing(playing: SavedRelease): FileListing<*>? =
        if (playing.source == null) {
            container.store.get(playing.nzbId)
        } else {
            InfoHash.parse(playing.nzbId.removePrefix(ReleaseKey.TORRENT))?.let { hash ->
                container.debridListings.kept(hash)?.let { ServiceFiles(hash, it.title, it.prepared.listing) }
            }
        }

    /** Whether a torrent's listing of [release] is kept, which a play of it again prepares from its service. */
    private suspend fun kept(release: RememberedRelease): Boolean =
        release.source != null && InfoHash.parse(release.nzbId?.removePrefix(ReleaseKey.TORRENT))?.let { container.debridListings.find(it) } != null

    /** What [playing]'s release, as this process holds it ([playingListing]), holds of [ep] ([PlayingPack.find]), [picked] for it before or not; null when nothing. */
    private fun fileInPlaying(ep: EpisodePlay, playing: SavedRelease, picked: RememberedRelease? = null): PlayingPack.InPack? =
        playingListing(playing)?.let { PlayingPack.find(it, playing, ep.season, ep.episode, picked) }

    /** Whether [playing]'s release, as this process holds it ([playingListing]), plainly lacks [ep] ([PlayingPack.lacks]). */
    private fun lacksIt(ep: EpisodePlay, playing: SavedRelease): Boolean =
        playingListing(playing)?.let { PlayingPack.lacks(it, playing, ep.season, ep.episode) } == true

    /**
     * [ep] played from [playing]'s release ([PlayingPack]): from its NZB as prepared in the store, which is on disk too
     * (else it would be grabbed), or from a torrent's listing its service gave, kept, which its service prepares again
     * (L181); and the file of it for [ep], the one played for [ep] from it before if any, else the one the user chooses
     * where it names none for [ep]. Null when it holds no such file.
     */
    private suspend fun fromPlaying(ep: EpisodePlay, playing: SavedRelease): PlayingPack.Play? {
        val listing = playingListing(playing) ?: return null
        val found = PlayingPack.find(listing, playing, ep.season, ep.episode, picked = container.library.lastRelease(ep.key)) ?: return null
        if (listing is PreparedNzb) container.store.stored(listing.id) ?: return null
        return PlayingPack.play(ep, playing, listing, found.file)
    }

    /** Read fresh for every search, so changed indexers, keys or preferences apply to the next one. */
    private suspend fun episodeSearch(ep: EpisodePlay): io.skjaere.usenet.search.EpisodeSearch {
        val remembered = container.library.lastRelease(ep.key)?.let { io.skjaere.usenet.search.RememberedPick(it.indexerId, it.guid, it.title, it.downloadTemplate, Replay.key(it)) }
        return io.skjaere.usenet.search.EpisodeSearch(ep.request, container.indexerConfigs(), container.rankingContext(), remembered)
    }

    /**
     * The finder's last word for [search]: Play's own pick shows no list, and can't take one back, so it waits for torrents
     * still checked when every indexer has answered, 10 s at most (L199), and takes the list they joined.
     */
    private suspend fun findEpisode(search: io.skjaere.usenet.search.EpisodeSearch, onEvent: (io.skjaere.usenet.search.FinderEvent) -> Unit): io.skjaere.usenet.search.FindResult? {
        var result: io.skjaere.usenet.search.FindResult? = null
        container.finder.find(search.request, search.indexers, search.context, search.remembered).collect { event ->
            onEvent(event)
            when (event) {
                is io.skjaere.usenet.search.FinderEvent.Finished -> result = event.result
                is io.skjaere.usenet.search.FinderEvent.Joined -> result = event.result
                else -> Unit
            }
        }
        return result
    }

    /**
     * Finds a release for [ep] (by the show's ids, with the season's packs, then by name) and plays
     * the best one; the release that played this episode before goes first. From the player, the
     * episode either side of the one playing ([playing]: the release it plays) plays from that
     * release when it is a season pack that holds it, with no search ([PlayingPack]). [origin]: a paired phone's play
     * (L33), which the TV starts as from its own Show page, or the one the player hands on in its session.
     */
    fun playEpisode(ep: EpisodePlay, playing: SavedRelease? = null, origin: PlayOrigin = PlayOrigin.Here) {
        replaceJob(origin, ep.showName, ep.subtitle, ep.watch) { playEpisode(ep, playing, it) }
        val inPlaying = playing?.let { fileInPlaying(ep, it) } != null
        // A pack named per episode that plainly lacks this one would be grabbed and prepared again for nothing: the search leaves it out.
        val lacking = playing?.takeIf { !inPlaying && lacksIt(ep, it) }?.key
        _state.value = PlayState.Searching(ep.showName, ep.subtitle, if (inPlaying) "${PlayingPack.SEARCH_LINE}…" else "Searching your indexers…")
        // A search started ahead for this episode is waited for and used; one for another episode
        // would only hold this one up, for as long as a slow indexer takes.
        val prefetch = prefetchJob?.takeIf { prefetchKey == ep.key }
        if (prefetch == null) prefetchJob?.cancel()
        job = launchPlay(ep.showName, ep.subtitle, origin = origin) {
            PlayingPack.run(
                playing?.let { fromPlaying(ep, it) },
                start = { runPipeline(it.request, prepared = it.prepared) },
                search = { retry ->
                    prefetch?.join()
                    findAndPlay(ep, retry)
                },
                lacking = lacking,
            )
        }
    }

    /** Searches for [ep] and plays the best release; a [retry] leaves out the releases that just failed to play it. */
    private suspend fun findAndPlay(ep: EpisodePlay, retry: Retry?) {
        val title = ep.showName
        val search = episodeSearch(ep)
        val indexers = search.indexers
        val progress = SearchProgress(indexers.count { it.enabled })
        val result = prefetched.take(search) ?: findEpisode(search) { event ->
            progress.after(event)?.let { _state.value = PlayState.Searching(title, ep.subtitle, it) }
        }
        val r = result?.let { found ->
            found.copy(playable = found.playable.filter { it.key !in retry?.failed.orEmpty() && retry?.stops(io.skjaere.usenet.search.RankedItem(it.item, it.ranked)) != true })
        } ?: return
        if (r.playable.isEmpty() && retry?.left != null) {
            // Nothing else to try: back to the release that was playing.
            val back = PlayRequest(title, ep.subtitle, emptyList(), io.skjaere.usenet.core.FilePicker.Episode(ep.season, ep.episode), ep.watch, episodeName = ep.episodeName)
            runPipeline(back, retry)?.let { _state.value = it }
            return
        }
        if (r.playable.isEmpty()) {
            prefetched.clear()
            _state.value = noRelease(ep, r)
            return
        }
        val by = r.stages.firstOrNull()?.indexers?.mapNotNull { it.by }?.distinct()?.joinToString(" / ") ?: "id"
        val request = PlayRequest(
            title = title,
            subtitle = ep.subtitle,
            candidates = r.playable.map { io.skjaere.usenet.search.RankedItem(it.item, it.ranked) },
            picker = io.skjaere.usenet.core.FilePicker.Episode(ep.season, ep.episode),
            watch = ep.watch,
            searchLine = "Searched ${indexers.count { it.enabled }} indexer${if (indexers.count { it.enabled } == 1) "" else "s"} by $by · ${r.playable.size + r.hidden.size} found",
            // The remembered release just failed: it isn't put back ahead of the others. Where the
            // release that was playing failed instead, the one remembered for the episode goes first.
            rememberedFirst = retry == null || retry.fromPlaying,
            episodeName = ep.episodeName,
            hidden = r.hidden.map { it.key }.toSet(),
        )
        runPipeline(request, retry)?.let { _state.value = it }
    }

    fun cancel() {
        // A cast play on its way: what it loaded stops on the device, the session lives on (L82). Here, once: the job's own
        // end is dropped, as the sheet is no longer its play's when it is cancelled.
        val casting = _state.origin.cast != null && job?.isActive == true
        _state.end()
        job?.cancel()
        if (casting) container.castLauncher()?.stop()
        container.playerInFront.notLaunching()
        _state.value = PlayState.Idle
        container.displayHold.clear("the play was cancelled")
    }

    /**
     * The user went back to the play in the picture-in-picture window while the one the sheet started was on its way (L9):
     * that one stops as Cancel stops it, unless it is over already (a failure keeps its sheet), and a launch it sent that
     * no screen has taken yet, waiting while the app's screens are hidden, is dropped, not to take the player over later.
     */
    fun withdraw() {
        val now = _state.value
        if (now !is PlayState.Idle && now !is PlayState.Failed && now !is PlayState.NoRelease) cancel()
        while (_launches.tryReceive().isSuccess) log.info("A launch no screen took dropped: the user went back to the play in the window")
        container.playerInFront.notLaunching()
    }

    fun dismiss() {
        if (_state.value is PlayState.Failed || _state.value is PlayState.NoRelease) _state.value = PlayState.Idle
    }

    companion object {
        /** Test seam: an indexer grab of a release's NZB, which a test can count. */
        @VisibleForTesting
        @Volatile
        internal var fetchNzb: suspend (AppContainer, IndexerConfig, NewznabItem) -> NzbDownload = { c, indexer, item -> c.newznab.download(indexer, item) }

        /** Test seam: where the pipeline gets a candidate's NZB, given the indexer grab it uses by default; a test can serve its own. */
        @VisibleForTesting
        @Volatile
        internal var nzbSource: (AppContainer, NzbSource) -> NzbSource = { _, grab -> grab }
    }
}
