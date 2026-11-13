package io.skjaere.usenet.app

import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.ProviderWithPassword
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Work on the app's scope outlives the screen that starts it, so a failure in it must not crash the app (#117). */
class AppScopeTest {
    @Test
    fun `a failure nothing handled is passed to the scope's handler, and the scope's other work goes on`() = runBlocking<Unit> {
        val handled = CompletableDeferred<Throwable>()
        val scope = AppScope(onFailure = { handled.complete(it) })
        try {
            // A save on leaving the player, on a full disk.
            scope.launch { throw IOException("No space left on device") }
            val failure = withTimeout(5_000) { handled.await() }
            assertEquals("No space left on device", failure.message)
            val next = scope.launch { }
            withTimeout(5_000) { next.join() }
            assertTrue(next.isCompleted && !next.isCancelled)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a save that fails is logged, not thrown`() = runBlocking<Unit> {
        saveOrLog("save progress") { throw IOException("No space left on device") }
    }

    @Test
    fun `a save that is cancelled stays cancelled`() = runBlocking<Unit> {
        assertFailsWith<CancellationException> { saveOrLog("save progress") { throw CancellationException("left") } }
    }

    /**
     * The start's reads for This device and the log header: a decoder probe that fails (a box's codec service gone)
     * still lets rapidyenc, the display's modes, the kind and the memory be read, and the header's lines be logged.
     */
    @Test
    fun `a start read that fails leaves the others to be read, and the header logged`() = runBlocking<Unit> {
        val read = mutableListOf<String>()
        var logged = false
        readDeviceFacts(
            listOf(
                "probe the decoders" to suspend { throw RuntimeException("cannot get MediaCodecList") },
                "read rapidyenc's facts" to suspend { read += "native" },
                "read the display's modes" to suspend { read += "modes" },
            ),
        ) { logged = true }
        assertEquals(listOf("native", "modes"), read)
        assertTrue(logged)
    }

    @Test
    fun `the start's reads stay cancelled`() = runBlocking<Unit> {
        var after = false
        assertFailsWith<CancellationException> {
            readDeviceFacts(listOf("probe the decoders" to suspend { throw CancellationException("stopped") }, "read rapidyenc's facts" to suspend { after = true })) {}
        }
        assertTrue(!after)
    }

    @Test
    fun `a change of providers that can't be applied doesn't end the sync`() = runBlocking<Unit> {
        val first = listOf(ProviderRecord("a", "News A", "news.a.example"))
        val second = first + ProviderRecord("b", "News B", "news.b.example", connections = 4)
        var reads = 0
        val configured = mutableListOf<EngineSettings>()
        syncProviders(
            saved = flowOf(first, second),
            usable = {
                // The first read of the passwords fails (the keystore busy, the disk full).
                if (reads++ == 0) throw IOException("Couldn't read the secrets file")
                second.map { ProviderWithPassword(it, "pw-${it.id}") }
            },
            configure = { configured += it },
        )
        val settings = configured.single()
        assertEquals(listOf("a" to 0, "b" to 1), settings.providers.map { it.id to it.priority })
        assertEquals(listOf("pw-a", "pw-b"), settings.providers.map { it.password })
        assertEquals(4, settings.providers[1].maxConnections)
    }

    /**
     * The engine held twelve segments ahead of the player whatever their size, and fetched four at once
     * whatever the provider allowed: it gets the device's read-ahead budget, and the provider's connections.
     */
    @Test
    fun `the engine gets the device's read-ahead budget and the provider's connections`() = runBlocking<Unit> {
        val provider = ProviderRecord("a", "News A", "news.a.example", connections = 8)
        val configured = mutableListOf<EngineSettings>()
        syncProviders(
            saved = flowOf(listOf(provider)),
            usable = { listOf(ProviderWithPassword(provider, "pw")) },
            configure = { configured += it },
            readAheadBytes = { 20L * 1024 * 1024 },
        )
        val settings = configured.single()
        assertEquals(20L * 1024 * 1024, settings.readAheadBytes)
        // Two of the provider's eight are kept spare.
        assertEquals(6, settings.streamConcurrency)
    }

    /**
     * The device's memory is read from the system before the providers are, and a failure there (the
     * system server gone) ended the sync: the engine was never given the providers, and nothing played.
     */
    @Test
    fun `a read-ahead budget that can't be read leaves the streams without a byte cap, and the providers are configured`() = runBlocking<Unit> {
        val provider = ProviderRecord("a", "News A", "news.a.example")
        val configured = mutableListOf<EngineSettings>()
        syncProviders(
            saved = flowOf(listOf(provider)),
            usable = { listOf(ProviderWithPassword(provider, "pw")) },
            configure = { configured += it },
            readAheadBytes = { throw RuntimeException("DeadSystemException") },
        )
        val settings = configured.single()
        assertEquals(listOf("a"), settings.providers.map { it.id })
        assertNull(settings.readAheadBytes)
        assertFailsWith<CancellationException> {
            syncProviders(flowOf(listOf(provider)), usable = { emptyList() }, configure = {}, readAheadBytes = { throw CancellationException("stopped") })
        }
    }

    /**
     * Opening the video cache walks its directory, seconds for a large one on slow storage, and the
     * providers were configured only after it: a play started meanwhile failed as if none were set up.
     */
    @Test
    fun `the providers are configured while the video cache is still opening`() = runBlocking<Unit> {
        val provider = ProviderRecord("a", "News A", "news.a.example")
        val configured = CompletableDeferred<EngineSettings>()
        val opening = CompletableDeferred<Unit>()
        val applied = mutableListOf<Pair<Boolean, Long>>()
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            scope.syncEngine(
                providers = flowOf(listOf(provider)),
                usable = { listOf(ProviderWithPassword(provider, "pw")) },
                configure = { configured.complete(it) },
                cache = flowOf(true to 2_000_000_000L),
                applyCache = { keep, limit -> applied += keep to limit; opening.await() },
            )
            assertEquals(listOf("a"), withTimeout(5_000) { configured.await() }.providers.map { it.id })
            assertEquals(listOf(true to 2_000_000_000L), applied)
        } finally {
            scope.cancel()
        }
    }

    /**
     * The engine's lock lines name who holds the lock and who waits for it (#273): the provider sync's configure and
     * the video cache sync's apply (which asks the engine to build again) go by their names.
     */
    @Test
    fun `the provider and video cache syncs run under their names`() = runBlocking<Unit> {
        val provider = ProviderRecord("a", "News A", "news.a.example")
        val configuredBy = CompletableDeferred<String?>()
        val appliedBy = CompletableDeferred<String?>()
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            scope.syncEngine(
                providers = flowOf(listOf(provider)),
                usable = { listOf(ProviderWithPassword(provider, "pw")) },
                configure = { configuredBy.complete(currentCoroutineContext()[CoroutineName]?.name) },
                cache = flowOf(true to 2_000_000_000L),
                applyCache = { _, _ -> appliedBy.complete(currentCoroutineContext()[CoroutineName]?.name) },
            )
            assertEquals("provider sync", withTimeout(5_000) { configuredBy.await() })
            assertEquals("video cache sync", withTimeout(5_000) { appliedBy.await() })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a sync that is cancelled stays cancelled`() = runBlocking<Unit> {
        val e = assertFailsWith<CancellationException> {
            syncProviders(flowOf(emptyList()), usable = { throw CancellationException("stopped") }, configure = {})
        }
        assertIs<CancellationException>(e)
    }
}
