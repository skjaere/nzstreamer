package io.skjaere.usenet.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Whether MainActivity keeps the splash up: until its first screen has something to show (Home reads
 * the settings to choose between itself and the first-run screen), so that screen comes up in the
 * splash's place rather than an empty frame after it. At most [timeoutMillis], so a screen that never
 * gets ready can't keep the app behind the splash. One for each start of the app's screen, kept over
 * a configuration change ([SplashViewModel]). Times are SystemClock.uptimeMillis(); everything here
 * is on the main thread.
 */
class SplashGate(private val startedAt: Long, private val timeoutMillis: Long = TIMEOUT_MILLIS) {
    private var ready = false

    /** Whether the timeout, not the first screen, let the splash go. */
    var timedOut = false
        private set

    /** How often [keepOnScreen] was asked: Android asks before the first frame even when ready, so never means the splash isn't held by this. */
    var asked = 0
        private set

    /** The first screen has something to show. */
    fun ready() {
        ready = true
    }

    /**
     * Ready once [settings], null until they are read, has a value. The settings themselves, not
     * Home's screen: a .nzb opened from another app takes Home off the screen for Import at once.
     */
    suspend fun readyOnRead(settings: Flow<Any?>) {
        settings.first { it != null }
        ready()
    }

    /** Asked before each frame the activity would draw, on the main thread: true keeps the splash over it. */
    fun keepOnScreen(now: Long): Boolean {
        asked++
        if (!ready && now - startedAt >= timeoutMillis) {
            // A shared log then says why the app took that long to show.
            log.warn("The first screen wasn't ready after {} ms: the splash lets it show as it is", timeoutMillis)
            ready = true
            timedOut = true
        }
        return !ready
    }

    companion object {
        const val TIMEOUT_MILLIS = 2_000L
    }
}

/**
 * MainActivity's [SplashGate], ready once [settings] are read. A configuration change (turned, say)
 * keeps the view model, so the new activity holds the splash only while the first would have, its
 * timeout counted from the first start. After the process died Android brings the activity back with
 * its saved screens and shows its splash with nothing read yet: a new view model, whose gate holds.
 */
class SplashViewModel(settings: Flow<Any?>, startedAt: Long) : ViewModel() {
    val gate = SplashGate(startedAt)

    init {
        viewModelScope.launch { gate.readyOnRead(settings) }
    }
}

private val log = LoggerFactory.getLogger(SplashGate::class.java)
