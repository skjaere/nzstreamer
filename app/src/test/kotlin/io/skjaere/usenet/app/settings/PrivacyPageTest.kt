package io.skjaere.usenet.app.settings

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W60, L138 (CsPhoneSettingsCast 3): the Privacy page. A build that came set up with someone's accounts (SEED_DEV_SETTINGS:
 * a private build with preset settings, debug builds) says so above the policy, in words that name no one and stay out of
 * privacy.txt, so the site's page stays equal to it; any other build shows the policy alone. Until the site's page is live
 * (PRIVACY_ONLINE) nothing links it; then a phone reads it online through the browser and a TV shows the address and opens
 * nothing (TV-WB). The page reads the app's own copy alone, so it reads offline (item 29).
 * The policy's debrid parts show while debrid shows and are left out while it is hidden (L211, L192).
 */
class PrivacyPageTest {
    private val policy = "The opening.\n\n## A heading\n\nIts first paragraph,\nwritten on two lines.\n\nIts second.\n"

    /** The page's own files, read as text. */
    private val pageSources = listOf("Privacy.kt", "PrivacyScreen.kt").map { File("src/main/kotlin/io/skjaere/usenet/app/settings", it) }

    /**
     * Any way to the network: Java's, OkHttp's, Ktor's, Coil's and Android's own clients and URL fetches, a web view, and
     * the app's container, whose TMDB, indexer and image clients are made as the app starts (before a device test's hook
     * could catch their fetches). Each as a whole name; a package as an import names it.
     */
    private val network = listOf(
        """^import\s+(java\.net|javax\.net|okhttp3|io\.ktor|coil3?|android\.net\.http|android\.webkit)\.""",
        """\bURL\s*\(""", """\bopenConnection\b""", """\bopenStream\b""", """\b(Http|OkHttp)Client\b""", """\bWebView\b""",
        """\bSocket\b""", """\bAsyncImage\b""", """\bcontainer\b""",
    ).map { Regex(it, RegexOption.MULTILINE) }

    @Test
    fun `the policy's text parses as the page shows it`() {
        assertEquals(
            listOf(PrivacySection(null, listOf("The opening.")), PrivacySection("A heading", listOf("Its first paragraph, written on two lines.", "Its second."))),
            privacySections(policy),
        )
    }

    @Test
    fun `a build set up with someone's accounts says so first`() {
        val page = privacyPage(policy, seeded = true)
        assertEquals(PrivacySection(null, listOf(PRIVACY_SEEDED)), page.first())
        assertEquals(privacySections(policy), page.drop(1))
        assertEquals(
            "This build came set up with the Usenet provider, indexers and TMDB key of the person who gave it to you, so your " +
                "searches and what you play go to them under that person’s accounts.",
            PRIVACY_SEEDED,
        )
    }

    /**
     * L211, L192: the debrid parts the policy marks, the opening's words and a part of their own, show unmarked while
     * debrid shows and are left out while it is hidden, the rest as it is.
     */
    @Test
    fun `hidden, the page leaves out the marked parts, which show unmarked while debrid shows`() {
        val marked = "The opening[debrid], and more[/debrid].\n\n[debrid]## Marked\n\nIts paragraph,\non two lines.\n\n[/debrid]## A heading\n\nIts first.\n"
        val shown = listOf(
            PrivacySection(null, listOf("The opening, and more.")), PrivacySection("Marked", listOf("Its paragraph, on two lines.")),
            PrivacySection("A heading", listOf("Its first.")),
        )
        val hidden = listOf(PrivacySection(null, listOf("The opening.")), PrivacySection("A heading", listOf("Its first.")))
        assertEquals(shown, privacySections(marked, shown = true))
        assertEquals(hidden, privacySections(marked, shown = false))
        assertEquals(hidden, privacyPage(marked, seeded = false, shown = false))
        assertEquals(listOf(PrivacySection(null, listOf(PRIVACY_SEEDED))) + hidden, privacyPage(marked, seeded = true, shown = false))
    }

    @Test
    fun `any other build shows the policy alone`() {
        assertEquals(privacySections(policy), privacyPage(policy, seeded = false))
    }

    @Test
    fun `the seeded paragraph stays out of the policy the site shows`() {
        assertFalse(PRIVACY_SEEDED in File("src/main/assets/privacy.txt").readText())
    }

    /**
     * Item 29, L139: the page shows the APK's own copy and asks no one for it. Its files name no network client, fetch or
     * web view, nor the container's clients; the browser, through openExternalUrl, is the only way out, and only on a tap.
     */
    @Test
    fun `the page fetches nothing, as its files name no way to the network`() {
        pageSources.forEach { assertTrue(it.isFile, "no ${it.absolutePath}") }
        val named = pageSources.flatMap { file -> network.flatMap { it.findAll(file.readText()).toList() }.map { "${file.name}: ${it.value}" } }
        assertEquals(emptyList(), named, "the privacy page would read the network, not only the app's own copy")
    }

    @Test
    fun `the ways to the network are found where they are written`() {
        // The check above would pass on patterns that find nothing.
        listOf(
            "import java.net.URL", "URL(url).openStream()", "url.openConnection()", "HttpClient(OkHttp)", "OkHttpClient.Builder()", "import io.ktor.client.request.get",
            "import coil3.compose.AsyncImage", "WebView(context)", "context.container.tmdb", "import android.webkit.WebView",
        ).forEach { line -> assertTrue(network.any { it.containsMatchIn(line) }, line) }
        listOf("context.assets.open(PRIVACY_ASSET)", "context.openExternalUrl(url, formFactor)", "import android.content.Context").forEach { line ->
            assertFalse(network.any { it.containsMatchIn(line) }, line)
        }
    }

    /** Until the site's page is live, no build links it: no row on a phone, no address on a TV, so nothing to open. */
    @Test
    fun `while the site's page isn't live, neither a phone nor a TV links it`() {
        assertEquals("Privacy", PRIVACY_TITLE)
        assertFalse(PRIVACY_ONLINE, "the site's page isn't live yet")
        assertNull(privacyLink(tv = false), "a phone's Read it online")
        assertNull(privacyLink(tv = true), "a TV's address")
        assertNull(privacyLink(tv = false, online = false))
        assertNull(privacyLink(tv = true, online = false))
    }

    @Test
    fun `once it is live, a phone reads it online, a TV shows the address and opens nothing`() {
        assertEquals(PrivacyLink("Read it online", "nzstreamer.app/privacy", "https://nzstreamer.app/privacy"), privacyLink(tv = false, online = true))
        assertEquals(PrivacyLink("Read it at nzstreamer.app/privacy", line = null, url = null), privacyLink(tv = true, online = true))
    }
}
