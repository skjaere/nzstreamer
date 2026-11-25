package app.nzstreamer.plugin.sportarr.model

import io.skjaere.usenet.plugin.WatchInfo
import java.time.Instant

/** "<watched> of <of> watched": a card's parts, or a weekend's sessions that have started. */
data class Count(val watched: Int, val of: Int)

/**
 * A row of the tab's Up next: what it plays ([item]), the row it belongs to ([listed], whose count the row shows), and
 * the part's watch state where it is in progress ([info]; none for the next part of a card or weekend).
 */
data class UpNext(val listed: Listed, val item: Item, val info: WatchInfo?)

/**
 * What counts as watched (L644), from the host's watch states by part key (the plugin's keys, as [PluginWatch.observe][io.skjaere.usenet.plugin.PluginWatch.observe]
 * gives them):
 * - a part is watched as the host says (at 90 %, or by Mark watched);
 * - an event when its headline part is: the main card, the main show, else the whole event;
 * - a weekend when its race is, or every race where a round has several; with no race followed, every session.
 */
object Watched {
    fun part(part: Part, watch: Map<String, WatchInfo>): Boolean = watch[part.key]?.watched == true

    fun event(event: SportsEvent, watch: Map<String, WatchInfo>): Boolean = part(event.headline, watch)

    fun weekend(weekend: Weekend, watch: Map<String, WatchInfo>): Boolean = weekend.headline.all { event(it, watch) }

    fun listed(listed: Listed, watch: Map<String, WatchInfo>): Boolean = when (listed) {
        is SportsEvent -> event(listed, watch)
        is Weekend -> weekend(listed, watch)
    }

    /** Whether [info] is a play in progress: stopped past the start, not watched. */
    fun inProgress(info: WatchInfo?): Boolean = info != null && info.positionMs > 0 && !info.watched

    /** A card's watched parts of all its parts; a weekend's watched sessions of those that have started by [now]. */
    fun count(listed: Listed, watch: Map<String, WatchInfo>, now: Instant): Count {
        val counted = when (listed) {
            is SportsEvent -> listed.items
            is Weekend -> listed.items.filter { !it.event.start.isAfter(now) }
        }
        return Count(counted.count { part(it.part, watch) }, counted.size)
    }

    /**
     * The tab's Up next, of [rows] (shown on Recent alone, which the list decides), three rows at most:
     * - first, parts in progress, the last played first;
     * - then, for a card or weekend the user started (a part watched, none in progress), the next part: the first one
     *   after the last watched that has started and isn't watched, as a show's page goes on to the next episode, so a
     *   practice skipped never comes back; the one the user moved on last first ([moved]);
     * - nothing of a watched card or weekend, its parts in progress included.
     */
    fun upNext(rows: List<Listed>, watch: Map<String, WatchInfo>, now: Instant): List<UpNext> {
        val open = rows.filterNot { listed(it, watch) }
        val playing = open.flatMap { row -> row.items.mapNotNull { item -> watch[item.part.key]?.takeIf(::inProgress)?.let { UpNext(row, item, it) } } }
            .sortedWith(compareByDescending<UpNext> { it.info?.lastPlayedAt ?: Instant.MIN }.thenBy { it.item.part.key })
        val started = playing.map { it.listed.key }.toSet()
        val next = open.filter { it.key !in started && it.items.size > 1 }
            .mapNotNull { row -> next(row, watch, now)?.let { UpNext(row, it, null) to moved(row, watch) } }
            .sortedWith(compareByDescending<Pair<UpNext, Instant>> { it.second }.thenBy { it.first.item.part.key })
            .map { it.first }
        return (playing + next).take(ROWS)
    }

    /** The part after the last one watched that has started and isn't watched; null where none is, or none is watched. */
    private fun next(row: Listed, watch: Map<String, WatchInfo>, now: Instant): Item? {
        val items = row.items
        val last = items.indexOfLast { part(it.part, watch) }
        if (last < 0) return null
        return items.drop(last + 1).firstOrNull { !it.event.start.isAfter(now) && !part(it.part, watch) }
    }

    /**
     * When the user last moved on in [row], for the order of the next parts: its last play, or the start of the latest part
     * watched where that is later. The host gives a part only marked watched no time, but it can't have been marked
     * before it started, so a weekend caught up on by Mark watched isn't put behind a card played days before.
     */
    private fun moved(row: Listed, watch: Map<String, WatchInfo>): Instant {
        val played = row.items.mapNotNull { watch[it.part.key]?.lastPlayedAt }.maxOrNull()
        val watchedStart = row.items.filter { part(it.part, watch) }.maxOfOrNull { it.event.start }
        return listOfNotNull(played, watchedStart).maxOrNull() ?: Instant.MIN
    }

    /** Up next's rows at most. */
    const val ROWS = 3
}
