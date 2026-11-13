package io.skjaere.usenet.core

import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A connection that drops once partway through an article (a Wi-Fi blip, a provider closing a
 * connection as it is reused) costs that article a second fetch, not the play or the stream (#167):
 * nzb-streamer fetches it again on a fresh connection (library issue K21). Through the real engine,
 * preparer, opener and pipeline against a [FakeNntpServer]; a connection that keeps dropping is
 * [NzbStreamOpenerTest]'s.
 */
class ConnectionDropTest {
    private val clip = TestClip()
    private val engine = UsenetEngine()
    private val servers = mutableListOf<FakeNntpServer>()

    /** How often each article of the clip was asked for with BODY, by index. */
    private val asked = ConcurrentHashMap<Int, AtomicInteger>()

    @AfterTest
    fun tearDown() {
        engine.close()
        servers.forEach { it.close() }
    }

    /** Serves the clip; the articles [drops] names drop partway the first time each is asked for. */
    private suspend fun serve(vararg drops: Int) {
        val server = FakeNntpServer(has = { it.startsWith("<seg") }, body = { id ->
            val i = clip.index(id)
            val n = asked.computeIfAbsent(i) { AtomicInteger() }.incrementAndGet()
            if (i in drops && n == 1) FakeNntpServer.Body.Dropped(clip.article(i), bytes = 2_000) else FakeNntpServer.Body.Article(clip.article(i))
        })
        servers += server
        engine.configure(EngineSettings(listOf(server.provider())))
    }

    private fun askedFor(i: Int) = asked[i]?.get() ?: 0

    @Test
    fun `a stream whose connection drops once mid-article is whole`() = runBlocking<Unit> {
        serve(3)
        val (received, cause) = withTimeout(30_000) { NzbStreamOpener(engine).open(clip.nzb, 0, 0L until clip.data.size.toLong()).readToEnd() }
        assertNull(cause, "the stream ended with $cause")
        assertContentEquals(clip.data, received)
        assertEquals(2, askedFor(3))
    }

    /**
     * The play sheet's probe reads the start of the picked file as the player would (the app's
     * ExoTrackProbe, through the same opener); its second article drops the first time.
     */
    @Test
    fun `a connection that drops once while a release is opened and probed doesn't end the play`() = runBlocking<Unit> {
        serve(1)
        val probe = TrackProbe { target ->
            val (_, cause) = NzbStreamOpener(engine).open((target as UsenetTarget).prepared, target.file.index, 0L until 2L * clip.segmentSize).readToEnd()
            cause?.let { ProbeResult.Failed(it.toUsenetError()) } ?: ProbeResult.Playable()
        }
        val pipeline = usenetPipeline({ clip.nzbBytes }, EngineAvailabilityBackend(engine), NzbPreparer(EnginePrepareBackend(engine)), probe)
        val played = withTimeout(30_000) { pipeline.play(listOf(PlayCandidate("clip1", "Clip 1"), PlayCandidate("clip2", "Clip 2"))).last() }
        val succeeded = assertIs<PipelineEvent.Succeeded>(played)
        assertEquals(emptyList(), succeeded.failedAttempts)
        assertEquals(clip.data.size.toLong(), succeeded.target.file.size)
        assertEquals(2, askedFor(1))
    }
}
