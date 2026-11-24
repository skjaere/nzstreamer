package io.skjaere.usenet.plugin

import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * What NZStreamer offers one loaded instance of a plugin, and all of it a plugin reaches through the API: no setting,
 * indexer, provider, database or route of the host's crosses it. Keys, addresses and indexer data stay in the host;
 * a plugin sees releases as names and opaque handles.
 */
interface PluginHost {
    /** The plugin's id, as its package and the plugin list name it: app.nzstreamer.plugin.<id>. */
    val id: String

    /** The user's indexers, searched and ranked by the host. */
    val search: PluginSearch

    /** Plays an item from releases the plugin found, on the device the user plays on. */
    val play: PluginPlay

    /** What the user watched of the plugin's items, kept by the host under the plugin's id. */
    val watch: PluginWatch

    /** The plugin's own secrets (an address's key), kept encrypted by the host under the plugin's id. */
    val secrets: PluginSecrets

    /** GET requests to an address the plugin is set up with. */
    val http: PluginHttp

    /**
     * The plugin's own folder for what it keeps, in NZStreamer's storage. Kept across updates; emptied before a new
     * install, deleted on Remove, and never backed up or copied to a new device.
     */
    val dataDir: File

    /** The plugin's own cache folder, which Android may empty when space runs low; deleted on Remove. */
    val cacheDir: File

    /**
     * The instance's scope: the main thread (Dispatchers.Main.immediate) and a SupervisorJob, so one coroutine's failure
     * cancels no other. An exception that escapes it is logged, recorded, and turns the version off ("Turned off after an
     * error") rather than close NZStreamer. Cancelled as the instance is let go, before [NzPlugin.close].
     */
    val scope: CoroutineScope

    /** How exposed [url] leaves what is sent to it, judged as the host judges an indexer's address, from the address alone. */
    fun addressSafety(url: String): AddressSafety
}
