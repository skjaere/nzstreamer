package io.skjaere.usenet.app.download

import android.annotation.SuppressLint
import android.app.job.JobParameters
import io.skjaere.usenet.app.casting.CastWakeLock
import io.skjaere.usenet.core.formatSize
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadPrefs
import io.skjaere.usenet.data.DownloadState
import java.util.Locale

/** What runs the downloads in the background (L334). */
enum class DownloadHost {
    /** A user-initiated data transfer job: API 34 on. */
    Job,

    /** The app's own `dataSync` foreground service: API 26 to 33, or any level with the debug override (L336). */
    Service,
}

/**
 * What a download needs to run (W110, L365): an [unmetered] network (Wi-Fi or Ethernet, never mobile data or a metered
 * hotspot) or any, and the [charging] or not. No Android types: the job builds its NetworkRequest and JobInfo from it, the
 * service its network callback and power broadcasts.
 */
data class DownloadNeeds(val unmetered: Boolean, val charging: Boolean)

/** A download notification's words: its [title] (the show and episode, or the film), its [text] by state, and its [subText]. */
data class NotificationWords(val title: String, val text: String, val subText: String?)

/**
 * The downloads' hosts (L334-L336, L358, L369, L375, L380, L388): which host for the API level, what a download needs, what
 * a stop of the job means, which rows can run, when the boot receiver is on, how long the service waits, when the visible
 * app schedules, the wake lock, and what the notification says. Pure, so a JVM test reads every case, as CastServiceRules
 * is; the job, the service, the receiver, the runner and the notifications only wire it. Nothing runs on a TV
 * ([runs]): each of them asks first.
 */
object DownloadHostRules {
    /** Waiting for an unmetered network, Wi-Fi only on. */
    const val WAITING_FOR_WIFI = "Waiting for Wi-Fi"

    /** Waiting for any network, Wi-Fi only off. */
    const val WAITING_FOR_NETWORK = "Waiting for a network"

    /** Waiting for the charger, while charging on. */
    const val WAITING_TO_CHARGE = "Waiting to charge"

    /**
     * The provider out of reach while Android calls the network validated (L382): said while the writer backs off, and as
     * a wait that ends the host after an hour of it (L388).
     */
    const val CANT_REACH_PROVIDER = "Can't reach your Usenet provider"

    /**
     * The indexers out of reach or failing (a 5xx) as Resolve grabs (L382's outage, an indexer's): said while it backs off,
     * and as a wait that ends the host after an hour of it (L388).
     */
    const val CANT_REACH_INDEXER = "Can't reach your indexer"

    /** The provider refused the login, or none is saved (L382). */
    const val CHECK_PROVIDER = "Check your Usenet provider"

    /** The primary volume's folder isn't there, unmounted (L388): [DownloadStorage.Unavailable]'s words. */
    const val STORAGE_UNAVAILABLE = "Storage isn't available"

    /** Task Manager's Stop or a force stop (L359): held until Resume. */
    const val STOPPED_BY_ANDROID = "Stopped by Android"

    /** A release's search and Resolve's first step (L338). */
    const val FINDING_RELEASES = "Finding releases…"

    /** The service waits in place for its need this long, then stops (L334): an hour. */
    const val SERVICE_WAIT_CAP_MILLIS = 60 * 60_000L

    /** The service's own wake lock is let go this long after the last byte, as the cast service's is (L59). */
    const val WAKE_LET_GO_MILLIS = 10_000L

    /** The wake lock's own timeout, so no missed path holds it unbounded: taken again at half of it while bytes flow. */
    const val WAKE_TIMEOUT_MILLIS = 10 * 60_000L

    /** Waits for what the job's constraints watch: the job runs again once they are met, so the row can run. */
    private val NEED_WAITS = setOf(WAITING_FOR_WIFI, WAITING_FOR_NETWORK, WAITING_TO_CHARGE)

    /** States that hold the whole queue until Resume (L380). */
    private val HOLDS = setOf(DownloadState.Paused, DownloadState.Stopped)

    /** Nothing of downloads runs on a TV (W108, L369): the runner, both hosts, the start's schedule, the receiver, the notifications. */
    fun runs(television: Boolean): Boolean = !television

    /** The host for API level [sdk] (L334): the job from 34, the service below; the service on any level with the debug [override] (L336). */
    fun hostFor(sdk: Int, override: Boolean): DownloadHost = if (override || sdk < JOB_SDK) DownloadHost.Service else DownloadHost.Job

    /** What a download needs, as the settings say (W110, L365). */
    fun needs(prefs: DownloadPrefs): DownloadNeeds = DownloadNeeds(unmetered = prefs.unmeteredOnly, charging = prefs.whileCharging)

    /** The words of a wait for the network the [needs] ask for. */
    fun waitingForNetwork(needs: DownloadNeeds): String = if (needs.unmetered) WAITING_FOR_WIFI else WAITING_FOR_NETWORK

    /** Whether a row Waiting for [reason] waits for what the job's constraints and the service's callbacks watch (L388). */
    fun waitsForNeed(reason: String?): Boolean = reason in NEED_WAITS

    /** What a stop of the job means (onStopJob, API 34 on), and whether onStopJob asks JobScheduler to run it again (L335). */
    sealed interface JobStop {
        val reschedule: Boolean

        /** A need is no longer met: the item waits, saying [words], and the job runs again once it is. */
        data class Wait(val words: String) : JobStop {
            override val reschedule get() = true
        }

        /** A run's time limit, or the system's own reasons: the item stays as it is, and the job runs again. */
        data object Again : JobStop {
            override val reschedule get() = true
        }

        /** The user stopped it from Android: "Stopped by Android", held until Resume (L359); not run again. */
        data object Stopped : JobStop {
            override val reschedule get() = false
        }

        /** The app cancelled it itself (Pause, Cancel, nothing left to run): the rows are as the app left them; not run again. */
        data object Cancelled : JobStop {
            override val reschedule get() = false
        }
    }

    /** What the job's stop [reason] (JobParameters' STOP_REASON_…) means for a download that [needs] what it needs. */
    // The reasons are Android 12's, and only the job hands one over, which is Android 14's.
    @SuppressLint("InlinedApi")
    fun onStop(reason: Int, needs: DownloadNeeds): JobStop = when (reason) {
        JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY -> JobStop.Wait(waitingForNetwork(needs))
        JobParameters.STOP_REASON_CONSTRAINT_CHARGING -> JobStop.Wait(WAITING_TO_CHARGE)
        JobParameters.STOP_REASON_USER -> JobStop.Stopped
        JobParameters.STOP_REASON_CANCELLED_BY_APP -> JobStop.Cancelled
        // A timeout (Android 16's abandoned one too), a preemption, the device's state, quotas, standby, a restriction, and
        // any reason a later Android adds: the job runs again, and the item goes on from its safe offset.
        else -> JobStop.Again
    }

    /**
     * The rows that can run, in [rows]' order (L388): Queued; Waiting for a network or for charging, which the job's
     * constraints and the service's callbacks wait for; and Running or Resolving, as a kill left them. None while a row
     * is Paused or Stopped, which hold the whole queue (L380). The waits for the provider and for storage end the host:
     * Save or Resume makes them Queued again, and a storage wait can run again at the app's start once its folder is there
     * ([storageBack]).
     */
    fun runnable(rows: List<Download>, storageBack: Boolean = false): List<Download> {
        if (rows.any { it.state in HOLDS }) return emptyList()
        return rows.filter { row ->
            when (row.state) {
                DownloadState.Queued, DownloadState.Resolving, DownloadState.Running -> true
                DownloadState.Waiting -> row.reason in NEED_WAITS || (storageBack && row.reason == STORAGE_UNAVAILABLE)
                else -> false
            }
        }
    }

    /**
     * Whether the boot and update receiver is on (L375): while the [host] is the service and something can run. A TV, a
     * phone whose host is the job (JobScheduler persists it) and a phone with nothing to run are never woken at boot.
     */
    fun receiverOn(television: Boolean, host: DownloadHost, rows: List<Download>): Boolean =
        runs(television) && host == DownloadHost.Service && runnable(rows).isNotEmpty()

    /** Whether the service still waits in place for its need after [waitedMillis] (L334): up to an hour, then it stops. */
    fun serviceWaits(waitedMillis: Long): Boolean = waitedMillis < SERVICE_WAIT_CAP_MILLIS

    /**
     * The notification the service leaves once its wait has run out (Android 8 to 13): the row's title, and a tap that opens
     * the app and resumes, a notification's tap being a start Android allows (Words).
     */
    fun tapToResume(row: Download): NotificationWords = NotificationWords(title(row), "Paused: no connection · Tap to resume", null)

    /** What asks the host to start (L388), always from the visible app. */
    enum class ScheduleCause { Download, Resume, ProviderSaved, SwitchChanged, AppStart }

    /**
     * Whether the app schedules the host now (L335): only from the [visible] app (Android refuses a user-initiated job from
     * the background, C4), only with something [runnable], and only while no host's walk will take it up ([walking]: the
     * runner's [DownloadRunner.walking], false as soon as a walk has found nothing more to run, before its job ends) and no
     * job is [pending], as a schedule stops a running job of the same id (C5): one whose walk has ended is stopped with
     * nothing left to do. A changed Wi-Fi or charging switch schedules all the same: a pending job takes the new needs, and
     * a running item stops at its safe offset and goes on under them. Never on a TV.
     */
    fun schedules(cause: ScheduleCause, television: Boolean, visible: Boolean, runnable: Boolean, walking: Boolean, pending: Boolean): Boolean = when {
        !runs(television) || !visible || !runnable -> false
        cause == ScheduleCause.SwitchChanged -> true
        else -> !walking && !pending
    }

    /** Whether the [host] holds a wake lock of its own: the service does; the job's is the system's, which vitals don't count. */
    fun ownWakeLock(host: DownloadHost): Boolean = host == DownloadHost.Service

    /** The service's partial wake lock, by the cast service's rule: held while bytes flow, let go 10 s after the last. */
    fun wakeLock(): CastWakeLock = CastWakeLock(WAKE_LET_GO_MILLIS, WAKE_TIMEOUT_MILLIS)

    /**
     * What [row]'s notification says (L358), [written] bytes of it written at [bytesPerSecond] (null: not known yet), with
     * [more] queued behind it: its title, the show and its episode or the film, as the row keeps them, never the release's
     * name; its state's words, with how far it is where it is under way; and what else waits. Null for a removed one, which
     * is hidden at once (L379).
     */
    fun words(row: Download, written: Long = row.safeOffset, bytesPerSecond: Long? = null, more: Int = 0): NotificationWords? {
        val total = row.size ?: row.indexerSize
        fun far(words: String) = if (total > 0) "$words · ${progress(written, total)}" else words
        val text = when (row.state) {
            DownloadState.Queued -> "Queued"
            DownloadState.Resolving -> row.reason ?: FINDING_RELEASES
            // While the writer backs off, why, beside how far it is.
            DownloadState.Running -> row.reason?.let(::far) ?: listOfNotNull(far("Downloading"), timeLeft(total - written, bytesPerSecond).takeIf { total > 0 }).joinToString(" · ")
            DownloadState.Paused -> far("Paused")
            DownloadState.Waiting -> row.reason?.let { if (it in NEED_WAITS) far(it) else it } ?: far("Waiting")
            DownloadState.Stopped -> STOPPED_BY_ANDROID
            DownloadState.Done -> listOfNotNull("Downloaded", missing(row.gaps.size), formatSize(total)).joinToString(" · ")
            DownloadState.Failed -> listOfNotNull("Couldn't download it.", failure(row)?.let { if (it.endsWith('.')) it else "$it." }).joinToString(" ")
            DownloadState.Deleting -> return null
        }
        return NotificationWords(title(row), text, if (more > 0) "$more more queued" else null)
    }

    /** Resolve's [step] on its [attempt] of [of] (L340): "Trying release 2 of 3 · Checking availability" after the first. */
    fun resolving(step: String, attempt: Int, of: Int): String = if (attempt > 1) "Trying release $attempt of $of · $step" else step

    /** "Harbor Lights · S02E04", or the film's title: what the row keeps of its show or film, never its release. */
    private fun title(row: Download): String {
        val season = row.season
        val episode = row.episode
        // The code's digits are the same in every language, as a release's are; the title is the row's as it is.
        return if (row.kind == DownloadKind.Episode && season != null && episode != null) "${row.title} · ${"S%02dE%02d".format(Locale.ROOT, season, episode)}" else row.title
    }

    /**
     * Why [row] failed, as its notification says it: a release that lacked parts (the play sheet's "Release incomplete",
     * and a long gap) by how many releases were tried; any other reason as the row keeps it.
     */
    private fun failure(row: Download): String? = when (row.reason) {
        DownloadResolver.INCOMPLETE -> if (row.tried.size > 1) "Your provider is missing parts of all ${row.tried.size} releases tried" else "Your provider is missing parts of this release"
        else -> row.reason
    }

    private fun missing(parts: Int): String? = when (parts) {
        0 -> null
        1 -> "1 part missing"
        else -> "$parts parts missing"
    }

    /**
     * "0.5 of 1.2 GB": [done] in [total]'s unit, as [formatSize] says [total]: whole KB under 1 MB, MB with one decimal under
     * 10 MB, whole MB under 1 GB, then GB with one decimal, each rounded half up.
     */
    internal fun progress(done: Long, total: Long): String {
        val whole = formatSize(total)
        val (unit, decimals) = when {
            whole.endsWith(" KB") -> 1_000L to false
            whole.endsWith(" GB") -> 1_000_000_000L to true
            (total + 50_000) / 100_000 < 100 -> 1_000_000L to true
            else -> 1_000_000L to false
        }
        val amount = if (decimals) "%.1f".format((done.coerceIn(0, total) + unit / 20) / (unit / 10) / 10.0) else "%d".format((done.coerceIn(0, total) + unit / 2) / unit)
        return "$amount of $whole"
    }

    /** "2 min left", "1 h 5 min left": [remaining] bytes at [bytesPerSecond], in whole minutes rounded up; null with no rate yet. */
    internal fun timeLeft(remaining: Long, bytesPerSecond: Long?): String? {
        if (bytesPerSecond == null || bytesPerSecond <= 0) return null
        val perMinute = bytesPerSecond * 60
        val minutes = (remaining.coerceAtLeast(0) + perMinute - 1) / perMinute
        return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min left" else "$minutes min left"
    }

    /** The first API level with user-initiated data transfer jobs: Android 14. */
    private const val JOB_SDK = 34
}
