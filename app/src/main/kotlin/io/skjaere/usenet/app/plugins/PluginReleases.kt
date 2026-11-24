package io.skjaere.usenet.app.plugins

import io.skjaere.usenet.app.play.ItemFound
import io.skjaere.usenet.app.play.ItemTarget
import io.skjaere.usenet.app.search.PossibleMatches
import io.skjaere.usenet.plugin.Offered
import io.skjaere.usenet.plugin.PlayItem
import io.skjaere.usenet.plugin.ReleaseHandle
import io.skjaere.usenet.search.GroupKind
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup
import java.util.UUID

/**
 * One Choose release of a plugin's item (L628), as the host keeps it for its page: [target], the item as its Play plays
 * it, its picker the plugin's; [accepted], the releases the plugin accepted in its order, playable here or hidden as the
 * searches ranked them; [possible], the ones it offered, each with its reason, its line of what it left out, and the
 * item's noun for the lines over them.
 */
class PluginReleaseList(val target: ItemTarget, val accepted: ResultGroup, val possible: PossibleMatches) {
    /** Nothing accepted: the possible matches alone, under their own line. */
    val onlyPossible: Boolean get() = accepted.size == 0
}

/**
 * The lists of the host's Choose release pages for plugins' items, each under a token its route carries
 * ([PluginReleasesRoute]): a plugin's openReleases leaves one ([PluginChooser]), and so does an item's Play that found only
 * possible matches (L627). Kept in memory alone, so a page whose token a process death took closes; let go as its page is
 * left ([close]), or, for pages piling up unclosed, the oldest past [MAX]. A list holds the plugin's picker for its plays
 * (L635) only as long as that.
 */
class PluginReleases {
    private val lists = LinkedHashMap<String, PluginReleaseList>()

    /**
     * Keeps [found]'s releases for [target]'s Choose release, and gives the token its page opens with. A release listed
     * twice shows once, where it was first accepted, else first offered.
     */
    @Synchronized
    fun open(target: ItemTarget, found: ItemFound): String {
        val token = UUID.randomUUID().toString()
        val accepted = found.accepted.distinctBy { it.key }
        val listed = accepted.map { it.key }.toSet()
        val offered = found.offered.filter { (item, _) -> item.key !in listed }.distinctBy { (item, _) -> item.key }
        val possible = PossibleMatches(
            group("$KEY$token$POSSIBLE", offered.map { it.first }), offered.associate { (item, reason) -> item.key to reason }, target.noun, found.leftOut,
        )
        lists[token] = PluginReleaseList(target, group("$KEY$token", accepted), possible)
        while (lists.size > MAX) lists.remove(lists.keys.first())
        return token
    }

    /** [token]'s list, or null where none is kept. */
    @Synchronized
    operator fun get(token: String): PluginReleaseList? = lists[token]

    /** [token]'s page was left: its list goes. */
    @Synchronized
    fun close(token: String) {
        lists.remove(token)
    }

    /** [items] as one group of the page, playable here or hidden as the searches ranked them. */
    private fun group(key: String, items: List<RankedItem>) =
        ResultGroup(key, GroupKind.Other, null, emptyList(), null, null, items.filter { it.ranked.playable }, items.filterNot { it.ranked.playable })

    companion object {
        /** How many lists are kept for pages not closed yet: more than a user leaves open on the tabs' stacks. */
        const val MAX = 16

        /** The groups' keys, under which a list keeps its order and place while its page shows. */
        private const val KEY = "plugin-releases:"
        private const val POSSIBLE = "|possible"
    }
}

/** A plugin instance's way to the host's Choose release (L628): what [io.skjaere.usenet.plugin.PluginNav.openReleases] keeps. */
fun interface PluginChooser {
    /** Keeps [item]'s releases for Choose release, and gives the page's token; null where there is no page to open. */
    fun open(item: PlayItem, accepted: List<ReleaseHandle>, offered: List<Offered>, leftOut: String?): String?

    companion object {
        /** No Choose release: a host made without the app's play service (a test's registry). */
        val NONE = PluginChooser { _, _, _, _ -> null }
    }
}
