package app.nzstreamer.plugin.sportarr.bouts

import app.nzstreamer.plugin.sportarr.model.SportsEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/** What a UFC card's page shows of its fight card (L-B7); none (null) where the page reads none, or before it knows. */
internal sealed interface CardState {
    /** The switch is off, or options.json can't be read. */
    data object Off : CardState

    /** Nothing kept, and a read runs or waits for the one before it. */
    data object Reading : CardState

    /** A card kept or just read: each part's bouts. */
    data class Bouts(val card: BoutCard) : CardState

    /** No card out yet: none split into parts, or no page or card before the event's start. */
    data object NotYet : CardState

    /** No page or card found after the event's start. */
    data object NoCard : CardState

    /** The last read failed, with nothing kept: Try again. */
    data object CantReach : CardState
}

/**
 * UFC events' fight cards (L-B5, L-B6, L-B8), one for each session, in the host's [scope]: read through [reads] only as an
 * event's page opens ([opened]) or on Try again ([tryAgain]), never from the list, a refresh, a timer or the background
 * (W1); kept in [kept], whose entry a page shows at once; read again only when it may have changed, by [clock]:
 * - a card found before the event's start, when over an hour old, as cards change up to the night;
 * - a card found after the start, when it was read before the start or is over three days old;
 * - not out yet or no card, when over an hour old.
 * A read in progress leaves the kept entry on screen; a failed one leaves it as it was, or says Can't reach.
 *
 * The switch ([option], on by default) is read before any read starts; a page opened meanwhile shows none. Switched off
 * ([setOn]), the read running or waiting is cancelled and joined, then, holding the reads' lock, the kept cards and the
 * index's copy are deleted from disk and memory; the holds stay. Every UFC card page then says Off, and nothing is read
 * until the switch is on again and a page opens.
 *
 * It runs on the scope's thread, the main one: [opened], [tryAgain] and [setOn] are called there, and the files are read
 * and written off it. Nothing it does throws into the scope: a failure is logged by its class and the read is failed.
 */
internal class Cards(
    private val scope: CoroutineScope,
    private val reads: WikipediaReads,
    private val kept: KeptCards,
    private val option: CardsOption,
    private val clock: () -> Instant,
) {
    private val on = MutableStateFlow<Boolean?>(null)

    /** The line of each event whose page opened since the session started, by event key. */
    private val shown = MutableStateFlow<Map<String, CardState>>(emptyMap())

    /** The kept cards once read from disk, by event key; null until then. */
    private var cards: MutableMap<String, KeptCard>? = null

    /** Each event's read, running or waiting for the lock, by event key. */
    private val running = HashMap<String, Job>()

    /** The last change of the switch, which the next one waits for, so options.json ends as the last one asked. */
    private var switching: Job? = null

    /** Whether fight cards are read: null until options.json has been read ([readSwitch], or a page opening). */
    val switch: StateFlow<Boolean?> = on.asStateFlow()

    /** What [event]'s page shows: null for a page that reads no card, and while the switch or the kept cards load. */
    fun state(event: SportsEvent): Flow<CardState?> {
        if (!PageFinder.reads(event)) return flowOf(null)
        return combine(on, shown) { on, shown ->
            when (on) {
                null -> null
                false -> CardState.Off
                true -> shown[event.key]
            }
        }
    }

    /** Reads options.json, where it hasn't been, for the settings page's switch. */
    fun readSwitch(): Job = scope.launch { guarded { switchedOn() } }

    /** As [event]'s page shows: its kept card at once, and a read where none is kept or it may have changed. */
    fun opened(event: SportsEvent): Job {
        if (!PageFinder.reads(event)) return Job().apply { complete() }
        running[event.key]?.takeIf { it.isActive }?.let { return it }
        val job = scope.launch {
            guarded(onFailure = { failed(event) }) {
                if (!switchedOn()) return@guarded
                val entry = keptCards()[event.key]
                show(event, entry)
                if (entry != null && fresh(entry, event)) return@guarded
                if (entry == null) shown.value += event.key to CardState.Reading
                read(event)
            }
        }
        running.values.removeAll { !it.isActive }
        running[event.key] = job
        return job
    }

    /**
     * Try again, under Can't reach: one new read, holds allowing. Can't reach shows only where nothing is kept, so it reads
     * as a page's opening would.
     */
    fun tryAgain(event: SportsEvent): Job = opened(event)

    /** Turns the switch [value] on or off, and keeps it. */
    fun setOn(value: Boolean): Job {
        on.value = value
        val stopping = if (value) emptyList() else running.values.toList().onEach { it.cancel() }
        if (!value) running.clear()
        val before = switching
        return scope.launch {
            before?.join()
            guarded {
                if (!value) {
                    stopping.forEach { it.join() }
                    reads.lock.withLock {
                        kept.delete()
                        reads.forget()
                        cards = HashMap()
                        shown.value = emptyMap()
                    }
                }
                option.write(value)
            }
        }.also { switching = it }
    }

    /** The read, holding the lock, unless the switch went off or a read meanwhile kept a card that is fresh. */
    private suspend fun read(event: SportsEvent): Unit = reads.lock.withLock {
        if (on.value != true) return@withLock
        val entry = keptCards()[event.key]
        if (entry != null && fresh(entry, event)) {
            show(event, entry)
            return@withLock
        }
        val read = reads.readHeld(event)
        if (read is CardRead.Failed) {
            shown.value += event.key to (entry?.let(::stateOf) ?: CardState.CantReach)
            return@withLock
        }
        val card = KeptCard(clock(), read)
        val all = keptCards()
        all[event.key] = card
        // The file keeps the newest reads alone, and so does memory.
        all.keys.retainAll(KeptCards.capped(all).map { it.first }.toSet())
        kept.write(all)
        show(event, card)
    }

    /** Whether the switch is on, read from options.json the first time. */
    private suspend fun switchedOn(): Boolean {
        if (on.value == null) {
            val read = option.read()
            // A change of the switch meanwhile wins over the file.
            if (on.value == null) on.value = read
        }
        return on.value == true
    }

    /** The kept cards, read from cards.json the first time. */
    private suspend fun keptCards(): MutableMap<String, KeptCard> {
        cards?.let { return it }
        val read = kept.read()
        // The switch turned off meanwhile left none.
        return cards ?: HashMap(read).also { cards = it }
    }

    private fun show(event: SportsEvent, card: KeptCard?) {
        shown.value = if (card == null) shown.value - event.key else shown.value + (event.key to stateOf(card))
    }

    private fun stateOf(card: KeptCard): CardState = when (val read = card.read) {
        is CardRead.Found -> CardState.Bouts(read.card)
        CardRead.NotYet -> CardState.NotYet
        CardRead.NoCard -> CardState.NoCard
        is CardRead.Failed -> CardState.CantReach
    }

    /** A read failed outside the reads' own catch (the files): its kept entry as it was, or Can't reach. */
    private fun failed(event: SportsEvent) {
        val entry = cards?.get(event.key)
        shown.value += event.key to (entry?.let(::stateOf) ?: CardState.CantReach)
    }

    /** Whether [card], kept for [event], is fresh now (L-B5); one read in the future, by a clock put back since, isn't. */
    private fun fresh(card: KeptCard, event: SportsEvent): Boolean {
        val now = clock()
        if (card.readAt.isAfter(now)) return false
        val age = Duration.between(card.readAt, now)
        return when (card.read) {
            is CardRead.Found -> if (now.isBefore(event.start)) age <= HOUR else !card.readAt.isBefore(event.start) && age <= THREE_DAYS
            else -> age <= HOUR
        }
    }

    /** Runs [block], logging anything but a cancellation it throws by its class alone, then [onFailure]. */
    private suspend fun guarded(onFailure: () -> Unit = {}, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.warn("Fight cards failed: {}", e.javaClass.simpleName)
            onFailure()
        }
    }

    private companion object {
        val HOUR: Duration = Duration.ofHours(1)
        val THREE_DAYS: Duration = Duration.ofDays(3)
        val log = LoggerFactory.getLogger(Cards::class.java)
    }
}
