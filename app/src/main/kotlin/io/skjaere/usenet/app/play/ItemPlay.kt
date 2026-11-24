package io.skjaere.usenet.app.play

import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.search.RankedItem

/**
 * A plugin's item to play (L627): plugin [pluginId]'s [key] for it, under which the host keeps its progress and the
 * release that played it, item:<plugin id>:<key> ([WatchKey.item]); its [title], stored with them, and [subtitle], on the
 * sheet and in the player; [picker], the plugin's file picker made the host's, else the largest video, as for a film.
 */
data class ItemTarget(
    val pluginId: String,
    val key: String,
    val title: String,
    val subtitle: String?,
    val picker: FilePicker = FilePicker.Largest,
) {
    val watchKey: String get() = WatchKey.item(pluginId, key)

    val watch: WatchTarget get() = WatchTarget(WatchKind.Item, title, itemKey = watchKey)
}

/**
 * What a plugin's releases() came to, its handles mapped back to the releases they stand for: [accepted], in the order the
 * plugin gave them; [offered] as possible matches, each with its reason; the line of what it [leftOut]; and for a play that
 * finds nothing, the indexers' [lines] (an indexer's name and the plugin's words for it) and its [note]. A handle the host
 * no longer keeps, or that isn't the plugin's, is left out.
 */
data class ItemFound(
    val accepted: List<RankedItem>,
    val offered: List<Pair<RankedItem, String>> = emptyList(),
    val leftOut: String? = null,
    val lines: List<Pair<String, String>> = emptyList(),
    val note: String? = null,
) {
    companion object {
        /** Nothing found: what a releases() that threw comes to (L619). */
        val NOTHING = ItemFound(emptyList())
    }
}

/**
 * Play on a plugin's item, as a film's Play ([MoviePlay]): the release that played it before goes alone while its NZB is
 * still stored (no search, no grab, as Resume on Home does); otherwise, or when that fails, the releases the plugin
 * accepts, in its order, those the host doesn't play here left out as a film's search leaves them. Nothing to play: the
 * no-release sheet with the plugin's lines and note, and nothing hidden to show, as names the plugin passed over can carry
 * what it keeps from the user.
 */
internal object ItemPlay {
    /** [release] alone, the file that played in it, as Resume on Home plays it. */
    private fun remembered(target: ItemTarget, release: RememberedRelease) = PlayRequest(
        target.title, target.subtitle, listOf(Replay.candidate(release)), Replay.file(release, target.picker, target.watch), target.watch,
        remembered = release,
    )

    /**
     * Plays [target]: [play] runs a request through the pipeline (null once the player starts, else the failure);
     * [searching] puts the sheet's line up as the plugin is asked; [releases], the plugin's, asked at most once. Returns
     * what the sheet shows at the end, or null once the player starts.
     */
    suspend fun run(
        target: ItemTarget,
        remembered: RememberedRelease?,
        stored: Boolean,
        play: suspend (PlayRequest, Retry?) -> PlayState.Failed?,
        searching: (line: String) -> Unit,
        releases: suspend () -> ItemFound,
    ): PlayState? {
        val alone = remembered?.takeIf { stored }
        val failed = alone?.let { play(remembered(target, it), null) ?: return null }
        // It just failed, or was skipped as found incomplete before: not tried again.
        val retry = alone?.let { Retry.afterRemembered(Replay.key(it), failed) }
        searching(retry?.let { "${it.searchingNotice} · $SEARCHING" } ?: SEARCHING)
        val found = releases()
        val (playable, hidden) = found.accepted.partition { it.ranked.playable }
        val candidates = retry?.leaveOut(playable) ?: playable
        // Only offered: read as nothing found until Choose release opens with them.
        if (candidates.isEmpty()) {
            return failed ?: PlayState.NoRelease(target.title, target.subtitle, found.lines, found.note, nameQuery = null, hidden = emptyList())
        }
        // In the plugin's order: the release that played it before isn't put first.
        val request = PlayRequest(
            target.title, target.subtitle, candidates, target.picker, target.watch, rememberedFirst = false, hidden = hidden.map { it.key }.toSet(),
        )
        return play(request, retry)
    }

    private const val SEARCHING = "Searching your indexers…"
}
