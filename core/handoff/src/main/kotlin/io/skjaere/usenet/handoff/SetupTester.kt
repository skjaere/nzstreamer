package io.skjaere.usenet.handoff

import java.util.concurrent.Future

/** How an item fared in a test from the phone's page: it worked, it failed, or it wasn't tried (its words say why). */
enum class SetupOutcome { Passed, Failed, NotTested }

/**
 * One item's line on the page after a test from the phone: its [outcome], and the [words] the TV's own forms would
 * show for it. The words go back to the phone only; [toString] has the outcome alone, as they may name a host.
 * [plainHttp]: not tested only because the address is plain http over the internet, so the page warns and offers to
 * test it anyway.
 */
data class SetupResult(val outcome: SetupOutcome, val words: String, val plainHttp: Boolean = false) {
    override fun toString() = "SetupResult($outcome)"
}

/**
 * The app's tests of a card from the phone's page: the TV's forms' own tests, of what the card holds, saving nothing.
 * [test] is called on a connection's thread and returns at once, the work under way; the server waits on the future
 * for at most [SetupLimits.testMillis], and cancels it when it gives up on it, when a newer test replaces it, when Next
 * or Back leaves its step, when the form is sent, and when the session ends.
 */
fun interface SetupTester {
    fun test(item: SetupTestItem): Future<SetupResult>
}

/**
 * One card's test from the page, as the app's tester gets it: its [slot], and for a provider, an indexer, a debrid
 * service or a torrent indexer the saved record it is ([Provider.ref], null for a new card) with the values to test. An
 * empty password or key is the card's saved one, which the app uses only under its own same-server rule; the TV binds
 * it to the card through its own slot to record map, never through anything the page sent.
 */
sealed interface SetupTestItem {
    val slot: SetupSlot

    data class Provider(override val slot: SetupSlot, val ref: String?, val draft: ProviderDraft) : SetupTestItem

    /** [plainHttp]: the phone chose Test anyway after the page warned that the address isn't encrypted. */
    data class Indexer(override val slot: SetupSlot, val ref: String?, val draft: IndexerDraft, val plainHttp: Boolean) : SetupTestItem

    /** A debrid service account: [ref] its saved account, null for a new card. */
    data class Service(override val slot: SetupSlot, val ref: String?, val draft: ServiceDraft) : SetupTestItem

    /** A torrent indexer, as [Indexer]. */
    data class TorrentIndexer(override val slot: SetupSlot, val ref: String?, val draft: TorrentIndexerDraft, val plainHttp: Boolean) : SetupTestItem

    /** [key] empty: the saved key. */
    data class Tmdb(val key: String) : SetupTestItem {
        override val slot: SetupSlot get() = SetupSlot.Tmdb

        override fun toString() = "Tmdb(key=${shown(key)})"
    }
}
