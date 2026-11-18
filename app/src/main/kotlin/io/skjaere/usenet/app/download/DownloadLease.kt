package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.debrid.DebridAdds

/**
 * The owner a debrid download's add is held by in [DebridAdds] (L421): the download's own lease, by its [id], so a second
 * hold of it changes nothing. A download outlives the 2-minute grace a play's add gets, so its torrent stays in the
 * account from Resolve's find to the download's end, through Pause, Resume and its waits, and across a process's start.
 */
data class DownloadLease(val id: String)

/**
 * What holds a debrid download's add in its account (L421): [DebridAdds]' leases, each download's its own
 * ([DownloadLease]). The runner holds it at each write of a torrent and lets it go at the download's end; the debrid
 * writer moves it with the torrent to the next account's listing (L424); the app's start holds it again for every download
 * still to finish with its listing's copy.
 */
interface DownloadLeases {
    /** Download [id]'s add held on the listing [listingId], alone: one it held before is let go, to go a grace later. */
    fun hold(id: String, listingId: String)

    /** Download [id] lets go of its add, which goes a grace later where nothing else holds it. */
    fun release(id: String)

    companion object {
        /** No lease: what a test of something else gives, and what holds nothing. */
        val None: DownloadLeases = object : DownloadLeases {
            override fun hold(id: String, listingId: String) = Unit

            override fun release(id: String) = Unit

            override fun toString() = "DownloadLeases.None"
        }

        /** The app's: [adds]' lease of each download, by its id. */
        fun of(adds: DebridAdds): DownloadLeases = object : DownloadLeases {
            override fun hold(id: String, listingId: String) = adds.holdOnly(DownloadLease(id), listingId)

            override fun release(id: String) = adds.release(DownloadLease(id))

            override fun toString() = "DownloadLeases"
        }
    }
}
