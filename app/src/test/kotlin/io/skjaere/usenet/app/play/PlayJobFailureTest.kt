package io.skjaere.usenet.app.play

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** A play sheet's job that throws ends in a failure the sheet shows, not in a crash and a sheet left on "Searching…" (#117). */
class PlayJobFailureTest {
    @Test
    fun `a job that throws ends in a failure for its title, in plain words`() = runBlocking<Unit> {
        val failed = failureOf("Harbor Lights", "S02E05") { throw IOException("database or disk is full") }
        assertEquals(
            // A paired phone is told it in the TV's words, never the exception's.
            PlayState.Failed(
                "Harbor Lights", "S02E05", listOf("Harbor Lights" to "Something went wrong: database or disk is full"), notTried = 0,
                remote = listOf("Something went wrong on the TV"),
            ),
            failed,
        )
    }

    @Test
    fun `a failure without a message still says something went wrong`() = runBlocking<Unit> {
        val failed = failureOf("Lantern Field", null) { throw IllegalStateException() }
        assertEquals(listOf("Lantern Field" to "Something went wrong"), failed?.failures)
    }

    @Test
    fun `a job that finishes has no failure to show`() = runBlocking<Unit> {
        assertNull(failureOf("Lantern Field", null) { })
    }

    @Test
    fun `a job that is cancelled stays cancelled`() = runBlocking<Unit> {
        assertFailsWith<CancellationException> { failureOf("Lantern Field", null) { throw CancellationException("replaced") } }
    }
}
