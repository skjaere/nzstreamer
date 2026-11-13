package io.skjaere.usenet.app

import io.skjaere.usenet.app.settings.NoUsableProvider
import io.skjaere.usenet.core.UsenetError
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Import with no usable provider (#159): it says why at once, rather than after waiting for the engine. */
class ImportNoProviderTest {
    private val steps = mutableListOf<String>()
    private val imported = ImportUi.Error("imported")

    private suspend fun open(missing: NoUsableProvider?) =
        importWhenUsable("Lantern Field", missing, awaitEngine = { steps += "waited" }, import = { steps += "imported"; imported })

    @Test
    fun `without a usable provider Import says why at once, without waiting for the engine`() = runBlocking<Unit> {
        listOf(NoUsableProvider.NoneSaved, NoUsableProvider.AllOff, NoUsableProvider.Unreadable(1)).forEach { missing ->
            assertEquals(ImportUi.NeedsProvider("Lantern Field", missing), open(missing))
        }
        assertEquals(emptyList(), steps)
    }

    @Test
    fun `with a usable provider Import waits for the engine to take it up, then opens the file`() = runBlocking<Unit> {
        assertEquals(imported, open(null))
        assertEquals(listOf("waited", "imported"), steps)
    }

    @Test
    fun `each case says what is wrong and when the file opens`() {
        assertEquals(
            "No Usenet provider is set up yet. Lantern Field opens as soon as one is added.",
            ImportUi.NeedsProvider("Lantern Field", NoUsableProvider.NoneSaved).text,
        )
        assertEquals(
            "Every Usenet provider is switched off. Lantern Field opens as soon as one is switched on.",
            ImportUi.NeedsProvider("Lantern Field", NoUsableProvider.AllOff).text,
        )
        assertEquals(
            "Your Usenet provider's password can't be read here: enter it again. Lantern Field opens once it is.",
            ImportUi.NeedsProvider("Lantern Field", NoUsableProvider.Unreadable(1)).text,
        )
        assertEquals(
            "Your Usenet providers' passwords can't be read here: enter them again. Lantern Field opens once one is.",
            ImportUi.NeedsProvider("Lantern Field", NoUsableProvider.Unreadable(2)).text,
        )
    }

    @Test
    fun `the engine's own not-configured error names the providers screen`() {
        assertEquals("No Usenet provider is set up yet (Settings › Usenet providers).", UsenetError.NotConfigured.describe())
        assertTrue("Usenet providers" in NoUsableProvider.ACTION)
    }
}
