package io.skjaere.usenet.core

import io.skjaere.nzbstreamer.enrichment.VerificationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.AbstractLongTimeSource
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * Several providers, end to end through the real [UsenetEngine], [NzbStreamOpener] and
 * [EngineAvailabilityBackend] against two [FakeNntpServer]s, configured as the app's provider sync
 * configures them: the usable providers in the saved order, priority = position. An article is
 * asked of the first provider, then of the next one when the first doesn't have it.
 */
class MultiProviderTest {
    private val clip = TestClip()
    private val engine = UsenetEngine()
    private val servers = mutableListOf<FakeNntpServer>()

    @AfterTest
    fun tearDown() {
        engine.close()
        servers.forEach { it.close() }
    }

    private fun server(password: String = "secret", has: (Int) -> Boolean = { true }) = clip.server(password, has = has).also { servers += it }

    /** [this] as the provider named [id]; [password] is what the app would log in with. */
    private fun FakeNntpServer.config(id: String, password: String = "secret") = provider(password).copy(id = id, name = id)

    /** Configures the engine with [providers], tried in that order, and otherwise as the app does. */
    private suspend fun configure(vararg providers: ProviderConfig) =
        engine.configure(EngineSettings(providers.mapIndexed { i, p -> p.copy(priority = i) }))

    /** Streams the whole clip; fails unless it ends plainly. */
    private suspend fun stream(): ByteArray = withTimeout(30_000) {
        val (received, cause) = NzbStreamOpener(engine).open(clip.nzb, 0, 0L until clip.data.size.toLong()).readToEnd()
        assertNull(cause, "the stream ended with $cause")
        received
    }

    private suspend fun check(timeSource: TimeSource = TimeSource.Monotonic): VerificationResult =
        withTimeout(30_000) { EngineAvailabilityBackend(engine, timeSource = timeSource).verify(clip.document) { _, _ -> } }

    /** [check], which must not wait long on a provider that can't serve. */
    private suspend fun promptCheck(): VerificationResult =
        assertNotNull(withTimeoutOrNull(10_000) { check() }, "the article check was still waiting after 10 s")

    /**
     * Plays the clip as [releases] releases (the same NZB each time) through the real check and
     * opening, as the play sheet does; how it ended. None may wait long on a provider that can't serve.
     */
    private suspend fun play(releases: Int = 1): PipelineEvent = playReleases(*Array(releases) { clip.nzbBytes })

    /**
     * Plays the releases whose NZBs are [nzbs] ("clip1", "clip2" and on), in that order, as [play] does: as films, or with
     * [episode] as season packs ([PACK]) to pick that episode from, which are opened before they are checked.
     */
    private suspend fun playReleases(vararg nzbs: ByteArray, episode: FilePicker.Episode? = null): PipelineEvent = assertNotNull(
        withTimeoutOrNull(15_000) {
            val pipeline = usenetPipeline(
                { candidate -> nzbs[candidate.id.removePrefix("clip").toInt() - 1] },
                EngineAvailabilityBackend(engine), NzbPreparer(EnginePrepareBackend(engine)),
                providersLeftOut = { engine.leftOut.value.isNotEmpty() },
                providerFallback = { engine.fallback.value },
            )
            val title = if (episode == null) "Clip" else PACK
            pipeline.play(nzbs.indices.map { PlayCandidate("clip${it + 1}", "$title ${it + 1}") }, episode ?: FilePicker.Largest).last()
        },
        "the play was still going after 15 s",
    )

    /** A server with every article of the clip, in any domain, but those named in [lacks] as they are sent ("<seg3@test>"). */
    private fun serverLacking(vararg lacks: String): FakeNntpServer {
        fun has(id: String) = id.startsWith("<seg") && id !in lacks
        return FakeNntpServer(
            has = ::has,
            body = { id -> if (has(id)) FakeNntpServer.Body.Article(clip.article(clip.index(id))) else FakeNntpServer.Body.Missing },
        ).also { servers += it }
    }

    /** The articles of the clip asked of this server with [command], by index. */
    private fun FakeNntpServer.asked(command: String) =
        commands.filter { it.startsWith("$command <seg") }.map { clip.index(it.substringAfter(' ')) }.sorted()

    @Test
    fun `an article the first provider lacks is fetched from the second and the stream is whole`() = runBlocking<Unit> {
        val first = server(has = { it != 3 })
        val second = server()
        configure(first.config("first"), second.config("second"))
        assertContentEquals(clip.data, stream())
        assertEquals(listOf(0, 1, 2, 3, 4, 5), first.asked("BODY"))
        assertEquals(listOf(3), second.asked("BODY"))
    }

    @Test
    fun `the article check passes when each article is on some provider and finds one missing only when it is on none`() = runBlocking<Unit> {
        val low = server(has = { it < 3 })
        val high = server(has = { it >= 3 })
        configure(low.config("low"), high.config("high"))
        assertEquals(VerificationResult.Success, check())
        assertEquals(listOf(0, 1, 2, 3, 4, 5), low.asked("STAT"))
        assertEquals(listOf(3, 4, 5), high.asked("STAT"))

        val gapLow = server(has = { it < 3 })
        val gapHigh = server(has = { it > 3 })
        configure(gapLow.config("low"), gapHigh.config("high"))
        val missing = assertIs<VerificationResult.MissingArticles>(check())
        assertEquals(1, missing.missingCount)
        assertTrue("seg3@test" in missing.message, missing.message)
    }

    @Test
    fun `swapping the order makes the other provider be asked first`() = runBlocking<Unit> {
        val a = server()
        val b = server()
        configure(a.config("a"), b.config("b"))
        assertContentEquals(clip.data, stream())
        assertEquals(VerificationResult.Success, check())
        assertEquals(listOf(0, 1, 2, 3, 4, 5), a.asked("BODY"))
        assertEquals(listOf(0, 1, 2, 3, 4, 5), a.asked("STAT"))
        assertEquals(emptyList(), b.asked("BODY") + b.asked("STAT"))

        configure(b.config("b"), a.config("a"))
        assertContentEquals(clip.data, stream())
        assertEquals(VerificationResult.Success, check())
        assertEquals(listOf(0, 1, 2, 3, 4, 5), b.asked("BODY"))
        assertEquals(listOf(0, 1, 2, 3, 4, 5), b.asked("STAT"))
        // Nothing more of the one now second.
        assertEquals(listOf(0, 1, 2, 3, 4, 5), a.asked("BODY"))
        assertEquals(listOf(0, 1, 2, 3, 4, 5), a.asked("STAT"))
    }

    @Test
    fun `a first provider that refuses the login doesn't stop the second from serving`() = runBlocking<Unit> {
        val refusing = server(password = "secret")
        val serving = server()
        configure(refusing.config("refusing", password = "wrong"), serving.config("serving"))
        assertContentEquals(clip.data, stream())
        assertTrue(refusing.commands.none { it.startsWith("BODY ") }, "the refusing provider was asked for articles: ${refusing.commands}")
        assertEquals(listOf(0, 1, 2, 3, 4, 5), serving.asked("BODY"))
    }

    /**
     * A provider switched off (or moved, added, deleted) while a play's article check runs: the check
     * goes on with the providers it started with rather than failing as their connections close (S9-7).
     */
    @Test
    fun `a change to the providers while the article check runs leaves the check its providers until it is done`() = runBlocking<Unit> {
        val asked = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val first = server(has = { asked.countDown(); answer.await(10, TimeUnit.SECONDS); true })
        val second = server()
        configure(first.config("first"), second.config("second"))
        val checked = async(Dispatchers.Default) { runCatching { check() } }
        assertTrue(asked.await(10, TimeUnit.SECONDS), "the check never asked for an article")
        configure(first.config("first"))
        answer.countDown()
        assertEquals(VerificationResult.Success, checked.await().getOrThrow())
    }

    // A login the provider rejects fails what waits for its connections at once, as a rejected login
    // (library issues K4, K5): it used to be retried for half a minute, then reported as a connection
    // failure. Here with one provider, whose failure is the play's.

    @Test
    fun `a provider alone that refuses the login fails the check, a play and a stream at once, as a rejected login`() = runBlocking<Unit> {
        val refusing = server()
        configure(refusing.config("refusing", password = "wrong"))
        val failure = assertIs<VerificationResult.Failure>(promptCheck())
        assertIs<UsenetError.AuthFailed>(failure.cause.toUsenetError(), "${failure.cause}")
        val exhausted = assertIs<PipelineEvent.Exhausted>(play(releases = 2))
        assertIs<UsenetError.AuthFailed>(assertIs<AttemptFailure.Provider>(exhausted.failedAttempts.single().second).error)
        assertEquals(1, exhausted.notTried)
        val (_, cause) = withTimeout(10_000) { NzbStreamOpener(engine).open(clip.nzb, 0, 0L until clip.data.size.toLong()).readToEnd() }
        assertIs<UsenetError.AuthFailed>(cause?.toUsenetError(), "the stream ended with $cause")
    }

    // With several providers the engine logs in to each before it uses them, and leaves out one whose
    // login is rejected or that can't be reached while another serves (S9-1, #172): each use would try
    // that login again, or wait for a connection. Anything else is left to nzb-streamer's article
    // check, which moves on from a provider that fails (library issue N33), and to its fetches, which
    // do too (#172, #173).

    @Test
    fun `a first provider that refuses the login holds up neither the article check nor a play`() = runBlocking<Unit> {
        val refusing = server()
        val serving = server()
        configure(refusing.config("refusing", password = "wrong"), serving.config("serving"))
        assertEquals(VerificationResult.Success, promptCheck())
        assertIs<PipelineEvent.Succeeded>(play())
        assertEquals(emptyList(), refusing.asked("STAT") + refusing.asked("BODY"))
        assertEquals(setOf("refusing"), engine.leftOut.value.keys)
    }

    /**
     * ktor-nntp-client retries connecting to a provider that refuses connections (a server down, a
     * wrong port) for about 7 s per connection asked for, twice with the pool's command retry (library
     * issue K24), which the check would wait out before it moves on: 14 s a check (as the test of one
     * gone after the build measures). The engine's check before the build finds out at once, and
     * leaves such a provider out, as one whose host doesn't resolve (#172).
     */
    @Test
    fun `a first provider that can't be reached holds up neither the article check nor a play, and is left out`() = runBlocking<Unit> {
        val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        listOf(
            ProviderConfig("refusing", "refusing", "127.0.0.1", closedPort, "user", "secret", useTls = false),
            ProviderConfig("unknown", "unknown", "news.invalid", 119, "user", "secret", useTls = false),
        ).forEach { unreachable ->
            val serving = server()
            configure(unreachable, serving.config("serving"))
            assertEquals(VerificationResult.Success, promptCheck(), unreachable.id)
            assertIs<PipelineEvent.Succeeded>(play(), unreachable.id)
            assertEquals(setOf(unreachable.id), engine.leftOut.value.keys)
            assertIs<UsenetError.ConnectionFailed>(engine.leftOut.value.getValue(unreachable.id))
        }
    }

    @Test
    fun `a provider that turns the login check away for too many connections is kept, and serves once it can`() = runBlocking<Unit> {
        val busy = server().apply { loginAnswer = "502 Too many connections" }
        val second = server()
        configure(busy.config("busy"), second.config("second"))
        engine.streamer()
        assertTrue(busy.commands.any { it.startsWith("AUTHINFO PASS") }, "the login check never ran")
        assertEquals(emptyMap(), engine.leftOut.value)
        assertEquals(2, assertIs<EngineState.Ready>(engine.state.value).providers.size)
        busy.loginAnswer = null
        assertEquals(VerificationResult.Success, promptCheck())
        assertEquals(listOf(0, 1, 2, 3, 4, 5), busy.asked("STAT"))
    }

    /**
     * While the backup is left out, an article the primary lacks may be on it: the release is
     * incomplete on the providers in use, which the failures say, and isn't held against it for good.
     */
    @Test
    fun `an article the first provider lacks and a second that refuses the login make the release incomplete on the providers in use`() = runBlocking<Unit> {
        val primary = server(has = { it != 3 })
        val backup = server()
        configure(primary.config("primary"), backup.config("backup", password = "wrong"))
        val missing = assertIs<VerificationResult.MissingArticles>(promptCheck())
        assertEquals(1, missing.missingCount)
        // The next release is tried, as for any incomplete one.
        val exhausted = assertIs<PipelineEvent.Exhausted>(play(releases = 2))
        exhausted.failedAttempts.forEach { (_, failure) ->
            assertTrue(assertIs<AttemptFailure.MissingArticles>(failure).providersLeftOut, failure.text)
            assertTrue(failure.text.startsWith("Incomplete on the providers in use"), failure.text)
        }
        assertEquals(2, exhausted.failedAttempts.size)
    }

    /**
     * A provider that passes the login check and stops serving later (an account that ran out
     * mid-session, #173): the article check moves on from it to the next provider at once, so it
     * passes, and nothing is built again; the provider is asked again next time, as it may serve again.
     */
    @Test
    fun `a provider that stops serving is moved past, and asked again next time`() = runBlocking<Unit> {
        val flaky = server()
        val steady = server()
        configure(flaky.config("flaky"), steady.config("steady"))
        assertEquals(VerificationResult.Success, promptCheck())
        val streamer = engine.streamer()
        flaky.refusing = true
        assertEquals(VerificationResult.Success, promptCheck())
        assertEquals(listOf(0, 1, 2, 3, 4, 5), steady.asked("STAT"))
        assertSame(streamer, engine.streamer())
        assertEquals(emptyMap(), engine.leftOut.value)
        val asked = flaky.asked("STAT").size
        assertEquals(VerificationResult.Success, promptCheck())
        assertTrue(flaky.asked("STAT").size > asked, "the provider that stopped serving wasn't asked again")
    }

    /**
     * A provider whose login passed the check before the build and is rejected later (an account that ran out, #173),
     * while the other provider lacks an article of the first release: whether that release is whole is unknown, as the
     * article may be on the one that failed. That fails the release, not the play, and the providers are checked again
     * before the next one, which leaves that one out; the second release, whole on the other provider, plays (#172).
     * Whichever step finds the gap: a film's article check, or a season pack's opening, which comes first.
     */
    @Test
    fun `a provider whose login is rejected after the build fails only the release the others lack an article of`() = runBlocking<Unit> {
        val two = TestClip(domain = "two")
        listOf(null to "<seg3@test>", FilePicker.Episode(1, 1) to "<seg0@test>").forEach { (episode, gap) ->
            val first = server()
            val second = serverLacking(gap)
            configure(first.config("first"), second.config("second"))
            engine.streamer()
            first.loginAnswer = "481 authentication rejected"
            val played = assertIs<PipelineEvent.Succeeded>(playReleases(clip.nzbBytes, two.nzbBytes, episode = episode), "$episode")
            assertEquals("clip2", played.target.candidate.id)
            val (failed, failure) = played.failedAttempts.single()
            assertEquals("clip1", failed.id)
            val incomplete = assertIs<AttemptFailure.MissingArticles>(failure, "$episode")
            assertIs<UsenetError.AuthFailed>(incomplete.unanswered, "$episode")
            assertNull(incomplete.route)
            assertTrue(incomplete.text.startsWith("Incomplete on the providers that answered"), incomplete.text)
            assertEquals(setOf("first"), engine.leftOut.value.keys, "$episode")
        }
    }

    /**
     * The same with the first provider turning logins away for too many connections after the build (another device
     * using the account): it is kept, as it may serve in a moment, and moved past at once meanwhile.
     */
    @Test
    fun `a provider busy after the build fails only the release the others lack an article of`() = runBlocking<Unit> {
        val two = TestClip(domain = "two")
        val busy = server()
        val second = serverLacking("<seg3@test>")
        configure(busy.config("busy"), second.config("second"))
        engine.streamer()
        busy.loginAnswer = "502 Too many connections"
        val played = assertIs<PipelineEvent.Succeeded>(playReleases(clip.nzbBytes, two.nzbBytes))
        assertEquals("clip2", played.target.candidate.id)
        val incomplete = assertIs<AttemptFailure.MissingArticles>(played.failedAttempts.single().second)
        assertIs<UsenetError.ConnectionFailed>(incomplete.unanswered)
        assertEquals(emptyMap(), engine.leftOut.value)
        assertTrue(engine.fallback.value)
    }

    /**
     * A provider gone out of reach after the build holds a check up for as long as its pool tries to connect (14 s and
     * more, library issue K24) before the check moves on to the next provider, which may then have every article: a check
     * that slow has the providers checked again before the next use, which leaves that one out (#172).
     */
    @Test
    fun `a slow article check has the providers checked again before the next use`() = runBlocking<Unit> {
        val clock = Clock()
        val slow = AtomicBoolean()
        val first = server(has = { if (slow.get()) clock.now.addAndGet(EngineAvailabilityBackend.SLOW_CHECK.inWholeMilliseconds); true })
        val second = server()
        configure(first.config("first"), second.config("second"))
        val streamer = engine.streamer()
        assertEquals(VerificationResult.Success, check(clock))
        assertSame(streamer, engine.streamer())
        slow.set(true)
        assertEquals(VerificationResult.Success, check(clock))
        assertNotSame(streamer, engine.streamer())
    }

    /**
     * A provider gone out of reach after the build (its server down, refusing connections): the check
     * waits for its pool to give up connecting before it moves on to the next provider. That is about
     * 7 s of ktor-nntp-client's 10 s connect budget for a refused connect, twice with the pool's
     * command retry (library issue K24), no longer the half minute and more a slow check was measured
     * against. A check held up that long has the providers checked again before the next use, which
     * leaves that one out (#172).
     */
    @Test
    fun `a provider gone out of reach after the build is left out at the next use`() = runBlocking<Unit> {
        val gone = server()
        val serving = server()
        configure(gone.config("gone"), serving.config("serving"))
        val streamer = engine.streamer()
        gone.close()
        val started = TimeSource.Monotonic.markNow()
        assertEquals(VerificationResult.Success, check())
        val took = started.elapsedNow()
        assertNotSame(streamer, engine.streamer(), "a check held up for $took by a provider out of reach left the providers as they were")
        assertEquals(setOf("gone"), engine.leftOut.value.keys)
    }

    /**
     * What a check waits for before it has the streamer, the engine's lock and a build's check of every
     * provider (up to 10 s for one that doesn't answer), isn't the check being slow: counted, a check
     * right after a build that waited on a provider would have the providers checked again at each use.
     */
    @Test
    fun `a build's check of the providers doesn't count towards a slow article check`() = runBlocking<Unit> {
        val clock = Clock()
        val first = server()
        val second = server()
        var builds = 0
        val probing = UsenetEngine(
            factory = { settings -> builds++; UsenetEngine.defaultStreamer(settings) },
            probe = { p -> clock.now.addAndGet(EngineAvailabilityBackend.SLOW_CHECK.inWholeMilliseconds); UsenetEngine.probeProvider(p) },
        )
        probing.use {
            probing.configure(EngineSettings(listOf(first.config("first"), second.config("second")).mapIndexed { i, p -> p.copy(priority = i) }))
            val result = withTimeout(30_000) { EngineAvailabilityBackend(probing, timeSource = clock).verify(clip.document) { _, _ -> } }
            assertEquals(VerificationResult.Success, result)
            probing.streamer()
            assertEquals(1, builds, "the streamer was built again after a check that only waited for the build")
        }
    }

    /** A clock the servers' threads move on: how long a check takes, as the backend measures it. */
    private class Clock : AbstractLongTimeSource(DurationUnit.MILLISECONDS) {
        val now = AtomicLong()

        override fun read() = now.get()
    }

    private companion object {
        /** What a season pack is called: a release that may hold more than the episode to play, opened before it is checked. */
        const val PACK = "Show.S01.1080p.WEB-GRP"
    }
}
