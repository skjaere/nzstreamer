package io.skjaere.usenet.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.VisibleForTesting
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.skjaere.usenet.app.play.DisplayPorts
import io.skjaere.usenet.app.play.RemoteKeys
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FormFactor
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class MainActivity : ComponentActivity() {
    private val log = LoggerFactory.getLogger(MainActivity::class.java)

    /** The .nzb files [OpenNzbActivity] handed over (their [NzbHandoff] ids), for AppNav to open in Import. */
    private val openedNzbs = Channel<String>(Channel.CONFLATED)
    private val opened = openedNzbs.receiveAsFlow()

    /** Presses of a TV remote's search key, for the TV shell to open Search with. */
    private val searchKeys = Channel<Unit>(Channel.CONFLATED)

    /** The remote asked for by a cast's notification ([openRemote]), for AppNav to open. */
    private val remotes = Channel<Unit>(Channel.CONFLATED)

    /** How many times this activity handed AppNav the remote to open: a device test's view of a notification's tap. */
    @VisibleForTesting
    internal var remotesAsked = 0
        private set

    /** The privacy page asked for by the player's list ([openPrivacy], L138), for AppNav to open. */
    private val privacies = Channel<Unit>(Channel.CONFLATED)

    /** How many times this activity handed AppNav the privacy page to open: a device test's view of the player's ask. */
    @VisibleForTesting
    internal var privaciesAsked = 0
        private set

    /** Add a TV asked for by the player's list ([openAddTv]), for AppNav to open. */
    private val addTvs = Channel<Unit>(Channel.CONFLATED)

    /** How many times this activity handed AppNav Add a TV to open: a device test's view of the player's ask. */
    @VisibleForTesting
    internal var addTvsAsked = 0
        private set

    private lateinit var formFactor: FormFactor

    /** Whether a player was started since this activity last started: it has the TV's mode held for it by then. */
    private var launchedSinceStart = false

    /** Keeps the splash's gate over a configuration change, not over the process dying (SplashViewModel). */
    private val splashModel: SplashViewModel by viewModels {
        viewModelFactory { initializer { SplashViewModel(container.setup, SystemClock.uptimeMillis()) } }
    }

    /** How long the splash stays up. */
    @VisibleForTesting
    internal val splashGate: SplashGate
        get() = splashModel.gate

    override fun onCreate(savedInstanceState: Bundle?) {
        // Swaps the splash theme the activity starts on for the app's, before anything is drawn.
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // The design is dark-only; keep light status-bar icons regardless of the system theme.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        // Once per creation: a debug build's launch extra may ask for the TV's layouts on a phone.
        formFactor = FormFactor.of(this, intent)
        val gate = splashModel.gate
        setContent {
            AppTheme(formFactor) {
                AppNav(opened, searchKeys = searchKeys.receiveAsFlow(), remotes = remotes.receiveAsFlow(), privacy = privacies.receiveAsFlow(), addTv = addTvs.receiveAsFlow())
            }
        }
        // Over the activity until the settings Home shows by are read: the container's, which Home
        // reads too, so it has them from its next frame.
        splash.setKeepOnScreenCondition { gate.keepOnScreen(SystemClock.uptimeMillis()) }
        // A recreation restores Import, the remote, the privacy page or Add a TV as it was; only a new start opens one.
        if (savedInstanceState == null) {
            showOpenedNzb(intent)
            showRemote(intent)
            showPrivacy(intent)
            showAddTv(intent)
        }
        // Play on (W18): a new launch starts on This phone, a recreation keeps the TV it had.
        if (!container.television) container.playOn.restore(savedInstanceState?.getString(PLAY_ON))
        // The lists' refresher asks TMDB only while this is in front.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.foreground.value = true
                try {
                    awaitCancellation()
                } finally {
                    container.foreground.value = false
                }
            }
        }
        // Started from the play sheet once a release is ready; without the transition while the TV's mode is held for it,
        // as a window's mode counts for nothing while it animates.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.playback.launches.collect {
                    launchedSinceStart = true
                    PlayerActivity.start(this@MainActivity, it, animate = container.displayHold.held.value == null)
                }
            }
        }
        // The TV's mode held for the next play (DisplayModeHold): this window asks for it while the play sheet finds that
        // play, and gives it up as it stops, once the next player's window asks for it (or the app has gone), or as soon
        // as the hold goes (the play didn't start, was cancelled or minimised; a minute passed; the display had left the
        // mode as this started).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val display = DisplayPorts.of(this@MainActivity)
                try {
                    container.displayHold.held.collect { held ->
                        val id = held?.let { container.displayHold.keptIn(display.read())?.id } ?: 0
                        if (display.preferredModeId != id) display.preferredModeId = id
                    }
                } finally {
                    if (display.preferredModeId != 0) display.preferredModeId = 0
                    container.displayHold.clear(if (launchedSinceStart) "the next player has it" else "the app's screens stopped before the next play")
                }
            }
        }
    }

    // Before the started blocks above run (the lifecycle's ON_START comes after this): a launch waiting in the channel is
    // started by one of them.
    override fun onStart() {
        launchedSinceStart = false
        super.onStart()
        // Play services may have been installed or updated while the app was away (L146).
        container.appScope.launch { container.refreshCastAvailable() }
    }

    /**
     * The keys the screens left: on a TV the remote's search key opens Search (its system would
     * search elsewhere); phones keep the system's handling. With detailed logging, a key nothing in
     * the app takes is logged, so a shared log says what a box's remote sends (not the arrows or Tab,
     * with which Android moves the focus once the app has passed them on, nor the volume keys).
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (formFactor == FormFactor.Tv && keyCode == KeyEvent.KEYCODE_SEARCH) return true
        val handled = super.onKeyDown(keyCode, event)
        if (!handled && event.repeatCount == 0 && RemoteKeys.logsUnhandled(keyCode)) {
            log.debug("Key {} {} not handled", keyCode, KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_"))
        }
        return handled
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (formFactor == FormFactor.Tv && keyCode == KeyEvent.KEYCODE_SEARCH) {
            if (!event.isCanceled) searchKeys.trySend(Unit)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** The system's search request (a TV's search button may send one rather than the key): Search too, on a TV. */
    override fun onSearchRequested(): Boolean {
        if (formFactor != FormFactor.Tv) return super.onSearchRequested()
        searchKeys.trySend(Unit)
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!container.television) outState.putString(PLAY_ON, container.playOn.saved)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showOpenedNzb(intent)
        showRemote(intent)
        showPrivacy(intent)
        showAddTv(intent)
    }

    /** Opens the file [intent] hands over in Import; not again when Recents starts the app with the intent that first opened it. */
    private fun showOpenedNzb(intent: Intent) {
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        intent.getStringExtra(EXTRA_OPENED_NZB)?.let { openedNzbs.trySend(it) }
    }

    /**
     * Opens the remote a cast's notification asks for ([openRemote], L86): only while the remote has a play to follow (the
     * activity is exported, so anything may ask), and not when Recents starts the app with the intent that first asked.
     */
    private fun showRemote(intent: Intent) {
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (!intent.getBooleanExtra(EXTRA_OPEN_REMOTE, false) || container.remotePlaying.now.value == null) return
        remotesAsked++
        remotes.trySend(Unit)
    }

    /**
     * Opens the privacy page the player's list asks for ([openPrivacy], L138), as the remote's ask is opened: not when
     * Recents starts the app with the intent that first asked.
     */
    private fun showPrivacy(intent: Intent) {
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (!intent.getBooleanExtra(EXTRA_OPEN_PRIVACY, false)) return
        privaciesAsked++
        privacies.trySend(Unit)
    }

    /**
     * Opens Add a TV the player's list asks for ([openAddTv]), as the privacy page is opened: on phones and tablets alone,
     * as a TV plays on no TV (the activity is exported, so anything may ask).
     */
    private fun showAddTv(intent: Intent) {
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (!intent.getBooleanExtra(EXTRA_OPEN_ADD_TV, false) || container.television) return
        addTvsAsked++
        addTvs.trySend(Unit)
    }

    companion object {
        private const val EXTRA_OPENED_NZB = "openedNzb"

        /** A cast's notification's ask for the remote (L86). */
        private const val EXTRA_OPEN_REMOTE = "openRemote"

        /** The player's list's ask for the privacy page (L138). */
        private const val EXTRA_OPEN_PRIVACY = "openPrivacy"

        /** The player's list's ask for Add a TV. */
        private const val EXTRA_OPEN_ADD_TV = "openAddTv"

        /** The saved state's TV Play on chose (W18): its key id, kept across a recreation alone. */
        private const val PLAY_ON = "playOn"

        /**
         * Brings the app forward on Import with the .nzb handed over as [id] ([NzbHandoff]): in its
         * own task, closing what is open over this activity there (the player).
         */
        fun openNzb(context: Context, id: String): Intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPENED_NZB, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /**
         * Brings the app forward on the remote of the play it follows (L86), as a cast's notification's tap does: into the
         * activity there is, so a second one doesn't start and choose This phone again (W18), closing what is over it.
         */
        fun openRemote(context: Context): Intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_REMOTE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /**
         * Brings the app forward on the privacy page (L138), as the Privacy of the player's list asks for it: into the
         * activity there is, closing what is over it (the player).
         */
        fun openPrivacy(context: Context): Intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_PRIVACY, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /**
         * Brings the app forward on Add a TV, as the player's list asks for it: into the activity there is, closing what is
         * over it (the player); the TV it pairs is chosen, as from Play on's sheet.
         */
        fun openAddTv(context: Context): Intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_ADD_TV, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
