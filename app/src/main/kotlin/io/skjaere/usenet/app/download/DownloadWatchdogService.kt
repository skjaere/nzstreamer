package io.skjaere.usenet.app.download

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.download.ServicePlan.Wake
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * The downloads' service's watchdog (L334): a regular job, job 2 on the default scheduler, persisted, needing the network
 * the downloads need ([ServicePlan.watchdog]), which the service schedules as it waits in place for its network and
 * cancels once nothing is left to run. It runs once the network is back, when the service no longer waits for it itself:
 * its wait given up after an hour, or its process gone meanwhile. Then, with something to run, it starts the service again
 * on Android 8 to 11 (and on 14 and later with the debug override, L336), and on 12 and 13, which refuse a foreground
 * service started from a job, it posts "Paused: no connection · Tap to resume", a tap being a start they allow. Nothing on
 * a TV, nor while the service is up.
 */
class DownloadWatchdogService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val container = applicationContext.container
        // Kept before it begins, so a stop finds it.
        val run = container.appScope.launch(start = CoroutineStart.LAZY) { wake(params, container, coroutineContext.job) }
        runs[params.jobId] = run
        run.start()
        return true
    }

    /** Android stopped it, its network gone again before it was done: it runs again once the network is back. */
    override fun onStopJob(params: JobParameters): Boolean {
        runs.remove(params.jobId)?.cancel()
        log.info("Downloads: Android stopped the watchdog")
        return true
    }

    /** The watchdog's run [me]: as [ServicePlan.onWatchdog] says, then done. */
    private suspend fun wake(params: JobParameters, container: AppContainer, me: Job) {
        try {
            val override = container.downloadDebug.serviceHost
            val rows = container.downloads.runnable()
            val wake = ServicePlan.onWatchdog(container.television, Build.VERSION.SDK_INT, override, DownloadService.up, rows.isNotEmpty())
            log.info("Downloads: the watchdog runs, {} to run: {}", rows.size, wake)
            when (wake) {
                Wake.StartService -> try {
                    ContextCompat.startForegroundService(this, DownloadService.start(this, DownloadTrace.StartCause.Watchdog))
                } catch (e: IllegalStateException) {
                    // Refused from the background (Android 12 on, the override's levels): a tap is a start Android allows.
                    log.info("Downloads: Android refused the watchdog's start ({}): tap to resume", e::class.simpleName)
                    container.downloadNotifications.tapToResume(rows.first())
                }
                Wake.TapToResume -> container.downloadNotifications.tapToResume(rows.first())
                Wake.Nothing -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Downloads: the watchdog's run failed: {}", e::class.simpleName)
        } finally {
            // Still its own: a run Android stopped is taken by its stop, and runs again.
            if (runs.remove(params.jobId, me)) jobFinished(params, false)
        }
    }

    companion object {
        /**
         * The watchdog's run under way, by its id. Android hands onStopJob parameters of its own, not those onStartJob had,
         * and the service may be made again between the two, so it is the process's.
         */
        private val runs = ConcurrentHashMap<Int, Job>()

        /** [plan] as Android's JobInfo, on every level: a regular job, persisted, needing an unmetered network or any. */
        fun jobInfo(context: Context, plan: ServicePlan.Watchdog): JobInfo = JobInfo.Builder(plan.id, ComponentName(context, DownloadWatchdogService::class.java))
            .setRequiredNetworkType(if (plan.unmetered) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(plan.persisted)
            .build()
    }
}

private val log = LoggerFactory.getLogger(DownloadWatchdogService::class.java)
