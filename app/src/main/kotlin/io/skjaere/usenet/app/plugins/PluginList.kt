package io.skjaere.usenet.app.plugins

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The plugin list as the app knows it (L620): its copy ([PluginListCache]) and when it is read. Only three things read it:
 * the Plugins page as it opens ([pageOpened]), unless the list was read in the last 5 minutes (raw.githubusercontent.com's
 * own max-age); its Check again ([checkAgain]); and the first resume at least a day after the last attempt, good or not,
 * while a plugin is installed ([resumed]), so a failing GitHub is asked once a day at most. Never a job, an alarm or a
 * timer: with no plugin installed and the page never opened, the app never contacts GitHub. A Retry-After holds every read,
 * a day at most, so a withdrawal still arrives within one. One read at a time; the copy is read from its file at each
 * trigger, so what a test deleted is gone. Each read GitHub answered is handed on ([reads]) for the updates it brings.
 */
class PluginList(
    private val source: () -> PluginSource,
    private val reader: (PluginSource) -> ListReader,
    private val cache: PluginListCache,
    private val installed: suspend () -> Boolean,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val shown = MutableStateFlow(PluginListState())

    /** The list as this build reads its copy, when GitHub last answered, and whether a read failed or runs now. */
    val state: StateFlow<PluginListState> = shown.asStateFlow()

    private val handed = MutableSharedFlow<PluginIndex>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Each read GitHub answered (a list, a 304 or a 404), as the list then stands: the installer's cue for updates and
     * withdrawals (L621). A read that failed, or found a later format, hands nothing on. The last one is kept for a
     * collector that comes later.
     */
    val reads: SharedFlow<PluginIndex> = handed.asSharedFlow()

    // The copy's body last read, the source it was read with, and its reading, so the same body isn't read again at each
    // step. Under [lock].
    private var lastBody: String? = null
    private var lastSource: PluginSource? = null
    private var lastReading: IndexReading? = null

    /** The Plugins page opened: a read, unless the list was read in the last 5 minutes. */
    fun pageOpened(): Job = launch(Trigger.Page)

    /** The page's Check again: a read now. */
    fun checkAgain(): Job = launch(Trigger.CheckAgain)

    /** MainActivity resumed: the day's read, while a plugin is installed, a day after the last attempt. */
    fun resumed(): Job = launch(Trigger.Daily)

    private fun launch(trigger: Trigger): Job = scope.launch(io) {
        if (trigger == Trigger.Daily && !installed()) return@launch
        lock.withLock { read(trigger) }
    }

    /** Under [lock]. */
    private suspend fun read(trigger: Trigger) {
        val copy = cache.read()
        val source = source()
        val time = now()
        val due = due(trigger, copy, time)
        if (trigger != Trigger.Daily || due) publish(copy, source, checking = due)
        if (!due) return
        val attempted = copy.copy(attemptedAt = time)
        val (after, answered) = try {
            outcome(fetch(source, copy), attempted, source, time)
        } catch (e: CancellationException) {
            publish(copy, source, checking = false)
            throw e
        }
        try {
            cache.write(after)
        } catch (e: IOException) {
            log.warn("Plugin list: its copy wasn't written: {}", e.javaClass.simpleName)
        }
        publish(after, source, checking = false)
        if (answered) shown.value.index?.let(handed::tryEmit)
    }

    /** One read of the list at [source], sending the copy's ETag while it has a body to keep. */
    private suspend fun fetch(source: PluginSource, copy: ListCopy): ListAnswer = try {
        reader(source).read(copy.etag?.takeIf { copy.body != null })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ListAnswer.Failed(ReadFailure.Network)
    }

    /**
     * Whether [trigger] reads now: never while GitHub's Retry-After holds (one more than a day ahead, after the clock went
     * back, holds nothing); the page 5 minutes after the last read that worked; Check again at once; the day's read a day
     * after the last attempt.
     */
    private fun due(trigger: Trigger, copy: ListCopy, time: Long): Boolean {
        if (copy.waitUntil?.let { it > time && it - time <= DAY_MS } == true) return false
        return when (trigger) {
            Trigger.Page -> !within(copy.readAt, time, PAGE_MS)
            Trigger.CheckAgain -> true
            Trigger.Daily -> !within(copy.attemptedAt, time, DAY_MS)
        }
    }

    // A time in the future (the clock went back) counts as long ago.
    private fun within(since: Long?, time: Long, span: Long) = since != null && time >= since && time - since < span

    /**
     * The copy after [answer], [attempted] at [time], and whether GitHub answered with the list as this build reads it: a
     * list, one older than the copy, a 304 for a copy with a body, or a 404.
     */
    private fun outcome(answer: ListAnswer, attempted: ListCopy, source: PluginSource, time: Long): Pair<ListCopy, Boolean> = when (answer) {
        is ListAnswer.Fresh -> when (val reading = PluginIndex.read(answer.body, source, attempted.generated?.let(::instant))) {
            is IndexReading.Listed -> {
                val dropped = reading.index.dropped.groupingBy { it }.eachCount()
                log.info("Plugin list read: {} plugins{}", reading.index.plugins.size, if (dropped.isEmpty()) "" else ", dropped $dropped")
                attempted.copy(body = answer.body.decodeToString(), etag = answer.etag, readAt = time, generated = reading.index.generated.toString(), waitUntil = null) to true
            }
            IndexReading.TooNew -> {
                log.info("Plugin list read: a later format")
                attempted.copy(body = answer.body.decodeToString(), etag = answer.etag, readAt = time, waitUntil = null) to false
            }
            IndexReading.Older -> {
                log.info("Plugin list read: older than the copy, ignored")
                attempted.copy(readAt = time, waitUntil = null) to true
            }
            is IndexReading.Refused -> {
                log.warn("Plugin list refused: {}", reading.fault)
                attempted to false
            }
        }
        ListAnswer.NotChanged -> if (attempted.body == null) attempted to false else attempted.copy(readAt = time, waitUntil = null) to true
        ListAnswer.NoList -> {
            log.info("Plugin list: none yet")
            attempted.copy(body = null, etag = null, readAt = time, waitUntil = null) to true
        }
        is ListAnswer.Busy -> {
            val wait = answer.retryAfter?.let { minOf(it, MAX_WAIT) }
            log.warn("Plugin list: HTTP {}{}", answer.status, wait?.let { ", waiting ${it.seconds} s" } ?: "")
            attempted.copy(waitUntil = wait?.let { time + it.toMillis() }) to false
        }
        is ListAnswer.Failed -> {
            log.warn("Plugin list not read: {}{}", answer.kind, answer.status?.let { " (HTTP $it)" } ?: "")
            attempted to false
        }
    }

    /** [copy] as the page shows it. Under [lock]. */
    private fun publish(copy: ListCopy, source: PluginSource, checking: Boolean) {
        val body = copy.body
        if (body != lastBody || source !== lastSource || lastReading == null && body != null) {
            lastBody = body
            lastSource = source
            lastReading = body?.let { PluginIndex.read(it.toByteArray(), source, accepted = null) }
        }
        val reading = lastReading
        shown.value = PluginListState(
            index = when {
                reading is IndexReading.Listed -> reading.index
                body == null && copy.readAt != null -> PluginIndex.EMPTY
                else -> null
            },
            tooNew = reading == IndexReading.TooNew,
            readAt = copy.readAt,
            failed = copy.attemptedAt != null && (copy.readAt == null || copy.attemptedAt > copy.readAt),
            checking = checking,
        )
    }

    private enum class Trigger { Page, CheckAgain, Daily }

    companion object {
        /** raw.githubusercontent.com's own max-age: the page reads no more often. */
        private const val PAGE_MS = 5 * 60 * 1000L
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private val MAX_WAIT: Duration = Duration.ofMillis(DAY_MS)

        private val log = LoggerFactory.getLogger(PluginList::class.java)

        /** The app's list: GitHub's (or a device test's) at each read, through [http], its copy beside the plugins. */
        fun of(filesDir: File, installed: suspend () -> Boolean, http: () -> HttpClient, scope: CoroutineScope): PluginList =
            PluginList(PluginSource::current, { source -> PluginListClient(source, http()) }, PluginListCache.of(filesDir), installed, scope)

        private fun instant(text: String): Instant? = try {
            Instant.parse(text)
        } catch (e: DateTimeParseException) {
            null
        }
    }
}

/**
 * The plugin list as the Plugins page shows it: the [index] as this build reads its copy (one with nothing in it after a
 * 404; null before any read, or while the copy is in a [tooNew] format); when GitHub last answered ([readAt], epoch
 * millis), "checked <when>"; whether the last read [failed], so the copy shown is from then; and whether one runs now
 * ([checking], "Checking…").
 */
class PluginListState(
    val index: PluginIndex? = null,
    val tooNew: Boolean = false,
    val readAt: Long? = null,
    val failed: Boolean = false,
    val checking: Boolean = false,
)
