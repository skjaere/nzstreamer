package app.nzstreamer.plugin.sportarr

import androidx.compose.ui.unit.dp
import io.skjaere.usenet.plugin.PluginKeys
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The plugin's tab as the host asks for it once a version loads (W164 a): "Sports", a label the host keeps to its rule,
 * with the cup at the bar's and the rail's 24 by 24. That the entry class is the manifest's, an NzPlugin with a constructor
 * that takes nothing, is plugin-check's rule on every build.
 */
class SportarrPluginTest {
    @Test
    fun `the tab is Sports, with the cup at 24 by 24`() {
        val tab = SportarrPlugin().tab()
        assertEquals("Sports", tab.label)
        assertTrue(PluginKeys.validLabel(tab.label), "the host's rule for a tab's label")
        assertEquals(24.dp, tab.icon.defaultWidth)
        assertEquals(24.dp, tab.icon.defaultHeight)
        assertEquals(24f, tab.icon.viewportWidth)
        assertEquals(24f, tab.icon.viewportHeight)
    }
}
