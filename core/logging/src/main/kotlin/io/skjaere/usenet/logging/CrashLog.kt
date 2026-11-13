package io.skjaere.usenet.logging

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The in-app log as it was when the app last crashed, with the crash's stack trace, kept in [file]
 * until deleted (a later crash replaces it). [header] goes at the top: what app and device it was,
 * as it is said when the crash is written (some of it is learnt after start).
 */
class CrashLog(private val file: File, private val header: () -> String) {
    constructor(file: File, header: String) : this(file, { header })

    /**
     * Writes the log as an uncaught exception ends the process, then hands the exception on to the
     * handler there was before (Android's, which ends the process), whether or not the log could be
     * written.
     */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                record(thread, e)
            } catch (_: Exception) {
                // Nothing more can be done for the log as the process ends.
            } finally {
                previous?.uncaughtException(thread, e)
            }
        }
    }

    /**
     * Writes the log of [e] on [thread] now, on the crashing thread, main or not: the process is
     * about to end, so there's no later to do it in.
     */
    fun record(thread: Thread, e: Throwable) {
        val zone = ZoneId.systemDefault()
        // What crashed first, then the log that led up to it.
        val text = buildString {
            append(header()).append("\n\n")
            append("Crashed ${stamp.format(Instant.now().atZone(zone))} on thread ${thread.name}:\n")
            append(redactSecrets(e.stackTraceToString()).trimEnd()).append("\n\n")
            append("The log before it:\n")
            LogBuffer.snapshot().forEach { append(it.text(zone)).append('\n') }
        }
        file.parentFile?.mkdirs()
        val partial = File(file.path + ".tmp")
        partial.writeText(text)
        if (!partial.renameTo(file)) {
            partial.delete()
            file.writeText(text)
        }
    }

    /** The kept log, or null when there is none. */
    suspend fun read(): String? = withContext(Dispatchers.IO) { file.takeIf { it.isFile }?.readText() }

    /** When the kept log was written (epoch millis), or null when there is none. */
    suspend fun writtenAt(): Long? = withContext(Dispatchers.IO) { file.takeIf { it.isFile }?.lastModified() }

    suspend fun delete() {
        withContext(Dispatchers.IO) { file.delete() }
    }

    private companion object {
        val stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    }
}
