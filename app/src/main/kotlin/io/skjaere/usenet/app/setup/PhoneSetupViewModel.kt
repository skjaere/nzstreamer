package io.skjaere.usenet.app.setup

import android.content.Context
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.TmdbLookup
import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.app.debrid.keyFailure
import io.skjaere.usenet.app.debrid.save
import io.skjaere.usenet.app.debrid.signIn
import io.skjaere.usenet.app.debrid.signedInLine
import io.skjaere.usenet.app.home.SetupState
import io.skjaere.usenet.app.settings.IndexerTest
import io.skjaere.usenet.app.settings.ProviderForm
import io.skjaere.usenet.app.settings.ProviderTestResult
import io.skjaere.usenet.app.settings.ProviderTests
import io.skjaere.usenet.app.settings.indexerConnected
import io.skjaere.usenet.app.settings.providerForm
import io.skjaere.usenet.app.settings.save
import io.skjaere.usenet.app.settings.testIndexer
import io.skjaere.usenet.app.settings.testLogin
import io.skjaere.usenet.app.settings.tmdbKeyCheck
import io.skjaere.usenet.data.ServiceSignIn
import io.skjaere.usenet.data.SettingsRepository
import io.skjaere.usenet.debrid.DebridService
import io.skjaere.usenet.debrid.ServiceException
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.handoff.SavedSecret
import io.skjaere.usenet.handoff.SetupChanges
import io.skjaere.usenet.handoff.SetupEvent
import io.skjaere.usenet.handoff.SetupLimits
import io.skjaere.usenet.handoff.SetupMode
import io.skjaere.usenet.handoff.SetupOutcome
import io.skjaere.usenet.handoff.SetupRejection
import io.skjaere.usenet.handoff.SetupSeed
import io.skjaere.usenet.handoff.SetupServer
import io.skjaere.usenet.handoff.SetupSlot
import io.skjaere.usenet.handoff.SetupStep
import io.skjaere.usenet.handoff.SetupTester
import io.skjaere.usenet.handoff.SetupToken
import io.skjaere.usenet.metadata.KeyCheck
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.IndexerKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.Inet4Address

/**
 * A session's page, as the view model holds it: [url] for the QR code and [display] to read off the screen both hold
 * the code, so they are shown, never logged, routed or saved. [deadline] is when the code stops working, on
 * [PhoneSetupDeps.now]'s clock, as the server has it now ([SetupEvent.DeadlineMoved] tells of it moving with use, once
 * the request that moved it is answered). [close] ends it at once.
 */
interface SetupSession : AutoCloseable {
    val url: String
    val display: String
    val deadline: Long

    override fun close()
}

/**
 * What a session's page opens with: on [ip], the [mode]'s page (the wizard or the edit page) from [seed], what is saved,
 * and [from], what an earlier session sent when this one is its Send again, testing cards with [tester] and telling
 * [onEvent] what happens.
 */
class SetupOpening(
    val ip: Inet4Address,
    val mode: SetupMode,
    val seed: SetupSeed,
    val from: SetupChanges?,
    val tester: SetupTester,
    val onEvent: (SetupEvent) -> Unit,
)

/** What the phone setup reads and writes: the app's ([of]), or fakes, so its view model runs in a JVM test. */
class PhoneSetupDeps(
    val settings: SettingsRepository,
    /** Where a provider's test result goes, for the providers list. */
    val providerTests: ProviderTests,
    /** The TV's address on its home network as it changes, while collected; null while it has none. */
    val addresses: Flow<Inet4Address?>,
    /** A session's page opened as its [SetupOpening] says (bound when it returns, else an IOException). */
    val openSession: (SetupOpening) -> SetupSession,
    /** A provider's login test, with the password to use. */
    val testProvider: suspend (ProviderForm, String) -> ProviderTestResult,
    val testIndexer: suspend (IndexerConfig) -> IndexerTest,
    val validateTmdbKey: suspend (String) -> KeyCheck,
    /** Monotonic milliseconds: the clock the server keeps the code's lifetime on, and the countdown counts down by. */
    val now: () -> Long,
    /** Told as the page starts and stops, so a phone's Play is refused meanwhile (L32). */
    val pages: SetupPages = SetupPages(),
    /** A client for a debrid key not saved yet (the registry's trial): a service card's test signs in on it, marking nothing. */
    val trialService: (ServiceKind, String) -> DebridService = { _, _ -> error("no debrid services") },
    /** Whether this build shows debrid ([DebridGate.shown]): hidden, the page has no debrid cards. */
    val debridShown: () -> Boolean = { DebridGate.shown },
) {
    companion object {
        /** How long a code works and the server's other limits: the design's. A test seam: the device tests shorten the lifetime. */
        @VisibleForTesting
        @Volatile
        internal var limits: SetupLimits = SetupLimits()

        fun of(c: AppContainer, context: Context) = PhoneSetupDeps(
            settings = c.settings,
            providerTests = c.providerTests,
            addresses = LanAddress.watch(context.applicationContext),
            openSession = { opening -> ServerSession.open(opening, SystemClock::elapsedRealtime) },
            testProvider = { f, password -> testLogin(f.host, f.portValue ?: 563, f.username, password, f.useTls, f.connectionsValue ?: 1) },
            testIndexer = { config -> testIndexer(c.newznab, c.caps, config) },
            validateTmdbKey = { TmdbLookup.validate(c, it) },
            now = SystemClock::elapsedRealtime,
            pages = c.setupPages,
            trialService = c.services::trial,
        )
    }
}

/** A session served by [SetupServer] on the home network, with a new code. */
private class ServerSession(private val server: SetupServer) : SetupSession {
    override val url: String get() = server.address.url
    override val display: String get() = server.address.display
    override val deadline: Long get() = server.deadline

    override fun close() = server.close()

    companion object {
        /** [opening]'s page, its code's lifetime kept by the server on [clock], the app's own: the countdown shows the server's deadline. */
        fun open(opening: SetupOpening, clock: () -> Long): SetupSession {
            val server = SetupServer(
                opening.ip, token = SetupToken.generate(), mode = opening.mode, seed = opening.seed, from = opening.from, clock = clock,
                limits = PhoneSetupDeps.limits, tester = opening.tester, onEvent = opening.onEvent,
            )
            server.start()
            log.info("phone setup: listening on {}", server.address.authority)
            return ServerSession(server)
        }
    }
}

/** A change or a new item saved, on the results. */
const val PHONE_SETUP_SAVED = "Saved"

/** A removal done. */
const val PHONE_SETUP_REMOVED = "Removed"

/** An item that failed on the TV and was saved all the same. */
const val PHONE_SETUP_SAVED_ANYWAY = "Saved untested"

/** An item that failed on the TV and was left out of the save. */
const val PHONE_SETUP_LEFT_OUT = "Left out"

/** A change whose record went from the TV, or changed its server, while Save ran: it isn't saved. */
const val PHONE_SETUP_CHANGED_MEANWHILE = "Not saved: it changed on this TV meanwhile."

/** How an item of what arrived fares at Save. */
sealed interface ItemStatus {
    /** Its test on the TV runs. */
    data object Running : ItemStatus

    /** It passed, from the phone ([PHONE_TESTED]) or here, with [message], its test's words. */
    data class Passed(val message: String) : ItemStatus

    /**
     * It failed ([message]): it can be tried again ([canRetry]: not an item that can't be saved as sent), saved anyway
     * ([canSaveAnyway]: a provider or an indexer, as their forms save untested), or left out.
     */
    data class Failed(val message: String, val canRetry: Boolean, val canSaveAnyway: Boolean) : ItemStatus

    /** It failed ([message]), and is to be saved all the same. */
    data class Anyway(val message: String) : ItemStatus

    /** It failed, and is left out of the save. */
    data object LeftOut : ItemStatus

    /** What Save did with it: [message], and whether it is saved as sent ([ok]). */
    data class Done(val message: String, val ok: Boolean) : ItemStatus
}

/**
 * A test the phone ran from its page, as Waiting tells of it: under way, or how each card's latest one went, by the
 * card's place ([SetupSlot]), never by anything typed. Only the outcomes: the words went to the phone.
 */
sealed interface PhoneTest {
    data object Running : PhoneTest

    data class Finished(val outcomes: Map<SetupSlot, SetupOutcome>) : PhoneTest
}

/** The phone setup page's state. */
sealed interface PhoneSetupState {
    /** Before a code is open: the page just shown, or its address being found. */
    data object Starting : PhoneSetupState

    /** No Wi-Fi or Ethernet network with a private address: nothing a phone at home can reach. */
    data object NoNetwork : PhoneSetupState

    /**
     * A code open at [url] ([display] to read), until [expiresAt] ([left] ms from now), its page the [mode]'s, the
     * phone at [step] of it, with [wrongTries] wrong codes so far, the page opened from [openedBy] (an address), the
     * phone's tests from its page ([phoneTest]), and the last form that couldn't be read ([rejected]), until a form is
     * read after it.
     */
    data class Waiting(
        val url: String,
        val display: String,
        val expiresAt: Long,
        val left: Long,
        val mode: SetupMode = SetupMode.Wizard,
        val step: SetupStep = SetupStep.Providers,
        val wrongTries: Int = 0,
        val openedBy: String? = null,
        val rejected: SetupRejection? = null,
        val phoneTest: PhoneTest? = null,
    ) : PhoneSetupState {
        override fun toString() = "Waiting(left=$left, mode=$mode, step=$step, wrongTries=$wrongTries, opened=${openedBy != null}, rejected=$rejected, phoneTest=$phoneTest)"
    }

    data object Expired : PhoneSetupState

    /** Closed after too many wrong codes. */
    data object Locked : PhoneSetupState

    /** The address the code was on changed or went. */
    data object NetworkChanged : PhoneSetupState

    /** What arrived, to review: nothing saved yet. */
    data class Received(val review: SetupReview) : PhoneSetupState

    /** Save's tests on the TV under way ([retry]: the one item tried again, the others' results standing); nothing saved. */
    data class Testing(val review: SetupReview, val items: Map<String, ItemStatus>, val retry: String? = null) : PhoneSetupState

    /**
     * Save's tests have run and some failed: nothing is saved. Each failure is tried again, saved anyway or left out;
     * once none is left ([settled]), Save saves everything. [retried] is the item last tried again, if any.
     */
    data class Results(val review: SetupReview, val items: Map<String, ItemStatus>, val retried: String? = null) : PhoneSetupState {
        val settled: Boolean get() = items.values.none { it is ItemStatus.Failed }

        /**
         * The item whose buttons take the focus, null for Save: the one just tried again while it still fails, so the
         * next OK tries it again; else the next failing one after it, else the first.
         */
        val focus: String?
            get() {
                val failing = items.filterValues { it is ItemStatus.Failed }.keys.toList()
                val retried = retried ?: return failing.firstOrNull()
                if (retried in failing) return retried
                val order = items.keys.toList()
                return failing.firstOrNull { order.indexOf(it) > order.indexOf(retried) } ?: failing.firstOrNull()
            }
    }

    /** Save writing everything, at once, as [items] leave it: a moment. */
    data class Saving(val review: SetupReview, val items: Map<String, ItemStatus> = emptyMap()) : PhoneSetupState

    /** Everything was saved, removed or left out: what each item came to. */
    data class Saved(val review: SetupReview, val items: Map<String, ItemStatus>) : PhoneSetupState
}

/**
 * "Set up with your phone": while the page is in front ([start] to [stop]) a one-time page on the home network, the
 * wizard or the edit page by what is saved, for the phone to add, change or remove providers, indexers, debrid service
 * accounts and torrent indexers (those two while debrid shows, L209) and the TMDB key through. What arrives is held here
 * only, never in a route, saved state or a log, and reviewed; nothing is saved until Save, which tests on the TV what the
 * phone didn't test with the same values and then saves everything at once.
 */
class PhoneSetupViewModel(private val deps: PhoneSetupDeps) : ViewModel() {
    private val _state = MutableStateFlow<PhoneSetupState>(PhoneSetupState.Starting)
    val state: StateFlow<PhoneSetupState> = _state.asStateFlow()

    private val _savedSomething = MutableStateFlow(false)

    /**
     * Whether this page has saved anything, in any round: a form under it read its record before, and its Save would
     * write the old values back, so every way out of the page then closes that form too.
     */
    val savedSomething: StateFlow<Boolean> = _savedSomething.asStateFlow()

    private var started = false
    private var session: SetupSession? = null
    /** Where [session]'s tests from the phone run: ending the session cancels them. */
    private var sessionWork: CoroutineScope? = null
    /** The address [session] listens on, and the last one the network told of. */
    private var listening: Inet4Address? = null
    private var latest: Inet4Address? = null
    private var watching: Job? = null
    /** The saved settings being read for a session about to open. */
    private var opening: Job? = null
    private var countdown: Job? = null
    /** When [session]'s code stops working, as its server last said. */
    private var deadline = 0L
    private var tests: Job? = null
    /** Each item's status before a Try again, which Back puts back; null in the first round. */
    private var round: Map<String, ItemStatus>? = null
    /** How Save's tests on the TV of providers went, by item, for the providers list. */
    private val tvResults = mutableMapOf<String, ProviderTestResult>()
    /** What a service said of each debrid account Save's tests on the TV signed in to, by item: Save keeps it with the key. */
    private val tvSignIns = mutableMapOf<String, ServiceSignIn>()
    /** Send again: what arrived, for the next code's page to start from; null for a page from what is saved alone. */
    private var resumeFrom: SetupChanges? = null
    /** Counts sessions: an event from one that has ended is dropped, but for [cutOff]'s Received. */
    private var generation = 0
    /**
     * The session the page stopping, its code expiring or the network changing just ended. The phone may have been
     * answered "Sent" a moment before, its form on its way here: that Received is still taken. New code and Discard
     * forget it, as the user chose to start again.
     */
    private var cutOff: Ended? = null
    /** The session's tests from the phone that passed, with exactly what each tested. */
    private var passes = PassLog()
    /** The session's page: the wizard or the edit page. */
    private var sessionMode = SetupMode.Wizard
    /** How each card last fared in a test from the phone, this session, by its place. */
    private var phoneOutcomes = emptyMap<SetupSlot, SetupOutcome>()
    /** The servers' events, which come on their own threads, handled here in turn. */
    private val events = Channel<Pair<Int, SetupEvent>>(Channel.UNLIMITED)

    private val holdsPayload: Boolean
        get() = _state.value.let {
            it is PhoneSetupState.Received || it is PhoneSetupState.Testing || it is PhoneSetupState.Results || it is PhoneSetupState.Saving ||
                it is PhoneSetupState.Saved
        }

    init {
        viewModelScope.launch {
            for ((from, event) in events) {
                val cut = cutOff?.takeIf { it.number == from && event is SetupEvent.Received }
                if (from == generation || cut != null) handle(event, cut)
            }
        }
    }

    /** The page is in front: a code is opened, unless what the phone sent is still here to review. */
    fun start() {
        if (started) return
        started = true
        deps.pages.started()
        if (!holdsPayload) {
            _state.value = PhoneSetupState.Starting
            watch()
        }
    }

    /** The page is covered or gone: the code is closed and forgotten; what the phone sent stays to review. */
    fun stop() {
        if (started) deps.pages.stopped()
        started = false
        watching?.cancel()
        watching = null
        cut()
        endSession()
        if (!holdsPayload) _state.value = PhoneSetupState.Starting
    }

    /**
     * New code, Send again: another code opened, what arrived (if anything) dropped. Before it is saved, the new page
     * starts from what arrived, typed passwords and keys still on the TV, each only for the server it was sent for (the
     * new code's page may be opened by another device first); once saved, from what is saved.
     */
    fun newCode() {
        val s = _state.value
        if (s is PhoneSetupState.Testing || s is PhoneSetupState.Saving) return
        when (s) {
            is PhoneSetupState.Received -> resumeFrom = s.review.changes
            is PhoneSetupState.Results -> resumeFrom = s.review.changes
            is PhoneSetupState.Saved -> resumeFrom = null
            else -> Unit
        }
        endSession()
        cutOff = null
        _state.value = PhoneSetupState.Starting
        if (!started) return
        if (watching == null) watch() else latest?.let(::open) ?: run { _state.value = PhoneSetupState.NoNetwork }
    }

    /** Drops what arrived, saving nothing. */
    fun discard() {
        tests?.cancel()
        resumeFrom = null
        endSession()
        cutOff = null
        _state.value = PhoneSetupState.Starting
    }

    /**
     * Save: tests on the TV, all at once, each item the phone didn't test with exactly what would be saved; once every
     * one has passed, saves everything at once. One that can't be saved as sent fails at once. Nothing is saved while
     * one is failing.
     */
    fun save() {
        val review = (_state.value as? PhoneSetupState.Received)?.review ?: return
        if (review.items.isEmpty()) return
        val statuses = review.items.filter { it.action != ReviewAction.Remove }.associate { item ->
            val problem = item.problem
            item.key to when {
                problem != null -> ItemStatus.Failed(problem, canRetry = false, canSaveAnyway = false)
                item.tested -> ItemStatus.Passed(PHONE_TESTED)
                else -> ItemStatus.Running
            }
        }
        round = null
        tvResults.clear()
        tvSignIns.clear()
        val untested = review.items.filter { statuses[it.key] == ItemStatus.Running }
        if (untested.isEmpty() && statuses.values.none { it is ItemStatus.Failed }) apply(review, statuses) else run(review, statuses, untested, retry = null)
    }

    /** Tests [key] again, after it failed. */
    fun retry(key: String) {
        val results = _state.value as? PhoneSetupState.Results ?: return
        if ((results.items[key] as? ItemStatus.Failed)?.canRetry != true) return
        val item = results.review.item(key) ?: return
        round = results.items
        run(results.review, results.items + (key to ItemStatus.Running), listOf(item), retry = key)
    }

    /** A provider or an indexer that failed, to be saved untested, as its form would save it. Never a TMDB key. */
    fun saveAnyway(key: String) = settle(key) { failed -> ItemStatus.Anyway(failed.message).takeIf { failed.canSaveAnyway } }

    /** A failed item left out of the save. */
    fun leaveOut(key: String) = settle(key) { ItemStatus.LeftOut }

    /** Save, once no failure is left: everything at once. */
    fun saveNow() {
        val results = _state.value as? PhoneSetupState.Results ?: return
        if (results.settled) apply(results.review, results.items)
    }

    /**
     * Back while testing: the tests are stopped, nothing having been saved. After a Try again the results show as they
     * were; else the review again.
     */
    fun cancelTests() {
        val testing = _state.value as? PhoneSetupState.Testing ?: return
        tests?.cancel()
        tests = null
        val before = round
        round = null
        _state.value = if (before != null) PhoneSetupState.Results(testing.review, before, testing.retry) else PhoneSetupState.Received(testing.review)
    }

    override fun onCleared() {
        stop()
        events.close()
    }

    private fun settle(key: String, to: (ItemStatus.Failed) -> ItemStatus?) {
        _state.update { s ->
            if (s !is PhoneSetupState.Results) return@update s
            val failed = s.items[key] as? ItemStatus.Failed ?: return@update s
            to(failed)?.let { s.copy(items = s.items + (key to it)) } ?: s
        }
    }

    private fun watch() {
        watching = viewModelScope.launch {
            deps.addresses.collectLatest { ip ->
                // The system tells of the networks a moment after it is asked: no "no network" for that moment.
                if (ip == null && _state.value == PhoneSetupState.Starting) delay(NO_NETWORK_GRACE_MS)
                latest = ip
                onAddress(ip)
            }
        }
    }

    private fun onAddress(ip: Inet4Address?) {
        when (_state.value) {
            PhoneSetupState.Starting, PhoneSetupState.NoNetwork -> if (ip == null) {
                // A code may be opening on the address that went.
                endSession()
                _state.value = PhoneSetupState.NoNetwork
            } else {
                open(ip)
            }
            is PhoneSetupState.Waiting -> if (ip != listening) {
                log.info("phone setup: the network changed")
                cut()
                endSession()
                _state.value = PhoneSetupState.NetworkChanged
            }
            // A code that ended waits for New code, and what arrived needs no network.
            else -> Unit
        }
    }

    /**
     * Opens a code on [ip]: what is saved decides the page (the wizard until the TV can play) and fills it in. Reading it
     * decrypts each password, which takes a moment, Starting meanwhile; a stop, New code or another network in it wins,
     * and this opening is dropped.
     */
    private fun open(ip: Inet4Address) {
        endSession()
        _state.value = PhoneSetupState.Starting
        val number = generation
        opening = viewModelScope.launch {
            val shown = deps.debridShown()
            val seed = deps.settings.setupSeed(shown)
            // The wizard until either pair is saved (L165): a provider and an indexer, or a service and a torrent indexer.
            val saved = SetupState(
                seed.providers.map { it.name.ifEmpty { it.host } }, seed.indexers.isNotEmpty(), seed.tmdbKey == SavedSecret.Saved, indexerSkipped = false,
                services = seed.services.mapNotNull { s -> seed.serviceKinds.firstOrNull { it.value == s.kind }?.name },
                hasTorrentIndexer = seed.torrentIndexers.isNotEmpty(), shown = shown,
            )
            if (number == generation) listen(ip, if (saved.canPlay) SetupMode.Edit else SetupMode.Wizard, seed, number)
        }
    }

    /** The session [number] on [ip]: its page the [mode]'s from [seed], its countdown to the server's deadline. */
    private fun listen(ip: Inet4Address, mode: SetupMode, seed: SetupSeed, number: Int) {
        // A child of the view model's scope: on its dispatcher, so the server's thread only starts a test, and gone with it.
        val work = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext.job))
        // A pass log of the session's own: what passed in it, for the review of what it sends.
        passes = PassLog()
        val tester = PhoneTester(work, SavedLookup.of(deps.settings), deps.testProvider, deps.testIndexer, deps.validateTmdbKey, passes, deps.trialService)
        val opened = try {
            deps.openSession(SetupOpening(ip, mode, seed, resumeFrom, tester) { event -> events.trySend(number to event) })
        } catch (e: IOException) {
            work.cancel()
            // The address went as it was asked for: as when the network changes.
            log.warn("phone setup: couldn't listen on the home network")
            _state.value = PhoneSetupState.NetworkChanged
            return
        }
        session = opened
        sessionWork = work
        sessionMode = mode
        listening = ip
        deadline = opened.deadline
        val step = if (mode == SetupMode.Wizard) SetupStep.Providers else SetupStep.Edit
        _state.value = PhoneSetupState.Waiting(opened.url, opened.display, deadline, deadline - deps.now(), mode, step)
        countdown = viewModelScope.launch {
            while (true) {
                val left = deadline - deps.now()
                if (left <= 0) {
                    // The server moves its deadline as a request comes in and tells of it once it has answered: a
                    // request in the code's last moment (a Send, say) has moved it already.
                    val moved = session?.deadline ?: deadline
                    if (moved > deadline) {
                        deadline = moved
                        continue
                    }
                    expire()
                    return@launch
                }
                waiting { it.copy(expiresAt = deadline, left = left) }
                // To the next whole second, which the countdown shows.
                delay((left - 1) % 1000 + 1)
            }
        }
    }

    private fun expire() {
        log.info("phone setup: the code expired")
        cut()
        endSession()
        _state.value = PhoneSetupState.Expired
    }

    /** The open session, if any, is about to end without the user starting again: what it already sent is still taken. */
    private fun cut() {
        cutOff = session?.let { Ended(generation, sessionMode, passes) }
    }

    /** Closes the session, if one is open, and stops its tests from the phone; its events from now on are dropped. */
    private fun endSession() {
        opening?.cancel()
        opening = null
        countdown?.cancel()
        countdown = null
        session?.close()
        session = null
        sessionWork?.cancel()
        sessionWork = null
        listening = null
        phoneOutcomes = emptyMap()
        generation++
    }

    private fun waiting(change: (PhoneSetupState.Waiting) -> PhoneSetupState.Waiting) =
        _state.update { if (it is PhoneSetupState.Waiting) change(it) else it }

    /** [event] from the open session, or [cut], a Received from the one that just ended ([cutOff]). */
    private suspend fun handle(event: SetupEvent, cut: Ended? = null) {
        when (event) {
            is SetupEvent.Opened -> waiting { it.copy(openedBy = event.peer.hostAddress) }
            is SetupEvent.WrongTry -> waiting { it.copy(wrongTries = event.count) }
            is SetupEvent.Rejected -> waiting { it.copy(rejected = event.rejection) }
            is SetupEvent.PeerRefused -> log.info("phone setup: refused another device")
            // The server read a form after the refused one: the phone has fixed it.
            SetupEvent.FormRead -> waiting { it.copy(rejected = null) }
            is SetupEvent.TestStarted -> waiting { it.copy(phoneTest = PhoneTest.Running, rejected = null) }
            is SetupEvent.TestFinished -> {
                phoneOutcomes = phoneOutcomes + (event.slot to event.outcome)
                waiting { it.copy(phoneTest = PhoneTest.Finished(phoneOutcomes)) }
            }
            // Next or Back on the phone stopped its test before it ended: the cards are as they were before it.
            is SetupEvent.TestStopped -> waiting { it.copy(phoneTest = if (phoneOutcomes.isEmpty()) null else PhoneTest.Finished(phoneOutcomes)) }
            is SetupEvent.StepChanged -> waiting { it.copy(step = event.step) }
            // The phone used the code: it lasts longer, and the countdown with it.
            is SetupEvent.DeadlineMoved -> {
                deadline = event.at
                waiting { it.copy(expiresAt = event.at, left = event.at - deps.now()) }
            }
            // A request came after the server's own deadline: as when the countdown runs out.
            SetupEvent.Expired -> expire()
            SetupEvent.Locked -> {
                log.info("phone setup: closed after too many wrong codes")
                endSession()
                _state.value = PhoneSetupState.Locked
            }
            is SetupEvent.Received -> {
                log.info("phone setup: a form arrived")
                // Its own session's page and passes: a session opened since has its own.
                val mode = cut?.mode ?: sessionMode
                val sessionPasses = cut?.passes ?: passes
                cutOff = null
                endSession()
                watching?.cancel()
                watching = null
                // Against what is saved now: the page's records may have changed on the TV meanwhile. Who sent it is
                // said on the review too: a sender that opens the page and sends at once skips Waiting's line.
                val saved = SavedSources.read(deps.settings, deps.debridShown())
                _state.value = PhoneSetupState.Received(SetupReview.of(event.changes, saved, sessionPasses, mode, event.peer.hostAddress))
            }
        }
    }

    /** Tests [items] at once, the others' statuses in [statuses] as they are; then the results, or the save when all passed. */
    private fun run(review: SetupReview, statuses: Map<String, ItemStatus>, items: List<ReviewItem>, retry: String?) {
        _state.value = PhoneSetupState.Testing(review, statuses, retry)
        tests = viewModelScope.launch {
            coroutineScope {
                items.forEach { item ->
                    launch {
                        val status = test(item)
                        _state.update { s -> if (s is PhoneSetupState.Testing) s.copy(items = s.items + (item.key to status)) else s }
                    }
                }
            }
            val done = _state.value as? PhoneSetupState.Testing ?: return@launch
            round = null
            // The first round with every item passing saves at once; else the failures wait, nothing saved.
            if (done.retry == null && done.items.values.none { it is ItemStatus.Failed }) {
                apply(done.review, done.items)
            } else {
                _state.value = PhoneSetupState.Results(done.review, done.items, done.retry)
            }
        }
    }

    /** [item]'s test on the TV, as its form runs it, saving nothing. */
    private suspend fun test(item: ReviewItem): ItemStatus = when (item) {
        is ProviderReview -> {
            val result = deps.testProvider(item.form, item.testPassword.orEmpty())
            // For the providers list, passed or not: one saved anyway shows its failed test there.
            tvResults[item.key] = result
            if (result.ok) {
                ItemStatus.Passed(result.message)
            } else {
                ItemStatus.Failed(result.message, canRetry = true, canSaveAnyway = true)
            }
        }
        is IndexerReview -> when (val result = deps.testIndexer(IndexerConfig(item.form.id, item.form.name, item.form.url.trim(), item.testKey.orEmpty()))) {
            is IndexerTest.Ok -> ItemStatus.Passed(indexerConnected(result))
            is IndexerTest.Failed -> ItemStatus.Failed(result.message, canRetry = true, canSaveAnyway = true)
            // The test only ever answers one of those.
            IndexerTest.Idle, IndexerTest.Running -> ItemStatus.Failed("The indexer's test didn't finish.", canRetry = true, canSaveAnyway = true)
        }
        is TorrentIndexerReview -> {
            val config = IndexerConfig(item.form.fields.id, item.form.fields.name, item.form.fields.url.trim(), item.testKey.orEmpty(), kind = IndexerKind.Torznab)
            when (val result = deps.testIndexer(config)) {
                is IndexerTest.Ok -> ItemStatus.Passed(indexerConnected(result))
                is IndexerTest.Failed -> ItemStatus.Failed(result.message, canRetry = true, canSaveAnyway = true)
                // The test only ever answers one of those.
                IndexerTest.Idle, IndexerTest.Running -> ItemStatus.Failed("The indexer's test didn't finish.", canRetry = true, canSaveAnyway = true)
            }
        }
        // As its key form's Save: a key is kept only if the service takes it, so never saved anyway (L196).
        is ServiceReview -> try {
            val account = deps.trialService(item.service, item.apiKey).test()
            tvSignIns[item.key] = account.signIn()
            ItemStatus.Passed(signedInLine(account))
        } catch (e: ServiceException) {
            ItemStatus.Failed(keyFailure(item.service, e.error), canRetry = true, canSaveAnyway = false)
        }
        is TmdbReview -> tmdbKeyCheck(deps.validateTmdbKey(item.apiKey)).let { check ->
            if (check.ok) ItemStatus.Passed(check.message) else ItemStatus.Failed(check.message, canRetry = true, canSaveAnyway = false)
        }
        // Neither is tested: a removal needs none, and a card whose record went is never saved.
        is Removal, is Gone -> ItemStatus.Passed("")
    }

    /**
     * Saves everything at once, as [statuses] leave it: removals first (a provider's test result forgotten with it), then
     * changes over each record read again (one gone meanwhile is said, never brought back, as saving it would), keeping
     * its id, place and on/off, then new ones in the page's order, then the TMDB key, then each saved provider's test
     * result for the providers list. A wizard that ends with a provider and no indexer passes the first run's indexer
     * step, as its "Not now" does. Back can't stop it half way.
     */
    private fun apply(review: SetupReview, statuses: Map<String, ItemStatus>) {
        _state.value = PhoneSetupState.Saving(review, statuses)
        viewModelScope.launch {
            val done = withContext(NonCancellable) { Writes(review, statuses).run() }
            _state.value = PhoneSetupState.Saved(review, done)
        }
    }

    /** One Save's writes, [statuses] saying which items fail and are saved anyway or left out. */
    private inner class Writes(private val review: SetupReview, private val statuses: Map<String, ItemStatus>) {
        private val settings = deps.settings
        /** What a service said of a key it refused as Save signed in: that item's words in place of "changed meanwhile". */
        private val refusals = mutableMapOf<String, String>()

        suspend fun run(): Map<String, ItemStatus> {
            val done = mutableMapOf<String, ItemStatus>()
            val (leftOut, saving) = review.items.partition { statuses[it.key] == ItemStatus.LeftOut }
            leftOut.forEach { done[it.key] = ItemStatus.Done(PHONE_SETUP_LEFT_OUT, ok = false) }
            for (item in saving.filterIsInstance<Removal>()) {
                when (item.kind) {
                    ReviewKind.Provider -> {
                        settings.deleteProvider(item.key)
                        deps.providerTests.forget(item.key)
                    }
                    ReviewKind.Indexer -> settings.deleteIndexer(item.key)
                    // Signed out of on this TV, its key with it; the account itself stays as it is.
                    ReviewKind.Service -> settings.signOut(item.key)
                    ReviewKind.TorrentIndexer -> settings.deleteTorrentIndexer(item.key)
                    // The TMDB key is never removed from the phone.
                    ReviewKind.Tmdb -> Unit
                }
                wrote()
                done[item.key] = ItemStatus.Done(PHONE_SETUP_REMOVED, ok = true)
            }
            val sources = saving.filter { it.kind != ReviewKind.Tmdb }
            val ordered = sources.filter { it.action == ReviewAction.Change } + sources.filter { it.action == ReviewAction.New } + saving.filterIsInstance<TmdbReview>()
            for (item in ordered) {
                val saved = when (item) {
                    is ProviderReview -> provider(item)
                    is IndexerReview -> indexer(item)
                    is ServiceReview -> service(item)
                    is TorrentIndexerReview -> torrentIndexer(item)
                    is TmdbReview -> true.also { settings.setTmdbKey(item.apiKey) }
                    is Removal, is Gone -> false
                }
                if (saved) wrote()
                done[item.key] = when {
                    !saved -> ItemStatus.Done(refusals[item.key] ?: if (item is Gone) RECORD_GONE else PHONE_SETUP_CHANGED_MEANWHILE, ok = false)
                    statuses[item.key] is ItemStatus.Anyway -> ItemStatus.Done(PHONE_SETUP_SAVED_ANYWAY, ok = false)
                    else -> ItemStatus.Done(PHONE_SETUP_SAVED, ok = true)
                }
            }
            if (review.mode == SetupMode.Wizard && settings.providers.first().isNotEmpty() && settings.indexers.first().isEmpty()) {
                settings.setIndexerSetupSkipped(true)
            }
            return review.items.associate { it.key to done.getValue(it.key) }
        }

        private fun wrote() {
            _savedSomething.value = true
        }

        /**
         * A provider: a change over its record read again, which keeps the record's id, place and on/off and, with no
         * password typed, its saved password, while the server is still its own; false when the record went or its
         * server changed meanwhile. Its test's result, from the phone or here, goes to the providers list.
         */
        private suspend fun provider(item: ProviderReview): Boolean {
            val form = if (item.action == ReviewAction.Change) {
                val now = settings.provider(item.key) ?: return false
                if (item.draft.password.isEmpty() && item.draft.username.isNotBlank() && refused(SavedSecretRule.password(now, item.draft))) return false
                providerForm(now).copy(
                    name = item.form.name, host = item.form.host, port = item.form.port, useTls = item.form.useTls, username = item.form.username,
                    password = item.form.password, connections = item.form.connections,
                )
            } else {
                item.form
            }
            settings.save(form)
            (tvResults[item.key] ?: item.login?.let { review.passes.provider(it, form.connectionsValue ?: 0) })?.let { deps.providerTests.record(form.id, it) }
            return true
        }

        /** An indexer, as [provider]: a change over its record read again, keeping its id, place, on/off and, with no key typed, its key. */
        private suspend fun indexer(item: IndexerReview): Boolean {
            val form = if (item.action == ReviewAction.Change) {
                val now = settings.indexer(item.key) ?: return false
                if (item.form.apiKey.isEmpty() && refused(SavedSecretRule.key(now, settings.indexerKeyStored(item.key), item.draft.url))) return false
                item.form.copy(url = SavedSecretRule.indexerAddress(now.record.baseUrl, item.draft.url), enabled = now.record.enabled)
            } else {
                item.form
            }
            settings.save(form)
            return true
        }

        /**
         * A torrent indexer, as [indexer]: a change over its record read again, keeping its id, place, on/off and, with no
         * key typed, its key; its "Use with your debrid service" as sent.
         */
        private suspend fun torrentIndexer(item: TorrentIndexerReview): Boolean {
            val form = if (item.action == ReviewAction.Change) {
                val now = settings.torrentIndexer(item.key) ?: return false
                if (item.form.fields.apiKey.isEmpty() && refused(SavedSecretRule.torrentIndexerKey(now, settings.torrentIndexerKeyStored(item.key), item.draft.url))) return false
                item.form.copy(fields = item.form.fields.copy(url = SavedSecretRule.indexerAddress(now.record.baseUrl, item.draft.url), enabled = now.record.enabled))
            } else {
                item.form
            }
            settings.save(form)
            return true
        }

        /**
         * A debrid account (L196): signed in to as its key form's Save does, keeping the key only if the service takes it,
         * as a new account or as the saved one's new key (its place and its switch kept). What the phone's test or Save's
         * own said of the key stands for that sign-in, so no service is asked again; false when the account was signed
         * out of on this TV meanwhile, or the service refused the key.
         */
        private suspend fun service(item: ServiceReview): Boolean {
            val signIn: suspend (String) -> ServiceSignIn = { key ->
                review.passes.service(item.service, key) ?: tvSignIns[item.key] ?: deps.trialService(item.service, key).test().signIn()
            }
            return try {
                if (item.action == ReviewAction.Change) {
                    if (settings.service(item.key) == null) return false
                    settings.replaceKey(item.key, item.apiKey, signIn)
                } else {
                    settings.saveService(item.service.name, item.apiKey, signIn)
                }
                true
            } catch (e: ServiceException) {
                refusals[item.key] = keyFailure(item.service, e.error)
                false
            }
        }

        /** A saved secret the rule won't let go with the card now: its server isn't the one it was saved for, or it can't be read. */
        private fun refused(use: SavedUse) = use is SavedUse.No && use.why != SavedRefusal.NoneSaved
    }

    /** A session ended by [cut]: its [number], the page it had ([mode]) and what passed on it ([passes]). */
    private class Ended(val number: Int, val mode: SetupMode, val passes: PassLog)

    companion object {
        /** How long the page waits for the system to tell of a network before it says there is none. */
        internal const val NO_NETWORK_GRACE_MS = 1_500L
    }
}

private val log = LoggerFactory.getLogger(PhoneSetupViewModel::class.java)
