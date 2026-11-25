package app.nzstreamer.plugin.sportarr.source

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L642's table (W163 a): a league counts when it is switched on in Sportarr; in one that counts, an event counts as the
 * league's route gives it, monitored or not, but for scope None with a monitored event (only monitored events: a promotion
 * added for one fighter) and Keep every game (only monitored events and events with a file); a missing or unreadable
 * unguaranteed field leaves the route's answer as it is.
 */
class MembershipTest {
    private val monitored = event(1, AT, monitored = true)
    private val switchedOff = event(2, AT, monitored = false)
    private val withFile = event(3, AT, monitored = false, hasFile = true)
    private val answer = listOf(monitored, switchedOff, withFile)

    private fun ids(events: List<WireEvent>) = events.map { it.id }

    @Test
    fun `a league switched off in Sportarr is never read`() {
        assertFalse(Membership.counts(league(1, monitored = false)))
        assertFalse(Membership.counts(league(1, monitored = false, monitorType = "All")))
        assertTrue(Membership.counts(league(1, monitored = true, monitorType = "None")))
    }

    @Test
    fun `scope None with a monitored event lists only the monitored`() {
        // A monitored event in the answer.
        assertEquals(listOf(1), ids(Membership.events(league(1, monitorType = "None"), answer)))
        // Ignoring case, and with Sportarr's count where the answer holds none of them.
        assertEquals(listOf(1), ids(Membership.events(league(1, monitorType = "none"), answer)))
        assertEquals(emptyList(), ids(Membership.events(league(1, monitorType = "NONE", monitoredEventCount = 2), listOf(switchedOff, withFile))))
    }

    @Test
    fun `scope None with no monitored event is the route's answer`() {
        val none = listOf(switchedOff, withFile)
        assertEquals(listOf(2, 3), ids(Membership.events(league(1, monitorType = "None", monitoredEventCount = 0), none)))
        assertEquals(listOf(2, 3), ids(Membership.events(league(1, monitorType = "None", monitoredEventCount = null), none)))
    }

    @Test
    fun `a Future league lists its monitored and its switched-off games alike`() {
        assertEquals(listOf(1, 2, 3), ids(Membership.events(league(1, monitorType = "Future", monitoredEventCount = 1), answer)))
        assertEquals(listOf(1, 2, 3), ids(Membership.events(league(1, monitorType = "All"), answer)))
        assertEquals(listOf(1, 2, 3), ids(Membership.events(league(1, monitorType = "Kestrel"), answer)))
    }

    @Test
    fun `no scope at all with mixed events lists them all`() {
        assertEquals(listOf(1, 2, 3), ids(Membership.events(league(1, monitorType = null), answer)))
        assertEquals(listOf(1, 2, 3), ids(Membership.events(league(1, monitorType = ""), answer)))
    }

    @Test
    fun `Keep every game lists the monitored and those with a file, not the rest`() {
        assertEquals(listOf(1, 3), ids(Membership.events(league(1, keepAllEvents = true), answer)))
        assertEquals(listOf(1, 3), ids(Membership.events(league(1, monitorType = "Future", keepAllEvents = true), answer)))
        assertEquals(listOf(1), ids(Membership.events(league(1, monitorType = "None", keepAllEvents = true), answer)))
        assertEquals(listOf(1, 2, 3), ids(Membership.events(league(1, keepAllEvents = false), answer)))
    }

    @Test
    fun `fields Sportarr doesn't promise, missing or unreadable, leave the route's answer`() {
        val bare = WireLeague(1, null, "Kestrel Baseball League", "Baseball", true)
        assertTrue(Membership.counts(bare))
        assertEquals(listOf(1, 2, 3), ids(Membership.events(bare, answer)))

        val unreadable = assertSingleLeague(
            """{"id":1,"name":"KCL","sport":"Fighting","monitored":true,"monitorType":["None"],"monitoredEventCount":"one","keepAllEvents":"yes"}""",
        )
        assertEquals(listOf(1, 2, 3), ids(Membership.events(unreadable, answer)))
    }

    @Test
    fun `an empty answer is empty`() {
        assertEquals(emptyList(), Membership.events(league(1, monitorType = "None", monitoredEventCount = 3, keepAllEvents = true), emptyList()))
    }

    private fun assertSingleLeague(json: String): WireLeague = Wire.leagues("[$json]".toByteArray())!!.single()

    private companion object {
        val AT: Instant = Instant.parse("2026-11-21T15:00:00Z")
    }
}
