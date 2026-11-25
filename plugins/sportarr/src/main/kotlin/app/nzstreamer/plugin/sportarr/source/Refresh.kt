package app.nzstreamer.plugin.sportarr.source

import app.nzstreamer.plugin.sportarr.model.Sports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.CoroutineContext

/**
 * When the list is read, and the state the tab draws (L641). A read starts:
 * - as the tab's page shows ([shown]) with no list, or one read more than 15 minutes ago;
 * - on the ⋮'s Refresh and on Try again ([refresh]);
 * - after a Save that changed the address or the key ([saved]).
 *
 * Never on a timer, never while the tab isn't shown, never in the background: the plugin has no job. One read runs at a
 * time, and an ask meanwhile joins it; only a changed Save lets the running read go and starts afresh, as that read asks
 * with what was saved before. A read that fails or is refused keeps the last list whole, with its age.
 *
 * It runs in [scope], the host's, on the main thread; the read ([read], a [ListReader]'s) decodes off it, [kept] reads
 * and writes list.json off it, and each list, read or kept, is built into the model ([build]) on [work], so only the state
 * it ends in is set here. [clock] says when a list is too old.
 */
class Refresh(
    private val scope: CoroutineScope,
    private val connection: Connection,
    private val kept: KeptList,
    private val clock: () -> Instant,
    private val work: CoroutineContext = Dispatchers.Default,
    private val build: (SportsList) -> Sports = Sports::of,
    private val read: suspend (Saved) -> ListRead,
) {
    private val current = MutableStateFlow<ListState>(ListState.Loading)

    /** What the tab draws: Loading until the kept list or a read says more. */
    val state: StateFlow<ListState> = current.asStateFlow()

    /** The list shown, as the model: the last read's, or list.json's until a read. */
    private var sports: Sports? = null
    private var loaded = false
    private var running: Job? = null

    /** As the tab's page shows: the kept list at once, and a read when there is none or it is over 15 minutes old. */
    fun shown(): Job = scope.launch {
        val sports = load()
        if (connection.saved() == null) {
            current.value = ListState.NotSetUp
            return@launch
        }
        if (sports != null && (current.value == ListState.Loading || current.value == ListState.NotSetUp)) current.value = settled(sports)
        if (sports == null || Duration.between(sports.readAt, clock()) > STALE) start(afresh = false).join()
    }

    /** The ⋮'s Refresh, and Try again: a read now, or the one running. */
    fun refresh(): Job = start(afresh = false)

    /** After a Save: a read at once when it [changed] the address or the key, else nothing. */
    fun saved(changed: Boolean): Job = if (changed) start(afresh = true) else Job().apply { complete() }

    /** The read running, or a new one; [afresh] lets the running one go first. */
    private fun start(afresh: Boolean): Job {
        val before = running?.takeIf { it.isActive }
        if (before != null && !afresh) return before
        val job = scope.launch(start = CoroutineStart.LAZY) {
            before?.cancelAndJoin()
            load()
            val saved = connection.saved()
            if (saved == null) {
                current.value = ListState.NotSetUp
                return@launch
            }
            if (sports == null) current.value = ListState.Loading
            current.value = when (val result = read(saved)) {
                is ListRead.Read -> {
                    val built = withContext(work) { build(result.list) }
                    kept.write(result.list)
                    sports = built
                    settled(built)
                }
                ListRead.Refused -> ListState.Refused(sports)
                is ListRead.Failed -> ListState.Unreachable(sports, result.why)
            }
        }
        running = job
        job.start()
        return job
    }

    /** The kept list, read from list.json once and built into the model. */
    private suspend fun load(): Sports? {
        if (!loaded) {
            val stored = kept.read()?.let { withContext(work) { build(it) } }
            // A read may have ended while the file was read: its list is newer.
            if (!loaded) {
                sports = stored
                loaded = true
            }
        }
        return sports
    }

    private fun settled(sports: Sports): ListState = if (sports.leagues.isEmpty()) ListState.NothingFollowed else ListState.Ready(sports)

    private companion object {
        val STALE: Duration = Duration.ofMinutes(15)
    }
}

/** The list's states (L641), each a frame of the tab's. */
sealed interface ListState {
    /** No address and key saved: the setup. */
    data object NotSetUp : ListState

    /** The first read runs, nothing kept yet. */
    data object Loading : ListState

    /** A list to show, as the model, read at [readAt], [cut] when the budget or the cap left events out. */
    data class Ready(val sports: Sports) : ListState {
        val readAt: Instant get() = sports.readAt
        val cut: Boolean get() = sports.cut
    }

    /** The last read failed ([why]); the list kept, if any, from [readAt]. */
    data class Unreachable(val sports: Sports?, val why: Failure) : ListState {
        val readAt: Instant? get() = sports?.readAt
    }

    /** Sportarr refused the key (changed there); the list kept, if any, from [readAt]. */
    data class Refused(val sports: Sports?) : ListState {
        val readAt: Instant? get() = sports?.readAt
    }

    /** A read found no league switched on in Sportarr. */
    data object NothingFollowed : ListState
}
