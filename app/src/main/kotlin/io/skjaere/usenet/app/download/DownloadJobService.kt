package io.skjaere.usenet.app.download

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.download.DownloadHostRules.JobStop
import io.skjaere.usenet.data.DownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * The downloads' host on Android 14 and later (L334, L335, L376, L388, C4, C5): a user-initiated data transfer job, one
 * for the whole queue ([JobPlan]: namespace "downloads", job 1, no work items), scheduled by the visible app alone
 * ([DownloadHosts]). Android runs it while its network (and the charger, when asked) is there, holds its wake lock, and
 * lets it past Doze, standby buckets and quotas.
 *
 * A start posts the job's notification at once, the ongoing one ([DownloadNotifications.ongoing]), which goes with the job
 * (JOB_END_NOTIFICATION_POLICY_REMOVE): Android stops a job that has none within 10 s. The runner then walks the queue on
 * a coroutine in the app's scope ([DownloadRunner.run]), the notification kept in step, and what the files grew by and
 * what is left reported every few seconds through the overloads without a work item ([JobBytes]). Once nothing can run
 * (all done or failed, the queue paused, a wait that ends the host, L388) the job ends, the status notification saying
 * what is left ([DownloadNotifications.hostEnded]).
 *
 * Android's stop ([onStopJob]) cancels the walk, which records where it stopped; the download under way then waits for the
 * need that went ("Waiting for Wi-Fi"), the job running again once it is back, or is Stopped by Android when the user
 * stopped it. Its log lines name the stop reason's code and the downloads by id, never a title or a release.
 *
 * Nothing on a TV (L369), and nothing where the debug override gives the downloads to the service (L336).
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class DownloadJobService : JobService() {
    /** A start of the job here, its walk on [job]; [stop] what Android's stop meant, once it came. */
    private class Run {
        lateinit var job: Job

        @Volatile
        var stop: JobStop? = null
    }

    override fun onStartJob(params: JobParameters): Boolean {
        val container = applicationContext.container
        if (!DownloadHostRules.runs(container.television)) return false
        // A job scheduled before the debug override gave the downloads to the service (L336) leaves them to it.
        if (DownloadHostRules.hostFor(Build.VERSION.SDK_INT, container.downloadDebug.serviceHost) != DownloadHost.Job) {
            log.info("Downloads: the job starts where the service is the host: it ends")
            return false
        }
        container.downloadDebug.trace(DownloadTrace.HostStart(DownloadHost.Job, DownloadTrace.StartCause.Job))
        log.info("Downloads: the job starts")
        live.update { it + 1 }
        container.downloadNotifications.hostStarted()
        val run = Run()
        runs[params.jobId] = run
        // Begun here, to its first wait: a stop that came before a dispatched start would skip its end, and a stop can't
        // come before this returns, on the same thread.
        run.job = container.appScope.launch(start = CoroutineStart.UNDISPATCHED) { walk(params, run, container) }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val reason = params.stopReason
        val needs = DownloadNeeds(unmetered = params.extras.getBoolean(UNMETERED, true), charging = params.extras.getBoolean(CHARGING, false))
        val stop = DownloadHostRules.onStop(reason, needs)
        log.info("Downloads: Android stopped the job, reason {}: {}", reason, stop::class.simpleName)
        runs[params.jobId]?.let { run ->
            run.stop = stop
            run.job.cancel()
        }
        return stop.reschedule
    }

    /**
     * The job's life: its notification within Android's 10 s, then the walk with the notification and the bytes kept in
     * step, then its end ([ended]), whatever ended it.
     */
    private suspend fun walk(params: JobParameters, run: Run, container: AppContainer) {
        val runner = container.downloads
        val notifications = container.downloadNotifications
        try {
            // From the queue as reconcile leaves it; from the table as it is, should reconcile take long, so the notification
            // still comes in time.
            var rows = withTimeoutOrNull(NOTICE_WAIT_MILLIS) { runner.runnable() }
            if (rows == null) {
                post(params, runner.items(), notifications)
                rows = runner.runnable()
            }
            if (rows.isEmpty()) {
                log.info("Downloads: nothing to run under the job")
                return
            }
            post(params, runner.items(), notifications)
            coroutineScope {
                val keeping = launch { notifications.keepOngoing(runner.state) }
                val reporting = launch { report(params, runner) }
                runner.run(DownloadHost.Job)
                // Before the job ends, so no update lands after Android has taken its notification away.
                keeping.cancel()
                reporting.cancel()
            }
        } finally {
            withContext(NonCancellable) { ended(params, run, container) }
        }
    }

    /** Posts the ongoing notification for [items] as the job's own; false with nothing to say. */
    private fun post(params: JobParameters, items: List<DownloadRunner.Item>, notifications: DownloadNotifications): Boolean {
        val notification = notifications.ongoing(items) ?: return false
        return try {
            setNotification(params, DownloadNotifications.ONGOING_ID, notification, JOB_END_NOTIFICATION_POLICY_REMOVE)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // A job Android stopped as it came: its end follows.
            log.warn("Downloads: the job's notification wasn't taken: {}", e::class.simpleName)
            false
        }
    }

    /**
     * Reports what the files grew by every few seconds, and what the job expects in all as the queue changes (C5's
     * overloads without a work item), for as long as the walk runs.
     */
    private suspend fun report(params: JobParameters, runner: DownloadRunner) = coroutineScope {
        val bytes = JobBytes()
        launch {
            var told: Long? = null
            runner.state.collect { items ->
                bytes.seen(items)
                if (bytes.estimated != told) {
                    told = bytes.estimated
                    tell("estimate") { updateEstimatedNetworkBytes(params, bytes.estimated, 0) }
                }
            }
        }
        var told = 0L
        while (true) {
            delay(REPORT_MILLIS)
            val transferred = bytes.transferred
            if (transferred != told) {
                told = transferred
                tell("progress") { updateTransferredNetworkBytes(params, transferred, 0) }
            }
        }
    }

    /** Tells Android [what] it asks; a job it has stopped meanwhile takes nothing more. */
    private inline fun tell(what: String, call: () -> Unit) {
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            log.debug("Downloads: the job's {} wasn't taken: {}", what, e::class.simpleName)
        }
    }

    /**
     * The walk ended: the download a stop of Android's left under way says why, the status notification says what is left
     * once no other start of the job runs, and the job ends unless Android ended it.
     */
    private suspend fun ended(params: JobParameters, run: Run, container: AppContainer) {
        // Its own: a start the visible app scheduled as this one ended may be there already.
        runs.remove(params.jobId, run)
        val runner = container.downloads
        val stop = run.stop
        try {
            when (stop) {
                is JobStop.Wait -> runner.hostStopped(DownloadState.Waiting, stop.words)
                JobStop.Stopped -> runner.hostStopped(DownloadState.Stopped, DownloadHostRules.STOPPED_BY_ANDROID)
                JobStop.Again, JobStop.Cancelled, null -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Downloads: couldn't say why the job stopped: {}", e::class.simpleName)
        }
        try {
            // One schedule stops a running job and starts it again: the later start's notification is the one that stays.
            if (live.updateAndGet { it - 1 } == 0) container.downloadNotifications.hostEnded(runner.items())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Downloads: couldn't say what the job left: {}", e::class.simpleName)
        }
        log.info("Downloads: the job ends")
        if (stop == null) jobFinished(params, false)
    }

    companion object {
        /** The job's needs, as it was scheduled with them: what its stop for one of them says (onStopJob). */
        private const val UNMETERED = "unmetered"
        private const val CHARGING = "charging"

        /** How long the start waits for reconcile before it posts the notification from the table as it is. */
        private const val NOTICE_WAIT_MILLIS = 5_000L

        /** How often what the files grew by is reported. */
        private const val REPORT_MILLIS = 5_000L

        /**
         * The job's start under way, by its id. Android hands onStopJob parameters of its own, not those onStartJob had;
         * and the service may be made again between the two, so it is the process's.
         */
        private val runs = ConcurrentHashMap<Int, Run>()

        /** The job's starts here whose walks haven't ended. */
        private val live = MutableStateFlow(0)

        /** How many starts of the job are under way in this process: a device test waits for none. */
        @VisibleForTesting
        internal val starts: StateFlow<Int> = live.asStateFlow()

        /** The downloads' own scheduler (Android 14's namespaces). */
        fun scheduler(context: Context): JobScheduler = context.getSystemService(JobScheduler::class.java).forNamespace(JobPlan.NAMESPACE)

        /**
         * Whether the job waits to run (L335): scheduled, and not running. A running one whose walk has ended is about to end
         * itself, so the runner's [DownloadRunner.walking] says whether a running one will take up what is queued.
         */
        fun pending(scheduler: JobScheduler): Boolean =
            scheduler.getPendingJob(JobPlan.ID) != null && scheduler.getPendingJobReason(JobPlan.ID) != JobScheduler.PENDING_JOB_REASON_EXECUTING

        /** [plan] as Android's JobInfo (L335, L376): user-initiated, its network, persisted, charging when asked, no storage constraint. */
        fun jobInfo(context: Context, plan: JobPlan): JobInfo = JobInfo.Builder(plan.id, ComponentName(context, DownloadJobService::class.java))
            .setUserInitiated(plan.userInitiated)
            .setRequiredNetwork(DownloadHosts.network(plan.unmetered))
            .setEstimatedNetworkBytes(plan.downloadBytes, plan.uploadBytes)
            .setPersisted(plan.persisted)
            .setRequiresCharging(plan.charging)
            .setRequiresStorageNotLow(plan.storageNotLow)
            .setExtras(PersistableBundle().apply {
                putBoolean(UNMETERED, plan.unmetered)
                putBoolean(CHARGING, plan.charging)
            })
            .build()
    }
}

private val log = LoggerFactory.getLogger(DownloadJobService::class.java)
