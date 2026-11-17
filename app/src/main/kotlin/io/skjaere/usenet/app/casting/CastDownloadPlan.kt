package io.skjaere.usenet.app.casting

import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.ContainerTypes
import io.skjaere.usenet.core.ProbedTracks
import io.skjaere.usenet.releases.DeviceCapabilities
import io.skjaere.usenet.releases.ReleaseParser

/**
 * A finished download's file cast to a Google Cast device (W121 c; L446, L447, L473), decided where a JVM test reads it:
 * the coordinator's hook judges a title's Play with a Cast device chosen by [of], on the main thread, from the tracks
 * Resolve's probe kept of the file and the device's profile; its cast of the file shows [sheet] for what it takes.
 */
object CastDownloadPlan {
    /** Where the play of the file was asked for. */
    enum class Place {
        /** A title's page, Resume or Up next: a stream of the title can stand in for the file there (W121 c). */
        Title,

        /** A place of files (the Downloads pages, Home's Downloads rows, the notifications, Import): a tap means that file (L447). */
        Files,
    }

    /** What a cast of the file comes to. */
    enum class Plan {
        /** The file goes to the device: it plays the file's tracks, or nothing read says it can't. */
        Cast,

        /** The pre-check over the file first (L130): Play on this phone, Cast anyway or Cancel. */
        Ask,

        /** A type no Cast device opens (L57): its failure, with Play on this phone and Close. */
        Type,

        /** The title streams for the device, ranked for it, as a title with no download does (W121 c): the hook passes. */
        Stream,
    }

    /**
     * What a cast of [fileName], whose tracks Resolve's probe read as [tracks] (null: none kept), comes to on a device of
     * [profile], asked for from [place] while the app is [offline] or not. A file the device plays, or one nothing read of,
     * is cast, and the device's refusal says what it says, as a cast's probe lets such a file pass. One it can't fully
     * play, or of a type no Cast device opens, streams the title from a title's page while online (W121 c); offline, as
     * nothing can stream, and always from the places of files, it asks over the file or says its type can't play there
     * (L446, L447). An imported file ([release]) has no title to stream (L473).
     */
    fun of(fileName: String?, tracks: ProbedTracks?, profile: DeviceCapabilities, offline: Boolean, place: Place, release: Boolean = false): Plan {
        val taken = over(fileName, tracks, profile)
        return if (taken != Plan.Cast && place == Place.Title && !offline && !release) Plan.Stream else taken
    }

    /**
     * What a cast of [fileName] comes to once the file is taken for it, wherever it was asked for: what is left is the
     * file's own, its type and its [tracks] by the device's [profile] (L447). Never [Plan.Stream].
     */
    fun over(fileName: String?, tracks: ProbedTracks?, profile: DeviceCapabilities): Plan = when {
        fileName != null && !ContainerTypes.castable(fileName) -> Plan.Type
        tracks != null && CastVerdict.miss(tracks, profile) != null -> Plan.Ask
        else -> Plan.Cast
    }

    /**
     * What the cast of [launch]'s file shows for [plan] on [device] (L447), its tracks judged by the device's [profile] and
     * what the user wants of them ([prefs]): the starting sheet ([starting]); the pre-check over the file ([asking]); the
     * type's failure ([refused]); nothing for a stream, whose own sheet shows.
     */
    fun sheet(plan: Plan, launch: PlayerLaunch, profile: DeviceCapabilities, prefs: CastVerdict.Prefs, device: CastTo): PlayState? {
        val tracks = launch.tracks
        val verdict = tracks?.let { CastVerdict.judge(it, profile, prefs) }
        return when (plan) {
            Plan.Cast -> starting(launch, verdict, device)
            Plan.Ask -> tracks?.let { t -> verdict?.let { asking(launch, t, it, device) } } ?: starting(launch, verdict, device)
            Plan.Type -> refused(launch, device)
            Plan.Stream -> null
        }
    }

    /**
     * The cast's one-step sheet (L447, W63): [launch]'s title and its line, the release's name, no release steps, as the
     * file needs none; "Starting on Kitchen display" once it is handed over ([CastStart.loading]). Under "Casting to Kitchen
     * display", the lines a cast says before it plays, by [verdict] (none read: none; the pre-check asked: it said them).
     */
    fun starting(launch: PlayerLaunch, verdict: CastVerdict?, device: CastTo): PlayState.Running = PlayState.Running(
        launch.title, launch.subtitle, null, 1, 1, launch.release.title, emptyList(), emptyMap(), null,
        castLines = CastPrecheck.startingLines(verdict, device.name),
    )

    /**
     * The pre-check over [launch]'s file (L447, L130), by [verdict] on its [tracks]: Play on this phone, which plays the file
     * here, Cast anyway and Cancel; never Play a compatible release, as a file has no list of others. Null where the verdict
     * finds nothing it can't play.
     */
    fun asking(launch: PlayerLaunch, tracks: ProbedTracks, verdict: CastVerdict, device: CastTo): PlayState.Prechecking? {
        val name = launch.release.title
        val precheck = CastPrecheck.of(verdict, tracks, device.name, name, ReleaseParser.parse(name).audio, compatible = false, here = true) ?: return null
        return PlayState.Prechecking(launch.title, launch.subtitle, precheck)
    }

    /**
     * The failure of [launch]'s file, of a type no Cast device opens (L57, L447), checked before anything was sent to [device]:
     * its card over the release's name, then Play on this phone, which plays the file here, and Close.
     */
    fun refused(launch: PlayerLaunch, device: CastTo): PlayState.Failed {
        val failure = AttemptFailure.NotForCast(ContainerTypes.extension(launch.release.fileName.orEmpty()))
        val failed = PlayState.Failed(launch.title, launch.subtitle, listOf(launch.release.title to failure.text), notTried = 0)
        return CastStart.refused(failed, listOf(failure), device, chosen = false) ?: failed
    }
}
