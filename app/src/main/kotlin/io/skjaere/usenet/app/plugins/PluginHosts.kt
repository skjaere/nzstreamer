package io.skjaere.usenet.app.plugins

import io.skjaere.usenet.plugin.AddressSafety
import io.skjaere.usenet.plugin.Found
import io.skjaere.usenet.plugin.HttpAnswer
import io.skjaere.usenet.plugin.ItemReleases
import io.skjaere.usenet.plugin.PlayItem
import io.skjaere.usenet.plugin.PluginHost
import io.skjaere.usenet.plugin.PluginHttp
import io.skjaere.usenet.plugin.PluginHttpException
import io.skjaere.usenet.plugin.PluginKeys
import io.skjaere.usenet.plugin.PluginPlay
import io.skjaere.usenet.plugin.PluginSearch
import io.skjaere.usenet.plugin.PluginSecrets
import io.skjaere.usenet.plugin.PluginWatch
import io.skjaere.usenet.plugin.WatchInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import java.io.File

/**
 * Makes the [PluginHost] each loaded instance of a plugin gets (L613, L635): its id, its own folders and secrets, and a
 * scope of its own on the main thread. What the host offers beyond them, its search, play, watch and HTTP, answers nothing
 * until those services are wired for plugins (L630, L627, L594, L631); address safety likewise.
 */
class PluginHosts(
    private val store: PluginStore,
    private val secrets: NamedSecrets,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    /**
     * The host of [version]'s instance: [failed] gets an exception that escapes its scope, which the scope's handler keeps
     * from closing NZStreamer (L619).
     */
    fun create(version: PluginVersion, failed: (Throwable) -> Unit): HostedPlugin = HostedPlugin(
        id = version.id,
        dataDir = store.dataDir(version.id).apply { mkdirs() },
        cacheDir = store.cacheDir(version.id).apply { mkdirs() },
        secrets = Secrets(version.id, secrets),
        scope = CoroutineScope(SupervisorJob() + main + CoroutineExceptionHandler { _, e -> failed(e) }),
    )
}

/**
 * One instance's host. Its [scope] is a SupervisorJob's, so one coroutine's failure cancels none of the others; [cancel]
 * ends it as the instance is let go, before the plugin's close().
 */
class HostedPlugin internal constructor(
    override val id: String,
    override val dataDir: File,
    override val cacheDir: File,
    override val secrets: PluginSecrets,
    override val scope: CoroutineScope,
) : PluginHost {
    override val search: PluginSearch = NoSearch
    override val play: PluginPlay = NoPlay
    override val watch: PluginWatch = NoWatch
    override val http: PluginHttp = NoHttp

    override fun addressSafety(url: String): AddressSafety = AddressSafety.Invalid

    fun cancel() {
        scope.cancel()
    }
}

/**
 * The plugin's secrets, plugin:<id>:<name> in the app's one store (L617). A name outside its rule is refused, and the
 * message names the rule, never the name.
 */
private class Secrets(private val id: String, private val secrets: NamedSecrets) : PluginSecrets {
    override suspend fun get(name: String): String? = secrets.get(full(name))

    override suspend fun put(name: String, value: String?) = secrets.put(full(name), value)

    override fun has(name: String): Flow<Boolean> = secrets.has(full(name))

    private fun full(name: String): String {
        require(PluginKeys.validSecretName(name)) { "A secret's name is 1 to 40 lower-case letters, digits, dots, underscores and dashes" }
        return "${PluginStore.SECRETS}$id:$name"
    }
}

/** No indexer searched: no line and no release. */
private object NoSearch : PluginSearch {
    override fun search(words: String, runtimeMinutes: Int?, fresh: Boolean): Flow<Found> = emptyFlow()
}

/** Nothing played, and the plugin's releases never asked for. */
private object NoPlay : PluginPlay {
    override fun play(item: PlayItem, releases: suspend () -> ItemReleases) {
    }
}

/** No watch state, and none kept. */
private object NoWatch : PluginWatch {
    override fun observe(keys: Collection<String>): Flow<Map<String, WatchInfo>> = flowOf(emptyMap())

    override suspend fun mark(items: List<Pair<String, String>>, watched: Boolean) {
    }
}

/** No request made: every one gets no answer. */
private object NoHttp : PluginHttp {
    override suspend fun get(url: String, headers: Map<String, String>, maxBytes: Int): HttpAnswer =
        throw PluginHttpException(PluginHttpException.Kind.Unreachable, "Not available yet")
}
