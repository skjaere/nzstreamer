package io.skjaere.usenet.core

import io.ktor.network.selector.SelectorManager
import io.skjaere.nntp.NntpClient
import io.skjaere.nntp.NntpDispatchers
import io.skjaere.nzbstreamer.NzbStreamer
import io.skjaere.nzbstreamer.config.MissingArticlePolicy
import io.skjaere.nzbstreamer.config.StreamingConfig
import io.skjaere.nzbstreamer.stream.SegmentCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

sealed interface EngineState {
    data object NotConfigured : EngineState
    data object Connecting : EngineState
    data class Ready(val providers: List<String>) : EngineState
    data class Error(val error: UsenetError) : EngineState
}

/**
 * Owns the single [NzbStreamer] for the app. The streamer is (re)built on [ioDispatcher]
 * because its builder blocks while it sets up the selector and pools. Its streams, and the logins
 * to check several providers, run there too: by default ktor-nntp-client's own view of
 * Dispatchers.IO, where a connection waits for its server in place, and which IO's 64-thread
 * limit doesn't count, so those waits never keep the app's other IO work (files, the database) waiting. That opens no connection
 * (the pools connect on first use), so [EngineState.Ready] means built, not reachable;
 * [testProvider] is the check that really logs in. [factory] builds a streamer and [release]
 * closes one the engine is done with: seams a test can count builds and closes through.
 * [EngineState.Ready] names the providers the streamer uses.
 *
 * With several providers, each is logged in to ([probe], one throwaway connection) before the
 * streamer is built, and one whose login is rejected or that can't be reached (no connection, no
 * answer) is left out of it while another one serves ([leftOut], S9-1, #172). Every use would try a
 * rejected login again, and a provider may lock an account that keeps trying. One out of reach would
 * hold up every use for as long as its pool tries to connect (about 14 s for one refusing connections,
 * 20 s for one that doesn't answer) before nzb-streamer's article check and fetches move on to the
 * next provider (library issues N33, K4, K24).
 * One turned away for too many connections, or failing the check any other way, is kept: the check
 * and fetches move on from it at once (K5). A provider left out is tried again by building the
 * streamer again, once [RECHECK_INTERVAL] has passed ([UNREACHABLE_RECHECK_INTERVAL] for one out of
 * reach, as measured by [timeSource]) and nothing is using the streamer. So is every provider after a
 * use one of them failed ([recheckProviders]). [fallback] says whether another serves when one fails.
 *
 * A streamer replaced by [configure] while [withStreamer] callers use it (a play's article check,
 * an Import's prepare) stays open for them and is closed when the last one is done (S9-7): closing
 * it at once failed their work with "Pool closed". Streams it opened end when it closes; the player
 * opens them again on the new streamer.
 *
 * Every use takes one lock, on Dispatchers.Default whatever the caller's dispatcher, and lets go of it there before the
 * caller gets its result (#273: a release list's check held it across a hop back to a dispatcher that had stopped, and a
 * play waited behind it for good). Who holds it is known ([lockState]); a caller kept waiting for it is logged every
 * [lockWaitWarn] with who holds it (at most [LOCK_WAIT_LINES] times), and a build names the caller it is built for. None
 * of these lines names a provider's host.
 */
class UsenetEngine(
    private val factory: (EngineSettings) -> NzbStreamer = ::defaultStreamer,
    private val release: (NzbStreamer) -> Unit = { it.close() },
    private val ioDispatcher: CoroutineDispatcher = NntpDispatchers.IO,
    private val probe: suspend (ProviderConfig) -> UsenetError? = { probeProvider(it) },
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val lockWaitWarn: Duration = LOCK_WAIT_WARN,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(UsenetEngine::class.java)
    private val lock = Mutex()
    /** Set under [lock]; [streamConcurrency] reads it without, as [configured] is read. */
    @Volatile
    private var settings: EngineSettings? = null
    private var current: Built? = null
    /** When to build the streamer again: to see whether the providers left out of it serve again, or as [rebuildWhenIdle] asks; null otherwise. */
    private var rebuildAt: TimeMark? = null
    private val _state = MutableStateFlow<EngineState>(EngineState.NotConfigured)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /**
     * Who holds the engine's lock, as its lines name them ([Holder]); null while it is free. For a test's failure to say,
     * and a test to wait on.
     */
    val lockState: String?
        get() = holder?.toString() ?: if (lock.isLocked) "taken, its holder not named yet" else null

    /** Set right after [lock] is taken, cleared just before it is let go. */
    @Volatile
    private var holder: Holder? = null

    /**
     * Where a lock waiter's watch runs ([waitForLock]): the engine's, not the caller's, whose scope would wait for the
     * watch to end before the caller went on, one more hop back to the caller with the lock held.
     */
    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _configuredProviders = MutableStateFlow<List<String>>(emptyList())

    /**
     * The ids of the providers [configure] was last given, in the order articles are asked of
     * them; empty without any. Known as soon as they are configured, unlike [EngineState.Ready],
     * which waits for the first use and names hosts, which two accounts may share.
     */
    val configuredProviders: StateFlow<List<String>> = _configuredProviders.asStateFlow()
    private val _leftOut = MutableStateFlow<Map<String, UsenetError>>(emptyMap())

    /**
     * The configured providers left out of the streamer because their login was rejected or they
     * couldn't be reached, by id, with why; empty while every one is used, and until providers just
     * configured have been checked. While one is left out, an article the others lack may be on it: a
     * release that reads as incomplete isn't necessarily so.
     */
    val leftOut: StateFlow<Map<String, UsenetError>> = _leftOut.asStateFlow()

    /** The providers [configure] was last given, in order: what [inUse] names. */
    @Volatile
    private var configured: List<ProviderConfig> = emptyList()

    /**
     * The configured providers not left out ([leftOut]), in the order articles are asked of them, as
     * they are named now: those a use of the streamer asks. Every configured one until they have
     * been checked; none until any is configured.
     */
    val inUse: List<ProviderConfig> get() = _leftOut.value.let { out -> configured.filter { it.id !in out } }

    /**
     * How many segments a stream fetches at once over the providers [inUse] ([EngineSettings.streamConcurrency]), as the
     * streamer is built with them: what the player's File details call the stream's connections. Null while none is in use.
     */
    val streamConcurrency: Int?
        get() = inUse.takeIf { it.isNotEmpty() }?.let { providers -> settings?.copy(providers = providers)?.streamConcurrency }

    /**
     * [inUse] once the providers have been checked: those the next use of the streamer asks, known before that use. The
     * streamer is built as that use would build it (several providers are logged in to first), or built again once a
     * provider left out of it is due to be checked again and nothing uses it. So one that can't serve counts as left out
     * from the first ask after the providers are configured (as after the process starts), and one serving again counts
     * as in use before a use takes it back. As [inUse] while the streamer can't be built (none configured, the build
     * failing): the use fails then too, and says why.
     */
    suspend fun checkedInUse(): List<ProviderConfig> = locked("checkedInUse") {
        try {
            currentOrBuild()
        } catch (e: UsenetException) {
            log.debug("Providers not checked: {}", e.error)
        }
        inUse
    }

    private val _fallback = MutableStateFlow(false)

    /**
     * Whether a provider of the streamer's that fails a use (its login rejected since it was built, out
     * of reach) leaves another that serves: two or more are in use, one of which passed the check before
     * the build, and another passed it too or was only turned away for too many connections. An article
     * the others then lack may be on the one that failed: the release isn't known to be incomplete, and
     * the next one may be whole on them. False with one provider, and until the providers are checked.
     */
    val fallback: StateFlow<Boolean> = _fallback.asStateFlow()

    /**
     * Scope for long-lived stream writers. Writers are children of the coroutine that launches
     * them; one that fails (a missing article mid-stream) closes its channel with the cause,
     * which is how the reader learns of it, and doesn't fail that coroutine. One that fails to
     * start does, and its opener throws that to the caller too, so the handler only logs: without
     * it the same exception would reach the thread's uncaught handler and kill the app.
     */
    val sessionScope = CoroutineScope(
        SupervisorJob() + ioDispatcher + CoroutineExceptionHandler { _, e -> log.debug("Stream session ended: {}", e.toUsenetError()) },
    )

    /**
     * Applies new settings; the next [streamer] call builds against them. A change only to what the
     * providers are called keeps the streamer, and the connections it has open.
     */
    suspend fun configure(newSettings: EngineSettings) = locked("configure") {
        if (newSettings == settings) return@locked
        val rebuild = newSettings.builtFrom() != settings?.builtFrom()
        settings = newSettings
        configured = newSettings.orderedProviders
        if (!rebuild) return@locked
        _configuredProviders.value = newSettings.orderedProviders.map { it.id }
        _leftOut.value = emptyMap()
        _fallback.value = false
        rebuildAt = null
        _state.value = if (newSettings.orderedProviders.isEmpty()) EngineState.NotConfigured else EngineState.Connecting
        // Last: for a caller cancelled meanwhile, the release throws that cancellation when it returns.
        current?.let { old ->
            current = null
            replace(old)
        }
    }

    /**
     * The streamer for the configured providers, built on first use. A caller cancelled while it is
     * being built gets its cancellation back, not a failure, and leaves the engine as it found it:
     * what was built for it is closed rather than lost (#123).
     */
    suspend fun streamer(): NzbStreamer = locked("streamer") { currentOrBuild().streamer }

    /**
     * Runs [block] with the streamer. While it runs, and while a stream it opened in [sessionScope]
     * is open, the streamer is in use and isn't swapped for a new one to take back a provider that
     * was left out of it. One that [configure] replaced meanwhile is closed once [block] is done,
     * unless another caller still uses it.
     */
    suspend fun <T> withStreamer(block: suspend (NzbStreamer) -> T): T {
        // The use is counted under the lock. A caller cancelled before the streamer gets back to it undoes the count: else
        // a streamer replaced meanwhile would never be closed, and the engine, never idle, would never build again.
        var counted: Built? = null
        val built = try {
            locked("withStreamer") { currentOrBuild().also { it.users.incrementAndGet(); counted = it } }
        } catch (e: CancellationException) {
            counted?.let { doneWith(it) }
            throw e
        }
        return try {
            block(built.streamer)
        } finally {
            doneWith(built)
        }
    }

    /** A [withStreamer] use of [built] is over: one [replaced][Built.replaced] meanwhile is closed when it was the last. */
    private suspend fun doneWith(built: Built) {
        if (built.users.decrementAndGet() == 0 && built.replaced) withContext(NonCancellable + ioDispatcher) { built.close() }
    }

    /**
     * A use of the streamer failed as it does when one of its providers stopped serving since it was
     * built (its login rejected, out of reach): with several providers, it is built again, each one
     * checked first, at the next use while nothing else is using it.
     */
    suspend fun recheckProviders() = locked("recheckProviders") {
        if (current != null && (settings?.orderedProviders?.size ?: 0) > 1) rebuildAt = timeSource.markNow()
    }

    /**
     * Builds the streamer again at its next use while nothing uses it, for a change the settings don't
     * carry: a segment cache opened since it was built ([VideoCache]).
     */
    suspend fun rebuildWhenIdle() = locked("rebuildWhenIdle") {
        if (current != null) rebuildAt = timeSource.markNow()
    }

    /**
     * Runs [block] holding the engine's lock, which [site], the engine's method, takes for the caller: who holds it is
     * [holder] meanwhile, for [lockState] and the lines that name it.
     *
     * Taken, held and let go on Dispatchers.Default, never on the caller's dispatcher, which may stop running what is sent
     * to it (a composition's under the v2 Compose test rule, while the test blocks): the build, a release and the
     * providers' check come back to the section, not to the caller, and a waiter is handed the lock on Default. The caller
     * gets the result once the lock is let go; one already on Default isn't dispatched again. Not [ioDispatcher]: the
     * build's and a release's hops back must be real dispatches, so that a caller cancelled meanwhile gets its
     * cancellation and what was built is closed (#123), and the lock mustn't wait for an IO thread.
     */
    private suspend inline fun <T> locked(site: String, crossinline block: suspend () -> T): T {
        // As it asked: the lines name the caller's dispatcher, not Default.
        val caller = currentCoroutineContext()
        return withContext(Dispatchers.Default) {
            if (!lock.tryLock()) waitForLock(site, caller)
            try {
                holder = Holder(site, caller, Thread.currentThread(), TimeSource.Monotonic.markNow())
                block()
            } finally {
                holder = null
                lock.unlock()
            }
        }
    }

    /**
     * Takes [lock] for [site] once it is let go, saying every [lockWaitWarn] while the wait lasts who waits and who holds
     * it. A watch beside the wait, not a timeout around it: a timeout that fires as the lock is handed over can leave it
     * held by a caller that went on without it. On real time, as on Dispatchers.Default, so a test's virtual time doesn't
     * spin it.
     */
    private suspend fun waitForLock(site: String, caller: CoroutineContext) {
        val waiting = TimeSource.Monotonic.markNow()
        val watch = watchScope.launch {
            repeat(LOCK_WAIT_LINES) {
                delay(lockWaitWarn)
                log.warn(
                    "engine_lock: {} has waited {} s; held by {}; state {}",
                    describe(site, caller), waiting.elapsedNow().inWholeSeconds, lockState ?: "no one now", _state.value.summary,
                )
            }
        }
        try {
            lock.lock()
        } finally {
            watch.cancel()
        }
    }

    /**
     * Who holds the lock: the engine's method that took it ([site]) for which caller (its coroutine's name and
     * dispatcher, as it asked), on which [thread], [since] when. The caller's own references are kept, and words made
     * of them only when a line says them.
     */
    private class Holder(val site: String, val caller: CoroutineContext, val thread: Thread, val since: TimeSource.Monotonic.ValueTimeMark) {
        /** For a build's line: who, without how long. */
        val who: String get() = "${describe(site, caller)}, thread ${thread.name}"

        override fun toString() = "$who, for ${since.elapsedNow().inWholeSeconds} s"
    }

    private fun idle(built: Built) = built.users.get() == 0 && sessionScope.coroutineContext.job.children.none()

    /**
     * A streamer and the [withStreamer] callers using it. One [replaced] is closed when the last of
     * them is done, or at once without any: whichever sees the other's change closes it, once.
     */
    private inner class Built(val streamer: NzbStreamer) {
        val users = AtomicInteger()
        @Volatile
        var replaced = false
        private val closed = AtomicBoolean()

        fun close() {
            if (closed.compareAndSet(false, true)) runCatching { this@UsenetEngine.release(streamer) }
        }
    }

    /** No longer the current streamer: closed now, or by the last caller still using it. */
    private suspend fun replace(old: Built) {
        old.replaced = true
        if (old.users.get() == 0) withContext(NonCancellable + ioDispatcher) { old.close() }
    }

    private suspend fun currentOrBuild(): Built {
        current?.let { built ->
            if (rebuildAt?.hasPassedNow() != true || !idle(built)) return built
            log.info("Building the streamer again")
            current = null
            rebuildAt = null
            _state.value = EngineState.Connecting
            replace(built)
        }
        val s = settings?.takeIf { it.orderedProviders.isNotEmpty() }
            ?: throw UsenetException(UsenetError.NotConfigured)
        val before = _state.value
        _state.value = EngineState.Connecting
        val count = s.orderedProviders.size
        log.info("Building the streamer for {} provider{}, for {}", count, if (count == 1) "" else "s", holder?.who ?: "a caller not named")
        val checked = try {
            checkProviders(s.orderedProviders)
        } catch (e: CancellationException) {
            _state.value = before
            throw e
        }
        val serving = s.copy(providers = s.orderedProviders.filter { it.id !in checked.leftOut })
        // What the build returned, also when withContext drops it: it does, for a caller cancelled
        // before the result gets back to it, and the streamer would be left open.
        var returned: NzbStreamer? = null
        val built = try {
            // The build blocks, so it runs to the end either way. withContext returns one that was over before the caller
            // suspended for it even to a caller cancelled meanwhile (backlog 337), so the cancellation is checked after it.
            withContext(NonCancellable + ioDispatcher) { factory(serving).also { returned = it } }
                .also { currentCoroutineContext().ensureActive() }
        } catch (e: CancellationException) {
            // The state first: the release throws the caller's cancellation again when it returns.
            _state.value = before
            returned?.let { withContext(NonCancellable + ioDispatcher) { runCatching { release(it) } } }
            throw e
        } catch (e: Exception) {
            val error = e.toUsenetError()
            log.warn("Failed to start usenet engine: {}", error)
            // Nothing built, nothing left out of it.
            _leftOut.value = emptyMap()
            _fallback.value = false
            _state.value = EngineState.Error(error)
            throw UsenetException(error, e)
        }
        rebuildAt = checked.leftOut.values
            .minOfOrNull { error -> if (error is UsenetError.AuthFailed) RECHECK_INTERVAL else UNREACHABLE_RECHECK_INTERVAL }
            ?.let { timeSource.markNow() + it }
        _leftOut.value = checked.leftOut
        _fallback.value = checked.fallback
        _state.value = EngineState.Ready(serving.orderedProviders.map { p -> "${p.host}:${p.port}" })
        return Built(built).also { current = it }
    }

    /** What the check before a build found: the providers to leave out, with why, and whether one failing leaves another ([fallback]). */
    private class Checked(val leftOut: Map<String, UsenetError>, val fallback: Boolean)

    /**
     * Logs in to each of several [providers] at once. Those whose login was rejected or that couldn't be
     * reached are left out, unless every one is. One alone isn't checked: there is nothing to fall back
     * to, and its failure says what is wrong when it is used.
     */
    private suspend fun checkProviders(providers: List<ProviderConfig>): Checked {
        if (providers.size < 2) return Checked(emptyMap(), fallback = false)
        val failures = withContext(ioDispatcher) {
            coroutineScope { providers.map { p -> async { p.id to probe(p) } }.awaitAll() }
        }
        val failing = failures.mapNotNull { (id, error) -> error?.takeIf { it.leavesOut }?.let { id to it } }.toMap()
        val leftOut = if (failing.size == providers.size) emptyMap() else failing
        leftOut.forEach { (id, error) -> log.warn("Leaving provider {} out for now: {}", id, error) }
        val passed = failures.count { (_, error) -> error == null }
        val busy = failures.count { (_, error) -> error?.tooManyConnections == true }
        return Checked(leftOut, fallback = passed >= 1 && passed + busy >= 2)
    }

    /** Whether a provider whose check failed with this is left out: its login rejected, or out of reach; not one that is only busy. */
    private val UsenetError.leavesOut: Boolean
        get() = this is UsenetError.AuthFailed || this is UsenetError.Timeout || (this is UsenetError.ConnectionFailed && !tooManyConnections)

    override fun close() {
        sessionScope.cancel()
        watchScope.cancel()
        current?.close()
        current = null
        _leftOut.value = emptyMap()
        _fallback.value = false
    }

    companion object {
        /**
         * A streamer for [settings], lent [segmentCache] (the process's one, [VideoCache]): its close
         * leaves the cache open for the next streamer.
         */
        fun defaultStreamer(settings: EngineSettings, segmentCache: SegmentCache? = null): NzbStreamer = NzbStreamer.fromConfig(
            nntpConfigs = settings.orderedProviders.map { it.toNntpConfig() },
            streamingConfig = StreamingConfig(
                concurrency = settings.streamConcurrency,
                // Availability checks are small STATs: use every connection the providers allow, up
                // to 32. Not kept to what a stream fetches at once: that may be more than 32.
                verificationConcurrency = settings.orderedProviders.sumOf { it.maxConnections }.coerceIn(1, 32),
                readAheadSegments = settings.streamReadAhead,
                segmentFetchTimeoutMs = settings.segmentFetchTimeoutMs,
                readAheadBytes = settings.readAheadBytes,
                // An article no provider has doesn't end a stream: zeros of its size take its place, and the stream says
                // where (OpenedStream.gaps), so the player stops in front of them and skips the stretch rather than stop.
                // What prepare reads (first articles, archive headers) and the availability check stay strict.
                missingArticles = MissingArticlePolicy.ZERO_FILL,
                poolTuning = settings.poolTuning,
                segmentTimeoutRetries = settings.segmentTimeoutRetries,
            ),
            segmentCache = segmentCache,
            closeSegmentCache = false,
        )

        /**
         * Logs in to [config] over one throwaway connection, for the provider screen's Test.
         * A built streamer proves nothing (its pools connect on first use, and retry a rejected
         * login for a while), so this opens a client directly. After the login it STATs a made-up
         * article, which should come back 430: a server may accept AUTHINFO and refuse an expired
         * account only on use. Returns null when all of that worked.
         */
        suspend fun testProvider(config: ProviderConfig, timeoutMs: Long = PROVIDER_TEST_TIMEOUT_MS): UsenetError? =
            withContext(NntpDispatchers.IO) {
                SelectorManager(Dispatchers.IO).use { selector ->
                    try {
                        withTimeout(timeoutMs) {
                            val client = openClient(config, selector)
                            try {
                                // Like the pools, skip AUTHINFO when no credentials are set.
                                if (config.username.isNotEmpty() && config.password.isNotEmpty()) {
                                    client.authenticate(config.username, config.password)
                                }
                                client.stat(PROBE_MESSAGE_ID)
                            } finally {
                                client.shutdown()
                            }
                        }
                        null
                    } catch (e: TimeoutCancellationException) {
                        UsenetError.Timeout("No answer from ${config.host}:${config.port} within $timeoutMs ms")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.toUsenetError()
                    }
                }
            }

        /** [testProvider] as the engine checks each of several providers before it uses them. */
        suspend fun probeProvider(config: ProviderConfig): UsenetError? = testProvider(config, PROBE_TIMEOUT_MS)

        /**
         * Opens the socket (and TLS) and reads the greeting; failing to get that far is a connection
         * failure, which ktor-nntp-client reports as one (NntpConnectionException), a server that
         * doesn't speak TLS (TLS set for a plain port) among them.
         */
        private suspend fun openClient(config: ProviderConfig, selector: SelectorManager): NntpClient =
            NntpClient.connect(config.host, config.port, selector, config.useTls)

        private const val PROVIDER_TEST_TIMEOUT_MS = 20_000L
        private const val PROBE_TIMEOUT_MS = 10_000L
        private const val PROBE_MESSAGE_ID = "<provider-test@usenet-streamer.invalid>"

        /** How long a provider whose login the check before a build rejected is left out before it is checked again. */
        val RECHECK_INTERVAL = 15.minutes

        /**
         * How long one the check couldn't reach is left out before it is checked again: it may be back
         * in a moment (a restart, a dropped route), and a release only it has whole reads as incomplete meanwhile.
         */
        val UNREACHABLE_RECHECK_INTERVAL = 2.minutes

        /**
         * How long a caller waits for the lock before it is logged with who holds it, and between lines while it waits:
         * longer than a use holds it (a build with the check of several providers takes up to [PROBE_TIMEOUT_MS]).
         */
        val LOCK_WAIT_WARN = 20.seconds

        /** At most this many lines per wait: ten minutes' worth at [LOCK_WAIT_WARN]. */
        private const val LOCK_WAIT_LINES = 30

        /** A caller of [site] as the lock's lines name it: its coroutine's name, else that it has none, and its dispatcher. */
        private fun describe(site: String, caller: CoroutineContext) =
            "$site from ${caller[CoroutineName]?.name ?: "an unnamed caller"} on ${caller[ContinuationInterceptor] ?: "no dispatcher"}"
    }
}

/**
 * The state as the engine's lines say it: without the hosts [EngineState.Ready] names, or an error's detail, which may
 * name one.
 */
val EngineState.summary: String
    get() = when (this) {
        EngineState.NotConfigured -> "NotConfigured"
        EngineState.Connecting -> "Connecting"
        is EngineState.Ready -> "Ready with ${providers.size} provider${if (providers.size == 1) "" else "s"}"
        is EngineState.Error -> "Error (${error::class.simpleName})"
    }
