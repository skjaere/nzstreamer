package io.skjaere.usenet.app.link

import io.skjaere.usenet.app.casting.CastDeviceFacts
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.casting.CastLinkNow
import io.skjaere.usenet.app.casting.NoCast
import io.skjaere.usenet.data.PairedTv
import io.skjaere.usenet.link.KeyId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What is chosen in Play on (W11, L77): This phone, a paired TV by its key, or a Cast device by its route and name. */
sealed interface PlayChoice {
    data object Phone : PlayChoice

    data class Tv(val key: KeyId) : PlayChoice {
        override fun toString() = "Tv(${key.short})"
    }

    /** The Cast device at MediaRouter's [route], named [name]. It prints without its name. */
    data class Cast(val route: String, val name: String) : PlayChoice {
        override fun toString() = "Cast"
    }
}

/**
 * Where Play on is chosen from (L148), which the Cast button's face and the list go by: a title's page ([Title]: Home,
 * the Show and Movie pages, Collection, Discover, the remote, and the router's own openings), whose Play goes wherever is
 * chosen; a screen whose Play still plays on this phone with a TV chosen ([PhoneOnly]: Search, the release lists and
 * Import, W11); or the [Player]. Opened from either of the last two, the list shows your TVs greyed, as they play from a
 * title's page (L116), and This phone ticked with a TV chosen (W59).
 */
enum class SheetPlace { Title, PhoneOnly, Player }

/** Whether This phone is the choice: its row ticked. */
val PlayChoice.thisPhone: Boolean get() = this == PlayChoice.Phone

/**
 * Where Play goes (W11, L77): this phone, a paired TV, or a Cast device. A reader matches it with an exhaustive `when`, so
 * a kind added later fails to compile until each says what it does with it.
 */
sealed interface PlayTarget {
    data object Phone : PlayTarget

    /**
     * The paired TV [key], under the [name] this phone keeps it by, and its [state] as its link last read it: null until
     * it has been tried. It prints without its name.
     */
    data class Tv(val key: KeyId, val name: String, val state: TvState?) : PlayTarget {
        override fun toString() = "Tv(${key.short}, $state)"
    }

    /**
     * The Cast device at MediaRouter's [route], named [name], and how its session is ([link]): the phone runs the play and
     * the device plays what the phone serves. What the session's route said of the device goes with it ([device], L153),
     * null until a session with it is there. It prints without its name or the device's facts.
     */
    data class Cast(val route: String, val name: String, val link: CastLink, val device: CastDeviceFacts? = null) : PlayTarget {
        override fun toString() = "Cast($link)"
    }
}

/**
 * The Cast device [choice] as Play goes to it, with the Cast [session] there is: as its session is, with what the session's
 * route said of the device (L153), while the session is [choice]'s; connecting, with no facts, while there is none or it
 * is another device's.
 */
internal fun castTarget(choice: PlayChoice.Cast, session: CastLinkNow?): PlayTarget.Cast {
    val mine = session?.takeIf { it.route == choice.route }
    return PlayTarget.Cast(choice.route, choice.name, mine?.link ?: CastLink.Connecting, mine?.device)
}

/**
 * Where Play, Resume and Up next go (W11, W18, L36, L77): This phone, a TV chosen on the Play on sheet, or a Cast device,
 * app-wide. The choice is held in memory alone, never written to link.json or anywhere else: each new launch starts on
 * This phone, and MainActivity's saved state carries a TV across a recreation ([restore], [saved]). The chosen TV's link
 * is held while it is chosen ([hold]), so its state is known and a Play goes at once. Forgetting it makes This phone the
 * choice (L30). [tvs] are the TVs kept, [states] what their links read. It also says whether the Play on sheet is open,
 * and from where ([sheet]), and which of its questions is asked ([asking]).
 *
 * A Cast device is chosen as its row is tapped, and stays so while its session lives, as castExtras' connection says
 * ([cast]: none on a TV and in a build without Cast): connecting, connected, reconnecting. When the session ends, fails
 * to start, or is another device's, This phone is chosen again (W36); a play's end leaves the session, and the choice,
 * as they are (L85). A live session wins over a new launch and a recreation (W38, L64), and is never saved: a new
 * process has none. Choosing This phone or a TV while casting ends the cast first ([endCast], L85). Cast anyway, told
 * for a Cast speaker's session ([castAnyway], L122), lasts that session alone, and a speaker's question goes with it.
 */
class PlayOn(
    private val scope: CoroutineScope,
    private val tvs: Flow<List<PairedTv>>,
    states: Flow<Map<KeyId, TvNow>>,
    private val cast: StateFlow<CastLinkNow?> = NoCast,
    private val endCast: () -> Unit = {},
    hold: (KeyId) -> AutoCloseable,
) {
    private val _chosen = MutableStateFlow<PlayChoice>(PlayChoice.Phone)
    private val _picked = MutableStateFlow<PlayChoice>(PlayChoice.Phone)
    private val _sheet = MutableStateFlow<SheetPlace?>(null)
    private val _asking = MutableStateFlow<PlayOnAsk?>(null)

    /** The route of the Cast session told Cast anyway for its speaker (L122); null with none, and once that session ends. */
    private val _castAnyway = MutableStateFlow<String?>(null)

    /** What is chosen: This phone, a TV, or a Cast device. */
    val chosen: StateFlow<PlayChoice> = _chosen.asStateFlow()

    /**
     * The choice made last on purpose ([choose], [restore]): what is chosen, but for This phone coming back by itself as
     * the chosen Cast device's session ended, failed or went to another device (W36), which leaves it as it was. A release
     * list on screen ranks again for a choice made, never for that (L127).
     */
    val picked: StateFlow<PlayChoice> = _picked.asStateFlow()

    /**
     * Where Play goes now: the chosen TV as it is kept and read, the chosen Cast device as its session is (connecting until
     * the connection says more), else This phone.
     */
    val target: StateFlow<PlayTarget> = combine(_chosen, tvs, states, cast) { choice, rows, now, session ->
        when (choice) {
            PlayChoice.Phone -> PlayTarget.Phone
            is PlayChoice.Tv -> rows.firstOrNull { it.key == choice.key }?.let { PlayTarget.Tv(choice.key, it.name, now[choice.key]?.state) } ?: PlayTarget.Phone
            is PlayChoice.Cast -> castTarget(choice, session)
        }
    }.stateIn(scope, SharingStarted.Eagerly, PlayTarget.Phone)

    init {
        scope.launch {
            _chosen.collectLatest { choice ->
                val key = (choice as? PlayChoice.Tv)?.key ?: return@collectLatest
                val held = hold(key)
                try {
                    awaitCancellation()
                } finally {
                    held.close()
                }
            }
        }
        // The chosen TV forgotten leaves This phone chosen: one that was kept and went, never one just added, whose row
        // a list read a moment before doesn't have yet. A question about a TV forgotten goes with it.
        scope.launch {
            var before = emptySet<KeyId>()
            tvs.collect { rows ->
                val now = rows.mapNotNullTo(HashSet()) { it.key }
                val gone = before - now
                (_chosen.value as? PlayChoice.Tv)?.takeIf { it.key in gone }?.let { chosen ->
                    // An update question is the chosen TV's.
                    _asking.value?.takeIf { it is PlayOnAsk.Update }?.let { _asking.compareAndSet(it, null) }
                    _chosen.compareAndSet(chosen, PlayChoice.Phone)
                }
                _asking.value?.takeIf { it.key in gone }?.let { _asking.compareAndSet(it, null) }
                before = now
            }
        }
        // The chosen Cast device's session ended, failed to start, or is another device's: This phone (W36). The sheet's
        // row says connecting before it chooses the device, so a choice never meets the moment before its session. Both
        // are read as they are now: a change of either only wakes this, as its watcher may hear the choice before the
        // session that came first. A speaker's Cast anyway and its question go with its session (L122): a question left
        // is cancelled, so nothing it would have played plays.
        scope.launch {
            merge(_chosen, cast).collect {
                val choice = _chosen.value
                val route = cast.value?.route
                if (choice is PlayChoice.Cast && route != choice.route) _chosen.compareAndSet(choice, PlayChoice.Phone)
                _castAnyway.value?.takeIf { it != route }?.let { _castAnyway.compareAndSet(it, null) }
                (_asking.value as? PlayOnAsk.SoundOnly)?.takeIf { it.route != route }?.let { ask ->
                    if (_asking.compareAndSet(ask, null)) ask.answered(PlayOnAsk.SoundOnly.Answer.Cancel)
                }
            }
        }
    }

    /** Plays go to [choice] from now; This phone or a TV chosen while casting ends the cast first (L85). */
    fun choose(choice: PlayChoice) {
        if (choice !is PlayChoice.Cast && cast.value != null) endCast()
        _picked.value = choice
        _chosen.value = choice
    }

    /** Plays go to the TV [tv] from now; null for This phone. */
    fun choose(tv: KeyId?) = choose(tv?.let(PlayChoice::Tv) ?: PlayChoice.Phone)

    /**
     * MainActivity is made (W18): with no saved state (a new launch) This phone is chosen again; with the state it saved,
     * [saved], the TV that was chosen stays so (a rotation, a recreation, back from Recents). A Cast session alive in this
     * process wins over both: Play goes to it, as the bar shows it (W38, L64).
     */
    fun restore(saved: String?) {
        val live = cast.value?.let { PlayChoice.Cast(it.route, it.name) }
        val key = saved?.takeIf { live == null }?.let(KeyId::parse)
        val choice = live ?: key?.let(PlayChoice::Tv) ?: PlayChoice.Phone
        _picked.value = choice
        _chosen.value = choice
        // One forgotten since it was saved (in another process): This phone.
        if (key != null) scope.launch { if (tvs.first().none { it.key == key }) _chosen.compareAndSet(PlayChoice.Tv(key), PlayChoice.Phone) }
    }

    /** What MainActivity keeps across a recreation: the chosen TV's key id, or null for This phone; never a Cast device's (L64). */
    val saved: String? get() = (_chosen.value as? PlayChoice.Tv)?.key?.hex

    /**
     * Where the Play on sheet was opened from, null while it is closed (L148): the Cast button opens it from its screen's
     * place; Play opens it from a title's page while the chosen TV isn't open or the Cast device connects (the router's).
     */
    val sheet: StateFlow<SheetPlace?> = _sheet.asStateFlow()

    fun openSheet(place: SheetPlace = SheetPlace.Title) {
        _sheet.value = place
    }

    fun closeSheet() {
        _sheet.value = null
    }

    /** The question Play or a row of the sheet asks now (PtPhonePairFail): pair again, or update; null for none. */
    val asking: StateFlow<PlayOnAsk?> = _asking.asStateFlow()

    fun ask(ask: PlayOnAsk) {
        _asking.value = ask
    }

    /** The question was answered, or put away. */
    fun answered() {
        _asking.value = null
    }

    /**
     * Cast anyway, told for the Cast session at [route] (L122): its speaker isn't asked about again while it lasts. Told
     * as its session had already gone, it is forgotten with it.
     */
    fun castAnyway(route: String) {
        if (cast.value?.route == route) _castAnyway.value = route
    }

    /** Whether Cast anyway was told for the Cast session at [route], which still lasts. */
    fun castAnywayTold(route: String): Boolean = _castAnyway.value == route
}
