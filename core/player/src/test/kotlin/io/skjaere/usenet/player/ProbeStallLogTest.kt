package io.skjaere.usenet.player

import org.junit.Test
import kotlin.test.assertEquals

class ProbeStallLogTest {
    /**
     * A shared log names only the threads it knows: OkHttp names its own after the host it calls
     * (an indexer's), and an interceptor added one day would put the app's code on one.
     */
    @Test
    fun `the stall log names only the threads it knows`() {
        val shown = listOf(
            "ExoPlayer:Loader:ProgressiveMediaPeriod", "ExoPlayer:Playback", "track-probe", "DefaultDispatcher-worker-12",
            "nntp-read-timeout", "main", "kotlinx.coroutines.DefaultExecutor",
            "OkHttp https://indexer.example.net/...", "OkHttp indexer.example.net", "pool-3-thread-1",
        ).mapIndexed { i, name -> ExoTrackProbe.shownThreadName(name, 40L + i) }
        assertEquals(
            listOf(
                "ExoPlayer:Loader:ProgressiveMediaPeriod", "ExoPlayer:Playback", "track-probe", "DefaultDispatcher-worker-12",
                // ktor-nntp-client has no threads of its own any more: a thread so named is none of ours.
                "thread 44", "main", "kotlinx.coroutines.DefaultExecutor",
                "thread 47", "thread 48", "thread 49",
            ),
            shown,
        )
    }
}
