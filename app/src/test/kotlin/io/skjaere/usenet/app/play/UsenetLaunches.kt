package io.skjaere.usenet.app.play

import io.skjaere.usenet.core.ProbedTracks
import io.skjaere.usenet.core.ProbedVideo
import io.skjaere.usenet.core.UsenetUris

/**
 * [launchOf] for a Usenet play of [release]: its URI the one [io.skjaere.usenet.core.UsenetTarget] gives the release's
 * file in its NZB ([usenetUri]), as the pipeline's target hands it on. For the tests of what a launch keeps besides.
 */
internal fun usenetLaunch(
    request: PlayRequest, release: SavedRelease, requestId: String, retry: Retry?, video: ProbedVideo? = null, tracks: ProbedTracks? = null,
    origin: PlayOrigin = PlayOrigin.Here,
): PlayerLaunch = launchOf(request, release, usenetUri(release), requestId, retry, video, tracks, origin)

/** [launchInJob] for a Usenet play of [release], as [usenetLaunch]. */
internal suspend fun usenetLaunchInJob(
    request: PlayRequest, release: SavedRelease, requestId: String, retry: Retry?, video: ProbedVideo? = null, tracks: ProbedTracks? = null,
): PlayerLaunch = launchInJob(request, release, usenetUri(release), requestId, retry, video, tracks)

/** The URI a Usenet target gives [release]'s file: the NZB's id and the file's place, name and size ([UsenetUris.of]). */
internal fun usenetUri(release: SavedRelease): String = UsenetUris.build(release.nzbId, release.fileIndex, release.fileName, release.fileSize)
