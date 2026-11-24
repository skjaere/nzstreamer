package io.skjaere.usenet.app.plugins

import io.skjaere.usenet.app.BuildConfig
import io.skjaere.usenet.app.settings.PluginsDeps
import io.skjaere.usenet.plugin.PluginApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Settings › Plugins over states a test sets, in place of the app's registry, list and installer: the plugins
 * ([installed]), whether [pluginsOff], the [list] and the [installs]. What the page asks is kept, in order ([asked]),
 * and changes nothing: the test sets what follows, as the installer would. Nothing is installed, read or downloaded.
 */
class PluginsPageFakes(television: Boolean) {
    val installed = MutableStateFlow<List<InstalledPlugin>?>(emptyList())
    val pluginsOff = MutableStateFlow(false)
    val list = MutableStateFlow(PluginListState())
    val installs = MutableStateFlow<Map<String, InstallState>>(emptyMap())

    @Volatile
    var installAgain = true
    val asked = CopyOnWriteArrayList<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val deps = PluginsDeps(
        installed, pluginsOff, list, installs,
        installAgainOffered = { installAgain },
        pageOpened = { asked += "open" },
        checkAgain = { asked += "check again" },
        install = { asked += "install $it" },
        cancel = { asked += "cancel $it" },
        tryAgain = { asked += "try again $it" },
        installAgain = { asked += "install again $it" },
        remove = { asked += "remove $it" },
        turnOnAgain = { asked += "turn on again $it" },
        turnPluginsOnAgain = { asked += "turn plugins on again" },
        pageLeft = { asked += "left" },
        scope = scope,
        television = television,
        now = { NOW },
        zone = { ZONE },
    )

    /** The list as read a minute ago with [plugins] in it, each offering a version this NZStreamer runs. */
    fun listing(vararg plugins: ListedPlugin, failed: Boolean = false, checking: Boolean = false, readAt: Long = NOW - 60_000) {
        list.value = PluginListState(PluginIndex(Instant.EPOCH, plugins.toList(), emptyList()), readAt = readAt, failed = failed, checking = checking)
    }

    fun close() = scope.cancel()

    companion object {
        /** The fakes' clock: today, 20:41. */
        val NOW: Long = Instant.parse("2026-11-24T20:41:00Z").toEpochMilli()
        val ZONE: ZoneId = ZoneOffset.UTC

        /** A plugin the list offers, [id] and [name] invented, its version made for this NZStreamer unless [minor] is later. */
        fun listed(id: String, name: String, minor: Int = PluginApi.MINOR, summary: String = "Invented words for the $name plugin's card."): ListedPlugin {
            val libs = PluginCompat.parseLibs(BuildConfig.PLUGIN_HOST_LIBS)!!
            val version = ListedVersion(
                12, "1.2", PluginApi.MAJOR, minor, libs, 26, "https://github.com/skjaere/nzstreamer/releases/download/plugin-$id-v1.2.0/$id.apk",
                700_000, "a".repeat(64),
            )
            return ListedPlugin(id, name, summary, emptySet(), listOf(version))
        }

        /** An installed plugin in [slot], its tab [label] given, [status] as the registry has it in this process. */
        fun installed(
            id: String, name: String, label: String, slot: Int = 1, status: PluginStatus = PluginStatus.Waiting, withdrawn: Boolean = false,
        ) = InstalledPlugin(id, name, slot, 12, "1.2", label, status, null, null, null, withdrawn)
    }
}
