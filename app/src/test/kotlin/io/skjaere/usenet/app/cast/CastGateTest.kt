package io.skjaere.usenet.app.cast

import android.content.Context
import android.content.ContextWrapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * L50, L67, L111: Google's Cast software starts through the gate alone, at the Play on sheet's opening, in this order:
 * never on a TV; only with Play services present and up to date, asked again at each opening until it is; only once this
 * phone looks for Cast devices ("Look for Cast devices", L112; off, or not read yet, it asks first and starts nothing);
 * then once per process, a failed start final; on the main thread alone. The log says once per process that it started,
 * and the first time an opening finds the flag off that it didn't, naming no device or person. Both seams are fakes here:
 * no JVM test can start Cast.
 */
class CastGateTest {
    private val context: Context = ContextWrapper(null)
    private val checks = AtomicInteger()
    private val starts = AtomicInteger()
    private val lines = CopyOnWriteArrayList<String>()
    @Volatile private var available = false
    @Volatile private var failing = false
    @Volatile private var throwing = false
    @Volatile private var main = true
    private val realAvailability = CastGate.availability
    private val realStarter = CastGate.starter
    private val realMain = CastGate.mainThread
    private val realInfo = CastGate.info

    @BeforeEach
    fun fakes() {
        CastGate.forget()
        CastGate.availability = CastAvailability {
            checks.incrementAndGet()
            available
        }
        CastGate.starter = object : CastStarter {
            override fun start(context: Context) {
                starts.incrementAndGet()
                if (throwing) throw IllegalStateException("not the main process")
            }

            override fun failed(context: Context) = failing
        }
        CastGate.mainThread = { main }
        CastGate.info = { lines += it }
    }

    @AfterEach
    fun real() {
        CastGate.forget()
        CastGate.availability = realAvailability
        CastGate.starter = realStarter
        CastGate.mainThread = realMain
        CastGate.info = realInfo
    }

    @Test
    fun `a TV never checks or starts, looking or not`() {
        available = true
        assertEquals(CastGateState.Television, CastGate.open(context, television = true, looking = true))
        assertEquals(CastGateState.Television, CastGate.open(context, television = true, looking = false))
        assertEquals(CastGateState.Television, CastGate.open(context, television = true, looking = null))
        assertEquals(0, checks.get())
        assertEquals(0, starts.get())
        assertEquals(emptyList(), lines, "a TV says nothing of Cast")
    }

    @Test
    fun `no Play services is unavailable before the flag is asked, and starts nothing`() {
        // L111: Play services first, so a phone without it never shows "Look for Cast devices".
        assertEquals(CastGateState.Unavailable, CastGate.open(context, television = false, looking = false))
        assertEquals(CastGateState.Unavailable, CastGate.open(context, television = false, looking = null))
        assertEquals(0, starts.get())
        assertEquals(2, checks.get())
        assertEquals(listOf("Cast isn't available on this phone: Google Play services is missing or out of date"), lines)
    }

    @Test
    fun `no Play services is unavailable, starts nothing, and is asked again at the next opening`() {
        assertEquals(CastGateState.Unavailable, CastGate.open(context, television = false, looking = true))
        assertEquals(1, checks.get())
        assertEquals(0, starts.get())
        assertEquals(CastGateState.Unavailable, CastGate.open(context, television = false, looking = true))
        assertEquals(2, checks.get(), "asked again: an update may have come meanwhile")
        assertEquals(0, starts.get())
        // Updated since: it starts at this opening.
        available = true
        assertEquals(CastGateState.Started, CastGate.open(context, television = false, looking = true))
        assertEquals(1, starts.get())
    }

    @Test
    fun `off or not read yet, every opening asks first and starts nothing`() {
        available = true
        repeat(3) {
            assertEquals(CastGateState.NotAsked, CastGate.open(context, television = false, looking = false))
            assertEquals(CastGateState.NotAsked, CastGate.open(context, television = false, looking = null))
        }
        assertEquals(0, starts.get())
    }

    @Test
    fun `not read yet, then read as on, starts once`() {
        available = true
        // settings.json not read when the list opened: the group shows its heading alone, then looks as the read lands.
        assertEquals(CastGateState.NotAsked, CastGate.open(context, television = false, looking = null))
        assertEquals(CastGateState.Started, CastGate.open(context, television = false, looking = true))
        assertEquals(CastGateState.Started, CastGate.open(context, television = false, looking = true))
        assertEquals(1, starts.get())
    }

    @Test
    fun `a start after the flag is set, at a later opening`() {
        available = true
        assertEquals(CastGateState.NotAsked, CastGate.open(context, television = false, looking = false))
        assertEquals(CastGateState.NotAsked, CastGate.open(context, television = false, looking = false))
        assertEquals(0, starts.get())
        // "Look for Cast devices" tapped, or turned on in Settings: the next opening starts it.
        assertEquals(CastGateState.Started, CastGate.open(context, television = false, looking = true))
        assertEquals(1, starts.get())
    }

    @Test
    fun `turned off once started, the list asks again, and nothing starts twice`() {
        available = true
        assertEquals(CastGateState.Started, CastGate.open(context, television = false, looking = true))
        // Google's software runs on until the process ends; the list shows the row again at once (L112).
        assertEquals(CastGateState.NotAsked, CastGate.open(context, television = false, looking = false))
        assertEquals(CastGateState.Started, CastGate.open(context, television = false, looking = true))
        assertEquals(1, starts.get())
    }

    @Test
    fun `a passing check starts once, and a later opening starts nothing`() {
        available = true
        assertEquals(CastGateState.Started, CastGate.open(context, television = false, looking = true))
        assertEquals(CastGateState.Started, CastGate.open(context, television = false, looking = true))
        assertEquals(CastGateState.Started, CastGate.open(context, television = false, looking = true))
        assertEquals(1, starts.get())
        assertEquals(1, checks.get(), "not asked again once started")
    }

    @Test
    fun `the start is said once a process, and never names a device or a person`() {
        available = true
        CastGate.open(context, television = false, looking = true)
        CastGate.open(context, television = false, looking = true)
        assertEquals(listOf("cast: Google's Cast software started"), lines)
    }

    @Test
    fun `the flag found off is said once a process, with NotAsked, and not before it is read`() {
        available = true
        assertEquals(CastGateState.NotAsked, CastGate.open(context, television = false, looking = null))
        assertEquals(emptyList(), lines, "not read yet: nothing is known of the flag")
        assertEquals(CastGateState.NotAsked, CastGate.open(context, television = false, looking = false))
        assertEquals(CastGateState.NotAsked, CastGate.open(context, television = false, looking = false))
        assertEquals(listOf("cast: not started: Look for Cast devices is off"), lines)
        CastGate.open(context, television = false, looking = true)
        assertEquals(listOf("cast: not started: Look for Cast devices is off", "cast: Google's Cast software started"), lines)
    }

    @Test
    fun `a failed start is final for the process`() {
        available = true
        assertEquals(CastGateState.Started, CastGate.open(context, television = false, looking = true))
        // Google's software failed as it loaded (an old module): unavailable from the next opening on, never started again.
        failing = true
        assertEquals(CastGateState.Unavailable, CastGate.open(context, television = false, looking = true))
        failing = false
        assertEquals(CastGateState.Unavailable, CastGate.open(context, television = false, looking = true), "final, whatever it says later")
        assertEquals(1, starts.get())
    }

    @Test
    fun `a start that throws is unavailable and final`() {
        available = true
        throwing = true
        assertEquals(CastGateState.Unavailable, CastGate.open(context, television = false, looking = true))
        throwing = false
        assertEquals(CastGateState.Unavailable, CastGate.open(context, television = false, looking = true))
        assertEquals(1, starts.get())
        assertEquals(listOf("Cast isn't available on this phone: its start failed (IllegalStateException)"), lines, "never said as started")
    }

    @Test
    fun `never off the main thread`() {
        available = true
        main = false
        assertFailsWith<IllegalStateException> { CastGate.open(context, television = false, looking = true) }
        assertEquals(0, checks.get())
        assertEquals(0, starts.get())
        // On the main thread it starts as before.
        main = true
        assertEquals(CastGateState.Started, CastGate.open(context, television = false, looking = true))
    }

    @Test
    fun `Cast asks for the Default Media Receiver`() {
        assertEquals("CC1AD845", castParams().receiverApplicationId)
        assertEquals("CC1AD845", RECEIVER_ID)
    }
}
