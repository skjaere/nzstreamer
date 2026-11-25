package app.nzstreamer.plugin.sportarr

import androidx.compose.runtime.Composable
import app.nzstreamer.plugin.sportarr.ui.CupIcon
import app.nzstreamer.plugin.sportarr.ui.SetupPage
import app.nzstreamer.plugin.sportarr.ui.SportsTab
import app.nzstreamer.plugin.sportarr.ui.Words
import io.skjaere.usenet.plugin.NzPlugin
import io.skjaere.usenet.plugin.PluginHost
import io.skjaere.usenet.plugin.PluginNav
import io.skjaere.usenet.plugin.PluginTab

/**
 * The Sportarr plugin (L639): a Sports tab with what the user follows in their own Sportarr, as its league pages show it
 * (W163 a), its address and API key kept in the host's secrets (L640). The entry class its manifest names; the host makes
 * one per version it loads, each with a host of its own, whose pages share one [Session].
 *
 * The tab is the setup until a Save, then the list; the settings page is the same form. The list's rows open an event's
 * or a weekend's page by its key (L652); until those pages are drawn, any page draws the tab's.
 */
class SportarrPlugin : NzPlugin {
    /** This instance's session, made as its first page draws (on the main thread, as every page is). */
    private var session: Session? = null

    private fun session(host: PluginHost): Session = session ?: Session(host).also { session = it }

    override fun tab(): PluginTab = PluginTab(Words.Setup.TAB, CupIcon)

    @Composable
    override fun Page(page: String, arg: String?, host: PluginHost, nav: PluginNav) {
        SportsTab(session(host), nav)
    }

    /** From the tab's ⋮ and from Settings › Plugins: back where it was opened from by the arrow, Back, or after a Save (L652). */
    @Composable
    override fun Settings(host: PluginHost, nav: PluginNav) {
        SetupPage(session(host), Words.Setup.SETTINGS_TITLE, intro = null, onBack = { nav.back() }, onSaved = { nav.back() })
    }

    // The host cancels the plugin's scope before it closes it, and the plugin starts nothing outside that scope.
    override fun close() = Unit
}
