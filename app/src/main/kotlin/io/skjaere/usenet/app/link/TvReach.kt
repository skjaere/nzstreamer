package io.skjaere.usenet.app.link

import io.skjaere.usenet.link.LinkClose
import io.skjaere.usenet.link.LinkMessage.Hello
import io.skjaere.usenet.link.Need
import io.skjaere.usenet.link.Refusal

/** A paired TV as this phone reaches it (L12, L30, L41): what Settings › TVs, and later the Play on sheet, say of it. */
enum class TvState {
    /** Its link is up, and it can play: a provider and an indexer set up there, or its debrid service (L223). */
    Ready,

    /** Not reached: NZStreamer isn't open there (L45), the TV is off or asleep, or another TV has its address. */
    NotOpen,

    /** Reached, but it has no usable Usenet provider. */
    NeedsProvider,

    /** Reached, with a provider, but no indexer switched on. */
    NeedsIndexer,

    /** Reached, set up only for a debrid service, and none signed in there (L223). */
    NeedsService,

    /** Reached, set up only for a debrid service, and none on: switched off, or its key refused or unreadable. */
    ServiceOff,

    /** Reached, its debrid service on, and no torrent indexer switched on there. */
    NeedsTorrentIndexer,

    /** NZStreamer there was set up again: another key where it was, under its name, and mDNS can't find its own (L12, L30). */
    SetUpAgain,

    /** It refused this phone as not paired: Remove on the TV (L30). */
    RemovedHere,

    /** Its NZStreamer is too old for this phone's (L26). */
    UpdateTv,

    /** This phone's NZStreamer is too old for the TV's, or the TV gave a reason this version doesn't know (L26). */
    UpdatePhone,
}

/** How a phone reads what its TVs say and do (item 13): decisions alone, so they are tested on the JVM. */
object TvReach {
    /**
     * A link up: what the TV's hello says of it (L25, L223). It plays by Usenet, a provider and an indexer, or by its
     * debrid service. Else, a TV set up only for its debrid way says what that way lacks ([Hello.Ready.needs]); any other
     * says it by Usenet, a provider first: without one nothing plays there, whatever its indexers. A lack this version
     * doesn't know, a newer TV's, reads by Usenet too. A TMDB key isn't needed: the phone sends what a search needs (L6).
     * A hello without readiness never makes a link (the TV doesn't know the phone), so null is taken as not reached.
     */
    fun linked(ready: Hello.Ready?): TvState = when {
        ready == null -> TvState.NotOpen
        ready.provider && ready.indexer || ready.debrid -> TvState.Ready
        ready.needs == Need.Service -> TvState.NeedsService
        ready.needs == Need.ServiceOff -> TvState.ServiceOff
        ready.needs == Need.TorrentIndexer -> TvState.NeedsTorrentIndexer
        !ready.provider -> TvState.NeedsProvider
        else -> TvState.NeedsIndexer
    }

    /**
     * Whether the TV plays from its debrid service alone, as its hello says (L223): its service's way ready and no usable
     * provider, so its own sheet lists the service's rows while it searches (its searchingSteps), before it sends them.
     */
    fun fromService(ready: Hello.Ready?): Boolean = ready != null && ready.debrid && !ready.provider

    /**
     * The TV refused this phone, or this phone the TV (L26, L30). Busy, and the refusals a pairing alone meets, leave it
     * as not reached for now: the next try may be let in.
     */
    fun refused(reason: Refusal): TvState = when (reason) {
        Refusal.NotPaired -> TvState.RemovedHere
        Refusal.UpdateTv -> TvState.UpdateTv
        Refusal.UpdatePhone, Refusal.Unknown -> TvState.UpdatePhone
        Refusal.Busy, Refusal.NotPairing, Refusal.TooMany -> TvState.NotOpen
    }

    /**
     * Another key at the TV's address (L12): DHCP may have given the address to another NZStreamer TV, so it reads
     * NotOpen. It reads SetUpAgain only when the other key's hello gives the TV's saved [name] and mDNS has run
     * [setUpAgainMillis] without finding the pinned id ([unseenMillis]: how long it has gone unseen while discovery ran;
     * null while discovery isn't running).
     */
    fun keyChanged(name: String, saved: String, unseenMillis: Long?, setUpAgainMillis: Long): TvState =
        if (name == saved && unseenMillis != null && unseenMillis >= setUpAgainMillis) TvState.SetUpAgain else TvState.NotOpen

    /** A link closed: a refusal on it as [refused] reads it (busy past the TV's links), anything else not reached. */
    fun closed(reason: LinkClose): TvState = if (reason is LinkClose.Refused) refused(reason.reason) else TvState.NotOpen

    /** How long to wait before the next try, after [tries] (L36): 2, 4, 8 and 16 s, then every 30 s. */
    fun backoff(tries: Int): Long = WAITS.getOrElse(tries) { WAITS.last() }

    private val WAITS = listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
}
