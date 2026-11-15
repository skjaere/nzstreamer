package io.skjaere.usenet.app.download

import io.skjaere.usenet.data.Clock
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

/**
 * What the downloads' trace says ([DownloadDebug.trace]), in the debug build alone: ids, offsets, sizes and causes, never a
 * title, a release or a host. The two-phase device tests read it for what each boundary did (L367).
 */
sealed interface DownloadTrace {
    /** What started a host. */
    enum class StartCause(val word: String) {
        /** JobScheduler ran the job: scheduled by the app, rescheduled after a stop, or restored after a reboot. */
        Job("job"),

        /** The boot or update receiver started the service (L375). */
        BootReceiver("boot"),

        /** Android started the service again after its process died (START_STICKY). */
        StickyRestart("sticky"),

        /** The watchdog job started the service again (API 26 to 30, or the override). */
        Watchdog("watchdog"),

        /** The visible app started the service. */
        Activity("activity"),
    }

    /** What reconcile did with a row a host had left Running or Resolving (L359). */
    enum class Decision(val word: String) {
        /** The user stopped the app since the row's last update: "Stopped by Android". */
        Stopped("stopped"),

        /** Any other end (a kill, a reboot, an update): left for the host to go on with. */
        LeftForHost("left"),
    }

    /** The trace's line for this, after its time. */
    val line: String

    /** A [host] started, by [cause]. */
    data class HostStart(val host: DownloadHost, val cause: StartCause) : DownloadTrace {
        override val line get() = "start host=${host.name.lowercase()} cause=${cause.word}"
    }

    /** Reconcile's [decision] for the row [id], by the process's last exit: its reason (ApplicationExitInfo's) and its time; none below API 30. */
    data class Reconciled(val id: String, val decision: Decision, val exitReason: Int?, val exitAt: Long?) : DownloadTrace {
        override val line get() = "reconcile id=$id decision=${decision.word} " + (exitReason?.let { "exit=$it" + exitAt?.let { at -> " exit-at=$at" }.orEmpty() } ?: "exit=none")
    }

    /** The row [id]'s stream opened again at [offset], its safe offset. */
    data class Reopen(val id: String, val offset: Long) : DownloadTrace {
        override val line get() = "reopen id=$id offset=$offset"
    }

    /** The row [id]'s progress record, at [offset] of [size]: traced at each whole percent, so the tests see a transfer under way. */
    data class Progress(val id: String, val offset: Long, val size: Long) : DownloadTrace {
        override val line get() = "progress id=$id offset=$offset size=$size"
    }

    /** The row [id]'s file is complete, [size] bytes. */
    data class Completed(val id: String, val size: Long) : DownloadTrace {
        override val line get() = "done id=$id size=$size"
    }
}

/**
 * The debug build's seams for the downloads' device tests (L336, L367), files in filesDir's `downloads/` beside the
 * downloads' folders, which reconcile leaves alone: [serviceHost] (`.host`), [throttle] (`.throttle`) and the [trace]
 * (`.trace`). Read and written only in a debug build ([of]'s flag, BuildConfig.DEBUG): a release, bench or beta build gets
 * [Off], which holds no path and touches no file. A device test writes the first two, so they last through a kill and a
 * reboot, as the trace does.
 */
sealed interface DownloadDebug {
    /** The host override (L336): the service on any API level while `.host` is there, whatever it holds. */
    val serviceHost: Boolean

    /** The writer's bytes a second (item 6), as `.throttle` says them; null with none, or anything but a number above 0. */
    val throttle: Long?

    /** Appends [event]'s line to `.trace`, after the wall-clock time in milliseconds. */
    fun trace(event: DownloadTrace)

    /** A release, bench or beta build's: no override, no throttle, no trace. */
    data object Off : DownloadDebug {
        override val serviceHost get() = false
        override val throttle: Long? get() = null

        override fun trace(event: DownloadTrace) = Unit
    }

    /**
     * A debug build's, in [folder], the trace's lines timed by [clock]. Progress is traced at each whole percent of a
     * download, and the trace starts again once past [cap] bytes, so a debug build's never grows unbounded.
     */
    class On(private val folder: File, private val clock: Clock, private val cap: Long = TRACE_CAP) : DownloadDebug {
        // The whole percent traced last, by download.
        private val percents = HashMap<String, Long>()

        override val serviceHost get() = File(folder, HOST).isFile

        override val throttle: Long?
            get() = try {
                File(folder, THROTTLE).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()?.takeIf { it > 0 }
            } catch (e: IOException) {
                log.warn("Couldn't read the downloads' throttle", e)
                null
            }

        @Synchronized
        override fun trace(event: DownloadTrace) {
            if (event is DownloadTrace.Progress) {
                val percent = if (event.size > 0) event.offset * 100 / event.size else 0
                if (percents.put(event.id, percent) == percent) return
            }
            try {
                folder.mkdirs()
                val file = File(folder, TRACE)
                val line = "${clock.now()} ${event.line}\n"
                if (file.length() + line.length > cap) file.writeText(line) else file.appendText(line)
            } catch (e: IOException) {
                // A trace that can't be written never stops a download.
                log.warn("Couldn't write the downloads' trace", e)
            }
        }
    }

    companion object {
        const val HOST = ".host"
        const val THROTTLE = ".throttle"
        const val TRACE = ".trace"

        /** The trace's size at most: past it, it starts again. */
        const val TRACE_CAP = 1L shl 20

        /** The seams of a build whose BuildConfig.DEBUG is [debug], in [filesDir]'s `downloads/`. */
        fun of(filesDir: File, debug: Boolean, clock: Clock = Clock.System): DownloadDebug =
            if (debug) On(File(filesDir, DownloadStorage.DIR), clock) else Off
    }
}

private val log = LoggerFactory.getLogger(DownloadDebug::class.java)
