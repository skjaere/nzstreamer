package app.nzstreamer.plugin.sportarr.ui

/**
 * The plugin's words. Strings live in Kotlin, as a plugin carries no resources (L580, L660), with straight apostrophes as
 * the host's strings have them.
 */
internal object Words {
    /** The tab's label (W164 a), and the title of its own page. */
    const val TAB = "Sports"

    /** The title of the plugin's settings page, the same form as the tab's first screen. */
    const val SETTINGS_TITLE = "Sportarr settings"

    /** Under the tab's title until a Save: what the tab shows. */
    const val SETUP_INTRO =
        "Sports lists what you follow in your Sportarr: the leagues you switched on there, with your teams and the sessions you chose."

    const val ADDRESS = "Sportarr address"
    const val ADDRESS_PLACEHOLDER = "http://sportarr.home:1867"
    const val ADDRESS_HELP = "Where you open Sportarr in a browser, with its port."

    const val KEY = "API key"
    const val KEY_HELP = "In Sportarr: Settings › General › Security. Stored encrypted on this device."
}
