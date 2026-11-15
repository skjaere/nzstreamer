package io.skjaere.usenet.app.download

import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadPrefs

/**
 * The downloads' job as plain parts (item 9, L335, L376, C5), what [DownloadJobService] builds its JobInfo from: one
 * user-initiated data transfer job for the whole queue ([namespace] "downloads", [id] 1, no work items), persisted so a
 * reboot keeps it, needing an [unmetered] network (Wi-Fi or Ethernet) or any, and the [charging] or not, as the settings
 * say (W110, L365), and expecting [downloadBytes] down and nothing up. No storage-not-low constraint ([storageNotLow],
 * L376): the app's own reserve decides, and Android's low-storage line could otherwise stop a download whose own
 * allocation took the device near it (C14).
 */
data class JobPlan(
    val unmetered: Boolean,
    val charging: Boolean,
    val downloadBytes: Long,
    val namespace: String = NAMESPACE,
    val id: Int = ID,
    val userInitiated: Boolean = true,
    val persisted: Boolean = true,
    val storageNotLow: Boolean = false,
    val uploadBytes: Long = 0,
) {
    companion object {
        /** The job's own namespace (JobScheduler.forNamespace, Android 14's), so no other job of the app shares its id. */
        const val NAMESPACE = "downloads"

        /** The one job of the queue (L335). */
        const val ID = 1

        /** JobInfo.NETWORK_BYTES_UNKNOWN: no size known of what is to fetch. */
        const val UNKNOWN_BYTES = -1L

        /** The job for [rows], the queue as it is, under [prefs]' switches. */
        fun of(prefs: DownloadPrefs, rows: List<Download>): JobPlan {
            val needs = DownloadHostRules.needs(prefs)
            return JobPlan(needs.unmetered, needs.charging, remaining(rows.map { DownloadRunner.Item(it, it.safeOffset, null) }))
        }

        /**
         * What is left to fetch of [items]' downloads that can run (L388), as the job goes: each one's size, its exact one
         * once resolved, else the indexer's, less what is written of it. [UNKNOWN_BYTES] where none of them has a size yet.
         */
        fun remaining(items: List<DownloadRunner.Item>): Long {
            val runnable = DownloadHostRules.runnable(items.map { it.row }).map { it.id }.toSet()
            val sized = items.filter { it.row.id in runnable }.map { (it.row.size ?: it.row.indexerSize) to it.written }.filter { (total, _) -> total > 0 }
            if (sized.isEmpty()) return if (runnable.isEmpty()) 0 else UNKNOWN_BYTES
            return sized.sumOf { (total, written) -> (total - written).coerceAtLeast(0) }
        }
    }
}

/**
 * What the job has fetched and expects to fetch, for Android's overloads without a work item (C5): [transferred] is what
 * the downloads' files grew by since the job started, never what was written before it; [estimated] adds what is left,
 * so it holds still while bytes only move from one to the other, and changes as the queue does.
 */
class JobBytes {
    // The bytes written of each download, as last seen.
    private val last = HashMap<String, Long>()

    var transferred = 0L
        private set

    var estimated = JobPlan.UNKNOWN_BYTES
        private set

    /** The runner's state, as it is now. */
    fun seen(items: List<DownloadRunner.Item>) {
        items.forEach { item ->
            val before = last.put(item.row.id, item.written)
            // A file started again from 0 (a release given up) fetches what it fetches from there.
            if (before != null && item.written > before) transferred += item.written - before
        }
        val left = JobPlan.remaining(items)
        estimated = if (left == JobPlan.UNKNOWN_BYTES) left else transferred + left
    }
}
