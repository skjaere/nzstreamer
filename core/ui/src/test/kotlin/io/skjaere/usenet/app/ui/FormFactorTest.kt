package io.skjaere.usenet.app.ui

import android.content.res.Configuration
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FormFactorTest {
    @Test
    fun `a TV in its TV mode or one that declares Leanback is a TV`() {
        assertEquals(FormFactor.Tv, FormFactor.of(Configuration.UI_MODE_TYPE_TELEVISION, leanback = false, override = null))
        assertEquals(FormFactor.Tv, FormFactor.of(Configuration.UI_MODE_TYPE_NORMAL, leanback = true, override = null))
        assertEquals(FormFactor.Tv, FormFactor.of(Configuration.UI_MODE_TYPE_TELEVISION, leanback = true, override = null))
    }

    @Test
    fun `anything else is a phone`() {
        listOf(Configuration.UI_MODE_TYPE_NORMAL, Configuration.UI_MODE_TYPE_DESK, Configuration.UI_MODE_TYPE_CAR, Configuration.UI_MODE_TYPE_UNDEFINED).forEach { mode ->
            assertEquals(FormFactor.Phone, FormFactor.of(mode, leanback = false, override = null), "ui mode $mode")
        }
    }

    /** The debug launch extra: a phone emulator shows the TV's layouts, a TV the phone's. */
    @Test
    fun `the launch extra wins, and one it doesn't know is no override`() {
        debugOverride(true) {
            assertEquals(FormFactor.Tv, FormFactor.of(Configuration.UI_MODE_TYPE_NORMAL, leanback = false, override = "tv"))
            assertEquals(FormFactor.Tv, FormFactor.of(Configuration.UI_MODE_TYPE_NORMAL, leanback = false, override = "TV"))
            assertEquals(FormFactor.Phone, FormFactor.of(Configuration.UI_MODE_TYPE_TELEVISION, leanback = true, override = "phone"))
            assertEquals(FormFactor.Tv, FormFactor.of(Configuration.UI_MODE_TYPE_TELEVISION, leanback = false, override = "tablet"))
            assertEquals(FormFactor.Phone, FormFactor.of(Configuration.UI_MODE_TYPE_NORMAL, leanback = false, override = ""))
        }
    }

    /**
     * The extra is a debug build's alone: the app turns [FormFactor.debugOverride] on from its own build as it starts, so a
     * release build, or anything that never says, keeps the device's form factor whatever a launch asks.
     */
    @Test
    fun `the launch extra is read only with the debug override on`() {
        debugOverride(false) {
            assertEquals(FormFactor.Phone, FormFactor.of(Configuration.UI_MODE_TYPE_NORMAL, leanback = false, override = "tv"))
            assertEquals(FormFactor.Tv, FormFactor.of(Configuration.UI_MODE_TYPE_TELEVISION, leanback = true, override = "phone"))
        }
        debugOverride(true) {
            assertEquals(FormFactor.Tv, FormFactor.of(Configuration.UI_MODE_TYPE_NORMAL, leanback = false, override = "tv"))
            assertEquals(FormFactor.Phone, FormFactor.of(Configuration.UI_MODE_TYPE_TELEVISION, leanback = true, override = "phone"))
        }
    }

    /** [block] with [FormFactor.debugOverride] [on], put back after it. */
    private fun debugOverride(on: Boolean, block: () -> Unit) {
        val before = FormFactor.debugOverride
        FormFactor.debugOverride = on
        try {
            block()
        } finally {
            FormFactor.debugOverride = before
        }
    }
}
