package io.skjaere.usenet.app.play

import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.FileListing
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.ListedFile
import io.skjaere.usenet.core.VideoFileFilter
import io.skjaere.usenet.data.RememberedRelease

/**
 * One video of the release, as the file chooser lists it: [index] is its place in the release's files ([ListedFile.index]).
 * [named]: the episode looked for ("S01E01"), when its name says it is that one.
 */
data class FileChoice(val index: Int, val name: String, val sizeBytes: Long, val sample: Boolean, val named: String? = null)

/**
 * Choose release's "Choose file": the user picks which of a release's videos plays ([choices]: all of them), whatever
 * their names say; a release of one plays it. What plays is checked once it is picked, on its own volumes. [wanted] is
 * what the release was chosen for (the episode), which says why nothing plays from a release without the file.
 */
internal data class ChooseFile(val wanted: FilePicker) : FilePicker {
    override fun <F : ListedFile> pick(listing: FileListing<F>): F? = listing.defaultPick?.takeIf { listing.oneVideo }
    override fun <F : ListedFile> choices(listing: FileListing<F>): List<F> = listing.files.filter { it.isVideo }.takeUnless { listing.oneVideo }.orEmpty()
    override val noMatch: AttemptFailure get() = wanted.noMatch
    override fun noMatchIn(listing: FileListing<*>): AttemptFailure = if (listing.files.none { it.isVideo }) AttemptFailure.NoVideo else wanted.noMatchIn(listing)
    override fun takesWhole(title: String): Boolean = false
}

/**
 * What the file chooser says it looks for, the play's episode: "Looking for S02E01 'Brine'", with TMDB's name for it
 * when the request has one; from Search, a season pack's Play looks for the episode its picker picks.
 */
internal fun lookingFor(request: PlayRequest): String {
    val episode = request.watch.episodeTarget?.label
        ?: (request.picker as? FilePicker.Episode)?.let { EpisodeTarget.label(it.season, it.episode) }
        ?: return "Choose the file to play"
    return "Looking for $episode" + request.episodeName?.let { " '$it'" }.orEmpty()
}

/**
 * The question for [request] of which of [choices], the videos of the release named [release], is the one to play. Where
 * the user asked to choose ([ChooseFile]), one may be named for the episode: the sheet marks it, and doesn't say none is.
 */
internal fun fileChoice(request: PlayRequest, release: String, choices: List<ListedFile>): PlayState.ChoosingFile {
    val asked = request.picker is ChooseFile
    val episode = ((request.picker as? ChooseFile)?.wanted ?: request.picker) as? FilePicker.Episode
    val label = episode?.let { EpisodeTarget.label(it.season, it.episode) }
    return PlayState.ChoosingFile(
        request.title, request.subtitle, lookingFor(request), release,
        choices.sortedWith(compareBy<ListedFile> { VideoFileFilter.isSample(it.path) }.thenByDescending { it.size })
            .map { FileChoice(it.index, it.name, it.size, VideoFileFilter.isSample(it.path), label.takeIf { _ -> episode?.wants(it.name) == true }) },
        prompt = if (asked) label?.let { "Choose the file to play as $it:" } ?: "Choose the file to play:" else PlayState.ChoosingFile.NONE_NAMED,
        asked = asked,
    )
}

/**
 * How [request] picks the file it plays: in [remembered]'s NZB the file that played before ([Replay.picker]), unless the user
 * asked to choose it ([ChooseFile]).
 */
internal fun playPicker(request: PlayRequest, remembered: RememberedRelease?): FilePicker =
    request.picker as? ChooseFile ?: Replay.picker(request.picker, remembered, request.watch)
