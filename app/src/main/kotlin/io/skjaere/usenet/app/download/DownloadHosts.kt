package io.skjaere.usenet.app.download

import android.app.job.JobScheduler
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.download.DownloadHostRules.ScheduleCause
import io.skjaere.usenet.app.download.DownloadTrace.ScheduleResult
import io.skjaere.usenet.data.Download
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * Asks this device's host to run the downloads (L335, L388), from the visible app alone, as Android starts a user-initiated
 * job, and a foreground service from Android 12 on, for the visible app alone (C4): the sheet's Download, Resume, a
 * provider saved, a changed switch, and the app's start with downloads to run and no host on them. The host is the job
 * from Android 14 ([DownloadJobService], L334), scheduled only while it is neither running a walk nor waiting to run, as a
 * schedule stops a running job of the same id (C5), but for a changed switch: a waiting job takes the new needs, and a
 * running one stops the download at its safe offset and goes on under them. Below Android 14, or with the debug override
 * (L336), it is the app's own service ([DownloadService]), started only while no walk runs and none stands ready to take
 * the queue up, but for a changed switch, which a service under way reads itself. Never on a TV (L369). Each ask is
 * traced in the debug build with its cause and how it went (L391).
 */
class DownloadHosts(private val deps: DownloadHostsDeps) {
    /**
     * One ask at a time: the app's start and a Resume as the app comes to the front would both find no job waiting, and
     * the second schedule would stop the job the first started (C5).
     */
    private val asking = Mutex()

    /** Schedules the host for [cause] where the rules allow it; whether Android took it: the job scheduled, or the service started. */
    suspend fun schedule(cause: ScheduleCause): Boolean {
        if (!DownloadHostRules.runs(deps.television)) return false
        val result = asking.withLock {
            try {
                val rows = deps.runnable()
                val override = deps.debug.serviceHost
                val visible = deps.visible()
                when {
                    DownloadHostRules.hostFor(deps.sdk, override) == DownloadHost.Service -> startService(cause, override, visible, rows)
                    deps.sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> scheduleJob(cause, visible, rows)
                    else -> ScheduleResult.Skipped
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Downloads: couldn't schedule the host for {}: {}", cause, e::class.simpleName)
                ScheduleResult.Refused
            }
        }
        // So a two-phase test tells the app's own schedule from the platform's run of a job that was waiting (L391).
        deps.debug.trace(DownloadTrace.Schedule(cause, result))
        return result == ScheduleResult.Scheduled || result == ScheduleResult.Service
    }

    /** Starts the service for [cause] as [ServicePlan.starts] allows, for the [rows] that can run. */
    private fun startService(cause: ScheduleCause, override: Boolean, visible: Boolean, rows: List<Download>): ScheduleResult {
        val starts = ServicePlan.starts(cause, deps.television, deps.sdk, override, visible, rows.isNotEmpty(), deps.walking(), deps.standing())
        if (!starts) return ScheduleResult.Skipped
        return try {
            deps.startService()
            log.info("Downloads: the service started for {} ({} to run)", cause, rows.size)
            ScheduleResult.Service
        } catch (e: IllegalStateException) {
            // Android 12 on refuses a foreground service to an app that has just left the front.
            log.info("Downloads: Android refused the service for {}: {}", cause, e::class.simpleName)
            ScheduleResult.Refused
        }
    }

    /** Schedules the job for [cause] as [DownloadHostRules.schedules] allows, for the [rows] that can run. */
    private suspend fun scheduleJob(cause: ScheduleCause, visible: Boolean, rows: List<Download>): ScheduleResult {
        // Waiting first: a start ahead of its walk reads as waiting until its walk takes the queue up, and walking from then.
        val pending = deps.pending()
        val schedules = DownloadHostRules.schedules(cause, deps.television, visible, rows.isNotEmpty(), deps.walking(), pending)
        if (!schedules) return ScheduleResult.Skipped
        return if (deps.scheduleJob(cause, rows)) ScheduleResult.Scheduled else ScheduleResult.Refused
    }

    companion object {
        /**
         * The network the downloads need, as JobScheduler's own network types ask it: a validated one with the internet, a
         * VPN's or a restricted one's included, and not metered (Wi-Fi or Ethernet, never mobile data or a metered hotspot)
         * when [unmetered]. The job and the service's watchdog require it; the service judges the device's default network
         * the same way ([ServicePlan.networkMet]).
         */
        fun network(unmetered: Boolean): NetworkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .apply { if (unmetered) addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }
            .build()
    }
}

/**
 * What [DownloadHosts] reads and asks of Android: the app's ([of]), or a test's fakes. [startService] starts the service
 * from the visible app, throwing [IllegalStateException] where Android refuses it; [pending] says whether the job waits to
 * run, or runs ahead of its walk ([DownloadJobService.pending]), and [scheduleJob] schedules it for the rows that can run,
 * whether Android took it (both Android 14's).
 */
class DownloadHostsDeps(
    /** Whether this is a TV, where nothing of downloads runs (W108, L369). */
    val television: Boolean,
    /** The API level, which with the debug override picks the host (L334, L336). */
    val sdk: Int,
    val debug: DownloadDebug,
    /** The downloads that can run now ([DownloadRunner.runnable], L388). */
    val runnable: suspend () -> List<Download>,
    /** Whether a host's walk will take up what is queued now ([DownloadRunner.walking]). */
    val walking: () -> Boolean,
    /** Whether the app is in front, which Android asks of a user-initiated job and a foreground service's start (C4). */
    val visible: () -> Boolean,
    /** Whether the service stands ready to take up what is queued ([DownloadService.standing]). */
    val standing: () -> Boolean,
    val startService: () -> Unit,
    val pending: () -> Boolean,
    val scheduleJob: suspend (cause: ScheduleCause, rows: List<Download>) -> Boolean,
) {
    companion object {
        /** The app's: the container's runner and settings, the service, and the job on Android 14 and later. */
        fun of(context: Context, container: AppContainer) = DownloadHostsDeps(
            television = container.television,
            sdk = Build.VERSION.SDK_INT,
            debug = container.downloadDebug,
            runnable = { container.downloads.runnable() },
            walking = { container.downloads.walking.value },
            visible = { container.inFront.value.value },
            standing = { DownloadService.standing },
            startService = { ContextCompat.startForegroundService(context, DownloadService.start(context, DownloadTrace.StartCause.Activity)) },
            pending = { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) pendingJob(context) else false },
            scheduleJob = { cause, rows ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) scheduleJob(context, container, cause, rows) else false
            },
        )

        /** Whether the job waits to run ([DownloadJobService.pending]). */
        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        private fun pendingJob(context: Context): Boolean = DownloadJobService.pending(DownloadJobService.scheduler(context))

        /** Schedules the job for [cause] and the [rows] that can run, by the settings' needs: whether Android took it. */
        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        private suspend fun scheduleJob(context: Context, container: AppContainer, cause: ScheduleCause, rows: List<Download>): Boolean {
            val plan = JobPlan.of(container.settings.downloads.first(), rows)
            DownloadJobService.scheduling()
            val result = try {
                DownloadJobService.scheduler(context).schedule(DownloadJobService.jobInfo(context, plan))
            } catch (e: RuntimeException) {
                DownloadJobService.refused()
                throw e
            }
            if (result != JobScheduler.RESULT_SUCCESS) DownloadJobService.refused()
            log.info(
                "Downloads: the job {} for {} ({} to run, {} bytes, {})", if (result == JobScheduler.RESULT_SUCCESS) "scheduled" else "refused", cause,
                rows.size, plan.downloadBytes, if (plan.unmetered) "unmetered" else "any network",
            )
            return result == JobScheduler.RESULT_SUCCESS
        }
    }
}

private val log = LoggerFactory.getLogger(DownloadHosts::class.java)
