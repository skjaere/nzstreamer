package io.skjaere.usenet.app.download

import io.skjaere.usenet.core.MIN_FREE_BYTES
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadState

/**
 * What downloads take of the primary volume and what a new one may take (L348), as the Download sheet, the Downloads
 * page's strip and Resolve read them. [available]: what downloads may take there without Android clearing any app's
 * cache to make room, this app's watched video and TMDB's answers among them: what is free on the volume, or what Android
 * can allocate where that is less (getAllocatableBytes counts the caches it would clear, less its own low-storage
 * reserve). [used]: what downloads hold, each file made so far at its whole size, as it is allocated whole before its
 * first byte (C8): the running one counts whole. [queued]: what the downloads still to make their file need, their exact
 * size once resolved, else the indexer's; nothing of it allocated yet.
 */
data class DownloadSpace(val available: Long, val used: Long, val queued: Long) {
    /** What a new download may take: [available] less what is kept free ([KEPT_FREE]) and [queued]; never below 0. */
    val free: Long get() = (available - KEPT_FREE - queued).coerceAtLeast(0)

    /** Whether a download of [size] bytes fits in what is [free]. */
    fun fits(size: Long): Boolean = size <= free

    companion object {
        /**
         * Kept free for the phone: what the video cache leaves free. Android's own low-storage line is below it (500 MB at
         * most), so an allocation that leaves this much of what is free finds its room free, without clearing a cache, and
         * the device stays above that line (C14).
         */
        const val KEPT_FREE = MIN_FREE_BYTES

        // Those that go on without the user's word: a failed one waits for Try again, whose Resolve checks the room again.
        private val GOING_ON = setOf(
            DownloadState.Queued, DownloadState.Resolving, DownloadState.Running, DownloadState.Waiting, DownloadState.Paused, DownloadState.Stopped,
        )

        /**
         * The space with [allocatable] bytes Android can give (getAllocatableBytes) and [usable] bytes free on the volume,
         * downloads taking the less of the two, and [rows] downloaded or on their way. A row with its file made (its
         * relative path set) holds its whole size; one without, still to go on, needs it; one being deleted counts as gone,
         * hidden as it is (L379), though Android counts its file until its Undo has gone. The row [except] names, the one
         * a check is for (Resolve's of its exact size), counts in neither: its size is the one checked against [free].
         */
        fun of(allocatable: Long, usable: Long, rows: List<Download>, except: String? = null): DownloadSpace {
            val counted = rows.filter { it.state != DownloadState.Deleting && it.id != except }
            val (made, toMake) = counted.partition { it.relativePath != null }
            return DownloadSpace(
                available = minOf(allocatable, usable),
                used = made.sumOf { it.wholeSize },
                queued = toMake.filter { it.state in GOING_ON }.sumOf { it.wholeSize },
            )
        }

        /** The file's size: exact once resolved, else the indexer's. */
        private val Download.wholeSize: Long get() = size ?: indexerSize
    }
}
