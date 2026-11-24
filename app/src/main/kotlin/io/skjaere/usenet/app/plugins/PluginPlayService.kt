package io.skjaere.usenet.app.plugins

import io.skjaere.usenet.app.play.ItemFound
import io.skjaere.usenet.app.play.ItemTarget
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.plugin.ItemReleases
import io.skjaere.usenet.plugin.PlayItem
import io.skjaere.usenet.plugin.PluginPlay
import io.skjaere.usenet.plugin.ReleaseHandle
import io.skjaere.usenet.search.RankedItem

/**
 * Plays plugins' items through the app's own play (L627, L593): the item as the host keeps it ([ItemTarget]), its picker
 * made the host's ([PluginFilePickers]), and [route] sends it where Play goes, as the play router does Choose release's.
 * The plugin's releases() is asked by the play at most once, inside the guard ([PluginCalls.calling], L619): one that throws
 * is nothing found, and turns the calling instance's version off. What it answers is copied inside the guard, as the
 * plugin's own lists are its code, and its handles are mapped back through its own table only ([ranked]). A plugin's
 * Choose release ([chooser], L628) is kept in [lists] the same way, for its page.
 */
class PluginPlayService(
    private val ranked: (id: String, handles: List<ReleaseHandle>) -> List<RankedItem>,
    private val lists: PluginReleases = PluginReleases(),
    private val route: (ItemTarget, suspend () -> ItemFound) -> Unit,
) {
    /** The play of [version]'s instance, whose calls into the plugin's code [calls] guards. */
    fun of(version: PluginVersion, calls: PluginCalls): PluginPlay = Player(version, calls)

    /**
     * The way of [version]'s instance to the host's Choose release (L628): the item as its Play would play it, its handles
     * mapped back through its own table, kept in [lists] under the token its page opens with.
     */
    fun chooser(version: PluginVersion, calls: PluginCalls): PluginChooser = PluginChooser { item, accepted, offered, leftOut ->
        lists.open(target(item, version, calls), found(version.id, accepted.toList(), offered.map { it.handle to it.reason }, leftOut))
    }

    /** [item] as the host plays it: its picker the plugin's, guarded; its noun and its search's words and note (L628, L629). */
    private fun target(item: PlayItem, version: PluginVersion, calls: PluginCalls): ItemTarget {
        val picker = item.picker?.let { PluginFilePickers.of(it, version, calls) } ?: FilePicker.Largest
        return ItemTarget(version.id, item.key, item.title, item.subtitle, picker, item.noun, item.search?.words, item.search?.note)
    }

    /** Plugin [id]'s answer in the host's terms: its handles mapped back, in its order, one the host no longer keeps left out. */
    private fun found(
        id: String,
        accepted: List<ReleaseHandle>,
        offered: List<Pair<ReleaseHandle, String>>,
        leftOut: String?,
        lines: List<Pair<String, String>> = emptyList(),
        note: String? = null,
    ) = ItemFound(
        accepted = ranked(id, accepted),
        offered = offered.mapNotNull { (handle, reason) -> ranked(id, listOf(handle)).singleOrNull()?.let { it to reason } },
        leftOut = leftOut,
        lines = lines,
        note = note,
    )

    private inner class Player(private val version: PluginVersion, private val calls: PluginCalls) : PluginPlay {
        override fun play(item: PlayItem, releases: suspend () -> ItemReleases) {
            route(target(item, version, calls)) {
                calls.calling(version, "releases", nothing = null) { Answer.of(releases()) }
                    ?.let { found(version.id, it.accepted, it.offered, it.leftOut, it.lines, it.note) }
                    ?: ItemFound.NOTHING
            }
        }
    }

    /** [ItemReleases] copied into the host's own lists. */
    private class Answer(
        val accepted: List<ReleaseHandle>,
        val offered: List<Pair<ReleaseHandle, String>>,
        val leftOut: String?,
        val lines: List<Pair<String, String>>,
        val note: String?,
    ) {
        companion object {
            fun of(given: ItemReleases) = Answer(
                given.accepted.toList(), given.offered.map { it.handle to it.reason }, given.leftOut, given.lines.map { it.indexer to it.words }, given.note,
            )
        }
    }
}
