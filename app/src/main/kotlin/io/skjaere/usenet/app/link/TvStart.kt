package io.skjaere.usenet.app.link

import android.os.SystemClock
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.debrid.ServiceSource
import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.PipelineStep
import io.skjaere.usenet.core.StepStatus
import io.skjaere.usenet.core.UsenetSource
import io.skjaere.usenet.data.TvSession
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.LinkMessage.Status
import io.skjaere.usenet.link.Playback
import io.skjaere.usenet.link.Rejection
import io.skjaere.usenet.link.SheetKind
import io.skjaere.usenet.link.SheetStep
import io.skjaere.usenet.link.StepState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/** Where a play started on a TV is, as the phone's sheet shows it (L37, PtPhoneStarting, PtPhoneStartFail). */
sealed interface TvPhase {
    /** Play is on its way: worked out, sent, and not answered yet. */
    data object Asking : TvPhase

    /** The TV took it as [session]: its sheet is mirrored. */
    data class Starting(val session: String) : TvPhase

    /** The TV's sheet is done and its player is starting: the last row, Buffering, runs until its picture starts. */
    data class Buffering(val session: String) : TvPhase

    /** Cancel was pressed: the TV is asked to stop. */
    data class Cancelling(val session: String?) : TvPhase

    /** Cancel wasn't answered in time: it may still start there. */
    data object NoAnswer : TvPhase

    /** No word from the TV, its link down, for the sheet's silence: it may still be starting there. [heardAgoMs]: since its last word. */
    data class Lost(val session: String?, val heardAgoMs: Long) : TvPhase

    /** The TV refused it, for [reason]. */
    data class Rejected(val reason: Rejection) : TvPhase

    /** Nothing went: what to ask couldn't be worked out ([why], the app's words), or the TV's link wasn't up. */
    data class NotSent(val why: String?) : TvPhase
}

/**
 * A start on [tv] (named [tvName] as this phone keeps it) of [title] and [subtitle], at [phase], with the TV's play
 * sheet as it last said it ([sheet], its lines in the TV's words, TvWords), whether the sheet is open ([expanded]), and
 * the rows the TV's sheet lists until it names a step ([rows], [mirroredRows]): as its hello said it plays
 * ([rowsBefore]), then as its Searching sheet sent them, kept while a sheet sends none ([rowsAfter]).
 */
data class TvStartNow(
    val tv: KeyId,
    val tvName: String,
    val title: String,
    val subtitle: String?,
    val phase: TvPhase,
    val sheet: Status.Sheet? = null,
    val expanded: Boolean = true,
    val rows: List<SheetStep> = emptyList(),
) {
    /** A start that went nowhere, or the TV's sheet says why it didn't: the sheet offers Close alone (W13). */
    val over: Boolean
        get() = phase is TvPhase.NoAnswer || phase is TvPhase.Lost || phase is TvPhase.Rejected || phase is TvPhase.NotSent ||
            (phase is TvPhase.Starting && sheet?.kind.let { it == io.skjaere.usenet.link.SheetKind.NoRelease || it == io.skjaere.usenet.link.SheetKind.Failed || it == io.skjaere.usenet.link.SheetKind.NoProvider })

    override fun toString() = "TvStartNow(${tv.short}, $phase, ${sheet?.kind})"
}

/** What a start on a TV runs on: the app's ([of]), or fakes, so it runs in a JVM test. */
class TvStartDeps(
    /** Sends a command on the TV's link with a number of its own; null when the link isn't up. */
    val command: (KeyId, (Int) -> LinkMessage) -> Int?,
    /** What the TVs say on their links. */
    val heard: Flow<TvHeard>,
    /** Whether the TV's link is up. */
    val linked: (KeyId) -> Flow<Boolean>,
    /** Holds the TV's link while its start runs (L36). */
    val hold: (KeyId) -> AutoCloseable,
    /** The TV's name as this phone keeps it. */
    val name: (KeyId) -> String,
    /** Whether the TV's hello said it plays from its debrid service alone ([TvReach.fromService]). */
    val fromService: (KeyId) -> Boolean = { false },
    /** Keeps a session this phone started on a TV (L33). */
    val keep: suspend (KeyId, TvSession) -> Unit,
    /** Each play the coordinator starts here, as it starts. */
    val startingHere: Flow<Unit>,
    /** Puts away a failure the coordinator's sheet still shows. */
    val dismissHere: () -> Unit,
    /** Wall-clock milliseconds, for when a session started. */
    val now: () -> Long,
    /** Monotonic milliseconds, for when the TV was last heard. */
    val clock: () -> Long,
    val timings: () -> PlayOnTimings = ::playOnTimings,
    /** The TV took the start as the session named: the Playing on bar follows it from now (L38). */
    val took: (TvStartNow, String) -> Unit = { _, _ -> },
) {
    companion object {
        fun of(c: AppContainer) = TvStartDeps(
            command = c.tvLinks::command,
            heard = c.tvLinks.heard,
            linked = { key -> c.tvLinks.states.map { it[key]?.linked == true }.distinctUntilChanged() },
            hold = c.tvLinks::holdTv,
            // Play goes to the TV chosen: the name it is kept by there.
            // A TV's start names a TV: a Cast device is never started here (L77).
            name = { key -> (c.playOn.target.value as? PlayTarget.Tv)?.takeIf { it.key == key }?.name ?: "the TV" },
            fromService = { key -> c.tvLinks.states.value[key]?.fromService == true },
            keep = c.data.link::addSession,
            startingHere = c.playback.starting,
            dismissHere = c.playback::dismiss,
            now = System::currentTimeMillis,
            clock = SystemClock::elapsedRealtime,
            took = { start, session -> c.tvPlaying.follow(start.tv, start.tvName, session, start.title, start.subtitle) },
        )
    }
}

/**
 * A play started on a TV, as the phone's own play sheet shows it (L37, PtPhoneStarting, PtPhoneStartFail): the Play
 * goes with a number of its own while the TV's link is held (L36); the TV's Ack names its session, which this phone
 * keeps (L33); the TV's sheet is mirrored, its lines in the TV's words ([TvWords]), then Buffering until the TV's
 * picture starts, when the sheet closes. The TV's statuses for another session, or another TV's, are none of its
 * business. A start that didn't start offers Close alone (W13), and stays when the TV closes its own sheet. Cancel asks
 * the TV to stop the session (once the TV has named it), and the sheet closes on its answer, or says after
 * [PlayOnTimings.cancelAnswerMillis] that the TV didn't answer. [PlayOnTimings.silenceMillis] without word from the
 * TV, its link down, reads lost; heard again, it goes on. Unanswered or lost, the sheet closes once the TV says the start
 * is over there (stopped, or its hello naming another play or none). A play started on this phone meanwhile lets the
 * sheet go, the TV's play going on. Logged: what came of it, never a title or the TV's words (L13).
 */
class TvStart(private val scope: CoroutineScope, private val deps: TvStartDeps) : PlayOnTv {
    private val _now = MutableStateFlow<TvStartNow?>(null)

    /** The start the sheet shows; null for none. */
    val now: StateFlow<TvStartNow?> = _now.asStateFlow()

    /** The start running now, whose the sheet is while it is [run]: its job, and how a press reaches it. */
    private class Run(val presses: Channel<Unit>) {
        var job: Job? = null
    }

    @Volatile private var run: Run? = null

    init {
        // A play started on this phone takes the sheet; the TV's goes on there.
        scope.launch { deps.startingHere.collect { letGo() } }
    }

    override fun start(tv: KeyId, title: String, subtitle: String?, ask: suspend () -> TvAsk) {
        letGo()
        deps.dismissHere()
        val next = Run(Channel(Channel.CONFLATED))
        run = next
        _now.value = TvStartNow(tv, deps.name(tv), title, subtitle, TvPhase.Asking, rows = rowsBefore(deps.fromService(tv)))
        next.job = scope.launch { follow(next, tv, ask) }
    }

    /** Cancel: the TV is asked to stop (a start that went nowhere just closes). */
    fun cancel() {
        val now = _now.value ?: return
        if (now.over) return close()
        run?.presses?.trySend(Unit)
    }

    /** Close, on a start that went nowhere: the sheet goes. */
    fun close() = letGo()

    fun minimize() = _now.update { it?.copy(expanded = false) }

    fun expand() = _now.update { it?.copy(expanded = true) }

    private fun letGo() {
        run?.job?.cancel()
        run = null
        _now.value = null
    }

    /** [change] on the sheet while [mine] is the start it shows. */
    private fun show(mine: Run, change: (TvStartNow) -> TvStartNow) = _now.update { now -> if (run === mine && now != null) change(now) else now }

    /** The start of [mine] ended by itself (the picture started, the TV said it stopped): the sheet closes. */
    private fun done(mine: Run, why: String) {
        log.info("Play on TV: a start on a TV ended ({})", why)
        if (run === mine) {
            run = null
            _now.value = null
        }
    }

    /** What is heard, pressed or seen of the TV's link, one at a time. */
    private sealed interface Event {
        class Heard(val message: LinkMessage) : Event

        class Link(val up: Boolean) : Event

        data object Cancel : Event
    }

    private suspend fun follow(mine: Run, tv: KeyId, ask: suspend () -> TvAsk) {
        val held = deps.hold(tv)
        try {
            coroutineScope {
                val events = Channel<Event>(Channel.UNLIMITED)
                // Listening before the Play goes, so its answer is never missed.
                val listening = listOf(
                    launch(start = CoroutineStart.UNDISPATCHED) { deps.heard.collect { if (it.tv == tv) events.send(Event.Heard(it.message)) } },
                    launch(start = CoroutineStart.UNDISPATCHED) { deps.linked(tv).collect { events.send(Event.Link(it)) } },
                    launch(start = CoroutineStart.UNDISPATCHED) { for (press in mine.presses) events.send(Event.Cancel) },
                )
                try {
                    val play = try {
                        ask()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.info("Play on TV: couldn't work out what to ask a TV for ({})", e::class.simpleName)
                        show(mine) { it.copy(phase = TvPhase.NotSent((e as? TvAskFailed)?.words)) }
                        return@coroutineScope
                    }
                    val playSeq = deps.command(tv, play::message)
                    if (playSeq == null) {
                        log.info("Play on TV: a play wasn't sent, the TV's link isn't up")
                        show(mine) { it.copy(phase = TvPhase.NotSent(null)) }
                        return@coroutineScope
                    }
                    Following(mine, tv, play, playSeq, events).follow()
                } finally {
                    listening.forEach { it.cancel() }
                }
            }
        } finally {
            held.close()
        }
    }

    /** One start followed from its Play on: its session, its cancel, and when the TV was last heard. */
    private inner class Following(private val mine: Run, private val tv: KeyId, private val play: TvAsk, private val playSeq: Int, private val events: Channel<Event>) {
        private val timings = deps.timings()
        private var session: String? = null
        private var cancelSeq: Int? = null
        private var cancelAt: Long? = null
        private var cancelWanted = false
        private var lastHeard = deps.clock()
        private var up = true
        private var ended = false

        /** Statuses heard before the Ack named the session, the newest of each. */
        private val early = LinkedHashMap<String, Status>()

        private val phase: TvPhase? get() = _now.value?.takeIf { run === mine }?.phase

        suspend fun follow() {
            while (!ended) {
                val wait = deadline()?.let { (it - deps.clock()).coerceAtLeast(0) }
                val event = if (wait == null) events.receive() else events.receiveWithin(wait)
                when (event) {
                    null -> timeUp()
                    is Event.Heard -> heard(event.message)
                    is Event.Link -> {
                        up = event.up
                        if (!up) timeUp()
                    }
                    Event.Cancel -> cancelPressed()
                }
            }
        }

        /**
         * When something is due: Cancel's answer; the TV's answer to Play, which comes at once; or, once it took the
         * play, word from a TV whose link is down (one whose link is up answers pings, and is only busy).
         */
        private fun deadline(): Long? = when (phase) {
            is TvPhase.Cancelling -> (cancelAt ?: deps.clock()) + timings.cancelAnswerMillis
            TvPhase.Asking -> lastHeard + timings.silenceMillis
            is TvPhase.Starting, is TvPhase.Buffering -> if (!up && !showsFailure()) lastHeard + timings.silenceMillis else null
            else -> null
        }

        private fun showsFailure(): Boolean = _now.value?.over == true

        /** Cancel was pressed, answered or not. */
        private val TvPhase.stopping: Boolean get() = this is TvPhase.Cancelling || this == TvPhase.NoAnswer

        private fun timeUp() {
            val now = deps.clock()
            when (val p = phase) {
                // Still listening: the TV's word that its start is over, or a late answer, closes the sheet.
                is TvPhase.Cancelling -> if (now >= (cancelAt ?: now) + timings.cancelAnswerMillis) {
                    log.info("Play on TV: a TV didn't answer Cancel in time")
                    show(mine) { it.copy(phase = TvPhase.NoAnswer) }
                }
                TvPhase.Asking, is TvPhase.Starting, is TvPhase.Buffering -> if ((p == TvPhase.Asking || (!up && !showsFailure())) && now - lastHeard >= timings.silenceMillis) {
                    log.info("Play on TV: no word from a TV for {} s", (now - lastHeard) / 1_000)
                    val s = (p as? TvPhase.Starting)?.session ?: (p as? TvPhase.Buffering)?.session
                    show(mine) { it.copy(phase = TvPhase.Lost(s, now - lastHeard)) }
                }
                else -> Unit
            }
        }

        private fun heard(message: LinkMessage) {
            lastHeard = deps.clock()
            when (message) {
                is LinkMessage.Ack -> when (message.seq) {
                    playSeq -> message.session?.let { acked(it) }
                    cancelSeq -> done(mine, "cancelled").also { ended = true }
                }
                is LinkMessage.Rejected -> when (message.seq) {
                    playSeq -> {
                        log.info("Play on TV: a TV refused a play ({})", message.reason)
                        if (cancelWanted) done(mine, "refused, as it was cancelled") else show(mine) { it.copy(phase = TvPhase.Rejected(message.reason)) }
                        ended = true
                    }
                    // Nothing left there to stop.
                    cancelSeq -> done(mine, "cancelled, nothing left to stop").also { ended = true }
                }
                is Status -> {
                    val ours = session
                    if (ours == null) {
                        early.remove(message.session)
                        early[message.session] = message
                        while (early.size > EARLY) early.remove(early.keys.first())
                    } else if (message.session == ours) {
                        status(message)
                    }
                }
                // The TV names its play now as its link comes up: once it took this one, another or none means it is over there.
                is LinkMessage.Hello -> session?.let { ours -> if (message.session?.id != ours) over("the TV plays something else now") }
                else -> Unit
            }
        }

        /**
         * The TV says the start is over there ([why]): a failure its sheet showed stays for this phone to close, with
         * nothing more to hear of it; anything else closes, a sheet that read lost or unanswered too.
         */
        private fun over(why: String) {
            val failed = _now.value?.takeIf { run === mine }?.let { it.phase is TvPhase.Starting && it.over } == true
            if (!failed) done(mine, why)
            ended = true
        }

        private fun acked(id: String) {
            // One Ack a Play: a second one names nothing new.
            if (session != null) return
            session = id
            log.info("Play on TV: a TV took a play")
            // The bar follows it from here, past this sheet, which closes as its player has it.
            _now.value?.takeIf { run === mine }?.let { deps.took(it, id) }
            scope.launch {
                try {
                    deps.keep(tv, TvSession(id, deps.now(), showTmdbId = play.episode?.showTmdbId, movieTmdbId = play.movie?.tmdbId))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Play on TV: couldn't keep a session started on a TV", e)
                }
            }
            if (cancelWanted) {
                sendCancel(id)
            } else {
                show(mine) { it.copy(phase = TvPhase.Starting(id)) }
            }
            early.remove(id)?.let { status(it) }
            early.clear()
        }

        /** The TV's word on the session's play. */
        private fun status(status: Status) {
            when (status.state) {
                // Asked to stop, it reads so until the TV answers or says more than that it still starts.
                Playback.Starting -> show(mine) { now ->
                    val sheet = status.sheet?.takeIf { it.kind != SheetKind.Unknown }?.let(TvWords::sheet) ?: now.sheet
                    now.copy(sheet = sheet, rows = rowsAfter(now.rows, sheet), phase = if (now.phase.stopping) now.phase else TvPhase.Starting(status.session))
                }
                Playback.Buffering -> show(mine) { now -> if (now.phase.stopping) now else now.copy(phase = TvPhase.Buffering(status.session)) }
                // The picture started, or the player stopped as it did (its bar says so): the sheet's work is done.
                Playback.Playing, Playback.Paused, Playback.Failed -> done(mine, "the TV's player has it").also { ended = true }
                // The TV closed its sheet: a failure stays for this phone to close, with nothing more to hear of it;
                // anything else is over there.
                Playback.Stopped -> over("stopped on the TV")
                Playback.Replaced -> done(mine, "another play took its place").also { ended = true }
                Playback.Unknown -> Unit
            }
        }

        private fun cancelPressed() {
            if (phase is TvPhase.Cancelling) return
            cancelAt = deps.clock()
            val id = session
            if (id == null) {
                // The TV hasn't named the session yet: Cancel goes once it has.
                cancelWanted = true
                show(mine) { it.copy(phase = TvPhase.Cancelling(null)) }
            } else {
                sendCancel(id)
            }
        }

        private fun sendCancel(id: String) {
            cancelWanted = false
            if (cancelAt == null) cancelAt = deps.clock()
            cancelSeq = deps.command(tv) { seq -> LinkMessage.Cancel(seq, id) }
            show(mine) { it.copy(phase = TvPhase.Cancelling(id)) }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(TvStart::class.java)

        /** Sessions whose statuses are kept until the Ack names one: a TV says one play at a time. */
        const val EARLY = 4
    }
}

/** Under the title: which TV the sheet speaks for (PtPhoneStarting). */
fun startingOn(name: String) = "Starting on $name"

/** The first row, until the TV says what it does. */
fun waitingFor(name: String) = "Waiting for $name…"

/** Above the release: the TV picked it with its own indexers (W4). */
fun tvChose(name: String, attempt: Int, of: Int) = if (of > 1) "$name chose · release $attempt of $of" else "$name chose"

/** The chip for the TV's own check (L10). */
fun playsOn(name: String) = "✓ Plays on $name"

/** The last row, until the TV's picture starts. */
const val BUFFERING = "Buffering"

const val CHOOSE_ON_TV = "Choose the file on the TV"

/** A season pack's question, answered with the TV's remote (L32). */
fun choosingOn(name: String) = "$name shows its files: choose the one it is with the TV's remote."
const val WAITING_FOR_THE_TV = "Waiting for the TV… The play goes on once a file is chosen there."

fun stoppingOn(name: String) = "Stopping on $name…"

fun didntAnswer(name: String) = "$name didn't answer. It may still start there."

fun lostConnection(name: String) = "Lost the connection to $name"
const val LOST_TEXT = "It may still start there: look at the TV. If it does, this phone shows it again as soon as it reaches the TV."

/** Where the steps stopped: the step that ran when the TV was last heard. */
const val NO_ANSWER = "no answer"

fun lastHeard(agoMs: Long) = "Last heard from the TV ${agoMs / 1_000} s ago."

fun noReleaseOn(name: String) = "No playable release found on $name"

fun couldntStartOn(name: String) = "Couldn't start on $name."

/** The failure sheet's line above the releases, for [failures] tried. */
fun eachFailedOn(name: String, failures: Int) = "Couldn't start on $name. " + if (failures == 1) "The release failed:" else "Each release tried failed:"

/** The words for a refusal (L41, PtPhoneStartFail): what to do next, if anything. */
fun rejectedWords(reason: Rejection, name: String): Pair<String, String?> = when (reason) {
    Rejection.BusySetup -> "$name is busy with setup. Try again when it's done." to null
    Rejection.TooFast -> "$name is busy. Try again in a moment." to null
    Rejection.NotReadyIndexer -> "Couldn't start on $name. It has no indexer set up yet." to null
    Rejection.NotReadyProvider -> "Couldn't start on $name. It has no Usenet provider set up yet." to "Set up $name first: Settings › Usenet providers on the TV."
    // D2PhoneTvStartFail: what the TV's debrid way lacks, naming no service (L223); "off" for a refused key too.
    Rejection.NotReadyService -> "Couldn't start on $name. It has no debrid service set up yet." to "Set up $name first: Settings › Debrid services on the TV."
    Rejection.NotReadyServiceOff -> "Couldn't start on $name. Its debrid service is off." to "Switch it on in Settings › Debrid services on the TV."
    Rejection.NotReadyTorrentIndexer -> "Couldn't start on $name. It has no torrent indexer set up yet." to null
    Rejection.NotCurrent, Rejection.Unsupported, Rejection.Unknown -> couldntStartOn(name) to null
}

/** Nothing went: the TV's link wasn't up, or what to ask couldn't be worked out ([why]). */
fun notSentWords(name: String, why: String?) = couldntStartOn(name) to (why ?: "${openNzStreamerOn(name)}, then try again.")

/** The mini bar's second line while a start on a TV runs (PtPhoneStarting 5): "On Living room TV · Checking availability…". */
fun onTvStep(name: String, step: String) = "On $name · $step"

/** The mini bar's line for a start on a TV that didn't start (PtPhoneStartFail). */
fun couldntPlayOn(title: String, name: String) = "Couldn't play $title on $name"

/**
 * The rows a TV's start lists, as its own sheet does (L203, L206), by the steps it named ([mirroredSteps]): a debrid
 * service's three once it names Preparing, else Usenet's four. Before it names a step, the [rows] the start keeps
 * ([TvStartNow.rows]), the ones its own sheet lists meanwhile (D2PhoneTvStarting, L223): as its hello said it plays, then
 * as its Searching sheet sent them, a TV that plays from its debrid service alone its service's. A TV before D2 sends
 * none and says nothing of a service: then Usenet's, as such a TV takes Play on TV only with a provider.
 */
fun mirroredRows(steps: Map<PipelineStep, StepStatus>, rows: List<SheetStep> = emptyList()): List<PipelineStep> = when {
    PipelineStep.Prepare in steps -> ServiceSource.STEPS
    steps.isNotEmpty() -> UsenetSource.STEPS
    else -> rows.mapNotNull(::pipelineStep).takeIf { it.isNotEmpty() } ?: UsenetSource.STEPS
}

/**
 * The rows a TV's sheet lists before it says them (L223): a debrid service's for a TV that plays [fromService] alone, as
 * its sheet does while it searches; else Usenet's, as every TV before D2.
 */
fun rowsBefore(fromService: Boolean): List<SheetStep> = (if (fromService) ServiceSource.STEPS else UsenetSource.STEPS).map(SheetMirror::wire)

/**
 * The rows a start keeps once [sheet] is heard (L223): those it sends, where it sends any (a Searching sheet's), else
 * those [kept] before. A Running sheet that names no step yet, as each release starts, then lists the rows the TV's own
 * sheet does rather than Usenet's (CWR-1).
 */
fun rowsAfter(kept: List<SheetStep>, sheet: Status.Sheet?): List<SheetStep> = sheet?.rows?.takeIf { it.isNotEmpty() } ?: kept

/** The sheet's step [step] names; null for one this version doesn't know. */
private fun pipelineStep(step: SheetStep): PipelineStep? = when (step) {
    SheetStep.Prepare -> PipelineStep.Prepare
    SheetStep.FetchNzb -> PipelineStep.FetchNzb
    SheetStep.CheckAvailability -> PipelineStep.CheckAvailability
    SheetStep.Open -> PipelineStep.Open
    SheetStep.ProbeTracks -> PipelineStep.ProbeTracks
    SheetStep.Unknown -> null
}

/**
 * The TV's steps as the phone's sheet rows show them (L37): how far each is, how long it took, and a failed one's words
 * as [AttemptFailure.Elsewhere], the TV's own. A step this version doesn't know is left out.
 */
fun mirroredSteps(sheet: Status.Sheet): Map<PipelineStep, StepStatus> = sheet.steps.mapNotNull { step ->
    val name = pipelineStep(step.step) ?: return@mapNotNull null
    val status = when (step.state) {
        StepState.Running -> StepStatus.Running(step.done, step.total)
        StepState.Done -> StepStatus.Done(step.millis ?: 0, step.detail)
        StepState.Failed -> StepStatus.Failed(step.millis ?: 0, AttemptFailure.Elsewhere(step.failure ?: SheetMirror.COULDNT))
        StepState.Unknown -> return@mapNotNull null
    }
    name to status
}.toMap()
