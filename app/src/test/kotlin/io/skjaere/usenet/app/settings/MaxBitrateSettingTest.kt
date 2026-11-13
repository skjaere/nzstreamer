package io.skjaere.usenet.app.settings

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * L308: the footnote under Maximum bitrate says what the setting does for larger releases on each device (W101): on a TV
 * the larger releases of a quality go first whatever is set; on a phone or tablet they rank higher only once a maximum is
 * set.
 */
class MaxBitrateSettingTest {
    @Test
    fun `on a TV larger releases of the same quality go first`() {
        assertEquals(
            "The speed this device can stream at. Releases above it are tried last. Of the same quality, larger releases go first.",
            MaxBitrateSetting.footnote(television = true),
        )
    }

    @Test
    fun `on a phone larger releases of the same quality rank higher once it is set`() {
        assertEquals(
            "The speed this device can stream at. Releases above it are tried last. Once it is set, larger releases of the same quality rank higher.",
            MaxBitrateSetting.footnote(television = false),
        )
    }
}
