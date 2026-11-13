package io.skjaere.usenet.app.play

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * TR-11: a play looks its first release up once the engine has checked its providers, which it does before the play's
 * first use when it hasn't since they changed: seconds while one is out of reach. The sheet says so then rather than
 * nothing, but not for a check done at once (the streamer built already, one provider), which it would only flash.
 */
class ProviderCheckNoticeTest {
    @Test
    fun `a wait longer than the grace is said once, a shorter one not at all`() = runTest {
        var said = 0
        assertEquals("checked", sayingIfSlow(500, { said++ }) { delay(499); "checked" })
        assertEquals(0, said)
        assertEquals("checked", sayingIfSlow(500, { said++ }) { delay(10_000); "checked" })
        assertEquals(1, said)
        // Nothing once the wait is over: the sheet has moved on.
        delay(10_000)
        assertEquals(1, said)
    }

    /**
     * What the sheet says while it waits keeps what it said of the release before a retry: the one that stopped playing, or,
     * as another is looked for, the one that played it (as Play on a movie's page searches after its remembered release
     * was skipped as found incomplete before).
     */
    @Test
    fun `the wait's line keeps what the sheet says of the release before`() {
        assertEquals("Checking your providers…", checkingProvidersLine(null))
        assertEquals(
            "The release before stopped playing · trying another · Checking your providers…",
            checkingProvidersLine(Retry(setOf("fake/hl-s01e01-broken"), startPositionMs = 600_000)),
        )
        assertEquals(
            "The release that played it was found incomplete before · Checking your providers…",
            checkingProvidersLine(Retry(setOf("fake/hl-s01e01-broken"), startPositionMs = null, midStream = false, foundIncomplete = true)),
        )
        assertEquals(
            "The release that played it failed · Checking your providers…",
            checkingProvidersLine(Retry(setOf("fake/hl-s01e01-broken"), startPositionMs = null, midStream = false)),
        )
    }

    @Test
    fun `a wait that fails or is cancelled says nothing after it`() = runTest {
        var said = 0
        assertFailsWith<IllegalStateException> { sayingIfSlow(500, { said++ }) { error("no streamer") } }
        val play = launch { sayingIfSlow(500, { said++ }) { awaitCancellation() } }
        delay(100)
        play.cancel()
        delay(1_000)
        assertEquals(0, said)
    }
}
