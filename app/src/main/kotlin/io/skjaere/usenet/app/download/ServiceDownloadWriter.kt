package io.skjaere.usenet.app.download

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.app.debrid.DebridListingStore
import io.skjaere.usenet.app.debrid.ServiceEndpoints
import io.skjaere.usenet.app.debrid.ServiceLinks
import io.skjaere.usenet.core.BackoffWakes
import io.skjaere.usenet.core.DownloadWriter
import io.skjaere.usenet.core.RecordedFile
import io.skjaere.usenet.core.TransferRate
import io.skjaere.usenet.data.Clock
import io.skjaere.usenet.debrid.DebridService
import io.skjaere.usenet.debrid.ServiceException
import io.skjaere.usenet.debrid.ServiceFile
import io.skjaere.usenet.player.HttpLink
import io.skjaere.usenet.player.LinkFailure
import io.skjaere.usenet.player.LinkResolver
import io.skjaere.usenet.player.LinkUnavailable
import io.skjaere.usenet.player.PlayUris
import io.skjaere.usenet.player.ServiceDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Writes a debrid download's file (L419, L424, L471): the file the row's own copy of its listing names (its
 * [DebridListingStore.Entry], L420), from the safe offset to its end, into the download's own file ([RecordedFile]), over
 * one connection. One read is open at a time, the next made only once the last is closed; each is a ranged GET from the
 * byte reached with a fresh link from the row's own account ([accounts], L422's gated lookup), through a read of its own
 * ([reads]: the app's makes a new ServiceDataSource.Factory each, metered nowhere, so no link a factory held is read
 * again). A link that isn't HTTPS is never read, but where the endpoints point at the fakes ([httpsOnly]). One write at a
 * time; it blocks on its reads and its file on [dispatcher].
 *
 * **An answer** must be the rest of the file: its length the size less the byte asked from, and the total its
 * `Content-Range` names, where it names one, the size. Any other is no working link. One that says neither is read to the
 * size, and a byte past it is no working link too, its bytes written again by the next. A cached file is whole: the
 * record keeps no gaps.
 *
 * **Its sort.** Each failure records what was written first, so a reopen starts at the safe offset:
 * - a body cut (an IOException mid-body, or its end before the size) opens again at the byte reached, after a wait from
 *   2 s, doubling to 5 min, which progress, [retryNow] and the network's return start again from 2 s;
 * - an open that reaches nothing (a connection that fails, the service's link request unanswered: [LinkFailure.Down]) on
 *   a validated [network] says so in its live and counts toward an hour, after which it waits ([Wait.Unreachable]); on
 *   one that isn't validated it counts toward nothing;
 * - no working link from a fresh one is a counted reopen, and six in a row with no progress fail it
 *   ([DownloadHostRules.SERVICE_KEPT_FAILING]);
 * - a server that won't resume ([LinkFailure.NoRanges]) with bytes written: the restart mark written ([Folder]), the
 *   record back to 0, and the file opened again from 0, where a whole answer is taken; a second time, or with the mark
 *   there from an earlier process, it fails ([DownloadHostRules.CANT_RESUME]). With nothing written, nothing can start
 *   again: it is no working link;
 * - a limit of the account ends the write with the account and when its gate opens ([Wait.Limit]); its key refused waits
 *   for a new one ([Wait.KeyRefused]), at the refusal and at each open after it while no new key is saved ([keyRefused]);
 * - the torrent lost on its account (not cached any more, taken down, refused there, or the account switched off or
 *   signed out of) goes on from the safe offset on the next account on that lists its file at the same path and size,
 *   prepared there, the listing kept as the row's (L424); with none, the release is given up ([Outcome.NextRelease]);
 * - storage with no room ([RecordedFile.StorageFailure]) fails it, its file and record kept;
 * - debrid hidden ([shown] false) at any open fails it ([DownloadHostRules.NOT_IN_THIS_VERSION]) with no call.
 *
 * A torrent the account added again for a link (TorBox's re-add) is kept in the row's copy and the shared [listings] under
 * the same listing id, as [ServiceLinks] keeps a play's, so the next link asks by its new handle.
 *
 * **While something streams** (L471, W123): [streaming] true (a play's or a cast's meter open) stops the reads within
 * one read: the read under way is given a moment to come back ([LET_GO]; one from a body still flowing does within
 * milliseconds), else it is aborted from another thread, and whatever it comes back with is left, to be read again; the
 * body is then let go at once, what was written forced and recorded, and its live says so
 * ([DownloadWriter.Live.streamWait]). Once [streaming] has read false for [QUIET] together, it goes on at the safe
 * offset with a fresh link, at once: one link per stream start, and a wait counts as no failure. Nothing opens while
 * something streams.
 *
 * A cancel (a pause, a cancel, the host ending) aborts the read under way from another thread and records what was
 * forced. Its log lines name the download by its id, with offsets, sizes and a failure's kind: never a link, a key, a
 * hash, a host or a file's name.
 */
class ServiceDownloadWriter(
    /** Each open's read, over the link resolver given (the app's: Media3's HTTP source, a factory of its own each). */
    private val reads: Reads = ServiceFileReads,
    /** Account [accountId]'s service while it is on and debrid shows (L422's gated lookup); null otherwise. */
    private val accounts: suspend (accountId: String) -> DebridService?,
    /**
     * Whether account [accountId] is saved and switched on with its key refused since it was saved: the registry marks a
     * key at the first refusal any call meets, after which [accounts] gives none for it.
     */
    private val keyRefused: suspend (accountId: String) -> Boolean,
    /** The listings plays keep: a torrent added again, or taken from the next account, is kept there too. */
    private val listings: DebridListingStore,
    /** The accounts on, by id, in the user's order: where a torrent lost on its account is looked for next (L424). */
    private val accountsOn: suspend () -> List<String>,
    /** Whether something streams here (L471): [io.skjaere.usenet.core.StreamTraffic.streaming]. */
    private val streaming: StateFlow<Boolean>,
    /** The row's own files beside its download: its listing's copy and its restart mark (L420). */
    private val folder: Folder,
    /** Whether Android calls the device's network validated: read at each failure, and its return ends a wait. */
    private val network: StateFlow<Boolean> = MutableStateFlow(true),
    /** The wall clock, for when a limit ends. */
    private val clock: Clock = Clock.System,
    /** Where the reads block. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** At most how many bytes a second a body is taken at, asked as each opens (the debug build's throttle); null: as they come. */
    private val throttle: () -> Long? = { null },
    /** Whether this build shows debrid (L192). */
    private val shown: () -> Boolean = { DebridGate.shown },
    /** Whether only an HTTPS link is read: but while the endpoints point at the fakes. */
    private val httpsOnly: () -> Boolean = { ServiceEndpoints.override == null },
    /** The record's, the rate's, the hour's and the throttle's clock; the waits themselves are the coroutine's own. */
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /** The first wait before a reopen, doubling to [MAX_BACKOFF]: a device test's given short. */
    private val firstBackoff: Duration = FIRST_BACKOFF,
    /** How long a stream's stop gives a call on the read under way to come back before it aborts it ([LET_GO]). */
    private val letGo: Duration = LET_GO,
) {
    /** How a write ended. */
    sealed interface Outcome {
        /** The whole file is written and recorded. */
        data object Done : Outcome

        /** The release can't give the file on its account or any other on ([why]): the next is tried, from 0. */
        data class NextRelease(val why: Lost) : Outcome

        /** The download can't go on until [wait] is over; what was written is recorded. */
        data class Waiting(val wait: Wait) : Outcome

        /** Ended, for [reason] in the download's words; what was written is recorded. */
        data class Failed(val reason: String) : Outcome
    }

    /** Why a torrent was lost on its account (L424). */
    enum class Lost {
        /** The service no longer has it cached. */
        NotCached,

        /** The service took it down. */
        Removed,

        /** The service won't give it to this account. */
        Refused,

        /** Its account was switched off or signed out of. */
        AccountGone,
    }

    /** What a waiting download waits for, and its row's words. */
    sealed interface Wait {
        val reason: String

        /** The service out of reach for an hour on a validated network. */
        data object Unreachable : Wait {
            override val reason get() = DownloadHostRules.CANT_REACH_SERVICE
        }

        /** The service refused the account's key: a new one saved. */
        data object KeyRefused : Wait {
            override val reason get() = DownloadHostRules.CHECK_SERVICE
        }

        /** [account]'s limit, its gate closed until [until] (the wall clock's milliseconds, L461). */
        data class Limit(val account: String, val until: Long) : Wait {
            override val reason get() = DownloadHostRules.SERVICE_LIMIT

            // No account in a log line.
            override fun toString() = "Limit(until=$until)"
        }
    }

    /** Makes each open's [Read] over [resolver]'s links to the file at [uri], a `debrid://` URI naming the listing and the file. */
    fun interface Reads {
        fun make(resolver: LinkResolver, uri: String): Read
    }

    /**
     * One GET of the file, opened and read on one thread at a time; [abort] from any thread. As the cast relay's read (its
     * ServiceRead), with the answer's `Content-Range` total beside its length.
     */
    interface Read {
        /** Opens the file from [position] to its end: the answer's length and total. Throws as the open fails. */
        fun open(position: Long): Answer

        /** Up to [length] bytes into [buffer] at [offset]: how many, or -1 at the end. Blocks. */
        fun read(buffer: ByteArray, offset: Int, length: Int): Int

        /** Lets the connection go from another thread: a read waiting on it fails at once, an open under way or to come too. */
        fun abort()

        /** Lets it go, on the thread that reads it. */
        fun close()
    }

    /** An open's answer: its [length] and the [total] its `Content-Range` names, each null where it said none. */
    data class Answer(val length: Long?, val total: Long?)

    /** The row's own files beside its download (L420): the downloads' storage. */
    interface Folder {
        /** Keeps [entry] as download [id]'s copy of its listing, written whole. */
        suspend fun keepListing(id: String, entry: DebridListingStore.Entry)

        /** Marks [id]'s file as started again from 0. */
        suspend fun markRestarted(id: String)

        /** Whether [id]'s file was started again from 0, in this process or an earlier one. */
        suspend fun restarted(id: String): Boolean
    }

    private val writing = AtomicBoolean()
    private val _live = MutableStateFlow<DownloadWriter.Live?>(null)

    /** The write under way, as the Usenet writer's says it (L475); null between writes. */
    val live: StateFlow<DownloadWriter.Live?> = _live.asStateFlow()

    @Volatile
    private var rate: TransferRate? = null

    /** What ends a wait before a reopen sooner: [retryNow], and the network's return. */
    private val wakes = BackoffWakes(network)

    /** The write's bytes a second over the last 5 s; 0 between writes. */
    fun bytesPerSecond(): Long = rate?.bytesPerSecond() ?: 0

    /** Ends a wait before a reopen, its backoff starting again from 2 s: Resume, the services saved. */
    fun retryNow() = wakes.retryNow()

    /**
     * Writes download [id]'s [file] of the listing [entry] (its copy) to [out] (allocated whole) from [from], every byte
     * before which is written, as the class says. [onOpen] hears each open's offset as it is asked for; [record] keeps the
     * safe offset, after the file is forced. A [record] that fails ends the write with its failure.
     */
    suspend fun write(
        id: String,
        entry: DebridListingStore.Entry,
        file: ServiceFile,
        out: FileChannel,
        from: Long,
        onOpen: (offset: Long) -> Unit = {},
        record: suspend (safeOffset: Long) -> Unit,
    ): Outcome {
        require(from in 0..file.size) { "A first byte of $from in a file of ${file.size} bytes" }
        check(writing.compareAndSet(false, true)) { "One write at a time" }
        val recorded = RecordedFile(
            id, out, file.size, from, live = { _live.value = it }, record = { offset, _ -> record(offset) }, timeSource = timeSource,
        )
        val run = Run(id, entry, file, recorded)
        try {
            rate = recorded.rate
            _live.value = DownloadWriter.Live(id, from, unreachable = false)
            log.info("download {}: writing from byte {} of {} from its debrid service", id, from, file.size)
            return try {
                coroutineScope {
                    // A stream that starts stops the read under way at once, wherever it waits.
                    val watch = launch(start = CoroutineStart.UNDISPATCHED) { streaming.collect { if (it) run.stopForStream(letGo) } }
                    try {
                        wakes.watching { transfer(run, onOpen) }
                    } finally {
                        watch.cancel()
                    }
                }
            } catch (e: RecordedFile.StorageFailure) {
                // What is written in front of it stays, as far as storage keeps it: Try again goes on from there.
                log.warn("download {}: writing at {} of {} bytes failed: {}", id, run.written, run.size, if (e.full) "storage full" else e.cause::class.simpleName)
                recorded.saveQuietly()
                Outcome.Failed(e.reason)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { recorded.saveQuietly() }
            log.info("download {}: stopped at {} of {} bytes", id, run.written, run.size)
            throw e
        } finally {
            rate = null
            _live.value = null
            writing.set(false)
        }
    }

    /** Reads [run]'s file from its safe offset to its end, opening it again as each failure says. */
    private suspend fun transfer(run: Run, onOpen: (Long) -> Unit): Outcome {
        var wait = firstBackoff
        var misses = 0
        var reopen = false
        // Since when the service has been out of reach on a validated network, reopen after reopen.
        var outOfReach: TimeMark? = null
        while (true) {
            // Nothing opens while something streams, nor before 10 s after a stream that started since the last body,
            // however short (one that came and went during a wait or a prepare): its stop is kept until its wait is over.
            if (run.stopped || streaming.value) {
                waitOutStreams(run)
                outOfReach = null
                continue
            }
            if (!shown()) {
                log.info("download {}: debrid is hidden in this build: failing it at {} of {} bytes, with no call", run.id, run.written, run.size)
                return Outcome.Failed(DownloadHostRules.NOT_IN_THIS_VERSION)
            }
            val service = accounts(run.entry.account)
            if (service == null) {
                // A refused key (this write's own refusal, or a play's) leaves the account saved and switched on: it waits
                // for a new key at its offset, and its torrent is never moved or given up for it (L474).
                if (keyRefused(run.entry.account)) {
                    log.info("download {}: its account's key was refused: waiting for a new one at {} of {} bytes", run.id, run.written, run.size)
                    return Outcome.Waiting(Wait.KeyRefused)
                }
                if (!nextAccount(run, Lost.AccountGone)) return Outcome.NextRelease(Lost.AccountGone)
                wait = firstBackoff
                misses = 0
                outOfReach = null
                continue
            }
            // A retry asked for, or the network back, from here on ends the wait after this open.
            val since = wakes.mark()
            val from = run.written
            onOpen(from)
            val ended = body(run, service, from)
            val progressed = run.written > from
            if (progressed) {
                wait = firstBackoff
                misses = 0
                outOfReach = null
            }
            // What came is kept: a reopen starts there.
            run.out.save()
            if (ended == Ended.Complete) {
                log.info("download {}: written, {} bytes", run.id, run.size)
                return Outcome.Done
            }
            if (ended == Ended.StreamWait) {
                // No failure: it goes on with a fresh link once nothing has streamed for a while.
                waitOutStreams(run)
                outOfReach = null
                continue
            }
            val into = when (ended) {
                is Ended.Cut -> Into.Counted
                is Ended.NotReached -> notReached()
                is Ended.Refused -> when (val failure = ended.failure) {
                    LinkFailure.NoWorkingLink -> Into.Counted
                    LinkFailure.Down -> notReached()
                    LinkFailure.NoRanges -> {
                        if (run.restarted || folder.restarted(run.id)) {
                            log.warn("download {}: its server won't resume it at {} of {} bytes, started again from 0 once already: failing it", run.id, run.written, run.size)
                            return Outcome.Failed(DownloadHostRules.CANT_RESUME)
                        }
                        // Nothing written: no part is asked from past the start, so this is a broken answer.
                        if (run.written == 0L) Into.Counted else {
                            restart(run)
                            continue
                        }
                    }
                    LinkFailure.NotCached, LinkFailure.Removed, is LinkFailure.Refused -> {
                        val why = when (failure) {
                            LinkFailure.NotCached -> Lost.NotCached
                            LinkFailure.Removed -> Lost.Removed
                            else -> Lost.Refused
                        }
                        if (!nextAccount(run, why)) return Outcome.NextRelease(why)
                        wait = firstBackoff
                        misses = 0
                        outOfReach = null
                        continue
                    }
                    is LinkFailure.Limit -> {
                        val until = clock.now() + (failure.waitMs ?: LIMIT_WITHOUT_WAIT.inWholeMilliseconds)
                        log.info("download {}: its account's limit reached at {} of {} bytes: waiting for it to end", run.id, run.written, run.size)
                        return Outcome.Waiting(Wait.Limit(run.entry.account, until))
                    }
                    is LinkFailure.KeyRefused -> {
                        log.info("download {}: its account's key refused at {} of {} bytes: waiting for a new one", run.id, run.written, run.size)
                        return Outcome.Waiting(Wait.KeyRefused)
                    }
                }
                Ended.Complete, Ended.StreamWait -> error("handled above")
            }
            when (into) {
                Into.Unreachable -> {
                    val out = outOfReach ?: timeSource.markNow().also { outOfReach = it }
                    if (out.elapsedNow() >= UNREACHABLE_FOR) {
                        log.info("download {}: its service out of reach for {} min at {} of {} bytes: waiting", run.id, out.elapsedNow().inWholeMinutes, run.written, run.size)
                        return Outcome.Waiting(Wait.Unreachable)
                    }
                }
                Into.Offline -> outOfReach = null
                Into.Counted -> {
                    outOfReach = null
                    if (!progressed && reopen && ++misses >= MAX_NO_PROGRESS) {
                        log.warn("download {}: {} reopens in a row made no progress at {} of {} bytes ({}): failing it", run.id, misses, run.written, run.size, ended.kind)
                        return Outcome.Failed(DownloadHostRules.SERVICE_KEPT_FAILING)
                    }
                }
            }
            run.out.unreachable = into == Into.Unreachable
            run.out.said()
            log.info(
                "download {}: its read ended at {} of {} bytes ({}{}); opening it again in {} s",
                run.id, run.written, run.size, ended.kind, if (misses > 0) ", $misses of $MAX_NO_PROGRESS with no progress" else "", wait.inWholeSeconds,
            )
            reopen = true
            // A stream that starts meanwhile ends the wait for its own (L471), at the top.
            if (pause(wait, since) == true) {
                wait = firstBackoff
                outOfReach = null
            } else {
                wait = minOf(wait * 2, MAX_BACKOFF)
            }
        }
    }

    /**
     * One body of [run]'s file from [from], with a fresh link from [service], its own account, written as it comes: what
     * ended it. The read is let go however it ends, aborted first unless read to its end, so a close never reads the rest.
     */
    private suspend fun body(run: Run, service: DebridService, from: Long): Ended {
        val link = try {
            link(run, service)
        } catch (e: ServiceException) {
            return Ended.Refused(ServiceLinks.failureOf(e.error, service.kind))
        } catch (e: LinkUnavailable) {
            return Ended.Refused(e.failure)
        }
        val read = reads.make(resolver(run, service, link), PlayUris.service(run.entry.id, run.file.index))
        // Seen by a stream's start from here on; and the start before this is seen below.
        run.current = read
        var whole = false
        try {
            if (run.stopped || streaming.value) return Ended.StreamWait
            val answer = try {
                calling(run, read) { read.open(from) } ?: return Ended.StreamWait
            } catch (e: LinkUnavailable) {
                return Ended.Refused(e.failure)
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                return if (run.stopped) Ended.StreamWait else Ended.Cut(e)
            } catch (e: IOException) {
                return if (run.stopped) Ended.StreamWait else Ended.NotReached(e)
            } catch (e: Exception) {
                // What else the source threw (Media3's, or its HTTP stack's own state): a broken answer, counted.
                return if (run.stopped) Ended.StreamWait else Ended.Cut(e)
            }
            if (answer.length != null && answer.length != run.size - from || answer.total != null && answer.total != run.size) {
                log.info("download {}: an answer at {} of {} bytes isn't the rest of the file", run.id, from, run.size)
                return Ended.Refused(LinkFailure.NoWorkingLink)
            }
            val pace = throttle()?.let { Pace(it) }
            while (run.written < run.size) {
                // Looked at before each read: a stream that started stops the reads here at the latest.
                if (streaming.value) run.stopped = true
                val n = try {
                    calling(run, read) { read.read(run.out.buffer, 0, minOf(run.out.buffer.size.toLong(), run.size - run.written).toInt()) }
                        ?: return Ended.StreamWait
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    return if (run.stopped) Ended.StreamWait else Ended.Cut(e)
                } catch (e: Exception) {
                    // A cut (an IOException), or what else the source threw, a close from another thread meeting it among them.
                    return if (run.stopped) Ended.StreamWait else Ended.Cut(e)
                }
                // A read a stream's stop came during: none of its bytes is taken, as an abort from another thread may have
                // met it, nor its end; they are read again from the safe offset after the wait.
                if (run.stopped) return Ended.StreamWait
                if (n <= 0) return Ended.Cut(null)
                run.out.took(n)
                pace?.took(n)
            }
            if (answer.length == null && past(read)) {
                // Longer than the file: another one, so none of this body's bytes are its own.
                run.out.rewind(from)
                log.info("download {}: an answer at {} of {} bytes ran past the file's end", run.id, from, run.size)
                return Ended.Refused(LinkFailure.NoWorkingLink)
            }
            whole = true
            return Ended.Complete
        } finally {
            run.current = null
            if (!whole) read.abort()
            try {
                read.close()
            } catch (e: IOException) {
                // Let go all the same.
            }
        }
    }

    /** Whether a byte comes past the size from [read], whose answer said no length. */
    private suspend fun past(read: Read): Boolean = try {
        blocking(read) { read.read(ByteArray(1), 0, 1) } > 0
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        false
    } catch (e: Exception) {
        false
    }

    /**
     * A fresh link to [run]'s file from [service], its own account. A torrent the account added again for it is kept as
     * the row's under the same listing id. Throws [ServiceException] as the service refuses, [LinkUnavailable] for a link
     * that isn't HTTPS.
     */
    private suspend fun link(run: Run, service: DebridService): HttpLink {
        val link = service.link(run.entry.prepared, run.file)
        link.prepared?.let { again ->
            val file = again.listing.files.firstOrNull { it.index == run.file.index && it.path == run.file.path && it.size == run.file.size } ?: run.file
            relisted(run, listings.put(run.entry.account, run.entry.title, again), file)
            log.info("download {}: its torrent was added to its account again for a link: its listing kept anew", run.id)
        }
        if (httpsOnly() && !link.url.startsWith("https://", ignoreCase = true)) throw LinkUnavailable(LinkFailure.NoWorkingLink)
        return HttpLink(link.url, link.madeAt.toEpochMilli(), run.file.name)
    }

    /**
     * The links one read opens with: [first] first, the one asked for it; then, for a dead link's refresh within the same
     * open, a fresh one from [service], as a play's source asks.
     */
    private fun resolver(run: Run, service: DebridService, first: HttpLink): LinkResolver {
        val unused = AtomicReference<HttpLink?>(first)
        return LinkResolver { _, _ ->
            unused.getAndSet(null) ?: try {
                runBlocking { link(run, service) }
            } catch (e: ServiceException) {
                throw LinkUnavailable(ServiceLinks.failureOf(e.error, service.kind))
            }
        }
    }

    /**
     * L424: the row's torrent from the next account on not tried yet that lists its file at the same path and size,
     * prepared there (the file picked before anything is added), its listing kept as the row's; false with none.
     */
    private suspend fun nextAccount(run: Run, why: Lost): Boolean {
        val hash = run.entry.prepared.hash
        for (account in accountsOn()) {
            if (!run.tried.add(account)) continue
            val service = accounts(account) ?: continue
            val prepared = try {
                service.prepare(hash) { listing -> listing.files.firstOrNull { it.path == run.file.path && it.size == run.file.size } }
            } catch (e: ServiceException) {
                log.info("download {}: the next account couldn't give its torrent ({})", run.id, e.error::class.simpleName)
                continue
            }
            val file = prepared.picked ?: continue
            relisted(run, listings.put(account, run.entry.title, prepared), file)
            log.info("download {}: its torrent lost on its account ({}): going on at {} of {} bytes from the next account", run.id, why, run.written, run.size)
            return true
        }
        log.info("download {}: its torrent lost on its account ({}) and no other gives it: giving its release up at {} of {} bytes", run.id, why, run.written, run.size)
        return false
    }

    /**
     * [entry] and its [file] as [run]'s from now on: kept as the row's copy, then in the shared store. One that isn't
     * written leaves the copy before it, from which a later process finds the torrent again.
     */
    private suspend fun relisted(run: Run, entry: DebridListingStore.Entry, file: ServiceFile) {
        run.entry = entry
        run.file = file
        try {
            folder.keepListing(run.id, entry)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("download {}: its listing's copy wasn't written ({})", run.id, e.javaClass.simpleName)
        }
        try {
            listings.keep(entry.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("download {}: its listing wasn't kept with the plays' ({})", run.id, e.javaClass.simpleName)
        }
    }

    /**
     * L419: [run]'s server won't resume it: the mark first, so no later process starts it again, then the record back to 0,
     * where the file is opened again at once.
     */
    private suspend fun restart(run: Run) {
        try {
            folder.markRestarted(run.id)
        } catch (e: IOException) {
            throw RecordedFile.StorageFailure(e)
        }
        run.restarted = true
        log.info("download {}: its server won't resume it at {} of {} bytes: starting it again from 0, once", run.id, run.written, run.size)
        run.out.rewind()
        run.out.save()
    }

    /**
     * Waits while something streams (L471), its live saying so, until [streaming] has read false for [QUIET] together:
     * another stream starting meanwhile waits again. The stop of every stream before is then over.
     */
    private suspend fun waitOutStreams(run: Run) {
        run.out.unreachable = false
        run.out.streamWait = true
        run.out.said()
        log.info("download {}: something streams: waiting at {} of {} bytes", run.id, run.written, run.size)
        while (true) {
            streaming.first { !it }
            if (withTimeoutOrNull(QUIET) { streaming.first { it } } == null) break
        }
        run.stopped = false
        run.out.streamWait = false
        run.out.said()
        log.info("download {}: nothing has streamed for {} s: going on at {} of {} bytes", run.id, QUIET.inWholeSeconds, run.written, run.size)
    }

    /**
     * [wakes]' wait of [wait] before a reopen: true when a retry asked for or the network's return since [since] ended it
     * sooner, null when a stream started meanwhile, false when it ran out.
     */
    private suspend fun pause(wait: Duration, since: BackoffWakes.Mark): Boolean? = coroutineScope {
        val woke = async { wakes.pause(wait, since) }
        val streams = async { streaming.first { it } }
        select<Boolean?> {
            woke.onAwait { it }
            streams.onAwait { null }
        }.also { coroutineContext.cancelChildren() }
    }

    private fun notReached() = if (network.value) Into.Unreachable else Into.Offline

    /**
     * [call] on [run]'s [read] (its open or a read), marked under way for a stream's stop to see; null, with no call made,
     * where a stop came first. The mark is set before the stop is looked at, and a stop is set before the mark is: so
     * either the stop waits for the call, or the call is never made.
     */
    private suspend fun <T> calling(run: Run, read: Read, call: () -> T): T? {
        run.calling.value = true
        try {
            if (run.stopped) return null
            return blocking(read) {
                try {
                    call()
                } finally {
                    run.calling.value = false
                }
            }
        } finally {
            run.calling.value = false
        }
    }

    /**
     * [call] on [dispatcher], where it may block; a cancel meanwhile aborts [read], so the call comes back at once and
     * the cancel goes on.
     */
    private suspend fun <T> blocking(read: Read, call: () -> T): T = coroutineScope {
        val done = async(dispatcher) { runCatching(call) }
        val result = try {
            done.await()
        } catch (e: CancellationException) {
            read.abort()
            throw e
        }
        result.getOrThrow()
    }

    /** What ended one body, and its kind for the log. */
    private sealed interface Ended {
        val kind: String

        /** Read to the file's end. */
        data object Complete : Ended {
            override val kind get() = "Complete"
        }

        /** Stopped as something streams. */
        data object StreamWait : Ended {
            override val kind get() = "StreamWait"
        }

        /** The body ended before the file's end: [cause], or none for an end that came early. */
        class Cut(val cause: Exception?) : Ended {
            override val kind get() = cause?.let { it::class.simpleName } ?: "EndedShort"
        }

        /** The open reached nothing ([cause]). */
        class NotReached(val cause: IOException) : Ended {
            override val kind get() = cause::class.simpleName ?: "IOException"
        }

        /** No link, or no working one, as [failure] says. */
        class Refused(val failure: LinkFailure) : Ended {
            override val kind get() = failure::class.simpleName ?: "LinkFailure"
        }
    }

    private enum class Into {
        /** Opened again; counts toward the six with no progress. */
        Counted,

        /** Opened again; counts toward the hour out of reach alone. */
        Unreachable,

        /** Opened again, the network not validated; counts toward nothing. */
        Offline,
    }

    /** One write's state, over the download's own file ([out]). */
    private class Run(val id: String, entry: DebridListingStore.Entry, file: ServiceFile, val out: RecordedFile) {
        val size = file.size

        /** The listing the row reads from now: its copy's, or the one a re-add or the next account made. */
        @Volatile
        var entry = entry

        @Volatile
        var file = file

        /** Every byte before this is written. */
        val written get() = out.written

        /** The accounts asked for the torrent in this write: its own first. */
        val tried = hashSetOf(entry.account)

        /** The read open now, which a stream's start stops. */
        @Volatile
        var current: Read? = null

        /** Whether a call on [current] (its open or a read) is under way. */
        val calling = MutableStateFlow(false)

        /** Set as a stream starts: the body under way stops. */
        @Volatile
        var stopped = false

        /** Whether the file was started again from 0 in this write. */
        var restarted = false

        /**
         * A stream started: the body stops. A call on its read under way is given [letGo] to come back, and is then let go
         * on the writer's side alone (Android's HTTP stack reads the rest of a body on the thread that closes it, which
         * beside a read would have two threads in its buffers); one still waiting on the CDN after it is aborted from here.
         */
        suspend fun stopForStream(letGo: Duration) {
            stopped = true
            val read = current ?: return
            if (withTimeoutOrNull(letGo) { calling.first { !it } } == null) read.abort()
        }
    }

    /** Holds a body to [bytesPerSecond] (the debug throttle): each chunk waits for its share of the time. */
    private inner class Pace(private val bytesPerSecond: Long) {
        private val start = timeSource.markNow()
        private var bytes = 0L

        suspend fun took(n: Int) {
            bytes += n
            val ahead = (bytes * NANOS_A_SECOND / bytesPerSecond).toLong().nanoseconds - start.elapsedNow()
            if (ahead.isPositive()) delay(ahead)
        }
    }

    companion object {
        /** The first wait before a reopen, doubling each time to [MAX_BACKOFF]: the Usenet writer's. */
        val FIRST_BACKOFF = DownloadWriter.FIRST_BACKOFF
        val MAX_BACKOFF = DownloadWriter.MAX_BACKOFF

        /** Reopens in a row with no progress before the download fails: the Usenet writer's six. */
        const val MAX_NO_PROGRESS = DownloadWriter.MAX_NO_PROGRESS

        /** How long the service is out of reach on a validated network before the download waits. */
        val UNREACHABLE_FOR = DownloadWriter.UNREACHABLE_FOR

        /** How long nothing must have streamed before the download goes on (L471): the lead's, which M-wait measures. */
        val QUIET = 10.seconds

        /**
         * How long a stream's stop gives a call on the read under way to come back before it aborts it from another thread:
         * one from a body still flowing comes back within milliseconds and is let go after it on the writer's side alone,
         * as the player's read-ahead and the cast relay let theirs go; one still waiting after it waits on a CDN gone silent.
         */
        val LET_GO = 200.milliseconds

        /** How long a service's gate stays closed for a limit whose answer named no wait, as its gate keeps it. */
        val LIMIT_WITHOUT_WAIT = 1.hours

        private const val NANOS_A_SECOND = 1_000_000_000.0

        private val TOTAL = Regex("""^\s*bytes\s+\d+-\d+/(\d+)\s*$""", RegexOption.IGNORE_CASE)

        /** The total a `Content-Range` names ("bytes 0-99/1000": 1000); null for none, or one that names no total. */
        fun totalOf(contentRange: String?): Long? = contentRange?.let(TOTAL::matchEntire)?.groupValues?.get(1)?.toLongOrNull()

        private val log = LoggerFactory.getLogger(ServiceDownloadWriter::class.java)
    }
}

/**
 * The app's reads for [ServiceDownloadWriter]: Media3's HTTP source under a ServiceDataSource of a factory made for that
 * read alone, metered nowhere (a download's bytes aren't a stream's, L471), the links its resolver gives; the total read
 * from the answer's `Content-Range`.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object ServiceFileReads : ServiceDownloadWriter.Reads {
    override fun make(resolver: LinkResolver, uri: String): ServiceDownloadWriter.Read =
        SourceRead(ServiceDataSource.Factory(resolver, traffic = null).createDataSource(), uri)

    private class SourceRead(private val source: ServiceDataSource, private val uri: String) : ServiceDownloadWriter.Read {
        override fun open(position: Long): ServiceDownloadWriter.Answer {
            val length = source.open(DataSpec.Builder().setUri(Uri.parse(uri)).setPosition(position).build())
            val range = source.responseHeaders.entries.firstOrNull { entry ->
                val key: String? = entry.key
                key?.equals(CONTENT_RANGE, ignoreCase = true) == true
            }?.value?.firstOrNull()
            return ServiceDownloadWriter.Answer(length.takeIf { it != C.LENGTH_UNSET.toLong() }, ServiceDownloadWriter.totalOf(range))
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = source.read(buffer, offset, length)

        override fun abort() = source.abort()

        override fun close() = source.close()

        // No URI or link in its words.
        override fun toString() = "ServiceFileReads.Read"
    }

    private const val CONTENT_RANGE = "Content-Range"

    override fun toString() = "ServiceFileReads"
}
