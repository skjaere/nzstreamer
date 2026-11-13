package io.skjaere.usenet.app.cast

import android.content.Context
import android.content.ContextWrapper
import io.skjaere.usenet.app.castExtras
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * Every build carries Google Cast's extras (C2, L105): the release (Play) build and both betas too, as debug and bench.
 * Main reads whether Play services is there through them (L146), with the gate's own check, which starts nothing.
 */
class CastExtrasTest {
    @Test
    fun `every build has Google Cast's extras`() {
        assertNotNull(castExtras)
        assertSame(GoogleCast, castExtras)
    }

    @Test
    fun `Play services is read through the gate's check, and nothing starts`() {
        val realAvailability = CastGate.availability
        val realStarter = CastGate.starter
        val starts = AtomicInteger()
        try {
            CastGate.starter = object : CastStarter {
                override fun start(context: Context) {
                    starts.incrementAndGet()
                }

                override fun failed(context: Context) = false
            }
            for (answer in listOf(true, false)) {
                CastGate.availability = CastAvailability { answer }
                assertEquals(answer, castExtras?.available(ContextWrapper(null)))
            }
            assertEquals(0, starts.get())
        } finally {
            CastGate.availability = realAvailability
            CastGate.starter = realStarter
        }
    }
}
