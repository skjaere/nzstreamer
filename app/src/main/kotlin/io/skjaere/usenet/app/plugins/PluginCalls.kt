package io.skjaere.usenet.app.plugins

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One loaded instance of a plugin: its [id], its version, and the [key] that tells it from another load of the same. */
class PluginVersion(val id: String, val versionCode: Long, val key: Long) {
    // Names the plugin by its id and version alone, as every log line does.
    override fun toString(): String = "$id $versionCode"
}

/**
 * The host's calls into a plugin's code outside composition (L619): its tab(), close(), a play's releases() and its file
 * picker. Each runs guarded: whatever it throws that is the plugin's ([isPluginsOwn]: an exception, a LinkageError, an
 * Error such as the NotImplementedError Kotlin's TODO() throws) is handed to [failed], which turns that version off "after
 * an error", and the call answers what it would had the plugin given nothing. NZStreamer goes on. [call] doesn't suspend,
 * so a CancellationException thrown in it is the plugin's own throw, never a coroutine's cancellation: it counts as an
 * error too.
 */
class PluginCalls(private val failed: (version: PluginVersion, what: String, e: Throwable) -> Unit) {
    fun <T> call(version: PluginVersion, what: String, nothing: T, block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        if (!e.isPluginsOwn()) throw e
        failed(version, what, e)
        nothing
    }

    /**
     * [call] for plugin code that suspends (a play's releases()). The caller's own cancellation goes on up, and is nobody's
     * error; a CancellationException the plugin's code throws while its caller goes on (a timeout of its own) is the
     * plugin's failure, as any other throw.
     */
    suspend fun <T> calling(version: PluginVersion, what: String, nothing: T, block: suspend () -> T): T = try {
        block()
    } catch (e: Throwable) {
        if (e is CancellationException) currentCoroutineContext().ensureActive()
        if (!e.isPluginsOwn()) throw e
        failed(version, what, e)
        nothing
    }
}

/**
 * Whether this, thrown by a plugin's code, is the plugin's to answer for: everything but the process's own failures, out
 * of memory and the VM's internal errors, which go on up. A stack overflow is the plugin's: its own frames unwind, and the
 * thread goes on.
 */
internal fun Throwable.isPluginsOwn(): Boolean = this !is VirtualMachineError || this is StackOverflowError
