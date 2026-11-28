package app.nzstreamer.plugin.sportarr

import androidx.compose.runtime.Composable
import app.nzstreamer.plugin.sportarr.ui.CupIcon
import app.nzstreamer.plugin.sportarr.ui.EventPage
import app.nzstreamer.plugin.sportarr.ui.Pages
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
 * or a weekend's page by its key (L652); a page the plugin doesn't know, or one without its key, draws the tab's.
 */
class SportarrPlugin : NzPlugin {
    /** This instance's session, made as its first page draws (on the main thread, as every page is). */
    private var session: Session? = null

    private fun session(host: PluginHost): Session = session ?: Session(host).also { session = it }

    override fun tab(): PluginTab = PluginTab(Words.Setup.TAB, CupIcon)

    @Composable
    override fun Page(page: String, arg: String?, host: PluginHost, nav: PluginNav) {
        val session = session(host)
        when {
            arg != null && (page == Pages.EVENT || page == Pages.WEEKEND) -> EventPage(session, host.play, nav, page, arg)
            else -> SportsTab(session, nav)
        }
    }

    /**
     * From the tab's ⋮, a UFC card's Sportarr settings and Settings › Plugins: back where it was opened from by the arrow,
     * Back, or after a Save (L652). Its Event pages part holds the fight cards switch (L-B8).
     */
    @Composable
    override fun Settings(host: PluginHost, nav: PluginNav) {
        SetupPage(session(host), Words.Setup.SETTINGS_TITLE, intro = null, onBack = { nav.back() }, onSaved = { nav.back() }, eventPages = true)
    }

    // The host cancels the plugin's scope before it closes it, and the plugin starts nothing outside that scope.
    override fun close() = Unit
}
