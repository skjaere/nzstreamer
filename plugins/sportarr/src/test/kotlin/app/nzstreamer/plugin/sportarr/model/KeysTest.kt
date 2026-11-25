package app.nzstreamer.plugin.sportarr.model

import io.skjaere.usenet.plugin.PluginKeys
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * L644's keys, which the host keeps a part's watch state and release under forever: an event's is its externalId where
 * that reads as 1 to 150 letters, digits, dots, underscores and dashes, else local and Sportarr's id; a part's is the
 * event's and the part's id.
 */
class KeysTest {
    @Test
    fun `an event's key is its externalId, and a part's the event's with the part's id`() {
        assertEquals("ev-000731", Keys.event("ev-000731", 731))
        assertEquals("ev-000731:main-card", Keys.part("ev-000731", Parts.MAIN_CARD))
        assertEquals("ev.000_731-B", Keys.event("ev.000_731-B", 731))
        assertEquals("731", Keys.event("731", 12))
        assertEquals("a".repeat(150), Keys.event("a".repeat(150), 12))
    }

    @Test
    fun `an event with no externalId is local, by Sportarr's id`() {
        assertEquals("local:12", Keys.event(null, 12))
        assertEquals("local:12:full", Keys.part(Keys.event(null, 12), Parts.FULL))
    }

    @Test
    fun `an externalId outside the rule falls back to the local key`() {
        listOf("", " ", "ev 731", " ev-000731", "ev-000731 ", "ev:731", "ev/731", "év-731", "ev-731\n", "ev+731", "a".repeat(151))
            .forEach { assertEquals("local:12", Keys.event(it, 12), "[$it]") }
    }

    @Test
    fun `the model keys every event and part, and the host takes each key`() {
        val sports = sports(
            PREMIER to listOf(on(PREMIER, 731, at("2026-11-21T15:00"), "Lantern Bay FC vs Copper Crown United").copy(externalId = "ev-000731")),
            UFC to listOf(ufc999(id = 12).copy(externalId = null)),
            BOXING to listOf(on(BOXING, 40, at("2026-11-21T22:00"), "Quessel vs. Halde").copy(externalId = "x".repeat(150))),
        )

        assertEquals(listOf("ev-000731:full"), sports.event("ev-000731")!!.parts.map { it.key })
        assertEquals(listOf("local:12:early-prelims", "local:12:prelims", "local:12:main-card"), sports.event("local:12")!!.parts.map { it.key })
        val longest = sports.event("x".repeat(150))!!.parts.single().key
        assertEquals("x".repeat(150) + ":full", longest)
        sports.events.flatMap { it.parts }.forEach { assertTrue(PluginKeys.validKey(it.key), it.key) }
        // The longest key the rule allows, with the longest part id, is still one the host takes.
        assertTrue(PluginKeys.validKey(Keys.part("x".repeat(150), Parts.EARLY_PRELIMS)))
    }

    @Test
    fun `an event's keys don't depend on what else the list holds`() {
        val alone = sports(UFC to listOf(ufc999()))
        val among = sports(
            UFC to listOf(ufc999(), on(UFC, 32, at("2026-11-14T23:00"), "UFC Fight Night: Brisk vs. Loam")),
            PREMIER to listOf(on(PREMIER, 33, at("2026-11-21T15:00"), "Lantern Bay FC vs Copper Crown United")),
        )
        val key = Keys.event("ev-000031", 31)
        assertEquals(alone.event(key)!!.parts.map { it.key }, among.event(key)!!.parts.map { it.key })
    }
}
