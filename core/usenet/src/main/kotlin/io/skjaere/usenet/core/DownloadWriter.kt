package io.skjaere.usenet.core

import io.ktor.utils.io.ClosedByteChannelException
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Writes a prepared file to a download's own file (L342, L345, L382): its bytes from a first byte to its end through
 * [opener] (the engine's download opener, [NzbStreamOpener.download]), each at its offset, then each stretch the stream
 * filled with zeros asked for once more. One write at a time; it blocks on the file in place, so it is called on a
 * dispatcher that may block.
 *
 * **The record.** Every [recordBytes] (32 MiB) or [recordEvery] (10 s), at each failure and at the end, the file is forced
 * to storage, then the caller records the safe offset (every byte before it written and forced) with the stretches missing
 * so far. A stream says each stretch it fills before its zeros come ([OpenedStream.gaps]), so the record that passes one
 * names it. A power cut can lose the last record, never put it ahead of the data. A cancelled write (a pause, a cancel,
 * the host ending) records what it forced before it ends: a caller that changes the row after a cancel joins it first.
 *
 * **Failures** are sorted by what ended the stream ([ByteReadChannel.closedCause][io.ktor.utils.io.ByteReadChannel.closedCause],
 * or the open's failure; C11, L382):
 * - a dropped connection, a timeout or a provider out of reach is opened again from the safe offset after a wait from
 *   2 s, doubling, to 5 min, which [retryNow] and a network's return end, starting again from 2 s (L381); so is a stream the
 *   engine ended as it built its streamer again (L347), or one that ended short with no cause;
 * - one that got no connection ([UsenetError.ConnectionFailed], or a dropped connection or timeout before any byte came)
 *   while Android calls the network validated is a provider out of reach: it counts toward nothing but an hour, after which
 *   the download waits ([Wait.Unreachable], L388); on a network that isn't validated it counts toward nothing at all;
 * - six reopens in a row that reached the provider and made no progress fail it ([NO_PROGRESS]);
 * - a part the NZB lists no article for, or one of a guessed size ([UsenetError.MissingArticle]), and a damaged release
 *   ([UsenetError.Corrupt]) give the release up for the next, as does a stretch missing in a row of [longGapBytes] or more
 *   (a long gap, L342), whose bytes are no use, and a reopen that ends short with no cause where the stream before it did
 *   (the file's last listed article shorter than its guessed size);
 * - a login refused, or no provider ([UsenetError.AuthFailed], [UsenetError.NotConfigured]), waits for the providers;
 * - a write or force the volume has no room for (ENOSPC, EFBIG) fails it as [STORAGE_FULL], with no reopen: its file and
 *   record are kept, and Try again goes on from the safe offset once there is room.
 *
 * Its rate is the bytes a second over the last 5 s ([bytesPerSecond]); [throttle] holds each stream to that many, the debug
 * build's seam for the device tests' pairs (item 8). Its log lines name the download by its id, with offsets, sizes and a
 * cause's kind (its [UsenetError]'s class): never the release, a host, or a cause's words, which can name the provider's
 * host.
 */
class DownloadWriter(
    private val opener: StreamOpener,
    /** Whether Android calls the device's network validated: read at each failure, and its return ends a wait (L381). */
    private val network: StateFlow<Boolean> = MutableStateFlow(true),
    /** At most how many bytes a second a stream is taken at, asked as each opens; null: as they come. */
    private val throttle: () -> Long? = { null },
    /** The record's, the rate's and the hour's clock; a wait before a reopen is the coroutine's own ([delay]). */
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val recordBytes: Long = RECORD_BYTES,
    private val recordEvery: Duration = RECORD_EVERY,
    private val longGapBytes: Long = LONG_GAP_BYTES,
) {
    /** The write under way: [written] bytes of download [id]'s file (forced or not), and whether its provider is out of reach now. */
    data class Live(val id: String, val written: Long, val unreachable: Boolean)

    /** How a write ended. */
    sealed interface Outcome {
        /** The whole file is written and recorded, with [gaps]: what the end's retry couldn't fill. */
        data class Done(val gaps: List<LongRange>) : Outcome

        /** This release can't give the file ([why]): the next is tried while tries are left. */
        data class NextRelease(val why: GiveUp) : Outcome

        /** The download can't go on until its providers can ([wait]); what was written is recorded. */
        data class Waiting(val wait: Wait) : Outcome

        /** Ended, for [reason] in the download's words; what was written is recorded. */
        data class Failed(val reason: String) : Outcome
    }

    /** Why a release was given up. */
    enum class GiveUp {
        /**
         * A part its NZB lists no article for, or one whose size it only guesses ([UsenetError.MissingArticle]), or a file
         * whose streams keep ending short at the same byte.
         */
        Incomplete,

        /** Its articles can't be read ([UsenetError.Corrupt]). */
        Corrupt,

        /** A stretch missing in a row of [LONG_GAP_BYTES] or more (L342). */
        LongGap,
    }

    /** What a waiting download waits for (L388). */
    enum class Wait {
        /** The providers saved again: a login refused, or none saved and on. */
        Provider,

        /** A provider in reach: an hour of reopens got no connection on a network Android calls validated. */
        Unreachable,
    }

    private val writing = AtomicBoolean()
    private val _live = MutableStateFlow<Live?>(null)

    /** The write under way; null between writes. */
    val live: StateFlow<Live?> = _live.asStateFlow()

    @Volatile
    private var rate: TransferRate? = null

    /** Bumped by [retryNow]: a wait before a reopen ends when it changes. */
    private val wakes = MutableStateFlow(0)

    /** The write's bytes a second over the last 5 s ([RATE_WINDOW]); 0 between writes. */
    fun bytesPerSecond(): Long = rate?.bytesPerSecond() ?: 0

    /** Ends a wait before a reopen, its backoff starting again from 2 s (L381): Resume, a provider saved. */
    fun retryNow() = wakes.update { it + 1 }

    /**
     * Writes download [id]'s [file] of [nzb] to [out] (allocated whole) from [from], every byte before which is written,
     * with [gaps], the stretches recorded missing so far, as the class says. [onOpen] hears each stream's first byte as it
     * opens; [record] keeps the safe offset and the gaps, after the file is forced. A [record] that fails ends the write
     * with its failure.
     */
    suspend fun write(
        id: String,
        nzb: PreparedNzb,
        file: FileCandidate,
        out: FileChannel,
        from: Long,
        gaps: List<LongRange>,
        onOpen: (offset: Long) -> Unit = {},
        record: suspend (safeOffset: Long, gaps: List<LongRange>) -> Unit,
    ): Outcome {
        require(from in 0..file.size) { "A first byte of $from in a file of ${file.size} bytes" }
        check(writing.compareAndSet(false, true)) { "One write at a time" }
        val run = Run(id, nzb, file, out, from, gaps, record)
        try {
            rate = run.rate
            _live.value = Live(id, from, unreachable = false)
            log.info("download {}: writing from byte {} of {}, {} stretch{} missing so far", id, from, file.size, gaps.size, plural(gaps.size, "es"))
            return try {
                transfer(run, onOpen) ?: finish(run)
            } catch (e: StorageFailure) {
                // What is written in front of it stays, as far as storage keeps it: Try again goes on from there.
                log.warn(
                    "download {}: writing at {} of {} bytes failed: {}", id, run.written, run.size, if (e.full) "storage full" else e.cause::class.simpleName,
                )
                run.saveQuietly()
                Outcome.Failed(if (e.full) STORAGE_FULL else WRITE_FAILED)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { run.saveQuietly() }
            log.info("download {}: stopped at {} of {} bytes", id, run.written, run.size)
            throw e
        } finally {
            rate = null
            _live.value = null
            writing.set(false)
        }
    }

    /** Streams [run]'s file from its safe offset to its end, opening it again as each failure says; null once it is all written. */
    private suspend fun transfer(run: Run, onOpen: (Long) -> Unit): Outcome? {
        var wait = FIRST_BACKOFF
        var misses = 0
        var reopen = false
        // Since when the provider has been out of reach on a validated network, reopen after reopen.
        var outOfReach: TimeMark? = null
        // Where the last stream that ended short with no cause ended.
        var shortAt: Long? = null
        while (run.written < run.size) {
            val from = run.written
            onOpen(from)
            val ended = stream(run, from)
            if (ended == Ended.Complete) break
            if (ended == Ended.LongGap) {
                log.info("download {}: {} bytes or more missing in a row by byte {}: giving its release up", run.id, longGapBytes, run.written)
                return Outcome.NextRelease(GiveUp.LongGap)
            }
            ended as Ended.Failed
            val progressed = run.written > from
            if (progressed) {
                wait = FIRST_BACKOFF
                misses = 0
                outOfReach = null
            }
            // What came is kept: a reopen starts there.
            run.save()
            if (ended.cause == null) {
                // Ended short again where the stream before it did, with nothing between: the release can't give the bytes
                // past it (its layout only guesses the file's size, its last articles not in the NZB), and no reopen will.
                if (!progressed && shortAt == run.written) {
                    log.info("download {}: its streams end at {} of {} bytes: giving its release up", run.id, run.written, run.size)
                    return Outcome.NextRelease(GiveUp.Incomplete)
                }
                shortAt = run.written
            } else {
                shortAt = null
            }
            val sorted = sort(ended.cause, progressed)
            when (sorted.into) {
                Into.Incomplete, Into.Corrupt -> {
                    log.info("download {}: its release can't give it at {} of {} bytes ({}): giving it up", run.id, run.written, run.size, sorted.kind)
                    return Outcome.NextRelease(if (sorted.into == Into.Incomplete) GiveUp.Incomplete else GiveUp.Corrupt)
                }
                Into.Provider -> {
                    log.info("download {}: waiting for its providers at {} of {} bytes ({})", run.id, run.written, run.size, sorted.kind)
                    return Outcome.Waiting(Wait.Provider)
                }
                Into.Unreachable -> {
                    val since = outOfReach ?: timeSource.markNow().also { outOfReach = it }
                    if (since.elapsedNow() >= UNREACHABLE_FOR) {
                        log.info(
                            "download {}: its provider out of reach for {} min at {} of {} bytes: waiting",
                            run.id, since.elapsedNow().inWholeMinutes, run.written, run.size,
                        )
                        return Outcome.Waiting(Wait.Unreachable)
                    }
                }
                Into.Offline -> outOfReach = null
                Into.Counted -> {
                    outOfReach = null
                    if (!progressed && reopen && ++misses >= MAX_NO_PROGRESS) {
                        log.warn(
                            "download {}: {} reopens in a row made no progress at {} of {} bytes ({}): failing it",
                            run.id, misses, run.written, run.size, sorted.kind,
                        )
                        return Outcome.Failed(NO_PROGRESS)
                    }
                }
                Into.Again -> Unit
            }
            run.unreachable = sorted.into == Into.Unreachable
            run.said()
            log.info(
                "download {}: its stream ended at {} of {} bytes ({}{}); opening it again in {} s",
                run.id, run.written, run.size, sorted.kind, if (misses > 0) ", $misses of $MAX_NO_PROGRESS with no progress" else "", wait.inWholeSeconds,
            )
            reopen = true
            if (pause(wait)) {
                wait = FIRST_BACKOFF
                outOfReach = null
            } else {
                wait = minOf(wait * 2, MAX_BACKOFF)
            }
        }
        return null
    }

    /** One stream of [run]'s file from [from] to its end, written as it comes: what ended it. */
    private suspend fun stream(run: Run, from: Long): Ended {
        val opened = try {
            opener.open(run.nzb, run.file.index, from until run.size)
        } catch (e: CancellationException) {
            // The caller's own cancellation goes on up; one of the opener's own is a failure to open.
            currentCoroutineContext().ensureActive()
            return Ended.Failed(e)
        } catch (e: Exception) {
            return Ended.Failed(e)
        }
        val pace = throttle()?.let { Pace(it) }
        val longGap = AtomicBoolean()
        // On the stream's download threads: a long gap stops it at once.
        opened.gaps.listen { range ->
            if (run.missing(range)) {
                longGap.set(true)
                opened.job.cancel()
            }
        }
        try {
            while (run.written < run.size) {
                val n = try {
                    opened.channel.readAvailable(run.buffer, 0, run.buffer.size)
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    return if (longGap.get()) Ended.LongGap else Ended.Failed(e)
                } catch (e: Exception) {
                    return if (longGap.get()) Ended.LongGap else Ended.Failed(e)
                }
                if (longGap.get()) return Ended.LongGap
                if (n < 0) break
                run.took(n)
                pace?.took(n)
            }
            return when {
                longGap.get() -> Ended.LongGap
                run.written >= run.size -> Ended.Complete
                else -> Ended.Failed(opened.channel.closedCause)
            }
        } finally {
            opened.job.cancel()
            opened.channel.cancel(null)
        }
    }

    /** All of [run]'s file written: each stretch missing asked for once more, then the record at its end (L342). */
    private suspend fun finish(run: Run): Outcome {
        run.save()
        val missing = run.known.all()
        if (missing.isNotEmpty()) {
            val left = missing.flatMap { retry(run, it) }
            val whole = missing.count { gap -> left.none { it.first >= gap.first && it.last <= gap.last } }
            log.info("download {}: {} of {} missing stretch{} came whole when asked for again", run.id, whole, missing.size, plural(missing.size, "es"))
            run.known = StreamGaps().apply { left.forEach(::add) }
            run.save()
        }
        val gaps = run.known.all()
        log.info("download {}: written, {} bytes, {} stretch{} missing", run.id, run.size, gaps.size, plural(gaps.size, "es"))
        return Outcome.Done(gaps)
    }

    /** [gap] of [run]'s file streamed once more, what comes written where it goes: what of it is still missing. */
    private suspend fun retry(run: Run, gap: LongRange): List<LongRange> {
        val opened = try {
            opener.open(run.nzb, run.file.index, gap)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            return listOf(gap)
        } catch (e: Exception) {
            return listOf(gap)
        }
        val pace = throttle()?.let { Pace(it) }
        var at = gap.first
        try {
            while (at <= gap.last) {
                val n = try {
                    opened.channel.readAvailable(run.buffer, 0, minOf(run.buffer.size.toLong(), gap.last - at + 1).toInt())
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    break
                } catch (e: Exception) {
                    break
                }
                if (n < 0) break
                run.writeAt(at, n, advance = false)
                at += n
                pace?.took(n)
            }
        } finally {
            opened.job.cancel()
            opened.channel.cancel(null)
        }
        // What it filled with zeros again, and what it didn't reach.
        val came = gap.first until at
        return opened.gaps.all().mapNotNull { it.within(came) } + listOfNotNull((at..gap.last).takeUnless { it.isEmpty() })
    }

    /**
     * Waits [wait] before a reopen; true when [retryNow] or the network's return ended it sooner (L381): a network up now
     * first has to go and come back.
     */
    private suspend fun pause(wait: Duration): Boolean {
        val seen = wakes.value
        return withTimeoutOrNull(wait) {
            merge(wakes.filter { it != seen }.map { }, network.dropWhile { it }.filter { it }.map { }).first()
        } != null
    }

    /** What ended a stream ([cause]: its close's, or its open's; null: it ended short with none), and its kind for the log. */
    private fun sort(cause: Throwable?, progressed: Boolean): Sorted {
        if (cause == null) return Sorted(Into.Counted, "EndedShort")
        // A read of a stream that failed throws ktor's wrapper of the failure, an IOException whatever it wraps: sorted as
        // it, any failure would be a dropped connection. What it wraps says what ended the stream.
        val origin = cause.causeChain().firstOrNull { it !is ClosedByteChannelException } ?: cause
        val chain = origin.causeChain()
        // Classified where it was thrown: it says what it is.
        if (chain.none { it is UsenetException }) {
            // The engine's own: it closed the streamer (a rebuild, the providers saved) or its scope under the stream.
            if (chain.any { it is CancellationException || it is IllegalStateException && it.message == POOL_CLOSED }) return Sorted(Into.Again, "EngineCut")
        }
        val error = origin.toUsenetError()
        val into = when (error) {
            is UsenetError.AuthFailed, UsenetError.NotConfigured -> Into.Provider
            is UsenetError.MissingArticle -> Into.Incomplete
            is UsenetError.Corrupt -> Into.Corrupt
            is UsenetError.ConnectionFailed -> notReached()
            is UsenetError.Network, is UsenetError.Timeout -> if (progressed) Into.Counted else notReached()
            else -> Into.Counted
        }
        return Sorted(into, error::class.simpleName ?: "UsenetError")
    }

    private fun notReached() = if (network.value) Into.Unreachable else Into.Offline

    /** Where a failure sends the write, and its kind for the log. */
    private class Sorted(val into: Into, val kind: String)

    private enum class Into {
        /** Opened again; counts toward the six with no progress. */
        Counted,

        /** Opened again; counts toward nothing (the engine's own). */
        Again,

        /** Opened again; counts toward the hour out of reach alone. */
        Unreachable,

        /** Opened again, the network not validated; counts toward nothing. */
        Offline,
        Provider,
        Incomplete,
        Corrupt,
    }

    /** What ended one stream. */
    private sealed interface Ended {
        data object Complete : Ended

        data object LongGap : Ended

        /** Failed with [cause]; null: it ended short with none. */
        class Failed(val cause: Throwable?) : Ended
    }

    /** One write's state: its file, how far it is written and recorded, what is missing, its rate. */
    private inner class Run(
        val id: String,
        val nzb: PreparedNzb,
        val file: FileCandidate,
        private val out: FileChannel,
        from: Long,
        gaps: List<LongRange>,
        private val record: suspend (Long, List<LongRange>) -> Unit,
    ) {
        val size = file.size
        val buffer = ByteArray(CHUNK)
        val rate = TransferRate(timeSource)

        /** Every byte before this is written. */
        @Volatile
        var written = from

        /** The stretches missing, from the record before and each stream: merged, safe from any thread. */
        @Volatile
        var known = StreamGaps().apply { gaps.forEach(::add) }

        var unreachable = false

        private var recorded = from
        private var recordedGaps = known.all()
        private var since = timeSource.markNow()

        /** Adds [range], a stream's stretch of zeros; true when it makes a stretch missing in a row a long gap. */
        fun missing(range: LongRange): Boolean {
            known.add(range)
            return known.next(range.first)?.let { it.last - it.first + 1 >= longGapBytes } == true
        }

        /** [n] bytes of [buffer] came: written at [written], and recorded when the cadence says. */
        suspend fun took(n: Int) {
            writeAt(written, n, advance = true)
            rate.add(n.toLong())
            // Bytes come: the provider is in reach.
            unreachable = false
            said()
            if (written - recorded >= recordBytes || since.elapsedNow() >= recordEvery) save()
        }

        /** Says where the write is ([live]). */
        fun said() {
            _live.value = Live(id, written, unreachable)
        }

        /** Writes [n] bytes of [buffer] at [position]; with [advance], [written] goes on with each part written. */
        fun writeAt(position: Long, n: Int, advance: Boolean) {
            val bytes = ByteBuffer.wrap(buffer, 0, n)
            var at = position
            while (bytes.hasRemaining()) {
                at += try {
                    out.write(bytes, at)
                } catch (e: IOException) {
                    throw StorageFailure(e)
                }
                if (advance) written = at
            }
        }

        /** Forces the file, then records [written] with what is missing: nothing when neither changed. */
        suspend fun save() {
            val at = written
            if (at == recorded && known.all() == recordedGaps) return
            try {
                out.force(false)
            } catch (e: IOException) {
                throw StorageFailure(e)
            }
            // Every stretch before [at] was added before its zeros came, so before they were written.
            val gaps = known.all()
            record(at, gaps)
            recorded = at
            recordedGaps = gaps
            since = timeSource.markNow()
        }

        /** [save], storage refusing it meaning only that the record stays where it was. */
        suspend fun saveQuietly() {
            try {
                save()
            } catch (e: StorageFailure) {
                log.warn("download {}: couldn't record {} of {} bytes: {}", id, written, size, if (e.full) "storage full" else e.cause::class.simpleName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("download {}: couldn't record {} of {} bytes: {}", id, written, size, e::class.simpleName)
            }
        }
    }

    /** Holds a stream to [bytesPerSecond] (the debug throttle): each chunk waits for its share of the time. */
    private inner class Pace(private val bytesPerSecond: Long) {
        private val start = timeSource.markNow()
        private var bytes = 0L

        suspend fun took(n: Int) {
            bytes += n
            val ahead = (bytes * NANOS_A_SECOND / bytesPerSecond).toLong().nanoseconds - start.elapsedNow()
            if (ahead.isPositive()) delay(ahead)
        }
    }

    /** A write or force of the download's file failed: [full] when for want of room (ENOSPC, EFBIG). */
    private class StorageFailure(override val cause: IOException) : RuntimeException(cause) {
        val full = cause.causeChain().any { c -> c.message?.let { message -> STORAGE_FULL_WORDS.any { it in message } } == true }
    }

    companion object {
        /** A stretch missing in a row of this many bytes or more gives the release up (L342): about 64 articles. A guess. */
        const val LONG_GAP_BYTES = 48L * 1024 * 1024

        /** The record at least this often in bytes (L345; M5 measures it). */
        const val RECORD_BYTES = 32L * 1024 * 1024

        /** The record at least this often in time. */
        val RECORD_EVERY = 10.seconds

        /** The first wait before a reopen, doubling each time to [MAX_BACKOFF]. */
        val FIRST_BACKOFF = 2.seconds
        val MAX_BACKOFF = 5.minutes

        /** Reopens in a row that reached the provider and made no progress before the download fails. */
        const val MAX_NO_PROGRESS = 6

        /** How long a provider is out of reach on a validated network before the download waits (L388). */
        val UNREACHABLE_FOR = 1.hours

        /** The rate's window ([bytesPerSecond]). */
        val RATE_WINDOW = 5.seconds

        /** The failed download's words: the volume has no room for it (L382). */
        const val STORAGE_FULL = "Storage is full"

        /** The failed download's words: six reopens got nothing. */
        const val NO_PROGRESS = "Your Usenet provider kept failing"

        /** The failed download's words: its file can't be written, for want of anything but room. */
        const val WRITE_FAILED = "Couldn't write the file"

        /** What a stream is read in at most. */
        private const val CHUNK = 256 * 1024

        /** How ktor-nntp-client's pool fails a fetch it was closed under. */
        private const val POOL_CLOSED = "Pool closed"

        /** How the JVM and Android say ENOSPC and EFBIG. */
        private val STORAGE_FULL_WORDS = listOf("ENOSPC", "No space left on device", "EFBIG", "File too large")

        private const val NANOS_A_SECOND = 1_000_000_000.0

        private val log = LoggerFactory.getLogger(DownloadWriter::class.java)

        private fun plural(count: Int, ending: String = "s") = if (count == 1) "" else ending
    }
}

/** The part of this range within [other]; null when none is. */
private fun LongRange.within(other: LongRange): LongRange? = (maxOf(first, other.first)..minOf(last, other.last)).takeUnless { it.isEmpty() }

/**
 * Bytes a second over the last [window], or over the time since the first byte while that is shorter (a second at least):
 * what a transfer's row says. Safe from any thread.
 */
class TransferRate(timeSource: TimeSource = TimeSource.Monotonic, private val window: Duration = DownloadWriter.RATE_WINDOW) {
    private val start = timeSource.markNow()
    private var first: Duration? = null

    // When, from [start], and how many bytes, oldest first: one a tenth of a second at most, those past the window dropped.
    private val samples = ArrayDeque<Pair<Duration, Long>>()

    @Synchronized
    fun add(bytes: Long) {
        val now = start.elapsedNow()
        if (first == null) first = now
        val last = samples.lastOrNull()
        if (last != null && now - last.first < BUCKET) samples[samples.lastIndex] = last.first to last.second + bytes else samples.addLast(now to bytes)
        drop(now)
    }

    @Synchronized
    fun bytesPerSecond(): Long {
        val since = first ?: return 0
        val now = start.elapsedNow()
        drop(now)
        val span = minOf(window, maxOf(now - since, 1.seconds))
        return (samples.sumOf { it.second } / span.toDouble(DurationUnit.SECONDS)).toLong()
    }

    private fun drop(now: Duration) {
        while (samples.isNotEmpty() && samples.first().first <= now - window) samples.removeFirst()
    }

    private companion object {
        val BUCKET = 100.milliseconds
    }
}
