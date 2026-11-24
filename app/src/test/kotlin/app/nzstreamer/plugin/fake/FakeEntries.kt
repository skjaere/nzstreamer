package app.nzstreamer.plugin.fake

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import io.skjaere.usenet.app.plugins.ShadowInits
import io.skjaere.usenet.plugin.NzPlugin
import io.skjaere.usenet.plugin.PluginHost
import io.skjaere.usenet.plugin.PluginNav
import io.skjaere.usenet.plugin.PluginTab

// Entry classes of an invented plugin, "fake", for PluginLoaderTest: it copies their class files into a jar of their own
// and loads them from there, as the app loads a plugin's file, with the test's own copies hidden from that loader. Only
// Shadow is left visible to it, as a class of NZStreamer's own would be.

/** A plugin as its entry should be. */
class GoodPlugin : NzPlugin {
    override fun tab(): PluginTab = PluginTab("Fake", Icons.Filled.Star)

    @Composable
    override fun Page(page: String, arg: String?, host: PluginHost, nav: PluginNav) {
    }

    @Composable
    override fun Settings(host: PluginHost, nav: PluginNav) {
    }

    override fun close() {
    }
}

/** An entry that isn't a plugin. */
class NotAPlugin

/** An entry whose constructor throws. */
class ThrowingPlugin : NzPlugin by GoodPlugin() {
    init {
        throw IllegalStateException("the fake's constructor")
    }
}

/** An entry that needs a class its file doesn't carry: Gone is left out of the jar. */
class LinkagePlugin : NzPlugin by GoodPlugin() {
    private val gone = Gone()

    override fun toString(): String = gone.toString()
}

class Gone

/**
 * An entry the plugin's loader finds through its parent, as a class of the app's own: its static initializer counts (in a
 * class of its own, so reading the count doesn't run it), and a test sees whether it ran.
 */
class Shadow : NzPlugin by GoodPlugin() {
    companion object {
        init {
            ShadowInits.count++
        }
    }
}
