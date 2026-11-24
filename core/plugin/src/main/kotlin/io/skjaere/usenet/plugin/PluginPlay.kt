package io.skjaere.usenet.plugin

/** Plays a plugin's items through NZStreamer's own play: its sheet, its player, its Cast and its watch state. */
interface PluginPlay {
    /**
     * Plays [item] as a film's Play does: on this phone, or on the Cast device when one is chosen (here with a TV chosen,
     * which can't play an item yet). While the release that played the item last is still stored, it goes alone; else, or
     * once it fails, the host asks [releases] and tries the accepted ones in the plugin's order. With none accepted but some offered, Choose release opens with
     * them as possible matches ([PluginNav.openReleases]); with none at all, the host's no-release sheet shows the
     * plugin's lines and note, and Search indexers when the item has a [PlayItem.search].
     *
     * [releases] is asked at most once a play, and let go as the play ends. A throw from it counts as nothing found and
     * turns the version off ("Turned off after an error"). What plays is kept under the item's key: its progress, and the
     * release, which Up next's Resume plays again with no plugin needed.
     */
    fun play(item: PlayItem, releases: suspend () -> ItemReleases)
}

/**
 * A plugin's playable thing. The host keeps its progress and the release that played under its [key] (1 to 200 letters,
 * digits, dots, underscores, colons and dashes, [PluginKeys.validKey]; refused as it is made otherwise), stored as
 * item:<plugin id>:<key>, so it must stay the same for the same thing across versions.
 */
class PlayItem(
    val key: String,
    /** Shown by the play sheet and the player, and stored: Up next and history name the item by it. */
    val title: String,
    /** Under the title on the play sheet and in the player. */
    val subtitle: String?,
    /**
     * How long the item runs, as the plugin expects it; null when it doesn't know. Kept with the play as a film's runtime
     * is; the releases were ranked by the plugin's searches, which take the runtime themselves ([PluginSearch.search]).
     */
    val runtimeMinutes: Int?,
    /**
     * The plugin's word for the item in Choose release's lines over its possible matches: "game" makes "Tap one if it's
     * the right game." A lower-case letter, then up to 19 lower-case letters, spaces and dashes ([PluginKeys.validNoun]).
     * One outside the rule isn't refused but counts as none, as null does, and the lines then say "release".
     */
    noun: String? = null,
    /** What Search indexers on the no-release sheet searches; null: the sheet offers no Search. */
    val search: SearchOffer? = null,
    /** Which file of a release is the item's; null: the largest video that isn't a sample, as for a film. */
    val picker: PluginFilePicker? = null,
) {
    /** The item's word in Choose release's lines, as given where it keeps to the rule; else null. */
    val noun: String? = noun?.takeIf(PluginKeys::validNoun)

    init {
        require(PluginKeys.validKey(key)) { "An item's key is 1 to 200 letters, digits, dots, underscores, colons and dashes" }
    }
}

/**
 * The plugin's answer to a play ([PluginPlay.play]): the releases it [accepted], in the order to try them; those it
 * [offered] as possible matches, each with its reason; one line of what it [leftOut] and why, counting and never naming
 * them; and, for a play that finds nothing, the indexers' [lines] and a [note] for the no-release sheet.
 */
class ItemReleases(
    val accepted: List<ReleaseHandle>,
    val offered: List<Offered> = emptyList(),
    val leftOut: String? = null,
    val lines: List<IndexerNote> = emptyList(),
    /** "Releases often appear a few hours after a game ends. Try Play again later." */
    val note: String? = null,
)

/** One line of the no-release sheet: an [indexer] as [IndexerLine.name] named it, and the plugin's [words] for it. */
class IndexerNote(val indexer: String, val words: String)

/**
 * Search indexers on the no-release sheet: the Search tab opens with [words] searched and [note] above the results, as
 * [PluginNav.openSearch] opens it.
 */
class SearchOffer(val words: String, val note: String?)

/**
 * Chooses the item's file in a release, as the host's own pickers choose an episode's. Called while the play waits for
 * its answer: quick, and without I/O. A throw from it fails the play with [noMatch] and turns the version off ("Turned
 * off after an error"). A member a later minor adds to this interface has a body, which a picker built before it
 * inherits.
 */
interface PluginFilePicker {
    /** The index in [files] of the item's file, or null when none is: the play then fails with [noMatch]. */
    fun pick(files: List<FileInfo>): Int?

    /** What the play sheet says when [pick] finds no file ("No file for the main card"). */
    val noMatch: String

    /**
     * Whether a release named [releaseName] holds just the one video this picker wants (besides PAR2, NFO and the like),
     * so a gap anywhere in it is a gap in what would play.
     */
    fun takesWhole(releaseName: String): Boolean
}

/** A file in a release: its [path] with its folders, its [size] in bytes, and whether the host takes it for a video. */
class FileInfo(val path: String, val size: Long, val isVideo: Boolean)
