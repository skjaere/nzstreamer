package io.skjaere.usenet.app.download

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Service
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.casting.CastWakeLock
import io.skjaere.usenet.app.casting.ForegroundOwed
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.download.DownloadTrace.StartCause
import io.skjaere.usenet.app.download.ServicePlan.Need
import io.skjaere.usenet.app.download.ServicePlan.Step
import io.skjaere.usenet.data.Download
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/**
 * The downloads' host on Android 8 to 13 (L334, L388): the app's own dataSync foreground service, one for the whole queue.
 * The visible app starts it ([DownloadHosts]), as the boot and update receiver ([DownloadBootReceiver], L375) and the
 * watchdog ([DownloadWatchdogService]) do; START_STICKY asks Android to start it again after its process dies. On Android
 * 14 and later the job is the host ([DownloadJobService]), and the service runs only by the debug build's override (L336).
 *
 * A start goes into the foreground within Android's 5 s with the queue's ongoing notification
 * ([DownloadNotifications.ongoing]), passing through it unseen where there is nothing to show ([ForegroundOwed], as the
 * cast's service does); then the runner walks the queue ([DownloadRunner.run]) while its needs are met: the network they
 * ask for, watched with a network callback for the job's own request, and the charger, from the power broadcasts while
 * Only while charging is on. When one goes, the walk stops at its safe offset and the service waits in place, in the
 * foreground, the download saying what it waits for ("Waiting for Wi-Fi"), and the watchdog scheduled while it waits for
 * the network; back, it goes on. After an hour of waiting it gives the wait up: "Paused: no connection · Tap to resume"
 * after the network's. Its partial wake lock is held only while bytes flow ([CastWakeLock]'s rule), and a Wi-Fi lock
 * while they flow on Wi-Fi. Once nothing can run (all done or failed, the queue held, a wait that ends the host) it leaves
 * the foreground, its notification with it, the status notification says what is left
 * ([DownloadNotifications.hostEnded]), and it stops.
 *
 * Each start that runs the queue is traced with its cause (the app, the boot receiver, a sticky restart, the watchdog).
 * Its log lines name the downloads by id and the causes' kinds, never a title, a release or a host. Nothing on a TV (L369),
 * and nothing where the job is the host. Everything here is on the main thread.
 */
class DownloadService : Service() {
    /** Where the service is: the process's one. */
    enum class Phase {
        /** Not running the queue. */
        None,

        /** Started, before its walk: it will take up what is queued. */
        Starting,

        /** Its walk runs the queue. */
        Walking,

        /** Waiting in place for its need: it will take up what is queued once the need is back. */
        Waiting,

        /** Its run ends: a start now runs the queue again after it. */
        Ending,
    }

    /** A start's foreground: [afterMillis] after the start, as [type] (ServiceInfo's FOREGROUND_SERVICE_TYPE_…; none below Android 10). */
    data class Foreground(val afterMillis: Long, val type: Int)

    /** What the needs ask for, and what the device has now: the network they ask for, on Wi-Fi or not, and the charger. */
    private data class Conditions(val needs: DownloadNeeds, val network: Boolean, val wifi: Boolean, val charging: Boolean) {
        val need: Need get() = ServicePlan.need(needs, network, charging)
    }

    /** The networks the needs' request matches now: whether any does, and whether one is Wi-Fi. */
    private data class Networks(val met: Boolean, val wifi: Boolean)

    /** Whether a start may still want the foreground: Android takes the app down if the service stops before it gets there. */
    private val owed = ForegroundOwed()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The run of the queue under way, from a start to its end. */
    private var life: Job? = null

    /** The run whose end is under way: a start now runs the queue again after it. */
    private var endingLife: Job? = null

    /** A start came while the run under way had no walk to take up what it was for: the run's end runs the queue again. */
    private var asked: StartCause? = null

    /** The last start's id: the service stops only if no start came after it. */
    private var lastStart = 0

    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        owed.created()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Any start may be one for the foreground: the app's, the receiver's or the watchdog's.
        owed.started(inForeground())
        lastStart = startId
        val container = application.container
        if (!ServicePlan.runsHere(container.television, Build.VERSION.SDK_INT, container.downloadDebug.serviceHost)) {
            log.info("Downloads: the service starts where it isn't the host: it stops")
            if (life == null) stopNow(container)
            return START_NOT_STICKY
        }
        val cause = causeOf(intent)
        val current = life
        if (current != null && current !== endingLife) {
            // Under way: a walk takes up what is queued, and the run reads a switch's new needs itself. With no walk to, its
            // end runs the queue again.
            if (!container.downloads.walking.value) asked = cause
            return START_STICKY
        }
        begin(container, cause)
        return START_STICKY
    }

    /** Android 15's six hours a day for a dataSync service ran out (the override's levels alone): the walk stops, and it ends. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        log.info("Downloads: Android's time for the service ran out")
        asked = null
        life?.cancel()
    }

    override fun onDestroy() {
        destroyed = true
        // A walk under way stops at its safe offset; the run's end says what is left.
        scope.cancel()
        super.onDestroy()
    }

    /** Runs the queue for a start by [cause], after the run whose end is under way. */
    private fun begin(container: AppContainer, cause: StartCause) {
        val previous = life
        val begun = SystemClock.elapsedRealtime()
        phases.value = Phase.Starting
        foregrounds.value = null
        asked = null
        val run = scope.launch(start = CoroutineStart.LAZY) {
            previous?.join()
            live(container, cause, begun, coroutineContext.job)
        }
        life = run
        run.start()
    }

    /**
     * A run: in the foreground with the queue's notification, then the queue walked while its needs are met, then its end
     * ([ended]), whatever ended it.
     */
    private suspend fun live(container: AppContainer, cause: StartCause, begun: Long, me: Job) {
        val runner = container.downloads
        val notifications = container.downloadNotifications
        container.downloadDebug.trace(DownloadTrace.HostStart(DownloadHost.Service, cause))
        log.info("Downloads: the service starts, by {}", cause.word)
        notifications.hostStarted()
        var gaveUp: Step.GiveUp? = null
        try {
            // From the queue as reconcile leaves it; from the table as it is, should reconcile take long, so the foreground
            // still comes within Android's 5 s.
            var rows = withTimeoutOrNull(NOTICE_WAIT_MILLIS) { runner.runnable() }
            if (rows == null) {
                foreground(container, runner.items(), begun)
                rows = runner.runnable()
            }
            if (rows.isEmpty()) {
                log.info("Downloads: nothing to run under the service")
                return
            }
            if (!foreground(container, runner.items(), begun)) return
            gaveUp = coroutineScope {
                val keeping = launch { notifications.keepOngoing(runner.state) }
                try {
                    queue(container, me)
                } finally {
                    // Before it leaves the foreground, so no update of its notification lands after.
                    keeping.cancelAndJoin()
                }
            }
        } finally {
            withContext(NonCancellable) { ended(container, gaveUp, me) }
        }
    }

    /**
     * Runs the queue while its needs are met, waiting in place while they aren't, an hour at most (L334): the wait given up,
     * or null once nothing can run (L388). The needs and the locks are followed for as long as it runs.
     */
    private suspend fun queue(container: AppContainer, me: Job): Step.GiveUp? = coroutineScope {
        val runner = container.downloads
        val conditions = conditions(container).stateIn(this)
        launch { holdLocks(runner, conditions) }
        try {
            steps(runner, conditions, me)
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    /** The run [me]'s steps, as [ServicePlan.step] decides each from the queue and the [conditions] now. */
    private suspend fun steps(runner: DownloadRunner, conditions: StateFlow<Conditions>, me: Job): Step.GiveUp? {
        // When the wait began (elapsedRealtime, the device's sleep counted), and the needs the watchdog is scheduled for.
        var since: Long? = null
        var watching: DownloadNeeds? = null
        while (true) {
            val now = conditions.value
            val need = now.need
            val waited = since?.let { SystemClock.elapsedRealtime() - it } ?: 0
            when (val step = ServicePlan.step(runner.runnable().isNotEmpty(), need, waited)) {
                Step.Stop -> {
                    ending(me)
                    return null
                }
                is Step.GiveUp -> {
                    log.info("Downloads: the service waited {} min: it stops", waited / 60_000)
                    ending(me)
                    return step
                }
                Step.Walk -> {
                    if (since != null) log.info("Downloads: the service's need is back after {} s", waited / 1000)
                    since = null
                    if (watching != null) {
                        watchdog(null)
                        watching = null
                    }
                    phases.value = Phase.Walking
                    // The walk takes up what is queued from here.
                    asked = null
                    if (!walk(runner, conditions)) {
                        // Ended by itself: nothing more can run (L388).
                        ending(me)
                        return null
                    }
                }
                is Step.Wait -> {
                    if (since == null) {
                        since = SystemClock.elapsedRealtime()
                        log.info("Downloads: the service waits in place: {}", step.words)
                    }
                    phases.value = Phase.Waiting
                    runner.hostWaits(step.words)
                    // Scheduled while it waits for the network, for the needs it waits under.
                    val wants = now.needs.takeIf { step.watchdog }
                    if (wants != watching) {
                        watchdog(wants)
                        watching = wants
                    }
                    // Until the need changes or nothing can run, a minute at most, so the hour is read again after the
                    // device slept: the main thread's clock doesn't count its sleep.
                    withTimeoutOrNull(minOf(step.forMillis, CHECK_MILLIS)) {
                        combine(conditions, runner.state) { c, items -> c.need != need || DownloadHostRules.runnable(items.map { it.row }).isEmpty() }.first { it }
                    }
                }
            }
        }
    }

    /** The run [me] ends now, its walk done or its wait given up: a start from here runs the queue again after it. */
    private fun ending(me: Job) {
        endingLife = me
        if (life === me) phases.value = Phase.Ending
    }

    /**
     * The walk, until it ends by itself (false) or the need goes (true): then it is stopped where it is, its writer
     * recording its safe offset.
     */
    private suspend fun walk(runner: DownloadRunner, conditions: StateFlow<Conditions>): Boolean = coroutineScope {
        var needWent = false
        val walking = launch { runner.run(DownloadHost.Service) }
        val watch = launch {
            conditions.first { it.need != Need.Met }
            needWent = true
            walking.cancel()
        }
        walking.join()
        watch.cancel()
        needWent
    }

    /**
     * The run [me] ended: the watchdog cancelled when nothing is left to run, the foreground left with its notification,
     * the status notification saying what is left (or the wait given up, tap to resume), and the service stopped unless a
     * start came meanwhile.
     */
    private suspend fun ended(container: AppContainer, gaveUp: Step.GiveUp?, me: Job) {
        ending(me)
        val runner = container.downloads
        val notifications = container.downloadNotifications
        var items: List<DownloadRunner.Item> = emptyList()
        var left: List<Download> = emptyList()
        try {
            items = runner.items()
            left = runner.runnable()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Downloads: couldn't read what the service left: {}", e::class.simpleName)
        }
        // The watchdog waits for the network only while something can run.
        if (left.isEmpty()) watchdog(null)
        if (!destroyed) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        try {
            val waiting = left.firstOrNull()
            if (gaveUp?.tapToResume == true && waiting != null) notifications.tapToResume(waiting) else notifications.hostEnded(items)
        } catch (e: Exception) {
            log.warn("Downloads: couldn't say what the service left: {}", e::class.simpleName)
        }
        log.info("Downloads: the service ends, {} to run", left.size)
        // A start came as it ended: the next run follows it.
        if (life !== me) return
        endingLife = null
        val again = asked
        if (again != null && !destroyed) {
            begin(container, again)
            return
        }
        life = null
        phases.value = Phase.None
        if (!destroyed) stopNow(container)
    }

    /**
     * In the foreground with the ongoing notification for [items], as a dataSync service (the type is Android 10's: below
     * it ServiceCompat leaves it out, as the manifest has it): whether Android took it.
     */
    @SuppressLint("InlinedApi")
    private fun foreground(container: AppContainer, items: List<DownloadRunner.Item>, begun: Long): Boolean {
        val notification = container.downloadNotifications.ongoing(items) ?: return false
        try {
            ServiceCompat.startForeground(this, DownloadNotifications.ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: IllegalStateException) {
            // Android 12 on refuses the foreground to a start from the background that no exemption covers (a sticky
            // restart may be one), and 15 once its six hours a day are spent: the run ends, the status notification saying
            // what is left.
            log.info("Downloads: Android refused the service the foreground ({})", e::class.simpleName)
            return false
        }
        owed.seen(inForeground())
        foregrounds.value = Foreground(SystemClock.elapsedRealtime() - begun, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) foregroundServiceType else NO_TYPE)
        return true
    }

    /** Stops the service, through the foreground first where a start may still want it, unless a start came after the last. */
    private fun stopNow(container: AppContainer) {
        if (owed.stopping(inForeground())) passThroughForeground(container)
        stopSelfResult(lastStart)
    }

    /**
     * A start that may want the foreground, with nothing to show: nothing to run, a TV, the job the host. Android ends an
     * app whose service it started for the foreground stops before it gets there, so it gets there once, unseen.
     */
    @SuppressLint("InlinedApi")
    private fun passThroughForeground(container: AppContainer) {
        try {
            ServiceCompat.startForeground(this, PASSING_ID, container.downloadNotifications.passing(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: IllegalStateException) {
            // Refused: past Android's window already, which has said so itself.
            log.info("Downloads: the service couldn't pass through the foreground ({})", e::class.simpleName)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    /** Whether Android has the service in the foreground now. */
    private fun inForeground(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            foregroundServiceType != NO_TYPE
        } else {
            // Before Android 10 a service can't ask; Android still lists an app's own services to it.
            @Suppress("DEPRECATION")
            getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
                .any { it.foreground && it.pid == Process.myPid() && it.service.className == DownloadService::class.java.name }
        }

    /** What the needs ask for and what the device has, as they change: the switches read live, so a changed one applies at once. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun conditions(container: AppContainer): Flow<Conditions> =
        container.settings.downloads.map(DownloadHostRules::needs).distinctUntilChanged().flatMapLatest { needs ->
            combine(networks(needs.unmetered), if (needs.charging) plugged() else flowOf(true)) { networks, charging ->
                Conditions(needs, networks.met, networks.wifi, charging)
            }
        }.distinctUntilChanged()

    /**
     * The networks the downloads' request matches ([DownloadHosts.network], the job's own), [unmetered] or any, as the
     * callbacks say them: only what they are given, as the docs ask. Until they say, the device's network now.
     */
    private fun networks(unmetered: Boolean): Flow<Networks> = callbackFlow {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        // Each network matched, and whether it is Wi-Fi.
        val matched = HashMap<Network, Boolean>()
        fun publish() {
            trySend(synchronized(matched) { Networks(matched.isNotEmpty(), matched.values.any { it }) })
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                synchronized(matched) { matched[network] = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
                publish()
            }

            override fun onLost(network: Network) {
                synchronized(matched) { matched.remove(network) }
                publish()
            }
        }
        val now = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        trySend(Networks(now != null && DownloadHosts.satisfies(now, unmetered), now?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true))
        connectivity.registerNetworkCallback(DownloadHosts.network(unmetered), callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    /** Whether the device is on the charger, from the power broadcasts (L365), and until one comes, as the battery's last one says. */
    private fun plugged(): Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(intent.action == Intent.ACTION_POWER_CONNECTED)
            }
        }
        // The system's own broadcasts reach a receiver no other app can send to.
        val battery = ContextCompat.registerReceiver(this@DownloadService, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        trySend((battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
        val power = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this@DownloadService, receiver, power, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    /**
     * The service's own partial wake lock, held only while bytes flow (CastWakeLock's rule: let go 10 s after the last, and
     * taken with a timeout), and its Wi-Fi lock while they flow on Wi-Fi; both let go when it stops. The bytes are what the
     * downloads' files grew by, as the job reports them ([JobBytes]).
     */
    private suspend fun holdLocks(runner: DownloadRunner, conditions: StateFlow<Conditions>) {
        val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply { setReferenceCounted(false) }
        // Helps the Wi-Fi keep up with the screen off up to Android 13; harmless later, as the cast's (L59).
        @Suppress("DEPRECATION")
        val wifi = applicationContext.getSystemService(WifiManager::class.java)?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_TAG)
            ?.apply { setReferenceCounted(false) }
        val rule = DownloadHostRules.wakeLock()
        val bytes = JobBytes()
        try {
            coroutineScope {
                launch { runner.state.collect(bytes::seen) }
                while (true) {
                    when (rule.tick(SystemClock.elapsedRealtime(), bytes.transferred)) {
                        CastWakeLock.Act.Acquire -> wake.acquire(DownloadHostRules.WAKE_TIMEOUT_MILLIS)
                        CastWakeLock.Act.Release -> if (wake.isHeld) wake.release()
                        null -> Unit
                    }
                    val wanted = ServicePlan.wifiLock(wake.isHeld, conditions.value.wifi)
                    if (wifi != null && wanted != wifi.isHeld) if (wanted) wifi.acquire() else wifi.release()
                    delay(TICK_MILLIS)
                }
            }
        } finally {
            if (wake.isHeld) wake.release()
            wifi?.takeIf { it.isHeld }?.release()
        }
    }

    /** The watchdog scheduled for [needs]' network (L334), or cancelled with none. */
    private fun watchdog(needs: DownloadNeeds?) {
        val scheduler = getSystemService(JobScheduler::class.java)
        try {
            if (needs == null) {
                scheduler.cancel(ServicePlan.WATCHDOG_ID)
            } else {
                scheduler.schedule(DownloadWatchdogService.jobInfo(this, ServicePlan.watchdog(Build.VERSION.SDK_INT, needs)))
                log.info("Downloads: the watchdog waits for {}", if (needs.unmetered) "an unmetered network" else "a network")
            }
        } catch (e: RuntimeException) {
            log.warn("Downloads: couldn't {} the watchdog: {}", if (needs == null) "cancel" else "schedule", e::class.simpleName)
        }
    }

    /** What started the service: the app's, the receiver's and the watchdog's starts say; Android's sticky restart has no intent. */
    private fun causeOf(intent: Intent?): StartCause =
        if (intent == null) StartCause.StickyRestart else intent.getStringExtra(EXTRA_CAUSE)?.let { word -> StartCause.entries.firstOrNull { it.word == word } } ?: StartCause.Activity

    companion object {
        /** The service's partial wake lock's tag, as `dumpsys power` lists it. */
        internal const val WAKE_TAG = "NZStreamer:download"
        private const val WIFI_TAG = "NZStreamer:download"
        private const val EXTRA_CAUSE = "cause"

        /** How long a start waits for reconcile before it goes into the foreground with the table as it is. */
        private const val NOTICE_WAIT_MILLIS = 3_000L

        /** How often the bytes are looked at for the wake lock. */
        private const val TICK_MILLIS = 1_000L

        /** How often a wait reads its hour again. */
        private const val CHECK_MILLIS = 60_000L

        /** Passing through the foreground with nothing to show: never the queue's own notification's id. */
        private const val PASSING_ID = 21_009

        /** No foreground type: what a service out of the foreground has, and what one had before Android 10. */
        private const val NO_TYPE = 0

        private val phases = MutableStateFlow(Phase.None)

        /** Where the service is: the hosts' schedule, the watchdog and a device test read it. */
        val phase: StateFlow<Phase> = phases.asStateFlow()

        /** Whether it stands ready to take up what is queued, started and not yet walking or waiting in place: the app's start isn't needed. */
        val standing: Boolean get() = phase.value == Phase.Starting || phase.value == Phase.Waiting

        /** Whether it runs the queue, or is about to, or waits in place: the watchdog has nothing to do. */
        val up: Boolean get() = phase.value == Phase.Starting || phase.value == Phase.Walking || phase.value == Phase.Waiting

        private val foregrounds = MutableStateFlow<Foreground?>(null)

        /** The last run's foreground, once it had it: a device test's view of Android's 5 s. */
        @VisibleForTesting
        internal val foreground: StateFlow<Foreground?> = foregrounds.asStateFlow()

        /** The start of the service by [cause]: the app's, the boot receiver's or the watchdog's. */
        fun start(context: Context, cause: StartCause): Intent = Intent(context, DownloadService::class.java).putExtra(EXTRA_CAUSE, cause.word)
    }
}

private val log = LoggerFactory.getLogger(DownloadService::class.java)
