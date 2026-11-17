package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.play.PlayInFlight
import io.skjaere.usenet.app.play.PlayOrigin
import io.skjaere.usenet.data.Download
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
 * downloads by watch key ([done]), kept in step with the library. A cast's, a paired phone's or a TV's play, Choose
 * release and Search stream as before. Its line names the download by its id alone.
 *
 * The file it launches is held for the launch's first [LAUNCH_HOLD_MILLIS] ([holds], the runner's), so a Remove, Delete
 * all or Delete once watched as its player opens waits for it (L413); the player holds it under its own lease from then on.
 * One Delete once watched has begun to delete can't be held: its play goes as any other title's, never from a file going.
 */
internal class DownloadHook(
    private val inFlight: PlayInFlight,
    private val television: () -> Boolean,
    private val done: () -> Map<String, Download>,
    /** The runner's holds on the downloads' files (L413). */
    private val holds: () -> DownloadHolds,
    /** Where a launch's hold is let go of once its time is over: the coordinator's. */
    private val scope: CoroutineScope,
) {
    /** Plays [watchKey]'s finished download from [origin] when there is one to play from there; whether it did. */
    fun play(watchKey: String?, origin: PlayOrigin): Boolean {
        if (watchKey == null || !DownloadLaunch.looks(origin, television())) return false
        val row = done()[watchKey] ?: return false
        val launch = DownloadLaunch.of(row) ?: return false
        if (!hold(row.id)) return false
        log.info("play: download {} plays from its file, with nothing searched", row.id)
        inFlight.launchNow(launch, origin)
        return true
    }

    /**
     * Holds download [id]'s file for its launch's first [LAUNCH_HOLD_MILLIS]: each launch its own hold, so one ending never
     * lets go of a later one's. Whether it holds it: not one being deleted.
     */
    private fun hold(id: String): Boolean {
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
