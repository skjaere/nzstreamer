package io.skjaere.usenet.app.link

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.saveOrLog
import io.skjaere.usenet.data.LinkStore
import io.skjaere.usenet.data.PairedTv
import io.skjaere.usenet.handoff.SetupServer
import io.skjaere.usenet.link.ConnectFailure
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.PairAttempt
import io.skjaere.usenet.link.PairOutcome
import io.skjaere.usenet.link.Refusal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.net.InetSocketAddress

/** A TV taking pairings nearby (L27: `pair=1`), not paired here yet: its record's [id], its [name] and where it listens. */
data class NearbyTv(val id: String, val name: String, val address: Inet4Address, val port: Int)

/** Where a pairing goes: a TV picked from those nearby, with its [name], or an address [typed] by hand. */
data class PairTarget(val address: InetSocketAddress, val name: String?, val typed: Boolean = false)

/** Why Add a TV ended without a TV (PtPhonePairFail, L41): each says what to do next ([failureTitle]). */
enum class AddTvFailure {
    /** No on this phone: the numbers didn't match. */
    NoMatch,

    /** Cancel on the TV. */
    CancelledOnTv,

    /** The TV isn't on its Pair a phone page (L41). */
    NotPairing,

    /** Three failed tries from this phone since Pair a phone opened there (L22, L41). */
    TooMany,

    /** The TV pairs with two phones already. */
    Busy,
    UpdateTv,
    UpdatePhone,

    /** No connection, or TLS and the hellos didn't come in time. */
    Unreachable,

    /** The TV's question, or a step, went unanswered in time. */
    TimedOut,

    /** Pair a phone closed on the TV meanwhile. */
    ClosedOnTv,

    /** The TV couldn't keep this phone. */
    NotSaved,

    /** The TV's reveal didn't match its commit: another device may sit between them (L21). */
    Interfered,

    /** Anything else that broke the ceremony. */
    Stopped,

    /** This phone keeps as many TVs as it can (L29). */
    Full,
    ;

    companion object {
        /** What [result] is to Add a TV: null for a pairing, and for one cancelled here, which shows nothing. */
        fun of(result: PairAttempt.Result): AddTvFailure? = when (result) {
            is PairAttempt.Result.Paired -> null
            is PairAttempt.Result.Refused -> when (result.reason) {
                Refusal.NotPairing -> NotPairing
                Refusal.TooMany -> TooMany
                Refusal.Busy -> Busy
                Refusal.UpdateTv -> UpdateTv
                // A reason this version doesn't know means a newer TV (L26).
                Refusal.UpdatePhone, Refusal.Unknown -> UpdatePhone
                Refusal.NotPaired -> Stopped
            }
            is PairAttempt.Result.Stopped -> when (result.outcome) {
                PairOutcome.No -> NoMatch
                PairOutcome.Declined -> CancelledOnTv
                PairOutcome.TimedOut -> TimedOut
                PairOutcome.Closed -> ClosedOnTv
                PairOutcome.NotSaved -> NotSaved
                PairOutcome.WrongCommit -> Interfered
                PairOutcome.Paired, PairOutcome.Stopped, PairOutcome.Broken, PairOutcome.Left -> Stopped
            }
            is PairAttempt.Result.Failed -> if (result.why == ConnectFailure.Closed) null else Unreachable
        }
    }
}

/** Where Add a TV is (PtPhonePair, PtPhonePairFail). */
sealed interface AddTvStep {
    /** TVs nearby, and the way to type an address. */
    data object Nearby : AddTvStep

    /** Enter the TV's address. */
    data object Address : AddTvStep

    data class Connecting(val target: PairTarget) : AddTvStep

    /** The TV pairs with another phone first; this one waits its turn (L22, L41). */
    data class Waiting(val target: PairTarget) : AddTvStep

    /** Both screens show [digits]: Yes or No. [name] is the TV's own, cleaned (L28). */
    data class Compare(val target: PairTarget, val name: String, val digits: String) : AddTvStep

    /** Yes said here: Pair on the TV next. */
    data class Confirming(val target: PairTarget, val name: String, val digits: String) : AddTvStep

    /** The TV [name] is paired and kept here, under its [key]. */
    data class Paired(val name: String, val key: KeyId) : AddTvStep

    /** It ended without a TV, for [failure]; Try again goes to [target] again. [name] is the TV's, when known. */
    data class Failed(val target: PairTarget?, val failure: AddTvFailure, val name: String? = target?.name) : AddTvStep
}

/** Add a TV's state: its [step], the TVs [nearby], whether [nothingYet] was found in its time, and the address [typed]. */
data class AddTvState(
    val step: AddTvStep = AddTvStep.Nearby,
    val nearby: List<NearbyTv> = emptyList(),
    val nothingYet: Boolean = false,
    val typed: String = "",
    val typedError: String? = null,
)

/** What a pairing shows its user ([io.skjaere.usenet.link.PairHandler] in the app's words), on the pairing's own thread. */
interface PairPrompt {
    /** The TV pairs with another phone: this one waits its turn. */
    fun waiting()

    /** Both screens show [digits]; [answer] says Yes (true) or No, once. [tvName] is the TV's own word, untrusted (L28). */
    fun compare(tvName: String, digits: String, answer: (Boolean) -> Unit)
}

/** What Add a TV runs on: the app's links ([of]), or fakes a test drives. */
class AddTvDeps(
    /** What discovery finds while it runs. */
    val discovery: Flow<Discovery?>,
    /** Discovery runs while a page looks: held while Add a TV shows. */
    val look: () -> AutoCloseable,
    /** The TVs this phone keeps. */
    val tvs: Flow<List<PairedTv>>,
    /** A pairing with the TV at an address, its prompt shown on the way; cancelled with the coroutine. */
    val pair: suspend (InetSocketAddress, PairPrompt) -> PairAttempt.Result,
    /** Keeps a TV paired: its key, its name (cleaned), its address and port; false when there is no room. */
    val save: suspend (KeyId, String, Inet4Address, Int) -> Boolean,
    /** After this with nothing found, Enter the TV's address becomes the main way (PtPhonePairFail). */
    val nothingFoundMillis: Long = NOTHING_FOUND_MS,
    /** Forgets a TV kept here: the one a Pair again replaces, or a plain Add of that TV set up again. */
    val forget: suspend (KeyId) -> Unit = {},
    /**
     * How long mDNS has run without a kept TV's own id, by now, as [TvLinks] counts it for the row's SetUpAgain (L12): a
     * record lost a moment ago counts as seen until it went; null while discovery isn't running.
     */
    val unseen: (KeyId) -> Long? = { null },
    /** How long [unseen] must be before a TV under a kept one's name, at its address, is taken for it set up again (L12). */
    val setUpAgainMillis: Long = TvLinksDeps.SET_UP_AGAIN_MS,
) {
    companion object {
        const val NOTHING_FOUND_MS = 10_000L

        fun of(c: AppContainer) = of(c.tvLinks, c.data.link)

        fun of(links: TvLinks, store: LinkStore) = AddTvDeps(
            discovery = links.discovery,
            look = links::look,
            tvs = store.tvs,
            pair = links::pair,
            save = { key, name, address, port -> store.addTv(key, name, address.hostAddress.orEmpty(), port) },
            forget = links::forget,
            unseen = links::unseenMillis,
            setUpAgainMillis = links.setUpAgainMillis,
        )
    }
}

/**
 * Add a TV (W3, L21, L22): the TVs nearby taking pairings, or an address typed, then the pairing: connecting, the
 * waiting phone's words, the number with Yes and No, "Now choose Pair", and the TV kept here once it said it saved this
 * phone. Discovery runs while the page is open ([start] to [stop]). Cancel, or Back, stops a pairing under way; nothing is
 * saved on either device until both have said yes (L22). A failure says what to do next, with Try again. Opened by Pair
 * again for the TV [again] (L30), that TV is listed though paired, and one set up again is replaced by its new key. A
 * plain Add replaces a kept TV too, without asking, once L12's check takes the new key for it set up again.
 */
class AddTvViewModel(private val deps: AddTvDeps, private val again: KeyId? = null) : ViewModel() {
    private val _state = MutableStateFlow(AddTvState())
    val state: StateFlow<AddTvState> = _state.asStateFlow()

    private var looking: AutoCloseable? = null
    private var quiet: Job? = null
    private var quietPassed = false
    private var pairing: Job? = null

    /** Counts the pairings: a word from one cancelled or replaced has nothing to say. */
    @Volatile private var runs = 0
    @Volatile private var answer: ((Boolean) -> Unit)? = null

    init {
        viewModelScope.launch {
            combine(deps.discovery, deps.tvs) { found, tvs -> nearby(found, tvs) }.collect { tvs ->
                _state.update { it.copy(nearby = tvs, nothingYet = quietPassed && tvs.isEmpty()) }
            }
        }
        viewModelScope.launch {
            // Room for one more, before anything is asked of a TV (L29); a Pair again takes its TV's place.
            val kept = deps.tvs.first()
            if (kept.size >= LinkStore.MAX_PEERS && kept.none { it.key == again }) _state.update { it.copy(step = AddTvStep.Failed(null, AddTvFailure.Full)) }
        }
    }

    /**
     * The page shows: discovery runs until the page is gone ([stop], as it is cleared), and the time for "nothing found"
     * runs from the first time. A rotation or a quick look elsewhere doesn't start it again: the list stays.
     */
    fun start() {
        if (looking == null) looking = deps.look()
        if (quiet == null) {
            quiet = viewModelScope.launch {
                delay(deps.nothingFoundMillis)
                quietPassed = true
                _state.update { it.copy(nothingYet = it.nearby.isEmpty()) }
            }
        }
    }

    /** The page is gone: discovery stops. */
    fun stop() {
        looking?.close()
        looking = null
    }

    fun pick(tv: NearbyTv) = run(PairTarget(InetSocketAddress(tv.address, tv.port), tv.name))

    fun enterAddress() = _state.update { it.copy(step = AddTvStep.Address) }

    fun type(text: String) = _state.update { it.copy(typed = text, typedError = null) }

    /** Connect on the typed address: a private IPv4 one alone, with or without its port (L12). */
    fun connect() {
        val address = parseTvAddress(_state.value.typed)
        if (address == null) {
            _state.update { it.copy(typedError = TYPE_ADDRESS_HELP_BAD) }
            return
        }
        run(PairTarget(address, name = null, typed = true))
    }

    /** Yes, the numbers match: the TV's Pair next. */
    fun yes() {
        val step = _state.value.step as? AddTvStep.Compare ?: return
        answer?.invoke(true) ?: return
        answer = null
        _state.update { it.copy(step = AddTvStep.Confirming(step.target, step.name, step.digits)) }
    }

    /** No: the numbers differ. The TV hears it, and nothing is saved. */
    fun no() {
        answer?.invoke(false)
        answer = null
    }

    /** Stops the pairing under way; back to where it started. */
    fun cancel() {
        val target = when (val step = _state.value.step) {
            is AddTvStep.Connecting -> step.target
            is AddTvStep.Waiting -> step.target
            is AddTvStep.Compare -> step.target
            is AddTvStep.Confirming -> step.target
            else -> return
        }
        runs++
        answer = null
        pairing?.cancel()
        _state.update { it.copy(step = if (target.typed) AddTvStep.Address else AddTvStep.Nearby) }
    }

    /** After a failure, the same TV again, afresh. */
    fun tryAgain() {
        val failed = _state.value.step as? AddTvStep.Failed ?: return
        failed.target?.let(::run)
    }

    /** Back within the page: true when it did something here; false leaves Add a TV. */
    fun back(): Boolean = when (_state.value.step) {
        AddTvStep.Address -> {
            _state.update { it.copy(step = AddTvStep.Nearby, typedError = null) }
            true
        }
        is AddTvStep.Connecting, is AddTvStep.Waiting, is AddTvStep.Compare, is AddTvStep.Confirming -> {
            cancel()
            true
        }
        else -> false
    }

    override fun onCleared() {
        stop()
        pairing?.cancel()
    }

    private fun run(target: PairTarget) {
        pairing?.cancel()
        val run = ++runs
        answer = null
        _state.update { it.copy(step = AddTvStep.Connecting(target)) }
        fun post(step: (AddTvStep) -> AddTvStep?) = _state.update { state -> if (runs != run) state else step(state.step)?.let { state.copy(step = it) } ?: state }
        val prompt = object : PairPrompt {
            override fun waiting() = post { AddTvStep.Waiting(target) }

            override fun compare(tvName: String, digits: String, answer: (Boolean) -> Unit) {
                if (runs != run) return
                this@AddTvViewModel.answer = answer
                post { AddTvStep.Compare(target, PeerNames.clean(tvName, target.name ?: "TV"), digits) }
            }
        }
        pairing = viewModelScope.launch {
            val result = deps.pair(target.address, prompt)
            if (runs != run) return@launch
            answer = null
            val known = (_state.value.step as? AddTvStep.Compare)?.name ?: (_state.value.step as? AddTvStep.Confirming)?.name ?: target.name
            val step = if (result is PairAttempt.Result.Paired) {
                val name = PeerNames.clean(result.name, target.name ?: "TV")
                // The old row first: with as many TVs kept as there may be, the new one takes its room.
                replaced(result.tv, name, target)?.let { old -> saveOrLog("forget a TV paired again") { deps.forget(old) } }
                if (keep(result.tv, name, target)) AddTvStep.Paired(name, result.tv) else AddTvStep.Failed(target, AddTvFailure.Full, name)
            } else {
                AddTvFailure.of(result)?.let { AddTvStep.Failed(target, it, known) } ?: if (target.typed) AddTvStep.Address else AddTvStep.Nearby
            }
            log.info("Play on TV: pairing with a TV ended ({})", if (step is AddTvStep.Failed) step.failure else "paired")
            _state.update { it.copy(step = step) }
        }
    }

    /**
     * The TV [tv] takes the place of: NZStreamer there set up again, a new key under the name kept here (L30). From Pair
     * again, the TV it was for, under that name. From a plain Add, with no question asked, a kept TV of that name last
     * reached at [target]'s address that L12's check takes for set up again, as its row's SetUpAgain does
     * ([TvReach.keyChanged]): mDNS has run [AddTvDeps.setUpAgainMillis] without its own id, a record lost a moment ago
     * counting as seen ([AddTvDeps.unseen]). So it isn't kept twice, one of them "Set up again" for good. The same key
     * keeps its row (paired again in its place); another TV, of another name, elsewhere, or with the kept one's id seen
     * in that time, leaves it be.
     */
    private suspend fun replaced(tv: KeyId, name: String, target: PairTarget): KeyId? {
        val rows = deps.tvs.first()
        again?.let { old -> return old.takeIf { it != tv && rows.any { row -> row.key == old && row.name == name } } }
        val at = target.address.address.hostAddress
        return rows.firstNotNullOfOrNull { row ->
            row.key?.takeIf { old ->
                old != tv && row.address == at &&
                    TvReach.keyChanged(name, row.name, deps.unseen(old), deps.setUpAgainMillis) == TvState.SetUpAgain
            }
        }
    }

    private suspend fun keep(tv: KeyId, name: String, target: PairTarget): Boolean = try {
        deps.save(tv, name, target.address.address as Inet4Address, target.address.port)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Play on TV: couldn't keep a TV that paired", e)
        false
    }

    /** The TVs taking pairings that aren't paired here yet, at the address each listens on (its `v4` first). */
    private fun nearby(found: Discovery?, tvs: List<PairedTv>): List<NearbyTv> {
        // A Pair again's TV is listed, paired or not: it removed this phone (L30).
        val paired = tvs.mapNotNullTo(HashSet()) { it.key?.takeIf { key -> key != again }?.short }
        return found?.tvs.orEmpty()
            .filter { it.record.pairing && it.record.id !in paired && it.addresses.isNotEmpty() }
            .map { NearbyTv(it.record.id, it.record.name, it.record.v4?.takeIf(it.addresses::contains) ?: it.addresses.first(), it.port) }
            .distinctBy { it.id }
    }

    private companion object {
        val log = LoggerFactory.getLogger(AddTvViewModel::class.java)
    }
}

/**
 * A typed address (L12): "ip", on the default port, or "ip:port", as the TV's Pair a phone page shows it. RFC 1918
 * IPv4 alone, written as four numbers; null for anything else.
 */
fun parseTvAddress(text: String): InetSocketAddress? {
    val typed = text.trim()
    val port = if (':' in typed) typed.substringAfter(':').toIntOrNull()?.takeIf { it in 1..MAX_PORT } ?: return null else PortChoice.DEFAULT
    val ip = parseIpv4(typed.substringBefore(':'))?.takeIf(SetupServer::isPrivateIpv4) ?: return null
    return InetSocketAddress(ip, port)
}

private const val MAX_PORT = 65_535

const val ADD_TV_TITLE = "Add a TV"
const val ADD_TV_HINT = "On the TV, open NZStreamer › Settings › Phones › Pair a phone. The TV then shows here."
const val TVS_NEARBY = "TVs nearby"
const val LOOKING = "Looking"
const val STILL_LOOKING = "Still looking"
const val NOT_LISTED = "Not listed? Some networks hide TVs from phones."
const val NO_TVS_YET = "No TVs found yet"
const val SAME_NETWORK = "Your phone and TV must be on the same network. Guest Wi-Fi and VPNs can hide the TV."
const val ENTER_ADDRESS = "Enter the TV’s address"
const val ADDRESS_HINT = "The TV shows its address on Settings › Phones › Pair a phone."
const val ADDRESS_LABEL = "Address"
const val ADDRESS_HELP = "As the TV shows it on its Pair a phone page."
const val TYPE_ADDRESS_HELP_BAD = "Type the address as the TV shows it, such as 192.168.1.42."
const val COMPARE_HOW = "Compare it with the number on the TV. If they match, choose Yes here, then Pair on the TV."
const val YES_IT_MATCHES = "Yes, it matches"
const val WAITING_FOR_TV = "Waiting for the TV…"

/** The paired screen's line (PtPhonePair 5): how to play there. */
fun pairedLine(name: String) = "To play there, choose $name in Play on. NZStreamer must be open on the TV when you play."

/** "Connecting to Living room TV…", or to "the TV" at a typed address. */
fun connectingTo(name: String?) = "Connecting to ${name ?: "the TV"}…"

fun doesItShow(name: String) = "Does $name show this number?"

fun choosePairOn(name: String) = "Now choose Pair on $name"

fun isReady(name: String) = "$name is ready"

/** The waiting phone's words (L41). */
fun waitingWords(name: String?) = "${name ?: "The TV"} is pairing with another device. If it shows a number now, choose Cancel on the TV."

/** A failure's title: what happened, or, where that says it all, what to do. */
fun failureTitle(failure: AddTvFailure, name: String?): String {
    val tv = name ?: "The TV"
    return when (failure) {
        AddTvFailure.NoMatch, AddTvFailure.TimedOut, AddTvFailure.NotSaved, AddTvFailure.Interfered, AddTvFailure.Stopped -> STOPPED
        AddTvFailure.CancelledOnTv -> "Pairing was cancelled on the TV. Nothing was saved."
        AddTvFailure.NotPairing -> "Open Settings › Phones › Pair a phone on the TV, then try again."
        AddTvFailure.TooMany -> "Too many tries. On the TV, leave Pair a phone and open it again."
        AddTvFailure.Busy -> "$tv is busy. Try again in a moment."
        AddTvFailure.UpdateTv -> "Update NZStreamer on the TV"
        AddTvFailure.UpdatePhone -> "Update NZStreamer on this phone"
        AddTvFailure.Unreachable -> "Couldn’t reach ${name ?: "the TV"}"
        AddTvFailure.ClosedOnTv -> "Pairing closed on the TV. Nothing was saved."
        AddTvFailure.Full -> "This phone keeps ${LinkStore.MAX_PEERS} TVs. Forget one in Settings › TVs to add another."
    }
}

/** A failure's line under its title, if it has one. */
fun failureText(failure: AddTvFailure, name: String?): String? {
    val tv = name ?: "The TV"
    return when (failure) {
        AddTvFailure.NoMatch -> "The numbers didn’t match, so your phone may have reached another device. Check the TV’s name and try again."
        AddTvFailure.UpdateTv -> "$tv has an older NZStreamer that can’t pair with this phone. Update it on the TV, then try again."
        AddTvFailure.UpdatePhone -> "$tv has a newer NZStreamer that can’t pair with this phone. Update it on this phone, then try again."
        AddTvFailure.Unreachable -> SAME_NETWORK
        AddTvFailure.TimedOut -> "The TV didn’t answer in time. Try again."
        AddTvFailure.NotSaved -> "$tv couldn’t keep this phone. It keeps ${LinkStore.MAX_PEERS} phones at most: remove one there, under Settings › Phones."
        AddTvFailure.Interfered -> "Your phone may have reached another device. Check the TV’s name and try again."
        AddTvFailure.Stopped -> "Something went wrong between your phone and the TV. Try again."
        AddTvFailure.CancelledOnTv, AddTvFailure.NotPairing, AddTvFailure.TooMany, AddTvFailure.Busy, AddTvFailure.ClosedOnTv, AddTvFailure.Full -> null
    }
}

private const val STOPPED = "Pairing stopped. Nothing was saved."
