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
import io.skjaere.usenet.data.Download
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.slf4j.LoggerFactory

/**
 * Asks this device's host to run the downloads (L335, L388), from the visible app alone, as Android starts a user-initiated
 * job, and a foreground service from Android 12 on, for the visible app alone (C4): the sheet's Download, Resume, a
 * provider saved, a changed switch, and the app's start with downloads to run and no host on them. The host is the job
 * from Android 14 ([DownloadJobService], L334), scheduled only while it is neither running a walk nor waiting to run, as a
 * schedule stops a running job of the same id (C5), but for a changed switch: a waiting job takes the new needs, and a
 * running one stops the download at its safe offset and goes on under them. Below Android 14, or with the debug override
 * (L336), it is the app's own service ([DownloadService]), started only while no walk runs and none stands ready to take
 * the queue up, but for a changed switch, which a service under way reads itself. Never on a TV (L369).
 */
class DownloadHosts(private val context: Context, private val container: AppContainer) {
    /** Schedules the host for [cause] where the rules allow it; whether Android took it. */
    suspend fun schedule(cause: ScheduleCause): Boolean {
        val television = container.television
        if (!DownloadHostRules.runs(television)) return false
        return try {
            val runner = container.downloads
            val rows = runner.runnable()
            val override = container.downloadDebug.serviceHost
            val visible = container.inFront.value.value
            when {
                DownloadHostRules.hostFor(Build.VERSION.SDK_INT, override) == DownloadHost.Service -> startService(cause, override, visible, rows, runner)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> scheduleJob(cause, visible, rows, runner)
                else -> false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Downloads: couldn't schedule the host for {}: {}", cause, e::class.simpleName)
            false
        }
    }

    /** Starts the service for [cause] as [ServicePlan.starts] allows, for the [rows] that can run: whether Android took it. */
    private fun startService(cause: ScheduleCause, override: Boolean, visible: Boolean, rows: List<Download>, runner: DownloadRunner): Boolean {
        val starts = ServicePlan.starts(
            cause, container.television, Build.VERSION.SDK_INT, override, visible, rows.isNotEmpty(), runner.walking.value, DownloadService.standing,
        )
        if (!starts) return false
        return try {
            ContextCompat.startForegroundService(context, DownloadService.start(context, DownloadTrace.StartCause.Activity))
            log.info("Downloads: the service started for {} ({} to run)", cause, rows.size)
            true
        } catch (e: IllegalStateException) {
            // Android 12 on refuses a foreground service to an app that has just left the front.
            log.info("Downloads: Android refused the service for {}: {}", cause, e::class.simpleName)
            false
        }
    }

    /** Schedules the job for [cause] as [DownloadHostRules.schedules] allows, for the [rows] that can run: whether Android took it. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun scheduleJob(cause: ScheduleCause, visible: Boolean, rows: List<Download>, runner: DownloadRunner): Boolean {
        val scheduler = DownloadJobService.scheduler(context)
        val pending = DownloadJobService.pending(scheduler)
        if (!DownloadHostRules.schedules(cause, container.television, visible, rows.isNotEmpty(), runner.walking.value, pending)) return false
        val plan = JobPlan.of(container.settings.downloads.first(), rows)
        val result = scheduler.schedule(DownloadJobService.jobInfo(context, plan))
        log.info(
            "Downloads: the job {} for {} ({} to run, {} bytes, {})", if (result == JobScheduler.RESULT_SUCCESS) "scheduled" else "refused", cause,
            rows.size, plan.downloadBytes, if (plan.unmetered) "unmetered" else "any network",
        )
        return result == JobScheduler.RESULT_SUCCESS
    }

    companion object {
        /**
         * The network the downloads need, for both hosts: as JobScheduler's own network types ask it, a validated one with
         * the internet, a VPN's or a restricted one's included, and not metered (Wi-Fi or Ethernet, never mobile data or a
         * metered hotspot) when [unmetered]. The job requires it; the service watches it with a network callback.
         */
        fun network(unmetered: Boolean): NetworkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .apply { if (unmetered) addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }
            .build()

        /** Whether a network of these [capabilities] is one [network] asks for, [unmetered] or not. */
        fun satisfies(capabilities: NetworkCapabilities, unmetered: Boolean): Boolean =
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                (!unmetered || capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }
}

private val log = LoggerFactory.getLogger(DownloadHosts::class.java)
