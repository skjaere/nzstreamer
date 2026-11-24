package io.skjaere.usenet.plugin

/**
 * Where a plugin's pages go: its own pages in its tab's history, and two pages of the host's. A value outside its rule
 * ([PluginKeys]) is refused with an [IllegalArgumentException].
 */
interface PluginNav {
    /**
     * Opens the plugin's [page] ([PluginKeys.validPage]) over the current one, drawn by [NzPlugin.Page] with [arg]
     * ([PluginKeys.validArg]). Back returns to the page it was opened from.
     */
    fun open(page: String, arg: String?)

    /** Goes back one page, as the Back key does. */
    fun back()

    /** Opens the plugin's settings page ([NzPlugin.Settings]) over the current one. */
    fun openSettings()

    /**
     * Opens the host's Choose release for [item] over the current page: the [accepted] releases ranked as the search
     * ranked them, then the [offered] ones under "Possible matches", each with its reason in place of a rank, and [leftOut]
     * as the list's last line ("4 left out: a score in the name", never naming one). With nothing accepted, only the
     * possible matches show. The lines over them name the item by its [PlayItem.noun] ("the right game"). A tap plays that release alone, as [item]'s play ([PluginPlay.play]). A handle the host no
     * longer knows is left out.
     */
    fun openReleases(item: PlayItem, accepted: List<ReleaseHandle>, offered: List<Offered>, leftOut: String?)

    /**
     * Switches to the Search tab with [words] searched across the user's indexers, as typed there. [note] shows as one line
     * in the warning colour above the results as long as those words show ("Indexer results can show scores"). A search
     * opened with a note isn't kept in the recent searches.
     */
    fun openSearch(words: String, note: String?)
}

/**
 * A release the plugin isn't sure of, offered under "Possible matches" with its [reason], the plugin's words in 1 to 60
 * characters ("No date in the name", [PluginKeys.validReason]). A reason outside the rule is refused as it is made.
 */
class Offered(val handle: ReleaseHandle, val reason: String) {
    init {
        require(PluginKeys.validReason(reason)) { "A possible match's reason is 1 to 60 characters" }
    }
}
