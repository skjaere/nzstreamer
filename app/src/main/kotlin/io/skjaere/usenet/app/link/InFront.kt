package io.skjaere.usenet.app.link

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Whether an NZStreamer activity is in front (L31): the app's started activities, any of them, the player's too, counted
 * through the application's lifecycle callbacks. It turns false [graceMillis] after the count reaches 0, since a
 * recreation (a rotation, a change of configuration) stops the old activity before it starts the new one; going from
 * one of the app's screens to another never passes through 0, the new one starting before the one it covers stops.
 *
 * On a TV it scopes Play on TV's listener (L4). It isn't [io.skjaere.usenet.app.AppContainer.foreground], which is
 * MainActivity's alone and keeps the lists' refresher from TMDB while the player streams.
 */
class InFront(private val scope: CoroutineScope, private val graceMillis: Long = GRACE_MS) : Application.ActivityLifecycleCallbacks {
    private val _value = MutableStateFlow(false)
    val value: StateFlow<Boolean> = _value.asStateFlow()

    private val lock = Any()
    private var started = 0

    /** Counts the starts: a grace whose wait ended as another start came has nothing to say. */
    private var generation = 0
    private var leaving: Job? = null

    /** An activity of the app's started: in front at once. */
    fun started() {
        synchronized(lock) {
            started++
            generation++
            leaving?.cancel()
            leaving = null
            _value.value = true
        }
    }

    /** One stopped: with none left started, the app leaves the front once [graceMillis] pass without a start. */
    fun stopped() {
        synchronized(lock) {
            // Never below 0: a stop whose start came before the callbacks were registered.
            if (started == 0) return
            started--
            if (started > 0) return
            val stop = generation
            leaving = scope.launch {
                delay(graceMillis)
                synchronized(lock) { if (generation == stop && started == 0) _value.value = false }
            }
        }
    }

    override fun onActivityStarted(activity: Activity) = started()

    override fun onActivityStopped(activity: Activity) = stopped()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        /** Longer than a recreation takes from the old activity's stop to the new one's start. */
        const val GRACE_MS = 1_000L
    }
}
