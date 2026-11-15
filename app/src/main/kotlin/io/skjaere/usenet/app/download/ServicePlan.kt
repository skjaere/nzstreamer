package io.skjaere.usenet.app.download

import android.content.Intent
import io.skjaere.usenet.app.download.DownloadHostRules.ScheduleCause
import io.skjaere.usenet.data.Download

/**
 * The downloads' service as plain rules (item 10, L334-L336, L375, L388): where a start of it runs the queue, when the
 * visible app starts it, what it does as its needs come and go (walk, wait in place up to an hour, give the wait up, stop),
 * when the boot receiver is on and which broadcasts start it, the watchdog's job and what its run does on each API level,
 * and the Wi-Fi lock. Pure, so a JVM test reads every case, as [DownloadHostRules] is; [DownloadService],
 * [DownloadBootReceiver] and [DownloadWatchdogService] only wire it. Nothing on a TV.
 */
object ServicePlan {
    /** The watchdog's job id, on the default scheduler: the app schedules no other job there (the downloads' own is in its namespace). */
    const val WATCHDOG_ID = 2

    /** The first API level that refuses a foreground service started from the background, a job's included: Android 12. */
    private const val BACKGROUND_START_LIMIT_SDK = 31

    /** The first API level with user-initiated jobs, whose host is the job unless the override says the service (L336). */
    private const val JOB_SDK = 34

    /** The broadcasts that start the service again (L375): the device's boot, and the app's own update. */
    private val BOOT_ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)

    /** Whether a start of the service runs the queue: on a phone whose host is the service (API 26 to 33, or the debug override). */
    fun runsHere(television: Boolean, sdk: Int, override: Boolean): Boolean =
        DownloadHostRules.runs(television) && DownloadHostRules.hostFor(sdk, override) == DownloadHost.Service

    /**
     * Whether the visible app starts the service for [cause] (L335, L388), as it schedules the job: only where the service is
     * the host, from the [visible] app, with something [runnable], and only while no walk under way will take it up
     * ([walking]) and no service stands ready to ([standing]: started and not yet walking, or waiting in place for its
     * need). A changed switch starts it all the same, as the job takes the new needs: a service under way reads them itself.
     */
    fun starts(cause: ScheduleCause, television: Boolean, sdk: Int, override: Boolean, visible: Boolean, runnable: Boolean, walking: Boolean, standing: Boolean): Boolean =
        runsHere(television, sdk, override) && DownloadHostRules.schedules(cause, television, visible, runnable, walking, pending = standing)

    /** What holds a download back now: nothing, the network its needs ask for, or the charger. */
    sealed interface Need {
        data object Met : Need

        /** No network the needs ask for: [words] say which ("Waiting for Wi-Fi", "Waiting for a network"). */
        data class Network(val words: String) : Need

        /** On the network, off the charger, with Only while charging on. */
        data object Charger : Need
    }

    /** What holds a download that [needs] what it needs, with a [network] they ask for there or not, and the [charging] or not. */
    fun need(needs: DownloadNeeds, network: Boolean, charging: Boolean): Need = when {
        !network -> Need.Network(DownloadHostRules.waitingForNetwork(needs))
        needs.charging && !charging -> Need.Charger
        else -> Need.Met
    }

    /** What the service does next. */
    sealed interface Step {
        /** Its need met: it walks the queue. */
        data object Walk : Step

        /**
         * It waits in place for [need], at most [forMillis] more, the download it would run saying why ([words]); the
         * watchdog is scheduled while it waits for the network ([watchdog]), which is what the watchdog's run speaks of.
         */
        data class Wait(val need: Need, val forMillis: Long) : Step {
            val words: String
                get() = when (need) {
                    is Need.Network -> need.words
                    Need.Charger -> DownloadHostRules.WAITING_TO_CHARGE
                    Need.Met -> error("a met need isn't waited for")
                }

            val watchdog: Boolean get() = need is Need.Network
        }

        /**
         * The hour is up (L334): the service stops, saying "Paused: no connection · Tap to resume" after the network's wait
         * ([tapToResume]), or what the status notification says after the charger's.
         */
        data class GiveUp(val tapToResume: Boolean) : Step

        /** Nothing can run (all done or failed, the queue held, a wait that ends the host, L388): the service stops. */
        data object Stop : Step
    }

    /** The service's next step, with something [runnable] or not, held by [need], having waited [waitedMillis] for it so far. */
    fun step(runnable: Boolean, need: Need, waitedMillis: Long): Step = when {
        !runnable -> Step.Stop
        need == Need.Met -> Step.Walk
        DownloadHostRules.serviceWaits(waitedMillis) -> Step.Wait(need, DownloadHostRules.SERVICE_WAIT_CAP_MILLIS - waitedMillis)
        else -> Step.GiveUp(tapToResume = need is Need.Network)
    }

    /** Whether the boot and update receiver is on (L375), by the host on API level [sdk] with or without the [override]. */
    fun receiverOn(television: Boolean, sdk: Int, override: Boolean, rows: List<Download>): Boolean =
        DownloadHostRules.receiverOn(television, DownloadHostRules.hostFor(sdk, override), rows)

    /** Whether the broadcast [action] starts the service (L375): the boot or the app's update, where it is the host, with something [runnable]. */
    fun bootStarts(action: String?, television: Boolean, sdk: Int, override: Boolean, runnable: Boolean): Boolean =
        action in BOOT_ACTIONS && runsHere(television, sdk, override) && runnable

    /**
     * The watchdog's job: [id] 2 on the default scheduler ([namespace] none, on every level: namespaces are Android 14's),
     * a regular job, not user-initiated, [persisted] so a reboot keeps it, needing the network the downloads need
     * ([unmetered] or any).
     */
    data class Watchdog(
        val unmetered: Boolean,
        val id: Int = WATCHDOG_ID,
        val namespace: String? = null,
        val userInitiated: Boolean = false,
        val persisted: Boolean = true,
    )

    /** The watchdog for downloads that [needs] what they need, on API level [sdk]: the same on every level. */
    @Suppress("UNUSED_PARAMETER")
    fun watchdog(sdk: Int, needs: DownloadNeeds): Watchdog = Watchdog(unmetered = needs.unmetered)

    /** What a run of the watchdog does. */
    enum class Wake {
        /** Starts the service again: Android 8 to 11 allow it from a job, and the override's levels try. */
        StartService,

        /** Posts "Paused: no connection · Tap to resume": Android 12 and 13 refuse it from a job, and a tap is a start they allow. */
        TapToResume,

        /** The service is up and goes on by itself, nothing can run, or it isn't the host. */
        Nothing,
    }

    /**
     * What the watchdog's run does (L334), its network back: nothing while the service is [up] or nothing is [runnable];
     * else it starts the service again on API 26 to 30, and on 34 and later with the override, and says tap to resume on
     * 31 to 33.
     */
    fun onWatchdog(television: Boolean, sdk: Int, override: Boolean, up: Boolean, runnable: Boolean): Wake = when {
        !runsHere(television, sdk, override) || up || !runnable -> Wake.Nothing
        sdk in BACKGROUND_START_LIMIT_SDK until JOB_SDK -> Wake.TapToResume
        else -> Wake.StartService
    }

    /** Whether the service holds its Wi-Fi lock: while bytes flow ([writing], as its wake lock is held) on Wi-Fi. */
    fun wifiLock(writing: Boolean, onWifi: Boolean): Boolean = writing && onWifi
}
