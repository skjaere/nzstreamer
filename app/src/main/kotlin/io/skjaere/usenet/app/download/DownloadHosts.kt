package io.skjaere.usenet.app.download

import android.app.job.JobScheduler
import android.content.Context
import android.os.Build
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.download.DownloadHostRules.ScheduleCause
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.slf4j.LoggerFactory

/**
 * Asks this device's host to run the downloads (L335, L388), from the visible app alone, as Android starts a user-initiated
 * job for the visible app alone (C4): the sheet's Download, Resume, a provider saved, a changed switch, and the app's start
 * with downloads to run and no host on them. The host is the job from Android 14 ([DownloadJobService], L334), scheduled
 * only while it is neither running a walk nor waiting to run, as a schedule stops a running job of the same id (C5), but
 * for a changed switch: a waiting job takes the new needs, and a running one stops the download at its safe offset and
 * goes on under them. Below Android 14, or with the debug override (L336), the host is the app's own service, which
 * item 10 brings; until then nothing is started there. Never on a TV (L369).
 */
class DownloadHosts(private val context: Context, private val container: AppContainer) {
    /** Schedules the host for [cause] where the rules allow it; whether Android took it. */
    suspend fun schedule(cause: ScheduleCause): Boolean {
        val television = container.television
        if (!DownloadHostRules.runs(television)) return false
        return try {
            val runner = container.downloads
            val rows = runner.runnable()
            val host = DownloadHostRules.hostFor(Build.VERSION.SDK_INT, container.downloadDebug.serviceHost)
            if (host != DownloadHost.Job || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
            val scheduler = DownloadJobService.scheduler(context)
            val visible = container.inFront.value.value
            val pending = DownloadJobService.pending(scheduler)
            if (!DownloadHostRules.schedules(cause, television, visible, rows.isNotEmpty(), runner.walking.value, pending)) return false
            val plan = JobPlan.of(container.settings.downloads.first(), rows)
            val result = scheduler.schedule(DownloadJobService.jobInfo(context, plan))
            log.info(
                "Downloads: the job {} for {} ({} to run, {} bytes, {})", if (result == JobScheduler.RESULT_SUCCESS) "scheduled" else "refused", cause,
                rows.size, plan.downloadBytes, if (plan.unmetered) "unmetered" else "any network",
            )
            result == JobScheduler.RESULT_SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Downloads: couldn't schedule the job for {}: {}", cause, e::class.simpleName)
            false
        }
    }
}

private val log = LoggerFactory.getLogger(DownloadHosts::class.java)
