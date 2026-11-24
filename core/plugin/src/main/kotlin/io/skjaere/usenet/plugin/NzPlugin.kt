package io.skjaere.usenet.plugin

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A plugin's entry class: the one class its manifest names ([PluginApi.META_ENTRY]), under app.nzstreamer.plugin.<id>.,
 * with a public constructor that takes no argument. The host makes one instance per version it loads, off the main thread,
 * only after the plugin's file passed every check, and only once NZStreamer's window first opens: never in a process
 * started for its background work alone. Each instance has a [PluginHost] of its own.
 *
 * The plugin's pages are drawn in its tab, with NZStreamer's look, on phones and TVs. Two rules hold for every page, which
 * the host can't check:
 * - A page keeps only strings, numbers and booleans in rememberSaveable, a Saver and its `arg`. After a process death the
 *   host restores them with its own class loader, which can't make a plugin's types.
 * - On a TV, every state of a page has something to focus.
 *
 * Compose can't catch an exception thrown while composing: one thrown by [Page] or [Settings] closes NZStreamer, and the
 * crash guard turns that version off at the next start ("Turned off after a crash"). A throw from [tab] or [close], or one
 * that escapes [PluginHost.scope], turns the version off at once ("Turned off after an error") and leaves NZStreamer
 * running.
 *
 * A member a later minor adds to this interface has a body, which a plugin built before it inherits: its class keeps
 * working unchanged.
 */
interface NzPlugin {
    /**
     * The plugin's tab, asked for once as the version loads. The host shows its label and icon in the phone's bar and the
     * TV's rail, after Discover (after Home in a build without it), and keeps the label to show while the version is off.
     */
    fun tab(): PluginTab

    /**
     * One of the plugin's pages: [page] "" is the tab's own, any other one a page [PluginNav.open] opened with [arg]. The
     * host keeps the tab's history while other tabs show, and across a process death.
     */
    @Composable
    fun Page(page: String, arg: String?, host: PluginHost, nav: PluginNav)

    /** The plugin's own settings page, opened from its card in Settings › Plugins and by [PluginNav.openSettings]. */
    @Composable
    fun Settings(host: PluginHost, nav: PluginNav)

    /**
     * Called once as the host lets this instance go: on an update's swap to a new version, a withdrawal, Remove, or an
     * error. Its [PluginHost.scope] is cancelled first. None of its pages is drawn after it; a play it started keeps its
     * releases and picker until that play ends.
     */
    fun close()
}

/**
 * A plugin's tab: its [label], 1 to 16 characters ([PluginKeys.validLabel]), which shrinks to fit the phone's bar on one
 * line, and its [icon]. A label outside the rule is refused as the tab is made.
 */
class PluginTab(val label: String, val icon: ImageVector) {
    init {
        require(PluginKeys.validLabel(label)) { "A tab's label is 1 to 16 characters" }
    }
}
