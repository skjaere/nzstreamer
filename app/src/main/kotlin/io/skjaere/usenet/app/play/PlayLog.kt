package io.skjaere.usenet.app.play

import io.skjaere.usenet.core.AttemptFailure

/**
 * How the play's log lines name a play, its watch key and an attempt's failure. The app's own plays by their titles and
 * keys, and failures in their own words, as they always were; a plugin's item by its plugin's id alone, as its title and
 * its key are the plugin's own words, and no log line carries a plugin's words beyond its id: the log can be shared from
 * Settings › Logs.
 */
internal object PlayLog {
    /** A play titled [title], whose watch key is [watchKey]: a plugin's item's by its plugin's id. */
    fun title(title: String, watchKey: String?): String = plugin(watchKey)?.let(::item) ?: title

    /** A watch key: a plugin's item's by its plugin's id. */
    fun key(watchKey: String): String = plugin(watchKey)?.let(::item) ?: watchKey

    /** An attempt's failure in its own words; a plugin picker's no-match by its kind, as its words are the plugin's. */
    fun failure(failure: AttemptFailure): String = if (failure is AttemptFailure.NoMatchingFile) NO_MATCHING_FILE else failure.text

    private const val NO_MATCHING_FILE = "no file the plugin's picker wants"

    private fun item(pluginId: String) = "a plugin's item ($pluginId)"

    /** The plugin's id in an item's watch key (item:<id>:<key>, WatchKey.item); null for any other key. */
    private fun plugin(watchKey: String?): String? = watchKey?.takeIf { it.startsWith("item:") }?.split(':')?.getOrNull(1)
}
