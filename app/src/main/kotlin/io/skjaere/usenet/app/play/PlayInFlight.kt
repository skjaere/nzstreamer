package io.skjaere.usenet.app.play

import io.skjaere.usenet.app.casting.CastLauncher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * The play the coordinator has on its way (L32), which the next play stops: what the sheet shows for it ([writes]), the job
 * that starts it ([job]), the launches it sends a player ([launches]), and each play as it starts ([starts]). The
 * coordinator's, apart from it so the download hooks' stop and launch are tried with fakes
 * ([io.skjaere.usenet.app.download.DownloadHook]). [castLauncher]: what stops a cast play on its way on its device.
 */
internal class PlayInFlight(private val playerInFront: PlayerInFront, private val castLauncher: () -> CastLauncher?) {
    /** The sheet's state: each write sets its state and its session together, and a job another play replaced writes nothing more. */
    val writes = SheetWrites()

    /** The sheet's job: searching, trying releases, or asking which file plays. */
    var job: Job? = null

    // A channel, not a SharedFlow: a launch emitted while no activity is collecting (the player
    // just finished for auto-advance, the app is in the background) waits instead of being lost.
    val launches = Channel<PlayerLaunch>(Channel.BUFFERED)

    // Heard only by whoever listens as a play starts, and never waited on: a play starting is no news later.
    val starts = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * What is in flight stops for a play from [origin] (L32): the sheet is that play's from now, so whatever the one before
     * still writes or sends as it ends is dropped; its job is cancelled; a cast play still on its way, replaced by a play
     * here, is stopped there (L85), one replaced by another cast being given up by that one's start; and a launch it sent
     * that no screen took is no longer coming (withdraw drops those it can). The sheet opens for nothing here.
     */
    fun stop(origin: PlayOrigin) {
        val casting = writes.origin.cast != null && job?.isActive == true
        // The sheet is this play's before the one before is cancelled: what that one still does as it ends is dropped.
        writes.begin(origin)
        job?.cancel()
        if (casting && origin.cast == null) castLauncher()?.stop()
        playerInFront.notLaunching()
    }

    /**
     * [launch] plays at once, from [origin], with no sheet (L353): what was in flight stops ([stop]), the sheet stays Idle,
     * those listening hear a play start (a player in the window pauses for it, L9), and the launch goes out as every launch
     * does, its player on its way ([PlayerInFront.launched]).
     */
    fun launchNow(launch: PlayerLaunch, origin: PlayOrigin) {
        stop(origin)
        writes.value = PlayState.Idle
        starts.tryEmit(Unit)
        writes.ifCurrent {
            playerInFront.launched(launch.remote)
            launches.trySend(launch)
        }
    }
}
