package io.skjaere.usenet.app.cast

import io.skjaere.usenet.app.casting.CastDeviceFacts
import io.skjaere.usenet.app.casting.CastDeviceKind
import io.skjaere.usenet.app.casting.castRowLook
import io.skjaere.usenet.app.link.RowLine
import io.skjaere.usenet.data.PairedTv
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * L78, L121, L122 (W50): the Play on sheet's Cast group lists what MediaRouter finds as plain values: Cast devices,
 * enabled and matching Cast's selector, the screens first, then the speakers, then the speaker groups, each by name;
 * never the phone's own route, a Bluetooth one, or one that plays neither picture nor sound (Terms 3.4.5: no Cast device
 * is hidden for what it is). A speaker's line says it plays the sound only; a Cast device at the address of one of your
 * TVs with NZStreamer says it also runs NZStreamer, by the TV's name where the two differ; a busy device's own text wins,
 * as in Google's list. What a route says of its device (its model, address, kind and volume, L153) goes with it, and
 * prints nowhere. The names and addresses are invented.
 */
class CastRowsTest {
    private fun route(
        name: String,
        description: String? = null,
        isDefault: Boolean = false,
        bluetooth: Boolean = false,
        enabled: Boolean = true,
        matches: Boolean = true,
        videoOut: Boolean = true,
        group: Boolean = false,
        model: String? = null,
        address: String? = null,
        fixedVolume: Boolean = false,
        audioOut: Boolean = true,
    ) = CastRoute(
        "route:$name", name, description, isDefault, bluetooth, enabled, matches, videoOut, group, deviceId = "device:$name", model = model, address = address,
        fixedVolume = fixedVolume, audioOut = audioOut,
    )

    private fun tv(name: String, address: String?) = PairedTv("a".repeat(64), name, pairedAt = 0, address = address)

    @Test
    fun `devices that show video are kept, by name`() {
        val rows = CastRows.of(listOf(route("Living room TV"), route("den TV"), route("Kitchen display")))
        assertEquals(listOf("den TV", "Kitchen display", "Living room TV"), rows.map { it.name })
    }

    /** W50, L122: the screens first, then the speakers, then the speaker groups, each by name. */
    @Test
    fun `the screens first by name, then the speakers, then the groups`() {
        val rows = CastRows.of(
            listOf(
                route("Whole house", videoOut = false, group = true), route("hallway speaker", videoOut = false), route("Living room TV"),
                route("Attic speaker", videoOut = false), route("Den TV"), route("Downstairs", videoOut = false, group = true),
                route("Speakers and screen", group = true),
            ),
        )
        assertEquals(listOf("Den TV", "Living room TV", "Attic speaker", "hallway speaker", "Downstairs", "Speakers and screen", "Whole house"), rows.map { it.name })
        assertEquals(
            listOf(CastDeviceKind.Video, CastDeviceKind.Video, CastDeviceKind.Speaker, CastDeviceKind.Speaker, CastDeviceKind.Group, CastDeviceKind.Group, CastDeviceKind.Group),
            rows.map { it.kind },
        )
    }

    @Test
    fun `the phone's own route, Bluetooth, a disabled or other app's route, and one with neither picture nor sound are left out`() {
        val kitchen = route("Kitchen display")
        val hallway = route("Hallway speaker", videoOut = false)
        val rows = CastRows.of(
            listOf(
                route("Phone", isDefault = true, videoOut = false),
                route("Headphones", bluetooth = true, videoOut = false),
                route("Garage TV", enabled = false),
                route("Other app's player", matches = false),
                route("Nothing out", videoOut = false, audioOut = false),
                hallway,
                kitchen,
            ),
        )
        assertEquals(listOf(kitchen, hallway), rows)
    }

    /** W50, L122 (CsPhoneSheetConnected): a speaker's and a group's line, by what it is; a screen has none of its own. */
    @Test
    fun `a speaker and a speaker group say they play the sound only`() {
        assertEquals("Speaker · sound only", CastRows.line(route("Hallway speaker", videoOut = false), emptyList()))
        assertEquals("Speaker group · sound only", CastRows.line(route("Whole house", videoOut = false, group = true), emptyList()))
        assertEquals("Speaker group · sound only", CastRows.line(route("Speakers and screen", group = true), emptyList()))
        assertNull(CastRows.line(route("Kitchen display"), emptyList()))
    }

    /**
     * L121: the Shield in both groups. A Cast device at the address of a TV with NZStreamer says so, with the TV's name
     * where the two differ; never hidden; nothing for another address or none.
     */
    @Test
    fun `a Cast device at one of your TVs' addresses also runs NZStreamer`() {
        val tvs = listOf(tv("Bedroom TV", "192.168.1.40"), tv("Living room TV", "192.168.1.31"))
        assertEquals("Also runs NZStreamer", CastRows.line(route("Living room TV", address = "192.168.1.31"), tvs))
        assertEquals("Also runs NZStreamer: Living room TV", CastRows.line(route("SHIELD", address = "192.168.1.31"), tvs))
        assertNull(CastRows.line(route("Kitchen display", address = "192.168.1.32"), tvs))
        assertNull(CastRows.line(route("Kitchen display"), tvs), "a device that says no address")
        assertNull(CastRows.line(route("Kitchen display", address = "192.168.1.32"), listOf(tv("Old TV", null))), "a TV this phone has no address for")
    }

    /** A busy device's own text wins while it is busy (L121): "Casting Default Media Receiver". */
    @Test
    fun `a busy device's own line wins`() {
        val tvs = listOf(tv("Living room TV", "192.168.1.31"))
        assertEquals("Casting Default Media Receiver", CastRows.line(route("Living room TV", "Casting Default Media Receiver", address = "192.168.1.31"), tvs))
        assertEquals("Casting Default Media Receiver", CastRows.line(route("Hallway speaker", "Casting Default Media Receiver", videoOut = false), tvs))
        assertEquals("Also runs NZStreamer", CastRows.line(route("Living room TV", "  ", address = "192.168.1.31"), tvs), "a blank text is none")
    }

    /** L153: what MediaRouter's route says of the device goes with it, so a session, and a play, carry the device's facts. */
    @Test
    fun `the device's facts travel with its route`() {
        val kitchen = route("Kitchen display", model = "Chromecast Ultra", address = "192.168.1.31", fixedVolume = true)
        assertEquals(CastDeviceFacts("device:Kitchen display", "Chromecast Ultra", "192.168.1.31", CastDeviceKind.Video, fixedVolume = true), kitchen.facts)
        assertEquals(CastDeviceFacts("device:Den TV", null, null, CastDeviceKind.Video, fixedVolume = false), route("Den TV").facts, "a device that says none")
        assertEquals(CastDeviceKind.Speaker, route("Hallway speaker", videoOut = false).facts.kind)
        assertEquals(CastDeviceKind.Video, route("Kitchen display", audioOut = false).kind, "a screen, whatever its sound")
        assertEquals(CastDeviceKind.Group, route("Whole house", videoOut = false, group = true).facts.kind)
        assertEquals(CastDeviceKind.Group, route("Speakers and screen", group = true).kind, "a group, whatever its members show")
    }

    /** The model and the address come from the network, and the name is the user's: none of them reaches a log by a slip. */
    @Test
    fun `nothing of a device's facts prints`() {
        val kitchen = route("Kitchen display", model = "Chromecast Ultra", address = "192.168.1.31", fixedVolume = true)
        for (printed in listOf(kitchen.toString(), kitchen.facts.toString())) {
            for (fact in listOf("Kitchen display", "Chromecast Ultra", "192.168.1.31", "device:")) assertFalse(fact in printed, "$fact in $printed")
        }
        assertEquals("CastDeviceFacts(Video)", kitchen.facts.toString())
    }

    @Test
    fun `a busy device's line is its own text, and an idle one has none`() {
        val den = route("Den TV", description = "Casting Default Media Receiver")
        val (busy) = CastRows.of(listOf(den))
        assertEquals(RowLine("Casting Default Media Receiver", RowLine.Tone.Muted), castRowLook(busy.id, busy.description, now = null, failed = false).line)
        assertNull(castRowLook("route:Kitchen display", null, now = null, failed = false).line)
        assertNull(castRowLook("route:Kitchen display", "  ", now = null, failed = false).line, "a blank text is none")
    }
}
