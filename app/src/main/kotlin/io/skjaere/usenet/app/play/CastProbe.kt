package io.skjaere.usenet.app.play

import io.skjaere.usenet.app.casting.CastPrecheck
import io.skjaere.usenet.core.ContainerTypes
import io.skjaere.usenet.core.PlaybackTarget
import io.skjaere.usenet.core.ProbeResult
import io.skjaere.usenet.core.ProbedTracks
import io.skjaere.usenet.core.TrackProbe

/**
 * A cast's probe (L57, L129, L130): a file of a type a Cast device can't play is refused by its name before [inner], this
 * phone's probe, reads a byte of it, so an automatic pick goes on to the next release as it does past an incomplete one,
 * and a release chosen by hand fails before anything is sent to the device. Any other file is read by [inner] and judged
 * by the device's verdict on its tracks ([judge], CastVerdict.miss with the device's profile): what it can't play fails
 * the attempt as such. This phone's own verdict doesn't apply, as its decoders are no Cast device's either way; only a
 * file [inner] read no tracks of keeps it, as nothing else can be judged. Cast anyway judges nothing (its [judge] finds
 * nothing), but the type is still refused, as no Cast device opens it. Nor is the release still playing on the device
 * judged (its candidate marked wasPlaying, the way back after the others failed, W3): it plays there already, Cast
 * anyway's too, and going back to it sends nothing.
 *
 * A release picked by hand asks first ([ask], the pre-check), told whether this phone's own probe plays the file, the
 * attempt waiting on the answer, as Choose file's does: Cast anyway passes the file all the same; Play on this phone has
 * this phone's own verdict on it, as a play here would; Play a compatible release fails it as the verdict says, the play
 * going on with other releases. Cancel stops the play, the question with it.
 */
class CastProbe(
    private val inner: TrackProbe,
    private val judge: (ProbedTracks) -> ProbeResult.CastMiss?,
    private val ask: (suspend (PlaybackTarget, ProbeResult.CastMiss, ProbedTracks, Boolean) -> CastPrecheck.Button)? = null,
) : TrackProbe {
    override suspend fun probe(target: PlaybackTarget): ProbeResult {
        val name = target.file.name
        if (!ContainerTypes.castable(name)) return ProbeResult.NotForCast(ContainerTypes.extension(name))
        val result = inner.probe(target)
        val (passing, tracks) = when (result) {
            is ProbeResult.Playable -> result to (result.tracks ?: return result)
            is ProbeResult.NoPlayableAudio -> (result.tracks ?: return result).let { ProbeResult.Playable(result.video, it) to it }
            is ProbeResult.NoPlayableVideo -> (result.tracks ?: return result).let { ProbeResult.Playable(result.video, it) to it }
            else -> return result
        }
        if (target.candidate.wasPlaying) return passing
        val miss = judge(tracks) ?: return passing
        return when (ask?.invoke(target, miss, tracks, result is ProbeResult.Playable)) {
            CastPrecheck.Button.CastAnyway -> passing
            CastPrecheck.Button.PlayHere -> result
            CastPrecheck.Button.Compatible, CastPrecheck.Button.Cancel, null -> miss
        }
    }
}
