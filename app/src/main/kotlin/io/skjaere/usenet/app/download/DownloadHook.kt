package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.casting.CastDownloadPlan
import io.skjaere.usenet.app.casting.CastProfiles
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.PlayInFlight
import io.skjaere.usenet.app.play.PlayOrigin
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.data.CastDeviceMemory
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * The coordinator's hooks (L353), first in its entry points that play a title (Play, Resume, Up next, auto-advance,
 * Previous and Next): a play asked for on this device ([DownloadLaunch.looks]) of a title with a finished download plays
 * its file at once. What is in flight stops, the sheet never opens, and the launch goes out as every launch does
 * ([PlayInFlight.launchNow]): no search, no lookup, and no check for a provider, so it plays offline. The entry points are
 * plain functions on the main thread, so the hook never waits on the library: it asks the runner's map of finished
 * downloads by watch key ([done]), kept in step with the library. A paired phone's or a TV's play, Choose release and
 * Search stream as before. Its line names the download by its id alone.
 *
 * A play cast to a Google Cast device casts the file (W121 c, L446): the hook judges it on the main thread too, by the
 * Cast devices' memory ([devices]) and the app's offline signal ([offline]), as [CastDownloadPlan.of] says from a title's
 * page, and hands it to the coordinator's cast of the file ([cast]). Where the plan streams the title for the device (a
 * file it can't fully play or open, online), the hook passes and the title streams as before; so does a download not
 * finished, never cast as its `.part`, and any before the devices' memory is read.
 *
 * The file it launches or casts is held for the play's first [LAUNCH_HOLD_MILLIS] ([holds], the runner's), so a Remove,
 * Delete all or Delete once watched as its player opens, or its cast starts, waits for it (L413); the player, or the
 * cast's own hold, holds it from then on. One Delete once watched has begun to delete can't be held: its play goes as any
 * other title's, never from a file going.
 */
internal class DownloadHook(
    private val inFlight: PlayInFlight,
    private val television: () -> Boolean,
    private val done: () -> Map<String, Download>,
    /** The runner's holds on the downloads' files (L413). */
    private val holds: () -> DownloadHolds,
    /** Where a launch's hold is let go of once its time is over: the coordinator's. */
    private val scope: CoroutineScope,
    /** The Cast devices' memory as the settings last said it, null until first read (the container's castDevices). */
    private val devices: () -> List<CastDeviceMemory>?,
    /** Whether the app reads as offline, its one signal (L438). */
    private val offline: () -> Boolean,
    /** The coordinator's cast of a finished download's file (L447): its row, its launch and the cast's origin. */
    private val cast: (Download, PlayerLaunch, PlayOrigin) -> Unit,
) {
    /** Plays [watchKey]'s finished download from [origin] when there is one to play from there; whether it did. */
    fun play(watchKey: String?, origin: PlayOrigin): Boolean {
        if (watchKey == null || !DownloadLaunch.looks(origin, television())) return false
        val row = done()[watchKey] ?: return false
        val launch = DownloadLaunch.of(row) ?: return false
        val to = origin.cast
        if (to != null && !casts(row, launch, to)) return false
        if (!holdLaunch(row.id)) return false
        if (to != null) {
            log.info("play: download {} is cast from its file, with nothing searched", row.id)
            cast(row, launch, origin)
        } else {
            log.info("play: download {} plays from its file, with nothing searched", row.id)
            inFlight.launchNow(launch, origin)
        }
        return true
    }

    /**
     * Whether a title's Play to the Cast device [to] casts [row]'s file (L446): a finished one alone, never a `.part`
     * holding zeros where nothing is written yet; judged by the device's profile with no wait, so not before the Cast
     * devices' memory is read (a moment at a cold start); and not where the plan streams the title for the device.
     */
    private fun casts(row: Download, launch: PlayerLaunch, to: CastTo): Boolean {
        if (row.state != DownloadState.Done) return false
        val profile = CastProfiles(devices() ?: return false).of(to.device, to.model)
        return CastDownloadPlan.of(launch.release.fileName, launch.tracks, profile, offline(), CastDownloadPlan.Place.Title) != CastDownloadPlan.Plan.Stream
    }

    /**
     * Holds download [id]'s file for its launch's first [LAUNCH_HOLD_MILLIS]: each launch its own hold, so one ending never
     * lets go of a later one's. Whether it holds it: not one being deleted. The coordinator's for a file a cast plays here
     * instead (L447).
     */
    fun holdLaunch(id: String): Boolean {
        val launch = Any()
        val holds = holds()
        if (!holds.hold(launch, id)) return false
        scope.launch {
            try {
                delay(LAUNCH_HOLD_MILLIS)
            } finally {
                holds.release(launch, id)
            }
        }
        return true
    }

    companion object {
        /** How long the hook holds a file it launches: until its player holds it under its own lease, with room to spare. */
        const val LAUNCH_HOLD_MILLIS = 30_000L
    }
}

private val log = LoggerFactory.getLogger(DownloadHook::class.java)
