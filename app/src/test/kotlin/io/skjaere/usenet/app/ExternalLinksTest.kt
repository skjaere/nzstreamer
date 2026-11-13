package io.skjaere.usenet.app

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import io.skjaere.usenet.app.ui.FormFactor
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalLinksTest {
    private val url = "https://developer.themoviedb.org/docs/getting-started"

    /** Stands in for an Activity on a device where nothing, or something, handles http(s) VIEW intents. */
    private class FakeContext(private val hasBrowser: Boolean) : ContextWrapper(null) {
        var started = 0

        override fun startActivity(intent: Intent?) {
            if (!hasBrowser) throw ActivityNotFoundException("No Activity found to handle Intent")
            started++
        }
    }

    @Test
    fun `no app for web links returns false instead of crashing`() {
        val context = FakeContext(hasBrowser = false)
        assertFalse(context.openExternalUrl(url, FormFactor.Phone))
    }

    @Test
    fun `a device with a browser starts it and returns true`() {
        val context = FakeContext(hasBrowser = true)
        assertTrue(context.openExternalUrl(url, FormFactor.Phone))
        assertEquals(1, context.started)
    }

    /** TV-WB: a browser a TV may have is no way to read a page from the sofa; the caller shows the address. */
    @Test
    fun `on a TV nothing is opened, even with a browser, so the caller shows the address`() {
        val context = FakeContext(hasBrowser = true)
        assertFalse(context.openExternalUrl(url, FormFactor.Tv))
        assertEquals(0, context.started)
    }

    /**
     * Each place that opens a link, with how it shows the address when that returns false (on a TV,
     * or with no browser). A new one fails here until it is listed with its fallback.
     */
    private val callSites = mapOf(
        "settings/MetadataScreen.kt" to "noBrowser: the TMDB key help's address as text",
        "settings/PrivacyScreen.kt" to
            "once the site's page is live (PRIVACY_ONLINE): the address under Read it online, noBrowser's line, and on a TV the address alone (privacyLink)",
    )

    @Test
    fun `every link opened shows its address when it can't be`() {
        val sources = File("src/main/kotlin/io/skjaere/usenet/app")
        val found = sources.walk().filter { it.extension == "kt" }
            .flatMap { file -> Regex("""(?<!fun Context)\.openExternalUrl\(""").findAll(file.readText()).map { file.relativeTo(sources).path } }
            .toList()
        assertTrue(found.isNotEmpty(), "no call sites found under ${sources.absolutePath}")
        assertEquals(callSites.keys.sorted(), found.sorted(), "openExternalUrl call sites; list a new one with how it shows the address")
    }
}
