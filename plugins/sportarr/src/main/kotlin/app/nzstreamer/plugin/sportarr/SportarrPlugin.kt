package app.nzstreamer.plugin.sportarr

import androidx.compose.runtime.Composable
import app.nzstreamer.plugin.sportarr.ui.CupIcon
import app.nzstreamer.plugin.sportarr.ui.SetupPage
import app.nzstreamer.plugin.sportarr.ui.Words
import io.skjaere.usenet.plugin.NzPlugin
import io.skjaere.usenet.plugin.PluginHost
import io.skjaere.usenet.plugin.PluginNav
import io.skjaere.usenet.plugin.PluginTab

/**
 * The Sportarr plugin (L639): a Sports tab with what the user follows in their own Sportarr, as its league pages show it
 * (W163 a), its address and API key kept in the host's secrets (L640). The entry class its manifest names; the host makes
 * one per version it loads.
 *
 * For now every page, the tab's own and any other, and the settings page draw the setup form's first lines: Test, Save,
 * the list and the event pages come later.
 */
class SportarrPlugin : NzPlugin {
    override fun tab(): PluginTab = PluginTab(Words.Setup.TAB, CupIcon)

    @Composable
    override fun Page(page: String, arg: String?, host: PluginHost, nav: PluginNav) {
        SetupPage(Words.Setup.TAB, Words.Setup.INTRO, onBack = null)
    }

    @Composable
    override fun Settings(host: PluginHost, nav: PluginNav) {
        SetupPage(Words.Setup.SETTINGS_TITLE, intro = null, onBack = { nav.back() })
    }

    // The host cancels the plugin's scope before it closes it, and the plugin starts nothing outside that scope.
    override fun close() = Unit
}
