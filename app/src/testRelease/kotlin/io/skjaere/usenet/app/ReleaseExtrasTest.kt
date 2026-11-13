package io.skjaere.usenet.app

import io.skjaere.usenet.app.ui.FormFactor
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The release (Play) build adds nothing to main's tabs and screens. */
class ReleaseExtrasTest {
    @Test
    fun `the release build has no extras, so its tabs are main's`() {
        assertNull(appExtras)
        FormFactor.entries.forEach { assertEquals(tabsFor(it, extras = null), tabsFor(it), "$it") }
    }
}
