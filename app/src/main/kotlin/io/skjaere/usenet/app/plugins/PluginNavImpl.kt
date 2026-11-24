package io.skjaere.usenet.app.plugins

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import io.skjaere.usenet.app.SearchRoute
import io.skjaere.usenet.app.ifResumed
import io.skjaere.usenet.app.switchTab
import io.skjaere.usenet.plugin.Offered
import io.skjaere.usenet.plugin.PlayItem
import io.skjaere.usenet.plugin.PluginKeys
import io.skjaere.usenet.plugin.PluginNav
import io.skjaere.usenet.plugin.ReleaseHandle

/**
 * Where a page of the plugin in [slot] goes (L582): its own pages and its settings onto the current stack, Back as the Back
 * key pops one page; the host's Choose release for one of its items over the page (L628), its releases kept by [chooser];
 * and the Search tab with its words and note (L629), as the play sheet's Search opens it. Each move happens only while
 * [entry], the page it was given to, is the resumed one: a second tap as the page fades out, or a call the plugin makes
 * later from its scope once another page shows, moves nothing.
 */
internal class PluginNavImpl(
    private val nav: NavController,
    private val slot: PluginSlot,
    private val entry: NavBackStackEntry,
    private val chooser: PluginChooser,
) : PluginNav {
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

    override fun openReleases(item: PlayItem, accepted: List<ReleaseHandle>, offered: List<Offered>, leftOut: String?) {
        entry.ifResumed {
            val token = chooser.open(item, accepted, offered, leftOut) ?: return
            nav.navigate(PluginReleasesRoute(token))
        }
    }

    override fun openSearch(words: String, note: String?) {
        entry.ifResumed { nav.switchTab(searchRoute(words, note), restore = false) }
    }

    companion object {
        /** L613's rules for a page the plugin opens: refused with the rule's words, never the value, which a log could keep. */
        fun check(page: String, arg: String?) {
            require(PluginKeys.validPage(page)) { "A page's name is 1 to 40 lower-case letters, digits and dashes" }
            require(PluginKeys.validArg(arg)) { "A page's argument is at most 200 characters" }
        }

        /**
         * The Search tab a plugin opens ([PluginNav.openSearch]): [words] searched, its [note] above the results; with no
         * words, Search to type in, with no note.
         */
        fun searchRoute(words: String, note: String?): SearchRoute {
            val query = words.trim().ifEmpty { return SearchRoute() }
            return SearchRoute(query = query, note = note?.takeIf(String::isNotBlank))
        }
    }
}
