package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.ui.FormFactor
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Downloads show on phones and tablets only (W108, L369), by one rule every way in asks: the phone's layout on a device that
 * isn't a TV. A debug build's TV layout on a phone, or a phone's layout forced on a TV, shows none either.
 */
class DownloadPlacesTest {
    @Test
    fun `the phone layout on a phone or tablet shows downloads`() {
        assertTrue(DownloadPlaces.shown(FormFactor.Phone, television = false))
    }

    @Test
    fun `the TV layout shows none, on a TV or on a phone showing it`() {
        assertFalse(DownloadPlaces.shown(FormFactor.Tv, television = true))
        assertFalse(DownloadPlaces.shown(FormFactor.Tv, television = false))
    }

    @Test
    fun `a TV with the phone layout shows none`() {
        assertFalse(DownloadPlaces.shown(FormFactor.Phone, television = true))
    }
}
