package io.skjaere.usenet.app.casting

import io.skjaere.usenet.serve.ServeCounters
import java.util.Locale

/**
 * The file a cast play's load serves, as a refusal on the device is read (L102): its [extension], whether the probe found
 * [video] in it, and whether the phone reads it from a debrid service ([fromService], L220) rather than from providers.
 */
data class CastFile(val extension: String, val video: Boolean, val fromService: Boolean = false) {
    /**
     * A file the device may refuse as it opens it, though its type is castable (L102): MOV and M4V, read through the MP4
     * demuxer's QuickTime side and tried only with H.264 and AAC, and a file with no video, which the Shield refused for
     * its DTS or TrueHD always and for its AC-3 or E-AC-3 in some sessions.
     */
    val borderline: Boolean get() = !video || extension in BORDERLINE

    /**
     * An MP4, M4V or MOV, whose index (its moov) may sit at the file's end: the device then reads it there before it can
     * open the file, so a read there is the head's as much as the one from byte 0 is.
     */
    val indexMayTrail: Boolean get() = extension in MP4_FAMILY

    private companion object {
        val BORDERLINE = setOf("mov", "m4v")
        val MP4_FAMILY = setOf("mp4", "m4v", "mov")
    }
}

// What the phone says of a cast play that failed on its device (L60, L88, L102; CsPhoneStarting), from the receiver's
// detailed code and what the phone's server saw of the device's requests. Words not drawn are L97's proposals.

const val CAST_DECODING_ERROR = "The device's player stopped with a decoding error"
const val CAST_CANT_OPEN = "The device's player can't open this file"
const val CAST_PROVIDERS = "Couldn't read the release from your providers"

/** [CAST_PROVIDERS] for a release the phone reads from a debrid service (L220): words that name no service. Words not drawn. */
const val CAST_SERVICE = "Couldn't read the release from your debrid service"
const val CAST_CLOSED_WITHOUT_ERROR = "The device's player closed the file without an error"

fun stoppedWithError(code: Int) = "The device's player stopped (error $code)"

/** A file the device refused as it opened it (L102): its card, which never calls a castable type unplayable. */
fun refusedAsItOpened(file: CastFile, name: String): String {
    val what = if (!file.video) "Audio-only file" else "${file.extension.uppercase(Locale.ROOT)} file"
    return "$what. $name refused it as it opened."
}

/** The Cast session ended before the device played the release (L60). */
fun closedBeforeItPlayed(name: String) = "$name closed before it played"

/** Another sender's load took the device's place before it played this one: not this release's fault. Words not drawn. */
fun somethingElseOn(name: String) = "Something else started playing on $name"

/** The kinds of failure each of the receiver's codes reads as (L88), and what the server saw. */
object CastOutcomes {
    /** The receiver's codes: a decoding error, a file its player can't open, a network error, a failed load. */
    private const val MEDIA_DECODE = 102
    private const val MEDIA_NETWORK = 103
    private const val MEDIA_SRC_NOT_SUPPORTED = 104
    private const val NETWORK_UNKNOWN = 300
    private const val LOAD_FAILED = 905

    /**
     * The device's player failed before it played the release, by its detailed [code] (null: it gave none, read as a media
     * session that vanished), what the server [served] it, on the device [name], of [file]. 104 and 905 alike: the device
     * couldn't open what it fetched, or, with no request at all, couldn't fetch it; but where the providers failed the
     * head as it was served (the answer from byte 0, or any of an MP4's, whose index may be at the end), the device only
     * says so as best it can (anything cut before the file's head is read is a 104, L76, L101), and the release is at
     * fault, not the device's formats. A later read of a Matroska file that failed (the tail's Cues, say) leaves the head
     * read whole: the refusal is still the device's.
     */
    fun error(code: Int?, served: ServeCounters, name: String, file: CastFile): CastLoadFailure = when (code) {
        null -> vanished(served, name, file)
        MEDIA_DECODE -> CastLoadFailure(CastFailKind.CouldNotPlay, CAST_DECODING_ERROR, format = true)
        MEDIA_SRC_NOT_SUPPORTED, LOAD_FAILED -> when {
            served.requests == 0 -> noRequest()
            served.headFailures > 0 || file.indexMayTrail && served.upstreamFailures > 0 -> providers(file)
            served.partials > 0 -> CastLoadFailure(CastFailKind.CouldNotPlay, CAST_CANT_OPEN, format = true)
            else -> CastLoadFailure(CastFailKind.CouldNotPlay, stoppedWithError(code))
        }
        MEDIA_NETWORK, NETWORK_UNKNOWN -> lostStream(served, name, file)
        else -> CastLoadFailure(CastFailKind.CouldNotPlay, stoppedWithError(code))
    }

    /**
     * The device's media session vanished before it played the release, with no idle reason and no code (L102): nothing
     * reached the server, the providers failed the file, a borderline [file] refused as it opened, or else the device's
     * player closed it.
     */
    fun vanished(served: ServeCounters, name: String, file: CastFile): CastLoadFailure = when {
        served.requests == 0 -> noRequest()
        served.upstreamFailures > 0 -> providers(file)
        file.borderline -> CastLoadFailure(CastFailKind.FileType, refusedAsItOpened(file, name))
        else -> CastLoadFailure(CastFailKind.CouldNotPlay, CAST_CLOSED_WITHOUT_ERROR)
    }

    /**
     * The Cast session ended before the device played the release (L60): nothing came from it, or it closed. Either way the
     * device is no longer chosen (W36), so only this phone is offered.
     */
    fun closedBeforePlaying(served: ServeCounters, name: String): CastLoadFailure =
        if (served.requests == 0) CastLoadFailure(CastFailKind.CannotReach, sessionEnded = true)
        else CastLoadFailure(CastFailKind.CouldNotPlay, closedBeforeItPlayed(name), sessionEnded = true)

    /** No request came from the device (W34, L88). */
    fun noRequest(): CastLoadFailure = CastLoadFailure(CastFailKind.CannotReach)

    /** Another sender's load took the device before it played the release. */
    fun interrupted(name: String): CastLoadFailure = CastLoadFailure(CastFailKind.CouldNotPlay, somethingElseOn(name))

    /** The device lost the stream: the providers' fault when one of the [file]'s upstreams failed, else the connection's. */
    private fun lostStream(served: ServeCounters, name: String, file: CastFile): CastLoadFailure =
        if (served.upstreamFailures > 0) providers(file) else CastLoadFailure(CastFailKind.CouldNotPlay, lostTheStream(name))

    /**
     * The release's fault, not the device's: Try another release forgets it as the release Resume plays. Said of where the
     * phone reads the [file] from: the providers, or the debrid service.
     */
    private fun providers(file: CastFile) = CastLoadFailure(CastFailKind.CouldNotPlay, if (file.fromService) CAST_SERVICE else CAST_PROVIDERS, providers = true)
}
