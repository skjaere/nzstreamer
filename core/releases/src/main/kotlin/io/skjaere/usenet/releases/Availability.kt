package io.skjaere.usenet.releases

/**
 * Whether a torrent can play from the user's debrid service, as its cache check settled it (L199): the words a list shows
 * for it name no service (W80). An NZB has none: it plays from Usenet.
 */
sealed interface Availability {
    /** The service accounts that said they have it cached, by their ids, in the order they are tried (the user's order). */
    data class Cached(val accounts: List<String>) : Availability

    /** Every account asked said it isn't cached: hidden, never tried (W71). */
    data object NotCached : Availability

    /** No account said yes, and one couldn't be asked or the check ran out of time: hidden, never tried. */
    data object NotChecked : Availability

    /** From a torrent indexer whose "Use with your debrid service" is off: nothing of it went to any service. */
    data object NotForService : Availability
}
