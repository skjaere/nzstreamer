package io.skjaere.usenet.app.plugins

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import io.skjaere.usenet.app.ifResumed
import io.skjaere.usenet.plugin.Offered
import io.skjaere.usenet.plugin.PlayItem
import io.skjaere.usenet.plugin.PluginKeys
import io.skjaere.usenet.plugin.PluginNav
import io.skjaere.usenet.plugin.ReleaseHandle

/**
 * Where a page of the plugin in [slot] goes (L582): its own pages and its settings onto the current stack, Back as the Back
 * key pops one page. Each move happens only while [entry], the page it was given to, is the resumed one: a second tap as
 * the page fades out, or a call the plugin makes later from its scope once another page shows, moves nothing.
 */
internal class PluginNavImpl(private val nav: NavController, private val slot: PluginSlot, private val entry: NavBackStackEntry) : PluginNav {
    override fun open(page: String, arg: String?) {
        check(page, arg)
        entry.ifResumed { nav.navigate(slot.page(page, arg)) }
    }

    override fun back() {
        entry.ifResumed { nav.popBackStack() }
    }

    override fun openSettings() {
        entry.ifResumed { nav.navigate(slot.settings) }
    }

    // The host's Choose release for a plugin's item, and Search with a plugin's note, come with their pages (L628, L629).
    override fun openReleases(item: PlayItem, accepted: List<ReleaseHandle>, offered: List<Offered>, leftOut: String?) = Unit

    override fun openSearch(words: String, note: String?) = Unit

    companion object {
        /** L613's rules for a page the plugin opens: refused with the rule's words, never the value, which a log could keep. */
        fun check(page: String, arg: String?) {
            require(PluginKeys.validPage(page)) { "A page's name is 1 to 40 lower-case letters, digits and dashes" }
            require(PluginKeys.validArg(arg)) { "A page's argument is at most 200 characters" }
        }
    }
}
