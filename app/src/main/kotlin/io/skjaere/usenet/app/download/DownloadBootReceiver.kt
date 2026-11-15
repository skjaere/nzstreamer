package io.skjaere.usenet.app.download

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.skjaere.usenet.app.container
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Starts the downloads' service again at boot and after the app's update (L334, L375), where it is the host (Android 8 to
 * 13, or the debug override, L336) and a download can run: both broadcasts let an app start a foreground service from the
 * background on Android 12 and 13. Disabled in the manifest, it is switched on by the runner ([switch], its
 * receiverSwitch) only while the service is the host and something can run, so a TV, a phone whose host is the job
 * (JobScheduler keeps its persisted job) and a phone with nothing to run are never woken at boot. Not exported: the
 * system's broadcasts reach it. Its log lines say the broadcast and how many can run, nothing of what they are.
 */
class DownloadBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.container
        val action = intent.action
        val override = container.downloadDebug.serviceHost
        if (!ServicePlan.runsHere(container.television, Build.VERSION.SDK_INT, override)) return
        // The queue is read from the table, after reconcile: the broadcast is held open until then.
        val pending = goAsync()
        container.appScope.launch {
            try {
                val rows = container.downloads.runnable()
                if (ServicePlan.bootStarts(action, container.television, Build.VERSION.SDK_INT, override, rows.isNotEmpty())) {
                    ContextCompat.startForegroundService(context, DownloadService.start(context, DownloadTrace.StartCause.BootReceiver))
                    log.info("Downloads: the service started on {} ({} to run)", action, rows.size)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Android 15 on refuses a dataSync service from the boot (the override's levels alone).
                log.warn("Downloads: couldn't start the service on {}: {}", action, e::class.simpleName)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /**
         * Turns the receiver on, or back to the manifest's off (L375): the runner's receiverSwitch, as its rows and the host
         * say. Without killing the app, which is running.
         */
        fun switch(context: Context, on: Boolean) {
            val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            context.packageManager.setComponentEnabledSetting(ComponentName(context, DownloadBootReceiver::class.java), state, PackageManager.DONT_KILL_APP)
        }
    }
}

private val log = LoggerFactory.getLogger(DownloadBootReceiver::class.java)
