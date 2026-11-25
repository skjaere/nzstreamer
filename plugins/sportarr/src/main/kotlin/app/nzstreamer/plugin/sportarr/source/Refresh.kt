package app.nzstreamer.plugin.sportarr.source

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

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
 * It runs in [scope], the host's, on the main thread; the read ([read], a [ListReader]'s) decodes off it, and [kept]
 * reads and writes list.json off it, so only the state it ends in is set here. [clock] says when a list is too old.
 */
class Refresh(
    private val scope: CoroutineScope,
    private val connection: Connection,
    private val kept: KeptList,
    private val clock: () -> Instant,
    private val read: suspend (Saved) -> ListRead,
) {
    private val current = MutableStateFlow<ListState>(ListState.Loading)

    /** What the tab draws: Loading until the kept list or a read says more. */
    val state: StateFlow<ListState> = current.asStateFlow()

    /** The list shown: the last read's, or list.json's until a read. */
    private var list: SportsList? = null
    private var loaded = false
    private var running: Job? = null

    /** As the tab's page shows: the kept list at once, and a read when there is none or it is over 15 minutes old. */
    fun shown(): Job = scope.launch {
        val list = load()
        if (connection.saved() == null) {
            current.value = ListState.NotSetUp
            return@launch
        }
        if (list != null && (current.value == ListState.Loading || current.value == ListState.NotSetUp)) current.value = settled(list)
        if (list == null || Duration.between(list.readAt, clock()) > STALE) start(afresh = false).join()
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
            if (list == null) current.value = ListState.Loading
            current.value = when (val result = read(saved)) {
                is ListRead.Read -> {
                    kept.write(result.list)
                    list = result.list
                    settled(result.list)
                }
                ListRead.Refused -> ListState.Refused(list)
                is ListRead.Failed -> ListState.Unreachable(list, result.why)
            }
        }
        running = job
        job.start()
        return job
    }

    /** The kept list, read from list.json once. */
    private suspend fun load(): SportsList? {
        if (!loaded) {
            val stored = kept.read()
            // A read may have ended while the file was read: its list is newer.
            if (!loaded) {
                list = stored
                loaded = true
            }
        }
        return list
    }

    private fun settled(list: SportsList): ListState = if (list.leagues.isEmpty()) ListState.NothingFollowed else ListState.Ready(list)

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

    /** A list to show, read at [readAt], [cut] when the budget or the cap left events out. */
    data class Ready(val list: SportsList) : ListState {
        val readAt: Instant get() = list.readAt
        val cut: Boolean get() = list.cut
    }

    /** The last read failed ([why]); the list kept, if any, from [readAt]. */
    data class Unreachable(val list: SportsList?, val why: Failure) : ListState {
        val readAt: Instant? get() = list?.readAt
    }

    /** Sportarr refused the key (changed there); the list kept, if any, from [readAt]. */
    data class Refused(val list: SportsList?) : ListState {
        val readAt: Instant? get() = list?.readAt
    }

    /** A read found no league switched on in Sportarr. */
    data object NothingFollowed : ListState
}
