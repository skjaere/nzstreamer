package io.skjaere.usenet.app.download

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.skjaere.usenet.app.container
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * Android's ask whether the app may show notifications, at the first Download (L358; DlPhoneNotification frames 1 and 2):
 * on Android 13 and later only, where downloads show, once ([due]). The Download sheet's line says why before it (item
 * 17); the sheet's Download queues the download, then calls [beforeDownload], which returns once Android has its answer
 * (or at once when nothing is asked), then schedules the host: either answer starts the download. Refused, downloads still
 * run, and the page and Home's section show them; Settings › Downloads offers Android's page for them later.
 *
 * "Asked" is saved ([markAsked]) before Android's dialog shows, so a process that dies with it up never asks again. The
 * activity's own launcher asks ([launch], MainActivity's, [of]); its result, or the activity's end with the dialog up,
 * calls [answered]. On the main thread.
 */
class NotificationAsk(
    private val sdk: Int,
    private val shown: () -> Boolean,
    private val granted: () -> Boolean,
    private val asked: suspend () -> Boolean,
    private val markAsked: suspend () -> Unit,
    private val launch: () -> Unit,
) {
    private val asking = Mutex()

    /** The answer Android is asked for now; null while nothing is asked. */
    private var answer: CompletableDeferred<Unit>? = null

    /** Asks Android when it is [due], and returns once it has answered, either way; at once when nothing is asked. */
    suspend fun beforeDownload() {
        val waiting = asking.withLock {
            answer ?: run {
                if (!due(sdk, shown(), asked(), granted())) return
                markAsked()
                val made = CompletableDeferred<Unit>()
                answer = made
                try {
                    launch()
                } catch (e: IllegalStateException) {
                    // The activity's launcher is gone with it: nothing is asked, and the download starts.
                    log.info("Couldn't ask for notifications: {}", e::class.simpleName)
                    answered()
                }
                made
            }
        }
        waiting.await()
    }

    /** Android answered, allowed or refused, or the activity ended with the dialog up: the downloads waiting for it start. */
    fun answered() {
        answer?.complete(Unit)
        answer = null
    }

    companion object {
        /** Whether the first Download asks: on Android 13 and later, where downloads show, not asked before, and not allowed already. */
        fun due(sdk: Int, shown: Boolean, asked: Boolean, granted: Boolean): Boolean = sdk >= Build.VERSION_CODES.TIRAMISU && shown && !asked && !granted

        /**
         * [activity]'s: its launcher registered as it is made (a field's initializer), the setting saved and read in the
         * app's settings, and where downloads show by [shown], read as each Download asks.
         */
        fun of(activity: ComponentActivity, shown: () -> Boolean): NotificationAsk {
            lateinit var ask: NotificationAsk
            val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { ask.answered() }
            ask = NotificationAsk(
                sdk = Build.VERSION.SDK_INT,
                shown = shown,
                granted = {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                },
                asked = { activity.container.settings.downloads.first().notificationsAsked },
                markAsked = { activity.container.settings.setDownloadNotificationsAsked(true) },
                // Only where it is due, which is Android 13 and later.
                launch = { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            )
            // Gone with the dialog up (a rotation remakes the activity): what waited for its answer goes on.
            activity.lifecycle.addObserver(LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_DESTROY) ask.answered() })
            return ask
        }
    }
}

/** The activity's notification ask, for the Download sheet (item 17): none outside MainActivity. */
val LocalNotificationAsk = staticCompositionLocalOf<NotificationAsk?> { null }

private val log = LoggerFactory.getLogger(NotificationAsk::class.java)
