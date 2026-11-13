package io.skjaere.usenet.app.link

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.play.OkGuard
import io.skjaere.usenet.app.setup.countdown
import io.skjaere.usenet.link.PairOutcome
import io.skjaere.usenet.data.LinkStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The TV's question as Pair a phone shows it (W3, L22): "Pair with [name]?" from [address], with the six [digits] the
 * phone shows too. [ok] or [cancel] answers it. [name] is cleaned already (L28). Prints as its type alone: never the
 * number or the name in a log (L13).
 */
class PhoneQuestion(val name: String, val address: String, val digits: String, private val answer: (Boolean) -> Unit) {
    fun ok() = answer(true)

    fun cancel() = answer(false)

    override fun toString() = "PhoneQuestion"
}

/** What Pair a phone hears of the listener's pairing, in order. */
sealed interface PairPageEvent {
    /** A phone asks to pair: its [question] shows until it is answered or closes. */
    data class Asking(val question: PhoneQuestion) : PairPageEvent

    /** Whether another phone waits behind the one asking (L22). */
    data class Waiting(val another: Boolean) : PairPageEvent

    /** The ceremony of [question] ended for [outcome]; null when it ended before its number showed. */
    data class Ended(val question: PhoneQuestion?, val outcome: PairOutcome) : PairPageEvent

    /** Pairing closed by itself, its 10 minutes up (L22). */
    data object Closed : PairPageEvent
}

/** The page that takes pairings, as the listener tells it what happens: on the listener's own thread, in order. */
fun interface PairPageListener {
    fun heard(event: PairPageEvent)
}

/** What Pair a phone shows (PtTvPair). */
sealed interface PairScreen {
    /** Before the listener is up. */
    data object Starting : PairScreen

    /** No Wi-Fi or Ethernet network with a private address: nothing a phone at home can reach. */
    data object NoNetwork : PairScreen

    /** This TV's link key couldn't be loaded: pairing can't start here now. */
    data object NoKey : PairScreen

    /** Waiting for a phone; [address] is the TV's, for a phone that can't find it by itself (L12). */
    data class Waiting(val address: String) : PairScreen

    /** [question], with "Another phone is also asking to pair" while [another] waits. */
    data class Asking(val question: PhoneQuestion, val another: Boolean) : PairScreen

    /** The phone [name] at [address] can now play on this TV. */
    data class Paired(val name: String, val address: String) : PairScreen

    /** Both said yes to [name], and this TV couldn't keep it: [full], it keeps [LinkStore.MAX_PEERS] phones already. */
    data class NotKept(val name: String, val full: Boolean) : PairScreen

    /** Pairing closed by itself (L41). */
    data object Closed : PairScreen
}

/**
 * Pair a phone's state: the [listener] as it is, the [question] asking since [askedAt] (the page's clock) with
 * [another] phone waiting or not, the phone just [paired] or [notKept], whether pairing [closed] by itself, and how
 * many [phones] this TV keeps.
 */
data class PairPage(
    val listener: TvListener = TvListener.Off,
    val question: PhoneQuestion? = null,
    val askedAt: Long = 0,
    val another: Boolean = false,
    val paired: PairScreen.Paired? = null,
    /** The name of the phone both said yes to that this TV couldn't keep. */
    val notKept: String? = null,
    val closed: Boolean = false,
    val phones: Int = 0,
) {
    /** The page once it heard [event] at [now]. */
    fun heard(event: PairPageEvent, now: Long): PairPage = when (event) {
        is PairPageEvent.Asking -> copy(question = event.question, askedAt = now, paired = null, notKept = null)
        is PairPageEvent.Waiting -> copy(another = event.another)
        is PairPageEvent.Ended -> when {
            event.question == null -> this
            event.outcome == PairOutcome.Paired -> copy(question = null, paired = PairScreen.Paired(event.question.name, event.question.address))
            event.outcome == PairOutcome.NotSaved -> copy(question = null, notKept = event.question.name)
            else -> copy(question = question.takeUnless { it === event.question })
        }
        PairPageEvent.Closed -> copy(question = null, closed = true)
    }

    val screen: PairScreen
        get() {
            val asking = question
            return when {
                closed -> PairScreen.Closed
                asking != null -> PairScreen.Asking(asking, another)
                paired != null -> paired
                notKept != null -> PairScreen.NotKept(notKept, full = phones >= LinkStore.MAX_PEERS)
                listener is TvListener.Listening -> PairScreen.Waiting(tvAddress(listener))
                listener == TvListener.NoNetwork -> PairScreen.NoNetwork
                listener == TvListener.NoKey -> PairScreen.NoKey
                else -> PairScreen.Starting
            }
        }

    /** On while the page waits for a phone and while a phone asks; a TV left on what came of it, or on no network, may sleep. */
    val keepsScreenOn: Boolean
        get() = screen.let { it == PairScreen.Starting || it is PairScreen.Waiting || it is PairScreen.Asking }

    /** How long the question has left at [now], of [questionMillis]. */
    fun left(now: Long, questionMillis: Long): Long = askedAt + questionMillis - now
}

/** The TV's address as a phone takes it (L12): "ip" on the default port, else "ip:port". */
fun tvAddress(listening: TvListener.Listening): String =
    listening.address.hostAddress.orEmpty() + if (listening.port == PortChoice.DEFAULT) "" else ":${listening.port}"

/** "Closes by itself in 0:54 if nobody answers.": the question's time left, rounded up, so it reads 0:01 to the end. */
fun closesIn(leftMillis: Long): String = "Closes by itself in ${countdown(leftMillis)} if nobody answers."

/** The number in threes, "482 913", as both screens show it. */
fun numberShown(digits: String): String = if (digits.length == 6) "${digits.take(3)} ${digits.drop(3)}" else digits

/** The number as TalkBack reads it, digit by digit (L43). */
fun numberSpoken(digits: String): String = digits.toList().joinToString(" ")

const val PAIR_PHONE_TITLE = "Pair a phone"
const val PAIR_WAITING = "Waiting for a phone…"
const val PAIR_ANOTHER = "Another phone is also asking to pair"
const val PAIR_CLOSED = "Pairing closed. Open Pair a phone again to pair."
const val PAIR_NO_KEY = "Pairing can’t start on this TV"
const val PAIR_NO_KEY_LINE = "This TV couldn’t open the key it pairs with. Restart NZStreamer here, then open Pair a phone again."

/** "Galaxy phone isn’t paired": both said yes, and this TV couldn't keep it. */
fun notKeptTitle(name: String) = "$name isn’t paired"
const val PAIR_NOT_KEPT_FULL = "This TV keeps 16 phones. Remove one in Settings › Phones, then pair again."
const val PAIR_NOT_KEPT = "This TV couldn’t keep it. Try again."

/** What Pair a phone runs on: the app's listener ([of]), or a fake that a test drives. */
class PairPhoneDeps(
    /** The TV's own name (L28), as phones are told it. */
    val name: String,
    val listener: StateFlow<TvListener>,
    /** Takes pairings for the page from now. */
    val open: (PairPageListener) -> Unit,
    /** Takes no more for it. */
    val close: (PairPageListener) -> Unit,
    val questionMillis: () -> Long,
    /** Monotonic milliseconds: what the countdown counts by. */
    val now: () -> Long,
    /** How long an OK on Waiting as it shows (again as a question goes) is ignored: the play sheet's (OkGuard). */
    val okGuardMillis: Long = OkGuard.WINDOW_MS,
    /** How long an OK on a question that just showed is ignored: [QUESTION_GUARD_MS]. */
    val questionGuardMillis: Long = QUESTION_GUARD_MS,
    /** How many phones this TV keeps, as it changes. */
    val phones: Flow<Int> = flowOf(0),
) {
    companion object {
        /**
         * The question's guard, longer than a sheet's: it comes unprompted with Pair focused, so an OK pressed by reflex as
         * it appears must not pair; as long as the phone setup's review's, unprompted with Save focused.
         */
        const val QUESTION_GUARD_MS = 1_000L

        fun of(c: AppContainer) = PairPhoneDeps(
            name = c.tvLink.name,
            listener = c.tvLink.state,
            open = c.tvLink::openPairing,
            close = c.tvLink::closePairing,
            questionMillis = { playOnTimings().questionMillis },
            now = SystemClock::elapsedRealtime,
            phones = c.data.link.phones.map { it.size },
        )
    }
}

/**
 * Pair a phone (W3, L22): the TV takes pairings while the page is in front ([start] to [stop]) and no longer, so nobody
 * else on the network can put a question on its screen. What the listener says comes on its own thread and is handled
 * here in turn; a word from an opening that has ended is dropped. A page started again starts afresh.
 */
class PairPhoneViewModel(private val deps: PairPhoneDeps) : ViewModel() {
    private val _page = MutableStateFlow(PairPage())
    val page: StateFlow<PairPage> = _page.asStateFlow()

    /** The listener's words, tagged by the opening they are for. */
    private val events = Channel<Pair<Int, PairPageEvent>>(Channel.UNLIMITED)
    private var opening = 0
    private var open: PairPageListener? = null

    init {
        viewModelScope.launch {
            for ((from, event) in events) if (from == opening && open != null) _page.update { it.heard(event, deps.now()) }
        }
        viewModelScope.launch { deps.listener.collect { listener -> _page.update { it.copy(listener = listener) } } }
        viewModelScope.launch { deps.phones.collect { phones -> _page.update { it.copy(phones = phones) } } }
    }

    /** The page is in front: the TV takes pairings from now. */
    fun start() {
        if (open != null) return
        val number = ++opening
        _page.update { PairPage(listener = it.listener, phones = it.phones) }
        val listener = PairPageListener { events.trySend(number to it) }
        open = listener
        deps.open(listener)
    }

    /** The page is covered or gone: no more pairings, a question showing closed with it. */
    fun stop() {
        val listener = open ?: return
        open = null
        opening++
        deps.close(listener)
    }

    override fun onCleared() {
        stop()
        events.close()
    }
}
