package io.skjaere.usenet.app.download

import io.skjaere.usenet.data.DownloadPrefs
import io.skjaere.usenet.data.WatchedDownload

/**
 * Delete once watched (W111; L412, L415, L416, L444): when a finished download goes, by its title's watched mark and the
 * setting. Pure, so the JVM reads every case; the runner deletes ([DownloadRunner.deleteWatched]) and the pages say
 * "Deleting tomorrow" ([DownloadRunner.deletingTomorrow]).
 * - A download is due a day after the later of its watched mark ([WatchedDownload.watchedAt]: the last write of its
 *   watch state as watched with no place in it, a mark or a play to its end, after it finished) and the time the setting
 *   was switched on, so nothing goes on the day it is switched on. Marked again, or played to its end again, it goes a
 *   day after that. A replay's first minute leaves a place in it, which keeps it until then; a title downloaded again
 *   after its mark is never due on that mark, however it is opened. Mark unwatched keeps it.
 * - None while the setting is off.
 * - One due that plays is left ([due]'s held): it goes once nothing holds it (L413).
 * - "Deleting tomorrow" only while its deletion is still ahead: one already due, in the start's two minutes or held by a
 *   play, reads as a plain finished download until it goes (L444).
 */
object AutoDelete {
    /** What a download going says, on every page that shows it (L415). */
    const val DELETING_TOMORROW = "Deleting tomorrow"

    /** How long after its mark, or the switch, a download goes. */
    const val DAY_MILLIS = 24 * 60 * 60_000L

    /** The clock as of [at]: the finished downloads watched since they finished ([rows]) and the setting ([since], null: off). */
    data class Now(val rows: List<WatchedDownload>, val since: Long?, val at: Long)

    /** The setting as the clock reads it: when it was switched on, or null while it is off (or on with no time kept, which keeps). */
    fun since(prefs: DownloadPrefs): Long? = prefs.deleteOnceWatchedSince?.takeIf { prefs.deleteOnceWatched }

    /** When [row] goes with the setting on [since]: null while it is off, or for a download finished after its title's mark. */
    fun dueAt(row: WatchedDownload, since: Long?): Long? {
        if (since == null || row.watchedAt <= row.completedAt) return null
        return maxOf(row.watchedAt, since) + DAY_MILLIS
    }

    /** The ids of [rows] due by [now], in their order, but those [held] by a play. */
    fun due(rows: List<WatchedDownload>, since: Long?, now: Long, held: (String) -> Boolean = { false }): List<String> =
        rows.filter { row -> dueAt(row, since)?.let { it <= now } == true && !held(row.id) }.map { it.id }

    /** The earliest due time of [rows] still ahead of [now]: when the runner looks again; null with none ahead. */
    fun nextAt(rows: List<WatchedDownload>, since: Long?, now: Long): Long? = rows.mapNotNull { dueAt(it, since) }.filter { it > now }.minOrNull()

    /** The ids of [rows] that will go and aren't due by [now]: those that say "Deleting tomorrow". */
    fun tomorrow(rows: List<WatchedDownload>, since: Long?, now: Long): Set<String> =
        rows.filter { row -> dueAt(row, since)?.let { it > now } == true }.mapTo(LinkedHashSet()) { it.id }
}
