package io.skjaere.usenet.app.casting

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * L153: one INFO line as each Cast session starts, which O4 and M1 read in Settings › Logs: the device's model, its kind
 * and what its route says of its volume (M1-A: "route volume fixed" or "variable", which the slider no longer goes by),
 * never its name or address. The model comes from the network, where any device can set it: it reaches the log on one
 * line, at most 40 characters, and a blank one reads "(unknown)". The names and addresses are invented.
 */
class CastConnectedLineTest {
    @Test
    fun `the line names the model, the kind and the volume`() {
        assertEquals("cast: connected to a SHIELD Android TV (video, route volume variable)", castConnectedLine("SHIELD Android TV", CastDeviceKind.Video, fixed = false))
        assertEquals("cast: connected to a Chromecast Ultra (video, route volume fixed)", castConnectedLine("Chromecast Ultra", CastDeviceKind.Video, fixed = true))
        assertEquals("cast: connected to a Google Nest Mini (speaker, route volume variable)", castConnectedLine("Google Nest Mini", CastDeviceKind.Speaker, fixed = false))
        assertEquals("cast: connected to a Google Cast Group (group, route volume variable)", castConnectedLine("Google Cast Group", CastDeviceKind.Group, fixed = false))
    }

    @Test
    fun `never the device's name or address`() {
        val den = CastDeviceFacts("device-den", "Chromecast", "192.168.1.31", CastDeviceKind.Video, fixedVolume = false)
        val line = castConnectedLine(den.model, den.kind, den.fixedVolume)
        for (fact in listOf("Den TV", "192.168.1.31", "device-den")) assertFalse(fact in line, "$fact in $line")
    }

    @Test
    fun `a model from the network reaches the log on one line, cut at 40 characters`() {
        val spilled = castConnectedLine("Chromecast\ncast: connected to a forged line", CastDeviceKind.Video, fixed = false)
        assertEquals(1, spilled.lines().size, spilled)
        assertEquals("cast: connected to a Chromecastcast: connected to a forged li (video, route volume variable)", spilled)
        val long = "A".repeat(39) + "BCDEF"
        assertEquals("cast: connected to a ${"A".repeat(39)}B (video, route volume variable)", castConnectedLine(long, CastDeviceKind.Video, fixed = false))
        // Bidi overrides would turn the line round on screen: they go too.
        assertEquals("cast: connected to a Chromecast (video, route volume variable)", castConnectedLine("Chrome‮cast", CastDeviceKind.Video, fixed = false))
    }

    @Test
    fun `a blank or missing model reads unknown`() {
        for (model in listOf(null, "", "   ", "\n\t")) {
            assertEquals("cast: connected to a (unknown) (video, route volume fixed)", castConnectedLine(model, CastDeviceKind.Video, fixed = true), "$model")
        }
    }
}
