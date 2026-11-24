package app.nzstreamer.plugin.skeleton

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import io.skjaere.usenet.plugin.FileInfo
import io.skjaere.usenet.plugin.NzPlugin
import io.skjaere.usenet.plugin.PluginFilePicker
import io.skjaere.usenet.plugin.PluginHost
import io.skjaere.usenet.plugin.PluginNav
import io.skjaere.usenet.plugin.PluginTab

/**
 * The platform's example plugin (L623), NZStreamer's own test builds of a plugin, never released. Its tab, Skeleton,
 * searches the user's indexers for the words given, plays a release it found as one item with a file picker, marks that
 * item watched, opens a second page and its own settings (one key, saved as a secret), and has a page that throws as it
 * is first drawn: the crash NZStreamer's guard is for (L619). Its words are invented.
 */
class SkeletonPlugin : NzPlugin {
    override fun tab(): PluginTab = PluginTab(Skeleton.LABEL, Icons.Filled.Star)

    @Composable
    override fun Page(page: String, arg: String?, host: PluginHost, nav: PluginNav) {
        when (page) {
            "" -> SearchPage(host, nav)
            Skeleton.SECOND -> SecondPage(arg, nav)
            Skeleton.CRASH -> CrashPage()
            // A page another version of the skeleton had, restored after an update: nothing of it is here.
            else -> SecondPage(null, nav)
        }
    }

    @Composable
    override fun Settings(host: PluginHost, nav: PluginNav) {
        SettingsPage(host, nav)
    }

    override fun close() = Unit
}

/** The skeleton's words, pages and keys. */
internal object Skeleton {
    const val LABEL = "Skeleton"

    /** The second page, opened with an argument, and the page that throws as it is first drawn. */
    const val SECOND = "second"
    const val CRASH = "crash"

    /** What it searches unless told otherwise: a title the fake indexer of the device tests has. */
    const val WORDS = "Copper Crown"

    /** The one item it plays and marks: its key, stored as item:skeleton:copper-crown, and its title. */
    const val ITEM_KEY = "copper-crown"
    const val ITEM_TITLE = "Copper Crown"

    /** Its one secret's name, stored as plugin:skeleton:key. */
    const val SECRET = "key"
}

/** The item's file in a release: its largest video, as the host's own picker takes a film's. */
internal object LargestVideo : PluginFilePicker {
    override fun pick(files: List<FileInfo>): Int? = files.withIndex().filter { it.value.isVideo }.maxByOrNull { it.value.size }?.index

    override val noMatch: String = "No video in this release"

    override fun takesWhole(releaseName: String): Boolean = true
}
