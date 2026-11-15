package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.play.PlayInFlight
import io.skjaere.usenet.app.play.PlayOrigin
import io.skjaere.usenet.data.Download
import org.slf4j.LoggerFactory

/**
 * The coordinator's hooks (L353), first in its entry points that play a title (Play, Resume, Up next, auto-advance,
 * Previous and Next): a play asked for on this device ([DownloadLaunch.looks]) of a title with a finished download plays
 * its file at once. What is in flight stops, the sheet never opens, and the launch goes out as every launch does
 * ([PlayInFlight.launchNow]): no search, no lookup, and no check for a provider, so it plays offline. The entry points are
 * plain functions on the main thread, so the hook never waits on the library: it asks the runner's map of finished
 * downloads by watch key ([done]), kept in step with the library. A cast's, a paired phone's or a TV's play, Choose
 * release and Search stream as before. Its line names the download by its id alone.
 */
internal class DownloadHook(
    private val inFlight: PlayInFlight,
    private val television: () -> Boolean,
    private val done: () -> Map<String, Download>,
) {
    /** Plays [watchKey]'s finished download from [origin] when there is one to play from there; whether it did. */
    fun play(watchKey: String?, origin: PlayOrigin): Boolean {
        if (watchKey == null || !DownloadLaunch.looks(origin, television())) return false
        val row = done()[watchKey] ?: return false
        val launch = DownloadLaunch.of(row) ?: return false
        log.info("play: download {} plays from its file, with nothing searched", row.id)
        inFlight.launchNow(launch, origin)
        return true
    }
}

private val log = LoggerFactory.getLogger(DownloadHook::class.java)
