package io.skjaere.usenet.app.plugins

import androidx.annotation.VisibleForTesting
import io.skjaere.usenet.app.BuildConfig
import io.skjaere.usenet.plugin.PluginKeys

/**
 * Debug builds' plugin settings from local.properties (W168 a, L656): a plugin's own secrets, Sportarr's address and key
 * (devSportarr.url, devSportarr.key), as the build encodes them into BuildConfig.DEV_PLUGIN_SECRETS: one line per secret,
 * the plugin's id, the secret's name and its value, separated by tabs. Every other build type has none. A new install of
 * that plugin is given them, each where none is stored (PluginRegistry), so a debug phone starts with them filled in.
 */
object PluginDevSecrets {
    /** One plugin's secret as the build encodes it. Its value is never shown. */
    data class Entry(val id: String, val name: String, val value: String) {
        override fun toString() = "Entry($id, $name)"
    }

    /**
     * The secrets [encoded] gives, in its order. A line without the three fields, with an id or a secret's name outside
     * the plugins' rules, or with no value, is left out, as is a second value for one plugin's name.
     */
    fun parse(encoded: String): List<Entry> =
        encoded.lineSequence()
            .map { line -> line.split('\t', limit = 3).map(String::trim) }
            .filter { it.size == 3 }
            .map { (id, name, value) -> Entry(id, name, value) }
            .filter { PluginIds.valid(it.id) && PluginKeys.validSecretName(it.name) && it.value.isNotEmpty() }
            .distinctBy { it.id to it.name }
            .toList()

    /**
     * What a new install of [id] is given, each secret's name and its value: those [encoded] (the build's encoding) gives
     * it, in a debug build with the seeding on. Else none, and [encoded] isn't read.
     */
    internal fun forNewInstall(id: String, encoded: () -> String): List<Pair<String, String>> =
        if (!BuildConfig.DEBUG || !seeding) emptyList() else parse(encoded()).filter { it.id == id }.map { it.name to it.value }

    /**
     * Test seam: whether a new install is given the build's values. The device tests' runner turns it off before the app
     * starts, so a run never stores the developer's own address and key on a device.
     */
    @VisibleForTesting
    @Volatile
    internal var seeding: Boolean = true
}
