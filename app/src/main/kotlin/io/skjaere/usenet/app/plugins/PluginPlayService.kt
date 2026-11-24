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
 * plugin's own lists are its code, and its handles are mapped back through its own table only ([ranked]).
 */
class PluginPlayService(
    private val ranked: (id: String, handles: List<ReleaseHandle>) -> List<RankedItem>,
    private val route: (ItemTarget, suspend () -> ItemFound) -> Unit,
) {
    /** The play of [version]'s instance, whose calls into the plugin's code [calls] guards. */
    fun of(version: PluginVersion, calls: PluginCalls): PluginPlay = Player(version, calls)

    private inner class Player(private val version: PluginVersion, private val calls: PluginCalls) : PluginPlay {
        override fun play(item: PlayItem, releases: suspend () -> ItemReleases) {
            val picker = item.picker?.let { PluginFilePickers.of(it, version, calls) } ?: FilePicker.Largest
            val target = ItemTarget(version.id, item.key, item.title, item.subtitle, picker)
            route(target) { calls.calling(version, "releases", nothing = null) { Answer.of(releases()) }?.let(::found) ?: ItemFound.NOTHING }
        }

        private fun found(answer: Answer) = ItemFound(
            accepted = ranked(version.id, answer.accepted),
            offered = answer.offered.mapNotNull { (handle, reason) -> ranked(version.id, listOf(handle)).singleOrNull()?.let { it to reason } },
            leftOut = answer.leftOut,
            lines = answer.lines,
            note = answer.note,
        )
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
