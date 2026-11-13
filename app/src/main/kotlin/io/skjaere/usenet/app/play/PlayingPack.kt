package io.skjaere.usenet.app.play

import io.skjaere.nntp.NntpDispatchers
import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.PrepareResult
import io.skjaere.usenet.core.FileListing
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.ListedFile
import io.skjaere.usenet.core.NzbPreparer
import io.skjaere.usenet.core.PrepareBackend
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.data.RememberedRelease
import kotlinx.coroutines.CoroutineDispatcher

/**
 * The episode either side of the one playing (auto-advance's card, the controls' Next and Previous), played from the
 * release playing when that holds a file of it: a season pack prepared for this play. No search, no grab and no
 * prepare: its NZB is the stored one, prepared as it was for the play; a torrent's, the listing its service gave, kept
 * (L181), which its service's Preparing confirms. Its file is checked and probed as any play's is, and where that fails
 * the episode is searched for as before, without the pack. Where the pack names none of its
 * videos for the episode, the play asks which it is, unless one was chosen for it before; answered that none is, it is
 * searched for, without the pack. An episode the release doesn't hold is searched for as before, without the release
 * where its files' names say so ([lacks]).
 */
internal object PlayingPack {
    /** [request] plays the episode from the release playing: from [prepared], its NZB, for Usenet's; null for a torrent's. */
    class Play(val request: PlayRequest, val prepared: PreparedNzb?) {
        /** The release's key, as a search result's ([io.skjaere.usenet.releases.ReleaseKey]). */
        val key: String get() = request.candidates.single().key
    }

    /**
     * The file of [listing], the files of [playing]'s release, that plays [season]x[episode]: the one played for it from
     * this release before ([picked], the release remembered for the episode, #179), else the one whose name says it is the
     * episode, as [FilePicker.Episode] picks it. Only in a release of several videos (a season pack): a release of one is
     * the one episode, whatever its file is named. Not the file playing (one of two episodes). Null when there is none.
     */
    fun fileFor(listing: FileListing<*>, playing: SavedRelease, season: Int, episode: Int, picked: RememberedRelease? = null): ListedFile? {
        if (listing.oneVideo) return null
        val current = listing.find(playing.fileIndex, playing.fileName, playing.fileSize)
        val before = picked?.takeIf { it.nzbId == listing.id }?.let { listing.find(it.fileIndex, it.fileName, it.fileSize) }?.takeIf { it.isVideo }
        return (before ?: FilePicker.Episode(season, episode).pick(listing))?.takeIf { it != current }
    }

    /** What the release playing holds of an episode: its [file], or, null, a video the user is to choose as it. */
    data class InPack(val file: ListedFile?)

    /**
     * Where [listing], the files of [playing]'s release, holds [season]x[episode]: its file ([fileFor]), else, where its name
     * holds the season and none of its videos is named for an episode it could be, a question of which it is
     * ([FilePicker.Episode.choices]); null when it holds no such file.
     */
    fun find(listing: FileListing<*>, playing: SavedRelease, season: Int, episode: Int, picked: RememberedRelease? = null): InPack? =
        fileFor(listing, playing, season, episode, picked)?.let(::InPack)
            ?: InPack(null).takeIf { FilePicker.Episode(season, episode).choices(listing).isNotEmpty() }

    /**
     * Whether [listing], the files of [playing]'s release, plainly lacks [season]x[episode]: it holds several videos, and
     * their names say which episodes they are, none this one ([find] finds no file, nor asks). A search for the episode
     * leaves it out, which would otherwise grab and prepare it again only to find no file for it.
     */
    fun lacks(listing: FileListing<*>, playing: SavedRelease, season: Int, episode: Int): Boolean =
        !listing.oneVideo && find(listing, playing, season, episode) == null

    /**
     * Plays [ep] from [playing]'s [file] in [listing], alone: the release is the one to play the episode, with that file
     * ([PlayRequest.remembered]), so its stored NZB is used ([ReplaySource]), or a torrent is played from its service
     * again, and that file picked ([Replay.picker]), an NZB's volumes checked once it is. Without a [file], the play asks
     * which of the release's videos is the episode.
     */
    fun play(ep: EpisodePlay, playing: SavedRelease, listing: FileListing<*>, file: ListedFile?): Play {
        val release = RememberedRelease(
            playing.indexerId, playing.guid, playing.title, playing.downloadTemplate, file?.index ?: NO_FILE, listing.id, file?.name, file?.size, playing.source,
        )
        val request = PlayRequest(
            title = ep.showName,
            subtitle = ep.subtitle,
            candidates = listOf(Replay.candidate(release)),
            picker = FilePicker.Episode(ep.season, ep.episode),
            watch = ep.watch,
            searchLine = SEARCH_LINE,
            remembered = release,
            episodeName = ep.episodeName,
        )
        return Play(request, listing as? PreparedNzb)
    }

    /**
     * A preparer that hands back [held], as it was prepared for the play, for its own NZB rather than prepare it again;
     * [backend] prepares any other.
     */
    fun preparer(held: PreparedNzb, backend: PrepareBackend, dispatcher: CoroutineDispatcher = NntpDispatchers.IO): NzbPreparer =
        NzbPreparer(
            object : PrepareBackend {
                override suspend fun prepare(nzbBytes: ByteArray, password: String?): PrepareResult =
                    if (PreparedNzb.idFor(nzbBytes) == held.id) PrepareResult.Success(held.metadata) else backend.prepare(nzbBytes, password)

                override suspend fun streamableFiles(metadata: ExtractedMetadata) =
                    if (metadata === held.metadata) held.candidates.map { it.file } else backend.streamableFiles(metadata)
            },
            dispatcher,
        )

    /**
     * Plays [play] with [start] (null once the player starts, else the failure), when there is one; else, or once it
     * fails, [search]es for the episode, leaving the release out. With no play, a release playing that plainly lacks the
     * episode ([lacking], its key: [lacks]) is left out too, with nothing said of it.
     */
    suspend fun run(play: Play?, start: suspend (Play) -> PlayState.Failed?, search: suspend (Retry?) -> Unit, lacking: String? = null) {
        if (play == null) {
            return search(lacking?.let { Retry(setOf(it), startPositionMs = null, midStream = false, fromPlaying = true, playingLacksIt = true) })
        }
        start(play) ?: return
        search(Retry(setOf(play.key), startPositionMs = null, midStream = false, fromPlaying = true))
    }

    /** What the play sheet says above the steps, as a search's line. */
    const val SEARCH_LINE = "From the release that was playing"

    /** The place of no file in an NZB ([RememberedRelease.fileIndex]): the release is remembered without one. */
    private const val NO_FILE = -1
}
