package io.skjaere.usenet.app.plugins

/** One loaded instance of a plugin: its [id], its version, and the [key] that tells it from another load of the same. */
class PluginVersion(val id: String, val versionCode: Long, val key: Long) {
    // Names the plugin by its id and version alone, as every log line does.
    override fun toString(): String = "$id $versionCode"
}

/**
 * The host's calls into a plugin's code outside composition (L619): its tab(), close(), and later a play's releases() and
 * file picker. Each runs guarded: one that throws, an Exception or a LinkageError (a class the plugin needs that isn't
 * the one it was built against), is handed to [failed], which turns that version off "after an error", and the call
 * answers what it would had the plugin given nothing. NZStreamer goes on. An Error of the process's own (out of memory)
 * isn't the plugin's to catch, and goes on up. [call] doesn't suspend, so a CancellationException thrown in it is the
 * plugin's own throw, never a coroutine's cancellation: it counts as an error too.
 */
class PluginCalls(private val failed: (version: PluginVersion, what: String, e: Throwable) -> Unit) {
    fun <T> call(version: PluginVersion, what: String, nothing: T, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        failed(version, what, e)
        nothing
    } catch (e: LinkageError) {
        failed(version, what, e)
        nothing
    }
}
