package io.skjaere.usenet.core

import io.skjaere.nntp.ArticleNotFoundException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.measureTimedValue

class UsenetEngineTest {
    private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

    @AfterTest
    fun restore() = Thread.setDefaultUncaughtExceptionHandler(previousHandler)

    @Test
    fun `a failing stream writer does not crash the process`() {
        // A missing article fails nzb-streamer's writer, a child of a coroutine in sessionScope.
        // The reader already gets the error through the channel; it must not also reach the
        // thread's uncaught-exception handler, which on Android kills the app.
        val uncaught = mutableListOf<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> synchronized(uncaught) { uncaught += e } }
        UsenetEngine().use { engine ->
            runBlocking { engine.sessionScope.launch { throw ArticleNotFoundException("<x@y> not found") }.join() }
        }
        assertEquals(emptyList(), synchronized(uncaught) { uncaught.toList() })
    }

    @Test
    fun `the engine lists the providers it was given, in the order they are tried`() = runBlocking<Unit> {
        UsenetEngine().use { engine ->
            assertEquals(emptyList(), engine.configuredProviders.value)
            val p = ProviderConfig("p", "p", "news.example.net", 563, "", "", useTls = true)
            engine.configure(EngineSettings(listOf(p.copy(id = "b", priority = 1), p.copy(id = "a", priority = 0), p.copy(id = "off", priority = 2, enabled = false))))
            assertEquals(listOf("a", "b"), engine.configuredProviders.value)
            engine.configure(EngineSettings(emptyList()))
            assertEquals(emptyList(), engine.configuredProviders.value)
        }
    }

    @Test
    fun `the streamer is built as the settings say`() {
        val providers = listOf(1001, 1002).mapIndexed { i, port -> ProviderConfig("p$i", "p$i", "127.0.0.1", port, "u", "p", useTls = false, maxConnections = 3, priority = i) }
        val settings = EngineSettings(providers, concurrency = 2, readAheadSegments = 5, segmentFetchTimeoutMs = 1_234)
        UsenetEngine.defaultStreamer(settings).use { streamer ->
            assertEquals(1_234, streamer.getSegmentFetchTimeoutMs())
            assertEquals(2, streamer.getStreamingConcurrency())
            assertEquals(5, streamer.getReadAheadSegments())
            assertEquals(providers.map { it.toNntpConfig() }, streamer.getPoolConfigs())
        }
    }

    /**
     * Streams fetched four segments at once whatever the providers allowed: eight connections set up
     * for a provider, and a 4K remux at 2.65 MB/s a connection came in under its bitrate.
     */
    @Test
    fun `a stream fetches as many segments at once as the first provider in use allows`() {
        val first = ProviderConfig("a", "a", "127.0.0.1", 1001, "u", "p", useTls = false, maxConnections = 8, priority = 0)
        val second = ProviderConfig("b", "b", "127.0.0.1", 1002, "u", "p", useTls = false, maxConnections = 3, priority = 1)
        val settings = EngineSettings(listOf(second, first))
        UsenetEngine.defaultStreamer(settings).use { streamer ->
            assertEquals(6, streamer.getStreamingConcurrency())
            assertEquals(18, streamer.getReadAheadSegments())
            assertNull(streamer.getReadAheadBytes())
        }
        // As the engine builds it with the first one left out (S9-1), or with it switched off.
        listOf(settings.copy(providers = listOf(second)), EngineSettings(listOf(first.copy(enabled = false), second))).forEach { without ->
            UsenetEngine.defaultStreamer(without).use { assertEquals(2, it.getStreamingConcurrency()) }
        }
    }

    /**
     * A stream fetched on every connection of the first provider: none was left for a seek's new
     * stream while the old one's fetches were read off theirs, for a retry, or for another app on the
     * account, and on a link slower than them all a whole wave of segments ran out of time together.
     */
    @Test
    fun `a stream keeps connections spare, and fetches eight segments at once at most`() {
        val provider = ProviderConfig("a", "a", "127.0.0.1", 1001, "u", "p", useTls = false)
        mapOf(1 to 1, 2 to 1, 3 to 2, 4 to 3, 5 to 4, 6 to 4, 7 to 5, 8 to 6, 9 to 7, 10 to 8, 50 to 8, 100 to 8).forEach { (connections, atOnce) ->
            assertEquals(atOnce, EngineSettings(listOf(provider.copy(maxConnections = connections))).streamConcurrency, "$connections connections")
        }
        // Unless a concurrency is set.
        assertEquals(12, EngineSettings(listOf(provider.copy(maxConnections = 50)), concurrency = 12).streamConcurrency)
    }

    /**
     * The provider screen allows up to 100 connections, and the availability check's 32 at most was
     * also kept at least what a stream fetches at once: with more than 32 on the first provider in
     * use, building the streamer threw, and nothing could be played.
     */
    @Test
    fun `a first provider with more connections than the availability check uses still builds`() {
        val provider = ProviderConfig("a", "a", "127.0.0.1", 1001, "u", "p", useTls = false, maxConnections = 50)
        UsenetEngine.defaultStreamer(EngineSettings(listOf(provider))).use { streamer ->
            assertEquals(32, streamer.getVerificationConcurrency())
        }
        // A concurrency set larger than every provider's connections together builds too.
        UsenetEngine.defaultStreamer(EngineSettings(listOf(provider.copy(maxConnections = 4)), concurrency = 40)).use { streamer ->
            assertEquals(4, streamer.getVerificationConcurrency())
        }
    }

    @Test
    fun `a stream holds no more read-ahead than the settings' bytes`() {
        val provider = ProviderConfig("a", "a", "127.0.0.1", 1001, "u", "p", useTls = false, maxConnections = 8)
        UsenetEngine.defaultStreamer(EngineSettings(listOf(provider), readAheadBytes = 48L * 1024 * 1024)).use { streamer ->
            assertEquals(48L * 1024 * 1024, streamer.getReadAheadBytes())
        }
    }

    // With several providers, the engine logs in to each before it builds its streamer and leaves
    // out one whose login is rejected or that can't be reached while another serves (S9-1, #172):
    // anything else the check runs into passes, as nzb-streamer's article check moves on from a
    // provider that fails it. These go through its seams: the streamers it builds open no connection.

    /**
     * An engine whose [probe] fails the providers in [failing] as it says; [built] gets the providers of each streamer it
     * builds, [released] counts closes. While [failBuild], building one fails.
     */
    private class Providers(timeSource: TestTimeSource = TestTimeSource()) {
        val failing = mutableMapOf<String, UsenetError>()
        val probed = mutableListOf<String>()
        val built = mutableListOf<List<String>>()
        var released = 0
        var failBuild = false
        val engine = UsenetEngine(
            factory = { s -> check(!failBuild) { "no streamer" }; built += s.orderedProviders.map { it.id }; UsenetEngine.defaultStreamer(s) },
            release = { released++; it.close() },
            probe = { p -> synchronized(probed) { probed += p.id }; failing[p.id] },
            timeSource = timeSource,
        )

        /** Configures the providers [ids], tried in that order, each called what [name] says. */
        suspend fun configure(vararg ids: String, name: (String) -> String = { it }) =
            engine.configure(EngineSettings(ids.mapIndexed { i, id -> ProviderConfig(id, name(id), "127.0.0.1", 1001 + i, "u", "p", useTls = false, priority = i) }))
    }

    private val rejected = UsenetError.AuthFailed("AUTHINFO PASS failed: 481 Authentication failed")
    private val busy = UsenetError.ConnectionFailed("AUTHINFO PASS failed: 502 Too many connections")
    private val refused = UsenetError.ConnectionFailed("Could not connect to 127.0.0.1:1002: Connection refused")

    /**
     * A provider out of reach would hold up every use of it for as long as its pool tries to connect (about 14 s for one
     * refusing connections, library issue K24) before nzb-streamer moves on: it is left out too (#172). One turned away for too many connections is up,
     * and an account refused only on use (an article STAT turned away) is moved past at once: both are kept.
     */
    @Test
    fun `of several providers, those whose login is rejected or that can't be reached are left out of the streamer, and say why`() = runBlocking<Unit> {
        Providers().run {
            engine.use {
                val timeout = UsenetError.Timeout("No answer from 127.0.0.1:1002 within 10000 ms")
                failing += mapOf(
                    "a" to rejected,
                    "b" to timeout,
                    "c" to busy,
                    "d" to refused,
                    "f" to UsenetError.Protocol("Command failed: 502 access denied"),
                )
                configure("a", "b", "c", "d", "e", "f")
                engine.streamer()
                assertEquals(listOf("a", "b", "c", "d", "e", "f"), probed.sorted())
                assertEquals(listOf(listOf("c", "e", "f")), built)
                assertEquals(mapOf("a" to rejected, "b" to timeout, "d" to refused), engine.leftOut.value)
                assertEquals(EngineState.Ready(listOf(1003, 1005, 1006).map { "127.0.0.1:$it" }), engine.state.value)
                // What it was given is still what it lists.
                assertEquals(listOf("a", "b", "c", "d", "e", "f"), engine.configuredProviders.value)
                // New providers haven't been checked: none is left out until they are.
                configure("b", "a")
                assertEquals(emptyMap(), engine.leftOut.value)
            }
        }
    }

    /**
     * The providers a use of the streamer asks: the configured ones less those left out, in order, as they are named now.
     * Every configured one until they have been checked; none before any is configured.
     */
    @Test
    fun `the providers in use are the configured ones not left out`() = runBlocking<Unit> {
        Providers().run {
            engine.use {
                assertEquals(emptyList(), engine.inUse.map { it.id })
                failing += mapOf("b" to rejected)
                configure("a", "b", "c")
                assertEquals(listOf("a", "b", "c"), engine.inUse.map { it.id })
                engine.streamer()
                assertEquals(listOf("a", "c"), engine.inUse.map { it.id })
                assertEquals(listOf("127.0.0.1:1001", "127.0.0.1:1003"), engine.inUse.map { "${it.host}:${it.port}" })
                // A new name keeps the streamer, and is the name in use.
                configure("a", "b", "c") { "Provider $it" }
                assertEquals(listOf("Provider a", "Provider c"), engine.inUse.map { it.name })
            }
        }
    }

    /**
     * File details' "N connections": how many segments a stream fetches at once over the providers in use, as the streamer
     * is built: the first one's connections less those kept spare, or the settings' own number. None without a provider.
     */
    @Test
    fun `a stream's connections are those the streamer is built with, from the first provider in use`() = runBlocking<Unit> {
        Providers().run {
            engine.use {
                assertNull(engine.streamConcurrency)
                failing += mapOf("a" to rejected)
                val providers = listOf(
                    ProviderConfig("a", "a", "127.0.0.1", 1001, "u", "p", useTls = false, maxConnections = 8, priority = 0),
                    ProviderConfig("b", "b", "127.0.0.1", 1002, "u", "p", useTls = false, maxConnections = 4, priority = 1),
                )
                engine.configure(EngineSettings(providers))
                // Until they are checked every one is in use: the first's eight, two kept spare.
                assertEquals(6, engine.streamConcurrency)
                engine.streamer()
                // The first left out: the second's four, one kept spare.
                assertEquals(3, engine.streamConcurrency)
                engine.configure(EngineSettings(providers, concurrency = 2))
                assertEquals(2, engine.streamConcurrency)
                engine.configure(EngineSettings(emptyList()))
                assertNull(engine.streamConcurrency)
            }
        }
    }

    /**
     * What the next use asks, known before that use (TR-11): the providers checked as it would check them. One that can't
     * serve is left out from the first ask after the providers are configured (as after the process starts), not only once a
     * use has built the streamer; one serving again is back once its recheck is due, before any use. Without a streamer to
     * build, what is configured: the use fails as well.
     */
    @Test
    fun `the providers in use once checked are those the next use asks, checked first when they haven't been`() = runBlocking<Unit> {
        val time = TestTimeSource()
        Providers(time).run {
            engine.use {
                assertEquals(emptyList(), engine.checkedInUse())
                failing += mapOf("b" to rejected)
                configure("a", "b", "c")
                assertEquals(listOf("a", "c"), engine.checkedInUse().map { it.id })
                assertEquals(listOf(listOf("a", "c")), built)
                // The next use is on the streamer that check built.
                engine.streamer()
                assertEquals(listOf("a", "c"), engine.checkedInUse().map { it.id })
                assertEquals(1, built.size)
                // b's account renewed: back once its recheck is due, without a use.
                failing.clear()
                time += UsenetEngine.RECHECK_INTERVAL
                assertEquals(listOf("a", "b", "c"), engine.checkedInUse().map { it.id })
                assertEquals(listOf(listOf("a", "c"), listOf("a", "b", "c")), built)
                // Nothing built: what is configured, and the use is the one to say why.
                failBuild = true
                configure("a", "b")
                assertEquals(listOf("a", "b"), engine.checkedInUse().map { it.id })
                assertIs<EngineState.Error>(engine.state.value)
            }
        }
    }

    @Test
    fun `a provider alone isn't checked, and when every one fails the check all are kept`() = runBlocking<Unit> {
        Providers().run {
            engine.use {
                failing += mapOf("a" to rejected, "b" to refused)
                configure("a")
                engine.streamer()
                assertEquals(emptyList(), probed)
                configure("a", "b")
                engine.streamer()
                assertEquals(listOf(listOf("a"), listOf("a", "b")), built)
                assertEquals(emptyMap(), engine.leftOut.value)
            }
        }
    }

    /**
     * Whether a provider failing a use leaves another that serves: then an article the others lack may be on the one that
     * failed, which fails the release it was found in, not every release (#172). So: two or more in use, one of which passed
     * the check before the build, and another passed too or was only turned away for too many connections.
     */
    @Test
    fun `another provider serves when one fails while two in use passed the check, or one did and another was only busy`() = runBlocking<Unit> {
        Providers().run {
            engine.use {
                suspend fun fallback(vararg ids: String, failures: Map<String, UsenetError> = emptyMap()): Boolean {
                    failing.clear()
                    failing += failures
                    configure(*ids)
                    // Not known before they are checked.
                    assertFalse(engine.fallback.value)
                    engine.streamer()
                    return engine.fallback.value
                }
                assertFalse(fallback("a"))
                assertTrue(fallback("a", "b"))
                assertTrue(fallback("a", "c", failures = mapOf("a" to busy)))
                assertTrue(fallback("a", "b", "c", failures = mapOf("a" to rejected)))
                // Every one busy, or one left out and one alone in use, or every one failing and kept.
                assertFalse(fallback("b", "c", failures = mapOf("b" to busy, "c" to busy)))
                assertFalse(fallback("a", "d", failures = mapOf("a" to rejected)))
                assertFalse(fallback("b", "d", failures = mapOf("b" to refused, "d" to refused)))
                assertFalse(fallback("c", "d", failures = mapOf("c" to busy, "d" to refused)))
                // Nor once the engine is closed.
                assertTrue(fallback("e", "f"))
                engine.close()
                assertFalse(engine.fallback.value)
            }
        }
    }

    @Test
    fun `a provider left out is taken back once the recheck interval has passed and nothing uses the streamer`() = runBlocking<Unit> {
        val time = TestTimeSource()
        Providers(time).run {
            engine.use {
                failing["a"] = rejected
                configure("a", "b")
                val first = engine.streamer()
                failing.clear()
                time += UsenetEngine.RECHECK_INTERVAL - 1.seconds
                assertSame(first, engine.streamer())
                // Not while it is in use, nor while a stream is open.
                engine.withStreamer {
                    time += 1.seconds
                    assertSame(first, engine.streamer())
                }
                val stream = engine.sessionScope.launch { awaitCancellation() }
                assertSame(first, engine.streamer())
                stream.cancelAndJoin()
                assertEquals(0, released)
                assertEquals(mapOf("a" to rejected), engine.leftOut.value)

                val second = engine.streamer()
                assertNotSame(first, second)
                assertEquals(1, released)
                assertEquals(listOf(listOf("b"), listOf("a", "b")), built)
                assertEquals(emptyMap(), engine.leftOut.value)
                // With every one serving, nothing is checked again.
                time += UsenetEngine.RECHECK_INTERVAL * 2
                assertSame(second, engine.streamer())
            }
        }
    }

    /** One that couldn't be reached may be back in a moment (a restart, a dropped route): it is checked again sooner. */
    @Test
    fun `a provider left out for being out of reach is checked again sooner`() = runBlocking<Unit> {
        val time = TestTimeSource()
        Providers(time).run {
            engine.use {
                failing += mapOf("a" to rejected, "b" to refused)
                configure("a", "b", "c")
                val first = engine.streamer()
                assertTrue(UsenetEngine.UNREACHABLE_RECHECK_INTERVAL < UsenetEngine.RECHECK_INTERVAL)
                time += UsenetEngine.UNREACHABLE_RECHECK_INTERVAL - 1.seconds
                assertSame(first, engine.streamer())
                failing.remove("b")
                time += 1.seconds
                assertNotSame(first, engine.streamer())
                assertEquals(listOf(listOf("c"), listOf("b", "c")), built)
                assertEquals(mapOf("a" to rejected), engine.leftOut.value)
            }
        }
    }

    /**
     * A provider that passed the check may stop serving later (an account that ran out, a server gone down, #173):
     * a use it fails has the providers checked again before the next use, which leaves it out.
     */
    @Test
    fun `after a provider fails a use, the next use while nothing else uses the streamer checks them all again`() = runBlocking<Unit> {
        Providers().run {
            engine.use {
                configure("a", "b")
                val first = engine.streamer()
                assertTrue(engine.fallback.value)
                failing["a"] = rejected
                engine.withStreamer {
                    engine.recheckProviders()
                    // Not while it is in use.
                    assertSame(first, engine.streamer())
                }
                assertNotSame(first, engine.streamer())
                assertEquals(listOf(listOf("a", "b"), listOf("b")), built)
                assertEquals(mapOf("a" to rejected), engine.leftOut.value)
                assertFalse(engine.fallback.value)

                // One alone is never checked, so a failure doesn't rebuild.
                configure("a")
                val alone = engine.streamer()
                engine.recheckProviders()
                assertSame(alone, engine.streamer())
            }
        }
    }

    /** A segment cache opened since the streamer was built (Settings › Cache turned on) reaches it this way. */
    @Test
    fun `asked to, the engine builds the streamer again at its next use while nothing uses it`() = runBlocking<Unit> {
        Providers().run {
            engine.use {
                // Nothing built yet, nothing to build again.
                engine.rebuildWhenIdle()
                configure("a")
                val first = engine.streamer()
                engine.withStreamer {
                    engine.rebuildWhenIdle()
                    assertSame(first, engine.streamer())
                }
                val stream = engine.sessionScope.launch { awaitCancellation() }
                assertSame(first, engine.streamer())
                stream.cancelAndJoin()
                val second = engine.streamer()
                assertNotSame(first, second)
                assertEquals(1, released)
                assertSame(second, engine.streamer())
                assertEquals(listOf(listOf("a"), listOf("a")), built)
            }
        }
    }

    @Test
    fun `providers left out of a streamer that failed to build, or of a closed engine, are left out of nothing`() = runBlocking<Unit> {
        Providers().run {
            engine.use {
                failing["a"] = rejected
                configure("a", "b")
                engine.streamer()
                assertEquals(setOf("a"), engine.leftOut.value.keys)
                failBuild = true
                engine.recheckProviders()
                assertFailsWith<UsenetException> { engine.streamer() }
                assertIs<EngineState.Error>(engine.state.value)
                assertEquals(emptyMap(), engine.leftOut.value)

                failBuild = false
                engine.streamer()
                assertEquals(setOf("a"), engine.leftOut.value.keys)
                engine.close()
                assertEquals(emptyMap(), engine.leftOut.value)
            }
        }
    }

    // S9-7: every change to the saved providers reconfigures the engine at once, also while a play's
    // article check or an Import's prepare is using the streamer.

    @Test
    fun `a streamer replaced while in use is closed once the last use of it is done`() = runBlocking<Unit> {
        Providers().run {
            engine.use {
                configure("a", "b")
                engine.withStreamer { first ->
                    configure("a")
                    assertEquals(0, released, "closed while still in use")
                    // Later uses get one built for the new providers.
                    engine.withStreamer { second -> assertNotSame(first, second) }
                    assertEquals(listOf(listOf("a", "b"), listOf("a")), built)
                    assertEquals(0, released)
                }
                assertEquals(1, released)
                // One not in use is closed at once.
                engine.streamer()
                configure("b")
                assertEquals(2, released)
            }
        }
    }

    @Test
    fun `renaming a provider keeps the streamer and its connections`() = runBlocking<Unit> {
        Providers().run {
            engine.use {
                configure("a", "b")
                val first = engine.streamer()
                configure("a", "b") { "Renamed $it" }
                assertSame(first, engine.streamer())
                assertEquals(0, released)
                assertEquals(1, built.size)
            }
        }
    }

    // The provider screen's Test runs testProvider; building a streamer opens no connection,
    // so these need a server on the other end to prove a real login happens.

    @Test
    fun `provider test logs in, checks an article and quits`() = runBlocking<Unit> {
        FakeNntpServer(password = "secret").use { server ->
            assertNull(UsenetEngine.testProvider(server.provider(password = "secret"), timeoutMs = 5_000))
            server.awaitDisconnect()
            assertEquals(
                listOf("AUTHINFO USER user", "AUTHINFO PASS secret", "STAT", "QUIT"),
                server.commands.map { it.substringBefore(" <") },
            )
        }
    }

    @Test
    fun `provider test reports a rejected password as AuthFailed`() = runBlocking<Unit> {
        FakeNntpServer(password = "secret").use { server ->
            assertIs<UsenetError.AuthFailed>(UsenetEngine.testProvider(server.provider(password = "wrong"), timeoutMs = 5_000))
            server.awaitDisconnect()
        }
    }

    @Test
    fun `provider test reports nothing listening as ConnectionFailed`() = runBlocking<Unit> {
        val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val config = ProviderConfig("closed", "closed", "127.0.0.1", closedPort, "user", "secret", useTls = false)
        assertIs<UsenetError.ConnectionFailed>(UsenetEngine.testProvider(config, timeoutMs = 5_000))
    }

    @Test
    fun `provider test reports a host that doesn't resolve as ConnectionFailed`() = runBlocking<Unit> {
        val config = ProviderConfig("nowhere", "nowhere", "news.invalid", 119, "user", "secret", useTls = false)
        assertEquals(UsenetError.ConnectionFailed("Unknown host news.invalid"), UsenetEngine.testProvider(config, timeoutMs = 5_000))
    }

    /**
     * The platform's TLS reads the server's plain greeting as a TLS record and refuses it (an SSLException in the
     * handshake): the provider's port doesn't speak TLS.
     */
    @Test
    fun `provider test reports TLS against a plain server as ConnectionFailed`() = runBlocking<Unit> {
        FakeNntpServer().use { server ->
            val error = UsenetEngine.testProvider(server.provider().copy(useTls = true), timeoutMs = 5_000)
            assertIs<UsenetError.ConnectionFailed>(error, "$error")
            server.awaitDisconnect()
        }
    }

    @Test
    fun `provider test times out on a server that never greets`() = runBlocking<Unit> {
        FakeNntpServer(greet = false).use { server ->
            val (error, elapsed) = measureTimedValue { UsenetEngine.testProvider(server.provider(), timeoutMs = 500) }
            assertIs<UsenetError.Timeout>(error)
            assertTrue(elapsed < 3.seconds, "took $elapsed")
            server.awaitDisconnect()
        }
    }
}
